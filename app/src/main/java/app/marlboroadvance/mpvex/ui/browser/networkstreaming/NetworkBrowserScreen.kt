package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkFolderCard
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkMediaCard
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkOtherFileRow
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkPhotoRow
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkVideoCard
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.states.EmptyState
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.LazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarSettings
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
 *   收藏当前目录（切完弹 toast）、随机播放（视频 / 图片二选一）、视图切换，全部收在竖三点里
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
    // 当前协议是否支持重命名 / 删除（目前只有 AList）—— 决定卡片挂不挂长按
    val supportsWrite by viewModel.supportsWrite.collectAsState()

    // 视图模式：与本地文件夹浏览共用同一偏好（AlbumView=网格 / FileManager=列表）
    val browserPreferences = koinInject<BrowserPreferences>()
    val folderViewMode by browserPreferences.folderViewMode.collectAsState()
    val isGridView = folderViewMode == FolderViewMode.AlbumView

    // 文件夹封面开关 + 网格标题行数（设置 → 网络浏览）
    val folderCoverEnabled by browserPreferences.folderCoverEnabled.collectAsState()
    val gridTitleLines by browserPreferences.networkGridTitleLines.collectAsState()
    val titleMaxLines = gridTitleLines.maxLines

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }

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

    // 当前目录显示名（查看器标题 / 收藏名 / 顶栏标题同一口径）
    val dirTitle =
      currentPath.trimEnd('/').substringAfterLast('/', "").ifBlank { connectionName }

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

                // ── 随机播放（视频 / 图片） ──
                // 目录经常是混合的，两项都给，用户想要哪个随机哪个。
                // 没有对应类型时不静默失败 —— Toast 说清楚「没有」。
                DropdownMenuItem(
                  text = { Text("随机播放视频") },
                  leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
                    if (files.none { it.isVideoFile() }) {
                      toast("当前目录没有视频")
                    } else {
                      viewModel.playRandomVideos()
                    }
                  },
                )
                DropdownMenuItem(
                  text = { Text("随机播放图片") },
                  leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                  onClick = {
                    showOverflowMenu = false
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
                  },
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
              }
            }
          },
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

        NetworkBrowserContent(
          files = visibleFiles,
          connectionId = connectionId,
          isLoading = isLoading && files.isEmpty(),
          isRefreshing = isRefreshing,
          error = error,
          isGridView = isGridView,
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
          onFileLongClick =
            if (supportsWrite) {
              { file, offset ->
                actionTarget = file
                actionMenuAnchor = offset
              }
            } else {
              null
            },
          // 未过滤总数（筛选 / 搜索前的 files.size）——空态文案要区分
          // 「目录真空」和「全被滤掉」，只有过滤后的列表看不出来
          totalCount = files.size,
          modifier = Modifier.weight(1f),
        )
      }

      // ── 长按条目的操作菜单（重命名 / 删除）──
      //
      // **范式照抄 Emby 媒体库**：锚定在手指位置的浮动气泡菜单（`DropdownMenu`），
      // 不是居中的大卡片 —— AList 库与 Emby 媒体库除接口外结构一致，交互观感
      // 就该是同一套。（`EmbyItemActionsDialog` 也是这么写的：菜单本体是
      // DropdownMenu，需要输入 / 二次确认的动作才另开对话框。）
      //
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
            onDismissRequest = { actionTarget = null },
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
  onDismissRequest: () -> Unit,
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
  //
  // ⚠️ 这里原来写的是 `folders + (photos + videos).sortedBy { it.name.lowercase() }`，
  // 那是个隐蔽的 bug：`files` 从 ViewModel 出来时**已经**按用户选的排序键排过
  // （`networkFileComparator` = 文件夹恒在前 + 排序键 + 名称兜底），网格再按名称
  // 排一次 = 把用户的排序选择丢掉。表现就是「列表视图里排序生效、网格视图里没反应」。
  //
  // 而纯目录的那一层（如 TOKYO 下全是 e0xx 子目录）整个走 `folders +`、没被重排，
  // 看起来「排序是好的」，于是很容易误判成「只有某些文件夹排序失效」——实际是
  // 「只有媒体被重排」，跟在哪一层无关。
  //
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
      Box(
        modifier = modifier
          .fillMaxSize()
          .padding(bottom = 80.dp), // Account for bottom navigation bar
        contentAlignment = Alignment.Center,
      ) {
        app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(size = 48.dp)
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
  val gridState = rememberLazyGridState()
  val navigationBarHeight = LocalNavigationBarHeight.current

  val isAtTop by remember {
    derivedStateOf {
      gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems = items.size > 20
  val scrollbarAlpha by animateFloatAsState(
    targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
    animationSpec = tween(durationMillis = 200),
    label = "scrollbarAlpha",
  )

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
      LazyVerticalGridScrollbar(
        state = gridState,
        settings = ScrollbarSettings(
          thumbUnselectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * scrollbarAlpha),
          thumbSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
        ),
      ) {
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
  val networkListState = LazyListState()

  val isAtTop by remember {
    derivedStateOf {
      networkListState.firstVisibleItemIndex == 0 && networkListState.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems = (folders.size + photos.size + videos.size + others.size) > 20
  val scrollbarAlpha by animateFloatAsState(
    targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
    animationSpec = tween(durationMillis = 200),
    label = "scrollbarAlpha",
  )

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    listState = networkListState,
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(bottom = navigationBarHeight),
    ) {
      LazyColumnScrollbar(
        state = networkListState,
        settings = ScrollbarSettings(
          thumbUnselectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * scrollbarAlpha),
          thumbSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
        ),
      ) {
        LazyColumn(
          state = networkListState,
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
                modifier = Modifier,
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
                modifier = Modifier,
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
                  modifier = Modifier,
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
                modifier = Modifier,
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
 * 本地搜索输入框。
 *
 * 点顶栏放大镜出现：对**当前目录已拉到的内容**按名称关键词过滤，
 * 不发任何请求（目录列表本来就是全量一次拉完的）。
 * 打开即自动聚焦要键盘；尾部按钮有词先清词、无词才关搜索 ——
 * 清词比关搜索更常用，省得关掉再点放大镜。
 */
@Composable
private fun NetworkSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
) {
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    // 要焦点弹键盘。偶发节点还没挂上（重组时序）会抛异常，吞掉不影响使用
    runCatching { focusRequester.requestFocus() }
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.Search,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(12.dp))
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
          .weight(1f)
          .focusRequester(focusRequester),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
          color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
              Text(
                text = "搜索当前目录…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            innerTextField()
          }
        },
      )
      IconButton(
        onClick = {
          if (query.isNotEmpty()) onQueryChange("") else onClose()
        },
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = if (query.isNotEmpty()) "清空关键词" else "关闭搜索",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
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
