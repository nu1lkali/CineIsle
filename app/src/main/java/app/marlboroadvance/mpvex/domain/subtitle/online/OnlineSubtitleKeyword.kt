package app.marlboroadvance.mpvex.domain.subtitle.online

import app.marlboroadvance.mpvex.ui.player.subtitle.smart.TitleFacts

/**
 * 搜索关键词生成。
 *
 * ## 为什么不能直接把文件名丢给站点
 *
 * 真实库里的名字长这样：`www.98t.la@n0907_iris2.(mp4).strm`。
 * 整串提交给字幕站，命中率接近 0 —— 站点只认 `n0907`。
 * 所以优先级是「编号 > 清理后的标题 > 媒体标题 > 原串」，与需求 3.1 一致。
 *
 * ## 复用而不是另写
 *
 * 番号提取、站点前缀剥离、发布标记清理这一整套已经在
 * [TitleFacts]（→ `FilenameNormalizer` + `NoiseCleaner` + `IdentifierExtractor`）里了，
 * 这里只做**选谁当关键词**的决策，不再重复实现解析。
 *
 * ⚠️ 本类只产出「建议值」，用户可以在面板里改。手改之后不要再用自动值覆盖它
 * （面板里靠「用户是否编辑过」来判断）。
 */
object OnlineSubtitleKeyword {
  /** 关键词上限：站点按 GET 参数搜，过长的串多半直接空结果。 */
  private const val MAX_LENGTH = 80

  /**
   * 生成建议关键词。
   *
   * @param rawTitle 原始文件名（含扩展名，播放页的 `force-media-title` 或 Emby 的 Path 末段）
   * @param embyTitle Emby 的展示标题（`Name`）；优先于 [rawTitle] 的退化处理
   * @return 关键词与它的来源说明；两个入参都为空时返回空串 + 「手动输入」
   */
  fun suggest(rawTitle: String?, embyTitle: String? = null): SubtitleKeywordHint {
    val raw = rawTitle?.trim().orEmpty()
    val title = embyTitle?.trim().orEmpty()

    if (raw.isNotBlank()) {
      val facts = runCatching { TitleFacts.of(raw) }.getOrNull()
      if (facts != null) {
        // ① 番号：置信度最高的那个。`n0907` 这种裸尾号置信度低（0.35），
        //    但它仍然是「比整串文件名更可能搜到东西」的关键词，所以只要有就用。
        val identifier = facts.identifiers.firstOrNull()?.raw?.trim()
        if (!identifier.isNullOrBlank()) {
          return SubtitleKeywordHint(
            keyword = identifier.take(MAX_LENGTH),
            origin = "影片编号",
          )
        }
        // ② 清理后的标题（站点前缀 / 分辨率 / 语言标记都已剥掉）
        val cleaned = facts.cleanedTitle.trim().ifBlank { facts.cleaned.trim() }
        if (cleaned.isNotBlank()) {
          return SubtitleKeywordHint(
            keyword = cleaned.take(MAX_LENGTH),
            origin = "清理后的标题",
            fallback = true,
          )
        }
        // ③ 清洗后什么都不剩（纯数字 / 纯符号）⇒ 退回文件名主体
        val stem = raw.substringBeforeLast('.').trim()
        if (stem.isNotBlank() && stem != raw) {
          return SubtitleKeywordHint(keyword = stem.take(MAX_LENGTH), origin = "文件名", fallback = true)
        }
        if (stem.isNotBlank()) {
          return SubtitleKeywordHint(keyword = stem.take(MAX_LENGTH), origin = "文件名", fallback = true)
        }
      }
    }

    // ④ Emby 标题
    if (title.isNotBlank()) {
      return SubtitleKeywordHint(keyword = title.take(MAX_LENGTH), origin = "媒体标题", fallback = true)
    }

    return SubtitleKeywordHint(keyword = "", origin = "手动输入", fallback = true)
  }

  /** 用户手输了关键词：只做基础清洗（去空白、截断），不改写用户意图。 */
  fun sanitizeUserInput(input: String): String =
    input.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
}
