package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbyLibraryFilterUiState] 的回归测试。
 *
 * 重点不是「状态类能不能存值」，而是两个**一旦改坏就会静默出问题**的契约：
 *  · 落盘快照与老偏好字段一一对应；
 *  · 缓存 key 的筛选段与重构前逐字节一致。后者一旦变化，升级后所有媒体库缓存失效，
 *    用户会感觉「每次进库都在重新拉」。
 */
class EmbyLibraryFilterUiStateTest {

  private fun saved() =
    EmbyLibraryFilterState(
      genres = listOf("Action", "Drama"),
      tags = listOf("tag2", "tag1"),
      years = listOf(2024, 2020),
      officialRatings = listOf("R", "PG-13"),
      personIds = listOf("person-2", "person-1"),
      studioIds = listOf("studio-1"),
      isPlayed = false,
      isHD = true,
      is3D = null,
      hasSubtitles = true,
      chineseSubsOnly = false,
      minRating = 7.5f,
      favoriteOnly = true,
    )

  @Test
  fun `演员作品子页的 personId 覆盖已保存的人员筛选`() {
    val state = EmbyLibraryFilterUiState(saved(), initialPersonId = "person-current")
    assertEquals(setOf("person-current"), state.personIdsState.value)
    assertEquals(setOf("studio-1"), state.studioIdsState.value)
  }

  @Test
  fun `snapshot 与初始状态逐字段一致`() {
    val initial = saved()
    val snapshot = EmbyLibraryFilterUiState(initial, initialPersonId = null).snapshot()
    assertEquals(initial.genres.toSet(), snapshot.genres.toSet())
    assertEquals(initial.tags.toSet(), snapshot.tags.toSet())
    assertEquals(initial.years.toSet(), snapshot.years.toSet())
    assertEquals(initial.officialRatings.toSet(), snapshot.officialRatings.toSet())
    assertEquals(initial.personIds.toSet(), snapshot.personIds.toSet())
    assertEquals(initial.studioIds.toSet(), snapshot.studioIds.toSet())
    assertEquals(initial.isPlayed, snapshot.isPlayed)
    assertEquals(initial.isHD, snapshot.isHD)
    assertEquals(initial.is3D, snapshot.is3D)
    assertEquals(initial.hasSubtitles, snapshot.hasSubtitles)
    assertEquals(initial.chineseSubsOnly, snapshot.chineseSubsOnly)
    assertEquals(initial.minRating, snapshot.minRating)
    assertEquals(initial.favoriteOnly, snapshot.favoriteOnly)
  }

  @Test
  fun `applyDraft 只改可见维度且返回是否变化`() {
    val state = EmbyLibraryFilterUiState(saved(), initialPersonId = null)
    val changed = state.applyDraft(
      genres = setOf("Comedy"),
      tags = emptySet(),
      years = setOf(2025),
      officialRatings = setOf("PG"),
      isPlayed = null,
      hasSubtitles = null,
      chineseSubsOnly = true,
      minRating = null,
      favoriteOnly = false,
    )
    assertTrue(changed)
    assertEquals(setOf("Comedy"), state.genresState.value)
    assertEquals(setOf(2025), state.yearsState.value)
    assertTrue(state.chineseSubsOnlyState.value)
    // 隐藏维度（UI 入口已撤）不能被「确定」顺手清掉
    assertEquals(setOf("person-2", "person-1"), state.personIdsState.value)
    assertEquals(setOf("studio-1"), state.studioIdsState.value)
    assertEquals(true, state.isHDState.value)

    val again = state.applyDraft(
      genres = setOf("Comedy"),
      tags = emptySet(),
      years = setOf(2025),
      officialRatings = setOf("PG"),
      isPlayed = null,
      hasSubtitles = null,
      chineseSubsOnly = true,
      minRating = null,
      favoriteOnly = false,
    )
    assertFalse(again)
  }

  @Test
  fun `恢复默认只清隐藏维度 不动可见筛选`() {
    val state = EmbyLibraryFilterUiState(saved(), initialPersonId = null)
    state.clearHidden()
    assertTrue(state.personIdsState.value.isEmpty())
    assertTrue(state.studioIdsState.value.isEmpty())
    assertEquals(null, state.isHDState.value)
    assertEquals(null, state.is3DState.value)
    // 可见维度要等「确定」才生效；点「恢复默认」不能绕过草稿的取消语义
    assertEquals(setOf("Action", "Drama"), state.genresState.value)
    assertEquals(setOf(2020, 2024), state.yearsState.value)
  }

  @Test
  fun `clearAll 连可见维度一起清`() {
    val state = EmbyLibraryFilterUiState(saved(), initialPersonId = null)
    state.clearAll()
    assertTrue(state.isEmpty)
    assertTrue(state.genresState.value.isEmpty())
    assertEquals(null, state.minRatingState.value)
  }

  @Test
  fun `缓存 key 的筛选段保持旧实现的逐字节格式`() {
    val state = EmbyLibraryFilterUiState(saved(), initialPersonId = null)
    assertEquals(
      "g=Action,Drama|t=tag1,tag2|y=2020,2024|r=PG-13,R|p=person-1,person-2|s=studio-1|" +
        "played=false|hd=true|3d=null|sub=true|csub=false|cmarks=marks-v1|m=7.5|fav=true|" +
        "st=Chinese,Sub|scope=1",
      state.cacheSignature(
        chineseMarksSignature = "marks-v1",
        searchFilterNames = "Chinese,Sub",
        searchScope = 1,
      ),
    )
  }
}
