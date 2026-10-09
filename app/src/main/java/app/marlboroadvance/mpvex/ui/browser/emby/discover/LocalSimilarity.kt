package app.marlboroadvance.mpvex.ui.browser.emby.discover

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlin.math.abs
import kotlin.math.exp

/**
 * 「相似推荐」用哪一套算法。
 *
 * 存的是枚举**常量名**（`PreferenceStore.getEnum` 存 `it.name`），
 * 所以**常量名发布后不能改** —— 改名等于让老用户的设置回退到默认值。
 */
enum class SimilarAlgorithm {
  /**
   * 走 Emby 服务端算法（`/Items/{Id}/Similar`、`/Items/{Id}/InstantMix`）。
   *
   * 优点是准（服务端能用上它自己索引的全量特征），缺点是：
   * - 服务端没刮削干净的库，它会**返回空**（`Similar` 依赖 `People` / `Genres` 之类的字段）
   * - 部分第三方兼容实现（不是官方 Emby）根本没实现这个端点，会 404
   */
  EMBY,

  /**
   * 走本地打分（[LocalSimilarity]）：拉一批候选，按类型 / 年代 / 评分 / 阵容重合度排序。
   *
   * 优点是**永远有结果**（只要库里还有别的条目），可预期；
   * 缺点是只能用它手上有的字段打分，比不上服务端的全量特征。
   */
  LOCAL,
  ;

  companion object {
    /** 取不到 / 手改坏了时的兜底。用服务端是多数场景下的期待值。 */
    val Default: SimilarAlgorithm = EMBY
  }
}

/**
 * 一条候选的打分明细。
 *
 * 分项暴露出来有两个用处：
 * 1. UI 可以在卡片上写「同类 · 同年代」这类**人话**理由，而不是干巴巴一个分数；
 * 2. 单测能逐项断言，而不是只能断言「A 排在 B 前面」。
 */
data class SimilarityBreakdown(
  val item: EmbyItem,
  /** 0~100 的总分，已按各权重加权求和 */
  val score: Double,
  /** 类型重合度贡献（0~1 × 权重） */
  val genrePart: Double,
  /** 年代接近度贡献 */
  val yearPart: Double,
  /** 阵容（演员/导演）重合度贡献 */
  val peoplePart: Double,
  /** 评分接近度贡献 */
  val ratingPart: Double,
  /** 同系列 / 同剧集的直接判重（会硬性剔除，不进分数） */
  val sameSeries: Boolean,
) {
  /**
   * 给用户看的一句话理由，按贡献从大到小挑一条。
   *
   * 只挑**权重最大的那一项**（而不是拼多项）—— 卡片角落就那么点位置，
   * 拼成「类型相同、年代相近、同样 8 分」反而像在凑字数。
   */
  fun reason(): String? {
    val best = maxOf(genrePart, yearPart, peoplePart, ratingPart)
    if (best <= 0.0) return null
    return when (best) {
      genrePart -> "同类题材"
      peoplePart -> "同班阵容"
      yearPart -> "年代相近"
      else -> "口碑接近"
    }
  }
}

/**
 * 本地相似度算法。
 *
 * ## 为什么要有它
 *
 * 服务端 `Similar` 不是每个部署都给：库没刮削、第三方兼容服务没实现该端点时它就是空的。
 * 本地这套只依赖条目自带的几项字段（类型 / 年份 / 评分 / 阵容），**必然能出结果**。
 *
 * ## 权重为什么这么分
 *
 * 经验值，没有理论依据，但每一条都能说出理由：
 * - **类型 0.45** — 观众说「像」的时候，八成指的是题材。权重最高。
 * - **阵容 0.25** — 同一批演员是极强的信号，但只在**都刮削了 People** 时才有效；
 *   没刮的库这一项全体为 0，会自动让位给类型，不会造成错排。
 * - **年代 0.20** — 「老片配老片」符合直觉，但对跨类型需求是负向的
 *   （看 1994 的动作片时推 1994 的喜剧片不如推 2019 的动作片），所以压到第三。
 * - **评分 0.10** — 权重最低。评分只是「质量」不是「相似度」，
 *   拿它当主要依据会把推荐变成「高分片 Top20」，那不是用户要的。
 *
 * ## 为什么不自己算 Jaccard 之外的复杂相似度
 *
 * 一是这套输入（无向量、无用户行为）撑不起复杂模型；二是每加一个特征就要重新调权重，
 * 收益不抵复杂度。宁可规则简单可解释 —— 每一项都能对用户说清「为什么推这个」。
 */
object LocalSimilarity {

  private const val WEIGHT_GENRE = 0.45
  private const val WEIGHT_PEOPLE = 0.25
  private const val WEIGHT_YEAR = 0.20
  private const val WEIGHT_RATING = 0.10

  /** 年份差在这个值以内算「同年」，再远就按指数衰减到 0 */
  private const val YEAR_DECAY_SCALE = 10.0

  /** 评分差在这个值以内算「同档」（0~10 分制） */
  private const val RATING_DECAY_SCALE = 3.0

  /**
   * 给 [seed] 的候选池打分排序。
   *
   * @param seed 种子条目（就是用户在详情页看的那部）
   * @param candidates 候选池。**不要求调用方先滤掉自己** —— [sameSeriesOf] 会兜住。
   * @param excludeIds 额外要排除的 Id（例如「已看过的不要」）
   * @param excludePlayed true 时剔除 `UserData.Played == true` 的条目
   * @param limit 最多返回几条；<= 0 表示不限
   *
   * 排序键：分数降序 → 分数**相同**时社区评分降序 → 社区评分也相同按年份降序 →
   * 再相同按 Id 升序。
   *
   * 最后两级排序不是装饰：**必须有一个全序**，否则两次调用（列表刷新前后）
   * 分数相同的候选相对顺序可能变，Compose 会把它们当成「同一条」复用，
   * 于是用户看到卡片内容莫名其妙换位。
   */
  fun rank(
    seed: EmbyItem,
    candidates: List<EmbyItem>,
    excludeIds: Set<String> = emptySet(),
    excludePlayed: Boolean = false,
    limit: Int = 0,
  ): List<SimilarityBreakdown> {
    val seedId = seed.Id
    val seedSeries = sameSeriesOf(seed)

    val scored = ArrayList<SimilarityBreakdown>(candidates.size)
    for (c in candidates) {
      val id = c.Id
      // Id 为 null 的条目没法做去重也没法被点开，直接丢
      if (id == null) continue
      if (seedId != null && id == seedId) continue
      if (id in excludeIds) continue
      if (excludePlayed && c.UserData?.Played == true) continue

      val sameSeries = seedSeries != null && sameSeriesOf(c) == seedSeries
      if (sameSeries) continue

      scored += score(seed, c)
    }

    scored.sortWith(
      compareByDescending<SimilarityBreakdown> { it.score }
        .thenByDescending { it.item.CommunityRating ?: 0.0 }
        .thenByDescending { it.item.ProductionYear ?: 0 }
        .thenBy { it.item.Id ?: "" },
    )
    return if (limit > 0) scored.take(limit) else scored
  }

  /**
   * 单条打分。抽成公开函数是为了让单测能直接验分项，不必先造一整个列表。
   */
  fun score(seed: EmbyItem, candidate: EmbyItem): SimilarityBreakdown {
    val genrePart = WEIGHT_GENRE * genreSimilarity(seed, candidate)
    val peoplePart = WEIGHT_PEOPLE * peopleSimilarity(seed, candidate)
    val yearPart = WEIGHT_YEAR * yearSimilarity(seed, candidate)
    val ratingPart = WEIGHT_RATING * ratingSimilarity(seed, candidate)
    return SimilarityBreakdown(
      item = candidate,
      score = genrePart + peoplePart + yearPart + ratingPart,
      genrePart = genrePart,
      yearPart = yearPart,
      peoplePart = peoplePart,
      ratingPart = ratingPart,
      sameSeries = false,
    )
  }

  /**
   * 类型重合度。
   *
   * 用 **Jaccard**（交集 / 并集）而不是「交集大小 / 种子类型数」：
   * 后者对「种子 5 个类型、候选只中 1 个」和「种子 1 个类型、候选也只中这 1 个」
   * 都给满分，明显不合理。
   *
   * 两边都没类型时返回 0（而不是 1）：「都没信息」不等于「很相似」，
   * 宁可少给这一项的权重，也不要凭空加分。
   */
  fun genreSimilarity(seed: EmbyItem, candidate: EmbyItem): Double {
    val a = seed.Genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val b = candidate.Genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.count { it in b }
    if (inter == 0) return 0.0
    val union = a.size + b.size - inter
    return inter.toDouble() / union
  }

  /**
   * 阵容重合度：共有演员 / 导演的人数，按**较小一方**归一。
   *
   * 用 min 而不是并集：一部电影常有 50 个演员，另一部只有 8 个，
   * 按并集算重合 1 人就只有 0.02，几乎等于没有信号；
   * 按较小方算则是 1/8，能真实反映「这一部基本就是那批人」。
   *
   * 只统计 `Type` 是 `Actor` / `Director` 的：Emby 的 People 里还有 `Writer` /
   * `Producer` / `GuestStar`，编剧重合的片子往往类型完全不同，
   * 算进去会把推荐带偏。
   */
  fun peopleSimilarity(seed: EmbyItem, candidate: EmbyItem): Double {
    val a = creditedPeopleIds(seed)
    val b = creditedPeopleIds(candidate)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.count { it in b }
    if (inter == 0) return 0.0
    return inter.toDouble() / minOf(a.size, b.size).toDouble()
  }

  /**
   * 年份接近度：同年满分，差 N 年按 [exp] 衰减。
   *
   * 有一方缺年份时返回 0 而不是「不扣分」——见 [genreSimilarity] 的同一理由。
   * 衰减而不是线性扣分：差 1 年和差 3 年差距不该和差 8 年和差 10 年一样大。
   */
  fun yearSimilarity(seed: EmbyItem, candidate: EmbyItem): Double {
    val a = seed.ProductionYear ?: return 0.0
    val b = candidate.ProductionYear ?: return 0.0
    val diff = abs(a - b).toDouble()
    if (diff == 0.0) return 1.0
    return exp(-diff / YEAR_DECAY_SCALE)
  }

  /**
   * 评分接近度：同分满分，差 N 分按 [exp] 衰减，差值 ≥ [RATING_DECAY_SCALE] 归零。
   *
   * 三种「没有评分」的情形要分开处理，这是本函数最容易写错的地方：
   *
   * | 种子 | 候选 | 结果 | 理由 |
   * |---|---|---|---|
   * | 8.0 | 8.0 | 1.0 | 都评分且相同，就是同分 |
   * | null | 8.0 | 0.036 | **一方缺**仍保留区分度，接近但不等于满分 |
   * | null | null | **0.0** | **双方都缺** ⇒ 什么信息都没有，不该给满分 |
   *
   * 最后一行是重点：早期版本让「双方都没评分」走进「差 0 分」分支拿到满分，
   * 结果是所有元数据缺失的条目白拿 0.1 分，被顶到有真实元数据的条目前面 ——
   * 而它恰恰是**最不可能值得推荐**的那些。这与 [genreSimilarity] /
   * [yearSimilarity] 的处理口径也就统一了：**都没信息 = 0，不是满分**。
   *
   * 中间那行（一方缺）不能也归零：种子没评分但候选有 8 分时，
   * 「候选是有口碑的」仍然是个弱信号，归零会白白浪费这一项权重。
   */
  fun ratingSimilarity(seed: EmbyItem, candidate: EmbyItem): Double {
    val a = seed.CommunityRating
    val b = candidate.CommunityRating
    if (a == null && b == null) return 0.0
    // 一方缺时按 0 分参与比较：它与任何真实评分的差都 ≥ 阈值，落到下一行的归零分支
    val diff = abs((a ?: 0.0) - (b ?: 0.0))
    if (diff == 0.0) return 1.0
    if (diff >= RATING_DECAY_SCALE) return 0.0
    return exp(-diff / RATING_DECAY_SCALE)
  }

  /**
   * 「同系列」的判据：剧集看 `SeriesId`，电影没有系列概念，用**类型完全相同**兜。
   *
   * ⚠️ 电影这里用「类型完全相同」是**故意从严**的：
   * 如果按「类型有交集」判，那两部同类型但毫无关系的电影会被当成同系列剔除，
   * 整块推荐就只剩跨类型的少数几部了。判严一点宁可漏掉一两部好的。
   *
   * @return 判据值；null 表示「无法判定」（种子本身缺这两个字段）
   */
  fun sameSeriesOf(item: EmbyItem): String? {
    item.SeriesId?.takeIf { it.isNotBlank() }?.let { return "series:$it" }
    val genres = item.Genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (genres.isEmpty()) return null
    return "genre:" + genres.sorted().joinToString("|")
  }

  /** 只取演员 / 导演的 Person Id（见 [peopleSimilarity] 的说明）。 */
  private fun creditedPeopleIds(item: EmbyItem): Set<String> {
    val people = item.People ?: return emptySet()
    return people.asSequence()
      .filter { it.Type == "Actor" || it.Type == "Director" }
      .mapNotNull { it.Id?.takeIf(String::isNotBlank) }
      .toSet()
  }
}

/**
 * 一条本地推荐的「种子 + 候选」。
 *
 * 单独抽出来是为了让「换一批」的逻辑能整体传给 ViewModel，
 * 而不用把 seed / offset 这些零散参数一路传下去。
 */
data class SimilarSeed(
  val seedItem: EmbyItem,
  /** 候选池的拉取起点，用于「换一批」偏移 */
  val offset: Int = 0,
)
