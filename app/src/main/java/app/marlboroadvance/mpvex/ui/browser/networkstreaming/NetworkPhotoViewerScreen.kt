package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoZoomRegistry
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoImageLoader
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoPageImage
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoSaver
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * 网络照片全屏查看器（SMB / WebDAV / FTP）。
 *
 * 交互（与 Emby 照片浏览器对齐，按需裁剪）：
 * - **缩放**：Telephoto 捏合 / 双击 / 拖拽，最大 4×，越界橡皮筋回弹；
 * - **下拉关闭**：没放大的照片往下拖，整页跟手位移 + 缩小 + 变暗，
 *   拖过阈值松手退出；⭐ 放大状态下不抢这个手势（判据走
 *   [PhotoZoomRegistry.zoomFractionOf]，不产生重组）；
 * - **单击画面**：进出沉浸模式（只留照片）；沉浸态返回键先退出沉浸；
 * - **保存**：顶栏下载钮，走 [NetworkPhotoSaver]（保存位置与 Emby 照片
 *   同一项设置：自研目录 > 老 SAF 树 > 系统相册 `Pictures/CineIsle`）；
 * - **幻灯片**：右下角播放 / 暂停，间隔走「设置 → Emby 媒体库 → 幻灯片间隔」，
 *   **淡出→换页→淡入**转场（不是硬切），按钮上显示剩余秒数（圆环 + 数字），
 *   播放期间 keepScreenOn，播到最后一张自动停；
 * - **前后预加载**：相邻 ±N 张（N 走「照片预加载张数」1~3，默认 1），
 *   拉的是 [coil3.size.Size.ORIGINAL] 全图，二次浏览命中磁盘缓存；
 * - **上一张 / 下一张**：左下悬浮钮，首尾边界置灰（不留假按钮）；
 * - **底部信息栏**：文件名 + 序号 + 大小 + 日期。
 *
 * 队列由浏览页传入（目录列表一次拉全，**没有续页** —— SMB 没有分页 API，
 * 这与 Emby 版按 StartIndex 续页是结构性差异）。「随机播放图片」从浏览页
 * 进来时 [autoSlideshow] = true：洗乱的队列 + 进页即播。
 */
@Serializable
data class NetworkPhotoViewerScreen(
  val connectionId: Long,
  val connectionName: String,
  /** 队列来自哪个目录（保存 / 重连时按 connectionId 找连接，这里只作展示） */
  val dirPath: String,
  /** 初始队列快照（浏览页当前目录照片 / 随机播放结果） */
  val photos: List<NetworkFile>,
  val initialIndex: Int,
  val title: String = "",
  /** 随机播放图片进来：进页即开幻灯片 */
  val autoSlideshow: Boolean = false,
) : Screen {
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val browserPreferences = koinInject<BrowserPreferences>()
    val repository = koinInject<NetworkRepository>()

    // ── 队列 ──
    // 传入即快照：网络目录没有分页续拉，队列不会再变（与 Emby 版最大的结构差异）
    val queue = photos
    val safeInitial = initialIndex.coerceIn(0, maxOf(0, queue.size - 1))
    val pagerState = rememberPagerState(initialPage = safeInitial) { queue.size }
    val currentPage = pagerState.currentPage
    val currentItem = queue.getOrNull(currentPage)

    // ── 连接（保存要用） ──
    var connection by remember { mutableStateOf<NetworkConnection?>(null) }
    LaunchedEffect(connectionId) {
      connection = repository.getConnectionById(connectionId)
    }

    // ── 幻灯片 ──
    var slideshowPlaying by remember { mutableStateOf(autoSlideshow) }
    val interval by browserPreferences.photoSlideshowInterval.collectAsState()
    var slideshowRemaining by remember { mutableIntStateOf(0) }
    // 转场整页透明度：淡到 0 → 换页 → 淡回。用 Animatable 而不是 State：
    // 值每帧都变，走 State 会把整屏重组一遍。
    val slideshowAlpha = remember { Animatable(1f) }

    val view = LocalView.current
    DisposableEffect(slideshowPlaying) {
      view.keepScreenOn = slideshowPlaying
      onDispose { view.keepScreenOn = false }
    }

    // ── 保存 ──
    var saving by remember { mutableStateOf(false) }

    fun saveCurrentPhoto() {
      val item = currentItem ?: return
      val conn = connection ?: run {
        Toast.makeText(context, "连接不存在（可能已删除）", Toast.LENGTH_SHORT).show()
        return
      }
      if (saving) return
      saving = true
      val saveDirPath = browserPreferences.photoSaveDirPath.get().takeIf { it.isNotBlank() }
      val saveDirUri = browserPreferences.photoSaveDirUri.get().takeIf { it.isNotBlank() }
      scope.launch {
        val result = NetworkPhotoSaver.saveOriginal(
          context = context,
          connection = conn,
          file = item,
          saveDirUri = saveDirUri,
          saveDirPath = saveDirPath,
        )
        saving = false
        val where = if (saveDirPath == null && saveDirUri == null) "系统相册" else "所选目录"
        val message = when (result) {
          is NetworkPhotoSaver.Result.Success -> "原图已保存到$where"
          is NetworkPhotoSaver.Result.Failure -> result.reason
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    }

    // ── 沉浸（全屏）模式 ──
    var immersive by remember { mutableStateOf(false) }
    BackHandler(enabled = immersive) { immersive = false }

    // ── 下拉关闭 ──
    // 竖向拖动位移（px）。⚠️ 只在 graphicsLayer 的 lambda 里读 —— 绘制阶段读
    // State 只让绘制失效，不触发重组；放到组合里读就是每帧重组整屏。
    val dismissOffset = remember { Animatable(0f) }
    val zoomRegistry = remember { PhotoZoomRegistry() }
    val dismissThreshold = with(LocalDensity.current) { DISMISS_THRESHOLD_DP.dp.toPx() }

    // ── 幻灯片计时 ──
    //
    // ⭐⭐ **key 里绝不能有 currentPage**（Emby 版踩过的坑：currentPage 一变协程
    // 被取消重建，取消恰好发生在 animateScrollToPage 挂起等动画的过程中 ⇒ 动画
    // 被腰斩，页面停在两张中间，「幻灯片只滑一半」）。改成「一个协程跑到底」：
    // 循环里自己 delay 到点、自己翻页。
    LaunchedEffect(slideshowPlaying, interval) {
      if (!slideshowPlaying) {
        slideshowRemaining = 0
        slideshowAlpha.snapTo(1f)
        return@LaunchedEffect
      }
      val totalMs = interval.seconds * 1000L
      // 幻灯片自己翻页期间 isScrollInProgress 也是 true，用这个标志区分用户手势
      var autoScrolling = false
      while (true) {
        var elapsed = 0L
        slideshowRemaining = interval.seconds
        while (elapsed < totalMs) {
          delay(TICK_MS)
          if (!autoScrolling && pagerState.isScrollInProgress) {
            // 用户在自己滑：这张重新开始计时（不停止播放）
            elapsed = 0
            slideshowRemaining = interval.seconds
          } else {
            elapsed += TICK_MS
            slideshowRemaining = ((totalMs - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
          }
        }
        val next = pagerState.currentPage + 1
        if (next >= queue.size) {
          slideshowPlaying = false // 播到最后一张自动停
          slideshowRemaining = 0
          break
        }
        autoScrolling = true
        // 转场：先淡出，换页（滑动过程被藏起来），再淡入。
        // 只在自动翻页时走这条路 —— 手动左右滑就该是跟手的。
        slideshowAlpha.animateTo(0f, tween(FADE_MS))
        pagerState.animateScrollToPage(next)
        slideshowAlpha.animateTo(1f, tween(FADE_MS))
        autoScrolling = false
      }
    }

    // ── 前后预加载（相邻 ±N 张） ──
    // ⚠️ 用页面级 scope 发射，不在本 effect 里直接挂起下载：本 effect 的 key 含
    //    currentPage，翻页会取消它，取消会把在途下载前功尽弃（恰好滑到那张就白一下）。
    val preloadCount by browserPreferences.photoPreloadCount.collectAsState()
    val preload = preloadCount.coerceIn(1, MAX_PRELOAD)
    LaunchedEffect(currentPage, preload) {
      for (offset in -preload..preload) {
        if (offset == 0) continue
        val item = queue.getOrNull(currentPage + offset) ?: continue
        scope.launch {
          withContext(Dispatchers.IO) {
            NetworkPhotoImageLoader.preload(context, connectionId, item.path, size = coil3.size.Size.ORIGINAL)
          }
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      if (queue.isEmpty()) {
        // 友好提示：不该发生（浏览页点进来必有照片），但队列被外部改空时
        // 不能甩一个黑屏
        Text(
          text = "没有照片",
          color = Color.White,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.align(Alignment.Center),
        )
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            // 竖向拖动 = 下拉关闭。放在 graphicsLayer **之前**（外层）：
            // 手势拿到未变换的坐标，拖动距离与手指位移一致。
            .pointerInput(Unit) {
              detectVerticalDragGestures(
                onDragEnd = {
                  if (abs(dismissOffset.value) > dismissThreshold) {
                    backStack.removeLastOrNull()
                  } else {
                    scope.launch { dismissOffset.animateTo(0f, spring()) }
                  }
                },
                onDragCancel = {
                  scope.launch { dismissOffset.animateTo(0f, spring()) }
                },
                onVerticalDrag = { _, dragAmount ->
                  // ⭐ 放大状态下竖向拖动是「平移画面」，不能抢。Telephoto 自己
                  //    会消费掉，根本走不到这里；这里的判断是双保险。
                  if (zoomRegistry.zoomFractionOf(currentPage) <= 0.01f) {
                    scope.launch { dismissOffset.snapTo(dismissOffset.value + dragAmount) }
                  }
                },
              )
            }
            .graphicsLayer {
              val progress = (abs(dismissOffset.value) / dismissThreshold).coerceIn(0f, 1f)
              translationY = dismissOffset.value
              val scale = 1f - progress * 0.22f
              scaleX = scale
              scaleY = scale
              // 下拉变暗 + 幻灯片转场淡入淡出，两者相乘
              alpha = (1f - progress * 0.55f) * slideshowAlpha.value
            },
        ) {
          HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> queue.getOrNull(index)?.path ?: index },
          ) { page ->
            val item = queue.getOrNull(page)
            if (item == null) {
              Text(
                text = "照片信息缺失",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
              )
            } else {
              NetworkPhotoPageImage(
                connectionId = connectionId,
                path = item.path,
                contentDescription = item.name,
                onTap = { immersive = !immersive },
                onLongPress = { saveCurrentPhoto() },
                modifier = Modifier.fillMaxSize(),
                page = page,
                zoomRegistry = zoomRegistry,
              )
            }
          }
        }

        // ── 顶栏：返回 + 标题 + 计数 + 保存 ──
        // 沉浸态整条淡出（不占手势、不挡画面），退出沉浸再淡入
        AnimatedVisibility(
          visible = !immersive,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.TopCenter),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.4f))
              .statusBarsPadding()
              .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
              )
            }
            Text(
              text = title.ifBlank { connectionName },
              color = Color.White,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
              Text(
                text = "${currentPage + 1} / ${queue.size}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            IconButton(onClick = { saveCurrentPhoto() }, enabled = !saving) {
              if (saving) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  color = Color.White,
                )
              } else {
                Icon(
                  imageVector = Icons.Default.Download,
                  contentDescription = "保存原图",
                  tint = Color.White,
                )
              }
            }
          }
        }

        // ── 左下角：上一张 / 下一张 ──
        // 首尾边界置灰：按钮在却点了没反应是最糟的交互
        AnimatedVisibility(
          visible = !immersive && queue.size > 1,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.BottomStart),
        ) {
          Row(
            modifier = Modifier
              .navigationBarsPadding()
              .padding(start = 20.dp, bottom = CONTROLS_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            PhotoNavButton(
              enabled = currentPage > 0,
              icon = Icons.Default.ChevronLeft,
              contentDescription = "上一张",
              onClick = { scope.launch { pagerState.animateScrollToPage(currentPage - 1) } },
            )
            PhotoNavButton(
              enabled = currentPage < queue.size - 1,
              icon = Icons.Default.ChevronRight,
              contentDescription = "下一张",
              onClick = { scope.launch { pagerState.animateScrollToPage(currentPage + 1) } },
            )
          }
        }

        // ── 右下角：幻灯片播放 / 暂停（播放时显示倒计时） ──
        AnimatedVisibility(
          visible = !immersive && queue.size > 1,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.BottomEnd),
        ) {
          SlideshowButton(
            playing = slideshowPlaying,
            remainingSeconds = slideshowRemaining,
            totalSeconds = interval.seconds,
            saving = saving,
            bottomPadding = CONTROLS_BOTTOM,
            onClick = { slideshowPlaying = !slideshowPlaying },
          )
        }

        // ── 底部信息栏：文件名 + 大小 + 日期 ──
        AnimatedVisibility(
          visible = !immersive,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.BottomCenter),
        ) {
          PhotoInfoBar(item = currentItem, index = currentPage + 1, total = queue.size)
        }
      }
    }
  }
}

/** 底部信息栏：文件名 + 序号 + 大小 + 日期（缺的段不显示） */
@Composable
private fun PhotoInfoBar(
  item: NetworkFile?,
  index: Int,
  total: Int,
) {
  if (item == null) return
  val sizeText = if (item.size > 0) formatPhotoSize(item.size) else null
  val dateText = if (item.lastModified > 0) formatPhotoDate(item.lastModified) else null
  val meta = listOfNotNull(sizeText, dateText).joinToString(" · ")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.4f))
      .navigationBarsPadding()
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = item.name,
      color = Color.White,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    if (meta.isNotBlank()) {
      Text(
        text = meta,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
      )
    }
    Text(
      text = "$index / $total",
      color = Color.White.copy(alpha = 0.7f),
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(start = 12.dp),
    )
  }
}

private fun formatPhotoSize(bytes: Long): String =
  when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }

private fun formatPhotoDate(timestamp: Long): String {
  val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
  return format.format(java.util.Date(timestamp))
}

/**
 * 右下角的幻灯片按钮：播放中显示**倒计时**（圆环 + 剩余秒数），暂停时是播放图标。
 *
 * 圆环自绘（Canvas）而不用带进度的 `CircularProgressIndicator`：那个 API 在
 * material3 1.5 的 alpha 版里签名一直在动，自绘一了百了，也不受主题色影响
 * —— 全屏浏览器底色是纯黑，所有悬浮控件统一「半透明黑底 + 白前景」。
 */
@Composable
private fun SlideshowButton(
  playing: Boolean,
  remainingSeconds: Int,
  totalSeconds: Int,
  saving: Boolean,
  bottomPadding: Dp,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = Color.Black.copy(alpha = 0.45f),
    modifier = Modifier
      .navigationBarsPadding()
      .padding(end = 20.dp, bottom = bottomPadding)
      .size(52.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      if (saving) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          color = Color.White,
        )
      } else if (playing) {
        val progress = (remainingSeconds.toFloat() / totalSeconds.coerceAtLeast(1).toFloat())
          .coerceIn(0f, 1f)
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
          val stroke = Stroke(width = 3.dp.toPx())
          // 底环
          drawArc(
            color = Color.White.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
          )
          // 剩余进度：从 12 点顺时针，随倒计时变短
          drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = stroke,
          )
        }
        Text(
          text = remainingSeconds.coerceAtLeast(0).toString(),
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
        )
      } else {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = "播放幻灯片",
          tint = Color.White,
          modifier = Modifier.size(26.dp),
        )
      }
    }
  }
}

/**
 * 照片浏览器左下角的悬浮切换按钮（上一张 / 下一张）。
 *
 * 到边界（第一张 / 最后一张）时**降到更低的不透明度并禁用点击**：让用户一眼看出
 * 「到头了」，而不是按了没反应。
 */
@Composable
private fun PhotoNavButton(
  enabled: Boolean,
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .background(
        color = Color.Black.copy(alpha = if (enabled) 0.45f else 0.2f),
        shape = CircleShape,
      )
      .then(
        if (enabled) {
          Modifier.clickable(onClick = onClick)
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
      modifier = Modifier.size(24.dp),
    )
  }
}
/** 下拉关闭的位移阈值（dp）—— 与 Emby 照片浏览器同一口径 */
private const val DISMISS_THRESHOLD_DP = 120f

/** 悬浮控件（上一张/下一张/幻灯片）距底的高度，给信息栏让位 */
private val CONTROLS_BOTTOM = 64.dp

/** 幻灯片计时 tick（ms）与淡入淡出时长（ms） */
private const val TICK_MS = 250L
private const val FADE_MS = 220

/** 预加载张数上限（设置项 1~3 的护栏） */
private const val MAX_PRELOAD = 3
