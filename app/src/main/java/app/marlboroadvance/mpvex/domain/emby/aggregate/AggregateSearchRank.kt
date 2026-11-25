package app.marlboroadvance.mpvex.domain.emby.aggregate

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import java.util.Locale

/**
 * 聚合结果的排序。
 *
 * ## 为什么不「按相关度」
 *
 * Emby 的 `/Items?SearchTerm` **不返回 score**，客户端拿不到任何相关度数值。
 * 硬编一个「相关度」只会让顺序看起来有道理但不可解释。这里给的是**确定性**顺序：
 * 同一关键词、同一批结果，每次排出来的次序完全一致，用户能建立稳定预期。
 *
 * ## 两级比较
 *
 * - [compareEntries]：合并后每行之间的顺序（跨服务器）；
 * - [compareHits]：同一部片的多份副本之间的顺序，决定「默认打开哪台」。
 */
object AggregateSearchRank {
  /**
   * 合并行的排序。
   *
   * ```text
   * 1. 匹配层级   名称完全相等 → 前缀命中 → 包含 → 都不命中
   * 2. 类型权重   电影 / 剧集 → 单集 → 其它
   * 3. 年份       降序，null 垫底
   * 4. 台数       多台都有的排前（更可能「有货」）
   * 5. 服务器序   配置靠前的排前
   * 6. 名称       升序，保证同分项稳定
   * ```
   *
   * 匹配层级取 [AggregateEntry.hits] 里**最好**的那个：各台命名的繁简 / 后缀不同，
   * 只看 primary 的名字会让「另一台名字更贴切」的条目被排到后面。
   */
  fun compareEntries(term: String): Comparator<AggregateEntry> =
    Comparator { a, b ->
      var r = bestMatchTier(a, term).compareTo(bestMatchTier(b, term))
      if (r != 0) return@Comparator r

      r = typeWeight(a.item).compareTo(typeWeight(b.item))
      if (r != 0) return@Comparator r

      // 降序：年份新的在前，null 用 MIN_VALUE 垫底
      r = (b.bestYear ?: Int.MIN_VALUE).compareTo(a.bestYear ?: Int.MIN_VALUE)
      if (r != 0) return@Comparator r

      r = b.serverCount.compareTo(a.serverCount)
      if (r != 0) return@Comparator r

      r = a.primary.serverOrder.compareTo(b.primary.serverOrder)
      if (r != 0) return@Comparator r

      (a.item.Name ?: "").compareTo(b.item.Name ?: "")
    }

  /**
   * 同一部片的多份副本之间排序，`first()` 即默认打开的那份。
   *
   * ```text
   * 1. 匹配层级   同上（各台命名不同时，名字更贴切的排前）
   * 2. 类型权重
   * 3. 观看进度   进度比例大的在前 —— 「我在哪台看到一半」是多服用户的真实问题
   * 4. 当前服务器 命中优先 —— 不切服是最符合预期的行为
   * 5. 文件大小   大的在前（同片多版本时通常是画质更好的那份）
   * 6. 服务器序
   * 7. 名称
   * ```
   *
   * 进度排在「当前服务器」之前是刻意的：多台都有同一部片时，用户点它的意图
   * 大多是「接着看」。两边进度都为 0（都没看过）时这一级判平，自然回到
   * 「当前服务器优先」—— 既有手感不变。
   *
   * @param currentServerId 用户此刻正在用的服务器；null = 无偏好
   */
  fun compareHits(term: String, currentServerId: Long?): Comparator<ServerHit> =
    Comparator { a, b ->
      var r = matchTier(a.item.Name, term).compareTo(matchTier(b.item.Name, term))
      if (r != 0) return@Comparator r

      r = typeWeight(a.item).compareTo(typeWeight(b.item))
      if (r != 0) return@Comparator r

      // 降序：进度靠前的在前
      r = b.playedRatio.compareTo(a.playedRatio)
      if (r != 0) return@Comparator r

      r = currentBias(a, currentServerId).compareTo(currentBias(b, currentServerId))
      if (r != 0) return@Comparator r

      // 降序：体积大的在前。-1 表示「取不到」，排在真实值之后
      r = (b.sizeBytes ?: -1L).compareTo(a.sizeBytes ?: -1L)
      if (r != 0) return@Comparator r

      r = a.serverOrder.compareTo(b.serverOrder)
      if (r != 0) return@Comparator r

      (a.item.Name ?: "").compareTo(b.item.Name ?: "")
    }

  // ─── 内部判据 ───

  /**
   * 匹配层级：0 完全相等 / 1 前缀 / 2 包含 / 3 不含。
   *
   * 用 [AggregateSearchKey.normalizeName] 归一化后再比 —— 大小写、全半角、空格的差异
   * 不该影响「谁更准」的判断。
   */
  internal fun matchTier(name: String?, term: String): Int {
    val n = AggregateSearchKey.normalizeName(name) ?: return 3
    val t = AggregateSearchKey.normalizeName(term) ?: return 3
    return when {
      n == t -> 0
      n.startsWith(t) -> 1
      n.contains(t) -> 2
      else -> 3
    }
  }

  /**
   * 类型权重：容器与主内容在前，单集与其它在后。
   *
   * 搜索「片名」时用户要的多半是电影 / 剧集本身；单集（`剧名 S01E03`）铺满首屏
   * 会让人觉得「搜出来一堆散的」。
   */
  internal fun typeWeight(item: EmbyItem): Int =
    when (item.Type?.lowercase(Locale.ROOT)) {
      "movie", "series" -> 0
      "boxset" -> 1
      "episode" -> 2
      else -> 3
    }

  private fun currentBias(hit: ServerHit, currentServerId: Long?): Int =
    if (currentServerId != null && hit.serverId == currentServerId) 0 else 1

  private fun bestMatchTier(entry: AggregateEntry, term: String): Int =
    entry.hits.minOf { matchTier(it.item.Name, term) }

  private val AggregateEntry.bestYear: Int?
    get() = hits.mapNotNull { it.item.ProductionYear }.maxOrNull()
}
