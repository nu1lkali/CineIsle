package app.marlboroadvance.mpvex.ui.subtitle.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue

/**
 * 搜索结果的时间线预览弹窗。
 *
 * ## 为什么是 Dialog 而不是再开一层 BottomSheet
 *
 * 它总是叠在「网络字幕」抽屉上面：一层 sheet 再套一层 sheet，两个都能被拖拽关闭，
 * 手势会互相抢。用 Dialog 明确「这是抽屉里的一个只读浮层」，也跟本文件旁边那个
 * 「删除确认」保持同一种层级表达。
 *
 * ## 只读、不下载
 *
 * 用户在这里做的事是「核对这条字幕对不对」：台词内容、出现时间、条目数量。
 * 看完关掉，本地库里不会多出任何文件。
 */
@Composable
internal fun OnlineSubtitleTimelineDialog(
  preview: OnlineSubtitleViewModel.TimelinePreview,
  onClose: () -> Unit,
  onRetry: () -> Unit,
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // ── 头部 ──
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "字幕时间线",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = preview.title,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
          IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
          }
        }

        HorizontalDivider(
          modifier = Modifier.padding(top = 8.dp),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        // ── 正文 ──
        when {
          preview.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                  text = "正在读取字幕内容…",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 10.dp),
                )
              }
            }
          }

          preview.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp),
              ) {
                Text(
                  text = "无法预览这条字幕",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                )
                Text(
                  text = preview.error,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
                  Text("重试")
                }
              }
            }
          }

          preview.cues.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp),
              ) {
                Text(
                  text = "这条字幕没有可解析的时间轴",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                )
                Text(
                  text = "图片字幕（PGS / VobSub）与未知格式无法列出条目。" +
                    "可以先用下方按钮下载，再到字幕实验室里用播放器试看。",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
            }
          }

          else -> {
            if (preview.truncated) {
              Text(
                text = "该字幕条目过多，此处仅列出前 ${preview.cues.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
              )
            }
            Text(
              text = "共 ${preview.cues.size} 条 · 预览只读取内容，不会保存到本地",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(
              modifier = Modifier.fillMaxWidth().weight(1f),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              itemsIndexed(preview.cues) { index, cue ->
                TimelineCueRow(index = index + 1, cue = cue)
              }
            }
          }
        }
      }
    }
  }
}

/** 一条时间轴：序号 + 起止时间 + 台词。长台词最多 4 行，避免一条撑满整屏。 */
@Composable
private fun TimelineCueRow(
  index: Int,
  cue: SubtitleCue,
) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Text(
      text = index.toString(),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
      // 四位数序号（最多 6000 条）也放得下，不再按固定 34dp 裁掉
      modifier = Modifier.widthIn(min = 34.dp).padding(top = 2.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = cue.timeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(1.dp))
      Text(
        text = cue.text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
