package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 保存目录的一层抽象：屏蔽「绝对路径」与「SAF 树」两种来源。
 *
 * ## 为什么需要
 *
 * 字幕保存目录有两种历史形态：
 *
 * 1. **绝对路径**（自研 `FolderPickerDialog` 的产物，与照片目录同一套交互）；
 * 2. **SAF 树 URI**（早期 `ACTION_OPEN_DOCUMENT_TREE` 的遗留值）。
 *
 * 两种形态的能力不完全相同（`File` 直接操作 vs `ContentResolver` 跨进程），但上层要做的
 * 事只有四件：找子目录、建目录、读写文件、删文件。把它们抽成 [SaveNode] 之后，
 * 另存 / 清单 / 恢复 / 目录扫描四处**只写一份逻辑**，不会出现「新写的绝对路径分支
 * 忘了处理同名覆盖」这类分叉。
 *
 * ## 线程
 *
 * 全部方法是阻塞 IO（SAF 的每个 `findFile` 都是一次跨进程调用）。调用方必须在
 * `Dispatchers.IO` 上执行。
 */
interface SaveNode {
  val name: String

  val isDirectory: Boolean
  val isFile: Boolean
  val canRead: Boolean

  /** 交给播放内核 / ContentResolver 的统一入口：`file://` 或 `content://`。 */
  val uri: Uri

  val size: Long

  fun children(): List<SaveNode>

  fun child(name: String): SaveNode?

  fun mkdir(name: String): SaveNode?

  /** 在该目录下写入文件（同名先删后建）。失败返回 null。 */
  fun write(name: String, bytes: ByteArray, mime: String = DEFAULT_MIME): SaveNode?

  fun readBytes(): ByteArray?

  fun delete(): Boolean

  companion object {
    const val DEFAULT_MIME = "application/octet-stream"
  }
}

/** 绝对路径实现：直接走 `java.io.File`。 */
private class FileSaveNode(
  private val file: File,
) : SaveNode {
  override val name: String get() = file.name
  override val isDirectory: Boolean get() = file.isDirectory
  override val isFile: Boolean get() = file.isFile
  override val canRead: Boolean get() = file.canRead()
  override val uri: Uri get() = Uri.fromFile(file)
  override val size: Long get() = file.length()

  override fun children(): List<SaveNode> =
    file.listFiles()?.map { FileSaveNode(it) } ?: emptyList()

  override fun child(name: String): SaveNode? {
    val target = File(file, name)
    return if (target.exists()) FileSaveNode(target) else null
  }

  override fun mkdir(name: String): SaveNode? {
    val target = File(file, name)
    if (target.isDirectory) return FileSaveNode(target)
    return if (target.mkdirs() || target.isDirectory) FileSaveNode(target) else null
  }

  override fun write(name: String, bytes: ByteArray, mime: String): SaveNode? =
    runCatching {
      val target = File(file, name)
      target.writeBytes(bytes)
      FileSaveNode(target)
    }.getOrNull()

  override fun readBytes(): ByteArray? = runCatching { file.readBytes() }.getOrNull()

  override fun delete(): Boolean = runCatching { file.delete() }.getOrDefault(false)
}

/** SAF 树实现：走 `DocumentFile` + `ContentResolver`。 */
private class TreeSaveNode(
  private val context: Context,
  private val doc: DocumentFile,
) : SaveNode {
  override val name: String get() = doc.name.orEmpty()
  override val isDirectory: Boolean get() = doc.isDirectory
  override val isFile: Boolean get() = doc.isFile
  override val canRead: Boolean get() = doc.canRead()
  override val uri: Uri get() = doc.uri
  override val size: Long get() = doc.length()

  override fun children(): List<SaveNode> =
    runCatching { doc.listFiles().map { TreeSaveNode(context, it) } }.getOrDefault(emptyList())

  override fun child(name: String): SaveNode? =
    runCatching { doc.findFile(name)?.let { TreeSaveNode(context, it) } }.getOrNull()

  override fun mkdir(name: String): SaveNode? =
    runCatching { doc.createDirectory(name)?.let { TreeSaveNode(context, it) } }.getOrNull()

  override fun write(name: String, bytes: ByteArray, mime: String): SaveNode? =
    runCatching {
      // SAF 的 createFile 遇到同名会改名成 "xxx (1).srt"，所以必须先删。
      doc.findFile(name)?.delete()
      val created = doc.createFile(mime, name) ?: return@runCatching null
      context.contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
        ?: return@runCatching null
      TreeSaveNode(context, created)
    }.getOrNull()

  override fun readBytes(): ByteArray? =
    runCatching {
      context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
    }.getOrNull()

  override fun delete(): Boolean = runCatching { doc.delete() }.getOrDefault(false)
}

/**
 * 打开保存目录。
 *
 * 优先级与照片一致：**绝对路径 > SAF 树 URI**。绝对路径非空但目录建不出来时
 * 返回 null（不静默回退到 SAF —— 那会把文件悄悄写到用户没选的旧目录里）。
 */
object SubtitleSaveStore {
  private const val TAG = "SubtitleSaveStore"

  fun open(
    context: Context,
    dirPath: String,
    legacyTreeUri: String,
  ): SaveNode? {
    val app = context.applicationContext
    if (dirPath.isNotBlank()) {
      val dir = File(dirPath)
      if (!dir.isDirectory) {
        val created = runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
        if (!created) {
          Log.w(TAG, "save dir unavailable: $dirPath")
          return null
        }
      }
      return FileSaveNode(dir)
    }
    if (legacyTreeUri.isNotBlank()) {
      return runCatching {
        val doc =
          DocumentFile.fromTreeUri(app, Uri.parse(legacyTreeUri))
            ?.takeIf { it.isDirectory && it.canRead() }
            ?: return null
        TreeSaveNode(app, doc)
      }.getOrNull()
    }
    return null
  }

  /** 是否已配置（两种形态任一）。UI 用它决定「未设置」文案。 */
  fun isConfigured(dirPath: String, legacyTreeUri: String): Boolean =
    dirPath.isNotBlank() || legacyTreeUri.isNotBlank()
}
