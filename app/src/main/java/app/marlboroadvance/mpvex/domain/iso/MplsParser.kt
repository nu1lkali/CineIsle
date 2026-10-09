package app.marlboroadvance.mpvex.domain.iso

/**
 * 蓝光播放列表（`BDMV/PLAYLIST/xxxxx.mpls`）的**纯逻辑**解析。
 *
 * ## 为什么必须有这个类
 *
 * 早先蓝光主影片的取法是「`STREAM` 里最大的那个 `.m2ts`」。这个判据在三种碟上会输：
 *
 * 1. **正片被切成多段**（`01100.m2ts` + `01101.m2ts`）—— 这是**常态**，
 *    只取最大那段只能播前半部；
 * 2. **3D / 多角度** —— 左右眼是两个**等长**的独立 m2ts，只取到一个视角；
 * 3. **高码率花絮**可能超过正片。
 *
 * mpls 是碟上作者留下的**权威事实**：它明确写出这个标题由哪些片段按什么顺序组成、
 * 每段取用哪一段时间。所以改成「问 mpls」，而不是猜。
 *
 * ## 二进制布局（全部大端序）
 *
 * 字段顺序与 libbluray 的 `src/libbluray/bdnav/mpls_parse.c` 逐字对齐 ——
 * 那是这个格式的事实标准实现。
 *
 * ```
 * 0x00  4  "MPLS"
 * 0x04  4  版本号（"0100" / "0200"，UHD 常见 "0300"）
 * 0x08  4  PlayList 段偏移
 * 0x0C  4  PlayListMark 段偏移（章节）
 * 0x10  4  扩展数据偏移（SubPath 等，用不到）
 * 0x14 20  保留
 *       ↓  AppInfoPlayList（本类跳过）
 * PlayList 段：
 *   4  本段长度
 *   2  保留
 *   2  number_of_PlayItems
 *   2  number_of_SubPaths
 *   ↓  逐个 PlayItem
 * PlayItem（每条自带长度，靠它跳过中间那一大堆 STN 轨道表）：
 *   2  本条长度 len
 *   5  clip_id（五位数字，如 "01100"）—— 对应 STREAM/01100.m2ts
 *   4  codec_id（"M2TS" / UHD 的 "FMTS"）
 *   2  保留 11 bit + is_multi_angle 1 bit + connection_condition 4 bit
 *   1  stc_id
 *   4  IN_time
 *   4  OUT_time
 *   …  uo_mask / STN 轨道表 / 多角度的其余 clip —— 本类用 len 整段跳过
 * PlayListMark 段：4 长度 + 2 mark_count + 每条 14 字节
 * ```
 *
 * ## 为什么敢跳过 STN 表
 *
 * 每个 PlayItem 的第 3 个字段就是它自己的**字节长度**（`len`），
 * 所以下一个 PlayItem 的位置是确定的 —— 不必理解 STN 表的内部布局，
 * 也不必随着格式版本变化去改这部分代码。真正需要的三个字段
 * （clip_id / IN_time / OUT_time）都在 STN 表**之前**，偏移固定。
 *
 * ## 可测性
 *
 * 纯字节处理，不碰网络与 Context ⇒ 纯 JVM 单测可完整覆盖（见 `MplsParserTest`）。
 */
internal object MplsParser {

  /** 文件头标志。 */
  private const val SIG = "MPLS"

  /** PlayListMark 里「章节点」的 mark_type。其余（link point 等）不算章节。 */
  private const val MARK_TYPE_ENTRY = 1

  /** 45kHz —— mpls 里的时间单位（BD 的 PTS 基准）。 */
  const val TICKS_PER_SECOND = 45_000L

  /** 一个 PlayItem 的结构里，我们真正要用的字段到第几字节结束（含长度字段本身）。 */
  /**
   * len(2) + clip_id(5) + codec_id(4) + flags(2) + stc_id(1) + in(4) + out(4) = 22。
   * 小于此值说明结构被截断，判为解析失败（libbluray 用的是 18，理由相同）。
   */
  private const val PLAY_ITEM_MIN_LEN = 22

  /** 允许的最大 PlayItem 数。真实 mpls 最多几十条；给个上限防畸形文件把内存吃光。 */
  private const val MAX_PLAY_ITEMS = 256

  /** 允许的最大 mark 数（章节）。真实碟最多几千条。 */
  private const val MAX_MARKS = 8192

  /** 一个片段。 */
  data class PlayItem(
    /** 五位数字的片段干，如 `"01100"` ⇒ `STREAM/01100.m2ts`。 */
    val clipId: String,
    /** 编码标识：`"M2TS"`（普通蓝光）或 `"FMTS"`（UHD）。 */
    val codecId: String,
    /** 该片段内实际取用的起点（45kHz 时间戳）。 */
    val inTime: Long,
    /** 该片段内实际取用的终点（45kHz 时间戳）。 */
    val outTime: Long,
    /**
     * 这是不是一个**多角度 / 3D** 片段（同一段时间有多个视角，碟上存成多个 clip）。
     *
     * 这里只如实记录「是/否」，不记录视角**个数** —— 真实个数写在 STN 之后，
     * 而本类为了少一处出错面刻意跳过了 STN 表（见类 KDoc），拿不到就不编。
     *
     * 播放时取 [clipId] 这一个 clip：它是 PlayItem 的**首个**视角（libbluray 的
     * `clip[0]`），也就是碟上标为默认的那一路 —— 3D 碟给的是单视角（不是完整 3D），
     * 但能正常播，比因为「凑不齐两个视角」而整条放弃要好。
     */
    val isMultiAngle: Boolean,
  ) {
    /** 本段时长（45kHz）。 */
    val duration: Long get() = (outTime - inTime).coerceAtLeast(0L)
  }

  /** 章节 / 导航点。 */
  data class Mark(
    /** 1 = 章节点，2 = link point。只统计 1。 */
    val type: Int,
    /** 归属第几个 PlayItem（从 0 起）。 */
    val playItemRef: Int,
    /** 相对整个播放列表的偏移（45kHz）。 */
    val time: Long,
  )

  /** 一个播放列表（≈「一个标题」）。 */
  data class Playlist(
    val playItems: List<PlayItem>,
    val marks: List<Mark>,
  ) {
    /** 总时长（45kHz）—— 各段之和，与 libbluray 的 `_pl_duration` 一致。 */
    val duration: Long get() = playItems.sumOf { it.duration }

    /** 章节数：只数 entry point。与 libbluray 的 `_pl_chapter_count` 同义。 */
    val chapterCount: Int get() = marks.count { it.type == MARK_TYPE_ENTRY }

    /** 去掉相邻重复后的片段序列 —— 相邻两段引用同一个 clip 时只保留一条。 */
    val clipSequence: List<String>
      get() = playItems.fold(mutableListOf<String>()) { acc, item ->
        if (acc.lastOrNull() != item.clipId) acc += item.clipId
        acc
      }
  }

  /**
   * 解析一个 mpls 文件的全部字节。
   *
   * @param data mpls 文件的完整字节（调用方负责只读到文件尾）。
   * @return 解析结果；magic 不对 / 越界 / 结构自相矛盾时返回 **null**，
   *   绝不做「猜一个默认值继续」—— 猜错会让 mpv 播到错误的角度或错误的时间段。
   */
  fun parse(data: ByteArray): Playlist? {
    if (data.size < 32) return null
    if (String(data, 0, 4, Charsets.US_ASCII) != SIG) return null

    val listPos = readBe32(data, 8)
    val markPos = readBe32(data, 12)
    if (listPos <= 0 || listPos >= data.size) return null

    val playItems = parsePlayList(data, listPos.toInt()) ?: return null
    val marks = if (markPos in 1 until data.size) parseMarks(data, markPos.toInt()) else emptyList()
    return Playlist(playItems, marks)
  }

  /** PlayList 段：长度 / 保留 / PlayItem 数 / SubPath 数，然后逐条 PlayItem。 */
  private fun parsePlayList(data: ByteArray, start: Int): List<PlayItem>? {
    if (start + 10 > data.size) return null
    val sectionLen = readBe32(data, start)
    val count = readBe16(data, start + 6)
    if (count > MAX_PLAY_ITEMS) return null
    // sectionLen 是本段（含这 4 字节自身）总长。它越界说明文件被截断。
    if (sectionLen > 0 && start + sectionLen > data.size) return null

    val out = ArrayList<PlayItem>(count)
    // PlayItem 起点：段头 10 字节之后。libbluray 在这里做 8 字节对齐检查，
    // 我们不做 —— 真实文件都对齐，而主动拒绝反而会误伤某些非标准镜像。
    var off = start + 10
    repeat(count) {
      val item = parsePlayItem(data, off) ?: return null
      out += item.first
      off = item.second
    }
    return out
  }

  /**
   * 单条 PlayItem。
   *
   * @return `(解析结果, 下一条的起点)`；结构不合法返回 null。
   */
  private fun parsePlayItem(data: ByteArray, start: Int): Pair<PlayItem, Int>? {
    if (start + PLAY_ITEM_MIN_LEN > data.size) return null
    val len = readBe16(data, start)
    // len 是本条（含 2 字节长度字段自身）的总字节数 —— 下一条就落在 start + len。
    if (len < PLAY_ITEM_MIN_LEN) return null
    if (start + len > data.size) return null

    val clipId = String(data, start + 2, 5, Charsets.US_ASCII)
    val codecId = String(data, start + 7, 4, Charsets.US_ASCII)
    // flags 的 16 位里：低 5 位是 connection_condition(4) + is_multi_angle(1)。
    val flags = readBe16(data, start + 11)
    val isMultiAngle = (flags and 0x0010) != 0
    // ⚠️ 多角度的其余 clip 记在 STN 之后，被 len 整段跳过 —— 所以拿不到视角**个数**，
    // 只知道「有多角度这回事」。不编数字（见 PlayItem.isMultiAngle 的 KDoc）。
    val inTime = readBe32(data, start + 14)
    val outTime = readBe32(data, start + 18)
    if (outTime < inTime) return null

    val item =
      PlayItem(
        clipId = clipId,
        codecId = codecId,
        inTime = inTime,
        outTime = outTime,
        isMultiAngle = isMultiAngle,
      )
    return item to (start + len)
  }

  /** PlayListMark 段：长度 / mark 数，然后每条 14 字节。 */
  private fun parseMarks(data: ByteArray, start: Int): List<Mark> {
    if (start + 6 > data.size) return emptyList()
    val count = readBe16(data, start + 4)
    if (count > MAX_MARKS) return emptyList()
    val out = ArrayList<Mark>(minOf(count, 256))
    var off = start + 6
    repeat(count) {
      if (off + 14 > data.size) return out
      val type = readBe16(data, off) and 0x00FF
      val playItemRef = readBe16(data, off + 2)
      val time = readBe32(data, off + 4)
      out += Mark(type = type, playItemRef = playItemRef, time = time)
      off += 14
    }
    return out
  }

  /**
   * 选主标题。
   *
   * 移植 libbluray 的 `_pl_guess_main_title`，但**砍掉了依赖 STN 轨道的两项比较**
   * （分辨率 / 音频格式）。砍的理由：那两项要解析 STN 表，而我们为了少一处出错面
   * **刻意跳过了 STN 表**（见类 KDoc）。留下的判据按优先级：
   *
   * 1. **章节数**（仅当两者都长于 30 分钟，且一方少于 2 章、相差超过 5 章时生效）
   * 2. **总时长** —— 兜底主力。正片几乎总是最长的那个标题，
   *    这条比「最大的 m2ts」可靠得多：它比的是**播放列表的意图**，
   *    而「最大的文件」只是文件系统的副产品。
   *
   * @param candidates 全部可解析的播放列表；解析失败的不该传进来。
   */
  fun pickMainTitle(candidates: List<Playlist>): Playlist? {
    if (candidates.isEmpty()) return null
    var best: Playlist? = null
    for (pl in candidates) {
      if (pl.playItems.isEmpty() || pl.duration <= 0L) continue
      if (best == null || isBetter(pl, best)) best = pl
    }
    return best
  }

  /** `a` 是否比 [best] 更像正片。规则见 [pickMainTitle]。 */
  private fun isBetter(a: Playlist, best: Playlist): Boolean {
    val thirtyMin = 30L * 60 * TICKS_PER_SECOND
    if (a.duration > thirtyMin && best.duration > thirtyMin) {
      val chapA = a.chapterCount
      val chapB = best.chapterCount
      if ((chapA < 2 || chapB < 2) && (chapA - chapB).let { it < -5 || it > 5 }) {
        return chapA > chapB
      }
    }
    // 时长相同时，章节多的更可能是正片（花絮一般没有分章）。
    if (a.duration != best.duration) return a.duration > best.duration
    return a.chapterCount > best.chapterCount
  }

  /** 大端读 4 字节无符号；越界返回 null。 */
  private fun readBe32(b: ByteArray, off: Int): Long {
    if (off < 0 || off + 4 > b.size) return -1L
    return ((b[off].toLong() and 0xFF) shl 24) or
      ((b[off + 1].toLong() and 0xFF) shl 16) or
      ((b[off + 2].toLong() and 0xFF) shl 8) or
      (b[off + 3].toLong() and 0xFF)
  }

  /** 大端读 2 字节无符号；越界返回 -1。 */
  private fun readBe16(b: ByteArray, off: Int): Int {
    if (off < 0 || off + 2 > b.size) return -1
    return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
  }
}