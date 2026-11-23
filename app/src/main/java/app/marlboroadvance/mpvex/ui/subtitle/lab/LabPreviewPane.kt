package app.marlboroadvance.mpvex.ui.subtitle.lab

import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 实验室要预览的那部片。
 *
 * @property url 直连播放地址（`/Videos/{id}/stream?static=true&api_key=…`，鉴权已含在查询串里）
 * @property startSec 从哪一秒开始（续播位置；没有记录就是 0）
 */
data class LabPreviewSource(
  val url: String,
  val startSec: Double,
  val title: String,
)

/**
 * 正在预览的那条字幕。
 *
 * [recordId] 与 [path] 一起用来判断「是不是换了另一条」—— 换字幕才重新 `sub-add`，
 * 只是调偏移量的话绝不能重挂（会在 `track-list` 里堆出一串重复轨道）。
 */
data class LabPreviewSubtitle(
  val recordId: String,
  val path: String,
  val title: String,
  val offsetMs: Long,
)

/**
 * 一次「跳到某秒」的请求。
 *
 * [token] 单调递增而不是直接用秒数当 key：用户可能**连续点同一条**字幕（
 * 第一次跳过去发现还是不对，再点一次），若以秒数为 key，第二次不会重新触发
 * `LaunchedEffect`，看起来就是「点了没反应」。
 */
data class LabSeekRequest(
  val token: Long,
  val seconds: Double,
)

/**
 * 字幕实验室顶部的**视频预览窗口**（需求 7.1 / 7.3 / 7.4）。
 *
 * ## 它为什么长在页面顶部而不是弹窗里
 *
 * 需求 7.2 明确「预览位于页面顶部、下方区域可滚动」。所以这里是一块占满宽度的 16:9 画面，
 * 固定在上半部分，下面的搜索 / 已下载列表照常滚动 —— 调偏移量时眼睛不用离开画面。
 *
 * ## 谁负责什么
 *
 * 内核（[LabPreviewEngine]）只管「放画面、挂字幕、延时」；这里只管画控件与把用户动作
 * 转成内核调用。媒体地址、字幕文件路径、偏移量都由调用方（字幕实验室）从本地库取好传进来。
 *
 * ## 内核的生死
 *
 * 只有 [source] 非空才建内核，页面离开 / 点「关闭预览」立刻 `release()` 把 mpv 句柄还回去。
 * 这条比「预览好看」重要：句柄没还，之后进播放页就是黑屏。
 */
@Composable
internal fun LabPreviewPane(
  source: LabPreviewSource?,
  subtitle: LabPreviewSubtitle?,
  /** 是否已经选好影片（决定空态里出不出「开始预览」按钮） */
  canStart: Boolean,
  /** 「跳到某一句」的请求（来自时间线列表点击）；null = 没有待处理的跳转 */
  seekRequest: LabSeekRequest? = null,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onOffsetChange: (Long) -> Unit,
  /** 播放位置回调（秒），250ms 一次。时间线用它做「跟随播放」的高亮与滚动。 */
  onPositionChange: (Double) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val positionListener by rememberUpdatedState(onPositionChange)

  var engine by remember { mutableStateOf<LabPreviewEngine?>(null) }
  var hostView by remember { mutableStateOf<View?>(null) }
  var engineError by remember { mutableStateOf<String?>(null) }
  var preparing by remember { mutableStateOf(false) }
  /** 建内核失败后点「重试」用的尝试计数：engineError 清掉不等于重跑，必须换键。 */
  var attempt by remember { mutableIntStateOf(0) }
  var subtitleApplied by remember { mutableStateOf(false) }
  var subtitleFailed by remember { mutableStateOf(false) }

  var paused by remember { mutableStateOf(true) }
  var position by remember { mutableDoubleStateOf(0.0) }
  var duration by remember { mutableDoubleStateOf(0.0) }
  var volume by remember { mutableIntStateOf(100) }
  var subVisible by remember { mutableStateOf(true) }
  var buffering by remember { mutableStateOf(false) }

  // ① 内核在这一页里**只建一次**，之后一直复用。
  //
  // ⚠️⚠️ 这条是 v314 的关键修复。旧实现把「建内核」绑在 `source?.url` 上：
  // 关预览（source = null）→ release() 销毁 mpv；再点预览 → 新建一次。
  // 而 mpv 是进程级单例，`destroy()` 之后解码 / 音频线程并不会立刻收干净
  // （`quit` 只是投队列），紧接着 create 就撞在正在收尾的句柄上 ⇒ 闪退。
  // 现在的语义：**关闭预览 = 停播**，只有离开整页才 destroy 一次。
  LaunchedEffect(source != null, attempt) {
    if (source == null) {
      engine?.pauseAndStop()
      // 准备过程可能被打断（用户点了关闭）：这里复位，别让占位停在「正在准备」
      preparing = false
      paused = true
      subtitleApplied = false
      subtitleFailed = false
      return@LaunchedEffect
    }
    // 已建好：换片 / 重新打开预览交给 ③（play 内部是 replace 语义，幂等）
    if (engine != null) return@LaunchedEffect

    preparing = true
    engineError = null
    // 脚本 / 字体 / CA 证书必须赶在 initialize 之前就位（都是 setOptionString）
    withContext(Dispatchers.IO) { LabPreviewEngine.prepareAssetsOnIo(context) }
    val created = LabPreviewEngine(context)
    val view = created.ensureHost(LayoutInflater.from(context))
    preparing = false
    if (view == null) {
      engineError = "预览内核初始化失败，可退出重进本页再试"
      return@LaunchedEffect
    }
    engine = created
    hostView = view
    position = source.startSec
  }

  // ② 离开页面一定释放：不 released 的 mpv 句柄会让播放页拿不到内核。
  //    页内反复开关预览**不再**走这里（见 ① 的说明）。
  DisposableEffect(Unit) {
    onDispose {
      engine?.release()
      engine = null
      hostView = null
    }
  }

  // ③ 起播（同一地址重复调用在引擎里是幂等的）
  LaunchedEffect(engine, source?.url) {
    val e = engine ?: return@LaunchedEffect
    val s = source ?: return@LaunchedEffect
    e.setVolume(volume)
    e.play(s.url, s.startSec)
  }

  // ④ 换字幕才重挂。⚠️ 只依赖「是哪一条」，不依赖 offsetMs —— 否则每调一次偏移
  //    就 sub-add 一次，轨道列表里会堆出一串重复项，且越调越慢。
  LaunchedEffect(engine, subtitle?.recordId, subtitle?.path) {
    val e = engine ?: return@LaunchedEffect
    val sub = subtitle ?: return@LaunchedEffect
    subtitleFailed = false
    // 媒体没加载完时 sub-add 会被丢掉（轨道列表还是空的），先等轨道出现
    if (!e.awaitTracks()) {
      subtitleFailed = true
      return@LaunchedEffect
    }
    subtitleApplied = e.attachSubtitle(sub.path, sub.title, sub.offsetMs)
    subtitleFailed = !subtitleApplied
  }

  // ⑤ 偏移量改动 → 只改运行期 sub-delay（需求 8：不修改字幕文件）
  LaunchedEffect(engine, subtitle?.recordId, subtitle?.offsetMs) {
    val e = engine ?: return@LaunchedEffect
    val sub = subtitle ?: return@LaunchedEffect
    if (subtitleApplied) e.setSubtitleDelay(sub.offsetMs)
  }

  // ⑤-b 时间线点击 → 跳到那一刻。
  //    ⚠️ 必须等轨道出现再 seek：文件还没 loadfile 完时发 seek，落点会算在
  //    上一条时间轴上（和 `play()` 里不用 loadfile+seek 是同一个原因）。
  LaunchedEffect(engine, seekRequest?.token) {
    val req = seekRequest ?: return@LaunchedEffect
    val e = engine ?: return@LaunchedEffect
    if (!e.awaitTracks(6_000)) return@LaunchedEffect
    e.setPaused(true)
    e.seekTo(req.seconds)
    position = req.seconds
    // 立刻把落点告诉时间线：等 250ms 心跳的话，用户会看到高亮还停在上一条
    positionListener(req.seconds)
  }

  // ⑥ 进度心跳：250ms 一次。mpv 属性只能在主线程读，所以用 Main 上的 LaunchedEffect。
  LaunchedEffect(engine) {
    val e = engine ?: return@LaunchedEffect
    while (true) {
      val current = e.positionSec()
      position = current
      positionListener(current)
      duration = e.durationSec()
      paused = e.isPaused()
      buffering = e.isBuffering() || (duration <= 0.0 && !e.hasPicture())
      delay(250)
    }
  }

  Column(modifier = modifier.fillMaxWidth().background(Color.Black)) {
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
      contentAlignment = Alignment.Center,
    ) {
      // 画面层一旦建好就一直留在组合里：关预览只是被下面的占位盖住，
      // 不做「移除 → 重建 Surface」（那会连带把 mpv 的输出面拆一次）。
      if (hostView != null) {
        // InertTouchFrameLayout 自己不接触摸，叠在上面的控件才点得动
        key(hostView) {
          AndroidView(
            factory = { hostView ?: View(context) },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      if (source == null || hostView == null) {
        // 不透明占位：盖住上一帧，也盖住「关预览后残留的画面」
        Box(
          modifier = Modifier.matchParentSize().background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          PlaceholderBody(
            error = engineError,
            preparing = preparing,
            canStart = canStart,
            onStart = onStart,
            onRetry = {
              engineError = null
              attempt += 1
            },
          )
        }
      }

      // 顶栏：片名 + 关闭预览
      if (source != null) {
        Row(
          modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = source.title.ifBlank { "预览" },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
            Icon(
              Icons.Default.Close,
              contentDescription = "关闭预览",
              tint = Color.White,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      if (engine != null && source != null && buffering) {
        CircularProgressIndicator(
          modifier = Modifier.size(26.dp),
          strokeWidth = 2.dp,
          color = Color.White.copy(alpha = 0.85f),
        )
      }

      // 只在预览真的开着时提示挂载失败：关掉预览后这条文案会浮在占位层上，
      // 让人以为是「刚才那次失败还没处理」。
      if (subtitleFailed && source != null) {
        Text(
          text = "这条字幕未能挂载，可能是格式不受支持",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.9f),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 6.dp),
        )
      }
    }

    // 控制条只在「真的在预览」时出现：关掉预览后还留一条没画面的进度条，
    // 看起来就像卡住了。
    if (engine != null && source != null) {
      PreviewControls(
        paused = paused,
        position = position,
        duration = duration,
        volume = volume,
        subVisible = subVisible,
        hasSubtitle = subtitle != null,
        offsetMs = subtitle?.offsetMs,
        onTogglePause = {
          val next = !paused
          engine?.setPaused(next)
          paused = next
        },
        onSkip = { delta -> engine?.seekTo(position + delta) },
        onSeek = { target ->
          engine?.seekTo(target)
          position = target
        },
        onVolume = { value ->
          volume = value
          engine?.setVolume(value)
        },
        onToggleSubtitle = {
          val next = !subVisible
          subVisible = next
          engine?.setSubtitleVisible(next)
        },
        onOffsetChange = onOffsetChange,
      )
    }
  }
}

@Composable
private fun PlaceholderBody(
  error: String?,
  preparing: Boolean,
  canStart: Boolean,
  onStart: () -> Unit,
  onRetry: () -> Unit,
) {
  Column(
    modifier = Modifier.padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when {
      error != null -> {
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFFFFB4AB),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 36.dp)) {
          Text("重试预览", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
      }

      preparing -> {
        Text(
          text = "正在准备预览…",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.9f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          modifier = Modifier.fillMaxWidth(0.5f),
          color = Color.White.copy(alpha = 0.85f),
          trackColor = Color.White.copy(alpha = 0.18f),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "首次预览需要准备字体与解码资源，之后会快很多",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.5f),
        )
      }

      canStart -> {
        Text(
          text = "点「开始预览」播放画面",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "预览用的是与正式播放器相同的 mpv 内核，字幕样式完全一致",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 36.dp)) {
          Text("开始预览", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
      }

      else -> {
        Text(
          text = "先选一部影片",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "选片后这里会播放画面，用来确认字幕的时间轴对不对",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.5f),
        )
      }
    }
  }
}

@Composable
private fun PreviewControls(
  paused: Boolean,
  position: Double,
  duration: Double,
  volume: Int,
  subVisible: Boolean,
  hasSubtitle: Boolean,
  /** 当前字幕的偏移量；null = 没有挂字幕，不显示偏移档。 */
  offsetMs: Long?,
  onTogglePause: () -> Unit,
  onSkip: (Double) -> Unit,
  onSeek: (Double) -> Unit,
  onVolume: (Int) -> Unit,
  onToggleSubtitle: () -> Unit,
  onOffsetChange: (Long) -> Unit,
) {
  // 点喇叭图标静音 / 恢复：记住静音前的音量，恢复时不会突然变回 100。
  var lastAudible by remember { mutableIntStateOf(if (volume > 0) volume else 80) }
  val muted = volume <= 0

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xFF101012))
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    // ── 进度 ──
    // 时间分列两端、进度条占满中间：读数与滑块同一条基线，扫一眼就知道「现在到哪」。
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = formatClock(position),
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = Color.White.copy(alpha = 0.92f),
      )
      SlimSlider(
        fraction = if (duration > 0) (position / duration).toFloat() else 0f,
        enabled = duration > 0,
        onSeek = { fraction -> if (duration > 0) onSeek(fraction * duration) },
        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
      )
      Text(
        text = formatClock(duration),
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = Color.White.copy(alpha = 0.5f),
      )
    }

    Spacer(modifier = Modifier.height(2.dp))

    // ── 播放 / 音量 / 字幕 ──
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { onSkip(-10.0) }, modifier = Modifier.size(34.dp)) {
        Icon(
          Icons.Default.FastRewind,
          contentDescription = "后退 10 秒",
          tint = Color.White.copy(alpha = 0.86f),
          modifier = Modifier.size(19.dp),
        )
      }
      // 主按钮：实心白圆 + 深色图标，是整块面板唯一的强对比焦点
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(Color.White),
        contentAlignment = Alignment.Center,
      ) {
        IconButton(onClick = onTogglePause, modifier = Modifier.size(42.dp)) {
          Icon(
            imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (paused) "播放" else "暂停",
            tint = Color(0xFF121214),
            modifier = Modifier.size(22.dp),
          )
        }
      }
      IconButton(onClick = { onSkip(10.0) }, modifier = Modifier.size(34.dp)) {
        Icon(
          Icons.Default.FastForward,
          contentDescription = "前进 10 秒",
          tint = Color.White.copy(alpha = 0.86f),
          modifier = Modifier.size(19.dp),
        )
      }

      Spacer(modifier = Modifier.weight(1f))

      IconButton(
        onClick = {
          if (muted) {
            onVolume(lastAudible.coerceIn(1, 100))
          } else {
            lastAudible = volume
            onVolume(0)
          }
        },
        modifier = Modifier.size(30.dp),
      ) {
        Icon(
          if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
          contentDescription = if (muted) "取消静音" else "静音",
          tint = Color.White.copy(alpha = if (muted) 0.42f else 0.78f),
          modifier = Modifier.size(17.dp),
        )
      }
      SlimSlider(
        fraction = volume / 100f,
        onSeek = { onVolume((it * 100f).roundToInt().coerceIn(0, 100)) },
        modifier = Modifier.width(76.dp),
        trackHeight = 2.5.dp,
        thumbRadius = 4.dp,
        accent = Color.White.copy(alpha = 0.82f),
      )
      Text(
        text = "$volume%",
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.width(34.dp),
        textAlign = TextAlign.End,
      )

      if (hasSubtitle) {
        IconButton(onClick = onToggleSubtitle, modifier = Modifier.size(32.dp)) {
          Icon(
            Icons.Default.Subtitles,
            contentDescription = if (subVisible) "隐藏字幕" else "显示字幕",
            tint = if (subVisible) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }

    // ── 偏移快捷档 ──
    // 与「已下载字幕」卡片是同一组动作；放在这里是为了边看画面边拧。
    if (offsetMs != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 6.dp)
          .height(1.dp)
          .background(Color.White.copy(alpha = 0.07f)),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "偏移",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.5f),
        )
        listOf(-500L to "-500", -100L to "-100", 100L to "+100", 500L to "+500").forEach { (delta, label) ->
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(999.dp))
              .background(Color.White.copy(alpha = 0.08f))
              .clickable { onOffsetChange(delta) }
              .padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
              color = Color.White.copy(alpha = 0.9f),
            )
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = formatOffsetMs(offsetMs),
          style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
          color = Color(0xFFFFC46B),
        )
      }
    }
  }
}

/**
 * 细轨道滑块：进度与音量共用。
 *
 * 为什么不用 `Slider`：Material3 的滑块自带 48dp 高度与 20dp 圆点，塞进预览窗格会
 * 把控制条撑成两条厚横带。这里画 3dp 轨道 + 10dp 圆点，按下时圆点放大，
 * 拖动过程中**只在松手时**才真正 seek —— 逐帧 seek 会把 mpv 拖到卡顿。
 */
@Composable
private fun SlimSlider(
  fraction: Float,
  onSeek: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  trackHeight: Dp = 3.dp,
  thumbRadius: Dp = 5.dp,
  accent: Color = Color.White,
) {
  var dragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableFloatStateOf(0f) }
  val shown = (if (dragging) dragFraction else fraction).coerceIn(0f, 1f)

  Canvas(
    modifier = modifier
      .height(24.dp)
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          val width = size.width.toFloat()
          if (width <= 0f) return@awaitEachGesture
          val down = awaitFirstDown(requireUnconsumed = false)
          dragging = true
          dragFraction = (down.position.x / width).coerceIn(0f, 1f)
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            dragFraction = (change.position.x / width).coerceIn(0f, 1f)
            change.consume()
          }
          dragging = false
          onSeek(dragFraction)
        }
      },
  ) {
    val centerY = size.height / 2f
    val track = trackHeight.toPx()
    val dot = if (dragging) thumbRadius.toPx() * 1.6f else thumbRadius.toPx()
    val progressX = (size.width * shown).coerceIn(0f, size.width)

    drawRoundRect(
      color = accent.copy(alpha = if (enabled) 0.2f else 0.1f),
      topLeft = Offset(0f, centerY - track / 2f),
      size = Size(size.width, track),
      cornerRadius = CornerRadius(track / 2f),
    )
    drawRoundRect(
      color = accent.copy(alpha = if (enabled) 0.95f else 0.3f),
      topLeft = Offset(0f, centerY - track / 2f),
      size = Size(progressX, track),
      cornerRadius = CornerRadius(track / 2f),
    )
    // 圆点在两端时别被裁掉一半：内缩到半径范围内
    drawCircle(
      color = accent,
      radius = dot,
      center = Offset(progressX.coerceIn(dot, (size.width - dot).coerceAtLeast(dot)), centerY),
    )
  }
}

private fun formatClock(sec: Double): String {
  if (sec <= 0.0 || !sec.isFinite()) return "00:00"
  val total = sec.toLong()
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) {
    String.format(Locale.US, "%d:%02d:%02d", h, m, s)
  } else {
    String.format(Locale.US, "%02d:%02d", m, s)
  }
}

private fun formatOffsetMs(ms: Long): String =
  if (ms == 0L) "未校准" else String.format(Locale.US, "%+d ms", ms)
