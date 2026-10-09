package app.marlboroadvance.mpvex.domain.dislike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「不喜欢」标记的**内存状态机 + 快照容器**。
 *
 * ## 为什么要有这个类
 *
 * 需求是「多视频切换时按当前状态更新 UI，且尽量减少查询状态的复杂度」。
 * 朴素做法是每次切换都去问数据库 —— 异步、有延迟，表现为**按钮先显示未选中、
 * 过一帧才变色**，快速连播时还会看到状态错乱。
 *
 * 这里改成：内存里常驻一份全量快照，切视频时只做一次 [HashMap] 查找，
 * **O(1)、无 IO、无协程**，UI 同一帧就能画出正确状态。
 * 落盘只在两个时机发生（启动加载 / 变更写入），由外部经 [onMutate] 回调触发 ——
 * 本类**不知道数据库的存在**，因此可以纯 JVM 单测。
 *
 * ## 两种「不标记」是不同的东西（这是有状态位的根本原因）
 *
 * 管理页给的是两个按钮，如果只有一个布尔量，它们就是**同一件事**、两个入口而已：
 * · [deactivate]（取消标记）：标记失效，但**记录与原因都留着**。
 *   下次重新标记时 [markOf] 能带出上次写过的理由；管理页里这行仍可见、置灰。
 * · [purge]（删除）：整条记录抹掉。用于条目已经失效（片子从库里删了、
 *   服务器下线了）而列表里还留着占位的情况。
 *
 * 所以 [DislikeMark.active] 不是多余的字段：它让「取消」和「删除」有可观察的差别，
 * 也让「保留原因」这个使用场景成立。
 *
 * ## 复杂度
 *
 * · 查询单个：[isDisliked] / [markOf] —— O(1)
 * · 一次取一批：[snapshotOf] —— O(k)，连播只需当前 + 下一条
 * · 变更：[mark] / [deactivate] / [purge] —— O(n) 重排 + O(n) 建索引
 *   ⚠️ 这里**故意**没优化成增量更新：n 是「用户累计标记过的条目数」，
 *   量级在几百，O(n) 完全无所谓；而增量更新会引入"两份数据结构可能不一致"的 bug
 *   （症状是删了却还显示已标记）。**正确性优先于这里的常数因子。**
 * · 全量列表：[all] —— 管理页用，用户主动打开才订阅
 *
 * ## 线程安全
 *
 * 内部用 `synchronized` 保护。写操作都是内存操作、持有时间极短。
 * [onMutate] 回调在**锁外**调用，所以它可以安全地做 IO、访问数据库，
 * 不会与读操作互相卡住。
 */
class DislikeStore {

  private val lock = Any()

  /**
   * 全量快照（**含已取消标记的记录** —— 管理页要把它们显示成置灰行）。
   * 始终是不可变 List，变更时整体换新，这样 Compose 侧
   * `collectAsState` 读到新引用必然触发重组，不存在"改到一半被读到"的中间态。
   */
  private var marks: List<DislikeMark> = emptyList()

  /**
   * 按 key 建的索引。**每次变更时与 [marks] 一并重建**，保证两者永远一致。
   * 分开维护早晚会漏掉一处更新，而漏掉的后果是"删了却还显示已标记"。
   */
  private var index: Map<DislikeKey, DislikeMark> = emptyMap()

  private val _all = MutableStateFlow<List<DislikeMark>>(emptyList())
  private val _revision = MutableStateFlow(0L)

  /**
   * 全量列表，按标记时间**倒序**（最近表态的排最前）。
   *
   * 这是管理页的唯一数据源 —— 它订阅这一个流就够了，
   * 不需要"标记了 → 主动通知管理页刷新"这类跨页通信。
   */
  val all: StateFlow<List<DislikeMark>> = _all.asStateFlow()

  /**
   * 每次数据变更自增。仅供测试与诊断用 —— UI **不该**依赖它判断状态，
   * 判断状态要看 [isDisliked]，否则「删了另一条」也会让当前视频的按钮闪一下。
   */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  /**
   * 数据变更回调，参数是**变更后的完整列表**。
   *
   * 由 repository 注入，用来把新状态落盘。回调在锁外调用（见 [publish]），
   * 所以它可以安全地做 IO —— 不会与读操作互相卡住。
   */
  @Volatile
  var onMutate: ((List<DislikeMark>) -> Unit)? = null

  /** 用持久化的数据初始化（启动时调用一次），随后触发一次 [onMutate]（调用方可忽略）。 */
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
   * 该条目**当前是否处于已标记状态** —— **UI 判断选中态只应看这个**，
   * 不要看 [revision]，也不要看 [all] 里有没有它（已取消的记录仍在列表里，
   * 但状态是未标记）。
   */
  fun isDisliked(key: DislikeKey): Boolean =
    synchronized(lock) { index[key]?.active == true }

  /**
   * 取完整记录（含备注与 [DislikeMark.active]），不存在返回 null。
   *
   * 长按弹输入框时用它**回填上次写的备注** —— 否则用户想改个字都得从头打。
   * 注意：已取消标记的记录也能取到（[markOf] 不看 active），
   * 这正是"取消标记保留原因"能成立的前提。
   */
  fun markOf(key: DislikeKey): DislikeMark? = synchronized(lock) { index[key] }

  /**
   * 批量查询，用于"一次把播放队列里所有条目的状态取好"。
   *
   * 比逐条 [isDisliked] 少 N-1 次锁获取，且只回传命中的 ——
   * 调用方不需要整份索引。
   */
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
   * 标记（或更新备注 / 重新激活）。
   *
   * 幂等 upsert：已存在的记录被**重新激活**并刷新时间戳。
   *
   * @param reason 备注。**传 null 表示沿用现有备注** ——
   *   重复点一次标记不该把上次写的理由抹掉（点了新标记但理由没填的场景很常见）。
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
    // 读旧值、算新列表、换引用必须全在同一个锁内 ——
    // 若 previous 在锁外读、next 在锁内算，两次并发 mark 会各自基于旧值算，
    // 后一个把前一个的备注/图覆盖掉（症状是"改了备注又变回旧的"）。
    mutate {
      val previous = index[key]
      val newMark =
        DislikeMark(
          key = key,
          // 片名/图同理：新值空则沿用旧的（用户改备注时不该把标题清空）
          title = title.ifBlank { previous?.title.orEmpty() },
          reason = reason ?: previous?.reason.orEmpty(),
          // 重新标记刷新时间戳：管理页显示的是"最后一次表态的时间"
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
   * 只补**展示用的元数据**（片名 / 缩略图），**不动标记状态、原因与时间**。
   *
   * ## 为什么不能复用 [mark]
   *
   * [mark] 是「表态」语义：它会刷新 `markedAt` 并重新激活。
   * 补元数据只是把**当初没拿到的片名补上**（播放器里标记时手上只有 id），
   * 走 [mark] 的话管理页里这行的「标记时间」会变成"补全的那一刻"，
   * 用户看到的就不是他真正点下「不喜欢」的时间了 —— 这是造假数据。
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
      // ⚠️ 刻意**不**重新排序：markedAt 没变，顺序就不该变。
      //  重排只是白算一次，还会让列表在补全那一刻跳一下。
    }

  /**
   * 取消标记 —— **记录与原因都留着**，只是 [DislikeMark.active] 置 false。
   *
   * @return 之前确实是已标记状态才为 true；对本来就未标记的（包括已取消过的）
   *   返回 false，调用方据此决定要不要提示。
   */
  fun deactivate(key: DislikeKey): Boolean =
    // 判定与改写在 [mutate] 的**同一个锁内**完成（见那里的说明）——
    // 分成两次加锁就会出现"只取消了一个"。
    // ⚠️ 刻意**不改** markedAt：它记的是「最后一次表态时间」，
    // 取消标记只是状态变化，保住原时间戳才能看出这条是"什么时候标记的"。
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
    // 命中判定与"剩下什么"一起在锁内算（见 [mutate]）：
    // 若先问 containsKey 再 filterNot，中间被并发插入/删除就会拿旧判断配新数据。
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
    // 计数在 [mutate] 的锁内累加（锁外已经拿不到旧状态了）。
    // 哪怕中途 return 也要把已计的数带出来，所以用一个可变量而不是局部 val。
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
   * 统一发布路径：**先换引用、再发新值**，且**全程持锁**。
   *
   * ## 为什么换引用也必须在锁内（这是本类最容易写错的地方）
   *
   * 直觉写法是「锁内算出新列表 → 锁外 publish」：
   * ```
   * val next = synchronized(lock) { marks.map { ... } }   // ← 锁内
   * publish(next)                                          // ← 锁外换引用
   * ```
   * 看着没问题，实际有竞态：两个并发 deactivate 会**各自拿到同一份旧 marks**，
   * 各自算出一个"只改了自己那条"的新列表，然后后 publish 的把先 publish 的整个覆盖掉 ——
   * 症状是"50 条只取消了 1 条"，而且只在并发时偶发。
   *
   * `DislikeStoreTest.并发取消多个条目不会互相覆盖` 就是守这个的。
   *
   * 所以正确形状是 **CAS 式**：调用方把"基于当前状态算新状态"这件事作为**函数**传进来，
   * 由本方法在锁内执行、并在同一个锁内换掉 [marks]/[index]。
   * 各写操作因此只需实现 [mutate] 里的那一个 lambda，不会有人再有机会漏掉加锁。
   *
   * ## 发布顺序
   *
   * 先换 map 引用、再发 `_all.value`。反过来（先发值再换引用）订阅者被唤醒时
   * 读到的还是旧 map，症状是"数据库已删但界面还显示在列表里"。
   *
   * ## 锁外只做 IO
   *
   * [onMutate] 回调在锁**外**调用，所以它可以安全地做 IO、访问数据库，
   * 不会与读操作互相卡住。
   */
  private fun mutate(compute: () -> List<DislikeMark>?): Boolean {
    val snapshot: List<DislikeMark>? = synchronized(lock) {
      // compute 内部只读 marks/index，产出基于**当前**状态的新列表；
      // 拿不到新状态（无变化）就返回 null，由本方法决定不发布。
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