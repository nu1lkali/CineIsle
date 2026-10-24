package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * 当前 Cine 子树的模糊源。
 *
 * 由 `CineTheme` 提供（每个 Cine 路由一份），页面把**滚动内容**标记成
 * `Modifier.hazeSource(LocalCineHaze.current)`，玻璃层再读同一个 state 取模糊底。
 *
 * 为 null 表示「这套界面没开模糊」（例如 Compose Preview、或者内容还没挂上），
 * 此时 [cineGlass] 会退回「半透明平涂」，绝不崩。
 */
@Suppress("CompositionLocalAllowlist")
val LocalCineHaze: ProvidableCompositionLocal<HazeState?> = staticCompositionLocalOf { null }

/**
 * 把内容标记成「可以被玻璃层取样的背景」。
 *
 * ⚠️ 必须挂在**滚动容器本身**上（LazyColumn / Box），不要挂在它的子项上 ——
 * Haze 取的是该节点整块区域的快照，挂在 item 上会导致滚出屏幕的玻璃层没有底可糊。
 */
fun Modifier.cineHazeSource(state: HazeState?): Modifier =
  if (state == null) this else this.hazeSource(state)

/**
 * Cine 的玻璃层：**真背景模糊 + 投影 + 顶边高光**。
 *
 * ## 为什么三样缺一不可（这一条是 v132 的核心修正）
 * 设计稿的玻璃是：
 * ```css
 * background: var(--glass);
 * backdrop-filter: blur(22px) saturate(1.5);
 * box-shadow: 0 14px 34px rgba(0,0,0,.42), 0 1px 0 rgba(255,255,255,.05) inset;
 * ```
 * v126~v131 只实现了第一行（半透明色）+ 1dp 描边，于是「玻璃」退化成一条色带：
 * - 没有 `backdrop-filter` ⇒ 底下内容不糊，看起来只是半透明，不像材质；
 * - 没有 `box-shadow` ⇒ 浮层和内容贴在同一层，没有高度；
 * - 没有内高光 ⇒ 少一层边缘反光，质感偏平。
 *
 * ## 参数
 * @param elevation 投影高度。Dock 14dp（设计稿 0 14 34）、二级顶栏 8dp、悬浮钮 10dp。
 *   传 `0.dp` 表示不投（例如嵌在别的玻璃里的次级胶囊）。
 * @param blurRadius 模糊半径，设计稿 Dock 22px / 顶栏 20px。越大越"奶油"，
 *   但离屏渲染开销随半径线性上涨，超过 28dp 在低端机上开始掉帧。
 * @param flatAlpha 没有模糊源时的平涂 alpha。有模糊时用 [CineTheme.colors.glassTint]，
 *   两者不能用同一个值：平涂要 0.72 才遮得住，模糊后 0.72 又厚得透不出内容。
 * @param tintOverride 压在**图片**上的玻璃要换色：设计稿的搜索胶囊是
 *   `rgba(255,255,255,.14)`（白色frost），而 `glassTint` 是表面色 ——
 *   在深色主题下用表面色会把剧照压得更暗，看着像一块污渍。
 */
@Composable
fun Modifier.cineGlass(
  shape: Shape,
  elevation: Dp = 14.dp,
  blurRadius: Dp = 22.dp,
  flatAlpha: Float = 0.78f,
  border: Boolean = true,
  tintOverride: Color? = null,
  borderColor: Color? = null,
): Modifier {
  val colors = CineTheme.colors
  val tint = tintOverride ?: colors.glassTint.copy(alpha = colors.glassTint.alpha.coerceAtMost(flatAlpha))
  val highlight = colors.glassHighlight
  // 没有模糊源时（低版本 / 还没挂 source）用什么平涂：优先跟着 tintOverride 走
  val flat = tintOverride ?: colors.glassSurface.copy(alpha = flatAlpha)

  val shadowed = if (elevation > 0.dp) {
    // clip = false：投影活在形状之外，先 clip 会把投影裁掉（卡片也有同样的坑）
    this.shadow(
      elevation = elevation,
      shape = shape,
      clip = false,
      ambientColor = colors.glassShadow,
      spotColor = colors.glassShadow,
    )
  } else {
    this
  }

  return shadowed
    .clip(shape)
    .cineHazeEffect(
      shape = shape,
      tint = tint,
      highlight = highlight,
      blurRadius = blurRadius,
      flat = flat,
    )
    .let { if (border) it.border(1.dp, borderColor ?: colors.glassBorder, shape) else it }
}

/**
 * 有模糊源就上 Haze，没有就退回平涂。
 *
 * 拆成独立函数是为了让「有没有模糊」这一个判断只写一次 —— 上层 [cineGlass]
 * 只负责投影 / 描边这些与模糊无关的部分。
 */
@Composable
private fun Modifier.cineHazeEffect(
  shape: Shape,
  tint: Color,
  highlight: Color,
  blurRadius: Dp,
  flat: Color,
): Modifier {
  val hazeState = LocalCineHaze.current
  val colors = CineTheme.colors

  if (hazeState == null) {
    return this.background(flat, shape)
  }

  // 两层 tint：整片底色 + 顶部渐隐高光（设计稿的 inset 1px 白线，
  // 1dp 的实线在手机上太硬，用 18% 高度的渐变反而更像玻璃边缘的受光）
  val style = HazeStyle(
    // backgroundColor 必须不透明：Haze 按「底色 + 模糊内容 + tint」合成，
    // 底色留透明会让被模糊的内容再叠一次，深色主题下会发灰
    backgroundColor = colors.surface,
    tints = listOf(
      HazeTint(tint),
      HazeTint(
        Brush.verticalGradient(
          0f to highlight,
          0.18f to Color.Transparent,
          1f to Color.Transparent,
        ),
      ),
    ),
    blurRadius = blurRadius,
    // 极轻的噪点：纯数学高斯在纯色区域会出现色带（banding），
    // 一层 3% 噪点就能打散，代价几乎为零
    noiseFactor = 0.03f,
    // 低版本 Android（<12）拿不到 RenderEffect，Haze 会退化成 scrim ——
    // 这里给它一个和不模糊时一致的观感，免得旧机上玻璃变成一片死白
    fallbackTint = HazeTint(colors.glassSurface),
  )

  return this.hazeEffect(state = hazeState, style = style)
}
