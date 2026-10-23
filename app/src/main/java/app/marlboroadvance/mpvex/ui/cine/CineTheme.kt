package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import app.marlboroadvance.mpvex.ui.cine.tokens.CineColors
import app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing
import app.marlboroadvance.mpvex.ui.cine.tokens.CineTypographySet
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineColors
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineSpacing
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineTypography
import app.marlboroadvance.mpvex.ui.cine.tokens.cineMaterialTypography
import app.marlboroadvance.mpvex.ui.cine.tokens.rememberCineColors

/**
 * Cine 子树的主题壳。
 *
 * ## 只做三件事
 * 1. 从 `colorScheme` 派生语义色（[CineColors]）；
 * 2. 下发 Cine 字阶 / 间距；
 * 3. 用 [cineMaterialTypography] 覆盖一层 M3 Typography —— **只作用于 Cine 子树**，
 *    因为 M3 组件（BottomSheet / AlertDialog）内部读的是 `MaterialTheme.typography`，
 *    不覆盖它们会按 M3 默认字阶渲染，和周围 Cine 文字差一号。
 *
 * ## 为什么这里不重新提供 colorScheme
 * 配色必须与老界面**同一套**（30 套主题 + 动态取色）。Cine 只加语义层，不改色相 ——
 * 用户换主题时两套界面的质感是连着的，不会出现「换了主题但新界面不理你」。
 *
 * ## ⚠️ 老页面落在 Cine 子树里怎么办
 * `MaterialTheme` 的 typography 覆盖会顺带作用于**回落进 Cine 外壳的 Classic 页面**
 * （如媒体库、设置）。这些页面的字号都是角色值（titleLarge / bodyMedium…），
 * 覆盖后仍在同一梯队，不会破版；这也是我们**没有**改 colorScheme 的原因 ——
 * 那才会真正让老页面配色错乱。
 */
@Composable
fun CineTheme(content: @Composable () -> Unit) {
  val colors = rememberCineColors()
  val typography = remember { CineTypographySet() }
  val spacing = remember { CineSpacing() }
  val materialTypography = remember { cineMaterialTypography() }

  CompositionLocalProvider(
    LocalCineColors provides colors,
    LocalCineTypography provides typography,
    LocalCineSpacing provides spacing,
  ) {
    MaterialTheme(typography = materialTypography, content = content)
  }
}

/**
 * Cine 主题读值入口，用法与 `MaterialTheme` 对齐：
 * `CineTheme.colors.posterScrim`、`CineTheme.type.heroTitle`、`CineTheme.space.sectionGap`。
 */
object CineTheme {
  val colors: CineColors
    @Composable @ReadOnlyComposable get() = LocalCineColors.current

  val type: CineTypographySet
    @Composable @ReadOnlyComposable get() = LocalCineTypography.current

  val space: CineSpacing
    @Composable @ReadOnlyComposable get() = LocalCineSpacing.current
}

/**
 * 是否宽屏（≥600dp）。
 *
 * 设计决策 4：本期只做 Dock → Rail 的二分，不做横屏双栏。
 * 用 `LocalConfiguration.screenWidthDp` 而不是 Material3 的 WindowSizeClass：
 * 后者要额外依赖，而这里只需要一个阈值。
 */
@Composable
fun rememberCineWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** 当前屏幕高度（dp），用于把 Hero 夹到「不超过 62% 屏高」 */
@Composable
fun rememberCineScreenHeightDp(): Int = LocalConfiguration.current.screenHeightDp
