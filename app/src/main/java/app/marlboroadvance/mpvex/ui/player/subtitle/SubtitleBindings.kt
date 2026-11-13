package app.marlboroadvance.mpvex.ui.player.subtitle

import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SmartSubtitleMatcher

/**
 * 「这部片子我手动挂的是哪条字幕」的**持久化**账本。
 *
 * ## 为什么需要
 *
 * 字幕文件通常不在视频旁边（在字幕保存目录里，甚至另一块盘），
 * 每次重新打开同一部片子都要再翻一遍目录、再点一次 —— 一个纯手工、可记忆的动作。
 * 把它记住之后，[app.marlboroadvance.mpvex.ui.player.PlayerViewModel] 在片名确定时自动挂回去，
 * 用户看到的直接就是带字幕的画面。
 *
 * ## 为什么不用 Room
 *
 * 这是「一个媒体名 → 一个 Uri」的极小映射（几百条就是几十 KB），
 * 而 Room 加一张表要走 schema 迁移（当前 v20 且 `exportSchema = true`），
 * 为一个键值对付出一次迁移风险不划算。这里存进现有的偏好存储（SharedPreferences 后端），
 * 一行一条记录，`key<TAB>uri`，读写都是 O(n) 且 n 有上限。
 *
 * 键用 [app.marlboroadvance.mpvex.ui.player.subtitle.smart.FilenameNormalizer] 归一化后的字干
 * （去扩展名、去站点前缀、去语言标签、小写）：
 * 换字幕、换目录都不会让绑定失效；改片名会（这是可接受的代价，改名的片子本来就很难自动关联）。
 */
object SubtitleBindings {
  private const val TAG = "SubtitleBindings"

  /** 上限。每条几十字节，200 条已经远超「一个人看过多少部片」的实际需要。 */
  private const val MAX_ENTRIES = 200

  private const val FIELD_SEPARATOR = '\t'
  private const val RECORD_SEPARATOR = '\n'

  /** 媒体名 → 稳定的键。跟 [SubtitlePickMemory] 用同一口径，两处才不会各记一套。 */
  fun keyOf(mediaTitle: String): String {
    val stem =
      runCatching { SmartSubtitleMatcher.prepare(mediaTitle).stem }.getOrNull().orEmpty()
    return stem.ifBlank { mediaTitle.lowercase().trim() }
  }

  /** 查这部片子绑过哪条字幕；没有则为 null。 */
  fun find(preferences: SubtitlesPreferences, mediaTitle: String): String? {
    if (mediaTitle.isBlank()) return null
    return read(preferences)[keyOf(mediaTitle)]
  }

  /** 记住「这部片子用这条字幕」。同一条媒体重复绑定会覆盖旧值并移到最新位。 */
  fun bind(preferences: SubtitlesPreferences, mediaTitle: String, uri: String) {
    if (mediaTitle.isBlank() || uri.isBlank()) return
    val key = keyOf(mediaTitle)
    if (key.isBlank()) return

    val map = read(preferences)
    map.remove(key)
    map[key] = uri
    // 超上限就丢最早的：LinkedHashMap 的迭代顺序就是写入顺序
    while (map.size > MAX_ENTRIES) {
      val oldest = map.keys.firstOrNull() ?: break
      map.remove(oldest)
    }
    write(preferences, map)
  }

  /** 用户主动解绑（例如换了字幕之后又删掉）。 */
  fun unbind(preferences: SubtitlesPreferences, mediaTitle: String) {
    if (mediaTitle.isBlank()) return
    val map = read(preferences)
    if (map.remove(keyOf(mediaTitle)) != null) write(preferences, map)
  }

  private fun read(preferences: SubtitlesPreferences): LinkedHashMap<String, String> {
    val raw = runCatching { preferences.subtitleBindings.get() }.getOrDefault("")
    val map = LinkedHashMap<String, String>()
    if (raw.isBlank()) return map
    raw.lineSequence().forEach { line ->
      if (line.isBlank()) return@forEach
      val split = line.indexOf(FIELD_SEPARATOR)
      if (split <= 0 || split == line.lastIndex) return@forEach
      val key = line.substring(0, split)
      val value = line.substring(split + 1)
      if (key.isNotBlank() && value.isNotBlank()) map[key] = value
    }
    return map
  }

  private fun write(preferences: SubtitlesPreferences, map: LinkedHashMap<String, String>) {
    val text =
      buildString {
        map.forEach { (key, value) ->
          append(key).append(FIELD_SEPARATOR).append(value).append(RECORD_SEPARATOR)
        }
      }
    runCatching { preferences.subtitleBindings.set(text) }
      .onFailure { Log.w(TAG, "save bindings failed: ${it.message}") }
  }
}
