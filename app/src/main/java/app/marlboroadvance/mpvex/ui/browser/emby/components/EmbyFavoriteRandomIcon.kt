package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Emby「随机播放收藏」专用图标（矢量）。
 *
 * 背景：媒体库工具行里「随机播放」和「随机播放收藏」原先分别用 Material 的
 * `Shuffle` 与 `ShuffleOn` —— 这两个图形只差一条下划线，并排放在一起几乎分不出来。
 * 这里换成 AlistClientN 项目用的 Emby 收藏随机图标（仓库根目录 `1.svg`，与心跳图标
 * 同源的收藏语义），一眼就能和普通「随机播放」区分开。
 *
 * 与 Flutter 版 `EmbyFavoriteRandomIcon` 保持一致的三个处理：
 * 1. SVG 原图并未铺满 512×512 视口（四周留白很大），直接按 viewBox 画会比相邻
 *    Material 图标小一圈。所以生成时先按墨迹包围盒做等比归一化（[FIT]），
 *    再微调垂直位置（[OFFSET_Y_RATIO]），使其大小与邻居一致；
 * 2. SVG 根元素带 `transform="translate(0,512) scale(0.1,-0.1)"`（y 轴反向），
 *    路径坐标已按该变换换算到 512 视口；
 * 3. 图形主体是细线条轮廓，只填充会太单薄，因此**同时描边 + 填充**，让笔画重量
 *    和相邻 Material 图标对齐。
 *
 * 形状内的所有坐标均已烘焙为最终视口坐标，运行时不做任何变换。
 */

/** 包围盒归一化缩放：512 / max(宽, 高) × 0.92，其中墨迹尺寸约 285.3×247.2 */
private const val FIT = 1.651f

/** 描边宽度（视口单位）：原图 80 用户单位 × 0.1 换算 × FIT */
private const val STROKE_WIDTH = 13.21f

val EmbyFavoriteRandomIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "EmbyFavoriteRandom",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
  ).apply {
    path(
      fill = SolidColor(Color.Black),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = STROKE_WIDTH,
      strokeLineJoin = StrokeJoin.Round,
      strokeLineCap = StrokeCap.Round,
    ) {
      moveTo(340.29f, 40.04f)
        .curveTo(324.27f, 44.33f, 310.73f, 55.72f, 304.79f, 69.92f)
        .curveTo(298.35f, 85.61f, 298.84f, 104.1f, 306.11f, 117.97f)
        .curveTo(311.56f, 128.2f, 390.48f, 207.78f, 395.26f, 207.78f)
        .curveTo(397.91f, 207.78f, 411.44f, 195.4f, 441.49f, 165.35f)
        .curveTo(490.53f, 116.48f, 491.52f, 115.16f, 491.52f, 92.21f)
        .curveTo(491.52f, 76.2f, 488.38f, 67.61f, 478.97f, 56.88f)
        .curveTo(459.32f, 34.42f, 426.63f, 32.11f, 402.53f, 51.26f)
        .curveTo(398.24f, 54.73f, 395.43f, 55.89f, 394.11f, 54.9f)
        .curveTo(380.74f, 45.16f, 375.62f, 42.35f, 367.53f, 40.2f)
        .curveTo(356.14f, 37.23f, 350.52f, 37.23f, 340.29f, 40.04f)
        .close()
        .moveTo(370f, 60.02f)
        .curveTo(372.98f, 61.5f, 379.25f, 66.29f, 384.37f, 70.75f)
        .curveTo(389.32f, 75.37f, 394.27f, 79f, 395.43f, 79f)
        .curveTo(398.07f, 79f, 405.01f, 74.21f, 407.65f, 70.58f)
        .curveTo(411.78f, 65.13f, 424.82f, 57.87f, 432.74f, 56.71f)
        .curveTo(454.37f, 53.74f, 474.18f, 70.75f, 474.18f, 92.21f)
        .curveTo(474.18f, 106.24f, 468.74f, 113.51f, 431.42f, 150.49f)
        .curveTo(412.44f, 169.31f, 396.26f, 184.67f, 395.43f, 184.67f)
        .curveTo(394.6f, 184.67f, 377.93f, 168.49f, 358.28f, 148.68f)
        .curveTo(324.11f, 114.33f, 322.45f, 112.52f, 319.81f, 103.11f)
        .curveTo(317.34f, 94.85f, 317.17f, 91.88f, 318.82f, 84.62f)
        .curveTo(321.3f, 73.72f, 331.04f, 62.16f, 340.95f, 58.53f)
        .curveTo(349.2f, 55.39f, 362.57f, 56.05f, 370f, 60.02f)
        .close()
        .moveTo(40.79f, 82.14f)
        .curveTo(32.53f, 85.77f, 24.11f, 95.18f, 21.8f, 103.27f)
        .curveTo(20.48f, 107.73f, 20.15f, 155.61f, 20.48f, 263.42f)
        .lineTo(20.98f, 417.13f)
        .lineTo(25.93f, 424.4f)
        .curveTo(29.07f, 428.86f, 33.85f, 432.98f, 39.14f, 435.63f)
        .lineTo(47.39f, 439.75f)
        .lineTo(226.53f, 440.25f)
        .curveTo(355.97f, 440.58f, 407.48f, 440.25f, 412.27f, 438.93f)
        .curveTo(423f, 435.79f, 431.42f, 429.02f, 435.72f, 419.94f)
        .lineTo(439.51f, 411.69f)
        .lineTo(439.51f, 322.03f)
        .lineTo(439.51f, 232.55f)
        .lineTo(430.43f, 232.55f)
        .lineTo(421.35f, 232.55f)
        .lineTo(421.35f, 321.7f)
        .lineTo(421.35f, 410.86f)
        .lineTo(416.56f, 416.14f)
        .lineTo(411.78f, 421.59f)
        .lineTo(230.66f, 422.09f)
        .lineTo(49.54f, 422.42f)
        .lineTo(43.92f, 416.8f)
        .lineTo(38.31f, 411.19f)
        .lineTo(38.31f, 260.78f)
        .curveTo(38.31f, 97.33f, 37.98f, 103.44f, 47.39f, 99.14f)
        .curveTo(50.53f, 97.66f, 79.92f, 97.16f, 161.31f, 97.16f)
        .lineTo(271.11f, 97.16f)
        .lineTo(271.11f, 88.08f)
        .lineTo(271.11f, 79f)
        .lineTo(159.33f, 79f)
        .curveTo(54.66f, 79f, 46.9f, 79.33f, 40.79f, 82.14f)
        .close()
        .moveTo(164.12f, 176.74f)
        .curveTo(162.63f, 178.23f, 162.14f, 200.02f, 162.14f, 264.91f)
        .curveTo(162.14f, 350.93f, 162.14f, 351.26f, 165.61f, 353.73f)
        .curveTo(170.72f, 357.2f, 173.86f, 355.55f, 202.42f, 335.24f)
        .curveTo(216.13f, 325.5f, 242.38f, 306.68f, 260.87f, 293.64f)
        .curveTo(292.57f, 271.18f, 294.39f, 269.53f, 293.89f, 264.91f)
        .curveTo(293.4f, 260.62f, 287.12f, 255.5f, 233.96f, 217.52f)
        .curveTo(175.02f, 175.26f, 169.07f, 171.79f, 164.12f, 176.74f)
        .close()
        .moveTo(224.88f, 233.54f)
        .curveTo(248.49f, 250.54f, 267.64f, 264.91f, 267.47f, 265.57f)
        .curveTo(267.14f, 266.72f, 237.92f, 287.86f, 185.75f, 324.68f)
        .lineTo(180.3f, 328.47f)
        .lineTo(180.3f, 265.73f)
        .curveTo(180.3f, 231.06f, 180.63f, 202.83f, 181.13f, 202.83f)
        .curveTo(181.46f, 202.83f, 201.27f, 216.7f, 224.88f, 233.54f)
        .close()
    }
  }.build()
}
