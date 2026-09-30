package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ════════════════════════════════════════════════════════════════════════
// 元数据编辑 / 图片管理 / 识别（Identify）用到的数据模型。
//
// 这些都是 Emby 的“管理类”接口（Web 端库菜单里的编辑元数据、编辑图片、识别）
// 才需要的结构，播放链路用不到，所以单独放一个文件，不混进 EmbyClient 的播放模型里。
// ════════════════════════════════════════════════════════════════════════

/**
 * 条目当前挂着的某张图片的信息（GET /Items/{Id}/Images 的一项）。
 *
 * [Url] 是服务器吐出来的原始地址（一般不带 api_key），
 * 展示时仍然走项目自己的 EmbyImageLoader 拼地址，不直接拿它去下载。
 */
@Serializable
data class EmbyImageInfo(
  val ImageType: String? = null,
  val ImageIndex: Int? = null,
  val Url: String? = null,
  /** 这张图的来源：Manual（手动上传）/ Tmdb / Tvdb / FanArt ... */
  val ProviderName: String? = null,
  val Width: Int? = null,
  val Height: Int? = null,
  val Size: Long? = null,
)

/** 远程图源搜出来的一张候选图（RemoteImageResult.Images 的一项） */
@Serializable
data class EmbyRemoteImageInfo(
  val ProviderName: String? = null,
  val Url: String? = null,
  /** 缩略图地址；没有就用 [Url]（原图一般几 MB，列表里直接加载会卡） */
  val ThumbnailUrl: String? = null,
  val Width: Int? = null,
  val Height: Int? = null,
  val Language: String? = null,
  val RatingType: String? = null,
  val Type: String? = null,
  val CommunityRating: Float? = null,
  val VoteCount: Int? = null,
)

/**
 * 「GET /Items/{Id}/RemoteImages」的返回体。
 *
 * **这一层包装不能省**：这个端点返回的是对象 `{Images:[...], Providers:[...], TotalRecordCount:n}`，
 * 不是数组。之前按 `List<EmbyRemoteImageInfo>` 解析，收到对象直接抛异常被 runCatching 吞掉，
 * 表现就是「永远搜不到图」。
 */
@Serializable
data class EmbyRemoteImageResult(
  val Images: List<EmbyRemoteImageInfo> = emptyList(),
  /**
   * 参与本次搜索的图源。**这里刻意不解析成具体结构** —— 实测不同版本 / 插件
   * 返回的形状不一样：有的给字符串数组 `["MetaTube"]`，有的给对象数组
   * `[{Name, SupportedImages}]`。写成 `List<EmbyImageProviderInfo>` 时，前一种
   * 会直接抛 `Expected start of the object '{', but had '"'`（发生在 `$.providers[0]`），
   * 整个搜图就废了。UI 目前只关心 [Images]，所以原样保留、不做反序列化。
   */
  val Providers: JsonElement? = null,
  val TotalRecordCount: Int = 0,
)

/** 可用的远程图源（GET /Items/{Id}/RemoteImages/Providers 的一项） */
@Serializable
data class EmbyImageProviderInfo(
  val Name: String? = null,
  val SupportedImages: List<String> = emptyList(),
)

/**
 * 识别 / 刮削的检索条件（POST /Items/RemoteSearch/{Type} 的请求体）。
 *
 * [ProviderIds] 的键是 Emby 的外部 ID 名称：**Imdb / Tmdb / Tvdb**。
 * 填了其中一个，服务器会直接按这个 ID 精确查，比按标题年份模糊匹配准得多。
 */
@Serializable
data class EmbyItemLookupInfo(
  val Name: String? = null,
  val OriginalTitle: String? = null,
  val Year: Int? = null,
  val ProviderIds: Map<String, String>? = null,
  /** 剧集检索用：季号 / 集号，帮服务器在整部剧里定位到具体一集 */
  val ParentIndexNumber: Int? = null,
  val IndexNumber: Int? = null,
  /** false = 用户手动发起（服务器会优先用本次结果覆盖已有元数据） */
  val IsAutomated: Boolean = false,
)

/**
 * 「POST /Items/RemoteSearch/{Type}」的**请求体外层**。
 *
 * 检索条件不是直接发 [EmbyItemLookupInfo]，而是要套一层 `SearchInfo` ——
 * 直接发裸的 lookup 对象时服务器收到的 `SearchInfo` 是 null，等于什么条件都没给，
 * 于是永远返回空数组（表现：「没有找到匹配的结果」，而 Web 端同一次搜索是有结果的）。
 */
@Serializable
data class EmbyRemoteSearchQuery(
  val SearchInfo: EmbyItemLookupInfo? = null,
  /**
   * 条目 Id —— **按字符串发**。
   *
   * 这里有个坑：官方 OpenAPI 把 `ItemId` 标成 `integer/int64`，但 Emby 服务端
   * `RemoteSearchQuery<T>.ItemId` 实际是 string。实测（直接用 python 打同一台服务器）
   * 传字符串能正常返回候选，传数字时部分版本会在反序列化阶段直接拒绝。
   * 所以主请求统一用字符串，[EmbyClient.remoteSearch] 里还留了一手数字兜底。
   */
  val ItemId: String? = null,
  val SearchProviderName: String? = null,
  /** 连用户在库设置里关掉的元数据源也一起问一遍 */
  val IncludeDisabledProviders: Boolean = false,
)

/**
 * 数字版 ItemId 的同一个请求体，只用于兜底重试。
 *
 * 少数 Emby 版本（严格按 OpenAPI 生成的那批）要求这里必须是数字；
 * 主请求失败时用它再试一次，避免「服务器挑类型」导致刮削永远没结果。
 */
@Serializable
data class EmbyRemoteSearchQueryNumeric(
  val SearchInfo: EmbyItemLookupInfo? = null,
  val ItemId: Long? = null,
  val SearchProviderName: String? = null,
  val IncludeDisabledProviders: Boolean = false,
)

/** 识别结果候选（POST /Items/RemoteSearch/{Type} 返回的一项） */
@Serializable
data class EmbyRemoteSearchResult(
  val Name: String? = null,
  val ProductionYear: Int? = null,
  val ProviderIds: Map<String, String>? = null,
  val ImageUrl: String? = null,
  val Overview: String? = null,
  val SearchProviderName: String? = null,
  val PremiereDate: String? = null,
  /** 剧集才有：季 / 集号 */
  val ParentIndexNumber: Int? = null,
  val IndexNumber: Int? = null,
)

/**
 * 条目的外部 ID 信息（GET /Items/{Id}/ExternalIdInfos 的一项）。
 *
 * 注意这个端点**只描述「支持哪些外部 ID」**，返回体里并没有当前绑定的值
 * （官方 schema 只有 Name / Key / UrlFormatString）。真正的值在 `item.ProviderIds` 里，
 * 所以识别对话框的预填走 ProviderIds，这里只用来补全「支持哪些 ID」。
 */
@Serializable
data class EmbyExternalIdInfo(
  /** 外部 ID 的键名（Imdb / Tmdb / Tvdb） */
  val Key: String? = null,
  /** 显示名（IMDb / TheMovieDb / TheTVDB） */
  val Name: String? = null,
  val UrlFormatString: String? = null,
  /** 少数版本会带上当前值，留着兜底 */
  val Value: String? = null,
)

/**
 * 图片类型的显示名与顺序。
 *
 * 顺序照 Emby Web 端「编辑图片」的排布：先主图，再 logo / 缩略图，最后横幅类。
 * Backdrop 没放进来 —— 它是多张的（索引 0..N），要单独按索引管理，单张增删改的
 * 这套交互套不上。
 */
enum class EmbyImageType(val label: String) {
  PRIMARY("封面"),
  LOGO("徽标"),
  THUMB("缩略图"),
  BANNER("横幅"),
  DISC("光盘"),
  ART("艺术图"),
  ;

  /** 传给 Emby 接口用的类型名（接口里是大写开头的英文） */
  val apiName: String
    get() = when (this) {
      PRIMARY -> "Primary"
      LOGO -> "Logo"
      THUMB -> "Thumb"
      BANNER -> "Banner"
      DISC -> "Disc"
      ART -> "Art"
    }
}
