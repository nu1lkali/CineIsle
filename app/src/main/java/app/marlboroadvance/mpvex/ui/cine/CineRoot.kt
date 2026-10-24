package app.marlboroadvance.mpvex.ui.cine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLiveTvScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyOfflineScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen
import app.marlboroadvance.mpvex.ui.browser.emby.collection.EmbyCollectionScreen
import app.marlboroadvance.mpvex.ui.browser.emby.discover.EmbyDiscoverScreen
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoGridScreen
import app.marlboroadvance.mpvex.ui.browser.emby.playlist.EmbyPlaylistScreen
import app.marlboroadvance.mpvex.ui.browser.folderlist.FolderListScreen
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingScreen
import app.marlboroadvance.mpvex.ui.cine.components.CineDock
import app.marlboroadvance.mpvex.ui.cine.components.CineDrawerEntry
import app.marlboroadvance.mpvex.ui.cine.components.CineDrawerOverlay
import app.marlboroadvance.mpvex.ui.cine.components.CineRail
import app.marlboroadvance.mpvex.ui.cine.components.CineTab
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.list.CineFavoritesScreen
import app.marlboroadvance.mpvex.ui.cine.list.CineHistoryScreen
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack

/**
 * Cine 界面的根容器。
 *
 * ## 职责边界（很重要）
 * 1. 五个 tab 的**选中态**与导航（Dock / Rail 二选一）；
 * 2. 叠在 tab 之上的三层覆盖：侧边抽屉、全屏搜索、分区「全部」网格；
 * 3. 把 `LocalNavigationBarHeight` 下发给**回落进 Cine 外壳的 Classic 页面**。
 *
 * 它**不碰数据**：首页与覆盖层各自取 ViewModel，其余四个 tab 直接复用 Classic 的整页实现。
 *
 * ## 为什么只有首页是新的
 * 新 UI 的第一版只覆盖首页 —— 收藏 / 历史 / 本地 / 网络这四页的交互复杂（多选、排序、
 * 长按菜单、批量操作），在没有对应设计稿的情况下重画只会做出一个「像 Cine 但少功能」的
 * 半成品。所以这里直接调 Classic 的实现：**功能一个不少**，只是外观沿用老样式。
 * 这正是「新老共存」的意义 —— 逐步替换，而不是一次性推翻。
 *
 * ## 底部留空的传递
 * Classic 的这四个页面用 `LocalNavigationBarHeight` 给列表底部留出导航条的空间。
 * Cine 的 Dock 是浮起的（高 64 + 下边距 14），比 Classic 的 80dp 更高，
 * 所以这里下发 [app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing.contentBottomInset]（96dp）——
 * 少给的话，收藏页最后一行会被 Dock 压住。宽屏走 Rail，没有底部条，下发 0。
 */
@Composable
fun CineRoot(modifier: Modifier = Modifier) {
  val space = CineTheme.space
  val wide = rememberCineWide()
  val backStack = LocalBackStack.current

  // 0 = 首页（Cine），1..4 = 收藏 / 历史 / 本地 / 网络（Classic 实现）。
  // rememberSaveable：转屏、进程被回收后回来还停在原来那个 tab。
  var tabIndex by rememberSaveable { mutableStateOf(0) }
  var dockHidden by remember { mutableStateOf(false) }
  var searchOpen by remember { mutableStateOf(false) }
  // 侧边抽屉（老 UI AppBar 功能的收纳处）。⚠️ CineDrawerOverlay 要求常驻组合播进出场动画，
  // 所以这个状态驱动的是 overlay 的 visible 参数，不是 if 包不包它。
  var drawerOpen by remember { mutableStateOf(false) }
  // 「全部」网格：标题 + 当时那一批数据。**存快照而不是存个 id 再重查** ——
  // 网格只是「把这一行展开」，没必要再打一次服务端。
  var grid by remember { mutableStateOf<CineGridPayload?>(null) }

  val selectedTab = CineTab.entries.getOrElse(tabIndex) { CineTab.Home }

  fun selectTab(tab: CineTab) {
    tabIndex = tab.ordinal
    dockHidden = false
  }

  /** 媒体条目 → 详情：演员没有「详情」，得进 TA 的作品页 */
  fun openDetail(item: EmbyItem) {
    val id = item.Id ?: return
    if (item.Type == "Person") {
      backStack.add(
        EmbyPersonScreen(
          personId = id,
          personName = item.Name ?: "",
          personImageTag = item.ImageTags["Primary"],
        ),
      )
    } else {
      backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
    }
  }

  /** 媒体库入口：纯照片库直接进相册宫格（与 Classic 首页的口径一致） */
  fun openLibrary(library: EmbyItem) {
    val id = library.Id ?: return
    if (library.CollectionType?.equals("photos", ignoreCase = true) == true) {
      backStack.add(EmbyPhotoGridScreen(libraryId = id, title = library.Name ?: ""))
    } else {
      backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = library.Name ?: "",
          collectionType = library.CollectionType,
        ),
      )
    }
  }

  /** 抽屉功能项 → 跳转。全是 Classic 的整页实现（回落是设计内行为），跳完收抽屉 */
  fun openDrawerEntry(entry: CineDrawerEntry) {
    drawerOpen = false
    when (entry) {
      CineDrawerEntry.Downloads -> backStack.add(EmbyDownloadScreen)
      CineDrawerEntry.Offline -> backStack.add(EmbyOfflineScreen)
      CineDrawerEntry.LiveTv -> backStack.add(EmbyLiveTvScreen)
      CineDrawerEntry.Playlists -> backStack.add(EmbyPlaylistScreen)
      CineDrawerEntry.Discover -> backStack.add(EmbyDiscoverScreen)
      CineDrawerEntry.Collections -> backStack.add(EmbyCollectionScreen)
      CineDrawerEntry.ServerManage -> backStack.add(EmbyServerManageScreen)
      CineDrawerEntry.Settings -> backStack.add(PreferencesScreen)
    }
  }

  // 非首页 tab 上按返回 → 回首页，而不是直接退出 App。
  // 与经典底部导航的行为一致（经典那边切 tab，不参与返回栈）。
  BackHandler(enabled = tabIndex != 0 && !searchOpen && grid == null) {
    selectTab(CineTab.Home)
  }

  CompositionLocalProvider(
    LocalNavigationBarHeight provides if (wide) 0.dp else space.contentBottomInset,
  ) {
    Box(modifier = modifier.fillMaxSize()) {
      if (wide) {
        Row(modifier = Modifier.fillMaxSize()) {
          CineRail(
            selected = selectedTab,
            onSelect = ::selectTab,
            modifier = Modifier.fillMaxHeight(),
          )
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight(),
          ) {
            CineTabContent(
              tab = selectedTab,
              onOpenDetail = ::openDetail,
              onOpenLibrary = ::openLibrary,
              onOpenSearch = { searchOpen = true },
              onOpenDrawer = { drawerOpen = true },
              onOpenServerManage = { backStack.add(EmbyServerManageScreen) },
              onOpenAll = { title, items -> grid = CineGridPayload(title, items) },
              onDockHiddenChange = { dockHidden = it },
            )
          }
        }
      } else {
        CineTabContent(
          tab = selectedTab,
          onOpenDetail = ::openDetail,
          onOpenLibrary = ::openLibrary,
          onOpenSearch = { searchOpen = true },
          onOpenDrawer = { drawerOpen = true },
          onOpenServerManage = { backStack.add(EmbyServerManageScreen) },
          onOpenAll = { title, items -> grid = CineGridPayload(title, items) },
          onDockHiddenChange = { dockHidden = it },
        )

        // 覆盖层打开时不画 Dock：它要么被搜索页整块遮住、要么在网格页底下露出一角
        if (!searchOpen && grid == null) {
          CineDock(
            selected = selectedTab,
            onSelect = ::selectTab,
            hidden = dockHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }

      // 覆盖层最后画：确保盖住 Dock 与内容（包括 Rail 右侧的边界）
      if (searchOpen) {
        CineSearchOverlay(
          // 关掉覆盖层时把 Dock 复位：搜索是「临时插一层」，回来时底部导航该在
          onClose = {
            searchOpen = false
            dockHidden = false
          },
          onOpenDetail = ::openDetail,
        )
      }

      grid?.let { payload ->
        CineGridOverlay(
          title = payload.title,
          items = payload.items,
          onClose = {
            grid = null
            dockHidden = false
          },
          onOpenDetail = ::openDetail,
        )
      }

      // 侧边抽屉最后画（盖住 Dock 与内容）。⚠️ 常驻组合、visible 驱动进出场动画，
      // 不要改成 `if (drawerOpen)` 包裹 —— 关闭时组合直接消失，滑出动画永远不会播
      CineDrawerOverlay(
        visible = drawerOpen,
        onDismiss = { drawerOpen = false },
        onNavigate = ::openDrawerEntry,
      )
    }
  }
}

/**
 * tab 内容分发。
 *
 * ⚠️ 顺序必须与 [CineTab] 一致。新增 Cine 页面时改这里 + 加一个 `CineTab` 枚举项，
 * 不要在这一层写 `if (某个条件)` 的分支 —— 那会让「哪个 tab 是哪套界面」变得不可预测。
 */
@Composable
private fun CineTabContent(
  tab: CineTab,
  onOpenDetail: (EmbyItem) -> Unit,
  onOpenLibrary: (EmbyItem) -> Unit,
  onOpenSearch: () -> Unit,
  onOpenDrawer: () -> Unit,
  onOpenServerManage: () -> Unit,
  onOpenAll: (String, List<CineMediaUi>) -> Unit,
  onDockHiddenChange: (Boolean) -> Unit,
) {
  when (tab) {
    CineTab.Home -> CineHomeScreen(
      onOpenDetail = onOpenDetail,
      onOpenLibrary = onOpenLibrary,
      onOpenSearch = onOpenSearch,
      onOpenDrawer = onOpenDrawer,
      onOpenServerManage = onOpenServerManage,
      onOpenAll = onOpenAll,
      onDockHiddenChange = onDockHiddenChange,
    )

    // 收藏 / 历史已重做（M3）；本地与网络仍是文件系统页面，暂时回落 Classic 整页实现。
    // ⚠️ 回落页必须过一层 [CineHazeBacked]：Classic 页面不会把自己标成模糊源，
    // 缺了它浮起的 Dock 在「本地 / 网络」两个 tab 上就退化成一条实色带（v135 用户反馈）
    CineTab.Favorites -> CineFavoritesScreen()
    CineTab.History -> CineHistoryScreen()
    CineTab.Local -> CineHazeBacked { FolderListScreen.Content() }
    CineTab.Network -> CineHazeBacked { NetworkStreamingScreen.Content() }
  }
}

/**
 * 给**回落进 Cine 外壳的 Classic 整页**补一个模糊源。
 *
 * ## 为什么需要这一层
 * Cine 的玻璃（[app.marlboroadvance.mpvex.ui.cine.components.cineGlass]）靠
 * `LocalCineHaze` 取样，而模糊源要由**内容自己**用 `Modifier.cineHazeSource` 声明 ——
 * Cine 的几个页面都声明了，Classic 的 `FolderListScreen` / `NetworkStreamingScreen` 没有。
 * 于是同一个浮起 Dock：首页 / 收藏 / 历史有底可糊，切到本地 / 网络就只剩「半透明平涂」，
 * 看着像玻璃掉了。
 *
 * ## 为什么包一层 Box 而不是改 Classic 页面
 * `ui/browser/` 目录是老界面的地盘，「只影响新 UI」是这套方案的硬约束。
 * 在 Cine 的壳里补声明，老页面一个字节不动。
 */
@Composable
private fun CineHazeBacked(content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .cineHazeSource(LocalCineHaze.current),
  ) {
    content()
  }
}

/** 「全部」网格的载荷：标题 + 展开用的数据快照 */
private data class CineGridPayload(
  val title: String,
  val items: List<CineMediaUi>,
)
