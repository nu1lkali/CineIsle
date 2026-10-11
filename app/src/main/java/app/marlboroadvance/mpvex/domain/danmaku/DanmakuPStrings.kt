package app.marlboroadvance.mpvex.domain.danmaku

/**
 * dandanplay 的 `p` 字段解析 —— 纯逻辑，无 Android 依赖，可 JVM 单测。
 *
 * ## 为什么不用 B 站 XML 解析器
 *
 * DanmakuFlameMaster 自带的 `BiliDanmakuParser` 吃的是 B 站 XML（`<?xml version…><d p="…">`）。
 * dandanplay 返回的是 JSON，且 `p` 字段虽然**沿用了 B 站的 csv 列顺序**，但列数不固定
 * （官方实现回 8 列，很多自建服务端只回 4 列）。所以这里自己解析，
 * 再用 DanmakuFlameMaster 的 `DanmakuFactory` 生成渲染对象 ——
 * 引擎只当中立画布用，不碰它的解析层。
 */
object DanmakuPStrings {

  /**
   * 一条解析好的弹幕。
   *
   * @param timeSec 出现时间（**秒**，浮点；dandanplay 的第 1 列）
   * @param mode类型：1=滚动、4=底部、5=顶部、6=反向滚动（对齐 DanmakuFlameMaster 的 TYPE_*）
   * @param textSize 字体档位：25=标准、18=小（对齐 B 站惯例；**不是**像素）
   * @param color 颜色（ARGB int；dandanplay 给的是十进制 RGB）
   */
  data class Parsed(
    val timeSec: Double,
    val mode: Int,
    val textSize: Int,
    val color: Int,
  )

  /** 三个「画布放不下就别画」的类型白名单里的滚动弹幕。 */
  const val MODE_SCROLL_RL = 1
  const val MODE_FIX_BOTTOM = 4
  const val MODE_FIX_TOP = 5
  const val MODE_SCROLL_LR = 6

  /**
   * 解析一行 `p`。
   *
   * @return null 表示这行**不可用**（时间解析不出、列数不足），调用方跳过这一条 ——
   *   返回 null 而不是默认值 0，是因为时间 0 会让弹幕在第 0 秒集体刷屏，
   *   比丢几条弹幕难看得多。
   */
  fun parse(p: String): Parsed? {
    val f = p.split(',')
    if (f.size < 2) return null
    val time = f[0].trim().toDoubleOrNull() ?: return null
    if (time < 0) return null
    val mode = f[1].trim().toIntOrNull() ?: MODE_SCROLL_RL

    // 第 3 列在不同实现里可能是「字体档位」也可能是空的；第 4 列才是颜色。
    // 颜色缺失时给白色（B 站默认），别给黑 —— 黑字在暗场景等于看不见。
    val textSize = f.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 10..60 } ?: 25
    val colorRgb = f.getOrNull(3)?.trim()?.toLongOrNull()?.takeIf { it in 0..0xFFFFFF }?.toInt()
      ?: 0xFFFFFF

    return Parsed(
      timeSec = time,
      // 只认渲染引擎认识的 4 种类型，其余（含特殊弹幕 mode 7）一律当滚动 ——
      // 宁可显示成滚动弹幕，也不要「解析成功但什么都不画」。
      mode = if (mode in setOf(MODE_SCROLL_RL, MODE_FIX_BOTTOM, MODE_FIX_TOP, MODE_SCROLL_LR)) mode
      else MODE_SCROLL_RL,
      textSize = textSize,
      color = colorRgb,
    )
  }

  /**
   * 把「颜色 + 透明度」合成引擎要的 ARGB。
   *
   * dandanplay 给的是 **不含 alpha** 的 RGB，所以透明度只能我们自己乘上去。
   *
   * @param color 颜色，取 [DanmakuComment.colorRgb]（**不含 alpha** 的十进制 RGB）。
   *   字段名与 [DanmakuComment.colorRgb] 刻意保持一致 —— 两个类用同名同义，
   *   避免「串了字段名」这类只有编译器能拦住的错。
   */
  fun toArgb(color: Int, alphaPercent: Int): Int {
    val a = (alphaPercent.coerceIn(0, 100) * 255 / 100)
    return (a shl 24) or (color and 0xFFFFFF)
  }
}

/**
 * 一条弹幕的领域模型（已解析完 `p`，不依赖渲染库）。
 *
 * 单独一层是为了让「解析 / 缓存 / 匹配」都不必认识 DanmakuFlameMaster 的类型 ——
 * 真要换渲染引擎，改的只有 [DanmakuOverlay] 那一个文件。
 */
data class DanmakuComment(
  /** 出现时间，秒。 */
  val timeSec: Double,
  /** 渲染类型，取值同 [DanmakuPStrings.MODE_*]。 */
  val mode: Int,
  /** 字体档位（25=标准 / 18=小）。 */
  val textSize: Int,
  /** 颜色，不含 alpha 的十进制 RGB。 */
  val colorRgb: Int,
  /** 文本内容。 */
  val text: String,
)