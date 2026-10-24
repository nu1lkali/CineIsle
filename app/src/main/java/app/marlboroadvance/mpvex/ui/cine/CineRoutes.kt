package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.runtime.Composable
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen
import app.marlboroadvance.mpvex.ui.cine.detail.CineDetailScreen
import app.marlboroadvance.mpvex.ui.cine.library.CineLibraryScreen

/**
 * Cine 路由表：**只登记已经重做过的页面**，没登记的一律回落 Classic。
 *
 * ## 这是整套方案能分期发版的关键
 * 页面契约（`Screen` 数据类 + `Content()`）不变，所以分流可以放在渲染处：
 * `MainActivity` 的 `entryProvider` 里写一次 `if (Cine) CineRoute(route) { route.Content() }`，
 * 之后每重做一个页面，只需要在下面加一行 —— **不需要改任何老页面**，
 * 也不会出现「某条路由没人画」的黑屏。
 *
 * ## 哪些刻意仍然回落
 * - 演员作品页（`EmbyLibraryScreen.personId != null`）：它带 PersonIds 预置筛选，
 *   与媒体库的参数口径不同，硬塞进 [CineLibraryScreen] 会显示成全库；
 * - 服务器管理 / 聚合库 / 直播 / 合集 / 照片：低频页，重做收益低于风险（方案第 12、13 类）；
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
      // 演员作品页：参数口径不同，回落
      route.personId != null -> fallback()

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

    else -> fallback()
  }
}
