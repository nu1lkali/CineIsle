package app.marlboroadvance.mpvex.domain.iso

/**
 * ISO 内的一段连续字节区间（对应一个文件，或文件的一个片段）。
 *
 * [offset] / [length] 都是**在 ISO 文件内部**的绝对坐标。
 */
internal data class Segment(val offset: Long, val length: Long)

/** 虚拟文件里的一段区间，换算成 ISO 内的物理区间之后的结果。 */
internal data class PhysicalRange(val isoOffset: Long, val length: Long)

/**
 * 主影片的「虚拟文件 → ISO 内物理字节」映射，支持**跨多个片段**。
 *
 * ## 为什么需要它（这是本文件存在的全部理由）
 *
 * 蓝光正片被切成多段是**常态**（`01100.m2ts` + `01101.m2ts` + …），
 * mpls 播放列表会把它们按正确顺序列出来。
 *
 * 以前「取最大的那个 m2ts」的做法在这种碟上**只能播前半部**。
 * 后来 DVD 侧改成了「同一 title 的分段拼成一段连续区间」——
 * 但那等于**假设这些分段在 ISO 里物理相邻**。这个假设在 DVD 上通常成立，
 * 在蓝光上**没有任何规范保证**：BDMV 的片段是作者按播放顺序写入的，
 * 但文件系统的分配顺序不必等于播放顺序。
 *
 * 所以这里不假设相邻：如实记下每一段，映射时按虚拟偏移逐段定位。
 * 段数通常只有 1~4 个，二分都不必要。
 */
internal class SegmentMap(segments: List<Segment>) {

  private val offsets: LongArray
  private val lengths: LongArray

  /** [starts][i] = 第 i 段在**虚拟文件**里的起始偏移。 */
  private val starts: LongArray

  /** 虚拟文件总长度 = 所有段长度之和。 */
  val totalLength: Long

  init {
    require(segments.isNotEmpty()) { "SegmentMap 不能没有片段" }
    offsets = LongArray(segments.size) { segments[it].offset }
    lengths = LongArray(segments.size) { segments[it].length }
    starts = LongArray(segments.size)
    var acc = 0L
    for (i in segments.indices) {
      // 长度为 0 的片段会让 starts 出现重复值，二分定位随之失效。直接拒掉。
      require(segments[i].length > 0L) { "第 $i 段长度为 0（offset=${segments[i].offset}）" }
      starts[i] = acc
      acc += segments[i].length
    }
    totalLength = acc
  }

  /** 段数。诊断信息用。 */
  val segmentCount: Int get() = lengths.size

  /**
   * 把虚拟区间 `[vStart, vEnd]`（闭区间）换算成 ISO 内的一串物理区间。
   *
   * 单段时返回恰好一条；跨段时按虚拟顺序返回多条，由调用方**按顺序拼接**。
   *
   * @return 越界或空区间返回空列表（调用方应当回 416，而不是猜）。
   */
  fun physicalRanges(vStart: Long, vEnd: Long): List<PhysicalRange> {
    if (vStart < 0 || vEnd < vStart || vStart >= totalLength) return emptyList()
    val last = minOf(vEnd, totalLength - 1)
    val out = ArrayList<PhysicalRange>()
    var v = vStart
    while (v <= last) {
      val idx = indexOfSegment(v)
      if (idx < 0) return emptyList()
      val within = v - starts[idx]
      val segLast = starts[idx] + lengths[idx] - 1
      val takeLast = minOf(last, segLast)
      out += PhysicalRange(isoOffset = offsets[idx] + within, length = takeLast - v + 1)
      v = takeLast + 1
    }
    return out
  }

  /**
   * 虚拟偏移落在第几段；落在段与段之间的缝隙（理论不该存在）时返回 -1。
   *
   * [starts] 单调递增，所以可以二分。段数很少，直接二分也不亏。
   */
  private fun indexOfSegment(v: Long): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (v < starts[mid]) {
        hi = mid - 1
      } else if (v >= starts[mid] + lengths[mid]) {
        lo = mid + 1
      } else {
        return mid
      }
    }
    return -1
  }

  companion object {
    /**
     * 单段映射的便捷构造 —— 绝大多数情况（不切段的正片）走这里。
     *
     * 参数名用 [Segment] 的字段名（offset / length），不用「baseOffset」这类
     * 只在单段时才成立的叫法：单段是特例，多段才是常态。
     */
    fun single(offset: Long, length: Long): SegmentMap = SegmentMap(listOf(Segment(offset, length)))
  }
}