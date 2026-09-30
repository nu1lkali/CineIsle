package app.marlboroadvance.mpvex.ui.browser.emby

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyPosterCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilterRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyItemActionsDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.SearchHistoryPanel
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

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

  // ── 下拉刷新 ──
  // 首页是「媒体库 + 继续观看 + 最新加入」三块的组合，服务端上新了片子不会自己冒出来，
  // 所以要给用户一个手动重拉的入口。刷新期间转圈一直转到这批请求真的回来（refreshHomeAndWait），
  // 否则手指一松动画就没了，看不出到底刷没刷。
  val isRefreshing = remember { mutableStateOf(false) }
  // 只有列表停在顶部时才允许下拉刷新：滚到中间往下拉，列表自己会往上滚，不该触发刷新
  val homeListState = rememberLazyListState()

  // 长按媒体库卡片 → 通知服务器扫描该库（异步任务，只发指令不等结果）
  val scanScope = rememberCoroutineScope()
  // 长按选中的媒体库：非空时弹操作框，用户点「扫描媒体库」后才真的发请求
  var scanTarget by remember { mutableStateOf<EmbyItem?>(null) }
  /** 长按位置（root 坐标）：菜单锚在手指旁边展开 */
  var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

  // ── 全库搜索 ──
  // 不带 ParentId，Emby 会跨所有媒体库检索，所以这里搜的是「全部媒体」而不是某个库。
  var searchActive by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var searchResults by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var isSearching by remember { mutableStateOf(false) }
  // 类型筛选：空集合 = 「全部」= 不加类型限制
  var searchFilters by remember { mutableStateOf(emptySet<EmbySearchFilter>()) }

  // 搜索历史：与媒体库内搜索共用同一张表，在哪儿搜过的词换个地方也能点到
  val searchHistoryRepository = koinInject<SearchHistoryRepository>()
  val searchHistoryFlow = remember { searchHistoryRepository.observe() }
  val searchHistory by searchHistoryFlow.collectAsState(initial = emptyList())
  val historyScope = rememberCoroutineScope()
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(modifier = Modifier.fillMaxSize()) {
    // 长按媒体库卡片弹出的操作框（扫描媒体库 / 刷新元数据）
    //
    // ⚠️ 这个锚定 Box **必须放在 Column 的第一个子元素**：它包的是 DropdownMenu
    // （Popup，不占布局空间），位置只取决于它在 Column 里排第几。放在最后的话，
    // 前面的列表 fillMaxSize 已经占满整屏，留给它的空间是 0，锚点就落到屏幕底部 ——
    // 表现就是「菜单跑到界面底部，而不是手指旁边」。
    val target = scanTarget
    if (target != null) {
      Box(
        modifier = Modifier.offset {
          androidx.compose.ui.unit.IntOffset(menuAnchor.x.roundToInt(), menuAnchor.y.roundToInt())
        },
      ) {
        val targetId = target.Id
        val targetName = target.Name ?: "媒体库"
        EmbyItemActionsDialog(
          name = targetName,
          kindLabel = "媒体库",
          onDismissRequest = { scanTarget = null },
          onScan = {
            scanTarget = null
            runEmbyLibraryAction(
              context = context,
              scope = scanScope,
              server = server,
              itemId = targetId,
              name = targetName,
              okMessage = "已通知服务器扫描「$targetName」，稍后下拉刷新查看新文件",
              action = { s, id -> runCatching { viewModel.scanLibrary(s, id) } },
            )
          },
          onRefreshMetadata = { replaceMetadata, replaceImages ->
            scanTarget = null
            runEmbyLibraryAction(
              context = context,
              scope = scanScope,
              server = server,
              itemId = targetId,
              name = targetName,
              okMessage = "已通知服务器刷新「$targetName」的元数据，稍后下拉刷新查看",
              action = { s, id ->
                runCatching { viewModel.refreshLibraryMetadata(s, id, replaceMetadata, replaceImages) }
              },
            )
          },
        )
      }
    }
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
        // 全库搜索：跨所有媒体库检索，结果替换首页内容
        IconButton(onClick = {
          searchActive = !searchActive
          if (!searchActive) {
            searchQuery = ""
            searchResults = emptyList()
            isSearching = false
          }
        }) {
          Icon(Icons.Default.Search, contentDescription = "搜索全部媒体库")
        }
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
        // 下载管理：有任务在跑时角标显示数量
        EmbyDownloadEntryButton()
        // Emby 维护：用官方 Emby 图标（品牌绿），与下方齿轮明确区分
        EmbyMaintainButton(onClick = onManageServers)
        // 设置：统一进 App 设置页（播放器 / 外观 / 解码等偏好都在这里）
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Default.Settings, contentDescription = "设置")
        }
      },
    )

    // 搜索框：只在点开搜索后出现
    if (searchActive) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("搜索全部媒体库…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          // 键盘上按「搜索」= 用户明确表态「我就搜这个词」，直接落一条历史并立刻出结果
          onSearch = {
            keyboardController?.hide()
            historyScope.launch { searchHistoryRepository.record(searchQuery) }
          },
        ),
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Clear, contentDescription = "清空")
            }
          }
        },
      )
      // 类型筛选：默认「全部」不加限制；勾了电影/合集/演员这类就按类型查
      EmbySearchFilterRow(
        selected = searchFilters,
        onSelectedChange = { searchFilters = it },
      )
    }

    // 防抖 400ms，避免每敲一个字就发一次请求
    LaunchedEffect(searchQuery, searchActive, searchFilters) {
      if (!searchActive || searchQuery.isBlank()) {
        searchResults = emptyList()
        isSearching = false
        return@LaunchedEffect
      }
      isSearching = true
      kotlinx.coroutines.delay(400)
      val current = server
      if (current == null) {
        isSearching = false
        return@LaunchedEffect
      }
      val finishedQuery = searchQuery
      searchResults = viewModel.searchGlobal(
        server = current,
        term = finishedQuery,
        itemTypes = EmbySearchFilter.toItemTypes(searchFilters),
      )
      // 有结果才记历史：一个字都没命中的多半是打字打岔了，记下来只会污染列表
      if (searchResults.isNotEmpty()) {
        searchHistoryRepository.record(finishedQuery)
      }
      isSearching = false
    }

    when {
      // 全库搜索：结果替换首页内容
      searchActive -> {
        if (searchQuery.isBlank()) {
          SearchHistoryPanel(
            history = searchHistory,
            onPick = { keyword -> searchQuery = keyword },
            onRemove = { keyword -> historyScope.launch { searchHistoryRepository.remove(keyword) } },
            onClearAll = { historyScope.launch { searchHistoryRepository.clear() } },
            modifier = Modifier.fillMaxSize(),
            emptyHint = "输入关键词，搜索全部媒体库",
          )
        } else if (isSearching) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        } else if (searchResults.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "没有找到「$searchQuery」相关媒体",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(
              items = searchResults,
              key = { it.Id ?: it.Name ?: "" },
            ) { item ->
              EmbyPosterCard(
                title = item.Name ?: "",
                subtitle = item.ProductionYear?.toString(),
                imageUrl = server?.let { s -> viewModel.imageUrl(s, item, "Primary", 300) },
                progress = null,
                isFavorite = item.UserData?.IsFavorite == true,
                onClick = { onOpenDetail(item) },
              )
            }
          }
        }
      }

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
        PullRefreshBox(
          isRefreshing = isRefreshing,
          onRefresh = { viewModel.refreshHomeAndWait() },
          modifier = Modifier.fillMaxSize(),
          // 搜索态首页列表是空的，此时下拉刷新没有意义（下拉要重跑的是搜索，不是首页数据）
          enabled = !searchActive,
          listState = homeListState,
        ) {
          LazyColumn(
            state = homeListState,
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
                    val libraryName = library.Name ?: "媒体库"
                    EmbyLibraryCard(
                      name = libraryName,
                      itemCount = library.ChildCount,
                      imageUrl = viewModel.imageUrl(server!!, library, "Primary", 300),
                      icon = libraryIcon(library.CollectionType),
                      onClick = { onOpenLibrary(library) },
                      // 长按 = 弹出操作框（扫描媒体库等）。扫描是作用在服务器上的异步任务、
                      // 发出去撤不回来，所以不直接执行，先让用户确认
                      onLongClick = { offset -> scanTarget = library; menuAnchor = offset },
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
                      remainingText = remainingLabel(item),
                      // 单击是进详情页而非直接播放，去掉居中的播放三角，避免误导
                      showPlayButton = false,
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
}

/** 计算播放进度（0f~1f）；没有时长信息时返回 null */
private fun playbackProgress(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 剩余时长文案：「剩余 XX分XX秒」。
 *
 * 只有「已开始播放且尚未看完」的条目才返回非空；
 * 超过 1 小时的用「剩余 X小时XX分」，避免数字太长撑破角标。
 */
private fun remainingLabel(item: EmbyItem): String? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  val remain = EmbyTicks.ticksToSeconds(total - position)
  if (remain <= 0) return null
  return if (remain >= 3600) {
    "剩余 ${remain / 3600}小时${(remain % 3600) / 60}分"
  } else {
    "剩余 ${remain / 60}分${remain % 60}秒"
  }
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
