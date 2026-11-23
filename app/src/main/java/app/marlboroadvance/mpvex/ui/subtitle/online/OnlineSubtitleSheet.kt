package app.marlboroadvance.mpvex.ui.subtitle.online

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleSource
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleTarget
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleMatchLevel
import kotlinx.coroutines.launch

/**
 * 网络字幕（迅雷 / SubtitleCat）搜索面板 —— 三个入口共用。
 *
 * ## 与既有「在线字幕」（Emby 字幕源）的区别
 *
 * 那个面板走 `POST /Items/{Id}/RemoteSearch/Subtitles`，由**服务端**提供方检索、
 * 入库成服务端字幕轨；这个面板直接连互联网站点，下载的是**客户端本地文件**。
 * 两者互不影响，也**不会**互相伪装（需求 1.12）。
 *
 * ## 为什么整页只有一个 LazyColumn
 *
 * 面板内容 = 表头（关键词 / 来源 / 按钮）+ 搜索结果 + 已下载列表，三块都可能很长。
 * 外层再套一个可滚动容器就会「两个 LazyColumn 抢高度」（本项目红线：绝不两层
 * verticalScroll）。所以表头作为第一个 item 一起滚，列表本身就是唯一滚动容器。
 *
 * @param target 媒体上下文（服务器 / ItemId / 用于匹配的标题）
 * @param primaryActionLabel 主按钮文案。播放页是「下载并挂载」，详情页是「下载」
 * @param onUse 下载完成后要做的事（播放页：挂载；详情页：关联）。null = 只下载
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleSheet(
  target: OnlineSubtitleTarget,
  onDismiss: () -> Unit,
  primaryActionLabel: String = "下载并挂载",
  onUse: ((Uri) -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val focus = LocalFocusManager.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val vm: OnlineSubtitleViewModel =
    viewModel(factory = OnlineSubtitleViewModel.factory(context.applicationContext as Application))
  val state by vm.state.collectAsState()

  // 目标变化（换片 / 换集）⇒ 重新预填关键词并刷新本地库
  LaunchedEffect(target.serverKey, target.itemId, target.mediaTitle) { vm.bindTarget(target) }

  var confirmDelete by remember { mutableStateOf<DownloadedSubtitle?>(null) }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    LazyColumn(
      // 固定高度而不是「内容多高就多高」：搜索结果是分两路先后到达的，内容高度会跳变，
      // 拖拽关闭时每一帧重新测量 ⇒ 手感和位置都会抖（与实验室选片面板同一个问题）。
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      item(key = "header") {
        OnlineSubtitleHeader(
          target = target,
          keyword = state.keyword,
          keywordOrigin = state.keywordOrigin,
          keywordWasFallback = state.keywordWasFallback,
          enabledSources = state.enabledSources,
          searching = state.searching,
          failure = state.failure,
          notice = state.notice,
          onKeywordChange = vm::setKeyword,
          onToggleSource = vm::toggleSource,
          onSearch = {
            focus.clearFocus()
            vm.search()
          },
          onCancelSearch = vm::cancelSearch,
        )
      }

      when {
        state.searching && state.items.isEmpty() ->
          item(key = "loading") {
            Box(
              modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "正在从各来源检索…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

        !state.searched ->
          item(key = "hint") { Hint("填入番号后点「搜索」。两个来源会同时检索。") }

        state.items.isEmpty() ->
          item(key = "empty") {
            Hint(if (state.failure != null) "没有可用结果" else "没找到字幕，换个番号或片名再试")
          }

        else ->
          items(state.items, key = { it.id }) { item ->
            val download = state.downloads[item.id]
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
              SubtitleResultRow(
                item = item,
                downloaded = item.id in state.downloadedOriginIds,
                download = download,
                primaryActionLabel = primaryActionLabel,
                onPrimary = {
                  scope.launch {
                    val uri = vm.download(item)
                    // onUse 为 null（详情页「仅下载」）时不回调，用户可继续下别的
                    if (uri != null) onUse?.invoke(uri)
                  }
                },
                onPreviewTimeline = { vm.previewTimeline(item) },
                onCancel = { vm.cancelDownload(item.id) },
                onRetry = { vm.retry(item) },
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
          }
      }

      // ── 已下载（本地库，跨入口共享）──
      if (state.downloaded.isNotEmpty()) {
        item(key = "library_header") {
          Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text(
              text = "已下载 ${state.downloaded.size} 条",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
          }
        }
        items(state.downloaded, key = { "dl_${it.id}" }) { record ->
          Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DownloadedRow(
              record = record,
              onUse = {
                val uri = vm.uriOf(record)
                if (uri != null) onUse?.invoke(uri)
              },
              onDelete = { confirmDelete = record },
              useEnabled = onUse != null,
              onPreviewTimeline = { vm.previewTimeline(record) },
            )
          }
        }
      }
    }
  }

  confirmDelete?.let { record ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("删除该字幕？") },
      text = {
        Text(
          "将从本机删除「${record.title}」的文件与关联记录，不影响 Emby 服务器上的字幕。" +
            "此操作不可撤销。",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            vm.delete(record)
            confirmDelete = null
          },
        ) {
          Text(text = "删除", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = null }) { Text("取消") }
      },
    )
  }

  // ── 时间线预览（叠在抽屉之上的只读浮层，不下载、不落盘）──
  state.timelinePreview?.let { preview ->
    OnlineSubtitleTimelineDialog(
      preview = preview,
      onClose = vm::closeTimelinePreview,
      onRetry = {
        val item = state.items.firstOrNull { it.id == preview.originId }
        if (item != null) vm.previewTimeline(item) else vm.closeTimelinePreview()
      },
    )
  }
}

/** 面板表头：标题、关键词输入、来源开关、操作按钮、状态提示。 */
@Composable
private fun OnlineSubtitleHeader(
  target: OnlineSubtitleTarget,
  keyword: String,
  keywordOrigin: String,
  keywordWasFallback: Boolean,
  enabledSources: Set<OnlineSubtitleSource>,
  searching: Boolean,
  failure: String?,
  notice: String?,
  onKeywordChange: (String) -> Unit,
  onToggleSource: (OnlineSubtitleSource) -> Unit,
  onSearch: () -> Unit,
  onCancelSearch: () -> Unit,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    Text(
      text = "网络字幕",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = target.mediaTitle.ifBlank { target.embyTitle.orEmpty() }.ifBlank { "未命名媒体" },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp),
    )

    Spacer(Modifier.height(10.dp))

    // 预填的是自动提取的编号（如 `n0907`），不是整串文件名 —— 站点只认编号。
    OutlinedTextField(
      value = keyword,
      onValueChange = onKeywordChange,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("番号或片名") },
      supportingText = {
        if (keywordOrigin.isNotBlank() && keywordOrigin != "手动输入") {
          Text(
            text = "自动提取：$keywordOrigin" + if (keywordWasFallback) "（未识别到编号）" else "",
            style = MaterialTheme.typography.labelSmall,
          )
        }
      },
      trailingIcon = {
        IconButton(onClick = onSearch, enabled = !searching) {
          Icon(Icons.Default.Search, contentDescription = "搜索")
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(top = 4.dp),
    ) {
      OnlineSubtitleSource.entries.forEach { source ->
        FilterChip(
          selected = source in enabledSources,
          onClick = { onToggleSource(source) },
          label = { Text(source.label) },
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = onSearch,
        enabled = enabledSources.isNotEmpty() && !searching,
        modifier = Modifier.weight(1f),
      ) {
        if (searching) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("搜索中…")
        } else {
          Text("搜索")
        }
      }
      if (searching) {
        OutlinedButton(onClick = onCancelSearch) { Text("取消") }
      }
    }

    failure?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
    notice?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }

    Spacer(Modifier.height(6.dp))
  }
}

@Composable
private fun Hint(text: String) {
  Box(
    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 一条搜索结果。
 *
 * 信息层级：**标题（用户靠它认版本）> 来源/格式/语言 > 匹配等级与依据**。
 * 匹配等级不只给分，还跟着一句「编号完全一致」这类依据 —— 需求 3.2 明确
 * 不要一个没有解释的分数。
 */
@Composable
private fun SubtitleResultRow(
  item: OnlineSubtitle,
  downloaded: Boolean,
  download: OnlineSubtitleViewModel.DownloadState?,
  primaryActionLabel: String,
  onPrimary: () -> Unit,
  onPreviewTimeline: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Text(
      text = item.title,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.padding(top = 3.dp),
    ) {
      MatchBadge(item.matchLevel)
      item.matchReason?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
    }

    // 来源 / 格式 / 语言：每项单独一个包裹框，和上面的匹配等级同一种视觉口径。
    // 原来拼成「迅雷 · SRT · 中文（简体）」一整串，窄屏下会被截掉后半段，
    // 而用户恰恰要靠「语言」决定下哪条。
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
      modifier = Modifier.padding(top = 3.dp),
    ) {
      InfoChip(item.source.label)
      InfoChip(item.format?.uppercase() ?: "格式未知")
      InfoChip(
        item.language?.takeIf { it.isNotBlank() }?.let { OnlineSubtitle.languageLabel(it) }
          ?: "语言未知",
      )
    }

    val progress = download?.progress
    when {
      download != null && download.error == null -> {
        if (progress != null) {
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = progress?.let { "${(it * 100).toInt()}%" } ?: "下载中…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = onCancel) { Text("取消") }
        }
      }

      download?.error != null -> {
        Text(
          text = download.error,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onRetry) { Text("重试") }
        }
      }

      downloaded -> {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TimelineButton(onClick = onPreviewTimeline)
          Spacer(Modifier.weight(1f))
          Text(
            text = "已在本地库",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.width(6.dp))
          TextButton(onClick = onPrimary) { Text("再次使用") }
        }
      }

      else -> {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 先看后下：不必为了一条不确定的字幕先占本地库
          TimelineButton(onClick = onPreviewTimeline)
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onPrimary) {
            Icon(
              Icons.Default.CloudDownload,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(primaryActionLabel)
          }
        }
      }
    }
  }
}

/** 搜索结果里的「时间线」入口：先看内容，再决定要不要下载。 */
@Composable
private fun TimelineButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) {
    Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text("时间线")
  }
}

/** 搜索结果里的一枚中性信息徽章（来源 / 格式 / 语言）：与匹配等级同一种包裹样式。 */
@Composable
private fun InfoChip(text: String) {
  Surface(
    shape = RoundedCornerShape(5.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun MatchBadge(level: SubtitleMatchLevel) {
  // UNKNOWN 不画：没有比较对象时给个「未评估」徽章反而让人以为系统看过却没结论
  if (level == SubtitleMatchLevel.UNKNOWN) return
  val emphasized = level.strong
  Surface(
    shape = RoundedCornerShape(5.dp),
    color =
      if (emphasized) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor =
      if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
      else MaterialTheme.colorScheme.onSurfaceVariant,
  ) {
    Text(
      text = level.label,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}

/** 本地库里的一行：格式块 + 标题 + 来源/语言/偏移。 */
@Composable
internal fun DownloadedRow(
  record: DownloadedSubtitle,
  onUse: () -> Unit,
  onDelete: () -> Unit,
  useEnabled: Boolean,
  /** 打开该条已下载字幕的时间线（读本地文件，零请求）。null = 不显示入口。 */
  onPreviewTimeline: (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
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
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = record.title,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildList {
          add(record.source.label)
          record.language?.let { add(OnlineSubtitle.languageLabel(it)) }
          if (record.offsetMs != 0L) add("偏移 ${record.offsetMs}ms")
        }.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (onPreviewTimeline != null) {
      // 已下载的字幕同样能核对待内容：读本地文件，不发请求
      TextButton(onClick = onPreviewTimeline) { Text("时间线") }
    }
    if (useEnabled) {
      TextButton(onClick = onUse) { Text("使用") }
    }
    IconButton(onClick = onDelete) {
      Icon(
        Icons.Default.Delete,
        contentDescription = "删除",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
