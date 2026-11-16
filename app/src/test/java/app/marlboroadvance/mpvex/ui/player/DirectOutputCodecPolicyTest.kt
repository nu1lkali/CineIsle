package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOutputCodecPolicyTest {
  @Test
  fun `MediaCodec allowlisted codecs keep direct output`() {
    for (codec in listOf("h264", "hevc", "mpeg2video", "vc1", "vp8", "vp9", "av1")) {
      assertFalse("$codec should be eligible for direct output", requiresSoftwareVideoOutput(codec))
    }
  }

  @Test
  fun `software decoded legacy codecs require a GPU output`() {
    for (codec in listOf("mpeg4", "msmpeg4v3", "wmv3", "mpeg1video", "theora", "rv40")) {
      assertTrue("$codec must not use mediacodec_embed", requiresSoftwareVideoOutput(codec))
    }
  }

  @Test
  fun `codec matching ignores case and surrounding whitespace`() {
    assertFalse(requiresSoftwareVideoOutput(" H264 "))
    assertTrue(requiresSoftwareVideoOutput(" Mpeg4 "))
  }

  @Test
  fun `missing codec metadata is inconclusive`() {
    assertFalse(requiresSoftwareVideoOutput(null))
    assertFalse(requiresSoftwareVideoOutput(""))
    assertFalse(requiresSoftwareVideoOutput("  "))
  }
}
