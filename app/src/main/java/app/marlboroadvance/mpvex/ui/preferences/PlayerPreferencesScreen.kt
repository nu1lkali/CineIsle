package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.player.PlayerOrientation
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object PlayerPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val preferences = koinInject<PlayerPreferences>()
    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(id = R.string.pref_player),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            ) 
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack, 
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          // General Section
          item {
            PreferenceSectionHeader(title = "常规")
          }
          
          item {
            PreferenceCard {
              val orientation by preferences.orientation.collectAsState()
              ListPreference(
                value = orientation,
                onValueChange = preferences.orientation::set,
                values = PlayerOrientation.entries,
                valueToText = { value -> AnnotatedString(stringResource(value.titleRes)) },
                title = { Text(text = stringResource(id = R.string.pref_player_orientation)) },
                summary = { 
                  Text(
                    text = stringResource(id = orientation.titleRes),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                },
              )
              
              PreferenceDivider()
              
              val savePositionOnQuit by preferences.savePositionOnQuit.collectAsState()
              SwitchPreference(
                value = savePositionOnQuit,
                onValueChange = preferences.savePositionOnQuit::set,
                title = { Text(stringResource(R.string.pref_player_save_position_on_quit)) },
              )
              
              PreferenceDivider()
              
              val closeAfterEndOfVideo by preferences.closeAfterReachingEndOfVideo.collectAsState()
              SwitchPreference(
                value = closeAfterEndOfVideo,
                onValueChange = preferences.closeAfterReachingEndOfVideo::set,
                title = { Text(stringResource(id = R.string.pref_player_close_after_eof)) },
              )
              
              PreferenceDivider()
              
              val autoplayNextVideo by preferences.autoplayNextVideo.collectAsState()
              SwitchPreference(
                value = autoplayNextVideo,
                onValueChange = preferences.autoplayNextVideo::set,
                title = { Text(text = stringResource(R.string.i18n_autoplay_next_video)) },
                summary = {
                  Text(
                    text = if (autoplayNextVideo)
                      "Automatically play next video when current ends"
                    else
                      "Stay on current video when it ends",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
              
              PreferenceDivider()

              // 自动连播倒计时：0 = 播完直接切下一集（原来的行为）
              val autoplayCountdown by preferences.autoplayNextCountdownSeconds.collectAsState()
              ListPreference(
                value = autoplayCountdown,
                onValueChange = { preferences.autoplayNextCountdownSeconds.set(it) },
                title = { Text("连播下一集倒计时") },
                values = listOf(0, 3, 5, 10),
                valueToText = { value ->
                  AnnotatedString(
                    when (value) {
                      0 -> "不显示，播完直接切"
                      else -> "$value 秒"
                    },
                  )
                },
                type = ListPreferenceType.DROPDOWN_MENU,
                summary = {
                  Text(
                    text = if (autoplayCountdown == 0) {
                      "不显示，播完直接切"
                    } else {
                      "播完弹卡片，倒数 $autoplayCountdown 秒后切下一集；点卡片可立即播放"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rememberSpeed by preferences.rememberSpeedPerSeries.collectAsState()
              SwitchPreference(
                value = rememberSpeed,
                onValueChange = preferences.rememberSpeedPerSeries::set,
                title = { Text("记住每部剧的播放速度") },
                summary = {
                  Text(
                    text = if (rememberSpeed) {
                      "同一部剧切下一集时沿用上次调过的倍速（电影按单部记）"
                    } else {
                      "每部片子都用默认倍速"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rememberAudioTrack by preferences.rememberAudioTrackPerSeries.collectAsState()
              SwitchPreference(
                value = rememberAudioTrack,
                onValueChange = preferences.rememberAudioTrackPerSeries::set,
                title = { Text("记住每部剧选择的音轨") },
                summary = {
                  Text(
                    text = if (rememberAudioTrack) {
                      "按「语言+编码+声道」匹配，下一集自动选中同一条音轨"
                    } else {
                      "每集都按默认语言规则选音轨"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 播放内核 / GSY 全部配置 ──
              // 这一整块（默认内核、长按反选、GSY 的解码内核、渲染方式、显示比例、手势…）
              // 已经整块搬到「设置 → GSY 播放器」（GsyPreferencesScreen）。
              // 原因：GSY 现在是**独立的一套播放页**（GsyPlayerActivity），
              // 它的配置和 mpv 播放页的配置必须各管各的，混在一起改一边会误伤另一边。
              Preference(
                title = { Text("GSY 播放器") },
                summary = {
                  Text(
                    text = "内核 / 渲染方式 / 显示比例 / 手势 / 全屏与旋转，以及「长按用哪个内核播放」——" +
                      "全部在 GSY 播放器自己的设置页里，与 mpv 的配置互不影响",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { backstack.add(GsyPreferencesScreen) },
              )

              PreferenceDivider()

              val preloadNextVideo by preferences.preloadNextVideo.collectAsState()
              SwitchPreference(
                value = preloadNextVideo,
                onValueChange = preferences.preloadNextVideo::set,
                title = { Text(text = stringResource(R.string.i18n_preload_next_video)) },
                summary = {
                  Text(
                    text = stringResource(R.string.i18n_preload_next_video_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val playlistMode by preferences.playlistMode.collectAsState()
              SwitchPreference(
                value = playlistMode,
                onValueChange = preferences.playlistMode::set,
                title = { Text(text = stringResource(R.string.i18n_enable_nav)) },
                summary = {
                  Text(
                    text = if (playlistMode)
                      "Show next/previous buttons for all videos in folder"
                    else
                      "Play videos individually (select multiple for playlist)",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
              
              PreferenceDivider()

              val rememberBrightness by preferences.rememberBrightness.collectAsState()
              SwitchPreference(
                value = rememberBrightness,
                onValueChange = preferences.rememberBrightness::set,
                title = { Text(text = stringResource(R.string.pref_player_remember_brightness)) },
              )

              PreferenceDivider()

              val autoPiPOnNavigation by preferences.autoPiPOnNavigation.collectAsState()
              SwitchPreference(
                value = autoPiPOnNavigation,
                onValueChange = preferences.autoPiPOnNavigation::set,
                title = { Text("自动画中画") },
                summary = {
                  Text(
                    text = stringResource(R.string.i18n_auto_pip_hint),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val keepScreenOnWhenPaused by preferences.keepScreenOnWhenPaused.collectAsState()
              SwitchPreference(
                value = keepScreenOnWhenPaused,
                onValueChange = preferences.keepScreenOnWhenPaused::set,
                title = { Text("暂停时保持屏幕常亮") },
                summary = {
                  Text(
                    text = if (keepScreenOnWhenPaused)
                      "Screen stays awake while video is paused"
                    else
                      "Screen can turn off while video is paused",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }


          // Seeking Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_player_seeking_title))
          }
          
          item {
            PreferenceCard {
              val showDoubleTapOvals by preferences.showDoubleTapOvals.collectAsState()
              SwitchPreference(
                value = showDoubleTapOvals,
                onValueChange = preferences.showDoubleTapOvals::set,
                title = { Text(stringResource(R.string.show_splash_ovals_on_double_tap_to_seek)) },
              )
              
              PreferenceDivider()
              
              val showSeekTimeWhileSeeking by preferences.showSeekTimeWhileSeeking.collectAsState()
              SwitchPreference(
                value = showSeekTimeWhileSeeking,
                onValueChange = preferences.showSeekTimeWhileSeeking::set,
                title = { Text(stringResource(R.string.show_time_on_double_tap_to_seek)) },
              )
              
              PreferenceDivider()
              
              val usePreciseSeeking by preferences.usePreciseSeeking.collectAsState()
              SwitchPreference(
                value = usePreciseSeeking,
                onValueChange = preferences.usePreciseSeeking::set,
                title = { Text(stringResource(R.string.pref_player_use_precise_seeking)) },
              )
              
              PreferenceDivider()
              
              val customSkipDuration by preferences.customSkipDuration.collectAsState()
              SliderPreference(
                value = customSkipDuration.toFloat(),
                onValueChange = { preferences.customSkipDuration.set(it.roundToInt()) },
                title = { Text(stringResource(R.string.pref_player_custom_skip_duration_title)) },
                valueRange = 5f..180f,
                summary = {
                   val summaryText = stringResource(R.string.pref_player_custom_skip_duration_summary)
                   Text(
                     "$summaryText ($customSkipDuration s)",
                     color = MaterialTheme.colorScheme.outline,
                   )
                },
                onSliderValueChange = { preferences.customSkipDuration.set(it.roundToInt()) },
                sliderValue = customSkipDuration.toFloat(),
              )
            }
          }
          // Gestures Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_player_gestures))
          }
          
          item {
            PreferenceCard {
              val brightnessGesture by preferences.brightnessGesture.collectAsState()
              SwitchPreference(
                value = brightnessGesture,
                onValueChange = preferences.brightnessGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_brightness)) },
              )
              
              PreferenceDivider()
              
              val volumeGesture by preferences.volumeGesture.collectAsState()
              SwitchPreference(
                value = volumeGesture,
                onValueChange = preferences.volumeGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_volume)) },
              )
              
              PreferenceDivider()
              
              val pinchToZoomGesture by preferences.pinchToZoomGesture.collectAsState()
              SwitchPreference(
                value = pinchToZoomGesture,
                onValueChange = preferences.pinchToZoomGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_pinch_to_zoom)) },
              )
              
              PreferenceDivider()
              
              val horizontalSwipeToSeek by preferences.horizontalSwipeToSeek.collectAsState()
              SwitchPreference(
                value = horizontalSwipeToSeek,
                onValueChange = preferences.horizontalSwipeToSeek::set,
                title = { Text(stringResource(R.string.pref_player_gestures_horizontal_swipe_to_seek)) },
              )
              
              PreferenceDivider()
              
              val horizontalSwipeSensitivity by preferences.horizontalSwipeSensitivity.collectAsState()
              SliderPreference(
                value = horizontalSwipeSensitivity,
                onValueChange = { preferences.horizontalSwipeSensitivity.set(it.toFixed(3)) },
                title = { Text(stringResource(R.string.pref_player_gestures_horizontal_swipe_sensitivity)) },
                valueRange = 0.020f..0.1f,
                summary = {
                  val sensitivityPercent = (horizontalSwipeSensitivity * 1000).toInt()
                  Text(
                    "Current: ${sensitivityPercent}/100 (${if (sensitivityPercent < 30) "Low" else if (sensitivityPercent < 55) "Medium" else "High"})",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { preferences.horizontalSwipeSensitivity.set(it.toFixed(3)) },
                sliderValue = horizontalSwipeSensitivity,
              )
              
              PreferenceDivider()
              
              val holdForMultipleSpeed by preferences.holdForMultipleSpeed.collectAsState()
              SliderPreference(
                value = holdForMultipleSpeed,
                onValueChange = { preferences.holdForMultipleSpeed.set(it.toFixed(2)) },
                title = { Text(stringResource(R.string.pref_player_gestures_hold_for_multiple_speed)) },
                valueRange = 0f..6f,
                summary = {
                  Text(
                    if (holdForMultipleSpeed == 0F) {
                      stringResource(R.string.generic_disabled)
                    } else {
                      "%.2fx".format(holdForMultipleSpeed)
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { preferences.holdForMultipleSpeed.set(it.toFixed(2)) },
                sliderValue = holdForMultipleSpeed,
              )
              
              PreferenceDivider()
              
              val showDynamicSpeedOverlay by preferences.showDynamicSpeedOverlay.collectAsState()
              SwitchPreference(
                value = showDynamicSpeedOverlay,
                onValueChange = preferences.showDynamicSpeedOverlay::set,
                title = { Text("动态倍速浮层") },
                summary = { 
                  Text(stringResource(R.string.i18n_speed_overlay_hint),
                    color = MaterialTheme.colorScheme.outline,
                  ) 
                }
              )
            }
          }
          // Controls Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_player_controls))
          }

          item {
            PreferenceCard {
              val allowGesturesInPanels by preferences.allowGesturesInPanels.collectAsState()
              SwitchPreference(
                value = allowGesturesInPanels,
                onValueChange = preferences.allowGesturesInPanels::set,
                title = {
                  Text(
                    text = stringResource(id = R.string.pref_player_controls_allow_gestures_in_panels),
                  )
                },
              )
              
              PreferenceDivider()
              
              val swapVolumeAndBrightness by preferences.swapVolumeAndBrightness.collectAsState()
              SwitchPreference(
                value = swapVolumeAndBrightness,
                onValueChange = preferences.swapVolumeAndBrightness::set,
                title = { Text(stringResource(R.string.swap_the_volume_and_brightness_slider)) },
              )
              
              PreferenceDivider()
              
              val showLoadingCircle by preferences.showLoadingCircle.collectAsState()
              SwitchPreference(
                value = showLoadingCircle,
                onValueChange = preferences.showLoadingCircle::set,
                title = { Text(stringResource(R.string.pref_player_controls_show_loading_circle)) },
              )
            }
          }
          // Display Section
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_player_display))
          }
          
          item {
            PreferenceCard {
              val showSystemStatusBar by preferences.showSystemStatusBar.collectAsState()
              SwitchPreference(
                value = showSystemStatusBar,
                onValueChange = preferences.showSystemStatusBar::set,
                title = { Text(stringResource(R.string.pref_player_display_show_status_bar)) },
              )

              PreferenceDivider()

              val showSystemNavigationBar by preferences.showSystemNavigationBar.collectAsState()
              SwitchPreference(
                value = showSystemNavigationBar,
                onValueChange = preferences.showSystemNavigationBar::set,
                title = { Text("显示控件时同时显示导航栏") },
              )
              
              PreferenceDivider()
              
              val reduceMotion by preferences.reduceMotion.collectAsState()
              SwitchPreference(
                value = reduceMotion,
                onValueChange = preferences.reduceMotion::set,
                title = { Text(stringResource(R.string.pref_player_display_reduce_player_animation)) },
              )
            }
          }
        }
      }
    }
  }
}
