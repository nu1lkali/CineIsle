package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════
// Emby REST API 数据模型（只定义播放器需要的字段，Emby 返回 JSON 字段远多于此，
// kotlinx.serialization 配合 ignoreUnknownKeys=true 会自动丢弃多余字段）
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
 * 媒体库筛选面板的可选项集合。
 *
 * 四个维度都来自 Emby 的「按名字聚合」端点（/Genres、/Tags、/Years、/OfficialRatings），
 * 即该库里真实出现过的值。任一维度为空表示服务端没给或不可用，UI 会隐藏对应分组。
 */
data class EmbyFilterOptions(
  val genres: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
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
   * 影评人评分（0~100）。
   *
   * 只用于客户端排序（服务端对「搜索 + SortBy」这个组合不保证认），界面不显示。
   * 加在末尾并给默认值，避免影响既有的按位置构造。
   */
  val CriticRating: Double? = null,
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

// ════════════════════════════════════════════════════════════════════════
// EmbyClient —— 单例 HTTP 客户端，所有 Emby REST API 调用集中在此
// ════════════════════════════════════════════════════════════════════════

/**
 * Emby REST API 客户端。基于 OkHttp3 + kotlinx.serialization。
 *
 * 用法：
 * ```
 * val client = EmbyClient
 * val auth = client.authenticateByName(server, "user", "pass")
 * client.saveCredentials(server.id, auth)  // 由 Repository 处理
 * val libs = client.getVirtualFolders(server)  // 媒体库列表
 * val items = client.getItems(server, parentId = libs[0].Id, startIndex = 0, limit = 50)
 * ```
 *
 * 所有方法都会在 IO 线程阻塞（OkHttp 同步），调用方需自行 withContext(Dispatchers.IO)。
 */
object EmbyClient {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  /**
   * 无参数 POST 的请求体。
   *
   * 不能直接发空字符串：Emby 走的是 ServiceStack，对 Content-Type 为 json、
   * body 却是空的 POST 有概率返回 400，请求压根没到业务逻辑。发一个空对象最稳。
   */
  private const val EMPTY_JSON_BODY = "{}"

  /** 无 body 的 POST（OkHttp 的 post 必须给 body，给空串就是 content-length=0，跟 Web 端一致） */
  private val EMPTY_BODY = "".toRequestBody(null)

  /** 视频类媒体（用于把音乐、图片从影视列表中过滤掉） */
  private const val VIDEO_MEDIA_TYPE = "Video"

  /** 请求 Emby 时统一附加的扩展字段 */
  private const val ITEM_FIELDS =
    "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources," +
    "Tags,SortName,ProductionLocations,Path"

  // ─── 内部工具 ───

  private fun authedRequest(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    val sb = StringBuilder("${server.hostUrl}/emby$path")
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

  private fun authedRequestWithApiKey(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    // 带 api_key query 参数（用于 stream/images，便于直接拼 URL 给 PlayerActivity）
    val withKey = extraQuery.toMutableMap()
    if (server.apiToken.isNotEmpty()) withKey["api_key"] = server.apiToken
    return authedRequest(server, path, withKey)
  }

  private fun execString(request: Request): String {
    httpClient.newCall(request).execute().use { resp ->
      val body = resp.body?.string() ?: ""
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, body.ifEmpty { resp.message })
      }
      return body
    }
  }

  private inline fun <reified T> execJson(request: Request): T {
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

  private inline fun <reified T> postJson(
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

  private inline fun <reified T> getJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).get().build()
    return execJson(req)
  }

  private inline fun <reified T> deleteJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).delete().build()
    return execJson(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 1. 认证 / 服务器信息
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 用用户名密码登录。返回的 AccessToken 写回 [EmbyServer.apiToken]。
   *
   * Emby 要求 X-Emby-Authorization header，格式：
   *   MediaBrowser Client="mpvEx", Device="Android", DeviceId="<uuid>", Version="1.0"
   */
  fun authenticateByName(server: EmbyServer, username: String, password: String): EmbyAuthResult {
    val body = """{"Username":"$username","Pw":"$password"}"""
    val deviceId = androidDeviceId(server.host)
    val authHeader =
      """MediaBrowser Client="mpvEx", Device="Android", DeviceId="$deviceId", Version="1.0.0""""
    val req = Request.Builder()
      .url("${server.hostUrl}/emby/Users/AuthenticateByName")
      .header("X-Emby-Authorization", authHeader)
      .post(body.toRequestBody(jsonMedia))
      .build()
    return execJson(req)
  }

  /** 拉取服务器公开信息（无需登录）。 */
  fun getPublicSystemInfo(server: EmbyServer): EmbySystemInfo = getJson(server, "/System/Info/Public")

  // ════════════════════════════════════════════════════════════════════════
  // 2. 媒体库（VirtualFolders） / 媒体浏览
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 获取用户可见的媒体库（媒体库视图 / CollectionFolders）。
   *
   * 对应 SDK：`UserViewsServiceApi.getUsersByUseridViews` → `GET /Users/{UserId}/Views`
   * 返回电影、剧集、音乐等顶层库，而不是把所有根级 Item 平铺。
   */
  fun getVirtualFolders(server: EmbyServer): EmbyItemsResult =
    getJson(server, "/Users/${server.userId}/Views")

  /**
   * 继续观看。
   *
   * 只取电影与剧集（不含单集）——单集属于剧集内部层级，
   * 放在"继续观看"里会显得零散，也不方便从海报直接定位。
   * 音乐、图片同样排除。
   */
  fun getResumeItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
  ): EmbyItemsResult = getJson(
    server,
    "/Users/${server.userId}/Items/Resume",
    mapOf(
      "startIndex" to startIndex.toString(),
      "limit" to limit.toString(),
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
    ),
  )

  /**
   * 最新加入。
   *
   * 默认只取电影与剧集（不含单集），避免单集把整页刷满。
   */
  fun getLatestItems(
    server: EmbyServer,
    parentId: String? = null,
    limit: Int = 16,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
  ): List<EmbyItem> = getJson<List<EmbyItem>>(
    server,
    "/Users/${server.userId}/Items/Latest",
    mapOf(
      "limit" to limit.toString(),
      "ParentId" to parentId,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
    ),
  )

  /**
   * 通用分页查询。可用于：库内媒体、收藏夹、随机播放、按类型筛选等。
   *
   * @param parentId 父级 ID（库 ID 或剧集 ID 等）。null 表示查用户根。
   * @param sortBy 排序字段：DateCreated/DatePlayed/Name/ProductionYear/Random/...
   * @param sortOrder Ascending / Descending
   * @param filters 筛选：IsFavorite/IsPlayed/IsUnplayed/...
   * @param includeItemTypes 类型过滤：Movie/Series/Episode/MusicAlbum/...
   * @param startIndex 分页起始
   * @param limit 每页大小
   */
  fun getItems(
    server: EmbyServer,
    parentId: String? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    filters: List<String>? = null,
    includeItemTypes: List<String>? = null,
    genres: List<String>? = null,
    searchTerm: String? = null,
    mediaTypes: List<String>? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    /** true / false 分别限定只要收藏 / 只要未收藏；null 表示不限 */
    isFavorite: Boolean? = null,
    /**
     * 排除的条目类型。Emby 的 Recursive 查询默认会把 Folder 一并返回，
     * 「全部」这类视图要传入 Folder/CollectionFolder/UserView 把它们挡掉。
     */
    excludeItemTypes: List<String>? = null,
    /** 按人员筛选：传演员/导演的 PersonId，查 TA 参与过的条目 */
    personIds: List<String>? = null,
    /** 按发行年份筛选；多选时 Emby 取并集 */
    years: List<Int>? = null,
    /** 最低社区评分（0~10），例如传 7.0 表示只要 7 分以上的 */
    minCommunityRating: Float? = null,
    /** 按标签筛选；多选时 Emby 取并集，查询串用 | 分隔 */
    tags: List<String>? = null,
    /** 按官方分级筛选；参数名是复数 OfficialRatings，查询串用 | 分隔 */
    officialRatings: List<String>? = null,
    /**
     * 已看 / 未看：true 只要看过的，false 只要没看过的，null 不限。
     *
     * Emby 4.x 起这是独立的 IsPlayed 布尔参数，不是 Filters 里的枚举值，
     * 所以单独拼，不要塞进 Filters（塞进去服务端会忽略）。
     */
    isPlayed: Boolean? = null,
    /** true 只要高清 / false 只要标清（Emby 的 IsHD） */
    isHD: Boolean? = null,
    /** true 只要 3D / false 排除 3D（Emby 的 Is3D） */
    is3D: Boolean? = null,
    /** true 只要有字幕 / false 只要没字幕（Emby 的 HasSubtitles） */
    hasSubtitles: Boolean? = null,
    /** 工作室（出品方）Id 列表，多选取并集 */
    studioIds: List<String>? = null,
  ): EmbyItemsResult {
    val q = LinkedHashMap<String, String?>()
    parentId?.let { q["ParentId"] = it }
    sortBy?.let { q["SortBy"] = it }
    sortOrder?.let { q["SortOrder"] = it }
    filters?.takeIf { it.isNotEmpty() }?.let { q["Filters"] = it.joinToString("|") }
    isFavorite?.let { q["IsFavorite"] = it.toString() }
    includeItemTypes?.takeIf { it.isNotEmpty() }?.let { q["IncludeItemTypes"] = it.joinToString(",") }
    excludeItemTypes?.takeIf { it.isNotEmpty() }?.let { q["ExcludeItemTypes"] = it.joinToString(",") }
    genres?.takeIf { it.isNotEmpty() }?.let { q["Genres"] = it.joinToString("|") }
    tags?.takeIf { it.isNotEmpty() }?.let { q["Tags"] = it.joinToString("|") }
    officialRatings?.takeIf { it.isNotEmpty() }?.let { q["OfficialRatings"] = it.joinToString("|") }
    // 已看状态与视频规格都是独立布尔参数，服务端只认 true / false，不传才是「不限」
    isPlayed?.let { q["IsPlayed"] = it.toString() }
    isHD?.let { q["IsHD"] = it.toString() }
    is3D?.let { q["Is3D"] = it.toString() }
    hasSubtitles?.let { q["HasSubtitles"] = it.toString() }
    studioIds?.takeIf { it.isNotEmpty() }?.let { q["StudioIds"] = it.joinToString(",") }
    personIds?.takeIf { it.isNotEmpty() }?.let { q["PersonIds"] = it.joinToString(",") }
    years?.takeIf { it.isNotEmpty() }?.let { q["Years"] = it.joinToString(",") }
    // Genres / Tags / OfficialRatings 都是「|」分隔，只有 Years 是「,」分隔
    minCommunityRating?.let { q["MinCommunityRating"] = it.toString() }
    searchTerm?.takeIf { it.isNotBlank() }?.let { q["SearchTerm"] = it }
    mediaTypes?.takeIf { it.isNotEmpty() }?.let { q["MediaTypes"] = it.joinToString(",") }
    q["StartIndex"] = startIndex.toString()
    q["Limit"] = limit.toString()
    q["Recursive"] = recursive.toString()
    q["Fields"] = ITEM_FIELDS
    q["EnableImages"] = "true"
    q["EnableUserData"] = "true"
    q["ImageTypeLimit"] = "1"
    return getJson(server, "/Users/${server.userId}/Items", q)
  }

  /**
   * 取「按名字聚合」端点 /Genres、/Tags、/OfficialRatings、/Years 返回的名称列表。
   * 这四个端点返回体结构一致（Items[].Name），可以共用一个取法。
   */
  private fun nameList(server: EmbyServer, path: String, parentId: String?): List<String> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { it.Name }
        .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

  /** 某媒体库下出现过的全部类型名 */
  fun getGenres(server: EmbyServer, parentId: String? = null): List<String> =
    nameList(server, "/Genres", parentId)

  /**
   * 取「Id + 名字」的聚合端点（/Persons、/Studios）。
   *
   * 和 [nameList] 的区别是这里要留下 Id —— 演员同名很常见，只有 Id 能唯一确定一个人。
   */
  private fun idNameList(
    server: EmbyServer,
    path: String,
    parentId: String?,
    extra: Map<String, String> = emptyMap(),
  ): List<EmbyIdName> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      extra.forEach { (k, v) -> q[k] = v }
      getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          EmbyIdName(id, name)
        }
    }.getOrDefault(emptyList())

  /**
   * 某媒体库里出现过的演员 / 导演 / 编剧。
   *
   * PersonTypes 用 | 分隔，和 Genres 一样；不传会把幕后工种全拉回来，列表会很长。
   */
  fun getPersons(
    server: EmbyServer,
    parentId: String? = null,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
  ): List<EmbyIdName> = idNameList(
    server,
    "/Persons",
    parentId,
    mapOf("PersonTypes" to personTypes.joinToString("|")),
  )

  /** 某媒体库里出现过的工作室（出品方） */
  fun getStudios(server: EmbyServer, parentId: String? = null): List<EmbyIdName> =
    idNameList(server, "/Studios", parentId)

  /**
   * 一次拿齐筛选面板需要的全部可选项。
   *
   * 走按名字聚合的端点而不是从条目列表里现去重：后者要翻完整个库才准，
   * 而且分页时漏掉的项根本看不见。任一端点失败就退化成空列表，
   * 面板里对应的分组会自动隐藏（年份另有兜底，见 [fallbackYears]）。
   */
  fun getFilterOptions(server: EmbyServer, parentId: String? = null): EmbyFilterOptions =
    EmbyFilterOptions(
      genres = getGenres(server, parentId),
      tags = nameList(server, "/Tags", parentId),
      years = nameList(server, "/Years", parentId)
        .mapNotNull { it.toIntOrNull() }
        .sortedDescending()
        .takeIf { it.isNotEmpty() } ?: fallbackYears(),
      officialRatings = nameList(server, "/OfficialRatings", parentId),
      persons = getPersons(server, parentId),
      studios = getStudios(server, parentId),
    )

  /** /Years 端点不可用时兜底：给一份从今年往前推的年份表，保证年份筛选不会空着 */
  private fun fallbackYears(): List<Int> {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return (now downTo 1950).toList()
  }

  /**
   * 媒体详情。
   *
   * 显式带 Fields：不带的话 Emby 默认不返回 People / Studios / Tags /
   * ProductionLocations 等字段，「编辑元数据」弹窗里就是一片空白。
   */
  fun getItem(server: EmbyServer, itemId: String): EmbyItem =
    getJson(
      server,
      "/Users/${server.userId}/Items/$itemId",
      mapOf(
        "fields" to listOf(
          "Overview",
          "OriginalTitle",
          "SortName",
          "Genres",
          "Tags",
          "Studios",
          "ProductionLocations",
          "People",
          "OfficialRating",
          "CommunityRating",
          "PremiereDate",
          "ProductionYear",
          "MediaSources",
          "MediaStreams",
        ).joinToString(","),
      ),
    )

  /**
   * 关键词搜索。
   *
   * @param includeItemTypes 限定类型，传 null 表示不限（Emby 默认只搜部分类型，
   *                         传入 Movie/Series/Episode 等可精确控制）
   */
  fun searchItems(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>? = null,
    limit: Int = 60,
    /**
     * 排序方式。默认 SortName（按名称），与改造前行为一致。
     *
     * Emby 的 /Items 接受 SearchTerm 与 SortBy 组合，所以搜索结果同样能排序 ——
     * 媒体库里搜索后再切排序要真的生效，就必须把用户选的排序传进来。
     */
    sortBy: String? = "SortName",
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = includeItemTypes,
    searchTerm = term,
    startIndex = 0,
    limit = limit,
  )

  /** 某剧集下的季列表 */
  fun getSeasons(
    server: EmbyServer,
    seriesId: String,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Seasons",
    mapOf(
      "UserId" to server.userId,
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines",
      "EnableUserData" to "true",
    ),
  )

  /** 某季（或整部剧集）下的剧集列表 */
  fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Episodes",
    mapOf(
      "UserId" to server.userId,
      "SeasonId" to seasonId,
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources",
      "EnableUserData" to "true",
    ),
  )

  /** 播放历史：按最近播放时间倒序（只统计视频类媒体） */
  fun getPlayedHistory(
    server: EmbyServer,
    limit: Int = 60,
    startIndex: Int = 0,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "DatePlayed",
    sortOrder = "Descending",
    filters = listOf("IsPlayed"),
    mediaTypes = mediaTypes,
    startIndex = startIndex,
    limit = limit,
  )

  /**
   * 收藏夹列表（用户标过 IsFavorite 的项）。
   *
   * 默认只返回视频类媒体，音乐与图片不在播放器收藏页展示。
   */
  fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
    sortOrder = "Ascending",
    filters = listOf("IsFavorite"),
    mediaTypes = mediaTypes,
    startIndex = startIndex,
    limit = limit,
  )

  /**
   * 随机播放：拿 [limit] 个随机项（按 Random 排序）。
   * 用户可在播放页点"随机播放"调用，把返回的 IDs 依次送给 PlayerActivity。
   */
  fun getRandomItems(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = "Random",
    sortOrder = "Ascending",
    includeItemTypes = includeItemTypes,
    startIndex = 0,
    limit = limit,
    isFavorite = isFavorite,
  )

  // ════════════════════════════════════════════════════════════════════════
  // 3. 收藏 / 删除
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 加入 / 取消收藏。返回服务器回传的 UserData（含 IsFavorite），用来确认真实状态。
   */
  fun setFavorite(server: EmbyServer, itemId: String, favorite: Boolean): EmbyUserData? =
    toggleUserState(server, "/Users/${server.userId}/FavoriteItems/$itemId", favorite)

  /** 标记已看 / 未看。返回服务器回传的 UserData（含 Played）。 */
  fun setPlayed(server: EmbyServer, itemId: String, played: Boolean): EmbyUserData? =
    toggleUserState(server, "/Users/${server.userId}/PlayedItems/$itemId", played)

  /**
   * 收藏 / 已播放这类「开关型」接口的统一发送。
   *
   * 请求形态照 Emby Web 端实测的两种（**不是**官方 OpenAPI 写的那种）：
   *   打开：`POST /Users/{uid}/FavoriteItems/{id}`
   *   关闭：`POST /Users/{uid}/FavoriteItems/{id}/Delete` ← 是 POST 到 `/Delete`，不是 DELETE 方法
   * 实测 SmartStrm 这类服务端只认后一种写法，用 DELETE 方法会被拒。
   *
   * **响应体本身就是最新的 UserData**（`{"IsFavorite":true,"Played":false,...}`），
   * 直接解析它就能确认状态 —— 不用再回查一次条目：那台服务器的详情响应
   * `Content-Length` 比实际 body 长，OkHttp 读不满会抛 unexpected end of stream，
   * 回查失败就会把「其实已经成功了」误报成失败。
   */
  private fun toggleUserState(server: EmbyServer, basePath: String, enable: Boolean): EmbyUserData? {
    val path = if (enable) basePath else "$basePath/Delete"
    // 1) 主：POST + 无 body（Web 端就是这个形态，content-length=0）
    val first = runCatching { execString(authedRequest(server, path).post(EMPTY_BODY).build()) }
    val body = first.getOrNull()
      // 2) 兜底：空 body 会被部分服务端（ServiceStack 系）判 400，此时改发空对象
      ?: runCatching {
        execString(authedRequest(server, path).post(EMPTY_JSON_BODY.toRequestBody(jsonMedia)).build())
      }.getOrNull()
      // 3) 关闭再兜底：老版本只认 DELETE 方法
      ?: if (!enable) {
        runCatching { execString(authedRequest(server, basePath).delete().build()) }.getOrNull()
      } else {
        null
      }
    // 三种形态都失败：把第一次的真实原因抛出去（它最接近「实际用的是哪种形态」的问题）
    if (body == null) throw first.exceptionOrNull() ?: EmbyApiException(0, "请求失败")
    return parseUserData(body)
  }

  /** 解析响应体里的 UserData；解析不了（空 body / 不是 JSON）返回 null，由调用方另想办法 */
  private fun parseUserData(body: String): EmbyUserData? =
    body.takeIf { it.isNotBlank() }
      ?.let { runCatching { json.decodeFromString<EmbyUserData>(it) }.getOrNull() }

  /** 删除媒体（需要相应权限） */
  fun deleteItem(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Items/$itemId").delete().build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.5 元数据操作（编辑 / 刮削 / 刷新）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 刷新 / 扫描：让服务器重新读取本地文件、或从网络刮削。
   *
   * @param mode MetadataRefreshMode：Default（只补新增 / 缺失）/ FullRefresh（全量重刮）/
   *             LatestRefresh（仅补缺失字段）
   * @param replaceAllMetadata ReplaceAllMetadata：连已存在的片名、简介一起重刮覆盖
   * @param replaceAllImages ReplaceAllImages：重新下载并替换已有封面、背景图
   *                         （勾选时图片模式一并提到 FullRefresh，否则服务器可能仍沿用缓存图）
   */
  fun refreshItem(
    server: EmbyServer,
    itemId: String,
    mode: String = "Default",
    replaceAllMetadata: Boolean = false,
    replaceAllImages: Boolean = false,
  ) {
    val req = authedRequest(
      server,
      "/Items/$itemId/Refresh",
      mapOf(
        "MetadataRefreshMode" to mode,
        "Recursive" to "true",
        "ImageRefreshMode" to if (replaceAllImages) "FullRefresh" else "Default",
        "ReplaceAllMetadata" to replaceAllMetadata.toString(),
        "ReplaceAllImages" to replaceAllImages.toString(),
      ),
    ).post("".toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.6 图片管理（编辑图片：列出现有图 / 删除 / 本地上传 / 从图源搜图）
  // ════════════════════════════════════════════════════════════════════════

  /** 条目当前挂着的图片清单（GET /Items/{Id}/Images） */
  fun getItemImages(server: EmbyServer, itemId: String): List<EmbyImageInfo> =
    execJson(authedRequest(server, "/Items/$itemId/Images").build())

  /** 删除一张图片（封面 / 徽标 / 艺术图 …） */
  fun deleteItemImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    index: Int = 0,
  ) {
    val base = "/Items/$itemId/Images/$imageType/$index"
    runCatching {
      // Emby 4.9 起改成了 POST …/{Index}/Delete
      execString(
        authedRequest(server, "$base/Delete")
          .post("".toRequestBody(jsonMedia))
          .build(),
      )
    }.recoverCatching {
      // 老版本只认 DELETE /Items/{Id}/Images/{Type}/{Index}
      execString(authedRequest(server, base).delete().build())
    }.getOrThrow()
  }

  /**
   * 上传（更换）一张图片：POST /Items/{Id}/Images/{Type}。
   *
   * 这条接口的 body 格式各版本不一致：新版本收**原始二进制**（Content-Type 是图片 mime），
   * 老版本只认 **base64 文本**（同样配图片 mime）。先按二进制发，被拒了再退回 base64 ——
   * 两种都试过仍失败才抛，避免「换图一直失败但不知道为什么」。
   */
  fun uploadItemImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    bytes: ByteArray,
    mime: String,
  ) {
    val mediaType = mime.toMediaType()
    val rawError = runCatching {
      execString(
        authedRequest(server, "/Items/$itemId/Images/$imageType")
          .post(bytes.toRequestBody(mediaType))
          .build(),
      )
    }.exceptionOrNull()
    if (rawError == null) return

    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
    runCatching {
      execString(
        authedRequest(server, "/Items/$itemId/Images/$imageType")
          .post(base64.toByteArray().toRequestBody(mediaType))
          .build(),
      )
    }.onFailure {
      throw rawError
    }
  }

  /** 可用的远程图源（GET /Items/{Id}/RemoteImages/Providers） */
  fun getRemoteImageProviders(server: EmbyServer, itemId: String): List<EmbyImageProviderInfo> =
    execJson(authedRequest(server, "/Items/$itemId/RemoteImages/Providers").build())

  /** 从远程图源搜某类图片（GET /Items/{Id}/RemoteImages） */
  fun searchRemoteImages(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    providerName: String? = null,
    includeAllLanguages: Boolean = true,
  ): List<EmbyRemoteImageInfo> =
    execJson<EmbyRemoteImageResult>(
      authedRequest(
        server,
        "/Items/$itemId/RemoteImages",
        mapOf(
          "Type" to imageType,
          "ProviderName" to providerName,
          "IncludeAllLanguages" to includeAllLanguages.toString(),
          // 跟 Emby Web 端一致：一次取 50 张足够挑，不设的话有的图源会全量返回几百张
          "Limit" to "50",
        ),
      ).build(),
    ).Images

  /** 把远程搜到的那张图下载并挂到条目上（POST /Items/{Id}/RemoteImages/Download） */
  fun downloadRemoteImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    imageUrl: String,
    providerName: String? = null,
  ) {
    val req = authedRequest(
      server,
      "/Items/$itemId/RemoteImages/Download",
      mapOf(
        // 4.9 起参数名叫 Type，老版本叫 ImageType —— 两个都带上，各自忽略不认识的那个
        "Type" to imageType,
        "ImageType" to imageType,
        "ImageUrl" to imageUrl,
        // 必须是「这张图来自哪个图源」（MetaTube / FanArt ...）。写死 Manual 会让服务器
        // 拿错的图源去取图，MetaTube 这类外链图源基本必失败。留空时服务器自己判断。
        "ProviderName" to providerName,
      ),
    ).post("".toRequestBody(jsonMedia)).build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.7 识别 / 刮削（Identify）
  // ════════════════════════════════════════════════════════════════════════

  /** 条目已绑定的外部 ID（GET /Items/{Id}/ExternalIdInfos），用于预填识别对话框 */
  fun getExternalIdInfos(server: EmbyServer, itemId: String): List<EmbyExternalIdInfo> =
    execJson(authedRequest(server, "/Items/$itemId/ExternalIdInfos").build())

  /**
   * 按条件远程检索元数据（POST /Items/RemoteSearch/{Type}）。
   *
   * [searchType] 是 **检索类型**（Movie / Series / BoxSet / Person …），
   * 跟条目的 Type 大多数时候同名，但剧集（Episode）要按 Series 去搜，不能直接拿 Type 填。
   *
   * 请求体必须是 `{"SearchInfo":{...},"ItemId":n,...}` 这层壳 ——
   * 官方 schema 里 body 类型是 `RemoteSearchQuery<MovieInfo>`，
   * 直接把 lookup 对象发过去的话服务器读到的 SearchInfo 是 null，返回永远是空数组。
   */
  fun remoteSearch(
    server: EmbyServer,
    searchType: String,
    lookup: EmbyItemLookupInfo,
    itemId: String? = null,
  ): List<EmbyRemoteSearchResult> {
    val path = "/Items/RemoteSearch/$searchType"
    // 主请求：ItemId 按字符串发（实测能拿到候选的形式）
    val body = json.encodeToString(EmbyRemoteSearchQuery(SearchInfo = lookup, ItemId = itemId))
    val first = runCatching { postJson<List<EmbyRemoteSearchResult>>(server, path, body) }
    if (first.isSuccess) return first.getOrThrow()

    // 兜底：少数版本只认数字 ItemId（严格按 OpenAPI 生成的那批），换形式再试一次。
    // 两次都失败时抛第一次的错误，它更贴近「实际用的那种形式」的问题。
    val numeric = itemId?.toLongOrNull() ?: throw first.exceptionOrNull()!!
    runCatching {
      postJson<List<EmbyRemoteSearchResult>>(
        server,
        path,
        json.encodeToString(EmbyRemoteSearchQueryNumeric(SearchInfo = lookup, ItemId = numeric)),
      )
    }.getOrElse { throw first.exceptionOrNull()!! }
    // 上一行要么返回、要么抛，这里到不了；写出来是为了让编译器确认返回类型非空
    @Suppress("UNREACHABLE_CODE")
    return emptyList()
  }

  /** 应用识别结果并刷新元数据（POST /Items/RemoteSearch/Apply/{Id}） */
  fun applyRemoteSearch(
    server: EmbyServer,
    itemId: String,
    result: EmbyRemoteSearchResult,
    replaceAllImages: Boolean = false,
  ) {
    val req = authedRequest(
      server,
      "/Items/RemoteSearch/Apply/$itemId",
      mapOf("ReplaceAllImages" to replaceAllImages.toString()),
    ).post(json.encodeToString(result).toRequestBody(jsonMedia)).build()
    execString(req)
  }

  /**
   * 更新（编辑）条目元数据：PUT 完整 item 对象。
   * Emby 以传入对象整体覆盖，因此调用方应传入「带修改后的完整 item」，避免丢字段。
   */
  fun updateItem(server: EmbyServer, item: EmbyItem) {
    val body = json.encodeToString(item)
    val req = authedRequest(server, "/Items/${item.Id}")
      .put(body.toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 4. 播放进度上报（Playstate）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 上报播放开始。调用时机：PlayerActivity 准备开始播放时。
   * 返回 PlaySessionId，后续 Progress/Stopped 需带它。
   */
  fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    playMethod: String = "DirectStream",
  ): String {
    val obj = buildMap {
      put("ItemId", itemId)
      put("PositionTicks", positionTicks)
      put("PlayMethod", playMethod)
      audioStreamIndex?.let { put("AudioStreamIndex", it) }
      subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
      put("CanSeek", true)
      put("IsMuted", false)
      put("IsPaused", false)
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing")
      .post(body.toRequestBody(jsonMedia))
      .build()
    val resp = execString(req)
    // Emby 返回 PlaySessionId 字段
    return try {
      json.parseToJsonElement(resp).jsonObject["PlaySessionId"]?.jsonPrimitive?.contentOrNull ?: ""
    } catch (_: Exception) { "" }
  }

  /** 上报播放进度（建议每 10 秒一次 + 暂停/恢复时） */
  fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
    isMuted: Boolean = false,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
      put("IsPaused", isPaused)
      put("IsMuted", isMuted)
      put("PlayMethod", "DirectStream")
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing/Progress")
      .post(body.toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  /** 上报播放停止（退出播放页时调用） */
  fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
  ) {
    val obj = buildMap {
      put("ItemId", itemId)
      put("PlaySessionId", playSessionId)
      put("PositionTicks", positionTicks)
    }
    val body = json.encodeToString(JsonObject.serializer(), jsonObjectOf(obj))
    val req = authedRequest(server, "/Sessions/Playing/Stopped")
      .post(body.toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 5. 图片 / 视频流 URL 构造（不调用，只生成 URL 给 Coil/PlayerActivity）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 构造图片 URL。Emby 图片 API：
   *   GET /Items/{itemId}/Images/{imageType}?tag={tag}&maxWidth={w}&maxHeight={h}&quality=90
   *
   * imageType: Primary / Backdrop / Thumb / Banner / Logo / Art / Disc
   */
  fun imageUrl(
    server: EmbyServer,
    itemId: String,
    imageType: String = "Primary",
    tag: String? = null,
    maxWidth: Int = 480,
    maxHeight: Int? = null,
  ): String {
    val q = LinkedHashMap<String, String>()
    if (tag != null) q["tag"] = tag
    q["maxWidth"] = maxWidth.toString()
    maxHeight?.let { q["maxHeight"] = it.toString() }
    q["quality"] = "90"
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "${server.hostUrl}/emby/Items/$itemId/Images/$imageType?$qs"
  }

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
    return "${server.hostUrl}/emby/Videos/$itemId/stream?$qs"
  }

  /**
   * 构造「下载原始文件」URL。
   *
   * Emby 的 `/Items/{id}/Download` 会把服务器上的原文件以附件形式吐出（不转码），
   * 是官方客户端「下载」用的接口；它支持 Range 请求，所以能断点续传。
   */
  fun itemDownloadUrl(server: EmbyServer, itemId: String): String {
    val q = if (server.apiToken.isNotEmpty()) "?api_key=${server.apiToken}" else ""
    return "${server.hostUrl}/emby/Items/$itemId/Download$q"
  }

  /**
   * 字幕下载 URL（用于把外挂字幕交给 mpv 用 sub-file 加载）。
   * Emby 字幕 API：GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.srt
   */
  fun subtitleUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): String {
    val q = if (server.apiToken.isNotEmpty()) "?api_key=${server.apiToken}" else ""
    return "${server.hostUrl}/emby/Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format$q"
  }

  // ════════════════════════════════════════════════════════════════════════
  // 6. 辅助：基于 Android 设备 ID（用于 X-Emby-Authorization）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 简单生成稳定的设备 ID（基于主机 + 安卓 ANDROID_ID 哈希）。
   * Emby 用 DeviceId 标识设备，同一账号同一设备会复用会话。
   */
  private fun androidDeviceId(seed: String): String {
    // 不依赖 Context（避免本类需要 Application 注入），用稳定字符串哈希
    val raw = "mpvex-android-$seed"
    val md = java.security.MessageDigest.getInstance("MD5")
    val bytes = md.digest(raw.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(32)
  }
}

/**
 * Emby API 调用失败时抛出，包含 HTTP 状态码与原始 body。
 */
class EmbyApiException(val code: Int, val body: String) : Exception("Emby API $code: ${body.take(200)}")

/** kotlinx.serialization 的 JsonObject 构造辅助（避免每次写 JsonObject(mapOf(...)) 套娃） */
private fun jsonObjectOf(entries: Map<String, Any?>): JsonObject {
  val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
  entries.forEach { (k, v) ->
    val e = when (v) {
      null -> kotlinx.serialization.json.JsonNull
      is String -> kotlinx.serialization.json.JsonPrimitive(v)
      is Number -> kotlinx.serialization.json.JsonPrimitive(v)
      is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
      else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }
    map[k] = e
  }
  return JsonObject(map)
}
