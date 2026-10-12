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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Icon
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
 * 片头 / 片尾区间里的「跳过片头 / 跳过片尾」小胶囊。
 *
 * 只在**自动跳过关着**、播放头又落在片头 / 片尾区间里时出现
 * （自动跳开着的时候不需要用户动手，见 `PlayerActivity.startSkipSegmentWatcher`）。
 * 点整条 = 跳到段尾；片尾的「段尾」就是视频结尾，等价于触发连播。
 *
 * 视觉上与连播倒计时卡（[AutoplayCountdownCard]）同一条设计线：
 * 半透明小胶囊、无强调色、无水波纹 —— 用户在专心看片，任何抢视觉的东西都是打扰。
 */
@Composable
fun SkipSegmentChip(
  viewModel: PlayerViewModel,
  onSkip: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val label by viewModel.skipSegmentLabel.collectAsState()

  AnimatedVisibility(
    visible = label != null,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(180)),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(percent = 50))
        .background(Color.Black.copy(alpha = 0.38f))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onSkip,
        )
        .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = Icons.Default.FastForward,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.82f),
        modifier = Modifier.size(15.dp),
      )
      Text(
        text = label ?: "",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.82f),
      )
    }
  }
}
