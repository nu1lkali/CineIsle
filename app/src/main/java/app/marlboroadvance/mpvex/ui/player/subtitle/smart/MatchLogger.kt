package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 匹配日志（对应设计里的 MatchLogger）。
 *
 * ## 为什么要有
 *
 * 「为什么这条没匹配上」是这个功能唯一真正难查的问题。用户能给的只有
 * 「这片子字幕没出来」，而原因可能是：番号没提取出来、番号冲突被否、
 * 分数被 cap 压到阈值以下、Top1/Top2 分差不够。这四种在 UI 上长得一模一样。
 *
 * 所以每次匹配都留一份可读的完整推导：解析出什么、每个特征多少分、
 * 被哪个冲突压住、最后为什么接受或拒绝。行数不多（Top5 × 6 行），
 * 只在扫描时打一次，不影响播放。
 */
object MatchLogger {
  /** 单条候选的日志宽度上限，避免超长文件名把日志挤爆。 */
  private const val MAX_NAME = 72

  /**
   * 生成一次匹配的完整说明。
   *
   * @param mediaLabel 媒体名（原始）
   * @param query 解析结果
   * @param results 决策后的结果
   * @param dropped 因冲突被淘汰的候选（路径 → 原因），没有则传空
   */
  fun describe(
    mediaLabel: String,
    query: TitleFacts,
    results: List<SubtitleMatchResult>,
    config: MatchConfig,
    dropped: Map<String, String> = emptyMap(),
  ): String =
    buildString {
      appendLine("[query] ${clip(mediaLabel)}")
      appendLine("[normalized] ${clip(query.normalized)}")
      appendLine("[cleaned] ${clip(query.cleaned)}")
      appendLine("[title] ${clip(query.cleanedTitle)}")
      appendLine(
        "[identifiers] " +
          query.identifiers.joinToString(", ") {
            "${it.raw}(conf=${fmt(it.confidence)})"
          }.ifEmpty { "none" } +
          " | primary=${
            query.primaryCode?.let { it.compact } ?: "none"
          } | ambiguous=${query.ambiguous}",
      )
      appendLine("[tokens] ${query.tokens.joinToString(" ").ifEmpty { "-" }}")
      appendLine("[stem] ${query.stem.ifEmpty { "-" }}")
      if (dropped.isNotEmpty()) {
        appendLine("[dropped] ${dropped.size}")
        dropped.forEach { (path, why) -> appendLine("  x ${clip(path)} -> $why") }
      }
      if (results.isEmpty()) {
        appendLine("[result] no candidate >= ${fmt(config.candidateScore)}")
        return@buildString
      }
      appendLine("[result] ${results.size} candidate(s)")
      results.forEachIndexed { index, r ->
        appendLine(
          "  ${index + 1}. ${fmt(r.score)} (${r.confidenceLevel}${if (r.canAutoMatch) ", auto" else ""}) " +
            "code=${r.identifierMatchType}/${fmt(r.identifierSimilarity)} " +
            "title=${fmt(r.titleSimilarity)} token=${fmt(r.tokenSimilarity)} " +
            "ngram=${fmt(r.ngramSimilarity)} meta=${r.metaSimilarity?.let { fmt(it) } ?: "-"} " +
            "flags=${r.conflictFlags.joinToString(",").ifEmpty { "-" }} " +
            "why=${r.matchReasons.joinToString(",")}",
        )
        appendLine("     ${clip(r.name)}")
      }
    }

  private fun fmt(value: Double): String =
    if (value == 0.0) "0" else String.format(java.util.Locale.US, "%.2f", value)

  private fun clip(text: String): String =
    if (text.length <= MAX_NAME) text else text.take(MAX_NAME) + "…"
}
