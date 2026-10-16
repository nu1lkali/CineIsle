package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.DisposableEffect
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
 * 布局：工具行与图区是 **Column 上下两行**（工具行占自己的高度），
 * 不是「工具行叠在图区之上」。⭐ 叠上去的写法必须靠内容自己留白让位，
 * 而上滑时那点留白会跟着滚走，卡片就压到按钮底下了（真机反馈）；
 * 上下两行是真正的「安全区」—— 图区从工具行**下方**开始，怎么滑都进不去。
 *
 * 与视频媒体库页的刻意差异（需求锁定，见 photo.md 方案）：
 * - 工具行只有：数量 + **随机播放图片** + 列数 + 排序。
 *   没有视界流 / 随机播放(视频) / 随机播放收藏 / 筛选 —— 那些对照片没有意义。
 * - 网格是**统一等比**的（每张卡同一尺寸、缩略图 Crop 填满），
 *   不再是「高度随原图比例」的瀑布流 —— 后者横竖图并排大小差一倍，观感很乱。
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
    // 相册态是**怎么来的**：true = 用户在本页点相册点进去的；false = 构造时就带着
    // initialAlbumId、一进来就是相册态（媒体库 → 文件夹分类 → 点某个相册）。
    // ⚠️ 后者**没有可退的相册网格**：本页的 libraryId 就是那个相册自己的 Id，
    //    退回去只会拉到一个空的相册网格（显示「此库没有照片」），用户还得再按一次
    //    才真正出栈。所以返回键只在「本页点进去的」那种情况拦截（见下方 BackHandler）。
    var enteredAlbumInPage by remember { mutableStateOf(false) }

    // 排序 / 列数：全局一份（照片网格一套设置走天下，不按库分）
    val sortBy by browserPreferences.photoSortBy.collectAsState()
    val sortOrderOverride by browserPreferences.photoSortOrder.collectAsState()
    val sortOrder = sortOrderOverride.takeIf { it.isNotBlank() }
      ?: if (sortBy == "SortName") "Ascending" else "Descending"
    val savedColumns by browserPreferences.photoGridColumns.collectAsState()
    val columnCount = savedColumns.coerceIn(2, 4)

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    // ⚠️ 「该显示转圈」和「有没有请求在飞」是两件事，必须分开：
    //  · loading 的初值只能是 true —— 首帧还没发请求，给 false 会先闪一下「此库没有照片」；
    //  · 但它**不能**同时当并发闸门。曾经的写法是 `if (loading || loadingMore) return`，
    //    于是第一次 fetchPage(reset = true) 被自己挡掉、loading 再也回不到 false
    //    ⇒ 整页永远停在转圈（「点进相册/文件夹一直加载中」就是这个）。
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    // 请求代际：每次 reset 拉取 ++。迟到的旧响应（用户已经换了相册 / 排序）整批丢弃，
    // 否则它会盖到新内容上 —— 表现为「切了相册，看到的还是上一个相册的照片」。
    var generation by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var consumed by remember { mutableIntStateOf(0) }
    var showColumnsMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // 「再洗一副牌」的计数器：点排序里的「随机」时 ++，把重拉的请求踢一脚。
    // ⭐ 为什么需要它：换排序项 / 反转方向都会改 LaunchedEffect 的 key，从而重拉；
    //   但「已经是 Random 了、用户还想再洗一次」时 sortBy 与 sortOrder 都没变，
    //   key 不变 ⇒ 不会重拉 ⇒ 清了缓存也白清，看起来像点了没反应。
    var shuffleNonce by remember { mutableIntStateOf(0) }

    // 归位标记：换排序（含「随机」重洗）后，等**新一批数据落位**再把网格滚回顶部。
    // 为什么不能在换排序的 LaunchedEffect 里直接滚：[fetchPage] 是「发射后就返回」的
    // （内部自己 `scope.launch`），那时新数据还没回来 —— 滚了会被 LazyGrid 的
    // 「按 key 把原第一条重新定位到新位置」抵掉，画面照样停在列表中间。见其收尾处。
    var pendingScrollTop by remember { mutableStateOf(false) }

    // 当前父节点：相册态 = 该相册 Id，库根态 = libraryId。
    // ⚠️ 必须写成函数而不是 `val … get()` —— Kotlin 不接受局部属性带自定义 getter
    // （编译报 "Unexpected tokens"，且会把 parentId 误判成无初始化量的 val）。
    // album 是状态，每次调用都要读最新值，所以也不能用 `= album?.Id ?: libraryId` 快照。
    fun parentId(): String = album?.Id ?: libraryId

    /** 拉一页；[reset] = 清空重来（换层级 / 换排序），否则追加 */
    fun fetchPage(reset: Boolean) {
      val current = server ?: return
      // 续页同一时刻只允许一个：重复触发会把同一页拉两遍。
      // ⚠️ 这里**只看 loadingMore**，绝不能用 loading 当闸门（见上面 loading 的说明）。
      if (!reset && loadingMore) return
      val startIndex = if (reset) 0 else consumed
      if (!reset && startIndex >= totalCount) return
      // 只有 reset 才推进代际：续页不该让在途的整批加载作废
      val gen = if (reset) ++generation else generation
      if (reset) {
        loading = true
        errorMessage = null
      } else {
        loadingMore = true
      }

      // ⭐ 随机排序：命中缓存就直接复用，不再问服务端。
      //
      // 服务端 `SortBy=Random` 是**每次请求重新洗牌**的 —— 只要本页重新拉一次
      // （从大图返回、退回相册网格再进、甚至切走再回来），顺序就全变了，
      // 用户刚看过的那张再也找不回来（真机反馈：「返回上一级就重新随机了」）。
      // 所以第一批拉到之后就按相册缓存在进程内，重进直接复用同一副牌。
      if (reset && sortBy == "Random") {
        val cached = PhotoRandomQueue.get(parentId())
        if (cached != null) {
          items = cached.items
          consumed = cached.items.size
          totalCount = cached.totalCount
          loading = false
          loadingMore = false
          // 命中缓存说明顺序没换（只是重进同一副牌），不需要归位；顺手清掉残留标记
          pendingScrollTop = false
          return
        }
      }

      scope.launch {
        try {
          val result = runCatching {
            withContext(Dispatchers.IO) {
              if (album == null) {
                vm.loadPhotoAlbums(current, parentId(), sortBy, sortOrder, startIndex = startIndex)
              } else {
                vm.loadPhotos(current, parentId(), sortBy, sortOrder, startIndex = startIndex)
              }
            }
          }
          // 代际已前进：这次结果属于上一个层级 / 排序，整批丢弃（收尾交给新那次）
          if (gen != generation) return@launch
          result.onSuccess { res ->
            val random = sortBy == "Random"
            if (reset) {
              items = res.Items
              consumed = res.Items.size
              if (random) PhotoRandomQueue.put(parentId(), res.Items, res.TotalRecordCount)
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
            // 随机排序下续页拿到的同样是新洗的一批，追加进缓存即可 ——
            // 已经看过的那部分顺序不动，只是队尾多了一批（比「整副牌重洗」温和得多）
            if (random) PhotoRandomQueue.put(parentId(), items, totalCount)
            // 换排序 / 重洗后的归位：新数据已经落位，此刻滚才真正生效（见 pendingScrollTop 说明）
            if (reset && pendingScrollTop) {
              pendingScrollTop = false
              gridState.scrollToItem(0)
            }
          }.onFailure { e ->
            if (reset) errorMessage = "加载照片失败：${e.message}"
          }
        } finally {
          // 自己的标志自己收：过期的请求也要把「续页中」放开，
          // 否则一次被接替的续页会让这一页此后再也不续页。
          loadingMore = false
          // loading 只由**当前这一代**收尾 —— 过期的别去动它，
          // 那一代的请求还在飞，动了就会闪出空态。
          if (gen == generation) loading = false
        }
      }
    }

    // 换层级 / 换排序 ⇒ 重拉第一页
    //
    // ⭐ 换排序（或点「随机」再洗一次）之后回到顶部，首次进入除外：
    //   「随机」整副牌重洗、其它排序整体换序后，条目顺序全变了，而 LazyGrid 会**按 key**
    //   把「原来的第一条」重新定位到它在新顺序里的位置 ⇒ 画面停在列表中间（用户反馈
    //   「切到随机后停在中间」）。
    // ⚠️ 这里只**置标记**，真正的归位在 [fetchPage] 的新数据落位之后 ——
    //   本 effect 调用的 fetchPage 是发射后就返回的，当场滚会被 key 重定位抵掉
    //   （见上面 pendingScrollTop 的说明）。
    // ⚠️ 用「排序组合是否真的变了」判据、而不是「是不是第一次跑」：
    //   本 effect 的 key 里有 server?.id，冷启动时它会从 null 变成有值、effect 会再跑一次 ——
    //   用「第一次」判据的话那次会被误判成「换了排序」而把恢复出来的位置顶掉。
    //   换相册（album?.Id 变）时 lastSortKey 随之重新初始化，键相同 ⇒ 不置标记，新相册照样能恢复。
    var lastSortKey by remember(album?.Id) { mutableStateOf("$sortBy|$sortOrder|$shuffleNonce") }
    LaunchedEffect(server?.id, album?.Id, sortBy, sortOrder, shuffleNonce) {
      val key = "$sortBy|$sortOrder|$shuffleNonce"
      if (key != lastSortKey) {
        lastSortKey = key
        pendingScrollTop = true
      }
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

    // ── 记住上次浏览位置 ──
    //
    // 浏览器退出时会按 parentId 记下「看到第几张」，这里在**重新进入**相册时把网格
    // 滚到那一张 —— 不然每次点进相册都从第 1 张开始，看到第 200 张退出去再进来就得
    // 重新滑一遍。
    //
    // ⭐ 只做**一次**：续页会不断把 [items] 变长，而本 effect 的 key 里有 items.size，
    // 没有 [restored] 闸门的话每续一页都会把用户重新拽回定位点（表现为「往下翻着翻着
    // 自己跳回去了」）。
    // ⭐ 也只在 idx > 0 时滚：上次就停在第 1 张还要滚一次纯属多余动画。
    val parentKey = album?.Id ?: libraryId
    val lastIndexPref = remember(parentKey) { browserPreferences.photoLastIndex(parentKey) }
    val lastOffsetPref = remember(parentKey) { browserPreferences.photoLastOffset(parentKey) }
    var restored by remember(parentKey) { mutableStateOf(false) }
    LaunchedEffect(parentKey, items.size) {
      if (restored || items.isEmpty()) return@LaunchedEffect
      restored = true
      val idx = lastIndexPref.get()
      if (idx > 0) {
        // 只定位到**已经加载出来的范围**内：页码超出已加载部分时不硬滚
        // （要么抛异常，要么得为一次滚动连拉好几页、几十个请求换一次定位，不值）
        runCatching {
          gridState.scrollToItem(idx.coerceAtMost(items.size - 1), lastOffsetPref.get())
        }
      }
    }

    // ── 退出时记下滚动位置 ──
    //
    // 光记「打开过第几张」不够：很多时候用户只是在网格里一路滚着看、一张都没点开，
    // 退出去再进来还是从第 1 张开始。所以这里把**网格自己的滚动位置**也存下来。
    //
    // ⭐ 与浏览器写的「当前第几张」谁生效：从大图返回后如果用户**没有再滚动**
    //   （可见位置还停在打开照片时的那格），就保留浏览器写的那张 —— 那才是他最后看的；
    //   一旦滚过，就以新的滚动位置为准。
    var indexWhenOpened by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(parentKey) {
      onDispose {
        if (items.isEmpty()) return@onDispose
        val firstVisible = gridState.firstVisibleItemIndex
        val opened = indexWhenOpened
        if (opened == null || firstVisible != opened) {
          lastIndexPref.set(firstVisible)
          lastOffsetPref.set(gridState.firstVisibleItemScrollOffset)
        }
      }
    }

    // 相册态按返回键先退回相册网格，而不是直接退出整个照片页。
    // ⚠️ 但**只有**「在本页点相册点进去的」才该这样退：直接以 initialAlbumId 进来的
    //    那一档要让它正常出栈，否则会退到以相册 Id 当父级的空网格（见 enteredAlbumInPage）。
    BackHandler(enabled = inAlbum && enteredAlbumInPage) {
      album = null
      enteredAlbumInPage = false
    }

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
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        // ── 工具行：数量 + 随机播放图片 + 列数 + 排序 ──
        // ⭐ 它是 Column 的**第一行，占自己的高度**，图区从它下面开始 ——
        // 这样上滑时卡片不会压到按钮上（「安全区」的真做法）。
        // 以前它是叠在图区之上的悬浮条，靠网格自己留白让位：留白会跟着滚走，
        // 滚一段后卡片就跑到按钮底下了（真机反馈「卡片和工具栏叠在一起」）。
        // 数量文案 weight(1f) 吃掉剩余宽度，保证右边按钮永不被挤出屏幕
        Row(
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
                      if (value == "Random") {
                        // 已经是随机了，再点一次 = **再洗一副牌**。
                        // 「正序/倒序」对随机没有意义，所以这里不碰 sortOrder，
                        // 改为清缓存 + 踢 shuffleNonce 触发重拉。
                        PhotoRandomQueue.invalidate(parentId())
                        shuffleNonce++
                      } else {
                        browserPreferences.photoSortOrder.set(
                          if (sortOrder == "Ascending") "Descending" else "Ascending",
                        )
                      }
                    } else {
                      // 换排序项：方向清空，重新跟随自然方向
                      // 切**进**随机也先清缓存 —— 上一次的随机序列不该留到这回
                      if (value == "Random") PhotoRandomQueue.invalidate(parentId())
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

        // ── 图区（工具行**下面**那一行）──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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

            else -> PhotoGrid(
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
                  // 记下「打开时的滚动位置」：退出相册时据此判断用户回来后有没有再滚动过
                  indexWhenOpened = gridState.firstVisibleItemIndex
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
                  // ⚠️ 记下「是本页点进去的」：只有这种情况返回键才先退回相册网格
                  album = item
                  enteredAlbumInPage = true
                }
              },
            )
          }
        }
      }
    }
  }
}

/**
 * 「随机」排序下每批结果的进程内缓存：key = 相册 / 库 的 parentId。
 *
 * 存在的唯一理由：Emby 服务端 `SortBy=Random` **每次请求都重新洗牌**。
 * 网格页一旦重新拉取（从大图返回、退回相册网格再进、误触返回），整屏顺序就变了，
 * 用户刚看过的那张再也找不到。缓存之后，同一个相册在进程存活期间永远是同一副牌。
 *
 * 为什么不持久化到磁盘：
 *  · 随机序列是「这一轮逛相册」的上下文，隔天再来本来就该是新的；
 *  · 存下来还会让「换一台设备 / 服务端新增了照片」后的行为变得难以解释。
 * 进程重启后重新随机，这是**符合预期**的（用户能理解「重开 App 换一批」）。
 *
 * ⚠️ 用 synchronizedMap：下拉续页在 IO 线程回调后回主线程写，而命中缓存的读发生在
 *    组合里，两端不是同一线程。
 *
 * ⚠️ 两层上限（百万张相册实测教训）：
 *    · MAX_ITEMS —— 续页后 put 的是**全量 items**，不封顶的话一个百万张的相册
 *      翻到底就能把 Entry 撑爆（OOM）。只保前 2000 张「同一副牌」；
 *      totalCount 保留真实值，命中缓存后 consumed=2000 < totalCount 会自然
 *      续页从 2000 继续拉服务端，行为是「前 2000 张稳定 + 后面新洗」，比 OOM 好。
 *    · MAX_PARENTS —— 逛相册是进进出出的，只保最近几个 parent，淘汰最老的。
 */
object PhotoRandomQueue {
  class Entry(val items: List<EmbyItem>, val totalCount: Int)

  /** 单个 parent 最多缓存多少条随机牌 */
  private const val MAX_ITEMS = 2_000

  /** 进程内最多缓存几个 parent 的随机牌 */
  private const val MAX_PARENTS = 4

  private val cache = java.util.Collections.synchronizedMap(mutableMapOf<String, Entry>())

  fun get(parentId: String): Entry? = cache[parentId]

  fun put(parentId: String, items: List<EmbyItem>, totalCount: Int) {
    val capped = if (items.size > MAX_ITEMS) items.take(MAX_ITEMS) else items
    synchronized(cache) {
      // 先摘掉自己再塞：mutableMapOf 是 LinkedHashMap，已存在的 key 重复 put
      // **不会刷新顺序** —— 不先摘的话，频繁重进的那个相册会一直排在
      // 最老位置，下一秒就被误淘汰，缓存等于白建
      cache.remove(parentId)
      cache[parentId] = Entry(capped, totalCount)
      // 淘汰最老的；⚠️ synchronizedMap 的迭代器不支持 remove，
      // 必须 keys.toList() 快照之后再删（本仓 ConcurrentHashMap 同款教训）
      while (cache.size > MAX_PARENTS) {
        val oldest = cache.keys.toList().firstOrNull() ?: break
        cache.remove(oldest)
      }
    }
  }

  /** 作废某个相册的随机序列（下次进入重新洗牌） */
  fun invalidate(parentId: String) {
    cache.remove(parentId)
  }
}

/** 照片网格排序项：值与 Emby /Items 的 SortBy 参数一致 */
private val PHOTO_SORT_OPTIONS = listOf(
  "SortName" to "名称",
  "DateCreated" to "拍摄时间",
  "Random" to "随机",
)

/**
 * 照片网格本体。抽出来只是为了让 [EmbyPhotoGridScreen.Content] 的 when 分支短一点。
 *
 * ⚠️ 用**普通网格**而不是瀑布流：照片卡已经是统一等比（见 EmbyPhotoCard.aspectRatio），
 * 瀑布流在这种等高卡片下只会让相邻列错位、底部参差 —— 正好是用户反馈的
 * 「卡片大小不一」。等高卡片 + 普通网格才是齐整的那一种。
 *
 * @param displayUrl / fallbackUrl 按卡片需要的解码宽度现算（HEIC 兜底同参数）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
  columnCount: Int,
  gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
  items: List<EmbyItem>,
  inAlbum: Boolean,
  displayUrl: (EmbyItem, Int) -> String?,
  fallbackUrl: (EmbyItem, Int) -> String?,
  onCardClick: (EmbyItem) -> Unit,
) {
  LazyVerticalGrid(
    columns = GridCells.Fixed(columnCount),
    state = gridState,
    // ⭐ 顶部 12dp 就是普通留白 —— 工具行已经**占了自己的那一行**，
    // 网格不用（也不能）再为它让位：contentPadding 会跟着内容滚走，
    // 靠它让位等于没让（v1.5.2 的「卡片压在工具行底下」就是这么来的）。
    contentPadding = PaddingValues(
      start = 12.dp,
      end = 12.dp,
      top = 12.dp,
      bottom = 12.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(10.dp),
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
