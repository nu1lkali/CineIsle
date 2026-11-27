package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.components.RepeatingIconButton
import app.marlboroadvance.mpvex.ui.player.engine.PlaybackSpeed
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 动态倍速浮层的展开态：紧凑显示 0.1~6.0 的**数值线性**刻度尺。
 *
 * ## 为什么按数值线性而不是按档位等距
 *
 * 旧实现把 8 个 preset 当等距刻度画（`0.25/0.5/1/1.5/2/2.5/3/4`），
 * 档距忽大忽小，精度和上限都上不去。换成线性尺之后，4.0~6.0 只是轨道右侧
 * 自然的 1/3，标签只放七个整数刻度，不会挤成一团。
 *
 * 所有写入值都经过 [PlaybackSpeed.quantize]，与横滑、面板、预设 chip 同口径。
 *
 * @param currentSpeed 当前播放速度
 * @param modifier 容器 modifier
 * @param onSpeedChange 微调回调（0.1 步进）；传 null 时只展示、不给按钮
 */
@Composable
fun SpeedControlSlider(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
  onSpeedChange: ((Float) -> Unit)? = null,
) {
  val speed = PlaybackSpeed.quantize(currentSpeed)
  // 只标七个整数刻度；0.1 作为下限单独标出，4.0~6.0 是右侧自然的 1/3。
  val ticks = listOf(0.1f, 1f, 2f, 3f, 4f, 5f, 6f)
  val trackWidth = 280.dp

  val primaryColor = MaterialTheme.colorScheme.primary
  val onSurfaceColor = MaterialTheme.colorScheme.onSurface
  val progress = ((speed - PlaybackSpeed.MIN) / PlaybackSpeed.SPAN).coerceIn(0f, 1f)

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(
      1.dp,
      MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    ),
    modifier = modifier.animateContentSize(),
  ) {
    Box(
      modifier = Modifier.padding(
        vertical = MaterialTheme.spacing.small,
        horizontal = MaterialTheme.spacing.medium,
      ),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // 当前速度：大号一位小数，不写「倍速播放中」这类重复说明。
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.FastForward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "${PlaybackSpeed.display(speed)}x",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp),
          )
        }

        // 线性尺的刻度标签：0.1 / 1 / 2 / 3 / 4 / 5 / 6
        Row(
          modifier = Modifier.width(trackWidth),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ticks.forEach { tick ->
            val isCurrent = abs(speed - tick) < 0.05f
            Text(
              text = if (tick == PlaybackSpeed.MIN) "0.1" else tick.toInt().toString(),
              fontSize = if (isCurrent) 13.sp else 10.sp,
              fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
              color = if (isCurrent) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
            )
          }
        }

        // 线性轨道：填充到 (speed - MIN) / SPAN，刻度按同样比例分布。
        Canvas(
          modifier = Modifier
            .width(trackWidth)
            .height(3.dp),
        ) {
          val w = size.width
          val centerY = size.height / 2
          val trackHeight = 3.dp.toPx()

          drawLine(
            color = onSurfaceColor.copy(alpha = 0.35f),
            start = Offset(0f, centerY),
            end = Offset(w, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
          )
          drawLine(
            color = primaryColor,
            start = Offset(0f, centerY),
            end = Offset(w * progress, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
          )
          ticks.forEach { tick ->
            val fraction = ((tick - PlaybackSpeed.MIN) / PlaybackSpeed.SPAN).coerceIn(0f, 1f)
            val reached = speed + 0.001f >= tick
            drawCircle(
              color = if (reached) primaryColor else onSurfaceColor.copy(alpha = 0.7f),
              radius = 2.5.dp.toPx(),
              center = Offset(w * fraction, centerY),
            )
          }
        }

        // 0.1 步进微调：点一下走一格，长按连续走（RepeatingIconButton）。
        if (onSpeedChange != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            RepeatingIconButton(
              onClick = { onSpeedChange(PlaybackSpeed.quantize(speed - PlaybackSpeed.STEP)) },
              modifier = Modifier.size(32.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.player_speed_decrease),
                modifier = Modifier.size(18.dp),
              )
            }
            Text(
              text = "${PlaybackSpeed.display(speed)}x",
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.width(56.dp),
              textAlign = TextAlign.Center,
            )
            RepeatingIconButton(
              onClick = { onSpeedChange(PlaybackSpeed.quantize(speed + PlaybackSpeed.STEP)) },
              modifier = Modifier.size(32.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.player_speed_increase),
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 收起态胶囊：只显示 `x.x x`，不重复控件名。
 */
@Composable
fun CompactSpeedIndicator(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
) {
  val speed = PlaybackSpeed.quantize(currentSpeed)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier
      .background(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(100.dp)
      )
      .border(
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(100.dp)
      )
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small)
  ) {
    Icon(
      imageVector = Icons.Filled.FastForward,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurface
    )
    Text(
      text = "${PlaybackSpeed.display(speed)}x",
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(start = 4.dp),
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}
