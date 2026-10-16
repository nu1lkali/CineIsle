package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * 网络照片缩略图（网格卡用）。
 *
 * 与 Emby 照片卡的 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoThumbnail]
 * 同一套视觉语言（骨架屏 / 失败占位 + 重试 / Crop 填满），差异只在数据源：
 * 字节从 SMB/WebDAV/FTP 会话里拉，没有服务端缩放与转码兜底
 * （见 [NetworkPhotoImageLoader] 的 KDoc）。
 *
 * @param size 请求的解码尺寸；null = 按组件约束采样（更省内存）。
 */
@Composable
fun NetworkPhotoThumbnail(
  connectionId: Long,
  path: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  val context = LocalContext.current
  val imageLoader = remember(context) { NetworkPhotoImageLoader.get(context) }
  val request = remember(connectionId, path) {
    NetworkPhotoImageLoader.request(context, connectionId, path)
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
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp),
          )
          IconButton(
            onClick = { painter.restart() },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .size(32.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "重新加载",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }

      else -> Unit
    }
  }
}
