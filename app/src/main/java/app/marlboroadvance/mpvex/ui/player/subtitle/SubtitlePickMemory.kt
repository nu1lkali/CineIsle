package app.marlboroadvance.mpvex.ui.player.subtitle

import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MediaQuery

/**
 * 「这部片子我上次挂的是哪条字幕」的进程内记忆。
 *
 * ## 为什么需要它
 *
 * 番号能证明「同一部」，但证明不了「哪一条字幕能对上这个版本」——
 * 同一个番号下常有简中 / 繁中 / 机翻 / 不同剪辑的多条字幕，而**文件名里没有任何信息**
 * 能区分它们的时间轴是否对得上（时间轴本身也不作为否决依据，见 [LocalSubtitleScanner]）。
 *
 * 所以最后一条真正可靠的判据是用户自己的选择：他点开、看了两分钟、没换——
 * 这条字幕就是对的。下次打开面板时把它排在最前面，比任何猜测都准。
 *
 * ## 为什么只存在内存里
 *
 * 这点偏好不值得引入一次持久化迁移与清理策略：进程内够用（换片、重开面板都在同一次播放里），
 * 而跨会话的「上次用哪条」价值有限 —— 用户下次可能就想要另一个语言版本了。
 * 容量 [CAPACITY] 条，LRU 淘汰，理论上不会积累到影响内存。
 */
object SubtitlePickMemory {
  private const val CAPACITY = 32

  /** accessOrder = true 让它按「最近使用」淘汰，而不是按写入顺序。 */
  private val picks =
    object : LinkedHashMap<String, String>(CAPACITY, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
        size > CAPACITY
    }

  /**
   * 记忆的键：优先用媒体名剥出来的字干（同一部片子稳定），
   * 退化为番号 / 片名（媒体名解析不出来时的兜底）。
   */
  fun keyOf(query: MediaQuery): String =
    query.stem.ifBlank { query.label.orEmpty() }

  fun remember(mediaKey: String, uri: String) {
    if (mediaKey.isBlank() || uri.isBlank()) return
    synchronized(picks) { picks[mediaKey] = uri }
  }

  fun recall(mediaKey: String): String? {
    if (mediaKey.isBlank()) return null
    return synchronized(picks) { picks[mediaKey] }
  }
}
