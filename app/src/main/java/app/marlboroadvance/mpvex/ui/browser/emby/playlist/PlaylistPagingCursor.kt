package app.marlboroadvance.mpvex.ui.browser.emby.playlist

import app.marlboroadvance.mpvex.domain.emby.EmbyItemsResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/**
 * 播放列表条目的分页游标。
 *
 * ## 为什么必须单独抽一个类
 *
 * 核心是 **`consumed`（服务端已消费的条数）与 `items.size`（本地去重后的条数）
 * 是两个不同的数**，而且两者能差很远：
 *
 * 播放列表里**同一集加两次是常态**（按 A 收藏加一次、按 B 收藏再加一次）。
 * 某一页服务端给了 100 条、其中 8 条与前面重复，那 `items` 只多了 92 条，
 * 但服务端那边已经**消费掉 100 个位置**了。拿 92 当 `StartIndex` ⇒
 * 反复拉回已看过的区间，**永远到不了底**（用户会看到列表无限重复且底部
 * 永远转圈）。
 *
 * ## 为什么要抽出来
 *
 * 这段逻辑必须**能在 JVM 单测里跑**。它原先直接写在 ViewModel 里，而
 * ViewModel 依赖 Android `Application` + Koin —— 单测跑不起来，于是这段
 * 极易出错的偏移量计算没有任何测试保护。抽成纯 Kotlin 类后，
 * [PlaylistPagingCursorTest] 直接测它。
 *
 * 不是线程安全的：只在 ViewModel 的 `viewModelScope`（单线程分派器）里用。
 */
class PlaylistPagingCursor(private val pageSize: Int) {

  /**
   * 已经**向服务端要过**的条数 —— 也就是下一页该用的 `StartIndex`。
   *
   * ⚠️ 与 [items] 的长度分开维护，原因见类注释。
   */
  var consumed: Int = 0
    private set

  /** 本地去重后的条目（顺序 = 服务端顺序）。 */
  var items: List<EmbyItem> = emptyList()
    private set

  /** 还有下一页吗。 */
  var hasMore: Boolean = true
    private set

  /**
   * 「整页都没带来新条目」——服务端在原地打转。
   *
   * 单独记一个标志而不只是看 [hasMore]：日志/调试时能区分
   * 「真的到底了」和「卡住了」，后者是需要用户重新进入页面才恢复的异常态。
   */
  var stalled: Boolean = false
    private set

  /** 首屏加载：清空全部状态再来一遍。 */
  fun reset() {
    consumed = 0
    items = emptyList()
    hasMore = true
    stalled = false
  }

  /**
   * 吃进一页并推进游标。
   *
   * @param result 服务端返回的一页（**可能含重复条目**，不要提前去重 ——
   *   去重后的条数会破坏 [consumed] 与 offset 空间的对应关系）。
   */
  fun accept(result: EmbyItemsResult) {
    val before = items.size

    items = if (items.isEmpty()) {
      // 首屏：整页内部也可能有重复（服务端重复条目）。
      // 不去重的话 LazyColumn 的 key 会重复而崩掉。
      val seen = HashSet<Any>(result.Items.size)
      result.Items.filter { seen.add(it.Id ?: it) }
    } else {
      val seen = items.mapTo(HashSet()) { it.Id ?: it }
      items + result.Items.filter { seen.add(it.Id ?: it) }
    }

    // 服务端给了多少就算多少，与去重结果无关 —— 这是本类的全部意义所在
    consumed += result.Items.size

    val noNewItems = items.size == before && consumed > 0
    stalled = noNewItems && result.Items.isNotEmpty()

    hasMore = when {
      result.Items.isEmpty() -> false
      // 整页没带来任何新条目 ⇒ 服务端在原地打转，别无限翻了
      noNewItems -> false
      // 服务端给了总数：按它判
      result.TotalRecordCount > 0 -> consumed < result.TotalRecordCount
      // 没给总数：靠「本页是否不满」兜底
      else -> result.Items.size >= pageSize
    }
  }

  /**
   * 强制改写当前条目列表，**不动** [consumed] 与 [hasMore]。
   *
   * 用于「本地增删/重排」这类操作：它们只改变列表内容，不影响服务端已消费到哪了。
   *
   * ⚠️ 调用方**必须**把所有直接改 `_entries` 的地方都改走这个方法，
   * 否则 [items] 与 ViewModel 的 StateFlow 会各说各话：下一次翻页
   * `accept()` 会用旧的 [items] 去重，把刚删掉的条目又补回来。
   */
  fun replaceItems(newItems: List<EmbyItem>) {
    items = newItems
  }

  /**
   * 追加一页失败后回滚「判到底」标记。
   *
   * 网络失败 ≠ 没有下一页。若因为失败顺手把 [hasMore] 置 false，用户就再也
   * 拉不动下一页了（页面也不提供手动重试）。
   */
  fun markAppendFailed() {
    if (items.isNotEmpty()) hasMore = true
  }
}