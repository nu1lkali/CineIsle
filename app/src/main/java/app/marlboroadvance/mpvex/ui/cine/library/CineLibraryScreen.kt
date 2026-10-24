package app.marlboroadvance.mpvex.ui.cine.library

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import app.marlboroadvance.mpvex.ui.browser.emby.AutoLoadMore
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPagingFooter
import app.marlboroadvance.mpvex.ui.browser.emby.pagingSource
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.components.CineFilterChip
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineMediaListRow
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterGrid
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterRow
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.CineStatsRow
import app.marlboroadvance.mpvex.ui.cine.actions.CineActionSheetHost
import app.marlboroadvance.mpvex.ui.cine.actions.rememberCineActionSheetState
import app.marlboroadvance.mpvex.ui.cine.rememberCinePlayController
import app.marlboroadvance.mpvex.ui.cine.rememberCineWide
import app.marlboroadvance.mpvex.ui.cine.toCineMedia
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.ScrollToTopOnOrderChange
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 媒体库（设计稿 8.1）。
 *
 * ## 相对 Classic 的结构性差别
 * Classic 是「顶栏 + 分类树 + 长列表」，Cine 把分类压成**一行 Chip**，
 * 把「文件夹」从一棵树变成一次下钻：
 * - 普通库：Chip = 全部 / 类型 / 收藏；
 * - 文件夹下钻（[folderDive]）：Chip 行整块去掉，标题就是当前目录名，
 *   这一层真实装了什么就铺什么（不套 IncludeItemTypes —— 老页面踩过「整层被滤空」的坑）。
 *
 * ## 分页与刷新
 * 走项目统一的 [PagedList]：切 Chip / 换排序 / 下拉刷新都只是 `reload`，
 * 触底续拉由 [AutoLoadMore] 负责，预取距离读「预测性懒加载」偏好（不硬编码）。
 */
@Composable
fun CineLibraryScreen(
  libraryId: String,
  title: String,
  collectionType: String?,
  includeItemTypes: List<String>?,
  modifier: Modifier = Modifier,
  /** true = 这是点进一个文件夹后的下钻页（口径见 [EmbyLibraryScreen.isFolderDive]） */
  folderDive: Boolean = false,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val prefs = koinInject<BrowserPreferences>()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val type = CineTheme.type

  val categories = remember(collectionType, folderDive, includeItemTypes) {
    cineLibraryCategories(collectionType, folderDive, includeItemTypes)
  }
  var categoryIndex by remember(libraryId) { mutableIntStateOf(0) }
  val category = categories.getOrElse(categoryIndex) { categories.first() }

  // 排序沿用 Classic 的偏好键：Cine 里选「评分」，切回 Classic 也是「评分」。
  val sortBy by prefs.embyLibrarySortBy.collectAsState()
  // 没手动切过方向时跟随该排序项的自然顺序（与 Classic 同一规则）
  val sortOrder = if (sortBy == "SortName" || sortBy == "ProductionYear") "Ascending" else "Descending"

  var showSortSheet by remember { mutableStateOf(false) }
  var listMode by remember(libraryId) { mutableStateOf(false) }
  var reloadTicket by remember { mutableIntStateOf(0) }
  // 长按玻璃菜单：与首页共用同一份实现（状态放页面这层，Host 挂在页面末尾）
  val actionSheet = rememberCineActionSheetState()

  val page = remember(libraryId) { PagedList<EmbyItem>(keyOf = { it.Id ?: it.Name ?: "" }) }
  val isRefreshing = remember { mutableStateOf(false) }

  val gridState: LazyGridState = rememberLazyGridState()
  val listState = rememberLazyListState()
  val preloadDistance = rememberPredictivePreloadDistance(prefs)

  // 换库 / 换分类 / 换排序 / 刷新 = 整批替换，必须回顶：停在上一批的中段会让人以为
  // 「排序没生效」，随机排序时更明显。统一走 ScrollToTopOnOrderChange
  // （内部 requestScrollToItem，直接 scrollToItem 会被 LazyGrid 的 key 锚定抵掉，
  //  规矩见 ui/utils/ScrollToTop.kt）。
  ScrollToTopOnOrderChange(
    key = listOf<Any?>(libraryId, categoryIndex, sortBy, reloadTicket),
    gridState = gridState,
    listState = listState,
  )

  suspend fun loadPage(startIndex: Int, limit: Int): PagedResult<EmbyItem>? {
    val current = server ?: return null
    val result = viewModel.loadItems(
      server = current,
      parentId = libraryId,
      // 文件夹下钻：内容原样（不限类型 / 不限媒体形态），否则会把只有 Video 的目录滤空
      includeItemTypes = if (folderDive) null else category.includeItemTypes,
      sortBy = sortBy,
      sortOrder = sortOrder,
      startIndex = startIndex,
      limit = limit,
      // 普通库递归（整个库），文件夹下钻只看这一层 —— 与「文件夹」在 Classic 里的语义一致
      recursive = !folderDive,
      excludeItemTypes = if (!folderDive && category.includeItemTypes == null) {
        // 「全部」里剔掉明显不是内容的东西；文件夹保留，点进去就是下钻入口
        listOf("Season", "PhotoAlbum", "Photo", "Person", "TvChannel", "LiveTvProgram")
      } else {
        null
      },
      isFavorite = if (category.favoritesOnly) true else null,
    )
    return PagedResult(result.items, result.totalCount, limit)
  }

  LaunchedEffect(server?.id, libraryId, categoryIndex, sortBy, reloadTicket) {
    page.reload(::loadPage)
  }

  page.AutoLoadMore(
    source = if (listMode) listState.pagingSource() else gridState.pagingSource(),
    distance = preloadDistance,
    loader = ::loadPage,
  )

  // ── 展示模型 ──
  val mediaUi: List<CineMediaUi> = remember(server?.id, page.items) {
    val s = server ?: return@remember emptyList()
    page.items.map { item -> viewModel.toCineMedia(s, item) }
  }

  val playController = rememberCinePlayController()

  /**
   * 点卡片：**一律进详情**（与 Classic 一致），文件夹例外 —— 它是下钻入口。
   *
   * 播放不再由单击触发：Cine 的卡片是「看这是什么」，播放是详情页/长按菜单的动作。
   * 只有文件夹没有「详情」可看，所以保留原有的下钻语义。
   */
  fun open(media: CineMediaUi) {
    val item = media.item
    when {
      item.Type == "Folder" || item.Type == "CollectionFolder" -> {
        backStack.add(
          EmbyLibraryScreen(
            libraryId = item.Id ?: return,
            title = item.Name ?: title,
            collectionType = collectionType,
            isFolderDive = true,
          ),
        )
      }

      else -> backStack.add(EmbyDetailScreen(itemId = item.Id ?: return, title = item.Name ?: ""))
    }
  }

  val columns = if (wide) 4 else 2
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth: Dp = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(120.dp)

  val bottomInset = LocalNavigationBarHeight.current + 24.dp

  Column(modifier = modifier.fillMaxSize().background(colors.surface)) {
    CineGlassTopBar(
      title = title,
      onBack = { backStack.removeLastOrNull() },
    ) {
      CineIconButton(
        icon = if (listMode) Icons.Outlined.GridView else Icons.Outlined.ViewList,
        contentDescription = stringResource(
          if (listMode) R.string.cine_library_grid else R.string.cine_library_list,
        ),
        tint = colors.onSurfaceVariant,
        onClick = { listMode = !listMode },
      )
    }

    val errorText = page.error
    when {
      page.loading && page.items.isEmpty() -> CineGridSkeleton(
        cardWidth = cardWidth,
        columns = columns,
        modifier = Modifier.fillMaxSize(),
      )

      errorText != null && page.items.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.Search,
        title = stringResource(R.string.cine_state_error_title),
        description = errorText,
        isError = true,
        primaryLabel = stringResource(R.string.cine_state_error_action),
        onPrimary = { reloadTicket++ },
      )

      !page.loading && page.items.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.Search,
        title = stringResource(R.string.cine_library_empty_title),
        description = stringResource(R.string.cine_library_empty_desc),
      )

      else -> {
        val header: @Composable () -> Unit = {
          Column {
            if (!folderDive && categories.size > 1) {
              LazyRow(
                contentPadding = PaddingValues(horizontal = space.screenH),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
              ) {
                items(categories.size) { index ->
                  CineFilterChip(
                    text = stringResource(categories[index].labelRes),
                    selected = index == categoryIndex,
                    onClick = { categoryIndex = index },
                  )
                }
              }
            }
            CineStatsRow(
              countText = stringResource(
                R.string.cine_library_count,
                formatCount(page.total ?: page.items.size),
                stringResource(sortLabelRes(sortBy)),
              ),
              sortText = stringResource(R.string.cine_sort_action),
              onSortClick = { showSortSheet = true },
            )
          }
        }

        if (listMode) {
          Column(modifier = Modifier.fillMaxSize()) {
            header()
            LazyColumn(
              state = listState,
              modifier = Modifier
                .fillMaxSize()
                .cineHazeSource(LocalCineHaze.current)
                .navigationBarsPadding(),
              contentPadding = PaddingValues(bottom = bottomInset),
            ) {
              items(mediaUi.size, key = { mediaUi[it].id.ifBlank { "row-$it" } }) { index ->
                val media = mediaUi[index]
                CineMediaListRow(
                  media = media,
                  onClick = { open(media) },
                  onLongClick = { actionSheet.show(media) },
                )
              }
              item {
                EmbyPagingFooter(
                  appending = page.appending,
                  hasMore = page.hasMore,
                  error = page.error,
                  loadedCount = mediaUi.size,
                  onRetry = { scope.launch { page.retry(::loadPage) } },
                )
              }
            }
          }
        } else {
          PullRefreshGridBox(
            isRefreshing = isRefreshing,
            // 只重载一次：`reloadTicket++` 会再触发一次 LaunchedEffect 的 reload，
            // 两者都写就是「下拉一次发两次请求」
            onRefresh = { page.reload(::loadPage) },
            gridState = gridState,
            modifier = Modifier.fillMaxSize(),
          ) {
            CinePosterGrid(
              items = mediaUi,
              cardWidth = cardWidth,
              columns = columns,
              state = gridState,
              contentPadding = PaddingValues(
                start = space.screenH,
                end = space.screenH,
                bottom = bottomInset,
              ),
              onClick = { open(it) },
              onLongClick = { actionSheet.show(it) },
              header = header,
              footer = {
                EmbyPagingFooter(
                  appending = page.appending,
                  hasMore = page.hasMore,
                  error = page.error,
                  loadedCount = mediaUi.size,
                  onRetry = { scope.launch { page.retry(::loadPage) } },
                )
              },
            )
          }
        }
      }
    }
  }

  if (showSortSheet) {
    CineSortSheet(
      current = sortBy,
      onSelect = { key ->
        prefs.embyLibrarySortBy.set(key)
        showSortSheet = false
      },
      onDismiss = { showSortSheet = false },
    )
  }

  // 长按玻璃菜单：收藏 / 已看 / 演职员 / 刮削 / 刷新 / 编辑 / 删除（功能对齐 Classic）
  CineActionSheetHost(
    state = actionSheet,
    server = server,
    viewModel = viewModel,
    playController = playController,
    onOpenDetail = { media ->
      backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
    },
    // 改完重拉当前页：收藏在「只看收藏」分类里会直接决定这条还在不在列表里
    onChanged = { reloadTicket++ },
    onDeleted = { reloadTicket++ },
  )
}

/**
 * 媒体库分类。`labelRes` 而不是现成字符串：分类是纯数据，取词必须在组合里做。
 *
 * @param includeItemTypes null = 不限类型（「全部」）
 */
internal data class CineLibraryCategory(
  val labelRes: Int,
  val includeItemTypes: List<String>?,
  val favoritesOnly: Boolean = false,
)

private fun cineLibraryCategories(
  collectionType: String?,
  folderDive: Boolean,
  routeTypes: List<String>?,
): List<CineLibraryCategory> {
  // 文件夹下钻：这一层装什么就显示什么，不需要分类
  if (folderDive) return listOf(CineLibraryCategory(R.string.cine_category_all, null))

  return when (collectionType?.lowercase()) {
    "movies" -> listOf(
      CineLibraryCategory(R.string.cine_category_all, null),
      CineLibraryCategory(R.string.cine_category_movies, listOf("Movie")),
      CineLibraryCategory(R.string.cine_category_boxsets, listOf("BoxSet")),
      CineLibraryCategory(R.string.cine_category_favorites, null, favoritesOnly = true),
    )

    "tvshows" -> listOf(
      CineLibraryCategory(R.string.cine_category_all, null),
      CineLibraryCategory(R.string.cine_category_series, listOf("Series")),
      CineLibraryCategory(R.string.cine_category_favorites, null, favoritesOnly = true),
    )

    "music" -> listOf(
      CineLibraryCategory(R.string.cine_category_all, null),
      CineLibraryCategory(R.string.cine_category_albums, listOf("MusicAlbum")),
      CineLibraryCategory(R.string.cine_category_music, listOf("Audio")),
      CineLibraryCategory(R.string.cine_category_favorites, null, favoritesOnly = true),
    )

    else -> buildList {
      add(CineLibraryCategory(R.string.cine_category_all, null))
      // 路由带了明确的类型（如演员作品页）就只给那一类
      if (routeTypes != null && routeTypes.isNotEmpty()) {
        if ("Movie" in routeTypes) {
          add(CineLibraryCategory(R.string.cine_category_movies, listOf("Movie")))
        }
        if ("Series" in routeTypes) {
          add(CineLibraryCategory(R.string.cine_category_series, listOf("Series")))
        }
      } else {
        add(CineLibraryCategory(R.string.cine_category_movies, listOf("Movie")))
        add(CineLibraryCategory(R.string.cine_category_series, listOf("Series")))
        add(CineLibraryCategory(R.string.cine_category_videos, listOf("Video")))
      }
      add(CineLibraryCategory(R.string.cine_category_favorites, null, favoritesOnly = true))
    }
  }
}

/** 排序键 → 文案资源。值与 Emby `/Items` 的 `SortBy` 一致（与 Classic 共用偏好键）。 */
internal fun sortLabelRes(sortBy: String): Int = when (sortBy) {
  "SortName" -> R.string.cine_sort_name
  "CommunityRating" -> R.string.cine_sort_rating
  "ProductionYear" -> R.string.cine_sort_year
  "PlayCount" -> R.string.cine_sort_play_count
  "Runtime" -> R.string.cine_sort_runtime
  "Random" -> R.string.cine_sort_random
  else -> R.string.cine_sort_recent
}

/** 排序面板：单选列表。用 BottomSheet 而不是下拉菜单 —— 手机上单手够得着。 */
@Composable
internal fun CineSortSheet(
  current: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = colors.surface,
  ) {
    Column(modifier = Modifier.padding(bottom = 22.dp)) {
      Text(
        text = stringResource(R.string.cine_sort_label),
        style = type.sectionTitle,
        color = colors.onSurface,
        modifier = Modifier.padding(start = space.screenH, bottom = 8.dp),
      )
      CINE_SORT_KEYS.forEach { key ->
        val selected = key == current
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(key) }
            .padding(horizontal = space.screenH, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(sortLabelRes(key)),
            style = type.itemTitle,
            color = if (selected) colors.sectionAction else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (selected) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.progressFill),
            )
          }
        }
      }
    }
  }
}

private val CINE_SORT_KEYS = listOf(
  "DateCreated",
  "SortName",
  "CommunityRating",
  "ProductionYear",
  "Random",
)

/** 首屏骨架：按真实列数与卡片比例摆灰块，数据落地时不跳位 */
@Composable
private fun CineGridSkeleton(
  cardWidth: Dp,
  columns: Int,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space
  Column(
    modifier = modifier.padding(horizontal = space.screenH, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    repeat(3) {
      Row(horizontalArrangement = Arrangement.spacedBy(space.itemGap)) {
        repeat(columns) {
          Column {
            androidx.compose.material3.Surface(
              color = CineTheme.colors.skeleton,
              modifier = Modifier
                .size(cardWidth, cardWidth * space.posterRatio),
            ) {}
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Surface(
              color = CineTheme.colors.skeleton,
              modifier = Modifier
                .size(cardWidth * 0.6f, 12.dp),
            ) {}
          }
        }
      }
    }
  }
}

/** 千分位：`1,284`。手写而不用 NumberFormat —— 后者会跟着系统区域给出 `1.284`。 */
internal fun formatCount(value: Int): String {
  if (value < 1000) return value.toString()
  val raw = value.toString()
  val builder = StringBuilder()
  raw.forEachIndexed { index, ch ->
    if (index > 0 && (raw.length - index) % 3 == 0) builder.append(',')
    builder.append(ch)
  }
  return builder.toString()
}

/** 继续观看类操作的续播秒数（0 = 从头） */
internal fun EmbyItem.resumeSecondsOrZero(): Long =
  EmbyTicks.ticksToSeconds(UserData?.PlaybackPositionTicks ?: 0L)
