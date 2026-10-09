package app.marlboroadvance.mpvex.domain.iso

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/**
 * [IsoRangeProxy] 里两个字节流工具函数的单测。
 *
 * 这两个函数是「代理转CDN」这条链路最容易出错的地方，且错了**不报错、只是花屏**，
 * 所以必须单独覆盖：
 *  · [bounded] —— 保证返回给 mpv 的字节数恰好等于声明的区间长度；
 *  · [skipFully] —— 上游忽略 Range 返回全量时，跳过前缀必须准。
 */
class IsoStreamTest {

  // ── bounded ────────────────────────────────────────────────────────────

  @Test
  fun `bounded 在上游多给数据时截断到指定长度`() {
    // 上游给了 10 个字节，但我们只要 4 个
    val src = ByteArray(10) { it.toByte() }
    val got = bounded(src, 4).readAllBytes()
    assertEquals("只应返回 4 个字节", 4, got.size)
    assertArrayEquals(byteArrayOf(0, 1, 2, 3), got)
  }

  @Test
  fun `bounded 在上游给的数据不足时如实返回不足的部分`() {
    val src = ByteArray(3) { it.toByte() }
    val got = bounded(src, 10).readAllBytes()
    // 不能凭空补字节 —— 上游给多少就是多少（少的那部分会表现为播放提前结束，
    // 比给错数据容易定位）。
    assertEquals(3, got.size)
    assertArrayEquals(byteArrayOf(0, 1, 2), got)
  }

  @Test
  fun `bounded 长度为 0 时不返回任何字节`() {
    assertEquals(0, bounded(ByteArray(10) { 7 }, 0).readAllBytes().size)
  }

  @Test
  fun `bounded 逐字节读也受长度限制`() {
    val got = bounded(ByteArray(10) { it.toByte() }, 2)
    // 某些流实现只走 read()，这条路径也得守规矩
    assertEquals(0, got.read())
    assertEquals(1, got.read())
    assertEquals(-1, got.read())
  }

  @Test
  fun `bounded 不会把多余字节留在流里`() {
    val src = ByteArray(6) { it.toByte() }
    val stream = bounded(src, 2)
    val buf = ByteArray(10)
    val n = stream.read(buf, 0, 10)
    assertEquals(2, n)
    assertArrayEquals(byteArrayOf(0, 1), buf.copyOf(2))
  }

  // ── skipFully ──────────────────────────────────────────────────────────

  @Test
  fun `skipFully 跳过指定字节数`() {
    val src = ByteArray(10) { it.toByte() }
    val got = src.skipFullyOrThrow(4)
    assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9), got.readAllBytes())
  }

  @Test
  fun `skipFully 跳 0 字节时原样返回`() {
    val src = ByteArray(4) { it.toByte() }
    assertArrayEquals(byteArrayOf(0, 1, 2, 3), src.skipFullyOrThrow(0).readAllBytes())
  }

  @Test
  fun `skipFully 跳过正好全部长度`() {
    val src = ByteArray(8) { it.toByte() }
    assertEquals(0, src.skipFullyOrThrow(8).readAllBytes().size)
  }

  @Test(expected = IOException::class)
  fun `skipFully 数据不够时抛异常而不是返回短流`() {
    // ⚠️ 这是刻意的行为：跳不完还继续返回，读到的就是错位的字节，
    // 表现是花屏 —— 宁可报错也不要静默给错数据。
    val src = ByteArray(4) { it.toByte() }
    src.skipFullyOrThrow(100)
  }

  // ── 组合：模拟「上游忽略 Range 返回全量」的真实场景 ──────────────────

  @Test
  fun `上游全量返回时跳过前缀再截断 得到恰好目标区间`() {
    // 模拟一份 200 字节的 ISO，上游无视 Range 从头给了全部
    val iso = ByteArray(200) { it.toByte() }
    val baseOffset = 50L // 主影片从偏移 50 开始
    val want = 20L // mpv 要 20 个字节

    val got = iso.skipFullyOrThrow(baseOffset).bounded(want).readAllBytes()

    assertEquals(20, got.size)
    // 内容必须等于 ISO 里 [50, 70) 这一段
    assertArrayEquals(iso.copyOfRange(50, 70), got)
  }

  // ── 小工具：返回流，断言时既能逐字节读也能整段收 ───────────────────────

  private fun bounded(src: ByteArray, limit: Long): InputStream =
    src.inputStream().bounded(limit)

  private fun ByteArray.skipFullyOrThrow(n: Long): InputStream = inputStream().skipFully(n)
}