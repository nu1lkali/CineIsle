package app.marlboroadvance.mpvex.domain.emby.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页字幕文案（[SubtitlePresentation]）的纯逻辑回归。
 *
 * 重点只有一件事：**「将自动使用的那一条」要说得名字出来**。
 * 主标题仍走归一化语言名（换服务器语言不换说法），实际名称另走 [SubtitlePresentation.actualNameOf]。
 *
 * 不碰 Android / 网络：本机 Gradle 单测会卡死，用
 * `.verify/run_emby_subtitle_tests.py` 直接编译这一组文件跑 JUnit。
 */
class SubtitlePresentationTest {
  private fun sub(
    index: Int = 0,
    lang: String? = "chi",
    codec: String? = "subrip",
    external: Boolean = true,
    displayTitle: String? = null,
    path: String? = null,
  ): SubtitleInfo = EmbySubtitleMapper.fromFacts(
    SubtitleStreamFacts(
      index = index,
      codec = codec,
      language = lang,
      displayTitle = displayTitle,
      isExternal = external,
      isTextSubtitle = true,
      path = path,
    ),
    "ms1",
  )!!

  // ══════════════════════════════════════════════════════════════════════
  // 实际名称
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `外挂字幕的实际名称取磁盘文件名`() {
    val s = sub(
      codec = "ass",
      displayTitle = "中文（简体）- 外挂",
      path = "/media/Movie (2020)/Movie.2020.chs.ass",
    )
    // 服务端的「语言 - 外挂」几条会撞名，只有文件名能定位到具体哪一个文件
    assertEquals("Movie.2020.chs.ass", SubtitlePresentation.actualNameOf(s))
    assertEquals("简体中文", SubtitlePresentation.titleOf(s))
    assertEquals("Movie.2020.chs.ass · 外挂", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `Windows 与 SMB 路径同样只取末段`() {
    val win = sub(path = "D:\\media\\Movie.2020.cht.ass", codec = "ass")
    assertEquals("Movie.2020.cht.ass", SubtitlePresentation.actualNameOf(win))

    val smb = sub(path = "\\\\nas\\share\\Movie.2020.jp.ass", codec = "ass")
    assertEquals("Movie.2020.jp.ass", SubtitlePresentation.actualNameOf(smb))
  }

  @Test
  fun `外挂没有路径时退回服务端名称`() {
    val s = sub(external = true, displayTitle = "中英双语", path = null)
    assertEquals("中英双语", SubtitlePresentation.actualNameOf(s))
    assertEquals("中英双语 · 外挂", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `内嵌轨用 DisplayTitle 当实际名称`() {
    val s = sub(external = false, displayTitle = "Chinese (Simplified)")
    assertEquals("Chinese (Simplified)", SubtitlePresentation.actualNameOf(s))
    assertEquals("Chinese (Simplified) · 内嵌", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `名称与语言名相同时不重复显示`() {
    val s = sub(external = false, displayTitle = "简体中文")
    assertNull(SubtitlePresentation.actualNameOf(s))
    assertEquals("内嵌", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `服务端没给名称时不把拼出来的占位名当实际名称`() {
    // DisplayTitle 缺失时 Mapper 会拼「简体中文 · SRT」，那不是实际名称
    val s = sub(external = false, displayTitle = null)
    assertNull(SubtitlePresentation.actualNameOf(s))
    assertEquals("内嵌", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `服务端名称恰好等于来源标签时副标题只留一份`() {
    val s = sub(external = true, displayTitle = "外挂", path = null)
    assertEquals("外挂", SubtitlePresentation.detailOf(s))
  }

  @Test
  fun `路径末段的边界`() {
    assertNull(SubtitlePresentation.fileNameOf(null))
    assertNull(SubtitlePresentation.fileNameOf(""))
    assertNull(SubtitlePresentation.fileNameOf("   "))
    assertNull(SubtitlePresentation.fileNameOf("/media/"))
    assertEquals("x.ass", SubtitlePresentation.fileNameOf("/media/x.ass"))
  }

  // ══════════════════════════════════════════════════════════════════════
  // 列表口径
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `将使用的那条置顶且带上实际名称`() {
    val first = sub(index = 0, codec = "srt", path = "/media/Movie/Movie.2020.chs.srt")
    val second = sub(index = 1, codec = "ass", path = "/media/Movie/Movie.2020.cht.ass", lang = "zh-TW")

    val rows = SubtitlePresentation.rowsOf(listOf(first, second), pickedKey = second.key)

    assertEquals(second.key, rows.first().key)
    assertEquals("Movie.2020.cht.ass · 外挂", rows.first().detail)
    assertEquals("Movie.2020.chs.srt · 外挂", rows.last().detail)
    assertTrue(rows.first().available)
  }
}
