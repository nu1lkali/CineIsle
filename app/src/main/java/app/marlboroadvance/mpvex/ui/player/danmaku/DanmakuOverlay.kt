package app.marlboroadvance.mpvex.ui.player.danmaku

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuPStrings
import kotlinx.coroutines.delay
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 把 DanmakuFlameMaster 的 `DanmakuView` 桥接进 Compose。
 *
 * ## 为什么用 AndroidView 而不是自己画
 *
 * 弹幕要处理轨道分配（同轨道不能撞车）、跨轨道让位、滚动速度与存活时间的联动 ——
 * 这些有一百多行，而且很容易写出「偶尔叠字」的 bug。这个库把这件事做完了，
 * 所以这里只做**桥接**，不重写渲染。
 *
 * ## ⚠️ 为什么用 [episodeKey] 而不是直接拿 comments 当 key
 *
 * `LaunchedEffect` 的 key 用 `equals()` 比较，而一份弹幕动辄几千条 ——
 * 拿列表当 key 等于每帧做一次 O(n) 深比较。
 * 而「是不是同一集的弹幕」这件事，真正决定重建的其实是**集的身份**，
 * 所以由调用方给一个稳定 id（`"s1|item42|ep3"`这种），语义也更清楚：
 * 换 key = 换集 = 重建引擎。
 *
 * ## 进度怎么喂
 *
 * ⚠️ **绝对不能每帧 seekTo**。进度是 42ms 轮询的 StateFlow，
 * 每帧 seekTo 会把库内部「已显示弹幕集合」整个重建 ⇒ 弹幕永远在闪、追不上。
 * 正确做法见 [DanmakuPositionSync]：低频（250ms）比对，**只在时间真的跳了**
 * （开播 / 拖进度 / 换片）时才 seekTo；正常前进交给库自己的绘制线程。
 *
 * @param episodeKey 当前集的稳定标识；变了就重建引擎
 * @param positionSec 当前播放位置（秒）
 * @param playing 是否在播：暂停时停掉库的绘制线程（否则暂停后弹幕还在飘）
 * @param comments 当前这一集的弹幕
 */
@Composable
fun DanmakuOverlay(
  episodeKey: String,
  positionSec: Float,
  playing: Boolean,
  comments: List<DanmakuComment>,
  modifier: Modifier = Modifier,
  alphaPercent: Int = 100,
  fontScale: Float = 1f,
  speedScale: Float = 1f,
  maxOnScreen: Int = 0,
  areaRatio: Float = 1f,
) {
  val context = LocalContext.current
  val danmakuView = remember { DanmakuView(context) }

  DisposableEffect(danmakuView) {
    onDispose {
      // ⚠️ 必须 release：库的绘制线程是常驻 HandlerThread，
      // 不停掉就是每进一次播放页泄漏一个线程。
      danmakuView.release()
    }
  }

  // 库的绘制线程必须跟着播放状态走。
  DisposableEffect(danmakuView, playing) {
    if (playing) danmakuView.resume() else danmakuView.pause()
    onDispose { }
  }

  // 灌数据：只在「换集」时跑一次。
  // ⚠️ 反复 prepare 会新建 HandlerThread、弹幕叠成两遍，所以 key 只认 episodeKey。
  LaunchedEffect(danmakuView, episodeKey) {
    if (comments.isEmpty()) {
      danmakuView.stop()
      return@LaunchedEffect
    }
    val danmakuContext = DanmakuContext.create()
    // ⚠️ context 必须**在构造 parser 时就传进去**：DanmakuListParser 拿不到
    // setConfig() 存进去的那份（BaseDanmakuParser 没有 getConfig），而 parse() 是无参的。
    val parser = DanmakuListParser(comments, alphaPercent, danmakuContext)
    danmakuView.prepare(parser, danmakuContext)
    applyDisplayConfig(danmakuContext, alphaPercent, fontScale, speedScale, maxOnScreen, areaRatio)
    danmakuView.start(0L)
  }

  // 显示参数：改了立刻生效，但不重建引擎。
  // ⚠️ 这几个 setter 内部会 notifyConfigureChanged + 全局标记重测，
  // 每帧调会拖垮渲染 —— 所以只在**值真的变了**时才调（下面的守卫）。
  var lastAlpha by remember { mutableIntStateOf(Int.MIN_VALUE) }
  var lastFont by remember { mutableFloatStateOf(Float.NaN) }
  var lastSpeed by remember { mutableFloatStateOf(Float.NaN) }
  var lastMax by remember { mutableIntStateOf(Int.MIN_VALUE) }
  var lastArea by remember { mutableFloatStateOf(Float.NaN) }
  LaunchedEffect(alphaPercent, fontScale, speedScale, maxOnScreen, areaRatio) {
    val a = alphaPercent.coerceIn(10, 100)
    val f = fontScale.coerceIn(0.5f, 2f)
    val s = speedScale.coerceIn(0.5f, 2f)
    val m = maxOnScreen.coerceAtLeast(0)
    val r = areaRatio.coerceIn(0.25f, 1f)
    val changed = a != lastAlpha || f != lastFont || s != lastSpeed || m != lastMax || r != lastArea
    if (changed) {
      lastAlpha = a; lastFont = f; lastSpeed = s; lastMax = m; lastArea = r
      danmakuView.config?.let {
        applyDisplayConfig(it, a, f, s, m, r)
        // 透明度/字号变了要让已有弹幕重画，否则已经画出来的还是旧样式。
        danmakuView.forceRender()
      }
    }
  }

  DanmakuPositionSync(danmakuView = danmakuView, positionSec = positionSec, enabled = playing)

  AndroidView(factory = { danmakuView }, modifier = modifier)
}

/** 把五个显示参数写进 [DanmakuContext]（方法名全部对过 DanmakuContext 源码）。 */
private fun applyDisplayConfig(
  context: DanmakuContext,
  alphaPercent: Int,
  fontScale: Float,
  speedScale: Float,
  maxOnScreen: Int,
  areaRatio: Float,
) {
  // 透明度：setter 收 0~1 比例，内部换算成 paint alpha。
  context.setDanmakuTransparency(alphaPercent.coerceIn(10, 100) / 100f)
  // 字号：乘在弹幕自带的字体档位上。
  context.setScaleTextSize(fontScale.coerceIn(0.5f, 2f))
  // 速度：只对滚动弹幕有效，>1 = 更快（内部把 duration 调短）。
  context.setScrollSpeedFactor(speedScale.coerceIn(0.5f, 2f))
  // 同屏条数：0 = 无限制，-1 = 按绘制耗时自动调整（低配机的安全档）。
  context.setMaximumVisibleSizeInScreen(maxOnScreen.coerceAtLeast(0))
  // 显示区域：用「最大行数」间接限制 —— 行数 ≈ 区域高度 / 单行高。
  // 上下各限一半，否则弹幕会跑到画面底部那条控制栏底下。
  val lines = ((areaRatio.coerceIn(0.25f, 1f)) * 10).toInt().coerceAtLeast(2)
  context.setMaximumLines(
    mapOf(
      BaseDanmaku.TYPE_SCROLL_RL to lines,
      BaseDanmaku.TYPE_SCROLL_LR to lines,
      BaseDanmaku.TYPE_FIX_TOP to (lines / 2).coerceAtLeast(1),
      BaseDanmaku.TYPE_FIX_BOTTOM to (lines / 2).coerceAtLeast(1),
    )
  )
  // 合并重复弹幕：否则同一秒的「哈哈哈哈」能糊满一屏。
  context.setDuplicateMergingEnabled(true)
}

/**
 * 低频进度同步：只在**时间跳变**时 seekTo。
 *
 * ⚠️ 两个坑都在这里：
 * 1. 循环体里读的是 [positionSec] 的**当前值**（通过 `rememberUpdatedState`），
 *    不是启动那一刻捕获的值 —— 后者会让整个循环一直拿着开播时的位置。
 * 2. 阈值不能太小：正常播放时 250ms 内也会前进约 0.25 秒，
 *    所以「跳变」判据是**与上次 seek 的距离 > 1.5 秒**，而不是「变了就 seek」。
 */
@Composable
private fun DanmakuPositionSync(
  danmakuView: DanmakuView,
  positionSec: Float,
  enabled: Boolean,
) {
  val latestPosition = rememberUpdatedState(positionSec)
  LaunchedEffect(danmakuView, enabled) {
    if (!enabled) return@LaunchedEffect
    var lastSeekSec = -1f
    while (true) {
      delay(250)
      if (!danmakuView.isPrepared) continue
      val now = latestPosition.value
      val jumped = lastSeekSec < 0f || kotlin.math.abs(now - lastSeekSec) > 1.5f
      if (jumped) {
        danmakuView.seekTo((now * 1000f).toLong())
        lastSeekSec = now
      }
    }
  }
}

/**
 * 直接喂一串已解析好的弹幕 —— 不走 B 站 XML 解析。
 *
 * `BaseDanmakuParser` 只有一个抽象方法 `parse(): IDanmakus`，
 * 我们手里已经是列表了，所以直接返回；`load(IDataSource)` 用不上。
 */
private class DanmakuListParser(
  private val comments: List<DanmakuComment>,
  private val alphaPercent: Int,
  /**
   * `DanmakuContext` —— 由构造注入。
   *
   * ⚠️ **不能靠 `setConfig()` 之后再取回来**：`BaseDanmakuParser` 有 `setConfig(...)`
   * 但**没有 public 的 `getConfig()`/字段**，只有 `getDisplayer()`。
   * 而 `parse()` 在真正被调用时**拿不到外部引用**（签名是 `parse(): IDanmakus`，无参），
   * 所以必须在构造时就把它存下来 —— 这是本类唯一可靠的时机。
   *
   * `mDanmakuFactory` / `mDisplayer` 都是 `DanmakuContext` 上的 **public 字段**
   * （已用 javap 核对过真实签名，见记忆里的「引第三方库先读它自己的字节码」）。
   */
  private val danmakuContext: DanmakuContext,
) : BaseDanmakuParser() {
  override fun parse(): IDanmakus {
    val danmakus = Danmakus()
    val factory = danmakuContext.mDanmakuFactory
    val displayer = danmakuContext.mDisplayer
    for (c in comments) {
      // ⚠️ createDanmaku 会用 displayer 的宽高算存活时间；
      // AndroidView 还没布局时宽高是 0 ⇒ 返回 null，所以这里必须容错跳过。
      val item = factory.createDanmaku(mapMode(c.mode), displayer, 1f, 1f) ?: continue
      item.setTime((c.timeSec * 1000).toLong())
      item.text = c.text
      item.textSize = c.textSize.toFloat()
      item.textColor = DanmakuPStrings.toArgb(c.colorRgb, alphaPercent)
      // 描边用黑：弹幕压在亮画面上时，纯色字会看不清。
      item.textShadowColor = Color.BLACK
      danmakus.addItem(item)
    }
    return danmakus
  }

  /** dandanplay 的 mode 值恰好与库的 TYPE_* 同值，但仍然显式映射 —— 不依赖巧合。 */
  private fun mapMode(mode: Int): Int = when (mode) {
    DanmakuPStrings.MODE_SCROLL_RL -> BaseDanmaku.TYPE_SCROLL_RL
    DanmakuPStrings.MODE_SCROLL_LR -> BaseDanmaku.TYPE_SCROLL_LR
    DanmakuPStrings.MODE_FIX_TOP -> BaseDanmaku.TYPE_FIX_TOP
    DanmakuPStrings.MODE_FIX_BOTTOM -> BaseDanmaku.TYPE_FIX_BOTTOM
    else -> BaseDanmaku.TYPE_SCROLL_RL
  }
}