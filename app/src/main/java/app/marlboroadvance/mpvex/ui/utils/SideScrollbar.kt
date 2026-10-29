package app.marlboroadvance.mpvex.ui.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import kotlinx.coroutines.channels.Channel
import org.koin.compose.koinInject
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 侧边可拖动滚动条的**统一外壳** —— App 内所有长列表要显示滚动条，
 * 都必须套这两个壳（网格套 [LazyGridSideScrollbar]、列表套 [LazyListSideScrollbar]）。
 * 壳负责三件事：
 *
 *  1. **全局开关**：读「设置 → 外观 → 侧边滚动条」
 *     （[BrowserPreferences.sideScrollbarEnabled]）。关掉时**不套壳**、直接渲染内容。
 *  2. **出现时机**：回到顶部（露出来会挡第一屏内容）或条目不够多（没有滚动需求）时
 *     淡出。
 *  3. **外观 / 拖动映射**：见下方「⚠️ 为什么不再用 nanihadesuka」。
 *
 * ## ⚠️⚠️ 为什么不再用 nanihadesuka LazyColumnScrollbar（v178 自绘）
 *
 * 2.2.0 的拖动实现在 `LazyGridStateController.setScrollOffset`（javap 核验），每收到一次
 * 拖动 delta 就 `launch` 一个协程，协程里**连做两次** `scrollToItem`：
 *
 * ```kotlin
 * state.scrollToItem(index, 0)        // ① 先跳到该行行首，只为量出行高
 * state.scrollToItem(index, remainder * itemHeight)  // ② 再落到真实位置
 * ```
 *
 * ①是一次**最多一整行高**的瞬间回跳（网格里就是一张海报的高度），而 delta 是逐帧来的、
 * 协程之间还会交错（前一个协程的 ② 可能落在后一个协程的 ① 之后）。**慢慢拖**的时候
 * 每帧的真实位移只有几像素，却夹着一次整行回跳 ⇒ 画面来回抽搐；拖得快时一帧跨好几行，
 * 回跳被跨过去，反而看不出来。这就是「滑得慢画面很抖」的根因，且与业务无关 ——
 * 所有套这个库的页面（Emby 库里三个视图、聚合库、网络浏览、本地文件、播放列表…）都有。
 *
 * 自绘版把三件事一起改掉：
 *  - **只发一次目标**：拖动只算出「目标位置」，经 [Channel.CONFLATED] 交给单一消费者
 *    执行 `scrollToItem`，永不出现两个协程互相覆盖（旧目标被新目标直接顶掉）。
 *  - **尺度自洽**：滑块位置与拖动目标用**同一个** [ThumbGeometry]（总单位数 / 视口单位数
 *    / 可滚动量），拖动时滑块**只听手指**（`dragPosition`），绝不用滚动结果反推滑块位置，
 *    所以不存在「跳 → 反推 → 再跳」的反馈环。
 *  - **单位内插值**：网格按「行」、列表按「条目」为单位，单位内再按像素插值
 *    （`scrollToItem(index, 行内偏移)`），因此慢慢拖是**连续**移动，不是一格一格跳。
 *
 * ## 库 API 事实（保留备查，LazyColumnScrollbar 2.2.0 默认值）
 *  - `ScrollbarSettings` 默认：`side=End`、`scrollbarPadding=8.dp`、`thumbThickness=6.dp`、
 *    `thumbShape=CircleShape`、`thumbMinLength=0.1`（**占轨道高度的比例**）、`thumbMaxLength=1`。
 *    自绘版沿用这套数值，替换后外观与旧版一致。
 *  - 库的 `enabled=false` 仍会布置拖动热区，所以壳的「不套壳」才是零残留。
 */

/** 条目少于此数不显示滚动条：一屏多一点点的列表，滚动条只会添乱 */
private const val MIN_ITEMS_FOR_SCROLLBAR = 20

/** 滚动条淡入淡出时长（ms） */
private const val SCROLLBAR_ALPHA_TWEEN_MS = 200

/** 滑块最短长度（占轨道高度比例）—— 与库的 thumbMinLength 默认值一致 */
private const val THUMB_MIN_LENGTH_FRACTION = 0.1f

/** 滑块粗细 —— 与库的 thumbThickness 默认值一致 */
private val THUMB_THICKNESS = 6.dp

/** 滑块距右边缘的距离 —— 与库的 scrollbarPadding 默认值一致 */
private val SCROLLBAR_PADDING = 8.dp

/** 触摸热区宽度（比滑块粗得多，方便捏住；不在滑块上的触摸会**原样放行**给列表） */
private val THUMB_TOUCH_WIDTH = 24.dp

/** 手指落在滑块上下这么近也算「捏住滑块」 */
private val GRAB_SLOP = 12.dp

/**
 * 网格版外壳：给 `LazyVerticalGrid`（含 stickyHeader 的年份时间轴）套侧边滚动条。
 *
 * 用法：
 * ```kotlin
 * LazyGridSideScrollbar(state = gridState) {
 *   LazyVerticalGrid(state = gridState, ...) { ... }
 * }
 * ```
 */
@Composable
fun LazyGridSideScrollbar(
  state: LazyGridState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val enabled by koinInject<BrowserPreferences>().sideScrollbarEnabled.collectAsState()
  // ⚠️ 两个判据都必须走 derivedStateOf：裸读 firstVisibleItemIndex 会让**每帧滚动**
  //    都重组一次本壳。derivedStateOf 只在布尔值真正翻转时才通知。
  val isAtTop by remember {
    derivedStateOf {
      state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems by remember {
    derivedStateOf { state.layoutInfo.totalItemsCount > MIN_ITEMS_FOR_SCROLLBAR }
  }
  if (!enabled) {
    content()
  } else {
    val alpha by animateFloatAsState(
      targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
      animationSpec = tween(durationMillis = SCROLLBAR_ALPHA_TWEEN_MS),
      label = "scrollbarAlpha",
    )
    Box(modifier = modifier.fillMaxSize()) {
      content()
      if (hasEnoughItems) {
        SideScrollbarThumb(units = remember(state) { GridScrollUnits(state) }, alpha = alpha)
      }
    }
  }
}

/**
 * 列表版外壳：给 `LazyColumn`（如网络浏览的分 section 列表）套侧边滚动条。
 */
@Composable
fun LazyListSideScrollbar(
  state: LazyListState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val enabled by koinInject<BrowserPreferences>().sideScrollbarEnabled.collectAsState()
  val isAtTop by remember {
    derivedStateOf {
      state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems by remember {
    derivedStateOf { state.layoutInfo.totalItemsCount > MIN_ITEMS_FOR_SCROLLBAR }
  }
  if (!enabled) {
    content()
  } else {
    val alpha by animateFloatAsState(
      targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
      animationSpec = tween(durationMillis = SCROLLBAR_ALPHA_TWEEN_MS),
      label = "scrollbarAlpha",
    )
    Box(modifier = modifier.fillMaxSize()) {
      content()
      if (hasEnoughItems) {
        SideScrollbarThumb(units = remember(state) { ListScrollUnits(state) }, alpha = alpha)
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 滚动尺度抽象：把「网格」和「列表」抹平成同一套「单位坐标」
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 滚动条的**单位坐标**：网格一行算一个单位、列表一条算一个单位。
 *
 * [firstPosition] 是「首个可见单位 + 单位内已滚过的比例」（如 3.42 表示第 3 行滚过去 42%），
 * 因为显示与跳转都用它，两者天然互为逆运算 —— 这是不抖的前提。
 */
private interface ScrollUnits {
  /** 总单位数（网格=行数，列表=条目数） */
  fun totalUnits(): Int

  /** 一个单位的高度（px），用于单位内插值 */
  fun unitHeightPx(): Int

  /** 首个可见单位的坐标（含单位内比例） */
  fun firstPosition(): Float

  /** 跳到单位坐标 [position] */
  suspend fun seekTo(position: Float)
}

/** 网格：单位 = 行（一行 [columns] 个条目） */
private class GridScrollUnits(private val state: LazyGridState) : ScrollUnits {

  /**
   * 列数。用可见条目的最大 column 反推，而不是靠调用方传：
   * 外壳的调用点有 Fixed(1)/Fixed(3)/Fixed(n)/Adaptive 各种写法，传参容易写错。
   * 整行跨列的头部（`GridItemSpan(maxLineSpan)`）column 也是 0，不影响取最大值。
   */
  private fun columns(): Int {
    var maxColumn = 0
    for (item in state.layoutInfo.visibleItemsInfo) {
      if (item.column > maxColumn) maxColumn = item.column
    }
    return maxColumn + 1
  }

  override fun totalUnits(): Int {
    val columns = columns()
    return ((state.layoutInfo.totalItemsCount + columns - 1) / columns).coerceAtLeast(1)
  }

  /**
   * 行高。跳过第 0 行是为了躲开「整行跨列的头部」（`CinePosterGrid(header=)` 那种，
   * 它比普通海报行高得多，拿它当行高会让单位内插值错得离谱）。
   */
  override fun unitHeightPx(): Int {
    val visible = state.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 1
    val bodyHeights = visible.filter { it.row > 0 }.map { it.size.height }
    return (bodyHeights.maxOrNull() ?: visible.maxOf { it.size.height }).coerceAtLeast(1)
  }

  override fun firstPosition(): Float {
    val columns = columns()
    val index = state.firstVisibleItemIndex
    val row = index / columns
    // 只有行首条目才谈得上「行内偏移」；如果首条目落在行中间（理论上不会，非满列时才可能），
    // 退化成整行，保证坐标单调。
    val intra = if (index % columns == 0) {
      state.firstVisibleItemScrollOffset / unitHeightPx().toFloat()
    } else {
      0f
    }
    return row + intra
  }

  override suspend fun seekTo(position: Float) {
    val columns = columns()
    val row = floor(position).toInt().coerceAtLeast(0)
    state.scrollToItem(row * columns, ((position - row) * unitHeightPx()).roundToInt())
  }
}

/** 列表：单位 = 条目 */
private class ListScrollUnits(private val state: LazyListState) : ScrollUnits {

  override fun totalUnits(): Int =
    state.layoutInfo.totalItemsCount.coerceAtLeast(1)

  /**
   * 列表条目高度不一致（标题 / 分组头 / 普通行），取可见条目里的最大值当「一个单位」：
   * 只为把单位内比例压到 0..1，取平均会让长条目把坐标推出单位区间。
   */
  override fun unitHeightPx(): Int {
    val visible = state.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 1
    return visible.maxOf { it.size }.coerceAtLeast(1)
  }

  override fun firstPosition(): Float =
    state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / unitHeightPx().toFloat()

  override suspend fun seekTo(position: Float) {
    val index = floor(position).toInt().coerceAtLeast(0)
    state.scrollToItem(index, ((position - index) * unitHeightPx()).roundToInt())
  }
}

/** 一次算好、三处共用（滑块长度 / 滑块位置 / 拖动目标），保证互逆 */
private class ThumbGeometry(
  /** 滑块长度（px） */
  val thumbHeight: Float,
  /** 滑块可移动的距离（px）= 轨道高 - 滑块长 */
  val travel: Float,
  /** 可滚动的单位量（总单位 - 视口单位） */
  val maxScroll: Float,
)

private fun ScrollUnits.geometry(trackHeight: Float, minThumbHeight: Float): ThumbGeometry {
  val total = totalUnits().toFloat().coerceAtLeast(1f)
  // 视口能装下多少个单位 —— 决定滑块该多长
  val viewportUnits = (trackHeight / unitHeightPx().toFloat()).coerceIn(0.1f, total)
  val thumbHeight = (trackHeight * viewportUnits / total).coerceIn(
    minThumbHeight.coerceAtMost(trackHeight),
    trackHeight,
  )
  return ThumbGeometry(
    thumbHeight = thumbHeight,
    travel = (trackHeight - thumbHeight).coerceAtLeast(1f),
    // 分母兜底成极小正数：内容刚好一屏时 maxScroll≈0，位置恒 0，不会除零
    maxScroll = (total - viewportUnits).coerceAtLeast(0.0001f),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 滑块本体：自绘 + 只认手指的拖动
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BoxScope.SideScrollbarThumb(units: ScrollUnits, alpha: Float) {
  val color = MaterialTheme.colorScheme.primary
  // 拖动时滑块位置**只来自手指**（dragPosition ∈ 0..1 的 travel 比例），
  // 绝不读滚动结果 —— 这是与旧库最大的区别，也是不抖的根因。
  var dragging by remember { mutableStateOf(false) }
  var dragPosition by remember { mutableFloatStateOf(0f) }
  // 合并式发令：拖动中每帧都可能发目标，只保留最后一个，天然杜绝旧目标盖新目标
  val seeks = remember(units) { Channel<Float>(capacity = Channel.CONFLATED) }
  LaunchedEffect(units) {
    for (target in seeks) units.seekTo(target)
  }

  Canvas(
    modifier = Modifier
      .align(Alignment.CenterEnd)
      .fillMaxHeight()
      .width(THUMB_TOUCH_WIDTH)
      .pointerInput(units) {
        val grabSlopPx = GRAB_SLOP.toPx()
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val trackHeight = size.height.toFloat()
          val minThumb = THUMB_MIN_LENGTH_FRACTION * trackHeight
          val geometry = units.geometry(trackHeight, minThumb)
          val currentTop = if (dragging) {
            dragPosition * geometry.travel
          } else {
            (units.firstPosition() / geometry.maxScroll).coerceIn(0f, 1f) * geometry.travel
          }
          // 手指不在滑块上：**一个事件都不消费**，让下面的列表照常滚动 / 点击。
          // （旧库把整条轨道都当热区，右边缘那一竖条摸得到却点不到卡片。）
          if (down.position.y < currentTop - grabSlopPx ||
            down.position.y > currentTop + geometry.thumbHeight + grabSlopPx
          ) {
            return@awaitEachGesture
          }
          down.consume()
          dragging = true
          dragPosition = (currentTop / geometry.travel).coerceIn(0f, 1f)
          seeks.trySend(dragPosition * geometry.maxScroll)
          val pointerId = down.id
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
              change.consume()
              break
            }
            val dy = change.positionChange().y
            // 无论本次位移是否为 0 都要消费：消费标记是按事件重置的，
            // 只有每个 move 都标记，下面那层 LazyGrid 的拖拽检测才会立刻判「已被消费」而退出，
            // 否则滑块和列表会同时跟手（就是另一场抖）。
            change.consume()
            if (dy != 0f) {
              dragPosition = (dragPosition + dy / geometry.travel).coerceIn(0f, 1f)
              seeks.trySend(dragPosition * geometry.maxScroll)
            }
          }
          dragging = false
        }
      },
  ) {
    val trackHeight = size.height
    if (trackHeight <= 0f) return@Canvas
    val minThumb = THUMB_MIN_LENGTH_FRACTION * trackHeight
    val geometry = units.geometry(trackHeight, minThumb)
    val top = if (dragging) {
      dragPosition * geometry.travel
    } else {
      (units.firstPosition() / geometry.maxScroll).coerceIn(0f, 1f) * geometry.travel
    }
    val thickness = THUMB_THICKNESS.toPx()
    val padding = SCROLLBAR_PADDING.toPx()
    drawRoundRect(
      color = color.copy(alpha = (if (dragging) alpha else 0.4f * alpha).coerceIn(0f, 1f)),
      topLeft = Offset(x = size.width - padding - thickness, y = top),
      size = Size(width = thickness, height = geometry.thumbHeight),
      // CircleShape 的等价物：圆角取半宽（长度不足半宽时取一半长度，不会画成歪的）
      cornerRadius = CornerRadius(thickness / 2f),
    )
  }
}
