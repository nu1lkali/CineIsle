package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleMapper
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleStreamFacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// Emby 字幕发现：从服务端「已识别的字幕」里取信息，而不是猜文件名
// ════════════════════════════════════════════════════════════════════════
//
// 与 EmbySubtitles.kt（在线字幕搜索 → 让服务端下载）是两条**完全独立**的路：
//   · 这里读的是 Emby 自己**已经识别到**的字幕（内嵌轨 + 旁挂文件），
//   · 那边是去字幕提供方搜一条**新的**字幕存进媒体库。
// 两者最终都会走到「取字节 → 挂到 mpv」，共用 `EmbySubtitleFetcher`。

/**
 * 一次字幕发现的结果。
 *
 * @property mediaSourceId 实际查的媒体源（null = 用服务端默认源）
 * @property subtitles 归一化后的字幕列表（只含 `Type == "Subtitle"` 的流）
 * @property totalStreams 该媒体源的流总数，用于日志区分「没有字幕」和「压根没拿到流」
 * @property error 失败原因；只是「没有字幕」时**不算失败**（error 为 null、列表为空）
 */
data class SubtitleDiscovery(
  val mediaSourceId: String?,
  val subtitles: List<SubtitleInfo>,
  val totalStreams: Int = 0,
  val error: String? = null,
)

/**
 * 从 `POST /Items/{Id}/PlaybackInfo` 取「当前这条媒体源」上的字幕清单。
 *
 * ## 为什么必须走 PlaybackInfo，而不是用详情页那份 `MediaSources`
 *
 * 1. 详情页的 `MediaStreams` 里**没有** `DeliveryMethod` / `DeliveryUrl` /
 *    `IsTextSubtitleStream` —— 这几个字段是服务端在**计算播放方案**时才填的；
 * 2. 一个 Item 可能有多个 MediaSource，字幕索引**只在同一源内**有意义
 *    （需求六：绝不能拿第一个源的索引去请求第二个源）；
 * 3. `MediaSourceId` 传下去，服务端才会按那个版本回答。
 *
 * ## 失败处理
 *
 * 整个流程**绝不抛异常**：字幕是增强功能，任何一步失败都只让 `error` 有值，
 * 视频该怎么播还怎么播（需求 9 / 十一）。
 * PlaybackInfo 完全打不通时退回 `GET /Items/{Id}` 的 `MediaSources` —— 字段少一些，
 * 但至少能拿到 `Index` / `Language` / `IsExternal`，足够做语言选择。
 */
object EmbySubtitleDiscovery {
  private const val PLAYBACK_INFO_TIMEOUT_HINT = "PlaybackInfo 请求失败"

  /**
   * @param mediaSourceId 当前**实际在播**的媒体源 Id；null / 空 = 交给服务端挑默认源
   */
  fun discover(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
  ): SubtitleDiscovery {
    val source = mediaSourceId?.takeIf { it.isNotBlank() }

    // ── 主路径：PlaybackInfo ──
    val fromPlayback = runCatching { requestPlaybackInfo(server, itemId, source) }
      .getOrElse { t ->
        EmbyLog.warn("字幕发现：$PLAYBACK_INFO_TIMEOUT_HINT（${t.message}）")
        null
      }

    if (fromPlayback != null) {
      val picked = pickSource(fromPlayback.MediaSources, source)
      if (picked != null) {
        val streams = picked.MediaStreams.orEmpty()
        val subtitles = buildSubtitles(streams, picked.Id ?: source)
        EmbyLog.info(
          "字幕发现：item=$itemId ms=${picked.Id ?: source} 流=${streams.size} 字幕=${subtitles.size}",
        )
        return SubtitleDiscovery(
          mediaSourceId = picked.Id ?: source,
          subtitles = subtitles,
          totalStreams = streams.size,
        )
      }
      val reason = "PlaybackInfo 没有返回媒体源" + (fromPlayback.ErrorCode?.let { "（$it）" } ?: "")
      EmbyLog.warn("字幕发现：item=$itemId $reason，退回条目详情")
    }

    // ── 兜底：条目详情里的 MediaSources ──
    val item = runCatching { EmbyClient.getItem(server, itemId) }.getOrNull()
      ?: return SubtitleDiscovery(source, emptyList(), error = "拿不到媒体信息（PlaybackInfo 与条目详情都失败）")

    val picked = pickSource(item.MediaSources, source)
      ?: return SubtitleDiscovery(source, emptyList(), error = "条目没有任何媒体源")
    val streams = picked.MediaStreams.orEmpty()
    val subtitles = buildSubtitles(streams, picked.Id ?: source)
    EmbyLog.info(
      "字幕发现（详情兜底）：item=$itemId ms=${picked.Id ?: source} 流=${streams.size} 字幕=${subtitles.size}",
    )
    return SubtitleDiscovery(
      mediaSourceId = picked.Id ?: source,
      subtitles = subtitles,
      totalStreams = streams.size,
    )
  }

  /**
   * 选中「这次真正要用的媒体源」。
   *
   * ⚠️ 指定了 [wanted] 却拿不到同名源时**退回首条**（而不是返回 null）：
   * 首条就是服务端默认源，也是没有 `MediaSourceId` 时的实际播放源 —— 这是唯一正确的兜底。
   */
  private fun pickSource(sources: List<EmbyMediaSource>?, wanted: String?): EmbyMediaSource? {
    val list = sources.orEmpty()
    if (list.isEmpty()) return null
    if (wanted != null) {
      list.firstOrNull { it.Id == wanted }?.let { return it }
      EmbyLog.warn("字幕发现：指定的媒体源 $wanted 不存在，退回首个源 ${list.first().Id}")
    }
    return list.first()
  }

  private fun buildSubtitles(streams: List<EmbyMediaStream>, mediaSourceId: String?): List<SubtitleInfo> =
    streams
      .filter { it.Type.equals("Subtitle", ignoreCase = true) }
      .mapNotNull { stream ->
        // Index 缺失的字幕流无法请求字节（端点是按 Index 定位的），直接跳过并留痕
        if (stream.Index == null) {
          EmbyLog.warn("字幕流缺少 Index，已跳过（title=${stream.DisplayTitle}）")
          null
        } else {
          runCatching { EmbySubtitleMapper.fromFacts(stream.toSubtitleFacts(), mediaSourceId) }
            .getOrNull()
        }
      }

  private fun requestPlaybackInfo(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
  ): PlaybackInfoDto {
    val payload = buildJsonObject {
      if (server.userId.isNotEmpty()) put("UserId", JsonPrimitive(server.userId))
      // 与 videoStreamUrl 同一口径：只要直连/直串流，别让服务端为「取字幕」去转码
      put("EnableDirectPlay", JsonPrimitive(true))
      put("EnableDirectStream", JsonPrimitive(true))
      put("EnableTranscoding", JsonPrimitive(false))
      put("IsPlayback", JsonPrimitive(false))
      if (mediaSourceId != null) put("MediaSourceId", JsonPrimitive(mediaSourceId))
    }.toString()

    val request = EmbyClient.authedRequest(server, EmbyPaths.playbackInfo(itemId))
      .post(payload.toRequestBody(JSON_MEDIA))
      .build()
    return EmbyClient.execJson(request)
  }

  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

  @Serializable
  private data class PlaybackInfoDto(
    val MediaSources: List<EmbyMediaSource>? = null,
    val PlaySessionId: String? = null,
    val ErrorCode: String? = null,
  )
}

/**
 * `EmbyMediaStream` → [SubtitleStreamFacts]。
 *
 * 单独一层是为了让「字段映射」与「判断逻辑」解耦：判断逻辑在纯 JVM 单测里跑，
 * 这里只做字段搬运，不出任何决策。
 */
fun EmbyMediaStream.toSubtitleFacts(): SubtitleStreamFacts = SubtitleStreamFacts(
  index = Index,
  codec = Codec,
  language = Language,
  displayTitle = DisplayTitle,
  displayLanguage = DisplayLanguage,
  isExternal = IsExternal,
  isTextSubtitle = IsTextSubtitleStream,
  isDefault = IsDefault,
  isForced = IsForced,
  isHearingImpaired = IsHearingImpaired,
  deliveryMethod = DeliveryMethod,
  deliveryUrl = DeliveryUrl,
  deliveryFormat = DeliveryFormat,
  isExternalUrl = IsExternalUrl,
  isChunkedResponse = IsChunkedResponse,
  path = Path,
)
