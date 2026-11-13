package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 一个文件名的全部可用信息，预处理一次、反复使用。
 *
 * 这是整条管线的中间产物，也是 [SubtitleCache] 的缓存单元。
 * 字段分两类：**原始保留**（[original] / [normalized]）与**派生**（其余），
 * 派生字段随时可以由原始串重算，所以缓存失效时不需要迁移数据。
 *
 * @property original 原始文件名
 * @property normalized 字符标准化后的名字（大写、分隔符已统一）
 * @property cleaned 去掉站点前缀 / 发布标记 / 语言标签之后的名字
 * @property cleanedTitle 去掉番号片段之后的标题部分，用于标题相似度
 * @property tokens 标题词（拼音化前的原词，已过滤停用词）
 * @property identifiers 全部编号候选，按置信度降序
 * @property codeKeys 编号的可比较形式
 * @property primaryConfidence 主编号（第一个）的提取置信度；无编号时为 0
 * @property stem 字干：剥站点前缀与语言标签后紧凑化成小写串
 * @property crc 独立出现的 8 位十六进制（发布组 CRC32）
 * @property versionFlags 会改变时间轴的版本标记（`UC` / `4K` / `CD1`）
 * @property year 解析出的年份（1900..2099），没有则 null
 * @property episodeKey 归一化的集号（`S01E02` / `EP16` / `第16集`），没有则 null
 * @property ambiguous 是否提取到多个互相矛盾的编号
 */
data class TitleFacts(
  val original: String,
  val normalized: String,
  val cleaned: String,
  val cleanedTitle: String,
  val tokens: List<String>,
  val identifiers: List<Identifier>,
  val codeKeys: List<CodeKey>,
  val primaryConfidence: Double,
  val stem: String,
  val crc: Set<String>,
  val versionFlags: Set<String>,
  val year: Int?,
  val episodeKey: String?,
  val ambiguous: Boolean,
) {
  val hasCode: Boolean
    get() = codeKeys.isNotEmpty()

  /** 主编号（置信度最高的那个）。 */
  val primaryCode: CodeKey?
    get() = codeKeys.firstOrNull()

  /** 标题里是否有中日文。有则词级特征不可靠（没有词边界），见 [SmartSubtitleMatcher]。 */
  val hasCjkTitle: Boolean
    get() = cleanedTitle.any { it.code > 0x2E80 }

  companion object {
    val EMPTY =
      TitleFacts(
        original = "",
        normalized = "",
        cleaned = "",
        cleanedTitle = "",
        tokens = emptyList(),
        identifiers = emptyList(),
        codeKeys = emptyList(),
        primaryConfidence = 0.0,
        stem = "",
        crc = emptySet(),
        versionFlags = emptySet(),
        year = null,
        episodeKey = null,
        ambiguous = false,
      )

    /** 全角数字 / 字母混排的 `５６：５７` 这类时间文字不是标题词。 */
    private val ASCII_TOKEN = Regex("[A-Z][A-Z0-9']{2,}")
    private val CJK_TOKEN = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff]{2,}")
    private val CRC_TOKEN = Regex("(?<![0-9A-Z])[0-9A-F]{8}(?![0-9A-Z])")
    private val VERSION_FLAG =
      Regex("(?<![A-Z0-9])(UNCENSORED|UC|LEAK|LEAKED|REMUX|4K|2160P|CD[12]|PART[12])(?![A-Z0-9])")
    private val YEAR_TOKEN = Regex("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])")

    /**
     * 集号。三种常见写法各自归一成一个键：
     * `S05E16` / `EP16` / `E16` / `第16集`。
     *
     * 为什么单独抽出来：`Breaking.Bad.S05E16` 与 `...S05E15` 的标题相似度高达 0.95，
     * 靠文本特征永远分不开，而它们**必须是两条不同的记录**。这是「相邻番号」规则
     * 在剧集上的等价物。
     */
    private val EPISODE_SXXEXX = Regex("(?<![A-Z0-9])S(\\d{1,2})[-]?E(\\d{1,3})(?![0-9])")
    private val EPISODE_EXX = Regex("(?<![A-Z0-9])E(?:P)?[-]?(\\d{1,3})(?![0-9])")
    private val EPISODE_CJK = Regex("第\\s*(\\d{1,4})\\s*[集话話]")

    /** 标题里不参与匹配的词。这些词在字幕目录里几乎每条都有，留着只会稀释重合度。 */
    private val STOP_WORDS =
      setOf(
        "THE", "AND", "FOR", "WITH", "FROM", "THAT", "THIS", "YOU", "YOUR",
        "MOVIE", "FILM", "VIDEO", "SAMPLE", "TRAILER",
        "SUB", "SUBS", "SUBTITLE", "SUBTITLES",
        "WWW", "HTTP", "HTTPS", "COM", "NET",
      )

    /** 标题词上限。再多也不会更准，只会让重合度被长尾词稀释。 */
    private const val MAX_TOKENS = 8

    /**
     * 从任意视频 / 字幕名构造。
     *
     * 传进来的可以带扩展名，也可以只是标题（Emby 的 `itemName`）。
     */
    fun of(raw: String): TitleFacts {
      if (raw.isBlank()) return EMPTY
      val normalized = FilenameNormalizer.normalize(raw)
      val cleaned = NoiseCleaner.clean(normalized.normalized)
      val normalizedIds = IdentifierExtractor.extract(cleaned)

      // 同一字母段下，数字更长的那个才是完整编号：`259LUXU-1234` 会同时命中
      // DIGIT_BRAND（LUXU + 2591234）与 VENDOR（LUXU + 1234），必须合并成前者。
      val merged = mergeContained(normalizedIds)
      val codeKeys = merged.map { CodeKey.of(it) }

      val year = YEAR_TOKEN.findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.firstOrNull()
      val titleSource = stripYear(stripIdentifiers(cleaned, merged), year)
      val tokens = tokenize(titleSource)

      return TitleFacts(
        original = raw,
        normalized = normalized.normalized,
        cleaned = cleaned,
        cleanedTitle = titleSource,
        tokens = tokens,
        identifiers = merged,
        codeKeys = codeKeys,
        primaryConfidence = merged.firstOrNull()?.confidence ?: 0.0,
        stem = normalized.stem,
        crc = CRC_TOKEN.findAll(normalized.normalized).map { it.value }.toSet(),
        versionFlags = VERSION_FLAG.findAll(normalized.normalized).map { it.value }.toSet(),
        year = year,
        episodeKey = episodeOf(normalized.normalized),
        ambiguous = isAmbiguous(merged),
      )
    }

    /** 数字段互为后缀的一对里，只留更长的那个（见 [of] 里的说明）。 */
    private fun mergeContained(ids: List<Identifier>): List<Identifier> {
      if (ids.size <= 1) return ids
      return ids.filter { candidate ->
        ids.none { other ->
          other !== candidate &&
            other.letters == candidate.letters &&
            other.letters.isNotEmpty() &&
            other.numericCore.length > candidate.numericCore.length &&
            other.numericCore.endsWith(candidate.numericCore)
        }
      }
    }

    /**
     * 查询侧编号是否自相矛盾。
     *
     * 只有「两个都够可信、且数字主体互不包含」才算：`SSIS-123` 与 `SSIS-1234`
     * 同时出现说明这条文件名里塞了两部片子的信息，此时不该自动挂任何字幕。
     */
    private fun isAmbiguous(ids: List<Identifier>): Boolean {
      val strong = ids.filter { it.confidence >= 0.8 }
      if (strong.size < 2) return false
      val first = strong.first()
      return strong.drop(1).any { other ->
        other.body != first.body &&
          !other.numericCore.endsWith(first.numericCore) &&
          !first.numericCore.endsWith(other.numericCore)
      }
    }

    /** 从清洗后的名字里抠掉番号片段，剩下的就是标题。 */
    private fun stripIdentifiers(cleaned: String, ids: List<Identifier>): String {
      if (ids.isEmpty()) return cleaned
      var text = cleaned
      ids.forEach { id ->
        if (id.confidence >= 0.5) {
          text = text.replace(id.raw, "-")
        }
      }
      return text.trim('-', ' ')
    }

    /**
     * 把年份从标题里摘掉。
     *
     * 年份是**作品的发行年**，两侧写法本来就常常不一致（视频名几乎一定带，字幕名几乎一定不带）。
     * 留在标题里会让 `THE DARK KNIGHT` 与 `THE DARK KNIGHT 2008` 的字符 N-gram 从 1.0 掉到 0.75，
     * 白扣一条本来完全正确的候选。它该去的地方是 [TitleFacts.year]，那里会做「同年加分」。
     */
    private fun stripYear(title: String, year: Int?): String {
      if (year == null || title.isEmpty()) return title
      val key = year.toString()
      return title.split('-', ' ').filterNot { it == key }.joinToString("-").trim('-', ' ')
    }

    /** 集号归一化。识别不出来就返回 null（「没有集号」不是「集号不一致」）。 */
    private fun episodeOf(normalized: String): String? {
      EPISODE_SXXEXX.find(normalized)?.let { m ->
        return "S%02dE%03d".format(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toIntOrNull() ?: 0)
      }
      EPISODE_CJK.find(normalized)?.let { m ->
        return "第%03d集".format(m.groupValues[1].toIntOrNull() ?: 0)
      }
      EPISODE_EXX.find(normalized)?.let { m ->
        return "EP%03d".format(m.groupValues[1].toIntOrNull() ?: 0)
      }
      return null
    }

    private fun tokenize(title: String): List<String> {
      if (title.isBlank()) return emptyList()
      val ascii =
        ASCII_TOKEN.findAll(title).map { it.value }.filterNot { it in STOP_WORDS }.toList()
      val cjk = CJK_TOKEN.findAll(title).map { it.value }.toList()
      return (ascii + cjk).distinct().take(MAX_TOKENS)
    }
  }
}
