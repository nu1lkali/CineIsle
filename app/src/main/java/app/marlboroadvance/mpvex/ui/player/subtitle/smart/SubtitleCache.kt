package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/**
 * 预处理缓存（对应设计里的 SubtitleCache）。
 *
 * ## 为什么只缓存「文件名解析结果」
 *
 * 字幕目录的枚举躲不掉（SAF 的 `listFiles` 每层都是跨进程调用），但**正则解析**
 * 是纯 CPU：一个文件名要跑八条规则 + 清洗 + 分词。几百个文件乘上去，
 * 每次打开字幕面板都要重算一遍，面板就会明显晚半拍。
 *
 * ## 为什么用 LRU 而不是全量常驻
 *
 * 几百个 `TitleFacts` 对象只有几十 KB，本可以不淘汰。但用户的字幕目录可能被
 * 换成另一个盘（几万个文件），无上限的静态表会一直涨到进程结束。
 * 上限取 [MAX_ENTRIES]，超了淘汰最久未用的，代价只是重算一次。
 */
object SubtitleCache {
  private const val MAX_ENTRIES = 2_048

  private val entries =
    object : LinkedHashMap<String, TitleFacts>(256, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TitleFacts>?): Boolean =
        size > MAX_ENTRIES
    }

  /** 规则版本变化时整体作废，避免旧口径的结果被继续复用。 */
  private var version: Int = currentVersion()

  private fun currentVersion(): Int =
    FilenameNormalizer.VERSION * 31 + IdentifierExtractor.VERSION

  /** 取一条字幕的解析结果（含缓存）。 */
  fun factsOf(relativePath: String): TitleFacts {
    val now = currentVersion()
    synchronized(entries) {
      if (now != version) {
        entries.clear()
        version = now
      }
      entries[relativePath]?.let { return it }
      val facts = TitleFacts.of(relativePath)
      entries[relativePath] = facts
      return facts
    }
  }

  /** 目录换过之后清空（用户改设置时调用）。 */
  fun clear() {
    synchronized(entries) { entries.clear() }
  }

  /** 当前条目数，仅用于日志与测试。 */
  val size: Int
    get() = synchronized(entries) { entries.size }
}
