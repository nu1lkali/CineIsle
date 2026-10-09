package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity

/**
 * 离线内容列表的分组结构（清单 4.2）。
 *
 * 剧集下载下来是一堆散条目（每集一行），直接平铺的话「下载了 20 集」
 * 会变成 20 张一模一样的卡片，认不出是哪部剧。分组结构是为了让列表
 * 按「电影 / 剧（带集数） / 其他」三段组织，跟 Emby 自己的媒体库观感一致。
 */
sealed interface DownloadedGroup {
  /** 分段标题（如「电影」「剧集」） */
  val title: String

  /** 电影 / 其他：平铺展示 */
  data class Flat(
    override val title: String,
    val items: List<DownloadedMediaEntity>,
  ) : DownloadedGroup

  /** 剧集：按剧名聚成一块，块内按季→集号→集名排 */
  data class BySeries(
    override val title: String,
    val series: List<SeriesGroup>,
    /** 这一段里所有集数的总数，用于标题上的「共 N 集」 */
    val totalEpisodes: Int,
  ) : DownloadedGroup
}

/** 一部剧在离线列表里的一块 */
data class SeriesGroup(
  val seriesName: String,
  val items: List<DownloadedMediaEntity>,
) {
  /** 该剧已下载的季号，升序、去重 */
  val seasons: List<Int> get() = items.mapNotNull { it.seasonNumber }.distinct().sorted()
}

/**
 * 把已下载条目分成 [DownloadedGroup]。
 *
 * ## 排序规则（都是产品决策，写在这里免得以后有人「顺手优化」掉）
 * - 段间顺序固定：**电影 → 剧集 → 其他**。不按数量排 —— 数量会变，
 *   段会跳来跳去，用户的肌肉记忆就废了。
 * - 段内：电影/其他按片名排；剧按剧名排；集按 **季 → 集号 → 集名** 排。
 *   季集号可能为 null（Emby 有时不给），这类排最后而不是排最前 ——
 *   排最前会让「未知集号」的条目插到 S01E01 前面，看着像乱序。
 * - 片名/剧名一律**忽略大小写、忽略首尾空白**排（见 [sortedByTitle]）。
 *   默认的 `sortedBy` 是 ASCII 序，大写字母全部排在小写前面，英文片名多的话
 *   列表会是「Zebra, apple, Banana」这种交错顺序，跟用户心里的字母表不是一回事。
 */
fun groupDownloadedMedia(items: List<DownloadedMediaEntity>): List<DownloadedGroup> {
  if (items.isEmpty()) return emptyList()

  val movies = items.filter { it.type == "Movie" }.sortedByTitle()
  val episodes = items.filter { it.type == "Episode" }
  val others = items.filter { it.type != "Movie" && it.type != "Episode" }.sortedByTitle()

  return buildList {
    if (movies.isNotEmpty()) add(DownloadedGroup.Flat("电影", movies))

    if (episodes.isNotEmpty()) {
      val bySeries = episodes
        .groupBy { it.seriesName?.takeIf { name -> name.isNotBlank() } ?: "未归属剧集" }
        .map { (name, list) ->
          SeriesGroup(
            seriesName = name,
            items = list.sortedWith(
              compareBy(
                // null 排最后：先比「有没有季号」，有的在前
                { it.seasonNumber == null },
                { it.seasonNumber ?: 0 },
                { it.episodeNumber == null },
                { it.episodeNumber ?: 0 },
                { it.title.trim().lowercase() },
              ),
            ),
          )
        }
        .sortedBy { it.seriesName.trim().lowercase() }
      add(DownloadedGroup.BySeries("剧集", bySeries, episodes.size))
    }

    if (others.isNotEmpty()) add(DownloadedGroup.Flat("其他", others))
  }
}

/** 按片名排，忽略大小写与首尾空白。 */
private fun List<DownloadedMediaEntity>.sortedByTitle(): List<DownloadedMediaEntity> =
  sortedWith(compareBy { it.title.trim().lowercase() })

/**
 * 集数的「S01E05」式短标签；没有季集号时返回空串。
 *
 * 剧名后面那截东西就靠它 —— 只显示「第一集」在列表里认不出是哪一集。
 */
fun DownloadedMediaEntity.episodeTag(): String {
  val season = seasonNumber
  val episode = episodeNumber
  return when {
    season != null && episode != null -> "S%02dE%02d".format(season, episode)
    episode != null -> "E%02d".format(episode)
    season != null -> "S%02d".format(season)
    else -> ""
  }
}
