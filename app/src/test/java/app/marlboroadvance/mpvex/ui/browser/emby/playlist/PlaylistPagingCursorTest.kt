package app.marlboroadvance.mpvex.ui.browser.emby.playlist

import app.marlboroadvance.mpvex.domain.emby.EmbyItemsResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaylistPagingCursor] 的单测。
 *
 * 这段逻辑原先埋在 ViewModel 里（依赖 Android `Application` + Koin，跑不了 JVM），
 * 也就是**最容易出错的偏移量计算完全没有测试保护**。抽成纯类后直接测它。
 *
 * 重点场景：`consumed`（服务端已消费）与 `items.size`（本地去重后）不等时，
 * 翻页仍能推进到底。
 */
class PlaylistPagingCursorTest {

  private fun item(id: String) = EmbyItem(Id = id, Name = "片 $id")

  private fun page(ids: List<String>, total: Int = 0): EmbyItemsResult =
    EmbyItemsResult(Items = ids.map(::item), TotalRecordCount = total)

  @Test
  fun `首屏只取一页`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 25))
    assertEquals(3, c.items.size)
    assertEquals(3, c.consumed)
    assertTrue(c.hasMore)
  }

  @Test
  fun `下一页的游标是服务端已消费的条数而不是去重后的条数`() {
    // 本测试类的核心用例。播放列表里同一集加两次是常态。
    // 第一页 5 条（1..5）；第二页服务端又给了 10 条（1..10），其中 5 条是重复的。
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3", "4", "5"), total = 30))
    c.accept(page(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), total = 30))

    assertEquals("去重后只有 10 条（5 + 净增 5）", 10, c.items.size)
    // ⚠️ 这就是关键：本地 10 条、服务端已消费 15 个位置，两者是不同的数。
    // 拿 10 当 StartIndex ⇒ 下一页会拉回 11..20 里的老区间，永远到不了底。
    assertEquals("但服务端已消费 15 个位置", 15, c.consumed)

    // 第三页：服务端从 15 开始给（6..15），其中 6..10 已见过 ⇒ 净增 5
    c.accept(page(listOf("6", "7", "8", "9", "10", "11", "12", "13", "14", "15"), total = 30))
    assertEquals("consumed 按 10 递增而不是按净增的 5", 25, c.consumed)
    assertEquals(15, c.items.size)
  }

  @Test
  fun `整页都是重复项时判到底`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 100))
    // 服务端在原地打转：返回的全是已经见过的
    c.accept(page(listOf("1", "2", "3"), total = 100))
    assertFalse("整页无新条目 ⇒ 不该继续翻", c.hasMore)
    assertTrue("并且要记下是卡住了而不是真到底", c.stalled)
  }

  @Test
  fun `真到底时不算卡住`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 3))
    assertFalse(c.hasMore)
    assertFalse(c.stalled)
  }

  @Test
  fun `空页直接判到底`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(emptyList(), total = 100))
    assertTrue(c.items.isEmpty())
    assertFalse(c.hasMore)
    assertFalse(c.stalled)
  }

  @Test
  fun `服务端不给总数时按本页是否不满判断到底`() {
    // 25 条 / 每页 10 ⇒ 需要两次翻页才不满
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page((1..10).map { "$it" }))
    assertTrue(c.hasMore)
    c.accept(page((11..20).map { "$it" }))
    assertTrue(c.hasMore)
    c.accept(page((21..25).map { "$it" }))
    assertFalse("25 条，第三页不满 ⇒ 到底", c.hasMore)
    assertEquals(25, c.items.size)
  }

  @Test
  fun `服务端给总数时按总数判断到底而不是按不满判断`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page((1..10).map { "$it" }, total = 15))
    assertTrue(c.hasMore)
    c.accept(page((11..15).map { "$it" }, total = 15))
    assertFalse("consumed 15 >= total 15 ⇒ 到底", c.hasMore)
  }

  @Test
  fun `首屏内的重复项也会被去掉`() {
    // 否则 LazyColumn 的 key 会重复而崩掉（同 PagedList 的历史问题）
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "1", "2"), total = 3))
    assertEquals(2, c.items.size)
    // 服务端给了 3 条，consumed 照实记 3
    assertEquals(3, c.consumed)
  }

  @Test
  fun `Id 为 null 的条目按对象本身去重不会互相误杀`() {
    // 服务端偶尔返回没有 Id 的条目；用对象身份当 key 时两个不同的 null-Id
    // 条目不该被当成同一个。
    val a = EmbyItem(Id = null, Name = "A")
    val b = EmbyItem(Id = null, Name = "B")
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(EmbyItemsResult(Items = listOf(a, b), TotalRecordCount = 2))
    assertEquals(2, c.items.size)
  }

  @Test
  fun `追加失败后不会把已到底的标记设上`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 100))
    assertTrue(c.hasMore)
    // 模拟 ViewModel 在追加失败时调的回滚
    c.markAppendFailed()
    assertTrue("网络失败 ≠ 没有下一页，必须还能继续拉", c.hasMore)
  }

  @Test
  fun `reset 之后游标归零能从头再来`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2"), total = 100))
    c.reset()
    assertEquals(0, c.consumed)
    assertTrue(c.items.isEmpty())
    assertTrue(c.hasMore)
    assertFalse(c.stalled)
    c.accept(page(listOf("9", "8"), total = 100))
    assertEquals(listOf("9", "8"), c.items.map { it.Id })
  }

  @Test
  fun `replaceItems 改写列表后下一页去重要用新列表`() {
    // 回归用例：删除/重排走 replaceItems，若漏掉，下一页去重会拿旧列表当 seen，
    // 把刚删掉的条目「补」回来。
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 30))
    c.replaceItems(c.items.filterNot { it.Id == "2" })
    assertEquals(listOf("1", "3"), c.items.map { it.Id })

    c.accept(page(listOf("4", "5", "6"), total = 30))
    assertEquals("被删的 2 不该复活", listOf("1", "3", "4", "5", "6"), c.items.map { it.Id })
    assertEquals("consumed 不受 replaceItems 影响", 6, c.consumed)
  }

  @Test
  fun `replaceItems 不该动 consumed 与 hasMore`() {
    val c = PlaylistPagingCursor(pageSize = 10)
    c.accept(page(listOf("1", "2", "3"), total = 100))
    val consumedBefore = c.consumed
    val hasMoreBefore = c.hasMore
    c.replaceItems(c.items.reversed())
    assertEquals(consumedBefore, c.consumed)
    assertEquals(hasMoreBefore, c.hasMore)
  }

  @Test
  fun `每页都有重复也能靠 consumed 精确推进到底`() {
    // 综合用例：服务端总数 40（含 15 条重复位置，本地只认 25 个不重复条目），
    // 每页都给 10 条。consumed 必须按 10 递增，第四页正好 40 == TotalRecordCount。
    // 若错用 items.size 当游标，这里会永远拉不到底。
    val total = 40
    val c = PlaylistPagingCursor(pageSize = 10)

    c.accept(page((1..10).map { "$it" }, total = total))
    assertEquals(10, c.items.size)
    assertEquals(10, c.consumed)
    assertTrue(c.hasMore)

    c.accept(page((6..15).map { "$it" }, total = total))   // 6..10 重复
    assertEquals(15, c.items.size)
    assertEquals(20, c.consumed)
    assertTrue(c.hasMore)

    c.accept(page((11..20).map { "$it" }, total = total))  // 11..15 重复
    assertEquals(20, c.items.size)
    assertEquals(30, c.consumed)
    assertTrue(c.hasMore)

    c.accept(page((16..25).map { "$it" }, total = total))  // 16..20 重复
    assertEquals("本地只认 25 个不重复条目", 25, c.items.size)
    assertEquals("consumed 精确等于服务端总数", total, c.consumed)
    assertFalse(c.hasMore)
  }
}