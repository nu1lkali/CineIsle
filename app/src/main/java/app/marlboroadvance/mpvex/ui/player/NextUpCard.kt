package app.marlboroadvance.mpvex.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 片尾前约半分钟出现的「下一集」悬停卡片（Yamby 的 `next_up_sec`）。
 *
 * ## 与 [AutoplayCountdownCard] 的分工
 *
 * | | 出现时机 | 有无倒计时 | 语义 |
 * |---|---|---|---|
 * | 本卡片 | 片尾前约 30 秒 | 无 | 「后面还有一集，要不要接着看」 |
 * | 倒计时胶囊 | 片尾前最后一小段（默认 5 秒） | 有 | 「马上就要切了」 |
 *
 * 两个都保留：长片尾（有的剧片尾带 90 秒预告 / 片花）时，30 秒前的提示才是
 * 用户真正需要的 —— 等到最后 5 秒弹出来，他已经决定去干别的了。
 *
 * ## 为什么比倒计时胶囊更「实」
 *
 * 倒计时胶囊刻意做成极淡的一行小字（用户正在看片尾，别抢注意力）；
 * 这张卡是**主动邀请**，所以给了标题、给了播放入口、给了明确的关闭键 ——
 * 但仍然是半透明深底 + 白字，不引入主题强调色（播放器控制条之外不许硬编码色，
 * 这里用的全是「黑底 + 白字」这一套播放器通用中性色，不会和任何主题打架）。
 */
@Composable
fun NextUpCard(
  viewModel: PlayerViewModel,
  onPlayNow: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val visible by viewModel.nextUpVisible.collectAsState()
  val title by viewModel.nextUpTitle.collectAsState()

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 3 },
    exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 3 },
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier
        .widthIn(max = 260.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(Color.Black.copy(alpha = 0.55f))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onPlayNow,
        )
        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.size(20.dp),
      )
      Column(modifier = Modifier.weight(1f, fill = false)) {
        Text(
          text = "下一集",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.6f),
        )
        Text(
          text = title ?: "即将播放",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = Color.White.copy(alpha = 0.92f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(28.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "收起下一集提示",
          tint = Color.White.copy(alpha = 0.55f),
          modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}
