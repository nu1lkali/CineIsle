package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuOverlay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import kotlin.math.ceil
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.StreamProbe
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
import app.marlboroadvance.mpvex.ui.player.danmaku.DanmakuRenderParams
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.utils.storage.FileFilterUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.android.inject
import java.io.File

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
open class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.marlboroadvance.mpvex.database.repository.PlaylistRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /** 本次播放使用的内核 */
  private var engineKind: EngineKind = EngineKind.MPV

  /**
   * 本次播放用哪个内核。
   *
   * [GsyPlayerActivity] 会重写它直接返回 [EngineKind.GSY] ——
   * 「长按用备用内核播」走的就是那个 Activity，这样即便备用内核出问题，
   * 默认的 mpv 页面也完全不受影响。
   */
  protected open fun resolveEngineKind(preferred: String): EngineKind = EngineKind.from(preferred)

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /** 弹幕偏好（开关 / 字号 / 速度 / 透明度 / 密度 / 屏蔽词 / 弹幕源）。 */
  private val danmakuPreferences: app.marlboroadvance.mpvex.preferences.DanmakuPreferences by inject()

  /** Emby 仓库（片头片尾分段的精准数据源；非 Emby 播放用不到） */
  private val embyRepository: app.marlboroadvance.mpvex.domain.emby.EmbyRepository by inject()

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName = ""

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""

  /**
   * 弹幕状态（加载 / 匹配 / 过滤）。
   *
   * 只在 mpv 内核的播放页存在；GSY 播放页与视界流不接（弹幕要跟随 mpv 的
   * `time-pos`，换内核要重新对接一遍）。
   * 字段用 `by lazy` + nullable：弹幕默认是关的，不该在 onCreate 里就建协程作用域。
   */
  private var danmakuState: app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState? = null

  /**
   * 弹幕状态访问器（给 MoreSheet / PlayerSheets 用）。
   *
   * 沿用 MoreSheet 里已有的做法：那几处已经在直接从宿主 Activity 取 Emby 上下文
   * （`currentEmbyItemId()` 等），弹幕也照抄这一套，不再给那条已经很长的参数表加参数。
   */
  internal fun danmakuState(): app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState? = danmakuState

  /**
   * 「从头播放」一次性标记。
   *
   * Emby 详情页的「从头播放」会带 `play_from_start=true` 发起播放。置位后，
   * [applyPlaybackState] 不再把本地续播记录里的 lastPosition 套回 `time-pos`
   * —— 否则「从头播放」会被本地续播覆盖成「继续播放」。
   *
   * 只对本次 intent 的第一个视频生效（[loadVideoPlaybackState] 读出后立刻清掉），
   * 这样剧集连播时后面的剧集仍然能恢复各自的观看进度。
   */
  private var playFromStartOnce = false

  /**
   * 「整场从头播放」标记（随机播放入口专用）。
   *
   * 「随机播放 / 随机播放收藏」里可能混着已经看完的剧：如果切到下一个视频时
   * 恢复本地续播记录，会直接跳到片尾一秒就结束，观感极差。所以这两个入口
   * 带 `play_from_start_all=true`，让**本次播放会话里的每一个视频**都从头放。
   *
   * 与 [playFromStartOnce] 的区别：它在整个会话内持续生效（读的时候不清零），
   * 直到下一个 intent 重新赋值（随机入口必然带 true，其它入口必然是 false）。
   */
  private var playFromStartAllSession = false

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * 与 [playlist] 下标一一对应的显示标题。
   *
   * 网络流（尤其 Emby 的 `/Videos/{id}/stream`）URL 末段没有可用片名，
   * 只能从 URL 猜 → 猜出来是 "stream"。发起播放的一方知道真实片名，
   * 通过 intent extra `playlist_titles` 传进来，切集时优先用它。
   */
  private var playlistTitles: List<String> = emptyList()

  /**
   * 「每部剧记住播放设置」用的键，与 playlist 下标一一对应（Emby 剧集是 SeriesId）。
   *
   * 单文件播放不走列表，用 [singleSeriesKey]；
   * 列表播放按下标取，切集时键随之换成新一集所属的剧。
   * 本地播放（非 Emby）两个都是空，记忆逻辑整体跳过。
   */
  private var playlistSeriesKeys: List<String> = emptyList()
  private var singleSeriesKey: String? = null

  /**
   * 与 [playlistSeriesKeys] 平行的「**剧名**」（Emby 的 `SeriesName`）。
   *
   * ⚠️ 弹幕自动匹配只能拿剧名去搜番。原因是：点第 5 集进来时 title 是
   * 「都市传说」这种**分集名**，拿它去查 dandanplay 是查不到结果的；
   * 要查的是整部剧的名（「某科学的超电磁炮」）。电影这类非剧集条目没有剧名
   * ⇒ 空串 ⇒ 调用方退回用显示名。
   */
  private var playlistSeriesNames: List<String> = emptyList()
  private var singleSeriesName: String? = null

  /**
   * 交接单里带来的「当前亮度」（0~1），由 [onStart] 消费一次即清空。
   *
   * 为什么要有这一份：`onStart` 会按记忆重设亮度，但记忆里可能**根本没有这部片**
   * （用户从没在 mpv 侧调过亮度、刚在 GSY 侧调暗后切回来），那种情况下只有
   * 交接单里的值才是正确的。不消费它是「切内核亮度跳一下」的根因。
   */
  private var handoffBrightness: Float? = null

  /**
   * 与 [playlist] 同序的「多版本名」（空串 = 这一条没有多版本 / 没选版本）。
   *
   * 只在 Emby 多版本条目上才有值（用户在下拉框里选了哪个版本），顶栏会把它追加在片名后面。
   * 与 `playlistTitles` / `playlistSeriesKeys` 一样是**平行数组** —— 队列换位 / 删除时必须同步。
   */
  private var playlistMediaSourceNames: List<String> = emptyList()

  /**
   * 与 [playlist] 同序的 Emby **ItemId**（非 Emby 播放为空表）。
   *
   * 为什么既留「路径反查」（[currentEmbyItemId]）又要这个平行数组：
   * 开了「预解析直链」后 mpv 的 `path` 是 302 之后的真实地址（网盘域名 / ISO 本地代理），
   * 里面根本没有 `/Videos/{id}/stream` 可反查 —— 那时只能按下标取。
   * 与 `playlistTitles` / `playlistMediaSourceNames` 一样是**平行数组**，
   * 队列换位 / 删除时必须同步（见 `movePlaylistItem` / `removePlaylistItem`）。
   */
  private var playlistItemIds: List<String> = emptyList()

  /**
   * 与 [playlist] 同序的 Emby 剧集原始序号（`"2005"` = S02E05；空串 = 不是剧集）。
   *
   * ⚠️ 只给**弹幕匹配**用：弹幕库的集号是「第几集」，而 [playlistIndex] 是「队列里的第几个」。
   * 从第 5 集点进详情页播放时队列只有 1 条，按下标算会取成第 1 集的弹幕 ——
   * 用户看到的现象就是「弹幕和画面完全对不上」。
   *
   * 与 [playlistItemIds] 一样是平行数组，所以队列换位 / 删除时必须同步
   * （见 `movePlaylistItem` / `removePlaylistItem`）。
   */
  private var playlistEpisodeNumbers: List<String> = emptyList()

  /**
   * Emby **ItemId → 用户为该条选定的媒体源 Id**（多版本条目才有；单版本 / 非 Emby 为空表）。
   *
   * 为什么是 Map 而不是又一个平行数组：它的读取方是 [saveVideoPlaybackState]，
   * 靠「从 mpv 路径反查出的 ItemId」来查（队列换位后下标会与 intent 的原始顺序错位，
   * 用下标索引必错）。Map 按 Id 查，与队列顺序完全无关，换位 / 删除都不需要同步。
   */
  private var embyMediaSourceByItemId: Map<String, String> = emptyMap()

  /**
   * 视频预加载：对下一集的流地址做 HTTP Range 预取的协程。
   *
   * 目标是在当前视频起播 [PlayerPreferences.PRELOAD_TRIGGER_SECONDS] 秒后，
   * 提前把下一集开头一段数据拉过来（预热 DNS / 连接 / 服务端转码会话），
   * 用户切集时更无缝。受「视频预加载」偏好开关控制。
   */
  private var preloadJob: Job? = null

  /**
   * 已经为哪个 playlistIndex 发起过预加载。
   *
   * 用下标（而不是 URI）做去重键：切集时下标必变，保证每集只预加载一次；
   * -1 表示还没预加载过。
   */
  private var preloadRequestedForIndex: Int = -1

  /**
   * 预加载专用的 HTTP 客户端：超时压得很短，失败也不影响正常播放。
   *
   * ⚠️ **故意不并入 [EmbyHttp]共享池**：这里要的就是「5秒连不上立刻放弃」——
   * 预加载是后台锦上添花，绝不能占着共享连接池里的连接等 15 秒超时，
   * 把正在播的正片挤掉。独立的短超时客户端反而是这个场景的正确选择。
   */
  private val preloadHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  }

  /**
   * Current index in the playlist.
   *
   * 用 `mutableStateOf` 暴露给 Compose：切集（playNext/playPrevious）改的是这个值，
   * 但上一集/下一集按钮的 enabled、悬浮切换件的显隐都是**组合期**读 `hasPrevious()/
   * hasNext()` 算出来的 —— 若它是普通 var，值变了不会触发重组，按钮就「卡」在旧状态
   * （横屏尤其明显：切了下一集后上一集按钮仍是灰色、点不动）。改成可观察状态后，
   * 位置一变 UI 立即刷新。所有非组合场景的读写照常当普通 Int 用，零影响。
   */
  internal var playlistIndex: Int by mutableStateOf(0)

  /**
   * 网络播放队列里每个文件的**原始网络路径**（与 [playlist] 同序同长）。
   *
   * 为什么不能只靠 intent 里那个单值 `network_file_path`：队列连播时换片不会重建
   * intent，单值永远是第一个文件的路径 —— 进度/history 键（见 [getMediaIdentifier]）
   * 与字幕自动加载就会全串到第一片上。有了这份同序表，两者都能按 [playlistIndex]
   * 取当前片的真实路径。非网络队列为空表，行为与从前完全一致。
   */
  private var networkFilePaths: List<String> = emptyList()

  /**
   * Shuffled order of playlist indices (when shuffle is enabled)
   */
  private var shuffledIndices: List<Int> = emptyList()

  /**
   * Current position in shuffled playlist (when shuffle is enabled).
   * 同样做成可观察状态，保证随机模式下上一集/下一集按钮的 enabled 也随位置即时刷新。
   */
  private var shuffledPosition: Int by mutableStateOf(0)

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isManualBackgroundPlayback = false // Track manual background playback trigger
  private var mpvInitialized = false // Track MPV initialization state
  /**
   * 首帧视频尺寸到达后只据此设一次方向；之后切集不再调用 [setOrientation]，
   * 否则「按视频方向」模式下下一集若是竖屏内容会被翻成竖屏，违背用户当前方向。
   */
  private var videoParamsOrientationHandled = false
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * 系统媒体会话（清单 1.5 拆分第 3 刀）：锁屏 / 通知栏 / 蓝牙 / Android Auto 的播放控制。
   *
   * 懒加载：只有真正开始播放、且内核已就绪时才创建，避免冷启动就占系统资源。
   */
  private val mediaSession by lazy {
    PlayerMediaSession(
      appContext = applicationContext,
      onPlay = { viewModel.unpause() },
      onPause = { viewModel.pause() },
      onSeekTo = { posMs -> viewModel.seekTo((posMs / 1000).toInt()) },
      currentPositionMs = { (viewModel.pos ?: 0) * 1000L },
      isPaused = { viewModel.paused ?: false },
    )
  }

  // ==================== Audio Focus ====================

  /**
   * 音频焦点 + 拔耳机处理（清单 1.5 拆分第 2 刀）。
   *
   * 懒加载：它只在 [initializeMpvAndFinishSetup] 走到音频设置那一步时才被创建，
   * 那时 viewModel 已经就绪，可以安全地把 `paused` 传成回调。
   */
  private val audioFocus by lazy {
    PlayerAudioFocus(
      context = this,
      isPaused = { viewModel.paused ?: false },
      onPause = { viewModel.pause() },
      onResume = { viewModel.unpause() },
      onScreenKeepOnChange = { keepOn ->
        if (keepOn) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // ── 默认播放内核是 GSY 时，把这次播放整体转交给 GSY 播放页 ──
    // 必须在任何 mpv 初始化之前判断，转交时连画面都还没建起来，观感上就是直接进 GSY。
    if (redirectToPreferredEngine()) return

    setContentView(binding.root)

    // 弹幕状态：默认关，所以**按需**创建（不建就不会有任何网络请求）。
    danmakuState = app.marlboroadvance.mpvex.ui.player.danmaku.PlayerDanmakuState(
      preferences = danmakuPreferences,
      scope = lifecycleScope,
    )

    // ── 播放内核选择 ──
    // intent 里带 "engine" 的长按反选优先，否则用设置里的默认内核。
    // 必须在任何 PlayerLib 调用之前定下来：门面层靠它决定转发给 mpv 还是 Exo。
    // 本页固定 mpv。备用内核 GSY 走独立的 GsyPlayerActivity（官方默认布局 + 官方三行用法），
    // 两套东西不再共用同一个 Activity，互不干扰。
    engineKind = EngineKind.MPV
    PlayerLib.kind = EngineKind.MPV

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    // ── 内核初始化：必须同步完成，onCreate 返回前 mpv 句柄就要可用 ──
    // ## 这里曾经被改成异步，直接导致「点播放 → 闪退」（清单 #26 的由来）
    // 原来的写法是 `lifecycleScope.launch { withContext(IO) { … } ; setupMPV() }`，
    // 动机是「assets 拷贝 / SAF 读配置是纯 IO，别占住主线程」。但它破坏了一个
    // 硬性前提：**`onCreate` 返回时 mpv 必须已经 create**。
    // 原因在 `MPVView` 的继承链：`BaseMPVView extends SurfaceView`。
    // `setContentView` 一执行，首帧 traversal 就会回调 `surfaceCreated`，
    // 而它的实现是 `MPVLib.attachSurface(holder.surface)` —— 一个 native 调用。
    // AAR 里所有 native 方法操作的句柄都由 `MPVLib.create()` 分配，
    // 在 create 之前调用 = 解引用空指针 = **SIGSEGV**。
    // 这类崩溃在 native 栈里，**JVM 的 try/catch 与 runCatching 都拦不住**，
    // 表现就是整个进程直接消失、且拿不到任何 Java 堆栈。
    // 时序上 IO 段要几百 ms 到几秒（首次进播放页要拷 scripts/shaders/fonts，
    // 还要读 SAF 同步用户自建 mpv 目录），而首帧只要一帧（~16ms）——
    // 异步化之后 `attachSurface` **必然**抢在内核 create 之前跑，100% 复现。
    // 同一窗口里还有第二条路：`onResume → updateVolume() → viewModel` 让
    // `PlayerViewModel` 构造，其字段初始化里的 `PlayerLib.propInt["volume-max"]`
    // 首次访问会触发 native `observeProperty`，同样是死路。
    // ## 现在的做法
    // 同步调用（回到改造前的行为），**但先把 IO 挪出主线程的那部分成果保留**：
    //  ① `Anime4KManager.prepareOnIoThread()` 自带 `.assets_copied` 标记文件的快路径，
    //     冷启动第二次起连 `assets.list()` 都不进；
    // ② [PlayerLib] 的内核就绪门禁作为第二道防线 ——
    //     万一将来又有人挪动初始化时机，最坏也只是「值读不到」而不是「进程崩掉」。
    // 想彻底去掉主线程 IO 的正确解法是**在 Application 启动时预热**拷贝，
    // 而不是把内核 create 推迟到 Activity 里 —— 那是另一件事，不在这里做。
    prepareMpvFilesOnIoThread()
    initializeMpvAndFinishSetup()
  }

  /**
   * mpv 配置文件就绪之后的初始化 + 页面装配（清单 #20 必修 1）。
   *
   * 从 [onCreate] 原样搬出来的部分，因此**整体仍跑在主线程**
   *（[lifecycleScope] 的默认 dispatcher 就是 `Dispatchers.Main.immediate`）。
   *
   * 搬出来而不是只把 `setupMPV()` 异步化，是因为 `player.initialize()` 之后那一整段
   * （`setupAudio` / `setupPlayerControls` / `player.playFile(uri)` …）全都依赖
   * 内核**已经初始化完**这个前提 —— 只把 IO 挪走而把 `player.initialize()` 留在原地，
   * 会让 `playFile` 有机会抢在内核就绪前跑。
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun initializeMpvAndFinishSetup() {
    // NOW initialize MPV - it will find and load the scripts we just copied
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    // 句柄此刻才真正存在，开门禁（清单 #26）。
    // 必须在任何 PlayerLib 调用之前 —— 下面紧接着就是 addObserver / observeProperty。
    PlayerLib.markCoreReady()
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    PlayerLib.addObserver(playerObserver)

    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    // 弹幕层独立成 View 层，声明在 controls 之前（= 控件下面）。
    // 顺序无依赖：danmakuState 在 onCreate 更早处（L510）就建好了。
    mountDanmakuLayer()
    setupPipHelper()
    setupMediaSession()

    // Emby：本次播放来自 Emby 时，把播放进度实时回传给服务器
    // （开始 / 进度 / 暂停 / 切下一集 / 退出）。非 Emby 播放不会注册，无任何副作用。
    runCatching {
      lifecycle.addObserver(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
          .EmbyPlaybackLifecycleObserver(intent),
      )
    }

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)

    // Emby「从头播放」：本次启动的第一个视频不要从本地续播记录恢复进度
    playFromStartOnce = intent.getBooleanExtra("play_from_start", false)
    // 随机播放入口：整场每个视频都从头放（连播切集也不恢复进度）
    playFromStartAllSession = intent.getBooleanExtra("play_from_start_all", false)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // 与 playlist 一一对应的显示标题（由发起方写入，如 Emby 的「S01E05 剧集名」）。
    // 没有这个列表时只能从 URL 末段猜标题 —— Emby 的流地址末段固定是 "stream"，
    // 于是从第二个视频起标题全变成 "stream"（ bug）。有它就优先用它。
    playlistTitles = intent.getStringArrayListExtra("playlist_titles") ?: emptyList()
    // 「记住每部剧的播放设置」的键，与 playlist 下标一一对应
    playlistSeriesKeys = intent.getStringArrayListExtra("playlist_series_keys") ?: emptyList()
    singleSeriesKey = intent.getStringExtra("emby_series_key")
    // ⚠️ 剧名（与 playlist 同序）——**弹幕自动匹配必须用它**，不能用某一集的标题。
    //    搜「都市传说」（分集名）在弹幕库里是搜不到番的，必须搜「某科学的超电磁炮」。
    //    电影/音乐等非剧集条目没有剧名 ⇒ 空串 ⇒ 退回显示名，行为与旧版一致。
    playlistSeriesNames = intent.getStringArrayListExtra("playlist_series_names") ?: emptyList()
    singleSeriesName = intent.getStringExtra("emby_series_name")
    // 交接单带来的亮度（重建 / 首次进入时由 onStart 消费）
    handoffBrightness = PlayerHandoff.read(intent).brightness
    // Emby 多版本名（与 playlist 下标一一对应，空串占位）——顶栏用它显示「正在播的是哪个版本」
    playlistMediaSourceNames =
      intent.getStringArrayListExtra("emby_media_source_names") ?: emptyList()
    // Emby ItemId（与 playlist 同序）—— 路径反查不到时靠它按当前下标取
    playlistItemIds =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
      ) ?: emptyList()
    // 网络队列的原始路径表（与 playlist 同序）—— 换片后按 playlistIndex 取当前片的
    // 网络路径算进度键 / 找字幕，避免队列连播时全串到第一片（见 networkFilePaths 注释）
    networkFilePaths = intent.getStringArrayListExtra("network_file_paths") ?: emptyList()
    // Emby 剧集原始序号（与 playlist 同序，空串 = 不是剧集）—— 弹幕按真实集号匹配，
    // 而不是按队列下标（从第 5 集单独点播时，队列只有 1 条，下标 0 会被当成第 1 集）
    playlistEpisodeNumbers =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS,
      ) ?: emptyList()
    // Emby 多版本：ItemId → 选定的媒体源 Id（按版本记进度的键靠它拼，见 EmbyVersionProgress）
    embyMediaSourceByItemId = buildEmbyMediaSourceMap(intent)
    viewModel.seriesKey = currentSeriesKey()

    // If playlist is empty but playlist_id is provided, load asynchronously from database
    // Load all items - LazyColumn handles pagination/virtualization efficiently
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          // Check if this is an M3U playlist
          val playlistEntity = playlistRepository.getPlaylistById(pid)
          isM3uPlaylist = playlistEntity?.isM3uPlaylist ?: false

          // Load all items - LazyColumn will handle virtualization/pagination efficiently
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          val totalCount = items.size

          withContext(Dispatchers.Main) {
            playlist = items
            playlistWindowOffset = 0
            playlistTotalCount = totalCount
            Log.d(TAG, "Loaded all $totalCount items from playlist $pid (isM3U: $isM3uPlaylist)")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load playlist from database", e)
        }
      }
    }

    // Only auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract fileName early so it's available when video loads
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE playing the file
    setHttpHeadersFromExtras(intent.extras)

    // Guard against opening a local file that was deleted (e.g. externally)
    // before it was launched. Avoids a blank/stuck player with no feedback.
    val initialUri = extractUriFromIntent(intent)
    if (initialUri != null && isLocalFileMissing(initialUri)) {
      viewModel.showToast(getString(app.marlboroadvance.mpvex.R.string.toast_file_no_longer_exists))
      finishAndRemoveTask()
      return
    }

    // 起播前已被 Emby 侧判定为死链（403 / 404 / 登录失效）：
    // 地址**根本没交给 mpv** ⇒ 不会 native 崩溃，直接把原因显示成卡片。
    // 见 EmbyViewModel.play() 里对 StreamProbe.Denied 的处理。
    val preflightError = intent.getStringExtra("play_error")
    if (preflightError != null) {
      viewModel.showPlaybackError(preflightError)
    } else {
      getPlayableUri(intent)?.let { uri -> startPlayback(uri) }
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    }

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }

  /**
   * 把地址直接交给 mpv 起播，**不再探测**。
   *
   * ## 为什么这里不再发探测请求（v128）
   *
   * v127 曾在这里对每条 http(s) 地址发一次 `Range: bytes=0-0`，用来把死链挡在 mpv 之外。
   * 代价是**每次起播多打一次 CDN** —— 对网盘/CDN 来说这就是一条会被风控记下的请求，
   * 而 Emby 侧「预解析直链」本来就会发同样的一次请求（`EmbyClient.resolveStreamUrl`）。
   *
   * 现在判定合并到那次已有的请求里，结论通过 `play_error` extra 带进本页
   * （见 onCreate 里的 preflightError）。所以正常起播路径**零额外请求**。
   *
   * 探测能力没有丢，只是挪到了「重试」—— 见 [retryPlayback]。
   */
  private fun startPlayback(uri: String) {
    player.playFile(uri)
  }

  /**
   * 用户点「重试」：地址可能只是临时过期，重新探一次再播。
   *
   * 这是**用户主动触发**的请求，最多 1 次，不会在正常起播路径上反复发生 ——
   * 这正是「既要能判死链、又不能每次都白打 CDN」的折中点。
   *
   * 判定口径与 [EmbyClient.probeStreamUrl] 一致：只有服务端明确拒绝（4xx）才拦，
   * 超时 / DNS 失败 / 连接重置一律照旧播放，本地网络抖一下不该把用户挡在门外。
   */
  private fun retryPlayback(uri: String) {
    // 本地文件 / content:// / 本地 ISO 代理（127.0.0.1）：不需要探测，直接播
    if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
      player.playFile(uri)
      return
    }
    lifecycleScope.launch {
      val probe = withContext(Dispatchers.IO) { EmbyClient.probeStreamUrl(uri) }
      when (probe) {
        // 用探测跟完 302 后的最终地址：省得内核自己再跳一次，也避免「内核不跟 302」
        is StreamProbe.Ok -> player.playFile(probe.finalUrl)
        // 明确不可播：不调 playFile ⇒ mpv 不会去碰死链 ⇒ 不会 native 崩溃，
        // 改成界面上的一张错误卡片（用户能看到原因，而不是整个 App 消失）。
        is StreamProbe.Denied -> viewModel.showPlaybackError(probe.message)
        StreamProbe.Unknown -> player.playFile(uri)
      }
    }
  }

  /**
   * 默认播放内核是 GSY 时，把这次播放整体转交给 [GsyPlayerActivity]。
   *
   * 全项目有十来处「起播放」的入口（文件浏览、播放列表、网络流、播放历史、外部的
   * `content://` VIEW intent…），逐个去改既容易漏又散；在 mpv 播放页这一处兜住，
   * 所有入口就都服从「设置 → GSY 播放器 → 默认播放内核」。
   *
   * 显式指定了内核时不转交：长按反选、以及从 GSY 页切回 mpv 都会带上 [EXTRA_ENGINE]，
   * 那说明调用方已经决定好要用哪一套了。
   *
   * 交接所需的播放队列 / 标题 / 进度 / Emby 上报信息都在 intent extras 里，
   * [Intent] 拷贝构造会原样带过去（key 与 [PlayerHandoff] 完全一致），
   * 所以 GSY 那边拿到的是一个完整的播放会话，而不是一个孤零零的地址。
   *
   * 「带没带播放内容」要先判一下：播放通知点开（[MediaPlaybackService] 里那个只有
   * CLEAR_TOP 的 intent）、以及本页复用时的一些内部 refresh intent 都不带内容，
   * 对它们转交只会把一个正在好好运行的 mpv 会话踢掉。真正的「起播放」入口
   * （文件浏览 / 播放列表 / 网络流 / Emby / 外部 VIEW intent）无一例外都带 URI。
   *
   * @return true 表示已完成转交并收摊，调用方应当直接返回
   */
  private fun redirectToPreferredEngine(): Boolean {
    // 起播前已被判定为死链：这次**没有可播内容**，别转交。
    // GSY 页不认识 `play_error`，转过去只会拿同一条死链再失败一次，
    // 而错误卡片在本页就能显示 —— 让用户看到原因比换个播放器重要。
    if (intent.getStringExtra("play_error") != null) return false

    if (intent.data == null &&
      !intent.hasExtra("playlist") &&
      !intent.hasExtra("playlist_id")
    ) {
      return false
    }

    val requested = intent.getStringExtra(EXTRA_ENGINE)
    val effective =
      if (requested != null) {
        EngineKind.from(requested)
      } else {
        EngineKind.from(playerPreferences.playbackEngine.get())
      }
    if (effective != EngineKind.GSY) return false

    runCatching {
      startActivity(
        Intent(intent).apply {
          setClass(this@PlayerActivity, GsyPlayerActivity::class.java)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
      )
    }.onFailure { e ->
      Log.e(TAG, "Failed to hand off playback to GsyPlayerActivity", e)
    }
    finish()
    return true
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        val configurationDisplayMetrics = configurationContext.resources.displayMetrics
        @Suppress("DEPRECATION")
        configurationDisplayMetrics.scaledDensity = updatedConfiguration.fontScale * configurationDisplayMetrics.density
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      pipHelper.enterPipMode()
      return
    }

    isUserFinishing = true
    finish()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvexTheme {
        Box(modifier = Modifier.fillMaxSize()) {
          PlayerControls(
            viewModel = viewModel,
            onBackPress = {
              isUserFinishing = true
              finish()
            },
            modifier = Modifier,
          )
          // ── 「出画面前」的加载层 ────────────────────────────────
          // 从进入播放器到首帧真正上屏（MPV_EVENT_PLAYBACK_RESTART）之间，
          // 视频 Surface 是**纯黑**的 —— 原来这里什么都没有，只有一片黑。
          // ⚠️ 关键：它**必须挂在 PlayerControls 之外**。
          //   `PlayerControls` 内部的缓冲转圈被包在
          //   `AnimatedVisibility(visible = controlsShown)` 里（PlayerControls.kt:845），
          //   而 `controlsShown` 的**初值就是 false**（PlayerViewModel.kt:224）
          //   ⇒ 首帧前控件是收着的，那个转圈根本不显示。
          //   放在这里才不受控件显隐影响。
          // 复用播放器的「显示缓冲转圈」开关（`show_loading_circle`）：
          // 语义一致（都是「正在等画面」），而且设置页已经有这一项，不新增开关。
          PlayerFirstFrameLoading(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.Center),
          )

          // 下一集「悬停卡」（片尾前约 30 秒出现，无倒计时）：比倒计时胶囊(136)更高一截，
          // 两者可能同时出现 —— 悬停卡在上、倒计时胶囊在下，各自都能点到。
          NextUpCard(
            viewModel = viewModel,
            onPlayNow = {
              autoplayCancelled = false
              cancelAutoplayCountdown()
              playNext()
            },
            onDismiss = {
              // 收起 = 「本集别再提示了」：本集内不再弹出，本集播完仍按原逻辑自动连播，
              // 与倒计时胶囊上的 ×（那是取消连播）语义**不同**，别合并。
              // ⚠️ 只把 visible 置 false 没用：盯梢每 500ms 复算窗口会重新推开，
              //    必须打上 nextUpDismissed 让它彻底不再弹出（下一集开播时清零）。
              nextUpDismissed = true
              viewModel.nextUpVisible.value = false
              viewModel.nextUpTitle.value = null
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 176.dp),
          )
          // 下一集连播倒计时：贴在底部、抬高一截避开控制栏。
          // 只有倒计时 > 0 时卡片才出现（AnimatedVisibility 内部判断）。
          AutoplayCountdownCard(
            viewModel = viewModel,
            onPlayNow = {
              // 点卡片 = 立刻切下一集（不等片尾放完）
              autoplayCancelled = false
              cancelAutoplayCountdown()
              playNext()
            },
            onCancel = {
              // 取消 = 本集播完停在最后一帧，不再自动连播
              autoplayCancelled = true
              cancelAutoplayCountdown()
            },
            // 右下角，抬高到控制条按钮簇上方：84dp 时正好压在右下控件上，
            // 太高又飘到画面中央 —— 136dp 刚好越过「seekbar + 按钮簇」的总高度
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 136.dp),
          )
          // 左下角悬浮的上一条/下一条：常驻显示（不随控件显隐）。
          // bottom=120dp：比倒计时卡片(136)更低、更贴角，但压在底部控件条(含 seekbar +
          // 按钮簇，顶部约在距底 108dp)上方，控件显示时也不遮挡。
          FloatingPlaylistSwitcher(
            viewModel = viewModel,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(
                start = 14.dp,
                bottom = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 92.dp else 120.dp,
              ),
          )
          // 「跳过片头 / 跳过片尾」小胶囊（b2+a1 手动模式）：右下角，
          // 刻意比连播倒计时卡(136)再高一截 —— 片尾区间里两者可能同时可见
          // （兜底片尾默认 90s，倒计时默认只有几秒），叠在一起会互相盖住。
          SkipSegmentChip(
            viewModel = viewModel,
            onSkip = {
              when (viewModel.skipSegmentLabel.value) {
                "跳过片头" -> introRangeMs?.let { range ->
                  runCatching { PlayerLib.command("seek", (range.second / 1000.0).toString(), "absolute") }
                }
                "跳过片尾" -> {
                  val duration = PlayerLib.getPropertyDouble("duration")
                  if (duration != null && duration > 0) {
                    runCatching { PlayerLib.command("seek", (duration - 0.2).toString(), "absolute") }
                  }
                }
              }
              viewModel.skipSegmentLabel.value = null
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 184.dp),
          )
          // ── 播放失败卡片（声明在最后 = 最上层）──
          // 服务端明确拒绝（401 / 403 / 404）时，由 Emby 侧预解析经
          // `play_error` extra 写入，或「重试」时由 [retryPlayback] 写入 ——
          // 替代原来的「native 崩溃、整个 App 消失」。必须排在所有浮层之后，
          // 否则会被悬停卡 / 倒计时胶囊盖住，用户点不到「重试」。
          PlaybackErrorCard(
            viewModel = viewModel,
            onBack = {
              isUserFinishing = true
              finish()
            },
            onRetry = {
              // 重试 = 清掉错误、重新探测一次（地址可能只是临时过期，重取就好了）
              viewModel.clearPlaybackError()
              getPlayableUri(intent)?.let { uri -> retryPlayback(uri) }
            },
            modifier = Modifier.align(Alignment.Center),
          )
          // 弹幕层**不在这里**了（V118）：它已挪到 `binding.danmaku` 这一独立图层，
          // 声明在 controls **之前**（= 画在它下面、触摸也让给它）。
          // 原因见 [mountDanmakuLayer]。
        }
      }
    }
  }

  /**
   * 把弹幕叠层挂到**它自己的 ComposeView**（`binding.danmaku`）上。
   *
   * ⚠️ V118：这是「点屏幕唤不出控制栏」的根因修复，别再挪回控件那棵 Compose 树里。
   *
   * 之前弹幕叠层是 `binding.controls` 那棵 Compose 树里**最靠后（= 最上层）的全屏
   * `AndroidView`**。Compose 的命中测试里，兄弟节点按 z 序**上面那个先收事件**；
   * 而 `AndroidView` 必挂 `pointerInteropFilter` —— 它会把 ACTION_DOWN 先交给
   * 宿主 View 问一句「你要不要」，一旦那句返回 true，整串手势就被它独占
   * （`suppressMovementConsumption`），排在它后面的 `GestureHandler` 一条都收不到
   * ⇒ 单击永远走不到「显/隐控件」那条分支。
   * 与用户反馈的现象完全对得上：**弹幕还没渲染出来时点屏幕正常，弹幕一出来就点不动了**
   * （弹幕层进入组合的那一刻，DOWN 就被它先截走）。
   *
   * 修法是**改分层，不靠事件传递的运气**：弹幕层变成独立的 View 层，且在 XML 里
   * 声明在 `controls` **之前** ⇒ 在原生 View 体系的 z 序里它**在控件下面**。
   * 于是触摸一定先给上层的 `controls`，手势照常工作；弹幕层只是拿漏下来的那部分，
   * 而它本来就不需要触摸（我们没设 `OnDanmakuClickListener`，弹幕点击功能从没开过）。
   *
   * 顺带把叠放顺序也修正成注释里一直写的那样：**弹幕在画面之上、控制条之下**。
   *
   * 与 GSY 播放页一致 —— 那边弹幕层同样是独立 ComposeView，点击正常。
   */
  private fun mountDanmakuLayer() {
    binding.danmaku.setViewCompositionStrategy(
      androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
    )
    binding.danmaku.setContent {
      MpvexTheme { DanmakuOverlayContent() }
    }
  }

  /**
   * 弹幕叠层本体（跑在 [mountDanmakuLayer] 那棵独立 Compose 树上）。
   *
   * 与 GSY 页的差异只在**进度与播放态的来源**：那边没有属性流，在叠层里自己轮询。
   *
   * 开关关着 / 没有弹幕数据时整块不渲染：连 DanmakuView 都不建，也就没有绘制线程。
   */
  @Composable
  private fun DanmakuOverlayContent() {
    val state = danmakuState ?: return
    // ⚠️ 这里必须用 **Compose 自带的 `collectAsState()`**（无参、返回非空 `State<T>`），
    //   **不是** `PlayerViewModel.kt` 里那个同名的自定义扩展 —— 后者签名是
    //   `Flow<T>.collectAsState(scope, initial)`，要求显式传 scope 且返回 `T?`，
    //   用它会一路报「No value passed for parameter 'scope'」+ 一串可空类型错误。
    //   同名不同签名是这类批量报错的根源，看到「No value for scope」先查这里。
    val dmComments by state.comments.collectAsState(emptyList())
    val dmKey by state.episodeKey.collectAsState("")
    val dmPrefs by state.renderParams.collectAsState(DanmakuRenderParams())
    val dmPos by viewModel.precisePosition.collectAsState(0f)
    val dmPaused by PlayerLib.propBoolean["pause"].collectAsState()
    if (dmPrefs.enabled && dmComments.isNotEmpty()) {
      DanmakuOverlay(
        episodeKey = dmKey,
        positionSec = dmPos,
        playing = dmPaused != true,
        comments = dmComments,
        alphaPercent = dmPrefs.alphaPercent,
        fontScale = dmPrefs.fontScale,
        speedScale = dmPrefs.speedScale,
        areaRatio = dmPrefs.areaRatio,
        maxOnScreen = dmPrefs.maxOnScreen,
        preventOverlapping = dmPrefs.preventOverlapping,
        // 竖屏下 mpv 窗口铺满、画面居中一条，弹幕层要跟着画面走，
        // 否则 retainer 从窗口顶部 packing ⇒ 全挤在画面上方的黑边里。
        videoAspectProvider = { player.getVideoOutAspect()?.toFloat() },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player)
  }

  private fun setupAudio() {
    val channels = audioPreferences.audioChannels.get()
    runCatching {
      // 非「反向立体声」的声道档位走 audio-channels 属性；
      // 反向立体声（pan）与夜间模式（dynaudnorm）都属于 af 链，统一由 AudioFilters 组装后写入，
      // 否则两者会各自 setPropertyString("af", ...) 而互相覆盖。
      if (channels.property != "af") {
        PlayerLib.setPropertyString(channels.property, channels.value)
      }
      app.marlboroadvance.mpvex.ui.player.engine.AudioFilters.applyAf(audioPreferences, channels)
    }.onFailure { e ->
      Log.e(TAG, "Error applying audio settings: $channels", e)
    }

    if (!serviceBound) {
      audioFocus.create()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean = audioFocus.requestAudioFocus()

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      pipHelper.enterPipMode()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")

    // 页面都没了就别再倒计时切集了
    cancelAutoplayCountdown()

    // 片头片尾盯梢一起停（不取消的话切到后台它还每隔 0.5 秒读一次 mpv 属性）
    cancelSkipSegmentWatcher()

    // 弹幕：取消在途的匹配请求（绘制线程由 DanmakuOverlay 的 DisposableEffect 负责 release）。
    danmakuState?.dispose()
    danmakuState = null

    runCatching {
      // OPTIMIZATION: Prevent any further UI updates or callbacks
      isReady = false

      // Only stop the service if we're not doing manual background playback
      if ((isUserFinishing || isFinishing) && !isManualBackgroundPlayback) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      }

      // 存档协程正在读 mpv 属性并写 Room。原先这里 `runBlocking { job.join() }`
      // **无限等它在主线程跑完** —— 那是 ANR 级的卡顿（Room 首次建表 + WAL fsync
      // 可以到几百毫秒），而它要防的竞态其实有更省的办法：
      // 竞态的本质是「destroy() 之后 job 还去读 mpv 属性」。所以只要**先冻结 mpv**
      // （下面 cleanupMPV 里的 `player.isExiting = true` + `removeObserver`），
      // job 读到的是什么就已经不重要了 —— 它写的要么是完整快照，要么是半截快照，
      // 两种都不会让程序崩。
      // 真正需要保证的是「别丢进度」，而那个由 onPause 的 `isSync` 分支负责
      // （退出时那次是同步写的，见 saveVideoPlaybackState）。这里的 job 是
      // onPause 之后的**异步补写**，它和 onPause 那次写的是同一份数据，
      // 晚一点到账不会丢，只是时序上晚一点。
      // 所以这里仍然等一下，但**必须有上限**：万一它真的卡住（磁盘满 / 数据库锁），
      // 也不能让用户看着「退出应用」卡死在那儿。
      savePlaybackStateJob?.let { job ->
        if (job.isActive) {
          Log.d(TAG, "Waiting for pending playback state save (max ${SAVE_JOIN_BUDGET_MS}ms)...")
          runCatching {
            kotlinx.coroutines.runBlocking {
              withTimeoutOrNull(SAVE_JOIN_BUDGET_MS) { job.join() }
            }
          }.onFailure { Log.w(TAG, "Join playback state job failed", it) }
          if (job.isActive) {
            Log.w(TAG, "Playback state save still running after budget; releasing MPV anyway")
            // 不 join 了：让这个协程随 Activity 一起结束。
            // 它后续读 mpv 会拿到 null（内核已销毁），runCatching 会吞掉。
            job.cancel()
          }
        }
      }

      cleanupMPV()
      cleanupAudio()
      // PiP receiver 被 activity 强引用；「PiP 中划掉应用」这条路不会走
      // onPictureInPictureModeChanged(false)，所以必须在这里显式反注册（清单 #20）
      // `pipHelper` 是 `lateinit var`（非可空），所以判「有没有初始化过」而不是判 null ——
      // 转交 GSY / 文件已不存在这两种提前 return 的路径上它压根没被赋值，
      // 直接 `pipHelper.release()` 会抛 UninitializedPropertyAccessException。
      if (::pipHelper.isInitialized) {
        pipHelper.release()
      }
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    // 「不喜欢」的当前条目是进程级单例，退出播放页要复位 ——
    // 否则下次进来会先闪一下上一条视频的标记状态。
    DislikePlayerActions.clear()

    super.onDestroy()
  }

  /**
   * 释放 mpv 内核（清单 #20）。
   *
   * ## 这里原先有两个 bug，都已修掉
   *
   * 1. **`command("quit")` 从未发出**（功能性 bug，可能原生崩溃）：
   *    原来的判断写的是 `if (isReady)`，而 `onDestroy` 开头已经把 `isReady = false`
   *    了 —— 于是 `quit` 与后面的等待整段都是**死代码**，直接从 `removeObserver`
   *    跳到 `destroy()`。而硬解的 UI 线程此时可能正在访问被销毁的互斥量/队列。
   *    现在改用 [mpvInitialized] 判断：它表示「内核有没有初始化过」，
   *    不受 `onDestroy` 里那行 `isReady = false` 影响，语义也对。
   *
   * 2. **主线程 `Thread.sleep(100)`**：
   *    每次退出播放页硬卡 100ms，而且「等固定时长」本身就不可靠（慢机器不够）。
   *    现在改成轮询 [PlayerLib.isIdleActive] 到真正空闲为止，且**有上限**，
   *    最坏情况才退化成原来的固定等待，不会无限卡住。
   *
   * ## 为什么这个等待不能搬到协程里
   *
   * `destroy()` 必须在等待完成**之后**同步调用（它是 native 的、不可挂起的）。
   * 把等待挪到后台再回来调用 `destroy()` 会留下一个「内核还活着但没人管」的窗口，
   * 反而更容易崩。所以这里保持同步 —— 区别是**从「盲等 100ms」变成「最多等 100ms，通常几毫秒就返回」**。
   */
  private fun cleanupMPV() {
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      PlayerLib.removeObserver(playerObserver)

      // 暂停可以真正降低活跃度，让 mpv 更快进入 idle
      PlayerLib.setPropertyBoolean("pause", true)
      // quit 是**异步**命令：投进队列就返回，此刻内部线程还在跑
      PlayerLib.command("quit")

      // 等 mpv 真正空闲，而不是盲等固定时长
      awaitMpvIdle()

      // ⚠️ 关闸必须在 destroy() **之前**（清单 #26）：
      // destroy() 一执行句柄就失效，而后面 `cleanupAudio` / `releaseMediaSession`
      // 以及 `onDestroy` 余下代码里仍有零散的属性访问（如音量、播放状态回写）。
      // 门禁关上后那些调用会被静默挡掉；不关就是打在已释放的句柄上 —— native 崩溃。
      PlayerLib.markCoreGone()

      PlayerLib.destroy()
      mpvInitialized = false
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  /**
   * 等 mpv 进入空闲状态，带时间上限。
   *
   * 上限取 100ms：与原先的 `Thread.sleep(100)` 同量级，所以**最坏情况不比以前更差**，
   * 而典型情况（quit 很快被处理）只等几毫秒。轮询间隔 5ms —— 再密也不会更准，
   * 因为 mpv 处理命令的粒度远大于 5ms。
   *
   * 不用 `Thread.sleep` 而用 `SystemClock.sleep`：显式表达「我就是要阻塞在这里」，
   * 避免以后有人 review 时以为这里挂错了线程。
   */
  private fun awaitMpvIdle() {
    val deadline = SystemClock.uptimeMillis() + MPV_IDLE_WAIT_BUDGET_MS
    while (SystemClock.uptimeMillis() < deadline) {
      if (PlayerLib.isIdleActive()) return
      SystemClock.sleep(MPV_IDLE_POLL_INTERVAL_MS)
    }
    Log.w(TAG, "MPV did not go idle within ${MPV_IDLE_WAIT_BUDGET_MS}ms; destroying anyway")
  }

  override fun abandonAudioFocus() {
    audioFocus.abandon()
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    audioFocus.unregisterNoisyReceiver()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPause() {
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause = (!audioPreferences.automaticBackgroundPlayback.get() && !isManualBackgroundPlayback) || 
                        (isUserFinishing && !isManualBackgroundPlayback)

      // Save playback state before stopping MPV or finishing
      // When finishing, do it synchronously so database is updated before MainActivity resumes
      if (!isInPip || isFinishing || isUserFinishing) {
        saveVideoPlaybackState(fileName, isSync = isFinishing || isUserFinishing)
      }

      // OPTIMIZATION: Stop playback immediately if finishing to reduce cleanup overhead
      if (isFinishing && !isManualBackgroundPlayback) {
        viewModel.pause()
        // Tell MPV to stop processing to reduce busywork during cleanup
        PlayerLib.command("stop")
      } else if (!isInPip && shouldPause) {
        // 这里原本还往 wasPlayingBeforePause 记一笔「暂停前在不在播」，但那个字段
        // 在音频焦点逻辑搬进 PlayerAudioFocus（改用 isPaused 实时回调）后就没有
        // 任何读取点了，属于拆分残留，已删。
        viewModel.pause()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isManualBackgroundPlayback) {
        restoreSystemUI()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun finish() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  // finishAndRemoveTask() was added in API 21, but since our minSdk is 26, it's always available
  override fun finishAndRemoveTask() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    runCatching {
      pipHelper.onStop()
      if (!isFinishing && !isUserFinishing) {
        saveVideoPlaybackState(fileName)
      }

      audioFocus.unregisterNoisyReceiver()

      // Handle background playback based on preferences
      val shouldAllowBackgroundPlayback = isManualBackgroundPlayback || 
                                          audioPreferences.automaticBackgroundPlayback.get()
      
      // Pause playback if background playback is not enabled and user is finishing
      if (!shouldAllowBackgroundPlayback && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onStart() {
    super.onStart()

    runCatching {
      setupWindowFlags()
      setupSystemUI()

      audioFocus.registerNoisyReceiver()

      // 交接单（从 GSY 切回来 / 重建）带来的亮度：那是**用户此刻眼睛看到的值**，
      // 优先级高于记忆 —— 记忆里可能还没有这部片（用户从没在这边调过）。
      if (handoffBrightness != null) {
        viewModel.changeBrightnessTo(handoffBrightness!!)
        handoffBrightness = null
      } else if (playerPreferences.rememberBrightness.get() ||
        playerPreferences.rememberSeriesBrightness.get()
      ) {
        // 按剧优先于全局默认（见 PlayerPreferences.brightnessFor）：
        // 上次把这部片调暗了，这次开播第一帧就该是暗的，不能先套全局值闪一下。
        // ⚠️ 不写「两份都开时取按剧的」以外的分支 —— 由 brightnessFor 统一裁决。
        val brightness = playerPreferences.brightnessFor(currentSeriesKey())
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      // Reset manual background playback flag when returning to foreground
      isManualBackgroundPlayback = false
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupSystemUI() {
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    // Set status bar color for when it will be shown (with controls)
    if (playerPreferences.showSystemStatusBar.get()) {
      @Suppress("DEPRECATION")
      window.statusBarColor = android.graphics.Color.parseColor("#80000000") // Semi-transparent black
    }

    // Always start with status bar hidden - it will show when controls are shown
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to setup system UI insets", e)
    }

    // Don't use LOW_PROFILE if we plan to show status bar with controls
    // LOW_PROFILE causes only icons to show without background
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun restoreSystemUI() {
    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  /**
   * 准备 mpv 的配置文件与脚本。
   *
   * ## 必须在 `player.initialize()` 之前、且在主线程调用
   *
   * 做三件纯文件 IO：
   * - `Utils.copyAssets`：从 assets 拷 mpv.conf / input.conf / scripts / shaders / fonts
   * - `syncFromUserMpvDirectory`：用户自建 mpv 目录（SAF）+ 字体目录同步
   * - `prepareAnime4KOnIoThread`：Anime4K shader（内部有 `.assets_copied` 标记文件的快路径，
   *   所以第二次起几乎不耗时间）
   *
   * ## 为什么这一段还是同步的（清单 #26 的教训）
   *
   * 曾经把它整块搬进 `lifecycleScope.launch { withContext(Dispatchers.IO) { … } }`，
   * 结果 `onCreate` 在内核 create 之前就返回，而 `MPVView` 是 `SurfaceView` ——
   * 首帧 traversal 触发的 `surfaceCreated → MPVLib.attachSurface()` 是 native 调用，
   * 打在还没 create 的句柄上 = SIGSEGV，JVM 拦不住 ⇒ 点播放必闪退。
   * 详见 `onCreate` 里那段注释。
   *
   * 真正该做的是「在 Application 启动时预热拷贝」，而不是把内核 create 推迟。
   *
   * 内核初始化本身在 [initializeMpvAndFinishSetup]（也是主线程，且必须如此：
   * `setOptionString` / `SurfaceView` 都要在主线程）。
   */
  private fun prepareMpvFilesOnIoThread() {
    // Copy essential files FIRST, before MPV initialization
    runCatching {
      Utils.copyAssets(this@PlayerActivity)
      // 用户自建 mpv 目录（SAF）+ 字体目录同步。
      // 逻辑已抽到 MpvAssetSync（清单 1.5 拆分第 1 刀）—— 它是纯文件 IO，
      // 不碰任何播放器状态，留在 Activity 里只会挡住真正的初始化流程。
      MpvAssetSync(
        context = this@PlayerActivity,
        advancedPreferences = advancedPreferences,
        subtitlesPreferences = subtitlesPreferences,
        fileManager = fileManager,
      ).syncFromUserMpvDirectory()
      // Anime4K 的 shader 也一起备好（清单 #20 必修 2）。
      // 必须在 `player.initialize()` 之前，因为 `glsl-shaders` 是 setOptionString，
      // 过了初始化期再设就不生效。
      player.prepareAnime4KOnIoThread()
      Log.d(TAG, "MPV config and scripts prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and scripts", e)
    }
  }

  override fun onResume() {
    super.onResume()
    updateVolume()
    // 诊断日志跟着「详细日志」开关走：这样用户在设置里改完开关、回到播放页就生效，
    // 不用重启应用。顺带把日志目录写进日志本身，省得还要翻代码找路径。
    PlayerDiagLog.configure(this, advancedPreferences.verboseLogging.get())
    PlayerDiagLog.log("session", "diag dir=${PlayerDiagLog.dirPath(this)}")
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    viewModel.currentVolume.update {
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also { volume ->
        if (volume < viewModel.maxVolume) {
          viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
        }
      }
    }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getInt("position", POSITION_NOT_SET).takeIf { it != POSITION_NOT_SET }?.let {
      PlayerLib.setPropertyInt("time-pos", it / MILLISECONDS_TO_SECONDS)
    }

    addSubtitlesFromExtras(extras)
    setHttpHeadersFromExtras(extras)
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs")
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

    lifecycleScope.launch(Dispatchers.Default) {
      for (suburi in subList) {
        val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        PlayerLib.command("sub-add", subfile, flag)
      }
    }
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    // Build header map starting with auto-detected referer
    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URL
    val uri = extractUriFromIntent(intent)
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { referer ->
        headerMap["Referer"] = referer
        Log.d(TAG, "Auto-detected Referer: $referer")
      }
    }

    // Process headers from extras (these can override the auto-detected referer)
    extras?.getStringArray("headers")?.let { headers ->
      if (headers.isEmpty()) return@let

      if (headers[0].startsWith("User-Agent", ignoreCase = true)) {
        PlayerLib.setPropertyString("user-agent", headers[1])
      }

      if (headers.size > 2) {
        headers
          .asSequence()
          .drop(2)
          .chunked(2)
          .filter { it.size == 2 }
          .forEach { (key, value) ->
            headerMap[key] = value
          }
      }
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      PlayerLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers: $headersString")
    }
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) return

    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URI
    HttpUtils.extractRefererDomain(uri)?.let { referer ->
      headerMap["Referer"] = referer
      Log.d(TAG, "Auto-detected Referer for playlist item: $referer")
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      PlayerLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers for playlist item: $headersString")
    }
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromIntent(intent: Intent): String? {
    val explicitFilePath = intent.getStringExtra("file_path")
    if (!explicitFilePath.isNullOrBlank() && File(explicitFilePath).exists()) {
      return explicitFilePath
    }
    return when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
      Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
      else -> intent.getStringExtra("uri")
    }
  }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      uri?.resolveUri(this@PlayerActivity)
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.resolveUri(this)
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    
    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * 取播放下标对应的显示标题。
   *
   * @return 标题；下标越界或标题为空时返回 null，交由调用方回落到 URL 解析。
   */
  private fun getPlaylistTitleAt(index: Int): String? =
    playlistTitles.getOrNull(index)?.takeIf { it.isNotBlank() }

  /**
   * Gets the display title for a playlist item URI.
   *
   * @param uri The URI to get the title for
   * @param index 该 URI 在 [playlist] 中的下标；传 -1 表示未知，直接走 URL 解析
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(
    uri: Uri,
    index: Int = -1,
  ): String {
    // 发起方给的标题最准：Emby 的流地址末段固定是 "stream"，从 URL 猜不出片名
    if (index >= 0) {
      getPlaylistTitleAt(index)?.let { return it }
    }

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  internal fun playPlaylistItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * 队列内拖动排序：把 [from] 处的条目移动到 [to]（[to] 是移动**之后**的目标下标）。
   *
   * `playlist` / `playlistTitles` / `playlistSeriesKeys` 三条平行数组必须同步换位，
   * 否则切集后标题、记忆键（「每部剧记住速度/音轨」的 key）会整体错位。
   * 若三条数组长度不一致（例如 windowed 加载只填了一部分），只换主数组并放弃标题数组，
   * 保证不会越界 —— 错位比崩溃好，且这种情况极少。
   */
  internal fun movePlaylistItem(
    from: Int,
    to: Int,
  ) {
    if (from == to) return
    if (from !in playlist.indices || to !in playlist.indices) return

    /** 拖动后的旧下标 → 新下标映射，用于同步 playlistIndex 与随机序列。 */
    fun remap(index: Int): Int =
      when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        from > to && index in to until from -> index + 1
        else -> index
      }

    val movedItems = playlist.toMutableList()
    movedItems.add(to, movedItems.removeAt(from))
    playlist = movedItems

    if (playlistTitles.size == movedItems.size) {
      val t = playlistTitles.toMutableList()
      t.add(to, t.removeAt(from))
      playlistTitles = t
    }
    if (playlistSeriesKeys.size == movedItems.size) {
      val k = playlistSeriesKeys.toMutableList()
      k.add(to, k.removeAt(from))
      playlistSeriesKeys = k
    }
    if (playlistSeriesNames.size == movedItems.size) {
      val s = playlistSeriesNames.toMutableList()
      s.add(to, s.removeAt(from))
      playlistSeriesNames = s
    }
    if (playlistMediaSourceNames.size == movedItems.size) {
      val n = playlistMediaSourceNames.toMutableList()
      n.add(to, n.removeAt(from))
      playlistMediaSourceNames = n
    }
    if (playlistItemIds.size == movedItems.size) {
      val i = playlistItemIds.toMutableList()
      i.add(to, i.removeAt(from))
      playlistItemIds = i
    }
    // 剧集序号也必须跟着换位：弹幕按它匹配集号，错位 = 整场弹幕对不上画面
    if (playlistEpisodeNumbers.size == movedItems.size) {
      val e = playlistEpisodeNumbers.toMutableList()
      e.add(to, e.removeAt(from))
      playlistEpisodeNumbers = e
    }

    playlistIndex = remap(playlistIndex)
    // 随机播放序列存的是下标，换位后必须按同一映射重算，否则「下一条」会重复或跳条
    if (shuffledIndices.isNotEmpty()) shuffledIndices = shuffledIndices.map(::remap)

    viewModel.refreshPlaylistItems()
  }

  /**
   * 从队列移除一条。
   *
   * - 只剩一条时**拒绝移除**（返回 false）：队列清空后播放页没有任何可播项，
   *   等于让用户点的按钮把当前视频也停了，不是他想要的；
   * - 移除的正好是「正在播放」那一条时，原地播放接替它的下一条（已是最后一条则退到前一条）。
   *
   * @return 是否真的移除了。
   */
  internal fun removePlaylistItem(index: Int): Boolean {
    if (index !in playlist.indices) return false
    if (playlist.size <= 1) return false

    val wasCurrent = index == playlistIndex
    val items = playlist.toMutableList()
    items.removeAt(index)
    playlist = items

    if (playlistTitles.size == items.size + 1) {
      playlistTitles = playlistTitles.toMutableList().also { it.removeAt(index) }
    }
    if (playlistSeriesKeys.size == items.size + 1) {
      playlistSeriesKeys = playlistSeriesKeys.toMutableList().also { it.removeAt(index) }
    }
    if (playlistSeriesNames.size == items.size + 1) {
      playlistSeriesNames = playlistSeriesNames.toMutableList().also { it.removeAt(index) }
    }
    if (playlistMediaSourceNames.size == items.size + 1) {
      playlistMediaSourceNames = playlistMediaSourceNames.toMutableList().also { it.removeAt(index) }
    }
    if (playlistItemIds.size == items.size + 1) {
      playlistItemIds = playlistItemIds.toMutableList().also { it.removeAt(index) }
    }
    if (playlistEpisodeNumbers.size == items.size + 1) {
      playlistEpisodeNumbers = playlistEpisodeNumbers.toMutableList().also { it.removeAt(index) }
    }
    if (shuffledIndices.isNotEmpty()) {
      // 被删的下标从随机序列里摘掉，其后所有下标整体前移一位
      shuffledIndices =
        shuffledIndices.filter { it != index }.map { if (it > index) it - 1 else it }
      shuffledPosition = shuffledPosition.coerceIn(0, (shuffledIndices.size - 1).coerceAtLeast(0))
    }

    when {
      wasCurrent -> {
        val next = index.coerceAtMost(playlist.size - 1)
        playlistIndex = next
        loadPlaylistItem(next)
      }
      index < playlistIndex -> playlistIndex -= 1
    }

    viewModel.refreshPlaylistItems()
    return true
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? =
    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    } else {
      intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    }

  /** 返回当前正在播放的媒体 URI：优先取播放列表当前项，否则从 intent 解析。供投屏按钮写入 payload。 */
  internal fun getCurrentPlayingUri(): Uri? {
    if (playlist.isNotEmpty() && playlistIndex in playlist.indices) return playlist[playlistIndex]
    return extractUriFromIntent(intent)
  }

  // ────────────────────────────────────────────────────────────────────────
  // 内核切换：把当前播放会话交给 GSY 播放页
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 把「当前视频 + 整份播放队列 + 当前进度」交给 [GsyPlayerActivity]。
   *
   * 只传一个地址是不够的：那样切过去就只剩一个孤零零的视频 —— 队列、标题、
   * 「现在放到第几个」全丢，Emby 那边的进度回传也断档。所以整份 [PlayerHandoff]
   * 都要带过去，GSY 那边读出来之后就能在同一份队列里继续切集。
   *
   * 进度取 `time-pos`（秒，带小数）而不是取整秒的 `propInt`，切过去接着放的口径更准。
   */
  internal fun switchToGsyPlayer() {
    val uri = getCurrentPlayingUri()
    if (uri == null) {
      viewModel.showToast(getString(app.marlboroadvance.mpvex.R.string.player_engine_switch_unavailable))
      return
    }

    val positionMs = ((PlayerLib.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()

    val target =
      Intent(Intent.ACTION_VIEW, uri, this, GsyPlayerActivity::class.java).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra("internal_launch", true)
        putExtra("title", getTitleForControls())
        putExtra("filename", fileName)
        PlayerHandoff(
          playlist = playlist,
          titles = playlistTitles,
          seriesKeys = playlistSeriesKeys,
          // 弹幕要按真实集号匹配，必须一起交给 GSY，否则那边只剩队列下标可用（会错集）
          episodeNumbers = playlistEpisodeNumbers,
          index = playlistIndex,
          playlistId = playlistId,
          positionMs = positionMs,
          seriesKey = currentSeriesKey(),
          embyServerId =
            intent
              .getLongExtra(app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_SERVER_ID, -1L)
              .takeIf { it > 0 },
          embyItemIds =
            intent.getStringArrayListExtra(
              app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
            ) ?: emptyList(),
          headers = intent.getStringArrayExtra("headers"),
          // 按剧亮度跨内核带过去（GSY 是另一个 Activity 的 window，不带就弹回默认）
          brightness = if (playerPreferences.rememberSeriesBrightness.get()) {
            viewModel.currentBrightness.value.takeIf { it in 0f..1f }
          } else {
            null
          },
        ).writeTo(this)
      }

    startActivity(target)
    // 立刻收摊：mpv 与 GSY 同时持有音频焦点 / 解码器只会互相打架
    isUserFinishing = true
    finish()
  }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? =
    runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Converts the intent URI to a playable URI string for MPV.
   *
   * @param intent The intent containing the file URI
   * @return A playable URI string, or null if unable to resolve
   */
  private fun getPlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent) ?: return null
    return isoPlayableUri(
      if (uri.startsWith("content://")) {
        uri.toUri().openContentFd(this) ?: uri
      } else {
        uri
      },
    ).also { applyAviDemuxerWorkaround(it) }
  }

  /** content:// 转成 fd://（mpv 自己读不了 SAF）；.iso 镜像再映射成 bluray:// / dvd:// */
  private fun getPlayableUriForEngine(intent: Intent): String? = getPlayableUri(intent)

  private fun playlistItemPlayableUri(uri: Uri): String =
    isoPlayableUri(uri.openContentFd(this) ?: uri.toString()).also { applyAviDemuxerWorkaround(it) }

  /**
   * AVI 容器 + H264（avc1）的黑屏兜底。
   *
   * AVI 没有 PTS 概念，带打包 B 帧的 H264 流进 ffmpeg 解复用后解码器常常
   * 等不到时间戳，表现就是「打开一直黑屏没反应」。只对 .avi 文件开
   * `fflags=+genpts`（缺失 PTS 时补齐）；其他文件显式置空，不影响。
   */
  private fun applyAviDemuxerWorkaround(playableUri: String) {
    val pathLike = playableUri.substringBefore('?')
    val isAvi = pathLike.endsWith(".avi", ignoreCase = true) && pathLike.startsWith("/")
    PlayerLib.setPropertyString("demuxer-lavf-o", if (isAvi) "fflags=+genpts" else "")
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // ── 诊断埋点 ──
    // 「挂了外挂字幕时旋转会卡一下」这条线，在排除掉 Activity 重建 / 我们自己的旋转路径 /
    // 字体重解析 / 字幕恢复链重跑这四条之后，只剩 mpv 内部的 resize 路径，没有实机数据定不了案。
    // 这里在**旋转那一刻**与**1.2 秒后**各拍一行：播放位置走了多少、丢帧计数涨了多少 ——
    // 用来区分「画面真的停住」和「只是观感上卡」。只在设置里打开「详细日志」时才写文件。
    PlayerDiagLog.log("rotate", "orient=${orientationName(newConfig)} ${playbackProbe()}")
    lifecycleScope.launch {
      kotlinx.coroutines.delay(1200)
      PlayerDiagLog.log("rotate+1200ms", playbackProbe())
    }
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /** 诊断用：横竖屏名，只为日志好读。 */
  private fun orientationName(config: Configuration): String =
    if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

  /**
   * 诊断用：把「这一刻播到哪儿、丢了多少帧、字幕选的是哪条」拍成一行。
   *
   * `time-pos` 两次采样的差值 + `frame-drop-count` 的增量，是判断旋转时**画面是否真的停住**
   * 的客观依据 —— 光靠「感觉卡」定位不了（这也是为什么要专门把日志落盘：
   * 非 root 手机上第三方 logcat 应用读不到别的应用的日志）。
   */
  private fun playbackProbe(): String =
    runCatching {
      val pos = PlayerLib.getPropertyDouble("time-pos")
      val drops = PlayerLib.getPropertyInt("frame-drop-count")
      val sid = PlayerLib.getPropertyInt("sid")
      val vo = PlayerLib.getPropertyString("current-vo")
      "pos=%.3f drops=%d sid=%d vo=%s".format(pos ?: -1.0, drops ?: -1, sid ?: -2, vo ?: "?")
    }.getOrElse { "probe-failed=${it.message}" }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    PlayerDiagLog.log("rotate-handled", "pip=$isInPictureInPictureMode")
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h" -> {
        // Safety check: don't access MPV during cleanup（Exo 内核下 mpv 从未初始化，只查 isFinishing）
        if (isFinishing) return
        if (engineKind == EngineKind.MPV && (!mpvInitialized || player.isExiting)) return

        val aspect = currentVideoAspect()
        Log.d(TAG, "Video dimension changed: $property, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video dimensions change (fixes Video orientation mode)
        // 但仅首帧设一次：之后切集不再调用 setOrientation，避免「按视频方向」模式下
        // 下一集若是竖屏内容被翻成竖屏，违背用户当前方向（最佳体验：切集不改方向）。
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && aspect != null) {
          if (!videoParamsOrientationHandled) {
            setOrientation()
            videoParamsOrientationHandled = true
          }
        }

        // Re-apply Anime4K shaders (check for resolution limit) —— mpv 专属
        if (engineKind == EngineKind.MPV) player.applyAnime4KShaders()
      }
    }
  }

  /** 当前视频宽高比：走 MPVView（含 rotate 修正） */
  private fun currentVideoAspect(): Double? = player.getVideoOutAspect()

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady) {
          isReady = true
        }
      }
      "eof-reached" -> handleEndOfFile(value)
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }
  }

  /** 自动连播倒计时的盯梢协程；切集 / 取消 / 退出时都要 cancel，避免播完后还偷偷切集 */
  private var autoplayCountdownJob: kotlinx.coroutines.Job? = null

  /**
   * 用户在倒计时卡片上点过「取消」：本集播完不再自动切，停在最后一帧。
   * 只影响当前这一集，切集 / 开播下一集时清零。
   */
  private var autoplayCancelled = false

  /**
   * 用户在本集点过「下一集」悬停卡上的 ×：本集内不再弹这张卡。
   *
   * ⚠️ 必须有这个标记，不能只把 `nextUpVisible` 置 false ——
   * 片尾盯梢每 500ms 复算一次出现窗口，只要剩余时长还在窗口内就会把 visible 重新置 true，
   * 于是「叉掉半秒后又弹出来」⇒ × 形同虚设。只在每集开播时清零（见 loadFile 里
   * `startAutoplayCountdownWatcher()` 的调用点），因此语义是「这一集别再烦我，下一集照常提示」。
   */
  private var nextUpDismissed = false

  // ==================== 片头片尾跳过（b2+a1） ====================

  /**
   * 片头 / 片尾盯梢协程：拉精准分段 + 每 0.5 秒看一次播放头在哪。
   * 切集（重新起一轮）/ 退出时 cancel。
   */
  private var skipSegmentJob: kotlinx.coroutines.Job? = null

  /** 片头区间（毫秒，闭开区间 start..<end）；null = 这一段没有数据 */
  private var introRangeMs: Pair<Long, Long>? = null

  /** 片尾区间（毫秒）；null = 这一段没有数据 */
  private var outroRangeMs: Pair<Long, Long>? = null

  /** 本集是否已经自动跳过片头 / 片尾过一次（跳完置 true，用户往回拖也不会再跳） */
  private var introSkipped = false
  private var outroSkipped = false

  /**
   * 每集开播时起一轮「片头片尾」处理。
   *
   * 两步：
   * 1. **拉精准数据**：Emby 4.7+ 的 `/Users/{UserId}/Items/{Id}/Intros`。
   *    拿不到（非 Emby 播放 / 服务端没扫描 / 网络断）就退到固定时长兜底，
   *    见 [PlayerPreferences.autoSkipIntro] 的 KDoc。
   * 2. **轮询**：0.5 秒精度（同连播盯梢，不观察 time-pos —— 每帧回调的 JNI
   *    开销在这里不值）。自动跳开关开着 ⇒ 播放头进入区间且没暂停就跳到段尾；
   *    开关关着 ⇒ 把「跳过片头 / 跳过片尾」小胶囊的文案写进
   *    [PlayerViewModel.skipSegmentLabel]，等用户自己点（手动精准跳）。
   *
   * 注意**两个开关都关着盯梢照样跑**：手动胶囊只需要精准数据，不需要开关。
   * 代价是每集一次小 GET（纯本地播放没有这个请求）。
   */
  private fun startSkipSegmentWatcher() {
    skipSegmentJob?.cancel()
    viewModel.skipSegmentLabel.value = null
    introRangeMs = null
    outroRangeMs = null
    introSkipped = false
    outroSkipped = false

    val autoIntro = playerPreferences.autoSkipIntro.get()
    val autoOutro = playerPreferences.autoSkipOutro.get()
    val fixedMs = playerPreferences.customSkipDuration.get().coerceAtLeast(1) * 1000L

    skipSegmentJob =
      lifecycleScope.launch {
        // 1) 精准分段（失败 / 没有都算正常，静默退兜底）
        val serverId = currentEmbyServerId()
        val itemId = currentEmbyItemId()
        if (serverId != null && !itemId.isNullOrBlank()) {
          val server = runCatching { embyRepository.getServer(serverId) }.getOrNull()
          if (server != null) {
            val result = runCatching { embyRepository.getItemIntros(server, itemId) }.getOrNull()
            result?.Segments?.forEach { seg ->
              if (!seg.isValid) return@forEach
              when (seg.Type?.lowercase()) {
                "intro" -> introRangeMs = seg.startMs to seg.endMs
                "outro" -> outroRangeMs = seg.startMs to seg.endMs
              }
            }
          }
        }

        // 2) 轮询播放头
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
          kotlinx.coroutines.delay(500)
          if (isFinishing) break
          val durationMs = ((PlayerLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
          if (durationMs <= 0) continue
          val posMs = ((PlayerLib.getPropertyDouble("time-pos") ?: continue) * 1000).toLong()
          val paused = PlayerLib.getPropertyBoolean("pause") ?: false

          // 没有精准数据时按固定时长兜底（**只在自动跳开着时兜底** ——
          // 关着时只认精准数据，否则每集开头都挂个「跳过片头」纯属打扰，
          // 控制条上本来就有固定时长的快进钮）。
          // 短片（总长 ≤ 2×兜底值）不兜底：几分钟的片子不可能有 90 秒片头。
          val fixedIntroUsable = durationMs > fixedMs * 2
          val intro = introRangeMs ?: if (autoIntro && fixedIntroUsable) 0L to fixedMs else null
          val outro = outroRangeMs ?: if (autoOutro && fixedIntroUsable) (durationMs - fixedMs) to durationMs else null

          // 手动胶囊：开关关着、播放头在区间里 ⇒ 给个可点的提示
          val label =
            when {
              !autoIntro && intro != null && posMs in intro.first until intro.second -> "跳过片头"
              !autoOutro && outro != null && posMs >= outro.first && posMs < outro.second -> "跳过片尾"
              else -> null
            }
          viewModel.skipSegmentLabel.value = label

          // 自动跳：只跳一次、且不趁用户暂停时跳
          if (!paused && !introSkipped && autoIntro && intro != null && posMs in intro.first until intro.second) {
            introSkipped = true
            viewModel.skipSegmentLabel.value = null
            runCatching { PlayerLib.command("seek", (intro.second / 1000.0).toString(), "absolute") }
          }
          if (!paused && !outroSkipped && autoOutro && outro != null && posMs >= outro.first && posMs < outro.second) {
            outroSkipped = true
            viewModel.skipSegmentLabel.value = null
            // 跳到结尾前 0.2 秒： seek 到报告的 duration 有时差一点点到不了 EOF
            runCatching {
              PlayerLib.command("seek", ((durationMs - 200) / 1000.0).toString(), "absolute")
            }
          }
        }
      }
  }

  /** 切集 / 退出时停掉片头片尾盯梢（不 cancel 的话旧条目的分段会一直生效） */
  private fun cancelSkipSegmentWatcher() {
    skipSegmentJob?.cancel()
    skipSegmentJob = null
    viewModel.skipSegmentLabel.value = null
  }

  /**
   * 每集开播时起一个轻量轮询：**快播完的那几秒**把倒计时卡片推到屏幕上。
   *
   * 不用「播完再倒数」的做法 —— 那样视频已经停在最后一帧了，
   * 用户还要干等几秒才切，纯粹是浪费时间。现在卡片在片尾提前出现，
   * 真正切集仍然发生在播完的那一刻（[onPlaybackEndedAutoplay]），一秒都不多等。
   *
   * 轮询而不是观察 mpv 的 time-remaining：后者每帧回调一次、JNI 开销明显，
   * 而这里只需要 0.5 秒精度。
   */
  private fun startAutoplayCountdownWatcher() {
  autoplayCountdownJob?.cancel()
  val total = playerPreferences.autoplayNextCountdownSeconds.get()
  // 下一集卡片（Yamby 的 next_up_sec）：比倒计时更早出现，给用户「要不要继续看」的决策时间。
  // 只在设置页真的开了这一项且大于 0 时生效，否则严格保持老行为（不额外探测）。
  val nextUpTotal = if (playerPreferences.showNextUpCard.get()) {
    playerPreferences.nextUpShowSeconds.get().coerceIn(0, 300)
  } else {
    0
  }
  val prefetchLead = if (playerPreferences.prefetchNextEpisode.get()) {
    playerPreferences.prefetchNextEpisodeSeconds.get().coerceIn(0, 300)
  } else {
    0
  }
  // 两件事都关、连倒计时也是 0 ⇒ 完全不盯，老行为。
  if (total <= 0 && nextUpTotal <= 0 && prefetchLead <= 0) {
    autoplayCountdownJob = null
    return
  }
    val startIndex = playlistIndex
  autoplayCountdownJob = lifecycleScope.launch {
    var prefetched = false
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
      kotlinx.coroutines.delay(500)
      // 用户自己切了集 / 退出了 / 在片尾取消了：这次盯梢作废
      if (playlistIndex != startIndex || isFinishing || autoplayCancelled) break
      val nextIdx = peekNextIndex()
      if (nextIdx == null && !viewModel.shouldRepeatPlaylist()) break // 没有下一集
      val left = wallClockSecondsToEnd() ?: continue
      // 预热：进入提前量窗口就打一次小流量 Range（每个会话只打一次）
      if (!prefetched && prefetchLead > 0 && left in 0.1..prefetchLead.toDouble()) {
        prefetched = true
        peekNextIndex()?.let { warmUpNextEpisode(it) }
      }
      // 下一集卡片：比倒计时早 nextUpTotal 秒出现。
      // 用户点过 × ⇒ 本集不再进入这个分支，交给下面的 else 分支确保它保持收起。
      if (nextUpTotal > 0 && !nextUpDismissed && left in 0.1..nextUpTotal.toDouble()) {
        if (viewModel.nextUpTitle.value == null) {
          viewModel.nextUpTitle.value = nextIdx?.let { playlistTitles.getOrNull(it) }
        }
        viewModel.nextUpVisible.value = true
      } else if (viewModel.nextUpVisible.value) {
        // 三种情况都收起来：用户叉掉了 / 往后拖出了窗口 / 设置里关了这一项
        viewModel.nextUpVisible.value = false
        viewModel.nextUpTitle.value = null
      }
      // 进入最后 total 秒：把卡片推出来，之后每 0.5 秒刷新一次，数字自然往下走
      if (total > 0 && left in 0.1..total.toDouble()) {
        if (viewModel.autoplayNextTitle.value == null) {
          viewModel.autoplayNextTitle.value = peekNextIndex()?.let { playlistTitles.getOrNull(it) }
        }
        viewModel.autoplayCountdown.value = kotlin.math.ceil(left).toInt().coerceAtLeast(1)
      } else if (total > 0 && left > total && viewModel.autoplayCountdown.value > 0) {
        // 用户往后拖了进度条：卡片收起来，等再次接近片尾再出现
        viewModel.autoplayCountdown.value = 0
        viewModel.autoplayNextTitle.value = null
      }
    }
  }
}

/**
 * 预热下一集：只发一个**很小的** Range 请求，拿到响应头就断开。
 *
 * ⚠️ 故意不用 `Cache-Control: no-cache` 之类 —— 让它落进 okhttp 的连接池 / DNS 缓存，
 *    这才是要的：切集时省掉 DNS + TCP + TLS 三次握手（高延迟网络上这才是主要耗时）。
 * ⚠️ 失败静默：预热本来就是锦上添花，绝不能弹提示打扰播放。
 */
private fun warmUpNextEpisode(index: Int) {
  val url = networkFilePaths.getOrNull(index)?.takeIf { it.startsWith("http") } ?: return
  lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
      val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()
      val req = okhttp3.Request.Builder()
        .url(url)
        .header("Range", "bytes=0-65535")
        .build()
      client.newCall(req).execute().use { it.body?.close() }
    }
  }
}

/**
 * 距离本集播完还剩多少**真实**秒数（考虑倍速）。
 *
 * mpv 的 duration / time-pos 都是媒体时间，2 倍速下剩 10 秒片尾实际只要 5 秒就放完，
 * 所以要除以当前倍速，否则卡片会早一倍时间弹出来。
 */
private fun wallClockSecondsToEnd(): Double? {
  val duration = PlayerLib.getPropertyDouble("duration") ?: return null
  val position = PlayerLib.getPropertyDouble("time-pos") ?: return null
  val speed = PlayerLib.getPropertyDouble("speed") ?: 1.0
  if (duration <= 0 || speed <= 0) return null
  return (duration - position) / speed
}

/**
 * 播完一集时调用：默认直接切下一集（一秒都不等），
 * 除非用户刚才在卡片上点了「取消」——那就停在最后一帧，等他手动操作。
 *
 * 取消标记用完即清，只影响「这一集」，下一集重新给机会。
 */
private fun onPlaybackEndedAutoplay() {
  val cancelled = autoplayCancelled
  autoplayCancelled = false
  autoplayCountdownJob?.cancel()
  autoplayCountdownJob = null
  viewModel.autoplayCountdown.value = 0
  viewModel.autoplayNextTitle.value = null
  if (cancelled) return
  playNext()
}

/** 取消倒计时：卡片上的「取消」按钮，以及用户手动切集 / 退出时都要调用 */
private fun cancelAutoplayCountdown() {
  autoplayCountdownJob?.cancel()
  autoplayCountdownJob = null
  viewModel.autoplayCountdown.value = 0
  viewModel.autoplayNextTitle.value = null
  viewModel.nextUpVisible.value = false
  viewModel.nextUpTitle.value = null
}

  /**
   * 下一个要播的条目下标（不真的切过去，只用来提前取标题）。
   * 与 [playNext] 的判断保持一致：随机模式走 shuffledIndices，末位且整列表循环时回到开头。
   */
  private fun peekNextIndex(): Int? {
    if (playlist.isEmpty()) return null
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size
    return if (viewModel.shuffleEnabled.value) {
      if (shuffledIndices.isEmpty()) generateShuffledIndices()
      val nextPosition = shuffledPosition + 1
      when {
        nextPosition <= shuffledIndices.size - 1 -> shuffledIndices.getOrNull(nextPosition)
        viewModel.shouldRepeatPlaylist() -> shuffledIndices.firstOrNull()
        else -> null
      }
    } else {
      when {
        playlistIndex < effectiveSize - 1 -> playlistIndex + 1
        viewModel.shouldRepeatPlaylist() -> 0
        else -> null
      }
    }
  }

  /**
   * 当前这一集对应的「记忆键」：列表播放按下标取，单文件播放用 intent 里那个。
   *
   * 取不到就返回 null，此时播放速度 / 音轨记忆整体跳过（本地播放就是这种情况）。
   */
  private fun currentSeriesKey(): String? =
    playlistSeriesKeys.getOrNull(playlistIndex) ?: singleSeriesKey

  /**
   * 当前这一集所属**剧**的名字；没有剧名时（电影 / 本地文件 / 音乐）退回 [fileName]。
   *
   * ⚠️ 仅供弹幕自动匹配使用 —— 它要搜的是整部番，不是某一集的标题。
   *
   * @param index 目标条目在队列里的下标
   */
  private fun danmakuMatchTitle(index: Int = playlistIndex): String {
    val series = playlistSeriesNames.getOrNull(index) ?: singleSeriesName
    return if (!series.isNullOrBlank()) series else fileName
  }

  /**
   * 通知弹幕状态机「现在是哪一集」。
   *
   * ## 为什么要封装
   *
   * 有两个时机需要通知：切集（[loadPlaylistItemInternal]）和**首次起播**
   * （[handleFileLoaded]，那条路径是 `playFile` 直通，不经队列装载）。
   * 两处的参数口径必须完全一致 —— 尤其是集号要用 [currentEpisodeIndex] 而不是队列下标，
   * 否则「从第 5 集点进详情页播放」会被当成第 1 集。
   *
   * ⚠️ 队列下标必须**显式传**：切集时调用方手里的 `index` 才是目标条目，
   * 而 `handleFileLoaded` 时 `playlistIndex` 已经更新过了。默认参数取 `playlistIndex`，
   * 两个时机都成立。
   *
   * @param index 目标条目在队列里的下标；默认当前正在播的那一条
   */
  private fun notifyDanmakuEpisodeChanged(index: Int = playlistIndex) {
    danmakuState?.onEpisodeChanged(
      currentSeriesKey(),
      danmakuMatchTitle(index),
      currentEpisodeIndex(index),
    )
  }

  /**
   * 当前这一条在**剧集里的真实集号（0-based）**，供弹幕「自动按当前集挑」使用。
   *
   * 弹幕面板手动选番时要按「当前是第几集」取那一集，而 [playlistIndex] 是队列下标 ——
   * 从第 5 集点播时队列只有 1 条，用下标会取成第 1 集。
   */
  fun currentEpisodeIndexForDanmaku(): Int = currentEpisodeIndex()

  /**
   * 当前这一条在**剧集里的真实集号（0-based）**，供弹幕匹配使用。
   *
   * 之所以不能直接用 [playlistIndex]：
   * - 从第 5 集点进详情页播放时，队列只有 1 条 ⇒ 下标 0 ⇒ 会被当成第 1 集；
   * - 单文件播放（`launchSingle`）根本没有队列，下标恒为 0。
   *
   * 优先用 intent 里下发的 Emby 原始序号（`S02E05` → `2005` → 2004）；
   * 拿不到（本地播放 / 网盘）才退回队列下标。
   *
   * @param index 要查的队列下标；默认取当前的 [playlistIndex]。
   */
  private fun currentEpisodeIndex(index: Int = playlistIndex): Int {
    val encoded = playlistEpisodeNumbers.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return index
    val number = encoded.toIntOrNull() ?: return index
    // 编码是 season*1000 + episode：整除得季、取余得集。
    // 集号从 1 开始，转成 0-based 后再交给 DanmakuMatcher（它内部再 +1）。
    val episode = number % 1000
    return if (episode > 0) episode - 1 else index
  }

  /**
   * 当前这一条对应的「多版本名」（如 "1080P HEVC"）；不是多版本条目就返回 null。
   *
   * 供「更多 → 媒体信息」弹窗显示「现在播的是哪个版本」—— 不再拼进顶栏片名
   * （片名 + 版本名一长，标题栏会更挤）。
   * 读 [playlistIndex]（Compose 状态）⇒ 切集后会自动跟着换。
   */
  fun currentMediaSourceName(): String? =
    playlistMediaSourceNames.getOrNull(playlistIndex)?.takeIf { it.isNotBlank() }

  /**
   * 当前播放条目的 Emby 服务器 Id；不是 Emby 播放（本地文件 / 网盘）返回 null。
   */
  fun currentEmbyServerId(): Long? =
    intent
      .getLongExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_SERVER_ID,
        -1L,
      )
      .takeIf { it > 0 }

  /**
   * 当前播放条目的 Emby ItemId；不是 Emby 播放 / 解析不出来返回 null。
   *
   * ⚠️ ItemId 从 **mpv 当前的 `path`** 反查，不用 `playlistIndex` 索引 intent 里的
   * `emby_item_ids` —— 用户拖动过播放队列、或随机播放后，下标就与 intent 的原始
   * 顺序错位了（同一个坑见 [currentEmbyVersionProgressKey]）。
   */
  fun currentEmbyItemId(): String? {
    // ① 首选：从 mpv 的 `path` 反查 —— 与队列顺序完全无关（换位 / 随机播放都不会错）
    app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
      .parseItemIdFromPath(PlayerLib.getPropertyString("path"))
      ?.let { return it }
    // ② 兜底：按当前下标取平行数组。
    //    什么时候会走到这里：开了「预解析直链」时 path 已经是 302 之后的真实地址
    //    （网盘域名 / 别的主机），里面根本没有 `/Videos/{id}/stream` 可反查；
    //    ISO 走本地 Range 代理时 path 是 127.0.0.1 的地址，同样反查不到。
    //    平行数组在换位 / 删除时同步维护（见 movePlaylistItem / removePlaylistItem），
    //    所以这条路径依然对得上当前这一条。
    return playlistItemIds.getOrNull(playlistIndex)?.takeIf { it.isNotBlank() }
  }

  /**
   * 当前播放条目**实际在播的那个版本**的媒体源 Id；单版本 / 非 Emby 返回 null。
   *
   * 查的是 [embyMediaSourceByItemId]（ItemId → MediaSourceId 的 Map），
   * 与队列顺序无关 ⇒ 换位、删除、随机播放都不需要同步。
   */
  fun currentEmbyMediaSourceId(): String? =
    currentEmbyItemId()
      ?.let { embyMediaSourceByItemId[it] }
      ?.takeIf { it.isNotBlank() }

  /**
   * 从 intent 里取出「ItemId → 媒体源 Id」映射（多版本条目才有值）。
   *
   * intent 里 `emby_item_ids` 与 `emby_media_source_id` 是**同序同长**的两串，
   * 这里把它们 zip 成 Map；空串（该条没有多版本）直接丢掉，不入表。
   */
  private fun buildEmbyMediaSourceMap(intent: Intent): Map<String, String> {
    val ids =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
      ).orEmpty()
    val sources =
      intent.getStringArrayListExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID,
      ).orEmpty()
    if (ids.isEmpty() || sources.isEmpty()) return emptyMap()
    return buildMap<String, String> {
      ids.forEachIndexed { index, id ->
        val source = sources.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        if (id.isNotBlank()) put(id, source)
      }
    }
  }

  /**
   * 当前播放条目的「按版本进度键」；非 Emby / 单版本 / 该条没选版本时返回 null。
   *
   * 键形状与详情页算的完全一致（同一份 [EmbyVersionProgress]），两个页面各算各的、
   * 不需要互通。详见该对象的注释：Emby 服务端的进度是按条目共享的，多版本条目只能本地分开记。
   *
   * ⚠️ ItemId 从 **mpv 当前路径**反查，不用 `playlistIndex` 索引 intent：
   * 用户拖动过播放队列后下标就与 intent 的原始顺序错位了。
   */
  private fun currentEmbyVersionProgressKey(): String? {
    val serverId =
      intent.getLongExtra(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_SERVER_ID,
        -1L,
      )
    if (serverId <= 0) return null
    val itemId =
      app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
        .parseItemIdFromPath(PlayerLib.getPropertyString("path"))
        ?: return null
    val sourceId = embyMediaSourceByItemId[itemId]?.takeIf { it.isNotBlank() } ?: return null
    return app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress.key(serverId, itemId, sourceId)
  }

  /**
   * 切集后把记忆键同步给 ViewModel —— 用户在这一集里改的速度 / 音轨要记到「这一部剧」名下。
   */
  private fun refreshSeriesKey() {
    viewModel.seriesKey = currentSeriesKey()
  }

  /**
   * 应用「本剧上次记住的播放速度 / 音轨」。
   *
   * 只在开关打开且有记忆键时生效；音轨按指纹匹配（见 [PlaybackMemory]），
   * 匹配不到就什么都不做，交给 TrackSelector 的默认语言逻辑。
   */
  private fun applyRememberedPlaybackSettings() {
    val key = currentSeriesKey()
    viewModel.seriesKey = key
    // b4 全局倍速记忆作为**兜底**：先套全局上次倍速，再让每剧记忆覆盖。
    // 顺序不能反 —— 每剧记忆优先级更高（同剧切集沿用本剧调过的速度）。
    if (playerPreferences.rememberGlobalSpeed.get()) {
      val last = playerPreferences.lastPlaybackSpeed.get()
      if (last > 0f) {
        runCatching { PlayerLib.setPropertyDouble("speed", last.toDouble()) }
      }
    }
    if (key.isNullOrBlank()) return
    if (playerPreferences.rememberSpeedPerSeries.get()) {
      val speed = PlaybackMemory.speedFor(playerPreferences, key)
      if (speed != null && speed > 0f) {
        runCatching { PlayerLib.setPropertyDouble("speed", speed.toDouble()) }
      }
    }
    if (playerPreferences.rememberAudioTrackPerSeries.get()) {
      val fingerprint = PlaybackMemory.audioFingerprintFor(playerPreferences, key)
      if (!fingerprint.isNullOrBlank()) {
        val trackId = PlaybackMemory.findAudioTrackId(fingerprint)
        if (trackId != null && trackId > 0) {
          runCatching { PlayerLib.setPropertyInt("aid", trackId) }
        }
      }
    }
    if (playerPreferences.rememberSubtitleTrackPerSeries.get()) {
      val fingerprint = PlaybackMemory.subtitleFingerprintFor(playerPreferences, key)
      if (!fingerprint.isNullOrBlank()) {
        val trackId = PlaybackMemory.findSubtitleTrackId(fingerprint)
        if (trackId != null && trackId > 0) {
          runCatching { PlayerLib.setPropertyInt("sid", trackId) }
        }
      }
    }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (isEof) {
      // Check if we should repeat the current file
      if (viewModel.shouldRepeatCurrentFile()) {
        PlayerLib.command("seek", "0", "absolute")
        viewModel.unpause()
        return
      }

      // Handle playlist playback
      if (playlist.isNotEmpty()) {
        val hasNextItem = if (viewModel.shuffleEnabled.value) {
          shuffledPosition < shuffledIndices.size - 1
        } else {
          playlistIndex < playlist.size - 1
        }

        // Check if autoplay next video is enabled
        val autoplayEnabled = playerPreferences.autoplayNextVideo.get()
        // 整列表循环 == 永远有"下一集"（playNext 内部会把末尾接回开头）
        val repeatPlaylist = viewModel.shouldRepeatPlaylist()

        if (repeatPlaylist || (hasNextItem && autoplayEnabled)) {
          // 卡片在片尾倒数阶段已经弹过了，这里直接切，不再多等；
          // 只有用户在卡片上点过「取消」才会停住不动。
          onPlaybackEndedAutoplay()
        } else if (!hasNextItem && playerPreferences.closeAfterReachingEndOfVideo.get()) {
          // 只有"真的没有下一集可播"时才退出播放器（整个队列/单文件播放结束）。
          // autoplay 关闭但队列里还有后续时，属于"等用户手动切"，不能算播放结束，
          // 因此停住而不是退出 —— 否则会和"自动下一集"抢同一段判断。
          // isReady 兜底：从来没真正起播过（例如备用内核打开失败）时不能退出，
          // 否则表现就是"进播放器转两下就被踢回上一页"。
          if (isReady) finishAndRemoveTask()
        }
        // 其余情况（autoplay 关 且 队列还有后续）：停在当前视频，等用户手动切集
      } else {
        // Single video playback (no playlist)
        // 同样要求真的起播过：备用内核打开失败时不能把用户踢回上一页。
        if (playerPreferences.closeAfterReachingEndOfVideo.get() && isReady) {
          finishAndRemoveTask()
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup（Exo 内核下 mpv 从未初始化，只查 isFinishing）
        if (isFinishing) return
        if (engineKind == EngineKind.MPV && (!mpvInitialized || player.isExiting)) return

        val aspect = currentVideoAspect()
        Log.d(TAG, "video-params/aspect changed: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video aspect ratio changes (fixes Video orientation mode)
        // BUT: Don't update if aspect is being overridden (stretch/custom aspect mode)
        // to prevent infinite orientation switching loop
        val aspectOverride = PlayerLib.getPropertyDouble("video-aspect-override") ?: -1.0
        if (playerPreferences.orientation.get() == PlayerOrientation.Video &&
            aspect != null &&
            aspectOverride <= 0.0
        ) {
          // 仅首帧按宽高比设一次；之后冻结，切集不翻转（与 handleFileLoaded / w·h 处理器共用标记）
          if (!videoParamsOrientationHandled) {
            setOrientation()
            videoParamsOrientationHandled = true
          }
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   *
   * This method is called when an MPV property (with String value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    // Currently no String properties are handled
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(eventId: Int) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        handleFileLoaded()
        isReady = true
        // 文件刚起来、画面还什么都没有 ⇒ 重新显示加载层。
        // ⚠️ 必须在每次 loadfile 后复位：`isReady` 换片时不会清零，
        //    拿它当加载判据会导致「只有第一集有加载动画」。
        viewModel.onLoadingNewFile()
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
        // 首帧已经上屏 ⇒ 撤掉「出画面前」的加载层。
        // 判据为什么用这个事件而不是 vo-configured，见 PlayerViewModel.firstFrameRendered 的注释。
        viewModel.onFirstFrameRendered()
        updateRecentlyPlayedDurationIfAvailable()
      }
    }
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    // 文件真的起来了 ⇒ 之前那次「无法播放」的判定作废（可能是地址临时过期，
    // 重试后拿到了新的直链）。不清的话错误卡片会一直压在画面上。
    viewModel.clearPlaybackError()
    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (networkFilePaths.isNotEmpty()) {
      // 网络队列连播：换片后必须按当前下标重算进度键（getMediaIdentifier 内部
      //    会取 networkFilePaths[playlistIndex]），否则第二片起存/取进度都落在
      //    第一片头上。单文件播放（networkFilePaths 为空）不走这里，行为不变。
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    // Start media notification service (like YouTube - always show notification)
    // 服务经由 PlayerLib 门面驱动，mpv / Exo 内核都能用（Exo 下缩略图为空，其余一致）
    startBackgroundPlayback()

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    // 换片了：网速采样的基线（fw-bytes 增量）与平滑历史一并清掉，
    // 否则新片开头那一秒会把上一条的缓冲量算进来，速度显示跳变
    MpvNetSpeedSampler.reset()

    // 落盘缓存超限就先清一遍（清单 #18）。mpv 自己的 LRU 只管「单条流不超过
    // cache-persize」，**不管总量** —— 连看 10 部片子总占用早就撑爆了，
    // 到那时 mpv 写不进去，表现为「缓存开着但没效果」。
    // ⚠️ 必须扔到后台线程：之前这行就在主线程上裸跑，而 [MpvDiskCache.trimIfNeeded]
    // 会 `deleteRecursively()` 几百 MB ~ 2GB 的缓存目录 —— 这里是 handleFileLoaded，
    // 正好卡在「首帧要上屏」的时机上，删得越久画面越是定住不动。
    // 缓存容量越大越慢，所以开着大缓存播大文件时表现为「卡成幻灯片」。
    // mpv 自己写缓存是异步的，晚几百毫秒清一次完全不影响这次播放。
    val diskCacheLimitMb = playerPreferences.diskCacheSizeMb.get()
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching { MpvDiskCache.trimIfNeeded(this@PlayerActivity, diskCacheLimitMb) }
    }

    setIntentExtras(intent.extras)

    // ── 弹幕：文件真正加载完成时补一次「换集」通知 ──
    // ⚠️ 首次起播**不走** [loadPlaylistItemInternal]：`onCreate` 里是直接
    //   `player.playFile(uri)`（见 L695），那条路径不会通知弹幕状态机。
    //   于是 `pendingTitle` 一直是空串 —— 线上日志里 `setEnabled(true)：pending=""`、
    //   `弹幕灌数据：… key=` 就是这么来的：
    //   ① 自动匹配永远拿不到片名，弹幕只能靠手动搜番；
    //   ② 「打开开关时补拉」这条兜底路径永远死在「还没拿到片名」上。
    // 放在这里的理由：此刻 `fileName` 已经定稿（上面刚处理完）、`playlistIndex`
    // 指向正确的一条、intent 里的剧集键也都在。切集时 [loadPlaylistItemInternal]
    // 已经通知过一次，这里再通知一次是**幂等**的（同一 key 不会重新拉）——
    // 因为只有 `episodeKey` 变了引擎才重建，网络请求也只在自动匹配时发一次。
    // ⚠️ 用 `playlistIndex` 而不是 0：单条播放时它是 0，队列播放时它已被切集流程更新。
    notifyDanmakuEpisodeChanged(playlistIndex)

    // mpv 已经换成新文件：轨道列表重建了，外挂字幕跟踪表必须跟着清。
    // 不清的话恢复流程会以为「这条字幕已经加过了」而跳过，字幕就再也加不回来。
    viewModel.resetExternalSubtitles()

    // 先落媒体标题、再进下面的恢复流程 —— 顺序很关键：
    // 1) setMediaTitle 会 clear() 外挂字幕列表。若它排在外挂字幕恢复**之后**，刚恢复的记录
    //    会被抹掉 → 退出时保存成空 → 下次打开无从恢复。这正是「外挂字幕记不住」的根因之一。
    // 2) 它还负责把 zoom / pan 归零，也必须早于 applyPlaybackState 里的 zoom 恢复。
    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    if (!isCurrentStreamM3U()) {
      PlayerLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state. 这几步的**顺序**是外挂字幕能记住的关键：
      // 1) loadVideoPlaybackState 里会把存档中的外挂字幕加回 mpv，并等轨道真正挂上；
      // 2) 「同名外挂字幕自动加载」与 TrackSelector 并行跑（网络目录列举可能很慢，
      //    不能挡住音轨偏好等其他恢复步骤）；有存档时不抢选择（autoSelectFirst = false）；
      // 3) 等自动加载也挂完，最后才把存档里的字幕选择套一次 —— 此时所有字幕轨都已就位。
      // Load playback state (will skip track restoration if preferred language configured)
      val hasState = loadVideoPlaybackState(fileName)

      val autoloadJob = launch { autoloadMatchingSubtitles(autoSelectFirst = !hasState) }

      // Apply track selection logic (defaults only apply when no saved state)
      trackSelector.onFileLoaded(hasState)

      // 「每部剧记住速度 / 音轨」：必须在 TrackSelector 之后，
      // 否则会被默认语言逻辑覆盖掉用户上次手动选的音轨。
      applyRememberedPlaybackSettings()

      // 起一个盯梢协程：片尾倒数阶段把「下一集」卡片推出来
      autoplayCancelled = false
      // 新的一集 ⇒ 用户上一集叉掉「下一集」卡片的决定作废，重新给一次提示机会
      nextUpDismissed = false
      withContext(Dispatchers.Main) { startAutoplayCountdownWatcher() }

      // 起片头片尾盯梢（b2+a1）：拉精准分段 + 自动跳 / 手动胶囊
      withContext(Dispatchers.Main) { startSkipSegmentWatcher() }

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          PlayerLib.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }

      // Apply saved aspect ratio setting
      withContext(Dispatchers.Main) {
        val savedAspect = playerPreferences.defaultVideoAspect.get()
        val savedCustomRatio = playerPreferences.defaultCustomAspectRatio.get()
        
        if (savedCustomRatio > 0) {
          // Apply custom aspect ratio
          viewModel.setCustomAspectRatio(savedCustomRatio)
        } else {
          // Apply standard aspect mode (Fit, Crop, or Stretch)
          viewModel.changeVideoAspect(savedAspect, showUpdate = false)
        }
      }

      // 等自动加载的同名字幕也挂上之后，再确定性地套一次存档里的字幕选择。
      // 这里不设死等：网络目录列举可能很慢，最多等 1.2s 就照旧套存档 —— 宁可忽略
      // 一条同名外挂字幕，也不能把「记住的字幕」拖着不显示。
      withTimeoutOrNull(1200) { autoloadJob.join() }
      applyRestoredSubtitleSelection()
    }

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (playlist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (playlistIndex >= 0 && playlistIndex < playlist.size) {
          saveRecentlyPlayedForUri(playlist[playlistIndex], fileName)
        } else {
          Log.w(TAG, "Cannot save recently played: invalid playlist index $playlistIndex (playlist size: ${playlist.size})")
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // 用户手动指定过方向时直接沿用：cycleScreenRotations 已经直接改写 requestedOrientation，
    // 这里**不再**调 setOrientation()（否则会按宽高比把它覆盖掉），保证本次会话内切集沿用。
    if (viewModel.manualOrientationOverrideValue != null) {
      // no-op：保持用户手动选择的方向
    } else if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      // 非「按视频」模式：方向是确定的（横/竖/自由），直接套用（幂等，切集不会翻转）
      setOrientation()
    } else {
      // 「按视频」模式：仅在首帧按宽高比设一次方向，之后冻结，
      // 切集不再随视频内容（竖屏/横屏）翻转 —— 符合「切集不变方向」的诉求。
      // 注意：videoParamsOrientationHandled 一旦置真，本会话内再也不自动改方向。
      if (!videoParamsOrientationHandled) {
        lifecycleScope.launch {
          kotlinx.coroutines.delay(100)
          if (isFinishing) return@launch
          if (engineKind == EngineKind.MPV && (!mpvInitialized || player.isExiting)) return@launch
          val aspect = currentVideoAspect()
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
            videoParamsOrientationHandled = true
          }
        }
      }
    }

    applySubtitlePreferences()

    // 黑边自动裁切：开关打开则探测并裁掉四周黑边；关掉的路径也要清一次 ——
    // 裁切是挂在 mpv 的 vf 链上的，不会随换片自动消失，不清就会带着上一条的黑边偏移播。
    viewModel.applyAutoCropPolicy()

    viewModel.unpause()

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle()

    // 起播后按需预热下一集（受「视频预加载」开关控制）
    maybeStartNextVideoPreload()
  }

  // ==================== Next Video Preload ====================

  /**
   * 在视频加载完成后启动「下一集预加载」的等待协程。
   *
   * 只有在开关打开、确实存在下一集、且下一集是 http(s) 流时才动作；
   * 本地文件 / content:// 不需要预热。
   */
  private fun maybeStartNextVideoPreload() {
    preloadJob?.cancel()
    preloadJob = null

    if (!playerPreferences.preloadNextVideo.get()) return

    // 本集已经发起过预加载，避免 seek 回开头时重复触发
    if (preloadRequestedForIndex == playlistIndex) return

    val nextIndex = peekNextPlaylistIndex() ?: return
    val nextUri = playlist.getOrNull(nextIndex) ?: return
    val scheme = nextUri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return

    val uriString = nextUri.toString()
    preloadRequestedForIndex = playlistIndex
    Log.d(TAG, "preloadNextVideo: scheduled for playlist index $nextIndex")

    preloadJob =
      lifecycleScope.launch(Dispatchers.IO) {
        val self = coroutineContext[Job]
        // 等当前视频真正播起来，避免起播瞬间和首帧解码抢带宽
        while (self?.isActive == true) {
          if (isFinishing || player.isExiting) return@launch
          val pos = runCatching { PlayerLib.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
          if (pos >= PlayerPreferences.PRELOAD_TRIGGER_SECONDS) break
          delay(250)
        }
        if (self?.isActive != true) return@launch
        preloadUrl(uriString, self)
      }
  }

  /**
   * 计算「下一集」在 [playlist] 中的下标，语义与 playNext 保持一致；
   * 没有下一集（且未开启整列表循环）时返回 null。
   */
  private fun peekNextPlaylistIndex(): Int? {
    if (playlist.isEmpty()) return null
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    return if (viewModel.shuffleEnabled.value) {
      if (shuffledIndices.isEmpty()) generateShuffledIndices()
      when {
        shuffledPosition < shuffledIndices.size - 1 -> shuffledIndices[shuffledPosition + 1]
        viewModel.shouldRepeatPlaylist() && shuffledIndices.isNotEmpty() -> shuffledIndices[0]
        else -> null
      }
    } else {
      when {
        playlistIndex < effectiveSize - 1 -> playlistIndex + 1
        viewModel.shouldRepeatPlaylist() -> 0
        else -> null
      }
    }
  }

  /**
   * 对 [url] 发一个带 Range 头的 GET，读完开头 [PlayerPreferences.PRELOAD_BYTES] 字节后立即断开。
   *
   * 走的是直链（Emby 的 URL 里已带 api_key），因此无需额外鉴权头。
   * 任何异常（含切集导致的中止）都只记日志，绝不影响正常播放。
   */
  private suspend fun preloadUrl(
    url: String,
    job: Job?,
  ) {
    val bytesToRead = PlayerPreferences.PRELOAD_BYTES
    try {
      val request =
        Request.Builder()
          .url(url)
          .header("Range", "bytes=0-${bytesToRead - 1}")
          .get()
          .build()

      preloadHttpClient.newCall(request).execute().use { response ->
        response.body.byteStream().use { stream ->
          val buffer = ByteArray(64 * 1024)
          var readTotal = 0L
          while (readTotal < bytesToRead && job?.isActive == true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            readTotal += n
          }
          Log.d(TAG, "preloadNextVideo: read ${readTotal}B (code=${response.code})")
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "preloadNextVideo: aborted (${e.javaClass.simpleName}: ${e.message})")
    }
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null && betterFilename.isNotBlank() &&
          betterFilename != fileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream"
        ) {

          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          // Update fileName
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            PlayerLib.setPropertyString("force-media-title", fileName)
            viewModel.setMediaTitle(fileName)

// Update media session
            val durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
  title = fileName,
    durationMs = durationMs,
            )

        // Update background service if connected
            if (serviceBound && mediaPlaybackService != null) {
            val artist = runCatching { PlayerLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
        // 截图搬回后台（清单 #20 必修 4）。
   // `MPVLib.grabThumbnail` 在 AAR 里是「另写的一套像素转换」——
    // 它会把当前帧从解码器里拉出来、在 native 侧转成 Bitmap 再跨 JNI 传回来，
     // 1080 宽一次约 2.6MB，而且**同步阻塞调用方**。
                // 放在 `withContext(Dispatchers.Main)` 里就是拿 UI 线程去等一次硬解+内存分配。
  launch {
        val thumbnail = withContext(Dispatchers.IO) {
                  runCatching { PlayerLib.grabThumbnail(NOTIFICATION_THUMBNAIL_SIZE) }.getOrNull()
     }
   // 服务可能在截图期间被解绑/销毁，回来时重新判一次
   if (serviceBound && mediaPlaybackService != null) {
     mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
   }
              }
            }
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else null
              } ?: uri.toString()
            }

            else -> uri.toString()
          }

          // Get duration and file size from MPV
          val updatedDuration = runCatching {
            (PlayerLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
          }.getOrDefault(0L)

          val updatedFileSize = runCatching {
            // Try multiple properties to get file size
            PlayerLib.getPropertyDouble("file-size")?.toLong()
              ?: PlayerLib.getPropertyDouble("stream-end")?.toLong()
              ?: 0L
          }.getOrDefault(0L)

          // Get video resolution from MPV
          val updatedWidth = runCatching {
            PlayerLib.getPropertyInt("width") ?: PlayerLib.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

          val updatedHeight = runCatching {
            PlayerLib.getPropertyInt("height") ?: PlayerLib.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

          // Update metadata without thumbnail
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              fileName,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata: $fileName (duration: ${updatedDuration}ms, size: ${updatedFileSize}B, resolution: ${updatedWidth}x${updatedHeight}) for $filePath",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    // Typography settings
    PlayerLib.setPropertyString("sub-font", subtitlesPreferences.font.get())
    PlayerLib.setPropertyString("secondary-sub-font", subtitlesPreferences.font.get())
    PlayerLib.setPropertyInt("sub-font-size", subtitlesPreferences.fontSize.get())
    PlayerLib.setPropertyBoolean("sub-bold", subtitlesPreferences.bold.get())
    PlayerLib.setPropertyBoolean("sub-italic", subtitlesPreferences.italic.get())
    PlayerLib.setPropertyString("sub-justify", subtitlesPreferences.justification.get().value)
    PlayerLib.setPropertyString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    PlayerLib.setPropertyInt("sub-outline-size", subtitlesPreferences.borderSize.get())
    PlayerLib.setPropertyInt("sub-shadow-offset", subtitlesPreferences.shadowOffset.get())

    // Color settings
    PlayerLib.setPropertyString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    PlayerLib.setPropertyString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    PlayerLib.setPropertyString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())

    // Miscellaneous settings
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    PlayerLib.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
    PlayerLib.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")

    // ── 字号基准：跟随画面 ──
    // mpv 的 sub-font-size 单位是「窗口高 720 时的缩放像素」，实际大小随**窗口高度**走。
    // 只设 sub-scale-by-window 时 mpv 默认还开着 sub-scale-with-window，于是
    // sd_ass.c 里 `set_font_scale *= dim->h / 画面在窗口内的高度`：竖屏窗口高、画面只占
    // 中间一条 → 字号被放大 3~4 倍；横屏画面铺满 → 约 1 倍。这正是
    // 「横屏调好竖屏太大、竖屏调好横屏太小」的根因。
    // 关掉 sub-scale-with-window 后字号改为**按画面高度**等比（mpv 手册原话：
    // "To make the font size scale with video size instead, set only
    //  --sub-scale-with-window to no"），横竖屏占画面的比例由此一致。
    val followPicture = subtitlesPreferences.followPictureScale.get()
    val withWindow = if (followPicture) "no" else "yes"
    PlayerLib.setPropertyString("sub-scale-with-window", withWindow)
    PlayerLib.setPropertyString("secondary-sub-scale-with-window", withWindow)
    // sub-scale-by-window 保持 mpv 默认 yes：它只管「窗口变化时要不要重算」，不影响基准。
    PlayerLib.setPropertyString("sub-scale-by-window", "yes")
    PlayerLib.setPropertyString("secondary-sub-scale-by-window", "yes")

    // ── 位置基准：贴画面底部 ──
    // 关掉 sub-use-margins 后字幕不再往上下黑边里跑，横竖屏都紧贴画面下沿。
    // 配合上面的「跟随画面」，字号与位置一起随画面走，方向一变不会互相打架。
    PlayerLib.setPropertyString("sub-use-margins", "no")
    PlayerLib.setPropertyString("secondary-sub-use-margins", "no")

    PlayerLib.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
    PlayerLib.setPropertyInt("sub-pos", subtitlesPreferences.subPos.get())

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  @OptIn(ExperimentalStdlibApi::class)
  private fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

  /**
   * Saves the current playback state to the database.
   *
   * @param mediaTitle The title of the media being played
   * @param isSync If true, executes synchronously on the calling thread with runBlocking.
   *               Essential during onPause when finishing so the state is written before MainActivity resumes.
   */
  private fun saveVideoPlaybackState(mediaTitle: String, isSync: Boolean = false) {
    if (mediaIdentifier.isBlank()) return

    // Cancel any previous pending save operation
    savePlaybackStateJob?.cancel()

    // Read values from MPV immediately while it is still loaded (before stop/destroy or coroutine delay)
    val currentPos = (PlayerLib.getPropertyInt("time-pos") ?: viewModel.pos ?: 0).coerceAtLeast(0)
    val currentDuration = (PlayerLib.getPropertyInt("duration")?.takeIf { it > 0 } ?: (viewModel.duration ?: 0)).coerceAtLeast(0)
    val speed = PlayerLib.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED
    val videoZoom = PlayerLib.getPropertyDouble("video-zoom")?.toFloat() ?: 0f
    val currentSid = PlayerLib.getPropertyInt("sid") ?: -1
    val currentSecondarySid = PlayerLib.getPropertyInt("secondary-sid") ?: -1
    val (effectiveSid, effectiveSecondarySid) = if (currentSid <= 0 && currentSecondarySid > 0) {
      currentSecondarySid to -1
    } else {
      currentSid to currentSecondarySid
    }
    val subDelay = ((PlayerLib.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val subSpeed = PlayerLib.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED
    val aid = PlayerLib.getPropertyInt("aid") ?: -1
    val audioDelay = ((PlayerLib.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val externalSubs = viewModel.externalSubtitles.joinToString("|")
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val saveOnQuit = playerPreferences.savePositionOnQuit.get()
    // 多版本媒体：这一条属于哪个「版本」？键由 (服务器, 条目, 媒体源) 拼出，详情页用同一个键读数。
    // ⚠️ 必须在**主线程、mpv 还活着**的时候读（要读 mpv 当前路径），与上面那批属性一起取。
    // 只有「记住播放位置」开着才写：按版本进度是本地续播的一部分，开关关掉就该整体不生效。
    val embyVersionKey = if (saveOnQuit) currentEmbyVersionProgressKey() else null

    val saveBlock: suspend () -> Unit = {
      runCatching {
        val oldState = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
        Log.d(TAG, "Saving playback state for: $mediaTitle (identifier: $mediaIdentifier)")

        val duration = if (currentDuration > 0) {
          currentDuration
        } else {
          (oldState?.lastPosition ?: 0) + (oldState?.timeRemaining ?: 0)
        }

        val lastPosition = if (!saveOnQuit) {
          oldState?.lastPosition ?: 0
        } else if (duration > 0 && currentPos >= duration - 1) {
          0
        } else {
          currentPos
        }

        val timeRemaining = if (duration > lastPosition) duration - lastPosition else 0

        val hasBeenWatched = run {
          val durationSeconds = duration.toFloat()
          val isFinished = (durationSeconds > 0) && (currentPos >= durationSeconds - 1)
          val progress = if (durationSeconds > 0) currentPos.toFloat() / durationSeconds else 0f
          val isCurrentlyWatched = progress >= (watchedThreshold / 100f)
          val oldProgress = if (durationSeconds > 0) lastPosition.toFloat() / durationSeconds else 0f
          val wasWatchedThisSession = oldProgress >= (watchedThreshold / 100f)

          isCurrentlyWatched || isFinished || wasWatchedThisSession || (oldState?.hasBeenWatched == true)
        }

        val entity =
          PlaybackStateEntity(
            mediaTitle = mediaIdentifier,
            lastPosition = lastPosition,
            playbackSpeed = speed,
            videoZoom = videoZoom,
            sid = effectiveSid,
            secondarySid = effectiveSecondarySid,
            subDelay = subDelay,
            subSpeed = subSpeed,
            aid = aid,
            audioDelay = audioDelay,
            timeRemaining = timeRemaining,
            externalSubtitles = externalSubs,
            hasBeenWatched = hasBeenWatched,
          )
        playbackStateRepository.upsert(entity)
        // 多版本媒体：再按「版本」记一份。
        // Emby 服务端的进度是按**条目**存的 —— 4K 与 1080P 共用一个续播点，服务端层面无解。
        // 所以在本地多记一条（键见 EmbyVersionProgress），详情页就能让每个版本各算各的进度。
        embyVersionKey?.let { playbackStateRepository.upsert(entity.copy(mediaTitle = it)) }
        Log.d(TAG, "Playback state saved successfully: pos=$lastPosition, duration=$duration, remaining=$timeRemaining, watched=$hasBeenWatched")
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state", e)
      }
    }

    if (isSync) {
      // 退出路径（`isFinishing` / `isUserFinishing`）原先是
      // `runBlocking(Dispatchers.IO) { saveBlock() }` —— 看着「切了 IO 就不卡主线程」，
      // 其实**完全误解了**：`runBlocking` 阻塞的是**调用线程**，
      // 写成 `Dispatchers.IO` 只是让 `saveBlock` 在 IO 线程执行，
      // 主线程照样原地等到磁盘写完。按返回键退出时每次都卡一下，
      // 数据库慢一点（首次建表 / WAL fsync / 磁盘忙）就是 ANR。
      // 关键在于**该保住的东西已经保住了**：上面 `currentPos` / `currentDuration` /
      // `speed` / `sid` … 全都在**主线程同步从 mpv 读完了**（mpv 属性读取是纳秒级的
      // 内存读，不涉及 IO），`saveBlock` 内部只剩 `getVideoDataByTitle` + `upsert`
      // 两个 Room 调用。所以改成异步**不会丢任何数据** ——
      // 进度值已经以本地变量的形式被捕获进闭包了。
      // 那 `isSync` 这个参数还需要吗？需要，但要重新定义它：
      // 它的意义从「同步写库」变成「**持有这个 Job 引用**」——
      // 退出时把 job 记进 [savePlaybackStateJob]，onDestroy 里好去 join/取消。
      savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO) {
        saveBlock()
      }
    } else {
      savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO) {
        saveBlock()
      }
    }
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(mediaTitle: String): Boolean {
    // 「从头播放」是一次性标记：这里读出后立刻清掉，避免影响后面的剧集。
    // 「整场从头播放」（随机播放入口）是会话级标记：整个会话都不清，切到哪个视频都从头放。
    val playFromStart = playFromStartOnce || playFromStartAllSession
    playFromStartOnce = false

    // 先清掉上一集留下的「待恢复字幕选择」，避免 mediaIdentifier 为空直接 return 时
    // 把上一集的 sid 误套到本集上（会连累 applyRestoredSubtitleSelection）。
    restoredSubtitleSid = NO_SAVED_SUBTITLE_SELECTION
    restoredSecondarySubtitleSid = NO_SAVED_SUBTITLE_SELECTION

    if (mediaIdentifier.isBlank()) return false

    return runCatching {
      val state = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)

      applyPlaybackState(state, playFromStart)
      applyDefaultSettings(state)

      state != null
    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * 本次加载从存档里读到的字幕选择（主 / 副）。哨兵值见 [NO_SAVED_SUBTITLE_SELECTION]。
   * 先记下来、等所有字幕来源都挂上之后再统一套 —— 见 [applyRestoredSubtitleSelection]。
   */
  private var restoredSubtitleSid: Int = NO_SAVED_SUBTITLE_SELECTION

  private var restoredSecondarySubtitleSid: Int = NO_SAVED_SUBTITLE_SELECTION

  /**
   * 自动加载与视频同名的外挂字幕（本地同目录 / 同网络路径），受设置开关控制。
   *
   * @param autoSelectFirst 有存档时传 false：只把字幕挂上去、**不选**，选哪条留给存档说了算。
   *   否则这里会把用户上次手动选的那条顶掉，「记住的字幕」看起来就没生效。
   */
  private suspend fun autoloadMatchingSubtitles(autoSelectFirst: Boolean) {
    if (!subtitlesPreferences.autoloadMatchingSubtitles.get()) return
    runCatching {
      // For network files played via proxy (SMB/WebDAV/FTP), use the original network file path
      val networkFilePath = intent.getStringExtra("network_file_path")
      val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
      // 队列连播时按当前下标取本片的网络路径（同 networkFilePaths 语义），
      // 否则第二片起会拿第一片的目录去找字幕，要么找不到、要么挂错片子的字幕
      val networkFilePathForItem = networkFilePaths.getOrNull(playlistIndex) ?: networkFilePath

      if (networkFilePathForItem != null && networkConnectionId != -1L) {
        // Pass network file path and connection ID for subtitle discovery
        SubtitleOps.autoloadSubtitles(
          videoFilePath = networkFilePathForItem,
          videoFileName = fileName,
          networkConnectionId = networkConnectionId,
          autoSelectFirst = autoSelectFirst,
        )
      } else {
        // Regular file or direct network stream
        val filePath = parsePathFromIntent(intent)
        if (filePath != null) {
          SubtitleOps.autoloadSubtitles(
            videoFilePath = filePath,
            videoFileName = fileName,
            autoSelectFirst = autoSelectFirst,
          )
        }
      }
    }.onFailure { e -> Log.e(TAG, "Autoload subtitles failed", e) }
  }

  /**
   * 把存档里的字幕选择**最后一次**确定地套到播放器上。
   *
   * 必须等「存档外挂字幕 + 同名自动加载 + 下载目录扫描」都做完之后再调：这些来源都是
   * 异步挂轨的，谁先挂上不确定，而「上次选的是哪条」只能以存档为准 ——
   *   · 早一步设 `sid`，轨道可能还不存在，mpv 会把这次选择丢掉（外挂字幕就是这样丢的）；
   *   · 晚一步又会被自动加载的 `select` 抢走。
   * 所以统一收口在这里，一次套准。三种情况：选中某条 / 只选了副字幕 / 上次就没字幕。
   */
  private fun applyRestoredSubtitleSelection() {
    // 外部应用经 intent 显式指定要启用的字幕（subs.enable）优先级最高 —— 那是用户在
    // 文件管理器里点「用影屿播放 + 带上这条字幕」，不该被存档里的旧选择盖掉。
    if (intent.hasExtra("subs.enable")) return

    val sid = restoredSubtitleSid
    if (sid == NO_SAVED_SUBTITLE_SELECTION) return
    val secondarySid = restoredSecondarySubtitleSid

    when {
      sid > 0 -> {
        if (player.sid != sid) player.sid = sid
        if (secondarySid > 0 && secondarySid != sid) {
          if (player.secondarySid != secondarySid) player.secondarySid = secondarySid
        } else if (player.secondarySid > 0) {
          player.secondarySid = -1
        }
        Log.d(TAG, "Restored subtitle selection: sid=$sid, secondary=$secondarySid")
      }

      secondarySid > 0 -> {
        // 只有副字幕：提到主字幕（单条字幕必须贴底显示）
        player.secondarySid = -1
        player.sid = secondarySid
        Log.d(TAG, "Promoted saved secondary subtitle track $secondarySid to primary (single subtitle must stay at bottom)")
      }

      else -> {
        // 上次没有选中任何字幕：保持关闭 —— 否则「同名自动加载」或文件自带的默认轨会把它
        // 重新打开，用户会以为「我把字幕关了」这个选择没被记住。
        if (player.sid > 0) {
          player.sid = -1
          Log.d(TAG, "Restored 'subtitles off' state from saved state")
        }
        if (player.secondarySid > 0) player.secondarySid = -1
      }
    }
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio track selection, and playback speed.
   * 字幕的**选中**不在这里做（见 [applyRestoredSubtitleSelection]），本函数只负责把
   * 存档里的外挂字幕加回 mpv 并等它们真正挂上。
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   * @param playFromStart true 表示用户显式点了「从头播放」，本地续播位置一律不恢复
   */
  private suspend fun applyPlaybackState(
    state: PlaybackStateEntity?,
    playFromStart: Boolean = false,
  ) {
    if (state == null) {
      // 没有存档：字幕交给 TrackSelector 按偏好决定，这里不记任何「待恢复的选择」
      restoredSubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      restoredSecondarySubtitleSid = NO_SAVED_SUBTITLE_SELECTION
      return
    }

    // 先把存档里的字幕选择记下来，等所有字幕来源（存档外挂字幕 / 同名自动加载 /
    // 下载目录扫描）都挂上之后，再由 applyRestoredSubtitleSelection() 统一定夺。
    restoredSubtitleSid = state.sid
    restoredSecondarySubtitleSid = state.secondarySid

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first.
    // ⚠️ 这里必须**等轨道真正挂进 track-list** 再往下走：`sub-add` 是异步命令，
    // 命令返回时轨道还没建出来，紧接着设 `sid` 会被 mpv 当成「不存在的轨道」直接丢弃
    // —— 外挂字幕「重开 app 就没了」正是这个顺序造成的。
    // （内嵌字幕轨在文件加载时就已经存在，所以只有外挂字幕会中招。）
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      // 已经被跟踪的（例如刚被下载目录扫描加过）不会再下发 sub-add，等轨道数时要按
      // 实际会新增的条数算，否则会白等一个超时。
      val pendingCount = externalSubUris.count { !viewModel.externalSubtitles.contains(it) }
      val tracksBefore = PlayerLib.getPropertyInt("track-list/count") ?: 0

      for (subUri in externalSubUris) {
        // 用可挂起的版本：加完再往下，不再即发即忘
        viewModel.loadSubtitle(Uri.parse(subUri), select = false, silent = true)
      }

      if (pendingCount > 0) {
        viewModel.awaitTrackCountAtLeast(tracksBefore + pendingCount)
      }
    }

    // 字幕轨的选中**不在这里做**：此刻「同名外挂字幕自动加载」可能还没挂上，
    // 统一留到 applyRestoredSubtitleSelection()（排在自动加载之后）一次套准。
    // 音轨不依赖异步添加，照旧在这里恢复。
    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    PlayerLib.setPropertyDouble("sub-delay", subDelay)
    PlayerLib.setPropertyDouble("speed", state.playbackSpeed)
    PlayerLib.setPropertyDouble("audio-delay", audioDelay)
    PlayerLib.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    PlayerLib.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    when {
      // 显式「从头播放」：本地续播记录一律不生效（详情页的「从头播放」按钮）
      playFromStart -> {
        PlayerLib.setPropertyInt("time-pos", 0)
        Log.d(TAG, "play_from_start: ignore saved position (${state.lastPosition}s), seek to 0")
      }

      playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0 -> {
        val saved = state.lastPosition.toDouble()
        val duration = PlayerLib.getPropertyDouble("duration")
        // 续播位置离片尾太近（例如上一次是"播完/假播完"退出的，位置就停在 duration 上）：
        // 直接从头放。否则一开局就被 seek 到结束点，IJK 立刻回调播放完成，
        // 上层把它当成「播放结束」自动退出 —— 表现就是"一进播放器就被踢回详情页"。
        val tooCloseToEnd = duration != null && duration > 0.0 && duration - saved < RESUME_TAIL_GUARD_SEC
        if (tooCloseToEnd) {
          Log.w(
            TAG,
            "saved position ${saved}s is within $RESUME_TAIL_GUARD_SEC s of the end ($duration s); restart from 0",
          )
          PlayerLib.setPropertyInt("time-pos", 0)
        } else {
          PlayerLib.setPropertyInt("time-pos", state.lastPosition)
        }
      }
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      PlayerLib.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Get parsed video title from MPV
      // （媒体标题可能落成整条播放直链，存进库前先给 api_key 打码 —— 这条会显示在「最近播放」里）
      val videoTitle = runCatching {
        redactUrlSecrets(PlayerLib.getPropertyString("media-title"))
      }.getOrNull()?.takeIf { it.isNotBlank() && it != fileName }

      // Get duration and file size from MPV
      var duration = runCatching {
        (PlayerLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      if (duration <= 0L) {
        duration = getVideoDurationFromUri(uri)
      }

      val fileSize = runCatching {
        // Try multiple properties to get file size
        PlayerLib.getPropertyDouble("file-size")?.toLong()
          ?: PlayerLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        PlayerLib.getPropertyInt("width") ?: PlayerLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        PlayerLib.getPropertyInt("height") ?: PlayerLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val resultIntent =
      Intent(RESULT_INTENT).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // Update the intent first so getFileName uses the new intent data
    setIntent(intent)

    // 本页是 singleTask，第二次「起播放」走的是这里而不是 onCreate，
    // 所以默认内核的转交判断也得在这儿兜一次，否则「默认内核 = GSY」只在冷启动生效。
    // 守卫条件（带没带播放内容）在方法内部统一处理，两处口径一致。
    if (redirectToPreferredEngine()) return

    // 每个新 intent 都重新判定「从头播放」标记（不跨 intent 存活）
    playFromStartOnce = intent.getBooleanExtra("play_from_start", false)
    // 随机播放入口：整场从头放（连播切集也不恢复进度）
    playFromStartAllSession = intent.getBooleanExtra("play_from_start_all", false)

    // Check if this intent has playlist information
    val hasPlaylistExtras = intent.hasExtra("playlist_id") ||
      intent.hasExtra("playlist")

    // 交接单带来的亮度（从 GSY 切回来）：存起来让下一次 onStart 消费。
    // ⚠️ 必须在下面处理 playlist 之前读 —— 两者互不相干，但顺序读起来更清楚。
    handoffBrightness = PlayerHandoff.read(intent).brightness

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // Only update playlist state if we have new playlist information
    // This prevents losing the playlist when coming back from notification/PiP
    if (hasPlaylistExtras || playlistFromIntent.isNotEmpty()) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = -1
      playlist = playlistFromIntent
      // 标题列表必须和 playlist 同步替换，否则切集时会用到上一批的标题
      playlistTitles = intent.getStringArrayListExtra("playlist_titles") ?: emptyList()
      // 记忆键同理：换了队列就换键，否则会把上一部剧的速度套到新剧上
      playlistSeriesKeys = intent.getStringArrayListExtra("playlist_series_keys") ?: emptyList()
      // 网络路径表同理：换队列必须整份替换，否则新队列会拿旧队列的路径当进度键
      networkFilePaths = intent.getStringArrayListExtra("network_file_paths") ?: emptyList()
      singleSeriesKey = intent.getStringExtra("emby_series_key")
      // 剧名同理：换了队列就必须整份替换，否则弹幕会拿上一部剧的名字去搜番
      playlistSeriesNames = intent.getStringArrayListExtra("playlist_series_names") ?: emptyList()
      singleSeriesName = intent.getStringExtra("emby_series_name")
      // 多版本名同理：换了队列就换，否则会显示上一批的版本名
      playlistMediaSourceNames =
        intent.getStringArrayListExtra("emby_media_source_names") ?: emptyList()
      // ItemId 列表同理：换了队列就换，否则会拿到上一批条目的媒体信息
      playlistItemIds =
        intent.getStringArrayListExtra(
          app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter.EXTRA_ITEM_IDS,
        ) ?: emptyList()
      // 多版本源映射同理：换了队列就重建
      embyMediaSourceByItemId = buildEmbyMediaSourceMap(intent)
      viewModel.seriesKey = currentSeriesKey()
      // 换了播放队列，允许对新的下一集重新预加载
      preloadRequestedForIndex = -1
    }

    // If playlist is empty but playlist_id is provided, load from database
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          val totalCount = playlistRepository.getPlaylistItemCount(pid)
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          withContext(Dispatchers.Main) {
            playlist = items
            playlistTotalCount = totalCount
            Log.d(TAG, "onNewIntent: Loaded ${items.size} items from playlist $pid")
          }
        } catch (e: Exception) {
          Log.e(TAG, "onNewIntent: Failed to load playlist from database", e)
        }
      }
    }

    // Auto-generate playlist from folder if playlist mode is enabled and no playlist_id
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        generatePlaylistFromFolder(path)
      }
    }

    // Extract the new fileName before loading the file
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE loading the new file
    setHttpHeadersFromExtras(intent.extras)

    // Load the new file
    // ⚠️ 复位必须**同步**做在 loadfile 之前：下面那次 loadfile 跑在 Dispatchers.Default，
    //   放到协程里会有「协程先把新文件load完、复位才执行」的竞态
    //   ⇒ 加载层刚要撤又被复位成显示，画面已经出来了还在转圈。
    //   （FILE_LOADED 事件里那次复位是兜底，两者都要有。）
    viewModel.onLoadingNewFile()
    getPlayableUriForEngine(intent)?.let { uri ->
      // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
      lifecycleScope.launch(Dispatchers.Default) {
        PlayerLib.command("loadfile", uri)
      }
    }
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)

    binding.controls.alpha = if (isInPictureInPictureMode) 0f else 1f

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    binding.controls.alpha = 0f

    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    val orientationPref = playerPreferences.orientation.get()

    requestedOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect = runCatching { player.getVideoOutAspect() }.getOrNull()
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Aspect not available yet - wait for video-params/aspect update
            Log.d(TAG, "setOrientation - Aspect not available, defaulting to landscape")
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          } else {
            // Aspect available - set correct orientation now
            val orientation = if (aspect > 1.0) {
              Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
              Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
  }

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
        // 偏好见 设置 → 手势 → 媒体键（mediaPlayGesture）
        viewModel.handleMediaPlayPause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_NEXT -> {
        viewModel.handleMediaNext()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
        viewModel.handleMediaPrevious()
        return true
      }

      else -> {
        // input.conf 按键映射是 mpv 专属；Exo 下没有这个体系
        if (engineKind == EngineKind.MPV) event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    if (engineKind == EngineKind.MPV) {
      event?.let {
        if (player.onKey(it)) return true
      }
    }
    return super.onKeyUp(keyCode, event)
  }

  /** 建立系统媒体会话。真正的实现在 [PlayerMediaSession]。 */
  private fun setupMediaSession() {
    mediaSession.create()
  }

  /** 上报播放/暂停状态给系统。 */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    mediaSession.updatePlaybackState(isPlaying)
  }

  /** 上报标题与时长给系统（锁屏 / 通知栏显示）。 */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    mediaSession.updateMetadata(title, durationMs)
  }

  /** 释放系统媒体会话。 */
  private fun releaseMediaSession() {
    mediaSession.release()
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        serviceBound = true
        Log.d(TAG, "Service connected")
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return
    }

    // Prevent starting service multiple times
    if (serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
// Get media info before starting service
    val artist = runCatching { PlayerLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""

    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }

    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
    }

    // 缩略图异步抓（清单 #20 必修 4）。
    // 原来这里是主线程同步 `grabThumbnail(1080)`：一次约 2.6MB，
    // 而且 `MPVLib.grabThumbnail` 内部会把当前帧从解码器拉出来做像素转换、跨 JNI 传回，
    // **同步阻塞调用方** —— 也就是说在 UI 线程上等一次硬解 + 一次大块内存分配。
    // 现在改成：先把服务起起来（通知先出来，封面可以晚几百毫秒），
    // 抓图丢后台，回来再挂上。
    // 用 `setStaticThumbnail` 而不是 `mediaPlaybackService?.setMediaInfo(...)`：
    // 此刻可能还没绑定上（`bindService` 是异步回调），拿不到实例。
    // 那个方法里判了 `!==`，所以「重新起服务时传的同一张」不会被误 recycle。
    lifecycleScope.launch {
      val thumbnail = withContext(Dispatchers.IO) {
     runCatching { PlayerLib.grabThumbnail(NOTIFICATION_THUMBNAIL_SIZE) }.getOrNull()
   }
      if (isFinishing || isDestroyed) return@launch
      MediaPlaybackService.setStaticThumbnail(thumbnail)
      // 服务这会儿大概率已经绑上了，让它把新封面刷进通知里
      if (serviceBound) {
        mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
      }
    }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /**
   * Manually triggers background playback when the user clicks the background playback button.
   * This works independently of the automaticBackgroundPlayback preference.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  fun triggerBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot trigger background playback: video not ready")
      return
    }

    Log.d(TAG, "User triggered background playback")
    
    // Set flag to enable background playback (same logic as automatic)
    isManualBackgroundPlayback = true
    
    // Restore system UI before going to background
    restoreSystemUI()
    
    // Move to background by going to home screen (same behavior as automatic)
    val intent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = value
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  fun hasNext(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "next" (loops back to beginning)
    if (viewModel.shouldRepeatPlaylist()) return true

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition < shuffledIndices.size - 1
    } else {
      playlistIndex < effectiveSize - 1
    }
  }

  /**
   * Check if there's a previous video in the playlist
   */
  fun hasPrevious(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "previous" (loops back to end)
    if (viewModel.shouldRepeatPlaylist()) return true

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition > 0
    } else {
      playlistIndex > 0
    }
  }

  /**
   * 当前是否携带了播放队列（上一个 / 下一个按钮的显示依据）。
   *
   * 注意与 [PlayerViewModel.hasPlaylistSupport] 的区别：后者还要求用户开启
   * "播放列表模式" 偏好，而 Emby 连播不依赖该开关，所以这里只看队列本身。
   */
  internal fun hasPlaylistItems(): Boolean = playlist.isNotEmpty()

  /**
   * Generate shuffled indices for the playlist
   */
  private fun generateShuffledIndices() {
    if (playlist.isEmpty()) return

    // Create a list of all indices except the current one
    val indices = playlist.indices.filter { it != playlistIndex }.toMutableList()
    indices.shuffle()

    // Put current index at the beginning
    shuffledIndices = listOf(playlistIndex) + indices
    shuffledPosition = 0
  }

  /**
   * Called when shuffle is toggled on/off
   */
  fun onShuffleToggled(enabled: Boolean) {
    if (enabled && playlist.isNotEmpty()) {
      generateShuffledIndices()
    } else {
      shuffledIndices = emptyList()
      shuffledPosition = 0
    }
  }

  /**
   * Play the next video in the playlist
   */
  fun playNext() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to next position
      if (shuffledPosition < shuffledIndices.size - 1) {
        shuffledPosition++
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of shuffled playlist with repeat ALL: regenerate and restart
        generateShuffledIndices()
        shuffledPosition = 0
        playlistIndex = shuffledIndices[0]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex < effectiveSize - 1) {
        playlistIndex++
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of playlist with repeat ALL: restart from beginning
        playlistIndex = 0
        loadPlaylistItem(0)
      }
    }
  }

  /**
   * Play the previous video in the playlist
   */
  fun playPrevious() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to previous position
      if (shuffledPosition > 0) {
        shuffledPosition--
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of shuffled playlist with repeat ALL: go to end
        shuffledPosition = shuffledIndices.size - 1
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex > 0) {
        playlistIndex--
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of playlist with repeat ALL: go to last item
        playlistIndex = effectiveSize - 1
        loadPlaylistItem(playlistIndex)
      }
    }
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    // 切集了：记忆键要跟着换成新一集所属的剧，否则会把上一部剧的速度记到这部剧名下
    refreshSeriesKey()
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
    }

    val uri = playlist[index]

    // Skip playlist items whose local file was deleted (e.g. externally) so we
    // don't get stuck on a missing file. Advance to the next playable item,
    // or finish if there's nothing left.
    if (isLocalFileMissing(uri)) {
      Log.w(TAG, "Skipping missing playlist item at index $index: $uri")
      viewModel.showToast(getString(app.marlboroadvance.mpvex.R.string.toast_file_no_longer_exists))
      val nextIndex = index + 1
      if (nextIndex < playlist.size) {
        loadPlaylistItemInternal(nextIndex)
      } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
        finishAndRemoveTask()
      }
      return
    }

    val playableUri = playlistItemPlayableUri(uri)

    // Update playlist index
    playlistIndex = index

    // Extract and set the new file name
    // 优先用发起方给的标题（Emby 等网络流 URL 末段是 "stream"，直译会得到假标题）
    fileName = getPlaylistTitleAt(index) ?: getFileNameFromUri(uri)
    // Generate new media identifier for playback state
    mediaIdentifier = getMediaIdentifierFromUri(uri, fileName)

    // 弹幕：换集 = 重新匹配。内部会先取消上一次加载，
    // 否则上一集的慢响应会回来覆盖这一集（竞态）。
    // ⚠️ 第三个参数是「**集号**」，不是队列下标 —— 见 [currentEpisodeIndex]。
    notifyDanmakuEpisodeChanged(index)

    // Set HTTP headers (including referer) for network streams
    setHttpHeadersForUri(uri)

    // Update playlist play history if this is a custom playlist
    playlistId?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val filePath = when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }

          else -> uri.toString()
        }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Load the new video
    // ⚠️ 复位必须**同步**做在 loadfile 之前 —— 下面的协程跑在 Dispatchers.Default，
    //   复位放进协程会有竞态（见 onNewIntent 里同一处的注释）。
    viewModel.onLoadingNewFile()
    // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
    lifecycleScope.launch(Dispatchers.Default) {
      PlayerLib.command("loadfile", playableUri)
    }

    // Update media title (this will trigger UI update)
    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    val isM3U = uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u")
    if (!isM3U) {
      PlayerLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (PlayerLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
      // Refresh playlist items to update the currently playing indicator
      viewModel.refreshPlaylistItems()
    }
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Used as a fallback when MPV hasn't set the media-title property yet.
   * For m3u/m3u8 streams, returns the raw media-title from MPV instead of parsing.
   */
  fun getTitleForControls(): String {
    // For m3u/m3u8 streams, use MPV's raw media-title directly.
    // mpv 在拿不到片名时会回传**整条播放直链**（Emby 的链里带 api_key），
    // 而这里的结果会被顶栏 / 投屏 / 交接给另一个内核的标题用到 —— 全部先打码。
    // 打码只发生在展示层，真正的播放地址不受影响。
    if (isCurrentStreamM3U()) {
      val rawTitle = PlayerLib.getPropertyString("media-title")
      if (!rawTitle.isNullOrBlank()) {
        return redactUrlSecrets(rawTitle).orEmpty()
      }
    }
    return fileName
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      // Get parsed video title from MPV
      // （同上：标题可能是整条直链，先给 api_key 打码再落库）
      val videoTitle = runCatching {
        redactUrlSecrets(PlayerLib.getPropertyString("media-title"))
      }.getOrNull()?.takeIf { it.isNotBlank() && it != name }

      // Get duration and file size from MPV
      var duration = runCatching {
        (PlayerLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      if (duration <= 0L) {
        duration = getVideoDurationFromUri(uri)
      }

      val fileSize = runCatching {
        // Try multiple properties to get file size
        PlayerLib.getPropertyDouble("file-size")?.toLong()
          ?: PlayerLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        PlayerLib.getPropertyInt("width") ?: PlayerLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        PlayerLib.getPropertyInt("height") ?: PlayerLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        playlistId = playlistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - playlistId: $playlistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  private fun getVideoDurationFromUri(uri: Uri): Long {
    return runCatching {
      val projection = arrayOf(MediaStore.Video.Media.DURATION)
      when (uri.scheme) {
        "content" -> {
          contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
              val idx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
              if (idx >= 0) {
                val dur = cursor.getLong(idx)
                if (dur > 0L) return dur
              }
            }
          }
        }
        "file" -> {
          val path = uri.path
          if (path != null) {
            val selection = "${MediaStore.Video.Media.DATA} = ?"
            contentResolver.query(
              MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
              projection,
              selection,
              arrayOf(path),
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                if (idx >= 0) {
                  val dur = cursor.getLong(idx)
                  if (dur > 0L) return dur
                }
              }
            }
          }
        }
      }
      val retriever = android.media.MediaMetadataRetriever()
      try {
        if (uri.scheme == "file") {
          retriever.setDataSource(uri.path)
        } else {
          retriever.setDataSource(this, uri)
        }
        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      } finally {
        runCatching { retriever.release() }
      }
    }.getOrDefault(0L)
  }

  private fun updateRecentlyPlayedDurationIfAvailable() {
    lifecycleScope.launch(Dispatchers.IO) {
      val durMs = runCatching {
        (PlayerLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)
      if (durMs > 0L) {
        val uri = extractUriFromIntent(intent) ?: return@launch
        val filePath = when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }
          else -> uri.toString()
        }
        val fileSize = runCatching {
          PlayerLib.getPropertyDouble("file-size")?.toLong()
            ?: PlayerLib.getPropertyDouble("stream-end")?.toLong()
            ?: 0L
        }.getOrDefault(0L)
        val width = runCatching {
          PlayerLib.getPropertyInt("width") ?: PlayerLib.getPropertyInt("video-params/w") ?: 0
        }.getOrDefault(0)
        val height = runCatching {
          PlayerLib.getPropertyInt("height") ?: PlayerLib.getPropertyInt("video-params/h") ?: 0
        }.getOrDefault(0)
        // 标题可能是整条播放直链：写进「最近播放」前先给 api_key 打码
        val videoTitle = runCatching {
          redactUrlSecrets(PlayerLib.getPropertyString("media-title"))
        }.getOrNull()?.takeIf { it.isNotBlank() && it != fileName }

        RecentlyPlayedOps.updateVideoMetadata(
          filePath = filePath,
          videoTitle = videoTitle,
          duration = durMs,
          fileSize = fileSize,
          width = width,
          height = height,
        )
      }
    }
  }

  /**
   * Generate a unique identifier for this media for playback state/history.
   *
   * For local/offline files, uses fileName plus a hash of the file's stable full
   * path so that two files with the same name in different directories get
   * distinct playback histories.
   * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from intent extras.
   * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      // For network files via proxy: use connection ID + file path for stable identifier
      // 队列模式按当前下标取本片路径（networkFilePaths 与 playlist 同序）——
      //    只用单值 extra 的话，连播换片后进度键永远停留在第一片，看第二片存下的
      //    进度会记到第一片头上（用户「连播后每集进度都一样」就是它）
      val pathForCurrentItem = networkFilePaths.getOrNull(playlistIndex) ?: networkFilePath
      val identifier = "network_${networkConnectionId}_${pathForCurrentItem.hashCode()}"
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $pathForCurrentItem)",
      )
      return identifier
    }

    val explicitFilePath = intent.getStringExtra("file_path")
    if (!explicitFilePath.isNullOrBlank()) {
      val identifier = app.marlboroadvance.mpvex.utils.media.MediaIdentifier.forLocalPath(explicitFilePath)
      Log.d(TAG, "Using explicit file path identifier: $identifier (path: $explicitFilePath)")
      return identifier
    }

    val uri = extractUriFromIntent(intent)
    return if (uri != null && (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms")) {
      // For remote protocols: hash the URI so position is per-episode or per-stream.
      "${fileName}_${uri.toString().hashCode()}"
    } else if (uri != null) {
      // For local/file/content uris: include the full path so same-named files
      // in different directories don't collide.
      localMediaIdentifier(uri, fileName)
    } else {
      fileName
    }
  }

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName plus a hash of the file's stable full
   * path so that same-named files in different directories are distinct.
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    return if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      localMediaIdentifier(uri, fileName)
    }
  }

  /**
   * Builds a stable, directory-aware identifier for a local file URI.
   *
   * The identifier combines the display name with a hash of the file's full path
   * so that two files with the same name in different folders resolve to
   * different playback-history keys. The path is resolved to a value that stays
   * stable across app launches (unlike a temporary file descriptor).
   */
  private fun localMediaIdentifier(uri: Uri, fileName: String): String {
    val stablePath = resolveStableLocalPath(uri)
    return if (stablePath.isNullOrBlank()) {
      // Fallback: keep the previous filename-only behavior if we can't resolve a path.
      fileName
    } else {
      // Delegate to the shared helper so deletion/rename cleanup keys match exactly.
      app.marlboroadvance.mpvex.utils.media.MediaIdentifier.forLocalPath(stablePath)
    }
  }

  /**
   * Resolves a stable, persistent path string for a local file URI, including its
   * directory. Returns null if no stable path can be determined.
   *
   * - file:// -> the URI path (already the full filesystem path)
   * - content:// -> the real filesystem path via MediaStore DATA, falling back to
   *   RELATIVE_PATH + DISPLAY_NAME, then the URI string itself.
   *
   * Note: [Uri.resolveUri] is intentionally NOT used here because it returns a
   * temporary /proc/self/fd file descriptor for content URIs, which changes every
   * session and would not be a stable key.
   */
  private fun resolveStableLocalPath(uri: Uri): String? = runCatching {
    val explicitFilePath = intent.getStringExtra("file_path")
    if (!explicitFilePath.isNullOrBlank() && (uri == extractUriFromIntent(intent) || playlistIndex <= 0)) {
      return explicitFilePath
    }
    when (uri.scheme) {
      "file" -> uri.path
      "content" -> {
        contentResolver.query(
          uri,
          arrayOf(MediaStore.MediaColumns.DATA),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (columnIndex != -1) cursor.getString(columnIndex) else null
          } else {
            null
          }
        }?.takeIf { it.isNotBlank() } ?: resolveRelativeContentPath(uri) ?: uri.toString()
      }

      else -> uri.toString()
    }
  }.onFailure { e ->
    Log.e(TAG, "Error resolving stable local path for $uri", e)
  }.getOrNull()

  /**
   * Fallback for content URIs where MediaStore DATA is unavailable (e.g. on newer
   * Android versions): builds a stable path from RELATIVE_PATH + DISPLAY_NAME.
   */
  private fun resolveRelativeContentPath(uri: Uri): String? = runCatching {
    contentResolver.query(
      uri,
      arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val relative = if (relIdx != -1) cursor.getString(relIdx) else null
        val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
        if (!relative.isNullOrBlank() && !name.isNullOrBlank()) {
          val relPath = "$relative$name".trimStart('/')
          val storageDir = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
          "$storageDir/$relPath"
        } else {
          null
        }
      } else {
        null
      }
    }
  }.getOrNull()

  /**
   * Returns true only when the given URI points at a LOCAL file that no longer
   * exists on disk. Network streams, content URIs we can't resolve to a path,
   * and existing files all return false (we don't want false positives that
   * would block legitimate playback).
   */
  private fun isLocalFileMissing(uri: Uri): Boolean {
    // Never treat network streams as "missing".
    if (uri.scheme?.startsWith("http") == true ||
      uri.scheme == "rtmp" || uri.scheme == "rtsp" ||
      uri.scheme == "mms" || uri.scheme == "ftp" || uri.scheme == "ftps"
    ) {
      return false
    }

    val path = when (uri.scheme) {
      "file" -> uri.path
      "content" -> resolveStableLocalPath(uri)?.takeIf { it.startsWith("/") }
      else -> uri.path?.takeIf { it.startsWith("/") }
    } ?: return false

    return runCatching { !File(path).exists() }.getOrDefault(false)
  }

  private fun generatePlaylistFromFolder(currentPath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val currentFile = File(currentPath)
        if (!currentFile.exists()) return@runCatching

        val parentFolder = currentFile.parentFile ?: return@runCatching

        val videoExtensions = FileTypeUtils.VIDEO_EXTENSIONS

        val files = parentFolder.listFiles { file ->
          file.isFile &&
            FileTypeUtils.isVideoFile(file) &&
            !FileFilterUtils.shouldSkipFile(file)
        } ?: return@runCatching

        val launchSource = intent.getStringExtra("launch_source") ?: ""
        val siblingFiles = if (launchSource == "video_list" || launchSource == "recently_played_button" || launchSource == "first_video_button") {
          val videoSortType = browserPreferences.videoSortType.get()
          val videoSortOrder = browserPreferences.videoSortOrder.get()
          val bucketId = parentFolder.absolutePath.replace("\\", "/")
          val videosInFolder =
            app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(
              context,
              setOf(bucketId)
            )
          val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(videosInFolder, videoSortType, videoSortOrder)
          sortedVideos.mapNotNull { video -> files.find { it.absolutePath == video.path } }
        } else {
          files.sortedWith { f1, f2 -> app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name) }
        }

        if (siblingFiles.size <= 1) return@runCatching

        val newPlaylist = siblingFiles.map { it.toUri() }

        val newIndex = siblingFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }

        if (newIndex != -1) {
          withContext(Dispatchers.Main) {
            playlist = newPlaylist
            playlistIndex = newIndex
            Log.d(TAG, "Auto-playlist generated: ${playlist.size} videos")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Failed to auto-generate playlist", e)
      }
    }
  }

  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist

  companion object {
    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    /**
     * 「本次加载没有字幕存档」的哨兵值。用 [Int.MIN_VALUE] 而不是 0 / -1：
     * mpv 的空轨道（`sid=no`）读回来就是 -1，必须和「压根没有存档」区分开 ——
     * 前者要如实恢复成「字幕关着」，后者则完全不该碰字幕选择。
     */
    private const val NO_SAVED_SUBTITLE_SELECTION = Int.MIN_VALUE

    /**
     * intent 里指定播放内核的 extra key（详情页长按播放按钮 → 备用内核）。
     * 值为 [EngineKind.name]（"MPV" / "EXO"），缺省回退到设置里的默认内核。
     */
    const val EXTRA_ENGINE = "engine"

    /** GSY 底下的解码内核（"IJK" / "EXO"），交给 [GsyPlayerActivity] 时带上 */
    const val EXTRA_GSY_KERNEL = "gsy_kernel"

    /**
     * 通知栏封面截图的宽度（像素）（清单 #20 必修 4）。
     *
     * 原来是 `grabThumbnail(1080)` —— **按高度还是宽度不确定**，但无论哪个，
     * 一张 1080 级的图解码成 ARGB_8888 都在 2MB 以上（1080 宽×约 600 高×4B ≈ 2.6MB），
     * 而 `MediaPlaybackService.thumbnail` 是静态字段，一路上还有多处引用。
     *
     * 640 是个够用的值：Android 通知栏的大图标在标准分辨率下也就是
     * `Notification.largeIcon` 那一档，实际显示尺寸通常 < 300dp，
     * 640 宽在 xxhdpi 上仍然绰绰有余，视觉上看不出差别。
     *
     * 省下的是实打实的 4 倍内存 + 4 倍拷贝时间，而这份内存是**要一直挂在进程上**的。
     */
    private const val NOTIFICATION_THUMBNAIL_SIZE = 640

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * `onDestroy` 里等存档协程收尾的上限（毫秒）（清单 #20）。
     *
     * 原本是 `runBlocking { job.join() }` **无限等**，在慢机器 / 数据库锁 /
     * 磁盘满的时候会直接 ANR。留一个上限后最坏情况是「进度晚一点到账」，
     * 而晚到账本身不会丢数据（onPause 退出那次已经同步写过一遍）。
     *
     * 取 500ms：要能覆盖 Room 首次建表 + WAL fsync 的典型耗时，
     * 又不至于让用户看着「退出应用」明显卡顿。
     */
    private const val SAVE_JOIN_BUDGET_MS = 500L

    /**
     * 等 mpv 进入 idle 的上限（毫秒）（清单 #20）。
     *
     * 与原先的 `Thread.sleep(100)` 同量级 —— **最坏情况不比以前更差**，
     * 典型情况（quit 很快被处理）只等几毫秒。
     */
    private const val MPV_IDLE_WAIT_BUDGET_MS = 100L

    /**
     * 上面那个轮询的间隔（毫秒）。
     *
     * 5ms 足够：mpv 处理命令的粒度远大于 5ms，再密只是白烧 CPU。
     */
    private const val MPV_IDLE_POLL_INTERVAL_MS = 5L

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * 续播位置离片尾小于这个秒数就视为"已经看完"，从头开始播。
     *
     * 防止把 `-1` 秒级的续播位置写进播放器：那样会让 mpv / GSY 一开局就 seek 到结束点，
     * IJK 立刻回调播放完成，上层按「播放结束」把 Activity 关掉。
     */
    private const val RESUME_TAIL_GUARD_SEC = 5.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvex"
  }
}

/**
 * 「出画面前」的加载层：从进入播放器到首帧真正上屏之间显示加载动画。
 *
 * 之前这段只有一片黑（视频 Surface 已创建但还没有内容），用户分不清是在加载还是卡死了。
 *
 * ## 判据为什么是 `MPV_EVENT_PLAYBACK_RESTART` 而不是 mpv 的 `vo-configured`
 *
 * mpv 文档对 `vo-configured` 的定义是「VO 是否已配置好，通常对应视频窗口是否可见」，
 * 听起来正好合适 —— 但它在 `vo_reconfig2()` 返回时就为 yes，**早于第一帧 `flip_page`**。
 * 拿它当判据 = 加载动画先消失、再黑一下、画面才浮现，**比不显示更难看**。
 * `PLAYBACK_RESTART` 在日志顺序上排在 `first video frame after restart shown` 之后。
 *
 * 这与视界流那条踩过的坑是同一个（见 `FeedViewModel.buffered` 的注释：
 * 判据早一步就会「转圈消失 → 黑屏 → 画面浮现」）。
 *
 * ## 为什么淡出而不是硬切
 *
 * 硬切的话，哪怕只差一两个 vsync 也会被看成「闪了一下」。
 * 淡出期间画面从动画背后浮上来，观感上就是无缝衔接。
 *
 * ⚠️ 必须是**顶层函数**（放在类的右花括号之外），不是类的方法 ——
 *   写在 `companion object` 里的话，普通 `private fun` 拿不到 `@Composable` 的
 *   调用环境，编译会报一串「@Composable invocations can only happen from…」。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PlayerFirstFrameLoading(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  // ⚠️ 不能从 ViewModel 拿偏好：`PlayerViewModel.playerPreferences` 是 **private**
  //   （PlayerViewModel.kt:87）。这里自己注入一份 —— 偏好是 Koin 单例，读的是同一个值
  //   （PlayerControls.kt:858 也是就地注入后读的）。
  val playerPrefs = koinInject<PlayerPreferences>()
  // ⚠️ 三个同名 `collectAsState`（红线 38）：
  //   ① Compose 的 `StateFlow.collectAsState(initial)`（本文件顶部 import 的就是它，
  //      上面 `firstFrameRendered` 用的这个）；
  //   ② 本项目的 `Flow.collectAsState(scope, initial)`（返回 T?，PlayerViewModel.kt:2232）；
  //   ③ 本项目的 `Preference<T>.collectAsState()`（**无参**扩展，
  //      preferences/preference/Preference.kt:43）—— `showLoadingCircle` 是
  //      `Preference<Boolean>`，要的是这个。
  //    同文件 import 两个同名函数不冲突：靠接收者类型重载分辨
  //    （MainActivity.kt:35+59 同时 import 了两个，同样用法）。
  val showLoadingCircle by playerPrefs.showLoadingCircle.collectAsState()

  val firstFrameRendered by viewModel.firstFrameRendered.collectAsState()

  if (!showLoadingCircle) return

  AnimatedVisibility(
    visible = !firstFrameRendered,
    enter = fadeIn(tween(120)),
    exit = fadeOut(tween(260)),
    modifier = modifier,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      // 96dp 是**基准**尺寸（还会乘用户设的缩放系数，默认 1.1×）—— 与缓冲转圈
      // （PlayerControls.kt:866）同基准 ⇒ 首帧层与缓冲层接力时画面不跳。
      // ⚠️ 用 size 参数而不是 Modifier.size，否则「加载动画大小」缩放对它不生效。
      // 压在画面上，所以固定白色半透明（主题色在视频上基本看不见）。
      app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
        size = 96.dp,
        tint = Color.White.copy(alpha = 0.85f),
      )
      // ── 文字提示（原来只有转圈）──
      // 只有动画时用户分不清「在加载」和「卡死了」，尤其网络播放要等好几秒。
      // 第二行是**兜底说明**：死链在起播前已被拦掉（见 EmbyClient.resolveStreamUrl），
      // 但「地址有效却极慢」还是会长时间停在这一屏，得给用户一句能对应上的话。
      Spacer(Modifier.height(14.dp))
      Text(
        text = "正在加载…",
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 13.sp,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "若长时间停在这里，可能是网络不稳或直链已过期",
        color = Color.White.copy(alpha = 0.42f),
        fontSize = 11.sp,
      )
    }
  }
}
