package app.marlboroadvance.mpvex.ui.lock

import kotlin.random.Random

/** 一次点击的结果。 */
internal enum class HitMoleTap {
  /** 敲中当前这只。 */
  HIT,

  /** 敲在空格上 —— 不扣进度，只给一次抖动反馈。 */
  MISS,

  /** 当前不接受输入（已锁定 / 还没出洞 / 已完成）。 */
  IGNORED,
}

/**
 * 打地鼠的推进状态机，纯 Kotlin，不依赖 Compose / Android。
 *
 * ## 为什么单独抽出来
 *
 * 这套玩法的全部内容就是时序：存留时长、出洞间隔、命中锁格。任何一处只靠
 * UI 定时器表达，都既测不了也调不动。抽成纯状态机之后，注入固定种子的
 * [Random] 就能断言具体序列，注入窄区间（如 `120..120`）就能把时序测试
 * 变成确定性的 —— 不需要 instrumentation，纯 JVM 跑。
 *
 * ## 不做失败判定
 *
 * 漏掉不扣进度、敲错不扣进度、没有时限也没有总次数上限。打地鼠是**技能完成**，
 * 不是凭证校验：设备持有者没有密码可输，一旦这条路径可以失败，就等于给用户
 * 留了一条「永远进不去」的路。进度单调不减 ⇒ 必然可达 ⇒ 也就不需要「跳过」后门。
 *
 * ## 时长只取一次
 *
 * [holdMs] / [gapMs] 在 [nextSpawn] 里各取一次并存住。UI 的倒计时、超时判定、
 * 动画时长必须读同一个字段 —— 两边各取一次随机会得到不同的值，现场表现是
 * 「看着还在、点下去已经算漏」，这种 bug 在真机上极难复现。
 *
 * ## 洞是可复用的
 *
 * 敲过的格子照样参与随机，唯一的回避规则是「不与上一只同格」，否则连出同一格
 * 会给人「按住不放也能中」的错觉。命中标记因此只能是瞬时的，不能画成常驻状态。
 */
internal class HitMoleGameState(
  val target: Int,
  private val random: Random = Random.Default,
  private val holdRange: IntRange = DEFAULT_HOLD_RANGE,
  private val gapRange: IntRange = DEFAULT_GAP_RANGE,
) {

  /** 已敲中数。只在 [commitHit] 里增加 —— 落锤到位那一帧才算数。 */
  var hits: Int = 0
    private set

  /** 当前有地鼠的格子（0..8），-1 = 没有。 */
  var activeCell: Int = -1
    private set

  /** 当前这只的存留时长，单位 ms。 */
  var holdMs: Int = 0
    private set

  /** 下一次出洞前的等待时长，单位 ms。 */
  var gapMs: Int = 0
    private set

  /** 命中已发生、还没收手 —— 这段时间不再接受输入。 */
  var locked: Boolean = false
    private set

  /**
   * 这一次命中还没记账。
   *
   * 单独一个标志是为了让 [commitHit] **幂等**：它由 UI 在落锤那一帧调用，
   * 而落锤与收手之间可能因为重组、恢复而重入。只靠 [locked] 判会重复计数。
   */
  private var pendingCommit: Boolean = false

  /** 是否已达目标次数。 */
  val isComplete: Boolean get() = hits >= target

  private var lastCell: Int = -1

  /**
   * 取下一只地鼠：选格（不与上一格相同）、取 [holdMs] / [gapMs]。
   *
   * 返回格子下标，同时把状态推进到「这只已出洞」。
   */
  fun nextSpawn(): Int {
    val cell = if (lastCell < 0) {
      random.nextInt(CELL_COUNT)
    } else {
      // 在 0..7 里取，再跳过 lastCell —— 保证剩下 8 格等概率
      val raw = random.nextInt(CELL_COUNT - 1)
      if (raw >= lastCell) raw + 1 else raw
    }
    lastCell = cell
    activeCell = cell
    locked = false
    pendingCommit = false
    holdMs = random.nextInt(holdRange.first, holdRange.last + 1)
    gapMs = random.nextInt(gapRange.first, gapRange.last + 1)
    return cell
  }

  /**
   * 点击某格。
   *
   * 命中只**锁格**，不计数 —— 计数交给 [commitHit]，由 UI 在落锤到位那一帧调用，
   * 这样进度条、震动、压扁能和锤头同时发生。
   */
  fun tap(cell: Int): HitMoleTap = when {
    isComplete -> HitMoleTap.IGNORED
    locked -> HitMoleTap.IGNORED
    activeCell < 0 -> HitMoleTap.MISS
    cell != activeCell -> HitMoleTap.MISS
    else -> {
      locked = true
      pendingCommit = true
      HitMoleTap.HIT
    }
  }

  /**
   * 落锤到位：进度 +1。
   *
   * 幂等 —— 同一只重复调用只记一次（[pendingCommit] 用掉即清）。
   */
  fun commitHit() {
    if (!pendingCommit) return
    pendingCommit = false
    hits += 1
  }

  /** 收手完成：清空当前格，等待 [gapMs] 之后下一次 [nextSpawn]。 */
  fun finishHit() {
    activeCell = -1
    locked = false
  }

  /**
   * 超时未敲。
   *
   * 返回是否真的超时了 —— 已经命中的那只不算超时（此时 [locked] 为真），
   * 调用方据此决定要不要走「漏掉」的收手路径。
   */
  fun onTimeout(): Boolean {
    if (activeCell < 0 || locked) return false
    activeCell = -1
    return true
  }

  companion object {
    /** 3×3 九宫格，与图案解锁的视觉语言保持一致。 */
    const val CELL_COUNT = 9

    /** 目标次数三档。 */
    val TARGET_CHOICES = listOf(3, 5, 8)

    /** 默认 5 次 —— 大约 8~10 秒，够解一个锁又不至于烦。 */
    const val DEFAULT_TARGET = 5

    /** 单只存留 760~1140ms（均值 950）：反应 350ms + 抬手落指 200ms，再留余量。 */
    val DEFAULT_HOLD_RANGE = 760..1140

    /** 出洞间隔 380~720ms（均值 550）：低于 350 像在赶人，高于 800 会以为卡住。 */
    val DEFAULT_GAP_RANGE = 380..720
  }
}
