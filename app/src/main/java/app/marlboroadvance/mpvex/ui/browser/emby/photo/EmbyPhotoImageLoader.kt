package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.content.Context
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
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
import coil3.size.Size
import okio.Buffer
import okio.FileSystem

/**
 * 一张 Emby 照片的取图请求（Coil 的 `data`）。
 *
 * ⭐ 刻意用一个**自己的类型**而不是直接把 URL 当 String 传：String 会被 Coil 内置的
 * 网络 Fetcher 抢走（那条路不知道 Emby 的 `X-Emby-Token` 鉴权，
 * 也不会在「服务端缩放失败」时退回原图）。套一层壳之后，只有下面这个
 * [EmbyPhotoFetcher.Factory] 会接它。
 */
data class EmbyPhotoUrl(val url: String)

/**
 * 照片链路的图片加载器（Coil 3）。
 *
 * ⭐ **只服务照片**（浏览器 + 照片网格卡）。海报 / 横幅 / 宫格封面仍然走自研
 * [EmbyImageLoader] —— 那条链路全项目在用，不动它。这里换 Coil 要的是成熟框架才有的东西：
 *  - **按请求尺寸采样**（不会因为「服务端缩放失败返回原图」就整张塞进内存）；
 *  - **内存缓存按字节上限自动淘汰**（[MEMORY_PERCENT]，超了自动丢最旧的）；
 *  - **磁盘缓存**（[DISK_LIMIT_BYTES]）跨启动复用；
 *  - **请求随组合自动取消**（滑走了不再占带宽）。
 *
 * 网络**不是** Coil 的默认客户端：注册了 [EmbyPhotoFetcher]，字节仍然由
 * [EmbyImageLoader.downloadBytes] 取 —— 节流（同时最多 4 个）、双鉴权
 * （api_key query + X-Emby-Token 头）、缩放失败退回原图，全部沿用既有实现。
 *
 * ⚠️ 为什么不用官方的 `coil-network-okhttp`：那要引用 `OkHttpNetworkFetcher`，
 *    而它在某些 Gradle 缓存状态下（transforms 没解开 AAR）会「类在 AAR 里、
 *    编译时却 unresolved」，排查成本远高于这里自己写这十几行 —— 何况我们本来
 *    就要走项目自己的 OkHttp 档位，官方模块给的那点封装用不上。
 */
object EmbyPhotoImageLoader {
  /** 磁盘缓存上限。照片比封面大得多，但也不该无限涨 */
  private const val DISK_LIMIT_BYTES = 128L * 1024 * 1024

  /**
   * 内存缓存占可用堆的比例。
   *
   * ⭐ 这是「预加载会不会炸内存」的答案：堆再大，照片缓存也不会超过这个上限，
   * 超了 Coil 自动按 LRU 淘汰最旧的 —— 预取张数调大只是更容易把**别的**图挤掉，
   * 不会把进程撑爆。0.25 是 Coil 的默认值，不动。
   */
  private const val MEMORY_PERCENT = 0.25

  @Volatile
  private var instance: ImageLoader? = null

  /** 进程内单例：多个照片页共用一份缓存（这点很重要，跨页翻看才不重复下载） */
  fun get(context: Context): ImageLoader =
    instance ?: synchronized(this) {
      instance ?: build(context.applicationContext).also { instance = it }
    }

  private fun build(context: Context): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(EmbyPhotoFetcher.Factory(), EmbyPhotoUrl::class) }
      .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_PERCENT).build() }
      .diskCache { DiskCache.Builder().maxSizeBytes(DISK_LIMIT_BYTES).build() }
      .build()

  /**
   * 构造图片请求。
   *
   * 缓存键**显式给 URL**：`data` 是我们自己的类型，Coil 没有内置的 Keyer 认识它，
   * 不给会退化成「每次请求都是一个新键」，缓存等于没有。
   *
   * `size = [Size.ORIGINAL]`：照片要能放大看细节，**不按屏幕尺寸降采样**。
   * 内存靠两道闸兜住：① URL 里已经带 `maxWidth`（服务端先缩放过），
   * ② 上面那个按百分比的内存缓存上限。
   */
  fun request(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
      .data(EmbyPhotoUrl(url))
      .memoryCacheKey(url)
      .diskCacheKey(url)
      .size(Size.ORIGINAL)
      .build()

  /**
   * 预取：把图拉进缓存，等真滑到那张时直接命中（不白屏）。
   *
   * 用 `execute`（同步取结果）而不是 `enqueue`（发射后不管），是为了能**判断成败**：
   * 原格式解不动（HEIC / 老系统）时顺手把服务端转码的 [fallbackUrl] 也取下来，
   * 否则真滑到那张还是要现等一次网络。
   *
   * ⚠️ 必须在 IO 线程调用（内部有网络 + 解码）。
   */
  suspend fun preload(context: Context, url: String?, fallbackUrl: String? = null) {
    if (url.isNullOrBlank()) return
    val loader = get(context)
    val result = runCatching { loader.execute(request(context, url)) }.getOrNull()
    if (result is coil3.request.ErrorResult && !fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
      runCatching { loader.execute(request(context, fallbackUrl)) }
    }
  }
}

/**
 * 照片的 Coil Fetcher：字节交给 [EmbyImageLoader.downloadBytes]，其余交给 Coil。
 *
 * 失败一律抛异常 —— Coil 会把它转成 `AsyncImagePainter.State.Error`，
 * 界面那边据此显示占位图 + 重试（见 EmbyPhotoImage）。
 */
private class EmbyPhotoFetcher(private val url: String) : Fetcher {
  override suspend fun fetch(): FetchResult {
    val bytes = EmbyImageLoader.downloadBytes(url)
      ?: throw java.io.IOException("照片下载失败: $url")
    val buffer = Buffer().write(bytes)
    return SourceFetchResult(
      source = ImageSource(source = buffer, fileSystem = FileSystem.SYSTEM, metadata = null),
      mimeType = null,
      dataSource = DataSource.NETWORK,
    )
  }

  class Factory : Fetcher.Factory<EmbyPhotoUrl> {
    override fun create(
      data: EmbyPhotoUrl,
      options: Options,
      imageLoader: ImageLoader,
    ): Fetcher = EmbyPhotoFetcher(data.url)
  }
}
