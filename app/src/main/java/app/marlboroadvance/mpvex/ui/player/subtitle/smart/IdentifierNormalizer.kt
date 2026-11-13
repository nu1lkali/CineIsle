package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 番号的可比较形式：字母段 + 数字主体 + 字母后缀。
 *
 * 拆三段而不是一个串，是为了让「同厂牌相邻编号」(`ABC-123` / `ABC-124`) 与
 * 「同编号不同后缀」(`ABC-123` / `ABC-123B`) 各自落到正确的等级上，
 * 而不是笼统地算一个编辑距离。
 */
data class CodeKey(
  val letters: String,
  val numericCore: String,
  val numericRaw: String,
  val suffix: String,
) {
  /** 字母 + 数字主体，不含后缀。 */
  val body: String
    get() = letters + numericCore

  /** 完整紧凑写法（含后缀），用于别名表与日志。 */
  val compact: String
    get() = body + suffix

  companion object {
    fun of(identifier: Identifier): CodeKey =
      CodeKey(
        letters = identifier.letters,
        numericCore = identifier.numericCore,
        numericRaw = identifier.numericRaw,
        suffix = identifier.suffix,
      )
  }
}

/**
 * 两个番号之间的关系等级。分值直接参与 [MatchScorer] 的编号特征（权重 45%）。
 *
 * 顺序即「可信度从高到低」，注意 [SUFFIX_VARIANT] 与 [PARTIAL] 都**不允许自动匹配**：
 * 前者是同一编号的不同剪辑，后者只是数字片段重合，两者都是误配的高发区。
 *
 * [PARTIAL] 的分值不是「像不像」的分数，而是「够不够进候选列表」的下限：
 * 查询侧没有标题内容时（`www.98T.la@022313-272` 这类纯番号名），整条分数就等于
 * 本枚举的分值乘 100。定得太低（旧值 0.30）会让「尾号型」候选连
 * `candidateScore` 都过不去，用户永远看不到库里明明存在的那条字幕 ——
 * 那比显示一条弱候选更糟。真正的安全闸门是 [ConflictDetector.CAP_PARTIAL_CODE]。
 */
enum class CodeMatch(val similarity: Double) {
  /** 归一化后完全一致（含前导零写法一致） */
  EXACT(1.0),

  /** 仅分隔符 / 大小写不同（`ABC-123` / `ABC123`） */
  FORMAT_VARIANT(0.97),

  /** 命中用户确认过的别名 */
  ALIAS(0.93),

  /** 前导零不同（`ABC-001` / `ABC-1`）：写法差异，实质上就是同一部 */
  ZERO_VARIANT(0.92),

  /** 数字主体相同、字母后缀不同（`ABC-123` / `ABC-123B`） */
  SUFFIX_VARIANT(0.68),

  /** 只对上数字片段（`022313-272` / `272`），弱候选 */
  PARTIAL(0.66),

  /** 明确冲突：同厂牌相邻编号、不同厂牌、数字主体互不包含 */
  CONFLICT(0.0),

  /** 没有可比较的编号 */
  NONE(0.0),
}

/**
 * 番号标准化与变体判定（对应设计里的 IdentifierNormalizer）。
 *
 * ## 判定的优先级
 *
 * 1. 字母段与数字主体都相等 ⇒ 看后缀决定 [CodeMatch.EXACT] / [CodeMatch.SUFFIX_VARIANT]
 * 2. 字母段相等、数字主体不等 ⇒ **一律 [CodeMatch.CONFLICT]**
 *
 *    这一条是准确率的地基。`SSIS-123` 与 `SSIS-1234`、`N0907` 与 `N09070`
 *    在字面上只差一位，但同厂牌下就是两部不同的作品。旧实现用 `contains`
 *    判命中，用户报的「番号对上也不准」正是这里漏出去的。
 *
 * 3. 一方字母段为空、另一方非空，数字主体互为前后缀 ⇒ [CodeMatch.PARTIAL]
 *
 *    `N0907` 与 `0907` 属于这一类：可以给弱候选，绝不自动挂。
 *
 * 4. 双方字母段都为空（日期型 / 尾号型）⇒ 数字主体相等即一致，互为后缀 ⇒ [CodeMatch.PARTIAL]
 *
 *    加勒比系的 `022313-272` 与字幕名 `272-carib` 靠这条救回，但仍然只是候选。
 */
object IdentifierNormalizer {
  /** 前导零差异超过这个位数就是两个不同的号（`001` 与 `00001` 不算变体）。 */
  private const val MAX_ZERO_DIFF = 2

  /**
   * 比一对番号。
   *
   * @param alias 两侧是否已在用户确认的别名表里建立等价关系
   */
  fun compare(a: CodeKey, b: CodeKey, alias: Boolean = false): CodeMatch {
    if (a.body.isEmpty() || b.body.isEmpty()) return CodeMatch.NONE

    if (a.body == b.body) {
      if (a.suffix == b.suffix) {
        // 写法已经一致的编号**不能**因为别名的存在而降级成 ALIAS。
        // 别名只用来把「写法不同的两个编号」拉到同一档；写法相同时它就是 EXACT，
        // 否则每一条完全正确的候选都会被压在 0.93 上，永远够不到自动采用线。
        if (a.numericRaw != b.numericRaw &&
          kotlin.math.abs(a.numericRaw.length - b.numericRaw.length) <= MAX_ZERO_DIFF
        ) {
          return CodeMatch.ZERO_VARIANT
        }
        return if (a.compact == b.compact) CodeMatch.EXACT else CodeMatch.FORMAT_VARIANT
      }
      return if (alias) CodeMatch.ALIAS else CodeMatch.SUFFIX_VARIANT
    }

    // 主体不同的两个编号，只有用户确认过的别名关系才允许拉平
    if (alias) return CodeMatch.ALIAS

    val aEmpty = a.letters.isEmpty()
    val bEmpty = b.letters.isEmpty()

    // 两侧都没有字母段（日期型 `022313-272` / 尾号型 `272`）：**没有厂牌可比**，
    // 只能看数字之间的关系。这一步必须排在「字母段相同 ⇒ 冲突」之前：
    // 两个空串恒等，先比对字母段会把这对本来能救回来的候选直接判成两部作品。
    if (aEmpty && bEmpty) {
      val short = if (a.numericCore.length <= b.numericCore.length) a.numericCore else b.numericCore
      val long = if (a.numericCore.length <= b.numericCore.length) b.numericCore else a.numericCore
      return if (short.length >= 3 && (long.endsWith(short) || long.startsWith(short))) {
        CodeMatch.PARTIAL
      } else {
        CodeMatch.CONFLICT
      }
    }

    if (a.letters == b.letters) {
      // 同厂牌、数字主体不同 ⇒ 两部片子。前缀 / 后缀关系不构成任何证据。
      return CodeMatch.CONFLICT
    }

    // 一方有字母段、一方没有：`N0907` 与 `0907`，可能是同一部（字幕只留数字），
    // 也可能是另一部，只当弱候选。
    if (aEmpty || bEmpty) {
      val short = if (a.numericCore.length <= b.numericCore.length) a.numericCore else b.numericCore
      val long = if (a.numericCore.length <= b.numericCore.length) b.numericCore else a.numericCore
      if (short.length >= 3 && (long.endsWith(short) || long.startsWith(short))) {
        return CodeMatch.PARTIAL
      }
      return CodeMatch.CONFLICT
    }

    return CodeMatch.CONFLICT
  }

  /**
   * 一组番号对另一组番号的最佳关系。
   *
   * 任一组合产生 [CodeMatch.CONFLICT] 不会被当成「整体冲突」—— 两侧都可能有误提取的
   * 假番号（`1PON`、`VOL-545`）。只有**所有**组合都冲突时才算冲突，
   * 这样既不会放过真冲突，也不会被一个假番号毁掉整条正确候选。
   */
  fun bestMatch(
    left: List<CodeKey>,
    right: List<CodeKey>,
    isAlias: (CodeKey, CodeKey) -> Boolean = { _, _ -> false },
  ): CodeMatch {
    if (left.isEmpty() || right.isEmpty()) return CodeMatch.NONE
    var best = CodeMatch.CONFLICT
    var sawNonConflict = false
    for (a in left) {
      for (b in right) {
        val match = compare(a, b, alias = isAlias(a, b))
        if (match == CodeMatch.CONFLICT || match == CodeMatch.NONE) continue
        sawNonConflict = true
        if (match.similarity > best.similarity) best = match
      }
    }
    return if (sawNonConflict) best else CodeMatch.CONFLICT
  }
}
