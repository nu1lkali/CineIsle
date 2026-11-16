package app.marlboroadvance.mpvex.ui.player.subtitle.emby

import android.content.Context
import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleFormats
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import java.io.File
import java.security.MessageDigest

/**
 * Emby 字幕的**磁盘缓存**。
 *
 * ## 键
 *
 * `ItemId + MediaSourceId + StreamIndex + Format`（需求七），取 SHA-1 当文件名。
 * 四段缺一不可：
 * · 少了 `MediaSourceId`，同一部片子的 4K 版字幕会被 1080P 版复用（索引大概率对不上）；
 * · 少了 `StreamIndex`，同一源里简繁两条会互相覆盖；
 * · 少了 `Format`，请求 `ass` 与「回退拿到的 `srt`」会互相覆盖，样式悄悄跑掉。
 *
 * ⚠️ 哈希只由上面四段决定，**扩展名另外拼**。这样「本该是 ASS、服务端只给了 SRT」
 * 的那份缓存也仍然能被命中（同一个哈希前缀），不会每播一次就重下一遍。
 *
 * ## 落点
 *
 * `cacheDir/emby_subtitles/` —— **不放**用户配置的字幕目录。两个理由：
 * · 那里会被 `PlayerViewModel.scanLocalSubtitles` 扫成一堆外挂轨，同一部片子
 *   每次开播多几条重复字幕（还会被当成用户手动加的）；
 * · 那是 SAF 目录，写之前要权限、写完还要处理 fd，纯缓存没必要。
 *
 * 缓存目录里的文件是 `file://` 路径，mpv 可直接读。
 *
 * ## 失效
 *
 * · 超过 [TTL_MS] 视为过期，重新下载（Emby 不提供字幕版本号，只能按时间兜底）；
 * · 总量超过 [MAX_TOTAL_BYTES] 或条数超过 [MAX_ENTRIES] 时按最久未访问删除；
 * · 下载失败时主动删掉旧文件，避免一直命中坏缓存。
 *
 * **不缓存任何认证信息**：文件名只有哈希与扩展名，内容就是字幕字节本身。
 */
class EmbySubtitleCache(private val context: Context) {
  private val dir: File
    get() = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

  /** 命中的缓存文件；不存在或已过期返回 null。 */
  fun cached(itemId: String, info: SubtitleInfo): File? {
    val prefix = hashFor(itemId, info)
    val file = runCatching {
      dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$prefix.") }
    }.getOrNull() ?: return null
    val age = System.currentTimeMillis() - file.lastModified()
    if (age > TTL_MS || file.length() <= 0) {
      runCatching { file.delete() }
      return null
    }
    // 更新访问时间：trim 时靠它判断「最久没用的」
    runCatching { file.setLastModified(System.currentTimeMillis()) }
    return file
  }

  /** 写入缓存并返回文件。写失败返回 null（缓存失败不该影响播放）。 */
  fun store(itemId: String, info: SubtitleInfo, bytes: ByteArray, format: String): File? =
    runCatching {
      val prefix = hashFor(itemId, info)
      val ext = EmbySubtitleFormats.fileExtension(format)
      val target = File(dir, "$prefix.$ext")
      // 先写临时文件再改名：中途被杀不会留下半截字幕（半截字幕会解析出诡异时间轴）
      val tmp = File(dir, "$prefix.$ext.part")
      tmp.writeBytes(bytes)
      // 同一条流的其它扩展名（上一次成功用的是别的格式）一并清掉，只留这一份。
      // ⚠️ 必须排除 tmp 自己 —— 循环条件 `startsWith(prefix.)` 会连 `.part` 一起命中，
      // 早先就因此在 rename 前把刚写好的文件删了，缓存永远写不进（每播一次重下一次）。
      dir.listFiles()?.forEach { if (it.isFile && it.name.startsWith("$prefix.") && it != tmp) it.delete() }
      if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
      }
      target.takeIf { it.length() > 0 }
    }.getOrNull()

  /** 让某条缓存失效（下载失败 / 用户要求刷新时调）。 */
  fun invalidate(itemId: String, info: SubtitleInfo) {
    val prefix = hashFor(itemId, info)
    runCatching {
      dir.listFiles()?.forEach { if (it.isFile && it.name.startsWith("$prefix.")) it.delete() }
    }
  }

  /**
   * 按总量 / 条数裁剪。**必须在后台线程调**（要遍历目录、删文件）。
   *
   * @return 删掉的字节数（用于日志）
   */
  fun trim(): Long {
    val files = runCatching { dir.listFiles()?.filter { it.isFile } }.getOrNull().orEmpty()
    if (files.isEmpty()) return 0L
    var total = files.sumOf { it.length() }
    var count = files.size
    if (total <= MAX_TOTAL_BYTES && count <= MAX_ENTRIES) return 0L

    var removed = 0L
    for (file in files.sortedBy { it.lastModified() }) {
      if (total <= MAX_TOTAL_BYTES && count <= MAX_ENTRIES) break
      val size = file.length()
      if (runCatching { file.delete() }.getOrDefault(false)) {
        total -= size
        removed += size
        count--
      }
    }
    return removed
  }

  /** 清空全部缓存（设置里「清理字幕缓存」用）。返回释放的字节数。 */
  fun clearAll(): Long {
    val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
    val bytes = files.sumOf { if (it.isFile) it.length() else 0L }
    runCatching { files.forEach { it.deleteRecursively() } }
    return bytes
  }

  /** 哈希键：`ItemId | MediaSourceId | StreamIndex | Format` */
  private fun hashFor(itemId: String, info: SubtitleInfo): String =
    MessageDigest.getInstance("SHA-1")
      .digest("$itemId|${info.cacheDiscriminator}".toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  companion object {
    private const val DIR_NAME = "emby_subtitles"

    /** 缓存有效期：7 天。Emby 不给字幕版本号，只能按时间兜底重取 */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 总量上限 64 MB —— 文本字幕通常几十 KB，这个量级够放上千条 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 条数上限，防止极小文件把 inode 撑爆 */
    private const val MAX_ENTRIES = 500
  }
}
