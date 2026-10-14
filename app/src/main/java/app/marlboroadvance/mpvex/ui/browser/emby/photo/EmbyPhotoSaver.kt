package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 照片保存器：「保存原图」与「另存为 JPEG」。
 *
 * 两条路都先把原文件从 `/Items/{Id}/Download` 拉到内存（原图可能是 10MB+ 的
 * HEIC/JPEG，所以走 [EmbyHttp.streaming] 长超时档，不走图片档）：
 * - **保存原图**：字节原样落盘，保留 EXIF 与原始格式 —— 用户要的就是相机拍出来
 *   那个文件；
 * - **另存为 JPEG**：已是 JPEG 的直接写（省一次解码再压缩，有损转码只会掉画质）；
 *   HEIC 等由 [EmbyImageLoader.transcodeToJpeg] 本地转码，本地解不了时调用方
 *   应改走服务端 `format=jpg` 的地址（见浏览器菜单的兜底顺序）。
 *
 * 落盘路径与截图一致（`Pictures/CineIsle`）：Android 10+ 走 MediaStore
 * （IS_PENDING 占位 → 写完再发布，无需存储权限），9 及以下写外部存储 + 媒体扫描。
 * 用户在「设置 → Emby 媒体库 → 照片保存位置」里选过目录时改走 SAF 树（见 [writeToTree]）。
 */
object EmbyPhotoSaver {
  private const val TAG = "EmbyPhotoSaver"

  /** 相册下的子目录名（与播放截图同一个产品目录） */
  private const val ALBUM = "CineIsle"

  sealed interface Result {
    object Success : Result
    data class Failure(val reason: String) : Result
  }

  /** 保存原图（相机拍出来的那个文件，含 EXIF）。 */
  suspend fun saveOriginal(
    context: Context,
    server: EmbyServer,
    item: EmbyItem,
    /** 自定义保存目录（SAF 树 URI 字符串，老版本留下的）；空 = 看 [saveDirPath] */
    saveDirUri: String? = null,
    /** 自定义保存目录（**绝对路径**，自研选择器的产物）；空 = 系统相册 Pictures/CineIsle */
    saveDirPath: String? = null,
  ): Result =
    withContext(Dispatchers.IO) {
      val id = item.Id ?: return@withContext Result.Failure("照片信息不完整")
      val bytes = download(EmbyClient.photoDownloadUrl(server, id))
        ?: return@withContext Result.Failure("下载原图失败")
      val fileName = originalFileName(item, bytes)
      writePhoto(context, fileName, mimeOf(fileName), bytes, saveDirUri, saveDirPath)
    }

  /**
   * 另存为 JPEG。调用方需保证 [item] 能取到原文件字节。
   *
   * 本地转码失败（例如系统解不了这种 HEIC）时返回带原因的 Failure，
   * 由调用方决定是否改走服务端转码地址重试。
   *
   * @param rotationDegrees 浏览器里用户转过的角度（顺时针 90° 的整数倍）——
   *   转过图再另存，用户要的是转过的样子，所以转码时一并旋转。
   *   已经是 JPEG 且未旋转时直接写原字节（有损转码只会掉画质）。
   */
  suspend fun saveAsJpeg(
    context: Context,
    server: EmbyServer,
    item: EmbyItem,
    saveDirUri: String? = null,
    rotationDegrees: Int = 0,
    /** 自定义保存目录（**绝对路径**，自研选择器的产物）；空 = 系统相册 */
    saveDirPath: String? = null,
  ): Result =
    withContext(Dispatchers.IO) {
      val id = item.Id ?: return@withContext Result.Failure("照片信息不完整")
      val bytes = download(EmbyClient.photoDownloadUrl(server, id))
        ?: return@withContext Result.Failure("下载原图失败")
      val rotation = ((rotationDegrees % 360) + 360) % 360
      val outBytes = if (EmbyImageLoader.isJpeg(bytes) && rotation == 0) {
        bytes
      } else {
        EmbyImageLoader.transcodeToJpeg(bytes, rotation)
          ?: return@withContext Result.Failure("本地转码 JPEG 失败")
      }
      val base = item.Name?.takeIf { it.isNotBlank() }?.substringBeforeLast('.') ?: "photo"
      writePhoto(context, "$base.jpg", "image/jpeg", outBytes, saveDirUri, saveDirPath)
    }

  // ── 下载 ──

  /** 发起一次下载；失败返回 null（内部已打日志）。 */
  private fun download(url: String): ByteArray? = runCatching {
    EmbyHttp.streaming.newCall(Request.Builder().url(url).build()).execute().use { resp ->
      if (!resp.isSuccessful) {
        Log.w(TAG, "下载失败 HTTP ${resp.code}: $url")
        return@use null
      }
      resp.body.bytes()
    }
  }.onFailure { e ->
    Log.w(TAG, "下载异常: ${e.message} | $url")
  }.getOrNull()

  // ── 落盘 ──

  /**
   * 按用户设置落盘，优先级：**绝对路径 > 老 SAF 树 URI > 系统相册**。
   *
   * @param saveDirPath 自研选择器选出的目录绝对路径。非空时优先走 [writeToPath]。
   * @param saveDirUri `ACTION_OPEN_DOCUMENT_TREE` 拿到的树 URI（字符串，老版本遗留）。
   */
  private fun writePhoto(
    context: Context,
    fileName: String,
    mime: String,
    bytes: ByteArray,
    saveDirUri: String?,
    saveDirPath: String?,
  ): Result =
    when {
      !saveDirPath.isNullOrBlank() ->
        writeToPath(saveDirPath, fileName, bytes)
      !saveDirUri.isNullOrBlank() ->
        writeToTree(context, Uri.parse(saveDirUri), fileName, mime, bytes)
      else ->
        writeToGallery(context, fileName, mime, bytes)
    }

  /**
   * 写进**绝对路径目录**（自研选择器的产物）。
   *
   * 走 `java.io.File`，依赖「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）——
   * 本 App 是本地播放器，这个权限本来就是主流程要用的。
   *
   * ⚠️ **没授权时不能静默失败**：`SecurityException` / `IOException` 会转成带原因的
   * [Result.Failure]，文案里直接点出「没有所有文件访问权限」，让用户知道去哪开，
   * 而不是「保存失败」四个字把人晾着。
   *
   * 同名文件**直接覆盖**：相册目录里同名是同一张图，追加 " (1)" 只会让目录越用越乱。
   */
  private fun writeToPath(
    dirPath: String,
    fileName: String,
    bytes: ByteArray,
  ): Result =
    runCatching {
      val dir = File(dirPath)
      if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
        return Result.Failure("无法创建目录：$dirPath")
      }
      val dest = File(dir, fileName)
      dest.writeBytes(bytes)
      Result.Success
    }.getOrElse { e ->
      Log.w(TAG, "写入自定义路径失败: ${e.message}")
      val hint = if (e is SecurityException) {
        "没有「所有文件访问权限」，请在系统设置里授予后再保存"
      } else {
        "保存到所选目录失败：${e.message}"
      }
      Result.Failure(hint)
    }

  /**
   * 写进**用户自选目录**（SAF）。失败不抛异常，返回带原因的 [Result.Failure]。
   *
   * 用 [DocumentsContract] 而不是第三方库：系统相册那张「选文件夹」界面
   * （`ACTION_OPEN_DOCUMENT_TREE`）是框架自带的，返回的树 URI 配
   * `createDocument` 就能建文件，不必再引依赖，也不用申请存储权限 ——
   * 这正是 Android 10+ 官方推荐的「用户指定保存位置」做法。
   * 同名文件由 DocumentsProvider 自动去重（追加 " (1)"）。
   */
  private fun writeToTree(
    context: Context,
    treeUri: Uri,
    fileName: String,
    mime: String,
    bytes: ByteArray,
  ): Result =
    runCatching {
      val resolver = context.contentResolver
      // ⚠️ createDocument 要的是「目录文档 URI」，不是树 URI ——
      //    直接把树 URI 传进去大部分 provider 会抛 IllegalArgumentException。
      val parentDir = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
      )
      val docUri = DocumentsContract.createDocument(resolver, parentDir, mime, fileName)
        ?: return Result.Failure("无法在所选目录创建文件（可能没给写权限）")
      try {
        resolver.openOutputStream(docUri)?.use { it.write(bytes) }
          ?: return Result.Failure("无法写入所选目录")
      } catch (e: Exception) {
        // 半成品清掉，别在用户目录里留一个 0 字节文件
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
        throw e
      }
      Result.Success
    }.getOrElse { e ->
      Log.w(TAG, "写入自定义目录失败: ${e.message}")
      Result.Failure("保存到所选目录失败：${e.message}")
    }

  /**
   * 写进系统相册。失败不抛异常，返回带原因的 [Result.Failure]（调用方只提示用户）。
   */
  private fun writeToGallery(
    context: Context,
    fileName: String,
    mime: String,
    bytes: ByteArray,
  ): Result =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
          put(MediaStore.Images.Media.MIME_TYPE, mime)
          put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: return Result.Failure("无法创建相册文件")
        try {
          resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return Result.Failure("无法写入相册文件")
        } catch (e: Exception) {
          // 半成品清掉，别在相册里留一个打不开的占位
          runCatching { resolver.delete(uri, null, null) }
          throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Result.Success
      } else {
        val dir = File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
          ALBUM,
        )
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
          return Result.Failure("无法创建相册目录")
        }
        val dest = File(dir, fileName)
        dest.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        Result.Success
      }
    }.getOrElse { e ->
      Log.w(TAG, "保存失败: ${e.message}")
      Result.Failure("保存失败：${e.message}")
    }

  // ── 文件名 / 格式判定 ──

  /** 原图名：优先服务端 Path 里的文件名（带原始扩展名），退回 名称 + 嗅探扩展名 */
  private fun originalFileName(item: EmbyItem, bytes: ByteArray): String {
    val fromPath = item.Path
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.takeIf { it.isNotBlank() && it.contains('.') }
    if (fromPath != null) return fromPath
    val base = item.Name?.takeIf { it.isNotBlank() } ?: "photo"
    return "$base.${extensionOf(bytes)}"
  }

  /** Magic bytes 嗅探扩展名；认不出来按 jpg 处理（Emby 侧图片默认就是 jpg） */
  private fun extensionOf(bytes: ByteArray): String = when {
    EmbyImageLoader.isJpeg(bytes) -> "jpg"
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
      bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
    bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
      bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> "heic"
    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
      bytes[2] == 'F'.code.toByte() -> "gif"
    else -> "jpg"
  }

  private fun mimeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "gif" -> "image/gif"
      "heic", "heif" -> "image/heic"
      "webp" -> "image/webp"
      else -> "image/jpeg"
    }

  // ── 展示用标签 ──

  /**
   * 把 SAF 树 URI 转成给人看的路径标签，例如
   * `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FCineIsle`
   * → `内部存储/Download/CineIsle`。
   *
   * 设置页只拿得到 URI 字符串，直接显示一长串 `content://…` 没人看得懂，
   * 所以用 [DocumentsContract.getTreeDocumentId] 取出 `卷:相对路径`
   * （`primary:Download/CineIsle`），卷是 `primary` 就写「内部存储」，
   * 其它（SD 卡 / 网盘 provider）写「存储卡」。
   * 解析不出来时返回 null，由调用方退回默认文案。
   */
  fun displayLabel(uriString: String?): String? {
    if (uriString.isNullOrBlank()) return null
    return runCatching {
      val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
      val volume = docId.substringBefore(':', "")
      val relative = docId.substringAfter(':', "").trim('/')
      val prefix = if (volume.equals("primary", ignoreCase = true)) "内部存储" else "存储卡"
      if (relative.isEmpty()) prefix else "$prefix/$relative"
    }.getOrNull()
  }

  /**
   * 把自研选择器选出的**绝对路径**转成给人看的短标签。
   *
   * `/storage/emulated/0/DCIM/Camera` → `内部存储/DCIM/Camera`。
   * 设置页那一行只有 ~40 个字的宽度，直接甩绝对路径会被截断成 `…/0/DCIM/Camera`，
   * 前半截 `/storage/emulated` 对所有人都是废话，换掉它。
   */
  fun displayPathLabel(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val p = path.trimEnd('/')
    val internal = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    return when {
      p == internal -> "内部存储"
      p.startsWith("$internal/") -> "内部存储/${p.removePrefix("$internal/")}"
      else -> p
    }
  }
}
