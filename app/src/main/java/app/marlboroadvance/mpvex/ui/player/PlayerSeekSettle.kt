package app.marlboroadvance.mpvex.ui.player

import kotlin.math.abs

/**
 * 「刚下发过 seek」的判定口径。
 *
 * ## 为什么单独成文件
 *
 * 这里的全部内容都是**纯函数**：不读时钟、不碰单例、不依赖 android 类。于是它可以在
 * 普通 JVM 上直接单测（`app/src/test/.../PlayerSeekSettleTest.kt`，用
 * `.verify/run_settle_tests.py` 十几秒跑完），而 [PlayerSeekTracker] 那边只负责
 * 「取时钟 + 存状态 + 落日志」。
 *
 * 这不是洁癖：这条判定连着「续播点会不会被写成 0」，改错的代价是用户丢掉观看进度，
 * 而真机复现一次要拖动进度条、返回、再看详情页 —— 必须有能在本机跑穿的回归网。
 */
internal object SeekSettlePolicy {
  /**
   * 允许「还没到位」的差值。
   *
   * mpv 的 `absolute+keyframes` 会落到目标**之前**的关键帧上，GOP 长一点能差近 10 秒；
   * 12 秒 = 关键帧粒度 + 一点抖动余量。差值在这之内就当已经落地、照读数上报。
   */
  const val TOLERANCE_SECONDS = 12L

  /**
   * 「目标还压在读数上面」最多能压多久。
   *
   * ## 为什么这里不能再用 8 秒的固定窗口（v259）
   *
   * 老实现（v256）是「距下发 8 秒内、且与目标差 > 容差 ⇒ 用目标」。那 8 秒是按
   * **本地文件**的 seek 落地时间（1~4 秒）拍的。网络流上根本不是这个量级：
   * 一次 seek 要先丢缓存、重开 Range 连接、解复用器重新定位、再解出第一帧，
   * 用户「频繁拖动」时上一条 seek 还没跑完就来了下一条，落地时间轻松超过 8 秒。
   *
   * 窗口一过，读数就又被采信 —— 而那时 `time-pos` 恰好还在报 0（取流层刚被推倒重建），
   * 于是一次「拖完、等了十几秒、返回」就把 0 写进了续播点。**这才是修了几次都没好的原因：
   * 判据是时间，而真正该看的是「这个读数有没有跑到目标附近」。**
   *
   * 现在改用状态判定：只要读数还没接近目标，就一直认为这次 seek 没落地，不受时间限制；
   * 本常量只作**最后的兜底**（seek 真的失败了、播放器其实还在原地放），超过它就恢复采信读数。
   */
  const val MAX_HOLD_MS = 120_000L

  /**
   * 「用户要求跳到开头」这个意图的有效期。
   *
   * 意图必须**限时**：`seekTargetsZero()` 是「读到 0 时能不能放行」的总闸，若一次
   * 拖回开头就让它在整场播放里恒为真，那么二十分钟后一次取流层重启报出的假 0 也会
   * 被当成「用户自己要的」直接上报 —— 一道本该救命的闸门反过来成了漏斗。
   */
  const val ZERO_INTENT_WINDOW_MS = 30_000L

  /** 「目标就是开头」的判定阈值：1 秒以内都算用户要求回到开头。 */
  const val ZERO_TARGET_SECONDS = 1L
}

/**
 * 一次位置校正的结果。
 *
 * @property seconds 校正后应当采用的位置（秒）
 * @property substituted true 表示 [seconds] **不是实测读数**，而是「刚下发、还没落地」
 *   的跳转目标顶上来的。看这个标志的调用方要区别对待：
 *   · 实时进度上报 —— 不该发（发了等于替用户宣称「我现在在这里」，而播放器还在重连）；
 *   · 退出结算 / 本地续播 —— 该用（用户按返回时想知道「他要去哪儿」）。
 */
internal data class SeekSettleOutcome(
  val seconds: Long,
  val substituted: Boolean,
)

/** 读数是否已经跑到跳转目标附近（= 这次 seek 落地了）。 */
internal fun isAtSeekTarget(
  rawSeconds: Long,
  targetSeconds: Long,
  toleranceSeconds: Long = SeekSettlePolicy.TOLERANCE_SECONDS,
): Boolean =
  rawSeconds >= 0L && targetSeconds >= 0L && abs(rawSeconds - targetSeconds) <= toleranceSeconds

/**
 * 用「刚下发过的跳转目标」校正一次位置读数。
 *
 * 判定顺序（每一条都对应一种真机形态，别随手调换）：
 *
 * 1. **没有目标 / 读数无效** ⇒ 原样返回。没跳转过就没有可校正的东西。
 * 2. **时钟异常，或压在目标上已经超过 [SeekSettlePolicy.MAX_HOLD_MS]** ⇒ 原样返回。
 *    后者是「seek 大概真的失败了」的兜底：宁可回到实测值，也不能永远替用户宣称他在目标处。
 * 3. **读数已经接近目标** ⇒ 原样返回，并让调用方把「已确认」记下来。
 * 4. **已经确认过落地** ⇒ 原样返回。此后的偏差是真实播放或用户又拖了一次，不该再扶。
 * 5. 其余（刚下发、读数还在 0 或旧值上）⇒ **用目标顶替**，并标记 `substituted`。
 *
 * @param rawSeconds 播放器报的位置（秒）
 * @param targetSeconds 最近一次跳转的目标（秒）；负数表示没有目标
 * @param elapsedSinceDispatchMs 距那次跳转下发的毫秒数
 * @param alreadyConfirmed 这一次跳转此前是否已经确认落地过
 */
internal fun settleSeekPosition(
  rawSeconds: Long,
  targetSeconds: Long,
  elapsedSinceDispatchMs: Long,
  alreadyConfirmed: Boolean = false,
  toleranceSeconds: Long = SeekSettlePolicy.TOLERANCE_SECONDS,
  maxHoldMs: Long = SeekSettlePolicy.MAX_HOLD_MS,
): SeekSettleOutcome {
  if (targetSeconds < 0L || rawSeconds < 0L) return SeekSettleOutcome(rawSeconds, false)
  if (elapsedSinceDispatchMs < 0L || elapsedSinceDispatchMs > maxHoldMs) {
    return SeekSettleOutcome(rawSeconds, false)
  }
  if (isAtSeekTarget(rawSeconds, targetSeconds, toleranceSeconds)) {
    return SeekSettleOutcome(rawSeconds, false)
  }
  if (alreadyConfirmed) return SeekSettleOutcome(rawSeconds, false)
  return SeekSettleOutcome(targetSeconds, true)
}

/** [TicksSettleOutcome] 的结果，单位 **ticks**（上报器对外的口径）。 */
internal data class TicksSettleOutcome(
  val ticks: Long,
  val substituted: Boolean,
)

/**
 * [settleSeekPosition] 的 ticks 适配层。
 *
 * ## 为什么换算必须放在这里、而不是让上报器自己手写（v259）
 *
 * 早先上报器把 `time-pos` 换算出的 **ticks** 直接传进以**秒**为单位的
 * [settleSeekPosition]：1 秒 = 10⁷ ticks，于是「容差 12 秒」在 ticks 面前等于 0，
 * 每次 seek 之后都判成「未落地」，返回值被替换成目标**秒数**（如 2400），再被
 * 当成 ticks 发出去 —— 2400 ticks = 0.00024 秒，详情页就是 0%。
 * 这正是「频繁拖动后返回变 0%」反复修不好的另一半病根。
 *
 * 把换算收成纯函数，就能在 JVM 单测里直接钉死「ticks 进、ticks 出、量纲不变」
 * （见 `PlayerSeekSettleTest`），而不是只靠真机拖一次进度条去碰运气。
 *
 * @param rawTicks 播放器报的位置（ticks）
 * @param targetSeconds 最近一次跳转目标（**秒**，与 [PlayerSeekTracker] 的记账口径一致）
 * @param ticksPerSecond 1 秒对应的 ticks（Emby 为 10_000_000）
 */
internal fun settleTicksPosition(
  rawTicks: Long,
  targetSeconds: Long,
  elapsedSinceDispatchMs: Long,
  alreadyConfirmed: Boolean,
  ticksPerSecond: Long,
  toleranceSeconds: Long = SeekSettlePolicy.TOLERANCE_SECONDS,
  maxHoldMs: Long = SeekSettlePolicy.MAX_HOLD_MS,
): TicksSettleOutcome {
  val rawSeconds = rawTicks / ticksPerSecond
  val outcome =
    settleSeekPosition(
      rawSeconds = rawSeconds,
      targetSeconds = targetSeconds,
      elapsedSinceDispatchMs = elapsedSinceDispatchMs,
      alreadyConfirmed = alreadyConfirmed,
      toleranceSeconds = toleranceSeconds,
      maxHoldMs = maxHoldMs,
    )
  // 非「替换」时原样返回 ticks：不做 ticks→秒→ticks 往返，免得整除把小数秒抹掉。
  return TicksSettleOutcome(
    ticks = if (outcome.substituted) outcome.seconds * ticksPerSecond else rawTicks,
    substituted = outcome.substituted,
  )
}
