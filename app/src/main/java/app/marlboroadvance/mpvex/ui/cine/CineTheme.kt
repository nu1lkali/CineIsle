package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.tokens.CineColors
import app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing
import app.marlboroadvance.mpvex.ui.cine.tokens.CineTypographySet
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineColors
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineSpacing
import app.marlboroadvance.mpvex.ui.cine.tokens.LocalCineTypography
import app.marlboroadvance.mpvex.ui.cine.tokens.cineColorScheme
import app.marlboroadvance.mpvex.ui.cine.tokens.cineMaterialTypography
import app.marlboroadvance.mpvex.ui.cine.tokens.rememberCineColors
import dev.chrisbanes.haze.rememberHazeState

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
 * ## 为什么这里**要**重新提供 colorScheme（v132 改口）
 * v126 的写法是不动色相、只加语义层，理由是「换主题时两套界面质感要连着」。
 * 实测下来这条在**动态取色**下是错的：动态取色的 `surface` 会被壁纸染成淡紫 / 灰绿，
 * 且 `surface` 与 `surfaceContainer*` 的明度差被压得很平 —— 设计稿那套
 * 「近黑底 + 四档容器层级」的观感直接消失，用户看到的就是「发灰、发平」。
 * 现在改成：中性阶梯（surface / onSurface / outline）取设计稿定值，
 * **primary / tertiary / error 仍然跟随用户主题** —— 强调色还是他的，
 * 只是底色不再被壁纸污染。详见 `cineColorScheme` 的说明。
 *
 * ## ⚠️ 老页面落在 Cine 子树里怎么办
 * `MaterialTheme` 的 typography / colorScheme 覆盖会顺带作用于**回落进 Cine 外壳的
 * Classic 页面**。但 v130 之后 `CineTheme` 已经收进 `CineRoute` 的 Cine 分支里，
 * 回落分支（`fallback()`）**不包 CineTheme**，所以 Classic 页面根本拿不到这套覆盖 ——
 * 老 UI 一个像素都不变。
 */
@Composable
fun CineTheme(content: @Composable () -> Unit) {
  // 顺序很重要：先由「用户主题」算出一套中性影院基底，再让 CineColors 从**这套基底**派生。
  // 反过来（先派生颜色再换 scheme）会让 onSurface / glassBorder 仍然带着壁纸的染色。
  val base = MaterialTheme.colorScheme
  val scheme = remember(base) { cineColorScheme(base) }
  val colors = rememberCineColors(scheme)
  val typography = remember { CineTypographySet() }
  val spacing = remember { CineSpacing() }
  val materialTypography = remember { cineMaterialTypography() }
  // 模糊源：每个 Cine 路由一份。页面把滚动容器标成 cineHazeSource，玻璃层取它做底
  val hazeState = rememberHazeState()

  CompositionLocalProvider(
    LocalCineColors provides colors,
    LocalCineTypography provides typography,
    LocalCineSpacing provides spacing,
    LocalCineHaze provides hazeState,
  ) {
    MaterialTheme(
      colorScheme = scheme,
      typography = materialTypography,
      content = content,
    )
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
