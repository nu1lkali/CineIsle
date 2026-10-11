package app.marlboroadvance.mpvex.ui.player.controls

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.Decoder
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AspectRatioSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AudioTracksSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.ChaptersSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.DecodersSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.FrameNavigationSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.GifRecordSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.MoreSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaybackSpeedSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.SubtitlesSheet
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.VideoZoomSheet
import app.marlboroadvance.mpvex.dlna.DlnaSheet
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.koinInject
import androidx.compose.runtime.collectAsState as composeCollectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

@Composable
fun PlayerSheets(
  sheetShown: Sheets,
  viewModel: app.marlboroadvance.mpvex.ui.player.PlayerViewModel,
  // subtitles sheet
  subtitles: ImmutableList<TrackNode>,
  onAddSubtitle: (Uri) -> Unit,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  onRemoveSubtitle: (Int) -> Unit,
  // audio sheet
  audioTracks: ImmutableList<TrackNode>,
  onAddAudio: (Uri) -> Unit,
  onSelectAudio: (TrackNode) -> Unit,
  // chapters sheet
  chapter: Segment?,
  chapters: ImmutableList<Segment>,
  onSeekToChapter: (Int) -> Unit,
  // Decoders sheet
  decoder: Decoder,
  onUpdateDecoder: (Decoder) -> Unit,
  // Speed sheet
  speed: Float,
  speedPresets: List<Float>,
  onSpeedChange: (Float) -> Unit,
  onAddSpeedPreset: (Float) -> Unit,
  onRemoveSpeedPreset: (Float) -> Unit,
  onResetSpeedPresets: () -> Unit,
  onMakeDefaultSpeed: (Float) -> Unit,
  onResetDefaultSpeed: () -> Unit,
  // More sheet
  sleepTimerTimeRemaining: Int,
  onStartSleepTimer: (Int) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  onShowSheet: (Sheets) -> Unit,
  /** mpv 播放页「更多」里的一键切到 GSY 内核（由 PlayerActivity 实现） */
  onSwitchToGsy: () -> Unit = {},
  onDismissRequest: () -> Unit,
) {
  when (sheetShown) {
    Sheets.None -> {}
    Sheets.SubtitleTracks -> {
      val subtitlesPicker =
        rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) {
          if (it == null) return@rememberLauncherForActivityResult
          onAddSubtitle(it)
        }

      val subtitlesPreferences = koinInject<app.marlboroadvance.mpvex.preferences.SubtitlesPreferences>()
      val savedPickerPath = subtitlesPreferences.pickerPath.get()

      val currentMediaTitle = viewModel.currentMediaTitle
      val matchToName = if (currentMediaTitle.isNotBlank()) {
          // Remove extension if present to improve matching
          currentMediaTitle.substringBeforeLast(".")
      } else null

      var showFilePicker by remember { mutableStateOf(false) }

      // ── 在线字幕搜索（Emby 专属）──
      // 这里自己从 intent 解析 Emby 上下文，而不是让 PlayerActivity 一路传下来：
      // PlayerSheets 已经能拿到宿主 Activity，加两个参数会让 PlayerControls
      // 的参数表再涨一截，而在线字幕只有 Emby 播放才有。
      val embyContext = rememberEmbySubtitleContext()
      var showOnlineSubtitles by remember { mutableStateOf(false) }
      val subtitleScope = rememberCoroutineScope()

      if (showOnlineSubtitles && embyContext != null) {
        app.marlboroadvance.mpvex.ui.browser.emby.subtitle.SubtitleSearchSheet(
          server = embyContext.server,
          itemId = embyContext.itemId,
          mediaSourceId = embyContext.mediaSourceId,
          mediaTitle = currentMediaTitle.ifBlank { embyContext.itemName },
          // 下载完立刻挂载（走 loadSubtitle 会等轨道就绪，见 PlayerViewModel KDoc）
          onDownloaded = { uri ->
            subtitleScope.launch {
              viewModel.loadSubtitle(uri, select = true, silent = false)
            }
          },
          // 在线 sheet 里也给了「本地文件…」按钮，走同一个 SAF picker
          onPickLocal = { showFilePicker = true },
          onDismissRequest = { showOnlineSubtitles = false },
        )
      }

      if (showFilePicker) {
          app.marlboroadvance.mpvex.ui.browser.dialogs.FilePickerDialog(
              isOpen = true,
              currentPath = savedPickerPath.ifBlank { android.os.Environment.getExternalStorageDirectory().absolutePath },
              onDismiss = { showFilePicker = false },
              onPathChanged = { path ->
                  if (path != null) {
                      subtitlesPreferences.pickerPath.set(path)
                  }
              },
              onFileSelected = { path ->
                  showFilePicker = false
                   onAddSubtitle(Uri.parse("file://$path"))
              },
              onSystemPickerRequest = {
                  showFilePicker = false
                  subtitlesPicker.launch(
                    arrayOf(
                      "text/plain",
                      "text/srt",
                      "text/vtt",
                      "application/x-subrip",
                      "application/x-subtitle",
                      "text/x-ssa",
                      "*/*",
                    ),
                  )
              },
              matchToName = matchToName
          )
      }

      SubtitlesSheet(
        tracks = subtitles.toImmutableList(),
        onToggleSubtitle = onToggleSubtitle,
        isSubtitleSelected = isSubtitleSelected,
        onAddSubtitle = { showFilePicker = true },
        // 只有「正在播 Emby 媒体」时才给在线搜索 —— 本地文件 / 网盘直链
        // 没有 itemId，按钮留着只会点了报错
        onSearchOnline = embyContext?.let { { showOnlineSubtitles = true } },
        onRemoveSubtitle = onRemoveSubtitle,
        onOpenSubtitleSettings = { onOpenPanel(Panels.SubtitleSettings) },
        onOpenSubtitleDelay = { onOpenPanel(Panels.SubtitleDelay) },
        onDismissRequest = onDismissRequest
      )
    }

    Sheets.AudioTracks -> {
      val audioPicker =
        rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) {
          if (it == null) return@rememberLauncherForActivityResult
          onAddAudio(it)
        }
      AudioTracksSheet(
        tracks = audioTracks,
        onSelect = onSelectAudio,
        onAddAudioTrack = { audioPicker.launch(arrayOf("*/*")) },
        onOpenDelayPanel = { onOpenPanel(Panels.AudioDelay) },
        onDismissRequest,
      )
    }

    Sheets.Chapters -> {
      if (chapter == null) return
      ChaptersSheet(
        chapters,
        currentChapter = chapter,
        onClick = { onSeekToChapter(chapters.indexOf(it)) },
        onDismissRequest,
      )
    }

    Sheets.Decoders -> {
      DecodersSheet(
        selectedDecoder = decoder,
        onSelect = onUpdateDecoder,
        onDismissRequest,
      )
    }

    Sheets.More -> {
      // 「更多」面板里的播放开关只剩：自动下一集、播完退出（都直接读写偏好）。
      // 循环 / 随机不在这里 —— 底部控件条上已有对应按钮，重复出现只会让两边状态对不上。
      MoreSheet(
        remainingTime = sleepTimerTimeRemaining,
        onStartTimer = onStartSleepTimer,
        onDismissRequest = onDismissRequest,
        onEnterFiltersPanel = { onOpenPanel(Panels.VideoFilters) },
        onAnime4KChanged = { },
        // 「快捷功能」宫格点一下直接切到对应面板（倍速 / 比例 / 缩放 / 逐帧 / 章节 / 音轨 / 字幕 / 解码器）
        onShowSheet = onShowSheet,
        // 最后一项是把整个播放会话交给 GSY 内核（面板内只在 mpv 下显示）
        onSwitchToGsy = onSwitchToGsy,
        // 自动裁黑边（cropdetect，仅 mpv）
        onToggleAutoCrop = viewModel::toggleAutoCrop,
        onSetAutoCrop = viewModel::setAutoCrop,
      )
    }

    Sheets.PlaybackSpeed -> {
      PlaybackSpeedSheet(
        speed,
        onSpeedChange = onSpeedChange,
        speedPresets = speedPresets,
        onAddSpeedPreset = onAddSpeedPreset,
        onRemoveSpeedPreset = onRemoveSpeedPreset,
        onResetPresets = onResetSpeedPresets,
        onMakeDefault = onMakeDefaultSpeed,
        onResetDefault = onResetDefaultSpeed,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.VideoZoom -> {
      val videoZoom by viewModel.videoZoom.composeCollectAsState()
      VideoZoomSheet(
        videoZoom = videoZoom,
        onSetVideoZoom = viewModel::setVideoZoom,
        onResetVideoPan = viewModel::resetVideoPan,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.AspectRatios -> {
      val playerPreferences = koinInject<app.marlboroadvance.mpvex.preferences.PlayerPreferences>()
      val customRatiosSet by playerPreferences.customAspectRatios.collectAsState()
      val currentRatio by viewModel.currentAspectRatio.composeCollectAsState()
      val customRatios =
        customRatiosSet.mapNotNull { str ->
          val parts = str.split("|")
          if (parts.size == 2) {
            app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AspectRatio(
              label = parts[0],
              ratio = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
              isCustom = true,
            )
          } else {
            null
          }
        }

      AspectRatioSheet(
        currentRatio = currentRatio,
        customRatios = customRatios,
        onSelectRatio = { ratio ->
          if (ratio < 0) {
            // Default selected - apply Fit mode
            viewModel.changeVideoAspect(app.marlboroadvance.mpvex.ui.player.VideoAspect.Fit)
          } else {
            // Custom ratio selected
            viewModel.setCustomAspectRatio(ratio)
          }
        },
        onAddCustomRatio = { label, ratio ->
          playerPreferences.customAspectRatios.set(customRatiosSet + "$label|$ratio")
          viewModel.setCustomAspectRatio(ratio)
        },
        onDeleteCustomRatio = { ratio ->
          val toRemove = "${ratio.label}|${ratio.ratio}"
          playerPreferences.customAspectRatios.set(customRatiosSet - toRemove)
          // If the deleted ratio is currently active, reset to default (Fit)
          if (kotlin.math.abs(currentRatio - ratio.ratio) < 0.01) {
            viewModel.changeVideoAspect(app.marlboroadvance.mpvex.ui.player.VideoAspect.Fit)
          }
        },
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.FrameNavigation -> {
      val currentFrame by viewModel.currentFrame.composeCollectAsState()
      val totalFrames by viewModel.totalFrames.composeCollectAsState()
      FrameNavigationSheet(
        currentFrame = currentFrame,
        totalFrames = totalFrames,
        onUpdateFrameInfo = viewModel::updateFrameInfo,
        onPause = viewModel::pause,
        onUnpause = viewModel::unpause,
        onPauseUnpause = viewModel::pauseUnpause,
        onSeekTo = { position, _ -> viewModel.seekTo(position) },
        onDismissRequest = onDismissRequest,
      )
    }


    Sheets.Cast -> {
      DlnaSheet(onDismissRequest = onDismissRequest)
    }

    Sheets.Playlist -> {
      // Refresh playlist items when sheet is shown
      LaunchedEffect(Unit) {
        viewModel.refreshPlaylistItems()
      }

      // Observe playlist updates
      val playlist by viewModel.playlistItems.collectAsState()
      val playerPreferences = koinInject<app.marlboroadvance.mpvex.preferences.PlayerPreferences>()

      if (playlist.isNotEmpty()) {
        val playlistImmutable = playlist.toImmutableList()
        val totalCount = viewModel.getPlaylistTotalCount()
        val isM3U = viewModel.isPlaylistM3U()
        PlaylistSheet(
          playlist = playlistImmutable,
          onDismissRequest = onDismissRequest,
          onItemClick = { item ->
            viewModel.playPlaylistItem(item.index)
          },
          // M3U 列表是从数据库/M3U 文件读出来的只读队列，不改它的顺序与内容
          onMoveItem = { from, to ->
            if (!isM3U) viewModel.movePlaylistItem(from, to)
          },
          onRemoveItem =
            if (isM3U) {
              null
            } else {
              { index -> viewModel.removePlaylistItem(index) }
            },
          totalCount = totalCount,
          isM3UPlaylist = isM3U,
          playerPreferences = playerPreferences,
        )
      }
    }

    Sheets.GifRecord -> {
      val gifPreferences = koinInject<app.marlboroadvance.mpvex.preferences.PlayerPreferences>()
      val gifProgress by viewModel.gifProgress.composeCollectAsState()
      val gifWidth by gifPreferences.gifWidth.collectAsState()
      GifRecordSheet(
        progress = gifProgress,
        qualityWidth = gifWidth,
        onQualityChange = { gifPreferences.gifWidth.set(it) },
        onStart = viewModel::startGifRecording,
        onCancel = viewModel::cancelGifRecording,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.Danmaku -> {
      // 弹幕状态挂在宿主 Activity 上（只有 mpv 播放页有），拿不到就整个面板不显示。
      val host = LocalActivity.current as? app.marlboroadvance.mpvex.ui.player.PlayerActivity
      val danmakuState = host?.danmakuState()
      if (danmakuState != null) {
        val sheetState by danmakuState.sheet.composeCollectAsState()
        app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuSheet(
          state = sheetState,
          onEnabledChange = danmakuState::setEnabled,
          onHostChange = danmakuState::setHost,
          onAppIdChange = danmakuState::setAppId,
          onAppSecretChange = danmakuState::setAppSecret,
          onAutoMatchChange = danmakuState::setAutoMatch,
          onFontScaleChange = danmakuState::setFontScale,
          onSpeedScaleChange = danmakuState::setSpeedScale,
          onAlphaChange = danmakuState::setAlpha,
          onAreaChange = danmakuState::setArea,
          onDensityChange = danmakuState::setDensity,
          onBlockWordsChange = danmakuState::setBlockWords,
          onSearch = danmakuState::search,
          onPickAnime = { anime ->
            // ⚠️ 手动选番时按**当前播放的集号**取那一集，
            // 不能拿搜索结果的第一集 —— 用户搜的是系列，要看的是当前这集。
            danmakuState.pick(anime, host.playlistIndex)
          },
          onDismissRequest = onDismissRequest,
        )
      }
    }
  }
}
