package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 底部 / 侧边导航的五个入口。
 *
 * ⚠️ 顺序与 Classic 的底部导航**必须一致**（首页 / 收藏 / 历史 / 本地 / 网络）：
 * 用户在两套界面之间切换时，肌肉记忆（第几个是收藏）不该变。
 *
 * 只有 [Home] 有 Cine 版本页面，其余四个暂时回落到 Classic 的整页实现
 * （见 `CineRoot`）—— 所以这一层的职责只有「切哪个 tab」，不管内容怎么画。
 */
enum class CineTab(
  val labelRes: Int,
  val icon: ImageVector,
) {
  Home(R.string.cine_nav_home, Icons.Rounded.Home),
  Favorites(R.string.cine_nav_favorites, Icons.Rounded.Bookmark),
  History(R.string.cine_nav_history, Icons.Rounded.History),
  Local(R.string.cine_nav_local, Icons.Rounded.Folder),
  Network(R.string.cine_nav_network, Icons.Rounded.Cloud),
}

/**
 * 手机端悬浮 Dock。
 *
 * ## 为什么是「胶囊浮起」而不是贴底的 NavigationBar
 * Cine 首页的 Hero 是满宽出血的，任何贴底实心条都会在视觉上把画面切断；
 * 浮起的胶囊让内容从它下面穿过去，页面还是一整块。
 *
 * 代价是**内容必须自己给底部留空**（[app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing.contentBottomInset]），
 * 否则最后一行会永远压在胶囊下面 —— 这一点在 `CineRoot` 里通过
 * `LocalNavigationBarHeight` 下发给回落进 Cine 外壳的 Classic 页面。
 *
 * @param hidden 下滚时收起（设计稿 05）。**只平移不缩小**：缩放会让胶囊里的
 *   文字重新排版，收起来的一瞬间能看到字在跳。
 */
@Composable
fun CineDock(
  selected: CineTab,
  onSelect: (CineTab) -> Unit,
  hidden: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val space = CineTheme.space
  val capsule = remember { RoundedCornerShape(32.dp) }
  val shift by animateFloatAsState(
    targetValue = if (hidden) 1f else 0f,
    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    label = "cineDockShift",
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = space.dockInset, vertical = space.dockBottom)
      .graphicsLayer {
        translationY = (size.height + 18.dp.toPx()) * shift
        alpha = 1f - shift
      },
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(space.dockHeight)
        .clip(capsule)
        .background(colors.glassSurface)
        .border(1.dp, colors.glassBorder, capsule)
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      CineTab.entries.forEach { tab ->
        DockItem(
          tab = tab,
          selected = tab == selected,
          onClick = { onSelect(tab) },
        )
      }
    }
  }
}

@Composable
private fun DockItem(
  tab: CineTab,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val label = stringResource(tab.labelRes)

  Row(
    modifier = Modifier
      .height(44.dp)
      .clip(CircleShape)
      .background(if (selected) colors.chipSelected else androidx.compose.ui.graphics.Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = if (selected) 13.dp else 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = tab.icon,
      contentDescription = label,
      tint = if (selected) colors.onChipSelected else colors.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
    // 文字只在选中时出现：Dock 上五个都带字会挤成一条导航条，失去「浮起」的轻量感
    AnimatedVisibility(
      visible = selected,
      enter = expandHorizontally(animationSpec = tween(160)) + fadeIn(tween(120)),
      exit = shrinkHorizontally(animationSpec = tween(140)) + fadeOut(tween(90)),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(7.dp))
        Text(
          text = label,
          style = type.cardMeta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.W600),
          color = colors.onChipSelected,
          maxLines = 1,
          // Ellipsis 而不是 Clip：英文（Favorites / Network）比中文长得多，
          // 窄屏 + 大字号设置下可能放不下 —— 宁可看到「Favori…」也不要被硬切一半。
          // widthIn 是硬上限：Dock 是 SpaceEvenly 布局，某一项无限变宽会顶破胶囊。
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthIn(max = 76.dp),
        )
      }
    }
  }
}

/**
 * 宽屏（≥600dp）左侧 Rail。
 *
 * 与 [CineDock] 是同一份数据、同一个选中态，**不是两套导航逻辑** ——
 * 二分的只有「摆哪儿」。
 */
@Composable
fun CineRail(
  selected: CineTab,
  onSelect: (CineTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val space = CineTheme.space

  Column(
    modifier = modifier
      .width(space.railWidth)
      .fillMaxHeight()
      .background(colors.glassSurface)
      .drawBehind {
        val x = size.width - 1.dp.toPx()
        drawLine(
          color = colors.glassBorder,
          start = Offset(x, 0f),
          end = Offset(x, size.height),
          strokeWidth = 1.dp.toPx(),
        )
      }
      .statusBarsPadding()
      .padding(vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    CineTab.entries.forEach { tab ->
      RailItem(
        tab = tab,
        selected = tab == selected,
        onClick = { onSelect(tab) },
      )
    }
  }
}

@Composable
private fun RailItem(
  tab: CineTab,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val label = stringResource(tab.labelRes)
  val shape = remember { RoundedCornerShape(16.dp) }

  Column(
    modifier = Modifier
      .width(64.dp)
      .clip(shape)
      .background(if (selected) colors.chipSelected else androidx.compose.ui.graphics.Color.Transparent)
      .clickable(onClick = onClick)
      .padding(vertical = 9.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = tab.icon,
      contentDescription = label,
      tint = if (selected) colors.onChipSelected else colors.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
    Text(
      text = label,
      style = type.badge.copy(fontSize = 10.sp),
      color = if (selected) colors.onChipSelected else colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
