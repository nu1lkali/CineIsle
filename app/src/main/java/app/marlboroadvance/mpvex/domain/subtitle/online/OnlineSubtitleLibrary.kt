package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import java.io.File

/**
 * 一条「已下载到本地」的在线字幕。
 *
 * ## 为什么键是 `serverKey + itemId`，而不是片名
 *
 * 片名会变（改名 / 刮削修正 / 换语言），而 Emby 的 `ItemId` 在一个服务器内稳定。
 * 同一个 `ItemId` 在两个服务器上则可能是完全不同的片子，所以必须带服务器标识
 * （需求 10：优先 `Emby Server 标识 + ItemId`）。
 *
 * @property id 记录主键：`serverKey|itemId|originId`，同一条字幕重复下载只会覆盖这一行
 * @property fileName 相对本地库根目录的路径（含媒体子目录），**不存绝对路径** ——
 *   应用数据目录在升级 / 迁移后可能变，存绝对路径会全部失联
 * @property offsetMs 时间轴偏移（毫秒），正数 = 字幕整体延后
 * @property preferred 是否为该媒体的「优先使用」字幕
 * @property originUrl 原始下载地址。留着它是为了让「重新下载」不需要再搜一次
 *   （站点结构变了 / 关键词变了也能按原地址重下一次）。旧记录没有这个字段，为空即不可重下。
 * @property archived 是否已另存一份到「设置 → 字幕」里的字幕保存目录
 */
data class DownloadedSubtitle(
  val id: String,
  val serverKey: String,
  val itemId: String,
  val mediaTitle: String,
  val source: OnlineSubtitleSource,
  val title: String,
  val fileName: String,
  val format: String,
  val language: String?,
  val sizeBytes: Long,
  val downloadedAt: Long,
  val offsetMs: Long = 0L,
  val preferred: Boolean = false,
  val originId: String,
  val originUrl: String = "",
  val archived: Boolean = false,
)

/**
 * 本地在线字幕库（三个入口共用）。
 *
 * ## 存哪
 *
 * · 字节：`filesDir/online_subtitles/<serverKey>-<itemId>/<title>.<ext>`
 *   —— 应用私有目录，卸载即清，不需要任何存储权限，也不会污染用户的字幕目录；
 * · 索引：沿用既有偏好存储的一行一条记录（与 [app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings]
 *   同一套思路）。只为几条关联信息引 Room 要付一次 schema 迁移风险，不划算。
 *
 * ## 为什么不做「全局唯一文件 + 引用计数」
 *
 * 路径里带了媒体目录，同一条字幕给两部片子下载会各存一份。代价是几十 KB 的重复，
 * 换来的是**删除时不会误删别人还在用的文件**：只要删的是「自己目录下的那份」，
 * 删除操作天然是安全的。仍保留了引用计数检查，防止用户用同一个 ItemId 多次关联
 * 到同一路径的极端情况。
 */
class OnlineSubtitleLibrary(
  context: Context,
  private val preferences: SubtitlesPreferences,
) {
  private val appContext = context.applicationContext
  private val lock = Any()

  /** 本地库根目录。目录不存在时**不**主动创建（读操作不该有副作用）。 */
  private val root: File
    get() = File(appContext.filesDir, ROOT_DIR)

  // ──────────────────────────── 读 ────────────────────────────

  /** 全部记录，最新下载在前。顺带清掉「文件已经不在」的孤儿记录。 */
  fun all(): List<DownloadedSubtitle> =
    synchronized(lock) {
      val records = read()
      val alive = records.filter { fileOf(it).isFile }
      if (alive.size != records.size) {
        Log.i(TAG, "prune ${records.size - alive.size} missing subtitle file(s)")
        write(alive)
      }
      alive.sortedByDescending { it.downloadedAt }
    }

  /** 某部片子已下载的字幕。 */
  fun forMedia(serverKey: String, itemId: String): List<DownloadedSubtitle> =
    all().filter { it.serverKey == serverKey && it.itemId == itemId }

  /** 该媒体「优先使用」的那条（没有则 null）。 */
  fun preferredFor(serverKey: String, itemId: String): DownloadedSubtitle? =
    forMedia(serverKey, itemId).firstOrNull { it.preferred }

  /** 同一条字幕是否已经下过（跨入口去重的依据）。 */
  fun find(serverKey: String, itemId: String, originId: String): DownloadedSubtitle? =
    all().firstOrNull {
      it.serverKey == serverKey && it.itemId == itemId && it.originId == originId
    }

  fun fileOf(record: DownloadedSubtitle): File = File(root, record.fileName)

  /**
   * 本地库占用情况。
   *
   * [orphanCount] / [orphanBytes] 是**反向孤儿**：磁盘上存在、但没有任何索引记录指向的字幕文件。
   * [all] 只清「索引有、文件没了」，反过来的这些（索引被清空过、下载中断留下的
   * `.part` 之外的残留、旧版本遗留目录）会一直占着空间且永远显示不出来 —— 这是
   * 「一键清理」真正要处理的那一类。
   */
  data class Usage(
    val recordCount: Int = 0,
    val totalBytes: Long = 0L,
    val orphanCount: Int = 0,
    val orphanBytes: Long = 0L,
  )

  fun usage(): Usage =
    synchronized(lock) {
      val records = read()
      val referenced = HashSet<String>(records.size * 2)
      var total = 0L
      records.forEach { record ->
        val file = fileOf(record)
        referenced += file.absolutePath
        if (file.isFile) total += file.length()
      }
      var orphanCount = 0
      var orphanBytes = 0L
      candidateFiles().forEach { file ->
        if (file.absolutePath !in referenced) {
          orphanCount++
          orphanBytes += file.length()
        }
      }
      Usage(
        recordCount = records.size,
        totalBytes = total,
        orphanCount = orphanCount,
        orphanBytes = orphanBytes,
      )
    }

  /**
   * 清掉未被任何记录引用的字幕文件（连同因此变空的媒体目录）。
   *
   * 只删**字幕扩展名**的文件：这里扫的是整个本地库根目录，万一以后有别的东西放进来，
   * `*.db`、缩略图缓存之类不该被当成孤儿顺手删掉。
   */
  fun purgeOrphans(): Usage {
    synchronized(lock) {
      val records = read()
      val referenced = records.map { fileOf(it).absolutePath }.toHashSet()
      candidateFiles().forEach { file ->
        if (file.absolutePath !in referenced) {
          runCatching { if (!file.delete()) Log.w(TAG, "purge failed: ${file.name}") }
        }
      }
      root.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) runCatching { dir.delete() }
      }
      return usage()
    }
  }

  /** 本地库里所有「看起来是字幕」的文件（跳过 `.part` 之类的临时文件）。 */
  private fun candidateFiles(): List<File> {
    val dir = root
    if (!dir.isDirectory) return emptyList()
    val out = ArrayList<File>()
    dir.listFiles()?.forEach { child ->
      if (child.isDirectory) {
        child.listFiles()?.forEach { file ->
          if (file.isFile && !file.name.startsWith(".") && isSubtitleFileName(file.name)) out += file
        }
      }
    }
    return out
  }

  private fun isSubtitleFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS

  /** 文件 → 相对库根目录的路径（写记录用）。 */
  fun relativePathOf(file: File): String = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)

  /** 该媒体在本地库里的目录（下载目标）。 */
  fun mediaDir(serverKey: String, itemId: String): File =
    File(root, safeSegment("$serverKey-$itemId"))

  // ──────────────────────────── 写 ────────────────────────────

  /**
   * 落一条记录。同 [DownloadedSubtitle.id] 已存在时覆盖（并保留原来的偏移量 /
   * 优先标记 —— 重新下载不该把用户调好的时间轴清掉）。
   */
  fun upsert(record: DownloadedSubtitle): DownloadedSubtitle =
    synchronized(lock) {
      val records = read().toMutableList()
      val index = records.indexOfFirst { it.id == record.id }
      val merged =
        if (index >= 0) {
          val old = records[index]
          record.copy(
            offsetMs = if (record.offsetMs != 0L) record.offsetMs else old.offsetMs,
            preferred = record.preferred || old.preferred,
          )
        } else {
          record
        }
      if (index >= 0) records[index] = merged else records += merged
      // 一部片子只允许一条「优先使用」
      val normalized =
        if (merged.preferred) {
          records.map { if (it.id != merged.id && it.itemId == merged.itemId && it.serverKey == merged.serverKey) it.copy(preferred = false) else it }
        } else {
          records
        }
      write(normalized)
      merged
    }

  fun setOffset(id: String, offsetMs: Long) {
    synchronized(lock) {
      val records = read()
      if (records.none { it.id == id }) return
      write(records.map { if (it.id == id) it.copy(offsetMs = offsetMs) else it })
    }
  }

  /** 设为该媒体的优先字幕；传 null 表示取消全部优先标记。 */
  fun setPreferred(serverKey: String, itemId: String, id: String?) {
    synchronized(lock) {
      val records = read()
      write(
        records.map {
          when {
            it.serverKey != serverKey || it.itemId != itemId -> it
            else -> it.copy(preferred = it.id == id)
          }
        },
      )
    }
  }

  /**
   * 删除一条记录。
   *
   * 只有**没有其它记录**指向同一个文件时才真删文件 —— 需求 9 明确要求
   * 「同一字幕被多个媒体关联时不能误删」。
   */
  fun delete(id: String) {
    synchronized(lock) {
      val records = read()
      val target = records.firstOrNull { it.id == id } ?: return
      val remaining = records.filterNot { it.id == id }
      write(remaining)
      val file = fileOf(target)
      val stillUsed = remaining.any { it.fileName == target.fileName }
      if (!stillUsed) {
        runCatching { if (file.exists() && !file.delete()) Log.w(TAG, "delete failed: ${file.name}") }
        // 媒体目录空了就顺手删掉，避免本地库里留下成片的空目录
        runCatching { file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
      }
    }
  }

  // ──────────────────────── 序列化 ────────────────────────

  private fun read(): List<DownloadedSubtitle> {
    val raw = runCatching { preferences.onlineSubtitleLibrary.get() }.getOrDefault("")
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<DownloadedSubtitle>()
    raw.lineSequence().forEach { line ->
      if (line.isBlank()) return@forEach
      val parts = line.split(FIELD)
      // 字段数变化（未来加字段）时按前缀解析：多的忽略、少的用默认值，
      // 这样旧版本读新数据也不会整库失效。
      if (parts.size < 12) return@forEach
      val source = OnlineSubtitleSource.fromKey(parts[4]) ?: return@forEach
      out +=
        DownloadedSubtitle(
          id = parts[0],
          serverKey = parts[1],
          itemId = parts[2],
          mediaTitle = parts[3],
          source = source,
          title = parts[5],
          fileName = parts[6],
          format = parts[7],
          language = parts[8].ifBlank { null },
          sizeBytes = parts[9].toLongOrNull() ?: 0L,
          downloadedAt = parts[10].toLongOrNull() ?: 0L,
          offsetMs = parts[11].toLongOrNull() ?: 0L,
          preferred = parts.getOrNull(12)?.toBooleanStrictOrNull() ?: false,
          originId = parts.getOrNull(13).orEmpty(),
          originUrl = parts.getOrNull(14).orEmpty(),
          archived = parts.getOrNull(15)?.toBooleanStrictOrNull() ?: false,
        )
    }
    return out
  }

  private fun write(records: List<DownloadedSubtitle>) {
    val text =
      buildString {
        records.forEach { record ->
          append(sanitize(record.id)).append(FIELD)
          append(sanitize(record.serverKey)).append(FIELD)
          append(sanitize(record.itemId)).append(FIELD)
          append(sanitize(record.mediaTitle)).append(FIELD)
          append(record.source.key).append(FIELD)
          append(sanitize(record.title)).append(FIELD)
          append(sanitize(record.fileName)).append(FIELD)
          append(sanitize(record.format)).append(FIELD)
          append(sanitize(record.language.orEmpty())).append(FIELD)
          append(record.sizeBytes).append(FIELD)
          append(record.downloadedAt).append(FIELD)
          append(record.offsetMs).append(FIELD)
          append(record.preferred).append(FIELD)
          append(sanitize(record.originId)).append(FIELD)
          append(sanitize(record.originUrl)).append(FIELD)
          append(record.archived)
          append('\n')
        }
      }
    runCatching { preferences.onlineSubtitleLibrary.set(text) }
      .onFailure { Log.w(TAG, "save library failed: ${it.message}") }
  }

  /** 分隔符与换行会毁掉整行记录，写之前替换成空格。 */
  private fun sanitize(value: String): String =
    value.replace(FIELD, ' ').replace('\n', ' ').replace('\r', ' ').trim()

  companion object {
    private const val TAG = "OnlineSubtitleLibrary"
    private const val ROOT_DIR = "online_subtitles"
    private const val FIELD = '\t'

    /** 清理孤儿文件时认的扩展名（`sub`/`idx` 是 VobSub 成对出现的两个文件）。 */
    private val SUBTITLE_EXTENSIONS =
      setOf("srt", "ass", "ssa", "vtt", "txt", "sup", "sub", "idx")

    /** 目录名 / 文件名用：只留安全字符，避免 `:` `/` 之类在文件系统上报错。 */
    fun safeSegment(raw: String): String {
      val cleaned = raw.replace(Regex("[^A-Za-z0-9._\\-]"), "_").trim('_', '.')
      return cleaned.take(60).ifBlank { "media" }
    }

    /** 落盘用的文件名主干：保留中文，去掉文件系统非法字符。 */
    fun safeFileName(raw: String): String {
      val cleaned =
        raw
          .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
          .trim(' ', '.', '_')
          .take(80)
      return cleaned.ifBlank { "subtitle" }
    }

    /**
     * 下载落盘的文件名主干（不含扩展名）。
     *
     * 带来源与地址短哈希：同一站点下同名但不同内容的候选（不同版本 / 不同发布组）
     * 不能互相覆盖 —— 覆盖后两条记录会指向同一份内容，用户根本看不出差别。
     */
    fun downloadBaseName(item: OnlineSubtitle): String =
      safeFileName("${item.title}[${item.source.label}]-${item.id.substringAfter(':').take(8)}")
  }
}
