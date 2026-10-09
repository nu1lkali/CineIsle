package app.marlboroadvance.mpvex.domain.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteIsoParser] 的单测。
 *
 * 做法：**在内存里拼一个结构真实的 ISO9660 镜像**（含 PVD、目录记录、m2ts / VOB 文件），
 * 再喂给解析器。这样不依赖网络、不依赖真实镜像文件，
 * 就能验证「偏移算得对不对」「目录记录解析对不对」「会不会选错文件」。
 *
 * 布局约定（与真实 ISO9660 一致，见 [RemoteIsoParser]）：
 *  - 第 16 号扇区 = PVD（"CD001"）
 *  - PVD 偏移 156 = 根目录记录（extent 在 +2、size 在 +10）
 *  - 目录记录：首字节=本条长度，+2=extent，+10=size，+25=文件标志，+32=名字长度，+33=名字
 */
class RemoteIsoParserTest {

  // ── 测试脚手架：构造内存 ISO ─────────────────────────────────────────────

  private class MemReader(private val data: ByteArray) : RemoteIsoParser.RangeReader {
    var readCount = 0

    override fun readAt(offset: Long, length: Long): ByteArray {
      readCount++
      if (offset < 0 || offset >= data.size) return ByteArray(0)
      val start = offset.toInt()
      val end = minOf(start + length.toInt(), data.size)
      if (end <= start) return ByteArray(0)
      return data.copyOfRange(start, end)
    }

    override fun totalSize(): Long = data.size.toLong()
  }

  /**
   * 写一条目录记录。
   *
   * @param name 目录项名字（自动补 ";1" 版本号）
   * @param extent 起始扇区
   * @param size 字节长度
   * @param isDir 目录标志
   */
  private fun dirRecord(name: String, extent: Long, size: Long, isDir: Boolean): ByteArray {
    val full = "$name;1"
    val nameLen = full.length
    var len = 33 + nameLen
    if (len % 2 != 0) len += 1 // ISO9660 记录长度必须为偶数
    val rec = ByteArray(len)
    rec[0] = len.toByte()
    writeLe32(rec, 2, extent)
    writeLe32(rec, 10, size)
    rec[25] = if (isDir) 1 else 0
    rec[32] = nameLen.toByte()
    for (i in full.indices) rec[33 + i] = full[i].code.toByte()
    return rec
  }

  private fun writeLe32(b: ByteArray, off: Int, v: Long) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
    b[off + 2] = ((v shr 16) and 0xFF).toByte()
    b[off + 3] = ((v shr 24) and 0xFF).toByte()
  }

  /** 拼一个目录扇区：先 "." 再 ".."，再拼上真实条目（与真实 ISO 顺序一致）。 */
  private fun dirSector(entries: List<ByteArray>): ByteArray {
    val out = ArrayList<ByteArray>()
    out += dirRecord(".", 40, 2048, isDir = true)
    out += dirRecord("..", 40, 2048, isDir = true)
    out += entries
    val total = out.sumOf { it.size }
    val buf = ByteArray(((total + 2047) / 2048) * 2048)
    var off = 0
    out.forEach {
      it.copyInto(buf, off)
      off += it.size
    }
    return buf
  }

  /** PVD 扇区：只有解析器真正读的那几个字段需要正确。 */
  private fun pvdSector(rootExtent: Long, rootSize: Long): ByteArray {
    val s = ByteArray(2048)
    "CD001".forEachIndexed { i, c -> s[1 + i] = c.code.toByte() }
    writeLe32(s, 156 + 2, rootExtent)
    writeLe32(s, 156 + 10, rootSize)
    return s
  }

  /**
   * 组装一个蓝光 ISO（**没有 PLAYLIST**，用于测降级路径）。
   *
   * 扇区规划：
   *  - 16: PVD（根目录在扇区 20）
   *  - 20: 根目录（含 BDMV）
   *  - 30: BDMV 目录（含 STREAM）
   *  - 40: STREAM 目录（含若干 m2ts）
   *  - 100: m2ts 的数据起点
   */
  private fun bluRayIso(
    tracks: List<Pair<String, Long>>,
    rootDirName: String = "BDMV",
  ): ByteArray {
    val streamEntries = tracks.map { (name, size) -> dirRecord(name, 100, size, isDir = false) }

    // 目录记录要**两遍**：一次算长度（填进上级目录的 size 字段），一次真正写进扇区。
    // 顺序不能反 —— 上级目录里记的 size 必须等于本目录扇区的实际字节数。
    val rootSize = dirSector(listOf(dirRecord(rootDirName, 30, 2048, isDir = true))).size.toLong()
    val bdmvSize = dirSector(listOf(dirRecord("STREAM", 40, 2048, isDir = true))).size.toLong()
    val streamSize = dirSector(streamEntries).size.toLong()

    val buf = ByteArray(160 * 2048)
    pvdSector(20, rootSize).copyInto(buf, 16 * 2048)
    dirSector(listOf(dirRecord(rootDirName, 30, bdmvSize, isDir = true))).copyInto(buf, 20 * 2048)
    dirSector(listOf(dirRecord("STREAM", 40, streamSize, isDir = true))).copyInto(buf, 30 * 2048)
    dirSector(streamEntries).copyInto(buf, 40 * 2048)
    return buf
  }

  /**
   * 组装一个**带 PLAYLIST** 的蓝光 ISO —— 这才是真实蓝光原盘的结构。
   *
   * 扇区规划：
   *  - 16: PVD（根目录在 20）
   *  - 20: 根目录（含 BDMV）
   *  - 30: BDMV 目录（含 STREAM@40 与 PLAYLIST@50）
   *  - 40: STREAM 目录
   *  - 50: PLAYLIST 目录（含各 .mpls）
   *  - 100: 各 .mpls 的数据（每条占一个扇区）
   *  - 200: 各 m2ts 的数据起点
   *
   * @param tracks STREAM 里的 m2ts：`文件名` to `字节数`
   * @param playLists PLAYLIST 里的 mpls：`文件名` to `文件字节`。数据从扇区 100 起顺序排。
   */
  private fun bluRayIsoWithPlayLists(
    tracks: List<Pair<String, Long>>,
    playLists: List<Pair<String, ByteArray>>,
  ): ByteArray {
    val streamEntries = tracks.map { (name, size) -> dirRecord(name, 200, size, isDir = false) }
    val plEntries =
      playLists.mapIndexed { i, (name, bytes) ->
        dirRecord(name, 100L + i, bytes.size.toLong(), isDir = false)
      }

    val rootSize = dirSector(listOf(dirRecord("BDMV", 30, 2048, isDir = true))).size.toLong()
    val bdmvSize =
      dirSector(
          listOf(
            dirRecord("STREAM", 40, 2048, isDir = true),
            dirRecord("PLAYLIST", 50, 2048, isDir = true),
          )
        )
        .size
        .toLong()
    val streamSize = dirSector(streamEntries).size.toLong()
    val plDirSize = dirSector(plEntries).size.toLong()

    val buf = ByteArray(400 * 2048)
    pvdSector(20, rootSize).copyInto(buf, 16 * 2048)
    dirSector(listOf(dirRecord("BDMV", 30, bdmvSize, isDir = true))).copyInto(buf, 20 * 2048)
    dirSector(
        listOf(
          dirRecord("STREAM", 40, streamSize, isDir = true),
          dirRecord("PLAYLIST", 50, plDirSize, isDir = true),
        )
      )
      .copyInto(buf, 30 * 2048)
    dirSector(streamEntries).copyInto(buf, 40 * 2048)
    dirSector(plEntries).copyInto(buf, 50 * 2048)
    playLists.forEachIndexed { i, (_, bytes) -> bytes.copyInto(buf, (100 + i) * 2048) }
    return buf
  }

  // ── 蓝光降级路径：读不到 mpls 时才用「最大 m2ts」 ────────────────────────
  //
  // 这些用例的 ISO 里**故意没有 PLAYLIST 目录**，
  // 走的是 pickFromPlayLists 返回 null 之后的 fallbackLargestClip。

  @Test
  fun `没有 PLAYLIST 时降级取最大的 m2ts`() {
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L, "00002.m2ts" to 50_000L, "00003.m2ts" to 8_000L))
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    assertEquals(RemoteIsoParser.IsoKind.BLU_RAY, track!!.kind)
    // 降级路径下最大的那个是 00002（50000）
    assertEquals(100L * 2048, track.segments[0].offset)
    assertEquals(50_000L, track.length)
  }

  @Test
  fun `m2ts 大写后缀也能识别`() {
    val iso = bluRayIso(listOf("00001.M2TS" to 4_096L))
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    assertEquals(RemoteIsoParser.IsoKind.BLU_RAY, track!!.kind)
    assertEquals(4_096L, track.length)
  }

  @Test
  fun `非 m2ts 文件不参与降级选择`() {
    // 放一个更大的其他扩展名文件，应该被忽略
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L, "cover.jpg" to 999_999L))
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    assertEquals(1_000L, track!!.length)
  }

  // ── 非 ISO / 无法识别：必须返回 null 而不是瞎猜 ─────────────────────────

  @Test
  fun `不是 ISO 时返回 null`() {
    val junk = ByteArray(64 * 1024) // 全 0，没有 CD001
    assertNull(RemoteIsoParser.parseMainTrack(MemReader(junk)))
  }

  @Test
  fun `文件太短时返回 null`() {
    assertNull(RemoteIsoParser.parseMainTrack(MemReader(ByteArray(1024))))
  }

  @Test
  fun `根目录既没有 BDMV 也没有 VIDEO_TS 时返回 null`() {
    // UDF-only 镜像的典型形态：光有 PVD，根目录是 UDF 结构的产物。
    // 这里明确断言「不猜」—— 猜错会让 mpv 用错容器，表现为花屏/没声音。
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L), rootDirName = "UDF_README")
    assertNull(RemoteIsoParser.parseMainTrack(MemReader(iso)))
  }

  @Test
  fun `STREAM 目录为空时返回 null`() {
    val iso = bluRayIso(emptyList())
    assertNull(RemoteIsoParser.parseMainTrack(MemReader(iso)))
  }

  // ── DVD：主标题分段拼接 ──────────────────────────────────────────────────

  @Test
  fun `DVD 同一 title 的多个分段全部保留`() {
    val v1 = RemoteIsoParser.DirEntry("VTS_01_1.VOB", 100, 10_000, false)
    val v2 = RemoteIsoParser.DirEntry("VTS_01_2.VOB", 200, 20_000, false)
    val segs = RemoteIsoParser.pickMainTitle(listOf(v1, v2))
    assertNotNull(segs)
    // ⚠️ 两段**各自如实记下自己的 ISO 偏移** —— 不假设它们物理相邻。
    // （相邻时结果与老实现一致；不相邻时老实现会读错，这里才是对的。）
    assertEquals(2, segs!!.size)
    assertEquals(100L * 2048, segs[0].offset)
    assertEquals(10_000L, segs[0].length)
    assertEquals(200L * 2048, segs[1].offset)
    assertEquals(20_000L, segs[1].length)
  }

  @Test
  fun `DVD 取总码流最大的那个 title`() {
    val a = listOf(
      RemoteIsoParser.DirEntry("VTS_01_1.VOB", 100, 5_000, false),
      RemoteIsoParser.DirEntry("VTS_02_1.VOB", 300, 90_000, false),
    )
    val segs = RemoteIsoParser.pickMainTitle(a)
    assertEquals(1, segs!!.size)
    assertEquals(300L * 2048, segs[0].offset)
    assertEquals(90_000L, segs[0].length)
  }

  @Test
  fun `DVD 分段按序号排序而不是按名字`() {
    // VTS_01_10 必须排在 VTS_01_2 之后，纯字符串排序会错
    val v2 = RemoteIsoParser.DirEntry("VTS_01_2.VOB", 200, 1_000, false)
    val v10 = RemoteIsoParser.DirEntry("VTS_01_10.VOB", 210, 2_000, false)
    val segs = RemoteIsoParser.pickMainTitle(listOf(v10, v2))
    // 第 2 段必须在第 10 段之前
    assertEquals(200L * 2048, segs!![0].offset)
    assertEquals(210L * 2048, segs[1].offset)
  }

  @Test
  fun `DVD 空列表返回 null`() {
    assertNull(RemoteIsoParser.pickMainTitle(emptyList()))
  }

  // ── 读放大控制：解析不应把整个镜像拉下来 ────────────────────────────────

  @Test
  fun `解析只读少量扇区而不是整个镜像`() {
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L))
    val reader = MemReader(iso)
    RemoteIsoParser.parseMainTrack(reader)
    // PVD + 根目录 + BDMV + STREAM 共 4 次左右；给个宽松上限，
    // 关键是「远小于镜像大小」—— 这正是 Range 方案的意义。
    assertTrue("读了 ${reader.readCount} 次，应远小于扇区总数", reader.readCount <= 8)
  }

  // ── DVD 整盘识别 ────────────────────────────────────────────────────────

  @Test
  fun `识别 DVD 镜像`() {
    val rootSize = dirSector(listOf(dirRecord("VIDEO_TS", 30, 2048, isDir = true))).size.toLong()
    val vtsSize =
      dirSector(
        listOf(
          dirRecord("VTS_01_1.VOB", 100, 40_000, isDir = false),
          dirRecord("VTS_02_1.VOB", 200, 90_000, isDir = false),
        ),
      ).size.toLong()

    val buf = ByteArray(160 * 2048)
    pvdSector(20, rootSize).copyInto(buf, 16 * 2048)
    dirSector(listOf(dirRecord("VIDEO_TS", 30, vtsSize, isDir = true))).copyInto(buf, 20 * 2048)
    dirSector(
      listOf(
        dirRecord("VTS_01_1.VOB", 100, 40_000, isDir = false),
        dirRecord("VTS_02_1.VOB", 200, 90_000, isDir = false),
      ),
    ).copyInto(buf, 30 * 2048)

    val track = RemoteIsoParser.parseMainTrack(MemReader(buf))
    assertNotNull(track)
    assertEquals(RemoteIsoParser.IsoKind.DVD, track!!.kind)
    // 更大的 VTS_02
    assertEquals(200L * 2048, track.segments[0].offset)
    assertEquals(90_000L, track.length)
  }

  // ── looksLikeIso：只验 PVD 的快速预筛 ──────────────────────────────────

  @Test
  fun `looksLikeIso 对蓝光镜像返回 true`() {
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L))
    assertTrue(RemoteIsoParser.looksLikeIso(MemReader(iso)))
  }

  @Test
  fun `looksLikeIso 对 DVD 镜像返回 true`() {
    val rootSize = dirSector(listOf(dirRecord("VIDEO_TS", 30, 2048, isDir = true))).size.toLong()
    val buf = ByteArray(160 * 2048)
    pvdSector(20, rootSize).copyInto(buf, 16 * 2048)
    dirSector(listOf(dirRecord("VIDEO_TS", 30, 2048, isDir = true))).copyInto(buf, 20 * 2048)
    assertTrue(RemoteIsoParser.looksLikeIso(MemReader(buf)))
  }

  @Test
  fun `looksLikeIso 对非 ISO 数据返回 false`() {
    // 一段随机内容：没有 "CD001" 标志
    assertEquals(false, RemoteIsoParser.looksLikeIso(MemReader(ByteArray(64 * 2048) { 0x41 })))
  }

  @Test
  fun `looksLikeIso 对太短的文件返回 false 而不是抛异常`() {
    // 比一个扇区还短 —— 真实场景是 strm 指向了个 404 页面
    assertEquals(false, RemoteIsoParser.looksLikeIso(MemReader(ByteArray(100))))
    assertEquals(false, RemoteIsoParser.looksLikeIso(MemReader(ByteArray(0))))
  }

  @Test
  fun `looksLikeIso 只读一个扇区`() {
    val iso = bluRayIso(listOf("00001.m2ts" to 1_000L))
    val reader = MemReader(iso)
    RemoteIsoParser.looksLikeIso(reader)
    // 这是它的全部意义：不合格时立刻退出，不拉目录树。
    assertEquals(1, reader.readCount)
  }

  // ── 走 mpls 的正路：真蓝光结构（BDMV/PLAYLIST + BDMV/STREAM） ────────────
  //
  // 下面这组用例是本轮改动的意义所在：它们正面覆盖「取最大 m2ts」会错的三个场景。
  // mpls 字节的构造规则见 MplsParserTest 的 KDoc。

  private fun be32(v: Long) = byteArrayOf(
    ((v shr 24) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte(),
  )

  private fun be16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

  /** 一条 PlayItem（与 MplsParserTest 同一构造规则）。 */
  private fun playItem(clipId: String, inTime: Long, outTime: Long): ByteArray {
    val len = 22 + 64
    val out = ByteArray(len)
    be16(len).copyInto(out, 0)
    clipId.toByteArray(Charsets.US_ASCII).copyInto(out, 2)
    "M2TS".toByteArray(Charsets.US_ASCII).copyInto(out, 7)
    be16(0x0001).copyInto(out, 11)
    out[13] = 0
    be32(inTime).copyInto(out, 14)
    be32(outTime).copyInto(out, 18)
    return out
  }

  /** 组装 mpls（结构与 MplsParserTest 一致）。 */
  private fun mpls(items: List<ByteArray>): ByteArray {
    val listPos = 40
    // PlayList 段头：保留(2) + PlayItem 数(2) + SubPath 数(2) = 6 字节。
    // ⚠️ 不能图省事写 ByteArray(6) —— 那样会把 PlayItem 数填成 0，
    // 解析出「零条 PlayItem」的播放列表，于是主标题选不出来、静默走降级路径。
    val body = be16(0) + be16(items.size) + be16(0) + items.fold(ByteArray(0)) { acc, i -> acc + i }
    val section = be32((4 + body.size).toLong()) + body
    val header = ByteArray(listPos)
    "MPLS".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
    "0200".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
    be32(listPos.toLong()).copyInto(header, 8)
    be32(0L).copyInto(header, 12) // 无 mark 段
    return header + section
  }

  // 普通 val 而非 const val：const 只允许顶层 / object / companion。
  // ⚠️ mpls 时间单位是 **45kHz 时间戳**，45_000 是**一秒**。这里定义成「一分钟」，
  // 写 `120L * MIN` 才真的是 120 分钟（否则主标题的 30 分钟判据形同虚设）。
  private val MIN = 45_000L * 60

  /**
   * ⚠️ 本轮改动的核心用例：**正片切成两段**。
   *
   * 老的「取最大 m2ts」实现在这里只能拿到 01101（它更大），
   * 于是**只能播前半部**。现在两段都在，且按 mpls 的顺序。
   */
  @Test
  fun `正片被切成两段时两段都保留并按 mpls 顺序`() {
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 5_000L, "01101.m2ts" to 9_000L),
        playLists =
          listOf(
            "00800.mpls" to mpls(listOf(playItem("01100", 0L, 60L * MIN), playItem("01101", 0L, 20L * MIN)))
          ),
      )
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    assertEquals(RemoteIsoParser.IsoKind.BLU_RAY, track!!.kind)
    // 两段都在，且按 mpls 里的先后（01100 → 01101）
    assertEquals(2, track.segments.size)
    assertEquals(5_000L, track.segments[0].length)
    assertEquals(9_000L, track.segments[1].length)
    assertEquals(14_000L, track.length)
  }

  /** ⚠️ 「高码率花絮比正片大」—— 老实现在这种碟上会播成花絮。 */
  @Test
  fun `花絮比正片大时仍按 mpls 选出正片`() {
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 2_000L, "09999.m2ts" to 99_000L),
        playLists =
          listOf(
            "00800.mpls" to mpls(listOf(playItem("01100", 0L, 120L * MIN))),
            "09999.mpls" to mpls(listOf(playItem("09999", 0L, 10L * MIN))),
          ),
      )
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    // 选的是 01100（时长 120 分钟），不是更大的 09999
    assertEquals(2_000L, track!!.length)
  }

  /** 多个播放列表时按时长选主标题。 */
  @Test
  fun `多个 mpls 里选时长最长的那个`() {
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 3_000L, "02200.m2ts" to 4_000L),
        playLists =
          listOf(
            "00801.mpls" to mpls(listOf(playItem("01100", 0L, 20L * MIN))),
            "00800.mpls" to mpls(listOf(playItem("02200", 0L, 110L * MIN))),
          ),
      )
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    // 110 分钟那个胜出
    assertEquals(4_000L, track!!.length)
  }

  /** mpls 存在但引用的 clip 在 STREAM 里找不到 ⇒ 整体作废并降级，绝不半截播放。 */
  @Test
  fun `mpls 引用的 clip 缺失时降级而不是半截播放`() {
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 5_000L, "01101.m2ts" to 7_000L),
        playLists = listOf("00800.mpls" to mpls(listOf(playItem("01100", 0L, 60L * MIN), playItem("09999", 0L, 20L * MIN)))),
      )
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    // 降级：只有一段，且是最大的那个 m2ts
    assertEquals(1, track!!.segments.size)
    assertEquals(7_000L, track.length)
  }

  /** PLAYLIST 在但 mpls 全是垃圾 ⇒ 降级到最大 m2ts。 */
  @Test
  fun `mpls 内容损坏时降级到最大 m2ts`() {
    val junk = ByteArray(2048) { 0x7F } // 没有 "MPLS" 头
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 1_000L, "01101.m2ts" to 6_000L),
        playLists = listOf("00800.mpls" to junk),
      )
    val track = RemoteIsoParser.parseMainTrack(MemReader(iso))
    assertNotNull(track)
    assertEquals(6_000L, track!!.length)
  }

  /** 正路仍要控制读放大：不能把整个镜像拉下来。 */
  @Test
  fun `走 mpls 时读的次数仍然很少`() {
    val iso =
      bluRayIsoWithPlayLists(
        tracks = listOf("01100.m2ts" to 5_000L, "01101.m2ts" to 9_000L),
        playLists = listOf("00800.mpls" to mpls(listOf(playItem("01100", 0L, 60L * MIN), playItem("01101", 0L, 20L * MIN)))),
      )
    val reader = MemReader(iso)
    RemoteIsoParser.parseMainTrack(reader)
    // PVD + 根目录 + BDMV + STREAM + PLAYLIST + 1 个 mpls + (缺片段时重扫 STREAM)
    // 给个宽松上限，关键是远小于扇区总数。
    assertTrue("读了 ${reader.readCount} 次", reader.readCount <= 12)
  }
}
