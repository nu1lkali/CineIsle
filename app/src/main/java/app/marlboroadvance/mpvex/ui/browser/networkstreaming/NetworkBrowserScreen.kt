package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.FolderViewMode
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.components.NetworkSearchField
import app.marlboroadvance.mpvex.ui.browser.states.BrowserSkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.states.BrowserSkeletonRows
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkFolderCard
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkMediaCard
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkOtherFileRow
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkPhotoRow
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkVideoCard
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.emby.components.VideoFeedIcon
import app.marlboroadvance.mpvex.ui.browser.states.EmptyState
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.download.NetworkDownloadBarHost
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.download.NetworkDownloadCenter
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.DirSizeUiState
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.NetworkFilePropertiesOverlay
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.NetworkUploadCenter
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkToolboxSheet
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.NetworkUploadSheet
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LazyGridSideScrollbar
import app.marlboroadvance.mpvex.ui.utils.LazyListSideScrollbar
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.ScrollToTopOnOrderChange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * 网络共享目录浏览页（SMB / WebDAV / FTP 二级页面）。
 *
 * 与旧版「只有文件夹 + 视频两个 section」的差异（混合内容改造）：
 * - **四分法**：文件夹 / 图片 / 视频 / 其他（字幕、nfo）。SMB、WebDAV 目录
 *   经常是混合内容，旧版按 mimeType 过滤直接把照片全滤掉了。
 * - **网格视图**（默认）：统一卡型 [NetworkMediaCard]，文件夹在前、
 *   照片与视频混合按名称排；右上角类型小角标。OTHER 在网格里不渲染。
 * - **列表视图**：分 section（文件夹 / 图片 / 视频 / 其他），
 *   照片走 [NetworkPhotoRow]，其他文件走 [NetworkOtherFileRow]。
 * - **视图模式与本地文件夹浏览共用同一偏好**（[FolderViewMode]）：
 *   用户在「本地」里选的网格/列表，这里跟着变，不用学两套。
 * - **文件夹封面**：文件夹卡用目录内第一张图片作封面，懒加载 + 两级缓存
 *   （VM 本页 Map → Repository 内存 LRU → Room 持久表），可在设置里关闭。
 * - **排序**（内容区控制条）：名称 / 时间 / 大小 / 类型 / 随机多维度，
 *   一个 chip（图标 + 维度名 + ↑/↓）点开下拉菜单换维度，范式对齐 Emby 媒体库
 *   （[SortMenu]）：选中项标签 append 方向 + 主色高亮、不单列方向选项、
 *   再点一次已选中项 = 只翻方向；切维度走自然方向。
 * - **类型筛选**（内容区控制条）：单个「筛选」按钮点开小窗勾选
 *   （显示文件夹 / 显示图片 / 显示媒体，「显示全部」= 三项全开的派生态），
 *   多选不关窗；有筛选生效时按钮主色高亮。字幕 / nfo 等其他文件只在列表视图出现，
 *   不受这三项管。
 * - **本地搜索**（顶栏放大镜）：对当前目录已拉到的内容按名称过滤，零请求。
 * - **顶栏标题 = 当前文件夹名**（根目录退化为连接名），过长自动截断；
 *   标题用**小两档字号（20sp，`compactTitle`）**—— 标题常年是长文件夹名、
 *   右侧又挂着几个图标，24sp 会把它们挤在一起。
 * - **点标题 → 「跳转到」导航弹窗**（[PathNavigatorSheet]）：列出 根目录 → 当前目录
 *   的全部层级，点任意一层直达；当前层打勾。**点标题不再切换深浅色**（该页让位给导航）。
 * - **顶栏「更多」菜单**（[BrowserTopBar.additionalActions]）：回到首页（一键回网络模块首页）、
 *   收藏当前目录（切完弹 toast）、随机播放（视频 / 图片二选一）、**视界流播放**（仿抖音竖屏
 *   连播）、**穿透子文件夹**（勾选项，作用于随机播放视频、视界流、随机播放图片三项）、
 *   视图切换，全部收在竖三点里
 *   —— 顶栏常驻图标只有「更多 / 搜索 / 设置」，宽度留给文件夹标题。
 * - **视频点击即播**：不起详情页，直接走 mpv 队列播放（[NetworkBrowserViewModel.playVideos]）。
 * - **照片点击进查看器**：[NetworkPhotoViewerScreen]（缩放 / 保存 / 幻灯片）。
 */
@Serializable
data class NetworkBrowserScreen(
  val connectionId: Long,
  val connectionName: String,
  val currentPath: String = "/",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    // 穿透模式抽图片要逐层列目录（异步），菜单点击只是发起点
    val scope = rememberCoroutineScope()

    val viewModel: NetworkBrowserViewModel =
      viewModel(
        key = "NetworkBrowser_${connectionId}_$currentPath",
        factory =
          NetworkBrowserViewModel.factory(
            context.applicationContext as android.app.Application,
            connectionId,
            currentPath,
          ),
      )

    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isCurrentFavorite by viewModel.isCurrentFavorite.collectAsState()
    val folderCovers by viewModel.folderCovers.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    // 「动过排序」的计数（每次有效排序操作 +1，含随机换种子那种值不变的）
    val sortChangeTick by viewModel.sortChangeTick.collectAsState()
    // 当前协议是否支持重命名 / 删除（目前只有 AList）—— 决定卡片挂不挂长按
    val supportsWrite by viewModel.supportsWrite.collectAsState()
    // 整理工具箱的执行状态（挂在 ViewModel 上：抽屉关掉，任务照跑）
    val toolProgress by viewModel.toolProgress.collectAsState()

    // 视图模式：与本地文件夹浏览共用同一偏好（AlbumView=网格 / FileManager=列表）
    val browserPreferences = koinInject<BrowserPreferences>()
    val folderViewMode by browserPreferences.folderViewMode.collectAsState()
    val isGridView = folderViewMode == FolderViewMode.AlbumView
    // 「随机播放视频 / 视界流」是否穿透子文件夹（⋮ 菜单里的勾选项，持久化）
    val drillDown by browserPreferences.networkRandomDrillDown.collectAsState()

    // 文件夹封面开关 + 网格标题行数（设置 → 网络浏览）
    val folderCoverEnabled by browserPreferences.folderCoverEnabled.collectAsState()
    val gridTitleLines by browserPreferences.networkGridTitleLines.collectAsState()
    val titleMaxLines = gridTitleLines.maxLines

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }

    // ⭐ 滚动位置 state 提升到本页顶层持有（网格/列表各建一个，按视图模式选用）。
    //     为什么不放进 Grid/List 内部：保存要挂在「本页组合被销毁」上，恢复要等
    //     条目落位后由本页统一驱动 —— 两头都在这一层最稳，且两个视图能共用一套逻辑。
    //     `rememberLazyGridState()` 内部是 `rememberSaveable`，框架那条保存恢复链
    //     （SaveableStateHolder + 异步 loadFiles）不稳，具体见
    //     [NetworkBrowserViewModel.scrollPositions] 的 KDoc —— 所以这里自己做一份。
    val networkGridState = rememberLazyGridState()
    val networkListState = rememberLazyListState()

    // 保存：本页组合销毁时记下当前位置。
    // 什么时候会销毁：nav3 的 NavDisplay **只组合栈顶 entry** —— 进照片查看器 /
    //    播放器时本页被盖住，组合即被销毁（用户「返回后位置丢了」的根因）。
    // ⚠️ 只在还留在返回栈里时才记：被 pop 意味着「离开这个目录」，下次从零进来
    //    应停在顶部；被盖住才需要「回来还在原位」。用实例身份（===）判，不用
    //    结构相等 —— data class 的 equals 会把同路径的不同 entry 判成相等。
    val selfEntry = this
    DisposableEffect(selfEntry) {
      onDispose {
        if (backstack.any { it === selfEntry }) {
          val index =
            if (isGridView) networkGridState.firstVisibleItemIndex
            else networkListState.firstVisibleItemIndex
          viewModel.saveScrollPosition(connectionId, currentPath, index)
        }
      }
    }

    // 恢复：从查看器/播放器返回时本页是**全新组合**（框架恢复不稳，见上）——
    // 有记录就等条目真正落位后显式滚过去，覆盖框架可能恢复出的任何旧值。
    // ⚠️ 必须等 `totalItemsCount > 0`：空列表上 scrollToItem 会被 clamp 成 0，
    //    等数据到位也回不去（loadFiles 虽不清空旧列表，但首帧布局可能还没出）。
    // ⚠️ `LaunchedEffect(Unit)` 每次全新组合都会跑一次：没有记录时直接返回，
    //    不影响正常进目录 / 下拉刷新 / 旋屏（旋屏后 VM 重建、map 为空，走框架恢复）。
    // ⚠️ 两个 state 没有共同父类带 scrollToItem（LazyGridState/LazyListState 各自独立），
    //    只能按视图模式分开写。
    LaunchedEffect(Unit) {
      val saved = viewModel.getScrollPosition(connectionId, currentPath)
      if (saved <= 0) return@LaunchedEffect
      if (isGridView) {
        snapshotFlow { networkGridState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (networkGridState.firstVisibleItemIndex != saved) {
          networkGridState.scrollToItem(saved)
        }
      } else {
        snapshotFlow { networkListState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (networkListState.firstVisibleItemIndex != saved) {
          networkListState.scrollToItem(saved)
        }
      }
      // 恢复完成即清掉：之后用户再进查看器会重新记，不会拿到过期位置
      viewModel.clearScrollPosition(connectionId, currentPath)
    }

    // 排序一动就回到顶部：重排后若停在原位，用户看到的是「一片不相关的中段条目」，
    // 完全看不出排序结果长什么样。用户明确要求「改完排序就停在第一个项目」。
    // ⚠️ 键是「排序动作计数」而不是排序值 —— 随机换种子时值不变但列表重排了，
    //    那也必须回顶（曾漏掉，反馈）。
    // ⚠️ 回顶**一律**走 [ScrollToTopOnOrderChange]（内部是 requestScrollToItem）：
    //    直接写 `scrollToItem(0)` 会被 LazyGrid/LazyList 的 key 锚定抵掉，画面停在
    //    列表中间 —— 即用户三次反馈的「item(0) 问题」。完整根因与规矩见
    //    `ui/utils/ScrollToTop.kt`。
    //    它同时自带「首次组合不触发」，所以从查看器 / 播放器返回时不会再被弹回顶端
    //    （这就是原先那套「已消费 tick」记账在做的事，现在收进封装里了）。
    ScrollToTopOnOrderChange(
      key = sortChangeTick,
      gridState = networkGridState,
      listState = networkListState,
    )

    // 随机播放下拉菜单（视频 / 图片）
    // 顶栏竖三点「更多」菜单：收藏 / 随机播放 / 视图切换 全收在这里（见下方 additionalActions）
    var showOverflowMenu by remember { mutableStateOf(false) }
    // 长按条目：当前协议支持写操作（AList）时才会有值。
    // actionMenuAnchor = 长按那一刻的**手指坐标（root）**，菜单据此锚定
    // （Emby 媒体库同一手法：气泡菜单出现在手指旁边，而不是屏幕中间的对话框）。
    var actionTarget by remember { mutableStateOf<NetworkFile?>(null) }
    var actionMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var renameTarget by remember { mutableStateOf<NetworkFile?>(null) }
    var deleteTarget by remember { mutableStateOf<NetworkFile?>(null) }

    // 「跳转到」导航弹窗（点顶栏标题弹出）：把 根目录 → 当前目录 的全部层级列出来，
    // 点任意一层直达 —— 多层级目录里不用再一层层按「上一级」。
    // 参考 AlistClientN 的路径导航弹窗（`file_list_screen.dart#_showPathNavigator`），
    // 并修掉它几处边界问题，见 [PathNavigatorSheet] 的 KDoc。
    var showPathNavigator by remember { mutableStateOf(false) }

    // 上传面板（本地文件 / 文件夹 → AList 当前目录）。只有支持写操作的协议给入口。
    var showUpload by remember { mutableStateOf(false) }

    // 新建文件夹的输入框（与「重命名」同一套：输入框 + 确认）
    var showNewFolder by remember { mutableStateOf(false) }

    // 整理工具箱抽屉（按类型归类 / 提取并整理 / 清理空目录 …）。
    // 只有 AList 有写接口，入口跟「上传」「新建文件夹」同一条 supportsWrite 门禁。
    var showToolbox by remember { mutableStateOf(false) }

    // ── 属性面板（右侧抽屉）──
    // null = 收起。属性是**只读**的，所以不设 supportsWrite 门禁 ——
    // SMB / WebDAV / FTP 的用户同样有「这个文件夹多大、它在哪」的疑问。
    var propertyTarget by remember { mutableStateOf<NetworkFile?>(null) }
    // 目录统计状态。⚠️ 换条目时必须清空，否则会把上一个目录的字节数贴到新条目上
    var dirSizeState by remember { mutableStateOf<DirSizeUiState?>(null) }

    // 下载进度（进程级单例：关掉提示条、甚至离开本页，任务照跑）
    val downloadProgress by NetworkDownloadCenter.job.collectAsState()

    // 本地搜索（关键词过滤，纯内存 —— 目录列表本来就是全量一次拉完的）
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 类型筛选（显示文件夹 / 显示图片 / 显示媒体）。「显示全部」= 三项全开，
    // 不单独存状态 —— 由这三项推导（勾上全部 ⇒ 三项全勾；取消任一项 ⇒ 全部自动取消）。
    // 字幕 / nfo 等其他文件只出现在列表视图，不受这三项管。
    var showFolders by remember { mutableStateOf(true) }
    var showPhotos by remember { mutableStateOf(true) }
    var showVideos by remember { mutableStateOf(true) }

    // 友好提示 Toast：复用同一个实例、每次先 cancel，连点不排队
    // （与 Emby 媒体库快捷收藏同一手法）
    val toastRef = remember { mutableStateOf<Toast?>(null) }
    fun toast(msg: String) {
      toastRef.value?.cancel()
      toastRef.value = Toast.makeText(context, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // 当前目录显示名（查看器标题 / 收藏名 / 顶栏标题同一口径）。
    // ⚠️ 必须走 [networkPathDisplayName]：旧写法 `substringAfterLast('/', "")` 会把
    //    单段路径（第一层，如 volume1）判成空串 ⇒ 顶栏退化显示成连接名，
    //    而再进一层就正常 —— 用户报的就是这个。完整推导见那个函数的 KDoc。
    val dirTitle = networkPathDisplayName(currentPath, connectionName)

    // 是否已在**连接根目录**：根目录再往上就是首页，没有层级可跳 ⇒ 不给导航入口。
    // 用 trim('/') 判，兼容 ""、"//" 这些异常值。
    val atConnectionRoot = currentPath.trim('/').isEmpty()

    // 面包屑：根目录 → 当前目录，逐级累积出完整路径（弹窗列表用）。
    // ⚠️ 先 `trim('/')` 把首尾斜杠归一化再拆 —— 否则「/a/b/」这类尾斜杠会拼出
    //    「/a/b/」并因此匹配不上栈里的「/a/b」（AlistClientN 没做这层归一化）。
    val pathLevels = remember(currentPath, connectionName) {
      val segments = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
      buildList {
        add("/" to "根目录")
        var accumulated = ""
        segments.forEach { segment ->
          accumulated = "$accumulated/$segment"
          add(accumulated to segment)
        }
      }
    }

    // ── 双栏（平板 / 折叠屏展开）──
    // 720dp = 240dp 左栏 + 480dp 内容：再窄两边都不好看，直接回落到单栏。
    // 这一条判据只用「宽度」—— 窄屏（手机）无论如何都不开，满足「自动回落」的要求。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val dualPaneCapable = screenWidthDp >= 720
    val dualPanePref by browserPreferences.networkDualPane.collectAsState()
    val siblings by viewModel.siblings.collectAsState()
    val protocolLabel by viewModel.protocolLabel.collectAsState()
    // 根目录没有「上一级」，双栏在那儿只会多一块空壳
    val dualPane = dualPaneCapable && dualPanePref && !atConnectionRoot
    // 左栏的列表是一次**额外**的列目录请求，只有真要用双栏时才发
    LaunchedEffect(dualPane) {
      if (dualPane) viewModel.ensureSiblings()
    }
    // 上一级自己的显示名（左栏栏头那行小字）
    val parentLabel =
      remember(currentPath, connectionName) {
        networkPathDisplayName(currentPath.trim('/').substringBeforeLast('/', ""), connectionName)
      }

    // 跳到某一层（面包屑跳转 = **丢弃「前进历史」**，与文件管理器点面包屑同一语义）。
    fun navigateToPath(target: String) {
      fun normalize(path: String) = path.trimEnd('/').ifEmpty { "/" }
      val normalized = normalize(target)
      if (normalized == normalize(currentPath)) return
      // 层层下钻时每一层都压在栈里 —— 把目标层之上的都弹掉即可。
      // 永远保留栈底（MainScreen）：idx >= 0 且 idx <= size-1，不会把栈弹空。
      val targetIndex =
        backstack.indexOfLast {
          it is NetworkBrowserScreen &&
            it.connectionId == connectionId &&
            normalize(it.currentPath) == normalized
        }
      if (targetIndex >= 0) {
        repeat(backstack.size - 1 - targetIndex) { backstack.removeLastOrNull() }
      } else {
        // 目标层不在栈里（典型：从「收藏目录」一步进了很深的目录，中间层从未压栈）。
        // ⚠️ 这里**只 push、绝不先弹栈**。AlistClientN 的 `_navigateToPath` 先用
        //    `Get.until` 弹栈再判断有没有匹配，路径一旦没匹配上就把整条导航栈弹空了。
        backstack.add(
          NetworkBrowserScreen(
            connectionId = connectionId,
            connectionName = connectionName,
            currentPath = normalized,
          ),
        )
      }
    }

    // 一键回首页（MainScreen = 网络模块首页）。导航栈底永远是 MainScreen，
    // 所以「弹到 size == 1」就是回到首页，与连按返回键等价、只是一步到位。
    fun goHome() {
      while (backstack.size > 1) backstack.removeLastOrNull()
    }

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    // 上传成功 → 重新列一次当前目录。
    // 不这么做的话用户传完关掉面板，列表里什么都没有，会以为没传上去再点一次。
    // ⚠️ 只认本连接的批次：回到首页再进别的连接时，别把别人的完成事件当自己的。
    LaunchedEffect(connectionId) {
      NetworkUploadCenter.completed.collect { uploadedConnectionId ->
        if (uploadedConnectionId == connectionId) viewModel.loadFiles()
      }
    }

    // 返回键：搜索态先退搜索（清词 + 收键盘），再退出页面 —— 与 Emby 媒体库同一手势
    BackHandler {
      if (searchActive) {
        searchActive = false
        searchQuery = ""
      } else {
        backstack.removeLastOrNull()
      }
    }

    // 两级过滤：① 类型（文件夹 / 图片 / 视频，「显示全部」= 三项全开）；
    // ② 搜索词命中名称（大小写不敏感）。空条件 = 全量。
    // remember 带上全部依赖：大目录每次重组都过滤一遍纯浪费，条件不变就直接拿旧结果。
    val visibleFiles = remember(files, showFolders, showPhotos, showVideos, searchQuery) {
      files.filter { file ->
        val typeVisible =
          when {
            file.isDirectory -> showFolders
            file.isPhoto() -> showPhotos
            file.isVideoFile() -> showVideos
            // 字幕 / nfo 等其他文件只出现在列表视图，不受类型筛选影响
            else -> true
          }
        if (!typeVisible) {
          false
        } else if (searchQuery.isBlank()) {
          true
        } else {
          file.name.contains(searchQuery, ignoreCase = true)
        }
      }
    }

    // 封面懒加载触发点：功能开着才传（关了调用方不传 ⇒ 卡片不触发 ⇒ 零额外请求）。
    // VM 侧有缓存 / 在途去重 / 限流，重复调是安全的。
    val coverTrigger: ((String) -> Unit)? =
      if (folderCoverEnabled) viewModel::ensureFolderCover else null

    Scaffold(
      topBar = {
        BrowserTopBar(
          // 顶栏标题 = **当前文件夹名**（根目录退化为连接名）。
          // 以前传的是 connectionName，在服务器里层层往下点时，
          // 左上角永远显示「我的 NAS」这类连接名，看不出当前在哪一层。
          // 名字过长由 BrowserTopBar 的 maxLines=1 + Ellipsis 自动截断。
          title = dirTitle,
          // 标题一直是长文件夹名、右侧又挂着 3~4 个图标 ⇒ 用小两档字号（20sp）留出横向空间。
          compactTitle = true,
          // 点标题 → 打开「跳转到」导航弹窗（列出全部层级，直达任意一层）。
          // 在连接根目录没有可跳的层级，就不接管点击（回落到默认的「切换深浅色」），
          // 也不显示下拉箭头 —— 不给「点了没反应」的死入口。
          onTitleClick = if (atConnectionRoot) null else ({ showPathNavigator = true }),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onBackClick = { backstack.removeLastOrNull() },
          onCancelSelection = {},
          // 排序不走顶栏：它要常驻又能显示当前状态，放在内容区顶部（见 NetworkSortRow）。
          // 顶栏图标只有 更多 / 搜索 / 设置 三个（「回到首页」收进「更多」菜单里）。
          onSortClick = null,
          onSearchClick = { searchActive = true },
          onSettingsClick = {
            backstack.add(PreferencesScreen)
          },
          onDeleteClick = null,
          onRenameClick = null,
          isSingleSelection = false,
          onInfoClick = null,
          onShareClick = null,
          onPlayClick = null,
          onSelectAll = null,
          onInvertSelection = null,
          onDeselectAll = null,
          additionalActions = {
            // ── 顶栏瘦身：三个入口收进一个竖三点菜单 ──
            // 原先是 5 个平铺图标（收藏 + 随机 + 视图 + 顶栏自带的搜索 + 设置），
            // 文件夹标题被挤到只剩一两个字。现在顶栏常驻只剩「更多」一个按钮，
            // 加上搜索与设置共 3 个图标，横向空间基本都还给标题。
            // 这三个都是低频操作（不像搜索那样随时要点），多一下点击换来的
            // 「看得清当前在哪个文件夹」是划算的。
            Box {
              // ⚠️ 强制「吸在屏幕右侧」（用户要求，与首页长按 Emby 图标的
              //    `EmbyLibraryVisibilityMenu` 同一手法，别再"优化"掉）：
              //    给一个必然超宽的 x 偏移（整个屏宽），M3 的前两个横向候选
              //    （贴锚点左缘 / 贴锚点右缘）都会被「放不下」检查否掉，
              //    只剩 third 分支 rightToWindowRight（贴窗口右缘 − 8dp）。
              //    不这么绕的话，菜单右缘会贴在竖三点图标上 —— 图标虽然也在
              //    右侧，但它落在 actions 行的内缩位置，菜单看着是"飘在中间"。
              val pinMenuToRightEdge =
                if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
                  DpOffset(x = LocalConfiguration.current.screenWidthDp.dp, y = 0.dp)
                } else {
                  DpOffset.Zero
                }
              IconButton(onClick = { showOverflowMenu = true }) {
                Icon(
                  imageVector = Icons.Default.MoreVert,
                  contentDescription = "更多操作",
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
              DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false },
                offset = pinMenuToRightEdge,
              ) {
                // ── 回到首页（网络模块首页）──
                // 从深层文件夹一键回首页，不必连按好几次「上一级」。
                // 原先是个顶栏图标（占横向位置），收进「更多」里。
                DropdownMenuItem(
                  text = { Text("回到首页") },
                  leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    goHome()
                  },
                )

                // ── 随机进入一个子文件夹 ──
                // 大库里「随便翻一个」比逐层点进去快得多；跟下面两个「随机播放」是同一族操作。
                // 没有子文件夹时明确 Toast —— 与随机播放同一口径，不静默失败。
                DropdownMenuItem(
                  text = { Text("随机进入一个文件夹") },
                  leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    // 用未过滤的 files：类型筛选决定「这屏显示什么」，不该限制随机跳到哪
                    val folders = files.filter { it.isDirectory }
                    if (folders.isEmpty()) {
                      toast("当前目录没有子文件夹")
                    } else {
                      // 与点击文件夹同一个压栈方式（onFolderClick），返回键即回本层
                      backstack.add(
                        NetworkBrowserScreen(
                          connectionId = connectionId,
                          connectionName = connectionName,
                          currentPath = folders.random().path,
                        ),
                      )
                    }
                  },
                )

                // ── 收藏当前目录 ──
                // 状态来自 ViewModel（loadFiles 时同源刷新），主界面「收藏的目录」
                // section 与这里永远一致。切完弹 toast：一屏那么多条目，
                // 没提示根本注意不到刚才那下点没点上。
                DropdownMenuItem(
                  text = {
                    Text(if (isCurrentFavorite) "取消收藏「$dirTitle」" else "收藏「$dirTitle」")
                  },
                  leadingIcon = {
                    Icon(
                      imageVector =
                        if (isCurrentFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                      contentDescription = null,
                      tint =
                        if (isCurrentFavorite) {
                          MaterialTheme.colorScheme.primary
                        } else {
                          MaterialTheme.colorScheme.secondary
                        },
                    )
                  },
                  onClick = {
                    showOverflowMenu = false
                    viewModel.toggleFavoriteCurrent { added ->
                      toast(if (added) "已收藏「$dirTitle」" else "已取消收藏「$dirTitle」")
                    }
                  },
                )

                // ── 当前目录属性（大小统计 / 完整路径 / 打包下载）──
                // 长按只能点列表里的条目，而「当前目录」自己也在列表里看不到 ——
                // 想统计「我现在站的这个文件夹有多大」只有这里一个入口。
                DropdownMenuItem(
                  text = { Text("当前目录属性") },
                  leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    val dirFile =
                      NetworkFile(
                        name = dirTitle,
                        path = currentPath,
                        size = 0,
                        isDirectory = true,
                      )
                    dirSizeState = null
                    propertyTarget = dirFile
                    viewModel.computeDirSize(dirFile) { dirSizeState = it }
                  },
                )

                // ── 上传（本地 → 当前目录）──
                // 只有 AList 有写接口（fs/put），其余协议的 supportsWrite 为 false，
                // 压根不显示 —— 弹出来点了报错不如不给入口（与长按菜单同一原则）。
                if (supportsWrite) {
                  DropdownMenuItem(
                    text = { Text("上传到远程") },
                    leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    onClick = {
                      showOverflowMenu = false
                      // 没有「所有文件访问权限」也照开：面板自己有授权引导，
                      // 直接把用户送进面板比先弹一句 toast 再让他重进一次顺。
                      showUpload = true
                    },
                  )
                }

                // ── 新建文件夹（同样只有 AList 有 fs/mkdir）──
                if (supportsWrite) {
                  DropdownMenuItem(
                    text = { Text("新建文件夹") },
                    leadingIcon = {
                      Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                    },
                    onClick = {
                      showOverflowMenu = false
                      showNewFolder = true
                    },
                  )
                }

                // ── 整理工具箱（按类型归类 / 提取并整理 / 清理空目录 / 按月份归档）──
                // 从 AlistClientN 的批量整理能力搬过来的，全部走 AList 写接口，
                // 所以同样只在 supportsWrite 下给入口。
                if (supportsWrite) {
                  DropdownMenuItem(
                    text = { Text("整理工具箱") },
                    leadingIcon = {
                      Icon(Icons.Default.Handyman, contentDescription = null)
                    },
                    onClick = {
                      showOverflowMenu = false
                      showToolbox = true
                    },
                  )
                }

                // ── 随机播放（视频 / 图片） ──
                // 目录经常是混合的，两项都给，用户想要哪个随机哪个。
                // 没有对应类型时不静默失败 —— Toast 说清楚「没有」。
                DropdownMenuItem(
                  text = { Text("随机播放视频") },
                  leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    // 递归模式事先不知道子文件夹里有没有视频，只能抽完再报「没有」；
                    // 只播当前层时列表就在内存里，直接判即可。
                    if (!drillDown && files.none { it.isVideoFile() }) {
                      toast("当前目录没有视频")
                    } else {
                      if (drillDown) toast("正在抽取视频…")
                      viewModel.playRandomVideos(drillDown = drillDown) {
                        toast(if (drillDown) "当前目录及其子文件夹里没有视频" else "当前目录没有视频")
                      }
                    }
                  },
                )
                // ── 视界流（仿抖音竖屏上下滑连播）──
                // 与「随机播放视频」同源同范围：同一次抽片、同一个 randomPlayCount 数量口径，
                // 差别只在最后一步 —— 交给 VerticalFeedActivity 自己管「滑到哪放哪」。
                // 网盘内容不绑 Emby 库（libraryId = null），所以视界流里不会出现
                // 收藏 / 不喜欢那两个按钮（点了必然失败，见 FeedViewModel.embyBound）。
                DropdownMenuItem(
                  text = { Text("视界流播放") },
                  leadingIcon = { Icon(VideoFeedIcon, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    if (drillDown) toast("正在抽取视频…")
                    viewModel.startFeedPlayback(drillDown = drillDown) {
                      toast(if (drillDown) "当前目录及其子文件夹里没有视频" else "当前目录没有视频")
                    }
                  },
                )
                DropdownMenuItem(
                  text = { Text("随机播放图片") },
                  leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    if (!drillDown) {
                      // 只播当前层：列表已在内存里，同步打开查看器
                      val photos = files.filter { it.isPhoto() }
                      if (photos.isEmpty()) {
                        toast("当前目录没有图片")
                      } else {
                        // 与视频随机播放同一数量口径（1~500 护栏）
                        val limit =
                          browserPreferences.randomPlayCount.get().coerceIn(1, 500)
                        backstack.add(
                          NetworkPhotoViewerScreen(
                            connectionId = connectionId,
                            connectionName = connectionName,
                            dirPath = currentPath,
                            photos = photos.shuffled().take(limit),
                            initialIndex = 0,
                            title = dirTitle,
                            autoSlideshow = true,
                          ),
                        )
                      }
                    } else {
                      // 穿透模式：每到一个子目录就把**该级图片整层**收进队列
                      // （不按 randomPlayCount 抽，用户要求「都加到队列去」）。
                      // 要逐层列目录 ⇒ 先把菜单收掉、给一句反应，再等结果。
                      toast("正在抽取图片…")
                      scope.launch {
                        val queue = viewModel.collectRandomPhotos(drillDown = true)
                        if (queue.isEmpty()) {
                          toast("当前目录及其子文件夹里没有图片")
                        } else {
                          backstack.add(
                            NetworkPhotoViewerScreen(
                              connectionId = connectionId,
                              connectionName = connectionName,
                              dirPath = currentPath,
                              // 组间已洗牌、组内保序（见 VM.collectRandomPhotos）
                              photos = queue,
                              initialIndex = 0,
                              title = dirTitle,
                              autoSlideshow = true,
                            ),
                          )
                        }
                      }
                    }
                  },
                )
                // ── 穿透子文件夹（开关项，作用于上面三项：随机视频 / 视界流 / 随机图片）──
                // ⚠️ 点它**不关菜单**：关了的话用户看不到勾有没有打上，得再点开确认一次。
                // 状态存偏好（networkRandomDrillDown），下次进来还记得上次的选择。
                // ⚠️ 位置在三个播放项**之后**：它是个修饰前三项的开关，先摆「随机什么」
                //    再摆「范围到哪」，读下来才顺。
                DropdownMenuItem(
                  text = { Text("穿透子文件夹") },
                  leadingIcon = { Checkbox(checked = drillDown, onCheckedChange = null) },
                  onClick = { browserPreferences.networkRandomDrillDown.set(!drillDown) },
                )

                // ── 视图切换 ──
                // 文案与图标都指向**目标模式**（网格时显示「列表视图」），
                // 与播放列表页同一惯例
                DropdownMenuItem(
                  text = { Text(if (isGridView) "列表视图" else "网格视图") },
                  leadingIcon = {
                    Icon(
                      imageVector =
                        if (isGridView) {
                          Icons.AutoMirrored.Filled.ViewList
                        } else {
                          Icons.Default.GridView
                        },
                      contentDescription = null,
                    )
                  },
                  onClick = {
                    showOverflowMenu = false
                    browserPreferences.folderViewMode.set(
                      if (isGridView) FolderViewMode.FileManager else FolderViewMode.AlbumView,
                    )
                  },
                )

                // ── 双栏浏览（**只在宽屏出现**）──
                // 窄屏根本不具备双栏条件，摆在这儿点了也没反应 —— 干脆不显示。
                // 与「穿透子文件夹」同一手法：点它不关菜单，勾选状态要能被看见。
                if (dualPaneCapable) {
                  DropdownMenuItem(
                    text = { Text("双栏浏览") },
                    leadingIcon = { Checkbox(checked = dualPanePref, onCheckedChange = null) },
                    onClick = { browserPreferences.networkDualPane.set(!dualPanePref) },
                  )
                }
              }
            }
          },
        )
      },
      // 下载进度条挂在底栏：bottomBar 是**占位**的（内容会被顶上去），
      // 不是浮在列表上 —— 最后一行永远不会被这条盖住。
      bottomBar = {
        NetworkDownloadBarHost(
          progress = downloadProgress,
          onCancel = { NetworkDownloadCenter.cancel() },
          onDismiss = { NetworkDownloadCenter.clear() },
          // 重试要用同一个 context；引擎自己记着上一次的源条目
          onRetry = { NetworkDownloadCenter.retry(context) },
        )
      },
    ) { padding ->
      // ⚠️ 这层 Box 是**为长按菜单的锚定**而包的（不是排版需要）：
      //    菜单要用手指的 root 坐标定位，锚点 Box 的原点必须落在屏幕原点。
      //    直接塞进下面那个 Column 的话，Column 自带 padding(padding)（顶栏高度），
      //    菜单会整体下移一个顶栏 —— 表现是「菜单出现在手指下方几十 dp 处」。
      Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.padding(padding)) {
        // ── 本地搜索输入框 ──
        // 点顶栏放大镜出现：对**当前目录已拉到的内容**按名称关键词过滤，
        // 不发任何请求（目录列表本来就是全量一次拉完的）。
        if (searchActive) {
          NetworkSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onClose = {
              searchActive = false
              searchQuery = ""
            },
          )
        }

        // ── 类型筛选 + 排序控制条 ──
        // 有内容才显示：空目录 / 首屏加载中 / 出错时下面就是空态或错误页，
        // 摆一排控件在上面只会让人对着空气点。
        // 两个紧凑按钮一排放下（筛选 + 排序），不再横滚、不抢顶栏位置。
        if (files.isNotEmpty() && error == null) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            NetworkTypeFilterButton(
              showFolders = showFolders,
              showPhotos = showPhotos,
              showVideos = showVideos,
              onShowFoldersChange = { showFolders = it },
              onShowPhotosChange = { showPhotos = it },
              onShowVideosChange = { showVideos = it },
            )
            NetworkSortButton(
              sortType = sortType,
              sortOrder = sortOrder,
              onSortTypeChange = viewModel::setSortType,
              onSortOrderChange = viewModel::setSortOrder,
            )
          }
        }

        // ── 主体：单栏 / 双栏 ──
        // 双栏只在**宽屏且不在根目录**时出现（见上面 dualPane）。左栏固定 240dp，
        // 右栏吃掉剩下的宽度 —— 两个面板在同一个 Row 里各占各的宽度，
        // 所以无论哪种形态都不会互相压盖。
        Row(modifier = Modifier.weight(1f)) {
          if (dualPane) {
            NetworkFolderPane(
              parentLabel = parentLabel,
              folders = siblings,
              currentPath = currentPath,
              onGoUp = { backstack.removeLastOrNull() },
              onSelect = { folder -> navigateToPath(folder.path) },
              modifier = Modifier.width(240.dp),
            )
            PaneDivider()
          }

          NetworkBrowserContent(
            files = visibleFiles,
            connectionId = connectionId,
            isLoading = isLoading && files.isEmpty(),
            isRefreshing = isRefreshing,
            error = error,
            isGridView = isGridView,
            // 滚动 state 由本页顶层持有（保存/恢复/排序回顶都在那一层驱动，见上方注释）
            gridState = networkGridState,
            listState = networkListState,
            searchQuery = searchQuery,
            anyTypeHidden = !(showFolders && showPhotos && showVideos),
            folderCovers = folderCovers,
            coverTrigger = coverTrigger,
            titleMaxLines = titleMaxLines,
            // 下拉刷新 = 用户显式要最新 ⇒ 顺带失效子文件夹封面缓存重取；
            // 正常进入 / 返回上一级走缓存，不重列目录（见 NetworkBrowserViewModel.loadFiles）
            onRefresh = { viewModel.loadFiles(invalidateCovers = true) },
            onFolderClick = { folder ->
              backstack.add(
                NetworkBrowserScreen(
                  connectionId = connectionId,
                  connectionName = connectionName,
                  currentPath = folder.path,
                ),
              )
            },
            onPhotoClick = { photo ->
              // 队列 = 当前目录全部照片（浏览器的目录列表本来就是全量一次拉完，
              // 没有续页问题），点哪张就从哪张开始
              val photos = files.filter { it.isPhoto() }
              backstack.add(
                NetworkPhotoViewerScreen(
                  connectionId = connectionId,
                  connectionName = connectionName,
                  dirPath = currentPath,
                  photos = photos,
                  initialIndex =
                    photos.indexOfFirst { it.path == photo.path }.coerceAtLeast(0),
                  title = dirTitle,
                ),
              )
            },
            onVideoClick = { video ->
              // 点击即播：队列是当前目录全部视频，点哪条从哪条开始连播
              val videos = files.filter { it.isVideoFile() }
              viewModel.playVideos(
                videos,
                videos.indexOfFirst { it.path == video.path }.coerceAtLeast(0),
              )
            },
            // 长按菜单：**所有协议都挂**（属性 / 下载是只读动作，SMB、WebDAV、FTP 一样需要）；
            // 重命名 / 删除两项在菜单内部按 supportsWrite 决定显不显示。
            onFileLongClick = { file, offset ->
              actionTarget = file
              actionMenuAnchor = offset
            },
            // 未过滤总数（筛选 / 搜索前的 files.size）——空态文案要区分
            // 「目录真空」和「全被滤掉」，只有过滤后的列表看不出来
            totalCount = files.size,
            modifier = Modifier.weight(1f),
          )
        }
      }

      // ── 属性面板（右侧抽屉）──
      // ⚠️ 这层 Box 让出顶栏 / 底栏的高度：Scaffold 的内容层铺满整屏（padding 只是
      //    数值，不给就真的铺满），而 bars 是画在内容**之上**的 —— 不让开的话，
      //    抽屉的头部会被顶栏压住、底部的「下载 / 关闭」会被下载条压住。
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(
              top = padding.calculateTopPadding(),
              bottom = padding.calculateBottomPadding(),
            ),
      ) {
        NetworkFilePropertiesOverlay(
          file = propertyTarget,
          connectionName = connectionName,
          protocolLabel = protocolLabel,
          dirSize = dirSizeState,
          onRequestDirSize = {
            propertyTarget?.takeIf { it.isDirectory }?.let { dir ->
              viewModel.computeDirSize(dir) { dirSizeState = it }
            }
          },
          onDownload = { file ->
            viewModel.startDownload(file) { started, reason ->
              if (!started) toast(reason ?: "无法开始下载")
            }
          },
          onDismiss = {
            propertyTarget = null
            // 面板关了就停掉统计：一次几千个目录的递归没必要在后台跑完
            viewModel.cancelDirSize()
          },
        )
      }

      // ── 长按条目的操作菜单（重命名 / 删除）──
      // **范式照抄 Emby 媒体库**：锚定在手指位置的浮动气泡菜单（`DropdownMenu`），
      // 不是居中的大卡片 —— AList 库与 Emby 媒体库除接口外结构一致，交互观感
      // 就该是同一套。（`EmbyItemActionsDialog` 也是这么写的：菜单本体是
      // DropdownMenu，需要输入 / 二次确认的动作才另开对话框。）
      // 与 Emby「扫描媒体库」同一原则：**写操作作用在服务器上、发出去撤不回来**，
      // 所以长按只弹菜单，删除还要再过一道确认，绝不直接执行。
      val target = actionTarget
      if (target != null) {
        Box(
          modifier =
            Modifier.offset {
              IntOffset(actionMenuAnchor.x.roundToInt(), actionMenuAnchor.y.roundToInt())
            },
        ) {
          NetworkFileActionMenu(
            file = target,
            // 重命名 / 删除只有 AList 有写接口；属性与下载对谁都可用
            canWrite = supportsWrite,
            onDismissRequest = { actionTarget = null },
            onProperties = {
              actionTarget = null
              // 先清掉上一个条目的统计结果，再对目录发起统计（文件不需要）
              dirSizeState = null
              propertyTarget = target
              if (target.isDirectory) {
                viewModel.computeDirSize(target) { dirSizeState = it }
              }
            },
            onDownload = {
              actionTarget = null
              viewModel.startDownload(target) { started, reason ->
                if (!started) toast(reason ?: "无法开始下载")
              }
            },
            onRename = {
              actionTarget = null
              renameTarget = target
            },
            onDelete = {
              actionTarget = null
              deleteTarget = target
            },
          )
        }
      }
      }

      // 重命名：给个输入框，默认填当前名称
      renameTarget?.let { file ->
        // ⚠️ stringResource 只能在这里（Composable 上下文）取：
        //    下面的 onClick 回调不是 Composable，里面调不了
        val renamedText = stringResource(R.string.i18n_renamed)
        var newName by remember(file.path) { mutableStateOf(file.name) }
        AlertDialog(
          onDismissRequest = { renameTarget = null },
          title = { Text(stringResource(R.string.i18n_rename)) },
          text = {
            OutlinedTextField(
              value = newName,
              onValueChange = { newName = it },
              label = { Text(stringResource(R.string.i18n_new_name)) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
          },
          confirmButton = {
            TextButton(
              onClick = {
                renameTarget = null
                viewModel.renameFile(file, newName) { _, message ->
                  // 失败时 message 是服务端/校验给的原因，成功时是 null
                  toast(message ?: renamedText)
                }
              },
            ) {
              Text(stringResource(R.string.i18n_confirm))
            }
          },
          dismissButton = {
            TextButton(onClick = { renameTarget = null }) {
              Text(stringResource(R.string.i18n_cancel))
            }
          },
        )
      }

      // 删除：二次确认。AList 没有回收站，删了就是没了
      deleteTarget?.let { file ->
        val deletedText = stringResource(R.string.i18n_deleted)
        AlertDialog(
          onDismissRequest = { deleteTarget = null },
          title = { Text(stringResource(R.string.i18n_delete)) },
          text = {
            Text(stringResource(R.string.i18n_delete_confirm_message, file.name))
          },
          confirmButton = {
            TextButton(
              onClick = {
                deleteTarget = null
                viewModel.deleteFile(file) { _, message ->
                  toast(message ?: deletedText)
                }
              },
            ) {
              Text(
                stringResource(R.string.i18n_delete),
                color = MaterialTheme.colorScheme.error,
              )
            }
          },
          dismissButton = {
            TextButton(onClick = { deleteTarget = null }) {
              Text(stringResource(R.string.i18n_cancel))
            }
          },
        )
      }

      // ── 新建文件夹 ──
      // 与「重命名」同一套外观（一个输入框 + 确认），空名时确认键直接禁用 ——
      // 比点完再弹「名称不能为空」少一次往返。
      if (showNewFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
          onDismissRequest = { showNewFolder = false },
          title = { Text("新建文件夹") },
          text = {
            OutlinedTextField(
              value = folderName,
              onValueChange = { folderName = it },
              label = { Text(stringResource(R.string.i18n_new_name)) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
          },
          confirmButton = {
            TextButton(
              enabled = folderName.isNotBlank(),
              onClick = {
                val name = folderName.trim()
                showNewFolder = false
                viewModel.createFolder(name) { _, message ->
                  toast(message ?: "已新建「$name」")
                }
              },
            ) {
              Text(stringResource(R.string.i18n_confirm))
            }
          },
          dismissButton = {
            TextButton(onClick = { showNewFolder = false }) {
              Text(stringResource(R.string.i18n_cancel))
            }
          },
        )
      }

      // ── 「跳转到」导航弹窗 ──
      if (showPathNavigator) {
        PathNavigatorSheet(
          connectionName = connectionName,
          currentPath = currentPath,
          levels = pathLevels,
          onDismiss = { showPathNavigator = false },
          onSelect = { path ->
            // 先关窗再跳：避免「弹窗还在收、页面已经切过去」的观感叠影
            showPathNavigator = false
            navigateToPath(path)
          },
        )
      }

      // ── 上传面板（本地文件 / 文件夹 → 当前目录）──
      // 放在这里而不是顶栏菜单内部：菜单一旦收起就会被移出组合，
      // 挂在里面的 ModalBottomSheet 会跟着消失（菜单点击的第一帧就崩）。
      if (showUpload) {
        NetworkUploadSheet(
          connectionId = connectionId,
          targetDir = currentPath,
          targetLabel = dirTitle,
          onDismiss = { showUpload = false },
        )
      }

      // ── 整理工具箱（底部抽屉）──
      // 与上传面板同理挂在菜单外面：菜单一收就出组合，抽屉会被连带销毁。
      // 抽屉只负责「选工具 / 看清单 / 看进度」，真正的任务是 ViewModel 上的
      // toolJob —— 抽屉关掉它也继续跑，再打开还能接着看进度与结果。
      if (showToolbox) {
        NetworkToolboxSheet(
          dirTitle = dirTitle,
          progress = toolProgress,
          // 根目录只留只读工具：这一层往往是所有网盘挂载的汇合处，
          // 「按类型归类」在这儿建个「视频」就等于把整块存储顶层搬空
          atRoot = currentPath.trim('/').isEmpty(),
          onScan = { tool, onResult -> viewModel.scanToolbox(tool, onResult) },
          onRun = { plan ->
            viewModel.runToolbox(plan) { message -> toast(message) }
          },
          onCancel = { viewModel.cancelToolbox() },
          onDismiss = {
            showToolbox = false
            // 只清「已经跑完」的状态；任务还在跑就保留，重开抽屉能继续看
            viewModel.resetToolbox()
          },
        )
      }
    }
  }
}

/**
 * 长按条目弹出的**操作气泡菜单**（重命名 / 删除）。
 *
 * 与 Emby 媒体库的 `EmbyItemActionsDialog` 同一套写法与观感 —— AList 库和
 * Emby 媒体库除接口外结构一致，长按交互就不该长成两副样子：
 * - `DropdownMenu`（**不是** AlertDialog）：宽度贴合内容、锚在长按位置；
 * - 顶部一行条目名（`labelMedium` + `onSurfaceVariant`），一行截断；
 * - 每项「图标 + 文字」，删除用 `error` 色（危险动作一眼看出来）；
 * - **需要输入 / 不可逆的动作不塞这里**：菜单只负责选动作，重命名与删除确认
 *   另开对话框（Emby 那边同样把「刷新元数据」的选项放二级框）。
 */
@Composable
private fun NetworkFileActionMenu(
  file: NetworkFile,
  /** 当前协议是否支持写操作（AList）。false 时不显示重命名 / 删除 */
  canWrite: Boolean,
  onDismissRequest: () -> Unit,
  onProperties: () -> Unit,
  onDownload: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  DropdownMenu(expanded = true, onDismissRequest = onDismissRequest) {
    Text(
      text = file.name,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    // ── 只读两项：任何协议都有 ──
    DropdownMenuItem(
      text = { Text("属性") },
      leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
      onClick = onProperties,
    )
    DropdownMenuItem(
      text = { Text(if (file.isDirectory) "打包下载" else "下载到本地") },
      leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
      onClick = onDownload,
    )
    if (canWrite) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      DropdownMenuItem(
        text = { Text(stringResource(R.string.i18n_rename)) },
        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
        onClick = onRename,
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.i18n_delete), color = MaterialTheme.colorScheme.error) },
        leadingIcon = {
          Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
          )
        },
        onClick = onDelete,
      )
    }
  }
}

/**
 * 「跳转到」导航弹窗（点顶栏标题弹出）。
 *
 * 列出 **连接根目录 → 当前目录** 的全部路径层级，点任意一层直接跳过去 ——
 * 多层级目录里不用再一层层按「上一级」，也不止是「回首页」这一个出口。
 *
 * 范式参考 AlistClientN 的路径导航（`lib/screen/file_list/file_list_screen.dart`
 * 的 `_showPathNavigator`，用户自己写的那版），并把它几处边界问题修掉了：
 *
 * 1. **列表顺序**：它用 `crumbs.reversed().skip(1)`，于是「根目录」之后紧跟着的是
 *    **最靠近的上级**、越往下越远 —— 读起来不是面包屑，而这里改成
 *    根目录 → 逐级向下的自然顺序（与本地文件夹层级一致）。
 * 2. **当前层**：它把当前层 `skip` 掉，用户看不出自己站在哪一层。这里保留当前层、
 *    用勾号标出（点它只是关窗）。
 * 3. **路径尾斜杠**：它按 `path.split('/')` 拼 `fullPath`，若原路径带尾斜杠会拼出
 *    `/a/b/`。这里统一 `trim('/')` 归一化后再逐级拼。
 * 4. **无匹配时的破坏性**：它的 `_navigateToPath` 先用 `Get.until` 弹栈再判断是否
 *    匹配，目标路径没匹配上就把整条导航栈弹空了。这里改成「栈里有就截栈、没有就
 *    只 push」，见调用方 `navigateToPath`。
 * 5. **根目录**：它对根目录直接 `return`，点标题毫无反馈。这里根目录干脆不给入口
 *    （标题不带下拉箭头、也不接管点击）—— 保留原有的「点标题切深浅色」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PathNavigatorSheet(
  /** 连接名（「根目录」那一行的副标题，让用户知道这是哪台服务器） */
  connectionName: String,
  /** 当前目录完整路径（弹窗标题下那行；同时是「我在哪」的判据） */
  currentPath: String,
  /** 根目录 → 当前目录，逐级 (完整路径 to 显示名) */
  levels: List<Pair<String, String>>,
  onDismiss: () -> Unit,
  /** 选中某一层（传该层的完整路径） */
  onSelect: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()

  // 尾斜杠归一化：只用于判「这一行是不是我当前所在的那层」
  fun normalize(path: String) = path.trimEnd('/').ifEmpty { "/" }
  val currentNormalized = normalize(currentPath)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
      Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)) {
        Text(
          text = "跳转到",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = currentPath,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
      }

      HorizontalDivider()

      // 层级一般不多（几层到十几层），用普通 Column + 纵向滚动即可；
      // 给个最大高度上限，免得深目录把整屏铺满。
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
      ) {
        levels.forEach { (path, label) ->
          val isCurrent = normalize(path) == currentNormalized
          val isRoot = normalize(path) == "/"
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                // 当前层点了只关窗（已经在那一层了，跳过去是空动作）
                .clickable { if (isCurrent) onDismiss() else onSelect(path) }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = if (isRoot) Icons.Default.Home else Icons.Default.Folder,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color =
                  if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                // 根目录那行副标题给连接名（比一个赤裸的 "/" 有用）；
                // 其余层级给完整路径，长路径截断。
                text = if (isRoot) connectionName.ifBlank { "/" } else path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (isCurrent) {
              Spacer(modifier = Modifier.width(8.dp))
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NetworkBrowserContent(
  /** 过滤后的列表（类型筛选 + 搜索词都已应用） */
  files: List<NetworkFile>,
  connectionId: Long,
  isLoading: Boolean,
  isRefreshing: MutableState<Boolean>,
  error: String?,
  isGridView: Boolean,
  /**
   * 滚动 state 由上层（NetworkBrowserScreen.Content）持有并传下来。
   *
   * 不在这里 `remember`：保存挂在「本页组合被销毁」上、恢复要等条目落位后统一驱动，
   * 两头都在上层（见那边注释）。这里只负责把 state 接到 LazyGrid / LazyColumn 上。
   */
  gridState: LazyGridState,
  listState: LazyListState,
  /** 当前搜索词（空 = 没在搜），只用于空态文案 */
  searchQuery: String,
  /** 是否有类型被筛掉（空态文案用） */
  anyTypeHidden: Boolean,
  /** 文件夹封面缓存（path → 封面 path）；功能关闭时传空 Map */
  folderCovers: Map<String, String?>,
  /** 封面懒加载触发点；功能关闭时传 null */
  coverTrigger: ((String) -> Unit)?,
  /** 网格卡标题行数（设置项：一行 / 两行 / 全显） */
  titleMaxLines: Int,
  /** 未过滤的条目数（区分「目录真空」与「全被筛掉」） */
  totalCount: Int,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onPhotoClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  /**
   * 长按条目的回调；null = 当前协议不支持写操作，长按需忽略
   * （不给入口，免得弹个菜单出来点了全是报错）。
   *
   * 第二个参数是**手指坐标（root）** —— 菜单要锚定到它旁边（见 Emby 媒体库）。
   */
  onFileLongClick: ((NetworkFile, Offset) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  // Load connection details（走仓库而不是直接读 DAO —— 密码落盘是密文，仓库会负责解密）
  val repository = org.koin.compose.koinInject<NetworkRepository>()
  var connection by remember { mutableStateOf<NetworkConnection?>(null) }

  LaunchedEffect(connectionId) {
    connection = repository.getConnectionById(connectionId)
  }

  // 四分法。remember(files)：大目录（上千条）每次重组都过滤一遍纯浪费，
  // files 不变就直接拿旧结果。
  val folders = remember(files) { files.filter { it.isDirectory } }
  val photos = remember(files) { files.filter { it.isPhoto() } }
  val videos = remember(files) { files.filter { it.isVideoFile() } }
  val others = remember(files) { files.filter { it.type() == NetworkFileType.OTHER } }
  // 网格内容：与列表视图**同一口径** —— 直接沿用 files 的顺序，不再自己排一遍。
  // ⚠️ 这里原来写的是 `folders + (photos + videos).sortedBy { it.name.lowercase() }`，
  // 那是个隐蔽的 bug：`files` 从 ViewModel 出来时**已经**按用户选的排序键排过
  // （`networkFileComparator` = 文件夹恒在前 + 排序键 + 名称兜底），网格再按名称
  // 排一次 = 把用户的排序选择丢掉。表现就是「列表视图里排序生效、网格视图里没反应」。
  // 而纯目录的那一层（如 TOKYO 下全是 e0xx 子目录）整个走 `folders +`、没被重排，
  // 看起来「排序是好的」，于是很容易误判成「只有某些文件夹排序失效」——实际是
  // 「只有媒体被重排」，跟在哪一层无关。
  // 仍然排除 OTHER（字幕 / nfo 等）：网格只铺能渲染成卡片的条目（与空态文案一致）。
  // 照片与视频保持**混排**（不再按类型分屏），顺序仍由上面的排序键决定。
  val gridItems = remember(files) {
    files.filter { it.isDirectory || it.isPhoto() || it.isVideoFile() }
  }

  // 空态文案：把「目录真的空」「搜索没命中」「筛选把内容滤光了」三种情况说清楚，
  // 别甩一个空网格让人猜。
  val filteredEmptyMessage =
    when {
      searchQuery.isNotBlank() -> "没有匹配「$searchQuery」的内容，换个关键词试试"
      anyTypeHidden -> "当前筛选条件下没有内容，勾选上方更多类型试试"
      else -> "此目录下只有其他类型文件（字幕、nfo 等）；切换到列表视图可查看全部文件"
    }

  when {
    isLoading -> {
      // 骨架屏替掉原先居中的转圈：转圈只说明「在加载」，骨架顺带把「待会儿会出现
      // 什么」先摆出来，内容落地时不会整屏跳变。版式按当前视图模式选 ——
      // 网格 3 列 1:1（与 NetworkBrowserGrid 一致），列表是「左缩略图 + 右两行」。
      if (isGridView) {
        BrowserSkeletonGrid(
          columns = 3,
          ratio = 1f,
          itemCount = 12,
          modifier = modifier,
        )
      } else {
        BrowserSkeletonRows(
          itemCount = 9,
          thumbWidth = 64.dp,
          thumbRatio = 16f / 9f,
          modifier = modifier,
        )
      }
    }

    error != null -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = "加载文件出错",
          message = error,
        )
      }
    }

    // 目录本身是空的（没有任何条目）
    files.isEmpty() && totalCount == 0 -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = "空文件夹",
          message = "该文件夹中没有文件或子目录",
        )
      }
    }

    // 有内容但全被滤掉（搜索词没命中 / 类型全关）
    files.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = "没有可显示的内容",
          message = filteredEmptyMessage,
        )
      }
    }

    // 网格视图下没有可渲染的卡片（整个目录只有 OTHER 文件时会出现）：
    // 友好提示说清楚「有哪些东西、去哪看」，别甩一个空网格
    isGridView && gridItems.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = "没有图片或视频",
          message = filteredEmptyMessage,
        )
      }
    }

    isGridView -> {
      NetworkBrowserGrid(
        items = gridItems,
        connectionId = connectionId,
        gridState = gridState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        onFolderClick = onFolderClick,
        onPhotoClick = onPhotoClick,
        onVideoClick = onVideoClick,
        folderCovers = folderCovers,
        coverTrigger = coverTrigger,
        titleMaxLines = titleMaxLines,
        onFileLongClick = onFileLongClick,
        modifier = modifier,
      )
    }

    else -> {
      NetworkBrowserList(
        folders = folders,
        photos = photos,
        videos = videos,
        others = others,
        connection = connection,
        connectionId = connectionId,
        listState = listState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        onFolderClick = onFolderClick,
        onPhotoClick = onPhotoClick,
        onVideoClick = onVideoClick,
        folderCovers = folderCovers,
        coverTrigger = coverTrigger,
        onFileLongClick = onFileLongClick,
        modifier = modifier,
      )
    }
  }
}

/**
 * 网格视图：统一卡型混排（文件夹在前，照片 + 视频沿用当前排序混排）。
 *
 * 排序沿用 [NetworkBrowserScreen] 传来的 `gridItems` 顺序 —— 也就是用户在顶栏
 * 选的排序键，这里**不再二次排序**（见该处注释）。
 *
 * 固定 3 列：混合内容没有「海报比例」可依赖，1:1 卡 + 3 列是手机宽度下
 * 缩略图尺寸与信息密度的平衡点（与 Emby 照片网格默认列数一致）。
 */
@Composable
private fun NetworkBrowserGrid(
  items: List<NetworkFile>,
  connectionId: Long,
  /**
   * 滚动 state 由上层持有并传入（保存 / 恢复 / 排序回顶都在上层驱动，
   * 见 NetworkBrowserScreen.Content 的注释）—— 这里只负责接上。
   */
  gridState: LazyGridState,
  isRefreshing: MutableState<Boolean>,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onPhotoClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  folderCovers: Map<String, String?>,
  coverTrigger: ((String) -> Unit)?,
  titleMaxLines: Int,
  /** 长按条目；null = 协议不支持写操作，卡片不挂长按。第二参 = 手指坐标 */
  onFileLongClick: ((NetworkFile, Offset) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val navigationBarHeight = LocalNavigationBarHeight.current

  PullRefreshGridBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    gridState = gridState,
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(bottom = navigationBarHeight),
    ) {
      // 滚动条走统一外壳（全局开关 + 出现时机 + 外观都在里面，见 ui/utils/SideScrollbar.kt）
      LazyGridSideScrollbar(state = gridState) {
        LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = navigationBarHeight,
          ),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(
            items = items,
            key = { it.path },
          ) { file ->
            NetworkMediaCard(
              file = file,
              connectionId = connectionId,
              onClick = {
                when (file.type()) {
                  NetworkFileType.FOLDER -> onFolderClick(file)
                  NetworkFileType.PHOTO -> onPhotoClick(file)
                  NetworkFileType.VIDEO -> onVideoClick(file)
                  NetworkFileType.OTHER -> Unit // 网格里不渲染，兜底
                }
              },
              // 文件夹封面：缓存里有就用（功能关闭时 Map 是空的 ⇒ 全是 null），
              // 没有就让卡片触发懒加载（coverTrigger 为 null 时不触发）
              coverPath = if (file.isDirectory) folderCovers[file.path] else null,
              titleMaxLines = titleMaxLines,
              onCoverNeeded = coverTrigger,
              onLongClick =
                if (onFileLongClick == null) null else { { offset -> onFileLongClick(file, offset) } },
              // 切排序 / 删除条目后平滑重排，而不是整屏硬切
              modifier = Modifier.animateItem(),
            )
          }
        }
      }
    }
  }
}

/**
 * 列表视图：分 section 展示（文件夹 / 图片 / 视频 / 其他）。
 *
 * 保留旧版的 section 头与滚动条行为；照片行 [NetworkPhotoRow] 与
 * 其他文件行 [NetworkOtherFileRow] 是这次改造新增的两种卡。
 */
@Composable
private fun NetworkBrowserList(
  folders: List<NetworkFile>,
  photos: List<NetworkFile>,
  videos: List<NetworkFile>,
  others: List<NetworkFile>,
  connection: NetworkConnection?,
  connectionId: Long,
  /**
   * 滚动 state 由上层持有并传入（保存 / 恢复 / 排序回顶都在上层驱动，
   * 见 NetworkBrowserScreen.Content 的注释）—— 这里只负责接上。
   *
   * ⚠️ 绝不能在这里写 `LazyListState()`（不带 remember）：每次重组都新建一个 state，
   *    滚动位置归零 ⇒「列表滑不动、每次加载都弹回顶部」，还会顺带让下拉刷新在
   *    任何位置都处于启用状态（手势冲突）。上层的 `rememberLazyListState()` 正是
   *    为这个毛病存在的，别把它改回局部新建。
   */
  listState: LazyListState,
  isRefreshing: MutableState<Boolean>,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onPhotoClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  folderCovers: Map<String, String?>,
  coverTrigger: ((String) -> Unit)?,
  /** 长按条目；null = 协议不支持写操作，卡片不挂长按。第二参 = 手指坐标 */
  onFileLongClick: ((NetworkFile, Offset) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val navigationBarHeight = LocalNavigationBarHeight.current

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    listState = listState,
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(bottom = navigationBarHeight),
    ) {
      // 滚动条走统一外壳（全局开关 + 出现时机 + 外观都在里面，见 ui/utils/SideScrollbar.kt）
      LazyListSideScrollbar(state = listState) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = navigationBarHeight,
          ),
        ) {
          // Folders section
          if (folders.isNotEmpty()) {
            item {
              SectionHeader("文件夹")
            }
            items(
              items = folders,
              key = { it.path },
            ) { folder ->
              NetworkFolderCard(
                file = folder,
                connectionId = connectionId,
                onClick = { onFolderClick(folder) },
                coverPath = folderCovers[folder.path],
                onCoverNeeded = coverTrigger,
                onLongClick =
                  if (onFileLongClick == null) {
                    null
                  } else {
                    { offset -> onFileLongClick(folder, offset) }
                  },
                modifier = Modifier.animateItem(),
              )
            }
          }

          // Photos section
          if (photos.isNotEmpty()) {
            item {
              SectionHeader("图片")
            }
            items(
              items = photos,
              key = { it.path },
            ) { photo ->
              NetworkPhotoRow(
                file = photo,
                connectionId = connectionId,
                onClick = { onPhotoClick(photo) },
                onLongClick =
                  if (onFileLongClick == null) {
                    null
                  } else {
                    { offset -> onFileLongClick(photo, offset) }
                  },
                modifier = Modifier.animateItem(),
              )
            }
          }

          // Videos section
          if (videos.isNotEmpty()) {
            item {
              SectionHeader("视频")
            }
            items(
              items = videos,
              key = { it.path },
            ) { video ->
              // Only show card if connection is loaded
              connection?.let { conn ->
                NetworkVideoCard(
                  file = video,
                  connection = conn,
                  onClick = { onVideoClick(video) },
                  onLongClick =
                    if (onFileLongClick == null) {
                      null
                    } else {
                      { offset -> onFileLongClick(video, offset) }
                    },
                  modifier = Modifier.animateItem(),
                )
              }
            }
          }

          // Other files section（字幕 / nfo 等，纯展示）
          if (others.isNotEmpty()) {
            item {
              SectionHeader("其他文件")
            }
            items(
              items = others,
              key = { it.path },
            ) { other ->
              NetworkOtherFileRow(
                file = other,
                modifier = Modifier.animateItem(),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
  )
}

/**
 * 类型筛选按钮：一个「筛选」chip，点开小窗勾选类型。
 *
 * 为什么从四个 chip 收成一个按钮：四个 chip 一行放不下就横滚，还跟排序按钮
 * 抢控制条的位置；而且「显示全部」这种派生态放在 chip 上，语义也绕。
 * 收成按钮后控制条只剩两个紧凑控件，勾选观感参考 Emby 媒体库的筛选
 * （真 Checkbox + 整行可点）：
 * - **多选不关窗**：勾一项菜单留着，连勾几项再点外面收；
 * - 「显示全部」= 三项全开的**派生状态**（不单独存）：勾它 = 三项全勾，
 *   取消任一项它自动灭；
 * - 有筛选生效时按钮主色高亮 —— 一眼看得出「现在显示的不是全部内容」。
 * 字幕 / nfo 等其他文件只出现在列表视图，不归这三项管。
 */
@Composable
private fun NetworkTypeFilterButton(
  showFolders: Boolean,
  showPhotos: Boolean,
  showVideos: Boolean,
  onShowFoldersChange: (Boolean) -> Unit,
  onShowPhotosChange: (Boolean) -> Unit,
  onShowVideosChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val anyHidden = !(showFolders && showPhotos && showVideos)

  Box(modifier = modifier) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color =
        if (anyHidden) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant
        },
      contentColor =
        if (anyHidden) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { menuExpanded = true }
          .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.FilterList,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "筛选",
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
    DropdownMenu(
      expanded = menuExpanded,
      onDismissRequest = { menuExpanded = false },
    ) {
      // 「显示全部」派生：三项全开才选中。勾选 ⇒ 三项全勾；取消 ⇒ 三项全清
      NetworkFilterDropdownRow(
        text = "显示全部",
        checked = showFolders && showPhotos && showVideos,
        onCheckedChange = { checked ->
          onShowFoldersChange(checked)
          onShowPhotosChange(checked)
          onShowVideosChange(checked)
        },
      )
      NetworkFilterDropdownRow(
        text = "显示文件夹",
        checked = showFolders,
        onCheckedChange = onShowFoldersChange,
      )
      NetworkFilterDropdownRow(
        text = "显示图片",
        checked = showPhotos,
        onCheckedChange = onShowPhotosChange,
      )
      NetworkFilterDropdownRow(
        text = "显示媒体",
        checked = showVideos,
        onCheckedChange = onShowVideosChange,
      )
    }
  }
}

/**
 * 筛选菜单里的一行：真 Checkbox + 文字，整行可点（与 Emby 媒体库
 * 的筛选行同款）。onClick **不关菜单** —— 多选语义，连勾几项再点外面收。
 */
@Composable
private fun NetworkFilterDropdownRow(
  text: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * 排序控件：一个 chip（Sort 图标 + 维度名 + ↑/↓），点开下拉菜单换维度。
 *
 * 范式对齐 Emby 媒体库的排序（`SortMenu`）：
 * - **方向包在类型标签里**：chip 上的 ↑/↓ 直接显示当前方向（随机没有方向，
 *   不显示）；菜单里选中项的标签 append " ↑"/" ↓" + 主色高亮，不用勾；
 * - **方向不单列菜单项**：再点一次已选中项 = 只翻方向；点其他项 = 换维度
 *   （ViewModel 走该维度的自然方向）；
 * - 随机再点一次菜单里的「随机」= 换一批顺序（新种子，ViewModel 处理）。
 */
@Composable
private fun NetworkSortButton(
  sortType: NetworkSortType,
  sortOrder: SortOrder,
  onSortTypeChange: (NetworkSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val hasDirection = sortType != NetworkSortType.Random

  Box {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { menuExpanded = true }
          .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Sort,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = sortType.label,
          style = MaterialTheme.typography.labelLarge,
        )
        // 升降序指示包在类型标签里面：随机排序没有方向，不显示
        if (hasDirection) {
          Spacer(modifier = Modifier.width(2.dp))
          Icon(
            imageVector =
              if (sortOrder.isAscending) {
                Icons.Filled.ArrowUpward
              } else {
                Icons.Filled.ArrowDownward
              },
            contentDescription =
              if (sortOrder.isAscending) "当前为升序" else "当前为降序",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
    DropdownMenu(
      expanded = menuExpanded,
      onDismissRequest = { menuExpanded = false },
    ) {
      NetworkSortType.entries.forEach { type ->
        val selected = type == sortType
        DropdownMenuItem(
          text = {
            Text(
              text = buildString {
                append(type.label)
                // 选中项直接带方向后缀（随机除外）；不单列方向选项
                if (selected && type != NetworkSortType.Random) {
                  append(if (sortOrder.isAscending) " ↑" else " ↓")
                }
              },
              style = MaterialTheme.typography.bodyMedium,
              color =
                if (selected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          },
          onClick = {
            menuExpanded = false
            when {
              // 随机再点一次 = 换一批顺序（ViewModel 的 setSortType 同维度重复点即换种子）
              selected && type == NetworkSortType.Random -> onSortTypeChange(type)
              // 其他维度再点一次已选中项 = 只翻方向
              selected ->
                onSortOrderChange(
                  if (sortOrder.isAscending) SortOrder.Descending else SortOrder.Ascending,
                )
              // 点其他项 = 换维度（ViewModel 走自然方向）
              else -> onSortTypeChange(type)
            }
          },
        )
      }
    }
  }
}
