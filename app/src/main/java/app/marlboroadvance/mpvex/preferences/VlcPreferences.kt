package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

/**
 * VLC 内核自己的偏好（`vlc_` 前缀）。
 *
 * 与 [PlayerPreferences] / [GsyPreferences] 完全独立：VLC 播放页只读这一份，
 * mpv / GSY 的行为不会因为这里改动而受影响。
 */
class VlcPreferences(
  private val preferenceStore: PreferenceStore,
) {
  /** 硬件解码（MediaCodec）；关掉后走 VLC 自己的软件解码链。 */
  val hardwareDecode = preferenceStore.getBoolean("vlc_hw_decode", true)

  /** 网络流缓存（毫秒）；越大越抗抖动，起播越慢。 */
  val networkCachingMs = preferenceStore.getInt("vlc_network_caching_ms", 3000)

  /** 本地文件缓存（毫秒）。 */
  val fileCachingMs = preferenceStore.getInt("vlc_file_caching_ms", 1000)

  /** 去隔行；老 DVD / 电视录制源再开。 */
  val deinterlace = preferenceStore.getBoolean("vlc_deinterlace", false)

  /** 丢晚帧 / 跳帧：解码跟不上时优先保持音画同步。 */
  val dropLateFrames = preferenceStore.getBoolean("vlc_drop_late_frames", true)

  /** 跳帧（与丢晚帧分开，VLC 两个选项语义不同）。 */
  val skipFrames = preferenceStore.getBoolean("vlc_skip_frames", true)

  /** 切到后台时继续播放；没有前台服务时系统仍可能在内存紧张时杀进程。 */
  val backgroundPlayback = preferenceStore.getBoolean("vlc_background_playback", false)

  /** 默认画面比例：auto / 16:9 / 4:3 / fill。 */
  val defaultAspect = preferenceStore.getString("vlc_default_aspect", "auto")

  /** 字幕字号百分比（50–200）。 */
  val subtitleScalePercent = preferenceStore.getInt("vlc_subtitle_scale_percent", 100)

  /** 字幕底部边距（像素，0–160）。 */
  val subtitleMargin = preferenceStore.getInt("vlc_subtitle_margin", 0)

  /** 音频延迟（毫秒，-5000–5000）。 */
  val audioDelayMs = preferenceStore.getInt("vlc_audio_delay_ms", 0)

  /** 字幕延迟（毫秒，-5000–5000）。 */
  val subtitleDelayMs = preferenceStore.getInt("vlc_subtitle_delay_ms", 0)

  /** 首选音频语言（VLC 语言码，如 `chi` / `eng`；空 = 自动）。 */
  val audioLanguage = preferenceStore.getString("vlc_audio_language", "")

  /** 首选字幕语言（VLC 语言码；空 = 自动）。 */
  val subtitleLanguage = preferenceStore.getString("vlc_subtitle_language", "")

  /**
   * 额外 VLC 命令行参数，每行一个（不含前缀也可以）。
   *
   * 这是给「设置页还没做成开关」的 VLC 选项留的逃生口；改错只影响 VLC 播放页，
   * 不会波及 mpv/GSY。
   */
  val extraOptions = preferenceStore.getString("vlc_extra_options", "")
}
