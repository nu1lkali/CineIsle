package app.marlboroadvance.mpvex.domain.subtitle.online

/**
 * 在线字幕来源。
 *
 * ⚠️ 与 Emby 服务端字幕**不是同一类资源**：
 * Emby 的字幕由服务端识别、有自己的 `TrackIndex` 与下载端点；这里的来源是
 * 互联网站点，只能拿到一个 HTTP 文件。两者在界面上可以并列展示，但绝不能把
 * 下载来的文件伪装成 Emby 轨道（不改 `TrackIndex`、不调服务端入库接口）。
 *
 * [EMBY] / [LOCAL] 只用于「统一字幕列表」这一层展示归因，不参与这里的搜索。
 */
enum class OnlineSubtitleSource(
  /** 稳定的持久化键；改名会让已下载记录失联，别动。 */
  val key: String,
  /** 用户可见名称。 */
  val label: String,
) {
  THUNDER("thunder", "迅雷"),
  SUBTITLE_CAT("subtitle_cat", "SubtitleCat"),
  ;

  companion object {
    /** 设置里默认开启的来源。两个都开：任一个挂了另一个还能出结果。 */
    val DEFAULT_ENABLED: Set<OnlineSubtitleSource> = setOf(THUNDER, SUBTITLE_CAT)

    fun fromKey(key: String?): OnlineSubtitleSource? =
      entries.firstOrNull { it.key == key }
  }
}

/**
 * 匹配等级。
 *
 * 刻意不用「一个裸分数」：用户看不懂 78 分，但看得懂「较高匹配 · 编号一致」。
 * 分档阈值见 [of]。
 */
enum class SubtitleMatchLevel(
  val label: String,
  /** 列表里那一行颜色倾向（true = 值得优先看）。 */
  val strong: Boolean,
) {
  HIGH("高匹配", true),
  GOOD("较高匹配", true),
  FAIR("一般匹配", false),
  LOW("低匹配", false),
  UNKNOWN("未评估", false),
  ;

  companion object {
    /**
     * 分数 → 等级。
     *
     * 阈值与 `MatchDecisionEngine` 的自动挂载门槛对齐：≥85 才算「值得直接信」，
     * 70~84 是「大概率对，但要让用户看一眼」，50~69 只能说「名字像」。
     */
    fun of(score: Int): SubtitleMatchLevel =
      when {
        score >= 85 -> HIGH
        score >= 70 -> GOOD
        score >= 50 -> FAIR
        else -> LOW
      }
  }
}

/**
 * 一条在线字幕候选。
 *
 * 字段按「来源确实给得出」来定：迅雷没有文件大小、SubtitleCat 没有语言标注，
 * 拿不到的一律留 null，界面显示「未知」——**绝不编造**。
 *
 * @property id 稳定标识。用下载地址本身（同一地址必然同一条），用于去重与下载态跟踪。
 * @property fileName 落盘时优先用的名字；来源只给标题时由 [title] 兜底。
 * @property format `srt` / `ass` / `ssa` / `vtt`；来源没写扩展名时为 null（交给内容嗅探）。
 * @property language 已确认的语言（如 `zh-CN`）；无法确认时为 null。
 * @property detailUrl 详情页（仅用于展示来源与排错，不参与下载）。
 * @property matchScore 0..100；0 且 [matchLevel] 为 UNKNOWN 表示没有可比较的媒体信息。
 */
data class OnlineSubtitle(
  val id: String,
  val source: OnlineSubtitleSource,
  val title: String,
  val fileName: String,
  val format: String?,
  val language: String?,
  val downloadUrl: String,
  val detailUrl: String? = null,
  val fileSizeBytes: Long? = null,
  val matchScore: Int = 0,
  val matchLevel: SubtitleMatchLevel = SubtitleMatchLevel.UNKNOWN,
  /** 一句话解释「为什么是这个匹配等级」，来自既有匹配器的 matchReason。 */
  val matchReason: String? = null,
) {
  /** 界面上的副标题行：`迅雷 · SRT · 中文（简体）· 较高匹配`。 */
  val summary: String
    get() = buildList {
      add(source.label)
      add(format?.uppercase() ?: "格式未知")
      add(language?.let { languageLabel(it) } ?: "语言未知")
    }.joinToString(" · ")

  companion object {
    /** ISO / BCP-47 语言码 → 中文名。认不出就原样返回，不猜。 */
    fun languageLabel(code: String): String =
      when (code.lowercase().replace('_', '-')) {
        "zh", "zh-cn", "zh-hans", "chs", "chi", "cn" -> "中文（简体）"
        "zh-tw", "zh-hk", "zh-hant", "cht" -> "中文（繁体）"
        "en", "eng" -> "英语"
        "ja", "jpn" -> "日语"
        "ko", "kor" -> "韩语"
        else -> code
      }
  }
}

/** 单个来源的搜索结局。失败不影响其它来源，所以收在结果里而不是抛异常。 */
data class ProviderOutcome(
  val source: OnlineSubtitleSource,
  val results: List<OnlineSubtitle> = emptyList(),
  /** null = 成功（哪怕 0 条）。 */
  val error: String? = null,
  val elapsedMs: Long = 0,
)

/**
 * 一次搜索的完整结局。
 *
 * @property keyword 实际提交给来源的关键词（用户可能手改过），界面要回显它，
 *   否则「搜出来一堆不相关」时用户无法判断是不是关键词错了。
 * @property usedFallbackKeyword 是否用了「媒体标题」这类退化关键词。
 */
data class SubtitleSearchOutcome(
  val keyword: String,
  val items: List<OnlineSubtitle> = emptyList(),
  val outcomes: List<ProviderOutcome> = emptyList(),
  val usedFallbackKeyword: Boolean = false,
) {
  val isEmpty: Boolean get() = items.isEmpty()

  /** 全部来源都失败（与「都成功但没结果」必须区分，界面文案不同）。 */
  val allFailed: Boolean
    get() = outcomes.isNotEmpty() && outcomes.all { it.error != null }

  /** 失败来源的一句话摘要，用于面板底部提示。 */
  val failureSummary: String?
    get() =
      outcomes
        .filter { it.error != null }
        .joinToString("；") { "${it.source.label}：${it.error}" }
        .takeIf { it.isNotBlank() }
}

/** 搜索关键词及其来源依据，用于在界面解释「为什么用这个词搜」。 */
data class SubtitleKeywordHint(
  val keyword: String,
  /** 人话说明：`影片编号` / `清理后的标题` / `手动输入`。 */
  val origin: String,
  val fallback: Boolean = false,
)
