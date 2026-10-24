package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album

import android.content.Context
import coil3.ImageLoader
import coil3.video.VideoFrameDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

/**
 * 相册网格专用的 Coil 实例（进程级单例）。
 *
 * ## 为什么不复用照片链路的 ImageLoader
 *
 * 那两个（`NetworkPhotoImageLoader` / `EmbyPhotoImageLoader`）都注册了项目自己的
 * `Fetcher`，只认各自的请求壳（`NetworkPhotoRequest` / `EmbyPhotoUrl`）——
 * 把 `content://` 交给它们会直接落到默认的网络/资源 Fetcher 上，加载不出来。
 *
 * ## 为什么必须注册 [VideoFrameDecoder]
 *
 * 视频格子的数据源是 `content://media/external/video/media/123`，Coil 的核心只装了
 * Bitmap 解码器，拿整段 MP4 去解码必然失败。`VideoFrameDecoder` 用
 * MediaMetadataRetriever 抽关键帧，这也是官方推荐的视频缩略图做法。
 *
 * ## 缓存口径
 *
 * - 内存：堆的 1/8，夹在 16~96MB。缩略图很小（每张约 200KB），这个量够滚几十屏；
 * - 磁盘：256MB，存在应用私有 cacheDir 下（不用 `java.io.tmpdir`，见
 *   `NetworkPhotoImageLoader` 里那段踩坑记录）。
 *
 * 磁盘缓存存的是**解码前的字节**，视频也是（`VideoFrameDecoder` 只从磁盘缓存读源数据），
 * 所以同一个视频换尺寸也只是重解码、不重读盘。
 */
object AlbumImageLoader {
  private const val CACHE_DIR = "album_thumbs"
  private const val DISK_LIMIT_BYTES = 256L * 1024 * 1024
  private const val MEMORY_MIN_BYTES = 16L * 1024 * 1024
  private const val MEMORY_MAX_BYTES = 96L * 1024 * 1024

  @Volatile private var instance: ImageLoader? = null

  fun get(context: Context): ImageLoader =
    instance
      ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
      }

  private fun build(context: Context): ImageLoader =
    ImageLoader.Builder(context)
      // add() 是「追加到默认组件表」，图片的 Bitmap 解码器仍在，不会互相顶掉
      .components { add(VideoFrameDecoder.Factory()) }
      .memoryCache {
        MemoryCache.Builder()
          .maxSizeBytes(
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(MEMORY_MIN_BYTES, MEMORY_MAX_BYTES),
          )
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(context.cacheDir.resolve(CACHE_DIR).toOkioPath())
          .maxSizeBytes(DISK_LIMIT_BYTES)
          .build()
      }
      .build()
}
