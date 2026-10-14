package app.marlboroadvance.mpvex.ui.browser.emby.photo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyAppearance

/**
 * 照片卡片（图集 / 媒体库网格用）。
 *
 * 与视频卡片的区别，每条都是有意的：
 * - **缩略图裁切填满**（[ContentScale.Crop]）：照片卡是统一 1:1，用 Fit 的话
 *   竖图左右、横图上下都会露出底色，一屏看下去全是灰边（用户实测反馈）。
 *   网格裁、单张不裁 —— 完整画面在**全屏浏览器**里看（那边是 Fit）。
 *   这也是 Google Photos / Emby Web 的做法。卡片底色仍用 surfaceVariant，
 *   图还没到时露出的就是它（现在被骨架屏盖住）。
 * - **统一等比**（[aspectRatio]，默认 1:1）：⭐ 每张卡都占同一块尺寸，网格才齐整。
 *   早先的版本是「高度随原图比例」的瀑布流，横图与竖图并排差出一倍高度，
 *   观感很乱（用户反馈「卡片大小不一」）。
 * - **没有任何角标**：照片没有播放进度 / 收藏 / 时长 / 多选，
 *   右上角红心、底部进度条一概不渲染。
 * - 副标题给相机型号 + 像素尺寸（见 [photoSubtitle]），不是年份评分。
 *
 * 缩略图走 [EmbyPhotoThumbnail]（Coil）：HEIC / 老系统解不动时自动改用服务端
 * `format=jpg` 转码那张（[fallbackUrl]）；加载中是骨架屏，失败给占位 + 重试。
 */
@Composable
fun EmbyPhotoCard(
  item: EmbyItem,
  displayUrl: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  fallbackUrl: String? = null,
  subtitle: String? = photoSubtitle(item),
  /** 卡片图区的宽高比；默认 1:1。所有照片卡都用同一个值才叫「统一」。 */
  aspectRatio: Float = 1f,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(LocalEmbyAppearance.current.corner),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      // 定死图区比例：卡片尺寸不再随原图比例浮动。图还没加载出来时也是这一块，
      // 所以不会出现「先塌成一条、图到了再撑开」的跳动。
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(aspectRatio),
      ) {
        // 照片链路走 Coil（EmbyPhotoThumbnail）：加载中骨架屏、失败占位 + 重试，
        // 缩略图 Crop 填满卡片不留灰边。完整画面在全屏浏览器里看。
        EmbyPhotoThumbnail(
          url = displayUrl,
          fallbackUrl = fallbackUrl,
          contentDescription = item.Name,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
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
