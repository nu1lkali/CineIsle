package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.repository.EmbyServerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    serverRepo.getById(id) ?: throw EmbyApiException(500, "无法读取新添加的服务器记录")
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
    )
  }

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
    EmbyClient.favoriteItem(server, itemId)
  }

  suspend fun unfavorite(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.unfavoriteItem(server, itemId)
  }

  suspend fun deleteItem(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.deleteItem(server, itemId)
  }

  suspend fun markPlayed(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.markPlayed(server, itemId)
  }

  suspend fun markUnplayed(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    EmbyClient.markUnplayed(server, itemId)
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

  /** 搜索媒体（服务端 SearchTerm 匹配） */
  suspend fun searchItems(
    server: EmbyServer,
    term: String,
    includeItemTypes: List<String>? = null,
    limit: Int = 60,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.searchItems(server, term, includeItemTypes, limit)
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
