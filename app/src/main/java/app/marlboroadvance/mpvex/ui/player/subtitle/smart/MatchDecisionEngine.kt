package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/** 置信度等级。只用于展示与「能不能自动挂」的判断，不参与排序（排序只看 score）。 */
enum class ConfidenceLevel {
  /** 可直接采用 */
  HIGH,

  /** 需要用户确认 */
  MEDIUM,

  /** 证据不足 */
  LOW,
}

/**
 * 匹配依据。UI 把它映射成一句人话，日志里直接打枚举名。
 *
 * 之所以用枚举而不是直接拼字符串：文案要进 `strings.xml`（有多语言），
 * 而日志要稳定可比。两边需求不一样，中间必须有一层结构化表示。
 */
enum class MatchReason {
  /** 字干完全一致（剥站点前缀 / 语言标签后相同） */
  STEM_EXACT,

  /** 字干前缀一致，差异部分不含数字 */
  STEM_PREFIX,

  /** 编号完全一致 */
  CODE_EXACT,

  /** 编号仅分隔符 / 大小写不同 */
  CODE_FORMAT_VARIANT,

  /** 命中用户确认的别名 */
  CODE_ALIAS,

  /** 前导零不同 */
  CODE_ZERO_VARIANT,

  /** 数字主体相同、字母后缀不同 */
  CODE_SUFFIX_VARIANT,

  /** 只对上数字片段 */
  CODE_PARTIAL,

  /** 发布组 CRC 一致 */
  CRC_SAME,

  /** 标题词命中共有词 */
  TITLE_TOKENS,

  /** 版本标记一致（同剪辑） */
  VERSION_FLAGS,

  /** 时间轴与片长吻合（仅作佐证） */
  TIMING_VERIFIED,

  /** 没有任何编号证据 */
  TEXT_ONLY,
}

/**
 * 一次匹配的结果。字段与设计里的输出结构一一对应，便于日志与 UI 共用。
 *
 * @property relativePath 字幕相对路径（候选的唯一标识）
 * @property name 文件名（展示用）
 * @property score 0..100，已应用冲突上限
 * @property identifierMatchType 编号关系等级
 * @property identifierSimilarity 编号特征得分 0..1
 * @property titleSimilarity 标题特征得分 0..1
 * @property tokenSimilarity 词 Jaccard
 * @property ngramSimilarity 字符 N-gram
 * @property metaSimilarity 辅助信息得分；无信息时为 null
 * @property conflictFlags 冲突标记
 * @property matchReasons 匹配依据（按重要性排序）
 * @property canAutoMatch 是否满足全部自动匹配的安全条件
 */
data class SubtitleMatchResult(
  val relativePath: String,
  val name: String,
  val score: Double,
  val identifierMatchType: CodeMatch,
  val identifierSimilarity: Double,
  val titleSimilarity: Double,
  val tokenSimilarity: Double,
  val ngramSimilarity: Double,
  val metaSimilarity: Double?,
  val conflictFlags: Set<ConflictFlag>,
  val confidenceLevel: ConfidenceLevel,
  val matchReasons: List<MatchReason>,
  val canAutoMatch: Boolean,
) {
  /** 主依据（列表里那一行短文案）。 */
  val primaryReason: MatchReason
    get() = matchReasons.firstOrNull() ?: MatchReason.TEXT_ONLY

  /** 分数上限被谁压住了，供日志排查。 */
  val cappedOut: Boolean
    get() = conflictFlags.any { it in CAP_FLAGS }

  private companion object {
    val CAP_FLAGS =
      setOf(
        ConflictFlag.SUFFIX_CONFLICT,
        ConflictFlag.PARTIAL_CODE,
        ConflictFlag.NO_CODE_EVIDENCE,
        ConflictFlag.WEAK_IDENTIFIER,
        ConflictFlag.AMBIGUOUS_QUERY,
      )
  }
}

/**
 * 决策阈值。全部可配置（设计里明确要求），默认值来自真实字幕库的调参结果。
 *
 * @property autoMatchScore 自动匹配线
 * @property candidateScore 进入候选列表的线。低于此分直接不显示 ——
 *   列表里塞满 40 分的噪音，用户反而找不到那一条对的
 * @property minTopGap Top1 与 Top2 的最小分差。同分并列时人也不知道选哪条，
 *   这时交给用户比替用户猜更安全
 * @property minIdentifierConfidence 编号置信度门槛。低于它就只剩文本证据
 * @property maxCandidates 最多返回几条
 */
data class MatchConfig(
  val autoMatchScore: Double = 90.0,
  val candidateScore: Double = 62.0,
  val minTopGap: Double = 8.0,
  val minIdentifierConfidence: Double = 0.7,
  val maxCandidates: Int = 5,
)

/**
 * 自动匹配决策（对应设计里的 MatchDecisionEngine）。
 *
 * ## 与阈值的分工
 *
 * 分数只说明「像」，[ConflictDetector] 才说明「能不能信」。所以自动匹配是
 * 「分数够高 **且** 没有任何结构性冲突 **且** 领先第二名足够多」三条同时成立，
 * 缺一条就退化成候选。反对称地，只要出现 [ConflictFlag.CODE_CONFLICT]，
 * 无论分数多高都不会出现在结果里（那一层已经在 ConflictDetector 里淘汰了）。
 */
object MatchDecisionEngine {
  /**
   * 把已打分的候选整理成最终结果：应用阈值、标注等级、判定能否自动匹配。
   *
   * @param scored 已按分数降序排好的候选
   */
  fun decide(
    scored: List<ScoredCandidate>,
    query: TitleFacts,
    config: MatchConfig,
  ): List<SubtitleMatchResult> {
    if (scored.isEmpty()) return emptyList()

    val kept = scored.filter { it.score >= config.candidateScore }.take(config.maxCandidates)
    if (kept.isEmpty()) return emptyList()

    val top = kept.first()
    val runnerUp = kept.getOrNull(1)
    val gap = if (runnerUp != null) top.score - runnerUp.score else MatchScorer.FULL_SCORE

    /**
     * 同分并列、但两条都已经是「同一部作品的不同版本」⇒ 不撤销自动采用。
     *
     * 分差门槛本来是为了拦「两条都像，人也不知道选哪条」；而同一部片子在字幕目录里
     * 常有多份（`xxx.ja.merged` / `xxx.ja.pass1`、`_1` / `_2`），它们的编号都与媒体
     * 完全一致，分数自然齐平在 100。这时并列只说明「版本不止一个」，不说明
     * 「可能选错作品」—— 按排序取第一条即可，用户仍能在列表里换。
     * 反过来说，两条并列都是 100 却因为分差为 0 而给不出「可直接使用」，
     * 是这个门槛最容易被用户当成 bug 的形态。
     */
    val tiedSameWork =
      runnerUp != null &&
        gap < config.minTopGap &&
        top.score >= config.autoMatchScore &&
        runnerUp.score >= config.autoMatchScore &&
        (
          (top.identifierMatchType in STRONG_CODE_MATCH && runnerUp.identifierMatchType in STRONG_CODE_MATCH) ||
            (top.strongText && runnerUp.strongText)
        )
    val gapOk = gap >= config.minTopGap || tiedSameWork

    return kept.mapIndexed { index, candidate ->
      // 文本几乎逐字相同时，编号缺失 / 置信度低不再是否决理由（见 ConflictDetector）。
      // 但「编号冲突」「后缀冲突」「只对上尾号」「集号不同」这些结构性矛盾永远有效。
      val blocking = if (candidate.strongText) HARD_BLOCKING else BLOCKING_CONFLICT
      val canAuto =
        index == 0 &&
          candidate.score >= config.autoMatchScore &&
          gapOk &&
          candidate.conflictFlags.none { it in blocking } &&
          !query.ambiguous &&
          (
            candidate.strongText ||
              !query.hasCode ||
              query.primaryConfidence >= config.minIdentifierConfidence
          ) &&
          candidate.identifierMatchType != CodeMatch.SUFFIX_VARIANT &&
          candidate.identifierMatchType != CodeMatch.PARTIAL

      SubtitleMatchResult(
        relativePath = candidate.path,
        name = candidate.name,
        score = candidate.score,
        identifierMatchType = candidate.identifierMatchType,
        identifierSimilarity = candidate.features.code ?: 0.0,
        titleSimilarity = candidate.features.title ?: 0.0,
        tokenSimilarity = candidate.features.token ?: 0.0,
        ngramSimilarity = candidate.features.ngram ?: 0.0,
        metaSimilarity = candidate.features.meta,
        conflictFlags = candidate.conflictFlags,
        confidenceLevel =
          when {
            candidate.score >= config.autoMatchScore -> ConfidenceLevel.HIGH
            candidate.score >= 75.0 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
          },
        matchReasons = candidate.reasons,
        canAutoMatch = canAuto,
      )
    }
  }

  /**
   * 「编号层面已经确认是同一部」的几个等级：写法一致、分隔符差异、用户确认的别名。
   *
   * [CodeMatch.ZERO_VARIANT] 不在其中 —— 它自带上限（[ConflictDetector.CAP_WEAK_IDENTIFIER]），
   * 永远够不到自动线，列进来只会让这份名单看起来像「什么都能自动」。
   */
  private val STRONG_CODE_MATCH =
    setOf(CodeMatch.EXACT, CodeMatch.FORMAT_VARIANT, CodeMatch.ALIAS)

  /**
   * 会否决自动采用的标记。
   *
   * 注意不是「有任何标记就不许自动」：`LEADING_ZERO`（前导零写法不同）与
   * `VERSION_FLAGS` 这类只是**说明性**标记，它们描述的是「为什么分数是这个值」，
   * 不是「不能信」。把它们也当否决条件，会让 `ABC-001` ↔ `ABC-1` 这种
   * 事实上同一部作品的候选永远需要用户再点一次。
   */
  private val BLOCKING_CONFLICT =
    setOf(
      ConflictFlag.CODE_CONFLICT,
      ConflictFlag.SUFFIX_CONFLICT,
      ConflictFlag.PARTIAL_CODE,
      ConflictFlag.NO_CODE_EVIDENCE,
      ConflictFlag.WEAK_IDENTIFIER,
      ConflictFlag.AMBIGUOUS_QUERY,
      ConflictFlag.EPISODE_CONFLICT,
    )

  /**
   * 无论文本多像都必须否决的标记。
   *
   * 它们描述的是「这两条指向的不是同一部 / 同一集」，属于结构性矛盾；
   * 而 `NO_CODE_EVIDENCE` / `WEAK_IDENTIFIER` 只是「编号证据不足」，
   * 在文本几乎逐字相同的前提下不该继续挡路（`910 白雪美音…` 这类编号只有 3 位）。
   */
  private val HARD_BLOCKING =
    setOf(
      ConflictFlag.CODE_CONFLICT,
      ConflictFlag.SUFFIX_CONFLICT,
      ConflictFlag.PARTIAL_CODE,
      ConflictFlag.AMBIGUOUS_QUERY,
      ConflictFlag.EPISODE_CONFLICT,
    )
}

/**
 * 打完分、还没套阈值的中间结果。
 *
 * @property path 相对路径
 * @property name 文件名
 * @property score 已应用 cap 的分数
 * @property rawScore 未应用 cap 的分数（日志里对比用，能看出「被谁压住了」）
 * @property stemExact 两侧字干是否完全相等
 * @property strongText 文本证据是否几乎逐字相同（见 [MatchScorer.isStrongText]）
 */
data class ScoredCandidate(
  val path: String,
  val name: String,
  val score: Double,
  val rawScore: Double,
  val identifierMatchType: CodeMatch,
  val features: FeatureScores,
  val conflictFlags: Set<ConflictFlag>,
  val reasons: List<MatchReason>,
  val stemExact: Boolean = false,
  val strongText: Boolean = false,
)
