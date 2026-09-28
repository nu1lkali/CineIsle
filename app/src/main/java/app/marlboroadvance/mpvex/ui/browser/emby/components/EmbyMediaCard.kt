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

        // 居中播放按钮
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

        // 左下角剩余时长角标（深蓝底），仅「继续观看」等带进度的卡片传入
        if (!remainingText.isNullOrBlank()) {
          Surface(
            modifier = Modifier
              .align(Alignment.BottomStart)
              // 留出底部进度条（3dp）空间，避免角标压住进度条
              .padding(start = 6.dp, bottom = 8.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF0B2E5B).copy(alpha = 0.92f),
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
      // 底部深色遮罩：库名/数量整块落在遮罩里，而不是浮在封面上。
      // 封面图深浅不可控（浅色海报上白字几乎看不见），所以遮罩从卡片 35% 处开始渐入、
      // 到文字区域已经是接近不透明的黑，标题一定在「深色遮罩里面」。
      //
      // 之前用「整卡渐变 + BottomStart 文字块」，文字块顶部其实还落在半透明区
      // （卡片只有 90dp 高，两行文字加内边距占了近一半），标题看着浮在遮罩上沿。
      // 现在改成固定高度的底部遮罩带，文字块在带内**垂直居中** —— 标题落在遮罩正中，
      // 位置也比原来更低。
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(LIBRARY_MASK_BAND_HEIGHT)
          .background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
              colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.45f to Color.Black.copy(alpha = 0.72f),
                1.00f to Color.Black.copy(alpha = 0.94f),
              ),
            ),
          ),
      )
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .height(LIBRARY_MASK_BAND_HEIGHT)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = name,
          style = MaterialTheme.typography.titleMedium.copy(
            // 遮罩再厚也可能压不住极亮的封面边缘，补一点描边阴影兜底
            shadow = androidx.compose.ui.graphics.Shadow(
              color = Color.Black.copy(alpha = 0.8f),
              blurRadius = 6f,
            ),
          ),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (itemCount != null && itemCount > 0) {
          Text(
            text = "$itemCount 项",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
          )
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

/** 首页媒体库卡片底部遮罩带的高度（标题块在带内垂直居中）。 */
private val LIBRARY_MASK_BAND_HEIGHT = 48.dp
