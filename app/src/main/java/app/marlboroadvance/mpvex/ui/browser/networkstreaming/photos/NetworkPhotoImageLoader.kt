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
 *
 * @param sign AList 的下载签名（`fs/list` 条目带的 `sign`）。其他协议恒为 null。
 *   ⚠️ **不参与缓存 key** —— 同一张图换个签名还是同一张图，签名进 key 会让
 *   缓存永远命中不了（签名每次都可能不同）。
 */
data class NetworkPhotoRequest(
  val connectionId: Long,
  val path: String,
  val sign: String? = null,
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

  /**
   * 磁盘缓存目录名（挂在系统临时目录 [FileSystem.SYSTEM_TEMPORARY_DIRECTORY] 下）。
   *
   * ⭐⭐ 这一项是**必需**的：[DiskCache.Builder.build] 里是
   *    `checkNotNull(directory) { "directory == null" }`，`Builder()` 构造时
   *    **不会**给目录赋默认值（已 javap 核验 3.6.3：`<init>` 只设了
   *    fileSystem / maxSizePercent / min / max，没有 directory）。
   *    以前这里漏了 `.directory(...)` ⇒ 建缓存必抛 `IllegalStateException`；
   *    因为 `diskCacheLazy` 是懒加载，异常发生在图片请求协程里，被 Coil 兜成
   *    Error ⇒ 表现为「封面加载不出来、点重载也没反应」。
   */
  private const val NET_IMAGE_CACHE_DIR = "network_photo_cache"

  /** 内存缓存占可用堆比例（0.25 = Coil 默认值） */
  private const val MEMORY_PERCENT = 0.25

  private const val TAG = "NetworkPhotoImageLoader"

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
      .diskCache {
        DiskCache.Builder()
          .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(NET_IMAGE_CACHE_DIR))
          .maxSizeBytes(DISK_LIMIT_BYTES)
          .build()
      }
      .build()

  /** 内存 / 磁盘缓存 key。三处（request / isMemoryCached）必须同一口径 */
  private fun cacheKey(connectionId: Long, path: String): String = "net:$connectionId:$path"

  /**
   * 构造图片请求。
   *
   * @param size 网格缩略图传组件尺寸（默认，走采样解码省内存）；
   *   全屏查看器传 [Size.ORIGINAL]（要放大看细节）。**不传 ORIGINAL 就是对大图采样**，
   *   这正是没有服务端缩放时唯一的内存闸门。
   * @param sign AList 下载签名（[app.marlboroadvance.mpvex.domain.network.NetworkFile.sign]）；
   *   其他协议传 null。⚠️ **不进缓存 key**（见 [NetworkPhotoRequest]）。
   */
  fun request(
    context: Context,
    connectionId: Long,
    path: String,
    size: Size? = null,
    sign: String? = null,
  ): ImageRequest {
    val key = cacheKey(connectionId, path)
    return ImageRequest.Builder(context)
      .data(NetworkPhotoRequest(connectionId, path, sign))
      .memoryCacheKey(key)
      .diskCacheKey(key)
      .apply { if (size != null) size(size) }
      .build()
  }

  /**
   * 这张图是否已在内存缓存里。
   *
   * 用途：命中 ⇒ 卡片**首帧就能拿到图**，调用方可以跳过骨架屏。
   * 否则「进入子文件夹 → 返回上一级」时，painter 状态机会从 Loading 重新走一遍，
   * 每张封面都闪一下骨架屏 —— 图其实一直在缓存里，纯属视觉噪音。
   *
   * ⚠️ 只用于决定是否画骨架屏，**不能**用来决定要不要发请求：
   *    这个判断本身就有竞态（查完到请求发出之间缓存可能被淘汰）。
   */
  fun isMemoryCached(context: Context, connectionId: Long, path: String): Boolean =
    get(context).memoryCache?.get(MemoryCache.Key(cacheKey(connectionId, path))) != null

  /**
   * 把一张图的缓存条目**删掉**（内存 + 磁盘）。
   *
   * ⭐ 用途：加载失败后的「重载」。之前那个重试按钮只调 `painter.restart()`，
   * 也就是**重走同一个请求** —— 而「网络抖了一下」往往在磁盘缓存里留下了
   * 半截 / 解不动的数据，或者 AList 的签名已经过期，于是每次重试都撞同一堵墙：
   * 状态从 Error 走一圈回到 Error，界面上就是「点了没反应」。
   *
   * 清掉这个 key 之后再取，才是真的从头拉一次。
   *
   * ⚠️ 只删这一张（key 口径与 [request] 完全一致），不动别的图。
   * ⚠️ 有磁盘 IO，调用方放 IO 线程。
   *
   * ⭐ **整段包在 `runCatching` 里**：这个方法是「用户点了重载」这种纯 UI 触发的路径，
   *    它跑在普通协程里、**不在 Coil 的 try/catch 之内** —— 里面任何异常（典型如
   *    建 ImageLoader 时磁盘缓存目录不可用）都会直接冒泡成崩溃。清缓存失败最多是
   *    「这次重载没清干净」，绝不该把界面带崩，所以这里吞掉并记日志。
   */
  fun evict(context: Context, connectionId: Long, path: String) {
    runCatching {
      val key = cacheKey(connectionId, path)
      val loader = get(context)
      loader.memoryCache?.remove(MemoryCache.Key(key))
      loader.diskCache?.remove(key)
    }.onFailure { android.util.Log.w(TAG, "清理网络照片缓存失败: $path", it) }
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
    sign: String? = null,
  ) {
    val loader = get(context)
    runCatching { loader.execute(request(context, connectionId, path, size, sign)) }
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
  private val sign: String?,
) : Fetcher {
  override suspend fun fetch(): FetchResult {
    val koin = GlobalContext.getOrNull() ?: throw java.io.IOException("Koin 未初始化")
    val repository: app.marlboroadvance.mpvex.repository.NetworkRepository = koin.get()
    val connection = repository.getConnectionById(connectionId)
      ?: throw java.io.IOException("连接不存在（可能已删除）")

    // sign 是 AList 的下载签名（其他协议为 null）。AList 的 /d/ 下载口只认它，
    // 缺了会 401 —— 但 AlistClient 内部还有 fs/get 兜底，所以这里传 null 也能取到
    // （只是多一个请求）。见 AlistClient 的类注释。
    val inputStream = repository.openFileStream(connection, path, sign).getOrElse { e ->
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
    ): Fetcher = NetworkPhotoFetcher(data.connectionId, data.path, data.sign)
  }
}
