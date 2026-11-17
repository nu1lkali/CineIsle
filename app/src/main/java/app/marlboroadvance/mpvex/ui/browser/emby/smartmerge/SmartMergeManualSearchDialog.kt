package app.marlboroadvance.mpvex.ui.browser.emby.smartmerge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage

/**
 * 手动匹配：全屏页式对话框。
 *
 * 交互改成「购物车」式：每搜索一条，就把需要的条目**加入待处理清单**；
 * 清单独立于当前搜索结果，所以可以「先搜 A 加入、再搜 B 加入、再搜 C 加入」，
 * 最后一次性组合。顶部搜索词、结果列表可以随便换，清单不会丢。
 */
@Composable
internal fun SmartMergeManualSearchDialog(
  ui: SmartMergeUiState,
  imageUrl: (SmartMergeItem) -> String?,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onToggle: (SmartMergeItem) -> Unit,
  onOpenTray: () -> Unit,
  onCreate: () -> Unit,
  onDismiss: () -> Unit,
  errorMessage: String? = null,
) {
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
          Text(
            "手动匹配",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = ui.manualQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("输入番号 / 标题 / Emby ItemId") },
          )
          Button(onClick = onSearch, enabled = !ui.manualLoading) {
            Text("搜索")
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              if (ui.manualResults.isEmpty()) {
                "输入关键词后在所有媒体库中搜索"
              } else {
                "找到 ${ui.manualResults.size} 个条目 · 待处理 ${ui.tray.size} 项"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.tray.isNotEmpty()) {
              Text(
                "可以继续换关键词搜索，已加入的条目会保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
          if (ui.tray.isNotEmpty()) {
            TextButton(onClick = onOpenTray) { Text("查看清单") }
          }
        }
        HorizontalDivider()

        if (ui.manualLoading) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        if (ui.manualResults.isEmpty()) {
          Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              if (ui.manualLoading) "正在搜索…" else "没有结果",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              "可以尝试完整番号、标题关键词，或直接粘贴 32 位 Emby ItemId。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(ui.manualResults, key = { it.id }) { item ->
              ManualResultCard(
                item = item,
                imageUrl = imageUrl(item),
                inTray = ui.tray.any { it.id == item.id },
                onToggle = { onToggle(item) },
              )
            }
          }
        }

        HorizontalDivider()
        Surface(tonalElevation = 3.dp) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            errorMessage?.let {
              Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                if (ui.tray.isEmpty()) {
                  "待处理清单为空：先把需要的条目加入，至少 2 项才能组合。"
                } else {
                  "待处理 ${ui.tray.size} 项" +
                    if (ui.tray.size < 2) "（还差 ${2 - ui.tray.size} 项）" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
              if (ui.tray.isNotEmpty()) {
                TextButton(onClick = onOpenTray) { Text("清单") }
              }
            }
            Button(
              onClick = onCreate,
              enabled = ui.tray.size >= 2,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("组合为候选（${ui.tray.size}）")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ManualResultCard(
  item: SmartMergeItem,
  imageUrl: String?,
  inTray: Boolean,
  onToggle: () -> Unit,
) {
  // 卡片本身不响应点击：加入待处理是明确的按钮动作，避免滑动列表时误加。
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 68.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          item.displayTitle,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          (item.code?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + item.version.label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val source = item.mediaSources.firstOrNull()
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
            item.providerIds.entries.take(4)
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
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onToggle, enabled = !item.alreadyMultiVersion) {
            Icon(
              imageVector = if (inTray) Icons.Filled.Check else Icons.Filled.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Text(
              when {
                item.alreadyMultiVersion -> "已是多版本"
                inTray -> "已加入待处理"
                else -> "加入待处理"
              },
              modifier = Modifier.padding(start = 4.dp),
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
    }
  }
}

private fun formatTicks(ticks: Long): String {
  val totalSeconds = ticks / 10_000_000L
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return if (h > 0) {
    "%d:%02d:%02d".format(h, m, s)
  } else {
    "%d:%02d".format(m, s)
  }
}
