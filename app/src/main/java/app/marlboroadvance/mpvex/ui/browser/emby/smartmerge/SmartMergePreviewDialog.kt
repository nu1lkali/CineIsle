package app.marlboroadvance.mpvex.ui.browser.emby.smartmerge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyVersionOption
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyVersionPickerSheet
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小窗预览的会话：候选里的全部条目 + 当前正在看的那个。
 */
data class SmartMergePreviewSession(
  val items: List<SmartMergeItem>,
  val selectedId: String,
)

/**
 * 非全屏预览播放器。
 *
 * 背景模糊不依赖系统跨窗口模糊（部分 ROM / Android 12 以下根本没有），而是：
 *  1. 打开时把 Activity 当前画面截一张图；
 *  2. 缩小到 1/12 后做两遍盒式模糊（几乎等于高斯），再放大铺满；
 *  3. 在模糊图上盖一层轻遮罩，中心放视频小窗。
 *
 * 这样任何系统版本都能看到真正的模糊效果，且小窗内容不参与截图，始终清晰。
 */
@Composable
fun SmartMergePreviewDialog(
  request: SmartMergePreviewRequest,
  items: List<SmartMergeItem> = emptyList(),
  selectedId: String? = null,
  onSelectItem: (SmartMergeItem) -> Unit = {},
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var error by remember(request.url) { mutableStateOf<String?>(null) }
  var versionSheetOpen by remember { mutableStateOf(false) }
  var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }

  // 截图必须在主线程；缩放 + 模糊放到后台，避免打开小窗时卡一下。
  LaunchedEffect(Unit) {
    val activity = context.findActivity() ?: return@LaunchedEffect
    val captured = runCatching { captureActivityWindow(activity) }.getOrNull()
      ?: return@LaunchedEffect
    val blurred = withContext(Dispatchers.Default) {
      runCatching { downscaleAndBlur(captured) }.getOrNull()
    }
    if (blurred != null) {
      if (blurred !== captured) captured.recycle()
      backdrop = blurred.asImageBitmap()
    } else {
      captured.recycle()
    }
  }

  val player = remember(request.url) {
    val httpFactory = DefaultHttpDataSource.Factory()
      .setDefaultRequestProperties(request.headers)
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(12_000)
      .setReadTimeoutMs(20_000)
    ExoPlayer.Builder(context)
      .setRenderersFactory(
        NextRenderersFactory(context).setEnableDecoderFallback(true),
      )
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory),
      )
      .build()
  }

  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) {
        error = "预览播放失败：${e.errorCodeName}"
      }
    }
    player.addListener(listener)
    onDispose {
      player.removeListener(listener)
      player.release()
    }
  }

  LaunchedEffect(player, request.url) {
    player.setMediaItem(MediaItem.fromUri(request.url))
    player.prepare()
    player.playWhenReady = true
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    FullscreenDialogEffect()

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val selectedItem = items.getOrNull(selectedIndex)

    Box(modifier = Modifier.fillMaxSize()) {
      // 自绘模糊背景：截图 → 缩小 → 两次盒式模糊 → 裁切铺满。
      backdrop?.let { image ->
        Image(
          bitmap = image,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        // 轻遮罩：既压暗背景，又保证模糊图不会抢小窗里的视频。
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
      } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))

      Surface(
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxWidth(0.94f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              request.title,
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
              Icon(Icons.Filled.Close, contentDescription = "关闭预览")
            }
          }

          if (items.size > 1 && selectedItem != null) {
            Surface(
              onClick = { versionSheetOpen = true },
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    "版本 ${selectedIndex + 1}/${items.size}：" + previewVersionLabel(selectedItem),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val spec = previewSpecParts(selectedItem).joinToString(" · ")
                  if (spec.isNotBlank()) {
                    Text(
                      spec,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换版本")
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .background(Color.Black),
          ) {
            AndroidView(
              modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
              factory = { ctx ->
                PlayerView(ctx).apply {
                  useController = true
                  resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                  setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                  this.player = player
                }
              },
              update = { view -> view.player = player },
            )
            error?.let {
              Text(
                it,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          }
          Text(
            "小窗预览：上方可打开版本列表原地切换；关闭后回到候选详情，不会影响 Emby 中的任何数据。",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (versionSheetOpen) {
      EmbyVersionPickerSheet(
        options = items.map { item ->
          EmbyVersionOption(
            id = item.id,
            title = previewVersionLabel(item),
            badge = previewBadge(item),
            spec = previewSpecParts(item),
            path = previewFileName(item),
          )
        },
        selectedId = selectedItem?.id,
        onSelect = { id ->
          versionSheetOpen = false
          items.firstOrNull { it.id == id }?.let(onSelectItem)
        },
        onDismiss = { versionSheetOpen = false },
      )
    }
  }
}

/** 下拉框标题：番号 + 版本特征，例如 `NEOB-0017 · LTX · 1080P · 中字`。 */
private fun previewVersionLabel(item: SmartMergeItem): String =
  listOfNotNull(
    item.code?.takeIf { it.isNotBlank() },
    item.version.label.takeIf { it.isNotBlank() && it != "未知版本" },
  ).distinct().joinToString(" · ").ifBlank {
    item.displayTitle.take(30)
  }

/** 版本卡片角标：分辨率。 */
private fun previewBadge(item: SmartMergeItem): String =
  item.mediaSources.firstOrNull()?.height?.takeIf { it > 0 }?.let { "${it}P" }.orEmpty()

/** 版本卡片的规格标签：编码 / 音频 / 容器 / 体积 / 码率 / 字幕。 */
private fun previewSpecParts(item: SmartMergeItem): List<String> {
  val source = item.mediaSources.firstOrNull() ?: return emptyList()
  return listOfNotNull(
    source.videoCodec?.takeIf { it.isNotBlank() }?.uppercase(),
    source.audioCodecs
      .take(2)
      .joinToString("/")
      .takeIf { it.isNotBlank() }
      ?.uppercase(),
    source.container?.takeIf { it.isNotBlank() }?.uppercase(),
    source.size?.takeIf { it > 0 }?.let { formatPreviewSize(it) },
    source.bitrate?.takeIf { it > 0 }?.let { formatPreviewBitrate(it) },
    source.subtitleLanguages
      .take(3)
      .joinToString("/")
      .takeIf { it.isNotBlank() }
      ?.let { "字幕 $it" },
  )
}

/** 版本卡片的次要信息：文件名，方便区分同名版本。 */
private fun previewFileName(item: SmartMergeItem): String? =
  item.path
    ?.substringAfterLast('/')
    ?.substringAfterLast('\\')
    ?.takeIf { it.isNotBlank() && it.length <= 120 }

private fun formatPreviewSize(bytes: Long): String {
  val gb = bytes / 1024.0 / 1024.0 / 1024.0
  return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatPreviewBitrate(bits: Long): String =
  if (bits >= 1_000_000) "%.1f Mbps".format(bits / 1_000_000.0) else "${bits / 1000} kbps"

/**
 * 对话框窗口铺满全屏，方便自绘遮罩覆盖整块屏幕。
 * 背景亮度由截图上的 scrim 控制，所以把系统 dim 关掉，避免叠两层黑。
 */
@Composable
private fun FullscreenDialogEffect() {
  val view = LocalView.current
  DisposableEffect(view) {
    val window = view.findDialogWindow()
    window?.setLayout(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    window?.setDimAmount(0f)
    onDispose { }
  }
}

/** 沿 View 树向上找承载 Dialog 的窗口。 */
private fun View.findDialogWindow(): Window? {
  var parent: ViewParent? = this.parent
  while (parent != null) {
    if (parent is DialogWindowProvider) return parent.window
    parent = parent.parent
  }
  return null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/** 把 Activity 当前画面画进一张 Bitmap。必须在主线程调用。 */
private fun captureActivityWindow(activity: Activity): Bitmap? {
  val view = activity.window?.decorView ?: return null
  if (view.width <= 0 || view.height <= 0) return null
  val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(bitmap))
  return bitmap
}

/**
 * 截图缩小到 1/12，再做两遍半径 2 的盒式模糊。
 *
 * 先缩小再模糊是刻意的：像素少 144 倍，模糊成本可以忽略；放大时双线性过滤
 * 会进一步平滑，最终效果接近高斯模糊，而且不会有 RenderEffect 的机型兼容问题。
 */
private fun downscaleAndBlur(source: Bitmap): Bitmap {
  val width = (source.width / 12).coerceIn(24, 240)
  val height = (source.height / 12).coerceIn(24, 480)
  var small = Bitmap.createScaledBitmap(source, width, height, true)
  repeat(2) {
    val next = boxBlur(small, 2)
    if (next !== small) small.recycle()
    small = next
  }
  return small
}

/** 简单的可分离盒式模糊；在小图上跑，成本很低。 */
private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
  val width = source.width
  val height = source.height
  if (width <= 0 || height <= 0) return source
  val input = IntArray(width * height)
  source.getPixels(input, 0, width, 0, 0, width, height)
  val horizontal = IntArray(width * height)
  val window = radius * 2 + 1

  for (y in 0 until height) {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val pixel = input[y * width + i.coerceIn(0, width - 1)]
      a += pixel ushr 24 and 0xFF
      r += pixel shr 16 and 0xFF
      g += pixel shr 8 and 0xFF
      b += pixel and 0xFF
    }
    for (x in 0 until width) {
      horizontal[y * width + x] =
        (a / window shl 24) or
        (r / window shl 16) or
        (g / window shl 8) or
        (b / window)
      val old = input[y * width + (x - radius).coerceIn(0, width - 1)]
      val new = input[y * width + (x + radius + 1).coerceIn(0, width - 1)]
      a += (new ushr 24 and 0xFF) - (old ushr 24 and 0xFF)
      r += (new shr 16 and 0xFF) - (old shr 16 and 0xFF)
      g += (new shr 8 and 0xFF) - (old shr 8 and 0xFF)
      b += (new and 0xFF) - (old and 0xFF)
    }
  }

  val output = IntArray(width * height)
  for (x in 0 until width) {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val pixel = horizontal[i.coerceIn(0, height - 1) * width + x]
      a += pixel ushr 24 and 0xFF
      r += pixel shr 16 and 0xFF
      g += pixel shr 8 and 0xFF
      b += pixel and 0xFF
    }
    for (y in 0 until height) {
      output[y * width + x] =
        (a / window shl 24) or
        (r / window shl 16) or
        (g / window shl 8) or
        (b / window)
      val old = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
      val new = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
      a += (new ushr 24 and 0xFF) - (old ushr 24 and 0xFF)
      r += (new shr 16 and 0xFF) - (old shr 16 and 0xFF)
      g += (new shr 8 and 0xFF) - (old shr 8 and 0xFF)
      b += (new and 0xFF) - (old and 0xFF)
    }
  }

  return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}
