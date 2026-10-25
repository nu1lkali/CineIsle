package app.marlboroadvance.mpvex.ui.player.feed

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.dislike.DislikeRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 多久还没拿到时长就判定加载失败 */
private const val LOAD_TIMEOUT_MS = 15_000L

/**
 * 翻页时「撤到一半又得重新加载」的宽限时长（毫秒）。
 *
 * 新页已经接手但首帧还没上屏的那几十毫秒里，先不要把加载圈打回来 —— 相邻页通常
 * 早就 prepared 好了，宽限这点时间足够它把第一帧画上去，用户就不会看到「圈闪一下」。
 * 反过来，真的没画面（网络慢 / 解码卡住）时它也只是让圈晚 0.4 秒出现。
 */
private const val LOADING_GRACE_MS = 400L

/** 拖进度时最多多久把预览位置真正下发一次给内核 */
private const val SEEK_PREVIEW_INTERVAL_MS = 120L

/**
 * 「视界流」——仿抖音竖屏上下滑动连播的播放器（ExoPlayer 多实例版）。
 *
 * ── 为什么现在是「一页一个实例」而不是「一个画面换源」 ──
 * 这一版把内核从 mpv 换成了 ExoPlayer，原因就在这一点上：
 * mpv 是进程级单例（一个进程只能有一个解码实例），滑动时只能换它的源来播放，
 * 「打开 → 探测 → 出首帧」这段时间屏幕是空的，肉眼可见地卡一下。
 * ExoPlayer 没有这个限制，[ExoPlayerPool] 会让相邻几条**各自持有一个实例**并提前
 * 把自己缓冲到就绪 —— 手指刚到位，那一条早就在手上握着了，切过去只需要打开播放。
 *
 * ── 为什么只用 ExoPlayer 的画面、不用它的控件 ──
 * 本页的控件全在 [FeedScreen] 里用 Compose 画。ExoPlayer 自带的控制器一旦冒出来，
 * 屏幕上就两套 UI 打架 —— 所以每一页的画面容器（view_feed_video.xml）里都写着
 * `use_controller=false`，播放器那边天生不提供任何控件。
 *
 * ── 手势如何三分 ──
 * 见 [FeedGestures]：中间带的竖直滑动完全不拦，交给 [VerticalPager] 翻页；
 * 左右两条边缘带在按下瞬间就被认领下来做亮度 / 音量；横向统一做进度调节。
 */
class VerticalFeedActivity : ComponentActivity(), FeedPlayerController, FeedGestureHost, KoinComponent {

  private val embyRepository: EmbyRepository by inject()

  /** 「标记为不喜欢」的仓库。ViewModel 靠它读快照 / 写标记，UI 不直接碰。 */
  private val dislikeRepository: DislikeRepository by inject()

  private lateinit var viewModel: FeedViewModel
  private lateinit var pool: ExoPlayerPool
  private lateinit var audioManager: AudioManager

  /** 三条手势要显示出来的状态（指示条、预览卡片） */
  private val gestureUi = FeedGestureUi()

  private var tickerJob: Job? = null
  private var isExiting = false

  /** 同一条片子播完不应该反复触发「下一个」 */
  private var endHandled = false

  // ── mpv 兜底（只服务个别条目，界面不变）──
  //
  // 内置内核解不了的文件（wmv / asf / rmvb、本机没有对应解码器的编码）交给 mpv。
  // 但**不跳进 mpv 播放页** —— 那会把视界流这套「一屏一条」的简洁界面整个换掉。
  // 这里只叠一层 mpv 的画面（FeedMpvEngine 的 SurfaceView），控件仍然由 FeedScreen 画。
  //
  // mpv 是进程级单例，所以「同一时刻只有一条在用 mpv」：
  // 滑走 → stop（声音立刻停，内核让回空闲）；滑回来 → 重新 play 这一条；
  // 其余条目一律照常走默认引擎（ExoPlayer）。

  private lateinit var mpvEngine: FeedMpvEngine

  /** 已经交给 mpv 的条目下标；这些页不再建 ExoPlayer 实例（池里同样记一份） */
  private val mpvServed = mutableStateListOf<Int>()

  /** mpv 的画面层是否已经挂在窗口里（建好后常驻，靠 visibility 开关） */
  private var mpvHostReady by mutableStateOf(false)

  /** mpv 画面层当前是否可见 */
  private var mpvVisible by mutableStateOf(false)

  /** 正在用 mpv 播放的条目下标；`-1` = 没有 */
  private var mpvIndex = -1

  /** 离开某条 mpv 条目时的播放位置（秒），滑回来时据此续播 */
  private val mpvResumeSec = HashMap<Int, Double>()

  /** 本次起播的时刻，用来做加载看门狗 */
  private var loadStartedAt = 0L

  /**
   * 「画面已经显示过、但当前页还没出帧」的起始时刻（0 = 不在这个状态）。
   *
   * 翻页时 Pager 的当前页已经换过去，可它的首帧还要几十毫秒 —— 这段时间里如果
   * 立刻把 [FeedViewModel.buffered] 压回 false，加载圈会在每次翻页时闪一下。
   * 所以先记时刻、超过 [LOADING_GRACE_MS] 才真的退回加载态。
   */
  private var blankSinceAt = 0L

  // ── 拖进度节流 ──
  private var seekPreviewTargetMs = 0L
  private var lastPreviewCommitAt = 0L
  private var wasPlayingBeforeSeek = false

  // ── 系统手势让位区 ──
  private var _topInsetPx by mutableFloatStateOf(0f)
  override val topInsetPx: Float get() = _topInsetPx
  private var _bottomInsetPx by mutableFloatStateOf(0f)
  override val bottomInsetPx: Float get() = _bottomInsetPx

  // ── 亮度 / 音量 ──
  private var brightnessValue by mutableFloatStateOf(0.6f)
  private var volumeValue by mutableFloatStateOf(0.5f)

  override val brightnessFraction: Float get() = brightnessValue
  override val volumeFraction: Float get() = volumeValue
  override val landscape: Boolean get() = viewModel.landscape

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // ── 边界一：空列表 ──
    // 随机没抽到任何东西时不该进这个页面：一个黑屏且什么都做不了的 Activity
    // 比「不启动」更难解释。
    val items = readItems(intent)
    if (items.isEmpty()) {
      finish()
      return
    }

    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    volumeControlStream = AudioManager.STREAM_MUSIC
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    viewModel = ViewModelProvider(
      this,
      FeedViewModel.factory(
        initialItems = items,
        embyRepository = embyRepository,
        dislikeRepository = dislikeRepository,
        libraryId = intent.getStringExtra(EXTRA_LIBRARY_ID),
        favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES_ONLY, false),
      ),
    )[FeedViewModel::class.java]

    pool = ExoPlayerPool(
      context = this,
      // 解码类失败先换软解试一次（池里对同一条只放行一次），软解也不行才换内核。
      // ⚠️ 只对**当前这条**重试：相邻页的失败等它成为当前条再说 —— 用户还没看到它，
      // 提前重建会白占一个解码器，还会把预缓冲攒好的进度丢掉。
      onError = { index, reason, decoderRelated ->
        if (decoderRelated) {
          handleDecodeFailure(index, reason)
        } else {
          viewModel.errors[index] = reason
        }
      },
      // 帧**真的画到屏幕上**了（不是「渲染器交出去了」）→ 整份状态一起抄回来：
      // 加载圈在这一刻才撤、进度条同时拿到真实时长。只把 buffered 置真是不够的 ——
      // 那样进度条还得再等 250ms 的心跳才有数字。
      onFrameVisible = { index ->
        if (index == viewModel.index) viewModel.applySnapshot(pool.snapshotOf(index))
      },
      // strm 能播的关键（见 ExoPlayerPool.defaultHttpHeaders 的说明）：
      // UA 必须与主播放器（mpv）以及直链预解析探测用的是**同一个常量**，
      // 否则网盘直链的签名校验对不上，服务器直接 403（现象：「strm 提示服务器拒绝」）。
      defaultHttpHeaders = feedStreamHeaders(items),
    )
    brightnessValue = readSystemBrightness()
    volumeValue = readSystemVolume()
    mpvEngine = FeedMpvEngine(this)

    setupSystemGestureInsets()

    setContent {
      val density = LocalDensity.current
      MpvexTheme {
        val pagerState = rememberPagerState(
          initialPage = viewModel.index.coerceAtLeast(0),
          pageCount = { viewModel.items.size },
        )

        // 横竖屏切换期间为 true：这段时间里 Pager 的页码不作数。
        // 屏幕尺寸一变，Pager 会按新的页高重新换算当前的滚动偏移，可能顺势吸附到
        // 相邻页上 —— 那是布局的副作用，不是用户在翻页，所以既不能拿它去改「当前条」，
        // 也不能让它决定画面。用户看到的「点翻转，画面却换成了下一条」就是这么来的。
        var orientationLock by remember { mutableStateOf(false) }

        // 预加载：页码一变（含滑动途中）就把窗口内三页备好、接好画面。
        // 这是「滑过去就有画面」的来源，所以必须跟着实时页码走，不能等落定。
        LaunchedEffect(pagerState.currentPage, viewModel.items.size) {
          val page = pagerState.currentPage
          // 页码一变（哪怕还在滑动途中）就先把 mpv 那条停掉：相邻页这一刻要接手播放，
          // 两边同时出声就是双音轨。**必须先停 mpv 再让池子起播** —— 顺序反了会有
          // 一小段两条音轨叠在一起的时刻（ExoPlayer 起播只要几毫秒）。
          leaveMpvFor(page)
          pool.onPageChanged(page) { index -> viewModel.items.getOrNull(index)?.url }
          if (!orientationLock) {
            // 相邻那条在用户滑过来之前就已经 prepare 完，它的真实时长、进度、首帧
            // 状态全都在手边 —— 一次抄回来，UI 不需要先退回「未知」再等心跳填。
            // 只在这一页**就是当前条**时才抄：滑动途中 currentPage 会先于 settledPage
            // 变成下一页，此刻 vm.index 还没跟上，抄进去的会是一份对不上号的进度。
            if (page == viewModel.index) viewModel.applySnapshot(pool.snapshotOf(page))
            endHandled = false
            loadStartedAt = SystemClock.elapsedRealtime()
          }
        }

        // 落定 → 「当前条」。用 settledPage 而不是 currentPage：后者在滑动途中一直在变，
        // 而且会被上面那种布局副作用带偏；前者只在真正吸附停稳后才动，语义恰好就是
        // 「用户现在停在哪一条」。标题、进度、自动续播都以它为准。
        LaunchedEffect(pagerState) {
          snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
              if (orientationLock) return@collect
              if (page != viewModel.index) viewModel.syncFromPager(page)
              // syncFromPager 刚把 index 换过来，这里立刻用该页实例上的真实值把整套
              // 状态盖回去 —— 同一帧内完成，界面就没有「进度条先塌成细线、再被撑回来」
              // 的中间态。这就是切条时那下闪烁的根治点。
              viewModel.applySnapshot(pool.snapshotOf(page))
              // mpv 只服务个别条目：滑走就停、滑回来再接管（mpv 是进程级单例，
              // 同一时刻只能有一条在用它 —— 见 switchToMpv 的说明）
              syncMpvWithIndex(page)
            }
        }

        // 下标 → 页码。自动播完下一个时由这里把画面滚过去。
        LaunchedEffect(viewModel.index) {
          val target = viewModel.index
          // 自动续播走的是「改下标 → 滚过去」这条路，不经过 settledPage，所以这里
          // 也得回填一次 —— 否则滚过去的那小半秒里，进度条显示的还是上一条的数值。
          viewModel.applySnapshot(pool.snapshotOf(target))
          // 自动续播同样要结算 mpv：下一条若是普通条目，必须把 mpv 停下（否则上一条
          // 的声音会跟着走），若是交给过 mpv 的条目则重新接管。
          syncMpvWithIndex(target)
          if (target in 0 until viewModel.items.size && pagerState.currentPage != target) {
            runCatching { pagerState.animateScrollToPage(target) }
          }
        }

        // 横竖屏切换：等新方向下的布局稳定两帧，把 Pager 锚回「用户真正在看的那一条」。
        // 不锚的话它可能停在因尺寸换算而漂到的相邻页上。这里用 scrollToPage 而不是动画 ——
        // 翻转本来就该是瞬时的，再滚一段动画反而让画面更乱。
        LaunchedEffect(viewModel.landscape) {
          orientationLock = true
          withFrameNanos { }
          withFrameNanos { }
          val target = viewModel.index
          if (target in 0 until viewModel.items.size && pagerState.currentPage != target) {
            runCatching { pagerState.scrollToPage(target) }
          }
          withFrameNanos { }
          orientationLock = false
        }

        // 换片令牌：只处理「重载」这一件事（初始进入时 token 为 0，不能误伤）
        LaunchedEffect(viewModel.playToken) {
          if (viewModel.playToken == 0) return@LaunchedEffect
          val item = viewModel.current ?: return@LaunchedEffect
          pool.reloadCurrent(item.url)
          val resume = viewModel.consumeReloadSeek()
          if (resume > 1.0) restoreSeek(resume)
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .feedGestures(
              host = this@VerticalFeedActivity,
              density = density.density,
            ),
        ) {
          // 相邻页一起留在 Composition 里，它们的画面 View 与实例才得以并存 ——
          // 这是相邻那条能被提前准备好的前提。
          // ── 翻页为什么交回 Pager 原生（userScrollEnabled 保持默认 true）──
          // 之前那一版是「关掉 Pager 滚动，由手势层逐帧喂 dispatchRawDelta」。那条路
          // 确实能翻页，但它**没有速度投掷**：松手只按走过的距离归位，整段行程里画面
          // 1:1 粘着手指走 —— 观感就是「上面那条粘着下面那条」，而不是抖音那种一甩
          // 就嗖地过去。参考 demo 用的是 ViewPager2，手感来自系统 pager 的原生吸附，
          // 所以这里也把滚动 / 投掷 / 吸附全部还给 Pager；手势层只在认领亮度 / 音量 /
          // 进度时介入并消费事件，中间带一概不碰。
          VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
          ) { page ->
            val item = viewModel.items.getOrNull(page)
            if (item != null) FeedVideoPage(index = page, item = item, pool = pool)
          }

          // ── mpv 兜底画面层 ──
          // 叠在 Pager 之上、控件之下：视频画面在最底下，标题/进度/按钮仍然盖在它上面，
          // 所以「换了内核」在界面上看不出来。
          // 建好后**常驻**组合，只用 visibility 开关 —— 反复 detach/attach Surface
          // 会让 mpv 每切一次都重建一次视频输出，画面会闪。
          if (mpvHostReady) {
            AndroidView(
              modifier = Modifier.fillMaxSize(),
              factory = { ctx -> mpvEngine.hostView() ?: View(ctx) },
              update = { root ->
                root.visibility = if (mpvVisible) View.VISIBLE else View.GONE
              },
            )
          }

          FeedScreen(vm = viewModel, controller = this@VerticalFeedActivity, gestureUi = gestureUi)
        }
      }
    }

    startTicker()
  }

  /**
   * 顶 / 底的系统手势让位区。
   *
   * 全屏沉浸式下，这两段分别是「下拉通知栏」和「上滑回桌面」的地盘。把它们从手势
   * 判定里挖掉，用户在这些位置拖动才不至于「既拉出了通知栏、又顺手把亮度拉满」。
   */
  private fun setupSystemGestureInsets() {
    val fallback = 24f * resources.displayMetrics.density
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      _topInsetPx = bars.top.takeIf { it > 0 }?.toFloat() ?: fallback
      _bottomInsetPx = bars.bottom.takeIf { it > 0 }?.toFloat() ?: fallback
      insets
    }
    // 兜底：有些机型首次回调来得很晚，先按常见高度给一个值，避免第一帧判定失效
    _topInsetPx = fallback
    _bottomInsetPx = fallback
  }

  /**
   * 单页的画面：我们自己声明的 TextureView，绑到 [pool] 里属于这一页的那个实例上。
   *
   * 页面被回收时只解绑不解实例 —— 实例去留由池按 ±1 窗口裁剪决定，这样滑回去
   * 之前攒好的缓冲还在。
   *
   * 画面容器不用 PlayerView 的原因见 view_feed_video.xml：截图需要一个我们
   * 自己拿得住引用的 TextureView，而不是事后去问 PlayerView 内部到底用了哪个 View。
   * 画面该占多大也由池按屏幕尺寸算（见 `ExoPlayerPool.applyVideoLayout`），
   * 这里不掺和。
   *
   * ── 点击层为什么在「页」里面 ──
   * 它本来在 [FeedScreen] 顶层，是 Pager 的**兄弟节点**。Compose 的命中测试是
   * 「兄弟节点先到先得」，一层盖住全屏的点击层会把下面 Pager 的触摸全部拦掉 ——
   * 这正是之前不得不把 Pager 滚动关掉的原因。
   *
   * 挪进页内（成为 Pager 滚动容器的后代）之后：
   * - 拖拽照常由祖先滚动容器处理 —— 这就是 `LazyColumn` 里放 `clickable` 的标准写法；
   * - 滚动一旦开始就会消费事件，`detectTapGestures` 见到消费即判定「不是点击」，
   *   于是「划一下顺带把收藏点了」从结构上不可能再发生。
   */
  @Composable
  private fun FeedVideoPage(
    index: Int,
    item: FeedItem,
    pool: ExoPlayerPool,
  ) {
    // 读一次「这一条是否已交给 mpv」：它决定了这一页还要不要接 ExoPlayer 的画面。
    // 在组合里读（而不是在 update 回调里读）是为了让这层在身份变化时重新走一次 update。
    val servedByMpv = index in mpvServed
    Box(modifier = Modifier.fillMaxSize()) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          LayoutInflater.from(ctx).inflate(R.layout.view_feed_video, null, false)
        },
        update = { root ->
          val texture = root.findViewById<TextureView>(R.id.feedTexture)
          // 交给 mpv 的那一条不再建 ExoPlayer 实例：解码器让给 mpv，
          // 画面由 mpv 的 SurfaceView 层输出（它盖在 Pager 之上）。
          // 连这块 TextureView 也一起藏掉：让 SurfaceView 的「打洞」不必去和一层
          // 纯黑的硬件层抢同一块区域，省得个别机型上出现「mpv 在放、屏幕却是黑的」。
          if (servedByMpv) {
            texture.visibility = View.GONE
            pool.detach(index)
          } else {
            texture.visibility = View.VISIBLE
            pool.attach(index, item.url, texture)
          }
        },
        onRelease = { pool.detach(index) },
      )

      // 单击：竖屏切播放/暂停，横屏切控件显隐；双击：收藏
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(index) {
            detectTapGestures(
              onTap = {
                if (viewModel.landscape) {
                  viewModel.controlsVisible = !viewModel.controlsVisible
                } else {
                  togglePlayPause()
                }
              },
              onDoubleTap = { viewModel.toggleFavorite() },
            )
          },
      )
    }
  }

  // ══════════════════ 状态轮询 ══════════════════

  /**
   * 250ms 一次的心跳：把 ExoPlayer 的状态抄进 ViewModel。
   *
   * 为什么不直接吃 ExoPlayer 的回调：它是按帧给位置的，直接灌进 Compose 会把重组
   * 打爆。按节拍轮询，UI 一秒刷四次足够。
   */
  private fun startTicker() {
    tickerJob?.cancel()
    tickerJob = lifecycleScope.launch {
      while (true) {
        runCatching { tick() }
        delay(250)
      }
    }
  }

  private fun tick() {
    if (isExiting) return

    // 先确认「当前这条」的实例还接在屏幕上那块 TextureView 上。
    // 页面 View 与播放实例是分开管的，任一条边被重置都可能留下「两边都活着、
    // 中间断了一根线」的状态 —— 症状就是有声音、进度条在走、画面全黑。
    // 账本一致时这一步什么都不做，所以每 250ms 走一次不花什么钱。
    pool.ensureActiveBound()

    val idx = viewModel.index
    if (idx < 0) return

    // ── mpv 接管的那一条：状态改从 mpv 读 ──
    // 两条内核的状态字段完全对不上（playWhenReady / STATE_BUFFERING ↔ pause /
    // paused-for-cache），所以这里必须整段分流，不能混着读。
    if (mpvIndex == idx) {
      tickMpv(idx)
      return
    }

    // ── 位置 / 时长 / 首帧：读**当前条自己**的实例 ──
    // 刻意不用 pool.active()：滑动过半时 activeIndex 已经先一步切到下一页了，
    // 跟着它读会让进度条先显示下一条的时长，落定后再被回填纠正 —— 又是一次跳动。
    val snap = pool.snapshotOf(idx)
    if (snap != null) {
      viewModel.positionSec = snap.positionMs / 1000.0
      viewModel.durationSec = snap.durationMs / 1000.0
      if (snap.durationMs > 0L) loadStartedAt = 0L
    }

    // ── 撤加载圈的判据必须落在「屏幕上正在出画面的那一条」上 ──
    // 快滑时 viewModel.index 会落后于 Pager 的当前页（要等 settledPage 才回填），
    // 若按 viewModel.index 去读帧账本，读到的可能是相邻页/上一条的 false ——
    // 于是出现「视频已经在放了，圈还挂着」。activeFrameVisible() 读的是 Pager
    // 当前页，也就是用户眼睛看到的那一条，这才是加载态的语义。
    // 另外它每 250ms 重新算一次（而不是「一旦置真就不再回头」），任何一处把
    // buffered 写错了，下一拍就能自愈 —— 这是「圈不消失」唯一的兜底。
    if (pool.activeFrameVisible()) {
      viewModel.buffered = true
      blankSinceAt = 0L
    } else if (viewModel.buffered) {
      // 已经显示过画面、但新页还没出帧：先宽限一下，别让圈在翻页时闪
      val nowMs = SystemClock.elapsedRealtime()
      if (blankSinceAt == 0L) {
        blankSinceAt = nowMs
      } else if (nowMs - blankSinceAt > LOADING_GRACE_MS) {
        blankSinceAt = 0L
        viewModel.buffered = false
      }
    } else {
      // 本来就是加载态，等出帧（由上面第一支或 applySnapshot 抬起）
      blankSinceAt = 0L
    }

    // ── 播放 / 暂停 / 缓冲：跟**真正出声出画面的那一条**走 ──
    // 非活动实例的 playWhenReady 一律 false（见 applyPlaybackState），拿它们判
    // 「是否暂停」，画面明明在播却会冒出暂停按钮。
    val active = pool.active()
    if (active != null) {
      viewModel.paused = !active.playWhenReady
      viewModel.buffering = active.playbackState == Player.STATE_BUFFERING
    }

    // ── 播放结束 → 自动下一个 ──
    if (active != null) {
      val durMs = active.duration.takeIf { it > 0 } ?: 0L
      val posMs = active.currentPosition.coerceAtLeast(0L)
      if (active.playbackState == Player.STATE_ENDED || (durMs > 0L && posMs >= durMs - 500L)) {
        if (!endHandled) {
          endHandled = true
          // 已经是最后一条时留在原地，让用户自己决定要不要退
          viewModel.goNext()
        }
      }
    }

    // ── 有声音、没画面 ──
    // 硬解「建得起来但一帧都不吐」不会报错（AVI 里的 Xvid、部分 10bit HEVC），
    // 只能靠「位置在推进 + 零帧上屏」这个判据抓出来（见 ExoPlayerPool.detectNoVideoStall）。
    // 抓到之后走同一套兜底链：先软解重建一次，还不行就换 mpv 内核。
    pool.detectNoVideoStall()?.let { stall ->
      if (stall.hasVideoTrack) {
        handleDecodeFailure(stall.index, "画面出不来：本机解码器吐不出帧")
      } else {
        // 纯音频文件：换内核也不会有画面，别白切一次
        viewModel.errors[stall.index] = "这个文件里没有可显示的图像"
      }
    }

    // ── 加载看门狗 ──
    if (loadStartedAt > 0L && (snap == null || snap.durationMs <= 0L) &&
      SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS
    ) {
      loadStartedAt = 0L
      viewModel.errors[idx] = "加载失败：源不可用或本机无法解码"
    }
  }

  /**
   * mpv 接管期间的 250ms 心跳（与 ExoPlayer 那条路数值口径保持一致）。
   *
   * 首帧判据用 `video-params/w`（见 [FeedMpvEngine.hasPicture]）而不是「时长出来了」：
   * 后者在容器头读完就有了，拿它撤圈会撤出一个黑屏。
   */
  private fun tickMpv(idx: Int) {
    if (!mpvVisible) return
    // 画面层刚挂上去那几帧 Surface 还没 attach，`play` 只登记不打开（见 FeedMpvEngine.pump）。
    // 心跳是天然的重试点：Surface 一就绪这一行就会把地址真正送进 mpv。
    mpvEngine.pump()
    val pos = mpvEngine.positionSec()
    val dur = mpvEngine.durationSec()
    viewModel.positionSec = pos
    if (dur > 0.0) {
      viewModel.durationSec = dur
      loadStartedAt = 0L
    }
    viewModel.paused = mpvEngine.isPaused()
    viewModel.buffering = mpvEngine.isBuffering()
    if (mpvEngine.hasPicture()) viewModel.buffered = true
    if (mpvEngine.reachedEnd() && !endHandled) {
      endHandled = true
      viewModel.goNext()
    }
    // 看门狗：mpv 迟迟连时长都拿不到，说明源本身不通（不是解码问题，换内核也没用）
    if (loadStartedAt > 0L && dur <= 0.0 &&
      SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS
    ) {
      loadStartedAt = 0L
      viewModel.errors[idx] = "加载失败：源不可用"
    }
  }

  /**
   * 解码失败的三级兜底：**当前这条**先软解重建一次，还不行就切 mpv 内核。
   *
   * 为什么分两级而不是直接换内核：软解重建只花几百毫秒，画面位置、队列、手势全都
   * 还在原地；换内核要重新初始化一套解码链路，能不用就不用。但软解救不回来的那批
   * （容器本身不认识 / 本机压根没有这个编码的解码器）**只有** mpv 能过 ——
   * 内置内核的解复用器就那几个，MediaCodec 里没有的东西再做多少次重建也变不出来。
   *
   * 非当前条不解不碰：用户还没翻到它，此刻重建是白占解码器，交给它自己的错误卡片
   * （「重试」/「用 mpv 播放」两个按钮）就好。
   */
  private fun handleDecodeFailure(index: Int, reason: String) {
    if (index < 0) return
    if (index != viewModel.index) {
      viewModel.errors[index] = reason
      return
    }
    // 已经在由 mpv 负责的条目：没必要再走 ExoPlayer 的兜底链
    if (index in mpvServed) return
    if (pool.beginSoftwareRetry()) {
      viewModel.errors.remove(index)
      viewModel.showHint("硬解不支持，已改用软件解码重试")
      viewModel.requestReload()
      return
    }
    switchToMpv(index)
  }

  /**
   * 把 `index` 这一条切给 mpv —— **界面不动**，只换画面来源。
   *
   * ## 为什么不跳进 mpv 播放页
   *
   * 那个页面是为「坐下来看一部片子」设计的：控制栏、进度条、弹幕、顶栏、播放列表……
   * 而视界流是「一屏一条、上下滑」的随手刷。为了放一条 wmv 就把用户丢进另一套 UI，
   * 等于把场景换掉了。所以这里只在当前条目上叠一层 mpv 的画面（[FeedMpvEngine]），
   * 标题 / 进度 / 按钮 / 手势全部还是 [FeedScreen] 那一套。
   *
   * ## mpv 是进程级单例，为什么这不冲突
   *
   * 同一时刻只有**当前这一条**在用 mpv：滑走时 [syncMpvWithIndex] 会 `stop()` 把内核
   * 让回空闲，下一条立刻回到默认引擎（ExoPlayer）；滑回来再重新交给它。
   * 换句话说 mpv 只是一个「个别条目的旁路」，不接管整场播放。
   *
   * ## 与池子的账要同时结清
   *
   * [ExoPlayerPool.handOffToOtherEngine] 会把这一条的实例释放掉并标记为「别内核负责」：
   * 不释放就白攥着一个解码器（mpv 那边正需要），不标记则翻页回补时会重建一个必然
   * 失败的实例、再黑一次。
   */
  private fun switchToMpv(index: Int) {
    val item = viewModel.items.getOrNull(index) ?: return
    if (index in mpvServed) return

    // ① 画面层：第一次用到时建（初始化必须发生在 surface attach 之前，见 FeedMpvEngine）
    if (!mpvHostReady) {
      val host = mpvEngine.ensureHost(layoutInflater)
      if (host == null) {
        // mpv 起不来（设备 / 内核初始化失败）：如实报错，不要静默黑屏
        viewModel.errors[index] = "无法播放：mpv 内核初始化失败"
        return
      }
      mpvHostReady = true
      // 请求头必须与 ExoPlayer 侧同一套（UA + 服务器 origin 作 Referer）：
      // 换了内核但拉流口径不能变，否则网盘直链的签名校验对不上，切过去就是 403。
      mpvEngine.applyHeaders(feedStreamHeaders(viewModel.items))
    }

    // ② 结算：池子放手，这一条从此由 mpv 负责
    mpvServed.add(index)
    pool.handOffToOtherEngine(index)
    viewModel.errors.remove(index)

    // ③ 接管
    mpvIndex = index
    mpvVisible = true
    mpvEngine.play(item.url)
    viewModel.showHint("这条视频内置内核解不了，已切到 mpv 内核")
  }

  /**
   * 让 mpv 跟上「当前是哪一条」。
   *
   * 三条规则，对应三种走向：
   * · 就是 mpv 正在放的那条 → 什么都不做；
   * · 滑到另一条**交给过 mpv** 的 → 重新交给它播（mpv 仍是同一份实例）；
   * · 滑到普通条目 → `stop()`，声音立刻断，内核让回空闲，画面层隐藏，
   *   这一条由 ExoPlayer 正常播（默认引擎）。
   *
   * 「滑走再滑回」会从头重新打开（`stop` 之后没有别的选择），所以这里记下离开时的
   * 位置，回来直接跳回去 —— 否则用户滑过去瞄一眼再滑回来，片子已经回到开头了。
   */
  private fun syncMpvWithIndex(index: Int) {
    if (mpvIndex == index) return
    val item = viewModel.items.getOrNull(index)
    if (item != null && index in mpvServed) {
      mpvIndex = index
      mpvVisible = true
      endHandled = false
      mpvEngine.play(item.url)
      mpvResumeSec[index]?.takeIf { it > 1.0 }?.let { resumeMpvAt(it) }
      return
    }
    leaveMpvFor(index)
  }

  /**
   * 离开 mpv 那条：记下位置、停掉声音、收起画面层。
   *
   * ⚠️ 调用点不止「落定」，还有**滑动途中**（页码一变就调）：相邻页的 ExoPlayer
   * 在那一刻就已经接手播放了，mpv 若还出声，就是两条音轨叠在一起。
   */
  private fun leaveMpvFor(page: Int) {
    if (mpvIndex < 0 || mpvIndex == page) return
    mpvResumeSec[mpvIndex] = mpvEngine.positionSec()
    mpvEngine.stop()
    mpvVisible = false
    mpvIndex = -1
  }

  /**
   * 刚 `playFile` 完就 seek 会被 mpv 丢掉（时长还没解析出来），所以等到拿到时长再跳。
   * 与 ExoPlayer 那条路的 [restoreSeek] 同一个道理、同一套节拍。
   */
  private fun resumeMpvAt(targetSec: Double) {
    lifecycleScope.launch {
      repeat(40) {
        delay(150)
        if (isExiting || mpvIndex < 0) return@launch
        val dur = mpvEngine.durationSec()
        if (dur > 0.0) {
          mpvEngine.seekTo(targetSec.coerceIn(0.0, (dur - 1.0).coerceAtLeast(0.0)))
          return@launch
        }
      }
    }
  }

  /**
   * 重载后跳回原进度。
   *
   * 刚 prepare 完时时长常常还是未知，此刻 seek 会被内核丢掉（表现为拖完又弹回开头），
   * 所以这里一直等到时长出来再跳。
   */
  private fun restoreSeek(targetMs: Double) {
    lifecycleScope.launch {
      repeat(40) {
        delay(150)
        if (isExiting) return@launch
        val player = pool.active() ?: return@launch
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        if (dur > 0L) {
          player.seekTo((targetMs * 1000.0).toLong().coerceIn(0L, dur - 1000L).coerceAtLeast(0L))
          return@launch
        }
      }
    }
  }

  // ══════════════════ FeedPlayerController ══════════════════

  override fun togglePlayPause() {
    // mpv 接管的那条：两条内核的「暂停」不是一个字段，必须分流
    if (mpvIndex == viewModel.index) {
      val next = !mpvEngine.isPaused()
      mpvEngine.setPaused(!next)
      viewModel.paused = !next
      return
    }
    val player = pool.active() ?: return
    val next = !player.playWhenReady
    pool.setPlayWhenReady(next)
    viewModel.paused = !next
  }

  override fun seekTo(sec: Double) {
    if (mpvIndex == viewModel.index) {
      mpvEngine.seekTo(sec)
      return
    }
    pool.active()?.seekTo((sec * 1000.0).toLong().coerceAtLeast(0L))
  }

  override fun reload(url: String, resumeSec: Double) {
    // 已交给 mpv 的条目没有「重载 ExoPlayer」这回事：重新下发给 mpv 即可
    if (mpvIndex == viewModel.index) {
      mpvEngine.play(url)
      return
    }
    viewModel.requestReload()
  }

  /**
   * 截图。
   *
   * 两条内核取像素的方式不同，必须分开：
   * · ExoPlayer 那条渲染在**我们自己持有的 TextureView** 上，直接 `getBitmap()`；
   * · mpv 那条渲染在 SurfaceView 的独立图层里，拿不到帧数据 ⇒ 让 mpv 自己
   *   `screenshot-to-file` 写一张原分辨率 PNG，再转存进相册。
   *
   * 三种失败分开报：View 找不到（页面还没铺好）、Surface 还没就绪（刚滑过来）、
   * GPU 还没吐出过帧。这样用户看到的那句话能说明到底卡在哪一步。
   */
  override fun screenshot() {
    val name = "CineIsle_" +
      SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".png"

    if (mpvIndex == viewModel.index) {
      shootMpvFrame(name)
      return
    }

    val texture = pool.textureOf(viewModel.index)
    if (texture == null) {
      viewModel.showHint("截图失败：画面还没准备好")
      return
    }
    if (!texture.isAvailable) {
      viewModel.showHint("截图失败：画面尚未就绪，稍等一下再试")
      return
    }
    val bitmap = runCatching { texture.bitmap }.getOrNull()
    if (bitmap == null) {
      viewModel.showHint("截图失败：还没渲染出画面")
      return
    }
    lifecycleScope.launch {
      val ok = saveBitmapToGallery(bitmap, name)
      viewModel.showHint(if (ok) "截图已保存到相册" else "截图失败")
    }
  }

  /**
   * mpv 那条路的截图：先落到缓存目录，再按公共相册的规矩存一份，最后删掉中转文件。
   *
   * 不能直接让 mpv 写进相册目录：Android 10+ 的公共目录要走 MediaStore 才会有正确的
   * 索引（否则相册里看不到，见 [saveBitmapToGallery] 的说明）。
   */
  private fun shootMpvFrame(displayName: String) {
    val temp = File(cacheDir, "feed_shot.png")
    if (!mpvEngine.screenshot(temp)) {
      viewModel.showHint("截图失败：mpv 写不出这一帧")
      return
    }
    lifecycleScope.launch {
      val ok = runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeFile(temp.absolutePath)
        bitmap != null && saveBitmapToGallery(bitmap, displayName)
      }.getOrDefault(false)
      temp.delete()
      viewModel.showHint(if (ok) "截图已保存到相册" else "截图失败")
    }
  }

  /**
   * 把截图放进相册。
   *
   * Android 10 起不能再往公共 Pictures 目录里直接扔文件，必须走 MediaStore；
   * 老版本则写入公共目录后手动触发一次媒体扫描，否则文件躺在磁盘上但相册里找不到。
   */
  private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CineIsle")
      }
      val uri = runCatching {
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      }.getOrNull() ?: return false
      return runCatching {
        contentResolver.openOutputStream(uri)?.use { stream ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } != null
      }.getOrDefault(false)
    }

    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val target = File(dir, displayName)
    return runCatching {
      FileOutputStream(target).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
      // 不发这次扫描的话，用户在相册里找不到刚截的图
      MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
      true
    }.getOrDefault(false)
  }

  override fun toggleOrientation() {
    // 竖屏内容不提供旋转：转到横屏之后画面还是同样大小（我们一律完整显示、不裁画面），
    // 两侧却凭空多出两条黑边 —— 除了把画面挤小没有任何收益，只会让人以为按钮坏了。
    // 直接拦住并说明原因，比默默转过去更好解释。
    if (pool.activeIsPortrait()) {
      viewModel.showHint("竖屏视频无需旋转")
      return
    }
    val next = !viewModel.landscape
    viewModel.landscape = next
    requestedOrientation = if (next) {
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    viewModel.controlsVisible = true
  }

  override fun exit() {
    isExiting = true
    finish()
  }

  /** 错误卡片上的「用 mpv 播放」：用户主动换内核，与自动兜底走同一条路 */
  override fun playInMpv() {
    switchToMpv(viewModel.index)
  }

  // ══════════════════ FeedGestureHost ══════════════════

  override val durationMs: Long
    get() = if (mpvIndex == viewModel.index) {
      (mpvEngine.durationSec() * 1000.0).toLong().takeIf { it > 0 } ?: 0L
    } else {
      pool.active()?.duration?.takeIf { it > 0 } ?: 0L
    }

  override val positionMs: Long
    get() = if (mpvIndex == viewModel.index) {
      (mpvEngine.positionSec() * 1000.0).toLong().coerceAtLeast(0L)
    } else {
      pool.active()?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

  /**
   * 只改**当前 Activity 窗口**的亮度，不动系统设置。
   *
   * 这与 Flutter 版那套 `setScreenBrightness` 有本质区别：后者是系统级写入，用户退出
   * 页面之后整台手机还停留在刚才那个亮度。窗口级亮度随着 Activity 销毁自动失效，
   * 零残留，也不需要任何权限。
   */
  override fun setBrightness(fraction: Float) {
    brightnessValue = fraction.coerceIn(0.01f, 1f)
    val lp = window.attributes
    lp.screenBrightness = brightnessValue
    window.attributes = lp
  }

  /** 读一次系统亮度作为指示条的起点，之后就不再碰系统设置了 */
  private fun readSystemBrightness(): Float =
    window.attributes.screenBrightness.takeIf { it in 0f..1f }
      ?: runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .coerceIn(0, 255) / 255f
      }.getOrDefault(0.6f)

  private fun readSystemVolume(): Float {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0.5f
    return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
  }

  /** flags = 0：不要弹出系统的音量条，我们自己有指示条 */
  override fun setVolume(fraction: Float) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    volumeValue = fraction.coerceIn(0f, 1f)
    runCatching {
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeValue * max).roundToInt(), 0)
    }
  }

  override fun showVerticalIndicator(kind: VerticalZone?, fraction: Float) {
    gestureUi.showIndicator(kind, fraction)
  }

  override fun beginSeek(startMs: Long) {
    seekPreviewTargetMs = startMs
    lastPreviewCommitAt = 0L
    gestureUi.seekStartMs = startMs
    gestureUi.seekTargetMs = startMs
    if (mpvIndex == viewModel.index) {
      wasPlayingBeforeSeek = !mpvEngine.isPaused()
      mpvEngine.setPaused(true)
      return
    }
    val player = pool.active() ?: return
    wasPlayingBeforeSeek = player.playWhenReady
    player.playWhenReady = false
  }

  /**
   * 拖动过程中的预览。
   *
   * 每 [SEEK_PREVIEW_INTERVAL_MS] 才真正下发一次 seek：画面跟手变、但又不至于每个
   * 移动事件都让解码器重新定位一次（那会在高码率片子上卡成一帧一帧的幻灯片）。
   * 卡片上的数字则是实时更新的，无论有没有下发。
   */
  override fun previewSeek(targetMs: Long) {
    seekPreviewTargetMs = targetMs
    gestureUi.seekTargetMs = targetMs
    val now = SystemClock.elapsedRealtime()
    if (now - lastPreviewCommitAt < SEEK_PREVIEW_INTERVAL_MS) return
    lastPreviewCommitAt = now
    if (mpvIndex == viewModel.index) {
      mpvEngine.seekTo(targetMs / 1000.0)
      return
    }
    pool.active()?.seekTo(targetMs)
  }

  override fun commitSeek(targetMs: Long) {
    if (mpvIndex == viewModel.index) {
      mpvEngine.seekTo(targetMs / 1000.0)
      if (wasPlayingBeforeSeek) mpvEngine.setPaused(false)
      wasPlayingBeforeSeek = false
      gestureUi.endSeek()
      return
    }
    val player = pool.active()
    player?.seekTo(targetMs)
    if (wasPlayingBeforeSeek) player?.playWhenReady = true
    wasPlayingBeforeSeek = false
    gestureUi.endSeek()
  }

  override fun requestEdgeBack() {
    exit()
  }

  // ══════════════════ 生命周期 ══════════════════

  override fun onPause() {
    super.onPause()
    // 进后台一律暂停：回来时不自动续播，免得用户只是切出去回个消息声音就冒出来
    runCatching { pool.setPlayWhenReady(false) }
    if (mpvIndex >= 0) mpvEngine.setPaused(true)
    viewModel.paused = true
  }

  override fun onDestroy() {
    isExiting = true
    tickerJob?.cancel()
    tickerJob = null
    pool.releaseAll()
    // mpv 是进程级单例：不释放就会把内核连同它的线程一直挂在进程里
    mpvEngine.release()
    super.onDestroy()
  }

  companion object {
    private const val EXTRA_ITEMS = "feed_items"
    private const val EXTRA_LIBRARY_ID = "feed_library_id"
    private const val EXTRA_FAVORITES_ONLY = "feed_favorites_only"

    /**
     * 视界流拉流用的默认请求头。
     *
     * ## 为什么要 Referer 指向 **Emby 服务器**而不是直链自己
     *
     * strm 的真实媒体在网盘 CDN 上。网盘的防盗链普遍要求「Referer 是某个已知站点」，
     * 而**自引用的 Referer（直链自己的域名）常被直接判为非法** ——
     * 现象就是用户看到的「服务器拒绝了」。主播放器（mpv）早就是这么做的
     * （`EmbyViewModel.putStreamHeaders`），这里跟它保持一致。
     *
     * ## 为什么从 [items] 的 URL 反推 origin，而不是注入服务器对象
     *
     * 池是在 `onCreate` 里**同步**构造的，而拿当前服务器要走
     * `embyRepository.awaitCurrentServer()`（suspend）。而视界流的 URL 本身就是
     * `{host}/emby/Videos/{id}/stream?...`（见 `EmbyClient.videoStreamUrl`），
     * **origin 就在字符串里** —— 直接解析即可，不必为了取一个前缀把构造改成异步。
     *
     * ⚠️ 只取**第一条能解析出 origin 的** URL：同一批队列来自同一个服务器，
     * 而加载更多追加的条目也是同库（同 server），所以一条足够。
     * 全都解析不出来时退化为「只带 UA」—— 至少比什么都不带强。
     */
    private fun feedStreamHeaders(items: List<FeedItem>): Map<String, String> {
      val headers = LinkedHashMap<String, String>()
      headers["User-Agent"] = EmbyClient.STREAM_USER_AGENT
      items.asSequence()
        .mapNotNull { runCatching { Uri.parse(it.url) }.getOrNull() }
        .firstOrNull { it.scheme != null && it.host != null }
        ?.let { u ->
          val port = if (u.port != -1) ":${u.port}" else ""
          headers["Referer"] = "${u.scheme}://${u.host}$port"
        }
      return headers
    }

    /**
     * 启动视界流。
     *
     * @param items 随机出来的播放队列；**为空就不启动**，调用方应在此之前把
     *              「没有可播放内容」提示到位
     */
    fun launch(
      context: Context,
      items: List<FeedItem>,
      libraryId: String?,
      favoritesOnly: Boolean,
    ) {
      if (items.isEmpty()) return
      val intent = Intent(context, VerticalFeedActivity::class.java).apply {
        putExtra(EXTRA_ITEMS, ArrayList(items))
        putExtra(EXTRA_LIBRARY_ID, libraryId)
        putExtra(EXTRA_FAVORITES_ONLY, favoritesOnly)
      }
      if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun readItems(intent: Intent): List<FeedItem> =
      (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<FeedItem>).orEmpty()
  }
}
