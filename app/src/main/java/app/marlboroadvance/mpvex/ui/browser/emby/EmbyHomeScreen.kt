package app.marlboroadvance.mpvex.ui.browser.emby

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.LibraryOrdering
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryOrderDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLibraryVisibilityMenu
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMaintainButton
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyPosterCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilterRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchTabRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.OopsErrorAnimation
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonHome
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyWideCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyItemActionsDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.SearchHistoryPanel
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarRecommendationViewModel
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Emby 首页。
 *
 * 结构参照 Emby 官方客户端：
 * 1. 顶部横向媒体库卡片（电影 / 剧集 / 音乐 …）
 * 2. 继续观看（横向宽卡片）
 * 3. 最新加入（横向海报卡片）
 *
 * 交互约定：**单击直接播放，长按查看媒体详情**。
 *
 * @param onOpenLibrary 点击媒体库入口（下钻到库内）
 * @param onOpenDetail 长按媒体项：打开详情页
 * @param onManageServers 打开 Emby 服务器维护（顶栏用 Emby 官方图标标识）
 * @param onOpenSettings 打开 App 设置页（顶栏齿轮）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbyHomeScreen(
  onOpenLibrary: (EmbyItem) -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  onManageServers: () -> Unit,
  onOpenSettings: () -> Unit,
  /**
   * 打开 Emby 服务端播放列表（清单 #12）。
   *
   * 单独一个回调而不是让首页自己 push：导航栈由 [MainScreen] 持有，
   * 首页拿不到 —— 和 onManageServers / onOpenSettings 是同一套做法。
   */
  onOpenPlaylists: () -> Unit = {},
  /**
   * 打开「猜你喜欢 / 即混」独立页（清单 #14）。
   *
   * 同样只传回调不自己 push：导航栈在 [MainScreen] 手里。
   */
  onOpenDiscover: () -> Unit = {},
  /**
   * 打开「合集」管理页（清单 #15）。
   *
   * 与播放列表分开一个入口：合集是「同一部作品的不同版本」，
   * 播放列表是「任意片单」，两者语义不同，混在一个列表里用户会认错。
   */
  onOpenCollections: () -> Unit = {},
  /**
   * 打开「直播电视」频道列表页。
   *
   * 频道来自 Emby 的 `/LiveTv/Channels`，只有列表 + 点播播放，
   * 不做 EPG / 录制（服务端没配 LiveTv 时会返回空列表，页面给空态文案）。
   * 同样只传回调、不自己 push：导航栈在 [MainScreen] 手里。
   */
  onOpenLiveTv: () -> Unit = {},
) {
  val context = LocalContext.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as android.app.Application),
  )

  // 「随便看看」区块用的推荐 ViewModel（清单 #14 的一部分）。
  // 首页只取**即混**这一块 —— 它不需要用户先选种子（没有种子时退化成随机一批），
  // 相似推荐和续播仍然留在独立页：前者要挑种子，后者首页已经有「继续观看」了。
  val mixViewModel: SimilarRecommendationViewModel = viewModel(
    factory = SimilarRecommendationViewModel.factory(
      context.applicationContext as android.app.Application,
    ),
  )
  val mixItems by mixViewModel.mix.collectAsState()

  val server by viewModel.currentServer.collectAsState()
  val libraries by viewModel.libraries.collectAsState()
  val resumeItems by viewModel.resumeItems.collectAsState()
  val latestItems by viewModel.latestItems.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()

  // 顶栏「更多」菜单（直播电视 / 播放列表 / 猜你喜欢 / 合集）
  var moreMenuExpanded by remember { mutableStateOf(false) }

  // ── 下拉刷新 ──
  // 首页是「媒体库 + 继续观看 + 最新加入」三块的组合，服务端上新了片子不会自己冒出来，
  // 所以要给用户一个手动重拉的入口。刷新期间转圈一直转到这批请求真的回来（refreshHomeAndWait），
  // 否则手指一松动画就没了，看不出到底刷没刷。
  val isRefreshing = remember { mutableStateOf(false) }
  // 只有列表停在顶部时才允许下拉刷新：滚到中间往下拉，列表自己会往上滚，不该触发刷新
  val homeListState = rememberLazyListState()

  // 长按媒体库卡片 → 通知服务器扫描该库（异步任务，只发指令不等结果）
  val scanScope = rememberCoroutineScope()
  // 长按选中的媒体库：非空时弹操作框，用户点「扫描媒体库」后才真的发请求
  var scanTarget by remember { mutableStateOf<EmbyItem?>(null) }
  /** 长按位置（root 坐标）：菜单锚在手指旁边展开 */
  var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

  // 长按「继续观看」卡片 → 弹确认框，确认后从服务器与本机列表一起移除
  var resumeTarget by remember { mutableStateOf<EmbyItem?>(null) }

  // ── 媒体库的置顶 / 顺序号 ──
  // 两条偏好都按服务器分开存，切换服务器时各用各的；用 serverId（没服务器时取 0）
  // 当 remember 的键，切服务器会重新取到那个服务器自己的偏好。
  val browserPreferences = koinInject<BrowserPreferences>()
  val serverId = server?.id ?: 0L

  // 「随便看看」区块的显示开关与条数。默认值见 BrowserPreferences。
  // 关掉时下面那次推荐请求也一起停（LaunchedEffect 的 key 里带了 showHomeMix）。
  val showHomeMix by browserPreferences.embyHomeShowMix.collectAsState()
  val homeMixCount by browserPreferences.embyHomeMixCount.collectAsState()

  val pinsPreference = remember(serverId) { browserPreferences.embyLibraryPins(serverId) }
  val numbersPreference = remember(serverId) { browserPreferences.embyLibraryNumbers(serverId) }
  val hiddenPreference = remember(serverId) { browserPreferences.embyHiddenLibraries(serverId) }
  val pinsRaw by pinsPreference.collectAsState()
  val numbersRaw by numbersPreference.collectAsState()
  val hiddenRaw by hiddenPreference.collectAsState()
  val pins = remember(pinsRaw) { LibraryOrdering.parsePins(pinsRaw) }
  val numbers = remember(numbersRaw) { LibraryOrdering.parseNumbers(numbersRaw) }
  // 「隐藏」与置顶用同一种序列化（逗号分隔的库 Id）—— 两者本来就是同一形状的数据
  val hiddenIds = remember(hiddenRaw) { LibraryOrdering.parsePins(hiddenRaw).toSet() }
  // 显示顺序 = 置顶优先，其次序号（规则见 LibraryOrdering 的类注释）
  val orderedLibraries = remember(libraries, pins, numbers) {
    LibraryOrdering.sortLibraries(libraries, pins, numbers)
  }
  // 首页那一眼真正要摆出来的：全部有序库 **减去**被隐藏的。
  // ⚠️ 隐藏只是**首页这一处**的过滤，「调整顺序号」弹窗和长按菜单仍传 orderedLibraries
  //   （全部）—— 否则被隐藏的库就再也没法取消隐藏了。
  val visibleLibraries = remember(orderedLibraries, hiddenIds) {
    orderedLibraries.filterNot { it.Id != null && it.Id in hiddenIds }
  }
  var orderDialogShown by remember { mutableStateOf(false) }
  // 长按顶栏 Emby 图标弹出的「媒体库显示 / 隐藏」小菜单
  var visibilityMenuExpanded by remember { mutableStateOf(false) }

  // 新出现的媒体库（服务端新加了库 / 换了服务器）补一个序号，落在队尾。
  // 只在真的缺号时才写盘，避免每次重组都往偏好里塞一遍同样的值。
  LaunchedEffect(libraries, serverId) {
    if (serverId == 0L || libraries.isEmpty()) return@LaunchedEffect
    val current = LibraryOrdering.parseNumbers(numbersPreference.get())
    val updated = LibraryOrdering.ensureNumbers(libraries.mapNotNull { it.Id }, current)
    if (updated != current) {
      numbersPreference.set(LibraryOrdering.serializeNumbers(updated))
    }
  }

  // 拉「随便看看」那一批。
  // 只跟服务器 id 走：下拉刷新不再重复拉这一批（首页本来就有三个区块在刷），
  // 想换内容点区块标题上的「换一批」即可。
  //
  // ⚠️ `showHomeMix` 进 key：用户在设置里关掉这个开关后，这里要**立刻停掉请求**，
  // 不是「下次进首页才生效」。所以开关是 LaunchedEffect 的 key 之一，
  // 关掉时 effect 重跑并在第一行就 return。
  //
  // ⭐ 排在首页三请求**之后**（2026-10-15）：这一行横滑在页面最底部，用户得滚好久
  // 才看得见，没道理跟「媒体库 / 继续观看 / 最新加入」抢服务端。十万级条目的库上
  // 每一发都可能是数百毫秒到数秒，四发同时打过去只会让首屏更晚出来
  // （详见 EmbyViewModel.loadHome / awaitHomeLoaded 的说明）。
  LaunchedEffect(serverId, showHomeMix) {
    if (!showHomeMix) return@LaunchedEffect
    val current = server ?: return@LaunchedEffect
    if (serverId == 0L) return@LaunchedEffect
    viewModel.awaitHomeLoaded()
    mixViewModel.loadMix(current, seed = null, limit = homeMixCount)
  }

  // ── 全库搜索 ──
  // 不带 ParentId，Emby 会跨所有媒体库检索，所以这里搜的是「全部媒体」而不是某个库。
  var searchActive by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var searchResults by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var isSearching by remember { mutableStateOf(false) }
  /**
   * 结果是否被 [EmbyViewModel.GLOBAL_SEARCH_LIMIT] 截断（清单 1.1）。
   * 全库搜索是「人物 + 媒体」两条流拼的，混合列表没法真分页，
   * 所以只能靠明确提示让用户知道还有更多、可以收窄条件。
   */
  var searchTruncated by remember { mutableStateOf(false) }
  // 类型筛选：空集合 = 「全部」= 不加类型限制
  var searchFilters by remember { mutableStateOf(emptySet<EmbySearchFilter>()) }
  // 结果分类页签（清单 4.1）。**纯客户端切片**，与上面的 searchFilters 不同：
  // 它决定「已拿到的结果里先看哪一堆」，切一下不发请求（详见 EmbySearchTab 的 KDoc）。
  // 用 rememberSaveable：旋转屏幕时结果集靠 ViewModel 保住，页签也得跟着保住，
  // 否则转个屏就退回「全部」，用户会以为搜索结果被重置了。
  var searchTab by rememberSaveable { mutableStateOf(EmbySearchTab.ALL) }

  // 搜索历史：与媒体库内搜索共用同一张表，在哪儿搜过的词换个地方也能点到
  val searchHistoryRepository = koinInject<SearchHistoryRepository>()
  val searchHistoryFlow = remember { searchHistoryRepository.observe() }
  val searchHistory by searchHistoryFlow.collectAsState(initial = emptyList())
  val historyScope = rememberCoroutineScope()
  val keyboardController = LocalSoftwareKeyboardController.current

  // 搜索态下系统返回（含边缘滑动）**先退搜索**，而不是直接退掉首页。
  // 返回键对应「上一层」，而搜索是叠在首页之上的一层；直接出栈不符合预期。
  // enabled = searchActive ⇒ 非搜索态完全不拦截，交给系统正常出栈。
  BackHandler(enabled = searchActive) {
    searchActive = false
    searchQuery = ""
    searchResults = emptyList()
    searchTruncated = false
    isSearching = false
    // 页签也要复位：下次进搜索时用户要的默认视图就是「全部」，
    // 留着上一次的「电影」页签会让第一条结果直接消失，看着像搜索坏了。
    searchTab = EmbySearchTab.ALL
    keyboardController?.hide()
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // 长按「继续观看」卡片弹出的确认框：确认后清掉服务器记录的播放位置，
    // 并把这条从本机列表摘掉（乐观移除，不等服务端回读）
    val removingResume = resumeTarget
    if (removingResume != null) {
      AlertDialog(
        onDismissRequest = { resumeTarget = null },
        title = { Text("从继续观看中移除？") },
        text = {
          Text("「${viewModel.displayTitle(removingResume)}」的播放进度会从服务器清除，之后不再出现在「继续观看」里。")
        },
        confirmButton = {
          TextButton(
            onClick = {
              val target = removingResume
              val current = server
              val id = target.Id
              resumeTarget = null
              if (current == null || id == null) return@TextButton
              // 乐观移除：先让用户看到它消失，服务器清位置在后台跑
              viewModel.dropResumeItem(id)
              scanScope.launch {
                val ok = viewModel.removeFromResume(current, id)
                Toast.makeText(
                  context,
                  if (ok) "已从继续观看移除" else "服务器未接受这次移除，稍后可再试",
                  Toast.LENGTH_SHORT,
                ).show()
              }
            },
          ) { Text("移除") }
        },
        dismissButton = {
          TextButton(onClick = { resumeTarget = null }) { Text("取消") }
        },
      )
    }

    // 长按媒体库卡片弹出的操作框（扫描媒体库 / 刷新元数据）
    //
    // ⚠️ 这个锚定 Box **必须放在 Column 的第一个子元素**：它包的是 DropdownMenu
    // （Popup，不占布局空间），位置只取决于它在 Column 里排第几。放在最后的话，
    // 前面的列表 fillMaxSize 已经占满整屏，留给它的空间是 0，锚点就落到屏幕底部 ——
    // 表现就是「菜单跑到界面底部，而不是手指旁边」。
    val target = scanTarget
    if (target != null) {
      Box(
        modifier = Modifier.offset {
          androidx.compose.ui.unit.IntOffset(menuAnchor.x.roundToInt(), menuAnchor.y.roundToInt())
        },
      ) {
        val targetId = target.Id
        val targetName = target.Name ?: "媒体库"
        val targetPinned = targetId != null && targetId in pins
        EmbyItemActionsDialog(
          name = targetName,
          kindLabel = "媒体库",
          onDismissRequest = { scanTarget = null },
          // 置顶 / 取消置顶合并成一项，文案跟着当前状态走
          isPinned = targetPinned,
          onTogglePin = {
            val id = targetId
            scanTarget = null
            if (id == null) return@EmbyItemActionsDialog
            if (targetPinned) {
              pinsPreference.set(LibraryOrdering.serializePins(LibraryOrdering.unpin(pins, id)))
              Toast.makeText(context, "已取消置顶「$targetName」", Toast.LENGTH_SHORT).show()
            } else {
              // 名额满了会挤掉最早置顶的那个 —— 提示里把被挤走的名字说出来，
              // 否则用户看到的只是「另一个库突然不置顶了」
              val (nextPins, evicted) = LibraryOrdering.pin(pins, id)
              pinsPreference.set(LibraryOrdering.serializePins(nextPins))
              val evictedName = evicted?.let { eid -> libraries.firstOrNull { it.Id == eid }?.Name }
              Toast.makeText(
                context,
                if (evictedName != null) {
                  "已置顶「$targetName」，同时取消「$evictedName」的置顶" +
                    "（最多置顶 ${LibraryOrdering.MAX_PINNED} 个）"
                } else {
                  "已置顶「$targetName」"
                },
                Toast.LENGTH_SHORT,
              ).show()
            }
          },
          onAdjustOrder = {
            scanTarget = null
            orderDialogShown = true
          },
          onScan = {
            scanTarget = null
            runEmbyLibraryAction(
              context = context,
              scope = scanScope,
              server = server,
              itemId = targetId,
              name = targetName,
              okMessage = "已通知服务器扫描「$targetName」，稍后下拉刷新查看新文件",
              action = { s, id -> runCatching { viewModel.scanLibrary(s, id) } },
            )
          },
          onRefreshMetadata = { replaceMetadata, replaceImages ->
            scanTarget = null
            runEmbyLibraryAction(
              context = context,
              scope = scanScope,
              server = server,
              itemId = targetId,
              name = targetName,
              okMessage = "已通知服务器刷新「$targetName」的元数据，稍后下拉刷新查看",
              action = { s, id ->
                runCatching { viewModel.refreshLibraryMetadata(s, id, replaceMetadata, replaceImages) }
              },
            )
          },
        )
      }
    }

    // 调整顺序号：一屏管全部媒体库的先后。
    // 列表传的是**已排好序**的 orderedLibraries —— 用户看到的就是首页那一眼的顺序，
    // 点 ＋/− 后的变化也能立刻对上号。
    if (orderDialogShown) {
      EmbyLibraryOrderDialog(
        libraries = orderedLibraries,
        pinnedIds = pins,
        numbers = numbers,
        onNumberChange = { id, newNumber ->
          numbersPreference.set(
            LibraryOrdering.serializeNumbers(LibraryOrdering.assignNumber(numbers, id, newNumber)),
          )
        },
        onDismissRequest = { orderDialogShown = false },
      )
    }
    TopAppBar(
      title = {
        Column {
          Text(
            text = server?.name ?: "Emby",
            style = MaterialTheme.typography.titleLarge,
            // ⚠️ 服务器名是用户自己起的，长度不可控（默认还带「未登录」第二行）。
            //   TopAppBar 的 actions 会挤占标题宽度，名字长就该**省略**而不是
            //   硬撑把顶栏撑开 —— 单行 + Ellipsis 才不会把按钮挤出可视区。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (server != null && server?.isLoggedIn != true) {
            Text(
              text = "未登录",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              maxLines = 1,
            )
          }
        }
      },
      actions = {
        // 全库搜索：跨所有媒体库检索，结果替换首页内容
        // 图标跟着搜索态走（放大镜 ↔ 放大镜加斜杠），否则进了搜索界面就没有明确的退出入口。
        // 用 SearchOff 而不是通用的 ✕：旁边就是别的工具栏动作，✕ 太容易被读成「关掉这一页」。
        IconButton(onClick = {
          searchActive = !searchActive
          if (!searchActive) {
            searchQuery = ""
            searchResults = emptyList()
            searchTruncated = false
            isSearching = false
          }
        }) {
          Icon(
            imageVector = if (searchActive) Icons.Default.SearchOff else Icons.Default.Search,
            contentDescription = if (searchActive) "退出搜索" else "搜索全部媒体库",
          )
        }
        // ⚠️ **这里原来还有一个 `Icons.Default.Dns`「切换服务器」按钮，已删**。
        //   切服务器在服务器管理页里本来就能做，而顶栏每个图标都在挤压标题里的
        //   **服务器名称**（用户反馈「服务器名称都放不下了」）⇒ 不如把这个位置还给标题。
        //   需要切换时：Emby 维护 → 服务器管理。
        // 下载管理：有任务在跑时角标显示数量。
        // 高频（用户在下载页和首页之间来回切），所以留在顶栏不进「更多」。
        EmbyDownloadEntryButton()
        // Emby 维护：用官方 Emby 图标（品牌绿），与下方齿轮明确区分。
        // 同理常驻顶栏 —— 服务器掉线、认证过期、任务异常都得从这里处理。
        //
        // **长按**这个图标弹「媒体库显示」小菜单（勾选决定首页媒体库横滑里显示哪些）。
        // 复用长按媒体库卡片那套 `DropdownMenu` 版式，两个长按菜单看起来是同一家族。
        // 单击照旧进服务器维护 —— 长按是额外的一层，不改动原有的单击语义。
        Box {
          EmbyMaintainButton(
            onClick = onManageServers,
            onLongClick = { visibilityMenuExpanded = true },
          )
          // 列的是**全部**已排序库（不是 visibleLibraries）：被隐藏的那几个也得
          // 出现在菜单里，否则用户没法把它们勾回来。
          EmbyLibraryVisibilityMenu(
            expanded = visibilityMenuExpanded,
            libraries = orderedLibraries,
            hiddenIds = hiddenIds,
            onToggle = { id ->
              val next = if (id in hiddenIds) hiddenIds - id else hiddenIds + id
              hiddenPreference.set(LibraryOrdering.serializePins(next.toList()))
            },
            // 全部勾选 = 一个都不隐藏（写空串，回到默认）
            onSelectAll = { hiddenPreference.set("") },
            // 全部取消 = 全部隐藏
            onDeselectAll = {
              hiddenPreference.set(
                LibraryOrdering.serializePins(libraries.mapNotNull { it.Id }),
              )
            },
            onDismissRequest = { visibilityMenuExpanded = false },
          )
        }
        // ── 更多（清单：顶栏精简）────────────────────────────────────
        // 之前这里平铺了 6 个功能按钮（下载 / 离线 / 播放列表 / 猜你喜欢 / 合集 / Emby 维护），
        // 挤到连「设置」都差点被顶出可视区域。低频的收成一个 ⋮ 菜单，
        // 高频的（下载 / 维护 / 搜索 / 服务器 / 设置）仍然常驻。
        //
        // ⚠️ **顶栏已经 5 个 actions（搜索 / 下载 / Emby 维护 / ⋮ / 设置），
        //   不要再往里加**：每个图标都在挤压标题里的**服务器名称**，
        //   名字稍长就显示不全（用户反馈「服务器名称都放不下了」）。
        //   低频功能一律进这个 ⋮ 菜单，或另找内容区的位置。
        Box {
          IconButton(onClick = { moreMenuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多功能")
          }
          DropdownMenu(
            expanded = moreMenuExpanded,
            onDismissRequest = { moreMenuExpanded = false },
          ) {
            // 直播电视排**第一位**：它是最找不到的功能（频道不出现在 /Items，
            // 媒体库横向卡片扫不到），而下面几个用户一眼能看到。
            DropdownMenuItem(
              text = { Text("直播电视") },
              leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null) },
              onClick = {
                moreMenuExpanded = false
                onOpenLiveTv()
              },
            )
            DropdownMenuItem(
              text = { Text("播放列表") },
              leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
              },
              onClick = {
                moreMenuExpanded = false
                onOpenPlaylists()
              },
            )
            DropdownMenuItem(
              text = { Text("猜你喜欢 / 连播") },
              leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
              onClick = {
                moreMenuExpanded = false
                onOpenDiscover()
              },
            )
            DropdownMenuItem(
              text = { Text("合集") },
              leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
              onClick = {
                moreMenuExpanded = false
                onOpenCollections()
              },
            )
            }
        }
        // 设置：放在最后且永不折叠 —— 用户要的就是「设置按钮别再被挤没」。
        // 播放器 / 外观 / 解码等偏好都在这里。
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Default.Settings, contentDescription = "设置")
        }
      },
    )

    // 搜索框：只在点开搜索后出现
    if (searchActive) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("搜索全部媒体库…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          // 键盘上按「搜索」= 用户明确表态「我就搜这个词」，直接落一条历史并立刻出结果
          onSearch = {
            keyboardController?.hide()
            historyScope.launch { searchHistoryRepository.record(searchQuery) }
          },
        ),
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Clear, contentDescription = "清空")
            }
          }
        },
      )
      // 类型筛选：默认「全部」不加限制；勾了电影/合集/演员这类就按类型查
      EmbySearchFilterRow(
        selected = searchFilters,
        onSelectedChange = { searchFilters = it },
      )
    }

    // 防抖 400ms，避免每敲一个字就发一次请求
    LaunchedEffect(searchQuery, searchActive, searchFilters) {
      if (!searchActive || searchQuery.isBlank()) {
        searchResults = emptyList()
        searchTruncated = false
        isSearching = false
        return@LaunchedEffect
      }
      isSearching = true
      // 换词 / 改筛选 ⇒ 回到「全部」页签。这不是「顺手」而是必需的：
      // 新结果集里很可能没有上一轮选中的那个类型（比如上轮选「电影」、这轮只搜到剧集），
      // 页签不会自己消失（见 EmbySearchTab.shouldShowBadge 会保留选中项），
      // 于是用户会停在一个永远空白的页签上，看起来像搜索坏了。
      searchTab = EmbySearchTab.ALL
      kotlinx.coroutines.delay(400)
      val current = server
      if (current == null) {
        isSearching = false
        return@LaunchedEffect
      }
      val finishedQuery = searchQuery
      val result = viewModel.searchGlobal(
        server = current,
        term = finishedQuery,
        itemTypes = EmbySearchFilter.toItemTypes(searchFilters),
      )
      searchResults = result.items
      searchTruncated = result.truncated
      // 有结果才记历史：一个字都没命中的多半是打字打岔了，记下来只会污染列表
      if (result.items.isNotEmpty()) {
        searchHistoryRepository.record(finishedQuery)
      }
      isSearching = false
    }

    // 页签切片结果。remember 包住是因为 grid 会把整个列表传进 items()，
    // 每帧重新 filter 一遍 120 条是白给的开销。key 带上 searchTab —— 切页签必须重算。
    val visibleSearchResults = remember(searchResults, searchTab) {
      EmbySearchTab.itemsFor(searchResults, searchTab)
    }
    // 角标统计只依赖原始结果，跟当前选中哪个页签无关
    val searchTabCounts = remember(searchResults) { EmbySearchTab.countByTab(searchResults) }

    when {
      // 全库搜索：结果替换首页内容
      searchActive -> {
        if (searchQuery.isBlank()) {
          SearchHistoryPanel(
            history = searchHistory,
            onPick = { keyword -> searchQuery = keyword },
            onRemove = { keyword -> historyScope.launch { searchHistoryRepository.remove(keyword) } },
            onClearAll = { historyScope.launch { searchHistoryRepository.clear() } },
            modifier = Modifier.fillMaxSize(),
            emptyHint = "输入关键词，搜索全部媒体库",
          )
        } else if (isSearching) {
          // 搜索期间用骨架屏而不是居中转圈：结果卡片的版式先摆出来，落地时不会整屏跳
          EmbySkeletonGrid(columns = 3, ratio = 3f / 4f, itemCount = 9)
        } else if (searchResults.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "没有找到「$searchQuery」相关媒体",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            // 分类页签（清单 4.1）。放在结果网格**上面**而不是吸顶：搜索结果通常一屏到两屏就到底，
            // 真滚很久的场合下面还有上一排筛选 chip 顶着，页签不会被顶出视野。
            EmbySearchTabRow(
              counts = searchTabCounts,
              total = searchResults.size,
              selected = searchTab,
              onSelectedChange = { searchTab = it },
            )
            if (visibleSearchResults.isEmpty()) {
              // 正常走不到这里（切页签时 0 条的页签不给选，换词时页签会复位到「全部」），
              // 留着是防「结果集变了但选中态没跟上」时给用户一句人话，而不是一片空白。
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                  text = "「${searchTab.label}」下没有匹配的结果",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else {
              androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(
                  items = visibleSearchResults,
                  key = { it.Id ?: it.Name ?: "" },
                ) { item ->
                  EmbyPosterCard(
                    title = item.Name ?: "",
                    subtitle = item.ProductionYear?.toString(),
                    imageUrl = server?.let { s -> viewModel.imageUrl(s, item, "Primary", 300) },
                    progress = null,
                    isFavorite = item.UserData?.IsFavorite == true,
                    onClick = { onOpenDetail(item) },
                  )
                }
                // 结果被上限截断时明确告知（清单 1.1）：全库搜索是「人物 + 媒体」两条流
                // 拼的，混合列表没法真正分页，所以这里靠提示让用户自己收窄条件。
                // 只在「全部」页签下提示 —— 截断是**整体**现象，
                // 停在「电影」页签时报「只显示前 N 条」会让人误以为电影只有 N 部。
                if (searchTruncated && searchTab == EmbySearchTab.ALL) {
                  item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                      text = "只显示前 ${searchResults.size} 条，试试更精确的关键词或勾选类型筛选",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // 加载尚未结束时不判定「没有服务器」，避免冷启动（服务器还在异步恢复）闪一下空状态
      server == null && !isLoading -> EmbyEmptyState(
        message = "还没有添加 Emby 服务器",
        buttonText = "添加服务器",
        onAction = onManageServers,
      )

      error != null && libraries.isEmpty() -> EmbyEmptyState(
        message = error ?: "加载失败",
        buttonText = "重试",
        onAction = { viewModel.refreshHome() },
        // 连不上服务器 / 请求失败 ⇒ 给 Oops 指示牌（「这里坏了」而不是「这里没东西」）
        isError = true,
      )

      isLoading && libraries.isEmpty() -> EmbySkeletonHome()

      else -> {
        PullRefreshBox(
          isRefreshing = isRefreshing,
          onRefresh = { viewModel.refreshHomeAndWait() },
          modifier = Modifier.fillMaxSize(),
          // 搜索态首页列表是空的，此时下拉刷新没有意义（下拉要重跑的是搜索，不是首页数据）
          enabled = !searchActive,
          listState = homeListState,
        ) {
          LazyColumn(
            state = homeListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            // ⚠️ 用 visibleLibraries 而不是 libraries：被长按菜单隐藏掉的库
            //   连「媒体库」这个区块标题一起收掉（全隐藏时整块消失是预期行为）。
            if (visibleLibraries.isNotEmpty()) {
              item {
                EmbySectionHeader(
                  title = "媒体库",
                  modifier = Modifier.padding(top = 10.dp),
                )
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(visibleLibraries, key = { it.Id ?: it.Name ?: "" }) { library ->
                    val libraryName = library.Name ?: "媒体库"
                    EmbyLibraryCard(
                      name = libraryName,
                      itemCount = library.ChildCount,
                      imageUrl = viewModel.imageUrl(server!!, library, "Primary", 300),
                      icon = libraryIcon(library.CollectionType),
                      // 置顶的在卡片右上角钉一枚小图钉，用户一眼能看出哪两个被钉住了
                      pinned = library.Id != null && library.Id in pins,
                      onClick = { onOpenLibrary(library) },
                      // 长按 = 弹出操作框（扫描媒体库等）。扫描是作用在服务器上的异步任务、
                      // 发出去撤不回来，所以不直接执行，先让用户确认
                      onLongClick = { offset -> scanTarget = library; menuAnchor = offset },
                    )
                  }
                }
              }
            }

            if (resumeItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = "继续观看")
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(resumeItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    EmbyWideCard(
                      title = viewModel.displayTitle(item),
                      subtitle = item.SeriesName ?: item.ProductionYear?.toString(),
                      imageUrl = viewModel.imageUrl(server!!, item, "Backdrop", 640),
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Primary", 640),
                      progress = playbackProgress(item),
                      remainingText = remainingLabel(item),
                      // 单击是进详情页而非直接播放，去掉居中的播放三角，避免误导
                      showPlayButton = false,
                      isFavorite = item.UserData?.IsFavorite == true,
                      // 单击进入详情页，播放由详情页发起
                      onClick = { onOpenDetail(item) },
                      // 长按 → 从「继续观看」移除（清服务器播放位置 + 本机列表摘掉）
                      onLongClick = { resumeTarget = item },
                    )
                  }
                }
              }
            }

            if (latestItems.isNotEmpty()) {
              item {
                EmbySectionHeader(title = "最新加入")
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(latestItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    EmbyPosterCard(
                      title = viewModel.displayTitle(item),
                      subtitle = item.ProductionYear?.toString(),
                      imageUrl = viewModel.imageUrl(server!!, item, "Primary", 480),
                      // 单集等条目可能没有 Primary（海报），回退到 Thumb（剧照/缩略图）
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Thumb", 480),
                      progress = playbackProgress(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      // 单击进入详情页，播放由详情页发起
                      onClick = { onOpenDetail(item) },
                    )
                  }
                }
              }
            }

            // 「随便看看」：即混的一批，不占独立页也能有点击。
            // 放在「最新加入」下面 —— 那块是「刚出的新片」，这块是「点开就有惊喜」，
            // 语义不重复。整块只给一行横滑，点卡片进详情，点标题右侧按钮换一批。
            //
            // `showHomeMix &&` 是设置里那个开关：关掉时整块消失（请求也已经停了）。
            if (showHomeMix && mixItems.isNotEmpty()) {
              item {
                EmbySectionHeader(
                  title = "随便看看",
                  action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      TextButton(onClick = {
                        val current = server ?: return@TextButton
                        // 换一批沿用当前条数设置，不然用户设了 12 条、
                        // 点一下「换一批」突然变成 100 条，视觉上像是坏了
                        mixViewModel.loadMix(current, seed = null, limit = homeMixCount)
                      }) { Text("换一批") }
                      TextButton(onClick = onOpenDiscover) { Text("更多") }
                    }
                  },
                )
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(mixItems, key = { it.Id ?: it.hashCode().toString() }) { item ->
                    EmbyPosterCard(
                      title = viewModel.displayTitle(item),
                      subtitle = item.ProductionYear?.toString() ?: item.Type,
                      imageUrl = viewModel.imageUrl(server!!, item, "Primary", 480),
                      fallbackImageUrl = viewModel.imageUrl(server!!, item, "Thumb", 480),
                      progress = playbackProgress(item),
                      isFavorite = item.UserData?.IsFavorite == true,
                      onClick = { onOpenDetail(item) },
                    )
                  }
                }
              }
            }

            if (libraries.isEmpty() && resumeItems.isEmpty() && latestItems.isEmpty() && !isLoading) {
              item {
                EmbyEmptyState(
                  message = "这个服务器上还没有可显示的媒体",
                  buttonText = "刷新",
                  onAction = { viewModel.refreshHome() },
                  modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                )
              }
            }
        }
        }
      }
    }

  }
}

// playbackProgress / remainingLabel 已抽到同包 EmbyItemProgress.kt（首页与媒体库共用一份）。

/** 按媒体库类型挑选图标 */
private fun libraryIcon(collectionType: String?): ImageVector = when (collectionType) {
  "tvshows" -> Icons.Default.Tv
  else -> Icons.Default.PhotoLibrary
}

/**
 * 空态 / 错误态的通用占位（全 Emby 模块共用）。
 *
 * @param isError **true = 这是出错了**（连不上服务器、加载失败），不是「本来就没内容」。
 *   这两种情况外观必须分开：出错给 [OopsErrorAnimation] 动态指示牌，
 *   一眼就知道「这里坏了、可以重试」；单纯的空态仍然只给一个静态图标 ——
 *   给空态也挂 Oops 是在骗用户「你遇到问题了」。
 */
@Composable
fun EmbyEmptyState(
  message: String,
  buttonText: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (isError) {
      OopsErrorAnimation(
        modifier = Modifier.size(width = 128.dp, height = 132.dp),
        caption = "哎呀，出了点小问题",
      )
    } else {
      Icon(
        imageVector = Icons.Default.Dns,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = message,
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onAction) {
      Text(buttonText)
    }
  }
}

/** 列表里的错误横幅（有内容但某一页失败了）。同样给 Oops 指示牌，只是小一号。 */
@Composable
fun EmbyErrorBanner(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    OopsErrorAnimation(
      modifier = Modifier
        .size(width = 112.dp, height = 116.dp)
        .padding(bottom = 8.dp),
      caption = "哎呀，出了点小问题",
    )
    Text(
      text = message,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    TextButton(onClick = onRetry) {
      Text("重试")
    }
  }
}

/** 把 tick 时长转成「1小时23分」这类中文可读文本 */
fun formatDuration(ticks: Long?): String {
  val totalMinutes = EmbyTicks.ticksToSeconds(ticks ?: 0) / 60
  if (totalMinutes <= 0) return ""
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
