package app.marlboroadvance.mpvex.ui.player.subtitle.smart

/** 别名来源。[SYSTEM] 只用于展示，绝不参与强匹配；只有 [USER] 确认过的才算数。 */
enum class AliasSource {
  SYSTEM,
  USER,
}

/**
 * 一条别名记录：`canonicalIdentifier` 与 `aliasIdentifier` 指向同一部作品。
 *
 * @property canonical 归一化后的主编号（`N907`）
 * @property alias 归一化后的别名（`0907`）
 * @property source 来源。系统推测只是日志，用户确认才生效
 * @property createdAt 毫秒时间戳，用于「同一条别名的最后一次确认时间」
 * @property confidence 0..1。用户确认固定 1.0，系统推测 < 0.6
 */
data class AliasRecord(
  val canonical: String,
  val alias: String,
  val source: AliasSource,
  val createdAt: Long,
  val confidence: Double,
) {
  /** 落盘格式：`canonical<TAB>alias<TAB>source<TAB>createdAt<TAB>confidence`。 */
  fun encode(): String =
    listOf(canonical, alias, source.name, createdAt.toString(), confidence.toString())
      .joinToString("\t")

  companion object {
    fun decode(line: String): AliasRecord? {
      val parts = line.split('\t')
      if (parts.size < 5) return null
      val canonical = parts[0].trim()
      val alias = parts[1].trim()
      if (canonical.isEmpty() || alias.isEmpty() || canonical == alias) return null
      val source = runCatching { AliasSource.valueOf(parts[2].trim()) }.getOrDefault(AliasSource.USER)
      val createdAt = parts[3].trim().toLongOrNull() ?: 0L
      val confidence = parts[4].trim().toDoubleOrNull() ?: 1.0
      return AliasRecord(canonical, alias, source, createdAt, confidence)
    }
  }
}

/**
 * 番号别名管理（对应设计里的 AliasManager）。
 *
 * ## 只有 USER 记录生效
 *
 * 系统推测（`SYSTEM`）可以写进来做诊断，但 [equivalentsOf] 只吃 `USER`。
 * 「一次自动匹配成功就永久记为别名」是绝对不能做的事 —— 错一次会错一辈子，
 * 而且错得无声无息（用户只会觉得「这软件越来越乱」）。
 *
 * ## 关系是双向的
 *
 * 用户确认 `N0907 ≡ 0907` 之后，两个方向都必须成立；撤销时两边同时删。
 * 存储时只存一条，索引时双向登记，避免「A 记了别名、B 查不到」的半边状态。
 */
class AliasManager private constructor(
  private val records: List<AliasRecord>,
  private val index: Map<String, Set<String>>,
) {
  /** 与该编号等价的其他编号（紧凑写法，不含自身）。只认 USER 来源。 */
  fun equivalentsOf(compact: String): Set<String> = index[compact]?.minus(compact).orEmpty()

  /** 两条编号是否已被用户确认等价。 */
  fun isEquivalent(a: String, b: String): Boolean = a == b || b in equivalentsOf(a)

  /** 全部生效别名，供设置页展示与撤销。 */
  fun all(): List<AliasRecord> = records

  fun isEmpty(): Boolean = records.isEmpty()

  companion object {
    val EMPTY = AliasManager(emptyList(), emptyMap())

    /** 从落盘记录重建。`SYSTEM` 记录保留在 [all] 里但不进索引。 */
    fun fromRecords(records: List<AliasRecord>): AliasManager {
      if (records.isEmpty()) return EMPTY
      val index = HashMap<String, MutableSet<String>>()
      records.forEach { record ->
        if (record.source != AliasSource.USER) return@forEach
        if (record.confidence < 0.8) return@forEach
        index.getOrPut(record.canonical) { mutableSetOf() }.add(record.alias)
        index.getOrPut(record.alias) { mutableSetOf() }.add(record.canonical)
      }
      return AliasManager(records, index)
    }
  }
}
