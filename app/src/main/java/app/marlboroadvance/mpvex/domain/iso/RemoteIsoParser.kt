package app.marlboroadvance.mpvex.domain.iso

/**
 * 远程 ISO 蓝光 / DVD 原盘的**纯逻辑**解析。
 *
 * ## 这个类解决什么
 *
 * 本地 ISO 早就支持了（见 `ui/player/PlayerUtils.kt` 的 `isoPlayableUri`）：
 * libbluray 按**路径**打开镜像，扫一下 ISO9660 根目录就知道是 BDMV 还是 VIDEO_TS。
 *
 * 但远程 ISO（strm 指向 `http://cdn/xxx.iso`）用不了那套 —— libbluray 只认路径，
 * 而 CDN 直链是**顺序流**，让它随机 seek 会退化成从头拉几十 GB。
 *
 * 所以换一条路：**自己在客户端把 ISO 拆开，只把「主影片」那一个文件喂给 mpv**。
 * 蓝光原盘里的主影片是一个 `BDMV/STREAM/xxxxx.m2ts`，它本身就是标准 MPEG-TS，
 * mpv 用自己的 ffmpeg 直接解，**不需要 libbluray、不需要服务端转码**（与 VidHub 同路子）。
 *
 * ## 为什么必须拆而不整盘丢给 mpv
 *
 * 整盘丢给 mpv 只有两条路，都不通：
 *  · `bluray://` ⇒ libbluray 只开路径，开不了 http；
 *  · 当普通文件播 ⇒ mpv 会把整张 ISO 当成一个字节流，找不到 TS 的 0x47 同步，
 *    表现为「一直缓冲 / 黑屏」。
 *
 * ## 边界（重要，别越界用）
 *
 * - 只解析 **ISO9660**（俗称「带 CD001 的镜像」）。UDF-only 的镜像是另一套结构，
 *   本类会返回 null 而不是乱猜 —— 宁可播不了，也不要播错。
 * - 蓝光靠 `BDMV/PLAYLIST` 里的 **mpls 播放列表**选正片（见 [MplsParser]），
 *   这比「取最大的 m2ts」可靠得多（后者在正片分段 / 3D / 高码率花絮上都会选错）。
 *   mpls 读不通时有降级路径（取最大 m2ts），宁可播得糙也不播不了。
 * - DVD 的正片常被拆成多段 `.VOB`，按序号取全部分段（见 [pickMainTitle]）。
 *   不做菜单、不渲染章节。
 * - 多 PGC 的 DVD 不做多标题切换 —— 只能播一个主标题。
 *
 * ## 可测性
 *
 * 所有字节都从 [RangeReader] 拿，不碰网络与 Context ⇒ 纯 JVM 单测可完整覆盖。
 */
internal object RemoteIsoParser {

  /** ISO 逻辑块大小，固定 2048 字节（ISO9660 规范如此，不能改）。 */
  private const val SECTOR = 2048

  /** 卷描述符固定在第 16 号扇区（ISO9660 规定 16~31 是系统区）。 */
  private const val PVD_SECTOR = 16

  /** 蓝光 / DVD 各自的根目录名 —— 二者靠它区分。 */
  private const val DIR_BDMV = "BDMV"
  private const val DIR_VIDEO_TS = "VIDEO_TS"

  /** BDMV 下的播放列表目录 —— 蓝光选正片的权威依据（见 [MplsParser]）。 */
  private const val DIR_PLAYLIST = "PLAYLIST"

  /** BDMV 下的码流目录。 */
  private const val DIR_STREAM = "STREAM"

  /**
   * 单个 mpls 最多读多少字节。
   *
   * 真实 mpls 是几 KB 到几十 KB（章节多时更大），1MB 足够覆盖任何真碟。
   * 设上限是因为 `PLAYLIST` 目录里可能有几十条，每条都无上限读会放大成几十 MB。
   */
  private const val MAX_MPLS_BYTES = 1 shl 20

  /**
   * 最多解析多少个 mpls 参与「选主标题」。
   *
   * 真实碟的 `PLAYLIST` 通常只有几条到几十条，但有的盘（含 4K 原盘）会塞进上百条。
   * 取前 [MAX_PLAYLISTS] 个：主标题（`00800.mpls` 那一族）在真碟里编号靠前，
   * 靠前的就够用；更重要的是给读放大设一个硬闸。
   */
  private const val MAX_PLAYLISTS = 32

  /** 探测时最多读多少字节的目录项 —— 目录项是定长小记录，1MB 足够覆盖任何真实目录。 */
  private const val MAX_DIR_BYTES = 1 shl 20

  /** ISO 镜像类型。 */
  enum class IsoKind {
    /** 蓝光原盘（根目录有 BDMV）。 */
    BLU_RAY,

    /** DVD（根目录有 VIDEO_TS）。 */
    DVD,
  }

  /**
   * 主影片：一段（或几段）可直接交给 mpv 播的字节区间。
   *
   * [segments] 按**播放顺序**排列，每段是 ISO 内的一段连续字节。
   * 正片被切成多段是常态（`01100.m2ts` + `01101.m2ts`），所以这里是列表而非单段。
   * 代理层用 [SegmentMap] 把 mpv 的虚拟偏移换算回 ISO 内的物理偏移。
   *
   * ⚠️ 刻意**不假设各段在 ISO 里物理相邻** —— 见 [SegmentMap] 的类 KDoc。
   */
  data class MainTrack(
    val segments: List<Segment>,
    val kind: IsoKind,
    /** 容器 MIME，仅用于本地代理返回正确的 `Content-Type`。 */
    val mimeType: String = "video/mp2t",
  ) {
    /** 虚拟文件总长度 = 各段之和。代理用它当 `Content-Range` 的总长。 */
    val length: Long get() = segments.sumOf { it.length }
  }

  /**
   * 只读随机访问接口。生产实现用 HTTP Range，测试里用内存数组。
   *
   * [length] 传 -1 表示「不知道总长」（HEAD 拿不到时的退路）。
   */
  interface RangeReader {
    /** 从 [offset] 起读最多 [length] 个字节；到文件尾可以少返回。 */
    fun readAt(offset: Long, length: Long): ByteArray

    /** 文件总长度；未知返回 -1。 */
    fun totalSize(): Long
  }

  /**
   * 把 [findChild] / 各解析分支里的「读整个目录」统一成这个上限。
   *
   * 目录项是定长小记录，1MB 足够覆盖任何真实目录；不设上限的话，
   * 一个畸形的大 extent 会让客户端去拉几个 GB。
   */
  private const val DIR_READ_CAP = MAX_DIR_BYTES.toLong()

  /**
   * 只验「这是不是一张可处理的 ISO 镜像」，不解析目录。
   *
   * 用途：列表里点条目时的**快速预筛**。先只读第 16 号扇区（2KB）看一眼标志，
   * 不是 ISO 就立刻返回，不必把整棵目录树都拉下来。
   * 真正的解析在 [parseMainTrack]。
   *
   * @return 是 ISO9660 镜像返回 true。
   */
  fun looksLikeIso(reader: RangeReader): Boolean =
    runCatching {
      val pvd = reader.readAt(PVD_SECTOR.toLong() * SECTOR, SECTOR.toLong())
      pvd.size >= SECTOR && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001"
    }.getOrDefault(false)

  /**
   * 解析远程 ISO，取出主影片所在区间。
   *
   * @return 主影片区间；不是可识别的 ISO / 探测失败返回 null（调用方回退到普通播放）。
   */
  fun parseMainTrack(reader: RangeReader): MainTrack? {
    val pvd = reader.readAt(PVD_SECTOR.toLong() * SECTOR, SECTOR.toLong())
    if (pvd.size < SECTOR) return null

    // ISO9660 的识别标志：扇区第 1 字节起 5 个字符是 "CD001"。
    if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null

    val rootExtent = readLe32(pvd, ROOT_EXTENT_OFFSET) ?: return null
    val rootSize = readLe32(pvd, ROOT_SIZE_OFFSET) ?: return null
    if (rootExtent <= 0L || rootSize <= 0L) return null

    val dir = reader.readAt(rootExtent * SECTOR, minOf(rootSize, MAX_DIR_BYTES.toLong()))
    if (dir.isEmpty()) return null

    val entries = parseDirectory(dir)
    val kind =
      when {
        entries.any { it.name == DIR_BDMV } -> IsoKind.BLU_RAY
        entries.any { it.name == DIR_VIDEO_TS } -> IsoKind.DVD
        // 根目录里两个标志目录都没有：不是本类能处理的结构（典型是 UDF-only），
        // **不要瞎猜类型** —— 猜错会让 mpv 用错容器解析，表现是「能播但花屏/没声音」。
        else -> return null
      }

    return when (kind) {
      IsoKind.BLU_RAY -> parseBluRay(reader, rootExtent)
      IsoKind.DVD -> parseDvd(reader, rootExtent)
    }
  }

  // 不是 private：public 的 pickMainTitle 要拿它当参数类型，private 会被 Kotlin 拒绝。
  // 单测也直接构造它来验证分段挑选。
  /** 目录项。 */
  internal data class DirEntry(
    val name: String,
    val extent: Long,
    val size: Long,
    val isDir: Boolean,
  )

  /**
   * 解析 ISO9660 目录记录序列。
   *
   * 记录是变长的：首字节是本条长度（含自身），0 表示本扇区到此结束。
   * 名字在偏移 33 处、长度在第 32 字节。
   */
  private fun parseDirectory(dir: ByteArray): List<DirEntry> {
    val out = ArrayList<DirEntry>()
    var off = 0
    while (off + 33 <= dir.size) {
      val recordLen = dir[off].toInt() and 0xFF
      if (recordLen == 0) break
      if (off + recordLen > dir.size) break
      val nameLen = dir[off + 32].toInt() and 0xFF
      if (nameLen in 1..255 && off + 33 + nameLen <= dir.size) {
        // 「;1」是 ISO9660 的版本后缀，去掉才是真实名字。
        val raw = String(dir, off + 33, nameLen, Charsets.US_ASCII)
        val name = raw.substringBefore(';').uppercase()
        // 首条是 "."、第二条是 ".."，不是真条目。
        if (name != "." && name != ".." && !name.isEmpty()) {
          out += DirEntry(
            name = name,
            extent = readLe32(dir, off + 2) ?: 0L,
            size = readLe32(dir, off + 10) ?: 0L,
            isDir = (dir[off + 25].toInt() and 0xFF) != 0,
          )
        }
      }
      off += recordLen
    }
    return out
  }

  /**
   * 蓝光：读 `BDMV/PLAYLIST` 下的播放列表，按 [MplsParser.pickMainTitle] 选出正片，
   * 再按它列的片段顺序拼出 [MainTrack.segments]。
   *
   * ## 为什么必须走 mpls 而不是「取最大的 m2ts」
   *
   * 早先的实现是「`STREAM` 里最大的 `.m2ts` 就是正片」。这个判据在三种碟上会输：
   *
   * 1. **正片被切成多段**（`01100.m2ts` + `01101.m2ts` + …）—— 这是**常态**，
   *    只取最大那段**只能播前半部**；
   * 2. **3D / 多角度** —— 左右眼是**两个等长**的独立 m2ts，取到一个视角；
   * 3. **高码率花絮**可能超过正片。
   *
   * mpls 是碟上作者留下的权威事实：它明确写出这个标题由哪些片段按什么顺序组成。
   * 所以改成「问 mpls」，把一个**能查证的事实**换回来，而不是继续赌。
   *
   * ## 降级路径
   *
   * 读不到 PLAYLIST 目录、或所有 mpls 都解析失败时，回退到「取最大的 m2ts」。
   * 回退不是妥协，是**防呆**：少数非标准镜像（尤其 4K 原盘的 FMTS），
   * STREAM 里的文件名与 mpls 记的 clip_id 对不上时，宁可给一个不完美但能播的结果，
   * 也不要因为「解析不出来」就完全播不了。回退路径在 KDoc 与单测里都标明了。
   */
  private fun parseBluRay(reader: RangeReader, rootExtent: Long): MainTrack? {
    val bdmv = findChild(reader, rootExtent, DIR_BDMV) ?: return null
    val stream = findChild(reader, bdmv.extent, DIR_STREAM) ?: return null

    val mplsTrack = pickFromPlayLists(reader, bdmv, stream)
    if (mplsTrack != null) return mplsTrack

    return fallbackLargestClip(reader, stream)
  }

  /**
   * 正路：走 PLAYLIST 下的 mpls 选标题。
   *
   * @return 选不出正片返回 null（由调用方走降级路径）。
   */
  private fun pickFromPlayLists(reader: RangeReader, bdmv: DirEntry, stream: DirEntry): MainTrack? {
    val playListDir = findChild(reader, bdmv.extent, DIR_PLAYLIST) ?: return null
    val entries =
      parseDirectory(
        reader.readAt(playListDir.extent * SECTOR, minOf(playListDir.size, MAX_DIR_BYTES.toLong()))
      )
    val mplsFiles =
      entries.filter { !it.isDir && it.name.endsWith(".MPLS") && it.size > 0 }.sortedBy { it.name }
    if (mplsFiles.isEmpty()) return null

    // clip_id（"01100"）→ STREAM 里的条目。先建一次索引，避免每个片段都重扫目录。
    val streamEntries =
      parseDirectory(
        reader.readAt(stream.extent * SECTOR, minOf(stream.size, MAX_DIR_BYTES.toLong()))
      )
    val byClipId = HashMap<String, DirEntry>()
    streamEntries.forEach { e ->
      if (!e.isDir && e.size > 0) byClipId.putIfAbsent(stem(e.name), e)
    }
    if (byClipId.isEmpty()) return null

    // 逐个解析 mpls，取主标题。
    // ⚠️ 读放大会被这里的循环放大（PLAYLIST 目录可能有几十条）。
    // 所以给两个闸：单文件读上限 + 文件数上限。真碟的 PLAYLIST 通常只有几条到几十条，
    // 每条 mpls 也就几 KB 到几十 KB，代价可接受。
    val playlists = ArrayList<MplsParser.Playlist>(minOf(mplsFiles.size, 16))
    for (entry in mplsFiles) {
      if (playlists.size >= MAX_PLAYLISTS) break
      val bytes =
        runCatching {
          reader.readAt(entry.extent * SECTOR, minOf(entry.size, MAX_MPLS_BYTES.toLong()))
        }.getOrNull() ?: continue
      if (bytes.isEmpty()) continue
      val pl = runCatching { MplsParser.parse(bytes) }.getOrNull() ?: continue
      playlists += pl
    }

    val best = MplsParser.pickMainTitle(playlists) ?: return null

    // 按 mpls 给的顺序取片段。缺片段就整体作废（走降级）——
    // 宁可给一个能播的不完美结果，也不要播到一半断掉。
    val segments = ArrayList<Segment>(best.clipSequence.size)
    for (clipId in best.clipSequence) {
      val e = byClipId[clipId] ?: return null
      segments += Segment(offset = e.extent * SECTOR, length = e.size)
    }
    if (segments.isEmpty()) return null
    return MainTrack(segments, IsoKind.BLU_RAY)
  }

  /**
   * 降级路径：`STREAM` 里取**最大**的那个 `.m2ts`。
   *
   * ⚠️ 这条路径**只在 mpls 走不通时**才用，它有已知的三个缺陷
   * （正片分段、3D 双视角、花絮码率更高），见 [parseBluRay] 的 KDoc。
   * 保留它是为了「宁可播得糙，不要播不了」。
   */
  private fun fallbackLargestClip(reader: RangeReader, stream: DirEntry): MainTrack? {
    val entries =
      parseDirectory(
        reader.readAt(stream.extent * SECTOR, minOf(stream.size, MAX_DIR_BYTES.toLong()))
      )
    val m2ts = entries.filter { !it.isDir && it.name.endsWith(".M2TS") && it.size > 0 }
    val main = m2ts.maxByOrNull { it.size } ?: return null
    return MainTrack(listOf(Segment(main.extent * SECTOR, main.size)), IsoKind.BLU_RAY)
  }

  /** `01100.M2TS` → `01100`（mpls 记的 clip_id 就是这个干）。 */
  private fun stem(name: String): String = name.substringBeforeLast('.')

  /** DVD：取 `VIDEO_TS` 下码流最大的那个 `.VOB`（见 [pickMainTitle] 的取舍说明）。 */
  private fun parseDvd(reader: RangeReader, rootExtent: Long): MainTrack? {
    val videoTs = findChild(reader, rootExtent, DIR_VIDEO_TS) ?: return null
    val entries =
      parseDirectory(
        reader.readAt(videoTs.extent * SECTOR, minOf(videoTs.size, MAX_DIR_BYTES.toLong()))
      )
    val segments =
      pickMainTitle(entries.filter { !it.isDir && it.name.endsWith(".VOB") && it.size > 0 })
        ?: return null
    return MainTrack(segments, IsoKind.DVD)
  }

  /**
   * DVD 主标题：取**总码流最大的那个 title**，并保留它的**全部分段**。
   *
   * DVD 的正片常被拆成 `VTS_01_1.VOB`（第 1 段）…`VTS_01_N.VOB`（后续段），
   * 只取其中一段会**只播到片头**。所以这里返回该 title 的所有分段（按序号排序）。
   *
   * ⚠️ 早先的实现在这里**假设分段在 ISO 里物理相邻**，把首段起点 + 总长度当成一段。
   * 蓝光那边已经不这么假设了（见 [SegmentMap]），这里也一并改成如实列出各段 ——
   * 由代理层用 [SegmentMap] 映射，行为在「相邻」时与原来完全一致，不相邻时反而正确。
   *
   * @return 选中的各分段（按播放顺序）；没有可用分段时返回 null。
   */
  fun pickMainTitle(vobEntries: List<DirEntry>): List<Segment>? {
    if (vobEntries.isEmpty()) return null
    // 按「文件名去掉最后一段」归组：`VTS_01_1` / `VTS_01_2` / … 属于同一 title。
    val groups = vobEntries.groupBy { e -> e.name.substringBeforeLast('_') }
    val bestGroup = groups.maxByOrNull { (_, segs) -> segs.sumOf { it.size } } ?: return null
    val sorted = bestGroup.value.sortedBy { segmentIndex(it.name) }
    return sorted.map { Segment(offset = it.extent * SECTOR, length = it.size) }
  }

  /** 从 `VTS_01_3.VOB` 里取出末段的 `3`（取不到时给 0，保证排序稳定）。 */
  private fun segmentIndex(name: String): Int =
    name.substringAfterLast('_').substringBefore('.').toIntOrNull() ?: 0

  /** 在某个目录里找一个子目录（名字大写比较）。 */
  private fun findChild(reader: RangeReader, parentExtent: Long, childName: String): DirEntry? {
    val dir = reader.readAt(parentExtent * SECTOR, DIR_READ_CAP)
    return parseDirectory(dir).firstOrNull { it.isDir && it.name == childName }
  }

  // PVD 里的根目录记录：起始于偏移 156，其中 extent 在 +2、size 在 +10。
  private const val ROOT_EXTENT_OFFSET = 156 + 2
  private const val ROOT_SIZE_OFFSET = 156 + 10

  /** 小端读 4 字节无符号；越界或为负返回 null（宁可放弃也不猜）。 */
  private fun readLe32(b: ByteArray, off: Int): Long? {
    if (off < 0 || off + 4 > b.size) return null
    val v =
      (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)
    return if (v < 0) null else v
  }
}
