package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「视界流」入口的音符图标（矢量）。
 *
 * 媒体库工具行里「随机播放」「随机播放收藏」已经占掉了两个随机语义的位置，
 * 第三个「视界流」（仿抖音竖屏上下滑连播）必须换个一眼能分辨的图形，
 * 否则三排图标并排看全是「随机」的意思。这里用抖音标志性的音符：
 * 符头 + 符干 + 右上甩出去的那一勾。
 *
 * 三个子路径同向绘制、统一非零环绕填充 —— Material 图标同样只填充不描边，
 * 加了描边会比相邻的 Material 图标粗一圈。
 *
 * 坐标直接按 24×24 视口给，运行时不做任何变换。
 */
val TikTokNoteIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "TikTokNote",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // ── 符干 ──
    path(fill = SolidColor(Color.Black)) {
      moveTo(11.7f, 17.6f)
        .lineTo(11.7f, 3.0f)
        .lineTo(14.3f, 3.0f)
        .lineTo(14.3f, 17.6f)
        .close()
    }
    // ── 符头：圆心 (9.2, 17.8)、半径 3.8，用四段三次贝塞尔近似圆 ──
    // k = 0.5523 × r ≈ 2.10，是圆的标准贝塞尔控制点偏移量
    path(fill = SolidColor(Color.Black)) {
      moveTo(13.0f, 17.8f)
        .curveTo(13.0f, 19.9f, 11.3f, 21.6f, 9.2f, 21.6f)
        .curveTo(7.1f, 21.6f, 5.4f, 19.9f, 5.4f, 17.8f)
        .curveTo(5.4f, 15.7f, 7.1f, 14.0f, 9.2f, 14.0f)
        .curveTo(11.3f, 14.0f, 13.0f, 15.7f, 13.0f, 17.8f)
        .close()
    }
    // ── 右上甩出去的那一勾（抖音音符最有辨识度的一笔）──
    path(fill = SolidColor(Color.Black)) {
      moveTo(14.3f, 3.0f)
        .curveTo(14.3f, 8.1f, 18.2f, 11.6f, 23.0f, 11.6f)
        .curveTo(19.6f, 11.6f, 16.3f, 9.9f, 14.3f, 7.0f)
        .close()
    }
  }.build()
}
