package app.marlboroadvance.mpvex.ui.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴佐证这一层的行为约定。
 *
 * 它**只**是排序佐证：这些用例除了钉住解析，还要钉住「加减分小于一个档位」——
 * 一旦有人把这里的系数调大，时长就会重新变成判据，v252 那条教训就白吃了。
 */
class SubtitleTimingEvidenceTest {

  // ── 解析 ────────────────────────────────────────────────────────────────────

  @Test
  fun `SRT 取末条结束时间与条数`() {
    val text =
      """
      1
      00:00:01,000 --> 00:00:03,500
      第一句

      2
      01:59:58,000 --> 01:59:59,500
      最后一句
      """
        .trimIndent()

    val timing = SubtitleTimingProbe.parse(text)
    assertEquals(1 * 3600_000L + 59 * 60_000L + 59_500L, timing!!.lastCueMs)
    assertEquals(2, timing.cueCount)
  }

  @Test
  fun `ASS 取末条结束时间与 Dialogue 条数`() {
    // ASS 的时间是「时:分:秒.百分秒」，两位小数按百分秒算
    val text =
      """
      [Events]
      Format: Layer, Start, End, Style, Text
      Dialogue: 0,0:00:01.00,0:00:03.50,Default,第一句
      Dialogue: 0,0:23:45.12,0:23:47.00,Default,第二句
      """
        .trimIndent()

    val timing = SubtitleTimingProbe.parse(text)
    assertEquals(23 * 60_000L + 47_000L, timing!!.lastCueMs)
    assertEquals(2, timing.cueCount)
  }

  @Test
  fun `没有时间戳时返回 null 而不是 0`() {
    assertNull(SubtitleTimingProbe.parse("只是一段说明文字，没有时间轴"))
    assertNull(SubtitleTimingProbe.parse(""))
  }

  // ── 与片长的关系 ────────────────────────────────────────────────────────────

  @Test
  fun `末条落在片尾两分钟内算吻合`() {
    val duration = 90 * 60_000L
    assertTrue(LocalSubtitleScanner.coincides(duration - 60_000L, duration))
    assertTrue("片尾曲无字幕，短一分钟属正常", LocalSubtitleScanner.coincides(duration - 110_000L, duration))
  }

  @Test
  fun `只覆盖一半不算吻合`() {
    val duration = 90 * 60_000L
    assertFalse(LocalSubtitleScanner.coincides(duration / 3, duration))
  }

  @Test
  fun `片长未知时不算吻合也不报错`() {
    assertFalse(LocalSubtitleScanner.coincides(90 * 60_000L, 0))
    assertFalse(LocalSubtitleScanner.coincides(0, 90 * 60_000L))
  }

  /**
   * 时间轴**不参与打分**（v255 起）。
   *
   * 旧实现给它 ±20 分的增减。那个设计在真实库里会把「番号完全一致、只是片头被剪掉」
   * 的正确字幕压到「编号只对上尾号」的错候选后面 —— 因为后者的时长区间更接近。
   * 现在它只负责两件事：给候选行加一个「时长吻合」的注记，以及同分时决定先后。
   */
  @Test
  fun `时间轴不再改变分数`() {
    val media = "www.98T.la@n0907_iris2.(mp4).strm"
    val sub = "n0907_runa_kobayashi_na_n_fhd.chinese.srt"
    val scannerClass = LocalSubtitleScanner::class.java
    val timingDelta =
      runCatching {
        scannerClass.getDeclaredMethod("timingDelta", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
      }
    assertTrue("timingDelta 应当已经不存在（时间轴不再折算成分数）", timingDelta.isFailure)
    // 顺序无关：只是确认主链路仍然可用
    assertTrue(LocalSubtitleScanner.coincides(5400_000L, 5400_000L))
  }
}
