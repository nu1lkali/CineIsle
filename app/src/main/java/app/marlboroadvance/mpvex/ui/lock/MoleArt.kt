package app.marlboroadvance.mpvex.ui.lock

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.random.Random

/**
 * 打地鼠用到的全部颜色。
 *
 * 分两套（浅色 / 深色），但地鼠本体**不换色** —— 灰褐在深底上对比度足够，
 * 反色反而会让它看起来像另一种动物。换的只有格子、土丘、洞口和草叶。
 */
internal data class MolePalette(
  val cell: Color,
  val cellUp: Color,
  val cellStroke: Color,
  val activeStroke: Color,
  val mound: Color,
  val rim: Color,
  val hole: Color,
  val grass: Color,
  val grassDim: Color,
  val body: Color,
  val muzzle: Color,
  val earPit: Color,
  val eye: Color,
  val nose: Color,
  val blush: Color,
  val tooth: Color,
  val paw: Color,
  val tear: Color,
  val hammerHandle: Color,
  val hammerHead: Color,
  val hammerCap: Color,
  val impact: Color,
  val marker: Color,
  val markerInk: Color,
)

internal fun molePalette(dark: Boolean): MolePalette = if (dark) {
  MolePalette(
    cell = Color(0xFF27500A),
    cellUp = Color(0xFF2C5A0B),
    cellStroke = Color(0xFFC0DD97),
    activeStroke = Color(0xFFEF9F27),
    mound = Color(0xFF1E3D07),
    rim = Color(0xFF2E5A0D),
    hole = Color(0xFF101208),
    grass = Color(0xFF97C459),
    grassDim = Color(0xFFC0DD97),
    body = Color(0xFF888780),
    muzzle = Color(0xFFB4B2A9),
    earPit = Color(0xFF5F5E5A),
    eye = Color(0xFF2C2C2A),
    nose = Color(0xFFF09595),
    blush = Color(0xFFF4C0D1),
    tooth = Color(0xFFFFFFFF),
    paw = Color(0xFFB4B2A9),
    tear = Color(0xFF85B7EB),
    hammerHandle = Color(0xFF633806),
    hammerHead = Color(0xFFBA7517),
    hammerCap = Color(0xFFEF9F27),
    impact = Color(0xFFEF9F27),
    marker = Color(0xFF3B6D11),
    markerInk = Color(0xFFC0DD97),
  )
} else {
  MolePalette(
    cell = Color(0xFFEAF3DE),
    cellUp = Color(0xFFF2F8E7),
    cellStroke = Color(0xFFC0DD97),
    activeStroke = Color(0xFFBA7517),
    mound = Color(0xFFDFEDC6),
    rim = Color(0xFFCBE0A6),
    hole = Color(0xFF3C3D33),
    grass = Color(0xFF7FB047),
    grassDim = Color(0xFF639922),
    body = Color(0xFF888780),
    muzzle = Color(0xFFB4B2A9),
    earPit = Color(0xFF5F5E5A),
    eye = Color(0xFF2C2C2A),
    nose = Color(0xFFF09595),
    blush = Color(0xFFF4C0D1),
    tooth = Color(0xFFFFFFFF),
    paw = Color(0xFFB4B2A9),
    tear = Color(0xFF85B7EB),
    hammerHandle = Color(0xFF633806),
    hammerHead = Color(0xFFBA7517),
    hammerCap = Color(0xFFEF9F27),
    impact = Color(0xFFBA7517),
    marker = Color(0xFFC0DD97),
    markerInk = Color(0xFF3B6D11),
  )
}

// ────────────────────────────────────────────────────────────────────────
// 绘制
// ────────────────────────────────────────────────────────────────────────
//
// 坐标系固定 90×100，由调用方按格子尺寸缩放。为什么要固定：
// 三帧（举起 / 落点 / 回弹）的角度是按这套坐标算出来的 —— 锤头中心距轴心 72，
// 旋转 -6° 落在 (67,24)、-26° 落在 (43,31) 正好压在地鼠头顶。换算成 dp 再算
// 一遍容易错，不如整层缩放。
//
// ## 地鼠的四个识别特征（改这里之前先读）
//
// 第一版画成了猴子，原因是四条特征全画反了：
//
//  1. 耳朵：地鼠几乎没有外耳，只有贴着脑袋的小耳孔。加招风耳立刻变猴 / 仓鼠。
//  2. 毛色：灰褐（0xFF888780）。亮棕黄是猴。
//  3. 眼睛：小，但保留一点高光。大圆眼会变成幼态动物通用脸。
//  4. 门牙：两颗白色方门牙从吻部下沿露出来 —— 漏掉就失去最强识别点。
//
// 加分项：粉色大圆鼻、两侧胡须、两只前爪扒在洞口边缘。

/** 设计坐标系的宽高，缩放时按这两个数换算。 */
internal const val MOLE_UNIT_W = 90f
internal const val MOLE_UNIT_H = 100f

/**
 * 一丛草的位置与形态。
 *
 * [layer] 决定画在哪一层：0 = 洞后面（会被洞沿压掉下半截）、1 = 洞前面的地面上、
 * 2 = 地鼠前面（压住地鼠下缘）。三层分开画是「从草里探出来」的关键。
 */
internal data class GrassTuft(
  val x: Float,
  val y: Float,
  val scale: Float,
  val flip: Boolean,
  val dim: Boolean,
  val tilt: Float,
  val layer: Int,
)

/**
 * 按种子生成一片草。
 *
 * 种子固定为格子下标（调用方传 `index * 31 + 7` 之类），所以九个格子各不相同，
 * 但同一个格子每次重组都是同一片草 —— 不能用随机数现取，否则每帧重绘都在抖。
 */
internal fun grassTufts(seed: Int): List<GrassTuft> {
  val random = Random(seed)
  fun jitter(base: Float, span: Float) = base + (random.nextFloat() * 2f - 1f) * span

  val tufts = ArrayList<GrassTuft>(8)

  // 洞后两丛：会被洞沿压掉一大半，只露出草尖
  tufts += GrassTuft(
    x = jitter(22f, 3.5f),
    y = jitter(82f, 1.5f),
    scale = jitter(0.82f, 0.08f),
    flip = random.nextBoolean(),
    dim = true,
    tilt = jitter(0f, 8f),
    layer = 0,
  )
  tufts += GrassTuft(
    x = jitter(68f, 3.5f),
    y = jitter(83f, 1.5f),
    scale = jitter(0.8f, 0.08f),
    flip = random.nextBoolean(),
    dim = true,
    tilt = jitter(0f, 8f),
    layer = 0,
  )

  // 洞左右两侧：贴边，撑住画面外框
  tufts += GrassTuft(
    x = jitter(8f, 2f),
    y = jitter(97f, 1.5f),
    scale = jitter(1f, 0.1f),
    flip = random.nextBoolean(),
    dim = false,
    tilt = jitter(0f, 10f),
    layer = 1,
  )
  tufts += GrassTuft(
    x = jitter(82f, 2f),
    y = jitter(98f, 1.5f),
    scale = jitter(1.02f, 0.1f),
    flip = random.nextBoolean(),
    dim = false,
    tilt = jitter(0f, 10f),
    layer = 1,
  )

  // 地面上零散的一丛（左右随机），让「每格不一样」看得出来
  tufts += GrassTuft(
    x = if (random.nextBoolean()) jitter(30f, 10f) else jitter(60f, 10f),
    y = jitter(93.5f, 2.5f),
    scale = jitter(0.72f, 0.12f),
    flip = random.nextBoolean(),
    dim = true,
    tilt = jitter(0f, 12f),
    layer = 1,
  )

  // 地鼠前面的两丛：压在洞口下缘
  tufts += GrassTuft(
    x = jitter(26f, 7f),
    y = jitter(100f, 1f),
    scale = jitter(0.86f, 0.12f),
    flip = random.nextBoolean(),
    dim = false,
    tilt = jitter(0f, 10f),
    layer = 2,
  )
  tufts += GrassTuft(
    x = jitter(62f, 7f),
    y = jitter(101f, 1f),
    scale = jitter(0.84f, 0.12f),
    flip = random.nextBoolean(),
    dim = random.nextBoolean(),
    tilt = jitter(0f, 10f),
    layer = 2,
  )

  return tufts
}

/** 洞口地面层：土丘 → 土沿 →（洞后的草）→ 洞 → 洞前地面上的草。 */
internal fun DrawScope.drawMoleGround(p: MolePalette, tufts: List<GrassTuft>) {
  // 土丘：椭圆超出画布，靠格子的圆角裁掉两侧，形成一条自然的弧形地面
  drawOval(color = p.mound, topLeft = Offset(-13f, 76f), size = Size(116f, 68f))
  // 洞口土沿：比洞大一圈，深色下靠明度差分开
  drawOval(color = p.rim, topLeft = Offset(9f, 75f), size = Size(72f, 28f))
  // 洞后的草先画，会被洞沿压掉下半截
  tufts.filter { it.layer == 0 }.forEach { drawTuft(it, if (it.dim) p.grassDim else p.grass) }
  drawOval(color = p.hole, topLeft = Offset(11f, 78f), size = Size(68f, 26f))
  tufts.filter { it.layer == 1 }.forEach { drawTuft(it, if (it.dim) p.grassDim else p.grass) }
}

/** 最前一层草：盖在地鼠下缘，做出「从草里探出来」的遮挡关系。 */
internal fun DrawScope.drawMoleFrontGrass(p: MolePalette, tufts: List<GrassTuft>) {
  tufts.filter { it.layer == 2 }.forEach { drawTuft(it, if (it.dim) p.grassDim else p.grass) }
}

/** 地鼠本体。上下移动与压扁由调用方在变换里做。 */
internal fun DrawScope.drawMoleBody(p: MolePalette, cry: Float = 0f) {
  drawOval(color = p.paw, topLeft = Offset(13f, 79f), size = Size(14f, 10f))
  drawOval(color = p.paw, topLeft = Offset(63f, 79f), size = Size(14f, 10f))
  drawOval(color = p.body, topLeft = Offset(16f, 26f), size = Size(58f, 60f))
  drawOval(color = p.earPit, topLeft = Offset(17.5f, 33.5f), size = Size(9f, 7f))
  drawOval(color = p.earPit, topLeft = Offset(63.5f, 33.5f), size = Size(9f, 7f))
  drawOval(color = p.muzzle, topLeft = Offset(24f, 47f), size = Size(42f, 34f))
  drawOval(color = p.blush.copy(alpha = 0.85f), topLeft = Offset(19f, 65f), size = Size(12f, 8f))
  drawOval(color = p.blush.copy(alpha = 0.85f), topLeft = Offset(59f, 65f), size = Size(12f, 8f))

  if (cry < 0.35f) {
    drawOval(color = p.eye, topLeft = Offset(29.8f, 45.2f), size = Size(8.4f, 9.6f))
    drawOval(color = p.eye, topLeft = Offset(51.8f, 45.2f), size = Size(8.4f, 9.6f))
    drawOval(color = Color.White, topLeft = Offset(33.9f, 46.9f), size = Size(3f, 3f))
    drawOval(color = Color.White, topLeft = Offset(55.9f, 46.9f), size = Size(3f, 3f))
  } else {
    // 眯成 >< ：两笔折线比圆弧更像「痛」
    drawSqueezedEye(p, Offset(29.5f, 46.5f), 6f)
    drawSqueezedEye(p, Offset(60.5f, 46.5f), -6f)
  }

  // 胡须：两侧各两根
  drawLine(p.earPit, Offset(26f, 61f), Offset(15f, 57f), strokeWidth = 1.2f, cap = StrokeCap.Round)
  drawLine(p.earPit, Offset(26f, 65.5f), Offset(15f, 67f), strokeWidth = 1.2f, cap = StrokeCap.Round)
  drawLine(p.earPit, Offset(64f, 61f), Offset(75f, 57f), strokeWidth = 1.2f, cap = StrokeCap.Round)
  drawLine(p.earPit, Offset(64f, 65.5f), Offset(75f, 67f), strokeWidth = 1.2f, cap = StrokeCap.Round)
  drawOval(color = p.nose, topLeft = Offset(38f, 56.5f), size = Size(14f, 11f))
  drawRoundRect(
    color = p.tooth,
    topLeft = Offset(39.5f, 68f),
    size = Size(5.4f, 9.5f),
    cornerRadius = CornerRadius(1.6f),
  )
  drawRoundRect(
    color = p.tooth,
    topLeft = Offset(45.1f, 68f),
    size = Size(5.4f, 9.5f),
    cornerRadius = CornerRadius(1.6f),
  )

  if (cry > 0f) {
    // 泪珠：位置跟着 cry 往下淌，用它当时间轴，不再单独开一条动画
    val drop = cry * 10f
    val alpha = (1f - cry * 0.45f).coerceIn(0f, 1f)
    drawOval(color = p.tear.copy(alpha = alpha), topLeft = Offset(29.9f, 55f + drop), size = Size(4.2f, 6.2f))
    drawOval(color = p.tear.copy(alpha = alpha), topLeft = Offset(55.9f, 55f + drop), size = Size(4.2f, 6.2f))
  }
}

/** 眯起来的眼睛：一笔「>」（[dx] 为正）或「<」（[dx] 为负）。 */
private fun DrawScope.drawSqueezedEye(p: MolePalette, start: Offset, dx: Float) {
  val middle = Offset(start.x + dx, start.y + 4.5f)
  drawLine(p.eye, start, middle, strokeWidth = 2.4f, cap = StrokeCap.Round)
  drawLine(p.eye, middle, Offset(start.x, start.y + 9f), strokeWidth = 2.4f, cap = StrokeCap.Round)
}

/**
 * 木槌。绕**手腕**（75, 96）旋转，不绕锤头 —— 固定轴心让三个角度自动落在同一段
 * 圆弧上，不会出现「一边转一边平移」的滑移感。
 *
 * 锤头宽 21（约占格宽 23%）。再大一点落点会盖住地鼠半张脸，看着像压脸不是敲头。
 */
internal fun DrawScope.drawMoleHammer(p: MolePalette, angleDeg: Float, alpha: Float) {
  if (alpha <= 0.01f) return
  rotate(degrees = angleDeg, pivot = Offset(75f, 96f)) {
    drawRoundRect(
      color = p.hammerHandle.copy(alpha = alpha),
      topLeft = Offset(72.4f, 28.8f),
      size = Size(5.2f, 67.2f),
      cornerRadius = CornerRadius(2.6f),
    )
    drawRoundRect(
      color = p.hammerHead.copy(alpha = alpha),
      topLeft = Offset(64.5f, 19.3f),
      size = Size(21f, 9.5f),
      cornerRadius = CornerRadius(4.4f),
    )
    drawOval(
      color = p.hammerCap.copy(alpha = alpha),
      topLeft = Offset(83.4f, 20f),
      size = Size(4.2f, 8f),
    )
  }
}

/**
 * 落点冲击线：4 条短线从锤头四周射出。
 *
 * [progress] 0 → 1：缩放 0.55 → 1.2，透明度 1 → 0。不做粒子系统 ——
 * 一局才敲 5 下，不值得那点开销。
 */
internal fun DrawScope.drawMoleImpact(p: MolePalette, progress: Float) {
  if (progress <= 0f || progress >= 1f) return
  val scale = 0.55f + 0.65f * progress
  val alpha = 1f - progress
  withTransform({ scale(scale, scale, pivot = Offset(43f, 31f)) }) {
    IMPACT_LINES.forEach { (from, to) ->
      drawLine(
        color = p.impact.copy(alpha = alpha),
        start = from,
        end = to,
        strokeWidth = 1.8f,
        cap = StrokeCap.Round,
      )
    }
  }
}

private fun DrawScope.drawTuft(tuft: GrassTuft, color: Color) {
  withTransform({
    translate(tuft.x, tuft.y)
    rotate(tuft.tilt, pivot = Offset(0f, 0f))
    // 负的 scaleX 把草叶镜像过去 —— 同一套路径，四种「朝向」
    scale(if (tuft.flip) -tuft.scale else tuft.scale, tuft.scale, pivot = Offset(0f, 0f))
  }) {
    TUFT_PATHS.forEach {
      drawPath(path = it, color = color, style = Stroke(width = 1.9f, cap = StrokeCap.Round))
    }
  }
}

/** 三片草叶，基点 (0,0)，向上生长。只建一次，反复 draw。 */
private val TUFT_PATHS: List<Path> = listOf(
  Path().apply {
    moveTo(0f, 0f)
    quadraticBezierTo(-3.6f, -4.2f, -5.6f, -7.6f)
  },
  Path().apply {
    moveTo(0f, 0f)
    quadraticBezierTo(0.2f, -6f, -0.4f, -10.4f)
  },
  Path().apply {
    moveTo(0f, 0f)
    quadraticBezierTo(3.6f, -4.6f, 6.4f, -7.2f)
  },
)

/** 冲击线四笔，围绕锤头落点 (43, 31)。 */
private val IMPACT_LINES: List<Pair<Offset, Offset>> = listOf(
  Offset(34f, 22f) to Offset(28f, 16f),
  Offset(52f, 22f) to Offset(58f, 16f),
  Offset(30f, 33f) to Offset(22f, 36f),
  Offset(56f, 33f) to Offset(64f, 36f),
)
