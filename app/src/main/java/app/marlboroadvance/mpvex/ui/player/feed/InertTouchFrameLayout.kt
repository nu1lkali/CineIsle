package app.marlboroadvance.mpvex.ui.player.feed

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 「不碰触摸」的 FrameLayout —— 视界流 mpv 画面层的根容器。
 *
 * ── 为什么需要它 ──
 * mpv 的画面层是一块**全屏**的 `AndroidView`，叠在 Pager 之上。Compose 的命中测试
 * 会把指针事件先交给这一层：`AndroidView` 内部通过 `pointerInteropFilter` 调它的
 * `dispatchTouchEvent`，一旦这里返回 true（= 被消费），**同一条链上其它节点就拿不到
 * 这一串事件了** —— 具体表现就是翻页划不动、亮度音量拖不出来、单击唤不出控件。
 *
 * 这个坑在播放页已经踩过一次（弹幕层当全屏 AndroidView 时点屏幕唤不出控制栏，
 * 见 `ui/player/danmaku/DanmakuOverlay.kt` 里同名的私有类）。结论是一样的：
 * **全屏 View 想不参与触摸，必须在容器这一层短路，不能指望「子 View 默认不可点击」。**
 *
 * 而 mpv 这块 SurfaceView 本来就不需要触摸：单击 / 双击 / 拖动 / 翻页全部由
 * [VerticalFeedActivity] 的 Compose 手势层与 Pager 处理（它们在这条链的上游）。
 *
 * ── 为什么覆写 dispatchTouchEvent 而不是给子 View 设 isClickable = false ──
 * 后者只管子 View 自己，管不住 ViewGroup 的兜底分发（`onTouchEvent` 可能被
 * ViewGroup 自己吃掉）。覆写容器则连子 View 一起挡掉，不依赖任何默认行为。
 */
internal class InertTouchFrameLayout(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

  init {
    isClickable = false
    isLongClickable = false
    isFocusable = false
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

  override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

  override fun onTouchEvent(event: MotionEvent?): Boolean = false

  override fun performClick(): Boolean = false
}
