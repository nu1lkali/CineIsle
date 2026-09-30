package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.dlna.CastPayload
import app.marlboroadvance.mpvex.dlna.DlnaCastManager
import app.marlboroadvance.mpvex.dlna.DlnaSheet
import app.marlboroadvance.mpvex.preferences.GsyKernelKind
import app.marlboroadvance.mpvex.preferences.GsyPreferences
import app.marlboroadvance.mpvex.preferences.GsyRenderKind
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlayerActions
import app.marlboroadvance.mpvex.ui.player.controls.EmbyFavoritePlayerButton
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.cache.CacheFactory
import com.shuyu.gsyvideoplayer.cache.ProxyCacheManager
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.listener.GSYVideoShotSaveListener
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.player.SystemPlayerManager
import com.shuyu.gsyvideoplayer.subtitle.GSYSubtitleSource
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import `is`.xyz.mpv.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import tv.danmaku.ijk.media.exo2.ExoPlayerCacheManager
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * GSYVideoPlayer 备用内核播放页 —— **GSY 官方 demo（`DetailPlayer`）的等价实现**。
 *
 * 用法严格照官方样例，不写任何自定义转接层：
 *
 * 1. 布局里直接声明 [CineIsleGsyPlayer]（它自己把内部布局换成 CineIsle 版）；
 * 2. 起播前把「全局静态」和「实例级」偏好全部灌进 GSY 官方 setter（见 [applyStatics] / [configurePlayer]）；
 * 3. `setUp(url, cacheWithPlay, title)` 绑定地址 → `startPlayLogic()` 起播；
 * 4. 全屏走**官方那套 window 全屏**：`orientationUtils.resolveByClick()` 先转屏，
 *    再 `startWindowFullscreen(...)`（或由 GSY 自己在配置变化里触发）。只有走这条路，
 *    `setLockLand` / `setNeedLockFull` / `setHideKey` / `setFullHideStatusBar` /
 *    `setAutoFullWithSize` / `setShowFullAnimation` 这些「全屏专属」能力才会真的生效；
 * 5. 返回键：先 `orientationUtils.backToProtVideo()` 回竖屏，再 `backFromWindowFull()` 退全屏，
 *    最后才 `finish()`；
 * 6. `onPause → onVideoPause()`、`onResume → onVideoResume()`、退出 → `GSYVideoManager.releaseAllVideos()`。
 *
 * 它不继承 [PlayerActivity]：mpv 那一整套 Compose 控件 / ViewModel / 属性门面与 GSY 无关，
 * 混在一起只会互相干扰。
 *
 * 注意 GSY 的 window 全屏是**克隆了一个新的播放器实例**（反射调本类的 (Context, Boolean) 构造器），
 * 那个实例不带我们在竖屏实例上绑的点击监听，所以拿到之后要重新 `configurePlayer` 一次 ——
 * 入口有两条：`startWindowFullscreen` 的返回值，以及官方回调 `onEnterFullscreen`
 * （由传感器/配置变化触发的全屏走后者），见 [installCallbacks] / [configureClone]。
 *
 * ── 与 mpv 播放页的「内核切换」────────────────────────────────────────────
 * 本页顶栏右端的双向箭头按钮会把**当前视频 + 整份播放队列 + 当前进度**交回 mpv
 * （[switchToMpvPlayer]）；反方向由 mpv 播放页「更多 → 用 GSY 播放」发起。
 * 两边都用 [PlayerHandoff] 承载上下文，所以切过去接着放、队列不丢。
 */
class GsyPlayerActivity : ComponentActivity() {
  private val prefs: GsyPreferences by inject()

  /** 「播完自动下一集」沿用 mpv 那套全局播放偏好，避免同一件事在两处各有一份开关 */
  private val playerPreferences: PlayerPreferences by inject()

  /** DLNA 投屏：与 mpv 播放页 / 详情页共用同一个单例（发现、连接、投屏状态都在它里面） */
  private val dlnaManager: DlnaCastManager by inject()

  private var player: CineIsleGsyPlayer? = null

  /** 全屏时 GSY 克隆出来的那个实例（退出全屏后置空） */
  private var fullscreenPlayer: CineIsleGsyPlayer? = null

  private var orientationUtils: OrientationUtils? = null

  private var pipHelper: CineIsleGsyPipHelper? = null

  /** 当前标题（`setUp` 与「标题为空就别留空底栏」都要用） */
  private var videoTitle: String = ""

  // ── 本次播放的队列（由 [PlayerHandoff] 灌入，切集时在页内自行维护）──
  private var playlist: List<Uri> = emptyList()
  private var titles: List<String> = emptyList()
  private var seriesKeys: List<String> = emptyList()
  private var currentIndex: Int = 0
  private var currentUri: Uri? = null
  private var playlistId: Int? = null
  private var embyServerId: Long? = null
  private var embyItemIds: List<String> = emptyList()
  private var headers: Array<String>? = null

  // ── 本次播放的外挂字幕（切集不清，用户选一次就能一直用）──
  private var subtitleSource: GSYSubtitleSource? = null
  private var subtitleLabel: String? = null
  private var subtitleOffsetMs: Long = 0L

  /**
   * 用户手动强制横屏中。
   *
   * GSY 的「竖屏视频自动竖屏全屏」（`setAutoFullWithSize`，本项目默认开）会让
   * `OrientationUtils.resolveByClick()` 对竖屏视频**直接 return** —— 表现就是
   * 「竖屏视频点全屏没反应、点好几次才切过去」。用户手动按横屏键时把这项临时关掉，
   * 全屏期间保持关闭，退出全屏再恢复。
   */
  private var forcedLandscape = false

  /** 投屏面板（Compose）挂在内容根布局上的覆盖层 */
  private var castOverlay: ComposeView? = null

  /**
   * 正在播的那个实例。
   *
   * 全屏时真正的播放器是 GSY 克隆出来的实例，`GSYVideoManager.instance().listener()`
   * 正是「当前接管播放的那个 View」（GSY 自己的 `GSYVideoHelper` 也是这么取的），
   * 所以切集 / 取进度都必须走它，否则会去操作背后那个已经停住的小屏实例。
   */
  private val activePlayer: CineIsleGsyPlayer?
    get() = (GSYVideoManager.instance().listener() as? CineIsleGsyPlayer) ?: player

  /** 选外挂字幕：GSYSubtitleLoader 内部对 `content://` 走 ContentResolver，SAF 给的 URI 可直接用 */
  private val pickSubtitleFile =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) loadSubtitle(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 播放页是纯黑底，走边到边（与 mpv 播放页一致），安全区由我们自己按 insets 垫
    enableEdgeToEdge()
    setContentView(R.layout.activity_gsy_player)

    // 播放页常亮；音量键交给「媒体音量」（只是改按键路由，不动音量本身）
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    volumeControlStream = AudioManager.STREAM_MUSIC

    applySystemBars()
    applySafeArea(findViewById(R.id.gsy_player))

    val view = findViewById<CineIsleGsyPlayer>(R.id.gsy_player)
    player = view
    pipHelper = CineIsleGsyPipHelper(this)

    if (!readSession(intent)) {
      finish()
      return
    }

    // ── 官方 demo 的「三件套」 ──
    setUpOrientation(view)
    view.getFullscreenButton()?.setOnClickListener { toggleLandscapeFullscreen() }
    view.getBackButton()?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    installCallbacks(view)
    bindControls(view)
    applyTitlePlacement(view)
    setUpFavoriteButton()

    // 续播 / 起播；显式「从头播放」时带 play_from_start，重建播放页（换渲染方式）时带
    // gsy_restore_position，此时不受「续播到上次位置」开关影响。
    startSession(
      positionMs = intent.getIntExtra("position", 0).toLong(),
      forceRestore = intent.getBooleanExtra(EXTRA_RESTORE_POSITION, false),
      playFromStart = intent.getBooleanExtra("play_from_start", false),
    )

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          // 官方 demo 的返回顺序：先回竖屏 → 再退全屏 → 都没得退才关页面
          if (castOverlay != null) {
            hideCastSheet()
            return
          }
          orientationUtils?.backToProtVideo()
          forcedLandscape = false
          if (GSYVideoManager.backFromWindowFull(this@GsyPlayerActivity)) return
          finish()
        }
      },
    )
  }

  /**
   * 单实例（`launchMode=singleTask`）页面被复用时要重载一次交接单。
   *
   * 典型场景：GSY → mpv → 又切回 GSY。虽然正常路径下前一个 GSY 已经 finish 了，
   * 但用户可能从通知 / 桌面快捷方式再次拉起，此时不能拿着旧视频接着放。
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val view = player ?: return
    if (!readSession(intent)) return

    // 换片子了，上一部的外挂字幕不再适用
    clearSubtitle()

    applyStatics()
    configurePlayer(view)
    applyTitlePlacement(view)
    bindControls(view)
    startSession(
      positionMs = intent.getIntExtra("position", 0).toLong(),
      forceRestore = true,
      playFromStart = intent.getBooleanExtra("play_from_start", false),
    )
  }

  // ────────────────────────────────────────────────────────────────────────
  // 播放会话：交接单 → 播放队列 → 起播
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把 intent 里的播放上下文读进字段。
   *
   * 地址优先取**队列里当前那一项**（`playlist_index` 是权威的「现在放哪个」），
   * 没有队列时才退回 `intent.data` / `gsy_url`（单视频播放，以及 Emby 那类直接给地址的调用方）。
   *
   * @return false 表示拿不到任何可播地址（调用方应当直接收摊）
   */
  private fun readSession(source: Intent): Boolean {
    val handoff = PlayerHandoff.read(source)
    playlist = handoff.playlist
    titles = handoff.titles
    seriesKeys = handoff.seriesKeys
    currentIndex = handoff.safeIndex
    playlistId = handoff.playlistId
    embyServerId = handoff.embyServerId
    embyItemIds = handoff.embyItemIds
    headers = handoff.headers

    currentUri =
      playlist.getOrNull(currentIndex)
        ?: source.data
        ?: source.getStringExtra(EXTRA_URL)?.toUri()

    if (currentUri == null) return false

    // 标题：交接单里的「与本项一一对应的标题」优先，其次才是 intent 里的兜底字段。
    // Emby 的流地址末段固定是 "stream"，没有标题列表就只能显示 "stream"。
    videoTitle =
      titles.getOrNull(currentIndex)?.takeIf { it.isNotBlank() }
        ?: source.getStringExtra("title")
        ?: source.getStringExtra(EXTRA_TITLE)
        ?: source.getStringExtra("filename")
        ?: ""

    // Emby 收藏态：extras 的 key 与 mpv 播放页完全一致，直接交给那个单例去拉
    EmbyPlayerActions.bind(source)
    return true
  }

  /**
   * 起播当前 [currentUri]。
   *
   * 全局静态偏好必须在 `setUp` 之前落定（`GSYVideoType` 是进程级静态值，
   * 起播后再改要等下一次起播）；实例级偏好同理（`setNeedShowWifiTip` /
   * `setOverrideExtension` / `setSpeed` 都在 prepare 时读）。
   */
  private fun startSession(
    positionMs: Long,
    forceRestore: Boolean,
    playFromStart: Boolean,
  ) {
    val view = player ?: return
    val uri = currentUri ?: return

    applyStatics()
    configurePlayer(view)
    applyTitlePlacement(view)

    if (positionMs > 0 && !playFromStart && (forceRestore || prefs.resumePosition.get())) {
      // setSeekOnStart 必须在 startPlayLogic() 之前调用（官方要求）
      view.setSeekOnStart(positionMs)
    } else {
      view.setSeekOnStart(0)
    }

    setUpCurrent(view)
    syncNavigation(view)

    view.startPlayLogic()
  }

  /**
   * 把当前这一集的地址交给播放器。
   *
   * 网络流的请求头（User-Agent / Referer）走官方 `setUp(url, cache, cachePath, mapHeadData, title)`；
   * 没有头就走三参数版，避免平白多传一个空 Map。
   */
  private fun setUpCurrent(target: CineIsleGsyPlayer) {
    val uri = currentUri ?: return
    val url = playableUrl(uri)
    applyTitleVisibility(target)

    val headData = headerMap()
    if (headData != null) {
      target.setUp(url, prefs.cacheWithPlay.get(), null, headData, videoTitle)
    } else {
      target.setUp(url, prefs.cacheWithPlay.get(), videoTitle)
    }
    // 外挂字幕是「跟着播放器实例」的，重新 setUp 后要再挂一次
    applySubtitle(target)
  }

  /**
   * 换到队列里的第 [index] 个视频。
   *
   * GSY 官方对「一个 View 连播多个视频」的用法就是重新 `setUp` + `startPlayLogic`
   * （官方的 `ListGSYVideoPlayer` / `GSYVideoHelper` 就是这么做的），
   * 这里的 `setSpeed` / `setLooping` / 渲染方式等实例状态都会保留。
   *
   * 目标实例取 [activePlayer]：全屏状态下真正在播的是克隆实例，必须换它。
   */
  private fun playIndex(index: Int) {
    if (index !in playlist.indices) return
    val target = activePlayer ?: return

    currentIndex = index
    currentUri = playlist[index]
    videoTitle = titles.getOrNull(index)?.takeIf { it.isNotBlank() } ?: ""

    // Emby 队列：让收藏按钮跟着切到这一集
    EmbyPlayerActions.onItemChanged(embyItemIds.getOrNull(index))

    // 小屏实例即使正在后台站着，也要知道「现在放的是哪一集」，
    // 否则退出全屏时标题 / 地址会回退到切集之前那一集。
    player?.takeIf { it !== target }?.getTitleTextView()?.let {
      it.text = videoTitle
      it.visibility = if (videoTitle.isBlank()) View.GONE else View.VISIBLE
    }

    target.setSeekOnStart(0)
    setUpCurrent(target)
    syncNavigation(target)
    target.startPlayLogic()
  }

  /** 上一集 / 下一集按钮的可用性与显隐（队列 ≤ 1 时整块不出现） */
  private fun syncNavigation(target: CineIsleGsyPlayer) {
    target.setPlaylistNavigation(
      hasPrevious = currentIndex > 0,
      hasNext = currentIndex < playlist.size - 1,
    )
  }

  /** 队列里还有下一集，且「播完自动下一集」开着 */
  private fun canAdvance(): Boolean =
    playlist.size > 1 && currentIndex < playlist.size - 1 && playerPreferences.autoplayNextVideo.get()

  // ────────────────────────────────────────────────────────────────────────
  // 与 mpv 播放页互相切换
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把「当前视频 + 整份播放队列 + 当前进度」交给 mpv 播放页。
   *
   * 只传一个地址是不够的：那样切过去就只剩一个孤零零的视频，队列、标题、
   * 「现在放到第几个」全丢，Emby 那边的进度回传也断档 —— 所以整份 [PlayerHandoff] 都要带上。
   */
  private fun switchToMpvPlayer() {
    val uri = currentUri
    if (uri == null) {
      Toast.makeText(this, R.string.player_engine_switch_unavailable, Toast.LENGTH_SHORT).show()
      return
    }

    val positionMs = activePlayer?.getCurrentPositionWhenPlaying() ?: 0L

    val target =
      Intent(Intent.ACTION_VIEW, uri, this, PlayerActivity::class.java).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 显式指定 mpv：本页就是「备用内核」，切回去当然要用主内核
        putExtra(PlayerActivity.EXTRA_ENGINE, EngineKind.MPV.name)
        putExtra("internal_launch", true)
        putExtra("title", videoTitle)
        putExtra("filename", videoTitle)
        PlayerHandoff(
          playlist = playlist,
          titles = titles,
          seriesKeys = seriesKeys,
          index = currentIndex,
          playlistId = playlistId,
          positionMs = positionMs,
          seriesKey = seriesKeys.getOrNull(currentIndex),
          embyServerId = embyServerId,
          embyItemIds = embyItemIds,
          headers = headers,
        ).writeTo(this)
      }

    startActivity(target)
    // 立刻收摊：两套播放器同时持有解码器 / 音频焦点只会互相打架
    finish()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 偏好 → GSY 官方 API
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 全局静态偏好：**必须在 setUp 之前**调用。
   *
   * 这几项是进程级静态值（`GSYVideoType` / `PlayerFactory` 里都是 static），
   * 而且按官方文档「设置时机：`setUp` 之前生效」。
   */
  private fun applyStatics() {
    val kernel = prefs.kernel.get()

    // 解码内核：PlayerFactory 内部用 newInstance() 反射构造，所以只能传 Class
    PlayerFactory.setPlayManager(
      when (kernel) {
        GsyKernelKind.IJK -> IjkPlayerManager::class.java
        GsyKernelKind.SYSTEM -> SystemPlayerManager::class.java
        GsyKernelKind.EXO -> Exo2PlayerManager::class.java
      },
    )
    // 缓存方案要跟内核配套（官方 skills/04-kernel-switch 的对照表）
    CacheFactory.setCacheManager(
      if (kernel == GsyKernelKind.EXO) {
        ExoPlayerCacheManager::class.java
      } else {
        ProxyCacheManager::class.java
      },
    )

    // 渲染载体：TEXTURE / SURFACE / GLSURFACE（滤镜只在 GLSURFACE 下有效）
    GSYVideoType.setRenderType(prefs.renderKind.get().value)
    // 显示比例
    GSYVideoType.setShowType(prefs.showKind.get().value)

    // IJK 硬解码双通道
    if (prefs.hardwareDecode.get()) {
      GSYVideoType.enableMediaCodec()
    } else {
      GSYVideoType.disableMediaCodec()
    }
    if (prefs.smartFallback.get()) {
      GSYVideoType.enableSmartMediaCodec()
    } else {
      GSYVideoType.disableSmartMediaCodec()
    }
  }

  /**
   * 实例级偏好。竖屏实例在 `setUp` 之前调，全屏克隆实例在 `startWindowFullscreen` 返回之后调
   * （官方 `cloneParams` 只搬了一部分字段，`mHideKey` / `mNeedLockFull` / `mFullHideStatusBar` /
   * `mShowFullAnimation` / `mIsOnlyRotateLand` / `mIsTouchWiget` 这些都没搬，必须补一遍）。
   */
  private fun configurePlayer(view: CineIsleGsyPlayer) {
    // 全屏：一进全屏就锁横屏（官方 setLockLand，实际生效点在 isLockLandByAutoFullSize()）
    view.setLockLand(prefs.lockLand.get())
    // 只允许横屏方向变化
    view.setOnlyRotateLand(prefs.onlyRotateLand.get())
    // 竖屏视频自动竖屏全屏（用户手动强制横屏期间例外，见 forcedLandscape）
    applyAutoFullWithSize(view)
    // 全屏过渡动画
    view.setShowFullAnimation(prefs.showFullAnimation.get())
    // 全屏隐藏系统状态栏（作为 startWindowFullscreen 的 statusBar 参数用）
    view.setFullHideStatusBar(prefs.fullHideStatusBar.get())
    // 全屏隐藏虚拟按键
    view.setHideKey(prefs.hideKey.get())
    // 全屏显示屏幕锁
    view.setNeedLockFull(prefs.needLockFull.get())

    // 触摸手势：非全屏 / 全屏
    view.setIsTouchWiget(prefs.touchGesture.get())
    view.setIsTouchWigetFull(prefs.touchGestureFull.get())
    // 横滑 seek 灵敏度
    view.setSeekRatio(prefs.seekRatio.get())
    // 移动网络提示
    view.setNeedShowWifiTip(prefs.wifiTip.get())
    // 控件自动隐藏时间
    view.setDismissControlTime(prefs.dismissControlTimeMs.get())
    // 拖动进度条时在进度条上显示目标时间
    view.setShowDragProgressTextOnSeekBar(prefs.showDragProgressText.get())
    // 暂停保留最后一帧
    view.setShowPauseCover(prefs.showPauseCover.get())

    // 循环 / 倍速 / 变速不变调（workRightNow=false：还没起播时先存下来，prepare 时统一生效）
    view.setLooping(prefs.looping.get())
    view.setSpeed(prefs.defaultSpeed.get(), prefs.soundTouch.get(), false)

    // 强制解封装器（Exo 用；空串表示不设置）
    prefs.overrideExtension.get().takeIf { it.isNotBlank() }?.let { view.setOverrideExtension(it) }

    // GL 滤镜（只有 GLSURFACE 渲染下有效，其余渲染层实现的是空方法，调用无害）
    view.setEffectFilter(prefs.filter.get().toShader())

    // 进入即静音
    GSYVideoManager.instance().setNeedMute(prefs.muteOnStart.get())

    // 没标题就别留一条空白底栏
    applyTitleVisibility(view)
  }

  /**
   * 「竖屏视频自动竖屏全屏」的实际下发点。
   *
   * [forcedLandscape] 为 true（用户手动按过横屏键）时强制关掉：
   * 开着它 GSY 会认为「竖屏视频就该竖屏全屏」，于是既不让 `resolveByClick()` 转横屏，
   * 进了横屏之后转回手机竖屏时 `backFromFull()` 也会被跳过 —— 一进一出全被卡住。
   */
  private fun applyAutoFullWithSize(view: CineIsleGsyPlayer) {
    view.setAutoFullWithSize(prefs.autoFullWithSize.get() && !forcedLandscape)
  }

  /**
   * 没标题就藏起来。
   *
   * GSY 只会给 `title` 这个 TextView setText，从不改它的 visibility（它不在
   * `setViewShowState` 的名单里），所以在这里设一次是安全的：setUp / cloneParams
   * 都不会把它翻回来。
   */
  private fun applyTitleVisibility(view: CineIsleGsyPlayer) {
    view.getTitleTextView()?.visibility = if (videoTitle.isBlank()) View.GONE else View.VISIBLE
  }

  /** 标题的落位：竖屏在底栏独占一行，横屏搬到顶栏与返回键同一行 */
  private fun applyTitlePlacement(view: CineIsleGsyPlayer) {
    view.applyTitlePlacement(
      resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
  }

  // ────────────────────────────────────────────────────────────────────────
  // 控件接线
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 给自控的按钮条接线。
   *
   * 「换渲染载体」只能在重建播放页时生效，所以这里顺手把当前进度写回 intent 再重建 ——
   * 观感上就是画面闪一下、继续播，而不是被打回从头。
   */
  private fun bindControls(view: CineIsleGsyPlayer) {
    view.bindGlassControls(
      prefs = prefs,
      actions =
        GsyGlassActions(
          onRecreateNeeded = { message ->
            if (view.isIfCurrentIsFullscreen) {
              // 全屏实例是 GSY 克隆出来的另一棵树，重建会让全屏状态错乱 —— 让用户先退全屏
              Toast.makeText(this, "$message（请先退出全屏再切换）", Toast.LENGTH_LONG).show()
            } else {
              Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
              recreateKeepingPosition()
            }
          },
          onPrevious = { playIndex(currentIndex - 1) },
          onNext = { playIndex(currentIndex + 1) },
          onSwitchEngine = { switchToMpvPlayer() },
          onOrientation = { toggleLandscapeFullscreen() },
          onScreenshot = { takeSnapshot() },
          onSubtitle = { showSubtitleMenu() },
          onAudioTrack = { showAudioTrackDialog() },
          onPictureInPicture = { enterPip() },
          onCast = { showCastSheet() },
        ),
    )
    // 剪辑/切集后字幕键的激活态要跟着实例走
    view.setSubtitleActive(subtitleSource != null)
  }

  /**
   * 官方回调。
   *
   * 两件事官方不会替我们做：
   *  · **全屏克隆实例的接线** —— 由传感器 / 配置变化触发的全屏不会经过我们的
   *    `startWindowFullscreen` 返回值，只能在这里从 `objects[0]` 拿到克隆实例补配置；
   *  · **播完自动下一集** —— GSY 只管把这一集放完，队列是我们自己维护的。
   */
  private fun installCallbacks(view: CineIsleGsyPlayer) {
    view.setVideoAllCallBack(
      object : GSYSampleCallBack() {
        override fun onEnterFullscreen(url: String?, vararg objects: Any?) {
          val full =
            (objects.getOrNull(0) as? CineIsleGsyPlayer)
              ?: (GSYVideoManager.instance().listener() as? CineIsleGsyPlayer)
          full?.let { configureClone(it) }
          pipHelper?.updateParams()
        }

        override fun onQuitFullscreen(url: String?, vararg objects: Any?) {
          fullscreenPlayer = null
          // 退出全屏 = 这次「手动强制横屏」结束，把「竖屏视频自动竖屏全屏」还给用户设置
          forcedLandscape = false
          player?.let { applyAutoFullWithSize(it) }
        }

        override fun onAutoComplete(url: String?, vararg objects: Any?) {
          if (canAdvance()) {
            playIndex(currentIndex + 1)
          } else if (prefs.closeAfterEnd.get()) {
            finish()
          }
        }
      },
    )
  }

  /**
   * 全屏克隆实例的「补配置」：偏好要补一遍、玻璃按钮条要重新接线、
   * 标题要按当前方向重新摆位、上/下一集要按当前队列重新判定。
   */
  private fun configureClone(clone: CineIsleGsyPlayer) {
    if (fullscreenPlayer === clone) return
    fullscreenPlayer = clone
    clone.setVideoAllCallBack(cloneCallback)
    applySafeArea(clone)
    configurePlayer(clone)
    applyTitlePlacement(clone)
    bindControls(clone)
    syncNavigation(clone)
  }

  /** 克隆实例自己也要能收到「播完自动下一集」，否则全屏下看完就停在那儿 */
  private val cloneCallback =
    object : GSYSampleCallBack() {
      override fun onAutoComplete(url: String?, vararg objects: Any?) {
        if (canAdvance()) {
          playIndex(currentIndex + 1)
        } else if (prefs.closeAfterEnd.get()) {
          finish()
        }
      }

      override fun onQuitFullscreen(url: String?, vararg objects: Any?) {
        fullscreenPlayer = null
        forcedLandscape = false
        player?.let { applyAutoFullWithSize(it) }
      }
    }

  /** 带上当前播放进度重建播放页（换渲染载体 / 开滤镜的唯一办法） */
  private fun recreateKeepingPosition() {
    val position = activePlayer?.getCurrentPositionWhenPlaying() ?: 0L
    val handoff =
      PlayerHandoff(
        playlist = playlist,
        titles = titles,
        seriesKeys = seriesKeys,
        index = currentIndex,
        playlistId = playlistId,
        positionMs = position,
        seriesKey = seriesKeys.getOrNull(currentIndex),
        embyServerId = embyServerId,
        embyItemIds = embyItemIds,
        headers = headers,
      )
    Intent(intent).apply {
      data = currentUri
      handoff.writeTo(this)
      putExtra("play_from_start", false)
      putExtra(EXTRA_RESTORE_POSITION, true)
      setIntent(this)
    }
    recreate()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 横竖屏
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 方向工具与两个「同名但不同义」的开关。
   *
   * `OrientationUtils` 和 `GSYBaseVideoPlayer` **各有一个** `mRotateWithSystem`，含义完全不同：
   *
   *  · `OrientationUtils.mRotateWithSystem`（官方默认 true）：系统「自动旋转」关掉时，
   *    它就把重力感应整个忽略掉 —— 用户怎么转手机播放页都不动，看上去就是「切不了横屏」。
   *    这里按偏好传 false（默认），让播放页内始终跟随重力感应，与 mpv 播放页一致。
   *
   *  · `GSYBaseVideoPlayer.mRotateWithSystem`：GSY 在「转回竖屏」的配置回调里会执行
   *    `orientationUtils.setEnable(isRotateWithSystem())`。一旦它是 false，
   *    转一次竖屏就会把传感器监听 `disable()` 掉，之后彻底不再响应旋转。必须保持 true。
   *
   * 另外 `setIsPause` 是老版本 `pause()/resume()` 在 13.x 里的替代写法：
   * 置 true 后 `onOrientationChanged` 会直接 return，所以离开页面要置 true、回来要置 false。
   */
  private fun setUpOrientation(view: CineIsleGsyPlayer) {
    val utils = OrientationUtils(this, view)
    utils.setRotateWithSystem(prefs.rotateWithSystem.get())
    utils.setEnable(true)
    utils.setIsPause(false)
    orientationUtils = utils

    view.setRotateWithSystem(true)
  }

  /**
   * 竖屏 ↔ 横屏全屏，一次点击完成一次切换。
   *
   * **不复用官方 `resolveByClick()` 的判定**，因为它在竖屏视频上会提前 return：
   *
   * ```java
   * if (mIsLand == 0 && mVideoPlayer.isVerticalFullByVideoSize()) return;   // ← 竖屏视频被吃掉
   * ```
   *
   * 而 `isVerticalFullByVideoSize() = isVerticalVideo() && isAutoFullWithSize()`，
   * 本项目 `setAutoFullWithSize` 默认是开的 —— 于是「竖屏视频点全屏没反应、
   * 得点好几次才能切到横屏」。
   *
   * 这里的处理：手动切横屏时用 [forcedLandscape] 把「竖屏视频自动竖屏全屏」临时关掉，
   * 官方的 `resolveByClick()` 就会正常转屏；退出全屏时再恢复用户设置。
   */
  private fun toggleLandscapeFullscreen() {
    val view = player ?: return
    val somehowLandscape =
      view.isIfCurrentIsFullscreen ||
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (somehowLandscape) {
      // → 回竖屏
      forcedLandscape = false
      applyAutoFullWithSize(view)
      orientationUtils?.backToProtVideo()
      if (GSYVideoManager.backFromWindowFull(this)) return
      // 没有 window 全屏（只是被转了屏），那就把方向请求改回竖屏
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      return
    }

    // → 强制横屏全屏
    // 判定「竖屏视频」的 isVerticalFullByVideoSize() 是 protected，外部拿不到，
    // 所以这里一律按「强制横屏」处理：手动切横屏时本就不再需要「竖屏视频自动竖屏全屏」。
    forcedLandscape = true
    applyAutoFullWithSize(view)
    orientationUtils?.resolveByClick()
    if (!view.isIfCurrentIsFullscreen) {
      val full =
        view.startWindowFullscreen(this, false, prefs.fullHideStatusBar.get()) as? CineIsleGsyPlayer
      full?.let { configureClone(it) }
    }
    applyTitlePlacement(view)
  }

  // ────────────────────────────────────────────────────────────────────────
  // 截图
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 截当前帧。
   *
   * 用 GSY 官方的 `saveFrame(File, GSYVideoShotSaveListener)`（内部会把渲染层当前帧取出来），
   * 落盘交给 [PlayerSnapshotStore] —— 走 MediaStore，不需要存储权限。
   */
  private fun takeSnapshot() {
    val target = activePlayer
    if (target == null) {
      Toast.makeText(this, R.string.gsy_snapshot_failed, Toast.LENGTH_SHORT).show()
      return
    }

    val fileName =
      "CineIsle_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
    val temp = File(cacheDir, fileName)

    target.saveFrame(
      temp,
      GSYVideoShotSaveListener { success, file ->
        lifecycleScope.launch {
          val ok =
            success &&
              file != null &&
              file.length() > 0L &&
              PlayerSnapshotStore.save(this@GsyPlayerActivity, file, fileName)
          Toast
            .makeText(
              this@GsyPlayerActivity,
              if (ok) R.string.gsy_snapshot_saved else R.string.gsy_snapshot_failed,
              Toast.LENGTH_SHORT,
            ).show()
        }
      },
    )
  }

  // ────────────────────────────────────────────────────────────────────────
  // 字幕
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 字幕菜单。
   *
   * 只做「外挂字幕」（.srt / .vtt）—— GSY 的嵌入式字幕（内核把文本回吐给
   * `setSubtitleTextFromPlayer`）在 IJK / Exo2 里都没有实现，官方字幕子系统
   * (`GSYSubtitleController`) 本身也只吃外挂源。
   */
  private fun showSubtitleMenu() {
    val has = subtitleSource != null
    val items = mutableListOf<String>()
    items += getString(R.string.gsy_subtitle_pick)
    if (has) {
      items += getString(R.string.gsy_subtitle_delay)
      items += getString(R.string.gsy_subtitle_off)
    }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_subtitle_title)
      .setItems(items.toTypedArray()) { _, which ->
        when (which) {
          0 -> pickSubtitleFile.launch(arrayOf("*/*"))
          1 -> if (has) showSubtitleDelayDialog()
          else -> clearSubtitle()
        }
      }
      .show()
  }

  /** 字幕延迟微调（官方 `setSubtitleOffsetMs`，正数 = 字幕往后推） */
  private fun showSubtitleDelayDialog() {
    val items =
      arrayOf(
        getString(R.string.gsy_subtitle_delay_earlier),
        getString(R.string.gsy_subtitle_delay_later),
        getString(R.string.gsy_subtitle_delay_reset),
      )
    android.app.AlertDialog
      .Builder(this)
      .setTitle(
        getString(R.string.gsy_subtitle_delay_value, formatOffset(subtitleOffsetMs)),
      )
      .setItems(items) { _, which ->
        subtitleOffsetMs =
          when (which) {
            0 -> subtitleOffsetMs - SUBTITLE_STEP_MS
            1 -> subtitleOffsetMs + SUBTITLE_STEP_MS
            else -> 0L
          }
        applySubtitleToAll()
      }
      .show()
  }

  /** SAF 选完文件：读一次拿到展示名 + 推断编码，然后挂给播放器 */
  private fun loadSubtitle(uri: Uri) {
    val label = displayNameOf(uri)
    val charset = detectCharset(uri)
    subtitleLabel = label
    subtitleOffsetMs = 0L
    subtitleSource =
      GSYSubtitleSource
        .Builder(uri.toString())
        .setLabel(label)
        .setMimeType(mimeOf(label))
        .setCharsetName(charset)
        .setDefault(true)
        .build()

    applySubtitleToAll()
    Toast
      .makeText(
        this,
        getString(R.string.gsy_subtitle_loaded, label),
        Toast.LENGTH_SHORT,
      ).show()
  }

  private fun clearSubtitle() {
    subtitleSource = null
    subtitleLabel = null
    subtitleOffsetMs = 0L
    applySubtitleToAll()
  }

  /** 小屏实例与全屏克隆实例都要挂，否则全屏下字幕会突然消失 */
  private fun applySubtitleToAll() {
    player?.let { applySubtitle(it) }
    fullscreenPlayer?.let { if (it !== player) applySubtitle(it) }
  }

  private fun applySubtitle(target: CineIsleGsyPlayer) {
    val source = subtitleSource
    if (source == null) {
      target.setSubtitleEnabled(false)
      target.setSubtitleActive(false)
      return
    }
    target.setSubtitleSource(source)
    target.setSubtitleOffsetMs(subtitleOffsetMs)
    target.setSubtitleEnabled(true)
    target.setSubtitleActive(true)
  }

  /** 取 SAF URI 的展示名（拿不到就退回路径末段） */
  private fun displayNameOf(uri: Uri): String {
    val fromResolver =
      runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
      }.getOrNull()

    return fromResolver
      ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: "subtitle"
  }

  private fun mimeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
      "vtt" -> "text/vtt"
      "ass", "ssa" -> "text/x-ssa"
      else -> "application/x-subrip"
    }

  /**
   * 猜字幕文件的字符集。
   *
   * 中文外挂字幕里有大量 GBK 编码的老文件，直接按 UTF-8 读会满屏「锟斤拷」。
   * 做法：有 BOM 就 UTF-8；否则严格解码前 64KB，能过就是 UTF-8，报错就当 GBK。
   */
  private fun detectCharset(uri: Uri): String {
    val head =
      runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
          val buffer = ByteArray(CHARSET_PROBE_BYTES)
          var filled = 0
          while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
          }
          buffer.copyOf(filled)
        }
      }.getOrNull() ?: return "UTF-8"

    if (head.isEmpty()) return "UTF-8"
    if (head.size >= 3 &&
      head[0] == 0xEF.toByte() &&
      head[1] == 0xBB.toByte() &&
      head[2] == 0xBF.toByte()
    ) {
      return "UTF-8"
    }

    val decoder =
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching {
      decoder.decode(java.nio.ByteBuffer.wrap(head))
      "UTF-8"
    }.getOrDefault("GBK")
  }

  private fun formatOffset(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds >= 0) "+%.1fs".format(seconds) else "%.1fs".format(seconds)
  }

  // ────────────────────────────────────────────────────────────────────────
  // 音轨
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 音轨选择。
   *
   * GSY 的**通用**播放接口（[com.shuyu.gsyvideoplayer.player.IPlayerManager]）里没有轨道相关
   * 方法，只有 IJK 的 [IjkPlayerManager] 自己多提供了 `getTrackInfo()/selectTrack()/deselectTrack()`
   * —— 所以这项能力**只在 IJK 内核下可用**，换成 System / Exo 会明确提示。
   *
   * 轨道下标就是 `getTrackInfo()` 数组的下标（IJK 的 `selectTrack(int)` 收的就是全局轨道索引）。
   */
  private fun showAudioTrackDialog() {
    val manager = GSYVideoManager.instance().curPlayerManager
    val ijk = manager as? IjkPlayerManager
    if (ijk == null) {
      Toast
        .makeText(
          this,
          getString(R.string.gsy_audio_unsupported, prefs.kernel.get().name),
          Toast.LENGTH_LONG,
        ).show()
      return
    }

    val infos: Array<IjkTrackInfo> = runCatching { ijk.trackInfo }.getOrNull() ?: emptyArray()
    val tracks =
      infos.mapIndexedNotNull { index, info ->
        if (info.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) index to info else null
      }

    if (tracks.isEmpty()) {
      Toast.makeText(this, R.string.gsy_audio_empty, Toast.LENGTH_SHORT).show()
      return
    }

    val selected = runCatching { ijk.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) }.getOrDefault(-1)
    val labels =
      tracks
        .map { (index, info) ->
          val language = info.language?.takeIf { it.isNotBlank() && it != "und" }
          val detail = language ?: info.infoInline.orEmpty().take(40)
          "音轨 ${tracks.indexOfFirst { it.first == index } + 1} · $detail"
        }
        .toTypedArray()

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_audio_title)
      .setSingleChoiceItems(labels, tracks.indexOfFirst { it.first == selected }) { dialog, which ->
        runCatching { ijk.selectTrack(tracks[which].first) }
        dialog.dismiss()
      }
      .show()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 画中画 / 投屏
  // ────────────────────────────────────────────────────────────────────────

  private fun enterPip() {
    val helper = pipHelper
    if (helper == null || !helper.isSupported()) {
      Toast.makeText(this, R.string.gsy_pip_unsupported, Toast.LENGTH_SHORT).show()
      return
    }
    // 小窗里不需要控件条（系统会整块裁掉，留着只是白占一层）
    if (!helper.enter()) {
      Toast.makeText(this, R.string.gsy_pip_unsupported, Toast.LENGTH_SHORT).show()
    }
  }

  /**
   * 投屏面板。
   *
   * 直接复用 mpv 播放页那份 [DlnaSheet]（同一个 [DlnaCastManager] 单例 + 同一套 Compose 面板），
   * 只是本页是纯 View 体系，所以用一层 [ComposeView] 覆盖层把它挂上来。
   *
   * 投屏意味着画面交给远端设备，本机继续解码只是白耗电，所以先暂停本地播放。
   */
  private fun showCastSheet() {
    val uri = currentUri
    if (uri == null) {
      Toast.makeText(this, R.string.gsy_cast_no_source, Toast.LENGTH_SHORT).show()
      return
    }

    if (GSYVideoManager.instance().isPlaying) GSYVideoManager.instance().pause()
    dlnaManager.pendingPayload = CastPayload(uri, videoTitle)

    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    hideCastSheet()

    val overlay =
      ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { CastOverlayContent() }
      }
    castOverlay = overlay
    root.addView(
      overlay,
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  /** 面板本体：DlnaSheet 自己会在关闭时通知 manager，我们只管把覆盖层摘掉 */
  @Composable
  private fun CastOverlayContent() {
    MpvexTheme { DlnaSheet(onDismissRequest = { hideCastSheet() }) }
  }

  private fun hideCastSheet() {
    castOverlay?.let { overlay ->
      (overlay.parent as? ViewGroup)?.removeView(overlay)
    }
    castOverlay = null
  }

  // ────────────────────────────────────────────────────────────────────────
  // Emby 收藏
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 顶栏的收藏键。
   *
   * 挂的是 mpv 播放页那份 [EmbyFavoritePlayerButton]（弹跳 + 星光 + 红心渐变完全一致），
   * 状态来自 `EmbyPlayerActions` 单例：[readSession] 里已经 `bind` 过本次播放的 server/item，
   * 非 Emby 播放时它自己会渲染成空。
   */
  private fun setUpFavoriteButton() {
    val host = findViewById<ComposeView>(R.id.gsy_btn_favorite) ?: return
    host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    host.setContent { FavoriteButtonContent() }
  }

  @Composable
  private fun FavoriteButtonContent() {
    MpvexTheme { EmbyFavoritePlayerButton(hideBackground = true, buttonSize = 32.dp) }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 系统栏 / 安全区
  // ────────────────────────────────────────────────────────────────────────

  /** 状态栏 / 导航栏的显示与配色：黑底播放页统一用浅色图标 */
  private fun applySystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    if (prefs.showSystemStatusBar.get()) {
      controller.show(WindowInsetsCompat.Type.statusBars())
    } else {
      controller.hide(WindowInsetsCompat.Type.statusBars())
    }
    if (prefs.showSystemNavigationBar.get()) {
      controller.show(WindowInsetsCompat.Type.navigationBars())
    } else {
      controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
  }

  /**
   * 状态栏 / 导航栏安全区。
   *
   * GSY 的布局里顶栏和底栏都是「贴屏幕上下沿」的（它的状态机只认 id、不管位置），
   * 边到边之后返回键会顶到刘海 / 挖孔下面、进度条会压在手势条上，所以在这里按 insets 补 padding：
   *
   *  · `layout_top`   ← 状态栏高度（避让刘海 / 挖孔）
   *  · `layout_bottom` / `bottom_progressbar` ← 导航栏高度（避让手势条 / 三大金刚）
   *
   * 关掉「显示系统状态栏 / 导航栏」时对应 insets 本来就是 0，padding 自然归零 —— 不需要额外分支。
   */
  private fun applySafeArea(view: View) {
    val topBar = view.findViewById<View>(R.id.layout_top) ?: return
    val bottomBar = view.findViewById<View>(R.id.layout_bottom)
    val bottomProgress = view.findViewById<View>(R.id.bottom_progressbar)

    // 记下 XML 里写的 padding，重复派发 insets 时不会越加越多
    val topBase = topBar.paddingTop
    val bottomBase = bottomBar?.paddingBottom ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
      val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

      topBar.updatePadding(top = topBase + statusTop)
      bottomBar?.updatePadding(bottom = bottomBase + navBottom)
      bottomProgress?.updatePadding(bottom = navBottom)

      insets
    }
    ViewCompat.requestApplyInsets(view)
  }

  // ────────────────────────────────────────────────────────────────────────
  // 工具
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 交给 GSY 的地址。
   *
   * mpv 是自己读文件描述符的，GSY 读不了 SAF 的 `content://`，所以这里先试着把
   * content URI 解析成真实文件路径（与 [PlayerActivity] 同一套 `Utils.findRealPath`），
   * 解析不到就原样把 URI 交出去 —— GSY 的 IJK / Exo2 内核自己能处理 content URI。
   */
  private fun playableUrl(uri: Uri): String =
    when (uri.scheme) {
      "content" ->
        runCatching {
          contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> Utils.findRealPath(pfd.fd) }
        }.getOrNull() ?: uri.toString()

      "file" -> uri.path ?: uri.toString()
      else -> uri.toString()
    }

  /**
   * 网络流请求头 → GSY 的 `Map<String, String>`。
   *
   * 格式与 mpv 侧 `headers` extra 完全一致（第 0/1 位可选 User-Agent，其后两两成对），
   * 这样 mpv 切过来时 Referer / UA 不会丢，腾讯视频这类校验 Referer 的源在 GSY 下也能播。
   */
  private fun headerMap(): Map<String, String>? {
    val arr = headers ?: return null
    if (arr.isEmpty()) return null

    val map = LinkedHashMap<String, String>()
    if (arr.size > 1 && arr[0].startsWith("User-Agent", ignoreCase = true)) {
      map["User-Agent"] = arr[1]
    }
    if (arr.size > 2) {
      arr.drop(2).chunked(2).filter { it.size == 2 }.forEach { (key, value) -> map[key] = value }
    }
    return map.ifEmpty { null }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 生命周期
  // ────────────────────────────────────────────────────────────────────────

  override fun onPause() {
    super.onPause()
    player?.onVideoPause()
    // 离开页面就停掉重力感应监听（13.x 里 pause()/resume() 改成了这个开关）
    orientationUtils?.setIsPause(true)
  }

  override fun onResume() {
    super.onResume()
    player?.onVideoResume()
    orientationUtils?.setIsPause(false)
    pipHelper?.updateParams()
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    pipHelper?.onPictureInPictureModeChanged(isInPictureInPictureMode)
  }

  override fun onDestroy() {
    super.onDestroy()
    hideCastSheet()
    pipHelper?.release()
    pipHelper = null
    orientationUtils?.releaseListener()
    orientationUtils = null
    GSYVideoManager.releaseAllVideos()
    // 收藏态是进程内单例，退出播放页要交还，否则会带到下一个播放页
    EmbyPlayerActions.release()
    player = null
    fullscreenPlayer = null
  }

  /**
   * 页面声明了 `configChanges="orientation|screenSize|..."`，旋转时系统**不会重建 Activity**，
   * 所以要主动把配置变化交给 GSY —— 它才会在转到横屏时进全屏、转回竖屏时退全屏。
   * （官方 demo DetailPlayer 的 onConfigurationChanged 就是这一句。）
   *
   * GSY 可能在这次调用里自己进出全屏，所以标题落位要放在它之后重新算一遍。
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val view = player ?: return
    val utils = orientationUtils ?: return
    view.onConfigurationChanged(this, newConfig, utils, false, prefs.fullHideStatusBar.get())

    applyTitlePlacement(view)
    fullscreenPlayer?.takeIf { it !== view }?.let { applyTitlePlacement(it) }
  }

  companion object {
    /** 播放地址的备用 extra（官方只看 intent.data） */
    private const val EXTRA_URL = "gsy_url"

    /** 标题的备用 extra */
    private const val EXTRA_TITLE = "gsy_title"

    /** 重建播放页时强制按 position 续播（不受「续播到上次位置」开关影响） */
    private const val EXTRA_RESTORE_POSITION = "gsy_restore_position"

    /** 字幕延迟一次调整的步长 */
    private const val SUBTITLE_STEP_MS = 500L

    /** 探测字幕编码时最多读多少字节 */
    private const val CHARSET_PROBE_BYTES = 64 * 1024

    /**
     * 构造一次「用 GSY 播放」的 intent。
     *
     * 地址统一放在 data 上（官方 `setUp` 读的就是它），另外存一份字符串便于
     * 从播放列表 / Emby 这类不走 Uri 的来源直接起播。
     *
     * @param positionMs 续播位置（毫秒），0 = 从头播
     */
    fun createIntent(
      context: android.content.Context,
      uri: Uri,
      title: String? = null,
      positionMs: Long = 0L,
    ): Intent =
      Intent(Intent.ACTION_VIEW, uri, context, GsyPlayerActivity::class.java).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(EXTRA_URL, uri.toString())
        title?.let { putExtra(EXTRA_TITLE, it) }
        if (positionMs > 0) putExtra("position", positionMs.toInt())
      }
  }
}
