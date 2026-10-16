package app.marlboroadvance.mpvex.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * 按下时缩到的比例。
 *
 * 0.96 在真机上被反馈「有点轻」（几乎看不出来），所以调到 **0.94**：
 * 缩幅从 4% 提到 6%，是「一眼能看出按下了」但又不至于像按钮在做动画的量级。
 * 想更明显就继续往下调（0.92 已经很重了，别到 0.9 以下 —— 卡片边缘会明显脱离栅格）。
 */
private const val DEFAULT_PRESSED_SCALE = 0.94f

/** 按下与回弹共用的弹簧：MediumLow 刚度 + 轻微回弹，约 150ms 走完 */
private val PRESS_SPRING = spring<Float>(
  dampingRatio = Spring.DampingRatioMediumBouncy,
  stiffness = Spring.StiffnessMediumLow,
)

/**
 * 卡片「按下去」的轻微缩放反馈（按下缩到 [pressedScale]，松手弹回 1f）。
 *
 * ## 为什么不用 InteractionSource
 * 项目里的卡片点击有两种写法：`Card(onClick = ...)` 和
 * `Card(modifier = Modifier.combinedClickable(...))`。走 InteractionSource 就得
 * **逐个把交互源塞进各自的 clickable**（改签名 / 加参数），碰一个漏一个；
 * 这里改成自己监听指针，**与点击实现解耦** ⇒ 任何卡片只要在 modifier 链最前面
 * 挂一句 `.pressDownScale()` 就行（不消费事件，不影响原有的点击 / 长按 / 水波纹）。
 *
 * ## 三个必须守住的点
 *  1. ⭐ **位移超过 touch slop 就撤销按压态**：列表里几乎每个手势都会先「按下」再变成
 *     滚动，不判 slop 的话一滑列表满屏卡片一起缩一下，非常刺眼。
 *  2. ⭐ **用 `Animatable` + 在 `graphicsLayer` 的 lambda 里读 `value`**：缩放值每帧都变，
 *     走 `animateFloatAsState`（State）会让整张卡**每帧重组**；写在绘制 lambda 里只失效绘制。
 *  3. ⭐ **只做缩放，不改尺寸**：`graphicsLayer` 只影响绘制，卡片占位不变 ⇒ 按下时
 *     周围卡片不会跟着挪位（用 padding / size 做缩放就会抖）。
 *
 * @param pressedScale 按下时缩到的比例；默认 0.94
 * @param enabled false 时完全不挂手势（例如不可点的静态卡）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressDownScale(
  pressedScale: Float = DEFAULT_PRESSED_SCALE,
  enabled: Boolean = true,
): Modifier {
  if (!enabled) return this
  val scale = remember { Animatable(1f) }
  val scope = rememberCoroutineScope()
  return this
    .pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        scope.launch { scale.animateTo(pressedScale, PRESS_SPRING) }
        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull { it.id == down.id }
          if (change == null || !change.pressed) break
          val dx = change.position.x - down.position.x
          val dy = change.position.y - down.position.y
          val slop = viewConfiguration.touchSlop
          // 手指移动超过 touch slop ⇒ 判定为滑动（列表滚动 / 翻页），撤销按压态
          if (dx * dx + dy * dy > slop * slop) break
        }
        scope.launch { scale.animateTo(1f, PRESS_SPRING) }
      }
    }
    .graphicsLayer {
      scaleX = scale.value
      scaleY = scale.value
    }
}
