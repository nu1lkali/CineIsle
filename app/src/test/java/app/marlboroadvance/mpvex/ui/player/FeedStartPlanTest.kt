package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.feed.FeedItem
import app.marlboroadvance.mpvex.ui.player.feed.FeedStartPlan
import app.marlboroadvance.mpvex.ui.player.feed.startPlanOf
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedStartPlanTest {
  @Test
  fun `unsupported containers start directly in mpv`() {
    for (container in listOf("rmvb", "asf", "wmv", "ogv")) {
      assertEquals(
        container,
        FeedStartPlan.MPV,
        startPlanOf(item(container = container, codec = "h264")),
      )
    }
  }

  @Test
  fun `cold codecs prefer Exo FFmpeg instead of waiting for hardware stall`() {
    for (codec in listOf("wmv3", "vc1", "rv40", "vp6", "msmpeg4v3")) {
      assertEquals(codec, FeedStartPlan.FFMPEG, startPlanOf(item(codec = codec)))
    }
    assertEquals(FeedStartPlan.FFMPEG, startPlanOf(item(container = "avi", codec = "mpeg4")))
    assertEquals(FeedStartPlan.INNER, startPlanOf(item(container = "mp4", codec = "mpeg4")))
  }

  @Test
  fun `unsupported codec aliases normalize to direct mpv handoff`() {
    assertEquals(FeedStartPlan.MPV, startPlanOf(item(codec = "Theora")))
    assertEquals(FeedStartPlan.MPV, startPlanOf(item(codec = "Indeo 5")))
  }

  @Test
  fun `codec aliases covered by the FFmpeg extension preselect it`() {
    assertEquals(FeedStartPlan.FFMPEG, startPlanOf(item(codec = "VC-1")))
    assertEquals(FeedStartPlan.FFMPEG, startPlanOf(item(codec = "WVC1")))
    assertEquals(FeedStartPlan.FFMPEG, startPlanOf(item(codec = "RealVideo 4.0")))
  }

  private fun item(container: String? = null, codec: String? = null) =
    FeedItem(itemId = "id", title = "test", url = "file:///test", container = container, videoCodec = codec)
}
