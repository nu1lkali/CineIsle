package app.marlboroadvance.mpvex.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.presentation.Screen
import kotlinx.coroutines.delay

/**
 * 设置搜索的「跳到那一项并闪一下」。
 *
 * ⚠️⚠️ **锚点就是那一行在屏幕上显示的文字本身**（v174 起）。
 *
 * v173 用的是脚本生成的另一套标识（`pref_xxx` 这种资源名），索引里存一份、行上挂一份 ——
 * 只要两边有一点不同步，现象就是「有的能定位、有的点了只到页顶」，而且**静默**失效。
 * 最要命的一处：手写索引的条目没有这个字段，合并去重时把带锚点的那条吃掉，锚点就丢了。
 *
 * 现在两侧都从同一个表达式取值（string 资源或字面量标题），索引里不再存第三套键，
 * 于是「索引最终选中了哪一条」与「能不能定位」彻底解耦：
 * 只要那张页面上真有一行叫这个名字，就一定能滚过去并闪。
 *
 * 一行可能显示多种文字（`title = if (flag) A else B` 这种按状态换文案的），
 * 所以锚点接受多个标题 —— 运行时显示哪一句都能对上。
 *
 * 流程：
 * 1. [request] 记下「跳到哪一行」与目标页，然后照常跳页；
 * 2. 目标页在 [SettingsSpotlightReveal] 里滚到那一行（离屏行 LazyColumn 不会组合，
 *    所以先逐步下扫直到它被组合，再交给它自己 `bringIntoView`）；
 * 3. 那一行由 [settingsAnchor] 接管：滚进视野后背景呼吸三次，然后自己撤销请求。
 */
object SettingsSpotlight {
  /** 待定位的行标题；null = 当前没有定位任务。 */
  var pending: String? by mutableStateOf(null)
    private set

  /** 这次定位属于哪张设置页（栈里可能同时组合着两张，别的页不该抢着滚动）。 */
  var owner: Screen? by mutableStateOf(null)
    private set

  /** 正在闪烁的行标题（滚动定位完成之后才置位，避免「还没滚到就已经闪完了」）。 */
  var flashing: String? by mutableStateOf(null)
    private set

  /** 已组合出来的行：标题 → 它的 `bringIntoView` 请求器。 */
  private val targets = HashMap<String, BringIntoViewRequester>()

  /**
   * 已组合出来的行的实际尺寸（标题 → 像素尺寸）。
   *
   * `bringIntoView(rect)` 的 rect 是**行内局部坐标**，要做「上方留白」就得知道行本身多高
   * （留白那块是往行上方扩出来的空矩形）。行自己 `onSizeChanged` 报上来，比在定位侧猜一个
   * 固定行高靠谱 —— 设置行有一行的也有带副标题两行的，高度差一倍。
   */
  private val sizes = HashMap<String, IntSize>()

  fun request(screen: Screen, title: String?) {
    val key = title?.trim().orEmpty()
    pending = key.ifEmpty { null }
    owner = screen
    flashing = null
  }

  fun cancel() {
    pending = null
    owner = null
    flashing = null
  }

  /** 定位完成、该开始闪了。只认当前 pending，避免慢一步的旧请求闪到别的行。 */
  fun beginFlash(title: String) {
    if (pending == title) flashing = title
  }

  /** 闪完收工。只清「确实是自己在闪」的那次请求，别把刚点的新请求也一起清掉。 */
  fun finish(titles: List<String>) {
    val lit = flashing ?: return
    if (titles.contains(lit)) {
      flashing = null
      pending = null
      owner = null
    }
  }

  /** 兜底：标题对不上任何一行时也要撤掉请求，否则下次进这页会莫名其妙地闪。 */
  fun abandon(title: String) {
    if (pending == title) {
      pending = null
      owner = null
    }
  }

  internal fun attach(title: String, requester: BringIntoViewRequester) {
    targets[title] = requester
  }

  internal fun detach(title: String) {
    targets.remove(title)
  }

  internal fun isComposed(title: String): Boolean = targets.containsKey(title)

  internal fun requesterOf(title: String): BringIntoViewRequester? = targets[title]

  internal fun recordSize(title: String, size: IntSize) {
    sizes[title] = size
  }

  internal fun sizeOf(title: String): IntSize? = sizes[title]
}

/**
 * 给一行设置项挂上「可被搜索定位」的能力。
 *
 * 资源版与字面量版最终都收敛到同一批字符串键 —— 与索引里那一条解析出来的标题逐字相同，
 * 所以不存在「两边写法不一致」这类失效。
 *
 * 多个参数用于「按状态换文案」的行：把每种可能的文案都登记上，
 * 用户从哪个文案点进来都能定位（`title = if (x) A else B`）。
 *
 * ⚠️ 用 `drawBehind` 而不是包一层 Box：设置行的对齐 / 分隔线间距都靠父 Column 直接测量，
 *    多套一层布局节点容易出半像素偏差。
 */
@Composable
fun Modifier.settingsAnchor(@StringRes vararg resIds: Int): Modifier {
  // 每个 resId 都在这里解析成「屏幕上那句文字」，与索引侧 `titleRes` 的解析结果一致
  val titles = resIds.map { stringResource(it).trim() }
  return settingsAnchor(titles)
}

@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.settingsAnchor(text: String): Modifier = settingsAnchor(listOf(text))

@Composable
internal fun Modifier.settingsAnchor(titles: List<String>): Modifier {
  val keys = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  if (keys.isEmpty()) return this

  val requester = remember(keys) { BringIntoViewRequester() }
  DisposableEffect(keys) {
    keys.forEach { SettingsSpotlight.attach(it, requester) }
    onDispose { keys.forEach { SettingsSpotlight.detach(it) } }
  }

  val lit = keys.any { it == SettingsSpotlight.flashing }
  val pulse = remember { Animatable(0f) }
  LaunchedEffect(lit) {
    if (!lit) return@LaunchedEffect
    // 三次呼吸：亮 → 暗 ×3，约 1.6s。用 Animatable 而不是无限动画 ——
    // 闪完必须停，否则页面一直有个高亮块在闪，比找不到更烦。
    repeat(3) {
      pulse.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
      pulse.animateTo(0f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
    }
    SettingsSpotlight.finish(keys)
  }

  val highlight = MaterialTheme.colorScheme.primary
  return this
    .drawBehind {
      val alpha = pulse.value
      if (alpha <= 0f) return@drawBehind
      drawRoundRect(
        color = highlight.copy(alpha = 0.22f * alpha),
        cornerRadius = CornerRadius(16.dp.toPx()),
      )
    }
    // 报尺寸：定位侧要拿它算「上方留白」那块空矩形（见 SettingsSpotlightReveal）
    .onSizeChanged { size -> keys.forEach { SettingsSpotlight.recordSize(it, size) } }
}

/**
 * 目标页一侧：把 [SettingsSpotlight.pending] 落实到「滚到那一行」。
 *
 * 为什么不是直接 `animateScrollToItem(index)`：LazyColumn 只能按**下标**滚，
 * 而「某个标题在第几个 item」这件事只有源码知道，写死一张下标表迟早和布局对不上，
 * 且对不上时是**静默**滚到别处。这里的判据是「那一行有没有被组合出来」，布局怎么改都不会错。
 *
 * [listState] 可空：`Folders` / `About` 这类页面没有主 LazyColumn（内容是普通 Column），
 * 所有行天然都在组合里，只需要 `bringIntoView` + 闪，不需要下扫。
 */
@Composable
fun SettingsSpotlightReveal(listState: LazyListState, owner: Screen) {
  SettingsSpotlightReveal(owner, listState)
}

@Composable
fun SettingsSpotlightReveal(owner: Screen) {
  SettingsSpotlightReveal(owner, listState = null)
}

@Composable
private fun SettingsSpotlightReveal(owner: Screen, listState: LazyListState?) {
  val title = SettingsSpotlight.pending
  val target = SettingsSpotlight.owner
  val density = LocalDensity.current
  LaunchedEffect(title, target, listState, owner) {
    val wanted = title ?: return@LaunchedEffect
    if (target !== owner) return@LaunchedEffect

    // 等一帧：让行的 DisposableEffect 把标题登记进来（同一次组合里 effect 顺序不保证）
    withFrameNanos { }

    if (listState != null && !SettingsSpotlight.isComposed(wanted)) {
      // 目标是离屏行 ⇒ 从顶往下扫。步长 0.6 屏：LazyColumn 的预组合窗口只有一屏多一点，
      // 步子迈太大（一屏一次）可能整段跳过目标行，落得「扫到底也没找到」。
      listState.scrollToItem(0)
      var guard = 0
      while (guard++ < 40 && !SettingsSpotlight.isComposed(wanted)) {
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0) {
          withFrameNanos { }
          continue
        }
        val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        listState.scrollBy(viewport * 0.6f)
        // 两帧：滚动落位 + 新行进入组合（只等一帧时 isComposed 往往还是旧值）
        withFrameNanos { }
        withFrameNanos { }
        val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        if (before == after) break // 到底了
      }
    }

    revealRow(wanted, listState, density)
    SettingsSpotlight.beginFlash(wanted)

    // 兜底：标题对不上任何一行（页面级入口 / 页面改版删了那项）也要把请求撤掉，
    // 否则下次进这页会莫名其妙地闪
    delay(3000)
    SettingsSpotlight.abandon(wanted)
  }
}

/**
 * 落位：让那一行的**顶边**停在大约 [LEAD_FRACTION] 屏高的位置，而不是刚好贴住顶端。
 *
 * v174 用的是裸 `bringIntoView()`，语义是「滚最少的距离让它完整露出」——
 * 而上面的下扫是 0.6 屏一步，扫到它时它往往已经贴着顶端了，于是「定位完还得自己往上找一下」。
 *
 * 做法：请求一个**往行上方扩出 `offset` 的空矩形**（`Rect(top = -offset)`）。滚动容器为了让
 * 这块空矩形也可见，只能继续滚，把行顶压到 `offset` 以下 —— 比「先滚再算差多少」稳，
 * 后者要拿行的实时坐标去和视口比，跨嵌套滚动 / 卡片内滚动时很容易算偏。
 *
 * ⚠️ 只往上推、不强行归位：行本来就停在屏幕中间时，这个扩出来的矩形已经完整可见，
 *    `bringIntoView` 是 no-op，不会把画面硬拽一下。
 * ⚠️ 行本身很高时（卡片式）要收敛：留白 + 行高不能超过 85% 视口，否则顶放对了、底部被切。
 */
private suspend fun revealRow(
  title: String,
  listState: LazyListState?,
  density: Density,
) {
  val requester = SettingsSpotlight.requesterOf(title) ?: return
  val size = SettingsSpotlight.sizeOf(title)
  if (size == null || size.width <= 0 || size.height <= 0) {
    requester.bringIntoView()
    return
  }

  val viewport = listState?.layoutInfo?.viewportSize?.height ?: 0
  var offset = if (viewport > 0) {
    viewport * LEAD_FRACTION
  } else {
    with(density) { LEAD_FALLBACK.toPx() }
  }
  if (viewport > 0) {
    offset = offset.coerceIn(0f, (viewport * 0.85f - size.height).coerceAtLeast(0f))
  }

  requester.bringIntoView(
    Rect(
      left = 0f,
      top = -offset,
      right = size.width.toFloat(),
      bottom = size.height.toFloat(),
    ),
  )
}

/** 定位后行顶上方留白 = 视口高度的 1/5（够看清上下文，又不至于把行推到屏幕中间）。 */
private const val LEAD_FRACTION = 0.2f

/** 没有视口信息时（`About` / `Folders` 这类普通 Column 页面）用的固定留白。 */
private val LEAD_FALLBACK = 120.dp
