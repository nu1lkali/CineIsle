package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 从文件名里提取出来的一个「核心标识符」候选。
 *
 * @property raw 原始片段（`ABC-0123B`），用于展示与日志
 * @property letters 字母段（`ABC`）。纯数字番号（素人日期型）为空串
 * @property numericRaw 数字段原样，**保留前导零**（`0123`）
 * @property numericCore 去掉前导零的数字段（`123`），比较时用它
 * @property suffix 紧贴数字段之后的字母后缀（`ABC-123B` 的 `B`）；有分隔符时为空
 * @property confidence 提取置信度 0..1。规则越专有越高（FC2 / HEYZO / 日期型 > 连写 > 裸尾号）
 * @property start 在清洗后名字里的起点，仅用于日志与调试
 */
data class Identifier(
  val raw: String,
  val letters: String,
  val numericRaw: String,
  val numericCore: String,
  val suffix: String,
  val confidence: Double,
  val start: Int,
) {
  /** 归一化键：字母段 + 数字主体，不含后缀。 */
  val body: String
    get() = letters + numericCore
}

/**
 * 核心标识符提取（对应设计里的 IdentifierExtractor）。
 *
 * ## 为什么不能只靠「完整文件名相等」
 *
 * 真实库里视频叫 `www.98T.la@n0907_iris2.(mp4).strm`，字幕叫
 * `n0907_runa_kobayashi_na_n_fhd.ja.pass1.chinese.srt`。两者没有公共前缀可言，
 * 唯一能对上的是 `N0907` 这个编号本身。
 *
 * ## 为什么必须有边界与结构
 *
 * 旧实现用 `contains` 判番号，于是 `N0907` 命中 `N09070`、`SSIS-123` 命中 `SSIS-1234`。
 * 这里把番号拆成 **(字母段, 数字主体, 字母后缀) 三段**，
 * 加法只做「三元组相等」，减法（[IdentifierNormalizer]）才允许前缀 / 后缀这类弱关系，
 * 并且弱关系一律降档、不参与自动匹配。
 *
 * ## 尾号规则为什么是「后备」
 *
 * 加勒比系的视频名是 `022313-272`（日期 + 作品号），字幕名却只写 `272-carib`。
 * 只留 3 位数字当番号会大面积误命中（`123` 这种段到处都是），所以裸数字
 * **只在整条名字没有别的编号时才启用**，且置信度只有 0.35，永远进不了自动匹配。
 */
object IdentifierExtractor {
  /** 规则版本，参与缓存键。 */
  const val VERSION = 2

  /** 番号里不能当前缀的字母段：都是发布信息 / 编码 / 语言 / 容器。 */
  private val LETTER_BLACKLIST =
    setOf(
      // 来源与分辨率
      "BD", "BDRIP", "BDREMUX", "WEB", "WEBDL", "WEBRIP", "HDTV", "HD", "FHD", "UHD",
      "SD", "DVD", "DVDRIP", "REMUX", "HDR", "SDR", "DV", "HDRIP", "BRRIP", "QHD",
      // 编码
      "AVC", "HEVC", "X264", "X265", "H264", "H265", "AV1", "VP9", "VP8", "MPEG", "MPEG4",
      "AAC", "AC3", "EAC3", "DTS", "DDP", "DD", "FLAC", "TRUEHD", "ATMOS", "OPUS", "LPCM", "MP3",
      // 平台
      "NF", "AMZN", "ATVP", "HMAX", "DSNP", "PC", "HULU", "STAN",
      // 语言 / 字幕轨
      "CHS", "CHT", "GB", "BIG5", "ENG", "JPN", "KOR", "MULTI", "DUAL", "SUB", "SUBS",
      "ASS", "SRT", "SSA", "VTT",
      // 结构与容器
      "CD", "DISC", "PART", "PT", "VOL", "EP", "MKV", "MP4", "AVI", "M2TS",
      "TS", "TC", "CAM", "SC", "SAMPLE", "PV", "SP",
    )

  /** 这些数字不是番号，是分辨率 / 帧率 / 段落号。 */
  private val NON_CODE_NUMBERS = setOf("480", "576", "720", "1080", "2160", "4320", "60", "50", "24", "25", "30")

  /** 一条提取规则：正则 + 置信度 + 从 MatchResult 里取三段。 */
  private class Rule(
    val name: String,
    val regex: Regex,
    val confidence: Double,
    val parse: (MatchResult) -> Triple<String, String, String>?,
  )

  /**
   * 具体厂牌规则。顺序只影响日志可读性，不影响结果（全部都会跑一遍）。
   *
   * 手写 `(?<![A-Z0-9])` 而不是 `\b`：Java 的 `\b` 把中日文当字母，
   * `N0907中文字幕` 里 `7` 与 `中` 之间没有词边界，番号会整条提不出来。
   */
  private val RULES =
    listOf(
      Rule(
        "FC2",
        Regex("(?<![A-Z0-9])(FC2)-?(?:PPV-?)?(\\d{5,8})(?![A-Z0-9])"),
        0.96,
      ) { m -> Triple("FC2", m.groupValues[2], "") },
      Rule(
        "HEYZO",
        Regex("(?<![A-Z0-9])(HEYZO)-?(?:(?:HD|FHD|4K)-?)?(\\d{3,5})(?![A-Z0-9])"),
        0.96,
      ) { m -> Triple("HEYZO", m.groupValues[2], "") },
      // 素人 / 加勒比系：011214-521、082313-649
      Rule(
        "DATE",
        Regex("(?<![A-Z0-9])(\\d{6})-(\\d{3})(?![A-Z0-9])"),
        0.94,
      ) { m -> Triple("", m.groupValues[1] + m.groupValues[2], "") },
      // 数字在前的厂牌：259LUXU-1234、300MIUM-123
      Rule(
        "DIGIT_BRAND",
        Regex("(?<![A-Z0-9])(\\d{2,3})([A-Z]{2,6})-?(\\d{3,7})(?![A-Z0-9])"),
        0.90,
      ) { m -> Triple(m.groupValues[2], m.groupValues[1] + m.groupValues[3], "") },
      // 通贩系：ABC-123 / ABC-123B / GACHIG-140 / IPX-1（去零写法，见 toIdentifier）
      Rule(
        "VENDOR",
        Regex("(?<![A-Z0-9])([A-Z]{2,8})-(\\d{1,6})([A-Z]{0,2})(?![A-Z0-9])"),
        0.88,
      ) { m -> Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) },
      // 无分隔符连写：SSIS00123 / GACHIG140
      Rule(
        "COMPACT",
        Regex("(?<![A-Z0-9])([A-Z]{2,8})(\\d{3,6})(?![A-Z0-9])"),
        0.86,
      ) { m -> Triple(m.groupValues[1], m.groupValues[2], "") },
      // 单字母：TokyoHot 的 n0907 / k0583。数字 ≥4 位，x264 与 h265（3 位）天然被挡在外面
      Rule(
        "SINGLE",
        Regex("(?<![A-Z0-9])([A-Z])-?(\\d{4,6})(?![A-Z0-9])"),
        0.82,
      ) { m -> Triple(m.groupValues[1], m.groupValues[2], "") },
      // 后备：裸 3~4 位数字（272-carib）。仅在没有任何别的编号时启用
      Rule(
        "TAIL",
        Regex("(?<![A-Z0-9])(\\d{3,4})(?![A-Z0-9])"),
        0.35,
      ) { m -> Triple("", m.groupValues[1], "") },
    )

  /**
   * 提取全部候选，按置信度降序。
   *
   * @param cleaned 已清洗、已大写的名字（[NoiseCleaner.clean] 的产物）
   */
  fun extract(cleaned: String): List<Identifier> {
    if (cleaned.isBlank()) return emptyList()
    val strong = mutableListOf<Identifier>()
    var tailHits: List<Identifier> = emptyList()

    for (rule in RULES) {
      val hits = rule.regex.findAll(cleaned).mapNotNull { match -> toIdentifier(rule, match, cleaned) }
      for (hit in hits) {
        if (rule.name == "TAIL") {
          tailHits = tailHits + hit
        } else {
          strong += hit
        }
      }
    }

    // 裸数字只在「这一次什么都没认出来」时兜底：`272-CARIB` 能救回来，
    // 但 `ABC-123-272` 这种已经有正式番号的不会被塞进第二个假番号。
    val result = if (strong.isEmpty()) tailHits else strong

    // 过滤只发生在 [toIdentifier] 一处。这里再补一条「去零后长度」的过滤，
    // 就会和那边的口径悄悄分叉 —— `IPX-001` / `IPX-1` 正是被这种「第二道闸门」
    // 反复滤掉（去零后只剩 1 位），而两处判定永远不可能保持同步。
    return result
      .distinctBy { it.letters + "|" + it.numericCore + "|" + it.suffix }
      .sortedByDescending { it.confidence }
  }

  private fun toIdentifier(rule: Rule, match: MatchResult, text: String): Identifier? {
    val triple = rule.parse(match) ?: return null
    val (letters, digitsRaw, suffix) = triple
    if (digitsRaw.isEmpty()) return null
    if (digitsRaw in NON_CODE_NUMBERS) return null
    // 年份不是番号（1980~2099 的 4 位纯数字）
    if (digitsRaw.length == 4 && digitsRaw.toIntOrNull()?.let { it in 1900..2099 } == true) return null

    val upperLetters = letters.uppercase()
    if (upperLetters.length >= 2 && upperLetters in LETTER_BLACKLIST) return null
    // 纯数字番号（日期型）只在数字够长时成立，否则 `720` 这种分辨率会变成番号
    if (upperLetters.isEmpty() && digitsRaw.length < 3) return null
    if (upperLetters.length == 1 && digitsRaw.length < 4) return null

    val core = digitsRaw.trimStart('0').ifEmpty { "0" }
    // 「够不够长」看**原样位数**，不看去零后的位数：`IPX-001` 去零后只剩 `1`，
    // 但它显然是一个番号，去零只用于比较（前导零判定）。
    //
    // 一位数字要更严：字母段 ≥3，且整条名字里除了它就再没有别的内容。
    // `IPX-1.chs.srt` 清洗后正好是 `IPX-1`，成立；而 `050418-002-CARIBPR-5` 里的
    // `-5` 若不拦，就会被当成厂牌 `CARIBPR` 的第 5 部，与真实编号并列后触发
    // 「编号自相矛盾」，把本来完全正确的那条候选压到 89 分。
    if (digitsRaw.length < 2) {
      if (upperLetters.length < 3) return null
      val compact = FilenameNormalizer.compact(text)
      if (compact != upperLetters + core && compact != upperLetters + digitsRaw) return null
    }

    return Identifier(
      raw = match.value,
      letters = upperLetters,
      numericRaw = digitsRaw,
      numericCore = core,
      suffix = suffix.uppercase(),
      confidence = rule.confidence,
      start = match.range.first.coerceAtLeast(0),
    )
  }
}
