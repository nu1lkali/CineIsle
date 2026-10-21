package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyBatchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.koin.compose.koinInject

/**
 * 收藏页的内容类型。影片可播放；演员 / 类型 / 工作室是「点进去看作品清单」的入口，
 * 本身不可播放。
 */
private enum class FavoriteTab(val label: String) {
  MOVIE("影片"),
  ACTOR("演员"),
  GENRE("类型"),
  STUDIO("工作室"),
  ;

  /** 是否为聚合 tab（演员 / 类型 / 工作室），即条目本身不可播放。 */
  val isAggregate: Boolean get() = this != MOVIE
}

/** 收藏影片每页条数。 */
private const val MOVIE_PAGE_SIZE = 100

/**
 * 搜索时最多自动往后补拉几页。
 *
 * 本地过滤只能看见已加载的页，不补拉就搜不到后面才出现的片名；但收藏几千部时全翻完
 * 太费流量，故设上限（10 页 ≈ 1000 项），更多内容仍可正常滚动到底加载。
 */
private const val SEARCH_AUTOFILL_MAX_PAGES = 10

/**
 * Emby 收藏页：展示服务器上标记为收藏的媒体、演员、类型与工作室。
 *
 * 标题右侧下拉切换 tab（默认影片），各 tab 列表各自缓存一份，来回切换不重复发请求；
 * 顶栏刷新按钮只重拉当前 tab 并作废其缓存。交互与首页一致：单击进详情 / 作品清单，长按多选。
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

  // 当前 tab 落盘到偏好而非 rememberSaveable：需要跨冷启动恢复（重启后还停在上次选的 tab），
  // 而 saveable 只防旋转。两者同时维护会双写同一状态，出现不一致时更难排查。
  val browserPreferences = koinInject<BrowserPreferences>()
  // 每行个数跟随媒体库设置，避免两页栏数不一致
  val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()
  // 底栏高度由宿主下发；+16 为呼吸空间
  val favoritesBottomPadding = LocalNavigationBarHeight.current + 16.dp
  var tab by remember {
    mutableStateOf(
      FavoriteTab.entries.firstOrNull { it.name == browserPreferences.embyFavoritesTab.get() }
        ?: FavoriteTab.MOVIE,
    )
  }

  // ── 各 tab 各自的数据 ──
  // 影片走 [PagedList]（分页）；其余三个 tab 一次拉全量，沿用同一套 list 缓存，按 server.id 区分。
  // ⚠️⚠️ 这三份列表必须整体替换（`value = 新 list`），不能用 mutableStateListOf 就地改 ——
  // 原因见下方 filteredItems 处：SnapshotStateList 实例永远不变，当 remember 的 key 等于没有 key。
  var actorItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val actorCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  var genreItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val genreCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  var studioItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  val studioCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  /** 影片 tab 的分页缓存：serverId → 已加载页数（0 = 没加载过） */
  val movieCache = remember { mutableStateOf<Pair<Long, Int>?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  /** 收藏影片的分页状态，滚到底自动续拉。 */
  val moviePage = remember { PagedList<EmbyItem>(MOVIE_PAGE_SIZE, keyOf = { it.Id ?: it.Name ?: "" }) }

  /** 展示列表 = 当前 tab 对应的那一份 */
  val items: List<EmbyItem> = when (tab) {
    FavoriteTab.MOVIE -> moviePage.items
    FavoriteTab.ACTOR -> actorItems
    FavoriteTab.GENRE -> genreItems
    FavoriteTab.STUDIO -> studioItems
  }

  // ── 搜索 / 排序 ──
  // 过滤与排序都在已加载的页上做，即时且不发请求。分页后本地搜索只覆盖已翻到的页，
  // 故 searchQuery 变化时会自动补拉（有上限，见 SEARCH_AUTOFILL_MAX_PAGES）。
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var sort by remember {
    mutableStateOf(
      FavoriteSort.entries.firstOrNull { it.name == browserPreferences.embyFavoritesSort.get() }
        ?: FavoriteSort.NAME,
    )
  }
  // 方向同样落盘：只存排序项的话，返回页面后方向会被打回默认，比整体重置更让人困惑
  var sortAsc by remember { mutableStateOf(browserPreferences.embyFavoritesSortAsc.get()) }
  // 随机种子只在「切到随机 / 再点一次随机」时变
  // ⚠️ 不能每次重组现洗 —— 敲一个搜索字都会重组，列表会在手指底下乱跳
  var randomSeed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }

  // ⚠️⚠️ `remember(items, ...)` 成立的前提是 items 每次更新都为新的 List 实例。
  // 若用 mutableStateListOf，实例不变 ⇒ key 不变 ⇒ 缓存死在首次（空列表）那一版，
  // 表现为一进页面就弹「没有匹配「」的收藏」且网格空白。
  val filteredItems = remember(items, searchQuery) {
    items.filter {
      searchQuery.isBlank() || it.Name?.contains(searchQuery.trim(), ignoreCase = true) == true
    }
  }
  val visibleItems = remember(filteredItems, sort, sortAsc, randomSeed) {
    sort.apply(filteredItems, sortAsc, randomSeed)
  }

  // ── 多选模式（批量操作）──
  // 长按任意卡片进入；选中集合存条目 Id；退出时清空。
  // ⚠️ selectionMode 与 selectedIds 必须一起 saveable：只改开关不改集合，
  // 旋转后会停在「已进入多选但一条没选」的矛盾状态。
  var selectionMode by rememberSaveable { mutableStateOf(false) }
  // SnapshotStateList 没有默认 Saver，用 listSaver 存成 List<String>，恢复时重建
  val selectedIds = rememberSaveable(
    saver = listSaver<SnapshotStateList<String>, String>(
      save = { it.toList() },
      restore = { restored ->
        val list = mutableStateListOf<String>()
        list.addAll(restored)
        list
      },
    ),
  ) { mutableStateListOf<String>() }
  // 批量动作的结果提示 + 一次性撤销入口（只保留最近一次）
  val snackbarHostState = remember { SnackbarHostState() }
  // 防误触：底部按钮只记意图，弹窗确认后才执行
  var pendingBatch by remember { mutableStateOf<BatchAction?>(null) }
  // 与详情页共用同一个下载管理器（任务队列共享）
  val downloadViewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
  )

  // 进多选就收起外层底部导航栏：本页批量条画在屏幕最底部，而宿主内容全屏铺、
  // 导航栏浮在其上，不收起来按钮会被整条盖住。
  DisposableEffect(selectionMode) {
    app.marlboroadvance.mpvex.ui.browser.MainScreen.updateBottomBarVisibility(!selectionMode)
    onDispose {
      // key 变化时 onDispose 先跑、之后才执行上面那行，两次写入语义一致；
      // 离开页面时则由这里恢复。
      app.marlboroadvance.mpvex.ui.browser.MainScreen.updateBottomBarVisibility(true)
    }
  }

  // 多选态下系统返回先退多选，避免一按返回就直接退出 App；
  // enabled ⇒ 非多选态完全不拦截，交给宿主正常出栈。
  BackHandler(enabled = selectionMode) {
    selectionMode = false
    selectedIds.clear()
  }

  /**
   * 切换某条的选中态。首次调用会顺带进入多选模式，实现「长按 → 直接多选」。
   */
  fun toggleSelection(id: String?) {
    if (id == null) return
    if (!selectionMode) {
      selectionMode = true
      selectedIds.clear()
    }
    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
  }

  /**
   * 收藏影片的分页加载器（首屏 / 续拉共用，区别只在起始下标）。
   *
   * 必须显式标注 `suspend (Int, Int) -> PagedResult<EmbyItem>?`：靠推导得到的类型里
   * `loadFavorites` 不带 suspend，编译器会拒绝该调用。
   */
  fun moviePageLoader(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer):
    suspend (Int, Int) -> PagedResult<EmbyItem>? = { start, limit ->
    val page = viewModel.loadFavorites(current, start, limit)
    // 网格 key = Id ?: Name：服务端可能重复返回同一条目，重复 key 会撞崩 LazyVerticalGrid
    PagedResult(
      items = page.items.distinctBy { it.Id ?: it.Name ?: "" },
      totalCount = page.totalCount,
      pageSize = limit,
    )
  }

  suspend fun loadMovies(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) {
    // 只有本服务器已拉过至少一页才跳过请求（切 tab 回来不再发一次）
    val cached = movieCache.value
    if (!force && cached != null && cached.first == current.id && moviePage.items.isNotEmpty()) {
      return
    }
    runCatching { moviePage.reload(moviePageLoader(current)) }
      .onFailure { error = it.message ?: "加载收藏失败" }
    if (moviePage.error != null) {
      error = moviePage.error
      movieCache.value = null
      return
    }
    error = null
    movieCache.value = current.id to 1
  }

  /**
   * 滚到底：续拉下一页收藏影片，并把页数并进 [movieCache]，
   * 改搜索条件时据此判断还要不要继续往后翻。
   */
  suspend fun loadMoreMovies(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer) {
    if (!moviePage.hasMore) return
    val before = moviePage.items.size
    runCatching { moviePage.loadMore(moviePageLoader(current)) }
      .onFailure { error = it.message ?: "加载更多失败" }
    if (moviePage.items.size > before) {
      movieCache.value = current.id to ((movieCache.value?.second ?: 0) + 1)
    }
  }

  /**
   * 「一次性拉全量」类 tab（演员 / 类型 / 工作室）的通用加载器：命中缓存则直接摊平，
   * 否则请求一次、按 server.id 落缓存并灌进列表。
   *
   * @param cache  该 tab 的页内缓存（serverId → 名单）
   * @param target 列表赋值器（必须整体替换，理由见其声明处）
   * @param fetch  真正发请求的挂起函数
   */
  suspend fun loadAll(
    current: app.marlboroadvance.mpvex.domain.emby.EmbyServer,
    force: Boolean,
    cache: androidx.compose.runtime.MutableState<Pair<Long, List<EmbyItem>>?>,
    target: (List<EmbyItem>) -> Unit,
    failureMessage: String,
    fetch: suspend () -> List<EmbyItem>,
  ) {
    val cached = cache.value
    if (!force && cached != null && cached.first == current.id) {
      target(cached.second)
      return
    }
    runCatching { fetch() }
      .onSuccess { list ->
        // 按同一 key 口径去重，防网格崩溃
        val deduped = list.distinctBy { it.Id ?: it.Name ?: "" }
        cache.value = current.id to deduped
        target(deduped)
      }
      .onFailure { error = it.message ?: failureMessage }
  }

  suspend fun loadActors(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = actorCache,
      target = { actorItems = it },
      failureMessage = "加载收藏演员失败",
      fetch = { viewModel.loadFavoritePersons(current, forceRefresh = force) },
    )

  suspend fun loadGenres(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = genreCache,
      target = { genreItems = it },
      failureMessage = "加载收藏类型失败",
      fetch = { viewModel.loadFavoriteGenres(current, forceRefresh = force) },
    )

  suspend fun loadStudios(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) =
    loadAll(
      current = current,
      force = force,
      cache = studioCache,
      target = { studioItems = it },
      failureMessage = "加载收藏工作室失败",
      fetch = { viewModel.loadFavoriteStudios(current, forceRefresh = force) },
    )

  suspend fun load(force: Boolean) {
    // 冷启动时当前服务器可能还没恢复，这里等一下，避免误判成「没有服务器」
    val current = viewModel.currentServerOrAwait() ?: run {
      isLoading = false
      return
    }
    isLoading = true
    error = null
    when (tab) {
      FavoriteTab.MOVIE -> loadMovies(current, force)
      FavoriteTab.ACTOR -> loadActors(current, force)
      FavoriteTab.GENRE -> loadGenres(current, force)
      FavoriteTab.STUDIO -> loadStudios(current, force)
    }
    isLoading = false
  }

  /**
   * 一次批量操作的收尾：退出多选态 → 弹结果 Snackbar。
   *
   * [undo] 非空时多给一个「撤销」按钮，点了把反向动作跑一遍；只保证最近一次可撤销，
   * 再操作就被覆盖，不做撤销栈。
   */
  fun finishBatch(
    actionLabel: String,
    result: EmbyBatchResult,
    undo: (suspend () -> Unit)?,
  ) {
    selectionMode = false
    selectedIds.clear()
    scope.launch {
      val res = snackbarHostState.showSnackbar(
        message = batchResultText(actionLabel, result),
        actionLabel = if (undo != null) "撤销" else null,
        duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
      )
      if (res == SnackbarResult.ActionPerformed && undo != null) {
        undo()
        snackbarHostState.showSnackbar("已撤销")
      }
    }
  }

  /**
   * 执行一个已确认的批量动作；跑完把反向动作交给 [finishBatch] 当撤销凭据。
   *
   * ⚠️ ids / items 必须在进协程之前取快照：finishBatch 一进来就清空 selectedIds，
   * 闭包里再读只剩空集合。定义在 [load] 之后也是必须的 —— 局部函数不能前向引用。
   */
  fun executeBatch(action: BatchAction) {
    val current = server
    val ids = selectedIds.toList()
    val picked = visibleItems.filter { it.Id != null && it.Id in selectedIds }
    when (action) {
      BatchAction.MARK_PLAYED ->
        if (current != null && ids.isNotEmpty()) {
          scope.launch {
            val r = viewModel.setPlayedBatch(current, ids, true)
            finishBatch("标为已看", r) { viewModel.setPlayedBatch(current, ids, false) }
          }
        }

      BatchAction.MARK_UNPLAYED ->
        if (current != null && ids.isNotEmpty()) {
          scope.launch {
            val r = viewModel.setPlayedBatch(current, ids, false)
            finishBatch("标为未看", r) { viewModel.setPlayedBatch(current, ids, true) }
          }
        }

      BatchAction.UNFAVORITE ->
        if (current != null && picked.isNotEmpty()) {
          scope.launch {
            val r = viewModel.setFavoriteBatch(current, picked, false)
            // 取消收藏后这批已不在收藏页，重拉让列表同步
            load(force = true)
            finishBatch("取消收藏", r) {
              // 撤销 = 收藏回去（影片 / 演员同一路径，setFavoriteBatch 内部按类型分流）
              viewModel.setFavoriteBatch(current, picked, true)
              load(force = true)
            }
          }
        }

      BatchAction.DOWNLOAD -> {
        if (picked.isEmpty()) return
        var queued = 0
        var skipped = 0
        picked.forEach { item ->
          when (downloadViewModel.enqueue(server ?: return@forEach, item)) {
            EmbyEnqueueResult.INVALID -> skipped++
            else -> queued++
          }
        }
        // 下载只是入队，误触代价小（下载页可随时暂停 / 删除），故不给撤销，
        // 免得「撤销」把队列里原有的任务一起删掉。
        selectionMode = false
        selectedIds.clear()
        Toast.makeText(
          context,
          if (skipped == 0) "已加入下载队列：$queued 项" else "已加入下载队列：$queued 项，跳过 $skipped 项",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
  }

  LaunchedEffect(server?.id, tab) { load(force = false) }

  // 下拉刷新：绕过本页缓存与 ViewModel 缓存强制重拉当前 tab
  val isRefreshing = remember { mutableStateOf(false) }
  val gridState = rememberLazyGridState()

  // 换排序（换项 / 反转 / 随机换一批）就回顶：视口停在原处会看到完全另一批内容，
  // 让人以为排序没生效。与媒体库页行为一致。
  LaunchedEffect(sort, sortAsc, randomSeed) {
    gridState.scrollToItem(0)
  }

  // ── 触底自动续拉 ──
  // 预取距离跟随「预测性懒加载」开关；演员等 tab 不分页，只在 MOVIE 生效。
  val reachBottomServer = server
  val gridSource = gridState.pagingSource()
  val prefetchDistance = rememberPredictivePreloadDistance(browserPreferences)
  moviePage.AutoLoadMore(
    source = gridSource,
    enabled = server != null && tab == FavoriteTab.MOVIE,
    distance = prefetchDistance,
    loader = { s, l -> moviePageLoader(server!!)(s, l) },
  )

  // ── 搜索时自动补拉后面的页 ──
  // 本地过滤只覆盖已加载的页，不补拉则「第 5 页才有的片名」永远搜不出；
  // 每敲一个字最多补 SEARCH_AUTOFILL_MAX_PAGES 页，避免收藏几千部时跑满流量。
  LaunchedEffect(searchQuery, tab, reachBottomServer?.id) {
    val target = reachBottomServer ?: return@LaunchedEffect
    if (tab != FavoriteTab.MOVIE || searchQuery.isBlank()) return@LaunchedEffect
    var guard = 0
    while (moviePage.hasMore && guard < SEARCH_AUTOFILL_MAX_PAGES) {
      val before = moviePage.items.size
      loadMoreMovies(target)
      // 一页都没新增 = 服务端开始重复返回同一批，再拉也是白拉
      if (moviePage.items.size == before) break
      guard++
    }
  }

  // Scaffold + TopAppBar：自动为状态栏留出安全区域
  Scaffold(
    // ⚠️ 内容区全屏铺、宿主底部导航栏浮在其上：Snackbar 默认贴底会被导航栏盖住，
    // 所以先让开导航栏高度，再叠上系统手势条高度。
    snackbarHost = {
      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
          .padding(bottom = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current)
          .navigationBarsPadding(),
      )
    },
    topBar = {
      if (selectionMode) {
        FavoritesSelectionTopBar(
          count = selectedIds.size,
          total = visibleItems.size,
          onSelectAll = {
            selectedIds.clear()
            visibleItems.mapNotNull { it.Id }.forEach { selectedIds.add(it) }
          },
          onClearSelection = { selectedIds.clear() },
          onClose = {
            selectionMode = false
            selectedIds.clear()
          },
        )
      } else {
        EmbyFavoritesTopBar(
          tab = tab,
          onTabChange = {
            if (it != tab) {
              tab = it
              // 写回偏好：返回 / 重启都停在上次选的类型
              browserPreferences.embyFavoritesTab.set(it.name)
            }
          },
          onRefresh = { scope.launch { load(force = true) } },
        )
      }
    },
    bottomBar = {
      if (selectionMode) {
        FavoritesBatchBar(
          // 演员 / 类型 / 工作室 tab 只有「取消收藏」有意义
          nonMediaTab = tab.isAggregate,
          count = selectedIds.size,
          // 四个动作都只记意图并弹确认框，真正发请求在 executeBatch
          onMarkPlayed = { pendingBatch = BatchAction.MARK_PLAYED },
          onMarkUnplayed = { pendingBatch = BatchAction.MARK_UNPLAYED },
          onUnfavorite = { pendingBatch = BatchAction.UNFAVORITE },
          onDownload = { pendingBatch = BatchAction.DOWNLOAD },
        )
      }
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      // 搜索 + 排序：只作用于当前 tab 的已加载列表，本地过滤不发请求
      FavoriteSearchSortRow(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        sort = sort,
        asc = sortAsc,
        onSortChange = { newSort, newAsc ->
          sort = newSort
          sortAsc = newAsc
          browserPreferences.embyFavoritesSort.set(newSort.name)
          browserPreferences.embyFavoritesSortAsc.set(newAsc)
          // 切到随机时给新种子，否则沿用旧种子洗出来的还是同一批
          if (newSort == FavoriteSort.RANDOM) randomSeed = Random.nextInt()
        },
        onReshuffle = { randomSeed = Random.nextInt() },
      )
      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        onRefresh = { load(force = true) },
        gridState = gridState,
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
      when {
        // 加载未结束时不下「没有服务器」的判定，避免冷启动闪一下空状态
        server == null && !isLoading -> EmbyEmptyState(
          message = "还没有添加 Emby 服务器",
          buttonText = "添加服务器",
          onAction = { backStack.add(EmbyServerManageScreen) },
          modifier = Modifier.align(Alignment.Center),
        )

        isLoading && items.isEmpty() ->
          // 骨架屏替代转圈：先摆出网格版式，列表落下时不会整屏跳变
          EmbySkeletonGrid(columns = gridColumns.coerceIn(2, 6), ratio = 3f / 4f)

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load(force = true) } },
          isError = true,
          modifier = Modifier.align(Alignment.Center),
        )

        items.isEmpty() -> EmbyEmptyState(
          message = when (tab) {
            FavoriteTab.MOVIE -> "还没有收藏任何媒体"
            FavoriteTab.ACTOR -> "还没有收藏任何演员"
            FavoriteTab.GENRE -> "还没有收藏任何类型"
            FavoriteTab.STUDIO -> "还没有收藏任何工作室"
          },
          buttonText = "刷新",
          onAction = { scope.launch { load(force = true) } },
          modifier = Modifier.align(Alignment.Center),
        )

        // 有收藏但被搜索滤空不在这里判，见网格后的空态叠加：
        // 让网格常驻，否则每敲一个字都可能卸载重建整个网格、封面集体重载。

        tab == FavoriteTab.MOVIE -> LazyVerticalGrid(
          // 与媒体库页同口径：跟随「每行个数」设置（2~6）
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { item ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = viewModel.displayTitle(item),
              subtitle = item.ProductionYear?.toString(),
              imageUrl = viewModel.imageUrl(currentServer, item, "Primary", 480),
              fallbackImageUrl = viewModel.imageUrl(currentServer, item, "Backdrop", 480),
              progress = itemProgressOf(item),
              isFavorite = true,
              selected = item.Id != null && item.Id in selectedIds,
              onClick = {
                if (selectionMode) {
                  toggleSelection(item.Id)
                } else {
                  val id = item.Id
                  if (id != null) backStack.add(EmbyDetailScreen(id, item.Name ?: ""))
                }
              },
              // 长按 = 进入多选并选上这一条
              onLongClick = { toggleSelection(item.Id) },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              // 条目位移动画：切排序 / 取消收藏时平滑重排
              modifier = Modifier.animateItem(),
            )
          }
          // 底部状态行：续拉中 / 出错可重试 / 已到底
          // span = maxLineSpan 让它横跨整行（否则只占一格宽度）
          item(span = { GridItemSpan(maxLineSpan) }) {
            EmbyPagingFooter(
              appending = moviePage.appending,
              hasMore = moviePage.hasMore,
              error = moviePage.error,
              loadedCount = moviePage.items.size,
              endText = moviePage.total?.let { "已显示全部 ${moviePage.items.size} / $it 项" },
              onRetry = { scope.launch { server?.let { loadMoreMovies(it) } } },
            )
          }
        }

        tab == FavoriteTab.ACTOR -> LazyVerticalGrid(
          // 与媒体库「演员」分类同一套版式，同样跟随「每行个数」
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { person ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = person.Name.orEmpty(),
              subtitle = null,
              // 人物只有 Primary（头像），人形图标兜底
              imageUrl = viewModel.imageUrl(currentServer, person, "Primary", 480),
              progress = null,
              isFavorite = true,
              placeholder = Icons.Default.Person,
              selected = person.Id != null && person.Id in selectedIds,
              onClick = {
                if (selectionMode) {
                  toggleSelection(person.Id)
                  return@EmbyMediaCard
                }
                val pid = person.Id ?: return@EmbyMediaCard
                // 进该演员的作品清单页（与详情页点演职员头像同一条路由）
                backStack.add(
                  EmbyPersonScreen(
                    personId = pid,
                    personName = person.Name.orEmpty(),
                    personImageTag = person.ImageTags["Primary"],
                  ),
                )
              },
              onLongClick = { toggleSelection(person.Id) },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              modifier = Modifier.animateItem(),
            )
          }
        }

        // 类型 / 工作室：点卡片进「该类型 / 工作室下的作品」清单页（与详情页 chip 同一路由）
        else -> LazyVerticalGrid(
          columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = favoritesBottomPadding),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { facet ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = facet.Name.orEmpty(),
              subtitle = null,
              // 类型 / 工作室通常没有 Primary 图，交给卡片默认占位
              imageUrl = viewModel.imageUrl(currentServer, facet, "Primary", 480),
              progress = null,
              isFavorite = true,
              selected = facet.Id != null && facet.Id in selectedIds,
              onClick = {
                if (selectionMode) {
                  toggleSelection(facet.Id)
                  return@EmbyMediaCard
                }
                val name = facet.Name ?: return@EmbyMediaCard
                // 带上条目 Id：结果页顶栏的收藏红心要用它取收藏状态，免得按名字反查。
                // 清单本身的筛选口径不变 —— 类型仍按名字筛（见 EmbyTagItemsScreen）。
                backStack.add(
                  EmbyTagItemsScreen(
                    keyword = name,
                    kind = if (tab == FavoriteTab.STUDIO) "studio" else "genre",
                    filterId = facet.Id.orEmpty(),
                  ),
                )
              },
              onLongClick = { toggleSelection(facet.Id) },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
              modifier = Modifier.animateItem(),
            )
          }
        }
      }

        // ── 搜索滤空的提示：叠在网格上层，不参与上面的 when 二选一 ──
        // ⚠️ 若做成 when 分支与网格互斥，中间态滤出 0 条时整个网格会被卸载重建，
        // 卡片全部重新组合、封面重新加载，表现为搜索时封面闪一下。
        // ⚠️ 只在真的敲了搜索词时提示：空搜索词下滤空只可能是代码 bug。
        if (visibleItems.isEmpty() && items.isNotEmpty() && error == null && server != null && searchQuery.isNotBlank()) {
          EmbyEmptyState(
            message = "没有匹配「${searchQuery.trim()}」的收藏",
            buttonText = "清空搜索",
            onAction = { searchQuery = "" },
            modifier = Modifier.align(Alignment.Center),
          )
        }
      }
    }
    }
  }

  // ── 批量操作的二次确认弹窗 ──
  // 底部按钮只走到这里：确认过才调 executeBatch；做错的那次可在结果提示上撤销。
  pendingBatch?.let { action ->
    val (title, body, confirmLabel) =
      when (action) {
        BatchAction.MARK_PLAYED ->
          Triple("标为已看", "将把选中的 ${selectedIds.size} 项标记为已看。", "标为已看")

        BatchAction.MARK_UNPLAYED ->
          Triple("标为未看", "将把选中的 ${selectedIds.size} 项标记为未看，并清除它们的观看进度。", "标为未看")

        BatchAction.UNFAVORITE ->
          Triple(
            "取消收藏",
            "将从收藏中移除选中的 ${selectedIds.size} 项。\n\n完成后的提示里可以点「撤销」再收藏回来。",
            "取消收藏",
          )

        BatchAction.DOWNLOAD ->
          Triple("下载", "将把选中的 ${selectedIds.size} 项加入下载队列。", "加入队列")
      }
    AlertDialog(
      onDismissRequest = { pendingBatch = null },
      title = { Text(title) },
      text = { Text(body) },
      confirmButton = {
        TextButton(
          onClick = {
            val act = action
            pendingBatch = null
            executeBatch(act)
          },
        ) { Text(confirmLabel) }
      },
      dismissButton = { TextButton(onClick = { pendingBatch = null }) { Text("取消") } },
    )
  }
}

/**
 * 收藏页顶部标题栏：标题右侧为 tab 下拉切换（用 Surface + DropdownMenu，
 * ExposedDropdownMenuBox 在 TopAppBar 标题区点击判定容易出问题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyFavoritesTopBar(
  onRefresh: () -> Unit,
  /** null = 旧签名调用方（不显示切换器），仅渲染标题与动作键 */
  tab: FavoriteTab? = null,
  onTabChange: (FavoriteTab) -> Unit = {},
) {
  val backStack = LocalBackStack.current
  TopAppBar(
    title = {
      if (tab == null) {
        Text("收藏")
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("收藏")
          Spacer(modifier = Modifier.width(10.dp))
          FavoriteTabSwitch(current = tab, onSelected = onTabChange)
        }
      }
    },
    actions = {
      IconButton(onClick = onRefresh) {
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
}

/** 收藏类型切换器：当前值胶囊 + 下拉菜单。 */
@Composable
private fun FavoriteTabSwitch(
  current: FavoriteTab,
  onSelected: (FavoriteTab) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    Surface(
      onClick = { expanded = true },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      ) {
        Text(
          text = current.label,
          style = MaterialTheme.typography.labelLarge,
        )
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "切换收藏类型",
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      FavoriteTab.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          leadingIcon = {
            // 选中项打勾；未选中放同宽占位，保持文字左对齐
            if (option == current) {
              Icon(Icons.Default.Check, contentDescription = null)
            } else {
              Spacer(modifier = Modifier.width(24.dp))
            }
          },
          onClick = {
            expanded = false
            onSelected(option)
          },
        )
      }
    }
  }
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

/**
 * 收藏页的排序方式（本地排序，不发请求）。
 *
 * 名称用 [EmbyItem.SortName]（已剥离 "The/A" 前缀）而非 Name，与服务器排序口径一致，
 * 切到本地排序不会突然乱序。
 */
private enum class FavoriteSort(
  val label: String,
  /** 升序时的方向提示 */
  val ascHint: String,
  /** 降序时的方向提示 */
  val descHint: String,
  /** 没手动切过方向时的自然方向（与媒体库页同一套规则） */
  val naturalAsc: Boolean,
) {
  NAME("名称", "A → Z", "Z → A", naturalAsc = true),
  YEAR("年份", "旧 → 新", "新 → 旧", naturalAsc = false),
  RATING("评分", "低 → 高", "高 → 低", naturalAsc = false),
  ADDED("最新添加", "旧 → 新", "新 → 旧", naturalAsc = false),
  // 随机：不分升降序，再点一次换一批
  RANDOM("随机", "再点一次换一批", "再点一次换一批", naturalAsc = true),
  ;

  /** 是否带方向（随机没有） */
  val hasDirection: Boolean get() = this != RANDOM

  fun apply(list: List<EmbyItem>, asc: Boolean, seed: Int = 0): List<EmbyItem> = when (this) {
    // ⚠️ 随机必须用固定种子：直接 shuffled() 会在每次重组时洗出不同顺序
    RANDOM -> list.shuffled(Random(seed))
    NAME -> sortByKey(list, asc) { (it.SortName ?: it.Name)?.lowercase() }
    YEAR -> sortByKey(list, asc) { it.ProductionYear }
    RATING -> sortByKey(list, asc) { it.CommunityRating }
    // DateCreated 是 ISO-8601 串，字典序即时间序（同格式下成立）
    ADDED -> sortByKey(list, asc) { it.DateCreated }
  }
}

/**
 * 按可空键排序，键值缺失的条目一律沉底（无论升 / 降序）。
 *
 * 不能简单对整体 `reversed()`：那会把无值组一起翻到最前面。故先 partition 出有值与无值
 * 两段，只对前者排序，再把无值段接在末尾。
 *
 * @param key 取排序键；null 表示该条目没有这个值
 */
private inline fun <T : Comparable<T>> sortByKey(
  list: List<EmbyItem>,
  asc: Boolean,
  crossinline key: (EmbyItem) -> T?,
): List<EmbyItem> {
  val (withKey, withoutKey) = list.partition { key(it) != null }
  val comparator = compareBy(key)
  // sortedWith 是稳定排序：键值相同的条目保持原相对顺序
  val sorted = if (asc) withKey.sortedWith(comparator) else withKey.sortedWith(comparator.reversed())
  return sorted + withoutKey
}

/**
 * 顶部的「搜索框 + 排序」一行。搜索是即时的本地过滤（只作用在已加载的页上），
 * 不打服务器所以无需防抖；已加载页不够时由外层副作用自动往后补拉。
 */
@Composable
private fun FavoriteSearchSortRow(
  query: String,
  onQueryChange: (String) -> Unit,
  sort: FavoriteSort,
  asc: Boolean,
  /** 选了某个排序项 + 方向（重复点当前项被本函数判定为「反转方向」，不会走到这里） */
  onSortChange: (FavoriteSort, Boolean) -> Unit,
  /** 已经是随机排序时再点一次：换一批 */
  onReshuffle: () -> Unit,
) {
  var sortMenu by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      modifier = Modifier.weight(1f),
      singleLine = true,
      shape = MaterialTheme.shapes.large,
      placeholder = { Text("搜索收藏") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      trailingIcon = {
        if (query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
          }
        }
      },
    )
    Spacer(modifier = Modifier.width(8.dp))
    Box {
      // 触发区直接标出当前排序，不用点开就知道
      Surface(
        modifier = Modifier.clickable {
          // ⚠️ 展开前先收掉输入框焦点：否则菜单关闭会把焦点还给它，每切一次排序弹一次软键盘
          focusManager.clearFocus()
          sortMenu = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Sort,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(text = sort.label, style = MaterialTheme.typography.labelLarge)
          // 带上方向图标：不看菜单就知道升还是降（随机没有方向，给骰子图标）
          Icon(
            imageVector = if (!sort.hasDirection) {
              Icons.Default.Shuffle
            } else if (asc) {
              Icons.Default.ArrowUpward
            } else {
              Icons.Default.ArrowDownward
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
        FavoriteSort.entries.forEach { option ->
          val selected = option == sort
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  // 选中项直接标出当前方向（↑ 升 / ↓ 降），不单列「升序 / 降序」两个选项
                  text = buildString {
                    append(option.label)
                    if (selected && option.hasDirection) {
                      append(if (asc) " ↑" else " ↓")
                    }
                  },
                  color = if (selected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                )
                // 方向副标题：说明这项在该方向下具体怎么排
                Text(
                  text = if (!option.hasDirection) {
                    option.ascHint
                  } else if (selected) {
                    // 已选中：副标题跟随当前方向
                    if (asc) option.ascHint else option.descHint
                  } else {
                    // 未选中：预告点下去会变成哪个方向（= 该项的自然方向）
                    if (option.naturalAsc) option.ascHint else option.descHint
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            },
            // 勾放尾部（M3 惯例），再用主色文字强化，不靠位置飘忽的首位图标表达选中
            trailingIcon = {
              if (selected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "当前排序",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            },
            onClick = {
              sortMenu = false
              when {
                // 再点一次已选中的项 = 只反转方向（随机则是换一批），不换排序项
                selected && option.hasDirection -> onSortChange(option, !asc)
                selected -> onReshuffle()
                // 换排序项：方向重置为该项的自然方向
                else -> onSortChange(option, option.naturalAsc)
              }
            },
          )
        }
      }
    }
  }
}

/** 多选模式顶栏：左侧关闭、中间「已选 N / 总数」、右侧全选 / 取消全选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSelectionTopBar(
  count: Int,
  total: Int,
  onSelectAll: () -> Unit,
  onClearSelection: () -> Unit,
  onClose: () -> Unit,
) {
  TopAppBar(
    title = { Text("已选 $count / $total") },
    navigationIcon = {
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "退出多选")
      }
    },
    actions = {
      if (count < total) {
        TextButton(onClick = onSelectAll) { Text("全选") }
      } else {
        TextButton(onClick = onClearSelection) { Text("取消全选") }
      }
    },
  )
}

/**
 * 多选模式底部批量操作条：每个动作为「图标 + 文字」竖排等分，点击区比纯文字按钮大得多。
 *
 * 非媒体 tab（演员 / 类型 / 工作室）只保留「取消收藏」，因为播放进度、下载是媒体条目
 * 的概念；只剩一个动作时让它占满整行居中。
 */
@Composable
private fun FavoritesBatchBar(
  nonMediaTab: Boolean,
  count: Int,
  onMarkPlayed: () -> Unit,
  onMarkUnplayed: () -> Unit,
  onUnfavorite: () -> Unit,
  onDownload: () -> Unit,
) {
  val enabled = count > 0
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 3.dp,
    // 应用底部导航栏已收起，但系统手势条还在：留出它的高度，否则按钮被压住且易误触返回
    modifier = Modifier.navigationBarsPadding(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!nonMediaTab) {
        BatchActionItem(
          modifier = Modifier.weight(1f),
          icon = Icons.Filled.CheckCircle,
          label = "标为已看",
          enabled = enabled,
          onClick = onMarkPlayed,
        )
        BatchActionItem(
          modifier = Modifier.weight(1f),
          icon = Icons.Filled.RadioButtonUnchecked,
          label = "标为未看",
          enabled = enabled,
          onClick = onMarkUnplayed,
        )
      }
      BatchActionItem(
        modifier = Modifier.weight(1f),
        icon = Icons.Filled.FavoriteBorder,
        label = "取消收藏",
        enabled = enabled,
        // 破坏性动作用 error 色区分
        danger = true,
        onClick = onUnfavorite,
      )
      if (!nonMediaTab) {
        BatchActionItem(
          modifier = Modifier.weight(1f),
          icon = Icons.Filled.Download,
          label = "下载",
          enabled = enabled,
          onClick = onDownload,
        )
      }
    }
  }
}

/**
 * 批量条上的单个动作：图标 + 文字竖排，宽度由调用方的 weight 决定。
 *
 * [danger] = 破坏性动作（取消收藏），用 error 色区分；禁用态统一降到 38% 不透明度。
 */
@Composable
private fun BatchActionItem(
  icon: ImageVector,
  label: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  danger: Boolean = false,
  onClick: () -> Unit,
) {
  val base = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  val contentColor = if (enabled) base else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(22.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = contentColor,
      maxLines = 1,
    )
  }
}

/** 批量操作的结果文案：「动作：N 项」；有失败时补上「成功 N · 失败 M」。 */
private fun batchResultText(action: String, result: EmbyBatchResult): String =
  if (result.failed == 0) {
    "$action：${result.ok} 项"
  } else {
    "$action：成功 ${result.ok} · 失败 ${result.failed}"
  }

/** 底部批量条上的动作。点一下先弹确认框，确认过才执行（见 executeBatch）。 */
private enum class BatchAction { MARK_PLAYED, MARK_UNPLAYED, UNFAVORITE, DOWNLOAD }
