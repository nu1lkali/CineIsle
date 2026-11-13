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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleMatch
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleScan
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleScanner
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
      val subtitlesPreferences = koinInject<app.marlboroadvance.mpvex.preferences.SubtitlesPreferences>()

      // 用户手动挑中的外挂字幕 → 落一条持久绑定（片名 → 字幕 Uri）。
      // 只有「用户点出来的」才记；目录扫描自动加进来的不记，否则一次误扫就会把错字幕钉死。
      // 刻意读 viewModel 的当前值而不是闭包里的局部变量：选择器的回调是记住的，
      // 捕捉到的是首次组合时的值，片名换了之后就会记错片子。
      val bindManualSubtitle: (Uri) -> Unit = { uri ->
        val title = viewModel.currentMediaTitle
        if (title.isNotBlank()) {
          app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings.bind(
            subtitlesPreferences,
            title,
            uri.toString(),
          )
        }
      }

      val subtitlesPicker =
        rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) {
          if (it == null) return@rememberLauncherForActivityResult
          bindManualSubtitle(it)
          onAddSubtitle(it)
        }

      val savedPickerPath = subtitlesPreferences.pickerPath.get()

      val currentMediaTitle = viewModel.currentMediaTitle
      val matchToName = if (currentMediaTitle.isNotBlank()) {
          // Remove extension if present to improve matching
          currentMediaTitle.substringBeforeLast(".")
      } else null

      // ── 字幕目录里按番号 / 标题智能匹配（纯本地，不发任何请求）──
      // 只取 media-title：PlayerActivity 开播时把它设成文件名（force-media-title），
      // 番号就在那里面。Emby 的 `itemName` 实际存的是 itemId（见 EmbySubtitleContext），
      // 当名字用会把「匹配钥匙」变成一串 32 位十六进制。
      val saveFolder by subtitlesPreferences.subtitleSaveFolder.collectAsState()
      val hostContext = LocalContext.current.applicationContext
      val localQuery = remember(currentMediaTitle) { LocalSubtitleMatch.buildQuery(currentMediaTitle) }
      val localScan by
        produceState(initialValue = LocalSubtitleScan(), localQuery, saveFolder) {
          // 目录没配、或一个可用条件都没有 ⇒ 不扫。此时面板与旧版逐行一致。
          if (saveFolder.isBlank() || localQuery.isEmpty) {
            value = LocalSubtitleScan()
            return@produceState
          }
          value = LocalSubtitleScan(scanning = true)
          value = LocalSubtitleScanner.scan(hostContext, saveFolder, localQuery)
        }

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
        onDismissRequest = onDismissRequest,
        // 字幕目录里的候选；点一下直接挂载（走与在线字幕同一条 loadSubtitle 路径）
        localScan = localScan,
        localMatchLabel = localQuery.label,
        onPickLocalSubtitle = { candidate ->
          // 记住这次的选择：下次打开面板时它排第一（用户自己验证过的那条，比任何猜测都准）
          app.marlboroadvance.mpvex.ui.player.subtitle.SubtitlePickMemory.remember(
            app.marlboroadvance.mpvex.ui.player.subtitle.SubtitlePickMemory.keyOf(localQuery),
            candidate.uri,
          )
          // 同一条关系也落进持久账本：进程被杀重进时自动挂回去
          val picked = Uri.parse(candidate.uri)
          bindManualSubtitle(picked)
          subtitleScope.launch {
            viewModel.loadSubtitle(picked, select = true, silent = false)
          }
        },
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
        // ⚠️ 面板每次打开都从偏好刷一次快照。`_sheet` 的初始值是
        //    `DanmakuSheetState()`（enabled=false），而「设置-弹幕」页的开关
        //    是直接写 preference 的（不走本状态的 setter）—— 不刷的话，
        //    用户在设置里开了弹幕+自动匹配，播放时弹幕正常加载、画面正常渲染，
        //    面板总开关却显示灭的（「弹幕加载了，开关没自动打开」）。
        //    只在进入本分支时跑一次：关闭面板即离开组合，重开自然重刷。
        LaunchedEffect(danmakuState) { danmakuState.refreshSheet() }
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
          onPreventOverlappingChange = danmakuState::setPreventOverlapping,
          onBlockWordsChange = danmakuState::setBlockWords,
          onTimeOffsetChange = danmakuState::setTimeOffset,
          onClearTimeOffset = danmakuState::clearTimeOffset,
          onRememberSelectionChange = danmakuState::setRememberSelection,
          onWithRelatedChange = danmakuState::setWithRelated,
          onSearch = danmakuState::search,
          onPickAnime = { anime ->
            // ⚠️ 手动选番时按**当前播放的集号**取那一集，
            // 不能拿搜索结果的第一集 —— 用户搜的是系列，要看的是当前这集。
            // 且必须是「真实集号」而不是 playlistIndex（理由见 currentEpisodeIndexForDanmaku）。
            danmakuState.pick(anime, host.currentEpisodeIndexForDanmaku())
          },
          // 「选集」：自动挑错集时（跨季番最常见）由用户自己指定。
          onLoadEpisodes = { anime -> danmakuState.episodeList(anime) },
          onPickEpisode = { anime, episode -> danmakuState.pickEpisode(anime, episode) },
          onDismissRequest = onDismissRequest,
        )
      }
    }
  }
}
