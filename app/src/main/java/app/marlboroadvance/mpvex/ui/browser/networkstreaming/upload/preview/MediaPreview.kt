package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album.AlbumImageLoader
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 预览器的三种内容，各有一个专门实现：
 * [Image] 交给 Coil 解码、[Video] 用 `VideoView`、[Audio] 用 `MediaPlayer`。
 *
 * ⚠️ **没有「其它」这一档**：pdf / txt / 字幕 / 压缩包之类根本没有预览器，
 * 硬塞进某一档只会得到 `Failed to create image decoder` 这类红字。
 * 判别入口是 [previewKindOf]，返回 null 的格式**不提供预览入口**。
 */
enum class PreviewKind { Image, Video, Audio }

/**
 * 预览器里的一项。
 *
 * [kind] 是**显式标记**而不是「按 URI 猜」：同一条 `content://media/external/…`
 * 在相册里既可能是图也可能是视频，猜错的代价是「Failed to create image decoder」——
 * 用户看到的是一个红字错误，而不是预览。
 *
 * @param uri null = 拿不到本地源（只能显示「无法预览」）
 * @param title 显示在底部标题行，也是翻页时唯一的「这是哪一个」的线索
 */
data class PreviewItem(val uri: Uri?, val kind: PreviewKind, val title: String)

/**
 * 预览器的内容 + 当前页。
 *
 * 为什么是一「叠」而不是单个：用户点开一张图往往要看前后几张（这组是不是完整、
 * 下一个视频是不是那一集）。所以预览器自带翻页，**只在当前上下文里翻** ——
 * 选择器翻当前筛选出的网格，进度页翻这一批任务，分享页翻这次分享进来的那一批。
 *
 * ⚠️ 构造时必须**只放能预览的项**（[previewKindOf] 非 null），否则翻页会停在一个
 * 什么都没有的屏上。
 */
data class PreviewDeck(val items: List<PreviewItem>, val index: Int)

/** 图片扩展名。**只用于判断「有没有预览器」**，不参与 MIME 判定 */
private val PREVIEW_IMAGE_EXTENSIONS =
  setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "tif", "tiff")

/**
 * 名字（可带 MIME）→ 该用哪个预览器；**null = 这类格式没有预览器**。
 *
 * 这是「用什么预览」的唯一判定入口：分享页、选择器、进度页三处都调它，
 * 免得某处「不是视频就当图片」（pdf 就是这么走进图片解码器的）。
 *
 * 顺序：扩展名比 MIME 可靠（分享进来的 `content://` 常常报
 * `application/octet-stream`），所以先看扩展名，再看 MIME 兜底。
 */
fun previewKindOf(name: String, mime: String? = null): PreviewKind? {
  val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
  return when {
    ext.isNotEmpty() && FileTypeUtils.isVideoExtension(ext) -> PreviewKind.Video
    ext.isNotEmpty() && FileTypeUtils.isAudioExtension(ext) -> PreviewKind.Audio
    ext.isNotEmpty() && ext in PREVIEW_IMAGE_EXTENSIONS -> PreviewKind.Image
    mime?.startsWith("video/") == true -> PreviewKind.Video
    mime?.startsWith("audio/") == true -> PreviewKind.Audio
    mime?.startsWith("image/") == true -> PreviewKind.Image
    else -> null
  }
}

/** 预览器的三种内容共用一套外壳：黑底卡片 + 底部翻页/标题/关闭 */
@Composable
fun MediaPreviewDialog(deck: PreviewDeck, onDismiss: () -> Unit) {
  // 页码跟着 deck 走：从别的行点开是「另一叠」，必须回到它自己的起点
  var index by remember(deck) { mutableIntStateOf(deck.index) }
  val item = deck.items.getOrNull(index) ?: return
  val total = deck.items.size

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.92f),
      shape = RoundedCornerShape(16.dp),
      color = Color.Black,
      tonalElevation = 6.dp,
    ) {
      Column {
        Box(
          modifier =
            Modifier.fillMaxWidth().then(
              when (item.kind) {
                // 视频**自己不锁高度**：画面 16:9 由 VideoPreview 内部固定，
                // 底下还有一条固定高度的控件栏 —— 在本层再套 aspectRatio 会把
                // 控件栏挤到框外（或者压成一条线），这正是「控件显示不全」的老毛病。
                PreviewKind.Video -> Modifier
                // 音频是一块固定高度的「封面 + 控件」：高度随封面变会让控件条上下跳
                PreviewKind.Audio -> Modifier.height(240.dp)
                // 图片不锁比例：手机竖拍塞进 16:9 只剩中间一条，看不出是什么
                PreviewKind.Image -> Modifier.heightIn(min = 180.dp, max = 440.dp)
              },
            ),
          contentAlignment = Alignment.Center,
        ) {
          PreviewContent(item = item)
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (total > 1) {
            val canPrev = index > 0
            IconButton(onClick = { if (canPrev) index-- }, enabled = canPrev) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一个",
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = if (canPrev) 0.95f else 0.25f),
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = item.title,
              style = MaterialTheme.typography.labelMedium,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (total > 1) {
              Text(
                text = "${index + 1} / $total",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
              )
            }
          }
          if (total > 1) {
            val canNext = index < total - 1
            IconButton(onClick = { if (canNext) index++ }, enabled = canNext) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一个",
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = if (canNext) 0.95f else 0.25f),
              )
            }
          }
          TextButton(onClick = onDismiss) { Text(text = "关闭", color = Color.White) }
        }
      }
    }
  }
}

/**
 * 预览器里的一屏内容：图片解码 / 视频抽帧播放 / 音频出声。
 *
 * ⚠️ 三者必须分开实现，不能「都交给 AsyncImage」：音频没有画面，
 * Coil 拿到 `content://…/audio/…` 只会抛 `Failed to create image decoder`，
 * 用户看到的就是那条红字错误（v187 修的就是这个）。
 */
@Composable
private fun PreviewContent(item: PreviewItem) {
  val uri = item.uri
  if (uri == null) {
    Text(text = "无法预览", style = MaterialTheme.typography.bodySmall, color = Color.White)
    return
  }
  when (item.kind) {
    // key(uri)：AndroidView / DisposableEffect 的 factory 只跑一次，
    // 不给 key 时翻到下一项仍在播上一项
    PreviewKind.Video -> key(uri) { VideoPreview(uri = uri) }
    PreviewKind.Audio -> key(uri) { AudioPreview(uri = uri) }
    PreviewKind.Image -> ImagePreview(uri = uri, title = item.title)
  }
}

/**
 * 图片这一屏。
 *
 * 必须自己回填加载态：黑底上「还没解码完」和「解码失败」都是黑屏，
 * 不给转圈和失败文案，用户只能猜是不是卡死了。
 */
@Composable
private fun ImagePreview(uri: Uri, title: String) {
  val context = LocalContext.current
  val loader = remember(context) { AlbumImageLoader.get(context) }
  var ready by remember(uri) { mutableStateOf(false) }
  var failure by remember(uri) { mutableStateOf<String?>(null) }

  AsyncImage(
    model = uri,
    contentDescription = title,
    imageLoader = loader,
    contentScale = ContentScale.Fit,
    modifier = Modifier.fillMaxSize(),
    onState = { state ->
      when (state) {
        is AsyncImagePainter.State.Success -> {
          ready = true
          failure = null
        }
        is AsyncImagePainter.State.Error -> {
          ready = false
          failure = state.result.throwable.message ?: "未知原因"
        }
        else -> Unit
      }
    },
  )

  if (!ready) {
    val reason = failure
    if (reason == null) {
      CircularProgressIndicator(
        modifier = Modifier.size(22.dp),
        strokeWidth = 2.dp,
        color = Color.White,
      )
    } else {
      Text(
        text = "图片加载失败：$reason",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.75f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
      )
    }
  }
}

/**
 * 上传预览里的视频（画面 + **自绘控件条**）。
 *
 * ## 为什么不用 `MediaController`（这是用户报过的那个 bug 的根因）
 *
 * 原先挂的是 `VideoView.setMediaController(MediaController(ctx).setAnchorView(view))`。
 * `MediaController` 是 Android 十多年前的控件，它的尺寸完全由**系统主题里的
 * `@dimen/mediacontroller_*`** 与锚点窗口决定，我们一行都改不了。这里的具体表现：
 *
 * - 它是「浮在画面底部的一层」，锚点就是那个 16:9 的画面框；
 *   框本身不高（手机竖屏下约 200dp），控件层按比例压下来就只剩一条；
 * - 时长很长时，两边的时间文本从 `01:23` 变成 `12:34:56`，**把中间的进度条挤没了** ——
 *   用户看到的正是「控件显示不全、过小」；
 * - 而且它显示/隐藏靠点画面切换，预览这种「只想看一眼」的场景经常一半控件在外面。
 *
 * 所以这里换成**自己排的一条控件栏**（[PlaybackBar]）：固定 56dp 高、图标按钮 44dp
 * 触摸区、时间文本给最小宽度、进度条吃剩余空间。时长是 3 分钟还是 30 小时，布局都一样。
 *
 * ## 为什么控件放在画面**下方**而不是浮在上面
 *
 * [VideoView] 底层是 `SurfaceView`：它直接在自己的 surface 上出画面，**Compose 画在
 * 同一层的内容盖不住它**（这正是「必须用独立 Dialog 窗口」的同一个原因）。浮层想稳，
 * 只能再开一个子窗口 —— 为一条控件栏不值。放在下方是纯布局关系，谁也不会盖住谁，
 * 且永远可见（不需要「点一下才出来」）。
 *
 * ## 为什么位置靠轮询
 *
 * `VideoView` 只有 `getCurrentPosition()`，没有任何进度回调。500ms 读一次够用
 * （进度条 1px 的移动肉眼分辨不出更细的粒度），也不会费电。拖动时跳过轮询 ——
 * 否则手指还没松，轮询就把滑块拽回播放位置，滑块会「抖」。
 */
@Composable
private fun VideoPreview(uri: Uri) {
  // 关窗必须显式停播：VideoView 不在 Compose 的生命周期里，不主动停会一直占着解码器
  var player by remember { mutableStateOf<VideoView?>(null) }
  var prepared by remember(uri) { mutableStateOf(false) }
  var playing by remember(uri) { mutableStateOf(false) }
  var durationMs by remember(uri) { mutableStateOf(0L) }
  var positionMs by remember(uri) { mutableStateOf(0L) }
  // 拖动中：此时滑块位置由手指决定，轮询让位
  var scrubbing by remember(uri) { mutableStateOf(false) }
  var scrubMs by remember(uri) { mutableStateOf(0L) }

  DisposableEffect(uri) { onDispose { player?.stopPlayback() } }

  LaunchedEffect(prepared) {
    if (!prepared) return@LaunchedEffect
    while (true) {
      delay(500)
      val view = player ?: continue
      playing = runCatching { view.isPlaying }.getOrDefault(false)
      if (!scrubbing) {
        positionMs = runCatching { view.currentPosition.toLong() }.getOrDefault(0L)
      }
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
      contentAlignment = Alignment.Center,
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          VideoView(ctx).apply {
            val view = this
            setOnPreparedListener { mp ->
              mp.isLooping = true
              // 时长在这里取一次就够了：本地文件不会中途改长度
              durationMs = mp.duration.toLong().coerceAtLeast(0L)
              prepared = true
              playing = true
            }
            setOnCompletionListener { playing = false }
            setOnErrorListener { _, _, _ ->
              prepared = false
              true
            }
            setVideoURI(uri)
            start()
            player = this
          }
        },
      )
    }

    PlaybackBar(
      playing = playing,
      ready = prepared,
      shownPositionMs = if (scrubbing) scrubMs else positionMs,
      durationMs = durationMs,
      onTogglePlay = {
        val view = player ?: return@PlaybackBar
        if (view.isPlaying) {
          view.pause()
          playing = false
        } else {
          // 播完了再点播放：回到开头，否则「点了没反应」（停在末尾）
          if (durationMs > 0 && positionMs >= durationMs - 200) view.seekTo(0)
          view.start()
          playing = true
        }
      },
      onScrubStart = { scrubbing = true },
      onScrub = { scrubMs = it },
      onScrubFinish = {
        player?.seekTo(scrubMs.toInt())
        positionMs = scrubMs
        scrubbing = false
      },
    )
  }
}

/**
 * 音频这一屏：封面（有内嵌图就显示）+ 与视频同一套控件条。
 *
 * ## 为什么用 `MediaPlayer` 而不是 `VideoView` / mpv
 *
 * 音频没有画面，`VideoView` 只会给一块黑框；mpv 是整套播放内核（渲染线程、轨道、
 * 字幕），为「听五秒确认是不是这首」拉起来太重。[MediaPlayer] 是系统自带的，
 * 支持 `content://`、异步 prepare、`seekTo`，正好够用。
 *
 * ## 三个必须做对的地方
 *
 * 1. **`prepareAsync()` 而不是 `prepare()`**：`prepare()` 会同步阻塞主线程直到读到
 *    足够数据，网络/慢存储上就是几百毫秒的卡顿（点开预览「先僵一下」）。
 * 2. **`onDispose` 必须 `stop()` + `release()`**：MediaPlayer 不在 Compose 生命周期里，
 *    不释放会一直占着音频焦点与解码器，退出预览后音乐还在响。
 * 3. **每一项都 `runCatching`**：`release()` 之后再碰任何方法都会抛
 *    `IllegalStateException`，而轮询协程与 UI 回调的时序不保证。
 */
@Composable
private fun AudioPreview(uri: Uri) {
  val context = LocalContext.current
  var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
  var prepared by remember(uri) { mutableStateOf(false) }
  var playing by remember(uri) { mutableStateOf(false) }
  var durationMs by remember(uri) { mutableStateOf(0L) }
  var positionMs by remember(uri) { mutableStateOf(0L) }
  var scrubbing by remember(uri) { mutableStateOf(false) }
  var scrubMs by remember(uri) { mutableStateOf(0L) }
  var art by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

  DisposableEffect(uri) {
    val mp = MediaPlayer()
    runCatching {
      mp.setDataSource(context, uri)
      mp.isLooping = true
    }
    mp.setOnPreparedListener { p ->
      prepared = true
      durationMs = runCatching { p.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
      runCatching { p.start() }
      playing = true
    }
    mp.setOnErrorListener { _, _, _ ->
      prepared = false
      playing = false
      true
    }
    runCatching { mp.prepareAsync() }
    player = mp
    onDispose {
      runCatching { if (mp.isPlaying) mp.stop() }
      runCatching { mp.release() }
      player = null
    }
  }

  LaunchedEffect(prepared) {
    if (!prepared) return@LaunchedEffect
    while (true) {
      delay(500)
      val mp = player ?: continue
      playing = runCatching { mp.isPlaying }.getOrDefault(playing)
      if (!scrubbing) {
        positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(positionMs)
      }
    }
  }

  // 内嵌封面是纯 IO（要解 ID3/MP4 atom），必须在 IO 线程；失败就退回音符图标
  LaunchedEffect(uri) {
    art =
      withContext(Dispatchers.IO) {
        runCatching {
          val retriever = MediaMetadataRetriever()
          try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
          } finally {
            runCatching { retriever.release() }
          }
        }.getOrNull()
      }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
      val cover = art
      if (cover != null) {
        Image(
          bitmap = cover,
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize().padding(12.dp),
        )
      } else {
        // 没有内嵌封面：一枚大音符。**不**拿文件图标糊上去 —— 那张图在 40dp 缩略图里
        // 还能认，放大到整块画面就成了一个孤零零的小图标
        Box(
          modifier =
            Modifier.size(112.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.08f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.AudioFile,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = Color.White.copy(alpha = 0.8f),
          )
        }
      }
    }

    PlaybackBar(
      playing = playing,
      ready = prepared,
      shownPositionMs = if (scrubbing) scrubMs else positionMs,
      durationMs = durationMs,
      onTogglePlay = {
        val mp = player ?: return@PlaybackBar
        if (playing) {
          runCatching { mp.pause() }
          playing = false
        } else {
          if (durationMs > 0 && positionMs >= durationMs - 200) runCatching { mp.seekTo(0) }
          runCatching { mp.start() }
          playing = true
        }
      },
      onScrubStart = { scrubbing = true },
      onScrub = { scrubMs = it },
      onScrubFinish = {
        runCatching { player?.seekTo(scrubMs.toInt()) }
        positionMs = scrubMs
        scrubbing = false
      },
    )
  }
}

/**
 * 播放控件条（视频与音频共用）。
 *
 * 高度固定 56dp、图标按钮 44dp 触摸区、两端时间文本给最小宽度、进度条吃剩余空间：
 * 时长从 `00:03` 变成 `12:34:56` 时布局不跳、进度条不被挤没。
 *
 * 黑底上必须显式给颜色 —— 默认的 inactive track 在纯黑上几乎看不见。
 */
@Composable
private fun PlaybackBar(
  playing: Boolean,
  ready: Boolean,
  shownPositionMs: Long,
  durationMs: Long,
  onTogglePlay: () -> Unit,
  onScrubStart: () -> Unit,
  onScrub: (Long) -> Unit,
  /** 松手时提交。**不带参数**：要落地的位置是调用方自己那份 `scrubMs`（这里拿不到最新值） */
  onScrubFinish: () -> Unit,
) {
  val total = durationMs.coerceAtLeast(1L)
  Row(
    modifier =
      Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 44dp = Material 的最小触摸目标，比图标本身大一圈，指头不容易点空
    IconButton(onClick = onTogglePlay, modifier = Modifier.size(44.dp), enabled = ready) {
      Icon(
        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = if (playing) "暂停" else "播放",
        modifier = Modifier.size(28.dp),
        tint = Color.White.copy(alpha = if (ready) 0.95f else 0.35f),
      )
    }

    // 时间文本给最小宽度：否则「0:03」和「12:34:56」宽度跳变，滑块会左右抖
    Text(
      text = formatPlaybackTime(shownPositionMs),
      style = MaterialTheme.typography.labelMedium,
      color = Color.White.copy(alpha = 0.9f),
      maxLines = 1,
      modifier = Modifier.widthIn(min = 54.dp),
    )

    Slider(
      value = shownPositionMs.coerceIn(0L, total).toFloat(),
      onValueChange = {
        onScrubStart()
        onScrub(it.toLong())
      },
      onValueChangeFinished = { onScrubFinish() },
      valueRange = 0f..total.toFloat(),
      enabled = ready,
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
      colors =
        SliderDefaults.colors(
          thumbColor = Color.White,
          activeTrackColor = Color.White,
          inactiveTrackColor = Color.White.copy(alpha = 0.28f),
          disabledThumbColor = Color.White.copy(alpha = 0.35f),
          disabledActiveTrackColor = Color.White.copy(alpha = 0.35f),
          disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f),
        ),
    )

    Text(
      text = if (ready) formatPlaybackTime(durationMs) else "--:--",
      style = MaterialTheme.typography.labelMedium,
      color = Color.White.copy(alpha = 0.9f),
      maxLines = 1,
      modifier = Modifier.widthIn(min = 54.dp),
    )
  }
}

/**
 * 毫秒 → `mm:ss` / `h:mm:ss`。
 *
 * 超过 1 小时才补小时段：几十分钟的视频显示成 `00:12:34` 只是白占宽度
 * （而预览窗的控件条宽度是有限的，这几十 dp 直接决定进度条长短）。
 */
internal fun formatPlaybackTime(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format("%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format("%02d:%02d", minutes, seconds)
  }
}
