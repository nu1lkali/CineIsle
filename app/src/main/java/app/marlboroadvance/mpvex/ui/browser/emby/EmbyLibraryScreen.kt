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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyFavoriteRandomIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    // 排序方式 / 方向 / 卡片样式走偏好存储：重新进入媒体库、甚至重启 App 都沿用上次的选择
    val browserPreferences = koinInject<BrowserPreferences>()
    val sortBy by browserPreferences.embyLibrarySortBy.collectAsState()
    val sortOrderOverride by browserPreferences.embyLibrarySortOrder.collectAsState()
    val cardStyleName by browserPreferences.embyLibraryCardStyle.collectAsState()
    val cardStyle = remember(cardStyleName) { embyCardStyleFromName(cardStyleName) }
    // 没手动切过时跟随该排序项的自然方向：名称 / 年份升序，其余（加入时间、评分…）降序
    val sortOrder = sortOrderOverride.takeIf { it.isNotBlank() }
      ?: if (sortBy == "SortName" || sortBy == "ProductionYear") "Ascending" else "Descending"

    // ── 筛选条件 ──
    // 各项可叠加；空集合、null、false 都代表「不限」。
    // 初始值从偏好里恢复（每个库一份 JSON），没筛过就是全空。
    val savedFilter = remember(libraryId) {
      runCatching {
        val raw = browserPreferences.embyLibraryFilter(libraryId).get()
        if (raw.isBlank()) {
          EmbyLibraryFilterState()
        } else {
          Json.decodeFromString<EmbyLibraryFilterState>(raw)
        }
      }.getOrDefault(EmbyLibraryFilterState())
    }
    var showFilterDialog by remember { mutableStateOf(false) }
    var selectedGenres by remember(libraryId) { mutableStateOf(savedFilter.genres.toSet()) }
    var selectedTags by remember(libraryId) { mutableStateOf(savedFilter.tags.toSet()) }
    var selectedYears by remember(libraryId) { mutableStateOf(savedFilter.years.toSet()) }
    var selectedRatings by remember(libraryId) {
      mutableStateOf(savedFilter.officialRatings.toSet())
    }
    var selectedPersonIds by remember(libraryId) { mutableStateOf(savedFilter.personIds.toSet()) }
    var selectedStudioIds by remember(libraryId) { mutableStateOf(savedFilter.studioIds.toSet()) }
    var playedFilter by remember(libraryId) { mutableStateOf(savedFilter.isPlayed) }
    var hdFilter by remember(libraryId) { mutableStateOf(savedFilter.isHD) }
    var threeDFilter by remember(libraryId) { mutableStateOf(savedFilter.is3D) }
    var subtitlesFilter by remember(libraryId) { mutableStateOf(savedFilter.hasSubtitles) }
    var minRating by remember(libraryId) { mutableStateOf(savedFilter.minRating) }
    var favoriteOnly by remember(libraryId) { mutableStateOf(savedFilter.favoriteOnly) }
    // 该库实际出现过的可选项，进页面时拉一次
    var filterOptions by remember { mutableStateOf(EmbyFilterOptions()) }
    val hasActiveFilter =
      selectedGenres.isNotEmpty() || selectedTags.isNotEmpty() ||
        selectedYears.isNotEmpty() || selectedRatings.isNotEmpty() ||
        selectedPersonIds.isNotEmpty() || selectedStudioIds.isNotEmpty() ||
        playedFilter != null || hdFilter != null || threeDFilter != null ||
        subtitlesFilter != null || minRating != null || favoriteOnly

    /** 把当前筛选条件写回偏好，下次进这个库还带着 */
    fun persistFilter() {
      val state = EmbyLibraryFilterState(
        genres = selectedGenres.toList(),
        tags = selectedTags.toList(),
        years = selectedYears.toList(),
        officialRatings = selectedRatings.toList(),
        personIds = selectedPersonIds.toList(),
        studioIds = selectedStudioIds.toList(),
        isPlayed = playedFilter,
        isHD = hdFilter,
        is3D = threeDFilter,
        hasSubtitles = subtitlesFilter,
        minRating = minRating,
        favoriteOnly = favoriteOnly,
      )
      runCatching { browserPreferences.embyLibraryFilter(libraryId).set(Json.encodeToString(state)) }
    }

    // 列表缓存 key：库 + 分类 + 排序 + 搜索词。
    // 从详情页 / 播放器返回时这个 key 不变，于是直接命中缓存、不再发请求；
    // 只有用户下拉刷新或改了筛选条件才会真正重新拉取。
    val cacheKey = "$libraryId|${category.name}|$sortBy|$sortOrder|$searchQuery|" +
      "g=${selectedGenres.sorted().joinToString(",")}|" +
      "t=${selectedTags.sorted().joinToString(",")}|" +
      "y=${selectedYears.sorted().joinToString(",")}|" +
      "r=${selectedRatings.sorted().joinToString(",")}|" +
      "p=${selectedPersonIds.sorted().joinToString(",")}|" +
      "s=${selectedStudioIds.sorted().joinToString(",")}|" +
      "played=$playedFilter|hd=$hdFilter|3d=$threeDFilter|sub=$subtitlesFilter|" +
      "m=$minRating|fav=$favoriteOnly"
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
    // 「全部」只保留媒体本体：合集（BoxSet）单独放到「合集」分类里看，这里排除掉。
    // 例外：专门的「合集库」（collectionType = boxsets）本身装的就是合集，
    // 若也排除会把它剔空，所以只对非合集库生效。
    val excludeBoxSet = category == EmbyCategory.ALL &&
      collectionType?.lowercase() != "boxsets"
    val effectiveExclude: List<String>? =
      if (includeItemTypes == null && category != EmbyCategory.FOLDER) {
        if (excludeBoxSet) FOLDER_TYPES + "BoxSet" else FOLDER_TYPES
      } else null

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
            sortOrder = sortOrder,
            startIndex = if (reset) 0 else items.size,
            limit = PAGE_SIZE,
            recursive = recursive,
            excludeItemTypes = effectiveExclude,
            genres = selectedGenres.toList().takeIf { it.isNotEmpty() },
            tags = selectedTags.toList().takeIf { it.isNotEmpty() },
            years = selectedYears.toList().takeIf { it.isNotEmpty() },
            officialRatings = selectedRatings.toList().takeIf { it.isNotEmpty() },
            minCommunityRating = minRating,
            isFavorite = if (favoriteOnly) true else null,
            personIds = selectedPersonIds.toList().takeIf { it.isNotEmpty() },
            isPlayed = playedFilter,
            isHD = hdFilter,
            is3D = threeDFilter,
            hasSubtitles = subtitlesFilter,
            studioIds = selectedStudioIds.toList().takeIf { it.isNotEmpty() },
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

    // 筛选可选项（类型 / 标签 / 年份 / 分级）按库拉一次，
    // 只在「库」或「服务器」变化时重新取
    LaunchedEffect(libraryId, server) {
      val current = server ?: viewModel.currentServerOrAwait() ?: return@LaunchedEffect
      filterOptions = viewModel.loadFilterOptions(current, libraryId)
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
            if (random.isNotEmpty()) {
              // 随机列表里可能混着看过的剧，切过去若恢复进度会直接跳到片尾，
              // 所以每个视频都从头放
              viewModel.launchPlaylist(current, random, playFromStartAll = true)
            }
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
              // 同上：随机收藏列表里每个视频都从头放
              viewModel.launchPlaylist(current, random, playFromStartAll = true)
            } else {
              android.widget.Toast
                .makeText(context, "该媒体库还没有收藏内容", android.widget.Toast.LENGTH_SHORT)
                .show()
            }
          }
        }) {
          // 区别于上面的「随机播放」：用它自己的 Emby 收藏随机图标，
          // 原先用的 ShuffleOn 只比 Shuffle 多一条下划线，并排根本分不出来
          Icon(EmbyFavoriteRandomIcon, contentDescription = "随机播放收藏")
        }
        // 筛选：有生效条件时图标高亮，让人一眼看出列表不是全量
        IconButton(onClick = { showFilterDialog = true }) {
          Icon(
            imageVector = Icons.Default.FilterAlt,
            contentDescription = "筛选",
            tint = if (hasActiveFilter) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          )
        }
        IconButton(onClick = { showStyleDialog = true }) {
          Icon(Icons.Default.GridView, contentDescription = "视图样式")
        }
        SortMenu(
          currentSort = sortBy,
          currentOrder = sortOrder,
          onSortChange = { newSort ->
            browserPreferences.embyLibrarySortBy.set(newSort)
            // 换排序项就丢掉手动方向，回到该排序项的自然方向
            browserPreferences.embyLibrarySortOrder.set("")
          },
          onOrderChange = { newOrder ->
            browserPreferences.embyLibrarySortOrder.set(newOrder)
          },
        )
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

    // ── 筛选弹窗：类型 / 标签 / 年份 / 官方分级 / 评分 / 只看收藏 ──
    // 弹窗里改的是草稿副本，只有点「确定」才写回，避免每点一个 chip 就发一次请求。
    // 点弹窗外部 / 返回键 = 放弃本次改动。
    // ── 筛选面板：底部上划，每个维度一个下拉；选中立即生效，不用点确定 ──
    // 之所以不用「草稿 + 确定」：筛选的结果在下面列表里是实时可见的，
    // 每点一项就刷新一次，比「点完确定才知道对不对」少一次试错。
    if (showFilterDialog) {
      ModalBottomSheet(onDismissRequest = { showFilterDialog = false }) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "筛选",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f),
            )
            // 恢复默认：清空全部条件并落盘，面板保持打开，列表立刻回到全量
            TextButton(onClick = {
              selectedGenres = emptySet()
              selectedTags = emptySet()
              selectedYears = emptySet()
              selectedRatings = emptySet()
              minRating = null
              favoriteOnly = false
              selectedPersonIds = emptySet()
              selectedStudioIds = emptySet()
              playedFilter = null
              hdFilter = null
              threeDFilter = null
              subtitlesFilter = null
              persistFilter()
            }) {
              Text("恢复默认")
            }
          }

          // 类型：多选
          FilterDropdown(
            label = "类型",
            options = filterOptions.genres,
            selectedNames = filterOptions.genres.filter { it in selectedGenres }.toSet(),
            onToggle = { name ->
              selectedGenres = if (name in selectedGenres) selectedGenres - name else selectedGenres + name
              persistFilter()
            },
            onClear = { selectedGenres = emptySet(); persistFilter() },
          )
          // 标签：多选
          FilterDropdown(
            label = "标签",
            options = filterOptions.tags,
            selectedNames = filterOptions.tags.filter { it in selectedTags }.toSet(),
            onToggle = { name ->
              selectedTags = if (name in selectedTags) selectedTags - name else selectedTags + name
              persistFilter()
            },
            onClear = { selectedTags = emptySet(); persistFilter() },
          )
          // 年份：下拉里是字符串，切回 Int 再存（多选）
          FilterDropdown(
            label = "年份",
            options = filterOptions.years.map { it.toString() },
            selectedNames = selectedYears.map { it.toString() }.toSet(),
            onToggle = { v ->
              v.toIntOrNull()?.let { y ->
                selectedYears = if (y in selectedYears) selectedYears - y else selectedYears + y
                persistFilter()
              }
            },
            onClear = { selectedYears = emptySet(); persistFilter() },
          )
          // 官方分级：多选
          FilterDropdown(
            label = "官方分级",
            options = filterOptions.officialRatings,
            selectedNames = filterOptions.officialRatings.filter { it in selectedRatings }.toSet(),
            onToggle = { name ->
              selectedRatings = if (name in selectedRatings) selectedRatings - name else selectedRatings + name
              persistFilter()
            },
            onClear = { selectedRatings = emptySet(); persistFilter() },
          )
          // 演员 / 导演：显示名字，按 Id 筛（同名不同人只能靠 Id 区分），多选
          FilterDropdown(
            label = "演员 / 导演",
            options = filterOptions.persons.map { it.name },
            selectedNames = filterOptions.persons
              .filter { it.id in selectedPersonIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val person = filterOptions.persons.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedPersonIds = if (person.id in selectedPersonIds) {
                selectedPersonIds - person.id
              } else {
                selectedPersonIds + person.id
              }
              persistFilter()
            },
            onClear = { selectedPersonIds = emptySet(); persistFilter() },
          )
          // 工作室：多选
          FilterDropdown(
            label = "工作室",
            options = filterOptions.studios.map { it.name },
            selectedNames = filterOptions.studios
              .filter { it.id in selectedStudioIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val studio = filterOptions.studios.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedStudioIds = if (studio.id in selectedStudioIds) {
                selectedStudioIds - studio.id
              } else {
                selectedStudioIds + studio.id
              }
              persistFilter()
            },
            onClear = { selectedStudioIds = emptySet(); persistFilter() },
          )
          // 播放状态：单选，再点一次已选的那项就取消
          FilterDropdown(
            label = "播放状态",
            options = listOf("已看", "未看"),
            selectedNames = when (playedFilter) {
              true -> setOf("已看")
              false -> setOf("未看")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "已看"
              playedFilter = if (playedFilter == target) null else target
              persistFilter()
            },
            onClear = { playedFilter = null; persistFilter() },
          )
          // 清晰度：单选
          FilterDropdown(
            label = "清晰度",
            options = listOf("高清", "标清"),
            selectedNames = when (hdFilter) {
              true -> setOf("高清")
              false -> setOf("标清")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "高清"
              hdFilter = if (hdFilter == target) null else target
              persistFilter()
            },
            onClear = { hdFilter = null; persistFilter() },
          )
          // 3D：单选
          FilterDropdown(
            label = "3D",
            options = listOf("3D", "非 3D"),
            selectedNames = when (threeDFilter) {
              true -> setOf("3D")
              false -> setOf("非 3D")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "3D"
              threeDFilter = if (threeDFilter == target) null else target
              persistFilter()
            },
            onClear = { threeDFilter = null; persistFilter() },
          )
          // 字幕：单选
          FilterDropdown(
            label = "字幕",
            options = listOf("有字幕", "无字幕"),
            selectedNames = when (subtitlesFilter) {
              true -> setOf("有字幕")
              false -> setOf("无字幕")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "有字幕"
              subtitlesFilter = if (subtitlesFilter == target) null else target
              persistFilter()
            },
            onClear = { subtitlesFilter = null; persistFilter() },
          )
          // 评分：单选
          FilterDropdown(
            label = "评分",
            options = RATING_OPTIONS.map { it.first }.filter { it != "不限" },
            selectedNames = RATING_OPTIONS
              .firstOrNull { it.second == minRating }
              ?.let { setOf(it.first) }
              ?: emptySet(),
            onToggle = { v ->
              val target = RATING_OPTIONS.firstOrNull { it.first == v }?.second
              minRating = if (minRating == target) null else target
              persistFilter()
            },
            onClear = { minRating = null; persistFilter() },
          )

          // 只看收藏：打开 = 只在收藏里套用上面的筛选；关掉 = 不限（不是「只看未收藏」）
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "只看收藏",
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = favoriteOnly,
              onCheckedChange = {
                favoriteOnly = it
                persistFilter()
              },
            )
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

/**
 * 筛选弹窗里的一组可多选 chip（类型 / 标签 / 年份 / 官方分级 / 评分共用）。
 *
 * 选项为空时整组隐藏：说明该维度服务端没给或这个库用不上，
 * 与其显示一个空标题，不如直接不占地方。
 */
/**
 * 筛选面板里的「下拉选择」：一行摘要 + 点开后纵向铺开全部选项。
 *
 * 原来是横向滑动的 chip 组 —— 类型、演员这种动辄几十项的维度横向滑根本没法找，
 * 而且滑到后面完全不知道还剩多少。改成下拉后一屏能扫十几项，末尾还有「不限」一键清空。
 *
 * 选项为空时整组隐藏：说明这个维度服务端没给或该库没有，不占地方。
 * 展开区最高 260dp，超出后自己在组内滚动，不会把整个面板撑得很长。
 */
@Composable
private fun FilterDropdown(
  label: String,
  options: List<String>,
  selectedNames: Set<String>,
  onToggle: (String) -> Unit,
  onClear: () -> Unit,
) {
  if (options.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  // 摘要：没选显示「不限」，选得少就全列出来，选得多只报数量，避免一行塞不下
  val summary = when {
    selectedNames.isEmpty() -> "不限"
    selectedNames.size <= 2 -> selectedNames.joinToString("、")
    else -> "已选 ${selectedNames.size} 项"
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .clickable { expanded = !expanded }
        .padding(horizontal = 14.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 260.dp)
          .verticalScroll(rememberScrollState())
          .padding(top = 4.dp),
      ) {
        // 「不限」永远放在第一位，点它等于清空这一组
        FilterDropdownRow(
          text = "不限",
          checked = selectedNames.isEmpty(),
          onClick = { if (selectedNames.isNotEmpty()) onClear() },
        )
        options.forEach { option ->
          FilterDropdownRow(
            text = option,
            checked = option in selectedNames,
            onClick = { onToggle(option) },
          )
        }
      }
    }
  }
}

@Composable
private fun FilterDropdownRow(
  text: String,
  checked: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(checked = checked, onCheckedChange = { onClick() })
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
  }
}


@Composable
private fun SortMenu(
  currentSort: String,
  currentOrder: String,
  onSortChange: (String) -> Unit,
  onOrderChange: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Default.Sort, contentDescription = "排序")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      // 上半区：排序项。勾中的那一项高亮，并直接显示它当前的方向（↑ 升 / ↓ 降）
      SORT_OPTIONS.forEach { (value, label) ->
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
              // 再点一次已选中的排序项 = 只反转方向，不换排序项
              onOrderChange(if (currentOrder == "Ascending") "Descending" else "Ascending")
            } else {
              // 换排序项：由调用方把方向重置为该排序项的自然方向
              onSortChange(value)
            }
            expanded = false
          },
        )
      }
      HorizontalDivider()
      // 下半区：手动指定方向。留一个「自动」把手动覆盖清掉，回到各项的自然方向
      DropdownMenuItem(
        text = {
          Text(
            text = "升序",
            color = if (currentOrder == "Ascending") {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          )
        },
        onClick = {
          onOrderChange("Ascending")
          expanded = false
        },
      )
      DropdownMenuItem(
        text = {
          Text(
            text = "降序",
            color = if (currentOrder == "Descending") {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          )
        },
        onClick = {
          onOrderChange("Descending")
          expanded = false
        },
      )
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

/** 最低评分档位：显示文案 → 传给 Emby 的 MinCommunityRating（null 表示不限） */
private val RATING_OPTIONS: List<Pair<String, Float?>> = listOf(
  "不限" to null,
  "6 分以上" to 6f,
  "7 分以上" to 7f,
  "8 分以上" to 8f,
  "9 分以上" to 9f,
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
    "movies" -> listOf("Movie")
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
