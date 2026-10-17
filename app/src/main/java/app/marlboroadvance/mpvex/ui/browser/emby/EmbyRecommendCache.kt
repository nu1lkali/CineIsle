package app.marlboroadvance.mpvex.ui.browser.emby

import android.os.SystemClock
import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/**
 * 详情页两块推荐区（「推荐」按演员/导演、「同类型」）的**进程内缓存**。
 *
 * 它只为一件事服务：**「点推荐卡 / 演职员 / 类型 chip 进下一层页面，再返回」时，
 * 推荐不要重新取一批**。
 *
 * ── 为什么需要这一层 ────────────────────────────────────────────
 * navigation3 的 `NavDisplay` 只组合当前 entry：被压住的 entry 其**组合会被销毁**，
 * 返回时重新组合 ⇒ 详情页里那些 `remember` 状态全部从零开始。推荐数据本来就放在详情页
 * 这一层（见 `EmbyDetailScreen` 里「推荐区数据」那段注释 —— 那是为了解决「滚动导致
 * LazyColumn 回收 item 而重复请求」），于是它也跟着从零开始：重新请求 + 重新随机。
 *
 * 但**不能无脑缓存**：既定语义是「重新进详情页要换一批」（`loadRecommendations` 与
 * 「同类型」的类型选择都是刻意随机的），一律复用会让推荐永远不变。
 *
 * ── 怎么区分「返回」与「重新进入」────────────────────────────────
 * 两者在组合层面长得一模一样，所以**由压栈方留标记**：
 *   · 详情页准备 push 子页面之前 → [markChildPushed]，给自己这条记录打 `hasChild`；
 *   · 返回后重新组合 → effect 先 [takeForRestore]，命中就复用它的数据并**消费掉标记**；
 *   · 从别处（列表 / 首页）重新进详情页 → 标记不存在 ⇒ 走正常加载，「换一批」照旧。
 *
 * 也正因如此 [takeForRestore] 是**一次性**的：读到即清，避免同一条被连着复用两次。
 *
 * 仅做进程内缓存、不落盘 —— 重启 App 后重新拉取符合预期。容量按 LRU 封顶。
 */
internal object EmbyRecommendCache {
  /** LRU 上限：条目里持着 `EmbyItem` 列表，别无限攒。 */
  private const val MAX_ENTRIES = 24

  /**
   * 复用窗口：`hasChild` 标记超过这么久还没人消费就作废。
   *
   * 兜一个很边角的情况 —— 在子页面里直接「回到首页」，把父详情页连同它的标记一起丢掉，
   * 之后从列表重新进同一条目时标记还在（这时**不该**复用）。超时后按「重新进入」处理；
   * 代价只是偶尔少复用一次，比给出陈旧推荐安全。
   */
  private const val REUSE_WINDOW_MS = 10 * 60 * 1000L

  class Entry {
    var items: List<EmbyItem> = emptyList()
    var genreLabel: String? = null
    var genreItems: List<EmbyItem> = emptyList()

    /** 是否有子页面被压栈（见类注释）。 */
    var hasChild: Boolean = false

    /** 标记时间戳（单调时钟，`elapsedRealtime`，不受用户改系统时间影响）。 */
    private var markedAt: Long = 0L

    fun markChild() {
      hasChild = true
      markedAt = SystemClock.elapsedRealtime()
    }

    fun childStillFresh(): Boolean = SystemClock.elapsedRealtime() - markedAt <= REUSE_WINDOW_MS

    fun clearChild() {
      hasChild = false
    }
  }

  private val entries = LinkedHashMap<String, Entry>()

  private fun key(
    serverId: Long,
    itemId: String,
  ): String = "$serverId/$itemId"

  /** 加载成功后写入（含空结果 —— 空也要存，否则「查不到推荐」的条目返回时会再请求一轮）。 */
  @Synchronized
  fun put(
    serverId: Long,
    itemId: String,
    items: List<EmbyItem>,
    genreLabel: String?,
    genreItems: List<EmbyItem>,
  ) {
    val k = key(serverId, itemId)
    val entry = entries.getOrPut(k) { Entry() }
    entry.items = items
    entry.genreLabel = genreLabel
    entry.genreItems = genreItems
    touch(k, entry)
  }

  /** 详情页 push 子页面**之前**调用：给这一条打上「有子页面」标记。 */
  @Synchronized
  fun markChildPushed(
    serverId: Long,
    itemId: String,
  ) {
    entries[key(serverId, itemId)]?.markChild()
  }

  /**
   * 返回后重新组合时调用：命中（标记存在且未过期）⇒ 返回数据并**消费掉标记**；
   * 否则返回 null，调用方照常重新加载（= 「换一批」）。
   */
  @Synchronized
  fun takeForRestore(
    serverId: Long,
    itemId: String,
  ): Entry? {
    val entry = entries[key(serverId, itemId)] ?: return null
    if (!entry.hasChild) return null
    val fresh = entry.childStillFresh()
    // 无论新不新鲜都消费掉：陈旧标记留着只会误伤下一次真正的「重新进入」。
    entry.clearChild()
    if (!fresh) return null
    if (entry.items.isEmpty() && entry.genreItems.isEmpty()) return null
    return entry
  }

  /** 把刚访问过的条目挪到 LRU 队尾，并裁掉超额的队首。 */
  @Synchronized
  private fun touch(
    key: String,
    entry: Entry,
  ) {
    entries.remove(key)
    entries[key] = entry
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}
