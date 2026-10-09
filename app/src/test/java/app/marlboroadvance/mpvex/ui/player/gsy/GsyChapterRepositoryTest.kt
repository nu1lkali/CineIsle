package app.marlboroadvance.mpvex.ui.player.gsy

import app.marlboroadvance.mpvex.domain.emby.EmbyChapter
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository.Companion.currentAt
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository.Companion.indexOfCurrent
import app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository.Companion.toGsyChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节相关的纯逻辑测试（清单 #19）。
 *
 * 覆盖两块：
 *  1. [EmbyChapter.toGsyChapter] —— ticks→毫秒的换算与标题兜底；
 *  2. [List.indexOfCurrent] / [List.currentAt] —— 「当前在第几章」的二分查找。
 *
 * 这两处是**最容易静默出错**的地方：
 *  - ticks 换算错一个数量级，表现为「点章节跳到个奇怪的时间」，用户很难归因；
 *  - 二分查找边界写错（`<` 写成 `<=`），表现为「差一秒就跳到下一章」，同样难查。
 * 所以它们值得有单测钉死，而不是靠手点。
 */
class GsyChapterRepositoryTest {

  // ── ticks → 毫秒 ────────────────────────────────────────────────────────

  @Test
  fun `起点换算 一秒等于一千万 ticks`() {
    // 1 秒 = 1_000_000_000 纳秒 = 10_000_000 ticks
    assertEquals(1_000L, EmbyChapter(StartPositionTicks = 10_000_000L).startPositionMs)
  }

  @Test
  fun `起点换算 零保持零`() {
    assertEquals(0L, EmbyChapter(StartPositionTicks = 0L).startPositionMs)
  }

  @Test
  fun `起点换算 缺字段时是零而不是崩`() {
    // 服务端偶尔漏字段；漏了必须退化成 0（第一章），不能抛异常
    assertEquals(0L, EmbyChapter().startPositionMs)
  }

  @Test
  fun `起点换算 不足一毫秒的余数被截断而不是四舍五入`() {
    // 9999 ticks = 0.9999 ms → 截断成 0。
    // 章节起点本来就以整秒为主，这个分支只是保证行为确定（不因四舍五入变成 1）
    assertEquals(0L, EmbyChapter(StartPositionTicks = 9_999L).startPositionMs)
    assertEquals(1L, EmbyChapter(StartPositionTicks = 10_000L).startPositionMs)
  }

  @Test
  fun `起点换算 长片两小时不溢出`() {
    // 2 小时 = 7_200_000 ms = 72_000_000_000 ticks，超 Int 范围 ——
    // 这就是字段必须用 Long 的原因
    assertEquals(7_200_000L, EmbyChapter(StartPositionTicks = 72_000_000_000L).startPositionMs)
  }

  // ── 标题兜底 ────────────────────────────────────────────────────────────

  @Test
  fun `有标题就用标题`() {
    val chapter = EmbyChapter(StartPositionTicks = 0L, Name = "片头").toGsyChapter(0)
    assertEquals("片头", chapter.title)
  }

  @Test
  fun `标题为 null 时退回第 N 章`() {
    assertEquals("第 1 章", EmbyChapter(Name = null).toGsyChapter(0).title)
  }

  @Test
  fun `标题是空串时也退回第 N 章`() {
    // 空白串不能当标题 —— 会显示成一个空行，用户看着像加载失败
    assertEquals("第 3 章", EmbyChapter(Name = "   ").toGsyChapter(2).title)
  }

  @Test
  fun `下标原样带过来`() {
    assertEquals(4, EmbyChapter(Name = "x").toGsyChapter(4).index)
  }

  // ── 当前章二分查找 ──────────────────────────────────────────────────────

  private fun chapters(vararg startsMs: Long): List<GsyChapter> =
    startsMs.mapIndexed { i, ms -> GsyChapter(startMs = ms, title = "第 ${i + 1} 章", index = i) }

  @Test
  fun `空表没有当前章`() {
    assertNull(emptyList<GsyChapter>().indexOfCurrent(0L))
    assertNull(emptyList<GsyChapter>().currentAt(0L))
  }

  @Test
  fun `播放头在第一章之前没有当前章`() {
    // 这种情况真实存在：Emby 的章节起点未必从 0 开始（比如正片前有 30 秒片头素材未标章）
    val list = chapters(60_000L, 120_000L)
    assertNull(list.indexOfCurrent(0L))
  }

  @Test
  fun `正好落在章节起点上算这一章`() {
    // 边界：< 还是 <= 写错就体现在这个用例上
    val list = chapters(60_000L, 120_000L)
    assertEquals(0, list.indexOfCurrent(60_000L))
    assertEquals(1, list.indexOfCurrent(120_000L))
  }

  @Test
  fun `落在两章之间归到前一个`() {
    val list = chapters(60_000L, 120_000L)
    assertEquals(0, list.indexOfCurrent(119_999L))
  }

  @Test
  fun `超过最后一章归到最后一章`() {
    val list = chapters(60_000L, 120_000L)
    assertEquals(1, list.indexOfCurrent(999_999L))
  }

  @Test
  fun `第一章之前一毫秒仍无当前章`() {
    val list = chapters(60_000L)
    assertNull(list.indexOfCurrent(59_999L))
  }

  @Test
  fun `单章表里任意位置都归到那一章`() {
    val list = chapters(30_000L)
    assertEquals(0, list.indexOfCurrent(30_000L))
    assertEquals(0, list.indexOfCurrent(90_000L))
  }

  @Test
  fun `currentAt 返回章节本身而不是下标`() {
    val list = chapters(60_000L, 120_000L)
    val current = list.currentAt(130_000L)
    assertEquals(1, current?.index)
    assertEquals(120_000L, current?.startMs)
  }

  @Test
  fun `currentAt 对 null 列表返回 null`() {
    // 可空扩展：没缓存到章节时调用方不该崩
    val empty: List<GsyChapter>? = null
    assertNull(empty.currentAt(0L))
  }

  @Test
  fun `多章查找结果始终单调不倒退`() {
    // 扫一遍全时间轴，确保不会出现「往后走反而章节下标变小」这种错位
    val list = chapters(0L, 10_000L, 20_000L, 30_000L, 40_000L)
    var previous = -1
    var t = 0L
    while (t <= 50_000L) {
      val index = list.indexOfCurrent(t)
      if (index != null) {
        assertTrue("下标不应回退：t=$t index=$index previous=$previous", index >= previous)
        previous = index
      }
      t += 500L
    }
  }
}
