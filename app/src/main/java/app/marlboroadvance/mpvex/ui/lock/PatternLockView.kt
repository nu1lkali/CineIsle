package app.marlboroadvance.mpvex.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import app.marlboroadvance.mpvex.preferences.AppLockPreferences

/**
 * 九宫格图案输入控件（3×3 点阵，滑动连线）。
 *
 * 点按行优先编号：`0 1 2 / 3 4 5 / 6 7 8`。手势结束时把经过的点序回调出去
 * （**含点数不足的情况** —— 是否合法由调用方判断并决定提示文案，控件不管业务）。
 *
 * ## 交互
 *  · 按住拖动，经过的圆点依次入队；**同一个点不重复入队**。
 *  · 松手（或手指离开）即结束本次手势，清掉拖动痕迹，保留 [lastPattern] 用于展示。
 *
 * ## 错误红闪
 * [error] 置真时，已画出的图案整体转成错误色；调用方负责在 ~0.7s 后把它复位，
 * 复位瞬间清掉 [lastPattern]（错误态结束 = 该清空了）。
 *
 * ## 命中判定
 * 纯几何：点到圆心距离 ≤ 半径阈值即算命中 —— 不做 Compose 组件级 hitTest，
 * 因为一次拖动要「顺路」扫过多个点，组件树那套一次只认一个。
 */
@Composable
internal fun PatternLockView(
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  error: Boolean = false,
  onPattern: (List<Int>) -> Unit,
) {
  var active by remember { mutableStateOf<List<Int>>(emptyList()) }
  var lastPattern by remember { mutableStateOf<List<Int>>(emptyList()) }
  var finger by remember { mutableStateOf<Offset?>(null) }

  // 错误态结束（调用方把 error 复位）⇒ 清掉上一次画的图案，别让红图挂在屏上
  LaunchedEffect(error) {
    if (!error) lastPattern = emptyList()
  }

  val primary = MaterialTheme.colorScheme.primary
  val errorColor = MaterialTheme.colorScheme.error
  val idleColor = MaterialTheme.colorScheme.outline
  val shown = if (finger != null) active else lastPattern
  val strokeColor = if (error) errorColor else primary
  val dotColor = if (error) errorColor else primary

  Canvas(
    modifier = modifier
      .aspectRatio(1f)
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        // 控件是正方形（aspectRatio(1f)），取短边算格宽；IntSize 的 width/height 是成员属性
        val cell = minOf(size.width, size.height) / AppLockPreferences.PATTERN_GRID.toFloat()
        val hitRadius = cell * 0.38f

        // 位置 → 点下标（命中不到返回 null）
        fun hitDot(pos: Offset): Int? {
          for (index in 0 until AppLockPreferences.PATTERN_GRID * AppLockPreferences.PATTERN_GRID) {
            val row = index / AppLockPreferences.PATTERN_GRID
            val col = index % AppLockPreferences.PATTERN_GRID
            val center = Offset(cell * (col + 0.5f), cell * (row + 0.5f))
            if ((pos - center).getDistance() <= hitRadius) return index
          }
          return null
        }

        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          active = emptyList()
          lastPattern = emptyList()
          finger = down.position
          hitDot(down.position)?.let { active = active + it }

          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
              change.consume()
              break
            }
            finger = change.position
            hitDot(change.position)?.let { dot ->
              if (dot !in active) active = active + dot
            }
            change.consume()
          }

          val result = active
          finger = null
          active = emptyList()
          lastPattern = result
          onPattern(result)
        }
      },
  ) {
    val cell = minOf(size.width, size.height) / AppLockPreferences.PATTERN_GRID
    val dotRadius = cell * 0.085f
    val lineWidth = dotRadius * 0.7f

    fun centerOf(index: Int): Offset {
      val row = index / AppLockPreferences.PATTERN_GRID
      val col = index % AppLockPreferences.PATTERN_GRID
      return Offset(cell * (col + 0.5f), cell * (row + 0.5f))
    }

    // 已确定的连线
    for (i in 0 until shown.size - 1) {
      drawLine(
        color = strokeColor,
        start = centerOf(shown[i]),
        end = centerOf(shown[i + 1]),
        strokeWidth = lineWidth,
      )
    }

    // 拖动中的「尾巴」：最后一点 → 当前手指
    val currentFinger = finger
    if (currentFinger != null && active.isNotEmpty()) {
      drawLine(
        color = strokeColor.copy(alpha = 0.5f),
        start = centerOf(active.last()),
        end = currentFinger,
        strokeWidth = lineWidth,
      )
    }

    // 圆点：未选中 = 空心圈，选中 = 实心
    for (index in 0 until AppLockPreferences.PATTERN_GRID * AppLockPreferences.PATTERN_GRID) {
      val center = centerOf(index)
      if (index in shown) {
        drawCircle(color = dotColor, radius = dotRadius * 1.8f, center = center)
      } else {
        drawCircle(
          color = idleColor,
          radius = dotRadius,
          center = center,
          style = Stroke(width = dotRadius * 0.4f),
        )
      }
    }
  }
}
