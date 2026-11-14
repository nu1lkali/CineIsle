package app.marlboroadvance.mpvex.ui.browser.emby.discover

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * 搜索页「推荐」模块的取数口径。
 *
 * 四路全是**服务端已有数据**，不在客户端做任何偏好推断：
 * - [FAVORITE] 用户收藏（按收藏时间倒序，最近的先出现）
 * - [LATEST]   最近添加（`DateCreated` 倒序）
 * - [RATED]    高评分（社区评分 ≥ [SearchRecommendViewModel.MIN_RATING]）
 * - [RANDOM]   随机精选（服务端 `SortBy=Random`）
 *
 * 四路按「轮转」混排，不是拼接：拼接会让前 8 条全被收藏占满，
 * 用户看不出这是「混合推荐」（需求：混合推荐、避免重复展示）。
 * 轮转的自然副作用正好是「首次加载每条都有来源差异」，不需要额外打散。
 */
internal enum class RecommendSource(
  /** 送进 `SortBy` 的排序字段 */
  val sortBy: String,
  val sortOrder: String?,
  val favoriteOnly: Boolean = false,
  val minRating: Float? = null,
) {
  FAVORITE(sortBy = "DateCreated", sortOrder = "Descending", favoriteOnly = true),
  LATEST(sortBy = "DateCreated", sortOrder = "Descending"),
  RATED(sortBy = "CommunityRating", sortOrder = "Descending", minRating = 7.0f),
  RANDOM(sortBy = "Random", sortOrder = null),
}

/**
 * 一路推荐源的**游标**：已经翻到第几条、手上还剩哪几条没发出去。
 *
 * 之所以把「池子」也留着，是因为一页要 8 条、一路一次取 8 条，
 * 四路共 32 条里只发出去 8 条 —— 剩下的 24 条必须攥在手里，
 * 否则「加载更多」会把同样的 8 条再问服务端要一遍。
 */
internal class RecommendCursor {
  /** 已经展示过的条目 Id。跨页保留 ⇒ 「避免重复展示」这条要求才真正成立。 */
  val seen = LinkedHashSet<String>()

  /** 各路待发条目（先进先出） */
  val pools = LinkedHashMap<RecommendSource, MutableList<EmbyItem>>()

  /** 各路下一次请求的 `StartIndex`；[EXHAUSTED] = 这一路已经取空 */
  val offsets = LinkedHashMap<RecommendSource, Int>()

  var endReached: Boolean = false

  fun pending(source: RecommendSource): MutableList<EmbyItem> =
    pools.getOrPut(source) { mutableListOf() }

  fun offset(source: RecommendSource): Int = offsets[source] ?: 0

  fun advance(source: RecommendSource, by: Int) {
    offsets[source] = offset(source) + by
  }

  fun markExhausted(source: RecommendSource) {
    offsets[source] = EXHAUSTED
  }

  fun isExhausted(source: RecommendSource): Boolean = offset(source) == EXHAUSTED

  companion object {
    const val EXHAUSTED = -1
  }
}

/**
 * 搜索页推荐结果的**进程内缓存**。
 *
 * ## 为什么要有它
 * 需求写得很明确：「避免每次进入搜索页面都重复请求服务器」。
 * 三件事在搜索页会高频发生 ——
 * 1. 输入关键词（历史/推荐区整块被结果替换，再删空又回到推荐）；
 * 2. 点进详情页再返回（nav3 会把整棵子树销毁重建）；
 * 3. 关掉搜索再打开。
 *
 * 三件事都不该让服务端再算一遍「收藏 + 最近 + 评分 + 随机」四发请求。
 * 所以结果连同**游标一起**存在这里：[RecommendCursor] 也跟着留下，
 * 「加载更多」不会因为页面重建而回到第一页重复拉。
 *
 * ## 什么时候失效
 * - 用户下拉刷新（`refresh`）⇒ 强制重拉，游标重置；
 * - 超过 [TTL_MS]；或换了服务器（键是 serverId）。
 *
 * 只在进程内、不落盘：重启 App 重新拉一次是符合预期的（服务端可能刚加了新片）。
 */
internal object SearchRecommendCache {
  /** 复用窗口：10 分钟。搜索是高频入口，窗口比「冷启动重进」短一些更贴近真实数据。 */
  private const val TTL_MS = 10 * 60 * 1000L

  /** 同时记几份。多服务器 + 多个媒体库，8 份够用且不会攒住大列表。 */
  private const val MAX_ENTRIES = 8

  /**
   * 一条记录对应「一台服务器的一个范围」。
   *
   * [parentId] 为 null = 全库（首页搜索）；非 null = 该媒体库内（媒体库页搜索）。
   * 两者必须分开存：库内推荐与全库推荐是两批完全不同的条目，
   * 共用一个桶会让「先搜首页、再进库里搜」直接看到别的库的片子。
   */
  class Entry(val scope: String, val serverId: Long, val parentId: String?) {
    var items: List<EmbyItem> = emptyList()
    var cursor: RecommendCursor = RecommendCursor()
    var fetchedAt: Long = 0L
    var endReached: Boolean = false
  }

  private val entries = LinkedHashMap<String, Entry>()

  /** 命中且未过期 ⇒ 直接回放，不发请求 */
  @Synchronized
  fun fresh(scope: String): Entry? {
    val entry = entries[scope] ?: return null
    if (entry.items.isEmpty()) return null
    if (SystemClock.elapsedRealtime() - entry.fetchedAt > TTL_MS) return null
    return entry
  }

  /** 取（或建）当前范围的记录。**不看是否过期** —— 过期由调用方决定要不要重拉。 */
  @Synchronized
  fun entry(serverId: Long, parentId: String?): Entry {
    val scope = scopeOf(serverId, parentId)
    val existing = entries[scope]
    if (existing != null) {
      entries.remove(scope)
      entries[scope] = existing
      return existing
    }
    val created = Entry(scope, serverId, parentId)
    entries[scope] = created
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
    return created
  }

  /** 换服务器 / 下拉刷新时把这条丢掉，下次按新数据重算 */
  @Synchronized
  fun invalidate(serverId: Long, parentId: String?) {
    entries.remove(scopeOf(serverId, parentId))
  }

  @Synchronized
  fun invalidateServer(serverId: Long) {
    entries.keys.filter { entries[it]?.serverId == serverId }.forEach { entries.remove(it) }
  }

  private fun scopeOf(serverId: Long, parentId: String?): String =
    "$serverId|${parentId.orEmpty()}"
}

/**
 * 搜索页「推荐」区的 ViewModel。
 *
 * ## 数据来自哪
 * 全部走 [EmbyRepository.getItems]（即 `/Users/{id}/Items`）：
 * 收藏、最近添加、高评分、随机四路各自一次查询，客户端只做**混排与去重**。
 * 没有虚构接口，也没有自己实现「推荐算法」——
 * 排序/评分/收藏标记都是服务端算好的，这里只决定「先后顺序怎么摆」。
 *
 * ## 分页
 * [FIRST_PAGE] 8 条（两列 4 行，符合「首次限制 6~10 条」），
 * 「加载更多」每次 [MORE_PAGE] 条，到 [MAX_ITEMS] 条为止（[UiState.endReached]）。
 * 因为混合页的 offset 空间分属四条流，这里的「下一页」是**先补池子再发**，
 * 池子空了才向后翻 `StartIndex` —— 这一点与全库搜索刻意不分页是同一类取舍。
 */
class SearchRecommendViewModel(
  application: Application,
) : AndroidViewModel(application) {

  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)

  data class UiState(
    val items: List<EmbyItem> = emptyList(),
    /** 首次加载（还没有任何数据可显示） */
    val loading: Boolean = false,
    /** 下拉刷新进行中 */
    val refreshing: Boolean = false,
    /** 加载更多进行中 */
    val loadingMore: Boolean = false,
    /** 失败原因。有旧数据时不清空列表，只在刷新时提示 */
    val error: String? = null,
    val endReached: Boolean = false,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var job: Job? = null
  private var serverId: Long = 0L
  /** 当前推荐的范围：null = 全库（首页搜索），非 null = 该媒体库内（媒体库页搜索） */
  private var parentId: String? = null

  /**
   * 进入页面时调用。
   *
   * [parentId] 限定推荐范围：媒体库页传该库的 Id，首页搜索传 null（全库）。
   * 命中缓存 ⇒ 立即回放（零请求、零骨架）；否则拉第一批。
   * 重复调用是安全的：同一范围正在加载时直接返回。
   */
  fun start(server: EmbyServer?, parentId: String? = null) {
    val current = server ?: return
    // 换服务器或换范围：旧状态整体作废，否则库内搜索会先露出上一台服务器 / 全库的推荐
    if (!isForServer(current.id) || this.parentId != parentId) _state.value = UiState()
    serverId = current.id
    this.parentId = parentId
    if (_state.value.loading) return
    val cached = SearchRecommendCache.fresh(scopeKey(current.id, parentId))
    if (cached != null) {
      publish(cached, refreshing = false, loadingMore = false)
      return
    }
    load(current, force = false)
  }

  /** 下拉刷新：强制重拉，游标与已展示内容一起作废 */
  fun refresh(server: EmbyServer?, parentId: String? = null, done: () -> Unit = {}) {
    val current = server ?: run { done(); return }
    serverId = current.id
    this.parentId = parentId
    load(current, force = true, onFinished = done)
  }

  /**
   * 下拉刷新的**挂起版**：给 `PullRefreshBox` 用。
   *
   * 它必须真的等到数据回来才返回 —— 否则转圈动画在请求发出前就转完了，
   * 用户看到「刷了但内容没变」（首页 `refreshHomeAndWait` 是同一条理由）。
   */
  suspend fun refreshAndWait(server: EmbyServer?, parentId: String? = null) {
    val current = server ?: return
    job?.cancel()
    if (!isForServer(current.id) || this.parentId != parentId) _state.value = UiState()
    serverId = current.id
    this.parentId = parentId
    doLoad(current, force = true)
  }

  /** 失败后重试：等同于刷新，但走 loading 态（此时列表是空的） */
  fun retry(server: EmbyServer?, parentId: String? = null) {
    val current = server ?: return
    this.parentId = parentId
    load(current, force = true)
  }

  /** 加载更多：到底了、正在加载、还什么都没加载出来时都不发请求 */
  fun loadMore(server: EmbyServer?, parentId: String? = null) {
    val current = server ?: return
    val snapshot = _state.value
    if (snapshot.loading || snapshot.loadingMore || snapshot.endReached) return
    if (snapshot.items.isEmpty()) return
    // 首次加载 / 刷新还在跑：这时「下一页」的游标正在被改写，插一脚只会拿到一半的状态
    if (job?.isActive == true) return
    if (!isForServer(current.id) || this.parentId != parentId) return
    viewModelScope.launch {
      _state.value = _state.value.copy(loadingMore = true, error = null)
      val entry = SearchRecommendCache.entry(current.id, parentId)
      val next = runCatching { buildPage(current, parentId, entry.cursor, MORE_PAGE) }
      next.onSuccess { page ->
        if (page.isNotEmpty()) {
          entry.items = entry.items + page
          entry.fetchedAt = SystemClock.elapsedRealtime()
        }
        // 到底的两种情形在这里已经统一（四路取空 / 触到 [MAX_ITEMS] 上限），
        // 直接把游标的结论搬过来即可；再要求 page 为空会让触顶那一页之后
        // 还多发一次注定为空的请求。
        entry.endReached = entry.cursor.endReached
      }
      // 已经有内容时「加载更多失败」不整屏报错：把手上这批留住，用户还能继续点
      _state.value = _state.value.copy(
        items = entry.items,
        loadingMore = false,
        endReached = entry.endReached,
      )
    }
  }

  private fun load(
    server: EmbyServer,
    force: Boolean,
    onFinished: () -> Unit = {},
  ) {
    job?.cancel()
    job = viewModelScope.launch {
      doLoad(server, force)
      onFinished()
    }
  }

  /** 真正的取数（不自己开协程）：[load] 与 [refreshAndWait] 共用同一份逻辑 */
  private suspend fun doLoad(
    server: EmbyServer,
    force: Boolean,
  ) {
    val scope = parentId
    _state.value = _state.value.copy(
      loading = !force && _state.value.items.isEmpty(),
      refreshing = force,
      error = null,
    )
    if (force) SearchRecommendCache.entry(server.id, scope).let {
      // 刷新只重置「翻到哪了」，不清旧内容 —— 请求失败时用户手上还剩一屏，
      // 比整屏换成错误态体面得多（成功时反正会被新的一页覆盖）
      it.cursor = RecommendCursor()
      it.endReached = false
    }
    val entry = SearchRecommendCache.entry(server.id, scope)
    runCatching { buildPage(server, scope, entry.cursor, FIRST_PAGE) }
      .onSuccess { page ->
        entry.items = page
        entry.fetchedAt = SystemClock.elapsedRealtime()
        entry.endReached = entry.cursor.endReached
        publish(entry, refreshing = false, loadingMore = false)
      }
      .onFailure { error ->
        // 刷新失败时如果手上还有旧数据，就留着旧数据 + 一句提示，不要整屏空掉
        _state.value = _state.value.copy(
          items = entry.items,
          loading = false,
          refreshing = false,
          loadingMore = false,
          error = describe(error),
        )
      }
  }

  private fun publish(
    entry: SearchRecommendCache.Entry,
    refreshing: Boolean,
    loadingMore: Boolean,
  ) {
    _state.value = UiState(
      items = entry.items,
      loading = false,
      refreshing = refreshing,
      loadingMore = loadingMore,
      error = null,
      endReached = entry.endReached,
    )
  }

  private fun isForServer(id: Long): Boolean = serverId == 0L || serverId == id

  private fun scopeKey(serverId: Long, parentId: String?): String =
    "$serverId|${parentId.orEmpty()}"

  /**
   * 组一页：先把各路池子里剩下的按轮转发完，不够再向后翻页补池子。
   *
   * 四路全失败 ⇒ 抛出去给 UI 显示「重试」；只要有一路有数据就正常出内容
   * （收藏为空、评分不足在真实库里都很常见，不该被当成错误）。
   */
  private suspend fun buildPage(
    server: EmbyServer,
    parentId: String?,
    cursor: RecommendCursor,
    want: Int,
  ): List<EmbyItem> {
    // 总量封顶：推荐区是「入口导流」，不是无限画廊。
    // `seen` 只在真正发出去时增长 ⇒ 已发条数就是 seen.size，差额即剩余额度。
    val remaining = (MAX_ITEMS - cursor.seen.size).coerceAtLeast(0)
    if (remaining == 0) {
      cursor.endReached = true
      return emptyList()
    }
    val target = minOf(want, remaining)
    val out = ArrayList<EmbyItem>(target)
    var failures = 0
    var rounds = 0
    while (out.size < target && !cursor.endReached && rounds <= MAX_REFILL_ROUNDS) {
      out += drain(cursor, target - out.size)
      if (out.size >= target) break
      if (cursor.endReached) break
      // 四路都取空了 ⇒ 后面再翻也是空，直接收尾
      if (RecommendSource.entries.all { cursor.isExhausted(it) }) {
        cursor.endReached = true
        break
      }
      var added = 0
      for (source in RecommendSource.entries) {
        if (cursor.isExhausted(source)) continue
        val page = fetch(server, parentId, source, cursor.offset(source), POOL_PAGE)
        page.onSuccess { items ->
          if (items.isEmpty()) {
            cursor.markExhausted(source)
          } else {
            cursor.pending(source).addAll(items)
            cursor.advance(source, items.size)
            added += items.size
          }
        }.onFailure { failures++ }
      }
      if (added == 0) {
        // 这一轮一条都没补到：要么全失败，要么全空 —— 都是「没有下一页」了
        if (failures > 0 && out.isEmpty()) {
          throw IllegalStateException("推荐内容加载失败")
        }
        cursor.endReached = true
      }
      rounds++
    }
    // 额度刚好吃满：这一页发完就到顶，别让下一次「加载更多」空跑一趟
    if (cursor.seen.size >= MAX_ITEMS) cursor.endReached = true
    return out
  }

  /**
   * 从各路池子里按「一路一条」轮转取 [want] 条。
   *
   * 取完就 `removeAt(0)`：同一批条目只会被发出去一次，
   * 配合 [RecommendCursor.seen] 形成两道去重（跨源重复 + 跨页重复）。
   *
   * ⚠️ 没有主图的条目在这里**直接丢掉**（用户要求：只推荐有 Primary 的）。
   * 放在取的时候过滤而不是请求时过滤，是为了让 `StartIndex` 仍然按服务端返回的
   * 原始条数前进 —— 否则「本页 8 条里 3 条没图」会让游标少走 3 条，
   * 下一轮把同样的 3 条再拉一遍，翻到底也翻不完。
   */
  private fun drain(cursor: RecommendCursor, want: Int): List<EmbyItem> {
    val out = ArrayList<EmbyItem>(want)
    while (out.size < want) {
      var tookAny = false
      for (source in RecommendSource.entries) {
        if (out.size >= want) break
        val pool = cursor.pools[source] ?: continue
        while (pool.isNotEmpty()) {
          val candidate = pool.removeAt(0)
          val id = candidate.Id
          if (id == null) continue
          if (!candidate.hasUsablePrimary()) continue
          if (cursor.seen.add(id)) {
            out += candidate
            tookAny = true
            break
          }
        }
      }
      if (!tookAny) break
    }
    return out
  }

  /**
   * 能否拿来当推荐卡：必须有可用的主图。
   *
   * 两种都算「有 Primary」：
   *  1. 自己带主图（`ImageTags["Primary"]`）—— 绝大多数影片；
   *  2. 主图「借」在别的条目上（`PrimaryImageItemId`）—— 相册 / 部分文件夹。
   *
   * 这两条之外一律不发：卡片上没有封面只剩一块空白，比少推荐几条更难看。
   */
  private fun EmbyItem.hasUsablePrimary(): Boolean =
    ImageTags["Primary"] != null || !PrimaryImageItemId.isNullOrBlank()

  private suspend fun fetch(
    server: EmbyServer,
    parentId: String?,
    source: RecommendSource,
    startIndex: Int,
    limit: Int,
  ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
    runCatching {
      repository.getItems(
        server = server,
        // 库内推荐：把范围钉在这个库上（递归，含子目录），
        // 否则「在某个库里搜」会推荐出别的库的片子
        parentId = parentId,
        includeItemTypes = MIX_TYPES,
        sortBy = source.sortBy,
        sortOrder = source.sortOrder,
        isFavorite = if (source.favoriteOnly) true else null,
        minCommunityRating = source.minRating,
        startIndex = startIndex,
        limit = limit,
      ).Items
    }
  }

  private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: "推荐内容加载失败"

  companion object {
    /** 首屏条数：两列 4 行（需求：首次限制 6~10 条） */
    const val FIRST_PAGE = 8

    /** 加载更多每次条数 */
    const val MORE_PAGE = 12

    /**
     * 推荐总量上限（含首屏）。
     *
     * 8 + 12 + 12 + 8 = 40：够翻四屏，又不会让「搜索页空词态」变成无限滚动的画廊 ——
     * 推荐的作用是把人引到内容上，不是替代浏览页。
     * 触顶后 `endReached` 置位，UI 只留一行「已显示全部推荐」，不再发请求。
     */
    const val MAX_ITEMS = 40

    /**
     * 每一路每次向服务端要多少条（四路共 4 × 该值进池子）。
     *
     * 比一页的 8 条多要一些：没有主图的条目会在发牌时被丢掉，
     * 8 条里若有三四条没图，一页就凑不满（多要几条能把这一页补齐）。
     */
    private const val POOL_PAGE = 14

    /** 一轮最多补几次池子：防止某台服务端持续返回空页时死循环 */
    private const val MAX_REFILL_ROUNDS = 3

    /** 高评分线的阈值，与 [RecommendSource.RATED] 的说明一致 */
    const val MIN_RATING = 7.0f

    /** 推荐只收能被播放的两种「作品」类型：单集/合集/演员不是「一部」推荐 */
    private val MIX_TYPES = listOf("Movie", "Series")

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { SearchRecommendViewModel(application) }
    }
  }
}
