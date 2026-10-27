package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

/**
 * Cine 二级页（媒体库 / 收藏 / 历史 / 详情）的通用外壳。
 *
 * ## 为什么单独一层
 * M1 只有首页，组件都是「为一个页面服务」的。一旦有四个二级页，每页各写一遍顶栏、
 * 各写一遍海报网格，必然出现「媒体库 2 列、收藏 3 列、圆角 12 和 18 混着」这种走样。
 * 这里只放**跨页面必须一致**的那几件东西：顶栏、网格、列表行。
 *
 * ## 顶栏为什么是 44dp
 * 设计稿 8.1 的二级页顶栏是 44dp（比首页折叠态的 52dp 更矮）：这些页面没有 Hero，
 * 顶栏一旦占掉 52dp，一屏能看到的片子就少一行 —— 二级页是「翻库」场景，密度优先。
 */

/** 顶栏 / 浮层上的圆形图标按钮。40dp 热区是拇指可点的下限。 */
@Composable
fun CineIconButton(
  icon: ImageVector,
  contentDescription: String?,
  tint: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  iconSize: Dp = 20.dp,
) {
  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(iconSize),
    )
  }
}

/**
 * 二级页的玻璃顶栏：`返回 + 标题 + 动作`。
 *
 * ⚠️ 图标色一律走 `onSurfaceVariant`，**不能**写 `onImage`（纯白）：
 * 这条顶栏的底是玻璃条（浅色主题下是浅色的），白图标会彻底消失 ——
 * 折叠顶栏踩过一次，这里不重犯。
 */
@Composable
fun CineGlassTopBar(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        // 真模糊（设计稿 8.1 的二级页顶栏同样是玻璃）。这一条贴边，所以不投外影：
        // 满宽的投影会在左右屏幕边缘留下一圈灰边
        .cineGlass(
          shape = RectangleShape,
          elevation = 0.dp,
          blurRadius = 20.dp,
          border = false,
        )
        .drawBehind {
          // 只用一条底边收口：整圈描边会让它看起来像一张浮卡，而它其实是一条贴边的栏
          val y = size.height - 1.dp.toPx()
          drawLine(
            color = colors.glassBorder,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
          )
        }
        .statusBarsPadding()
        .height(space.secondaryBarHeight)
        .padding(start = 4.dp, end = space.screenH - 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onBack != null) {
        CineIconButton(
          icon = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = null,
          tint = colors.onSurface,
          onClick = onBack,
          size = 40.dp,
        )
      } else {
        Spacer(Modifier.width(space.screenH - 4.dp))
      }
      Text(
        text = title,
        style = type.pageBarTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .padding(start = 2.dp),
      )
      actions()
    }
    // 1dp 分隔线：玻璃条与内容同色时，没有它顶栏像「浮在内容上的半透明块」
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(colors.glassBorder),
    )
  }
}

/** 详情页左上角的浮动返回键：压在 Backdrop 上，所以走玻璃底 + 白图标。 */
@Composable
fun CineFloatingBackButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors

  Box(
    modifier = modifier
      .size(40.dp)
      // 设计稿 .bd-btn：`rgba(8,10,14,.46)` + blur(12px)。压在剧照上的圆钮必须**取暗色 tint**，
      // 用表面色 tint 时浅色主题下会在图上糊出一块发白的斑
      .cineGlass(
        shape = CircleShape,
        elevation = 0.dp,
        blurRadius = 12.dp,
        flatAlpha = 0.46f,
        tintOverride = Color.Black.copy(alpha = 0.42f),
        borderColor = Color.White.copy(alpha = 0.22f),
      )
      .pressDownScale()
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
      contentDescription = null,
      tint = colors.onImage,
      modifier = Modifier.size(20.dp),
    )
  }
}

/**
 * 统计 + 排序行（设计稿 8.1 的「1,284 部 · 按最近加入 | 排序」）。
 *
 * 排序入口用 `primary` 文字而不是按钮：这一行本身是弱信息，配一颗实心按钮会把
 * 视觉重心从海报上抢走。
 */
@Composable
fun CineStatsRow(
  countText: String,
  sortText: String,
  onSortClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = 14.dp, bottom = 12.dp)
      .padding(horizontal = space.screenH),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = countText,
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(50))
        .clickable(onClick = onSortClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.SwapVert,
        contentDescription = null,
        tint = colors.sectionAction,
        modifier = Modifier.size(15.dp),
      )
      Text(
        text = sortText,
        style = type.cardMeta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.W600),
        color = colors.sectionAction,
        maxLines = 1,
      )
    }
  }
}

/**
 * 海报网格（媒体库 / 收藏 / 历史 / 详情页的「全部」共用）。
 *
 * 列数由调用方按屏宽算好传进来 —— 与首页横滑行里同一个 [CinePosterCard]，
 * 所以标题自适应、角标、进度条的表现完全一致。
 *
 * @param header 跨满整行的头部（分类 Chip 行、统计行）；放进网格而不是外包一层 Column，
 *   是为了让头部跟着网格一起滚走，而不是永远占着顶部
 */
@Composable
fun CinePosterGrid(
  items: List<CineMediaUi>,
  cardWidth: Dp,
  columns: Int,
  state: LazyGridState,
  contentPadding: PaddingValues,
  onClick: (CineMediaUi) -> Unit,
  onLongClick: ((CineMediaUi) -> Unit)? = null,
  modifier: Modifier = Modifier,
  header: (@Composable () -> Unit)? = null,
  footer: (@Composable () -> Unit)? = null,
) {
  val space = CineTheme.space

  LazyVerticalGrid(
    columns = GridCells.Fixed(columns),
    state = state,
    // 网格是这些页面的滚动容器 ⇒ 同时充当玻璃层的模糊源（顶栏 / 长按菜单取它做底）。
    // 放在这里而不是每个调用点：调用点漏一个，那页的玻璃就会静默退化成纯色。
    modifier = modifier
      .fillMaxSize()
      .cineHazeSource(LocalCineHaze.current),
    contentPadding = contentPadding,
    horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    // 18 = 图下那一行元信息（15dp 行高）+ 8dp 间距；小了会挤在一起，大了像断页
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    if (header != null) {
      item(span = { GridItemSpan(maxLineSpan) }, key = "cine-grid-header") { header() }
    }
    itemsIndexed(items, key = { index, media -> media.id.ifBlank { "idx-$index" } }) { _, media ->
      CinePosterCard(
        media = media,
        width = cardWidth,
        onClick = { onClick(media) },
        onLongClick = onLongClick?.let { { it(media) } },
      )
    }
    if (footer != null) {
      item(span = { GridItemSpan(maxLineSpan) }, key = "cine-grid-footer") { footer() }
    }
  }
}

/**
 * 列表模式的单行（一屏能扫到更多标题，代价是丢掉海报的视觉冲击）。
 *
 * 长按行为与网格一致：调用方给的是同一个 `onLongClick`。
 */
@Composable
fun CineMediaListRow(
  media: CineMediaUi,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val corner = LocalCardCorner.current
  val shape = remember(corner) { RoundedCornerShape(corner) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .pressDownScale()
      // 长按 = 玻璃菜单；不传就是纯单击（combinedClickable 允许 onLongClick 为 null）
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = space.screenH, vertical = 8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .width(72.dp)
        .height(108.dp)
        .clip(shape)
        .background(colors.skeleton),
    ) {
      EmbyImage(
        url = media.posterUrl ?: media.thumbUrl,
        contentDescription = media.title,
        modifier = Modifier.fillMaxSize(),
        maxWidth = 240,
      )
      media.progress?.let { progress ->
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(3.dp)
            .background(colors.onImageTrack),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(progress.coerceIn(0f, 1f))
              .height(3.dp)
              .background(colors.progressFill),
          )
        }
      }
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp),
    ) {
      Text(
        text = media.title,
        style = type.itemTitle,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(5.dp))
      Text(
        text = media.meta,
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // 简介只在列表模式里给：一行的空间够，网格里给不下
      val overview = media.item.Overview?.trim().orEmpty()
      if (overview.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
          text = overview,
          style = type.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
          color = colors.onSurfaceVariant.copy(alpha = 0.82f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
