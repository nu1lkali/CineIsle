package app.marlboroadvance.mpvex.ui.cine.tokens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Cine 语义色。
 *
 * ## 为什么要有这一层
 * Cine 需要一批「压在图上的暗层 / 玻璃层 / 角标」语义色，而 Material 的 colorScheme 里
 * 没有对应角色。直接在每个组件里写 `copy(alpha = ..)` 会出现「同一个压图渐变在三个文件里
 * 各写一遍、其中一个 alpha 不一样」的经典问题 —— 用户换主题时看不出来，改设计时全崩。
 *
 * 所以：**所有派生规则只写在这里**，组件一律只读 token。
 *
 * ## 三条推导原则
 * 1. **能用主题色的，绝不写色值**：`badgeStrong` = `tertiaryContainer`、
 *    `progressFill` = `primary`、`glassBorder` = `outlineVariant`。换任意主题都跟着走。
 * 2. **压在图上的暗层与主题无关**（浅色主题下也是暗的）：海报 / Hero 的图片本身是暗的，
 *    浅色主题若把 scrim 换成白色，白字压白雾直接不可读。设计稿 2.1 明确要求这一条，
 *    因此这些层用 [Color.Black] 的 alpha 阶梯表达 —— 它是「压暗」这一操作的基元，
 *    不是调色板里的颜色。
 * 3. **玻璃层随明暗翻转**：深色用 `surfaceContainer`，浅色用 `surface`（否则在浅底上发灰，
 *    见设计稿 2.1 的说明）。
 *
 * @param isLight 由 `surface.luminance()` 判定，而不是读系统深色开关 —— 这样 AMOLED 纯黑、
 *   动态取色的极端值也能判对。
 */
@Immutable
data class CineColors(
  val isLight: Boolean,
  val surface: Color,
  val onSurface: Color,
  val onSurfaceVariant: Color,
  /** Dock / Rail / 折叠顶栏的玻璃底 */
  val glassSurface: Color,
  /** 玻璃层 1dp 描边 */
  val glassBorder: Color,
  /** 强角标（4K / HDR / 集数）底色 */
  val badgeStrong: Color,
  val onBadgeStrong: Color,
  /** 弱角标（Ghost）：压在图上，白 16% + 白描边 */
  val badgeGhostSurface: Color,
  val badgeGhostBorder: Color,
  /** 压在图上的主文字（标题、按钮文案） */
  val onImage: Color,
  /** 压在图上的次级文字（元信息、剩余时长） */
  val onImageMuted: Color,
  /** 压在图上的进度底槽 */
  val onImageTrack: Color,
  val progressFill: Color,
  /** 普通表面上的进度底槽（浅色 = 黑 16%，深色 = 白 22%） */
  val progressTrack: Color,
  /** Chip 底色 / 描边（未选中） */
  val chipSurface: Color,
  val chipBorder: Color,
  /** Chip 选中态 */
  val chipSelected: Color,
  val onChipSelected: Color,
  /** 分区标题右侧「全部 / 换一批」 */
  val sectionAction: Color,
  /** 骨架屏色块 */
  val skeleton: Color,
  /** 错误态图标容器（用 errorContainer，不用 primary —— 错误该刺眼） */
  val dangerContainer: Color,
  val onDangerContainer: Color,
  /** Hero 顶部状态栏压暗（给状态栏图标与品牌行留可读背景） */
  val heroScrimTop: Brush,
  /** Hero 底部渐变：透明 → surface 0.86 → surface（与页面底色无缝接上，见设计稿 01） */
  val heroScrimBottom: Brush,
  /** 海报底部信息层：透明 → 黑 62% → 黑 90%（覆盖下 58%） */
  val posterScrim: Brush,
  /** 继续观看卡（16:9）的压暗：只需压住左上角集号与左下角剩余时长 */
  val wideScrim: Brush,
)

/**
 * 从当前 `colorScheme` 派生 Cine 语义色。
 *
 * `remember(scheme)` 包住：派生本身有十几个对象分配，没必要每次重组都重算。
 */
@Composable
fun rememberCineColors(): CineColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    val isLight = scheme.surface.luminance() > 0.5f
    CineColors(
      isLight = isLight,
      surface = scheme.surface,
      onSurface = scheme.onSurface,
      onSurfaceVariant = scheme.onSurfaceVariant,
      glassSurface = if (isLight) {
        scheme.surface.copy(alpha = 0.78f)
      } else {
        scheme.surfaceContainer.copy(alpha = 0.72f)
      },
      glassBorder = scheme.outlineVariant.copy(alpha = 0.35f),
      badgeStrong = scheme.tertiaryContainer,
      onBadgeStrong = scheme.onTertiaryContainer,
      badgeGhostSurface = Color.White.copy(alpha = 0.16f),
      badgeGhostBorder = Color.White.copy(alpha = 0.26f),
      onImage = Color.White,
      onImageMuted = Color.White.copy(alpha = 0.78f),
      onImageTrack = Color.White.copy(alpha = 0.24f),
      progressFill = scheme.primary,
      progressTrack = if (isLight) {
        scheme.onSurface.copy(alpha = 0.16f)
      } else {
        Color.White.copy(alpha = 0.22f)
      },
      chipSurface = scheme.surfaceContainerHigh,
      chipBorder = scheme.outlineVariant,
      chipSelected = scheme.primaryContainer,
      onChipSelected = scheme.onPrimaryContainer,
      sectionAction = scheme.primary,
      skeleton = scheme.surfaceContainerHigh,
      dangerContainer = scheme.errorContainer,
      onDangerContainer = scheme.onErrorContainer,
      heroScrimTop = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.72f),
        0.46f to Color.Black.copy(alpha = 0.30f),
        1f to Color.Transparent,
      ),
      heroScrimBottom = Brush.verticalGradient(
        0f to Color.Transparent,
        0.62f to scheme.surface.copy(alpha = 0.86f),
        1f to scheme.surface,
      ),
      posterScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.42f to Color.Transparent,
        0.58f to Color.Black.copy(alpha = 0.62f),
        1f to Color.Black.copy(alpha = 0.90f),
      ),
      wideScrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.35f),
        0.34f to Color.Transparent,
        0.66f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.62f),
      ),
    )
  }
}

/**
 * 没有默认值：Cine 组件只应该在 `CineTheme` 里组合。
 * 漏包主题时立刻报错，好过悄悄退化成一堆 `Color.Unspecified` 的黑块。
 */
@Suppress("CompositionLocalAllowlist")
val LocalCineColors = staticCompositionLocalOf<CineColors> {
  error("CineColors 未提供：Cine 组件必须包在 CineTheme { } 内")
}
