package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Composable
fun NetworkFolderCard(
  file: NetworkFile,
  connectionId: Long,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  /** 长按回调带**手指坐标**（root 坐标）—— 调用方据此在手指旁边弹菜单，见 tapAndLongPress */
  onLongClick: ((Offset) -> Unit)? = null,
  isSelected: Boolean = false,
  /** 文件夹封面引用（视频海报 URL 或第一张图片的 path）；null = 还没有 / 没有可用封面 / 功能关闭 */
  coverRef: String? = null,
  /** 还没有封面时通知调用方去加载（懒加载触发点，语义同 NetworkMediaCard） */
  onCoverNeeded: ((String) -> Unit)? = null,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  // 与网格卡同一套懒加载触发：滚到眼前才列目录，VM 侧缓存/去重兜底
  if (file.isDirectory && coverRef == null && onCoverNeeded != null) {
    LaunchedEffect(file.path) { onCoverNeeded(file.path) }
  }

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
            if (isSelected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f) else Color.Transparent,
          )
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(LocalCardCorner.current))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .tapAndLongPress(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
      ) {
        // 有封面就渲染，否则默认文件夹图标（与网格卡同一口径、同一套分流）。
        // 封面可能是「视频的服务端海报 URL」或「第一张图片的 path」——
        // 缓存里只存引用本身（不含签名），图片那一支靠 AlistClient 的 fs/get 兜底。
        if (coverRef != null) {
          FolderCoverImage(
            coverRef = coverRef,
            connectionId = connectionId,
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize(),
            fallback = {
              FolderPlaceholderIcon(
                modifier = Modifier.fillMaxSize(),
                iconSize = 48.dp,
                contentDescription = stringResource(R.string.i18n_folder),
              )
            },
          )
        } else {
          FolderPlaceholderIcon(
            modifier = Modifier.fillMaxSize(),
            iconSize = 48.dp,
            contentDescription = stringResource(R.string.i18n_folder),
          )
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          file.name,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
