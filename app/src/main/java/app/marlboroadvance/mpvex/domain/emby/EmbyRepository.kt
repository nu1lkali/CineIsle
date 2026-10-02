package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Emby 业务仓库。封装 [EmbyClient] 调用 + 持有当前会话服务器，
 * 对 UI 层暴露挂起 API，所有网络调用都切到 IO 线程。
 *
 * 用法：
 * ```
 * class EmbyLibraryScreenViewModel(private val repo: EmbyRepository): ViewModel() {
 *   val servers = repo.observeServers()
 *   fun loadLibs(server: EmbyServer) = viewModelScope.launch {
 *     val libs = repo.getVirtualFolders(server)
 *     ...
 *   }
 * }
 * ```
 */
class EmbyRepository(
  private val serverRepo: EmbyServerRepository,
  private val context: android.content.Context,
) {
  // 仓库是 Koin 单例，用它自己的作用域维护"当前服务器"，保证跨页面一致
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val prefs by lazy {
    context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
  }

  private val _currentServer = MutableStateFlow<EmbyServer?>(null)
  /** 当前正在浏览的服务器；null 表示未选择/未配置 */
  val currentServer: StateFlow<EmbyServer?> = _currentServer.asStateFlow()

  /** 「启动时恢复上次服务器」是否已完成。未完成前读 [currentServer] 一定是 null。 */
  private val restored = CompletableDeferred<Unit>()

  init {
    // 启动后自动恢复上次使用的服务器
    scope.launch {
      try {
        val all = runCatching { serverRepo.getAll() }.getOrDefault(emptyList())
        if (all.isNotEmpty()) {
          val savedId = prefs.getLong(KEY_CURRENT_SERVER_ID, -1L)
          val preferred = all.firstOrNull { it.id == savedId && it.isLoggedIn }
            ?: all.firstOrNull { it.isLoggedIn }
            ?: all.firstOrNull()
          if (preferred != null) setCurrentServer(preferred)
        }
      } finally {
        restored.complete(Unit)
      }
    }
  }

  /**
   * 等待「启动恢复」完成后返回当前服务器。
   *
   * 冷启动时服务器是从数据库异步恢复的，[currentServer] 一开始是 null。
   * 如果直接读，首屏会误判成「没有服务器」而清空数据，用户必须手动点刷新才看得到内容。
   * 因此所有首屏加载都要用这个方法取服务器。
   */
  suspend fun awaitCurrentServer(): EmbyServer? {
    restored.await()
    return _currentServer.value
  }

  /** 切换当前服务器（选择会被记住，下次启动自动恢复） */
  fun setCurrentServer(server: EmbyServer) {
    _currentServer.value = server
    prefs.edit().putLong(KEY_CURRENT_SERVER_ID, server.id).apply()
  }

  /** 按 id 切换当前服务器 */
  suspend fun selectServer(id: Long): EmbyServer? {
    val server = serverRepo.getById(id) ?: return null
    setCurrentServer(server)
    return server
  }

  /** 重新从数据库读取并刷新当前服务器（例如凭据更新后） */
  suspend fun refreshCurrentServer(): EmbyServer? {
    val id = _currentServer.value?.id ?: return null
    val fresh = serverRepo.getById(id) ?: return null
    setCurrentServer(fresh)
    return fresh
  }

  /**
   * 确保当前服务器处于已登录状态；未登录时用库中保存的账号密码自动重登。
   * 返回可安全用于 API 调用的服务器，失败返回 null。
   */
  suspend fun ensureLoggedIn(server: EmbyServer? = _currentServer.value): EmbyServer? {
    val target = server ?: return null
    if (target.isLoggedIn) return target
    if (target.username.isBlank() || target.password.isBlank()) return null
    return runCatching { relogin(target) }
      .onSuccess { setCurrentServer(it) }
      .getOrNull()
  }

  // ─── 服务器管理（透传给 EmbyServerRepository）───
  val servers = serverRepo.allServers

  suspend fun getServers(): List<EmbyServer> = serverRepo.getAll()

  suspend fun getServer(id: Long): EmbyServer? = serverRepo.getById(id)

  suspend fun addServer(server: EmbyServer): Long = serverRepo.add(server)

  suspend fun updateServer(server: EmbyServer) = serverRepo.update(server)

  suspend fun deleteServer(server: EmbyServer) = serverRepo.delete(server)

  // ─── 登录 ───

  /**
   * 用 [host]/[port]/[useHttps]/[username]/[password] 登录，拿到 token 后写入新服务器记录。
   * 返回新服务器（含 id + apiToken + userId）。
   *
   * 失败会抛 [EmbyApiException]。
   */
  suspend fun loginAndSave(
    name: String,
    host: String,
    port: Int,
    useHttps: Boolean,
    username: String,
    password: String,
  ): EmbyServer = withContext(Dispatchers.IO) {
    // 临时服务器对象（不入库）拿 token
    val probe = EmbyServer(
      name = name,
      host = host,
      port = port,
      useHttps = useHttps,
      username = username,
      password = password,
    )
    val auth = EmbyClient.authenticateByName(probe, username, password)
    val token = auth.AccessToken
      ?: throw EmbyApiException(401, "AuthenticateByName 没返回 AccessToken")
    val userId = auth.User?.Id ?: throw EmbyApiException(401, "AuthenticateByName 没返回 User.Id")

    // 顺便拿服务器名/版本
    val info = runCatching { EmbyClient.getPublicSystemInfo(probe) }.getOrNull()

    // 入库前先看一眼：这是不是第一台服务器（之前一台都没有）
    val wasFirstServer = runCatching { serverRepo.getAll().isEmpty() }.getOrDefault(false)

    // 入库（带凭据）
    val id = serverRepo.add(
      probe.copy(
        userId = userId,
        apiToken = token,
        serverName = info?.ServerName ?: name,
        version = info?.Version ?: "",
      ),
    )
    // 入库时 createdAt 等被 Room 填，重新查一次返回完整对象
    val saved = serverRepo.getById(id) ?: throw EmbyApiException(500, "无法读取新添加的服务器记录")

    // 第一台服务器直接设为当前：没有历史选择可恢复，不设的话添加完
    // 还得手动再点一下才能进库浏览
    if (wasFirstServer) setCurrentServer(saved)
    saved
  }

  /**
   * 重新登录已有服务器记录（token 过期或被 revoke 时用）。
   * 用 [server] 的用户名密码重新调 authenticateByName，回写凭据。
   */
  suspend fun relogin(server: EmbyServer): EmbyServer = withContext(Dispatchers.IO) {
    val auth = EmbyClient.authenticateByName(server, server.username, server.password)
    val token = auth.AccessToken ?: throw EmbyApiException(401, "重登录失败")
    val userId = auth.User?.Id ?: throw EmbyApiException(401, "重登录失败：无 User.Id")
    serverRepo.saveCredentials(
      id = server.id,
      userId = userId,
      apiToken = token,
      serverName = server.serverName,
      version = server.version,
    )
    serverRepo.getById(server.id)?.copy(
      userId = userId,
      apiToken = token,
    ) ?: server
  }

  // ─── 媒体库 / 媒体 ───

  /** 媒体库列表（根级 VirtualFolders） */
  suspend fun getVirtualFolders(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      EmbyClient.getVirtualFolders(server).Items
    }

  /** 库内媒体分页 */
  suspend fun getItems(
    server: EmbyServer,
    parentId: String? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    filters: List<String>? = null,
    includeItemTypes: List<String>? = null,
    genres: List<String>? = null,
    searchTerm: String? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    excludeItemTypes: List<String>? = null,
    /** 按发行年份筛选，多选取并集 */
    years: List<Int>? = null,
    /** 最低社区评分（0~10） */
    minCommunityRating: Float? = null,
    /** 标签筛选，多选取并集 */
    tags: List<String>? = null,
    /** 官方分级筛选，多选取并集 */
    officialRatings: List<String>? = null,
    /** true / false 分别限定只要收藏 / 只要未收藏；null 表示不限 */
    isFavorite: Boolean? = null,
    /**
     * 只要某种 MediaType：Video / Audio / Photo / Book。
     *
     * 这是 Emby 官方「只要视频」的正解 —— 和 IncludeItemTypes 是两套口径：
     * 后者按条目类型（Movie / Episode …）筛，前者按**媒体形态**筛。
     * 库里混进的音频、图片、电子书，MediaType 都不是 Video，单靠类型白名单挡不住。
     */
    mediaTypes: List<String>? = null,
    /** false = 只要能直接播放的媒体本体，容器（Folder / Series / Season / BoxSet）全挡掉 */
    isFolder: Boolean? = null,
    /** 按人员（演员 / 导演）筛选，传 PersonId */
    personIds: List<String>? = null,
    /** true 只要已看 / false 只要未看；null 不限 */
    isPlayed: Boolean? = null,
    /** true 只要高清 / false 只要标清 */
    isHD: Boolean? = null,
    /** true 只要 3D / false 排除 3D */
    is3D: Boolean? = null,
    /** true 只要有字幕 / false 只要没字幕 */
    hasSubtitles: Boolean? = null,
    /** 工作室 Id 列表，多选取并集 */
    studioIds: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getItems(
      server,
      parentId = parentId,
      sortBy = sortBy,
      sortOrder = sortOrder,
      filters = filters,
      includeItemTypes = includeItemTypes,
      genres = genres,
      searchTerm = searchTerm,
      startIndex = startIndex,
      limit = limit,
      recursive = recursive,
      excludeItemTypes = excludeItemTypes,
      years = years,
      minCommunityRating = minCommunityRating,
      tags = tags,
      officialRatings = officialRatings,
      isFavorite = isFavorite,
      mediaTypes = mediaTypes,
      isFolder = isFolder,
      personIds = personIds,
      isPlayed = isPlayed,
      isHD = isHD,
      is3D = is3D,
      hasSubtitles = hasSubtitles,
      studioIds = studioIds,
    )
  }

  /**
   * 全量扫描：把 [query] 命中的**整个**结果集逐页拉完。
   *
   * 用在「客户端筛选」场景（如按路径判断的中文字幕）：Emby 服务端没有这类筛选参数，
   * 只筛第一页必然漏，必须一个 StartIndex 一个 StartIndex 地翻到底。
   * 每页拉完调一次 [onChunk]，UI 能边扫边出结果，不必等整库拉完。
   *
   * 结束条件只认「空页」和「已翻到 TotalRecordCount」：
   * 不能拿「返回条数 < 请求条数」当结束条件 —— 有些 Emby 版本会截断 Limit，
   * 那样会在第一页就退出，退化成「只筛出一部分」。
   *
   * @param pageSize 每页条数
   * @param maxItems 本次扫描的条数上限（兜底，防止异常数据把设备拖死）
   * @return 实际扫描到的条目数
   */
  suspend fun scanItems(
    server: EmbyServer,
    query: EmbyScanQuery,
    pageSize: Int = SCAN_PAGE_SIZE,
    maxItems: Int = SCAN_MAX_ITEMS,
    onChunk: suspend (EmbyScanChunk) -> Unit,
  ): Int = withContext(Dispatchers.IO) {
    var startIndex = 0
    var scanned = 0
    var total = 0
    while (scanned < maxItems) {
      // 每页之间检查一次取消：整个扫描可能上百个请求，用户退出页面时要能立刻停下
      currentCoroutineContext().ensureActive()
      val limit = (maxItems - scanned).coerceAtMost(pageSize)
      val page = EmbyClient.getItems(
        server = server,
        parentId = query.parentId,
        sortBy = query.sortBy,
        sortOrder = query.sortOrder,
        filters = query.filters,
        includeItemTypes = query.includeItemTypes,
        genres = query.genres,
        startIndex = startIndex,
        limit = limit,
        recursive = query.recursive,
        excludeItemTypes = query.excludeItemTypes,
        personIds = query.personIds,
        years = query.years,
        minCommunityRating = query.minCommunityRating,
        tags = query.tags,
        officialRatings = query.officialRatings,
        isPlayed = query.isPlayed,
        isHD = query.isHD,
        is3D = query.is3D,
        hasSubtitles = query.hasSubtitles,
        studioIds = query.studioIds,
        isFavorite = query.isFavorite,
        mediaTypes = query.mediaTypes,
        isFolder = query.isFolder,
      )
      val items = page.Items
      if (page.TotalRecordCount > 0) total = page.TotalRecordCount
      if (items.isEmpty()) break
      scanned += items.size
      startIndex += items.size
      onChunk(EmbyScanChunk(scanned = scanned, total = total, items = items))
      if (total > 0 && startIndex >= total) break
    }
    scanned
  }

  /** 某媒体库的筛选可选项（类型 / 标签 / 年份 / 分级） */
  suspend fun getFilterOptions(    server: EmbyServer,
    parentId: String? = null,
  ): EmbyFilterOptions = withContext(Dispatchers.IO) { EmbyClient.getFilterOptions(server, parentId) }

  /** 继续观看 */
  suspend fun getResumeItems(server: EmbyServer, limit: Int = 20): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getResumeItems(server, limit = limit).Items }

  /** 最新加入 */
  suspend fun getLatestItems(server: EmbyServer, limit: Int = 16): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getLatestItems(server, limit = limit) }

  /** 媒体详情 */
  suspend fun getItem(server: EmbyServer, itemId: String): EmbyItem =
    withContext(Dispatchers.IO) { EmbyClient.getItem(server, itemId) }

  /** 收藏夹列表 */
  suspend fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getFavoriteItems(server, startIndex, limit)
  }

  /**
   * 收藏的演员列表（收藏页「演员」tab）。
   *
   * Person 只能从 `/Persons` 端点拿（`/Users/{id}/Items` 不返回 Person），
   * 见 [EmbyClient.getFavoritePersons] 的说明。
   */
  suspend fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getFavoritePersons(server) }

  /** 按 Id 取单个人员（演员作品页收藏红心查当前状态用） */
  suspend fun getPersonById(server: EmbyServer, personId: String): EmbyItem? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonById(server, personId) }

  /**
   * 随机播放：返回 [limit] 个随机项。可指定 [includeItemTypes]
   * 例如 listOf("Movie") / listOf("Episode")。
   */
  suspend fun getRandomItems(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getRandomItems(server, parentId, includeItemTypes, limit, isFavorite)
  }

  // ─── 收藏 / 删除 / 已看 ───

  suspend fun favorite(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.setFavorite(server, itemId, true)
  }

  suspend fun unfavorite(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.setFavorite(server, itemId, false)
  }

  /** 加入 / 取消收藏；返回服务器回传的 UserData（含 IsFavorite），调用方据此确认真实状态 */
  suspend fun setFavorite(
    server: EmbyServer,
    itemId: String,
    favorite: Boolean,
  ): EmbyUserData? = withContext(Dispatchers.IO) {
    EmbyClient.setFavorite(server, itemId, favorite)
  }

  /**
   * 取消收藏的「用力」版：额外补一次标准 DELETE 方法（见 [EmbyClient.unfavoriteHard]）。
   *
   * 只在回读确认「取消收藏没生效」时作为重试用 —— 部分兼容服务端对
   * `POST .../Delete` 回 200 却不落库，需要标准写法兜底。
   */
  suspend fun unfavoriteHard(server: EmbyServer, itemId: String): EmbyUserData? = withContext(Dispatchers.IO) {
    EmbyClient.unfavoriteHard(server, itemId)
  }

  suspend fun deleteItem(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.deleteItem(server, itemId)
  }

  suspend fun markPlayed(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.setPlayed(server, itemId, true)
  }

  suspend fun markUnplayed(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.setPlayed(server, itemId, false)
  }

  /** 标记已看 / 未看；返回服务器回传的 UserData（含 Played），调用方据此确认真实状态 */
  suspend fun setPlayed(
    server: EmbyServer,
    itemId: String,
    played: Boolean,
  ): EmbyUserData? = withContext(Dispatchers.IO) {
    EmbyClient.setPlayed(server, itemId, played)
  }

  // ─── 播放进度上报 ───

  suspend fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
  ): String = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackStart(server, itemId, positionTicks)
  }

  suspend fun reportPlaybackProgress(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
    isPaused: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackProgress(server, itemId, playSessionId, positionTicks, isPaused)
  }

  suspend fun reportPlaybackStopped(
    server: EmbyServer,
    itemId: String,
    playSessionId: String,
    positionTicks: Long,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackStopped(server, itemId, playSessionId, positionTicks)
  }

  /** 搜索媒体（服务端 SearchTerm 匹配），可按指定方式排序 */
  suspend fun searchItems(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>? = null,
    limit: Int = 60,
    sortBy: String? = "SortName",
    sortOrder: String? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.searchItems(server, term, includeItemTypes, limit, sortBy, sortOrder)
  }

  /**
   * 搜演员（走 `/Persons`）。
   *
   * 与 [searchItems] 分开，因为 `/Users/{id}/Items` 那条端点不返回 Person。
   */
  suspend fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    EmbyClient.searchPersons(server, term, parentId, limit)
  }

  /** 剧集的季列表 */
  suspend fun getSeasons(
    server: EmbyServer,
    seriesId: String,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getSeasons(server, seriesId)
  }

  /** 某一季下的剧集列表 */
  suspend fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getEpisodes(server, seriesId, seasonId)
  }

  /** 按播放时间倒序拉取历史记录（Emby 的 DatePlayed 排序） */
  suspend fun getPlayedHistory(
    server: EmbyServer,
    limit: Int = 60,
    startIndex: Int = 0,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPlayedHistory(server, limit, startIndex)
  }

  companion object {
    private const val PREFS_NAME = "emby_session"
    private const val KEY_CURRENT_SERVER_ID = "current_server_id"
  }

  // ─── URL 构造（图片 / 流 / 字幕）───

  fun imageUrl(
    server: EmbyServer,
    itemId: String,
    imageType: String = "Primary",
    tag: String? = null,
    maxWidth: Int = 480,
    maxHeight: Int? = null,
  ): String = EmbyClient.imageUrl(server, itemId, imageType, tag, maxWidth, maxHeight)

  fun videoStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    mediaSourceId: String? = null,
  ): String = EmbyClient.videoStreamUrl(server, itemId, static, mediaSourceId)

  /**
   * 「让服务器转码」后的播放地址（本机解不了的编码走这条，见 [EmbyClient.transcodeStreamUrl]）。
   *
   * 会先向服务器要一次播放方案（`PlaybackInfo`），所以是个挂起函数、有网络往返；
   * 只在用户明确选择「服务器转码播放」时才调用，不要放进默认播放路径。
   */
  suspend fun transcodeStreamUrl(
    server: EmbyServer,
    itemId: String,
    startTimeTicks: Long = 0L,
  ): String = EmbyClient.transcodeStreamUrl(server, itemId, startTimeTicks)

  /** 离线下载原始文件的 URL（支持 Range，可断点续传）。 */
  fun itemDownloadUrl(server: EmbyServer, itemId: String): String =
    EmbyClient.itemDownloadUrl(server, itemId)

  fun subtitleUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): String = EmbyClient.subtitleUrl(server, itemId, mediaSourceId, subtitleIndex, format)
}

/**
 * Ticks (Emby 内部时间单位, 10000 ticks = 1ms = 0.001s) 与秒/毫秒转换。
 */
object EmbyTicks {
  const val TICKS_PER_MS = 10_000L
  const val TICKS_PER_SECOND = 10_000_000L

  fun ticksToSeconds(ticks: Long?): Long = (ticks ?: 0) / TICKS_PER_SECOND

  fun ticksToMs(ticks: Long?): Long = (ticks ?: 0) / TICKS_PER_MS

  fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND

  fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
}
