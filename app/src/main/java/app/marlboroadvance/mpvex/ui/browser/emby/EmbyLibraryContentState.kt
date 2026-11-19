package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleHit
import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/**
 * 媒体库的**列表数据状态**（P0：LibraryUiState 的第二刀）。
 *
 * 这些字段原先都散在 `EmbyLibraryScreen` 的组合体里，而且初始值由「搜索恢复 / 缓存 /
 * 中文字幕模式」三种来源共同决定。收进一个容器后：
 *  · 初始值只在一个构造点算一次，页面里剩下的只是 `By` 委托；
 *  · 分页游标、演员数据、扫描态有了明确的边界，下一步可以直接搬进 ViewModel；
 *  · 纯 UI 状态（下拉刷新、菜单、扫描 Job、这一层快照）仍留在页面，不混进来。
 *
 * ⚠️ 容器用 `remember(cacheKey)` 持有：`cacheKey` 一变（换库 / 换分类 / 改筛选 / 改搜索词），
 * 整组数据必须和旧实现一样重新起值。`layerItems` 刻意**不**放进来 —— 它按 libraryId
 * 记忆、跨 cacheKey 存活，是搜索本地兜底的「这一层真实内容」。
 */
@Stable
internal class EmbyLibraryContentState(
  initialItems: List<EmbyItem>,
  initialTotalCount: Int,
  initialConsumedCount: Int,
  initialIsLoading: Boolean,
  initialChineseHits: Map<String, ChineseSubtitleHit>,
  initialScanScanned: Int,
) {
  val itemsState = mutableStateOf(initialItems)
  val totalCountState = mutableIntStateOf(initialTotalCount)

  /** 服务端实际返回过的条数（分页游标），绝不能拿 `items.size` 代替。 */
  val consumedCountState = mutableIntStateOf(initialConsumedCount)
  val loadingMoreState = mutableStateOf(false)

  /** 「演员」分类独立数据：/Persons 一次性给全，不走媒体分页。 */
  val actorItemsState = mutableStateOf(emptyList<EmbyItem>())
  val actorTotalState = mutableIntStateOf(0)
  /** 作品数角标当前已补到哪一批；刷新演员名单后归零并重新渐进加载。 */
  val actorCountLoadedState = mutableIntStateOf(0)
  /** 防止滚动触底回调在同一批统计尚未结束时重复发起请求。 */
  val actorCountLoadingState = mutableStateOf(false)
  val actorLoadingState = mutableStateOf(false)
  val actorErrorState = mutableStateOf<String?>(null)

  val isLoadingState = mutableStateOf(initialIsLoading)
  val errorState = mutableStateOf<String?>(null)

  /** itemId → 命中的中文字幕标记，卡片角标与搜索沿用同一份。 */
  val chineseHitsState = mutableStateOf(initialChineseHits)

  /** 客户端全库扫描的进度与状态。 */
  val scanScannedState = mutableIntStateOf(initialScanScanned)
  val isScanningState = mutableStateOf(false)
}
