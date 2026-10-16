package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import okio.Buffer
import okio.FileSystem
import okio.buffer
import okio.source
import org.koin.core.context.GlobalContext

/**
 * 一张网络照片的取图请求（Coil 的 `data`）。
 *
 * 与 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoUrl] 同理：
 * 必须套自己的壳，否则 String 会被 Coil 内置网络 Fetcher 抢走（它不认识
 * content:// 代理、更不会走 SMB 会话）。
 */
data class NetworkPhotoRequest(
  val connectionId: Long,
  val path: String,
)

/**
 * 网络照片的图片加载器（Coil 3）。
 *
 * 与 Emby 照片链路（[app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoImageLoader]）
 * 的** deliberate 差异**，每条都是边界条件逼出来的：
 *
 * 1. **没有服务端缩放**：SMB/WebDAV/FTP 就是文件流，缩略图也只能拉全图再解码采样。
 *    兜底是 Coil 的三层：按请求尺寸的 `inSampleSize` 采样解码（不整张进内存）、
 *    内存缓存 25% 上限 LRU、磁盘缓存 128MB（二次浏览同目录直接命中，不再过网络）。
 * 2. **没有转码兜底**：HEIC/AVIF 老系统解不动就是解不动（Emby 那边有服务端
 *    `format=jpg` 兜底），失败走占位 + 重试。
 * 3. **流的生命周期**：`inputStream.source().buffer()` 交给 Coil —— okio 的
 *    `InputStream.source()` 在 Source 被 close 时会顺手关掉底层 InputStream，
 *    而 Coil 消费完（成功 / 失败 / 取消）都会 close 这个 Source，所以会话不会漏。
 *    ⚠️ 不要自己提前 close：那时 Coil 还没开始解码。
 */
object NetworkPhotoImageLoader {
  /** 与照片链路对齐的磁盘缓存上限 */
  private const val DISK_LIMIT_BYTES = 128L * 1024 * 1024

  /** 内存缓存占可用堆比例（0.25 = Coil 默认值） */
  private const val MEMORY_PERCENT = 0.25

  @Volatile
  private var instance: ImageLoader? = null

  /** 进程内单例：网格页与查看器共用一份缓存（从网格点进大图不重新下载） */
  fun get(context: Context): ImageLoader =
    instance ?: synchronized(this) {
      instance ?: build(context.applicationContext).also { instance = it }
    }

  private fun build(context: Context): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(NetworkPhotoFetcher.Factory(), NetworkPhotoRequest::class) }
      .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_PERCENT).build() }
      .diskCache { DiskCache.Builder().maxSizeBytes(DISK_LIMIT_BYTES).build() }
      .build()

  /**
   * 构造图片请求。
   *
   * @param size 网格缩略图传组件尺寸（默认，走采样解码省内存）；
   *   全屏查看器传 [Size.ORIGINAL]（要放大看细节）。**不传 ORIGINAL 就是对大图采样**，
   *   这正是没有服务端缩放时唯一的内存闸门。
   */
  fun request(
    context: Context,
    connectionId: Long,
    path: String,
    size: Size? = null,
  ): ImageRequest {
    val key = "net:$connectionId:$path"
    return ImageRequest.Builder(context)
      .data(NetworkPhotoRequest(connectionId, path))
      .memoryCacheKey(key)
      .diskCacheKey(key)
      .apply { if (size != null) size(size) }
      .build()
  }

  /**
   * 预取：把图拉进缓存，等真滑到那张时直接命中。
   *
   * ⚠️ 必须在 IO 线程调用（内部有网络 + 解码）。
   */
  suspend fun preload(
    context: Context,
    connectionId: Long,
    path: String,
    size: Size? = null,
  ) {
    val loader = get(context)
    runCatching { loader.execute(request(context, connectionId, path, size)) }
  }
}

/**
 * 网络照片的 Coil Fetcher：字节经 [app.marlboroadvance.mpvex.repository.NetworkRepository]
 * 从 SMB/WebDAV/FTP 会话里取，其余交给 Coil。
 *
 * ⚠️ **流式 Source，不整读进内存**：单张可能是 20MB+ 的 TIFF/HEIC，
 *    先 `readBytes()` 再交给 Coil 等于白翻一倍内存。`inputStream.source()`
 *    让 Coil 边写磁盘缓存边解码。
 * ⚠️ **流必须关**：SMB 会话泄漏 = 几次之后这台服务器再也打不开文件。
 *    这里靠 okio 的传递关闭（见第 3 条），**不要**在 fetch 里提前
 *    `inputStream.close()` —— 那时 Coil 还没开始解码。
 */
private class NetworkPhotoFetcher(
  private val connectionId: Long,
  private val path: String,
) : Fetcher {
  override suspend fun fetch(): FetchResult {
    val koin = GlobalContext.getOrNull() ?: throw java.io.IOException("Koin 未初始化")
    val repository: app.marlboroadvance.mpvex.repository.NetworkRepository = koin.get()
    val connection = repository.getConnectionById(connectionId)
      ?: throw java.io.IOException("连接不存在（可能已删除）")

    val inputStream = repository.openFileStream(connection, path).getOrElse { e ->
      throw java.io.IOException("照片打开失败: $path", e)
    }
    // Coil 会消费这个 Source；无论成败，流都由我们关
    val source = inputStream.source().buffer()
    return SourceFetchResult(
      source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM, metadata = null),
      mimeType = null,
      dataSource = DataSource.NETWORK,
    )
  }

  class Factory : Fetcher.Factory<NetworkPhotoRequest> {
    override fun create(
      data: NetworkPhotoRequest,
      options: Options,
      imageLoader: ImageLoader,
    ): Fetcher = NetworkPhotoFetcher(data.connectionId, data.path)
  }
}
