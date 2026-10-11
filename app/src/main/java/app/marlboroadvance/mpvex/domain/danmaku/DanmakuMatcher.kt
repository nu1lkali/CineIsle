package app.marlboroadvance.mpvex.domain.danmaku

import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient.Credentials

/**
 * 弹幕「搜番 → 选集 → 拉弹幕」的编排（无状态，纯函数式入口）。
 *
 * 放在 domain 而不是 ViewModel，是因为这一整段逻辑**不依赖 Application / Koin**：
 * host、剧名、是否自动匹配全是参数。所以它能直接跑 JVM 单测 ——
 * 而「p 字段解析」「集号匹配」「屏蔽词过滤」这三处恰好是最容易出静默错误的地方。
 */
object DanmakuMatcher {

  private const val TAG = "DanmakuMatcher"

  /** 匹配结果：命中的集 + 这一集的弹幕。 */
  data class Matched(
    val anime: DandanAnime?,
    val episode: DandanEpisode?,
    val comments: List<DanmakuComment>,
    /** 失败原因（用于给用户一句能看懂的话；成功时为 null）。 */
    val error: String? = null,
  )

  /**
   * 按剧名 + 集号自动匹配一集弹幕。
   *
   * @param title 剧集名（Emby 的 `Name`，通常形如「某番 - 03 [1080p]」）
   * @param episodeIndex 本片在播放队列里的序号（从 0 开始；用**序号**而不是名字里的数字，
   *   因为名字里的数字经常是「第二季」这种骗人的）
   */
  suspend fun autoMatch(host: String, title: String, episodeIndex: Int, credentials: Credentials): Matched {
    val keyword = normalizeTitle(title)
    if (keyword.isBlank()) {
      return Matched(null, null, emptyList(), "片名里没有可用的关键词")
    }
    val animes = DanmakuClient.searchAnime(host, keyword, credentials)
    if (animes.isEmpty()) {
      //⚠️ 搜索失败有两个完全不同的原因（没网 vs 认证没过），提示必须分开：
      //    都写成「没搜到」的话，用户会去反复换关键词，而真正的问题是凭证。
      return Matched(null, null, emptyList(), DanmakuClient.lastError ?: "没搜到「$keyword」")
    }
    // 第一条命中即采用：搜索是按相关度排的，用户要的是「最像的那部」。
    val anime = animes.first()
    val episodes = anime.episodes.ifEmpty {
      DanmakuClient.getEpisodes(host, anime.animeId, credentials)
    }
    if (episodes.isEmpty()) {
      return Matched(anime, null, emptyList(), "「${anime.animeTitle}」没有分集信息")
    }
    val episode = pickEpisode(episodes, episodeIndex, title)
      ?: return Matched(anime, null, emptyList(), "「${anime.animeTitle}」里找不到对应的集")

    val comments = fetch(host, episode, credentials)
    return Matched(anime, episode, comments, if (comments.isEmpty()) "这一集没有弹幕" else null)
  }

  /** 手动搜番（用户在弹幕面板里搜片名）。 */
  suspend fun search(host: String, keyword: String, credentials: Credentials): List<DandanAnime> =
    DanmakuClient.searchAnime(host, keyword, credentials)

  /** 拿指定集的弹幕（手动匹配用）。 */
  suspend fun fetchByEpisode(host: String, episodeId: Long, credentials: Credentials): List<DanmakuComment> =
    DanmakuCommentFilter.apply(DanmakuClient.getComments(host, episodeId, credentials), emptyList())

  private suspend fun fetch(host: String, episode: DandanEpisode, credentials: Credentials): List<DanmakuComment> =
    DanmakuCommentFilter.apply(DanmakuClient.getComments(host, episode.episodeId, credentials), emptyList())

  /**
   * 从 Emby 片名里抽出可用于搜索的剧名。
   *
   * 「某番 第03话 [1080p][简体内嵌]」→ 「某番」。
   * 做法是从**第一个分隔符**开始截断，而不是逐个剥离后缀 —— 后缀组合太多
   *（`[1080p]`、`@字幕组`、`- 第2季`…），逐个剥离必然漏。
   */
  fun normalizeTitle(title: String): String {
    val t = title.trim()
    if (t.isEmpty()) return ""
    val cut = t.indexOfFirst { it == '-' || it == '–' || it == '—' || it == '：' || it == ':' || it == '[' || it == '第' }
    val head = if (cut > 0) t.substring(0, cut) else t
    return head.trim().trim('-', '–', '—', ' ', '：', ':').ifBlank { t }
  }

  /**
   * 在分集列表里挑对应的一集。
   *
   * 两级匹配：先信 `episodeNumber`（服务端给的权威集号），再退到标题里找数字。
   * 都不中就取**序号落在范围内**的那一集（列表通常就是按集号排的）。
   */
  fun pickEpisode(episodes: List<DandanEpisode>, episodeIndex: Int, title: String = ""): DandanEpisode? {
    if (episodes.isEmpty()) return null
    // 播放队列序号是 1-based 的集号（把「第 0 集」这种少数情况也兜住）。
    val wanted = episodeIndex + 1

    episodes.firstOrNull { it.episodeNumber?.trim()?.toIntOrNull() == wanted }?.let { return it }

    // 标题里的集号：只认「第N集 / 第N话 / Ep.N / N」这些明确形态。
    titleEpNumber(title)?.let { n ->
      episodes.firstOrNull { it.episodeNumber?.trim()?.toIntOrNull() == n }?.let { return it }
    }

    return episodes.getOrNull(episodeIndex)
  }

  /** 从片名里抠集号，抠不出返回 null。 */
  fun titleEpNumber(title: String): Int? {
    val patterns = listOf(
      Regex("第\\s*(\\d{1,4})\\s*[集话話期]"),
      Regex("[Ee][Pp]\\s*(\\d{1,4})"),
      Regex("""[\s.\-]\s?(\d{1,3})\s*(?:v\d)?\s*$"""),
    )
    for (p in patterns) {
      val m = p.find(title) ?: continue
      val n = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
      if (n in 0..9999) return n
    }
    return null
  }

  /**
   * 对**已解析成领域模型**的弹幕套屏蔽词。
   *
   * 为什么需要这一步：自动匹配那路在 [DanmakuClient] 之后就已经把 `p` 解好了，
   * 直接再滤一遍即可，不必回到原始响应重解。
   */
  fun applyBlockWords(comments: List<DanmakuComment>, blockWords: List<String>): List<DanmakuComment> {
    if (comments.isEmpty() || blockWords.isEmpty()) return comments
    val words = blockWords.map { it.trim() }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return comments
    return comments.filter { c -> words.none { c.text.contains(it, ignoreCase = true) } }
  }
}

/**
 * 原始响应 → 领域模型，并套上屏蔽词过滤。
 *
 * 单独成对象是为了让「过滤」这一步可单测：屏蔽词是用户自己写的字符串，
 * 边界（空词、大小写、空白）恰恰是最容易写错的地方。
 */
object DanmakuCommentFilter {

  /**
   * @param raw dandanplay 的原始条目
   * @param blockWords 屏蔽词列表（**已切分好**的词，不要在这里切）
   */
  fun apply(raw: List<DandanComment>, blockWords: List<String>): List<DanmakuComment> {
    if (raw.isEmpty()) return emptyList()
    // ⚠️ 先切词再判空：用户很容易在屏蔽词框里留下 `空格,逗号` 这种空项，
    // 而「空词命中一切」会让整屏弹幕消失 —— 这是最难排查的那种 bug。
    val words = blockWords.map { it.trim() }.filter { it.isNotEmpty() }
    val lowerWords = words.map { it.lowercase() }

    val out = ArrayList<DanmakuComment>(raw.size)
    for (r in raw) {
      val text = r.m.trim()
      if (text.isEmpty()) continue
      if (lowerWords.isNotEmpty()) {
        val lower = text.lowercase()
        if (lowerWords.any { lower.contains(it) }) continue
      }
      val parsed = DanmakuPStrings.parse(r.p) ?: continue
      out += DanmakuComment(
        timeSec = parsed.timeSec,
        mode = parsed.mode,
        textSize = parsed.textSize,
        colorRgb = parsed.color,
        text = text,
      )
    }
    // 库里 Danmakus 用 TreeSet 按时间排序，自己先排一遍可以省掉库的重复整理。
    out.sortBy { it.timeSec }
    return out
  }
}