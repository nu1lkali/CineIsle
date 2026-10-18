package app.marlboroadvance.mpvex.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * 全 App 卡片圆角（本地文件、网络视频、Emby 媒体卡片统一使用）。
 *
 * 为什么单独开一个 CompositionLocal 而不是复用 `EmbyAppearance.corner`：
 * 本地/网络卡片（FolderCard / VideoCard / NetworkMediaCard…）跟 Emby 没有任何
 * 业务关系，让它们反向依赖 `ui/browser/emby/components` 里的类型属于错误的分层
 * 方向。这里的值同样由主题层从「卡片圆角」这一项偏好下发，因此最终仍然是
 * **一个设置项、全 App 生效**，不存在两套圆角。
 *
 * 默认 12.dp 与偏好默认值一致，保证在预览/未套主题的场景下不会突然变成直角。
 */
@Suppress("CompositionLocalAllowlist")
val LocalCardCorner = staticCompositionLocalOf { 12.dp }
