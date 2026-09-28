package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Emby 官方品牌绿（取自官方 Logo 主色） */
val EmbyBrandGreen = Color(0xFF52B54B)

/**
 * Emby 官方 Logo 的矢量路径（Simple Icons 收录的官方标识，viewBox 24×24）。
 *
 * 用真实路径而不是自绘近似图形 —— 用户在顶部工具栏一眼就能认出这是 Emby 维护入口。
 */
private const val EMBY_LOGO_PATH =
  "M11.041 0c-.007 0-1.456 1.43-3.219 3.176L4.615 6.352l.512.513.512.512-2.819 2.791L0 12.961l1.83 " +
    "1.848c1.006 1.016 2.438 2.46 3.182 3.209l1.351 1.359.508-.496c.28-.273.515-.498.524-.498.008 0 " +
    "1.266 1.264 2.794 2.808L12.97 24l.187-.182c.23-.225 5.007-4.95 5.717-5.656l.52-.516-.502-.513c-.276-.282-.5-.52-.496-.53.003-.009 " +
    "1.264-1.26 2.802-2.783 1.538-1.522 2.8-2.776 2.803-2.785.005-.012-3.617-3.684-6.107-6.193L17.65 " +
    "4.6l-.505.505c-.279.278-.517.501-.53.497-.013-.005-1.27-1.267-2.793-2.805A449.655 449.655 0 " +
    "0011.041 0zM9.223 7.367c.091.038 7.951 4.608 7.957 4.627.003.013-1.781 1.056-3.965 " +
    "2.32a999.898 999.898 0 01-3.996 2.307c-.019.006-.026-1.266-.026-4.629 0-3.7.007-4.634.03-4.625Z"

/** Emby 官方 Logo 图标（默认黑色填充，实际颜色由 Icon 的 tint 决定） */
val EmbyLogo: ImageVector by lazy {
  ImageVector.Builder(
    name = "EmbyLogo",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    addPath(
      pathData = PathParser().parsePathString(EMBY_LOGO_PATH).toNodes(),
      fill = SolidColor(Color.Black),
    )
  }.build()
}

/**
 * 「Emby 维护」入口按钮。
 *
 * 用 Emby 官方图标 + 品牌绿，和通用的齿轮（设置）在视觉上明确区分开，
 * 用户不会再点错 —— 齿轮进设置，Emby 图标进服务器维护。
 */
@Composable
fun EmbyMaintainButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(
      imageVector = EmbyLogo,
      contentDescription = "Emby 维护",
      tint = EmbyBrandGreen,
    )
  }
}
