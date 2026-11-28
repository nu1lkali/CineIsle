package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstoneStore
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstones
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除一条网络字幕的**统一编排**（播放器、字幕实验室、全局删除共用）。
 *
 * 一条网络字幕有两份字节：应用私有目录的权威副本，以及用户字幕目录里的镜像副本。
 * 本修复只清理应用私有的下载与索引；用户媒体目录里的镜像始终保留，靠 tombstone 阻止
 * 它被本地扫描、绑定恢复或 TSV 恢复重新挂载。
 *
 * ## 顺序与失败策略
 *
 * 1. **先同步落盘 tombstone**：在线 origin、私有文件路径、镜像路径 / 稳定 URI；
 * 2. 任一必要 tombstone 写失败 ⇒ 不删索引、不删文件、不移除清单行，避免清理后复活；
 * 3. tombstone 已写 ⇒ 再删私有记录 / 文件、按需处理镜像、移除清单行；
 * 4. 后续任一步失败都不回滚 tombstone，因此重启后也不会复活。
 *
 * 全部 IO 阻塞，调用方需在 [Dispatchers.IO] 上（[delete] 内部已切换）。
 */
object OnlineSubtitleCleanup {
  data class CleanupResult(
    val privateDeleted: Boolean = false,
    val mirrorDeleted: Boolean = false,
    val mirrorSkipped: Boolean = false,
    val mirrorFailed: Boolean = false,
    val manifestRemoved: Boolean = false,
    /** 播放器只有在 true 时才允许卸载 mpv 轨道。 */
    val tombstonePersisted: Boolean = false,
    /** 残留镜像的稳定身份，供诊断 / 日志使用。 */
    val mirrorUri: String? = null,
  )

  /**
   * @param deleteMirror 默认 false：保留用户目录字节。只有明确需要物理清理镜像的调用方
   *   才传 true；即使删除失败，外部路径 tombstone 仍会保留。
   */
  suspend fun delete(
    context: Context,
    library: OnlineSubtitleLibrary,
    preferences: SubtitlesPreferences,
    record: DownloadedSubtitle,
    deleteMirror: Boolean = false,
  ): CleanupResult =
    withContext(Dispatchers.IO) {
      // 清单必须在 library.delete 之前读，才能判断镜像是否仍被其它记录引用。
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
      val mirror =
        if (root != null && target != null && fileName.isNotBlank() && !stillReferenced) {
          if (dirName.isBlank()) root.child(fileName) else root.child(dirName)?.child(fileName)
        } else {
          null
        }

      val tombstones = SubtitleTombstoneStore(preferences)
      val privateFile = library.fileOf(record)
      val privatePathShared = library.hasOtherFileReference(record.id, record.fileName)

      // 1) 所有必要 tombstone 必须作为一次同步事务提交，才允许触碰字幕字节 / 索引。
      val onlineTombstone = SubtitleTombstones.online(
        serverKey = record.serverKey,
        itemId = record.itemId,
        originId = record.originId,
        originUri = record.originUrl,
        storagePath = privateFile.absolutePath,
        createdAt = System.currentTimeMillis(),
      )
      val required = buildList {
        onlineTombstone?.let(::add)
        if (!privatePathShared) {
          SubtitleTombstones.external(privateFile.absolutePath, null, System.currentTimeMillis())?.let(::add)
        }
        mirror?.let { file ->
          SubtitleTombstones.external(
            file.uri.path.takeIf { file.uri.scheme == "file" },
            file.uri.toString(),
            System.currentTimeMillis(),
          )?.let(::add)
        }
      }
      val persisted = onlineTombstone != null && tombstones.rememberAll(required)
      if (!persisted) {
        Log.w(TAG, "online subtitle tombstone transaction failed: ${record.id}")
        return@withContext CleanupResult(mirrorSkipped = true)
      }

      // 2) 权威副本：沿用既有引用计数删除，不改它的语义。
      val privateDeleted = runCatching { library.delete(record.id) }.isSuccess

      // 3) 用户目录副本默认只忽略、不删字节。
      var mirrorDeleted = false
      var mirrorSkipped = false
      var mirrorFailed = false
      when {
        !deleteMirror -> mirrorSkipped = true
        root == null || target == null || fileName.isBlank() || mirror == null ->
          mirrorSkipped = true
        !mirror.isFile -> mirrorDeleted = true
        else -> {
          mirrorDeleted = runCatching { mirror.delete() }.getOrDefault(false)
          if (!mirrorDeleted) {
            mirrorFailed = true
            Log.w(TAG, "mirror delete failed: ${record.id} -> $dirName/$fileName")
          } else if (dirName.isNotBlank()) {
            val parent = root.child(dirName)
            if (parent != null && parent.children().isEmpty()) runCatching { parent.delete() }
          }
        }
      }

      // 4) 清单行：镜像删除失败也移除，恢复计划还会再按在线 tombstone 过滤一次。
      val manifestRemoved = SubtitleSaveManifest.remove(root, record.id)
      CleanupResult(
        privateDeleted = privateDeleted,
        mirrorDeleted = mirrorDeleted,
        mirrorSkipped = mirrorSkipped,
        mirrorFailed = mirrorFailed,
        manifestRemoved = manifestRemoved,
        tombstonePersisted = true,
        mirrorUri = mirror?.uri?.toString(),
      )
    }

  private const val TAG = "OnlineSubtitleCleanup"
}
