package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyMediaInfoSummaryTest {
  @Test
  fun `summary keeps the important fields and path`() {
    val item =
      EmbyItem(
        Id = "movie",
        Name = "Demo",
        MediaSources =
          listOf(
            EmbyMediaSource(
              Id = "ms1",
              Container = "mkv",
              Path = "/media/movies/Demo.mkv",
              Size = 12L * 1024 * 1024 * 1024,
              Bitrate = 18_000_000,
              MediaStreams =
                listOf(
                  EmbyMediaStream(
                    Index = 0,
                    Type = "Video",
                    Codec = "hevc",
                    Profile = "Main 10",
                    Width = 3840,
                    Height = 2160,
                    VideoRangeType = "HDR10",
                  ),
                  EmbyMediaStream(
                    Index = 1,
                    Type = "Audio",
                    Codec = "eac3",
                    ChannelLayout = "5.1",
                    IsDefault = true,
                  ),
                  EmbyMediaStream(
                    Index = 2,
                    Type = "Subtitle",
                    Language = "chi",
                    IsExternal = true,
                  ),
                  EmbyMediaStream(
                    Index = 3,
                    Type = "Subtitle",
                    Language = "eng",
                  ),
                ),
            ),
          ),
      )

    val summary = buildEmbyMediaInfoSummary(item)

    assertEquals("3840×2160", summary.resolution)
    assertEquals("HEVC · Main 10", summary.videoCodec)
    assertEquals("EAC3 · 5.1", summary.audio)
    assertEquals("MKV", summary.container)
    assertEquals("HDR10", summary.hdr)
    assertEquals(12.0, summary.size!!.removeSuffix(" GB").toDouble(), 0.01)
    assertEquals("/media/movies/Demo.mkv", summary.path)
    assertEquals(2, summary.subtitleCount)
    assertEquals(1, summary.externalSubtitleCount)
    assertEquals(listOf("中文", "英语"), summary.subtitleLanguages)
    assertTrue(!summary.isEmpty)
  }

  @Test
  fun `summary tolerates missing fields`() {
    val summary = buildEmbyMediaInfoSummary(EmbyItem(Id = "x"))
    assertTrue(summary.isEmpty)
    assertNull(summary.path)
    assertNull(summary.resolution)
    assertEquals(0, summary.subtitleCount)
  }

  @Test
  fun `multi-source version label follows selected source`() {
    val mk4 =
      EmbyMediaSource(
        Id = "4k",
        Name = "4K",
        Container = "mkv",
        Path = "/media/4k.mkv",
        MediaStreams = listOf(EmbyMediaStream(Type = "Video", Codec = "hevc", Width = 3840, Height = 2160)),
      )
    val hd =
      EmbyMediaSource(
        Id = "hd",
        Name = "1080P",
        Container = "mp4",
        Path = "/media/hd.mp4",
        MediaStreams = listOf(EmbyMediaStream(Type = "Video", Codec = "h264", Width = 1920, Height = 1080)),
      )
    val item = EmbyItem(Id = "x", MediaSources = listOf(mk4, hd))

    val summary = buildEmbyMediaInfoSummary(item, selectedMediaSourceId = "hd")

    assertEquals("1080P", summary.version)
    assertEquals("1920×1080", summary.resolution)
    assertEquals("/media/hd.mp4", summary.path)
  }

  // ── 版本名前缀补全（Emby 会削掉多版本文件的公共前缀）──

  @Test
  fun `stripped version prefix is completed from the file name`() {
    val a = EmbyMediaSource(Id = "a", Name = "522A.", Container = "mp4", Path = "/media/HD/KAWD-522A.mp4")
    val b = EmbyMediaSource(Id = "b", Name = "522B.", Container = "mp4", Path = "/media/HD/KAWD-522B.mp4")

    assertEquals("KAWD-522A", embyMediaSourceLabel(a))
    assertEquals("KAWD-522B", embyMediaSourceLabel(b))
  }

  @Test
  fun `version prefix completion also works with dash separated codes and windows paths`() {
    val fourK = EmbyMediaSource(
      Id = "4k",
      Name = "229-4K",
      Container = "mkv",
      Path = "D:\\media\\HD\\DGCEMD-229-4K\\DGCEMD-229-4K.mkv",
    )
    assertEquals("DGCEMD-229-4K", embyMediaSourceLabel(fourK))

    val underscore = EmbyMediaSource(Id = "u", Name = "123", Path = "/media/SSIS_123.mp4")
    assertEquals("SSIS_123", embyMediaSourceLabel(underscore))
  }

  @Test
  fun `clean version names are left alone`() {
    // 前缀里带点 ⇒ 那是片名/年份段，补上去只会更长更乱
    val byYear = EmbyMediaSource(Id = "y", Name = "1080p", Path = "/media/Demo.2024.1080p.mkv")
    assertEquals("1080p", embyMediaSourceLabel(byYear))

    // 名字根本不是文件名后缀 ⇒ 无从补起
    val unrelated = EmbyMediaSource(Id = "x", Name = "1080P", Path = "/media/hd.mp4")
    assertEquals("1080P", embyMediaSourceLabel(unrelated))

    // 没有 Path / Name 为空 ⇒ 走原来的兜底
    val noPath = EmbyMediaSource(Id = "n", Name = "4K")
    assertEquals("4K", embyMediaSourceLabel(noPath))
  }

  @Test
  fun `local custom label still wins over the completed prefix`() {
    val source = EmbyMediaSource(Id = "a", Name = "522A.", Path = "/media/KAWD-522A.mp4")
    assertEquals("KAWD-522A", embyMediaSourceTitle(source))
    assertEquals("中字 4K", embyMediaSourceTitle(source, "中字 4K"))
  }

  // ── 回归：用户现场那两行（`522A.(mp4)` / `522B.(mp4)`）──

  @Test
  fun `container annotation left by emby is stripped before matching`() {
    // Emby 生成版本名时只切掉扩展名、把点留下，个别版本还带一层容器括注：
    // 直接把 `522A.(mp4)` 当子串比，永远匹配不到 `KAWD-522A` —— 这是上一版漏掉的主因
    val a = EmbyMediaSource(
      Id = "a",
      Name = "522A.(mp4)",
      Container = "mp4",
      Path = "/media/HD/KAWD-522A.mp4",
    )
    val b = EmbyMediaSource(
      Id = "b",
      Name = "522B.(mp4)",
      Container = "mp4",
      Path = "/media/HD/KAWD-522B.mp4",
    )

    assertEquals("KAWD-522A", embyMediaSourceLabel(a))
    assertEquals("KAWD-522B", embyMediaSourceLabel(b))

    // 裸扩展名（没括注）同样要剥
    val bare = EmbyMediaSource(Id = "c", Name = "522A.mp4", Path = "/media/HD/KAWD-522A.mp4")
    assertEquals("KAWD-522A", embyMediaSourceLabel(bare))
  }

  @Test
  fun `prefix is completed from the item title when media source has no path`() {
    // 详情页拿到的 MediaSources 不一定带 Path（不同查询 / 不同服务端版本），
    // 这时只能靠标题里的番号兜底：标题 `KAWD-522 …` + 版本名 `522A` ⇒ `KAWD-522A`
    val noPath = EmbyMediaSource(Id = "a", Name = "522A.", Container = "mp4")
    assertEquals("KAWD-522A", embyMediaSourceLabel(noPath, titleHint = "KAWD-522 某部影片"))
    assertEquals("KAWD-522A", embyMediaSourceTitle(noPath, null, "KAWD-522 某部影片"))

    // 带容器括注的写法同样能补
    val annotated = EmbyMediaSource(Id = "a", Name = "522A.(mp4)")
    assertEquals("KAWD-522A", embyMediaSourceLabel(annotated, titleHint = "KAWD-522 某部影片"))
  }

  @Test
  fun `title based completion refuses names that are not the code tail`() {
    // 分辨率类版本名与番号里的数字无关 ⇒ 不动
    val hd = EmbyMediaSource(Id = "h", Name = "1080p")
    assertEquals("1080p", embyMediaSourceLabel(hd, titleHint = "KAWD-522 某部影片"))

    // `5220P` 的前四位是 `5220`，不能当成番号 `522` 后面跟个 `0`
    val bogus = EmbyMediaSource(Id = "x", Name = "5220P")
    assertEquals("5220P", embyMediaSourceLabel(bogus, titleHint = "KAWD-522 某部影片"))

    // 标题里压根没有番号 ⇒ 不猜
    val noCode = EmbyMediaSource(Id = "n", Name = "522A.")
    assertEquals("522A.", embyMediaSourceLabel(noCode, titleHint = "某部影片"))
  }
}
