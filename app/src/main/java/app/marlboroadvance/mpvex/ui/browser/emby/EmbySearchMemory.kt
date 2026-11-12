package app.marlboroadvance.mpvex.ui.browser.emby

import android.os.SystemClock
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter

/**
 * 搜索态在「详情页压栈期间」的留存表（需求：搜索结果返回保持 v1）。
 *
 * ## 为什么需要这一层
 * navigation3 的 `NavDisplay` **只组合栈顶 entry**：被压住那页的组合会被销毁
 * （同一结论见 `EmbyRecommendCache` 的 KDoc）。三个搜索入口的搜索态原本全是页面内
 * `remember`（`EmbyHomeScreen` / `EmbyLibraryScreen` / `CineSearchOverlay`），
 * 于是「搜索 → 点进详情 → 返回」落回浏览态：词、结果、筛选、滚动位置全丢，
 * 用户必须重搜一次。这一层把那份状态搬到组合之外，按票据存取。
 *
 * ## 票据（key）怎么来 —— 这是区分「返回」与「重新进入」的唯一手段
 * 调用方用 `rememberSaveable { UUID }` 生成票据：
 *   · entry 只要还在返回栈上（被详情页压住），同一个票据就会在重新组合时被还原 ⇒ 命中；
 *   · 从首页重新进入同一个库 / 重新打开搜索 = **新的 entry** = 新的票据 ⇒ 必然不命中。
 * 所以这里**不需要**猜「这次是回来还是新进来」，也不存在误恢复上次搜索的问题。
 * 票据本身只占一个字符串（进 Bundle 安全），重状态留在本表里，不进 Bundle。
 *
 * ## 容量与时效
 * - 条目里持有 `EmbyItem` 列表，按 LRU 封顶 [MAX_ENTRIES]；
 * - 超过 [TTL_MS] 未被取用即作废：兜「票据被系统还原、但用户其实隔了很久才回来」，
 *   以及「页面被整体出栈、票据跟着消失但本表还留着一条」这两种残留。
 *
 * [take] 是**一次性**的：读到即清，避免同一条被连着复用两次。
 */
internal object EmbySearchMemory {
  /** LRU 上限。同时在「搜索态」里的页面不会超过这个量级，超出的先丢最久未用的。 */
  private const val MAX_ENTRIES = 8

  /** 复用窗口：票据超过这么久没被取用就作废。 */
  private const val TTL_MS = 10 * 60 * 1000L

  /**
   * 一份搜索态快照。
   *
   * 字段刻意做成「三个页面都能落地」的并集，缺省的走默认值 ——
   * 首页全库搜索没有分页游标，库内搜索没有结果页签，各自只填自己用得上的那些。
   */
  class Snapshot(
    val query: String,
    val items: List<EmbyItem>,
    /**
     * 这次搜索属于哪台服务器。
     *
     * 用途只有一个：**切了服务器就不许复用**。结果是服务端给的、id 也是服务端的，
     * 换一台服务器还回放上一台的结果，用户看到的东西在库里根本不存在。
     * 取用时比对（`takeIf { it.serverId == 当前 server.id }`），对不上按全新进入处理。
     */
    val serverId: Long? = null,
    /** 服务端报的总条数（首页全库搜索不给，留 0） */
    val totalCount: Int = 0,
    /** 已向服务端消耗的条数：续页 StartIndex 只能用它，不能用 items.size（见 `PagedList`） */
    val consumedCount: Int = 0,
    /** 结果是否被上限截断（首页全库搜索专用） */
    val truncated: Boolean = false,
    /** 类型筛选 chip */
    val filters: Set<EmbySearchFilter> = emptySet(),
    /** 库内搜索的「仅本目录」开关 */
    val localOnly: Boolean = false,
    /** 结果分类页签（首页全库搜索专用） */
    val tab: EmbySearchTab = EmbySearchTab.ALL,
    /** 离开时列表的首个可见项下标与偏移，返回后据此接回原位 */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
  )

  private class Entry(
    val snapshot: Snapshot,
    val savedAt: Long,
  )

  /** accessOrder = true ⇒ 读取也算一次使用，淘汰的是最久没被碰过的那条。 */
  private val entries =
    object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
        size > MAX_ENTRIES
    }

  /** 记一份快照。同一票据重复写＝用新的覆盖（取最后一次离开时的状态）。 */
  @Synchronized
  fun attach(key: String, snapshot: Snapshot) {
    purgeExpired()
    if (snapshot.query.isBlank()) {
      // 空词没有「搜索态」可言，留着只会让返回后出现一个空搜索页
      entries.remove(key)
      return
    }
    entries[key] = Entry(snapshot, SystemClock.elapsedRealtime())
  }

  /** 取走一份快照并清除。返回 `null` = 不是「返回」，按全新进入处理。 */
  @Synchronized
  fun take(key: String): Snapshot? {
    purgeExpired()
    return entries.remove(key)?.snapshot
  }

  /** 主动丢弃（例如用户退出搜索态）。 */
  @Synchronized
  fun detach(key: String) {
    entries.remove(key)
  }

  private fun purgeExpired() {
    val now = SystemClock.elapsedRealtime()
    entries.entries.removeAll { now - it.value.savedAt > TTL_MS }
  }
}
