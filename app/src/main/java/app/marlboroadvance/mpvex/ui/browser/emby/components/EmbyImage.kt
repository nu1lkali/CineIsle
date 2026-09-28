package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Emby 封面图。
 *
 * 图片地址由 [app.marlboroadvance.mpvex.domain.emby.EmbyClient.imageUrl] 生成（已带 api_key），
 * 通过 [EmbyImageLoader] 用项目自带的 OkHttp 下载，不依赖第三方图片库。
 *
 * @param url 图片地址，为空/加载失败时显示占位图标
 * @param fallbackUrl 备用图地址：当 [url] 加载失败（例如服务器没有 Backdrop 图，返回 404）时改用此地址。
 *                    剧照（Backdrop）缺失时回退到海报（Primary）即可用这一参数实现。
 * @param maxWidth 解码宽度上限。默认 720：既能覆盖卡片尺寸，又能把内存占用压住
 *                 （服务端缩放失败时会回退取原图，原图可能是 2000px 级别）。
 *                 需要全尺寸的场合（如详情页大图）显式传 0。
 * @param placeholder 占位图标（海报用电影图标、剧集用电视图标等）
 */
@Composable
fun EmbyImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
  placeholder: ImageVector = Icons.Default.BrokenImage,
  maxWidth: Int = 720,
  fallbackUrl: String? = null,
) {
  val bitmap by produceState<android.graphics.Bitmap?>(
    initialValue = null,
    key1 = url,
    key2 = fallbackUrl,
    key3 = maxWidth,
  ) {
    value = loadWithFallback(url, fallbackUrl, maxWidth)
  }

  if (bitmap != null) {
    androidx.compose.foundation.Image(
      bitmap = bitmap!!.asImageBitmap(),
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
    )
  } else {
    Box(
      modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = placeholder,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
      )
    }
  }
}

/** 先试主地址，失败再试备用地址；两者都失败返回 null。 */
private suspend fun loadWithFallback(
  url: String?,
  fallbackUrl: String?,
  maxWidth: Int,
): android.graphics.Bitmap? {
  if (!url.isNullOrBlank()) {
    val primary = EmbyImageLoader.load(url, maxWidth)
    if (primary != null) return primary
  }
  if (!fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
    return EmbyImageLoader.load(fallbackUrl, maxWidth)
  }
  return null
}

/**
 * 带渐变遮罩的横幅图，用于详情页背景。
 */
@Composable
fun EmbyBackdrop(
  url: String?,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    EmbyImage(
      url = url,
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
  }
}

/** 卡片圆角，全 Emby 界面统一 */
val EMBY_CARD_CORNER = 12.dp
