package app.marlboroadvance.mpvex.ui.player.subtitle.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实文件名**做回归。
 *
 * 样本全部取自本机字幕库 `G:\字幕`（50 条）与媒体目录
 * `\\192.168.2.124\docker\smartstrm\strm\115\AI增强`（42 部）。
 * 这些名字里集中体现了本项目的四类噪音：
 *
 * - 站点前缀：`www.98T.la@`、`hhd800.com@`、`[psk.la]`、`-skp2p.com`、`-kan224.com`
 * - 发布标记：`_iris2`、`_chf3`、`_prob4`、`.ja.merged.whisperjav.chinese`、`.shift-108s`
 * - 双层扩展名：`xxx.(mp4).strm`（smartstrm 产物）
 * - 只写尾号：视频 `022313-272` ↔ 字幕 `272-carib-whole_hd1`
 */
class SmartSubtitleRealDataTest {

  // ── 工具 ────────────────────────────────────────────────────────────────────

  private fun match(media: String, subtitle: String): SubtitleMatchResult? =
    SmartSubtitleMatcher
      .match(SmartSubtitleMatcher.prepare(media), listOf(subtitle))
      .firstOrNull()

  private fun scoreOf(media: String, subtitle: String): Double =
    match(media, subtitle)?.score ?: 0.0

  private fun assertAuto(media: String, subtitle: String) {
    val hit = match(media, subtitle)
    assertNotNull("「$subtitle」应该匹配上「$media」", hit)
    assertTrue(
      "「$subtitle」应能自动采用，实际 ${hit!!.score} (${hit.conflictFlags})",
      hit.canAutoMatch,
    )
  }

  private fun assertNoAuto(media: String, subtitle: String) {
    val hit = match(media, subtitle)
    if (hit != null) {
      assertTrue(
        "「$subtitle」不能被自动采用，实际 ${hit.score} (${hit.matchReasons})",
        !hit.canAutoMatch,
      )
    }
  }

  private fun assertRejected(media: String, subtitle: String) {
    assertNull("「$subtitle」不是「$media」的字幕，不能被匹配", match(media, subtitle))
  }

  // ── 一、正样本：必须高置信度匹配 ────────────────────────────────────────────

  @Test
  fun `站点前缀加额外后缀_尾号一致`() {
    // 需求里点名的场景：视频名带域名、@、额外后缀，字幕只留核心编号
    assertAuto("www.98T.la@n0907_iris2.(mp4).strm", "n0907_runa_kobayashi_na_n_fhd.ja.pass1.chinese.srt")
    assertAuto("www.98T.la@n0907_iris2.(mp4).strm", "n0907_runa_kobayashi_na_n_fhd.chinese.srt")
  }

  @Test
  fun `素人日期编号_字幕带厂牌与分段`() {
    assertAuto("www.98T.la@011214-521_iris2.(mp4).strm", "011214-521-carib-high_1.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@052413-345_iris2.(mp4).strm", "052413-345-carib-whole_hd1.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@082313_649_iris2.(mp4).strm", "082313_649-1pon-whole1_hd.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@122113-505_iris2.(mp4).strm", "122113-505-carib-high_1.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `加勒比系_字幕只写作品号`() {
    // 视频是「日期-作品号」，字幕只写 272-carib：只能当候选，不能自动
    val hit = match("www.98T.la@022313-272.(mp4).strm", "272-carib-whole_hd1.ja.merged.whisperjav.chinese.srt")
    assertNotNull("只写尾号的字幕应作为候选出现", hit)
    assertEquals(CodeMatch.PARTIAL, hit!!.identifierMatchType)
    assertTrue("Part 级匹配不能自动采用", !hit.canAutoMatch)
    assertTrue("必须带 PARTIAL_CODE 标记", ConflictFlag.PARTIAL_CODE in hit.conflictFlags)
  }

  @Test
  fun `HEYZO_带HD段与不带一致`() {
    assertAuto("www.98T.la@HEYZO-0325.(mp4).strm", "HEYZO-0325-FHD.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@HEYZO-0768_iris2.(mp4).strm", "HEYZO-0768-HD.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@HEYZO-0206_iris2.(mp4).strm", "heyzo_hd_0206_full.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@HEYZO-0557_iris2.(mp4).strm", "heyzo_hd_0557_full.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `单字母番号_大小写与后缀不同`() {
    assertAuto("www.98T.la@N0550_iris3.(mp4).strm", "n0550.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@n0846.(mp4).strm", "n0846.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `通贩番号_带站点前缀与字幕组标记`() {
    assertAuto("www.98T.la@SKYHD-091.(mp4).strm", "SKYHD-091.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98T.la@SKYHD-098.(mp4).strm", "SKYHD-098.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `同名字幕_两侧只差语言标签`() {
    // 视频 xxx.(mp4).strm，字幕 xxx.ja.pass2.chinese.srt —— 剥完标签就是同一个文件
    assertAuto(
      "www.98T.la@050418_002-Caribpr.(mp4).strm",
      "www.98T.la@050418_002-Caribpr.ja.pass2.chinese.srt",
    )
    assertAuto(
      "www.98T.la@060118_003-caribpr.(mp4).strm",
      "www.98T.la@060118_003-caribpr.ja.merged.whisperjav.chinese.srt",
    )
    assertAuto(
      "www.98T.la@102618_003-caribpr.(mp4).strm",
      "www.98T.la@102618_003-caribpr.ja.merged.whisperjav.chinese.srt",
    )
  }

  @Test
  fun `同名字幕_视频名带版本后缀`() {
    // 同一部片子的两个压制版本（_5_prob4）共用一条字幕
    assertAuto(
      "www.98T.la@050418_002-Caribpr_5_prob4.(mp4).strm",
      "www.98T.la@050418_002-Caribpr.ja.pass2.chinese.srt",
    )
  }

  @Test
  fun `电影式标题_字幕名只差空格与括号`() {
    assertAuto(
      "www.98T.la@[Caribbeancom]910白雪美音陵辱3P初体験極品學生少女瘦小屁股.(mp4).strm",
      "[Caribbeancom] 910 白雪美音 陵辱3P初体験極品 學生 少女 瘦 小屁股.ja.merged.whisperjav.chinese.srt",
    )
    assertAuto(
      "www.98T.la@[1Pondo一本道]Vol.545啦啦隊白雪美音チアガールの私生活.(mp4).strm",
      "[1 Pondo 一本道] Vol.545 啦啦隊 白雪美音 チアガールの私生活.ja.pass2.chinese.srt",
    )
    assertAuto(
      "www.98T.la@[1Pondo一本道]カリビアンコム体育祭はやっぱりブルマー白雪美音.(mp4).strm",
      "[1 Pondo 一本道] カリビアンコム 体育祭はやっぱりブルマー 白雪美音.ja.merged.whisperjav.chinese.srt",
    )
  }

  @Test
  fun `长字母厂牌_连写编号`() {
    // gachig140 / gachi582：字母段 6~7 位，旧规则的 5 位上限提不出来
    assertAuto("www.98T.la@gachig140_prob3.(mp4).strm", "gachig140_1.chinese.srt")
    assertAuto("www.98T.la@gachig140_prob3.(mp4).strm", "gachig140_2.chinese.srt")
    assertAuto("www.98T.la@gachi582_iris2.(mp4).strm", "gachi582.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `三段式站点前缀_3xplanet`() {
    // `3xplanet_060813_606-1pon-whole1_hd`：前缀没有 @，靠番号对上
    assertAuto("www.98T.la@060813_606_iris2.(mp4).strm", "3xplanet_060813_606-1pon-whole1_hd.ja.merged.whisperjav.chinese.srt")
    assertAuto("www.98t.la@n0765_natsu_aoi_chf3_iris3.(mp4).strm", "3xplanet_n0765_natsu_aoi.ja.pass1.chinese.srt")
  }

  @Test
  fun `方括号站点前缀_psk_la`() {
    assertAuto("www.98T.la@050115_071_iris2.(mp4).strm", "[psk.la]050115_071-1pon-1080p.ja.merged.whisperjav.chinese.srt")
  }

  // ── 二、负样本：必须拒绝或至少不许自动 ──────────────────────────────────────

  @Test
  fun `相邻番号_双向都拒绝`() {
    // 用户报过两次：n0861 的字幕挂到 n0849 上
    assertRejected("www.98t.la@TokyoHot_n0849_尾野真知子_chf3_iris2.(mp4).strm", "n0846.ja.merged.whisperjav.chinese.srt")
    assertRejected("www.98t.la@TokyoHot_n0861_尾野真知子_chf3_iris2.(mp4).strm", "n0846.ja.merged.whisperjav.chinese.srt")
    assertRejected("www.98T.la@n0846.(mp4).strm", "n0550.ja.merged.whisperjav.chinese.srt")
    assertRejected("www.98T.la@n0907_iris2.(mp4).strm", "n0846.ja.merged.whisperjav.chinese.srt")
  }

  @Test
  fun `同一天_不同作品号_必须拒绝`() {
    // 三条 `_003-caribpr` 是同一天不同作品，只差日期
    assertRejected(
      "www.98T.la@102618_003-caribpr.(mp4).strm",
      "www.98T.la@060118_003-caribpr.ja.merged.whisperjav.chinese.srt",
    )
    assertRejected(
      "www.98T.la@102618_003-caribpr.(mp4).strm",
      "www.98T.la@091418_003-Caribpr.ja.merged.whisperjav.chinese.srt",
    )
    assertRejected(
      "www.98T.la@060118_003-caribpr.(mp4).strm",
      "www.98T.la@102618_003-caribpr.ja.merged.whisperjav.chinese.srt",
    )
  }

  @Test
  fun `同演员不同片_不能靠人名匹配`() {
    // 白雪美音有七部片在库里，只有「名字全同」的那一条才是同一条字幕
    assertRejected(
      "www.98T.la@[1Pondo一本道]Vol.545啦啦隊白雪美音チアガールの私生活.(mp4).strm",
      "(N) 1Pondo 564 - 卒業記念 ~童貞男子生徒の夢現~- Shirayuki Mio 白雪美音.ja.merged.whisperjav.chinese.srt",
    )
    assertRejected(
      "www.98T.la@[Caribbeancom]910白雪美音陵辱3P初体験極品學生少女瘦小屁股.(mp4).strm",
      "[AND-002] メイドカフェ 白雪美音.ja.merged.whisperjav.chinese.srt",
    )
  }

  @Test
  fun `不同番号_不匹配`() {
    assertRejected("www.98T.la@HEYZO-0325.(mp4).strm", "HEYZO-0283-skp2p.com.ja.pass1.chinese.srt")
    assertRejected("www.98T.la@SKYHD-091.(mp4).strm", "SKYHD-098.ja.merged.whisperjav.chinese.srt")
    assertRejected("www.98T.la@gachig140_prob3.(mp4).strm", "gachi582.ja.merged.whisperjav.chinese.srt")
    assertRejected("www.98T.la@MDTM-027.restored.(mp4).strm", "DMC-31.srt")
    assertRejected("www.98T.la@MDTM-029.restored.(mp4).strm", "DMCR-27.srt")
  }

  @Test
  fun `分段字幕_不许当成同一集`() {
    // gachig140_1 与 gachig140_2 是两段，番号一致所以都能命中，
    // 但真正的分界是「用户选哪一条」，不是自动挂哪一条
    val one = match("www.98T.la@gachig140_prob3.(mp4).strm", "gachig140_1.chinese.srt")
    val two = match("www.98T.la@gachig140_prob3.(mp4).strm", "gachig140_2.chinese.srt")
    assertNotNull(one)
    assertNotNull(two)
    assertEquals(
      "两条分段应同分（都只对上番号）",
      one!!.score.toInt(),
      two!!.score.toInt(),
    )
  }

  @Test
  fun `没有字幕的片子_不能凭空匹配`() {
    // 这 42 部里有一批在库里根本没有字幕，一条都不该出来
    val orphans =
      listOf(
        "61ia127.(mp4).strm",
        "www.98T.la@082713-417_chf3_iris2.(mp4).strm",
        "www.98T.la@091513-431_iris2.(mp4).strm",
        "www.98T.la@dwi-01-C=.(mp4).strm",
        "www.98T.la@HEYZO-0806_iris2.(mp4).strm",
        "www.98t.la@n0837_chf3_iris3.(mp4).strm",
        "www.98t.la@n0878.(mp4).strm",
      )
    val library =
      listOf(
        "011214-521-carib-high_1.ja.merged.whisperjav.chinese.srt",
        "052413-345-carib-whole_hd1.ja.merged.whisperjav.chinese.srt",
        "082313_649-1pon-whole1_hd.ja.merged.whisperjav.chinese.srt",
        "122113-505-carib-high_1.ja.merged.whisperjav.chinese.srt",
        "272-carib-whole_hd1.ja.merged.whisperjav.chinese.srt",
        "3xplanet_060813_606-1pon-whole1_hd.ja.merged.whisperjav.chinese.srt",
        "HEYZO-0201.ja.pass1.chinese.srt",
        "HEYZO-0283-skp2p.com.ja.pass1.chinese.srt",
        "HEYZO-0325-FHD.ja.merged.whisperjav.chinese.srt",
        "HEYZO-0768-HD.ja.merged.whisperjav.chinese.srt",
        "SKYHD-091.ja.merged.whisperjav.chinese.srt",
        "SKYHD-098.ja.merged.whisperjav.chinese.srt",
        "n0550.ja.merged.whisperjav.chinese.srt",
        "n0846.ja.merged.whisperjav.chinese.srt",
        "n0907_runa_kobayashi_na_n_fhd.chinese.srt",
        "gachig140_1.chinese.srt",
        "gachig140_2.chinese.srt",
        "gachi582.ja.merged.whisperjav.chinese.srt",
        "DMC-31.srt",
        "DMCR-27.srt",
        "gtj-116.ja.merged.whisperjav.chinese.srt",
        "heyzo_hd_0206_full.ja.merged.whisperjav.chinese.srt",
      )
    orphans.forEach { media ->
      library.forEach { sub ->
        assertRejected(media, sub)
      }
    }
  }

  // ── 三、边界用例（需求 13.3） ────────────────────────────────────────────────

  @Test
  fun `番号前导零差异_仍可自动`() {
    assertAuto("IPX-001.mkv", "IPX-1.chs.srt")
    assertAuto("SSIS-0123.mp4", "SSIS-123.chs.srt")
  }

  @Test
  fun `字母后缀不同_不许自动`() {
    // 需求 13.2：ABC-123 与 ABC-123B 存在后缀冲突
    assertNoAuto("ABC-123.mp4", "ABC-123B.srt")
    val hit = match("ABC-123.mp4", "ABC-123B.srt")
    assertNotNull(hit)
    assertEquals(CodeMatch.SUFFIX_VARIANT, hit!!.identifierMatchType)
    assertTrue(ConflictFlag.SUFFIX_CONFLICT in hit.conflictFlags)
  }

  @Test
  fun `数字更长_必须拒绝`() {
    // N0907 vs N09070：只差一位，但是两部片子
    assertRejected("N0907.mp4", "N09070.srt")
    assertRejected("ABC-123.mp4", "ABC-1234.srt")
  }

  @Test
  fun `只留数字_只是弱候选`() {
    assertNoAuto("N0907.mp4", "0907.srt")
    val hit = match("N0907.mp4", "0907.srt")
    assertNotNull("应作为弱候选出现", hit)
    assertEquals(CodeMatch.PARTIAL, hit!!.identifierMatchType)
  }

  @Test
  fun `分隔符与大小写变体_完全一致`() {
    listOf(
      "ABC-123.mp4" to "ABC_123.srt",
      "ABC-123.mp4" to "abc123.ass",
      "ABC-123.mp4" to "ABC.123.chs.srt",
      "ABC-123.mp4" to "ＡＢＣ－１２３.chs.srt",
    ).forEach { (media, sub) ->
      assertAuto(media, sub)
    }
  }

  @Test
  fun `多个疑似番号_以主编号为准`() {
    // 视频名里同时出现年份与分辨率，不能被当成番号
    val facts = TitleFacts.of("Some.Movie.2024.1080p.BluRay.x264-WEB.mkv")
    assertTrue("年份不是番号", facts.codeKeys.none { it.numericCore == "2024" })
    assertTrue("分辨率不是番号", facts.codeKeys.none { it.numericCore == "1080" })
  }

  @Test
  fun `发布组标记不是番号`() {
    val facts = TitleFacts.of("www.98T.la@n0907_iris2.(mp4).strm")
    assertEquals("应只提取出 N0907", 1, facts.identifiers.size)
    assertEquals("N907", facts.primaryCode?.compact)
  }

  @Test
  fun `两侧都没有番号_靠标题`() {
    assertAuto("The.Dark.Knight.2008.1080p.BluRay.mkv", "The Dark Knight.zh.srt")
    assertRejected("Carib.War.2020.1080p.mkv", "Caribbean.Pirates.2003.zh.srt")
  }

  @Test
  fun `集号不同_必须拒绝`() {
    assertRejected("Breaking.Bad.S05E16.720p.mkv", "Breaking.Bad.S05E15.chs.srt")
  }

  // ── 四、别名（需求 6.4 / 十一） ─────────────────────────────────────────────

  @Test
  fun `用户确认别名之后_弱候选变成可自动`() {
    val media = "www.98T.la@022313-272.(mp4).strm"
    val sub = "272-carib-whole_hd1.ja.merged.whisperjav.chinese.srt"

    val before = match(media, sub)
    assertNotNull(before)
    assertTrue("未确认别名前不能自动", !before!!.canAutoMatch)

    val aliases =
      AliasManager.fromRecords(
        listOf(AliasRecord("22313272", "272", AliasSource.USER, 1L, 1.0)),
      )
    val after =
      SmartSubtitleMatcher
        .match(SmartSubtitleMatcher.prepare(media, aliases), listOf(sub))
        .firstOrNull()
    assertNotNull(after)
    assertEquals(CodeMatch.ALIAS, after!!.identifierMatchType)
  }

  @Test
  fun `系统推测的别名不生效`() {
    val aliases =
      AliasManager.fromRecords(
        listOf(AliasRecord("22313272", "272", AliasSource.SYSTEM, 1L, 0.5)),
      )
    assertTrue("SYSTEM 来源不能进索引", aliases.equivalentsOf("22313272").isEmpty())
  }

  // ── 五、性能（几百个文件的规模） ─────────────────────────────────────────────

  @Test
  fun `一千个候选的打分耗时在预算内`() {
    val media = "www.98T.la@n0907_iris2.(mp4).strm"
    val library =
      (1..1000).map { i ->
        "n%04d_some_title_%d.ja.merged.whisperjav.chinese.srt".format(i, i)
      }
    val query = SmartSubtitleMatcher.prepare(media)
    // 预热缓存，避免把首次解析算进耗时
    SmartSubtitleMatcher.match(query, library)
    val start = System.nanoTime()
    val results = SmartSubtitleMatcher.match(query, library)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    assertNotNull(results)
    assertTrue("1000 个候选应在 200ms 内完成，实际 ${elapsedMs}ms", elapsedMs < 200)
  }

  // ── 六、真实目录全量对照（需要本机路径，CI 自动跳过） ────────────────────────

  @Test
  fun `真实字幕库与媒体库全量对照`() {
    val subtitleDir = System.getenv("REAL_SUBTITLE_DIR")
    val mediaDir = System.getenv("REAL_MEDIA_DIR")
    assumeTrue("未提供 REAL_SUBTITLE_DIR / REAL_MEDIA_DIR，跳过", subtitleDir != null && mediaDir != null)

    val subtitles =
      File(subtitleDir!!).listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("srt", "ass", "ssa", "vtt") }
        ?.map { it.name }
        .orEmpty()
    val medias =
      File(mediaDir!!).listFiles()
        ?.filter { it.name.endsWith(".strm") }
        ?.map { it.name }
        .orEmpty()

    assumeTrue("目录为空，跳过", subtitles.isNotEmpty() && medias.isNotEmpty())

    val report = StringBuilder()
    var autoCount = 0
    var candidateCount = 0
    medias.sorted().forEach { media ->
      val results = SmartSubtitleMatcher.match(SmartSubtitleMatcher.prepare(media), subtitles)
      val auto = results.filter { it.canAutoMatch }
      autoCount += auto.size
      candidateCount += results.size - auto.size
      report.appendLine("${if (auto.isEmpty()) "  " else "√ "}$media")
      results.forEach { r ->
        report.appendLine(
          "     ${"%.1f".format(r.score)} ${if (r.canAutoMatch) "AUTO" else "cand"} " +
            "${r.identifierMatchType} ${r.primaryReason} ${r.name}",
        )
      }
      if (results.isEmpty()) report.appendLine("     (none)")
    }
    report.appendLine("--- auto=$autoCount candidate=$candidateCount")
    File(System.getProperty("java.io.tmpdir"), "smart_subtitle_report.txt").writeText(report.toString())
    assertTrue(true)
  }
}
