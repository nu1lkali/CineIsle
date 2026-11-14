package app.marlboroadvance.mpvex.ui.player

import android.os.SystemClock
import kotlin.math.abs

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
 * 里的最终落点），所以结算时不该盲信一次瞬时读数，而应当用它校正：
 *
 * · 距下发不到 [SETTLE_WINDOW_MS] 且读数与目标差得超过 [SETTLE_TOLERANCE_SECONDS]
 *   ⇒ 认为 seek 还没落地，**用目标位置**；
 * · 其余情况（早就落地了 / 差值在关键帧粒度内 / 从没跳转过）⇒ 原样返回。
 *
 * 这条校正是**双向**的：往前拖时防 `0`，往回拖时防「还停在拖动前的高位」。
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
  /**
   * 「刚下发过 seek」的有效窗口。
   *
   * 网络流上一次 seek 的落地时间实测在 1~4 秒（丢缓存 → Range 重连 → 定位到关键帧 →
   * 解出第一帧），取 8 秒留一倍余量。窗口**必须小于两倍上报周期（10s × 2）**：
   * 否则「拖完接着看了十几秒」的那种正常读数也会被拖回目标位置。
   */
  const val SETTLE_WINDOW_MS = 8_000L

  /**
   * 允许「还没到位」的差值。
   *
   * mpv 的 `absolute+keyframes` 会落到目标**之前**的关键帧上，GOP 长一点能差近 10 秒；
   * 12 秒 = 关键帧粒度 + 一点抖动余量。差值在这之内就当已经落地、照读数上报。
   */
  const val SETTLE_TOLERANCE_SECONDS = 12L

  /** 「目标就是开头」的判定阈值：1 秒以内都算用户要求回到开头。 */
  private const val ZERO_TARGET_SECONDS = 1L

  /** 最近一次跳转的目标（秒）；-1 = 本次播放里还没有过跳转。 */
  @Volatile private var targetSeconds: Long = -1L

  /** 最近一次跳转的下发时刻（`SystemClock.elapsedRealtime()`）。 */
  @Volatile private var dispatchedAt: Long = 0L

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
  }

  /** 换片 / 本场收尾后清空，见类注释「边界」。 */
  fun clear() {
    targetSeconds = -1L
    dispatchedAt = 0L
  }

  /**
   * 用「刚下发过的跳转目标」校正一次位置读数。
   *
   * @param seconds 播放器报上来的位置（秒）。调用方保证非 null —— 读不到应当由调用方
   *   自己跳过本轮，不要把 null 折成 0 送进来（那个 0 会直接变成续播点）。
   * @return 校正后的位置（秒）。没有任何可校正的跳转时原样返回 [seconds]。
   */
  fun settle(seconds: Long): Long {
    val target = targetSeconds
    if (target < 0L) return seconds
    val elapsed = SystemClock.elapsedRealtime() - dispatchedAt
    val fixed = settleSeekPosition(seconds, target, elapsed)
    if (fixed != seconds) {
      PlayerDiagLog.log(
        "report",
        "seek 未落地，位置按目标校正：raw=${seconds}s target=${target}s since=${elapsed}ms",
      )
    }
    return fixed
  }

  /**
   * 这次的 `0` 是不是**用户自己要的**（拖回开头）。
   *
   * 给最后的「别把续播点写零」闸门用：只有用户明确要求跳到开头时，`0` 才可信。
   * 不做时间窗口判定 —— 目标一旦是 0，本场之内它都代表同样的意图。
   */
  fun seekTargetsZero(): Boolean = targetSeconds in 0..ZERO_TARGET_SECONDS

  /** 当前跳转目标（秒）；没有则 -1。仅用于日志与调试。 */
  fun currentTargetSeconds(): Long = targetSeconds
}

/**
 * [PlayerSeekTracker.settle] 的**纯函数**部分（不碰状态、不读时钟，可直接单测）。
 *
 * @param rawSeconds 播放器报的位置
 * @param targetSeconds 最近一次跳转的目标
 * @param elapsedSinceDispatchMs 距那次跳转下发的毫秒数
 * @param windowMs 有效窗口，超过就认为早就落地了
 * @param toleranceSeconds 允许的差值，在这之内算「已经到位」
 */
internal fun settleSeekPosition(
  rawSeconds: Long,
  targetSeconds: Long,
  elapsedSinceDispatchMs: Long,
  windowMs: Long = PlayerSeekTracker.SETTLE_WINDOW_MS,
  toleranceSeconds: Long = PlayerSeekTracker.SETTLE_TOLERANCE_SECONDS,
): Long {
  if (targetSeconds < 0L) return rawSeconds
  if (rawSeconds < 0L) return rawSeconds
  // 时钟回绕 / 记录比现在还晚：不敢判，原样返回
  if (elapsedSinceDispatchMs < 0L || elapsedSinceDispatchMs > windowMs) return rawSeconds
  return if (abs(rawSeconds - targetSeconds) > toleranceSeconds) targetSeconds else rawSeconds
}
