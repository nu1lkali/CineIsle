package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.Debanding

class DecoderPreferences(
  preferenceStore: PreferenceStore,
) {
  val profile = preferenceStore.getString("mpv_profile", "fast")
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)

  /**
   * 渲染器：true = gpu-next（libplacebo），false = 旧版 gpu。
   *
   * **默认开启**（mpv 上游 0.37 起的默认渲染器也是它）。这直接决定「杜比视界能不能播」：
   * DV Profile 5 的 IPTPQc2 色彩没有 HDR10 兜底层，只有 gpu-next 的 libplacebo
   * 会读 HEVC 流里的 DV RPU 元数据做映射 —— 旧版 gpu 下播放 Profile 5 会整屏紫绿。
   * Profile 7 / 8 的 RPU 同样由 gpu-next 处理。愿意接受旧渲染器的用户仍可关掉。
   */
  val gpuNext = preferenceStore.getBoolean("gpu_next", true)
  val useVulkan = preferenceStore.getBoolean("use_vulkan", false)
  val useYUV420P = preferenceStore.getBoolean("use_yuv420p", false)

  val debanding = preferenceStore.getEnum("debanding", Debanding.None)
  val debandIterations = preferenceStore.getInt("deband_iterations", 1)
  val debandThreshold = preferenceStore.getInt("deband_threshold", 48)
  val debandRange = preferenceStore.getInt("deband_range", 16)
  val debandGrain = preferenceStore.getInt("deband_grain", 32)

  val brightnessFilter = preferenceStore.getInt("filter_brightness")
  val saturationFilter = preferenceStore.getInt("filter_saturation")
  val gammaFilter = preferenceStore.getInt("filter_gamma")
  val contrastFilter = preferenceStore.getInt("filter_contrast")
  val hueFilter = preferenceStore.getInt("filter_hue")
  val sharpnessFilter = preferenceStore.getInt("filter_sharpness")

  // Anime4K Preferences
  val enableAnime4K = preferenceStore.getBoolean("enable_anime4k", false)
  val anime4kMode = preferenceStore.getString("anime4k_mode", "OFF")
  val anime4kQuality = preferenceStore.getString("anime4k_quality", "FAST")
}
