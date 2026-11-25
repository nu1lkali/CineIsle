package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SearchHit

/**
 * 服务端全库搜索的界面状态。
 *
 * 刻意做成**纯 Kotlin**（不依赖 Compose）：ViewModel 与 UI 共用同一份定义，
 * 分页 / 分组这些容易出错的逻辑也就能用纯 JVM 单测覆盖，不必起仪器测试。
 *
 * 与「目录内本地过滤」是两条独立链路：本地搜索不产生任何状态（它只是
 * `files.filter { ... }`），本状态只由 `POST /api/fs/search` 的结果驱动。
 */
sealed interface ServerSearchUiState {
  /** 没在搜 / 已清空。界面此时回到常规浏览，不渲染结果区 */
  data object Idle : ServerSearchUiState

  /** 首屏加载（换词后的 debounce 等待期也算这一档） */
  data class Loading(
    val keyword: String,
  ) : ServerSearchUiState

  /**
   * 有结果可渲染。[hits] 是**累计**的所有已加载页 —— 分页只在尾部追加，
   * 不重置列表，这样用户正在看的分组不会因为续页而挪位。
   *
   * @param nextPage 下一次续页要请求的页码。由服务端返回的页码推进，
   *   不用 `hits.size / perPage + 1` 反推 —— 条目可能因空名被过滤，反推会重复拉第一页。
   */
  data class Content(
    val keyword: String,
    val hits: List<SearchHit>,
    val total: Int,
    val hasMore: Boolean,
    val nextPage: Int = 2,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
  ) : ServerSearchUiState

  /** 服务端明确返回 0 条 */
  data class Empty(
    val keyword: String,
  ) : ServerSearchUiState

  /** 首屏请求失败。保留 [keyword] 供「重试」用原词重发 */
  data class Error(
    val keyword: String,
    val message: String,
  ) : ServerSearchUiState
}

/** 当前状态对应的关键词；[ServerSearchUiState.Idle] 返回 null */
val ServerSearchUiState.keywordOrNull: String?
  get() =
    when (this) {
      ServerSearchUiState.Idle -> null
      is ServerSearchUiState.Loading -> keyword
      is ServerSearchUiState.Content -> keyword
      is ServerSearchUiState.Empty -> keyword
      is ServerSearchUiState.Error -> keyword
    }

/**
 * 按目录分组后的一个结果块。
 *
 * [parentLabel] 是 AList 绝对路径视角的目录标签（如 `/电影/科幻`），**只用于显示**；
 * 真正用于跳转 / 起播的是 [SearchHit.file] 里的项目内部相对 path。
 */
data class SearchGroup(
  val parentLabel: String,
  val hits: List<SearchHit>,
)

/**
 * 把扁平的搜索结果按 `parentLabel` 分组 —— **纯函数视图**，与分页完全解耦。
 *
 * ⭐ 刻意保持「目录首次出现的顺序」，不按命中数 / 路径重排：服务端按页返回，
 *    分页追加后如果组顺序会跳，用户正在看的那一组会突然挪走，滚动位置直接失效。
 *    保留首次出现顺序 ⇒ 续页只会在尾部加新组、或往已有组尾部加行，画面不跳。
 *
 * 组内同样保持服务端返回顺序（不额外排序）。
 */
fun groupSearchHits(hits: List<SearchHit>): List<SearchGroup> {
  if (hits.isEmpty()) return emptyList()
  val grouped = LinkedHashMap<String, MutableList<SearchHit>>()
  hits.forEach { hit ->
    grouped.getOrPut(hit.parentLabel) { ArrayList() }.add(hit)
  }
  return grouped.map { (label, list) -> SearchGroup(label, list) }
}
