package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState

/**
 * 媒体库筛选条件的**页面级状态容器**（P0：LibraryUiState 的第一刀）。
 *
 * ## 为什么需要
 *
 * 原先 `EmbyLibraryScreen` 里散着 12 个 `selected* / *Filter` 变量，外加一套草稿变量；
 * 「当前条件」「落盘快照」「缓存 key」三件事各写一遍，任何新增维度都要改四五个地方。
 * 这里把它们收成一份：
 *  · 字段是 Compose `MutableState`，页面用 `by` 委托后原引用名不变；
 *  · [snapshot] 负责落盘，[cacheSignature] 负责缓存 key，口径只有一处；
 *  · [applyDraft] / [clearAll] 把「确定」与「恢复默认」的批量赋值也收回来。
 *
 * ## 不能变的两个契约
 *
 * 1. **持久化 JSON**：字段名与 [EmbyLibraryFilterState] 完全一致，老偏好照常反序列化。
 * 2. **缓存 key**：[cacheSignature] 的输出必须与改造前逐字节一致，否则升级后旧缓存
 *    全部失效、每个库都要重拉一遍。`EmbyLibraryFilterUiStateTest` 用固定样本钉住了它。
 */
@Stable
internal class EmbyLibraryFilterUiState(
  initial: EmbyLibraryFilterState,
  /** 演员作品子页：personId 非空时直接把它当成已选中的人员筛选。 */
  initialPersonId: String?,
) {
  val genresState = mutableStateOf(initial.genres.toSet())
  val tagsState = mutableStateOf(initial.tags.toSet())
  val yearsState = mutableStateOf(initial.years.toSet())
  val officialRatingsState = mutableStateOf(initial.officialRatings.toSet())
  val personIdsState = mutableStateOf(
    initialPersonId?.let { setOf(it) } ?: initial.personIds.toSet(),
  )
  val studioIdsState = mutableStateOf(initial.studioIds.toSet())
  val isPlayedState = mutableStateOf(initial.isPlayed)
  val isHDState = mutableStateOf(initial.isHD)
  val is3DState = mutableStateOf(initial.is3D)
  val hasSubtitlesState = mutableStateOf(initial.hasSubtitles)
  val chineseSubsOnlyState = mutableStateOf(initial.chineseSubsOnly)
  val minRatingState = mutableStateOf(initial.minRating)
  val favoriteOnlyState = mutableStateOf(initial.favoriteOnly)

  /** 全空 = 没有生效条件。与 [EmbyLibraryFilterState.isEmpty] 同一口径。 */
  val isEmpty: Boolean
    get() =
      genresState.value.isEmpty() && tagsState.value.isEmpty() && yearsState.value.isEmpty() &&
        officialRatingsState.value.isEmpty() && personIdsState.value.isEmpty() &&
        studioIdsState.value.isEmpty() && isPlayedState.value == null &&
        isHDState.value == null && is3DState.value == null && hasSubtitlesState.value == null &&
        minRatingState.value == null && !favoriteOnlyState.value &&
        !chineseSubsOnlyState.value

  /** 生成落盘快照。集合顺序与原实现一致（Set.toList）。 */
  fun snapshot(): EmbyLibraryFilterState =
    EmbyLibraryFilterState(
      genres = genresState.value.toList(),
      tags = tagsState.value.toList(),
      years = yearsState.value.toList(),
      officialRatings = officialRatingsState.value.toList(),
      personIds = personIdsState.value.toList(),
      studioIds = studioIdsState.value.toList(),
      isPlayed = isPlayedState.value,
      isHD = isHDState.value,
      is3D = is3DState.value,
      hasSubtitles = hasSubtitlesState.value,
      chineseSubsOnly = chineseSubsOnlyState.value,
      minRating = minRatingState.value,
      favoriteOnly = favoriteOnlyState.value,
    )

  /**
   * 应用筛选抽屉里的**可见草稿**。
   *
   * 只覆盖抽屉里能编辑的维度；演员 / 工作室 / 高清 / 3D 的 UI 入口已撤，但老偏好里
   * 可能还留着值，这些维度刻意不动（「恢复默认」才由 [clearAll] 一并清）。
   *
   * @return 是否真的发生了变化（调用方据此决定要不要重新拉列表）。
   */
  fun applyDraft(
    genres: Set<String>,
    tags: Set<String>,
    years: Set<Int>,
    officialRatings: Set<String>,
    isPlayed: Boolean?,
    hasSubtitles: Boolean?,
    chineseSubsOnly: Boolean,
    minRating: Float?,
    favoriteOnly: Boolean,
  ): Boolean {
    val changed =
      genresState.value != genres || tagsState.value != tags || yearsState.value != years ||
        officialRatingsState.value != officialRatings || isPlayedState.value != isPlayed ||
        hasSubtitlesState.value != hasSubtitles ||
        chineseSubsOnlyState.value != chineseSubsOnly ||
        minRatingState.value != minRating || favoriteOnlyState.value != favoriteOnly
    if (!changed) return false
    genresState.value = genres
    tagsState.value = tags
    yearsState.value = years
    officialRatingsState.value = officialRatings
    isPlayedState.value = isPlayed
    hasSubtitlesState.value = hasSubtitles
    chineseSubsOnlyState.value = chineseSubsOnly
    minRatingState.value = minRating
    favoriteOnlyState.value = favoriteOnly
    return true
  }

  /**
   * 「恢复默认」时清理**没有草稿入口的隐藏维度**。
   *
   * ⚠️ 不能清可见维度：抽屉里的可见项只改草稿，用户点「取消」要能整体撤销；
   * 隐藏维度没有草稿，老偏好里留着值会让「恢复默认」看起来没恢复干净，所以只能
   * 在这里立即清。这个区分是旧实现的既有语义，重构不能把它合并成 clearAll。
   */
  fun clearHidden() {
    personIdsState.value = emptySet()
    studioIdsState.value = emptySet()
    isHDState.value = null
    is3DState.value = null
  }

  /** 清空全部（含可见维度）。给「重置整个筛选」这类明确语义用，不用于抽屉的恢复默认。 */
  fun clearAll() {
    genresState.value = emptySet()
    tagsState.value = emptySet()
    yearsState.value = emptySet()
    officialRatingsState.value = emptySet()
    personIdsState.value = emptySet()
    studioIdsState.value = emptySet()
    isPlayedState.value = null
    isHDState.value = null
    is3DState.value = null
    hasSubtitlesState.value = null
    chineseSubsOnlyState.value = false
    minRatingState.value = null
    favoriteOnlyState.value = false
  }

  /**
   * 缓存 key 中「筛选条件」这一段。
   *
   * ⚠️ 顺序与分隔符必须与旧实现逐字节一致：
   * `g=…|t=…|y=…|r=…|p=…|s=…|played=…|hd=…|3d=…|sub=…|csub=…|cmarks=…|m=…|fav=…|st=…|scope=…`
   */
  fun cacheSignature(
    chineseMarksSignature: String,
    searchFilterNames: String,
    searchScope: Int,
  ): String =
    buildString {
      append("g=").append(genresState.value.sorted().joinToString(",")).append('|')
      append("t=").append(tagsState.value.sorted().joinToString(",")).append('|')
      append("y=").append(yearsState.value.sorted().joinToString(",")).append('|')
      append("r=").append(officialRatingsState.value.sorted().joinToString(",")).append('|')
      append("p=").append(personIdsState.value.sorted().joinToString(",")).append('|')
      append("s=").append(studioIdsState.value.sorted().joinToString(",")).append('|')
      append("played=").append(isPlayedState.value).append('|')
      append("hd=").append(isHDState.value).append('|')
      append("3d=").append(is3DState.value).append('|')
      append("sub=").append(hasSubtitlesState.value).append('|')
      append("csub=").append(chineseSubsOnlyState.value).append('|')
      append("cmarks=").append(chineseMarksSignature).append('|')
      append("m=").append(minRatingState.value).append('|')
      append("fav=").append(favoriteOnlyState.value).append('|')
      append("st=").append(searchFilterNames).append('|')
      append("scope=").append(searchScope)
    }
}
