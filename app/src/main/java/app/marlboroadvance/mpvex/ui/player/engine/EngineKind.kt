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
 * mpv 几乎全支持；GSY 覆盖主干 + 一批能对齐的能力（画面比例、截图、外挂字幕、
 * 字幕延迟、字幕样式、章节、画中画、后台播放）。
 * mpv 特有的滤镜链 / GLSL 着色器 / 解码器切换 / 第二字幕 / 音轨延迟仍然不支持。
 * 界面按 [EngineKind.supports] 把对应入口**隐藏**而不是置灰 ——
 * 置灰了还能点，点了没反应更难受。
 *
 * 加新枚举项前先看 [GSY_SUPPORTED] 的约定：列进去就得真的有实现。
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

  /**
   * 去色带（deband）。
   *
   * 没有独立读点 —— 面板入口是按 [EngineFeature.VIDEO_FILTERS] 显隐的，
   * 去色带只是滤镜面板里的一个开关。保留这一项是为了**说清能力边界**：
   * mpv 有 `vf=debund`，GSY 没有。
   */
  DEBAND,

  /** GLSL 着色器链 */
  GLSL_SHADERS,

  /**
   * 字幕字体 / 颜色 / 描边 / 位置等排版设置。
   *
   * 两侧能力**不对等**，所以隐藏不等于「完全没有」：
   *  - mpv：完整（字体、字号、粗斜体、对齐、描边样式与粗细、阴影、三色、位置、缩放、ASS 覆盖）；
   *  - GSY：`GSYSubtitleStyle` 只有文字色、字号、背景色、阴影（色/半径/XY 偏移）、
   *    上下左右边距、gravity（见 `GsySubtitleStyleMapper`）。字体名 / 描边样式 / ASS 覆盖都做不了。
   *
   * 所以 GSY 的样式面板是**同一个偏好（`SubtitlesPreferences`）的子集视图**：
   * 用户在设置里调过的字号、颜色会一并生效，只是 GSY 侧能调的项更少。
   */
  SUBTITLE_STYLE,

  /** 第二字幕（secondary-sub-*）—— GSY 的字幕子系统只渲染一路，恒不支持 */
  SECONDARY_SUBTITLE,

  /** 字幕延迟 */
  SUBTITLE_DELAY,

  /** 音轨延迟 —— GSY 无对应 API，恒不支持 */
  AUDIO_DELAY,

  /** 逐帧前进 / 后退 */
  FRAME_NAVIGATION,

  /** 截图 */
  SCREENSHOT,

  /**
   * 章节跳转。
   *
   * 两侧**数据来源完全不同**，这是补齐时最容易踩的坑：
   *  - mpv：容器内嵌章节表（`chapter-list` property，由 demuxer 解析），本地文件也有；
   *  - GSY：GSY 没有任何章节 API，只能走 Emby `GET /Items/{id}/Chapters`
   *    （见 `GsyChapterRepository`），所以**纯本地文件在 GSY 下没有章节**。
   *
   * 这是能力本身的边界，不是没实现 —— 同一个文件在两个内核下章节数量可能不同。
   */
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
 * ⚠️ **这个集合是「约定」，不是「愿望」** —— 列进来就等于告诉 UI「按钮可以显示」，
 * 而 [PlayerControls] 只会按它过滤按钮，**不会**在点击后告诉你「这个内核其实没实现」。
 * 所以加一项之前必须先确认 GSY 侧真的有对应实现，否则就是「按钮能点、点了没反应」，
 * 比隐藏更糟。
 *
 * 复核结论（清单 #19）：上一版这里有 **5 项虚标** ——
 * [EngineFeature.FRAME_NAVIGATION]、[EngineFeature.MIRROR_FLIP]、[EngineFeature.VIDEO_ZOOM]、
 * [EngineFeature.VIDEO_ASPECT]、[EngineFeature.AB_LOOP] 声称支持，但
 * `GsyPlayerActivity` / `CineIsleGsyPlayer` 里搜不到任何对应实现。
 * 其中 [EngineFeature.VIDEO_ASPECT] 后来确认是**真做了**的（`CineIsleGsyPlayer.setShowTypeRatio`
 * 走 `GSYVideoType.setShowType()`），已放回；其余四项要么补实现、要么老实移出。
 *
 * 现存各项的来源：
 * - [EngineFeature.VIDEO_ASPECT]：`GSYVideoType.setShowType()` + `changeTextureViewShowType()`；
 * - [EngineFeature.PICTURE_IN_PICTURE] / [EngineFeature.BACKGROUND_PLAYBACK]：官方能力，Activity 里接线；
 * - [EngineFeature.SCREENSHOT]：`saveFrame(File, listener)`；
 * - [EngineFeature.EXTERNAL_SUBTITLES] / [EngineFeature.SUBTITLE_DELAY] / [EngineFeature.SUBTITLE_STYLE]：
 *   GSY 自带 SRT / WebVTT 字幕子系统（`GSYSubtitleController` + `GSYSubtitleStyle`）；
 * - [EngineFeature.CHAPTERS]：数据来自 Emby API（GSY 本身无章节能力），见 `GsyChapterRepository`；
 * - [EngineFeature.GSY_KERNEL_SWITCH]：GSY 播放页自己的内核切换。
 */
private val GSY_SUPPORTED =
  setOf(
    EngineFeature.VIDEO_ASPECT,
    EngineFeature.PICTURE_IN_PICTURE,
    EngineFeature.BACKGROUND_PLAYBACK,
    EngineFeature.SCREENSHOT,
    EngineFeature.EXTERNAL_SUBTITLES,
    EngineFeature.SUBTITLE_DELAY,
    EngineFeature.SUBTITLE_STYLE,
    EngineFeature.CHAPTERS,
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
