package app.marlboroadvance.mpvex.ui.player.danmaku

import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * ## ⚠️⚠️ v115：DFM 已改为**源码内置**（`app/src/main/java/master/flame/danmaku/`），
 * 并从引擎层修掉三个把「寿命」拖短的真凶
 *
 * 之前几版（v112/v113/v114）都在外面跟 `MAX_Duration_Scroll_Danmaku` 斗智斗勇：
 * `setValue(...)` 覆写时长会被 `NOTIFY_DISP_SIZE_CHANGED` 用 REAL 冲掉，
 * `setScrollSpeedFactor` 调速又会把 `duration.value` 乘一遍 —— 因为 DFM 里
 * 「滚动速度」和「存活寿命」本来就是同一个 `duration` 值
 * （`mStepX = 距离/duration` 既是速度也是寿命），外面怎么改都是按下葫芦浮起瓢。
 * v115 起从源码层根治：
 *
 * ① **速度与寿命解耦**：新增 `Duration.getLifetime()`（= mInitialDuration，不吃速度
 *    因子）作为寿命判据（`BaseDanmaku.isTimeOut/isOutside`、`R2LDanmaku/FTDanmaku/
 *    L2RDanmaku` 的 layout 与 getAccurateLeft）；滚动速度仍读 `value`（吃 factor）
 *    ⇒ 用户调快调慢速度，**寿命恒定**由框架 REAL 决定（2340px 屏约 5.9s，
 *    即「从右出现到完全移出左边缘」）。
 * ② **计时器不再超前真实时间**：原库 `syncTimer` 每帧至少推进硬编码的 16ms
 *    （=60Hz 假设），90/120Hz 屏帧间隔只有 11.1/8.3ms ⇒ timer 持续超前
 *    （「时钟比」>1）⇒ 寿命被等比例烧短。v115 改为按屏幕真实刷新率取帧间隔，
 *    且 timer 已追上/超前时只按真实帧间隔推进，绝不超过真实增量。
 *    ⚠️ v116 实测修正：这一条**不是**「弹幕 2 秒就没」的主因（用户切 60/120Hz
 *    复测无效已证明），但作为健壮性修复保留。
 * ③ 顺手修两个库 bug：`DanmakuUtils.compare` 的 `obj1.hashCode() - obj1.hashCode()`
 *    笔误（恒 0 ⇒ 同毫秒+同 index 弹幕被 TreeSet 静默丢弃）、`Duration.setFactor`
 *    的 `if (factor != f)` 守卫（先调速后 setValue 时 value 不重算）。
 *
 * ## ⚠️⚠️ v116：「寿命短」的真凶是 **DanmakuPositionSync 每 ~1.75s 假 seek 一次**
 *
 * 旧同步逻辑用「上次 seek 的位置」当基准且只在 seek 时更新它 ⇒ 正常播放每 1.75s
 * 触发一次硬 `seekTo`；而 DFM 的 seek 语义会把 `mStartRenderTime` 推到当前位置，
 * 渲染器从此**永久跳过所有 time 早于该位置的弹幕** ⇒ 在飞舞幕全部中途消失
 * （每条弹幕最多活 ~1.75s，长弹幕只够「探个头」）。与刷新率/速度设置无关。
 * 详见 `DanmakuPositionSync` 的文档。v112~v115 在 duration/lifetime/计时器上
 * 的努力全部打偏，就是因为没抓到这条。
 *
 * 诊断日志两条（真机回归时按 tag 过滤）：
 *   · `DFM-Diag`（引擎内 DrawTask，2s 一条）：timer / 时钟比 / 取窗区间 / scrollVal /
 *     scrollLife —— clock≈1 且 life≈5900 即正常
 *   · `DFM 状态`（本文件，3s 一条）：timer / visible / duration / lifetime / 时钟比
 *
 * ## 与官方 demo 的差异只有三处，全部是被迫适配
 *
 * ① 弹幕数据来自 dandanplay 而非 B 站 XML ⇒ 自写 `BaseDanmakuParser`（做法照抄
 *    jar 里自带的官方示例 `FakeDanmakuView$CustomParser`：逐条 `createDanmaku` +
 *    `setTimer` + `flags` + `setVisibility(true)`；AAR 裁掉了 `BiliDanmukuParser`）。
 * ② `enableDanmakuDrawingCache(false)` —— 规避库里 `CacheManagingDrawTask` 的 NPE
 *    （崩在它自己的缓存线程上，catch 不住），必须在 `prepare()` 之前调。
 * ③ 播放位置由外部（mpv）驱动 ⇒ `prepared()` 回调里用 `seekTo(at)` 定位 +
 *    `resume()/pause()` 跟播放状态，而不是 demo 里那种「一 show 就从 0 开始播」。
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
   * 视频画面宽高比（宽/高）的**提供者**；返回 null 或 <=0 = 未知（回退全屏老行为）。
   *
   * ⚠️ v117 竖屏适配：mpv/GSY 在竖屏下都是「窗口铺满全屏、画面居中一条」。
   * 弹幕层若跟着窗口铺满，`DanmakusRetainer` 会从**窗口顶部**往下 packing
   * （`AlignTopRetainer`：`topPos = lastItem.getBottom() + margin` 逐行下排，
   * `isOutVerticalEdge` 按 `disp.getHeight()` 判溢出）⇒ 弹幕全部挤在画面上方的
   * 黑边/状态栏区域，用户看到「全挤在最上方」。
   * 修法：按视频宽高比算出居中的画面矩形，把 DanmakuView 约束到它里面 ——
   * retainer 改为在画面条带内 packing，`REAL` 时长也改按画面宽度算（更正确）。
   * 横屏且无黑边时矩形≈全屏，自动回退，零回归。
   *
   * ⚠️ 必须是**懒读取的 lambda**：调用点（PlayerActivity/GsyPlayerActivity）直接传
   * `{ player.getVideoOutAspect() }` 这种闭包即可，本文件内按 1s 低频轮询、
   * 只在数值变化时更新状态，不会把 JNI 读拖进 composition。
   */
  videoAspectProvider: () -> Float? = { null },
) {
  val context = LocalContext.current
  val danmakuView = remember { DanmakuView(context) }
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

  // ⚠️⚠️ 这是「开关能开但一条都不渲染」的真正修复点（v110）。
  //
  // DFM 的标准用法是 `prepare()` → 等 `prepared()` 回调 → 再 `start()`。
  // 我们以前是 prepare 之后同一帧直接 start()，而 `start(long)` 内部会
  // `handler.removeCallbacksAndMessages(null)`，把 prepare() 刚投出的 PREPARE
  // 连同它的 100ms 重试链一起清空 ⇒ `mReady` 永远 false ⇒ `drawTask` 永远 null
  // ⇒ 画布永远空白。（详见灌数据处的长注释。）
  //
  // ⚠️ `prepared()` 是**每次 prepare 成功都会回调一次**的，不是只回调一次。
  //   换集时会重新 prepare，所以这里必须幂等地「重新对齐时间轴」而不是无脑再 start
  //   （`start(pos)` 会清队列 + 重投 START，本身是安全的，但连续两次会让计时器跳一格）。
  DisposableEffect(danmakuView) {
    danmakuView.setCallback(
      object : DrawHandler.Callback {
        override fun prepared() {
          // 走到这里才算「引擎真的活了」，drawTask 已创建、计时器可用。
          // 存活时长由框架自己算（REAL），我们不碰 —— 见类注释「v114：严格按官方用法」。
          val at = (latestPositionForCallback.value * 1000f).toLong().coerceAtLeast(0L)
          // ⚠️⚠️ v111：这里**改用 seekTo，不再用 start()**。
          //
          // `start(long)` 第一件事就是 `handler.removeCallbacksAndMessages(null)`，
          // 把队列里所有消息（含刚投出的 UPDATE/RESUME/PREPARE 重试链）一把清空，
          // 然后 START → `mInSeekingAction = true` + `SEEK_POS` → 才回到 RESUME。
          // 这条链和 `DanmakuPositionSync` 每 250ms 的 seekTo 撞在一起时，
          // `syncTimer()` 会一直 `if (mInSeekingAction || mInSyncAction) return 0;`
          // ⇒ 计时器不推进 ⇒ 弹幕的 layout() 拿到的 currMillisecond 卡在起点
          // ⇒ 除了开局那零点几秒，后面的弹幕永远不满足「已进入存活区间」。
          // 而 `pause()` 也走 `syncTimerIfNeeded()`，被同一条件挡住 ⇒ 播放态跟丢。
          //
          // `seekTo()` 内部是 `removeMessages(RESUME); syncTimerIfNeeded();
          // obtainMessage(SEEK_POS, ms).sendToTarget();`，不碰 PREPARE 链、不清队列，
          // 是「已 prepared 之后定位」的**正确**入口。
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

  // ⚠️ v111：**播放状态对账必须常驻**，不能只在 `playing` 变化时下发一次。
  //    `DanmakuView.resume()` 有三个分支（源码 L387）：
  //      handler 非空且已 prepared → post(mResumeRunnable)  ✅
  //      handler == null           → restart()
  //      其余（handler 非空但**尚未 prepared**）→ **什么都不做**
  //    而 `pause()` 更直接：`if (handler != null) handler.pause()` —— 未 prepared 时
  //    DrawHandler.pause() 只是往队列里投一条 PAUSE，等 PREPARE 走完才被处理，
  //    顺序上没问题，但**反过来 resume 那条会被永丢**。
  //    续播场景正好踩这个点：视频已经开播（playing=true）时弹幕引擎才刚建，
  //    那一次 resume 落进「第三态」被吞掉 ⇒ 引擎一直处于 paused ⇒ 计时器不动
  //    ⇒ 弹幕一条都不出来，但开关、条数、prepared 日志全部正常。
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
  // ⚠️ 反复 prepare 会新建 HandlerThread、弹幕叠成两遍，所以**不能**把 `comments`
  //    整个列表当 key —— 那是每帧 O(n) 深比较，而且改屏蔽词也会重建引擎。
  // ⚠️ 但只用 `episodeKey` 也不够（v108 修的线上问题）：用户开播时开关是关的，
  //    这时 `PlayerActivity` 直接把叠层从组合里摘掉；等他在抽屉里打开开关、
  //    `setEnabled` 补拉回 N 条，叠层**首次组合**、effect 正常跑 —— 这条路径是通的。
  //    真正会漏的是**叠层已在组合里、但那一刻 comments 还是空**的情形
  //    （切集瞬间 `onEpisodeChanged` 先把 comments 清空、拉完才填回来；
  //    mpv 侧 `episodeKey` 此时已经变了，于是 effect 用空列表跑了一遍，
  //    之后 comments 从空变非空而 key 不变 ⇒ 永远不再 prepare ⇒ 画面上一条都没有，
  //    抽屉里却明明白白写着「已匹配 N 条」）。所以第三段 key 用「是否为空」这个**布尔**
  //    而不是列表本身：只在「空 ↔ 非空」翻转时才多跑一次，且保证「有数据必灌一次」。
  //
  // ⚠️⚠️ v110：空列表时**绝不能调 `danmakuView.stop()`**（v109 及以前就是这么写的）。
  //    DFM 的 `stop()` 是**不可逆**的：
  //      handler = null；mHandlerThread.join()；mHandlerThread.quit()；mHandlerThread = null
  //    之后 `resume()` 会走 `else if (handler == null) restart()` → `stop(); start()`，
  //    而 `start()` 里 `handler == null` 又会调**无参** `prepare()` —— 那个只能建线程、
  //    **不会 setParser**，于是 PREPARE 分支的 `mParser == null` 恒成立 ⇒ 每 100ms 自投，
  //    永不 `mReady` ⇒ 永不建 drawTask ⇒ 画面永远空白。这正是「开关能开、条数也有、
  //    就是一条都不渲染」的根因之一。
  //    正确做法：空列表时**什么都不做**（引擎留着，反正没数据它也不画）。
  LaunchedEffect(danmakuView, episodeKey, comments.isEmpty()) {
    if (comments.isEmpty()) {
      // 不 stop()，只清屏；等下一条数据回来时 effect 会因为第三段 key 翻转再跑一次。
      danmakuView.clear()
      return@LaunchedEffect
    }
    val danmakuContext = DanmakuContext.create()
    // ⚠️⚠️ 必须在 prepare() **之前**关掉绘制缓存（v106 线上崩溃根因）：
    //   DFM 的 DrawHandler.prepare() 会读 `isDanmakuDrawingCacheEnabled()` 来决定
    //   建 `CacheManagingDrawTask`（带 `DFM Cache-Building Thread`）还是普通 `DrawTask`。
    //   缓存那条路径里 `CacheManager$3.accept` 在 `createDanmaku()` 返回 null 时直接解引用
    //   ⇒ NPE，且崩在库自己的线程上、try/catch 拦不到
    //   （用户日志 10-19 08:24:34 FATAL EXCEPTION: DFM Cache-Building Thread）。
    //   放在 prepare 之后再调**无效**：线程已经建好了。
    //   代价只是回拖进度时重走一遍轨道分配，几千条弹幕量级肉眼无感。
    danmakuView.enableDanmakuDrawingCache(false)
    // ⚠️ context 必须**在构造 parser 时就传进去**：DanmakuListParser 拿不到
    // setConfig() 存进去的那份（BaseDanmakuParser 没有 getConfig），而 parse() 是无参的。
    val parser = DanmakuListParser(comments, alphaPercent, danmakuContext)
    danmakuView.prepare(parser, danmakuContext)
    applyDisplayConfig(danmakuContext, alphaPercent, fontScale, speedScale, maxOnScreen, areaRatio, preventOverlapping)
    // ⚠️⚠️⚠️ **绝对不能在这里调 `start()`**（v109 及以前的写法，v110 修）。
    //
    // DFM 的 `DanmakuView.start(long position)` 第一件事是：
    //     Handler handler = this.handler;
    //     if (handler == null) { prepare(); } else { handler.removeCallbacksAndMessages(null); }
    //
    // 而 `prepare(parser, config)` 刚刚投出去的那条 **PREPARE 消息**正好排在这个 Handler 的队列里：
    //     DrawHandler.handleMessage(PREPARE):
    //         if (mParser == null || !mDanmakuView.isViewReady()) sendEmptyMessageDelayed(PREPARE, 100);
    //         else prepare(runnable) → mReady = true; mCallback.prepared();
    //
    // `AndroidView` 是**先组合、后测量布局**，所以此刻 `onLayout()` 还没跑 ⇒ `isViewReady()` 为 false
    // ⇒ PREPARE 本该 100ms 后自己重投，等布局好了再建 drawTask。
    // 但 `start()` 的 `removeCallbacksAndMessages(null)` 把这条重试链**连同队列里所有消息一起清空**，
    // 之后再没有任何人投 PREPARE ⇒ `mReady` 永远是 false ⇒ `drawTask` 永远是 null
    // ⇒ `onDraw` 里 `handler.draw(canvas)` 直接返回 ⇒ **画布永远空白，但开关/条数一切正常**。
    //
    // `resume()` 也救不回来：它的实现是
    //     if (handler != null && handler.isPrepared()) post(mResumeRunnable);
    //     else if (handler == null) restart();
    // ——「handler 非空但未 prepared」这个**第三态什么都不做**（库的漏洞）。
    //
    // 正确姿势：**只 prepare，不 start**。PREPARE 成功后 DFM 会回调 `mCallback.prepared()`，
    // 我们在那里再开始画（见下面的 setCallback）。这也是 DFM README 的官方用法。
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

  // ⚠️ v111 诊断采样：把「引擎到底卡在哪一层」一口气打清楚。
  //    用户反馈「开关能开、条数也对、就是不画」时，光看 prepared 日志远远不够 ——
  //    必须同时看到**计时器有没有在推进**和**有没有可见弹幕对象**，这两个指标能一次定位：
  //      · timer 不动            ⇒ 卡在 seek 竞态（`mInSeekingAction` 让 syncTimer 一直返 0）
  //      · timer 在动但 visible=0 ⇒ parser 侧 flags/timer/visibility 没设对（本版修的就是这个）
  //      · visible>0 但看不见      ⇒ 只剩字号/颜色问题（textSize 算错会小到看不见）
  //    每 3 秒一行，只在真正有数据且引擎起来后打；确认没问题后可以整段删掉。
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
          // ⚠️ 这两个数的比值能一次区分两种完全不同的病因：
          //   · ratio ≈ 1.0 且 visible>0 但用户仍说「一会儿就没」 ⇒ 看下面 duration/lifetime
          //     （框架 REAL 或速度因子的实际取值；v115 起寿命是 lifetime，与调速无关）
          //   · ratio 明显 > 1 ⇒ **计时器跑得比真实时间快**，弹幕会被快速"烧完"。
          //     v115 已修高刷屏场景（原库每帧至少推进 16ms）；若仍 >1，是 UPDATE
          //     被重复投递或 seek 竞态，与弹幕本身的时长无关，别再去调 duration。
          val ratio = if (wallDelta > 0) timerDelta.toFloat() / wallDelta else 0f
          Log.i(
            TAG,
            "DFM 状态：timer=${timerNow}，paused=${danmakuView.isPaused}，" +
              "shown=${danmakuView.isShown}，viewReady=${danmakuView.isViewReady}，" +
              "visible=${danmakuView.currentVisibleDanmakus?.size()}，" +
              "size=${danmakuView.width}x${danmakuView.height}，" +
              // ⚠️ v115：value=速度基准（吃速度因子），lifetime=寿命（v115 起不吃因子，
              //   恒定等于框架 REAL）。默认速度下 2340px 屏 lifetime≈5900ms =
              //   「从右出现到完全移出左边缘」；若 clock≈1 而弹幕仍早夭，看 DFM-Diag 的取窗。
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

  // ── v117：竖屏/黑边适配 —— 把弹幕层约束到「视频画面矩形」内 ──────────────
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

  Box(modifier = modifier) {
    if (videoAspect <= 0.01f) {
      // 比例未知（视频还没加载出来 / 调用方没提供）⇒ 老行为：铺满。
      AndroidView(factory = { danmakuView }, modifier = Modifier.fillMaxSize())
    } else {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
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
          AndroidView(factory = { danmakuView }, modifier = Modifier.fillMaxSize())
        } else {
          // ⚠️ 这里必须 **px 精确定位**：`Modifier.offset/size` 只有 Dp 重载，
          //    小数密度（2.75/3.25…）上 dp→px 的 rounding 会让弹幕层和画面
          //    错开一两像素，DanmakuView 的边缘 retainer 判据就跟着偏。
          //    Int 参数也不会隐式转 Float/Dp，所以走 `Modifier.layout` 自己摆：
          //    本节点撑成父级全尺寸，再把子 View 钉在画面矩形的左上角。
          // ⚠️ DanmakuView.onLayout → notifyDispSizeChanged 会按新尺寸重算
          //    REAL 时长与 retainer 边界，运行中改大小是 DFM 支持的标准路径。
          AndroidView(
            factory = { danmakuView },
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
  // 速度：用户可控项，保留（v114 只修语义，不砍功能）。
  //
  // ⚠️ DFM 的 factor 方向和直觉相反，而且它直接乘在寿命上：
  //   `setScrollSpeedFactor(f)` → `Duration.setFactor(f)` → `value = mInitialDuration * f`
  //   ⇒ **f 越大 duration 越长、弹幕越慢**。而我们偏好文案写的是「>1 更快」，
  //   直接把 speedScale 传下去会让「想调快」的用户把弹幕调慢（用户日志实测：
  //   调到 1.6 ⇒ duration 被乘成 16 秒，滚动弹幕赖在屏幕上不走）。
  // ⇒ 这里取倒数：默认 1.0 时 factor=1.0（= 框架默认行为，什么都不做）；
  //   调到 2.0 ⇒ factor=0.5 ⇒ duration 减半 ⇒ 更快，文案终于对得上。
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
 * ⚠️⚠️⚠️ v116：「弹幕活不过 2 秒 / 长弹幕探个头就消失 / 改刷新率没用」的真凶在这里，
 * 不在引擎里（v115 的计时器与寿命修复方向正确但治不了这个病）：
 *
 * 旧代码用「上次 seek 的位置 `lastSeekSec`」当对齐基准，而 `lastSeekSec` **只在 seek
 * 发生时才更新**。正常播放时它永远停在初始值，`now` 却每 250ms 前进 0.25s
 * ⇒ 每约 1.75 秒 `abs(now - lastSeekSec)` 就超过 1.5s 阈值 ⇒ **一次硬 seekTo**。
 *
 * 而 DFM 的 seek 是「跳转」语义，一次 seekTo 会顺手做掉三件事
 * （`DrawHandler.SEEK_POS` → `DrawTask.seek(position)`）：
 *   1. `reset()`：清空渲染器 retainer 与当前取窗；
 *   2. `mStartRenderTime = position`；
 *   3. `DanmakuRenderer.Consumer.accept` 里
 *      `if (drawItem.getActualTime() < startRenderTime) return ACTION_CONTINUE;`
 *      —— **所有 time 早于 seek 位置的弹幕被永久跳过不画**。
 * ⇒ 每次假 seek 之后，屏幕上所有「已经在飞」的弹幕全部中途消失；
 *   下一条弹幕从出现到下次假 seek 最多只能活 ~1.75s（用户看到的「2 秒」），
 *   长弹幕位移慢，1.75s 内只从右缘露出个头 ⇒ 「探了个头就消失」。
 *   这与刷新率、速度设置全都无关（v115 修复因此显得无效）。
 *
 * 修法（v116）：对齐基准从「上次 seek 的位置」改成**引擎自己的计时器**
 * （`danmakuView.currentTime`）——
 *   · 正常播放时引擎与视频同速推进，drift≈0 ⇒ 永不 seek，弹幕活满 lifetime；
 *   · 用户拖动进度、卡顿续播造成的**真实**跳变 ⇒ drift>1.5s ⇒ seek 纠正
 *     （这正是本函数存在的目的，保留原阈值语义）；
 *   · 引擎被暂停/闲置导致计时器冻结时也能被发现并纠正（旧写法发现不了）。
 *
 * ⚠️ 另外两个 v111 时留下的约束仍然有效，不要回退：
 *   1. 循环**常驻**，不绑 `enabled = playing`（否则起播瞬间 effect 启动即退出，
 *      `lastSeekSec` 重置导致开局一次硬 seek，和 prepared() 的定位撞车）。
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
    // ⚠️⚠️⚠️ v111 根因修复：以下三行（flags / timer / visibility）缺了任何一样，
    //   弹幕就**一条都画不出来**，且不报错、不崩、计数正常。逐条对源码：
    //
    // ① `item.setTimer(timer)` —— 必须！
    //    DrawTask.loadDanmakus() 只做了 `parser.setTimer(mTimer)`，那只是存到 parser 字段上；
    //    **库不会替你往下分发给每条 item**，官方 FakeDanmakuView.CustomParser 里是逐条
    //    `item.setTimer(mTimer)` 的。缺了它：
    //      · BaseDanmaku.isTimeOut() = `mTimer == null || ...` ⇒ **恒 true**
    //        ⇒ DanmakuRenderer.Consumer.accept 第一句就 `disp.recycle(drawItem);
    //          return ACTION_CONTINUE` ⇒ 2528 条全部被回收，一条都不进绘制。
    //      · BaseDanmaku.isLate() 同理恒 true ⇒ 就算绕过去也会 `return ACTION_BREAK`
    //        让整轮 forEach 立刻终止。
    //      · R2LDanmaku/FTDanmaku/L2RDanmaku.layout() 里是
    //        `if (mTimer != null) { ... setVisibility(true) ... } setVisibility(false);`
    //        ⇒ mTimer 为 null 时**直接走 else 分支**把弹幕置成不可见，永远不亮。
    //
    // ② `item.flags = mGlobalFlagValues` —— 必须！
    //    BaseDanmaku.flags 默认 null，而 isShown()/isMeasured()/isPrepared()/
    //    setVisibility(true) 全都要读 `flags.XXX_RESET_FLAG` ⇒ NPE 或判等失败。
    //    ⚠️ 必须在 resetAll() **之后**取（DrawTask.loadDanmakus 里 getDanmakus() 返回后
    //    才调 mGlobalFlagValues.resetAll()），且 flags 是**同一个对象引用**，
    //    所以这里拿到的就是 reset 之后的那份，判等天然成立。
    //    官方 DrawHandler.addDanmaku() 也是这么写的：`item.flags = mContext.mGlobalFlagValues;`
    //
    // ③ `item.setVisibility(true)` —— 给初值，且**必须排在 ② 之后**
    //    （它内部 `visibleResetFlag = flags.VISIBLE_RESET_FLAG`，flags 为 null 直接 NPE）。
    //    BaseDanmaku.visibility 默认 0 = INVISIBLE，可见标记也要对齐到当前代。
    //
    // 参照物：同一个 jar 里的 FakeDanmakuView$CustomParser.parse()（官方自己写的
    // 「直接喂一串 Danmaku 数据」的实现），这三行一个不落。
    //
    // ⚠️ `timer` 来自 BaseDanmakuParser 的 public getter `getTimer()`（Kotlin 属性 `timer`）。
    //    DrawTask.loadDanmakus() 里刚做过 `parser.setTimer(mTimer)`，所以这里拿到的
    //    就是 DrawHandler 自己那个 DanmakuTimer —— 逐条下发后，弹幕与帧时钟同源。
    // ⚠️ `flags` 必须在 resetAll() **之后**取（DrawTask.loadDanmakus 里 getDanmakus() 返回后
    //    才调 mGlobalFlagValues.resetAll()），且 flags 是**同一个对象引用**，
    //    所以这里拿到的就是 reset 之后的那份，判等天然成立。
    //    官方 DrawHandler.addDanmaku() 也是这么写的：`item.flags = mContext.mGlobalFlagValues;`
    val timer: DanmakuTimer = this.timer
    val flags = danmakuContext.mGlobalFlagValues

    // ⚠️ **字号换算**：dandanplay 第 3 列是「档位」（25=标准 / 18=小），**不是像素**。
    //    而 `DisplayerConfig.getPaint()` 里是 `paint.setTextSize(danmaku.textSize)` ——
    //    Paint 的单位是 **px**。直接把 25 填进去，在 density=2 的机器上只有 12.5dp，
    //    1080p 横屏下肉眼几乎看不见（这正是「明明渲染了却以为没渲染」的第二种形态）。
    //
    //    系数沿用**库自己的密度约定**：DanmakuFactory 用 `(density - 0.6f)` 把 viewport
    //    折算成滚动时长（`updateViewportState` / `getViewportSizeFactor`），这里反向用它
    //    把字号补齐到同一基准，保证弹幕的视觉大小与滚动速度随密度同步缩放。
    //    下限锁 1f —— 低密度机器上宁可保持原值，也不要把字缩得更小。
    val density = displayer.density
    val sizeFactor = (density - 0.6f).coerceAtLeast(1f)

    // ⚠️ **index 必须唯一**：Danmakus 默认走 TreeSet(TimeComparator)，比较落到
    //    `DanmakuUtils.compare()` —— 先比 time，再比 index，最后一行是库自己的 bug
    //    （`obj1.hashCode() - obj1.hashCode()` 恒为 0）。所以 **time 和 index 都相等时
    //    比较器返回 0 ⇒ TreeSet.add 返回 false ⇒ 这条弹幕被静默丢弃**。
    //    正常入口 `DrawTask.addDanmaku()` 会 `item.index = danmakuList.size()`，
    //    而我们走的是 `parse()` 批量灌数据那条路，**从来没人赋过 index**（恒为 0）
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
      // Alpha 位是**白写**的：`DisplayerConfig.applyPaintConfig()` 里是
      // `paint.setColor(danmaku.textColor & 0x00FFFFFF); paint.setAlpha(transparency);`
      // —— DFM 会把高 8 位抹掉，真正的透明度只认 `DanmakuContext.setDanmakuTransparency()`
      // （我们在 applyDisplayConfig 里已经按同一份 alphaPercent 下发了）。保留 toArgb 只是
      // 保持调用点语义完整，去掉它结果也一样。
      item.textColor = DanmakuPStrings.toArgb(c.colorRgb, alphaPercent)
      // 描边用黑：弹幕压在亮画面上时，纯色字会看不清。
      item.textShadowColor = Color.BLACK
      item.flags = flags
      item.setTimer(timer)
      item.setVisibility(true)
      danmakus.addItem(item)
    }

    // ⚠️⚠️ v114：存活时长**交给框架自己算**，不再从外面覆写。
    //
    // 之前几个版本在这里（以及 prepared()、250ms 对账里）写
    // `MAX_Duration_Scroll_Danmaku.setValue(...)`，理由是库的 REAL 会被
    // `min(9000)/max(4000)` 夹住、在宽屏上偏短。但那是和框架的机制对抗：
    // `NOTIFY_DISP_SIZE_CHANGED` 一发现 view 尺寸变了就用 REAL 把我们的值冲掉，
    // 我们写到 prepared、写到 250ms 对账里追着补，越描越黑。
    //
    // 库的口径本来就是「按 B 站基准屏宽（682）等比缩放」：
    //   REAL = COMMON_DANMAKU_DURATION(3800) × viewportSizeFactor × 屏宽/682
    //        （再被 [4000, 9000] 夹住）
    // 在 2340px 屏上实测约 5.9s —— 正是「从右出现到完全移出左边缘」的时间。
    // 用户要更快/更慢走 `setScrollSpeedFactor`（官方 demo 自己也这么干），
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