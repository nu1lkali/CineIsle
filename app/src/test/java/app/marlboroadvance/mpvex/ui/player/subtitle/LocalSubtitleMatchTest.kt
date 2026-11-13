package app.marlboroadvance.mpvex.ui.player.subtitle

import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalSubtitleMatch] 的行为测试。
 *
 * 匹配口径是这个功能唯一的风险点（松了满屏无关字幕、紧了什么都出不来），
 * 所以两头都要钉住：
 *  - 番号必须认得出（大小写 / 下划线 / 后缀不同也算同一条）
 *  - 发布标签不能当番号（`BD1080`、`1080` 这类）
 *  - 集号**单独**不足以命中（`03` 在字幕目录里满地都是）
 *  - 标题重合度不足一半就不出（宁缺勿错）
 */
class LocalSubtitleMatchTest {

  // ── 番号 ────────────────────────────────────────────────────────────────────

  @Test
  fun `番号命中_同一条的不同写法`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123-C.mkv")
    val hit = LocalSubtitleMatch.match(query, "SSIS-123/首华.zh.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
    assertEquals(100, hit.score)
    assertEquals("SSIS-123", query.displayCode)
  }

  @Test
  fun `番号命中_下划线小写与语言后缀`() {
    val query = LocalSubtitleMatch.buildQuery("ssis_123.mkv")
    val hit = LocalSubtitleMatch.match(query, "ssis_123.chs.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
  }

  @Test
  fun `番号命中_FC2`() {
    val query = LocalSubtitleMatch.buildQuery("FC2-PPV-1234567.mp4")
    val hit = LocalSubtitleMatch.match(query, "FC2-PPV-1234567.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
  }

  @Test
  fun `番号命中_番号只在子目录上`() {
    // 文件名里一点番号都没有，只有目录名带 —— 这是最常见的一种摆法
    val query = LocalSubtitleMatch.buildQuery("[Group] ABC-123 1080p WEB-DL.mkv")
    val hit = LocalSubtitleMatch.match(query, "ABC-123/简体.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
  }

  @Test
  fun `番号命中_素人六位三段`() {
    // 真实样本：视频 011214-521-...，字幕 011214-521-carib-high_1.ja.merged.whisperjav.chinese.srt
    val query =
      LocalSubtitleMatch.buildQuery("011214-521-carib-high_1.ja.merged.whisperjav.mp4")
    val hit =
      LocalSubtitleMatch.match(
        query,
        "011214-521-carib-high_1.ja.merged.whisperjav.chinese.srt",
      )

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
    assertEquals("011214-521", query.displayCode)
  }

  @Test
  fun `发布标签不是番号`() {
    val query = LocalSubtitleMatch.buildQuery("Show.BD-1080p.WEB-DL.mkv")
    assertTrue("BD-1080 不能被当成番号", query.codeKeys.isEmpty())
  }

  @Test
  fun `年份不是番号`() {
    val query = LocalSubtitleMatch.buildQuery("Some.Movie.2024-GROUP.mkv")
    assertTrue("2024 不能被当成番号", query.codeKeys.none { it.endsWith("2024") })
  }

  // ── CRC ─────────────────────────────────────────────────────────────────────

  @Test
  fun `CRC 目录命中`() {
    val media = "Movie.2024.1080p.mkv"
    val query = LocalSubtitleMatch.buildQuery(media)
    val crc = ChecksumUtils.getCRC32(media)

    val hit = LocalSubtitleMatch.match(query, "$crc/中文.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Crc, hit!!.reason)
  }

  // ── 标题 / 集号 ─────────────────────────────────────────────────────────────

  @Test
  fun `番剧_标题加集号`() {
    val query = LocalSubtitleMatch.buildQuery("[SubsPlease] Frieren - 01 (1080p) [ABCD1234].mkv")
    val hit = LocalSubtitleMatch.match(query, "Frieren - 01.chs.ass")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Episode, hit!!.reason)
    assertTrue(hit.score >= 60)
  }

  @Test
  fun `电影_只靠标题`() {
    val query = LocalSubtitleMatch.buildQuery("The.Dark.Knight.2008.1080p.BluRay.mkv")
    val hit = LocalSubtitleMatch.match(query, "The Dark Knight.zh.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Title, hit!!.reason)
  }

  @Test
  fun `集号相同但标题不同_不出`() {
    // 「同一集号 + 完全不同的片子」是最典型的一次误判，必须挡住
    val query = LocalSubtitleMatch.buildQuery("Breaking.Bad.S05E16.720p.mkv")
    assertNull(LocalSubtitleMatch.match(query, "Better.Call.Saul.S05E16.srt"))
  }

  @Test
  fun `毫不相干_不出`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123.mkv")
    assertNull(LocalSubtitleMatch.match(query, "random/comment.srt"))
  }

  @Test
  fun `空条件时查询标记为空`() {
    val query = LocalSubtitleMatch.buildQuery("")
    assertTrue(query.isEmpty)
    assertNull(query.label)
  }

  // ── 文案依据 ────────────────────────────────────────────────────────────────

  @Test
  fun `文案依据_有番号用番号_没番号用片名`() {
    assertEquals(
      "011214-521",
      LocalSubtitleMatch.buildQuery("011214-521-carib-high_1.ja.merged.whisperjav.mp4").label,
    )
    assertEquals(
      "The Dark Knight",
      LocalSubtitleMatch.buildQuery("The.Dark.Knight.2008.1080p.BluRay.mkv").label,
    )
  }
}
