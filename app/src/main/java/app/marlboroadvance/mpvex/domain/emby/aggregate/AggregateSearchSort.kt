package app.marlboroadvance.mpvex.domain.emby.aggregate

/**
 * 结果排序方式。
 *
 * ## 为什么是「客户端固定几种」而不是照搬媒体库的 11 项
 *
 * 媒体库页的排序是**服务端**排序（`/Items?SortBy=`），服务端知道全量数据、能翻页。
 * 聚合检索这边每台只取 [AggregateSearchLimits.PER_SERVER] 条，再跨台合并 ——
 * 在客户端重排一份「已经截过片的样本」，排出来的只是这份样本的顺序，
 * 把它包装成「按播放次数排序」会让人以为看到了全库排行榜。
 *
 * 所以这里只给**当前结果集能被可靠排序**的维度：名称、年份、体积、加入时间，
 * 全是条目自身字段，不依赖额外请求，也不暗示「全库口径」。
 *
 * ## 方向是固定的
 *
 * 每个维度一个自然方向（名称升序、年份 / 体积 / 加入时间降序），**不做正反切换**：
 * 聚合页的排序是「换个看法」，不是精细检索工具；加一个方向开关只会让人多点一次
 * 才能回到刚才那个顺序。要精细排序请进对应媒体库页。
 */
enum class AggregateSort(
  /** 菜单文案。与媒体库页用词区分开：「匹配优先」不是「相关度」——服务端没给分 */
  val label: String,
) {
  /**
   * 默认：匹配层级 → 类型权重 → 年份 → 台数 → 服务器序 → 名称。
   *
   * 规则见 [AggregateSearchRank.compareEntries]，同一关键词每次结果顺序完全一致。
   */
  MATCH("匹配优先"),

  /** 名称升序（忽略大小写；中文按 Unicode 码位，不做拼音） */
  NAME("名称"),

  /** 发行年份降序，无年份垫底 */
  YEAR("发行年份"),

  /** 体积降序（同一部片取各台最大的一份），取不到体积的垫底 */
  SIZE("文件体积"),

  /** 加入服务器时间降序（`DateCreated` 是 ISO 8601，字符串序即时间序） */
  ADDED("加入时间"),
  ;

  companion object {
    /** 偏好里存的是枚举名；读不到或改名了都退回 [MATCH] */
    fun fromName(raw: String?): AggregateSort =
      entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MATCH
  }
}

/** 排序执行。合并视图排「行」，分组视图排段内条目。 */
object AggregateSearchSort {
  /**
   * 合并后的行顺序。
   *
   * @param term 只有 [AggregateSort.MATCH] 会用到（匹配层级要看关键词）
   */
  fun sortEntries(
    entries: List<AggregateEntry>,
    sort: AggregateSort,
    term: String,
  ): List<AggregateEntry> =
    when (sort) {
      AggregateSort.MATCH -> entries.sortedWith(AggregateSearchRank.compareEntries(term))

      AggregateSort.NAME ->
        entries.sortedWith(
          compareBy(String.CASE_INSENSITIVE_ORDER) { entry -> entry.item.Name ?: "" },
        )

      AggregateSort.YEAR -> entries.sortedWith(descendingLong { entry -> entry.bestYear?.toLong() })

      AggregateSort.SIZE -> entries.sortedWith(descendingLong { entry -> entry.bestSize })

      AggregateSort.ADDED -> entries.sortedWith(descendingText { entry -> entry.bestCreated })
    }

  /**
   * 段内条目顺序（分组视图）。
   *
   * [AggregateSort.MATCH] 时用 [AggregateSearchRank.compareHits]，其余维度对单条直接可比。
   */
  fun sortHits(
    hits: List<ServerHit>,
    sort: AggregateSort,
    term: String,
  ): List<ServerHit> =
    when (sort) {
      AggregateSort.MATCH -> hits.sortedWith(AggregateSearchRank.compareHits(term, null))

      AggregateSort.NAME ->
        hits.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { hit -> hit.item.Name ?: "" })

      AggregateSort.YEAR -> hits.sortedWith(descendingLong { hit -> hit.item.ProductionYear?.toLong() })

      AggregateSort.SIZE -> hits.sortedWith(descendingLong { hit -> hit.sizeBytes })

      AggregateSort.ADDED -> hits.sortedWith(descendingText { hit -> hit.item.DateCreated })
    }

  // ─── 比较器 ───

  /** 降序；null 一律垫底（「不知道」不该排在「很小」前面） */
  private fun <T> descendingLong(selector: (T) -> Long?): Comparator<T> =
    Comparator { a, b ->
      val left = selector(a)
      val right = selector(b)
      when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> right.compareTo(left)
      }
    }

  /** 字符串降序（ISO 时间串）；null / 空白垫底，长度相同时按字典序 */
  private fun <T> descendingText(selector: (T) -> String?): Comparator<T> =
    Comparator { a, b ->
      val left = selector(a)?.takeIf { it.isNotBlank() }
      val right = selector(b)?.takeIf { it.isNotBlank() }
      when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> right.compareTo(left, ignoreCase = true)
      }
    }

  /**
   * 该行最好的年份：取各台最大值。
   *
   * 各台刮削进度不同时同一部片年份可能不一致（一方 2021、一方 2020），
   * 用最大值能让「新片」稳定排在前面，不会因为默认打开项落在哪台而抖动。
   */
  private val AggregateEntry.bestYear: Int?
    get() = hits.mapNotNull { it.item.ProductionYear }.maxOrNull()

  private val AggregateEntry.bestSize: Long?
    get() = hits.mapNotNull { it.sizeBytes }.maxOrNull()

  private val AggregateEntry.bestCreated: String?
    get() =
      hits.mapNotNull { it.item.DateCreated?.takeIf { value -> value.isNotBlank() } }.maxOrNull()
}
