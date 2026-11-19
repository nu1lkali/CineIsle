package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sortActors] 的回归测试。
 *
 * 三个契约一旦改坏就会静默出错：
 *  · 名称用 `SortName`（剥离 The/A 前缀），不是 Name；
 *  · 作品数缺失（还在分批统计）的演员**永远沉底**，升序时也不能顶到最前；
 *  · 随机必须由种子决定 —— 否则每次重组都洗出新顺序，列表在手指底下乱跳。
 */
class ActorSortingTest {

  private fun actor(
    id: String,
    name: String,
    sortName: String? = null,
    works: Int? = null,
  ) = EmbyItem(Id = id, Name = name, SortName = sortName, ChildCount = works, Type = "Person")

  private fun names(list: List<EmbyItem>) = list.map { it.Name }

  @Test
  fun `名称升序用 SortName 而不是 Name`() {
    val list = listOf(
      actor("1", "The Matrix", sortName = "Matrix"),
      actor("2", "Alien", sortName = "Alien"),
      actor("3", "bMovie", sortName = "bMovie"),
    )
    assertEquals(
      listOf("Alien", "bMovie", "The Matrix"),
      names(sortActors(list, ActorSort.NAME, asc = true)),
    )
  }

  @Test
  fun `名称降序是升序的镜像`() {
    val list = listOf(
      actor("1", "A"),
      actor("2", "B"),
      actor("3", "C"),
    )
    assertEquals(
      listOf("C", "B", "A"),
      names(sortActors(list, ActorSort.NAME, asc = false)),
    )
  }

  @Test
  fun `没有名字的演员沉底`() {
    val list = listOf(
      actor("1", ""),
      actor("2", "张三"),
      actor("3", "李四"),
    )
    val sorted = sortActors(list, ActorSort.NAME, asc = true)
    assertEquals("张三", sorted.first().Name)
    assertEquals("", sorted.last().Name)
  }

  @Test
  fun `作品数量降序把作品多的排前面`() {
    val list = listOf(
      actor("1", "A", works = 3),
      actor("2", "B", works = 12),
      actor("3", "C", works = 7),
    )
    assertEquals(
      listOf("B", "C", "A"),
      names(sortActors(list, ActorSort.WORKS, asc = false)),
    )
  }

  @Test
  fun `作品数量升序时缺失值仍然沉底，不会顶到最前`() {
    val list = listOf(
      actor("1", "A", works = 3),
      actor("2", "B", works = null),
      actor("3", "C", works = 1),
    )
    assertEquals(
      listOf("C", "A", "B"),
      names(sortActors(list, ActorSort.WORKS, asc = true)),
    )
  }

  @Test
  fun `作品数量相同按名称兜底，保证顺序稳定`() {
    val list = listOf(
      actor("1", "C", works = 5),
      actor("2", "A", works = 5),
      actor("3", "B", works = 5),
    )
    assertEquals(
      listOf("A", "B", "C"),
      names(sortActors(list, ActorSort.WORKS, asc = false)),
    )
  }

  @Test
  fun `随机用固定种子，同一批人得到同一顺序`() {
    val list = (1..12).map { actor("$it", "actor-$it") }
    val first = sortActors(list, ActorSort.RANDOM, asc = true, seed = 20261118)
    val second = sortActors(list, ActorSort.RANDOM, asc = true, seed = 20261118)
    assertEquals(names(first), names(second))
    // 换种子应当换一批（1..12 全排列撞回同一顺序的概率可以忽略）
    val other = sortActors(list, ActorSort.RANDOM, asc = true, seed = 20261119)
    assertTrue(names(other) != names(first))
  }

  @Test
  fun `随机排序不改变成员集合`() {
    val list = (1..9).map { actor("$it", "actor-$it") }
    val shuffled = sortActors(list, ActorSort.RANDOM, asc = true, seed = 7)
    assertEquals(
      list.map { it.Id.orEmpty() }.sorted(),
      shuffled.map { it.Id.orEmpty() }.sorted(),
    )
  }

  @Test
  fun `未知或空排序键回落到名称`() {
    assertEquals(ActorSort.NAME, ActorSort.fromKey(null))
    assertEquals(ActorSort.NAME, ActorSort.fromKey(""))
    assertEquals(ActorSort.NAME, ActorSort.fromKey("CommunityRating"))
    assertEquals(ActorSort.WORKS, ActorSort.fromKey("ChildCount"))
    assertEquals(ActorSort.RANDOM, ActorSort.fromKey("Random"))
  }

  @Test
  fun `随机没有方向，名称与作品数量有`() {
    assertTrue(ActorSort.NAME.hasDirection)
    assertTrue(ActorSort.WORKS.hasDirection)
    assertTrue(!ActorSort.RANDOM.hasDirection)
  }
}
