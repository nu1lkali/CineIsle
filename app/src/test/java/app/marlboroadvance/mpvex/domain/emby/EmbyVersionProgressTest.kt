package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按版本进度」的键：**播放页与详情页各自独立算一遍，必须算出同一个键**。
 *
 * 这是本功能唯一的跨页契约 —— 两边没有任何通过，一旦形状漂了就是
 * 「播了但详情页读不到」（静默失效，不会报错），所以用例把形状钉死。
 */
class EmbyVersionProgressTest {
  @Test
  fun `键由服务器 条目 版本三段拼成`() {
    assertEquals(
      "embyver:7:abc123:src-4k",
      EmbyVersionProgress.key(7L, "abc123", "src-4k"),
    )
  }

  @Test
  fun `不同版本算出的键必然不同`() {
    val a = EmbyVersionProgress.key(1L, "item", "4k")
    val b = EmbyVersionProgress.key(1L, "item", "1080p")
    assertTrue("两个版本必须各自独立", a != b)
  }

  @Test
  fun `不同条目 不同服务器互不串味`() {
    assertTrue(
      EmbyVersionProgress.key(1L, "itemA", "src") !=
        EmbyVersionProgress.key(1L, "itemB", "src"),
    )
    assertTrue(
      EmbyVersionProgress.key(1L, "item", "src") !=
        EmbyVersionProgress.key(2L, "item", "src"),
    )
  }

  @Test
  fun `条目前缀能覆盖该条目下所有版本`() {
    val prefix = EmbyVersionProgress.itemPrefix(3L, "movie")
    assertEquals("embyver:3:movie:", prefix)
    assertTrue(EmbyVersionProgress.key(3L, "movie", "4k").startsWith(prefix))
    assertTrue(EmbyVersionProgress.key(3L, "movie", "1080p").startsWith(prefix))
    // 别的条目不能被这个前缀捞到（否则详情页会误判成「记过进度」）
    assertFalse(EmbyVersionProgress.key(3L, "movie2", "4k").startsWith(prefix))
    // 别的服务器同理
    assertFalse(EmbyVersionProgress.key(4L, "movie", "4k").startsWith(prefix))
  }

  @Test
  fun `前缀不能命中普通媒体的标识`() {
    // 本地文件 / 网络流的进度键（"片名_pathHash" / "片名_uriHash"）绝不能被认成按版本进度
    assertFalse(EmbyVersionProgress.isVersionKey("Some Movie_12345"))
    assertFalse(EmbyVersionProgress.isVersionKey("network_3_98765"))
    assertTrue(EmbyVersionProgress.isVersionKey(EmbyVersionProgress.key(1L, "i", "s")))
  }
}
