package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkApiThumbnail
import org.koin.compose.koinInject

@Composable
fun NetworkVideoCard(
  file: NetworkFile,
  connection: NetworkConnection,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  /** 长按回调带**手指坐标**（root 坐标）—— 调用方据此在手指旁边弹菜单，见 tapAndLongPress */
  onLongClick: ((Offset) -> Unit)? = null,
  isSelected: Boolean = false,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  val thumbSizeDp = 64.dp

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tapAndLongPress(onClick = onClick, onLongClick = onLongClick),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(
            if (isSelected) {
              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
            } else {
              Color.Transparent
            },
          )
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Square thumbnail matching folder icon size
      Box(
        modifier =
          Modifier
            .size(thumbSizeDp)
            .clip(RoundedCornerShape(LocalCardCorner.current))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .tapAndLongPress(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
      ) {
        // 有接口海报就不再压播放三角（48dp 压在 64dp 的海报上会挡掉大半个画面，
        // 而且文件名后缀已经表明是视频）。没海报 / 拉不到才退回三角占位。
        NetworkApiThumbnail(
          remoteThumbUrl = file.thumb,
          contentDescription = file.name,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          showShimmer = false,
          fallback = {
            // ⚠️ 自己居中：本组件空态在父 Box（居中）、失败态在自己的 Box（默认 TopStart）
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.i18n_play),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(
        modifier = Modifier.weight(1f),
      ) {
        // 日期进标题前缀（含年份、定长）⇒ 不再单占一个 chip，长名字被省略时日期仍在
        NetworkFileNameText(
          name = file.name,
          lastModified = file.lastModified,
          style = MaterialTheme.typography.titleSmall,
          maxLines = maxLines,
          color = MaterialTheme.colorScheme.onSurface,
        )
        // 只有大小有 chip 时才画这一行：日期已进标题，这里再没别的内容
        if (showSizeChip && file.size > 0) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            formatFileSize(file.size),
            style = MaterialTheme.typography.labelSmall,
            modifier =
              Modifier
                .background(
                  MaterialTheme.colorScheme.surfaceContainerHigh,
                  RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }
}
