package app.marlboroadvance.mpvex.domain.emby

import android.widget.Toast
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
  /**
   * 收藏时间账本。Emby 只存 `IsFavorite` 这个布尔值，没有收藏时间戳
   * （见 [app.marlboroadvance.mpvex.database.entities.FavoriteMarkEntity] 的 KDoc），
   * 收藏页「收藏时间」排序只能靠它。
   *
   * ⚠️ 挂在这一层而不是各个 UI 调用点：单条收藏一共有三条路
   * （详情页/媒体库卡片 → EmbyViewModel、播放器控件 → EmbyPlayerActions、
   * 视界流红心 → FeedViewModel），它们最终都走 [setFavorite] / [favorite] / [unfavorite]。
   * 在 UI 层记账迟早漏一条，而漏掉的表现是「排序里它凭空沉到最后」，很难查。
   *
   * 可空：仓库在单元测试里不需要账本，传 null 即退化为「不记录」。
   */
  private val favoriteClock: FavoriteClock? = null,
) {
  // 仓库是 Koin 单例，用它自己的作用域维护"当前服务器"，保证跨页面一致
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val prefs by lazy {
    context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
  }

  private val _currentServer = MutableStateFlow<EmbyServer?>(null)
  /** 当前正在浏览的服务器；null 表示未选择/未配置 */
  val currentServer: StateFlow<EmbyServer?> = _currentServer.asStateFlow()

  /**
   * 第二地址（内网 / frp 公网双入口）的落盘与择优，见 [EmbyEndpointResolver]。
   *
   * 与 [failover] 的分工：这里换的是**同一台服务器**的入口，[failover] 换的是**服务器**。
   * 顺序不能反 —— 内网抖一下就把用户甩到另一台服务器上是错的。
   */
  private val endpointStore = EmbyEndpointStore(context)

  val endpoints: EmbyEndpointResolver =
    EmbyEndpointResolver(
      scope = scope,
      store = endpointStore,
    )

  /**
   * 主 / 备服务器的自动接管（见 [EmbyFailoverController]）。
   *
   * 落盘在独立的 `emby_failover` 偏好里，**不动 Room 表** —— 加这个功能不需要任何迁移。
   * 由仓库持有而不是做成另一个 Koin 单例：它要读的「当前服务器」、服务器列表都在这里，
   * 做成单例会为了拿这两样再反向依赖回来，绕一圈没有收益。
   */
  val failover: EmbyFailoverController =
    EmbyFailoverController(
      context = context,
      scope = scope,
      currentServerProvider = { _currentServer.value },
      // 自动切换只改内存：不写 current_server_id，重启后仍然回到用户选的那台
      switchInMemory = { _currentServer.value = it },
      loadServers = { serverRepo.getAll() },
      // 一台服务器可能有两个入口，任一入口的失败都算「这台」的失败
      hostBelongsTo = { server, host ->
        server.host.equals(host, ignoreCase = true) ||
          endpointStore.altFor(server.id)?.host?.equals(host, ignoreCase = true) == true
      },
    )

  /** 「启动时恢复上次服务器」是否已完成。未完成前读 [currentServer] 一定是 null。 */
  private val restored = CompletableDeferred<Unit>()

  init {
    // 拼 URL 的统一出口装上解析器：没装时行为与改造前完全一致（回落服务器自身配置）
    EmbyEndpoints.resolver = endpoints
    // 入口切换要给用户一句提示（v160）。
    //
    // 为什么不放在某个页面里：触发点是「任意一次真实请求失败」，那一刻用户可能
    // 在首页、媒体库、甚至在播放器里 —— 没有哪个界面「拥有」这个事件。
    // 放在仓库（Koin 单例、随 App 存活）才不会漏。
    //
    // ⚠️ Toast 必须在主线程：仓库的 scope 是 `Dispatchers.Default`，非主线程上
    // `Toast#show()` 会抛 "Can't create handler inside thread that has not called
    // Looper.prepare()"（Toast 的 TN 在构造时绑当前线程的 Looper）。
    scope.launch {
      endpoints.switched.collect { server ->
        val now = endpoints.currentFor(server)
        val text =
          if (now.baseUrl == server.primaryEndpoint().baseUrl) {
            "已切回主地址 ${now.host}:${now.port}"
          } else {
            "已切换到第二地址 ${now.host}:${now.port}"
          }
        withContext(Dispatchers.Main) {
          Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
      }
    }
    // 进出家门 / 切 WiFi / 断开蜂窝时重估所有配了双入口的服务器。
    // 只在网络变化这一刻探，请求路径上零开销。
    registerNetworkCallback()
    // 让 [EmbyHttp] 的重试拦截器知道「当前该用哪个 token」——
    // 401 自动重登后要拿**新** token 重放原请求，拦截器自己取不到。
    EmbyHttp.installTokenSource { _currentServer.value?.apiToken.orEmpty() }
    // 请求失败的处理顺序（很重要）：
    //   ① 有第二地址 ⇒ 先让地址层竞速自救（换到内网/公网另一个入口，或确认只是网络闪断）；
    //   ② 只有两个入口都不通，或者压根没配第二地址，才算「这台服务器有问题」，交给主备接管。
    // 顺序反了会出现「家里内网抖一下就被甩到另一台服务器上」。
    // 只挂 shared 那一档（API 请求），图片 / 拉流不走这里 —— 它们失败不代表服务器不可用。
    EmbyHttp.onTransientFailure = failure@{ host, _ ->
      val target = _currentServer.value ?: return@failure
      scope.launch {
        val outcome = runCatching {
          endpoints.refresh(target, failedHost = host)
        }.getOrDefault(EmbyEndpointResolver.Outcome.NoAlternate)
        val deadLike =
          outcome == EmbyEndpointResolver.Outcome.Dead ||
            outcome == EmbyEndpointResolver.Outcome.NoAlternate
        // 刚换过入口的 30 秒内不判死：新入口可能还在建连接
        if (deadLike && !endpoints.switchedRecently(target.id)) {
          failover.onRequestFailure(host)
        }
      }
    }
    // 收到 401 时自动重登一次再重放请求（对应清单 1.3）。
    // 以前 token 过期只能靠各调用点手动 ensureLoggedIn 补，漏掉的地方
    // 用户看到的是「筛选面板空着」「列表不刷新」这类莫名其妙的表现。
    EmbyHttp.onUnauthorized = {
      val target = _currentServer.value
      if (target != null && target.username.isNotBlank() && target.password.isNotBlank()) {
        scope.launch {
          runCatching { relogin(target) }.onSuccess { setCurrentServer(it) }
        }
      }
    }

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

  // ─── 第二地址（内网 / 公网双入口）───

  /** 各服务器配的第二地址。UI 必须 collect 这个 flow，别直接调函数读快照 */
  val endpointAlts: StateFlow<Map<Long, EmbyEndpoint>> = endpointStore.alts

  /** 当前生效入口（可能已经切到第二地址） */
  val activeEndpoints: StateFlow<Map<Long, EmbyEndpoint>> = endpoints.active

  /** 这台此刻实际走在哪个入口 */
  fun activeEndpointFor(server: EmbyServer): EmbyEndpoint = endpoints.currentFor(server)

  /**
   * 设置 / 取消某台的第二地址。
   *
   * 传 null 或空 host = 取消。改完立刻重估当前服务器，用户不用退出重进。
   */
  fun setAltEndpoint(serverId: Long, endpoint: EmbyEndpoint?) {
    endpointStore.setAlt(serverId, endpoint)
    if (endpoint == null || endpoint.host.isBlank()) {
      endpoints.forget(serverId)
      return
    }
    val current = _currentServer.value
    if (current?.id == serverId) {
      scope.launch { runCatching { endpoints.refresh(current) } }
    }
  }

  /** 按 id 切换当前服务器 */
  suspend fun selectServer(id: Long): EmbyServer? {
    val server = serverRepo.getById(id) ?: return null
    setCurrentServer(server)
    return server
  }

  /**
   * 用户在管理页**亲手**选了某台服务器。
   *
   * 与 [setCurrentServer] 的区别只有一点，但很关键：要告诉 [failover]「这是一次明确选择」——
   * 清掉降级态、停掉后台的自动切回，否则用户刚点到备用上，20 秒后主恢复又把它抢回去。
   */
  fun selectServerAsUser(server: EmbyServer) {
    setCurrentServer(server)
    failover.onUserSelected(server)
    // 用户主动选服务器时顺手定一次入口：从另一台切过来时，可能正好换了网络环境
    if (endpointStore.altFor(server.id) != null) {
      scope.launch { runCatching { endpoints.refresh(server) } }
    }
  }

  /**
   * 监听默认网络变化。
   *
   * 「在家 / 在外」这件事不需要读 WiFi 名字（那要定位权限，而且连着家里 WiFi
   * 也不代表内网那台可达 —— 访客网络、VLAN 隔离都会骗过它），改成：
   * **网络一变就重新竞速两台地址，谁先通走谁**。判据是事实而不是猜测。
   */
  private fun registerNetworkCallback() {
    val cm =
      context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(
        object : android.net.ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: android.net.Network) {
            // 回调在工作线程：只起协程，别在这里做网络
            scope.launch {
              runCatching { endpoints.refreshAll(serverRepo.getAll()) }
            }
          }
        },
      )
    }.onFailure {
      android.util.Log.w("EmbyRepository", "注册网络变化回调失败", it)
    }
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

  suspend fun deleteServer(server: EmbyServer) {
    serverRepo.delete(server)
    // 顺手清掉与它相关的「备用」映射与降级态：留着只会指向一个不存在的 id
    failover.forgetServer(server.id)
    // 第二地址与它的生效入口缓存同样要清（否则 id 复用时会串到新服务器上）
    endpointStore.forget(server.id)
    endpoints.forget(server.id)
  }

  // ─── 登录 ───

  /**
   * 用 [host]/[port]/[useHttps]/[username]/[password] 登录，写入服务器记录。
   * 返回保存后的服务器（含 id + apiToken + userId）。
   *
   * a7：同主机 + 同用户重复登录 = **更新原记录**（凭据刷新，不产生重复行）；
   * 同主机 + **不同用户** = 新插一行，即「同一服务器多用户」的存在形态。
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

    // a7 多用户数据模型：**同一台服务器（host+port+https）下不同用户各占一行**，
    // 各自带自己的 token / UserData 口径（播放进度、收藏、不喜欢标记全部分开）；
    // 而**同一个用户**重复登录则更新原行，不新插 —— 不然每重登一次
    // 管理页就多出一条一模一样的记录，用户根本分不清点哪个。
    val existing =
      runCatching { serverRepo.getAll() }.getOrDefault(emptyList()).firstOrNull {
        it.host == probe.host &&
          it.port == probe.port &&
          it.useHttps == probe.useHttps &&
          it.userId == userId
      }
    if (existing != null) {
      val refreshed =
        existing.copy(
          name = name,
          username = username,
          password = password,
          userId = userId,
          apiToken = token,
          serverName = info?.ServerName ?: existing.serverName,
          version = info?.Version ?: existing.version,
          lastConnected = System.currentTimeMillis(),
        )
      serverRepo.update(refreshed)
      val saved = serverRepo.getById(existing.id) ?: refreshed
      // 正在用的正是这一台：token 换了必须立刻生效，否则后续请求还带着旧 token（401）
      if (_currentServer.value?.id == existing.id) setCurrentServer(saved)
      // ⚠️ 这里是 withContext 的 lambda（非 inline），不能 `return saved` ——
      // 非局部返回会被编译器拒（RETURN_NOT_ALLOWED）。用 if/else 两分支
      // 各自以表达式收尾，语义与「提前 return」完全一致。
      saved
    } else {
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
    /** 类型 Id 筛选（逗号分隔），优先于按名字的 [genres] */
    genreIds: List<String>? = null,
    /** 标签 Id 筛选（逗号分隔），优先于按名字的 [tags] */
    tagIds: List<String>? = null,
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
    /**
     * 覆盖默认的 [EmbyClient.ITEM_FIELDS]。
     *
     * 只在「大页数 + 只要几个字段」的场景用得上，典型是拼文件夹宫格封面：
     * 一页几百条，却只要 `ParentId` 与 `ImageTags`，带上默认的
     * `MediaSources` / `Overview` / `People` 会让响应体大出一个数量级。
     * 其余调用点保持默认值，行为不变。
     */
    fields: String = EmbyClient.ITEM_FIELDS,
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
      genreIds = genreIds,
      tagIds = tagIds,
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
      fields = fields,
    )
  }

  // ─── 照片（图集） ───

  /**
   * 相册列表：照片库根级（或任意文件夹下）的 PhotoAlbum。
   *
   * Emby 的照片库结构是「库 → 相册（文件夹）→ 照片」两层，所以进照片库先拉相册。
   * `Recursive=false`：相册就一层，不需要往下递归（再往下是照片，由 [getPhotos] 拉）。
   */
  suspend fun getPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server = server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("PhotoAlbum"),
    startIndex = startIndex,
    limit = limit,
    recursive = false,
    isFolder = false,
    fields = EmbyClient.PHOTO_FIELDS,
  )

  /**
   * 照片列表：相册内的 Photo（[parentId] 传相册 Id），或整个照片库递归平铺的
   * 全部照片（[parentId] 传库 Id + [recursive] = true，跳过相册层级）。
   *
   * `MediaTypes=Photo` 与 `IncludeItemTypes=Photo` 双写：前者按媒体形态、
   * 后者按条目类型，是 Emby 官方「只要照片」的口径，混进来的视频/音频
   * 两个关口都过不掉。
   *
   * ⚠️ 宽高 / EXIF **不靠 fields 索取**（见 [EmbyClient.PHOTO_FIELDS] 的说明），
   * 服务端对 Photo 条目自动返回，这里传默认的相册 fields 串即可。
   */
  suspend fun getPhotos(
    server: EmbyServer,
    parentId: String,
    recursive: Boolean = true,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = getItems(
    server = server,
    parentId = parentId,
    sortBy = sortBy,
    sortOrder = sortOrder,
    includeItemTypes = listOf("Photo"),
    mediaTypes = listOf("Photo"),
    startIndex = startIndex,
    limit = limit,
    recursive = recursive,
    isFolder = false,
    fields = EmbyClient.PHOTO_FIELDS,
  )

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
        // 「搜索 + 客户端筛选」时把搜索词交给服务端，扫的就是命中那批，但照样翻到底
        searchTerm = query.searchTerm,
        filters = query.filters,
        includeItemTypes = query.includeItemTypes,
        genres = query.genres,
        genreIds = query.genreIds,
        tagIds = query.tagIds,
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

  /**
   * 收藏夹列表。
   *
   * [sortBy] / [sortOrder] 会**下推到服务端**：收藏页是分页的，分页切分跟着 SortBy 走，
   * 只在本地排序会得到「第一页里再排一遍」这种半截结果（见 EmbyClient.getFavoriteItems 的 KDoc）。
   */
  suspend fun getFavoriteItems(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
    sortBy: String? = null,
    sortOrder: String? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getFavoriteItems(
      server = server,
      startIndex = startIndex,
      limit = limit,
      sortBy = sortBy,
      sortOrder = sortOrder,
    )
  }

  /**
   * 收藏的演员列表（收藏页「演员」tab）。
   *
   * Person 只能从 `/Persons` 端点拿（`/Users/{id}/Items` 不返回 Person），
   * 见 [EmbyClient.getFavoritePersons] 的说明。
   */
  suspend fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getFavoritePersons(server) }

  /**
   * 收藏的类型 / 工作室列表（收藏页「类型 / 工作室」tab）。
   *
   * 与 [getFavoritePersons] 不同，Genre / Studio 是 `/Users/{id}/Items` 能直接返回的
   * 正式条目，所以走服务端 `Filters=IsFavorite` 筛选，不需要客户端再过滤。
   */
  suspend fun getFavoriteGenres(server: EmbyServer): EmbyItemsResult =
    withContext(Dispatchers.IO) { EmbyClient.getFavoriteGenres(server) }

  suspend fun getFavoriteStudios(server: EmbyServer): EmbyItemsResult =
    withContext(Dispatchers.IO) { EmbyClient.getFavoriteStudios(server) }

  /** 按 Id 取单个人员（演员作品页收藏红心查当前状态用） */
  suspend fun getPersonById(server: EmbyServer, personId: String): EmbyItem? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonById(server, personId) }

  /**
   * 读单个人员的收藏真值（true=已收藏 / false=未收藏 / null=读不到）。
   *
   * 与 [getPersonById] 的分工：那个返回**条目**（给 UI 拼可点状态用），这个只关心
   * **IsFavorite 布尔值**，且走与加载时不同的 URL 形态（单条轻量读 + Web 收藏页同款的
   * `IsFavorite=true` 列表查询），专门绕开「按 URL 缓存导致回读拿到旧值」的坑。
   */
  suspend fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonFavoriteState(server, personId) }

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

  // ─── 相似推荐 / 即混 / 续播 ───
  // 服务端算法那三套（`/Items/{Id}/Similar` / `/Items/{Id}/InstantMix` / `/Shows/NextUp`）
  // 与本地算法（`LocalSimilarity`）由 `SimilarAlgorithm` 偏好二选一，
  // 详见 `EmbyClient` 2.5 节的说明。

  /** 相似推荐（服务端算法）。返回的是分页壳，用 [EmbyItemsResult.Items] 取。 */
  suspend fun getSimilarItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 24,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf("Video"),
    excludeIdList: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getSimilarItems(server, itemId, limit, includeItemTypes, mediaTypes, excludeIdList)
  }

  /** 即混（服务端基于种子自动续播一批）。 */
  suspend fun getInstantMixItems(
    server: EmbyServer,
    itemId: String,
    limit: Int = 20,
    includeItemTypes: List<String>? = listOf("Movie", "Series"),
    mediaTypes: List<String>? = listOf("Video"),
    filters: List<String>? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getInstantMixItems(server, itemId, limit, includeItemTypes, mediaTypes, filters)
  }

  /** 续播列表。服务端没开「剧集自动续播」时会是空列表。 */
  suspend fun getNextUpItems(
    server: EmbyServer,
    limit: Int = 20,
    seriesId: String? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getNextUpItems(server, limit, seriesId)
  }

  // ─── 合集（BoxSet）───
  // ⚠️ REST 路径是 `/Collections` 不是 `/BoxSets`（那是 Jellyfin 的命名），
  // 且参数名一律 `Ids`（只有 Playlist 增删用 `EntryIds`）。详见 [EmbyClient] 5.7 节。

  suspend fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getCollections(server, startIndex, limit)
  }

  suspend fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getCollectionItems(server, collectionId, startIndex, limit)
  }

  /**
   * 新建合集。返回 null 表示**建成了但拿不到 Id**（部分部署只在 `Location` 头里给地址），
   * 调用方要能接受这种情况：提示「已创建，请刷新」而不是当失败。
   */
  suspend fun createCollection(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    parentId: String? = null,
  ): EmbyCollectionCreationResult = withContext(Dispatchers.IO) {
    EmbyClient.createCollection(server, name, itemIds, parentId)
  }

  suspend fun addToCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean = withContext(Dispatchers.IO) {
    EmbyClient.addToCollection(server, collectionId, itemIds)
  }

  suspend fun removeFromCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean = withContext(Dispatchers.IO) {
    EmbyClient.removeFromCollection(server, collectionId, itemIds)
  }

  // ─── 显示偏好（DisplayPreferences）───
  // ⚠️ 粒度是「每个媒体库一份」而不是「每个用户一份」，且写操作是**整体覆盖**。
  // 详见 [EmbyClient] 5.8 节与 [EmbyDisplayPrefsCodec]。

  /** 读某个目标的显示偏好。目标不存在时返回 null（404 / 空响应都当「还没有」） */
  suspend fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String = EmbyClient.DISPLAY_PREFS_CLIENT,
  ): EmbyDisplayPreferences? = withContext(Dispatchers.IO) {
    EmbyClient.getDisplayPreferences(server, displayPreferencesId, client)
  }

  /** 写某个目标的显示偏好（整体覆盖，务必先读后写，理由见上） */
  suspend fun postDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String,
    preferences: EmbyDisplayPreferences,
  ): Boolean = withContext(Dispatchers.IO) {
    EmbyClient.postDisplayPreferences(server, displayPreferencesId, client, preferences)
  }

  /**
   * 拉条目章节（清单 #19：GSY 内核的章节数据源）。
   *
   * mpv 侧的章节来自容器内嵌章节表；GSY 没有章节能力，只能走这里。
   * 详见 [EmbyClient.getItemChapters] 的 KDoc —— 那里面写清了由此产生的
   * 「同文件两内核章节数可能不同」的能力边界，别在别处重复解释。
   *
   * 拿不到就返回空列表**而不是抛异常**：本地文件根本没这个 Emby 条目、
   * 或者服务端没刮削章节，都是正常情况，不该让播放页崩。
   */
  suspend fun getItemChapters(
    server: EmbyServer,
    itemId: String,
  ): List<EmbyChapter> =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getItemChapters(server, itemId) }
        .getOrDefault(emptyList())
    }

  /**
   * 拉条目的片头 / 片尾分段（a1 精准跳过片头片尾）。
   *
   * 拿不到就返回 null **而不是抛异常**：服务端没扫描片头标记（空 Segments）、
   * 端点不存在（旧版服务端）、网络断，都是正常情况，播放页按「没有精准数据、
   * 退固定时长兜底」处理即可。
   */
  suspend fun getItemIntros(
    server: EmbyServer,
    itemId: String,
  ): EmbyIntrosResult? =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getItemIntros(server, itemId) }.getOrNull()
    }

  // ─── 播放列表（Emby 服务端）───
  // ⚠️ 与本地播放列表（Room `PlaylistRepository`）是两套完全独立的东西，
  // 详见 [EmbyClient] 里 5.5 节的说明。

  suspend fun getPlaylists(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPlaylists(server, startIndex, limit)
  }

  suspend fun getPlaylistItems(
    server: EmbyServer,
    playlistId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPlaylistItems(server, playlistId, startIndex, limit)
  }

  suspend fun createPlaylist(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
  ): EmbyPlaylistCreationResult = withContext(Dispatchers.IO) {
    EmbyClient.createPlaylist(server, name, itemIds)
  }

  suspend fun addToPlaylist(
    server: EmbyServer,
    playlistId: String,
    itemIds: List<String>,
  ): EmbyPlaylistAddResult = withContext(Dispatchers.IO) {
    EmbyClient.addToPlaylist(server, playlistId, itemIds)
  }

  suspend fun removeFromPlaylist(
    server: EmbyServer,
    playlistId: String,
    entryIds: List<String>,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.removeFromPlaylist(server, playlistId, entryIds)
  }

  suspend fun movePlaylistItem(
    server: EmbyServer,
    playlistId: String,
    entryId: String,
    newIndex: Int,
  ) = withContext(Dispatchers.IO) {
    EmbyClient.movePlaylistItem(server, playlistId, entryId, newIndex)
  }

  suspend fun deletePlaylist(server: EmbyServer, playlistId: String) = withContext(Dispatchers.IO) {
    EmbyClient.deletePlaylist(server, playlistId)
  }

  // ─── 字幕在线搜索 ───

  /**
   * 搜在线字幕候选（详见 [EmbyClient.searchRemoteSubtitles]）。
   *
   * ⚠️ 单语言单次请求 —— 搜双语就是两次调用，由调用方合并。
   */
  suspend fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
  ): List<EmbyRemoteSubtitle> = withContext(Dispatchers.IO) {
    EmbyClient.searchRemoteSubtitles(
      server = server,
      itemId = itemId,
      language = language,
      mediaSourceId = mediaSourceId,
      isPerfectMatch = isPerfectMatch,
    )
  }

  /**
   * 让服务端把选中的在线字幕存进媒体库，返回新的字幕轨索引（可能为 null = 失败）。
   */
  suspend fun downloadRemoteSubtitle(
    server: EmbyServer,
    itemId: String,
    subtitleId: String,
    mediaSourceId: String? = null,
  ): EmbySubtitleDownloadResult = withContext(Dispatchers.IO) {
    EmbyClient.downloadRemoteSubtitle(server, itemId, subtitleId, mediaSourceId)
  }

  /**
   * 取已存到服务器的字幕字节。
   *
   * ⚠️ 返回的是**内存里的字节**而不是落盘文件 —— 落盘与命名由 UI 层的
   * `SubtitleDownloadSaver` 负责（它才知道该放进哪个字幕目录、叫什么名字）。
   */
  suspend fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): ByteArray = withContext(Dispatchers.IO) {
    EmbyClient.downloadSubtitleStream(server, itemId, mediaSourceId, subtitleIndex, format)
  }

  // ─── 收藏 / 删除 / 已看 ───

  suspend fun favorite(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    val data = EmbyClient.setFavorite(server, itemId, true)
    // 记账（见构造参数的说明）：服务端不记时间，客户端现在记
    favoriteClock?.stamp(server.id, listOf(itemId))
    data
  }

  suspend fun unfavorite(server: EmbyServer, itemId: String) = withContext(Dispatchers.IO) {
    val data = EmbyClient.setFavorite(server, itemId, false)
    favoriteClock?.forget(server.id, listOf(itemId))
    data
  }

  /** 加入 / 取消收藏；返回服务器回传的 UserData（含 IsFavorite），调用方据此确认真实状态 */
  suspend fun setFavorite(
    server: EmbyServer,
    itemId: String,
    favorite: Boolean,
  ): EmbyUserData? = withContext(Dispatchers.IO) {
    val data = EmbyClient.setFavorite(server, itemId, favorite)
    if (favorite) {
      favoriteClock?.stamp(server.id, listOf(itemId))
    } else {
      favoriteClock?.forget(server.id, listOf(itemId))
    }
    data
  }

  /**
   * 取消收藏的「用力」版：额外补一次标准 DELETE 方法（见 [EmbyClient.unfavoriteHard]）。
   *
   * 只在回读确认「取消收藏没生效」时作为重试用 —— 部分兼容服务端对
   * `POST .../Delete` 回 200 却不落库，需要标准写法兜底。
   */
  suspend fun unfavoriteHard(server: EmbyServer, itemId: String): EmbyUserData? = withContext(Dispatchers.IO) {
    val data = EmbyClient.unfavoriteHard(server, itemId)
    favoriteClock?.forget(server.id, listOf(itemId))
    data
  }

  /**
   * 删除媒体 —— **物理删除服务器上的文件**（媒体库 + 磁盘），不可逆。
   *
   * 见 [EmbyClient.deleteItem]：需管理员权限，失败会抛 [app.marlboroadvance.mpvex.domain.emby.EmbyApiException]。
   */
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

  /**
   * a8：标记已看 / 未看 **并同时清掉播放进度**，一个请求写完（见 [EmbyClient.setPlayedWithClearPosition]）。
   *
   * 这是「标记已看 / 未看」UI 动作的**主路径**： Emby Web 的同一动作本来就是
   * 两个字段一起写的，旧路径只翻 Played 位、留下陈旧的续播点。
   */
  suspend fun setPlayedWithClearPosition(
    server: EmbyServer,
    itemId: String,
    played: Boolean,
  ): EmbyUserData? = withContext(Dispatchers.IO) {
    EmbyClient.setPlayedWithClearPosition(server, itemId, played)
  }

  // ─── 播放进度上报 ───

  suspend fun reportPlaybackStart(
    server: EmbyServer,
    itemId: String,
    positionTicks: Long = 0,
    /** 多版本媒体：告诉服务器「正在播的是哪一个版本」，服务器控制台的「正在播放」才准 */
    mediaSourceId: String? = null,
  ): String = withContext(Dispatchers.IO) {
    EmbyClient.reportPlaybackStart(server, itemId, positionTicks, mediaSourceId = mediaSourceId)
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
   * 直播电视频道列表（`/LiveTv/Channels`）。
   *
   * 与 [searchItems] 一样走 IO 线程；服务端没启用直播电视时返回空列表。
   */
  suspend fun getLiveTvChannels(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      EmbyClient.getLiveTvChannels(server)
    }

  /**
   * 直播电视节目单（`/LiveTv/Programs`，a2 + c1）。
   *
   * [channelId] 为 null 时拿「所有频道」在窗口内的节目（频道网格的正在播出）；
   * 非 null 时拿单个频道的时间轴。时间参数是 ISO 8601 UTC 字符串。
   * 服务端没有 EPG 数据源时返回空列表。
   */
  suspend fun getLiveTvPrograms(
    server: EmbyServer,
    channelId: String? = null,
    startTime: String,
    endTime: String,
    limit: Int = 500,
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    EmbyClient.getLiveTvPrograms(server, channelId, startTime, endTime, limit)
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

  /**
   * 剧集的季列表。
   *
   * [startIndex] / [limit] 是清单 1.1 补的分页透传；不传时行为与从前一致（一次拿全）。
   */
  suspend fun getSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getSeasons(server, seriesId, startIndex, limit)
  }

  /** 某一季下的剧集列表（分页参数同上，见 [getSeasons]） */
  suspend fun getEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String? = null,
    startIndex: Int = 0,
    limit: Int? = null,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getEpisodes(server, seriesId, seasonId, startIndex, limit)
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
    /** 多版本媒体要转码的那个版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): String = EmbyClient.transcodeStreamUrl(server, itemId, startTimeTicks, mediaSourceId = mediaSourceId)

  /**
   * 多版本媒体：**指定版本**的直连播放地址（先问一次 `PlaybackInfo`）。
   *
   * 为什么不能直接用 [videoStreamUrl]：官方文档把 `PlaySessionId` 与 `MediaSourceId`
   * 一起列为 `/Videos/{Id}/stream` 的必需参数，缺了前者服务器会无视我们选的版本。
   * 详见 [EmbyClient.directStreamUrlForSource]。
   *
   * @return 该版本的地址；拿不到返回 null，调用方回退到 [videoStreamUrl]。
   */
  suspend fun directStreamUrl(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
  ): String? = EmbyClient.directStreamUrlForSource(server, itemId, mediaSourceId)

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
