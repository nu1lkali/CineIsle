package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleEncoding
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleCandidate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「字幕保存目录」里已有的文件按**指定影片**导入本地库。
 *
 * ## 为什么不是「自动重建清单」
 *
 * 保存目录里的副本是 `OnlineSubtitleSaveFolder.mirror` 写的，命名只有
 * `标题.online.格式`，**不含 `serverKey` / `itemId` / `originId`**。凭文件名反推索引
 * 只能靠猜：同名不同片（重制版、合集、同系列不同集）会挂错，而一旦写进 tsv，
 * 重装恢复就会把这份错误固定下来 —— 比「不在已下载列表里」更糟。
 *
 * 所以归属必须由用户给：实验室里先选一部影片，再把目录里与之匹配的文件导进来。
 * 影片已确定，索引主键就是准确的，写进 tsv 也就安全了。
 *
 * ## 与 [SubtitleSaveRestore] 的分工
 *
 * [SubtitleSaveRestore] 处理**清单里有登记**的文件（精确还原，不需要人参与）；
 * 这里处理**清单没登记**的（旧版下载、手工拷入），必须逐部确认。
 *
 * 全部方法阻塞 IO，调用方需在 `Dispatchers.IO` 上执行。
 */
object SubtitleSaveImport {
  private const val TAG = "SubtitleSaveImport"

  /**
   * 导入结果。
   *
   * @property imported 写入本地库并登记进清单的条数
   * @property failed 读不到 / 写不进 / 内容为空的条数
   */
  data class Outcome(
    val imported: Int = 0,
    val failed: Int = 0,
  ) {
    val total: Int get() = imported + failed
  }

  /**
   * 逐条导入。
   *
   * @param mediaTitle 影片标题：作为本地库的记录标题，也是保存目录子目录名的来源
   * @param candidates 由 `LocalSubtitleScanner` 按该影片匹配出的候选
   */
  suspend fun importCandidates(
    context: Context,
    library: OnlineSubtitleLibrary,
    serverKey: String,
    itemId: String,
    mediaTitle: String,
    saveDirPath: String,
    legacyTreeUri: String,
    candidates: List<LocalSubtitleCandidate>,
  ): Outcome =
    withContext(Dispatchers.IO) {
      if (serverKey.isBlank() || itemId.isBlank() || candidates.isEmpty()) return@withContext Outcome()
      val app = context.applicationContext
      // 保存目录只开一次：导入过程里用户改设置的窗口很小，但两次 open 可能命中不同形态
      val saveRoot = SubtitleSaveStore.open(app, saveDirPath, legacyTreeUri)
      var imported = 0
      var failed = 0

      candidates.forEach { candidate ->
        val ok =
          runCatching {
              importOne(
                context = app,
                library = library,
                saveRoot = saveRoot,
                serverKey = serverKey,
                itemId = itemId,
                mediaTitle = mediaTitle,
                candidate = candidate,
              )
            }
            .onFailure { Log.w(TAG, "import failed: ${candidate.relativePath}: ${it.message}") }
            .getOrDefault(false)
        if (ok) imported++ else failed++
      }

      Log.i(TAG, "import done: ok=$imported failed=$failed")
      Outcome(imported = imported, failed = failed)
    }

  /** @return true 表示已写入本地库（并尽量登记进清单） */
  private fun importOne(
    context: Context,
    library: OnlineSubtitleLibrary,
    saveRoot: SaveNode?,
    serverKey: String,
    itemId: String,
    mediaTitle: String,
    candidate: LocalSubtitleCandidate,
  ): Boolean {
    val raw =
      runCatching {
          context.contentResolver.openInputStream(Uri.parse(candidate.uri))?.use { it.readBytes() }
        }
        .getOrNull()
    if (raw == null || raw.isEmpty()) return false

    // 与其它入口同口径：落盘前统一归一成 UTF-8，否则播放侧会按编码误判
    val payload = SubtitleEncoding.normalize(raw).utf8
    val name = OnlineSubtitleLibrary.safeFileName(candidate.name)
    val dir = library.mediaDir(serverKey, itemId)
    if (!dir.isDirectory && !dir.mkdirs()) return false

    val target = File(dir, name)
    val tmp = File(dir, ".$name.part")
    try {
      tmp.writeBytes(payload)
      if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
      }
    } catch (t: Throwable) {
      runCatching { tmp.delete() }
      Log.w(TAG, "write failed: $name: ${t.message}")
      return false
    }

    // 稳定 originId：同一相对路径重复导入要覆盖同一条记录，不能每次新增
    val originId = "local:" + candidate.relativePath.hashCode().toUInt().toString(16)
    val record =
      DownloadedSubtitle(
        id = "$serverKey|$itemId|$originId",
        serverKey = serverKey,
        itemId = itemId,
        mediaTitle = mediaTitle,
        source = OnlineSubtitleSource.LOCAL,
        title = candidate.name.substringBeforeLast('.', candidate.name),
        fileName = library.relativePathOf(target),
        format = candidate.name.substringAfterLast('.', "srt").lowercase(),
        language = null,
        sizeBytes = target.length(),
        downloadedAt = System.currentTimeMillis(),
        offsetMs = 0L,
        preferred = false,
        originId = originId,
        originUrl = "",
        // 文件本来就在用户的字幕目录里，没有「另存」这一步
        archived = true,
      )
    library.upsert(record)

    // 登记清单：下次重装恢复可以精确还原，不必再让人确认一遍
    if (saveRoot != null) {
      val dirName = candidate.relativePath.substringBeforeLast('/', "")
      val fileName = candidate.relativePath.substringAfterLast('/')
      SubtitleSaveManifest.upsert(
        saveRoot,
        SubtitleSaveManifest.Entry(
          serverKey = serverKey,
          itemId = itemId,
          originId = originId,
          mediaTitle = mediaTitle,
          title = record.title,
          sourceKey = OnlineSubtitleSource.LOCAL.key,
          dirName = dirName,
          fileName = fileName,
          format = record.format,
          language = null,
          sizeBytes = record.sizeBytes,
          downloadedAt = record.downloadedAt,
          offsetMs = 0L,
          preferred = false,
          originUrl = "",
        ),
      )
    }
    return true
  }
}
