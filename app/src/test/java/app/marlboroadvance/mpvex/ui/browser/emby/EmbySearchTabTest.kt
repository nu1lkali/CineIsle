package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbySearchTab] 的分类逻辑测试。
 *
 * 这个类是**故意抽成纯 Kotlin** 的（不碰 Compose、不碰网络），
 * 所以分类规则能被 JVM 单测盖住 —— 清单 4.1 的搜索分页签一旦分类错了，
 * 用户会看到「明明有结果却点进去是空的」，这种 bug 在真机上很难靠肉眼发现。
 */
class EmbySearchTabTest {

  private fun item(type: String?, id: String = type ?: "x") = EmbyItem(Id = id, Name = id, Type = type)

  // ── classify ──

  @Test
  fun `已知的 5 种类型各自归到自己的页签`() {
    assertEquals(EmbySearchTab.MOVIE, EmbySearchTab.classify(item("Movie")))
    assertEquals(EmbySearchTab.SERIES, EmbySearchTab.classify(item("Series")))
    assertEquals(EmbySearchTab.EPISODE, EmbySearchTab.classify(item("Episode")))
    assertEquals(EmbySearchTab.BOXSET, EmbySearchTab.classify(item("BoxSet")))
    assertEquals(EmbySearchTab.PERSON, EmbySearchTab.classify(item("Person")))
  }

  @Test
  fun `Type 大小写不同也归到同一个页签`() {
    // 实测 Emby 不同版本 / 不同插件下 Type 大小写不一致，必须 ignoreCase
    assertEquals(EmbySearchTab.MOVIE, EmbySearchTab.classify(item("movie")))
    assertEquals(EmbySearchTab.BOXSET, EmbySearchTab.classify(item("boxset")))
    assertEquals(EmbySearchTab.PERSON, EmbySearchTab.classify(item("PERSON")))
  }

  @Test
  fun `Type 前后有空白也能正确归类`() {
    assertEquals(EmbySearchTab.SERIES, EmbySearchTab.classify(item("  Series  ")))
  }

  @Test
  fun `Type 为 null 归到其他而不是全部`() {
    // ALL 只是 UI 上「不分类」的代表，classify 永远不该返回它 ——
    // 否则「其他」页签的角标会把没有 Type 的条目算进去，切过去却是空的。
    assertEquals(EmbySearchTab.OTHER, EmbySearchTab.classify(item(null)))
    assertEquals(EmbySearchTab.OTHER, EmbySearchTab.classify(item("")))
    assertEquals(EmbySearchTab.OTHER, EmbySearchTab.classify(item("   ")))
  }

  @Test
  fun `没预料到的类型归到其他而不是被丢掉`() {
    // MusicVideo / Video / Recording / Program / Book …… 都属于这一类
    assertEquals(EmbySearchTab.OTHER, EmbySearchTab.classify(item("MusicVideo")))
    assertEquals(EmbySearchTab.OTHER, EmbySearchTab.classify(item("Book")))
  }

  // ── countByTab ──

  @Test
  fun `角标只统计具体的 5 种类型`() {
    val items = listOf(
      item("Movie", "m1"), item("Movie", "m2"),
      item("Series", "s1"),
      item("Person", "p1"), item("Person", "p2"), item("Person", "p3"),
      item("MusicVideo", "mv1"),
      item(null, "none"),
    )
    val counts = EmbySearchTab.countByTab(items)
    assertEquals(2, counts[EmbySearchTab.MOVIE])
    assertEquals(1, counts[EmbySearchTab.SERIES])
    assertEquals(3, counts[EmbySearchTab.PERSON])
    // 没出现的类型不该占位（UI 用 ?: 0 兜底，但 map 里多一个 0 会让显隐逻辑误判）
    assertEquals(null, counts[EmbySearchTab.EPISODE])
    assertEquals(null, counts[EmbySearchTab.BOXSET])
  }

  @Test
  fun `角标不含全部也不含其他`() {
    val counts = EmbySearchTab.countByTab(listOf(item("Movie"), item("MusicVideo"), item(null)))
    assertTrue("ALL 不该在角标 map 里，ALL 的角标是总数", EmbySearchTab.ALL !in counts)
    assertTrue("OTHER 不设独立页签，不该在角标 map 里", EmbySearchTab.OTHER !in counts)
  }

  @Test
  fun `空结果的角标是空 map`() {
    assertTrue(EmbySearchTab.countByTab(emptyList()).isEmpty())
  }

  @Test
  fun `角标 map 的键都是 TYPED 里出现过的`() {
    // 注意这里**不**断言 map 的迭代顺序等于 TYPED 顺序：
    // countByTab 是按「结果里遇到的先后」往 LinkedHashMap 里塞的，顺序不保证等于 TYPED。
    // 页签在屏幕上的排列由 EmbySearchTabRow 里的 TYPED.forEach 决定，与 map 的顺序无关。
    // 这里只保证「map 的键不会跑到 TYPED 之外」—— 那是 UI 显隐逻辑正确的前提。
    val items = listOf(item("Person"), item("Movie"), item("Series"), item("Episode"), item("BoxSet"))
    val keys = EmbySearchTab.countByTab(items).keys
    assertTrue("ALL/OTHER 不该出现在角标里", EmbySearchTab.ALL !in keys && EmbySearchTab.OTHER !in keys)
    assertEquals(EmbySearchTab.TYPED.toSet(), keys)
  }

  @Test
  fun `同一个类型散落在结果各处也只算进同一个页签`() {
    val items = listOf(
      item("Person", "p1"), item("Movie", "m1"), item("Person", "p2"),
      item("Series", "s1"), item("Movie", "m2"), item("Person", "p3"),
    )
    val counts = EmbySearchTab.countByTab(items)
    assertEquals(2, counts[EmbySearchTab.MOVIE])
    assertEquals(3, counts[EmbySearchTab.PERSON])
    assertEquals(1, counts[EmbySearchTab.SERIES])
    // 总数守恒：角标加起来 + OTHER 的条数 == 结果总数
    val typed = counts.values.sum()
    val other = EmbySearchTab.itemsFor(items, EmbySearchTab.OTHER).size
    assertEquals(items.size, typed + other)
  }

  // ── itemsFor ──

  @Test
  fun `全部页签原样返回同一个 List 实例`() {
    // UI 会把整个列表传进 LazyVerticalGrid，少一次 120 条量级的数组分配
    val items = listOf(item("Movie"), item("Person"), item("MusicVideo"))
    assertSame(items, EmbySearchTab.itemsFor(items, EmbySearchTab.ALL))
  }

  @Test
  fun `切页签只留下该类型的条目`() {
    val items = listOf(
      item("Movie", "m1"), item("Series", "s1"), item("Movie", "m2"), item("Person", "p1"),
    )
    assertEquals(listOf("m1", "m2"), EmbySearchTab.itemsFor(items, EmbySearchTab.MOVIE).map { it.Id })
    assertEquals(listOf("s1"), EmbySearchTab.itemsFor(items, EmbySearchTab.SERIES).map { it.Id })
    assertEquals(listOf("p1"), EmbySearchTab.itemsFor(items, EmbySearchTab.PERSON).map { it.Id })
  }

  @Test
  fun `其他页签能拿到所有不认识的类型`() {
    val items = listOf(item("Movie"), item("MusicVideo", "mv"), item(null, "none"), item("Book", "bk"))
    assertEquals(
      listOf("mv", "none", "bk"),
      EmbySearchTab.itemsFor(items, EmbySearchTab.OTHER).map { it.Id },
    )
  }

  @Test
  fun `切页签不改变原列表`() {
    val items = mutableListOf(item("Movie"), item("Person"))
    EmbySearchTab.itemsFor(items, EmbySearchTab.MOVIE)
    assertEquals(2, items.size)
  }

  @Test
  fun `5 种类型的条目加起来等于总数减去其他`() {
    // 「全部」里看到的每一条，都应该能在某个页签里找到（OTHER 也是页签，只是不在角标里）
    val items = listOf(
      item("Movie", "m1"), item("Series", "s1"), item("Episode", "e1"),
      item("BoxSet", "b1"), item("Person", "p1"), item("MusicVideo", "x1"), item(null, "x2"),
    )
    val typed = EmbySearchTab.TYPED.sumOf { EmbySearchTab.itemsFor(items, it).size }
    val other = EmbySearchTab.itemsFor(items, EmbySearchTab.OTHER).size
    assertEquals(items.size, typed + other)
  }

  // ── shouldShowBadge ──

  @Test
  fun `0 条的未选中页签不显示`() {
    assertTrue(!EmbySearchTab.shouldShowBadge(EmbySearchTab.MOVIE, count = 0, selected = false))
  }

  @Test
  fun `有条目的页签显示`() {
    assertTrue(EmbySearchTab.shouldShowBadge(EmbySearchTab.MOVIE, count = 3, selected = false))
  }

  @Test
  fun `0 条但正被选中的页签仍然保留`() {
    // 否则用户能点到的页签会凭空消失，点一下就把选中态弄丢
    assertTrue(EmbySearchTab.shouldShowBadge(EmbySearchTab.MOVIE, count = 0, selected = true))
  }
}
