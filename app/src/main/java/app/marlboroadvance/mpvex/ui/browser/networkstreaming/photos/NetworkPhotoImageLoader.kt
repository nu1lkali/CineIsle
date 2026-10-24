package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath
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
  /**
   * 内存缓存分桶（同时也是二级缩略图磁盘缓存的维度）。
   *
   * 参与 equals：不同桶的 request 本来就不该判等（解码尺寸不同），
   * 这样 `AsyncImagePainter` 在桶切换时也能正确重发。
   */
  val bucket: String = NetworkPhotoImageLoader.BUCKET_THUMB,
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
  /**
   * 磁盘缓存上限。
   *
   * ⭐ **这里存的是一张图的原始字节**（完整 JPEG，网络照片常见 3–8MB），
   *    不是解码后的缩略图 —— 因为本链路没有服务端缩放，只能整张拉下来再采样解码。
   *    所以这条缓存的**张数**约等于 `上限 ÷ 单张原图大小`：
   *    128MB 只够 20~40 张，用户滚过这一段之后，前面看过的图全被 LRU 挤掉，
   *    滚回来只能重新走网络 —— 表现就是「滚得多了缩略图就丢了」。
   *
   *    512MB 能放下约 100 张（按 5MB/张），配合内存缓存分桶，日常几百张的目录
   *    来回滚动基本不再重复下载。再往上（1GB+）收益递减，而且会挤占别的应用空间。
   */
  private const val DISK_LIMIT_BYTES = 512L * 1024 * 1024

  /**
   * 磁盘缓存目录名（挂在 [Context.getCacheDir] 下）。
   *
   * ⭐ 这一项是**必需**的：[DiskCache.Builder.build] 里是
   *    `checkNotNull(directory) { "directory == null" }`，`Builder()` 构造时
   *    **不会**给目录赋默认值（已 javap 核验 3.6.3：`<init>` 只设了
   *    fileSystem / maxSizePercent / min / max，没有 directory）。
   *    以前这里漏了 `.directory(...)` ⇒ 建缓存必抛 `IllegalStateException`；
   *    因为 `diskCacheLazy` 是懒加载，异常发生在图片请求协程里，被 Coil 兜成
   *    Error ⇒ 表现为「封面加载不出来、点重载也没反应」。
   *
   * ⚠️ 用 `context.cacheDir` 而不是 `FileSystem.SYSTEM_TEMPORARY_DIRECTORY`：
   *    后者是 `java.io.tmpdir`，在部分 ROM 上会指向 `/data/local/tmp` 之类的
   *    非应用私有目录（不可写 / 被系统定期清空）。`cacheDir` 是本应用的
   *    缓存目录，语义明确、一定可写，系统清缓存时也会连带清掉（正是我们要的）。
   */
  private const val NET_IMAGE_CACHE_DIR = "network_photo_cache"

  /**
   * 内存缓存占堆上限的比例。
   *
   * Coil 默认 0.25；这里抬到 0.35 —— 本链路每张缩略图约 490KB（360px ARGB_8888），
   * 25%（512MB 堆 ≈ 128MB）只够 260 张，用户「滚得多了」就会撞到上限。
   * 0.35 约 360 张，代价是留给 Bitmap 之外的内存更少，所以下面再压一道
   * **字节上限**，避免高堆设备上算出 300MB+ 这种不合理的值。
   */
  private const val MEMORY_PERCENT = 0.35

  /** 内存缓存下限：低堆设备上也别低于这个值，否则滚两屏就空了。 */
  private const val MEMORY_MIN_BYTES = 64L * 1024 * 1024

  /** 内存缓存上限：高堆设备上封顶，别真的按 35% 吃满。 */
  private const val MEMORY_MAX_BYTES = 256L * 1024 * 1024

  private const val TAG = "NetworkPhotoImageLoader"

  @Volatile
  private var instance: ImageLoader? = null

  /** 进程内单例：网格页与查看器共用一份缓存（从网格点进大图不重新下载） */
  fun get(context: Context): ImageLoader =
    instance ?: synchronized(this) {
      instance
        ?: build(context.applicationContext).also {
          instance = it
          // 清掉上个进程可能残留的缩略图临时文件（见 cleanupTempFiles）
          cleanupTempFiles(context.applicationContext)
        }
    }

  /**
   * 清掉上次进程残留的缩略图临时文件。
   *
   * 正常路径下 [NetworkPhotoFetcher] 用完就删；只有「原图解码失败、把临时文件
   * 直接交给 Coil」那条降级路径不会删（Coil 不接管文件生命周期）。这里按
   * 1 小时前的时间戳清一次，避免长期堆积 —— 顺手也避开「刚写的临时文件正被读」。
   */
  private fun cleanupTempFiles(context: Context) {
    runCatching {
      val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
      File(context.cacheDir, TEMP_DIR_NAME).listFiles()?.forEach { file ->
        if (file.lastModified() < cutoff) file.delete()
      }
    }
  }

  private fun build(context: Context): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(NetworkPhotoFetcher.Factory(context), NetworkPhotoRequest::class) }
      .memoryCache {
        MemoryCache.Builder()
          .maxSizeBytes(memoryCacheBytes())
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          // 见 NET_IMAGE_CACHE_DIR：用应用私有 cacheDir，不用 java.io.tmpdir
          // ⚠️ `DiskCache.Builder.directory(...)` 只接受 okio 的 Path（不是 java.io.File），
          //    必须 `toOkioPath()` 转换（真实编译错误：Argument type mismatch）。
          .directory(context.cacheDir.resolve(NET_IMAGE_CACHE_DIR).toOkioPath())
          .maxSizeBytes(DISK_LIMIT_BYTES)
          .build()
      }
      .build()

  /**
   * 内存缓存字节数：按堆比例算完再夹一道上下限（理由见 [MEMORY_PERCENT]）。
   *
   * 不用 `MemoryCache.Builder.maxSizePercent(context, …)` 是因为它只有百分比、
   * 没法设下限 —— 低堆设备上算出来的值可能小到「滚两屏就淘汰」。
   */
  private fun memoryCacheBytes(): Long =
    (Runtime.getRuntime().maxMemory() * MEMORY_PERCENT)
      .toLong()
      .coerceIn(MEMORY_MIN_BYTES, MEMORY_MAX_BYTES)

  /**
   * 磁盘缓存 key。
   *
   * 磁盘里存的是**原始字节**（解码前），与显示尺寸无关 —— 一张图只需要存一份，
   * 所以这里**不带尺寸**。
   */
  private fun cacheKey(connectionId: Long, path: String): String = "net:$connectionId:$path"

  /**
   * 内存缓存 key = 磁盘 key + **尺寸分桶**。
   *
   * ⭐ 分桶是**必需**的，不分桶会连锁出一整串症状：
   *
   * 内存缓存里存的是**解码后的 Bitmap**，而同一张照片在不同入口要的解码尺寸差了几个数量级：
   * 网格缩略图 ~360px（约 500KB）、列表行 64dp（约 150KB）、查看器大图
   * `Size.ORIGINAL`（4000×3000 ARGB_8888 ≈ **48MB**）。它们被塞进同一个 key 时：
   *
   * 1. 点开一次大图 ⇒ 该 key 的缓存条目被原图**覆盖**；
   * 2. 一张 48MB 原图进了「25% 堆」的 LRU（中端机常见 64MB 上限）⇒ 吃掉大半个缓存；
   * 3. Coil 立刻按字节淘汰 ⇒ **周围一圈缩略图全被踢出内存缓存**；
   * 4. 滚回去 ⇒ 每张都重新走解码/网络 ⇒ 用户看到的就是
   *    「图明明加载过，滚回来又要重新加载」。
   *
   * 列表视图的 64dp 与网格的 360px 共用一个 key 时也会互相覆盖 —— 后写的把小图钉死，
   * 另一个入口拿到尺寸不符的条目只能重新解码。所以按**入口**分桶，而不是按像素值
   * （像素值随屏幕密度变，桶是稳定的语义单位）。
   *
   * ⚠️ 三个口径必须一致：`request()` 写入用哪个桶，`isMemoryCached()` 就得查哪个桶，
   *    `evict()` 得把这张图**所有桶**都清掉（见各自的 KDoc）。
   */
  private fun memoryKey(connectionId: Long, path: String, bucket: String): String =
    "${cacheKey(connectionId, path)}|$bucket"

  /** 网格 / 列表缩略图（按组件尺寸采样解码）。 */
  const val BUCKET_THUMB = "thumb"

  /** 查看器底部胶片条（44dp 见方，比网格更小）。 */
  const val BUCKET_FILM = "film"

  /** 全屏查看（同一张图最大的一份解码结果，尺寸见 [FULL_SIZE]）。 */
  const val BUCKET_FULL = "full"

  /**
   * 全屏查看的解码边长上限（像素）。
   *
   * 2560 ≈ 1080p 屏宽的 2.4 倍：4× 放大还看得出细节，再往上人眼也分不出来。
   */
  const val FULL_MAX_EDGE_PX = 2560

  /**
   * 全屏查看的请求尺寸 —— **不要改回 [Size.ORIGINAL]**。
   *
   * ⭐ 用户反馈「10MB 左右的大图一直加载中」，根因就是原图直解：
   *
   * 1. **内存**：一张 6000×4000 的 10MB JPEG，ARGB_8888 要 **96MB** 一个 Bitmap。
   *    查看器还会按「照片预加载张数」拉前后几张（同一口径也是原图），2~4 张
   *    一起就是 200~400MB —— 撞 OOM，或者把内存缓存（上限 256MB）来回挤爆，
   *    表现就是解码不停、缩略图全被淘汰、界面一直转圈。
   * 2. **磁盘**：Coil 拿到的是**不可回退的流**，做边界探测 / EXIF 时必须先
   *    `SourceImageSource.file()` 把整个流复制成一个临时文件（已 javap 核验），
   *    10MB 的图等于白写两遍磁盘再解码。
   *
   * 换成有界框后：单张解码降到约 17MB、不再有「几张 96MB 并发解码」的尖峰，
   * 几百毫秒就能出图。（Coil 对不可回退的流仍会临时落一份文件做边界探测，
   * 那是 10MB 级的磁盘写，跟 96MB 的内存峰值不是一个量级，可以接受。）
   *
   * ⚠️ 它只是**上限**，Coil 不会放大 —— 小图仍按原尺寸解码，画质一点没变。
   * ⚠️ 预加载必须用**同一个尺寸 + 同一个桶**，否则预热的是别的 key，等于白下载
   *    （见 [preload] 的 bucket 参数）。
   */
  val FULL_SIZE: Size = Size(FULL_MAX_EDGE_PX, FULL_MAX_EDGE_PX)

  /**
   * 二级缩略图磁盘缓存的 key（供 [NetworkPhotoFetcher] 用）。
   *
   * 与内存 key 同口径、**含桶**：胶片条的 44dp 和网格的 360px 是两个不同的文件，
   * 共用会小图覆盖大图 ⇒ 网格显示被放大后的糊图。
   * ⚠️ `BUCKET_FULL` 不走这份缓存（它要的是原图），key 里也不会出现。
   */
  fun thumbDiskKey(connectionId: Long, path: String, bucket: String): String =
    memoryKey(connectionId, path, bucket)

  /**
   * 构造图片请求。
   *
   * @param size 网格缩略图传组件尺寸（默认，走采样解码省内存）；
   *   全屏查看器传 [FULL_SIZE]（有界框，见那里的推导）。**不传就是对大图采样**，
   *   这正是没有服务端缩放时唯一的内存闸门。
   * @param sign AList 下载签名（[app.marlboroadvance.mpvex.domain.network.NetworkFile.sign]）；
   *   其他协议传 null。⚠️ **不进缓存 key**（见 [NetworkPhotoRequest]）。
   * @param bucket 内存缓存分桶，见 [memoryKey] 的 KDoc。网格/列表传 [BUCKET_THUMB]、
   *   胶片条传 [BUCKET_FILM]、全屏查看传 [BUCKET_FULL]。
   *   ⚠️ **同一个显示尺寸必须始终用同一个桶**，否则会退化成「每次都是新 key = 必然 miss」。
   */
  fun request(
    context: Context,
    connectionId: Long,
    path: String,
    size: Size? = null,
    sign: String? = null,
    bucket: String = BUCKET_THUMB,
  ): ImageRequest {
    val diskKey = cacheKey(connectionId, path)
    return ImageRequest.Builder(context)
      .data(NetworkPhotoRequest(connectionId, path, sign, bucket))
      .memoryCacheKey(memoryKey(connectionId, path, bucket))
      .diskCacheKey(diskKey)
      .apply {
        if (size != null) {
          size(size)
          // ⚠️⚠️ 必须显式 INEXACT：Coil 3 的 `ImageRequest.Defaults.precision` 默认是
          //    **EXACT**（已 javap 核实：Defaults 构造里 `getstatic Precision.EXACT`），
          //    意思是「输出必须正好是你给的尺寸，不够就放大」。我们给的尺寸一律是
          //    **上限**（[FULL_SIZE] / 缩略图边长），配上 EXACT 就会把 800×600 的小图
          //    插值放大成 2560×1920 —— 白吃 17MB 内存，画质还更差。
          //    INEXACT = 允许结果小于请求尺寸（即「绝不放大」），缩图该缩还是缩。
          precision(coil3.size.Precision.INEXACT)
        }
      }
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
   * @param bucket 必须与 [request] 用的是同一个桶，否则永远查不到（见 [memoryKey]）。
   */
  fun isMemoryCached(
    context: Context,
    connectionId: Long,
    path: String,
    bucket: String = BUCKET_THUMB,
  ): Boolean =
    get(context).memoryCache?.get(MemoryCache.Key(memoryKey(connectionId, path, bucket))) != null

  /**
   * 「这张图在本进程里成功显示过」的 LRU 记录（按**磁盘 key** 存，不分桶 ——
   * 只要某个尺寸成功过，这张图就是可用的）。
   *
   * 存在的理由：内存缓存必然会被 LRU 淘汰（1000 张的目录不可能全放得下），
   * 但磁盘里通常还在 —— 命中磁盘只需几十毫秒解码。这几十毫秒里如果画扫光骨架屏，
   * 观感就是「图丢了又重新加载」；而它其实只是从磁盘读了一次。
   *
   * ⚠️ 纯**视觉**判据：只决定画不画骨架屏，**不能**用来跳过请求。
   * ⚠️ 进程内有效，重启即空（那时磁盘缓存还在，只是会闪一次骨架，可以接受）。
   */
  private val loadedKeys = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
      size > LOADED_KEYS_MAX
  }

  /** 这张图的「成功过」记录要记多少条。一条只是一个 path 字符串，不值钱。 */
  private const val LOADED_KEYS_MAX = 4096

  /** 标记这张图已经成功显示过（见 [loadedKeys]）。 */
  fun markLoaded(connectionId: Long, path: String) {
    runCatching {
      synchronized(loadedKeys) { loadedKeys[cacheKey(connectionId, path)] = Unit }
    }
  }

  /**
   * 这张图是否「已经显示过」= 内存缓存命中 **或** 曾经成功过。
   *
   * 调用方拿它决定**画不画骨架屏**：返回 true 就不画，让图自己出来
   * （内存命中是即时的，磁盘命中是几十毫秒）。详见 [loadedKeys]。
   */
  fun hasShownBefore(
    context: Context,
    connectionId: Long,
    path: String,
    bucket: String = BUCKET_THUMB,
  ): Boolean =
    isMemoryCached(context, connectionId, path, bucket) ||
      synchronized(loadedKeys) { loadedKeys.containsKey(cacheKey(connectionId, path)) }

  /**
   * 把一张图的缓存条目**删掉**（内存 + 磁盘）。
   *
   * 用途：加载失败后的「重载」。之前那个重试按钮只调 `painter.restart()`，
   * 也就是**重走同一个请求** —— 而「网络抖了一下」往往在磁盘缓存里留下了
   * 半截 / 解不动的数据，或者 AList 的签名已经过期，于是每次重试都撞同一堵墙：
   * 状态从 Error 走一圈回到 Error，界面上就是「点了没反应」。
   *
   * 清掉这个 key 之后再取，才是真的从头拉一次。
   *
   * ⚠️ 只删这一张，不动别的图。内存里**三个桶全清**：这张图坏不坏跟显示尺寸无关，
   *    只清调用方自己那个桶的话，切到另一个入口仍会拿到同一份坏字节解出来的图。
   * ⚠️ 有磁盘 IO，调用方放 IO 线程。
   *
   * **整段包在 `runCatching` 里**：这个方法是「用户点了重载」这种纯 UI 触发的路径，
   *    它跑在普通协程里、**不在 Coil 的 try/catch 之内** —— 里面任何异常（典型如
   *    建 ImageLoader 时磁盘缓存目录不可用）都会直接冒泡成崩溃。清缓存失败最多是
   *    「这次重载没清干净」，绝不该把界面带崩，所以这里吞掉并记日志。
   */
  fun evict(context: Context, connectionId: Long, path: String) {
    runCatching {
      val loader = get(context)
      listOf(BUCKET_THUMB, BUCKET_FILM, BUCKET_FULL).forEach { bucket ->
        loader.memoryCache?.remove(MemoryCache.Key(memoryKey(connectionId, path, bucket)))
      }
      loader.diskCache?.remove(cacheKey(connectionId, path))
    }.onFailure { android.util.Log.w(TAG, "清理网络照片缓存失败: $path", it) }
  }

  /**
   * 预取：把图拉进缓存，等真滑到那张时直接命中。
   *
   * ⚠️ **必须在 IO 线程调用**（内部有网络 + 解码）。
   * ⚠️ [bucket] 故意**没有默认值**：以前默认 `BUCKET_THUMB`，而查看器要的是
   *    `BUCKET_FULL` ⇒ 预热的是另一个 key，查看器照样得重新下载 + 重新解码，
   *    白花一次网络（一张 10MB 的图就是白读 10MB），还占满缩略图那份内存缓存。
   *    这类「参数默认值悄悄写错」的 bug 编译期查不出来，只能靠不给默认值逼调用方表态。
   * ⚠️ 尺寸也必须与真正显示那次**完全一致**（[request] 的 size 进 ImageRequest，
   *    内存 key 里只有桶没有尺寸 —— 不一致就是命中不了）。
   */
  suspend fun preload(
    context: Context,
    connectionId: Long,
    path: String,
    size: Size?,
    sign: String?,
    bucket: String,
  ) {
    val loader = get(context)
    runCatching { loader.execute(request(context, connectionId, path, size, sign, bucket)) }
  }
}

/**
 * 网络照片的 Coil Fetcher。
 *
 * 分两条路径，由 [NetworkPhotoRequest.bucket] 决定：
 *
 * 1. **缩略图桶（thumb / film）** —— 三级缓存：内存（Coil）→
 *    **二级缩略图磁盘缓存**（[NetworkThumbnailDiskCache]，压缩后的 JPEG 几十 KB）→ 网络。
 *    未命中时才把原图下到临时文件、按 `options.size` 采样解码、压成 JPEG 落盘。
 *    于是「滚回来」命中的是几十 KB 的缩略图，而不是重新下几 MB 的原图 ——
 *    这才是「滚多了缩略图就丢」的真正解法，单靠调大原图缓存只能治标。
 *
 * 2. **全屏桶（[NetworkPhotoImageLoader.BUCKET_FULL]）** —— 原流式直通，
 *    解码尺寸由请求的 [NetworkPhotoImageLoader.FULL_SIZE] 卡在 2560 有界框内。
 *
 * ⚠️ **不整读进内存**：单张可能是 20MB+ 的 TIFF/HEIC。缩略图路径走
 *    「流 → 临时文件 → 采样解码」，内存里只留采样后的 Bitmap。
 * ⚠️ **流必须关**：SMB 会话泄漏 = 几次之后这台服务器再也打不开文件。
 *    缩略图路径用 `use {}` 显式关；全屏路径靠 okio 的传递关闭，
 *    **不要**在 fetch 里提前 close —— 那时 Coil 还没开始解码。
 */
private class NetworkPhotoFetcher(
  private val context: Context,
  private val connectionId: Long,
  private val path: String,
  private val sign: String?,
  private val bucket: String,
) : Fetcher {
  override suspend fun fetch(): FetchResult {
    if (bucket == NetworkPhotoImageLoader.BUCKET_FULL) return fetchOriginal()

    val cacheKey = NetworkPhotoImageLoader.thumbDiskKey(connectionId, path, bucket)
    // ① 命中二级缩略图缓存：几十 KB 的 JPEG，零网络
    NetworkThumbnailDiskCache.cachedFile(context, cacheKey)?.let { file ->
      // 命中即刷新 mtime，让磁盘缓存的淘汰更接近真正的 LRU（见 touch 的说明）
      NetworkThumbnailDiskCache.touch(file)
      return fileResult(file, "image/jpeg", DataSource.DISK)
    }
    // ② 未命中：下载 → 采样 → 压 JPEG → 存盘
    return fetchAndBuildThumbnail(cacheKey)
  }

  /**
   * 全屏：原流式直通（不预缩放、不进缩略图缓存）。
   *
   * 采样交给 Coil 在解码阶段按 [NetworkPhotoImageLoader.FULL_SIZE] 做 ——
   * 这里**不要**自己读进内存再缩，那会在 Coil 之外多一份整图缓冲。
   */
  private suspend fun fetchOriginal(): FetchResult {
    val inputStream = openStream()
    // Coil 会消费这个 Source；无论成败，流都由我们关
    val source = inputStream.source().buffer()
    return SourceFetchResult(
      source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM, metadata = null),
      mimeType = null,
      dataSource = DataSource.NETWORK,
    )
  }

  /**
   * 未命中二级缓存：下载 → 采样解码 → 压 JPEG 存盘。
   *
   * 首次加载会多花「落临时文件 + 解码 + 编码」约 100~300ms；相比本来就在等的
   * 网络时间可以忽略，换来的是这张图**以后每次滚回来只要几十毫秒**。
   */
  private suspend fun fetchAndBuildThumbnail(cacheKey: String): FetchResult {
    val raw = downloadToTempFile()
    val bitmap = decodeSampled(raw, maxDecodeEdge(bucket))
    if (bitmap == null) {
      // 解不动（TIFF / 罕见编码 / 文件损坏）：把原始字节交给 Coil，走它原来的解码链路。
      // 能显示就显示，不能就由 Coil 报 Error 交给卡片的重试 UI ——
      // 这里不能替它兜底，否则会把「真坏的文件」伪装成成功。
      // ⚠️ 临时文件不删，由下次启动统一清理（见 cleanupTempFiles）。
      return fileResult(raw, null, DataSource.NETWORK)
    }
    val cached =
      try {
        NetworkThumbnailDiskCache.store(context, cacheKey, bitmap)
      } finally {
        // 编码完立刻回收：这张 Bitmap 的唯一用途就是压成 JPEG
        bitmap.recycle()
      }
    raw.delete()
    if (cached == null) throw java.io.IOException("缩略图缓存写入失败: $path")
    return fileResult(cached, "image/jpeg", DataSource.DISK)
  }

  private fun fileResult(file: File, mime: String?, source: DataSource): FetchResult =
    SourceFetchResult(
      source =
        ImageSource(
          source = FileSystem.SYSTEM.source(file.toOkioPath()).buffer(),
          fileSystem = FileSystem.SYSTEM,
          metadata = null,
        ),
      mimeType = mime,
      dataSource = source,
    )

  /** 打开网络流（SMB / WebDAV / FTP / AList）。 */
  private suspend fun openStream(): java.io.InputStream {
    val koin = GlobalContext.getOrNull() ?: throw java.io.IOException("Koin 未初始化")
    val repository: app.marlboroadvance.mpvex.repository.NetworkRepository = koin.get()
    val connection =
      repository.getConnectionById(connectionId)
        ?: throw java.io.IOException("连接不存在（可能已删除）")

    // sign 是 AList 的下载签名（其他协议为 null）。AList 的 /d/ 下载口只认它，
    // 缺了会 401 —— 但 AlistClient 内部还有 fs/get 兜底，所以这里传 null 也能取到
    // （只是多一个请求）。见 AlistClient 的类注释。
    return repository.openFileStream(connection, path, sign).getOrElse { e ->
      throw java.io.IOException("照片打开失败: $path", e)
    }
  }

  /** 把流落到临时文件（内存里不保留整张图）。 */
  private suspend fun downloadToTempFile(): File {
    val tempDir = File(context.cacheDir, TEMP_DIR_NAME).apply { if (!isDirectory) mkdirs() }
    val temp = File.createTempFile("thumb_", ".tmp", tempDir)
    return try {
      openStream().use { input ->
        temp.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
      }
      temp
    } catch (t: Throwable) {
      // 半截文件没有任何价值，不删会一直占空间
      temp.delete()
      throw t
    }
  }

  /** 按目标显示尺寸采样解码；解不动返回 null（由调用方降级）。 */
  private fun decodeSampled(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
      }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
  }

  /**
   * 采样边长的目标值（像素）。
   *
   * 这里**故意不用** Coil 的 `Options.size`：它的宽高是 `Dimension` 值类，
   * 内部字段名随版本变过，为了一个「缩略图存多大」
   * 的决策去绑死第三方内部结构不划算。改用「按桶给固定上限」——
   * 桶的语义本来就稳定（网格 / 胶片条），尺寸上限跟着桶走即可。
   */
  private fun maxDecodeEdge(bucket: String): Int =
    if (bucket == NetworkPhotoImageLoader.BUCKET_FILM) FILM_MAX_EDGE_PX else THUMB_MAX_EDGE_PX

  /**
   * 2 的幂次采样率，保证**短边**采样后仍不小于 [maxEdge]。
   *
   * 用短边而不是长边，是因为缩略图一律 `ContentScale.Crop` 成 1:1 ——
   * 决定清晰度的是短边，按长边算会让竖图/横图被过度采样（糊掉）。
   * 同时「仍不小于」保证不会采样过头，这正是「质量不要压太严重」的落点。
   */
  private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (maxEdge <= 0) return 1
    val shortEdge = minOf(width, height)
    var sample = 1
    while (shortEdge / (sample * 2) >= maxEdge) {
      sample *= 2
    }
    return sample
  }

  class Factory(private val context: Context) : Fetcher.Factory<NetworkPhotoRequest> {
    override fun create(
      data: NetworkPhotoRequest,
      options: Options,
      imageLoader: ImageLoader,
    ): Fetcher =
      NetworkPhotoFetcher(
        context = context,
        connectionId = data.connectionId,
        path = data.path,
        sign = data.sign,
        bucket = data.bucket,
      )
  }
}

/**
 * 缩略图下载用的临时目录名（挂在 `cacheDir` 下）。
 *
 * ⚠️ 正常路径用完就删；只有「原图解码失败」那条降级路径会把文件交给 Coil
 *    （Coil 不接管文件生命周期），所以需要启动时统一清理 —— 见
 *    [NetworkPhotoImageLoader.cleanupTempFiles]。
 */
private const val TEMP_DIR_NAME = "net_thumb_tmp"

/**
 * 网格 / 列表缩略图的目标短边（像素）。
 *
 * 3 列布局在 1080p 屏上每格约 360px，这里留到 512 是给「4 列 / 平板 / 折叠屏」
 * 以及后续放大查看留余量 —— 只多占约一倍体积，换的是不会在任何机型上糊。
 */
private const val THUMB_MAX_EDGE_PX = 512

/** 查看器底部胶片条的目标短边（像素）。44dp @3x ≈ 132px，留到 192 同理。 */
private const val FILM_MAX_EDGE_PX = 192
