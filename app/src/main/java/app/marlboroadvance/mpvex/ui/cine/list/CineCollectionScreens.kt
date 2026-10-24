package app.marlboroadvance.mpvex.ui.cine.list

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.AutoLoadMore
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPagingFooter
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.PagedList
import app.marlboroadvance.mpvex.ui.browser.emby.PagedResult
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.pagingSource
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.components.CineFilterChip
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterGrid
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.actions.CineActionSheetHost
import app.marlboroadvance.mpvex.ui.cine.actions.rememberCineActionSheetState
import app.marlboroadvance.mpvex.ui.cine.rememberCinePlayController
import app.marlboroadvance.mpvex.ui.cine.rememberCineWide
import app.marlboroadvance.mpvex.ui.cine.toCineMedia
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 收藏页（M3）。
 *
 * ## 先做「影片 + 演员」两个 tab
 * Classic 的收藏页有四个 tab（影片 / 演员 / 类型 / 工作室）。后两个是**筛选维度**，
 * Cine 的定位是「一次看到自己喜欢的东西」，把类型 / 工作室铺成 tab 只会让首屏多两层选择。
 * 影片与演员是真正的两类内容，保留；类型 / 工作室仍可从媒体库的筛选进入（回落到 Classic）。
 *
 * ## 为什么不做排序
 * 收藏接口（`getFavoriteItems`）不带 SortBy，本地排序只能对**已加载的那几页**排 ——
 * 翻页之后顺序就断了，用户会以为「排序坏了」。宁可不给，也不给一个会骗人的按钮。
 */
@Composable
fun CineFavoritesScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val backStack = LocalBackStack.current
  val prefs = koinInject<BrowserPreferences>()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val playController = rememberCinePlayController()
  val actionSheet = rememberCineActionSheetState()
  val scope = rememberCoroutineScope()

  var tab by remember { mutableIntStateOf(0) }
  var actors by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var actorsLoading by remember { mutableStateOf(false) }
  var reloadTicket by remember { mutableIntStateOf(0) }

  val page = remember { PagedList<EmbyItem>(keyOf = { it.Id ?: it.Name ?: "" }) }
  val gridState = rememberLazyGridState()
  val preloadDistance = rememberPredictivePreloadDistance(prefs)

  suspend fun loadPage(startIndex: Int, limit: Int): PagedResult<EmbyItem>? {
    val current = server ?: return null
    val result = viewModel.loadFavorites(current, startIndex, limit)
    return PagedResult(result.items, result.totalCount, limit)
  }

  LaunchedEffect(server?.id, tab, reloadTicket) {
    if (tab == 0) page.reload(::loadPage)
  }

  // 演员 tab：换进来才拉（loadFavoritePersons 是全量拉一遍再过滤，开销不小）
  LaunchedEffect(server?.id, tab) {
    val current = server ?: return@LaunchedEffect
    if (tab != 1) return@LaunchedEffect
    actorsLoading = true
    actors = runCatching { viewModel.loadFavoritePersons(current) }.getOrDefault(emptyList())
    actorsLoading = false
  }

  page.AutoLoadMore(
    source = gridState.pagingSource(),
    enabled = tab == 0,
    distance = preloadDistance,
    loader = ::loadPage,
  )

  val mediaUi = remember(server?.id, page.items) {
    val current = server ?: return@remember emptyList()
    page.items.map { viewModel.toCineMedia(current, it) }
  }

  val columns = if (wide) 4 else 2
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth: Dp = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(120.dp)
  val bottomInset = LocalNavigationBarHeight.current + 24.dp

  Column(modifier = modifier.fillMaxSize().background(colors.surface)) {
    // 根 tab，没有返回键
    CineGlassTopBar(title = stringResource(R.string.cine_nav_favorites))

    Row(
      modifier = Modifier.padding(start = space.screenH, top = 12.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      CineFilterChip(
        text = stringResource(R.string.cine_favorites_movies),
        selected = tab == 0,
        onClick = { tab = 0 },
      )
      CineFilterChip(
        text = stringResource(R.string.cine_favorites_people),
        selected = tab == 1,
        onClick = { tab = 1 },
      )
    }

    if (tab == 0) {
      val errorText = page.error
      when {
        page.loading && page.items.isEmpty() -> CollectionSkeleton(
          cardWidth = cardWidth,
          columns = columns,
        )

        errorText != null && page.items.isEmpty() -> CineStatePanel(
          icon = Icons.Outlined.CloudOff,
          title = stringResource(R.string.cine_state_error_title),
          description = errorText,
          isError = true,
          primaryLabel = stringResource(R.string.cine_state_error_action),
          onPrimary = { reloadTicket++ },
        )

        !page.loading && page.items.isEmpty() -> CineStatePanel(
          icon = Icons.Outlined.BookmarkBorder,
          title = stringResource(R.string.cine_favorites_empty_title),
          description = stringResource(R.string.cine_favorites_empty_desc),
        )

        else -> CinePosterGrid(
          items = mediaUi,
          cardWidth = cardWidth,
          columns = columns,
          state = gridState,
          contentPadding = PaddingValues(
            start = space.screenH,
            end = space.screenH,
            top = 14.dp,
            bottom = bottomInset,
          ),
          // 单击进详情，长按弹玻璃菜单（与首页 / 媒体库同一套）
          onClick = { media ->
            backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
          },
          onLongClick = { media -> actionSheet.show(media) },
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
    } else {
      FavoritePeopleGrid(
        server = server,
        people = actors,
        loading = actorsLoading,
        onOpenPerson = { person ->
          val personId = person.Id ?: return@FavoritePeopleGrid
          backStack.add(
            EmbyPersonScreen(
              personId = personId,
              personName = person.Name ?: "",
              personImageTag = person.PrimaryImageTag,
            ),
          )
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  CineActionSheetHost(
    state = actionSheet,
    server = server,
    viewModel = viewModel,
    playController = playController,
    onOpenDetail = { media ->
      backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
    },
    // 取消收藏后这条要从「收藏」里消失 ⇒ 重拉第一页
    onChanged = { reloadTicket++ },
    onDeleted = { reloadTicket++ },
  )
}

/** 收藏的演员：圆形头像墙 */
@Composable
private fun FavoritePeopleGrid(
  server: EmbyServer?,
  people: List<EmbyItem>,
  loading: Boolean,
  onOpenPerson: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val wide = rememberCineWide()
  val columns = if (wide) 6 else 3
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cell: Dp = (
    (screenWidth - space.screenH * 2 - space.gapForColumns(columns)) / columns
    ).coerceAtLeast(80.dp)

  when {
    loading && people.isEmpty() -> CollectionSkeleton(cardWidth = cell, columns = columns, aspect = 1f)

    people.isEmpty() -> CineStatePanel(
      icon = Icons.Outlined.BookmarkBorder,
      title = stringResource(R.string.cine_favorites_people_empty_title),
      description = stringResource(R.string.cine_favorites_people_empty_desc),
      modifier = modifier,
    )

    else -> {
      androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(
          start = space.screenH,
          end = space.screenH,
          top = 14.dp,
          bottom = LocalNavigationBarHeight.current + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(space.itemGap),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        items(people.size) { index ->
          val person = people[index]
          val personId = person.Id
          Column(
            modifier = Modifier
              .width(cell)
              .clip(RoundedCornerShape(12.dp))
              .clickable { onOpenPerson(person) },
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              modifier = Modifier
                .size(cell)
                .clip(CircleShape)
                .background(colors.skeleton),
            ) {
              EmbyImage(
                url = if (server != null && personId != null) {
                  EmbyClient.imageUrl(server, personId, "Primary", person.PrimaryImageTag, 300)
                } else {
                  null
                },
                contentDescription = person.Name,
                modifier = Modifier.fillMaxSize(),
                maxWidth = 300,
              )
            }
            Spacer(Modifier.height(8.dp))
            Text(
              text = person.Name ?: "",
              style = type.cardMeta.copy(fontSize = 12.sp),
              color = colors.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}

/**
 * Cine 历史页（M3）。
 *
 * 数据走 `loadHistory`（已看过的条目，服务端按播放时间倒序），与 Classic 同一个接口。
 * 网格里带进度条：历史页的高频动作是「接着看」，进度是它唯一需要的额外信息。
 */
@Composable
fun CineHistoryScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val backStack = LocalBackStack.current
  val prefs = koinInject<BrowserPreferences>()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val type = CineTheme.type
  val playController = rememberCinePlayController()
  val actionSheet = rememberCineActionSheetState()
  val scope = rememberCoroutineScope()

  var reloadTicket by remember { mutableIntStateOf(0) }
  val page = remember { PagedList<EmbyItem>(keyOf = { it.Id ?: it.Name ?: "" }) }
  val gridState = rememberLazyGridState()
  val preloadDistance = rememberPredictivePreloadDistance(prefs)

  suspend fun loadPage(startIndex: Int, limit: Int): PagedResult<EmbyItem>? {
    val current = server ?: return null
    val result = viewModel.loadHistory(current, startIndex, limit)
    return PagedResult(result.items, result.totalCount, limit)
  }

  LaunchedEffect(server?.id, reloadTicket) { page.reload(::loadPage) }
  page.AutoLoadMore(source = gridState.pagingSource(), distance = preloadDistance, loader = ::loadPage)

  val mediaUi = remember(server?.id, page.items) {
    val current = server ?: return@remember emptyList()
    page.items.map { viewModel.toCineMedia(current, it) }
  }

  val columns = if (wide) 4 else 2
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth: Dp = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(120.dp)
  val bottomInset = LocalNavigationBarHeight.current + 24.dp

  Column(modifier = modifier.fillMaxSize().background(colors.surface)) {
    CineGlassTopBar(title = stringResource(R.string.cine_nav_history))

    val errorText = page.error
    when {
      page.loading && page.items.isEmpty() -> CollectionSkeleton(
        cardWidth = cardWidth,
        columns = columns,
      )

      errorText != null && page.items.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.cine_state_error_title),
        description = errorText,
        isError = true,
        primaryLabel = stringResource(R.string.cine_state_error_action),
        onPrimary = { reloadTicket++ },
      )

      !page.loading && page.items.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.History,
        title = stringResource(R.string.cine_history_empty_title),
        description = stringResource(R.string.cine_history_empty_desc),
      )

      else -> CinePosterGrid(
        items = mediaUi,
        cardWidth = cardWidth,
        columns = columns,
        state = gridState,
        contentPadding = PaddingValues(
          start = space.screenH,
          end = space.screenH,
          top = 14.dp,
          bottom = bottomInset,
        ),
        // 单击进详情，长按弹玻璃菜单
        onClick = { media ->
          backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
        },
        onLongClick = { media -> actionSheet.show(media) },
        header = {
          Text(
            text = stringResource(
              R.string.cine_history_count,
              mediaUi.size,
            ),
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
          )
        },
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

  CineActionSheetHost(
    state = actionSheet,
    server = server,
    viewModel = viewModel,
    playController = playController,
    onOpenDetail = { media ->
      backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
    },
    onChanged = { reloadTicket++ },
    onDeleted = { reloadTicket++ },
  )
}

/** 骨架：与真实网格同列同比例，数据落地不跳位 */
@Composable
private fun CollectionSkeleton(
  cardWidth: Dp,
  columns: Int,
  aspect: Float = CineTheme.space.posterRatio,
) {
  val space = CineTheme.space
  val colors = CineTheme.colors

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = space.screenH, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    repeat(3) {
      Row(horizontalArrangement = Arrangement.spacedBy(space.itemGap)) {
        repeat(columns) {
          Column {
            Box(
              modifier = Modifier
                .size(cardWidth, cardWidth * aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.skeleton),
            )
            Spacer(Modifier.height(8.dp))
            Box(
              modifier = Modifier
                .size(cardWidth * 0.55f, 11.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.skeleton),
            )
          }
        }
      }
    }
  }
}

/** 多列间距合计（列数 - 1 个 itemGap） */
private fun app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing.gapForColumns(columns: Int): Dp =
  itemGap * (columns - 1).coerceAtLeast(0)
