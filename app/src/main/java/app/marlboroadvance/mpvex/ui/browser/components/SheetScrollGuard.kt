package app.marlboroadvance.mpvex.ui.browser.components

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 抽屉内滚动容器的**手势隔离**：内容自己滚，抽屉不跟着上下动。
 *
 * ## 为什么两者会打架（material3 1.5.0-alpha15 的 `BottomSheetImpl` 实测）
 *
 * sheet 的 `Surface` 上挂了两层都能改抽屉 offset 的东西：
 *
 * 1. `Modifier.nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)` ——
 *    内层滚动容器**到头之后剩下的位移**会被它接走 ⇒ 抽屉跟着滑；
 * 2. `Modifier.anchoredDraggable(...)` —— 手指拖拽直接改 offset。
 *
 * 再加上内容高度过半屏时锚点里会多出一个 `PartiallyExpanded`
 * （`rememberModalBottomSheetState()` 默认 `skipPartiallyExpanded = false`），
 * 于是「滚到底 → 抽屉弹成全屏」「滚回顶 → 抽屉缩回半屏」，手指还在内容上，
 * 观感却是**内容滚动把抽屉一起拽动了**。层级越深、条目越多，触碰到的次数越多。
 *
 * ## 这个 Modifier 干什么
 *
 * 挂在**内层滚动容器自己那个节点上**（与 `verticalScroll(...)` / `LazyColumn` 同一 modifier 链）：
 *
 * - `draggable` 空实现：内容滚到头以后，后续**手指拖拽**在这里被消费掉 ⇒ 抽屉的
 *   `anchoredDraggable` 再也拿不到「未被消费」的手势，不会跟着动。内容还能滚时不受影响 ——
 *   内层比外层先拿到事件，正常滚动照旧由内层消费。
 * - `onPostScroll` 把**余量全吃掉**：内容滚动的残余位移不再交给抽屉的 nestedScroll 连接
 *   ⇒ 不会出现「滚到底继续拉 = 收抽屉」。
 *
 * ## 代价与保留
 *
 * - 代价：内容滚到边界时没有 overscroll 回弹（回弹的输入就是被吃掉的余量）。
 *   这里用回弹换「抽屉不乱动」，是有意的取舍。
 * - 抽屉照样能关：顶部 dragHandle 拖拽/点击、点 scrim、返回键都不在这个容器里。
 *
 * ⚠️ 用法必须是 `Modifier.then(guard).verticalScroll(...)` 的顺序 —— 隔离层要在滚动容器**外层**，
 * 放到里面等于没挂。
 */
@Composable
fun rememberSheetContentScrollGuard(): Modifier {
  val swallowLeftover = remember { SwallowVerticalLeftover }
  // 空实现即可：目的不是真的拖什么，而是让拖拽事件在这里终止
  val noopDragState = remember { DraggableState { } }
  return remember(swallowLeftover, noopDragState) {
    Modifier
      .draggable(state = noopDragState, orientation = Orientation.Vertical)
      .nestedScroll(swallowLeftover)
  }
}

/** 把内层滚动容器没消化的纵向位移全部吃掉，一个像素都不往 BottomSheet 上递。 */
private object SwallowVerticalLeftover : NestedScrollConnection {
  override fun onPostScroll(
    consumed: Offset,
    available: Offset,
    source: NestedScrollSource,
  ): Offset = available
}
