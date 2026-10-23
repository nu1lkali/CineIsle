package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面风格。
 *
 * `Classic` = 现有的长列表式界面；`Cine` = 新的图像优先界面（见 docs/design/ui-2.0-proposal.md）。
 *
 * ⚠️ 默认必须是 [Classic]：老用户升级后界面**一像素都不能变**。这一点不能反过来 ——
 * 一旦默认成 Cine，所有升级用户都会被强行换界面，而 Cine 目前只覆盖首页。
 *
 * ⚠️ 枚举名即偏好里存的字符串（`preferenceStore.getEnum` 用 `name`），发布后不可改名。
 */
enum class UiStyle {
  Classic,
  Cine,
}

/**
 * 当前界面风格，由 `MpvexTheme` 之上的一层（`MainActivity.Navigator`）下发。
 *
 * 用途只有两处：
 * 1. `Navigator` 决定返回栈起点（`MainScreen` / `CineRoot`）；
 * 2. 需要在运行时判断「现在是不是新界面」的零散代码（如设置页的说明文案）。
 *
 * ⚠️ 具体页面**不要**读它来决定自己怎么画 —— 页面分流统一在 `CineRoutes.kt` 里做，
 * 页面自己再判一次就会出现「两个地方各有一套规则」的分裂。
 */
@Suppress("CompositionLocalAllowlist")
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Classic }
