package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 首页顶栏。**两态叠在一起**，由 [collapse] 交叉淡化，不是两个条件分支 ——
 * 后者在阈值处会「弹」一下，前者是一条连续的过渡。
 *
 * ## 两态各管什么
 * - 展开态（[collapse] = 0）：品牌行 + 搜索胶囊，全部透明底，直接压在 Hero 图上；
 * - 折叠态（[collapse] = 1）：52dp 玻璃条 + 下方 1dp 描边，从顶部浮起，Hero 滚出后接手。
 *
 * `collapse = heroOffset / 120` 并 clamp 0..1（设计稿 05 的标注）：用 120 而不是 Hero 全高，
 * 是因为 Hero 有 500dp 以上，滚到一半才开始折叠的话，用户在 Hero 上滑时顶栏一直处于
 * 「半透明、字叠在图和图上」的中间态，观感很脏。
 *
 * ## 为什么两块都写在同一个 Box 里而不是 AnimatedContent
 * 交叉淡化期间两块必须**同时存在**（一块在淡出一块在淡入），AnimatedContent 会做
 * 尺寸/位置动画，反而让折叠中途出现缩放。这里只要 alpha + 一点 translationY。
 */
@Composable
fun CineTopBar(
  collapse: Float,
  title: String,
  onSearch: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val c = collapse.coerceIn(0f, 1f)
  val pillShape = remember { RoundedCornerShape(50) }

  Box(modifier = modifier.fillMaxWidth()) {
    // ── 折叠态：玻璃条（在最下层，展开时 alpha = 0） ──
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = c }
        .background(colors.glassSurface)
        .drawBehind {
          // 只画底边：整圈描边会让它看起来像一张浮卡，而它其实是一条贴边的栏
          val y = size.height - 1.dp.toPx()
          drawLine(
            color = colors.glassBorder,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
          )
        }
        .statusBarsPadding()
        .height(space.topBarHeight),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(space.topBarHeight)
          .padding(start = space.screenH, end = space.screenH - 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          style = type.itemTitle,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        // 折叠态的底是玻璃条（浅色主题下是浅色），图标必须走 onSurfaceVariant；
        // 写死白色时浅色主题下这两个按钮会彻底消失 —— 用户从此找不到搜索和设置入口。
        TopBarIconButton(
          icon = Icons.Outlined.Search,
          label = null,
          tint = colors.onSurfaceVariant,
          onClick = onSearch,
        )
        TopBarIconButton(
          icon = Icons.Outlined.Settings,
          label = null,
          tint = colors.onSurfaceVariant,
          onClick = onOpenSettings,
        )
      }
    }

    // ── 展开态：品牌行 + 搜索胶囊（压在图上的透明层） ──
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
          alpha = 1f - c
          translationY = -12.dp.toPx() * c
        }
        .statusBarsPadding()
        .padding(horizontal = space.screenH)
        .padding(top = 6.dp, bottom = 10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 品牌标记：一个小色块 + 名字。不用 logo 图 —— 图标在深浅主题下都要重新着色，
        // 而这一格只需要「这是哪个 App」的最小信息量。
        Box(
          modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colors.progressFill),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = title,
          style = type.itemTitle.copy(fontSize = 16.sp),
          color = colors.onImage,
          maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // 展开态压在 Hero 图上 ⇒ 白色图标 + Hero 顶部压暗层，两者配合才可读
        TopBarIconButton(
          icon = Icons.Outlined.Search,
          label = null,
          tint = colors.onImage,
          onClick = onSearch,
        )
        TopBarIconButton(
          icon = Icons.Outlined.Settings,
          label = null,
          tint = colors.onImage,
          onClick = onOpenSettings,
        )
      }

      Spacer(Modifier.height(8.dp))

      // 搜索胶囊是**入口不是输入框**：点一下进全屏搜索页。
      // 在首页就地展开输入框会把 Hero 的视觉重心抢走，而且软键盘弹起后 Hero 会被顶掉一半。
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp)
          .clip(pillShape)
          .background(colors.badgeGhostSurface)
          .border(1.dp, colors.badgeGhostBorder, pillShape)
          .clickable(onClick = onSearch)
          .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Search,
          contentDescription = null,
          tint = colors.onImageMuted,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = stringResource(R.string.cine_search_hint),
          style = type.meta,
          color = colors.onImageMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** 顶栏图标钮：34dp 圆形热区（低于 32dp 在真机上不好点），图标 19dp。 */
@Composable
private fun TopBarIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String?,
  tint: Color,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(34.dp)
      .clip(CircleShape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = tint,
      modifier = Modifier.size(19.dp),
    )
  }
}

/**
 * 宽屏（≥600dp）的静态顶栏。
 *
 * 宽屏上**不做折叠**：Hero 不再是「满屏出血 + 顶栏压在上面」的结构（内容区两侧有边距、
 * Hero 高度也降到 392dp），顶栏退回到普通的一行 —— 品牌 + 搜索 + 设置。
 * 折叠动效在宽屏反而会让一块 88dp 宽的 Rail 上方多出一条会滑动的横条，很乱。
 */
@Composable
fun CineWideHeader(
  title: String,
  onSearch: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val pillShape = remember { RoundedCornerShape(50) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .height(space.wideHeaderHeight)
      .padding(horizontal = space.screenH),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Box(
        modifier = Modifier
          .size(18.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(colors.progressFill),
      )
      Text(
        text = title,
        style = type.sectionTitle,
        color = colors.onSurface,
        maxLines = 1,
      )
    }

    Row(
      modifier = Modifier
        .weight(1f)
        .height(42.dp)
        .clip(pillShape)
        .background(colors.chipSurface)
        .border(1.dp, colors.chipBorder, pillShape)
        .clickable(onClick = onSearch)
        .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Search,
        contentDescription = null,
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(17.dp),
      )
      Text(
        text = stringResource(R.string.cine_search_hint),
        style = type.meta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // 宽屏顶栏在普通表面上，图标要用 onSurfaceVariant —— 白色在浅色主题下直接看不见
    Box(
      modifier = Modifier
        .size(42.dp)
        .clip(CircleShape)
        .clickable(onClick = onOpenSettings),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Outlined.Settings,
        contentDescription = null,
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
