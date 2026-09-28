package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch

/**
 * Emby 收藏页：展示服务器上标记为收藏的全部媒体。
 *
 * 交互与首页一致：**单击播放，长按查看详情**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyFavoritesScreen() {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val server by viewModel.currentServer.collectAsState()
  val scope = rememberCoroutineScope()

  val items = remember { mutableStateListOf<EmbyItem>() }
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  suspend fun load() {
    // 冷启动时当前服务器可能还没恢复，这里等一下，避免误判成「没有服务器」而空白
    val current = viewModel.currentServerOrAwait() ?: run {
      isLoading = false
      return
    }
    isLoading = true
    error = null
    runCatching { viewModel.loadFavorites(current, 0, 200) }
      .onSuccess { page ->
        items.clear()
        items.addAll(page.items)
      }
      .onFailure { error = it.message ?: "加载收藏失败" }
    isLoading = false
  }

  LaunchedEffect(server?.id) { load() }

  // Scaffold + TopAppBar：自动为状态栏留出安全区域，避免网格压在状态栏下
  Scaffold(
    topBar = {
      EmbyFavoritesTopBar(onRefresh = { scope.launch { load() } })
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when {
        // 加载尚未结束时不判定「没有服务器」，避免冷启动瞬间闪一下空状态
        server == null && !isLoading -> EmbyEmptyState(
          message = "还没有添加 Emby 服务器",
          buttonText = "添加服务器",
          onAction = { backStack.add(EmbyServerManageScreen) },
          modifier = Modifier.align(Alignment.Center),
        )

        isLoading && items.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        items.isEmpty() -> EmbyEmptyState(
          message = "还没有收藏任何媒体",
          buttonText = "刷新",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          LazyVerticalGrid(
            // 与媒体库页统一：固定一行三个（含「文件夹」分类下的宫格封面保持一致观感）
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            items(items, key = { it.Id ?: it.Name ?: "" }) { item ->
              val currentServer = server ?: return@items
              EmbyMediaCard(
                title = viewModel.displayTitle(item),
                subtitle = item.ProductionYear?.toString(),
                imageUrl = viewModel.imageUrl(currentServer, item, "Primary", 480),
                fallbackImageUrl = viewModel.imageUrl(currentServer, item, "Backdrop", 480),
                progress = itemProgressOf(item),
                isFavorite = true,
                onClick = {
                  val id = item.Id
                  if (id != null) backStack.add(EmbyDetailScreen(id, item.Name ?: ""))
                },
                style = EmbyCardStyle.POSTER,
                fillWidth = true,
              )
            }
          }
        }
      }
    }
  }
}

/** 收藏页顶部标题栏（供 MainScreen 复用统一的刷新入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyFavoritesTopBar(onRefresh: () -> Unit) {
  val backStack = LocalBackStack.current
  TopAppBar(
    title = { Text("收藏") },
    actions = {
      IconButton(onClick = onRefresh) {
        Icon(Icons.Default.Refresh, contentDescription = "刷新")
      }
      // 与首页保持一致：Emby 图标进服务器维护，齿轮进 App 设置
      EmbyMaintainButton(onClick = { backStack.add(EmbyServerManageScreen) })
      IconButton(onClick = { backStack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen) }) {
        Icon(Icons.Default.Settings, contentDescription = "设置")
      }
    },
  )
}

@Composable
private fun EmptyText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun itemProgressOf(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
