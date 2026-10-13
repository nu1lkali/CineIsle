package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 照片库网格页（图集入口页，二级页面）。
 *
 * 两个状态，都在本页内切换（不新开页面，返回键退回上一态）：
 *  1. **相册网格**：库根下的一级 PhotoAlbum 容器（recursive=false，不拉平）；
 *  2. **照片网格**：某个相册内的 Photo（点相册进入）。
 *
 * 与视频媒体库页的刻意差异（需求锁定，见 photo.md 方案）：
 * - 工具行只有：数量 + **随机播放图片** + 列数 + 排序。
 *   没有视界流 / 随机播放(视频) / 随机播放收藏 / 筛选 —— 那些对照片没有意义。
 * - 瀑布流（StaggeredGrid）保留照片原始宽高比，不做 2:3 裁切。
 *
 * 分页：⚠️ StartIndex 用独立 [consumed] 计数，**永远不用 items.size**
 * （服务端单页可能重复，两者会不等 ⇒ 永远到不了底）。
 */
@Serializable
data class EmbyPhotoGridScreen(
  val libraryId: String,
  val title: String,
  /**
   * 直接以**相册态**打开（跳过相册网格这一步）。
   *
   * 用在「媒体库 → 文件夹分类 → 点某个相册」：那时已经明确要看哪个相册，
   * 再让用户从相册网格里点一次是白多一步。为 null 时行为与以前逐字节一致。
   */
  val initialAlbumId: String? = null,
  val initialAlbumName: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by vm.currentServer.collectAsState()
    val browserPreferences = koinInject<BrowserPreferences>()

    // 当前层级：null = 库根（相册网格）；非 null = 该相册内的照片网格。
    // [initialAlbumId] 非空时直接落位相册态 —— 本页只需要相册的 Id 与 Name，
    // 所以构造一个最小 EmbyItem 当载体，不为这一步多发一次详情请求。
    var album by remember {
      mutableStateOf(
        initialAlbumId?.takeIf { it.isNotBlank() }
          ?.let { EmbyItem(Id = it, Name = initialAlbumName, Type = "PhotoAlbum") },
      )
    }
    val inAlbum = album != null

    // 排序 / 列数：全局一份（照片网格一套设置走天下，不按库分）
    val sortBy by browserPreferences.photoSortBy.collectAsState()
    val sortOrderOverride by browserPreferences.photoSortOrder.collectAsState()
    val sortOrder = sortOrderOverride.takeIf { it.isNotBlank() }
      ?: if (sortBy == "SortName") "Ascending" else "Descending"
    val savedColumns by browserPreferences.photoGridColumns.collectAsState()
    val columnCount = savedColumns.coerceIn(2, 4)

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var consumed by remember { mutableIntStateOf(0) }
    var showColumnsMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()

    // 当前父节点：相册态 = 该相册 Id，库根态 = libraryId。
    // ⚠️ 必须写成函数而不是 `val … get()` —— Kotlin 不接受局部属性带自定义 getter
    // （编译报 "Unexpected tokens"，且会把 parentId 误判成无初始化量的 val）。
    // album 是状态，每次调用都要读最新值，所以也不能用 `= album?.Id ?: libraryId` 快照。
    fun parentId(): String = album?.Id ?: libraryId

    /** 拉一页；[reset] = 清空重来（换层级 / 换排序），否则追加 */
    fun fetchPage(reset: Boolean) {
      val current = server ?: return
      if (loading || loadingMore) return
      val startIndex = if (reset) 0 else consumed
      if (!reset && startIndex >= totalCount) return
      if (reset) {
        loading = true
        errorMessage = null
      } else {
        loadingMore = true
      }
      scope.launch {
        val result = runCatching {
          withContext(Dispatchers.IO) {
            if (album == null) {
              vm.loadPhotoAlbums(current, parentId(), sortBy, sortOrder, startIndex = startIndex)
            } else {
              vm.loadPhotos(current, parentId(), sortBy, sortOrder, startIndex = startIndex)
            }
          }
        }
        result.onSuccess { res ->
          if (reset) {
            items = res.Items
            consumed = res.Items.size
          } else {
            // 翻页边界可能重复（同一 StartIndex 被服务端重算），按 Id 去重再追加
            val seen = items.mapNotNull { it.Id }.toHashSet()
            val fresh = res.Items.filter { it.Id == null || it.Id !in seen }
            items = items + fresh
            // ⚠️ consumed 按「服务端吐了多少」推进，不是按追加了多少 ——
            //    否则去重掉的几条会让下一页 StartIndex 回退、同一页反复拉
            consumed += res.Items.size
            if (res.Items.isEmpty()) totalCount = consumed // 兜底：没数据就停，别死循环
          }
          totalCount = res.TotalRecordCount
        }.onFailure { e ->
          if (reset) errorMessage = "加载照片失败：${e.message}"
        }
        loading = false
        loadingMore = false
      }
    }

    // 换层级 / 换排序 ⇒ 重拉第一页
    LaunchedEffect(server?.id, album?.Id, sortBy, sortOrder) {
      fetchPage(reset = true)
    }

    // 滑到距末尾 4 个以内续页。consumed 进 key：每拉一页都要重新判一次
    val nearEnd by remember {
      derivedStateOf {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
      }
    }
    LaunchedEffect(nearEnd, consumed) {
      if (nearEnd) fetchPage(reset = false)
    }

    // 相册态按返回键先退回相册网格，而不是直接退出整个照片页
    BackHandler(enabled = inAlbum) { album = null }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(album?.Name ?: title, maxLines = 1) },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        when {
          loading -> AppLoadingIndicator(
            modifier = Modifier
              .size(48.dp)
              .align(Alignment.Center),
          )

          errorMessage != null -> Text(
            text = errorMessage ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .align(Alignment.Center)
              .padding(32.dp),
          )

          items.isEmpty() -> Text(
            text = if (inAlbum) "此相册没有照片" else "此库没有照片",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .align(Alignment.Center)
              .padding(32.dp),
          )

          else -> PhotoStaggeredGrid(
            columnCount = columnCount,
            gridState = gridState,
            items = items,
            inAlbum = inAlbum,
            displayUrl = { item, maxWidth -> server?.let { vm.photoImageUrl(it, item, maxWidth) } },
            fallbackUrl = { item, maxWidth ->
              server?.let { vm.photoImageUrl(it, item, maxWidth, format = "jpg") }
            },
            onCardClick = { item ->
              if (inAlbum) {
                // 照片 → 全屏浏览器；队列 = 当前网格快照（含已续页拉到的部分）
                val index = items.indexOfFirst { it.Id == item.Id }.coerceAtLeast(0)
                backStack.add(
                  EmbyPhotoBrowserScreen(
                    initialPhotos = items,
                    initialIndex = index,
                    parentId = parentId(),
                    title = album?.Name ?: title,
                  ),
                )
              } else {
                // 相册 → 进入该相册（本页内切态）
                album = item
              }
            },
          )
        }

        // ── 工具行：数量 + 随机播放图片 + 列数 + 排序 ──
        // 贴在图区之上（不占布局高度）：与视频库一致的做法。
        // 数量文案 weight(1f) 吃掉剩余宽度，保证右边按钮永不被挤出屏幕
        androidx.compose.foundation.layout.Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = when {
              loading -> "加载中…"
              totalCount > 0 -> if (inAlbum) "$totalCount 张" else "$totalCount 个相册"
              else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
          )
          // 随机播放图片：相册态随机本相册内、库根态随机整个库（递归拉平），
          // 条数走「随机播放数量」偏好（与视频随机播放同一个设置）
          IconButton(onClick = {
            val current = server ?: return@IconButton
            scope.launch {
              val result = runCatching {
                withContext(Dispatchers.IO) {
                  vm.loadPhotos(
                    current,
                    parentId(),
                    sortBy = "Random",
                    sortOrder = null,
                    // 与视频随机播放同一个数量设置；夹在 1..500，
                    // 偏好被外部改坏时也不至于拉 0 条或把服务端打挂
                    limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500),
                  )
                }
              }
              result.onSuccess { res ->
                val photos = res.Items.filter { it.Type == "Photo" || it.Type == null }
                if (photos.isEmpty()) {
                  Toast.makeText(context, "这里没有照片", Toast.LENGTH_SHORT).show()
                } else {
                  backStack.add(
                    EmbyPhotoBrowserScreen(
                      initialPhotos = photos,
                      initialIndex = 0,
                      parentId = parentId(),
                      title = album?.Name ?: title,
                    ),
                  )
                }
              }.onFailure {
                Toast.makeText(context, "加载失败，稍后再试", Toast.LENGTH_SHORT).show()
              }
            }
          }) {
            Icon(Icons.Default.Shuffle, contentDescription = "随机播放图片")
          }
          // 列数 2 / 3 / 4
          Box {
            IconButton(onClick = { showColumnsMenu = true }) {
              Icon(Icons.Default.GridView, contentDescription = "列数")
            }
            DropdownMenu(
              expanded = showColumnsMenu,
              onDismissRequest = { showColumnsMenu = false },
            ) {
              listOf(2, 3, 4).forEach { n ->
                DropdownMenuItem(
                  text = {
                    Text(
                      text = "$n 列",
                      color = if (n == columnCount) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurface
                      },
                    )
                  },
                  onClick = {
                    browserPreferences.photoGridColumns.set(n)
                    showColumnsMenu = false
                  },
                )
              }
            }
          }
          // 排序：名称 / 拍摄时间；再点一次已选项 = 反转方向
          Box {
            IconButton(onClick = { showSortMenu = true }) {
              Icon(Icons.Default.Sort, contentDescription = "排序")
            }
            DropdownMenu(
              expanded = showSortMenu,
              onDismissRequest = { showSortMenu = false },
            ) {
              PHOTO_SORT_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(
                  text = {
                    Text(
                      text = buildString {
                        append(label)
                        if (value == sortBy) {
                          append(if (sortOrder == "Ascending") " ↑" else " ↓")
                        }
                      },
                      color = if (value == sortBy) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurface
                      },
                    )
                  },
                  onClick = {
                    if (value == sortBy) {
                      browserPreferences.photoSortOrder.set(
                        if (sortOrder == "Ascending") "Descending" else "Ascending",
                      )
                    } else {
                      // 换排序项：方向清空，重新跟随自然方向
                      browserPreferences.photoSortBy.set(value)
                      browserPreferences.photoSortOrder.set("")
                    }
                    showSortMenu = false
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/** 照片网格排序项：值与 Emby /Items 的 SortBy 参数一致 */
private val PHOTO_SORT_OPTIONS = listOf(
  "SortName" to "名称",
  "DateCreated" to "拍摄时间",
  "Random" to "随机",
)

/**
 * 瀑布流本体。抽出来只是为了让 [EmbyPhotoGridScreen.Content] 的 when 分支短一点。
 *
 * @param displayUrl / fallbackUrl 按卡片需要的解码宽度现算（HEIC 兜底同参数）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoStaggeredGrid(
  columnCount: Int,
  gridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
  items: List<EmbyItem>,
  inAlbum: Boolean,
  displayUrl: (EmbyItem, Int) -> String?,
  fallbackUrl: (EmbyItem, Int) -> String?,
  onCardClick: (EmbyItem) -> Unit,
) {
  LazyVerticalStaggeredGrid(
    columns = StaggeredGridCells.Fixed(columnCount),
    state = gridState,
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
    verticalItemSpacing = 8.dp,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    items(items, key = { it.Id ?: it.Name ?: "" }) { item ->
      EmbyPhotoCard(
        item = item,
        displayUrl = displayUrl(item, 720),
        fallbackUrl = fallbackUrl(item, 720),
        // 相册态副标题给「N 张」；照片态给相机型号 + 像素尺寸
        subtitle = if (inAlbum) {
          item.ChildCount?.let { "$it 张" }
        } else {
          photoSubtitle(item)
        },
        onClick = { onCardClick(item) },
      )
    }
  }
}
