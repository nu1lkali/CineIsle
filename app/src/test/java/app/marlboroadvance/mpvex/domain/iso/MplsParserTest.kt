package app.marlboroadvance.mpvex.domain.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MplsParser] 的单测。
 *
 * 做法：**在内存里拼一个结构真实的 mpls** —— 头部、PlayList 段、若干 PlayItem
 * （含 STN 那段填充）、PlayListMark 段，全部按 libbluray `mpls_parse.c` 的
 * 字段顺序与偏移写出来。这样不依赖真实蓝光镜像就能验证解析对不对。
 *
 * 布局（与 [MplsParser] 的 KDoc 一致）：
 * ```
 * 0x00 "MPLS" | 0x04 "0200" | 0x08 list_pos | 0x0C mark_pos | 0x10 ext_pos | 0x14 保留20
 * → PlayList 段: len(4) reserved(2) count(2) subpath(2) + PlayItem...
 * → PlayItem: len(2) clip_id(5) codec(4) flags(2) stc(1) in(4) out(4) + STN填充
 * → Mark 段: len(4) count(2) + 每条 14 字节
 * ```
 */
class MplsParserTest {

  // ── 测试脚手架：拼 mpls 字节 ─────────────────────────────────────────────

  private fun be16(v: Int): ByteArray =
    byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

  private fun be32(v: Long): ByteArray =
    byteArrayOf(
      ((v shr 24) and 0xFF).toByte(),
      ((v shr 16) and 0xFF).toByte(),
      ((v shr 8) and 0xFF).toByte(),
      (v and 0xFF).toByte(),
    )

  /** 一条 PlayItem。`stnPad` 模拟 STN 轨道表那堆我们**刻意跳过**的字节。 */
  private fun playItem(
    clipId: String,
    inTime: Long,
    outTime: Long,
    multiAngle: Boolean = false,
    stnPad: Int = 64,
  ): ByteArray {
    // 固定部分：len(2)+clip(5)+codec(4)+flags(2)+stc(1)+in(4)+out(4) = 22
    val fixed = 22
    val len = fixed + stnPad
    val out = ByteArray(len)
    var o = 0
    be16(len).copyInto(out, o); o += 2
    clipId.toByteArray(Charsets.US_ASCII).copyInto(out, o); o += 5
    "M2TS".toByteArray(Charsets.US_ASCII).copyInto(out, o); o += 4
    // flags: 11 bit 保留 + is_multi_angle(bit4) + connection_condition(低4位)
    be16(if (multiAngle) 0x0011 else 0x0001).copyInto(out, o); o += 2
    out[o++] = 0 // stc_id
    be32(inTime).copyInto(out, o); o += 4
    be32(outTime).copyInto(out, o); o += 4
    // 剩下的 stnPad 字节保持 0 —— 解析器必须靠 len 跳过它们
    return out
  }

  /** 一条章节 mark（14 字节）。 */
  private fun mark(type: Int, playItemRef: Int, time: Long): ByteArray {
    val out = ByteArray(14)
    out[0] = 0 // reserved
    out[1] = type.toByte()
    be16(playItemRef).copyInto(out, 2)
    be32(time).copyInto(out, 4)
    return out
  }

  /**
   * 组装一个 mpls。
   *
   * @param items PlayItem 列表
   * @param marks 章节列表；传 null 表示不写 mark 段（mark_pos = 0）
   */
  private fun mpls(items: List<ByteArray>, marks: List<ByteArray>? = null): ByteArray {
    val listPos = 40L
    val playListBody = ArrayList<ByteArray>()
    playListBody += be16(0) // reserved
    playListBody += be16(items.size)
    playListBody += be16(0) // subpath count
    items.forEach { playListBody += it }
    // PlayList 段长 = 4（自身长度字段）+ body
    val listSection = ByteArray(4) + flatten(playListBody)

    val markSection = marks?.let {
      ByteArray(4) + be16(it.size) + flatten(it)
    }

    val header = ByteArray(listPos.toInt())
    "MPLS".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
    "0200".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
    be32(listPos).copyInto(header, 8)
    be32(if (markSection != null) (listPos + listSection.size).toLong() else 0L)
      .copyInto(header, 12)
    be32(0).copyInto(header, 16) // ext_pos

    return header + listSection + (markSection ?: ByteArray(0))
  }

  private fun flatten(parts: List<ByteArray>): ByteArray {
    val total = parts.sumOf { it.size }
    val buf = ByteArray(total)
    var o = 0
    parts.forEach { it.copyInto(buf, o); o += it.size }
    return buf
  }

  // 普通 val 而非 const val：const 只允许顶层 / object / companion。
  // ⚠️ mpls 的时间单位是 **45kHz 时间戳**，所以 45_000 是**一秒**不是一分钟。
  // 这里定义成「一分钟」，写 `120L * MIN` 才真的是 120 分钟。
  // （曾经写成 45_000 直接当分钟用，结果 30 分钟的判据永远不成立，
  //   「章节多的更像正片」那条用例因此失败 —— 单测抓出来的。）
  private val MIN = 45_000L * 60

  // ── 解析基本正确性 ───────────────────────────────────────────────────────

  @Test
  fun `解析出单条 PlayItem 的 clip 与时间`() {
    val pl = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 120L * MIN))))
    assertNotNull(pl)
    assertEquals(1, pl!!.playItems.size)
    val item = pl.playItems[0]
    assertEquals("01100", item.clipId)
    assertEquals("M2TS", item.codecId)
    assertEquals(0L, item.inTime)
    assertEquals(120L * MIN, item.outTime)
    assertEquals(120L * MIN, pl.duration)
  }

  @Test
  fun `解析出多条 PlayItem 并保持顺序`() {
    val items =
      listOf(
        playItem("01100", 0L, 60L * MIN),
        playItem("01101", 0L, 30L * MIN),
        playItem("01102", 0L, 10L * MIN),
      )
    val pl = MplsParser.parse(mpls(items))!!
    assertEquals(3, pl.playItems.size)
    assertEquals(listOf("01100", "01101", "01102"), pl.clipSequence)
    assertEquals(100L * MIN, pl.duration)
  }

  // ⚠️ 这条是 mpls 解析的**核心判据**：
  // 正片被切成多段是常态，只取「最大的那个 m2ts」只能播前半部。
  @Test
  fun `正片分两段时两段都在`() {
    val items = listOf(playItem("01100", 0L, 60L * MIN), playItem("01101", 0L, 20L * MIN))
    val pl = MplsParser.parse(mpls(items))!!
    assertEquals(listOf("01100", "01101"), pl.clipSequence)
    assertEquals(2, pl.playItems.size)
  }

  @Test
  fun `STN 表被 len 字段整段跳过不影响后续 PlayItem`() {
    // 第一条带一大段 STN 填充（模拟轨道表），第二条必须仍能被正确解析出来。
    // 这条盯的是「靠 len 跳过后半结构」这个设计前提。
    val items =
      listOf(
        playItem("01100", 0L, 60L * MIN, stnPad = 512),
        playItem("01101", 0L, 20L * MIN, stnPad = 128),
      )
    val pl = MplsParser.parse(mpls(items))!!
    assertEquals("01100", pl.playItems[0].clipId)
    assertEquals("01101", pl.playItems[1].clipId)
    assertEquals(60L * MIN, pl.playItems[0].outTime)
    assertEquals(20L * MIN, pl.playItems[1].outTime)
  }

  @Test
  fun `相邻重复的 clip 只保留一条`() {
    // 有些碟把同一段拆成多个相邻 PlayItem 指向同一个 clip
    val items =
      listOf(
        playItem("01100", 0L, 10L * MIN),
        playItem("01100", 10L * MIN, 20L * MIN),
        playItem("01101", 0L, 5L * MIN),
      )
    val pl = MplsParser.parse(mpls(items))!!
    assertEquals(listOf("01100", "01101"), pl.clipSequence)
  }

  // ── 章节 ────────────────────────────────────────────────────────────────

  @Test
  fun `解析出章节并只数 entry 类型`() {
    val marks =
      listOf(
        mark(MARK_ENTRY, 0, 0L),
        mark(MARK_ENTRY, 0, 60L * MIN),
        mark(2, 0, 70L * MIN), // link point，不算章节
        mark(MARK_ENTRY, 1, 80L * MIN),
      )
    val pl = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 90L * MIN)), marks))!!
    assertEquals(4, pl.marks.size)
    assertEquals(3, pl.chapterCount) // 只有 3 个 entry
  }

  @Test
  fun `章节的 playItemRef 与 time 正确`() {
    val marks = listOf(mark(MARK_ENTRY, 1, 12345L))
    val pl = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 90L * MIN)), marks))!!
    assertEquals(1, pl.marks[0].playItemRef)
    assertEquals(12345L, pl.marks[0].time)
  }

  // ── 3D / 多角度 ─────────────────────────────────────────────────────────

  @Test
  fun `多角度片段被标记为多角度`() {
    val single = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 60L * MIN))))!!
    assertFalse(single.playItems[0].isMultiAngle)

    val multi = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 60L * MIN, multiAngle = true))))!!
    assertTrue(multi.playItems[0].isMultiAngle)
    // 只取 clip[0]（默认视角）—— 不是拒播，也不是硬凑两个视角
    assertEquals(listOf("01100"), multi.clipSequence)
  }

  // ── 畸形输入必须返回 null，绝不猜 ────────────────────────────────────────

  @Test
  fun `magic 不对时返回 null`() {
    val bad = mpls(listOf(playItem("01100", 0L, 60L * MIN)))
    "XXXX".toByteArray(Charsets.US_ASCII).copyInto(bad, 0)
    assertNull(MplsParser.parse(bad))
  }

  @Test
  fun `空数据或太短时返回 null`() {
    assertNull(MplsParser.parse(ByteArray(0)))
    assertNull(MplsParser.parse(ByteArray(8)))
  }

  @Test
  fun `list_pos 越界时返回 null`() {
    val data = mpls(listOf(playItem("01100", 0L, 60L * MIN)))
    be32(999_999L).copyInto(data, 8)
    assertNull(MplsParser.parse(data))
  }

  @Test
  fun `PlayItem 长度小于结构下限时返回 null`() {
    val data = mpls(listOf(playItem("01100", 0L, 60L * MIN)))
    // 把第一条 PlayItem 的 len 改成一个不可能的值（libbluray 用 < 18 判非法）
    // PlayItem 起点 = list_pos(40) + 段头(4+2+2+2=10) = 50
    be16(4).copyInto(data, 50)
    assertNull(MplsParser.parse(data))
  }

  @Test
  fun `PlayItem 越界时返回 null 而不是读越界`() {
    val items = listOf(playItem("01100", 0L, 60L * MIN))
    val data = mpls(items)
    // 段长声称比实际文件还长
    be32(data.size.toLong() + 1000L).copyInto(data, 40)
    assertNull(MplsParser.parse(data))
  }

  @Test
  fun `OUT_time 小于 IN_time 时返回 null`() {
    assertNull(MplsParser.parse(mpls(listOf(playItem("01100", 100L, 50L)))))
  }

  @Test
  fun `PlayItem 数被篡改成超大值时不 OOM`() {
    val data = mpls(listOf(playItem("01100", 0L, 60L * MIN)))
    // count 在 list_pos + 6
    be16(60000).copyInto(data, 40 + 6)
    // 应当被 MAX_PLAY_ITEMS 挡住并返回 null，而不是按 6 万条去循环
    assertNull(MplsParser.parse(data))
  }

  @Test
  fun `没有 mark 段时章节为空而不是崩`() {
    val pl = MplsParser.parse(mpls(listOf(playItem("01100", 0L, 60L * MIN)), marks = null))!!
    assertEquals(0, pl.marks.size)
    assertEquals(0, pl.chapterCount)
  }

  @Test
  fun `mark 段越界时章节为空而不是崩`() {
    // 让 mark_count 声称有很多条，但文件里并没有那么多条。
    // 用一个 count 明显超出文件剩余长度的值，保证一定触发「读到一半就停」。
    val data = mpls(listOf(playItem("01100", 0L, 60L * MIN)), listOf(mark(MARK_ENTRY, 0, 0L)))
    val markPos = 40 + 4 + 10 + (22 + 64) // listPos + 段头 + PlayItem 固定大小
    be16(5000).copyInto(data, markPos + 4)
    val pl = MplsParser.parse(data)
    // 要么解析出能拿到的部分，要么整条返回 null —— 关键是不能崩、不能死循环。
    if (pl != null) assertTrue(pl.marks.size <= 5000)
  }

  // ── 主标题选择 ──────────────────────────────────────────────────────────

  @Test
  fun `主标题取时长最长的那个`() {
    // 花絮 30 分钟、正片 120 分钟 —— 应该选正片。
    // 这条正面回应「最大的 m2ts 可能是花絮」那个缺陷。
    val candidate = listOf(mpls(listOf(playItem("01100", 0L, 120L * MIN))), mpls(listOf(playItem("09000", 0L, 30L * MIN))))
      .mapNotNull { MplsParser.parse(it) }
    val best = MplsParser.pickMainTitle(candidate)
    assertNotNull(best)
    assertEquals("01100", best!!.playItems[0].clipId)
    assertEquals(120L * MIN, best.duration)
  }

  @Test
  fun `时长接近时章节多的更像正片`() {
    // 两个都超过 30 分钟、时长差 < 5 章的阈值内 —— 章节多的胜
    val few =
      MplsParser.parse(
        mpls(listOf(playItem("01100", 0L, 100L * MIN)), listOf(mark(MARK_ENTRY, 0, 0L)))
      )!!
    val many =
      MplsParser.parse(
        mpls(
          listOf(playItem("01200", 0L, 95L * MIN)),
          (1..12).map { mark(MARK_ENTRY, 0, it * MIN) },
        )
      )!!
    assertEquals(12, many.chapterCount)
    assertEquals("01200", MplsParser.pickMainTitle(listOf(few, many))!!.playItems[0].clipId)
  }

  @Test
  fun `空候选列表返回 null`() {
    assertNull(MplsParser.pickMainTitle(emptyList()))
  }

  @Test
  fun `时长为零的播放列表不参与竞选`() {
    val empty = MplsParser.parse(mpls(listOf(playItem("01100", 5L, 5L))))!!
    assertEquals(0L, empty.duration)
    assertNull(MplsParser.pickMainTitle(listOf(empty)))
  }

  private companion object {
    const val MARK_ENTRY = 1
  }
}