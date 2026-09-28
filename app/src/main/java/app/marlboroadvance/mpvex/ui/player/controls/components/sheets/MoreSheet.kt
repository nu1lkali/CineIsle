package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import `is`.xyz.mpv.MPVLib
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MoreSheet(
  remainingTime: Int,
  onStartTimer: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  onEnterFiltersPanel: () -> Unit,
  onAnime4KChanged: () -> Unit = {},
  /** 用来从「更多」里直接打开其它面板/子页（快捷功能宫格） */
  onShowSheet: (Sheets) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val decoderPreferences = koinInject<DecoderPreferences>()
  val anime4kManager = koinInject<Anime4KManager>()
  val playerPreferences = koinInject<PlayerPreferences>()

  val autoplayNextVideo by playerPreferences.autoplayNextVideo.collectAsState()
  val closeAfterEof by playerPreferences.closeAfterReachingEndOfVideo.collectAsState()

  val enableAnime4K by decoderPreferences.enableAnime4K.collectAsState()
  val anime4kMode by decoderPreferences.anime4kMode.collectAsState()
  val anime4kQuality by decoderPreferences.anime4kQuality.collectAsState()
  val gpuNext by decoderPreferences.gpuNext.collectAsState()
  val useVulkan by decoderPreferences.useVulkan.collectAsState()

  val context = LocalContext.current
val scope = rememberCoroutineScope()

  PlayerSheet(
    onDismissRequest,
    modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(MaterialTheme.spacing.medium)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(id = R.string.player_sheets_more_title),
          style = MaterialTheme.typography.headlineMedium,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
        ) {
          var isSleepTimerDialogShown by remember { mutableStateOf(false) }
          TextButton(onClick = { isSleepTimerDialogShown = true }) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
              Icon(imageVector = Icons.Outlined.Timer, contentDescription = null)
              Text(
                text =
                  if (remainingTime == 0) {
                    stringResource(R.string.timer_title)
                  } else {
                    stringResource(
                      R.string.timer_remaining,
                      DateUtils.formatElapsedTime(remainingTime.toLong()),
                    )
                  },
              )
              if (isSleepTimerDialogShown) {
                TimePickerDialog(
                  remainingTime = remainingTime,
                  onDismissRequest = { isSleepTimerDialogShown = false },
                  onTimeSelect = onStartTimer,
                )
              }
            }
          }
          TextButton(onClick = onEnterFiltersPanel) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
              Icon(imageVector = Icons.Default.Tune, contentDescription = null)
              Text(text = stringResource(id = R.string.player_sheets_filters_title))
            }
          }
        }
      }


      // ── 播放 ──
      // 这里只留「这一集放完之后干什么」里、底部控件条上没有对应按钮的两项。
      // 循环播放 / 随机播放原本也在这儿各占一行，但底部控件条上的循环按钮已经
      // 是「关闭 → 单个视频循环 → 列表顺序循环」三态轮转（cycleRepeatMode），
      // 随机也有独立按钮；同一功能在两处出现，两边状态不容易对应上，故从「更多」移除。
      Text(
        text = stringResource(R.string.player_sheets_more_playback),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      ) {
        PlaybackToggleRow(
          icon = Icons.Outlined.SkipNext,
          title = stringResource(R.string.pref_autoplay_next_video_title),
          summary = stringResource(
            if (autoplayNextVideo) {
              R.string.player_toggle_autoplay_on
            } else {
              R.string.player_toggle_autoplay_off
            },
          ),
          checked = autoplayNextVideo,
          onCheckedChange = { playerPreferences.autoplayNextVideo.set(it) },
        )

        PlaybackToggleRow(
          icon = Icons.Outlined.PowerSettingsNew,
          title = stringResource(R.string.player_toggle_close_eof),
          summary = stringResource(
            if (closeAfterEof) {
              R.string.player_toggle_close_eof_on
            } else {
              R.string.player_toggle_close_eof_off
            },
          ),
          checked = closeAfterEof,
          onCheckedChange = { playerPreferences.closeAfterReachingEndOfVideo.set(it) },
        )
      }

      // ── 快捷功能 ──
      // 这里原本是「默认统计页面」选择器（关闭 + 第 1~5 页共 6 个 chip）。
      // 统计浮层本身是给开发者看帧时序/缓存用的，属于极低频功能，整块移除；
      // 腾出的位置改成这些「不常用、但需要时得能找到」的功能入口，
      // 播放页的竖屏控件条上就不用再为它们各占一个按钮位置了。
      // 每一项都是「打开对应面板」，不改动播放状态，因此行为可预期、不会误触。
      Text(
        text = stringResource(R.string.player_sheets_more_quick_actions),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
      ) {
        QuickActionItem(
          icon = Icons.Outlined.Speed,
          label = stringResource(R.string.player_control_playback_speed),
          onClick = { onShowSheet(Sheets.PlaybackSpeed) },
        )
        QuickActionItem(
          icon = Icons.Outlined.AspectRatio,
          label = stringResource(R.string.player_sheets_video_settings_title),
          onClick = { onShowSheet(Sheets.AspectRatios) },
        )
        QuickActionItem(
          icon = Icons.Outlined.ZoomIn,
          label = stringResource(R.string.player_control_video_zoom),
          onClick = { onShowSheet(Sheets.VideoZoom) },
        )
        QuickActionItem(
          icon = Icons.Outlined.Camera,
          label = stringResource(R.string.player_sheets_frame_navigation_title),
          onClick = { onShowSheet(Sheets.FrameNavigation) },
        )
        QuickActionItem(
          icon = Icons.Outlined.Bookmarks,
          label = stringResource(R.string.player_sheets_more_chapters),
          onClick = { onShowSheet(Sheets.Chapters) },
        )
        QuickActionItem(
          icon = Icons.Outlined.Audiotrack,
          label = stringResource(R.string.pref_audio),
          onClick = { onShowSheet(Sheets.AudioTracks) },
        )
        QuickActionItem(
          icon = Icons.Outlined.Subtitles,
          label = stringResource(R.string.pref_subtitles),
          onClick = { onShowSheet(Sheets.SubtitleTracks) },
        )
        QuickActionItem(
          icon = Icons.Outlined.Memory,
          label = stringResource(R.string.pref_decoder),
          onClick = { onShowSheet(Sheets.Decoders) },
        )
      }
      
      // Shaders Controls
      if (enableAnime4K && (!gpuNext || useVulkan)) {
        // Auto-detect resolution to disable for 4K+
        val width = MPVLib.getPropertyInt("video-params/w") ?: 0
        val height = MPVLib.getPropertyInt("video-params/h") ?: 0
        val isHighRes = width >= 3840 || height >= 2160

        // Presets (Mode) - Now on Top
        Text(
            text = stringResource(R.string.anime4k_mode_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        if (isHighRes) {
            Text(
                text = stringResource(R.string.i18n_not_available_4k8k),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        ) {
          items(Anime4KManager.Mode.entries) { mode ->
            FilterChip(
              label = { Text(stringResource(mode.titleRes)) },
              selected = anime4kMode == mode.name,
              enabled = !isHighRes,
              leadingIcon = null,
              onClick = {
                decoderPreferences.anime4kMode.set(mode.name)
                
                // Apply shaders immediately (runtime change)
                scope.launch(Dispatchers.IO) {
                  runCatching {
                    val qualityStr = decoderPreferences.anime4kQuality.get()
                    val quality = try {
                      Anime4KManager.Quality.valueOf(qualityStr)
                    } catch (e: IllegalArgumentException) {
                      Anime4KManager.Quality.BALANCED
                    }
                    val currentMode = try {
                        Anime4KManager.Mode.valueOf(mode.name)
                    } catch (e: IllegalArgumentException) {
                        Anime4KManager.Mode.OFF
                    }

                    val shaderChain = anime4kManager.getShaderChain(currentMode, quality)

                    // Use setPropertyString for runtime changes
                    MPVLib.setPropertyString("glsl-shaders", if (shaderChain.isNotEmpty()) shaderChain else "")
                    onAnime4KChanged()
                  }
                }
              }
            )
          }
        }

        Text(
            text = stringResource(R.string.anime4k_quality_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        ) {
          items(Anime4KManager.Quality.entries) { quality ->
             FilterChip(
              label = { Text(stringResource(quality.titleRes)) },
              selected = anime4kQuality == quality.name,
              enabled = anime4kMode != "OFF" && !isHighRes,
              leadingIcon = null,
              onClick = {
                decoderPreferences.anime4kQuality.set(quality.name)

                // Apply shaders immediately (runtime change)
                scope.launch(Dispatchers.IO) {
                  runCatching {
                    val modeStr = decoderPreferences.anime4kMode.get()
                    val modeEnum = try {
                      Anime4KManager.Mode.valueOf(modeStr)
                    } catch (e: IllegalArgumentException) {
                      Anime4KManager.Mode.OFF
                    }
                    val currentQuality = try {
                        Anime4KManager.Quality.valueOf(quality.name)
                    } catch (e: IllegalArgumentException) {
                        Anime4KManager.Quality.BALANCED
                    }

                    val shaderChain = anime4kManager.getShaderChain(modeEnum, currentQuality)

                    // Use setPropertyString for runtime changes
                    MPVLib.setPropertyString("glsl-shaders", if (shaderChain.isNotEmpty()) shaderChain else "")
                    onAnime4KChanged()
                  }
                }
              }
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimePickerDialog(
  onDismissRequest: () -> Unit,
  onTimeSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  remainingTime: Int = 0,
) {
  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      modifier = modifier
          .width(360.dp) // Fixed wide width to fit presets
          .padding(MaterialTheme.spacing.medium),
    ) {
      Column(
        modifier =
          Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
              text = stringResource(R.string.timer_title), // "Sleep Timer"
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.timer_picker_enter_timer),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface
            )
        }

        val state =
          rememberTimePickerState(
            remainingTime / 3600,
            (remainingTime % 3600) / 60,
            is24Hour = true,
          )

        TimeInput(state = state)
        
        // Quick Presets
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.i18n_quick_presets),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = listOf(15, 30, 45, 60)
                presets.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = { 
                            onTimeSelect(minutes * 60)
                            onDismissRequest()
                        },
                        label = { Text("${minutes}m") },
                        leadingIcon = null,
                    )
                }
            }
        }

        // Actions
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          TextButton(onClick = {
             onTimeSelect(0)
             onDismissRequest()
          }) {
              Text(stringResource(id = R.string.generic_reset))
          }
          Spacer(Modifier.weight(1f))
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            TextButton(onClick = onDismissRequest) {
              Text(stringResource(id = R.string.generic_cancel))
            }
            Button(
              onClick = {
                onTimeSelect(state.hour * 3600 + state.minute * 60)
                onDismissRequest()
              },
            ) {
              Text(stringResource(id = R.string.generic_ok))
            }
          }
        }
      }
    }
  }
  }


/**
 * 「更多」面板里的单个快捷功能入口。
 *
 * 图标放在圆形浅底上、下方一行小号标签 —— 宫格排布（外层 FlowRow），
 * 与播放页控件条上的圆形按钮形成区分：这里点一下是「打开面板」，不会改变播放状态。
 */
@Composable
private fun QuickActionItem(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier =
      Modifier
        .width(76.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .padding(vertical = MaterialTheme.spacing.small),
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.onSurface,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier =
          Modifier
            .padding(11.dp)
            .size(20.dp),
      )
    }
    Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * 「更多」面板里的一行播放开关：图标 + 标题/说明 + 右侧 Switch。
 *
 * 整行可点（点行内空白处即切换），和点 Switch 本身等价；
 * 说明文字随开关状态变化，避免「这个开关开着到底代表什么」的歧义
 * （尤其是「播完退出播放器」这种反向表述）。
 */
@Composable
private fun PlaybackToggleRow(
  icon: ImageVector,
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable { onCheckedChange(!checked) }
        .padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.smaller),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
    Spacer(Modifier.width(MaterialTheme.spacing.small))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
