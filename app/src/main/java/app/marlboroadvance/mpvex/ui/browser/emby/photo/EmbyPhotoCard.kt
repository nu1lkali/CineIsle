package app.marlboroadvance.mpvex.ui.browser.emby.photo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyAppearance

/**
 * 照片卡片（图集网格用）。
 *
 * 与视频卡片的区别，每条都是有意的：
 * - **不裁切**（[ContentScale.Fit]）：照片是内容本身，裁掉边缘等于丢画面；
 *   视频海报裁成 2:3 是行业惯例，照片不是。卡片底色用 surfaceVariant，
 *   宽银幕 / 竖图 Letterbox 时露出的就是它，与视频卡同一套底色。
 * - **不固定宽高比**：高度随原图比例（瀑布流的前提），所以不能像海报卡那样
 *   给 Card 加 `aspectRatio`。
 * - **没有任何角标**：照片没有播放进度 / 收藏 / 时长 / 多选，
 *   右上角红心、底部进度条一概不渲染。
 * - 副标题给相机型号 + 像素尺寸（见 [photoSubtitle]），不是年份评分。
 *
 * HEIC 走 [photoDisplayUrl] 原格式 + [photoJpegFallbackUrl] 服务端转码兜底，
 * 老系统 / AVIF 也能出图（见 EmbyImageLoader 的 sniffModernMime）。
 */
@Composable
fun EmbyPhotoCard(
  item: EmbyItem,
  displayUrl: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  fallbackUrl: String? = null,
  subtitle: String? = photoSubtitle(item),
  maxWidth: Int = 720,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(LocalEmbyAppearance.current.corner),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      EmbyImage(
        url = displayUrl,
        fallbackUrl = fallbackUrl,
        contentDescription = item.Name,
        // Fit + 不固定高：高度由原图比例决定（瀑布流）。
        // heightIn(min) 是给「图还没加载出来」时的占位高度，避免卡片塌成一条。
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 120.dp),
        contentScale = ContentScale.Fit,
        placeholder = Icons.Default.Image,
        maxWidth = maxWidth,
      )
    }

    Column(modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
      Text(
        text = item.Name ?: "照片",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
