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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoZoomRegistry
import app.marlboroadvance.mpvex.ui.browser.photo.PhotoLoadRegistry
import app.marlboroadvance.mpvex.ui.browser.photo.PhotoLoadState
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
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
 *   请求尺寸传 [NetworkPhotoImageLoader.FULL_SIZE]（2560 有界框，**不是原图**：
 *   原图直解一张 10MB 照片要 96MB，正是「大图加载不出来」的根因）；
 *   没有服务端缩放 ⇒ 首屏加载比 Emby 慢是已知限制（磁盘缓存兜二次浏览）。
 * - **没有 HEIC 转码兜底**：老系统解不动就是失败占位 + 重试（Emby 那边有服务端
 *   `format=jpg` 兜底，网络侧没有）。
 * - **旋转**（[rotationDegrees]）：由共享外壳顶栏的旋转钮驱动，角度按条目 path 记在
 *   `PhotoRotationState` 里。放在 `.zoomable` **之前** —— 转的是整个画面，
 *   手势方向才与所见一致（与 Emby 版同一个顺序，别在这里改成 after）。
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
  /** 显示旋转角（顺时针 0/90/180/270）：只影响画面，保存的仍是原字节 */
  rotationDegrees: Int = 0,
  /** 页码：与 [zoomRegistry] 配套，用于「下拉关闭」查这张有没有被放大 */
  page: Int = 0,
  zoomRegistry: PhotoZoomRegistry? = null,
  /**
   * 加载状态登记处：把 Coil 的状态回填进去，共享外壳的幻灯片才知道
   * 「这张能看了」——图还在转圈时不倒计时、不翻页。
   */
  loadRegistry: PhotoLoadRegistry? = null,
  /** AList 下载签名（[NetworkFile.sign]）；其他协议传 null。见 AlistClient 类注释 */
  sign: String? = null,
) {
  val context = LocalContext.current
  val imageLoader = remember(context) { NetworkPhotoImageLoader.get(context) }
  // 请求尺寸走 [NetworkPhotoImageLoader.FULL_SIZE]（有界框），**不是 `Size.ORIGINAL`**：
  // 10MB 的 6000×4000 原图直解要 96MB 一个 Bitmap，与预加载叠加直接撞 OOM，
  // 表现就是「大图一直加载不出来」（推导见 FULL_SIZE 的 KDoc）。
  // ⚠️ 必须用 BUCKET_FULL 单独分桶：大图解码后仍有十几 MB，
  //    与网格缩略图共用内存 key 会把 LRU 里一圈缩略图全挤掉
  //    ⇒「点过一次大图，回网格滚回来缩略图全部重新加载」。
  // ⚠️ 预加载（查看器那边）必须用**同一个 size + 同一个桶**，否则预热的是别的 key。
  val request = remember(connectionId, path, sign) {
    NetworkPhotoImageLoader.request(
      context,
      connectionId,
      path,
      size = NetworkPhotoImageLoader.FULL_SIZE,
      sign = sign,
      bucket = NetworkPhotoImageLoader.BUCKET_FULL,
    )
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

  // 把「这张好没好」回填给共享外壳（幻灯片据此决定什么时候开始倒数）。
  // ⚠️ `Empty`（请求还没发出）也算 Loading：那时候画面只有骨架屏，不能开始计时。
  if (loadRegistry != null) {
    val loadState = when (state) {
      is AsyncImagePainter.State.Success -> PhotoLoadState.Ready
      is AsyncImagePainter.State.Error -> PhotoLoadState.Failed
      else -> PhotoLoadState.Loading
    }
    LaunchedEffect(page, loadState) { loadRegistry.bind(page, loadState) }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (painter != null) {
      Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = Modifier
          .fillMaxSize()
          .rotate(rotationDegrees.toFloat())
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
