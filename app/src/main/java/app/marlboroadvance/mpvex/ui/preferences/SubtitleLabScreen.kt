package app.marlboroadvance.mpvex.ui.preferences

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTimeline
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitle
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.subtitle.lab.LabPreviewPane
import app.marlboroadvance.mpvex.ui.subtitle.online.OnlineSubtitleSheet
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 设置页 →「字幕实验室」。
 *
 * ## 页面结构（需求 7.1 / 7.2）
 *
 * 上半部分固定是**视频预览窗口**（16:9，满宽，画面主体），下半部分可滚动：
 * 选片 → 搜索 / 下载 → 已下载字幕管理 → 时间轴校准。
 * 预览不受下半部分滚动影响 —— 调偏移量时眼睛不用离开画面。
 *
 * ## 交互主线（v314 重排）
 *
 * 1. 选片（继续观看 / 按媒体库搜索，带封面）
 * 2. 搜索并下载网络字幕
 * 3. 试看 → 打开「时间线」点某一句，画面直接跳到那一刻
 * 4. 对不上就 ±100 / ±500 拧偏移，画面立即跟着变
 *
 * 页面里所有动作都有明确中文说明与状态反馈（Snackbar / 进度条），不靠猜。
 */
@Serializable
object SubtitleLabScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: SubtitleLabViewModel =
      viewModel(factory = viewModelFactory { initializer { SubtitleLabViewModel(app) } })
    val state by vm.state.collectAsState()

    var mediaPickerOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var timelineOpen by remember { mutableStateOf(false) }
    var offsetEditing by remember { mutableStateOf<DownloadedSubtitle?>(null) }
    var deleteConfirm by remember { mutableStateOf<DownloadedSubtitle?>(null) }
    var infoRecord by remember { mutableStateOf<DownloadedSubtitle?>(null) }
    var libraryQuery by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    // 预览播放位置单独放一个 State：只让「时间线」这一个读取方重组。
    // 若塞进 viewModel.state，250ms 一次的心跳会把整页（含列表）全部重组一遍。
    val playhead = remember { mutableDoubleStateOf(0.0) }

    // 已下载字幕可能积累到几十条，按片名 / 字幕标题 / 文件名 / 来源 / 语言 / 格式过滤。
    // 多个关键词用空格分隔，全部命中才算匹配（「片名 简体」这种组合能直接收敛结果）。
    val filteredLibrary =
      remember(state.library, libraryQuery) {
        val query = libraryQuery.trim()
        if (query.isBlank()) state.library else state.library.filter { it.matchesQuery(query) }
      }

    // 按媒体分组：同一部片下了四五条字幕时，平铺列表看不出它们是同一部片，
    // 删除 / 设默认都很容易点错行。分组只影响展示，顺序按片名排。
    val groupedLibrary =
      remember(filteredLibrary) {
        filteredLibrary
          .groupBy { it.serverKey to it.itemId }
          .map { (key, records) -> key to records.sortedByDescending { it.downloadedAt } }
          .sortedBy { (_, records) -> records.first().mediaTitle.lowercase(Locale.getDefault()) }
      }
    // 只有一部片子时不显示分组标题：上方「目标影片」卡片已经写着片名了，再加一行是噪音
    val showGroupHeaders = groupedLibrary.size > 1

    // 所有动作的反馈都走这里（下载成功 / 设为默认 / 删除 / 取流失败…）
    LaunchedEffect(state.message) {
      val message = state.message
      if (!message.isNullOrBlank()) {
        snackbar.showSnackbar(message)
        vm.clearMessage()
      }
    }

    Scaffold(
      snackbarHost = { SnackbarHost(snackbar) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              "字幕实验室",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backStack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // ── 预览窗口：固定在上半部分，不随下方列表滚动 ──
        LabPreviewPane(
          source = state.previewSource,
          subtitle = state.previewSubtitle,
          canStart = state.target != null,
          seekRequest = state.seekRequest,
          onStart = vm::startPreview,
          onStop = vm::stopPreview,
          onOffsetChange = vm::adjustPreviewOffset,
          onPositionChange = { seconds -> playhead.doubleValue = seconds },
        )

        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item(key = "target") {
            TargetMediaCard(
              hasTarget = state.target != null,
              title = state.selectedItem?.Name,
              posterUrl = state.selectedItem?.let(vm::posterUrl),
              keywordHint = state.target?.suggestKeyword()?.keyword,
              loading = state.loadingMedia,
              error = state.mediaError,
              previewing = state.previewSource != null,
              hasTimeline = state.timelineRecordId != null,
              timelineCount = state.timelineCues.size,
              onPick = { mediaPickerOpen = true },
              onReload = vm::loadMediaCandidates,
              onSearch = { searchOpen = true },
              onTimeline = { timelineOpen = true },
              onPreview = vm::startPreview,
              onStopPreview = vm::stopPreview,
            )
          }

          item(key = "library_header") {
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "已下载字幕",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = if (libraryQuery.isBlank()) {
                  "${state.library.size} 条"
                } else {
                  "${filteredLibrary.size} / ${state.library.size} 条"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          // 字幕积累到几十条后逐条翻找不现实，这里按「片名 / 字幕标题 / 文件名 / 来源 / 语言」
          // 过滤；搜索框只在已有字幕时出现，没有字幕时不占版面。
          if (state.library.isNotEmpty()) {
            item(key = "library_search") {
              OutlinedTextField(
                value = libraryQuery,
                onValueChange = { libraryQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = {
                  Text(
                    text = "搜索片名、字幕标题或文件名",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                },
                trailingIcon = {
                  if (libraryQuery.isNotEmpty()) {
                    TextButton(onClick = { libraryQuery = "" }) {
                      Text("清除", style = MaterialTheme.typography.labelMedium)
                    }
                  }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
              )
            }
          }

          // ── 本地库概况 + 孤儿清理 ──
          if (state.library.isNotEmpty() || state.stats.orphanCount > 0) {
            item(key = "library_stats") {
              LibraryStatsCard(
                recordCount = state.stats.recordCount,
                mediaCount = state.stats.mediaCount,
                totalBytes = state.stats.totalBytes,
                orphanCount = state.stats.orphanCount,
                orphanBytes = state.stats.orphanBytes,
                cleaning = state.cleaning,
                onPurge = vm::purgeOrphans,
              )
            }
          }

          if (state.library.isEmpty()) {
            item(key = "library_empty") {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(14.dp)) {
                  Text(
                    text = "尚未下载网络字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                  )
                  Text(
                    text = "请先在上方选择目标影片，再使用「搜索并下载字幕」。已下载的字幕会显示在此处，" +
                      "可试看、校准时间轴与设为默认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                  )
                }
              }
            }
          } else if (filteredLibrary.isEmpty()) {
            item(key = "library_no_match") {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(14.dp)) {
                  Text(
                    text = "没有匹配的字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                  )
                  Text(
                    text = "当前关键词「${libraryQuery.trim()}」在 ${state.library.size} 条已下载字幕中没有命中。" +
                      "可尝试只输入片名的前几个字，或点击右上角清除关键词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                  )
                  TextButton(
                    onClick = { libraryQuery = "" },
                    modifier = Modifier.padding(top = 2.dp),
                  ) {
                    Text("清除关键词", style = MaterialTheme.typography.labelMedium)
                  }
                }
              }
            }
          } else {
            groupedLibrary.forEach { (key, records) ->
              if (showGroupHeaders) {
                item(key = "group_${key.first}_${key.second}") {
                  MediaGroupHeader(
                    title = records.first().mediaTitle.ifBlank { records.first().title },
                    count = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                  )
                }
              }
              items(records, key = { it.id }) { record ->
                LibraryCard(
                  record = record,
                  previewing = state.previewSubtitle?.recordId == record.id,
                  timelineActive = state.timelineRecordId == record.id,
                  redownloading = state.redownloading == record.id,
                  redownloadProgress = if (state.redownloading == record.id) state.redownloadProgress else null,
                  onPreview = { vm.previewRecord(record) },
                  onUse = { vm.useRecord(record) },
                  onTimeline = {
                    vm.loadTimeline(record)
                    timelineOpen = true
                  },
                  onInfo = { infoRecord = record },
                  onRedownload = { vm.redownload(record) },
                  onMinus500 = { vm.adjustOffset(record, -500) },
                  onMinus100 = { vm.adjustOffset(record, -100) },
                  onPlus100 = { vm.adjustOffset(record, 100) },
                  onPlus500 = { vm.adjustOffset(record, 500) },
                  onExact = { offsetEditing = record },
                  onReset = { vm.setOffset(record, 0L) },
                  onDelete = { deleteConfirm = record },
                )
              }
            }
          }

          item(key = "footer") {
            HelpFooter()
          }
        }
      }
    }

    // ── 媒体选择：媒体库筛选 + 继续观看 ──
    if (mediaPickerOpen) {
      // skipPartiallyExpanded + 固定高度内容，是为了解决「往下拖关闭时抖动」：
      // 内容高度随结果条数变化时，拖拽中每一帧都在重新测量，sheet 会自己跳。
      val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(onDismissRequest = { mediaPickerOpen = false }, sheetState = sheetState) {
        MediaPickerSheetContent(
          state = state,
          onQueryChange = vm::setMediaQuery,
          onSearch = vm::searchMedia,
          onClear = vm::clearMediaSearch,
          onSelectLibrary = vm::selectLibrary,
          posterUrl = vm::posterUrl,
          onPick = { media ->
            val server = state.selectedServer
            if (server != null) {
              vm.selectMedia(server, media)
              mediaPickerOpen = false
            }
          },
        )
      }
    }

    // ── 搜索面板：与播放页 / 详情页同一个组件、同一套业务逻辑 ──
    val target = state.target
    if (searchOpen && target != null) {
      OnlineSubtitleSheet(
        target = target,
        primaryActionLabel = "下载",
        onUse = null,
        onDismiss = {
          searchOpen = false
          // 面板里下完的字幕要立刻反映到实验室的列表
          vm.refreshLibrary()
        },
      )
    }

    // ── 字幕时间线：点一句 → 预览跳到那一刻 ──
    val timelineRecord = state.library.firstOrNull { it.id == state.timelineRecordId }
    if (timelineOpen && timelineRecord != null) {
      TimelineSheet(
        record = timelineRecord,
        cues = state.timelineCues,
        loading = state.timelineLoading,
        error = state.timelineError,
        truncated = state.timelineTruncated,
        playheadSeconds = playhead.doubleValue,
        // 预览里挂的是**这条**字幕才算「正在播这条」；否则高亮没有意义
        previewing = state.previewSource != null && state.previewSubtitle?.recordId == timelineRecord.id,
        onSeek = vm::seekToCue,
        onDismiss = { timelineOpen = false },
      )
    }

    offsetEditing?.let { record ->
      ExactOffsetDialog(
        record = record,
        onDismiss = { offsetEditing = null },
        onConfirm = { value ->
          vm.setOffset(record, value)
          offsetEditing = null
        },
      )
    }

    infoRecord?.let { record ->
      AlertDialog(
        onDismissRequest = { infoRecord = null },
        title = { Text("文件信息") },
        text = {
          Text(
            text = vm.fileInfoOf(record),
            style = MaterialTheme.typography.bodySmall,
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              vm.redownload(record)
              infoRecord = null
            },
            enabled = record.originUrl.isNotBlank(),
          ) { Text("重新下载") }
        },
        dismissButton = {
          TextButton(onClick = { infoRecord = null }) { Text("关闭") }
        },
      )
    }

    deleteConfirm?.let { record ->
      AlertDialog(
        onDismissRequest = { deleteConfirm = null },
        title = { Text("删除该字幕？") },
        text = {
          Text(
            "将从本机删除「${record.title}」的文件与关联记录。不影响 Emby 服务器上的字幕。" +
              "此操作不可撤销。",
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              vm.delete(record)
              deleteConfirm = null
            },
          ) {
            Text(text = "删除", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { deleteConfirm = null }) { Text("取消") }
        },
      )
    }
  }
}

// ════════════════════════════ 目标影片 ════════════════════════════

/**
 * 目标影片卡：封面 + 片名 + 搜索关键词 + 三个动作。
 *
 * 封面不是装饰：番号类文件名（`ABC-123.mkv`）在媒体库里可能对应一堆同系列条目，
 * 只有封面能让人一眼确认「是不是这一部」。
 */
@Composable
private fun TargetMediaCard(
  hasTarget: Boolean,
  title: String?,
  posterUrl: String?,
  keywordHint: String?,
  loading: Boolean,
  error: String?,
  previewing: Boolean,
  hasTimeline: Boolean,
  timelineCount: Int,
  onPick: () -> Unit,
  onReload: () -> Unit,
  onSearch: () -> Unit,
  onTimeline: () -> Unit,
  onPreview: () -> Unit,
  onStopPreview: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "目标影片",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPick) {
          Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (hasTarget) "更换影片" else "选择影片")
        }
      }

      if (!hasTarget) {
        Text(
          text = error ?: "先选一部影片：搜索时按它生成番号，下载的字幕也挂在它下面。",
          style = MaterialTheme.typography.bodySmall,
          color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (error != null) {
          TextButton(onClick = onReload) { Text("重新读取") }
        }
      } else {
        Row(verticalAlignment = Alignment.Top) {
          Box(
            modifier = Modifier
              .size(width = 46.dp, height = 66.dp)
              .clip(RoundedCornerShape(6.dp)),
          ) {
            EmbyImage(
              url = posterUrl,
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              maxWidth = 240,
            )
          }
          Spacer(Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = title.orEmpty().ifBlank { "已选择" },
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (!keywordHint.isNullOrBlank()) {
              Text(
                text = "搜索关键词：$keywordHint",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
            if (loading) {
              Spacer(Modifier.height(6.dp))
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
          }
        }

        Spacer(Modifier.height(10.dp))
        // 这里刻意用 FlowRow，而不是「Button(weight=1f) + 自适应 OutlinedButton」：
        // 后者在窄屏或被系统字体放大后会把等宽那一侧压到装不下，而 `softWrap = false`
        // 又不带省略号 ⇒ 文字被硬切掉半截（用户报过「只显示一部分的字」）。
        // FlowRow 让每个按钮按自身文案取宽，放不下就换行，永远不裁字。
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("搜索字幕", maxLines = 1)
          }
          OutlinedButton(onClick = if (previewing) onStopPreview else onPreview) {
            Icon(
              imageVector = if (previewing) Icons.Default.CheckCircle else Icons.Default.PlayCircleOutline,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (previewing) "关闭画面" else "试看画面", maxLines = 1)
          }
        }

        if (hasTimeline) {
          Spacer(Modifier.height(4.dp))
          TextButton(
            onClick = onTimeline,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 32.dp),
          ) {
            Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              text = if (timelineCount > 0) "字幕时间线 · 共 $timelineCount 条" else "字幕时间线",
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

// ════════════════════════════ 媒体选择器 ════════════════════════════

/**
 * 媒体选择面板：媒体库筛选 + 按片名搜索 + 继续观看。
 *
 * ## 为什么固定高度
 *
 * 内容高度如果随「结果条数 / 加载状态」变化，用户往下拖想关掉面板时，每一帧都在
 * 重新测量 sheet 高度 —— 表现就是「拖到一半自己跳一下」。这里把外壳钉成 85% 屏高，
 * 内部列表自己滚动，拖拽就只影响 sheet 位移，不再触发重新测量。
 */
@Composable
private fun MediaPickerSheetContent(
  state: SubtitleLabViewModel.State,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onClear: () -> Unit,
  onSelectLibrary: (String?) -> Unit,
  posterUrl: (EmbyItem) -> String?,
  onPick: (EmbyItem) -> Unit,
) {
  val searching = state.searchingMedia
  val showSearch = state.mediaQuery.isNotBlank()
  val rows = if (showSearch) state.searchResults else state.mediaCandidates
  val error = if (showSearch) state.searchError else state.mediaError

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.85f)
      .padding(horizontal = 16.dp),
  ) {
    Text(
      text = "选择要配字幕的影片",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = "可按媒体库限定搜索范围，也可从「继续观看」中直接选择。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )

    // ── 媒体库筛选 ──
    if (state.libraries.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilterChip(
          selected = state.selectedLibraryId == null,
          onClick = { onSelectLibrary(null) },
          label = { Text("全部媒体库") },
        )
        state.libraries.forEach { lib ->
          val id = lib.Id ?: return@forEach
          FilterChip(
            selected = state.selectedLibraryId == id,
            onClick = { onSelectLibrary(id) },
            label = {
              Text(
                text = lib.Name.orEmpty().ifBlank { "未命名库" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
          )
        }
      }
      Spacer(Modifier.height(6.dp))
    } else if (state.loadingLibraries) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    }

    OutlinedTextField(
      value = state.mediaQuery,
      onValueChange = onQueryChange,
      singleLine = true,
      label = { Text("搜索片名 / 番号") },
      placeholder = { Text("例如 ABC-123") },
      leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
      trailingIcon = {
        if (state.mediaQuery.isNotBlank()) {
          TextButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          ) {
            Text("清空", style = MaterialTheme.typography.labelSmall)
          }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onSearch() }),
      modifier = Modifier.fillMaxWidth(),
    )

    if (searching) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }

    Spacer(Modifier.height(8.dp))

    when {
      rows.isEmpty() -> Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = error ?: if (showSearch) "输入片名后执行搜索" else "「继续观看」暂无内容",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (showSearch && state.searchDone && error == null) {
            TextButton(onClick = onSearch) { Text("重新搜索") }
          }
        }
      }

      else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(rows, key = { it.Id ?: it.Name.orEmpty() }) { media ->
          MediaCandidateRow(
            media = media,
            posterUrl = posterUrl(media),
            onClick = { onPick(media) },
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
        item(key = "picker_footer") { Spacer(Modifier.height(24.dp)) }
      }
    }
  }
}

/**
 * 一行候选影片：封面 + 片名 + 年份/类型/进度 + 「选择」。
 *
 * 「选择此片」比「选它」明确：用户点下去会关掉面板、把它设成目标影片，
 * 而不是「选中高亮」。
 */
@Composable
private fun MediaCandidateRow(
  media: EmbyItem,
  posterUrl: String?,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp, horizontal = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(width = 44.dp, height = 62.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      EmbyImage(
        url = posterUrl,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        maxWidth = 240,
      )
    }
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = media.Name.orEmpty().ifBlank { "未命名" },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildList {
          media.ProductionYear?.let { add(it.toString()) }
          media.Type?.let { add(it) }
          val progress = media.UserData?.PlayedPercentage
          if (progress != null && progress > 0) {
            add(String.format(Locale.US, "已看 %.0f%%", progress))
          }
        }.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(6.dp))
    Text(
      text = "选择",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

// ════════════════════════════ 已下载字幕 ════════════════════════════

/**
 * 本地库概况：条数 / 影片数 / 占用空间 / 未关联的残留文件。
 *
 * 只有「未关联的文件」需要用户动手 —— 它们不在列表里、也不会自己消失，
 * 除了点这里清理没有第二个入口。
 */
@Composable
private fun LibraryStatsCard(
  recordCount: Int,
  mediaCount: Int,
  totalBytes: Long,
  orphanCount: Int,
  orphanBytes: Long,
  cleaning: Boolean,
  onPurge: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "$recordCount 条 · $mediaCount 部影片 · " +
            (if (totalBytes > 0) formatSize(totalBytes) else "0 B"),
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
        )
        if (orphanCount > 0) {
          TextButton(onClick = onPurge, enabled = !cleaning) {
            Text(
              text = if (cleaning) "清理中…" else "清理残留",
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
      Text(
        text =
          if (orphanCount > 0) {
            "另有 $orphanCount 个未关联任何影片的字幕文件（${formatSize(orphanBytes)}），不显示在下方列表中。"
          } else {
            "全部字幕都已关联到影片，没有多余占用。"
          },
        style = MaterialTheme.typography.labelSmall,
        color =
          if (orphanCount > 0) MaterialTheme.colorScheme.tertiary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
      )
    }
  }
}

/** 分组标题：片名 + 该片已下载条数 + 合计大小。 */
@Composable
private fun MediaGroupHeader(
  title: String,
  count: Int,
  totalBytes: Long,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Outlined.Movie,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(15.dp),
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    Text(
      text = "$count 条 · ${if (totalBytes > 0) formatSize(totalBytes) else "0 B"}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 一条已下载字幕：信息 + 动作 chips + 时间轴校准。
 *
 * ## 为什么用 chips 而不是排成一行的 TextButton
 *
 * 旧版把「预览 / 使用 / 信息」塞在一行、还给了固定 30dp 高度 —— 结果既**语义不清**
 * （「使用」到底是什么？），又**显示不全**（30dp 装不下 Material3 按钮的默认内边距，
 * 文字被裁掉一截）。现在改成 [FlowRow] + 芯片：文字按完整长度排版，一行放不下就换行，
 * 每个动作都是完整的中文短语，不用猜。
 *
 * 偏移仍然是固定档（±100 / ±500）：校准是「边看边拧」的动作，滑动条反而对不准。
 */
@Composable
private fun LibraryCard(
  record: DownloadedSubtitle,
  previewing: Boolean,
  timelineActive: Boolean,
  redownloading: Boolean,
  redownloadProgress: Float?,
  onPreview: () -> Unit,
  onUse: () -> Unit,
  onTimeline: () -> Unit,
  onInfo: () -> Unit,
  onRedownload: () -> Unit,
  onMinus500: () -> Unit,
  onMinus100: () -> Unit,
  onPlus100: () -> Unit,
  onPlus500: () -> Unit,
  onExact: () -> Unit,
  onReset: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color =
      if (previewing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
      else MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(width = 40.dp, height = 26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = record.format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = record.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = buildList {
              add(record.source.label)
              record.language?.let { add(OnlineSubtitle.languageLabel(it)) }
              add(formatSize(record.sizeBytes))
              add(DateUtils.getRelativeTimeSpanString(record.downloadedAt).toString())
            }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (record.preferred) {
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Text(
              text = "默认字幕",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            )
          }
        }
      }

      Text(
        text = record.mediaTitle.ifBlank { record.itemId },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 3.dp),
      )

      if (redownloading) {
        Spacer(Modifier.height(8.dp))
        if (redownloadProgress != null) {
          LinearProgressIndicator(
            progress = { redownloadProgress },
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
          text = redownloadProgress?.let { "正在重新下载 ${(it * 100).toInt()}%" } ?: "正在重新下载…",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 3.dp),
        )
      }

      // ── 动作区（语义完整、可换行，不会被截断）──
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        AssistChip(
          onClick = onPreview,
          label = { Text(if (previewing) "正在试看" else "试看") },
          leadingIcon = {
            Icon(
              imageVector = if (previewing) Icons.Default.CheckCircle else Icons.Default.PlayCircleOutline,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
        )
        AssistChip(
          onClick = onTimeline,
          label = { Text(if (timelineActive) "时间线（已载入）" else "打开时间线") },
          leadingIcon = {
            Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
          },
        )
        AssistChip(
          onClick = onUse,
          label = { Text(if (record.preferred) "已是默认" else "设为默认") },
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
        )
        AssistChip(
          onClick = onInfo,
          label = { Text("文件信息") },
          leadingIcon = {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
          },
        )
        AssistChip(
          onClick = onRedownload,
          enabled = !redownloading && record.originUrl.isNotBlank(),
          label = { Text("重新下载") },
          leadingIcon = {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
          },
        )
        AssistChip(
          onClick = onDelete,
          label = { Text("删除", color = MaterialTheme.colorScheme.error) },
          leadingIcon = {
            Icon(
              Icons.Default.Delete,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(16.dp),
            )
          },
        )
      }

      Text(
        text = "「试看」会在上方预览窗口中加载所选字幕；「设为默认」将在下次播放该媒体时自动挂载。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )

      HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(vertical = 8.dp),
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "时间轴偏移",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = formatOffset(record.offsetMs),
          style = MaterialTheme.typography.labelLarge,
          color =
            if (record.offsetMs == 0L) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        if (record.offsetMs != 0L) {
          TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 32.dp)) {
            Text("归零", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OffsetButton("-500", onMinus500, Modifier.weight(1f))
        OffsetButton("-100", onMinus100, Modifier.weight(1f))
        OffsetButton("+100", onPlus100, Modifier.weight(1f))
        OffsetButton("+500", onPlus500, Modifier.weight(1f))
        OffsetButton("精确", onExact, Modifier.weight(1f))
      }
      Text(
        text = "字幕整体偏晚时选择「-」，偏早时选择「+」；试看过程中画面会实时同步。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
private fun OffsetButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      softWrap = false,
    )
  }
}

@Composable
private fun HelpFooter() {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "使用步骤",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
      )
      val steps =
        listOf(
          "1. 选择目标影片，使用「搜索并下载字幕」（迅雷 / SubtitleCat 同时检索）",
          "2. 下载完成后使用「试看」，上方预览窗口会加载该字幕",
          "3. 打开「时间线」选择某一条字幕，画面会跳转到对应时间点；时间不匹配时使用 ±100 / ±500 微调",
        )
      steps.forEach {
        Text(
          text = it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 3.dp),
        )
      }
      Text(
        text = "下载的字幕保存在应用私有目录；若在「设置 → 字幕」中配置了字幕保存目录，" +
          "会额外另存一份，供播放页与本地扫描直接使用。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

// ════════════════════════════ 时间线 ════════════════════════════

/**
 * 字幕时间线面板。
 *
 * ## 为什么是「底部抽屉 + 透明遮罩」
 *
 * 校准要**边看画面边定位**：点一句台词，上方视频立刻跳过去。所以这个面板不能
 * 全屏盖住画面 —— 高度取 62%，遮罩透明，视频区（页面顶部）仍然看得见。
 *
 * ## 单一滚动容器
 *
 * 整个面板只有一个 [LazyColumn]（表头也不放进列表，直接固定在顶部），不嵌套滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSheet(
  record: DownloadedSubtitle,
  cues: List<SubtitleCue>,
  loading: Boolean,
  error: String?,
  truncated: Boolean,
  /** 当前播放位置（秒）；用于高亮「此刻正在播的那一句」。 */
  playheadSeconds: Double,
  /** 播放位置是否可信（预览没开时为 false，不做跟随）。 */
  previewing: Boolean,
  onSeek: (SubtitleCue) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var filter by remember { mutableStateOf("") }
  var follow by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()

  // 「收起」不能直接把面板从组合里摘掉：那样 ModalBottomSheet 一帧内消失，没有任何过渡。
  // 走 sheetState.hide() 让 M3 播完它自己的收起动画（下移 + 遮罩淡出），动画结束再关状态。
  val closeAnimated: () -> Unit = {
    scope
      .launch { runCatching { sheetState.hide() } }
      .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
  }

  val visible =
    remember(cues, filter) {
      val key = filter.trim()
      if (key.isBlank()) cues else cues.filter { it.text.contains(key, ignoreCase = true) }
    }

  // 字幕时间轴是**文件时间**：偏移 +100ms 表示整条字幕延后 100ms，
  // 那么画面播到 P 秒时，正在显示的其实是文件时间 P - offset 处的那一句。
  val activeIndex =
    remember(visible, playheadSeconds, previewing, record.offsetMs) {
      if (!previewing || visible.isEmpty()) {
        -1
      } else {
        val fileTimeMs = (playheadSeconds * 1000.0).toLong() - record.offsetMs
        visible.indexOfFirst { fileTimeMs >= it.startMs && fileTimeMs < it.endMs }
      }
    }

  // 跟随播放：只在「正在预览 + 开着跟随 + 用户没在手动滑」时滚动。
  // 三者缺一都会变成「我正翻列表，它自己往下跳」。
  LaunchedEffect(activeIndex, follow) {
    if (!follow || activeIndex < 0) return@LaunchedEffect
    if (listState.isScrollInProgress) return@LaunchedEffect
    val first = listState.firstVisibleItemIndex
    val last = first + listState.layoutInfo.visibleItemsInfo.size - 1
    // 已经在视野内就不动，避免每句台词都把列表居中一次（画面会抖）
    if (activeIndex < first || activeIndex > last - 1) {
      listState.animateScrollToItem(activeIndex)
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    scrimColor = Color.Transparent,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.62f)
        .padding(horizontal = 16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "字幕时间线",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = buildString {
              append(record.title)
              if (cues.isNotEmpty()) append(" · 共 ${cues.size} 条")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (previewing && cues.isNotEmpty()) {
          // 跟随开关：默认开，用户往下翻看别的段落时点一下即可停止自动滚动
          TextButton(onClick = { follow = !follow }) {
            Icon(
              if (follow) Icons.Filled.PauseCircle else Icons.Filled.PlayCircleOutline,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (follow) "跟随中" else "已暂停跟随", style = MaterialTheme.typography.labelMedium)
          }
        }
        TextButton(onClick = closeAnimated) { Text("收起") }
      }

      // 条目超上限时解析层会截断；不提示的话用户会以为字幕只到那句话为止。
      if (truncated) {
        Text(
          text = "该字幕条目数超过 ${SubtitleTimeline.MAX_CUES} 条，此处仅列出前 ${SubtitleTimeline.MAX_CUES} 条",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (cues.isNotEmpty()) {
        OutlinedTextField(
          value = filter,
          onValueChange = { filter = it },
          singleLine = true,
          label = { Text("在台词中查找") },
          leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        LaunchedEffect(filter) {
          if (filter.isBlank()) listState.animateScrollToItem(0)
        }
      }

      when {
        loading -> Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
              text = "正在解析字幕时间轴…",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 6.dp),
            )
          }
        }

        error != null -> Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        visible.isEmpty() -> Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "未找到匹配「$filter」的台词",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        else -> LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
          itemsIndexed(visible) { index, cue ->
            CueRow(
              index = index + 1,
              cue = cue,
              active = previewing && index == activeIndex,
              onClick = { onSeek(cue) },
            )
          }
        }
      }
    }
  }
}

/**
 * 一句台词：序号 + 起止时间 + 正文。
 *
 * 点击即让预览跳到这句的起点 —— 这是「时间轴对不上」时唯一的实用定位手段。
 *
 * [active] 表示画面此刻正在播这一句：给底色 + 左侧竖条。只靠改字色不够显眼，
 * 而「哪一句正在播」正是校准偏移时要盯的那个参照点。
 */
@Composable
private fun CueRow(
  index: Int,
  cue: SubtitleCue,
  active: Boolean = false,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
      )
      .clickable(onClick = onClick)
      .padding(vertical = 7.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.Top,
  ) {
    // 左侧竖条：告诉用户「这一条正在播」，同时不改变行高（避免列表跳动）
    Box(
      modifier = Modifier
        .padding(top = 1.dp)
        .width(3.dp)
        .heightIn(min = 28.dp)
        .clip(RoundedCornerShape(999.dp))
        .background(
          if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        ),
    )
    Spacer(Modifier.width(5.dp))
    Text(
      text = "#$index",
      style = MaterialTheme.typography.labelSmall,
      color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
      // 以前写死 34dp：四位数序号（#1234）在那个宽度里会被 Compose 折成两行或直接裁掉。
      // 改成「最小 34dp、能撑就撑」，对齐基线保持不变，序号再长也完整显示。
      maxLines = 1,
      modifier = Modifier.widthIn(min = 34.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = cue.timeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = cue.text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 1.dp),
      )
    }
    Spacer(Modifier.width(6.dp))
    Icon(
      Icons.Default.PlayCircleOutline,
      contentDescription = "跳转到该条字幕",
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
  }
}

// ════════════════════════════ 工具 ════════════════════════════

@Composable
private fun ExactOffsetDialog(
  record: DownloadedSubtitle,
  onDismiss: () -> Unit,
  onConfirm: (Long) -> Unit,
) {
  var text by remember { mutableStateOf(record.offsetMs.toString()) }
  val parsed = text.trim().toLongOrNull()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("精确校准偏移") },
    text = {
      Column {
        Text(
          text = "输入偏移毫秒数。正数 = 字幕整体延后，负数 = 提前。范围 -60000 ~ 60000。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          singleLine = true,
          label = { Text("毫秒") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (parsed == null) {
          Text(
            text = "请输入整数",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { parsed?.let(onConfirm) },
        enabled = parsed != null,
      ) { Text("保存") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

private fun formatOffset(ms: Long): String =
  if (ms == 0L) "未校准" else String.format(Locale.US, "%+d ms", ms)

private fun formatSize(bytes: Long): String =
  when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
  }

/**
 * 已下载字幕的关键词匹配（纯函数，便于单独验证）。
 *
 * 覆盖用户在列表里能看到的全部可辨识信息：片名（`mediaTitle`）、字幕标题、
 * 实际文件名、来源站名、语言与格式。语言走 [OnlineSubtitle.languageLabel]，
 * 这样输入「简体」也能命中 `zh-CN` 这类代码。
 *
 * 多个关键词按空格拆分，**全部命中**才算匹配 —— 与「片名 + 语言」这种收窄式检索一致。
 */
private fun DownloadedSubtitle.matchesQuery(rawQuery: String): Boolean {
  val tokens =
    rawQuery
      .trim()
      .lowercase(Locale.getDefault())
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() }
  if (tokens.isEmpty()) return true
  val haystack =
    buildString {
      append(mediaTitle).append('\n')
      append(title).append('\n')
      append(fileName).append('\n')
      append(format).append('\n')
      append(source.label).append('\n')
      language?.let { append(OnlineSubtitle.languageLabel(it)) }
    }.lowercase(Locale.getDefault())
  return tokens.all { haystack.contains(it) }
}
