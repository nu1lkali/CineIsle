
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
