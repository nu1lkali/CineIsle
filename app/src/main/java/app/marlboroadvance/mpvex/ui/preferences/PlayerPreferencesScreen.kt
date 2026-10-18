package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SEEK_STEP_RANGE
import app.marlboroadvance.mpvex.preferences.coerceSeekStepSeconds
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.player.MpvDiskCache
import app.marlboroadvance.mpvex.ui.player.PlayerOrientation
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.preferences.components.TickedSlider
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                icon = {
                  Icon(
                    Icons.Outlined.ScreenRotation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = orientation,
                onValueChange = preferences.orientation::set,
                values = PlayerOrientation.entries,
                valueToText = { value -> AnnotatedString(stringResource(value.titleRes)) },
                title = { Text(text = stringResource(id = R.string.pref_player_orientation), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = savePositionOnQuit,
                onValueChange = preferences.savePositionOnQuit::set,
                title = { Text(stringResource(R.string.pref_player_save_position_on_quit), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val closeAfterEndOfVideo by preferences.closeAfterReachingEndOfVideo.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = closeAfterEndOfVideo,
                onValueChange = preferences.closeAfterReachingEndOfVideo::set,
                title = { Text(stringResource(id = R.string.pref_player_close_after_eof), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val autoplayNextVideo by preferences.autoplayNextVideo.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoplayNextVideo,
                onValueChange = preferences.autoplayNextVideo::set,
                title = { Text(text = stringResource(R.string.i18n_autoplay_next_video), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoplayCountdown,
                onValueChange = { preferences.autoplayNextCountdownSeconds.set(it) },
                title = { Text("连播下一集倒计时", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = rememberSpeed,
                onValueChange = preferences.rememberSpeedPerSeries::set,
                title = { Text("记住每部剧的播放速度", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = rememberAudioTrack,
                onValueChange = preferences.rememberAudioTrackPerSeries::set,
                title = { Text("记住每部剧选择的音轨", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

              val rememberSubtitleTrack by preferences.rememberSubtitleTrackPerSeries.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = rememberSubtitleTrack,
                onValueChange = preferences.rememberSubtitleTrackPerSeries::set,
                title = { Text(text = stringResource(R.string.pref_remember_subtitle_track_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(
                      if (rememberSubtitleTrack) R.string.pref_remember_subtitle_track_summary_on
                      else R.string.pref_remember_subtitle_track_summary_off,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rememberGlobalSpeed by preferences.rememberGlobalSpeed.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = rememberGlobalSpeed,
                onValueChange = preferences.rememberGlobalSpeed::set,
                title = { Text(text = stringResource(R.string.pref_remember_global_speed_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(
                      if (rememberGlobalSpeed) R.string.pref_remember_global_speed_summary_on
                      else R.string.pref_remember_global_speed_summary_off,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoSkipIntro by preferences.autoSkipIntro.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoSkipIntro,
                onValueChange = preferences.autoSkipIntro::set,
                title = { Text(text = stringResource(R.string.pref_auto_skip_intro_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(
                      if (autoSkipIntro) R.string.pref_auto_skip_intro_summary_on
                      else R.string.pref_auto_skip_intro_summary_off,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoSkipOutro by preferences.autoSkipOutro.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.FastForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoSkipOutro,
                onValueChange = preferences.autoSkipOutro::set,
                title = { Text(text = stringResource(R.string.pref_auto_skip_outro_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(
                      if (autoSkipOutro) R.string.pref_auto_skip_outro_summary_on
                      else R.string.pref_auto_skip_outro_summary_off,
                    ),
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
                icon = {
                  Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                title = { Text("GSY 播放器", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = "内核 / 渲染方式 / 显示比例 / 手势 / 全屏与旋转，以及「长按用哪个内核播放」——" +
                      "全部在 GSY 播放器自己的设置页里，与 mpv 的配置互不影响",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = { backstack.add(GsyPreferencesScreen) },
              )

              PreferenceDivider()

              val preloadNextVideo by preferences.preloadNextVideo.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = preloadNextVideo,
                onValueChange = preferences.preloadNextVideo::set,
                title = { Text(text = stringResource(R.string.i18n_preload_next_video), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(R.string.i18n_preload_next_video_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 落盘播放缓存（清单 #18）──
              // 开关 + 上限 + 清除三件套。上限滑杆在开关关着时也照样能调：
              // 用户往往是先调好上限、回头再打开开关，硬禁用会让人以为设置坏了。
              val diskCacheEnabled by preferences.diskCacheEnabled.collectAsState()
              val diskCacheSizeMb by preferences.diskCacheSizeMb.collectAsState()
              val scope = rememberCoroutineScope()
              // -1 = 还没量过。跟「真的是 0 字节」区分开，否则第一次进页面
              // 在清完缓存后会分不清该不该刷新显示。
              var cacheBytes by remember { mutableLongStateOf(-1L) }
              var showClearCache by remember { mutableStateOf(false) }
              // 缓存占用要读磁盘，别在重组里同步扫目录 —— 只在进页面和清完之后刷新
              LaunchedEffect(Unit) {
                cacheBytes = withContext(Dispatchers.IO) { MpvDiskCache.sizeBytes(context) }
              }

              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = diskCacheEnabled,
                onValueChange = preferences.diskCacheEnabled::set,
                title = { Text(text = stringResource(R.string.pref_player_disk_cache), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(R.string.pref_player_disk_cache_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              SliderPreference(
                icon = {
                  Icon(
                    Icons.Outlined.SdCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = diskCacheSizeMb.toFloat(),
                onValueChange = { preferences.diskCacheSizeMb.set(it.roundToInt()) },
                sliderValue = diskCacheSizeMb.toFloat(),
                onSliderValueChange = { preferences.diskCacheSizeMb.set(it.roundToInt()) },
                title = { Text(text = stringResource(R.string.pref_player_disk_cache_size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                valueRange = MpvDiskCache.MIN_SIZE_MB.toFloat()..MpvDiskCache.MAX_SIZE_MB.toFloat(),
                summary = {
                  Text(
                    text = stringResource(R.string.pref_player_disk_cache_size_summary) +
                      " ($diskCacheSizeMb MB)",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              Preference(
                title = { Text(text = stringResource(R.string.pref_player_clear_disk_cache), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = if (cacheBytes < 0L) {
                      stringResource(R.string.pref_player_clear_disk_cache_summary)
                    } else {
                      stringResource(R.string.pref_player_clear_disk_cache_summary) +
                        " · " + formatCacheBytes(cacheBytes)
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { showClearCache = true },
              )

              if (showClearCache) {
                ConfirmDialog(
                  title = "清除播放缓存",
                  subtitle = "将删除已缓存的网络流数据。之后重看同一部片子需要重新下载，" +
                    "已经下载到本地的文件（离线下载）不受影响。",
                  onConfirm = {
                    showClearCache = false
                    scope.launch {
                      withContext(Dispatchers.IO) { MpvDiskCache.clear(context) }
                      cacheBytes = withContext(Dispatchers.IO) { MpvDiskCache.sizeBytes(context) }
                      android.widget.Toast
                        .makeText(context, "已清除播放缓存", android.widget.Toast.LENGTH_SHORT)
                        .show()
                    }
                  },
                  onCancel = { showClearCache = false },
                )
              }

              PreferenceDivider()

              val playlistMode by preferences.playlistMode.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = playlistMode,
                onValueChange = preferences.playlistMode::set,
                title = { Text(text = stringResource(R.string.i18n_enable_nav), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.BrightnessMedium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = rememberBrightness,
                onValueChange = preferences.rememberBrightness::set,
                title = { Text(text = stringResource(R.string.pref_player_remember_brightness), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )

              PreferenceDivider()

              val autoPiPOnNavigation by preferences.autoPiPOnNavigation.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.PictureInPictureAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoPiPOnNavigation,
                onValueChange = preferences.autoPiPOnNavigation::set,
                title = { Text("自动画中画", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = keepScreenOnWhenPaused,
                onValueChange = preferences.keepScreenOnWhenPaused::set,
                title = { Text("暂停时保持屏幕常亮", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showDoubleTapOvals,
                onValueChange = preferences.showDoubleTapOvals::set,
                title = { Text(stringResource(R.string.show_splash_ovals_on_double_tap_to_seek), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val showSeekTimeWhileSeeking by preferences.showSeekTimeWhileSeeking.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showSeekTimeWhileSeeking,
                onValueChange = preferences.showSeekTimeWhileSeeking::set,
                title = { Text(stringResource(R.string.show_time_on_double_tap_to_seek), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val usePreciseSeeking by preferences.usePreciseSeeking.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = usePreciseSeeking,
                onValueChange = preferences.usePreciseSeeking::set,
                title = { Text(stringResource(R.string.pref_player_use_precise_seeking), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()

              val customSkipDuration by preferences.customSkipDuration.collectAsState()
              SliderPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = customSkipDuration.toFloat(),
                onValueChange = { preferences.customSkipDuration.set(it.roundToInt()) },
                title = { Text(stringResource(R.string.pref_player_custom_skip_duration_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

              PreferenceDivider()

              // ⭐ 快进/快退时长：带刻度的滑块（见 TickedSlider）。
              // 用自绘滑块而不是 SliderPreference，是因为 Material 那个没有刻度 ——
              // 1~300 秒这个跨度，用户需要一眼看出自己大概在哪个量级。
              val seekForward by preferences.seekForwardSeconds.collectAsState()
              val seekBackward by preferences.seekBackwardSeconds.collectAsState()
              Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TickedSlider(
                  value = coerceSeekStepSeconds(seekForward),
                  onValueChange = { preferences.seekForwardSeconds.set(it) },
                  valueRange = SEEK_STEP_RANGE,
                  title = stringResource(R.string.pref_seek_step_title),
                  supportingText =
                    stringResource(R.string.pref_seek_forward_title) +
                      " · " +
                      stringResource(R.string.pref_seek_seconds, seekForward),
                  valueLabel = stringResource(R.string.pref_seek_seconds, seekForward),
                  ticks = listOf(1, 10, 30, 60, 120, 300),
                  tickLabels = listOf(1, 10, 30, 60, 120, 300),
                )

                Spacer(modifier = Modifier.height(20.dp))

                TickedSlider(
                  value = coerceSeekStepSeconds(seekBackward),
                  onValueChange = { preferences.seekBackwardSeconds.set(it) },
                  valueRange = SEEK_STEP_RANGE,
                  supportingText =
                    stringResource(R.string.pref_seek_backward_title) +
                      " · " +
                      stringResource(R.string.pref_seek_seconds, seekBackward),
                  valueLabel = stringResource(R.string.pref_seek_seconds, seekBackward),
                  ticks = listOf(1, 10, 30, 60, 120, 300),
                  tickLabels = listOf(1, 10, 30, 60, 120, 300),
                )
              }

              PreferenceDivider()

              // 控制栏信息：时钟 / 电量两个独立开关。
              val showClock by preferences.showPlayerClock.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showClock,
                onValueChange = preferences.showPlayerClock::set,
                title = { Text(stringResource(R.string.pref_show_player_clock_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_show_player_clock_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showBattery by preferences.showPlayerBattery.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.BatteryFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showBattery,
                onValueChange = preferences.showPlayerBattery::set,
                title = { Text(stringResource(R.string.pref_show_player_battery_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_show_player_battery_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
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
                icon = {
                  Icon(
                    Icons.Outlined.BrightnessMedium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = brightnessGesture,
                onValueChange = preferences.brightnessGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_brightness), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val volumeGesture by preferences.volumeGesture.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = volumeGesture,
                onValueChange = preferences.volumeGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_volume), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val pinchToZoomGesture by preferences.pinchToZoomGesture.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.ZoomIn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = pinchToZoomGesture,
                onValueChange = preferences.pinchToZoomGesture::set,
                title = { Text(stringResource(R.string.pref_player_gestures_pinch_to_zoom), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val horizontalSwipeToSeek by preferences.horizontalSwipeToSeek.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Swipe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = horizontalSwipeToSeek,
                onValueChange = preferences.horizontalSwipeToSeek::set,
                title = { Text(stringResource(R.string.pref_player_gestures_horizontal_swipe_to_seek), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val horizontalSwipeSensitivity by preferences.horizontalSwipeSensitivity.collectAsState()
              SliderPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = horizontalSwipeSensitivity,
                onValueChange = { preferences.horizontalSwipeSensitivity.set(it.toFixed(3)) },
                title = { Text(stringResource(R.string.pref_player_gestures_horizontal_swipe_sensitivity), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

              // 震动反馈总开关（issue 反馈新增）。
              // 放在「手势」卡里、紧跟横滑快进与灵敏度 —— 震动就是这条手势的配套反馈，
              // 放在这里用户能一眼看出「它管的是滑动时的手感」。
              // 总开关而非只管快进：马达强弱因人而异，给一个一键全关的出口比逐项关更实际。
              val hapticFeedbackEnabled by preferences.hapticFeedbackEnabled.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = hapticFeedbackEnabled,
                onValueChange = preferences.hapticFeedbackEnabled::set,
                title = { Text(stringResource(R.string.pref_player_haptic_feedback), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    text = stringResource(R.string.pref_player_haptic_feedback_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
              
              PreferenceDivider()
              
              val holdForMultipleSpeed by preferences.holdForMultipleSpeed.collectAsState()
              SliderPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = holdForMultipleSpeed,
                onValueChange = { preferences.holdForMultipleSpeed.set(it.toFixed(2)) },
                title = { Text(stringResource(R.string.pref_player_gestures_hold_for_multiple_speed), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showDynamicSpeedOverlay,
                onValueChange = preferences.showDynamicSpeedOverlay::set,
                title = { Text("动态倍速浮层", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                icon = {
                  Icon(
                    Icons.Outlined.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
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
                icon = {
                  Icon(
                    Icons.Outlined.SwapVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = swapVolumeAndBrightness,
                onValueChange = preferences.swapVolumeAndBrightness::set,
                title = { Text(stringResource(R.string.swap_the_volume_and_brightness_slider), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val showLoadingCircle by preferences.showLoadingCircle.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Autorenew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showLoadingCircle,
                onValueChange = preferences.showLoadingCircle::set,
                title = { Text(stringResource(R.string.pref_player_controls_show_loading_circle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )

              PreferenceDivider()

              // 左下角悬浮的上一条/下一条切换按钮（mpv 播放页）
              val showFloatingPlaylistSwitcher by preferences.showFloatingPlaylistSwitcher.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showFloatingPlaylistSwitcher,
                onValueChange = preferences.showFloatingPlaylistSwitcher::set,
                title = { Text("左下角悬浮切换按钮", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = { Text("上一条 / 下一条视频的快捷切换；控制条显示时自动隐藏，打开播放队列时也会收起") },
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
                icon = {
                  Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showSystemStatusBar,
                onValueChange = preferences.showSystemStatusBar::set,
                title = { Text(stringResource(R.string.pref_player_display_show_status_bar), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )

              PreferenceDivider()

              val showSystemNavigationBar by preferences.showSystemNavigationBar.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = showSystemNavigationBar,
                onValueChange = preferences.showSystemNavigationBar::set,
                title = { Text("显示控件时同时显示导航栏", maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )
              
              PreferenceDivider()
              
              val reduceMotion by preferences.reduceMotion.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Animation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = reduceMotion,
                onValueChange = preferences.reduceMotion::set,
                title = { Text(stringResource(R.string.pref_player_display_reduce_player_animation), maxLines = 1, overflow = TextOverflow.Ellipsis) },
              )

              PreferenceDivider()

              // 自动裁黑边（cropdetect）：mpv 专属，探测有几百毫秒开销 + 可能误裁，
              // 所以默认关闭、放在这里由用户自己决定
              val autoCropBlackBars by preferences.autoCropBlackBars.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Crop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoCropBlackBars,
                onValueChange = preferences.autoCropBlackBars::set,
                title = { Text(stringResource(R.string.pref_auto_crop_black_bars_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_auto_crop_black_bars_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }
        }
      }
    }
  }
}
