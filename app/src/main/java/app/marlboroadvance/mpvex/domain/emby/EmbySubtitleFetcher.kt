package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleFormats
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次字幕取字节的结果。失败也要能说清「哪一环挂了」（需求 9 / 10）。 */
sealed interface SubtitleFetchResult {
  data class Ok(
    val bytes: ByteArray,
    /** 实际拿到的格式（可能低于首选格式，例如 ASS 回退成 SRT） */
    val format: String,
    /** 是否走了服务端给的 `DeliveryUrl` */
    val viaDeliveryUrl: Boolean,
    /** 是否发生了格式降级（要提示「样式可能丢失」） */
    val degraded: Boolean,
    val elapsedMs: Long,
  ) : SubtitleFetchResult {
    val sizeBytes: Int get() = bytes.size
  }

  data class Failed(
    val reason: String,
    val httpCode: Int = 0,
  ) : SubtitleFetchResult
}

/**
 * 把一条 Emby 字幕**取成字节**。
 *
 * ## 顺序（不许反过来）
 *
 * 1. 服务端给了 `DeliveryUrl` 就**先走它** —— 那是 Emby 对这条字幕交付方式的定义，
 *    可能是转换后的地址、也可能挂着鉴权参数，自己拼 URL 必然有猜错的可能（需求 3.3）；
 * 2. 没有（或那条路失败）才用官方字幕端点
 *    `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}`，
 *    按格式候选依次尝试 —— ASS 先要 `ass` 保住样式，404 再退 `srt`。
 *
 * ## 不做的事
 *
 * · **不把图像字幕当文本下**：候选为空时直接失败（`unsupported` 由上层展示，不属于下载失败）；
 * · **不无限读**：单条字幕上限 [MAX_SUBTITLE_BYTES]，防止服务端返回整段视频；
 * · **不把失败抛出去**：视频播放不该被字幕拖垮。
 *
 * 鉴权走 `X-Emby-Token` 头（与项目其余 API 一致），**日志里绝不打印 URL 的 query**
 * —— 里面有 `api_key`。
 */
object EmbySubtitleFetcher {
  /** 单条字幕体积上限。真实字幕最大也就几 MB，超过一定是拿错了东西。 */
  private const val MAX_SUBTITLE_BYTES = 16 * 1024 * 1024

  suspend fun fetch(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    subtitle: SubtitleInfo,
  ): SubtitleFetchResult = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    // ⚠️ 优先用**字幕自己所属的**媒体源：`SubtitleInfo` 是发现阶段按实际源标注的；
    //    调用方传进来的 mediaSourceId 只作为兜底（服务端没回 Id 时才用得上）。
    //    反过来的话，一旦「指定源不存在、退回首个源」发生，就会拿 A 源的索引去 B 源取字幕。
    val msId = subtitle.mediaSourceId?.takeIf { it.isNotBlank() }
      ?: mediaSourceId?.takeIf { it.isNotBlank() }
    if (msId.isNullOrBlank()) {
      return@withContext SubtitleFetchResult.Failed("缺少媒体源 Id，无法请求字幕")
    }
    val formats = subtitle.externalRequestFormats
    if (formats.isEmpty()) {
      return@withContext SubtitleFetchResult.Failed(
        subtitle.unsupportedReason ?: "这条字幕没有可下载的格式",
      )
    }

    // ── 1. 服务端给的交付地址 ──
    val delivery = subtitle.deliveryUrl?.takeIf { it.isNotBlank() }
    if (delivery != null) {
      val url = absoluteDeliveryUrl(server, delivery)
      val result = fetchBytes(server, url)
      if (result is SubtitleFetchResult.Ok) {
        val format = EmbySubtitleFormats.normalizeFormat(subtitle.format) ?: formats.first()
        return@withContext result.copy(
          format = format,
          viaDeliveryUrl = true,
          elapsedMs = System.currentTimeMillis() - startedAt,
        )
      }
      EmbyLog.warn(
        "字幕交付地址取字节失败（${subtitle.describe()}，${(result as SubtitleFetchResult.Failed).reason}），" +
          "退回官方字幕端点",
      )
    }

    // ── 2. 官方字幕端点，按格式候选依次试 ──
    var lastReason = "未知错误"
    var lastCode = 0
    formats.forEachIndexed { position, format ->
      val path = EmbyPaths.videoSubtitleStream(itemId, msId, "${subtitle.streamIndex}", format)
      val result = fetchBytesWithToken(server, path)
      when (result) {
        is SubtitleFetchResult.Ok -> {
          val degraded = position > 0 ||
            (subtitle.sourceType == SubtitleSourceType.EXTERNAL && format != formats.first())
          if (degraded) {
            EmbyLog.warn(
              "字幕格式降级：${subtitle.describe()} 首选 ${formats.first()} 失败，改用 $format" +
                "（ASS/SSA 的字体、颜色、定位与描边会丢失）",
            )
          }
          return@withContext result.copy(
            format = format,
            degraded = degraded,
            elapsedMs = System.currentTimeMillis() - startedAt,
          )
        }
        is SubtitleFetchResult.Failed -> {
          lastReason = result.reason
          lastCode = result.httpCode
          // 404/400 = 这个目标格式服务端不支持，继续试下一个；
          // 其它（401/403/5xx）换格式也没用，早停省一次往返。
          if (result.httpCode != 0 && result.httpCode !in RETRY_NEXT_FORMAT_CODES) {
            return@withContext SubtitleFetchResult.Failed(
              "${result.reason}（格式 $format）",
              result.httpCode,
            )
          }
        }
      }
    }
    SubtitleFetchResult.Failed(lastReason, lastCode)
  }

  /** 「换个目标格式也许会成功」的状态码 */
  private val RETRY_NEXT_FORMAT_CODES = setOf(400, 404, 415, 501)

  private fun fetchBytesWithToken(server: EmbyServer, path: String): SubtitleFetchResult =
    runCatching {
      val request = EmbyClient.authedRequest(server, path).get().build()
      execute(request)
    }.getOrElse { t -> SubtitleFetchResult.Failed(t.message ?: "网络异常") }

  private fun fetchBytes(server: EmbyServer, url: String): SubtitleFetchResult =
    runCatching {
      val builder = okhttp3.Request.Builder().url(url)
      // DeliveryUrl 有时自带 api_key、有时什么都没有 —— 两种都补上同值 token 头，
      // 服务端两种鉴权方式都认（与 EmbyClient.authedRequest 一致）
      val token = extractToken(url)?.takeIf { it.isNotBlank() } ?: server.apiToken
      if (token.isNotEmpty()) builder.header("X-Emby-Token", token)
      execute(builder.get().build())
    }.getOrElse { t -> SubtitleFetchResult.Failed(t.message ?: "网络异常") }

  private fun execute(request: okhttp3.Request): SubtitleFetchResult {
    EmbyClient.httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        return SubtitleFetchResult.Failed(describeHttpFailure(response.code), response.code)
      }
      val declared = response.body.contentLength()
      if (declared > MAX_SUBTITLE_BYTES) {
        return SubtitleFetchResult.Failed("字幕体积异常（${declared / 1024} KB），已放弃")
      }
      val bytes = response.body.bytes()
      if (bytes.isEmpty()) return SubtitleFetchResult.Failed("服务端返回了空字幕", response.code)
      if (bytes.size > MAX_SUBTITLE_BYTES) {
        return SubtitleFetchResult.Failed("字幕体积异常（${bytes.size / 1024} KB），已放弃")
      }
      return SubtitleFetchResult.Ok(
        bytes = bytes,
        format = EmbySubtitleFormats.normalizeFormat(request.url.encodedPath.substringAfterLast('.'))
          ?: "srt",
        viaDeliveryUrl = false,
        degraded = false,
        elapsedMs = 0,
      )
    }
  }

  private fun describeHttpFailure(code: Int): String = when (code) {
    400 -> "服务端不接受这个字幕格式（HTTP 400）"
    401 -> "登录状态已失效，请重新登录 Emby（HTTP 401）"
    403 -> "没有这条字幕的访问权限（HTTP 403）"
    404 -> "服务端没有这条字幕的该格式（HTTP 404）"
    429 -> "服务端限流，稍后再试（HTTP 429）"
    in 500..599 -> "Emby 服务端错误（HTTP $code）"
    else -> "字幕下载失败（HTTP $code）"
  }

  /** `DeliveryUrl` 可能是相对路径（`/Videos/...`）；补成绝对地址。 */
  fun absoluteDeliveryUrl(server: EmbyServer, raw: String): String {
    val url = raw.trim()
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
      return url
    }
    val path = if (url.startsWith("/")) url else "/$url"
    // 与 EmbyClient.authedRequest 同口径：走端点解析器（多地址/故障转移），
    // 服务端返回的相对地址不带 /emby 前缀，这里补上
    return EmbyHttpCore.absolute(server, path)
  }

  /** 从 URL 里取 `api_key`（只用于补 header，**不用于打日志**）。 */
  private fun extractToken(url: String): String? =
    Regex("[?&]api_key=([^&]+)").find(url)?.groupValues?.getOrNull(1)

  /**
   * 文本字幕的**轻量完整性校验**：出现 NUL 字节基本可以断定是二进制（下错东西了）。
   *
   * 只做这一条 —— 真正的解析交给 mpv，我们不做字幕语法校验，
   * 否则「格式有点怪的 srt」会被误判成损坏。
   */
  fun looksLikeText(bytes: ByteArray): Boolean = bytes.none { it == 0.toByte() }
}
