package app.marlboroadvance.mpvex.ui.player.vlc

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.VlcPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekbarWithTimers
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AddTrackRow
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.AudioTrackRow
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.SubtitlesSheet
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.ui.theme.controlColor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * VLC 独立播放内核。
 *
 * 这是**第三个完整播放页**，不是 mpv/GSY 里的一个分支：
 *  - 自己持有 [LibVLC] / [MediaPlayer]；
 *  - 自己的 Compose 控件层；
 *  - 自己的偏好（[VlcPreferences]）；
 *  - 只通过 Intent / [EmbyPlaybackReporter] 与外部交互。
 *
 * 当前覆盖：播放 / 暂停 / 拖动 / 倍速 / 音轨 / 字幕轨 / 外挂字幕 / 画面比例 /
 * 画中画 / 后台播放 / 播放列表自动下一集 / Emby 进度回传。VLC 的原生字幕渲染
 * 可直接显示内嵌 ASS/PGS；投屏与完整手势后续按 VLC 自身能力补齐。
 */
class VlcPlayerActivity : ComponentActivity(), KoinComponent {
  private val prefs: VlcPreferences by inject()
  private val audioManager by lazy { getSystemService(AudioManager::class.java) }

  private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    pickerOpen = false
    controlsVisible.value = true
    if (uri != null) attachExternalSubtitle(uri)
  }

  private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    pickerOpen = false
    controlsVisible.value = true
    if (uri != null) attachExternalAudio(uri)
  }

  private var libVlc: LibVLC? = null
  private var mediaPlayer: MediaPlayer? = null
  private var videoLayoutRef: VLCVideoLayout? = null
  private var surfaceReady = false
  private var hasStarted = false
  private var enteringPip = false
  /** 系统文件选择器打开期间不要因为 onStop 把播放暂停（回来后用户会以为卡住了）。 */
  private var pickerOpen = false

  private var playlist: List<Uri> = emptyList()
  private var playlistTitles: List<String> = emptyList()
  private var currentIndex = 0
  private var embyItemIds: List<String> = emptyList()
  private var pendingStartMs = 0L
  /** 上一次选的外挂字幕：切集 / 换源后自动重新挂上。 */
  private var externalSubtitleFile: File? = null
  /** 上一次选的外挂音轨：切集 / 换源后自动重新挂上。 */
  private var externalAudioFile: File? = null
  private var reporterObserver: EmbyPlaybackReporter.EmbyPlaybackLifecycleObserver? = null

  private val uiState = mutableStateOf(VlcUiState())
  private var controlsVisible = mutableStateOf(true)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    readIntent(intent)
    uiState.value = uiState.value.copy(
      subtitleDelayMs = prefs.subtitleDelayMs.get().coerceIn(-5000, 5000),
      audioDelayMs = prefs.audioDelayMs.get().coerceIn(-5000, 5000),
      brightness = currentBrightnessFraction(),
      volumeFraction = currentVolumeFraction(),
    )
    runCatching { createPlayer() }.onFailure { error ->
      uiState.value = uiState.value.copy(
        error = "VLC 内核初始化失败：${describeThrowable(error)}",
        buffering = false,
      )
    }

    setContent {
      MpvexTheme {
        VlcPlayerScreen(
          player = mediaPlayer,
          ui = uiState.value,
          controlsVisible = controlsVisible.value,
          onToggleControls = { controlsVisible.value = !controlsVisible.value },
          onBack = { finish() },
          onTogglePlay = { togglePlay() },
          onSeek = { seekTo(it) },
          onSeekBy = { seekBy(it) },
          onSpeed = { setSpeed(it) },
          onAudioTrack = { selectAudioTrack(it) },
          onSubtitleTrack = { selectSubtitleTrack(it) },
          onPickSubtitle = {
            pickerOpen = true
            pickSubtitle.launch(arrayOf("*/*"))
          },
          onPickAudio = {
            pickerOpen = true
            pickAudio.launch(arrayOf("audio/*"))
          },
          onAspectSelect = { selectAspect(it) },
          onSubtitleDelay = { setSubtitleDelay(it) },
          onAudioDelay = { setAudioDelay(it) },
          onBrightness = { setBrightnessFraction(it) },
          onVolume = { setVolumeFraction(it) },
          onSelectPlaylist = { playIndex(it) },
          onPip = { enterPip() },
          onRotate = { toggleOrientation() },
          onRetry = { retryPlayback() },
          onSurfaceReady = ::onSurfaceReady,
        )

        DisposableEffect(Unit) {
          onDispose {
            releasePlayer()
          }
        }
      }
    }

    lifecycleScope.launch {
      while (isActive) {
        delay(500)
        updateProgress()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readIntent(intent)
    currentIndex = intent.getIntExtra("playlist_index", 0)
      .coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
    if (surfaceReady) {
      startEmbyReporting(intent)
      loadCurrent()
    } else {
      hasStarted = false
    }
  }

  override fun onStop() {
    super.onStop()
    if (pickerOpen) return
    if (prefs.backgroundPlayback.get()) return
    if (!isInPictureInPictureMode && !enteringPip) {
      runCatching { mediaPlayer?.pause() }
    }
  }

  override fun onDestroy() {
    releasePlayer()
    super.onDestroy()
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    enteringPip = isInPictureInPictureMode
    controlsVisible.value = !isInPictureInPictureMode
  }

  private fun onSurfaceReady(layout: VLCVideoLayout) {
    videoLayoutRef = layout
    if (surfaceReady) return
    surfaceReady = true
    // AndroidView factory 运行在组合/布局阶段，不能在这里直接写 Compose state；
    // post 到下一帧再起播，VLC 也正好拿到已 attach 的 Surface。
    window.decorView.post {
      if (!hasStarted && !isFinishing && !isDestroyed) {
        hasStarted = true
        if (mediaPlayer != null) {
          applyAspectPreference()
          startEmbyReporting(intent)
          loadCurrent()
        }
      }
    }
  }

  /**
   * 初始化异常往往套了 2～3 层（`ExceptionInInitializerError → UnsatisfiedLinkError`），
   * 只显示最外层 message 常常是 null；这里把整条 cause 链拼出来，
   * 用户点「复制错误」就能直接发回完整现场。
   */
  private fun describeThrowable(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 4) {
      if (isNotEmpty()) append(" ← ")
      append(current.javaClass.name.substringAfterLast('.'))
      current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
      current = current.cause
      depth++
    }
  }

  /**
   * 外挂字幕 / 音轨：先把 SAF 的 `content://` 复制进 cache，再交给 libVLC。
   *
   * VLC 的 `addSlave` 最终走 native 文件访问，对 `content://` 支持不稳定；
   * 复制成本地文件后兼容性明显更好，代价只是几百 KB 缓存。
   */
  private fun attachExternalSubtitle(uri: Uri) {
    attachExternalSlave(
      uri = uri,
      type = IMedia.Slave.Type.Subtitle,
      label = "字幕",
      fallbackExt = "srt",
      allowedExts = SUBTITLE_EXTENSIONS,
    ) { file -> externalSubtitleFile = file }
  }

  private fun attachExternalAudio(uri: Uri) {
    attachExternalSlave(
      uri = uri,
      type = IMedia.Slave.Type.Audio,
      label = "音轨",
      fallbackExt = "mka",
      allowedExts = AUDIO_EXTENSIONS,
    ) { file -> externalAudioFile = file }
  }

  private fun attachExternalSlave(
    uri: Uri,
    type: Int,
    label: String,
    fallbackExt: String,
    allowedExts: Set<String>,
    remember: (File) -> Unit,
  ) {
    lifecycleScope.launch {
      val file = withContext(Dispatchers.IO) {
        runCatching { copySlaveToCache(uri, fallbackExt, allowedExts) }.getOrNull()
      }
      if (file == null) {
        Toast.makeText(this@VlcPlayerActivity, "读取${label}文件失败", Toast.LENGTH_SHORT).show()
        return@launch
      }
      val added = runCatching {
        mediaPlayer?.addSlave(type, Uri.fromFile(file), true) == true
      }.getOrDefault(false)
      if (added) remember(file)
      Toast.makeText(
        this@VlcPlayerActivity,
        if (added) "已加载${label}：${file.name}" else "VLC 不接受这个${label}文件",
        Toast.LENGTH_SHORT,
      ).show()
      refreshTracks()
    }
  }

  private fun copySlaveToCache(uri: Uri, fallbackExt: String, allowedExts: Set<String>): File? {
    val displayName = runCatching {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    val ext = displayName
      ?.substringAfterLast('.', "")
      ?.lowercase()
      ?.takeIf { it in allowedExts }
      ?: fallbackExt
    val dir = File(cacheDir, "vlc_slaves").apply { mkdirs() }
    // 顺手清掉半天前的临时文件，避免 cache 只增不减。
    runCatching {
      val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
      dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    val target = File(dir, "external_${System.currentTimeMillis()}.$ext")
    contentResolver.openInputStream(uri)?.use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    return target.takeIf { it.length() > 0 }
  }

  private fun readIntent(intent: Intent) {
    val uris = if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist")
    }.orEmpty()
    playlist = uris.ifEmpty { listOfNotNull(intent.data) }
    playlistTitles = intent.getStringArrayListExtra("playlist_titles").orEmpty()
    currentIndex = intent.getIntExtra("playlist_index", 0).coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
    embyItemIds = intent.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS).orEmpty()
    pendingStartMs = intent.getIntExtra("position", 0).toLong().takeIf { it > 0 } ?: 0L
    if (intent.getBooleanExtra("play_from_start", false)) pendingStartMs = 0L
    uiState.value = uiState.value.copy(
      title = intent.getStringExtra("title")
        ?: playlistTitles.getOrNull(currentIndex)
        ?: intent.getStringExtra("filename")
        ?: "VLC",
      engine = EngineKind.VLC,
      playlistIndex = currentIndex,
      playlistCount = playlist.size,
      playlistTitles = playlistTitles,
    )
  }

  private fun createPlayer() {
    // LibVLC.loadLibraries() 在 native 加载失败时会直接 System.exit(1)，用户只看到 App 闪退。
    // 先按同样的顺序手动加载：失败就抛给上层显示错误卡片；成功时后续 loadLibrary 是幂等的。
    // 这一步同时能把 dlopen 的真实原因（缺符号 / ABI 不匹配）带进错误信息。
    System.loadLibrary("c++_shared")
    System.loadLibrary("vlc")
    System.loadLibrary("vlcjni")

    val options = buildList {
      add("--no-video-title-show")
      add("--audio-time-stretch")
      // 缓存：网络流比本地文件更重要
      add("--network-caching=${prefs.networkCachingMs.get().coerceIn(0, 30_000)}")
      add("--file-caching=${prefs.fileCachingMs.get().coerceIn(0, 30_000)}")
      if (prefs.deinterlace.get()) add("--deinterlace=1")
      if (prefs.dropLateFrames.get()) add("--drop-late-frames") else add("--no-drop-late-frames")
      if (prefs.skipFrames.get()) add("--skip-frames") else add("--no-skip-frames")
      prefs.extraOptions.get()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { option -> add(if (option.startsWith("-")) option else "--$option") }
    }
    // 选项列表里任何一项在个别 VLC 版本上不被认识时，宁可退回默认实例，
    // 也不要让整个内核初始化直接失败（用户看到的就是「VLC 内核初始化失败」）。
    val lib = runCatching { LibVLC(this, options) }
      .getOrElse { error ->
        android.util.Log.w("VlcPlayer", "LibVLC(options) 初始化失败，回退默认:", error)
        LibVLC(this)
      }
    val player = try {
      MediaPlayer(lib)
    } catch (error: Throwable) {
      runCatching { lib.release() }
      throw error
    }
    player.setEventListener(object : MediaPlayer.EventListener {
      override fun onEvent(event: MediaPlayer.Event) {
        runOnUiThread { handleEvent(event) }
      }
    })
    libVlc = lib
    mediaPlayer = player
    // retry 时 AndroidView 已经建好，新 player 必须重新挂到同一个视频层上。
    videoLayoutRef?.let { layout ->
      runCatching { player.attachViews(layout, null, false, false) }
    }
  }

  /** 初始化失败后「重试」：重建内核，而不是对空 player 再调一次 loadCurrent。 */
  private fun retryPlayback() {
    if (mediaPlayer != null) {
      loadCurrent()
      return
    }
    runCatching { createPlayer() }.onFailure { error ->
      uiState.value = uiState.value.copy(
        error = "VLC 内核初始化失败：${describeThrowable(error)}",
        buffering = false,
      )
      return
    }
    if (!surfaceReady) return
    startEmbyReporting(intent)
    loadCurrent()
  }

  private fun loadCurrent() {
    val player = mediaPlayer ?: return
    val lib = libVlc ?: return
    val uri = playlist.getOrNull(currentIndex)
    if (uri == null) {
      uiState.value = uiState.value.copy(
        error = "没有可播放的地址",
        buffering = false,
        playing = false,
      )
      return
    }
    uiState.value = uiState.value.copy(
      error = null,
      ended = false,
      buffering = true,
      positionMs = 0,
      durationMs = 0,
      title = playlistTitles.getOrNull(currentIndex) ?: uiState.value.title,
      playlistIndex = currentIndex,
      playlistCount = playlist.size,
      playlistTitles = playlistTitles,
    )

    val media = Media(lib, uri).apply {
      setHWDecoderEnabled(prefs.hardwareDecode.get(), false)
      val audioLang = prefs.audioLanguage.get().trim()
      if (audioLang.isNotEmpty()) addOption(":audio-language=$audioLang")
      val subLang = prefs.subtitleLanguage.get().trim()
      if (subLang.isNotEmpty()) addOption(":sub-language=$subLang")
      val subtitleScale = prefs.subtitleScalePercent.get().coerceIn(50, 200) / 100f
      if (subtitleScale != 1f) addOption(":sub-text-scale=$subtitleScale")
      val subtitleMargin = prefs.subtitleMargin.get().coerceIn(0, 160)
      if (subtitleMargin > 0) addOption(":sub-margin=$subtitleMargin")
      intent?.getStringArrayExtra("headers")?.let { headers ->
        if (headers.size >= 2 && headers[0].equals("User-Agent", ignoreCase = true)) {
          addOption(":http-user-agent=${headers[1]}")
        }
        if (headers.size >= 4 && headers[2].equals("Referer", ignoreCase = true)) {
          addOption(":http-referrer=${headers[3]}")
        }
      }
    }
    player.setMedia(media)
    media.release()
    player.play()
    // 外挂字幕 / 音轨是「这一场播放」的选择：切集后自动重新挂上同一个文件。
    externalSubtitleFile?.let { file ->
      runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true) }
    }
    externalAudioFile?.let { file ->
      runCatching { player.addSlave(IMedia.Slave.Type.Audio, Uri.fromFile(file), true) }
    }
  }

  private fun handleEvent(event: MediaPlayer.Event) {
    when (event.type) {
      MediaPlayer.Event.Playing -> {
        uiState.value = uiState.value.copy(playing = true, buffering = false, error = null)
        if (pendingStartMs > 0) {
          runCatching { mediaPlayer?.setTime(pendingStartMs) }
          pendingStartMs = 0L
        }
        refreshTracks()
        applyDelays()
      }

      MediaPlayer.Event.Paused,
      MediaPlayer.Event.Stopped,
      -> uiState.value = uiState.value.copy(playing = false)

      MediaPlayer.Event.EndReached -> {
        uiState.value = uiState.value.copy(playing = false)
        if (currentIndex + 1 < playlist.size) {
          currentIndex++
          loadCurrent()
        } else {
          uiState.value = uiState.value.copy(ended = true)
        }
      }

      MediaPlayer.Event.EncounteredError -> {
        uiState.value = uiState.value.copy(
          playing = false,
          buffering = false,
          error = "VLC 无法播放这个文件；可返回后换 mpv / GSY 内核重试",
        )
      }

      MediaPlayer.Event.Buffering -> {
        uiState.value = uiState.value.copy(buffering = event.buffering < 100f)
      }

      MediaPlayer.Event.TimeChanged -> {
        uiState.value = uiState.value.copy(positionMs = event.timeChanged.coerceAtLeast(0))
      }

      MediaPlayer.Event.LengthChanged -> {
        uiState.value = uiState.value.copy(durationMs = event.lengthChanged.coerceAtLeast(0))
      }

      MediaPlayer.Event.ESAdded,
      MediaPlayer.Event.ESDeleted,
      MediaPlayer.Event.ESSelected,
      -> refreshTracks()
    }
  }

  private fun updateProgress() {
    val player = mediaPlayer ?: return
    if (player.isReleased) return
    val pos = runCatching { player.time }.getOrDefault(0L)
    val len = runCatching { player.length }.getOrDefault(0L)
    val playing = runCatching { player.isPlaying }.getOrDefault(false)
    uiState.value = uiState.value.copy(
      positionMs = pos.coerceAtLeast(0),
      durationMs = len.coerceAtLeast(0),
      playing = playing,
    )
  }

  private fun refreshTracks() {
    val player = mediaPlayer ?: return
    val audio = runCatching {
      player.audioTracks.orEmpty()
        .filter { it.id >= 0 }
        .map { VlcTrack(it.id, it.name ?: "音轨 ${it.id}") }
    }.getOrDefault(emptyList())
    val subs = runCatching {
      player.spuTracks.orEmpty()
        .filter { it.id >= 0 }
        .map { VlcTrack(it.id, it.name ?: "字幕 ${it.id}") }
    }.getOrDefault(emptyList())
    uiState.value = uiState.value.copy(
      audioTracks = audio,
      subtitleTracks = subs,
      audioTrackId = runCatching { player.audioTrack }.getOrDefault(-1),
      subtitleTrackId = runCatching { player.spuTrack }.getOrDefault(-1),
    )
  }

  private fun togglePlay() {
    val player = mediaPlayer ?: return
    if (uiState.value.ended) {
      uiState.value = uiState.value.copy(ended = false)
      loadCurrent()
      return
    }
    if (player.isPlaying) player.pause() else player.play()
  }

  private fun seekTo(ms: Long) {
    mediaPlayer?.let { runCatching { it.setTime(ms.coerceAtLeast(0)) } }
    uiState.value = uiState.value.copy(positionMs = ms.coerceAtLeast(0))
  }

  /** 快进 / 快退：以**当前播放位置**为基准，越界自动夹到 0。 */
  private fun seekBy(seconds: Int) {
    val player = mediaPlayer ?: return
    val target = (runCatching { player.time }.getOrDefault(0L) + seconds * 1000L)
      .coerceAtLeast(0L)
    seekTo(target)
  }

  private fun setSubtitleDelay(ms: Int) {
    val value = ms.coerceIn(-5000, 5000)
    prefs.subtitleDelayMs.set(value)
    mediaPlayer?.let { runCatching { it.setSpuDelay(value * 1000L) } }
    uiState.value = uiState.value.copy(subtitleDelayMs = value)
  }

  private fun setAudioDelay(ms: Int) {
    val value = ms.coerceIn(-5000, 5000)
    prefs.audioDelayMs.set(value)
    mediaPlayer?.let { runCatching { it.setAudioDelay(value * 1000L) } }
    uiState.value = uiState.value.copy(audioDelayMs = value)
  }

  private fun currentVolumeFraction(): Float {
    val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
      .getOrDefault(15)
      .coerceAtLeast(1)
    val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
      .getOrDefault(0)
    return (current.toFloat() / max).coerceIn(0f, 1f)
  }

  private fun setVolumeFraction(fraction: Float) {
    val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
      .getOrDefault(15)
      .coerceAtLeast(1)
    val value = fraction.coerceIn(0f, 1f)
    runCatching {
      audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (value * max).roundToInt().coerceIn(0, max),
        0,
      )
    }
    uiState.value = uiState.value.copy(volumeFraction = value)
  }

  private fun currentBrightnessFraction(): Float {
    val windowValue = window.attributes.screenBrightness
    if (windowValue in 0f..1f) return windowValue
    // 窗口还没设过亮度时读系统亮度作起点（读这个设置不需要权限）。
    return runCatching {
      Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
  }

  private fun setBrightnessFraction(fraction: Float) {
    val value = fraction.coerceIn(0.01f, 1f)
    runCatching {
      val attrs = window.attributes
      attrs.screenBrightness = value
      window.attributes = attrs
    }
    uiState.value = uiState.value.copy(brightness = value)
  }

  private fun playIndex(index: Int) {
    if (index !in playlist.indices) return
    currentIndex = index
    loadCurrent()
  }

  /** 横竖屏切换：与 mpv 播放页的旋转键同义，解决系统自动旋转关掉时无法横屏。 */
  private fun toggleOrientation() {
    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    requestedOrientation = if (isLandscape) {
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
  }

  private fun setSpeed(speed: Float) {
    runCatching { mediaPlayer?.rate = speed }
    uiState.value = uiState.value.copy(speed = speed)
  }

  private fun selectAudioTrack(id: Int) {
    runCatching { mediaPlayer?.setAudioTrack(id) }
    uiState.value = uiState.value.copy(audioTrackId = id)
  }

  private fun selectSubtitleTrack(id: Int) {
    runCatching { mediaPlayer?.setSpuTrack(id) }
    uiState.value = uiState.value.copy(subtitleTrackId = id)
  }

  /** 用户在比例面板里选了一项（name = `MediaPlayer.ScaleType` 的枚举名）。 */
  private fun selectAspect(name: String) {
    val scale = runCatching { MediaPlayer.ScaleType.valueOf(name) }.getOrNull() ?: return
    runCatching { mediaPlayer?.videoScale = scale }
    uiState.value = uiState.value.copy(aspect = scale.name.removePrefix("SURFACE_"))
  }

  private fun applyAspectPreference() {
    val scale = when (prefs.defaultAspect.get().lowercase()) {
      "16:9" -> MediaPlayer.ScaleType.SURFACE_16_9
      "4:3" -> MediaPlayer.ScaleType.SURFACE_4_3
      "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
      else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }
    runCatching { mediaPlayer?.videoScale = scale }
    uiState.value = uiState.value.copy(aspect = scale.name.removePrefix("SURFACE_"))
  }

  private fun applyDelays() {
    val player = mediaPlayer ?: return
    val audio = prefs.audioDelayMs.get().coerceIn(-5000, 5000)
    val subtitle = prefs.subtitleDelayMs.get().coerceIn(-5000, 5000)
    runCatching { player.setAudioDelay(audio * 1000L) }
    runCatching { player.setSpuDelay(subtitle * 1000L) }
    uiState.value = uiState.value.copy(audioDelayMs = audio, subtitleDelayMs = subtitle)
  }

  private fun enterPip() {
    if (Build.VERSION.SDK_INT < 26) return
    val params = PictureInPictureParams.Builder()
      .setAspectRatio(Rational(16, 9))
      .build()
    enteringPip = true
    val ok = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
    if (!ok) enteringPip = false
  }

  private fun startEmbyReporting(source: Intent) {
    runCatching {
      val reporter = EmbyPlaybackReporter
      reporterObserver?.let {
        lifecycle.removeObserver(it)
        reporter.stop()
      }
      reporterObserver = null
      reporter.idProvider = { embyItemIds.getOrNull(currentIndex) }
      reporter.positionProvider = {
        val p = mediaPlayer
        if (p == null || p.isReleased) null else EmbyTicks.msToTicks(p.time)
      }
      reporter.pausedProvider = {
        runCatching { mediaPlayer?.isPlaying != true }.getOrNull()
      }
      val observer = EmbyPlaybackReporter.EmbyPlaybackLifecycleObserver(source)
      reporterObserver = observer
      lifecycle.addObserver(observer)
    }
  }

  private fun releasePlayer() {
    reporterObserver?.let {
      runCatching { lifecycle.removeObserver(it) }
    }
    reporterObserver = null
    // 通知观察者结束（如果 Activity 还没走 onStop 的 finish 分支）
    runCatching { EmbyPlaybackReporter.stop() }
    runCatching { mediaPlayer?.detachViews() }
    runCatching { mediaPlayer?.release() }
    mediaPlayer = null
    runCatching { libVlc?.release() }
    libVlc = null
    videoLayoutRef = null
    surfaceReady = false
    hasStarted = false
    enteringPip = false
  }
}

private data class VlcTrack(
  val id: Int,
  val name: String,
)

private data class VlcUiState(
  val title: String = "VLC",
  val engine: EngineKind = EngineKind.VLC,
  val playing: Boolean = false,
  val buffering: Boolean = false,
  val ended: Boolean = false,
  val error: String? = null,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val speed: Float = 1f,
  val audioTracks: List<VlcTrack> = emptyList(),
  val subtitleTracks: List<VlcTrack> = emptyList(),
  val audioTrackId: Int = -1,
  val subtitleTrackId: Int = -1,
  val aspect: String? = null,
  val subtitleDelayMs: Int = 0,
  val audioDelayMs: Int = 0,
  /** 手势调整亮度 / 音量的起点值（0..1），由 Activity 与系统状态同步。 */
  val brightness: Float = 0.5f,
  val volumeFraction: Float = 0.5f,
  /** 播放列表（单条播放时 count = 1）。 */
  val playlistIndex: Int = 0,
  val playlistCount: Int = 0,
  val playlistTitles: List<String> = emptyList(),
)

private enum class VlcSheet { None, Speed, Audio, Subtitle, Aspect, SubtitleDelay, AudioDelay, Playlist }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VlcPlayerScreen(
  player: MediaPlayer?,
  ui: VlcUiState,
  controlsVisible: Boolean,
  onToggleControls: () -> Unit,
  onBack: () -> Unit,
  onTogglePlay: () -> Unit,
  onSeek: (Long) -> Unit,
  onSeekBy: (Int) -> Unit,
  onSpeed: (Float) -> Unit,
  onAudioTrack: (Int) -> Unit,
  onSubtitleTrack: (Int) -> Unit,
  onPickSubtitle: () -> Unit,
  onPickAudio: () -> Unit,
  onAspectSelect: (String) -> Unit,
  onSubtitleDelay: (Int) -> Unit,
  onAudioDelay: (Int) -> Unit,
  onBrightness: (Float) -> Unit,
  onVolume: (Float) -> Unit,
  onSelectPlaylist: (Int) -> Unit,
  onPip: () -> Unit,
  onRotate: () -> Unit,
  onRetry: () -> Unit,
  onSurfaceReady: (VLCVideoLayout) -> Unit,
) {
  var sheet by remember { mutableStateOf(VlcSheet.None) }
  var sliderPosition by remember { mutableLongStateOf(ui.positionMs) }
  var dragging by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val playerPrefs = koinInject<PlayerPreferences>()
  val appearancePrefs = koinInject<AppearancePreferences>()
  val seekbarStyle by appearancePrefs.seekbarStyle.collectAsState()
  val invertDuration by playerPrefs.invertDuration.collectAsState()
  val seekBackSeconds by playerPrefs.seekBackwardSeconds.collectAsState()
  val seekForwardSeconds by playerPrefs.seekForwardSeconds.collectAsState()
  val backStep = seekBackSeconds.coerceIn(1, 600)
  val forwardStep = seekForwardSeconds.coerceIn(1, 600)
  val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
  val haptics = LocalHapticFeedback.current
  val horizontalSwipeToSeek by playerPrefs.horizontalSwipeToSeek.collectAsState()
  val brightnessGesture by playerPrefs.brightnessGesture.collectAsState()
  val volumeGesture by playerPrefs.volumeGesture.collectAsState()
  val swapVolumeBrightness by playerPrefs.swapVolumeAndBrightness.collectAsState()
  val swipeSensitivity by playerPrefs.horizontalSwipeSensitivity.collectAsState()
  val latestUi by rememberUpdatedState(ui)
  var gestureHint by remember { mutableStateOf<VlcGestureHint?>(null) }

  LaunchedEffect(ui.positionMs, dragging) {
    if (!dragging) sliderPosition = ui.positionMs
  }
  // 自动隐藏只在「真的在播、没有面板、没有错误」时进行；
  // 淡出而不是默认的 expand/shrink —— 默认动画会让整块控件往左上角收缩。
  LaunchedEffect(controlsVisible, ui.playing, dragging, sheet, ui.error) {
    if (controlsVisible && ui.playing && !dragging && sheet == VlcSheet.None && ui.error == null) {
      delay(3_500)
      onToggleControls()
    }
  }
  BackHandler {
    if (sheet != VlcSheet.None) sheet = VlcSheet.None else onBack()
  }

  Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    // VLC 只负责画视频；控件全部由 Compose 画在上面。
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        VLCVideoLayout(ctx).also { layout ->
          runCatching { player?.attachViews(layout, null, false, false) }
          layout.keepScreenOn = true
          onSurfaceReady(layout)
        }
      },
      onRelease = {
        runCatching { player?.detachViews() }
      },
    )

    // 手势层：横滑进度、左侧亮度 / 右侧音量、单击显示隐藏控件。
    // 它铺在视频上、控件层下面 —— 点按钮时事件先被按钮吃掉，不会误触手势。
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(
          horizontalSwipeToSeek,
          brightnessGesture,
          volumeGesture,
          swapVolumeBrightness,
          swipeSensitivity,
        ) {
          val slop = 24.dp.toPx()
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            val startUi = latestUi
            val startVolume = runCatching {
              val am = context.getSystemService(AudioManager::class.java)
              val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
              am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            }.getOrDefault(startUi.volumeFraction).coerceIn(0f, 1f)
            val startBrightness = (context as? android.app.Activity)
              ?.window?.attributes?.screenBrightness
              ?.takeIf { it in 0f..1f }
              ?: startUi.brightness
            var mode = 0
            var seekTargetMs = startUi.positionMs
            var hint: VlcGestureHint? = null

            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (change.isConsumed) break

              val dx = change.position.x - startX
              val dy = change.position.y - startY
              if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                mode = if (abs(dx) >= abs(dy)) 1 else 2
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              }

              if (mode == 1 && horizontalSwipeToSeek) {
                val duration = latestUi.durationMs
                val deltaMs = (dx * swipeSensitivity * 1000f).toLong()
                val upper = if (duration > 0) duration else Long.MAX_VALUE
                seekTargetMs = (startUi.positionMs + deltaMs).coerceIn(0L, upper)
                hint = VlcGestureHint(
                  icon = if (deltaMs >= 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                  text = formatGestureTime(seekTargetMs),
                  secondary = signedGestureTime(seekTargetMs - startUi.positionMs),
                )
                change.consume()
              } else if (mode == 2) {
                val useVolume = if (swapVolumeBrightness) {
                  startX < size.width / 2f
                } else {
                  startX >= size.width / 2f
                }
                val delta = -dy / size.height.toFloat()
                if (useVolume && volumeGesture) {
                  val value = (startVolume + delta).coerceIn(0f, 1f)
                  onVolume(value)
                  hint = VlcGestureHint(
                    icon = Icons.Filled.VolumeUp,
                    text = "${(value * 100).roundToInt()}%",
                  )
                  change.consume()
                } else if (!useVolume && brightnessGesture) {
                  val value = (startBrightness + delta).coerceIn(0.01f, 1f)
                  onBrightness(value)
                  hint = VlcGestureHint(
                    icon = Icons.Filled.BrightnessHigh,
                    text = "${(value * 100).roundToInt()}%",
                  )
                  change.consume()
                }
              }

              gestureHint = hint
              if (!change.pressed) {
                if (mode == 0) {
                  onToggleControls()
                } else if (mode == 1 && horizontalSwipeToSeek) {
                  onSeek(seekTargetMs)
                }
                gestureHint = null
                break
              }
            }
          }
        },
    )

    if (ui.buffering && !ui.ended && ui.error == null) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center).size(44.dp),
        color = Color.White,
      )
    }

    AnimatedVisibility(
      visible = controlsVisible,
      enter = fadeIn(tween(120)),
      exit = fadeOut(tween(220)),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：返回 + 标题胶囊 + 旋转 / 画中画（与 mpv 播放页同一套玻璃控件） ──
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ControlsButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack,
            color = controlColor,
            modifier = Modifier.size(45.dp),
          )
          if (!isPortrait) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(50),
              color = PlayerControlGlassFill,
              contentColor = controlColor,
              tonalElevation = 0.dp,
              shadowElevation = 0.dp,
              border = BorderStroke(1.dp, PlayerControlGlassBorder),
              modifier = Modifier.weight(1f, fill = false),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  "VLC",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  ui.title,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodyMedium,
                  fontFamily = FontFamily.Monospace,
                  color = controlColor,
                )
              }
            }
          }
          Spacer(modifier = Modifier.weight(1f))
          ControlsButton(
            icon = Icons.Filled.ScreenRotation,
            onClick = onRotate,
            title = "横竖屏",
            color = controlColor,
            modifier = Modifier.size(45.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          ControlsButton(
            icon = Icons.Filled.PictureInPictureAlt,
            onClick = onPip,
            title = "画中画",
            color = controlColor,
            modifier = Modifier.size(45.dp),
          )
        }

        // ── 正中：快退 / 播放 / 快进（与 mpv 播放页一致） ──
        Row(
          modifier = Modifier.align(Alignment.Center),
          horizontalArrangement = Arrangement.spacedBy(22.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          VlcCenterButton(
            icon = Icons.Filled.FastRewind,
            label = "-${backStep}s",
            onClick = { onSeekBy(-backStep) },
          )
          Surface(
            modifier = Modifier
              .size(68.dp)
              .clip(CircleShape)
              .clickable(onClick = onTogglePlay),
            shape = CircleShape,
            color = PlayerControlGlassFill,
            contentColor = controlColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, PlayerControlGlassBorder),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (ui.playing) "暂停" else "播放",
                tint = controlColor,
                modifier = Modifier.size(34.dp),
              )
            }
          }
          VlcCenterButton(
            icon = Icons.Filled.FastForward,
            label = "+${forwardStep}s",
            onClick = { onSeekBy(forwardStep) },
          )
        }

        // ── 底栏：进度条 + 快捷按钮 ──
        Column(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
              Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
              ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
          if (isPortrait) {
            val indexLabel = if (ui.playlistCount > 1) {
              "${ui.playlistIndex + 1}/${ui.playlistCount} · "
            } else {
              ""
            }
            Text(
              text = indexLabel + ui.title,
              modifier = Modifier
                .fillMaxWidth()
                .basicMarquee()
                .padding(horizontal = 6.dp, vertical = 2.dp),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
              style = MaterialTheme.typography.bodyMedium.copy(
                shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 8f),
              ),
              fontFamily = FontFamily.Monospace,
              color = controlColor,
            )
          }
          SeekbarWithTimers(
            position = sliderPosition / 1000f,
            duration = ui.durationMs / 1000f,
            onValueChange = {
              dragging = true
              sliderPosition = (it * 1000L).toLong()
            },
            onValueChangeFinished = {
              dragging = false
              onSeek(sliderPosition)
            },
            timersInverted = Pair(false, invertDuration),
            positionTimerOnClick = {},
            durationTimerOnCLick = {
              playerPrefs.invertDuration.set(!invertDuration)
            },
            chapters = persistentListOf(),
            paused = !ui.playing,
            seekbarStyle = seekbarStyle,
            modifier = Modifier.fillMaxWidth(),
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ControlsButton(
              icon = Icons.Filled.Speed,
              onClick = { sheet = VlcSheet.Speed },
              title = "倍速",
              color = controlColor,
              modifier = Modifier.size(40.dp),
            )
            ControlsButton(
              icon = Icons.Filled.Audiotrack,
              onClick = { sheet = VlcSheet.Audio },
              title = "音轨",
              color = controlColor,
              modifier = Modifier.size(40.dp),
            )
            ControlsButton(
              icon = Icons.Filled.Subtitles,
              onClick = { sheet = VlcSheet.Subtitle },
              title = "字幕",
              color = controlColor,
              modifier = Modifier.size(40.dp),
            )
            ControlsButton(
              icon = Icons.Filled.AspectRatio,
              onClick = { sheet = VlcSheet.Aspect },
              title = "画面比例",
              color = controlColor,
              modifier = Modifier.size(40.dp),
            )
            if (ui.playlistCount > 1) {
              ControlsButton(
                icon = Icons.Filled.PlaylistPlay,
                onClick = { sheet = VlcSheet.Playlist },
                title = "播放列表",
                color = controlColor,
                modifier = Modifier.size(40.dp),
              )
            }
          }
        }
      }
    }

    // 手势 HUD：横滑进度 / 亮度 / 音量
    gestureHint?.let { hint ->
      Surface(
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PlayerControlGlassBorder),
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            hint.icon,
            contentDescription = null,
            tint = controlColor,
            modifier = Modifier.size(30.dp),
          )
          Column {
            Text(
              hint.text,
              color = controlColor,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            hint.secondary?.let {
              Text(
                it,
                color = controlColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    }

    // 错误卡片不跟控件一起淡出：初始化失败时用户必须一直看得到原因。
    ui.error?.let { error ->
      Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PlayerControlGlassBorder),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .windowInsetsPadding(WindowInsets.navigationBars)
          .padding(horizontal = 16.dp, vertical = 96.dp)
          .fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(error, color = controlColor, style = MaterialTheme.typography.bodySmall)
          Row {
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(
              onClick = {
                clipboard.setText(AnnotatedString(vlcErrorReport(error)))
                Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
              },
            ) { Text("复制错误") }
            TextButton(onClick = onBack) { Text("返回") }
          }
        }
      }
    }
  }

  when (sheet) {
    VlcSheet.None -> Unit

    VlcSheet.Speed -> VlcSpeedSheet(
      speed = ui.speed,
      onSpeed = onSpeed,
      onDismiss = { sheet = VlcSheet.None },
    )

    VlcSheet.Audio -> VlcAudioSheet(
      tracks = ui.audioTracks,
      selectedId = ui.audioTrackId,
      audioDelayMs = ui.audioDelayMs,
      onSelect = onAudioTrack,
      onOpenAudioDelay = { sheet = VlcSheet.AudioDelay },
      onPickAudio = {
        sheet = VlcSheet.None
        onPickAudio()
      },
      onDismiss = { sheet = VlcSheet.None },
    )

    VlcSheet.Subtitle -> {
      val subtitleNodes = remember(ui.subtitleTracks, ui.subtitleTrackId) {
        ui.subtitleTracks.map {
          TrackNode(
            id = it.id,
            type = "sub",
            title = it.name,
            selected = it.id == ui.subtitleTrackId,
          )
        }.toImmutableList()
      }
      SubtitlesSheet(
        tracks = subtitleNodes,
        onToggleSubtitle = { id ->
          onSubtitleTrack(if (id == ui.subtitleTrackId) SUBTITLE_TRACK_OFF else id)
        },
        isSubtitleSelected = { it == ui.subtitleTrackId },
        onAddSubtitle = {
          sheet = VlcSheet.None
          onPickSubtitle()
        },
        onOpenSubtitleSettings = { sheet = VlcSheet.SubtitleDelay },
        onOpenSubtitleDelay = { sheet = VlcSheet.SubtitleDelay },
        onRemoveSubtitle = {},
        onDismissRequest = { sheet = VlcSheet.None },
        onDisableSubtitles = { onSubtitleTrack(SUBTITLE_TRACK_OFF) },
      )
    }

    VlcSheet.Aspect -> VlcAspectSheet(
      current = ui.aspect,
      onSelect = onAspectSelect,
      onDismiss = { sheet = VlcSheet.None },
    )

    VlcSheet.SubtitleDelay -> VlcDelaySheet(
      title = "字幕延迟",
      hint = "正数延后字幕，负数提前字幕。字号 / 边距在「设置 → 播放内核设置 → VLC」里调。",
      initialDelayMs = ui.subtitleDelayMs,
      onApply = onSubtitleDelay,
      onDismiss = { sheet = VlcSheet.None },
    )

    VlcSheet.AudioDelay -> VlcDelaySheet(
      title = "音频延迟",
      hint = "正数延后声音，负数提前声音；只影响本次 VLC 播放的音频同步。",
      initialDelayMs = ui.audioDelayMs,
      onApply = onAudioDelay,
      onDismiss = { sheet = VlcSheet.None },
    )

    VlcSheet.Playlist -> VlcPlaylistSheet(
      titles = ui.playlistTitles,
      count = ui.playlistCount,
      currentIndex = ui.playlistIndex,
      onSelect = {
        onSelectPlaylist(it)
        sheet = VlcSheet.None
      },
      onDismiss = { sheet = VlcSheet.None },
    )
  }
}

@Composable
private fun VlcPlaylistSheet(
  titles: List<String>,
  count: Int,
  currentIndex: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  PlayerSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        "播放列表",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
      )
      LazyColumn {
        items(count) { index ->
          AudioTrackRow(
            title = "${index + 1}. ${titles.getOrNull(index) ?: "第 ${index + 1} 项"}",
            isSelected = index == currentIndex,
            onClick = { onSelect(index) },
          )
        }
      }
    }
  }
}

private data class VlcGestureHint(
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val text: String,
  val secondary: String? = null,
)

private fun formatGestureTime(ms: Long): String {
  val total = (ms.coerceAtLeast(0)) / 1000
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun signedGestureTime(ms: Long): String =
  (if (ms >= 0) "+" else "-") + formatGestureTime(abs(ms))

@Composable
private fun VlcCenterButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .size(52.dp)
      .clip(CircleShape)
      .clickable(onClick = onClick),
    shape = CircleShape,
    color = PlayerControlGlassFill,
    contentColor = controlColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(1.dp, PlayerControlGlassBorder),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(icon, contentDescription = label, tint = controlColor, modifier = Modifier.size(22.dp))
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = controlColor.copy(alpha = 0.75f),
      )
    }
  }
}

@Composable
private fun VlcSpeedSheet(
  speed: Float,
  onSpeed: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  PlayerSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("播放速度", style = MaterialTheme.typography.headlineSmall)
      Text(
        "%.2fx".format(speed),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
      )
      Slider(
        value = speed.coerceIn(0.25f, 3f),
        onValueChange = { onSpeed((it * 20).roundToInt() / 20f) },
        valueRange = 0.25f..3f,
      )
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { preset ->
          FilterChip(
            selected = abs(speed - preset) < 0.01f,
            onClick = { onSpeed(preset) },
            label = {
              Text(if (preset == preset.toInt().toFloat()) "${preset.toInt()}x" else "${preset}x")
            },
          )
        }
      }
      Text(
        "0.25x – 3.00x；步进 0.05x。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun VlcAudioSheet(
  tracks: List<VlcTrack>,
  selectedId: Int,
  audioDelayMs: Int,
  onSelect: (Int) -> Unit,
  onOpenAudioDelay: () -> Unit,
  onPickAudio: () -> Unit,
  onDismiss: () -> Unit,
) {
  PlayerSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth()) {
      AddTrackRow(title = "添加外部音轨", onClick = onPickAudio)
      AudioTrackRow(
        title = "音频延迟：%+d ms".format(audioDelayMs),
        isSelected = false,
        onClick = onOpenAudioDelay,
      )
      LazyColumn {
        items(tracks, key = { it.id }) { track ->
          AudioTrackRow(
            title = track.name,
            isSelected = track.id == selectedId,
            onClick = {
              onSelect(track.id)
              onDismiss()
            },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VlcAspectSheet(
  current: String?,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val options = listOf(
    "SURFACE_BEST_FIT" to "自动",
    "SURFACE_FIT_SCREEN" to "适应屏幕",
    "SURFACE_FILL" to "填充",
    "SURFACE_16_9" to "16:9",
    "SURFACE_4_3" to "4:3",
    "SURFACE_16_10" to "16:10",
    "SURFACE_235_1" to "2.35:1",
    "SURFACE_239_1" to "2.39:1",
    "SURFACE_ORIGINAL" to "原始",
  )
  PlayerSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("画面比例", style = MaterialTheme.typography.headlineSmall)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        options.forEach { (name, label) ->
          FilterChip(
            selected = current == name.removePrefix("SURFACE_"),
            onClick = {
              onSelect(name)
              onDismiss()
            },
            label = { Text(label) },
          )
        }
      }
      Text(
        "「自动」= 按视频原始比例；「适应屏幕」会裁掉黑边。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun VlcDelaySheet(
  title: String,
  hint: String,
  initialDelayMs: Int,
  onApply: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var delayMs by remember(initialDelayMs) { mutableStateOf(initialDelayMs) }
  PlayerSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, style = MaterialTheme.typography.headlineSmall)
      Text(
        "%+d ms".format(delayMs),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
      )
      Slider(
        value = delayMs.toFloat(),
        onValueChange = { delayMs = it.toInt() },
        onValueChangeFinished = { onApply(delayMs) },
        valueRange = -5000f..5000f,
        steps = 19,
      )
      Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private const val SUBTITLE_TRACK_OFF = -1

/** 复制给开发者的错误报告：带上版本 / 设备 / ABI，光有异常文本没法定位。 */
private fun vlcErrorReport(error: String): String = buildString {
  appendLine("CineIsle ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
  appendLine(
    "device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, " +
      Build.SUPPORTED_ABIS.joinToString("/"),
  )
  append(error)
}

/** 允许复制到 cache 再交给 VLC 的字幕扩展名；未知扩展按 srt 处理。 */
private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "txt")

/** 允许复制到 cache 再交给 VLC 的外挂音轨扩展名；未知扩展按 mka 处理。 */
private val AUDIO_EXTENSIONS = setOf("mka", "mkv", "aac", "m4a", "mp3", "flac", "ogg", "opus", "ac3", "eac3", "dts", "wav")
