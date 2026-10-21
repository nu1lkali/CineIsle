package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.GsyKernelKind
import app.marlboroadvance.mpvex.preferences.GsyPreferences
import app.marlboroadvance.mpvex.preferences.GsyRenderKind
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.domain.dislike.DislikeKey
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuOverlay
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuRenderParams
import app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState
import app.marlboroadvance.mpvex.ui.player.engine.EngineFeature
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapter
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository.Companion.indexOfCurrent
import app.marlboroadvance.mpvex.ui.player.gsy.GsySubtitleStyleMapper
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
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import `is`.xyz.mpv.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import tv.danmaku.ijk.media.exo2.ExoPlayerCacheManager
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * GSYVideoPlayer 备用内核播放�? —�? **GSY 官方 demo（`DetailPlayer`）的等价实现**�?
 *
 * 用法严格照官方样例，不写任何自定义转接层�?
 *
 * 1. 布局里直接声�? [CineIsleGsyPlayer]（它自己把内部布局换成 CineIsle 版）�?
 * 2. 起播前把「全局静态」和「实例级」偏好全部灌�? GSY 官方 setter（见 [applyStatics] / [configurePlayer]）；
 * 3. `setUp(url, cacheWithPlay, title)` 绑定地址 �? `startPlayLogic()` 起播�?
 * 4. 全屏仍然�?**官方那套 window 全屏**（`startWindowFullscreen` 会克隆一个新实例�?
 *    `setLockLand` / `setNeedLockFull` / `setHideKey` / `setFullHideStatusBar` /
 *    `setAutoFullWithSize` / `setShowFullAnimation` 这些「全屏专属」能力也才真的生效）�?
 *    �?**进入全屏的方向不再交给官方的 `resolveByClick()`** —�? 它会被「竖屏视频自动竖屏全屏�?
 *    提前 return 掉、还会和应用内方向请求来回拉锯。现在由 [toggleLandscapeFullscreen]
 *    直接请求方向，配置变化时再由 GSY 自己进出全屏�?
 * 5. 返回键：�? `orientationUtils.backToProtVideo()` 回竖屏，�? `backFromWindowFull()` 退全屏�?
 *    最后才 `finish()`�?
 * 6. `onPause �? onVideoPause()`、`onResume �? onVideoResume()`、退�? �? `GSYVideoManager.releaseAllVideos()`�?
 *    注意画中画：进小窗时系统也会�? `onPause`，那两种情况下都不该暂停/恢复（见 onPause / onResume）�?
 *
 * 它不继承 [PlayerActivity]：mpv 那一整套 Compose 控件 / ViewModel / 属性门面与 GSY 无关�?
 * 混在一起只会互相干扰�?
 *
 * 注意 GSY �? window 全屏�?**克隆了一个新的播放器实例**（反射调本类�? (Context, Boolean) 构造器），
 * 那个实例不带我们在竖屏实例上绑的点击监听，所以拿到之后要重新 `configurePlayer` 一�? —�?
 * 入口有两条：`startWindowFullscreen` 的返回值，以及官方回调 `onEnterFullscreen`
 * （由传感�?/配置变化触发的全屏走后者），见 [installCallbacks] / [configureClone]�?
 *
 * ── �? mpv 播放页的「内核切捀��────────────────────────────────────────────
 * 本页顶栏右端的双向箭头按钮会�?**当前视频 + 整份播放队列 + 当前进度**交回 mpv
 * （[switchToMpvPlayer]）；反方向由 mpv 播放页「更�? �? �? GSY 播放」发起�?
 * 两边都用 [PlayerHandoff] 承载上下文，所以切过去接着放、队列不一�?
 */
class GsyPlayerActivity : ComponentActivity() {
  private val prefs: GsyPreferences by inject()

  /** 「播完自动下一集」沿�? mpv 那套全局播放偏好，避免同一件事在两处各有一份开�? */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * 字幕偏好 —�? **�? mpv 播放页共用同一�?**（清�? #19）�?
   *
   * 这是「双内核共用一�? UI」的关键：字号、文字色、对齐这些在 GSY 侧改了，
   * 切回 mpv 依然生效，反之亦然。不要在 GSY 侧另开一�? `gsy_sub_*` 偏好�? —�?
   * 那样用户会得到「两个内核两套外观」，切一下就变样�?
   *
   * 代价�? GSY 能调的只是这个偏好的**子集**（GSY 没有字体名、没有描边样式、不�? ASS），
   * 差集�? [GsySubtitleStyleMapper.unsupportedByGsy] 在样式面板标题里如实告知�?
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * 弹幕偏好 —— **与 mpv 播放页共用同一份**（清单 b6「GSY 内核能力不对等」）。
   *
   * 设置里开一次弹幕，两个内核都生效；关着（默认）时本页不建状态机、
   * 不发任何弹幕网络请求。样式/密度/屏蔽词也全部走这一份 —— 与字幕偏好
   * （清单 #19）同样的「双内核一套 UI」原则。
   */
  private val danmakuPreferences: DanmakuPreferences by inject()

  /**
   * GSY 内核的章节数据源（清�? #19）�?
   *
   * �? mpv 侧那�? `chapters: StateFlow` 无关 —�? mpv 的章节来自容器内嵌章节表�?
   * GSY 没有章节 API，只能走 Emby。能力边界见 [EngineFeature.CHAPTERS] �? KDoc�?
   */
  private val chapterRepository: GsyChapterRepository by inject()

  /** 拉章节要用当前登录的 Emby 服务器（清单 #19�? */
  private val embyRepository: EmbyRepository by inject()

  /** DLNA 投屏：与 mpv 播放�? / 详情页共用同一个单例（发现、连接、投屏状态都在它里面�? */
  private val dlnaManager: DlnaCastManager by inject()

  /**
   * 一次「退出全屏」正在路上�?
   *
   * GSY 的退出不是同步的：`clearFullscreenLayout` 只是�? `backToNormal` post 到主线程�?
   * 真正的收尾（摘掉克隆视图、把 listener 还给小屏实例、回�? onQuitFullscreen）要等这�?
   * 任务跑完。这期间 manager �? listener 还指着正在退场的克隆实例 —�? 若此时再来一�?
   * 旋转配置�? GSY 继续处理，它会「克隆正在退场的克隆」：新克隆顶掉旧克隆的窗口视图，
   * 等旧克隆的退出任务落地时又把新克隆从窗口摘掉、把 listener 挂回已被拆除的视�? —�?
   * 画面从此黑住、渲染层断掉、所有方�?/返回键在窗口里找不到全屏视图而全部失�?
   * （快速反复横竖屏必现）�?
   *
   * 所以进出全屏必须串行：退出在途时忽略新的进出请求，退出落地后按当前实际方向补收尾�?
   */
  private var fullBusy = false

  private var player: CineIsleGsyPlayer? = null

  /** 全屏�? GSY 克隆出来的那个实例（退出全屏后置空�? */
  private var fullscreenPlayer: CineIsleGsyPlayer? = null

  private var orientationUtils: OrientationUtils? = null

  private var pipHelper: CineIsleGsyPipHelper? = null

  /** 当前标题（`setUp` 与「标题为空就别留空底栏」都要用�? */
  private var videoTitle: String = ""

  // ── 本次播放的队列（�? [PlayerHandoff] 灌入，切集时在页内自行维护）──
  private var playlist: List<Uri> = emptyList()
  private var titles: List<String> = emptyList()
  private var seriesKeys: List<String> = emptyList()
  /** 与 [playlist] 同序的 Emby 原始集号编码（`season*1000+episode`，空串 = 非剧集）。弹幕匹配用。 */
  private var episodeNumbers: List<String> = emptyList()
  private var currentIndex: Int = 0
  private var currentUri: Uri? = null

  /**
   * 本次播放**临时强制**使用�? GSY 解码内核（null = 用偏好里选的那个）�?
   *
   * 只有一条来源：解码失败后的自动降级（见 [fallbackToIjk]）。它必须能跨 `recreate()`
   * 存活 —�? `PlayerFactory` 上的是进程级静态值，只有重建播放页才真正生效�?
   * 所以降级走的是「重�? + intent 里带上本项」这条路（和「换渲染载体」同一套机制）�?
   */
  private var forcedKernel: GsyKernelKind? = null

  /** 这条视频已经降过一次级�? —�? 防止「失�? �? 降级 �? 又失败」反复重�? */
  private var kernelFallbackUsed = false
  private var playlistId: Int? = null
  private var embyServerId: Long? = null
  private var embyItemIds: List<String> = emptyList()
  private var headers: Array<String>? = null

  /** 交接单带来的当前亮度（0~1）；null = 没带。见 [applyRememberedBrightness]。 */
  private var handoffBrightness: Float? = null

  // ── 本次播放的外挂字幕（切集不清，用户选一次就能一直用）──
  private var subtitleSource: GSYSubtitleSource? = null
  private var subtitleLabel: String? = null
  private var subtitleOffsetMs: Long = 0L

  /**
   * 用户手动切到横屏中�?
   *
   * GSY 的「竖屏视频自动竖屏全屏」（`setAutoFullWithSize`，本项目默认开）会�?
   * `isVerticalFullByVideoSize()` 在竖屏视频上恒为 true，进而让官方
   * `onConfigurationChanged()` �?**竖屏分支拒绝退全屏**（见 GSYBaseVideoPlayer 源码）�?
   * 手动横屏期间把这项临时关掉（竖屏实例与全屏克隆实例都要），退出横屏再恢复用户设置�?
   */
  private var forcedLandscape = false

  /** 投屏面板（Compose）挂在内容根布局上的覆盖�? */
  private var castOverlay: ComposeView? = null

  /**
   * 弹幕覆盖层（Compose），挂在内容根布局上（清单 b6）。
   *
   * 与 [castOverlay] 不同，它**常驻**：没有弹幕数据 / 开关关着时
   * [DanmakuOverlayContent] 整块不渲染，连 DanmakuView 都不建。
   */
  private var danmakuOverlay: ComposeView? = null

  /**
   * 弹幕状态机（与 mpv 播放页同一个类）。
   *
   * 默认关，所以**按需创建**：[PlayerDanmakuState] 构造本身不发请求，
   * [PlayerDanmakuState.onEpisodeChanged] 关着时也在第一行就返回。
   */
  private var danmakuState: PlayerDanmakuState? = null

  /**
   * 打开投屏面板时，本地播放是不�?**我们**暂停的�?
   *
   * 只有我们暂停的才由我们恢�? —�? 用户自己在面板打开前就已经暂停了的�?
   * 关掉面板不该擅自给他播起来�?
   */
  private var pausedForCastSheet = false

  // ── 网络加载速度指示器（顶栏 gsy_net_speed）──
  /** 速度平滑器：压平 GSY 底层网速采样的毛刺，避免显示值一秒一个数（见 [NetworkSpeedSmoother]�? */
  private val netSpeedSmoother = NetworkSpeedSmoother()

  // TrafficStats 兜底采样基线
  private var lastTotalRxBytes = -1L
  private var lastRxTimestampMs = 0L

  private val netSpeedHandler = Handler(Looper.getMainLooper())

  /**
   * 1 秒一拍的速度刷新。周期与 mpv 播放页（PlayerViewModel 的协程轮询）一致�?
   *
   * 换片 / 停止后平滑历史不重置的问题可忽略：换片走的是重建播放�?
   * （[recreateKeepingPosition]）或 setUp+startPlayLogic，原地更新的场景�?
   * 平滑器的 3 秒惯性只会造成一小段过渡值，不会显示错数据�?
   */
  private val netSpeedTicker =
    object : Runnable {
      override fun run() {
        updateNetSpeedView()
        netSpeedHandler.postDelayed(this, NET_SPEED_POLL_INTERVAL_MS)
      }
    }

  /**
   * 正在播的那个实例�?
   *
   * 全屏时真正的播放器是 GSY 克隆出来的实例，`GSYVideoManager.instance().listener()`
   * 正是「当前接管播放的那个 View」（GSY 自己�? `GSYVideoHelper` 也是这么取的），
   * 所以切�? / 取进度都必须走它，否则会去操作背后那个已经停住的小屏实例�?
   */
  private val activePlayer: CineIsleGsyPlayer?
    get() = (GSYVideoManager.instance().listener() as? CineIsleGsyPlayer) ?: player

  /** 选外挂字幕：GSYSubtitleLoader 内部�? `content://` �? ContentResolver，SAF 给的 URI 可直接用 */
  private val pickSubtitleFile =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) loadSubtitle(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 播放页是纯黑底，走边到边（与 mpv 播放页一致），安全区由我们自己按 insets �?
    enableEdgeToEdge()
    setContentView(R.layout.activity_gsy_player)

    // 播放页常亮；音量键交给「媒体音量」（只是改按键路由，不动音量本身�?
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    volumeControlStream = AudioManager.STREAM_MUSIC

    applySystemBars()
    applySafeArea(findViewById(R.id.gsy_player))

    val view = findViewById<CineIsleGsyPlayer>(R.id.gsy_player)
    player = view
    pipHelper = CineIsleGsyPipHelper(this, prefs)

    if (!readSession(intent)) {
      finish()
      return
    }

    // 按剧亮度：交接单里带了就直接用，没带就按本页 seriesKey 查一次记忆。
    // ⚠️ 必须在 startSession 之前：GSY 建好 surface 之后才设亮度，第一帧会闪一下亮的。
    applyRememberedBrightness(handoffBrightness)

    // ── 官方 demo 的「三件套�? ──
    setUpOrientation(view)
    installCallbacks(view)
    bindShellButtons(view)
    bindControls(view)
    applyTitlePlacement(view)
    setUpFavoriteButton(view)
    observeDislikeState()

    // ── 弹幕（清单 b6：GSY 内核与 mpv 内核能力对齐）──
    // 与 mpv 播放页同一个 PlayerDanmakuState + 同一份 DanmakuPreferences：
    // 设置里开一次两个内核都生效；默认关 ⇒ 不建状态机就没有任何弹幕网络请求。
    // overlay 常驻内容根布局，起播时由 setUpCurrent 触发第一次匹配。
    danmakuState = PlayerDanmakuState(preferences = danmakuPreferences, scope = lifecycleScope)
    mountDanmakuOverlay()

    // 续播 / 起播；显式「从头播放」时�? play_from_start，重建播放页（换渲染方式）时�?
    // gsy_restore_position，此时不受「续播到上次位置」开关影响�?
    startSession(
      positionMs = intent.getIntExtra("position", 0).toLong(),
      forceRestore = intent.getBooleanExtra(EXTRA_RESTORE_POSITION, false),
      playFromStart = intent.getBooleanExtra("play_from_start", false),
    )

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          // 官方 demo 的返回顺序：先回竖屏 �? 再退全屏 �? 都没得退才关页面
          if (castOverlay != null) {
            hideCastSheet()
            return
          }
          orientationUtils?.backToProtVideo()
          forcedLandscape = false
          if (requestExitFullscreen()) return
          finish()
        }
      },
    )

    // 网速指示器：进页面就开跑（onPause 停、onResume 续）
    netSpeedHandler.post(netSpeedTicker)
  }

  /**
   * 单实例（`launchMode=singleTask`）页面被复用时要重载一次交接单�?
   *
   * 典型场景：GSY �? mpv �? 又切�? GSY。虽然正常路径下前一�? GSY 已经 finish 了，
   * 但用户可能从通知 / 桌面快捷方式再次拉起，此时不能拿着旧视频接着放�?
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
  // 播放会话：交接单 �? 播放队列 �? 起播
  // ────────────────────────────────────────────────────────────────────────

  /**
   * �? intent 里的播放上下文读进字段�?
   *
   * 地址优先�?**队列里当前那一�?**（`playlist_index` 是权威的「现在放哪个」）�?
   * 没有队列时才退�? `intent.data` / `gsy_url`（单视频播放，以�? Emby 那类直接给地址的调用方）�?
   *
   * @return false 表示拿不到任何可播地址（调用方应当直接收摊�?
   */
  private fun readSession(source: Intent): Boolean {
    val handoff = PlayerHandoff.read(source)
    playlist = handoff.playlist
    titles = handoff.titles
    seriesKeys = handoff.seriesKeys
    episodeNumbers = handoff.episodeNumbers
    currentIndex = handoff.safeIndex
    playlistId = handoff.playlistId
    embyServerId = handoff.embyServerId
    embyItemIds = handoff.embyItemIds
    headers = handoff.headers
    handoffBrightness = handoff.brightness

    // 解码失败降级时由 recreate 带过来的「强制内核」；普通起播时没有这一项（null�?
    forcedKernel =
      source.getStringExtra(EXTRA_FORCE_KERNEL)?.let { name ->
        GsyKernelKind.entries.firstOrNull { it.name == name }
      }

    currentUri =
      playlist.getOrNull(currentIndex)
        ?: source.data
        ?: source.getStringExtra(EXTRA_URL)?.toUri()

    if (currentUri == null) return false
    // 标题：交接单里的「与本项一一对应的标题」优先，其次才是 intent 里的兜底字段�?
    // Emby 的流地址末段固定�? "stream"，没有标题列表就只能显示 "stream"�?
    videoTitle =
      titles.getOrNull(currentIndex)?.takeIf { it.isNotBlank() }
        ?: source.getStringExtra("title")
        ?: source.getStringExtra(EXTRA_TITLE)
        ?: source.getStringExtra("filename")
        ?: ""

    // Emby 收藏态：extras �? key �? mpv 播放页完全一致，直接交给那个单例去拉
    EmbyPlayerActions.bind(source)
    return true
  }

  /**
   * 起播当前 [currentUri]�?
   *
   * 全局静态偏好必须在 `setUp` 之前落定（`GSYVideoType` 是进程级静态值，
   * 起播后再改要等下一次起播）；实例级偏好同理（`setNeedShowWifiTip` /
   * `setOverrideExtension` / `setSpeed` 都在 prepare 时读）�?
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
      // setSeekOnStart 必须�? startPlayLogic() 之前调用（官方要求）
      view.setSeekOnStart(positionMs)
    } else {
      view.setSeekOnStart(0)
    }

    setUpCurrent(view)
    syncNavigation(view)
    // ⚠️ 初次起播**也必�?** bind「不喜欢」的 key�?
    //  之前只在 `playIndex()`（切�?/下一集、播完自动下一集）�? bind�?
    //  于是「刚进播放器」这一整段时间�? `DislikePlayerActions.currentKey` 还是 null
    //  �? 点按钮走 `toggle()` �? `key ?: return` 直接静默返回 —�?
    //  用户看到的就是「找到了按钮，点了完全没反应」�?
    bindDislikeForCurrent()

    view.startPlayLogic()
  }

  /**
   * 把当前这一集的「不喜欢」key 交给 [DislikePlayerActions]�?
   *
   * 每次换片都要调（起播 + 切集），否则按钮会停留在上一条视频的状态�?
   *
   * ⚠️ [embyServerId] 是可空的：没绑服务器（本�? / SMB / 网络直链播放）时�? null
   * �? 按钮点了会提示「当前视频无法标记」，而不是拿一个假�? id 去查
   * （查不到只是白查，但「没有来源」和「查过了没标记」在语义上必须分开）�?
   */
  private fun bindDislikeForCurrent() {
    DislikePlayerActions.bind(
      embyItemIds.getOrNull(currentIndex)?.let { itemId ->
        embyServerId?.let { DislikeKey(it, itemId) }
      },
      title = videoTitle,
    )
  }

  /**
   * 把当前这一集的地址交给播放器�?
   *
   * 网络流的请求头（User-Agent / Referer）走官方 `setUp(url, cache, cachePath, mapHeadData, title)`�?
   * 没有头就走三参数版，避免平白多传一个空 Map�?
   */
  private fun setUpCurrent(target: CineIsleGsyPlayer) {
    val uri = currentUri ?: return
    val url = playableUrl(uri)

    // ISO 原盘：GSY 的三个内核（IJK / Exo / System）都不带 libbluray，读不了镜像�?
    // mpv 内核�? libbluray（见 PlayerUtils.isoPlayableUri），自动转交，队列与进度不丢
    if (url.endsWith(".iso", ignoreCase = true) ||
      uri.lastPathSegment?.endsWith(".iso", ignoreCase = true) == true
    ) {
      Toast.makeText(this, "ISO 原盘将使用 mpv 内核播放，正在切换", Toast.LENGTH_LONG).show()
      switchToMpvPlayer()
      return
    }

    applyTitleVisibility(target)

    // 弹幕：起播 / 切集都走这里（startSession 与 playIndex 共用）⇒ 按**当前这一集**
    // 的片名重新匹配。内部会先取消上一次加载，否则上一集的慢响应回来会把
    // 这一集的弹幕覆盖掉（竞态，与 mpv 播放页同一处理）。
    // ⚠️ 第三个参数是**真实集号**，不是队列下标 —— 从第 5 集单独点播时队列只有 1 条，
    //    用下标会去取第 1 集的弹幕。解码规则与 mpv 侧 `currentEpisodeIndex` 逐字一致。
    danmakuState?.onEpisodeChanged(seriesKeys.getOrNull(currentIndex), videoTitle, episodeIndex(currentIndex))

    val headData = headerMap()
    if (headData != null) {
      target.setUp(url, prefs.cacheWithPlay.get(), null, headData, videoTitle)
    } else {
      target.setUp(url, prefs.cacheWithPlay.get(), videoTitle)
    }
    // 外挂字幕是「跟着播放器实例」的，重�? setUp 后要再挂一�?
    applySubtitle(target)
  }

  /**
   * 当前这一条在**剧集里的真实集号（0-based）**，供弹幕匹配使用。
   *
   * ⚠️ 与 mpv 侧 `PlayerActivity.currentEpisodeIndex` **必须逐字一致**：
   * 编码是 `season*1000 + episode`，集号从 1 开始，转 0-based 后再交给 DanmakuMatcher。
   * 拿不到（本地播放 / 网盘 / 非剧集）时退回队列下标 —— 同样是 mpv 侧的兜底口径。
   * 两处口径一旦分叉，「同一个文件在 mpv 里弹幕对、切到 GSY 就错集」这种 bug 极难查。
   */
  private fun episodeIndex(index: Int = currentIndex): Int {
    val encoded = episodeNumbers.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return index
    val number = encoded.toIntOrNull() ?: return index
    val episode = number % 1000
    return if (episode > 0) episode - 1 else index
  }

  /**
   * 换到队列里的�? [index] 个视频�?
   *
   * GSY 官方对「一�? View 连播多个视频」的用法就是重新 `setUp` + `startPlayLogic`
   * （官方的 `ListGSYVideoPlayer` / `GSYVideoHelper` 就是这么做的），
   * 这里�? `setSpeed` / `setLooping` / 渲染方式等实例状态都会保留�?
   *
   * 目标实例�? [activePlayer]：全屏状态下真正在播的是克隆实例，必须换它�?
   */
  private fun playIndex(index: Int) {
    if (index !in playlist.indices) return
    val target = activePlayer ?: return

    currentIndex = index
    currentUri = playlist[index]
    videoTitle = titles.getOrNull(index)?.takeIf { it.isNotBlank() } ?: ""

    // Emby 队列：让收藏按钮跟着切到这一�?
    EmbyPlayerActions.onItemChanged(embyItemIds.getOrNull(index))

    // 「不喜欢」按钮同样跟着切（内存快照 O(1)，无 IO �? 与画面同帧）
    bindDislikeForCurrent()

    // 小屏实例即使正在后台站着，也要知道「现在放的是哪一集」，
    // 否则退出全屏时标题 / 地址会回退到切集之前那一集�?
    player?.takeIf { it !== target }?.getTitleTextView()?.let {
      it.text = videoTitle
      it.visibility = if (videoTitle.isBlank()) View.GONE else View.VISIBLE
    }

    target.setSeekOnStart(0)
    setUpCurrent(target)
    syncNavigation(target)
    // 章节�?**�? Emby 条目**缓存的（清单 #19）：切到新的一集要清掉别的条目的缓存，
    // 否则用户从第 3 集跳到第 4 集、点开章节会看到第 3 集的章�?
    // 保留「切过去那一集」的缓存 —�? 从第 3 集跳到第 4 集再跳回来时不该重新拉�?
    chapterRepository.invalidate(embyItemIds.getOrNull(index))
    target.startPlayLogic()
  }

  /** 上一�? / 下一集按钮的可用性与显隐（队�? �? 1 时整块不出现�? */
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
  // �? mpv 播放页互相切�?
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把「当前视�? + 整份播放队列 + 当前进度」交�? mpv 播放页�?
   *
   * 只传一个地址是不够的：那样切过去就只剩一个孤零零的视频，队列、标题�?
   * 「现在放到第几个」全丢，Emby 那边的进度回传也断档 —�? 所以整�? [PlayerHandoff] 都要带上�?
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
          episodeNumbers = episodeNumbers,
          index = currentIndex,
          playlistId = playlistId,
          positionMs = positionMs,
          seriesKey = seriesKeys.getOrNull(currentIndex),
          embyServerId = embyServerId,
          embyItemIds = embyItemIds,
          headers = headers,
          // 切回 mpv 时把此刻的亮度交回去：那边 onStart 会按记忆重设，
          // 但记忆里可能还没有这部片（用户从没调过），此时只有交过去的才是对的。
          brightness = handoffBrightness
            ?: runCatching { window.attributes.screenBrightness }.getOrNull()?.takeIf { it in 0f..1f },
        ).writeTo(this)
      }

    startActivity(target)
    // 立刻收摊：两套播放器同时持有解码�? / 音频焦点只会互相打架
    finish()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 按剧亮度
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 套用「这部片上次的亮度」。
   *
   * 取值顺序：交接单带来的（切内核时最准，那是**此刻屏幕上真实的值**）>
   * 本页 seriesKey 的记忆 > 全局默认 > 不动（保持系统亮度）。
   *
   * ⚠️ 只在「记忆开关打开」时用记忆值 —— 关着的时候切内核也应当保持当前亮度，
   *   交接单那条路径是**必然**要用的（它代表用户眼睛现在看到的东西），所以两条分开判。
   */
  private fun applyRememberedBrightness(handoff: Float?) {
    val remembered =
      if (playerPreferences.rememberBrightness.get() ||
        playerPreferences.rememberSeriesBrightness.get()
      ) {
        playerPreferences.brightnessFor(seriesKeys.getOrNull(currentIndex))
      } else {
        -1f
      }
    val value = remembered.takeIf { it in 0f..1f } ?: handoff ?: return
    setScreenBrightness(value)
    // 记录是「本集所属的剧」的，不是「上一次打开的那个」—— 切集后这个标记也该失效。
    handoffBrightness = value
  }

  /** 真的写进 window（GSY 页没有亮度手势，只在进入/切集时用）。 */
  private fun setScreenBrightness(value: Float) {
    runCatching {
      window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0f, 1f) }
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 偏好 �? GSY 官方 API
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 全局静态偏好：**必须�? setUp 之前**调用�?
   *
   * 这几项是进程级静态值（`GSYVideoType` / `PlayerFactory` 里都�? static），
   * 而且按官方文档「设置时机：`setUp` 之前生效」�?
   */
  private fun applyStatics() {
    // 「临时强制内核」优先：只有解码失败自动降级时才会被设上（见 [fallbackToIjk]�?
    val kernel = forcedKernel ?: prefs.kernel.get()

    // 解码内核：PlayerFactory 内部�? newInstance() 反射构造，所以只能传 Class
    PlayerFactory.setPlayManager(
      when (kernel) {
        GsyKernelKind.IJK -> IjkPlayerManager::class.java
        GsyKernelKind.SYSTEM -> SystemPlayerManager::class.java
        GsyKernelKind.EXO -> Exo2PlayerManager::class.java
      },
    )
    // 缓存方案要跟内核配套（官�? skills/04-kernel-switch 的对照表�?
    CacheFactory.setCacheManager(
      if (kernel == GsyKernelKind.EXO) {
        ExoPlayerCacheManager::class.java
      } else {
        ProxyCacheManager::class.java
      },
    )

    // 渲染载体：TEXTURE / SURFACE / GLSURFACE（滤镜只�? GLSURFACE 下有效）
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
   * 实例级偏好。竖屏实例在 `setUp` 之前调，全屏克隆实例�? `startWindowFullscreen` 返回之后�?
   * （官�? `cloneParams` 只搬了一部分字段，`mHideKey` / `mNeedLockFull` / `mFullHideStatusBar` /
   * `mShowFullAnimation` / `mIsOnlyRotateLand` / `mIsTouchWiget` 这些都没搬，必须补一遍）�?
   */
  private fun configurePlayer(view: CineIsleGsyPlayer) {
    // 全屏：一进全屏就锁横屏（官方 setLockLand，实际生效点�? isLockLandByAutoFullSize()�?
    view.setLockLand(prefs.lockLand.get())
    // 只允许横屏方向变�?
    view.setOnlyRotateLand(prefs.onlyRotateLand.get())
    // 竖屏视频自动竖屏全屏（用户手动强制横屏期间例外，�? forcedLandscape�?
    applyAutoFullWithSize(view)
    // 全屏过渡动画
    view.setShowFullAnimation(prefs.showFullAnimation.get())
    // 全屏隐藏系统状态栏（作�? startWindowFullscreen �? statusBar 参数用）
    view.setFullHideStatusBar(prefs.fullHideStatusBar.get())
    // 全屏隐藏虚拟按键
    view.setHideKey(prefs.hideKey.get())
    // 全屏显示屏幕�?
    view.setNeedLockFull(prefs.needLockFull.get())

    // 触摸手势：非全屏 / 全屏
    view.setIsTouchWiget(prefs.touchGesture.get())
    view.setIsTouchWigetFull(prefs.touchGestureFull.get())
    // 横滑 seek 灵敏�?
    view.setSeekRatio(prefs.seekRatio.get())
    // 移动网络提示
    view.setNeedShowWifiTip(prefs.wifiTip.get())
    // 控件自动隐藏时间
    view.setDismissControlTime(prefs.dismissControlTimeMs.get())
    // 拖动进度条时在进度条上显示目标时�?
    view.setShowDragProgressTextOnSeekBar(prefs.showDragProgressText.get())
    // 暂停保留最后一�?
    view.setShowPauseCover(prefs.showPauseCover.get())

    // 循环 / 倍�? / 变速不变调（workRightNow=false：还没起播时先存下来，prepare 时统一生效�?
    view.setLooping(prefs.looping.get())
    view.setSpeed(prefs.defaultSpeed.get(), prefs.soundTouch.get(), false)

    // 强制解封装器（Exo 用；空串表示不设置）
    prefs.overrideExtension.get().takeIf { it.isNotBlank() }?.let { view.setOverrideExtension(it) }

    // GL 滤镜（只�? GLSURFACE 渲染下有效，其余渲染层实现的是空方法，调用无害）
    view.setEffectFilter(prefs.filter.get().toShader())

    // 进入即静�?
    GSYVideoManager.instance().setNeedMute(prefs.muteOnStart.get())

    // 没标题就别留一条空白底�?
    applyTitleVisibility(view)
  }

  /**
   * 「竖屏视频自动竖屏全屏」的实际下发点�?
   *
   * [forcedLandscape] �? true（用户手动按过横屏键）时强制关掉�?
   * 开着�? GSY 会认为「竖屏视频就该竖屏全屏」，于是既不�? `resolveByClick()` 转横屏，
   * 进了横屏之后转回手机竖屏�? `backFromFull()` 也会被跳�? —�? 一进一出全被卡住�?
   */
  private fun applyAutoFullWithSize(view: CineIsleGsyPlayer) {
    view.setAutoFullWithSize(prefs.autoFullWithSize.get() && !forcedLandscape)
  }

  /**
   * 没标题就藏起来�?
   *
   * GSY 只会�? `title` 这个 TextView setText，从不改它的 visibility（它不在
   * `setViewShowState` 的名单里），所以在这里设一次是安全的：setUp / cloneParams
   * 都不会把它翻回来�?
   */
  private fun applyTitleVisibility(view: CineIsleGsyPlayer) {
    view.getTitleTextView()?.visibility = if (videoTitle.isBlank()) View.GONE else View.VISIBLE
  }

  /** 标题的落位：竖屏在底栏独占一行，横屏搬到顶栏与返回键同一�? */
  private fun applyTitlePlacement(view: CineIsleGsyPlayer) {
    view.applyTitlePlacement(
      resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
  }

  // ────────────────────────────────────────────────────────────────────────
  // 控件接线
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 接上 GSY **自己�?**那两个键：右下角�? `fullscreen` 与左上角�? `back`�?
   *
   * 它们�? GSY �? `init()` 里绑好官方行为，我们覆盖掉：
   *  · `fullscreen` �? [toggleLandscapeFullscreen]（手机上「全屏」和「横屏」本来就是一件事�?
   *    所以整个播放页只有这一个方向键，不再另设一个横屏键）；
   *  · `back` �? 交给系统的返回分发，和物理返回键 / 手势返回走同一条路径�?
   *
   * **必须按实例调�?**：GSY 进全屏时克隆出的那个实例是另一棵视图树，它的这两个�?
   * 还是官方默认行为（点「退出全屏」键会走 GSY 内部那套，状态和我们自己维护的对不上）�?
   */
  private fun bindShellButtons(view: CineIsleGsyPlayer) {
    view.getFullscreenButton()?.setOnClickListener { toggleLandscapeFullscreen() }
    view.getBackButton()?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
  }

  /**
   * 接线给自控的按钮条�?
   *
   * 「换渲染载体」只能在重建播放页时生效，所以这里顺手把当前进度写回 intent 再重�? —�?
   * 观感上就是画面闪一下、继续播，而不是被打回从头�?
   */
  private fun bindControls(view: CineIsleGsyPlayer) {
    view.bindGlassControls(
      prefs = prefs,
      actions =
        GsyGlassActions(
          onRecreateNeeded = { message ->
            if (view.isIfCurrentIsFullscreen) {
              // 全屏实例�? GSY 克隆出来的另一棵树，重建会让全屏状态错�? —�? 让用户先退全屏
              Toast.makeText(this, "$message（请先退出全屏再切换）", Toast.LENGTH_LONG).show()
            } else {
              Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
              recreateKeepingPosition()
            }
          },
          onPrevious = { playIndex(currentIndex - 1) },
          onNext = { playIndex(currentIndex + 1) },
          onSwitchEngine = { switchToMpvPlayer() },
          onScreenshot = { takeSnapshot() },
          onSubtitle = { showSubtitleMenu() },
          onAudioTrack = { showAudioTrackDialog() },
          onChapters = { showChaptersDialog() },
          onPictureInPicture = { enterPip() },
          onCast = { showCastSheet() },
          onDislikeToggle = { onDislikeToggle() },
          onDislikeRemark = { showDislikeRemarkDialog() },
        ),
    )
    // 剪辑/切集后字幕键的激活态要跟着实例�?
    view.setSubtitleActive(subtitleSource != null)
    // 字幕样式要在**每个实例**上各刷一次（清单 #19）—�? 全屏克隆实例是另一棵视图树�?
    // 它自带一个空�? GSYSubtitleView，不刷的话进全屏字号/颜色会回�? GSY 默认值�?
    applySubtitleStyleTo(view)
  }

  /**
   * 官方回调�?
   *
   * 两件事官方不会替我们做：
   *  · **全屏克隆实例的接�?** —�? 由传感器 / 配置变化触发的全屏不会经过我们的
   *    `startWindowFullscreen` 返回值，只能在这里从 `objects[0]` 拿到克隆实例补配置；
   *  · **播完自动下一�?** —�? GSY 只管把这一集放完，队列是我们自己维护的�?
   */
  private fun installCallbacks(view: CineIsleGsyPlayer) {
    view.setVideoAllCallBack(
      object : GSYSampleCallBack() {
        override fun onEnterFullscreen(url: String?, vararg objects: Any?) {
          // GSY 13.x 的回调签名是 onEnterFullscreen(url, title, fullPlayer)�?
          // objects = [title 字符�?, 克隆实例]。直接在 objects 里找实例�?
          // 别依�? listener() —�? 非动画分支下它此刻还指向竖屏的小屏实例，
          // 拿去 configureClone 会把克隆实例整个漏掉（全屏里收藏�?/玻璃按钮全失灵）�?
          val full =
            objects.firstNotNullOfOrNull { it as? CineIsleGsyPlayer }
              ?: (GSYVideoManager.instance().listener() as? CineIsleGsyPlayer)
          full?.let { configureClone(it) }
          pipHelper?.updateParams()
        }

        override fun onQuitFullscreen(url: String?, vararg objects: Any?) {
          handleFullExited()
        }

        override fun onAutoComplete(url: String?, vararg objects: Any?) {
          if (canAdvance()) {
            playIndex(currentIndex + 1)
          } else if (prefs.closeAfterEnd.get()) {
            finish()
          }
        }

        /**
         * 解码 / 解封装失�? �? 自动换内核再试一次（�? [fallbackToIjk]）�?
         *
         * 这条是「AVI 放不了」的通用解药：GSY �? Exo / System 两个内核都依赖设备自带的
         * 解码器，碰到 AVI + Xvid（MPEG-4 ASP）、WMV、RMVB 这类就必挂；只有 IJK
         * 是自�? ffmpeg 的。与其让用户自己跑去设置里翻内核，不如这里直接兜住�?
         */
        override fun onPlayError(url: String?, vararg objects: Any?) {
          fallbackToIjk()
        }
      },
    )
  }

  /**
   * 全屏克隆实例的「补配置」：偏好要补一遍、玻璃按钮条要重新接线�?
   * 标题要按当前方向重新摆位、上/下一集要按当前队列重新判定�?
   */
  private fun configureClone(clone: CineIsleGsyPlayer) {
    if (fullscreenPlayer === clone) return
    fullscreenPlayer = clone
    clone.setVideoAllCallBack(cloneCallback)
    applySafeArea(clone)
    configurePlayer(clone)
    applyTitlePlacement(clone)
    bindShellButtons(clone)
    bindControls(clone)
    syncNavigation(clone)
    // 克隆实例�?**另一棵视图树**：它自己�? gsy_btn_favorite 是个全新的空 ComposeView�?
    // setContent 只对竖屏实例做过，所以一进全屏顶栏那个收藏键�?"消失"�? —�? 这里补挂一次�?
    setUpFavoriteButton(clone)
    // GSY 的 window 全屏是把克隆容器 **addView 到 android.R.id.content 的末尾**
    // （对过 GSYBaseVideoPlayer.startWindowFullscreen 源码）⇒ 它会整块盖住我们的
    // 弹幕叠层。补一次前置，让弹幕在全屏里也压在播放器之上。
    danmakuOverlay?.bringToFront()
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

      /** 全屏实例同样要接解码失败降级，否则「竖屏能放、一进全屏就黑�? */
      override fun onPlayError(url: String?, vararg objects: Any?) {
        fallbackToIjk()
      }

      override fun onQuitFullscreen(url: String?, vararg objects: Any?) {
        handleFullExited()
      }
    }

  /**
   * 解码失败时的兜底：改�? IJK（自�? ffmpeg）重播一次�?
   *
   * 为什么必须是「重建播放页」而不是就地换：`PlayerFactory.setPlayManager` 写的�?
   * 进程级静态值，�? GSYVideoManager 手里那个 manager 实例是在 prepare 时就建好�? —�?
   * 就地改对**正在播的这一�?**不生效。换渲染载体也是同一个道理，走的也是重建
   * （见 [recreateKeepingPosition]）�?
   *
   * 只降一次：降完还失败就直接把话说清楚，不要无限重建把用户卡在闪屏里�?
   */
  private fun fallbackToIjk() {
    val current = forcedKernel ?: prefs.kernel.get()
    if (current == GsyKernelKind.IJK) {
      Toast.makeText(this, "所有可用内核均无法解码该文件，请检查媒体源是否完好", Toast.LENGTH_LONG).show()
      return
    }
    if (kernelFallbackUsed) return
    kernelFallbackUsed = true
    forcedKernel = GsyKernelKind.IJK
    Toast.makeText(this, "当前内核无法解码此文件，已自动切换至 IJK（ffmpeg）内核重试", Toast.LENGTH_LONG).show()
    recreateKeepingPosition()
  }

  /** 带上当前播放进度重建播放页（换渲染载�? / 开滤镜的唯一办法�? */
  private fun recreateKeepingPosition() {
    val position = activePlayer?.getCurrentPositionWhenPlaying() ?: 0L
    val handoff =
      PlayerHandoff(
        playlist = playlist,
        titles = titles,
        seriesKeys = seriesKeys,
        episodeNumbers = episodeNumbers,
        index = currentIndex,
        playlistId = playlistId,
        positionMs = position,
        seriesKey = seriesKeys.getOrNull(currentIndex),
        embyServerId = embyServerId,
        embyItemIds = embyItemIds,
        headers = headers,
        // 重建是新 Activity，window 亮度会回到系统值 ⇒ 必须把当前亮度带过去
        brightness = handoffBrightness
          ?: runCatching { window.attributes.screenBrightness }.getOrNull()?.takeIf { it in 0f..1f },
      )
    Intent(intent).apply {
      data = currentUri
      handoff.writeTo(this)
      putExtra("play_from_start", false)
      putExtra(EXTRA_RESTORE_POSITION, true)
      // 降级出来的「强制内核」必须跟着重建一起带过去。否则重建后 `readSession` 读不到它�?
      // 又会用回用户选的那个内核 �? 再失�? �? 再降级，就成了死循环�?
      forcedKernel?.let { putExtra(EXTRA_FORCE_KERNEL, it.name) }
      setIntent(this)
    }
    recreate()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 横竖�?
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 方向工具与两个「同名但不同义」的开关�?
   *
   * `OrientationUtils` �? `GSYBaseVideoPlayer` **各有一�?** `mRotateWithSystem`，含义完全不同：
   *
   *  · `OrientationUtils.mRotateWithSystem`（官方默�? true）：系统「自动旋转」关掉时�?
   *    它就把重力感应整个忽略掉 —�? 用户怎么转手机播放页都不动，看上去就是「切不了横屏」�?
   *    这里按偏好传 false（默认），让播放页内始终跟随重力感应，与 mpv 播放页一致�?
   *
   *  · `GSYBaseVideoPlayer.mRotateWithSystem`：GSY 在「转回竖屏」的配置回调里会执行
   *    `orientationUtils.setEnable(isRotateWithSystem())`。一旦它�? false�?
   *    转一次竖屏就会把传感器监�? `disable()` 掉，之后彻底不再响应旋转。必须保�? true�?
   *
   * 另外 `setIsPause` 是老版�? `pause()/resume()` �? 13.x 里的替代写法�?
   * �? true �? `onOrientationChanged` 会直�? return，所以离开页面要置 true、回来要�? false�?
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
   * 竖屏 �? 横屏，一次点击切过去�?
   *
   * 这是播放�?**唯一**的方向键 —�? 绑在右下�? GSY 官方�? `fullscreen` 键上（见 [bindShellButtons]）�?
   * 手机上「全屏」和「横屏全屏」本来就是同一件事，之前拆成两个键又指向同一条逻辑�?
   * 只会让人不知道该点哪�? —�? 现在只留一个�?
   *
   * 做法�?**刻意绕开官方�? `resolveByClick()`**，只做一件事：直接改 Activity 的方向请求�?
   * 为什么不能用它：
   *
   *  1. 它第一行就�? `if (mIsLand == 0 && mVideoPlayer.isVerticalFullByVideoSize()) return;`�?
   *     �? `isVerticalFullByVideoSize() = isVerticalVideo() && isAutoFullWithSize()` —�?
   *     本项�? `setAutoFullWithSize` 默认开着，于�?**竖屏视频点它直接�? return**，怎么点都没反应�?
   *  2. 它设的是 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，之�? `OrientationUtils` 的传感器回调
   *     还会按重力再把方向改回去 —�? 两个方向请求来回拉锯，用户就得点好几次才切得过去�?
   *
   * 现在的做法：
   *  · 切横�? �? �? `orientationUtils.setEnable(false)` 把传感器让开，再请求 `SENSOR_LANDSCAPE`，一次到位；
   *  · 切竖�? �? 请求 `SENSOR`（与 mpv 播放页一致：交还重力感应），
   *    �? `onConfigurationChanged` 的竖屏分支把 `orientationUtils` 重新启用�?
   *
   * �? / 出全屏仍然交�? GSY 官方那条路（转横屏它就进全屏、转竖屏它就退全屏），
   * 只是入口从「官方按重力判断」换成了「我们明确要求的方向」�?
   */
  private fun toggleLandscapeFullscreen() {
    val view = player ?: return
    val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (landscape) {
      // �? 回竖屏：退出走幂等入口（退出在途时这里�? no-op，见 [fullBusy]�?
      requestExitFullscreen()
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
      return
    }

    // �? 竖屏状态下的旋转键
    if (fullscreenPlayer != null) {
      // 竖屏视频的「竖屏全屏」在场：这一下先退全屏回普通竖屏，再点一下才转横�?
      requestExitFullscreen()
      return
    }
    if (fullBusy) return

    // 判定「竖屏视频」的 isVerticalFullByVideoSize() �? protected，外部拿不到�?
    // 手动切横屏时本来也不需要「竖屏视频自动竖屏全屏」，所以一律按强制横屏处理�?
    forcedLandscape = true
    applyAutoFullWithSize(view)
    orientationUtils?.setEnable(false)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  }

  // ────────────────────────────────────────────────────────────────────────
  // 截图
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 截当前帧�?
   *
   * �? GSY 官方�? `saveFrame(File, GSYVideoShotSaveListener)`（内部会把渲染层当前帧取出来），
   * 落盘交给 [PlayerSnapshotStore] —�? �? MediaStore，不需要存储权限�?
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
  // 章节（清�? #19�?
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 章节面板�?
   *
   * ## �? mpv �? `ChaptersSheet` 的差�?
   *
   * mpv 侧是 Compose 弹层（`ChaptersSheet`，复用了 `GenericTracksSheet`），
   * 数据来自 `PlayerLib.propNode["chapter-list"]` —�? 一�? StateFlow�?
   * 章节�? `loadfile` 时就�? demuxer 解析好了，开面板零延迟�?
   *
   * GSY 三样都没有：没有章节 property、没有当前章下标、也没有 `setPropertyInt("chapter", i)`�?
   * 所以这里是**先按需�? Emby、拉完再�?**（[GsyChapterRepository.chaptersOf]）�?
   *
   * ## 为什么要�? AlertDialog 而不�? Compose 弹层
   *
   * mpv 侧的 `ChaptersSheet` 依赖 Compose �? `PlayerSheet` 宿主，�? GSY 播放页是
   * **�? View 体系**（`setContentView(R.layout.gsy_player_cineisle)`，没�? ComposeView）�?
   * 为了一个面板把 Compose 塞进 GSY 页不划算 —�? 本页所有交互（音轨、字幕、屏幕比例）
   * 都是 AlertDialog，章节跟着走才一致�?
   *
   * ## 拿不到章节时的表�?
   *
   * 直接 Toast 一句就完事�?**不开空面�?**。空列表弹一个没内容的对话框�?
   * 用户会以为是加载卡住了�?
   */
  private fun showChaptersDialog() {
    val itemId = embyItemIds.getOrNull(currentIndex)
    // 已经在缓存里就直接弹，不让用户等网络
    chapterRepository.cachedOf(itemId)?.let { cached ->
      if (cached.isNotEmpty()) {
        presentChapters(cached)
        return
      }
    }

    if (embyServerId == null || itemId.isNullOrBlank()) {
      // 纯本地文件：GSY 没有从容器解析章节的能力，这是内核边界不�? bug
      Toast.makeText(this, R.string.gsy_chapters_local_no_server, Toast.LENGTH_SHORT).show()
      return
    }

    Toast.makeText(this, R.string.gsy_chapters_loading, Toast.LENGTH_SHORT).show()
    lifecycleScope.launch {
      val server = currentEmbyServer() ?: return@launch
      val chapters = chapterRepository.chaptersOf(server, itemId)
      if (chapters.isEmpty()) {
        Toast.makeText(this@GsyPlayerActivity, R.string.gsy_chapters_empty, Toast.LENGTH_SHORT).show()
      } else {
        presentChapters(chapters)
      }
    }
  }

  /** 章节面板本体。当前章打勾（高�? + ✓），点任意一项立即跳过去�? */
  private fun presentChapters(chapters: List<GsyChapter>) {
    val current = currentPositionMs()
    val currentIndex = chapters.indexOfCurrent(current)
    val labels =
      chapters
        .mapIndexed { i, chapter ->
          val prefix = if (i == currentIndex) "${getString(R.string.gsy_chapters_now)}  " else ""
          val time = formatOffset(chapter.startMs)
          val title = chapter.title.ifBlank { getString(R.string.gsy_chapters_untitled, i + 1) }
          "$prefix$time  $title"
        }
        .toTypedArray()

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_chapters_title)
      .setItems(labels) { _, which ->
        val chapter = chapters.getOrNull(which) ?: return@setItems
        val target = player ?: return@setItems
        runCatching { target.seekTo(chapter.startMs) }
          .onFailure { Toast.makeText(this, R.string.gsy_chapters_seek_failed, Toast.LENGTH_SHORT).show() }
        Toast.makeText(this, R.string.gsy_chapters_jumped, Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * 当前播放头位置（毫秒）�?
   *
   * GSY �? `curPlayerManager` 在起播前 / 释放后可能是 null 或已 detach�?
   * 所以全链路都包�? `runCatching` �? —�? 拿不到就�? 0（表现为「没有高亮当前章」，
   * 而不是崩）�?
   */
  private fun currentPositionMs(): Long =
    runCatching { GSYVideoManager.instance().curPlayerManager?.currentPosition ?: 0L }
      .getOrDefault(0L)

  /**
   * 当前这一项对应的 Emby 服务器�?
   *
   * [EmbyRepository] 持有「当前登录的服务器」，播放页不自己存一�? —�?
   * 用户可能在另一个页面换了服务器，这里跟着走才是对的�?
   * `awaitCurrentServer()` 会在首次读取时挂起直到拿到服务器，所以包�? IO 协程里�?
   */
  private suspend fun currentEmbyServer() =
    withContext(Dispatchers.IO) {
      embyRepository.awaitCurrentServer()
      embyRepository.currentServer.value
    }

  // ────────────────────────────────────────────────────────────────────────
  // 字幕
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 字幕菜单�?
   *
   * 两类字幕分开管，别混�?
   *   - **外挂字幕**�?.srt / .vtt）—�? �? GSY 字幕子系统（`GSYSubtitleController` 只吃外挂源）�?
   *   - **内嵌字幕�?**（mkv / mp4 里自带的那些）—�? �? [showSubtitleTrackDialog]�?
   *
   * 原先只有前者，所以「mkv 有内嵌中字但没有外挂文件」这种片子在 GSY 页没法切字幕�?
   */
  private fun showSubtitleMenu() {
    val has = subtitleSource != null
    val items = mutableListOf<String>()
    items += getString(R.string.gsy_subtitle_pick)
    // 内嵌字幕轨：始终给入口（有没有轨�? showTrackDialog 自己提示，不预先探测�?
    items += getString(R.string.gsy_subtitle_track_title)
    // 字幕样式：始终给入口（清�? #19 新增）。GSY 的样式只对外挂字幕生效，
    // 但入口保�? —�? 用户可以在还没挂字幕时先把字�?/颜色调好�?
    items += getString(R.string.gsy_subtitle_style)
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
          1 -> showSubtitleTrackDialog()
          2 -> showSubtitleStyleDialog()
          3 -> if (has) showSubtitleDelayDialog()
          else -> clearSubtitle()
        }
      }
      .show()
  }

  /**
   * 字幕样式面板（清�? #19：GSY 补上「字幕样式」这一项）�?
   *
   * ## �? mpv 侧那个三卡片面板的差�?
   *
   * mpv 侧是完整�? Compose 面板（`SubtitleSettingsPanel`：字体排�? / 三色 / 杂项三张卡）�?
   * 因为 mpv 能改**四十多个 option**。GSY 侧能改的只有 `GSYSubtitleStyle` 里那几项
   * （字号、文字色、背景色、阴影、边距、对齐），所以这里用 AlertDialog 而不是再写一�?
   * Compose 面板 —�? 6 个选项铺一张卡片是浪费，铺一个列表更合适，也和本页其他对话框一致�?
   *
   * ## 改完立刻生效，不必重�?
   *
   * `GSYVideoControlView.setSubtitleStyle()` 一路传�? `GSYSubtitleView.applyStyle()`�?
   * 后者直接调 `setTextColor` / `setTextSize` / `setShadowLayer` —�? 已经在显示的字幕会重绘�?
   * �?**竖屏实例和全屏克隆实例都要刷**（和 [applySubtitleToAll] 同一个理由）�?
   *
   * ## 存的�? mpv 侧的原始刻度
   *
   * 所有改动都写回 [SubtitlesPreferences]（`sub_font_size` / `sub_color_text` …）�?
   * 不新增任�? GSY 专属偏好�? —�? 换回 mpv 内核时同一套设置继续生效，
   * 这才是「双内核共用一�? UI」的意义。反过来 mpv 调的字号，GSY 也认�?
   * 换算规则全在 [GsySubtitleStyleMapper] 里，两个内核的差异也写在那儿�?
   *
   * ## 做不到的项显式列出来
   *
   * 用户开�? ASS 覆盖 / 指定了字�? / 开了粗斜体，GSY 这边统统做不�?
   * （`GSYSubtitleStyle` 没有字体入口，也压根不认 ASS）�?
   * 这里�? [GsySubtitleStyleMapper.unsupportedByGsy] 查出来列在标题里 —�?
   * 让用户知道「不是坏了，是这个内核做不到」，比默默不生效好�?
   */
  private fun showSubtitleStyleDialog() {
    val unsupported = GsySubtitleStyleMapper.unsupportedByGsy(subtitlesPreferences)
    val title =
      if (unsupported.isEmpty()) {
        getString(R.string.gsy_subtitle_style)
      } else {
        getString(R.string.gsy_subtitle_style_with_unsupported, unsupported.joinToString("、"))
      }

    val items =
      arrayOf(
        getString(
          R.string.gsy_subtitle_style_size,
          GsySubtitleStyleMapper.scaledFontSizeSp(subtitlesPreferences.fontSize.get()).toInt(),
        ),
        getString(
          R.string.gsy_subtitle_style_shadow,
          GsySubtitleStyleMapper.shadowRadius(subtitlesPreferences.borderSize.get()).toInt(),
        ),
        getString(R.string.gsy_subtitle_style_color),
        getString(R.string.gsy_subtitle_style_border_color),
        getString(
          R.string.gsy_subtitle_style_align,
          alignmentLabel(subtitlesPreferences.justification.get()),
        ),
        getString(R.string.gsy_subtitle_style_reset),
      )

    android.app.AlertDialog
      .Builder(this)
      .setTitle(title)
      .setItems(items) { _, which ->
        when (which) {
          0 -> showFontSizeDialog()
          1 -> showShadowSizeDialog()
          2 -> pickSubtitleColor(isBorder = false)
          3 -> pickSubtitleColor(isBorder = true)
          4 -> showAlignmentDialog()
          5 -> resetSubtitleStyle()
        }
      }
      .show()
  }

  /**
   * 把当前偏好刷�?**所�?**播放器实例（竖屏 + 全屏克隆）�?
   *
   * `density` 从这里取而不是在 mapper 内部取，是为了让 [GsySubtitleStyleMapper] 保持�?
   * 纯函�? —�? 间距换算恰恰是最需要被单测断言的部分，不能依赖 Android 静态状态�?
   */
  private fun applySubtitleStyle() {
    applySubtitleStyleTo(player)
    fullscreenPlayer?.let { if (it !== player) applySubtitleStyleTo(it) }
  }

  /** 刷单个实例。[GsySubtitleView.applyStyle] 会立即重绘，不用重新起播�? */
  private fun applySubtitleStyleTo(target: CineIsleGsyPlayer?) {
    if (target == null) return
    runCatching {
      target.setSubtitleStyle(
        GsySubtitleStyleMapper.styleFrom(resources.displayMetrics.density, subtitlesPreferences),
      )
    }
  }

  /**
   * 字号：带当前值的单选�?
   *
   * 不用滑杆 —�? AlertDialog 里现成搭不出滑杆，而字号本来就只有 [FONT_SIZE_STEPS] 几档�?
   * 单选列表一次看全所有档位，比拖滑杆更准�?
   *
   * 存回的是 **mpv 侧的原始刻度**（`sub_font_size` 的值域 0~200），
   * 显示时才�? [GsySubtitleStyleMapper.scaledFontSizeSp] 换算�? sp�?
   */
  private fun showFontSizeDialog() {
    val current = subtitlesPreferences.fontSize.get()
    val checked = FONT_SIZE_STEPS.indexOfFirst { it >= current }.let { if (it < 0) FONT_SIZE_STEPS.lastIndex else it }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_subtitle_style_size_pick)
      .setSingleChoiceItems(
        FONT_SIZE_STEPS
          .map { getString(R.string.gsy_subtitle_style_size, GsySubtitleStyleMapper.scaledFontSizeSp(it).toInt()) }
          .toTypedArray(),
        checked,
      ) { dialog, which ->
        subtitlesPreferences.fontSize.set(FONT_SIZE_STEPS[which])
        applySubtitleStyle()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * 阴影粗细�?
   *
   * 存的�? `sub_border_size`（mpv 侧叫「描边大小」，范围 0~25），
   * GSY 没有描边只有阴影，所以这个值被当作阴影半径�? —�? 映射理由写在
   * [GsySubtitleStyleMapper.shadowRadius] �? KDoc 里�?
   */
  private fun showShadowSizeDialog() {
    val current = subtitlesPreferences.borderSize.get()
    val checked = SHADOW_SIZE_STEPS.indexOfFirst { it >= current }.let { if (it < 0) SHADOW_SIZE_STEPS.lastIndex else it }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_subtitle_style_shadow_pick)
      .setSingleChoiceItems(
        SHADOW_SIZE_STEPS
          .map { getString(R.string.gsy_subtitle_style_shadow, GsySubtitleStyleMapper.shadowRadius(it).toInt()) }
          .toTypedArray(),
        checked,
      ) { dialog, which ->
        subtitlesPreferences.borderSize.set(SHADOW_SIZE_STEPS[which])
        applySubtitleStyle()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * 对齐�?
   *
   * ⚠️ 这里**只给三个选项、不�? `Auto`**：`Auto` �? GSY 下会被映射成居中
   * （见 [GsySubtitleStyleMapper.gravity]），给一个「选了看不出区别」的选项是骗人�?
   * 想回到默认值走面板最后那项「重置」�?
   */
  private fun showAlignmentDialog() {
    val options = ALIGNMENT_OPTIONS
    val current = subtitlesPreferences.justification.get()
    val checked = options.indexOf(current).let { if (it < 0) 1 else it }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_subtitle_style_align_pick)
      .setSingleChoiceItems(options.map { alignmentLabel(it) }.toTypedArray(), checked) { dialog, which ->
        subtitlesPreferences.justification.set(options[which])
        applySubtitleStyle()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * 取色�?
   *
   * ⚠️ 这里�?**预置色板单�?**而不是项目里那个 `SubtitlesColorPicker`（RGBA 四条滑杆）：
   * 那个�? `@Composable`，塞不进 `AlertDialog`（除非另开一�? `Dialog` + `setContent`�?
   * 那就为一个取色器多铺一�? Compose 容器，不值）。色板覆盖了字幕绝大多数实际需求，
   * 真要细调的用户去 mpv 侧那个三色面�? —�? 两个内核共用同一份偏好，调了互相认�?
   *
   * @param isBorder true 改描边色（GSY 侧当阴影色用），false 改文字色�?
   */
  private fun pickSubtitleColor(isBorder: Boolean) {
    val preference = if (isBorder) subtitlesPreferences.borderColor else subtitlesPreferences.textColor
    val current = preference.get()
    val palette = SUBTITLE_COLOR_PALETTE
    val labels = palette.map { colorHexLabel(it) }.toTypedArray()
    // 当前颜色不在色板里就落在第一项，不预选一个「看起来像但其实不是」的颜色
    val checked = palette.indexOf(current).let { if (it < 0) 0 else it }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(if (isBorder) R.string.gsy_subtitle_style_border_color else R.string.gsy_subtitle_style_color)
      .setSingleChoiceItems(labels, checked) { dialog, which ->
        preference.set(palette[which])
        applySubtitleStyle()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** 颜色的十六进制标签（`#RRGGBB`，不�? alpha —�? 字幕色板都是不透明的） */
  private fun colorHexLabel(color: Int): String =
    String.format(Locale.US, "#%06X", 0xFFFFFF and color)

  /**
   * 重置字幕样式�?
   *
   * 只重�?**样式�?**的项�?**不动**外挂字幕轨、字幕延迟、音轨这�? —�?
   * 「重置样式」不该把用户挑好的字幕文件也清掉�?
   *
   * �? [Preference.delete] 而不�? `set(默认�?)`：删掉键之后 [Preference.get] 会回落到
   * `defaultValue()`，这样默认值只写在 [SubtitlesPreferences] 一处，
   * 以后调默认值不用记得回来同步这个列表�?
   */
  private fun resetSubtitleStyle() {
    subtitlesPreferences.fontSize.delete()
    subtitlesPreferences.textColor.delete()
    subtitlesPreferences.backgroundColor.delete()
    subtitlesPreferences.borderColor.delete()
    subtitlesPreferences.borderSize.delete()
    subtitlesPreferences.shadowOffset.delete()
    subtitlesPreferences.justification.delete()
    subtitlesPreferences.subPos.delete()
    applySubtitleStyle()
    Toast.makeText(this, R.string.gsy_subtitle_style_reset_done, Toast.LENGTH_SHORT).show()
  }

  private fun alignmentLabel(value: SubtitleJustification): String =
    getString(
      when (value) {
        SubtitleJustification.Left -> R.string.gsy_subtitle_style_align_left
        SubtitleJustification.Center -> R.string.gsy_subtitle_style_align_center
        SubtitleJustification.Right -> R.string.gsy_subtitle_style_align_right
        SubtitleJustification.Auto -> R.string.gsy_subtitle_style_align_auto
      },
    )

  /** 字幕延迟微调（官�? `setSubtitleOffsetMs`，正�? = 字幕往后推�? */
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

  /** 小屏实例与全屏克隆实例都要挂，否则全屏下字幕会突然消�? */
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

  /** �? SAF URI 的展示名（拿不到就退回路径末段） */
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
   * 猜字幕文件的字符集�?
   *
   * 中文外挂字幕里有大量 GBK 编码的老文件，直接�? UTF-8 读会满屏「锟斤拷」�?
   * 做法：有 BOM �? UTF-8；否则严格解码前 64KB，能过就�? UTF-8，报错就�? GBK�?
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
   * 音轨选择（薄壳，实现�? [showTrackDialog] —�? 音轨 / 字幕轨共用一套）�?
   */
  private fun showAudioTrackDialog() {
    showTrackDialog(
      trackType = ITrackInfo.MEDIA_TRACK_TYPE_AUDIO,
      kindRes = R.string.gsy_track_kind_audio,
      titleRes = R.string.gsy_audio_title,
    )
  }

  /**
   * 字幕轨选择�?**内嵌**轨）�?
   *
   * ⚠️ 与「外挂字幕」是**两回�?**，别混：
   *   - 外挂字幕 = [showSubtitleMenu] 里挂的独�? .srt / .vtt，走 GSY 字幕子系统；
   *   - 字幕�?   = 容器（mkv / mp4）里内嵌的那几条字幕，本项管的是这个�?
   *
   * 很多 mkv 有内嵌中文轨却一个外挂文件都没有，�? GSY 页此�?**完全没有这个入口**
   *（只有音轨对话框），想切内嵌字幕只能�? mpv 内核 —�? 明确的功能缺口，本次补上�?
   */
  private fun showSubtitleTrackDialog() {
    showTrackDialog(
      trackType = ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
      kindRes = R.string.gsy_track_kind_subtitle,
      titleRes = R.string.gsy_subtitle_track_title,
    )
  }

  /**
   * 轨道选择对话框（音轨 / 字幕轨共用）�?
   *
   * GSY �?**通用**播放接口（`com.shuyu.gsyvideoplayer.player.IPlayerManager`）里没有轨道方法�?
   * 只有 IJK �? [IjkPlayerManager] 自己多提供了 `trackInfo` / `selectTrack()` /
   * `getSelectedTrack()` / `deselectTrack()` —�? 所以这项能�?**只在 IJK 内核下可�?**�?
   * �? System / Exo 会明确提示（不做静默失败：用户按了按钮没反应是最糟的）�?
   *
   * 轨道下标就是 `trackInfo` 数组的下标（IJK �? `selectTrack(int)` 收的就是全局轨道索引）�?
   *
   * 字幕轨列表最前面额外插一个「关闭」项：内嵌字幕可以整体关掉�?
   * 音轨不给这一�? —�? 关掉声音没有意义�?
   *
   * @param trackType [ITrackInfo.MEDIA_TRACK_TYPE_AUDIO] �? [ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE]
   * @param kindRes 「音�? / 字幕轨」这个词，用于不支持 / 为空时的提示�?
   * @param titleRes 对话框标�?
   */
  private fun showTrackDialog(trackType: Int, kindRes: Int, titleRes: Int) {
    val manager = GSYVideoManager.instance().curPlayerManager
    val ijk = manager as? IjkPlayerManager
    if (ijk == null) {
      Toast
        .makeText(
          this,
          getString(R.string.gsy_track_unsupported, getString(kindRes), prefs.kernel.get().name),
          Toast.LENGTH_LONG,
        )
        .show()
      return
    }

    val infos: Array<IjkTrackInfo> = runCatching { ijk.trackInfo }.getOrNull() ?: emptyArray()
    val tracks =
      infos.mapIndexedNotNull { index, info ->
        if (info.trackType == trackType) index to info else null
      }

    if (tracks.isEmpty()) {
      Toast
        .makeText(this, getString(R.string.gsy_track_empty, getString(kindRes)), Toast.LENGTH_SHORT)
        .show()
      return
    }

    val selected = runCatching { ijk.getSelectedTrack(trackType) }.getOrDefault(-1)
    val kindLabel = getString(kindRes)
    val closable = trackType == ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
    // 字幕轨在列表最前面插一个「关闭」；两种列表的下标含义不同，�? trackIndexAt 收口
    val labels =
      buildList {
          if (closable) add(getString(R.string.gsy_track_off))
          tracks.forEachIndexed { n, (_, info) ->
            val language = info.language?.takeIf { it.isNotBlank() && it != "und" }
            val detail = language ?: info.infoInline.orEmpty().take(40)
            add("$kindLabel ${n + 1} · $detail")
          }
        }
        .toTypedArray()
    val trackIndexAt: (Int) -> Int = { which -> if (closable && which == 0) -1 else tracks[which - 1].first }
    val checkedAt =
      when {
        selected < 0 && closable -> 0
        selected < 0 -> -1
        else -> tracks.indexOfFirst { it.first == selected }.let { if (it < 0) -1 else it + 1 }
      }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(titleRes)
      .setSingleChoiceItems(labels, checkedAt) { dialog, which ->
        val idx = trackIndexAt(which)
        if (idx < 0) {
          // 「关闭」：先把当前选中的那�? deselect 掉，内嵌字幕即不再显�?
          if (selected >= 0) runCatching { ijk.deselectTrack(selected) }
        } else {
          runCatching { ijk.selectTrack(idx) }
        }
        dialog.dismiss()
      }
      .show()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 画中�? / 投屏
  // ────────────────────────────────────────────────────────────────────────

  private fun enterPip() {
    val helper = pipHelper
    if (helper == null || !helper.isSupported()) {
      Toast.makeText(this, R.string.gsy_pip_unsupported, Toast.LENGTH_SHORT).show()
      return
    }
    // 进小窗前先把两棵视图树的控件收掉 —�? 系统�? onPictureInPictureModeChanged
    // 要等收缩动画开始之后才回调，等它再收的话，控件条会被整块等比缩进小窗里挤成一坨�?
    player?.setPipMode(true)
    fullscreenPlayer?.takeIf { it !== player }?.setPipMode(true)

    if (!helper.enter()) {
      // 进小窗失败（极少数机型）：把控件还原回来，别让页面停在无控件状�?
      player?.setPipMode(false)
      fullscreenPlayer?.takeIf { it !== player }?.setPipMode(false)
      Toast.makeText(this, R.string.gsy_pip_unsupported, Toast.LENGTH_SHORT).show()
    }
  }

  // ──────────────────────────────── 标记为不喜欢 ────────────────────────────────

  /**
   * 订阅「不喜欢」的反馈提示�?
   *
   * 选中�?**不用这里�?** —�? 那颗键是 ComposeView，每个实例各自订�?
   * `DislikePlayerActions.isMarked`，竖屏实例与全屏克隆实例天然同步
   * （详�? `CineIsleGsyPlayer.DislikeButtonContent`）�?
   */
  private fun observeDislikeState() {
    // 轻提示单独一个协程：提示是「发一次就清」的，用 collectLatest 更合�? —�?
    // 快速连点时后一条会顶掉前一条，而不是排着队挨个弹�?
    lifecycleScope.launch {
      DislikePlayerActions.toast.collectLatest { message ->
        if (message == null) return@collectLatest
        Toast.makeText(this@GsyPlayerActivity, message, Toast.LENGTH_SHORT).show()
        DislikePlayerActions.consumeToast()
      }
    }
  }

  /** 单击「不喜欢」：切换标记并给轻提示�? */
  private fun onDislikeToggle() {
    DislikePlayerActions.toggle()
  }

  /**
   * 长按「不喜欢」：填备注�?
   *
   * �? `AlertDialog` + `EditText` 而不�? Compose：这一页整体是�? View 体系
   * （`setContentView`，见 [showChaptersDialog] 的说明），为这一个输入框再铺一�?
   * ComposeView 覆盖层不值得 —�? 页面上其他所有弹窗也都是 AlertDialog，保持一致�?
   *
   * 确认按钮文案随当前状态变：已标记时是「保存」，未标记时是「保存并标记�?
   * （保存备注本身就算标记，�? [DislikePlayerActions.saveRemark]）�?
   */
  private fun showDislikeRemarkDialog() {
    if (DislikePlayerActions.currentKey.value == null) {
      Toast.makeText(this, R.string.gsy_dislike_unavailable, Toast.LENGTH_SHORT).show()
      return
    }
    val input = EditText(this).apply {
      setHint(R.string.gsy_dislike_reason_hint)
      setText(DislikePlayerActions.currentReason())
      setSelection(text.length)
      setSingleLine(false)
      maxLines = 3
    }
    val marked = DislikePlayerActions.isMarked.value
    val padding = (20 * resources.displayMetrics.density).toInt()
    val container =
      FrameLayout(this).apply {
        setPadding(padding, padding / 2, padding, 0)
        addView(input)
      }

    android.app.AlertDialog
      .Builder(this)
      .setTitle(R.string.gsy_dislike_reason_title)
      .setView(container)
      .setPositiveButton(
        if (marked) R.string.gsy_dislike_reason_save else R.string.gsy_dislike_reason_save_and_mark,
      ) { _, _ -> DislikePlayerActions.saveRemark(input.text.toString()) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * 投屏面板�?
   *
   * 直接复用 mpv 播放页那�? [DlnaSheet]（同一�? [DlnaCastManager] 单例 + 同一�? Compose 面板），
   * 只是本页是纯 View 体系，所以用一�? [ComposeView] 覆盖层把它挂上来�?
   *
   * 投屏意味着画面交给远端设备，本机继续解码只是白耗电，所以先暂停本地播放�?
   */
  private fun showCastSheet() {
    val uri = currentUri
    if (uri == null) {
      Toast.makeText(this, R.string.gsy_cast_no_source, Toast.LENGTH_SHORT).show()
      return
    }

    // ⚠️ 暂停 / 恢复必须**成对�? View �?**（`onVideoPause` / `onVideoResume`）�?
    //  之前是底�? `GSYVideoManager.instance().pause()` �? View �? `onVideoResume()`�?
    //  两套混用会把 GSY 的状态机搞乱：`mCurrentState` 与内核实际状态不一致，
    //  于是关掉面板后播�? / 暂停键点了都没反应（用户「暂停不了也恢复不了」）�?
    //  记下「是我们暂停的」：关面板时只恢复我们自己按下去的这一次�?
    pausedForCastSheet = GSYVideoManager.instance().isPlaying
    if (pausedForCastSheet) activePlayer?.onVideoPause()
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

  /**
   * 面板本体：DlnaSheet 自己会在关闭时通知 manager，我们只管把覆盖层摘掉�?
   *
   * ⚠️ `showSheet` 这一�?**不能�?**�?
   *  `DlnaSheet` 里的 `ModalBottomSheet` 是渲染到**独立窗口**（Popup / Dialog）的�?
   *  不是画在我们这层 ComposeView 里。如果只 `removeView` �? ComposeView 摘掉�?
   *  那个独立窗口并不会跟着消失 —�? 它全屏且 focusable�?
   *  �?**继续吞掉所有触摸事件和返回�?**�?
   *  表现就是「抽屉看着关了，但播放器点哪都没反应、返回键也退不出去」，也就是卡死�?
   *
   *  所以顺序必须是：先�? sheet �? composition 里退出（它自�? dismiss 掉那个窗口）�?
   *  再摘 ComposeView�?
   */
  @Composable
  private fun CastOverlayContent() {
    var showSheet by remember { mutableStateOf(true) }
    MpvexTheme {
      if (showSheet) {
        DlnaSheet(
          onDismissRequest = {
            showSheet = false
            hideCastSheet()
          },
        )
      }
    }
  }

  private fun hideCastSheet() {
    val overlay = castOverlay ?: return
    // 先摘引用：即便后面的 post 没跑成，返回键兜底也不会再指向一个已经废掉的覆盖�?
    castOverlay = null

    // 关掉面板就把「为投屏暂停」的播放恢复回去 —�?
    // 不恢复的话画面是静止的，用户看到的就是「关了抽屉，播放器卡住了」�?
    if (pausedForCastSheet) {
      pausedForCastSheet = false
      runCatching { activePlayer?.onVideoResume() }
    }

    overlay.post {
      // dispose 是最后的保险：它会强制结�? composition �? dismiss 掉所有残存的
      // Popup / Dialog 窗口，避免「摘�? View 但窗口还在」把整个页面锁住�?
      overlay.disposeComposition()
      (overlay.parent as? ViewGroup)?.removeView(overlay)
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 弹幕（清单 b6）
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把弹幕叠层挂到**内容根布局**（`android.R.id.content`）上，整页常驻。
   *
   * 为什么不挂进播放器内部：GSY 的 window 全屏是把克隆容器
   * `addView` 到 content 的**末尾**（对过 `GSYBaseVideoPlayer.startWindowFullscreen`
   * 源码）—— 挂在播放器里会被整块盖住；挂 content 再在每次配克隆时
   * [bringToFront] 一次（见 [configureClone]），全屏里也压在播放器之上。
   * 退出全屏时 GSY 摘掉克隆容器，本层留在原地，无需额外处理。
   *
   * 触摸**不拦截**：`DanmakuView.onTouchEvent` 只转交自己的手势助手、
   * 没有 clickable listener 时 `super.onTouchEvent` 返回 false
   * （对过 DanmakuFlameMaster 源码）⇒ 事件继续往下传到 GSY 播放器，
   * 单击显隐控件 / 双击快进 / 横划调亮度音量全都不受影响 ——
   * 与 mpv 播放页「弹幕叠层声明在控件之后」是同一个叠层顺序。
   */
  private fun mountDanmakuOverlay() {
    if (danmakuOverlay != null) return
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val overlay =
      ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { DanmakuOverlayContent() }
      }
    danmakuOverlay = overlay
    root.addView(
      overlay,
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  /**
   * 弹幕覆盖层本体：复刻 mpv 播放页 `PlayerActivity` 里的挂法。
   *
   * 与 mpv 页的差异只在**进度与播放态的来源**：那边收
   * `viewModel.precisePosition` / `PlayerLib.propBoolean["pause"]`，
   * 这边 GSY 没有属性流，就在叠层里按 250ms 自己轮询
   * （与 [DanmakuOverlay] 内部 `DanmakuPositionSync` 同频）。
   * 取「当前真正在播的那个实例」—— 全屏时是 GSY 克隆实例，见 [activePlayer]。
   *
   * 开关关着 / 没有弹幕数据时整块不渲染：连 DanmakuView 都不建，
   * 也就没有绘制线程；轮询也一并停掉（key 用 [DanmakuRenderParams.enabled]）。
   */
  @Composable
  private fun DanmakuOverlayContent() {
    val state = danmakuState ?: return
    // ⚠️ 与 mpv 播放页同一处警告：这里必须用 Compose 自带的 `collectAsState()`
    //    （无参、收初始值），不是 PlayerViewModel 里那个要传 scope 的同名自定义扩展。
    val comments by state.comments.collectAsState(emptyList())
    val dmKey by state.episodeKey.collectAsState("")
    val dmPrefs by state.renderParams.collectAsState(DanmakuRenderParams())

    var dmPos by remember { mutableFloatStateOf(0f) }
    var dmPlaying by remember { mutableStateOf(false) }
    // 关着就不轮询（enabled 变 true 时循环重启，初始位置 250ms 内就校正过来）
    LaunchedEffect(dmPrefs.enabled) {
      if (!dmPrefs.enabled) return@LaunchedEffect
      while (true) {
        val player = activePlayer
        if (player == null) {
          dmPlaying = false
        } else {
          // getCurrentPositionWhenPlaying：播放/暂停/缓冲态问 manager 要实时位置，
          // 暂停时 manager 停在暂停点（对过 GSYVideoView 源码）。
          dmPos = player.currentPositionWhenPlaying / 1000f
          val playState = player.currentState
          dmPlaying =
            playState == GSYVideoView.CURRENT_STATE_PLAYING ||
              playState == GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START
        }
        delay(250)
      }
    }

    if (dmPrefs.enabled && comments.isNotEmpty()) {
      DanmakuOverlay(
        episodeKey = dmKey,
        positionSec = dmPos,
        playing = dmPlaying,
        comments = comments,
        alphaPercent = dmPrefs.alphaPercent,
        fontScale = dmPrefs.fontScale,
        speedScale = dmPrefs.speedScale,
        areaRatio = dmPrefs.areaRatio,
        maxOnScreen = dmPrefs.maxOnScreen,
        preventOverlapping = dmPrefs.preventOverlapping,
        // 竖屏适配 —— GSY 的 AspectTextureView 同样居中留黑边，
        // 弹幕层按视频宽高比约束到画面矩形内（拿不到尺寸时回退全屏）。
        videoAspectProvider = {
          val p = activePlayer
          val w = p?.currentVideoWidth ?: 0
          val h = p?.currentVideoHeight ?: 0
          if (w > 0 && h > 0) w.toFloat() / h else null
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // Emby 收藏
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 顶栏的收藏键�?
   *
   * 挂的�? mpv 播放页那�? [EmbyFavoritePlayerButton]（弹�? + 星光 + 红心渐变完全一致）�?
   * 状态来�? `EmbyPlayerActions` 单例：[readSession] 里已�? `bind` 过本次播放的 server/item�?
   * �? Emby 播放时它自己会渲染成空�?
   *
   * **必须按实例调�?**：GSY 进全屏时克隆出的那个实例是另一棵视图树，它自己�?
   * `gsy_btn_favorite` 是个全新的空 ComposeView，不补挂就看不到收藏键�?
   */
  private fun setUpFavoriteButton(host: View) {
    val compose = host.findViewById<ComposeView>(R.id.gsy_btn_favorite) ?: return
    compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    compose.setContent { FavoriteButtonContent() }
  }

  @Composable
  private fun FavoriteButtonContent() {
    MpvexTheme { EmbyFavoritePlayerButton(hideBackground = true, buttonSize = 32.dp) }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 系统�? / 安全�?
  // ────────────────────────────────────────────────────────────────────────

  /** 状态栏 / 导航栏的显示与配色：黑底播放页统一用浅色图�? */
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
   * 状态栏 / 导航栏安全区�?
   *
   * GSY 的布局里顶栏和底栏都是「贴屏幕上下沿」的（它的状态机只认 id、不管位置）�?
   * 边到边之后返回键会顶到刘�? / 挖孔下面、进度条会压在手势条上，所以在这里�? insets �? padding�?
   *
   *  · `layout_top`   �? 状态栏高度（避让刘�? / 挖孔�?
   *  · `layout_bottom` / `bottom_progressbar` �? 导航栏高度（避让手势�? / 三大金刚�?
   *
   * 关掉「显示系统状态栏 / 导航栏」时对应 insets 本来就是 0，padding 自然归零 —�? 不需要额外分支�?
   */
  private fun applySafeArea(view: View) {
    val topBar = view.findViewById<View>(R.id.layout_top) ?: return
    val bottomBar = view.findViewById<View>(R.id.layout_bottom)
    val bottomProgress = view.findViewById<View>(R.id.bottom_progressbar)

    // 记下 XML 里写�? padding，重复派�? insets 时不会越加越�?
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
   * 交给 GSY 的地址�?
   *
   * mpv 是自己读文件描述符的，GSY 读不�? SAF �? `content://`，所以这里先试着�?
   * content URI 解析成真实文件路径（�? [PlayerActivity] 同一�? `Utils.findRealPath`），
   * 解析不到就原样把 URI 交出�? —�? GSY �? IJK / Exo2 内核自己能处�? content URI�?
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
   * 网络流请求头 �? GSY �? `Map<String, String>`�?
   *
   * 格式�? mpv �? `headers` extra 完全一致（�? 0/1 位可�? User-Agent，其后两两成对）�?
   * 这样 mpv 切过来时 Referer / UA 不会丢，腾讯视频这类校验 Referer 的源�? GSY 下也能播�?
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
    // 进画中画时系统也会走 onPause —�? 但小窗的全部意义就是继续播，
    // 所以按官方指引排除这个状态，否则小窗里只剩一张停住的画面�?
    if (!isInPictureInPictureMode) player?.onVideoPause()
    // 离开页面就停掉重力感应监听（13.x �? pause()/resume() 改成了这个开关）
    orientationUtils?.setIsPause(true)
    // 后台不需要刷网速；注意�? remove �? post，避�? onResume 时双�?
    netSpeedHandler.removeCallbacks(netSpeedTicker)
  }

  override fun onResume() {
    super.onResume()
    // 同理：小窗被系统重新“唤起”时不要反过来去恢复播放，会把小窗的状态搞�?
    if (!isInPictureInPictureMode) player?.onVideoResume()
    orientationUtils?.setIsPause(false)
    pipHelper?.updateParams()
    netSpeedHandler.removeCallbacks(netSpeedTicker)
    netSpeedHandler.post(netSpeedTicker)
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    pipHelper?.onPictureInPictureModeChanged(isInPictureInPictureMode)
    // 小窗里只留画面：两棵视图树（竖屏实例 + 全屏克隆实例）都要收控件
    player?.setPipMode(isInPictureInPictureMode)
    fullscreenPlayer?.takeIf { it !== player }?.setPipMode(isInPictureInPictureMode)
  }

  override fun onDestroy() {
    super.onDestroy()
    // 退出时**同步**清掉投屏覆盖层：`onDestroy` 之后 post 出去的活儿已经没有意义了�?
    // �? composition �? dispose 的话，ModalBottomSheet 那个独立窗口会一直挂在窗口管理器上�?
    // 同时把「为投屏暂停」标记清�? —�? 页面都要没了，不该再去恢复播放�?
    pausedForCastSheet = false
    castOverlay?.disposeComposition()
    hideCastSheet()
    // 弹幕：取消在途的匹配请求 + 销毁覆盖层 composition。
    // DanmakuView 的绘制线程（常驻 HandlerThread）由 DanmakuOverlay 的
    // DisposableEffect 负责 release —— 不 dispose 就是每进一次播放页漏一个线程。
    danmakuState?.dispose()
    danmakuState = null
    danmakuOverlay?.disposeComposition()
    (danmakuOverlay?.parent as? ViewGroup)?.removeView(danmakuOverlay)
    danmakuOverlay = null
    netSpeedHandler.removeCallbacks(netSpeedTicker)
    pipHelper?.release()
    pipHelper = null
    orientationUtils?.releaseListener()
    orientationUtils = null
    GSYVideoManager.releaseAllVideos()
    // 收藏态是进程内单例，退出播放页要交还，否则会带到下一个播放页
    EmbyPlayerActions.release()
    // 同理：「不喜欢」的当前条目也必须复位，否则下次进页面会先闪一下上一条的状�?
    DislikePlayerActions.clear()
    player = null
    fullscreenPlayer = null
  }

  // ────────────────────────────────────────────────────────────────────────
  // 网络加载速度指示�?
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把当前网速写进顶栏的 [R.id.gsy_net_speed]�?
   *
   * 数据来源：GSY �? `getNetSpeed()`（字�?/秒）—�?
   *  · IJK 内核（默认）：`IjkMediaPlayer.getTcpSpeed()`，播放器自身 TCP 连接的读取速度，准确；
   *  · Exo2 内核：TrafficStats 按进程接收增量估算的近似值（GSY 官方实现）�?
   *
   * 显示策略（与 mpv 播放页对齐）�?
   *  · 非网络流（本地文�? / content Uri）：TextView 整个隐藏 —�? 本地播放没有「网速」可言�?
   *  · 暂停：归零显�? 0 KB/s。暂停时 GSY 的进度定时器停转、内核读数冻结，
   *    保留旧值会让人误以为还在下载，归零最诚实�?
   *  · 缓冲结束 / 缓冲已满：内核读数自然回落到 0，不做额外干预；
   *  · 竖屏实例与全屏克隆实例都挂在 [activePlayer] 上取，切全屏不丢刷新目标�?
   *
   * TextView 跟着顶栏（layout_top）一起显隐：GSY 的控件状态机管顶栏，
   * 我们只负责「顶栏在的时候这行字是对的」�?
   */
  private fun updateNetSpeedView() {
    val view = activePlayer ?: return
    val speedView = view.findViewById<TextView>(R.id.gsy_net_speed) ?: return

    val uri = currentUri
    val isNetworkStream = uri != null && (uri.scheme == "http" || uri.scheme == "https")
    if (!isNetworkStream) {
      speedView.visibility = View.GONE
      return
    }

    val paused = view.currentState == GSYVideoView.CURRENT_STATE_PAUSE
    val raw = if (paused) {
      0L
    } else {
      // GSY 内部没起播放时返�? 0，直接显�? 0 KB/s 也符合「没有在下载」的事实
      val kernelSpeed = runCatching { view.getNetSpeed() }.getOrDefault(0L)
      if (kernelSpeed > 0) kernelSpeed else sampleRxBytesFallback()
    }
    val speed = netSpeedSmoother.smooth(raw)

    speedView.visibility = View.VISIBLE
    // 只写数值 + 单位：单位自带语义（MB/s），顶栏本来就没多余宽度再放前缀。
    // 早先这里有个单字前缀，源码编码损坏后已无法确认原字，直接去掉。
    speedView.text = formatNetworkSpeed(speed)
  }

  /**
   * TrafficStats 兜底：内核自报的网速（IJK �? tcp_speed / Exo2 的估算）在部分设备�?
   * 或走了本地代理缓存（ProxyCacheManager）的路径上会恒为 0 —�? 用户「一�? 0 B/s」�?
   * 此时改用**进程级接收字节增�?**近似当前下载速度：正在播放时视频流就是本 App �?
   * 主要流量，误差可接受；缓冲已�? / 停止下载时增量自然归 0，语义一致�?
   */
  private fun sampleRxBytesFallback(): Long {
    val total = android.net.TrafficStats.getTotalRxBytes()
    // 设备不支持时返回 UNSUPPORTED(-1)，只能保�? 0
    if (total == android.net.TrafficStats.UNSUPPORTED.toLong()) return 0L
    val now = android.os.SystemClock.elapsedRealtime()
    val prevBytes = lastTotalRxBytes
    val prevTs = lastRxTimestampMs
    lastTotalRxBytes = total
    lastRxTimestampMs = now
    if (prevBytes < 0 || prevTs == 0L) return 0L
    val delta = total - prevBytes
    if (delta <= 0) return 0L
    val dt = (now - prevTs).coerceAtLeast(1L)
    return delta * 1000 / dt
  }

  /**
   * 页面声明�? `configChanges="orientation|screenSize|..."`，旋转时系统**不会重建 Activity**�?
   * 全屏的进出由这里串行驱动（等价于 GSY `onConfigurationChanged` 的两个分支，但加了守卫）�?
   *
   *  · 转横屏且当前没有全屏克隆 �? 直接在小屏实例上�? `startWindowFullscreen`
   *    （GSY 横屏分支就这一句，行为等价；入口收敛到「永远只克隆小屏实例」，
   *    杜绝对着退场中的克隆再克隆一次）。过渡动画必须关：此刻窗口刚转成横屏�?
   *    小屏实例还带着竖屏的旧几何，GSY 的动画分支会按这份过期矩形摆克隆�?
   *    300ms 后再展开 —�? 就是「画面塞在角落、周围黑屏、然后恢复」的来源�?
   *  · 转回竖屏且全屏在�? �? [requestExitFullscreen]（幂等）；竖屏视�? + 「竖屏视�?
   *    自动竖屏全屏」时�? GSY 原语义保留竖屏全屏不退�?
   *  · 其余情况（退出在�? / 已在全屏 / 画中画里）一律不�? —�? �? [fullBusy] 的说明�?
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isInPictureInPictureMode || fullBusy) return

    if (landscape) {
      if (fullscreenPlayer == null) enterLandscapeFullscreen()
    } else {
      val view = player
      if (fullscreenPlayer != null && view != null && !view.isVerticalFullByVideoSize()) {
        requestExitFullscreen()
      }
      // GSY 竖屏分支的另一半：回到竖屏后把方向交还给重力感�?
      orientationUtils?.setEnable(true)
      // 转回竖屏的当下就按用户设置恢复系统栏。全屏期�? GSY 把小白条（手势导航条�?
      // 收掉了，它自己的恢复要等「退出收尾」那�? post 任务落地（比旋转晚最�? ~300ms），
      // 小白条迟到会让画面按�? insets 再缩一�? —�? 就是「先没小白条、再出现、闪一下」�?
      // 提前到旋转发生的那一刻恢复，让导航条和竖屏布局同帧就位（GSY 之后�?
      // showNavKey 是幂等的显示调用，无冲突）�?
      applySystemBars()
    }

    // GSY 可能刚做完进出全屏（克隆是另一棵视图树），标题落位两棵树都要重�?
    player?.let { applyTitlePlacement(it) }
    fullscreenPlayer?.takeIf { it !== player }?.let { applyTitlePlacement(it) }
  }

  /**
   * 进入横屏全屏。只�? [fullBusy] �? false 且没有克隆在场时调用�?
   *
   * 刻意绕开 GSY �? `resolveByClick()`（见 [toggleLandscapeFullscreen]），
   * 也直接调官方 `startWindowFullscreen` 而不是转发配置变�? —�? 二者内部等价，
   * 但这里能拿到返回值同步做 [configureClone]，不依赖 300ms 后才来的 onEnterFullscreen
   * （那个回调在非动画分支下还会把小屏实例误当成克隆实例，见 installCallbacks 里的注释）�?
   */
  private fun enterLandscapeFullscreen() {
    val view = player ?: return
    // 旋转驱动的进入不走过渡动画（此刻几何是过期的竖屏值，动画必然「塞在角落」）
    view.setShowFullAnimation(false)
    val full = view.startWindowFullscreen(this, false, prefs.fullHideStatusBar.get())
    // 小屏实例的动画设置还回去（「竖屏视频自动竖屏全屏」那条路还要用它�?
    view.setShowFullAnimation(prefs.showFullAnimation.get())
    (full as? CineIsleGsyPlayer)?.let { clone ->
      // 克隆实例保持关闭：它退出全屏的收缩动画用的也是同一份过期矩�?
      clone.setShowFullAnimation(false)
      configureClone(clone)
    }
  }

  /**
   * 发起一次全屏退出（幂等）。真正落地时 onQuitFullscreen �? [handleFullExited] 会清�? [fullBusy]�?
   *
   * @return 是否真的发起了一次退出（已在退出中 / 没有全屏在场时返�? false�?
   */
  private fun requestExitFullscreen(): Boolean {
    if (fullscreenPlayer == null || fullBusy) return false
    fullBusy = true
    // 退出期间把小屏实例的动画标志临时关掉：GSY �? clearFullscreenLayout �?
    // `if (!mShowFullAnimation) delay = 0` —�? 不关的话退出要固定�? 300ms�?
    // 全屏克隆会撑过整个旋转过程、窗口都转完了才瞬间换视图，就是那次轻微闪屏�?
    // 立即退出则让竖屏小屏视图直接顶上，跟系统旋转动画一起自然过渡�?
    player?.setShowFullAnimation(false)
    val exited = GSYVideoManager.backFromWindowFull(this)
    player?.setShowFullAnimation(prefs.showFullAnimation.get())
    if (!exited) fullBusy = false
    return exited
  }

  /**
   * 全屏退出的统一收尾（onQuitFullscreen 回调，两份回调实例都汇到这里）�?
   * 退出落地时窗口若停在横屏（快速旋转时退出比方向变化慢半拍），把横屏全屏补回来�?
   */
  private fun handleFullExited() {
    fullscreenPlayer = null
    fullBusy = false
    // 退出全�? = 这次「手动强制横屏」结束，把「竖屏视频自动竖屏全屏」还给用户设�?
    forcedLandscape = false
    player?.let {
      applyAutoFullWithSize(it)
      applyTitlePlacement(it)
    }
    if (
      !isFinishing &&
        !isDestroyed &&
        !isInPictureInPictureMode &&
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    ) {
      enterLandscapeFullscreen()
    }
  }

  companion object {
    /** 播放地址的备�? extra（官方只�? intent.data�? */
    private const val EXTRA_URL = "gsy_url"

    /** 标题的备�? extra */
    private const val EXTRA_TITLE = "gsy_title"

    /** 重建播放页时强制�? position 续播（不受「续播到上次位置」开关影响） */
    private const val EXTRA_RESTORE_POSITION = "gsy_restore_position"

    /** 解码失败降级时，重建播放页要带上「本次强制用哪个内核」（�? = [GsyKernelKind].name�? */
    private const val EXTRA_FORCE_KERNEL = "gsy_force_kernel"

    /** 字幕延迟一次调整的步长 */
    private const val SUBTITLE_STEP_MS = 500L

    /**
     * 字幕字号档位�?**mpv 侧原始刻�?**，值域 0~200，默�? 55）�?
     *
     * 存的是这一侧的值而不是换算后�? sp —�? 换算只在显示时做（[GsySubtitleStyleMapper.scaledFontSizeSp]），
     * 这样同一份偏好在 mpv 侧也是对的，用户切内核看到的字号一致�?
     *
     * 档位�? 12 / 20 / 30 / 40 / 55 / 70 / 100：低档给「手机小�? + 字幕多」的场景�?
     * 高档给「投�? / 大屏 + 字幕少」的场景，两头都要能到�?
     */
    private val FONT_SIZE_STEPS = listOf(12, 20, 30, 40, 55, 70, 100)

    /**
     * 字幕阴影档位（存 `sub_border_size`，mpv 侧叫「描边大小」，值域 0~25，默�? 3）�?
     *
     * GSY 没有描边只有阴影，所以这个值当阴影半径用（�? [GsySubtitleStyleMapper.shadowRadius]）�?
     * 0 = 不要阴影（不是「零半径的阴影」，那仍然会画一层模糊）�?
     * 所�? 0 单独一档、其余按 2 递增�?
     */
    private val SHADOW_SIZE_STEPS = listOf(0, 2, 3, 5, 8, 12)

    /**
     * 字幕色板（`argb`）�?
     *
     * 全不透明 —�? 字幕文字色做半透明只会让字变灰、看着像发虚，没别的用处�?
     * 前两个（白 / 黑）覆盖绝大多数场景，其余用来适配不同背景亮度。
     */
    private val SUBTITLE_COLOR_PALETTE =
      intArrayOf(
        0xFFFFFFFF.toInt(), // 白
        0xFF000000.toInt(), // 黑
        0xFFFFE03B.toInt(), // 黄
        0xFF4FC3F7.toInt(), // 天蓝
        0xFF81C784.toInt(), // 绿
        0xFFFF8A80.toInt(), // 浅红
        0xFFE0E0E0.toInt(), // 浅灰
      )

    /**
     * 字幕对齐的三个选项�?
     *
     * ⚠️ 刻意**不含** [SubtitleJustification.Auto]：GSY �? `Auto` 会被映射成居�?
     * （[GsySubtitleStyleMapper.gravity]），给一个「选了看不出区别」的选项是骗人�?
     * 想回默认走「重置」�?
     */
    private val ALIGNMENT_OPTIONS =
      listOf(SubtitleJustification.Left, SubtitleJustification.Center, SubtitleJustification.Right)

    /** 探测字幕编码时最多读多少字节 */
    private const val CHARSET_PROBE_BYTES = 64 * 1024

    /** 网速指示器的刷新周期：1 秒一拍，�? mpv 播放页保持一�? */
    private const val NET_SPEED_POLL_INTERVAL_MS = 1_000L

    /**
     * 构造一次「用 GSY 播放」的 intent�?
     *
     * 地址统一放在 data 上（官方 `setUp` 读的就是它），另外存一份字符串便于
     * 从播放列�? / Emby 这类不走 Uri 的来源直接起播�?
     *
     * @param positionMs 续播位置（毫秒）�?0 = 从头�?
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
