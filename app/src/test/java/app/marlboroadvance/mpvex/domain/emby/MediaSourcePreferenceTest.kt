package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [selectPreferredMediaSource] 的行为约定。
 *
 * 这些用例锁住的是**产品约定**而不只是实现：缺失值怎么处理、
 * 「宁可返回 null 也不乱选」这两条一旦被改坏，用户的播放结果会静默变化
 * （选错版本 ⇒ 画质变了，但界面上看不出任何异常）。
 */
class MediaSourcePreferenceTest {

  private fun source(
    id: String,
    bitrate: Long? = null,
    size: Long? = null,
    width: Int? = null,
    height: Int? = null,
  ) = EmbyMediaSource(
    Id = id,
    Bitrate = bitrate,
    Size = size,
    MediaStreams =
      if (width == null && height == null) {
        null
      } else {
        listOf(EmbyMediaStream(Type = "Video", Width = width, Height = height))
      },
  )

  @Test
  fun `默认规则不选版本`() {
    val list = listOf(source("a", bitrate = 1000), source("b", bitrate = 2000))
    assertNull(selectPreferredMediaSourceId(list, MediaSourcePreference.DEFAULT))
  }

  @Test
  fun `单版本时不选`() {
    assertNull(
      selectPreferredMediaSourceId(listOf(source("only")), MediaSourcePreference.HIGHEST_BITRATE)
    )
  }

  @Test
  fun `空列表不选`() {
    assertNull(selectPreferredMediaSourceId(emptyList(), MediaSourcePreference.HIGHEST_BITRATE))
  }

  @Test
  fun `最高分辨率按像素总数而不是高度`() {
    // 竖屏 1280x720 = 921600 px，横向 1920x800 = 1536000 px：
    // 只比 Height 会误判 720P 竖版更"高"，必须比总像素。
    val list =
      listOf(
        source("portrait720", width = 1280, height = 720),
        source("wide800", width = 1920, height = 800),
      )
    assertEquals("wide800", selectPreferredMediaSourceId(list, MediaSourcePreference.HIGHEST_RESOLUTION))
  }

  @Test
  fun `最低分辨率把缺失值排到最后而不是排最前`() {
    val list =
      listOf(
        source("unknown"), // 服务端没给分辨率
        source("uhd", width = 3840, height = 2160),
        source("sd", width = 640, height = 480),
      )
    assertEquals("sd", selectPreferredMediaSourceId(list, MediaSourcePreference.LOWEST_RESOLUTION))
  }

  @Test
  fun `最高码率在缺失值存在时仍然选最高`() {
    val list = listOf(source("none"), source("low", bitrate = 2_000_000), source("high", bitrate = 40_000_000))
    assertEquals("high", selectPreferredMediaSourceId(list, MediaSourcePreference.HIGHEST_BITRATE))
  }

  @Test
  fun `最低码率不会选中缺失值`() {
    val list = listOf(source("none"), source("mid", bitrate = 5_000_000))
    assertEquals("mid", selectPreferredMediaSourceId(list, MediaSourcePreference.LOWEST_BITRATE))
  }

  @Test
  fun `码率缺失时回退用视频流自身的码率`() {
    // EmbyMediaSource.Bitrate 常为空，但视频流上带 BitRate —— 两者取其一即可。
    val a =
      EmbyMediaSource(
        Id = "a",
        Bitrate = null,
        MediaStreams = listOf(EmbyMediaStream(Type = "Video", BitRate = 1_000_000)),
      )
    val b =
      EmbyMediaSource(
        Id = "b",
        Bitrate = 30_000_000,
        MediaStreams = listOf(EmbyMediaStream(Type = "Video", BitRate = 30_000_000)),
      )
    assertEquals("b", selectPreferredMediaSourceId(listOf(a, b), MediaSourcePreference.HIGHEST_BITRATE))
  }

  @Test
  fun `体积最小在体积缺失时挑有值里最小的`() {
    val list = listOf(source("none"), source("big", size = 900L), source("small", size = 120L))
    assertEquals("small", selectPreferredMediaSourceId(list, MediaSourcePreference.SMALLEST_SIZE))
  }

  @Test
  fun `同画质选更小的是按分辨率优先`() {
    // 1080P 的 5G 与 720P 的 200M：按"每像素成本"算 720P 更优，
    // 但用户说"同画质"就是**别降分辨率**，所以仍应选 1080P。
    val list =
      listOf(source("hd", size = 5_000L, width = 1920, height = 1080), source("sd", size = 200L, width = 1280, height = 720))
    assertEquals(
      "hd",
      selectPreferredMediaSourceId(list, MediaSourcePreference.BEST_QUALITY_PER_SIZE),
    )
  }

  @Test
  fun `全部字段缺失时不瞎选`() {
    val list = listOf(source("a"), source("b"))
    assertNull(selectPreferredMediaSourceId(list, MediaSourcePreference.HIGHEST_RESOLUTION))
  }

  @Test
  fun `规则键非法时回落到默认`() {
    assertEquals(MediaSourcePreference.DEFAULT, MediaSourcePreference.fromKey("不存在的规则"))
    assertEquals(MediaSourcePreference.DEFAULT, MediaSourcePreference.fromKey(null))
    assertEquals(
      MediaSourcePreference.HIGHEST_BITRATE,
      MediaSourcePreference.fromKey("highest_bitrate"),
    )
  }
}
