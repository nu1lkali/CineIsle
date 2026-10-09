package app.marlboroadvance.mpvex.domain.iso

import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 远程 ISO 的**本地 Range 中转代理**（纯客户端解码路线的一环）。
 *
 * ## 它解决什么
 *
 * [RemoteIsoParser] 已经从远程 ISO 里定位出主影片在镜像内的字节区间。
 * 但 mpv 不能直接去 CDN 上「只读镜像里的第 X 到第 Y 字节」—— CDN 只认整文件的 URL。
 *
 * 于是本类在本地开一个极小的 HTTP 服务：mpv 拿到的地址看起来是个普通的媒体文件，
 * 它发来的 `Range: bytes=A-B` 被翻译成对 CDN 的 `Range: bytes=(X+A)-(X+B)`。
 * **mpv 侧完全不需要知道这是个 ISO**，它以为在播一个普通视频文件。
 *
 * 主影片跨多个片段时（蓝光正片分段是常态），一个虚拟区间会落到多个物理区间上，
 * 此时开多个上游请求并按顺序拼接 —— 见 [SegmentMap]。
 *
 * ## 为什么不复用 NetworkStreamingProxy
 *
 * 那个代理的 `StreamInfo` 以 `NetworkConnection`（host/port/用户名/密码）为中心，
 * 是为 SMB / FTP / WebDAV 设计的；把「已经解析好的完整直链」硬塞进去会很别扭，
 * 而且改动它会牵连已经在用的三条链路。这里另起一个，边界清楚、互不影响。
 *
 * ## 直链过期
 *
 * 网盘 / strm 的直链通常**带有效期**。播放中途过期时，[onExhausted] 回调会被触发，
 * 由上层重新走一次 302 解析、拿到新直链后调 [refreshUrl] 换掉 —— 代理本身不碰
 * Emby 也不关心新地址从哪来。
 */
// internal 而不是 public：public 不得暴露 internal 的参数/返回类型（[SegmentMap]
// 是 internal）。这个类只在 domain/iso 包内使用，没有对外暴露的必要。
internal class IsoRangeProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {

  /**
   * 一路流。
   *
   * ⚠️ [map] 是关键：ISO 内的物理偏移与 mpv 看到的虚拟文件偏移是**两套坐标系**，
   * 所有 Range 换算都要经过它。漏掉这一步的表现是「开头能播，一拖就错位」。
   *
   * 用 [SegmentMap] 而不是单个 `baseOffset + length`：正片跨多个片段时
   * 虚拟→物理的映射不是线性的（见 [SegmentMap] 的类 KDoc）。
   */
  private data class Route(
    /** 虚拟↔物理映射。虚拟文件长度取 [SegmentMap.totalLength]。 */
    val map: SegmentMap,
    /** 当前直链。会过期，需要通过 [refreshUrl] 换掉。 */
    @Volatile var url: String,
    val mimeType: String,
    /** 直链已不可用时的回调（参数 = 期望拿到的新直链）。 */
    val onExhausted: () -> String?,
    /**
     * 最后一次被请求的时刻（[SystemClock.elapsedRealtime]）。
     *
     * 用来自动回收：路由表是单例里的 ConcurrentHashMap，播完一部不会自己清 ——
     * 而每条 Route 都持有那条闭包（连带直链）。放着不管就是 steadily 泄漏。
     * 播放器退出路径太多（正常返回 / 切内核 / 连播下一集 / 划掉任务），
     * 在每个 onDestroy 里挂钩子必然漏一处，所以改成按「多久没人用」来回收。
     */
    @Volatile var lastUsedAt: Long,
  )

  private val routes = ConcurrentHashMap<String, Route>()

  /** token 序号，保证每次注册的路由唯一（见 [register]）。 */
  private val seq = java.util.concurrent.atomic.AtomicInteger(0)

  /**
   * 直链请求客户端。
   *
   * 独立于 Emby 的池子：这里访问的是网盘 / CDN，
   * 鉴权靠直链自带的签名或 Referer，与 Emby 的 token 机制无关。
   * 连接池必须复用 —— 拖进度条会高频发 Range，每次新建连接池会拖垮播放。
   */
  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      // ⚠️ readTimeout 要给足：某些 CDN 在 Range 请求建立后很久才吐第一个字节，
      // 给太短会出现「刚开始播就 IOException」，看起来像源坏了。
      .readTimeout(60, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  companion object {
    private const val TAG = "IsoRangeProxy"

    @Volatile
    private var instance: IsoRangeProxy? = null

    fun getInstance(): IsoRangeProxy =
      instance ?: synchronized(this) {
        instance ?: IsoRangeProxy().also {
          it.start(SOCKET_READ_TIMEOUT, true)
          instance = it
        }
      }

    fun stopInstance() {
      val proxy = synchronized(this) { instance.also { instance = null } } ?: return
      runCatching { proxy.stop() }
        .onFailure { Log.w(TAG, "stop failed", it) }
    }

    /** NanoHTTPD 的读超时（毫秒）。给 0 = 永不超时，Range 长连接需要。 */
    private const val SOCKET_READ_TIMEOUT = 0

    /**
     * 客户端**不带 Range** 时先给它这么多字节（8MB）。
     *
     * 够 mpv 探测出容器、时长、音轨轨数，够它建好 demuxer，
     * 又不会因为「一次性把整部片子拉下来」把网盘打爆。
     */
    private const val WHOLE_WINDOW = 8L * 1024 * 1024

    /**
     * 路由多久没人用就回收（毫秒）。
     *
     * 给 30 分钟：足够一部两三个小时的电影中间暂停 / 换字幕都不受影响，
     * 又保证「看完就退出」不会把 route 和它持有的直链一直留在内存里。
     */
    private const val STALE_ROUTE_MS = 30L * 60 * 1000

    /**
     * 与 [app.marlboroadvance.mpvex.domain.emby.EmbyClient] 保持一致的 UA。
     *
     * ⚠️ 两处必须一致：探测直链（`resolveStreamUrl`）与这里向 CDN 取数据时，
     * 有些签名会把 UA 算进去 —— 探测用一套、下载用另一套 ⇒ 403。
     */
    const val STREAM_UA =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0 Mobile Safari/537.36"
  }

  /**
   * 注册一路 ISO 主影片，返回给 mpv 的本地地址。
   *
   * @param map 主影片的虚拟↔物理映射（来自 [RemoteIsoParser.MainTrack.segments]）
   * @param directUrl 已解析好的直链（302 之后那个）
   * @param onExhausted 直链失效时的回调；返回新直链，或 null 表示这次拉倒
   */
  fun register(
    map: SegmentMap,
    directUrl: String,
    mimeType: String = "video/mp2t",
    onExhausted: () -> String? = { null },
  ): String {
    // ⚠️ token 必须每次唯一：同一张 ISO 连播两次会算出同样的
    // segments 哈希，直接覆盖会让**还在用的旧 mpv 连接**突然拿到
    // 另一条路由（症状是「上一部播到一半花屏」）。所以带一个自增序号。
    val token = "iso${seq.incrementAndGet()}"
    routes[token] =
      Route(
        map = map,
        url = directUrl,
        mimeType = mimeType,
        onExhausted = onExhausted,
        lastUsedAt = SystemClock.elapsedRealtime(),
      )
    Log.d(TAG, "register token=$token segs=${map.segmentCount} len=${map.totalLength}")
    return "http://127.0.0.1:$listeningPort/$token"
  }

  /** 换掉某路的直链（重新 302 解析后调用）。 */
  fun refreshUrl(token: String, newUrl: String): Boolean {
    val route = routes[token] ?: return false
    route.url = newUrl
    Log.d(TAG, "refresh token=$token host=${android.net.Uri.parse(newUrl).host}")
    return true
  }

  fun unregister(token: String) {
    routes.remove(token)
  }

  /**
   * 回收「一段时间没人用」的路由。
   *
   * 挂在 [serve] 开头顺带做，理由：每个请求都会经过这里，
   * 而路由只有在**还有人播**的时候才会被请求 —— 一旦停播就再没请求进来，
   * 所以「有请求就顺便清旧的」正好覆盖到了。
   * 不用另起定时器，也就不会漏掉「用户直接划掉任务」这种没有回调的情况。
   */
  private fun evictStaleRoutes() {
    val now = SystemClock.elapsedRealtime()
    // ⚠️ 不能用 routes[key] 边遍历边删（ConcurrentHashMap 的迭代器
    // 不支持 remove）。先收集再按 key 删。
    val stale =
      routes.entries
        .filter { now - it.value.lastUsedAt > STALE_ROUTE_MS }
        .map { it.key }
    stale.forEach {
      routes.remove(it)
      Log.d(TAG, "evict stale route $it")
    }
  }

  override fun serve(session: IHTTPSession): Response {
    val token = session.uri.removePrefix("/").substringBefore('?')
    val route = routes[token]
      ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no such iso route")

    route.lastUsedAt = SystemClock.elapsedRealtime()
    evictStaleRoutes()

    val total = route.map.totalLength
    val rangeHeader = session.headers["range"]

    // ⚠️ 「没有 Range」与「Range 不合法」必须分开处理：
    // 把不合法当没有 ⇒ 对着几十 GB 的主影片从头开流，而 mpv 要的是文件尾那一段，
    // 表现是「一拖进度条就从头开始缓冲」。
    if (rangeHeader.isNullOrBlank()) return respondWhole(session, route, total)

    val range =
      parseRange(rangeHeader, total)
        ?: return newFixedLengthResponse(
          Response.Status.RANGE_NOT_SATISFIABLE,
          MIME_PLAINTEXT,
          "range not satisfiable",
        )

    return try {
      val (from, to) = range
      respondRange(session, route, from, to)
    } catch (e: Exception) {
      Log.e(TAG, "serve range failed token=$token", e)
      newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "proxy error: ${e.message}",
      )
    }
  }

  /**
   * 解析 `Range: bytes=start-end`。
   *
   * @return `(起始, 结束)`；**无 Range 或格式不认识时返回 null**（调用方走整段读）。
   */
  private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
    if (header == null || !header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=").substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
    // 末尾开放（`bytes=N-`）是拖进度条时最常见的形态，必须支持。
    val end = spec.substring(dash + 1).trim().let {
      if (it.isEmpty()) total - 1 else it.toLongOrNull() ?: return null
    }
    if (start >= total || start < 0) return null
    return start to end.coerceAtMost(total - 1)
  }

  /**
   * 没有任何 Range 时的回应。
   *
   * 这里**只发一段窗口**（[WHOLE_WINDOW]），而不是从头一路拉到片尾：
   * 主影片动辄几十 GB，一次性全量会把 CDN 打死、也会把手机撑爆。
   * 用 `206 + Content-Range` 把完整长度告诉 mpv，它自己会接着发 Range 往后要 ——
   * 这正是 mpv 打开 http 源时的标准行为。
   */
  private fun respondWhole(session: IHTTPSession, route: Route, total: Long): Response {
    val end = minOf(total, WHOLE_WINDOW) - 1
    if (end <= 0) {
      return newFixedLengthResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        MIME_PLAINTEXT,
        "empty track",
      )
    }
    return try {
      val (from, to) = 0L to end
      respondRange(session, route, from, to)
    } catch (e: Exception) {
      Log.e(TAG, "serve head failed", e)
      newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "proxy error: ${e.message}",
      )
    }
  }

  /**
   * 核心：把虚拟区间翻译成 ISO 内的物理区间再向 CDN 要。
   *
   * 跨片段时（正片被切成多段）会开多个上游请求，
   * 按 [SegmentMap.physicalRanges] 返回的顺序用 [SequenceInputStream] 串起来 ——
   * 顺序错乱就是画面错位，所以**严格按返回顺序**追加，不做任何重排。
   */
  private fun respondRange(session: IHTTPSession, route: Route, vStart: Long, vEnd: Long): Response {
    val length = vEnd - vStart + 1
    val pieces = route.map.physicalRanges(vStart, vEnd)
    // physicalRanges 只在越界时返回空。走到这里说明映射与 parseRange 的判断不一致 ——
    // 如实回 416，不能当成「没有 Range」从头开流。
    if (pieces.isEmpty()) {
      return newFixedLengthResponse(
        Response.Status.RANGE_NOT_SATISFIABLE,
        MIME_PLAINTEXT,
        "range outside mapped segments",
      )
    }

    val body = openAll(route, pieces)
    if (body == null) {
      // 直链多半是过期了。给上层一次机会换新直链再重试一次；
      // 换不到就如实回 503 —— mpv 会报「打不开」，比静默黑屏好。
      val renewed =
        try {
          route.onExhausted()
        } catch (e: Exception) {
          Log.w(TAG, "onExhausted threw", e)
          null
        }
      val retry =
        renewed?.let {
          route.url = it
          Log.d(TAG, "retry with renewed url for token")
          openAll(route, pieces)
        }
      if (retry == null) {
        return newFixedLengthResponse(
          Response.Status.SERVICE_UNAVAILABLE,
          MIME_PLAINTEXT,
          "upstream unavailable (link likely expired)",
        )
      }
      return respondWithBody(route, retry, vStart, vEnd, length)
    }
    return respondWithBody(route, body, vStart, vEnd, length)
  }

  /**
   * 打开所有片段的流并按顺序拼接。
   *
   * ⚠️ 任何一段拿不到就整体返回 null（并关掉已打开的那些）：
   * 半截数据喂给 mpv 会花屏/音画不同步，比直接报错难查一个量级。
   */
  private fun openAll(route: Route, pieces: List<PhysicalRange>): InputStream? {
    if (pieces.size == 1) return openRemote(route, pieces[0].isoOffset, pieces[0].length)
    val opened = ArrayList<InputStream>(pieces.size)
    for (p in pieces) {
      val s = openRemote(route, p.isoOffset, p.length)
      if (s == null) {
        opened.forEach { runCatching { it.close() } }
        return null
      }
      opened += s
    }
    return SequenceInputStream(java.util.Collections.enumeration(opened))
  }

  private fun respondWithBody(
    route: Route,
    body: InputStream,
    vStart: Long,
    vEnd: Long,
    length: Long,
  ): Response {
    val resp =
      newFixedLengthResponse(
        Response.Status.PARTIAL_CONTENT,
        route.mimeType,
        body,
        length,
      )
    resp.addHeader("Accept-Ranges", "bytes")
    // Content-Range 用**虚拟文件**的坐标，不是 ISO 内的绝对坐标 ——
    // mpv 只认它来校验自己请求的区间是否被正确满足。
    resp.addHeader("Content-Range", "bytes $vStart-$vEnd/${route.map.totalLength}")
    return resp
  }

  /**
   * 向 CDN 请求从 [isoOffset] 起的 [length] 个字节，失败返回 null。
   *
   * ⚠️ 返回的流**恰好**是那一段、且不会多给一个字节 ——
   * 上游用 `newFixedLengthResponse` 按请求长度声明了 `Content-Length`，
   * 实际字节数一旦对不上，mpv 会把这一个连接的数据错接到下一个区间上（花屏/音画不同步）。
   *
   * 参数刻意用 `(offset, length)` 而不是 `(start, end)`：
   * 「闭区间的 end」与「字节数」差 1，传错一次就是一个字节的错位，非常难查。
   */
  private fun openRemote(route: Route, isoOffset: Long, length: Long): InputStream? {
    if (length <= 0L) return null
    val isoEnd = isoOffset + length - 1
    return runCatching {
      val req =
        Request.Builder()
          .url(route.url)
          .header("Range", "bytes=$isoOffset-$isoEnd")
          // 探测阶段解析直链时用过同样的 UA（见 EmbyClient.resolveStreamUrl）——
          // 这里必须一致，否则某些签名把 UA 算进去的 CDN 会回 403。
          .header("User-Agent", STREAM_UA)
          .get()
          .build()
      val resp = client.newCall(req).execute()
      when (resp.code) {
        // 206 = 按 Range 返回（正常路径）
        206 -> resp.body.byteStream().bounded(length)
        // 200 = 忽略了 Range 从头给了全量：跳掉前缀、并在读满目标长度后截断。
        // 不截断的话会把片尾之后的东西也喂给 mpv。
        200 -> resp.body.byteStream().skipFully(isoOffset).bounded(length)
        else -> {
          Log.w(TAG, "upstream ${resp.code} for ${route.url.take(60)}")
          resp.close()
          null
        }
      }
    }.getOrNull()
  }
}

/**
 * 把流限制在最多 [limit] 个字节。
 *
 * NanoHTTPD 按声明长度读，读满就停；这里提前截断是为了让「上游多给了」也不会
 * 泄漏到下一个响应里（连接复用时多出的字节会被当成下一段响应的开头）。
 *
 * internal 而非 private：这两个字节流工具是整条链路上最容易错、错了不报错
 * 只会花屏的地方，必须能被单测直接覆盖（见 `IsoStreamTest`）。
 */
internal fun InputStream.bounded(limit: Long): InputStream {
  // ⚠️ limit <= 0 必须给**空流**，不能原样返回 `this` ——
  // 那等于「不限制」，与「最多 N 个字节」的语义正好相反：
  // 上游多给的字节会全部漏给 mpv，下一段响应就从错位的地方开始（花屏）。
  // 单测 `bounded 长度为 0 时不返回任何字节` 就是盯这一条。
  if (limit <= 0) return ByteArray(0).inputStream()
  var remaining = limit
  return object : InputStream() {
    override fun read(): Int {
      if (remaining <= 0) return -1
      val b = this@bounded.read()
      if (b >= 0) remaining--
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (remaining <= 0) return -1
      val toRead = minOf(len.toLong(), remaining).toInt()
      val n = this@bounded.read(b, off, toRead)
      if (n > 0) remaining -= n
      return n
    }

    override fun available(): Int = minOf(this@bounded.available().toLong(), remaining).toInt()
  }
}

/**
 * 顺序流跳过前 [n] 个字节。
 *
 * ⚠️ 跳不完就**抛异常**，不返回短流：上游明明给了 200 全量却比 [n] 还短，
 * 说明它给的根本不是我们要的那份数据。继续往下读只会把错位的字节喂给 mpv，
 * 表现是花屏 —— 比直接报错难查得多。
 *
 * internal 而非 private：理由同 [bounded]。
 */
internal fun InputStream.skipFully(n: Long): InputStream {
  if (n <= 0) return this
  var remaining = n
  val buf = ByteArray(64 * 1024)
  while (remaining > 0) {
    val want = minOf(remaining, buf.size.toLong()).toInt()
    val read = read(buf, 0, want)
    if (read <= 0) throw IOException("流比预期的短 $n 字节，跳不动了")
    remaining -= read
  }
  return this
}
