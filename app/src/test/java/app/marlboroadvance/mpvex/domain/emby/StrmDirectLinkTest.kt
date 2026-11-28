package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StrmDirectLink] 的纯函数规则：判错会让开关一开就全体 strm 播不了。 */
class StrmDirectLinkTest {

  @Test
  fun `识别 strm 路径，忽略大小写和查询串`() {
    assertTrue(StrmDirectLink.isStrmPath("/media/movie.strm"))
    assertTrue(StrmDirectLink.isStrmPath("C:\\media\\movie.STRM"))
    assertTrue(StrmDirectLink.isStrmPath("/media/movie.strm?token=1"))
    assertTrue(StrmDirectLink.isStrmPath("  \"movie.strm\"  "))
  }

  @Test
  fun `非 strm 路径一律不识别`() {
    assertFalse(StrmDirectLink.isStrmPath(null))
    assertFalse(StrmDirectLink.isStrmPath(""))
    assertFalse(StrmDirectLink.isStrmPath("/media/movie.mkv"))
    assertFalse(StrmDirectLink.isStrmPath("/media/movie.strm.mkv"))
  }

  @Test
  fun `条目路径或容器都能识别 strm`() {
    assertTrue(StrmDirectLink.isStrmMedia("/media/movie.strm", "mp4"))
    assertTrue(StrmDirectLink.isStrmMedia("/media/movie", "STRM"))
    assertTrue(StrmDirectLink.isStrmMedia(null, " strm "))
    assertFalse(StrmDirectLink.isStrmMedia("/media/movie.mkv", "mkv"))
    assertFalse(StrmDirectLink.isStrmMedia(null, null))
  }

  @Test
  fun `只接受 http 和 https 直链`() {
    assertEquals(
      "https://cdn.example.com/a.mp4",
      StrmDirectLink.directUrl("  https://cdn.example.com/a.mp4  "),
    )
    assertEquals(
      "http://cdn.example.com/a.mp4",
      StrmDirectLink.directUrl("\"http://cdn.example.com/a.mp4\""),
    )
    assertNull(StrmDirectLink.directUrl("rtsp://cdn.example.com/a.mp4"))
    assertNull(StrmDirectLink.directUrl("mms://cdn.example.com/a.mp4"))
    assertNull(StrmDirectLink.directUrl("/media/movie.strm"))
    assertNull(StrmDirectLink.directUrl(""))
    assertNull(StrmDirectLink.directUrl(null))
  }

  @Test
  fun `请求头始终把 User-Agent 放在第一位`() {
    assertArrayEquals(
      arrayOf("User-Agent", "mpvex/1.0"),
      StrmDirectLink.headerArray(null, "mpvex/1.0"),
    )
    assertArrayEquals(
      arrayOf("User-Agent", "CustomUA", "Referer", "https://pan.example.com/"),
      StrmDirectLink.headerArray(
        linkedMapOf(
          "Referer" to "https://pan.example.com/",
          "user-agent" to "CustomUA",
        ),
        "mpvex/1.0",
      ),
    )
    // 空值丢掉；大小写不同的 UA 不重复追加
    assertArrayEquals(
      arrayOf("User-Agent", "CustomUA"),
      StrmDirectLink.headerArray(
        linkedMapOf(
          "USER-AGENT" to "CustomUA",
          "Cookie" to " ",
        ),
        "mpvex/1.0",
      ),
    )
  }
}
