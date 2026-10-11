package app.marlboroadvance.mpvex.domain.danmaku

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * dandanplay（弹弹play开放平台）的响应模型。
 *
 * 坐标在 [DanmakuClient] 里可自定义（设置 → 弹幕），默认 `https://api.dandanplay.net`。
 * 自建服务端（danmu_api 之类）也是同一套协议，把 host 指过去即可。
 *
 * ⚠️ 三个端点都是 `GET`，且**只有 `/comment/{id}` 不需要签名**；
 * `search/anime` 与 `bangumi/{id}` 在官方服务上要 AppId/AppKey 签名（`withSign`）。
 * 自建服务端一般不校验签名，所以这里把签名字段留成可空 —— 拿不到就发空串。
 */
@Serializable
data class DandanSearchResponse(
  @SerialName("success") val success: Boolean = false,
  @SerialName("errorCode") val errorCode: Int? = null,
  @SerialName("animes") val animes: List<DandanAnime> = emptyList(),
)

/** `GET /api/v2/search/anime` 里的一条命中。 */
@Serializable
data class DandanAnime(
  @SerialName("animeId") val animeId: Long = 0,
  @SerialName("animeTitle") val animeTitle: String = "",
  @SerialName("type") val type: String? = null,
  @SerialName("typeDescription") val typeDescription: String? = null,
  @SerialName("episodeCount") val episodeCount: Int? = null,
  /** 部分自建服务端会直接在这里带上分集，省一次 bangumi 请求。 */
  @SerialName("episodes") val episodes: List<DandanEpisode> = emptyList(),
)

/** 一集（正片一集 = 一条弹幕时间轴）。 */
@Serializable
data class DandanEpisode(
  @SerialName("episodeId") val episodeId: Long = 0,
  @SerialName("episodeTitle") val episodeTitle: String = "",
  @SerialName("episodeNumber") val episodeNumber: String? = null,
  /** `withRelated`=true 时服务端会带上同名的其它剧集候选，手动匹配用。 */
  @SerialName("animeId") val animeId: Long? = null,
  @SerialName("animeTitle") val animeTitle: String? = null,
)

/** `GET /api/v2/bangumi/{id}` 的响应。 */
@Serializable
data class DandanBangumiResponse(
  @SerialName("success") val success: Boolean = false,
  @SerialName("bangumi") val bangumi: DandanBangumi? = null,
)

@Serializable
data class DandanBangumi(
  @SerialName("animeId") val animeId: Long = 0,
  @SerialName("animeTitle") val animeTitle: String = "",
  @SerialName("episodes") val episodes: List<DandanEpisode> = emptyList(),
)

/** `GET /api/v2/comment/{episodeId}` 的响应。 */
@Serializable
data class DandanCommentResponse(
  @SerialName("count") val count: Int = 0,
  @SerialName("comments") val comments: List<DandanComment> = emptyList(),
  /** `duration=true` 时服务端会回总时长（毫秒），用于「时长对不上就别硬塞」。 */
  @SerialName("duration") val durationMillis: Long? = null,
)

/**
 * 一条弹幕。
 *
 * ⚠️ **`p` 是 csv 而不是 JSON** —— 这是 dandanplay 的历史包袱（为了兼容 B 站弹幕格式）：
 * `时间(秒),类型,字体大小,颜色(十进制 RGB),时间戳,弹幕池,用户Hash,弹幕ID`
 *
 * 类型：1=右→左滚动、4=底部固定、5=顶部固定、6=左→右滚动。
 * 注意 dandanplay 的部分实现只回 4 个字段（时间,类型,颜色,时间戳），
 * 所以 [DanmakuComment] 那边按「切出几段用几段」的方式解析，不写死 8 段。
 */
@Serializable
data class DandanComment(
  @SerialName("cid") val cid: Long = 0,
  @SerialName("p") val p: String = "",
  @SerialName("m") val m: String = "",
)