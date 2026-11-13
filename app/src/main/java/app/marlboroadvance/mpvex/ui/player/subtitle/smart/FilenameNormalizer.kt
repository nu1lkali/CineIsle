package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 文件名标准化的结果。
 *
 * 原始串一律保留（[original]），后续每一步都在它之外产出新串，绝不就地覆盖 ——
 * 否则「为什么这条没匹配上」就再也查不回来了。
 *
 * @property original 原始文件名（只 trim，不做任何改写）
 * @property normalized 字符标准化后的名字：全角转半角、统一大写、`. _ 空格` 统一成 `-`
 * @property stem 归一化的字干：剥掉站点前缀与语言 / 发布标记后再 compact 成小写串
 */
data class NormalizedName(
  val original: String,
  val normalized: String,
  val stem: String,
)

/**
 * 名称标准化（对应设计里的 FilenameNormalizer）。
 *
 * 只做「字符层面」的事，不认识番号、不认识发布组 —— 那些属于
 * [NoiseCleaner] 与 [IdentifierExtractor]。分开的好处是：清洗规则改错时，
 * 标准化这一层的输出仍然可比，能快速二分定位是哪一层出的问题。
 *
 * ## 顺序不能换
 *
 * 剥扩展名必须在「点号归一成连字符」**之前**（否则 `xxx.mp4` 的点没了，扩展名剥不掉）；
 * 剥站点前缀在 [NoiseCleaner] 里做，它看到的是点号已归一的名字，所以域名正则同时认
 * `.` 与 `-` 两种分隔。
 */
object FilenameNormalizer {
  /** 缓存版本。任何一条规则变化都要 +1，否则旧缓存会被继续复用（[SubtitleCache] 读它）。 */
  const val VERSION = 2

  /** 视频容器扩展名。 */
  private val CONTAINER_EXT =
    setOf(
      "strm", "mp4", "mkv", "avi", "ts", "m2ts", "mts", "wmv", "mov", "flv", "webm",
      "rmvb", "rm", "iso", "mpg", "mpeg", "m4v", "vob", "f4v", "3gp", "ogv", "divx",
    )

  /** 字幕扩展名。 */
  private val SUBTITLE_EXT =
    setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "smi", "sami", "ttml", "dfxp", "lrc")

  /** 各类连字符 / 破折号 → `-`。 */
  private val DASH_LIKE =
    setOf('\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\uFE63', '\uFF0D')

  /** 会被当成「词分隔」的字符 → `-`。 */
  private val SEPARATOR_LIKE = setOf('_', '\u00B7', '\u2022', '\uFF5C', '|', '/', '\\')

  /**
   * 标准化主入口。
   *
   * 产物是同一条管线上的中间态：调用方拿到 [NormalizedName] 后交给 [NoiseCleaner]，
   * 再交给 [IdentifierExtractor]。三步互不覆盖，调试时能逐层打印。
   */
  fun normalize(raw: String): NormalizedName {
    val original = raw.trim()
    if (original.isEmpty()) return NormalizedName("", "", "")
    val half = toHalfWidth(original)
    val noExt = stripExtensions(half)
    val normalized = unifySeparators(noExt).uppercase()
    return NormalizedName(original, normalized, NoiseCleaner.stemOf(normalized))
  }

  /**
   * 循环剥掉末尾扩展名，最多四层。
   *
   * 必须循环，因为本项目的媒体名是 smartstrm 产出的 `xxx.(mp4).strm` 形态 ——
   * 只剥一层会留下 `.(mp4)`，于是字幕侧的 `xxx.ja.merged.whisperjav.chinese.srt`
   * 永远等不上「字干完全一致」这一档，只能退到前缀匹配。真实库里这类名字是常态。
   */
  fun stripExtensions(name: String): String {
    var text = name.substringAfterLast('/').substringAfterLast('\\')
    var guard = 0
    while (guard++ < 4) {
      val dot = text.lastIndexOf('.')
      if (dot <= 0) break
      val rawExt = text.substring(dot + 1)
      // `.(mp4)` 这种带括号的写法：括号在点之后、扩展名之前
      val ext = rawExt.trim().trim('(', ')', '[', ']').lowercase()
      if (ext in CONTAINER_EXT || ext in SUBTITLE_EXT) {
        text = text.substring(0, dot)
      } else {
        break
      }
    }
    return text
  }

  /**
   * 全角转半角。
   *
   * 逐字符 1:1 映射，偏移量不变，所以位置信息可以在各层之间直接换算。
   */
  fun toHalfWidth(text: String): String {
    if (text.none { it.code in FULL_WIDTH_RANGE || it.code == IDEOGRAPHIC_SPACE }) return text
    return buildString(text.length) {
      text.forEach { ch ->
        val code = ch.code
        append(
          when {
            code == IDEOGRAPHIC_SPACE -> ' '
            code in FULL_WIDTH_RANGE -> (code - FULL_WIDTH_OFFSET).toChar()
            else -> ch
          }
        )
      }
    }
  }

  /**
   * 统一分隔符并折叠重复。
   *
   * 点号也归成连字符：剥扩展名之后，剩下的点都只是「分隔符」，
   * 保留它会让 `A.B` 与 `A-B` 变成两个不同的串，白白降低字干相似度。
   */
  fun unifySeparators(text: String): String {
    val out = StringBuilder(text.length)
    var lastWasSep = false
    text.forEach { ch ->
      val normalized =
        when {
          ch in DASH_LIKE -> '-'
          ch in SEPARATOR_LIKE -> '-'
          ch == '.' -> '-'
          ch == '\t' -> ' '
          else -> ch
        }
      val isSep = normalized == '-'
      if (isSep && lastWasSep) return@forEach
      out.append(normalized)
      lastWasSep = isSep
    }
    return out.toString().trim(' ', '-')
  }

  /** 只留字母数字（含中日文），大写。用于「字干 / 紧凑串」比较。 */
  fun compact(text: String): String =
    buildString(text.length) {
      text.forEach { ch ->
        if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
      }
    }

  private val FULL_WIDTH_RANGE = 0xFF01..0xFF5E
  private const val FULL_WIDTH_OFFSET = 0xFEE0
  private const val IDEOGRAPHIC_SPACE = 0x3000
}
