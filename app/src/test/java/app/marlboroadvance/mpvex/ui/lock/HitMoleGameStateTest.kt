package app.marlboroadvance.mpvex.ui.lock

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打地鼠状态机的回归网。
 *
 * 这套玩法的全部内容就是时序，而时序坏掉在真机上表现为「偶尔点不中」「看着还在却算漏了」，
 * 极难复现。这里把随机源和时长区间都注入成定值，让每条判定都可以断言死。
 *
 * 不碰 Android 类，纯 JVM 跑（`.verify/` 下的 JUnitCore 脚本即可）。
 */
class HitMoleGameStateTest {

  /** 时长全部钉死成单值，时序相关的断言才是确定的。 */
  private fun fixedGame(target: Int = 3, seed: Int = 42) = HitMoleGameState(
    target = target,
    random = Random(seed),
    holdRange = 120..120,
    gapRange = 200..200,
  )

  @Test
  fun tapActiveCellIsHitButDoesNotCountUntilCommit() {
    val game = fixedGame()
    val cell = game.nextSpawn()

    assertEquals(HitMoleTap.HIT, game.tap(cell))
    // 点击只是锁格 —— 计数要等落锤到位那一帧，否则进度条会先动、锤子后到
    assertEquals(0, game.hits)

    game.commitHit()
    assertEquals(1, game.hits)
  }

  @Test
  fun tapOtherCellIsMissAndKeepsProgress() {
    val game = fixedGame()
    val cell = game.nextSpawn()
    val other = (cell + 1) % HitMoleGameState.CELL_COUNT

    assertEquals(HitMoleTap.MISS, game.tap(other))
    assertEquals(0, game.hits)
    // 敲错不扣进度、也不锁格：同一只还能继续敲
    assertEquals(HitMoleTap.HIT, game.tap(cell))
  }

  @Test
  fun timeoutClearsCellAndIsNotFailure() {
    val game = fixedGame()
    game.nextSpawn()

    assertTrue(game.onTimeout())
    assertEquals(-1, game.activeCell)
    assertEquals(0, game.hits)
    // 已锁定的那只不算超时（命中动画还在播）
    assertFalse(game.onTimeout())
  }

  @Test
  fun tapBeforeSpawnIsMiss() {
    val game = fixedGame()
    assertEquals(HitMoleTap.MISS, game.tap(4))
  }

  @Test
  fun repeatedHitsCompleteAtTarget() {
    val game = fixedGame(target = 3)
    repeat(3) {
      val cell = game.nextSpawn()
      assertEquals(HitMoleTap.HIT, game.tap(cell))
      game.commitHit()
      game.finishHit()
    }
    assertTrue(game.isComplete)
    // 达标之后不再接受输入
    assertEquals(HitMoleTap.IGNORED, game.tap(0))
  }

  @Test
  fun spawnNeverRepeatsPreviousCell() {
    val game = fixedGame()
    var previous = game.nextSpawn()
    repeat(200) {
      game.finishHit()
      val next = game.nextSpawn()
      assertNotEquals(previous, next)
      previous = next
    }
  }

  @Test
  fun spawnCoversEveryCell() {
    // 洞是可复用的：九个格子都应该出现过，不能把敲过的格子永久排除
    val game = fixedGame(seed = 7)
    val seen = HashSet<Int>()
    repeat(400) {
      game.finishHit()
      seen += game.nextSpawn()
    }
    assertEquals(HitMoleGameState.CELL_COUNT, seen.size)
  }

  @Test
  fun durationsStayInsideInjectedRange() {
    val game = HitMoleGameState(
      target = 5,
      random = Random(3),
      holdRange = 760..1140,
      gapRange = 380..720,
    )
    repeat(100) {
      game.finishHit()
      game.nextSpawn()
      assertTrue(game.holdMs in 760..1140)
      assertTrue(game.gapMs in 380..720)
    }
    // 两个区间独立取值，不该被绑成同一个数
    assertNotEquals(game.holdMs, game.gapMs)
  }

  @Test
  fun holdMsDoesNotChangeDuringOneMole() {
    val game = fixedGame()
    game.nextSpawn()
    val hold = game.holdMs

    // 期间反复点击（含敲错、连点）都不能重算时长，否则会出现
    // 「看着还在、点下去已经算漏」
    game.tap((game.activeCell + 1) % HitMoleGameState.CELL_COUNT)
    game.tap(game.activeCell)
    assertEquals(hold, game.holdMs)
  }

  @Test
  fun doubleTapCountsOnce() {
    val game = fixedGame()
    val cell = game.nextSpawn()

    assertEquals(HitMoleTap.HIT, game.tap(cell))
    // 第二次点击落锤期间 —— 忽略，不能算两次
    assertEquals(HitMoleTap.IGNORED, game.tap(cell))
    game.commitHit()
    // 收手前再补一次 commit 也只记一次
    game.commitHit()
    assertEquals(1, game.hits)
  }
}
