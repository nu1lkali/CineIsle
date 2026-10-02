package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.player.engine.VideoCrop

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


enum class RepeatMode {
  OFF,      // No repeat
  ONE,      // Repeat current file
  ALL       // Repeat all (playlist)
}

class PlayerViewModelProviderFactory(
  private val host: PlayerHost,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(
    modelClass: Class<T>,
    extras: CreationExtras,
  ): T {
    if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return PlayerViewModel(host) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@Suppress("TooManyFunctions")
class PlayerViewModel(
  private val host: PlayerHost,
) : ViewModel(),
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val json: Json by inject()

  /**
   * 「记住每部剧播放设置」的键：Emby 剧集是 SeriesId，电影是 ItemId，本地播放为 null。
   *
   * 由 PlayerActivity 从 intent 里读出来后写进来，切集时按下标换成新一集的键。
   * 用普通字段而不是 StateFlow：它只在「用户改速度 / 选音轨」的回调里被读一次，
   * 不需要驱动重组。
   */
  var seriesKey: String? = null

  /**
   * 自动连播下一集前的倒计时剩余秒数；0 表示不显示卡片。
   *
   * 播完一集时由播放页起一个每秒递减的协程，卡片上「立即播放 / 取消」
   * 两个按钮会取消这个协程并把它清零。
   */
  val autoplayCountdown = MutableStateFlow(0)

  /** 倒计时卡片里显示的下一集标题（取不到就是 null，卡片只显示秒数） */
  val autoplayNextTitle = MutableStateFlow<String?>(null)
  private val playbackStateDao: app.marlboroadvance.mpvex.database.dao.PlaybackStateDao by inject()

  // Playlist items for the playlist sheet
  private val _playlistItems = kotlinx.coroutines.flow.MutableStateFlow<List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>>(emptyList())
  val playlistItems: kotlinx.coroutines.flow.StateFlow<List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>> = _playlistItems.asStateFlow()

  // Cache for video metadata to avoid re-extracting — LruCache handles bounds + thread-safety
  private val metadataCache = object : android.util.LruCache<String, Pair<String, String>>(100) {}

  private fun updateMetadataCache(key: String, value: Pair<String, String>) {
    metadataCache.put(key, value)
  }

  // MPV properties with efficient collection
  val paused by PlayerLib.propBoolean["pause"].collectAsState(viewModelScope)
  val pos by PlayerLib.propInt["time-pos"].collectAsState(viewModelScope)
  val duration by PlayerLib.propInt["duration"].collectAsState(viewModelScope)

  // ── 网络加载速度（播放器顶栏的指示器）──
  private val _netSpeedBytesPerSec = MutableStateFlow<Long?>(null)

  /**
   * 当前视频的网络加载速度（字节/秒），约 1 秒刷新一次。
   *
   * null = 指示器整体隐藏：非 mpv 内核、或当前流不是网络流（本地文件上
   * 显示「0 KB/s」只会造成困惑）。值为 0 表示「缓冲已满 / 暂停中，没有在下载」，
   * 此时指示器显示 0 KB/s 而不是消失 —— 数值归零是诚实的，忽隐忽现反而让人以为坏了。
   */
  val netSpeedBytesPerSec = _netSpeedBytesPerSec.asStateFlow()

  // High-precision position and duration for smooth seekbar
  private val _precisePosition = MutableStateFlow(0f)
  val precisePosition = _precisePosition.asStateFlow()

  private val _preciseDuration = MutableStateFlow(0f)
  val preciseDuration = _preciseDuration.asStateFlow()

  // Audio state
  val currentVolume = MutableStateFlow(host.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
  private val volumeBoostCap by PlayerLib.propInt["volume-max"].collectAsState(viewModelScope)

  init {
    // Poll precise position only when playing
    viewModelScope.launch {
      while (isActive) {
        val time = PlayerLib.getPropertyDouble("time-pos")
        if (time != null) {
          _precisePosition.value = time.toFloat()
        }
        delay(42) // ~24fps updates
      }
    }

    // Update precise duration when the integer duration changes (avoid polling)
    viewModelScope.launch {
      PlayerLib.propInt["duration"].collect { _ ->
        val dur = PlayerLib.getPropertyDouble("duration")
        if (dur != null && dur > 0) {
            _preciseDuration.value = dur.toFloat()
        }
      }
    }

    // ── 网络加载速度：固定 1 秒一拍 ──
    // 数据源与归零策略见 [MpvNetSpeedSampler] / [netSpeedBytesPerSec] 的注释。
    // 轮询而非 observe：cache-speed 的属性变更通知不保证按秒触发，定时读最稳。
    viewModelScope.launch {
      while (isActive) {
        val paused = PlayerLib.getPropertyBoolean("pause") == true
        _netSpeedBytesPerSec.value = MpvNetSpeedSampler.sample(
          isMpv = PlayerLib.kind == EngineKind.MPV,
          isPaused = paused,
        )
        delay(NET_SPEED_POLL_INTERVAL_MS)
      }
    }
  }

  val maxVolume = host.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

  /**
   * 全部轨道。mpv 从 `track-list` 这个 MPVNode 反序列化；GSY 没有 MPVNode，
   * 走门面里由 GsyBackend 直接构造好的 [PlayerLib.gsyTrackNodes]。
   */
  private val allTracks: StateFlow<List<TrackNode>> =
    if (PlayerLib.kind == EngineKind.MPV) {
      PlayerLib.propNode["track-list"]
        .map { node -> node?.toObject<List<TrackNode>>(json) ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    } else {
      PlayerLib.gsyTrackNodes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

  val subtitleTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { list -> list.filter { it.isSubtitle }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  val audioTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { list -> list.filter { it.isAudio }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  val chapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    PlayerLib.propNode["chapter-list"]
      .map { node ->
        node?.toObject<List<ChapterNode>>(json)?.map { it.toSegment() }?.toImmutableList()
          ?: persistentListOf()
      }.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

  // UI state
  private val _controlsShown = MutableStateFlow(false)
  val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()

  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()

  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)
  val currentBrightness =
    MutableStateFlow(
      runCatching {
        Settings.System
          .getFloat(host.hostContentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .normalize(0f, 255f, 0f, 1f)
      }.getOrElse { 0f },
    )

  val sheetShown = MutableStateFlow(Sheets.None)
  val panelShown = MutableStateFlow(Panels.None)

  // Seek state
  private val _seekText = MutableStateFlow<String?>(null)
  val seekText: StateFlow<String?> = _seekText.asStateFlow()

  private val _doubleTapSeekAmount = MutableStateFlow(0)
  val doubleTapSeekAmount: StateFlow<Int> = _doubleTapSeekAmount.asStateFlow()

  private val _isSeekingForwards = MutableStateFlow(false)
  val isSeekingForwards: StateFlow<Boolean> = _isSeekingForwards.asStateFlow()

  // Frame navigation
  private val _currentFrame = MutableStateFlow(0)
  val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()

  private val _totalFrames = MutableStateFlow(0)
  val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

  private val _isFrameNavigationExpanded = MutableStateFlow(false)
  val isFrameNavigationExpanded: StateFlow<Boolean> = _isFrameNavigationExpanded.asStateFlow()

  private val _isSnapshotLoading = MutableStateFlow(false)
  val isSnapshotLoading: StateFlow<Boolean> = _isSnapshotLoading.asStateFlow()

  // Video zoom
  private val _videoZoom = MutableStateFlow(0f)
  val videoZoom: StateFlow<Float> = _videoZoom.asStateFlow()

  // Video aspect ratio (now persisted via preferences)
  private val _videoAspect = MutableStateFlow(playerPreferences.defaultVideoAspect.get())
  val videoAspect: StateFlow<VideoAspect> = _videoAspect.asStateFlow()

  // Current aspect ratio value (for custom ratios and tracking)
  private val _currentAspectRatio = MutableStateFlow(playerPreferences.defaultCustomAspectRatio.get())
  val currentAspectRatio: StateFlow<Double> = _currentAspectRatio.asStateFlow()

  // Timer
  private var timerJob: Job? = null
  private val _remainingTime = MutableStateFlow(0)
  val remainingTime: StateFlow<Int> = _remainingTime.asStateFlow()

  // Media title for subtitle association
  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null

  // External subtitle tracking
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()
  
  // Mapping from mpv internal path/URI to the original source URI (resolves deletion issues)
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  // Repeat and Shuffle state
  private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
  val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

  private val _shuffleEnabled = MutableStateFlow(false)
  val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

  // A-B Loop state
  private val _abLoopA = MutableStateFlow<Double?>(null)
  val abLoopA: StateFlow<Double?> = _abLoopA.asStateFlow()

  private val _abLoopB = MutableStateFlow<Double?>(null)
  val abLoopB: StateFlow<Double?> = _abLoopB.asStateFlow()

  private val _isABLoopExpanded = MutableStateFlow(false)
  val isABLoopExpanded: StateFlow<Boolean> = _isABLoopExpanded.asStateFlow()

  // Mirroring state
  private val _isMirrored = MutableStateFlow(false)
  val isMirrored: StateFlow<Boolean> = _isMirrored.asStateFlow()

  // Vertical flip state
  private val _isVerticalFlipped = MutableStateFlow(false)
  val isVerticalFlipped: StateFlow<Boolean> = _isVerticalFlipped.asStateFlow()

  init {
    // Track selection is now handled by TrackSelector in PlayerActivity
    
    // Restore repeat mode and shuffle state from preferences
    _repeatMode.value = playerPreferences.repeatMode.get()
    _shuffleEnabled.value = playerPreferences.shuffleEnabled.get()

    // Observe volume boost cap changes to enforce limits dynamically (in PiP)
    viewModelScope.launch {
      audioPreferences.volumeBoostCap.changes().collect { cap ->
        val maxVol = 100 + cap
        runCatching {
          PlayerLib.setPropertyString("volume-max", maxVol.toString())
          
          // Clamp current volume if it exceeds the new limit
          val currentMpvVol = PlayerLib.getPropertyInt("volume") ?: 100
          if (currentMpvVol > maxVol) {
            PlayerLib.setPropertyInt("volume", maxVol)
          }
        }.onFailure { e ->
          Log.e(TAG, "Error setting volume-max: $maxVol", e)
        }
      }
    }

    // Monitor duration and AB loop changes to automatically enable precise seeking
    viewModelScope.launch {
      combine(
        PlayerLib.propInt["duration"],
        abLoopA,
        abLoopB
      ) { duration, loopA, loopB ->
        Triple(duration, loopA, loopB)
      }.collect { (duration, loopA, loopB) ->
        val videoDuration = duration ?: 0
        
        // Only override hr-seek when duration is actually known and stable
        if (videoDuration > 0) {
          // Use precise seeking for videos shorter than 2 minutes, or if AB loop is active, or if preference is enabled
          val isLoopActive = loopA != null || loopB != null
          val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || videoDuration < 120 || isLoopActive
          
          // Update hr-seek settings dynamically
          PlayerLib.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
          PlayerLib.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
        }
      }
    }
  }

  // Cached values
  private val doubleTapToSeekDuration by lazy { gesturePreferences.doubleTapToSeekDuration.get() }
  private val inputMethodManager by lazy {
    host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
  }

  // Seek coalescing for smooth performance
  private var pendingSeekOffset: Int = 0
  private var seekCoalesceJob: Job? = null

  private companion object {
    const val TAG = "PlayerViewModel"
    const val SEEK_COALESCE_DELAY_MS = 60L

    /** 网络加载速度的采样周期：1 秒一拍，与常见播放器（VLC / PotPlayer）的刷新节奏一致 */
    const val NET_SPEED_POLL_INTERVAL_MS = 1_000L
    val VALID_SUBTITLE_EXTENSIONS =
      setOf(
        // Common & modern
        "srt", "vtt", "ass", "ssa",
        // DVD / Blu-ray
        "sub", "idx", "sup",
        // Streaming / XML / Professional
        "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",
        // Online platforms
        "sbv", "srv1", "srv2", "srv3", "json",
        // Legacy & niche
        "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",
        // Broadcast captions
        "scc", "vttx",
        // Karaoke / lyrics
        "lrc", "krc",
        // Fallback / raw text
        "txt", "pgs"
      )
  }

  // ==================== Timer ====================

  fun startTimer(seconds: Int) {
    timerJob?.cancel()
    _remainingTime.value = seconds
    if (seconds < 1) return

    timerJob =
      viewModelScope.launch {
        for (time in seconds downTo 0) {
          _remainingTime.value = time
          delay(1000)
        }
        PlayerLib.setPropertyBoolean("pause", true)
        showToast(host.context.getString(R.string.toast_sleep_timer_ended))
      }
  }

  // ==================== Decoder ====================

  // ==================== Audio/Subtitle Management ====================

  fun addAudio(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val path =
          uri.resolveUri(host.context)
            ?: return@launch withContext(Dispatchers.Main) {
              showToast("Failed to load audio file: Invalid URI")
            }

        PlayerLib.command("audio-add", path, "cached")
        withContext(Dispatchers.Main) {
          showToast("Audio track added")
        }
      }.onFailure { e ->
        withContext(Dispatchers.Main) {
          showToast("Failed to load audio: ${e.message}")
        }
        android.util.Log.e("PlayerViewModel", "Error adding audio", e)
      }
    }
  }

  /**
   * mpv 加载新文件时清空外挂字幕跟踪表。
   *
   * mpv 的轨道列表随文件重建，旧的 URI 记录不再对应任何轨道；不清的话恢复流程会以为
   * 「这条字幕已经加过了」而跳过，字幕就再也加不回来了（同一部片重开时必然踩到）。
   */
  fun resetExternalSubtitles() {
    _externalSubtitles.clear()
    mpvPathToUriMap.clear()
  }

  /** 用户主动添加外挂字幕（即发即忘）。要「加完再选中」请用 [loadSubtitle]。 */
  fun addSubtitle(uri: Uri, select: Boolean = true, silent: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
      loadSubtitle(uri, select, silent)
    }
  }

  /**
   * 把外挂字幕真正交给 mpv，**在调用方协程里顺序执行**。
   *
   * 与 [addSubtitle] 的唯一区别是「这里是挂起函数」：返回时 `sub-add` 命令已经下发。
   * 恢复播放状态必须用这个版本 —— `addSubtitle` 是即发即忘的，而恢复流程紧接着就要用
   * 存档里的 `sid` 选中字幕，那时轨道还没建出来，mpv 会把这次选择当成「不存在的轨道」
   * 直接丢弃。表现就是「上次加的外挂字幕，重开 app 就没了」。
   *
   * 注意：命令下发 ≠ 轨道已就绪。mpv 挂轨道是异步的，之后还要 [awaitTrackCountAtLeast] 等一拍。
   */
  internal suspend fun loadSubtitle(uri: Uri, select: Boolean, silent: Boolean) {
    val uriString = uri.toString()
    if (_externalSubtitles.contains(uriString)) {
      Log.d("PlayerViewModel", "Subtitle already tracked, skipping: $uriString")
      return
    }

    runCatching {
      val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"

      if (!isValidSubtitleFile(fileName)) {
        if (!silent) withContext(Dispatchers.Main) { showToast("Invalid subtitle file format") }
        return
      }

      // Take persistent URI permission for content:// URIs
      if (uri.scheme == "content") {
        try {
          host.context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        } catch (e: SecurityException) {
          // Permission already granted, not available, or not needed (e.g. from tree).
          Log.i("PlayerViewModel", "Persistent permission not taken for $uri (may already have it via tree)")
        }
      }

      val mpvPath = uri.resolveUri(host.context) ?: uri.toString()

      // Store mapping for reliable physical deletion later
      mpvPathToUriMap[mpvPath] = uriString

      PlayerLib.command("sub-add", mpvPath, if (select) "select" else "auto")

      // Track external subtitle URI for persistence
      if (!_externalSubtitles.contains(uriString)) _externalSubtitles.add(uriString)

      val displayName = fileName.take(30).let { if (fileName.length > 30) "$it..." else it }
      if (!silent) withContext(Dispatchers.Main) { showToast("$displayName added") }
    }.onFailure {
      if (!silent) withContext(Dispatchers.Main) { showToast("Failed to load subtitle") }
    }
  }

  /**
   * 等 mpv 的轨道列表涨到至少 [target] 条（最多等 [timeoutMs]）。
   *
   * `sub-add` 是异步命令：命令返回那一刻轨道还没进 `track-list`，这时候去设 `sid`
   * 会被 mpv 当成「不存在的轨道」丢掉。恢复外挂字幕时必须等这一拍。
   */
  internal suspend fun awaitTrackCountAtLeast(
    target: Int,
    timeoutMs: Long = 1000,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if ((PlayerLib.getPropertyInt("track-list/count") ?: 0) >= target) return
      delay(30)
    }
  }

  private fun scanLocalSubtitles(mediaTitle: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val saveFolderUri = subtitlesPreferences.subtitleSaveFolder.get()
      if (saveFolderUri.isBlank()) return@launch
      
      try {
        val sanitizedTitle = MediaInfoParser.parse(mediaTitle).title
        val fullTitle = mediaTitle.substringBeforeLast(".")
        val checksumTitle = ChecksumUtils.getCRC32(mediaTitle)
        val parentDir = DocumentFile.fromTreeUri(host.context, Uri.parse(saveFolderUri)) ?: return@launch
        
        // Scan potential folder names for compatibility: checksum, full, and sanitized
        listOf(checksumTitle, fullTitle, sanitizedTitle).distinct().forEach { folderName ->
          val movieDir = parentDir.findFile(folderName) ?: return@forEach
          if (movieDir.isDirectory) {
            movieDir.listFiles().forEach { file ->
              if (file.isFile && isValidSubtitleFile(file.name ?: "")) {
                withContext(Dispatchers.Main) {
                  // Don't auto-select during scan, just make available
                  addSubtitle(file.uri, select = false, silent = true)
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("PlayerViewModel", "Error scanning local subtitles: ${e.message}", e)
      }
    }
  }

  fun setMediaTitle(mediaTitle: String) {
    if (currentMediaTitle != mediaTitle) {
      currentMediaTitle = mediaTitle
      lastAutoSelectedMediaTitle = null
      // Clear external subtitles when media changes
      _externalSubtitles.clear()
      // Scan for previously downloaded/added subtitles
      scanLocalSubtitles(mediaTitle)

      // 1. Reset Aspect Ratio to saved preference
      val savedAspect = playerPreferences.defaultVideoAspect.get()
      val savedCustomRatio = playerPreferences.defaultCustomAspectRatio.get()
      
      if (savedCustomRatio > 0) {
        // Apply saved custom aspect ratio
        _currentAspectRatio.value = savedCustomRatio
        runCatching {
          PlayerLib.setPropertyDouble("panscan", 0.0)
          PlayerLib.setPropertyDouble("video-aspect-override", savedCustomRatio)
        }
      } else {
        // Apply saved standard aspect mode (Fit, Crop, or Stretch)
        _videoAspect.value = savedAspect
        _currentAspectRatio.value = -1.0
        runCatching {
          when (savedAspect) {
            VideoAspect.Fit -> {
              PlayerLib.setPropertyDouble("panscan", 0.0)
              PlayerLib.setPropertyDouble("video-aspect-override", -1.0)
            }
            VideoAspect.Crop -> {
              PlayerLib.setPropertyDouble("video-aspect-override", -1.0)
              PlayerLib.setPropertyDouble("panscan", 1.0)
            }
            VideoAspect.Stretch -> {
              @Suppress("DEPRECATION")
              val dm = DisplayMetrics()
              @Suppress("DEPRECATION")
              host.hostWindowManager.defaultDisplay.getRealMetrics(dm)
              val rotate = PlayerLib.getPropertyInt("video-params/rotate") ?: 0
              val isVideoRotated = (rotate % 180 == 90)
              val screenRatio = if (isVideoRotated) {
                dm.heightPixels.toDouble() / dm.widthPixels.toDouble()
              } else {
                dm.widthPixels.toDouble() / dm.heightPixels.toDouble()
              }
              PlayerLib.setPropertyDouble("video-aspect-override", screenRatio)
              PlayerLib.setPropertyDouble("panscan", 0.0)
            }
          }
        }
      }

      // 2. Reset Video Zoom
      if (_videoZoom.value != 0f) {
          _videoZoom.value = 0f
          runCatching { PlayerLib.setPropertyDouble("video-zoom", 0.0) }
      }

      // 3. Reset Video Pan
      if (_videoPanX.value != 0f || _videoPanY.value != 0f) {
          _videoPanX.value = 0f
          _videoPanY.value = 0f
          runCatching {
              PlayerLib.setPropertyDouble("video-pan-x", 0.0)
              PlayerLib.setPropertyDouble("video-pan-y", 0.0)
          }
      }
      // ---------------------------------------------------
    }
  }


  fun getPrimarySubtitleId(): Int =
    PlayerLib.getPropertyString("sid")?.toIntOrNull() ?: 0

  fun getSecondarySubtitleId(): Int =
    PlayerLib.getPropertyString("secondary-sid")?.toIntOrNull() ?: 0

  fun removeSubtitle(id: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val primarySid = getPrimarySubtitleId()
      val secondarySid = getSecondarySubtitleId()

      // If removing the primary subtitle while a secondary subtitle is active,
      // promote secondary to primary so the remaining subtitle stays at the bottom.
      // Must clear secondary-sid FIRST so mpv does not reject assigning that track to sid.
      if (id == primarySid) {
        if (secondarySid > 0 && secondarySid != id) {
          PlayerLib.setPropertyString("secondary-sid", "no")
          PlayerLib.setPropertyInt("sid", secondarySid)
        } else {
          PlayerLib.setPropertyString("sid", "no")
        }
      } else if (id == secondarySid) {
        PlayerLib.setPropertyString("secondary-sid", "no")
      }

      // Find the subtitle track info before removing
      val tracks = subtitleTracks.value
      val trackToRemove = tracks.firstOrNull { it.id == id }

      // Removing an external subtitle only detaches it from the player and stops
      // tracking it. It must NOT delete the underlying file from disk: external
      // subtitles are user-provided (e.g. downloaded with another app), so
      // deleting them would cause unexpected data loss.
      if (trackToRemove?.external == true && trackToRemove.externalFilename != null) {
        val mpvPath = trackToRemove.externalFilename
        val originalUriString = mpvPathToUriMap[mpvPath] ?: mpvPath
        _externalSubtitles.remove(originalUriString)
        mpvPathToUriMap.remove(mpvPath)
      }

      PlayerLib.command("sub-remove", id.toString())
    }
  }

  fun toggleSubtitle(id: Int) {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()

    when {
      id == primarySid -> {
        // User disabled the primary subtitle while a secondary subtitle is active:
        // Promote secondary to primary so the single remaining subtitle is positioned at the bottom.
        // MUST clear secondary-sid FIRST so mpv does not reject assigning it to sid.
        if (secondarySid > 0 && secondarySid != id) {
          PlayerLib.setPropertyString("secondary-sid", "no")
          PlayerLib.setPropertyInt("sid", secondarySid)
        } else {
          PlayerLib.setPropertyString("sid", "no")
        }
      }
      id == secondarySid -> {
        // User disabled the secondary subtitle (top). Primary stays at bottom.
        PlayerLib.setPropertyString("secondary-sid", "no")
      }
      primarySid <= 0 -> {
        if (secondarySid > 0) {
          if (secondarySid == id) {
            PlayerLib.setPropertyString("secondary-sid", "no")
            PlayerLib.setPropertyInt("sid", id)
          } else {
            val prevSecondary = secondarySid
            PlayerLib.setPropertyString("secondary-sid", "no")
            PlayerLib.setPropertyInt("sid", prevSecondary)
            PlayerLib.setPropertyInt("secondary-sid", id)
          }
        } else {
          PlayerLib.setPropertyInt("sid", id)
        }
      }
      secondarySid <= 0 -> {
        if (primarySid != id) {
          PlayerLib.setPropertyInt("secondary-sid", id)
        }
      }
      else -> {
        // Both slots occupied; replace primary subtitle
        PlayerLib.setPropertyInt("sid", id)
      }
    }
  }

  /**
   * Ensures that if only one subtitle track is active, it is always the primary
   * subtitle (`sid`), which is positioned at the bottom of the screen.
   */
  fun normalizeSubtitles() {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()
    if (primarySid <= 0 && secondarySid > 0) {
      PlayerLib.setPropertyString("secondary-sid", "no")
      PlayerLib.setPropertyInt("sid", secondarySid)
    }
  }

  fun isSubtitleSelected(id: Int): Boolean {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()
    return (id == primarySid && primarySid > 0) || (id == secondarySid && secondarySid > 0)
  }

  private fun getFileNameFromUri(uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        host.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

      "file" -> uri.lastPathSegment
      else -> uri.lastPathSegment
    }

  private fun isValidSubtitleFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in VALID_SUBTITLE_EXTENSIONS

  // ==================== Playback Control ====================

  fun pauseUnpause() {
    viewModelScope.launch(Dispatchers.IO) {
      val isPaused = PlayerLib.getPropertyBoolean("pause") ?: false
      if (isPaused) {
        // We are about to unpause, so request focus
        withContext(Dispatchers.Main) { host.requestAudioFocus() }
        PlayerLib.setPropertyBoolean("pause", false)
      } else {
        // We are about to pause
        PlayerLib.setPropertyBoolean("pause", true)
        withContext(Dispatchers.Main) { host.abandonAudioFocus() }
      }
    }
  }

  fun pause() {
    viewModelScope.launch(Dispatchers.IO) {
      PlayerLib.setPropertyBoolean("pause", true)
      withContext(Dispatchers.Main) { host.abandonAudioFocus() }
    }
  }

  fun unpause() {
    viewModelScope.launch(Dispatchers.IO) {
      withContext(Dispatchers.Main) { host.requestAudioFocus() }
      PlayerLib.setPropertyBoolean("pause", false)
    }
  }

  // ==================== UI Control ====================

  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.isAppearanceLightStatusBars = false
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      // Defensive: InsetsController animation can crash under FD pressure
      // (e.g. during high-res HEVC playback on certain devices)
      Log.e(TAG, "Failed to show system bars", e)
    }
    _controlsShown.value = true
  }

  fun hideControls() {
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars", e)
    }
    _controlsShown.value = false
    _seekBarShown.value = false
  }

  fun autoHideControls() {
    try {
      if (playerPreferences.showSystemStatusBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
      }
      if (playerPreferences.showSystemNavigationBar.get()) {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars", e)
    }
    _controlsShown.value = false
    _seekBarShown.value = true
  }

  fun showSeekBar() {
    if (sheetShown.value == Sheets.None) {
      _seekBarShown.value = true
    }
  }

  fun hideSeekBar() {
    _seekBarShown.value = false
  }

  fun lockControls() {
    _areControlsLocked.value = true
  }

  fun unlockControls() {
    _areControlsLocked.value = false
  }

  // ==================== Seeking ====================

  fun seekBy(offset: Int) {
    coalesceSeek(offset)
  }

  fun seekTo(position: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val maxDuration = PlayerLib.getPropertyInt("duration") ?: 0
      var clampedPosition = position.coerceIn(0, maxDuration)

      // Clamp within AB loop if active
      val loopA = _abLoopA.value
      val loopB = _abLoopB.value
      if (loopA != null && loopB != null) {
        val min = minOf(loopA.toInt(), loopB.toInt())
        val max = maxOf(loopA.toInt(), loopB.toInt())
        clampedPosition = clampedPosition.coerceIn(min, max)
      }

      if (clampedPosition !in 0..maxDuration) return@launch

      // Cancel pending relative seek before absolute seek
      seekCoalesceJob?.cancel()
      pendingSeekOffset = 0
      
      // Use precise seeking for videos shorter than 2 minutes (120 seconds) or if preference is enabled
      val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || maxDuration < 120
      val seekMode = if (shouldUsePreciseSeeking) "absolute+exact" else "absolute+keyframes"
      PlayerLib.command("seek", clampedPosition.toString(), seekMode)
    }
  }

  private fun coalesceSeek(offset: Int) {
    pendingSeekOffset += offset
    seekCoalesceJob?.cancel()
    seekCoalesceJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(SEEK_COALESCE_DELAY_MS)
        val toApply = pendingSeekOffset
        pendingSeekOffset = 0
        
        if (toApply != 0) {
          val duration = PlayerLib.getPropertyInt("duration") ?: 0
          val currentPos = PlayerLib.getPropertyInt("time-pos") ?: 0
          
          if (duration > 0 && currentPos + toApply >= duration) {
              // If seeking past the end, force seek to 100% absolute to ensure EOF is triggered
              PlayerLib.command("seek", "100", "absolute-percent+exact")
          } else {
              // Use precise seeking for videos shorter than 2 minutes (120 seconds) or if preference is enabled
              val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || duration < 120
              val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
              PlayerLib.command("seek", toApply.toString(), seekMode)
          }
        }
      }
  }

  fun leftSeek() {
    if ((pos ?: 0) > 0) {
      _doubleTapSeekAmount.value -= doubleTapToSeekDuration
    }
    _isSeekingForwards.value = false
    seekBy(-doubleTapToSeekDuration)
  }

  fun rightSeek() {
    if ((pos ?: 0) < (duration ?: 0)) {
      _doubleTapSeekAmount.value += doubleTapToSeekDuration
    }
    _isSeekingForwards.value = true
    seekBy(doubleTapToSeekDuration)
  }

  fun updateSeekAmount(amount: Int) {
    _doubleTapSeekAmount.value = amount
  }

  fun updateSeekText(text: String?) {
    _seekText.value = text
  }

  fun updateIsSeekingForwards(isForwards: Boolean) {
    _isSeekingForwards.value = isForwards
  }

  private fun seekToWithText(
    seekValue: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    _isSeekingForwards.value = seekValue > currentPos
    _doubleTapSeekAmount.value = seekValue - currentPos
    _seekText.value = text
    seekTo(seekValue)
  }

  private fun seekByWithText(
    value: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    val maxDuration = duration ?: return

    _doubleTapSeekAmount.update {
      if ((value < 0 && it < 0) || currentPos + value > maxDuration) 0 else it + value
    }
    _seekText.value = text
    _isSeekingForwards.value = value > 0
    seekBy(value)
  }

  // ==================== Brightness & Volume ====================

  fun changeBrightnessTo(brightness: Float) {
    val coercedBrightness = brightness.coerceIn(0f, 1f)
    host.hostWindow.attributes =
      host.hostWindow.attributes.apply {
        screenBrightness = coercedBrightness
      }
    currentBrightness.value = coercedBrightness

    // Save brightness to preferences if enabled
    if (playerPreferences.rememberBrightness.get()) {
      playerPreferences.defaultBrightness.set(coercedBrightness)
    }
  }

  fun displayBrightnessSlider() {
    isBrightnessSliderShown.value = true
    brightnessSliderTimestamp.value = System.currentTimeMillis()
  }

  fun changeVolumeBy(change: Int) {
    val mpvVolume = PlayerLib.getPropertyInt("volume")
    val absoluteMaxVolume = volumeBoostCap ?: (audioPreferences.volumeBoostCap.get() + 100)

    if (absoluteMaxVolume > 100 && currentVolume.value == maxVolume) {
      if (mpvVolume == 100 && change < 0) {
        changeVolumeTo(currentVolume.value + change)
      }
      val finalMPVVolume = (mpvVolume?.plus(change))?.coerceAtLeast(100) ?: 100
      if (finalMPVVolume in 100..absoluteMaxVolume) {
        return changeMPVVolumeTo(finalMPVVolume)
      }
    }
    changeVolumeTo(currentVolume.value + change)
  }

  fun changeVolumeTo(volume: Int) {
    val newVolume = volume.coerceIn(0..maxVolume)
    host.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
    currentVolume.value = newVolume
  }

  fun changeMPVVolumeTo(volume: Int) {
    PlayerLib.setPropertyInt("volume", volume)
  }

  fun displayVolumeSlider() {
    isVolumeSliderShown.value = true
    volumeSliderTimestamp.value = System.currentTimeMillis()
  }

  // ==================== Video Aspect ====================

  fun changeVideoAspect(
    aspect: VideoAspect,
    showUpdate: Boolean = true,
  ) {
    when (aspect) {
      VideoAspect.Fit -> {
        // To FIT: Reset both properties to their defaults.
        PlayerLib.setPropertyDouble("panscan", 0.0)
        PlayerLib.setPropertyDouble("video-aspect-override", -1.0)
      }
      VideoAspect.Crop -> {
        // To CROP: Reset aspect override first, then set panscan
        PlayerLib.setPropertyDouble("video-aspect-override", -1.0)
        PlayerLib.setPropertyDouble("panscan", 1.0)
      }
      VideoAspect.Stretch -> {
        // To STRETCH: Calculate screen ratio accounting for video rotation
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        host.hostWindowManager.defaultDisplay.getRealMetrics(dm)
        
        // Get video rotation from metadata
        val rotate = PlayerLib.getPropertyInt("video-params/rotate") ?: 0
        val isVideoRotated = (rotate % 180 == 90) // 90° or 270° rotation
        
        // Calculate screen ratio, inverting if video is rotated
        val screenRatio = if (isVideoRotated) {
          // Video is rotated, so invert the screen ratio
          dm.heightPixels.toDouble() / dm.widthPixels.toDouble()
        } else {
          // Video is not rotated, use normal screen ratio
          dm.widthPixels.toDouble() / dm.heightPixels.toDouble()
        }

        // Set aspect override first, then reset panscan
        // This prevents the brief flash of Fit mode
        PlayerLib.setPropertyDouble("video-aspect-override", screenRatio)
        PlayerLib.setPropertyDouble("panscan", 0.0)
      }
    }

    // Update the state and persist to preferences
    _videoAspect.value = aspect
    _currentAspectRatio.value = -1.0 // Reset custom ratio when using standard modes
    playerPreferences.defaultVideoAspect.set(aspect)
    playerPreferences.defaultCustomAspectRatio.set(-1.0)

    // Notify the UI
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
    }
  }

  fun setCustomAspectRatio(ratio: Double) {
    PlayerLib.setPropertyDouble("panscan", 0.0)
    PlayerLib.setPropertyDouble("video-aspect-override", ratio)
    _currentAspectRatio.value = ratio
    playerPreferences.defaultCustomAspectRatio.set(ratio)
    playerUpdate.value = PlayerUpdates.AspectRatio
  }

  // ==================== Screen Rotation ====================

  /**
   * 本次播放会话中用户通过旋转按钮手动指定的屏幕方向。
   *
   * null 表示未手动覆盖，跟随偏好设置。切集（playNext / playPrevious）时沿用该值，
   * 避免用户手动转到的方向被偏好设置强制拉回。
   */
  private var manualOrientationOverride: Int? = null

  /** 当前生效的手动方向覆盖值；null 表示未覆盖（应跟随偏好设置） */
  val manualOrientationOverrideValue: Int?
    get() = manualOrientationOverride

  fun cycleScreenRotations() {
    // 临时切换方向但**不修改偏好设置**：偏好仍是持久化的事实来源，
    // 但本次会话内会记住用户的选择，切集时沿用而不是回退到偏好。
    val next =
      when (host.hostRequestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        -> {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        else -> {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
      }

    manualOrientationOverride = next
    host.hostRequestedOrientation = next
  }

  // ==================== Gesture Handling ====================

  fun handleLeftDoubleTap() {
    when (gesturePreferences.leftSingleActionGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapLeft.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  fun handleCenterDoubleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleCenterSingleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleRightDoubleTap() {
    when (gesturePreferences.rightSingleActionGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.DoubleTapRight.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  // ==================== Video Zoom ====================

  fun setVideoZoom(zoom: Float) {
    _videoZoom.value = zoom
    PlayerLib.setPropertyDouble("video-zoom", zoom.toDouble())
  }

  // Video pan (for pan & zoom feature)
  private val _videoPanX = MutableStateFlow(0f)
  val videoPanX: StateFlow<Float> = _videoPanX.asStateFlow()

  private val _videoPanY = MutableStateFlow(0f)
  val videoPanY: StateFlow<Float> = _videoPanY.asStateFlow()

  fun setVideoPan(x: Float, y: Float) {
    _videoPanX.value = x
    _videoPanY.value = y
    PlayerLib.setPropertyDouble("video-pan-x", x.toDouble())
    PlayerLib.setPropertyDouble("video-pan-y", y.toDouble())
  }

  fun resetVideoPan() {
    setVideoPan(0f, 0f)
  }

  fun resetVideoZoom() {
    setVideoZoom(0f)
  }

  // ==================== Frame Navigation ====================

  fun updateFrameInfo() {
    _currentFrame.value = PlayerLib.getPropertyInt("estimated-frame-number") ?: 0

    val durationValue = PlayerLib.getPropertyDouble("duration") ?: 0.0
    val fps =
      PlayerLib.getPropertyDouble("container-fps")
        ?: PlayerLib.getPropertyDouble("estimated-vf-fps")
        ?: 0.0

    _totalFrames.value =
      if (durationValue > 0 && fps > 0) {
        (durationValue * fps).toInt()
      } else {
        0
      }
  }

  fun toggleFrameNavigationExpanded() {
    val wasExpanded = _isFrameNavigationExpanded.value
    _isFrameNavigationExpanded.update { !it }
    // Update frame info and pause when expanding (going from false to true)
    if (!wasExpanded) {
      // Pause the video if it's playing
      if (paused != true) {
        pauseUnpause()
      }
      updateFrameInfo()
      showFrameInfoOverlay()
      resetFrameNavigationTimer()
    } else {
      // Cancel timer when manually collapsing
      frameNavigationCollapseJob?.cancel()
    }
  }

  private fun showFrameInfoOverlay() {
    playerUpdate.value = PlayerUpdates.FrameInfo(_currentFrame.value, _totalFrames.value)
  }

  fun frameStepForward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlayerLib.command("no-osd", "frame-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  fun frameStepBackward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlayerLib.command("no-osd", "frame-back-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  private var frameNavigationCollapseJob: Job? = null

  fun resetFrameNavigationTimer() {
    frameNavigationCollapseJob?.cancel()
    frameNavigationCollapseJob = viewModelScope.launch {
      delay(10000) // 10 seconds
      if (_isFrameNavigationExpanded.value) {
        _isFrameNavigationExpanded.value = false
      }
    }
  }

  fun takeSnapshot(context: Context) {
    viewModelScope.launch(Dispatchers.IO) {
      _isSnapshotLoading.value = true
      try {
        val includeSubtitles = playerPreferences.includeSubtitlesInSnapshot.get()

        // Generate filename with timestamp
        val timestamp =
          java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val filename = "mpv_snapshot_$timestamp.png"

        // Create a temporary file first
        val tempFile = File(context.cacheDir, filename)

        // Take screenshot using MPV to temp file, with or without subtitles
        if (includeSubtitles) {
          PlayerLib.command("screenshot-to-file", tempFile.absolutePath, "subtitles")
        } else {
          PlayerLib.command("screenshot-to-file", tempFile.absolutePath, "video")
        }

        // Wait a bit for MPV to finish writing the file
        delay(200)

        // Check if file was created
        if (!tempFile.exists() || tempFile.length() == 0L) {
          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to create screenshot", Toast.LENGTH_SHORT).show()
          }
          return@launch
        }

        // Use different methods based on Android version
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          // Android 10+ - Use MediaStore with RELATIVE_PATH
          val contentValues =
            android.content.ContentValues().apply {
              put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
              put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
              put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_PICTURES}/mpvSnaps",
              )
              put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }

          val resolver = context.contentResolver
          val imageUri =
            resolver.insert(
              android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              contentValues,
            )

          if (imageUri != null) {
            // Copy temp file to MediaStore
            resolver.openOutputStream(imageUri)?.use { outputStream ->
              tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
              }
            }

            // Mark as finished
            contentValues.clear()
            contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(imageUri, contentValues, null, null)

            // Delete temp file
            tempFile.delete()

            // Show success toast
            withContext(Dispatchers.Main) {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
                  Toast.LENGTH_SHORT,
                ).show()
            }
          } else {
            throw Exception("Failed to create MediaStore entry")
          }
        } else {
          // Android 9 and below - Use legacy external storage
          val picturesDir =
            android.os.Environment.getExternalStoragePublicDirectory(
              android.os.Environment.DIRECTORY_PICTURES,
            )
          val snapshotsDir = File(picturesDir, "mpvSnaps")

          // Create directory if it doesn't exist
          if (!snapshotsDir.exists()) {
            val created = snapshotsDir.mkdirs()
            if (!created && !snapshotsDir.exists()) {
              throw Exception("Failed to create mpvSnaps directory")
            }
          }

          val destFile = File(snapshotsDir, filename)
          tempFile.copyTo(destFile, overwrite = true)
          tempFile.delete()

          // Notify media scanner about the new file
          android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf("image/png"),
            null,
          )

          withContext(Dispatchers.Main) {
            Toast
              .makeText(
                context,
                context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
                Toast.LENGTH_SHORT,
              ).show()
          }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast.makeText(context, "Failed to save snapshot: ${e.message}", Toast.LENGTH_LONG).show()
        }
      } finally {
        _isSnapshotLoading.value = false
      }
    }
  }

  // ==================== Playlist Management ====================

  fun hasPlaylistSupport(): Boolean {
    val playlistModeEnabled = playerPreferences.playlistMode.get()
    return playlistModeEnabled && ((host as? PlayerActivity)?.playlist?.isNotEmpty() ?: false)
  }

  fun getPlaylistInfo(): String? {
    val activity = host as? PlayerActivity ?: return null
    if (activity.playlist.isEmpty()) return null

    val totalCount = getPlaylistTotalCount()
    return "${activity.playlistIndex + 1}/$totalCount"
  }

  fun isPlaylistM3U(): Boolean {
    val activity = host as? PlayerActivity ?: return false
    return activity.isCurrentPlaylistM3U()
  }

  fun getPlaylistTotalCount(): Int {
    val activity = host as? PlayerActivity ?: return 0
    return activity.playlist.size
  }

  fun getPlaylistData(): List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>? {
    val activity = host as? PlayerActivity ?: return null
    if (activity.playlist.isEmpty()) return null

    // Get current video progress
    val currentPos = pos ?: 0
    val currentDuration = duration ?: 0
    val currentProgress = if (currentDuration > 0) {
      ((currentPos.toFloat() / currentDuration.toFloat()) * 100f).coerceIn(0f, 100f)
    } else 0f

    return activity.playlist.mapIndexed { index, uri ->
      val title = activity.getPlaylistItemTitle(uri, index)
      // Path is not used for thumbnail loading - thumbnails are loaded directly from URI
      // Keep it for cache key compatibility
      val path = uri.toString()
      val isCurrentlyPlaying = index == activity.playlistIndex

      // Try to get from cache first (synchronized access)
      val cacheKey = uri.toString()
      val (durationStr, resolutionStr) = synchronized(metadataCache) { metadataCache[cacheKey] } ?: ("" to "")

      app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem(
        uri = uri,
        title = title,
        index = index,
        isPlaying = isCurrentlyPlaying,
        path = path,
        progressPercent = if (isCurrentlyPlaying) currentProgress else 0f,
        isWatched = isCurrentlyPlaying && currentProgress >= 95f,
        duration = durationStr,
        resolution = resolutionStr,
        // Emby 播放源：用背景图作列表缩略图（本地文件 / strm 直链没有该模式，留空）
        thumbnailUrl = embyBackdropUrl(uri) ?: "",
      )
    }
  }

  /**
   * 从 Emby 播放流地址派生背景图地址，作播放列表缩略图。
   *
   * 流地址形如 `http(s)://host[:port]/emby/Videos/{itemId}/stream?...&api_key=...`，
   * 图片地址就是同源下的 `/emby/Items/{itemId}/Images/Backdrop`（api_key 原样带上，
   * static 等视频参数丢弃）。strm 指向的 CDN 直链解析不出该模式时返回 null ——
   * 走原有的本地缩略图 / 占位图标路径，不会对 CDN 发起任何多余请求。
   */
  private fun embyBackdropUrl(uri: Uri): String? {
    if (uri.scheme != "http" && uri.scheme != "https") return null
    val match = EMBY_STREAM_PATH_REGEX.find(uri.path ?: return null) ?: return null
    val itemId = match.groupValues[1]
    val builder =
      uri
        .buildUpon()
        .path("/emby/Items/$itemId/Images/Backdrop")
        .query(null)
        .appendQueryParameter("maxWidth", "512")
        .appendQueryParameter("quality", "90")
    uri.getQueryParameter("api_key")?.let { builder.appendQueryParameter("api_key", it) }
    return builder.build().toString()
  }

  private fun getVideoMetadata(uri: Uri): Pair<String, String> {
    // Skip metadata extraction for network streams and M3U playlists
    if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      return "" to ""
    }

    // Skip M3U/M3U8 files
    val uriString = uri.toString().lowercase()
    if (uriString.contains(".m3u8") || uriString.contains(".m3u")) {
      return "" to ""
    }

    // Try MediaStore first (much faster - uses cached values)
    val mediaStoreMetadata = getVideoMetadataFromMediaStore(uri)
    if (mediaStoreMetadata != null) {
      return mediaStoreMetadata
    }

    // Fallback to MediaMetadataRetriever only if MediaStore fails
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      // For file:// URIs, use the path directly (faster)
      if (uri.scheme == "file") {
        retriever.setDataSource(uri.path)
      } else {
        // For content:// URIs, use context
        retriever.setDataSource(host.context, uri)
      }

      // Get duration
      val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
      val durationStr = if (durationMs != null) {
        formatDuration(durationMs.toLong())
      } else ""

      // Get resolution
      val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
      val resolutionStr = if (width != null && height != null) {
        "${width}x${height}"
      } else ""

      durationStr to resolutionStr
    } catch (e: Exception) {
      android.util.Log.e("PlayerViewModel", "Failed to get video metadata for $uri", e)
      "" to ""
    } finally {
      try {
        retriever.release()
      } catch (e: Exception) {
        // Ignore release errors
      }
    }
  }

  /**
   * Get video metadata from MediaStore (fast - uses cached system values).
   * Returns null if the video is not found in MediaStore.
   */
  private fun getVideoMetadataFromMediaStore(uri: Uri): Pair<String, String>? {
    return try {
      val projection = arrayOf(
        android.provider.MediaStore.Video.Media.DURATION,
        android.provider.MediaStore.Video.Media.WIDTH,
        android.provider.MediaStore.Video.Media.HEIGHT,
        android.provider.MediaStore.Video.Media.DATA
      )

      // Determine the query URI based on the input URI scheme
      val queryUri = when (uri.scheme) {
        "content" -> {
          // If it's already a content URI, use it directly
          if (uri.toString().startsWith(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())) {
            uri
          } else {
            // Try to find by path if available
            null
          }
        }
        "file" -> {
          // For file:// URIs, query by path
          null
        }
        else -> null
      }

      // Query by URI if we have a content URI
      if (queryUri != null) {
        host.context.contentResolver.query(
          queryUri,
          projection,
          null,
          null,
          null
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

            val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
            val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
            val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

            val durationStr = formatDuration(durationMs)

            val resolutionStr = if (width > 0 && height > 0) {
              "${width}x${height}"
            } else ""

            return durationStr to resolutionStr
          }
        }
      }

      // Query by file path if we have a file:// URI or content URI without direct match
      val filePath = when (uri.scheme) {
        "file" -> uri.path
        "content" -> {
          // Try to get the file path from content URI
          host.context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Video.Media.DATA),
            null,
            null,
            null
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val dataColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
              if (dataColumn >= 0) cursor.getString(dataColumn) else null
            } else null
          }
        }
        else -> null
      }

      if (filePath != null) {
        val selection = "${android.provider.MediaStore.Video.Media.DATA} = ?"
        val selectionArgs = arrayOf(filePath)

        host.context.contentResolver.query(
          android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          null
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

            val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
            val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
            val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

            val durationStr = formatDuration(durationMs)

            val resolutionStr = if (width > 0 && height > 0) {
              "${width}x${height}"
            } else ""

            return durationStr to resolutionStr
          }
        }
      }

      null
    } catch (e: Exception) {
      android.util.Log.w("PlayerViewModel", "Failed to get metadata from MediaStore for $uri, will try MediaMetadataRetriever", e)
      null
    }
  }

  /**
   * Format duration in milliseconds to hh:mm:ss or mm:ss format
   */
  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""
    
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
      String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format("%d:%02d", minutes, seconds)
    }
  }
  
  

  fun playPlaylistItem(index: Int) {
    val activity = host as? PlayerActivity ?: return
    activity.playPlaylistItem(index)
  }

  /**
   * Refreshes the playlist items to update the currently playing indicator.
   * Called when a new video starts playing to update the playlist UI.
   */
  fun refreshPlaylistItems() {
    viewModelScope.launch(Dispatchers.IO) {
      val updatedItems = getPlaylistData()
      if (updatedItems != null) {
        // Clear cache if playlist size changed
        if (_playlistItems.value.size != updatedItems.size) {
          metadataCache.evictAll()
        }

        _playlistItems.value = updatedItems

        // Load metadata asynchronously in the background
        loadPlaylistMetadataAsync(updatedItems)
      }
    }
  }

  /**
   * Loads metadata for all playlist items asynchronously in the background.
   * Updates the playlist items as metadata becomes available.
   * Uses batched updates to avoid O(n²) complexity with large playlists.
   * Skips metadata extraction for M3U playlists (network streams).
   */
  private fun loadPlaylistMetadataAsync(items: List<app.marlboroadvance.mpvex.ui.player.controls.components.sheets.PlaylistItem>) {
    viewModelScope.launch(Dispatchers.IO) {
      // Skip metadata extraction for M3U playlists
      val activity = host as? PlayerActivity
      if (activity?.isCurrentPlaylistM3U() == true) {
        Log.d(TAG, "Skipping metadata extraction for M3U playlist")
        return@launch
      }

      // Limit concurrent metadata extraction to avoid overwhelming resources
      val batchSize = 5
      items.chunked(batchSize).forEach { batch ->
        val updates = mutableMapOf<String, Pair<String, String>>()

        // Extract metadata for the batch
        batch.forEach { item ->
          val cacheKey = item.uri.toString()

          // Skip if already in cache (LruCache is thread-safe)
          if (metadataCache.get(cacheKey) == null) {
            // Extract metadata
            val (durationStr, resolutionStr) = getVideoMetadata(item.uri)

            // Update cache and track update
            updateMetadataCache(cacheKey, durationStr to resolutionStr)
            updates[cacheKey] = durationStr to resolutionStr
          }
        }

        // Apply all batched updates at once (single playlist update)
        if (updates.isNotEmpty()) {
          _playlistItems.value = _playlistItems.value.map { currentItem ->
            val cacheKey = currentItem.uri.toString()
            val (durationStr, resolutionStr) = updates[cacheKey] ?: return@map currentItem
            currentItem.copy(duration = durationStr, resolution = resolutionStr)
          }
        }
      }
    }
  }

  fun hasNext(): Boolean = (host as? PlayerActivity)?.hasNext() ?: false

  /** 队列内拖动排序：[to] 是移动之后的目标下标（见 PlayerActivity.movePlaylistItem）。 */
  fun movePlaylistItem(
    from: Int,
    to: Int,
  ) {
    (host as? PlayerActivity)?.movePlaylistItem(from, to)
  }

  /**
   * 从队列移除一条。
   *
   * @return 是否移除成功 —— 队列只剩一条时会拒绝（返回 false），由 UI 决定怎么提示。
   */
  fun removePlaylistItem(index: Int): Boolean = (host as? PlayerActivity)?.removePlaylistItem(index) ?: false

  fun hasPrevious(): Boolean = (host as? PlayerActivity)?.hasPrevious() ?: false

  fun playNext() {
    (host as? PlayerActivity)?.playNext()
  }

  fun playPrevious() {
    (host as? PlayerActivity)?.playPrevious()
  }

  // ==================== Repeat and Shuffle ====================

  fun applyPersistedShuffleState() {
    if (_shuffleEnabled.value) {
      val activity = host as? PlayerActivity
      activity?.onShuffleToggled(true)
    }
  }

  fun cycleRepeatMode() {
    val hasPlaylist = (host as? PlayerActivity)?.playlist?.isNotEmpty() == true

    _repeatMode.value = when (_repeatMode.value) {
      RepeatMode.OFF -> RepeatMode.ONE
      RepeatMode.ONE -> if (hasPlaylist) RepeatMode.ALL else RepeatMode.OFF
      RepeatMode.ALL -> RepeatMode.OFF
    }

    // Persist the repeat mode
    playerPreferences.repeatMode.set(_repeatMode.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.RepeatMode(_repeatMode.value)
  }

  /**
   * 直接设置循环模式（「更多」面板里的「循环播放」开关走这条路径）。
   *
   * 与 [cycleRepeatMode] 的区别：这里是「置为指定值」而不是「下一档」，
   * 开关的两态（开 / 关）才能和循环模式一一对应；同样会持久化并弹出浮层提示。
   */
  fun setRepeatMode(mode: RepeatMode) {
    if (_repeatMode.value == mode) return
    _repeatMode.value = mode
    playerPreferences.repeatMode.set(mode)
    playerUpdate.value = PlayerUpdates.RepeatMode(mode)
  }

  fun toggleShuffle() {
    _shuffleEnabled.value = !_shuffleEnabled.value
    val activity = host as? PlayerActivity

    // Persist the shuffle state
    playerPreferences.shuffleEnabled.set(_shuffleEnabled.value)

    // Notify activity to handle shuffle state change
    activity?.onShuffleToggled(_shuffleEnabled.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.Shuffle(_shuffleEnabled.value)
  }

  fun shouldRepeatCurrentFile(): Boolean {
    return _repeatMode.value == RepeatMode.ONE ||
      (_repeatMode.value == RepeatMode.ALL && (host as? PlayerActivity)?.playlist?.isEmpty() == true)
  }

  fun shouldRepeatPlaylist(): Boolean {
    return _repeatMode.value == RepeatMode.ALL && (host as? PlayerActivity)?.playlist?.isNotEmpty() == true
  }

  // ==================== A-B Loop ====================

  fun toggleABLoopExpanded() {
    _isABLoopExpanded.update { !it }
  }

  fun setLoopA() {
    if (_abLoopA.value != null) {
      // Toggle off - clear point A
      _abLoopA.value = null
      PlayerLib.setPropertyString("ab-loop-a", "no")
      return
    }

    val currentPos = PlayerLib.getPropertyDouble("time-pos") ?: return
    _abLoopA.value = currentPos
    PlayerLib.setPropertyDouble("ab-loop-a", currentPos)
  }

  fun setLoopB() {
    if (_abLoopB.value != null) {
      // Toggle off - clear point B
      _abLoopB.value = null
      PlayerLib.setPropertyString("ab-loop-b", "no")
      return
    }

    val currentPos = PlayerLib.getPropertyDouble("time-pos") ?: return
    _abLoopB.value = currentPos
    PlayerLib.setPropertyDouble("ab-loop-b", currentPos)
  }

  fun clearABLoop() {
    val hadLoop = _abLoopA.value != null || _abLoopB.value != null
    _abLoopA.value = null
    _abLoopB.value = null
    PlayerLib.setPropertyString("ab-loop-a", "no")
    PlayerLib.setPropertyString("ab-loop-b", "no")
  }

  fun formatTimestamp(seconds: Double): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
  }

  // ==================== Mirroring ====================

  fun toggleMirroring() {
    val newMirrorState = !_isMirrored.value
    _isMirrored.value = newMirrorState
    
    // Use labeled video filter for mirroring to avoid state desync
    if (newMirrorState) {
      PlayerLib.command("vf", "add", "@mpvex_hflip:hflip")
    } else {
      PlayerLib.command("vf", "remove", "@mpvex_hflip")
    }
    playerUpdate.value = PlayerUpdates.ShowText(if (newMirrorState) "H-Flip On" else "H-Flip Off")
  }

  fun toggleVerticalFlip() {
    val newState = !_isVerticalFlipped.value
    _isVerticalFlipped.value = newState

    // Use labeled video filter for vflip to avoid state desync
    if (newState) {
      PlayerLib.command("vf", "add", "@mpvex_vflip:vflip")
    } else {
      PlayerLib.command("vf", "remove", "@mpvex_vflip")
    }

    playerUpdate.value = PlayerUpdates.ShowText(if (newState) "V-Flip On" else "V-Flip Off")
  }

  // ==================== 黑边自动裁切（cropdetect） ====================

  /** 当前是否已应用自动裁切，供 UI 显示状态。 */
  private val _isAutoCropped = MutableStateFlow(false)
  val isAutoCropped: StateFlow<Boolean> = _isAutoCropped.asStateFlow()

  /** 探测协程。同一时刻只允许一条，避免连点叠加出多条 cropdetect 滤镜。 */
  private var cropJob: Job? = null

  /**
   * 手动「自动裁黑边 / 还原」。
   *
   * 已在裁切状态时点击即还原 —— 探测不准（比如把暗场戏当成黑边）时用户能一键退回去。
   */
  fun toggleAutoCrop() {
    if (VideoCrop.isApplied) {
      VideoCrop.clear()
      _isAutoCropped.value = false
      playerUpdate.value = PlayerUpdates.ShowText(host.context.getString(R.string.player_crop_off))
      return
    }
    runAutoCrop(userInitiated = true)
  }

  /**
   * 「自动裁黑边」开关：写偏好并**立即**生效。
   *
   * 打开 → 马上对当前画面探测一次（不等下一次换片）；
   * 关闭 → 立刻摘掉已挂上的裁切，不然用户看着开关是关的、画面却还裁着。
   */
  fun setAutoCrop(enabled: Boolean) {
    playerPreferences.autoCropBlackBars.set(enabled)
    if (enabled) {
      runAutoCrop(userInitiated = true)
    } else {
      cropJob?.cancel()
      cropJob = null
      VideoCrop.clear()
      _isAutoCropped.value = false
      playerUpdate.value = PlayerUpdates.ShowText(host.context.getString(R.string.player_crop_off))
    }
  }

  /**
   * 每次加载新文件后按偏好套用裁切策略。
   *
   * - 开关打开 → 探测并裁切；
   * - 开关关闭 → **主动清一次**：上一条片子的裁切滤镜会留在 mpv 的 vf 链上，
   *   不清的话新片会带着上一条的黑边偏移继续播。
   */
  fun applyAutoCropPolicy() {
    if (playerPreferences.autoCropBlackBars.get()) {
      runAutoCrop(userInitiated = false)
    } else {
      VideoCrop.clear()
      _isAutoCropped.value = false
    }
  }

  private fun runAutoCrop(userInitiated: Boolean) {
    if (cropJob?.isActive == true) return
    cropJob =
      viewModelScope.launch {
        val crop = VideoCrop.detectAndApply()
        _isAutoCropped.value = crop != null
        if (userInitiated) {
          val msg =
            if (crop != null) {
              host.context.getString(R.string.player_crop_applied)
            } else {
              host.context.getString(R.string.player_crop_failed)
            }
          playerUpdate.value = PlayerUpdates.ShowText(msg)
        }
      }
  }

  /** 播放页退出 / 换内核时把裁切滤镜摘干净。 */
  fun clearAutoCrop() {
    cropJob?.cancel()
    cropJob = null
    VideoCrop.clear()
    _isAutoCropped.value = false
  }

  // ==================== GIF 片段录制 ====================

  /** 录制进度（0f..1f）；null 表示当前没有在录。 */
  private val _gifProgress = MutableStateFlow<Float?>(null)
  val gifProgress: StateFlow<Float?> = _gifProgress.asStateFlow()

  /**
   * 录制一段 GIF 并存入相册。
   *
   * 整个流程（取帧 → 编码 → 入库）都在协程里跑，UI 只订阅 [gifProgress]。
   * 重复点击会被忽略（[gifProgress] 非 null 时直接返回），避免两次录制互相抢帧。
   */
  fun startGifRecording(durationSec: Int) {
    if (_gifProgress.value != null) return
    viewModelScope.launch {
      _gifProgress.value = 0f
      var clip: GifRecorder.GifClip? = null
      try {
        // 采集是「跟着视频实时走」的，固定花掉 durationSec 秒；编码耗时则随分辨率增长。
        // 进度条把前 60% 给采集、后 40% 给编码：否则高分辨率下进度会先走满，
        // 然后一动不动地卡在 100% 干等编码，看着像卡死。
        val captureShare = 0.6f
        val captured =
          GifRecorder.captureFrames(
            host.context,
            durationSec,
            targetWidth = playerPreferences.gifWidth.get(),
          ) { progress ->
            _gifProgress.value = progress * captureShare
          }
        clip = captured
        if (captured == null) {
          showToast(host.context.getString(R.string.player_gif_failed))
          return@launch
        }
        val saved =
          saveGifToGallery(host.context, captured) { progress ->
            _gifProgress.value = captureShare + progress * (1f - captureShare)
          }
        showToast(
          host.context.getString(
            if (saved) R.string.player_gif_saved else R.string.player_gif_failed,
          ),
        )
      } catch (t: Throwable) {
        Log.w("PlayerViewModel", "GIF recording failed", t)
        showToast(host.context.getString(R.string.player_gif_failed))
      } finally {
        // 截图临时目录在这里统一清；采集帧现在留在磁盘上，不清会在 cache 里堆积
        clip?.cleanup()
        _gifProgress.value = null
      }
    }
  }

  /**
   * 把采集到的帧编码成 GIF 并写入系统相册。
   *
   * Android 10+ 走 MediaStore + RELATIVE_PATH（无需存储权限）；更低版本回落到
   * 公共 Pictures 目录 + 手动触发媒体扫描。
   *
   * 帧本身由 [GifRecorder.encode] 在磁盘上逐帧流式处理、编完即弃，所以这里不再需要
   * 「回收位图」；要清的只有那个临时 GIF 文件（截图目录归调用方的 cleanup 管）。
   */
  private suspend fun saveGifToGallery(
    context: Context,
    clip: GifRecorder.GifClip,
    onProgress: (Float) -> Unit,
  ): Boolean {
    val name =
      "cineisle_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.gif"
    val tempFile = File(context.cacheDir, name)
    return try {
      java.io.FileOutputStream(tempFile).use { out ->
        GifRecorder.encode(clip, out, onProgress)
      }
      if (!tempFile.exists() || tempFile.length() == 0L) {
        return false
      }

      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values =
          android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(
              android.provider.MediaStore.Images.Media.RELATIVE_PATH,
              "${android.os.Environment.DIRECTORY_PICTURES}/CineIsle",
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
          }
        val uri =
          context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
          ) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { output ->
          tempFile.inputStream().use { input -> input.copyTo(output) }
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
      } else {
        @Suppress("DEPRECATION")
        val dir =
          android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES,
          )
        val target = File(dir, "CineIsle").apply { mkdirs() }
        val dest = File(target, name)
        tempFile.copyTo(dest, overwrite = true)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
      }

      true
    } catch (t: Throwable) {
      Log.w("PlayerViewModel", "Failed to save GIF", t)
      false
    } finally {
      // 成功时已经拷进相册、失败时它是垃圾，两条路都不留
      tempFile.delete()
    }
  }

  // ==================== Utility ====================

  fun showToast(message: String) {
    Toast.makeText(host.context, message, Toast.LENGTH_SHORT).show()
  }

  override fun onCleared() {
    super.onCleared()
  }
}

/** Emby 播放流地址里的 itemId（`/emby/Videos/{id}/stream`），用于派生图片地址 */
private val EMBY_STREAM_PATH_REGEX = Regex("/emby/Videos/([^/?]+)/stream", RegexOption.IGNORE_CASE)

// Extension functions
fun Float.normalize(
  inMin: Float,
  inMax: Float,
  outMin: Float,
  outMax: Float,
): Float = (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin

fun <T> Flow<T>.collectAsState(
  scope: CoroutineScope,
  initialValue: T? = null,
) = object : ReadOnlyProperty<Any?, T?> {
  private var value: T? = initialValue

  init {
    scope.launch { collect { value = it } }
  }

  override fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ) = value
}
