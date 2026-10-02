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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyBatchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 收藏页的内容类型：影片 = 现有的收藏媒体；演员 = 收藏的演员（Person） */
private enum class FavoriteTab(val label: String) {
  MOVIE("影片"),
  ACTOR("演员"),
}

/**
 * Emby 收藏页：展示服务器上标记为收藏的媒体与演员。
 *
 * 标题右侧有「影片 / 演员」下拉切换（默认影片）：
 *  · 影片 → 单击进媒体详情页（原有逻辑）；
 *  · 演员 → 单击进该演员的作品清单页（EmbyPersonScreen）。
 *
 * 两个 tab 的列表各自缓存一份，来回切换不重复发请求；
 * 顶栏刷新按钮只重拉当前 tab（并作废其缓存）。
 *
 * 交互与首页一致：**单击播放 / 看详情，长按查看详情**。
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

  // 当前选中的类型：初始值从偏好里恢复（默认「影片」）。
  // **必须落盘**：从收藏页点进演员作品页再返回时整屏会重建，
  // 只放 remember 的话 tab 就被打回「影片」，用户还得再点一次。
  val browserPreferences = koinInject<BrowserPreferences>()
  var tab by remember {
    mutableStateOf(
      FavoriteTab.entries.firstOrNull { it.name == browserPreferences.embyFavoritesTab.get() }
        ?: FavoriteTab.MOVIE,
    )
  }

  // ── 两个 tab 各自的列表 + 缓存 ──
  // 缓存命中时切 tab 不重新发请求；手动刷新时用 forceRefresh 绕过缓存。
  // 缓存按 server.id 区分：切服务器后旧数据不会串台。
  val movieItems = remember { mutableStateListOf<EmbyItem>() }
  val actorItems = remember { mutableStateListOf<EmbyItem>() }
  val movieCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  val actorCache = remember { mutableStateOf<Pair<Long, List<EmbyItem>>?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  /** 展示列表 = 当前 tab 对应的那一份 */
  val items = if (tab == FavoriteTab.MOVIE) movieItems else actorItems

  // ── 搜索 / 排序 ──
  // 收藏页一次性拉全量（200 条）到内存，所以过滤与排序都在本地做：即时、不发请求。
  var searchQuery by remember { mutableStateOf("") }
  var sort by remember {
    mutableStateOf(
      FavoriteSort.entries.firstOrNull { it.name == browserPreferences.embyFavoritesSort.get() }
        ?: FavoriteSort.NAME,
    )
  }
  val visibleItems = items
    .filter {
      searchQuery.isBlank() || it.Name?.contains(searchQuery.trim(), ignoreCase = true) == true
    }
    .let { sort.apply(it) }

  // ── 多选模式（批量操作）──
  // 长按任意卡片进入；选中集合存条目 Id；退出时清空。
  var selectionMode by remember { mutableStateOf(false) }
  val selectedIds = remember { mutableStateListOf<String>() }
  // 批量动作完成后的结果提示 + **一次性撤销**入口：Snackbar 上的「撤销」把刚做的动作
  // 用反向动作跑一遍（取消收藏 → 收藏回去、标为未看 → 标为已看…）。只保留最近一次。
  val snackbarHostState = remember { SnackbarHostState() }
  // 错误点（防误触）：点底部按钮先只记下意图，弹窗确认之后才真正执行。
  var pendingBatch by remember { mutableStateOf<BatchAction?>(null) }
  // 批量下载走的是与详情页同一个下载管理器（持久化在 manager 里，任务队列共享）
  val downloadViewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
  )

  // 进多选就把外层底部导航栏收起来 —— 本页的批量操作条画在屏幕最底部，
  // 而宿主 MainScreen 的内容是**全屏铺**的（导航栏浮在内容之上、没让出高度），
  // 不收起来这条就会被导航栏整条盖住，用户根本看不到按钮（用户报障）。
  // 与「本地」文件浏览页多选时的做法一致（见 FileSystemBrowserScreen）。
  DisposableEffect(selectionMode) {
    app.marlboroadvance.mpvex.ui.browser.MainScreen.updateBottomBarVisibility(!selectionMode)
    onDispose {
      // key 变化时 onDispose 先跑、之后才执行上面那行，两次写入语义一致；
      // 离开页面（多选还没退）时则由这里负责恢复。
      app.marlboroadvance.mpvex.ui.browser.MainScreen.updateBottomBarVisibility(true)
    }
  }

  // 多选态下系统返回（含边缘滑动）**先退多选**，别直接退页面。
  // 顶部那个 ✕ 走的是同一件事；导航栏这时已被收起，不给返回键兜住的话
  // 用户按一下返回就直接退出 App（比看不到按钮更糟）。enabled = selectionMode
  // ⇒ 非多选态完全不拦截，交给宿主正常出栈。
  BackHandler(enabled = selectionMode) {
    selectionMode = false
    selectedIds.clear()
  }

  /**
   * 切换某条的选中态。
   *
   * 首次调用（还没进多选模式）会顺带进入多选并把这一条选上 —— 这样「长按 → 直接多选」
   * 一步到位，不用先点一个「多选」按钮再点卡片。
   */
  fun toggleSelection(id: String?) {
    if (id == null) return
    if (!selectionMode) {
      selectionMode = true
      selectedIds.clear()
    }
    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
  }

  suspend fun loadMovies(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) {
    val cached = movieCache.value
    if (!force && cached != null && cached.first == current.id) {
      movieItems.clear()
      movieItems.addAll(cached.second)
      return
    }
    runCatching { viewModel.loadFavorites(current, 0, 200) }
      .onSuccess { page ->
        // 网格 key = Id ?: Name：服务端把同一条目返回两次时（SmartStrm 实测有此问题），
        // 重复 key 会直接撞崩 LazyVerticalGrid，这里按同一口径去重
        val deduped = page.items.distinctBy { it.Id ?: it.Name ?: "" }
        movieCache.value = current.id to deduped
        movieItems.clear()
        movieItems.addAll(deduped)
      }
      .onFailure { error = it.message ?: "加载收藏失败" }
  }

  suspend fun loadActors(current: app.marlboroadvance.mpvex.domain.emby.EmbyServer, force: Boolean) {
    val cached = actorCache.value
    if (!force && cached != null && cached.first == current.id) {
      actorItems.clear()
      actorItems.addAll(cached.second)
      return
    }
    runCatching { viewModel.loadFavoritePersons(current, forceRefresh = force) }
      .onSuccess { list ->
        // 同上：按 key 口径去重防网格崩溃
        val deduped = list.distinctBy { it.Id ?: it.Name ?: "" }
        actorCache.value = current.id to deduped
        actorItems.clear()
        actorItems.addAll(deduped)
      }
      .onFailure { error = it.message ?: "加载收藏演员失败" }
  }

  suspend fun load(force: Boolean) {
    // 冷启动时当前服务器可能还没恢复，这里等一下，避免误判成「没有服务器」而空白
    val current = viewModel.currentServerOrAwait() ?: run {
      isLoading = false
      return
    }
    isLoading = true
    error = null
    if (tab == FavoriteTab.MOVIE) loadMovies(current, force) else loadActors(current, force)
    isLoading = false
  }

  /**
   * 一次批量操作的收尾：退出多选态 → 弹结果 Snackbar。
   *
   * [undo] 非空时 Snackbar 上会多一个「撤销」按钮，点了就把**反向动作**跑一遍
   * （取消收藏 → 收藏回去、标为未看 → 标为已看…）。只保证「最近一次」可撤销，
   * 再操作一次就被新的覆盖 —— 一次性回退，不做撤销栈。
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
   * 执行一个**已确认**的批量动作。底部按钮本身只负责弹确认框（防误触），
   * 真正发请求都在这里；跑完把反向动作交给 [finishBatch] 当撤销凭据。
   *
   * ⚠️ ids / items 都要在进协程**之前**取快照：finishBatch 一进来就清空 selectedIds，
   * 闭包里再去读只剩空集合。定义在 [load] 之后也是必须的 —— 局部函数不能前向引用。
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
            // 取消收藏后这批就不在收藏页了，重拉一次让列表同步
            load(force = true)
            finishBatch("取消收藏", r) {
              // 撤销 = 收藏回去（影片 / 演员同一条路，setFavoriteBatch 内部按类型分流）
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
        // 下载只是「加入队列」：误触的代价小（下载页里随时能暂停 / 删除），
        // 所以只给确认、不给撤销，免得「撤销」把用户之前在队列里的任务也误删。
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

  // Scaffold + TopAppBar：自动为状态栏留出安全区域，避免网格压在状态栏下
  Scaffold(
    // 批量操作的结果提示挂在本页自己的 Snackbar 上 —— 一次性「撤销」入口也在这里。
    // ⚠️ 内容区是全屏铺的、宿主底部导航栏浮在其上：Snackbar 默认贴底会被导航栏整条盖住，
    // 所以先让开导航栏的高度，再叠上**系统**手势条的高度，把它抬到导航栏之上。
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
          // 演员 tab 只有「取消收藏」有意义（有没有看过 / 时长这些是媒体条目的概念）
          isActorTab = tab == FavoriteTab.ACTOR,
          count = selectedIds.size,
          // 四个动作都只「记下意图」并弹确认框 —— 真正发请求在 executeBatch（防误触）
          onMarkPlayed = { pendingBatch = BatchAction.MARK_PLAYED },
          onMarkUnplayed = { pendingBatch = BatchAction.MARK_UNPLAYED },
          onUnfavorite = { pendingBatch = BatchAction.UNFAVORITE },
          onDownload = { pendingBatch = BatchAction.DOWNLOAD },
        )
      }
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      // 搜索 + 排序：只作用于当前 tab 的列表（见上方 visibleItems），本地过滤不发请求
      FavoriteSearchSortRow(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        sort = sort,
        onSortChange = {
          sort = it
          browserPreferences.embyFavoritesSort.set(it.name)
        },
      )
      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        onRefresh = { load(force = true) },
        gridState = gridState,
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
      when {
        // 加载尚未结束时不判定「没有服务器」，避免冷启动瞬间闪一下空状态
        server == null && !isLoading -> EmbyEmptyState(
          message = "还没有添加 Emby 服务器",
          buttonText = "添加服务器",
          onAction = { backStack.add(EmbyServerManageScreen) },
          modifier = Modifier.align(Alignment.Center),
        )

        isLoading && items.isEmpty() ->
          // 骨架屏替代转圈：先把网格版式摆出来，收藏列表落下时不会整屏跳变。
          // 演员 tab 也复用这套网格骨架 —— 栏数一致，观感上是同一套节奏。
          EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)

        error != null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load(force = true) } },
          modifier = Modifier.align(Alignment.Center),
        )

        items.isEmpty() -> EmbyEmptyState(
          message = if (tab == FavoriteTab.MOVIE) "还没有收藏任何媒体" else "还没有收藏任何演员",
          buttonText = "刷新",
          onAction = { scope.launch { load(force = true) } },
          modifier = Modifier.align(Alignment.Center),
        )

        // 有收藏但被搜索框滤空：给一句提示，别让用户以为收藏丢了
        visibleItems.isEmpty() -> EmbyEmptyState(
          message = "没有匹配「${searchQuery.trim()}」的收藏",
          buttonText = "清空搜索",
          onAction = { searchQuery = "" },
          modifier = Modifier.align(Alignment.Center),
        )

        tab == FavoriteTab.MOVIE -> LazyVerticalGrid(
          // 与媒体库页统一：固定一行三个（含「文件夹」分类下的宫格封面保持一致观感）
          columns = GridCells.Fixed(3),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
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
              // 长按 = 进入多选并选上这一条（已在多选模式则等价于切换）
              onLongClick = { toggleSelection(item.Id) },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
            )
          }
        }

        else -> LazyVerticalGrid(
          // 演员网格与媒体库「演员」分类同一套版式：一行三个海报卡 + 人形占位
          columns = GridCells.Fixed(3),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { person ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = person.Name.orEmpty(),
              subtitle = null,
              // 人物只有 Primary（头像）一张图，人形图标兜底
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
              // 长按 = 进入多选并选上这一条
              onLongClick = { toggleSelection(person.Id) },
              style = EmbyCardStyle.POSTER,
              fillWidth = true,
            )
          }
        }
      }
      }
    }
    }
  }

  // ── 批量操作的二次确认弹窗 ──
  // 点底部按钮只走到这里：确认过才调 executeBatch；做错的那一次可在结果提示上「撤销」。
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
 * 收藏页顶部标题栏。
 *
 * 标题右侧新增「影片 / 演员」下拉切换：点击弹出两个选项，
 * 选中项打勾。样式用 M3 的 Surface + DropdownMenu，与顶栏其它控件同一套观感。
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

/**
 * 「影片 / 演员」类型切换器：当前值胶囊 + 下拉菜单。
 *
 * 不用 Spinner：Compose 下 M3 没有直接对应的控件，ExposedDropdownMenuBox 的
 * 展开态与 TopAppBar 的标题区叠加时点击判定容易出问题；一个小 Surface + DropdownMenu
 * 最稳，观感也与项目里其它下拉一致。
 */
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
            // 选中项打勾；未选中放一个同宽占位，两行文字左对齐
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
 * 选「名称」时用 [EmbyItem.SortName]（Emby 内部的排序名，已剥离 "The/A" 这类前缀）
 * 而不是 Name —— 和服务器按 SortName 排序的口径保持一致，切到本地排序不会突然乱序。
 */
private enum class FavoriteSort(
  val label: String,
  /** 排序方向提示：菜单里作为副标题显示，避免「勾了但不知道是升还是降」 */
  val hint: String,
) {
  NAME("名称", "A → Z"),
  YEAR("年份", "新 → 旧"),
  RATING("评分", "高 → 低"),
  ADDED("最新添加", "新 → 旧"),
  ;

  fun apply(list: List<EmbyItem>): List<EmbyItem> = when (this) {
    NAME -> list.sortedBy { (it.SortName ?: it.Name ?: "").lowercase() }
    YEAR -> list.sortedByDescending { it.ProductionYear ?: 0 }
    RATING -> list.sortedByDescending { it.CommunityRating ?: 0.0 }
    // DateCreated 是 ISO-8601 串，字典序即时间序（同格式下成立）
    ADDED -> list.sortedByDescending { it.DateCreated ?: "" }
  }
}

/**
 * 收藏页顶部的「搜索框 + 排序」一行。
 *
 * 搜索是即时的本地过滤（收藏页本来就一次拉全量），不做防抖也不需要防抖 ——
 * 不打服务器，敲一个字就重算一次毫无成本。
 */
@Composable
private fun FavoriteSearchSortRow(
  query: String,
  onQueryChange: (String) -> Unit,
  sort: FavoriteSort,
  onSortChange: (FavoriteSort) -> Unit,
) {
  var sortMenu by remember { mutableStateOf(false) }
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
      // 触发区直接标出「当前按什么排序」，不用点开就知道现在的排序方式
      Surface(
        modifier = Modifier.clickable { sortMenu = true },
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
                  text = option.label,
                  color = if (selected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                )
                // 方向副标题：让「名称 / 年份 / 评分」到底怎么排一目了然
                Text(
                  text = option.hint,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            },
            // 勾放**尾部**（M3 惯例）：明确表示「当前选中的就是这一项」，
            // 再用主色文字强化，不再靠一个位置飘忽的首位图标表达选中。
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
              onSortChange(option)
            },
          )
        }
      }
    }
  }
}

/**
 * 多选模式下的顶栏：左侧关闭、中间「已选 N / 总数」、右侧全选 / 取消全选。
 */
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
 * 多选模式下的底部批量操作条。
 *
 * 每个动作 = 「图标 + 文字」竖排、等宽平分，点按有水波纹 —— 比原来一排光秃秃的文字按钮
 * 更好认，点击区也更大（原来文字按钮的可点范围只有文字本身那么宽，很容易点空）。
 *
 * 图标会歧义的顾虑用**文字兜底**：图标只当视觉锚点，下面一行始终写着动作名。
 * 演员 tab 只保留「取消收藏」—— 播放进度、下载是媒体条目的概念，对 Person 无意义；
 * 只剩一个动作时让它占满整行居中，不至于孤零零挤在左边。
 */
@Composable
private fun FavoritesBatchBar(
  isActorTab: Boolean,
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
    // 应用自己的底部导航栏已被收起（见上面的 DisposableEffect），但**系统**手势条还在，
    // 留出它的高度：否则按钮会被系统导航条压住，手指从手势区起划还会误触发「返回」。
    modifier = Modifier.navigationBarsPadding(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!isActorTab) {
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
        // 破坏性动作：用 error 色区分，别和「标为已看」这类平行动作混作一块
        danger = true,
        onClick = onUnfavorite,
      )
      if (!isActorTab) {
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
 * 批量条上的单个动作：图标 + 文字竖排，宽度由调用方的 weight 决定（等分）。
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
