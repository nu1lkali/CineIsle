package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.FolderViewMode
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
 * - **顶栏三件套**（[BrowserTopBar.additionalActions]）：收藏当前目录（星形）、
 *   随机播放（视频 / 图片二选一）、视图切换。
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

    // 视图模式：与本地文件夹浏览共用同一偏好（AlbumView=网格 / FileManager=列表）
    val browserPreferences = koinInject<BrowserPreferences>()
    val folderViewMode by browserPreferences.folderViewMode.collectAsState()
    val isGridView = folderViewMode == FolderViewMode.AlbumView

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }

    // 随机播放下拉菜单（视频 / 图片）
    var showRandomMenu by remember { mutableStateOf(false) }

    // 友好提示 Toast：复用同一个实例、每次先 cancel，连点不排队
    // （与 Emby 媒体库快捷收藏同一手法）
    val toastRef = remember { mutableStateOf<Toast?>(null) }
    fun toast(msg: String) {
      toastRef.value?.cancel()
      toastRef.value = Toast.makeText(context, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // 当前目录显示名（查看器标题 / 收藏名同一口径）
    val dirTitle =
      currentPath.trimEnd('/').substringAfterLast('/', "").ifBlank { connectionName }

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    BackHandler {
      backstack.removeLastOrNull()
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = connectionName,
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onBackClick = { backstack.removeLastOrNull() },
          onCancelSelection = {},
          onSortClick = null,
          onSearchClick = null,
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
            // ── 收藏当前目录 ──
            // 星形点一下即切，状态来自 ViewModel（loadFiles 时同源刷新），
            // 主界面「收藏的目录」section 与这里永远一致。
            IconButton(
              onClick = { viewModel.toggleFavoriteCurrent() },
              modifier = Modifier.padding(horizontal = 2.dp),
            ) {
              Icon(
                imageVector =
                  if (isCurrentFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (isCurrentFavorite) "取消收藏" else "收藏此目录",
                modifier = Modifier.size(24.dp),
                tint =
                  if (isCurrentFavorite) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.secondary
                  },
              )
            }

            // ── 随机播放（视频 / 图片） ──
            // 一个入口两项：目录经常是混合的，用户想要哪个随机哪个。
            // 没有对应类型时不静默失败 —— Toast 说清楚「没有」。
            Box {
              IconButton(
                onClick = { showRandomMenu = true },
                modifier = Modifier.padding(horizontal = 2.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Shuffle,
                  contentDescription = "随机播放",
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
              DropdownMenu(
                expanded = showRandomMenu,
                onDismissRequest = { showRandomMenu = false },
              ) {
                DropdownMenuItem(
                  text = { Text("随机播放视频") },
                  leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                  onClick = {
                    showRandomMenu = false
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
                    showRandomMenu = false
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
              }
            }

            // ── 视图切换 ──
            // 图标指向**目标模式**（网格时显示列表图标），与播放列表页同一惯例
            IconButton(
              onClick = {
                browserPreferences.folderViewMode.set(
                  if (isGridView) FolderViewMode.FileManager else FolderViewMode.AlbumView,
                )
              },
              modifier = Modifier.padding(horizontal = 2.dp),
            ) {
              Icon(
                imageVector =
                  if (isGridView) {
                    Icons.AutoMirrored.Filled.ViewList
                  } else {
                    Icons.Default.GridView
                  },
                contentDescription = "切换视图",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      NetworkBrowserContent(
        files = files,
        connectionId = connectionId,
        isLoading = isLoading && files.isEmpty(),
        isRefreshing = isRefreshing,
        error = error,
        isGridView = isGridView,
        onRefresh = { viewModel.loadFiles() },
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
        modifier = Modifier.padding(padding),
      )
    }
  }
}

@Composable
private fun NetworkBrowserContent(
  files: List<NetworkFile>,
  connectionId: Long,
  isLoading: Boolean,
  isRefreshing: MutableState<Boolean>,
  error: String?,
  isGridView: Boolean,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onPhotoClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
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
  // 网格内容：文件夹在前（它们是导航），照片 + 视频混合按名称排 ——
  // 混合目录同屏混排正是这次改造的核心，不能再按类型分屏
  val gridItems = remember(files) {
    folders + (photos + videos).sortedBy { it.name.lowercase() }
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

    files.isEmpty() -> {
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
          message = "此目录下只有其他类型文件（字幕、nfo 等）；切换到列表视图可查看全部文件",
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
        modifier = modifier,
      )
    }
  }
}

/**
 * 网格视图：统一卡型混排（文件夹在前，照片 + 视频按名称混合）。
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
                onClick = { onFolderClick(folder) },
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
