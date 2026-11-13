package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 一个媒体标题的匹配查询。
 *
 * @property facts 解析结果
 * @property aliases 用户确认的别名表
 */
data class MediaQuery(
  val facts: TitleFacts,
  val aliases: AliasManager,
) {
  /** 能拿出的全部证据都没有 ⇒ 别扫了，扫了也只能靠猜。 */
  val isEmpty: Boolean
    get() = !facts.hasCode && facts.tokens.isEmpty() && facts.stem.isEmpty()

  /** 文案里「我在找什么」：优先主编号原文，其次标题。 */
  val label: String?
    get() = facts.identifiers.firstOrNull()?.raw ?: facts.cleanedTitle.takeIf { it.isNotBlank() }

  /** 字干，用于「同一文件」判定与绑定键。 */
  val stem: String
    get() = facts.stem
}

/**
 * 智能本地字幕匹配的门面（对应设计里的总流程）。
 *
 * ## 一句话原则
 *
 * 从复杂文件名里提取**真正的作品标识符**，再用多种证据交叉验证「是不是同一部」，
 * 而不是比较两个文件名像不像。准确率优先，宁可不匹配。
 *
 * ## 流程
 *
 * 1. [FilenameNormalizer] 字符标准化（半角、大写、分隔符统一、剥扩展名）
 * 2. [NoiseCleaner] 剥站点前缀与发布 / 语言 / 画质标记
 * 3. [IdentifierExtractor] 抽全部编号候选（三段结构 + 置信度）
 * 4. [IdentifierNormalizer] 判两个编号的关系等级（EXACT / 变体 / 弱 / 冲突）
 * 5. [SimilarityCalculator] 算标题、词、N-gram 相似度
 * 6. [MatchScorer] 加权归一化成 0..100
 * 7. [ConflictDetector] 给冲突标记与分数上限（决定「能不能信」）
 * 8. [MatchDecisionEngine] 套阈值、算 Top1/Top2 分差、判能否自动匹配
 *
 * ## 为什么「上限」与「分数」分开
 *
 * 若只用一个分数表达一切，那么「编号完全一致但字幕是另一集」与「编号只对上尾号
 * 但标题全中」会被压成同一个数值，用户看到两个 82 分无从选择。
 * 分开之后：分数负责排序，冲突标记负责解释，上限负责「不许自动」。
 */
object SmartSubtitleMatcher {
  const val VERSION = 1

  /** 构造查询。[mediaTitle] 可以是文件名、完整路径，也可以只是标题。 */
  fun prepare(mediaTitle: String, aliases: AliasManager = AliasManager.EMPTY): MediaQuery =
    MediaQuery(TitleFacts.of(mediaTitle), aliases)

  /**
   * 对一组候选路径打分并决策。
   *
   * @param candidatePaths 字幕相对路径（含子目录）。只用最后一段做名字解析
   */
  fun match(
    query: MediaQuery,
    candidatePaths: List<String>,
    config: MatchConfig = MatchConfig(),
  ): List<SubtitleMatchResult> {
    if (query.isEmpty || candidatePaths.isEmpty()) return emptyList()
    val scored = rank(query, candidatePaths)
    return MatchDecisionEngine.decide(scored, query.facts, config)
  }

  /**
   * 只打分、不决策。返回按分数降序的中间结果（含被淘汰项），供日志与测试使用。
   */
  fun rank(query: MediaQuery, candidatePaths: List<String>): List<ScoredCandidate> {
    if (query.isEmpty) return emptyList()
    val out = ArrayList<ScoredCandidate>(candidatePaths.size)
    for (path in candidatePaths) {
      val candidate = SubtitleCache.factsOf(path)
      scoredOne(query, path, candidate)?.let { out += it }
    }
    return out.sortedWith(
      compareByDescending<ScoredCandidate> { it.score }
        .thenByDescending { it.identifierMatchType.similarity }
        .thenBy { it.path.length }
    )
  }

  private fun scoredOne(query: MediaQuery, path: String, candidate: TitleFacts): ScoredCandidate? {
    if (candidate.original.isBlank()) return null
    val q = query.facts

    // ── 番号 ────────────────────────────────────────────────────────────────
    val codeMatch =
      IdentifierNormalizer.bestMatch(q.codeKeys, candidate.codeKeys) { a, b ->
        query.aliases.isEquivalent(a.compact, b.compact)
      }

    val stemExact = q.stem.isNotEmpty() && q.stem == candidate.stem
    val stemPrefix = !stemExact && isStemPrefix(q.stem, candidate.stem)

    // ── 文本特征 ────────────────────────────────────────────────────────────
    // 标题**去掉全部分隔符**再比：`[Caribbeancom]910白雪美音…` 与
    // `[Caribbeancom] 910 白雪美音 …` 是同一个标题，只是中标点 / 空格写法不同。
    // 不压平的话，这一类会永远停在 85 分，够不到自动线，只能靠用户点一次。
    val titleA = FilenameNormalizer.compact(q.cleanedTitle)
    val titleB = FilenameNormalizer.compact(candidate.cleanedTitle)
    val title =
      when {
        stemExact -> 1.0
        titleA.isEmpty() || titleB.isEmpty() -> null
        else ->
          maxOf(
            SimilarityCalculator.jaroWinkler(titleA, titleB),
            SimilarityCalculator.levenshteinRatio(titleA, titleB) * 0.98,
          )
      }

    // 中日文没有词边界：`一本道カリビアンコム` 与 `一本道 カリビアンコム` 会被切成
    // 完全不同的词表（一侧一个长块、一侧三个短块），Jaccard 掉到 0.6 却没有任何信息量。
    // 这种标题的词特征一律视为「没有可比内容」，交给字符级的 title / ngram 去判。
    val token =
      if (q.tokens.isEmpty() || candidate.tokens.isEmpty() || q.hasCjkTitle || candidate.hasCjkTitle) {
        null
      } else {
        SimilarityCalculator.jaccard(q.tokens, candidate.tokens)
      }

    val ngram =
      if (q.cleanedTitle.isBlank() || candidate.cleanedTitle.isBlank()) {
        null
      } else {
        // 只用**标题部分**：编号由 code 特征单独计分，年份已经被摘到 TitleFacts.year。
        // 之前拿整串（含年份 / 站点余项）算，会让 `THE DARK KNIGHT` 与
        // `THE DARK KNIGHT 2008` 的 N-gram 从 1.0 掉到 0.75 —— 同一部片子，
        // 只因为视频名多了个年份就判成「文本不够像」。
        SimilarityCalculator.bestNgram(
          FilenameNormalizer.compact(q.cleanedTitle),
          FilenameNormalizer.compact(candidate.cleanedTitle),
        )
      }

    val features =
      FeatureScores(
        code = if (codeMatch == CodeMatch.NONE) null else codeMatch.similarity,
        title = title,
        token = token,
        ngram = ngram,
        meta = metaSimilarity(q, candidate),
      )
    val raw = MatchScorer.score(features)

    // 「文本几乎逐字相同」与「字干完全相同」是同一种证据的两个来源：
    // 都足以让编号缺失 / 置信度低不再成为否决理由（见 ConflictDetector 的说明）。
    val strongText = stemExact || MatchScorer.isStrongText(features)
    val episodeConflict =
      q.episodeKey != null && candidate.episodeKey != null && q.episodeKey != candidate.episodeKey

    val report =
      ConflictDetector.inspect(
        codeMatch = codeMatch,
        queryHasCode = q.hasCode,
        stemExact = stemExact,
        queryConfidence = q.primaryConfidence,
        ambiguousQuery = q.ambiguous,
        strongText = strongText,
        episodeConflict = episodeConflict,
      )
    if (report.fatal) return null

    val score = minOf(raw, report.scoreCap)
    return ScoredCandidate(
      path = path,
      name = path.substringAfterLast('/').substringAfterLast('\\'),
      score = score,
      rawScore = raw,
      identifierMatchType = codeMatch,
      features = features,
      conflictFlags = report.flags,
      reasons = reasonsOf(codeMatch, stemExact, stemPrefix, q, candidate, features),
      stemExact = stemExact,
      strongText = strongText,
    )
  }

  /**
   * 字干前缀：一方是另一方的前缀，且差异片段**不含数字**。
   *
   * 差异含数字就是另一个编号（`n0907` 与 `n09074`），这条闸门不能松。
   */
  private fun isStemPrefix(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    val longer = if (a.length >= b.length) a else b
    val shorter = if (a.length >= b.length) b else a
    if (!longer.startsWith(shorter)) return false
    val tail = longer.substring(shorter.length)
    if (tail.length > 24) return false
    return tail.none { it.isDigit() }
  }

  /**
   * 辅助信息：版本标记、CRC、年份。
   *
   * 缺一边就返回 null（「无从比较」不等于「不一致」），这样归一化时不会因为
   * 「字幕名没写 4K」而把一条正确候选压下去。
   */
  private fun metaSimilarity(q: TitleFacts, c: TitleFacts): Double? {
    val parts = ArrayList<Double>(3)
    if (q.versionFlags.isNotEmpty() && c.versionFlags.isNotEmpty()) {
      parts += SimilarityCalculator.jaccard(q.versionFlags, c.versionFlags)
    }
    if (q.crc.isNotEmpty() && c.crc.isNotEmpty()) {
      parts += if (q.crc.any { it in c.crc }) 1.0 else 0.0
    }
    if (q.year != null && c.year != null) {
      parts += if (q.year == c.year) 1.0 else 0.0
    }
    return if (parts.isEmpty()) null else parts.average()
  }

  /**
   * 匹配依据，按重要性排序。第一条会显示在候选行上。
   */
  private fun reasonsOf(
    codeMatch: CodeMatch,
    stemExact: Boolean,
    stemPrefix: Boolean,
    q: TitleFacts,
    c: TitleFacts,
    features: FeatureScores,
  ): List<MatchReason> {
    val reasons = ArrayList<MatchReason>(4)
    when (codeMatch) {
      CodeMatch.EXACT -> reasons += MatchReason.CODE_EXACT
      CodeMatch.FORMAT_VARIANT -> reasons += MatchReason.CODE_FORMAT_VARIANT
      CodeMatch.ALIAS -> reasons += MatchReason.CODE_ALIAS
      CodeMatch.ZERO_VARIANT -> reasons += MatchReason.CODE_ZERO_VARIANT
      CodeMatch.SUFFIX_VARIANT -> reasons += MatchReason.CODE_SUFFIX_VARIANT
      CodeMatch.PARTIAL -> reasons += MatchReason.CODE_PARTIAL
      else -> Unit
    }
    if (stemExact) reasons += MatchReason.STEM_EXACT
    if (stemPrefix) reasons += MatchReason.STEM_PREFIX
    if (q.crc.isNotEmpty() && c.crc.isNotEmpty() && q.crc.any { it in c.crc }) {
      reasons += MatchReason.CRC_SAME
    }
    if (features.token != null && features.token > 0.0) reasons += MatchReason.TITLE_TOKENS
    if (q.versionFlags.isNotEmpty() && q.versionFlags == c.versionFlags) {
      reasons += MatchReason.VERSION_FLAGS
    }
    if (reasons.isEmpty()) reasons += MatchReason.TEXT_ONLY
    return reasons.distinct()
  }
}
