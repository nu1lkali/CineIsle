package app.marlboroadvance.mpvex.ui.preferences.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 带刻度的可拖动滑块 —— 用于「快进/快退时长」这类**需要一眼看清量级**的数值设置。
 *
 * ## 为什么不用 Material 的 [androidx.compose.material3.Slider]
 *
 * `Slider` 只有轨道 + 圆点，没有刻度；这个值域（1~300 秒）跨度很大，
 * 光看一个数字说不清"现在大概在哪个量级、两端极限在哪"，刻度线把这件事摊开了。
 *
 * ## 交互
 *
 * · 拖圆点，或**直接点轨道任意位置**跳转（点轨道是这类滑块最省事的操作）
 * · 圆点半径会占掉轨道两端各半，所以位置↔数值换算时要内缩，否则拖到最左圆点会跑出控件
 *
 * @param ticks 刻度线所在的数值（位置按它在值域中的比例线性算）
 */
@Composable
fun TickedSlider(
  value: Int,
  onValueChange: (Int) -> Unit,
  valueRange: IntRange,
  modifier: Modifier = Modifier,
  ticks: List<Int> = defaultTicks(valueRange),
  enabled: Boolean = true,
  /** 当前值的显示文案，如「30 秒」；不传则只显示数字 */
  valueLabel: String = value.toString(),
  /** 每档刻度下方的文字标签；数量应与 [ticks] 对应或更少（按首尾+中间取） */
  tickLabels: List<Int> = ticks,
  title: String? = null,
  supportingText: String? = null,
) {
  val density = LocalDensity.current
  val min = valueRange.first
  val max = valueRange.last
  val span = (max - min).coerceAtLeast(1)
  val fraction = ((value - min).toFloat() / span).coerceIn(0f, 1f)

  val activeColor =
    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  val inactiveColor =
    if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
  // ⚠️ 必须在 Canvas **外面**取色：`Canvas` 的 lambda 是 `DrawScope`（非 @Composable），
  // 在里面写 `MaterialTheme.colorScheme.surface` 直接编译失败
  // （「@Composable invocations can only happen from the context of a @Composable function」）。
  val thumbInnerColor = MaterialTheme.colorScheme.surface

  val trackHeight = 6.dp
  val thumbRadius = 12.dp
  val controlHeight = thumbRadius * 2 + 12.dp

  Column(modifier = modifier) {
    if (title != null) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = if (title != null) 2.dp else 0.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = supportingText.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
      Text(text = valueLabel, style = MaterialTheme.typography.titleMedium, color = activeColor)
    }

    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(controlHeight)
          .semantics { contentDescription = "${title.orEmpty()} $valueLabel" }
          // ⚠️ 两个 pointerInput（点击 + 拖动）**必须分成两个**：
          // 合在一个里会让 detectTapGestures 与 detectHorizontalDragGestures
          // 互相抢事件，表现为"轻点没反应、拖动又跳一下"。
          .pointerInput(enabled, min, span) {
            if (!enabled) return@pointerInput
            val radiusPx = with(density) { thumbRadius.toPx() }
            fun posToValue(x: Float): Int =
              (((x - radiusPx) / (size.width - radiusPx * 2).coerceAtLeast(1f)) * span + min)
                .roundToInt()
                .coerceIn(min, max)
            detectTapGestures { offset -> onValueChange(posToValue(offset.x)) }
          }
          .pointerInput(enabled, min, span) {
            if (!enabled) return@pointerInput
            val radiusPx = with(density) { thumbRadius.toPx() }
            var last = -1
            detectHorizontalDragGestures(
              onDragStart = { last = -1 },
              onHorizontalDrag = { change, _ ->
                change.consume()
                val radiusPx2 = with(density) { thumbRadius.toPx() }
                val v =
                  (((change.position.x - radiusPx2) /
                    (size.width - radiusPx2 * 2).coerceAtLeast(1f)) * span + min)
                    .roundToInt()
                    .coerceIn(min, max)
                // 只在跨过整数档位时才回调：拖动是每像素触发的，
                // 不去重的话一秒能写几十次偏好存储。
                if (v != last) {
                  last = v
                  onValueChange(v)
                }
              },
            )
          },
    ) {
      Canvas(modifier = Modifier.fillMaxWidth().height(controlHeight)) {
        val cy = size.height / 2f
        val left = thumbRadius.toPx()
        val right = size.width - thumbRadius.toPx()
        val usable = (right - left).coerceAtLeast(1f)
        val thumbX = left + usable * fraction

        drawLine(
          color = inactiveColor,
          start = Offset(left, cy),
          end = Offset(right, cy),
          strokeWidth = trackHeight.toPx(),
          cap = StrokeCap.Round,
        )
        drawLine(
          color = activeColor,
          start = Offset(left, cy),
          end = Offset(thumbX, cy),
          strokeWidth = trackHeight.toPx(),
          cap = StrokeCap.Round,
        )

        // 刻度线：从圆点上缘到下缘，露在轨道两侧，像一把尺子。
        val tickStroke = 1.5.dp.toPx()
        val tickY0 = cy - thumbRadius.toPx() + 2.dp.toPx()
        val tickY1 = cy + thumbRadius.toPx() - 2.dp.toPx()
        ticks.forEach { t ->
          val f = ((t - min).toFloat() / span).coerceIn(0f, 1f)
          val passed = f <= fraction + 0.001f
          drawLine(
            color = if (passed) activeColor else inactiveColor,
            start = Offset(left + usable * f, tickY0),
            end = Offset(left + usable * f, tickY1),
            strokeWidth = tickStroke,
          )
        }

        drawCircle(color = activeColor, radius = thumbRadius.toPx(), center = Offset(thumbX, cy))
        drawCircle(color = thumbInnerColor, radius = thumbRadius.toPx() - 4.dp.toPx(), center = Offset(thumbX, cy))
      }
    }

    // 刻度数值：只标 首 / 中 / 尾 三个。全标出来会挤成一团（红线 23）。
    if (tickLabels.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = thumbRadius - 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        val picks =
          buildList {
            add(tickLabels.first())
            if (tickLabels.size >= 3) add(tickLabels[tickLabels.size / 2])
            if (tickLabels.size >= 2) add(tickLabels.last())
          }.distinct()
        picks.forEach { Text(text = it.toString(), style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)) }
      }
    }
  }
}

/** 值域较大时默认按 60 一档刻度；太密会挤在一起。 */
fun defaultTicks(range: IntRange, step: Int = 60): List<Int> {
  val min = range.first
  val max = range.last
  val out = mutableListOf<Int>()
  var t = ((min + step - 1) / step) * step
  while (t <= max) {
    if (t >= min) out += t
    t += step
  }
  return out.ifEmpty { listOf(min, max) }
}
