package app.marlboroadvance.mpvex.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

/**
 * 播放期的「片源色彩策略」：杜比视界 P5 修正 + HDR 显示输出。
 *
 * 两件事都必须在**开片之后**才能判定 —— 片源的色彩信息（DV profile、
 * PQ / HLG、静态峰值）只有解出第一帧才拿得到，所以不像 `hwdec` / `vo` 那样
 * 能在 [MPVView.initOptions] 里一次写完。这里由 [PlayerActivity] 在
 * `file-loaded` / `playback-restart` / `video-params/w` 三个时机重复调用，
 * 每次都是幂等的：只有「目标状态」变化时才真的动 mpv 属性或 Window。
 *
 * ## ① DV Profile 5 颜色修正
 *
 * Android 的 MediaCodec 不把 DV RPU 挂到输出帧上 ⇒ 硬解 P5 必然紫绿反色。
 * 命中 P5 时把 `hwdec` 临时降到 `no`，让 ffmpeg 软解把 RPU 交给
 * gpu-next / libplacebo 重塑；换到别的片源立刻恢复 [DecoderPreferences.defaultHwdec]。
 * **只让 P5 让路**，其余片源（含 Profile 7 / 8）继续硬解。
 *
 * ## ② HDR 显示输出
 *
 * `target-colorspace-hint` 只是 libplacebo 的提示，Android 上只有 Vulkan 后端
 * 会把它转成 Surface 的 HDR dataspace，默认 OpenGL 后端直接忽略。真正让
 * **系统**切进 HDR 显示模式的是 `Window.setColorMode(COLOR_MODE_HDR)`
 * （Android 官方接口，API 26+，本项目 minSdk 26）。因此这里按片源动态切：
 * HDR 片 + 设备报告支持 HDR → 切 HDR；SDR 片 / 退出播放页 → 复位默认，
 * 免得 Compose 控件层被拉进 HDR 亮度后发白。
 */
object PlaybackColorPolicy {
  private const val TAG = "PlaybackColorPolicy"

  /** null = 本次播放尚未判定过；true/false = Window 当前所处的色彩模式。 */
  @Volatile
  private var appliedHdr: Boolean? = null

  /** 当前 `hwdec` 是否正被 P5 修正压在软解上。换片恢复用。 */
  @Volatile
  private var p5HwdecOverridden = false

  /**
   * 设备是否报告支持 HDR 输出。
   *
   * `Display.HdrCapabilities.supportedHdrTypes` 为非空数组才算数（HDR10 / HLG /
   * HDR10+ / DV）。厂商没上报时不要硬切 colorMode：屏幕达不到 HDR 亮度，
   * 切过去只会让画面发灰，还白搭一次窗口重配。
   */
  fun deviceSupportsHdr(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return runCatching {
      val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
      val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
      display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }.getOrDefault(false)
  }

  /**
   * 当前视频轨的 DV Profile；非 DV 片返回 0。
   *
   * `track-list/N/dolby-vision-profile` 是 mpv 0.36+ 的标准字段（项目里的
   * [TrackNode] 也在反序列化它）。个别构建把它挂在 `video-params` 下，
   * 所以留一条兜底；读不到就当普通片，不做任何降级。
   */
  fun dolbyVisionProfile(): Int {
    val count = PlayerLib.getPropertyInt("track-list/count") ?: return 0
    for (i in 0 until count.coerceAtMost(64)) {
      if (PlayerLib.getPropertyString("track-list/$i/type") != "video") continue
      val profile = PlayerLib.getPropertyInt("track-list/$i/dolby-vision-profile") ?: 0
      if (profile > 0) return profile
    }
    return PlayerLib.getPropertyInt("video-params/dolby-vision-profile") ?: 0
  }

  /** 片源是否属于 HDR：PQ / HLG 传递函数、静态峰值元数据，或任意 DV profile。 */
  private fun isHdrSource(doviProfile: Int): Boolean {
    if (doviProfile > 0) return true
    when (PlayerLib.getPropertyString("video-params/gamma")) {
      "pq", "hlg" -> return true
    }
    return (PlayerLib.getPropertyDouble("video-params/sig-peak") ?: 0.0) > 0.0
  }

  /**
   * 按当前片源重算并应用两条策略。可在一次播放中被反复调用（幂等）。
   *
   * 所有 mpv 属性写 / Window 操作都投到主线程：`PlayerObserver` 已经把回调
   * 投递过一次，这里再兜一层，将来谁直接从 libmpv 线程调也不会跨线程碰 Window。
   * 判定与写入整体 `runCatching`：observer 链上抛异常 = 未捕获异常 = 进程死。
   */
  fun apply(
    activity: Activity,
    prefs: DecoderPreferences,
  ) {
    if (!PlayerLib.isCoreReady || activity.isFinishing || activity.isDestroyed) return

    val profile = runCatching { dolbyVisionProfile() }.getOrDefault(0)
    val hdrSource = runCatching { isHdrSource(profile) }.getOrDefault(false)
    val wantP5Fix =
      prefs.dolbyVisionColorFix.get() &&
        prefs.gpuNext.get() &&
        !prefs.directOutput.get() &&
        profile == 5
    val wantHdr = prefs.hdrEnabled.get() && deviceSupportsHdr(activity) && hdrSource

    activity.runOnUiThread {
      runCatching {
        if (wantP5Fix != p5HwdecOverridden) {
          val hwdec = if (wantP5Fix) "no" else prefs.defaultHwdec()
          PlayerLib.setPropertyString("hwdec", hwdec)
          p5HwdecOverridden = wantP5Fix
          Log.i(TAG, "DV P5 fix=${wantP5Fix}, hwdec -> $hwdec (profile=$profile)")
        }

        if (wantHdr != appliedHdr) {
          // 首次判定且仍是 SDR：Window 本来就是 COLOR_MODE_DEFAULT，
          // 没必要为一个「没变化」触发一次窗口重配。
          if (appliedHdr != null || wantHdr) {
            activity.window.setColorMode(
              if (wantHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT,
            )
          }
          appliedHdr = wantHdr
          Log.i(TAG, "window colorMode -> ${if (wantHdr) "HDR" else "SDR"} (hdrSource=$hdrSource)")
        }
      }.onFailure { Log.w(TAG, "apply 失败（已忽略）", it) }
    }
  }

  /**
   * 退出播放页时复位。必须在主线程调（[PlayerActivity.onDestroy] 本来就在主线程）。
   * 只复位「确实切到过 HDR」的情况，普通 SDR 播放不碰 Window。
   */
  fun reset(activity: Activity) {
    if (appliedHdr == true) {
      runCatching { activity.window.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT) }
        .onFailure { Log.w(TAG, "复位 colorMode 失败（已忽略）", it) }
    }
    appliedHdr = null
    p5HwdecOverridden = false
  }
}
