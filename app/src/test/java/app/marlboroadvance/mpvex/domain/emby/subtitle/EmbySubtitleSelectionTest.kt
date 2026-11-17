package app.marlboroadvance.mpvex.domain.emby.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emby 字幕「发现 → 归一化 → 选择」的纯逻辑回归网。
 *
 * 覆盖需求第十二节的场景 A / B / C / D / F / I（本地引擎部分）以及语言归一化的各种写法。
 * 不碰网络、不碰 Android —— 本机 Gradle 单测会卡死，用
 * `.verify/run_emby_subtitle_tests.py` 直接编译这一组文件跑 JUnit。
 */
class EmbySubtitleSelectionTest {
  // ── 构造工具 ──

  private fun sub(
    index: Int,
    lang: String?,
    codec: String? = "subrip",
    external: Boolean = true,
    text: Boolean? = true,
    isDefault: Boolean = false,
    forced: Boolean = false,
    hi: Boolean = false,
    displayTitle: String? = null,
    displayLanguage: String? = null,
    path: String? = null,
    deliveryUrl: String? = null,
    externalUrl: Boolean? = null,
    mediaSourceId: String? = "ms1",
  ): SubtitleInfo = EmbySubtitleMapper.fromFacts(
    SubtitleStreamFacts(
      index = index,
      codec = codec,
      language = lang,
      displayTitle = displayTitle,
      displayLanguage = displayLanguage,
      isExternal = external,
      isTextSubtitle = text,
      isDefault = isDefault,
      isForced = forced,
      isHearingImpaired = hi,
      deliveryUrl = deliveryUrl,
      isExternalUrl = externalUrl,
      path = path,
    ),
    mediaSourceId,
  )!!

  // ══════════════════════════════════════════════════════════════════════
  // 语言归一化
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `三字母码与大小写都归一`() {
    assertEquals("zh", EmbySubtitleLanguage.baseOf("chi"))
    assertEquals("zh", EmbySubtitleLanguage.baseOf("ZHO"))
    assertEquals("en", EmbySubtitleLanguage.baseOf("eng"))
    assertEquals("en", EmbySubtitleLanguage.baseOf("English"))
    assertEquals("ja", EmbySubtitleLanguage.baseOf("jpn"))
    assertEquals("zh", EmbySubtitleLanguage.baseOf("zh-CN"))
    assertNull(EmbySubtitleLanguage.baseOf("und"))
    assertNull(EmbySubtitleLanguage.baseOf(""))
    assertNull(EmbySubtitleLanguage.baseOf(null))
  }

  @Test
  fun `简繁靠线索分开`() {
    // region 写法最确定
    assertEquals(EmbySubtitleLanguage.ZH_HANS, EmbySubtitleLanguage.resolve("zh-CN"))
    assertEquals(EmbySubtitleLanguage.ZH_HANT, EmbySubtitleLanguage.resolve("zh-TW"))
    // 服务端本地化文案
    assertEquals(
      EmbySubtitleLanguage.ZH_HANS,
      EmbySubtitleLanguage.resolve("chi", "简体中文"),
    )
    assertEquals(
      EmbySubtitleLanguage.ZH_HANT,
      EmbySubtitleLanguage.resolve("chi", "繁体中文"),
    )
    // 旁挂文件名里的 CHS / CHT
    assertEquals(
      EmbySubtitleLanguage.ZH_HANS,
      EmbySubtitleLanguage.resolve("chi", null, "movie.chs.ass"),
    )
    assertEquals(
      EmbySubtitleLanguage.ZH_HANT,
      EmbySubtitleLanguage.resolve("chi", null, "movie.cht.ass"),
    )
    // 认不出就落回「脚本未知」，不能瞎猜
    assertEquals(EmbySubtitleLanguage.ZH, EmbySubtitleLanguage.resolve("chi"))
  }

  @Test
  fun `简繁不互相覆盖`() {
    // 需求 4.1：zh-CN 的偏好不能把繁体字幕算成命中
    assertFalse(EmbySubtitleLanguage.matches("zh-CN", EmbySubtitleLanguage.ZH_HANT))
    assertFalse(EmbySubtitleLanguage.matches("zh-TW", EmbySubtitleLanguage.ZH_HANS))
    assertTrue(EmbySubtitleLanguage.matches("zh-CN", EmbySubtitleLanguage.ZH_HANS))
    // 但脚本未知时宁可选中
    assertTrue(EmbySubtitleLanguage.matches("zh-CN", EmbySubtitleLanguage.ZH))
    assertTrue(EmbySubtitleLanguage.matches("zh", EmbySubtitleLanguage.ZH_HANT))
  }

  @Test
  fun `语言未知不匹配任何偏好`() {
    assertFalse(EmbySubtitleLanguage.matches("zh-CN", EmbySubtitleLanguage.UNKNOWN))
    assertFalse(EmbySubtitleLanguage.matches("en", EmbySubtitleLanguage.UNKNOWN))
  }

  // ══════════════════════════════════════════════════════════════════════
  // 格式与来源判定
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `ASS 首选原格式保住样式`() {
    assertEquals(listOf("ass", "srt"), EmbySubtitleFormats.requestCandidates("ass"))
    assertEquals(listOf("ass", "ssa", "srt"), EmbySubtitleFormats.requestCandidates("ssa"))
    assertEquals(listOf("srt"), EmbySubtitleFormats.requestCandidates("subrip"))
    assertEquals(listOf("vtt", "srt"), EmbySubtitleFormats.requestCandidates("webvtt"))
  }

  @Test
  fun `图像字幕不当作文本下载`() {
    val pgs = sub(3, "chi", codec = "hdmv_pgs_subtitle", external = true, text = false)
    assertEquals(SubtitleSourceType.IMAGE_BASED, pgs.sourceType)
    // PGS 只能以 .sup 交给 mpv，其它图像字幕一律不支持
    assertEquals(listOf("sup"), pgs.externalRequestFormats)

    val dvd = sub(4, "chi", codec = "dvdsub", external = true, text = false)
    assertTrue(dvd.externalRequestFormats.isEmpty())
    assertTrue(dvd.unsupportedReason!!.contains("图像字幕"))
  }

  @Test
  fun `VobSub 需要成对文件所以不给下载`() {
    val vob = sub(5, "chi", codec = "vobsub", external = true, text = false)
    assertTrue(vob.externalRequestFormats.isEmpty())
  }

  @Test
  fun `DeliveryUrl 优先归为服务端交付`() {
    val s = sub(
      6, "eng", external = true, deliveryUrl = "/Videos/1/ms1/Subtitles/6/Stream.srt",
      externalUrl = true,
    )
    assertEquals(SubtitleSourceType.SERVER_DELIVERED, s.sourceType)
    assertTrue(s.canMountExternally)
  }

  @Test
  fun `内嵌轨不需要下载`() {
    val s = sub(7, "jpn", codec = "ass", external = false, text = true)
    assertEquals(SubtitleSourceType.EMBEDDED, s.sourceType)
    assertTrue(s.externalRequestFormats.isEmpty())
    assertNull(s.unsupportedReason)
  }

  @Test
  fun `字段全空也不会崩`() {
    // 连 Index 都没有 ⇒ 无法用端点定位，直接跳过（不允许因此崩溃）
    assertNull(EmbySubtitleMapper.fromFacts(SubtitleStreamFacts(), null))

    // 只有 Index、其它全空 ⇒ 语言未知、来源退化为内嵌，仍然是一个合法对象
    val bare = EmbySubtitleMapper.fromFacts(SubtitleStreamFacts(index = 1), "ms")
    assertEquals(EmbySubtitleLanguage.UNKNOWN, bare!!.languageKey)
    assertEquals(SubtitleSourceType.EMBEDDED, bare.sourceType)
  }

  // ══════════════════════════════════════════════════════════════════════
  // 选择策略
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `场景A 内嵌简体中文被选中`() {
    val list = listOf(sub(1, "chi", codec = "subrip", external = false, displayLanguage = "简体中文"))
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.PREFERRED_LANGUAGE, result.reason)
  }

  @Test
  fun `场景B 首选简体中文压过内嵌英文和外挂`() {
    val list = listOf(
      sub(1, "eng", codec = "subrip", external = false),
      sub(2, "chi", codec = "ass", external = true, displayLanguage = "简体中文"),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(2, result.chosen?.streamIndex)
    assertEquals("ass", result.chosen?.format)
  }

  @Test
  fun `场景D 简体 繁体 英文三条能正确区分`() {
    val list = listOf(
      sub(1, "chi", codec = "ass", external = true, path = "/m/a.chs.ass"),
      sub(2, "chi", codec = "ass", external = true, path = "/m/a.cht.ass"),
      sub(3, "eng", codec = "srt", external = true),
    )
    val hans = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(1, hans.chosen?.streamIndex)

    val hant = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-TW"))
    assertEquals(2, hant.chosen?.streamIndex)

    val eng = EmbySubtitleSelector.select(list, preferredLanguages = listOf("eng"))
    assertEquals(3, eng.chosen?.streamIndex)
  }

  @Test
  fun `没有首选语言候选时跟随 Emby 默认`() {
    val list = listOf(
      sub(1, "eng", codec = "srt", isDefault = true),
      sub(2, "jpn", codec = "srt"),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.EMBY_DEFAULT, result.reason)
  }

  @Test
  fun `关掉跟随默认就保持关闭`() {
    val list = listOf(sub(1, "eng", codec = "srt", isDefault = true))
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("zh-CN"),
      allowEmbyDefault = false,
    )
    assertNull(result.chosen)
    assertEquals(SubtitlePickReason.NONE, result.reason)
  }

  @Test
  fun `允许语言作为替补`() {
    val list = listOf(sub(1, "chi", codec = "ass", path = "/m/a.cht.ass"))
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("zh-CN"),
      allowedLanguages = listOf("zh-TW"),
      allowEmbyDefault = false,
    )
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.ALLOWED_LANGUAGE, result.reason)
  }

  @Test
  fun `强制字幕不参与自动选择`() {
    val list = listOf(
      sub(1, "eng", codec = "srt", forced = true, isDefault = true),
      sub(2, "eng", codec = "srt"),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("eng"))
    assertEquals(2, result.chosen?.streamIndex)
  }

  @Test
  fun `同语言优先非听障`() {
    val list = listOf(
      sub(1, "eng", codec = "srt", hi = true, isDefault = true),
      sub(2, "eng", codec = "srt"),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("eng"))
    assertEquals(2, result.chosen?.streamIndex)
  }

  @Test
  fun `同语言优先文本字幕而不是图像字幕`() {
    // 顺序故意把 PGS 放前、并标成 default：文本轨仍应胜出。
    // 图像字幕不可检索、不可改样式，外部图像还要额外下载一次 .sup。
    val list = listOf(
      sub(1, "chi", codec = "hdmv_pgs_subtitle", external = false, text = false, isDefault = true),
      sub(2, "chi", codec = "ass", external = false, text = true),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(2, result.chosen?.streamIndex)
  }

  @Test
  fun `记住的选择精确命中`() {
    val list = listOf(
      sub(1, "chi", codec = "ass", path = "/m/a.chs.ass"),
      sub(2, "chi", codec = "ass", path = "/m/b.cht.ass"),
    )
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("zh-CN"),
      rememberedKey = "ms1#2",
    )
    assertEquals(2, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.REMEMBERED, result.reason)
  }

  @Test
  fun `记住的流不存在时退回语言偏好`() {
    val list = listOf(sub(1, "chi", codec = "ass", path = "/m/a.chs.ass"))
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("zh-CN"),
      rememberedKey = "ms99#42",
    )
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.PREFERRED_LANGUAGE, result.reason)
  }

  @Test
  fun `只有图像字幕时不自动选`() {
    val list = listOf(
      sub(1, "chi", codec = "hdmv_pgs_subtitle", external = true, text = false, isDefault = true),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    // PGS 外挂其实支持 .sup，所以这里应被选中；换成 VobSub 才是「不支持」
    assertEquals(1, result.chosen?.streamIndex)

    val vob = listOf(sub(2, "chi", codec = "dvdsub", external = true, text = false, isDefault = true))
    val none = EmbySubtitleSelector.select(vob, preferredLanguages = listOf("zh-CN"))
    assertNull(none.chosen)
    assertTrue(none.trace.any { it.contains("没有一条能被播放器使用") })
  }

  @Test
  fun `空清单给出可追踪的结论`() {
    val result = EmbySubtitleSelector.select(emptyList(), preferredLanguages = listOf("zh-CN"))
    assertEquals(SubtitlePickReason.NONE, result.reason)
    assertTrue(result.detail.contains("未返回"))
  }

  // ══════════════════════════════════════════════════════════════════════
  // 「只剩一种可能」时的兜底（v1.6.22 实测反馈）
  //
  // 用户原话：「拉下来的字幕并不会自动加载到播放器上，要手动勾选才会加载；
  // 只有一个字幕就默认加载，多个字幕按规则智能加载」。
  // ══════════════════════════════════════════════════════════════════════

  @Test
  fun `只有一条字幕时不论语言偏好都加载`() {
    // 片源只有英文字幕、用户偏好简中 —— 以前直接「保持关闭」，表现成「拉下来了却不挂」
    val list = listOf(sub(1, "eng", codec = "ass"))
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.SINGLE_ONLY, result.reason)
  }

  @Test
  fun `多条字幕同属一种语言时挑最优`() {
    // 简中普通轨 + 简中 SDH（Emby 把 SDH 标成默认）—— 偏好英文，两种语言都算不上命中
    val list = listOf(
      sub(1, "chi", codec = "ass", hi = true, isDefault = true),
      sub(2, "chi", codec = "ass"),
    )
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("eng"))
    assertEquals(2, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.ONLY_LANGUAGE, result.reason)
  }

  @Test
  fun `多条不同语言都不匹配时保持关闭`() {
    // 需求 4 末条：没有符合条件的字幕就不加载，绝不随便塞一条
    val list = listOf(sub(1, "eng", codec = "srt"), sub(2, "jpn", codec = "srt"))
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-CN"))
    assertNull(result.chosen)
    assertEquals(SubtitlePickReason.NONE, result.reason)
  }

  @Test
  fun `关掉兜底开关后单条也不加载`() {
    // 「跟随 Emby 默认」关掉 = 用户明确不要「没匹配上也自动选一条」
    val list = listOf(sub(1, "eng", codec = "srt"))
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("zh-CN"),
      allowEmbyDefault = false,
    )
    assertNull(result.chosen)
  }

  @Test
  fun `没配语言偏好时按系统语言兜底`() {
    val list = listOf(
      sub(1, "eng", codec = "srt"),
      sub(2, "chi", codec = "ass", path = "/m/a.chs.ass"),
    )
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = emptyList(),
      localeLanguages = listOf("zh-CN"),
    )
    assertEquals(2, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.SYSTEM_LANGUAGE, result.reason)
  }

  @Test
  fun `首选语言永远压过系统语言`() {
    val list = listOf(
      sub(1, "eng", codec = "srt"),
      sub(2, "chi", codec = "ass", path = "/m/a.chs.ass"),
    )
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("eng"),
      localeLanguages = listOf("zh-CN"),
    )
    assertEquals(1, result.chosen?.streamIndex)
    assertEquals(SubtitlePickReason.PREFERRED_LANGUAGE, result.reason)
  }

  @Test
  fun `允许语言限制也管住兜底`() {
    // 用户填了「只还允许繁体」，片源只有简中 ⇒ 不该用兜底把它塞进来
    val list = listOf(sub(1, "chi", codec = "ass", path = "/m/a.chs.ass"))
    val result = EmbySubtitleSelector.select(
      list,
      preferredLanguages = listOf("eng"),
      allowedLanguages = listOf("zh-TW"),
    )
    assertNull(result.chosen)
    assertEquals(SubtitlePickReason.NONE, result.reason)
  }

  @Test
  fun `中文优先级按用户给出的顺序`() {
    val list = listOf(
      sub(1, "chi", codec = "ass", path = "/m/a.cht.ass"),
      sub(2, "chi", codec = "ass", path = "/m/b.chs.ass"),
    )
    // 用户先繁后简
    val result = EmbySubtitleSelector.select(list, preferredLanguages = listOf("zh-TW", "zh-CN"))
    assertEquals(1, result.chosen?.streamIndex)
  }
}
