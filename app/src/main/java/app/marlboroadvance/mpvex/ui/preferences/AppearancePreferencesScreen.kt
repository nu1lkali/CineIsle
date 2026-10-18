package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Segment
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.MultiChoiceSegmentedButton
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.preferences.components.ThemePicker
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Serializable
object AppearancePreferencesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val preferences = koinInject<AppearancePreferences>()
        val browserPreferences = koinInject<BrowserPreferences>()
        val gesturePreferences = koinInject<GesturePreferences>()
        val backstack = LocalBackStack.current
        val systemDarkTheme = isSystemInDarkTheme()

        val darkMode by preferences.darkMode.collectAsState()
        val appTheme by preferences.appTheme.collectAsState()

        // Determine if we're in dark mode for theme preview
        val isDarkMode = when (darkMode) {
            DarkMode.Dark -> true
            DarkMode.Light -> false
            DarkMode.System -> systemDarkTheme
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.pref_appearance_title),
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
                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_theme))
                    }

                    item {
                        PreferenceCard {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                // Dark mode selector
                                MultiChoiceSegmentedButton(
                                    choices = DarkMode.entries.map { stringResource(it.titleRes) }.toImmutableList(),
                                    selectedIndices = persistentListOf(DarkMode.entries.indexOf(darkMode)),
                                    onClick = { preferences.darkMode.set(DarkMode.entries[it]) },
                                )
                            }

                            PreferenceDivider()

                            // AMOLED mode state - need it before theme picker
                            val amoledMode by preferences.amoledMode.collectAsState()

                            // Theme picker - Aniyomi style
                            ThemePicker(
                                currentTheme = appTheme,
                                isDarkMode = isDarkMode,
                                onThemeSelected = { preferences.appTheme.set(it) },
                                modifier = Modifier.padding(vertical = 8.dp),
                            )

                            PreferenceDivider()

                            // AMOLED mode toggle
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.DarkMode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = amoledMode,
                                onValueChange = { newValue ->
                                    preferences.amoledMode.set(newValue)
                                },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_amoled_mode_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_amoled_mode_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                enabled = darkMode != DarkMode.Light
                            )
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_file_browser))
                    }

                    item {
                        PreferenceCard {
                            val unlimitedNameLines by preferences.unlimitedNameLines.collectAsState()
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.Title,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = unlimitedNameLines,
                                onValueChange = { preferences.unlimitedNameLines.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_unlimited_name_lines_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_unlimited_name_lines_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            val showUnplayedOldVideoLabel by preferences.showUnplayedOldVideoLabel.collectAsState()
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.Label,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = showUnplayedOldVideoLabel,
                                onValueChange = { preferences.showUnplayedOldVideoLabel.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_unplayed_old_video_label_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_unplayed_old_video_label_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            val unplayedOldVideoDays by preferences.unplayedOldVideoDays.collectAsState()
                            SliderPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.CalendarToday,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = unplayedOldVideoDays.toFloat(),
                                onValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_unplayed_old_video_days_title)) },
                                valueRange = 1f..30f,
                                summary = {
                                    Text(
                                        text = stringResource(
                                            id = R.string.pref_appearance_unplayed_old_video_days_summary,
                                            unplayedOldVideoDays,
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                onSliderValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                                sliderValue = unplayedOldVideoDays.toFloat(),
                                enabled = showUnplayedOldVideoLabel
                            )

                            PreferenceDivider()

                            val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.SwapVert,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = autoScrollToLastPlayed,
                                onValueChange = { browserPreferences.autoScrollToLastPlayed.set(it) },
                                title = {
                                    Text(text = stringResource(R.string.pref_appearance_auto_scroll_title))
                                },
                                summary = {
                                    Text(
                                        text = stringResource(R.string.pref_appearance_auto_scroll_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            // 侧边滚动条：网络浏览 / Emby 媒体库等长列表右侧那根可拖动的滚动条。
                            // **全局开关** —— 消费入口统一在 ui/utils/SideScrollbar.kt 的两个外壳里
                            // （LazyGridSideScrollbar / LazyListSideScrollbar），所有长列表套同一个壳
                            // 即自动遵循本设置；新增长列表不要绕过壳直接调滚动条库。
                            val sideScrollbar by browserPreferences.sideScrollbarEnabled.collectAsState()
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.Segment,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = sideScrollbar,
                                onValueChange = { browserPreferences.sideScrollbarEnabled.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_side_scrollbar_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(
                                            id = if (sideScrollbar) {
                                                R.string.pref_appearance_side_scrollbar_summary_on
                                            } else {
                                                R.string.pref_appearance_side_scrollbar_summary_off
                                            },
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )

                            PreferenceDivider()

                            val watchedThreshold by browserPreferences.watchedThreshold.collectAsState()
                            SliderPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = watchedThreshold.toFloat(),
                                onValueChange = { browserPreferences.watchedThreshold.set(it.roundToInt()) },
                                sliderValue = watchedThreshold.toFloat(),
                                onSliderValueChange = { browserPreferences.watchedThreshold.set(it.roundToInt()) },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_watched_threshold_title)) },
                                valueRange = 50f..100f,
                                valueSteps = 9,
                                summary = {
                                    Text(
                                        text = stringResource(
                                            id = R.string.pref_appearance_watched_threshold_summary,
                                            watchedThreshold,
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )

                            PreferenceDivider()

                            val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
                            SwitchPreference(
                                icon = {
                                  Icon(
                                    Icons.Outlined.TouchApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                  )
                                },
                                value = tapThumbnailToSelect,
                                onValueChange = { gesturePreferences.tapThumbnailToSelect.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_gesture_tap_thumbnail_to_select_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_gesture_tap_thumbnail_to_select_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )
                        }
                    }

                    // 外观深度定制（桌面图标 / 加载动画）—— 从设置主页「界面与外观」挪进来（用户要求）：
                    // 都在外观页里改，不用回主页绕一圈。单独一卡，不并进上面两个分类 ——
                    // 这一页全都会**立刻看到画面变化**，混在长列表里对不上「改了什么」。
                    item {
                        PreferenceCard {
                            Preference(
                                title = { Text(text = stringResource(id = R.string.appearance_deep_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.appearance_icon_title) + " · " +
                                            stringResource(id = R.string.appearance_loading_anim_title),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                icon = {
                                    Icon(
                                        Icons.Outlined.AutoAwesomeMotion,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = { backstack.add(AppearanceDeepCustomScreen) },
                            )
                        }
                    }

                }
            }
        }
    }
}
