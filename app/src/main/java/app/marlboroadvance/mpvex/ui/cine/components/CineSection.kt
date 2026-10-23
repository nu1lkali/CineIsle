package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

/** 分区动作图标：换一批 */
val CineRefreshIcon: ImageVector get() = Icons.Rounded.Refresh

/** 分区动作图标：全部 / 尖角 */
val CineChevronIcon: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight

/** 分区标题右侧的动作（`全部` / `换一批`）。不用 Button —— 它太宽，会抢走标题的重量。 */
@Composable
fun CineSectionAction(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = label,
      style = type.cardMeta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.W600),
      color = colors.sectionAction,
      maxLines = 1,
    )
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = colors.sectionAction,
        modifier = Modifier.size(13.dp),
      )
    }
  }
}

/**
 * 分区标题：`titleLarge` + 可选计数 + 右侧动作。
 *
 * @param count 紧跟在标题后的弱色计数（`影片 · 4`）
 * @param contentPadding 默认按 [CineSpacing.screenH] 左右内缩；横滑行外面自己已经带
 *   内边距的场合（如搜索结果的网格）可传别的值
 */
@Composable
fun CineSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  count: Int? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  actionIcon: ImageVector? = null,
  contentPadding: PaddingValues? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val horizontal = contentPadding ?: PaddingValues(horizontal = space.screenH)

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal)
      .padding(bottom = space.sectionHeadGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 标题 + 计数占「除动作以外的全部宽度」：这样超长标题会在动作之前省略，
    // 而不是把动作挤出屏幕（老界面踩过这个坑：标题一长，右侧按钮就没了）
    Row(
      modifier = Modifier
        .weight(1f)
        .padding(end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = type.sectionTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (count != null) {
        Spacer(Modifier.width(6.dp))
        Text(
          text = "· $count",
          style = type.cardMeta.copy(fontSize = 13.sp),
          color = colors.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
    if (actionLabel != null && onAction != null) {
      CineSectionAction(label = actionLabel, onClick = onAction, icon = actionIcon)
    }
  }
}

/** 筛选 Chip（分类 / 类型）。选中态走 `primaryContainer`，无描边。 */
@Composable
fun CineFilterChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = modifier
      .height(32.dp)
      .clip(shape)
      .background(if (selected) colors.chipSelected else colors.chipSurface)
      .then(if (selected) Modifier else Modifier.border(1.dp, colors.chipBorder, shape))
      .pressDownScale()
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = type.cardMeta.copy(fontSize = 12.5.sp),
      color = if (selected) colors.onChipSelected else colors.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

/**
 * 媒体库入口 Chip（首页 Hero 下方那一行）。
 *
 * ## 为什么要有它（设计稿里没有）
 * Cine 首页的设计稿是 Hero + 三个内容分区，**没有媒体库入口**。但媒体库是 Emby 里
 * 唯一能「按库浏览」的路径，去掉它用户就只能看首页推荐。Chip 是这套语言里已有的元素
 * （分类 / 筛选同款），用它承载媒体库，既补上可达性，又不会多出一行权重超过 Hero 的卡片。
 *
 * @param badge 计数等附注（聚合库的「N 库聚合」）
 */
@Composable
fun CineLibraryChip(
  name: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  badge: String? = null,
  pinned: Boolean = false,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = modifier
      .height(40.dp)
      .clip(shape)
      .background(colors.chipSurface)
      .border(1.dp, colors.chipBorder, shape)
      .pressDownScale()
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (pinned) colors.progressFill else colors.onSurfaceVariant,
      modifier = Modifier.size(16.dp),
    )
    // 库名是用户自己起的，长度不可控：上限 140dp + 省略号。
    // 用 widthIn 而不是 width —— 后者会让每个 Chip 一样宽，短名字后面拖一大块空白。
    Text(
      text = name,
      style = type.cardMeta.copy(fontSize = 13.sp),
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 140.dp),
    )
    if (badge != null) {
      Text(
        text = badge,
        style = type.badge.copy(fontSize = 9.5.sp),
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}
