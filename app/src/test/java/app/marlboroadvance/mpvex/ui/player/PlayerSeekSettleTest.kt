package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「频繁拖动进度条 → 返回 → 详情页进度变 0%」的回归网。
 *
 * 这里只测 `PlayerSeekSettle.kt` 里的纯函数判定，不碰 Android 类，所以能在普通 JVM 上
 * 十几秒跑完（`.verify/run_settle_tests.py`）。之所以值得单独建一张网：这条判定连着
 * 服务端续播点，而真机复现一次要「拖动 → 返回 → 进详情页」，前几版就是靠肉眼验的。
 *
 * 每条用例都对应一种真实形态，改动前先想清楚它会把哪条打红。
 */
class PlayerSeekSettleTest {
  /** Emby: 1 秒 = 10_000_000 ticks。 */
  private val tps = 10_000_000L

  private fun sec(value: Long): Long = value * tps

  // ───────── 秒制状态机 ─────────

  @Test
  fun noTargetReturnsRaw() {
    val out = settleSeekPosition(rawSeconds = 600, targetSeconds = -1, elapsedSinceDispatchMs = 100)
    assertEquals(600, out.seconds)
    assertFalse(out.substituted)
  }

  @Test
  fun zeroRightAfterSeekIsReplacedByTarget() {
    // 用户拖到 40:00 立刻返回：取流层重建，mpv 的 time-pos 报 0。
    val out = settleSeekPosition(0, 2400, 100)
    assertEquals(2400, out.seconds)
    assertTrue(out.substituted)
  }

  @Test
  fun stalePositionRightAfterSeekIsReplacedByTarget() {
    // 新位置还没解出第一帧，读数停在拖动前的 10:00。
    val out = settleSeekPosition(600, 2400, 100)
    assertEquals(2400, out.seconds)
    assertTrue(out.substituted)
  }

  @Test
  fun readingNearTargetIsTrustedAsIs() {
    // absolute+keyframes 允许落在目标之前的关键帧上，容差内照用实测值。
    val out = settleSeekPosition(2395, 2400, 100)
    assertEquals(2395, out.seconds)
    assertFalse(out.substituted)
  }

  @Test
  fun slowNetworkSeekBeyondOldEightSecondWindowStillCorrected() {
    // v259 的核心：老实现按「距下发 8 秒」判，网络流上 seek 落地常超过 8 秒，
    // 窗口一过就重新采信读数，而那时读数正好还是 0 ⇒ 0 被写进续播点。
    val out = settleSeekPosition(0, 2400, 30_000)
    assertEquals(2400, out.seconds)
    assertTrue(out.substituted)
  }

  @Test
  fun holdExpiresAndRawReadingIsTrustedAgain() {
    // seek 大概真的失败了：不能永远替用户宣称他在目标处。
    val out = settleSeekPosition(0, 2400, SeekSettlePolicy.MAX_HOLD_MS + 1)
    assertEquals(0, out.seconds)
    assertFalse(out.substituted)
  }

  @Test
  fun confirmedLandingIsNeverCorrectedAgain() {
    // 确认落地之后的偏差是真实播放，或用户又拖了一次，不该再被旧目标扶回去。
    val out = settleSeekPosition(600, 2400, 100, alreadyConfirmed = true)
    assertEquals(600, out.seconds)
    assertFalse(out.substituted)
  }

  @Test
  fun userSeekingBackToStartKeepsZero() {
    // 用户有权拖回开头：这个 0 是合法的，不能一律当成假读数拦掉。
    val out = settleSeekPosition(0, 0, 100)
    assertEquals(0, out.seconds)
    assertFalse(out.substituted)
    assertTrue(isAtSeekTarget(0, 0))
  }

  @Test
  fun toleranceBoundaries() {
    assertTrue(isAtSeekTarget(2388, 2400))
    assertFalse(isAtSeekTarget(2387, 2400))
    assertTrue(isAtSeekTarget(2412, 2400))
    assertFalse(isAtSeekTarget(-1, 2400))
  }

  // ───────── ticks 适配层（上报器真正走的路） ─────────

  @Test
  fun ticksZeroAfterSeekIsReplacedByTargetInTicks() {
    val out = settleTicksPosition(0, 2400, 100, false, tps)
    // 2400 秒的目标必须变成 2400 × 10⁷ ticks，绝不是「2400」这个裸数字。
    assertEquals(sec(2400), out.ticks)
    assertTrue(out.substituted)
    assertTrue(out.ticks > tps)
  }

  @Test
  fun ticksStalePositionAfterSeekIsReplacedByTargetInTicks() {
    val out = settleTicksPosition(sec(600), 2400, 100, false, tps)
    assertEquals(sec(2400), out.ticks)
    assertTrue(out.substituted)
  }

  @Test
  fun ticksNearTargetAreReturnedWithoutPrecisionLoss() {
    // 非替换路径不做 ticks→秒→ticks 往返，小数秒不能被整除抹掉。
    val raw = sec(2395) + 1234
    val out = settleTicksPosition(raw, 2400, 100, false, tps)
    assertEquals(raw, out.ticks)
    assertFalse(out.substituted)
  }

  @Test
  fun ticksNoTargetReturnsRaw() {
    val raw = sec(600)
    val out = settleTicksPosition(raw, -1, 100, false, tps)
    assertEquals(raw, out.ticks)
    assertFalse(out.substituted)
  }

  @Test
  fun ticksSlowSeekBeyondOldWindowStillCorrected() {
    val out = settleTicksPosition(0, 2400, 30_000, false, tps)
    assertEquals(sec(2400), out.ticks)
    assertTrue(out.substituted)
  }

  @Test
  fun ticksHoldExpiresReturnsRaw() {
    val raw = sec(600)
    val out = settleTicksPosition(raw, 2400, SeekSettlePolicy.MAX_HOLD_MS + 1, false, tps)
    assertEquals(raw, out.ticks)
    assertFalse(out.substituted)
  }

  @Test
  fun feedingTicksIntoSecondsPolicyDemonstratesTheOldUnitBug() {
    // 旧实现把 600 秒对应的 ticks 直接喂给秒制判定：容差 12「秒」在 ticks 面前失效，
    // 结果被替换成 2400，再被上报器当 ticks 发出去 = 0.00024 秒 ⇒ 详情页 0%。
    // 这条用例把「为什么换算必须集中在一处」钉死：漏了换算，等式右边就是 2400 而不是 2400×tps。
    val wrong = settleSeekPosition(sec(600), 2400, 100)
    assertEquals(2400, wrong.seconds)
    assertTrue(wrong.substituted)

    val right = settleTicksPosition(sec(600), 2400, 100, false, tps)
    assertEquals(sec(2400), right.ticks)
    assertTrue(right.ticks != wrong.seconds)
  }
}
