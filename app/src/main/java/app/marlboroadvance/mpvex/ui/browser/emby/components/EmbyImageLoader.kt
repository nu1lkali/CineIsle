package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.emby.EmbyHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Emby 封面图加载器。
 *
 * 项目不引入第三方图片库，这里用已有的 OkHttp + BitmapFactory 实现，三层结构：
 *
 *  1. 内存 LRU（进程内，列表滚动不重复解码）；
 *  2. **磁盘缓存**（[diskDir]/<sha256(url)>.img，TTL [DISK_TTL_MS]，容量上限
 *     [DISK_LIMIT_BYTES]，超限按最旧淘汰）—— 跨启动复用，减少 Emby 服务端的
 *     图片缩放开销（每个封面都是一次服务端缩放 + 网络传输）；
 *  3. 网络（OkHttp，缩放失败自动退回原图重试）。
 *
 * 磁盘层需要 [init] 注入 Context（App.onCreate 里调一次）；未 init 时磁盘层静默
 * 关闭，行为退化为纯内存缓存。
 */
object EmbyImageLoader {
  private const val TAG = "EmbyImageLoader"

  /**
   * 走 [EmbyHttp.images]（图片档：超时短、并发高，且与其余 5 个客户端**共享连接池**）。
   *
   * 原来这里自建一个（connect 10s / read 20s），现在档位挪到 [EmbyHttp.images] 统一管。
   */
  private val client: OkHttpClient = EmbyHttp.images

  /** 缓存上限取可用堆内存的 1/8 */
  private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtMost(Int.MAX_VALUE),
  ) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  // ── 磁盘缓存 ──

  /** 磁盘缓存目录名（cacheDir 下） */
  private const val DISK_DIR_NAME = "emby_image_cache"

  /** 磁盘缓存容量上限 */
  private const val DISK_LIMIT_BYTES = 256L * 1024 * 1024

  /** 缓存失效时间：7 天（以文件修改时间 ≈ 下载时间为准） */
  private const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000

  @Volatile
  private var diskDir: File? = null

  /** App.onCreate 注入 Context，启用磁盘缓存层 */
  fun init(context: Context) {
    diskDir = File(context.cacheDir, DISK_DIR_NAME)
  }

  /** URL → 磁盘文件名（sha256，避免 URL 里的非法字符 / 过长路径） */
  private fun diskKeyOf(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") {
      "%02x".format(it)
    } + ".img"

  /** 磁盘缓存当前占用（字节），设置页展示用 */
  fun diskCacheSizeBytes(): Long =
    diskDir?.listFiles()?.sumOf { it.length() } ?: 0L

  /** 清空磁盘缓存 + 内存缓存，返回清除的字节数 */
  fun clearDiskCache(): Long {
    var freed = 0L
    diskDir?.listFiles()?.forEach { f ->
      freed += f.length()
      runCatching { f.delete() }
    }
    cache.evictAll()
    return freed
  }

  /** 容量超限时按「最旧优先」淘汰，直到回到上限以内 */
  private fun trimDisk(dir: File) {
    val files = dir.listFiles() ?: return
    var total = files.sumOf { it.length() }
    if (total <= DISK_LIMIT_BYTES) return
    files.sortedBy { it.lastModified() }.forEach { f ->
      if (total <= DISK_LIMIT_BYTES) return
      val len = f.length()
      if (f.delete()) total -= len
    }
  }

  // ── 下载节流 ──

  /**
   * 同时进行的封面下载上限。
   *
   * 照片库动辄 10 万张，Lazy 网格滚动时若不限并发，会瞬间向 Emby 服务器打出
   * 几十个图片请求（每个都是一次服务端缩放 + 传输），小服务器直接被打满。
   * 4 同时落在 [EmbyHttp.images] 连接池与 OkHttp 默认 maxRequestsPerHost=5 之内。
   */
  private const val MAX_CONCURRENT_DOWNLOADS = 4

  /** 下载并发闸门 */
  private val downloadGate = Semaphore(MAX_CONCURRENT_DOWNLOADS)

  /**
   * URL → 在途下载（同一 URL 的并发请求合并成一次网络往返）。
   *
   * 典型场景：快速回滑 / 重组导致同一张卡片在第一次下载完成前再次请求，
   * 后来者直接等待已有那次下载，不会重复打服务器。
   */
  private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

  /**
   * 加载图片；失败返回 null。
   *
   * @param maxWidth 客户端解码的目标宽度上限，>0 时按需降采样，避免大图占用过多内存。
   *                 即使服务端已按尺寸返回也建议给一个上限，因为「服务端缩放失败→取原图」
   *                 这条兜底路径拿到的可能是很大的原图。
   */
  suspend fun load(url: String, maxWidth: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
    cache.get(url)?.let { return@withContext it }

    val diskKey = diskKeyOf(url)
    val diskFile = diskDir?.let { File(it, diskKey) }
    // 磁盘命中且未过期：直接解码（过期/损坏则当未命中，走网络重新下载覆盖）
    if (diskFile != null && diskFile.isFile &&
      System.currentTimeMillis() - diskFile.lastModified() <= DISK_TTL_MS
    ) {
      val bytes = runCatching { diskFile.readBytes() }.getOrNull()
      if (bytes != null) {
        decode(bytes, maxWidth)?.let { bmp ->
          cache.put(url, bmp)
          return@withContext bmp
        }
      }
    }

    // 先按带尺寸参数的地址取，失败再退回原图。
    // 背景：部分 Emby 服务端对「按 maxWidth/maxHeight 缩放」的请求会返回 500
    //（源图格式特殊 / 缩略图缓存损坏），但直接取原图是正常的。
    val bytes = downloadBytes(url) ?: return@withContext null

    // 合并下载时，先完成的协程可能已经解码入缓存（同 URL 同尺寸的常见情形）
    cache.get(url)?.let { return@withContext it }

    // 落盘（失败不影响本次展示）+ 容量维护
    if (diskFile != null) {
      runCatching {
        diskFile.parentFile?.mkdirs()
        diskFile.writeBytes(bytes)
        trimDisk(diskFile.parentFile)
      }
    }

    val bitmap = decode(bytes, maxWidth)
    if (bitmap == null) {
      Log.w(TAG, "封面解码失败（bytes=${bytes.size}）: $url")
      return@withContext null
    }
    cache.put(url, bitmap)
    bitmap
  }

  /**
   * 取图片字节（带节流）：同一 URL 合并为一次下载，下载数受 [downloadGate] 限制。
   *
   * 等待者**不占**信号量（先在 [inFlight] 等，等不到才自己去下载）——
   * 否则「等下载的人占着坑、下载的人没坑可用」会死锁。
   */
  private suspend fun downloadBytes(url: String): ByteArray? {
    inFlight[url]?.let { return it.await() }
    val deferred = CompletableDeferred<ByteArray?>()
    val existing = inFlight.putIfAbsent(url, deferred)
    if (existing != null) return existing.await()
    try {
      val bytes = downloadGate.withPermit {
        withContext(Dispatchers.IO) { fetch(url) ?: fetchOriginal(url) }
      }
      deferred.complete(bytes)
      return bytes
    } catch (e: CancellationException) {
      // 发起方被取消（滑走了）：别让等待者吊死在永不完结的 Deferred 上
      deferred.complete(null)
      throw e
    } finally {
      inFlight.remove(url)
    }
  }

  /** 发起一次请求取字节；失败返回 null（内部已打日志）。 */
  private fun fetch(url: String): ByteArray? = runCatching {
    client.newCall(buildRequest(url)).execute().use { response ->
      if (!response.isSuccessful) {
        // 400/401/403 多为鉴权问题，404 多为确实没有这张图，5xx 多为服务端处理失败
        Log.w(TAG, "加载封面失败 HTTP ${response.code}: $url")
        return@use null
      }
      response.body.bytes()
    }
  }.onFailure { e ->
    Log.w(TAG, "加载封面异常: ${e.message} | $url")
  }.getOrNull()

  /**
   * 兜底：去掉 maxWidth / maxHeight / quality 再取一次原图。
   *
   * 有些 Emby 服务端只在「需要缩放」时才失败（500），取原图反而是好的；
   * 客户端 [decode] 会做降采样，所以取原图没有额外代价。
   */
  private fun fetchOriginal(url: String): ByteArray? {
    val raw = stripResizeParams(url)
    if (raw == url) return null
    Log.w(TAG, "改用原图重试: $raw")
    return fetch(raw)
  }

  /** 去掉图片缩放相关的 query 参数；没有这些参数时原样返回。 */
  private fun stripResizeParams(url: String): String {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return url
    val parts = query.split('&')
    val kept = parts.filterNot { param ->
      param.startsWith("maxWidth=") ||
        param.startsWith("maxHeight=") ||
        param.startsWith("quality=")
    }
    if (kept.size == parts.size) return url
    val base = url.substringBefore('?')
    return if (kept.isEmpty()) base else "$base?" + kept.joinToString("&")
  }

  private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
    // HEIC/AVIF（iPhone 照片常见）BitmapFactory 解不了，走 ImageDecoder；
    // 仍然解不了就返回 null，由调用方改用服务端 format=jpg 兜底
    sniffModernMime(bytes)?.let { mime -> return decodeModern(bytes, mime, maxWidth) }
    if (maxWidth <= 0) {
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // 先只解析边界拿到原始尺寸，再按目标宽度计算采样率
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val width = bounds.outWidth
    if (width <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxWidth) {
      sampleSize *= 2
    }

    return BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
  }

  // ── HEIC / AVIF（现代图片格式） ──

  /**
   * 识别 HEIC / AVIF（ISO-BMFF 容器，ftyp box 的 brand 字段）。
   *
   * iPhone 拍的照片默认就是 HEIC；Emby 扫库后按原格式存，取回来的字节流不是
   * JPEG/PNG。返回 `image/heic` / `image/avif`；不是这两种返回 null
   * （JPEG/PNG/WebP/GIF 一概不认，走原有 BitmapFactory 路径，零改动）。
   */
  private fun sniffModernMime(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
      bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
    ) {
      return null
    }
    // 兼容 brand 列表是一串 4 字节 chunk，从偏移 8 扫到 64 足够覆盖常见文件
    var isAvif = false
    var isHeic = false
    var i = 8
    val limit = minOf(bytes.size, 64)
    while (i + 4 <= limit) {
      when (String(bytes, i, 4, Charsets.US_ASCII)) {
        "avif", "avis" -> isAvif = true
        "heic", "heix", "hevc", "hevx", "mif1", "mif2", "msf1" -> isHeic = true
      }
      i += 4
    }
    return when {
      isAvif -> "image/avif"
      isHeic -> "image/heic"
      else -> null
    }
  }

  /**
   * 用 [ImageDecoder] 解 HEIC/AVIF（API 28+ 原生支持 HEIF；AVIF 要 API 31+）。
   *
   * 不支持 / 解失败一律返回 null —— 照片链路的调用方（[EmbyImage] 的
   * fallbackUrl）会改用服务端 `format=jpg` 重新取图，失败不会暴露给用户。
   */
  private fun decodeModern(bytes: ByteArray, mime: String, maxWidth: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      !ImageDecoder.isMimeTypeSupported(mime)
    ) {
      return null
    }
    return try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        // setTargetSize 会放大图，只在原图确实更大时缩
        if (maxWidth > 0 && info.size.width > maxWidth) {
          val height = (maxWidth.toLong() * info.size.height / info.size.width).toInt()
          decoder.setTargetSize(maxWidth, height.coerceAtLeast(1))
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "$mime 解码失败（${e.message}）")
      null
    }
  }

  /**
   * 把服务端返回的图片字节转码成 JPEG（「另存为 JPEG」用）。
   *
   * 已是 JPEG 的调用方应直接写原字节（见 [isJpeg]），不必过这里。解码失败返回 null。
   *
   * @param rotationDegrees 顺时针旋转角度（90 的整数倍）。浏览器里用户转过图之后
   *   「另存为 JPEG」要存成转过的样子，所以转码时一并旋转；0 表示不转。
   */
  suspend fun transcodeToJpeg(bytes: ByteArray, rotationDegrees: Int = 0): ByteArray? =
    withContext(Dispatchers.IO) {
      val decoded = decode(bytes, 0) ?: return@withContext null
      val bitmap = rotateBitmap(decoded, rotationDegrees)
      val out = ByteArrayOutputStream()
      runCatching {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
      }.getOrNull()
    }

  /**
   * 按 90° 的整数倍旋转位图；0（或非 90 倍数归一化后为 0）时**原样返回不复制**。
   *
   * 刻意不 recycle 原图：调用方那边可能还有引用（宁可多占一次内存，
   * 也别整出「用已回收的 Bitmap」这种偶发崩溃）。
   */
  private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return src
    val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
    return runCatching {
      Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }.getOrNull() ?: src
  }

  /** Magic bytes 判定是否已是 JPEG（FF D8 FF）；「另存为 JPEG」时省一次转码。 */
  fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
      bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

  /**
   * 构造图片请求。
   *
   * 图片地址形如 `.../Images/Primary?maxWidth=480&quality=90&api_key=xxx`，
   * 项目里 JSON 接口走的是 `X-Emby-Token` header，而图片/流地址只带 `api_key` query 参数。
   * 不同 Emby 服务端对两者接受程度不一致，这里把 query 里的 token 再作为 header 发一份，
   * 两种鉴权方式同时满足，避免「列表能加载、封面全空白」的情况。
   */
  private fun buildRequest(url: String): Request {
    val builder = Request.Builder().url(url)
    apiKeyOf(url)?.let { builder.header("X-Emby-Token", it) }
    return builder.build()
  }

  /** 从 URL 的 query 中取出 api_key（可能经过 URL 编码）。 */
  private fun apiKeyOf(url: String): String? {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    return query
      .split('&')
      .firstOrNull { it.startsWith("api_key=") }
      ?.substringAfter('=')
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
  }
}
