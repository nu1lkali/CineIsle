package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.runtime.Composable
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLiveTvChannelScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLiveTvScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen
import app.marlboroadvance.mpvex.ui.cine.detail.CineDetailScreen
import app.marlboroadvance.mpvex.ui.cine.download.CineDownloadScreen
import app.marlboroadvance.mpvex.ui.cine.library.CineLibraryScreen
import app.marlboroadvance.mpvex.ui.cine.livetv.CineLiveTvGuideScreen
import app.marlboroadvance.mpvex.ui.cine.livetv.CineLiveTvScreen
import app.marlboroadvance.mpvex.ui.cine.person.CinePersonScreen
import app.marlboroadvance.mpvex.ui.cine.servers.CineServerManageScreen

/**
 * Cine 路由表：**只登记已经重做过的页面**，没登记的一律回落 Classic。
 *
 * ## 这是整套方案能分期发版的关键
 * 页面契约（`Screen` 数据类 + `Content()`）不变，所以分流可以放在渲染处：
 * `MainActivity` 的 `entryProvider` 里写一次 `if (Cine) CineRoute(route) { route.Content() }`，
 * 之后每重做一个页面，只需要在下面加一行 —— **不需要改任何老页面**，
 * 也不会出现「某条路由没人画」的黑屏。
 *
 * ## 已经迁到 Cine 的页面
 * 详情、媒体库、演员作品、直播频道/节目单、下载管理、服务器管理、本地、网络。
 * 本地与网络是 tab，不走路由表，见 `CineRoot.CineTabContent`。
 *
 * ## 哪些刻意仍然回落
 * - 聚合库 / 合集 / 播放列表 / 发现 / 离线内容 / 照片：结构与媒体库不同，
 *   要么有自己的一套聚合口径，要么（照片）是独立的查看器壳子，逐个重做收益低于风险；
 * - 网络目录浏览（`NetworkBrowserScreen`）：协议栈 + 面包屑 + 多选，属于另一个页面，
 *   根页（连接列表）已 Cine 化，浏览页后续再迁；
 * - 播放器相关页面：本方案完全不涉及。
 *
 * 回落到 Classic 是**设计内的行为**：功能一个不少，只是外观沿用老样式。
 *
 * ## ⚠️ 每条 Cine 路由必须自带 [CineTheme]
 * `CineTheme` 在 `MainScreen` 里只包住了底部 tab 的 `CineRoot`。详情页 / 媒体库页是
 * **独立的导航 entry**，由 `NavDisplay` 直接渲染，**不在 `CineRoot` 子树内** ——
 * 少包一层 `CineTheme` 就会在组合第一帧抛
 * `IllegalStateException: CineColors 未提供`（v129 线上崩溃真凶）。
 *
 * 回落分支**不**包 `CineTheme`：Classic 页面应当用原样的 M3 字阶。
 */
@Composable
fun CineRoute(
  route: Screen,
  fallback: @Composable () -> Unit,
) {
  when (route) {
    is EmbyDetailScreen -> CineTheme {
      CineDetailScreen(itemId = route.itemId, title = route.title)
    }

    is EmbyLibraryScreen -> when {
      // 演员作品页：带 PersonIds 预置筛选，走人员页而不是媒体库页
      route.personId != null -> CineTheme {
        CinePersonScreen(
          personId = route.personId,
          personName = route.personName ?: route.title,
        )
      }

      else -> CineTheme {
        CineLibraryScreen(
          libraryId = route.libraryId,
          title = route.title,
          collectionType = route.collectionType,
          includeItemTypes = route.includeItemTypes,
          folderDive = route.isFolderDive,
        )
      }
    }

    is EmbyPersonScreen -> CineTheme {
      CinePersonScreen(
        personId = route.personId,
        personName = route.personName,
        personImageTag = route.personImageTag,
      )
    }

    is EmbyLiveTvScreen -> CineTheme { CineLiveTvScreen() }

    is EmbyLiveTvChannelScreen -> CineTheme { CineLiveTvGuideScreen(route.channel) }

    is EmbyDownloadScreen -> CineTheme { CineDownloadScreen() }

    is EmbyServerManageScreen -> CineTheme { CineServerManageScreen() }

    else -> fallback()
  }
}
