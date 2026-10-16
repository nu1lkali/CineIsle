package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

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
 * 落盘三路逻辑（自定义路径 / SAF 树 / 系统相册）在 [PhotoStorage]，与网络照片
 * 保存器共用一份 —— 改相册目录名、改优先级都只改那一处。
 */
object EmbyPhotoSaver {
  private const val TAG = "EmbyPhotoSaver"

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
      // PhotoStorage 的 Result 与对外的 Result 同构但不同源，显式映射
      // （落盘实现换成别的，不该波及调用方的 when）
      return@withContext when (
        val write = PhotoStorage.writePhoto(context, fileName, mimeOf(fileName), bytes, saveDirUri, saveDirPath)
      ) {
        is PhotoStorage.Result.Success -> Result.Success
        is PhotoStorage.Result.Failure -> Result.Failure(write.reason)
      }
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
      return@withContext when (
        val write = PhotoStorage.writePhoto(context, "$base.jpg", "image/jpeg", outBytes, saveDirUri, saveDirPath)
      ) {
        is PhotoStorage.Result.Success -> Result.Success
        is PhotoStorage.Result.Failure -> Result.Failure(write.reason)
      }
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
      bytes[2] == 0x4E.toByte() && bytes[3] == 0x50.toByte() -> "png"
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

  // ── 展示用标签（转交 PhotoStorage，签名不变） ──

  fun displayLabel(uriString: String?): String? = PhotoStorage.displayLabel(uriString)

  fun displayPathLabel(path: String?): String? = PhotoStorage.displayPathLabel(path)
}
