package app.marlboroadvance.mpvex.ui.browser.emby.discover

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地相似度算法（`LocalSimilarity`）的单测。
 *
 * 调的是**主源码里的真函数**，不是测试内复刻的副本 ——
 * 复刻一份「同逻辑的代码」测它自己，永远测不出真代码里的错。
 */
class LocalSimilarityTest {

  // ─── 工具 ───

  private fun item(
    id: String,
    genres: List<String> = emptyList(),
    year: Int? = null,
    rating: Double? = null,
    people: List<Pair<String, String>> = emptyList(),
    seriesId: String? = null,
    played: Boolean = false,
  ) = EmbyItem(
    Id = id,
    Name = "片 $id",
    Type = "Movie",
    Genres = genres,
    ProductionYear = year,
    CommunityRating = rating,
    People = people.map { EmbyPerson(Id = it.first, Name = it.second, Type = "Actor") },
    SeriesId = seriesId,
    UserData = EmbyUserData(Played = played),
  )

  // ─── 类型重合度 ───

  @Test
  fun `类型完全相同重合度是满分`() {
    val seed = item("a", genres = listOf("科幻", "动作"))
    val candidate = item("b", genres = listOf("动作", "科幻"))
    assertEquals(1.0, LocalSimilarity.genreSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `类型顺序不影响结果`() {
    val a = item("a", genres = listOf("科幻", "动作", "犯罪"))
    val b = item("b", genres = listOf("犯罪", "科幻", "动作"))
    assertEquals(1.0, LocalSimilarity.genreSimilarity(a, b), 1e-9)
  }

  @Test
  fun `一半类型重合按 Jaccard 给分而不是满分`() {
    // 种子 2 个类型，候选 4 个，交集 2 ⇒ 2/(2+4-2) = 0.5
    // 这正是「不按种子类型数归一」的意义：候选类型多不代表它就完全不像
    val seed = item("a", genres = listOf("科幻", "动作"))
    val candidate = item("b", genres = listOf("科幻", "动作", "喜剧", "爱情"))
    assertEquals(0.5, LocalSimilarity.genreSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `类型互不相交时是零分`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidate = item("b", genres = listOf("喜剧"))
    assertEquals(0.0, LocalSimilarity.genreSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `大小写与空白不影响类型匹配`() {
    val seed = item("a", genres = listOf(" Sci-Fi ", "ACTION"))
    val candidate = item("b", genres = listOf("sci-fi", "action"))
    assertEquals(1.0, LocalSimilarity.genreSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `两方都没有类型时给零分而不是满分`() {
    // 「都没信息」不等于「很相似」。给满分会把所有没刮削的条目全推到前面
    assertEquals(0.0, LocalSimilarity.genreSimilarity(item("a"), item("b")), 1e-9)
  }

  @Test
  fun `只有一方没类型时给零分`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidate = item("b")
    assertEquals(0.0, LocalSimilarity.genreSimilarity(seed, candidate), 1e-9)
  }

  // ─── 阵容重合度 ───

  @Test
  fun `阵容完全重合时是满分`() {
    val cast = listOf("p1" to "演员甲", "p2" to "演员乙")
    val seed = item("a", people = cast)
    val candidate = item("b", people = cast)
    assertEquals(1.0, LocalSimilarity.peopleSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `演员数量差异大时按较小一方归一`() {
    // 种子 4 人、候选 2 人，交集 2 ⇒ 2/min(4,2) = 1.0
    // 若按并集算只有 0.5，按种子数算 0.5 —— 两种都会低估「小剧组与大剧组同班人马」
    val seed = item(
      "a",
      people = listOf("p1" to "甲", "p2" to "乙", "p3" to "丙", "p4" to "丁"),
    )
    val candidate = item("b", people = listOf("p1" to "甲", "p2" to "乙"))
    assertEquals(1.0, LocalSimilarity.peopleSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `只统计演员和导演不算编剧`() {
    // 编剧相同但题材完全不同的两部片子，不该被当成「同班阵容」
    val writer = EmbyPerson(Id = "w1", Name = "编剧", Type = "Writer")
    val seed = item("a", genres = listOf("科幻")).copy(People = listOf(writer))
    val candidate = item("b", genres = listOf("喜剧")).copy(People = listOf(writer))
    assertEquals(0.0, LocalSimilarity.peopleSimilarity(seed, candidate), 1e-9)
  }

  @Test
  fun `两边都没刮演员时阵容项为零`() {
    assertEquals(0.0, LocalSimilarity.peopleSimilarity(item("a"), item("b")), 1e-9)
  }

  // ─── 年代接近度 ───

  @Test
  fun `同年给满分`() {
    assertEquals(1.0, LocalSimilarity.yearSimilarity(item("a", year = 2019), item("b", year = 2019)), 1e-9)
  }

  @Test
  fun `年代越远分越低且单调递减`() {
    val seed = item("a", year = 2000)
    val near = LocalSimilarity.yearSimilarity(seed, item("b", year = 2001))
    val mid = LocalSimilarity.yearSimilarity(seed, item("c", year = 2005))
    val far = LocalSimilarity.yearSimilarity(seed, item("d", year = 2020))
    assertTrue("差 1 年应高于差 5 年", near > mid)
    assertTrue("差 5 年应高于差 20 年", mid > far)
    assertTrue(near in 0.0..1.0)
  }

  @Test
  fun `任一方缺年份时给零分`() {
    assertEquals(0.0, LocalSimilarity.yearSimilarity(item("a", year = 2000), item("b")), 1e-9)
    assertEquals(0.0, LocalSimilarity.yearSimilarity(item("a"), item("b", year = 2000)), 1e-9)
  }

  // ─── 评分接近度 ───

  @Test
  fun `同评分给满分`() {
    assertEquals(1.0, LocalSimilarity.ratingSimilarity(item("a", rating = 8.0), item("b", rating = 8.0)), 1e-9)
  }

  @Test
  fun `评分差超过阈值直接归零`() {
    // 0 分 vs 10 分：差 10 已封顶为 0，而不是 exp(-10/3) 的 0.036
    assertEquals(0.0, LocalSimilarity.ratingSimilarity(item("a", rating = 0.0), item("b", rating = 10.0)), 1e-9)
  }

  @Test
  fun `双方都没评分时给零分`() {
    // 「都没信息」不等于「同样 8 分」。早期版本在这里白送满分，
    // 导致元数据缺失的条目被顶到有真实评分的前面
    assertEquals(0.0, LocalSimilarity.ratingSimilarity(item("a"), item("b")), 1e-9)
  }

  @Test
  fun `只有一方没评分时仍有区分度`() {
    // 种子没评分、候选 1 分：差 1 分 < 阈值 3，仍给一点分
    val value = LocalSimilarity.ratingSimilarity(item("a"), item("b", rating = 1.0))
    assertTrue("一方缺时应保留弱信号", value > 0.0)
  }

  // ─── 同系列判重 ───

  @Test
  fun `剧集按 SeriesId 判同系列`() {
    val a = item("s1e1", seriesId = "series-1")
    val b = item("s1e2", seriesId = "series-1")
    val ranked = LocalSimilarity.rank(a, listOf(b))
    assertTrue("同一剧的不同集不该互相推荐", ranked.isEmpty())
  }

  @Test
  fun `不同剧集不判同系列`() {
    val a = item("a", seriesId = "series-1")
    val b = item("b", seriesId = "series-2")
    assertEquals(1, LocalSimilarity.rank(a, listOf(b)).size)
  }

  @Test
  fun `电影类型完全相同时被判同系列并剔除`() {
    // 这是「从严」策略：宁可漏掉，不让两部同类型但无关的电影互相挤掉名额
    val a = item("a", genres = listOf("科幻"))
    val b = item("b", genres = listOf("科幻"))
    assertTrue(LocalSimilarity.rank(a, listOf(b)).isEmpty())
  }

  @Test
  fun `电影类型只部分相同时不算同系列`() {
    val a = item("a", genres = listOf("科幻", "动作"))
    val b = item("b", genres = listOf("科幻", "喜剧"))
    assertEquals(1, LocalSimilarity.rank(a, listOf(b)).size)
  }

  @Test
  fun `种子和条目都没类型时无法判同系列`() {
    assertNull(LocalSimilarity.sameSeriesOf(item("a")))
  }

  // ─── rank 整体行为 ───

  @Test
  fun `种子自己不出现在结果里`() {
    val seed = item("a", genres = listOf("科幻", "动作"))
    val candidates = listOf(seed, item("b", genres = listOf("科幻", "喜剧")))
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals(1, ranked.size)
    assertEquals("b", ranked.first().item.Id)
  }

  @Test
  fun `Id 为空的候选被丢弃`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(EmbyItem(Name = "无 ID"), item("b", genres = listOf("喜剧")))
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals(1, ranked.size)
    assertEquals("b", ranked.first().item.Id)
  }

  @Test
  fun `excludeIds 里的条目被剔除`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(item("b", genres = listOf("科幻", "动作")), item("c", genres = listOf("科幻", "犯罪")))
    val ranked = LocalSimilarity.rank(seed, candidates, excludeIds = setOf("b"))
    assertEquals(1, ranked.size)
    assertEquals("c", ranked.first().item.Id)
  }

  @Test
  fun `排除已看时看过的条目被剔除`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(
      item("b", genres = listOf("科幻", "动作"), played = true),
      item("c", genres = listOf("科幻", "犯罪")),
    )
    val ranked = LocalSimilarity.rank(seed, candidates, excludePlayed = true)
    assertEquals(1, ranked.size)
    assertEquals("c", ranked.first().item.Id)
  }

  @Test
  fun `不排除已看时看过的条目保留`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(item("b", genres = listOf("科幻", "动作"), played = true))
    assertEquals(1, LocalSimilarity.rank(seed, candidates, excludePlayed = false).size)
  }

  @Test
  fun `limit 生效`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = (1..10).map { item("c$it", genres = listOf("科幻", "动作$it")) }
    assertEquals(3, LocalSimilarity.rank(seed, candidates, limit = 3).size)
  }

  @Test
  fun `limit 小于等于零表示不限`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = (1..5).map { item("c$it", genres = listOf("科幻", "动作$it")) }
    assertEquals(5, LocalSimilarity.rank(seed, candidates, limit = 0).size)
    assertEquals(5, LocalSimilarity.rank(seed, candidates, limit = -1).size)
  }
  @Test
  fun `同分时按社区评分降序`() {
    val seed = item("a", genres = listOf("科幻"))
    // 三部与种子的类型重合度完全相同（交集 1、并集 3），只评分不同
    val candidates = listOf(
      item("low", genres = listOf("科幻", "动作", "犯罪"), rating = 5.0),
      item("high", genres = listOf("科幻", "动作", "犯罪"), rating = 9.0),
      item("mid", genres = listOf("科幻", "动作", "犯罪"), rating = 7.0),
    )
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals(listOf("high", "mid", "low"), ranked.map { it.item.Id })
  }

  @Test
  fun `评分也相同时按年份降序`() {
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(
      item("old", genres = listOf("科幻", "动作", "犯罪"), rating = 8.0, year = 1990),
      item("new", genres = listOf("科幻", "动作", "犯罪"), rating = 8.0, year = 2020),
    )
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals(listOf("new", "old"), ranked.map { it.item.Id })
  }

  @Test
  fun `同分同分同年时按 Id 升序保证全序`() {
    // 这一条不是可有可无：没有全序时两次调用的相对顺序可能变，
    // Compose 会把同一条当成复用，卡片内容就会莫名其妙换位
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(
      item("z", genres = listOf("科幻", "动作", "犯罪"), rating = 8.0, year = 2020),
      item("m", genres = listOf("科幻", "动作", "犯罪"), rating = 8.0, year = 2020),
      item("a2", genres = listOf("科幻", "动作", "犯罪"), rating = 8.0, year = 2020),
    )
    val first = LocalSimilarity.rank(seed, candidates).map { it.item.Id }
    val second = LocalSimilarity.rank(seed, candidates.reversed()).map { it.item.Id }
    assertEquals(listOf("a2", "m", "z"), first)
    assertEquals("输入顺序不同结果必须一致", first, second)
  }

  @Test
  fun `分数相同时候选项相同的能力必须能区分开`() {
    // 两条「都没元数据」的条目，四个分项全同 → 只能靠 Id 区分
    val seed = item("a", genres = listOf("科幻"))
    val candidates = listOf(item("b"), item("c"))
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals(2, ranked.size)
    assertEquals(listOf("b", "c"), ranked.map { it.item.Id })
  }

  @Test
  fun `没元数据的条目排在有元数据的后面`() {
    // 权重设计的核心断言：本地算法绝不能把「查无此片」的条目顶到前面
    val seed = item("a", genres = listOf("科幻", "动作"), year = 2020, rating = 8.0)
    val candidates = listOf(
      item("bare"),
      item("rich", genres = listOf("科幻", "动作", "犯罪"), year = 2020, rating = 8.0),
    )
    val ranked = LocalSimilarity.rank(seed, candidates)
    assertEquals("rich", ranked.first().item.Id)
  }

  // ─── 分项与理由 ───

  @Test
  fun `分项权重之和等于总分`() {
    val seed = item("a", genres = listOf("科幻", "动作"), year = 2019, rating = 8.0, people = listOf("p1" to "甲"))
    val candidate = item("b", genres = listOf("科幻", "犯罪"), year = 2017, rating = 7.5, people = listOf("p1" to "甲"))
    val result = LocalSimilarity.score(seed, candidate)
    assertEquals(
      result.score,
      result.genrePart + result.peoplePart + result.yearPart + result.ratingPart,
      1e-9,
    )
  }

  @Test
  fun `分项不超过各自权重上限`() {
    val seed = item("a", genres = listOf("科幻", "动作"), year = 2020, rating = 8.0, people = listOf("p1" to "甲"))
    val same = item("b", genres = listOf("科幻", "动作"), year = 2020, rating = 8.0, people = listOf("p1" to "甲"))
    val result = LocalSimilarity.score(seed, same)
    assertTrue(result.genrePart in 0.0..0.45 + 1e-9)
    assertTrue(result.peoplePart in 0.0..0.25 + 1e-9)
    assertTrue(result.yearPart in 0.0..0.20 + 1e-9)
    assertTrue(result.ratingPart in 0.0..0.10 + 1e-9)
    assertTrue("完全相同应该接近满分", result.score > 0.99)
  }

  @Test
  fun `理由只取贡献最大的那一项`() {
    val seed = item("a", genres = listOf("科幻"), year = 2020, rating = 8.0)
    val candidate = item("b", genres = listOf("科幻"), year = 2020, rating = 8.0)
    assertEquals("同类题材", LocalSimilarity.score(seed, candidate).reason())
  }

  @Test
  fun `阵容完全重合时理由是同班阵容`() {
    val cast = listOf("p1" to "甲")
    val seed = item("a", genres = listOf("科幻"), year = 2020, people = cast)
    // 类型只对一半（把类型贡献压下去），让阵容成为最大项
    val candidate = item(
      "b",
      genres = listOf("科幻", "动作", "喜剧", "爱情"),
      year = 2020,
      people = cast,
    )
    assertEquals("同班阵容", LocalSimilarity.score(seed, candidate).reason())
  }

  @Test
  fun `全部零分时没有理由可写`() {
    val seed = item("a")
    val candidate = item("b")
    val result = LocalSimilarity.score(seed, candidate)
    assertEquals(0.0, result.score, 1e-9)
    // 服务端算法也会走这个构造：分数全 0 ⇒ 不显示理由，而不是编一个
    assertNull(result.reason())
  }

  // ─── 枚举 ───

  @Test
  fun `默认算法是服务端`() {
    assertEquals(SimilarAlgorithm.EMBY, SimilarAlgorithm.Default)
  }

  @Test
  fun `算法枚举名就是存储值改名等于丢设置`() {
    // 偏好里存的是 `it.name`，所以常量名不能改。这里把当前名字钉住，
    // 将来有人「顺手改成更通顺的名字」时这个用例会红
    assertEquals(listOf("EMBY", "LOCAL"), SimilarAlgorithm.entries.map { it.name })
  }

  @Test
  fun `rank 对空候选池返回空列表而不是抛异常`() {
    val seed = item("a", genres = listOf("科幻"))
    val ranked: List<SimilarityBreakdown> = LocalSimilarity.rank(seed, emptyList())
    assertNotNull(ranked)
    assertTrue(ranked.isEmpty())
  }
}
