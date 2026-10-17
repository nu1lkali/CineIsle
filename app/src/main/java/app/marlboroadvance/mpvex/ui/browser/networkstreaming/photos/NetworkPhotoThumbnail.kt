package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络照片缩略图（网格卡用）。
 *
 * 与 Emby 照片卡的 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoThumbnail]
 * 同一套视觉语言（骨架屏 / 失败占位 + 重试 / Crop 填满），差异只在数据源：
 * 字节从 SMB/WebDAV/FTP 会话里拉，没有服务端缩放与转码兜底
 * （见 [NetworkPhotoImageLoader] 的 KDoc）。
 *
 * @param size 请求的解码尺寸；null = 按组件约束采样（更省内存）。
 * @param sign AList 下载签名（[NetworkFile.sign]）；其他协议 / 文件夹封面传 null。
 *   文件夹封面在缓存里只存了 path（拿不到签名），由 AlistClient 的 `fs/get` 兜底。
 */
@Composable
fun NetworkPhotoThumbnail(
  connectionId: Long,
  path: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
  sign: String? = null,
) {
  val context = LocalContext.current
  val imageLoader = remember(context) { NetworkPhotoImageLoader.get(context) }
  val request = remember(connectionId, path, sign) {
    NetworkPhotoImageLoader.request(context, connectionId, path, sign = sign)
  }
  // 内存缓存里已有 ⇒ 首帧就有图，不必画骨架屏。
  // 否则返回上一级时每张封面都会「闪一下」（painter 从 Loading 重新走一遍），
  // 而图其实一直在缓存里。
  val memoryCached = remember(connectionId, path) {
    NetworkPhotoImageLoader.isMemoryCached(context, connectionId, path)
  }
  val painter = rememberAsyncImagePainter(
    model = request,
    imageLoader = imageLoader,
    contentScale = contentScale,
  )
  val state by painter.state.collectAsState()
  val shimmer = rememberShimmer()
  val scope = rememberCoroutineScope()

  /**
   * 重载：**先清掉这一张的缓存**，再让 painter 重走一次请求。
   *
   * ⚠️ 只 `painter.restart()` 是不够的 —— 原实现就是这样，用户反馈「点了没反应」：
   *    取流失败常常在磁盘缓存里留下半截数据，或 AList 的签名已经过期，
   *    重走同一个请求只会再撞一次同一堵墙（状态 Error → Error，界面毫无变化）。
   *    清缓存这一步才是关键，见 [NetworkPhotoImageLoader.evict]。
   *
   * 清缓存是磁盘 IO，放 IO 线程；`restart()` 必须回主线程调。
   */
  val onRetry: () -> Unit = {
    scope.launch {
      withContext(Dispatchers.IO) {
        NetworkPhotoImageLoader.evict(context, connectionId, path)
      }
      painter.restart()
    }
  }

  // 失败后**自动重载一次**：网络抖一下（首包超时 / 会话过期）能自己救回来。
  // 只试一次 —— 真挂了（文件已删 / 编码解不动）就别在后台空转，留给用户点。
  // 延迟 600ms 是给网络栈喘口气，也避开「刚失败立刻又失败」的瞬时状态。
  var autoRetried by remember(connectionId, path) { mutableStateOf(false) }
  LaunchedEffect(connectionId, path, state) {
    if (state is AsyncImagePainter.State.Error && !autoRetried) {
      autoRetried = true
      delay(600)
      onRetry()
    }
  }

  Box(modifier = modifier) {
    if (painter != null) {
      Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
      )
    }
    when (state) {
      is AsyncImagePainter.State.Loading -> {
        // 缓存命中就别画骨架屏了：那一下闪烁纯属噪音
        if (!memoryCached) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .shimmer(shimmer),
          )
        }
      }

      is AsyncImagePainter.State.Error -> {
        Box(
          // 整块可点，不只是右下角那个小图标：32dp 的按钮在缩略图里很容易点偏，
          // 而这个区域本来就是「这块图没出来」的视觉焦点，点哪儿都该重试。
          // （也顺手挡掉外层卡片的点击 —— 用户想重试，不是想进这个文件夹。）
          modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onRetry),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp),
          )
          IconButton(
            onClick = onRetry,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .size(34.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "重新加载",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      else -> Unit
    }
  }
}
