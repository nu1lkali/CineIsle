package app.marlboroadvance.mpvex.domain.dislike

import android.util.Log
import app.marlboroadvance.mpvex.database.dao.DislikeDao
import app.marlboroadvance.mpvex.database.entities.DislikedMediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「不喜欢」标记的仓库 —— [DislikeStore]（内存快照）与 [DislikeDao]（落盘）之间的桥。
 *
 * ## 读路径（UI 用得最多的）
 *
 * 全部走内存，**零 IO**：[isDisliked] / [markOf] / [snapshotOf] 都是同步方法，
 * 切视频时可以在同一帧算出状态。
 *
 * ## 写路径
 *
 * 变更先改内存（UI 立刻响应），再**异步**落盘。落盘做了两件事以免把数据库写坏：
 * · **串行化**（[writeLock]）：多个入口（播放器 / 详情页 / 管理页）可能同时改，
 *   并发 `replaceAll` 会让「先写的后到」把「后写的先到」覆盖掉。
 * · **去抖**（[pending] + [flushJob]）：连续标记时只落最后一次盘。
 *
 * ⚠️ 去抖的等待窗口（[FLUSH_DELAY_MS]）内若进程被杀，这一批变更会丢。
 * 这是刻意的取舍：用户连续标记 5 部片子，逐个立刻写库毫无收益，
 * 而丢掉的代价只在"极端情况下重标一次"。
 */
class DislikeRepository(
  private val dao: DislikeDao,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val store = DislikeStore()

  private val writeLock = Mutex()

  /** 待落盘的最新快照（null = 无待写变更）。受 [writeLock] 保护。 */
  private var pending: List<DislikeMark>? = null
  private var flushJob: kotlinx.coroutines.Job? = null

  init {
    // 任何内存变更都排队落盘（去抖由 flushPending 实现）
    store.onMutate = { snapshot -> schedulePersist(snapshot) }
  }

  /**
   * 管理页与各入口的数据源。与 UI 订阅的是同一份内存快照，不是数据库的 Flow。
   *
   * 刻意暴露成 [StateFlow] 而不是 [Flow]：`collectAsState()` 只接受 StateFlow，
   * 声明成普通 Flow 会让所有 UI 调用点编译不过（而这层转换对读方毫无价值 ——
   * 底层本来就是 StateFlow）。
   */
  val all: StateFlow<List<DislikeMark>> get() = store.all

  fun isDisliked(key: DislikeKey): Boolean = store.isDisliked(key)

  fun markOf(key: DislikeKey): DislikeMark? = store.markOf(key)

  fun snapshotOf(keys: Collection<DislikeKey>): Map<DislikeKey, DislikeMark> = store.snapshotOf(keys)

  /**
   * 从数据库载入初始快照。
   *
   * 只该在 App 启动时调一次。**读失败必须吞掉**（用默认空集继续）——
   * 「不喜欢」是个锦上添花的功能，为它让整个 App 起不来是本末倒置。
   */
  suspend fun load(): Boolean = withContext(Dispatchers.IO) {
    runCatching { dao.getAll() }
      .onFailure { Log.w(TAG, "读取不喜欢列表失败，按空列表继续", it) }
      .map { rows ->
        rows.map { row ->
          DislikeMark(
            key = DislikeKey(row.serverId, row.itemId),
            title = row.title,
            reason = row.reason,
            markedAt = row.markedAt,
            imageUrl = row.imageUrl,
            active = row.active,
          )
        }
      }
      .onSuccess { store.loadAll(it) }
      .isSuccess
  }

  /** 标记（或更新备注）。UI 立即响应，落盘异步。 */
  fun mark(key: DislikeKey, title: String, reason: String?, imageUrl: String? = null) {
    store.mark(
      key = key,
      title = title,
      reason = reason,
      markedAt = System.currentTimeMillis(),
      imageUrl = imageUrl,
    )
  }

  /**
   * 补一条记录的**展示元数据**（片名 / 缩略图），**不动标记状态与时间**。
   *
   * ## 为什么需要它
   *
   * 播放器里标记时手上只有 `serverId + itemId` —— 播放页不持有片名与封面
   * （见 `DislikePlayerActions` 的注释），存下来的记录 `title` 是空串，
   * 管理页只能显示「（无标题）」、缩略图也是空的。
   * 这类**历史脏数据不会自己长出来**，只能事后回查服务器补一次（所谓"自愈"）。
   *
   * 补全不能算作一次新的表态 ⇒ 走 `store.updateMeta`（它不动 `markedAt`）。
   */
  fun updateMeta(
    key: DislikeKey,
    title: String = "",
    imageUrl: String? = null,
    reason: String? = null,
  ) {
    store.updateMeta(key, title, imageUrl, reason)
  }

  /**
   * 取消标记 —— **记录与原因都留着**，只是状态变为未标记。
   *
   * 与 [delete] 的区别就是数据层那句 active 位：取消是可逆的（重新标记能带出上次的理由），
   * 删除是抹干净（用于条目已经失效、不该再占着列表的情况）。
   *
   * @return 之前确实处于已标记状态才为 true。
   */
  fun unmark(key: DislikeKey): Boolean = store.deactivate(key)

  /** 彻底删除一条记录（含原因）。返回是否真删了。 */
  fun delete(key: DislikeKey): Boolean = store.purge(key)

  /** 批量取消标记（保留记录）。返回实际取消条数。 */
  fun unmarkAll(keys: Collection<DislikeKey>): Int = store.deactivateAll(keys)

  /** 批量删除（含已取消的）。返回实际删除条数。 */
  fun deleteAll(keys: Collection<DislikeKey>): Int = store.purgeAll(keys)

  /** 清空全部。返回实际删除条数。 */
  fun clear(): Int = store.clear()

  /**
   * 立刻落盘（不再等去抖窗口）。
   *
   * 给「删除全部」这类破坏性操作用 —— 操作后用户可能直接杀进程，
   * 等 400ms 去抖窗口就白丢了。
   */
  suspend fun flushNow() {
    writeLock.withLock {
      val snapshot = pending ?: return@withLock
      pending = null
      persist(snapshot)
    }
  }

  /**
   * 安排落盘：把新快照记为待写，并**重置**去抖计时。
   *
   * 连续标记时计时器被一次次推后 ⇒ 只有最后一次真的写库。
   */
  private fun schedulePersist(snapshot: List<DislikeMark>) {
    flushJob?.cancel()
    flushJob = scope.launch {
      kotlinx.coroutines.delay(FLUSH_DELAY_MS)
      runCatching {
        writeLock.withLock {
          val toWrite = pending ?: return@withLock
          pending = null
          persist(toWrite)
        }
      }.onFailure { Log.w(TAG, "落盘失败", it) }
    }
  }

  private suspend fun persist(snapshot: List<DislikeMark>) {
    runCatching {
      dao.replaceAll(snapshot.map { it.toEntity() })
    }.onFailure { Log.w(TAG, "写入不喜欢列表失败", it) }
  }

  private fun DislikeMark.toEntity() = DislikedMediaEntity(
    serverId = key.serverId,
    itemId = key.itemId,
    title = title,
    reason = reason,
    markedAt = markedAt,
    imageUrl = imageUrl,
    active = active,
  )

  private companion object {
    const val TAG = "DislikeRepo"
    const val FLUSH_DELAY_MS = 400L
  }
}