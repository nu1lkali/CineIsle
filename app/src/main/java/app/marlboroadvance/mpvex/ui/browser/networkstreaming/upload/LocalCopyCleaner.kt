package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除手机里的原文件（v192）。
 *
 * ## 两种来源必须分开处理
 *
 * 相册来源是 MediaStore 的 `content://`，删它只能走 `ContentResolver.delete` —— 直接
 * 按路径删会留下一条指向空文件的媒体库记录（相册里出现点不开的缩略图，要等扫描才消失）。
 * 文件/文件夹来源是 [File]，走 `delete()`。
 *
 * ## 为什么不走系统回收站
 *
 * `MediaStore.createTrashRequest` 要求 Activity 拿 `registerForActivityResult` 接回执，
 * 而这里是在**上传批次的协程**里删，手上没有 Activity；应用又持有
 * `MANAGE_EXTERNAL_STORAGE`，`ContentResolver.delete` 本来也不需要那次确认。
 * 所以直接删，安全性靠两道关保证：**只有服务端确认过字节数才允许删**，
 * 以及删前的二次确认框。
 *
 * ## 用 [Result] 而不是抛异常
 *
 * 删除失败是**正常业务分支**（外置卡只读、文件被占用、权限被系统收回），调用方要把它
 * 写进那一行的状态里给用户看，而不是让整批上传崩掉。
 */
internal object LocalCopyCleaner {
  private const val TAG = "LocalCopyCleaner"

  /** `_ID` 用字面量而不是 `MediaStore.MediaColumns._ID`：后者是 API 29 才有的常量 */
  private const val COL_ID = "_ID"

  suspend fun delete(context: Context, contentUri: Uri?, file: File?): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
          when {
            contentUri != null -> deleteUri(context, contentUri)
            file != null -> deleteFile(file)
            else -> error("缺少本机来源引用")
          }
        }
        .onFailure { Log.w(TAG, "删除本机文件失败: ${contentUri ?: file?.absolutePath}", it) }
    }

  private fun deleteUri(context: Context, uri: Uri) {
    val rows =
      runCatching { context.contentResolver.delete(uri, null, null) }
        .getOrElse { e ->
          // 权限被系统收回（用户关掉「所有文件访问权限」）时是 SecurityException，
          // 这时候要说人话，别把异常类名甩给用户
          if (e is SecurityException) error("无删除权限，权限可能已被收回") else throw e
        }
    // 个别 ROM 删成功了也返回 0（条目已经不在媒体库里）——
    // 回查一次再判，别把「本来就没有」报成失败
    if (rows <= 0 && stillExists(context, uri)) {
      error("系统未删除该条目")
    }
  }

  private fun deleteFile(file: File) {
    // 已经被别处删掉了：目标已经达成，不算失败
    if (!file.exists()) return
    if (file.delete()) return
    // delete() 返回 false 但其实没了（少见，某些 FUSE 实现会这样）
    if (!file.exists()) return
    error(if (file.canWrite()) "文件被其他进程占用" else "存储为只读，无写入权限")
  }

  private fun stillExists(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.query(uri, arrayOf(COL_ID), null, null, null)?.use { it.count > 0 } ?: false
      }
      .getOrDefault(false)
}
