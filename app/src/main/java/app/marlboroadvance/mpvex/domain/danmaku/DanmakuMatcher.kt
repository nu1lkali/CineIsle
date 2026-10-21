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
  suspend fun autoMatch(
    host: String,
    title: String,
    episodeIndex: Int,
    credentials: Credentials,
    /** 合并关联剧集弹幕（Yamby 的 `danmaku_with_related`），提高密度。 */
    withRelated: Boolean = false,
  ): Matched {
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
    // ⚠️ 不能直接 `animes.first()`：同一部番常有真人版 / 剧场版 / 第二季 / 不同版本同时命中，
    //    服务端只按关键词相关度排，会把「真人版」排在正片前面。改为打分排序（[rankAnimes]），
    //    同分时保留服务端原顺序（稳定排序）。
    val anime = rankAnimes(keyword, animes).first()
    val episodes = anime.episodes.ifEmpty {
      DanmakuClient.getEpisodes(host, anime.animeId, credentials)
    }
    if (episodes.isEmpty()) {
      return Matched(anime, null, emptyList(), "「${anime.animeTitle}」没有分集信息")
    }
    val episode = pickEpisode(episodes, episodeIndex, title)
      ?: return Matched(anime, null, emptyList(), "「${anime.animeTitle}」里找不到对应的集")

    val comments = fetch(host, episode, credentials, withRelated)
    return Matched(anime, episode, comments, if (comments.isEmpty()) "这一集没有弹幕" else null)
  }

  /** 手动搜番（用户在弹幕面板里搜片名）。返回**已按 [rankAnimes] 排序**的候选，第一条即推荐项。 */
  suspend fun search(host: String, keyword: String, credentials: Credentials): List<DandanAnime> =
    rankAnimes(keyword, DanmakuClient.searchAnime(host, keyword, credentials))

  /**
   * 给搜索结果打分并排序（分数高的在前；同分保留服务端原顺序 —— `sortedWith` 是稳定排序）。
   *
   * ## 为什么需要打分，而不是信任返回顺序
   *
   * 「进击的巨人」这类片名搜出来时，真人版 `進撃の巨人 ATTACK ON TITAN` 会排在
   * TV 动画前面 —— 服务端按「关键词相关度」排，真人版标题更短、更"贴合"。
   * 用户要的是**正在看的这一部**，判据应该是：
   *
   * | 信号 | 分 | 理由 |
   * |---|---|---|
   * | 标题完全等于关键词 | +40 | 正主 |
   * | 标题**以**关键词开头 | +25 | 「某番 第二季」 |
   * | 标题包含关键词 | +10 | 基本命中 |
   * | 类型 tvseries | +20 | dandanplay 正片类型 |
   * | 类型 movie / ova | −10 | 剧场版 / OVA 是补充内容 |
   * | 标题含「真人 / 実写 / 剧场版 / 映画」 | −35 | 误匹配的头号来源 |
   * | 集数 | +min(集数/10, 5) | 正片集数多；短篇 SP 不抢位 |
   *
   * 权重刻意让「完全等于」(+40) 大过「tvseries + 真人版」(20−35=−15)，这样
   * 即使真人版被服务端标成 tvseries，也压不过正片。
   *
   * ⚠️ 这里**不碰**「第二季 vs 第一季」：用户可能就是在看第二季，
   * 季号该由 [autoMatch] 的分集匹配去判断，不该在这一层就判死。
   */
  fun rankAnimes(keyword: String, animes: List<DandanAnime>): List<DandanAnime> {
    if (animes.size <= 1) return animes
    val kw = keyword.trim().lowercase()
    if (kw.isEmpty()) return animes

    fun score(a: DandanAnime): Int {
      val lower = a.animeTitle.trim().lowercase()
      var s =
        when {
          lower == kw -> 40
          lower.startsWith(kw) -> 25
          lower.contains(kw) -> 10
          else -> 0
        }

      val type = (a.typeDescription ?: a.type ?: "").lowercase()
      s +=
        when {
          type.contains("tv") || type.contains("series") -> 20
          type.contains("movie") || type.contains("ova") || type.contains("剧场") -> -10
          else -> 0
        }

      if (LIVE_ACTION_HINTS.any { lower.contains(it) }) s -= 35

      s += ((a.episodeCount ?: 0) / 10).coerceIn(0, 5)
      return s
    }

    return animes.sortedByDescending { score(it) }
  }

  /** 「不是用户在看的那部正片」的信号词：真人版 / 剧场版 / 电影。 */
  private val LIVE_ACTION_HINTS =
    listOf("真人", "実写", "实写", "剧场版", "劇場版", "电影", "映画", "电视剧版", "ドラマ")

  /** 拿指定集的弹幕（手动匹配用）。 */
  suspend fun fetchByEpisode(
    host: String,
    episodeId: Long,
    credentials: Credentials,
    withRelated: Boolean = false,
  ): List<DanmakuComment> =
    DanmakuCommentFilter.apply(
      DanmakuClient.getComments(host, episodeId, credentials, withRelated),
      emptyList(),
    )

  private suspend fun fetch(
    host: String,
    episode: DandanEpisode,
    credentials: Credentials,
    withRelated: Boolean = false,
  ): List<DanmakuComment> =
    DanmakuCommentFilter.apply(
      DanmakuClient.getComments(host, episode.episodeId, credentials, withRelated),
      emptyList(),
    )

  /**
   * 从 Emby 片名里抽出可用于搜索的剧名。
   *
   * ## 三步，顺序不能换
   *
   * 1. **先剥「季集标记」**：`S01E03` / `S1` / `第N集` / `EP03` 这类是可判定的结构，
   *    切掉从标记开始往后的全部内容。
   * 2. **方括号 / 冒号**：`[1080p]`、`片名：副标题` 一律从分隔符截断。
   * 3. **短横线**：只有右侧「像补充信息」时才截。
   *
   * ## ⚠️ 为什么不再无条件按 `-` 截断
   *
   * 老实现取第一个 `-` 截，`Re:Zero - 第03话` 是对的，但
   * `Spider-Man 第3集` 会被截成 `Spider`、`Fate/stay night - 05` 也有同样风险。
   * 现在的判据是分隔符**右侧像不像压制组的尾巴**（纯数字 / 画质字样 / 方括号 / `第N话`）：
   * 是 ⇒ 截；还能读出一个完整词 ⇒ 认为 `-` 属于剧名本身，不截。
   *
   * 宁可多带一个词去搜（dandanplay 按相关度排序，多带词反而更准），
   * 也不能把剧名截成半截 —— 截错等于搜到另一部番，弹幕会整体跑偏。
   */
  fun normalizeTitle(title: String): String {
    var t = title.trim()
    if (t.isEmpty()) return ""

    // ① 剥季集标记（连同它前面的空白 / 连接符）
    SEASON_EPISODE_MARK.find(t)?.let { m ->
      val head = t.substring(0, m.range.first).trim().trim('-', '–', '—', ' ', '：', ':')
      if (head.isNotEmpty()) t = head
    }

    // ② 方括号：`[1080p]` `[字幕组]` 从第一个 `[` 截断
    t.indexOf('[').takeIf { it > 0 }?.let { t = t.substring(0, it).trim() }

    // ③ 冒号：`片名：副标题` 截断；`Re:Zero` 这种「全半角冒号紧贴字母」的是番名本身，不截。
    // ⚠️ 判据是「冒号类型 + 紧邻字符」，不是「后面有没有空格」：
    //   中文副标题写法通常是 `片名：副标题`（**全角冒号、后面不带空格**），
    //   只认「冒号后紧跟空格」的话这一类永远截不掉，会把副标题一起拿去搜。
    //   而 `Re:Zero`、`Fate:Grand Order` 是**半角**冒号夹在两个拉丁字母之间 —— 这是番名。
    val colon = t.indexOfFirst { it == '：' || it == ':' }
    if (colon > 0) {
      val mark = t[colon]
      val next = if (colon + 1 < t.length) t[colon + 1] else ' '
      val compactLatin = mark == ':' && next.isLetter() && t[colon - 1].isLetter() &&
        next.code < 0x2E80 && t[colon - 1].code < 0x2E80
      if (!compactLatin) t = t.substring(0, colon).trim()
    }

    // ④ 短横线：只有右侧像补充信息才截（见 KDoc）
    val dash = t.indexOfFirst { it == '–' || it == '—' || it == '-' }
    if (dash > 0) {
      val right = t.substring(dash + 1).trim()
      if (right.isBlank() || looksLikeSuffix(right)) t = t.substring(0, dash).trim()
    }

    return t.trim('-', '–', '—', ' ', '：', ':').ifBlank { title.trim() }
  }

  /**
   * `S01E03` / `S1` / `第N集` / `EP03` / `E03` 这类「季集标记」。
   *
   * ⚠️ 三条容易踩的边界，全部是「别把正常番名当成集号」：
   * 1. `S\d+` 后面必须跟 `E\d+` 或**结尾 / 分隔符** —— 否则会误伤
   *    `Steins;Gate`、`Sword Art Online` 这种以 S 开头的正常番名。
   * 2. `E` 分支**必须**紧跟数字（`E03` / `EP03`），且数字后面要么结尾、要么是分隔符 ——
   *    `E4` 是集号；`Endless`、`EXTRA` 后面不是数字，`E4K` 后面是字母（无边界）⇒ 都不误判。
   * 3. 整条正则要求标记**前面有边界**（行首 / 空白 / `.` `_` `-`），
   *    所以 `GATE0`、`TSUBASA1` 这种「字母直接连数字」不会被切开。
   * 4. 季号 `S\d+` 单独出现时（无 E）必须**自成一段**（前后都是边界）——
   *    `Show S2` 会截成 `Show`，而 `Show S2E3` 走第一条分支。
   */
  private val SEASON_EPISODE_MARK =
    Regex(
      """(?:^|[\s.\-_])(?:S\d{1,2}\s*[Ee]\d{1,4}|S\d{1,2}(?=$|[\s.\-_])|第\s*\d{1,4}\s*[集话話期]|[Ee][Pp]?\s*\d{1,4}(?=$|[\s.\-_]))""",
      RegexOption.IGNORE_CASE,
    )

  /**
   * 短横线右侧「像不像补充信息」。
   *
   * 是：纯数字、版本号 `v2`、方括号、`第N话`、画质 / 字幕组字样 —— 删掉才搜得到正主。
   * 否：还能读出一个完整词（`Man`、`stay night`）—— 那多半是剧名的一部分。
   */
  private fun looksLikeSuffix(s: String): Boolean {
    val v = s.trim()
    if (v.isEmpty()) return true
    if (v.startsWith("[")) return true
    if (v.matches(Regex("""\d{1,4}(\.\d+)?"""))) return true
    if (v.matches(Regex("""[vV]\d{1,2}"""))) return true
    if (v.contains(Regex("""(?i)^(第\s*\d+\s*[集话話期]|[Ee][Pp]?\s*\d+)"""))) return true
    if (QUALITY_WORDS.any { v.contains(it, ignoreCase = true) }) return true
    return false
  }

  private val QUALITY_WORDS =
    listOf(
      "1080", "720", "2160", "4k", "8k", "hdr", "web", "webdl", "web-dl", "bluray", "bd",
      "hevc", "avc", "x264", "x265", "h264", "h265", "aac", "flac", "简", "繁", "繁体",
      "简体", "内嵌", "外挂", "中字", "字幕", "修复", "合集", "完结",
    )

  /**
   * 在分集列表里挑对应的一集。
   *
   * 三级匹配：
   * 1. `episodeNumber` 数值等于目标集号（服务端给的权威集号）；
   * 2. 标题里的集号（「第N集 / Ep.N」）；
   * 3. 序号落在范围内（列表通常按集号排）。
   *
   * @param episodeIndex 集号，**0-based**（由调用方保证是真实集号而不是队列下标）
   * @param title 片名（用于兜底抠集号）
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