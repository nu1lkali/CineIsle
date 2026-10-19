package app.marlboroadvance.mpvex.domain.danmaku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * dandanplay 弹幕源。
 *
 * 只做三件事：搜番 → 拿分集 → 拉弹幕。渲染在 `DanmakuOverlay`，解析在 [DanmakuPStrings]。
 *
 * ## host 与凭证
 *
 * - **host 可自定义**：默认官方 `https://api.dandanplay.net`；也可指向自建
 *   `danmu_api` 服务（协议一致，且那类服务一般不校验签名）。
 * - **凭证可留空**：留空就**不发签名头**。自建服务端走的就是这条路，
 *   也让「没申请到 AppId 也能用」成为可能。
 *
 * ## 为什么不复用 [app.marlboroadvance.mpvex.domain.emby.EmbyHttp]
 *
 * EmbyHttp 带着认证拦截器 + 401 自动重登 + token 注入，而这里请求的是第三方 host、
 * 根本不需要 Emby token；更要紧的是它的重试退避里有 `Thread.sleep`（最长 3 秒），
 * 不包 `withContext(IO)` 就会卡主线程。所以单开一个短超时、无认证的干净客户端。
 */
object DanmakuClient {

  private const val TAG = "DanmakuClient"

  /** 官方 host。设置项默认值与之保持一致。 */
  const val DEFAULT_HOST = "https://api.dandanplay.net"

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
  }

  /**
   * 独立的客户端：短超时、无认证、无重试退避。
   *
   * 弹幕是**锦上添花**功能，卡住 25 秒比直接失败糟糕得多。
   */
  private val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .callTimeout(25, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  /** 凭证：留空 = 不签名（自建服务端 / 还没申请到 AppId）。 */
  data class Credentials(val appId: String = "", val appSecret: String = "") {
    val isConfigured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
  }

  /**
   * 完整 URL。抽成函数是为了让「用户漏写协议」这种高频手误在**调用点之前**就被兜住。
   *
   * @return null 表示 host 完全不可用（空白 / 非法），调用方据此跳过请求。
   */
  private fun apiUrl(host: String, path: String): String? {
    val base = host.trim().trimEnd('/').ifBlank { return null }
    val normalized =
      if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
    return "$normalized$path".toHttpUrlOrNull()?.toString()
  }

  /**
   * 带签名的 GET。
   *
   * @return 响应体；失败时返回 null 并**记下可读原因**（[lastError]）。
   */
  private suspend fun getText(host: String, pathWithQuery: String, credentials: Credentials): String? =
    withContext(Dispatchers.IO) {
      val url = apiUrl(host, pathWithQuery) ?: return@withContext null
      try {
        val ts = System.currentTimeMillis() / 1000
        val builder = Request.Builder()
          .url(url)
          .header("User-Agent", "CineIsle/1.2.5 (Android)")
          .header("Accept", "application/json")
        if (credentials.isConfigured) {
          // ⚠️ 签名必须对**去掉 query 后的路径**计算（见 DanmakuSignature.pathOf）。
          val signature = DanmakuSignature.sign(
            appId = credentials.appId,
            appSecret = credentials.appSecret,
            path = DanmakuSignature.pathOf(pathWithQuery),
            timestampSec = ts,
          )
          builder.header("X-AppId", credentials.appId)
          builder.header("X-Timestamp", ts.toString())
          builder.header("X-Signature", signature)
        }
        http.newCall(builder.build()).execute().use { resp ->
          // ⚠️ 403 的 body 是空的，**原因只在 X-Error-Message 头里**。
          // 不读它的话用户只会看到「搜不到」而无从下手（官方文档明确列出这几种取值）。
          val errMsg = resp.header("X-Error-Message")
          if (!resp.isSuccessful) {
            lastError = when (resp.code) {
              403 -> errMsg?.let { "认证被拒：$it" }
                ?: "认证被拒（403）。若未在开放平台提交应用审核，凭证不会生效；" +
                "若未填凭证则只能使用自建弹幕服务。"
              else -> "HTTP ${resp.code}${errMsg?.let { " · $it" } ?: ""}"
            }
            Log.w(TAG, "GET 失败 code=${resp.code} msg=$errMsg url=$url")
            return@use null
          }
          lastError = null
          resp.body?.string()
        }
      } catch (t: Throwable) {
        // ⚠️ 不能吞 CancellationException：协程取消时抛的正是它，
        // 吞掉会破坏结构化并发（页面已走、请求还在跑）。
        if (t is kotlinx.coroutines.CancellationException) throw t
        lastError = "网络错误：${t.message ?: t::class.java.simpleName}"
        Log.w(TAG, "GET 异常 url=$url", t)
        null
      }
    }

  /** 最近一次失败的原因；成功时为 null。供上层给用户一句能看懂的话。 */
  @Volatile
  var lastError: String? = null
    private set

  /**
   * 搜番。
   *
   * ⚠️⚠️ **官方端点的参数名是 `keyword`，不是 `anime`**（v100 真机修复的核心）。
   * 传 `anime=` 服务端不会报参数缺失，而是回一个 **HTTP 200 + `success:false`**：
   *
   * ```
   * {"animes": null, "errorCode": 2, "success": false, "errorMessage": "一个或多个参数不符合规则"}
   * ```
   *
   * 客户端原先只看 `animes`（null → 空列表）⇒ 用户看到的就是「搜索不到弹幕」，
   * 而网络、凭证、签名全都是好的，根本无从排查。所以这里必须**同时**看 `success`
   * 和 `errorMessage`，把它变成一句能看懂的话。
   *
   * @return 命中列表；网络失败 / 认证失败 / 参数被拒时返回**空列表**（调用方给空态，不崩）。
   */
  suspend fun searchAnime(host: String, keyword: String, credentials: Credentials): List<DandanAnime> {
    val q = keyword.trim()
    if (q.isEmpty()) return emptyList()
    val path = "/api/v2/search/anime?keyword=" + java.net.URLEncoder.encode(q, "UTF-8")
    val body = getText(host, path, credentials) ?: return emptyList()
    val parsed = runCatching { json.decodeFromString<DandanSearchResponse>(body) }
      .onFailure { lastError = "搜索响应格式不对"; Log.w(TAG, "搜索响应解析失败", it) }
      .getOrNull()
      ?: return emptyList()

    // ⚠️ success=false 时 animes 恒为 null，**绝不能**当成「没搜到」——那是服务端在报错。
    if (!parsed.success) {
      lastError = parsed.errorMessage?.takeIf { it.isNotBlank() }
        ?.let { "弹幕源拒绝请求：$it" }
        ?: "弹幕源拒绝了这次搜索（errorCode=${parsed.errorCode ?: "?"}）"
      Log.w(TAG, "搜索成功返回但 success=false：${parsed.errorCode} ${parsed.errorMessage}")
      return emptyList()
    }
    return parsed.animes
  }

  /** 拿一部番的分集列表。 */
  suspend fun getEpisodes(host: String, animeId: Long, credentials: Credentials): List<DandanEpisode> {
    val body = getText(host, "/api/v2/bangumi/$animeId", credentials) ?: return emptyList()
    return runCatching { json.decodeFromString<DandanBangumiResponse>(body).bangumi?.episodes }
      .onFailure { lastError = "番剧响应格式不对"; Log.w(TAG, "番剧响应解析失败", it) }
      .getOrNull()
      .orEmpty()
  }

  /**
   * 拉一集的原始弹幕。
   *
   * `withRelated=false`：只要这一集的评论，不要服务端顺带返回的其它剧集。
   */
  /**
   * 拉某一集的弹幕。
   *
   * @param withRelated 是否合并「关联剧集」的弹幕（对标 Yamby 的 `danmaku_with_related`）。
   *   dandanplay 会把同一集在不同版本 / SP / OVA / 不同字幕组下的弹幕一起返回 ——
   *   开启后弹幕密度明显更高，但也可能混进「同一集另一个剪辑版」的偏差弹幕。
   *   服务端默认 false，由调用方按用户偏好决定。
   */
  suspend fun getComments(
    host: String,
    episodeId: Long,
    credentials: Credentials,
    withRelated: Boolean = false,
  ): List<DandanComment> {
    // ⚠️ 参数顺序固定：`withRelated` 在前、`chConvert` 在后（服务端按名解析，但保持一致更稳）
    val path = "/api/v2/comment/$episodeId?withRelated=$withRelated&chConvert=0"
    val body = getText(host, path, credentials) ?: return emptyList()
    return runCatching { json.decodeFromString<DandanCommentResponse>(body).comments }
      .onFailure { lastError = "弹幕响应格式不对"; Log.w(TAG, "弹幕响应解析失败", it) }
      .getOrDefault(emptyList())
  }
}