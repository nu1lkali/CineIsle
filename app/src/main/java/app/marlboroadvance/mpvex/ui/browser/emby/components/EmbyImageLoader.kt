package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
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

  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

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
    val bytes = fetch(url) ?: fetchOriginal(url) ?: return@withContext null

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
