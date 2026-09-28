package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
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
  val Path: String? = null,
  val Taglines: List<String> = emptyList(),
  val ProviderIds: Map<String, String> = emptyMap(),
)

@Serializable
data class EmbyUserData(
  val Played: Boolean = false,
  val PlayCount: Int = 0,
  val IsFavorite: Boolean = false,
  val PlaybackPositionTicks: Long = 0,
  val PlayedPercentage: Double? = null,
  val UnplayedItemCount: Int? = null,
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

  /** 视频类媒体（用于把音乐、图片从影视列表中过滤掉） */
  private const val VIDEO_MEDIA_TYPE = "Video"

  /** 请求 Emby 时统一附加的扩展字段 */
  private const val ITEM_FIELDS =
    "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources"

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

  /** 媒体详情 */
  fun getItem(server: EmbyServer, itemId: String): EmbyItem =
    getJson(server, "/Users/${server.userId}/Items/$itemId")

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
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
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

  /** 加入收藏夹 */
  fun favoriteItem(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Users/${server.userId}/FavoriteItems/$itemId")
      .post("".toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  /** 取消收藏 */
  fun unfavoriteItem(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Users/${server.userId}/FavoriteItems/$itemId")
      .delete()
      .build()
    execString(req)
  }

  /** 删除媒体（需要相应权限） */
  fun deleteItem(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Items/$itemId").delete().build()
    execString(req)
  }

  /** 标记已看 */
  fun markPlayed(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Users/${server.userId}/PlayedItems/$itemId")
      .post("".toRequestBody(jsonMedia))
      .build()
    execString(req)
  }

  /** 标记未看 */
  fun markUnplayed(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Users/${server.userId}/PlayedItems/$itemId")
      .delete()
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
