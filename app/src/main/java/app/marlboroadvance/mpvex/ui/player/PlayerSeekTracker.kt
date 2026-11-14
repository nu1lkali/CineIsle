package app.marlboroadvance.mpvex.ui.player

import android.os.SystemClock

/**
 * 「最近一次绝对跳转的目标位置」的唯一记账处。
 *
 * ## 为什么非要有它（v256，用户报「快速拖动进度条然后返回，详情页进度变成 0%」）
 *
 * 出问题的那条链路是**退出结算**：
 *
 * ```
 * 用户拖动 → PlayerLib.command("seek", 40:00) → 立刻返回
 *          → 播放页 onPause / onStop → EmbyPlaybackReporter.stop()
 *          → 读 time-pos（此刻 mpv 正在为这次 seek 重连取流）
 * ```
 *
 * `mpv` 在一次 seek 真的落地之前，`time-pos` 有两种**都不能用**的取值：
 *
 * · **`0`** —— 取流层被推倒重来（HTTP Range 重连 / 解复用器重启），此刻的播放时间戳
 *   属于「新起的那一段」，绝对位置就是文件开头；
 * · **拖动前的旧值** —— 新位置还没解码出第一帧，上报的是上一帧的时间戳。
 *
 * 两种值都会被当成「用户正在这里看」写进服务端 `PositionTicks`：前者把续播点**清零**
 * （详情页进度条变 0%，「继续观看」里那条也退回开头），后者把续播点**挪回拖动前**。
 * 这不是阈值调不调的问题 —— 这类读数在这个窗口里**本身就没有意义**。
 *
 * 而「用户想跳到哪」这个信息我们**自己手里就有**（`PlayerViewModel.dispatchAbsoluteSeek`
 * 里的最终落点），所以结算时不该盲信一次瞬时读数，而应当用它校正。
 *
 * ## v259：为什么「修过一次还是没好」
 *
 * 两处都出在**判据本身**，不是阈值：
 *
 * 1. **校正靠时间窗口（8 秒）**。网络流上一次 seek 落地常常超过 8 秒（丢缓存 → Range
 *    重连 → 定位关键帧 → 解出第一帧），窗口一过就重新采信读数 —— 那时读数正好还是 0。
 *    现在改成**状态判定**：只要读数还没跑到目标附近就一直用目标，见
 *    [SeekSettlePolicy.MAX_HOLD_MS] 的注释。
 * 2. **目标本身可能是 0**。`PlayerViewModel.dispatchAbsoluteSeek` 里
 *    `PlayerLib.getPropertyInt("duration") ?: 0` 会把「时长读不到」变成「时长 0 秒」，
 *    于是 `position.coerceIn(0, 0)` ⇒ 目标被夹成 0 ⇒ 这里记下的就是「用户要跳到开头」。
 *    此后 [seekTargetsZero] 恒真（两道「读到 0 就不许上报」的闸门全部失效），
 *    而 [settle] 又会把任何一个读数都校正成 0 —— 一次拖动就把 Progress / Stopped /
 *    本地续播三条写入全部打成 0。**上一版修的所有逻辑，反而成了把 0 送出去的高速公路。**
 *    目标端的病根在 `PlayerViewModel` 那边堵死（时长未知时不再夹取），这里补一道
 *    「0 意图必须限时」的兜底（见 [seekTargetsZero]）。
 *
 * ## 为什么不做成「一律取最大值」之类的启发式
 *
 * 用户**有权**把进度拖回开头。`0` 只在「用户没要求跳到开头」时才可疑，所以判据必须是
 * 「这次零是不是我们自己下发的目标」，而不是「零看起来不像真的」——
 * 后者会在「拖回 0:00 再退出」时把续播点强行留在原位，比原 bug 更难解释。
 *
 * ## 边界
 *
 * · 纯内存状态，进程内单例：播放页与 Emby 上报器各在一处读它，不需要传参；
 * · 换片 / 收尾时由调用方 [clear]，避免上一集的跳转目标污染下一集；
 * · 写它的只有 `PlayerViewModel`（主线程 + IO 各一处），读它的可能是 IO 线程 ⇒ `@Volatile`。
 */
internal object PlayerSeekTracker {
  /** 最近一次跳转的目标（秒）；-1 = 本次播放里还没有过跳转。 */
  @Volatile private var targetSeconds: Long = -1L

  /** 最近一次跳转的下发时刻（`SystemClock.elapsedRealtime()`）。 */
  @Volatile private var dispatchedAt: Long = 0L

  /**
   * 这一次跳转是否已经**确认落地**（读数跑到目标附近过）。
   *
   * 落地之后就不能再拿目标去压读数：用户可能只是拖到 40 分钟看了一眼、接着又拖到
   * 12 分钟，而 12 分钟那次如果没记进目标（异常路径），继续用旧目标会把位置拉回去。
   * 只要确认过一次，就说明取流层已经完成定位，之后的读数一律照用。
   */
  @Volatile private var confirmed: Boolean = false

  /**
   * 记一笔「用户要求跳到这里」。
   *
   * 两个调用点都在 `PlayerViewModel`：拖动进度条 / 章节跳转（绝对 seek）、
   * 双击快进快退（相对 seek，换算成绝对目标）。传的是**最终落点**（已 clamp 过时长）。
   *
   * @param seconds 目标位置，单位秒；负数（无效值）直接忽略。
   */
  fun noteAbsoluteSeek(seconds: Int) {
    if (seconds < 0) return
    targetSeconds = seconds.toLong()
    dispatchedAt = SystemClock.elapsedRealtime()
    confirmed = false
    if (seconds <= SeekSettlePolicy.ZERO_TARGET_SECONDS) {
      // 这是「0 是从哪儿来的」的取证线：正常拖动只会在用户把进度条拖到最左端时命中。
      // 若日志里在用户没拖到开头的情况下出现它，说明上游把「时长未知」又当成了 0 —— 见类注释 v259 第 2 条。
      PlayerDiagLog.log("seek", "记录跳转目标 = 0（视为用户要求回到开头）")
    }
  }

  /** 换片 / 本场收尾后清空，见类注释「边界」。 */
  fun clear() {
    targetSeconds = -1L
    dispatchedAt = 0L
    confirmed = false
  }

  /**
   * 用「刚下发过的跳转目标」校正一次位置读数。
   *
   * @param seconds 播放器报上来的位置（秒）。调用方保证非 null —— 读不到应当由调用方
   *   自己跳过本轮，不要把 null 折成 0 送进来（那个 0 会直接变成续播点）。
   * @return 校正后的位置（秒）。没有任何可校正的跳转时原样返回 [seconds]。
   */
  fun settle(seconds: Long): Long = settleDetailed(seconds).seconds

  /**
   * 同 [settle]，但把「这个值是不是实测读数」一并给出。
   *
   * 实时上报要据此跳过本轮（见 [SeekSettleOutcome.substituted]），退出结算则照用。
   */
  fun settleDetailed(seconds: Long): SeekSettleOutcome {
    val target = targetSeconds
    if (target < 0L) return SeekSettleOutcome(seconds, false)
    val elapsed = SystemClock.elapsedRealtime() - dispatchedAt
    // 先判「落地」：落地这一拍之后的所有读数都是可信的
    if (isAtSeekTarget(seconds, target)) {
      confirmed = true
      return SeekSettleOutcome(seconds, false)
    }
    val outcome = settleSeekPosition(seconds, target, elapsed, alreadyConfirmed = confirmed)
    if (outcome.substituted) {
      PlayerDiagLog.forceLog(
        "report",
        "seek 未落地，位置按目标校正：raw=${seconds}s target=${target}s since=${elapsed}ms",
      )
    }
    return outcome
  }

  /**
   * 同 [settleDetailed]，但输入 / 输出都是 **ticks** —— Emby 上报器全程用 ticks，
   * 由它自己换算容易再把量纲搞错（见 [settleTicksPosition] 的类注释），所以收在这里。
   *
   * @param ticksPerSecond 1 秒对应的 ticks（Emby 是 `EmbyTicks.TICKS_PER_SECOND`）。
   */
  fun settleDetailedTicks(rawTicks: Long, ticksPerSecond: Long): TicksSettleOutcome {
    val target = targetSeconds
    if (target < 0L) return TicksSettleOutcome(rawTicks, false)
    val elapsed = SystemClock.elapsedRealtime() - dispatchedAt
    // 「已落地」的判据是秒，读数先换算；这一拍之后 confirmed 置位，之后照用实测值。
    if (isAtSeekTarget(rawTicks / ticksPerSecond, target)) {
      confirmed = true
      return TicksSettleOutcome(rawTicks, false)
    }
    val outcome =
      settleTicksPosition(
        rawTicks = rawTicks,
        targetSeconds = target,
        elapsedSinceDispatchMs = elapsed,
        alreadyConfirmed = confirmed,
        ticksPerSecond = ticksPerSecond,
      )
    if (outcome.substituted) {
      PlayerDiagLog.forceLog(
        "report",
        "seek 未落地，位置按目标校正：raw=${rawTicks}ticks target=${target}s since=${elapsed}ms",
      )
    }
    return outcome
  }

  /**
   * 这次的 `0` 是不是**用户自己要的**（拖回开头）。
   *
   * 给「别把续播点写零」的闸门用：只有用户明确要求跳到开头、且**就是刚才**，`0` 才可信。
   *
   * ⚠️ 必须限时，理由见 [SeekSettlePolicy.ZERO_INTENT_WINDOW_MS]。
   */
  fun seekTargetsZero(): Boolean {
    val target = targetSeconds
    if (target !in 0..SeekSettlePolicy.ZERO_TARGET_SECONDS) return false
    return SystemClock.elapsedRealtime() - dispatchedAt <= SeekSettlePolicy.ZERO_INTENT_WINDOW_MS
  }

  /** 当前跳转目标（秒）；没有则 -1。仅用于日志与调试。 */
  fun currentTargetSeconds(): Long = targetSeconds
}
