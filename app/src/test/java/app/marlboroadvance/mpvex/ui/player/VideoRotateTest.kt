package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.engine.RotationState
import app.marlboroadvance.mpvex.ui.player.engine.VideoRotate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面旋转的纯逻辑测试。
 *
 * 只覆盖**不碰 native** 的部分（累计状态 / 角度换算 / 写入值）——
 * 真正下发属性要靠真机验证，这里保证「转四次回原点」「写度数而非弧度」
 * 这类用户可感知的语义不出错。
 */
class VideoRotateTest {
  @Test
  fun `four steps return to identity`() {
    var s = RotationState.Identity
    val seen = mutableListOf(s.degrees)
    repeat(4) {
      s = s.next()
      seen += s.degrees
    }
    assertEquals(listOf(0, 90, 180, 270, 0), seen)
    assertFalse(s.isRotated)
  }

  @Test
  fun `rotation is clockwise only`() {
    assertEquals(90, RotationState.Identity.next().degrees)
    assertEquals(180, RotationState.Identity.next().next().degrees)
    assertEquals(270, RotationState.Identity.next().next().next().degrees)
  }

  @Test
  fun `reset always lands on zero`() {
    val s = RotationState.Identity.next().next().next()
    assertEquals(0, s.reset().degrees)
    assertFalse(s.reset().isRotated)
  }

  @Test
  fun `property value is exact degrees not radians`() {
    // 回归：旧版 vf rotate=90 被 libavfilter 当 90 弧度 ≈ 116.7°，画面是斜的。
    // 写入 video-rotate 的必须是精确的度数，不能有任何弧度换算。
    assertEquals(90, VideoRotate.degreesOf(1))
    assertEquals(180, VideoRotate.degreesOf(2))
    assertEquals(270, VideoRotate.degreesOf(3))
    assertEquals(0, VideoRotate.degreesOf(0))
  }

  @Test
  fun `zero quarter turns means zero property value`() {
    // 0° 必须写 0（干净的复位），不能产生 360 这类等价旋转。
    assertEquals(0, VideoRotate.degreesOf(0))
    assertEquals(0, VideoRotate.degreesOf(4))
    // 越界圈数要归一，不能产生 450 这种角度。
    assertEquals(90, VideoRotate.degreesOf(5))
  }

  @Test
  fun `negative quarter turns are normalised`() {
    assertEquals(270, VideoRotate.degreesOf(-1))
    assertEquals(180, VideoRotate.degreesOf(-2))
    assertEquals(90, VideoRotate.degreesOf(-3))
  }

  @Test
  fun `degrees and quarter turns round trip`() {
    for (q in 0..3) {
      val deg = VideoRotate.degreesOf(q)
      assertEquals(q, VideoRotate.quarterTurnsOf(deg))
    }
    // 越界角度也要能安全归一
    assertEquals(1, VideoRotate.quarterTurnsOf(450))
    assertEquals(3, VideoRotate.quarterTurnsOf(-90))
  }

  @Test
  fun `state can be restored from mpv reported degrees`() {
    assertEquals(0, RotationState.ofDegrees(0).degrees)
    assertEquals(90, RotationState.ofDegrees(90).degrees)
    assertEquals(180, RotationState.ofDegrees(180).degrees)
    assertEquals(270, RotationState.ofDegrees(270).degrees)
    // mpv 可能报 360 / 越界值，状态里不能出现「360°」
    assertEquals(0, RotationState.ofDegrees(360).degrees)
  }

  @Test
  fun `property is the native video rotate`() {
    // 必须是 mpv 原生属性，叠加在容器旋转之上；不再是自定义 vf 标签。
    assertEquals("video-rotate", VideoRotate.PROPERTY)
  }

  @Test
  fun `direct output has no rotation entry`() {
    assertFalse(VideoRotate.isAvailable(directOutput = true))
    assertTrue(VideoRotate.isAvailable(directOutput = false))
  }

  @Test
  fun `odd quarter turns swap width and height`() {
    // getVideoOutAspect / Stretch 的屏幕比例都靠这条判定取倒数
    val swapped = listOf(1, 3).map { VideoRotate.degreesOf(it) }
    swapped.forEach { assertEquals(90, it % 180) }
    val kept = listOf(0, 2).map { VideoRotate.degreesOf(it) }
    kept.forEach { assertEquals(0, it % 180) }
  }
}
