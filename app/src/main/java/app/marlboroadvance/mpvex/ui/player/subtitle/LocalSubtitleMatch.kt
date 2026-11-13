package app.marlboroadvance.mpvex.ui.player.subtitle

import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser

/**
 * 本地字幕「按番号智能匹配」的纯匹配层：只吃字符串，不碰 Android、不碰文件系统。
 *
 * ## 为什么要单独一层
 *
 * 结算口径（哪个算「同一条片子」）是这个功能唯一的风险点：松一点满屏无关字幕，
 * 紧一点一条都出不来。把它写成纯函数才能在单测里把两头都钉住，
 * 而不是每次靠装 APK 手点。
 *
 * ## 匹配优先级（分数由高到低，命中即止）
 *
 * 1. **番号**（`SSIS-123` / `FC2-PPV-1234567` / `123456-789`）——最硬的证据。
 *    两侧都归一化成「大写 + 只留字母数字」再比包含关系，于是
 *    `SSIS-123`、`ssis123`、`SSIS_123-C` 互相都能认出来。
 * 2. **CRC32**——在线字幕落盘时目录名就是媒体名的 CRC（见 `PlayerViewModel.scanLocalSubtitles`），
 *    命中它等于命中「当初专门为这部片子建的那个目录」。
 * 3. **标题词重合度**——给没有番号的片子（欧美电影 / 番剧）兜底，重合度需 ≥ 50%。
 *    单靠集号**不足以**命中：`03` 这种数字在字幕目录里满地都是。
 *
 * ## 刻意不做的
 *
 * - 不猜「同一系列的不同集」：集号只用来**加分**，不用来放宽标题门槛。
 * - 不读字幕内容（不解析 srt 头、不比时长）：那要把每个文件都打开一遍，
 *   对一个「打开面板时顺手给的建议」来说成本不成比例。
 */
object LocalSubtitleMatch {
  /** 命中理由。UI 只用来给出一句人话，不参与排序细节。 */
  enum class Reason {
    /** 番号一致 */
    Code,

    /** CRC32 一致（字幕目录的目录名就是它） */
    Crc,

    /** 集号一致（通常同时有标题重合） */
    Episode,

    /** 标题词重合 */
    Title,
  }

  /** 一条评分结果。[score] 越大越可信，跨理由可比。 */
  data class Hit(val score: Int, val reason: Reason)

  /**
   * 一次匹配的查询条件。
   *
   * @property displayCode 原样的番号（`SSIS-123`），只用于文案；没有番号时为 null
   * @property title 解析出的片名（`The Dark Knight`），没有番号时它就是文案里的「匹配依据」
   * @property codeKeys 归一化后的番号（`SSIS123`）
   * @property crcKeys 媒体名的 CRC32（大写字面量）
   * @property titleTokens 标题里的有效词（ASCII 词取 ≥3 字符，中日文取整段）
   * @property episodeNumber 集号（解析不出来时为 null）
   * @property season 季号，仅用于展示与调试
   */
  data class Query(
    val displayCode: String?,
    val title: String?,
    val codeKeys: Set<String>,
    val crcKeys: Set<String>,
    val titleTokens: List<String>,
    val episodeNumber: Int?,
    val season: Int?,
  ) {
    /** 一个可用条件都没有 ⇒ 别扫了，扫了也只能靠猜。 */
    val isEmpty: Boolean
      get() = codeKeys.isEmpty() && crcKeys.isEmpty() && titleTokens.isEmpty()

    /**
     * 文案里「我在找什么」的那句话：优先番号，其次片名。
     *
     * 只用于 UI，不参与匹配 —— 所以允许为 null（两个都认不出来时就不该给「未找到」提示）。
     */
    val label: String?
      get() = displayCode ?: title?.takeIf { it.isNotBlank() }
  }

  /** 番号里不能当前缀的「字母部分」：都是发布信息 / 编码 / 语言标签。 */
  private val CODE_PREFIX_BLACKLIST = setOf(
    // 来源与分辨率
    "BD", "BDRIP", "BDREMUX", "WEB", "WEBDL", "WEBRIP", "HDTV", "HD", "FHD", "UHD",
    "SD", "DVD", "DVDRIP", "REMUX", "HDR", "SDR", "DV", "HDRIP", "BRRIP",
    // 编码
    "AVC", "HEVC", "X264", "X265", "H264", "H265", "AV1", "VP9", "MPEG", "AAC", "AC3",
    "DTS", "DDP", "DD", "FLAC", "TRUEHD", "ATMOS", "OPUS", "LPCM",
    // 平台
    "NF", "AMZN", "ATVP", "HMAX", "DSNP", "PC", "HULU", "STAN",
    // 语言 / 字幕轨
    "CHS", "CHT", "GB", "BIG5", "ENG", "JPN", "KOR", "MULTI", "DUAL", "SUB", "SUBS",
    "ASS", "SRT", "SSA", "VTT",
    // 结构与容器
    "CD", "DISC", "PART", "PT", "VOL", "DISC1", "EP", "MKV", "MP4", "AVI", "M2TS",
    "TS", "TC", "CAM", "SC", "SAMPLE",
  )

  /** 这些数字不是番号，是分辨率。 */
  private val RESOLUTION_NUMBERS = setOf("480", "576", "720", "1080", "2160", "4320", "60", "50")

  /**
   * 番号的候选写法。**必须有分隔符**（`-` `_` 或空格）：
   * 允许无分隔会立刻把 `BD1080`、`WEB2160` 这类拼接标签认成番号。
   */
  private val CODE_REGEXES = listOf(
    // FC2 系：FC2-PPV-1234567
    Regex("""(?i)\bFC2[-_. ]?PPV[-_. ]?\d{5,8}\b"""),
    // HEYZO 系
    Regex("""(?i)\bHEYZO[-_. ]?\d{3,5}\b"""),
    // 素人系：123456-789
    Regex("""\b\d{6}[-_]\d{3}\b"""),
    // 通贩系：SSIS-123 / IPX-001 / ABP-123
    Regex("""(?i)\b[A-Z]{2,5}[-_ ]\d{2,5}\b"""),
  )

  private val BRACKET_HEX_REGEX = Regex("""[\[(（【]([0-9A-Fa-f]{8})[\])）】]""")

  private val ASCII_TOKEN_REGEX = Regex("""[A-Za-z][A-Za-z0-9']{2,}""")
  private val CJK_TOKEN_REGEX = Regex("""[\u4e00-\u9fff\u3040-\u30ff]{2,}""")

  /** 标题词上限：再多也不会更准，只会让「重合度」被长尾词稀释。 */
  private const val MAX_TITLE_TOKENS = 6

  /**
   * 从若干「媒体名」构造查询条件。
   *
   * 传多个是因为调用方手里可能同时有文件名和媒体标题，两者在 Emby 场景下不一定相同；
   * 番号 / CRC 取并集（多一个 key 只会更容易命中同一个片子），标题与集号取第一个能解析出来的。
   */
  fun buildQuery(vararg rawNames: String): Query {
    val names = rawNames.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) {
      return Query(
        displayCode = null,
        title = null,
        codeKeys = emptySet(),
        crcKeys = emptySet(),
        titleTokens = emptyList(),
        episodeNumber = null,
        season = null,
      )
    }

    val codeKeys = LinkedHashSet<String>()
    val crcKeys = LinkedHashSet<String>()
    var displayCode: String? = null

    names.forEach { raw ->
      val stem = stripExtension(raw)
      for (regex in CODE_REGEXES) {
        for (match in regex.findAll(stem)) {
          val key = codeKeyOf(match.value) ?: continue
          if (codeKeys.add(key) && displayCode == null) displayCode = match.value.uppercase()
        }
      }
      // 括号里的 8 位十六进制是发布组算的 CRC，出现在字幕文件名里时是最强证据之一
      for (crc in BRACKET_HEX_REGEX.findAll(stem)) crcKeys.add(crc.groupValues[1].uppercase())
      // 与 scanLocalSubtitles 建目录时同一口径：媒体名的 CRC32
      runCatching { crcKeys.add(ChecksumUtils.getCRC32(raw).uppercase()) }
    }

    val primary = MediaInfoParser.parse(names.first())
    val titleTokens = titleTokensOf(primary.title).take(MAX_TITLE_TOKENS)

    return Query(
      displayCode = displayCode,
      title = primary.title.takeIf { it.isNotBlank() },
      codeKeys = codeKeys,
      crcKeys = crcKeys.filter { it.length >= 6 }.toSet(),
      titleTokens = titleTokens,
      episodeNumber = primary.episode,
      season = primary.season,
    )
  }

  /**
   * 给一条字幕（相对路径，含所在目录）打分；够不上门槛返回 null。
   *
   * 比对对象是**相对路径**而不是纯文件名：用户常把字幕放在以番号命名的子目录里，
   * 只有文件名未必带番号（`zh.srt`），路径里却有。
   */
  fun match(query: Query, candidatePath: String): Hit? {
    if (candidatePath.isBlank()) return null
    val compact = compactOf(candidatePath)
    if (compact.isBlank()) return null
    val lowered = candidatePath.lowercase()

    query.codeKeys.firstOrNull { compact.contains(it) }?.let { return Hit(100, Reason.Code) }
    query.crcKeys.firstOrNull { compact.contains(it) }?.let { return Hit(95, Reason.Crc) }

    // 标题重合度低于一半就直接放弃：宁可不出，也不要出错的
    val ratio = titleRatio(query.titleTokens, lowered, compact)
    if (ratio < 0.5) return null

    val episodeHit = query.episodeNumber != null && episodeOf(candidatePath) == query.episodeNumber
    return if (episodeHit) {
      Hit(60 + (ratio * 20).toInt(), Reason.Episode)
    } else {
      Hit(40 + (ratio * 20).toInt(), Reason.Title)
    }
  }

  // ── 内部工具 ────────────────────────────────────────────────────────────────

  /** 番号归一化：`SSIS-123` → `SSIS123`；前缀命中黑名单 / 数字是分辨率时判为不是番号。 */
  private fun codeKeyOf(raw: String): String? {
    val letters = raw.takeWhile { it.isLetter() }.uppercase()
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 2) return null
    if (digits in RESOLUTION_NUMBERS) return null
    if (digits.toIntOrNull()?.let { it in 1900..2099 } == true) return null // 年份不是番号
    // 纯数字番号（素人系 123456-789）：没有字母前缀可查黑名单，只要求长度够，避免把集号当番号
    if (letters.isEmpty()) return compactOf(raw).takeIf { digits.length in 8..9 }
    if (letters.length < 2) return null
    if (letters in CODE_PREFIX_BLACKLIST) return null
    return compactOf(raw)
  }

  /** 大写 + 只留字母数字（保留中日文，`isLetter()` 对它们为真）。 */
  private fun compactOf(text: String): String =
    buildString(text.length) {
      text.forEach { ch ->
        if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
      }
    }

  private fun stripExtension(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    return if (dot > 0) base.substring(0, dot) else base
  }

  private fun titleTokensOf(title: String): List<String> {
    if (title.isBlank()) return emptyList()
    val ascii = ASCII_TOKEN_REGEX.findAll(title)
      .map { it.value.lowercase() }
      .filterNot { it in TITLE_STOP_WORDS }
      .toList()
    val cjk = CJK_TOKEN_REGEX.findAll(title).map { it.value }.toList()
    // ASCII 词更可靠（错一个字母就不重合），放前面
    return (ascii + cjk).distinct()
  }

  private fun titleRatio(
    tokens: List<String>,
    loweredPath: String,
    compactPath: String,
  ): Double {
    if (tokens.isEmpty()) return 0.0
    val hits =
      tokens.count { token ->
        if (token.first().code < 0x80) loweredPath.contains(token) else compactPath.contains(token)
      }
    return hits.toDouble() / tokens.size
  }

  private fun episodeOf(candidatePath: String): Int? =
    runCatching { MediaInfoParser.parse(stripExtension(candidatePath)).episode }.getOrNull()

  /**
   * 标题里不参与匹配的词。
   *
   * 这些词在字幕目录里几乎每个文件都有，留着只会把「重合度」抬高到毫无区分度。
   */
  private val TITLE_STOP_WORDS = setOf(
    "the", "and", "for", "with", "from", "that", "this", "you", "your",
    "movie", "film", "video", "sample", "trailer",
    "sub", "subs", "subtitle", "subtitles", "chi", "eng", "jpn",
  )
}
