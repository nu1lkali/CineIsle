package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.theme.spacing
import java.util.Locale

/**
 * 锁屏态下的**只读播放进度条**。
 *
 * ## 为什么需要它
 *
 * 锁屏（`areControlsLocked`）的设计目的是「防误触」：锁住之后屏幕上只剩一个
 * 滑动解锁条，所有按钮都不出现。但代价是**完全看不到播到哪儿了** ——
 * 躺床上看片、手机架在支架上时，用户想确认进度只能先解锁、看一眼、再锁回去，
 * 三次操作只为读一个时间。
 *
 * 所以这里补一条**不可交互**的细进度条：单击屏幕唤起控件时它跟着出现，
 * 2 秒后随控件一起自动隐藏（锁屏态的自动隐藏时间在 [PlayerControls] 里固定为 2000ms）。
 *
 * ## 为什么不用现成的 Seekbar
 *
 * `SeekbarWithTimers` 自带拖动手势和 Slider 的命中区域，锁屏态下必须**绝对**
 * 不消费触摸 —— 否则用户想点屏幕让控件消失，手指落在进度条上会变成拖动进度。
 * 这里从零画一条 3dp 的静态进度，不带任何 `clickable` / `pointerInput`，
 * 触摸事件直接穿透到下面的手势层。
 */
@Composable
fun LockedProgressStrip(
  position: Float,
  duration: Float,
  modifier: Modifier = Modifier,
) {
  val safeDuration = duration.takeIf { it > 0f } ?: 0f
  val fraction =
    if (safeDuration <= 0f) {
      0f
    } else {
      (position / safeDuration).coerceIn(0f, 1f)
    }

  val trackColor = Color.White.copy(alpha = 0.24f)
  val playedColor = MaterialTheme.colorScheme.primary

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    // 轨道。高度只有 3dp：锁屏态下它是「信息」不是「控件」，不该抢视觉重心。
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(3.dp)
          .clip(RoundedCornerShape(50))
          .background(trackColor),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth(fraction)
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(playedColor),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = formatClock(position),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = Color.White.copy(alpha = 0.85f),
      )
      Text(
        text = formatClock(safeDuration),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = Color.White.copy(alpha = 0.85f),
      )
    }
  }
}

/**
 * 秒 → `m:ss` / `h:mm:ss`。
 *
 * 不直接复用进度条那两个计时器：它们接受的是负值（-1 表示未知）并且带倒计时翻转，
 * 这里只处理「已知的绝对秒数」，语义单一，出错面小。
 */
private fun formatClock(seconds: Float): String {
  if (seconds <= 0f) return "--:--"
  val total = seconds.toLong()
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) {
    String.format(Locale.US, "%d:%02d:%02d", h, m, s)
  } else {
    String.format(Locale.US, "%d:%02d", m, s)
  }
}
