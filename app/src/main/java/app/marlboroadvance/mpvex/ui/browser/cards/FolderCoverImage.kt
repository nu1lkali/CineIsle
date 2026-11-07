package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.isRemoteCoverRef
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkApiThumbnail
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoThumbnail

/**
 * 文件夹封面渲染：**按封面引用的形态分流**（网格卡与列表行共用这一份）。
 *
 * 封面引用是一个字符串，两种来源（见 `domain/network/FolderCoverSelector.kt`）：
 *
 * | 形态 | 来源 | 渲染 |
 * |---|---|---|
 * | `http(s)://...` | 视频的服务端海报帧（AList `fs/list` 的 `thumb`） | [NetworkApiThumbnail] 直接拉 URL |
 * | 其余（相对 path） | 图片文件本身 | [NetworkPhotoThumbnail] 取字节解码 |
 *
 * 分流判据必须用 [isRemoteCoverRef] 而不是「看起来像 URL」，两处保持同一份实现：
 * 相对 path 不可能以 `http://` 开头，所以这个判据不会误判；而视频海报**只能**走
 * URL 渲染 —— 拿 path 去取字节得到的是整个视频文件，不可能当图用。
 *
 * @param fallback 服务端海报拉不到（签名过期 / 服务端关掉了缩略图）时的兜底，
 *   调用方传文件夹图标。⚠️ 只覆盖 URL 那一支：图片走 [NetworkPhotoThumbnail]，
 *   它自带破图占位与重试钮（失败时用户至少能重试，而不是永远停在图标上）。
 */
@Composable
fun FolderCoverImage(
  coverRef: String,
  connectionId: Long,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  fallback: @Composable () -> Unit,
) {
  if (isRemoteCoverRef(coverRef)) {
    NetworkApiThumbnail(
      remoteThumbUrl = coverRef,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = ContentScale.Crop,
      fallback = fallback,
    )
  } else {
    NetworkPhotoThumbnail(
      connectionId = connectionId,
      path = coverRef,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = ContentScale.Crop,
    )
  }
}

/**
 * 文件夹封面缺失时的统一占位（灰底 + 文件夹图标）。
 *
 * 网格卡与列表行共用：两处的图标尺寸不同（网格 44dp / 列表行 48dp），底色与
 * tint 必须一致，所以只开一个尺寸参数，别在调用点各写一份。
 *
 * @param iconSize 图标边长；容器会自己撑满 [modifier]
 * @param contentDescription 无障碍描述；网格卡不传（卡片整体已有文件名），
 *   列表行传「文件夹」（图标是那一行唯一的可见标识）
 */
@Composable
fun FolderPlaceholderIcon(
  modifier: Modifier = Modifier,
  iconSize: Dp = 44.dp,
  contentDescription: String? = null,
) {
  Box(
    modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.Folder,
      contentDescription = contentDescription,
      modifier = Modifier.size(iconSize),
      tint = MaterialTheme.colorScheme.secondary,
    )
  }
}
