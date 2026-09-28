package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 爱心收藏按钮的「动效本体」—— 播放页与详情页共用。
 *
 * 动效移植自 AlistClientN 的 `tiktok_player_page.dart` 里的 `_HeartBtn`（**单击收藏**，
 * 不是双击屏幕的红心涟漪）：
 * - 弹跳：520ms 内 easeOutCubic 冲到 1.35 倍，再用 easeOutBack 回落 1.0（带回弹过冲）；
 * - 星光：只有「收藏」时喷出，一圈红色光环 + 8 颗金色四角星沿半径扩散并淡出；
 * - 收藏后的红心用径向渐变（浅红 → 深红）并带一圈红色外发光；
 * - 收藏时给一次轻触反馈。
 *
 * 组件是**状态驱动**的：只监听 [isFavorite] 的翻转，false → true 播放弹跳 + 星光，
 * true → false 只做回弹（取消收藏不喷星光，语义更自然）。因此调用方只要保证
 * 点击后状态**乐观更新**（先翻转再发请求），手感就是即时的。
 *
 * 组件只画「爱心 + 星光」，不含圆形底与点击层 —— 那部分各页面差异较大，
 * 由调用方自己包一层（播放页要半透明玻璃圆底，详情页直接浮在剧照上）。
 *
 * @param isFavorite 当前是否已收藏
 * @param isToggling 请求进行中，置灰防重复点击
 * @param modifier 施加在「星光作画区」外层 Box 上（尺寸 = [iconSize] × 1.7）
 * @param iconSize 爱心图标边长
 * @param idleColor 未收藏时的图标颜色（收藏态由红心渐变接管）
 */
@Composable
fun FavoriteHeartIcon(
  isFavorite: Boolean,
  isToggling: Boolean,
  modifier: Modifier = Modifier,
  iconSize: Dp = FAVORITE_ICON_SIZE,
  idleColor: Color = Color.White,
) {
  val haptics = LocalHapticFeedback.current
  val progress = remember { Animatable(0f) }
  var burst by remember { mutableStateOf(false) }
  // 首次组合只是把「进入页面时的既有状态」画出来，不该播一次动画
  var initialized by remember { mutableStateOf(false) }

  LaunchedEffect(isFavorite) {
    if (!initialized) {
      initialized = true
      return@LaunchedEffect
    }
    burst = isFavorite
    if (isFavorite) {
      runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    progress.snapTo(0f)
    progress.animateTo(
      1f,
      animationSpec = tween(FAVORITE_ANIM_DURATION_MS, easing = LinearEasing),
    )
  }

  val t = progress.value

  Box(
    modifier = modifier.size(iconSize * HEART_SPARKLE_BOX_RATIO),
    contentAlignment = Alignment.Center,
  ) {
    // 星光只绘制、不参与命中测试，允许溢出到按钮之外
    if (burst && t > 0f && t < 1f) {
      Canvas(modifier = Modifier.matchParentSize()) {
        drawHeartSparkles(t)
      }
    }

    Icon(
      imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
      contentDescription = if (isFavorite) "取消收藏" else "收藏",
      // 收藏态用纯白打底，真正的红色来自下面叠加的径向渐变（SrcAtop 只涂在爱心像素上）
      tint = if (isFavorite) Color.White else idleColor,
      modifier =
        Modifier
          .size(iconSize)
          .graphicsLayer {
            val scale = heartBounceScale(t)
            scaleX = scale
            scaleY = scale
            // 请求进行中置灰，避免重复点击
            alpha = if (isToggling) 0.38f else 1f
          }
          .then(if (isFavorite) Modifier.heartGlow().heartGradient() else Modifier),
    )
  }
}

/** 收藏动效总时长，与 Flutter 版 `_HeartBtn` 的 520ms 对齐 */
private const val FAVORITE_ANIM_DURATION_MS = 520

/** 喷星光在动画进度 12% 之后才出现，先让爱心弹出来 */
private const val HEART_SPARKLE_START = 0.12f

/** 星光作画区相对爱心边长的倍数（四周留出扩散空间） */
private const val HEART_SPARKLE_BOX_RATIO = 1.7f

/** 爱心图标默认边长 */
private val FAVORITE_ICON_SIZE = 20.dp

/** 红心径向渐变的浅色端（左上偏上，营造高光） */
private val HEART_GRADIENT_LIGHT = Color(0xFFFF8A80)

/** 红心径向渐变的深色端 */
private val HEART_GRADIENT_DEEP = Color(0xFFE53935)

/** 红心外发光 */
private val HEART_GLOW_COLOR = Color(0x55FF3B30)

/** 扩散光环颜色 */
private val HEART_RING_COLOR = Color(0xFFFF5252)

/** 四角星颜色 */
private val HEART_STAR_COLOR = Color(0xFFFFD54F)

/** 光环起始半径 */
private val HEART_RING_START = 10.dp

/** 光环扩散增量（起始半径 + 该值 = 结束半径） */
private val HEART_RING_GROWTH = 14.dp

/**
 * 弹跳曲线：0 → 0.6 冲到 1.35 倍，0.6 → 1 用过冲曲线回落。
 *
 * 与 Flutter 版保持一致：`1 + 0.35 * easeOutCubic(t)`、
 * `1.35 - 0.35 * easeOutBack(t)`。
 */
private fun heartBounceScale(t: Float): Float =
  when {
    t <= 0f -> 1f
    t < 0.6f -> 1f + 0.35f * EaseOutCubic.transform(t / 0.6f)
    else -> 1.35f - 0.35f * EaseOutBack.transform((t - 0.6f) / 0.4f)
  }

/** 红心外发光：用径向渐变模拟带模糊的红色阴影，绘制在爱心之下 */
private fun Modifier.heartGlow(): Modifier =
  drawBehind {
    val radius = size.minDimension * 0.8f
    drawCircle(
      brush =
        Brush.radialGradient(
          colors = listOf(HEART_GLOW_COLOR, Color.Transparent),
          center = center,
          radius = radius,
        ),
      radius = radius,
    )
  }

/**
 * 用径向渐变给爱心上色。
 *
 * 等价于 Flutter 里的 `ShaderMask`：离屏合成后用 SrcAtop 把渐变「涂」在图标上，
 * 于是渐变只落在爱心的不透明像素里。
 */
private fun Modifier.heartGradient(): Modifier =
  this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
      drawContent()
      drawRect(
        brush =
          Brush.radialGradient(
            colors = listOf(HEART_GRADIENT_LIGHT, HEART_GRADIENT_DEEP),
            center = Offset(size.width * 0.5f, size.height * 0.32f),
            radius = size.minDimension * 1.1f,
          ),
        blendMode = BlendMode.SrcAtop,
      )
    }

/**
 * 爱心外围星光：一圈扩散光环 + 8 颗四角星，随进度扩散并淡出。
 *
 * 与 Flutter 版 `_SparklePainter` 同一套参数（角度偏移 0.35、半径 14→30、
 * 星星交替大小、先淡入再淡出），只是把像素单位换成了 dp。
 */
private fun DrawScope.drawHeartSparkles(t: Float) {
  val p = ((t - HEART_SPARKLE_START) / (1f - HEART_SPARKLE_START)).coerceIn(0f, 1f)
  if (p <= 0f || p >= 1f) return

  // 先快速淡入，再缓慢淡出，避免星光突兀地出现/消失
  val fade = (1f - p) * (if (p < 0.25f) p / 0.25f else 1f)
  val radius = HEART_RING_START.toPx() + HEART_RING_GROWTH.toPx() * EaseOutCubic.transform(p)

  // 扩散的光环
  drawCircle(
    color = HEART_RING_COLOR.copy(alpha = 0.55f * fade),
    radius = radius,
    style = Stroke(width = (2.4f * (1f - p) + 0.4f).dp.toPx()),
  )

  // 星光：8 颗四角星，交替大小形成闪烁节奏
  for (i in 0 until 8) {
    val angle = i * (2f * PI.toFloat() / 8f) + 0.35f
    val distance = radius * (0.85f + 0.15f * ((i % 3) / 2f))
    val cx = center.x + cos(angle.toDouble()).toFloat() * distance
    val cy = center.y + sin(angle.toDouble()).toFloat() * distance
    val starRadius = ((2.6f * (1f - p) + 0.6f) * (if (i % 2 == 0) 1f else 0.7f)).dp.toPx()
    drawPath(
      path = sparkleStarPath(cx, cy, starRadius),
      color = HEART_STAR_COLOR.copy(alpha = 0.95f * fade),
    )
  }
}

/** 四角星（十字形）路径，与 Flutter 版 `_drawSparkle` 的二次贝塞尔一致 */
private fun sparkleStarPath(
  cx: Float,
  cy: Float,
  r: Float,
): Path {
  val inner = r * 0.28f
  val outer = r * 1.8f
  return Path().apply {
    moveTo(cx, cy - outer)
    quadThrough(cx, cy - outer, cx + inner, cy - inner, cx + outer, cy)
    quadThrough(cx + outer, cy, cx + inner, cy + inner, cx, cy + outer)
    quadThrough(cx, cy + outer, cx - inner, cy + inner, cx - outer, cy)
    quadThrough(cx - outer, cy, cx - inner, cy - inner, cx, cy - outer)
    close()
  }
}

/**
 * 二次贝塞尔（起点 → 控制点 → 终点），用三次形式表达后调用 [Path.cubicTo]。
 *
 * 这样写是为了避开 `quadraticTo` / `quadraticBezierTo` 在不同 Compose 版本里
 * 的命名差异：二次贝塞尔可以精确等价成一条三次贝塞尔，控制点取 2/3 插值。
 */
private fun Path.quadThrough(
  startX: Float,
  startY: Float,
  controlX: Float,
  controlY: Float,
  endX: Float,
  endY: Float,
) {
  val c1x = startX + 2f / 3f * (controlX - startX)
  val c1y = startY + 2f / 3f * (controlY - startY)
  val c2x = endX + 2f / 3f * (controlX - endX)
  val c2y = endY + 2f / 3f * (controlY - endY)
  cubicTo(c1x, c1y, c2x, c2y, endX, endY)
}
