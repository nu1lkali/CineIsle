package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 网络照片保存器（SMB / WebDAV / FTP）：把共享目录里的照片存到本地。
 *
 * 与 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoSaver] 的分工：
 * - **取字节的方式不同**：那边走 Emby HTTP 下载，这边走
 *   [NetworkRepository.openFileStream] 从协议会话里拉流；
 * - **落盘完全共用** [PhotoStorage]（自定义路径 / SAF 树 / 系统相册三路 +
 *   同一个 `Pictures/CineIsle` 目录），保存位置设置也是同一项。
 *
 * 只做「保存原图」：网络侧没有服务端转码，本地转 JPEG 属于增强项，
 * 真需要时再按 Emby 那套（旋转 + transcodeToJpeg）补，不在这版铺开。
 */
object NetworkPhotoSaver {
  private const val TAG = "NetworkPhotoSaver"

  sealed interface Result {
    object Success : Result
    data class Failure(val reason: String) : Result
  }

  /**
   * 保存一张网络照片的原字节。
   *
   * @param saveDirPath 自定义保存目录（绝对路径，自研选择器的产物）；空 = 看 [saveDirUri]
   * @param saveDirUri 自定义保存目录（SAF 树 URI，老版本遗留）；都空 = 系统相册
   */
  suspend fun saveOriginal(
    context: Context,
    connection: NetworkConnection,
    file: NetworkFile,
    saveDirUri: String? = null,
    saveDirPath: String? = null,
  ): Result =
    withContext(Dispatchers.IO) {
      val koin = GlobalContext.getOrNull() ?: return@withContext Result.Failure("应用尚未初始化")
      val repository: NetworkRepository = koin.get()
      val bytes = runCatching {
        repository.openFileStream(connection, file.path).getOrThrow().use { input ->
          // 原图可能 10MB+（HEIC/TIFF），但保存就是要完整字节，读满再写
          input.readBytes()
        }
      }.onFailure { e ->
        Log.w(TAG, "拉取照片失败: ${file.path} | ${e.message}")
      }.getOrNull() ?: return@withContext Result.Failure("下载原图失败")

      // 文件名直接用共享上的原名（带扩展名）—— 用户认的就是它
      val fileName = file.name.takeIf { it.isNotBlank() } ?: "photo"
      // PhotoStorage 的 Result 与对外的 Result 同构但不同源，这里显式映射
      // （不直接把 sealed 类型透出去 —— 落盘实现换了不能波及调用方的 when）
      return@withContext when (
        val write = PhotoStorage.writePhoto(context, fileName, mimeOf(fileName), bytes, saveDirUri, saveDirPath)
      ) {
        is PhotoStorage.Result.Success -> Result.Success
        is PhotoStorage.Result.Failure -> Result.Failure(write.reason)
      }
    }

  private fun mimeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "gif" -> "image/gif"
      "bmp" -> "image/bmp"
      "webp" -> "image/webp"
      "heic", "heif", "hif" -> "image/heic"
      "avif" -> "image/avif"
      "tiff", "tif" -> "image/tiff"
      "svg" -> "image/svg+xml"
      else -> "image/jpeg"
    }
}
