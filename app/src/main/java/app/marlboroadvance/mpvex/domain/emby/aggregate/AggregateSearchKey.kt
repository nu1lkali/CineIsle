package app.marlboroadvance.mpvex.domain.emby.aggregate

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import java.text.Normalizer
import java.util.Locale

/**
 * 跨服务器去重键。
 *
 * ## 原则：宁可少合并，不可错合并
 *
 * 合并错了的后果是「点开一部片，进去是另一部」；不合并的后果只是「看到两条重复」。
 * 前者会让人怀疑整个功能不可信，后者最多是啰嗦。所以每一步都往「更保守」的方向退：
 *
 * | 条目类型 | 合并依据 |
 * | --- | --- |
 * | Movie / Series / BoxSet | 外部元数据 id（Tmdb → Imdb → Tvdb）优先；都没有时退「名称 + 年份」 |
 * | Episode | 剧名 + 季号 + 集号，**三者齐全**才合并 |
 * | Person | **不合并**（ProviderIds 通常为空、同名演员概率高） |
 * | 其它 | 不合并 |
 *
 * 名称退路要求 [EmbyItem.ProductionYear] 非空：两个 `null` 不等于同一年，
 * 把「无年份」的同名片当成同一部，是这类合并里最常见也最难排查的误判。
 *
 * ## 归一化只做四步
 *
 * `trim → NFKC → 去空白 → lowercase(Locale.ROOT)`。
 *
 * **刻意不做**去标点、去发布组标记、去年份括号：这些规则每加一条就会误伤一批
 * （`1917` 与 `1917 (2019)` 想合，`Re:Zero` 与 `ReZero` 也想合，两条规则一冲突就必然出错）。
 * 保守的归一化 + 严格的回退条件，比「聪明的模糊匹配」可靠。
 */
object AggregateSearchKey {
  /**
   * 外部元数据 id 的优先级。
   *
   * Tmdb 最通用（电影、剧集、合集都有），Imdb 覆盖老片好，Tvdb 主要补剧集。
   * 大小写不敏感比较：不同服务端写入的 key 有 `Tmdb` / `tmdb` 两种。
   */
  private val PROVIDER_KEYS = listOf("Tmdb", "Imdb", "Tvdb")

  /** 一条结果的去重键。`serverId` 只在「无法跨台判定」的退路里用到。 */
  fun of(hit: ServerHit): String = of(hit.item, hit.serverId)

  /**
   * 按条目本身构造去重键。
   *
   * @param serverId 来源服务器。仅当条目无法跨台判定时进入键中，
   *   这样两条「本不该合并」的条目永远拿不到同一个键。
   */
  fun of(item: EmbyItem, serverId: Long): String {
    val type = item.Type?.trim().orEmpty()
    val family =
      when (type.lowercase(Locale.ROOT)) {
        "movie" -> FAMILY_MOVIE
        "series" -> FAMILY_SERIES
        "boxset" -> FAMILY_BOXSET
        "episode" -> return episodeKey(item, serverId)
        else -> return sourceKey(type, serverId, item)
      }
    providerKey(item, family)?.let { return it }
    nameKey(item, family)?.let { return it }
    return sourceKey(type, serverId, item)
  }

  /**
   * 名称归一化。**只做四步**，见类注释。
   *
   * @return 空串 / 纯空白返回 null（调用方据此放弃按名称合并）
   */
  fun normalizeName(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val nfkc =
      runCatching { Normalizer.normalize(trimmed, Normalizer.Form.NFKC) }
        .getOrDefault(trimmed)
    return nfkc.filterNot { it.isWhitespace() }
      .lowercase(Locale.ROOT)
      .ifEmpty { null }
  }

  private fun providerKey(item: EmbyItem, family: String): String? {
    val ids = item.ProviderIds
    if (ids.isEmpty()) return null
    for (key in PROVIDER_KEYS) {
      val value =
        ids.entries
          .firstOrNull { it.key.equals(key, ignoreCase = true) }
          ?.value
          ?.trim()
      if (!value.isNullOrEmpty()) {
        return "$family#${key.lowercase(Locale.ROOT)}:$value"
      }
    }
    return null
  }

  private fun nameKey(item: EmbyItem, family: String): String? {
    val name = normalizeName(item.Name) ?: return null
    // 年份缺失 ⇒ 不按名称合并（两个 null 不等于同一年）
    val year = item.ProductionYear ?: return null
    return "$family#name:$name#$year"
  }

  private fun episodeKey(item: EmbyItem, serverId: Long): String {
    val series = normalizeName(item.SeriesName)
    val season = item.ParentIndexNumber
    val episode = item.IndexNumber
    // 三者缺一就不跨台：同名剧 + 同季同集的判断依据全在这三个字段上
    if (series != null && season != null && episode != null) {
      return "$FAMILY_EPISODE#s:$series#$season#$episode"
    }
    return sourceKey(item.Type.orEmpty(), serverId, item)
  }

  private fun sourceKey(type: String, serverId: Long, item: EmbyItem): String {
    val id = item.Id ?: "h${item.hashCode()}"
    return "src:${type.lowercase(Locale.ROOT)}:$serverId:$id"
  }

  private const val FAMILY_MOVIE = "movie"
  private const val FAMILY_SERIES = "series"
  private const val FAMILY_BOXSET = "boxset"
  private const val FAMILY_EPISODE = "episode"
}
