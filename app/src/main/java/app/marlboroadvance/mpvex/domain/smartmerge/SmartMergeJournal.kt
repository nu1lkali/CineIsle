package app.marlboroadvance.mpvex.domain.smartmerge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Smart Merge 的本地账本。
 *
 * 为什么先用 JSON 文件而不是立刻加 Room 表：
 *  · 阶段 1/2 需要快速迭代候选/操作结构，Room schema 每次字段变化都要迁移；
 *  · 完整条目快照（`BaseItemDto` 原文）可能有几十 KB，放 Room 普通列并不合适；
 *  · 项目已有 `PersonMergeJournal` 的成熟范式：临时文件 + rename 原子写、
 *    按 serverId 分文件、可 prune。
 *
 * 等结构稳定后，再把候选/忽略规则迁进 Room 做查询/分页；快照仍留文件系统。
 */
class SmartMergeJournal(private val dir: File) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private fun stateFile(serverId: Long) = File(dir, "state_$serverId.json")

  private fun snapshotDir(operationId: String) = File(dir, "snapshots/$operationId")

  @Synchronized
  fun load(serverId: Long): SmartMergeState = runCatching {
    val file = stateFile(serverId)
    if (!file.exists()) SmartMergeState(serverId = serverId)
    else json.decodeFromString<SmartMergeState>(file.readText())
  }.getOrElse { error ->
    SmartMergeLog.error("读取状态失败 serverId=$serverId，将使用空状态", error)
    SmartMergeState(serverId = serverId)
  }

  @Synchronized
  fun save(state: SmartMergeState): Boolean = try {
    ensureDirectory(dir)
    val target = stateFile(state.serverId)
    val tmp = File(dir, "state_${state.serverId}.tmp")
    tmp.writeText(json.encodeToString(state))
    if (!tmp.renameTo(target)) {
      target.writeText(tmp.readText())
      if (!tmp.delete()) {
        SmartMergeLog.warn("状态临时文件删除失败 path=${tmp.absolutePath}")
      }
    }
    true
  } catch (error: Throwable) {
    SmartMergeLog.error("保存状态失败 serverId=${state.serverId}", error)
    false
  }

  /** 保存完整条目原文（用于事后核对 / 人工恢复参考，不作为自动重建依据）。 */
  @Synchronized
  fun saveRawSnapshot(operationId: String, itemId: String, rawJson: String) {
    try {
      val operationDir = snapshotDir(operationId)
      ensureDirectory(operationDir)
      val target = File(operationDir, "${safeName(itemId)}.json")
      val tmp = File(operationDir, "${safeName(itemId)}.tmp")
      tmp.writeText(rawJson)
      if (!tmp.renameTo(target)) {
        target.writeText(tmp.readText())
        if (!tmp.delete()) {
          SmartMergeLog.warn("快照临时文件删除失败 path=${tmp.absolutePath}")
        }
      }
    } catch (error: Throwable) {
      SmartMergeLog.error("保存原始快照失败 operationId=$operationId itemId=$itemId", error)
      throw error
    }
  }

  @Synchronized
  fun readRawSnapshot(operationId: String, itemId: String): String? = runCatching {
    val file = File(snapshotDir(operationId), "${safeName(itemId)}.json")
    if (file.exists()) file.readText() else null
  }.getOrElse { error ->
    SmartMergeLog.error("读取原始快照失败 operationId=$operationId itemId=$itemId", error)
    null
  }

  /** 清理尚未进入账本的失败操作快照，避免遗留不可追踪的半成品。 */
  @Synchronized
  fun deleteSnapshots(operationId: String) {
    val directory = snapshotDir(operationId)
    if (!directory.exists()) return
    runCatching {
      if (!directory.deleteRecursively() && directory.exists()) {
        error("删除后目录仍存在：${directory.absolutePath}")
      }
    }.onFailure { error ->
      SmartMergeLog.warn("清理失败操作快照失败 operationId=$operationId", error)
    }
  }

  /** 只保留最近 [keep] 条操作；快照目录跟着操作一起裁。 */
  @Synchronized
  fun prune(state: SmartMergeState, keep: Int = 50): SmartMergeState {
    if (state.operations.size <= keep) return state
    val kept = state.operations.take(keep)
    val removed = state.operations.drop(keep)
    removed.forEach { op ->
      runCatching { snapshotDir(op.id).deleteRecursively() }
        .onFailure { error ->
          SmartMergeLog.warn("裁剪快照目录失败 operationId=${op.id}", error)
        }
    }
    return state.copy(operations = kept)
  }

  private fun ensureDirectory(directory: File) {
    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
      error("无法创建目录：${directory.absolutePath}")
    }
  }

  private fun safeName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
