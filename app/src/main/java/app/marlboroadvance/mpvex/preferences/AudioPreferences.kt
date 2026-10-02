package app.marlboroadvance.mpvex.preferences

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

class AudioPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("audio_preferred_languages")
  val defaultAudioDelay = preferenceStore.getInt("audio_delay_default")
  val audioPitchCorrection = preferenceStore.getBoolean("audio_pitch_correction", true)
  val audioChannels = preferenceStore.getEnum("audio_channels", AudioChannels.AutoSafe)
  val volumeBoostCap = preferenceStore.getInt("audio_volume_boost_cap", 30)
  val automaticBackgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  val volumeNormalization = preferenceStore.getBoolean("audio_volume_normalization", false)

  /**
   * 夜间模式（音量均衡）强度档。
   *
   * 只在 [volumeNormalization] 打开时参与 `af` 链组装；关掉开关时忽略本项。
   * 默认中档 —— 既压得住爆音，又不至于把动态范围压平。
   */
  val normalizationStrength =
    preferenceStore.getEnum("audio_normalization_strength", VolumeNormalizationStrength.Medium)
}

enum class AudioChannels(
  @StringRes val title: Int,
  val property: String,
  val value: String,
) {
  Auto(R.string.pref_audio_channels_auto, "audio-channels", "auto-safe"),
  AutoSafe(R.string.pref_audio_channels_auto_safe, "audio-channels", "auto"),
  Mono(R.string.pref_audio_channels_mono, "audio-channels", "mono"),
  Stereo(R.string.pref_audio_channels_stereo, "audio-channels", "stereo"),
  ReverseStereo(R.string.pref_audio_channels_stereo_reversed, "af", "pan=[stereo|c0=c1|c1=c0]"),
}

/**
 * 夜间模式（音量均衡）强度档。
 *
 * 三档都基于 mpv 的 `dynaudnorm` 动态归一化滤镜，只是参数不同：
 * 档位越高，目标峰值 `p` 越低、最大增益 `m` 越大 —— 弱对白听得清，爆炸 / 枪声被压得更狠，
 * 代价是动态范围更小、听感更「平」。
 *
 * - `f` 分析帧长（ms）：越长越平滑，但对突发的响应越慢；
 * - `g` 高斯窗口宽度（奇数）：影响平滑程度；
 * - `p` 目标峰值：0.95 基本不压，0.80 明显压；
 * - `m` 最大放大倍数（dB）：越大越能把小声拉起来；
 * - `r` 目标 RMS：仅强档启用，进一步收紧整体响度。
 */
enum class VolumeNormalizationStrength(
  @StringRes val title: Int,
  val afFragment: String,
) {
  Light(R.string.pref_audio_normalization_light, "dynaudnorm=f=150:g=5:p=0.95:m=10"),
  Medium(R.string.pref_audio_normalization_medium, "dynaudnorm=f=200:g=11:p=0.90:m=18"),
  Strong(R.string.pref_audio_normalization_strong, "dynaudnorm=f=300:g=15:p=0.80:m=25:r=0.5"),
}
