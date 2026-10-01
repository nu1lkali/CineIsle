package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
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

  LaunchedEffect(server?.id, tab) { load(force = false) }

  // 下拉刷新：绕过本页缓存与 ViewModel 缓存强制重拉当前 tab
  val isRefreshing = remember { mutableStateOf(false) }
  val gridState = rememberLazyGridState()

  // Scaffold + TopAppBar：自动为状态栏留出安全区域，避免网格压在状态栏下
  Scaffold(
    topBar = {
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
    },
  ) { innerPadding ->
    PullRefreshGridBox(
      isRefreshing = isRefreshing,
      onRefresh = { load(force = true) },
      gridState = gridState,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
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

        isLoading && items.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

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

        tab == FavoriteTab.MOVIE -> LazyVerticalGrid(
          // 与媒体库页统一：固定一行三个（含「文件夹」分类下的宫格封面保持一致观感）
          columns = GridCells.Fixed(3),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          items(movieItems, key = { it.Id ?: it.Name ?: "" }) { item ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = viewModel.displayTitle(item),
              subtitle = item.ProductionYear?.toString(),
              imageUrl = viewModel.imageUrl(currentServer, item, "Primary", 480),
              fallbackImageUrl = viewModel.imageUrl(currentServer, item, "Backdrop", 480),
              progress = itemProgressOf(item),
              isFavorite = true,
              onClick = {
                val id = item.Id
                if (id != null) backStack.add(EmbyDetailScreen(id, item.Name ?: ""))
              },
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
          items(actorItems, key = { it.Id ?: it.Name ?: "" }) { person ->
            val currentServer = server ?: return@items
            EmbyMediaCard(
              title = person.Name.orEmpty(),
              subtitle = null,
              // 人物只有 Primary（头像）一张图，人形图标兜底
              imageUrl = viewModel.imageUrl(currentServer, person, "Primary", 480),
              progress = null,
              isFavorite = true,
              placeholder = Icons.Default.Person,
              onClick = {
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
