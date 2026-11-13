package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 字符串相似度集合（对应设计里的 SimilarityCalculator）。
 *
 * 全部是纯函数、无状态、无 I/O，方便在单测里对着具体样本钉住行为。
 *
 * 为什么四种算法都要：它们各自覆盖不同的差异类型，互相不可替代 ——
 * Jaro-Winkler 擅长「前缀相同、中段乱」；Levenshtein 擅长「少一个字母」；
 * Jaccard 只关心词集；N-gram 对语言无关、对分隔符差异最稳。
 */
object SimilarityCalculator {
  /**
   * Jaro-Winkler 相似度，0..1。
   *
   * 前缀加权系数取标准 0.1、前缀上限 4 —— 文件名常共享厂商前缀
   * （`CARIB`、`HEYZO`），前缀加权能让它们在「只差后缀」时明显更高。
   */
  fun jaroWinkler(a: String, b: String): Double {
    if (a == b) return if (a.isEmpty()) 0.0 else 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val jaro = jaro(a, b)
    if (jaro <= 0.0) return 0.0
    var prefix = 0
    val limit = minOf(4, minOf(a.length, b.length))
    while (prefix < limit && a[prefix] == b[prefix]) prefix++
    return (jaro + prefix * 0.1 * (1.0 - jaro)).coerceAtMost(1.0)
  }

  private fun jaro(a: String, b: String): Double {
    val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
    val aMatched = BooleanArray(a.length)
    val bMatched = BooleanArray(b.length)
    var matches = 0
    for (i in a.indices) {
      val start = (i - window).coerceAtLeast(0)
      val end = (i + window).coerceAtMost(b.length - 1)
      for (j in start..end) {
        if (bMatched[j] || a[i] != b[j]) continue
        aMatched[i] = true
        bMatched[j] = true
        matches++
        break
      }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var k = 0
    for (i in a.indices) {
      if (!aMatched[i]) continue
      while (!bMatched[k]) k++
      if (a[i] != b[k]) transpositions++
      k++
    }
    val m = matches.toDouble()
    return (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
  }

  /** 编辑距离，0..max(len)。 */
  fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[b.length]
  }

  /** 归一化编辑距离相似度，0..1。 */
  fun levenshteinRatio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 0.0
    val max = maxOf(a.length, b.length)
    if (max == 0) return 0.0
    return 1.0 - levenshtein(a, b).toDouble() / max
  }

  /** 词集合 Jaccard，0..1。任一方为空时返回 0（「没有可比内容」不是「完全相同」）。 */
  fun jaccard(a: Collection<String>, b: Collection<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val setA = a.toSet()
    val setB = b.toSet()
    if (setA.isEmpty() || setB.isEmpty()) return 0.0
    val inter = setA.count { it in setB }
    val union = setA.size + setB.size - inter
    return if (union <= 0) 0.0 else inter.toDouble() / union
  }

  /**
   * 字符 N-gram 相似度，0..1。
   *
   * @param n 取 2 或 3。短名（`N0907`）用 2，长标题用 3 更稳
   */
  fun ngram(a: String, b: String, n: Int): Double {
    if (n <= 0) return 0.0
    val gramsA = ngrams(a, n)
    val gramsB = ngrams(b, n)
    if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0
    val inter = gramsA.count { it in gramsB }
    val union = gramsA.size + gramsB.size - inter
    return if (union <= 0) 0.0 else inter.toDouble() / union
  }

  /** 取 2-gram 与 3-gram 里更高的那个：短名靠 2-gram，长名靠 3-gram。 */
  fun bestNgram(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return maxOf(ngram(a, b, 2), ngram(a, b, 3))
  }

  private fun ngrams(text: String, n: Int): List<String> {
    if (text.length < n) return listOf(text)
    return (0..text.length - n).map { text.substring(it, it + n) }
  }
}
