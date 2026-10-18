package app.marlboroadvance.mpvex.domain.emby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 「聚合库」的取数引擎：把**多个成员库**的分页流归并成**一个**有序流。
 *
 * ## 为什么不能「各库拉一页、拼完再排」
 * 那样只能看到「每个库的前 N 条」，深翻页时拼出来的是重复片段 ——
 * 用户滑到第 300 条时，早就不在任何一个库的首页上了。
 * 这里的做法是给**每个成员库各开一条泳道（lane）**：
 *
 * - 每条泳道有独立的游标（`nextIndex`）与本地缓冲区（`queue`）；
 * - 每次要出条目时，从各泳道的**队头**里按 [sortBy] 的语义挑「当前最该出现」的那条
 *   （升序取最小、降序取最大，Id 做 tiebreak 保证确定性）；
 * - 哪条泳道缓冲区空了、且还没拉完，就给它续拉下一页（多条同时空则并发续拉）。
 *
 * 服务端对各库分别按同一 SortBy 排序 ⇒ 各泳道内部天然有序 ⇒ 归并结果与
 * 「这些库合并成一个大库后排出来的顺序」一致，且深翻页不重不漏。
 *
 * ## 随机（SortBy=Random）为什么单独一套
 * 随机排序下**游标没有意义**（服务端每次重洗，StartIndex 指过去拿到的不是
 * 「下一批」而是又一次重洗的前几条）。所以随机模式：
 * - 每条泳道用自己的 `Random(seed)` 洗自己刚拉到的页（**固定种子**）；
 * - 出条目时在「非空泳道」里均匀随机挑一条，队头出列 —— 相当于把各库洗过的牌
 *   随机交错发出来；
 * - 按 Id 去重兜底（万一服务端重洗时吐了重复条目），连吐多页全重复才判该泳道到底。
 *
 * 「换一批」= 上层用新 seed 新建一个 pager，语义就是整副牌重洗。
 *
 * ## 容错
 * 单个成员库加载失败（库被删 / 网络抽风）**不拖垮整个页面**：该泳道标记失败，
 * 其余泳道照常出条目；失败原因汇到 [laneErrors]，由界面提示「N 个媒体库加载失败」。
 * 只有**首屏全部泳道都失败**时，上层才该显示错误态（条目为空 + 有错误）。
 *
 * @param loadPage 取一页：(libraryId, startIndex, limit) → 该库的一页。
 *   调用方负责服务器上下文；本类只认结果。
 * @param seed 随机模式的种子（非随机模式忽略）
 */
class AggregateItemPager(
  private val memberLibraryIds: List<String>,
  private val sortBy: String,
  private val sortOrder: String,
  private val seed: Long = 0L,
  private val pageSize: Int = DEFAULT_PAGE_SIZE,
  private val loadPage: suspend (libraryId: String, startIndex: Int, limit: Int) -> EmbyItemsResult,
) {
  private val isRandom = sortBy == "Random"
  private val comparator: Comparator<EmbyItem>? = aggregateComparator(sortBy, sortOrder)
  private val lanes: List<Lane> = memberLibraryIds.map { Lane(it) }
  private val sharedRandom = Random(seed)

  /** 跨泳道按 Id 去重（同一部片可能同时躺在两个库里；随机模式还要防服务端重洗吐重）。
   *  并发集合：首屏各泳道是并发拉的，HashSet 不是线程安全的 */
  private val seenIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

  /** 单成员库的失败原因（库被删 / 权限 / 网络）。首屏全失败时由界面转成错误态。
   *  同上，多泳道并发写 */
  private val _laneErrors: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
  val laneErrors: List<String> get() = _laneErrors

  /** 并发保护：触底续拉与下拉刷新可能同时进来，串行化才不会把同一页拉两遍 */
  private val mutex = Mutex()

  /** 还有没有更多内容（任一泳道有存货、或还有没拉完的泳道） */
  val hasMore: Boolean
    get() = lanes.any { it.queue.isNotEmpty() || (it.canLoad) }

  /**
   * 服务端口径的总数（各成员库 TotalRecordCount 之和）。
   *
   * ⚠️ 这是**估计值**：跨库重复的条目会被去重掉，所以它略大于实际可播条数。
   * 仅用于「共 N 项」这种数量展示，别当精确条数用。
   */
  val estimatedTotal: Int get() = lanes.sumOf { it.total }

  /** 首屏：所有泳道并发拉第一页。单库失败不抛，记入 [laneErrors]。 */
  suspend fun prime() {
    mutex.withLock {
      lanes.forEach { loadLane(it) }
    }
  }

  /**
   * 取接下来 [count] 条归并后的条目；不足则有多少给多少（到底了）。
   *
   * 纯挂起函数，调用方在 IO 上跑。**不要**在主线程调用。
   */
  suspend fun next(count: Int): List<EmbyItem> {
    if (count <= 0) return emptyList()
    mutex.withLock {
      val out = ArrayList<EmbyItem>(count)
      while (out.size < count) {
        if (lanes.none { it.queue.isNotEmpty() || it.canLoad }) break
        // 有泳道见底但没拉完 → 并发续拉。可能连着好几页都是重复（随机模式），
        // 所以用 continue 回到循环顶重新评估，而不是假设这一轮一定有货。
        val needFill = lanes.filter { it.queue.isEmpty() && it.canLoad }
        if (needFill.isNotEmpty()) {
          coroutineScope {
            needFill.map { lane -> async { loadLane(lane) } }.awaitAll()
          }
          continue
        }
        val lane = if (isRandom) pickRandomLane() else pickBestLane() ?: break
        out.add(lane.queue.removeFirst())
      }
      return out
    }
  }

  // ── 泳道 ────────────────────────────────────────────────────────────

  private inner class Lane(val libraryId: String) {
    /** 下一个 StartIndex（按服务端**原始**返回条数推进，不是去重后的条数） */
    @Volatile var nextIndex = 0
    @Volatile var total = 0
    /** 服务端这一页给不满 pageSize ⇒ 这个库拉完了 */
    @Volatile var exhausted = false
    /** 加载失败（不再重试，避免每滚一屏就再撞一次同样的错） */
    @Volatile var failed = false
    /** 连续拉了好几个满页却全是重复条目（随机模式的服务端重洗）⇒ 判到底 */
    @Volatile var emptyPages = 0
    val queue = ArrayDeque<EmbyItem>()
    /** 随机模式下这条泳道自己的洗牌器（固定种子 ⇒ 同一次会话内顺序稳定） */
    val random: Random = Random(seed * 31 + libraryId.hashCode())

    val canLoad: Boolean get() = !exhausted && !failed
  }

  /**
   * 给一条泳道续拉一页。
   *
   * 失败只标记、不上抛：一个坏库不该让整个聚合页打不开。
   */
  private suspend fun loadLane(lane: Lane) {
    if (!lane.canLoad) return
    val start = lane.nextIndex
    try {
      val result = loadPage(lane.libraryId, start, pageSize)
      // 去重键：Id 缺失时用「泳道 + 页内位置」合成一个，避免两个无 Id 条目互相抵消
      val fresh = result.Items.filterIndexed { index, item ->
        val key = item.Id?.takeIf { it.isNotEmpty() } ?: "__syn_${lane.libraryId}_${start}_$index"
        seenIds.add(key)
      }
      if (isRandom) {
        lane.queue.addAll(fresh.shuffled(lane.random))
      } else {
        // 非随机：信任服务端顺序，队头就是这条泳道的下一个候选
        lane.queue.addAll(fresh)
      }
      lane.total = result.TotalRecordCount
      // 游标按**原始**条数推进：去重削掉的不占服务端分页窗口
      // （否则窗口回退，同一页会被反复拉出来）
      lane.nextIndex = start + result.Items.size
      if (result.Items.size < pageSize) {
        lane.exhausted = true
      } else if (fresh.isEmpty()) {
        // 满页但全是重复：随机模式才会走到这。给一点余量后判到底，
        // 防止「永远有新页、永远全是旧片」的死循环。
        lane.emptyPages++
        if (lane.emptyPages >= MAX_EMPTY_PAGES) lane.exhausted = true
      } else {
        lane.emptyPages = 0
      }
    } catch (e: CancellationException) {
      // 页面退出 / 接替加载导致的取消：**必须原样抛**。
      // 吞掉它会把「用户走了」误记成「这个库加载失败」，管理页里就会莫名其妙
      // 多一条失败提示。取消不是错误，是流程。
      throw e
    } catch (e: Exception) {
      lane.failed = true
      val label = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
      _laneErrors.add("媒体库 ${lane.libraryId} 加载失败：$label")
    }
  }

  // ── 挑选 ────────────────────────────────────────────────────────────

  /** 按 sortBy 语义从各泳道队头挑「当前最该出现」的那条（Id 做 tiebreak） */
  private fun pickBestLane(): Lane? {
    val ready = lanes.filter { it.queue.isNotEmpty() }
    if (ready.isEmpty()) return null
    val cmp = comparator ?: return ready.first()
    return ready.minWithOrNull { a, b ->
      val c = cmp.compare(a.queue.first(), b.queue.first())
      if (c != 0) c else a.libraryId.compareTo(b.libraryId)
    }
  }

  /** 随机模式：在非空泳道里均匀挑一条（各泳道内部已按自己的种子洗过） */
  private fun pickRandomLane(): Lane {
    val ready = lanes.filter { it.queue.isNotEmpty() }
    return ready[sharedRandom.nextInt(ready.size)]
  }

  companion object {
    /**
     * 每个成员库一页取多少。
     *
     * 取 60 是权衡：归并时每个泳道至少要缓冲 1 条才有的比，页太小会让
     * 「续拉一页 → 出一个条目」频繁发生（请求数 ≈ 条目数）；太大则首屏等待久、
     * 且单个库里用不上的缓冲白占内存。60 页在百兆局域网约一两次请求一屏。
     */
    const val DEFAULT_PAGE_SIZE = 60

    /** 随机模式下，一个泳道最多容忍多少个「满页但全重复」才判到底 */
    private const val MAX_EMPTY_PAGES = 10

    /**
     * 聚合归并用的比较器 —— 与媒体库页搜索路径的 `applyClientSort` **同一套字段口径**。
     *
     * 两边对不上会出现「聚合页按名称排，搜索结果里却不是这个序」的割裂感。
     * 认不出的 SortBy 返回 null（调用方退回服务端原序，不瞎排）。
     */
    fun aggregateComparator(sortBy: String, sortOrder: String): Comparator<EmbyItem>? {
      val base: Comparator<EmbyItem> = when (sortBy) {
        // 名称统一用 SortName（Emby 内部排序名，能正确处理「第 2 季」这类），取不到再退回 Name
        "SortName" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.SortName ?: it.Name ?: "" }
        // ISO-8601 字符串按字典序比较即等于按时间比较
        "DateCreated" -> compareBy { it.DateCreated ?: "" }
        "PremiereDate" -> compareBy { it.PremiereDate ?: "" }
        "DatePlayed" -> compareBy { it.UserData?.LastPlayedDate ?: "" }
        "ProductionYear" -> compareBy { it.ProductionYear ?: 0 }
        "CommunityRating" -> compareBy { it.CommunityRating ?: -1.0 }
        "CriticRating" -> compareBy { it.CriticRating ?: -1.0 }
        "Runtime" -> compareBy { it.RunTimeTicks ?: 0L }
        // 文件大小：顶层 Size 由 Fields=Size 带回；老服务端可能没有，
        // 退回 MediaSources 里第一个源的大小（实测两者一致），都没有就按 0
        "Size" -> compareBy { it.Size ?: it.MediaSources?.firstOrNull()?.Size ?: 0L }
        "PlayCount" -> compareBy { it.UserData?.PlayCount ?: 0 }
        else -> return null
      }
      return if (sortOrder == "Descending") base.reversed() else base
    }
  }
}
