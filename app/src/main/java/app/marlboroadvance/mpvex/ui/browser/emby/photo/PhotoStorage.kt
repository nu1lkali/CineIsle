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
import java.io.File

/**
 * 照片落盘的**唯一实现**（Emby 照片与网络照片共用）。
 *
 * 从 [EmbyPhotoSaver] 里抽出来的原因：网络照片查看器（SMB/WebDAV/FTP）
 * 也要「保存到同一套位置」，各写一份 MediaStore / SAF / File 三路逻辑，
 * 下次改相册目录名（`CineIsle`）就会只改一边、两边行为悄悄分叉。
 *
 * 落盘路径优先级（调用方传参，这里只负责执行）：
 * 1. **绝对路径**（自研 FolderPickerDialog 的产物，`photoSaveDirPath`）；
 * 2. **SAF 树 URI**（老版本 `ACTION_OPEN_DOCUMENT_TREE` 遗留，`photoSaveDirUri`）；
 * 3. **系统相册** `Pictures/CineIsle`（Android 10+ MediaStore，9 及以下写外部存储 + 媒体扫描）。
 */
object PhotoStorage {
  private const val TAG = "PhotoStorage"

  /** 相册下的子目录名（与播放截图同一个产品目录） */
  const val ALBUM = "CineIsle"

  sealed interface Result {
    object Success : Result
    data class Failure(val reason: String) : Result
  }

  /**
   * 按用户设置落盘。
   *
   * @param saveDirPath 自研选择器选出的目录绝对路径。非空时优先走绝对路径。
   * @param saveDirUri SAF 树 URI 字符串（老版本遗留）。空 = 系统相册。
   */
  fun writePhoto(
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
