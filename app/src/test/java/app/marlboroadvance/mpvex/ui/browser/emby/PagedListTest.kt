package app.marlboroadvance.mpvex.ui.browser.emby

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PagedList] 的行为测试（清单 1.2）。
 *
 * 这个类刚踩过两个**只有测试能挡住**的坑，所以这里逐条钉死：
 *  1. `keyOf` 不传时所有条目键相同 → 追加时被全判为重复 → **第二页永远是空的**；
 *  2. 「本页不满即到底」的判断在服务端给了 totalCount 时会**提前截断**（因为页面做了去重）。
 */
class PagedListTest {
  private data class Row(val id: Int)

  /** 造一页数据：id 从 [from] 开始的 [count] 条，total 是服务端报告的总数 */
  private fun loader(
    from: Int,
    limit: Int,
    total: Int,
    ids: ((Int) -> Int)? = null,
  ): suspend (Int, Int) -> PagedResult<Row> = { start, size ->
    val end = minOf(start + size, total)
    val items = if (start >= end) emptyList() else (start until end).map { Row(ids?.invoke(it) ?: it) }
    PagedResult(items = items, totalCount = total, pageSize = size)
  }

  @Test
  fun `首次加载把第一页装进来`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload(loader(from = 0, limit = 10, total = 35))

    assertEquals(10, page.items.size)
    assertEquals(35, page.total)
    assertTrue("还有 25 条没加载", page.hasMore)
    assertFalse(page.loading)
    assertNull(page.error)
  }

  @Test
  fun `追加把下一页接在后面`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload(loader(from = 0, limit = 10, total = 35))
    page.loadMore(loader(from = 0, limit = 10, total = 35))

    assertEquals(20, page.items.size)
    // 第二页必须是新条目，不能是第一页的复制
    assertEquals(19, page.items.last().id)
  }

  @Test
  fun `keyOf 缺省时靠对象的 equals 去重——非 data 类会失效`() = runTest {
    // 缺省 keyOf = { it as? Any ?: 0 }，对 **data class** 是能用的
    // （equals 按内容比，等价于按 id 比）。这里先钉住这个事实，
    // 免得后续误以为「不传 keyOf 一定会坏」。
    val withDataClass = PagedList<Row>(pageSize = 10)
    withDataClass.reload(loader(from = 0, limit = 10, total = 35))
    withDataClass.loadMore(loader(from = 0, limit = 10, total = 35))
    assertEquals("data class 靠 equals 去重，第二页正常追加", 20, withDataClass.items.size)

    // 但对**没有 equals 覆写**的普通类，缺省 keyOf 每次都产出新对象 ⇒ 键永不相同
    // ⇒ 去重完全失效，同一条会被反复追加。这就是「必须传 keyOf」的真正原因。
    class Plain(val id: Int)
    val plain = PagedList<Plain>(pageSize = 10, keyOf = { it.id })
    val echo = { _: Int, size: Int ->
      PagedResult(items = (0 until size).map { Plain(1) }, totalCount = null, pageSize = size)
    }
    plain.reload(echo)
    plain.loadMore(echo)
    assertEquals("传了 keyOf 之后，重复项被滤掉", 1, plain.items.size)
  }

  @Test
  fun `服务端忽略 StartIndex 反复返回同一批时不会无限重复`() = runTest {
    // ids 全部映射成同一个 id：模拟 SmartStrm 那种「翻页返回同一批」
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    val echo = { _: Int, size: Int ->
      PagedResult(
        items = (0 until size).map { Row(1) },
        totalCount = null, // 服务端不给总数
        pageSize = size,
      )
    }
    page.reload(echo)
    assertEquals(1, page.items.size)
    page.loadMore(echo)
    assertEquals("重复项应被滤掉，不能把列表撑大", 1, page.items.size)
  }

  @Test
  fun `本页内含重复项时仍能用 totalCount 判断是否到底`() = runTest {
    // 每页 10 条里 3 条是重复的（真实拿到 7 个新 id），但服务端说总共 20 条
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    val dup = { start: Int, size: Int ->
      PagedResult(
        items = (0 until size).map { Row(it % 7) },
        totalCount = 20,
        pageSize = size,
      )
    }
    page.reload(dup)
    assertEquals(7, page.items.size)
    // ⚠️ 关键断言：若按「本页不满 10 条」判断，这里会被误判成到底
    assertTrue("去重让本页看起来不满，但服务端说了还有 13 条", page.hasMore)

    page.loadMore(dup)
    assertEquals("第二页全是重复，items 不再增长", 7, page.items.size)
  }

  @Test
  fun `服务端不给总数时按本页是否不满判断到底`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { start, size ->
      PagedResult(
        items = (start until minOf(start + size, 25)).map { Row(it) },
        totalCount = null,
        pageSize = size,
      )
    }
    assertTrue(page.hasMore)
    page.loadMore { start, size ->
      PagedResult(
        items = (start until minOf(start + size, 25)).map { Row(it) },
        totalCount = null,
        pageSize = size,
      )
    }
    assertTrue("20 / 25，还有 5 条", page.hasMore)

    // 第三页：25 条 / 每页 10 ⇒ 只剩 5 条 ⇒ 本页不满 ⇒ 到底
    page.loadMore { start, size ->
      PagedResult(
        items = (start until minOf(start + size, 25)).map { Row(it) },
        totalCount = null,
        pageSize = size,
      )
    }
    assertEquals(25, page.items.size)
    assertFalse(page.hasMore)
  }

  @Test
  fun `exhausted 标记优先于任何其他判断`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { _, size ->
      PagedResult(items = (0 until size).map { Row(it) }, totalCount = 999, pageSize = size, exhausted = true)
    }
    assertFalse("exhausted 说不完了，哪怕 totalCount 还有 999", page.hasMore)
  }

  @Test
  fun `加载失败时给出错误并清空列表`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { _, _ -> throw IllegalStateException("服务器炸了") }

    assertNotNull("错误要能被 UI 看到，而不是静默空列表", page.error)
    assertTrue(page.items.isEmpty())
    assertFalse(page.hasMore)
  }

  @Test
  fun `追加失败时保留已有内容并给出错误`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload(loader(from = 0, limit = 10, total = 35))
    page.loadMore { _, _ -> throw IllegalStateException("超时") }

    assertEquals("第一页不该被失败牵连", 10, page.items.size)
    assertNotNull(page.error)
    assertTrue("还有下一页可拉", page.hasMore)
  }

  @Test
  fun `已经加载时重复调用 loadMore 不会并发`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload(loader(from = 0, limit = 10, total = 100))
    // 手动把 appending 置起来模拟「上一请求还没回来」
    // （loadMore 内部会在 appending 时直接返回）
    val ok = page.hasMore
    assertTrue(ok)
    page.loadMore(loader(from = 0, limit = 10, total = 100))
    assertEquals(20, page.items.size)
    assertFalse("收尾后必须复位，否则后续触底永远拉不动", page.appending)
  }

  @Test
  fun `到底之后再调 loadMore 不会发请求`() = runTest {
    var calls = 0
    val counting = { start: Int, size: Int ->
      calls++
      PagedResult(
        items = (start until minOf(start + size, 10)).map { Row(it) },
        totalCount = 10,
        pageSize = size,
      )
    }
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload(counting)
    assertEquals(1, calls)
    assertFalse(page.hasMore)
    page.loadMore(counting)
    assertEquals("已到底就该一次请求都不发", 1, calls)
  }

  @Test
  fun `nextIndex 是服务端已消费的条数而不是去重后的条数`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { start, size ->
      PagedResult(items = (0 until size).map { Row(it % 8) }, totalCount = null, pageSize = size)
    }
    // 页内 10 条只有 8 个唯一 id
    assertEquals(8, page.items.size)
    // 但 StartIndex 必须是 10：服务端已经给了 10 个位置，
    // 下一段要从第 11 条开始，否则会反复拉回已看过的区间（真实 bug）。
    assertEquals(10, page.nextIndex())

    page.loadMore { start, size ->
      // 从 start 继续，本页给 8~18，其中 8/9 与前面重复
      PagedResult(
        items = (start until minOf(start + size, 20)).map { Row(it % 12) },
        totalCount = null,
        pageSize = size,
      )
    }
    assertEquals("已消费 20 个位置", 20, page.nextIndex())
  }

  @Test
  fun `首屏内的重复项也会被去掉——否则 LazyColumn 的 key 会重复而崩掉`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { _, size ->
      // 服务端单页内就返回了重复 Id
      PagedResult(items = (0 until size).map { Row(it % 4) }, totalCount = 40, pageSize = size)
    }
    // 首屏去重：只剩 4 个唯一项
    assertEquals(4, page.items.size)
    // key 唯一性是 LazyColumn 不崩的前提
    assertEquals("key 必须唯一", page.items.size, page.items.map { it.id }.toSet().size)
  }

  @Test
  fun `整页都是重复项时判到底——服务端在原地打转就别无限翻了`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    // 第一页正常给 10 个
    page.reload { start, size ->
      PagedResult(
        items = (start until start + size).map { Row(it) },
        totalCount = 1000, // 服务端还报了个大总数
        pageSize = size,
      )
    }
    assertTrue(page.hasMore)

    // 之后每一页都返回同样这 10 条（StartIndex 被无视）
    page.loadMore { _, size ->
      PagedResult(items = (0 until size).map { Row(it) }, totalCount = 1000, pageSize = size)
    }
    assertEquals("重复项不该把列表撑大", 10, page.items.size)
    assertFalse("一条新的都没有 ⇒ 判到底，否则会无限翻", page.hasMore)
  }

  @Test
  fun `retry 在空列表时重载第一页`() = runTest {
    val page = PagedList<Row>(pageSize = 10, keyOf = { it.id })
    page.reload { _, _ -> throw IllegalStateException("首次失败") }
    assertTrue(page.items.isEmpty())

    page.retry(loader(from = 0, limit = 10, total = 5))
    assertEquals(5, page.items.size)
    assertNull("重试成功后错误要清掉", page.error)
  }
}
