package app.marlboroadvance.mpvex.ui.cine.components

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyBrandGreen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLogo
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 抽屉里可跳的功能入口。**刻意与老 UI AppBar 的功能全集一一对应** ——
 * 下载 / 离线 / 直播 / 播放列表 / 猜你喜欢 / 合集 / Emby 维护 / 设置，
 * 一个不少（搜索留在顶栏，它是高频交互且首页已有搜索胶囊）。
 */
enum class CineDrawerEntry {
  Downloads,
  Offline,
  LiveTv,
  Playlists,
  Discover,
  Collections,
  ServerManage,
  Settings,
}

/**
 * Cine 的侧边抽屉：把老 UI AppBar 里平铺的功能按钮收进一个玻璃面板，
 * 由首页顶栏左上角的菜单图标弹出。
 *
 * ## 形态
 * 左侧 300dp 玻璃面板（blur 26 + 投影 + 顶边高光）+ 压暗遮罩。面板从左滑入，
 * 遮罩淡入；点遮罩 / 返回键 / 点任意功能项都会收起。与 [CineSearchOverlay] 同族，
 * 都是「叠在 tab 之上的一层」，但抽屉是**半透明覆盖**而不是整页替换。
 *
 * ## 数据
 * 下载角标自己取 [EmbyDownloadViewModel]（与 Classic 顶栏 [app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadEntryButton]
 * 同一份任务队列）—— 覆盖层各自取 ViewModel，[app.marlboroadvance.mpvex.ui.cine.CineRoot] 继续不碰数据。
 *
 * ## ⚠️ 必须常驻组合
 * [visible] 传 false 时内部只留空盒子，进出场动画靠 AnimatedVisibility 播放。
 * 调用方**不能**写 `if (open) CineDrawerOverlay(...)` —— 那样关闭时组合直接消失，
 * 滑出动画永远不会播。与 [CineGridOverlay] 不同（那个是快照式覆盖层）。
 */
@Composable
fun CineDrawerOverlay(
  visible: Boolean,
  onDismiss: () -> Unit,
  onNavigate: (CineDrawerEntry) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val downloadViewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(app),
  )
  val embyViewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by embyViewModel.currentServer.collectAsState()
  val tasks by downloadViewModel.tasks.collectAsState()
  val downloadsActive = tasks.count { it.isActive }

  // 系统返回键先收抽屉（抽屉是叠在 tab 之上的一层）
  BackHandler(enabled = visible, onBack = onDismiss)

  Box(modifier = modifier.fillMaxSize()) {
    // ── 遮罩：点空白处关闭。无 indication —— 遮罩上的水波纹很脏 ──
    AnimatedVisibility(
      visible = visible,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(160)),
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.55f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
          ),
      )
    }

    // ── 玻璃面板：从左滑入 ──
    AnimatedVisibility(
      visible = visible,
      enter = slideInHorizontally(tween(260)) { -it },
      exit = slideOutHorizontally(tween(200)) { -it },
      modifier = Modifier.align(Alignment.CenterStart),
    ) {
      DrawerPanel(
        downloadsActive = downloadsActive,
        serverName = server?.name,
        onNavigate = onNavigate,
      )
    }
  }
}

/** 面板本体：头部（品牌 + 服务器名）+ 「媒体」「系统」两组功能项 */
@Composable
private fun DrawerPanel(
  downloadsActive: Int,
  serverName: String?,
  onNavigate: (CineDrawerEntry) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val panelShape = remember { RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp) }

  Column(
    modifier = Modifier
      .width(300.dp)
      .fillMaxHeight()
      // 面板承载的文字多，玻璃要比 dock/顶栏更「实」（flatAlpha 0.92），
      // 否则浅色主题下透出剧照会让正文读不清
      .cineGlass(
        shape = panelShape,
        elevation = 24.dp,
        blurRadius = 26.dp,
        flatAlpha = 0.92f,
      )
      .verticalScroll(rememberScrollState()),
  ) {
    // ── 头部：品牌块 + App 名 + 服务器名 ──
    Column(
      modifier = Modifier
        .statusBarsPadding()
        .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.progressFill),
        )
        Text(
          text = stringResource(R.string.app_name),
          style = type.pageTitle,
          color = colors.onSurface,
          maxLines = 1,
        )
      }
      Spacer(Modifier.height(4.dp))
      // 副标题放当前服务器名（用户最常问「现在连的是哪个」）；没连则给状态提示
      Text(
        text = serverName ?: stringResource(R.string.cine_search_no_server),
        style = type.meta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    DrawerDivider()

    DrawerSection(stringResource(R.string.cine_drawer_section_media))
    DrawerItem(
      icon = Icons.Default.Download,
      label = stringResource(R.string.cine_drawer_downloads),
      badge = downloadsActive,
      onClick = { onNavigate(CineDrawerEntry.Downloads) },
    )
    DrawerItem(
      icon = Icons.Outlined.CloudOff,
      label = stringResource(R.string.cine_drawer_offline),
      onClick = { onNavigate(CineDrawerEntry.Offline) },
    )
    DrawerItem(
      icon = Icons.Default.Tv,
      label = stringResource(R.string.cine_drawer_live_tv),
      onClick = { onNavigate(CineDrawerEntry.LiveTv) },
    )
    DrawerItem(
      icon = Icons.AutoMirrored.Filled.PlaylistPlay,
      label = stringResource(R.string.cine_drawer_playlists),
      onClick = { onNavigate(CineDrawerEntry.Playlists) },
    )
    DrawerItem(
      icon = Icons.Default.AutoAwesome,
      label = stringResource(R.string.cine_drawer_discover),
      onClick = { onNavigate(CineDrawerEntry.Discover) },
    )
    DrawerItem(
      icon = Icons.Default.Album,
      label = stringResource(R.string.cine_drawer_collections),
      onClick = { onNavigate(CineDrawerEntry.Collections) },
    )

    DrawerDivider()

    DrawerSection(stringResource(R.string.cine_drawer_section_system))
    // Emby 维护沿用官方 Logo + 品牌绿 —— 与 Classic 顶栏同一个视觉锚点，
    // 用户一眼认出这是服务器维护入口（设置用齿轮，两者不混）
    DrawerItem(
      icon = EmbyLogo,
      label = stringResource(R.string.cine_drawer_server_manage),
      iconTint = EmbyBrandGreen,
      onClick = { onNavigate(CineDrawerEntry.ServerManage) },
    )
    DrawerItem(
      icon = Icons.Outlined.Settings,
      label = stringResource(R.string.cine_drawer_settings),
      onClick = { onNavigate(CineDrawerEntry.Settings) },
    )

    Spacer(Modifier.height(28.dp))
  }
}

/** 组标题：小号强调字，与正文拉开层级（设计稿 t-cap 的用法） */
@Composable
private fun DrawerSection(text: String) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  Text(
    text = text,
    style = type.badge,
    color = colors.onSurfaceVariant,
    maxLines = 1,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
  )
}

/** 抽屉项：52dp 行高 + 14dp 圆角热区。[badge] > 0 时在行尾显示活跃任务数 */
@Composable
private fun DrawerItem(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  badge: Int = 0,
  iconTint: Color? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = 2.dp)
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp)
      .height(48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = iconTint ?: colors.onSurface,
      modifier = Modifier.size(21.dp),
    )
    Text(
      text = label,
      style = type.body,
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (badge > 0) {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .background(colors.dangerContainer)
          .padding(horizontal = 7.dp, vertical = 2.dp),
      ) {
        Text(
          text = if (badge > 9) "9+" else badge.toString(),
          style = type.badge,
          color = colors.onDangerContainer,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun DrawerDivider() {
  val colors = CineTheme.colors
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp)
      .height(1.dp)
      .background(colors.glassBorder),
  )
}
