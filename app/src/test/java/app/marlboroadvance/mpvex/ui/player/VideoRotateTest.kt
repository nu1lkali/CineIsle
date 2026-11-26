package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.engine.RotationState
import app.marlboroadvance.mpvex.ui.player.engine.VideoRotate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面旋转的纯逻辑测试。
 *
 * 只覆盖**不碰 native** 的部分（累计状态 / 滤镜参数拼装 / 角度换算）——
 * 真正下发 `vf` 命令要靠真机验证，这里保证的是「转四次回原点」这类
 * 用户可感知的语义不出错。
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
  fun `filter arg carries the output size so the frame is not cropped`() {
    assertEquals(
      "rotate=90:ow=rotw(90):oh=roth(90)",
      VideoRotate.filterArg(1),
    )
    assertEquals(
      "rotate=180:ow=rotw(180):oh=roth(180)",
      VideoRotate.filterArg(2),
    )
    assertEquals(
      "rotate=270:ow=rotw(270):oh=roth(270)",
      VideoRotate.filterArg(3),
    )
  }

  @Test
  fun `zero degrees means no filter at all`() {
    // 0° 必须返回 null，让调用方走「移除滤镜」而不是挂一个空转的 rotate
    assertNull(VideoRotate.filterArg(0))
    // 越界圈数要归一，不能产生 rotate=450 这种参数
    assertNull(VideoRotate.filterArg(4))
    assertEquals("rotate=90:ow=rotw(90):oh=roth(90)", VideoRotate.filterArg(5))
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
  fun `label must not carry the at sign used by vf add`() {
    // `vf add @<label>:<filter>` 里的 `@` 是分隔符，标签名自带 `@` 会变成 @@
    assertFalse(VideoRotate.LABEL.startsWith("@"))
    assertTrue(VideoRotate.LABEL.isNotBlank())
  }

  @Test
  fun `direct output has no filter chain`() {
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
