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
 * 匹配口径是这个功能唯一的风险点（松了满屏无关字幕、紧了什么都出不来），所以两头都要钉住：
 *  - 番号必须认得出（大小写 / 下划线 / 无分隔符 / 紧贴中文）
 *  - 发布标签不能当番号（`BD1080`、`1080` 这类）
 *  - 番号**前缀不同**不能算同一条（`SSIS-123` ≠ `SSIS-1234`）—— 旧版最大的误命中来源
 *  - 词必须按边界命中（`carib` 不能命中 `caribbean`）
 *  - 集号冲突必须否决（同一系列的 E05 字幕不能挂到 E06）
 */
class LocalSubtitleMatchTest {

  // ── 字干 ────────────────────────────────────────────────────────────────────

  @Test
  fun `字干一致_剥掉语言标签后与媒体名相同`() {
    // 真实样本：视频 ...whisperjav.mp4，字幕 ...whisperjav.chinese.srt
    val query =
      LocalSubtitleMatch.buildQuery("011214-521-carib-high_1.ja.merged.whisperjav.mp4")
    val hit =
      LocalSubtitleMatch.match(
        query,
        "011214-521-carib-high_1.ja.merged.whisperjav.chinese.srt",
      )

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Stem, hit!!.reason)
    assertEquals("011214-521", query.displayCode)
  }

  @Test
  fun `字干前缀_差异不含数字时认`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123-C.mkv")
    val hit = LocalSubtitleMatch.match(query, "SSIS-123-C-GROUP.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Stem, hit!!.reason)
  }

  @Test
  fun `字干前缀_差异含数字时不认`() {
    // 差一个数字就是另一部片子，这条前缀规则必须有这条闸门
    val query = LocalSubtitleMatch.buildQuery("SSIS-123.mkv")
    assertNull(LocalSubtitleMatch.match(query, "SSIS-1234.srt"))
  }

  // ── 番号 ────────────────────────────────────────────────────────────────────

  @Test
  fun `番号命中_下划线小写与语言后缀`() {
    val query = LocalSubtitleMatch.buildQuery("ssis_123.mkv")
    val hit = LocalSubtitleMatch.match(query, "ssis_123.chs.srt")

    assertNotNull(hit)
  }

  @Test
  fun `番号命中_FC2`() {
    val query = LocalSubtitleMatch.buildQuery("FC2-PPV-1234567.mp4")
    val hit = LocalSubtitleMatch.match(query, "FC2-PPV-1234567.chinese.srt")

    assertNotNull(hit)
  }

  @Test
  fun `番号命中_只在子目录上`() {
    // 文件名里一点番号都没有，只有目录名带 —— 这是最常见的一种摆法
    val query = LocalSubtitleMatch.buildQuery("[Group] ABC-123 1080p WEB-DL.mkv")
    val hit = LocalSubtitleMatch.match(query, "ABC-123/简体.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
  }

  @Test
  fun `番号命中_素人六位三段`() {
    val query = LocalSubtitleMatch.buildQuery("011214-521-carib-high_1.ja.merged.whisperjav.mp4")
    val hit = LocalSubtitleMatch.match(query, "011214-521 中文字幕.srt")

    assertNotNull(hit)
  }

  @Test
  fun `番号命中_无分隔符连写`() {
    // 视频用连写、字幕用短横线，两边归一化后必须对上
    val query = LocalSubtitleMatch.buildQuery("SSIS00123.mp4")
    val hit = LocalSubtitleMatch.match(query, "SSIS-123.chs.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Code, hit!!.reason)
  }

  @Test
  fun `番号命中_紧贴中文字幕名`() {
    // Java 的 \b 把中文当字母，旧的 \b 写法在这里提取不出番号
    val query = LocalSubtitleMatch.buildQuery("SSIS-123中文字幕.mp4")
    val hit = LocalSubtitleMatch.match(query, "SSIS-123 简体.srt")

    assertNotNull(hit)
  }

  @Test
  fun `番号前缀不同不得命中`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123.mkv")
    // 124 是另一部片子，必须否决；旧版用 contains 会给出 100 分
    assertNull(LocalSubtitleMatch.match(query, "SSIS-124/中文.srt"))
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

  @Test
  fun `番号变体表_同一部片子的各种写法都归一成同一个 key`() {
    // 视频名字里番号的写法五花八门，归一化之后必须落到同一个 (字母段, 数字段)
    val variants =
      listOf(
        "SSIS-123.mkv",
        "ssis-123.mp4",
        "SSIS_123.mkv",
        "SSIS 123.mkv",
        "SSIS.123.mkv",
        "SSIS123.mkv",
        "SSIS00123.mkv",
        "[SSIS-123] 1080p.mkv",
        "hhd800.com@SSIS-123.mkv",
        "SSIS-123中文字幕.mkv",
        "ＳＳＩＳ－１２３.mkv",
        "SSIS-123-UC.mkv",
        "SSIS-123-C.mkv",
        "SSIS-123-4K.Chs.mkv",
      )

    variants.forEach { name ->
      val keys = LocalSubtitleMatch.buildQuery(name).codeKeys
      assertTrue("「$name」应该提取出 SSIS123，实际 $keys", "SSIS123" in keys)
    }
  }

  @Test
  fun `番号变体表_FC2 的几种写法都归一`() {
    listOf(
      "FC2-PPV-1234567.mp4",
      "FC2PPV-1234567.mp4",
      "FC2-PPV1234567.mp4",
      "FC2-1234567.mp4",
      "fc2_ppv_1234567.mp4",
    ).forEach { name ->
      val keys = LocalSubtitleMatch.buildQuery(name).codeKeys
      assertTrue("「$name」应该提取出 FC21234567，实际 $keys", "FC21234567" in keys)
    }
  }

  @Test
  fun `番号变体表_视频与字幕写法不同也能匹配上`() {
    // 这正是「先拿视频番号、再去字幕库匹配」这条链路要保证的东西
    val video = LocalSubtitleMatch.buildQuery("SSIS00123.mkv")

    listOf(
      "SSIS-123.chs.srt",
      "ssis_123.zh.srt",
      "SSIS.123.简体.ass",
      "SSIS 123 中文字幕.srt",
      "ＳＳＩＳ－１２３.chs.srt",
      "[SSIS-123].chs.srt",
    ).forEach { sub ->
      assertNotNull("「$sub」应该匹配上", LocalSubtitleMatch.match(video, sub))
    }
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
    assertTrue(hit.score >= 74)
  }

  @Test
  fun `电影_只靠标题`() {
    val query = LocalSubtitleMatch.buildQuery("The.Dark.Knight.2008.1080p.BluRay.mkv")
    val hit = LocalSubtitleMatch.match(query, "The Dark Knight.zh.srt")

    assertNotNull(hit)
    assertEquals(LocalSubtitleMatch.Reason.Title, hit!!.reason)
  }

  @Test
  fun `标题不因子串命中`() {
    // carib 不能命中 caribbean —— 旧的 contains 口径正是这么错的
    val query = LocalSubtitleMatch.buildQuery("Carib.War.2020.1080p.mkv")
    assertNull(LocalSubtitleMatch.match(query, "Caribbean.Pirates.2003.zh.srt"))
  }

  @Test
  fun `集号相同但标题不同_不出`() {
    val query = LocalSubtitleMatch.buildQuery("Breaking.Bad.S05E16.720p.mkv")
    assertNull(LocalSubtitleMatch.match(query, "Better.Call.Saul.S05E16.srt"))
  }

  @Test
  fun `集号冲突_同一系列的别的集不出`() {
    // 标题完全重合、只有集号不同：这是最容易被误推荐的一种
    val query = LocalSubtitleMatch.buildQuery("Breaking.Bad.S05E16.720p.mkv")
    assertNull(LocalSubtitleMatch.match(query, "Breaking.Bad.S05E15.chs.srt"))
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

  // ── 同番号多条时的排序 ──────────────────────────────────────────────────────

  @Test
  fun `同番号多条_中文优先于英文`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123.mkv")
    val zh = LocalSubtitleMatch.match(query, "SSIS-123/SSIS-123.chs.srt")!!
    val en = LocalSubtitleMatch.match(query, "SSIS-123/SSIS-123.eng.srt")!!

    assertTrue("中文版应排在英文版之前", zh.score > en.score)
  }

  @Test
  fun `同番号多条_版本标记一致者优先`() {
    val query = LocalSubtitleMatch.buildQuery("SSIS-123-UC.mp4")
    val same = LocalSubtitleMatch.match(query, "SSIS-123-UC.chs.srt")!!
    val other = LocalSubtitleMatch.match(query, "SSIS-123.chs.srt")!!

    assertTrue("带相同版本标记的应排更前", same.score > other.score)
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
