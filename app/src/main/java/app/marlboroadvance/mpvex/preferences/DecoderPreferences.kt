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

  /**
   * HDR 输出（`target-colorspace-hint`）。
   *
   * ## 为什么要独立于 [gpuNext]
   *
   * 原先这个行为是**隐式绑在 gpuNext 上的**：只要 gpu-next 开着就自动
   * `target-colorspace-hint=yes`，用户没有任何开关能关掉它（见 `MPVView.initOptions`）。
   * 但它和渲染器其实是**两件事**：
   *  · 渲染器（gpu-next / gpu）决定「能不能正确解码并映射 HDR 片源」；
   *  · colorspace hint 决定「**要不要**把屏幕切进 HDR 亮度区间」。
   *
   * 二者拆开才说得通：
   *  · **默认开启**（沿用原行为，老用户观感不变）；
   *  · 不支持 HDR 的屏幕 —— 开了也无害，Android 会忽略这个 hint；
   *  · 但**部分设备/系统版本上这个 hint 会让画面明显偏暗或发灰**
   *    （屏幕本身没到 HDR 亮度却按 HDR 路径输出），这时就需要能手动关掉。
   *
   * ⚠️ 只在 gpu-next 下有意义：旧版 gpu 没有这条通路，设了也无效。
   *   见 `MPVView.initOptions` 里对 gpu-next / libplacebo 的说明。
   */
  val hdrEnabled = preferenceStore.getBoolean("hdr_enabled", true)

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
