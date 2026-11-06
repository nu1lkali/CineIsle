package app.marlboroadvance.mpvex.ui.player

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference

enum class PlayerOrientation(
  @StringRes val titleRes: Int,
) {
  Free(R.string.pref_player_orientation_free),
  Video(R.string.pref_player_orientation_video),
  Portrait(R.string.pref_player_orientation_portrait),
  ReversePortrait(R.string.pref_player_orientation_reverse_portrait),
  SensorPortrait(R.string.pref_player_orientation_sensor_portrait),
  Landscape(R.string.pref_player_orientation_landscape),
  ReverseLandscape(R.string.pref_player_orientation_reverse_landscape),
  SensorLandscape(R.string.pref_player_orientation_sensor_landscape),
}

enum class VideoAspect(
  @StringRes val titleRes: Int,
) {
  Crop(R.string.player_aspect_crop),
  Fit(R.string.player_aspect_fit),
  Stretch(R.string.player_aspect_stretch),
}

enum class SingleActionGesture(
  @StringRes val titleRes: Int,
) {
  None(R.string.pref_gesture_double_tap_none),
  Seek(R.string.pref_gesture_double_tap_seek),
  PlayPause(R.string.pref_gesture_double_tap_play),
  Custom(R.string.pref_gesture_double_tap_custom),
}

enum class CustomKeyCodes(
  val keyCode: String,
) {
  DoubleTapLeft("MBTN_LEFT_DBL"),
  DoubleTapCenter("MBTN_MID_DBL"),
  DoubleTapRight("MBTN_RIGHT_DBL"),
  MediaPrevious("PREV"),
  MediaPlay("PLAYPAUSE"),
  MediaNext("NEXT"),
}

enum class Decoder(
  val title: String,
  val value: String,
) {
  AutoCopy("Auto", "auto-copy"),
  Auto("Auto", "auto"),
  SW("SW", "no"),
  HW("HW", "mediacodec-copy"),
  HWPlus("HW+", "mediacodec"),
  ;

  companion object {
    /**
     * 从 mpv 的 `hwdec-current` 取值映射到 [Decoder]。
     *
     * **不能用 `first { }`**：mpv 在不同平台 / 不同构建下可能返回表里没有的值
     * （`vulkan`、`vaapi`、`cuda`、`dxva2` …），GSY 内核也会走自己的一套命名，
     * 一旦匹配不到就是 `NoSuchElementException` 直接把播放页炸掉 ——
     * 而这里只是在 Compose 派生状态里算个图标，取不到值用 Auto 兜住即可。
     */
    fun getDecoderFromValue(value: String): Decoder =
      Decoder.entries.firstOrNull { it.value == value } ?: Decoder.Auto
  }
}

/**
 * **默认解码方式**（设置 → 解码器 →「默认解码器」）。
 *
 * 跟播放中面板里那个 [Decoder]（`hwdec-current` 的实时读数 + 临时切换）是两件事：
 * 那个是「现在这一条片子正在用什么」，这个是「以后每条片子默认用什么」。
 * 这里只留四档 —— 面板里的 `auto` / `auto-copy` 是给排查用的，放进设置页只会让
 * 普通用户不知道该选哪个；四档之间是**能力递进**（回退 → 不回退 → 零拷贝 → 软解），
 * 每一档都有明确的适用场景。
 */
enum class DefaultDecoder(
  @StringRes val titleRes: Int,
  /**
   * 真正写进 mpv `hwdec` 的值。
   *
   * `,no` 是**回退链**：前面那档用不了（编码不被 MediaCodec 支持、设备解码器拒绝这个规格）
   * 就退到下一档；所以「硬解优先」必须带 `no` 兜底，否则遇到不支持的片子直接播不了。
   */
  val hwdec: String,
  /**
   * 是否要求**零拷贝直出**（`vo=mediacodec_embed`）。
   *
   * ⚠️ 这个标志和一档 `hwdec=mediacodec` 是**绑死的一件事**，不能拆成两个开关：
   * mpv 的零拷贝档只在 `vo=mediacodec_embed` 下成立，vo 是 gpu / gpu-next 时会
   * 静默跳过它、退到 `mediacodec-copy`。换句话说「选了零拷贝但没切 vo」等于没选，
   * 而且没有任何报错 —— 之前它是个单独的「直出模式」开关，与这里的解码档位
   * 各说各话（开关一开就把档位整体覆盖），同一个决定有两个入口，注定对不上。
   */
  val directOutput: Boolean = false,
) {
  /** 硬解优先，用不了自动退软解。默认值，与旧版行为完全一致 */
  Auto(R.string.pref_decoder_default_auto, "mediacodec-copy,no"),

  /**
   * 只用硬解，**不回退**。
   *
   * 给两类人：一是排查「这条片子到底是硬解在花屏、还是软解太慢」；
   * 二是某些机型上软解路径本身有问题（比如 10bit 软解发灰），宁可播不了也不想要软解。
   * 代价要说清楚：编码不支持时是**直接不出画面**，不是变慢。
   */
  Hardware(R.string.pref_decoder_default_hw, "mediacodec-copy"),

  /**
   * 零拷贝硬解（HW+）：`hwdec=mediacodec` + `vo=mediacodec_embed`。
   *
   * 与 [Hardware] 的差别只在**搬运**：copyback 档是解码器把整帧回拷到 CPU 内存、
   * 再由 vo 重新上传成纹理（4K 一帧 12.4 MB，2160×3840@60 ≈ 1.5 GB/s），
   * 这一档让解码器输出 buffer 直接挂到 Surface 上，两次搬运都不存在 ——
   * 系统播放器 / MX Player 走的就是这条路径。4K 高帧率卡顿基本都出在这里。
   *
   * 代价：mpv 侧的叠加层与后处理全部失效 —— 字幕（外挂 / 内嵌都不显示）、OSD、
   * GLSL 着色器（Anime4K / 锐化）、截图。播放器界面本身是 Compose 独立图层，不受影响。
   * 所以它只能是一个**用户明确选择**的档位，不能做默认值。
   */
  HardwarePlus(R.string.pref_decoder_default_hwplus, "mediacodec,no", directOutput = true),

  /** 只用软解。兼容性最好（解码器不再是变量），代价是功耗、发热与 4K 高帧率可能跟不上 */
  Software(R.string.pref_decoder_default_sw, "no"),
}

enum class Debanding(
  @StringRes val titleRes: Int,
) {
  None(R.string.player_sheets_deband_none),
  CPU(R.string.player_sheets_deband_cpu),
  GPU(R.string.player_sheets_deband_gpu),
}

enum class MPVProfile(
  @StringRes val displayNameRes: Int,
  val value: String,
) {
  Fast(R.string.i18n_mpv_fast, "fast"),
  Default(R.string.i18n_mpv_default, "default"),
  HighQuality(R.string.i18n_mpv_high_quality, "high-quality"),
  GpuHQ(R.string.i18n_mpv_gpu_hq, "gpu-hq"),
  LowLatency(R.string.i18n_mpv_low_latency, "low-latency"),
  SwFast(R.string.i18n_mpv_sw_fast, "sw-fast"),
  ;

  override fun toString(): String = name

  companion object {
    fun fromValue(value: String): MPVProfile = entries.firstOrNull { it.value == value } ?: Fast
  }
}

enum class Sheets {
  None,
  PlaybackSpeed,
  SubtitleTracks,
  AudioTracks,
  Chapters,
  Decoders,
  More,
  VideoZoom,
  AspectRatios,
  Playlist,
  FrameNavigation,
  Cast,
  /** 录制 GIF 片段（3~10 秒） */
  GifRecord,
  /** 弹幕：开关 / 搜番 / 字号速度透明度 / 屏蔽词 */
  Danmaku,
}

enum class Panels {
  None,
  SubtitleSettings,
  SubtitleDelay,
  AudioDelay,
  VideoFilters,
}

sealed class PlayerUpdates {
  data object None : PlayerUpdates()

  data object MultipleSpeed : PlayerUpdates()

  data class DynamicSpeedControl(
    val speed: Float,
    val showFullOverlay: Boolean = true,
  ) : PlayerUpdates()

  data object AspectRatio : PlayerUpdates()

  data object VideoZoom : PlayerUpdates()

  data class HorizontalSeek(
    val currentTime: String,
    val seekDelta: String,
  ) : PlayerUpdates()

  data class ShowText(
    val value: String,
  ) : PlayerUpdates()

  data class RepeatMode(
    val mode: app.marlboroadvance.mpvex.ui.player.RepeatMode,
  ) : PlayerUpdates()

  data class Shuffle(
    val enabled: Boolean,
  ) : PlayerUpdates()

  data class FrameInfo(
    val currentFrame: Int,
    val totalFrames: Int,
  ) : PlayerUpdates()
}

/**
 * Filter presets for quick video color adjustments.
 * Each preset defines specific values for brightness, saturation, contrast, gamma, hue, and sharpness.
 * Sharpness uses MPV's 'sharpen' property which ranges from -5 (blur) to 5 (sharp).
 */
enum class FilterPreset(
  @StringRes val displayNameRes: Int,
  @StringRes val descriptionRes: Int,
  val brightness: Int,
  val saturation: Int,
  val contrast: Int,
  val gamma: Int,
  val hue: Int,
  val sharpness: Int,
) {
  NONE(
    displayNameRes = R.string.i18n_fp_none,
    descriptionRes = R.string.i18n_fp_none_desc,
    brightness = 0,
    saturation = 0,
    contrast = 0,
    gamma = 0,
    hue = 0,
    sharpness = 0,
  ),
  VIVID(
    displayNameRes = R.string.i18n_fp_vivid,
    descriptionRes = R.string.i18n_fp_vivid_desc,
    brightness = 5,
    saturation = 25,
    contrast = 15,
    gamma = 0,
    hue = 0,
    sharpness = 0,
  ),
  WARM_TONE(
    displayNameRes = R.string.i18n_fp_warm_tone,
    descriptionRes = R.string.i18n_fp_warm_tone_desc,
    brightness = 5,
    saturation = 10,
    contrast = 5,
    gamma = 5,
    hue = 15,
    sharpness = 0,
  ),
  COOL_TONE(
    displayNameRes = R.string.i18n_fp_cool_tone,
    descriptionRes = R.string.i18n_fp_cool_tone_desc,
    brightness = 0,
    saturation = 5,
    contrast = 10,
    gamma = 0,
    hue = -15,
    sharpness = 0,
  ),
  SOFT_PASTEL(
    displayNameRes = R.string.i18n_fp_soft_pastel,
    descriptionRes = R.string.i18n_fp_soft_pastel_desc,
    brightness = 10,
    saturation = -15,
    contrast = -10,
    gamma = 5,
    hue = 0,
    sharpness = 0,
  ),
  CINEMATIC(
    displayNameRes = R.string.i18n_fp_cinematic,
    descriptionRes = R.string.i18n_fp_cinematic_desc,
    brightness = -5,
    saturation = -10,
    contrast = 20,
    gamma = -5,
    hue = 5,
    sharpness = 0,
  ),
  DRAMATIC(
    displayNameRes = R.string.i18n_fp_dramatic,
    descriptionRes = R.string.i18n_fp_dramatic_desc,
    brightness = -10,
    saturation = 15,
    contrast = 30,
    gamma = -10,
    hue = 0,
    sharpness = 0,
  ),
  NIGHT_MODE(
    displayNameRes = R.string.i18n_fp_night_mode,
    descriptionRes = R.string.i18n_fp_night_mode_desc,
    brightness = -20,
    saturation = -5,
    contrast = 5,
    gamma = -10,
    hue = 0,
    sharpness = 0,
  ),
  NOSTALGIC(
    displayNameRes = R.string.i18n_fp_nostalgic,
    descriptionRes = R.string.i18n_fp_nostalgic_desc,
    brightness = 5,
    saturation = -20,
    contrast = 10,
    gamma = 0,
    hue = 20,
    sharpness = 0,
  ),
  GHIBLI_STYLE(
    displayNameRes = R.string.i18n_fp_ghibli,
    descriptionRes = R.string.i18n_fp_ghibli_desc,
    brightness = 8,
    saturation = 15,
    contrast = -5,
    gamma = 5,
    hue = 5,
    sharpness = 0,
  ),
  NEON_POP(
    displayNameRes = R.string.i18n_fp_neon_pop,
    descriptionRes = R.string.i18n_fp_neon_pop_desc,
    brightness = 5,
    saturation = 40,
    contrast = 20,
    gamma = 0,
    hue = 0,
    sharpness = 0,
  ),
  DEEP_BLACK(
    displayNameRes = R.string.i18n_fp_deep_black,
    descriptionRes = R.string.i18n_fp_deep_black_desc,
    brightness = -15,
    saturation = 5,
    contrast = 25,
    gamma = -15,
    hue = 0,
    sharpness = 0,
  ),
}

enum class VideoFilters(
  @StringRes val titleRes: Int,
  val preference: (DecoderPreferences) -> Preference<Int>,
  val mpvProperty: String,
  val min: Int = -100,
  val max: Int = 100,
) {
  BRIGHTNESS(
    R.string.player_sheets_filters_brightness,
    { it.brightnessFilter },
    "brightness",
  ),
  SATURATION(
    R.string.player_sheets_filters_Saturation,
    { it.saturationFilter },
    "saturation",
  ),
  CONTRAST(
    R.string.player_sheets_filters_contrast,
    { it.contrastFilter },
    "contrast",
  ),
  GAMMA(
    R.string.player_sheets_filters_gamma,
    { it.gammaFilter },
    "gamma",
  ),
  HUE(
    R.string.player_sheets_filters_hue,
    { it.hueFilter },
    "hue",
  ),
  SHARPNESS(
    titleRes = R.string.player_sheets_filters_sharpness,
    preference = { it.sharpnessFilter },
    mpvProperty = "sharpen",
    min = -5,
    max = 5,
  ),
}

enum class DebandSettings(
  @StringRes val titleRes: Int,
  val preference: (DecoderPreferences) -> Preference<Int>,
  val mpvProperty: String,
  val start: Int,
  val end: Int,
) {
  Iterations(
    R.string.player_sheets_deband_iterations,
    { it.debandIterations },
    "deband-iterations",
    0,
    16,
  ),
  Threshold(
    R.string.player_sheets_deband_threshold,
    { it.debandThreshold },
    "deband-threshold",
    0,
    200,
  ),
  Range(
    R.string.player_sheets_deband_range,
    { it.debandRange },
    "deband-range",
    1,
    64,
  ),
  Grain(
    R.string.player_sheets_deband_grain,
    { it.debandGrain },
    "deband-grain",
    0,
    200,
  ),
}
