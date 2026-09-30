
package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.PlayerOrientation
import app.marlboroadvance.mpvex.ui.player.RepeatMode
import app.marlboroadvance.mpvex.ui.player.VideoAspect

class PlayerPreferences(
  preferenceStore: PreferenceStore,
) {
  val orientation = preferenceStore.getEnum("player_orientation", PlayerOrientation.Video)
  val invertDuration = preferenceStore.getBoolean("invert_duration")
  val holdForMultipleSpeed = preferenceStore.getFloat("hold_for_multiple_speed", 2f)
  val showDynamicSpeedOverlay = preferenceStore.getBoolean("show_dynamic_speed_overlay", true)
  val showDoubleTapOvals = preferenceStore.getBoolean("show_double_tap_ovals", true)
  val showSeekTimeWhileSeeking = preferenceStore.getBoolean("show_seek_time_while_seeking", true)
  val usePreciseSeeking = preferenceStore.getBoolean("use_precise_seeking", false)

  val brightnessGesture = preferenceStore.getBoolean("gestures_brightness", true)
  val volumeGesture = preferenceStore.getBoolean("volume_brightness", true)
  val pinchToZoomGesture = preferenceStore.getBoolean("pinch_to_zoom_gesture", true)
  val horizontalSwipeToSeek = preferenceStore.getBoolean("horizontal_swipe_to_seek", true)
  val horizontalSwipeSensitivity = preferenceStore.getFloat("horizontal_swipe_sensitivity", 0.05f)

  val customAspectRatios = preferenceStore.getStringSet("custom_aspect_ratios", emptySet())

  val defaultSpeed = preferenceStore.getFloat("default_speed", 1f)
  val speedPresets =
    preferenceStore.getStringSet(
      "default_speed_presets",
      setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
  val displayVolumeAsPercentage = preferenceStore.getBoolean("display_volume_as_percentage", true)
  val swapVolumeAndBrightness = preferenceStore.getBoolean("display_volume_on_right")
  val showLoadingCircle = preferenceStore.getBoolean("show_loading_circle", true)
  val savePositionOnQuit = preferenceStore.getBoolean("save_position", true)

  /**
   * 播完是否自动退出播放器。
   *
   * 默认 **false**（播完停在最后一帧，不退出），这也是「更多」面板里那个开关的默认状态；
   * 想要「看完就回列表」的用户可以在开关里打开它。
   */
  val closeAfterReachingEndOfVideo = preferenceStore.getBoolean("close_after_eof", false)

  val rememberBrightness = preferenceStore.getBoolean("remember_brightness")
  val defaultBrightness = preferenceStore.getFloat("default_brightness", -1f)

  val allowGesturesInPanels = preferenceStore.getBoolean("allow_gestures_in_panels")
  val showSystemStatusBar = preferenceStore.getBoolean("show_system_status_bar")
  val showSystemNavigationBar = preferenceStore.getBoolean("show_system_navigation_bar")
  val reduceMotion = preferenceStore.getBoolean("reduce_motion", true)
  val playerTimeToDisappear = preferenceStore.getInt("player_time_to_disappear", 4000)

  val defaultVideoZoom = preferenceStore.getFloat("default_video_zoom", 0f)
  val panAndZoomEnabled = preferenceStore.getBoolean("pan_and_zoom_enabled", false)

  val includeSubtitlesInSnapshot = preferenceStore.getBoolean("include_subtitles_in_snapshot", false)

  val playlistMode = preferenceStore.getBoolean("playlist_mode", true)
  val playlistViewMode = preferenceStore.getBoolean("playlist_view_mode_list", true) // true = list, false = grid

  val useWavySeekbar = preferenceStore.getBoolean("use_wavy_seekbar", true)

  val customSkipDuration = preferenceStore.getInt("custom_skip_duration", 90)

  val repeatMode = preferenceStore.getEnum("repeat_mode", RepeatMode.OFF)
  val shuffleEnabled = preferenceStore.getBoolean("shuffle_enabled", false)

  // New: autoplay next video when current file ends
  val autoplayNextVideo = preferenceStore.getBoolean("autoplay_next_video", true)

  /**
   * 播完自动切下一集前，先弹一个倒计时卡片让用户来得及取消。
   *
   * 单位秒：**0 表示不弹、直接切**（原来的行为）；3 / 5 / 10 为可选档位。
   * 只在 [autoplayNextVideo] 打开时才有意义 —— 关掉连播后根本不会走到倒计时。
   */
  val autoplayNextCountdownSeconds = preferenceStore.getInt("autoplay_next_countdown_seconds", 5)

  /**
   * 默认播放内核："MPV" 或 "GSY"。
   *
   * 默认 mpv —— 它是原始内核，功能最全；GSYVideoPlayer 作为兼容兜底存在，
   * 需要用户在设置里主动切换，或用「长按播放切换备用内核」临时用一次。
   */
  val playbackEngine = preferenceStore.getString("playback_engine", "MPV")

  /**
   * 长按反选内核：打开后，长按一个视频会用「与默认相反」的内核播放。
   *
   * 典型场景是「这个片 mpv 播不动，临时用 Exo 试试」，
   * 不需要先去设置里切默认内核再回来点。
   */
  val longPressReverseEngine = preferenceStore.getBoolean("long_press_reverse_engine", true)

  /*
   * 注：GSY 的解码内核（IJK / System / ExoPlayer）不在这里 ——
   * 它是 GSY 播放页自己的配置，见 [GsyPreferences.kernel]，
   * 配置入口在「设置 → GSY 播放器」。mpv 这边只有 [playbackEngine] 决定默认走哪套播放页。
   */

  /**
   * 记住每部剧的播放速度：同一部剧切下一集时沿用上一集调过的倍速。
   *
   * 默认**关闭** —— 倍速是很容易忘记自己改过的状态，自动继承时
   * 「下一集怎么变快了」比「每集重新调一次」更容易让人困惑，所以交给用户自己开。
   * 只在同一部剧（同一个 SeriesId）内继承，跨剧不继承。
   */
  val rememberSpeedPerSeries = preferenceStore.getBoolean("remember_speed_per_series", false)

  /**
   * 记住每部剧选择的音轨。
   *
   * 匹配用「语言 + 编码 + 声道数」指纹而不是 mpv 的 track id ——
   * 不同文件里同一条音轨的 id 可能因为多一条评论轨就整体错位。
   * 默认关闭，理由同 [rememberSpeedPerSeries]。
   */
  val rememberAudioTrackPerSeries = preferenceStore.getBoolean("remember_audio_track_per_series", false)

  /**
   * 记住的播放速度：SeriesKey → 倍速（JSON）。
   * 只在 [rememberSpeedPerSeries] 打开时写入；关掉后旧数据保留，重新打开仍然生效。
   */
  val rememberedSpeeds = preferenceStore.getString("remembered_speeds", "{}")

  /**
   * 记住的音轨指纹：SeriesKey → 指纹串（JSON）。
   * 只在 [rememberAudioTrackPerSeries] 打开时写入。
   */
  val rememberedAudioTracks = preferenceStore.getString("remembered_audio_tracks", "{}")

  /**
   * 视频预加载。
   *
   * 打开后：当前视频播放满 [PRELOAD_TRIGGER_SECONDS] 秒时，后台取下一个视频**开头**一段数据，
   * 等用户真的切过去时首帧来得更快，观感更接近「无缝」。
   *
   * 默认关闭 —— 预加载会额外占一点带宽，弱网或流量环境应由用户自己决定。
   */
  val preloadNextVideo = preferenceStore.getBoolean("preload_next_video", false)

  val autoPiPOnNavigation = preferenceStore.getBoolean("auto_pip_on_navigation", false)

  val keepScreenOnWhenPaused = preferenceStore.getBoolean("keep_screen_on_when_paused", false)

  // Persist aspect ratio setting (default to Fit)
  val defaultVideoAspect = preferenceStore.getEnum("default_video_aspect", VideoAspect.Fit)
  val defaultCustomAspectRatio = preferenceStore.getObject(
    key = "default_custom_aspect_ratio",
    defaultValue = -1.0,
    serializer = { it.toString() },
    deserializer = { it.toDoubleOrNull() ?: -1.0 }
  )

  companion object {
    /** 播放满多少秒后开始预加载下一个视频（用户需求：2 秒）。 */
    const val PRELOAD_TRIGGER_SECONDS = 2

    /** 预取的数据量：够覆盖容器头 + 开头关键帧即可，取多了反而挤占当前视频的带宽。 */
    const val PRELOAD_BYTES = 2L * 1024 * 1024
  }
}
