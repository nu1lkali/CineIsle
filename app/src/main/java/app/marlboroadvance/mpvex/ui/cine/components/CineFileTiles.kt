package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

/**
 * 非 Emby 内容（本地文件夹 / 网络目录 / 直播频道 / 下载任务）的通用卡片。
 *
 * ## 为什么另起一套而不是塞进 [CinePosterCard]
 * `CinePosterCard` 吃的是 `CineMediaUi`（背后必须是一条 `EmbyItem`）—— 本地文件、
 * 网络目录、电视频道都没有 Emby 条目，硬造一个空 `EmbyItem` 等于把「数据模型」
 * 变成「渲染参数」，以后任何字段映射都会在这条假路径上出错。
 * 所以这里只做**与数据来源无关**的那一层：一块 16:9 的图区 + 图下一行标题 + 一行元信息。
 */

/** 玻璃卡片容器：`glass` 底 + 1dp 描边 + 22dp 圆角，页面里的「一块内容」都用它。 */
@Composable
fun CineGlassPanel(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = CineTheme.colors
  val shape = remember { RoundedCornerShape(22.dp) }

  Box(
    modifier = modifier
      .clip(shape)
      .background(colors.chipSurface.copy(alpha = 0.6f))
      .border(1.dp, colors.glassBorder, shape),
  ) {
    content()
  }
}

/**
 * 图区 + 标题的竖排瓦片（本地文件夹 / 频道 / 网络目录）。
 *
 * @param imageUrl 有图就铺满（频道台标、网络目录封面）；没有则用 [icon] 顶在中间。
 *   两者都不给会留一块空底色 —— 调用方至少传一个。
 * @param badge 右上角角标（「12 集」「12.1 频道」「新」）
 */
@Composable
fun CineCoverTile(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  imageUrl: String? = null,
  icon: ImageVector? = null,
  badge: String? = null,
  aspectRatio: Float = 16f / 9f,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = LocalCardCorner.current
  val shape = remember(corner) { RoundedCornerShape(corner) }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(aspectRatio)
        .clip(shape)
        .background(colors.skeleton)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
      contentAlignment = Alignment.Center,
    ) {
      if (imageUrl != null) {
        EmbyImage(
          url = imageUrl,
          contentDescription = title,
          modifier = Modifier.fillMaxSize(),
          maxWidth = 480,
          contentScale = ContentScale.Crop,
          placeholder = icon ?: Icons.Filled.Folder,
        )
      } else {
        Icon(
          imageVector = icon ?: Icons.Filled.Folder,
          contentDescription = null,
          tint = colors.onSurfaceVariant,
          modifier = Modifier.size(34.dp),
        )
      }

      if (badge != null) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(7.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.badgeStrong)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
          Text(
            text = badge,
            style = type.badge,
            color = colors.onBadgeStrong,
            maxLines = 1,
          )
        }
      }
    }

    Spacer(Modifier.height(7.dp))
    Text(
      text = title,
      style = type.posterTitle,
      color = colors.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * 横排一行（文件管理器 / 下载任务 / 节目单）。
 *
 * @param trailing 右侧尾部（进度、开关、箭头）；行本身已经吃满宽度并自行处理点击
 */
@Composable
fun CineFileRow(
  title: String,
  meta: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector = Icons.Filled.Folder,
  iconTint: Color? = null,
  badge: String? = null,
  onLongClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(14.dp) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .pressDownScale()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 4.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(colors.chipSurface),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint ?: colors.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = title,
          style = type.itemTitle,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
          Spacer(Modifier.size(6.dp))
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(colors.badgeStrong)
              .padding(horizontal = 6.dp, vertical = 1.dp),
          ) {
            Text(text = badge, style = type.badge, color = colors.onBadgeStrong, maxLines = 1)
          }
        }
      }
      Text(
        text = meta,
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (trailing != null) {
      Row(
        modifier = Modifier.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        trailing()
      }
    }
  }
}

/**
 * 玻璃胶囊搜索框。
 *
 * 不用 `OutlinedTextField`：它自带 56dp 高度与浮动标签，放进 Cine 的 44dp 顶栏体系里
 * 会显得「厚一层」；这里只需要「一个能打字的胶囊」。
 */
@Composable
fun CineSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "搜索…",
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }
  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(44.dp)
      .clip(shape)
      .background(colors.chipSurface)
      .border(1.dp, colors.chipBorder, shape)
      .padding(start = 14.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.Search,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
    Box(
      modifier = Modifier
        .weight(1f)
        .padding(start = 10.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      if (query.isEmpty()) {
        Text(text = placeholder, style = type.body, color = colors.onSurfaceVariant)
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = type.body.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.progressFill),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester),
      )
    }
    CineIconButton(
      icon = Icons.Filled.Close,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      onClick = {
        keyboard?.hide()
        onClose()
      },
      size = 34.dp,
      iconSize = 17.dp,
    )
  }
}

/**
 * 页脚占位：Cine 的 tab 页底部有一条浮起的 Dock（96dp），滚到最底的内容
 * 若不留白就会被 Dock 压住 —— 每个 tab 页的 `contentPadding.bottom` 都要加它。
 */
@Composable
fun CineDockSpacer() {
  val space = CineTheme.space
  Spacer(Modifier.height(space.contentBottomInset))
}
