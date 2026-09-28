package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch

/**
 * Emby 历史记录页。
 *
 * 上半部分「继续观看」（服务器记录的播放进度），下半部分「播放历史」（已看过的媒体）。
 * 交互：**单击播放，长按查看详情**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyHistoryScreen() {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val server by viewModel.currentServer.collectAsState()
  val scope = rememberCoroutineScope()

  val resumeItems = remember { mutableStateListOf<EmbyItem>() }
  val historyItems = remember { mutableStateListOf<EmbyItem>() }
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
    runCatching {
      val resume = viewModel.loadResume(current, 40)
      val history = viewModel.loadHistory(current, 0, 100)
      resume to history
    }.onSuccess { (resume, history) ->
      resumeItems.clear()
      resumeItems.addAll(resume)
      historyItems.clear()
      historyItems.addAll(history.items)
    }.onFailure {
      error = it.message ?: "加载历史失败"
    }
    isLoading = false
  }

  LaunchedEffect(server?.id) { load() }

  // Scaffold + TopAppBar：自动为状态栏留出安全区域，避免内容被状态栏遮挡
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("播放历史") },
        actions = {
          IconButton(onClick = { scope.launch { load() } }) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
          }
          // 与首页保持一致：Emby 图标进服务器维护，齿轮进 App 设置
          EmbyMaintainButton(onClick = { backStack.add(EmbyServerManageScreen) })
          IconButton(onClick = { backStack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen) }) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
          }
        },
      )
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

        isLoading && resumeItems.isEmpty() && historyItems.isEmpty() ->
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        resumeItems.isEmpty() && historyItems.isEmpty() -> EmbyEmptyState(
          message = "还没有播放记录",
          buttonText = "刷新",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
          ) {
            if (resumeItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = "继续观看")
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(resumeItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    val currentServer = server ?: return@items
                    EmbyWideCard(
                      title = viewModel.displayTitle(item),
                      subtitle = item.SeriesName ?: item.ProductionYear?.toString(),
                      imageUrl = viewModel.imageUrl(currentServer, item, "Backdrop", 640),
                      fallbackImageUrl = viewModel.imageUrl(currentServer, item, "Primary", 640),
                      progress = progressOf(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      onClick = {
                        scope.launch {
                          viewModel.play(
                            currentServer,
                            item,
                            EmbyTicks.ticksToSeconds(item.UserData?.PlaybackPositionTicks ?: 0L),
                          )
                        }
                      },
                      onLongClick = {
                        val id = item.Id
                        if (id != null) backStack.add(EmbyDetailScreen(id, item.Name ?: ""))
                      },
                    )
                  }
                }
              }
            }

            if (historyItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = "播放历史", modifier = Modifier.padding(top = 8.dp))
              }
              items(historyItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                HistoryRow(
                  item = item,
                  viewModel = viewModel,
                  // 播放历史单击进详情，播放由详情页发起
                  onClick = {
                    val id = item.Id
                    if (id != null) backStack.add(EmbyDetailScreen(id, item.Name ?: ""))
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HistoryRow(
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onClick: () -> Unit,
) {
  androidx.compose.material3.ListItem(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickableCompat(onClick = onClick, onLongClick = {}),
    headlineContent = {
      Text(
        text = viewModel.displayTitle(item),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
      )
    },
    supportingContent = {
      val parts = mutableListOf<String>()
      item.SeriesName?.let { parts.add(it) }
      item.ProductionYear?.let { parts.add(it.toString()) }
      formatDuration(item.RunTimeTicks).takeIf { it.isNotBlank() }?.let { parts.add(it) }
      if (parts.isNotEmpty()) {
        Text(
          text = parts.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    },
  )
}

/** 兼容封装：单击 + 长按 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
  onClick: () -> Unit,
  onLongClick: () -> Unit,
): Modifier = this.combinedClickable(
  onClick = onClick,
  onLongClick = onLongClick,
)

private fun progressOf(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
