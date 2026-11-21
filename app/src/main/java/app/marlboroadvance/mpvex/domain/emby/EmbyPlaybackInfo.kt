package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════════════
// PlaybackInfo 的响应 DTO（从 EmbyClient 末尾原样搬出，可见性 private → internal）
// ════════════════════════════════════════════════════════════════════════
//
// 这两个类型同时被「会话上报」（EmbySessionApi）与「取流」（后续 EmbyStreamApi）
// 使用，所以单独成文件，不挂在任何一侧。

/**
 * `POST /Items/{id}/PlaybackInfo` 的响应。
 *
 * 只声明 [transcodeStreamUrl] 真正用得到的那几个字段 —— Emby 的完整响应有几十个字段，
 * 靠 `ignoreUnknownKeys` 忽略掉即可（`json` 已配置）。
 */
@Serializable
internal data class PlaybackInfoResult(
  val MediaSources: List<PlaybackMediaSource>? = null,
  /** 本次转码会话 Id，自己拼 URL 时要带上，服务器靠它复用 / 回收转码进程 */
  val PlaySessionId: String? = null,
  /** 拿不到可用源时的错误码，例如 `NoCompatibleStream` */
  val ErrorCode: String? = null,
)

/** [PlaybackInfoResult] 里的一条媒体源 */
@Serializable
internal data class PlaybackMediaSource(
  /** 即 MediaSourceId */
  val Id: String? = null,
  /** 服务器拼好的转码地址（通常是 HLS 的 master.m3u8），优先用它 */
  val TranscodingUrl: String? = null,
  val DirectStreamUrl: String? = null,
  /**
   * 媒体路径。对 `.strm` 条目，Emby 在这里返回的是 **strm 文件里的远端地址**
   * （同时 `Protocol=Http` / `IsRemote=true`），这是「STRM 直链播放」的来源。
   */
  val Path: String? = null,
  /** 这条媒体源要求客户端拉流时携带的请求头（可能为空）。 */
  val RequiredHttpHeaders: Map<String, String>? = null,
)
