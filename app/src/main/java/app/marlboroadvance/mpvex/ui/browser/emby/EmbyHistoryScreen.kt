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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonHistory
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch

/** 播放历史一页拉多少条（清单 1.1：原来固定 100 条） */
private const val HISTORY_PAGE_SIZE = 100

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
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val listSource = listState.pagingSource()

  // 播放历史改真分页（清单 1.1）：原来固定 100 条，看过 300 部的人永远看不到第 101 部。
  // 「继续观看」是横向卡片，数量天然有限（未看完的片子），仍一次拉完。
  val historyPage = remember { PagedList<EmbyItem>(HISTORY_PAGE_SIZE, keyOf = { it.Id ?: it.Name ?: "" }) }

  /** 播放历史的分页加载器 */
  val historyLoader: suspend (Int, Int) -> PagedResult<EmbyItem> = hist@{ start, limit ->
    val current = server ?: return@hist PagedResult(emptyList(), null, limit)
    val page = viewModel.loadHistory(current, start, limit)
    PagedResult(
      // 按列表 key 的口径去重：服务端重复条目会把 LazyColumn 撞崩（与媒体库搜索同款问题）
      items = page.items.distinctBy { it.Id ?: it.Name ?: "" },
      totalCount = page.totalCount,
      pageSize = limit,
    )
  }

  suspend fun load() {
    // 冷启动时当前服务器可能还没恢复，这里等一下，避免误判成「没有服务器」而空白
    val current = viewModel.currentServerOrAwait() ?: run {
      isLoading = false
      return
    }
    isLoading = true
    error = null
    // 继续观看与播放历史分开处理：前者失败不影响后者（原来捆在一个 runCatching 里，
    // 播放历史一失败，「继续观看」也跟着空着）
    runCatching { viewModel.loadResume(current, 40) }
      .onSuccess { list ->
        resumeItems.clear()
        resumeItems.addAll(list.distinctBy { it.Id ?: it.Name ?: "" })
      }
      .onFailure { error = it.message ?: "加载继续观看失败" }

    historyPage.reload(historyLoader)
    if (historyPage.error != null) error = historyPage.error
    isLoading = false
  }

  LaunchedEffect(server?.id) { load() }

  // 触底自动续拉播放历史（继续观看是横向的，不参与）
  historyPage.AutoLoadMore(
    source = listSource,
    enabled = server != null && resumeItems.isNotEmpty(),
    loader = historyLoader,
  )

  // Scaffold + TopAppBar：自动为状态栏留出安全区域，避免内容被状态栏遮挡
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("播放历史") },
        actions = {
          IconButton(onClick = { scope.launch { load() } }) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
          }
          // 下载管理：有任务在跑时角标显示数量
          EmbyDownloadEntryButton()
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

        isLoading && resumeItems.isEmpty() && historyPage.items.isEmpty() ->
          EmbySkeletonHistory()

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        resumeItems.isEmpty() && historyPage.items.isEmpty() -> EmbyEmptyState(
          message = "还没有播放记录",
          buttonText = "刷新",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          LazyColumn(
            state = listState,
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

            if (historyPage.items.isNotEmpty()) {
              item {
                EmbySectionHeader(
                  title = "播放历史",
                  modifier = Modifier.padding(top = 8.dp),
                )
              }
              items(historyPage.items, key = { it.Id ?: it.Name ?: "" }) { item ->
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
              // 底部：续拉中 / 出错重试 / 已全部加载
              item {
                EmbyPagingFooter(
                  appending = historyPage.appending,
                  hasMore = historyPage.hasMore,
                  error = historyPage.error,
                  loadedCount = historyPage.items.size,
                  endText = historyPage.total?.let { "已显示全部 ${historyPage.items.size} / $it 条" },
                  onRetry = { scope.launch { historyPage.retry(historyLoader) } },
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
