package app.marlboroadvance.mpvex.ui.browser.emby.photo

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * 一张照片的加载结果。
 *
 * @param painter Coil 的 painter（url 为空时为 null）
 * @param state 当前加载状态，调用方据此画骨架屏 / 失败占位
 * @param retry 重新发起这一次请求（Coil 不缓存失败结果，所以「重试」是真的重发）
 */
class EmbyPhotoPainter(
  val painter: AsyncImagePainter?,
  val state: AsyncImagePainter.State?,
  val retry: () -> Unit,
)

/**
 * 照片链路统一的图片加载（Coil）。
 *
 * 两件事在这里收口，调用方不用各写一份：
 *  1. **HEIC / AVIF 兜底**：主地址失败（老系统解不了 HEIC、服务端缩放 500）
 *     自动改用 [fallbackUrl]（服务端转好的 JPEG），与自研 EmbyImageLoader 的
 *     fallbackUrl 语义一致；
 *  2. **状态**：把 Coil 的 [AsyncImagePainter.State] 直接交给调用方画骨架屏 / 失败占位。
 *
 * ⚠️ [AsyncImagePainter.state] 在 Coil 3 里是 **StateFlow**（不是 Compose State），
 *    所以这里用 flow 版的 `collectAsState` 读 —— 写成 `painter.state` 直接读会编译不过。
 */
@Composable
fun rememberEmbyPhotoPainter(
  url: String?,
  fallbackUrl: String?,
  contentScale: ContentScale = ContentScale.Fit,
): EmbyPhotoPainter {
  val context = LocalContext.current
  val imageLoader = remember(context) { EmbyPhotoImageLoader.get(context) }

  // 主地址失败过就改用兜底地址；换 url（翻页 / 换图）自动重置回主地址
  var useFallback by remember(url, fallbackUrl) { mutableStateOf(false) }
  val target = (if (useFallback) fallbackUrl else url).takeIf { !it.isNullOrBlank() }

  val request = remember(target) { target?.let { EmbyPhotoImageLoader.request(context, it) } }
  val painter = rememberAsyncImagePainter(
    model = request,
    imageLoader = imageLoader,
    contentScale = contentScale,
  )
  val state by painter.state.collectAsState()

  LaunchedEffect(state, target) {
    if (state is AsyncImagePainter.State.Error && !useFallback && target == url) {
      useFallback = true
    }
  }

  return remember(painter, state) {
    EmbyPhotoPainter(painter = painter, state = state) { painter.restart() }
  }
}

/**
 * 照片网格里的缩略图：加载中给骨架屏，失败给占位图 + 重试。
 *
 * [contentScale] 默认 **Crop（填满卡片，不留灰边）**：网格是「一屏看很多张」的场景，
 * 缩略图有灰边会让整屏显得脏；完整画面在全屏浏览器里看（那边是 Fit 不裁切）。
 * 这也是 Google Photos / Emby Web 的做法 —— 网格裁、单张不裁。
 */
@Composable
fun EmbyPhotoThumbnail(
  url: String?,
  fallbackUrl: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  val holder = rememberEmbyPhotoPainter(url, fallbackUrl, contentScale)
  val shimmer = rememberShimmer()
  Box(modifier = modifier) {
    if (holder.painter != null) {
      Image(
        painter = holder.painter,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
      )
    }
    when (holder.state) {
      is AsyncImagePainter.State.Loading -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .shimmer(shimmer),
        )
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
          // 重试：卡片小，只给一个角上的圆钮，不占整块
          IconButton(
            onClick = holder.retry,
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

/**
 * 各页缩放状态的登记处，供「下拉关闭」判断当前这张**有没有被放大**。
 *
 * 为什么不把 zoomFraction 回调成 Compose State：那会让**每一次捏合**都触发整屏重组
 * （缩放是每帧都在变的值）。这里改成普通容器，手势回调里按需读一次 —— 手势 lambda
 * 不是快照观察者，读到的就是当前值，且**不产生任何订阅**，因此零重组。
 *
 * ⚠️ 按 page 存而不是只存「当前那个」：HorizontalPager 会先组合下一页再销毁上一页，
 * 单个槽位在翻页瞬间会指向错误的页（读到隔壁的缩放比例）。
 */
class PhotoZoomRegistry {
  private val states = mutableMapOf<Int, ZoomableState>()

  fun bind(page: Int, state: ZoomableState) {
    states[page] = state
  }

  fun unbind(page: Int, state: ZoomableState) {
    if (states[page] === state) states.remove(page)
  }

  /** 该页当前缩放进度：0 = 没放大，1 = 放到最大。页面没登记时按「没放大」处理 */
  fun zoomFractionOf(page: Int): Float = states[page]?.zoomFraction ?: 0f
}

/**
 * 照片浏览器里的整屏大图。
 *
 * - **缩放 / 双击**：Telephoto 的 [zoomable]。单击与长按**交给 zoomable 自己**（它的
 *   `onClick` / `onLongClick`），而不是在外层再挂 `pointerInput` —— 外层自己检测单击
 *   会与「双击放大」抢事件（双击被拆成两次单击，控件会闪一下），这个坑只能靠让
 *   手势归属单一组件来躲。
 * - **回弹**：[ZoomSpec] 显式关掉 `preventOverOrUnderZoom` 并给两个边界都挂
 *   [OverzoomEffect.RubberBanding] —— 默认值是「禁止越界」，越界被硬生生卡住，
 *   捏过头/缩过头都像撞墙；改成橡皮筋后才会弹回边界。最大倍率 4×（默认 2× 太小，
 *   照片看细节不够）。
 * - **旋转**：[rotationDegrees] 放在 zoomable **之前**（转整个画面，手势方向才与所见一致）。
 * - **加载中**：居中骨架块 + 底部细进度条（不用整屏转圈：整屏转圈会盖住上一张的构图，
 *   而且看不出「图有多大」）。
 * - **失败**：占位图标 + 一句说明 + 重试按钮。
 */
@Composable
fun EmbyPhotoPageImage(
  url: String?,
  fallbackUrl: String?,
  contentDescription: String?,
  rotationDegrees: Int,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
  modifier: Modifier = Modifier,
  /** 页码：与 [zoomRegistry] 配套，用于「下拉关闭」查这张有没有被放大 */
  page: Int = 0,
  zoomRegistry: PhotoZoomRegistry? = null,
) {
  val holder = rememberEmbyPhotoPainter(url, fallbackUrl, ContentScale.Fit)
  val shimmer = rememberShimmer()
  // ⚠️ 参数名以 javap 出的真实签名为准：ZoomSpec(maxZoomFactor, minZoomFactor,
  //    overzoomEffect)。凭印象写 `minimum` / `maximum` / `preventOverOrUnderZoom`
  //    会编译不过（这三个名字只在另外几个重载里存在）。
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
    if (holder.painter != null) {
      Image(
        painter = holder.painter,
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

    when (holder.state) {
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
            tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
          )
          Text(
            text = "这张照片没能加载出来",
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
          )
          Button(onClick = holder.retry, modifier = Modifier.padding(top = 8.dp)) {
            Text("重试")
          }
        }
      }

      else -> Unit
    }
  }
}

/**
 * 照片最大放大倍率。
 *
 * Telephoto 默认是 2×，看照片细节明显不够（拍屏幕上的小字、看远处景物都嫌小）；
 * 4× 是「双指能看清细节」和「放大后不至于一屏只有一个马赛克」之间的平衡点。
 * 再往上（8×、16×）在手机上没有实用意义，只会让双击缩放变成「一不小心点到就糊了」。
 */
private const val PHOTO_MAX_ZOOM = 4f
