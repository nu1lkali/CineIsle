package app.marlboroadvance.mpvex.domain.iso

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 用 HTTP Range 实现 [RemoteIsoParser.RangeReader]，把 CDN 直链接给ISO 解析器。
 *
 * ## 为什么解析要走网络
 *
 * 解析 ISO 目录只需要读几个扇区（PVD + 两三层目录项，量级是几十 KB），
 * 但这些扇区**散布在整张镜像的随机位置**。所以这里的每一次 [readAt]
 * 都是一次独立的 HTTP Range 请求，不存在「顺序读完一遍」这种省事路径。
 *
 * 探测阶段的请求量很小（见 `RemoteIsoParser` 的读放大测试：≤ 8 次），
 * 真正的大流量在 [IsoRangeProxy] 那边的正式播放。
 *
 * ## 直链会过期
 *
 * CDN 直链带签名，几分钟就失效。构造时传入 [urlProvider] 而不是固定字符串，
 * 每次真正发请求前都问一次要最新的 —— 这样解析阶段横跨签名过期也能自动续上，
 * 不用把整个流程重跑一遍。
 */
internal class HttpRangeReader(
  private val urlProvider: () -> String?,
  private val userAgent: String,
  private val client: OkHttpClient = defaultClient(),
) : RemoteIsoParser.RangeReader {

  @Volatile private var cachedTotal: Long = -1L

  override fun readAt(offset: Long, length: Long): ByteArray {
    if (offset < 0 || length < 0) return ByteArray(0)
    val url = urlProvider() ?: return ByteArray(0)
    if (length == 0L) return ByteArray(0)
    val start = offset.coerceAtLeast(0L)
    val end =
      if (length == Long.MAX_VALUE) Long.MAX_VALUE
      else {
        val e = start + length - 1
        // 溢出保护：length 大到离谱时宁可读满到文件尾，也不要算出一个负数区间。
        if (e < start) Long.MAX_VALUE else e
      }

    val request =
      Request.Builder()
        .url(url)
        .header("Range", "bytes=$start-$end")
        // 必须与播放器 / 代理层用**同一套 UA**：很多 CDN 把 UA 算进签名，
        // 探测时换个 UA 就会拿到 403 —— 这个坑 `EmbyClient.resolveStreamUrl` 踩过。
        .header("User-Agent", userAgent)
        .get()
        .build()

    client.newCall(request).execute().use { resp ->
      val body = resp.body ?: return ByteArray(0)
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for range $start-$end")

      // 200 = CDN 忽略 Range 直接给了全量。解析阶段能容忍（自己跳前缀），
      // 但要记下总长，别让后面每次探测都重下一次全量。
      if (resp.code == 200) {
        val whole = body.bytes()
        cachedTotal = whole.size.toLong()
        val from = start.coerceAtMost(whole.size.toLong()).toInt()
        val to = (if (end == Long.MAX_VALUE) whole.size.toLong() else end + 1)
          .coerceAtMost(whole.size.toLong())
          .toInt()
        return if (to > from) whole.copyOfRange(from, to) else ByteArray(0)
      }

      val bytes = body.bytes()
      // 206 的 Content-Range: bytes 0-999/123456 —— 总长就藏在这里。
      cachedTotal = parseContentRangeTotal(resp.header("Content-Range")) ?: cachedTotal
      if (bytes.isEmpty()) throw IOException("空响应 range $start-$end")
      return bytes
    }
  }

  override fun totalSize(): Long {
    if (cachedTotal > 0) return cachedTotal
    val url = urlProvider() ?: return -1L
    // 用 1 字节的 Range 探总长：比 HEAD 可靠 —— 有些 CDN 对 HEAD 返回 405 或不给 Content-Length。
    val request =
      Request.Builder()
        .url(url)
        .header("Range", "bytes=0-0")
        .header("User-Agent", userAgent)
        .get()
        .build()
    client.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) return -1L
      cachedTotal =
        parseContentRangeTotal(resp.header("Content-Range"))
          ?: resp.header("Content-Length")?.toLongOrNull()
          ?: -1L
    }
    return cachedTotal
  }

  /** 从 `bytes 0-1023/7654321` 里取出 `7654321`；格式不符返回 null（不猜）。 */
  private fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) return null
    val slash = contentRange.lastIndexOf('/')
    if (slash < 0 || slash == contentRange.length - 1) return null
    return contentRange.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0 }
  }

  companion object {
    /**
     * 探测用的 client：短超时 + 允许重试。
     *
     * 这里和 [IsoRangeProxy] 刻意用不同配置 —— 探测是「几十 KB 的小请求，越快失败越好」，
     * 播放是「长时间大流量，绝不能因为慢就断」。混用会让播放因探测策略被误伤。
     */
    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
  }
}