package app.marlboroadvance.mpvex.ui.browser.emby.aggsearch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateEntry
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerHit
import java.util.Locale

/**
 * 「逐台副本」抽屉：长按合并后的卡片弹出。
 *
 * ## 它解决什么问题
 *
 * 合并后一张卡片代表多台上同一部片，用户必须能回答两个问题：
 * 1. **这台也有吗** —— 逐台列出来；
 * 2. **默认会打开哪一台** —— 首行标「默认」，并说明它由什么规则选出。
 *
 * 每条只写三样：画质、体积、观看进度。这三样是决定「点哪台」的实际依据；
 * 服务器名是分组键，不重复写进描述里。**不推断**「只有这台有中字」之类
 * —— 搜索结果里没有字幕信息，写出来就是编。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregateCopySheet(
  entry: AggregateEntry,
  onDismiss: () -> Unit,
  onOpen: (ServerHit) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = entry.item.Name ?: "未命名",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "该条目在 ${entry.serverCount} 台服务器上可播放，默认打开首行",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.size(10.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

      entry.hits.forEachIndexed { index, hit ->
        CopyRow(
          hit = hit,
          isPrimary = index == 0,
          onClick = { onOpen(hit) },
        )
        if (index != entry.hits.lastIndex) {
          HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
          )
        }
      }

      TextButton(
        onClick = onDismiss,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
      ) {
        Text("关闭")
      }
    }
  }
}

@Composable
private fun CopyRow(
  hit: ServerHit,
  isPrimary: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = hit.serverName,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (isPrimary) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
              .padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = "默认",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
      Text(
        text = hitMeta(hit),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(12.dp))
    Text(
      text = "打开",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

/**
 * 一行元信息：`1080P · 4.2 GB · 已看 36 分钟`。
 *
 * 取不到的部分整段省掉，不写「未知」—— 空着的字段不会误导，占位词会。
 */
private fun hitMeta(hit: ServerHit): String {
  val parts = ArrayList<String>(3)
  hit.resolutionLabel?.let { parts += it }
  hit.sizeBytes?.let { parts += formatBytes(it) }
  val playedMs = hit.playedPositionMs
  if (playedMs > 0) {
    val minutes = (playedMs / 60_000L).coerceAtLeast(1L)
    parts += "已看 $minutes 分钟"
  }
  if (parts.isEmpty()) parts += (hit.item.Type ?: "条目")
  return parts.joinToString(" · ")
}

/** 体积：保留一位小数，超过 1024 进位到上一档 */
private fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var index = 0
  while (value >= 1024 && index < units.lastIndex) {
    value /= 1024
    index++
  }
  return if (index == 0) {
    "${value.toLong()} ${units[index]}"
  } else {
    String.format(Locale.ROOT, "%.1f %s", value, units[index])
  }
}
