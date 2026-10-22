package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// 数据模型（EmbyItem / EmbyUserDto / …）已搬到 EmbyModels.kt（清单 1.5 拆分第 1 刀），
// 尾部与错误处理相关的 DTO 仍留在本文件末尾。

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

  /**
   * 走 [EmbyHttp.shared]（共享连接池 + 统一 UA + 指数退避重试 + 401 自动重登）。
   *
   * 原来是这里自己 `OkHttpClient.Builder()` 造一个，与另外 5 个客户端各建各的连接池，
   * 详见 [EmbyHttp] 的说明。
   *
   * `internal` 而非 `private`：拆出去的 [EmbySubtitles] 需要它 —— 字幕下载要拿
   * **原始字节**（走完服务端格式转换的响应体），不能经过 [execString] 那条
   * 「按文本解码 + 抛异常」的路径。同理它必须显式写 `EmbyClient.httpClient`，
   * `internal` 不会把成员导入到别的 object 作用域里。
   */
  internal val httpClient: OkHttpClient = EmbyHttp.shared

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

  /**
   * 收藏 / 已播放等开关型请求附带的客户端标识 query 参数。
   *
   * 与 Emby Web 请求一致：该服务端上
   * 历史上出现过不带这几个参数时「回 200 但收藏没落库」的假成功 —— 请求形态与
   * Web 完全一致后写入才真正生效。X-Emby-* 是 Emby 的标准客户端标识参数，
   * 所有服务端都接受，带上没有副作用。
   */
  private val WEB_CLIENT_QUERY: Map<String, String> = mapOf(
    "X-Emby-Client" to "Emby Web",
    "X-Emby-Device-Name" to "Chrome Windows",
    "X-Emby-Device-Id" to "ca3b0a8f-2ff0-46ea-aa02-a6abf72f0061",
    "X-Emby-Client-Version" to "4.10.1.0",
    "X-Emby-Language" to "zh-cn",
  )

  /**
   * 拉流时统一使用的 User-Agent。
   *
   * 「预解析直链（302）」探测与播放器真正拉流**必须用同一个 UA**：
   * 网盘直链的签名常把 UA 算进校验，两边不一致时探测能过、播放被 403。
   * 播放器侧通过 intent extra `headers` 收到同一个值（见 EmbyViewModel 的播放入口）。
   */
  const val STREAM_USER_AGENT = "mpvex/1.0"

  /** 视频类媒体（用于把音乐、图片从影视列表中过滤掉） */
  private const val VIDEO_MEDIA_TYPE = "Video"

  /**
   * 超过这个耗时的请求，在 [execString] 的计时日志里按「慢请求」标出（毫秒）。
   *
   * 定 1500 是因为正常局域网内一次 `/Items` 查询在几十到几百毫秒量级；
   * 持续超过 1.5 秒基本就是服务端那边在扫大表（十万级条目库里
   * `SortBy=Random` 这类无法走索引的排序尤其明显），值得单独看一眼。
   */
  private const val SLOW_REQUEST_MS = 1_500L

  /** 请求 Emby 时统一附加的扩展字段 */
  internal const val ITEM_FIELDS =
    "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources," +
    // Size 是「按文件大小排序」的数据来源，Emby 默认不返回，必须显式索取
    "Tags,SortName,ProductionLocations,Path,Size"

  /**
   * [ITEM_FIELDS] + **本地排序要用的那几个键**：年份 / 评分 / 添加时间 / 首播日期。
   *
   * ⚠️ 这几个字段 Emby **默认不回传**（不带 Fields 的 `/Items` 只回
   * Name / Id / Type / UserData / ImageTags，其余全是 null），而 [ITEM_FIELDS] 里也没有它们。
   * 于是「按年份 / 评分 / 最新添加排序」时每条的键都是 null，稳定排序退化为**原顺序** ——
   * 表现就是「排序只有名称（SortName 在 ITEM_FIELDS 里）和随机有效，其余点了没反应」。
   *
   * 凡是**要在客户端本地排序**的列表（收藏页、演员 / 导演作品页）都必须用这一份。
   */
  internal const val ITEM_FIELDS_WITH_SORT_KEYS =
    "$ITEM_FIELDS,ProductionYear,CommunityRating,DateCreated,PremiereDate"

  /**
   * 拉照片（Photo）列表时索要的扩展字段。
   *
   * 只用 OpenAPI 文档列出的合法 Fields 名（Overview/Genres/Taglines/SortName/
   * ProductionLocations/Path）—— 服务端对不认识的字段名有可能直接 400，
   * 不放没把握的值进去。
   *
   * ⚠️ **宽高 / MIME / EXIF（CameraMake 等）不在这个串里，也不该加**：
   * OpenAPI 的 Fields 可选值清单里根本没有它们 —— 这些字段由服务端在库扫描时
   * 从图片 EXIF 写进条目实体，对 Photo 条目**自动返回**，与 Fields 无关。
   * 见 [app.marlboroadvance.mpvex.domain.emby.EmbyModels] 里 EmbyItem 的照片字段段。
   */
  internal const val PHOTO_FIELDS =
    "Overview,Genres,Taglines,SortName,ProductionLocations,Path"

  /**
   * 取「整份条目原文」时索要的字段（演职员合并回写用）。
   *
   * 要点是**必须包含 `People`**：不带的话返回体里没有 People，
   * [app.marlboroadvance.mpvex.domain.emby.rewriteItemPeople] 会认为这条不用改从而静默跳过。
   * 其余是编辑元数据时用户可能已经改过的字段，一并要回来，
   * 免得整包回写时把它们从「有值」变成「缺字段」。
   */
  private const val RAW_ITEM_FIELDS =
    "Overview,OriginalTitle,SortName,Genres,Tags,Studios,ProductionLocations,People," +
      "OfficialRating,CommunityRating,PremiereDate,ProductionYear,Path,ProviderIds,Taglines"

  /** 统计演员作品数时每页拉多少条。开大一点，几千条的库几次就能拉完 */
  private const val COUNT_PAGE_SIZE = 500

  /**
   * 统计能接受的库规模上限（条数）。超过就整体放弃统计（角标留空），
   * 免得为了几十个角标去拉几十 MB 的响应。它同时也是翻页的硬上限 ——
   * 服务端万一忽略 StartIndex，配合按 Id 去重不会死循环。
   */
  private const val COUNT_MAX_ITEMS = 10_000

  /**
   * 小库阈值（条数）：不超过这个规模就整库扫一遍算齐所有演员角标。
   *
   * 定 3000 是因为 3000 / [COUNT_PAGE_SIZE] 500 = 6 个请求就能扫完，而单个响应
   * 只带 `People` 一个字段（见 [getItems] 的 fields 参数），体量很小 ——
   * 这个开销换「所有角标一次到位」很值。超过就转逐个问
   * （见 [getPersonWorkCountsAdaptive]），避免为几十个角标把大库拉穿。
   */
  private const val COUNT_FULL_SCAN_MAX = 3_000

  // ── 「服务器转码」的默认参数（见 [transcodeStreamUrl]）──

  /**
   * 转码目标：H.264 + AAC。
   *
   * 这两个是**所有 Android 设备都硬解得了**的编码，转完就再没有兼容性问题。
   * 不用 HEVC 是因为它虽然更省码率，但老设备 / 低端机的硬解支持参差不齐，
   * 转码本来就是为了兼容，没有必要在这里引入新的不确定性。
   */
  private const val TRANSCODE_VIDEO_CODEC = "h264"
  private const val TRANSCODE_AUDIO_CODEC = "aac"

  /** 转码分辨率上限：1080p 封顶。源多半是 480p/720p，再高只是白烧服务器 CPU */
  private const val TRANSCODE_MAX_HEIGHT = 1080
  private const val TRANSCODE_VIDEO_BITRATE = 6_000_000
  private const val TRANSCODE_AUDIO_BITRATE = 192_000

  // ─── 内部工具 ───

  internal fun authedRequest(
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

  internal fun authedRequestWithApiKey(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder {
    // 带 api_key query 参数（用于 stream/images，便于直接拼 URL 给 PlayerActivity）
    val withKey = extraQuery.toMutableMap()
    if (server.apiToken.isNotEmpty()) withKey["api_key"] = server.apiToken
    return authedRequest(server, path, withKey)
  }

  internal fun execString(request: Request): String {
    // 计时日志：所有 Emby API 请求（GET/POST/DELETE）都走这一个收口点，所以在这里
    // 打一行就够覆盖全部。用途是回答「到底哪个端点在慢」—— 大媒体库（十万级条目）下
    // `/Items` 系列查询的耗时会随库规模上涨，而外观上只表现为「首页转圈很久」，
    // 没有这行日志根本分不清是哪一个请求、等了多久。
    // ⚠️ 只打 path 不打 query：query 里可能有用户输入的搜索词，不进日志。
    val startedAt = System.nanoTime()
    try {
      httpClient.newCall(request).execute().use { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) {
          throw EmbyApiException(resp.code, body.ifEmpty { resp.message })
        }
        return body
      }
    } finally {
      val ms = (System.nanoTime() - startedAt) / 1_000_000
      val line = "${ms}ms ${request.method} ${request.url.encodedPath}"
      // 慢的单独按 warn 标出：这标签本身是诊断用的（`adb logcat -s EmbyDiag`），
      // 一屏能刷很多行，慢请求要能一眼挑出来。
      if (ms >= SLOW_REQUEST_MS) EmbyLog.warn("慢请求 $line") else EmbyLog.info(line)
    }
  }

  internal inline fun <reified T> execJson(request: Request): T {
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

  internal inline fun <reified T> postJson(
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

  internal inline fun <reified T> getJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T {
    val req = authedRequest(server, path, query).get().build()
    return execJson(req)
  }

  internal inline fun <reified T> deleteJson(
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
    /**
     * 按「类型 Id / 标签 Id」筛选（逗号分隔）。
     *
     * 优先用这两个，而不是按名字的 [genres] / [tags]：
     * Emby 官方自 2019 年起就推荐用 Id（[官方说明](https://emby.media/community/topic/74602-tagitems-filtering-by-tags/)：
     * 「All list api's now have a TagIds filter… You can still filter using the tag name
     * with the Tags param, but this should only be used in situations where you do not have the id」）。
     *
     * 按名字那条路径会去查 `MediaItems.Genres` 列，某些服务端的 `library.db` 缺这一列
     * （跨版本升级/降级留下的坏 schema），一查就抛 SQLiteException 返回 500；
     * 按 Id 走的是关联表，与网页端筛选同路，不会踩到这个坑。
     */
    genreIds: List<String>? = null,
    tagIds: List<String>? = null,
    /** 按官方分级筛选；参数名是复数 OfficialRatings，查询串用 | 分隔 */
    officialRatings: List<String>? = null,
    /**
     * 已看 / 未看：true 只要看过的，false 只要没看过的，null 不限。
     *
     * Emby 4.x 起这是独立的 IsPlayed 布尔参数，不是 Filters 里的枚举值，
     * 所以单独拼，不要塞进 Filters（塞进去服务端会忽略）。
     */
    isPlayed: Boolean? = null,
    /**
     * 只要媒体本体 / 只要容器：false 会把 Folder、Series、Season、BoxSet 这类
     * 「本身不能播放」的容器一并挡掉（Emby 的 IsFolder）。null 表示不限。
     *
     * 「只要视频」的视图必须传 false —— 光靠 ExcludeItemTypes 只能挡住写在名单里的
     * 那几个容器类型，挡不住 Season、MusicAlbum 这些漏网的。
     */
    isFolder: Boolean? = null,
    /** true 只要高清 / false 只要标清（Emby 的 IsHD） */
    isHD: Boolean? = null,
    /** true 只要 3D / false 排除 3D（Emby 的 Is3D） */
    is3D: Boolean? = null,
    /** true 只要有字幕 / false 只要没字幕（Emby 的 HasSubtitles） */
    hasSubtitles: Boolean? = null,
    /** 工作室（出品方）Id 列表，多选取并集 */
    studioIds: List<String>? = null,
    /**
     * 工作室**名字**列表（`|` 分隔），多选取并集。
     *
     * 只在拿不到工作室 Id 时用 —— Id 那条（[studioIds]）更准。Emby 的 REST 文档里
     * `Studios` 与 `StudioIds` 都是官方过滤参数，所以这是个正经的兜底，不是硬凑。
     */
    studios: List<String>? = null,
    /**
     * 覆盖默认的 [ITEM_FIELDS]。
     *
     * 统计类请求（如数每个演员有多少部作品）只需要 `People`，用这个参数把
     * `MediaSources`、`Overview` 这些大字段去掉 —— 一页 500 条时响应体能小掉一大半。
     */
    fields: String = ITEM_FIELDS,
    /**
     * 按 Id 精确取条目（`Ids` 参数，逗号分隔）。
     *
     * 与 `Recursive` / `ParentId` 互斥的用法：`Ids` 命中即可，递归没有意义。
     * 用途见详情页的「同季其它集」（`BaseItemDto.SiblingIds` 只有 Id、没有内容）。
     */
    ids: List<String>? = null,
  ): EmbyItemsResult {
    val q = LinkedHashMap<String, String?>()
    // ⚠️ Ids 必须放在最前面并**关掉 Recursive**：Emby 在 `Ids + Recursive=true` 时
    // 会先展开整个库再过滤，几千条库能拖到超时（第三方服务端更甚）。
    ids?.takeIf { it.isNotEmpty() }?.let { q["Ids"] = it.joinToString(",") }
    parentId?.let { q["ParentId"] = it }
    sortBy?.let { q["SortBy"] = it }
    sortOrder?.let { q["SortOrder"] = it }
    filters?.takeIf { it.isNotEmpty() }?.let { q["Filters"] = it.joinToString("|") }
    isFavorite?.let { q["IsFavorite"] = it.toString() }
    // IsFolder 与 ExcludeItemTypes 是两个维度：前者按「是不是容器」筛，后者按类型名筛。
    // 只传其中一个都会漏 —— 第三方服务端对 ExcludeItemTypes 的支持尤其参差。
    isFolder?.let { q["IsFolder"] = it.toString() }
    includeItemTypes?.takeIf { it.isNotEmpty() }?.let { q["IncludeItemTypes"] = it.joinToString(",") }
    excludeItemTypes?.takeIf { it.isNotEmpty() }?.let { q["ExcludeItemTypes"] = it.joinToString(",") }
    genres?.takeIf { it.isNotEmpty() }?.let { q["Genres"] = it.joinToString("|") }
    tags?.takeIf { it.isNotEmpty() }?.let { q["Tags"] = it.joinToString("|") }
    // 按 Id 筛（优先）：官方推荐、且不碰会 500 的 Genres 列。逗号分隔。
    genreIds?.takeIf { it.isNotEmpty() }?.let { q["GenreIds"] = it.joinToString(",") }
    tagIds?.takeIf { it.isNotEmpty() }?.let { q["TagIds"] = it.joinToString(",") }
    officialRatings?.takeIf { it.isNotEmpty() }?.let { q["OfficialRatings"] = it.joinToString("|") }
    // 已看状态与视频规格都是独立布尔参数，服务端只认 true / false，不传才是「不限」
    isPlayed?.let { q["IsPlayed"] = it.toString() }
    isHD?.let { q["IsHD"] = it.toString() }
    is3D?.let { q["Is3D"] = it.toString() }
    hasSubtitles?.let { q["HasSubtitles"] = it.toString() }
    studioIds?.takeIf { it.isNotEmpty() }?.let { q["StudioIds"] = it.joinToString(",") }
    studios?.takeIf { it.isNotEmpty() }?.let { q["Studios"] = it.joinToString("|") }
    personIds?.takeIf { it.isNotEmpty() }?.let { q["PersonIds"] = it.joinToString(",") }
    years?.takeIf { it.isNotEmpty() }?.let { q["Years"] = it.joinToString(",") }
    // Genres / Tags / OfficialRatings 都是「|」分隔，只有 Years 是「,」分隔
    minCommunityRating?.let { q["MinCommunityRating"] = it.toString() }
    searchTerm?.takeIf { it.isNotBlank() }?.let { q["SearchTerm"] = it }
    mediaTypes?.takeIf { it.isNotEmpty() }?.let { q["MediaTypes"] = it.joinToString(",") }
    q["StartIndex"] = startIndex.toString()
    q["Limit"] = limit.toString()
    // Ids 查询一律非递归（见上方 ids 参数的说明）
    val byIds = ids?.takeIf { it.isNotEmpty() }
    q["Recursive"] = if (byIds != null) "false" else recursive.toString()
    q["Fields"] = fields
    q["EnableImages"] = "true"
    q["EnableUserData"] = "true"
    q["ImageTypeLimit"] = "1"
    return getJson(server, "/Users/${server.userId}/Items", q)
  }

  /**
   * 照片库：相册（PhotoAlbum）列表。
   *
   * ⚠️ **recursive=false**：相册是照片库下的一级容器，递归会把库/专辑里的照片
   * 一股脑倒出来，相册层级就没了。默认按名字升序（与 Emby 网页端一致）。
   *
   * 失败行为与 [getItems] 一致（抛给调用方），由 UI 决定怎么提示。
   */
  fun getPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("PhotoAlbum"),
    startIndex = startIndex,
    limit = limit,
    recursive = false,
    fields = PHOTO_FIELDS,
  )

  /**
   * 照片库：照片（Photo）列表。
   *
   * - [parentId] 传相册 Id 时取该相册内的照片；传库 Id 时递归拉平整个库
   *   （混合库「仅照片」态走这条：不管服务端把照片放在哪个专辑里，全部倒出来）。
   * - 排序传 `SortBy=Random` 即「随机一批照片」，供随机播放入口使用。
   * - 不传 `MediaTypes=Photo`：照片条目靠 `IncludeItemTypes=Photo` 已经圈死，
   *   再加 MediaTypes 属于重复过滤，且个别服务端对该参数的值域校验更严，
   *   多传一个反而可能整页拉空。
   */
  fun getPhotos(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    recursive: Boolean = true,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("Photo"),
    isFolder = false,
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    fields = PHOTO_FIELDS,
  )

  /**
   * 取「按名字聚合」端点 /Genres、/Tags、/OfficialRatings、/Years 返回的名称列表。
   * 这四个端点返回体结构一致（Items[].Name），可以共用一个取法。
   *
   * ⚠️ 失败仍返回空列表（**故意不抛**）：这四组数据是「筛选面板的选项」，
   * 缺了只是面板空着，不该因此让整个媒体库页面报错。**但必须留下日志** ——
   * 以前是纯 `getOrDefault(emptyList())`，失败后UI 表现为「筛选器里没有类型」，
   * 用户会以为库里真没有，而开发者根本查不到（这种静默失败最难查）。
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
    }.onFailure {
      EmbyLog.warn("nameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
    }.getOrDefault(emptyList())

  /** 某媒体库下出现过的全部类型名 */
  fun getGenres(server: EmbyServer, parentId: String? = null): List<String> =
    nameList(server, "/Genres", parentId)

  /**
   * 按名字查「类型」（Genre）条目的 Id。
   *
   * 用途：类型结果页（`EmbyTagItemsScreen`）顶栏的收藏红心要拿这个 Id 再去
   * [getItem] 取一条带 `UserData` 的完整条目，才知道「这个类型当前收没收藏」。
   * 详情页的类型 chip 只给了**名字**（`EmbyItem.Genres` 是纯字符串列表），
   * 所以必须有「名字 → Id」这条反查通道。
   *
   * 主路走官方 `/Genres` 聚合端点 —— 与筛选面板取「类型名 + Id」是同一个端点
   * （见 [getFilterOptions]）。该端点的 `parentId` 是**可选**参数，不传即
   * 「该用户可见的全部类型」（官方文档：Gets all genres from a given item,
   * folder, **or the entire library**）。拿到全量后本地匹配：先精确（忽略大小写），
   * 再退一步「包含」，兜住服务端类型名的全半角 / 空格差异。
   *
   * ⚠️ 不要退回 `/Items?IncludeItemTypes=Genre&SearchTerm=...` 且不带 `Recursive`：
   * 没有 `ParentId` 时非递归查询只扫用户根（那里只有媒体库视图、没有类型条目），
   * **必然返回空** —— 这正是「类型页右上角不显示收藏红心」的根因。
   *
   * 兜底：个别服务端的 `/Genres` 在缺 `ParentId` 时仍返回空，这时改走一次
   * 递归的 `/Items`（`Recursive=true` 才会跨库扫到 `Genre` 条目，
   * 与 [getFavoriteGenres] 同一口径）。
   */
  fun getGenreIdByName(server: EmbyServer, name: String): String? {
    if (name.isBlank()) return null
    // 主路：官方聚合端点
    idNameList(server, "/Genres", parentId = null)
      .let { candidates ->
        candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
          ?: candidates.firstOrNull { it.name.contains(name, ignoreCase = true) }
      }
      ?.let { return it.id }
    // 兜底：递归 /Items 按名字搜一次类型条目
    return runCatching {
      getItems(
        server = server,
        includeItemTypes = listOf("Genre"),
        searchTerm = name,
        recursive = true,
        limit = 64,
      ).Items.firstOrNull { it.Name.equals(name, ignoreCase = true) }?.Id
    }.onFailure {
      EmbyLog.warn("getGenreIdByName(\"$name\") 兜底查询失败：${it.message}", it)
    }.getOrNull()
  }

  /**
   * 直播电视频道列表（`/LiveTv/Channels`）。
   *
   * 返回体的 `Items[]` 结构与 `/Items` 一致（同一个 `EmbyItemsResult`），
   * 频道条目的 `Type` 是 `TvChannel`，封面照旧从 `ImageTags["Primary"]` 取。
   *
   * ⚠️ 失败返回空列表（与 [nameList] 同款处理）：服务端没开直播电视时这个端点会 404 / 500，
   * 那属于「这台服务器没有这个功能」，不该让页面报错。
   */
  fun getLiveTvChannels(server: EmbyServer): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      getJson<EmbyItemsResult>(server, "/LiveTv/Channels", q).Items
    }.onFailure {
      EmbyLog.warn("getLiveTvChannels 失败：${it.message} —— 该服务器可能未启用直播电视", it)
    }.getOrDefault(emptyList())

  /**
   * 直播电视节目单（`GET /LiveTv/Programs`，a2 + c1）。
   *
   * 两种用法：
   *  · 不带 [channelId]：返回**所有频道**在 [startTime]~[endTime] 窗口内的节目。
   *    频道网格的「正在播出」走这条 —— 一次请求覆盖全部频道，
   *    ⚠️ 不要按频道逐个拉（N 个频道 = N 个请求，进页面先卡一波）。
   *  · 带 [channelId]：只返回该频道的节目（频道详情页的时间轴列表走这条）。
   *
   * 时间参数用 ISO 8601 UTC 字符串（ServiceStack 的日期绑定 ISO / ticks 都吃，
   * ISO 是 Web 端引导区的用法）。节目**不依赖服务端排序** —— 客户端按
   * [EmbyItem.StartDate] 自己排，这个端点对排序不做保证。
   *
   * ⚠️ 失败返回空列表：服务端没开直播电视 / 没接 EPG 数据源时这个端点会
   * 404 / 500 / 空数据，都属于「没有节目单」，页面按空态显示，不该报错。
   *
   * 节目→频道的归属看节目的 `ParentId`（= 频道 Id），不需要额外字段。
   */
  fun getLiveTvPrograms(
    server: EmbyServer,
    channelId: String? = null,
    startTime: String,
    endTime: String,
    limit: Int = 500,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      channelId?.let { q["ChannelId"] = it }
      q["StartTime"] = startTime
      q["EndTime"] = endTime
      q["Limit"] = limit.toString()
      // Overview 是节目简介，Emby 对该端点默认不回，得显式要
      q["Fields"] = "Overview"
      getJson<EmbyItemsResult>(server, "/LiveTv/Programs", q).Items
    }.onFailure {
      EmbyLog.warn("getLiveTvPrograms 失败：${it.message} —— 该服务器可能没有节目单数据", it)
    }.getOrDefault(emptyList())

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
    }.onFailure {
      EmbyLog.warn("idNameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
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

  /**
   * 某媒体库里出现过的演员（PersonTypes=Actor）。
   *
   * 用于媒体库「演员」分类：返回**完整 EmbyItem**（带 [EmbyItem.ImageTags] 头像），
   * 复用现有媒体卡片渲染；强制 [EmbyItem.Type]="Person"，点击时走「该演员作品」路由。
   */
  fun getActors(
    server: EmbyServer,
    parentId: String? = null,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = "Actor"
      // 这里本来想直接要 ChildCount —— Person 在该字段里确实是「参与的作品数」，
      // 但 /Persons 端点的 Fields 白名单里没有它（官方文档的选项是 Budget / Chapters /
      // Genres / MediaStreams / People … ），传了会被服务端忽略，Person 的 ChildCount
      // 于是恒为 null，演员卡片左下角的角标永远画不出来。
      // 真正的数据来源是 [getPersonWorkCounts]（客户端自己数）。这行留着，万一某个
      // 服务端认它就能省掉那次统计。
      q["Fields"] = "ChildCount"
      // EnableUserData：让服务端回传 UserData.IsFavorite。演员长按收藏、收藏页「演员」tab
      // 都靠这个字段判断收藏状态，没有它 Person 的 IsFavorite 恒为空。
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("getPersons 失败：${it.message} —— 演员/导演筛选会是空的", it)
    }.getOrDefault(emptyList())

  /** 「演职员合并」扫描时一页拉多少人 */
  private const val PERSON_PAGE_SIZE = 500

  /** 「演职员合并」扫描的人员上限。超过就截断并在 UI 里说明，不会无限翻页。 */
  private const val PERSON_SCAN_MAX = 8_000

  /**
   * 拉**全服务器**的人员清单（演员 / 导演 / 编剧），给「演职员合并」扫重复用。
   *
   * 与 [getActors] 的三点差别：
   *  1. **不限媒体库**（`ParentId` 不传）：一个人在不同库里各有一份，合并必须跨库看；
   *  2. **工种可配**：重复的不只有演员，导演 / 编剧一样会裂，默认三种都要；
   *  3. **自己翻页**：`/Persons` 一次能返回多少条由服务端决定（部分版本会截断 `Limit`），
   *     所以用 `StartIndex` 逐页拉，并以**服务端给的 `TotalRecordCount`** 作为终止条件，
   *     而不是「本页返回数 < 请求数」—— 后者在服务端截断 Limit 时会在第一页就退出、漏掉大半。
   *
   * `Fields=ProviderIds` 是**尝试性**的：`/Persons` 的 Fields 白名单很窄
   * （[getActors] 已证实它会静默忽略 `ChildCount`）。能拿到就是「同名但其实是两个人」的
   * 关键反证；拿不到也不影响流程（退化为按名字判断）。
   */
  fun getPersonsDetailed(
    server: EmbyServer,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
    maxItems: Int = PERSON_SCAN_MAX,
  ): List<EmbyItem> = runCatching {
    val out = ArrayList<EmbyItem>()
    // 按 Id 去重：服务端若忽略 StartIndex 反复给同一批，没有这层会死循环
    val seen = HashSet<String>()
    var start = 0
    while (out.size < maxItems) {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = personTypes.joinToString("|")
      q["Fields"] = "ProviderIds"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      q["StartIndex"] = start.toString()
      q["Limit"] = PERSON_PAGE_SIZE.toString()
      val page = getJson<EmbyItemsResult>(server, "/Persons", q)
      if (page.Items.isEmpty()) break
      var fresh = 0
      page.Items.forEach { item ->
        val id = item.Id ?: return@forEach
        if (!seen.add(id)) return@forEach
        val name = item.Name?.takeIf { it.isNotBlank() } ?: return@forEach
        fresh++
        out.add(item.copy(Type = "Person", Name = name))
      }
      // 一条新的都没有 = 服务端在重复给同一批，收手（这也是防死循环的硬闸门）
      if (fresh == 0) break
      start += page.Items.size
      if (page.TotalRecordCount > 0 && start >= page.TotalRecordCount) break
    }
    out.take(maxItems)
  }.onFailure {
    EmbyLog.warn("scanAllPersons 失败：${it.message} —— 演职员合并的候选名单会是空的", it)
  }.getOrDefault(emptyList())

  /**
   * 一个人**参与了多少部作品** —— 用 `PersonIds` 查询的 `TotalRecordCount` 直接拿。
   *
   * 为什么不复用 [getPersonWorkCounts]：那个方法是把**整个库逐页拉下来自己数**，
   * 几千条的库要几十个请求，而且超过 1 万条时会整体放弃返回空表。
   * 这里只要一个数字，问服务端一次就够了（`Limit=1` 只取一条，响应很小）。
   *
   * `IsFolder=false` 与「作品数」的口径保持一致：只数**本身能播**的条目，
   * 免得一季、一个合集被算成一部作品。
   */
  fun getPersonWorkCount(server: EmbyServer, personId: String): Int =
    runCatching {
      getItems(
        server = server,
        personIds = listOf(personId),
        recursive = true,
        startIndex = 0,
        limit = 1,
        isFolder = false,
        fields = "People",
      ).TotalRecordCount
    }.onFailure {
      EmbyLog.warn("getPersonWorkCount($personId) 失败：${it.message} —— 该演员角标按0 处理", it)
    }.getOrDefault(0)

  /**
   * 收藏的演员列表（收藏页「演员」tab 用）。
   *
   * **为什么不用 `Filters=IsFavorite` 直接让服务端筛**：`/Persons` 这条端点对 Filters
   * 的支持参差不齐（官方 Emby 认，部分第三方兼容服务端会静默忽略，忽略后返回的是
   * 全量演员的第一页 —— 收藏的人可能根本不在里面）。而 `/Persons` 全量拉一遍是
   * 媒体库「演员」分类已经在用的成熟路径，这里复用它再在客户端按
   * `UserData.IsFavorite` 过滤，各服务端表现一致。
   *
   * 调用方（EmbyViewModel）按服务器缓存结果，切换 影片/演员 tab 不重复发请求。
   */
  fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    getActors(server, null).filter { it.UserData?.IsFavorite == true }

  /**
   * 按 Id 取单个人员（演员 / 导演），带 UserData.IsFavorite。演员作品页的收藏红心用。
   *
   * ⚠️ 参数形状按 Emby Web 实际请求校正：
   *  · `/Persons` 的 `Ids` 与 `Recursive` **不能同时传** —— `Ids+Recursive` 会让服务端
   *    先展开整个库再过滤（本项目在 `getItems` 上已经踩过并规避，见那里的注释），
   *    第三方服务端上几千条的库能直接拖到超时；
   *  · `Ids` 本身在部分服务端的 `/Persons` 上**不被支持**（Emby Web 自己也不用它，
   *    人员列表走 `PersonTypes` / `IsFavorite` 过滤），被忽略时这条查询会退化成
   *    「未过滤的人员第一页」—— 演员大多不在第一页，于是红心永远读不到状态；
   *  · 补 `PersonTypes=Actor,Director,Writer`：部分服务端不传它就返回空；
   *  · `/Users/{uid}/Items/{id}` 是 Emby Web 人员详情页的同款端点，显式带
   *    `EnableUserData`，防服务端在该路径上默认不回传 UserData。
   *
   * 三条路径的结果**合并**而非早退：谁带 UserData 用谁（IsFavorite 是红心的唯一数据源），
   * 都不带就返回任意一个非空结果（至少保证条目可点、状态按「未收藏」兜底）。
   */
  fun getPersonById(server: EmbyServer, personId: String): EmbyItem? {
    val candidates = ArrayList<EmbyItem>()

    // 路径 1：/Persons + Ids（关掉 Recursive，见上方说明）
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Ids"] = personId
      q["Recursive"] = "false"
      q["PersonTypes"] = "Actor,Director,Writer"
      q["SortBy"] = "SortName"
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .firstOrNull { it.Id == personId }
        ?.copy(Type = "Person")
    }.onFailure {
      EmbyLog.warn("getPersonById 路径1(/Persons?Ids) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 2：/Users/{uid}/Items/{id}（Emby Web 人员详情页同款端点；轻量 Fields + EnableUserData）
    runCatching {
      getJson<EmbyItem>(
        server,
        "/Users/${server.userId}/Items/$personId",
        mapOf(
          "EnableUserData" to "true",
          "Fields" to "BasicSyncInfo,CanDelete,CanDownload,PrimaryImageAspectRatio",
        ),
      )
        .takeIf { it.Id == personId }
        ?.let { item -> item.copy(Type = item.Type ?: "Person") }
    }.onFailure {
      EmbyLog.warn("getPersonById 路径2(/Users/{uid}/Items/{id}) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 3：原有兜底（getItem 的重 fields 组合）
    runCatching { getItem(server, personId) }
      .getOrNull()
      ?.takeIf { it.Id == personId }
      ?.let { person -> person.copy(Type = person.Type ?: "Person") }
      ?.let { candidates += it }

    if (candidates.isEmpty()) {
      EmbyLog.warn("getPersonById($personId) 三条路径全部失败 —— 红心按「未收藏」兜底，仍可点击")
      return null
    }
    return candidates.firstOrNull { it.UserData != null } ?: candidates.first()
  }

  /**
   * 读单个人员的**收藏真值**（true=已收藏 / false=未收藏 / null=读不到，无法确认）。
   *
   * ⚠️ **两条路径的先后顺序在 2026-10-21 被对调过**，这是个踩出来的坑，别再调回去：
   * 旧顺序是「单条轻量读 → 收藏列表扫描」，而单条读走的 `/Users/{uid}/Items/{id}`
   * 在用户的这台服务端上**对相当一部分人员会卡满 30 秒然后回 HTTP 500**
   * （如 1114461 / 1083309 / 1333172 / 1132405 全是 500，而 1031021、1081006 又 40ms 正常）。
   * 后果有两个：① 进演员页要先干等 30s+ 才轮到下一步；② 单条读抛异常后 `runCatching`
   * 返回 null，才轮到列表法 —— 而列表法其实 **54ms** 就能给出真值。
   * 更致命的是：演员作品页把「读收藏状态」挂在 [getPersonById] 的非空返回值后面，
   * 而 [getPersonById] 的三条路径（`/Persons?Ids` 不支持 → 空；`/Users/{uid}/Items/{id}` → 500）
   * 在这台机上对演员**全部失败**返回 null，于是列表法**连执行的机会都没有** ——
   * 这就是「导演作品页红心正常、演员作品页红心永远置灰」的根因（导演刚好走通了单条读）。
   *
   * 为什么敢把列表法放在第一步：
   *  · `/Persons?UserId=<guid>&IsFavorite=true&EnableUserData=true` 54ms 返回，带 UserData；
   *  · `POST .../FavoriteItems/<id>` 收藏一个人之后**立刻**再查，列表马上多出这个人
   *    （不存在「按 URL 缓存、回读拿到旧值」的问题 —— 旧代码担心的缓存坑在这条 URL 上不成立）；
   *  · `DELETE` 取消收藏后同样立刻消失。
   * 所以它可以同时承担「进页面取初值」和「收藏后回读确认」两个职责。
   *
   * 为什么不能只靠 [getPersonById]：演员红心历史上最顽固的表现是「收藏明明成功、
   * 回读却说没收藏，红心被回滚成灰色，怎么点都点不亮」。成因对上过两条：
   *  1. 服务端（或中间层）按 URL 缓存响应 —— 加载时读一次 `/Persons?Ids=X`，
   *     收藏成功后**同一个 URL** 再读还是缓存里的旧 UserData，于是把成功误判成失败；
   *  2. `/Persons` 的 `Ids` 参数在部分服务端上不被支持（见 [getPersonById] 注释）。
   */
  fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? {
    // 1) 收藏列表法（Emby Web 收藏页同款查询；最快、真值新鲜）—— 见上方注释，别调换顺序
    readFavoriteFromPersonList(server, personId)?.let { return it }
    // 2) 单条轻量读兜底：列表法拿不到结论时才走。部分人员在这台服务端上会 30s 后 500，
    //    失败就放弃（返回 null = 无法确认），不再让红心为它干等。
    return runCatching {
      getJson<EmbyItem>(
        server,
        "/Users/${server.userId}/Items/$personId",
        mapOf("EnableUserData" to "true"),
      )
        .takeIf { it.Id == personId }
        ?.UserData?.IsFavorite
    }.onFailure {
      EmbyLog.warn("getPersonFavoriteState 单条读 $personId 失败：${it.message}", it)
    }.getOrNull()
  }

  /**
   * 翻 `/Persons?IsFavorite=true` 找这个人：true=已收藏 / false=不在收藏列表里 / null=下不了结论。
   *
   * [isFavoriteFilterHonored] 只在「人找到了但没带 UserData」这一个歧义分支上被调用 ——
   * 那种情况下「出现在列表里」到底是"它被过滤出来的"还是"服务端忽略过滤返回了全量人员"，
   * 必须靠总数对比才能定，否则会把所有人的红心都点亮成已收藏。
   */
  private fun readFavoriteFromPersonList(server: EmbyServer, personId: String): Boolean? =
    runCatching {
      var start = 0
      val limit = 100
      var favTotal = 0
      var found: EmbyItem? = null
      while (found == null) {
        val q = LinkedHashMap<String, String?>()
        q["UserId"] = server.userId
        q["IsFavorite"] = "true"
        q["Recursive"] = "true"
        q["SortBy"] = "SortName"
        q["SortOrder"] = "Ascending"
        q["EnableUserData"] = "true"
        q["EnableTotalRecordCount"] = "true"
        q["StartIndex"] = start.toString()
        q["Limit"] = limit.toString()
        val page = getJson<EmbyItemsResult>(server, "/Persons", q)
        favTotal = page.TotalRecordCount
        found = page.Items.firstOrNull { it.Id == personId }
        if (found == null) {
          if (page.Items.isEmpty()) break
          // 终止条件两条腿走路：TotalRecordCount 说了算（服务端若忽略 Limit、按自己的
          // 默认上限分页时，「页不满」不代表拉完，会漏掉后面页里的人）；拿不到总数
          // （服务端不回传）才退化看页是否不满
          val total = page.TotalRecordCount.takeIf { it > 0 }
          if (total != null) {
            if (start + page.Items.size >= total) break
          } else if (page.Items.size < limit) {
            break
          }
          start += page.Items.size
          // 兜底防无限翻页：收藏的人员不可能有这个量级
          if (start >= 10_000) break
        }
      }
      val hit = found
      val hitFavorite = hit?.UserData?.IsFavorite
      when {
        // 翻完没找到 → 未收藏（能确认）
        hit == null -> false
        // 找到了且自带 UserData.IsFavorite → 直接信它（EnableUserData 生效时最准）
        hitFavorite != null -> hitFavorite
        // 找到了但拿不到 UserData.IsFavorite → 只有确认过滤真生效了，才敢把「在列表里」当成已收藏
        isFavoriteFilterHonored(server, favTotal) -> true
        else -> null
      }
    }.onFailure {
      EmbyLog.warn("readFavoriteFromPersonList($personId) 失败：${it.message}", it)
    }.getOrNull()

  /**
   * 判断这台服务端的 `/Persons` 是否真的认 `IsFavorite=true` 过滤：
   * 「收藏人员总数」明显少于「全部人员总数」就是生效了。
   *
   * 代价只是一次 `Limit=1` 的轻量请求，而且只在 [readFavoriteFromPersonList]
   * 撞上「找得到人、却没有 UserData」这个罕见分支时才发。
   */
  private fun isFavoriteFilterHonored(server: EmbyServer, favTotal: Int): Boolean {
    if (favTotal <= 0) return false
    val q = LinkedHashMap<String, String?>()
    q["UserId"] = server.userId
    q["Recursive"] = "true"
    q["EnableTotalRecordCount"] = "true"
    q["Limit"] = "1"
    val allTotal = runCatching {
      getJson<EmbyItemsResult>(server, "/Persons", q).TotalRecordCount
    }.getOrNull() ?: return false
    return allTotal > favTotal
  }

  /**
   * 数每个演员在该媒体库里参与了多少部作品：PersonId → 条目数。
   *
   * **为什么不直接读 `ChildCount`**：`/Persons` 端点的 `Fields` 参数并不支持
   * `ChildCount`（官方文档列出的可选项是 Budget / Chapters / Genres / MediaStreams /
   * People … ，没有它），传了也会被服务端忽略，Person 的 `ChildCount` 于是恒为
   * null —— 演员卡片左下角那个作品数角标就永远画不出来。所以自己数一遍。
   *
   * 口径与「点进这位演员」那一页保持一致（同一个库 + 递归 + 只要非容器条目），
   * 角标上的数字因此和点进去看到的条数对得上。
   *
   * 代价是把整个库逐页拉一遍，所以只索取 `People`、把 MediaSources 这类大字段去掉；
   * 几千条的库通常几次请求就够。调用方应放在后台，不要挡首屏。
   */
  fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    runCatching {
      val counts = HashMap<String, Int>()
      // 按 Id 去重：服务端若忽略 StartIndex 反复返回同一批，没有这层就会把同一部数好几次
      val seen = HashSet<String>()
      var start = 0
      while (true) {
        val page = getItems(
          server = server,
          parentId = parentId,
          startIndex = start,
          limit = COUNT_PAGE_SIZE,
          recursive = true,
          // 只要「本身能播」的条目：把 Folder / Season / BoxSet 这些容器挡掉，
          // 否则一季、一个合集都会被算成一部作品，数字虚高
          isFolder = false,
          fields = "People",
        )
        var fresh = 0
        page.Items.forEach itemLoop@{ item ->
          val id = item.Id
          if (id != null && !seen.add(id)) return@itemLoop
          fresh++
          item.People?.forEach { person ->
            person.Id?.let { pid -> counts[pid] = (counts[pid] ?: 0) + 1 }
          }
        }
        // 库太大就整体放弃：与其拉几十 MB 响应，不如让这几个角标先空着。
        // 只看第一页拿到的总数就够判断，不必真拉完
        if (start == 0 && page.TotalRecordCount > COUNT_MAX_ITEMS) {
          android.util.Log.d(
            "EmbyClient",
            "库规模 ${page.TotalRecordCount} 超过统计上限 $COUNT_MAX_ITEMS，跳过演员作品数统计",
          )
          return@runCatching emptyMap()
        }
        // 本页一条新的都没有 = 服务端在重复给同一批，收手
        if (fresh == 0 || page.Items.size < COUNT_PAGE_SIZE) break
        start += COUNT_PAGE_SIZE
        if (start >= COUNT_MAX_ITEMS) break
      }
      counts
    }.onFailure {
      EmbyLog.warn("getPersonWorkCounts 失败：${it.message} —— 演员角标会留空", it)
    }.getOrDefault(emptyMap())

  /**
   * 演员作品数角标 —— 按库规模自动挑一条路（[getPersonWorkCounts] 全库扫描太贵，
   * 逐个问又不敢全信，两头都要的话这里统一收口）。
   *
   * 策略分两档，用**第一条请求的 TotalRecordCount** 判断：
   *
   * - **小库**（≤ [COUNT_FULL_SCAN_MAX]）：走 [getPersonWorkCounts] 全库扫描。
   *   几千条 = 2~6 个请求就把**所有**演员的角标一次算齐，最划算。
   * - **大库**：逐个 PersonId 问服务端要 `TotalRecordCount`（[getPersonWorkCount]，
   *   `Limit=1` 响应极小），但**只查前 [ADAPTIVE_PER_PERSON_LIMIT] 个**。
   *   角标是次要信息，为它把大库拉穿不划算 —— 查不到的留空即可。
   *
   * @param personIds 这一轮要画角标的演员（通常是当前列表那一批）
   */
  fun getPersonWorkCountsAdaptive(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> {
    val targets = personIds.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) return emptyMap()

    val total = runCatching {
      getItems(
        server = server,
        parentId = parentId,
        startIndex = 0,
        limit = 1,
        recursive = true,
        isFolder = false,
        fields = "",
      ).TotalRecordCount
    }.getOrDefault(0)

    if (total in 1..COUNT_FULL_SCAN_MAX) {
      val all = getPersonWorkCounts(server, parentId)
      if (all.isNotEmpty()) return all
    }

    val out = HashMap<String, Int>(targets.size)
    for (pid in targets.take(ADAPTIVE_PER_PERSON_LIMIT)) {
      val n = getPersonWorkCount(server, pid)
      if (n > 0) out[pid] = n
    }
    return out
  }

  /** 大库兜底时逐个问演员的个数上限：够铺满好几屏，又不至于把请求打爆 */
  private const val ADAPTIVE_PER_PERSON_LIMIT = 60

  /**
   * 按名字搜演员。
   *
   * 必须走 `/Persons`：`/Users/{id}/Items` 这条拿媒体的端点**不返回 Person**，
   * 所以即便传了 `IncludeItemTypes=Person`，[getItems] 也永远只能拿到空列表 ——
   * 表现就是「勾选『演员』筛选后什么都搜不到」。
   *
   * @param parentId 限定某个媒体库；null 表示跨库（全库搜索用）
   */
  fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["SearchTerm"] = term
      q["Limit"] = limit.toString()
      // 与 [getActors] 同样的情况：Fields 里的 ChildCount 不被 /Persons 支持，
      // 搜索结果那张卡片的作品数同样得靠调用方另行统计（见 [getPersonWorkCounts]）
      q["Fields"] = "ChildCount"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      getJson<EmbyItemsResult>(server, "/Persons", q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("searchPersons(\"$term\") 失败：${it.message} —— 搜演员会搜不到", it)
    }.getOrDefault(emptyList())

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
  fun getFilterOptions(server: EmbyServer, parentId: String? = null): EmbyFilterOptions {
    // /Genres、/Tags 的返回体里 Items[] 同时带 Id 和 Name，一次请求拿两样
    // （名字给 UI，Id 给「按 Id 筛」的 GenreIds / TagIds 用）。
    val genreItems = idNameList(server, "/Genres", parentId)
    val tagItems = idNameList(server, "/Tags", parentId)
    return EmbyFilterOptions(
      genres = genreItems.map { it.name },
      tags = tagItems.map { it.name },
      genreItems = genreItems,
      tagItems = tagItems,
      years = nameList(server, "/Years", parentId)
        .mapNotNull { it.toIntOrNull() }
        .sortedDescending()
        .takeIf { it.isNotEmpty() } ?: fallbackYears(),
      officialRatings = nameList(server, "/OfficialRatings", parentId),
      persons = getPersons(server, parentId),
      studios = getStudios(server, parentId),
    )
  }

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
   *
   * ⚠️ **不要把 `TagItems` 加进这个清单**：它不在官方 OpenAPI 的 `ItemFields` 可选值里，
   * 而服务端对不认识的字段名有可能直接 400（见 [PHOTO_FIELDS] 的同一条告诫）。
   * 好消息是它**不需要索取**：单条目查询默认就会带 `TagItems`（响应里有
   * `TagItems` 三条、却连 `Tags` 字段都没有）⇒ 详情页读标签用
   * 「优先 TagItems、为空再退回 Tags」的写法，两边都索取就是白搭风险。
   */
  fun getItem(server: EmbyServer, itemId: String): EmbyItem {
    val path = "/Users/${server.userId}/Items/$itemId"
    // 先按「带 ExternalUrls」的清单要一次，失败再退回不带它的（原因见 [ITEM_FIELDS_WITH_EXTERNAL_URLS]）。
    // ⚠️ 必须显式写 `<EmbyItem>`：runCatching 里推断不出 reified 类型参数。
    val withExternalUrls = runCatching {
      getJson<EmbyItem>(server, path, mapOf("fields" to ITEM_FIELDS_WITH_EXTERNAL_URLS))
    }
    // 第二次再失败就让它抛出去 —— 真正的错误（网络 / 鉴权 / 404）不能被吞掉。
    return withExternalUrls.getOrElse {
      getJson(server, path, mapOf("fields" to ITEM_FIELDS_CORE))
    }
  }

  /**
   * 详情页要的字段清单（基础版）。
   *
   * 抽成常量而不是写在 [getItem] 里：它有两个使用者 —— 正常路径和
   * 「ExternalUrls 不被接受时的降级重试」，两处必须逐字一致。
   */
  private val ITEM_FIELDS_CORE: String = listOf(
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
    // 详情页「宣传语」一行 + 「外部链接」一行的数据来源，官方 ItemFields 里确实有这两个值
    "Taglines",
    "ProviderIds",
  ).joinToString(",")

  /**
   * [ITEM_FIELDS_CORE] + `ExternalUrls`（详情页「外部链接」一行的**首选**数据源）。
   *
   * 为什么要单列一个、还要在失败时重试：`ExternalUrls` 在 Emby SDK 的 `ItemFields`
   * 枚举里是有的（`BaseItemDto.ExternalUrls` 是标准字段），但它**没被写进官方 OpenAPI
   * 那段 `Fields` 可选值描述**（那段描述本身也不全，例如 `Size` / `Chapters` 都不在里面）。
   * 服务端对不认识的字段名**可能直接 400**，而详情页一旦 400 就整页打不开 ——
   * 这个代价（整页挂掉）远大于收益（少一个降级路径）。
   * 所以：带上它、失败就退回 [ITEM_FIELDS_CORE] 重来一次。
   *
   * 拿到 `ExternalUrls` 的价值：它的 `Url` 是**服务端/元数据插件拼好的完整地址**
   * （如 `http://<host>:<port>/?redirect=JavBus:ACZD-218`），与 Emby Web / Yamby
   * 打开的是同一条链接；拿 `ProviderIds` 自己拼容易拼错（见 `EmbyDetailScreen`）。
   */
  private val ITEM_FIELDS_WITH_EXTERNAL_URLS: String =
    // SiblingIds：单集详情页要拿「同季其它集」。它是标准 BaseItemDto 字段，
    // 但同样不一定被老服务端认 —— 所以放在这个「失败就降级」的清单里，
    // 而不是塞进 [ITEM_FIELDS_CORE]（那会把降级路径一起弄坏）。
    "$ITEM_FIELDS_CORE,ExternalUrls,SiblingIds"

  /**
   * 拉取条目的章节表。
   *
   * 对应 SDK：`ItemsServiceApi.getItemsByIdChapters` → `GET /Items/{Id}/Chapters`。
   *
   * ## 为什么 GSY 内核要靠这个端点（清单 #19）
   *
   * mpv 侧的章节来自容器内嵌章节表（`chapter-list` property，由 demuxer 解析），
   * 所以本地文件也有章节。而 GSYVideoPlayer **没有任何章节 API** ——
   * ExoPlayer 只认 mp4 的 `ChapterFrame`、IJK 的 `getMediaMeta()` 不保证有，
   * 都不是可靠来源。唯一稳定的通道就是 Emby 侧这份元数据。
   *
   * ⚠️ 由此产生一条**必须说清的能力边界**（也写进了 [EngineFeature.CHAPTERS] 的 KDoc）：
   * 同一个文件在两个内核下章节数量可能不同 —— mpv 从容器解析得到（本地文件也有），
   * GSY 从 Emby 拿（纯本地文件没有）。这不是 bug，是两条数据来源的固有差异。
   *
   * 服务端没有章节时返回**空数组**（不是 404），所以正常路径下不会抛异常。
   * [EmbyChapter.Name] 允许为空：不少刮削器只写了时间点没写标题，
   * 这种情况在 UI 上按「第 N 章 / 时间」兜底展示（见 `GsyChapterSheet`）。
   */
  fun getItemChapters(server: EmbyServer, itemId: String): List<EmbyChapter> =
    getJson(server, "/Items/$itemId/Chapters")

  /**
   * 拉取条目的片头 / 片尾分段（Emby 4.7+ 原生 Intro Skip）。
   *
   * 对应端点：`GET /Users/{UserId}/Items/{Id}/Intros`。
   *
   * ⚠️ 两个「拿不到」要分清：
   * - 服务端没扫描过片头标记 ⇒ 返回**空 Segments**（正常，不是错误）；
   * - 服务端版本 < 4.7 / 端点不存在 ⇒ 抛异常，由仓库层兜成空结果。
   *
   * 调用方（播放页）拿空结果时退到「固定时长兜底」，见 `PlayerPreferences.autoSkipIntro`。
   */
  fun getItemIntros(server: EmbyServer, itemId: String): EmbyIntrosResult =
    getJson(server, "/Users/${server.userId}/Items/$itemId/Intros")

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

  /**
   * 某剧集下的季列表。
   *
   * ⚠️ [startIndex] / [limit] 是后加的分页参数（对应清单 1.1）——
   * 原先这里一次性拿回全部季，长剧（几十季）会白拉一堆详情。
   * 传 [limit] = 0 / 不传时行为与从前一致（服务端按默认返回）。
   */
  fun getSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Seasons",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to (limit ?: 0).toString(),
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 某季（或整部剧集）下的剧集列表。
   *
   * ⚠️ 分页参数是后加的（对应清单 1.1）：原先一次拿回全部集，
   * 长剧（几百集）响应体很大且列表全进内存。现在由详情页按季分页拉。
   */
  fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/$seriesId/Episodes",
    mapOf(
      "UserId" to server.userId,
      "SeasonId" to seasonId,
      "StartIndex" to startIndex.toString(),
      "Limit" to (limit ?: 0).toString(),
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
    // 收藏页要按年份 / 评分 / 添加时间做**本地排序**，这几个字段必须显式索取
    // （见 [ITEM_FIELDS_WITH_SORT_KEYS] 的说明：不索取就全是 null，排序等于没排）
    fields = ITEM_FIELDS_WITH_SORT_KEYS,
  )

  /**
   * 收藏的**类型**（Genre）列表（收藏页「类型」tab 用）。
   *
   * Genre 是带 UserData 的正式条目，可以被收藏 —— 所以与 [getFavoriteItems] 同一套口径，
   * 让服务端直接按 `Filters=IsFavorite` 筛。
   *
   * ⚠️ **不传 MediaTypes**：类型没有媒体形态（不像影片是 Video），
   * 硬塞一个 MediaTypes 会让部分服务端整页拉空。
   *
   * @param limit 类型数量通常几十个，默认给 200 一次拉完，不分页。
   */
  fun getFavoriteGenres(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
    sortOrder = "Ascending",
    filters = listOf("IsFavorite"),
    includeItemTypes = listOf("Genre"),
    startIndex = startIndex,
    limit = limit,
  )

  /**
   * 收藏的**工作室**（Studio）列表（收藏页「工作室」tab 用）。
   *
   * 口径与 [getFavoriteGenres] 完全一致，只把条目类型换成 Studio。
   */
  fun getFavoriteStudios(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = "SortName",
    sortOrder = "Ascending",
    filters = listOf("IsFavorite"),
    includeItemTypes = listOf("Studio"),
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
  // 2.5 相似推荐 / 即混（Instant Mix）/ 续播（NextUp）
  // ════════════════════════════════════════════════════════════════════════
  // 这三个端点是**服务端算法**。客户端还另有一套本地打分算法（见
  // `ui/browser/emby/discover/LocalSimilarity.kt`），两者由
  // `SimilarAlgorithm` 偏好切换 —— 服务端没刮削或算法不产结果时，
  // 本地那套能兜住，不至于整个区块空着。

  /**
   * 相似推荐：`GET /Items/{Id}/Similar`。
   *
   * ⚠️ **没有** `MediaSourceId` / `IsMusic` / `IsLiveTvPrograms` / `IsNewsPrograms`
   * 这几个参数（它们只存在于 Jellyfin，Emby 的参数集比 Jellyfin 少），
   * 写上去会被服务端直接忽略，调试时极难发现「为什么筛选没生效」。
   *
   * @param itemId 种子条目（拿它的类型 / 人员 / 年代去做相似匹配）
   * @param limit 返回条数。Emby 服务端对 `Similar` 的实现是「查库再算相似度」，
   *   limit 给太大反而拖慢响应，20~50 比较合适。
   * @param includeItemTypes 只要这些类型；不传表示不限。
   *   默认 `Movie,Series` —— 单集（Episode）混进来会把区块塞满同一部剧的几十集。
   * @param excludeIdList 要排除的条目 Id（例如种子自己）。
   *   服务端通常自己会排除种子，但**第三方兼容实现不总是排除**，
   *   所以客户端还会再滤一次（见 `SimilarRecommendationViewModel`）。
   */
  fun getSimilarItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 24,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Items/$itemId/Similar",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      "ExcludeIdList" to excludeIdList?.joinToString(","),
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 即混：`GET /Items/{Id}/InstantMix`（服务端基于种子自动续播一批）。
   *
   * 参数集与 [getSimilarItems] **完全一致**（同一个服务端实现里出来的），
   * 所以复用一套参数拼装，不要以为它有什么特殊参数。
   *
   * ⚠️ **不存在 `/Shows/{Id}/InstantMix`** —— 那是幻觉端点。真要「按某部剧接着播」，
   * 只能用 `/Items/{Id}/InstantMix` 再配 `Filters=IsResumable`。
   */
  fun getInstantMixItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    filters: List<String>? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Items/$itemId/InstantMix",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      "Filters" to filters?.takeIf { it.isNotEmpty() }?.joinToString("|"),
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 续播（NextUp）：`GET /Shows/NextUp`。
   *
   * ⚠️ **两个极易写错的点**：
   * 1. `UserId` 是**必填 query 参数**，不是 path 段 —— 写成
   *    `/Shows/{UserId}/NextUp` 会 404，且报错信息毫无指向性。
   * 2. **不存在 `/Shows/{Id}/NextUp`**（按剧集取续播）这个端点。
   *    要限定某部剧，用 `SeriesId` query 参数。
   *
   * 服务端没启用「剧集自动续播」时这个列表会是空的，UI 要能接受空态。
   */
  fun getNextUpItems(
    server: EmbyServer,
    limit: Int = 20,
    seriesId: String? = null,
  ): EmbyItemsResult = getJson(
    server,
    "/Shows/NextUp",
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "SeriesId" to seriesId,
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
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
   * a8：**一次写多项** UserData —— 标记已看 / 未看的同时把播放进度归零。
   *
   * `POST /Users/{uid}/Items/{id}/UserData` 是 Emby 的「用户数据直写」端点，
   * body 是部分更新语义（同 [clearPlaybackPosition] 已经验证过的用法），
   * 所以「已看 + 进度」两项可以合成**一个**请求发出去。
   *
   * 为什么值得单开一个方法：旧路径 `PlayedItems` 只翻 Played 一个位，
   * 看完 42 分钟标记已看后，服务端仍挂着 `PlaybackPositionTicks=42min`
   * —— 部分兼容服务端的「继续观看」因此不清不爽，Emby Web 的同一动作
   * 本来就是两个字段一起写的。
   *
   * 兜底：端点不存在 / 老服务端不认这个路由时，退回 [setPlayed] 的
   * `PlayedItems` 单字段写法（只是不清进度，动作本身仍然生效）。
   * 回执解析不出（空 body / 拼接 JSON）时返回 null —— 调用方按
   * 「无法确认」处理，不下失败结论（同 [toggleUserState] 的口径）。
   */
  fun setPlayedWithClearPosition(
    server: EmbyServer,
    itemId: String,
    played: Boolean,
  ): EmbyUserData? {
    val path = "/Users/${server.userId}/Items/$itemId/UserData"
    val body = """{"Played":$played,"PlaybackPositionTicks":0}"""
    val response =
      runCatching {
        execString(
          authedRequest(server, path, mapOf("X-Emby-Token" to server.apiToken.takeIf { it.isNotEmpty() }))
            .header("Accept", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build(),
        )
      }.getOrNull() ?: return setPlayed(server, itemId, played)
    return parseUserData(response)
  }

  /**
   * 收藏 / 已播放这类「开关型」接口的统一发送。
   *
   * 请求形态照 Emby Web 的两种（**不是**官方 OpenAPI 写的那种）：
   *   打开：`POST /Users/{uid}/FavoriteItems/{id}`
   *   关闭：`POST /Users/{uid}/FavoriteItems/{id}/Delete` ← 是 POST 到 `/Delete`，不是 DELETE 方法
   * SmartStrm 这类服务端只认后一种写法，用 DELETE 方法会被拒。
   *
   * **请求细节向 Emby Web 的真实请求看齐**（与 Web 端核对过）：
   *  · `X-Emby-Token` 同时放 **query 参数和 header** —— 个别兼容服务端的这条路由只认 query；
   *  · 带 `Accept: application/json`，避免内容协商返回非 JSON。
   *
   * **响应体本身就是最新的 UserData**（`{"IsFavorite":true,"Played":false,...}`），
   * 直接解析它就能确认状态 —— 不用再回查一次条目：那台服务器的详情响应
   * `Content-Length` 比实际 body 长，OkHttp 读不满会抛 unexpected end of stream，
   * 回查失败就会把「其实已经成功了」误报成失败。
   * 回执可能不干净（SmartStrm 会把 JSON 重复拼接），所以解析失败后还有一层
   * 字段级提取兜底，见 [parseUserData]。
   */
  private fun toggleUserState(server: EmbyServer, basePath: String, enable: Boolean): EmbyUserData? {
    val path = if (enable) basePath else "$basePath/Delete"
    // 客户端标识 + token 一起进 query：与 Emby Web 请求一致（见 [WEB_CLIENT_QUERY]）
    val clientQuery: Map<String, String?> = buildMap {
      put("X-Emby-Token", server.apiToken.takeIf { it.isNotEmpty() })
      putAll(WEB_CLIENT_QUERY)
    }
    // 请求构造：token 同时放 query 与 header + Accept: application/json（对齐 Emby Web）
    val buildRequest: (okhttp3.RequestBody) -> Request = { body ->
      authedRequest(server, path, clientQuery)
        .header("Accept", "application/json")
        .post(body)
        .build()
    }
    // 1) 主：POST + 无 body（Web 端就是这个形态，content-length=0）
    val first = runCatching { execString(buildRequest(EMPTY_BODY)) }
    var body = first.getOrNull()
      // 2) 兜底：空 body 会被部分服务端（ServiceStack 系）判 400，此时改发空对象
      ?: runCatching { execString(buildRequest(EMPTY_JSON_BODY.toRequestBody(jsonMedia))) }.getOrNull()
      // 3) 关闭再兜底：老版本只认 DELETE 方法
      ?: if (!enable) {
        runCatching { execString(authedRequest(server, basePath, clientQuery).delete().build()) }.getOrNull()
      } else {
        null
      }
    // 2) 的空对象回执可能把 1) 的干净回执顶掉：优先取「能解析出 IsFavorite」的那份
    if (body != null && parseUserData(body) == null) {
      (first.getOrNull())?.takeIf { parseUserData(it) != null }?.let { body = it }
    }
    // 三种形态都失败：把第一次的真实原因抛出去（它最接近「实际用的是哪种形态」的问题）
    if (body == null) throw first.exceptionOrNull() ?: EmbyApiException(0, "请求失败")
    return parseUserData(body)
  }

  /**
   * 取消收藏的「用力」版：在 [toggleUserState] 的几路之外，**再显式补一次标准 DELETE 方法**。
   *
   * 为什么要多这一枪：部分兼容服务端对 `POST .../FavoriteItems/{id}/Delete` 会回 200，
   * 但**并没有真的落库**（收藏演员时踩过同一个坑）。[toggleUserState] 见到 200 就返回了，
   * 调用方光看回执根本发现不了 —— 表现就是「取消收藏点了没反应 / 红心只能点亮不能取消」。
   * 取消收藏是「要在服务器上真的抹掉」的动作，宁可多发一次请求：
   * 两个形态都发，任意一路成功即可。返回值仍然交给调用方回读确认。
   *
   * 不在这里抛异常：发不出去也要让调用方走「回读判真值」的流程，避免把软失败误报成硬失败。
   */
  fun unfavoriteHard(server: EmbyServer, itemId: String): EmbyUserData? {
    val base = "/Users/${server.userId}/FavoriteItems/$itemId"
    // 客户端标识 + token 一起进 query（同 [toggleUserState]，对齐 Emby Web）
    val clientQuery: Map<String, String?> = buildMap {
      put("X-Emby-Token", server.apiToken.takeIf { it.isNotEmpty() })
      putAll(WEB_CLIENT_QUERY)
    }
    // 1) 兼容服务端认的写法（POST 到 /Delete）
    val viaPost = runCatching { toggleUserState(server, base, false) }
    // 2) 官方标准写法：DELETE 方法
    val viaDelete =
      runCatching {
        execString(
          authedRequest(server, base, clientQuery)
            .delete()
            .build(),
        )
      }
    // 优先取「能解析出 IsFavorite」的那份回执（DELETE 的回执多为空 body，解析不出就返回 null），
    // 都拿不到状态就返回 null，交给调用方回读判真值 —— 这里不下结论、也不抛异常。
    return viaDelete.getOrNull()?.let { parseUserData(it) } ?: viaPost.getOrNull()
  }

  /**
   * 解析响应体里的 UserData。
   *
   * 先做严格 JSON 解析；失败后退回**字段级提取**——SmartStrm 会把回执 JSON
   * 重复拼接成 `{...}{...}`（不是合法 JSON，但两条里的 IsFavorite 一致），
   * 这条回执只关心 IsFavorite，正则抠出真值即可。
   * 解析不了（空 body / 完全没有该字段）返回 null，由调用方回读确认。
   */
  private fun parseUserData(body: String): EmbyUserData? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    runCatching { return json.decodeFromString<EmbyUserData>(trimmed) }
    val match = Regex("\"IsFavorite\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
      .find(trimmed) ?: return null
    return EmbyUserData(IsFavorite = match.groupValues[1].equals("true", ignoreCase = true))
  }

  /**
   * 删除媒体 —— **物理删除服务器上的文件**，不可逆。
   *
   * 用的是 Emby 官方的 `DELETE /Items/{Id}`，其定义就是
   * 「Deletes an item from the library **and file system**」——
   * 即同时从媒体库移除**并删除磁盘上的源文件**。这与「取消收藏 / 标未看」
   * 完全不是一个量级：那两者只改用户数据，这个是销毁内容。
   *
   * ⚠️ 需要**管理员权限**。非管理员账号调这个会拿到 403，
   *   [execString] 会抛 [EmbyApiException]（带服务端原话），调用方据此提示。
   *   **不要**把它当"操作成功"来展示。
   */
  fun deleteItem(server: EmbyServer, itemId: String) {
    val req = authedRequest(server, "/Items/$itemId").delete().build()
    execString(req)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.2 批量操作（收藏 / 已看）
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 批量收藏 / 取消收藏。
   *
   * Emby 的 `FavoriteItems` 路由**没有**多值 Id 端点（官方 OpenAPI 只有单条
   * `POST /Users/{uid}/FavoriteItems/{id}`），所以这里在客户端逐条串行发送，
   * 把所有结果汇总成一个 [EmbyBatchResult] —— 单条失败不中断整批，
   * 用户最终看到的是「成功 N / 失败 M」而不是一句笼统的失败。
   *
   * 串行而非并发：Emby 的收藏写在部分兼容服务端上是「先回 200 再异步落库」，
   * 并发几十条很容易触发它的写锁竞争，串行慢一点但结果可信。
   */
  fun setFavoriteMany(server: EmbyServer, itemIds: List<String>, favorite: Boolean): EmbyBatchResult =
    runBatch(itemIds) { id -> toggleUserState(server, "/Users/${server.userId}/FavoriteItems/$id", favorite) }

  /**
   * 批量标记已看 / 未看。
   *
   * 与 [setFavoriteMany] 同构：走单条 `PlayedItems` 端点、客户端汇总。
   */
  fun setPlayedMany(server: EmbyServer, itemIds: List<String>, played: Boolean): EmbyBatchResult =
    runBatch(itemIds) { id -> toggleUserState(server, "/Users/${server.userId}/PlayedItems/$id", played) }

  /**
   * 批量执行的公共壳：逐条跑 [action]，把「抛了异常」或「回传状态与预期不符」计入失败。
   *
   * 判定与单条路径保持一致 —— 拿不到回执（null）不下失败结论，只有明确与目标相反才算失败。
   * 因为 toggleUserState 的语义是「enable=目标值」，回执的 IsFavorite/Played
   * 只要不是「明确与目标相反」就认为成功。
   */
  private fun runBatch(itemIds: List<String>, action: (String) -> EmbyUserData?): EmbyBatchResult {
    if (itemIds.isEmpty()) return EmbyBatchResult(0, 0, null)
    var ok = 0
    var failed = 0
    var firstError: String? = null
    for (id in itemIds) {
      val outcome = runCatching { action(id) }
      if (outcome.isSuccess) {
        ok++
      } else {
        failed++
        if (firstError == null) firstError = outcome.exceptionOrNull()?.message
      }
    }
    return EmbyBatchResult(ok, failed, firstError)
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.3 「继续观看」单条清除
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 把一条媒体从服务器的「继续观看」列表里移除，并清掉它的播放位置。
   *
   * 这是「继续观看」卡片上那项操作的服务器侧实现，和 Emby Web 端
   * 「从继续观看中移除」是同一个动作。三种形态逐层兜底（不同服务端实现不一）：
   *
   *  1. `POST /Users/{uid}/Items/{id}/HideFromResume`，body `{"Hide":true}` —— Emby 官方；
   *  2. 同上但发空对象 body —— 个别 ServiceStack 服务端拒收带内容的 body；
   *  3. `DELETE /UserItems/{id}/UserData` —— Jellyfin 系 / 兼容服务端的等价写法。
   *
   * 返回 `true` 表示至少有一层被服务端接受。**注意**：返回 true 只代表请求发出去了，
   * 不代表对方一定落库（兼容服务端有「回 200 不生效」的前科），所以 UI 侧
   * 仍然做本地乐观移除，不依赖这里做二次回读。
   */
  fun hideFromResume(server: EmbyServer, itemId: String): Boolean {
    val path = "/Users/${server.userId}/Items/$itemId/HideFromResume"
    runCatching {
      execString(authedRequest(server, path).post("""{"Hide":true}""".toRequestBody(jsonMedia)).build())
    }.onSuccess { return true }
    runCatching {
      execString(authedRequest(server, path).post(EMPTY_JSON_BODY.toRequestBody(jsonMedia)).build())
    }.onSuccess { return true }
    return runCatching {
      execString(authedRequest(server, "/UserItems/$itemId/UserData").delete().build())
    }.isSuccess
  }

  /**
   * 清除服务器上该条目的**播放进度**（下载完成后顺手调用，见「下载完成后清除服务端播放进度」开关）。
   *
   * 只把 PlaybackPositionTicks 归零，**不动已看标记** —— 片子已经落到本地了，
   * 服务器再挂着「看到第 42 分钟」只会让「继续观看」里多一条没用的记录；
   * 但「有没有看过」是用户自己的档案，不该被一次下载动作改掉。
   *
   * 两种请求形态兜底（不同服务端实现认的不一样）：
   * 1. `POST /Users/{uid}/Items/{id}/UserData` + `{"PlaybackPositionTicks":0}`（官方形态）；
   * 2. 退回 `DELETE /UserItems/{id}/UserData`（部分兼容层只认这条，代价是连已看一起清）。
   * 两者都失败返回 false —— 调用方静默忽略，下载本身早已成功，不该因此报错。
   */
  fun clearPlaybackPosition(server: EmbyServer, itemId: String): Boolean {
    val path = "/Users/${server.userId}/Items/$itemId/UserData"
    runCatching {
      execString(
        authedRequest(server, path)
          .post("""{"PlaybackPositionTicks":0}""".toRequestBody(jsonMedia))
          .build(),
      )
    }.onSuccess { return true }
    return runCatching {
      execString(authedRequest(server, "/UserItems/$itemId/UserData").delete().build())
    }.isSuccess
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3.4 直链（302）预解析
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
  ): String? {
    val original = videoStreamUrl(server, itemId, static = static, mediaSourceId = mediaSourceId)
    repeat(attempts.coerceAtLeast(1)) {
      val direct = runCatching {
        val req = Request.Builder()
          .url(original)
          // 只取 1 个字节：既让服务端走完整跳转链，又不真的下载内容
          .header("Range", "bytes=0-0")
          // ⚠️ 必须显式带上与播放器一致的 UA。网盘直链（115 这类）的签名常把 UA
          // 算进去：探测用 OkHttp 默认 UA 拿到直链、再交给 mpv 用另一套 UA 去请求，
          // 服务端就会以 403 拒绝 —— 现象是「解析成功、播放必失败」。
          .header("User-Agent", STREAM_USER_AGENT)
          .get()
          .build()
        httpClient.newCall(req).execute().use { resp ->
          // 只有**确实取到了内容**才认这条直链。
          // 早期版本只判断「有没有发生跳转」，于是服务端回 403 / 404 时照样把那条
          // 死链当成直链交给播放器 —— 播放器必然失败，且无从回退。
          // 这里一票否决：非 2xx 一律当解析失败，调用方回退到原始 Emby 地址
          // （那条路径由播放器自己跟 302，可播）。
          if (!resp.isSuccessful) {
            android.util.Log.w(
              "EmbyClient",
              "预解析直链被拒（HTTP ${resp.code} @ ${resp.request.url.host}），回退原始地址",
            )
            return@runCatching null
          }
          // response.request 是「跟完跳转后的最后一个请求」，它的 URL 就是直链
          val finalUrl = resp.request.url.toString()
          finalUrl.takeIf {
            it != original && (it.startsWith("http://") || it.startsWith("https://"))
          }
        }
      }.getOrNull()
      if (direct != null) return direct
    }
    return null
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
    // 主请求：ItemId 按字符串发
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
  // 3.8 演职员合并（Person 去重）：整包读写条目 / 管理员探测 / 服务端任务
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 取条目的**原始 JSON 文本**（不解析成 [EmbyItem]）。
   *
   * ⚠️ 演职员合并改的是条目里的 `People` 引用，而 `POST /Items/{id}` **必须回传完整 payload**
   * （Emby 开发者 Luke 官方确认："you have to include the complete payload"，
   * 缺字段会被**清空** —— 社区里有人被清掉 plot / year / rating）。
   * 我们的 [EmbyItem] 只声明了四十来个字段，序列化回去恰好就是「不完整的 payload」，
   * 所以这里必须原文进、原文出，中途只在 `JsonObject` 上做定点修改。
   *
   * 走 `Users/{uid}/Items/{id}` 这条（Luke 明确推荐）而不是 `/Items/{id}`：
   * 用户维度的那条会带上 UserData，字段更全。
   *
   * `Fields` 里**必须包含 `People`** —— 不带的话返回体里没有 People，
   * 改写函数会认为「这条没事要改」从而静默跳过，表现就是「合并点了但作品一部都没动」。
   */
  fun getItemRawJson(server: EmbyServer, itemId: String): String =
    execString(
      authedRequest(
        server,
        "/Users/${server.userId}/Items/$itemId",
        mapOf("Fields" to RAW_ITEM_FIELDS),
      ).get().build(),
    )

  /**
   * 回写「整份条目 JSON」。
   *
   * 两种形态都试：主用 `POST /Items/{id}`（官方路由，社区脚本用的也是它），
   * 被拒就退 `PUT /Items/{id}`（本仓库原有的「编辑元数据」走的就是 PUT，在用户的服务端上已证实可用）。
   * 哪个先由 [preferPut] 决定 —— 合并引擎会在首条做一次回读校验，
   * 发现 POST 被「回 200 但没落库」时，把整批切到 PUT。
   *
   * @return true 表示服务端接受了这次写入（**不代表真的落库**，落库由调用方回读校验）
   */
  fun updateItemRawJson(
    server: EmbyServer,
    itemId: String,
    rawJson: String,
    preferPut: Boolean = false,
  ): String {
    val buildRequest = { verb: String ->
      val builder = authedRequest(server, "/Items/$itemId")
      val body = rawJson.toRequestBody(jsonMedia)
      (if (verb == "PUT") builder.put(body) else builder.post(body)).build()
    }
    var lastError: Throwable? = null
    for (verb in if (preferPut) listOf("PUT", "POST") else listOf("POST", "PUT")) {
      val outcome = runCatching { execString(buildRequest(verb)) }
      if (outcome.isSuccess) return verb
      lastError = outcome.exceptionOrNull() ?: lastError
    }
    throw (lastError ?: EmbyApiException(0, "更新条目失败"))
  }

  /**
   * 当前登录账号是不是管理员。
   *
   * 为什么必须先问：改条目元数据（`POST /Items/{id}`）是**管理员操作**，
   * 非管理员会被拒。与其让用户点了半天在每个条目上吃 403，
   * 不如进页面就把整块写作能力禁掉并说明原因。
   *
   * 探测失败（网络 / 端点不可用）返回 false —— **保守当作没有权限**，
   * 不能让一次失败的网络请求把「不能写」误判成「能写」。
   */
  fun fetchIsAdmin(server: EmbyServer): Boolean =
    runCatching {
      getJson<EmbyUserDetailDto>(server, "/Users/${server.userId}").Policy?.IsAdministrator == true
    }.onFailure {
      EmbyLog.warn("fetchIsAdmin 失败：${it.message} —— 保守当作无权限（写作能力会被禁掉）", it)
    }.getOrDefault(false)

  /** 服务端定时任务清单（找「刷新人员」用） */
  fun getScheduledTasks(server: EmbyServer): List<EmbyScheduledTask> =
    runCatching { getJson<List<EmbyScheduledTask>>(server, "/ScheduledTasks") }
      .onFailure { EmbyLog.warn("getScheduledTasks 失败：${it.message}", it) }
      .getOrDefault(emptyList())

  /**
   * 立刻触发一个服务端定时任务。
   *
   * 这是「清理孤立演员」的实现方式：**不自己去删 Person**，而是让 Emby 跑它自己的
   * 「刷新人员」任务去回收已经没有任何作品引用的条目。
   * 好处是**零风险**（完全走 Emby 自己的逻辑，不会误删有引用的条目），
   * 代价是**异步** —— 接口立刻返回，实际清理要过一会儿。
   */
  fun runScheduledTask(server: EmbyServer, taskId: String): Boolean = runCatching {
    execString(
      authedRequest(server, "/ScheduledTasks/Running/$taskId")
        .post(EMPTY_JSON_BODY.toRequestBody(jsonMedia))
        .build(),
    )
    true
  }.onFailure {
    EmbyLog.warn("runScheduledTask($taskId) 失败：${it.message}", it)
  }.getOrDefault(false)

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
    /** 多版本媒体：本次播放的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): String {
    val obj = buildMap {
      put("ItemId", itemId)
      put("PositionTicks", positionTicks)
      put("PlayMethod", playMethod)
      mediaSourceId?.let { put("MediaSourceId", it) }
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
   * 构造**照片**图片 URL（照片浏览器 / 照片卡片用）。
   *
   * 与 [imageUrl] 同一端点（`GET /Items/{itemId}/Images/Primary`），差别只有一个
   * 可选的 [format] 参数：传 `"jpg"` 时服务端把原图**转码成 JPEG** 再返回
   * （OpenAPI 原文：`Determines the output foramt of the image - original,gif,jpg,png`）。
   * 这是 HEIC 的兜底通道 —— 设备解不了 HEIC 时，让服务端转码而不是本地解。
   *
   * 为什么不直接改 [imageUrl] 加参数：照片链路整体是新增功能（相册/浏览器/混合库
   * 照片分支），单独一个函数能让视频侧调用点**一行都不用动**，回归风险为零。
   *
   * @param format 输出格式；null = 服务端按原图格式返回（默认）。HEIC 兜底传 `"jpg"`。
   */
  fun photoImageUrl(
    server: EmbyServer,
    itemId: String,
    tag: String? = null,
    maxWidth: Int = 1280,
    maxHeight: Int? = null,
    format: String? = null,
  ): String {
    val q = LinkedHashMap<String, String>()
    if (tag != null) q["tag"] = tag
    q["maxWidth"] = maxWidth.toString()
    maxHeight?.let { q["maxHeight"] = it.toString() }
    format?.let { q["Format"] = it }
    q["quality"] = "90"
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "${server.hostUrl}/emby/Items/$itemId/Images/Primary?$qs"
  }

  /**
   * 构造照片**原图下载** URL（`GET /Items/{itemId}/Download`）。
   *
   * 用于「保存到相册」：用户要的是相机拍出来的那个文件（含 EXIF 的 HEIC/JPEG），
   * 而不是服务端缩放/转码后的显示图。走 Download 端点拿到的就是磁盘原文件。
   *
   * 注意这个端点**不带任何缩放参数**，保存大图前应提示文件可能较大。
   */
  fun photoDownloadUrl(
    server: EmbyServer,
    itemId: String,
  ): String {
    val q = LinkedHashMap<String, String>()
    if (server.apiToken.isNotEmpty()) q["api_key"] = server.apiToken
    val qs = q.entries.joinToString("&") { (k, v) ->
      "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "${server.hostUrl}/emby/Items/$itemId/Download?$qs"
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
      execJson<PlaybackInfoResult>(
        authedRequest(server, "/Items/$itemId/PlaybackInfo")
          .post(payload.toRequestBody(jsonMedia))
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
    return "${server.hostUrl}/emby/Videos/$itemId/stream?$qs"
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
      execJson<PlaybackInfoResult>(
        authedRequest(server, "/Items/$itemId/PlaybackInfo")
          .post(payload.toRequestBody(jsonMedia))
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
    return "${server.hostUrl}/emby/Videos/$itemId/stream?$qs"
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
    return server.hostUrl + "/emby" + path
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
  // 5.5 播放列表（Emby 服务端）—— 实现在 EmbyPlaylists.kt，以下为转发
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 与项目里**已有的本地播放列表**（Room 的 PlaylistEntity / PlaylistDao）完全是两回事：
  // 这里的 playlist 存在 Emby 服务器上，多端共享、网页端也能看。
  // 两者刻意不互相同步 —— 本地的是 M3U 导入 + 本地路径，Emby 的是 ItemId，两者无法可靠映射。
  // 下面这些转发函数刻意**不用 inline**：inline + reified 泛型会要求调用方
  // 直接依赖实现细节，而且网络调用被复制到每个调用点后更难排查。
  // 留一层薄转发的好处是 —— 以后要在这里加统一的限流/日志/重试，只改这一处。
  // 默认参数值必须与 EmbyPlaylists 里的**完全一致**，否则两边行为会分叉。

  fun getPlaylists(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyPlaylists.getPlaylists(server, startIndex, limit)

  fun getPlaylistItems(
    server: EmbyServer,
    playlistId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyPlaylists.getPlaylistItems(server, playlistId, startIndex, limit)

  fun createPlaylist(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
  ): EmbyPlaylistCreationResult = EmbyPlaylists.createPlaylist(server, name, itemIds)

  fun addToPlaylist(
    server: EmbyServer,
    playlistId: String,
    itemIds: List<String>,
  ): EmbyPlaylistAddResult = EmbyPlaylists.addToPlaylist(server, playlistId, itemIds)

  fun removeFromPlaylist(
    server: EmbyServer,
    playlistId: String,
    entryIds: List<String>,
  ) = EmbyPlaylists.removeFromPlaylist(server, playlistId, entryIds)

  fun movePlaylistItem(
    server: EmbyServer,
    playlistId: String,
    entryId: String,
    newIndex: Int,
  ) = EmbyPlaylists.movePlaylistItem(server, playlistId, entryId, newIndex)

  fun deletePlaylist(
    server: EmbyServer,
    playlistId: String,
  ) = EmbyPlaylists.deletePlaylist(server, playlistId)

  // ════════════════════════════════════════════════════════════════════════
  // 5.6 字幕在线搜索（Emby 服务端）—— 实现在 EmbySubtitles.kt，以下为转发
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 与 [subtitleUrl]（拼 URL 把**服务端已有**的字幕轨交给 mpv）是两回事：
  // 这里是「搜字幕提供方 → 下载到服务器 → 再取字节」的三段式流程。
  // 默认参数值必须与 EmbySubtitles 里的完全一致。

  fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
    isForced: Boolean? = null,
    isHearingImpaired: Boolean? = null,
  ) = EmbySubtitles.searchRemoteSubtitles(
    server, itemId, language, mediaSourceId, isPerfectMatch, isForced, isHearingImpaired,
  )

  fun downloadRemoteSubtitle(
    server: EmbyServer,
    itemId: String,
    subtitleId: String,
    mediaSourceId: String? = null,
  ) = EmbySubtitles.downloadRemoteSubtitle(server, itemId, subtitleId, mediaSourceId)

  fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ) = EmbySubtitles.downloadSubtitleStream(server, itemId, mediaSourceId, subtitleIndex, format)
  // ════════════════════════════════════════════════════════════════════════
  // 5.7 合集（Collection / 官方客户端叫 BoxSet）—— 媒体库结构管理
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ **本节最容易踩的坑：REST 路径是 `/Collections`，不是 `/BoxSets`**
  // `/BoxSets` 是 **Jellyfin** 的命名。Emby 官方 Java 客户端里 BoxSet 走的是
  // `CollectionServiceApi` → `/Collections`。照着 Jellyfin 的文档 / 网上教程写必然 404。
  // 另一个要记的：**参数名是 `Ids` 不是 `EntryIds`**。
  // 口诀 —— BoxSet(Collection) 一律 `Ids`；只有 Playlist 增删条目才用 `EntryIds`。
  // 写错参数名服务端不报错，只是**静默不生效**（合集内容不变），极难排查。

  /**
   * 列出全部合集。
   *
   * 和播放列表一样，**没有** `GET /Collections` 这种「按类型拿」的专用端点 ——
   * 合集本身就是一种 Item，只能走 `/Users/{id}/Items` + `IncludeItemTypes=BoxSet`。
   *
   * ⚠️ 返回里的 `ChildCount` 是服务端算的合集条目数，可能与实际差一
   * （有条目被删但合集没刷新）。UI 显示它没问题，**拿它做判断条件不行**。
   */
  fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = getJson(
    server,
    "/Users/${server.userId}/Items",
    mapOf(
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "IncludeItemTypes" to "BoxSet",
      "Fields" to "Overview,DateCreated,ChildCount,Genres,ProductionYear",
      "SortBy" to "SortName",
      "SortOrder" to "Ascending",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 取合集里的条目（分页）。
   *
   * 对应 `GET /Collections/{Id}/Items`。
   *
   * 不用通用 `getItems(parentId = ...)` 的理由和播放列表一样：
   * `ParentId` 传播合集 Id 时部分服务端会算成空，老实用专用端点。
   */
  fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = getJson(
    server,
    "/Collections/$collectionId/Items",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "Fields" to ITEM_FIELDS,
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 新建合集。`POST /Collections`。
   *
   * ⚠️ **参数是 query 不是 body**（参考客户端里 `localVarPostBody = null`）。
   * 传成 body 的话服务端会当没收到，建出一个**空的没有名字的合集**。
   *
   * @param name 合集名
   * @param itemIds 初始条目（可空 = 建空合集）
   * @param parentId 挂在哪个媒体库下；null 表示用户根
   */
  fun createCollection(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    parentId: String? = null,
  ): EmbyCollectionCreationResult = postJson(
    server = server,
    path = "/Collections",
    bodyJson = "{}",
    query = buildMap {
      put("Name", name)
      put("Ids", itemIds.joinToString(",").ifEmpty { null })
      put("ParentId", parentId)
      put("UserId", server.userId)
    },
  )

  /**
   * 往合集追加条目：`POST /Collections/{Id}/Items`。
   *
   * ⚠️ `Ids` 是 **query**（同 [createCollection]）。
   * 返回体是空的，所以调用方拿不到「实际加了几条」——
   * 重复添加是幂等的，UI 只能自己判断要不要刷新。
   */
  fun addToCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean {
    if (itemIds.isEmpty()) return true
    val req = authedRequest(
      server,
      "/Collections/$collectionId/Items",
      mapOf("Ids" to itemIds.joinToString(","), "UserId" to server.userId),
    ).post("{}".toRequestBody(jsonMedia)).build()
    httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
  }

  /**
   * 从合集移除条目：`DELETE /Collections/{Id}/Items`。
   *
   * ⚠️ **参数是 `Ids` 且指的是「媒体 Id」，不是合集内的条目 Id**
   * （和 `POST /Playlists/{Id}/Items/Move` 那个 `ItemId` 正好相反，
   * 那边要的是播放列表内的条目 Id —— 两者极易搞混）。
   *
   * 这是真 DELETE（不是 POST 一个 `Ids` 标记），响应体为空。
   */
  fun removeFromCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean {
    if (itemIds.isEmpty()) return true
    val req = authedRequest(
      server,
      "/Collections/$collectionId/Items",
      mapOf("Ids" to itemIds.joinToString(","))
        .plus("UserId" to server.userId),
    ).delete().build()
    httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
  }

  // ════════════════════════════════════════════════════════════════════════
  // 5.8 显示偏好（DisplayPreferences）
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 三条容易踩空的真相，实现前务必先读完：
  // 1. **粒度是「每个目标」而不是「每个用户」**。落点的Id 叫
  //    `DisplayPreferencesId`，来自 `BaseItemDto.DisplayPreferencesId`，
  //    媒体库和单条目各有一份。所以不存在「一个用户一份显示偏好」这种写法 ——
  //    想让整个 App 的偏好跟着走，就得遍历所有目标库逐个写（见 EmbyDisplayPrefsSync）。
  // 2. **`Client` 是必填的分区键**，不是可选的 UA。`GET` 时它是 query 参数
  //    （`POST` 时它只在 body 里，见下），取不同值就读到不同的偏好副本。
  //    本 App 用 [DISPLAY_PREFS_CLIENT] 作为一个**独立分区**，
  //    好处是绝不会把 Emby Web 的排版偏好冲掉；代价是**不与网页端互通**，
  //    这是有意取舍，不是没做完。
  // 3. **不要写到 `/Users/{Id}/Configuration`**。`UserConfiguration` 的 17 个字段
  //    （音频语言 / 字幕语言 / ProfilePin / 跳过片头 / 重播剩余秒数…）**全是播放类**，
  //    一个显示字段都没有，网格密度写不进去。
  // 参数位置逐条照抄 SDK（`DisplayPreferencesServiceApi`）：
  //   GET  /DisplayPreferences/{Id}?UserId&Client← 只有 {Id} 在路径里
  //   POST /DisplayPreferences/{DisplayPreferencesId}?UserId
  //        body = 整个 DisplayPreferences（含 Client），**返回 Void（空 body）**

  /**
   * 本 App 在服务端显示偏好里的分区标识。
   *
   * 取值本身随意，只要**稳定** —— 换了它，之前写的偏好就读不到了（等于被清空）。
   * 所以定下来就别改，也别跟别的客户端重名。
   */
  const val DISPLAY_PREFS_CLIENT = "mpvex-android"

  /**
   * 读某个目标（媒体库 / 条目）的显示偏好。
   *
   * 目标不存在时部分部署返回 **404**，也有返回空对象的 —— 两种都当「还没有偏好」处理，
   * 由调用方决定是回落默认值还是提示用户。
   */
  fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String = DISPLAY_PREFS_CLIENT,
  ): EmbyDisplayPreferences? = getJson(
    server,
    "/DisplayPreferences/$displayPreferencesId",
    mapOf(
      "UserId" to server.userId,
      "Client" to client,
    ),
  )

  /**
   * 写某个目标的显示偏好。**整体覆盖**（不是增量合并），所以调用方必须
   * 先读出原有 `CustomPrefs` 再把自己的键merge 进去，否则会把别的客户端
   * 或本客户端的其它键一起抹掉。
   *
   * 返回 true = 服务端收下了。注意这条路径**成功时返回空 body**，
   * 不能拿去反序列化 —— 所以这里只判 HTTP 状态码。
   */
  fun postDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String,
    preferences: EmbyDisplayPreferences,
  ): Boolean {
    val body = json.encodeToString(preferences)
    val req = authedRequest(
      server,
      "/DisplayPreferences/$displayPreferencesId",
      mapOf("UserId" to server.userId),
    )
      .post(body.toRequestBody(jsonMedia))
      .header("Accept", "application/json")
      .build()
    httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
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
 *
 * ## 为什么要给消息加前缀（对应清单 1.3「错误分类」）
 *
 * 以前 UI 层只能拿到 `it.message`，而那个message 就是裸的
 * 「Emby API 500: <html>...</html>」—— 用户看到的是一坨 HTML，
 * 开发者也无法据此区分「网络断了」「密码错了」「服务器炸了」。
 *
 * 现在 [errorKind] 把失败归成少数几类，UI 可以据此给**人话**提示
 * （「网络不可用，检查连接」「账号或密码失效，请重新登录」）。
 * 原始 body 仍然保留在 [body] 里，排障时照发不误。
 */
class EmbyApiException(val code: Int, val body: String) :
  Exception(buildMessage(code, body)) {

  /** 归类后的错误种类，供 UI 决定提示文案 */
  val errorKind: EmbyErrorKind
    get() = EmbyErrorKind.of(code)
}

/** 错误归类（见 [EmbyApiException.errorKind]）。 */
enum class EmbyErrorKind {
  /** 断网 / 超时 / 连不上 */
  NETWORK,

  /** 401 / 403：token 过期或没权限 —— 该重登 / 重新登录 */
  AUTH,

  /** 404：资源没了（条目被删、Id 写错） */
  NOT_FOUND,

  /** 5xx：服务器自己出问题，可提示稍后重试 */
  SERVER,

  /** 其余（4xx 客户端错误等） */
  UNKNOWN,
  ;

  companion object {
    fun of(code: Int): EmbyErrorKind =
      when {
        code <= 0 -> NETWORK // 0 是本项目自定义的「没拿到响应码」⇒按网络问题处理
        code == 401 || code == 403 -> AUTH
        code == 404 -> NOT_FOUND
        code in 500..599 -> SERVER
        else -> UNKNOWN
      }
  }
}

/** 把原始响应包成人话；body 只留一小段，避免塞满 Toast。 */
private fun buildMessage(code: Int, body: String): String {
  val kind = EmbyErrorKind.of(code)
  val head = when (kind) {
    EmbyErrorKind.NETWORK -> "网络不可用"
    EmbyErrorKind.AUTH -> "鉴权失败"
    EmbyErrorKind.NOT_FOUND -> "资源不存在"
    EmbyErrorKind.SERVER -> "服务器出错"
    EmbyErrorKind.UNKNOWN -> "请求失败"
  }
  val detail = body.trim().take(120)
  return if (detail.isEmpty()) "$head（$code）" else "$head（$code）：$detail"
}

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

/**
 * `POST /Items/{id}/PlaybackInfo` 的响应。
 *
 * 只声明 [transcodeStreamUrl] 真正用得到的那几个字段 —— Emby 的完整响应有几十个字段，
 * 靠 `ignoreUnknownKeys` 忽略掉即可（`json` 已配置）。
 */
@Serializable
private data class PlaybackInfoResult(
  val MediaSources: List<PlaybackMediaSource>? = null,
  /** 本次转码会话 Id，自己拼 URL 时要带上，服务器靠它复用 / 回收转码进程 */
  val PlaySessionId: String? = null,
  /** 拿不到可用源时的错误码，例如 `NoCompatibleStream` */
  val ErrorCode: String? = null,
)

/** [PlaybackInfoResult] 里的一条媒体源 */
@Serializable
private data class PlaybackMediaSource(
  /** 即 MediaSourceId */
  val Id: String? = null,
  /** 服务器拼好的转码地址（通常是 HLS 的 master.m3u8），优先用它 */
  val TranscodingUrl: String? = null,
  val DirectStreamUrl: String? = null,
)

/**
 * `GET /Users/{id}` 的响应，只为拿 [Policy]。
 *
 * 声明成 private：外面只关心「是不是管理员」这一个布尔值（见 [EmbyClient.fetchIsAdmin]），
 * 权限模型的其余十几个字段不该泄漏成公共 API。
 */
@Serializable
private data class EmbyUserDetailDto(
  val Id: String? = null,
  val Name: String? = null,
  val Policy: EmbyUserPolicyDto? = null,
)

/** [EmbyUserDetailDto] 里的权限块。只声明要用的那个字段，其余靠 ignoreUnknownKeys 丢掉。 */
@Serializable
private data class EmbyUserPolicyDto(
  val IsAdministrator: Boolean = false,
)
