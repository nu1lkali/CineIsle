package app.marlboroadvance.mpvex.ui.cine

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
// ⚠️ 两个 collectAsState 必须都在：项目自定义的 `Preference<T>.collectAsState()` 读偏好，
//    Compose 的 `StateFlow<T>.collectAsState()` 读 StateFlow。只 import 后者时，
//    `preferences.embyHomeShowMix`（Preference<Boolean>）会报「receiver type mismatch」。
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarRecommendationViewModel
import app.marlboroadvance.mpvex.ui.cine.components.CineHeroBanner
import app.marlboroadvance.mpvex.ui.cine.components.CineHomeSkeleton
import app.marlboroadvance.mpvex.ui.cine.components.CineLibraryChip
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterRow
import app.marlboroadvance.mpvex.ui.cine.components.CineRefreshIcon
import app.marlboroadvance.mpvex.ui.cine.components.CineSectionHeader
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.CineTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineWideHeader
import app.marlboroadvance.mpvex.ui.cine.components.CineWideRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 首页。
 *
 * ## 数据来源与 Classic 完全一致
 * 同一个 [EmbyViewModel]（Activity 作用域）、同样的三发请求（媒体库 / 继续观看 / 最新加入），
 * 差别只在**怎么摆**。字段到文案的映射全部走 [CineItemMapper]，页面这里不拼任何元信息字符串。
 *
 * ## 一屏一个焦点
 * Hero 只挑**一条**：优先「有进度的」⇒ 回答「今晚接着看什么」；没有进度就退到最新加入
 * ⇒ 回答「新来了什么」。两条都没有时（空服务器 / 还没刮削）Hero 整块不画，
 * 页面从「媒体库」开始，并且顶栏直接落在折叠态（透明顶栏压在纯色背景上没法看）。
 *
 * ## 三种「没内容」必须分开
 * 1. **没连服务器** —— 不是错误，是还没配置：给「添加服务器」；
 * 2. **连上了但报错** —— 给「重试」，图标用 errorContainer 以示区分；
 * 3. **连上了、没报错、就是空的** —— 给「刷新」，说明「服务器上还没有扫描到媒体」。
 * 把三者混成一个「暂无内容」是 Classic 的老问题，用户分不清该去配置还是该重试。
 */
@Composable
fun CineHomeScreen(
  onOpenDetail: (EmbyItem) -> Unit,
  onOpenLibrary: (EmbyItem) -> Unit,
  onOpenSearch: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenServerManage: () -> Unit,
  onOpenAll: (String, List<CineMediaUi>) -> Unit,
  onDockHiddenChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val mixViewModel: SimilarRecommendationViewModel = viewModel(
    factory = SimilarRecommendationViewModel.factory(app),
  )

  val server by viewModel.currentServer.collectAsState()
  val libraries by viewModel.libraries.collectAsState()
  val resumeItems by viewModel.resumeItems.collectAsState()
  val latestItems by viewModel.latestItems.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()
  val mixItems by mixViewModel.mix.collectAsState()

  val browserPreferences = koinInject<BrowserPreferences>()
  val showHomeMix by browserPreferences.embyHomeShowMix.collectAsState()
  val homeMixCount by browserPreferences.embyHomeMixCount.collectAsState()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val screenHeight = rememberCineScreenHeightDp()
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  // 「首屏真的拉过一次」——用它区分「刚进来还没数据」与「服务器上就是空的」。
  // isLoading 单独不够：VM 的 init 里才刚 launch，第一帧 isLoading 还是 false。
  var firstLoadDone by remember { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.awaitHomeLoaded()
    firstLoadDone = true
  }

  // 「随便看看」：排在首页三请求之后（与 Classic 的取舍一致，避免抢服务端）
  val serverId = server?.id ?: 0L
  LaunchedEffect(serverId, showHomeMix) {
    if (!showHomeMix || serverId == 0L) return@LaunchedEffect
    val current = server ?: return@LaunchedEffect
    viewModel.awaitHomeLoaded()
    mixViewModel.loadMix(current, seed = null, limit = homeMixCount)
  }

  // ── 展示模型（唯一映射入口在 CineItemMapper） ──
  val resumeUi: List<CineMediaUi> = remember(server?.id, resumeItems) {
    val s = server ?: return@remember emptyList()
    resumeItems.map { viewModel.toCineMedia(s, it) }
  }
  val latestUi: List<CineMediaUi> = remember(server?.id, latestItems) {
    val s = server ?: return@remember emptyList()
    latestItems.map { viewModel.toCineMedia(s, it) }
  }
  val mixUi: List<CineMediaUi> = remember(server?.id, mixItems) {
    val s = server ?: return@remember emptyList()
    mixItems.map { viewModel.toCineMedia(s, it) }
  }

  // Hero 轮播：继续观看优先（回答「今晚接着看什么」），不够时用最新加入补位。
  // 上限 4 条 —— 设计稿的指示点就是 4 个；再多就不再是「一屏一个焦点」。
  // distinctBy：同一部剧可能既在继续观看里、又在最新加入里，撞 id 会让 Pager 的 key 重复。
  val heroList: List<CineMediaUi> = remember(resumeUi, latestUi) {
    (resumeUi + latestUi).distinctBy { it.id }.take(HERO_MAX_PAGES)
  }
  val heroUi: CineMediaUi? = heroList.firstOrNull()
  // 「来自继续观看」决定主按钮文案。
  // ⚠️ 不能只看 `hasProgress`：剧集（Series）自身的 PlaybackPositionTicks 是 0，
  //    进度记在分集上 —— 但它出现在 resumeItems 里就说明「有事可续」，
  //    这时按钮必须写「继续观看」，否则用户会以为自己点的是从头播。
  val resumeIds = remember(resumeUi) { resumeUi.map { it.id }.toSet() }

  // 收藏的本地覆盖：toggle 之后首页数据不会重拉，仅靠 item.UserData 里的旧值，
  // 图标会「点了没反应」。失败时回滚到服务器真值（toggleFavorite 内部已做回读确认）。
  // key 用条目 id：轮播换页之后，每一页的收藏态必须各自独立。
  val favoriteOverrides = remember { mutableStateMapOf<String, Boolean>() }
  var favoriteBusyId by remember { mutableStateOf<String?>(null) }

  val failureText = stringResource(R.string.cine_favorite_failed)

  // 播放统一走 rememberCinePlay（发起 + 失败提示只有一份实现）
  val play = rememberCinePlay()

  // 分区标题与「全部」既要用在头部、又要当作网格页的标题传出去 ⇒ 在组合里取一次字符串，
  // 不要在 item 的 lambda 里再调 stringResource（那里拿不到组合上下文，也编译不过）
  val titleLibraries = stringResource(R.string.cine_section_libraries)
  val titleResume = stringResource(R.string.cine_section_resume)
  val titleLatest = stringResource(R.string.cine_section_latest)
  val labelAll = stringResource(R.string.cine_action_all)

  fun toggleFavorite(media: CineMediaUi) {
    val current = server ?: return
    // 一次只允许一个请求在飞：连点不同卡片时，后一个的乐观值会把前一个的
    // 服务器回值覆盖掉（回读是全量的，会把界面写回旧状态）
    if (favoriteBusyId != null) return
    val next = !(favoriteOverrides[media.id] ?: media.isFavorite)
    favoriteOverrides[media.id] = next
    favoriteBusyId = media.id
    scope.launch {
      val result = viewModel.toggleFavorite(current, media.item, next)
      favoriteBusyId = null
      val confirmed = result.getOrNull()
      if (result.isSuccess && confirmed != null) {
        favoriteOverrides[media.id] = confirmed
      } else {
        // 失败：把这一条恢复成「服务器说什么就是什么」，并明确告诉用户没成
        favoriteOverrides.remove(media.id)
        Toast.makeText(context, failureText, Toast.LENGTH_SHORT).show()
      }
    }
  }

  // ── 滚动：折叠进度 / 视差 / Dock 自动收起 ──
  val listState = rememberLazyListState()
  val collapse by remember {
    derivedStateOf {
      if (listState.firstVisibleItemIndex > 0) {
        1f
      } else {
        (listState.firstVisibleItemScrollOffset / 120f).coerceIn(0f, 1f)
      }
    }
  }

  val heroHeight: Dp = if (wide) {
    minOf(392.dp, (screenHeight * 0.5f).dp).coerceAtLeast(300.dp)
  } else {
    minOf(space.heroHeight, (screenHeight * 0.62f).dp).coerceAtLeast(320.dp)
  }
  val heroHeightPx = with(density) { heroHeight.toPx() }
  val parallax by remember(heroHeightPx) {
    derivedStateOf {
      if (listState.firstVisibleItemIndex > 0) {
        1f
      } else {
        (listState.firstVisibleItemScrollOffset / heroHeightPx).coerceIn(0f, 1f)
      }
    }
  }

  val dockCallback by rememberUpdatedState(onDockHiddenChange)
  LaunchedEffect(listState) {
    var lastIndex = 0
    var lastOffset = 0
    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
      .collect { (index, offset) ->
        // 8px 的容差：LazyColumn 在惯性滚动收尾时会来回抖一两像素，
        // 不设容差的话 Dock 会在边缘反复弹出收回
        val down = index > lastIndex || (index == lastIndex && offset > lastOffset + 8)
        val up = index < lastIndex || (index == lastIndex && offset < lastOffset - 8)
        if (down && index > 0) dockCallback(true)
        if (up) dockCallback(false)
        lastIndex = index
        lastOffset = offset
      }
  }

  // ── 卡片尺寸：宽屏按「内容区宽度 ÷ 列数」算，与设计稿 168dp@834 对齐 ──
  val railWidth = space.railWidth
  val contentWidth = if (wide) {
    (LocalConfiguration.current.screenWidthDp.dp - railWidth - space.screenH * 2).coerceAtLeast(200.dp)
  } else {
    (LocalConfiguration.current.screenWidthDp.dp - space.screenH * 2).coerceAtLeast(200.dp)
  }
  val posterWidth: Dp = if (wide) {
    ((contentWidth - space.itemGap * 3) / 4).coerceAtLeast(120.dp)
  } else {
    space.posterWidth(wide = false)
  }
  val wideCardWidth: Dp = if (wide) {
    ((contentWidth - space.itemGap * 2) / 3).coerceAtLeast(180.dp)
  } else {
    space.wideCardWidth
  }

  val isRefreshing = remember { mutableStateOf(false) }
  val noServer = firstLoadDone && server == null
  val hasAnyContent = resumeUi.isNotEmpty() || latestUi.isNotEmpty() || libraries.isNotEmpty()
  val showSkeleton = !firstLoadDone || (isLoading && !hasAnyContent)

  Box(modifier = modifier.fillMaxSize()) {
    when {
      showSkeleton -> CineHomeSkeleton(
        heroHeight = heroHeight,
        wideCardWidth = wideCardWidth,
        // 宽屏与真实版面同样下移「状态栏 + 顶栏」，否则数据落地的一瞬间整页会往上跳
        modifier = Modifier
          .fillMaxSize()
          .then(
            if (wide) {
              Modifier.statusBarsPadding().padding(top = space.wideHeaderHeight)
            } else {
              Modifier
            },
          ),
      )

      noServer -> CinePanelScaffold {
        CineStatePanel(
          icon = Icons.Outlined.CloudOff,
          title = stringResource(R.string.cine_state_no_server_title),
          description = stringResource(R.string.cine_state_no_server_desc),
          primaryLabel = stringResource(R.string.cine_state_no_server_action),
          onPrimary = onOpenServerManage,
        )
      }

      error != null && !hasAnyContent -> CinePanelScaffold {
        CineStatePanel(
          icon = Icons.Outlined.CloudOff,
          title = stringResource(R.string.cine_state_error_title),
          description = error,
          isError = true,
          primaryLabel = stringResource(R.string.cine_state_error_action),
          onPrimary = { viewModel.refreshHome() },
        )
      }

      !hasAnyContent -> CinePanelScaffold {
        CineStatePanel(
          icon = Icons.Outlined.VideoLibrary,
          title = stringResource(R.string.cine_state_empty_title),
          description = stringResource(R.string.cine_state_empty_desc),
          primaryLabel = stringResource(R.string.cine_state_error_action),
          onPrimary = { viewModel.refreshHome() },
        )
      }

      else -> {
        PullRefreshBox(
          isRefreshing = isRefreshing,
          onRefresh = {
            viewModel.refreshHomeAndWait()
            // 「随便看看」跟着一起换一批，否则下拉刷新后最底下一行还是旧内容
            if (showHomeMix) {
              server?.let { mixViewModel.loadMix(it, seed = null, limit = homeMixCount) }
            }
          },
          modifier = Modifier.fillMaxSize(),
          listState = listState,
        ) {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxSize()
              .navigationBarsPadding()
              // 宽屏的顶栏是**不吃在 Hero 上**的实体条（见 CineWideHeader），
              // 内容必须整体下移一个「状态栏 + 64dp」；手机端 Hero 顶到屏幕最上面，
              // 顶栏用半透明压在上面，所以不加这段内缩。
              .then(
                if (wide) {
                  Modifier.statusBarsPadding().padding(top = space.wideHeaderHeight)
                } else {
                  Modifier
                },
              ),
            contentPadding = PaddingValues(bottom = space.contentBottomInset),
          ) {
            // ⚠️ Hero 必须是 item 0 且**满宽出血**：折叠进度、视差都直接读它的滚动偏移。
            //    包一层带 padding 的容器会让偏移与视觉位移对不上。
            item(key = "hero") {
              if (heroList.isNotEmpty()) {
                // Pager 状态放在 item 里：只有 Hero 这一块持有它。
                // 提到页面级的话，切 tab 再回来还停在第三张 —— 首页应当回到第一张。
                val pagerState = rememberPagerState(pageCount = { heroList.size })
                HorizontalPager(
                  state = pagerState,
                  modifier = Modifier.fillMaxWidth(),
                  key = { heroList[it].id },
                ) { page ->
                  val media = heroList[page]
                  CineHeroBanner(
                    media = media,
                    heroHeight = heroHeight,
                    parallaxFraction = parallax,
                    isFavorite = favoriteOverrides[media.id] ?: media.isFavorite,
                    favoriteBusy = favoriteBusyId == media.id,
                    primaryLabel = stringResource(
                      if (media.id in resumeIds) R.string.cine_action_resume else R.string.cine_action_play,
                    ),
                    onPlay = { play(media.item, media.resumeSeconds) },
                    onOpenDetail = { onOpenDetail(media.item) },
                    onToggleFavorite = { toggleFavorite(media) },
                    // 宽屏顶栏在 Hero 之外，不需要顶部压暗
                    topScrimHeight = if (wide) 0.dp else 200.dp,
                    // 宽屏不画指示点（设计稿 03）：宽屏 Hero 矮，四个点会挤到按钮右边
                    pageCount = if (wide) 0 else heroList.size,
                    currentPage = pagerState.currentPage,
                  )
                }
              } else {
                // 有内容但没有可当 Hero 的条目（比如只有音乐库）：留一块与顶栏等高的空白，
                // 否则第一个分区会顶到状态栏图标下面
                Spacer(
                  Modifier
                    .fillMaxWidth()
                    .height(if (wide) 12.dp else 96.dp),
                )
              }
            }

            if (libraries.isNotEmpty()) {
              item(key = "libraries-head") {
                Spacer(Modifier.height(if (heroUi != null) space.firstSectionTop else 8.dp))
                CineSectionHeader(
                  title = titleLibraries,
                  count = libraries.size,
                )
              }
              item(key = "libraries-row") {
                LazyRow(
                  contentPadding = PaddingValues(horizontal = space.screenH),
                  horizontalArrangement = Arrangement.spacedBy(space.itemGap),
                ) {
                  items(libraries.size) { index ->
                    val library = libraries[index]
                    CineLibraryChip(
                      name = library.Name ?: titleLibraries,
                      icon = libraryIcon(library.CollectionType),
                      onClick = { onOpenLibrary(library) },
                    )
                  }
                }
              }
            }

            if (resumeUi.isNotEmpty()) {
              item(key = "resume-head") {
                Spacer(Modifier.height(space.sectionGap))
                CineSectionHeader(
                  title = titleResume,
                  actionLabel = labelAll,
                  onAction = { onOpenAll(titleResume, resumeUi) },
                  contentPadding = PaddingValues(horizontal = space.screenH),
                )
              }
              item(key = "resume-row") {
                CineWideRow(
                  items = resumeUi,
                  cardWidth = wideCardWidth,
                  wide = wide,
                  onClick = { play(it.item, it.resumeSeconds) },
                  onLongClick = { onOpenDetail(it.item) },
                )
              }
            }

            if (latestUi.isNotEmpty()) {
              item(key = "latest-head") {
                Spacer(Modifier.height(space.sectionGap))
                CineSectionHeader(
                  title = titleLatest,
                  actionLabel = labelAll,
                  onAction = { onOpenAll(titleLatest, latestUi) },
                  contentPadding = PaddingValues(horizontal = space.screenH),
                )
              }
              item(key = "latest-row") {
                CinePosterRow(
                  items = latestUi,
                  cardWidth = posterWidth,
                  wide = wide,
                  onClick = { play(it.item, it.resumeSeconds) },
                  onLongClick = { onOpenDetail(it.item) },
                )
              }
            }

            if (showHomeMix && mixUi.isNotEmpty()) {
              item(key = "mix-head") {
                Spacer(Modifier.height(space.sectionGap))
                CineSectionHeader(
                  title = stringResource(R.string.cine_section_mix),
                  actionLabel = stringResource(R.string.cine_action_shuffle),
                  actionIcon = CineRefreshIcon,
                  onAction = {
                    server?.let { mixViewModel.loadMix(it, seed = null, limit = homeMixCount) }
                  },
                  contentPadding = PaddingValues(horizontal = space.screenH),
                )
              }
              item(key = "mix-row") {
                CinePosterRow(
                  items = mixUi,
                  cardWidth = posterWidth,
                  wide = wide,
                  onClick = { play(it.item, it.resumeSeconds) },
                  onLongClick = { onOpenDetail(it.item) },
                )
              }
            }
          }
        }
      }
    }

    if (wide) {
      // 宽屏顶栏固定在内容区顶部（不压在 Hero 上，也不折叠）
      CineWideHeader(
        title = stringResource(R.string.app_name),
        onSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    } else {
      CineTopBar(
        // 没有 Hero 时直接落在折叠态：白色图标压在纯色底上会看不见
        collapse = if (heroUi != null) collapse else 1f,
        title = stringResource(R.string.app_name),
        onSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
  }
}

/** Hero 轮播最多 4 页（与设计稿的 4 个指示点对齐，也避免首屏占满内存） */
private const val HERO_MAX_PAGES = 4

/** 顶部工具栏占位：给出「状态面板别钻到顶栏下面」的统一内缩 */
@Composable
private fun CinePanelScaffold(content: @Composable () -> Unit) {
  val space = CineTheme.space
  // 宽屏顶栏是 64dp 的实体条，手机上的折叠顶栏是 52dp —— 两者高度不同，
  // 统一按 52 算的话宽屏空态图标会钻到顶栏底下 12dp
  val headerHeight = if (rememberCineWide()) space.wideHeaderHeight else space.topBarHeight
  Box(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .padding(top = headerHeight),
    contentAlignment = Alignment.TopCenter,
  ) {
    content()
  }
}

/** 按媒体库类型挑图标：与 Emby 的 collectionType 取值一一对应，认不出的一律文件夹 */
private fun libraryIcon(collectionType: String?): ImageVector = when (collectionType?.lowercase()) {
  "movies" -> Icons.Rounded.Movie
  "tvshows" -> Icons.Rounded.Tv
  "music" -> Icons.Rounded.LibraryMusic
  "photos" -> Icons.Rounded.PhotoLibrary
  "boxsets" -> Icons.Rounded.Collections
  else -> Icons.Rounded.Folder
}
