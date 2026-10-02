package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面主色缓存。
 *
 * 同一个封面地址在同一屏里可能被问很多次（详情页背景、状态栏配色…），
 * 提取要下载 + 统计，必须缓存；[beginExtract] 再保证**同一地址并发只跑一次**。
 */
object EmbyColorCache {
  private val cache = ConcurrentHashMap<String, Color>()
  private val inFlight = ConcurrentHashMap<String, Boolean>()

  fun get(url: String): Color? = cache[url]

  fun put(url: String, color: Color) {
    cache[url] = color
  }

  /** @return true 表示抢到了这次提取任务，调用方负责在 finally 里 [endExtract] */
  fun beginExtract(url: String): Boolean = inFlight.putIfAbsent(url, true) == null

  fun endExtract(url: String) {
    inFlight.remove(url)
  }
}

/**
 * 取封面**主色**，用于详情页的海报主色渐变背景（需求里的「海报主色渐变」）。
 *
 * 为什么不引 androidx.palette：那是个新依赖（离线构建还要额外拉包），而这里真正需要的
 * 只是一个「拿去做渐变底色不难看」的颜色 —— 自己量化统计一遍就够，逻辑还能完全掌控。
 *
 * @param url 封面地址（已带 api_key 的完整地址）
 * @return 提取到的颜色；还在算 / 算不出来（图挂了、太灰）时为 null，调用方回退到主题色
 */
@Composable
fun rememberDominantColor(url: String?): Color? {
  val color by produceState<Color?>(
    initialValue = url?.let { EmbyColorCache.get(it) },
    key1 = url,
  ) {
    if (url == null) {
      value = null
      return@produceState
    }
    EmbyColorCache.get(url)?.let {
      value = it
      return@produceState
    }
    if (!EmbyColorCache.beginExtract(url)) return@produceState
    try {
      val extracted = extractDominantColor(url)
      if (extracted != null) {
        EmbyColorCache.put(url, extracted)
        value = extracted
      }
    } finally {
      EmbyColorCache.endExtract(url)
    }
  }
  return color
}

/**
 * 取色的四步：
 * 1. 用项目自带的图片加载器拉一张**小图**（48px 宽足够看分布，几乎不费流量）；
 * 2. 缩到 24×24 后逐像素量化到 4bit/通道，统计各档出现次数；
 * 3. 跳过接近纯黑 / 纯白 / 灰调的像素 —— 它们多是黑边、字幕条和纯色底，
 *    如果不跳过，「出现最多的颜色」往往正是它们，拿去做渐变就是一片灰；
 * 4. 取剩下的里最多的那一档，还原成 RGB 并调亮，作为渐变起点。
 */
private suspend fun extractDominantColor(url: String): Color? {
  val source = EmbyImageLoader.load(url, 48) ?: return null
  val small = runCatching { Bitmap.createScaledBitmap(source, 24, 24, true) }.getOrNull() ?: source

  val counts = HashMap<Int, Int>()
  for (y in 0 until small.height) {
    for (x in 0 until small.width) {
      val pixel = small.getPixel(x, y)
      if ((pixel ushr 24) and 0xFF < 128) continue
      val r = (pixel shr 16) and 0xFF
      val g = (pixel shr 8) and 0xFF
      val b = pixel and 0xFF
      val max = maxOf(r, g, b)
      val min = minOf(r, g, b)
      // 纯黑 / 纯白
      if (max < 32 || min > 232) continue
      // 灰调（黑边、渐变压暗区、字幕底）
      if (max - min < 20) continue
      val quantized = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
      counts[quantized] = (counts[quantized] ?: 0) + 1
    }
  }
  if (small !== source) runCatching { small.recycle() }

  val best = counts.maxByOrNull { it.value }?.key ?: return null
  val r = ((best shr 8) and 0xF) * 17
  val g = ((best shr 4) and 0xF) * 17
  val b = (best and 0xF) * 17
  return soften(Color(r, g, b))
}

/**
 * 把取到的原色往「能当背景」的方向调：向白色混一点、再整体提亮。
 *
 * 直接拿封面原色（常见是深蓝 / 深棕）铺满背景会让整页发闷，
 * 而且详情页上方压着白色标题，底色太深/太艳都不好看。
 */
private fun soften(color: Color): Color {
  val mix = 0.45f
  val gain = 1.18f
  return Color(
    red = ((color.red * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    green = ((color.green * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    blue = ((color.blue * (1f - mix) + mix) * gain).coerceIn(0f, 1f),
    alpha = 1f,
  )
}
