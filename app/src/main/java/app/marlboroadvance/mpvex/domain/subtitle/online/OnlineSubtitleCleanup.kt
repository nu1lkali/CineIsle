package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除一条网络字幕的**统一编排**（三个入口共用）。
 *
 * ## 为什么需要单独一层
 *
 * 一条网络字幕有两份字节：应用私有目录的**权威副本**（真正挂载）和用户字幕目录里的
 * **镜像副本**（用户可见 / 重装恢复）。删除时两份都要清，外加清单里那行索引 —— 这件事
 * 原本只有 [OnlineSubtitleLibrary.delete] 在做，且它只删私有那份，用户目录里就留下孤儿。
 * 删除入口有三处（字幕面板、实验室「已下载」页、全局删除），逐个补一遍极易漏，所以收口到
 * 这里：入口只管调一次 [delete]，结果由 [CleanupResult] 聚合上报。
 *
 * ## 删除顺序与失败策略
 *
 * 1. 权威副本：沿用 [OnlineSubtitleLibrary.delete] 自带的引用计数删除（不动它的既有行为）；
 * 2. 用户目录副本：按清单定位，存在才删、删除失败只记日志不阻断；
 * 3. 清单行：无论副本删没删掉都移除，避免重装恢复把已删记录当可恢复项。
 *
 * 任一步失败都不回滚、不抛给调用方 —— 删除是幂等操作，重复点也安全。
 *
 * ## 边界
 *
 * · 副本已不存在 ⇒ 视为完成；
 * · 未配置保存目录 / 清单无此行 ⇒ 跳过副本清理，只删权威副本；
 * · 路径变化（用户改了保存目录）⇒ 只按新根的清单定位，旧根残留不跨根追杀；
 * · 同一路径仍被其它清单记录引用 ⇒ 只移除本行，不删文件；
 * · SAF 授权撤销 / 删除抛异常 ⇒ 记 warn 日志，仍移除清单行。
 *
 * 全部 IO 阻塞，调用方需在 [Dispatchers.IO] 上（[delete] 内部已切换）。
 */
object OnlineSubtitleCleanup {
  /**
   * 删除结果。UI 据此决定要不要给一次性提示（例如副本删除失败时告诉用户手动清理）。
   *
   * [privateDeleted] 恒为 true 表示权威副本记录已交给本地库删除（本地库内部可能因为
   * 引用计数保留文件本体，那是它既有的安全策略，不算失败）。
   */
  data class CleanupResult(
    val privateDeleted: Boolean = true,
    val mirrorDeleted: Boolean = false,
    val mirrorSkipped: Boolean = false,
    val mirrorFailed: Boolean = false,
  )

  /**
   * 删除一条已下载字幕的全部副本。
   *
   * @param record 要删的记录（id 与 `library` 索引主键同构）
   */
  suspend fun delete(
    context: Context,
    library: OnlineSubtitleLibrary,
    preferences: SubtitlesPreferences,
    record: DownloadedSubtitle,
  ): CleanupResult =
    withContext(Dispatchers.IO) {
      // 清单必须在 library.delete 之前读 —— 虽然两者改的是不同存储，但先读能拿到
      // 「这份副本是否还被别的记录引用」的完整视图。
      val root =
        SubtitleSaveStore.open(
          context = context,
          dirPath = preferences.subtitleSaveDirPath.get(),
          legacyTreeUri = preferences.subtitleSaveFolder.get(),
        )
      val entries = SubtitleSaveManifest.read(root)
      val target = entries.firstOrNull { it.id == record.id }
      val dirName = target?.dirName.orEmpty()
      val fileName = target?.fileName.orEmpty()
      val stillReferenced =
        target != null &&
          entries.any { it.id != record.id && it.dirName == dirName && it.fileName == fileName }

      // 1) 权威副本：沿用既有引用计数删除，不改它的语义
      library.delete(record.id)

      // 2) 用户目录副本
      var mirrorDeleted = false
      var mirrorSkipped = false
      var mirrorFailed = false
      when {
        root == null || target == null || fileName.isBlank() ->
          // 未配置目录 / 清单无此行 ⇒ 无从定位副本，跳过
          mirrorSkipped = true
        stillReferenced ->
          // 同一路径仍被别的记录引用：只移除本行，文件必须保留
          mirrorSkipped = true
        else -> {
          val file =
            if (dirName.isBlank()) root.child(fileName) else root.child(dirName)?.child(fileName)
          if (file == null || !file.isFile) {
            // 文件已不存在（可能用户自己删了）⇒ 视为完成，照常移除清单行
            mirrorDeleted = true
          } else {
            mirrorDeleted = runCatching { file.delete() }.getOrDefault(false)
            if (!mirrorDeleted) {
              mirrorFailed = true
              Log.w(TAG, "mirror delete failed: ${record.id} -> $dirName/$fileName")
            } else if (dirName.isNotBlank()) {
              // 空目录顺手清掉，避免用户目录里留下成片的空文件夹
              val parent = root.child(dirName)
              if (parent != null && parent.children().isEmpty()) {
                runCatching { parent.delete() }
              }
            }
          }
        }
      }

      // 3) 清单行：无论副本删没删掉都移除，防止恢复流程把它当可恢复项
      SubtitleSaveManifest.remove(root, record.id)

      CleanupResult(
        privateDeleted = true,
        mirrorDeleted = mirrorDeleted,
        mirrorSkipped = mirrorSkipped,
        mirrorFailed = mirrorFailed,
      )
    }

  private const val TAG = "OnlineSubtitleCleanup"
}
