package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChineseSubtitleFilter] 的行为测试（清单 1.2）。
 *
 * 这个筛选器是「中文字幕」筛选功能的全部实现，全靠客户端按路径猜
 * （Emby 服务端没有对应参数），而且规则里有**一串刻意设计的边界**：
 *  - 《简·爱》里的「简」不算简体标记（单字必须整词）
 *  - `Movie.CHS.1080p` 算中字，`CHSDVD`（粘连）不算（拉丁两侧要边界）
 *  - `-C` 算，`-CD` / `-CH` 不算（右侧要边界）
 *  - `简繁中字` 里的「中字」要能命中（多字汉字走子串）
 *  - percent-encode 过的 strm URL 解码后才判
 *
 * 这些规则改坏了不会编译报错、也不会崩，只会让用户「筛出来的东西不对」——
 * 正是最需要测试钉住、也最容易被后续重构悄悄改掉的一类逻辑。
 */
class ChineseSubtitleFilterTest {
  @Before
  fun reset() {
    // 每个测试都从默认配置出发（configure 内部有 signature 短路，
    // 不复位的话前一个测试的自定义配置会漏到下一个）
    ChineseSubtitleFilter.configure(ChineseSubtitleMarks())
  }

  private fun markerOf(path: String): String? = ChineseSubtitleFilter.hitOfPath(path)?.marker

  @Test
  fun `目录上的标记也要命中`() {
    // 真实场景：标记写在目录里，只盯文件名会漏
    assertEquals(
      "-C",
      markerOf("/volume1/smartstrm/strm/av/新下/HMN-864-C/489155.com@HMN-864-C.(mp4).strm"),
    )
  }

  @Test
  fun `只盯文件名时目录里的标记不算`() {
    val onlyName = ChineseSubtitleMarks(markers = listOf("-C"), matchWholePath = false)
    ChineseSubtitleFilter.configure(onlyName)
    assertNull(
      "matchWholePath=false 时 /HMN-864-C/ 这个目录不该参与匹配",
      markerOf("/volume1/HMN-864-C/489155.com@HMN-864.(mp4).strm"),
    )
  }

  @Test
  fun `拉丁标记右侧必须是非字母数字`() {
    assertNotNull("-C 后面跟点应当命中", markerOf("/m/HMN-864-C.mkv"))
    assertNull("-CD 粘连不应命中 -C", markerOf("/m/HMN-864-CD.mkv"))
    assertNull("-CH 粘连不应命中 -C", markerOf("/m/HMN-864-CH.mkv"))
  }

  @Test
  fun `纯字母数字标记两侧都要边界`() {
    assertNotNull("CHS 点分隔应当命中", markerOf("/m/Movie.CHS.1080p.mkv"))
    assertNotNull("下划线也算边界", markerOf("/m/Movie_CHS_1080p.mkv"))
    assertNull("CHSDVD 粘连不算", markerOf("/m/Movie.CHSDVD.1080p.mkv"))
  }

  @Test
  fun `单个汉字必须整词匹配`() {
    // 片名里的「简」不算简体标记。注意三种写法都要挡住：
    assertNull("《简爱》里的简不应命中", markerOf("/m/简爱.2020.1080p.mkv"))
    assertNull("《简·爱》里的简不应命中（间隔号）", markerOf("/m/简·爱.2020.1080p.mkv"))
    // ⚠️ 这一条曾经真的漏判：文件名里 `.` 才是最常见的分隔符，
    // 只把间隔号当边界的话 `简.爱` 会被误判成简体标记。
    assertNull("《简.爱》里的简不应命中（点分隔）", markerOf("/m/简.爱.2020.1080p.mkv"))

    // 独立成词、后面跟的是数字/拉丁 ⇒ 才算标记
    assertEquals("简", markerOf("/m/某片.简.1080p.mkv"))
    assertEquals("繁", markerOf("/m/某片.繁.1080p.mkv"))
    // 分隔符不限于 `.`，资源库里 `-` / `_` / 空格分隔同样常见
    assertEquals("简", markerOf("/m/某片-简-1080p.mkv"))
    assertEquals("简", markerOf("/m/某片_简_1080p.mkv"))
    assertEquals("简", markerOf("/m/某片 简 1080p.mkv"))
    // 间隔号不算分隔符 —— 《简·爱》必须保持整段，否则「简」会被当成独立段
    assertNull("间隔号不能把简切开", markerOf("/m/某片.简·爱.2020.mkv"))

    // ⚠️ 已知取舍：`爱.简.2020`（标记字夹在片名中间）会被判成命中。
    // 这是**刻意**的 —— 左侧若也跨分隔符判汉字，`某片.简.1080p`
    // 这种真命中反而会被误杀（中文资源命名里「某片」这种通用占位极常见）。
    // 宁可漏判一条资源，也不能让整部影片从筛选结果里消失。
    // 详见 ChineseSubtitleRules.isTokenBoundary 的注释。
  }

  @Test
  fun `多字汉字标记走子串匹配`() {
    // 「简繁中字」里同时含 `繁中` 和 `中字`，两个都是默认标记。
    // 正则取**最左匹配**（`Regex.find` 的语义），所以返回的是位置更靠前的 `繁中`。
    // 这不是 bug：用户只关心「有没有中字标记」，不关心具体命中哪一个。
    assertEquals("繁中", markerOf("/m/某片.简繁中字.1080p.mkv"))
    assertEquals("中文字幕", markerOf("/m/某片.中文字幕.1080p.mkv"))
    assertEquals("内封中字", markerOf("/m/某片.内封中字.mkv"))
    // 没有歧义时就该命中期望的那个
    assertEquals("中字", markerOf("/m/某片.官方中字.1080p.mkv"))
  }

  @Test
  fun `大小写不敏感`() {
    assertNotNull(markerOf("/m/Movie.chs.1080p.mkv"))
    assertNotNull(markerOf("/m/Movie.-c.1080p.mkv"))
  }

  @Test
  fun `percent-encode 过的路径解码后才判`() {
    // 「中字」encode 之后是 %E4%B8%AD%E5%AD%97
    val encoded = "/m/%E6%9F%90%E7%89%87.%E4%B8%AD%E5%AD%97.1080p.mkv"
    assertEquals("中字", markerOf(encoded))
  }

  @Test
  fun `query 串不参与匹配`() {
    // ?t=xxx 之类不该影响判定（decode 时已 substringBefore('?')）
    assertNull(markerOf("/m/Movie.1080p.mkv?token=CHS"))
  }

  @Test
  fun `空路径不算命中也不抛异常`() {
    assertNull(ChineseSubtitleFilter.hitOfPath(null))
    assertNull(ChineseSubtitleFilter.hitOfPath(""))
    assertNull(ChineseSubtitleFilter.hitOfPath("   "))
  }

  @Test
  fun `没有任何标记的路径不算命中`() {
    assertNull(markerOf("/m/Some.Movie.2020.1080p.WEB-DL.x264-GROUP.mkv"))
  }

  @Test
  fun `自定义配置立即生效并清掉旧缓存`() {
    ChineseSubtitleFilter.configure(
      ChineseSubtitleMarks(markers = listOf("MYTAG"), matchWholePath = true),
    )
    assertEquals("MYTAG", markerOf("/m/film.MYTAG.mkv"))
    // 默认标记应不再生效
    assertNull("换成 MYTAG 后 -C 不该再命中", markerOf("/m/film-C.mkv"))

    // 换回默认
    ChineseSubtitleFilter.configure(ChineseSubtitleMarks())
    assertEquals("-C", markerOf("/m/film-C.mkv"))
    assertNull("换回默认后 MYTAG 不该再命中", markerOf("/m/film.MYTAG.mkv"))
  }

  @Test
  fun `parse 支持多种分隔符且回退到默认`() {
    val parsed = ChineseSubtitleMarks.parse("-C, CHS\n中字、简体；繁體|GB", matchWholePath = true)
    assertTrue(parsed.markers.containsAll(listOf("-C", "CHS", "中字", "简体", "繁體", "GB")))

    // 全是空白 ⇒ 回退默认，不能变成「一个都不匹配」
    val fallback = ChineseSubtitleMarks.parse("   ,  ;\n", matchWholePath = true)
    assertEquals(ChineseSubtitleMarks.DEFAULT_MARKERS.size, fallback.markers.size)
  }

  @Test
  fun `parse 丢弃超长标记防止塞正则`() {
    val parsed = ChineseSubtitleMarks.parse("-C\n" + "x".repeat(200), matchWholePath = true)
    assertTrue("超长标记应被丢掉", parsed.markers.none { it.length > 24 })
    assertTrue("正常标记应保留", parsed.markers.contains("-C"))
  }

  @Test
  fun `json 往返与损坏输入兜底`() {
    val marks = ChineseSubtitleMarks(markers = listOf("A", "B"), matchWholePath = false)
    val restored = ChineseSubtitleMarks.fromJson(marks.toJson())
    assertEquals(marks.markers, restored.markers)
    assertEquals(marks.matchWholePath, restored.matchWholePath)

    assertEquals(
      "损坏 JSON 应回退到默认而不是抛异常",
      ChineseSubtitleMarks.DEFAULT_MARKERS.size,
      ChineseSubtitleMarks.fromJson("{ not json").markers.size,
    )
    assertEquals(
      ChineseSubtitleMarks.DEFAULT_MARKERS.size,
      ChineseSubtitleMarks.fromJson(null).markers.size,
    )
  }

  @Test
  fun `signature 随配置变化`() {
    val a = ChineseSubtitleMarks(markers = listOf("A"), matchWholePath = true)
    val b = ChineseSubtitleMarks(markers = listOf("A"), matchWholePath = false)
    val c = ChineseSubtitleMarks(markers = listOf("B"), matchWholePath = true)
    assertTrue("matchWholePath 变了 signature 必须变", a.signature() != b.signature())
    assertTrue("markers 变了 signature 必须变", a.signature() != c.signature())
    assertEquals(a.signature(), ChineseSubtitleMarks(markers = listOf("A"), matchWholePath = true).signature())
  }
}
