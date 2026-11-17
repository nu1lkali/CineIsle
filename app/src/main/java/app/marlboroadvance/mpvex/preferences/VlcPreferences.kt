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

  /** 字幕字号百分比（50–200）。 */
  val subtitleScalePercent = preferenceStore.getInt("vlc_subtitle_scale_percent", 100)

  /** 首选音频语言（VLC 语言码，如 `chi` / `eng`；空 = 自动）。 */
  val audioLanguage = preferenceStore.getString("vlc_audio_language", "")

  /** 首选字幕语言（VLC 语言码；空 = 自动）。 */
  val subtitleLanguage = preferenceStore.getString("vlc_subtitle_language", "")
}
