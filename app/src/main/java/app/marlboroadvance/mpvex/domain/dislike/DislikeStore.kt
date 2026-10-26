package app.marlboroadvance.mpvex.domain.dislike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「不喜欢」标记的内存状态机与快照容器。
 *
 * 全量快照常驻内存，UI 查询为 O(1)、无 IO、无协程，切换视频时同一帧即可画出正确状态；
 * 落盘只在启动加载 / 变更写入两个时机发生，由外部经 [onMutate] 处理，本类不依赖数据库。
 *
 * [DislikeMark.active] 用于区分两种「不标记」：[deactivate] 取消标记但保留记录与原因，
 * [purge] 彻底删除。查询单条 O(1)，变更 O(n) 重排 + O(n) 重建索引（n 为累计标记数，
 * 百级量级，不做增量更新以保证两份结构一致）。
 */
class DislikeStore {

  private val lock = Any()

  /** 全量快照（含已取消标记的记录，管理页需置灰显示）。始终是不可变 List，变更时整体换新。 */
  private var marks: List<DislikeMark> = emptyList()

  /** 按 key 建的索引，每次变更时与 [marks] 一并重建，保证两者永远一致。 */
  private var index: Map<DislikeKey, DislikeMark> = emptyMap()

  private val _all = MutableStateFlow<List<DislikeMark>>(emptyList())
  private val _revision = MutableStateFlow(0L)

  /** 全量列表，按标记时间倒序。管理页订阅这一个流即可，无需跨页通信。 */
  val all: StateFlow<List<DislikeMark>> = _all.asStateFlow()

  /** 每次数据变更自增，仅供测试与诊断。UI 判断状态请用 [isDisliked]。 */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  /** 数据变更回调，参数是变更后的完整列表；在锁外调用，因此可以安全地做 IO。 */
  @Volatile
  var onMutate: ((List<DislikeMark>) -> Unit)? = null

  /** 用持久化的数据初始化（启动时调用一次）。 */
  fun loadAll(data: List<DislikeMark>) {
    val sorted = data.sortedByDescending { it.markedAt }
    synchronized(lock) {
      marks = sorted
      index = sorted.associateBy { it.key }
    }
    _all.value = sorted
    _revision.value = _revision.value + 1
  }

  /**
   * 用持久化的数据初始化，但**保留读库期间已经发生的本地变更**。
   *
   * ## 为什么不直接用 [loadAll]
   *
   * `loadAll` 是无条件覆盖。而启动时读库是异步的：用户完全可能在读回来之前
   * 就在播放器里标了一条 —— 那一刻内存快照里只有这一条，
   * 等读库结果回来再整体覆盖，**用户刚标的就被冲掉了**（界面上一闪又变回未标记）。
   *
   * 合并规则（冲突时内存优先）：
   * · 库里有的每一条：内存里有同 key 就取内存那份（备注可能刚改过），否则取库里的；
   * · 库里没有、内存里有的：原样保留 —— 那是读库期间新标的，还在落盘队列里。
   *
   * 刻意**不触发** [onMutate]：合并进来的数据要么已经在库里，要么已经在落盘队列中，
   * 再触发一次只会写一遍相同的内容。
   */
  fun mergeLoaded(data: List<DislikeMark>) {
    val merged: List<DislikeMark>
    synchronized(lock) {
      val local = index
      val fromDb = data.associateBy { it.key }
      // 库里每条：内存有同 key 就用内存的（读库期间可能改过备注/状态）
      val picked = data.map { local[it.key] ?: it }
      // 内存独有的（读库期间新标的）
      val localOnly = local.values.filter { it.key !in fromDb }
      merged = (picked + localOnly).sortedByDescending { it.markedAt }
      marks = merged
      index = merged.associateBy { it.key }
    }
    _all.value = merged
    _revision.value = _revision.value + 1
  }

  /** 该条目当前是否处于已标记状态 —— UI 判断选中态只应看这个。 */
  fun isDisliked(key: DislikeKey): Boolean =
    synchronized(lock) { index[key]?.active == true }

  /**
   * 取完整记录（含备注与 [DislikeMark.active]），不存在返回 null。
   *
   * 已取消标记的记录也能取到（不看 active），用于回填上次写过的备注。
   */
  fun markOf(key: DislikeKey): DislikeMark? = synchronized(lock) { index[key] }

  /** 批量查询，只回传命中的条目；比逐条 [isDisliked] 少 N-1 次加锁。 */
  fun snapshotOf(keys: Collection<DislikeKey>): Map<DislikeKey, DislikeMark> {
    if (keys.isEmpty()) return emptyMap()
    val wanted = keys.toHashSet()
    return synchronized(lock) {
      val out = HashMap<DislikeKey, DislikeMark>(wanted.size)
      for (k in wanted) index[k]?.let { out[k] = it }
      out
    }
  }

  /**
   * 标记（或更新备注 / 重新激活）。幂等 upsert，已存在的记录被重新激活并刷新时间戳。
   *
   * @param reason 传 null 表示沿用现有备注，避免重复标记把上次写的理由抹掉。
   * @return 变更后的记录。
   */
  fun mark(
    key: DislikeKey,
    title: String,
    reason: String?,
    markedAt: Long,
    imageUrl: String? = null,
  ): DislikeMark {
    var result: DislikeMark? = null
    // 读旧值、算新列表、换引用必须全在同一锁内，否则并发 mark 会互相覆盖备注/图
    mutate {
      val previous = index[key]
      val newMark =
        DislikeMark(
          key = key,
          // 片名/图：新值空则沿用旧的（改备注时不该把标题清空）
          title = title.ifBlank { previous?.title.orEmpty() },
          reason = reason ?: previous?.reason.orEmpty(),
          // 管理页显示的是最后一次表态的时间
          markedAt = markedAt,
          imageUrl = imageUrl ?: previous?.imageUrl,
          active = true,
        )
      result = newMark
      (marks.filterNot { it.key == key } + newMark).sortedByDescending { it.markedAt }
    }
    // mark 恒有变更（upsert 语义），mutate 不会返回 false
    return result!!
  }

  /**
   * 只补展示用的元数据（片名 / 缩略图），不改标记状态、原因与时间。
   *
   * 不能复用 [mark]：那是「表态」语义，会刷新 `markedAt` 并重新激活，
   * 而补元数据只是补上当初没拿到的片名，走 [mark] 会把管理页的标记时间改掉。
   *
   * @return 真的改动了才为 true（本来就有片名 / 记录不存在时返回 false）。
   */
  fun updateMeta(
    key: DislikeKey,
    title: String = "",
    imageUrl: String? = null,
    /** 传 null 表示"不改备注"；传空串则是"把备注清空"。 */
    reason: String? = null,
  ): Boolean =
    mutate {
      val cur = index[key] ?: return@mutate null
      val next =
        cur.copy(
          title = title.ifBlank { cur.title },
          imageUrl = imageUrl ?: cur.imageUrl,
          reason = reason ?: cur.reason,
        )
      // 没变化就别发布：补全失败（拿到空片名）时不该触发一次无意义的落盘
      if (next == cur) null else marks.map { if (it.key == key) next else it }
      // ⚠️ 刻意不重新排序：markedAt 没变，顺序就不该变，否则列表会跳一下
    }

  /**
   * 取消标记 —— 记录与原因都留着，只把 [DislikeMark.active] 置 false。
   *
   * @return 之前确实是已标记状态才为 true。
   */
  fun deactivate(key: DislikeKey): Boolean =
    // 判定与改写必须在 [mutate] 的同一锁内完成，分成两次加锁会"只取消了一个"
    // ⚠️ 刻意不改 markedAt：它记的是最后一次表态时间，取消标记只是状态变化
    mutate {
      val cur = index[key]
      if (cur == null || !cur.active) {
        null
      } else {
        marks.map { if (it.key == key) it.copy(active = false) else it }
          .sortedByDescending { it.markedAt }
      }
    }

  /**
   * 彻底删除一条记录（含原因）。
   *
   * @return 真的删掉一条才为 true。
   */
  fun purge(key: DislikeKey): Boolean =
    // 命中判定与过滤在同一锁内，避免拿旧判断配新数据
    mutate { if (index.containsKey(key)) marks.filterNot { it.key == key } else null }

  /**
   * 一条条取消全部（保留记录与原因，置灰显示）。
   *
   * @return 实际取消条数（只数真正处于已标记状态的）。
   */
  fun deactivateAll(keys: Collection<DislikeKey>): Int {
    if (keys.isEmpty()) return 0
    val target = keys.toHashSet()
    var changed = 0
    // 计数在锁内累加，需带出到锁外
    val published =
      mutate {
        var count = 0
        val mapped = marks.map { m ->
          if (m.key in target && m.active) {
            count++
            m.copy(active = false)
          } else {
            m
          }
        }
        changed = count
        if (count == 0) null else mapped.sortedByDescending { it.markedAt }
      }
    return if (published) changed else 0
  }

  /**
   * 一条条彻底删除。
   *
   * @return 实际删除条数。
   */
  fun purgeAll(keys: Collection<DislikeKey>): Int {
    if (keys.isEmpty()) return 0
    val target = keys.toHashSet()
    var removed = 0
    val published =
      mutate {
        val filtered = marks.filterNot { it.key in target }
        removed = marks.size - filtered.size
        if (removed == 0) null else filtered
      }
    return if (published) removed else 0
  }

  /**
   * 清空全部（连已取消的记录一起删）。
   *
   * @return 实际删除条数。
   */
  fun clear(): Int {
    var count = 0
    val published =
      mutate {
        count = marks.size
        if (count == 0) null else emptyList()
      }
    return if (published) count else 0
  }

  /**
   * 统一发布路径：在同一个锁内「算新状态 + 换引用」，随后在锁外发新值。
   *
   * ⚠️ 换引用必须在锁内：否则两个并发操作会各自基于同一份旧 marks 计算，
   * 后发布的一方把先发布的整个覆盖掉 —— 症状是"50 条只取消了 1 条"，且仅并发时偶发。
   * 因此调用方只传「基于当前状态算新状态」的 lambda，加锁由本方法统一负责。
   *
   * 先换 map 引用、再发 `_all.value`；反过来订阅者被唤醒时读到的还是旧 map。
   */
  private fun mutate(compute: () -> List<DislikeMark>?): Boolean {
    val snapshot: List<DislikeMark>? = synchronized(lock) {
      // compute 内只读 marks/index；返回 null 表示无变化，不发布
      val next = compute() ?: return@synchronized null
      val nextIndex = next.associateBy { it.key }
      marks = next
      index = nextIndex
      next
    }
    if (snapshot == null) return false
    _all.value = snapshot
    _revision.value = _revision.value + 1
    // 锁外回调：允许它做 IO / 访问数据库
    onMutate?.invoke(snapshot)
    return true
  }
}
