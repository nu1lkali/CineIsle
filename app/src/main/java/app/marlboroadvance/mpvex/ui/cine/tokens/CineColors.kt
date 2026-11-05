package app.marlboroadvance.mpvex.ui.cine.tokens

import androidx.compose.material3.ColorScheme
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
  /**
   * 详情页 Backdrop 的底部压暗：透明 → surface。
   *
   * 与 [heroScrimBottom] 的区别是**更短更陡**：详情页的剧照只有 386dp 高，
   * 渐变铺满 260dp 会把整张图压成一片灰，只剩顶上一点画面。
   */
  val detailScrim: Brush,
  /** 评分色（设计稿 8.2：评分用 tertiary，与角标同族） */
  val ratingAccent: Color,
  /**
   * 玻璃层压在内容上的**模糊后着色**（Haze 的 tint）。
   *
   * 与 [glassSurface] 的区别：那是「不模糊时的平涂替代」，alpha 要给到 0.72~0.78 才遮得住；
   * 真模糊之后同样的 alpha 会把底下的内容全糊成一片色块，看不出「透」。所以这里低一档。
   */
  val glassTint: Color,
  /** 玻璃顶边 1dp 内高光（设计稿 `0 1px 0 rgba(255,255,255,.05) inset`） */
  val glassHighlight: Color,
  /** 玻璃投影色（浅色主题下要淡得多，否则一条黑边压在浅底上像污渍） */
  val glassShadow: Color,
  /**
   * 详情页主行动按钮的底色。默认是 [onSurface]（反相实心，全屏对比度最高）；
   * 命中海报主色时换成「带该片色相、但仍保持反相」的版本。
   */
  val heroActionSurface: Color,
  /** 压在 [heroActionSurface] 上的图标 / 文字色，始终取页面底色 [surface]。 */
  val onHeroAction: Color,
)

/**
 * 从当前 `colorScheme` 派生 Cine 语义色。
 *
 * `remember(scheme)` 包住：派生本身有十几个对象分配，没必要每次重组都重算。
 */
@Composable
fun rememberCineColors(scheme: ColorScheme = MaterialTheme.colorScheme): CineColors {
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
      detailScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.5f to scheme.surface.copy(alpha = 0.55f),
        1f to scheme.surface,
      ),
      ratingAccent = scheme.tertiary,
      glassTint = scheme.surface.copy(alpha = if (isLight) 0.68f else 0.58f),
      glassHighlight = if (isLight) {
        Color.White.copy(alpha = 0.55f)
      } else {
        Color.White.copy(alpha = 0.06f)
      },
      glassShadow = Color.Black.copy(alpha = if (isLight) 0.16f else 0.55f),
      heroActionSurface = scheme.onSurface,
      onHeroAction = scheme.surface,
    )
  }
}

/**
 * 详情页「随海报变色」：只换强调色，中性阶梯一个像素都不动。
 *
 * rememberDominantColor 返回的是 soften 之后的**浅色**（向白混 45% 再提亮），
 * 那是给 Classic 详情页的浅色渐变用的；直接铺到 Cine 的近黑底上会发灰发白。
 * 所以先把色相拉开，再把亮度调到各自该在的档位。
 *
 * ## 三条不变量
 * 1. `surface` / `onSurface` / `onSurfaceVariant` 一律不动 —— 它们是全页文字与底色，
 *    染上去一张浅色海报就能让正文整片消失。
 * 2. `detailScrim` 的最后一档仍是 `base.surface`：剧照与页面底色之间必须无缝，
 *    只在中间档混一点主色。
 * 3. 取色失败 / 还在算（[posterColor] 为 null）时**原样返回** [base]。
 *    半页变了色、另一半没变，比整页不变更难看。
 *
 * 浅色主题下 `ratingAccent` / `sectionAction` 不染：中亮色压在白底上做小字对比度不够。
 * Cine 的主视觉本来就是暗底影院，浅色主题只保证不退化。
 */
fun cineColorsForPoster(base: CineColors, posterColor: Color?): CineColors {
  if (posterColor == null) return base
  val saturated = saturatePosterColor(posterColor)
  val accent = toneAtPeak(saturated, if (base.isLight) 0.34f else 0.72f)
  // 主按钮仍是「反相实心」：深色主题亮底深字、浅色主题暗底亮字，只是色相来自海报。
  // 文字色恒为页面底色，两个主题都成立。
  val action = toneAtPeak(saturated, if (base.isLight) 0.34f else 0.88f)
  val darkEnoughForSmallText = !base.isLight
  return base.copy(
    ratingAccent = if (darkEnoughForSmallText) accent else base.ratingAccent,
    sectionAction = if (darkEnoughForSmallText) accent else base.sectionAction,
    progressFill = accent,
    chipBorder = mix(base.chipBorder, accent, 0.45f),
    glassTint = mix(base.glassTint, accent, 0.10f),
    detailScrim = Brush.verticalGradient(
      0f to Color.Transparent,
      0.5f to mix(base.surface, accent, 0.18f).copy(alpha = 0.55f),
      1f to base.surface,
    ),
    heroActionSurface = action,
    onHeroAction = base.surface,
  )
}

/** soften 把颜色往白里混过，色相被压得很淡；这里相对灰点把差重新拉开。 */
private fun saturatePosterColor(source: Color): Color {
  val gray = (source.red + source.green + source.blue) / 3f
  val gain = 1.9f
  return Color(
    red = (gray + (source.red - gray) * gain).coerceIn(0f, 1f),
    green = (gray + (source.green - gray) * gain).coerceIn(0f, 1f),
    blue = (gray + (source.blue - gray) * gain).coerceIn(0f, 1f),
    alpha = 1f,
  )
}

/** 等比缩放到指定峰值亮度，保留色相；用来生成「亮强调」与「亮按钮」两档。 */
private fun toneAtPeak(color: Color, peak: Float): Color {
  val current = maxOf(color.red, color.green, color.blue).coerceAtLeast(0.01f)
  val scale = peak / current
  return Color(
    red = (color.red * scale).coerceIn(0f, 1f),
    green = (color.green * scale).coerceIn(0f, 1f),
    blue = (color.blue * scale).coerceIn(0f, 1f),
    alpha = 1f,
  )
}

private fun mix(a: Color, b: Color, fraction: Float): Color = Color(
  red = a.red + (b.red - a.red) * fraction,
  green = a.green + (b.green - a.green) * fraction,
  blue = a.blue + (b.blue - a.blue) * fraction,
  alpha = a.alpha + (b.alpha - a.alpha) * fraction,
)

/**
 * Cine 的**影院基底配色**。
 *
 * ## 为什么不能直接用 `colorScheme`
 * 用户的主题可能是「动态取色」—— 那套 `surface` 会被壁纸染成淡紫 / 淡青 / 灰绿，
 * 而且明度被拉得很平（`surface` 与 `surfaceContainer*` 之间只差一点点）。
 * 设计稿的观感恰恰建立在**两极**上：底色接近纯黑（`#090B0E`），
 * 容器层级靠 4~6 个亮度档位一层层推开。动态取色把这两条同时破坏掉，
 * 于是玻璃没有对比可浮、卡片也没有层次可退 —— 用户看到的就是"发灰、发平"。
 *
 * ## 做法：只换中性阶梯，不动色相
 * - `surface` / `surfaceContainer*` / `onSurface` / `outlineVariant` 一律换成设计稿 2.1 的
 *   定值（浅深各一套，与 `docs/design/ui-2.0-mockup.html` 的 `--surface`…`--surf3` 一一对应）；
 * - **primary / tertiary / error 全部保留** —— 强调色仍然跟用户的主题走，
 *   换主题时 Cine 的高亮色会跟着变，只是底色不再被壁纸污染。
 *
 * ## 作用范围
 * 由 [app.marlboroadvance.mpvex.ui.cine.CineTheme] 局部覆盖，只作用于 Cine 子树。
 * 回落进 Cine 外壳的 Classic 页面（如设置）**已经在 `CineRoute` 的回落分支外**，
 * 不会经过这里 —— 这是 v130 把 `CineTheme` 收进路由表带来的额外好处。
 */
fun cineColorScheme(base: ColorScheme): ColorScheme {
  val isLight = base.surface.luminance() > 0.5f
  return if (isLight) {
    base.copy(
      surface = Color(0xFFFFFFFF),
      surfaceDim = Color(0xFFF5F6F8),
      surfaceBright = Color(0xFFFFFFFF),
      surfaceContainerLowest = Color(0xFFFFFFFF),
      surfaceContainerLow = Color(0xFFF1F3F7),
      surfaceContainer = Color(0xFFE9EDF2),
      surfaceContainerHigh = Color(0xFFDFE4EB),
      surfaceContainerHighest = Color(0xFFD6DCE4),
      onSurface = Color(0xFF12151A),
      onSurfaceVariant = Color(0xFF5C6674),
      outline = Color(0xFF98A2AE),
      outlineVariant = Color(0xFFD3DAE2),
      scrim = Color(0xFF0A0C10),
    )
  } else {
    base.copy(
      surface = Color(0xFF0D1014),
      surfaceDim = Color(0xFF090B0E),
      surfaceBright = Color(0xFF232830),
      surfaceContainerLowest = Color(0xFF07080B),
      surfaceContainerLow = Color(0xFF12161C),
      surfaceContainer = Color(0xFF171D25),
      surfaceContainerHigh = Color(0xFF1E252F),
      surfaceContainerHighest = Color(0xFF262E3A),
      onSurface = Color(0xFFE9ECEF),
      onSurfaceVariant = Color(0xFF98A2AE),
      outline = Color(0xFF4B5867),
      outlineVariant = Color(0xFF2A323D),
      scrim = Color(0xFF000000),
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
