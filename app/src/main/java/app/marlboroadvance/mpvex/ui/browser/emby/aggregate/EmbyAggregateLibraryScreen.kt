package app.marlboroadvance.mpvex.ui.browser.emby.aggregate

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.utils.LazyGridSideScrollbar
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.scrollToTop
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyItemActionsDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoGridScreen
import app.marlboroadvance.mpvex.ui.browser.emby.playbackProgress
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/**
 * 「聚合库」浏览页：多个成员库归并成**一个**网格。
 *
 * 与媒体库页（[EmbyLibraryScreen]）的关系：**复用同一套卡片 / 下拉刷新 / 骨架屏 /
 * 侧边滚动条 / 排序菜单口径**，差别只在取数 —— 这边走
 * [app.marlboroadvance.mpvex.domain.emby.AggregateItemPager]（多泳道游标归并），
 * 那边走单库分页。点进去的下钻（季 / 集 / 文件夹 / 相册 / 合集）与媒体库页同构，
 * 因为下钻目标是服务端的真实条目，跟「这个页是怎么聚合成的」无关。
 *
 * ⚠️ 为什么不直接改造媒体库页加一个「多 ParentId」参数：那页 4300 行，
 * 带着搜索快照 / 中文字幕扫描 / 分类持久化一整套状态机，塞一条合并数据源进去
 * 等于给每条代码路径都加一个「现在是合并模式吗」的分叉。独立一页 + 共享组件
 * 是这里风险与复用量的最优点。
 *
 * ⚠️ 视图模式只做网格：紧凑列表（[EmbyLibraryScreen] 里的 `EmbyCompactRow`）是那页的
 * 私有组件，复制一份只会带来「两份行式卡片各自演化」的分叉。要列表视图就等
 * 哪天把那个组件提到公共区再说，不在这一版硬抄。
 */
@Serializable
data class EmbyAggregateLibraryScreen(
  val aggregateId: Long,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val vm: EmbyAggregateLibraryViewModel = viewModel(
      factory = EmbyAggregateLibraryViewModel.factory(application),
    )
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by embyVm.currentServer.collectAsState()

    val aggregate by vm.aggregate.collectAsState()
    val items by vm.items.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val error by vm.error.collectAsState()
    val laneErrors by vm.laneErrors.collectAsState()
    val memberHints by vm.memberHints.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val sortBy by vm.sortBy.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()
    val estimatedTotal by vm.estimatedTotal.collectAsState()

    val browserPreferences = koinInject<BrowserPreferences>()
    val cardStyleName by browserPreferences.embyLibraryCardStyle.collectAsState()
    val cardStyle = remember(cardStyleName) {
      EmbyCardStyle.entries.firstOrNull { it.name == cardStyleName } ?: EmbyCardStyle.POSTER
    }
    val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()
    val randomPlayCount = browserPreferences.randomPlayCount.get().coerceIn(1, 500)

    val gridState = rememberLazyGridState()
    val isRefreshing = remember { mutableStateOf(false) }

    // 长按条目 → 操作菜单（扫描 / 刷新元数据），与媒体库页同一套
    var actionTarget by remember { mutableStateOf<EmbyItem?>(null) }
    var actionAnchor by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(aggregateId) { vm.start(aggregateId) }

    // 触底续拉：预取距离走「预测性懒加载」开关（开 ≈ 一屏 / 关 ≈ 贴近末尾）。
    // 首屏未落位（last = -1）时不触发
    val prefetchDistance = rememberPredictivePreloadDistance(browserPreferences)
    LaunchedEffect(gridState, hasMore, isLoading, loadingMore, prefetchDistance) {
      if (!hasMore || isLoading || loadingMore) return@LaunchedEffect
      snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        .collect { last ->
          if (last >= 0 && last >= items.size - prefetchDistance) vm.loadMore()
        }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = aggregate?.name ?: "聚合库",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
    ) { innerPadding ->
      Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // 成员库健康提示（删了的 / 类型变了的）：不打断浏览，但要说清楚
        val hints = memberHints + laneErrors
        if (hints.isNotEmpty()) {
          Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
              hints.forEach { hint ->
                Text(
                  text = hint,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        // ── 工具行：数量 + 随机播放 + 排序 ──
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            // 有服务端口径就显示总数，否则只报已加载数（深翻页时它一直在涨）
            text = if (estimatedTotal > 0) "共 $estimatedTotal 项" else "${items.size} 项",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Box(modifier = Modifier.weight(1f))
          IconButton(
            onClick = {
              scope.launch {
                val current = server ?: return@launch
                val batch = vm.drawRandomPlaylist(randomPlayCount)
                if (batch.isEmpty()) {
                  Toast.makeText(context, "没有可随机播放的内容", Toast.LENGTH_SHORT).show()
                  return@launch
                }
                // 随机列表里可能混着看过的剧，整场从头放（与媒体库页同口径）
                embyVm.launchPlaylist(current, batch, playFromStartAll = true)
              }
            },
          ) {
            Icon(Icons.Default.Shuffle, contentDescription = "随机播放")
          }
          AggregateSortDropdown(
            currentSort = sortBy,
            currentOrder = sortOrder,
            onSortChange = { by, order ->
              vm.applySort(by, order)
              // ⚠️ 回顶只能走 scrollToTop()（内部 requestScrollToItem）：
              //    直接 scrollToItem(0) 落在旧列表上，新顺序 measure 时又被 key 锚回中段
              //    —— 见 ui/utils/ScrollToTop.kt。
              gridState.scrollToTop()
            },
          )
        }

        Box(modifier = Modifier.fillMaxSize()) {
          when {
            isLoading && items.isEmpty() -> EmbySkeletonGrid(
              columns = gridColumns,
              ratio = cardStyle.ratio,
              modifier = Modifier.fillMaxSize(),
            )

            error != null && items.isEmpty() -> EmbyEmptyState(
              message = error ?: "加载失败",
              buttonText = "重试",
              onAction = { vm.load(reset = true) },
              isError = true,
              modifier = Modifier.fillMaxSize(),
            )

            items.isEmpty() -> EmbyEmptyState(
              message = "这个聚合库里还没有内容",
              buttonText = "刷新",
              onAction = { vm.refresh() },
              modifier = Modifier.fillMaxSize(),
            )

            else -> PullRefreshGridBox(
              isRefreshing = isRefreshing,
              onRefresh = { vm.refresh() },
              gridState = gridState,
            ) {
              LazyGridSideScrollbar(state = gridState) {
                LazyVerticalGrid(
                  columns = GridCells.Fixed(gridColumns.coerceAtLeast(1)),
                  state = gridState,
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                  ),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(items, key = { it.Id ?: it.hashCode().toString() }) { item ->
                    EmbyMediaCard(
                      title = embyVm.displayTitle(item),
                      subtitle = item.ProductionYear?.toString() ?: item.Type,
                      imageUrl = server?.let { embyVm.imageUrl(it, item, "Primary", 480) },
                      progress = playbackProgress(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      onClick = {
                        openAggregateItem(backStack, item, aggregate?.collectionType)
                      },
                      style = cardStyle,
                      fallbackImageUrl = server?.let { embyVm.imageUrl(it, item, "Thumb", 480) },
                      isFolder = aggregateIsFolderLike(item),
                      onLongClick = { offset -> actionTarget = item; actionAnchor = offset },
                    )
                  }
                  if (loadingMore) {
                    item {
                      Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                      ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    actionTarget?.let { target ->
      val targetServer = server
      if (targetServer != null) {
        val targetId = target.Id
        val targetName = embyVm.displayTitle(target)
        // 锚定到长按的那一点：菜单从手指旁边展开（与媒体库页同一套）
        Box(
          modifier = Modifier.offset {
            androidx.compose.ui.unit.IntOffset(
              actionAnchor.x.roundToInt(),
              actionAnchor.y.roundToInt(),
            )
          },
        ) {
          EmbyItemActionsDialog(
            name = targetName,
            kindLabel = target.Type ?: "条目",
            onDismissRequest = { actionTarget = null },
            onScan = {
              actionTarget = null
              runEmbyLibraryAction(
                context = context,
                scope = scope,
                server = targetServer,
                itemId = targetId,
                name = targetName,
                okMessage = "已通知服务器扫描「$targetName」，稍后下拉刷新查看新文件",
                action = { srv, id -> runCatching { embyVm.scanLibrary(srv, id) } },
              )
            },
            onRefreshMetadata = { replaceMetadata, replaceImages ->
              actionTarget = null
              runEmbyLibraryAction(
                context = context,
                scope = scope,
                server = targetServer,
                itemId = targetId,
                name = targetName,
                okMessage = "已通知服务器刷新「$targetName」的元数据，稍后下拉刷新查看",
                action = { srv, id ->
                  runCatching {
                    embyVm.refreshLibraryMetadata(srv, id, replaceMetadata, replaceImages)
                  }
                },
              )
            },
          )
        }
      }
    }
  }
}

/** 排序项：值与 Emby /Items 的 SortBy 参数一致（与媒体库页同一份清单） */
private val AGGREGATE_SORT_OPTIONS = listOf(
  "SortName" to "名称",
  "DateCreated" to "加入时间",
  "DatePlayed" to "播放时间",
  "ProductionYear" to "发行年份",
  "PremiereDate" to "首播日期",
  "CommunityRating" to "评分",
  "CriticRating" to "影评评分",
  "Runtime" to "时长",
  "PlayCount" to "播放次数",
  "Size" to "大小",
  "Random" to "随机",
)

@Composable
private fun AggregateSortDropdown(
  currentSort: String,
  currentOrder: String,
  onSortChange: (String, String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Default.Sort, contentDescription = "排序")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      AGGREGATE_SORT_OPTIONS.forEach { (value, label) ->
        DropdownMenuItem(
          text = {
            Text(
              text = buildString {
                append(label)
                if (value == currentSort) {
                  append(if (currentOrder == "Ascending") " ↑" else " ↓")
                }
              },
              color = if (value == currentSort) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          },
          onClick = {
            if (value == currentSort) {
              // 再点一次已选项 = 只反转方向
              onSortChange(value, if (currentOrder == "Ascending") "Descending" else "Ascending")
            } else {
              // 换排序项时方向回到它的自然方向（与媒体库页同规则）
              val natural =
                if (value == "SortName" || value == "ProductionYear") "Ascending" else "Descending"
              onSortChange(value, natural)
            }
            expanded = false
          },
        )
      }
    }
  }
}

/**
 * 点聚合页里的一条条目要去哪。
 *
 * 与媒体库页的 `openItem` 同构：容器类继续下钻（季 / 集 / 文件夹 / 相册 / 合集），
 * Person 进作品列表，其余进详情页。唯一差别：下钻时把**聚合库自己的**
 * collectionType 传给子页当兜底 —— Emby 只在库根（CollectionFolder）上写
 * CollectionType，普通条目上它常是 null，不传兜底子页会以为自己是「未知库」，
 * 查询口径（MediaTypes / 内容原样档）跟着错位。
 */
private fun openAggregateItem(
  backStack: NavBackStack<Screen>,
  item: EmbyItem,
  parentCollectionType: String?,
) {
  val id = item.Id
  if (id.isNullOrBlank() || id.contains("://")) {
    return
  }
  when (item.Type) {
    "Series" -> backStack.add(
      EmbyLibraryScreen(
        libraryId = id,
        title = item.Name ?: "",
        collectionType = item.CollectionType,
        includeItemTypes = listOf("Season"),
      ),
    )

    "Season" -> backStack.add(
      EmbyLibraryScreen(
        libraryId = id,
        title = item.Name ?: "",
        includeItemTypes = listOf("Episode"),
      ),
    )

    // 相册装的是照片不是视频 —— 必须进专用照片网格（瀑布流），
    // 走视频库页会被 MediaTypes=Video 整层滤空
    "PhotoAlbum" -> backStack.add(
      EmbyPhotoGridScreen(
        libraryId = id,
        title = item.Name ?: "",
        initialAlbumId = id,
        initialAlbumName = item.Name ?: "",
      ),
    )

    // 真实文件夹：用「内容原样」口径打开（isFolderDive），
    // 否则库类型白名单 + IsFolder=false 会把这一层的内容整层滤空
    "Folder" -> backStack.add(
      EmbyLibraryScreen(
        libraryId = id,
        title = item.Name ?: "",
        collectionType = item.CollectionType ?: parentCollectionType,
        isFolderDive = true,
      ),
    )

    "CollectionFolder", "UserView", "BoxSet" -> backStack.add(
      EmbyLibraryScreen(
        libraryId = id,
        title = item.Name ?: "",
        collectionType = item.CollectionType ?: parentCollectionType,
      ),
    )

    "Person" -> backStack.add(
      EmbyPersonScreen(
        personId = id,
        personName = item.Name ?: "",
        personImageTag = item.ImageTags["Primary"],
      ),
    )

    else -> backStack.add(
      EmbyDetailScreen(itemId = id, title = item.Name ?: ""),
    )
  }
}

/**
 * 容器类条目判定（左下角文件夹图标用）。
 *
 * 与媒体库页的 `isFolderLike` 同一判定；那边是私有函数，这里带一份同口径实现。
 * ⚠️ 若将来媒体库页改了判定（比如新增容器类型），这里要同步 ——
 * 图标只是「点进去还是播放」的提示，不一致不会坏功能，但会让人困惑。
 */
private val AGGREGATE_FOLDER_TYPES = listOf("Folder", "CollectionFolder", "UserView")

private fun aggregateIsFolderLike(item: EmbyItem): Boolean =
  item.Type in AGGREGATE_FOLDER_TYPES || item.Type == "BoxSet"
