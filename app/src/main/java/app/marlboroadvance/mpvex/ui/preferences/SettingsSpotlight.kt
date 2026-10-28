package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.presentation.Screen
import kotlinx.coroutines.delay

/**
 * 设置搜索的「跳到那一项并闪一下」（v173）。
 *
 * 背景：搜索命中后只会把用户丢到那张设置页的**顶部** —— Emby 那几节动辄一整屏，
 * 用户还得自己找。这里把「哪一项」变成可执行的定位请求：
 *
 * 1. [request] 记下目标锚点与目标页，然后照常跳页；
 * 2. 目标页在 [SettingsSpotlightReveal] 里滚到那一行（离屏行 LazyColumn 不会组合，
 *    所以先逐步下扫直到它被组合，再交给它自己 `bringIntoView`）；
 * 3. 那一行由 [settingsAnchor] 修饰符接管：滚进视野后背景呼吸三次，然后自己撤销请求。
 *
 * ⚠️ 锚点字符串由 `tools/gen_pref_index.py` 同时写进「索引」和「行修饰符」两处，
 *    两边必须一致 —— 改完设置项重跑脚本，别只手改一边。
 */
object SettingsSpotlight {
  /** 待定位的锚点；null = 当前没有定位任务。 */
  var pending: String? by mutableStateOf(null)
    private set

  /** 这次定位属于哪张设置页（栈里可能同时组合着两张，别的页不该抢着滚动）。 */
  var owner: Screen? by mutableStateOf(null)
    private set

  /** 正在闪烁的锚点（滚动定位完成之后才置位，避免「还没滚到就已经闪完了」）。 */
  var flashing: String? by mutableStateOf(null)
    private set

  /** 已组合出来的行：锚点 → 它的 `bringIntoView` 请求器。 */
  private val targets = HashMap<String, BringIntoViewRequester>()

  fun request(screen: Screen, anchor: String?) {
    pending = anchor
    owner = screen
    flashing = null
  }

  fun cancel() {
    pending = null
    owner = null
    flashing = null
  }

  /** 定位完成、该开始闪了。只认当前 pending，避免慢一步的旧请求闪到别的行。 */
  fun beginFlash(anchor: String) {
    if (pending == anchor) flashing = anchor
  }

  /** 闪完收工：清掉两个状态，下次进页面不会再闪。 */
  fun finish(anchor: String) {
    if (flashing == anchor) flashing = null
    if (pending == anchor) {
      pending = null
      owner = null
    }
  }

  internal fun attach(anchor: String, requester: BringIntoViewRequester) {
    targets[anchor] = requester
  }

  internal fun detach(anchor: String) {
    targets.remove(anchor)
  }

  internal fun isComposed(anchor: String): Boolean = targets.containsKey(anchor)

  internal fun requesterOf(anchor: String): BringIntoViewRequester? = targets[anchor]
}

/**
 * 给一行设置项挂上「可被搜索定位」的能力。
 *
 * 只做两件事：登记自己的 `bringIntoView` 请求器（离屏行不会被组合，所以「是否已组合」
 * 就是「是否在屏幕附近」的判据），以及在被点亮时画一层呼吸背景。
 *
 * ⚠️ 用 `size` 无关的 `drawBehind`（不是包一层 Box）：包 Box 会多一层布局节点，
 *    设置行的对齐 / 分隔线间距都靠父 Column 直接测量，多套一层容易出半像素偏差。
 */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.settingsAnchor(anchor: String): Modifier {
  val requester = remember(anchor) { BringIntoViewRequester() }
  DisposableEffect(anchor) {
    SettingsSpotlight.attach(anchor, requester)
    onDispose { SettingsSpotlight.detach(anchor) }
  }

  val lit = SettingsSpotlight.flashing == anchor
  val pulse = remember { Animatable(0f) }
  LaunchedEffect(lit) {
    if (!lit) return@LaunchedEffect
    // 三次呼吸：亮 → 暗 ×3，约 1.6s。用 Animatable 而不是无限动画 ——
    // 闪完必须停，否则页面一直有个高亮块在闪，比找不到更烦。
    repeat(3) {
      pulse.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
      pulse.animateTo(0f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
    }
    SettingsSpotlight.finish(anchor)
  }

  val highlight = MaterialTheme.colorScheme.primary
  return this.drawBehind {
    val alpha = pulse.value
    if (alpha <= 0f) return@drawBehind
    drawRoundRect(
      color = highlight.copy(alpha = 0.22f * alpha),
      cornerRadius = CornerRadius(16.dp.toPx()),
    )
  }
}

/**
 * 目标页一侧：把 [SettingsSpotlight.pending] 落实到「滚到那一行」。
 *
 * 为什么不是直接 `animateScrollToItem(index)`：LazyColumn 只能按**下标**滚，
 * 而「某个标题在第几个 item」这件事只有源码知道，写死一张下标表迟早和布局对不上。
 * 这里换成「逐步下扫直到那一行被组合」—— 判据是行自己登记的锚点，布局怎么改都不会错。
 */
@Composable
fun SettingsSpotlightReveal(listState: LazyListState, owner: Screen) {
  val anchor = SettingsSpotlight.pending
  val target = SettingsSpotlight.owner
  LaunchedEffect(anchor, target, listState, owner) {
    val wanted = anchor ?: return@LaunchedEffect
    if (target !== owner) return@LaunchedEffect

    // 等一帧：让行的 DisposableEffect 把锚点登记进来（同一次组合里 effect 顺序不保证）
    withFrameNanos { }

    if (!SettingsSpotlight.isComposed(wanted)) {
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

    // 精确落位：行自己算「需要滚多少才能完整露出」（可见时是 no-op）
    SettingsSpotlight.requesterOf(wanted)?.bringIntoView()
    SettingsSpotlight.beginFlash(wanted)

    // 兜底：锚点漂了（比如那一行被删了）也要把请求撤掉，否则下次进这页会莫名其妙地闪
    delay(3000)
    SettingsSpotlight.finish(wanted)
  }
}
