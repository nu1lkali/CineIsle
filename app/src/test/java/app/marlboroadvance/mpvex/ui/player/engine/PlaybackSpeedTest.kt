package app.marlboroadvance.mpvex.ui.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍速口径的纯逻辑测试：范围、精度、横滑线性映射、显示格式。
 *
 * 这是「步长固定 / 到不了 6x」反馈的回归闸门 —— 任何入口绕过 [PlaybackSpeed]
 * 自己写 preset 索引或写死 4.0，这里的值域断言都会先提醒。
 *
 * ⚠️ 浮点断言一律带 delta：`step * 0.1f` 的结果不一定是字面量 `2.6f`，
 * 直接 assertEquals(Float, Float) 会被 IEEE-754 的最后一位坑到。
 */
class PlaybackSpeedTest {
  private val EPS = 0.0001f

  @Test
  fun `constants are the single source of truth`() {
    assertEquals(0.1f, PlaybackSpeed.MIN, EPS)
    assertEquals(6.0f, PlaybackSpeed.MAX, EPS)
    assertEquals(0.1f, PlaybackSpeed.STEP, EPS)
    assertEquals(5.9f, PlaybackSpeed.SPAN, EPS)
  }

  @Test
  fun `quantize snaps to one decimal`() {
    assertEquals(1.0f, PlaybackSpeed.quantize(1.04f), EPS)
    assertEquals(1.1f, PlaybackSpeed.quantize(1.06f), EPS)
    assertEquals(2.6f, PlaybackSpeed.quantize(2.59f), EPS)
    assertEquals(5.0f, PlaybackSpeed.quantize(4.96f), EPS)
  }

  @Test
  fun `quantize clamps to min and max`() {
    assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.quantize(0f), EPS)
    assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.quantize(-3f), EPS)
    assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.quantize(6.04f), EPS)
    assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.quantize(99f), EPS)
  }

  @Test
  fun `quantize tolerates nan and floats with accumulation error`() {
    // 0.1f + 0.2f = 0.30000001f —— 必须被压回 0.3，不许泄漏到 mpv。
    assertEquals(0.3f, PlaybackSpeed.quantize(0.1f + 0.2f), EPS)
    assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.quantize(Float.NaN), EPS)
    assertTrue(PlaybackSpeed.quantize(Float.POSITIVE_INFINITY) == PlaybackSpeed.MAX)
  }

  @Test
  fun `from swipe is linear in speed not in preset index`() {
    // 起点 2.0，右滑 1/10 屏宽、灵敏度 1 → 2.0 + 0.59 = 2.59 → 2.6。
    assertEquals(2.6f, PlaybackSpeed.fromSwipe(2.0f, 100f, 1000f, sensitivity = 1f), EPS)
    // 每格 0.1 等宽：再滑 1/10 屏宽仍是 +0.59，而不是「跨过某个 preset」。
    assertEquals(3.2f, PlaybackSpeed.fromSwipe(2.6f, 100f, 1000f, sensitivity = 1f), EPS)
  }

  @Test
  fun `from swipe left reduces speed`() {
    assertEquals(1.4f, PlaybackSpeed.fromSwipe(2.0f, -100f, 1000f, sensitivity = 1f), EPS)
  }

  @Test
  fun `from swipe clamps to full range`() {
    assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.fromSwipe(2.0f, 10000f, 1000f), EPS)
    assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.fromSwipe(2.0f, -10000f, 1000f), EPS)
  }

  @Test
  fun `from swipe with invalid width only quantizes start`() {
    assertEquals(2.0f, PlaybackSpeed.fromSwipe(2.04f, 500f, 0f), EPS)
    assertEquals(2.0f, PlaybackSpeed.fromSwipe(2.04f, 500f, -100f), EPS)
  }

  @Test
  fun `from swipe reaches six and one tenth`() {
    // 上界 6.0 与下界 0.1 是本次扩展的两个端点，必须可精确落到。
    assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.fromSwipe(5.0f, 1000f, 1000f, sensitivity = 1f), EPS)
    assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.fromSwipe(1.0f, -1000f, 1000f, sensitivity = 1f), EPS)
  }

  @Test
  fun `display always shows one decimal`() {
    assertEquals("1.0", PlaybackSpeed.display(1f))
    assertEquals("2.6", PlaybackSpeed.display(2.59f))
    assertEquals("6.0", PlaybackSpeed.display(6f))
    assertEquals("0.1", PlaybackSpeed.display(0f))
  }
}
