package app.marlboroadvance.mpvex.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import org.koin.compose.koinInject

/**
 * 左下角悬浮的「上一条 / 下一条」小切换按钮。
 *
 * 与控制条自带的上一集/下一集的区别：**它不随控件显隐**，
 * 控制条收起时仍然常驻在左下角，播放中随时可以切上一条/下一条，
 * 不用先点一下屏幕把控制条呼出来。
 *
 * 显隐规则（刻意做减法，避免「视觉散乱」）：
 * - 始终显示（不随控件显隐）：它是左下角常驻的小切换件，
 *   与控制条自带的上一集/下一集不抢位置（控制条中部那套是大的、居中）；
 * - 队列里既没有上一条也没有下一条（单文件播放）时整块隐藏，
 *   一排灰按钮挂着没有意义；
 * - 设置里可以整体关掉（[PlayerPreferences.showFloatingPlaylistSwitcher]）。
 */
@Composable
fun FloatingPlaylistSwitcher(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val switcherEnabled by playerPreferences.showFloatingPlaylistSwitcher.collectAsState()

  val hasPrev = viewModel.hasPrevious()
  val hasNext = viewModel.hasNext()

  AnimatedVisibility(
    visible = switcherEnabled && (hasPrev || hasNext),
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(180)),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.clip(RoundedCornerShape(percent = 50)),
      horizontalArrangement = Arrangement.spacedBy(1.5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SwitcherButton(
        enabled = hasPrev,
        onClick = { if (viewModel.hasPrevious()) viewModel.playPrevious() },
      ) {
        Icon(
          imageVector = Icons.Default.SkipPrevious,
          contentDescription = "上一条",
          tint = Color.White.copy(alpha = if (hasPrev) 0.85f else 0.35f),
          modifier = Modifier.size(17.dp),
        )
      }
      SwitcherButton(
        enabled = hasNext,
        onClick = { if (viewModel.hasNext()) viewModel.playNext() },
      ) {
        Icon(
          imageVector = Icons.Default.SkipNext,
          contentDescription = "下一条",
          tint = Color.White.copy(alpha = if (hasNext) 0.85f else 0.35f),
          modifier = Modifier.size(17.dp),
        )
      }
    }
  }
}

/** 玻璃质感的小圆按钮（30dp），比控制条的圆形按键小一号，作常驻悬浮件 */
@Composable
private fun SwitcherButton(
  enabled: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier.size(34.dp),
    shape = CircleShape,
    color = PlayerControlGlassFill,
    contentColor = Color.White,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(1.dp, PlayerControlGlassBorder),
    onClick = onClick,
    enabled = enabled,
  ) {
    androidx.compose.foundation.layout.Box(
      contentAlignment = Alignment.Center,
    ) {
      icon()
    }
  }
}
