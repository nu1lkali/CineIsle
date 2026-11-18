package app.marlboroadvance.mpvex.ui.player.vlc

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.VlcPreferences
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

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

  private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) attachExternalSubtitle(uri)
  }

  private var libVlc: LibVLC? = null
  private var mediaPlayer: MediaPlayer? = null
  private var videoLayoutRef: VLCVideoLayout? = null
  private var surfaceReady = false
  private var hasStarted = false
  private var enteringPip = false

  private var playlist: List<Uri> = emptyList()
  private var playlistTitles: List<String> = emptyList()
  private var currentIndex = 0
  private var embyItemIds: List<String> = emptyList()
  private var pendingStartMs = 0L
  /** 上一次选的外挂字幕：切集 / 换源后自动重新挂上。 */
  private var externalSubtitleFile: File? = null
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
          onSpeed = { setSpeed(it) },
          onAudioTrack = { selectAudioTrack(it) },
          onSubtitleTrack = { selectSubtitleTrack(it) },
          onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
          onAspect = { cycleAspect() },
          onPip = { enterPip() },
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
   * 外挂字幕：先把 SAF 的 `content://` 复制进 cache，再交给 libVLC。
   *
   * VLC 的 `addSlave` 最终走 native 文件访问，对 `content://` 支持不稳定；
   * 复制成本地文件后兼容性明显更好，代价只是几百 KB 缓存。
   */
  private fun attachExternalSubtitle(uri: Uri) {
    lifecycleScope.launch {
      val file = withContext(Dispatchers.IO) {
        runCatching { copySubtitleToCache(uri) }.getOrNull()
      }
      if (file == null) {
        Toast.makeText(this@VlcPlayerActivity, "读取字幕文件失败", Toast.LENGTH_SHORT).show()
        return@launch
      }
      val added = runCatching {
        mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true) == true
      }.getOrDefault(false)
      if (added) externalSubtitleFile = file
      Toast.makeText(
        this@VlcPlayerActivity,
        if (added) "已加载字幕：${file.name}" else "VLC 不接受这个字幕文件",
        Toast.LENGTH_SHORT,
      ).show()
      refreshTracks()
    }
  }

  private fun copySubtitleToCache(uri: Uri): File? {
    val displayName = runCatching {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    val ext = displayName
      ?.substringAfterLast('.', "")
      ?.lowercase()
      ?.takeIf { it in SUBTITLE_EXTENSIONS }
      ?: "srt"
    val dir = File(cacheDir, "vlc_subtitles").apply { mkdirs() }
    // 顺手清掉半天前的临时字幕，避免 cache 只增不减。
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
    // 外挂字幕是「这一场播放」的选择：切集后自动重新挂上同一个文件。
    externalSubtitleFile?.let { file ->
      runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true) }
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
        applyAspectPreference()
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
      player.audioTracks.orEmpty().map { VlcTrack(it.id, it.name ?: "音轨 ${it.id}") }
    }.getOrDefault(emptyList())
    val subs = runCatching {
      player.spuTracks.orEmpty().map { VlcTrack(it.id, it.name ?: "字幕 ${it.id}") }
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
    if (player.isPlaying) player.pause() else player.play()
  }

  private fun seekTo(ms: Long) {
    mediaPlayer?.let { runCatching { it.setTime(ms.coerceAtLeast(0)) } }
    uiState.value = uiState.value.copy(positionMs = ms.coerceAtLeast(0))
  }

  private fun setSpeed(speed: Float) {
    mediaPlayer?.rate = speed
    uiState.value = uiState.value.copy(speed = speed)
  }

  private fun selectAudioTrack(id: Int) {
    mediaPlayer?.setAudioTrack(id)
    uiState.value = uiState.value.copy(audioTrackId = id)
  }

  private fun selectSubtitleTrack(id: Int) {
    mediaPlayer?.setSpuTrack(id)
    uiState.value = uiState.value.copy(subtitleTrackId = id)
  }

  private fun cycleAspect() {
    val player = mediaPlayer ?: return
    val order = listOf(
      MediaPlayer.ScaleType.SURFACE_BEST_FIT,
      MediaPlayer.ScaleType.SURFACE_16_9,
      MediaPlayer.ScaleType.SURFACE_4_3,
      MediaPlayer.ScaleType.SURFACE_FILL,
    )
    val current = runCatching { player.videoScale }.getOrNull()
    val index = order.indexOf(current)
    val next = order[(if (index >= 0) index + 1 else 0) % order.size]
    runCatching { player.videoScale = next }
    uiState.value = uiState.value.copy(aspect = next.name.removePrefix("SURFACE_"))
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
    runCatching { player.setAudioDelay(prefs.audioDelayMs.get().coerceIn(-5000, 5000) * 1000L) }
    runCatching { player.setSpuDelay(prefs.subtitleDelayMs.get().coerceIn(-5000, 5000) * 1000L) }
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
)

@Composable
private fun VlcPlayerScreen(
  player: MediaPlayer?,
  ui: VlcUiState,
  controlsVisible: Boolean,
  onToggleControls: () -> Unit,
  onBack: () -> Unit,
  onTogglePlay: () -> Unit,
  onSeek: (Long) -> Unit,
  onSpeed: (Float) -> Unit,
  onAudioTrack: (Int) -> Unit,
  onSubtitleTrack: (Int) -> Unit,
  onPickSubtitle: () -> Unit,
  onAspect: () -> Unit,
  onPip: () -> Unit,
  onRetry: () -> Unit,
  onSurfaceReady: (VLCVideoLayout) -> Unit,
) {
  var showSpeed by remember { mutableStateOf(false) }
  var showAudio by remember { mutableStateOf(false) }
  var showSubtitle by remember { mutableStateOf(false) }
  var sliderPosition by remember { mutableLongStateOf(ui.positionMs) }
  var dragging by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  LaunchedEffect(ui.positionMs, dragging) {
    if (!dragging) sliderPosition = ui.positionMs
  }
  LaunchedEffect(controlsVisible, ui.playing, dragging) {
    if (controlsVisible && ui.playing && !dragging) {
      delay(3_500)
      onToggleControls()
    }
  }
  BackHandler(onBack = onBack)

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

    // 点击空白区域显示 / 隐藏控件
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) { onToggleControls() },
    )

    if (ui.buffering && !ui.ended && ui.error == null) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center).size(44.dp),
        color = Color.White,
      )
    }

    AnimatedVisibility(visible = controlsVisible) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.systemBars)
          .padding(horizontal = 12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              ui.title,
              color = Color.White,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              "VLC 内核",
              color = Color.White.copy(alpha = 0.7f),
              style = MaterialTheme.typography.labelSmall,
            )
          }
          IconButton(onClick = onPip) {
            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
          onClick = onTogglePlay,
          modifier = Modifier.align(Alignment.CenterHorizontally).size(72.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(36.dp),
            color = Color.Black.copy(alpha = 0.45f),
          ) {
            Icon(
              imageVector = if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription = if (ui.playing) "暂停" else "播放",
              tint = Color.White,
              modifier = Modifier.padding(18.dp).size(36.dp),
            )
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        ui.error?.let { error ->
          Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(error, color = Color.White, style = MaterialTheme.typography.bodySmall)
              Row {
                TextButton(onClick = onRetry) { Text("重试") }
                TextButton(
                  onClick = {
                    clipboard.setText(AnnotatedString(vlcErrorReport(error)))
                    android.widget.Toast.makeText(context, "错误信息已复制", android.widget.Toast.LENGTH_SHORT).show()
                  },
                ) { Text("复制错误") }
                TextButton(onClick = onBack) { Text("返回") }
              }
            }
          }
        }

        Slider(
          value = if (ui.durationMs > 0) {
            (sliderPosition.toFloat() / ui.durationMs.toFloat()).coerceIn(0f, 1f)
          } else {
            0f
          },
          onValueChange = {
            dragging = true
            sliderPosition = (it * ui.durationMs).toLong()
          },
          onValueChangeFinished = {
            dragging = false
            onSeek(sliderPosition)
          },
          colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
          ),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            "${formatVlcTime(sliderPosition)} / ${formatVlcTime(ui.durationMs)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
          )
          Text(
            "${(ui.speed * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelMedium,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          VlcControlButton(Icons.Filled.Speed, "倍速") { showSpeed = true }
          VlcControlButton(Icons.Filled.Audiotrack, "音轨") { showAudio = true }
          VlcControlButton(Icons.Filled.Subtitles, "字幕") { showSubtitle = true }
          VlcControlButton(Icons.Filled.FolderOpen, "外挂字幕") { onPickSubtitle() }
          VlcControlButton(Icons.Filled.AspectRatio, "比例") { onAspect() }
        }
      }
    }
  }

  if (showSpeed) {
    VlcChoiceDialog(
      title = "播放速度",
      options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
      selected = ui.speed,
      label = { "${(it * 100).toInt()}%" },
      onPick = {
        showSpeed = false
        onSpeed(it)
      },
      onDismiss = { showSpeed = false },
    )
  }

  if (showAudio) {
    VlcChoiceDialog(
      title = "音轨",
      options = ui.audioTracks,
      selected = ui.audioTracks.firstOrNull { it.id == ui.audioTrackId },
      label = { it.name },
      onPick = {
        showAudio = false
        onAudioTrack(it.id)
      },
      onDismiss = { showAudio = false },
    )
  }

  if (showSubtitle) {
    val subtitleOptions = listOf(VlcTrack(SUBTITLE_TRACK_OFF, "关闭字幕")) + ui.subtitleTracks
    VlcChoiceDialog(
      title = "字幕",
      options = subtitleOptions,
      selected = subtitleOptions.firstOrNull { it.id == ui.subtitleTrackId },
      label = { it.name },
      onPick = {
        showSubtitle = false
        onSubtitleTrack(it.id)
      },
      onDismiss = { showSubtitle = false },
    )
  }
}

@Composable
private fun VlcControlButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    IconButton(onClick = onClick) {
      Icon(icon, contentDescription = label, tint = Color.White)
    }
    Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun <T> VlcChoiceDialog(
  title: String,
  options: List<T>,
  selected: T?,
  label: (T) -> String,
  onPick: (T) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      LazyColumn {
        items(options) { option ->
          TextButton(
            onClick = { onPick(option) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              label(option) + if (option == selected) "  ✓" else "",
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("关闭") }
    },
  )
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

private fun formatVlcTime(ms: Long): String {
  val total = (ms.coerceAtLeast(0)) / 1000
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
