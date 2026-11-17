package app.marlboroadvance.mpvex.domain.smartmerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMergeVersionDetectorTest {

  @Test
  fun `LTX 与中文字幕与分辨率能同时识别`() {
    val profile = SmartMergeVersionDetector.detect(
      name = "ABC-123 LTX 中文字幕 1080p HEVC",
      path = null,
      videoHeight = 1080,
      videoCodec = "hevc",
    )
    assertEquals(TYPE_LTX, profile.type)
    assertEquals(SUBTITLES_CHINESE, profile.subtitles)
    assertEquals(QUALITY_1080P, profile.quality)
    assertEquals(CODEC_HEVC, profile.codec)
    assertTrue(profile.label.contains("LTX"))
    assertTrue(profile.label.contains("1080P"))
  }

  @Test
  fun `没有版本标记时是 NORMAL`() {
    val profile = SmartMergeVersionDetector.detect(
      name = "ABC-123",
      path = "/media/ABC-123.mp4",
      videoHeight = 2160,
      videoCodec = "h264",
    )
    assertEquals(TYPE_NORMAL, profile.type)
    assertEquals(QUALITY_4K, profile.quality)
    assertEquals(CODEC_H264, profile.codec)
  }

  @Test
  fun `内嵌字幕优先于中文字幕标签`() {
    val profile = SmartMergeVersionDetector.detect(
      name = "ABC-123 内嵌字幕",
      path = null,
      videoHeight = 720,
      videoCodec = "av1",
    )
    assertEquals(SUBTITLES_EMBEDDED, profile.subtitles)
    assertEquals(QUALITY_720P, profile.quality)
    assertEquals(CODEC_AV1, profile.codec)
  }
}
