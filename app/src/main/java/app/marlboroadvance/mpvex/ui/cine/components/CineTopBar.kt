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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Search
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
 * - 展开态（[collapse] = 0）：菜单钮 + 品牌行 + 搜索胶囊，全部透明底，直接压在 Hero 图上；
 * - 折叠态（[collapse] = 1）：52dp 玻璃条 + 下方 1dp 描边，从顶部浮起，Hero 滚出后接手。
 *
 * 左上角的菜单图标弹出侧边抽屉（[CineDrawerOverlay]）—— 老 UI AppBar 里的下载 / 维护 /
 * 直播 / 播放列表等功能全收在那里，顶栏只留「菜单 + 搜索」两个动作。
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
  onOpenDrawer: () -> Unit,
  onSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val c = collapse.coerceIn(0f, 1f)
  val pillShape = remember { RoundedCornerShape(50) }
  val barShape = remember { RoundedCornerShape(18.dp) }

  Box(modifier = modifier.fillMaxWidth()) {
    // ── 折叠态：浮起的玻璃条（在最下层，展开时 alpha = 0） ──
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = c }
        .statusBarsPadding()
        .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(space.topBarHeight)
          // 设计稿 .topbar-mini：**浮起**的 18dp 圆角玻璃条，blur(20px) + `0 8px 26px` 投影，
          // 不是贴满屏宽的实心栏。v131 之前这里是满宽 + 只画底边，收起来时像一条色带，
          // 和设计稿「一张卡片浮在内容上」的感觉完全不同。
          .cineGlass(shape = barShape, elevation = 8.dp, blurRadius = 20.dp)
          // 设计稿 .topbar-mini 的内边距是 `0 8px 0 16px`：文字侧宽、图标侧窄
          .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 折叠态的底是玻璃条（浅色主题下是浅色），图标必须走 onSurfaceVariant；
        // 写死白色时浅色主题下这些按钮会彻底消失 —— 用户从此找不到抽屉和搜索入口。
        TopBarIconButton(
          icon = Icons.Default.Menu,
          label = null,
          tint = colors.onSurfaceVariant,
          onClick = onOpenDrawer,
        )
        Text(
          text = title,
          style = type.itemTitle,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        TopBarIconButton(
          icon = Icons.Outlined.Search,
          label = null,
          tint = colors.onSurfaceVariant,
          onClick = onSearch,
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
        // 展开态压在 Hero 图上 ⇒ 白色图标 + Hero 顶部压暗层，两者配合才可读
        TopBarIconButton(
          icon = Icons.Default.Menu,
          label = null,
          tint = colors.onImage,
          onClick = onOpenDrawer,
        )
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
        TopBarIconButton(
          icon = Icons.Outlined.Search,
          label = null,
          tint = colors.onImage,
          onClick = onSearch,
        )
      }

      Spacer(Modifier.height(8.dp))

      // 搜索胶囊是**入口不是输入框**：点一下进全屏搜索页。
      // 在首页就地展开输入框会把 Hero 的视觉重心抢走，而且软键盘弹起后 Hero 会被顶掉一半。
      Row(
        modifier = Modifier
          .fillMaxWidth()
          // 设计稿 .searchpill 是 44dp（v131 做成了 40，比设计稿矮一档，按上去手感偏薄）
          .height(44.dp)
          // 压在剧照上的玻璃要换**白色** tint（设计稿 rgba(255,255,255,.14)）：
          // 用表面色会在深色主题下把剧照压得更暗，看着像块污渍
          .cineGlass(
            shape = pillShape,
            elevation = 0.dp,
            blurRadius = 16.dp,
            flatAlpha = 0.18f,
            tintOverride = colors.badgeGhostSurface,
            borderColor = colors.badgeGhostBorder,
          )
          .clickable(onClick = onSearch)
          .padding(horizontal = 16.dp),
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
 * Hero 高度也降到 392dp），顶栏退回到普通的一行 —— 菜单 + 品牌 + 搜索。
 * 折叠动效在宽屏反而会让一块 88dp 宽的 Rail 上方多出一条会滑动的横条，很乱。
 */
@Composable
fun CineWideHeader(
  title: String,
  onOpenDrawer: () -> Unit,
  onSearch: () -> Unit,
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
    // 左上角菜单图标：弹侧边抽屉（下载 / 维护 / 直播 / 设置等功能都收在里面）
    Box(
      modifier = Modifier
        .size(42.dp)
        .clip(CircleShape)
        .clickable(onClick = onOpenDrawer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Menu,
        contentDescription = null,
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }

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
  }
}
