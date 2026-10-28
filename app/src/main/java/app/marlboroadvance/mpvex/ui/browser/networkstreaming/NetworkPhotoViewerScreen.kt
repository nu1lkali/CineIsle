package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkApiThumbnail
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoThumbnail
import app.marlboroadvance.mpvex.ui.browser.photo.PhotoViewerEntry
import app.marlboroadvance.mpvex.ui.browser.photo.SharedPhotoViewer
import app.marlboroadvance.mpvex.ui.browser.photo.formatPhotoFileSize
import app.marlboroadvance.mpvex.ui.browser.photo.rememberPhotoRotationState
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 网络照片全屏查看器（SMB / WebDAV / FTP）。
 *
 * 外壳全部来自 [SharedPhotoViewer]（与 Emby 照片浏览器**同一份实现**）：
 * 横向翻页、沉浸模式（点画面 / 顶栏全屏钮）、**旋转 90°**、下拉关闭、
 * 幻灯片（间隔 / 倒计时 / 淡入淡出）、底部胶片条、上一张 / 下一张、
 * **底部信息栏**（文件名 + 大小·日期 + 序号，两个入口都有），改一处两边同时生效。
 *
 * 本文件只剩**网络特有的那一层**：
 * - [renderPage]：从 SMB / WebDAV / FTP 会话流式拉原图（[NetworkPhotoPageImage]）；
 * - [renderThumbnail]：胶片条缩略图走同一套网络取图（[NetworkPhotoThumbnail]）；
 * - 顶栏「更多菜单」→ 保存原图（[NetworkPhotoSaver]）/ 图片信息（含路径与连接名）；
 *   保存位置与 Emby 照片同一项设置：自研目录 > 老 SAF 树 > 系统相册 `Pictures/CineIsle`；
 * - 前后 ±N 张预加载（N 走「照片预加载张数」1~3，默认 1），拉的是
 *   [NetworkPhotoImageLoader.FULL_SIZE]（与真正显示同一个尺寸、同一个桶，命中才是真命中），
 *   二次浏览命中磁盘缓存。
 *
 * ⚠️ 全屏 / 旋转**不再由本文件画**——它们和 Emby 版共用外壳那一份，
 * 以前各画一份的结果就是「那边有、这边没有」。
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
    //    旋转角也按这个 key 记 —— 与 Emby 版（按条目 Id 记）同一个机制。
    val entries = remember(queue) {
      queue.map { file ->
        PhotoViewerEntry(
          key = file.path,
          title = file.name,
          meta = listOfNotNull(
            if (file.size > 0) formatPhotoFileSize(file.size) else null,
            if (file.lastModified > 0) formatPhotoDate(file.lastModified) else null,
          ).joinToString(" · "),
        )
      }
    }

    // 旋转状态：与外壳共用**同一个实例**（顶栏旋转钮 / 长按 / 导出都要读同一份角度）
    val rotation = rememberPhotoRotationState()
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

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
    // 用页面级 scope 发射而不是让本 effect 直接挂起：effect 的 key 含 currentPage，
    // 一旦直接挂起，翻页就等于把下载砍掉。这里改成「自己持有 Job、翻页时主动取消
    // 上一条」—— 取消是刻意的：串行跑完 6 张要几十秒，不取消的话快速连滑会堆成
    // 一串同时在下 10MB 的任务，把「当前这张」的带宽挤没（大图一直转圈的形态）。
    // Coil 对同一个 key 的请求会合并，当前这张由 painter 发起的请求优先级更高，
    // 取消旧的预加载不会牵连它。
    // ⚠️⚠️ **必须串行**（一个 launch 里 for 循环，不要每张一个 launch）：
    //    网络照片走的是同一个连接 / SMB 会话，几张 10MB 的图**并发**读会互相抢带宽，
    //    结果连「当前这一张」都读不完 —— 用户看到的就是「大图一直加载中」
    //    （小图几百 KB，抢起来也瞬间完成，所以只有大图才显形）。
    // ⚠️ 桶与尺寸必须和真正显示那次一致（BUCKET_FULL + FULL_SIZE），
    //    否则预热的是内存缓存里另一个 key，等于白下载一遍。
    val preloadCount by browserPreferences.photoPreloadCount.collectAsState()
    val preload = preloadCount.coerceIn(1, MAX_PRELOAD)
    var preloadJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(currentPage, preload) {
      // 上一条预加载先取消：串行 job 跑完 6 张要几十秒，快速连滑会堆成一串
      // 同时在下 10MB 的任务，把「当前这张」的带宽挤没（正是大图一直转圈的形态）。
      // 取消只会损失上一次的首张预取，而那张正好是用户此刻看到的 —— 它的请求
      // 由 painter 自己发起，优先级更高。
      preloadJob?.cancel()
      preloadJob = scope.launch {
        // 顺序：后一张 → 前一张 → ±2 ……（往后翻是主路径，先备它）
        for (offset in PRELOAD_ORDER.take(preload * 2)) {
          val item = queue.getOrNull(currentPage + offset) ?: continue
          withContext(Dispatchers.IO) {
            NetworkPhotoImageLoader.preload(
              context,
              connectionId,
              item.path,
              size = NetworkPhotoImageLoader.FULL_SIZE,
              sign = item.sign,
              bucket = NetworkPhotoImageLoader.BUCKET_FULL,
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
      // 外壳持有的同一个旋转实例（长按面板 / 信息栏之外的地方读角度都走它）
      rotation = rotation,
      onBack = { backStack.removeLastOrNull() },
      topBarActions = {
        // 顶栏结构与 Emby 版一致：外壳画「全屏 + 旋转」，这里只画「更多」。
        // ⚠️ 不再单独放一个下载图标 —— 与 Emby 的菜单入口对齐，少一个钮，
        //    也不会出现「Emby 在菜单里保存、网络在顶栏保存」这种两套习惯。
        Box {
          IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
          }
          DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
              text = { Text("图片信息") },
              onClick = {
                showMenu = false
                showInfo = true
              },
            )
            DropdownMenuItem(
              text = { Text("保存原图") },
              enabled = !saving,
              onClick = {
                showMenu = false
                saveCurrentPhoto()
              },
            )
          }
        }
      },
      renderPage = { index, zoomRegistry, loadRegistry, rotationDegrees, onTap ->
        val item = queue.getOrNull(index)
        if (item != null) {
          NetworkPhotoPageImage(
            connectionId = connectionId,
            path = item.path,
            contentDescription = item.name,
            rotationDegrees = rotationDegrees,
            // 长按 = 直接保存这张（网络版没有操作面板）
            onTap = onTap,
            onLongPress = { saveCurrentPhoto() },
            modifier = Modifier.fillMaxSize(),
            page = index,
            zoomRegistry = zoomRegistry,
            // 回填加载状态：原图还在流式拉取时，幻灯片不该开始倒数
            // （一张 SMB 上的几十 MB 原图要好几秒，先数完再翻页等于这张没看）
            loadRegistry = loadRegistry,
            sign = item.sign,
          )
        }
      },
      renderThumbnail = { index, modifier ->
        queue.getOrNull(index)?.let { item ->
          NetworkApiThumbnail(
            remoteThumbUrl = item.thumb,
            contentDescription = item.name,
            modifier = modifier,
            showShimmer = false,
            fallback = {
              NetworkPhotoThumbnail(
                connectionId = connectionId,
                path = item.path,
                contentDescription = item.name,
                sign = item.sign,
                // ⚠️ 胶片条的格子只有 44dp，而重试钮的触控区是 48dp —— 放进来会盖满整块，
                //    把「点一下跳到那张」这个唯一手势吃掉。
                showRetry = false,
                // 44dp 与网格的 360px 是两个尺寸，必须独占一个内存桶：共用会把
                // 网格缩略图覆盖成 132px 的小图（回去就变糊），反之也浪费内存。
                cacheBucket = app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoImageLoader.BUCKET_FILM,
              )
            },
          )
        }
      },
    )

    // ── 图片信息 ──
    // 与 Emby 版同一个样式（label 左、value 右），字段换成网络侧真实有的：
    // 名称 / 大小 / 修改时间 / 路径 / 连接。路径是网络版最有用的那一项
    // （排查「这张到底在哪」时全靠它）。
    if (showInfo) {
      val lines = currentItem?.let { networkPhotoInfoLines(it, connectionName) } ?: emptyList()
      AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text("图片信息") },
        text = {
          if (lines.isEmpty()) {
            Text("没有可显示的信息")
          } else {
            Column(
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
              lines.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                  Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                  )
                  Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showInfo = false }) { Text("知道了") }
        },
      )
    }
  }
}

/** 「图片信息」的内容行（label → value）：缺的字段不进列表，不占位 */
private fun networkPhotoInfoLines(file: NetworkFile, connectionName: String): List<Pair<String, String>> =
  buildList {
    add("名称" to file.name)
    if (file.size > 0) add("大小" to formatPhotoFileSize(file.size))
    if (file.lastModified > 0) add("修改时间" to formatPhotoDateTime(file.lastModified))
    add("路径" to file.path)
    if (connectionName.isNotBlank()) add("连接" to connectionName)
  }

private fun formatPhotoDate(timestamp: Long): String {
  val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
  return format.format(java.util.Date(timestamp))
}

/** 对话框里的完整时间（带时分）：信息栏那行只放日期，够用是为了不挤 */
private fun formatPhotoDateTime(timestamp: Long): String {
  val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
  return format.format(java.util.Date(timestamp))
}

/** 预加载张数上限（设置项 1~3 的护栏） */
private const val MAX_PRELOAD = 3

/**
 * 预加载的偏移顺序：**先下一张，再上一张**，然后 ±2、±3。
 *
 * 用固定序列而不是 `-N..N` 是为了让 `take(preload * 2)` 就能拿到「按优先级排好的
 * 前后各 N 张」—— 往后翻是浏览与幻灯片的默认方向，带宽有限时先备它。
 */
private val PRELOAD_ORDER = listOf(1, -1, 2, -2, 3, -3)
