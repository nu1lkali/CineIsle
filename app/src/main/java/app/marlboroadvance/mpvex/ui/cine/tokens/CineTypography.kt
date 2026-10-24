package app.marlboroadvance.mpvex.ui.cine.tokens

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Cine 字阶。
 *
 * ## 为什么不直接改 `AppTypography`
 * 老界面的行高是按 M3 默认字阶调出来的（列表项高度、卡片两行文字、顶栏基线）。
 * 一旦全局换字阶，老界面会**整体错位 1~3px**，那不是本次改动的目的，也没人要求。
 * 所以 Cine 自带一套，只通过 [LocalCineTypography] 提供给 Cine 子树；
 * 老页面继续吃 `MaterialTheme.typography`，两边互不影响。
 *
 * ## 与 M3 默认的三点差异（设计稿 3.2）
 * 1. **标题更重、字距更紧**：中文大标题在 W400 下会显得散，W700 + 负字距才「压得住」Hero。
 * 2. **正文更小一点（13.5）**：Cine 信息密度低、行距大，13.5 比 14 更耐看。
 * 3. **元信息不靠字号拉对比**：`labelMedium` 12sp、`bodySmall` 11.5sp 与正文差得不多，
 *    层级差异交给**字重和颜色**（`onSurfaceVariant`），避免屏幕上一堆极小字。
 */
@Immutable
data class CineTypographySet(
  /** Hero 主标题 40/700/-1.2 */
  val heroTitle: TextStyle = TextStyle(
    fontSize = 40.sp,
    lineHeight = 44.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = (-1.2).sp,
  ),
  /** 页面级标题 26/600/-0.6（搜索页、收藏页顶部） */
  val pageTitle: TextStyle = TextStyle(
    fontSize = 26.sp,
    lineHeight = 31.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = (-0.6).sp,
  ),
  /** 分区标题 20/600/-0.2 */
  val sectionTitle: TextStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 25.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = (-0.2).sp,
  ),
  /**
   * 二级页顶栏里的页面标题 17/600。
   *
   * 不直接用 [pageTitle]（26sp）：44dp 高的顶栏塞 26sp 会顶满上下边距，
   * 视觉上比内容还重 —— 二级页的主角是海报，不是标题。
   */
  val pageBarTitle: TextStyle = TextStyle(
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = (-0.2).sp,
  ),
  /** 列表主标题 15/600（继续观看卡的图下标题） */
  val itemTitle: TextStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 19.sp,
    fontWeight = FontWeight.W600,
  ),
  /**
   * 海报卡压在图上的一行标题 12.5/600。
   *
   * ⚠️ 这只是**基准**：真实字号由 `CineFitTitleStyle` 按可用宽度测量后决定
   * （长标题会降到 11.5 / 10.5，见 CineCards.kt 的算法说明）。
   */
  val posterTitle: TextStyle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 15.5.sp,
    fontWeight = FontWeight.W600,
  ),
  /** 卡片图下元信息 11.5/500 */
  val cardMeta: TextStyle = TextStyle(
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.W500,
  ),
  /** 正文 13.5/400（空态说明、设置项描述） */
  val body: TextStyle = TextStyle(
    fontSize = 13.5.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.W400,
  ),
  /** 元信息 12/500/+0.2（Hero 的年份 · 类型 · 时长） */
  val meta: TextStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.W500,
    letterSpacing = 0.2.sp,
  ),
  /** 角标 10/700/+0.7 */
  val badge: TextStyle = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = 0.7.sp,
  ),
  /** 按钮 / Chip 文字 14.5/600 */
  val button: TextStyle = TextStyle(
    fontSize = 14.5.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.W600,
  ),
)

@Suppress("CompositionLocalAllowlist")
val LocalCineTypography = staticCompositionLocalOf { CineTypographySet() }

/**
 * 给 M3 组件（BottomSheet 标题、AlertDialog 正文等）用的 Typography。
 *
 * 只在 Cine 子树里通过 `MaterialTheme(typography = ...)` 局部覆盖，
 * 老界面完全不经过这里。
 */
internal fun cineMaterialTypography(base: Typography = Typography()): Typography = base.copy(
  headlineMedium = base.headlineMedium.copy(
    fontSize = 26.sp,
    lineHeight = 31.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = (-0.6).sp,
  ),
  titleLarge = base.titleLarge.copy(
    fontSize = 20.sp,
    lineHeight = 25.sp,
    fontWeight = FontWeight.W600,
  ),
  titleMedium = base.titleMedium.copy(
    fontSize = 15.sp,
    lineHeight = 19.sp,
    fontWeight = FontWeight.W600,
  ),
  bodyMedium = base.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 21.sp),
  labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
)
