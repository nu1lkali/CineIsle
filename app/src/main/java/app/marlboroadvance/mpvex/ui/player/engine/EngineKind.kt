package app.marlboroadvance.mpvex.ui.player.engine

import app.marlboroadvance.mpvex.preferences.PlayerButton

/**
 * 播放内核。
 *
 * 项目原本只有 mpv 一个内核（[PlayerLib] 之前就是直接调 `MPVLib`）。
 * 双内核的目标是：**界面一套、内核两套** —— 用户选哪个内核就用哪个，
 * UI 与操作方式完全不变，只有「这个内核做不到的功能」会被隐藏。
 */
enum class EngineKind(
  val label: String,
) {
  /** mpv：功能最全，默认内核 */
  MPV("mpv"),

  /**
   * GSYVideoPlayer：兼容性与硬解更稳的备用内核。
   *
   * GSY 自己是一个「播放器框架」，底下还能挂不同的解码内核
   * （`GsyKernelKind`：IJK / System / ExoPlayer）——
   * 那是 GSY 播放页自己的配置，入口在「设置 → GSY 播放器」，与 mpv 这边无关。
   */
  GSY("GSYVideoPlayer"),
  ;

  companion object {
    fun from(value: String?): EngineKind =
      when (value?.uppercase()) {
        // "EXO" / "MEDIA3" 是改造前存在偏好里的旧值，继续识别，避免升级后设置失效
        "GSY", "EXO", "EXOPLAYER", "MEDIA3", "GSY_IJK", "GSY_EXO" -> GSY
        else -> MPV
      }
  }
}

/** 「与当前内核相反」的那个内核 —— 长按切换备用内核用的就是它 */
fun EngineKind.reversed(): EngineKind = if (this == EngineKind.MPV) EngineKind.GSY else EngineKind.MPV

/*
 * 注：GSY 框架底下的解码内核（IJK / System / ExoPlayer）已迁到
 * `app.marlboroadvance.mpvex.preferences.GsyKernelKind` ——
 * 那是 GSY 自己的配置，跟 mpv 播放页无关，配置入口也在「设置 → GSY 播放器」。
 */

/**
 * 内核能力标记。
 *
 * mpv 几乎全支持；GSY 覆盖主干 + 一批「手动补齐」的 mpv 功能
 * （A-B 循环 / 逐帧 / 截图 / 外挂字幕 / 字幕延迟 / 镜像翻转 / 平移 都是自己实现的）。
 * mpv 特有的滤镜链 / 字幕排版 / 解码器选择 / 章节仍然不支持，
 * 界面按 [EngineKind.supports] 把对应入口**隐藏**而不是置灰 ——
 * 置灰了还能点，点了没反应更难受。
 */
enum class EngineFeature {
  /**
   * mpv 的硬解 / 软解切换（hwdec）。
   *
   * GSY 那边没有一一对应的概念：IJK 的 codec 开关是 `GSYVideoType.enableMediaCodec()`
   * 这种全局静态开关，和 mpv 的 `hwdec=auto/mediacodec/no` 三态对不上，
   * 放同一个「解码器」面板只会让选项名和真实行为不一致，所以 GSY 下保持隐藏；
   * 想换解码路径就直接去「设置 → GSY 播放器」切 IJK / System / ExoPlayer。
   */
  DECODER_SWITCH,

  /** 视频滤镜面板（vf / GL effect） */
  VIDEO_FILTERS,

  /** 去色带（deband） */
  DEBAND,

  /** GLSL 着色器链 */
  GLSL_SHADERS,

  /** 字幕字体 / 颜色 / 描边 / 位置等排版设置 */
  SUBTITLE_STYLE,

  /** 第二字幕（secondary-sub-*） */
  SECONDARY_SUBTITLE,

  /** 字幕延迟 */
  SUBTITLE_DELAY,

  /** 音轨延迟 */
  AUDIO_DELAY,

  /** 逐帧前进 / 后退 */
  FRAME_NAVIGATION,

  /** mpv 统计信息页 */
  STATS,

  /** 截图 */
  SCREENSHOT,

  /** 章节跳转 */
  CHAPTERS,

  /** 外挂字幕（sub-add） */
  EXTERNAL_SUBTITLES,

  /** A-B 循环 */
  AB_LOOP,

  /** 画面缩放 / 平移 */
  VIDEO_ZOOM,

  /** 画面比例 */
  VIDEO_ASPECT,

  /** 镜像 / 垂直翻转 */
  MIRROR_FLIP,

  /** 后台播放（音频继续） */
  BACKGROUND_PLAYBACK,

  /** 画中画 */
  PICTURE_IN_PICTURE,

  /** 播放页里现场切换 GSY 的解码内核（IJK ↔ ExoPlayer） */
  GSY_KERNEL_SWITCH,
}

/**
 * GSY 内核支持的能力。
 *
 * 与改造前的 Media3 裸 ExoPlayer 相比，多出来这几项都是**手动补齐**的：
 * - [EngineFeature.SUBTITLE_DELAY]：GSY 自带 `setSubtitleOffsetMs`，对外挂字幕生效；
 * - [EngineFeature.EXTERNAL_SUBTITLES]：GSY 自带 SRT / WebVTT 解析与渲染（`GSYSubtitleView`）；
 * - [EngineFeature.AB_LOOP]：后端里自己按 time-pos 兜回去；
 * - [EngineFeature.SCREENSHOT]：GSY 的 `saveFrame()` 抓帧存盘；
 * - [EngineFeature.FRAME_NAVIGATION]：按 40ms 一帧近似 seek；
 * - [EngineFeature.MIRROR_FLIP]：渲染视图 scaleX / scaleY 取反。
 */
private val GSY_SUPPORTED =
  setOf(
    EngineFeature.VIDEO_ZOOM,
    EngineFeature.VIDEO_ASPECT,
    EngineFeature.PICTURE_IN_PICTURE,
    EngineFeature.BACKGROUND_PLAYBACK,
    EngineFeature.AB_LOOP,
    EngineFeature.SCREENSHOT,
    EngineFeature.EXTERNAL_SUBTITLES,
    EngineFeature.SUBTITLE_DELAY,
    EngineFeature.FRAME_NAVIGATION,
    EngineFeature.MIRROR_FLIP,
    EngineFeature.GSY_KERNEL_SWITCH,
  )

fun EngineKind.supports(feature: EngineFeature): Boolean =
  when (this) {
    EngineKind.MPV -> true
    EngineKind.GSY -> feature in GSY_SUPPORTED
  }

/**
 * 控件按钮 → 它依赖的内核能力。
 *
 * 返回 null 表示「任何内核都能用」（播放 / 暂停 / 倍速 / 音轨 / 字幕轨这类主干），
 * 非 null 的按钮在当前内核不支持时会被**从控件栏移除**。
 */
fun PlayerButton.requiredFeature(): EngineFeature? =
  when (this) {
    PlayerButton.DECODER -> EngineFeature.DECODER_SWITCH
    PlayerButton.VIDEO_ZOOM -> EngineFeature.VIDEO_ZOOM
    PlayerButton.ASPECT_RATIO -> EngineFeature.VIDEO_ASPECT
    PlayerButton.FRAME_NAVIGATION -> EngineFeature.FRAME_NAVIGATION
    PlayerButton.BACKGROUND_PLAYBACK -> EngineFeature.BACKGROUND_PLAYBACK
    PlayerButton.AB_LOOP -> EngineFeature.AB_LOOP
    PlayerButton.MIRROR, PlayerButton.VERTICAL_FLIP -> EngineFeature.MIRROR_FLIP
    PlayerButton.CURRENT_CHAPTER, PlayerButton.BOOKMARKS_CHAPTERS -> EngineFeature.CHAPTERS
    PlayerButton.PICTURE_IN_PICTURE -> EngineFeature.PICTURE_IN_PICTURE
    else -> null
  }

/** 当前内核下这个按钮还能不能显示 */
fun EngineKind.supportsButton(button: PlayerButton): Boolean {
  val feature = button.requiredFeature() ?: return true
  return supports(feature)
}
