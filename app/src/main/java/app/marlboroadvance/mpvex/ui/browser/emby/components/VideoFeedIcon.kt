package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「视界流」入口图标（矢量）：一张视频卡 + 后面还跟着一条。
 *
 * ── 为什么不用音符 ──
 * 原先用的抖音音符是**品牌符号**而不是功能符号：摆在一个 Emby 客户端里，用户第一反应
 * 是「看配乐 / 抖音专属功能」，而不是「竖着刷视频」。这里改回纯功能表达。
 *
 * ── 图形为什么长这样 ──
 * 这个入口要一次说清三件事：**是视频**、**一整串连着播**、**竖排上下刷**。
 * 于是画成「一张视频卡（卡里一个播放三角）+ 右侧露出半张卡」：
 * - 圆角矩形 + 中间的三角 = 一条视频（Material 的 Slideshow 也是这个语义）；
 * - 右边那半张卡 = 后面还跟着一条，也就是「连着播」；
 * - 两卡横向错开、整体竖长 = 竖排的信息流。
 *
 * ── 为什么卡身是描边而不是实心 ──
 * 实心卡里要放一个三角，就得把三角「挖空」，而挖空依赖奇偶填充规则
 * （EvenOdd）。与其为一个图标去依赖那套填充规则，不如直接按 Material 的常见做法：
 * 卡身描边、三角实心 —— 视觉重量反而更接近旁边的 Material 图标。
 *
 * 坐标按 24×24 视口直给，运行时不做变换。
 */
val VideoFeedIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "VideoFeed",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // ── 当前这条：圆角视频卡（描边），圆角用二次贝塞尔近似 ──
    path(
      fill = null,
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    ) {
      moveTo(6f, 4f)
      lineTo(14f, 4f)
      quadTo(17f, 4f, 17f, 7f)
      lineTo(17f, 17f)
      quadTo(17f, 20f, 14f, 20f)
      lineTo(6f, 20f)
      quadTo(3f, 20f, 3f, 17f)
      lineTo(3f, 7f)
      quadTo(3f, 4f, 6f, 4f)
      close()
    }
    // ── 卡里的播放三角（实心）──
    path(fill = SolidColor(Color.Black)) {
      moveTo(8.3f, 9.6f)
      lineTo(8.3f, 14.4f)
      lineTo(13.5f, 12f)
      close()
    }
    // ── 后面还跟着的那一条：只露右侧一条边，压淡表示「排在后面」──
    path(
      fill = SolidColor(Color.Black),
      fillAlpha = 0.55f,
    ) {
      moveTo(19.5f, 6.5f)
      lineTo(20.5f, 6.5f)
      quadTo(23f, 6.5f, 23f, 9f)
      lineTo(23f, 15f)
      quadTo(23f, 17.5f, 20.5f, 17.5f)
      lineTo(19.5f, 17.5f)
      close()
    }
  }.build()
}
