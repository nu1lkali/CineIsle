package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ════════════════════════════════════════════════════════════════════════
// Emby REST API 数据模型
//
// 这里只定义播放器需要的字段 —— Emby 返回的 JSON 字段远多于此，
// kotlinx.serialization 配合 `ignoreUnknownKeys = true` 会自动丢弃多余字段。
//
// 清单 1.5 拆分第 1 刀：从 EmbyClient.kt 独立出来。纯数据、零逻辑，
// 搬动不改变任何运行时行为；好处是「Emby 返回的字段长什么样」这件事
// 有了固定的查阅入口，不用在三千行的客户端里往上翻。
//
// ⚠️ 字段名刻意保持 Emby 的 PascalCase（`Id` / `Name` / `Type`…），
//    不要「顺手改成 Kotlin 惯例」—— kotlinx.serialization 靠字段名匹配 JSON，
//    改名就等于要求所有 Emby 版本都返回驼峰，直接把整个客户端打瘫。
// ════════════════════════════════════════════════════════════════════════

@Serializable
data class EmbyAuthResult(
  val User: EmbyUserDto? = null,
  val SessionInfo: JsonObject? = null,
  val AccessToken: String? = null,
  val ServerId: String? = null,
)

@Serializable
data class EmbyUserDto(
  val Id: String? = null,
  val Name: String? = null,
  val PrimaryImageTag: String? = null,
)

@Serializable
data class EmbySystemInfo(
  val ServerName: String? = null,
  val Version: String? = null,
  val Id: String? = null,
  val OperatingSystem: String? = null,
)

@Serializable
data class EmbyItemsResult(
  val Items: List<EmbyItem> = emptyList(),
  val TotalRecordCount: Int = 0,
)

/**
 * 新建播放列表的结果（`POST /Playlists` 的响应）。
 *
 * @param Id 新建的播放列表 Id，后续所有操作都靠它
 * @param Name 服务端回显的名字
 * @param ItemAddedCount 实际加入的条数（可能小于请求数 —— 服务端会去重）
 */
@Serializable
data class EmbyPlaylistCreationResult(
  val Id: String? = null,
  val Name: String? = null,
  val ItemAddedCount: Int = 0,
)

/**
 * 往播放列表追加条目的结果（`POST /Playlists/{Id}/Items` 的响应）。
 *
 * @param ItemAddedCount **本次真正新加入**的条数。
 *   重复添加同一批时会返回 0，所以它同时也是「是否需要刷新」的信号：
 *   为 0 就说明列表内容没变，不必重新拉一遍。
 */
@Serializable
data class EmbyPlaylistAddResult(
  val Id: String? = null,
  val ItemAddedCount: Int = 0,
)

/**
 * 新建合集的结果（`POST /Collections` 的响应）。
 *
 * ⚠️ 服务端在这条路径上返回的字段很不稳定：有的部署只回 `Id`，
 * 有的连 `Id` 都不回（只在 `Location` 头里给新资源地址）。
 * 所以 [Id] 允许为 null —— 调用方**必须**处理「建了但拿不到 Id」这种情况，
 * 典型做法是提示用户「已创建，请刷新列表」而不是当成失败。
 */
@Serializable
data class EmbyCollectionCreationResult(
  val Id: String? = null,
  val Name: String? = null,
)

/**
 * 一条在线字幕候选（`GET /Items/{Id}/RemoteSearch/Subtitles/{Language}` 返回的元素）。
 *
 * ## 字段名大小写是「坑」
 *
 * 三个布尔字段在参考客户端里 Java getter 叫 `isIsHashMatch()`（双 is，很难看），
 * 但 `@SerializedName` 上写的是 **`IsHashMatch`** —— 也就是说**服务端 JSON 里的键
 * 就是 `IsHashMatch`**，照抄布尔 getter 命名会让 `ignoreUnknownKeys` 直接把整个字段丢掉，
 * 而丢掉 `IsHashMatch` 就丢掉了最重要的一条信息（这条字幕是不是精确匹配本片）。
 *
 * @param Id 提供方内部的字幕 ID —— **不是文件地址**。要下载必须经
 *   [EmbyClient.downloadRemoteSubtitle] 让服务端去下。
 * @param Format 字幕格式（`srt` / `ass`…）。下载时要向服务端指定转码目标格式。
 * @param Name 提供方给这条字幕起的名字，通常是 `Movie.Name.2020.Chinese.srt` 这样，
 *   里面有文件名匹配信息，所以列表里要显示它。
 * @param IsHashMatch 是否与文件名哈希**精确匹配**（质量最高）。
 * @param CommunityRating 评分（0~10 区间，也可能服务端给 0 表示没评分）
 * @param DownloadCount 被下载过的次数 —— 可当「热度」参考，比评分更能反映实际可用性
 */
@Serializable
data class EmbyRemoteSubtitle(
  val Id: String? = null,
  val Name: String? = null,
  val ProviderName: String? = null,
  val Format: String? = null,
  val Author: String? = null,
  val Comment: String? = null,
  val Language: String? = null,
  val ThreeLetterISOLanguageName: String? = null,
  val CommunityRating: Float? = null,
  val DownloadCount: Int? = null,
  val DateCreated: String? = null,
  val IsHashMatch: Boolean = false,
  val IsForced: Boolean = false,
  val IsHearingImpaired: Boolean = false,
)

/**
 * 让服务端存下在线字幕后的结果（`POST /Items/{Id}/RemoteSearch/Subtitles/{SubtitleId}`）。
 *
 * ⚠️ **只有一个字段** [NewIndex] —— 服务端不返回字幕 Id、不返回名字。
 * 拿它去调 [EmbyClient.downloadSubtitleStream] 的 `Index` 参数。
 *
 * @param NewIndex 新字幕轨在媒体流里的索引；为 null 表示服务端存失败了
 *   （常见于字幕源超时或格式不支持）
 */
@Serializable
data class EmbySubtitleDownloadResult(
  val NewIndex: Int? = null,
)

/**
 * 批量操作的汇总结果。
 *
 * Emby 没有批量端点（见 [EmbyClient.setFavoriteMany]），所以批量操作在客户端逐条跑，
 * 这里记录成功 / 失败条数与首个失败原因 —— UI 据此提示「成功 N / 失败 M」，
 * 而不是含糊的「操作完成」。
 *
 * @param ok 成功条数
 * @param failed 失败条数
 * @param firstError 第一条失败的原因（可能为 null，表示失败但拿不到信息）
 */
data class EmbyBatchResult(
  val ok: Int,
  val failed: Int,
  val firstError: String? = null,
) {
  val total: Int get() = ok + failed
  /** 全部成功（且有内容）才算 success */
  val isAllOk: Boolean get() = failed == 0 && ok > 0
}

/**
 * 媒体库筛选面板的可选项集合。
 *
 * 四个维度都来自 Emby 的「按名字聚合」端点（/Genres、/Tags、/Years、/OfficialRatings），
 * 即该库里真实出现过的值。任一维度为空表示服务端没给或不可用，UI 会隐藏对应分组。
 */
data class EmbyFilterOptions(
  val genres: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  /**
   * 类型 / 标签的「Id + 名字」版本，与 [genres] / [tags] 一一对应。
   *
   * 为什么两套都留：**名字给人看**（UI 标签），**Id 才是发给服务端的**。
   * Emby 官方已明确推荐用 `GenreIds` / `TagIds` 按 Id 筛（按名字的 `Genres` / `Tags`
   * 是旧路径，在部分服务端会踩到坏列直接 500）。两个列表的名字顺序一致，
   * 名字 → Id 的映射由调用方按 name 查。
   */
  val genreItems: List<EmbyIdName> = emptyList(),
  val tagItems: List<EmbyIdName> = emptyList(),
  /** 发行年份，新的在前 */
  val years: List<Int> = emptyList(),
  val officialRatings: List<String> = emptyList(),
  /** 演员 / 导演 / 编剧（走 /Persons，带 PersonTypes 过滤） */
  val persons: List<EmbyIdName> = emptyList(),
  /** 工作室（出品方） */
  val studios: List<EmbyIdName> = emptyList(),
)

/**
 * 需要「按 Id 筛选」的可选项：名字给人看，Id 才是传给 /Items 的。
 *
 * 类型、标签这类是按**名字**筛（Genres=动作），
 * 演员和工作室是按**Id** 筛（PersonIds=xxx），同名不同人靠 Id 才能区分。
 */
@Serializable
data class EmbyIdName(
  val id: String,
  val name: String,
)

/** 筛选条件的持久化快照（按媒体库分别存，进库时恢复）。 */
@Serializable
data class EmbyLibraryFilterState(
  val genres: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val years: List<Int> = emptyList(),
  val officialRatings: List<String> = emptyList(),
  val personIds: List<String> = emptyList(),
  val studioIds: List<String> = emptyList(),
  /** null 不限，true 已看，false 未看 */
  val isPlayed: Boolean? = null,
  /** null 不限，true 高清 / false 标清 */
  val isHD: Boolean? = null,
  /** null 不限，true 3D / false 非 3D */
  val is3D: Boolean? = null,
  /** null 不限，true 有字幕 / false 无字幕 */
  val hasSubtitles: Boolean? = null,
  /**
   * 只看文件名带「中文字幕」标记的（-C / CHS / 简体 / 中字 等，客户端按文件名匹配，
   * Emby 服务端没有这类筛选）。
   */
  val chineseSubsOnly: Boolean = false,
  val minRating: Float? = null,
  val favoriteOnly: Boolean = false,
) {
  /** 全空 = 没有生效条件，用于决定图标高亮与「恢复默认」是否可用 */
  fun isEmpty(): Boolean =
    genres.isEmpty() && tags.isEmpty() && years.isEmpty() && officialRatings.isEmpty() &&
      personIds.isEmpty() && studioIds.isEmpty() && isPlayed == null && isHD == null &&
      is3D == null && hasSubtitles == null && minRating == null && !favoriteOnly &&
      // 「中文字幕」也是筛选条件之一，漏了它会让「恢复默认」在只有它生效时显示成可点
      !chineseSubsOnly
}

/**
 * Emby 媒体项。Emby 的 Item 类型丰富（Movie/Series/Episode/MusicAlbum/...），
 * 这里只定义播放器最关心的字段；UI 层根据 [type] 分支显示。
 */
@Serializable
data class EmbyItem(
  val Id: String? = null,
  val Name: String? = null,
  val OriginalTitle: String? = null,
  val Type: String? = null,
  val MediaType: String? = null,
  /** 媒体库类型：movies / tvshows / music / homevideos / photos / books / mixed（仅库视图有） */
  val CollectionType: String? = null,
  /** 季号（剧集隶属于第几季） */
  val ParentIndexNumber: Int? = null,
  /** 集号 / 季序号 */
  val IndexNumber: Int? = null,
  val SeriesName: String? = null,
  val SeasonName: String? = null,
  val SeriesId: String? = null,
  val SeasonId: String? = null,
  val ParentId: String? = null,
  val PremiereDate: String? = null,
  val DateCreated: String? = null,
  val Overview: String? = null,
  val RunTimeTicks: Long? = null,
  val ProductionYear: Int? = null,
  val CommunityRating: Double? = null,
  val OfficialRating: String? = null,
  /** 各类型图片 tag map：{Primary: "xxx", Backdrop: "yyy", ...} */
  val ImageTags: Map<String, String> = emptyMap(),
  /** 用于 backdrop 等多张图的 tags 数组（按 image type 分组） */
  val BackdropImageTags: List<String> = emptyList(),
  val UserData: EmbyUserData? = null,
  /** 对于 Series 类型：季总数 */
  val ChildCount: Int? = null,
  /** 对于 Series：季列表 */
  val Seasons: List<EmbyItem>? = null,
  /** 对于 Series：剧集列表 */
  val Episodes: List<EmbyItem>? = null,
  /** 媒体源信息（包含容器、码率等） */
  val MediaSources: List<EmbyMediaSource>? = null,
  /** 演员/导演等人员 */
  val People: List<EmbyPerson>? = null,
  /** 类型标签（"动作"、"科幻" 等） */
  val Genres: List<String> = emptyList(),
  /** 工作室 */
  val Studios: List<EmbyStudio>? = null,
  /** 标签（Emby 的 Tags，和 Genres 是两套东西） */
  val Tags: List<String> = emptyList(),
  /** 排序名：Emby 内部排序用，界面一般不显示，但编辑元数据要能改 */
  val SortName: String? = null,
  /** 制作地区 / 国家 */
  val ProductionLocations: List<String> = emptyList(),
  val Path: String? = null,
  val Taglines: List<String> = emptyList(),
  val ProviderIds: Map<String, String> = emptyMap(),
  /**
   * 媒体文件总大小（字节），供「按文件大小排序」使用。
   *
   * 只有请求时 [ITEM_FIELDS] 带上 `Size` 服务端才会返回；取不到时排序会退回
   * [MediaSources] 里第一个源的大小（两者一致），两边都没有就按 0 处理。
   */
  val Size: Long? = null,
  /**
   * 影评人评分（0~100）。
   *
   * 只用于客户端排序（服务端对「搜索 + SortBy」这个组合不保证认），界面不显示。
   * 加在末尾并给默认值，避免影响既有的按位置构造。
   */
  val CriticRating: Double? = null,
  /**
   * 服务端为「这一条 / 这个库」分配显示偏好分区 Id（`BaseItemDto.DisplayPreferencesId`）。
   *
   * 显示偏好（网格密度、视图模式……）**不是存在用户账号上的**，而是按这个 Id 分区存的：
   * 每个媒体库、每个单条目各有一份。这也是为什么同步显示偏好必须先知道目标库的
   * 这个字段 —— 它不是用户 Id，用错分区就写到别人的地方去了。
   *
   * 只有部分部署会返回，缺了就退化成用 [Id] 顶上（Emby 自己也接受条目 Id 作为分区 Id）。
   */
  val DisplayPreferencesId: String? = null,
  /**
   * 播放列表条目指向的**媒体本身**的 Id（`BaseItemDto.MediaItemId`）。
   *
   * `GET /Playlists/{Id}/Items` 返回的每一行是一条 `PlaylistItem`（播放列表内的「条目」），
   * 它的 [Id] 是条目自己的 Id，**不是媒体的 Id**。拿 [Id] 去请求媒体详情会拿到 404/500
   * （服务端按媒体 Id 查不到东西）。所以播放列表里点条目进详情页必须用这个字段。
   *
   * 只在播放列表接口的返回里有；其它接口的条目上它恒为 null，此时继续用 [Id]。
   */
  val MediaItemId: String? = null,
  /**
   * 直播电视频道号（`BaseItemDto.ChannelNumber`，**是字符串**：常见 "12"，
   * 也有带小数分的 "12.1"）。只有 TvChannel 类型的条目有，其余恒为 null。
   *
   * c1：频道网格左下角的小角标就是它（以前 [EmbyItem] 没这个字段，
   * `EmbyLiveTvScreen` 里只能干放着台标）。
   */
  val ChannelNumber: String? = null,
  /**
   * 节目单条目的开播时间（ISO 8601 UTC，如 `2026-10-10T20:00:00Z`）。
   *
   * 只有 Program 类型的条目有（`BaseItemDto.StartDate`）。「正在播出」的判据就是
   * 当前时刻夹在 [StartDate] 与 [EndDate] 之间。
   */
  val StartDate: String? = null,
  /** 节目单条目的结束时间（ISO 8601 UTC，`BaseItemDto.EndDate`）。 */
  val EndDate: String? = null,
  /** 该节目是否正在直播（`BaseItemDto.IsLive`；重播/录播为 false）。 */
  val IsLive: Boolean? = null,
  // ——— 照片（Photo）专属字段 ———
  // 只在使用照片专用 fields 请求（见 EmbyClient.PHOTO_FIELDS）时才可能非空，
  // 视频条目上恒为 null。全部加在末尾并给默认值，不影响既有按位置构造。

  /** 图片像素宽（`BaseItemDto.Width`，服务端 EXIF 探测值）。 */
  val Width: Int? = null,
  /** 图片像素高（`BaseItemDto.Height`）。 */
  val Height: Int? = null,
  /** 文件 MIME 类型（`BaseItemDto.MimeType`，如 `image/jpeg` / `image/heic`）。 */
  val MimeType: String? = null,
  /** 相机厂商（`BaseItemDto.CameraMake`，EXIF）。 */
  val CameraMake: String? = null,
  /** 相机型号（`BaseItemDto.CameraModel`，EXIF）。 */
  val CameraModel: String? = null,
  /** 生成该文件的软件（`BaseItemDto.Software`，EXIF）。 */
  val Software: String? = null,
  /** 曝光时间，秒（`BaseItemDto.ExposureTime`，如 0.008 表示 1/125s）。 */
  val ExposureTime: Double? = null,
  /** 焦距，毫米（`BaseItemDto.FocalLength`）。 */
  val FocalLength: Double? = null,
  /**
   * 图片方向（`BaseItemDto.ImageOrientation`，字符串枚举：
   * `TopLeft`/`TopRight`/`BottomRight`/`BottomLeft`/`LeftTop`/`RightTop`/`RightBottom`/`LeftBottom`）。
   * 用 String 而不是 enum 类：服务端可能返回清单外的新值，不能让它炸掉整条 JSON 解析。
   */
  val ImageOrientation: String? = null,
  /** 光圈值 f 值（`BaseItemDto.Aperture`，如 2.8）。 */
  val Aperture: Double? = null,
  /** 快门速度（`BaseItemDto.ShutterSpeed`，如 125 表示 1/125s）。 */
  val ShutterSpeed: Double? = null,
  /** GPS 纬度（`BaseItemDto.Latitude`，十进制度）。 */
  val Latitude: Double? = null,
  /** GPS 经度（`BaseItemDto.Longitude`，十进制度）。 */
  val Longitude: Double? = null,
  /** GPS 海拔，米（`BaseItemDto.Altitude`）。 */
  val Altitude: Double? = null,
  /** ISO 感光度（`BaseItemDto.IsoSpeedRating`，如 400）。 */
  val IsoSpeedRating: Int? = null,
  // ——— 主图「借用」字段（容器条目） ———
  // 普通条目（Movie/Series/Photo）的 Primary 图就在自己身上，tag 在 [ImageTags] 里。
  // 但**容器条目**（相册 PhotoAlbum、部分 Folder）自身没有 Primary 图，服务端会把
  // 「借来的」那张图的归属单独写在这两个字段上：
  //   GET /Items/{相册Id}/Images/Primary            -> 404 "does not have an image of type Primary"
  //   GET /Items/{PrimaryImageItemId}/Images/Primary -> 200 image/jpeg
  // 两个字段是 BaseItemDto 的顶层属性（不受 Fields 白名单限制），任何查询都会返回。
  // 判定与取值统一走 `domain/emby/EmbyPrimaryImage.kt` 的 primaryImageRef()，
  // 别在 UI 层各写一份（红线 58：同一份信息只允许一处实现）。

  /** 服务端给主图分配的 tag（`BaseItemDto.PrimaryImageTag`）；与 [PrimaryImageItemId] 配套。 */
  val PrimaryImageTag: String? = null,
  /** 主图**实际所在条目**的 Id（`BaseItemDto.PrimaryImageItemId`）。 */
  val PrimaryImageItemId: String? = null,
  /**
   * 带 Id 的标签列表（`BaseItemDto.TagItems`）。
   *
   * ⚠️ **和 [Tags] 是同一份数据的两种形态，但不是任何部署都给全**：
   * `Tags` 是纯名字数组，`TagItems` 每项带 `Name` + `Id`。有些服务端 / 元数据插件
   * 只写 `TagItems` 而把 `Tags` 留空（有部署就是这样：响应里有 `TagItems` 三条，
   * 却**完全没有 `Tags` 字段**），所以详情页读标签时必须
   * 「优先 [TagItems]，为空再退回 [Tags]」—— 只看 `Tags` 会一条都显示不出来。
   *
   * 与 [Genres] / `GenreItems` 是同一套关系。加在末尾并给默认值，不影响既有按位置构造。
   */
  val TagItems: List<EmbyNameIdPair> = emptyList(),
  /**
   * 条目在**外部网站**上的链接（`BaseItemDto.ExternalUrls`，每项 `Name` + `Url`）。
   *
   * 这是详情页「外部链接」一行的**首选**数据源，原因见 `EmbyDetailScreen` 里的定位说明：
   * `Url` 是**服务端/元数据插件已经拼好的完整地址**（如
   * `http://<host>:<port>/?redirect=JavBus:ACZD-218`），直接用就和 Emby Web、
   * 以及 Yamby 这类第三方客户端打开的是同一条链接；而拿 [ProviderIds] 自己拼
   * 很容易拼错（见那边的注释）。
   *
   * 不是每个部署都会返回（内置元数据源通常不产生 ExternalUrls，只有插件型数据源
   * 如 MetaTube 会），所以只是「优先」，取不到时详情页退回 [ProviderIds] 现拼。
   * 加在末尾并给默认值，不影响既有按位置构造。
   */
  val ExternalUrls: List<EmbyExternalUrl> = emptyList(),
  /**
   * 这一集所属的「同季兄弟集 Id 列表」（`BaseItemDto.SiblingIds`）。
   *
   * 只有 `Episode` 有，且**只有请求的 `Fields` 里带上 `SiblingIds` 才返回**。
   * 用途见详情页的「相关分集」：从一部剧的某一集进详情页时，把同季其它集也列出来，
   * 否则用户点进第 3 集就只能看到第 3 集本身（Yamby 的 `siblingEpisodes` 就是干这个的）。
   *
   * 它与「本剧全部分集」(`/Shows/{id}/Episodes`) 是**两个代价完全不同**的请求：
   * 这一个随详情一起回来、零额外往返，所以从单集进详情页时优先用它。
   * 加在末尾并给默认值，不影响既有按位置构造。
   */
  val SiblingIds: List<String> = emptyList(),
  /**
   * 这一集是不是「特别篇 / 花絮 / 幕后 / 预告」等非正片（`BaseItemDto.IsSpecialFeature`
   * / `ExtraType`）。
   *
   * 详情页的「相关分集」用它把正片与特别篇**分区呈现**：混在一起会让「第 3 集、
   * 第 4 集、幕后花絮、第 5 集」这种顺序看起来像番号错乱。
   * 服务端不一定返回这一个字段，所以列表里**保留**它、只做分区提示，不做过滤 ——
   * 判错时最坏情况是「本该分区的没分区」，而不是「某一集凭空消失了」。
   */
  val IsSpecialFeature: Boolean? = null,
  val ExtraType: String? = null,
)

/**
 * 条目的一个外部链接（`ExternalUrl`）：显示名 + 完整地址。
 *
 * 与 [EmbyExternalIdInfo] 的区别：那个描述「这个服务端支持哪些外部 ID」
 * （`Key` / `Name` / `UrlFormatString`，值是**格式串**），
 * 这个则是一条**已经拼好的、点开就能访问的**链接。
 */
@Serializable
data class EmbyExternalUrl(
  /** 显示名（如 `MetaTube` / `IMDb`），详情页 chip 上显示的就是它 */
  val Name: String? = null,
  /** 完整 URL（服务端已拼好，直接交给系统浏览器，不要再加工） */
  val Url: String? = null,
)

/**
 * Emby 的「名字 + Id」成对条目。
 *
 * `TagItems` / `GenreItems` 这类「带 Id 的元数据清单」都是这个形状（官方 schema 里
 * 每项至少 `Name` + `Id`）。与 [EmbyStudio] 字段完全一样，但**语义不同**，
 * 所以单独一个类型而不是复用 `EmbyStudio` —— 否则读代码的人会以为标签是「工作室」。
 */
@Serializable
data class EmbyNameIdPair(
  val Id: String? = null,
  val Name: String? = null,
)

/**
 * 显示偏好（`DisplayPreferences`，**类名没有 `Dto` 后缀**，别自己加）。
 *
 * 这是清单 #16「显示偏好持久化」唯一可用的服务端通道，重要结论有三条：
 *
 * 1. **不要**写到 `POST /Users/{Id}/Configuration` 去 —— `UserConfiguration` 的 17 个字段
 *    全是播放 / 字幕 / 语言类的（音频语言、字幕语言、ProfilePin、跳过片头…），
 *    **一个显示字段都没有**，写进去网页端也读不到。
 * 2. 真正的落点是 `CustomPrefs`（`Map<String, String>`），另外 `SortBy` / `SortOrder` 是
 *    DTO 上的独立字段，不在 `CustomPrefs` 里。
 * 3. `Client` 是**必填的分区键**：同一份偏好在 Emby Web（`emby-web`）和本 App 下是两份，
 *    互不干扰 —— 这既是限制（跨端不同步），也是好处（不会把网页端的排版冲掉）。
 */
@Serializable
data class EmbyDisplayPreferences(
  val Id: String? = null,
  /** 排序字段（对应 `/Items` 的 `SortBy`），**不是** CustomPrefs 里的键 */
  val SortBy: String? = null,
  val CustomPrefs: Map<String, String> = emptyMap(),
  val SortOrder: String? = null,
  val Client: String? = null,
)

/**
 * 单个章节。
 *
 * 字段名与 Emby 返回的 JSON 保持一致（`StartPositionTicks` / `Name`），
 * 别自己改成驼峰 —— 少写一个 `SerialName` 就静默变成默认值 0，
 * 表现为「所有章节都叠在 0 秒」，是那种很难一眼看出来的 bug。
 */
@Serializable
data class EmbyChapter(
  /** 起点，**单位是 100 纳秒**（.NET `TimeSpan`  ticks，1ms = 10000） */
  @SerialName("StartPositionTicks") val StartPositionTicks: Long = 0,
  val Name: String? = null,
  @SerialName("ImageTag") val ImageTag: String? = null,
) {
  /**
   * 起点毫秒。
   *
   * 1 tick = 100ns ⇒ 1ms = 10_000 ticks，除以 10000 正好是毫秒（不用先转微秒）。
   * 多数服务端返回的章节起点都是整数秒，除完不会掉精度。
   */
  val startPositionMs: Long get() = StartPositionTicks / 10_000L
}

/**
 * 一段媒体分段（片头 / 片尾 / 前情提要 / 预告 / 广告）。
 *
 * Emby 4.7+ 原生 Intro Skip 与 TheIntroDB 等插件都走这套数据：
 * `GET /Users/{UserId}/Items/{Id}/Intros` 返回 [EmbyIntrosResult]。
 *
 * ⚠️ 本项目是 **Emby 客户端**，端点是 `/Users/{UserId}/Items/{Id}/Intros`；
 * Jellyfin 那套 `/MediaSegments/{Id}` 不要照搬（见 feature-analysis 不做清单）。
 */
@Serializable
data class EmbyMediaSegment(
  /** 分段类型：`Intro` / `Outro` / `Recap` / `Preview` / `Commercial`（大小写随服务端） */
  @SerialName("Type") val Type: String? = null,
  /** 起点，**单位是 100 纳秒**（同 [EmbyChapter.StartPositionTicks]） */
  @SerialName("StartTicks") val StartTicks: Long = 0,
  /** 终点，同上 */
  @SerialName("EndTicks") val EndTicks: Long = 0,
) {
  /** 起点毫秒 */
  val startMs: Long get() = StartTicks / 10_000L

  /** 终点毫秒 */
  val endMs: Long get() = EndTicks / 10_000L

  /** 终点必须严格大于起点，否则这段数据是坏的（服务端偶尔会给 0 长度段） */
  val isValid: Boolean get() = endMs > startMs
}

/**
 * `/Users/{UserId}/Items/{Id}/Intros` 的响应体。
 *
 * 服务端没跑过片头标记扫描任务时返回空 [Segments]（**不是 404**），
 * 所以正常路径下不会抛异常；调用方按「没有精准数据」处理即可。
 */
@Serializable
data class EmbyIntrosResult(
  @SerialName("ItemId") val ItemId: String? = null,
  @SerialName("Segments") val Segments: List<EmbyMediaSegment> = emptyList(),
)

@Serializable
data class EmbyUserData(
  val Played: Boolean = false,
  val PlayCount: Int = 0,
  val IsFavorite: Boolean = false,
  val PlaybackPositionTicks: Long = 0,
  val PlayedPercentage: Double? = null,
  val UnplayedItemCount: Int? = null,
  /** 最近播放时间（ISO-8601 字符串）。客户端按「播放时间」排序用 */
  val LastPlayedDate: String? = null,
)

@Serializable
data class EmbyMediaSource(
  val Id: String? = null,
  val Name: String? = null,
  val Container: String? = null,
  val Path: String? = null,
  // ⚠️ 这里**不要**再加 `DateCreated` / `DateModified`：Emby 的 MediaSourceInfo 没有日期字段
  // （官方文档属性清单 + `embyclient-reference` 生成的模型都确认过），加了服务端也永远返回空，
  // 只会让调用方以为拿到了版本级时间。版本级入库时间服务端不提供，只能显示条目级
  // `EmbyItem.DateCreated`（媒体信息里措辞为「首次入库」）。
  val RunTimeTicks: Long? = null,
  val Size: Long? = null,
  val Bitrate: Long? = null,
  val MediaStreams: List<EmbyMediaStream>? = null,
)

@Serializable
data class EmbyMediaStream(
  val Index: Int? = null,
  val Type: String? = null,
  val Codec: String? = null,
  /** 编码档次，如 HEVC 的 "Main 10"、AAC 的 "LC" */
  val Profile: String? = null,
  /** 编码级别，如 H.264 的 41（对应 4.1） */
  val Level: Double? = null,
  val CodecTag: String? = null,
  val Language: String? = null,
  val DisplayTitle: String? = null,
  val Width: Int? = null,
  val Height: Int? = null,
  val AspectRatio: String? = null,
  val BitRate: Long? = null,
  /** 标称帧率（帧/秒） */
  val FrameRate: Double? = null,
  val AverageFrameRate: Double? = null,
  val RealFrameRate: Double? = null,
  val IsInterlaced: Boolean? = null,
  /** 位深度（8 / 10 / 12） */
  val BitDepth: Int? = null,
  /** 像素格式，如 yuv420p10le */
  val PixelFormat: String? = null,
  /** 动态范围，如 SDR / HDR */
  val VideoRange: String? = null,
  /** 更细的动态范围类型，如 HDR10 / HLG / DolbyVision */
  val VideoRangeType: String? = null,
  val ColorSpace: String? = null,
  val ColorTransfer: String? = null,
  val ColorPrimaries: String? = null,
  /** 参考帧数 */
  val RefFrames: Int? = null,
  // ── 音频流 ──
  val Channels: Int? = null,
  val ChannelLayout: String? = null,
  val SampleRate: Int? = null,
  // ── 字幕流 ──
  val IsExternal: Boolean? = null,
  val IsDefault: Boolean? = null,
  val IsForced: Boolean? = null,
)

@Serializable
data class EmbyPerson(
  val Id: String? = null,
  val Name: String? = null,
  val Type: String? = null,
  val Role: String? = null,
  val PrimaryImageTag: String? = null,
)

@Serializable
data class EmbyStudio(
  val Id: String? = null,
  val Name: String? = null,
)

/**
 * 服务端定时任务（`GET /ScheduledTasks` 的一项）。
 *
 * 只用到 [Key] / [Name] 两个字段去认「刷新人员」那个任务，其余字段忽略。
 * ⚠️ [Name] 是**本地化**的（中文服务器给「刷新人员」，英文给 "Refresh people"），
 * 所以匹配顺序必须先是 [Key]，名字只作兜底。
 */
@Serializable
data class EmbyScheduledTask(
  val Id: String? = null,
  val Key: String? = null,
  val Name: String? = null,
  val Description: String? = null,
  val Category: String? = null,
)
