package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emby 错误归类（清单 1.2，测的是 1.3 网络层收口的产物）。
 *
 * 归类错了的后果很具体：把 401 当成「服务器出错」⇒ 用户看到「稍后重试」，
 * 永远等不到该做的「重新登录」；把断网当成「请求失败」⇒ 用户以为是 App 的 bug。
 * 所以这张映射表必须钉死。
 */
class EmbyErrorKindTest {
  @Test
  fun `没有响应码一律当网络问题`() {
    // 0 是本项目自定义的「没拿到响应码」；负数来自某些 OkHttp 异常的约定
    assertEquals(EmbyErrorKind.NETWORK, EmbyErrorKind.of(0))
    assertEquals(EmbyErrorKind.NETWORK, EmbyErrorKind.of(-1))
    assertEquals(EmbyErrorKind.NETWORK, EmbyErrorKind.of(-1001))
  }

  @Test
  fun `401 与 403 归鉴权失败`() {
    assertEquals(EmbyErrorKind.AUTH, EmbyErrorKind.of(401))
    assertEquals(EmbyErrorKind.AUTH, EmbyErrorKind.of(403))
  }

  @Test
  fun `404 归资源不存在`() {
    assertEquals(EmbyErrorKind.NOT_FOUND, EmbyErrorKind.of(404))
  }

  @Test
  fun `5xx 归服务器出错`() {
    listOf(500, 502, 503, 504, 599).forEach {
      assertEquals("HTTP $it 应归 SERVER", EmbyErrorKind.SERVER, EmbyErrorKind.of(it))
    }
    assertEquals(
      "600 不在 5xx 范围",
      EmbyErrorKind.UNKNOWN,
      EmbyErrorKind.of(600),
    )
  }

  @Test
  fun `其余客户端错误归未知`() {
    listOf(400, 402, 405, 409, 418, 429).forEach {
      assertEquals("HTTP $it 应归 UNKNOWN", EmbyErrorKind.UNKNOWN, EmbyErrorKind.of(it))
    }
  }

  @Test
  fun `异常自带的 kind 与静态映射一致`() {
    assertEquals(EmbyErrorKind.AUTH, EmbyApiException(401, "").errorKind)
    assertEquals(EmbyErrorKind.SERVER, EmbyApiException(500, "").errorKind)
    assertEquals(EmbyErrorKind.NETWORK, EmbyApiException(0, "").errorKind)
  }

  @Test
  fun `错误消息是人话且带上原始 body`() {
    val e = EmbyApiException(401, """{"Message":"Unauthorized"}""")
    val msg = e.message.orEmpty()
    assertTrue("应说明是鉴权问题：$msg", msg.contains("鉴权失败"))
    assertTrue("应带响应码：$msg", msg.contains("401"))
    assertTrue("应保留原始 body 供排障：$msg", msg.contains("Unauthorized"))
  }

  @Test
  fun `body 为空时消息仍然完整`() {
    val msg = EmbyApiException(503, "").message.orEmpty()
    assertTrue(msg.contains("服务器出错"))
    assertTrue(msg.contains("503"))
    assertTrue("不该出现悬空的冒号", !msg.contains("："))
  }

  @Test
  fun `超长 body 被截断到 120 字`() {
    val long = "错".repeat(1000)
    val msg = EmbyApiException(500, long).message.orEmpty()
    assertTrue("消息长度应被控制：${msg.length}", msg.length < 200)
  }
}
