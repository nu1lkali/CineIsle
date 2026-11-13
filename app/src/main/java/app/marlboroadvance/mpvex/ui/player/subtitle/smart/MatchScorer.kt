package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 一条候选在各特征上的得分，范围都是 0..1；`null` 表示**该特征没有可比内容**。
 *
 * 「没有内容」与「内容不一致」必须分开：片名是中文而字幕名是罗马字，
 * 标题相似度低是正常的；而一部片子根本没有年份信息，就不该因为「年份对不上」扣分。
 * 归一化时只统计非 null 的特征，权重按实际可用的部分重新分配。
 */
data class FeatureScores(
  /** 编号特征（权重 45%） */
  val code: Double?,
  /** 标题整体相似度（权重 20%） */
  val title: Double?,
  /** 标题词 Jaccard（权重 15%） */
  val token: Double?,
  /** 字符 N-gram（权重 10%） */
  val ngram: Double?,
  /** 辅助信息：版本标记、CRC、年份（权重 10%） */
  val meta: Double?,
)

/**
 * 多特征综合评分（对应设计里的 MatchScorer）。
 *
 * ## 权重
 *
 * 编号 45 / 标题 20 / 词 15 / N-gram 10 / 辅助 10。编号占将近一半，
 * 因为这个场景里「是不是同一部作品」几乎等价于「编号是不是同一个」；
 * 文本相似度只用来在编号一致时区分版本，或在没有编号时给出一个不敢自动采用的低分。
 *
 * ## 动态归一化
 *
 * 缺项时把该项权重整个剔除，剩余权重归一化到 100。注意这会让「只有标题全中」
 * 也能拿到 100 分 —— 所以真正限制它的不是权重，而是
 * [ConflictDetector] 给出的分数上限（cap）。权重负责排序，cap 负责「不许自动」。
 */
object MatchScorer {
  const val WEIGHT_CODE = 45.0
  const val WEIGHT_TITLE = 20.0
  const val WEIGHT_TOKEN = 15.0
  const val WEIGHT_NGRAM = 10.0
  const val WEIGHT_META = 10.0

  /** 未封顶时的满分。 */
  const val FULL_SCORE = 100.0

  fun score(features: FeatureScores): Double {
    var weighted = 0.0
    var total = 0.0

    fun add(weight: Double, value: Double?) {
      if (value == null) return
      weighted += weight * value.coerceIn(0.0, 1.0)
      total += weight
    }

    add(WEIGHT_CODE, features.code)
    add(WEIGHT_TITLE, features.title)
    add(WEIGHT_TOKEN, features.token)
    add(WEIGHT_NGRAM, features.ngram)
    add(WEIGHT_META, features.meta)

    if (total <= 0.0) return 0.0
    return (weighted / total * FULL_SCORE).coerceIn(0.0, FULL_SCORE)
  }

  /**
   * 文本证据是否「几乎逐字相同」。
   *
   * 这是**唯一**允许绕过编号证据自动采用的一条路，门槛刻意定得很高：
   * 标题本身（压平分隔符后）相似度 ≥ 0.95 且字符 N-gram ≥ 0.90。
   * 判据全部来自字符层面，所以对语言、分词、标点写法都不敏感。
   *
   * 反例就是这条门槛存在的理由：`Carib War` 与 `Caribbean Pirates` 的 Jaro-Winkler
   * 能到 0.7，词重合度也有一半，但字符 N-gram 只有 0.35 —— 靠文本永远不该挂上去。
   */
  fun isStrongText(features: FeatureScores): Boolean {
    val title = features.title ?: return false
    val ngram = features.ngram ?: return false
    return title >= 0.95 && ngram >= 0.90
  }
}
