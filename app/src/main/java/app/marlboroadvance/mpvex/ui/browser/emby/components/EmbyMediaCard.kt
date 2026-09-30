package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Emby 海报卡片（2:3 竖版），用于电影、剧集、媒体库封面。
 *
 * @param progress 播放进度 0f~1f，null 表示未播放（不显示进度条）
 * @param isFavorite 是否在右上角显示收藏角标
 */
@Composable
fun EmbyPosterCard(
  title: String,
  subtitle: String?,
  imageUrl: String?,
  progress: Float?,
  isFavorite: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  placeholder: ImageVector = Icons.Default.Movie,
  fallbackImageUrl: String? = null,
) {
  Column(modifier = modifier.width(POSTER_WIDTH)) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(POSTER_RATIO)
        .then(
          if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
          } else {
            Modifier.combinedClickable(onClick = onClick)
          }
        ),
      shape = RoundedCornerShape(EMBY_CARD_CORNER),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        EmbyImage(
          url = imageUrl,
          fallbackUrl = fallbackImageUrl,
          contentDescription = title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          placeholder = placeholder,
        )

        if (isFavorite) {
          Surface(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(6.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
          ) {
            Icon(
              imageVector = Icons.Default.Favorite,
              contentDescription = "已收藏",
              modifier = Modifier
                .padding(4.dp)
                .size(14.dp),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        // 播放进度：贴在海报底部
        if (progress != null && progress > 0f) {
          EmbyProgressBar(
            progress = progress,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .height(3.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
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

/**
 * Emby 横版卡片（16:9），用于继续观看、剧集列表。
 *
 * @param progress 播放进度 0f~1f
 * @param remainingText 左下角剩余时长角标文案（如「剩余 12分30秒」），null 时不显示
 * @param showPlayButton 是否在封面正中显示播放按钮。首页「继续观看」单击是进详情页而不是
 *   直接播放，中间那个三角形会让人误以为「点了就播」，所以关闭；历史页仍是点击即播，保留。
 */
@Composable
fun EmbyWideCard(
  title: String,
  subtitle: String?,
  imageUrl: String?,
  progress: Float?,
  isFavorite: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  fallbackImageUrl: String? = null,
  remainingText: String? = null,
  showPlayButton: Boolean = true,
) {
  Column(modifier = modifier.width(WIDE_WIDTH)) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(WIDE_RATIO)
        .then(
          if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
          } else {
            Modifier.combinedClickable(onClick = onClick)
          }
        ),
      shape = RoundedCornerShape(EMBY_CARD_CORNER),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        EmbyImage(
          url = imageUrl,
          fallbackUrl = fallbackImageUrl,
          contentDescription = title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )

        // 居中播放按钮：首页「继续观看」不需要（单击进详情页，三角按钮会误导），
        // 由 showPlayButton 关闭，避免中央那个图标压住剧照主体
        if (showPlayButton) {
          Surface(
            modifier = Modifier
              .align(Alignment.Center)
              .size(36.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
              )
            }
          }
        }

        // 左下角剩余时长角标，仅「继续观看」等带进度的卡片传入
        if (!remainingText.isNullOrBlank()) {
          Surface(
            modifier = Modifier
              .align(Alignment.BottomStart)
              // 留出底部进度条（3dp）空间，避免角标压住进度条
              .padding(start = 6.dp, bottom = 8.dp),
            shape = RoundedCornerShape(6.dp),
            color = MEDIA_BADGE_BG,
          ) {
            Text(
              text = remainingText,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
          }
        }

        if (isFavorite) {
          Surface(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(6.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
          ) {
            Icon(
              imageVector = Icons.Default.Favorite,
              contentDescription = "已收藏",
              modifier = Modifier
                .padding(4.dp)
                .size(14.dp),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        if (progress != null && progress > 0f) {
          EmbyProgressBar(
            progress = progress,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .height(3.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
      Text(
        text = title,
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

/**
 * 媒体卡片展示样式。
 *
 * 与 Emby 官方客户端一致，库内列表可在三种样式间切换：
 * - 海报：竖版 2:3，电影/剧集默认
 * - 背景图：横版 16:9，剧集单集常用
 * - 横幅：超宽幅，接近剧照比例
 */
enum class EmbyCardStyle(
  val label: String,
  val ratio: Float,
  val width: Dp,
) {
  // 海报比例由 2:3 压到 3:4：一行三个时纵向能多显示一行，卡片不再显得又高又大
  POSTER("海报", 3f / 4f, 110.dp),
  BACKDROP("背景图", 16f / 9f, 160.dp),
  BANNER("横幅", 2.9f, 220.dp),
}

/**
 * 通用媒体卡片。
 *
 * @param style 卡片样式（决定宽高比与宽度）
 * @param progress 播放进度 0f~1f，null 表示未播放
 * @param fillWidth 为 true 时铺满父级宽度（用于「一行固定 N 个」的网格）；
 *                  为 false 时使用 [style] 自带的固定宽度（用于横向滚动行）
 * @param mosaicUrls 多宫格封面：文件夹类条目自身没有封面图，用内部视频的缩略图拼成
 *                   2×2 宫格（列表非空时优先于 [imageUrl]）
 */
@Composable
fun EmbyMediaCard(
  title: String,
  subtitle: String?,
  imageUrl: String?,
  progress: Float?,
  isFavorite: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: EmbyCardStyle = EmbyCardStyle.POSTER,
  placeholder: ImageVector = Icons.Default.Movie,
  fallbackImageUrl: String? = null,
  fillWidth: Boolean = false,
  mosaicUrls: List<String>? = null,
) {
  Column(modifier = if (fillWidth) modifier.fillMaxWidth() else modifier.width(style.width)) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(style.ratio)
        .combinedClickable(onClick = onClick),
      shape = RoundedCornerShape(EMBY_CARD_CORNER),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        if (!mosaicUrls.isNullOrEmpty()) {
          FolderMosaicCover(urls = mosaicUrls)
        } else {
          EmbyImage(
            url = imageUrl,
            fallbackUrl = fallbackImageUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
          )
        }

        if (isFavorite) {
          Surface(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(6.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
          ) {
            Icon(
              imageVector = Icons.Default.Favorite,
              contentDescription = "已收藏",
              modifier = Modifier
                .padding(4.dp)
                .size(14.dp),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        if (progress != null && progress > 0f) {
          EmbyProgressBar(
            progress = progress,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .height(3.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
      Text(
        text = title,
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

/**
 * 文件夹多宫格封面。
 *
 * Emby 的文件夹条目没有自己的封面图，这里用文件夹内前几项子媒体的缩略图拼成宫格，
 * 一眼能看出里面装的是什么，比一个纯灰色文件夹占位图标有用得多。
 *
 * - 1 张：直接铺满（不硬凑宫格）
 * - 2 张：左右并排
 * - 3 张：上排两张 + 下排一张通栏
 * - 4 张及以上：2×2 宫格
 */
@Composable
private fun FolderMosaicCover(urls: List<String>) {
  val tiles = urls.take(4)
  when (tiles.size) {
    1 -> MosaicTile(tiles[0], Modifier.fillMaxSize())

    2 -> Row(modifier = Modifier.fillMaxSize()) {
      MosaicTile(tiles[0], Modifier.weight(1f).fillMaxHeight())
      MosaicTile(tiles[1], Modifier.weight(1f).fillMaxHeight())
    }

    3 -> Column(modifier = Modifier.fillMaxSize()) {
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        MosaicTile(tiles[0], Modifier.weight(1f).fillMaxHeight())
        MosaicTile(tiles[1], Modifier.weight(1f).fillMaxHeight())
      }
      MosaicTile(tiles[2], Modifier.weight(1f).fillMaxWidth())
    }

    else -> Column(modifier = Modifier.fillMaxSize()) {
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        MosaicTile(tiles[0], Modifier.weight(1f).fillMaxHeight())
        MosaicTile(tiles[1], Modifier.weight(1f).fillMaxHeight())
      }
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        MosaicTile(tiles[2], Modifier.weight(1f).fillMaxHeight())
        MosaicTile(tiles[3], Modifier.weight(1f).fillMaxHeight())
      }
    }
  }
}

/** 宫格里的单张缩略图；地址为空时留一块占位底色 */
@Composable
private fun MosaicTile(
  url: String?,
  modifier: Modifier,
) {
  Box(
    modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    if (!url.isNullOrBlank()) {
      EmbyImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
  }
}

/**
 * 媒体库入口卡片：显示库名与媒体数量。
 */
@Composable
fun EmbyLibraryCard(
  name: String,
  itemCount: Int?,
  imageUrl: String?,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    onClick = onClick,
    modifier = modifier
      .width(LIBRARY_CARD_WIDTH)
      .height(LIBRARY_CARD_HEIGHT),
    shape = RoundedCornerShape(EMBY_CARD_CORNER),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      EmbyImage(
        url = imageUrl,
        contentDescription = name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        placeholder = icon,
      )
      // 库名不再靠「整片渐变遮罩」压暗，而是直接给文字垫一块实心底色
      // —— 跟「继续观看」卡片左下角那个「剩余 XX分XX秒」角标同一套做法：
      // 深蓝底 + 圆角 + 半透明，只盖住文字那一小块。
      //
      // 好处：遮罩面积从「半张卡片」降到「一小条」，封面主体完整露出来；
      // 文字一定落在实色底上，不会出现「上半截压在渐变半透明区」的问题。
      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
      ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MEDIA_BADGE_BG,
          ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = name,
              style = MaterialTheme.typography.titleSmall,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (itemCount != null && itemCount > 0) {
              Text(
                text = "  $itemCount 项",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 简易播放进度条。
 *
 * 用 Box 自绘而非 LinearProgressIndicator，避免依赖 Material3 alpha 中仍在变动的
 * progress 参数签名。
 */
@Composable
fun EmbyProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.background(Color.Transparent)) {
    Box(
      modifier = Modifier
        .fillMaxWidth(progress.coerceIn(0f, 1f))
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

/** 一行横向滚动的标题栏 */
@Composable
fun EmbySectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
    )
    action?.invoke()
  }
}

private val POSTER_WIDTH = 110.dp
private const val POSTER_RATIO = 2f / 3f
private val WIDE_WIDTH = 160.dp
private const val WIDE_RATIO = 16f / 9f
private val LIBRARY_CARD_WIDTH = 150.dp
private val LIBRARY_CARD_HEIGHT = 90.dp

/**
 * 压在封面上的角标底色（库名 / 剩余时长共用一套）。
 *
 * 原来是深蓝 `0xFF0B2E5B`，在暖色封面和紫调主题下都显得很跳，换成中性炭黑 ——
 * 取自 App 暗色背景（`0xFF161217`）那一族的色调，不偏蓝，配白字在任何封面上都稳。
 * 半透明是为了让底下的封面还能透出一点点，不至于像贴了张死色纸。
 */
private val MEDIA_BADGE_BG = Color(0xFF262229).copy(alpha = 0.85f)
