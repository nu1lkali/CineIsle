package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
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
   * （实测横屏尤其明显：切了下一集后上一集按钮仍是灰色、点不动）。改成可观察状态后，
   * 位置一变 UI 立即刷新。所有非组合场景的读写照常当普通 Int 用，零影响。
   */
  internal var playlistIndex: Int by mutableStateOf(0)

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
  private var noisyReceiverRegistered = false
  private var mpvInitialized = false // Track MPV initialization state
  /**
   * 首帧视频尺寸到达后只据此设一次方向；之后切集不再调用 [setOrientation]，
   * 否则「按视频方向」模式下下一集若是竖屏内容会被翻成竖屏，违背用户当前方向。
   */
  private var videoParamsOrientationHandled = false
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job
  private var wasPlayingBeforePause = false // Track if video was playing before pause

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
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  /**
   * Callback to restore audio focus after it's been lost and regained.
   */
  private var restoreAudioFocus: () -> Unit = {}

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
          -> {
          // Save current state to restore later
          val oldRestore = restoreAudioFocus
          val wasPlayerPaused = viewModel.paused ?: false
          viewModel.pause()
          restoreAudioFocus = {
            oldRestore()
            if (!wasPlayerPaused) viewModel.unpause()
          }
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          // Lower volume temporarily
          PlayerLib.command("multiply", "volume", "0.5")
          restoreAudioFocus = {
            PlayerLib.command("multiply", "volume", "2")
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          // Restore previous audio state
          restoreAudioFocus()
          restoreAudioFocus = {}
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
        }
      }
    }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // ── 默认播放内核是 GSY 时，把这次播放整体转交给 GSY 播放页 ──
    // 必须在任何 mpv 初始化之前判断，转交时连画面都还没建起来，观感上就是直接进 GSY。
    if (redirectToPreferredEngine()) return

    setContentView(binding.root)

    // ── 播放内核选择 ──
    // intent 里带 "engine" 的长按反选优先，否则用设置里的默认内核。
    // 必须在任何 PlayerLib 调用之前定下来：门面层靠它决定转发给 mpv 还是 Exo。
    // 本页固定 mpv。备用内核 GSY 走独立的 GsyPlayerActivity（官方默认布局 + 官方三行用法），
    // 两套东西不再共用同一个 Activity，互不干扰。
    engineKind = EngineKind.MPV
    PlayerLib.kind = EngineKind.MPV

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    setupMPV()
    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
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
    // 于是从第二个视频起标题全变成 "stream"（用户反馈的 bug）。有它就优先用它。
    playlistTitles = intent.getStringArrayListExtra("playlist_titles") ?: emptyList()
    // 「记住每部剧的播放设置」的键，与 playlist 下标一一对应
    playlistSeriesKeys = intent.getStringArrayListExtra("playlist_series_keys") ?: emptyList()
    singleSeriesKey = intent.getStringExtra("emby_series_key")
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

    getPlayableUri(intent)?.let { uri ->
      player.playFile(uri)
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
        }
      }
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
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restoreAudioFocus = {}
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restoreAudioFocus = { requestAudioFocus() }
        false
      }

      else -> {
        restoreAudioFocus = {}
        false
      }
    }
  }

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

      // Wait for any pending save operation to complete before destroying MPV
      // This prevents the race condition where the save coroutine tries to access
      // MPV properties after PlayerLib.destroy() has been called
      savePlaybackStateJob?.let { job ->
        Log.d(TAG, "Waiting for save playback state job to complete...")
        runCatching {
          // Use runBlocking to ensure we wait for the job to finish
          // This is safe here as onDestroy is already on the main thread
          kotlinx.coroutines.runBlocking {
            job.join()
          }
        }
        Log.d(TAG, "Save playback state job completed")
      }

      cleanupMPV()
      cleanupAudio()
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    super.onDestroy()
  }

  private fun cleanupMPV() {
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      PlayerLib.removeObserver(playerObserver)

      if (isReady) {
        // Pause playback first to reduce thread activity
        PlayerLib.setPropertyBoolean("pause", true)

        // Send quit command to gracefully shut down MPV
        PlayerLib.command("quit")

        // Wait briefly for MPV to process quit and clean up internal threads
        // This prevents race conditions where hardware UI threads try to access
        // mutexes/queues that are destroyed by PlayerLib.destroy()
        // We use a short blocking wait here as onDestroy is already on the main thread
        // and this ensures proper cleanup before activity destruction
        Thread.sleep(100)
      }

      // Now safe to destroy MPV as internal threads have had time to shut down
      PlayerLib.destroy()
      mpvInitialized = false
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  override fun abandonAudioFocus() {
    if (restoreAudioFocus != {}) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      restoreAudioFocus = {}
    }
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }
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
        wasPlayingBeforePause = !(viewModel.paused ?: true)
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

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

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

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
      }

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
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
   * Initializes the MPV player with the necessary paths and observers.
   */
  private fun setupMPV() {
    // Copy essential files FIRST, before MPV initialization
    runCatching {
      Utils.copyAssets(this@PlayerActivity)
      syncFromUserMpvDirectory()
      Log.d(TAG, "MPV config and scripts prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and scripts", e)
    }

    // NOW initialize MPV - it will find and load the scripts we just copied
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    PlayerLib.addObserver(playerObserver)
  }

  /**
   * Syncs ALL MPV assets from the user's configured MPV directory to internal storage.
   * Handles: mpv.conf, input.conf, scripts/, script-opts/, shaders/, fonts/
   *
   * Uses case-insensitive subfolder matching and falls back to root scanning
   * if standard subfolders don't exist. Falls back to preferences-based config
   * if no user directory is configured.
   */
  private fun syncFromUserMpvDirectory() {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree = if (mpvConfStorageUri.isNotBlank()) {
      runCatching {
        DocumentFile.fromTreeUri(this, mpvConfStorageUri.toUri())
      }.getOrNull()?.takeIf { it.exists() && it.canRead() }
    } else null

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      syncConfigFiles(tree)
      syncFonts(tree)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: use preferences-based config (no user directory set)
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyMPVConfigFromPreferences()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(tree: DocumentFile) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            File(filesDir, configName).writeText(content)
            // Cache in preferences for the config editor
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent = when (configName) {
            "mpv.conf" -> advancedPreferences.mpvConf.get()
            "input.conf" -> advancedPreferences.inputConf.get()
            else -> ""
          }
          File(filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   * Also syncs from the subtitle preferences font folder if set.
   */
  private fun syncFonts(tree: DocumentFile) {
    val internalFontsDir = File(filesDir, "fonts")
    internalFontsDir.mkdirs()

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts")
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    var count = 0

    sourceDir.listFiles().forEach { file ->
      if (!file.isFile) return@forEach
      val name = file.name ?: return@forEach
      val ext = name.substringAfterLast('.', "").lowercase()
      if (ext !in fontExtensions) return@forEach

      val target = File(internalFontsDir, name)
      // Skip if font already exists (fonts can be large)
      if (target.exists()) return@forEach

      runCatching {
        contentResolver.openInputStream(file.uri)?.use { input ->
          target.outputStream().use { output ->
            input.copyTo(output)
          }
          count++
          Log.d(TAG, "Synced font: $name")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing font: $name", e)
      }
    }

    // Also sync from subtitle preferences font folder if set
    runCatching {
      val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
      if (fontsFolderUri.isNotBlank()) {
        val destDir = fileManager.fromPath("${filesDir.path}/fonts")
        if (!fileManager.exists(destDir)) {
          fileManager.createDir(fileManager.fromPath(filesDir.path), "fonts")
        }
        val fontsDir = fileManager.fromUri(fontsFolderUri.toUri())
        if (fontsDir != null && fileManager.exists(fontsDir)) {
          fileManager.copyDirectoryWithContent(fontsDir, destDir, false)
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error syncing subtitle fonts: ${e.message}")
    }

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  // ==================== Helpers ====================

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      File(filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // Ensure fonts directory exists even without user dir
      File(filesDir, "fonts").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  override fun onResume() {
    super.onResume()
    updateVolume()
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
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
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
  // 设置里选了「不显示，播完直接切」：不需要盯进度，播完走原逻辑即可
  if (total <= 0) {
    autoplayCountdownJob = null
    return
  }
    val startIndex = playlistIndex
  autoplayCountdownJob = lifecycleScope.launch {
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
      kotlinx.coroutines.delay(500)
      // 用户自己切了集 / 退出了 / 在片尾取消了：这次盯梢作废
      if (playlistIndex != startIndex || isFinishing || autoplayCancelled) break
      if (peekNextIndex() == null && !viewModel.shouldRepeatPlaylist()) break // 没有下一集
      val left = wallClockSecondsToEnd() ?: continue
      // 进入最后 total 秒：把卡片推出来，之后每 0.5 秒刷新一次，数字自然往下走
      if (left in 0.1..total.toDouble()) {
        if (viewModel.autoplayNextTitle.value == null) {
          viewModel.autoplayNextTitle.value = peekNextIndex()?.let { playlistTitles.getOrNull(it) }
        }
        viewModel.autoplayCountdown.value = kotlin.math.ceil(left).toInt().coerceAtLeast(1)
      } else if (left > total && viewModel.autoplayCountdown.value > 0) {
        // 用户往后拖了进度条：卡片收起来，等再次接近片尾再出现
        viewModel.autoplayCountdown.value = 0
        viewModel.autoplayNextTitle.value = null
      }
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
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
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
    }

    // Start media notification service (like YouTube - always show notification)
    // 服务经由 PlayerLib 门面驱动，mpv / Exo 内核都能用（Exo 下缩略图为空，其余一致）
    startBackgroundPlayback()

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    // 换片了：网速采样的基线（fw-bytes 增量）与平滑历史一并清掉，
    // 否则新片开头那一秒会把上一条的缓冲量算进来，速度显示跳变
    MpvNetSpeedSampler.reset()

    setIntentExtras(intent.extras)

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
      withContext(Dispatchers.Main) { startAutoplayCountdownWatcher() }

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
              val thumbnail = runCatching { PlayerLib.grabThumbnail(1080) }.getOrNull()
              mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
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

    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    PlayerLib.setPropertyString("sub-scale-by-window", scaleValue)
    PlayerLib.setPropertyString("sub-use-margins", scaleValue)

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

        playbackStateRepository.upsert(
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
          ),
        )
        Log.d(TAG, "Playback state saved successfully: pos=$lastPosition, duration=$duration, remaining=$timeRemaining, watched=$hasBeenWatched")
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state", e)
      }
    }

    if (isSync) {
      kotlinx.coroutines.runBlocking(Dispatchers.IO) {
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

      if (networkFilePath != null && networkConnectionId != -1L) {
        // Pass network file path and connection ID for subtitle discovery
        SubtitleOps.autoloadSubtitles(
          videoFilePath = networkFilePath,
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
    //
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
      singleSeriesKey = intent.getStringExtra("emby_series_key")
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

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }
            },
          )
          isActive = true
        }
      playbackStateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      mediaSessionInitialized = true
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      val positionMs = (viewModel.pos ?: 0) * 1000L
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setState(state, positionMs, if (isPlaying) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
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
    val thumbnail = runCatching { PlayerLib.grabThumbnail(1080) }.getOrNull()
    
    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }
    
    // Store thumbnail in companion object for service to access
    MediaPlaybackService.thumbnail = thumbnail
    
    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
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
      val identifier = "network_${networkConnectionId}_${networkFilePath.hashCode()}"
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $networkFilePath)",
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
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

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
