package app.marlboroadvance.mpvex.domain.emby


// ════════════════════════════════════════════════════════════════════════
// 条目查询：列表浏览 / 搜索 / 分页 / 相似推荐
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// 这一段只做「问服务器要条目」+ 把结果映射成 EmbyItem，不涉及状态写入。
// 收藏 / 已看 / 删除那些会改服务端状态的接口仍在 EmbyUserStateApi。
//
// ⚠️ 三条从真机换来的约束（逐字保留）：
//  · 排序要在客户端做的列表必须用 `EmbyItemFields.WITH_SORT_KEYS`（不带就是全 null，排序等于没排）；
//  · 照片列表只索要 OpenAPI 允许的 Fields 名，不认识的名字有概率 400；
//  · `getItems` 的 `parentId` / `ids` 两个分支拼的 query 不同，别「统一」掉。

object EmbyItemsApi {
  /**
   * 继续观看。
   *
   * ## ⚠️ 不要再按类型过滤（v229）
   *
   * 原来默认传 `IncludeItemTypes=Movie,Series`，理由写着「单集属于剧集内部层级，放在
   * 继续观看里会显得零散」。后果是从**详情页点某一集**播放之后，那一集的类型是
   * `Episode` —— 服务端直接把它从 Resume 结果里滤掉：详情页明明有进度、首页那一行却
   * 始终没有它，用户侧就是「有些上报了但继续观看里看不到」。
   *
   * 现在按 [Yamby v2.1.0.11] 的做法：**不传 `IncludeItemTypes`**，只用
   * `MediaTypes=Video` + `Recursive=true` 圈定范围，让服务端按自己的 Resume 语义决定
   * 返回哪一条（电影返回电影、剧集返回那一集）。Emby 官方客户端也是这个口径。
   *
   * 需要收窄时再显式传 [includeItemTypes]（目前没有调用方这么做）。
   */
  fun getResumeItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 20,
    includeItemTypes: List<String>? = null,
    mediaTypes: List<String>? = listOf(EmbyClient.VIDEO_MEDIA_TYPE),
    parentId: String? = null,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.userResume(server.userId),
    mapOf(
      "startIndex" to startIndex.toString(),
      "limit" to limit.toString(),
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "MediaTypes" to mediaTypes?.joinToString(","),
      // ⚠️ `Recursive` 必须显式给：Resume 的默认递归行为在个别兼容服务端上不一致，
      //    显式 `true` 与官方客户端一致（Yamby 也是写死 true）。
      "Recursive" to "true",
      "ParentId" to parentId,
      "Fields" to EmbyItemFields.CORE,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
    ),
  )

  /**
   * 按 Id 批量取条目：`GET /Users/{uid}/Items?Ids=a,b,c`。
   *
   * 用途只有一个 —— 首页「继续观看」把**本地最近播放、但不在服务端那批结果里**的条目
   * 补回来。为什么需要补：服务端确实按 `LastPlayedDate` 降序排，但**只有该次播放真的
   * 刷新了时间戳，条目才会被顶到前面**；我们的上报过去只写了位置没写时间，条目就卡在
   * 旧位次（实测有一条卡在第 21 位，首页只要 20 条）。详见 [EmbyRecentPlays] 的 KDoc。
   *
   * ⚠️ 必须**一次请求取全部**，绝不逐条 `GET /Items/{id}`：账本最多补十几条，
   * 逐条就是十几个串行往返（`Limit` 显式给 `ids.size`，避免服务端按默认页大小截断）。
   */
  fun getItemsByIds(server: EmbyServer, ids: List<String>): EmbyItemsResult {
    if (ids.isEmpty()) return EmbyItemsResult()
    return EmbyHttpCore.getJson(
      server,
      EmbyPaths.userItems(server.userId),
      mapOf(
        "Ids" to ids.joinToString(","),
        "Recursive" to "true",
        "Limit" to ids.size.toString(),
        "Fields" to EmbyItemFields.CORE,
        "EnableImages" to "true",
        "EnableUserData" to "true",
        "ImageTypeLimit" to "1",
      ),
    )
  }

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
  ): List<EmbyItem> = EmbyHttpCore.getJson<List<EmbyItem>>(
    server,
    EmbyPaths.userLatest(server.userId),
    mapOf(
      "limit" to limit.toString(),
      "ParentId" to parentId,
      "IncludeItemTypes" to includeItemTypes?.joinToString(","),
      "Fields" to EmbyItemFields.CORE,
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
     * 覆盖默认的 [EmbyItemFields.CORE]。
     *
     * 统计类请求（如数每个演员有多少部作品）只需要 `People`，用这个参数把
     * `MediaSources`、`Overview` 这些大字段去掉 —— 一页 500 条时响应体能小掉一大半。
     */
    fields: String = EmbyItemFields.CORE,
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
    return EmbyHttpCore.getJson(server, EmbyPaths.userItems(server.userId), q)
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
    fields = EmbyItemFields.PHOTO,
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
    fields = EmbyItemFields.PHOTO,
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
  internal fun nameList(server: EmbyServer, path: String, parentId: String?): List<String> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      EmbyHttpCore.getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { it.Name }
        .filter { it.isNotBlank() }
    }.onFailure {
      EmbyLog.warn("nameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
    }.getOrDefault(emptyList())

  /** 某媒体库下出现过的全部类型名 */
  fun getGenres(server: EmbyServer, parentId: String? = null): List<String> =
    nameList(server, EmbyPaths.GENRES, parentId)

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
    idNameList(server, EmbyPaths.GENRES, parentId = null)
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
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.LIVE_TV_CHANNELS, q).Items
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
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.LIVE_TV_PROGRAMS, q).Items
    }.onFailure {
      EmbyLog.warn("getLiveTvPrograms 失败：${it.message} —— 该服务器可能没有节目单数据", it)
    }.getOrDefault(emptyList())

  /**
   * 取「Id + 名字」的聚合端点（/Persons、/Studios）。
   *
   * 和 [nameList] 的区别是这里要留下 Id —— 演员同名很常见，只有 Id 能唯一确定一个人。
   */
  internal fun idNameList(
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
      EmbyHttpCore.getJson<EmbyItemsResult>(server, path, q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          EmbyIdName(id, name)
        }
    }.onFailure {
      EmbyLog.warn("idNameList($path) 失败：${it.message} —— 筛选面板会少这一组选项", it)
    }.getOrDefault(emptyList())

  /** 某媒体库里出现过的工作室（出品方） */
  fun getStudios(server: EmbyServer, parentId: String? = null): List<EmbyIdName> =
    idNameList(server, EmbyPaths.STUDIOS, parentId)

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
    val genreItems = idNameList(server, EmbyPaths.GENRES, parentId)
    val tagItems = idNameList(server, EmbyPaths.TAGS, parentId)
    return EmbyFilterOptions(
      genres = genreItems.map { it.name },
      tags = tagItems.map { it.name },
      genreItems = genreItems,
      tagItems = tagItems,
      years = nameList(server, EmbyPaths.YEARS, parentId)
        .mapNotNull { it.toIntOrNull() }
        .sortedDescending()
        .takeIf { it.isNotEmpty() } ?: fallbackYears(),
      officialRatings = nameList(server, EmbyPaths.OFFICIAL_RATINGS, parentId),
      persons = EmbyClient.getPersons(server, parentId),
      studios = getStudios(server, parentId),
    )
  }

  /** /Years 端点不可用时兜底：给一份从今年往前推的年份表，保证年份筛选不会空着 */
  internal fun fallbackYears(): List<Int> {
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
   * 而服务端对不认识的字段名有可能直接 400（见 [EmbyItemFields.PHOTO] 的同一条告诫）。
   * 好消息是它**不需要索取**：单条目查询默认就会带 `TagItems`（响应里有
   * `TagItems` 三条、却连 `Tags` 字段都没有）⇒ 详情页读标签用
   * 「优先 TagItems、为空再退回 Tags」的写法，两边都索取就是白搭风险。
   */
  fun getItem(server: EmbyServer, itemId: String): EmbyItem {
    val path = EmbyPaths.userItem(server.userId, itemId)
    // 先按「带 ExternalUrls」的清单要一次，失败再退回不带它的（原因见 [EmbyItemFields.ITEM_FIELDS_WITH_EXTERNAL_URLS]）。
    // ⚠️ 必须显式写 `<EmbyItem>`：runCatching 里推断不出 reified 类型参数。
    val withExternalUrls = runCatching {
      EmbyHttpCore.getJson<EmbyItem>(server, path, mapOf("fields" to EmbyItemFields.ITEM_FIELDS_WITH_EXTERNAL_URLS))
    }
    // 第二次再失败就让它抛出去 —— 真正的错误（网络 / 鉴权 / 404）不能被吞掉。
    return withExternalUrls.getOrElse {
      EmbyHttpCore.getJson(server, path, mapOf("fields" to EmbyItemFields.ITEM_FIELDS_CORE))
    }
  }

  /**
   * 条目的**上级目录链**（由近到远），不含条目本身。
   *
   * 对应 SDK：`ItemsServiceApi.getItemsByIdAncestors` → `GET /Items/{Id}/Ancestors`，
   * 官方描述是 "Gets all parents of an item"。
   *
   * ## 谁在用
   *
   * 库页顶栏的「跳转到」下拉导航（`EmbyLibraryScreen` → `EmbyPathNavigatorSheet`）。
   * Emby 是 **Id 树**、不是路径树：页面手里只有一个 `libraryId`，光靠它无法知道自己
   * 站在第几层、上面还剩哪几级。这个端点一次就把整条链给全，比在 backStack 里
   * 反查可靠得多 —— 从详情页跳进文件夹时栈里夹着 `EmbyDetailScreen`，
   * 直接进某个库时栈里更是根本没有父级记录。
   *
   * ## 四件事必须说清
   *
   * 1. ⚠️⚠️ **顺序是「由近到远」**：数组第一个是**直接父目录**，最后一个才是最外层的
   *    容器（`CollectionFolder` / `UserView`）。这个方向与人的直觉相反，且官方文档
   *    只写了 "all parents" 没写顺序 —— 曾经按「最外层在前」实现，结果导航抽屉整条
   *    链上下颠倒。凡是要按「上层在上、下层在下」铺的调用方，必须先倒序。
   *    取「上一级」用 `firstOrNull()`。
   * 2. **不含条目自己**：所以「当前层」要单独用 `itemId` 查一次（本类 [getItem]），
   *    或用调用方手上已有的名字 —— 库页本来就知道自己叫什么。
   * 3. **可能返回空数组**：条目直接挂在服务器根时没有祖先。这不是错误，
   *    调用方按「只有一层」处理即可，不要当成失败弹提示。
   * 4. ⭐⭐ **链的末端（最后一个）通常是「顶层视图」而不是真实目录** ——
   *    即 `ParentId` 为空的容器，代表 Emby 自己的首页（媒体库入口列表）。
   *    它**照原样留在链里**，因为「回首页」正是这个导航最该提供的出口之一；
   *    但它不能用普通文件夹的下钻方式打开（`/Items?ParentId=<它>` 与普通目录
   *    语义不同，铺出来不是一个正常的库页）—— 调用方必须按「回到首页」处理，
   *    而不是压一层 `EmbyLibraryScreen`。判据见 `EmbyLibraryScreen` 里的
   *    `isTopLevelRoot`。
   *
   * ⚠️ 老服务端 / 第三方兼容实现可能没实现这个端点（404）。调用方一律
   * `runCatching` 兜成空列表，退化成「只有当前层」的导航 —— 功能降级，但页面不能挂。
   */
  fun getItemAncestors(server: EmbyServer, itemId: String): List<EmbyItem> {
    // ⚠️ 写成语句体 + 显式局部类型：表达式体的链式调用里 `List<T>` 推断不出来
    // （会退化成 Any），跟在后面的集合操作就解析不了。
    //
    // ⭐⭐ `fields` 必须显式要：这个端点不带 Fields 时返回的是**精简对象**，
    // 各容器只有最基本的几个字段，名字相关的那几项（`SortName` 等）一律为空。
    // 于是导航里显示的就不是「用户起的那个名字」，而是别的来源的短名 ——
    // 症状是「某一层显示的名字和它真实叫法对不上」（往往只剩数字后缀之类）。
    return EmbyHttpCore.getJson(server, EmbyPaths.itemAncestors(itemId), mapOf("fields" to EmbyItemFields.ANCESTOR_FIELDS))
  }

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
    EmbyHttpCore.getJson(server, EmbyPaths.itemChapters(itemId))

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
    EmbyHttpCore.getJson(server, EmbyPaths.userItemIntros(server.userId, itemId))

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
    /** 分页起点。聚合检索翻页时按上一页返回的原始条数推进 */
    startIndex: Int = 0,
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
    /**
     * 覆盖默认的 [EmbyItemFields.CORE]。
     *
     * 聚合搜索要按 `ProviderIds` 跨服务器判断「是不是同一部片」，而 CORE 里没有这一项
     * （不带 Fields 时服务端不回）。默认值保持 CORE ⇒ 现有调用行为逐字不变。
     */
    fields: String = EmbyItemFields.CORE,
  ): EmbyItemsResult = getItems(
    server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = includeItemTypes,
    searchTerm = term,
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    fields = fields,
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
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.showSeasons(seriesId),
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
   * limit 为空时不发送 Limit，避免兼容服务端把 0 解释成空结果。
   */
  fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.showEpisodes(seriesId),
    mapOf(
      "UserId" to server.userId,
      "SeasonId" to seasonId,
      "StartIndex" to startIndex.toString(),
      "Limit" to limit?.toString(),
      "Fields" to "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources",
      "EnableUserData" to "true",
    ),
  )

  /** 播放历史：按最近播放时间倒序（只统计视频类媒体） */
  fun getPlayedHistory(
    server: EmbyServer,
    limit: Int = 60,
    startIndex: Int = 0,
    mediaTypes: List<String>? = listOf(EmbyClient.VIDEO_MEDIA_TYPE),
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
   *
   * ## 为什么排序要交给服务端（而不是拉回来本地排）
   *
   * 这个接口是**分页**的（收藏页一页 100 条，滚到底再续拉）。本地排序只作用于
   * 「已经拉回来的那几页」，而**分页切分本身是按 SortBy 走的**：
   * 排序口径写死 `SortName Ascending` 时，服务端返回的第一页是「名字最靠前的 100 条」，
   * 客户端再按入库时间排一遍 —— 得到的其实是「名字前 100 条里最晚入库的」，
   * 真正最晚入库的那部片子如果名字靠后，压根不在这一页里，永远进不了第一屏。
   * 收藏越多越离谱，且只在特定排序下出现，看起来就像「排序偶尔不生效」。
   *
   * 所以凡是服务端有对应排序的（名称 / 年份 / 评分 / 入库时间），都必须下推，
   * 让**分页与排序同口径**；只有 `收藏时间` 这种服务端没有的，才退回本地排
   * （见 EmbyFavoritesScreen 的 FavoriteSort.FAVORITED）。
   *
   * @param sortBy Emby `SortBy` 取值（`SortName` / `ProductionYear` / `CommunityRating` /
   *   `DateCreated` …）。传 null 保持原来的 `SortName`，老调用方行为不变。
   * @param sortOrder `Ascending` / `Descending`
   */
  fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    mediaTypes: List<String>? = listOf(EmbyClient.VIDEO_MEDIA_TYPE),
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server,
    sortBy = sortBy ?: "SortName",
    sortOrder = sortOrder ?: "Ascending",
    filters = listOf("IsFavorite"),
    mediaTypes = mediaTypes,
    startIndex = startIndex,
    limit = limit,
    // 收藏页要按年份 / 评分 / 添加时间做**本地排序**，这几个字段必须显式索取
    // （见 [EmbyItemFields.WITH_SORT_KEYS] 的说明：不索取就全是 null，排序等于没排）
    fields = EmbyItemFields.WITH_SORT_KEYS,
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
    mediaTypes: List<String>? = listOf(EmbyClient.VIDEO_MEDIA_TYPE),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.itemSimilar(itemId),
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to EmbyItemFields.CORE,
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
    mediaTypes: List<String>? = listOf(EmbyClient.VIDEO_MEDIA_TYPE),
    filters: List<String>? = null,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.itemInstantMix(itemId),
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "Fields" to EmbyItemFields.CORE,
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
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    EmbyPaths.SHOWS_NEXT_UP,
    mapOf(
      "UserId" to server.userId,
      "Limit" to limit.toString(),
      "SeriesId" to seriesId,
      "Fields" to EmbyItemFields.CORE,
      "EnableImages" to "true",
      "EnableUserData" to "true",
      "ImageTypeLimit" to "1",
      "EnableTotalRecordCount" to "true",
    ),
  )
}
