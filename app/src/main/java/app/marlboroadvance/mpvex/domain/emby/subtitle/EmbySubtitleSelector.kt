package app.marlboroadvance.mpvex.domain.emby.subtitle

/** 「为什么选了这一条」—— 自动选择结果必须可追踪（需求 4 末尾 / 需求 10）。 */
enum class SubtitlePickReason {
  /** 用户此前为这部片子亲手选过这一条（键存在偏好里） */
  REMEMBERED,

  /** 命中用户设置的首选语言 */
  PREFERRED_LANGUAGE,

  /** 命中的是 Emby 标为默认的字幕轨（且没有任何首选语言候选） */
  EMBY_DEFAULT,

  /** 只有一条可用字幕 ⇒ 直接用它（用户诉求：单字幕不必再挑） */
  SINGLE_ONLY,

  /** 多条候选但都同属一种语言（例如只有简中 + 简中 SDH）⇒ 用其中最优的一条 */
  ONLY_LANGUAGE,

  /** 命中用户允许的其它语言（首选语言的替补） */
  ALLOWED_LANGUAGE,

  /** 用户没配过任何语言偏好时，按设备系统语言兜底 */
  SYSTEM_LANGUAGE,

  /** 没有任何符合条件的字幕 ⇒ 保持关闭，不随便加载一种语言 */
  NONE,
}

/**
 * 一次字幕自动选择的结果。
 *
 * @property chosen 选中的那条；null 表示「应保持关闭」
 * @property detail 一句话理由（写日志、也在面板上给用户看）
 * @property trace 逐层判定过程，排查「为什么选了这条」时看它
 */
data class SubtitleSelection(
  val chosen: SubtitleInfo?,
  val reason: SubtitlePickReason,
  val detail: String,
  val trace: List<String> = emptyList(),
) {
  val isNone: Boolean get() = chosen == null

  companion object {
    fun none(detail: String, trace: List<String> = emptyList()) =
      SubtitleSelection(null, SubtitlePickReason.NONE, detail, trace)
  }
}

/**
 * Emby 字幕自动选择策略 —— **纯函数**，不碰网络、不碰 mpv、不碰 Android。
 *
 * ## 优先级（高 → 低）
 *
 * 1. **用户亲手选过的**（[rememberedKey]）—— 精确到某一条流；
 * 2. **用户设置的首选语言**里的候选；同一语言内**非听障优先**，其次才看 Emby 的「默认」标记；
 * 3. **整部片子只剩一种选择**——只有一条能用（[SubtitlePickReason.SINGLE_ONLY]），
 *    或多条候选都属于同一种语言（[SubtitlePickReason.ONLY_LANGUAGE]）；
 * 4. **Emby 标为默认**的字幕轨；
 * 5. **用户允许的其它语言**；
 * 6. **设备系统语言**（**只**在用户一条语言偏好都没配过时才用）；
 * 7. 都没有 ⇒ **保持关闭**。
 *
 * ## 为什么第 3 级要存在
 *
 * 语言偏好是为了「在多种语言之间做选择」。片子里**根本只有一种可能**时，这条偏好的前提
 * 就不成立了：偏好英文而片源只有中文，把字幕关掉对用户毫无好处 —— 实测反馈就是
 * 「明明已经从服务端拉下来了，却不挂，得手动去勾」。它仍然受 [allowEmbyDefault]
 * 约束：用户明确关掉「没匹配上也自动选一条」时，宁可保持关闭。
 *
 * ## 为什么「首选语言」排在「Emby 默认」之前
 *
 * 需求正文把 Emby 默认写在第二优先级，但同一份需求的测试场景 B 明确要求
 * 「内嵌英文 + 外挂 ASS 简体中文，优先选择用户设置的简体中文」。两者只有在
 * 「Emby 默认那条**恰好**就是用户首选语言」时才能同时成立 —— 而那种情况由第 2 级的
 * 「同语言内默认优先」覆盖。所以这里把默认标记当作**同语言内的排序权重**，
 * 只有完全没有首选语言候选时它才独立成一级。这样两种表述都不违背。
 *
 * ## 几条硬规则
 *
 * · **强制字幕（Forced）不参与自动选择**：它只是「外语对白翻译」，当正片字幕用会
 *   让人以为字幕坏了。用户仍可在面板里手动选它。
 * · **听障字幕（SDH）降权**：同语言里有普通轨就不选 SDH。
 * · **不支持 / 取不到字节的不进候选**（图像字幕、烧录、VobSub 单文件）——
 *   它们会出现在面板上并标注原因，但不会被自动选中。
 * · 认不出语言（`und`）的轨道**不匹配任何语言偏好**，因此不会被自动选中。
 */
object EmbySubtitleSelector {
  /**
   * @param subtitles 当前**实际播放的媒体源**下的全部字幕（顺序按 Emby 返回顺序）
   * @param preferredLanguages 用户首选语言，**有序**；可写 `zh-CN` / `chi` / `English`
   * @param allowedLanguages 允许的替补语言（可为空 = 不限）
   * @param rememberedKey 用户上次为这部片子选过的 [SubtitleInfo.key]
   * @param allowEmbyDefault 是否允许「没有语言匹配时也自动选一条」：跟随 Emby 默认标记，
   *   以及「整部片子只有一条 / 只有一种语言」的兜底都归它管。用户关掉这个开关时传 false
   * @param localeLanguages 设备系统语言（有序，可带地区）。**只在用户一条语言偏好都没配过时**
   *   才应该传非空值 —— 它只是「用户没说想要什么」时的兜底，不能盖过用户自己的设置
   */
  fun select(
    subtitles: List<SubtitleInfo>,
    preferredLanguages: List<String>,
    allowedLanguages: List<String> = emptyList(),
    rememberedKey: String? = null,
    allowEmbyDefault: Boolean = true,
    localeLanguages: List<String> = emptyList(),
  ): SubtitleSelection {
    val trace = mutableListOf<String>()
    if (subtitles.isEmpty()) {
      return SubtitleSelection.none("Emby 未返回任何字幕", trace)
    }
    trace += "Emby 返回字幕 ${subtitles.size} 条"

    // ── 0. 上次的选择（精确到流） ──
    if (!rememberedKey.isNullOrBlank()) {
      val hit = subtitles.firstOrNull { it.key == rememberedKey }
      if (hit != null) {
        trace += "命中上次选择：${hit.describe()}"
        return SubtitleSelection(
          chosen = hit,
          reason = SubtitlePickReason.REMEMBERED,
          detail = "沿用上次为这部片子选的字幕：${hit.describe()}",
          trace = trace,
        )
      }
      trace += "上次选择的流已不存在（key=$rememberedKey），回落到语言偏好"
    }

    // ── 候选池：能挂上 / 能选中，且不是强制字幕 ──
    val excluded = subtitles.filter { it.unsupportedReason != null || it.isForced }
    val eligible = subtitles - excluded.toSet()
    trace += "可选候选 ${eligible.size} 条" +
      if (eligible.size != subtitles.size) "（已排除不支持 / 强制字幕 ${subtitles.size - eligible.size} 条）" else ""
    // 逐条记下「为什么被排除」——日志里只有条数时，排查起来等于没有信息
    excluded.forEach { s ->
      trace += "排除 ${s.describe()}：${s.unsupportedReason ?: "强制字幕（Forced）不参与自动选择"}"
    }
    if (eligible.isEmpty()) {
      val detail = "Emby 有 ${subtitles.size} 条字幕，但没有一条能被播放器使用"
      trace += detail
      return SubtitleSelection.none(detail, trace)
    }

    // ── 1. 首选语言（有序） ──
    for (preference in preferredLanguages) {
      if (preference.isBlank()) continue
      val group = eligible.filter { EmbySubtitleLanguage.matches(preference, it.languageKey) }
      if (group.isEmpty()) {
        trace += "首选语言「$preference」无候选"
        continue
      }
      val best = best(group)
      trace += "首选语言「$preference」命中 ${group.size} 条，取 ${best.describe()}"
      return SubtitleSelection(
        chosen = best,
        reason = SubtitlePickReason.PREFERRED_LANGUAGE,
        detail = "首选语言「${EmbySubtitleLanguage.label(EmbySubtitleLanguage.resolve(preference))}」→ ${best.describe()}",
        trace = trace,
      )
    }

    // ── 2. 整部片子只剩一种可能：只有一条能用 / 只有一种语言 ──
    // 语言偏好是用来「在多种语言之间做选择」的。片子里**根本只有一种可能**时，这条偏好的
    // 前提就不成立：偏好英文而片源只有中文，把字幕关掉对用户毫无好处 —— 实测反馈正是
    // 「明明从服务端拉下来了却不挂，得手动去勾」。
    // 放在 Emby 默认**之前**：Emby 常把该语言唯一那条 SDH 轨标成 default，而这里要在
    // 「同一语言的多条」里按「普通轨优先」挑，不能被默认标记抢在前面。
    if (allowEmbyDefault) {
      // 用户显式填了「还允许这些语言」时，它就是一条硬约束 —— 兜底也不能超出这个范围
      val pool = eligible.filter { languageAllowed(it, allowedLanguages) }
      if (pool.size == 1) {
        val only = pool.first()
        trace += "只有一条可用字幕，直接选用：${only.describe()}"
        return SubtitleSelection(
          chosen = only,
          reason = SubtitlePickReason.SINGLE_ONLY,
          detail = "只有一条可用字幕 → ${only.describe()}",
          trace = trace,
        )
      }
      val languageKeys = pool.map { it.languageKey }.distinct()
      if (languageKeys.size == 1) {
        val best = best(pool)
        trace += "候选都属于同一种语言（${best.languageKey}），取 ${best.describe()}"
        return SubtitleSelection(
          chosen = best,
          reason = SubtitlePickReason.ONLY_LANGUAGE,
          detail = "只有一种语言的字幕 → ${best.describe()}",
          trace = trace,
        )
      }
      if (pool.isEmpty()) {
        trace += "候选都被「允许语言」限制排除了（允许=${allowedLanguages.joinToString("/")}）"
      } else {
        trace += "候选跨 ${languageKeys.size} 种语言（${languageKeys.joinToString("/")}），需要语言偏好才能定夺"
      }
    } else {
      trace += "已禁用「跟随 Emby 默认标记」，无语言匹配时不再兜底"
    }

    // ── 3. Emby 默认标记 ──
    if (allowEmbyDefault) {
      val defaults = eligible.filter { it.isDefault }.filter { languageAllowed(it, allowedLanguages) }
      if (defaults.isNotEmpty()) {
        val best = best(defaults)
        trace += "跟随 Emby 默认标记：${best.describe()}"
        return SubtitleSelection(
          chosen = best,
          reason = SubtitlePickReason.EMBY_DEFAULT,
          detail = "Emby 默认字幕 → ${best.describe()}",
          trace = trace,
        )
      }
      trace += "Emby 没有可用的默认字幕"
    }

    // ── 4. 允许的其它语言 ──
    for (language in allowedLanguages) {
      if (language.isBlank()) continue
      val group = eligible.filter { EmbySubtitleLanguage.matches(language, it.languageKey) }
      if (group.isEmpty()) continue
      val best = best(group)
      trace += "允许语言「$language」命中 ${group.size} 条，取 ${best.describe()}"
      return SubtitleSelection(
        chosen = best,
        reason = SubtitlePickReason.ALLOWED_LANGUAGE,
        detail = "允许语言「${EmbySubtitleLanguage.label(EmbySubtitleLanguage.resolve(language))}」→ ${best.describe()}",
        trace = trace,
      )
    }

    // ── 5. 设备系统语言（只有用户没配过任何语言偏好时才有值） ──
    for (language in localeLanguages) {
      if (language.isBlank()) continue
      val group = eligible.filter { EmbySubtitleLanguage.matches(language, it.languageKey) }
      if (group.isEmpty()) continue
      val best = best(group)
      trace += "按设备系统语言「$language」命中 ${group.size} 条，取 ${best.describe()}"
      return SubtitleSelection(
        chosen = best,
        reason = SubtitlePickReason.SYSTEM_LANGUAGE,
        detail = "按系统语言「${EmbySubtitleLanguage.label(EmbySubtitleLanguage.resolve(language))}」→ ${best.describe()}",
        trace = trace,
      )
    }

    // ── 6. 什么都不选 ──
    trace += "没有符合语言偏好的字幕，保持关闭"
    return SubtitleSelection.none("没有符合语言偏好的字幕，保持关闭", trace)
  }

  private fun languageAllowed(subtitle: SubtitleInfo, allowed: List<String>): Boolean {
    if (allowed.isEmpty()) return true
    return allowed.any { EmbySubtitleLanguage.matches(it, subtitle.languageKey) }
  }

  /**
   * 同语言候选内的排序：
   * 非听障 → Emby 默认 → 外挂文本优先（旁挂文件通常是用户/Emby 特意补的）→ 流索引小者。
   *
   * **为什么「非听障」排在「默认」之前**：Emby 把 SDH 轨标成 default 很常见（尤其该语言
   * 只有 SDH 时），若默认权重更高，用户明明有普通轨却总被塞一条带 `[door slams]` 的字幕。
   * 「默认」只用来在**同类**轨道里分胜负。
   *
   * 最后一级用 `streamIndex` 而不是原始顺序，是为了**结果稳定**：
   * 同一个媒体源两次返回的数组顺序理论上一致，但用索引兜底更保险，也让单测可预期。
   */
  private fun best(group: List<SubtitleInfo>): SubtitleInfo =
    group.minWith(
      compareBy<SubtitleInfo> { it.isHearingImpaired }
        // 同语言优先文本字幕：PGS / DVD 图像字幕虽然能挂，但不可检索、不可改样式，
        // 而且外部图像还需要额外下载。有文本轨时不该先选图像。
        .thenBy { it.sourceType == SubtitleSourceType.IMAGE_BASED }
        .thenByDescending { it.isDefault }
        .thenByDescending { it.canMountExternally }
        .thenBy { it.streamIndex },
    )
}
