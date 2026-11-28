package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.player.engine.VideoCrop
import app.marlboroadvance.mpvex.ui.player.engine.RotationState
import app.marlboroadvance.mpvex.ui.player.engine.VideoRotate

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
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.domain.emby.EmbyPaths
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleLanguage
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleSourceType
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleFileNormalizer
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleMountArbiter
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleMountDecision
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleMountRequest
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleMountState
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleSource
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstoneStore
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstones
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleAutoFetch
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleCleanup
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibrary
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleTarget
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveManifest
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveStore
import app.marlboroadvance.mpvex.ui.player.subtitle.ExternalSubtitleArchive
import app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings
import app.marlboroadvance.mpvex.ui.player.subtitle.emby.EmbySubtitleController
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import app.marlboroadvance.mpvex.utils.media.SubtitleFileTypes
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
import kotlinx.coroutines.flow.filterNotNull
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
  /** 画面旋转要判断直出模式：直出（mediacodec_embed）没有滤镜链，折不了。 */
  private val decoderPreferences: DecoderPreferences by inject()
  private val json: Json by inject()
  private val subtitleTombstones = SubtitleTombstoneStore(subtitlesPreferences)

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

  /**
   * 「下一集」悬停卡片是否可见（Yamby 的 `next_up_sec`）。
   *
   * 与 [autoplayCountdown] 的区别：那个只在**最后几秒**出现且带数字倒计时、
   * 是「马上就要切了」的催促；这个提前 30 秒左右出现、不带倒计时，
   * 是「后面还有一集，要不要现在就看」的提示 —— 长片尾（预告 / 片花）时特别有用。
   */
  val nextUpVisible = MutableStateFlow(false)

  /** 「下一集」卡片里显示的标题 */
  val nextUpTitle = MutableStateFlow<String?>(null)

  /**
   * 「跳过片头 / 跳过片尾」小胶囊的文案；null = 不显示。
   *
   * 由播放页的片头片尾盯梢协程写入：自动跳过关着、而播放头又落在
   * 片头 / 片尾区间里时给出一个可点的提示（点它 = 跳到段尾）。
   * 自动跳过开着时这里恒为 null（不需要用户动手）。
   */
  val skipSegmentLabel = MutableStateFlow<String?>(null)
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

  // ── 暂停写入通道（v179，见 init 里的消费者）──────────────────────────────

  /**
   * 「想要的暂停态」单槽，`null` = 还没有人要求过。
   *
   * 只由 [setPaused] 写，只由 [init] 里那条串行协程消费。存在的理由：
   * **同一文件上 pause/unpause 的落地顺序必须可控**（详见 init 里的注释）。
   */
  private val _pauseIntent = MutableStateFlow<Boolean?>(null)

  /**
   * 这次 seek 期间的暂停**是我们自己打上的** ⇒ 松手时必须由我们恢复。
   *
   * 刻意不再用 `paused` 的快照判断「用户本来是不是在播」：那个快照来自
   * `propBoolean` 的 StateFlow，是**异步**值 —— 上一次 seek 的恢复还没落地时它
   * 仍然是 `true`，于是这一次就被误判成「用户本来就在暂停」⇒ 松手后不再恢复
   * ⇒ 播放永久停住。这是「拖一下就暂停」的直接成因，见 [beginSeekPause]。
   */
  @Volatile
  private var seekPauseApplied = false

  /** [seekPauseApplied] 的安全阀：手势被中途掐断（`onValueChangeFinished` 不触发）时的最后一道网。 */
  private var seekPauseSafetyJob: Job? = null

  /** 安全阀最近一次续期的时间戳（拖动每帧都会 seek，别每帧都重建协程）。 */
  private var seekPauseRenewedAt = 0L

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

  /** mpv 自己报的 `duration`（秒）。> 0 才可信，见 [preciseDuration]。 */
  private val _preciseDuration = MutableStateFlow(0f)

  /**
   * 兜底时长（秒）：mpv 报不出来、或报的值已被证伪时，由别的来源探到的值。
   *
   * 写入方只有一个 —— [applyProbedDuration]，由播放页的时长看守协程调用。
   */
  private val _probedDuration = MutableStateFlow(0f)

  /**
   * mpv 报的时长是否**已被证伪**。
   *
   * 证伪的依据只有一个，而且是物理事实：**已经播到的位置超过了声称的总时长**
   * （`.mts` 上很常见 —— mpv 会把时长报成一个很小的值，比如播到 15 秒、总时长还写着 8 秒）。
   * 一旦置位，[preciseDuration] 就彻底不再采信 mpv 的值，只用探针值。
   *
   * 由播放页的时长看守协程通过 [markDurationUntrusted] 置位，换片时随
   * [resetProbedDuration] 一起复位。
   */
  private val _durationUntrusted = MutableStateFlow(false)

  /**
   * 进度条用的总时长（秒）—— **mpv 的真值与兜底值合成后的结果**。
   *
   * ## 为什么需要这一层
   *
   * `.mts` / MPEG-TS 这类容器**没有内嵌索引**，时长只能靠「seek 到文件尾、读最后一个
   * PTS」算出来。取流层一旦不能随机读取（或那一步读到的并不是真正的尾部），mpv 的
   * `duration` 就只有两种坏法：
   *  · **恒为 0** —— 进度条显示 00:00、拖不动，连带「已看/续播」（都读 duration）一起失效；
   *  · **报一个很小的值** —— 播到 15 秒了总时长还写着 8 秒，进度条一开就顶到 100%。
   *
   * 而同一个地址交给 GSY 却正常 —— ExoPlayer / Android MediaExtractor 用的是
   * 「文件长度 ÷ 平均码率」估算，**不需要 seek 到尾部**。所以合成规则是：
   *  ① [markDurationUntrusted] 置位过 ⇒ 一律用探针值（mpv 已被证伪）；
   *  ② mpv 报 0 ⇒ 用探针值顶上；
   *  ③ mpv 报的值**不足探针值一半** ⇒ 判为伪值，仍用探针值；
   *  ④ 两者都拿不到 ⇒ 0，UI 侧如实显示 00:00（不编造时长）。
   */
  val preciseDuration: StateFlow<Float> =
    combine(_preciseDuration, _probedDuration, _durationUntrusted) { mpvDuration, probed, untrusted ->
      when {
        untrusted && probed > 0f -> probed
        mpvDuration <= 0f -> probed
        probed <= 0f -> mpvDuration
        mpvDuration < probed * 0.5f -> probed
        else -> mpvDuration
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

  /**
   * 写入「兜底时长」（秒）。
   *
   * 只在两种情况下生效：mpv 还没给出时长（0），或 mpv 的值已经被证伪
   * （[markDurationUntrusted]）。其余情况 mpv 一旦报出真值，永远以它为准。
   * 传入非法值（NaN / 负数 / 0）一律忽略，避免把进度条弄成空值。
   */
  fun applyProbedDuration(seconds: Float) {
    if (!seconds.isFinite() || seconds <= 0f) return
    if (!_durationUntrusted.value && _preciseDuration.value > 0f) return
    _probedDuration.value = seconds
  }

  /**
   * 宣布「mpv 报的这个时长不可信」。
   *
   * 判据（由播放页给）：**已播位置已经超过声称的总时长** —— 这是物理矛盾，不需要再讨论。
   * 置位后 mpv 的值直接被忽略，见 [preciseDuration] 的合成规则①。
   */
  fun markDurationUntrusted() {
    _durationUntrusted.value = true
  }

  /** 换片时清掉上一部片的兜底时长与「已证伪」标记，否则新片会顶着旧片的时长画进度条。 */
  fun resetProbedDuration() {
    _probedDuration.value = 0f
    _durationUntrusted.value = false
    // ⚠️ 必须把 mpv 那一份也清掉（v179）：不清的话，新片报出时长之前，
    // 进度条画的是**上一部片**的时长；更糟的是 [acceptMpvDuration] 的
    // 「同片跳变」闸门会拿旧片的值当基准，把新片（更长的）正常时长误判成离谱值。
    // 清成 0 之后不用等 StateFlow 重发 —— 时长看守每秒都会把 mpv 当前值喂回
    // [acceptMpvDuration]，最迟一秒就能恢复（见 `PlayerActivity.startDurationWatchdog`）。
    _preciseDuration.value = 0f
  }

  /**
   * 采信一条 mpv 报的 `duration`（秒）—— **带「离谱值」闸门**（v179）。
   *
   * ## 为什么需要闸门
   *
   * 以前这里是裸写（`if (dur > 0) _preciseDuration.value = dur`）。但 mpv 的 `duration`
   * **不是「报出来就定死」的属性**：TS / 直播录制这类没有内嵌索引的容器，时长靠
   * 「seek 到尾部读最后一个 PTS」算出来，那次 seek 一旦落在坏包上、或上游给的
   * 文件长度是错的，它就会重算出一个大得离谱的数（用户实测：正常 40 分钟的片，
   * 放着放着右端变成几百小时）。
   *
   * 而 [preciseDuration] 原来的合成规则只拦「报小了」（`mpv < probed × 0.5`），
   * 对「报大了」完全不设防 —— 所以巨值会直接生效、并且**永远不会被推翻**
   * （看守协程的 overflow 判据是 `pos > duration`，位置永远追不上几百小时）。
   *
   * ## 两道闸（任一命中：拒绝写入 + 宣布 mpv 不可信）
   *
   *  ① 有探针值时 `mpv > probed × [DURATION_OVERSIZE_RATIO]`。探针来自
   *     `MediaMetadataRetriever`（与 GSY 同源）或 `file-size ÷ bitrate`，
   *     与「seek 到尾部」是两条互不相干的路，两边差一个量级就说明 mpv 这次算错了。
   *  ② 同一份文件内的跳变 `mpv > 上一版 × [DURATION_OVERSIZE_RATIO]`。
   *     换片时 [resetProbedDuration] 会清零，所以这里比的一定是**同一份文件**
   *     的前后两次采样 —— 「放着放着变几百小时」正是这一条能抓住的形态。
   *
   * 拒绝写入 ≠ 时长没了：`_preciseDuration` 保留上一次的合理值，进度条继续用它画；
   * 同时 `_durationUntrusted` 置位，看守探到真值后会接管（见 [preciseDuration] 规则①）。
   */
  fun acceptMpvDuration(seconds: Double) {
    val value = seconds.toFloat()
    if (!value.isFinite() || value <= 0f) return

    val probed = _probedDuration.value
    if (probed > 0f && value > probed * DURATION_OVERSIZE_RATIO) {
      _durationUntrusted.value = true
      return
    }
    val previous = _preciseDuration.value
    if (previous > 0f && value > previous * DURATION_OVERSIZE_RATIO) {
      _durationUntrusted.value = true
      return
    }
    _preciseDuration.value = value
  }

  /** 当前可用的总时长（秒）：mpv 真值优先，其次探针兜底；0 = 两者都拿不到。 */
  fun effectiveDurationSeconds(): Float = preciseDuration.value

  // Audio state
  val currentVolume = MutableStateFlow(host.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
  private val volumeBoostCap by PlayerLib.propInt["volume-max"].collectAsState(viewModelScope)

  init {
    // Poll precise position only when playing
    // ⚠️ **必须跑在 IO 线程上，不能留在 viewModelScope 默认的 Main。**
    // `MPVLib.getPropertyDouble` 是同步 JNI 调用，客户端线程会去抢 mpv 的
    // 播放线程锁（`mpctx->lock`）。而 demux / 解码线程每秒要持锁几十次，
    // **码率越高、单帧越大，持锁时间越长** —— 4K 原盘（70GB 那种）上主线程
    // 每 42ms 抢一次锁，抢不到就阻塞，直接和「渲染 + 触摸事件」抢同一个线程，
    // 观感就是**整个 App 卡成幻灯片**（不是视频卡，是 UI 卡）。
    // 挪到 `Dispatchers.Default` 之后：读不到锁只影响这个采样协程自己，
    // 位置稍晚一拍对进度条完全无感，但主线程再也不会被 JNI 卡住。
    viewModelScope.launch(Dispatchers.Default) {
      while (isActive) {
        val time = PlayerLib.getPropertyDouble("time-pos")
        if (time != null) {
          _precisePosition.value = time.toFloat()
        }
        delay(42) // ~24fps updates
      }
    }

    // Update precise duration when the integer duration changes (avoid polling)
    // ⚠️ 不能裸写 `_preciseDuration`：mpv 会在播放中途重算时长，偶尔算出大得离谱的值
    // （TS 类容器靠 seek 到尾部读 PTS，读到坏包就翻车）。闸门逻辑见 [acceptMpvDuration]。
    viewModelScope.launch {
      PlayerLib.propInt["duration"].collect { _ ->
        PlayerLib.getPropertyDouble("duration")?.let(::acceptMpvDuration)
      }
    }

    // ── 暂停写入的**单槽串行**通道 ──
    // 这里必须串行：以前每个 pause()/unpause() 各起一个 IO 协程去写 mpv 属性，
    // **同一个文件上「暂停 → 恢复」的落地顺序没有任何保证**。进度条松手时会先
    // unpause 再（下一轮拖动的第一帧）pause，两个协程并发 ⇒ 谁最后落地看调度，
    // 结果是「暂停」赢下来，而 UI 侧那次收尾又以为「本来就在暂停」不再恢复
    // ⇒ 用户看到的就是「拖一下/快进一下就永久暂停了」。
    // 单槽 StateFlow 天然「最后一次意图胜出」，且 collect 是串行消费 ⇒ 顺序确定。
    viewModelScope.launch(Dispatchers.IO) {
      _pauseIntent.filterNotNull().collect { wantPaused ->
        if (wantPaused) {
          PlayerLib.setPropertyBoolean("pause", true)
          withContext(Dispatchers.Main) { host.abandonAudioFocus() }
        } else {
          withContext(Dispatchers.Main) { host.requestAudioFocus() }
          PlayerLib.setPropertyBoolean("pause", false)
        }
      }
    }

    // ── 网络加载速度：固定 1 秒一拍 ──
    // 数据源与归零策略见 [MpvNetSpeedSampler] / [netSpeedBytesPerSec] 的注释。
    // 轮询而非 observe：cache-speed 的属性变更通知不保证按秒触发，定时读最稳。
    // ⚠️ 同样放 Default：一条采样要连读好几个 mpv 属性（JNI + 播放线程锁），
    // 放在主线程上就又多一处周期性卡顿源（理由见上面 time-pos 那段）。
    viewModelScope.launch(Dispatchers.Default) {
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
    combine(allTracks, subtitlesPreferences.subtitleTombstones.changes()) { list, _ ->
      // 删除 tombstone 落盘后立即从面板消失，即使 mpv 的 sub-remove 还没回调。
      list.filter { it.isSubtitle && !isSubtitleTrackIgnored(it) }.toImmutableList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

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

  /**
   * 首帧**已经真正画到屏幕上了**吗 —— 「出画面前」的加载层靠它收起。
   *
   * ## 为什么不用现成的 `isReady`
   *
   * `PlayerActivity.isReady` 只在 `FILE_LOADED` 时置真、且**换片时不会复位**
   * （只在 `onDestroy` 里清零）⇒ 拿来当加载判据，第二集开始时加载层就不会再出现了。
   *
   * ## 为什么不用 `vo-configured`
   *
   * mpv 文档对它的定义是「VO 是否已配置好，通常对应视频窗口是否可见」——
   * 但它在 `vo_reconfig2()` 返回时就为 yes，**早于第一帧 `flip_page`**。
   * 拿它当判据 = 加载动画先消失、再黑一下、画面才浮现，**比不显示更难看**。
   *
   * ⇒ 用 `MPV_EVENT_PLAYBACK_RESTART`：mpv 的日志顺序是
   *   `first video frame after restart shown` → `playback-restart`，
   *   也就是首帧上屏之后才发。这与视界流那条的教训一致
   *   （见 `FeedViewModel.buffered` 的注释：判据早一步就会「转圈消失→黑屏→画面」）。
   */
  private val _firstFrameRendered = MutableStateFlow(false)
  val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

  /** 换片 / 重新加载时把「首帧已出」打回 false，让加载层重新出现。 */
  fun onLoadingNewFile() {
    _firstFrameRendered.value = false
    // 换片后 mpv 的 `pause` 会被 loadfile 重置，留在通道里的旧意图（多半是 `true`）
    // 已经不代表事实了 —— 不清的话 [beginSeekPause] 会拿它当「当前已暂停」，
    // 于是换片后的第一次拖动不会暂停（只是解码更吃力，不会卡住播放，但要修掉）。
    _pauseIntent.value = null
  }

  /** mpv 报 `PLAYBACK_RESTART`（首帧已上屏）时调用。 */
  fun onFirstFrameRendered() {
    _firstFrameRendered.value = true
  }

  /**
   * 播放失败原因（非空 ⇒ 界面上显示错误卡片）。
   *
   * 与 [firstFrameRendered] 是两回事：那个管「还在等画面」，这个管「画面永远不会来了」。
   * 写入方有两个，都发生在**播放之前**：
   * · Emby 侧预解析判定死链 ⇒ 通过 `play_error` extra 带进播放页；
   * · 用户点「重试」时重新探测仍被拒（见 [PlayerActivity.retryPlayback]）。
   *
   * 为什么不靠 mpv 的 `end-file` 事件兜底：死链会让 libmpv 在 native 层直接崩掉
   * （整个 App 消失），`end-file` 根本来不及发出。所以判断必须发生在播放之前。
   */
  private val _playbackError = MutableStateFlow<String?>(null)
  val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

  /** 显示播放错误卡片（`message` 是已经写好的人话，直接展示）。 */
  fun showPlaybackError(message: String) {
    _playbackError.value = message
  }

  /** 关掉错误卡片（重试 / 换片前调用）。 */
  fun clearPlaybackError() {
    _playbackError.value = null
  }

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
  @Volatile
  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null

  private val subtitleSessionLock = Any()
  private var subtitleSessionSerial = 0L

  @Volatile
  private var subtitleUserActionSerial = 0L

  @Volatile
  private var subtitleMountState = SubtitleMountState()

  fun beginSubtitleSession(): Long = synchronized(subtitleSessionLock) {
    subtitleSessionSerial += 1
    subtitleMountState = SubtitleMountArbiter.startSession(
      subtitleMountState,
      sessionId = subtitleSessionSerial,
    )
    boundSubtitleApplied = false
    embySubtitleApplied = false
    subtitleUserActionSerial = 0L
    subtitleSessionSerial
  }

  fun currentSubtitleSessionId(): Long = subtitleMountState.sessionId

  fun isSubtitleSessionCurrent(sessionId: Long): Boolean =
    subtitleMountState.sessionId == sessionId

  fun canAutomaticallySelectSubtitle(source: SubtitleSource): Boolean =
    SubtitleMountArbiter.decide(
      subtitleMountState,
      SubtitleMountRequest(
        sessionId = subtitleMountState.sessionId,
        source = source,
        select = true,
        ignored = false,
      ),
    ) == SubtitleMountDecision.MountAndSelect

  fun clearAutomaticSubtitleSuppression() {
    synchronized(subtitleSessionLock) {
      subtitleUserActionSerial += 1L
      subtitleMountState = SubtitleMountArbiter.clearUserOverride(subtitleMountState)
      embyAutoSuppressed = false
    }
  }

  /**
   * 本次加载中，「这部片子的手动字幕绑定」是否已经接管了字幕选择。
   *
   * 为 true 时 PlayerActivity **必须放弃**按存档 `sid` 覆写（见 `applyRestoredSubtitleSelection`），
   * 也放弃「每剧记住字幕轨」的指纹恢复（见 `applyRememberedPlaybackSettings`）。
   *
   * 为什么要这个标记：存档里的 `sid` 是**保存那一刻从 mpv 读到的值**，历史上有被存成 -1 的
   * （读属性的线程不对时读不到），而 `applyRestoredSubtitleSelection` 看到 `sid <= 0` 会主动
   * 把 `player.sid = -1`。结果是「刚按绑定挂上并选中的字幕，1.2 秒后被关掉」——
   * 用户看到的就是「字幕记忆失效」。精确的手动绑定必须压过模糊的存档与剧集指纹。
   */
  var boundSubtitleApplied: Boolean = false
    private set

  // External subtitle tracking
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()
  
  // Mapping from mpv internal path/URI to the original source URI (resolves deletion issues)
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  /** mpv 归一化后的路径 → 在线权威记录；删除时不能只按缓存副本路径猜来源。 */
  private val mpvPathToOnlineRecordMap = mutableMapOf<String, DownloadedSubtitle>()

  /** mpv 归一化后的路径 → Emby 的完整身份；缓存删除与服务端选择清理都依赖它。 */
  private val mpvPathToEmbyIdentityMap = mutableMapOf<String, EmbyTrackIdentity>()

  /** sub-add 已预留选择权的路径；用于区分「确认自己的轨道」与「同优先级后来者抢选」。 */
  private val mpvPathToSelectionSourceMap = mutableMapOf<String, SubtitleSource>()

  private data class EmbyTrackIdentity(
    val serverId: String,
    val itemId: String,
    val mediaSourceId: String?,
    val subtitle: SubtitleInfo,
  )

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

  // ==================== 画面旋转（顺时针 90° 步进） ====================

  /**
   * 画面旋转累计状态。
   *
   * 与镜像 / 垂直翻转不同，旋转是**四态**（0/90/180/270）而不是二态，
   * 所以外面存的是 [RotationState] 而不是 Bool —— 按钮要显示当前角度，
   * 单靠一个布尔值表达不了「转了两次」。
   */
  private val _rotation = MutableStateFlow(RotationState.Identity)
  val rotation: StateFlow<RotationState> = _rotation.asStateFlow()

  /** 便利出口：UI 里最常读的是「有没有转过」。 */
  val isRotated: StateFlow<Boolean> =
    _rotation
      .map { it.isRotated }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  init {
    // 预热公共字幕归档的文件名缓存：重装后第一次播放就能按片名找回手动字幕，
    // 同时让 hasBoundSubtitle() 不必在主线程查 MediaStore。
    viewModelScope.launch(Dispatchers.IO) {
      ExternalSubtitleArchive.preload(host.context.applicationContext)
    }

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

    /**
     * 拖动进度条期间，两次**真正下发**的 mpv seek 之间的最小间隔。
     *
     * 视界流 `previewSeek` 同款口径（那边是 120ms）：保住「画面跟手」的同时，
     * 不让每个移动事件都让解码器在网络流上重新定位一遍。拖完的最终落点
     * 不靠节流那条 —— 由 `commitScrubSeek` 在松手时强制保证。
     */
    const val SCRUB_SEEK_INTERVAL_MS = 150L

    /**
     * 「mpv 报的时长超过参照值多少倍就判为离谱」。
     *
     * 4 倍是刻意留宽的：真实片源里 `file-size ÷ bitrate` 对 VBR 的估算误差通常
     * 在 ±30% 内，两倍已经很反常；而 TS 读坏包算出来的巨值是**一个量级以上**的偏差
     * （40 分钟 → 几百小时）。取 4 既不会误伤正常的重算，也能一刀切掉巨值。
     */
    const val DURATION_OVERSIZE_RATIO = 4f

    /** seek 暂停的安全阀时长：远大于任何一次正常拖动，只在收尾回调被吞掉时才到期。 */
    const val SEEK_PAUSE_SAFETY_MS = 6_000L

    /** 安全阀续期的最小间隔（拖动每帧都会 seekTo）。 */
    const val SEEK_PAUSE_RENEW_INTERVAL_MS = 1_000L

    /** 网络加载速度的采样周期：1 秒一拍，与常见播放器（VLC / PotPlayer）的刷新节奏一致 */
    const val NET_SPEED_POLL_INTERVAL_MS = 1_000L
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
              showToast("加载音轨失败：地址无效")
            }

        PlayerLib.command("audio-add", path, "cached")
        withContext(Dispatchers.Main) {
          showToast("已添加音轨")
        }
      }.onFailure { e ->
        withContext(Dispatchers.Main) {
          showToast("加载音轨失败：${e.message}")
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
    mpvPathToOnlineRecordMap.clear()
    mpvPathToEmbyIdentityMap.clear()
    mpvPathToSelectionSourceMap.clear()
  }

  /**
   * 把用户手动选择的外挂字幕复制到公共归档（`Download/CineIsle/Subtitles`）。
   *
   * 只服务于「重装 app 后还能找回」：SharedPreferences 里的绑定和 `content://`
   * 持久权限都会在卸载时失效，而公共目录里的文件不会。失败只记日志，不影响
   * 本次已经挂上的字幕。
   */
  fun archiveExternalSubtitle(uri: Uri) {
    val title = currentMediaTitle
    if (title.isBlank() || isExternalSourceIgnored(uri)) return
    viewModelScope.launch(Dispatchers.IO) {
      if (isExternalSourceIgnored(uri)) return@launch
      val saved = ExternalSubtitleArchive.archive(host.context.applicationContext, title, uri)
      if (saved == null) {
        Log.w("PlayerViewModel", "手动字幕未归档（重装后需重新选择）：$title")
      } else {
        Log.i("PlayerViewModel", "手动字幕已归档，可跨重装恢复：$title")
      }
    }
  }

  /**
   * 把外挂字幕真正交给 mpv，**在调用方协程里顺序执行**。
   *
   * 返回时 `sub-add` 命令已经下发。恢复播放状态必须用这个挂起版本 —— 紧接着就要用
   * 存档里的 `sid` 选中字幕，那时轨道还没建出来，mpv 会把这次选择当成「不存在的轨道」
   * 直接丢弃。表现就是「上次加的外挂字幕，重开 app 就没了」。
   *
   * 注意：命令下发 ≠ 轨道已就绪。mpv 挂轨道是异步的，之后还要 [awaitTrackCountAtLeast] 等一拍。
   */
  suspend fun loadSubtitlePath(
    source: String,
    select: Boolean,
    title: String?,
    origin: SubtitleSource = SubtitleSource.AUTOLOAD,
    sessionId: Long = currentSubtitleSessionId(),
  ): Boolean = loadSubtitle(
    Uri.parse(source),
    select = select,
    silent = true,
    title = title,
    source = origin,
    sessionId = sessionId,
  ) != null

  internal suspend fun loadSubtitle(
    uri: Uri,
    select: Boolean,
    silent: Boolean,
    title: String? = null,
    lang: String? = null,
    allowManagedPrivate: Boolean = false,
    source: SubtitleSource = SubtitleSource.MANUAL,
    sessionId: Long = currentSubtitleSessionId(),
    sourceIgnored: Boolean = false,
  ): String? {
    val uriString = uri.toString()
    if (!isSubtitleSessionCurrent(sessionId)) return null
    val ignored = sourceIgnored || isExternalSourceIgnored(uri)
    if (ignored) return null
    val userActionSerial = if (source == SubtitleSource.MANUAL) {
      markUserSubtitleAction()
    } else {
      subtitleUserActionSerial
    }
    val initialDecision = SubtitleMountArbiter.decide(
      subtitleMountState,
      SubtitleMountRequest(sessionId, source, select, ignored),
    )
    if (initialDecision == SubtitleMountDecision.RejectStale ||
      initialDecision == SubtitleMountDecision.RejectIgnored ||
      initialDecision == SubtitleMountDecision.RejectSuppressed ||
      (source == SubtitleSource.MANUAL && userActionSerial != subtitleUserActionSerial)
    ) {
      Log.i("PlayerViewModel", "skip subtitle ($initialDecision): $uriString source=$source session=$sessionId")
      return null
    }
    val sourcePath = localPathOfUri(uri)
    if (!allowManagedPrivate && sourcePath?.let(::isEmbyCachePath) == true) {
      // 旧存档可能保存过 Emby 缓存 file:// URI。缓存必须由 Emby 身份重新发现 / 下载，
      // 不能绕过 tombstone 直接当普通外挂挂回来。
      Log.i("PlayerViewModel", "skip managed Emby cache restore: $uriString")
      return null
    }
    if (_externalSubtitles.contains(uriString)) {
      if (source == SubtitleSource.MANUAL && userActionSerial != subtitleUserActionSerial) return null
      Log.d("PlayerViewModel", "Subtitle already tracked: $uriString (select=$select)")
      if (select) {
        findSubtitleTrackIdOnMain(uriString)?.let {
          selectSubtitleTrack(it, source, sessionId)
        }
      }
      return mpvPathToUriMap.entries.firstOrNull { it.value == uriString }?.key
    }

    val mpvPath = runCatching {
      val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"
      if (!isValidSubtitleFile(fileName)) {
        if (!silent) showToast("字幕格式不支持")
        return null
      }

      if (uri.scheme == "content") {
        try {
          host.context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
          Log.i("PlayerViewModel", "Persistent permission not taken for $uri (may already have it via tree)")
        }
      }

      // 解析 / 转码是 IO；mpv 命令与 URI 映射留给主线程，避免后台碰 native。
      val playbackPath = withContext(Dispatchers.IO) {
        val rawPath = uri.resolveUri(host.context) ?: uri.toString()
        runCatching { SubtitleFileNormalizer.forPlayback(host.context, rawPath) }.getOrDefault(rawPath)
      }

      withContext(Dispatchers.Main) {
        val finalIgnored = sourceIgnored || isExternalSourceIgnored(uri)
        val decision = SubtitleMountArbiter.decide(
          subtitleMountState,
          SubtitleMountRequest(sessionId, source, select, finalIgnored),
        )
        if (decision == SubtitleMountDecision.RejectStale ||
          decision == SubtitleMountDecision.RejectIgnored ||
          decision == SubtitleMountDecision.RejectSuppressed ||
          (source == SubtitleSource.MANUAL && userActionSerial != subtitleUserActionSerial)
        ) {
          Log.i("PlayerViewModel", "drop late subtitle ($decision): $uriString source=$source session=$sessionId")
          return@withContext null
        }
        val shouldSelect = decision == SubtitleMountDecision.MountAndSelect
        val stateBeforeCommand = subtitleMountState
        if (shouldSelect) {
          subtitleMountState = SubtitleMountArbiter.onSelectionAccepted(subtitleMountState, source)
          mpvPathToSelectionSourceMap[playbackPath] = source
        }

        // 保存原始 URI 映射：归一化可能把路径换成 cacheDir 下的 UTF-8 副本。
        mpvPathToUriMap[playbackPath] = uriString

        val readableTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val langCode = lang?.trim()?.takeIf { it.isNotEmpty() }
        runCatching {
          if (readableTitle != null && langCode != null) {
            PlayerLib.command("sub-add", playbackPath, if (shouldSelect) "select" else "auto", readableTitle, langCode)
          } else if (readableTitle != null) {
            PlayerLib.command("sub-add", playbackPath, if (shouldSelect) "select" else "auto", readableTitle)
          } else {
            PlayerLib.command("sub-add", playbackPath, if (shouldSelect) "select" else "auto")
          }
        }.onFailure {
          if (shouldSelect) {
            subtitleMountState = stateBeforeCommand
            mpvPathToSelectionSourceMap.remove(playbackPath)
          }
          throw it
        }
        if (shouldSelect) {
          viewModelScope.launch(Dispatchers.Main) {
            delay(3_000L)
            releaseUnmaterializedSubtitleSelection(playbackPath, source, sessionId)
          }
        }

        // 应用私有在线库 / Emby 缓存由各自的身份库恢复，不写进 Room 的普通外挂清单。
        if (!isAppManagedSubtitlePath(playbackPath) && !_externalSubtitles.contains(uriString)) {
          _externalSubtitles.add(uriString)
        }
        playbackPath
      }
    }.getOrElse { t ->
      Log.e("PlayerViewModel", "load subtitle failed: $uriString", t)
      null
    }
    if (mpvPath == null && !silent) withContext(Dispatchers.Main) { showToast("加载字幕失败") }
    return mpvPath
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

  /**
   * 恢复「这部片子上次手动选择的外挂字幕」。
   *
   * 两级来源，优先级从高到低：
   * 1. [SubtitleBindings] 的偏好绑定（同一台设备、未重装时最精确，指向用户原来的文件）；
   * 2. [ExternalSubtitleArchive] 的公共归档（`Download/CineIsle/Subtitles`）——
   *    **重装应用后偏好被清空、原 `content://` 权限失效时**，仍能按片名找回。
   *
   * 从归档恢复成功后会补写一条绑定，后续切集 / 重进不再重复走查询。
   *
   * ⚠️ 必须等 mpv 真的把媒体加载起来再 `sub-add`：`loadfile` 还没完成时发过去的字幕会被丢掉
   * （轨道列表还是空的）。所以先轮询 `track-list/count`，最多等 8 秒；超时就放弃本次恢复 ——
   * 静默失败即可，绝不能弹错误打断播放。
   *
   * ⚠️ 轮询必须在主线程（`viewModelScope` 默认就是）：从后台线程读 mpv native 属性会崩。
   */
  private fun restoreBoundSubtitle(mediaTitle: String, sessionId: Long) {
    val bound = runCatching { SubtitleBindings.find(subtitlesPreferences, mediaTitle) }.getOrNull()
    boundSubtitleApplied = false
    viewModelScope.launch {
      // 偏好绑定为空（典型：重装后）⇒ 先查公共归档。归档查找是磁盘 IO，放 IO 线程。
      val archived =
        if (bound.isNullOrBlank()) {
          withContext(Dispatchers.IO) {
            ExternalSubtitleArchive.find(
              host.context.applicationContext,
              mediaTitle,
              ::isExternalSourceIgnored,
            )?.toString()
          }
        } else {
          null
        }
      if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitle) return@launch
      val source = bound?.takeIf { it.isNotBlank() } ?: archived
      if (source.isNullOrBlank()) return@launch
      // 升级前就已经存在的绑定：顺手补一份公共归档，避免用户这次不重新选择、
      // 下次重装又丢。已有归档则不重复复制，且 tombstone 归档不算「已有」。
      if (!bound.isNullOrBlank() && !ExternalSubtitleArchive.hasKnown(mediaTitle)) {
        withContext(Dispatchers.IO) {
          if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitle) return@withContext
          val visibleArchive = ExternalSubtitleArchive.find(
            host.context.applicationContext,
            mediaTitle,
            ::isExternalSourceIgnored,
          )
          if (visibleArchive == null && !isExternalSourceIgnored(Uri.parse(bound))) {
            runCatching { ExternalSubtitleArchive.archive(host.context.applicationContext, mediaTitle, Uri.parse(bound)) }
          }
        }
      }
      if (bound.isNullOrBlank() && !archived.isNullOrBlank()) {
        // 重装恢复：补回偏好绑定，后续逻辑（Emby 自动选择 / hasBoundSubtitle）立刻看得见。
        runCatching { SubtitleBindings.bind(subtitlesPreferences, mediaTitle, archived) }
        Log.i("PlayerViewModel", "manual subtitle restored from archive: $mediaTitle")
      }

      var waited = 0L
      while (waited < 8_000L && (PlayerLib.getPropertyInt("track-list/count") ?: 0) <= 0) {
        delay(120)
        waited += 120
        if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitle) return@launch
      }
      // 目录扫描可能已经把它以 `auto` 方式加进轨道（select = false）。那种情况下清单里
      // 已经有这条字幕，只是没选中；此时再走 loadSubtitle 会被「已跟踪」直接挡回去，
      // 表现成「字幕在列表里但画面上没有」。所以先查轨道，能查到就直接选中。
      val existing = findSubtitleTrackIdOnMain(source)
      if (existing != null) {
        boundSubtitleApplied = selectSubtitleTrack(existing, SubtitleSource.BOUND, sessionId)
      } else {
        val parsed = runCatching { Uri.parse(source) }.getOrNull()
        if (parsed != null) {
          loadSubtitle(
            parsed,
            select = true,
            silent = true,
            source = SubtitleSource.BOUND,
            sessionId = sessionId,
          )
          val trackId = findSubtitleTrackIdOnMain(source)
          if (trackId != null) {
            boundSubtitleApplied = selectSubtitleTrack(trackId, SubtitleSource.BOUND, sessionId)
          }
        }
      }
      if (boundSubtitleApplied) {
        Log.d("PlayerViewModel", "bound subtitle restored for $mediaTitle")
      }
    }
  }

  /** 这部片子有没有「用户亲手挑过」的外挂字幕（偏好绑定或公共归档任一存在即可）。 */
  fun hasBoundSubtitle(mediaTitle: String): Boolean {
    if (mediaTitle.isBlank()) return false
    val boundSource =
      runCatching { SubtitleBindings.find(subtitlesPreferences, mediaTitle) }.getOrNull()
    val bound = !boundSource.isNullOrBlank() &&
      !isExternalSourceIgnored(Uri.parse(boundSource))
    // hasKnown 只看文件名缓存，无法判断对应 URI 是否已 tombstone；这里必须做一次过滤查询。
    val archived = runCatching {
      ExternalSubtitleArchive.find(host.context.applicationContext, mediaTitle, ::isExternalSourceIgnored)
    }.getOrNull() != null
    return bound || archived
  }

  fun isSubtitleTrackIgnored(id: Int): Boolean {
    allTracks.value.firstOrNull { it.id == id }?.let { return isSubtitleTrackIgnored(it) }
    return runCatching {
      val count = PlayerLib.getPropertyInt("track-list/count") ?: return false
      for (i in 0 until count) {
        if (PlayerLib.getPropertyInt("track-list/$i/id") != id) continue
        if (PlayerLib.getPropertyString("track-list/$i/type") != "sub") return false
        val external = PlayerLib.getPropertyBoolean("track-list/$i/external") == true
        val path = PlayerLib.getPropertyString("track-list/$i/external-filename")
        val ffIndex = PlayerLib.getPropertyInt("track-list/$i/ff-index")
        return if (external) {
          path != null && isExternalTrackPathIgnored(path)
        } else {
          isEmbeddedTrackIndexIgnored(ffIndex)
        }
      }
      false
    }.getOrDefault(false)
  }

  private fun isSubtitleTrackIgnored(track: TrackNode): Boolean {
    if (!track.isSubtitle) return false
    if (track.external == true) {
      val path = track.externalFilename ?: return false
      return isExternalTrackPathIgnored(path)
    }
    return isEmbeddedTrackIndexIgnored(track.ffIndex?.toInt())
  }

  private fun isEmbeddedTrackIndexIgnored(streamIndex: Int?): Boolean {
    if (streamIndex == null || streamIndex < 0) return false
    val state = embySubtitleState.value
    val serverId = state.serverId ?: embyTrackServerId ?: return false
    val itemId = state.itemId ?: embyTrackItemId ?: return false
    val mediaSourceId = state.mediaSourceId ?: embyTrackMediaSourceId
    return subtitleTombstones.isEmbyIgnored(serverId, itemId, mediaSourceId, streamIndex)
  }

  private fun embyIdentityForEmbeddedTrack(track: TrackNode): EmbyTrackIdentity? {
    if (track.external == true) return null
    val streamIndex = track.ffIndex?.toInt()?.takeIf { it >= 0 } ?: return null
    val state = embySubtitleState.value
    val serverId = state.serverId ?: embyTrackServerId ?: return null
    val itemId = state.itemId ?: embyTrackItemId ?: return null
    val mediaSourceId = state.mediaSourceId ?: embyTrackMediaSourceId
    val subtitle = state.subtitles.firstOrNull { it.streamIndex == streamIndex } ?: return null
    return EmbyTrackIdentity(serverId, itemId, mediaSourceId, subtitle)
  }

  private fun isExternalTrackPathIgnored(path: String): Boolean {
    mpvPathToOnlineRecordMap[path]?.let { record ->
      if (onlineSubtitleLibrary.isIgnored(record)) return true
    }
    mpvPathToEmbyIdentityMap[path]?.let { identity ->
      if (embySubtitleController.isIgnored(identity.serverId, identity.itemId, identity.subtitle)) return true
    }
    val originalUriString = mpvPathToUriMap[path]
    val originalPath = originalUriString?.let { localPathOfUri(Uri.parse(it)) }
    val candidatePath = originalPath ?: SubtitleTombstones.normalizeAbsolutePath(path)
    return subtitleTombstones.isExternalIgnored(candidatePath, originalUriString ?: path)
  }

  private fun localPathOfUri(uri: Uri): String? =
    uri.path.takeIf { uri.scheme.isNullOrBlank() || uri.scheme == "file" }

  private fun isExternalSourceIgnored(uri: Uri): Boolean =
    subtitleTombstones.isExternalIgnored(localPathOfUri(uri), uri.toString())

  private fun isEmbyCachePath(path: String): Boolean = isUnder(path, File(host.context.cacheDir, "emby_subtitles"))

  private fun isAppManagedSubtitlePath(path: String): Boolean =
    isEmbyCachePath(path) || isUnder(path, File(host.context.filesDir, "online_subtitles"))

  private fun isUnder(path: String, root: File): Boolean {
    val normalized = SubtitleTombstones.normalizeAbsolutePath(path) ?: return false
    val candidate = File(normalized).absoluteFile.normalize().path
    val parent = root.absoluteFile.normalize().path
    return candidate == parent || candidate.startsWith(parent + File.separator)
  }

  /**
   * 按「字幕 Uri」查外挂字幕的轨道 id；轨道还没建出来时返回 null。
   *
   * 用 [mpvPathToUriMap] 反查而不是把 Uri 再解析一次：[Uri.resolveUri] 对 `content://`
   * 是**真的 open 一个 fd**，多解析一次就多漏一个 fd，而且两次拿到的 `fd://N` 编号
   * 本来也不一样，拿它去比 `external-filename` 永远不会相等。
   */
  private fun findSubtitleTrackIdOf(uriString: String): Int? =
    runCatching {
      val count = PlayerLib.getPropertyInt("track-list/count") ?: return@runCatching null
      for (i in 0 until count) {
        if (PlayerLib.getPropertyString("track-list/$i/type") != "sub") continue
        val path = PlayerLib.getPropertyString("track-list/$i/external-filename") ?: continue
        if (isExternalTrackPathIgnored(path)) continue
        if (mpvPathToUriMap[path] == uriString) {
          return@runCatching PlayerLib.getPropertyInt("track-list/$i/id")
        }
      }
      null
    }.getOrNull()

  /** 把一条已存在的字幕轨道顶成主字幕（bottom），必要时先腾出 secondary 槽位。 */
  private fun ensureSubtitleSelected(id: Int): Boolean {
    if (isSubtitleTrackIgnored(id)) return false
    return runCatching {
      if (getSecondarySubtitleId() == id) PlayerLib.setPropertyString("secondary-sid", "no")
      PlayerLib.setPropertyInt("sid", id)
    }.isSuccess
  }

  /** 所有自动/恢复来源写 sid 的唯一入口；同来源重复调用用于 sub-add 后的最终确认。 */
  suspend fun selectSubtitleTrack(
    id: Int,
    source: SubtitleSource,
    sessionId: Long = currentSubtitleSessionId(),
  ): Boolean = withContext(Dispatchers.Main) {
    if (isSubtitleTrackIgnored(id)) return@withContext false
    val state = subtitleMountState
    val decision = SubtitleMountArbiter.decide(
      state,
      SubtitleMountRequest(sessionId, source, select = true, ignored = false),
    )
    val finalizingSameSource = canFinalizeSubtitleSelection(id, source, state, decision)
    if (decision != SubtitleMountDecision.MountAndSelect && !finalizingSameSource) {
      return@withContext false
    }
    val selected = ensureSubtitleSelected(id)
    if (selected) {
      subtitleMountState = SubtitleMountArbiter.onSelectionAccepted(state, source)
    }
    selected
  }

  private fun canFinalizeSubtitleSelection(
    id: Int,
    source: SubtitleSource,
    state: SubtitleMountState,
    decision: SubtitleMountDecision,
    expectedPath: String? = null,
  ): Boolean {
    if (decision != SubtitleMountDecision.MountOnly || state.selectionSource != source) return false
    val count = PlayerLib.getPropertyInt("track-list/count") ?: return false
    for (index in 0 until count) {
      if (PlayerLib.getPropertyInt("track-list/$index/id") != id) continue
      val path = PlayerLib.getPropertyString("track-list/$index/external-filename") ?: return false
      val sameRequestedPath = expectedPath == null || path == expectedPath ||
        path.substringAfterLast('/') == expectedPath.substringAfterLast('/')
      if (!sameRequestedPath) return false
      val reservedSource = mpvPathToSelectionSourceMap[path] ?: expectedPath?.let { requested ->
        mpvPathToSelectionSourceMap.entries.firstOrNull {
          it.key.substringAfterLast('/') == requested.substringAfterLast('/')
        }?.value
      }
      return reservedSource == source
    }
    return false
  }

  fun releaseSubtitleSelection(source: SubtitleSource, sessionId: Long) {
    val state = subtitleMountState
    if (state.sessionId == sessionId && state.selectionSource == source) {
      subtitleMountState = state.copy(selectionSource = null)
    }
    if (state.sessionId == sessionId) {
      mpvPathToSelectionSourceMap.entries.removeAll { it.value == source }
    }
  }

  private fun releaseUnmaterializedSubtitleSelection(
    path: String,
    source: SubtitleSource,
    sessionId: Long,
  ) {
    if (!isSubtitleSessionCurrent(sessionId) || mpvPathToSelectionSourceMap[path] != source) return
    val count = PlayerLib.getPropertyInt("track-list/count") ?: 0
    val materialized = (0 until count).any { index ->
      PlayerLib.getPropertyString("track-list/$index/external-filename") == path
    }
    if (materialized) return
    mpvPathToSelectionSourceMap.remove(path)
    val state = subtitleMountState
    if (state.selectionSource == source && mpvPathToSelectionSourceMap.none { it.value == source }) {
      subtitleMountState = state.copy(selectionSource = null)
    }
  }

  /** 存档恢复也走同一仲裁：Intent / 手动绑定不能被旧 sid 或「上次关字幕」覆写。 */
  suspend fun applySavedSubtitleSelection(
    savedSid: Int,
    savedSecondarySid: Int,
    sessionId: Long = currentSubtitleSessionId(),
  ) = withContext(Dispatchers.Main) {
    val state = subtitleMountState
    if (state.sessionId != sessionId || state.userOverride) return@withContext
    val owner = state.selectionSource
    if (owner != null && owner.priority > SubtitleSource.SAVED.priority) return@withContext

    val sid = savedSid.takeIf { it > 0 && !isSubtitleTrackIgnored(it) } ?: 0
    val secondarySid = savedSecondarySid.takeIf { it > 0 && !isSubtitleTrackIgnored(it) } ?: 0
    when {
      sid > 0 -> {
        if (selectSubtitleTrack(sid, SubtitleSource.SAVED, sessionId)) {
          if (secondarySid > 0 && secondarySid != sid) {
            PlayerLib.setPropertyInt("secondary-sid", secondarySid)
          } else {
            PlayerLib.setPropertyString("secondary-sid", "no")
          }
        }
      }

      secondarySid > 0 -> {
        PlayerLib.setPropertyString("secondary-sid", "no")
        selectSubtitleTrack(secondarySid, SubtitleSource.SAVED, sessionId)
      }

      else -> {
        PlayerLib.setPropertyString("secondary-sid", "no")
        PlayerLib.setPropertyString("sid", "no")
        subtitleMountState = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.SAVED)
      }
    }
  }

  private fun scanLocalSubtitles(mediaTitle: String, sessionId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      val legacyTreeUri = subtitlesPreferences.subtitleSaveFolder.get()
      val saveDirPath = subtitlesPreferences.subtitleSaveDirPath.get()
      if (!SubtitleSaveStore.isConfigured(saveDirPath, legacyTreeUri)) return@launch

      try {
        val sanitizedTitle = MediaInfoParser.parse(mediaTitle).title
        val fullTitle = mediaTitle.substringBeforeLast(".")
        val checksumTitle = ChecksumUtils.getCRC32(mediaTitle)
        // 绝对路径 / SAF 树两种形态统一成 SaveNode，下面的目录口径只写一份
        val parentDir =
          SubtitleSaveStore.open(host.context, saveDirPath, legacyTreeUri) ?: return@launch

        // 网络字幕在用户目录的副本由权威副本那条轨道代表，不能再作为独立外挂加一次。
        // 清单是副本身份的权威依据；读不到时为空集，退化为现状。
        val onlineCopies = SubtitleSaveManifest.onlineCopyPaths(parentDir)

        // Scan potential folder names for compatibility: checksum, full, and sanitized
        listOf(checksumTitle, fullTitle, sanitizedTitle).distinct().forEach { folderName ->
          if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitle) return@launch
          val movieDir = parentDir.child(folderName)?.takeIf { it.isDirectory } ?: return@forEach
          movieDir.children().forEach { file ->
            if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitle) return@launch
            if (file.isFile && isValidSubtitleFile(file.name)) {
              // relativePath 口径与清单一致：dirName/fileName
              if ("$folderName/${file.name}" in onlineCopies) return@forEach
              // TSV 行可能清理失败；残留镜像必须再按外部 tombstone 过滤。
              if (isExternalSourceIgnored(file.uri)) return@forEach
              loadSubtitle(
                file.uri,
                select = false,
                silent = true,
                source = SubtitleSource.SAVE_DIRECTORY,
                sessionId = sessionId,
              )
            }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("PlayerViewModel", "Error scanning local subtitles: ${e.message}", e)
      }
    }
  }

  // ==================== 客户端网络字幕（迅雷 / SubtitleCat 下载的） ====================
  //
  // 与上面两条路径（目录扫描、片名绑定）的本质区别：**身份用 `EmbyServer.id + ItemId`**。
  //
  // 为什么不能沿用片名：详情页给在线字幕建库 / 建绑定时用的是**文件路径末段**
  // （`ABC-123.mkv`，番号在那里），而播放页的 `media-title` 是 Emby 的**显示标题**
  // （`title` extra 优先于 `filename`，见 PlayerActivity.getFileName）。两者归一化后
  // 完全对不上 ⇒ 用户在详情页下载完再点播放，面板里既看不到这条、也没有被挂上。
  // 本地在线字幕库本来就是按 ItemId 落的，这里直接用那份身份，绕开片名口径。

  private val onlineSubtitleLibrary: OnlineSubtitleLibrary by lazy {
    OnlineSubtitleLibrary(host.context.applicationContext, subtitlesPreferences)
  }

  private val _onlineSubtitles = MutableStateFlow<List<DownloadedSubtitle>>(emptyList())

  /** 当前这部片子在本地库里的网络字幕（按 ItemId 关联，供面板 / 调试查看）。 */
  val onlineSubtitles: StateFlow<List<DownloadedSubtitle>> = _onlineSubtitles.asStateFlow()

  /**
   * 把「这部片子已经下载过的网络字幕」挂进 mpv。
   *
   * 所有条目都 `sub-add`（`auto`，不抢选择），只有 [allowSelect] 为真时才把
   * 「优先使用」那条（没有优先则取最新下载）选中 —— 这样详情页下载完第一次播放
   * 就是「有字幕且已挂上」；而存档里已经选定过别的字幕时，只把它们列进面板。
   *
   * ⚠️ 必须等 mpv 把媒体真正加载起来（`track-list/count > 0`）再 `sub-add`：
   * 早发的命令会被丢掉，轨道列表里就永远没有这一条。与 [restoreBoundSubtitle] 同一口径。
   *
   * ⚠️ 读 mpv native 属性（`track-list` 那一族）**只能在主线程**：调用方常常在 IO 协程里
   * （PlayerActivity 的 handleFileLoaded 就是），所以这里显式切回 Main 再碰 mpv。
   *
   * @return true = 至少有一条挂上了（调用方据此跳过 Emby 的自动抢选）
   */
  suspend fun mountOnlineLibrarySubtitles(
    serverKey: String,
    itemId: String,
    allowSelect: Boolean,
    sessionId: Long = currentSubtitleSessionId(),
  ): Boolean {
    if (serverKey.isBlank() || itemId.isBlank()) return false
    val titleSnapshot = currentMediaTitle
    // 索引是磁盘 / SharedPreferences 读，放 IO；读完立刻回到主线程做 mpv 交互。
    val records = withContext(Dispatchers.IO) { onlineSubtitleLibrary.forMedia(serverKey, itemId) }
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != titleSnapshot) return false
    _onlineSubtitles.value = records
    Log.i("PlayerViewModel", "online library lookup: server=$serverKey item=$itemId records=${records.size}")
    if (records.isEmpty()) return false

    return withContext(Dispatchers.Main) {
      var waited = 0L
      while (waited < 8_000L && (PlayerLib.getPropertyInt("track-list/count") ?: 0) <= 0) {
        delay(120)
        waited += 120
        if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != titleSnapshot) return@withContext false
      }
      if ((PlayerLib.getPropertyInt("track-list/count") ?: 0) <= 0) return@withContext false

      var mounted = 0
      // 旧的先挂：mpv 的轨道 id 随挂载顺序递增，按下载时间升序挂上去，
      // 面板里最近下载的那条在最后 —— 与「最新下的最可能是用户想要的」一致。
      records.sortedBy { it.downloadedAt }.forEach { record ->
        if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != titleSnapshot) return@withContext false
        // 读取索引后用户可能已删除；挂载前必须再按 origin 身份检查一次。
        if (onlineSubtitleLibrary.isIgnored(record)) return@forEach
        val file = onlineSubtitleLibrary.fileOf(record)
        if (!file.isFile) {
          Log.w("PlayerViewModel", "online subtitle file missing: ${record.id}")
          return@forEach
        }
        val uriString = Uri.fromFile(file).toString()
        if (findSubtitleTrackIdOf(uriString) == null) {
          val mpvPath = loadSubtitle(
            Uri.fromFile(file),
            select = false,
            silent = true,
            title = record.title.take(60),
            allowManagedPrivate = true,
            source = SubtitleSource.ONLINE_LIBRARY,
            sessionId = sessionId,
            sourceIgnored = onlineSubtitleLibrary.isIgnored(record),
          )
          if (mpvPath != null) mpvPathToOnlineRecordMap[mpvPath] = record
          // `sub-add` 是异步的：必须等到轨道真的出现在 track-list 里才算数。
          // 早先这里是无条件置「已挂上」，文件其实没挂上去也报成功 ——
          // 调用方据此跳过 Emby 的自动字幕，结果画面上一条字幕都没有，
          // 用户只能到「网络字幕」里手点一下。这里改成以轨道是否真的出现为准。
          var waitedTrack = 0L
          while (waitedTrack < 1_000L && findSubtitleTrackIdOf(uriString) == null) {
            delay(80)
            waitedTrack += 80
            if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != titleSnapshot) return@withContext false
          }
        }
        if (findSubtitleTrackIdOf(uriString) != null) {
          mounted++
        } else {
          Log.w("PlayerViewModel", "online subtitle not mounted: ${record.title}")
        }
      }
      if (mounted == 0) {
        Log.w("PlayerViewModel", "no online subtitle mounted: item=$itemId records=${records.size}")
        return@withContext false
      }

      if (allowSelect && currentMediaTitle == titleSnapshot) {
        val pick = records.firstOrNull { it.preferred && !onlineSubtitleLibrary.isIgnored(it) }
          ?: records.filterNot { onlineSubtitleLibrary.isIgnored(it) }.maxByOrNull { it.downloadedAt }
        if (pick != null && onlineSubtitleLibrary.fileOf(pick).isFile) {
          val uriString = Uri.fromFile(onlineSubtitleLibrary.fileOf(pick)).toString()
          // `sub-add` 是异步的：轨道还没进 track-list 时设 sid 会被 mpv 丢掉，
          // 等一小会儿（最长 1.5s）再选，避免「下标到但没选中」。
          var trackId = findSubtitleTrackIdOf(uriString)
          var tries = 0
          while (trackId == null && tries < 25) {
            delay(60)
            trackId = findSubtitleTrackIdOf(uriString)
            tries++
            if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != titleSnapshot) return@withContext false
          }
          if (trackId != null && selectSubtitleTrack(trackId, SubtitleSource.ONLINE_LIBRARY, sessionId)) {
            boundSubtitleApplied = true
            // 顺手补一条「当前媒体名 → 文件」的绑定：详情页写的那条键对不上播放页，
            // 补上之后 hasBoundSubtitle / restoreBoundSubtitle 也能直接命中。
            // 只在没有绑定时才补 —— 有绑定时说明用户亲手挑过，不能被库里的默认值顶掉。
            if (currentMediaTitle.isNotBlank() &&
              SubtitleBindings.find(subtitlesPreferences, currentMediaTitle).isNullOrBlank()
            ) {
              runCatching { SubtitleBindings.bind(subtitlesPreferences, currentMediaTitle, uriString) }
            }
            Log.i(
              "PlayerViewModel",
              "online subtitle mounted by itemId: item=$itemId trackId=$trackId title=${pick.title}",
            )
          }
        }
      }
      mounted > 0
    }
  }

  /**
   * 起播兜底：本次没有任何可用字幕时，按设置检索网络字幕并挂载。
   *
   * ## 闸门（缺一不可）
   *
   * · 设置里开启了 [SubtitlesPreferences.onlineAutoFetch]；
   * · 本次没有手动绑定 / 已下载网络字幕（[boundSubtitleApplied]）；
   * · Emby 自动策略也没选出结果（[embySubtitleApplied]）；
   * · 当前画面上 `sid <= 0`（一条字幕都没在显示）。
   *
   * 任一条不成立都直接返回 [OnlineSubtitleAutoFetch.Result.Status.DISABLED] ——
   * 兜底不能顶掉用户已有的选择。
   *
   * 匹配低于阈值、检索无结果、下载失败都返回对应状态但**不弹提示**，
   * 由调用方按 `message` 是否为空决定要不要说话。
   */
  suspend fun autoFetchOnlineSubtitle(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    sessionId: Long = currentSubtitleSessionId(),
  ): OnlineSubtitleAutoFetch.Result {
    if (!subtitlesPreferences.onlineAutoFetch.get()) {
      return OnlineSubtitleAutoFetch.Result(OnlineSubtitleAutoFetch.Result.Status.DISABLED)
    }
    if (boundSubtitleApplied || embySubtitleApplied || embyAutoSuppressed) {
      Log.i("PlayerViewModel", "skip online auto fetch: 已有字幕来源")
      return OnlineSubtitleAutoFetch.Result(OnlineSubtitleAutoFetch.Result.Status.DISABLED)
    }

    val title = currentMediaTitle
    val target =
      OnlineSubtitleTarget(
        serverKey = server.id.toString(),
        itemId = itemId,
        // 匹配器按文件名口径解析番号；播放页的 media-title 就是当前文件 / 显示标题
        mediaTitle = title,
        embyTitle = title.takeIf { it.isNotBlank() },
        mediaSourceId = mediaSourceId,
      )
    val threshold = subtitlesPreferences.onlineAutoFetchThreshold.get().coerceIn(0, 100)
    val result =
      OnlineSubtitleAutoFetch.run(
        context = host.context.applicationContext,
        library = onlineSubtitleLibrary,
        preferences = subtitlesPreferences,
        target = target,
        threshold = threshold,
        saveFolderUri = subtitlesPreferences.subtitleSaveFolder.get(),
      )

    val record = result.record
    if (result.status != OnlineSubtitleAutoFetch.Result.Status.SAVED || record == null) {
      return result
    }

    // 落盘成功后挂载并选中：与「面板里点使用」走同一条 loadSubtitle 路径，
    // 但这里必须等轨道真的进 track-list 再置标记，避免「报成功、画面没字幕」。
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != title) return result
    if (onlineSubtitleLibrary.isIgnored(record)) return result
    val file = onlineSubtitleLibrary.fileOf(record)
    if (!file.isFile) return result
    val uri = Uri.fromFile(file)

    withContext(Dispatchers.Main) {
      val mpvPath = loadSubtitle(
        uri,
        select = true,
        silent = true,
        title = record.title.take(60),
        allowManagedPrivate = true,
        source = SubtitleSource.ONLINE_AUTO_FETCH,
        sessionId = sessionId,
        sourceIgnored = onlineSubtitleLibrary.isIgnored(record),
      )
      if (mpvPath != null) mpvPathToOnlineRecordMap[mpvPath] = record
      var waited = 0L
      while (waited < 1_500L && findSubtitleTrackIdOf(uri.toString()) == null) {
        delay(80)
        waited += 80
        if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != title) return@withContext
      }
      val trackId = findSubtitleTrackIdOf(uri.toString())
      if (trackId != null && selectSubtitleTrack(trackId, SubtitleSource.ONLINE_AUTO_FETCH, sessionId)) {
        boundSubtitleApplied = true
        Log.i("PlayerViewModel", "online subtitle auto-fetched: item=$itemId title=${record.title}")
      } else {
        releaseSubtitleSelection(SubtitleSource.ONLINE_AUTO_FETCH, sessionId)
        Log.w("PlayerViewModel", "online subtitle auto-fetch mounted but track missing: ${record.title}")
      }
      if (isSubtitleSessionCurrent(sessionId) && currentMediaTitle == title) {
        _onlineSubtitles.value = onlineSubtitleLibrary.forMedia(server.id.toString(), itemId)
      }
    }
    return result
  }

  // ==================== Emby 字幕（服务端已识别的字幕） ====================
  //
  // 与既有两条字幕路径的边界（三者**互斥**，不要混着改）：
  //  · `scanLocalSubtitles` —— 用户字幕目录里按目录名扫（文件名口径）；
  //  · `restoreBoundSubtitle` —— 用户为这部片子亲手绑过的那个文件（最高优先级）；
  //  · 这里 —— Emby 服务端**已经识别**的字幕（内嵌轨 + 旁挂文件），按语言偏好自动选。
  //
  // 自动挂载只在「没有手动绑定、没有存档选择、当前也没显示同语言字幕」时发生，
  // 而且用户一旦在本剧里手动动过字幕（关掉 / 换轨），本次就不再自动打开。

  /** Emby 字幕的发现 / 选择 / 下载状态（面板订阅它） */
  val embySubtitleState: kotlinx.coroutines.flow.StateFlow<EmbySubtitleController.State> by lazy {
    embySubtitleController.state
  }

  private val embySubtitleController: EmbySubtitleController by lazy {
    EmbySubtitleController(host.context.applicationContext, subtitlesPreferences)
  }

  private var embyTrackServerId: String? = null
  private var embyTrackItemId: String? = null
  private var embyTrackMediaSourceId: String? = null

  fun setEmbySubtitleTrackContext(serverId: String?, itemId: String?, mediaSourceId: String?) {
    embyTrackServerId = serverId
    embyTrackItemId = itemId
    embyTrackMediaSourceId = mediaSourceId
  }

  /**
   * 本次加载是否已经由 Emby 自动挂上并选中了字幕。
   *
   * 语义与 [boundSubtitleApplied] 相同，但优先级**更低** —— 手动绑定一旦成立，
   * `applyRestoredSubtitleSelection` 会先 return，轮不到这个标记生效。
   */
  var embySubtitleApplied: Boolean = false
    private set

  /**
   * 用户在本次播放里手动动过字幕。
   *
   * 一旦为真，**正在飞的**自动下载不得再把字幕打开 —— 否则表现就是
   * 「我把字幕关了，过两秒它自己又回来了」。
   */
  @Volatile
  private var embyAutoSuppressed: Boolean = false

  /** 用户手动操作字幕时调（关闭 / 换轨 / 删除）：锁定本次自动选择。 */
  fun markUserSubtitleAction(): Long = synchronized(subtitleSessionLock) {
    subtitleUserActionSerial += 1L
    subtitleMountState = SubtitleMountArbiter.onUserAction(subtitleMountState)
    if (!embyAutoSuppressed) {
      embyAutoSuppressed = true
      Log.d("PlayerViewModel", "automatic subtitle selection suppressed by user action")
    }
    subtitleUserActionSerial
  }

  /** 换片 / 换集时清空（**必须跟 [resetExternalSubtitles] 一起调**）。 */
  fun resetEmbySubtitleState() {
    embySubtitleController.reset()
    setEmbySubtitleTrackContext(null, null, null)
    embySubtitleApplied = false
    embyAutoSuppressed = false
  }

  /**
   * Emby 字幕**自动**发现 + 选择 + 挂载。播放页在「所有其它字幕来源都挂完」之后调。
   *
   * @return true = 本次字幕选择由 Emby 决定并且已经落地（调用方据此跳过存档覆写）
   */
  suspend fun autoLoadEmbySubtitle(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    sessionId: Long = currentSubtitleSessionId(),
  ): Boolean {
    if (!isSubtitleSessionCurrent(sessionId)) return false
    if (!embySubtitleController.autoEnabled()) {
      Log.i(EmbySubtitleController.TAG, "自动挂载已关闭（设置 → 字幕）")
      return false
    }
    if (embyAutoSuppressed) {
      Log.i(EmbySubtitleController.TAG, "用户本次已手动操作过字幕，跳过自动挂载")
      return false
    }
    if (boundSubtitleApplied) {
      // 手动字幕（含重装后从归档恢复的那条）已经挂好，Emby 自动策略不得顶掉它。
      Log.i(EmbySubtitleController.TAG, "手动字幕已恢复，跳过 Emby 自动挂载")
      return false
    }
    if (hasBoundSubtitle(currentMediaTitle)) {
      // 手动绑定是「精确选择」，比任何自动策略都准
      Log.i(EmbySubtitleController.TAG, "存在手动绑定字幕，跳过 Emby 自动挂载")
      return false
    }

    val mediaTitleSnapshot = currentMediaTitle
    val selection = embySubtitleController.discoverAndSelect(server, itemId, mediaSourceId)
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitleSnapshot) {
      Log.i(EmbySubtitleController.TAG, "字幕发现期间换片，放弃本次挂载")
      return false
    }
    val chosen = selection.chosen?.let { it.copy(mediaSourceId = it.mediaSourceId ?: mediaSourceId) }
    if (chosen == null) {
      Log.i(EmbySubtitleController.TAG, "没有符合条件的字幕：${selection.detail}")
      return false
    }
    if (embySubtitleController.isIgnored(server.id.toString(), itemId, chosen)) {
      Log.i(EmbySubtitleController.TAG, "自动选中的字幕已被删除，放弃挂载")
      return false
    }

    // 画面上已经在显示同语言字幕 / 已挂外挂字幕 ⇒ 不去顶掉它
    // （TrackSelector、剧集指纹、同名外挂字幕自动加载都可能刚选好一条）
    if (activeSubtitleBlocksAutoMount(chosen.languageKey)) {
      Log.i(
        EmbySubtitleController.TAG,
        "已显示同语言字幕（${chosen.languageKey}），不再重复挂载 ${chosen.describe()}",
      )
      embySubtitleController.markMounted(chosen.key)
      return false
    }

    return applyEmbySubtitle(
      server,
      itemId,
      mediaSourceId,
      chosen,
      auto = true,
      mediaTitleSnapshot,
      sessionId,
    )
  }

  /**
   * 只**发现 + 选择**，不挂载。
   *
   * 用于「存档里已有具体字幕选择」的场合：那条选择优先，自动策略不许推翻（需求四.8），
   * 但面板仍然要能看到 Emby 认得哪些字幕、以及「如果点自动会选择哪条」。
   *
   * @return 发现到的字幕条数（0 = Emby 没有字幕或请求失败，两种情况都只记日志）
   */
  suspend fun refreshEmbySubtitleList(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    sessionId: Long = currentSubtitleSessionId(),
  ): Int {
    if (!isSubtitleSessionCurrent(sessionId) || !embySubtitleController.autoEnabled()) return 0
    if (hasBoundSubtitle(currentMediaTitle)) {
      Log.i(EmbySubtitleController.TAG, "存在手动绑定字幕，不刷新 Emby 字幕清单")
      return 0
    }
    val snapshot = currentMediaTitle
    embySubtitleController.discoverAndSelect(server, itemId, mediaSourceId)
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != snapshot) return 0
    return embySubtitleState.value.subtitles.size
  }

  /**
   * 用户在面板里**手动**点了一条 Emby 字幕。
   *
   * 与自动路径的区别：不受「已手动操作过就跳过」的限制（这次就是手动操作），
   * 并且会把这次选择记进 [SubtitlesPreferences.embySubtitleChoices]。
   */
  suspend fun pickEmbySubtitle(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    subtitle: SubtitleInfo,
  ): Boolean {
    val sessionId = currentSubtitleSessionId()
    val normalized = subtitle.copy(mediaSourceId = subtitle.mediaSourceId ?: mediaSourceId)
    if (embySubtitleController.isIgnored(server.id.toString(), itemId, normalized)) return false
    val userActionSerial = markUserSubtitleAction()
    val ok = applyEmbySubtitle(
      server = server,
      itemId = itemId,
      mediaSourceId = mediaSourceId,
      chosen = normalized,
      auto = false,
      mediaTitleSnapshot = currentMediaTitle,
      sessionId = sessionId,
      userActionSerial = userActionSerial,
    )
    if (ok) {
      embySubtitleController.rememberChoice(server.id.toString(), itemId, mediaSourceId, normalized)
      // Emby 的显式选择压过「本地文件绑定」：用户刚刚亲手指定了另一条，
      // 不解除绑定的话下次开播又会被绑定的文件顶回去，看起来像「选择没生效」。
      if (currentMediaTitle.isNotBlank()) {
        runCatching { SubtitleBindings.unbind(subtitlesPreferences, currentMediaTitle) }
      }
    }
    return ok
  }

  /** 「自动选择」按钮：忘掉记住的那一条，重跑一次自动策略。 */
  suspend fun reapplyAutoEmbySubtitle(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
  ): Boolean {
    embySubtitleController.forgetChoice(itemId)
    clearAutomaticSubtitleSuppression()
    return autoLoadEmbySubtitle(server, itemId, mediaSourceId)
  }

  /** 「关闭字幕」按钮（面板语义）：关掉当前字幕并压制本次自动挂载。 */
  fun disableSubtitles() {
    markUserSubtitleAction()
    runCatching {
      if (getSecondarySubtitleId() > 0) PlayerLib.setPropertyString("secondary-sid", "no")
      PlayerLib.setPropertyString("sid", "no")
    }
    embySubtitleApplied = false
    embySubtitleController.markMounted(null)
  }

  /** 真正把一条 Emby 字幕挂上并选中。 */
  private suspend fun applyEmbySubtitle(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    chosen: SubtitleInfo,
    auto: Boolean,
    mediaTitleSnapshot: String,
    sessionId: Long = currentSubtitleSessionId(),
    userActionSerial: Long? = null,
  ): Boolean {
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitleSnapshot) return false
    if (userActionSerial != null && userActionSerial != subtitleUserActionSerial) return false
    val serverId = server.id.toString()
    val source = if (auto) SubtitleSource.EMBY_AUTO else SubtitleSource.MANUAL
    val normalized = chosen.copy(mediaSourceId = chosen.mediaSourceId ?: mediaSourceId)
    if (embySubtitleController.isIgnored(serverId, itemId, normalized)) return false
    // ── 内嵌轨：文件里本来就有，不需要下载，只要选中 ──
    // 内嵌图像字幕（PGS / DVD）同样属于「容器里已有」，优先让 mpv 原生渲染；
    // 只有**外挂**图像字幕才需要问服务端要 .sup。误把内嵌 PGS 当外挂下载，
    // 既多一次不必要的请求，也会丢掉原生渲染。
    val isEmbeddedTrack =
      chosen.sourceType == SubtitleSourceType.EMBEDDED ||
        (chosen.sourceType == SubtitleSourceType.IMAGE_BASED && !chosen.isExternal)
    if (isEmbeddedTrack) {
      val selected = selectEmbeddedTrack(chosen, source, sessionId)
      Log.i(
        EmbySubtitleController.TAG,
        "内嵌字幕选中${if (selected) "成功" else "失败（轨道还没出现或语言对不上）"}：${chosen.describe()}",
      )
      if (selected) {
        embySubtitleController.markMounted(chosen.key)
        embySubtitleApplied = true
      }
      return selected
    }

    // ── 外挂 / 服务端交付：取字节（缓存优先）→ sub-add → 选中 ──
    val file = embySubtitleController.localFile(server, itemId, mediaSourceId, normalized) ?: run {
      Log.i(EmbySubtitleController.TAG, "字幕未挂载：${chosen.describe()} 取字节失败")
      return false
    }
    // 下载是网络动作，可能耗时几秒 —— 期间用户可能关字幕 / 换片，必须重新校验
    if ((auto && embyAutoSuppressed) ||
      (userActionSerial != null && userActionSerial != subtitleUserActionSerial)
    ) {
      Log.i(EmbySubtitleController.TAG, "下载完成时用户已手动操作字幕，丢弃本次挂载")
      return false
    }
    if (!isSubtitleSessionCurrent(sessionId) || currentMediaTitle != mediaTitleSnapshot) {
      Log.i(EmbySubtitleController.TAG, "下载完成时已换片，丢弃本次挂载")
      return false
    }
    if (embySubtitleController.isIgnored(serverId, itemId, normalized)) {
      Log.i(EmbySubtitleController.TAG, "下载完成时字幕已被删除，丢弃本次挂载")
      return false
    }

    val uri = Uri.fromFile(file)
    // 缓存文件名是 `<sha1>.<ext>`：必须把 Emby 的可读标题与语言一起交给 mpv，
    // 否则面板上显示的是哈希串（`sub-add` 的 title/lang 是位置参数，见 loadSubtitle）。
    val title = chosen.displayName.takeIf { it.isNotBlank() }
      ?: EmbySubtitleLanguage.label(chosen.languageKey)
    val lang = chosen.language?.takeIf { it.isNotBlank() } ?: chosen.languageKey

    val beforeIds = subtitleTrackIdsOnMain()
    var pendingUserActionSerial = userActionSerial
    val mpvPath = loadSubtitle(
      uri,
      select = true,
      silent = true,
      title = title,
      lang = lang,
      allowManagedPrivate = true,
      source = source,
      sessionId = sessionId,
      sourceIgnored = embySubtitleController.isIgnored(serverId, itemId, normalized),
    ) ?: return false
    if (!auto) pendingUserActionSerial = subtitleUserActionSerial
    mpvPathToEmbyIdentityMap[mpvPath] = EmbyTrackIdentity(
      serverId = serverId,
      itemId = itemId,
      mediaSourceId = normalized.mediaSourceId,
      subtitle = normalized,
    )

    val trackId = awaitNewSubtitleTrack(
      existingIds = beforeIds,
      mpvPath = uri.path ?: uri.toString(),
      fileName = file.name,
    )
    if (trackId == null) {
      releaseSubtitleSelection(source, sessionId)
      Log.w(
        EmbySubtitleController.TAG,
        "字幕挂载失败：sub-add 之后轨道列表里找不到 ${file.name}（格式 ${chosen.format}）",
      )
      return false
    }
    if (pendingUserActionSerial != null && pendingUserActionSerial != subtitleUserActionSerial) {
      Log.i(EmbySubtitleController.TAG, "等待轨道期间用户已手动操作字幕，丢弃本次选中")
      return false
    }
    // ⚠️ 只靠 `sub-add ... select` 不够稳：命令下发那一刻轨道可能还没建出来，或者被
    //    别处（存档恢复 / 轨道选择器）的 sid 写入盖掉。这里显式选中并**回读校验**，
    //    不一致就再设一次 —— 否则表现就是「字幕进了列表但画面上没有，要手动点」。
    val selected = selectSubtitleTrackVerified(trackId, source, sessionId, mpvPath)
    if (!selected) releaseSubtitleSelection(source, sessionId)
    embySubtitleController.markMounted(chosen.key)
    embySubtitleApplied = selected
    Log.i(
      EmbySubtitleController.TAG,
      "字幕挂载${if (selected) "并选中" else "但选中失败"}：${chosen.describe()} " +
        "file=${file.name} trackId=$trackId sid=${currentSidOnMain()} auto=$auto",
    )
    return selected
  }

  /** 当前所有字幕轨的 id（用于判断 sub-add 之后哪条是新的） */
  private suspend fun subtitleTrackIdsOnMain(): Set<Int> = withContext(Dispatchers.Main) {
    val count = PlayerLib.getPropertyInt("track-list/count") ?: 0
    (0 until count).mapNotNull { i ->
      if (PlayerLib.getPropertyString("track-list/$i/type") != "sub") {
        null
      } else {
        PlayerLib.getPropertyInt("track-list/$i/id")
      }
    }.toSet()
  }

  /**
   * 等本次 `sub-add` 的外挂字幕轨出现，返回它的 mpv 轨道 id。
   *
   * 只接受 `external-filename` 能对应到请求路径的轨道；不能用「最大新 id」兜底，
   * 否则并发自动来源刚加进来的另一条字幕会被误选。
   */
  private suspend fun awaitNewSubtitleTrack(
    existingIds: Set<Int>,
    mpvPath: String,
    fileName: String,
    timeoutMs: Long = 2500,
  ): Int? = withContext(Dispatchers.Main) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val count = PlayerLib.getPropertyInt("track-list/count") ?: 0
      var byFileName: Int? = null
      for (i in 0 until count) {
        if (PlayerLib.getPropertyString("track-list/$i/type") != "sub") continue
        if (PlayerLib.getPropertyBoolean("track-list/$i/external") != true) continue
        val id = PlayerLib.getPropertyInt("track-list/$i/id") ?: continue
        val externalPath = PlayerLib.getPropertyString("track-list/$i/external-filename")
        if (externalPath == mpvPath) return@withContext id
        if (id !in existingIds && byFileName == null &&
          externalPath?.substringAfterLast('/') == fileName
        ) {
          byFileName = id
        }
      }
      byFileName?.let { return@withContext it }
      delay(40)
    }
    null
  }

  /**
   * 选中一条字幕轨，并**回读 `sid` 校验**（最多 3 次）。
   *
   * @return true = mpv 上确实选中了这条
   */
  private suspend fun selectSubtitleTrackVerified(
    id: Int,
    source: SubtitleSource,
    sessionId: Long,
    expectedPath: String,
  ): Boolean = withContext(Dispatchers.Main) {
    val state = subtitleMountState
    val allowed = SubtitleMountArbiter.decide(
      state,
      SubtitleMountRequest(sessionId, source, select = true, ignored = false),
    )
    if (allowed != SubtitleMountDecision.MountAndSelect &&
      !canFinalizeSubtitleSelection(id, source, state, allowed, expectedPath)
    ) {
      return@withContext false
    }
    if (isSubtitleTrackIgnored(id)) return@withContext false
    repeat(3) { attempt ->
      val selected = ensureSubtitleSelected(id)
      if (selected) {
        subtitleMountState = SubtitleMountArbiter.onSelectionAccepted(subtitleMountState, source)
      }
      delay(if (attempt == 0) 120L else 220L)
      if (getPrimarySubtitleId() == id) return@withContext true
    }
    Log.w(EmbySubtitleController.TAG, "字幕选中校验失败：期望 sid=$id 实际=${getPrimarySubtitleId()}")
    false
  }

  private suspend fun currentSidOnMain(): Int = withContext(Dispatchers.Main) { getPrimarySubtitleId() }

  /**
   * 画面上已经在显示的字幕，是否足以让 Emby 自动挂载**让位**。
   *
   * 两种情况都让位：
   * · 已选中的是**外挂轨**（本地同名字幕 / 用户添加的文件）—— 那是用户自己的东西，
   *   Emby 再挂一条只会变成两条字幕抢同一个画面；
   * · 已选中的内嵌轨**语言就够用**（等于或覆盖 Emby 想选的那条）—— 没必要多下一次。
   */
  private suspend fun activeSubtitleBlocksAutoMount(languageKey: String): Boolean =
    withContext(Dispatchers.Main) {
      val primary = getPrimarySubtitleId()
      val secondary = getSecondarySubtitleId()
      if (primary <= 0 && secondary <= 0) return@withContext false
      subtitleTracks.value
        .filter { (it.id == primary && primary > 0) || (it.id == secondary && secondary > 0) }
        .any { node ->
          if (node.external == true) return@any true
          val key = EmbySubtitleLanguage.resolve(node.lang)
          key != EmbySubtitleLanguage.UNKNOWN &&
            (key == languageKey || EmbySubtitleLanguage.matches(languageKey, key))
        }
    }

  /**
   * 把 Emby 选中的内嵌字幕轨挂到 mpv 上。
   *
   * 匹配顺序（从最精确到兜底）：
   * 1. **`ff-index` 等于 Emby 的 `MediaStreams[].Index`** —— 这两种索引同源于 ffmpeg
   *    流序号，直连播放时通常一一对应。同一语言有简繁两条、mpv 的 `lang` 又都只写
   *    `chi` 时，只有这一层能选对；
   * 2. 语言 + 标题归一化后能够匹配（`lang` / `title` 里带脚本线索）；
   * 3. 只按语言匹配（老服务端 / 转码流不暴露 ff-index 时的兜底）。
   *
   * 只用 Emby 元数据「找轨道」，选中仍走 [ensureSubtitleSelected]，并且只挑
   * **内嵌**轨 —— 外挂轨由 [applyEmbySubtitle] 的下载路径负责。
   */
  private suspend fun selectEmbeddedTrack(
    chosen: SubtitleInfo,
    source: SubtitleSource,
    sessionId: Long,
  ): Boolean = withContext(Dispatchers.Main) {
    val candidates = runCatching {
      val count = PlayerLib.getPropertyInt("track-list/count") ?: 0
      (0 until count).mapNotNull { i ->
        if (PlayerLib.getPropertyString("track-list/$i/type") != "sub") return@mapNotNull null
        if (PlayerLib.getPropertyBoolean("track-list/$i/external") == true) return@mapNotNull null
        EmbeddedTrackCandidate(
          id = PlayerLib.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null,
          ffIndex = PlayerLib.getPropertyInt("track-list/$i/ff-index"),
          lang = PlayerLib.getPropertyString("track-list/$i/lang"),
          title = PlayerLib.getPropertyString("track-list/$i/title"),
        )
      }.filterNot { isEmbeddedTrackIndexIgnored(it.ffIndex) }
    }.getOrDefault(emptyList())
    if (candidates.isEmpty()) return@withContext false

    // ① ff-index 精确对应（同语言多条时唯一定位）
    candidates.firstOrNull { it.ffIndex != null && it.ffIndex == chosen.streamIndex }?.let { exact ->
      val selected = selectSubtitleTrack(exact.id, source, sessionId)
      Log.i(
        EmbySubtitleController.TAG,
        "内嵌字幕按 ff-index 精确选中：${chosen.describe()} ff-index=${exact.ffIndex}",
      )
      return@withContext selected
    }

    // ② 语言 + 标题线索；③ 仅语言
    val matches = candidates.filter { candidate ->
      val key = EmbySubtitleLanguage.resolve(candidate.lang, candidate.title)
      key != EmbySubtitleLanguage.UNKNOWN &&
        (key == chosen.languageKey || EmbySubtitleLanguage.matches(chosen.languageKey, key))
    }
    val picked = matches.firstOrNull { candidate ->
      val key = EmbySubtitleLanguage.resolve(candidate.lang, candidate.title)
      key == chosen.languageKey
    } ?: matches.firstOrNull()
    picked?.let {
      val selected = selectSubtitleTrack(it.id, source, sessionId)
      Log.i(
        EmbySubtitleController.TAG,
        "内嵌字幕按语言选中：${chosen.describe()} trackId=${it.id} ff-index=${it.ffIndex}",
      )
      return@withContext selected
    }
    false
  }

  private data class EmbeddedTrackCandidate(
    val id: Int,
    val ffIndex: Int?,
    val lang: String?,
    val title: String?,
  )

  private suspend fun trackCountOnMain(): Int =
    withContext(Dispatchers.Main) { PlayerLib.getPropertyInt("track-list/count") ?: 0 }

  private suspend fun findSubtitleTrackIdOnMain(uriString: String): Int? =
    withContext(Dispatchers.Main) { findSubtitleTrackIdOf(uriString) }

  fun setMediaTitle(mediaTitle: String) {
    if (currentMediaTitle != mediaTitle) {
      currentMediaTitle = mediaTitle
      lastAutoSelectedMediaTitle = null
      // Clear external subtitles when media changes。
      // ⚠️ 必须连 mpvPathToUriMap 一起清（resetExternalSubtitles 就是干这个的）：
      // 只清 _externalSubtitles 会留下过期的 `fd://N → content://` 映射，而 fd 编号会被复用，
      // findSubtitleTrackIdOf 反查时可能命中一条**根本不是绑定字幕**的旧轨道。
      resetExternalSubtitles()
      // Emby 字幕状态也是「按片」的：不清的话面板会显示上一集的 Emby 字幕清单
      resetEmbySubtitleState()
      // 网络字幕清单同理：上一部的条目留在界面上会被误当成这一部也下过
      _onlineSubtitles.value = emptyList()

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

  /** 媒体与字幕会话都已建立后，再恢复保存目录和手动绑定，避免 sub-add 落到上一条媒体。 */
  fun restoreLocalSubtitleSources() {
    val mediaTitle = currentMediaTitle
    if (mediaTitle.isBlank()) return
    val sessionId = currentSubtitleSessionId()
    scanLocalSubtitles(mediaTitle, sessionId)
    restoreBoundSubtitle(mediaTitle, sessionId)
  }

  fun getPrimarySubtitleId(): Int =
    PlayerLib.getPropertyString("sid")?.toIntOrNull() ?: 0

  fun getSecondarySubtitleId(): Int =
    PlayerLib.getPropertyString("secondary-sid")?.toIntOrNull() ?: 0

  fun removeSubtitle(id: Int) {
    // 用户亲手删了一条字幕 ⇒ 本次别再自动挂回来。
    markUserSubtitleAction()
    val sessionId = currentSubtitleSessionId()
    viewModelScope.launch {
      // 所有 mpv / 当前轨道快照必须在主线程读；持久化与文件清理再切 IO。
      val primarySid = getPrimarySubtitleId()
      val secondarySid = getSecondarySubtitleId()
      val track = allTracks.value.firstOrNull { it.id == id && it.isSubtitle } ?: return@launch
      val mpvPath = track.externalFilename
      val titleSnapshot = currentMediaTitle
      val originalUriString = mpvPath?.let { mpvPathToUriMap[it] } ?: mpvPath
      val originalUri = originalUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
      val sourcePath = originalUri?.let(::localPathOfUri)
        ?: mpvPath?.let { SubtitleTombstones.normalizeAbsolutePath(it) }
      val bound = titleSnapshot.takeIf { it.isNotBlank() }?.let {
        runCatching { SubtitleBindings.find(subtitlesPreferences, it) }.getOrNull()
      }
      val onlineRecord = mpvPath?.let { path ->
        mpvPathToOnlineRecordMap[path] ?: _onlineSubtitles.value.firstOrNull { record ->
          Uri.fromFile(onlineSubtitleLibrary.fileOf(record)).toString() == originalUriString
        }
      }
      val embyIdentity = if (track.external == true) {
        mpvPath?.let { path ->
          mpvPathToEmbyIdentityMap[path] ?: run {
            val state = embySubtitleState.value
            val source = sourcePath ?: path
            if (state.serverId != null && state.itemId != null && state.mountedKey != null &&
              isEmbyCachePath(source)
            ) {
              state.subtitles.firstOrNull { it.key == state.mountedKey }?.let { subtitle ->
                EmbyTrackIdentity(state.serverId, state.itemId, state.mediaSourceId, subtitle)
              }
            } else {
              null
            }
          }
        }
      } else {
        embyIdentityForEmbeddedTrack(track)
      }

      val persisted = if (track.external == true) {
        withContext(Dispatchers.IO) {
          when {
            embyIdentity != null -> {
              val info = embyIdentity.subtitle.copy(
                mediaSourceId = embyIdentity.subtitle.mediaSourceId ?: embyIdentity.mediaSourceId,
              )
              val now = System.currentTimeMillis()
              val embyTombstone = SubtitleTombstones.emby(
                embyIdentity.serverId,
                embyIdentity.itemId,
                info.mediaSourceId,
                info.streamIndex,
                now,
              )
              // 旧 Room 存档可能直接保存了缓存 file:// URI；补一条外部 tombstone，
              // 让它即使绕过 Emby 发现也不能作为普通外挂复活。
              val cacheTombstone = SubtitleTombstones.external(sourcePath, originalUriString, now)
              val atomic = embyTombstone != null && cacheTombstone != null &&
                subtitleTombstones.rememberAll(listOf(embyTombstone, cacheTombstone))
              atomic && embySubtitleController.delete(
                serverId = embyIdentity.serverId,
                itemId = embyIdentity.itemId,
                mediaSourceId = embyIdentity.mediaSourceId,
                subtitle = info,
              )
            }

            onlineRecord != null -> OnlineSubtitleCleanup.delete(
              context = host.context.applicationContext,
              library = onlineSubtitleLibrary,
              preferences = subtitlesPreferences,
              record = onlineRecord,
              deleteMirror = false,
            ).tombstonePersisted

            else -> {
              val now = System.currentTimeMillis()
              val sourceTombstone = SubtitleTombstones.external(sourcePath, originalUriString, now)
              val archive = titleSnapshot.takeIf { it.isNotBlank() }?.let { title ->
                runCatching {
                  ExternalSubtitleArchive.find(host.context.applicationContext, title)
                }.getOrNull()
              }
              val archiveTombstone = archive?.let {
                SubtitleTombstones.external(localPathOfUri(it), it.toString(), now)
              }
              sourceTombstone != null &&
                subtitleTombstones.rememberAll(listOfNotNull(sourceTombstone, archiveTombstone))
            }
          }
        }
      } else {
        // 内嵌轨不能从媒体文件里抹掉，但可按 Emby 流序号持久忽略，并阻止恢复时再次选中。
        embyIdentity?.let { identity ->
          embySubtitleController.delete(
            serverId = identity.serverId,
            itemId = identity.itemId,
            mediaSourceId = identity.mediaSourceId,
            subtitle = identity.subtitle,
          )
        } ?: false
      }

      if (!persisted) {
        Log.w(TAG, "subtitle deletion not persisted; keep track $id mounted")
        showToast("删除记录保存失败，字幕仍保留")
        return@launch
      }

      val stillCurrent = isSubtitleSessionCurrent(sessionId) && currentMediaTitle == titleSnapshot
      // tombstone 已同步落盘才允许卸载；清 secondary 必须先于提升，避免 mpv 拒绝同轨双槽。
      if (stillCurrent) {
        if (id == primarySid) {
          if (secondarySid > 0 && secondarySid != id && !isSubtitleTrackIgnored(secondarySid)) {
            PlayerLib.setPropertyString("secondary-sid", "no")
            PlayerLib.setPropertyInt("sid", secondarySid)
          } else {
            PlayerLib.setPropertyString("sid", "no")
          }
        } else if (id == secondarySid) {
          PlayerLib.setPropertyString("secondary-sid", "no")
        }
        PlayerLib.command("sub-remove", id.toString())

        if (mpvPath != null) {
          mpvPathToUriMap.remove(mpvPath)
          mpvPathToOnlineRecordMap.remove(mpvPath)
          mpvPathToEmbyIdentityMap.remove(mpvPath)
          mpvPathToSelectionSourceMap.remove(mpvPath)
        }
        originalUriString?.let(_externalSubtitles::remove)
        onlineRecord?.let { removed ->
          _onlineSubtitles.value = _onlineSubtitles.value.filterNot { it.id == removed.id }
        }
        boundSubtitleApplied = false
        embySubtitleApplied = false
      }
      if (bound != null && bound == originalUriString && titleSnapshot.isNotBlank()) {
        SubtitleBindings.unbind(subtitlesPreferences, titleSnapshot)
      }
    }
  }

  fun toggleSubtitle(id: Int) {
    if (isSubtitleTrackIgnored(id)) return
    // 用户手动开关字幕 ⇒ 这就是「本次播放的字幕决定」，自动挂载不得再覆盖。
    // 尤其是「关掉主字幕」这一下：不压制的话，晚到的 Emby 下载会把字幕又打开。
    markUserSubtitleAction()
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()

    when {
      id == primarySid -> {
        // User disabled the primary subtitle while a secondary subtitle is active:
        // Promote secondary to primary so the single remaining subtitle is positioned at the bottom.
        // MUST clear secondary-sid FIRST so mpv does not reject assigning it to sid.
        if (secondarySid > 0 && secondarySid != id && !isSubtitleTrackIgnored(secondarySid)) {
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
   * 把某条轨道切成**主字幕**（`sid`）。
   *
   * 与 [toggleSubtitle] 分工不同：那个是「点一下开 / 关」，会在主副两个槽位之间轮转；
   * 这个是「我就要这一条当主字幕」—— 字幕时间线里的「设为主字幕」用它。
   *
   * 若这条正占着副字幕槽位（`secondary-sid`），必须先清槽再设 `sid`：
   * mpv 不允许同一条轨同时占两个槽位，顺序反了会被服务端拒绝（同 [removeSubtitle]）。
   */
  fun setPrimarySubtitle(id: Int) {
    if (id <= 0 || isSubtitleTrackIgnored(id)) return
    // 用户手动指定 ⇒ 这就是本次播放的字幕决定，自动挂载不得再覆盖
    markUserSubtitleAction()
    if (getSecondarySubtitleId() == id) {
      PlayerLib.setPropertyString("secondary-sid", "no")
    }
    PlayerLib.setPropertyInt("sid", id)
  }

  /**
   * Ensures that if only one subtitle track is active, it is always the primary
   * subtitle (`sid`), which is positioned at the bottom of the screen.
   */
  fun normalizeSubtitles() {
    val primarySid = getPrimarySubtitleId()
    val secondarySid = getSecondarySubtitleId()
    if (primarySid <= 0 && secondarySid > 0 && !isSubtitleTrackIgnored(secondarySid)) {
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
    SubtitleFileTypes.isSubtitle(fileName)

  // ==================== Playback Control ====================

  fun pauseUnpause() {
    viewModelScope.launch(Dispatchers.IO) {
      setPaused(PlayerLib.getPropertyBoolean("pause") != true)
    }
  }

  fun pause() = setPaused(true)

  fun unpause() = setPaused(false)

  /**
   * 请求「暂停 / 恢复」，**不做任何 JNI 调用**，只把一个意图塞进单槽通道，
   * 由 [init] 里那条串行协程按顺序落到 mpv 上。
   *
   * 为什么不让调用方各自起协程去写：同一个文件上「暂停 → 恢复」两次写入若并发，
   * 落地顺序是不确定的，会出现「最后一次意图是恢复，但最终状态是暂停」。
   * 单槽 StateFlow 保证**最后一次意图胜出**，collect 串行保证**不会交错**。
   */
  private fun setPaused(wantPaused: Boolean) {
    _pauseIntent.value = wantPaused
  }

  /**
   * 开始 seek：若当前正在播放，先暂停（拖动时解码器不必追着跳变的位置跑）。
   *
   * ## 为什么不用「记下 paused 再恢复」那套
   *
   * 以前是在调用点 `wasPlayerAlreadyPaused = paused ?: false`。`paused` 是
   * `propBoolean["pause"]` 的 StateFlow 快照，**异步**更新：上一次 seek 的恢复
   * 还没落地时它仍然是 `true` ⇒ 这次被判成「用户本来就在暂停」⇒ 松手后不恢复。
   * 连续拖动两次必现，表现就是「拖一下就永久暂停」。
   *
   * 现在改成**自己记账**（[seekPauseApplied]）：只有「我们亲手打上的暂停」才由我们
   * 撤销，用户自己按的暂停永远不动。判断当前是否已暂停时先看通道里有没有未落地的
   * 意图，没有再同步读一次 native（一次调用，可接受；避免快照滞后）。
   */
  fun beginSeekPause() {
    if (seekPauseApplied) return
    val alreadyPaused = _pauseIntent.value ?: (PlayerLib.getPropertyBoolean("pause") ?: false)
    if (alreadyPaused) return
    seekPauseApplied = true
    setPaused(true)
    armSeekPauseSafetyNet()
  }

  /**
   * 结束 seek：只有当暂停是 [beginSeekPause] 打上的，才恢复播放。
   *
   * 幂等 —— 进度条有三条手势路径（外层 tap / 外层 drag / 内层 Slider）都会调
   * `onValueChangeFinished`，同一次拖动可能进来两次；第二次在这里直接 no-op，
   * 不会把「用户自己暂停的播放」误恢复。
   */
  fun endSeekPause() {
    if (!seekPauseApplied) return
    seekPauseApplied = false
    seekPauseSafetyJob?.cancel()
    seekPauseSafetyJob = null
    setPaused(false)
  }

  /**
   * 给安全阀续期。拖动期间 [seekTo] 每帧都会调，所以只要手还在屏幕上就不会到期。
   */
  private fun armSeekPauseSafetyNet() {
    seekPauseSafetyJob?.cancel()
    seekPauseSafetyJob =
      viewModelScope.launch {
        delay(SEEK_PAUSE_SAFETY_MS)
        // 到点了还挂着 ⇒ 收尾回调一定被吞掉了（手指被父容器抢走、进度条随控制条
        // 一起被移除、返回手势打断…）。这时宁可多恢复一次播放，也不能让用户
        // 对着一个静止的画面到处找播放键。
        if (seekPauseApplied) {
          seekPauseApplied = false
          setPaused(false)
          Log.w(TAG, "seek-pause safety net fired")
        }
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

  // ── 拖动节流（v217）──
  // 一条在途的绝对 seek（单飞：新的取消旧的，避免乱序落地）
  private var scrubSeekJob: Job? = null
  // 上次真正下发拖动 seek 的时刻（毫秒）
  private var lastScrubCommitAt = 0L
  // 拖动的最新目标（手指当前指向的位置），松手时用它保证最终落点
  private var lastScrubTarget = -1
  // 最近一次已经发给 mpv 的拖动目标（避免收尾重复下发同一位置）
  private var lastDispatchedScrub = -1

  fun seekBy(offset: Int) {
    coalesceSeek(offset)
  }

  /**
   * 跳到某个绝对位置（秒）。
   *
   * [exact] = true 时**强制帧精确**（`absolute+exact`），不跟随全局 `hr-seek` 设置。
   * 用于「点某一句台词跳到那里」这类**落点有明确语义**的跳转：默认的
   * `absolute+keyframes` 会落在目标之前最近的关键帧上，长 GOP 片源能差好几秒，
   * 表现就是「时间线写着 2:03、点下去落在 1:56」。进度条拖动不需要它
   * （那里图的是跟手，帧精确反而更慢）。
   */
  fun seekTo(
    position: Int,
    exact: Boolean = false,
  ) {
    renewSeekPauseSafetyNet()
    dispatchAbsoluteSeek(position, exact)
  }

  /**
   * 拖动进度条期间的 seek —— **每一帧移动都会进来，必须节流**（v217）。
   *
   * 为什么不能像 [seekTo] 那样来一次发一次：一次两秒的拖动会打出 60~120 条
   * `seek` 命令，而网络流上每条 seek 的代价是「丢掉已缓存区间 → HTTP Range
   * 重连 → 重新定位 → 从关键帧解码」，新命令还会掐掉没跑完的上一条 —— 整个
   * 过程被反复推倒重来，缓存被反复清空，观感就是「拖一下卡好久」。视界流的
   * `previewSeek` 早就用 120ms 节流解决过同一个问题（注释原话：「不至于每个
   * 移动事件都让解码器重新定位一次」），这里照同款口径处理。
   *
   * 这里只更新目标并按 [SCRUB_SEEK_INTERVAL_MS] 间隔才真正下发；**最终落点由
   * [commitScrubSeek] 在松手时保证**。老实现里收尾回调不发 seek、完全依赖
   * 「最后一条 onValueChange 恰好是最终位置」—— 并发协程下顺序不保证，
   * 停错位置就是这么来的。
   */
  fun scrubTo(position: Int) {
    renewSeekPauseSafetyNet()
    lastScrubTarget = position
    val now = System.currentTimeMillis()
    if (now - lastScrubCommitAt < SCRUB_SEEK_INTERVAL_MS) return
    lastScrubCommitAt = now
    lastDispatchedScrub = position
    dispatchAbsoluteSeek(position)
  }

  /**
   * 松手（或手势收尾）时保证落在最终目标上：节流窗口没到也强制下发一次。
   * 与 [endSeekPause] 一样幂等 —— 没拖过（`lastScrubTarget` 还是 -1）时是 no-op。
   */
  fun commitScrubSeek() {
    val target = lastScrubTarget
    lastScrubTarget = -1
    lastScrubCommitAt = 0L
    if (target < 0) return
    // 刚下发过同一个目标（且还在途）：别重复发，mpv 的 seek 队列里它就是最新一条
    if (target == lastDispatchedScrub) return
    lastDispatchedScrub = target
    dispatchAbsoluteSeek(target)
  }

  /**
   * 手还在屏幕上：给 seek 暂停的安全阀续期（最多一秒续一次，别每帧都重建协程）。
   */
  private fun renewSeekPauseSafetyNet() {
    if (seekPauseApplied) {
      val now = System.currentTimeMillis()
      if (now - seekPauseRenewedAt > SEEK_PAUSE_RENEW_INTERVAL_MS) {
        seekPauseRenewedAt = now
        armSeekPauseSafetyNet()
      }
    }
  }

  /**
   * 真正把绝对定位发给 mpv（IO 线程，单飞：新 seek 取消旧的）。
   * [seekTo]（跳章节、跳过片头这类单点跳转）与拖动节流共用这一条路。
   */
  private fun dispatchAbsoluteSeek(
    position: Int,
    exact: Boolean = false,
  ) {
    scrubSeekJob?.cancel()
    scrubSeekJob =
      viewModelScope.launch(Dispatchers.IO) {
        // ⚠️⚠️ 时长**读不到**时绝不能当成 0（v259，用户报「频繁拖动进度条然后返回，详情页变 0%」）。
        //
        // 老写法是 `PlayerLib.getPropertyInt("duration") ?: 0`，看起来只是个兜底，实际是一条
        // 把续播点清零的流水线：
        //   `getPropertyInt` 在内核未就绪、或 mpv 正为上一次 seek 重建解复用器时返回 null
        //   ⇒ 时长变成 0 ⇒ `position.coerceIn(0, 0)` 把目标夹成 0
        //   ⇒ 真的 seek 到开头，并且把 0 记成「用户要跳到这里」（PlayerSeekTracker）
        //   ⇒ 此后「读到 0 就不许上报」的两道闸门全部失效，而位置校正又把任何读数都校正成 0
        //   ⇒ Progress / Stopped / 本地续播三条写入同时变 0。
        // 「频繁拖动」正是最容易撞上的场景：每 150ms 就下发一次，每次都现读一次时长，
        // 而这段时间里 mpv 一半以上时间在重开 demuxer。
        //
        // 所以：时长拿不到就**只夹下界**，不猜上界；拿得到也优先用 [preciseDuration] ——
        // 它已经做过「mpv 报 0 / 报小 / 报大」的三重甄别（见那里的注释），进度条画的也是它。
        // 老写法读裸 mpv 值，于是 `.mts` 这类「mpv 时长报成 8 秒」的文件上，进度条最大 40 分钟、
        // 拖到哪都被夹回 8 秒 —— 那本身也是「拖完进度条位置乱跳」的另一个来源。
        val knownDuration =
          preciseDuration.value.toInt().takeIf { it > 0 }
            ?: PlayerLib.getPropertyInt("duration")?.takeIf { it > 0 }
        var clampedPosition =
          if (knownDuration != null) position.coerceIn(0, knownDuration) else position.coerceAtLeast(0)

        // Clamp within AB loop if active
        val loopA = _abLoopA.value
        val loopB = _abLoopB.value
        if (loopA != null && loopB != null) {
          val min = minOf(loopA.toInt(), loopB.toInt())
          val max = maxOf(loopA.toInt(), loopB.toInt())
          clampedPosition = clampedPosition.coerceIn(min, max)
        }

        if (knownDuration != null && clampedPosition !in 0..knownDuration) return@launch

        // Cancel pending relative seek before absolute seek
        seekCoalesceJob?.cancel()
        pendingSeekOffset = 0

        // Use precise seeking for videos shorter than 2 minutes (120 seconds) or if preference is enabled.
        // [exact] 优先于一切：点台词这种「落点必须就是那一句」的跳转不能被 keyframes 降级。
        val shouldUsePreciseSeeking =
          exact || playerPreferences.usePreciseSeeking.get() || (knownDuration ?: 0) < 120
        val seekMode = if (shouldUsePreciseSeeking) "absolute+exact" else "absolute+keyframes"
        // ⚠️⚠️ 记账**必须紧贴命令之前**：这次跳转的目标是「seek 真的落地之前，位置读数
        //   不能代表用户在哪儿」的唯一凭据（详见 [PlayerSeekTracker]）。
        //   漏了这一行，退出结算就会采信 mpv 在 seek 途中给出的 0 / 旧值 ——
        //   用户看到的就是「拖完进度条马上返回，详情页进度变成 0%」。
        PlayerSeekTracker.noteAbsoluteSeek(clampedPosition)
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
          // 时长 / 位置**读不到就是读不到**：一律不折成 0（v259）。
          // 折成 0 会让「双击快进 10 秒」被记成「用户要跳到第 10 秒」，比不记目标更糟。
          val duration =
            preciseDuration.value.toInt().takeIf { it > 0 }
              ?: PlayerLib.getPropertyInt("duration")?.takeIf { it > 0 }
              ?: 0
          val currentPos = PlayerLib.getPropertyInt("time-pos") ?: pos?.toInt()

          if (duration > 0 && currentPos != null && currentPos + toApply >= duration) {
              // If seeking past the end, force seek to 100% absolute to ensure EOF is triggered
              PlayerSeekTracker.noteAbsoluteSeek(duration)
              PlayerLib.command("seek", "100", "absolute-percent+exact")
          } else {
              // Use precise seeking for videos shorter than 2 minutes (120 seconds) or if preference is enabled
              val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || duration < 120
              val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
              // 相对 seek 也要换算出**绝对落点**记账：双击快进/快退之后立刻退出，
              // 退出结算同样读不到可靠位置（理由见 [PlayerSeekTracker]）。
              // 只有读到当前位时才记 —— 读不到就没有落点，宁可不记也不要记个假的。
              if (currentPos != null) {
                val target = if (duration > 0) {
                  (currentPos + toApply).coerceIn(0, duration)
                } else {
                  (currentPos + toApply).coerceAtLeast(0)
                }
                PlayerSeekTracker.noteAbsoluteSeek(target)
              }
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
    // 按剧再记一份（对标 Yamby 的 resume_brightness）。
    // ⚠️ 和全局记忆是**叠加**关系不是二选一：用户可能只想全局固定一个亮度、
    //   不想某部片单独记住，所以两个开关各自独立判断。
    // ⚠️ seriesKey 为 null（本地播放 / 网盘）时不写 —— 没有稳定的剧标识可挂。
    if (playerPreferences.rememberSeriesBrightness.get()) {
      playerPreferences.setSeriesBrightness(seriesKey, coercedBrightness)
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

  // ==================== 媒体键（硬件按键 / 耳机线控 / 车机） ====================

  /**
   * 上一曲键（MEDIA_PREVIOUS）。
   *
   * 这三个偏好项（[GesturePreferences.mediaPreviousGesture] 等）以前只在设置页有 UI、
   * 没有任何代码读取 —— 这里补上接线，映射规则与双击手势完全一致：
   * Seek = 后退 / 前进、PlayPause = 播放暂停、Custom = 透传给 input.conf 自定义键、None = 不响应。
   */
  fun handleMediaPrevious() {
    when (gesturePreferences.mediaPreviousGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
      }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPlayPause() {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
      }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleMediaNext() {
    when (gesturePreferences.mediaNextGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom -> viewModelScope.launch(Dispatchers.IO) {
        PlayerLib.command("keypress", CustomKeyCodes.MediaNext.keyCode)
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
            Toast.makeText(context, "截图失败", Toast.LENGTH_SHORT).show()
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
          Toast.makeText(context, "保存截图失败：${e.message}", Toast.LENGTH_LONG).show()
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
        .path(EmbyPaths.PREFIX + EmbyPaths.itemImage(itemId, "Backdrop"))
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
    playerUpdate.value = PlayerUpdates.ShowText(if (newMirrorState) "水平翻转：开" else "水平翻转：关")
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

    playerUpdate.value = PlayerUpdates.ShowText(if (newState) "垂直翻转：开" else "垂直翻转：关")
  }

  // ==================== 画面旋转（顺时针 90° 步进） ====================

  /**
   * 顺时针转 90°。
   *
   * 四次一循环：第 4 次点击回到 0°，此时 `video-rotate` 被写回 0（而不是留一个
   * 360° 的等价旋转），所以「转四次」之后画面与初始状态像素级一致。
   *
   * 直出模式（`vo=mediacodec_embed`）沿用原有 UI 限制：这里**不改状态、只提示**。
   * 静默失败比一句解释糟得多 —— 用户会以为按钮坏了。
   */
  fun rotateScreenCw() {
    if (decoderPreferences.isDirectOutput()) {
      playerUpdate.value =
        PlayerUpdates.ShowText(host.context.getString(R.string.player_rotate_unavailable_direct))
      return
    }

    val next = _rotation.value.next()
    _rotation.value = next
    VideoRotate.apply(next.quarterTurns)
    // 宽高互换后，「按视频比例」进入的横/竖屏判断要重算一次（幂等，不触发重建 vo）
    host.onVideoRotationChanged()

    playerUpdate.value =
      if (next.isRotated) {
        PlayerUpdates.ShowText(host.context.getString(R.string.player_rotate_degrees, next.degrees))
      } else {
        PlayerUpdates.ShowText(host.context.getString(R.string.player_rotate_reset))
      }
  }

  /**
   * 把画面旋转复位到 0°（`video-rotate` 写回 0）。
   *
   * 用于「用户主动还原」以及内核 / 播放会话切换前的清理 —— mpv 内核 destroy 后
   * 属性会回到默认，但 ViewModel 属于 Activity，后台播放路径下会被复用，
   * 只清内核不清内存状态就会出现「按钮显示转了 90°，画面却是正的」。
   */
  fun resetScreenRotation(showUpdate: Boolean = false) {
    _rotation.value = RotationState.Identity
    VideoRotate.clear()
    if (showUpdate) {
      playerUpdate.value =
        PlayerUpdates.ShowText(host.context.getString(R.string.player_rotate_reset))
    }
  }

  /**
   * 每次 `file-loaded` 后调用（与 [applyAutoCropPolicy] 同位置）。
   *
   * 设计取舍：**换片不主动清零**。同一个目录 / 同一部剧的连播里，拍摄方向通常一致，
   * 每切一集都要重新转三次是灾难。`video-rotate` 是 mpv 全局属性，同内核换片自动保留。
   *
   * 唯一的动作是**以内核为准校正内存状态**：外部脚本改过属性、或上一次清理被打断时，
   * 内存与内核会不一致，这时以读回的真实值为准，而不是信内存。
   */
  fun applyRotationPolicy() {
    val actualDegrees = VideoRotate.readAppliedDegrees()
    if (actualDegrees == _rotation.value.degrees) return
    // 无论内核里是什么，都以内核为准对齐内存状态。读不到（0°）就归零，
    // 否则会出现「按钮亮着但画面是正的」这种两边不一致。
    _rotation.value = RotationState.ofDegrees(actualDegrees)
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

  /** 当前录制任务；用来实现「取消录制」。 */
  private var gifJob: Job? = null

  /**
   * 录制一段 GIF 并存入相册。
   *
   * 整个流程（取帧 → 编码 → 入库）都在协程里跑，UI 只订阅 [gifProgress]。
   * 重复点击会被忽略（[gifProgress] 非 null 时直接返回），避免两次录制互相抢帧。
   *
   * 开录前若视频是暂停的会**自动继续播放**：取帧是跟着画面实时走的，暂停状态下
   * 每一帧都长得一样，录出来是一张静图。
   */
  fun startGifRecording(durationSec: Int) {
    if (_gifProgress.value != null) return
    if (PlayerLib.getPropertyBoolean("pause") == true) {
      // 走和 unpause() 一样的路径（申请音频焦点 + 置 pause=no），这里同步执行，
      // 保证第一帧取到时画面已经在动了。
      host.requestAudioFocus()
      PlayerLib.setPropertyBoolean("pause", false)
    }
    gifJob = viewModelScope.launch {
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
        gifJob = null
      }
    }
  }

  /**
   * 取消正在进行的录制。
   *
   * 协程被取消后 `finally` 照跑：进度归零、临时帧目录删掉（采集侧自己也会在异常路径
   * 清一遍），所以取消后不会在 cache 里留下一堆截图。
   */
  fun cancelGifRecording() {
    gifJob?.cancel()
    gifJob = null
    _gifProgress.value = null
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
