package app.marlboroadvance.mpvex.domain.subtitle

/**
 * 字幕字节 → 可安全交给播放内核的 UTF-8。
 *
 * ## 为什么需要这一步
 *
 * `sub-add` 把文件交给 mpv 之后，**mpv 会自己再判一次编码**。它的判据与我们不同：
 * 先看 BOM，再按 `sub-codepage`（默认 auto，内部走自己的探测）解。于是同一份
 * Big5 繁体字幕会出现两种结论 —— 时间线预览按 Big5 解对了，画面上的字幕却是
 * GBK 乱码。这不是猜错谁的问题，是**两套判定各自为政**的必然结果。
 *
 * 唯一能消除分歧的做法是先归一：判定一次，把结果写成 UTF-8，再交给 mpv。
 * 从此 mpv 不需要猜，预览与画面读的是同一份文本。
 *
 * ## 谁在做判定
 *
 * 判定只有 [SubtitleTimeline.decodeDetailed] 一处（BOM → 严格 UTF-8 → 多候选择优
 * → 二次编码还原 → 逐行修复），这里只负责「要不要重写」和「怎么写」。两处各写一套
 * 判定正是问题本身，不能在新代码里重演。
 */
object SubtitleEncoding {
  /**
   * 归一化结果。
   *
   * @property decoded 解码结论（编码名、损坏比例、是否修复过）
   * @property utf8 建议写入的 UTF-8 字节。原字节已经规范时**原样返回入参**，
   *   调用方可以直接拿它去比对，避免无意义的重写
   * @property changed 是否需要重写。为 false 时 [utf8] 与入参内容相同
   */
  data class Normalized(
    val decoded: SubtitleTimeline.Decoded,
    val utf8: ByteArray,
    val changed: Boolean,
  ) {
    // ByteArray 在 data class 里默认按引用比较，这里显式给出内容相等语义
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Normalized) return false
      return decoded == other.decoded && changed == other.changed && utf8.contentEquals(other.utf8)
    }

    override fun hashCode(): Int =
      (decoded.hashCode() * 31 + changed.hashCode()) * 31 + utf8.contentHashCode()
  }

  /**
   * 归一化一段字幕字节。
   *
   * 已经是规范 UTF-8 的文件（含带 BOM 的）**原样放行**：重写一遍只会改变 BOM 与
   * 行尾，没有收益，却会让「文件修改时间」无端变化（本地库按 mtime 判断是否需要
   * 重新解析）。
   */
  fun normalize(bytes: ByteArray): Normalized {
    val decoded = SubtitleTimeline.decodeDetailed(bytes)
    if (decoded.mpvSafe || decoded.text.isEmpty()) {
      return Normalized(decoded, bytes, changed = false)
    }
    return Normalized(decoded, decoded.text.toByteArray(Charsets.UTF_8), changed = true)
  }

  /**
   * 该文件的字节是否需要重写才能交给 mpv。
   *
   * 只解码不落盘，供「要不要生成缓存副本」这类判断使用。
   */
  fun needsRewrite(bytes: ByteArray): Boolean = normalize(bytes).changed
}
