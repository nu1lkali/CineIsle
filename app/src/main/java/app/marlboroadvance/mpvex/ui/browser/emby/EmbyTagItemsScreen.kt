package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import org.koin.compose.koinInject
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 标签 / 类型结果页一页拉多少条（清单 1.1：原来固定 120 条） */
private const val TAG_PAGE_SIZE = 100

/**
 * 「按类型 / 标签 / 工作室找片」结果页。
 *
 * 详情页的类型 / 标签 / 工作室 chip 点一下进来：
 * 和点头像进「演员作品页」是同一套交互 —— 只是过滤维度不同
 * （类型 → Genres、标签 → Tags、工作室 → StudioIds）。
 *
 * 走 Emby 的 /Items 过滤（不传 ParentId，跨全部媒体库），只保留可播放类型，
 * 结果按名称升序，点卡片照常进详情页。
 *
 * @param keyword  显示用的名字（类型名 / 标签名 / 工作室名，就是 chip 上的文字）
 * @param kind     "genre" = 类型，"studio" = 工作室，其它值按标签处理
 * @param filterId 条目 Id，本页有**两个用途**：
 *   ① **清单筛选** —— 目前只有工作室用得上（Emby 的工作室过滤首选 `StudioIds`）；
 *      留空时退回用 [keyword] 按名字过滤（Emby 的 `Studios` 参数），两条路都是官方支持的。
 *      类型始终按名字筛（[keyword]），不看这个字段。
 *   ② **顶栏收藏红心** —— 类型 / 工作室都用它去取「本条目当前收没收藏」；
 *      留空时类型会按名字反查一次（详情页的类型 chip 只给名字），标签无 Id 故红心隐藏。
 */
@Serializable
data class EmbyTagItemsScreen(
  val keyword: String,
  val kind: String = "genre",
  val filterId: String = "",
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
    // 随机播放数量上限读用户设置，与媒体库 / 演员页的随机按钮同一口径
    val browserPreferences = koinInject<BrowserPreferences>()

    // 顶栏收藏红心的状态来源：类型 / 工作室是带 UserData 的正式条目可被收藏，
    // 标签没有独立 Id 不支持收藏（loadFacetItem 返回 null，红心据此隐藏）。
    // 进页面查一次；收藏 / 取消收藏成功后本地翻转，不再回查服务器。
    var facetItem by remember { mutableStateOf<EmbyItem?>(null) }

    val kindLabel = when (kind) {
      "genre" -> "类型"
      "studio" -> "工作室"
      else -> "标签"
    }

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 分页状态（清单 1.1）：原来一次拉 120 条就结束了，热门类型（科幻 / 动作）远不止
    val page = remember { PagedList<EmbyItem>(TAG_PAGE_SIZE, keyOf = { it.Id ?: it.Name ?: "" }) }
    val gridState = rememberLazyGridState()
    val gridSource = gridState.pagingSource()

    /** 本页的加载器：三个维度只差一个 ViewModel 方法 */
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = pageLoader@{ start, limit ->
      val target = server ?: return@pageLoader PagedResult(emptyList(), null, limit)
      val result = when (kind) {
        "genre" ->
          viewModel.loadGenreItems(server = target, genre = keyword, startIndex = start, limit = limit)

        "studio" ->
          viewModel.loadStudioItems(
            server = target,
            studioId = filterId,
            studioName = keyword,
            startIndex = start,
            limit = limit,
          )

        else ->
          viewModel.loadTagItems(server = target, tag = keyword, startIndex = start, limit = limit)
      }
      PagedResult(
        // 网格 key = Id ?: Name：按同一口径去重，防止服务端重复条目把网格撞崩
        items = result.items.distinctBy { it.Id ?: it.Name ?: "" },
        totalCount = result.totalCount,
        pageSize = limit,
      )
    }

    suspend fun load() {
      if (server == null) return
      isLoading = true
      error = null
      page.reload(loader)
      isLoading = false
      // 加载成功但一条都没有：给一句人话，而不是干晾着空网格
      if (page.error != null) error = page.error
      else if (page.items.isEmpty()) error = "没有找到该${kindLabel}下的媒体"
    }

    /**
     * 随机播放这个类型 / 标签 / 工作室下的作品：交给服务端按 `SortBy=Random` 从**整个范围**抽
     * （不是只抽已加载的那一页），数量读用户设置，与媒体库 / 演员页的随机按钮同一口径。
     * 复用本页三个 facet 加载方法的同一个过滤口径（类型按名、工作室按 Id 或名、标签按名），
     * 只是排序换成随机。随机出的列表里可能混着看过的剧，所以每条都从头放（playFromStartAll = true）。
     */
    fun startRandomPlayback() {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500)
        val result = when (kind) {
          "genre" ->
            viewModel.loadGenreItems(server = current, genre = keyword, sortBy = "Random", limit = limit)

          "studio" ->
            viewModel.loadStudioItems(
              server = current,
              studioId = filterId,
              studioName = keyword,
              sortBy = "Random",
              limit = limit,
            )

          else ->
            viewModel.loadTagItems(server = current, tag = keyword, sortBy = "Random", limit = limit)
        }
        if (result.items.isEmpty()) {
          Toast.makeText(context, "该${kindLabel}下没有可随机播放的内容", Toast.LENGTH_SHORT).show()
          return@launch
        }
        viewModel.launchPlaylist(current, result.items, playFromStartAll = true)
      }
    }

    LaunchedEffect(keyword, kind, filterId, server) { load() }

    // 收藏红心所需的条目：工作室用详情页传来的 Id，类型按名字反查，标签无 Id 故为 null。
    // 标签（kind 既不是 genre 也不是 studio）下红心始终隐藏，与 Emby Web 不支持收藏标签一致。
    LaunchedEffect(keyword, kind, filterId, server) {
      val current = server ?: run { facetItem = null; return@LaunchedEffect }
      facetItem = viewModel.loadFacetItem(current, kind, keyword, filterId)
    }

    // 触底自动续拉
    page.AutoLoadMore(source = gridSource, enabled = server != null, loader = loader)

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Text(
            text = keyword,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
            )
          }
        },
        actions = {
          // ── 随机播放这个类型 / 标签 / 工作室下的作品（服务端按 Random 从整个范围抽）──
          IconButton(
            enabled = page.items.isNotEmpty(),
            onClick = { startRandomPlayback() },
          ) {
            Icon(
              imageVector = Icons.Default.Shuffle,
              contentDescription = "随机播放",
            )
          }
          // ── 顶栏收藏红心 ──
          // 与演员作品页同一套交互：乐观更新红心 → 请求服务器 → 按结果修正并弹 Toast；
          // 动效复用 FavoriteHeartIcon（弹跳 + 星光 + 红心渐变）。
          // 仅当能解析出可收藏条目（类型 / 工作室）时显示；标签无 Id 自动隐藏红心。
          val facetItemSnapshot = facetItem
          if (facetItemSnapshot != null) {
            val isFacetFavorite = facetItemSnapshot.UserData?.IsFavorite == true
            IconButton(
              enabled = true,
              onClick = {
                val current = facetItem ?: return@IconButton
                val currentServer = server ?: return@IconButton
                val wasFavorite = current.UserData?.IsFavorite == true
                // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
                facetItem = current.copy(
                  UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = !wasFavorite),
                )
                scope.launch {
                  val result = viewModel.toggleFavorite(currentServer, current)
                  val nowFavorite = result.getOrNull()
                  facetItem = current.copy(
                    UserData = (current.UserData ?: EmbyUserData())
                      .copy(IsFavorite = nowFavorite ?: wasFavorite),
                  )
                  Toast.makeText(
                    context,
                    when (nowFavorite) {
                      true -> "已收藏「$keyword」"
                      false -> "已取消收藏「$keyword」"
                      null -> "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                    Toast.LENGTH_SHORT,
                  ).show()
                }
              },
            ) {
              // 与演员作品页同款：弹跳 + 星光 + 红心渐变（状态驱动，点击时先乐观更新）
              // 尺寸对齐 24dp，避免「顶栏这颗比其它页面小一圈」的观感落差
              FavoriteHeartIcon(
                isFavorite = isFacetFavorite,
                isToggling = false,
                iconSize = 24.dp,
                idleColor = LocalContentColor.current,
              )
            }
          }
        },
      )

      // 一行说明，告诉用户当前是按什么维度筛的。
      // 分页之后报**服务端总数**而不是已加载条数 —— 否则用户会以为「就这么多」而不去滚。
      Text(
        text = when {
          isLoading -> "加载中…"
          error != null && page.items.isEmpty() -> "$kindLabel"
          page.total != null -> "$kindLabel · 共 ${page.total} 项"
          else -> "$kindLabel · 已加载 ${page.items.size} 项"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          isLoading && page.items.isEmpty() -> {
            // 与下面结果的版式对齐（海报卡、约三列）
            EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)
          }

          error != null && page.items.isEmpty() -> {
            Text(
              text = error ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            )
          }

          else -> {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 110.dp),
              state = gridState,
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              items(page.items, key = { it.Id ?: it.Name ?: "" }) { item ->
                EmbyMediaCard(
                  title = item.Name ?: "",
                  subtitle = item.ProductionYear?.toString(),
                  imageUrl = server?.let { viewModel.imageUrl(it, item, "Primary", 400) },
                  progress = null,
                  isFavorite = item.UserData?.IsFavorite == true,
                  fillWidth = true,
                  onClick = {
                    item.Id?.let { id ->
                      backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
                    }
                  },
                )
              }
              // 底部：续拉中 / 出错重试 / 已到底
              item(span = { GridItemSpan(maxLineSpan) }) {
                EmbyPagingFooter(
                  appending = page.appending,
                  hasMore = page.hasMore,
                  error = page.error,
                  loadedCount = page.items.size,
                  endText = page.total?.let { "已显示全部 ${page.items.size} / $it 项" },
                  onRetry = { scope.launch { page.retry(loader) } },
                )
              }
            }
          }
        }
      }
    }
  }
}
