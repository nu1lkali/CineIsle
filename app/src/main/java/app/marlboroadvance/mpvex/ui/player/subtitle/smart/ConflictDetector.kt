package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/** 冲突 / 证据不足的标记。会原样带进 [MatchResult]，也写进日志。 */
enum class ConflictFlag {
  /** 番号主体明确冲突（同厂牌相邻编号、不同厂牌、数字互不包含） */
  CODE_CONFLICT,

  /** 数字主体相同、字母后缀不同（`ABC-123` / `ABC-123B`）：可能是不同剪辑 */
  SUFFIX_CONFLICT,

  /** 只有前导零不同（`ABC-001` / `ABC-1`）：通常等价，但不敢当「完全一致」 */
  LEADING_ZERO,

  /** 只有数字片段重合（`022313-272` / `272`）：弱候选 */
  PARTIAL_CODE,

  /** 番号提取置信度偏低（裸数字、超短数字段） */
  WEAK_IDENTIFIER,

  /** 查询侧存在多个互相矛盾的编号，无法确定以哪个为准 */
  AMBIGUOUS_QUERY,

  /** 没有任何编号证据，只剩文本相似度 */
  NO_CODE_EVIDENCE,

  /** 剧集号冲突：同一系列的别的集 */
  EPISODE_CONFLICT,
}

/**
 * 冲突报告：标记集合 + 是否直接淘汰 + 分数上限。
 *
 * 上限（cap）机制是「准确率优先」的落点：权重决定谁排前面，cap 决定谁**不许自动**。
 * 分开之后，弱证据的候选仍然会出现在列表里（用户可能正好就要它），
 * 但它永远拿不到「高置信度」这个标签。
 */
data class ConflictReport(
  val flags: Set<ConflictFlag>,
  /** true ⇒ 这条候选直接丢弃，不进候选列表 */
  val fatal: Boolean,
  /** 分数上限，0..100 */
  val scoreCap: Double,
)

/**
 * 冲突检测（对应设计里的 ConflictDetector）。
 *
 * ## 这里的每一条都在拦真实发生过的误配
 *
 * - `N0907` vs `N09070`：同厂牌数字前缀 ⇒ 冲突
 * - `ABC-123` vs `ABC-124`：同厂牌相邻编号 ⇒ 冲突
 * - `ABC-123` vs `ABC-123B`：后缀不同 ⇒ 封顶 85（不自动）
 * - `022313-272` vs `272-carib`：只对上尾号 ⇒ 封顶 74（只当候选）
 * - 番号对不上但标题词全中（`www`、`iris2` 这类共有词）⇒ 封顶 80
 */
object ConflictDetector {
  /** 各情形下的分数上限。 */
  const val CAP_SUFFIX_VARIANT = 85.0
  const val CAP_PARTIAL_CODE = 74.0
  const val CAP_NO_CODE = 80.0
  const val CAP_WEAK_IDENTIFIER = 89.0
  const val CAP_TEXT_ONLY = 84.0

  /**
   * @param codeMatch 编号关系
   * @param queryHasCode 查询侧是否提取到可用编号
   * @param stemExact 两侧字干是否完全相等（剥掉站点前缀与语言标签之后）
   * @param queryConfidence 查询主编号的提取置信度
   * @param ambiguousQuery 查询侧编号是否自相矛盾
   * @param strongText 文本证据是否「几乎逐字相同」（见 [MatchScorer.isStrongText]）
   * @param episodeConflict 两侧都识别出集号且不一致
   */
  fun inspect(
    codeMatch: CodeMatch,
    queryHasCode: Boolean,
    stemExact: Boolean,
    queryConfidence: Double,
    ambiguousQuery: Boolean,
    strongText: Boolean = false,
    episodeConflict: Boolean = false,
  ): ConflictReport {
    val flags = LinkedHashSet<ConflictFlag>()
    var cap = MatchScorer.FULL_SCORE

    if (codeMatch == CodeMatch.CONFLICT) {
      flags += ConflictFlag.CODE_CONFLICT
      // 番号说不是这一部，就真的不是 —— 标题再像也不救
      return ConflictReport(flags, fatal = true, scoreCap = 0.0)
    }

    if (episodeConflict) {
      // `S05E16` 与 `S05E15` 的标题几乎一模一样，只有集号能分开它们。
      // 这一条与「相邻番号」同级：直接淘汰，不给候选。
      flags += ConflictFlag.EPISODE_CONFLICT
      return ConflictReport(flags, fatal = true, scoreCap = 0.0)
    }

    when (codeMatch) {
      CodeMatch.SUFFIX_VARIANT -> {
        flags += ConflictFlag.SUFFIX_CONFLICT
        cap = minOf(cap, CAP_SUFFIX_VARIANT)
      }
      CodeMatch.PARTIAL -> {
        flags += ConflictFlag.PARTIAL_CODE
        cap = minOf(cap, CAP_PARTIAL_CODE)
      }
      CodeMatch.ZERO_VARIANT -> {
        // 前导零只是写法：`ABC-001` 与 `ABC-1` 是同一部作品，不该封顶。
        // 标记保留给日志与文案（「前导零写法不同」），但不阻止自动采用 ——
        // 自动线本身（分数 ≥ 90）已经能拦住「标题也不一样」的那一类。
        flags += ConflictFlag.LEADING_ZERO
      }
      else -> Unit
    }

    // 文本证据「几乎逐字相同」时，编号缺失或置信度低不再是扣分理由：
    // 那种情况下真正的判据就是标题本身，`910`、`545` 这种裸数字只是附带的。
    // 门槛见 [MatchScorer.isStrongText]，它比「编号对上」严格得多。
    if (queryHasCode) {
      if (codeMatch == CodeMatch.NONE && !stemExact && !strongText) {
        // 片子有番号，候选却拿不出番号证据：只可能是「标题词碰巧全中」
        flags += ConflictFlag.NO_CODE_EVIDENCE
        cap = minOf(cap, CAP_NO_CODE)
      }
      if (!strongText &&
        queryConfidence < 0.7 &&
        codeMatch != CodeMatch.EXACT &&
        codeMatch != CodeMatch.FORMAT_VARIANT
      ) {
        flags += ConflictFlag.WEAK_IDENTIFIER
        cap = minOf(cap, CAP_WEAK_IDENTIFIER)
      }
    } else if (!stemExact && !strongText) {
      // 查询侧没有番号（纯标题场景）：字干完全相等是硬证据，其余一律封顶
      flags += ConflictFlag.NO_CODE_EVIDENCE
      cap = minOf(cap, CAP_TEXT_ONLY)
    }

    if (ambiguousQuery) {
      flags += ConflictFlag.AMBIGUOUS_QUERY
      cap = minOf(cap, CAP_WEAK_IDENTIFIER)
    }

    return ConflictReport(flags, fatal = false, scoreCap = cap)
  }
}
