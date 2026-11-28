package app.marlboroadvance.mpvex.ui.player.subtitle

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import app.marlboroadvance.mpvex.utils.media.SubtitleFileTypes
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 「手动外挂字幕」的**公共归档** —— 重装应用后仍能找回的那一份。
 *
 * ## 为什么需要它
 *
 * [SubtitleBindings] 把「片名 → 字幕 Uri」记在 SharedPreferences 里，进程被杀、
 * 设备重启都还在；但**重装应用会清空应用数据**，而 `content://` 的持久读权限
 * 也会随卸载一起失效。于是用户重装后再看同一部片，字幕绑定就没了。
 *
 * 这里在用户**手动**添加字幕时，把字节复制到公共
 * `Download/CineIsle/Subtitles/`（Android 10+ 走 MediaStore，无需存储权限）；
 * 文件名由归一化片名 + 短哈希决定。重装后按同一个片名重新计算文件名即可找回，
 * 不依赖任何应用私有数据。
 *
 * ## 边界
 *
 * · 只归档**单个文本字幕文件**；VobSub 的 `.idx` + `.sub` 是成对资源，
 *   只复制其中一个必然播不了，因此不归档（原绑定仍在，重装前可用）。
 * · 不写到应用私有目录 —— 那里卸载即删，起不到任何作用。
 * · 同一个视频只保留一份：新内容先写到临时文件，**完整落盘后**才替换旧文件、
 *   清理旧扩展名。中途失败 / 进程被杀时，旧归档仍然完整可读。
 * · 日志不打印字幕内容与鉴权信息。
 */
object ExternalSubtitleArchive {
  private const val TAG = "SubtitleArchive"

  /** 公共目录：`Download/CineIsle/Subtitles` */
  private const val SUBDIR = "CineIsle/Subtitles"

  /** 单条字幕上限；超过基本可以断定选错了文件。 */
  private const val MAX_BYTES = 32L * 1024 * 1024

  /**
   * 已归档文件名缓存（只存 DISPLAY_NAME，不存内容）。
   *
   * `hasKnown()` 需要在主线程 / 自动选择逻辑里快速回答「这部片子有没有归档字幕」，
   * 不能每次都查 MediaStore。真正的 URI 仍由 [find] 在 IO 线程查询。
   */
  @Volatile private var cachedNames: Set<String>? = null

  private val lock = Any()

  /** 应用启动 / ViewModel 创建时预热，IO 线程调用。 */
  fun preload(context: Context) {
    runCatching { listNames(context.applicationContext) }
      .onSuccess { names -> synchronized(lock) { cachedNames = names } }
      .onFailure { Log.w(TAG, "preload failed: ${it.message}") }
  }

  /** 缓存是否已经加载过（没加载时调用方应允许一次实际查询）。 */
  fun isLoaded(): Boolean = cachedNames != null

  /**
   * 热路径判断：这部片名是否已有归档字幕。
   *
   * 只读内存缓存；缓存未预热时返回 false，调用方（[find]）仍会真正查一次。
   */
  fun hasKnown(mediaTitle: String): Boolean {
    if (mediaTitle.isBlank()) return false
    val base = fileBase(mediaTitle)
    return cachedNames.orEmpty().any { belongsToArchive(it, base) }
  }

  /**
   * 把用户手动选择的外挂字幕复制到公共归档。
   *
   * **必须在 IO 线程调用**。返回归档后的可播放 Uri；失败返回 null（只记日志，
   * 不影响本次已经挂上的字幕）。
   */
  fun archive(context: Context, mediaTitle: String, source: Uri): Uri? {
    if (mediaTitle.isBlank()) return null
    val app = context.applicationContext
    val sourceName = displayName(app, source) ?: source.lastPathSegment ?: "subtitle.srt"
    val ext = sourceName.substringAfterLast('.', "srt").lowercase()
    if (ext !in SubtitleFileTypes.EXTENSIONS) {
      Log.w(TAG, "skip archive: unsupported subtitle extension '$ext'")
      return null
    }
    // VobSub 需要 .idx + .sub 成对；单独复制一个文件没有意义。
    if (ext == "idx" || ext == "sub") {
      Log.i(TAG, "skip archive: VobSub pair cannot be archived as a single file")
      return null
    }

    val bytes = runCatching { readBytes(app, source) }.getOrElse { t ->
      Log.w(TAG, "read source subtitle failed: ${t.message}")
      return null
    }
    if (bytes.isEmpty()) {
      Log.w(TAG, "skip archive: empty subtitle")
      return null
    }
    if (bytes.size > MAX_BYTES) {
      Log.w(TAG, "skip archive: subtitle too large (${bytes.size} bytes)")
      return null
    }

    val base = fileBase(mediaTitle)
    val name = "$base.$ext"
    // 有「所有文件访问」时优先直接写公共目录：文件管理器里可见，重装后也不依赖
    // MediaStore 的归属记录。没有该权限才走 MediaStore（Android 10+ 免权限写自己的文件）。
    val uri = if (canUseDirectPublicDir(app)) {
      archiveToPublicDir(app, name, bytes)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      archiveViaMediaStore(app, name, bytes)
    } else {
      archiveToPublicDir(app, name, bytes)
    }
    if (uri != null) {
      synchronized(lock) {
        cachedNames =
          (cachedNames.orEmpty().filterNot { belongsToArchive(it, base) } + name).toSet()
      }
      Log.i(TAG, "archived manual subtitle: ${bytes.size} bytes")
    }
    return uri
  }

  /**
   * 找这部片名已归档的字幕。**必须在 IO 线程调用**。
   *
   * 返回最新的那一份；没有则 null。找不到不代表用户从未加过 —— 可能被用户在
   * 文件管理器里删掉了，此时只能让用户重新选择。
   */
  fun find(
    context: Context,
    mediaTitle: String,
    isIgnored: (Uri) -> Boolean = { false },
  ): Uri? {
    if (mediaTitle.isBlank()) return null
    val app = context.applicationContext
    val base = fileBase(mediaTitle)
    // 直接目录优先：有「所有文件访问」（本项目的文件浏览器依赖它）时，重装后
    // MediaStore 的归属记录已经没了，但文件本身还在，直接列目录最可靠。
    findInPublicDir(app, base, isIgnored)?.let { return it }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      findViaMediaStore(app, base, isIgnored)
    } else {
      null
    }
  }

  // ──────────────────────────── 命名 ────────────────────────────

  /**
   * 文件名基座：`归一化片名-片名哈希前 8 位`。
   *
   * 片名本身可能含 `/ \ : * ? " < > |`，在公共目录里非法；清洗后截断。
   * 哈希取自**未截断的完整归一化片名**，所以截断不会造成不同视频互相命中。
   */
  private fun fileBase(mediaTitle: String): String {
    val key = SubtitleBindings.keyOf(mediaTitle)
    val safe =
      key.lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
        .trim('-')
        .take(48)
        .ifBlank { "subtitle" }
    val hash =
      MessageDigest.getInstance("SHA-1")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(8)
    return "$safe-$hash"
  }

  /**
   * 这个名字是不是「这部片的归档」。
   *
   * 正常名是 `base.srt`；MediaStore 遇到重名会退化成 `base (1).srt`，崩溃恢复时
   * 也可能是 `base.<token>.tmp.srt` —— 这些都要认，否则重装后会找不到。
   * 扩展名必须在字幕白名单里：旧的 `base.srt.part` 半成品不能被当成可用字幕。
   */
  private fun belongsToArchive(fileName: String, base: String): Boolean {
    if (!fileName.startsWith("$base.") && !fileName.startsWith("$base (")) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in SubtitleFileTypes.EXTENSIONS
  }

  // ──────────────────────────── Android 10+ ────────────────────────────

  private fun relativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/"

  private fun archiveViaMediaStore(context: Context, name: String, bytes: ByteArray): Uri? =
    runCatching {
      val resolver = context.contentResolver
      val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      val base = name.substringBeforeLast('.')
      val ext = name.substringAfterLast('.')
      val token = UUID.randomUUID().toString().replace("-", "").take(8)
      // 临时名也以 base 开头：即使改名失败 / 进程被杀，find 仍能命中这份
      // 已经完整写入的字幕。扩展名必须留在最后，播放器才认得。
      val tempName = "$base.$token.tmp.$ext"
      val values =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, tempName)
          put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
          put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      val uri = resolver.insert(collection, values) ?: return null
      var published = false
      try {
        resolver.openOutputStream(uri)?.use { out ->
          out.write(bytes)
          out.flush()
        } ?: error("无法打开字幕归档临时文件")
        val updated =
          resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
          )
        if (updated <= 0) error("发布字幕归档失败")
        published = true
        // 到这里新文件已经完整可见。旧归档清理失败也不会影响新文件；
        // 清理完再尝试改成人类可读的最终文件名。
        deleteViaMediaStore(context, base, keepId = ContentUris.parseId(uri))
        runCatching {
          resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
            null,
            null,
          )
        }.onFailure { Log.w(TAG, "rename archive failed: ${it.message}") }
        uri
      } catch (t: Throwable) {
        // 只删「还没发布」的占位行；发布后即使改名失败，新文件也是可用的。
        if (!published) runCatching { resolver.delete(uri, null, null) }
        throw t
      }
    }.getOrElse { t ->
      Log.w(TAG, "MediaStore archive failed: ${t.message}")
      null
    }

  private fun findViaMediaStore(
    context: Context,
    base: String,
    isIgnored: (Uri) -> Boolean,
  ): Uri? =
    runCatching {
      val resolver = context.contentResolver
      val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
        arrayOf(relativePath(), "$base%"),
        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
      )?.use { cursor ->
        var found: Uri? = null
        while (cursor.moveToNext() && found == null) {
          val displayName = cursor.getString(1) ?: continue
          if (belongsToArchive(displayName, base)) {
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
            if (!isIgnored(uri)) found = uri
          }
        }
        found
      }
    }.getOrNull()

  private fun deleteViaMediaStore(context: Context, base: String, keepId: Long? = null) {
    runCatching {
      val resolver = context.contentResolver
      val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      val ids = mutableListOf<Long>()
      resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
        arrayOf(relativePath(), "$base%"),
        null,
      )?.use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getLong(0)
          val displayName = cursor.getString(1) ?: continue
          if (id != keepId && belongsToArchive(displayName, base)) ids += id
        }
      }
      // 先收集再删：边遍历 cursor 边 delete 会让部分 provider 的游标失效。
      ids.forEach { id ->
        resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
      }
    }.onFailure { Log.w(TAG, "delete old archive failed: ${it.message}") }
  }

  // ──────────────────────────── Android 9 及以下 ────────────────────────────

  private fun publicDir(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)

  private fun archiveToPublicDir(context: Context, name: String, bytes: ByteArray): Uri? {
    return runCatching {
      val dir = publicDir()
      if (!dir.exists() && !dir.mkdirs()) return null
      val base = name.substringBeforeLast('.')
      val target = File(dir, name)
      // 清掉六小时前遗留的临时文件；刚写入的那份不能被误删。
      val staleBefore = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
      dir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith(".$base-") && file.name.endsWith(".tmp") &&
          file.lastModified() < staleBefore
        ) {
          file.delete()
        }
      }
      // 先写隐藏的临时文件（以 `.` 开头，find 不会命中半成品）。
      val tmp = File.createTempFile(".$base-", ".tmp", dir)
      try {
        FileOutputStream(tmp).use { out ->
          out.write(bytes)
          out.flush()
        }
        // 新内容已完整落盘，之后才动旧归档。同目录 rename 会原子替换同名目标。
        if (!tmp.renameTo(target)) {
          tmp.copyTo(target, overwrite = true)
        }
        // 目标已就位，再清理其他旧扩展名（SRT → ASS 时）。
        dir.listFiles()?.forEach { file ->
          if (file.isFile && file != target && belongsToArchive(file.name, base)) {
            file.delete()
          }
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        Uri.fromFile(target)
      } finally {
        if (tmp.exists()) tmp.delete()
      }
    }.getOrElse { t ->
      Log.w(TAG, "legacy public archive failed: ${t.message}")
      null
    }
  }

  private fun findInPublicDir(context: Context, base: String, isIgnored: (Uri) -> Boolean): Uri? {
    val dir = publicDir()
    return dir.listFiles()
      ?.filter { it.isFile && belongsToArchive(it.name, base) }
      ?.sortedByDescending { it.lastModified() }
      ?.mapNotNull { file -> Uri.fromFile(file).takeUnless(isIgnored) }
      ?.firstOrNull()
  }

  // ──────────────────────────── 通用 ────────────────────────────

  /**
   * 能不能直接读写公共 Downloads 目录。
   *
   * Android 11+ 看「所有文件访问」；Android 9 及以下看 WRITE_EXTERNAL_STORAGE；
   * Android 10 只能走 MediaStore。
   */
  private fun canUseDirectPublicDir(context: Context): Boolean =
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
      Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
          PackageManager.PERMISSION_GRANTED
      else -> false
    }

  private fun listNames(context: Context): Set<String> {
    if (canUseDirectPublicDir(context)) {
      publicDir().listFiles()
        ?.filter { it.isFile }
        ?.mapNotNull { it.name }
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it.toSet() }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      return resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
        arrayOf(relativePath()),
        null,
      )?.use { cursor ->
        buildSet {
          while (cursor.moveToNext()) {
            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
          }
        }
      }.orEmpty()
    }
    return publicDir().listFiles()
      ?.filter { it.isFile }
      ?.mapNotNull { it.name }
      ?.toSet()
      .orEmpty()
  }

  private fun displayName(context: Context, uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
      else -> uri.lastPathSegment
    }

  private fun readBytes(context: Context, uri: Uri): ByteArray =
    when (uri.scheme) {
      "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法打开字幕文件")
      "file" -> File(uri.path ?: error("字幕路径无效")).readBytes()
      else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法打开字幕文件")
    }

  private fun mimeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
      "ass", "ssa" -> "text/x-ssa"
      "vtt" -> "text/vtt"
      "sup" -> "application/octet-stream"
      else -> "application/x-subrip"
    }
}
