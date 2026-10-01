package app.marlboroadvance.mpvex.ui.player.feed

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 视界流的手势分区。
 *
 * ── 分工原则：翻页归 Pager，本层只做「Pager 做不了的三件事」──
 * 竖直滑动到底算翻页还是算调亮度 / 音量，由**按下那一瞬间手指的横坐标**决定：
 *
 * | 区域       | 竖屏（绝对宽度） | 横屏（绝对宽度） | 竖滑的效果 |
 * |------------|------------------|------------------|------------|
 * | 左侧边缘带 | 48dp             | 72dp             | 调亮度     |
 * | 中间带     | 其余             | 其余             | **翻页**   |
 * | 右侧边缘带 | 48dp             | 72dp             | 调音量     |
 *
 * 关键在「中间带怎么办」：**本层完全不碰，直接退出**。
 * Pager 的 `userScrollEnabled` 保持默认的 true，滚动、速度投掷、吸附全部由它原生完成
 * —— 抖音那种「嗖」一下甩过去的手感，来源就是原生 scrollable 的速度投掷，手写做不到。
 *
 * ── 边缘带为什么用绝对 dp，而不是原版的百分比 ──
 * Flutter 原版是「左 15% / 右 25% 屏宽」。右侧 25% 在 1080 宽的屏上接近 270px ——
 * 右手持机的拇指自然落点就在里面，于是**想翻页的一划经常被判成调音量**。
 * 这两条带子是「偶尔才用一次」的功能，不该去和「每一次滑动都在用」的翻页抢地盘，
 * 所以收窄成固定 48dp：够得着（约一个指尖宽），但不会挡路。
 *
 * ── 认领之后必须 consume（这是「误触收藏」的真凶）──
 * `detectTapGestures` 判断「是不是点击」只看两件事：**事件是否被消费**、**指针是否滑出
 * 边界**——它根本不看移动距离。而本层此前从头到尾一次都没消费过事件，点击层又是全屏
 * （永远不会出界），于是**每一次滑动在点击层眼里都是一次「点击」**：
 * 单击 → 播放/暂停来回闪；连着两下滑 → 正好凑成「双击」→ 收藏被点掉。
 *
 * 现在：本层认领亮度 / 音量 / 进度时消费；中间带交给 Pager，Pager 一开始拖就会消费。
 * 两条路都会让点击层的判定自动作废，滑动再也不可能被当成点击。
 *
 * ── 为什么要走 Initial pass ──
 * Main pass 的传递顺序是「子 → 父」。本层是 Pager 与页内点击层的**父节点**，
 * 走 Initial pass（父 → 子）才能在子节点动手之前先看清这一帧的位移；我们只在认领时
 * 消费，所以点击照样能被页内的点击层收到。
 */
enum class VerticalZone {
  /** 左侧：亮度 */
  BRIGHTNESS,

  /** 中间：翻页（交给 Pager） */
  PAGE,

  /** 右侧：音量 */
  VOLUME,
}

/** 一次触摸最终被判成了什么 */
private enum class GestureIntent {
  /** 还没滑够，是拖动还是点击尚不明 */
  PENDING,

  SEEK,
  BRIGHTNESS,
  VOLUME,

  /** 判给 Pager 了 —— 本层退出，一点都不干预 */
  PAGER,
}

internal object FeedGestureZones {
  /** 竖屏下边缘带（亮度 / 音量）的绝对宽度（dp） */
  const val EDGE_DP_PORTRAIT = 48f

  /** 横屏下边缘带的绝对宽度（dp）：横屏更宽，同一条带子按手指行程给大一点 */
  const val EDGE_DP_LANDSCAPE = 72f

  /**
   * 竖直拖动的灵敏度：拖过一整屏高度的变化量 = [VERTICAL_GAIN] 倍满量程。
   *
   * 取 1.5 是为了让「小幅滑动也能调到头」：短视频场景用户常常只动一小段拇指，
   * 1:1 的话一趟下来才百分之十几，会觉得迟钝。
   */
  const val VERTICAL_GAIN = 1.5f

  /**
   * 认领边缘带所需的「方向优势」：竖直位移至少是水平位移的这么多倍。
   *
   * 斜着划的意图不清楚，宁可判给翻页（Pager 会按自己的轴锁定处理），也不要
   * 把一次想翻页的滑动变成调音量。
   */
  const val EDGE_DIRECTION_BIAS = 1.25f

  /** 认领横向拖进度所需的方向优势，理由同上 */
  const val SEEK_DIRECTION_BIAS = 1.25f

  /** 左右边缘热区宽度（dp）：这一段留给安卓的侧滑返回 */
  const val EDGE_BACK_ZONE_DP = 42f

  /** 在热区内向内划过多少（dp）算真的要返回 */
  const val EDGE_BACK_TRIGGER_DP = 64f

  /** 横拖行程里「精调区」占屏宽的比例 */
  const val FINE_SEEK_ZONE_RATIO = 0.2f

  /** 精调区对应的毫秒数上限 */
  const val FINE_SEEK_RANGE_MS = 10_000L

  /**
   * 精调区对应的毫秒数下限。
   *
   * 原版直接取 `min(10 秒, 总时长 × 10%)`，于是 15 秒的短片算出来只有 1.5 秒 ——
   * 而精调区照样吃掉 20% 行程，等于手指划过一大段进度才动 1.5 秒，明显「钝」。
   * 给个 3 秒下限就不会出现这种极端值。
   */
  const val FINE_SEEK_RANGE_MIN_MS = 3_000L
}

/**
 * 手势要做的事最终落到这里，由 Activity 实现。
 *
 * 不直接在手势层动手的原因：亮度要改 WindowManager.LayoutParams，音量要动
 * AudioManager —— 这两样在 @Composable 里拿不稳，放 Activity 侧最踏实。
 *
 * 注：**翻页不在这里** —— 那是 VerticalPager 自己的事。
 */
internal interface FeedGestureHost {
  /** 顶部让位高度（px）：状态栏 + 一点余量，留给下拉通知栏 */
  val topInsetPx: Float

  /** 底部让位高度（px）：手势条 / 导航栏，留给上滑回桌面 */
  val bottomInsetPx: Float

  val landscape: Boolean

  val durationMs: Long
  val positionMs: Long

  val brightnessFraction: Float
  val volumeFraction: Float

  fun setBrightness(fraction: Float)
  fun setVolume(fraction: Float)

  /** 显示对应的竖向指示条；[kind] 传 null 表示收起 */
  fun showVerticalIndicator(kind: VerticalZone?, fraction: Float)

  /** 开始拖进度：此时应当暂停影片并记下起始位置 */
  fun beginSeek(startMs: Long)

  /** 拖动过程中的预览位置 */
  fun previewSeek(targetMs: Long)

  /** 松手：真正落到这个位置，并恢复播放 */
  fun commitSeek(targetMs: Long)

  /** 从屏幕边缘向内划 —— 视作返回 */
  fun requestEdgeBack()
}

/**
 * 三条手势的界面状态。
 *
 * 手势层自己不画 UI（它只是个 Modifier），要显示的量放在这里由 Compose 侧读取。
 */
internal class FeedGestureUi {

  /** 正在调节的竖向指示条：亮度或音量 + 当前值 */
  data class Indicator(val kind: VerticalZone, val fraction: Float)

  /** 没有在调的时候为 null */
  var indicator by mutableStateOf<Indicator?>(null)

  /** 本次拖进度的起点，用来算卡片上的 ± 偏移 */
  var seekStartMs by mutableLongStateOf(0L)

  /** 拖进度的预览目标；-1 表示当前没在拖进度 */
  var seekTargetMs by mutableLongStateOf(-1L)

  fun showIndicator(kind: VerticalZone?, fraction: Float) {
    indicator = if (kind == null || kind == VerticalZone.PAGE) null else Indicator(kind, fraction)
  }

  fun endSeek() {
    seekTargetMs = -1L
  }
}

/**
 * 给整页叠一层手势：亮度 / 音量 / 拖进度三条边缘手势。
 *
 * **本层不接管翻页** —— 中间带一律原样放行给 VerticalPager，由它原生滚动。
 *
 * @param density 屏幕密度，用来把 dp 常量换算成像素
 */
internal fun Modifier.feedGestures(
  host: FeedGestureHost,
  density: Float,
): Modifier = pointerInput(host, density) {
  val slop = viewConfiguration.touchSlop.toFloat()
  val edgeBackZonePx = FeedGestureZones.EDGE_BACK_ZONE_DP * density
  val edgeBackTriggerPx = FeedGestureZones.EDGE_BACK_TRIGGER_DP * density

  awaitEachGesture {
    // 走 Initial pass + 允许已被消费的 down：本层是点击层的父节点，只有走父→子的
    // Initial pass 才能先看到事件；点击层在 Main pass 里会消费掉 down，若这里要求
    // 「未被消费」，整套边缘手势会彻底失效。
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val widthPx = size.width.toFloat()
    val heightPx = size.height.toFloat()
    if (widthPx <= 0f || heightPx <= 0f) return@awaitEachGesture

    val startX = down.position.x
    val startY = down.position.y

    // ── 让位区：顶 / 底这两条带完全不参与，还给系统手势 ──
    if (startY <= host.topInsetPx || startY >= heightPx - host.bottomInsetPx) {
      return@awaitEachGesture
    }

    val zone = zoneOf(startX, widthPx, host.landscape, density)
    val baseFraction = when (zone) {
      VerticalZone.BRIGHTNESS -> host.brightnessFraction
      VerticalZone.VOLUME -> host.volumeFraction
      VerticalZone.PAGE -> 0f
    }
    val seekStartMs = host.positionMs

    var edgeBack = false
    var intent = GestureIntent.PENDING
    var targetMs = seekStartMs

    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      // 还没判出意图就抬手 = 这是点击，本层不消费，交回页内点击层
      if (!change.pressed) break

      val dx = change.position.x - startX
      val dy = change.position.y - startY

      if (intent == GestureIntent.PENDING) {
        if (hypot(dx, dy) <= slop) continue

        val adx = abs(dx)
        val ady = abs(dy)
        intent = when {
          // 边缘带 + 竖直明显占优 → 亮度 / 音量
          zone != VerticalZone.PAGE && ady >= adx * FeedGestureZones.EDGE_DIRECTION_BIAS ->
            if (zone == VerticalZone.BRIGHTNESS) GestureIntent.BRIGHTNESS else GestureIntent.VOLUME

          // 水平明显占优 → 拖进度
          adx >= ady * FeedGestureZones.SEEK_DIRECTION_BIAS -> GestureIntent.SEEK

          // 其余（含中间带的竖滑、方向不明的斜划）一律让给 Pager
          else -> GestureIntent.PAGER
        }

        when (intent) {
          GestureIntent.SEEK -> {
            edgeBack = startX <= edgeBackZonePx || startX >= widthPx - edgeBackZonePx
            if (!edgeBack) host.beginSeek(seekStartMs)
          }

          GestureIntent.BRIGHTNESS ->
            host.showVerticalIndicator(VerticalZone.BRIGHTNESS, baseFraction)

          GestureIntent.VOLUME ->
            host.showVerticalIndicator(VerticalZone.VOLUME, baseFraction)

          // ⚠️ 直接退出，**一个字节都不消费**：这一划从头到尾都是 Pager 的。
          GestureIntent.PAGER -> return@awaitEachGesture

          GestureIntent.PENDING -> Unit
        }
      }

      // ── 认领之后消费：点击层据此判定「这不是点击」，翻页也不会被拖走 ──
      when (intent) {
        GestureIntent.SEEK -> {
          change.consume()
          if (edgeBack) {
            val inward = if (startX <= edgeBackZonePx) dx else -dx
            if (inward >= edgeBackTriggerPx) {
              host.requestEdgeBack()
              return@awaitEachGesture
            }
          } else {
            val totalMs = host.durationMs
            if (totalMs > 0L) {
              targetMs = (seekStartMs + seekDeltaMs(dx, totalMs, widthPx)).coerceIn(0L, totalMs)
              host.previewSeek(targetMs)
            }
          }
        }

        GestureIntent.BRIGHTNESS -> {
          change.consume()
          val fraction = (baseFraction + deltaFraction(dy, heightPx)).coerceIn(0f, 1f)
          host.setBrightness(fraction)
          host.showVerticalIndicator(VerticalZone.BRIGHTNESS, fraction)
        }

        GestureIntent.VOLUME -> {
          change.consume()
          val fraction = (baseFraction + deltaFraction(dy, heightPx)).coerceIn(0f, 1f)
          host.setVolume(fraction)
          host.showVerticalIndicator(VerticalZone.VOLUME, fraction)
        }

        // PAGER 在这里不可达（上面已经 return），列出来是为了让 when 穷尽
        GestureIntent.PAGER, GestureIntent.PENDING -> Unit
      }
    }

    // ── 松手收尾 ──
    when (intent) {
      GestureIntent.SEEK -> if (!edgeBack) host.commitSeek(targetMs)
      GestureIntent.BRIGHTNESS, GestureIntent.VOLUME -> host.showVerticalIndicator(null, 0f)
      GestureIntent.PAGER, GestureIntent.PENDING -> Unit
    }
  }
}

/** 按下的横坐标落在哪条带里 */
internal fun zoneOf(
  x: Float,
  widthPx: Float,
  landscape: Boolean,
  density: Float,
): VerticalZone {
  if (widthPx <= 0f) return VerticalZone.PAGE
  val edge = if (landscape) {
    FeedGestureZones.EDGE_DP_LANDSCAPE * density
  } else {
    FeedGestureZones.EDGE_DP_PORTRAIT * density
  }
  return when {
    x <= edge -> VerticalZone.BRIGHTNESS
    x >= widthPx - edge -> VerticalZone.VOLUME
    else -> VerticalZone.PAGE
  }
}

/** 竖直位移 → 亮度 / 音量的变化量（上滑为增） */
private fun deltaFraction(dy: Float, heightPx: Float): Float {
  if (heightPx <= 0f) return 0f
  // dy 向上滑动时为负，取反之后才是「上滑 = 增加」
  return (-dy / heightPx) * FeedGestureZones.VERTICAL_GAIN
}

/**
 * 横拖位移 → 时间偏移，两段灵敏度。
 *
 * - 起步那一小段（占屏宽 [FeedGestureZones.FINE_SEEK_ZONE_RATIO]）是精调区，只走
 *   几秒，用来对准台词或某个画面；
 * - 剩下的行程线性铺满整片，一次划到底就是一整部片子。
 */
private fun seekDeltaMs(dx: Float, totalMs: Long, widthPx: Float): Long {
  if (widthPx <= 0f || totalMs <= 0L) return 0L
  val fineWidth = widthPx * FeedGestureZones.FINE_SEEK_ZONE_RATIO
  val fineMs = FeedGestureZones.FINE_SEEK_RANGE_MS
    .coerceAtMost((totalMs * 0.1).toLong().coerceAtLeast(FeedGestureZones.FINE_SEEK_RANGE_MIN_MS))
    .toDouble()
  val absDx = abs(dx)
  if (absDx <= fineWidth) return ((dx / fineWidth) * fineMs).toLong()

  val restMs = (totalMs - fineMs).coerceIn(0.0, totalMs.toDouble())
  val restWidth = (widthPx - fineWidth).coerceAtLeast(1f)
  val sign = if (dx < 0f) -1.0 else 1.0
  return (sign * (fineMs + (absDx - fineWidth) / restWidth * restMs)).toLong()
}
