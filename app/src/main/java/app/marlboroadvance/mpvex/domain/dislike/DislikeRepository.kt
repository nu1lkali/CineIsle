package app.marlboroadvance.mpvex.domain.dislike

import android.os.SystemClock
import android.util.Log
import app.marlboroadvance.mpvex.database.dao.DislikeDao
import app.marlboroadvance.mpvex.database.entities.DislikedMediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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
 * 变更先改内存（UI 立刻响应），随后**立即**排队落盘，落盘写的是**差量**：
 * · **增量**（[persistDiff]）：只 upsert 变了的行、只删消失的行。
 *   原来那种「整表删除 + 全量重写」依赖「内存 == 全库」，
 *   而读库是异步的 —— 在它回来之前写入，会把库里原有记录连带抹掉。
 * · **串行**（[writeLock]）：多个入口（播放器 / 详情页 / 管理页）可能同时改，
 *   并发 diff 会各自基于同一份旧基线算出重复的新增。
 * · **不去抖**：一次只写一条（微秒级），省下的那点 IO 换不来「标完就退出
 *   App 会丢」的代价 —— 那曾经是真实用户报告的问题。
 */
class DislikeRepository(
  private val dao: DislikeDao,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val store = DislikeStore()

  private val writeLock = Mutex()

  /**
   * 库里当前那份状态，用来把下一次内存变更换算成「增量」。
   *
   * 没有它就是全量覆盖，而全量覆盖在启动阶段会把库里原有记录连带删掉 —— 见 [persistDiff]。
   * 受 [writeLock] 保护（只在持锁期间读写）。
   */
  private var persistedBaseline: Map<DislikeKey, DislikeMark> = emptyMap()

  /** 还在排队的写入数；[flushNow] 靠它判断「都落完了」。 */
  private val inflight = AtomicInteger(0)

  init {
    // 任何内存变更都排队落盘（增量 + 立即排队，不再去抖 —— 见类注释）
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
      .map { rows -> rows.map { it.toMark() } }
      .onSuccess { loaded ->
        // 先记基线、再合并进内存，顺序不能反：合并之后内存里会多出「读库期间新标的」
        // 那些，而基线必须是**库里真实的样子**，否则下一次 diff 会把它当成「库里已有」。
        writeLock.withLock { persistedBaseline = loaded.associateBy { it.key } }
        store.mergeLoaded(loaded)
        Log.i(TAG, "load: ${loaded.size} 条（其中 ${loaded.count { it.active }} 条处于标记状态）")
      }
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
   * 立刻落盘（不再等排队）。
   *
   * 给「删除全部」这类破坏性操作用 —— 操作后用户可能直接杀进程。
   * 现在写入已经是「变更即排队、无去抖窗口」，所以这里只需**等排队中的写完**，
   * 而不是再逼一次全量覆写（那正是 [persistDiff] 要避免的事）。
   */
  suspend fun flushNow() {
    // 排队中的写入由 writeLock 串行；拿一次锁即意味着「之前的都落完了」。
    // 但协程可能还没抢到锁，所以再看一眼 in-flight 计数，最多等 2 秒。
    val deadline = SystemClock.elapsedRealtime() + 2_000L
    while (inflight.get() > 0 && SystemClock.elapsedRealtime() < deadline) {
      writeLock.withLock { /* 等前面的写入落地 */ }
      if (inflight.get() > 0) delay(16L)
    }
  }

  /**
   * 安排落盘：**立即**排进写队列，不做去抖。
   *
   * ## 为什么去掉了 400ms 去抖
   *
   * 原来的取舍是「连续标记 5 部只写一次库」，代价写成「极端情况下丢一批」。
   * 但现实里「标完一部就随手把 App 从后台清掉」一点也不极端 —— 那个 400ms 窗口
   * 里进程被杀，这次标记就永远不会出现在库里（用户看到的就是「重启后全没了」）。
   *
   * 而现在写入是**增量**的：一次只 upsert 一条（微秒级），连续标 5 部就是 5 次单条写，
   * 去抖省下的那点 IO 远远不值这个丢失风险。所以直接排队、让 [writeLock] 保证顺序。
   */
  private fun schedulePersist(snapshot: List<DislikeMark>) {
    inflight.incrementAndGet()
    scope.launch {
      try {
        runCatching {
          writeLock.withLock { persistDiff(snapshot) }
        }.onFailure { Log.w(TAG, "落盘失败", it) }
      } finally {
        inflight.decrementAndGet()
      }
    }
  }

  /**
   * 把内存快照与「库里那份」做差，只写差异。
   *
   * ## 为什么必须是差量
   *
   * 内存快照在启动阶段可能**不完整**（读库还没回来）。全量覆盖会把这个半份快照
   * 当成真相写回去，库里原有记录被连带删除。差量写只碰真正变了的行，
   * 内存有多少都不影响其它行。
   *
   * 调用方必须持有 [writeLock]：基线读写都要在锁内，否则并发两次 diff 会各自
   * 基于同一份旧基线算出重复的新增。
   */
  private suspend fun persistDiff(snapshot: List<DislikeMark>) {
    val now = snapshot.associateBy { it.key }
    val upserts = now.values.filter { persistedBaseline[it.key] != it }
    val removed = persistedBaseline.keys - now.keys

    runCatching {
      if (now.isEmpty() && removed.isNotEmpty()) {
        // 清空全部：一条条删也能删完，但直接 deleteAll 更省事（几百行的事务也不慢）
        dao.replaceAll(emptyList())
      } else if (upserts.isNotEmpty() || removed.isNotEmpty()) {
        dao.applyDiff(
          upserts = upserts.map { it.toEntity() },
          removed = removed.map { it.serverId to it.itemId },
        )
      }
      // 基线只在写成功后推进；失败时保持旧值，下一次变更会把这次的差异重新算进去
      persistedBaseline = now
      if (upserts.isNotEmpty() || removed.isNotEmpty()) {
        Log.i(TAG, "persist: +${upserts.size} -${removed.size}（共 ${now.size} 条）")
      }
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

  private fun DislikedMediaEntity.toMark() = DislikeMark(
    key = DislikeKey(serverId, itemId),
    title = title,
    reason = reason,
    markedAt = markedAt,
    imageUrl = imageUrl,
    active = active,
  )

  private companion object {
    const val TAG = "DislikeRepo"
  }
}