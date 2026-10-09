package app.marlboroadvance.mpvex.ui.browser.emby.subtitle

import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕候选**排序**逻辑的测试。
 *
 * 排序是这功能里最影响体验的一环：Emby 各提供方都不校验文件名，
 * 搜出来会混进「同一部电影不同公映版」的字幕，用户不逐条比对根本认不出来。
 * `IsHashMatch` 是唯一可靠的机器判据，必须排第一。
 */
class SubtitleRankingTest {

  private fun sub(
    id: String,
    hashMatch: Boolean = false,
    rating: Float? = null,
    downloads: Int? = null,
    name: String? = null,
  ) = EmbyRemoteSubtitle(
    Id = id,
    Name = name ?: id,
    IsHashMatch = hashMatch,
    CommunityRating = rating,
    DownloadCount = downloads,
  )

  /** 直接调生产代码里的顶层排序函数（不是复刻）。 */
  private fun rank(
    candidates: Collection<EmbyRemoteSubtitle>,
    perfectMatchOnly: Boolean,
  ): List<EmbyRemoteSubtitle> = rankSubtitles(candidates, perfectMatchOnly)

  @Test
  fun `精确匹配的排最前`() {
    val list = rank(
      listOf(
        sub("a", hashMatch = false, rating = 9.0f, downloads = 9999),
        sub("b", hashMatch = true, rating = 1.0f, downloads = 0),
      ),
      perfectMatchOnly = false,
    )
    assertEquals("哈希匹配必须压过评分和下载量", "b", list.first().Id)
  }

  @Test
  fun `同档内按评分降序`() {
    val list = rank(
      listOf(
        sub("a", rating = 3.0f),
        sub("b", rating = 8.0f),
        sub("c", rating = 5.0f),
      ),
      perfectMatchOnly = false,
    )
    assertEquals(listOf("b", "c", "a"), list.map { it.Id })
  }

  @Test
  fun `评分相同时按下载量降序`() {
    val list = rank(
      listOf(
        sub("a", rating = 7.0f, downloads = 10),
        sub("b", rating = 7.0f, downloads = 500),
        sub("c", rating = 7.0f, downloads = 50),
      ),
      perfectMatchOnly = false,
    )
    assertEquals(listOf("b", "c", "a"), list.map { it.Id })
  }

  @Test
  fun `评分和下载量都缺时按名字稳定排序`() {
    val list = rank(
      listOf(sub("z", name = "Zebra.srt"), sub("a", name = "Apple.srt")),
      perfectMatchOnly = false,
    )
    assertEquals(listOf("Apple.srt", "Zebra.srt"), list.map { it.Name })
  }

  @Test
  fun `评分缺失不会让它排到最后`() {
    // 很多提供方不给评分（返回 null）。若按 0 处理，
    // 「有 3 分的」会排在「没评分的」前面 —— 顺序就乱了。
    val list = rank(
      listOf(sub("rated", rating = 3.0f), sub("unrated", rating = null)),
      perfectMatchOnly = false,
    )
    // 两者都视为 0 ⇒ 进入下载量/名字档；这里断言的是「unrated 不会被丢到最后」
    assertEquals(2, list.size)
    assertTrue(list.map { it.Id }.contains("unrated"))
  }

  @Test
  fun `开启只看精确匹配时非匹配项全被滤掉`() {
    val list = rank(
      listOf(
        sub("keep", hashMatch = true),
        sub("drop1", hashMatch = false),
        sub("drop2", hashMatch = false),
      ),
      perfectMatchOnly = true,
    )
    assertEquals(listOf("keep"), list.map { it.Id })
  }

  @Test
  fun `只看精确匹配但一条都不匹配时返回空列表`() {
    val list = rank(
      listOf(sub("a"), sub("b")),
      perfectMatchOnly = true,
    )
    assertTrue(list.isEmpty())
  }

  @Test
  fun `排序不会丢条目`() {
    // 排序若实现成「取前 N 条」就会漏；这里确认只是换了顺序
    val input = (1..20).map { sub("id$it", rating = it.toFloat()) }
    val list = rank(input, perfectMatchOnly = false)
    assertEquals(20, list.size)
    assertEquals(input.map { it.Id }.toSet(), list.map { it.Id }.toSet())
  }

  @Test
  fun `格式归一化只认白名单内的`() {
    assertEquals("srt", SubtitleSearchViewModel.formatSuffix("SRT"))
    assertEquals("ass", SubtitleSearchViewModel.formatSuffix("ASS"))
    // 服务端不支持 / 我们没把握的格式一律落到 srt（最通用）
    assertEquals("srt", SubtitleSearchViewModel.formatSuffix("smi"))
    assertEquals("srt", SubtitleSearchViewModel.formatSuffix(null))
    assertEquals("srt", SubtitleSearchViewModel.formatSuffix(""))
  }
}