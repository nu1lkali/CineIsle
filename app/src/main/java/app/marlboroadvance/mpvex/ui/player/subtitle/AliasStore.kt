package app.marlboroadvance.mpvex.ui.player.subtitle

import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.AliasManager
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.AliasRecord
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.AliasSource

/**
 * 番号别名的持久化。
 *
 * ## 为什么不用 Room
 *
 * 这是「两个短字符串等价」的小表（200 条就是十几 KB），而 Room 加一张表要走
 * schema 迁移（当前 v20 且 `exportSchema = true`）。为一个键值对付出一次迁移风险不划算 ——
 * 与 [SubtitleBindings] 同一套取舍。
 *
 * ## 什么情况下才写
 *
 * 只有用户**明确确认**「这两个编号是同一部作品」时才写 `USER` 记录。
 * 手动选一条字幕只记 [SubtitleBindings]（这部片子用这个文件），
 * 绝不自动升格成别名 —— 否则一次点错会变成永久性的全局错误。
 */
object AliasStore {
  private const val TAG = "AliasStore"

  /** 上限。每条几十字节，200 条远超正常使用量。 */
  private const val MAX_ENTRIES = 200

  private const val FIELD = '\t'
  private const val RECORD = '\n'

  /** 读出解析好的别名表（只含 USER 记录，见 [AliasManager.fromRecords]）。 */
  fun load(preferences: SubtitlesPreferences): AliasManager =
    AliasManager.fromRecords(read(preferences))

  /** 全部记录（含 SYSTEM，供设置页排查）。 */
  fun all(preferences: SubtitlesPreferences): List<AliasRecord> = read(preferences)

  /**
   * 记一条别名。同一对（忽略顺序）已存在时只更新时间戳，不重复写。
   *
   * @return true 表示写入成功且确实发生了变化
   */
  fun add(
    preferences: SubtitlesPreferences,
    canonical: String,
    alias: String,
    source: AliasSource = AliasSource.USER,
    now: Long = System.currentTimeMillis(),
  ): Boolean {
    val a = canonical.trim().uppercase()
    val b = alias.trim().uppercase()
    if (a.isEmpty() || b.isEmpty() || a == b) return false

    val records = read(preferences).toMutableList()
    val existing =
      records.indexOfFirst {
        (it.canonical == a && it.alias == b) || (it.canonical == b && it.alias == a)
      }
    val record =
      AliasRecord(
        canonical = a,
        alias = b,
        source = source,
        createdAt = now,
        confidence = if (source == AliasSource.USER) 1.0 else 0.5,
      )
    if (existing >= 0) {
      // 已确认过的一对，重复确认只刷新时间（保持它在淘汰顺序里的位置）
      records[existing] = record
    } else {
      records += record
      while (records.size > MAX_ENTRIES) records.removeAt(0)
    }
    return write(preferences, records)
  }

  /** 撤销一条别名。两个方向都删，避免「A 记了、B 还留着」的半边状态。 */
  fun remove(preferences: SubtitlesPreferences, canonical: String, alias: String): Boolean {
    val a = canonical.trim().uppercase()
    val b = alias.trim().uppercase()
    val records = read(preferences)
    val kept =
      records.filterNot {
        (it.canonical == a && it.alias == b) || (it.canonical == b && it.alias == a)
      }
    if (kept.size == records.size) return false
    return write(preferences, kept)
  }

  private fun read(preferences: SubtitlesPreferences): List<AliasRecord> {
    val raw = runCatching { preferences.subtitleCodeAliases.get() }.getOrDefault("")
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence()
      .mapNotNull { AliasRecord.decode(it) }
      .toList()
  }

  private fun write(preferences: SubtitlesPreferences, records: List<AliasRecord>): Boolean {
    val text =
      buildString {
        records.forEach { append(it.encode()).append(RECORD) }
      }
    return runCatching { preferences.subtitleCodeAliases.set(text) }
      .onFailure { Log.w(TAG, "save aliases failed: ${it.message}") }
      .isSuccess
  }

  /** 落盘字段分隔符，供测试与排查使用。 */
  internal val fieldSeparator: Char
    get() = FIELD
}
