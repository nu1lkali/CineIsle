package app.marlboroadvance.mpvex.ui.cine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterCard
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel


/**
 * 「全部」网格页。
 *
 * 分区只露 4 张（宽屏）/ 横滑（手机），剩下的走这里 —— 不把「全部」做成死按钮。
 */
@Composable
fun CineGridOverlay(
  title: String,
  items: List<CineMediaUi>,
  onClose: () -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space
  val colors = CineTheme.colors
  val type = CineTheme.type
  val play = rememberCinePlay()

  val wide = rememberCineWide()
  val columns = if (wide) 5 else 3
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(88.dp)

  BackHandler(onBack = onClose)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(start = 6.dp, end = space.screenH, top = 6.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = null,
          tint = colors.onSurface,
          modifier = Modifier.size(20.dp),
        )
      }
      Spacer(Modifier.width(4.dp))
      Text(
        text = title,
        style = type.sectionTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = items.size.toString(),
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }

    CineMediaGrid(
      items = items,
      cardWidth = cardWidth,
      columns = columns,
      onClick = { play(it.item, it.resumeSeconds) },
      onLongClick = { onOpenDetail(it.item) },
    )
  }
}

/**
 * 海报网格（搜索页与「全部」页共用）。
 *
 * 卡片宽度由调用方按列数算好传进来 —— 与横滑行里同样的 `CinePosterCard`，
 * 所以标题自适应、角标、进度条的表现完全一致，不会出现「网格里的卡片和首页不一样」。
 */
@Composable
internal fun CineMediaGrid(
  items: List<CineMediaUi>,
  cardWidth: Dp,
  columns: Int,
  onClick: (CineMediaUi) -> Unit,
  onLongClick: (CineMediaUi) -> Unit,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space

  LazyVerticalGrid(
    columns = GridCells.Fixed(columns),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = space.screenH,
      end = space.screenH,
      top = 6.dp,
      bottom = space.contentBottomInset,
    ),
    horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    // 16 = 图下那一行元信息的行高 + 8dp 间距，少了它两行卡片会挤在一起
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    itemsIndexed(items) { _, media ->
      CinePosterCard(
        media = media,
        width = cardWidth,
        onClick = { onClick(media) },
        onLongClick = { onLongClick(media) },
      )
    }
  }
}
