package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 无关信息清理（对应设计里的 NoiseCleaner）。
 *
 * ## 清理的边界
 *
 * 只删「确信不是作品标识」的片段：域名、发布组标记、分辨率、编码、语言、容器。
 * 厂牌词（`carib`、`1pondo`、`tokyohot`）**不删** —— 它们是作品来源的一部分，
 * 出现在字幕名里时是有效证据。
 *
 * ## 为什么不用「删掉 @ 之前的全部」
 *
 * 真实样本里 `www.98T.la@n0907_iris2` 的 `@` 前面确实是域名，但 `b.net@N0907` 只有一段，
 * `[psk.la]050115_071` 干脆没有 `@`。所以判断依据是「看起来像域名」的正则
 * （至少两段、**最后一段是纯字母**），而不是分隔符位置。
 * 认不出来的内容一律保留，交给后续特征去投票。
 *
 * ## 输入形态
 *
 * 拿到的是 [FilenameNormalizer] 的产物：已大写、`. _ 空格` 都归一成了 `-`。
 * 所以域名正则用 `[-.]` 作分隔，`www.98t.la@` 这时写作 `WWW-98T-LA@`。
 */
object NoiseCleaner {
  /** 域名主体：多段 + 最后一段全字母（`98T-LA`、`HHD800-COM`、`B-NET` 都成立）。 */
  private const val HOST_BODY = "[A-Z0-9][A-Z0-9-]*(?:[-][A-Z0-9][A-Z0-9-]*)*[-][A-Z]+"

  /** `www.98t.la@`、`https://x.tv@`、`hhd800.com#`、`skp2p.com@` */
  private val LEADING_SITE = Regex("^(?:HTTPS?://)?(?:$HOST_BODY)[@#]")

  /** `[psk.la]050115_071`、`[hhd800.com]ABC-123` */
  private val LEADING_SITE_BRACKET = Regex("^\\[(?:$HOST_BODY)][-]?")

  /**
   * 结尾的 `-skp2p.com` / `-kan224.com`。
   *
   * TLD 刻意不收 `LA` / `IN` / `TO` / `WS`：这些两字母段在番号里太常见
   * （`XXX-LA` 这种写法会被误当成域名整段删掉）。
   */
  private val TRAILING_SITE =
    Regex("[-](?:[A-Z0-9]+[-])+(?:COM|NET|ORG|TV|CC|XYZ|TOP|CLUB|VIP|ME|INFO|APP|LINK|SITE|PW|BIZ|IO)(?![A-Z0-9])")

  /**
   * 发布 / 站点标记词：只说明「谁压的」，不说明「哪一部」。
   *
   * 只收**纯字母**与**站点专属的固定串**。带数字的分段号（`_1`、`_2`、`CHF3`、`PROB4`）
   * 一律不动 —— 删了会把两个分段认成同一条，这正是「gachig140_1 / _2」的分界。
   */
  private val RELEASE_TAGS =
    setOf(
      "IRIS", "XPLANET", "3XPLANET", "WHISPERJAV", "WHISPER", "MERGED", "PASS",
      "SKP2P", "RESTORED", "REPACK", "PROPER", "UNCENSORED",
      "WHOLE", "HIGH", "LOW", "FULL", "COMPLETE", "NEW",
    )

  /** 分辨率。 */
  private val RESOLUTION_TAGS =
    setOf("4K", "8K", "2160P", "1440P", "1080P", "1080I", "720P", "576P", "480P", "SD", "HD", "FHD", "UHD", "QHD")

  /** 视频 / 音频编码。 */
  private val CODEC_TAGS =
    setOf(
      "X264", "X265", "H264", "H265", "HEVC", "AVC", "AV1", "VP9", "VP8", "MPEG2", "MPEG4",
      "AAC", "AC3", "EAC3", "DTS", "DDP", "DD", "FLAC", "TRUEHD", "ATMOS", "OPUS", "LPCM", "MP3",
    )

  /** 来源 / 画质。 */
  private val SOURCE_TAGS =
    setOf(
      "WEBDL", "WEBRIP", "WEB", "BLURAY", "BDRIP", "BDREMUX", "REMUX", "BD", "HDTV",
      "DVDRIP", "DVD", "HDRIP", "BRRIP", "HDR", "SDR", "DV", "SAMPLE", "TRAILER",
    )

  /** 语言 / 字幕轨标签。 */
  private val LANGUAGE_TAGS =
    setOf(
      "CHS", "CHT", "ZH", "ZHO", "CHI", "CHN", "CN", "TW", "HK", "SC", "TC", "GB", "BIG5",
      "HANS", "HANT", "CHINESE", "SIMPLIFIED", "TRADITIONAL",
      "JA", "JPN", "JP", "JAPANESE", "EN", "ENG", "ENGLISH",
      "KO", "KOR", "KOREAN", "RU", "RUS", "TH", "VIE",
      "FORCED", "SDH", "CC", "DEFAULT", "MULTI", "DUAL", "SUB", "SUBS", "SUBTITLE", "SUBTITLES",
      "简", "繁", "中", "英", "日", "韩", "双语", "雙語", "中文", "简体", "繁体", "繁體",
      "字幕", "中字", "外挂", "外掛",
    )

  /** 全部「可安全删除」的标记词。 */
  private val DROP_TAGS = RELEASE_TAGS + RESOLUTION_TAGS + CODEC_TAGS + SOURCE_TAGS + LANGUAGE_TAGS

  /** 形如 `CHF3` / `PROB4` / `HD1` / `PASS2` 的「短标记 + 1 位数字」：这一类只是组名 / 通行号，不是分段号。 */
  private val SHORT_TAG_WITH_DIGIT = Regex("^(?:CHF|PROB|SKP|KAN|IRIS|PASS)\\d{1,2}$")

  /** `SHIFT-108S` / `SHIFT108S` 这类时间轴偏移标记。 */
  private val SHIFT_TAG = Regex("^(?:SHIFT|OFFSET)-?\\d+S$")

  /**
   * 清洗。返回清洗后的名字（仍是「大写 + 连字符分隔」形态）。
   *
   * 不会删干净任何数字串 —— 分辨率与年份由 [IdentifierExtractor] 的候选过滤负责，
   * 这里删掉就无法再判断「这是不是番号」。
   */
  fun clean(normalizedName: String): String {
    if (normalizedName.isBlank()) return ""
    var text = normalizedName

    text = LEADING_SITE.replace(text, "")
    text = LEADING_SITE_BRACKET.replace(text, "")
    text = TRAILING_SITE.replace(text, "")

    val kept = text.split('-', ' ').filterNot { it.isEmpty() || isDroppable(it) }
    text = kept.joinToString("-").trim('-')

    // 只剩分隔符 / 空串时退回原名：清洗过度比不洗更危险
    return if (FilenameNormalizer.compact(text).isEmpty()) normalizedName else text
  }

  /**
   * 这一段是否应该整个删掉。
   *
   * 含中日文的段一律保留（可能是作品标题），只删纯 ASCII 的标记词。
   */
  private fun isDroppable(segment: String): Boolean {
    val token = segment.trim()
    if (token.isEmpty()) return true
    if (token.any { it.code > 0x2E80 }) return false
    return isNoiseToken(token)
  }

  private fun isNoiseToken(token: String): Boolean {
    val upper = token.uppercase()
    if (upper in DROP_TAGS) return true
    if (SHORT_TAG_WITH_DIGIT.matches(upper)) return true
    if (SHIFT_TAG.matches(upper)) return true
    return false
  }

  /**
   * 字干：剥掉站点前缀 + 尾部语言 / 发布标记后紧凑化成小写串。
   *
   * 只用于「两个名字是不是本来就是同一个文件」这一件事（[TitleFacts.stem]，
   * 以及绑定键）。它比 [clean] 更狠一点：允许从尾部连续剥多层标签
   * （`xxx-JA-MERGED-WHISPERJAV-CHINESE` 一路剥到 `xxx`）。
   */
  fun stemOf(normalizedName: String): String {
    var text = clean(normalizedName)
    var guard = 0
    while (guard++ < 8) {
      val segments = text.split('-')
      if (segments.size <= 1) break
      val last = segments.last().trim()
      if (last.isEmpty() || !isNoiseToken(last)) break
      text = segments.dropLast(1).joinToString("-")
    }
    return FilenameNormalizer.compact(text).lowercase()
  }
}
