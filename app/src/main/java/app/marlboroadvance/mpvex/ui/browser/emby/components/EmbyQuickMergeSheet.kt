package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeMapper
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeMatcher
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeRules

/**
 * 媒体库长按 →「合并同番号」的确认抽屉。
 *
 * 只负责「选哪些、以谁为主」，不直接调用 Emby。确认后仍会走
 * [app.marlboroadvance.mpvex.ui.browser.emby.smartmerge.SmartMergeDirectMergeDialog]
 * 的二次确认，再交给 SmartMergeManager 做快照、MergeVersions 与验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyQuickMergeSheet(
  items: List<EmbyItem>,
  serverId: Long,
  rules: SmartMergeRules,
  imageUrl: (EmbyItem) -> String?,
  onDismiss: () -> Unit,
  onConfirm: (selected: List<EmbyItem>, primaryId: String?) -> Unit,
  initialPrimaryId: String? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedIds by remember(items) {
    mutableStateOf(items.mapNotNull { it.Id }.toSet())
  }
  var primaryId by remember(items, initialPrimaryId) {
    mutableStateOf(
      initialPrimaryId?.takeIf { id -> items.any { it.Id == id } }
        ?: items.firstOrNull { it.Id != null }?.Id,
    )
  }

  val selected = items.filter { item -> item.Id != null && item.Id in selectedIds }
  val projected = remember(selected, rules) {
    selected.mapNotNull(SmartMergeMapper::from)
  }
  val candidate = remember(projected, rules, serverId) {
    if (projected.size >= 2) {
      SmartMergeMatcher.candidateFromSelection(serverId, projected, rules)
    } else {
      null
    }
  }

  LaunchedEffect(selectedIds) {
    if (primaryId == null || primaryId !in selectedIds) {
      primaryId = selected.firstOrNull()?.Id
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
      Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
          "合并同番号",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          "找到 ${items.size} 个条目 · 勾选要合并的版本；至少 2 项",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidate?.let {
          Text(
            "置信度 ${it.confidence} 分 · ${it.sourceSummary.ifBlank { "未知来源" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Spacer(modifier = Modifier.size(8.dp))

      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(items, key = { it.Id ?: it.Name ?: "" }) { item ->
          val id = item.Id
          QuickMergeItemCard(
            item = item,
            imageUrl = imageUrl(item),
            checked = id != null && id in selectedIds,
            isPrimary = id != null && id == primaryId,
            onToggle = {
              if (id != null) {
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
              }
            },
            onSetPrimary = {
              if (id != null) {
                primaryId = id
                if (id !in selectedIds) selectedIds = selectedIds + id
              }
            },
          )
        }
        item {
          val conflicts = candidate?.conflicts.orEmpty()
          if (conflicts.isNotEmpty()) {
            Text(
              "冲突：" + conflicts.joinToString("；"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          } else if (selected.size >= 2) {
            Text(
              "Emby MergeVersions 没有主项目参数；这里选的「推荐主项目」只影响 App 内提示，" +
                "最终元数据归属由服务端决定。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              "至少选择 2 个条目才能合并。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(modifier = Modifier.size(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          "已选 ${selected.size} 项" + if (candidate == null && selected.size >= 2) " · 无法生成候选" else "",
          style = MaterialTheme.typography.bodySmall,
          color = if (candidate == null && selected.size >= 2) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("取消") }
        Button(
          onClick = { onConfirm(selected, primaryId) },
          enabled = selected.size >= 2 && candidate != null,
        ) {
          Text("合并所选（${selected.size}）")
        }
      }
    }
  }
}

@Composable
private fun QuickMergeItemCard(
  item: EmbyItem,
  imageUrl: String?,
  checked: Boolean,
  isPrimary: Boolean,
  onToggle: () -> Unit,
  onSetPrimary: () -> Unit,
) {
  val mapped = remember(item) { SmartMergeMapper.from(item) }
  val source = mapped?.mediaSources?.firstOrNull()
  Card(
    onClick = onToggle,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (checked) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      },
    ),
    border = BorderStroke(
      width = if (checked) 1.5.dp else 1.dp,
      color = if (checked) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
      },
    ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Checkbox(checked = checked, onCheckedChange = { onToggle() })
      EmbyImage(
        url = imageUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          mapped?.displayTitle ?: item.Name.orEmpty(),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          (mapped?.code?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
            (mapped?.version?.label ?: "未知版本"),
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
            source?.size?.takeIf { it > 0 }?.let { formatQuickMergeSize(it) },
          ).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        item.Path?.takeIf { it.isNotBlank() }?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(
            selected = isPrimary,
            onClick = onSetPrimary,
            enabled = checked,
          )
          Text(
            if (isPrimary) "推荐主项目" else "设为主项目",
            style = MaterialTheme.typography.labelSmall,
            color = if (isPrimary) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
      }
    }
  }
}

private fun formatQuickMergeSize(bytes: Long): String {
  val gb = bytes / 1024.0 / 1024.0 / 1024.0
  return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1024.0 / 1024.0)
}
