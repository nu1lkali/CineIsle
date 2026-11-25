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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * 「待处理清单」：从多次手动搜索里累积的待组合条目。
 *
 * 这里不执行合并，只负责生成候选。生成后回到「待处理」候选列表，仍要用户
 * 打开候选详情、确认版本归属后再点合并 —— 与本功能「永不自动合并」的边界一致。
 */
@Composable
internal fun SmartMergeTrayDialog(
  items: List<SmartMergeItem>,
  imageUrl: (SmartMergeItem) -> String?,
  onRemove: (String) -> Unit,
  onClear: () -> Unit,
  onCombine: () -> Unit,
  onDismiss: () -> Unit,
  errorMessage: String? = null,
) {
  val codeBodies = items.mapNotNull { it.codeBody?.takeIf { body -> body.isNotBlank() } }.distinct()
  val codeLabels = items.mapNotNull { it.code?.takeIf { code -> code.isNotBlank() } }.distinct()

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
              "待处理清单",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              "已加入 ${items.size} 项 · 至少 2 项才能组合为候选",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
          }
        }

        if (codeBodies.size > 1) {
          Text(
            "清单包含不同番号（${codeLabels.joinToString(" / ")}）。" +
              "组合后这些条目将成为同一作品的多个版本。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))

        if (items.isEmpty()) {
          Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text("清单是空的", style = MaterialTheme.typography.titleMedium)
            Text(
              "回到「手动匹配」，搜索 A / B / C 后逐个点「加入待处理」。",
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
            items(items, key = { it.id }) { item ->
              TrayItemCard(
                item = item,
                imageUrl = imageUrl(item),
                onRemove = { onRemove(item.id) },
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
            Text(
              "组合只生成候选，不会立刻调用 Emby；确认候选详情后才会真正合并。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              OutlinedButton(
                onClick = onClear,
                enabled = items.isNotEmpty(),
              ) { Text("清空") }
              Button(
                onClick = onCombine,
                enabled = items.size >= 2,
                modifier = Modifier.weight(1f),
              ) { Text("组合为候选（${items.size}）") }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TrayItemCard(
  item: SmartMergeItem,
  imageUrl: String?,
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
        modifier = Modifier.size(width = 62.dp, height = 88.dp).clip(RoundedCornerShape(8.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          item.displayTitle,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          (item.code?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + item.version.label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          listOfNotNull(
            source?.height?.takeIf { it > 0 }?.let { "${it}P" },
            source?.videoCodec?.takeIf { it.isNotBlank() }?.uppercase(),
            source?.container?.takeIf { it.isNotBlank() }?.uppercase(),
            item.runtimeTicks?.let { formatTrayTicks(it) },
          ).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        item.path?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
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
      IconButton(onClick = onRemove) {
        Icon(
          Icons.Filled.Delete,
          contentDescription = "从待处理清单移除",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

private fun formatTrayTicks(ticks: Long): String {
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
