package app.marlboroadvance.mpvex.domain.smartmerge

import app.marlboroadvance.mpvex.ui.player.subtitle.smart.FilenameNormalizer

/**
 * 候选分组与置信度评分。**纯函数，不联网、不写数据。**
 *
 * 番号是基础身份；ProviderIds / 标题 / 演员 / 时长是否参与，由 [SmartMergeRules] 决定。
 * 任何严重冲突都封顶 69 分，强制人工确认；不做自动合并。
 */
object SmartMergeMatcher {

  fun buildCandidates(
    serverId: Long,
    items: List<SmartMergeItem>,
    ignoreRules: List<SmartMergeIgnoreRule> = emptyList(),
    rules: SmartMergeRules = SmartMergeRules(),
  ): List<SmartMergeCandidate> {
    val out = ArrayList<SmartMergeCandidate>()

    // ① 有番号的条目：按 codeBody 分组。
    val codeGroups = items
      .asSequence()
      .filter { !it.alreadyMultiVersion }
      .filter { it.codeBody?.isNotBlank() == true }
      .filter { it.codeConfidence >= 0.55 }
      .groupBy { it.codeBody!! }
      .filterValues { it.size >= 2 }
    for ((body, members) in codeGroups) addCandidate(
      out = out,
      serverId = serverId,
      items = members,
      code = members.maxByOrNull { it.codeConfidence }?.code ?: body,
      groupId = body,
      ignoreRules = ignoreRules,
      rules = rules,
    )

    // ② 没有番号、但 ProviderIds 有稳定共享值：可选单独成组。
    if (rules.allowProviderOnlyGroups) {
      val providerGroups = items
        .asSequence()
        .filter { !it.alreadyMultiVersion }
        .filter { it.codeBody.isNullOrBlank() }
        .flatMap { item ->
          item.providerIds.entries
            .filter { it.value.length >= 4 }
            .map { (key, value) -> "$key=${value.lowercase()}" to item }
        }
        .groupBy({ it.first }, { it.second })
        .filterValues { group -> group.map { it.id }.distinct().size >= 2 }
      for ((anchor, members) in providerGroups) {
        addCandidate(
          out = out,
          serverId = serverId,
          items = members.distinctBy { it.id },
          code = "PROVIDER:$anchor",
          groupId = "provider:$anchor",
          ignoreRules = ignoreRules,
          rules = rules,
        )
      }
    }

    return out
      .distinctBy { it.id }
      .filter { it.confidence >= rules.minScore }
      .sortedWith(
        compareByDescending<SmartMergeCandidate> { it.confidence }
          .thenBy { it.code },
      )
  }

  /**
   * 手动搜索选中的条目 → 一个候选组（同样走评分与冲突检查）。
   *
   * 与自动扫描不同，这里**允许任意条目组合**：用户可以分多次搜索、把 A / B / C
   * 逐个加入待处理清单，再一次性组合。为了不让「手动」绕过全部保护，以下情况
   * 一律记冲突并把置信度封顶到 69，强制用户在确认页再看一遍：
   *  - 所选条目番号主体不同（A 与 C 不是同一部作品）；
   *  - 混入了已经是多版本的条目（可能重复组合）；
   *  - 完全认不出番号（仍然允许，但 code 记为「手动组合」）。
   */
  fun candidateFromSelection(
    serverId: Long,
    items: List<SmartMergeItem>,
    rules: SmartMergeRules = SmartMergeRules(),
  ): SmartMergeCandidate? {
    val members = items.distinctBy { it.id }
    if (members.size < 2) return null
    val code = members.firstNotNullOfOrNull { it.code?.takeIf { c -> c.isNotBlank() } }
      ?: "手动组合"
    val body = members.firstNotNullOfOrNull { it.codeBody?.takeIf { b -> b.isNotBlank() } } ?: code
    val (score, baseConflicts) = score(
      members,
      members.maxOfOrNull { it.codeConfidence } ?: 0.0,
      rules,
    )
    val conflicts = baseConflicts.toMutableList()
    val bodies = members.mapNotNull { it.codeBody?.takeIf { b -> b.isNotBlank() } }.distinct()
    if (bodies.size > 1) {
      conflicts += "所选条目番号不同：" + members
        .mapNotNull { it.code?.takeIf { c -> c.isNotBlank() } }
        .distinct()
        .joinToString(" / ")
    }
    if (members.any { it.alreadyMultiVersion }) {
      conflicts += "包含已经是多版本的条目，可能重复组合"
    }
    val primary = members.maxByOrNull { metadataScore(it) }
    return SmartMergeCandidate(
      id = "${candidateId(serverId, body)}-manual-${System.currentTimeMillis()}",
      serverId = serverId,
      code = code,
      title = displayTitle(members),
      itemIds = members.map { it.id },
      items = members,
      confidence = if (conflicts.isNotEmpty()) minOf(score, 69) else score,
      conflicts = conflicts,
      status = when {
        conflicts.isNotEmpty() -> STATUS_CONFLICT
        score >= 90 -> STATUS_HIGH
        else -> STATUS_PENDING
      },
      sourceSummary = members.map { it.source }.distinct().joinToString(" / "),
      versionSummary = members.map { it.version.label }.distinct().joinToString(" / "),
      suggestedPrimaryId = primary?.id,
      createdAt = System.currentTimeMillis(),
      updatedAt = System.currentTimeMillis(),
    )
  }

  fun isIgnored(
    rules: List<SmartMergeIgnoreRule>,
    candidateId: String,
    code: String,
    itemIds: List<String>,
  ): Boolean {
    val pair = itemIds.sorted().joinToString("|")
    return rules.any { rule ->
      when (rule.type) {
        "code" -> rule.value.equals(code, ignoreCase = true)
        "candidate" -> rule.value == candidateId
        "item" -> rule.value in itemIds
        "pair" -> rule.value == pair
        else -> false
      }
    }
  }

  private fun addCandidate(
    out: MutableList<SmartMergeCandidate>,
    serverId: Long,
    items: List<SmartMergeItem>,
    code: String,
    groupId: String,
    ignoreRules: List<SmartMergeIgnoreRule>,
    rules: SmartMergeRules,
  ) {
    val members = items.distinctBy { it.id }
    if (members.size < 2) return
    val id = candidateId(serverId, groupId)
    if (isIgnored(ignoreRules, id, code, members.map { it.id })) return
    val confidence = members.maxOfOrNull { it.codeConfidence } ?: 0.0
    val (score, conflicts) = score(members, confidence, rules)
    val primary = members.maxByOrNull { metadataScore(it) }
    out += SmartMergeCandidate(
      id = id,
      serverId = serverId,
      code = code,
      title = displayTitle(members),
      itemIds = members.map { it.id },
      items = members,
      confidence = if (conflicts.isNotEmpty()) minOf(score, 69) else score,
      conflicts = conflicts,
      status = when {
        conflicts.isNotEmpty() -> STATUS_CONFLICT
        score >= 90 -> STATUS_HIGH
        else -> STATUS_PENDING
      },
      sourceSummary = members.map { it.source }.distinct().joinToString(" / "),
      versionSummary = members.map { it.version.label }.distinct().joinToString(" / "),
      suggestedPrimaryId = primary?.id,
      createdAt = System.currentTimeMillis(),
      updatedAt = System.currentTimeMillis(),
    )
  }

  // ──────────────────────────── 评分 ────────────────────────────

  private fun score(
    items: List<SmartMergeItem>,
    codeConfidence: Double,
    rules: SmartMergeRules,
  ): Pair<Int, List<String>> {
    val hasCode = items.all { !it.codeBody.isNullOrBlank() }
    var score = if (hasCode) 40 else 0
    val conflicts = mutableListOf<String>()

    if (rules.useProviderIds) {
      val sharedProvider = HashMap<String, String>()
      val providerConflicts = HashSet<String>()
      for (item in items) {
        for ((key, value) in item.providerIds) {
          if (value.isBlank()) continue
          val previous = sharedProvider[key]
          if (previous == null) {
            sharedProvider[key] = value
          } else if (!previous.equals(value, ignoreCase = true)) {
            providerConflicts += key
          }
        }
      }
      if (sharedProvider.isNotEmpty() && providerConflicts.isEmpty()) score += 20
      if (!hasCode && sharedProvider.isNotEmpty()) score += 20
      if (providerConflicts.isNotEmpty()) conflicts += "ProviderIds 冲突：${providerConflicts.joinToString()}"
    }

    if (rules.useTitle) {
      val stems = items.mapNotNull {
        runCatching { FilenameNormalizer.normalize(it.displayTitle).stem }.getOrNull()
      }.filter { it.isNotBlank() }.distinct()
      if (stems.size == 1 && stems.first().isNotEmpty()) {
        score += 15
      } else if (stems.any { a -> stems.any { b -> a != b && (a.contains(b) || b.contains(a)) } }) {
        score += 10
      }
    }

    if (rules.useActors) {
      val actorSets = items.map { item -> item.people.map { it.lowercase() }.toSet() }
      val union = actorSets.flatten().toSet()
      val common = actorSets.reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
      if (common.isNotEmpty()) score += 10
      if (union.isNotEmpty() && common.isEmpty() && items.size >= 2) {
        conflicts += "演员无重合"
      }
    }

    val studios = items.flatMap { it.studios }.map { it.lowercase() }.toSet()
    if (studios.size == 1 && items.all { it.studios.isNotEmpty() }) score += 5

    val years = items.mapNotNull { it.productionYear }
    if (years.size == items.size && years.max() - years.min() <= 1) score += 5
    if (years.size == items.size && years.max() - years.min() > 2) conflicts += "年份差异过大"

    if (rules.useDuration) {
      val runtimes = items.mapNotNull { it.runtimeTicks }.map { it / 10_000_000.0 }
      if (runtimes.size == items.size && runtimes.size >= 2) {
        val min = runtimes.min()
        val max = runtimes.max()
        val tolerance = maxOf(120.0, max * 0.05)
        if (max - min <= tolerance) score += 5 else conflicts += "时长差异过大"
      }
    }

    if (hasCode && codeConfidence < 0.6) conflicts += "番号识别置信度低"

    return score.coerceIn(0, 100) to conflicts.distinct()
  }

  private fun metadataScore(item: SmartMergeItem): Int =
    (item.overview?.length ?: 0).coerceAtMost(400) / 20 +
      item.people.size * 2 +
      item.providerIds.size * 3 +
      item.studios.size +
      item.mediaSources.size * 2 +
      (if (item.imageTag != null) 10 else 0)

  private fun displayTitle(items: List<SmartMergeItem>): String =
    items.maxByOrNull { metadataScore(it) }?.displayTitle
      ?: items.firstOrNull()?.displayTitle
      ?: "未命名作品"

  private fun candidateId(serverId: Long, body: String): String =
    "$serverId:${body.lowercase()}"
}
