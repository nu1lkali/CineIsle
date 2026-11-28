package app.marlboroadvance.mpvex.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 截取 Activity 当前画面并做高斯近似模糊，返回可直接给 Compose `Image` 用的 [ImageBitmap]。
 *
 * 供「已经确定画面静止」的场景使用（媒体实验室小窗预览：打开时页面本身不动）。
 * 调用方须在 Main dispatcher：截图在主线程完成，缩放与模糊切到 [Dispatchers.Default]。
 *
 * ⚠️ **不要**用它给异步重排的遮罩做底图 —— 截图时刻列表可能正在重组 / 滚动 /
 * 惯性滑动，会定格出一张「半新半旧」的画面。那种场景用 [BackdropMaterial]。
 */
suspend fun captureBlurredBackdrop(context: Context): ImageBitmap? {
  val captured = captureWindowBitmap(context) ?: return null
  return blurCapturedBitmap(captured)
}

/** 同步截取 Activity 当前画面（必须在主线程调用），只抓图、不模糊。 */
fun captureWindowBitmap(context: Context): Bitmap? {
  val activity = context.findHostActivity() ?: return null
  return runCatching { captureActivityWindow(activity) }.getOrNull()
}

/** 把已截取的 [source] 后台模糊成 Compose Image；原图无论成败都会被回收。 */
suspend fun blurCapturedBitmap(source: Bitmap): ImageBitmap? {
  val blurred = withContext(Dispatchers.Default) {
    runCatching { downscaleAndBlur(source) }.getOrNull()
  }
  return if (blurred != null) {
    if (blurred !== source) source.recycle()
    blurred.asImageBitmap()
  } else {
    source.recycle()
    null
  }
}

/** 沿 ContextWrapper 链向上找承载它的 Activity。 */
tailrec fun Context.findHostActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findHostActivity()
  else -> null
}

/** 把 Activity 当前画面画进一张 Bitmap。必须在主线程调用。 */
private fun captureActivityWindow(activity: Activity): Bitmap? {
  val view = activity.window?.decorView ?: return null
  if (view.width <= 0 || view.height <= 0) return null
  val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(bitmap))
  return bitmap
}

/**
 * 截图缩小到 1/12，再做两遍半径 2 的盒式模糊。
 *
 * 先缩小再模糊是刻意的：像素少 144 倍，模糊成本可以忽略；放大时双线性过滤
 * 会进一步平滑，最终效果接近高斯模糊，而且不会有 RenderEffect 的机型兼容问题。
 */
private fun downscaleAndBlur(source: Bitmap): Bitmap {
  val width = (source.width / 12).coerceIn(24, 240)
  val height = (source.height / 12).coerceIn(24, 480)
  var small = Bitmap.createScaledBitmap(source, width, height, true)
  repeat(2) {
    val next = boxBlur(small, 2)
    if (next !== small) small.recycle()
    small = next
  }
  return small
}

/**
 * 「内容更新时间」重排遮罩的背景材质（v353）。
 *
 * ## 为什么不再截图
 * 前两版都是「截当前画面 → 缩小 → 模糊」。根子上有问题的是**时机**：截图总要
 * 落在某个不确定的时刻，而那一刻列表可能正在重组、跳位、惯性滑动 —— 于是遮罩
 * 上会定格出一张过渡中的画面。改成「在改 sortBy 之前抓帧」只躲得掉一部分，
 * 用户点菜单时列表本身也可能是滑着的。
 *
 * ⇒ **任何截图方案都无法保证画面稳定**，因为「稳定」这个前提在这里不成立。
 * 所以换成一张与页面状态完全无关的底图：代码画出的层叠柔光渐变，再做模糊。
 *  · 与列表内容、滚动位置、动画进度全部无关 ⇒ 不可能截到中间态；
 *  · 不需要主线程抓图，可在后台算好并缓存（同一主题只算一次）；
 *  · 不引入任何图片资源，包体零增长，深浅色主题自动跟随。
 *
 * 观感上是典型的「磨砂玻璃」：压暗的底色 + 三团大面积柔光 + 斜向明暗，
 * 上层还会叠黑色 scrim 与提示文案，所以它只负责提供质感层次，不抢内容。
 */
internal object BackdropMaterial {
  private val cache = HashMap<String, Bitmap>()

  private const val WIDTH = 120
  private const val HEIGHT = 240

  /**
   * 取（或生成）当前主题对应的磨砂底图。须在后台线程调用。
   *
   * @param primary 主题主色，决定主柔光色相
   * @param secondary 次色，补一层对比色相
   * @param tertiary 第三色，压一侧增加纵深
   * @param dark 深色主题：整体压暗，避免毛玻璃比内容还亮
   */
  fun get(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    dark: Boolean,
  ): ImageBitmap {
    val key = "${primary.value}|${secondary.value}|${tertiary.value}|$dark"
    cache[key]?.let { if (!it.isRecycled) return it.asImageBitmap() }

    val bitmap = render(primary, secondary, tertiary, dark)
    // 只留最近几份：换主题 / 切深浅色不会无限增长
    if (cache.size > 4) {
      val first = cache.keys.firstOrNull()
      if (first != null) cache.remove(first)?.recycle()
    }
    cache[key] = bitmap
    return bitmap.asImageBitmap()
  }

  private fun render(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    dark: Boolean,
  ): Bitmap {
    val pixels = IntArray(WIDTH * HEIGHT)
    val base = if (dark) 0xFF14161A.toInt() else 0xFFF2F3F6.toInt()
    val baseR = (base shr 16) and 0xFF
    val baseG = (base shr 8) and 0xFF
    val baseB = base and 0xFF

    // 三团柔光：位置 / 半径都是相对坐标（0~1），与屏幕尺寸和密度无关。
    val blobs = listOf(
      Blob(0.22f, 0.12f, 0.62f, if (dark) 0.50f else 0.42f, primary),
      Blob(0.86f, 0.34f, 0.55f, if (dark) 0.34f else 0.28f, secondary),
      Blob(0.40f, 0.92f, 0.70f, if (dark) 0.30f else 0.22f, tertiary),
    )
    val args = blobs.map { it.color.toArgb() }

    for (y in 0 until HEIGHT) {
      val ny = y.toFloat() / HEIGHT
      for (x in 0 until WIDTH) {
        val nx = x.toFloat() / WIDTH
        var r = baseR.toFloat()
        var g = baseG.toFloat()
        var b = baseB.toFloat()

        for (i in blobs.indices) {
          val blob = blobs[i]
          val dx = nx - blob.cx
          val dy = ny - blob.cy
          val falloff = 1f - sqrt(dx * dx + dy * dy) / blob.radius
          if (falloff <= 0f) continue
          // 平方衰减：过渡更像真正的柔光，而不是一个硬边圆
          val weight = falloff * falloff * blob.strength
          val argb = args[i]
          r += (((argb shr 16) and 0xFF) - r) * weight
          g += (((argb shr 8) and 0xFF) - g) * weight
          b += ((argb and 0xFF) - b) * weight
        }

        // 斜向明暗：左上略亮、右下略沉，给磨砂面一点方向感
        val shade = (0.5f - (nx * 0.55f + ny * 0.45f)) * (if (dark) 26f else 18f)
        r = (r + shade).coerceIn(0f, 255f)
        g = (g + shade).coerceIn(0f, 255f)
        b = (b + shade).coerceIn(0f, 255f)

        pixels[y * WIDTH + x] =
          (0xFF shl 24) or
            ((r.toInt() and 0xFF) shl 16) or
            ((g.toInt() and 0xFF) shl 8) or
            (b.toInt() and 0xFF)
      }
    }

    // 大半径模糊：小图上两遍半径 3，放大时双线性过滤继续平滑。
    var small = Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    repeat(2) {
      val next = boxBlur(small, 3)
      if (next !== small) small.recycle()
      small = next
    }
    return small
  }

  private class Blob(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val strength: Float,
    val color: Color,
  )
}

/** 简单的可分离盒式模糊；在小图上跑，成本很低。 */
private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
  val width = source.width
  val height = source.height
  if (width <= 0 || height <= 0) return source
  val input = IntArray(width * height)
  source.getPixels(input, 0, width, 0, 0, width, height)
  val horizontal = IntArray(width * height)
  val window = radius * 2 + 1

  for (y in 0 until height) {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val pixel = input[y * width + i.coerceIn(0, width - 1)]
      a += pixel ushr 24 and 0xFF
      r += pixel shr 16 and 0xFF
      g += pixel shr 8 and 0xFF
      b += pixel and 0xFF
    }
    for (x in 0 until width) {
      horizontal[y * width + x] =
        (a / window shl 24) or
        (r / window shl 16) or
        (g / window shl 8) or
        (b / window)
      val old = input[y * width + (x - radius).coerceIn(0, width - 1)]
      val new = input[y * width + (x + radius + 1).coerceIn(0, width - 1)]
      a += (new ushr 24 and 0xFF) - (old ushr 24 and 0xFF)
      r += (new shr 16 and 0xFF) - (old shr 16 and 0xFF)
      g += (new shr 8 and 0xFF) - (old shr 8 and 0xFF)
      b += (new and 0xFF) - (old and 0xFF)
    }
  }

  val output = IntArray(width * height)
  for (x in 0 until width) {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val pixel = horizontal[i.coerceIn(0, height - 1) * width + x]
      a += pixel ushr 24 and 0xFF
      r += pixel shr 16 and 0xFF
      g += pixel shr 8 and 0xFF
      b += pixel and 0xFF
    }
    for (y in 0 until height) {
      output[y * width + x] =
        (a / window shl 24) or
        (r / window shl 16) or
        (g / window shl 8) or
        (b / window)
      val old = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
      val new = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
      a += (new ushr 24 and 0xFF) - (old ushr 24 and 0xFF)
      r += (new shr 16 and 0xFF) - (old shr 16 and 0xFF)
      g += (new shr 8 and 0xFF) - (old shr 8 and 0xFF)
      b += (new and 0xFF) - (old and 0xFF)
    }
  }

  return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}
