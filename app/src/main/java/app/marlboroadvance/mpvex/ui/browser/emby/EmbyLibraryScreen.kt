package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Emby 媒体库浏览页（二级页面）。
 *
 * 页面结构：
 * 1. 顶栏：返回 + 媒体库名称 + 搜索
 * 2. 分类行：全部 / 继续播放 / 合集 / 收藏 / 文件夹
 * 3. 工具行：媒体数量 + 随机播放 + 视图样式 + 排序
 * 4. 媒体网格（海报 / 背景图 / 横幅三种样式）
 *
 * 层级导航：媒体库 →（影视剧）季列表 → 剧集列表 → 详情。
 * 单击行为：容器类继续下钻，其余打开详情页，播放由详情页发起。
 */
@Serializable
data class EmbyLibraryScreen(
  val libraryId: String,
  val title: String,
  val collectionType: String? = null,
  val includeItemTypes: List<String>? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(EmbyCategory.ALL) }
    var showStyleDialog by remember { mutableStateOf(false) }

    // 排序方式与卡片样式走偏好存储：重新进入媒体库、甚至重启 App 都沿用上次的选择
    val browserPreferences = koinInject<BrowserPreferences>()
    val sortBy by browserPreferences.embyLibrarySortBy.collectAsState()
    val cardStyleName by browserPreferences.embyLibraryCardStyle.collectAsState()
    val cardStyle = remember(cardStyleName) { embyCardStyleFromName(cardStyleName) }

    // 列表缓存 key：库 + 分类 + 排序 + 搜索词。
    // 从详情页 / 播放器返回时这个 key 不变，于是直接命中缓存、不再发请求；
    // 只有用户下拉刷新或改了筛选条件才会真正重新拉取。
    val cacheKey = "$libraryId|${category.name}|$sortBy|$searchQuery"
    val cachedEntry = remember(cacheKey) { EmbyLibraryCache.get(cacheKey) }

    var items by remember(cacheKey) { mutableStateOf(cachedEntry?.items ?: emptyList()) }
    var totalCount by remember(cacheKey) { mutableIntStateOf(cachedEntry?.totalCount ?: 0) }
    var isLoading by remember(cacheKey) {
      mutableStateOf(cachedEntry == null || cachedEntry.items.isEmpty())
    }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val isRefreshing = remember { mutableStateOf(false) }

    // 文件夹宫格封面：初值取自进程内缓存，从子页面返回时不会重新请求
    val folderCovers = remember {
      mutableStateMapOf<String, List<String>>().apply { putAll(EmbyFolderCoverCache.snapshot()) }
    }

    // 用缓存里的滚动位置初始化，返回时能停在原来那一屏
    val gridState = rememberLazyGridState(
      initialFirstVisibleItemIndex = cachedEntry?.scrollIndex ?: 0,
      initialFirstVisibleItemScrollOffset = cachedEntry?.scrollOffset ?: 0,
    )

    // 季/集层级由 includeItemTypes 固定，优先于分类筛选
    val effectiveTypes: List<String>? = includeItemTypes
      ?: category.itemTypes
      // 「全部」按库类型限定条目类型；否则 Emby 会把季、集和文件夹一起返回，列表是混的
      ?: if (category == EmbyCategory.ALL) allItemTypesFor(collectionType) else null
    val effectiveFilters: List<String>? = if (includeItemTypes == null) category.filters else null
    val recursive: Boolean = category != EmbyCategory.FOLDER
    // Emby 的 Recursive 查询默认会把文件夹一并返回。
    // 用户期望「全部」直接铺媒体，只有切到「文件夹」分类才看到文件夹，所以这里显式排除。
    val effectiveExclude: List<String>? =
      if (includeItemTypes == null && category != EmbyCategory.FOLDER) FOLDER_TYPES else null

    fun cacheItems() {
      EmbyLibraryCache.putItems(cacheKey, items, totalCount)
    }

    suspend fun load(reset: Boolean) {
      // 冷启动时当前服务器可能还没恢复，这里等一下，避免静默不加载
      val current = viewModel.currentServerOrAwait() ?: return
      if (reset) isLoading = true
      error = null
      runCatching {
        if (searchQuery.isBlank()) {
          viewModel.loadItems(
            server = current,
            parentId = libraryId,
            includeItemTypes = effectiveTypes,
            filters = effectiveFilters,
            sortBy = sortBy,
            sortOrder = if (sortBy == "SortName" || sortBy == "ProductionYear") "Ascending" else "Descending",
            startIndex = if (reset) 0 else items.size,
            limit = PAGE_SIZE,
            recursive = recursive,
            excludeItemTypes = effectiveExclude,
          )
        } else {
          val result = viewModel.search(current, searchQuery)
          EmbyItemsPage(result, result.size)
        }
      }.onSuccess { page ->
        items = if (reset) {
          page.items
        } else {
          // 分页时按 Id 去重后追加
          items + page.items.filter { new -> items.none { it.Id == new.Id } }
        }
        totalCount = page.totalCount
        cacheItems()
      }.onFailure {
        error = it.message ?: "加载失败"
      }
      isLoading = false
    }

    // 首次进入 / 筛选条件变化：只有没有可用缓存时才请求。
    // 有缓存说明是刚从详情页或播放器返回，直接复用列表，不刷新。
    LaunchedEffect(cacheKey) {
      // 搜索走下面带防抖的那个 effect，这里跳过，避免每敲一个字就立刻发一次请求
      if (searchQuery.isNotBlank()) return@LaunchedEffect
      val entry = EmbyLibraryCache.get(cacheKey)
      if (entry == null || entry.items.isEmpty()) load(reset = true)
    }

    // 离开页面时记下滚动位置，返回时才能回到原来的位置
    DisposableEffect(cacheKey) {
      onDispose {
        EmbyLibraryCache.putScroll(
          cacheKey,
          gridState.firstVisibleItemIndex,
          gridState.firstVisibleItemScrollOffset,
        )
      }
    }

    LaunchedEffect(gridState, items.size) {
      snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .collect { lastVisible ->
          val canLoadMore = !isLoading && items.size < totalCount && lastVisible != null
          if (canLoadMore && lastVisible >= items.size - 6) {
            load(reset = false)
          }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      // ── 1. 顶栏：返回 + 库名 + 搜索 ──
      TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = {
            searchActive = !searchActive
            if (!searchActive) {
              searchQuery = ""
              scope.launch { load(reset = true) }
            }
          }) {
            Icon(Icons.Default.Search, contentDescription = "搜索")
          }
        },
      )

      // ── 2. 分类行 ──
      if (includeItemTypes == null) {
        CategoryChips(selected = category, onSelect = { category = it })
      }

      // ── 3. 工具行：数量 + 随机播放 + 视图 + 排序 ──
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (totalCount > 0) "$totalCount 项" else "",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = {
          scope.launch {
            val current = viewModel.currentServerOrAwait() ?: return@launch
            val random = viewModel.loadRandom(
              server = current,
              parentId = libraryId,
              includeItemTypes = PLAYABLE_TYPES,
              limit = 100,
            )
            if (random.isNotEmpty()) viewModel.launchPlaylist(current, random)
          }
        }) {
          Icon(Icons.Default.Shuffle, contentDescription = "随机播放")
        }
        // 只在本库已收藏的媒体里随机，避免随机到没看过的
        IconButton(onClick = {
          scope.launch {
            val current = viewModel.currentServerOrAwait() ?: return@launch
            val random = viewModel.loadRandom(
              server = current,
              parentId = libraryId,
              includeItemTypes = PLAYABLE_TYPES,
              limit = 100,
              isFavorite = true,
            )
            if (random.isNotEmpty()) {
              viewModel.launchPlaylist(current, random)
            } else {
              android.widget.Toast
                .makeText(context, "该媒体库还没有收藏内容", android.widget.Toast.LENGTH_SHORT)
                .show()
            }
          }
        }) {
          Icon(Icons.Default.ShuffleOn, contentDescription = "随机播放收藏")
        }
        IconButton(onClick = { showStyleDialog = true }) {
          Icon(Icons.Default.GridView, contentDescription = "视图样式")
        }
        SortMenu(currentSort = sortBy) { newSort -> browserPreferences.embyLibrarySortBy.set(newSort) }
      }

      if (searchActive) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          placeholder = { Text("搜索媒体…") },
          singleLine = true,
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = {
                searchQuery = ""
                scope.launch { load(reset = true) }
              }) {
                Icon(Icons.Default.Clear, contentDescription = "清空")
              }
            }
          },
        )
        LaunchedEffect(searchQuery) {
          if (searchQuery.isNotBlank()) {
            kotlinx.coroutines.delay(400)
            load(reset = true)
          }
        }
      }

      // ── 4. 媒体网格 ──
      // 用下拉刷新包裹：返回页面不会自动刷新，只有用户主动下拉才重新拉取
      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        onRefresh = { load(reset = true) },
        gridState = gridState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        when {
          isLoading && items.isEmpty() -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
          )

          error != null && items.isEmpty() -> EmbyEmptyState(
            message = error ?: "加载失败",
            buttonText = "重试",
            onAction = { scope.launch { load(reset = true) } },
            modifier = Modifier.align(Alignment.Center),
          )

          items.isEmpty() && !isLoading -> EmbyEmptyState(
            message = "没有找到媒体",
            buttonText = "刷新",
            onAction = { scope.launch { load(reset = true) } },
            modifier = Modifier.align(Alignment.Center),
          )

          else -> {
            LazyVerticalGrid(
              state = gridState,
              // 海报固定一行三个；横版 / 横幅样式本身更宽，仍按最小宽度自适应，避免被压得过小
              columns =
                if (cardStyle == EmbyCardStyle.POSTER) {
                  GridCells.Fixed(3)
                } else {
                  GridCells.Adaptive(minSize = cardStyle.width)
                },
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(items, key = { it.Id ?: it.Name ?: "" }) { item ->
                val s = server ?: return@items
                val itemId = item.Id
                // 文件夹 / 合集这类容器条目自身没有封面图，改用内部视频的缩略图拼宫格
                val needsMosaic =
                  itemId != null && isFolderLike(item) && item.ImageTags["Primary"] == null
                val folderCover: List<String>? = if (needsMosaic && itemId != null) {
                  val cached = folderCovers[itemId]
                  LaunchedEffect(itemId, s.id) {
                    if (cached == null && EmbyFolderCoverCache.beginLoad(itemId)) {
                      val urls = viewModel.loadFolderCoverUrls(s, itemId, 4)
                      EmbyFolderCoverCache.put(itemId, urls)
                      folderCovers[itemId] = urls
                    }
                  }
                  cached
                } else {
                  null
                }

                EmbyMediaCard(
                  title = viewModel.displayTitle(item),
                  subtitle = itemSubtitle(item),
                  imageUrl = viewModel.imageUrl(s, item, imageTypeFor(cardStyle), 480),
                  fallbackImageUrl = viewModel.imageUrl(s, item, "Primary", 480),
                  mosaicUrls = folderCover,
                  progress = itemProgress(item),
                  isFavorite = item.UserData?.IsFavorite == true,
                  onClick = { openItem(item, backStack, s) },
                  style = cardStyle,
                  fillWidth = cardStyle == EmbyCardStyle.POSTER,
                )
              }
            }
          }
        }
      }
    }

    // 视图样式选择
    if (showStyleDialog) {
      AlertDialog(
        onDismissRequest = { showStyleDialog = false },
        title = { Text("视图样式") },
        text = {
          Column {
            EmbyCardStyle.entries.forEach { style ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(
                  selected = style == cardStyle,
                  onClick = { browserPreferences.embyLibraryCardStyle.set(style.name) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(style.label)
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showStyleDialog = false }) {
            Text("完成")
          }
        },
      )
    }
  }
}

/** 不同卡片样式使用的图片类型：横版样式优先用背景图，海报用主封面 */
private fun imageTypeFor(style: EmbyCardStyle): String =
  if (style == EmbyCardStyle.POSTER) "Primary" else "Backdrop"

/**
 * 库内分类筛选。
 *
 * @param itemTypes 传给 Emby 的 IncludeItemTypes
 * @param filters 传给 Emby 的 Filters
 */
private enum class EmbyCategory(
  val label: String,
  val itemTypes: List<String>?,
  val filters: List<String>?,
) {
  ALL("全部", null, null),
  RESUMABLE("继续播放", null, listOf("IsResumable")),
  BOXSET("合集", listOf("BoxSet"), null),
  FAVORITE("收藏", null, listOf("IsFavorite")),
  FOLDER("文件夹", listOf("Folder", "CollectionFolder", "UserView"), null),
}

@Composable
private fun CategoryChips(
  selected: EmbyCategory,
  onSelect: (EmbyCategory) -> Unit,
) {
  androidx.compose.foundation.lazy.LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(EmbyCategory.entries.size) { index ->
      val category = EmbyCategory.entries[index]
      FilterChip(
        selected = category == selected,
        onClick = { onSelect(category) },
        label = { Text(category.label) },
      )
    }
  }
}

/**
 * 单击媒体项：容器类继续下钻，其余打开详情页。
 *
 * - 影视剧（Series）→ 季列表
 * - 季（Season）→ 该季的剧集列表
 * - 其它（电影 / 单集 / 视频）→ 详情页，播放由详情页发起
 */
private fun openItem(
  item: EmbyItem,
  backStack: androidx.navigation3.runtime.NavBackStack<Screen>,
  server: EmbyServer,
) {
  val id = item.Id ?: return
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

    "CollectionFolder", "Folder", "UserView", "BoxSet" -> backStack.add(
      EmbyLibraryScreen(
        libraryId = id,
        title = item.Name ?: "",
        collectionType = item.CollectionType,
      ),
    )

    else -> backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
  }
}

@Composable
private fun SortMenu(
  currentSort: String,
  onSortChange: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Default.Sort, contentDescription = "排序")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      SORT_OPTIONS.forEach { (value, label) ->
        DropdownMenuItem(
          text = {
            Text(
              text = label,
              color = if (value == currentSort) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          },
          onClick = {
            onSortChange(value)
            expanded = false
          },
        )
      }
    }
  }
}

/** 排序方式：值与 Emby /Items 的 SortBy 参数一致 */
private val SORT_OPTIONS = listOf(
  "SortName" to "名称",
  "DateCreated" to "加入时间",
  "DatePlayed" to "播放时间",
  "ProductionYear" to "发行年份",
  "PremiereDate" to "首播日期",
  "CommunityRating" to "评分",
  "CriticRating" to "影评评分",
  "Runtime" to "时长",
  "PlayCount" to "播放次数",
  "Random" to "随机",
)

/** 可播放的媒体类型（随机播放时使用） */
private val PLAYABLE_TYPES = listOf("Movie", "Episode", "Video", "MusicVideo")

/** 文件夹类条目。只在「文件夹」分类里显示，其它分类一律排除。 */
private val FOLDER_TYPES = listOf("Folder", "CollectionFolder", "UserView")

/**
 * 是否需要「多宫格封面」的容器类条目。
 *
 * 这些条目在 Emby 里只是容器，自身没有 Primary 图（请求图片接口直接 404），
 * 所以要用内部子媒体的缩略图拼封面。
 */
private fun isFolderLike(item: EmbyItem): Boolean =
  item.Type in FOLDER_TYPES || item.Type == "BoxSet"

/**
 * 文件夹宫格封面的进程内缓存（按文件夹 Id 存取）。
 *
 * 一个文件夹的封面要额外发一次 /Items 查询才能拿到子项缩略图，比较贵；
 * 缓存后滚动列表、切分类、从子页面返回都不必重复请求。
 * 取不到的（空文件夹）会缓存成空列表，避免反复重试把服务器打满。
 */
private object EmbyFolderCoverCache {
  private const val MAX_ENTRIES = 200

  private val entries = LinkedHashMap<String, List<String>>()
  private val loading = HashSet<String>()

  @Synchronized
  fun snapshot(): Map<String, List<String>> = LinkedHashMap(entries)

  /** 返回 true 表示这次由调用方发起加载（同一文件夹不会被并发重复请求） */
  @Synchronized
  fun beginLoad(folderId: String): Boolean {
    if (entries.containsKey(folderId) || loading.contains(folderId)) return false
    loading.add(folderId)
    return true
  }

  @Synchronized
  fun put(
    folderId: String,
    urls: List<String>,
  ) {
    loading.remove(folderId)
    entries.remove(folderId)
    entries[folderId] = urls
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}

/**
 * 「全部」分类下按媒体库类型限定条目类型。
 *
 * Emby 的 Recursive 查询在不指定类型时会把季、集、文件夹一起返回，
 * 列表会变得很杂；官方客户端的做法就是按库类型取「顶层条目」。
 * 返回 null 表示不限制类型（未知/混合库）。
 */
private fun allItemTypesFor(collectionType: String?): List<String>? =
  when (collectionType?.lowercase()) {
    "movies" -> listOf("Movie", "BoxSet")
    "tvshows" -> listOf("Series")
    "music" -> listOf("MusicAlbum")
    "musicvideos" -> listOf("MusicVideo")
    "boxsets" -> listOf("BoxSet")
    "homevideos" -> listOf("Video")
    "photos" -> listOf("PhotoAlbum")
    "books" -> listOf("Book")
    else -> null
  }

private fun itemSubtitle(item: EmbyItem): String? {
  val year = item.ProductionYear
  val count = item.ChildCount
  return when (item.Type) {
    "Series" -> count?.let { "$it 季" }
    "Season" -> count?.let { "$it 集" }
    else -> year?.toString()
  }
}

private fun itemProgress(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private const val PAGE_SIZE = 100

/** 枚举名 → [EmbyCardStyle]，取值异常时回退到海报样式 */
private fun embyCardStyleFromName(name: String): EmbyCardStyle =
  EmbyCardStyle.entries.firstOrNull { it.name == name } ?: EmbyCardStyle.POSTER

/**
 * 媒体库列表的进程内缓存，按「库 + 分类 + 排序 + 搜索词」分桶。
 *
 * 媒体库页在导航离开后会被销毁重组，如果每次都重新请求，从详情页 / 播放器返回
 * 时列表就会闪一下、跳回顶部。这里把结果和滚动位置一起缓存：
 * - [putItems] 在加载成功后写入，[putScroll] 在离开页面时写入；
 * - 页面首屏命中缓存时直接复用，不发起请求；
 * - 用户下拉刷新或改动筛选条件（key 变化）时照常重新拉取。
 *
 * 仅做进程内缓存、不落盘 —— 重启 App 后重新拉取是符合预期的。
 */
private object EmbyLibraryCache {
  private const val MAX_ENTRIES = 16

  class Entry {
    var items: List<EmbyItem> = emptyList()
    var totalCount: Int = 0
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
  }

  private val entries = LinkedHashMap<String, Entry>()

  @Synchronized
  fun get(key: String): Entry? = entries[key]

  @Synchronized
  fun putItems(
    key: String,
    items: List<EmbyItem>,
    totalCount: Int,
  ) {
    val entry = entries.getOrPut(key) { Entry() }
    entry.items = items
    entry.totalCount = totalCount
    touch(key, entry)
  }

  @Synchronized
  fun putScroll(
    key: String,
    index: Int,
    offset: Int,
  ) {
    val entry = entries.getOrPut(key) { Entry() }
    entry.scrollIndex = index
    entry.scrollOffset = offset
    touch(key, entry)
  }

  /** 最近使用的挪到末尾，超出上限时淘汰最久未使用的 */
  private fun touch(
    key: String,
    entry: Entry,
  ) {
    entries.remove(key)
    entries[key] = entry
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}
