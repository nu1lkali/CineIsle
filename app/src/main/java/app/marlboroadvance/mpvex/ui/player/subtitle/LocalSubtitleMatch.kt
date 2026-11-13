package app.marlboroadvance.mpvex.ui.player.subtitle

import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser

/**
 * 本地字幕「智能匹配」的纯匹配层：只吃字符串，不碰 Android、不碰文件系统。
 *
 * ## 为什么单独一层
 *
 * 「哪条字幕算同一条片子」是这个功能唯一的风险点：松一点满屏无关字幕，紧一点一条都出不来。
 * 写成纯函数才能在单测里把两头都钉住，而不是每次装 APK 手点。
 *
 * ## 为什么**不看**视频时长（v252 的重要修正）
 *
 * 曾经想过用「字幕末条 cue 的结束时间 ≈ 视频时长」当判据，这个想法是错的：
 *
 * - 片尾曲通常没有字幕，末条 cue 比视频短几十秒是**正常**的；
 * - 字幕组招新广告会把末条推到视频长度之外；
 * - 分段字幕（CD1/CD2）只覆盖一半；
 * - 有码/无码、加长版、不同剪辑，同一部片子差几分钟很常见；
 * - 帧率差异（23.976 / 25 / 29.97）会让整条时间轴线性漂移；
 * - 机器识别重跑的字幕（whisper 之类），时间轴来自识别结果，末尾偏几十秒也不奇怪。
 *
 * 也就是说，时长只能算「看起来像」的弱信号。**一旦拿它做否决，就会把正确的字幕误杀**，
 * 那比漏推更糟。所以这里刻意不读字幕内容、不做任何 I/O，判定 100% 基于文件名。
 *
 * ## 旧版为什么会「时准时不准」
 *
 * 旧实现用 `compact.contains(key)` 与 `path.contains(token)` 判命中，没有词边界：
 *
 * - `SSIS-123` 会命中 `SSIS-1234`（前缀被当成同一个番号）；
 * - `carib` 会命中 `caribbean`；
 * - 同番号但集号不同的字幕照样满 100 分。
 *
 * 现在改为：番号比对 **(字母段, 数字段) 二元组**，标题词比对**词边界**，并补两条否决规则。
 *
 * ## 分层（越靠前越硬，命中即止）
 *
 * 1. **字干一致**（[Reason.Stem]，100）——剥掉语言标签与扩展名后归一化相等。
 *    `...whisperjav.chinese.srt` 剥出 `...whisperjav`，与媒体名完全一致，这是最可靠的一种。
 * 2. **字干前缀**（[Reason.Stem]，88）——一方是另一方的前缀，且差异片段**不含数字**。
 *    允许 `-GROUP`、`.zh-CN` 这类尾缀，但 `SSIS-123` 与 `SSIS-1234` 的差异含数字，被挡在门外。
 * 3. **番号相等**（[Reason.Code]，92）——归一化成二元组后**完全相等**，不是 contains。
 * 4. **CRC 相等**（[Reason.Crc]，88）——独立出现的 8 位十六进制集合求交集。
 * 5. **标题 + 集号**（[Reason.Episode]，74）/ **仅标题**（[Reason.Title]，62）——
 *    词边界命中率 ≥ 0.6 且至少两个词（或一个长度 ≥ 6 的长词）。
 *
 * 其余情况一律返回 null（宁缺勿错）。
 *
 * ## 否决
 *
 * - 候选里有番号、但与查询的番号没有交集 ⇒ 这不是同一部片子；
 * - 两边都解析出集号且不相等 ⇒ 这是同一系列的另一集。
 *
 * ## 只影响排序、不影响入选的加分
 *
 * 语言偏好（简中 > 繁中 > 中日双语 > 日文 > 英文 > 无标签）、
 * 番号出现在**目录名**而非文件名（手工归档的更强证据）、路径越浅。
 */
object LocalSubtitleMatch {
  /**
   * 命中理由。顺序即同分时的优先级（[Stem] 最可信）。
   *
   * UI 只用来给出一句人话，不参与具体分数计算。
   */
  enum class Reason {
    /** 文件名（剥语言标签后）与媒体名一致 */
    Stem,

    /** 番号一致 */
    Code,

    /** CRC32 一致（字幕目录的目录名就是它） */
    Crc,

    /** 集号一致（通常同时有标题重合） */
    Episode,

    /** 标题词重合 */
    Title,
  }

  /**
   * 一个番号的两个组成部分。
   *
   * 分开存是为了「完全相等」而不是「前缀包含」：
   * `SSIS-123` 与 `SSIS-1234` 的字母段都是 `SSIS`，但数字段 `123` ≠ `1234`，
   * 于是不会被认成同一条。这是旧版最大的误命中来源。
   */
  data class CodeToken(val letters: String, val digits: String) {
    val compact: String
      get() = letters + digits
  }

  /** 一条评分结果。[score] 越大越可信，跨理由可比（层级已拉开距离）。 */
  data class Hit(val score: Int, val reason: Reason)

  /**
   * 一次匹配的查询条件。
   *
   * @property displayCode 原样的番号（`SSIS-123`），只用于文案；没有番号时为 null
   * @property title 解析出的片名（`The Dark Knight`），没有番号时它就是文案里的「匹配依据」
   * @property codes 归一化后的番号（字母段 + 数字段）
   * @property crcKeys 8 位十六进制（媒体名的 CRC32，以及媒体名里方括号中的发布组 CRC）
   * @property titleTokens 标题里的有效词（ASCII 词取 ≥3 字符，中日文取整段）
   * @property episodeNumber 集号（解析不出来时为 null）
   * @property season 季号，仅用于展示与调试
   * @property coreStem 媒体名剥掉语言标签与扩展名后的字干（已归一化）
   * @property versionFlags 版本标记（`UC` / `4K` / `CD1` / `LEAK`），只在两边都有时用于排序
   */
  data class Query(
    val displayCode: String?,
    val title: String?,
    val codes: Set<CodeToken>,
    val crcKeys: Set<String>,
    val titleTokens: List<String>,
    val episodeNumber: Int?,
    val season: Int?,
    val coreStem: String,
    val versionFlags: Set<String> = emptySet(),
  ) {
    /** 番号的紧凑写法（`SSIS123`），只用于调试与文案以外的兼容读取。 */
    val codeKeys: Set<String>
      get() = codes.mapTo(LinkedHashSet(codes.size)) { it.compact }

    /** 一个可用条件都没有 ⇒ 别扫了，扫了也只能靠猜。 */
    val isEmpty: Boolean
      get() = codes.isEmpty() && crcKeys.isEmpty() && titleTokens.isEmpty()

    /**
     * 文案里「我在找什么」的那句话：优先番号，其次片名。
     *
     * 只用于 UI，不参与匹配 —— 所以允许为 null（两个都认不出来时就不该给「未找到」提示）。
     */
    val label: String?
      get() = displayCode ?: title?.takeIf { it.isNotBlank() }

    /**
     * 标题词的词边界正则，懒构造。
     *
     * 放在这里而不是每次 `match` 里现编：字幕目录一次要过几百上千个文件，
     * 每个文件编译一遍正则会白烧几十毫秒。
     */
    internal val tokenRegexes: List<Regex> by lazy {
      titleTokens.map { token ->
        Regex("(?<![0-9a-z])" + Regex.escape(token) + "(?![0-9a-z])")
      }
    }
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

  /** 这些数字不是番号，是分辨率 / 帧率。 */
  private val RESOLUTION_NUMBERS = setOf("480", "576", "720", "1080", "2160", "4320", "60", "50")

  /**
   * 番号的候选写法。
   *
   * 分隔符收 `-` `_` `.` 与空格四种：`SSIS-123` / `SSIS_123` / `SSIS.123` / `SSIS 123`
   * 在真实文件名里都出现过。加点号是安全的 —— `Movie.2024` 这类会被年份 / 分辨率黑名单挡掉。
   *
   * 边界一律用 lookaround 而不用 `\b`：Java 的 `\b` 把中日文当作字母，
   * 于是 `SSIS-123中文字幕.srt` 里的番号根本提取不出来（`3` 与 `中` 之间没有词边界）。
   * 这类「番号和中文粘在一起」的文件名在字幕目录里很常见。
   */
  private val CODE_REGEXES = listOf(
    // FC2 系：FC2-PPV-1234567 / FC2PPV-1234567 / FC2-1234567
    Regex("""(?i)(?<![A-Za-z0-9])FC2[-_. ]?(?:PPV[-_. ]?)?\d{5,8}(?![A-Za-z0-9])"""),
    // HEYZO 系
    Regex("""(?i)(?<![A-Za-z0-9])HEYZO[-_. ]?\d{3,5}(?![A-Za-z0-9])"""),
    // 素人系：123456-789
    Regex("""(?<![A-Za-z0-9])\d{6}[-_.]\d{3}(?![A-Za-z0-9])"""),
    // 通贩系：SSIS-123 / IPX-001 / ABP-123
    Regex("""(?i)(?<![A-Za-z0-9])[A-Z]{2,5}[-_. ]\d{2,5}(?![A-Za-z0-9])"""),
    // 无分隔符的连写形态：SSIS00123。要求数字段 ≥ 3 位，
    // 否则 `AAC2`、`AVC1` 这类编码标签会源源不断地被当成番号。
    Regex("""(?i)(?<![A-Za-z0-9])[A-Z]{2,5}\d{3,5}(?![A-Za-z0-9])"""),
  )

  /** 独立出现的 8 位十六进制。目录名常常就是裸 CRC，没有方括号。 */
  private val BARE_HEX_REGEX = Regex("""(?i)(?<![0-9a-z])[0-9a-f]{8}(?![0-9a-z])""")

  private val ASCII_TOKEN_REGEX = Regex("""[A-Za-z][A-Za-z0-9']{2,}""")
  private val CJK_TOKEN_REGEX = Regex("""[\u4e00-\u9fff\u3040-\u30ff]{2,}""")

  /** 标题词上限：再多也不会更准，只会让「重合度」被长尾词稀释。 */
  private const val MAX_TITLE_TOKENS = 6

  /** 字干前缀差异的字符上限。再长就不是「多了个尾缀」而是另一份文件了。 */
  private const val MAX_STEM_TAIL = 16

  /** 长词门槛：单个词到这个长度就足以独立支撑一条推荐。 */
  private const val STRONG_TOKEN_LENGTH = 6

  /**
   * 从若干「媒体名」构造查询条件。
   *
   * 传多个是因为调用方手里可能同时有文件名和媒体标题，两者在 Emby 场景下不一定相同；
   * 番号 / CRC 取并集（多一个 key 只会更容易命中同一个片子），标题、集号与字干取第一个能解析出来的。
   */
  fun buildQuery(vararg rawNames: String): Query {
    val names = rawNames.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) {
      return Query(
        displayCode = null,
        title = null,
        codes = emptySet(),
        crcKeys = emptySet(),
        titleTokens = emptyList(),
        episodeNumber = null,
        season = null,
        coreStem = "",
      )
    }

    val codes = LinkedHashSet<CodeToken>()
    val crcKeys = LinkedHashSet<String>()
    var displayCode: String? = null
    var coreStem = ""

    names.forEachIndexed { index, raw ->
      val stem = toHalfWidth(stripExtension(raw))
      for (regex in CODE_REGEXES) {
        for (match in regex.findAll(stem)) {
          val token = codeTokenOf(match.value) ?: continue
          if (codes.add(token) && displayCode == null) displayCode = match.value.uppercase()
        }
      }
      // 媒体名里方括号中的 8 位十六进制是发布组算的 CRC，是最强证据之一
      crcKeys += hexesOf(stem)
      // 与 scanLocalSubtitles 建目录时同一口径：媒体名的 CRC32
      runCatching { crcKeys.add(ChecksumUtils.getCRC32(raw).uppercase()) }
      if (index == 0) coreStem = normalize(coreStemOf(stem))
    }

    val primary = MediaInfoParser.parse(names.first())
    val titleTokens = titleTokensOf(primary.title).take(MAX_TITLE_TOKENS)

    return Query(
      displayCode = displayCode,
      title = primary.title.takeIf { it.isNotBlank() },
      codes = codes,
      crcKeys = crcKeys.filter { it.length == 8 }.toSet(),
      titleTokens = titleTokens,
      episodeNumber = primary.episode,
      season = primary.season,
      coreStem = coreStem,
      versionFlags = versionFlagsOf(stripExtension(names.first())),
    )
  }

  /**
   * 给一条字幕（相对路径，含所在目录）判分；够不上门槛返回 null。
   *
   * 比对对象是**相对路径**而不是纯文件名：用户常把字幕放在以番号命名的子目录里，
   * 只有文件名未必带番号（`zh.srt`），路径里却有。
   */
  fun match(query: Query, candidatePath: String): Hit? {
    if (candidatePath.isBlank()) return null
    if (query.isEmpty && query.coreStem.isEmpty()) return null

    val facts = analyze(candidatePath)
    if (facts.isEmpty) return null

    // ── 否决 ──────────────────────────────────────────────────────────────────
    // 候选自带番号却与查询毫无交集：这是「另一部片子」，分数再高也不能出。
    // 这条规则挡掉的正是旧版把 `SSIS-124` 当成 `SSIS-123` 的那类误判。
    if (facts.codes.isNotEmpty() && query.codes.isNotEmpty() &&
      facts.codes.none { it in query.codes }
    ) {
      return null
    }

    val bonus =
      facts.languageBonus +
        facts.depthBonus +
        facts.dirCodeBonus +
        flagBonus(query.versionFlags, facts.flags)

    // ── 1/2. 字干 ──────────────────────────────────────────────────────────────
    val stem = stemScore(query.coreStem, facts.coreStem)
    if (stem > 0) return Hit(stem + bonus, Reason.Stem)

    // ── 3. 番号 ────────────────────────────────────────────────────────────────
    if (facts.codes.any { it in query.codes }) return Hit(92 + bonus, Reason.Code)

    // ── 4. CRC ─────────────────────────────────────────────────────────────────
    if (facts.hexes.any { it in query.crcKeys }) return Hit(88 + bonus, Reason.Crc)

    // ── 5. 标题 + 集号 ─────────────────────────────────────────────────────────
    val title = titleScore(query, candidatePath)
    val titleOk =
      (title.ratio >= 0.6 && title.hits >= 2) || (title.strong && title.ratio >= 0.5)
    if (!titleOk) return null

    // 标题对得上、集号却对不上 ⇒ 同一系列的另一集（`S01E05` 的字幕不能挂到 E06）。
    // 只在标题已经过关时才用这条否决，因为「标题都不重合」的情况上面已经返回了。
    val episodeKnown =
      query.episodeNumber != null && facts.episode != null && facts.episode <= 200
    if (episodeKnown && facts.episode != query.episodeNumber) return null

    return if (episodeKnown) {
      Hit(74 + bonus, Reason.Episode)
    } else {
      Hit(62 + bonus, Reason.Title)
    }
  }

  // ── 内部工具 ────────────────────────────────────────────────────────────────

  /** 一条候选路径上能取到的全部证据。 */
  private class Facts(
    val codes: Set<CodeToken>,
    val hexes: Set<String>,
    val flags: Set<String>,
    val episode: Int?,
    val coreStem: String,
    val languageBonus: Int,
    val depthBonus: Int,
    val dirCodeBonus: Int,
  ) {
    val isEmpty: Boolean
      get() = codes.isEmpty() && hexes.isEmpty() && coreStem.isEmpty()
  }

  private fun analyze(rawPath: String): Facts {
    // 全角转半角：中文输入法 / 某些站点导出的文件名会带全角字母数字与全角连字符
    val path = toHalfWidth(rawPath)
    val segments = path.split('/', '\\').filter { it.isNotEmpty() }
    val fileName = segments.lastOrNull().orEmpty()
    val dirs = segments.dropLast(1)

    val codes = LinkedHashSet<CodeToken>()
    for (regex in CODE_REGEXES) {
      for (match in regex.findAll(path)) {
        codeTokenOf(match.value)?.let(codes::add)
      }
    }

    // 番号出现在目录名里（`SSIS-123/简体.srt`）= 用户手工按番号归档过，比文件名里偶然带一句更可信
    val dirCompact = if (dirs.isEmpty()) "" else compactOf(dirs.joinToString("/"))
    val dirCodeBonus =
      if (codes.isNotEmpty() && dirCompact.isNotEmpty() && codes.any { dirCompact.contains(it.compact) }) 4 else 0

    return Facts(
      codes = codes,
      hexes = hexesOf(path),
      flags = versionFlagsOf(path),
      episode = runCatching { MediaInfoParser.parse(stripExtension(fileName)).episode }.getOrNull(),
      coreStem = normalize(coreStemOf(stripExtension(fileName))),
      languageBonus = languageBonusOf(path),
      depthBonus = (2 - dirs.size.coerceAtMost(2)) * 2,
      dirCodeBonus = dirCodeBonus,
    )
  }

  /**
   * 版本标记的重合度，最高 +6。
   *
   * 为什么需要它：同一个番号常有多个版本（无码 `UC`、`4K` 重制、`CD1`/`CD2` 分段、`LEAK` 泄露版），
   * 它们的**时间轴并不一样**。番号只能证明「同一部」，证明不了「同一个剪辑」；
   * 文件名里能拿到的、真正区分版本的信息就剩这些标记。
   *
   * 只在**两边都有**标记时才算（缺一边就无从比较，给 0 而不是扣分）。
   */
  private fun flagBonus(queryFlags: Set<String>, candidateFlags: Set<String>): Int {
    if (queryFlags.isEmpty() || candidateFlags.isEmpty()) return 0
    val union = (queryFlags + candidateFlags).size
    val inter = queryFlags.count { it in candidateFlags }
    return (inter.toDouble() / union * 6).toInt()
  }

  /**
   * 字干相似度。
   *
   * - 完全相等 ⇒ 100
   * - 一方是另一方前缀、差异片段**不含数字**、长度差 ≤ [MAX_STEM_TAIL] ⇒ 88
   *
   * 「差异不含数字」是这里的关键：`ssis123` 与 `ssis1234` 只差一个数字，
   * 一旦放行就会把两部片子认成同一部。
   */
  private fun stemScore(queryStem: String, candidateStem: String): Int {
    if (queryStem.isEmpty() || candidateStem.isEmpty()) return 0
    if (queryStem == candidateStem) return 100
    val longer = if (queryStem.length >= candidateStem.length) queryStem else candidateStem
    val shorter = if (queryStem.length >= candidateStem.length) candidateStem else queryStem
    if (!longer.startsWith(shorter)) return 0
    val tail = longer.substring(shorter.length)
    if (tail.length > MAX_STEM_TAIL) return 0
    if (tail.any { it.isDigit() }) return 0
    return 88
  }

  private class TitleScore(val ratio: Double, val hits: Int, val strong: Boolean)

  /**
   * 标题词命中情况。
   *
   * 只认**词边界**命中：`car` 不再命中 `caribbean`。
   * 中日文没有词边界概念，退化为整段包含（但要求 ≥2 字符，见 [CJK_TOKEN_REGEX]）。
   */
  private fun titleScore(query: Query, path: String): TitleScore {
    if (query.titleTokens.isEmpty()) return TitleScore(0.0, 0, false)
    val lowered = path.lowercase()
    var hits = 0
    var strong = false
    query.tokenRegexes.forEachIndexed { index, regex ->
      if (regex.containsMatchIn(lowered)) {
        hits++
        if (query.titleTokens[index].length >= STRONG_TOKEN_LENGTH) strong = true
      }
    }
    return TitleScore(hits.toDouble() / query.titleTokens.size, hits, strong)
  }

  /** 语言偏好：中文优先，其次日文，英文垫底，没有标签给中间值。 */
  private fun languageBonusOf(path: String): Int {
    val lowered = path.lowercase()
    return when {
      ZH_HANS_REGEX.containsMatchIn(lowered) -> 12
      ZH_HANT_REGEX.containsMatchIn(lowered) -> 10
      DUAL_REGEX.containsMatchIn(lowered) -> 10
      JA_REGEX.containsMatchIn(lowered) -> 5
      EN_REGEX.containsMatchIn(lowered) -> 3
      else -> 6
    }
  }

  private val ZH_HANS_REGEX =
    Regex("""(?<![a-z])(chs|sc|zh|zho|chi|chn|hans|cn)(?![a-z])|简|简体|简中|简体中文""")
  private val ZH_HANT_REGEX =
    Regex("""(?<![a-z])(cht|tc|big5|hant|tw|hk)(?![a-z])|繁|繁体|繁中|繁體""")
  private val DUAL_REGEX = Regex("""双语|雙語|chs&eng|chs_eng|zh-en|中日|简日|簡日""")
  private val JA_REGEX = Regex("""(?<![a-z])(ja|jpn|jp)(?![a-z])|日文|日语|日本語""")
  private val EN_REGEX = Regex("""(?<![a-z])(en|eng)(?![a-z])|英文|英语""")

  /** 番号归一化：`SSIS-123` → (SSIS, 123)；前缀命中黑名单 / 数字是分辨率时判为不是番号。 */
  private fun codeTokenOf(raw: String): CodeToken? {
    val letters = raw.takeWhile { it.isLetter() }.uppercase()
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 2) return null
    if (digits in RESOLUTION_NUMBERS) return null
    if (digits.toIntOrNull()?.let { it in 1900..2099 } == true) return null // 年份不是番号
    // 纯数字番号（素人系 123456-789）：没有字母前缀可查黑名单，只要求数字够长，避免把集号当番号
    if (letters.isEmpty()) {
      return if (digits.length in 8..9) CodeToken("", digits.trimStart('0').ifEmpty { "0" }) else null
    }
    if (letters.length < 2) return null
    if (letters in CODE_PREFIX_BLACKLIST) return null
    // 去掉前导零再比：`SSIS-001` / `SSIS-1` / `SSIS00001` 是同一条
    return CodeToken(letters, digits.trimStart('0').ifEmpty { "0" })
  }

  /** 独立出现的 8 位十六进制。只与已知 CRC 求交集，不必额外过滤纯字母串。 */
  private fun hexesOf(text: String): Set<String> =
    BARE_HEX_REGEX.findAll(text).map { it.value.uppercase() }.toSet()

  /**
   * 会改变时间轴的版本标记。
   *
   * 只收「同一部片子的不同剪辑/分段」这一类：无码、泄露、重制、分段。
   * 分辨率与来源（`1080p`/`WEB`）**不收**：字幕与它们无关，收进来只会稀释重合度。
   */
  private val VERSION_FLAG_REGEX =
    Regex("""(?i)(?<![a-z0-9])(uncensored|uc|leak|leaked|remux|4k|2160p|cd[12]|part[12])(?![a-z0-9])""")

  private fun versionFlagsOf(text: String): Set<String> =
    VERSION_FLAG_REGEX.findAll(text).map { it.value.lowercase() }.toSet()

  /**
   * 全角转半角。
   *
   * `ＳＳＩＳ－１２３` 这种全角写法在从网页 / 中文输入法里粘出来的文件名上会偶发出现，
   * 不归一的话番号正则一个都匹配不上。逐字符 1:1 映射，不会改变任何偏移。
   */
  private fun toHalfWidth(text: String): String {
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

  private val FULL_WIDTH_RANGE = 0xFF01..0xFF5E
  private const val FULL_WIDTH_OFFSET = 0xFEE0
  private const val IDEOGRAPHIC_SPACE = 0x3000

  /** 大写 + 只留字母数字（保留中日文，`isLetter()` 对它们为真）。 */
  private fun compactOf(text: String): String =    buildString(text.length) {
      text.forEach { ch ->
        if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
      }
    }

  private fun normalize(text: String): String = compactOf(text).lowercase()

  private fun stripExtension(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    return if (dot > 0) base.substring(0, dot) else base
  }

  /** 去掉末尾的语言 / 字幕标签，反复剥到剥不动为止。 */
  private fun coreStemOf(stem: String): String {
    var text = stem
    var guard = 0
    while (guard++ < 8) {
      val match = TRAILING_TAG_REGEX.find(text) ?: break
      val tag = match.groupValues[1].lowercase()
      if (tag !in LANGUAGE_TAGS) break
      text = text.substring(0, match.range.first)
    }
    return text
  }

  private val TRAILING_TAG_REGEX =
    Regex("""[.\-_\s(\[]+([A-Za-z]{2,12}|[\u4e00-\u9fff]{1,6})\s*[)\]]*$""")

  /**
   * 可以放心从字干末尾剥掉的标签。
   *
   * 只收语言 / 字幕类型：剥得越多，字干越接近媒体名；
   * 但不收发布组、编码、分辨率 —— 那些在媒体名里也存在，两边都留着才可比。
   */
  private val LANGUAGE_TAGS = setOf(
    "chs", "cht", "zh", "zho", "chi", "chn", "cn", "tw", "hk", "sc", "tc", "gb", "big5",
    "hans", "hant", "chinese", "simplified", "traditional",
    "ja", "jpn", "jp", "japanese", "en", "eng", "english",
    "ko", "kor", "korean", "ru", "rus", "th", "vie",
    "forced", "sdh", "cc", "default", "merged", "auto", "translated", "machine",
    "简", "繁", "中", "英", "日", "韩", "双语", "雙語", "中文", "简体", "繁体", "繁體",
    "字幕", "中字", "外挂",
  )

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
