package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * 上一集 / 下一集图标的**按下动画**：竖条纹丝不动，只有三角从侧面滑入并放大。
 *
 * ## 为什么不像中央播放键那样用 AnimatedVectorDrawable
 *
 * 中央键的动画是「三角 ↔ 两条竖条 + 整体旋转 90°」，两个形态都是 AVD 的
 * `pathData` 关键帧，天然适合。而这里要的是**每次点击都重放一次**的瞬时动画：
 * AVD 的 `atEnd` 只有在 true/false 之间切换才会播，切回去是**倒放**（三角从原位
 * 滑出到外面），方向正好反了。所以这一处改用 [Animatable] 直接驱动位移 / 缩放 / 透明度。
 *
 * ## 几何（64 网格，与 24dp 的播放键同一套「弧边语言」）
 *
 * - 竖条：6×32 的圆角矩形，位置固定，**永远不参与动画**
 * - 三角：两条斜边向外微凸、尖端收成小圆弧、左侧竖边带圆角过渡
 *
 * ## 动画参数（与设计稿一致）
 *
 * | 量 | 起始 | 中途（72%） | 结束 |
 * |---|---|---|---|
 * | 位移 | 外侧 18 | 越位 3 | 0 |
 * | 缩放 | 0.68 | 1.04 | 1.0 |
 * | 透明度 | 0.2 | —— | 1.0 |
 *
 * @param forward true = 下一集（三角朝右，从左滑入）；false = 上一集（整体镜像）
 * @param pressTick 每次点击由调用方 +1。用「计数」而不是「布尔」是因为要**重放**：
 *   布尔值第二次点击时没有变化，`LaunchedEffect` 不会重跑。
 * @param contentDescription 无障碍描述。Canvas 不自带语义，必须显式给，
 *   否则读屏软件在这两个按钮上只会念出"按钮"。
 */
@Composable
fun SkipEpisodeIcon(
  forward: Boolean,
  pressTick: Int,
  tint: Color,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  val dir = if (forward) 1f else -1f

  // 三个量分开驱动：位移与缩放要在 72% 处过冲、透明度只需单调渐显，
  // 用同一条 progress 再换算反而要写更多分支。
  val shift = remember { Animatable(0f) }
  val zoom = remember { Animatable(1f) }
  val fade = remember { Animatable(1f) }

  LaunchedEffect(pressTick) {
    if (pressTick == 0) return@LaunchedEffect

    // 先瞬移回起点，再播「进入」——这样每次点击都是完整的一次滑入，
    // 而不是从当前位置继续（否则第二下就是原地不动）。
    shift.snapTo(-18f * dir)
    zoom.snapTo(0.68f)
    fade.snapTo(0.2f)

    launch {
      shift.animateTo(
        targetValue = 0f,
        animationSpec =
          keyframes {
            durationMillis = ANIM_MS
            (3f * dir) at 374 using FastOutSlowInEasing
            0f at ANIM_MS
          },
      )
    }
    launch {
      zoom.animateTo(
        targetValue = 1f,
        animationSpec =
          keyframes {
            durationMillis = ANIM_MS
            1.04f at 374 using FastOutSlowInEasing
            1f at ANIM_MS
          },
      )
    }
    launch {
      fade.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 286),
      )
    }
  }

  Canvas(modifier.semantics { this.contentDescription = contentDescription.orEmpty() }) {
    val k = size.minDimension / GRID
    if (k <= 0f) return@Canvas

    // 竖条：静态，画在三角之后（同色填充，前后无所谓，这里先画省一次图层）
    drawRoundRect(
      color = tint,
      topLeft = Offset(if (forward) 46f * k else 12f * k, 16f * k),
      size = Size(6f * k, 32f * k),
      cornerRadius = CornerRadius(3f * k, 3f * k),
    )

    val triPath = trianglePath(forward, k)
    withTransform({
      // 先缩后移：位移若在缩放之后施加，会被一起放大 4%，视觉上略多个像素
      scale(zoom.value, zoom.value, pivot = Offset(32f * k, 32f * k))
      translate(shift.value * k, 0f)
    }) {
      // tint 本身可能已带 alpha（置灰 0.38），所以是相乘而不是覆盖
      drawPath(triPath, color = tint.copy(alpha = tint.alpha * fade.value))
    }
  }
}

private const val GRID = 64f
private const val ANIM_MS = 520

/**
 * 弧边三角（64 网格）。
 *
 * ⚠️ forward / 反向是**逐坐标镜像**（x → 64 - x），不是简单加个负号 —— 曲线控制点
 * 也要一起翻，否则镜像后弧度会朝里凹。
 */
private fun trianglePath(forward: Boolean, k: Float): Path =
  Path().apply {
    if (forward) {
      moveTo(22f * k, 18f * k)
      cubicTo(31f * k, 21f * k, 38f * k, 27f * k, 41f * k, 31f * k)
      quadraticTo(42.2f * k, 32f * k, 41f * k, 33f * k)
      cubicTo(38f * k, 37f * k, 31f * k, 43f * k, 22f * k, 46f * k)
      cubicTo(20.8f * k, 46.4f * k, 20.2f * k, 45.8f * k, 20.2f * k, 44.6f * k)
      lineTo(20.2f * k, 19.4f * k)
      cubicTo(20.2f * k, 18.2f * k, 20.8f * k, 17.6f * k, 22f * k, 18f * k)
    } else {
      moveTo(42f * k, 18f * k)
      cubicTo(33f * k, 21f * k, 26f * k, 27f * k, 23f * k, 31f * k)
      quadraticTo(21.8f * k, 32f * k, 23f * k, 33f * k)
      cubicTo(26f * k, 37f * k, 33f * k, 43f * k, 42f * k, 46f * k)
      cubicTo(43.2f * k, 46.4f * k, 43.8f * k, 45.8f * k, 43.8f * k, 44.6f * k)
      lineTo(43.8f * k, 19.4f * k)
      cubicTo(43.8f * k, 18.2f * k, 43.2f * k, 17.6f * k, 42f * k, 18f * k)
    }
    close()
  }
