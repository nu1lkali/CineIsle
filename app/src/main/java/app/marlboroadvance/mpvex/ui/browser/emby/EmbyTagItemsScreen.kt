package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 标签 / 类型结果页一页拉多少条（清单 1.1：原来固定 120 条） */
private const val TAG_PAGE_SIZE = 100

/**
 * 「按类型 / 标签找片」结果页。
 *
 * 详情页顶部的类型 chip（以及标签 chip）点一下进来：
 * 和点头像进「演员作品页」是同一套交互 —— 只是过滤维度从 PersonIds 换成 Genres / Tags。
 *
 * 走 Emby 的 /Items 过滤（不传 ParentId，跨全部媒体库），只保留可播放类型，
 * 结果按名称升序，点卡片照常进详情页。
 *
 * @param keyword 类型名或标签名（就是 chip 上的文字）
 * @param kind    "genre" = 类型，其它值按标签处理
 */
@Serializable
data class EmbyTagItemsScreen(
  val keyword: String,
  val kind: String = "genre",
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

    val isGenre = kind == "genre"
    val kindLabel = if (isGenre) "类型" else "标签"

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 分页状态（清单 1.1）：原来一次拉 120 条就结束了，热门类型（科幻 / 动作）远不止
    val page = remember { PagedList<EmbyItem>(TAG_PAGE_SIZE, keyOf = { it.Id ?: it.Name ?: "" }) }
    val gridState = rememberLazyGridState()
    val gridSource = gridState.pagingSource()

    /** 本页的加载器：类型与标签只差一个 ViewModel 方法 */
    val loader: suspend (Int, Int) -> PagedResult<EmbyItem> = pageLoader@{ start, limit ->
      val target = server ?: return@pageLoader PagedResult(emptyList(), null, limit)
      val result = if (isGenre) {
        viewModel.loadGenreItems(server = target, genre = keyword, startIndex = start, limit = limit)
      } else {
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

    LaunchedEffect(keyword, kind, server) { load() }

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
