package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// 4. 播放进度上报（Playstate）
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// ⚠️ 这一段是全项目最容易「看起来成功、其实一条没记上」的地方，
// 下面每个 KDoc 里写清的三条约束（PlaySessionId 自生成 / MediaSourceId 必带 /
// 空串不许发）都是从真机事故里换来的，搬迁时逐字保留。

object EmbySessionApi {
  /**
   * 上报播放开始。调用时机：播放页准备开始播放时。
   *
   * @param playSessionId **客户端自己生成的**会话 id（见下方说明）。传了就会写进 body，
   *   返回值也会在服务端不回显时退回它。
   * @return 本次播放的会话 id —— 服务端回显的就用它的，否则就是传进来的那个。
   */
  /**
   * 向服务器要一个**它签发的**播放会话（`POST /Items/{id}/PlaybackInfo`，`IsPlayback=true`）。
   *
   * @return `PlaySessionId to MediaSources[0].Id`；拿不到（老版本 / 该条目没有可用源 / 网络失败）
   *   返回 null，调用方退回自生成的 UUID。
   *
   * ## 为什么要这一步（v231）
   *
   * v226 起我们是自己造 UUID 当 `PlaySessionId` 的 —— Start / Progress / Stopped 三处一致，
   * 进度确实能记上（位置、百分比都对）。但「**条目什么时候被顶到继续观看最前面**」取决于
   * 服务端有没有把 `LastPlayedDate` 刷新，而那是挂在**服务端会话**上的：自己造的 id 服务端
   * 根本没有这条会话，收尾时就跳过了这一步。
   *
   * 现象因此非常割裂：详情页有进度（位置写了）、首页却不往前提（时间没写）。
   * 实测同一条服务器上，别的播放器（Yamby 先 PlaybackInfo 再上报）播过的片会立刻排到第一。
   *
   * 代价：每次起播多一次请求。它和「预解析直链」无关 —— PlaybackInfo 只打到自己的 Emby，
   * 不碰网盘 CDN，也不会跟 302。
   */
  internal suspend fun requestPlaybackSession(
    server: EmbyServer,
    itemId: String,
  ): Pair<String, String?>? {
    val payload =
      jsonObjectOf(
        mapOf(
          "UserId" to server.userId.takeIf { it.isNotEmpty() },
          // IsPlayback=true 才是「我要播了」（false 是探测），会话的语义在这里
          "IsPlayback" to true,
          "AutoOpenLiveStream" to false,
          "EnableDirectPlay" to true,
          "EnableDirectStream" to true,
          "EnableTranscoding" to true,
        ),
      ).toString()
    val info = runCatching {
      EmbyHttpCore.execJson<PlaybackInfoResult>(
        EmbyHttpCore.authedRequest(server, EmbyPaths.playbackInfo(itemId))
          .post(payload.toRequestBody(EmbyHttpCore.jsonMedia))
          .build(),
      )
    }.getOrNull() ?: return null
    val sessionId = info.PlaySessionId?.takeIf { it.isNotBlank() } ?: return null
    return sessionId to info.MediaSources?.firstOrNull()?.Id
  }

  internal fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    playMethod: String = "DirectStream",
    /** 多版本媒体：本次播放的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
    /**
     * 会话 id（客户端生成，官方客户端也是自己生成 GUID 带上来的）。
     *
     * ⚠️ 为什么必须由客户端给：Emby 的 `POST /Sessions/Playing` 正常返回 **204**（空 body）
     * 或 `{}`，**里面没有 `PlaySessionId` 字段**。只从响应里读的话永远是空串，而
     * Progress / Stopped 拿着空 id 去对账，服务端既匹配不上会话、又不会按 ItemId 兜底，
     * 整场播放一条进度都记不下来 —— 现象就是「退出后已观看 0%，剩余=总时长」。
     */
    playSessionId: String? = null,
  ): String {
    val obj = buildMap {
      put("ItemId", itemId)
      if (!playSessionId.isNullOrBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      put("PlayMethod", playMethod)
      mediaSourceId?.let { put("MediaSourceId", it) }
      audioStreamIndex?.let { put("AudioStreamIndex", it) }
      subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
      put("CanSeek", true)
      put("IsMuted", false)
      put("IsPaused", false)
    }
    val body = EmbyHttpCore.json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = EmbyHttpCore.authedRequest(server, EmbyPaths.SESSIONS_PLAYING)
      .post(body.toRequestBody(EmbyHttpCore.jsonMedia))
      .build()
    val resp = EmbyHttpCore.execString(req)
    // 服务端回显了就用它的（个别版本会）；没回显（204 / 空 JSON，最常见）就用我们自己的。
    return try {
      EmbyHttpCore.json.parseToJsonElement(resp).jsonObject["PlaySessionId"]?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: playSessionId.orEmpty()
    } catch (_: Exception) {
      playSessionId.orEmpty()
    }
  }

  /**
   * 上报播放进度（建议每 10 秒一次 + 暂停/恢复时）。
   *
   * 两个字段**不能省**，理由都是「服务端要靠它们把这条进度归到哪一集、哪个版本」：
   *
   * · `MediaSourceId`：多版本条目（4K / 1080P 各一条）上，Start 带了它、Progress 不带，
   *   服务端会把这次进度记到**默认版本**上，于是别的版本续播位置一直是旧的。
   * · `PlaySessionId`：只在**非空**时发送。送一个空串过去，服务端既匹配不上既有会话，
   *   又不会走「按 ItemId 找当前会话」的兜底，这条 Progress 就被丢掉了；
   *   整个字段缺席反而能命中兜底。
   */
  internal fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
    isMuted: Boolean = false,
    /** 多版本媒体：正在播的那个版本（`MediaSources[i].Id`）；null = 未知 / 单版本 */
    mediaSourceId: String? = null,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      if (playSessionId.isNotBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      put("IsPaused", isPaused)
      put("IsMuted", isMuted)
      put("PlayMethod", "DirectStream")
      mediaSourceId?.let { put("MediaSourceId", it) }
    }
    val body = EmbyHttpCore.json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = EmbyHttpCore.authedRequest(server, EmbyPaths.SESSIONS_PLAYING_PROGRESS)
      .post(body.toRequestBody(EmbyHttpCore.jsonMedia))
      .build()
    EmbyHttpCore.execString(req)
  }

  /** 上报播放停止（退出播放页时调用）。[playSessionId] / [mediaSourceId] 的取舍同上。 */
  internal fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    mediaSourceId: String? = null,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      if (playSessionId.isNotBlank()) put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      mediaSourceId?.let { put("MediaSourceId", it) }
    }
    val body = EmbyHttpCore.json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = EmbyHttpCore.authedRequest(server, EmbyPaths.SESSIONS_PLAYING_STOPPED)
      .post(body.toRequestBody(EmbyHttpCore.jsonMedia))
      .build()
    EmbyHttpCore.execString(req)
  }
}
