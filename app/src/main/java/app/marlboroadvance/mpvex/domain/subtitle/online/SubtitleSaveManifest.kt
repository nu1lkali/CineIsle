package app.marlboroadvance.mpvex.domain.subtitle.online

/**
 * 字幕保存目录里的清单文件（`_cineisle_subtitles.tsv`）。
 *
 * ## 为什么需要它
 *
 * 在线字幕的**权威副本**在应用私有目录，索引（`serverKey + itemId → 文件`）存在偏好里。
 * 卸载重装会把这两样一起清掉，而用户字幕保存目录里的那份副本只有文件名 —— 文件名里
 * 没有 `serverKey` / `itemId`。此时想「从目录恢复」，只能靠片名去猜：
 *
 * · 同名不同片（重制版、合集、同系列不同集）会挂错；
 * · 改名 / 换刮削语言之后连猜都猜不上。
 *
 * 所以在用户目录里放一份清单：**把 `serverKey + itemId + originId → 子目录 + 文件名`
 * 写下来**。重装后重新选一次目录，就能按清单精确还原索引，不需要任何启发式。
 *
 * ## 为什么是一份清单，不是每条字幕旁边一个侧车
 *
 * 侧车会让用户在文件管理器里看到「一条字幕配一个 json」，翻目录时很吵；而且删除字幕时
 * 两侧要同步删，容易留残。单文件清单只在下载时追加/覆盖一行，代价是每次多一次
 * 「读小文件 + 写小文件」—— 下载本身是网络请求，这点 IO 可以忽略。
 *
 * ## 陈旧行不是问题
 *
 * 用户删掉某条字幕时这里不会跟着删（删除路径分散在本地库与实验室两处，逐个改容易漏）。
 * 清单行指向的文件不存在时，恢复流程直接跳过 —— 宁可有冗余行，也不要有「删了索引还在」
 * 这类半更新状态。
 *
 * ⚠️ 文件用 `.tsv` 扩展名：播放侧只认字幕扩展名（srt / ass / vtt …），这份清单不会被
 * 误当成字幕候选扫进来。
 *
 * 全部方法阻塞 IO，调用方需在 `Dispatchers.IO` 上执行。
 */
object SubtitleSaveManifest {
  /** 清单文件名。放保存目录根，与各媒体的子目录同级。 */
  const val FILE_NAME = "_cineisle_subtitles.tsv"

  private const val HEADER = "#cineisle-subtitles-v1"
  private const val MIME = "text/tab-separated-values"
  private const val FIELD = '\t'
  private const val FIELD_COUNT = 15

  /**
   * 一条可恢复记录。
   *
   * @property dirName 媒体子目录名（相对保存目录根；为空表示文件直接放在根下）
   * @property fileName 子目录内的文件名
   */
  data class Entry(
    val serverKey: String,
    val itemId: String,
    val originId: String,
    val mediaTitle: String,
    val title: String,
    val sourceKey: String,
    val dirName: String,
    val fileName: String,
    val format: String,
    val language: String?,
    val sizeBytes: Long,
    val downloadedAt: Long,
    val offsetMs: Long,
    val preferred: Boolean,
    val originUrl: String,
  ) {
    /** 与 `OnlineSubtitleLibrary` 的记录主键同构，用于去重。 */
    val id: String get() = "$serverKey|$itemId|$originId"
  }

  /** 读清单。根为 null、文件不存在、格式不认识都返回空表（不抛）。 */
  fun read(root: SaveNode?): List<Entry> {
    if (root == null) return emptyList()
    val file = root.child(FILE_NAME)?.takeIf { it.isFile } ?: return emptyList()
    return parse(file.readBytes()?.toString(Charsets.UTF_8).orEmpty())
  }

  /**
   * 写入 / 覆盖一条记录（按 [Entry.id] 去重）。
   *
   * @return true 表示清单已落盘
   */
  fun upsert(root: SaveNode?, entry: Entry): Boolean {
    if (root == null) return false
    val current = read(root).filterNot { it.id == entry.id }
    return write(root, current + entry)
  }

  /** 整份重写（恢复流程不需要，留给将来的「重建清单」）。 */
  fun replace(root: SaveNode?, entries: List<Entry>): Boolean {
    if (root == null) return false
    return write(root, entries)
  }

  private fun write(root: SaveNode, entries: List<Entry>): Boolean {
    val text =
      buildString {
        append(HEADER).append('\n')
        entries.forEach { append(it.serialize()).append('\n') }
      }
    return root.write(FILE_NAME, text.toByteArray(Charsets.UTF_8), MIME) != null
  }

  private fun Entry.serialize(): String =
    listOf(
        serverKey,
        itemId,
        originId,
        mediaTitle,
        title,
        sourceKey,
        dirName,
        fileName,
        format,
        language.orEmpty(),
        sizeBytes.toString(),
        downloadedAt.toString(),
        offsetMs.toString(),
        preferred.toString(),
        originUrl,
      )
      .joinToString(FIELD.toString()) { sanitize(it) }

  private fun parse(text: String): List<Entry> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<Entry>()
    text.lineSequence().forEach { line ->
      if (line.isBlank() || line.startsWith("#")) return@forEach
      val parts = line.split(FIELD)
      if (parts.size < FIELD_COUNT) return@forEach
      val fileName = parts[7]
      // 没有文件名的行无法恢复，直接丢
      if (fileName.isBlank()) return@forEach
      out +=
        Entry(
          serverKey = parts[0],
          itemId = parts[1],
          originId = parts[2],
          mediaTitle = parts[3],
          title = parts[4],
          sourceKey = parts[5],
          dirName = parts[6],
          fileName = fileName,
          format = parts[8],
          language = parts[9].ifBlank { null },
          sizeBytes = parts[10].toLongOrNull() ?: 0L,
          downloadedAt = parts[11].toLongOrNull() ?: 0L,
          offsetMs = parts[12].toLongOrNull() ?: 0L,
          preferred = parts[13].toBooleanStrictOrNull() ?: false,
          originUrl = parts[14],
        )
    }
    return out
  }

  /** 分隔符与换行会毁掉整行，写之前替换成空格。 */
  private fun sanitize(value: String): String =
    value.replace(FIELD, ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
