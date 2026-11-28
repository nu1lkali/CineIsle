package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.RelatedEpisodes

/**
 * 详情页的**数据状态**容器（P0 拆分的第一刀）。
 *
 * 原先这十来个字段散落在 `EmbyDetailScreen.Content()` 里，全是裸 `remember { mutableStateOf(...) }`：
 *  · 数据（item / 剧集 / 推荐 / 相关分集 / 版本续播）与纯 UI 开关（菜单、弹窗、转圈）混在一起；
 *  · 任何一个字段改名都要在 3000 行的文件里全局搜；
 *  · 将来迁到 `DetailViewModel` 时，没有现成的状态边界可搬。
 *
 * 现在把「跟着 itemId 走的数据」收进来，页面用 `remember(itemId) { EmbyDetailContentState() }`
 * 持有；UI 开关仍留在页面本地。字段名保持 `xxxState`，页面里用 `var item by state.itemState`
 * 维持原引用名，所以这一刀不改任何布局与交互。
 *
 * 下一步（P1）可以把 `load()` / 分集 / 推荐的请求也搬进 ViewModel，让这个类变成
 * 不可变 `EmbyDetailUiState` 的 snapshot。
 */
@Stable
internal class EmbyDetailContentState {
  val itemState = mutableStateOf<EmbyItem?>(null)
  val loadingState = mutableStateOf(true)
  val errorState = mutableStateOf<String?>(null)

  /** 多版本媒体：当前选中的版本 Id；null = 服务端默认版本。 */
  val selectedMediaSourceIdState = mutableStateOf<String?>(null)

  /** 多版本媒体：当前版本的本地续播秒数；null = 沿用服务端进度。 */
  val versionResumeSecondsState = mutableStateOf<Long?>(null)

  /** 整部剧的分集（仅 Series）。 */
  val seriesEpisodesState = mutableStateOf<List<EmbyItem>>(emptyList())
  val episodesLoadingState = mutableStateOf(true)

  /** 两块推荐区。 */
  val recommendItemsState = mutableStateOf<List<EmbyItem>>(emptyList())
  val genreRecommendLabelState = mutableStateOf<String?>(null)
  val genreRecommendItemsState = mutableStateOf<List<EmbyItem>>(emptyList())
  val recommendReloadKeyState = mutableStateOf(0)

  /** 单集详情页的「同季 / 特别篇」分集。 */
  val relatedEpisodesState = mutableStateOf(RelatedEpisodes())

  /**
   * 字幕信息（详情页只读预览）。
   *
   * 与 item / 分集不同，它还会随**版本切换**重查 —— 但状态容器仍挂在这里，
   * 因为口径是「跟着本页的条目走」，换条目就该整份丢掉。
   */
  val subtitleState = mutableStateOf(DetailSubtitleUi())
}
