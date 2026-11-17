package app.marlboroadvance.mpvex.ui.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Classic 外壳（底部导航 + 权限态）的页面级状态。
 *
 * 这里替代了原先挂在 [MainScreen] 单例上的三组 `MutableStateFlow`：
 * `isInSelectionMode`、`sharedVideoSelectionManager`、`shouldHideNavigationBar`。
 * 那些字段的问题是：
 *  · 选择管理器用 `Any?` 传，类型信息全靠调用方强转；
 *  · 状态是**进程级单例**，一个 Tab 忘记清理，另一个 Tab 的导航栏也跟着消失；
 *  · 选择模式本身已经由各页的 `SelectionManager` 管，外层再存一份只会不同步。
 *
 * 现在由 [MainScreen] 在组合里 `remember` 一份，经 [LocalClassicChrome] 下发；
 * 只有真正需要改外壳的页面（本地文件多选、收藏批量操作）才读写它。
 * 在 Cine 外壳里这个 Local 为 null —— 那里没有 Classic 底部导航，所有写入都是 no-op。
 */
@Stable
class ClassicChromeState {
  /** Classic 底部导航是否可见。默认可见。 */
  var bottomBarVisible by mutableStateOf(true)

  /** 存储权限被拒绝：本地页据此收起 FAB，避免点了又弹权限。 */
  var permissionDenied by mutableStateOf(false)
}

/**
 * Classic 外壳状态。
 *
 * 刻意用可空默认值：`FileSystemBrowserScreen` 同时被 Classic 与 Cine 复用，
 * Cine 根容器没有这层导航；提供 null 比提供一个假的全局单例更安全。
 */
val LocalClassicChrome = staticCompositionLocalOf<ClassicChromeState?> { null }
