package app.marlboroadvance.mpvex.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp

/**
 * 片尾最后几秒出现的「下一集」提示。
 *
 * 设计上刻意**压低存在感**：不做卡片、不铺满宽度、不用强调色，
 * 就是右下角一个半透明小胶囊，一行小字 + 剩余秒数 + 一个很轻的关闭键。
 * 理由：这时候用户还在看片尾，任何抢视觉的东西都是打扰，
 * 它的作用是「让你知道要切了、并且来得及拦一下」，不是「请你注意我」。
 *
 * 交互保持简单：点整条胶囊 = 立刻切下一集；点右边的 × = 本集播完不切。
 */
@Composable
fun AutoplayCountdownCard(
  viewModel: PlayerViewModel,
  onPlayNow: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val remaining by viewModel.autoplayCountdown.collectAsState()

  AnimatedVisibility(
    visible = remaining > 0,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(180)),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(percent = 50))
        .background(Color.Black.copy(alpha = 0.38f))
        // 整条可点，但不要水波纹 —— 有涟漪就变成「按钮」了，会显得很跳
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onPlayNow,
        )
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = "下一个视频 $remaining",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.82f),
      )
      IconButton(
        onClick = onCancel,
        modifier = Modifier.size(28.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "不自动播放下一个视频",
          tint = Color.White.copy(alpha = 0.55f),
          modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}
