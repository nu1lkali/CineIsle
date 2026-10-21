package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 统一的分页列表状态机（对应清单 1.1「全量列表改真分页」）。
 *
 * ## 为什么需要它
 *
 * 原来每个页面各写一套分页逻辑，结果是**大部分列表根本没在分页**：
 * 首页、搜索结果、收藏页、历史页、标签页、演员页全是「一次性拉全量」。
 * 加上 `EmbyLibraryCache` 是无上限的内存数组，5000 条的库搜一次就把整库塞进内存，
 * 之后每次重组还在全量过滤。
 *
 * 与其每页重写一遍，不如把「加载中 / 还有更多 / 到底了 / 出错了」这四个状态
 * 收成一个类，谁用都一致。
 *
 * ## 状态是可观察的
 *
 * 所有状态都是 [mutableStateOf]，所以在 Composable 里**直接读** `page.loading` /
 * `page.hasMore` / `page.items` 就会自动重组 —— 不需要再往 `mutableStateListOf`
 * 里同步一份（早期版本两边都存，结果就是「数据到了但界面不动」）。
 *
 * ## 典型用法
 *
 * ```kotlin
 * val page = remember { PagedList(keyOf = { it.Id }) }
 * LaunchedEffect(category) {
 *   page.reload { s, l -> viewModel.loadPage(current, category, s, l) }
 * }
 * LazyColumn {
 *   items(page.items, key = { it.Id }) { Card(it) }
 *   item {
 *     when {
 *       page.loading -> CircularProgressIndicator()
 *       page.error != null -> RetryRow(page.error!!) { page.retry(loader) }
 *       page.appending -> SmallSpinner()
 *       else -> Spacer(8.dp)
 *     }
 *   }
 * }
 * // 触底自动续拉
 * LaunchedEffect(gridState, current) {
 *   snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
 *     .collect { last ->
 *       val total = gridState.layoutInfo.totalItemsCount
 *       if (last != null && last >= total - PREFETCH_DISTANCE) page.loadMore(loader)
 *     }
 * }
 * ```
 */
class PagedList<T>(
  /** 一页拉多少条。100 是「请求次数 / 首屏等待」的平衡点。 */
  private val pageSize: Int = DEFAULT_PAGE_SIZE,
  /**
   * 本地去重 / LazyColumn key 用的唯一标识。
   *
   * ⚠️ **必须传**。不传的话每条数据的键都是同一个（见默认实现），
   * 追加时会被误判成「全是重复项」而被过滤掉 —— 表现就是**第二页永远是空的**。
   * 典型传法：`PagedList(keyOf = { it.Id })`。
   */
  private val keyOf: (T) -> Any = { it as? Any ?: 0 },
) {
  /** 已加载的条目。**只装已加载的那些**，不是全库。 */
  var items: List<T> by mutableStateOf(emptyList())
    private set

  /** 首次加载中（此时 [items] 为空，UI 显示骨架 / 转圈）。 */
  var loading: Boolean by mutableStateOf(false)
    private set

  /** 加载下一页中（此时 [items] 非空，UI 在列表底部显示小转圈）。 */
  var appending: Boolean by mutableStateOf(false)
    private set

  /** 服务端报告的总数；null 表示还没拿到（此时只能靠「本页不满」判断到底）。 */
  var total: Int? by mutableStateOf(null)
    private set

  /**
   * 已经**向服务端要过**的条数 —— 也就是下一页该用的 `StartIndex`。
   *
   * ⚠️ 必须和 [items.size] 分开维护。
   *
   * 两者在有重复项时不相等：某一页里服务端返回了 10 条、其中 3 条与前面重复，
   * 那 [items] 只多了 7 条，但服务端那边我们已经**消费掉 10 个位置**了。
   * 如果拿 `items.size`（7 个里的总数）当 `StartIndex`，下一次就会从
   * 「已经看过的位置」重新开始拉 —— 表现为**滚动时反复出现同一批条目**，
   * 而且因为每页都能拉到点新东西，永远到不了底。
   *
   * 用 `loadMore` 自己的计数器就对了：它记的是「服务端给过多少」，
   * 与本地去重完全无关。
   */
  private var consumed: Int = 0
    private set

  /** 最近一次错误。UI 应显示错误态 + 重试，而不是静默空着。 */
  var error: String? by mutableStateOf(null)
    private set

  /** 已经到底（服务端总数已取尽，或某页返回不满）。 */
  var hasMore: Boolean by mutableStateOf(true)
    private set

  /**
   * 下一页的起始下标（= 服务端 `StartIndex`）。
   *
   * 用的是 [consumed]（服务端已返回的原始条数）而不是 [items.size]（去重后），
   * 理由见 [consumed] 的说明。
   */
  fun nextIndex(): Int = consumed

  /**
   * 重新加载第一页（切换分类 / 改筛选 / 改排序时调用）。
   *
   * [loader] 收到起始下标与页大小，返回这一页的数据。返回 `null` 视作「空页」。
   */
  suspend fun reload(loader: suspend (startIndex: Int, limit: Int) -> PagedResult<T>?) {
    loading = true
    error = null
    appending = false
    hasMore = true
    consumed = 0
    try {
      apply(loader(0, pageSize) ?: PagedResult(emptyList(), null, 0), replace = true)
    } catch (e: Exception) {
      // ⚠️ 这里**故意不静默吞**：错误要显示出来 + 给重试按钮。
      // 原来的做法是 catch 掉返回空列表，用户看到的是「库里什么都没有」。
      error = e.message ?: "加载失败"
      items = emptyList()
      total = null
      hasMore = false
      consumed = 0
    } finally {
      loading = false
    }
  }

  /**
   * 加载下一页（用户滚到底 / 点「加载更多」时调用）。
   *
   * 已经在加载或已到底时**直接返回** —— 所以可以放心地在触底回调里无脑调。
   */
  suspend fun loadMore(loader: suspend (startIndex: Int, limit: Int) -> PagedResult<T>?) {
    if (loading || appending || !hasMore) return
    appending = true
    error = null
    val from = nextIndex()
    try {
      val r = loader(from, pageSize) ?: PagedResult(emptyList(), null, 0)
      apply(r, replace = false)
    } catch (e: Exception) {
      error = e.message ?: "加载更多失败"
    } finally {
      appending = false
    }
  }

  /** 出错后重试：首页就重载第一页，否则继续加载下一页。 */
  suspend fun retry(loader: suspend (startIndex: Int, limit: Int) -> PagedResult<T>?) {
    if (items.isEmpty()) reload(loader) else loadMore(loader)
  }

  private fun apply(r: PagedResult<T>, replace: Boolean) {
    // 本页之前已有多少条 —— 用来判断「这一页是不是一条新的都没添进来」。
    // 必须在合并之前取。
    val before = items.size

    // ⚠️ 去重**首屏也要做**，不只是追加时。
    // 服务端单页内返回重复 Id 时，`LazyColumn(items = page.items, key = { it.Id })`
    // 会因为 key 重复直接抛异常崩掉 —— 宁可少显示几条，也不能崩。
    if (replace) {
      val seen = HashSet<Any>(r.items.size)
      items = r.items.filter { seen.add(keyOf(it)) }
      consumed = r.items.size // 服务端给了多少就算多少，与去重结果无关
    } else {
      // 追加时去重：某些服务端（SmartStrm）会忽略 StartIndex 反复返回同一批，
      // 不去重会出现「第二页和第一页一模一样」的错觉。
      val seen = items.mapTo(HashSet(), keyOf)
      items = items + r.items.filter { seen.add(keyOf(it)) }
      consumed += r.items.size
    }
    total = r.totalCount
    // 判断「还有没有」，**优先看服务端总数**。
    // 只有服务端没给总数时才退回「本页不满 pageSize 即到底」——
    // 服务端在同一页里返回了重复项时，去重会让本页看起来不满，用它判断会**提前截断**。
    //
    // ⚠️ 两个比较对象必须分清：
    //   hasMore   比 [items]（用户真正看到的条数）与服务端总数
    //   到底判断  比 [PagedResult.items]（服务端原始返回条数）与 pageSize
    //
    // ⚠️ 「这一页一条新的都没有」也判到底：那是服务端在原地打转
    // （SmartStrm 忽略 StartIndex 时就会这样），再翻下去也是白翻。
    hasMore = when {
      r.exhausted -> false
      items.size == before && consumed > 0 -> false
      r.totalCount != null -> items.size < r.totalCount
      r.items.size < r.pageSize -> false
      else -> true
    }
  }

  companion object {
    const val DEFAULT_PAGE_SIZE = 100

    /**
     * 触底预取距离（**预测性懒加载「开」**）：距列表末尾还剩几格就开始拉下一页。
     * 12 格 ≈ 3 列网格 4 行 / 单列列表约一屏半 —— 数据到得比用户滚到得快，
     * 快速fling 也覆盖得住。
     *
     * ⚠️ 不是唯一距离：设置里的「预测性懒加载」开关（`BrowserPreferences.embyPredictivePreload`）
     * 关时用 [PREFETCH_DISTANCE_OFF]。各页面一律通过
     * `rememberPredictivePreloadDistance(browserPreferences)` 取值，不要硬编码。
     */
    const val PREFETCH_DISTANCE = 12

    /** 预测性懒加载「关」：贴近末尾才拉下一页（≈ 滑到才加载的老行为，最省请求） */
    const val PREFETCH_DISTANCE_OFF = 2
  }
}

/**
 * 一页的结果。
 *
 * @param items 这一页的条目
 * @param totalCount 服务端报告的总数；null = 接口没给
 * @param pageSize 请求时用的页大小（判断「本页是否不满」要用）
 * @param exhausted 服务端明说「没有更多了」；用于某些不给总数的端点
 */
data class PagedResult<T>(
  val items: List<T>,
  val totalCount: Int?,
  val pageSize: Int,
  val exhausted: Boolean = false,
)
