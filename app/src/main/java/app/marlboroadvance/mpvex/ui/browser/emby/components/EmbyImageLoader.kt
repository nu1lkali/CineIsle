package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Emby 封面图加载器。
 *
 * 项目不引入第三方图片库，这里用已有的 OkHttp + BitmapFactory 实现，
 * 带一层内存 LRU 缓存，避免列表滚动时重复下载同一张封面。
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

  /**
   * 加载图片；失败返回 null。
   *
   * @param maxWidth 客户端解码的目标宽度上限，>0 时按需降采样，避免大图占用过多内存。
   *                 即使服务端已按尺寸返回也建议给一个上限，因为「服务端缩放失败→取原图」
   *                 这条兜底路径拿到的可能是很大的原图。
   */
  suspend fun load(url: String, maxWidth: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
    cache.get(url)?.let { return@withContext it }

    // 先按带尺寸参数的地址取，失败再退回原图。
    // 背景：部分 Emby 服务端对「按 maxWidth/maxHeight 缩放」的请求会返回 500
    //（源图格式特殊 / 缩略图缓存损坏），但直接取原图是正常的。
    val bytes = fetch(url) ?: fetchOriginal(url) ?: return@withContext null

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

  fun clearCache() = cache.evictAll()

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
