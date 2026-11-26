package app.marlboroadvance.mpvex.domain.emby

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════
// 取流：地址构造 + 可达性探测
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// 这两个职责放在一起是有意的：探测（`resolveStreamUrl` / `warmStream`）永远只对
// 「自己拼出来的那个地址」做，而地址拼错的表现（播错版本、403）和探测逻辑无法分开排查。
// 合在一处，才能保证「探测用的 URL == 交给播放器的 URL」这条不变量只有一处来源。
//
// ⚠️ 三条硬约束（都是真机事故换来的，搬迁时逐字保留）：
//  · `warmStream` **不跟 302**（不碰网盘 CDN、不触发风控），`resolveStreamUrl` **必须跟**；
//  · 探测必须带 `EmbyClient.STREAM_USER_AGENT`（网盘签名常把 UA 算进去，换 UA 即 403）；
//  · 所有超时都走 per-call `call.timeout()`，不动共享 client 的全局口径。

object EmbyStreamApi {
  // ── 「服务器转码」的默认参数（见 [transcodeStreamUrl]）──
  internal const val TRANSCODE_VIDEO_CODEC = "h264"
  internal const val TRANSCODE_AUDIO_CODEC = "aac"
  internal const val TRANSCODE_MAX_HEIGHT = 1080
  internal const val TRANSCODE_VIDEO_BITRATE = 6_000_000
  internal const val TRANSCODE_AUDIO_BITRATE = 192_000

  /**
   * 构造视频流 URL（static=true 表示直接转原文件，不转码）。
   * 适用于大多数情况；只有编码不支持的容器才需要 static=false 触发转码。
   *
   * 调用方把这个 URL 传给 PlayerActivity，作为 mpv 的播放源。
   */
  fun videoStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    mediaSourceId: String? = null,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
  ): String {
    val q = LinkedHashMap<String, String>()
    q["static"] = static.toString()
    mediaSourceId?.let { q["MediaSourceId"] = it }
    audioStreamIndex?.let { q["AudioStreamIndex"] = it.toString() }
    subtitleStreamIndex?.let { q["SubtitleStreamIndex"] = it.toString() }
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return EmbyHttpCore.absolute(server, EmbyPaths.videoStream(itemId)) + "?$qs"
  }

  /**
   * 多版本媒体：向服务器要**指定版本**的直连播放地址。
   *
   * ## 为什么不能只自己拼 `/Videos/{id}/stream?MediaSourceId=…`
   *
   * Emby 官方文档把 `MediaSourceId` 与 **`PlaySessionId`** 一起列为 `/Videos/{Id}/stream`
   * 的**必需参数**（`PlaySessionId` from the PlaybackInfo response）。我们以前只带
   * `MediaSourceId` 就去请求 —— 服务器会无视它、直接吐默认版本，现象正是
   * 「下拉里选了不带字幕的 B，播出来的还是带字幕的 A」，而且关掉直链预解析也一样
   * （说明不是 302 那一步的问题，是最初这条流地址就没带对身份）。
   *
   * 所以这里先 `POST /Items/{id}/PlaybackInfo`（声明只要直连 / 直串流），让服务器把
   * 该版本的地址拼好给我们（`DirectStreamUrl`）；服务器没给就自己拼，
   * 但这一次**一定带上 `PlaySessionId`**。
   *
   * @return 该版本的播放地址；任何一步拿不到都返回 null —— 调用方回退到原有拼法，
   *   解析失败绝不挡住播放。
   */
  suspend fun directStreamUrlForSource(
    server: EmbyServer,
    itemId: String,
    /** 要播放的版本（`MediaSources[i].Id`） */
    mediaSourceId: String,
  ): String? {
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          // 只要直连 / 直串流：这条路不希望服务器动数据（要转码另有 [transcodeStreamUrl]）
          "EnableDirectPlay" to true,
          "EnableDirectStream" to true,
          "EnableTranscoding" to false,
          "AutoOpenLiveStream" to false,
          "IsPlayback" to false,
          // 指定版本：服务器据此返回对应 MediaSource（含它自己的 DirectStreamUrl）
          "MediaSourceId" to mediaSourceId,
        ),
      ).toString()

    val info = runCatching {
      EmbyHttpCore.execJson<PlaybackInfoResult>(
        EmbyHttpCore.authedRequest(server, EmbyPaths.playbackInfo(itemId))
          .post(payload.toRequestBody(EmbyHttpCore.jsonMedia))
          .build(),
      )
    }.getOrNull() ?: return null

    // ⚠️ 服务端没按 MediaSourceId 给回我们要的那个版本 ⇒ 宁可返回 null 让调用方走原路径。
    // 退回首条会把「默认版本」当成「用户选的版本」播出去 —— 那正是要修的这个 bug，
    // 只是换个地方再犯一次。
    val source = info.MediaSources?.firstOrNull { it.Id == mediaSourceId } ?: return null

    source.DirectStreamUrl?.takeIf { it.isNotBlank() }?.let {
      return ensureApiKey(absolutize(server, it), server)
    }

    // 服务器没给直连地址：自己拼，这次带上 PlaySessionId（官方文档的必需参数）
    val q = LinkedHashMap<String, String?>()
    q["Static"] = "true"
    q["MediaSourceId"] = source.Id ?: mediaSourceId
    info.PlaySessionId?.let { q["PlaySessionId"] = it }
    q["DeviceId"] = androidDeviceId(server.id.toString())
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v ?: "", "UTF-8")}"
    }
    return EmbyHttpCore.absolute(server, EmbyPaths.videoStream(itemId)) + "?$qs"
  }

  /** 服务器给的 `DirectStreamUrl` 未必带 `api_key`（取决于 `AddApiKeyToDirectStreamUrl`），补上更稳。 */
  private fun ensureApiKey(url: String, server: EmbyServer): String {
    if (server.apiToken.isEmpty()) return url
    if (url.contains("api_key=")) return url
    return url + (if (url.contains("?")) "&" else "?") + "api_key=${server.apiToken}"
  }

  /**
   * 「让**服务器转码**」后的播放地址 —— 本机解不了的文件走这条路。
   *
   * ## 为什么需要它
   *
   * [videoStreamUrl] 的 `static=true` 是「原样吐原文件」，服务器一个字节都不动。
   * 这对「本机能解的编码」最省资源，但遇到下面这些源就一定翻车：
   *
   *  · **远程 STRM**：`.strm` 里指向一个远端地址，源文件是 AVI；
   *  · **AVI + Xvid / DivX（MPEG-4 ASP）**：手机上的 MediaCodec 普遍没有
   *    `video/mp4v-es` 解码器 —— 硬解直接不出画面，软解又常常跟不上；
   *  · 其它老旧编码 / 容器：本机解不了，**换内核也没用**（两条路用的都是系统解码器）。
   *
   * 这种情况下唯一可靠的办法是**让服务器转**：Emby 侧用 ffmpeg 转成 H.264 + AAC，
   * 客户端拿到的就是最普通的流。官方客户端、Infuse 那类第三方客户端在同样的片子上能放、
   * 我们放不了，差别就在这一步 —— 它们会先问服务器 PlaybackInfo，服务器发现
   * 「这个客户端不支持 mpeg4」就把转码地址给它。
   *
   * ## 两步（都是 Emby 官方接口）
   *
   * 1. `POST /Items/{id}/PlaybackInfo`，声明「只要转码」，换回 `MediaSources[0].Id`
   *    （MediaSourceId）与 `PlaySessionId`，响应里通常还会直接给一条 `TranscodingUrl`；
   * 2. 有 `TranscodingUrl` 就用它（服务器把参数都拼好了，最稳）；没有就自己拼
   *    `/Videos/{id}/stream?Static=false&...` —— `Static=false` 即触发服务端转码。
   *
   * @param startTimeTicks 从哪开始（Emby 的 tick，1 tick = 100ns）；续播时传进来
   * @return 可直接交给播放器的完整 URL
   * @throws EmbyApiException 服务器拒绝转码（例如策略里关了视频转码）
   */
  suspend fun transcodeStreamUrl(
    server: EmbyServer,
    itemId: String,
    startTimeTicks: Long = 0L,
    maxHeight: Int = TRANSCODE_MAX_HEIGHT,
    videoBitrate: Int = TRANSCODE_VIDEO_BITRATE,
    audioBitrate: Int = TRANSCODE_AUDIO_BITRATE,
    /**
     * 多版本媒体要转码的那个版本（`MediaSources[i].Id`）。null = 由服务端挑默认版本。
     * 不指定的话，服务器会转码它自己认定的默认源 —— 用户在详情页选了 4K 却拿到 1080P 的转码。
     */
    mediaSourceId: String? = null,
  ): String {
    // ── 1. 向服务器要一份「只转码」的播放方案 ──
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          // 三个开关是本方法的核心：禁掉直连、禁掉直接串流、只留转码
          "EnableDirectPlay" to false,
          "EnableDirectStream" to false,
          "EnableTranscoding" to true,
          "AllowVideoStreamCopy" to false,
          "AllowAudioStreamCopy" to false,
          "MaxStreamingBitrate" to (videoBitrate + audioBitrate),
          // 指定版本：服务器据此返回对应 MediaSource 的转码方案
          "MediaSourceId" to mediaSourceId,
        ),
      ).toString()

    val info =
      EmbyHttpCore.execJson<PlaybackInfoResult>(
        EmbyHttpCore.authedRequest(server, EmbyPaths.playbackInfo(itemId))
          .post(payload.toRequestBody(EmbyHttpCore.jsonMedia))
          .build(),
      )

    // 优先取用户选中的那个版本；服务端没按 MediaSourceId 过滤时退回首条
    val source = (mediaSourceId?.let { id -> info.MediaSources?.firstOrNull { it.Id == id } }
      ?: info.MediaSources?.firstOrNull())
      ?: throw EmbyApiException(0, info.ErrorCode ?: "服务器没有返回可播放的媒体源（可能转码被禁用）")

    // ── 2a. 服务器自己给的转码地址优先（参数齐全、已含 api_key）──
    source.TranscodingUrl?.takeIf { it.isNotBlank() }?.let { return absolutize(server, it) }

    // ── 2b. 没给就自己拼老的 /stream 端点（Static=false 即转码）──
    val q = LinkedHashMap<String, String?>()
    q["Static"] = "false"
    source.Id?.let { q["MediaSourceId"] = it }
    q["DeviceId"] = androidDeviceId(server.id.toString())
    info.PlaySessionId?.let { q["PlaySessionId"] = it }
    if (startTimeTicks > 0) q["StartTimeTicks"] = startTimeTicks.toString()
    q["MaxHeight"] = maxHeight.toString()
    q["VideoCodec"] = TRANSCODE_VIDEO_CODEC
    q["AudioCodec"] = TRANSCODE_AUDIO_CODEC
    q["VideoBitRate"] = videoBitrate.toString()
    q["AudioBitRate"] = audioBitrate.toString()
    q["TranscodingMaxAudioChannels"] = "2"
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken

    val qs = q.entries.filter { it.value != null }.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v!!, "UTF-8")}"
    }
    return EmbyHttpCore.absolute(server, EmbyPaths.videoStream(itemId)) + "?$qs"
  }

  /**
   * 把服务器返回的相对地址补成完整 URL。
   *
   * `TranscodingUrl` 长这样：`/videos/{id}/master.m3u8?DeviceId=...&api_key=...`
   * —— 注意它**不带 `/emby` 前缀**（虽然调用时是打在 `/emby` 下的），
   * 所以这里统一补回 `${hostUrl}/emby`。已经是绝对地址的原样返回。
   */
  private fun absolutize(server: EmbyServer, url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val path = if (url.startsWith("/")) url else "/$url"
    return EmbyHttpCore.absolute(server, path)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 直链（302）预解析
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 预解析「302 直链」：把 `/Videos/{id}/stream` 背后真正的直链取出来。
   *
   * ## 为什么需要
   *
   * 网盘 / STRM 这类源，Emby 的 `/Videos/{id}/stream` 会回一个 **302 跳转**到
   * 网盘给的临时直链。正常情况播放器自己会跟着跳转，不用管；但两类场景会翻车：
   *
   *  · **播放器不跟 302**（部分内核把 302 当错误 / 需要额外的 Referer 头）→ 直接播不动；
   *  · **直链有有效期**，播到一半过期 → 中途卡死，重开一次又能放。这正是
   *    「预解析失效重试」要解决的：把最终直链提前取出来交给播放器，出错时再重取一次。
   *
   * ## 怎么取
   *
   * 用 `Range: bytes=0-0` 发一个最小 GET，让 OkHttp 自动跟完跳转链，
   * 从最终请求的 URL 拿到直链，随即关闭响应 —— 只读 1 个字节，几乎不产生流量。
   * 只有**确实发生了跳转**才返回直链；没跳转（说明服务端是直出、或本身就是直链）
   * 返回 null，调用方沿用原地址即可，不改变既有行为。
   *
   * 失败时按 [attempts] 重试，全部失败返回 null（调用方回退到原始 URL，绝不因为
   * 解析失败而挡住播放）。
   *
   * @return 最终直链；未发生跳转 / 解析失败均为 null
   */
  /**
   * 预解析「302 直链」：拿 `/Videos/{id}/stream` 背后跳转到的真实地址，
   * **同时**把这次的 HTTP 状态码翻译成「能不能播」的结论。
   *
   * ## 为什么要带出状态码（v128）
   *
   * 这个方法本来就会发一次 `Range: bytes=0-0`。以前它只把结果当「直链」用，
   * 被拒（403 / 404）时静默返回 null、让调用方回退原始地址 —— 于是死链照样被交给
   * mpv，而 mpv 遇到死链会在 native 层崩掉（整个 App 消失，Java 层拦不到）。
   *
   * 现在把**本来就已经拿到手**的 `resp.code` 一并翻译出来：调用方据此在起播前拦下死链。
   * **不额外发任何请求** —— 防崩能力是白捡的，这是刻意的设计约束。
   *
   * ## 判定口径
   *
   * · 2xx → [StreamProbe.Ok]，`finalUrl` 是跟完跳转后的地址（没跳转就是原地址）；
   * · 401 / 403 / 404 / 410 / **5xx** → [StreamProbe.Denied]，**立即返回不再重试**
   *   （这些码重试没有意义，每多试一次就多打一次 CDN —— 网盘/CDN 会记风控）；
   * · 416 / 400 / 405 之类 → [StreamProbe.Unknown]，不下死结论
   *   （服务端对 Range 处理特殊，交给调用方回退原始地址，别误拦用户）；
   * · 网络异常 / 超时 → [StreamProbe.Unknown]，按可播处理。
   *
   * ## 5xx 为什么从「未知」改成「判死」（v341）
   *
   * 5xx 原来落在 [StreamProbe.Unknown]：地址照旧交给 mpv，理由是「服务端临时抽风」。
   * 真机上这不是抽风 —— 反代 / 上游挂掉时 `/Videos/{id}/stream` 稳定回 500 / 502 / 503，
   * 而 mpv 拿到的是一条**永远打不开的地址**：界面停在加载层，进程还会在 native 侧
   * （`fdsan` / `Fatal signal 6`）中止，用户看到的就是「点播放直接闪退」。
   *
   * 判死之后这条地址**根本不会交给内核**，播放页显示的是可重试的错误卡片；
   * 而且因为结论是明确的，`repeat(attempts)` 立刻收摊 —— 请求数反而从 2 次降到 1 次。
   *
   * @return [StreamProbe.Ok] 可播（含最终直链）；[StreamProbe.Denied] 明确不可播；
   *         [StreamProbe.Unknown] 探不出来，调用方回退原始地址
   */
  fun resolveStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    attempts: Int = 2,
    /**
     * 多版本媒体要指定的版本（`MediaSources[i].Id`）。null = 不指定，由服务端挑默认版本。
     * 探测直链与真正拉流必须是同一个版本，否则「解析出来的是 4K、播的却是 1080P」。
     */
    mediaSourceId: String? = null,
    /**
     * **单次调用的硬上限**（毫秒）。
     *
     * ⚠️ 这条请求挂在**起播关键路径**上：`EmbyViewModel.play()` 拿到结果之前不会拉起
     * 播放器。共享 client 的 `readTimeout` 是 60 秒，而 strm 条目在服务端还没 probe
     * 过源文件时（详情页「没有媒体信息」正是这个状态），`/Videos/{id}/stream` 会先
     * 让服务端去读远端文件、把大小/时长写库，这一次能挂到分钟级（× [attempts] 次重试）。
     * 现象就是「第一次点播放一直卡在加载中，返回再进详情页点播放立马就能播」——
     * 第二次服务端已经 probe 完、有缓存了。所以这里必须自己带上限，超时按
     * [StreamProbe.Unknown] 处理、把地址原样交给播放器，绝不久等。
     */
    callTimeoutMs: Long = STREAM_PROBE_CALL_TIMEOUT_MS,
  ): StreamProbe {
    val original = videoStreamUrl(server, itemId, static = static, mediaSourceId = mediaSourceId)
    repeat(attempts.coerceAtLeast(1)) {
      val outcome = runCatching {
        val req = Request.Builder()
          .url(original)
          // 只取 1 个字节：既让服务端走完整跳转链，又不真的下载内容
          .header("Range", "bytes=0-0")
          // ⚠️ 必须显式带上与播放器一致的 UA。网盘直链（115 这类）的签名常把 UA
          // 算进去：探测用 OkHttp 默认 UA 拿到直链、再交给 mpv 用另一套 UA 去请求，
          // 服务端就会以 403 拒绝 —— 现象是「解析成功、播放必失败」。
          .header("User-Agent", EmbyClient.STREAM_USER_AGENT)
          .get()
          .build()
        val call = EmbyHttpCore.httpClient.newCall(req)
        // per-call 超时：不动共享 client 的全局口径（下载 / 图片那些档位不受影响）
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
          when {
            resp.isSuccessful -> {
              // response.request 是「跟完跳转后的最后一个请求」，它的 URL 就是直链
              val finalUrl = resp.request.url.toString()
              StreamProbe.Ok(
                finalUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                  ?: original
              )
            }
            resp.code in DENIED_STREAM_CODES || resp.code in 500..599 -> {
              // 日志 tag 刻意保持 "EmbyClient"：现有排障都是按这个 tag 过滤 logcat 的
              android.util.Log.w(
                "EmbyClient",
                "直链被拒（HTTP ${resp.code} @ ${resp.request.url.host}）—— 判定不可播",
              )
              StreamProbe.Denied(streamDeniedMessage(resp.code))
            }
            else -> {
              // 416 / 400 / 405…：不下死结论，交给调用方回退原始地址
              android.util.Log.i(
                "EmbyClient",
                "直链探测返回 HTTP ${resp.code} @ ${resp.request.url.host}，按未知处理",
              )
              null
            }
          }
        }
      }.getOrNull()

      // Ok / Denied 都是明确结论，立即返回。
      // Denied 不重试是关键：4xx 重试注定还是 4xx，只会多打一次 CDN。
      if (outcome != null) return outcome
    }
    return StreamProbe.Unknown
  }

  /**
   * 能把一条流地址判死的 HTTP 状态码（见 [resolveStreamUrl] 的判定口径）。
   *
   * 5xx 不在这个集合里、而是由调用点按 `in 500..599` 补判：两者语义不同 ——
   * 4xx 是「这条链接不行」，5xx 是「服务端 / 反代现在不行」，但对「别交给内核」
   * 这个结论是一样的（见 [resolveStreamUrl] 里的 v341 说明）。
   */
  private val DENIED_STREAM_CODES = setOf(401, 403, 404, 410)

  /**
   * 「STRM 直链播放」：从 `PlaybackInfo` 里取 `.strm` 文件中的远端地址。
   *
   * ## 为什么是 `PlaybackInfo.MediaSources[].Path`
   *
   * Emby 官方对 `.strm` 的处理是把文件里那一行地址当作**媒体路径**：
   * `Path = "http://..."`、`Protocol = "Http"`、`IsRemote = true`。
   * 这正是「strm 的直链」；拿到后直接交给播放器，就不需要再让
   * `/Videos/{id}/stream` 中转（部分服务器 / 兼容层在这一步不兼容）。
   *
   * ## 取舍
   *
   * - 只发一次 `PlaybackInfo`，**不跟跳转、不下载媒体**；
   * - 超时 / 不是 http(s) / 服务端没给 Path ⇒ 返回 null，调用方回退正常流地址；
   * - 只在用户显式打开「STRM 直链播放」时调用（见 `BrowserPreferences.embyStrmDirectPlay`）。
   */
  fun strmDirectPlayInfo(
    server: EmbyServer,
    itemId: String,
    /** 多版本媒体：只取这个版本；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): StrmDirectPlayInfo? {
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          "EnableDirectPlay" to true,
          "EnableDirectStream" to true,
          "EnableTranscoding" to false,
          "AutoOpenLiveStream" to false,
          "IsPlayback" to false,
          "MediaSourceId" to mediaSourceId,
        ),
      ).toString()

    val request = EmbyHttpCore.authedRequest(server, EmbyPaths.playbackInfo(itemId))
      .post(payload.toRequestBody(EmbyHttpCore.jsonMedia))
      .build()

    return runCatching {
      strmDirectClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@use null
        val info = EmbyHttpCore.json.decodeFromString<PlaybackInfoResult>(body)
        // 指定版本时必须是同一个 Id：服务端退回默认版本的话，宁可回退普通流地址，
        // 也不能把「用户选的 4K」播成「默认的 1080P」。
        val source = if (mediaSourceId != null) {
          info.MediaSources?.firstOrNull { it.Id == mediaSourceId } ?: return@use null
        } else {
          info.MediaSources?.firstOrNull() ?: return@use null
        }
        val url = StrmDirectLink.directUrl(source.Path) ?: return@use null
        StrmDirectPlayInfo(url = url, requiredHeaders = source.RequiredHttpHeaders.orEmpty())
          .takeIf { probeStrmDirect(it) }
      }
    }.getOrNull()
  }

  /**
   * STRM 直链的可达性预检。
   *
   * 内网 / 失效 / 需要额外鉴权的直链，交给播放器只会得到一片黑屏或错误卡片；
   * 起播前用 `Range: bytes=0-0` 轻探一次（带服务器声明的请求头），
   * 探不通就返回 null，让调用方回退正常的 Emby 流地址。
   *
   * 判定口径：401/403/404/410 判死（链接本身不可用）；**5xx 同样判死**（v341）——
   * 反代 / 上游挂掉时 strm 里的地址稳定回 5xx，交给播放器只会得到一条永远打不开的
   * 流。判死后调用方回退到正常流地址（`/Videos/{id}/stream` 由 Emby 自己中转），
   * 至少给用户留一条能走的路。其余 4xx 视为「主机可达、只是不接受这次 Range」。
   *
   * ⚠️ 这次请求**本来就要发**（见 [strmDirectPlayInfo] 的取舍），收紧判定不增加任何
   * 请求次数 —— 与「不做主动探活」的约束不冲突。
   */
  private fun probeStrmDirect(info: StrmDirectPlayInfo): Boolean = runCatching {
    val builder = Request.Builder()
      .url(info.url)
      .header("Range", "bytes=0-0")
    var hasUserAgent = false
    info.requiredHeaders.forEach { (key, value) ->
      if (value.isBlank()) return@forEach
      if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
      builder.header(key, value)
    }
    if (!hasUserAgent) builder.header("User-Agent", EmbyClient.STREAM_USER_AGENT)

    val call = strmDirectClient.newCall(builder.get().build())
    call.timeout().timeout(STRM_DIRECT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    call.execute().use { response ->
      when (response.code) {
        401, 403, 404, 410 -> false
        // 反代 / 上游不可用：这条直链现在打不开，回退正常流地址去试
        in 500..599 -> false
        else -> true
      }
    }
  }.getOrDefault(false)

  /**
   * 预热专用 client：**不跟 302**。
   *
   * 这一点是本对象最关键的取舍，见 [warmStream]。
   */
  private val streamWarmClient: OkHttpClient by lazy {
    EmbyHttpCore.httpClient.newBuilder()
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  }

  /**
   * STRM 直链探测专用 client：**短超时**。
   *
   * `PlaybackInfo` 在服务端还没 probe 过 strm 源时可能挂很久，而它挂在起播关键路径上。
   * 这里给单次调用 2.5 秒硬上限；超时就当拿不到直链，回退正常流地址，绝不让用户干等。
   */
  private val strmDirectClient: OkHttpClient by lazy {
    EmbyHttpCore.httpClient.newBuilder()
      .callTimeout(STRM_DIRECT_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  /**
   * 后台「预热」一条 stream 地址：让**服务端**先把 strm 的远端源 probe 完并写库。
   *
   * ## 它解决的是什么
   *
   * strm 条目在服务端还没 probe 过源文件时，`/Videos/{id}/stream` 会先让服务端去读
   * 远端文件、把大小/时长写进库 —— 这一次能挂到分钟级（v140 的根因）。起播前的
   * 2 秒探测只能保证「别把播放器界面卡住」，改变不了服务端本来就慢这件事。
   *
   * 唯一真正能让「切到下一个」变快的办法，是**在用户还没切之前**把下一条预热掉：
   * 等用户切过去时服务端已经有缓存，请求秒回。
   *
   * ## 为什么不跟 302（这条决定了会不会触发 CDN 风控）
   *
   * `followRedirects=false` ⇒ 本次请求**只到我们自己的 Emby 服务器**，拿到那个 302
   * 就结束。服务端要 probe 远端文件，那是服务端去读网盘/CDN，跟客户端打多少请求无关。
   * 客户端因此**一个字节都不会打到网盘 CDN 上** —— 这正是「预热不能引发 CDN 风控」
   * 的硬保证。
   *
   * 反过来 [resolveStreamUrl] 必须跟 302（它的目的就是取直链），所以那条路会碰到
   * CDN，只允许在**起播关键路径、单条、单次**上用（见调用方）。
   *
   * ## 使用约束（调用方必须遵守）
   *
   * · **串行**：同一时刻只允许一条预热在飞（调用方用单个 Job 控制）；
   * · **单条**：只预热「下一条」，不做整列表批量预取；
   * · **可失败**：返回 false 就算了，绝不影响播放（预热是纯优化）。
   *
   * @param mediaSourceId 多版本条目要指定与播放一致的版本，否则预热的是另一个版本
   *   （白打一次，也不会出错）
   * @return true = 服务端已经应答（含 302），预热完成
   */
  fun warmStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
    /**
     * 预热是后台行为，不占起播关键路径，所以给的预算比探测宽松得多：
     * 服务端第一次 probe 远端文件可能十几秒，这里让它跑完才有意义。
     */
    callTimeoutMs: Long = STREAM_WARM_CALL_TIMEOUT_MS,
  ): Boolean {
    val original = videoStreamUrl(server, itemId, static = true, mediaSourceId = mediaSourceId)
    return runCatching {
      val req = Request.Builder()
        .url(original)
        // 只取 1 个字节：服务端只要开始应答，probe 就已经发生了
        .header("Range", "bytes=0-0")
        // 与播放器、与 [resolveStreamUrl] 同一套 UA（理由见那两处注释）
        .header("User-Agent", EmbyClient.STREAM_USER_AGENT)
        .get()
        .build()
      streamWarmClient.newCall(req).apply {
        timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
      }.execute().use { resp ->
        // 2xx / 3xx 都算预热成功：3xx 就是正常情况下服务端 probe 完给的跳转，
        // 我们**故意**不跟过去（见上面的说明）。
        resp.isSuccessful || resp.code in 300..399
      }
    }.getOrDefault(false)
  }

  /**
   * 预热请求的超时（毫秒）。
   *
   * 比 [STREAM_PROBE_CALL_TIMEOUT_MS] 宽得多 —— 它不在任何用户等待路径上，
   * 给得松才能让「服务端真的把 probe 跑完」这件事发生；超时也只是这一次预热白做，
   * 下次切到该条时播放器自己再请求一次即可（行为与没有预热时一致）。
   */
  internal const val STREAM_WARM_CALL_TIMEOUT_MS = 25_000L

  /**
   * 302 预解析的**单次调用上限**（毫秒）。
   *
   * 2 秒是刻意选的：4xx 死链基本都在百毫秒内回来（判死链的能力不受影响），
   * 而「服务端要先 probe 远端文件」这种慢路径一秒都不该让用户等 ——
   * 超时按 [StreamProbe.Unknown] 放行，地址照样交给播放器，它自己会跟 302。
   */
  internal const val STREAM_PROBE_CALL_TIMEOUT_MS = 2_000L

  /**
   * 「STRM 直链播放」的 PlaybackInfo 单次上限（毫秒）。
   *
   * 与 302 预解析同一个口径：它也在起播关键路径上，超时就回退正常流地址。
   */
  private const val STRM_DIRECT_INFO_TIMEOUT_MS = 2_500L

  /** STRM 直链可达性预检的单次上限（毫秒）。 */
  private const val STRM_DIRECT_PROBE_TIMEOUT_MS = 1_500L

  /** 把「判死」的状态码翻成可直接给用户看的中文原因。 */
  private fun streamDeniedMessage(code: Int): String = when {
    code == 401 -> "登录状态已失效（HTTP 401），请重新登录这台服务器"
    code == 403 -> "服务器拒绝访问（HTTP 403）—— 直链可能已过期，或这个文件没有访问权限"
    code == 404 -> "服务器找不到这个文件（HTTP 404）—— 它可能已被移动、重命名或删除"
    code == 410 -> "这个文件已从服务器移除（HTTP 410）"
    code in 500..599 -> "服务器出错了（HTTP $code），请稍后重试"
    else -> "服务器返回 HTTP $code，无法播放"
  }

  /**
   * 播放前探测一条**已经拼好的**流地址能不能真的取到数据。
   *
   * ## 为什么必须探在播放之前
   *
   * 403 / 404 的死链（网盘直链过期、文件被移走、STRM 指向失效）一旦交给 mpv，
   * libmpv 会在 **native 层**崩掉 —— 现象是「点播放 → 整个 App 直接消失回桌面」，
   * 而且拿不到任何 Java 堆栈（`GlobalExceptionHandler` 收不到，崩溃页也不会出现）。
   * 这类崩溃发生在 native 栈里，`try/catch` / `runCatching` 一律无效，
   * **唯一可靠的防线就是在把地址交出去之前确认它能取到数据**。
   *
   * ## 判定口径
   *
   * · 与 [resolveStreamUrl] 一致：`Range: bytes=0-0` 只取一个字节，
   *   并带 `EmbyClient.STREAM_USER_AGENT`（网盘签名常把 UA 算进去，换 UA 就 403）；
   * · 单次 2.5 秒超时，用 per-call timeout，不动共享 client 的全局口径；
   * · **只有服务端明确拒绝（4xx / 5xx）才算不可播**。超时、DNS 失败、连接被重置
   *   一律返回 [StreamProbe.Unknown] 并按可播处理 —— 本地网络抖一下不该被拦在门外。
   *
   * @return [StreamProbe.Ok] 可播（含跟完跳转后的最终地址，可直接交给播放器）；
   *         [StreamProbe.Denied] 明确不可播，`message` 是可直接展示给用户的中文原因；
   *         [StreamProbe.Unknown] 探不出来，调用方照旧播放
   */
  fun probeStreamUrl(url: String): StreamProbe {
    // 本地文件 / content:// / 本地 ISO 代理不需要探测，直接放行
    if (!url.startsWith("http://") && !url.startsWith("https://")) return StreamProbe.Ok(url)
    return runCatching {
      val req =
        Request.Builder()
          .url(url)
          .header("Range", "bytes=0-0")
          .header("User-Agent", EmbyClient.STREAM_USER_AGENT)
          .get()
          .build()
      val call = EmbyHttpCore.httpClient.newCall(req)
      call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      call.execute().use { resp ->
        val code = resp.code
        when {
          resp.isSuccessful -> StreamProbe.Ok(resp.request.url.toString())
          code == 401 -> StreamProbe.Denied("登录状态已失效（HTTP 401），请重新登录这台服务器")
          code == 403 ->
            StreamProbe.Denied("服务器拒绝访问（HTTP 403）—— 网盘直链可能已过期，或这个文件没有访问权限")
          code == 404 ->
            StreamProbe.Denied("服务器找不到这个文件（HTTP 404）—— 它可能已被移动、重命名或删除")
          code in 500..599 -> StreamProbe.Denied("服务器出错了（HTTP $code），请稍后重试")
          else -> StreamProbe.Denied("服务器返回 HTTP $code，无法播放")
        }
      }
    }.getOrElse { StreamProbe.Unknown }
  }

  /** [probeStreamUrl] 的单次探测超时（毫秒）。 */
  private const val PROBE_TIMEOUT_MS = 2_500L
}

/**
 * [EmbyStreamApi.probeStreamUrl] 的探测结果。
 *
 * 三态而不是布尔，是因为「探不出来」和「明确不可播」必须分开：
 * 前者（超时、DNS 失败）要照旧放行播放，后者（403 / 404）要在进播放器之前就拦住 ——
 * 把死链交给 mpv 会 native 崩溃（整个 App 消失，Java 层拦不住）。
 */
sealed interface StreamProbe {
  /**
   * 可以播。
   *
   * [finalUrl] 是**跟完 302 跳转之后**的最终地址；没发生跳转时就是传进来的原地址。
   * 播放器直接用它即可（也顺手解决了「内核不跟 302」那类问题）。
   */
  data class Ok(val finalUrl: String) : StreamProbe

  /** 明确不可播。[message] 是可直接展示给用户的中文原因。 */
  data class Denied(val message: String) : StreamProbe

  /** 探不出来（超时 / 网络异常 / 不支持探测）。调用方按「可播」处理，别挡着用户。 */
  object Unknown : StreamProbe

  /**
   * 可直接交给播放器的地址。
   *
   * [Denied] / [Unknown] 都是 null —— 注意 null 的语义是「不知道」，不是「不能播」，
   * 调用方应当回退到原始流地址，而不是当成失败。
   */
  val urlOrNull: String?
    get() = (this as? Ok)?.finalUrl
}
