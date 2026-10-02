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
 * 定位：它是**控制条收起时**那一层常驻 UI 的成员 —— 不用先点一下屏幕把控制条呼出来，
 * 播放中随时能切上一条/下一条。控制条一旦展开，中部已经有大的上一集/下一集，
 * 这个小件就是冗余的，所以**控制条显示期间自动让位**。
 *
 * 显隐规则（刻意做减法，避免「视觉散乱」）：
 * - 控制条收起时显示、展开时隐藏（大的那套已经在屏幕上了）；
 * - 队列里既没有上一条也没有下一条（单文件播放）时整块隐藏，
 *   一排灰按钮挂着没有意义；
 * - **队列弹窗（[Sheets.Playlist]）展开时隐藏**：本页里它是最后绘制的，
 *   会浮在弹窗之上压住弹窗左下角；弹窗关掉后按上面的规则恢复。
 *   （注意这条不能省：打开弹窗时控制条会被收起，只靠 `!controlsShown` 反而会把它放出来）
 * - 设置里可以整体关掉（[PlayerPreferences.showFloatingPlaylistSwitcher]），
 *   关掉后任何情况下都不显示。
 */
@Composable
fun FloatingPlaylistSwitcher(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val switcherEnabled by playerPreferences.showFloatingPlaylistSwitcher.collectAsState()
  // 控制条展开时让位给中部那套大的上一集/下一集；收起时才由它接管这一角
  val controlsShown by viewModel.controlsShown.collectAsState()
  // 队列弹窗展开时也收起（否则它会画在弹窗之上压住左下角）；关闭后按上面的规则恢复
  val sheetShown by viewModel.sheetShown.collectAsState()

  val hasPrev = viewModel.hasPrevious()
  val hasNext = viewModel.hasNext()

  AnimatedVisibility(
    visible =
      switcherEnabled &&
        !controlsShown &&
        sheetShown != Sheets.Playlist &&
        (hasPrev || hasNext),
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
