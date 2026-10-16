package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoZoomRegistry
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.size.Size
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * 网络照片查看器里的整屏大图（[app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkPhotoViewerScreen] 用）。
 *
 * 与 Emby 版 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoPageImage] 同一套
 * 交互（那份的注释里有完整推导），差异只在数据源与两个刻意省略：
 * - **数据源**：[NetworkPhotoImageLoader]（SMB/WebDAV/FTP 流式取图），
 *   请求尺寸传 [Size.ORIGINAL] —— 要放大看细节，不能按组件尺寸采样；
 *   没有服务端缩放 ⇒ 首屏加载比 Emby 慢是已知限制（磁盘缓存兜二次浏览）。
 * - **没有 HEIC 转码兜底**：老系统解不动就是失败占位 + 重试（Emby 那边有服务端
 *   `format=jpg` 兜底，网络侧没有）。
 * - **没有旋转**：查看器不提供旋转（保存原图语义不变），需要时再按 Emby 那套补。
 *
 * ⚠️ 单击 / 长按**交给 zoomable 自己**（它的 `onClick` / `onLongClick`），
 * 不要在外层再挂 `pointerInput` 检测点击 —— 外层自己检测会与「双击放大」抢事件
 * （双击被拆成两次单击，控件会闪一下）。
 */
@Composable
fun NetworkPhotoPageImage(
  connectionId: Long,
  path: String,
  contentDescription: String?,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
  modifier: Modifier = Modifier,
  /** 页码：与 [zoomRegistry] 配套，用于「下拉关闭」查这张有没有被放大 */
  page: Int = 0,
  zoomRegistry: PhotoZoomRegistry? = null,
) {
  val context = LocalContext.current
  val imageLoader = remember(context) { NetworkPhotoImageLoader.get(context) }
  // 全屏大图要原尺寸：采样解码会把细节抹掉，放大时全是马赛克
  val request = remember(connectionId, path) {
    NetworkPhotoImageLoader.request(context, connectionId, path, size = Size.ORIGINAL)
  }
  val painter = rememberAsyncImagePainter(
    model = request,
    imageLoader = imageLoader,
    contentScale = ContentScale.Fit,
  )
  val state by painter.state.collectAsState()
  val shimmer = rememberShimmer()

  // ⚠️ 参数名以 javap 出的真实签名为准（见 EmbyPhotoImage.kt 同款注释）：
  //    ZoomSpec(maxZoomFactor, minZoomFactor, overzoomEffect)。
  val zoomableState = rememberZoomableState(
    zoomSpec = ZoomSpec(
      maxZoomFactor = PHOTO_MAX_ZOOM,
      minZoomFactor = 1f,
      overzoomEffect = OverzoomEffect.RubberBanding,
    ),
  )
  zoomableState.contentScale = ContentScale.Fit

  if (zoomRegistry != null) {
    DisposableEffect(page, zoomableState) {
      zoomRegistry.bind(page, zoomableState)
      onDispose { zoomRegistry.unbind(page, zoomableState) }
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (painter != null) {
      Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = Modifier
          .fillMaxSize()
          .zoomable(
            state = zoomableState,
            onClick = { onTap() },
            onLongClick = { onLongPress() },
          ),
        contentScale = ContentScale.Fit,
      )
    }

    when (state) {
      is AsyncImagePainter.State.Loading -> {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(0.72f)
              .aspectRatio(4f / 3f)
              .clip(RoundedCornerShape(10.dp))
              .shimmer(shimmer),
          )
          LinearProgressIndicator(
            modifier = Modifier
              .padding(top = 18.dp)
              .fillMaxWidth(0.4f),
          )
        }
      }

      is AsyncImagePainter.State.Error -> {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(24.dp),
        ) {
          Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
          )
          Text(
            text = "这张照片没能加载出来",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
          )
          Button(onClick = { painter.restart() }, modifier = Modifier.padding(top = 8.dp)) {
            Text("重试")
          }
        }
      }

      else -> Unit
    }
  }
}

/**
 * 照片最大放大倍率（与 Emby 照片浏览器同一口径：默认 2× 看细节不够，4× 是平衡点）。
 */
private const val PHOTO_MAX_ZOOM = 4f
