package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitlePresentation
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleRowModel
import app.marlboroadvance.mpvex.ui.browser.emby.DetailSubtitleUi

/**
 * 「全部字幕」抽屉：当前媒体源上服务端识别到的每一条字幕。
 *
 * ## 为什么是抽屉而不是在页面里展开
 *
 * 一条片子的字幕可能有十几条（多语言 + 听障 + 强制）。铺在详情页里会把
 * 「简介 / 演职员 / 媒体信息」全推走，而用户多数时候只关心「有没有中文字幕」。
 *
 * ## 只读
 *
 * 点列表项**不切换字幕**：详情页没有挂载字幕的能力，能做的只有在播放页选。
 * 这一句必须写在抽屉底部 —— 否则用户点了一下没反应，会以为是坏了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailSubtitleSheet(
  ui: DetailSubtitleUi,
  itemTitle: String?,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  val rows = remember(ui.subtitles, ui.pickedKey) {
    SubtitlePresentation.rowsOf(ui.subtitles, ui.pickedKey)
  }
  // 不可用行点开看原因（烧录 / 图像字幕 / 格式不支持），不再另开页面
  var reasonRow by remember { mutableStateOf<SubtitleRowModel?>(null) }

  val contextLine = listOfNotNull(
    itemTitle?.takeIf { it.isNotBlank() },
    ui.versionLabel?.takeIf { it.isNotBlank() },
    ui.targetLabel?.takeIf { it.isNotBlank() },
  ).joinToString(" · ")

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Text(
          text = "全部字幕 · ${ui.subtitles.size} 条",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        if (contextLine.isNotBlank()) {
          Text(
            text = contextLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      HorizontalDivider()

      LazyColumn(
        modifier = Modifier.heightIn(max = 460.dp),
        // 底部浮层（导航条）由系统占位，列表留出内边距；不给最后一行贴边
        contentPadding = PaddingValues(bottom = 8.dp),
      ) {
        items(rows, key = { it.key }) { row ->
          SubtitleDetailRow(
            row = row,
            isPicked = row.key == ui.pickedKey,
            onClick = { if (!row.available) reasonRow = row },
          )
          HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
          )
        }
      }

      Text(
        text = "字幕切换在播放页完成 · 本页仅展示服务端已识别字幕",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp),
      )
    }
  }

  reasonRow?.let { row ->
    AlertDialog(
      onDismissRequest = { reasonRow = null },
      title = { Text(text = row.title, style = MaterialTheme.typography.titleMedium) },
      text = {
        Text(
          text = row.unavailableReason ?: "这条字幕无法交给播放器，具体原因未知",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        TextButton(onClick = { reasonRow = null }) { Text("知道了") }
      },
    )
  }
}

@Composable
private fun SubtitleDetailRow(
  row: SubtitleRowModel,
  isPicked: Boolean,
  onClick: () -> Unit,
) {
  val titleColor = if (row.available) {
    MaterialTheme.colorScheme.onSurface
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = !row.available, onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(width = 42.dp, height = 30.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(
          if (row.available) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
          },
        ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = row.formatLabel,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (row.available) {
          MaterialTheme.colorScheme.onSecondaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = row.title,
          style = MaterialTheme.typography.bodyLarge,
          color = titleColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (isPicked) RowTag(text = "将使用", highlighted = true)
        row.tags.forEach { tag -> RowTag(text = tag, highlighted = false) }
      }
      row.detail?.let { detail ->
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 1.dp),
        )
      }
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = if (row.available) "${row.streamIndex}" else "无法提取",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun RowTag(text: String, highlighted: Boolean) {
  Surface(
    shape = RoundedCornerShape(5.dp),
    color = if (highlighted) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = if (highlighted) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    },
    border = if (highlighted) {
      BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    } else {
      null
    },
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
    )
  }
}
