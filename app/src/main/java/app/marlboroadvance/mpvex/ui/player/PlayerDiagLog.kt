package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放器诊断日志 —— **只在设置里打开「详细日志」时才写**。
 *
 * ## 为什么需要它
 *
 * 有些卡顿是**没有设备就定位不了**的。典型例子：「挂了外挂字幕时，竖屏↔横屏切一下会卡一下」
 * （排查记录见 `memory/2026-10-02.md` 续十九 / 续二十）—— 四条嫌疑（Activity 重建、
 * 我们自己的旋转路径、字体/样式重解析、字幕恢复链重跑）全部被源码级排除之后，就只剩
 * 「mpv 内部 resize 路径」这一片黑箱，靠读代码推不出来。
 *
 * 而**手机上的第三方 logcat 应用在非 root 情况下读不到别的应用的日志**，
 * 所以这里不走 logcat，改成**写文件**，并且放在**应用外部目录**
 * （`Android/data/<包名>/files/`）—— 用户用文件管理器就能取出来。
 *
 * ## 与 mpv 自己的日志对齐
 *
 * [mpvLogPath] 给的是同一个目录下的 `mpv-log.txt`：打开「详细日志」后，
 * [MPVView] 会把它设成 mpv 的 `log-file`，于是 mpv 自己那一路（vo 重建、硬解重协商、
 * 字幕渲染…）也会落盘。两边都是「时:分:秒.毫秒」，**可以直接按时间戳对齐**看
 * 「旋转那一刻 mpv 内部在做什么」。
 *
 * ## 边界
 *
 * - 只在 `enabled` 为真时写；关掉时 [log] 直接返回，**零开销**；
 * - 单文件上限 [MAX_BYTES]，超了从头写（诊断日志不该把用户存储写爆）；
 * - 只用于**离散事件**（旋转、打开文件…），不要在逐帧循环里调 —— 每次都会开闭文件。
 */
internal object PlayerDiagLog {
  private const val TAG = "CineIsleDiag"
  private const val FILE_NAME = "cineisle-diag.log"
  private const val MPV_FILE_NAME = "mpv-log.txt"

  /** 单文件上限：超了就删掉重写。 */
  private const val MAX_BYTES = 512L * 1024L

  @Volatile private var target: File? = null

  @Volatile private var enabled = false

  private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  /** 外部私有目录（`Android/data/<包名>/files/`）；拿不到就退回内部 `files/`。 */
  private fun dirOf(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

  /**
   * 按设置里的「详细日志」开关初始化。关掉时把 [target] 置空，[log] 直接短路。
   *
   * 在 `onCreate` 与 `onResume` 各调一次 —— 用户在设置里改完开关回到播放页即生效。
   */
  fun configure(
    context: Context,
    on: Boolean,
  ) {
    enabled = on
    // 路径**总是**备好：`log()` 仍按 [enabled] 短路，但 [forceLog] 要在
    // 「详细日志关着」的情况下也能落盘（见 forceLog 的说明）。
    target = File(dirOf(context), FILE_NAME)
  }

  /** mpv 的 `log-file` 目标路径；未启用详细日志时返回 null（= 不设这个选项）。 */
  fun mpvLogPath(context: Context): String? =
    if (enabled) File(dirOf(context), MPV_FILE_NAME).absolutePath else null

  /** 诊断日志与 mpv 日志所在目录，用于在日志里标出来告诉用户去哪儿取。 */
  fun dirPath(context: Context): String = dirOf(context).absolutePath

  /**
   * 写一行。格式 `时:分:秒.毫秒 [tag] message` —— 与 mpv 自己的日志同一时间基准，便于对齐。
   */
  @Synchronized
  fun log(
    tag: String,
    message: String,
  ) {
    if (!enabled) return
    write(tag, message)
  }

  /**
   * 无视「详细日志」开关，强制写一行。
   *
   * ## 为什么需要一条绕过开关的通道
   *
   * 有些故障**只在关着详细日志时复现**：打开详细日志本身会给 mpv 加 `msg-level=all=v`
   * 和 `log-file`，每秒几十上百行落盘，本身就可能是卡顿源 —— 拿它去测「卡不卡」
   * 等于带着沙袋跑步。可「解码器到底立没立起来」这种关键结论又必须落盘。
   *
   * 所以只给**少数几个关键节点**（首帧后 3 秒的解码/渲染实况）开这条通道：
   * 一次播放只写一两行，代价可以忽略，又不会污染测量。
   */
  @Synchronized
  fun forceLog(
    tag: String,
    message: String,
  ) = write(tag, message)

  private fun write(
    tag: String,
    message: String,
  ) {
    val line = "${stamp.format(Date())} [$tag] $message"
    Log.d(TAG, line)
    val file = target ?: return
    runCatching {
      if (file.length() > MAX_BYTES) file.delete()
      file.appendText(line + "\n")
    }
  }
}
