package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlin.random.Random

/**
 * 媒体库「演员」分类的排序方式（纯本地排序，不发请求）。
 *
 * 为什么只能本地排：`/Persons` 端点只认 `SortBy=SortName`（见 `EmbyClient.getActors`），
 * 作品数（`ChildCount`）是客户端按库统计出来的，服务端根本没有这个排序维度；
 * 演员总是一次性拉全（不分页、不进媒体缓存），所以本地重排零成本。
 *
 * 存的是 [key] 而不是枚举名 —— 与媒体库排序（`embyLibrarySortBy`）同一套口径：
 * 枚举重命名不能让老用户的排序偏好失效。
 *
 * ⚠️ 这里**没有**「身高 / 三围」：Emby 的 Person 元数据里不存在这两个字段，
 * `/Persons` 也不返回，客户端拿不到任何可比较的值。真要做必须先从豆瓣一类来源
 * 补齐元数据（写进 Tag 或自定义字段）再接一档排序，否则做出来是一列恒定的假排序。
 */
enum class ActorSort(
  val key: String,
  val label: String,
  /** 没手动切过方向时的自然方向：名称升序，作品数量降序（先看作品多的） */
  val naturalAsc: Boolean,
) {
  NAME("SortName", "名称", naturalAsc = true),
  WORKS("ChildCount", "作品数量", naturalAsc = false),

  /**
   * 随机：**没有**升降序（那两个箭头对它没意义），再点一次同一项 = 换一批。
   * 洗牌用固定种子（见 [sortActors]），否则每次重组都会洗出新顺序，列表在手指底下乱跳。
   */
  RANDOM("Random", "随机", naturalAsc = true),
  ;

  /** 是否带方向（随机没有） */
  val hasDirection: Boolean get() = this != RANDOM

  companion object {
    /** 空串 / 未知键都回落「名称」，兼容老版本与手改过的偏好 */
    fun fromKey(key: String?): ActorSort =
      entries.firstOrNull { it.key == key } ?: NAME
  }
}

/**
 * 按 [sort] / [asc] 重排演员列表。
 *
 * 三条约定（与演员/导演作品页、收藏页一致，避免同一套排序在不同页面表现不一）：
 *  1. **键值缺失的条目一律沉底**，不管升序还是降序 —— 作品数还没统计出来（null）时
 *     不能当成 0 参与比较，否则它在升序里会全部顶到最前面，看起来就像「排序坏了」；
 *  2. 排序是稳定的：键值相同（作品数一样）时按名称兜底，不会因为一次重排莫名换位；
 *  3. 随机走 [seed]，同一个种子得到同一个顺序，只有用户主动「换一批」才换种子。
 */
fun sortActors(
  actors: List<EmbyItem>,
  sort: ActorSort,
  asc: Boolean,
  seed: Int = 0,
): List<EmbyItem> = when (sort) {
  ActorSort.RANDOM -> if (actors.size < 2) actors else actors.shuffled(Random(seed))
  ActorSort.NAME -> sortActorsByName(actors, asc)
  ActorSort.WORKS -> sortActorsByWorks(actors, asc)
}

private fun sortActorsByName(actors: List<EmbyItem>, asc: Boolean): List<EmbyItem> {
  // 名字全空的人排最后：连名字都没有的条目放哪儿都是噪音，不该占着第一屏
  val (named, nameless) = actors.partition { actorName(it).isNotBlank() }
  val comparator = compareBy(String.CASE_INSENSITIVE_ORDER, ::actorName)
  return (if (asc) named.sortedWith(comparator) else named.sortedWith(comparator.reversed())) +
    nameless
}

private fun sortActorsByWorks(actors: List<EmbyItem>, asc: Boolean): List<EmbyItem> {
  // 作品数按可见位置分批统计（见 EmbyLibraryScreen.loadNextActorCounts），
  // 因此「还没数到」的演员会有一段时间是 null；这段时间里让它们待在末尾，
  // 数出来之后再按真实数量归位，不会出现「先挤在最前、随后突然消失」。
  val (counted, uncounted) = actors.partition { it.ChildCount != null }
  val byName = compareBy(String.CASE_INSENSITIVE_ORDER, ::actorName)
  val sorted = if (asc) {
    counted.sortedWith(compareBy<EmbyItem> { it.ChildCount ?: 0 }.then(byName))
  } else {
    counted.sortedWith(compareByDescending<EmbyItem> { it.ChildCount ?: 0 }.then(byName))
  }
  return sorted + uncounted
}

/** 演员显示名：优先 SortName（Emby 内部排序名），取不到退回 Name */
private fun actorName(actor: EmbyItem): String =
  (actor.SortName ?: actor.Name).orEmpty()
