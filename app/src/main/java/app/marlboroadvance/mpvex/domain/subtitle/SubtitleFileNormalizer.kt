package app.marlboroadvance.mpvex.domain.subtitle

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把本地字幕文件变成「mpv 不用猜编码」的形态。
 *
 * ## 两种落地方式
 *
 * · [forPlayback] —— 不改原文件，在应用缓存里放一份 UTF-8 副本，把副本路径交给
 *   `sub-add`。适用于**任何来源**的本地字幕（用户从 SAF 选的、同目录自动加载的、
 *   别人 app 共享的），我们没有资格去改它们的字节。
 * · [normalizeLibraryFile] —— 就地重写。只用于**应用私有目录**里的字幕
 *   （`filesDir/online_subtitles/`），那本来就是我们自己下载的文件。原文件先备份成
 *   `<name>.bak`，再原子替换 —— 用户下载过的字幕从此在磁盘上就是正确的 UTF-8，
 *   预览、播放、导出三处读到的字节完全一致。
 *
 * ## 为什么缓存键要带 size + mtime
 *
 * 同一个路径重新下载之后内容变了，键必须跟着变，否则会继续命中上一版的副本 ——
 * 表现就是「重新下载了一条，画面还是旧的那条」。路径哈希单独用不够。
 */
object SubtitleFileNormalizer {
  private const val TAG = "SubtitleFileNormalizer"

  /** 缓存子目录（在 `cacheDir` 下，系统可在空间不足时自行清理）。 */
  private const val CACHE_DIR = "subtitle_norm"

  /** 缓存里最多留多少份副本。超出按最久未修改的删，避免长期播放攒出几百份。 */
  private const val CACHE_MAX_FILES = 64

  /** 认这几种扩展名才处理。`sub`/`idx` 是图像字幕，转文本没有意义。 */
  private val TEXT_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "txt")

  /**
   * 返回一个「内容一定是 UTF-8」的路径，供 `sub-add` 使用。
   *
   * 不需要重写时**原样返回** [path]（不产生任何 IO 副作用、不占用缓存）。
   *
   * ⚠️ 会读整份文件，必须在 IO 线程调用。
   */
  fun forPlayback(
    context: Context,
    path: String,
  ): String {
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return path
    if (file.extension.lowercase() !in TEXT_EXTENSIONS) return path

    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return path
    val normalized = runCatching { SubtitleEncoding.normalize(bytes) }.getOrNull() ?: return path
    if (!normalized.changed) return path

    val cached = writeCache(context, file, normalized.utf8) ?: return path
    Log.i(TAG, "normalized ${file.name} (${normalized.decoded.encoding}) -> cache")
    return cached
  }

  /**
   * 就地重写成 UTF-8。只对应用私有目录里的文件开放 —— 见类注释。
   *
   * @return true 表示确实重写了；false 表示原文件本来就不需要动（或无法处理）
   */
  fun normalizeLibraryFile(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    if (file.extension.lowercase() !in TEXT_EXTENSIONS) return false

    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
    val normalized = runCatching { SubtitleEncoding.normalize(bytes) }.getOrNull() ?: return false
    if (!normalized.changed) return false

    val backup = File(file.parentFile, "${file.name}.bak")
    // 已经有备份就不再覆盖：备份要保留的是**用户最初拿到的那一份**，
    // 反复运行不该把它换成上一次的中间结果。
    if (!backup.isFile) runCatching { file.copyTo(backup, overwrite = false) }

    val tmp = File(file.parentFile, ".${file.name}.enc.tmp")
    return runCatching {
      tmp.writeBytes(normalized.utf8)
      if (!tmp.renameTo(file)) {
        // 某些文件系统上 rename 到已存在的目标会失败，退回覆盖写
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
      }
      Log.i(TAG, "rewrote ${file.name} as UTF-8 (was ${normalized.decoded.encoding})")
      true
    }.getOrElse {
      Log.w(TAG, "rewrite failed: ${file.name}: ${it.message}")
      runCatching { tmp.delete() }
      false
    }
  }

  /** 把归一化后的字节写进缓存，返回其绝对路径；失败返回 null。 */
  private fun writeCache(
    context: Context,
    source: File,
    payload: ByteArray,
  ): String? =
    runCatching {
      val dir = File(context.cacheDir, CACHE_DIR)
      if (!dir.isDirectory && !dir.mkdirs()) return null

      // 键里带 size + mtime：重新下载后内容变了，键跟着变，不会命中上一版副本。
      val key = "${source.absolutePath.hashCode()}_${source.length()}_${source.lastModified()}"
      // 保留原扩展名：解析器按扩展名挑 SRT / ASS 语法
      val ext = source.extension.lowercase().ifBlank { "srt" }
      val target = File(dir, "$key.$ext")
      if (target.isFile && target.length() > 0L) return target.absolutePath

      val tmp = File(dir, ".${target.name}.part")
      tmp.writeBytes(payload)
      if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
      }
      prune(dir)
      target.takeIf { it.isFile }?.absolutePath
    }.getOrNull()

  /** 缓存超过上限时按最后修改时间删掉最旧的，只留最可能被复用的那一批。 */
  private fun prune(dir: File) {
    val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return
    if (files.size <= CACHE_MAX_FILES) return
    files
      .sortedBy { it.lastModified() }
      .take(files.size - CACHE_MAX_FILES)
      .forEach { runCatching { it.delete() } }
  }

  /**
   * 把私有目录里的字幕文件批量归一化（应用启动时跑一次）。
   *
   * 覆盖两种布局：
   * · 直接平铺在 [root] 下（Emby 字幕缓存就是这样，文件名是哈希）；
   * · 一层媒体子目录（在线字幕库是 `<serverKey>-<itemId>/<title>.srt`）。
   *
   * 只处理应用私有目录里的文件：那是我们自己下载的，有资格改。用户外部目录里的
   * 文件一律不碰 —— 播放时用 [forPlayback] 的缓存副本解决即可。
   *
   * @return 实际重写的文件数
   */
  fun normalizeTree(root: File): Int {
    if (!root.isDirectory) return 0
    var rewritten = 0
    fun handle(file: File) {
      // 跳过临时文件与备份，否则会把 `.bak` 也当成字幕再转一遍
      if (file.isFile && !file.name.startsWith(".") && !file.name.endsWith(".bak")) {
        if (normalizeLibraryFile(file)) rewritten++
      }
    }
    root.listFiles()?.forEach { child ->
      if (child.isDirectory) child.listFiles()?.forEach(::handle) else handle(child)
    }
    return rewritten
  }
}
