package app.marlboroadvance.mpvex.domain.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SegmentMap] 的单测。
 *
 * 为什么这个映射值得单独测：它是「mpv 的虚拟坐标 ↔ ISO 内物理坐标」唯一的换算处。
 * 算错一位就是**从错误的字节开始喂数据**，症状是花屏 / 音画不同步，
 * 而且不会报任何错 —— 比报错难查一个量级。
 *
 * 特别地，本类**刻意不支持**「假设各段物理相邻」。
 * 早先的 DVD 实现就是这么假设的（首段起点 + 总长度），
 * 相邻时结果一致，但蓝光分段在 ISO 里没有任何规范保证相邻。
 */
class SegmentMapTest {

  @Test
  fun `单段映射 虚拟偏移等于物理偏移加上 base`() {
    val map = SegmentMap.single(1000L, 500L)
    assertEquals(500L, map.totalLength)
    val ranges = map.physicalRanges(0L, 99L)
    assertEquals(1, ranges.size)
    assertEquals(1000L, ranges[0].isoOffset)
    assertEquals(100L, ranges[0].length)
  }

  @Test
  fun `单段映射 虚拟偏移 200 落在 base+200`() {
    val map = SegmentMap.single(1000L, 500L)
    val ranges = map.physicalRanges(200L, 299L)
    assertEquals(1, ranges.size)
    assertEquals(1200L, ranges[0].isoOffset)
    assertEquals(100L, ranges[0].length)
  }

  @Test
  fun `多段映射 完全落在第一段时只产生一条`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L), Segment(5000L, 100L), Segment(9000L, 100L)))
    val ranges = map.physicalRanges(0L, 50L)
    assertEquals(1, ranges.size)
    assertEquals(1000L, ranges[0].isoOffset)
    assertEquals(51L, ranges[0].length)
  }

  @Test
  fun `多段映射 完全落在第二段时偏移按段换算`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L), Segment(5000L, 100L), Segment(9000L, 100L)))
    // 虚拟 100..150 = 第二段的前 51 字节
    val ranges = map.physicalRanges(100L, 150L)
    assertEquals(1, ranges.size)
    assertEquals(5000L, ranges[0].isoOffset)
    assertEquals(51L, ranges[0].length)
  }

  /**
   * ⚠️ 本类存在的核心理由：跨段区间必须被**拆成多条**并按顺序返回。
   * 早先「假设相邻」的实现会把虚拟 90..160 直接算成 base+90 起算 71 字节 ——
   * 在段不物理相邻时那就是从错误位置取的 71 字节（花屏）。
   */
  @Test
  fun `跨段区间被拆成多条且物理偏移各归各段`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L), Segment(5000L, 100L), Segment(9000L, 100L)))
    // 虚拟 90..160：前 10 字节在第一段尾，后 61 字节在第二段头
    val ranges = map.physicalRanges(90L, 160L)
    assertEquals(2, ranges.size)
    assertEquals(1090L, ranges[0].isoOffset) // 1000 + 90
    assertEquals(10L, ranges[0].length)
    assertEquals(5000L, ranges[1].isoOffset) // 第二段起点
    assertEquals(61L, ranges[1].length)
  }

  @Test
  fun `跨三段的区间被拆成三条`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L), Segment(5000L, 100L), Segment(9000L, 100L)))
    val ranges = map.physicalRanges(95L, 205L)
    assertEquals(3, ranges.size)
    assertEquals(1095L, ranges[0].isoOffset)
    assertEquals(5L, ranges[0].length)
    assertEquals(5000L, ranges[1].isoOffset)
    assertEquals(100L, ranges[1].length)
    assertEquals(9000L, ranges[2].isoOffset)
    assertEquals(6L, ranges[2].length)
    // 三段长度之和必须恰好等于请求区间长度
    assertEquals(111L, ranges.sumOf { it.length })
  }

  @Test
  fun `各段长度之和等于虚拟总长`() {
    val map = SegmentMap(listOf(Segment(0L, 111L), Segment(999L, 222L), Segment(5L, 33L)))
    assertEquals(366L, map.totalLength)
    assertEquals(3, map.segmentCount)
  }

  // ── 边界：越界必须返回空，不能猜 ────────────────────────────────────────

  @Test
  fun `虚拟起点越界返回空列表`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L), Segment(5000L, 100L)))
    assertTrue(map.physicalRanges(200L, 250L).isEmpty())
  }

  @Test
  fun `虚拟起点为负返回空列表`() {
    val map = SegmentMap(listOf(Segment(1000L, 100L)))
    assertTrue(map.physicalRanges(-1L, 50L).isEmpty())
  }

  @Test
  fun `虚拟终点超出总长时被截断到总长`() {
    val map = SegmentMap.single(1000L, 100L)
    // 请求 50..999，但文件只有 100 字节 ⇒ 截到 50..99，共 50 字节
    val ranges = map.physicalRanges(50L, 999L)
    assertEquals(1, ranges.size)
    assertEquals(50L, ranges[0].length)
  }

  @Test
  fun `反向区间返回空列表`() {
    val map = SegmentMap.single(1000L, 100L)
    assertTrue(map.physicalRanges(80L, 20L).isEmpty())
  }

  @Test
  fun `区间起点正好等于总长时返回空列表`() {
    val map = SegmentMap.single(1000L, 100L)
    // 起点 == 长度 ⇒ 已在文件尾之后
    assertTrue(map.physicalRanges(100L, 120L).isEmpty())
  }

  /**
   * 随机抽查：任何区间的各段长度之和都必须等于区间长度。
   *
   * 这条是上面所有断言的「整体版」—— 逐段偏移对不对很难穷举，
   * 但**长度必须守恒**可以穷举，长度错了字节流必然错位。
   */
  @Test
  fun `任意区间的各段长度之和都等于区间长度`() {
    val maps =
      listOf(
        SegmentMap(listOf(Segment(1000L, 10L))),
        SegmentMap(listOf(Segment(1000L, 10L), Segment(5000L, 20L))),
        SegmentMap(listOf(Segment(1000L, 1L), Segment(2000L, 1L), Segment(3000L, 1L), Segment(4000L, 1L))),
      )
    for (map in maps) {
      val total = map.totalLength
      for (start in 0 until total) {
        for (end in start until total) {
          val ranges = map.physicalRanges(start, end)
          assertTrue("区间 $start..$end 应非空", ranges.isNotEmpty())
          assertEquals("区间 $start..$end 的总长度", end - start + 1, ranges.sumOf { it.length })
        }
      }
    }
  }

  /** 单段长度为 0 必须在构造时就拒掉 —— 它会让 starts 出现重复值，二分定位失效。 */
  @Test(expected = IllegalArgumentException::class)
  fun `零长度片段被拒绝`() {
    SegmentMap(listOf(Segment(1000L, 0L)))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `空片段列表被拒绝`() {
    SegmentMap(emptyList())
  }
}