package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyPosterCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard

/**
 * Emby 首页。
 *
 * 结构参照 Emby 官方客户端：
 * 1. 顶部横向媒体库卡片（电影 / 剧集 / 音乐 …）
 * 2. 继续观看（横向宽卡片）
 * 3. 最新加入（横向海报卡片）
 *
 * 交互约定：**单击直接播放，长按查看媒体详情**。
 *
 * @param onOpenLibrary 点击媒体库入口（下钻到库内）
 * @param onOpenDetail 长按媒体项：打开详情页
 * @param onManageServers 打开 Emby 服务器维护（顶栏用 Emby 官方图标标识）
 * @param onOpenSettings 打开 App 设置页（顶栏齿轮）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyHomeScreen(
  onOpenLibrary: (EmbyItem) -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  onManageServers: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as android.app.Application),
  )

  val server by viewModel.currentServer.collectAsState()
  val libraries by viewModel.libraries.collectAsState()
  val resumeItems by viewModel.resumeItems.collectAsState()
  val latestItems by viewModel.latestItems.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()
  val servers by viewModel.servers.collectAsState()

  var serverMenuExpanded by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxSize()) {
    TopAppBar(
      title = {
        Column {
          Text(
            text = server?.name ?: "Emby",
            style = MaterialTheme.typography.titleLarge,
          )
          if (server != null && server?.isLoggedIn != true) {
            Text(
              text = "未登录",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      actions = {
        Box {
          IconButton(onClick = { serverMenuExpanded = true }) {
            Icon(Icons.Default.Dns, contentDescription = "切换服务器")
          }
          DropdownMenu(
            expanded = serverMenuExpanded,
            onDismissRequest = { serverMenuExpanded = false },
          ) {
            if (servers.isEmpty()) {
              DropdownMenuItem(
                text = { Text("暂无服务器") },
                onClick = { serverMenuExpanded = false },
              )
            }
            servers.forEach { s ->
              DropdownMenuItem(
                text = {
                  Text(
                    text = buildString {
                      append(s.name)
                      if (s.id == server?.id) append(" ✓")
                    },
                    color = if (s.id == server?.id) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                  )
                },
                onClick = {
                  serverMenuExpanded = false
                  viewModel.switchServer(s)
                },
              )
            }
            DropdownMenuItem(
              text = { Text("管理服务器…") },
              onClick = {
                serverMenuExpanded = false
                onManageServers()
              },
            )
          }
        }
        // Emby 维护：用官方 Emby 图标（品牌绿），与下方齿轮明确区分
        EmbyMaintainButton(onClick = onManageServers)
        // 设置：统一进 App 设置页（播放器 / 外观 / 解码等偏好都在这里）
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Default.Settings, contentDescription = "设置")
        }
      },
    )

    when {
      // 加载尚未结束时不判定「没有服务器」，避免冷启动（服务器还在异步恢复）闪一下空状态
      server == null && !isLoading -> EmbyEmptyState(
        message = "还没有添加 Emby 服务器",
        buttonText = "添加服务器",
        onAction = onManageServers,
      )

      error != null && libraries.isEmpty() -> EmbyEmptyState(
        message = error ?: "加载失败",
        buttonText = "重试",
        onAction = { viewModel.refreshHome() },
      )

      isLoading && libraries.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }

      else -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 96.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (libraries.isNotEmpty()) {
            item {
              EmbySectionHeader(
                title = "媒体库",
                modifier = Modifier.padding(top = 10.dp),
              )
              LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(libraries, key = { it.Id ?: it.Name ?: "" }) { library ->
                  EmbyLibraryCard(
                    name = library.Name ?: "",
                    itemCount = library.ChildCount,
                    imageUrl = viewModel.imageUrl(server!!, library, "Primary", 300),
                    icon = libraryIcon(library.CollectionType),
                    onClick = { onOpenLibrary(library) },
                  )
                }
              }
            }
          }

          if (resumeItems.isNotEmpty()) {
            item {
              EmbySectionHeader(title = "继续观看")
              LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(resumeItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                  EmbyWideCard(
                    title = viewModel.displayTitle(item),
                    subtitle = item.SeriesName ?: item.ProductionYear?.toString(),
                    imageUrl = viewModel.imageUrl(server!!, item, "Backdrop", 640),
                    fallbackImageUrl = viewModel.imageUrl(server!!, item, "Primary", 640),
                    progress = playbackProgress(item),
                    isFavorite = item.UserData?.IsFavorite == true,
                    // 单击进入详情页，播放由详情页发起
                    onClick = { onOpenDetail(item) },
                  )
                }
              }
            }
          }

          if (latestItems.isNotEmpty()) {
            item {
              EmbySectionHeader(title = "最新加入")
              LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(latestItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                  EmbyPosterCard(
                    title = viewModel.displayTitle(item),
                    subtitle = item.ProductionYear?.toString(),
                    imageUrl = viewModel.imageUrl(server!!, item, "Primary", 480),
                    // 单集等条目可能没有 Primary（海报），回退到 Thumb（剧照/缩略图）
                    fallbackImageUrl = viewModel.imageUrl(server!!, item, "Thumb", 480),
                    progress = playbackProgress(item),
                    isFavorite = item.UserData?.IsFavorite == true,
                    // 单击进入详情页，播放由详情页发起
                    onClick = { onOpenDetail(item) },
                  )
                }
              }
            }
          }

          if (libraries.isEmpty() && resumeItems.isEmpty() && latestItems.isEmpty() && !isLoading) {
            item {
              EmbyEmptyState(
                message = "这个服务器上还没有可显示的媒体",
                buttonText = "刷新",
                onAction = { viewModel.refreshHome() },
                modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
              )
            }
          }
        }
      }
    }
  }
}

/** 计算播放进度（0f~1f）；没有时长信息时返回 null */
private fun playbackProgress(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** 按媒体库类型挑选图标 */
private fun libraryIcon(collectionType: String?): ImageVector = when (collectionType) {
  "tvshows" -> Icons.Default.Tv
  else -> Icons.Default.PhotoLibrary
}

@Composable
fun EmbyEmptyState(
  message: String,
  buttonText: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Dns,
      contentDescription = null,
      modifier = Modifier.size(64.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = message,
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onAction) {
      Text(buttonText)
    }
  }
}

@Composable
fun EmbyErrorBanner(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = message,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    TextButton(onClick = onRetry) {
      Text("重试")
    }
  }
}

/** 把 tick 时长转成「1小时23分」这类中文可读文本 */
fun formatDuration(ticks: Long?): String {
  val totalMinutes = EmbyTicks.ticksToSeconds(ticks ?: 0) / 60
  if (totalMinutes <= 0) return ""
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
