package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleEncoding
import app.marlboroadvance.mpvex.utils.media.SubtitleFileTypes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从「字幕保存目录」恢复到应用私有字幕库。
 *
 * ## 恢复的是什么
 *
 * 卸载重装会清掉两样东西：私有目录里的字幕字节，以及偏好里的本地库索引
 * （`serverKey + itemId → 文件`）。用户字幕保存目录里的副本仍在，但文件名不含
 * `serverKey` / `itemId` —— 只凭文件名无法安全地重建索引。
 *
 * 因此恢复的依据是保存目录里的清单 [SubtitleSaveManifest]：**按清单把文件拷回私有目录，
 * 并按清单重建索引**。清单不存在（旧版本下载的、或用户手工拷进去的字幕）时不做任何
 * 猜测性重建 —— 那类文件仍可由播放侧的 `LocalSubtitleScanner` 按番号 / 标题在保存目录里
 * 匹配到，不重建索引不会导致「完全用不了」，但不会出现在「已下载」列表里。
 *
 * ## 两段式
 *
 * [plan] 只统计不改动，供界面在选完目录后先给一次确认；[restore] 才真正写盘。
 * 统计与执行分开，是因为恢复可能是几百条文件 —— 不该在用户点选目录的瞬间无条件开始复制。
 */
object SubtitleSaveRestore {
  private const val TAG = "SubtitleSaveRestore"

  /** 一条待恢复项：清单里的记录 + 在保存目录里定位到的文件。 */
  data class Item(
    val entry: SubtitleSaveManifest.Entry,
    val file: SaveNode,
  )

  /**
   * 恢复计划。
   *
   * @property items 可恢复项（本机索引里没有、且文件确实存在）
   * @property bytes 可恢复项合计字节
   * @property alreadyPresent 本机索引里已有、无需恢复的条数
   * @property missingFiles 清单里有、但文件已不存在的条数
   * @property unlistedFiles 目录里存在、但清单未登记的字幕文件数（旧版下载或手工拷入）。
   *   这些文件不含 `serverKey` / `itemId`，无法按清单精确归属，只能按影片在字幕实验室里导入
   */
  data class Plan(
    val items: List<Item> = emptyList(),
    val bytes: Long = 0L,
    val alreadyPresent: Int = 0,
    val missingFiles: Int = 0,
    val unlistedFiles: Int = 0,
  ) {
    val restorable: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
  }

  /**
   * 恢复结果。
   *
   * @property failed 读不到 / 写不进 / 来源字段不可识别的条数
   */
  data class Outcome(
    val restored: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0L,
  )

  /** 只统计，不写盘。目录未配置或清单不存在时返回空计划。 */
  suspend fun plan(
    context: Context,
    saveDirPath: String,
    legacyTreeUri: String,
    library: OnlineSubtitleLibrary,
  ): Plan =
    withContext(Dispatchers.IO) {
      if (!SubtitleSaveStore.isConfigured(saveDirPath, legacyTreeUri)) {
        return@withContext Plan()
      }
      runCatching {
        val root =
          SubtitleSaveStore.open(context, saveDirPath, legacyTreeUri)
            ?: return@runCatching Plan()
        val entries = SubtitleSaveManifest.read(root)
        // 清单缺失（旧版本下载、手工拷入）时不能直接返回空计划：仍要统计目录里有多少
        // 字幕未被覆盖，UI 才能据此提示「这些文件只能按影片在字幕实验室里导入」。
        val unlisted = countUnlisted(root, entries)

        if (entries.isEmpty()) {
          return@runCatching Plan(unlistedFiles = unlisted)
        }

        val existing = library.all().mapTo(HashSet()) { it.id }
        // 目录查询是跨进程调用，同一个子目录只查一次
        val dirCache = HashMap<String, SaveNode?>()
        var alreadyPresent = 0
        var missingFiles = 0
        val items = ArrayList<Item>()
        entries.forEach { entry ->
          if (entry.id in existing) {
            alreadyPresent++
            return@forEach
          }
          val dir =
            if (entry.dirName.isBlank()) {
              root
            } else {
              dirCache.getOrPut(entry.dirName) {
                root.child(entry.dirName)?.takeIf { it.isDirectory }
              }
            }
          val file = dir?.child(entry.fileName)?.takeIf { it.isFile && it.canRead }
          if (file == null) {
            missingFiles++
            return@forEach
          }
          items += Item(entry, file)
        }
        Plan(
          items = items,
          bytes = items.sumOf { it.file.size },
          alreadyPresent = alreadyPresent,
          missingFiles = missingFiles,
          unlistedFiles = unlisted,
        )
      }.onFailure { Log.w(TAG, "plan failed: ${it.message}") }.getOrDefault(Plan())
    }

  // ──────────────────────────── 未登记文件的探测 ────────────────────────────

  /** 探测预算：只走前两层、最多这么多个文件。清单缺失时也要秒出结果。 */
  private const val UNLISTED_MAX_DEPTH = 3
  private const val UNLISTED_MAX_FILES = 800

  /**
   * 统计目录里存在、但清单没有登记的字幕文件。
   *
   * 这类文件（旧版下载的、用户手工拷进来的）文件名只有「标题.online.格式」，
   * 不含 `serverKey` / `itemId` / `originId` —— **无法反推出索引主键**。
   * 所以这里只统计数量供 UI 提示，不做任何猜测性归属：一次性写错 tsv 会让重装恢复
   * 把 A 片的字幕挂到 B 片上，比「不在已下载列表里」更糟。
   *
   * 阻塞 IO，调用方在 [plan] 的 IO 上下文里执行。
   */
  private fun countUnlisted(
    root: SaveNode,
    entries: List<SubtitleSaveManifest.Entry>,
  ): Int {
    val known = HashSet<String>(entries.size * 2)
    entries.forEach { entry ->
      known += if (entry.dirName.isBlank()) entry.fileName else "${entry.dirName}/${entry.fileName}"
    }
    var unlisted = 0
    var visited = 0

    fun walk(dir: SaveNode, prefix: String, depth: Int) {
      if (depth > UNLISTED_MAX_DEPTH || visited >= UNLISTED_MAX_FILES) return
      val children = runCatching { dir.children() }.getOrNull() ?: return
      children.forEach { child ->
        if (visited >= UNLISTED_MAX_FILES) return@forEach
        val name = child.name
        if (name.isBlank() || name.startsWith(".")) return@forEach
        if (child.isDirectory) {
          walk(child, "$prefix$name/", depth + 1)
          return@forEach
        }
        if (!SubtitleFileTypes.isSubtitle(name)) return@forEach
        visited++
        if ("$prefix$name" !in known) unlisted++
      }
    }

    walk(root, "", 1)
    return unlisted
  }

  /**
   * 执行恢复。传 [items] 时只恢复指定的这些（界面已经算过 [plan]，不必重算一遍）。
   */
  suspend fun restore(
    context: Context,
    saveDirPath: String,
    legacyTreeUri: String,
    library: OnlineSubtitleLibrary,
    items: List<Item>? = null,
  ): Outcome =
    withContext(Dispatchers.IO) {
      val app = context.applicationContext
      val targets = items ?: plan(app, saveDirPath, legacyTreeUri, library).items
      if (targets.isEmpty()) return@withContext Outcome()
      var restored = 0
      var failed = 0
      var bytes = 0L
      targets.forEach { item ->
        val written = runCatching { restoreOne(library, item) }.getOrDefault(0L)
        if (written > 0L) {
          restored++
          bytes += written
        } else {
          failed++
        }
      }
      Log.i(TAG, "restore done: ok=$restored failed=$failed bytes=$bytes")
      Outcome(restored = restored, failed = failed, bytes = bytes)
    }

  /**
   * 单条恢复：读用户目录字节 → 归一 UTF-8 → 写入私有库媒体目录 → 重建索引记录。
   *
   * @return 写入的字节数；任何一步失败返回 0
   */
  private fun restoreOne(
    library: OnlineSubtitleLibrary,
    item: Item,
  ): Long {
    val entry = item.entry
    val source = OnlineSubtitleSource.fromKey(entry.sourceKey) ?: return 0L

    val raw = item.file.readBytes() ?: return 0L
    if (raw.isEmpty()) return 0L
    // 保存目录里的副本可能是旧版本写的（当时还没有统一归一），这里再过一次
    val payload = SubtitleEncoding.normalize(raw).utf8

    // 文件名沿用清单里的那个：用户在文件管理器里看到的与私有库里的一一对应，
    // 排查「哪条字幕对应哪个文件」时不必再换算。
    val name = OnlineSubtitleLibrary.safeFileName(entry.fileName)
    val dir = library.mediaDir(entry.serverKey, entry.itemId)
    if (!dir.isDirectory && !dir.mkdirs()) return 0L
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
      Log.w(TAG, "write failed: ${entry.fileName}: ${t.message}")
      return 0L
    }

    library.upsert(
      DownloadedSubtitle(
        id = entry.id,
        serverKey = entry.serverKey,
        itemId = entry.itemId,
        mediaTitle = entry.mediaTitle,
        source = source,
        title = entry.title,
        fileName = library.relativePathOf(target),
        format = entry.format.ifBlank { name.substringAfterLast('.', "srt") },
        language = entry.language,
        sizeBytes = target.length(),
        downloadedAt = entry.downloadedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        offsetMs = entry.offsetMs,
        preferred = entry.preferred,
        originId = entry.originId,
        originUrl = entry.originUrl,
        // 这份文件本来就是从保存目录读回来的，标记置 true
        archived = true,
      ),
    )
    return target.length()
  }
}
