package app.marlboroadvance.mpvex.ui.player.danmaku

import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuPStrings
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

/** 弹幕渲染层日志标签：排查「能拉到但画面上没有」时按它过滤。 */
private const val TAG = "DanmakuOverlay"

/**
 * 把 DanmakuFlameMaster 的 `DanmakuView` 桥接进 Compose。
 *
 * DFM 已源码内置（`app/src/main/java/master/flame/danmaku/`），引擎层已修掉
 * 「速度与寿命共用同一个 duration 值」「计时器按 60Hz 硬编码超前」两个问题。
 *
 * 与官方 demo 的差异只有三处，全部是被迫适配：
 * ① 弹幕数据来自 dandanplay 而非 B 站 XML ⇒ 自写 `BaseDanmakuParser`；
 * ② `enableDanmakuDrawingCache(false)` 必须在 `prepare()` 之前调（见灌数据处）；
 * ③ 播放位置由外部（mpv）驱动 ⇒ `prepared()` 里 seekTo 定位 + resume/pause 跟播放状态。
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
  /** 防重叠：同轨道会撞车时换轨道（`DanmakuContext.preventOverlapping`）。 */
  preventOverlapping: Boolean = true,
  /**
   * 视频画面宽高比（宽/高）的**提供者**；返回 null 或 <=0 = 未知（回退全屏）。
   *
   * ⚠️ 竖屏适配：mpv/GSY 在竖屏下都是「窗口铺满全屏、画面居中一条」。
   * 弹幕层若跟着窗口铺满，`DanmakusRetainer` 会从**窗口顶部**往下 packing
   * ⇒ 弹幕全部挤在画面上方的黑边/状态栏区域。
   * 修法：按视频宽高比算出居中的画面矩形，把 DanmakuView 约束到它里面 ——
   * retainer 改为在画面条带内 packing，`REAL` 时长也改按画面宽度算。
   * 横屏且无黑边时矩形≈全屏，自动回退。
   *
   * ⚠️ 必须是**懒读取的 lambda**：本文件内按 1s 低频轮询、只在数值变化时更新状态，
   * 不会把 JNI 读拖进 composition。
   */
  videoAspectProvider: () -> Float? = { null },
) {
  val context = LocalContext.current
  val danmakuView = remember { DanmakuView(context) }
  // 真正交给 `AndroidView` 的是这层隔离容器，DanmakuView 是它的子 View。
  // 触摸在容器那一层就被短路成 false（详见 [InertTouchFrameLayout]）。
  val touchShield = remember {
    InertTouchFrameLayout(context).apply {
      addView(
        danmakuView,
        android.view.ViewGroup.LayoutParams(
          android.view.ViewGroup.LayoutParams.MATCH_PARENT,
          android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
    }
  }
  // 准备完成后的「接着播」位置：prepared() 回调可能比 effect 晚到（要等布局），
  // 而 effect 里又读不到最新播放位置（LaunchedEffect 捕获的是启动那一刻的值），
  // 所以用 rememberUpdatedState 拿「当前值」。
  val latestPositionForCallback = rememberUpdatedState(positionSec)
  // ⚠️ 同理：`prepared()` 要按**当前**播放状态决定 start 还是只定位。
  //   否则用户在「已暂停」状态下打开开关 → prepared 后我们还是 resume() 了 ⇒ 弹幕自己飘。
  val latestPlayingForCallback = rememberUpdatedState(playing)
  DisposableEffect(danmakuView) {
    onDispose {
      // ⚠️ 必须 release：库的绘制线程是常驻 HandlerThread，
      // 不停掉就是每进一次播放页泄漏一个线程。
      danmakuView.release()
    }
  }

  // ⚠️⚠️ 这是「开关能开但一条都不渲染」的真正修复点。
  //
  // DFM 的标准用法是 `prepare()` → 等 `prepared()` 回调 → 再 `start()`。
  // prepare 之后同一帧直接 start() 会被 `start(long)` 内部的
  // `handler.removeCallbacksAndMessages(null)` 把 PREPARE 连同它的 100ms 重试链
  // 一起清空 ⇒ `mReady` 永远 false ⇒ `drawTask` 永远 null ⇒ 画布永远空白。
  //
  // ⚠️ `prepared()` 是**每次 prepare 成功都会回调一次**的，不是只回调一次。
  //   换集时会重新 prepare，所以这里必须幂等地「重新对齐时间轴」而不是无脑再 start。
  DisposableEffect(danmakuView) {
    danmakuView.setCallback(
      object : DrawHandler.Callback {
        override fun prepared() {
          // 走到这里才算「引擎真的活了」，drawTask 已创建、计时器可用。
          // 存活时长由框架自己算（REAL），我们不碰。
          val at = (latestPositionForCallback.value * 1000f).toLong().coerceAtLeast(0L)
          // ⚠️ 用 seekTo 而不是 start()：`start(long)` 会清空队列里所有消息
          //（含刚投出的 UPDATE/RESUME/PREPARE 重试链），与 DanmakuPositionSync
          // 的 seekTo 相撞时 syncTimer 一直返回 0 ⇒ 计时器不推进 ⇒ 弹幕永远不满足
          //「已进入存活区间」。seekTo 不碰 PREPARE 链、不清队列，是 prepared 之后
          // 定位的正确入口。
          Log.i(
            TAG,
            "DFM prepared，从 ${at}ms 开始绘制，playing=${latestPlayingForCallback.value}，" +
              "timer=${danmakuView.currentTime}，visible=${danmakuView.currentVisibleDanmakus?.size()}",
          )
          danmakuView.seekTo(at)
          // seekTo 之后引擎是「定位中」而不是「播放中」，必须显式给一次播放状态，
          // 否则用户续播时弹幕停着不动（要等下一轮 250ms 对账才被拉起来）。
          if (latestPlayingForCallback.value) danmakuView.resume() else danmakuView.pause()
        }

        override fun updateTimer(timer: DanmakuTimer) = Unit

        override fun danmakuShown(danmaku: BaseDanmaku) = Unit

        override fun drawingFinished() = Unit
      },
    )
    // ⚠️ 不能传 `null`：`setCallback` 的参数在 Kotlin 侧是非空类型（库没标 @Nullable），
    //    传 null 要么编译报错要么被插桩抛异常。用一个空实现顶替即可 ——
    //    `onDispose` 之后这个 View 马上 `release()`，回调再也到不了。
    onDispose {
      danmakuView.setCallback(
        object : DrawHandler.Callback {
          override fun prepared() = Unit

          override fun updateTimer(timer: DanmakuTimer) = Unit

          override fun danmakuShown(danmaku: BaseDanmaku) = Unit

          override fun drawingFinished() = Unit
        },
      )
    }
  }

  // 库的绘制线程必须跟着播放状态走。
  DisposableEffect(danmakuView, playing) {
    if (playing) danmakuView.resume() else danmakuView.pause()
    onDispose { }
  }

  // ⚠️ 播放状态对账必须常驻，不能只在 `playing` 变化时下发一次。
  //   `DanmakuView.resume()` 有三个分支：已 prepared → post(mResumeRunnable)；
  //   handler == null → restart()；**尚未 prepared → 什么都不做**。
  //   续播场景正好踩第三态：视频已开播时弹幕引擎才刚建，那一次 resume 被吞掉
  //   ⇒ 引擎一直 paused ⇒ 计时器不动 ⇒ 一条都不出来，但开关/条数/prepared 日志全正常。
  LaunchedEffect(danmakuView) {
    var lastApplied: Boolean? = null
    while (true) {
      delay(300)
      val target = latestPlayingForCallback.value
      if (!danmakuView.isPrepared) {
        // 引擎未 ready 时下发必然被吞，等准备好再对账。
        lastApplied = null
        continue
      }
      if (lastApplied != target || danmakuView.isPaused == target) {
        // 第二个条件是兜底：无论我们记的是什么，只要引擎实际状态和期望不一致就纠一次。
        lastApplied = target
        if (target) danmakuView.resume() else danmakuView.pause()
      }
    }
  }

  // 灌数据：只在「换集」或「从没数据到有数据」时跑一次。
  //
  // ⚠️ 不能把 `comments` 整个列表当 key（每帧 O(n) 深比较，改屏蔽词也会重建引擎）；
  //    只用 `episodeKey` 也不够：切集瞬间 comments 先被清空、拉完才填回来，
  //    而 episodeKey 已经变了 ⇒ effect 用空列表跑过一遍，之后 comments 从空变非空
  //    而 key 不变 ⇒ 永远不再 prepare ⇒ 画面上一条都没有。
  //    所以第三段 key 用「是否为空」这个布尔：只在空 ↔ 非空翻转时多跑一次。
  //
  // ⚠️ 空列表时**绝不能调 `stop()`**：DFM 的 stop() 不可逆（线程退出、handler 置空），
  //    之后 resume() 会走到 restart() → 无参 prepare() → 不 setParser ⇒
  //    PREPARE 分支 mParser == null 恒成立 ⇒ 永不 mReady ⇒ 画面永远空白。
  //    正确做法：空列表时只 clear()，引擎留着。
  LaunchedEffect(danmakuView, episodeKey, comments.isEmpty()) {
    if (comments.isEmpty()) {
      // 不 stop()，只清屏；等下一条数据回来时 effect 会因为第三段 key 翻转再跑一次。
      danmakuView.clear()
      return@LaunchedEffect
    }
    val danmakuContext = DanmakuContext.create()
    // ⚠️ 必须在 prepare() **之前**关掉绘制缓存（缓存线程 NPE，catch 不住）：
    //   DFM 的 DrawHandler.prepare() 会按 isDanmakuDrawingCacheEnabled() 决定建
    //   CacheManagingDrawTask 还是普通 DrawTask；缓存那条路径在 createDanmaku()
    //   返回 null 时直接解引用 ⇒ NPE，且崩在库自己的缓存线程上、try/catch 拦不住。
    //   放在 prepare 之后再调无效：线程已经建好了。代价只是回拖进度时重走轨道分配。
    danmakuView.enableDanmakuDrawingCache(false)
    // ⚠️ context 必须**在构造 parser 时就传进去**：DanmakuListParser 拿不到
    // setConfig() 存进去的那份（BaseDanmakuParser 没有 getConfig），而 parse() 是无参的。
    val parser = DanmakuListParser(comments, alphaPercent, danmakuContext)
    danmakuView.prepare(parser, danmakuContext)
    applyDisplayConfig(danmakuContext, alphaPercent, fontScale, speedScale, maxOnScreen, areaRatio, preventOverlapping)
    // ⚠️⚠️ **绝对不能在这里调 `start()`**。
    //
    // DFM 的 `DanmakuView.start(long position)` 第一件事是
    // `handler.removeCallbacksAndMessages(null)`，而 `prepare(parser, config)` 刚投出的
    // PREPARE 消息正在队列里：`AndroidView` 先组合后测量布局，此刻 onLayout() 还没跑
    // ⇒ isViewReady() 为 false ⇒ PREPARE 本该 100ms 后自己重投，等布局好了再建 drawTask。
    // 但 start() 把这条重试链连同队列所有消息一起清空 ⇒ 之后再没人投 PREPARE
    // ⇒ mReady 永远 false ⇒ drawTask 永远 null ⇒ onDraw 直接返回 ⇒ 画布永远空白。
    // resume() 也救不回来：「handler 非空但未 prepared」这个第三态什么都不做。
    //
    // 正确姿势：**只 prepare，不 start**。PREPARE 成功后 DFM 回调 prepared()，
    // 我们在那里再开始画（见上面的 setCallback）。这也是 DFM README 的官方用法。
    val displayMetrics = context.resources.displayMetrics
    Log.i(
      TAG,
      "弹幕灌数据：${comments.size} 条，key=$episodeKey，" +
        "prepared=${danmakuView.isPrepared}，size=${danmakuView.width}x${danmakuView.height}，" +
        "density=${displayMetrics.density}，densityDpi=${displayMetrics.densityDpi}",
    )
  }

  // 显示参数：改了立刻生效，但不重建引擎。
  // ⚠️ 这几个 setter 内部会 notifyConfigureChanged + 全局标记重测，
  // 每帧调会拖垮渲染 —— 所以只在**值真的变了**时才调（下面的守卫）。
  var lastAlpha by remember { mutableIntStateOf(Int.MIN_VALUE) }
  var lastFont by remember { mutableFloatStateOf(Float.NaN) }
  var lastSpeed by remember { mutableFloatStateOf(Float.NaN) }
  var lastMax by remember { mutableIntStateOf(Int.MIN_VALUE) }
  var lastArea by remember { mutableFloatStateOf(Float.NaN) }
  var lastOverlap by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(alphaPercent, fontScale, speedScale, maxOnScreen, areaRatio, preventOverlapping) {
    val a = alphaPercent.coerceIn(10, 100)
    val f = fontScale.coerceIn(0.5f, 2f)
    val s = speedScale.coerceIn(0.5f, 2f)
    val m = maxOnScreen.coerceAtLeast(0)
    val r = areaRatio.coerceIn(0.25f, 1f)
    val o = preventOverlapping
    val changed = a != lastAlpha || f != lastFont || s != lastSpeed || m != lastMax ||
      r != lastArea || o != lastOverlap
    if (changed) {
      lastAlpha = a; lastFont = f; lastSpeed = s; lastMax = m; lastArea = r; lastOverlap = o
      danmakuView.config?.let {
        applyDisplayConfig(it, a, f, s, m, r, o)
        // 透明度/字号变了要让已有弹幕重画，否则已经画出来的还是旧样式。
        danmakuView.forceRender()
      }
    }
  }

  DanmakuPositionSync(danmakuView = danmakuView, positionSec = positionSec)

  // 诊断采样：把「引擎到底卡在哪一层」一口气打清楚（3s 一条）。
  //   · timer 不动              ⇒ 卡在 seek 竞态
  //   · timer 在动但 visible=0   ⇒ parser 侧 flags/timer/visibility 没设对
  //   · visible>0 但看不见       ⇒ 只剩字号/颜色问题
  if (comments.isNotEmpty()) {
    LaunchedEffect(danmakuView, episodeKey) {
      var wallLast = 0L
      var timerLast = 0L
      while (true) {
        delay(3000)
        if (!danmakuView.isPrepared) continue
        val wallNow = android.os.SystemClock.elapsedRealtime()
        val timerNow = danmakuView.currentTime
        if (wallLast != 0L) {
          val wallDelta = wallNow - wallLast
          val timerDelta = timerNow - timerLast
          // 时钟比（timer 推进 / 真实时间）能区分两种病因：
          //   · ≈1.0 且 visible>0 但用户仍说「一会儿就没」 ⇒ 看 duration/lifetime
          //   · 明显 >1 ⇒ 计时器跑得比真实时间快，弹幕被快速烧完（高刷屏场景已修）
          val ratio = if (wallDelta > 0) timerDelta.toFloat() / wallDelta else 0f
          Log.i(
            TAG,
            "DFM 状态：timer=${timerNow}，paused=${danmakuView.isPaused}，" +
              "shown=${danmakuView.isShown}，viewReady=${danmakuView.isViewReady}，" +
              "visible=${danmakuView.currentVisibleDanmakus?.size()}，" +
              "size=${danmakuView.width}x${danmakuView.height}，" +
              // value=速度基准（吃速度因子），lifetime=寿命（不吃因子，恒定等于框架 REAL）。
              // 默认速度下 2340px 屏 lifetime≈5900ms；clock≈1 而弹幕仍早夭，看 DFM-Diag 的取窗。
              "duration=${danmakuView.config?.mDanmakuFactory?.MAX_Duration_Scroll_Danmaku?.value}ms，" +
              "lifetime=${danmakuView.config?.mDanmakuFactory?.MAX_Duration_Scroll_Danmaku?.lifetime}ms，" +
              "时钟比=%.2fx（+%d/%dms）".format(ratio, timerDelta, wallDelta),
          )
        }
        wallLast = wallNow
        timerLast = timerNow
      }
    }
  }

  // ── 竖屏/黑边适配：把弹幕层约束到「视频画面矩形」内 ──────────────────────
  // 轮询画面宽高比（低频、只在变化时更新状态；换集先清零避免上一条视频的比例残留）。
  var videoAspect by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(danmakuView, episodeKey) {
    videoAspect = 0f
    while (true) {
      val a = runCatching { videoAspectProvider() }.getOrNull() ?: 0f
      if (a > 0.01f && kotlin.math.abs(a - videoAspect) > 0.01f) {
        videoAspect = a
      }
      delay(1000)
    }
  }

  // ⚠️⚠️ 容器尺寸用 `onSizeChanged` 拿，**绝不能用 BoxWithConstraints**。
  //
  // BoxWithConstraints 是 SubcomposeLayout，其子内容（含全屏 AndroidView）在测量阶段
  // 才 subcompose；AndroidView 的 pointerInteropFilter 是全屏尺寸的
  // PointerInputModifier，会排进命中路径并抢在 GestureHandler 前面接到 ACTION_DOWN
  // —— 它一旦认领 DOWN，同一次手势里 HitPathTracker 就把后面的 GestureHandler
  // 整条摁掉 ⇒ 单击永远走不到「显隐控件」⇒ 点屏幕唤不出控制栏。
  // onSizeChanged 没有 SubcomposeLayout、命中路径干净，尺寸一样拿得到。
  var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
  Box(modifier = modifier.onSizeChanged { containerSize = it }) {
    val viewW = containerSize.width.toFloat()
    val viewH = containerSize.height.toFloat()
    if (videoAspect <= 0.01f || viewW <= 0f || viewH <= 0f) {
      // 比例未知（视频还没加载出来 / 容器还没测出来）⇒ 老行为：铺满。
      AndroidView(factory = { touchShield }, modifier = Modifier.fillMaxSize())
    } else {
      // keepaspect 语义：哪一边贴满就按哪一边算，另一边居中。
      val drawW: Float
      val drawH: Float
      if (viewW / viewH > videoAspect) {
        drawH = viewH
        drawW = viewH * videoAspect
      } else {
        drawW = viewW
        drawH = viewW / videoAspect
      }
      val insetX = ((viewW - drawW) / 2f).roundToInt()
      val insetY = ((viewH - drawH) / 2f).roundToInt()
      if (insetX <= 2 && insetY <= 2) {
        // 无黑边（或不足 2px）⇒ 直接铺满，避免无谓的尺寸跳变。
        AndroidView(factory = { touchShield }, modifier = Modifier.fillMaxSize())
      } else {
        // ⚠️ 这里必须 **px 精确定位**：`Modifier.offset/size` 只有 Dp 重载，
        //    小数密度（2.75/3.25…）上 dp→px 的 rounding 会让弹幕层和画面
        //    错开一两像素，DanmakuView 的边缘 retainer 判据就跟着偏。
        //    Int 参数也不会隐式转 Float/Dp，所以走 `Modifier.layout` 自己摆：
        //    本节点撑成父级全尺寸，再把子 View 钉在画面矩形的左上角。
        // ⚠️ DanmakuView.onLayout → notifyDispSizeChanged 会按新尺寸重算
        //    REAL 时长与 retainer 边界，运行中改大小是 DFM 支持的标准路径。
        AndroidView(
          factory = { touchShield },
          modifier = Modifier.layout { measurable, constraints ->
            val w = drawW.roundToInt()
            val h = drawH.roundToInt()
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) {
              placeable.place(insetX, insetY)
            }
          },
        )
      }
    }
  }
}

/**
 * 把 [DanmakuView] 包起来、让它**绝对拿不到**触摸事件的隔离容器。
 *
 * `AndroidView` 会给它的 LayoutNode 挂 `pointerInteropFilter`：DOWN 一到，
 * Compose 就**先问这个 View 要不要**（`view.dispatchTouchEvent(ev)`），
 * 它返回 true 的话整串手势就被它独占，同一次手势里排在它后面的
 * `GestureHandler` 一条事件都收不到。而弹幕根本不需要触摸。
 *
 * 所以这里在最外层直接把 `dispatchTouchEvent` 短路成 false：事件连内部
 * DanmakuView 的手势识别器都到不了，pointerInteropFilter 拿到的必然是 false。
 *
 * 为什么是覆写 `dispatchTouchEvent` 而不是给 DanmakuView 设 `isClickable = false`：
 * 后者只管 DanmakuView 自己，管不住外面那层 ViewGroup 的兜底分发；
 * 覆写容器则连子 View 一起挡掉，不依赖任何「View 默认不可点击」的假设。
 */
private class InertTouchFrameLayout(context: android.content.Context) : android.widget.FrameLayout(context) {
  init {
    isClickable = false
    isLongClickable = false
    isFocusable = false
    // 弹幕可能画到容器边缘外一点点，别让 ViewGroup 把它裁了。
    clipChildren = false
  }

  override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false

  override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = false

  override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

  override fun performClick(): Boolean = false
}

/** 把六个显示参数写进 [DanmakuContext]（方法名全部对过 DanmakuContext 源码）。 */
private fun applyDisplayConfig(
  context: DanmakuContext,
  alphaPercent: Int,
  fontScale: Float,
  speedScale: Float,
  maxOnScreen: Int,
  areaRatio: Float,
  preventOverlapping: Boolean,
) {
  // 透明度：setter 收 0~1 比例，内部换算成 paint alpha。
  context.setDanmakuTransparency(alphaPercent.coerceIn(10, 100) / 100f)
  // 字号：乘在弹幕自带的字体档位上。
  context.setScaleTextSize(fontScale.coerceIn(0.5f, 2f))
  // 速度：用户可控项。
  //
  // ⚠️ DFM 的 factor 方向和直觉相反，而且它直接乘在寿命上：
  //   `setScrollSpeedFactor(f)` → `value = mInitialDuration * f`
  //   ⇒ **f 越大 duration 越长、弹幕越慢**。而偏好文案写的是「>1 更快」，
  //   把它直接传下去会让「想调快」的用户把弹幕调慢（调到 1.6 ⇒ duration 被乘成 16 秒）。
  // ⇒ 这里取倒数：默认 1.0 时 factor=1.0（框架默认行为）；调到 2.0 ⇒ factor=0.5 ⇒ 更快。
  context.setScrollSpeedFactor(1f / speedScale.coerceIn(0.5f, 2f))
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
  // 防重叠：库按「新弹幕会不会追上同轨道的上一条」决定换不换轨道。
  // ⚠️ 四类弹幕要**逐个类型**给值（Map 里缺的 key 视作 false），不能只塞一个 DEFAULT。
  context.preventOverlapping(
    mapOf(
      BaseDanmaku.TYPE_SCROLL_RL to preventOverlapping,
      BaseDanmaku.TYPE_SCROLL_LR to preventOverlapping,
      BaseDanmaku.TYPE_FIX_TOP to preventOverlapping,
      BaseDanmaku.TYPE_FIX_BOTTOM to preventOverlapping,
    ),
  )
  // ⚠️ 「绘制缓存」的开关**不在这里** —— DanmakuContext 上没有这个 API。
  //   真正管用的入口是 `DanmakuView.enableDanmakuDrawingCache(false)`，
  //   而且必须在 `prepare()` 之前调（见 DanmakuOverlay 的灌数据处）。
}


/**
 * 低频进度同步：只在**时间跳变**时 seekTo。
 *
 * ⚠️ 对齐基准必须是**引擎自己的计时器**（`danmakuView.currentTime`），
 * 不能是「上次 seek 的位置」这类只会在 seek 时更新的本地基准 —— 后者在正常播放时
 * 永远停在初始值 ⇒ 每约 1.75s 就产生一次假 seek。
 *
 * 而 DFM 的 seek 是「跳转」语义：`mStartRenderTime = position`，
 * 且 `Consumer.accept` 里 `if (drawItem.getActualTime() < startRenderTime) return;`
 * ⇒ **所有 time 早于 seek 位置的弹幕被永久跳过不画** ⇒ 在飞舞幕全部中途消失
 * （每条最多活 ~1.75s，长弹幕只够探个头）。与刷新率、速度设置无关。
 *
 * 改成与引擎计时器比之后：正常播放 drift≈0 永不 seek；只有真实跳变
 * （拖动进度 / 卡顿续播 / 引擎冻结）drift>1.5s 才 seek 纠正。
 *
 * ⚠️ 两个约束不要回退：
 *   1. 循环常驻，不绑 `enabled = playing`（否则起播瞬间 effect 启动即退出，
 *      基准重置导致开局一次硬 seek，和 prepared() 的定位撞车）；
 *   2. 未 prepared 时不能 seek（handler 未就绪，seekTo 会被直接丢掉）。
 */
@Composable
private fun DanmakuPositionSync(
  danmakuView: DanmakuView,
  positionSec: Float,
) {
  val latestPosition = rememberUpdatedState(positionSec)
  LaunchedEffect(danmakuView) {
    while (true) {
      delay(250)
      if (!danmakuView.isPrepared) {
        // 还不能 seek（handler 未 prepared 时 seekTo 会被直接丢掉）。
        continue
      }
      val targetMs = (latestPosition.value * 1000f).toLong()
      // ⚠️ 与**引擎计时器**比，而不是与某个只会越来越旧的本地基准比：
      //    正常播放 drift≈0 永不 seek；只有真实跳变（拖动/卡顿/引擎冻结）才纠正。
      val driftMs = kotlin.math.abs(targetMs - danmakuView.currentTime)
      if (driftMs > 1500L) {
        danmakuView.seekTo(targetMs)
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
   * 但**没有 public 的 `getConfig()`/字段**，而 `parse()` 是无参的、拿不到外部引用，
   * 所以必须在构造时就把它存下来 —— 这是本类唯一可靠的时机。
   */
  private val danmakuContext: DanmakuContext,
) : BaseDanmakuParser() {
  override fun parse(): IDanmakus {
    val danmakus = Danmakus()
    val factory = danmakuContext.mDanmakuFactory
    val displayer = danmakuContext.mDisplayer
    // ⚠️⚠️ 以下三行（flags / timer / visibility）缺了任何一样，弹幕就**一条都画不出来**，
    //   且不报错、不崩、计数正常：
    // ① `item.setTimer(timer)` —— BaseDanmaku.isTimeOut() = `mTimer == null || ...`
    //    ⇒ 恒 true ⇒ Consumer.accept 第一句就回收，全部弹幕进不了绘制；
    //    layout() 里 mTimer 为 null 时也直接走 else 分支置成不可见。
    //    注意 DrawTask.loadDanmakus() 只做了 parser.setTimer(mTimer)，
    //    **库不会替你往下分发给每条 item**，必须逐条设。
    // ② `item.flags = mGlobalFlagValues` —— isShown()/setVisibility(true) 都要读
    //    flags.XXX_RESET_FLAG，默认 null 会 NPE 或判等失败。
    //    必须在 resetAll() **之后**取（同一个对象引用，判等天然成立）。
    // ③ `item.setVisibility(true)` —— 给初值，且**必须排在 ② 之后**
    //    （它内部读 flags.VISIBLE_RESET_FLAG，flags 为 null 直接 NPE）。
    val timer: DanmakuTimer = this.timer
    val flags = danmakuContext.mGlobalFlagValues

    // ⚠️ **字号换算**：dandanplay 第 3 列是「档位」（25=标准 / 18=小），**不是像素**。
    //    而 DisplayerConfig.getPaint() 里是 `paint.setTextSize(danmaku.textSize)`
    //    —— Paint 的单位是 px。直接把 25 填进去，density=2 的机器上只有 12.5dp，
    //    1080p 横屏下肉眼几乎看不见。
    //    系数沿用**库自己的密度约定**：DanmakuFactory 用 `(density - 0.6f)` 把 viewport
    //    折算成滚动时长，这里反向用它把字号补齐到同一基准，保证弹幕的视觉大小与
    //    滚动速度随密度同步缩放。下限锁 1f。
    val density = displayer.density
    val sizeFactor = (density - 0.6f).coerceAtLeast(1f)

    // ⚠️ **index 必须唯一**：Danmakus 默认走 TreeSet(TimeComparator)，比较落到
    //    `DanmakuUtils.compare()` —— 先比 time，再比 index。库里该函数最后一行有笔误
    //    （恒返回 0），所以 **time 和 index 都相等时 add 返回 false ⇒ 被静默丢弃**。
    //    正常入口 DrawTask.addDanmaku() 会 `item.index = danmakuList.size()`，
    //    而我们走 parse() 批量灌数据那条路，从来没人赋过 index（恒为 0）
    //    ⇒ 同一毫秒到达的弹幕只会活下来一条。这里手动递增即可。
    var index = 0
    for (c in comments) {
      // ⚠️ createDanmaku 会用 displayer 的宽高算存活时间；
      // AndroidView 还没布局时宽高是 0 ⇒ 返回 null，所以这里必须容错跳过。
      val item = factory.createDanmaku(mapMode(c.mode), danmakuContext) ?: continue
      item.setTime((c.timeSec * 1000).toLong())
      item.index = index++
      // 文本直接赋值：`DanmakuUtils.fillText()` 多做的一件事是按 DFM 的换行符
      // （`BaseDanmaku.DANMAKU_BR_CHAR = "/n"`）拆成多行 —— dandanplay 不产出这个 token，
      // 所以这里等价，不做多余调用。
      item.text = c.text
      item.textSize = c.textSize.toFloat() * sizeFactor
      // Alpha 位是**白写**的：DisplayerConfig.applyPaintConfig() 会把高 8 位抹掉，
      // 真正的透明度只认 DanmakuContext.setDanmakuTransparency()。保留 toArgb 只是
      // 保持调用点语义完整，去掉它结果也一样。
      item.textColor = DanmakuPStrings.toArgb(c.colorRgb, alphaPercent)
      // 描边用黑：弹幕压在亮画面上时，纯色字会看不清。
      item.textShadowColor = Color.BLACK
      item.flags = flags
      item.setTimer(timer)
      item.setVisibility(true)
      danmakus.addItem(item)
    }

    // ⚠️ 存活时长**交给框架自己算**，不再从外面覆写 —— 覆写是和框架对抗：
    //   `NOTIFY_DISP_SIZE_CHANGED` 一发现 view 尺寸变了就用 REAL 把我们的值冲掉，
    //   追着补越描越黑。
    //
    // 库的口径本来就是「按 B 站基准屏宽（682）等比缩放」：
    //   REAL = COMMON_DANMAKU_DURATION(3800) × viewportSizeFactor × 屏宽/682
    //        （再被 [4000, 9000] 夹住）
    // 在 2340px 屏上约 5.9s。用户要更快/更慢走 `setScrollSpeedFactor`，
    // 见 applyDisplayConfig 里对 factor 方向的修正。
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