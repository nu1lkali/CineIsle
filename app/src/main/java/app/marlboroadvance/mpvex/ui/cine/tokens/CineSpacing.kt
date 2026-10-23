package app.marlboroadvance.mpvex.ui.cine.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Cine 尺寸与间距。
 *
 * 与 [app.marlboroadvance.mpvex.ui.theme.Spacing]（4/8/12/16/24/32/48/64 的通用梯度）
 * 并存而不是替换：通用梯度服务老界面，这里只放 Cine 布局真正依赖的**语义值**。
 * 混用的后果很具体 —— 「Hero 高度」「Dock 高度」这类数字一旦散落在各文件里，
 * 改一次要全项目搜索，还必然漏一处。
 *
 * ⚠️ 卡片圆角**不在这里**：它必须跟随用户的「卡片圆角」偏好（0~24dp），
 * 走 `LocalCardCorner`，Cine 不做任何覆盖（设计决策 3）。
 */
@Immutable
data class CineSpacing(
  /** 页面水平边距（Classic 是 16，Cine 更宽气一点） */
  val screenH: Dp = 20.dp,
  /** 区块之间的垂直间隔（Classic 是 16） */
  val sectionGap: Dp = 36.dp,
  /** 分区标题与内容之间 */
  val sectionHeadGap: Dp = 12.dp,
  /** 第一个分区距 Hero 的顶部留白 */
  val firstSectionTop: Dp = 26.dp,
  /** 横滑卡片之间的间距 */
  val itemGap: Dp = 12.dp,
  /** 手机海报宽（2:3）；353dp 可视宽恰好露出 2.4 张，暗示可滑 */
  val posterWidth: Dp = 132.dp,
  /** ≥600dp 时的海报宽 */
  val posterWidthWide: Dp = 168.dp,
  /** 继续观看卡（16:9）宽 */
  val wideCardWidth: Dp = 212.dp,
  /** Dock 高度（胶囊） */
  val dockHeight: Dp = 64.dp,
  /** Dock 距屏幕左右 */
  val dockInset: Dp = 16.dp,
  /** Dock 距屏幕底（手势条由窗口 inset 另算） */
  val dockBottom: Dp = 14.dp,
  /** Rail 宽度（≥600dp） */
  val railWidth: Dp = 88.dp,
  /** Hero 基准高度；实际会按屏高夹到 62% */
  val heroHeight: Dp = 532.dp,
  /** 列表底部为 Dock 预留的空隙（= Dock 高度 + 边距 + 呼吸） */
  val contentBottomInset: Dp = 96.dp,
  /** 折叠顶栏高度 */
  val topBarHeight: Dp = 52.dp,
  /** 宽屏固定顶栏高度（内容靠它算下移量，改这里两处一起变） */
  val wideHeaderHeight: Dp = 64.dp,
) {
  /** 海报宽高比 2:3 */
  val posterRatio: Float = 1.5f

  /** 继续观看卡 16:9 */
  val wideRatio: Float = 9f / 16f

  /** 该宽度下海报的实际宽度 */
  fun posterWidth(wide: Boolean): Dp = if (wide) posterWidthWide else posterWidth
}

@Suppress("CompositionLocalAllowlist")
val LocalCineSpacing = staticCompositionLocalOf { CineSpacing() }
