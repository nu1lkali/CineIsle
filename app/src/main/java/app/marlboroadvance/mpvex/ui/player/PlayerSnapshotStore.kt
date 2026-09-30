package app.marlboroadvance.mpvex.ui.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页截图落盘。
 *
 * 与 mpv 侧的落盘策略一致（Android 10+ 走 MediaStore，10 以下走外部存储 + 扫描），
 * 但相册目录用产品自己的名字 `Pictures/CineIsleSnaps` —— mpv 那边的 `mpvSnaps`
 * 属于历史命名，新代码不再沿用。
 *
 * 单独抽出来是因为 GSY 播放页是纯 View 体系、没有 ViewModel 可以挂协程，
 * 截图回调又必然在非主线程，逻辑放这里两边都好复用。
 */
object PlayerSnapshotStore {
  /** 相册下的子目录名 */
  private const val ALBUM = "CineIsleSnaps"

  /**
   * 把 [source]（播放器刚写出的临时文件）存进系统相册。
   *
   * @return 是否保存成功。失败时不抛异常 —— 调用方只需要给用户一句提示。
   */
  suspend fun save(
    context: Context,
    source: File,
    fileName: String,
  ): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          saveViaMediaStore(context, source, fileName)
        } else {
          saveViaExternalStorage(context, source, fileName)
        }.also { ok ->
          // 存完就没用了；失败也删掉，免得缓存目录越积越多
          runCatching { source.delete() }
          if (!ok) return@runCatching false
        }
      }.getOrDefault(false)
    }

  /** Android 10+：交给 MediaStore，无需任何存储权限 */
  private fun saveViaMediaStore(
    context: Context,
    source: File,
    fileName: String,
  ): Boolean {
    val values =
      ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }

    val resolver = context.contentResolver
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return false

    resolver.openOutputStream(target)?.use { output ->
      source.inputStream().use { input -> input.copyTo(output) }
    } ?: return false

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(target, values, null, null)
    return true
  }

  /** Android 9 及以下：直接写外部存储的 Pictures 目录，再通知媒体扫描 */
  private fun saveViaExternalStorage(
    context: Context,
    source: File,
    fileName: String,
  ): Boolean {
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val dir = File(pictures, ALBUM)
    if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false

    val dest = File(dir, fileName)
    source.copyTo(dest, overwrite = true)
    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
    return true
  }
}
