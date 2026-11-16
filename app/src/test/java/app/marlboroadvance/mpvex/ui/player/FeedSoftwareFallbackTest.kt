package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.feed.FeedSoftwareFallback
import app.marlboroadvance.mpvex.ui.player.feed.chooseFeedSoftwareFallback
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedSoftwareFallbackTest {
  @Test
  fun `NextLib FFmpeg is preferred when it supports the MIME`() {
    assertEquals(
      FeedSoftwareFallback.FFMPEG,
      chooseFeedSoftwareFallback(
        ffmpegSupportsMime = true,
        platformHasSoftwareDecoder = true,
      ),
    )
  }

  @Test
  fun `platform software codec is used when FFmpeg has no MIME mapping`() {
    assertEquals(
      FeedSoftwareFallback.PLATFORM,
      chooseFeedSoftwareFallback(
        ffmpegSupportsMime = false,
        platformHasSoftwareDecoder = true,
      ),
    )
  }

  @Test
  fun `mpv handoff remains necessary when neither software path exists`() {
    assertEquals(
      FeedSoftwareFallback.NONE,
      chooseFeedSoftwareFallback(
        ffmpegSupportsMime = false,
        platformHasSoftwareDecoder = false,
      ),
    )
  }
}
