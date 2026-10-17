package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoImageLoader
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoPageImage
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoSaver
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoThumbnail
import app.marlboroadvance.mpvex.ui.browser.photo.PhotoViewerEntry
import app.marlboroadvance.mpvex.ui.browser.photo.SharedPhotoViewer
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 网络照片全屏查看器（SMB / WebDAV / FTP）。
 *
 * ⭐ 外壳全部来自 [SharedPhotoViewer]（与 Emby 照片浏览器**同一份实现**）：
 * 横向翻页、沉浸模式、下拉关闭、幻灯片（间隔 / 倒计时 / 淡入淡出）、
 * 底部胶片条、上一张 / 下一张、底部信息栏，改一处两边同时生效。
 * 本文件只剩**网络特有的那一层**：
 * - [renderPage]：从 SMB / WebDAV / FTP 会话流式拉原图（[NetworkPhotoPageImage]）；
 * - [renderThumbnail]：胶片条缩略图走同一套网络取图（[NetworkPhotoThumbnail]）；
 * - 顶栏保存钮 → [NetworkPhotoSaver]（保存位置与 Emby 照片同一项设置：
 *   自研目录 > 老 SAF 树 > 系统相册 `Pictures/CineIsle`）；
 * - 前后 ±N 张预加载（N 走「照片预加载张数」1~3，默认 1），拉的是
 *   [coil3.size.Size.ORIGINAL] 全图，二次浏览命中磁盘缓存。
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

    // ── 交给共享外壳的最小信息 ──
    // 标题 = 文件名；附注 = 「大小 · 日期」（信息栏第二行）。
    // ⚠️ key 用 path：同一个目录内唯一，比下标稳（队列被换掉时不会错位复用）。
    val entries = remember(queue) {
      queue.map { file ->
        PhotoViewerEntry(
          key = file.path,
          title = file.name,
          meta = listOfNotNull(
            if (file.size > 0) formatPhotoSize(file.size) else null,
            if (file.lastModified > 0) formatPhotoDate(file.lastModified) else null,
          ).joinToString(" · "),
        )
      }
    }

    // ── 连接（保存要用） ──
    var connection by remember { mutableStateOf<NetworkConnection?>(null) }
    LaunchedEffect(connectionId) {
      connection = repository.getConnectionById(connectionId)
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
            NetworkPhotoImageLoader.preload(
              context,
              connectionId,
              item.path,
              size = coil3.size.Size.ORIGINAL,
              sign = item.sign,
            )
          }
        }
      }
    }

    SharedPhotoViewer(
      entries = entries,
      pagerState = pagerState,
      title = title.ifBlank { connectionName },
      autoPlaySlideshow = autoSlideshow,
      // 网络版有「文件名 + 大小·日期 + 序号」信息栏（Emby 版把这些放进了「图片信息」弹窗）
      showInfoBar = true,
      onBack = { backStack.removeLastOrNull() },
      topBarActions = {
        IconButton(onClick = { saveCurrentPhoto() }, enabled = !saving) {
          if (saving) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              color = androidx.compose.ui.graphics.Color.White,
            )
          } else {
            Icon(
              imageVector = Icons.Default.Download,
              contentDescription = "保存原图",
              tint = androidx.compose.ui.graphics.Color.White,
            )
          }
        }
      },
      renderPage = { index, zoomRegistry, onTap ->
        val item = queue.getOrNull(index)
        if (item != null) {
          NetworkPhotoPageImage(
            connectionId = connectionId,
            path = item.path,
            contentDescription = item.name,
            // 长按 = 直接保存这张（网络版没有操作面板）
            onTap = onTap,
            onLongPress = { saveCurrentPhoto() },
            modifier = Modifier.fillMaxSize(),
            page = index,
            zoomRegistry = zoomRegistry,
            sign = item.sign,
          )
        }
      },
      renderThumbnail = { index, modifier ->
        queue.getOrNull(index)?.let { item ->
          NetworkPhotoThumbnail(
            connectionId = connectionId,
            path = item.path,
            contentDescription = item.name,
            sign = item.sign,
            // ⚠️ 胶片条的格子只有 44dp，而重试钮的触控区是 48dp —— 放进来会盖满整块，
            //    把「点一下跳到那张」这个唯一手势吃掉。
            showRetry = false,
            modifier = modifier,
          )
        }
      },
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

/** 预加载张数上限（设置项 1~3 的护栏） */
private const val MAX_PRELOAD = 3
