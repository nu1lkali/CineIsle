package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络浏览的**统一网格卡**：文件夹 / 照片 / 视频三态共用一个卡型，
 * 右上角挂类型小角标（见 [NetworkTypeBadge]）—— SMB/WebDAV 目录经常是
 * 混合内容，卡片必须在同一屏里说清「这是图片还是视频还是文件夹」。
 *
 * 与旧版（文件夹卡 / 视频卡各一套列表卡）的刻意差异：
 * - **视频不拉缩略图**：网络视频取帧要么整文件下载要么接解码器，都不划算，
 *   沿用旧视频卡的做法 —— surfaceContainerHigh 底 + 播放图标占位。
 * - **照片走 [NetworkPhotoThumbnail]**：拉全图 + Coil 采样解码，Crop 填满
 *   （没有服务端缩放，见 NetworkPhotoImageLoader 的 KDoc）。
 * - **统一 1:1 图区**（[aspectRatio]）：与 Emby 照片卡同一理由 ——
 *   高度随原图比例浮动的话网格参差不齐。
 * - **OTHER 不出现**：字幕 / nfo 这类文件网格里直接不渲染（列表视图才显示），
 *   由调用方过滤；万一传进来也只是个通用文件占位，不会崩。
 */
@Composable
fun NetworkMediaCard(
  file: NetworkFile,
  connectionId: Long,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val type = file.type()
  Column(modifier = modifier.fillMaxWidth()) {
    Card(
      onClick = onClick,
      shape = RoundedCornerShape(12.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f),
      ) {
        when (type) {
          NetworkFileType.FOLDER -> {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          }

          NetworkFileType.PHOTO -> {
            // 网络照片：流式拉全图 + 采样解码，骨架屏 / 失败重试都在组件里
            NetworkPhotoThumbnail(
              connectionId = connectionId,
              path = file.path,
              contentDescription = file.name,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          }

          NetworkFileType.VIDEO -> {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          }

          NetworkFileType.OTHER -> {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        NetworkTypeBadge(
          type = type,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp),
        )
      }
    }

    Text(
      text = file.name,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
    )

    // 媒体类卡片给一行副标题（大小 · 日期）；文件夹没有可显示的元数据，不占位
    val subtitle = networkFileSubtitle(file)
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
      )
    }
  }
}

/**
 * 卡片右上角的**类型小角标**：半透明黑底 + 白色小图标，
 * 压在缩略图 / 占位块上都能看清（照片五颜六色、文件夹是浅色块，都压得住）。
 *
 * 网格卡与列表照片行共用这一个组件，两处视觉语言一致。
 */
@Composable
fun NetworkTypeBadge(
  type: NetworkFileType,
  modifier: Modifier = Modifier,
) {
  val icon = when (type) {
    NetworkFileType.FOLDER -> Icons.Filled.Folder
    NetworkFileType.PHOTO -> Icons.Filled.Image
    NetworkFileType.VIDEO -> Icons.Filled.Movie
    NetworkFileType.OTHER -> Icons.Filled.InsertDriveFile
  }
  Box(
    modifier = modifier
      .size(20.dp)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.45f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = type.displayName,
      modifier = Modifier.size(12.dp),
      tint = Color.White,
    )
  }
}

/**
 * 网络照片的**列表行**（列表视图用）：64dp 圆角缩略图 + 名称 + 大小/日期。
 *
 * 与网格卡的分工：网格看「脸」，列表看「信息」—— 所以这里额外渲染
 * 大小 · 日期副标题（[networkFileSubtitle]）。
 */
@Composable
fun NetworkPhotoRow(
  file: NetworkFile,
  connectionId: Long,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        NetworkPhotoThumbnail(
          connectionId = connectionId,
          path = file.path,
          contentDescription = file.name,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        NetworkTypeBadge(
          type = NetworkFileType.PHOTO,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp),
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          file.name,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle = networkFileSubtitle(file)
        if (subtitle != null) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * 其他文件的**列表行**（字幕 / nfo 等）：纯展示，不可点。
 *
 * 这些文件没有「打开」的语义（字幕不能单独播、nfo 是元数据），
 * 所以不给点击反馈 —— 长得像能点却点不动，比明显不能点更糟。
 */
@Composable
fun NetworkOtherFileRow(
  file: NetworkFile,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.InsertDriveFile,
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          file.name,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle = networkFileSubtitle(file)
        if (subtitle != null) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * 媒体卡片的副标题：「大小 · 日期」，缺哪段就不显示哪段。
 * 文件夹 / 没有任何元数据的条目返回 null（调用方不占位）。
 *
 * 纯函数（不读 Composable 状态）：调用点直接拿结果，不参与重组。
 */
private fun networkFileSubtitle(file: NetworkFile): String? {
  val sizeText = if (file.size > 0) formatNetworkFileSize(file.size) else null
  val dateText = if (file.lastModified > 0) formatNetworkFileDate(file.lastModified) else null
  return when {
    sizeText != null && dateText != null -> "$sizeText · $dateText"
    sizeText != null -> sizeText
    dateText != null -> dateText
    else -> null
  }
}

private fun formatNetworkFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }
}

private fun formatNetworkFileDate(timestamp: Long): String {
  val date = Date(timestamp)
  val format = SimpleDateFormat("MMM dd", Locale.getDefault())
  return format.format(date)
}
