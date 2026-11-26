package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// EmbyHttpCore —— Emby REST 的传输层（从 EmbyClient 原样搬出，行为零变化）
// ════════════════════════════════════════════════════════════════════════
//
// 这一层只做一件事：把「带 token 的 Request.Builder」和「按文本解码 + 计时日志」
// 收到一处。所有域对象（Items / Persons / Stream / Session / …）都调这里，
// EmbyClient 只保留同名转发，外部 46 个调用文件一行都不用改。
//
// 为什么 `internal` 而不是 `private`：这些成员要被同 module 的其它域对象调用，
// 且 `execJson` 等 inline 助手不能经由非 inline 门面委托 —— 目标必须是
// internal/public，否则 inline 展开到调用方时会看不见。

/**
 * Emby REST 用的 JSON 配置。
 *
 * `ignoreUnknownKeys` + `isLenient` + `coerceInputValues` 三项都是**必须**的：
 * 服务端版本差异会多回字段、少回字段、把数字写成字符串，任何一项关掉都会
 * 让"某台服务器上某个接口"整片解析失败。
 */
internal val embyJson: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  coerceInputValues = true
}

object EmbyHttpCore {
  /** Emby REST 专用的 JSON 实例（与 [embyJson] 同一个，供域对象引用） */
  internal val json: Json get() = embyJson

  /**
   * 走 [EmbyHttp.shared]（共享连接池 + 统一 UA + 指数退避重试 + 401 自动重登）。
   *
   * 原来是 [EmbyClient] 里自己 `OkHttpClient.Builder()` 造一个，与另外 5 个客户端各建各的
   * 连接池，详见 [EmbyHttp] 的说明。
   *
   * `internal` 而非 `private`：拆出去的 [EmbySubtitles] 需要它 —— 字幕下载要拿
   * **原始字节**（走完服务端格式转换的响应体），不能经过 [execString] 那条
   * 「按文本解码 + 抛异常」的路径。同理它必须显式写 `EmbyClient.httpClient`，
   * `internal` 不会把成员导入到别的 object 作用域里。
   */
  internal val httpClient: OkHttpClient = EmbyHttp.shared

  internal val jsonMedia = "application/json; charset=utf-8".toMediaType()

  /** 无 body 的 POST（OkHttp 的 post 必须给 body，给空串就是 content-length=0，跟 Web 端一致） */
  internal val EMPTY_BODY = "".toRequestBody(null)

  /** 无 body 的 POST（JSON 版本，`{}`） */
  internal const val EMPTY_JSON_BODY = "{}"

  /**
   * 收藏 / 已播放等开关型请求附带的客户端标识 query 参数。
   *
   * 与 Emby Web 请求一致：该服务端上
   * 历史上出现过不带这几个参数时「回 200 但收藏没落库」的假成功 —— 请求形态与
   * Web 完全一致后写入才真正生效。X-Emby-* 是 Emby 的标准客户端标识参数，
   * 所有服务端都接受，带上没有副作用。
   */
  internal val WEB_CLIENT_QUERY: Map<String, String> = mapOf(
    "X-Emby-Client" to "Emby Web",
    "X-Emby-Device-Name" to "Chrome Windows",
    "X-Emby-Device-Id" to "ca3b0a8f-2ff0-46ea-aa02-a6abf72f0061",
    "X-Emby-Client-Version" to "4.10.1.0",
    "X-Emby-Language" to "zh-cn",
  )

  /**
   * 超过这个耗时的请求，在 [execString] 的计时日志里按「慢请求」标出（毫秒）。
   *
   * 定 1500 是因为正常局域网内一次 `/Items` 查询在几十到几百毫秒量级；
   * 持续超过 1.5 秒基本就是服务端那边在扫大表（十万级条目库里
   * `SortBy=Random` 这类无法走索引的排序尤其明显），值得单独看一眼。
   */
  private const val SLOW_REQUEST_MS = 1_500L

  internal fun authedRequest(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    val sb = StringBuilder("${EmbyEndpoints.hostUrl(server)}/emby$path")
    if (extraQuery.isNotEmpty()) {
      sb.append('?')
      extraQuery.entries.filter { it.value != null }.joinTo(sb, "&") { (k, v) ->
        "$k=${java.net.URLEncoder.encode(v!!, "UTF-8")}"
      }
    }
    val b = Request.Builder().url(sb.toString())
    if (server.apiToken.isNotEmpty()) {
      // 已登录：用 X-Emby-Token header（也可用 ?api_key=，二选一即可）
      b.header("X-Emby-Token", server.apiToken)
    }
    return b
  }

  internal fun authedRequestWithApiKey(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    // 带 api_key query 参数（用于 stream/images，便于直接拼 URL 给 PlayerActivity）
    val withKey = extraQuery.toMutableMap()
    if (server.apiToken.isNotEmpty()) withKey["api_key"] = server.apiToken
    return authedRequest(server, path, withKey)
  }

  internal fun execString(request: Request): String {
    // 计时日志：所有 Emby API 请求（GET/POST/DELETE）都走这一个收口点，所以在这里
    // 打一行就够覆盖全部。用途是回答「到底哪个端点在慢」—— 大媒体库（十万级条目）下
    // `/Items` 系列查询的耗时会随库规模上涨，而外观上只表现为「首页转圈很久」，
    // 没有这行日志根本分不清是哪一个请求、等了多久。
    // ⚠️ 只打 path 不打 query：query 里可能有用户输入的搜索词，不进日志。
    val startedAt = System.nanoTime()
    try {
      httpClient.newCall(request).execute().use { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) {
          throw EmbyApiException(resp.code, body.ifEmpty { resp.message })
        }
        return body
      }
    } finally {
      val ms = (System.nanoTime() - startedAt) / 1_000_000
      val line = "${ms}ms ${request.method} ${request.url.encodedPath}"
      // 慢的单独按 warn 标出：这标签本身是诊断用的（`adb logcat -s EmbyDiag`），
      // 一屏能刷很多行，慢请求要能一眼挑出来。
      if (ms >= SLOW_REQUEST_MS) EmbyLog.warn("慢请求 $line") else EmbyLog.info(line)
    }
  }

  internal inline fun <reified T> execJson(request: Request): T {
    val body = execString(request)
    if (body.isEmpty()) {
      // 返回空 body 时，尝试构造默认实例（kotlinx 不支持反射 default ctor，所以特殊处理）
      @Suppress("UNCHECKED_CAST")
      return when (T::class) {
        Unit::class -> Unit as T
        else -> json.decodeFromString(body.ifBlank { "null" })
      }
    }
    return json.decodeFromString(body)
  }

  internal inline fun <reified T> postJson(
    server: EmbyServer,
    path: String,
    bodyJson: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query)
      .post(bodyJson.toRequestBody(jsonMedia))
      .build()
    return execJson(req)
  }

  internal inline fun <reified T> getJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).get().build()
    return execJson(req)
  }

  internal inline fun <reified T> deleteJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).delete().build()
    return execJson(req)
  }
}
