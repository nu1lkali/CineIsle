package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线内容分组逻辑测试（清单 4.2）。
 *
 * 分组是纯函数，所以能直接测。这里的每条断言都对应一个产品决策 ——
 * 改排序时这些测试会拦住你，理由写在每个用例的名字里。
 */
class DownloadedMediaGrouperTest {

  private fun media(
    itemId: String,
    type: String?,
    title: String = itemId,
    seriesName: String? = null,
    season: Int? = null,
    episode: Int? = null,
  ) = DownloadedMediaEntity(
    itemId = itemId,
    serverId = 1L,
    title = title,
    type = type,
    seriesName = seriesName,
    seasonNumber = season,
    episodeNumber = episode,
  )

  // ── 基本分段 ──

  @Test
  fun `空列表返回空分组`() {
    assertTrue(groupDownloadedMedia(emptyList()).isEmpty())
  }

  @Test
  fun `只有电影时只出电影段`() {
    val groups = groupDownloadedMedia(listOf(media("m1", "Movie"), media("m2", "Movie")))
    assertEquals(1, groups.size)
    assertEquals("电影", groups[0].title)
  }

  @Test
  fun `段顺序固定为电影 剧集 其他`() {
    // 刻意不按数量排：数量会变，段会跳来跳去，用户肌肉记忆就废了
    val groups = groupDownloadedMedia(
      listOf(
        media("o1", "MusicVideo"),
        media("e1", "Episode", seriesName = "剧A"),
        media("m1", "Movie"),
        media("o2", "BoxSet"),
      ),
    )
    assertEquals(listOf("电影", "剧集", "其他"), groups.map { it.title })
  }

  @Test
  fun `没有的段不出现`() {
    val groups = groupDownloadedMedia(listOf(media("m1", "Movie"), media("o1", "MusicVideo")))
    assertEquals(listOf("电影", "其他"), groups.map { it.title })
  }

  // ── 电影 / 其他段内排序 ──

  @Test
  fun `电影段按片名排`() {
    val groups = groupDownloadedMedia(
      listOf(media("1", "Movie", title = "Zebra"), media("2", "Movie", title = "Apple")),
    )
    val flat = groups[0] as DownloadedGroup.Flat
    assertEquals(listOf("Apple", "Zebra"), flat.items.map { it.title })
  }

  @Test
  fun `片名排序忽略大小写`() {
    // 默认的 sortedBy 是 ASCII 序，大写字母全排在小写前面，
    // 英文片名一多列表就变成「Zebra, apple, Banana」这种交错顺序。
    val groups = groupDownloadedMedia(
      listOf(
        media("1", "Movie", title = "zebra"),
        media("2", "Movie", title = "Apple"),
        media("3", "Movie", title = "banana"),
      ),
    )
    val flat = groups[0] as DownloadedGroup.Flat
    assertEquals(listOf("Apple", "banana", "zebra"), flat.items.map { it.title })
  }

  @Test
  fun `片名排序忽略首尾空白`() {
    val groups = groupDownloadedMedia(
      listOf(media("1", "Movie", title = "  Zebra"), media("2", "Movie", title = "Apple ")),
    )
    val flat = groups[0] as DownloadedGroup.Flat
    assertEquals(listOf("Apple ", "  Zebra"), flat.items.map { it.title })
  }

  @Test
  fun `其他段收拢所有既不是电影也不是单集的类型`() {
    val groups = groupDownloadedMedia(
      listOf(
        media("1", "BoxSet"),
        media("2", "MusicVideo"),
        media("3", "Series"),
        media("4", "Movie"),
        media("5", "Episode", seriesName = "剧"),
      ),
    )
    val other = groups.first { it.title == "其他" } as DownloadedGroup.Flat
    assertEquals(setOf("1", "2", "3"), other.items.map { it.itemId }.toSet())
  }

  // ── 剧集分组 ──

  @Test
  fun `同一剧名的集数聚到一块`() {
    val groups = groupDownloadedMedia(
      listOf(
        media("a1", "Episode", seriesName = "剧A", season = 1, episode = 1),
        media("b1", "Episode", seriesName = "剧B", season = 1, episode = 1),
        media("a2", "Episode", seriesName = "剧A", season = 1, episode = 2),
      ),
    )
    val bySeries = groups.single() as DownloadedGroup.BySeries
    assertEquals(2, bySeries.series.size)
    assertEquals(listOf("剧A", "剧B"), bySeries.series.map { it.seriesName })
    assertEquals(3, bySeries.totalEpisodes)
  }

  @Test
  fun `剧名缺失时归到未归属剧集而不是散架`() {
    val groups = groupDownloadedMedia(
      listOf(media("a1", "Episode", seriesName = null, season = 1, episode = 1)),
    )
    val bySeries = groups.single() as DownloadedGroup.BySeries
    assertEquals("未归属剧集", bySeries.series.single().seriesName)
  }

  @Test
  fun `剧名是空白串时也归到未归属剧集`() {
    val groups = groupDownloadedMedia(listOf(media("a1", "Episode", seriesName = "   ")))
    val bySeries = groups.single() as DownloadedGroup.BySeries
    assertEquals("未归属剧集", bySeries.series.single().seriesName)
  }

  @Test
  fun `集内按季再按集号排`() {
    val groups = groupDownloadedMedia(
      listOf(
        media("s2e1", "Episode", seriesName = "剧", season = 2, episode = 1),
        media("s1e2", "Episode", seriesName = "剧", season = 1, episode = 2),
        media("s1e10", "Episode", seriesName = "剧", season = 1, episode = 10),
        media("s1e1", "Episode", seriesName = "剧", season = 1, episode = 1),
      ),
    )
    val series = (groups.single() as DownloadedGroup.BySeries).series.single()
    // 集号按**数值**排，不是按字符串：按字符串排会把 E10 排在 E2 前面
    assertEquals(
      listOf("s1e1", "s1e2", "s1e10", "s2e1"),
      series.items.map { it.itemId },
    )
  }

  @Test
  fun `季号缺失的集排最后而不是排最前`() {
    // 排最前会让「未知集号」插到 S01E01 前面，看着像乱序
    val groups = groupDownloadedMedia(
      listOf(
        media("unknown", "Episode", seriesName = "剧", season = null, episode = 5),
        media("s1e1", "Episode", seriesName = "剧", season = 1, episode = 1),
      ),
    )
    val series = (groups.single() as DownloadedGroup.BySeries).series.single()
    assertEquals(listOf("s1e1", "unknown"), series.items.map { it.itemId })
  }

  @Test
  fun `季号列表去重且升序`() {
    val groups = groupDownloadedMedia(
      listOf(
        media("a", "Episode", seriesName = "剧", season = 2, episode = 1),
        media("b", "Episode", seriesName = "剧", season = 1, episode = 1),
        media("c", "Episode", seriesName = "剧", season = 1, episode = 2),
      ),
    )
    val series = (groups.single() as DownloadedGroup.BySeries).series.single()
    assertEquals(listOf(1, 2), series.seasons)
  }

  @Test
  fun `条目总数守恒不丢不重`() {
    val all = listOf(
      media("m1", "Movie"),
      media("m2", "Movie"),
      media("e1", "Episode", seriesName = "剧A", season = 1, episode = 1),
      media("e2", "Episode", seriesName = "剧B", season = 1, episode = 1),
      media("o1", "BoxSet"),
      media("o2", "MusicVideo"),
    )
    val seen = groupDownloadedMedia(all).flatMap { group ->
      when (group) {
        is DownloadedGroup.Flat -> group.items
        is DownloadedGroup.BySeries -> group.series.flatMap { it.items }
      }
    }.map { it.itemId }
    assertEquals(all.size, seen.size)
    assertEquals(all.map { it.itemId }.toSet(), seen.toSet())
  }

  // ── episodeTag ──

  @Test
  fun `季集号齐全时给 S01E05`() {
    assertEquals("S01E05", media("x", "Episode", season = 1, episode = 5).episodeTag())
    assertEquals("S12E34", media("x", "Episode", season = 12, episode = 34).episodeTag())
  }

  @Test
  fun `只有集号时给 E05`() {
    assertEquals("E05", media("x", "Episode", season = null, episode = 5).episodeTag())
  }

  @Test
  fun `只有季号时给 S02`() {
    assertEquals("S02", media("x", "Episode", season = 2, episode = null).episodeTag())
  }

  @Test
  fun `季集号都没有时给空串`() {
    // 空串让调用方能直接 `if (tag.isNotEmpty()) "$tag · ${title}"`，不用判两次 null
    assertEquals("", media("x", "Episode", season = null, episode = null).episodeTag())
  }
}
