package app.marlboroadvance.mpvex.ui.cine.local

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.media.model.VideoFolder
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.FolderSortType
import app.marlboroadvance.mpvex.preferences.FolderViewMode
import app.marlboroadvance.mpvex.preferences.FoldersPreferences
import app.marlboroadvance.mpvex.preferences.MediaLayoutMode
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.filesystem.FileSystemBrowserRootScreen
import app.marlboroadvance.mpvex.ui.browser.folderlist.FolderListViewModel
import app.marlboroadvance.mpvex.ui.browser.sheets.PlayLinkSheet
import app.marlboroadvance.mpvex.ui.browser.videolist.VideoListScreen
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetFrame
import app.marlboroadvance.mpvex.ui.cine.components.CineCoverTile
import app.marlboroadvance.mpvex.ui.cine.components.CineFileRow
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineSearchField
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import app.marlboroadvance.mpvex.utils.permission.PermissionUtils
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 版「本地」tab。
 *
 * ## 两套视图，一套壳
 * - `FolderViewMode.AlbumView`（默认）：Cine 自己画的文件夹瓦片 / 行，玻璃顶栏 + 搜索 +
 *   排序 + 更多菜单；
 * - `FolderViewMode.FileManager`：整棵树形文件管理器（面包屑、逐层下钻、隐藏文件开关）
 *   是另一套导航范式，重画等于重做一个文件管理器 —— 这里整体交给 Classic 的
 *   [FileSystemBrowserRootScreen]，外面套一层模糊源，保证 Dock 的玻璃在这条分支上不退化。
 *
 * ## 数据一条没改
 * 文件夹列表 / 新增计数 / 扫描状态全部来自 Classic 的 [FolderListViewModel]，
 * 排序仍走 [SortUtils]，黑名单仍写 [FoldersPreferences.blacklistedFolders] ——
 * 老用户切回 Classic 看到的还是同一份数据、同一套排序结果。
 *
 * ## Classic 的 FAB 三项搬到了「更多」菜单
 * 打开文件 / 最近播放 / 打开链接本来挤在一颗展开式 FAB 里（还会随滚动消失），
 * 这里收进顶栏的 `⋮` 抽屉 —— 低频操作不该占着屏幕右下角。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CineLocalScreen(modifier: Modifier = Modifier) {
  val prefs = koinInject<BrowserPreferences>()
  val folderViewMode by prefs.folderViewMode.collectAsState()

  // 树形模式：整页沿用 Classic（见 KDoc）。这里只补模糊源，不改老页面一个字节。
  if (folderViewMode == FolderViewMode.FileManager) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .cineHazeSource(LocalCineHaze.current),
    ) {
      FileSystemBrowserRootScreen.Content()
    }
    return
  }

  CineAlbumFolderBrowser(modifier = modifier)
}

/** 相册视图（按文件夹聚合）。 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CineAlbumFolderBrowser(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val prefs = koinInject<BrowserPreferences>()
  val foldersPrefs = koinInject<FoldersPreferences>()

  val viewModel: FolderListViewModel = viewModel(
    factory = FolderListViewModel.factory(context.applicationContext as Application),
  )

  val folders by viewModel.videoFolders.collectAsState()
  val newCounts by viewModel.foldersWithNewCount.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val scanStatus by viewModel.scanStatus.collectAsState()
  val loaded by viewModel.hasCompletedInitialLoad.collectAsState()
  val wereDeleted by viewModel.foldersWereDeleted.collectAsState()

  val layoutMode by prefs.mediaLayoutMode.collectAsState()
  val sortType by prefs.folderSortType.collectAsState()
  val sortOrder by prefs.folderSortOrder.collectAsState()
  val columnsPortrait by prefs.folderGridColumnsPortrait.collectAsState()
  val columnsLandscape by prefs.folderGridColumnsLandscape.collectAsState()
  val isLandscape = LocalConfiguration.current.orientation ==
    android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val columns = if (isLandscape) columnsLandscape else columnsPortrait

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var searchOpen by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var sortOpen by remember { mutableStateOf(false) }
  var moreOpen by remember { mutableStateOf(false) }
  var pendingHide by remember { mutableStateOf<VideoFolder?>(null) }
  var showLink by remember { mutableStateOf(false) }

  // 回到本页（从文件夹里退出来）时重算「新增」角标 —— 与 Classic 同一时机
  val backstackSize = backStack.size
  LaunchedEffect(backstackSize) { viewModel.recalculateNewVideoCounts() }

  val filePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    uri?.let {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          it,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
      MediaUtils.playFile(it.toString(), context, "open_file")
    }
  }

  val sorted = remember(folders, sortType, sortOrder) {
    SortUtils.sortFolders(folders, sortType, sortOrder)
  }
  val visible = remember(sorted, query) {
    val keyword = query.trim()
    if (keyword.isEmpty()) {
      sorted
    } else {
      sorted.filter {
        it.name.contains(keyword, ignoreCase = true) ||
          it.path.contains(keyword, ignoreCase = true)
      }
    }
  }
  val countMap = remember(newCounts) {
    newCounts.associate { it.folder.bucketId to it.newVideoCount }
  }

  val permissionState = PermissionUtils.handleStoragePermission(
    onPermissionGranted = { viewModel.refresh() },
  )
  val granted = permissionState.status is PermissionStatus.Granted

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = "本地",
      actions = {
        CineIconButton(
          icon = Icons.Outlined.Search,
          contentDescription = "搜索文件夹",
          tint = if (searchOpen) colors.sectionAction else colors.onSurfaceVariant,
          onClick = {
            searchOpen = !searchOpen
            if (!searchOpen) query = ""
          },
        )
        CineIconButton(
          icon = Icons.AutoMirrored.Filled.Sort,
          contentDescription = "排序",
          tint = colors.onSurfaceVariant,
          onClick = { sortOpen = true },
        )
        CineIconButton(
          icon = Icons.Filled.MoreVert,
          contentDescription = "更多",
          tint = colors.onSurfaceVariant,
          onClick = { moreOpen = true },
        )
      },
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .cineHazeSource(LocalCineHaze.current),
    ) {
      if (searchOpen) {
        CineSearchField(
          query = query,
          onQueryChange = { query = it },
          onClose = {
            searchOpen = false
            query = ""
          },
          placeholder = "搜索文件夹名 / 路径",
          modifier = Modifier.padding(horizontal = space.screenH, vertical = 10.dp),
        )
      }

      when {
        !granted -> CineStatePanel(
          icon = Icons.Filled.Folder,
          title = "需要存储权限",
          description = "授权后才能扫描本机的视频文件夹",
          primaryLabel = "去授权",
          onPrimary = { permissionState.launchPermissionRequest() },
        )

        isLoading && !loaded -> Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
              color = colors.progressFill,
              modifier = Modifier.size(30.dp),
              strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
              text = scanStatus ?: "正在扫描视频…",
              style = type.cardMeta,
              color = colors.onSurfaceVariant,
            )
          }
        }

        visible.isEmpty() -> CineStatePanel(
          icon = Icons.Filled.Folder,
          title = if (query.isNotBlank()) "没有匹配的文件夹" else "还没有视频文件夹",
          description = if (query.isNotBlank()) {
            "换个关键词试试，搜索会同时匹配文件夹名和路径"
          } else {
            "把视频放进设备后回到这里下拉刷新即可"
          },
          primaryLabel = "重新扫描",
          onPrimary = { viewModel.refresh() },
        )

        else -> {
          Text(
            text = buildString {
              append("${visible.size} 个文件夹 · ${visible.sumOf { it.videoCount }} 个视频")
              if (scanStatus != null) append(" · 正在补充元信息…")
            },
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(
              start = space.screenH,
              end = space.screenH,
              top = 6.dp,
              bottom = 10.dp,
            ),
          )

          if (layoutMode == MediaLayoutMode.GRID) {
            LazyVerticalGrid(
              columns = GridCells.Fixed(columns),
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(
                start = space.screenH,
                end = space.screenH,
                bottom = space.contentBottomInset,
              ),
              horizontalArrangement = Arrangement.spacedBy(space.itemGap),
              verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
              items(visible, key = { it.bucketId }) { folder ->
                CineCoverTile(
                  title = folder.name,
                  subtitle = "${folder.videoCount} 个视频",
                  icon = Icons.Filled.Folder,
                  badge = countMap[folder.bucketId]?.takeIf { it > 0 }?.let { "+$it" },
                  onClick = {
                    backStack.add(VideoListScreen(folder.bucketId, folder.name))
                  },
                  onLongClick = { pendingHide = folder },
                )
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(
                start = space.screenH,
                end = space.screenH,
                bottom = space.contentBottomInset,
              ),
            ) {
              items(visible, key = { it.bucketId }) { folder ->
                CineFileRow(
                  title = folder.name,
                  meta = folder.path,
                  icon = Icons.Filled.Folder,
                  badge = countMap[folder.bucketId]?.takeIf { it > 0 }?.let { "+$it" },
                  onClick = {
                    backStack.add(VideoListScreen(folder.bucketId, folder.name))
                  },
                  onLongClick = { pendingHide = folder },
                )
              }
            }
          }
        }
      }
    }
  }

  if (sortOpen) {
    CineSheetFrame(
      title = "排序方式",
      onDismissRequest = { sortOpen = false },
      dismissLabel = "关闭",
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
          FolderSortType.Title to Icons.Filled.Title,
          FolderSortType.Date to Icons.Filled.CalendarToday,
          FolderSortType.Size to Icons.Filled.SwapVert,
        ).forEach { (item, icon) ->
          val selected = item == sortType
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(if (selected) colors.chipSurface else colors.surface.copy(alpha = 0f))
              .clickable {
                if (selected) {
                  prefs.folderSortOrder.set(
                    if (sortOrder.isAscending) SortOrder.Descending else SortOrder.Ascending,
                  )
                } else {
                  prefs.folderSortType.set(item)
                }
                sortOpen = false
              }
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = if (selected) colors.sectionAction else colors.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
              text = item.label,
              style = type.itemTitle,
              color = if (selected) colors.sectionAction else colors.onSurface,
              modifier = Modifier.weight(1f),
            )
            if (selected) {
              Text(
                text = if (sortOrder.isAscending) "升序" else "降序",
                style = type.cardMeta,
                color = colors.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }

  if (moreOpen) {
    CineSheetFrame(
      title = "更多",
      onDismissRequest = { moreOpen = false },
      dismissLabel = "关闭",
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CineMenuItem(Icons.Filled.FolderOpen, "打开文件") {
          moreOpen = false
          filePicker.launch(arrayOf("video/*"))
        }
        CineMenuItem(Icons.Filled.History, "最近播放") {
          moreOpen = false
          scope.launch {
            val last = RecentlyPlayedOps.getRecentlyPlayed(limit = 1).firstOrNull()
            if (last != null) {
              MediaUtils.playFile(last.filePath, context, "recently_played_button")
            } else {
              Toast.makeText(context, "还没有播放记录", Toast.LENGTH_SHORT).show()
            }
          }
        }
        CineMenuItem(Icons.Filled.Link, "打开链接") {
          moreOpen = false
          showLink = true
        }
        CineMenuItem(
          icon = if (layoutMode == MediaLayoutMode.GRID) {
            Icons.AutoMirrored.Filled.ViewList
          } else {
            Icons.Filled.GridView
          },
          label = if (layoutMode == MediaLayoutMode.GRID) "切换为列表" else "切换为网格",
        ) {
          moreOpen = false
          prefs.mediaLayoutMode.set(
            if (layoutMode == MediaLayoutMode.GRID) {
              MediaLayoutMode.LIST
            } else {
              MediaLayoutMode.GRID
            },
          )
        }
        CineMenuItem(Icons.Filled.Refresh, "重新扫描") {
          moreOpen = false
          viewModel.refresh()
        }
        CineMenuItem(Icons.Filled.Settings, "设置") {
          moreOpen = false
          backStack.add(PreferencesScreen)
        }
      }
    }
  }

  // 长按 = 隐藏这个文件夹（黑名单），确认后才写偏好
  pendingHide?.let { folder ->
    CineSheetFrame(
      title = "隐藏文件夹",
      onDismissRequest = { pendingHide = null },
      confirmLabel = "隐藏",
      onConfirm = {
        val blacklist = foldersPrefs.blacklistedFolders.get().toMutableSet()
        blacklist.add(folder.path)
        foldersPrefs.blacklistedFolders.set(blacklist)
        pendingHide = null
        viewModel.refresh()
        Toast.makeText(context, "已隐藏「${folder.name}」", Toast.LENGTH_SHORT).show()
      },
    ) {
      Text(
        text = "「${folder.name}」将不再出现在本地列表里。",
        style = type.itemTitle,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "只影响显示，不会删除任何视频文件；可在 设置 → 文件夹 中恢复。",
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
      )
    }
  }

  PlayLinkSheet(
    isOpen = showLink,
    onDismiss = { showLink = false },
    onPlayLink = { url -> MediaUtils.playFile(url, context, "play_link") },
  )
}

/** 抽屉里的一行：图标 + 文案，选中态只靠图标色区分，不加箭头（这些是动作不是跳转）。 */
@Composable
private fun CineMenuItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(19.dp),
    )
    Spacer(Modifier.size(12.dp))
    Text(text = label, style = type.itemTitle, color = colors.onSurface)
  }
}
