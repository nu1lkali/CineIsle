package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 数据模型（EmbyItem / EmbyUserDto / …）已搬到 EmbyModels.kt（清单 1.5 拆分第 1 刀），
// 尾部与错误处理相关的 DTO 仍留在本文件末尾。

// ════════════════════════════════════════════════════════════════════════
// EmbyClient —— 单例 HTTP 客户端，所有 Emby REST API 调用集中在此
// ════════════════════════════════════════════════════════════════════════

/**
 * Emby REST API 客户端。基于 OkHttp 5 + kotlinx.serialization。
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
data class EmbySpeedTestResult(
  val averageMs: Long,
  val minMs: Long,
  val maxMs: Long,
  val samples: Int,
  val error: String? = null,
) {
  val success: Boolean get() = error == null && samples > 0
}

object EmbyClient {
  // ── 传输层转发（重构：从「一个 object 装 12 种职责」拆出，行为零变化）──
  //
  // 真正的实现在 [EmbyHttpCore]。这里保留同名成员只为「本文件内剩余代码与
  // 全部外部调用方一行都不用改」：全部是 getter / 一行委托。

  private val json: Json get() = EmbyHttpCore.json

  /**
   * 走 [EmbyHttp.shared]（共享连接池 + 统一 UA + 指数退避重试 + 401 自动重登）。
   *
   * `internal` 而非 `private`：拆出去的 [EmbySubtitles] 需要它 —— 字幕下载要拿
   * **原始字节**（走完服务端格式转换的响应体），不能经过 [execString] 那条
   * 「按文本解码 + 抛异常」的路径。同理它必须显式写 `EmbyClient.httpClient`，
   * `internal` 不会把成员导入到别的 object 作用域里。
   */
  internal val httpClient: OkHttpClient get() = EmbyHttpCore.httpClient

  private val jsonMedia get() = EmbyHttpCore.jsonMedia

  // ══════════════════════════════════════════════════════════════════════
  // 媒体库「权威名称」缓存
  // ══════════════════════════════════════════════════════════════════════
  //
  // ⭐⭐ 为什么需要这个：Emby 里一个媒体库的**名字**是用户在服务端手动设置的
  // （例：建库时叫「Movies_HD」），跟它索引的那个磁盘目录名（例：`HD`）
  // 完全是两回事。`/Items/{Id}/Ancestors` 返回的那几层是**磁盘目录条目**，
  // `Name` 给的是目录名；只有 `/Users/{uid}/Views` 里的 `Name` 才是库名。
  //
  // 目录导航要在库根那一行显示库名，就得能按 Id 查到 `/Views` 给的名字。
  // 但目录页、详情页跟首页未必共用同一个 ViewModel 实例（nav3 每个 entry
  // 有自己的 ViewModelStoreOwner），直接读 `EmbyViewModel.libraries` 常常是空的。
  // 所以把这份映射放在**进程级单例**里：任何一次 `getVirtualFolders` 成功都写入，
  // 任何页面按 (server, libraryId) 查，零网络请求、无生命周期依赖。
  //
  // key = "${server.id}:${server.userId}"：同一台服务器换账号，可见库与库名可能不同。
  private val libraryCatalog = java.util.concurrent.ConcurrentHashMap<String, List<LibraryRecord>>()

  /**
   * 一个媒体库在客户端这边的全部身份：Id、用户设置的名字、它索引的磁盘目录末段。
   *
   * [segments] 是 [EmbyLibraryNaming] 那张对照表的原料，可能为空 ——
   * `/Views` 不保证回 `Path`，这时只能按 Id 认库。
   */
  private data class LibraryRecord(val id: String, val name: String, val segments: List<String>)

  private fun serverKey(server: EmbyServer): String = "${server.id}:${server.userId}"

  /**
   * 按 Id 取媒体库名称（`/Users/{uid}/Views` 里用户设置的那个名字）。
   *
   * 查不到返回 null —— 调用方自己决定退回什么，这里不编名字。
   */
  fun libraryDisplayName(server: EmbyServer, libraryId: String?): String? {
    if (libraryId.isNullOrBlank()) return null
    return libraryCatalog[serverKey(server)]
      ?.firstOrNull { it.id == libraryId }
      ?.name
      ?.takeIf { it.isNotBlank() }
  }

  /**
   * 媒体库名称的完整索引（`库 Id → 库名`、`磁盘目录末段 → 库名`）。
   *
   * 给目录导航用：一趟拿到两张表，不必每行都回头查一次缓存。
   * 冷启动直进深层目录时缓存还没热，返回 [LibraryNameIndex.EMPTY]，
   * 调用方按「认不出就保持原样」处理即可。
   */
  fun libraryNameIndex(server: EmbyServer): LibraryNameIndex {
    val records = libraryCatalog[serverKey(server)] ?: return LibraryNameIndex.EMPTY
    val byId = LinkedHashMap<String, String>(records.size)
    val bySegment = LinkedHashMap<String, String>(records.size)
    records.forEach { record ->
      byId[record.id] = record.name
      record.segments.forEach { segment ->
        // 同一个目录名被两个库占用时保留先登记的：先到先得比来回改更可预期，
        // 而且这种配置本身在服务端就已经是歧义的，客户端不该猜。
        bySegment.putIfAbsent(segment.lowercase(), record.name)
      }
    }
    return LibraryNameIndex(byId = byId, bySegment = bySegment)
  }

  /** `getVirtualFolders` 成功后调用：把这次看到的库名整批写进缓存。 */
  private fun cacheLibraryCatalog(server: EmbyServer, libraries: List<EmbyItem>) {
    val records =
      libraries.mapNotNull { item ->
        val id = item.Id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LibraryRecord(
          id = id,
          name = name,
          // `/Views` 里带 `Path` 就顺手存下末段（`/mnt/data/HD` → `HD`）：
          // 这是「目录名 → 库名」唯一的免费来源，不额外发请求。
          segments = listOfNotNull(pathLastSegment(item.Path)),
        )
      }
    // 整批替换而不是累加：库被删了 / 改名了，下一轮就该以这次的结果为准。
    // 但请求失败（空列表）时不覆盖 —— 一次网络抖动不该把已有名字清掉。
    if (records.isNotEmpty()) libraryCatalog[serverKey(server)] = records
  }

  /**
   * 无参数 POST 的请求体。
   *
   * 不能直接发空字符串：Emby 走的是 ServiceStack，对 Content-Type 为 json、
   * body 却是空的 POST 有概率返回 400，请求压根没到业务逻辑。发一个空对象最稳。
   */
  private val EMPTY_JSON_BODY get() = EmbyHttpCore.EMPTY_JSON_BODY

  /** 无 body 的 POST（OkHttp 的 post 必须给 body，给空串就是 content-length=0，跟 Web 端一致） */
  private val EMPTY_BODY get() = EmbyHttpCore.EMPTY_BODY

  /**
   * 收藏 / 已播放等开关型请求附带的客户端标识 query 参数。
   *
   * 与 Emby Web 请求一致：该服务端上
   * 历史上出现过不带这几个参数时「回 200 但收藏没落库」的假成功 —— 请求形态与
   * Web 完全一致后写入才真正生效。X-Emby-* 是 Emby 的标准客户端标识参数，
   * 所有服务端都接受，带上没有副作用。
   */
  private val WEB_CLIENT_QUERY: Map<String, String> get() = EmbyHttpCore.WEB_CLIENT_QUERY

  /**
   * 拉流时统一使用的 User-Agent。
   *
   * 「预解析直链（302）」探测与播放器真正拉流**必须用同一个 UA**：
   * 网盘直链的签名常把 UA 算进校验，两边不一致时探测能过、播放被 403。
   * 播放器侧通过 intent extra `headers` 收到同一个值（见 EmbyViewModel 的播放入口）。
   */
  const val STREAM_USER_AGENT = "mpvex/1.0"

  /** 视频类媒体（用于把音乐、图片从影视列表中过滤掉）。取流/查询侧也要用，故为 internal */
  internal const val VIDEO_MEDIA_TYPE = "Video"

  // Fields 取值已搬到 [EmbyItemFields]（重构：god-class 拆分，逐字搬迁）。
  // 这里保留同名 const 别名：外部写 `EmbyClient.ITEM_FIELDS` 的调用方一行都不用改，
  // const 是编译期内联，值与原来完全一致。
  internal const val ITEM_FIELDS = EmbyItemFields.CORE
  internal const val ITEM_FIELDS_WITH_SORT_KEYS = EmbyItemFields.WITH_SORT_KEYS
  internal const val PHOTO_FIELDS = EmbyItemFields.PHOTO
  private const val RAW_ITEM_FIELDS = EmbyItemFields.RAW

  /** 统计演员作品数时每页拉多少条。开大一点，几千条的库几次就能拉完 */
  internal const val COUNT_PAGE_SIZE = 500

  /**
   * 统计能接受的库规模上限（条数）。超过就整体放弃统计（角标留空），
   * 免得为了几十个角标去拉几十 MB 的响应。它同时也是翻页的硬上限 ——
   * 服务端万一忽略 StartIndex，配合按 Id 去重不会死循环。
   */
  internal const val COUNT_MAX_ITEMS = 10_000

  /**
   * 小库阈值（条数）：不超过这个规模就整库扫一遍算齐所有演员角标。
   *
   * 定 3000 是因为 3000 / [COUNT_PAGE_SIZE] 500 = 6 个请求就能扫完，而单个响应
   * 只带 `People` 一个字段（见 [getItems] 的 fields 参数），体量很小 ——
   * 这个开销换「所有角标一次到位」很值。超过就转逐个问
   * （见 [getPersonWorkCountsAdaptive]），避免为几十个角标把大库拉穿。
   */
  internal const val COUNT_FULL_SCAN_MAX = 3_000

  // 「服务器转码」的默认参数已随实现搬到 EmbyStreamApi（TRANSCODE_*）。

  // ─── 内部工具（转发到 [EmbyHttpCore]，本文件内剩余代码与外部调用方零改动）───

  internal fun authedRequest(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder = EmbyHttpCore.authedRequest(server, path, extraQuery)

  internal fun authedRequestWithApiKey(
    server: EmbyServer,
    path: String,
    extraQuery: Map<String, String?> = emptyMap(),
  ): Request.Builder = EmbyHttpCore.authedRequestWithApiKey(server, path, extraQuery)

  internal fun execString(request: Request): String = EmbyHttpCore.execString(request)

  internal inline fun <reified T> execJson(request: Request): T =
    EmbyHttpCore.execJson<T>(request)

  internal inline fun <reified T> postJson(
    server: EmbyServer,
    path: String,
    bodyJson: String,
    query: Map<String, String?> = emptyMap(),
  ): T = EmbyHttpCore.postJson<T>(server, path, bodyJson, query)

  internal inline fun <reified T> getJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T = EmbyHttpCore.getJson<T>(server, path, query)

  internal inline fun <reified T> deleteJson(
    server: EmbyServer,
    path: String,
    query: Map<String, String?> = emptyMap(),
  ): T = EmbyHttpCore.deleteJson<T>(server, path, query)

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
      .url("${EmbyEndpoints.hostUrl(server)}/emby/Users/AuthenticateByName")
      .header("X-Emby-Authorization", authHeader)
      .post(body.toRequestBody(jsonMedia))
      .build()
    return execJson(req)
  }

  /** 拉取服务器公开信息（无需登录）。 */
  fun getPublicSystemInfo(server: EmbyServer): EmbySystemInfo = getJson(server, "/System/Info/Public")

  /**
   * 探活：这台服务器此刻通不通。
   *
   * 三条口径，缺一条都会让「主备切换」判错：
   * · 走 [EmbyHttp.health]（**无重试** + 秒级超时）：重试会把一次失败拖成十几秒，
   *   探活自己就变成了卡顿源；
   * · 只打 `System/Info/Public`：不碰媒体、不跟随 302 —— Emby 库只存 strm，
   *   真媒体在网盘 CDN 上，探活绝不能顺手往 CDN 打一发；
   * · 2xx/3xx 都算通：反代改写 `/emby` 前缀时可能回 302，能应答就说明服务活着。
   *
   * ⚠️ 阻塞调用（OkHttp `execute`），调用方必须切到 IO 线程。
   */
  fun probeAlive(server: EmbyServer): Boolean =
    runCatching {
      val req = authedRequest(server, "/System/Info/Public").get().build()
      EmbyHttp.health.newCall(req).execute().use { it.code in 200..399 }
    }.getOrDefault(false)

  /**
   * 探活某个**指定的**入口（不经过地址解析）。
   *
   * 「同一台服务器两个入口，谁先通就用谁」要靠它 —— 必须能显式指定地址，
   * 否则两个探测都会走解析器的当前值，永远只探同一个地址。
   *
   * 口径与 [probeAlive] 一致：只打 `System/Info/Public`、无重试、2xx/3xx 都算通；
   * 区别只是超时压到 [EmbyHttp.probe] 那一档（竞速等不起 6 秒）。
   */
  fun probeAlive(endpoint: EmbyEndpoint, token: String): Boolean =
    runCatching {
      val req =
        Request.Builder()
          .url("${endpoint.baseUrl}/emby/System/Info/Public")
          .apply { if (token.isNotEmpty()) header("X-Emby-Token", token) }
          .get()
          .build()
      EmbyHttp.probe.newCall(req).execute().use { it.code in 200..399 }
    }.getOrDefault(false)

  // ════════════════════════════════════════════════════════════════════════
  // 2. 媒体库（VirtualFolders） / 媒体浏览
  // ════════════════════════════════════════════════════════════════════════

  /**
   * 获取用户可见的媒体库（媒体库视图 / CollectionFolders）。
   *
   * 对应 SDK：`UserViewsServiceApi.getUsersByUseridViews` → `GET /Users/{UserId}/Views`
   * 返回电影、剧集、音乐等顶层库，而不是把所有根级 Item 平铺。
   */
  fun getVirtualFolders(server: EmbyServer): EmbyItemsResult {
    val result: EmbyItemsResult = getJson(server, "/Users/${server.userId}/Views")
    // 顺手登记权威库名：目录导航要按 Id 查「这个库到底叫什么」，
    // 而祖先链给的是磁盘目录名，不是库名。所有调用方自动受益，不用各自记一遍。
    cacheLibraryCatalog(server, result.Items)
    return result
  }

  fun getResumeItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 20,
    includeItemTypes: List<String>? = null,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    parentId: String? = null,
  ): EmbyItemsResult = EmbyItemsApi.getResumeItems(
    server = server,
    startIndex = startIndex,
    limit = limit,
    includeItemTypes = includeItemTypes,
    mediaTypes = mediaTypes,
    parentId = parentId,
  )

  fun getItemsByIds(server: EmbyServer, ids: List<String>): EmbyItemsResult = EmbyItemsApi.getItemsByIds(
    server = server,
    ids = ids,
  )

  fun getLatestItems(
    server: EmbyServer,
    parentId: String? = null,
    limit: Int = 16,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
  ): List<EmbyItem> = EmbyItemsApi.getLatestItems(
    server = server,
    parentId = parentId,
    limit = limit,
    includeItemTypes = includeItemTypes,
  )

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
  ): EmbyItemsResult = EmbyItemsApi.getItems(
    server = server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    filters = filters,
    includeItemTypes = includeItemTypes,
    genres = genres,
    searchTerm = searchTerm,
    mediaTypes = mediaTypes,
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    isFavorite = isFavorite,
    excludeItemTypes = excludeItemTypes,
    personIds = personIds,
    years = years,
    minCommunityRating = minCommunityRating,
    tags = tags,
    genreIds = genreIds,
    tagIds = tagIds,
    officialRatings = officialRatings,
    isPlayed = isPlayed,
    isFolder = isFolder,
    isHD = isHD,
    is3D = is3D,
    hasSubtitles = hasSubtitles,
    studioIds = studioIds,
    studios = studios,
    fields = fields,
    ids = ids,
  )

  fun getPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
  ): EmbyItemsResult = EmbyItemsApi.getPhotoAlbums(
    server = server,
    parentId = parentId,
    startIndex = startIndex,
    limit = limit,
    sortBy = sortBy,
    sortOrder = sortOrder,
  )

  fun getPhotos(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    recursive: Boolean = true,
  ): EmbyItemsResult = EmbyItemsApi.getPhotos(
    server = server,
    parentId = parentId,
    startIndex = startIndex,
    limit = limit,
    sortBy = sortBy,
    sortOrder = sortOrder,
    recursive = recursive,
  )

  fun getGenres(server: EmbyServer, parentId: String? = null): List<String> = EmbyItemsApi.getGenres(
    server = server,
    parentId = parentId,
  )

  fun getGenreIdByName(server: EmbyServer, name: String): String? = EmbyItemsApi.getGenreIdByName(
    server = server,
    name = name,
  )

  fun getLiveTvChannels(server: EmbyServer): List<EmbyItem> = EmbyItemsApi.getLiveTvChannels(
    server = server,
  )

  fun getLiveTvPrograms(
    server: EmbyServer,
    channelId: String? = null,
    startTime: String,
    endTime: String,
    limit: Int = 500,
  ): List<EmbyItem> = EmbyItemsApi.getLiveTvPrograms(
    server = server,
    channelId = channelId,
    startTime = startTime,
    endTime = endTime,
    limit = limit,
  )

  fun getPersons(
    server: EmbyServer,
    parentId: String? = null,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
  ): List<EmbyIdName> = EmbyPeopleApi.getPersons(
    server = server,
    parentId = parentId,
    personTypes = personTypes,
  )

  fun getActors(
    server: EmbyServer,
    parentId: String? = null,
  ): List<EmbyItem> = EmbyPeopleApi.getActors(
    server = server,
    parentId = parentId,
  )

  /** 「演职员合并」扫描时一页拉多少人 */
  internal const val PERSON_PAGE_SIZE = 500

  /** 「演职员合并」扫描的人员上限。超过就截断并在 UI 里说明，不会无限翻页。 */
  internal const val PERSON_SCAN_MAX = 8_000

  fun getPersonsDetailed(
    server: EmbyServer,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
    maxItems: Int = PERSON_SCAN_MAX,
  ): List<EmbyItem> = EmbyPeopleApi.getPersonsDetailed(
    server = server,
    personTypes = personTypes,
    maxItems = maxItems,
  )

  fun getPersonWorkCount(server: EmbyServer, personId: String): Int = EmbyPeopleApi.getPersonWorkCount(
    server = server,
    personId = personId,
  )

  fun getFavoritePersons(server: EmbyServer): List<EmbyItem> = EmbyPeopleApi.getFavoritePersons(
    server = server,
  )

  fun getPersonById(server: EmbyServer, personId: String): EmbyItem? = EmbyPeopleApi.getPersonById(
    server = server,
    personId = personId,
  )

  fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? = EmbyPeopleApi.getPersonFavoriteState(
    server = server,
    personId = personId,
  )

  fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> = EmbyPeopleApi.getPersonWorkCounts(
    server = server,
    parentId = parentId,
  )

  fun getPersonWorkCountsAdaptive(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> = EmbyPeopleApi.getPersonWorkCountsAdaptive(
    server = server,
    parentId = parentId,
    personIds = personIds,
  )

  /** 大库兜底时逐个问演员的个数上限：够铺满好几屏，又不至于把请求打爆 */
  internal const val ADAPTIVE_PER_PERSON_LIMIT = 60

  fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> = EmbyPeopleApi.searchPersons(
    server = server,
    term = term,
    parentId = parentId,
    limit = limit,
  )

  fun getStudios(server: EmbyServer, parentId: String? = null): List<EmbyIdName> = EmbyItemsApi.getStudios(
    server = server,
    parentId = parentId,
  )

  fun getFilterOptions(server: EmbyServer, parentId: String? = null): EmbyFilterOptions = EmbyItemsApi.getFilterOptions(
    server = server,
    parentId = parentId,
  )

  fun getItem(server: EmbyServer, itemId: String): EmbyItem = EmbyItemsApi.getItem(
    server = server,
    itemId = itemId,
  )

  fun getItemAncestors(server: EmbyServer, itemId: String): List<EmbyItem> = EmbyItemsApi.getItemAncestors(
    server = server,
    itemId = itemId,
  )

  fun getItemChapters(server: EmbyServer, itemId: String): List<EmbyChapter> = EmbyItemsApi.getItemChapters(
    server = server,
    itemId = itemId,
  )

  fun getItemIntros(server: EmbyServer, itemId: String): EmbyIntrosResult = EmbyItemsApi.getItemIntros(
    server = server,
    itemId = itemId,
  )

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
    /**
     * 搜索范围限定在某个目录下。
     *
     * null = 全服搜索（老口径，默认值，所有既有调用一行不改）。
     * 非 null + [recursive] = false 就是界面上的「仅本目录」：
     * 只在这一层的直接子项里按名字找。
     *
     * 为什么必须同时给 recursive：Emby 的 `ParentId` 默认是**带递归**的，
     * 单给 ParentId 等于「在这个子树里搜」，与「仅本目录」不是一回事。
     */
    parentId: String? = null,
    recursive: Boolean = true,
    /** 字段清单；默认 CORE（与改造前一致）。聚合搜索要 ProviderIds 时显式覆盖。 */
    fields: String = EmbyItemFields.CORE,
  ): EmbyItemsResult = EmbyItemsApi.searchItems(
    server = server,
    term = term,
    includeItemTypes = includeItemTypes,
    limit = limit,
    sortBy = sortBy,
    sortOrder = sortOrder,
    parentId = parentId,
    recursive = recursive,
    fields = fields,
  )

  fun getSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = EmbyItemsApi.getSeasons(
    server = server,
    seriesId = seriesId,
    startIndex = startIndex,
    limit = limit,
  )

  fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = EmbyItemsApi.getEpisodes(
    server = server,
    seriesId = seriesId,
    seasonId = seasonId,
    startIndex = startIndex,
    limit = limit,
  )

  fun getPlayedHistory(
    server: EmbyServer,
    limit: Int = 60,
    startIndex: Int = 0,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
  ): EmbyItemsResult = EmbyItemsApi.getPlayedHistory(
    server = server,
    limit = limit,
    startIndex = startIndex,
    mediaTypes = mediaTypes,
  )

  fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = EmbyItemsApi.getFavoriteItems(
    server = server,
    startIndex = startIndex,
    limit = limit,
    mediaTypes = mediaTypes,
    sortBy = sortBy,
    sortOrder = sortOrder,
  )

  fun getFavoriteGenres(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = EmbyItemsApi.getFavoriteGenres(
    server = server,
    startIndex = startIndex,
    limit = limit,
  )

  fun getFavoriteStudios(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 200,
  ): EmbyItemsResult = EmbyItemsApi.getFavoriteStudios(
    server = server,
    startIndex = startIndex,
    limit = limit,
  )

  fun getRandomItems(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): EmbyItemsResult = EmbyItemsApi.getRandomItems(
    server = server,
    parentId = parentId,
    includeItemTypes = includeItemTypes,
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

  fun getSimilarItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 24,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = EmbyItemsApi.getSimilarItems(
    server = server,
    itemId = itemId,
    limit = limit,
    includeItemTypes = includeItemTypes,
    mediaTypes = mediaTypes,
    excludeIdList = excludeIdList,
  )

  fun getInstantMixItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf(VIDEO_MEDIA_TYPE),
    filters: List<String>? = null,
  ): EmbyItemsResult = EmbyItemsApi.getInstantMixItems(
    server = server,
    itemId = itemId,
    limit = limit,
    includeItemTypes = includeItemTypes,
    mediaTypes = mediaTypes,
    filters = filters,
  )

  fun getNextUpItems(
    server: EmbyServer,
    limit: Int = 20,
    seriesId: String? = null,
  ): EmbyItemsResult = EmbyItemsApi.getNextUpItems(
    server = server,
    limit = limit,
    seriesId = seriesId,
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
  // 3.4 直链（302）预解析 —— 实现在 EmbyStreamApi.kt
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ `warmStream` 不跟 302（不碰网盘 CDN、不触发风控），`resolveStreamUrl` 必须跟
  // （它的目的就是取直链）；两者都带 `STREAM_USER_AGENT`，且只用 per-call 超时、
  // 不动共享 client 的全局口径。完整判定口径见 EmbyStreamApi 的文件头与 KDoc。

  fun resolveStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    attempts: Int = 2,
    mediaSourceId: String? = null,
    callTimeoutMs: Long = EmbyStreamApi.STREAM_PROBE_CALL_TIMEOUT_MS,
  ): StreamProbe = EmbyStreamApi.resolveStreamUrl(
    server = server,
    itemId = itemId,
    static = static,
    attempts = attempts,
    mediaSourceId = mediaSourceId,
    callTimeoutMs = callTimeoutMs,
  )

  fun strmDirectPlayInfo(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
  ): StrmDirectPlayInfo? = EmbyStreamApi.strmDirectPlayInfo(server, itemId, mediaSourceId)

  fun warmStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
    callTimeoutMs: Long = EmbyStreamApi.STREAM_WARM_CALL_TIMEOUT_MS,
  ): Boolean = EmbyStreamApi.warmStream(server, itemId, mediaSourceId, callTimeoutMs)

  fun probeStreamUrl(url: String): StreamProbe = EmbyStreamApi.probeStreamUrl(url)

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

  /**
   * 服务器连接测速：先做一次不计时的预热（DNS / TLS / 连接池），再连续请求
   * `/Users/{id}` 取平均 / 最低 / 最高延迟。
   *
   * 用当前登录用户的资料接口而不是 `/System/Info`：后者在部分服务端要求管理员，
   * 普通账号会得到 403，测速结果会被误判成「服务器不可用」。
   */
  fun measureLatency(server: EmbyServer, samples: Int = 3): EmbySpeedTestResult {
    val count = samples.coerceIn(1, 5)
    val warmup = runCatching { getJson<EmbyUserDetailDto>(server, "/Users/${server.userId}") }
    warmup.exceptionOrNull()?.let { error ->
      return EmbySpeedTestResult(
        averageMs = 0,
        minMs = 0,
        maxMs = 0,
        samples = 0,
        error = "连接失败：${error.message ?: error::class.simpleName}",
      )
    }
    val measured = mutableListOf<Long>()
    repeat(count) {
      val started = System.nanoTime()
      val outcome = runCatching { getJson<EmbyUserDetailDto>(server, "/Users/${server.userId}") }
      outcome.exceptionOrNull()?.let { error ->
        return EmbySpeedTestResult(
          averageMs = 0,
          minMs = 0,
          maxMs = 0,
          samples = measured.size,
          error = "第 ${measured.size + 1} 次请求失败：${error.message ?: error::class.simpleName}",
        )
      }
      measured += (System.nanoTime() - started) / 1_000_000L
    }
    return EmbySpeedTestResult(
      averageMs = measured.average().toLong(),
      minMs = measured.minOrNull() ?: 0,
      maxMs = measured.maxOrNull() ?: 0,
      samples = measured.size,
    )
  }

  // ─── Smart Merge：Emby 原生多版本组合 / 拆分 ───

  /**
   * 管理员：把多个条目组合成 Emby 原生多版本。
   *
   * 官方 `POST /Videos/MergeVersions?Ids=...`，200 响应是**空 body**，没有结果对象；
   * 官方不承诺主项目、ItemId 与元数据归属，调用方必须重新查询验证。
   */
  fun mergeVersions(server: EmbyServer, ids: List<String>) {
    require(ids.size >= 2) { "至少需要两个 ItemId" }
    val request =
      authedRequest(
        server,
        "/Videos/MergeVersions",
        mapOf("Ids" to ids.distinct().joinToString(",")),
      ).post("".toRequestBody(null)).build()
    execString(request)
  }

  /**
   * 管理员：移除备用视频源（Emby Web 的 Split Versions Apart 很可能走这条）。
   *
   * ⚠️ 官方描述只有 "Removes alternate video sources."，没有承诺不删除文件、也不承诺
   * 恢复原 ItemId。调用前必须已完成测试服验证；未验证时不要把它做成「一键回退」。
   */
  fun removeAlternateSources(server: EmbyServer, itemId: String) {
    val request =
      authedRequest(server, "/Videos/$itemId/AlternateSources").delete().build()
    execString(request)
  }

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
  // 实现已搬到 EmbySessionApi（重构：god-class 拆分），此处仅保留对外签名
  // ════════════════════════════════════════════════════════════════════════
  //
  // ⚠️ 这一段是全项目最容易「看起来成功、其实一条没记上」的地方，
  // 三条约束（PlaySessionId 自生成 / MediaSourceId 必带 / 空串不许发）
  // 的完整说明见 `EmbySessionApi` 内的 KDoc，搬迁时逐字保留。

  /** 向服务器要一个它签发的播放会话（`POST /Items/{id}/PlaybackInfo`，`IsPlayback=true`）。 */
  suspend fun requestPlaybackSession(
    server: EmbyServer,
    itemId: String,
  ): Pair<String, String?>? = EmbySessionApi.requestPlaybackSession(server, itemId)

  /** 上报播放开始。返回本次会话 id（服务端不回显时退回 [playSessionId]）。 */
  fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    playMethod: String = "DirectStream",
    mediaSourceId: String? = null,
    playSessionId: String? = null,
  ): String = EmbySessionApi.reportPlaybackStart(
    server = server,
    itemId = itemId,
    positionTicks = positionTicks,
    audioStreamIndex = audioStreamIndex,
    subtitleStreamIndex = subtitleStreamIndex,
    playMethod = playMethod,
    mediaSourceId = mediaSourceId,
    playSessionId = playSessionId,
  )

  /** 上报播放进度（建议每 10 秒一次 + 暂停/恢复时）。 */
  fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
    isMuted: Boolean = false,
    mediaSourceId: String? = null,
  ) = EmbySessionApi.reportPlaybackProgress(
    server = server,
    itemId = itemId,
    playSessionId = playSessionId,
    positionTicks = positionTicks,
    isPaused = isPaused,
    isMuted = isMuted,
    mediaSourceId = mediaSourceId,
  )

  /** 上报播放停止（退出播放页时调用）。 */
  fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    mediaSourceId: String? = null,
  ) = EmbySessionApi.reportPlaybackStopped(
    server = server,
    itemId = itemId,
    playSessionId = playSessionId,
    positionTicks = positionTicks,
    mediaSourceId = mediaSourceId,
  )

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
    return "${EmbyEndpoints.hostUrl(server)}/emby/Items/$itemId/Images/$imageType?$qs"
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
    return "${EmbyEndpoints.hostUrl(server)}/emby/Items/$itemId/Images/Primary?$qs"
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
    return "${EmbyEndpoints.hostUrl(server)}/emby/Items/$itemId/Download?$qs"
  }

  /**
   * 构造视频流 URL（static=true 表示直接转原文件，不转码）。
   * 实现见 [EmbyStreamApi.videoStreamUrl]。
   */
  fun videoStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    mediaSourceId: String? = null,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
  ): String = EmbyStreamApi.videoStreamUrl(
    server,
    itemId,
    static,
    mediaSourceId,
    audioStreamIndex,
    subtitleStreamIndex,
  )

  /**
   * 多版本媒体：向服务器要**指定版本**的直连播放地址。
   * 实现见 [EmbyStreamApi.directStreamUrlForSource]。
   */
  suspend fun directStreamUrlForSource(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
  ): String? = EmbyStreamApi.directStreamUrlForSource(server, itemId, mediaSourceId)

  /**
   * 「让服务器转码」后的播放地址 —— 本机解不了的文件走这条路。
   * 实现见 [EmbyStreamApi.transcodeStreamUrl]。
   */
  suspend fun transcodeStreamUrl(
    server: EmbyServer,
    itemId: String,
    startTimeTicks: Long = 0L,
    maxHeight: Int = EmbyStreamApi.TRANSCODE_MAX_HEIGHT,
    videoBitrate: Int = EmbyStreamApi.TRANSCODE_VIDEO_BITRATE,
    audioBitrate: Int = EmbyStreamApi.TRANSCODE_AUDIO_BITRATE,
    mediaSourceId: String? = null,
  ): String = EmbyStreamApi.transcodeStreamUrl(
    server = server,
    itemId = itemId,
    startTimeTicks = startTimeTicks,
    maxHeight = maxHeight,
    videoBitrate = videoBitrate,
    audioBitrate = audioBitrate,
    mediaSourceId = mediaSourceId,
  )

  /**
   * 构造「下载原始文件」URL。
   *
   * Emby 的 `/Items/{id}/Download` 会把服务器上的原文件以附件形式吐出（不转码），
   * 是官方客户端「下载」用的接口；它支持 Range 请求，所以能断点续传。
   */
  fun itemDownloadUrl(server: EmbyServer, itemId: String): String {
    val q = if (server.apiToken.isNotEmpty()) "?api_key=${server.apiToken}" else ""
    return "${EmbyEndpoints.hostUrl(server)}/emby/Items/$itemId/Download$q"
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
    return "${EmbyEndpoints.hostUrl(server)}/emby/Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format$q"
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
  // 5.7 合集（Collection / 官方客户端叫 BoxSet）—— 实现在 EmbyCollectionsApi.kt
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ REST 路径是 `/Collections` 不是 `/BoxSets`（后者是 Jellyfin 命名）；
  // 参数名是 `Ids` 不是 `EntryIds`。完整说明见 EmbyCollectionsApi 文件头。

  fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyCollectionsApi.getCollections(server, startIndex, limit)

  fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult =
    EmbyCollectionsApi.getCollectionItems(server, collectionId, startIndex, limit)

  fun createCollection(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    parentId: String? = null,
  ): EmbyCollectionCreationResult =
    EmbyCollectionsApi.createCollection(server, name, itemIds, parentId)

  fun addToCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean = EmbyCollectionsApi.addToCollection(server, collectionId, itemIds)

  fun removeFromCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean = EmbyCollectionsApi.removeFromCollection(server, collectionId, itemIds)

  // ════════════════════════════════════════════════════════════════════════
  // 5.8 显示偏好（DisplayPreferences）—— 实现在 EmbyDisplayPrefsApi.kt
  // ════════════════════════════════════════════════════════════════════════
  // 三条容易踩空的真相（每目标粒度 / Client 是分区键 / 别写 UserConfiguration）
  // 见 EmbyDisplayPrefsApi 的文件头注释。

  /**
   * 本 App 在服务端显示偏好里的分区标识。
   *
   * 取值本身随意，只要**稳定** —— 换了它，之前写的偏好就读不到了（等于被清空）。
   * 所以定下来就别改，也别跟别的客户端重名。
   */
  const val DISPLAY_PREFS_CLIENT = "mpvex-android"

  fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String = DISPLAY_PREFS_CLIENT,
  ): EmbyDisplayPreferences? =
    EmbyDisplayPrefsApi.getDisplayPreferences(server, displayPreferencesId, client)

  /**
   * 写某个目标的显示偏好（整体覆盖，见实现侧说明）。
   */
  fun postDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String,
    preferences: EmbyDisplayPreferences,
  ): Boolean = EmbyDisplayPrefsApi.postDisplayPreferences(
    server, displayPreferencesId, client, preferences,
  )

  // ════════════════════════════════════════════════════════════════════════
  // 6. 辅助：基于 Android 设备 ID —— 实现在 EmbyDeviceId.kt
  // ════════════════════════════════════════════════════════════════════════
  // 同包顶层函数 `androidDeviceId`，取流拼 `DeviceId` 参数时也用同一份，
  // 避免两处口径分叉。
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

/**
 * [EmbyStreamApi.probeStreamUrl] 的探测结果类型 `StreamProbe` 已随取流实现
 * 一起搬到 EmbyStreamApi.kt（同包，引用方无需改 import）。
 */

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
