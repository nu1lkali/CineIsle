package app.marlboroadvance.mpvex.ui.browser.emby.smartmerge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_IGNORED
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_MERGED
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeCandidate
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage

/**
 * 候选详情：全屏页式对话框。
 *
 * 之前用 AlertDialog，2~3 个条目就把屏幕撑满，路径/ProviderIds/版本信息全被截断。
 * 这里改成整页：顶部固定标题，中间每条候选占一张完整卡片，底部固定「忽略 / 预览合并 /
 * 直接合并」。直接合并也走独立的确认弹窗，不会一点就执行。
 */
@Composable
internal fun SmartMergeCandidateDetailDialog(
  candidate: SmartMergeCandidate,
  imageUrl: (SmartMergeItem) -> String?,
  onPreviewPlay: (SmartMergeItem) -> Unit,
  onDismiss: () -> Unit,
  onIgnore: () -> Unit,
  onPreview: (String?) -> Unit,
  onDirectMerge: (String?) -> Unit,
  onRemoveItem: (String) -> Unit,
) {
  var primaryId by remember(candidate.id) {
    mutableStateOf(candidate.suggestedPrimaryId ?: candidate.itemIds.firstOrNull())
  }
  val terminal = candidate.status == STATUS_MERGED || candidate.status == STATUS_IGNORED

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.systemBars)
          .imePadding(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              candidate.title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              "${candidate.code} · 置信度 ${candidate.confidence} · ${candidate.sourceSummary}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
          }
        }
        if (candidate.conflicts.isNotEmpty()) {
          Text(
            "冲突：" + candidate.conflicts.joinToString("；"),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        LazyColumn(
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            Text(
              "选择一个推荐主项目。Emby MergeVersions 没有主项目参数，最终元数据归属由服务端决定。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          items(candidate.items, key = { it.id }) { item ->
            CandidateItemCard(
              item = item,
              imageUrl = imageUrl(item),
              selectedAsPrimary = primaryId == item.id,
              onSelectPrimary = { primaryId = item.id },
              onPreviewPlay = { onPreviewPlay(item) },
              onRemove = { onRemoveItem(item.id) },
            )
          }
        }

        HorizontalDivider()
        Surface(tonalElevation = 3.dp) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (terminal) {
              Text(
                "该候选已处理，不能再合并。",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
              TextButton(onClick = onIgnore) { Text("忽略") }
              OutlinedButton(
                onClick = { onPreview(primaryId) },
                modifier = Modifier.weight(1f),
              ) { Text("预览合并") }
              Button(
                onClick = { onDirectMerge(primaryId) },
                modifier = Modifier.weight(1f),
              ) { Text("直接合并") }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CandidateItemCard(
  item: SmartMergeItem,
  imageUrl: String?,
  selectedAsPrimary: Boolean,
  onSelectPrimary: () -> Unit,
  onPreviewPlay: () -> Unit,
  onRemove: () -> Unit,
) {
  val source = item.mediaSources.firstOrNull()
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 84.dp, height = 118.dp).clip(RoundedCornerShape(8.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = selectedAsPrimary, onClick = onSelectPrimary)
          Text(
            if (selectedAsPrimary) "主项目（推荐）" else "设为推荐主项目",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          IconButton(onClick = onPreviewPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "小窗预览")
          }
          TextButton(onClick = onRemove) {
            Text("移除", style = MaterialTheme.typography.labelSmall)
          }
        }
        Text(
          item.displayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          (item.code?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + item.version.label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          listOfNotNull(
            item.source,
            source?.height?.takeIf { it > 0 }?.let { "${it}P" },
            source?.videoCodec?.takeIf { it.isNotBlank() }?.uppercase(),
            source?.container?.takeIf { it.isNotBlank() }?.uppercase(),
            item.runtimeTicks?.let { formatTicks(it) },
          ).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (item.mediaSources.size > 1) {
          Text(
            "包含 ${item.mediaSources.size} 个媒体源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
        if (item.providerIds.isNotEmpty()) {
          Text(
            item.providerIds.entries.take(5)
              .joinToString(" · ") { "${it.key}=${it.value}" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        item.path?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          "ItemId: ${item.id}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** 直接合并的二次确认（不展示完整预览页，但仍列出参与条目）。 */
@Composable
internal fun SmartMergeDirectMergeDialog(
  candidate: SmartMergeCandidate,
  primaryId: String?,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("确认直接合并") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("将 ${candidate.itemIds.size} 个条目组合为一个 Emby 多版本项目：")
        Text(
          candidate.items.joinToString("\n") { "· ${it.displayTitle}" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "推荐主项目：" + (candidate.items.firstOrNull { it.id == primaryId }?.displayTitle ?: "未选择"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
        if (candidate.conflicts.isNotEmpty()) {
          Text(
            "冲突：" + candidate.conflicts.joinToString("；"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        Text(
          "不会主动删除文件；但 Emby 可能只保留一个 ItemId，收藏/进度/元数据归属由服务端决定。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    },
    confirmButton = { Button(onClick = onConfirm) { Text("确认合并") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

private fun formatTicks(ticks: Long): String {
  val totalSeconds = ticks / 10_000_000L
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
