package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyExternalIdInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyImageProviderInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemLookupInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSearchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyScanChunk
import app.marlboroadvance.mpvex.domain.emby.EmbyScanQuery
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.ui.player.GsyPlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.reversed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/** 全库搜索没选任何筛选时的默认类型集：只保留可播放的视频本体 */
private const val PERSON_TYPE = "Person"

private val GLOBAL_SEARCH_DEFAULT_TYPES =
  listOf("Movie", "Series", "Episode", "Video", "MusicVideo")

/**
 * Emby 模块共享 ViewModel。
 *
 * 负责：当前服务器、媒体库/继续观看/最新加入数据加载、收藏、删除、播放启动。
 * [EmbyRepository] 是 Koin 单例，因此多个页面拿到的当前服务器是一致的。
 */
class EmbyViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val playerPreferences by inject<PlayerPreferences>(PlayerPreferences::class.java)
  private val browserPreferences by inject<BrowserPreferences>(BrowserPreferences::class.java)

  val servers: StateFlow<List<EmbyServer>> = repository.servers
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val currentServer: StateFlow<EmbyServer?> = repository.currentServer

  private val _libraries = MutableStateFlow<List<EmbyItem>>(emptyList())
  val libraries: StateFlow<List<EmbyItem>> = _libraries.asStateFlow()

  private val _resumeItems = MutableStateFlow<List<EmbyItem>>(emptyList())
  val resumeItems: StateFlow<List<EmbyItem>> = _resumeItems.asStateFlow()

  private val _latestItems = MutableStateFlow<List<EmbyItem>>(emptyList())
  val latestItems: StateFlow<List<EmbyItem>> = _latestItems.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  init {
    viewModelScope.launch { refreshHome() }
  }

  // ==================== 服务器管理 ====================

  /**
   * 登录并保存服务器。
   *
   * @return 成功返回保存后的服务器，失败返回 null（错误信息写入 [error]）
   */
  suspend fun loginAndSave(
    name: String,
    host: String,
    port: Int,
    useHttps: Boolean,
    username: String,
    password: String,
  ): EmbyServer? = runCatching {
    repository.loginAndSave(name, host, port, useHttps, username, password)
  }.onFailure { _error.value = it.message ?: "登录失败" }.getOrNull()

  /** 保存已存在的服务器（host/port 填完整 URL 的场景） */
  fun addServer(server: EmbyServer) {
    viewModelScope.launch {
      runCatching { repository.addServer(server) }
        .onFailure { _error.value = it.message ?: "保存失败" }
    }
  }

  fun updateServer(server: EmbyServer) {
    viewModelScope.launch {
      runCatching { repository.updateServer(server) }
        .onFailure { _error.value = it.message ?: "保存失败" }
    }
  }

  fun deleteServer(server: EmbyServer) {
    viewModelScope.launch {
      runCatching { repository.deleteServer(server) }
        .onFailure { _error.value = it.message ?: "删除失败" }
        .onSuccess {
          // 删掉的是当前服务器时，切到剩下的一台
          if (currentServer.value?.id == server.id) {
            val rest = repository.getServers().firstOrNull()
            rest?.let { repository.setCurrentServer(it) }
          }
        }
    }
  }

  fun switchServer(server: EmbyServer) {
    repository.setCurrentServer(server)
    viewModelScope.launch { refreshHome() }
  }

  suspend fun relogin(server: EmbyServer): EmbyServer? =
    runCatching { repository.relogin(server) }
      .onFailure { _error.value = it.message ?: "重新登录失败" }
      .getOrNull()

  // ==================== 数据加载 ====================

  /** 加载首页所需的全部数据：媒体库 + 继续观看 + 最新加入 */
  fun refreshHome() {
    viewModelScope.launch { loadHome() }
  }

  /**
   * [refreshHome] 的可等待版本：下拉刷新要用它 —— 刷新指示器得等这批请求真的回来才收起，
   * 否则手指刚松开动画就没了，用户看不出到底刷没刷。
   */
  suspend fun refreshHomeAndWait() = loadHome()

  private suspend fun loadHome() {
    _isLoading.value = true
    _error.value = null
    // 冷启动时服务器是异步恢复的，这里必须等它恢复完，否则会误判成「没有服务器」
    val server = repository.ensureLoggedIn(repository.awaitCurrentServer())
    if (server == null) {
      _libraries.value = emptyList()
      _resumeItems.value = emptyList()
      _latestItems.value = emptyList()
      _isLoading.value = false
      return
    }
    runCatching {
      val libs = repository.getVirtualFolders(server)
      val resume = repository.getResumeItems(server, limit = 20)
      val latest = repository.getLatestItems(server, limit = 24)
      Triple(libs, resume, latest)
    }.onSuccess { (libs, resume, latest) ->
      _libraries.value = libs
      _resumeItems.value = resume
      _latestItems.value = latest
    }.onFailure {
      _error.value = it.message ?: "加载失败"
    }
    _isLoading.value = false
  }

  /**
   * 取当前服务器；若启动恢复尚未完成则先等待。
   *
   * 各个二级页面（媒体库/收藏/历史/详情）的首屏加载都应使用它，
   * 直接用 [currentServer] 的 value 会在冷启动时拿到 null 而静默不加载。
   */
  suspend fun currentServerOrAwait(): EmbyServer? =
    repository.ensureLoggedIn(repository.awaitCurrentServer())

  /** 加载指定父级下的媒体（供库浏览页使用） */
  suspend fun loadItems(
    server: EmbyServer,
    parentId: String?,
    includeItemTypes: List<String>? = null,
    filters: List<String>? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
    excludeItemTypes: List<String>? = null,
    /** 类型筛选，多选取并集（Emby 的 Genres 参数） */
    genres: List<String>? = null,
    /** 发行年份筛选，多选取并集 */
    years: List<Int>? = null,
    /** 最低社区评分，例如 7f 表示只要 7 分以上 */
    minCommunityRating: Float? = null,
    /** 标签筛选，多选取并集 */
    tags: List<String>? = null,
    /** 官方分级筛选，多选取并集 */
    officialRatings: List<String>? = null,
    /** true 只返回收藏，false 只返回未收藏，null 不限 */
    isFavorite: Boolean? = null,
    /** 只返回指定 MediaType（Video / Audio / Photo / Book）的条目；null 不限 */
    mediaTypes: List<String>? = null,
    /** false = 只要能直接播放的媒体本体，容器（Folder / Series / Season / BoxSet）全不要 */
    isFolder: Boolean? = null,
    /** 演员 / 导演的 PersonId 列表，多选取并集 */
    personIds: List<String>? = null,
    /** true 只要已看，false 只要未看，null 不限 */
    isPlayed: Boolean? = null,
    /** true 只要高清，false 只要标清 */
    isHD: Boolean? = null,
    /** true 只要 3D，false 排除 3D */
    is3D: Boolean? = null,
    /** true 只要有字幕，false 只要没字幕 */
    hasSubtitles: Boolean? = null,
    /** 工作室 Id 列表，多选取并集 */
    studioIds: List<String>? = null,
  ): EmbyItemsPage {
    val result = repository.getItems(
      server = server,
      parentId = parentId,
      sortBy = sortBy,
      sortOrder = sortOrder,
      filters = filters,
      includeItemTypes = includeItemTypes,
      startIndex = startIndex,
      limit = limit,
      recursive = recursive,
      excludeItemTypes = excludeItemTypes,
      genres = genres,
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
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  /**
   * 全量扫描：客户端筛选（如按路径判断的「中文字幕」）需要遍历整个库时用。
   *
   * 逐页拉完 [query] 命中的全部条目，每页回调一次 [onChunk] —— 回调在 IO 线程上执行，
   * 里面做正则过滤这类纯 CPU 活不会卡 UI；写 Compose 状态记得切回主线程。
   * 调用方所在协程被取消（换筛选条件 / 退出页面）时，扫描会在一页之内停下。
   */
  suspend fun scanItems(
    server: EmbyServer,
    query: EmbyScanQuery,
    onChunk: suspend (EmbyScanChunk) -> Unit,
  ): Int = repository.scanItems(server, query, onChunk = onChunk)

  /** 取某媒体库的筛选可选项（类型 / 标签 / 年份 / 分级），供筛选面板使用。 */
  suspend fun loadFilterOptions(
    server: EmbyServer,
    parentId: String?,
  ): EmbyFilterOptions = withContext(Dispatchers.IO) {
    runCatching { repository.getFilterOptions(server, parentId) }
      .getOrDefault(EmbyFilterOptions())
  }

  suspend fun loadSeasons(server: EmbyServer, seriesId: String): List<EmbyItem> =
    repository.getSeasons(server, seriesId).Items

  suspend fun loadEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String?,
  ): List<EmbyItem> = repository.getEpisodes(server, seriesId, seasonId).Items

  suspend fun loadFavorites(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsPage {
    val result = repository.getFavoriteItems(server, startIndex, limit)
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  suspend fun loadHistory(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 60,
  ): EmbyItemsPage {
    val result = repository.getPlayedHistory(server, limit, startIndex)
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  suspend fun loadResume(server: EmbyServer, limit: Int = 60): List<EmbyItem> =
    repository.getResumeItems(server, limit)

  suspend fun loadRandom(
    server: EmbyServer,
    parentId: String? = null,
    includeItemTypes: List<String>? = null,
    limit: Int = 50,
    isFavorite: Boolean? = null,
  ): List<EmbyItem> = repository.getRandomItems(server, parentId, includeItemTypes, limit, isFavorite).Items

  suspend fun loadItemDetail(server: EmbyServer, itemId: String): EmbyItem =
    repository.getItem(server, itemId)

  /**
   * 库内搜索。
   *
   * [sortBy] / [sortOrder] 由媒体库工具行上的排序按钮给出：Emby 允许
   * SearchTerm 与 SortBy 组合，所以搜索完再切排序能真的改变结果顺序。
   * `sortBy = "Random"` 就是「在搜索结果里随机播」用的。
   */
  suspend fun search(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>? = null,
    sortBy: String? = "SortName",
    sortOrder: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> {
    // 演员必须走 /Persons：`/Users/{id}/Items` 不返回 Person，
    // 勾了「演员」筛选却搜不到人就是因为原先二者混在同一条请求里。
    val persons = searchPersons(server, term, limit, itemTypes)
    val mediaTypes = itemTypes?.filter { it != PERSON_TYPE }?.takeIf { it.isNotEmpty() }
    // 只勾了「演员」：媒体那一路就不用查了
    if (itemTypes != null && mediaTypes == null) return persons
    val media = runCatching {
      repository.searchItems(server, term, mediaTypes, limit, sortBy, sortOrder).Items
    }.getOrDefault(emptyList())
    return persons + media
  }

  /**
   * 演员走 `/Persons`：勾了「演员」筛选时才发这一路，否则不打扰服务端。
   *
   * 两层兜底：
   * 1. 先按名字问服务端 `/Persons?SearchTerm`，有结果直接用 —— 这是主路径，
   *    快且准，官方 Emby 与绝大多数兼容服务端都支持；
   * 2. 拿不到就退回「拉一份全量演员名单、在本地按名字做包含匹配」。
   *    这一层是给「服务端不认 `SearchTerm` 或匹配口径对不上」的兼容层准备的保险。
   *    全量名单按服务器缓存（见 [actorsCache]），所以用户一个字一个字地打，
   *    也只会产生一次全量请求。
   *
   * 注意：媒体库内搜不到演员**另有原因**（列表那层「是否可播放」的本地兜底把
   * Person 剔掉了，见 EmbyLibraryScreen 的 visibleItems），不在这里。
   */
  private suspend fun searchPersons(
    server: EmbyServer,
    term: String,
    limit: Int,
    itemTypes: List<String>?,
  ): List<EmbyItem> {
    if (itemTypes != null && PERSON_TYPE !in itemTypes) return emptyList()
    val direct = runCatching { repository.searchPersons(server, term, null, limit) }
      .getOrDefault(emptyList())
    if (direct.isNotEmpty()) return direct
    return allActors(server)
      .filter { person -> person.Name?.contains(term, ignoreCase = true) == true }
      .take(limit)
  }

  /** 全量演员名单的缓存：serverId → 名单。同一台服务器只拉一次。 */
  private var actorsCache: Pair<Long, List<EmbyItem>>? = null

  private suspend fun allActors(server: EmbyServer): List<EmbyItem> {
    actorsCache?.let { (cachedId, list) -> if (cachedId == server.id) return list }
    val list = withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getActors(server, null) }.getOrDefault(emptyList())
    }
    if (list.isNotEmpty()) actorsCache = server.id to list
    return list
  }

  /**
   * 首页的「全库搜索」：不传 ParentId，Emby 会跨所有媒体库检索。
   *
   * 和库内搜索 [search] 用的是同一个 /Items?SearchTerm 接口，区别只在两点：
   * 不传 ParentId（所以覆盖全部库），以及**默认**限定只看可播放类型
   * ——否则文件夹、合集这些容器会混进结果里，点进去还要再下钻一层。
   *
   * [itemTypes] 由搜索框下方的筛选条给出：用户显式选了「合集」「演员」这类时，
   * 就按他选的类型查，不再套用上面的默认值。
   */
  suspend fun searchGlobal(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>? = null,
  ): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      // 与 [search] 同理：Person 只能从 `/Persons` 拿，`/Items` 那条端点搜不出人；
      // 并且这里同样带上「服务端不认 SearchTerm 就本地过滤」的兜底
      val persons = searchPersons(server, term, 100, itemTypes)
      val mediaTypes = itemTypes?.filter { it != PERSON_TYPE }?.takeIf { it.isNotEmpty() }
        ?: GLOBAL_SEARCH_DEFAULT_TYPES.takeIf { itemTypes == null }
      val media = if (mediaTypes.isNullOrEmpty()) {
        emptyList()
      } else {
        runCatching {
          repository.searchItems(
            server = server,
            term = term,
            includeItemTypes = mediaTypes,
            limit = 100,
          ).Items
        }.getOrDefault(emptyList())
      }
      persons + media
    }


  // ==================== 媒体操作 ====================

  /**
   * 切换收藏。
   *
   * 返回 [Result]：成功时携带**切换后**的状态（true = 已收藏），失败时携带异常，
   * 由调用方决定提示文案与是否回滚本地状态 —— 详情页要据此弹 Toast，
   * 所以这里不自己吞掉错误（内部同步写 [_error] 供其他观察者使用）。
   */
  /**
   * 切换收藏。返回 [Result]，值 = 切换后的收藏状态。
   *
   * **成败以服务器回传的 UserData 为准**：这条接口的响应体本身就是最新的
   * `{"IsFavorite":true/false,...}`，直接采信它最可靠 ——
   * 之前用「再拉一次条目」回查，而详情接口在这台服务器上返回的 Content-Length
   * 比实际 body 长，OkHttp 读不满会抛异常，回查一失败就把「其实成功了」误报成失败。
   */
  suspend fun toggleFavorite(server: EmbyServer, item: EmbyItem): Result<Boolean> {
    val itemId = item.Id ?: return Result.failure(IllegalArgumentException("缺少条目 Id"))
    val target = item.UserData?.IsFavorite != true
    val result = runCatching {
      val data = repository.setFavorite(server, itemId, target)
      // 只有服务器明确回「还是没收藏」才算失败；拿不到 UserData 时不下失败结论
      if (data?.IsFavorite == false && target) throw IllegalStateException("服务器未接受这次收藏")
      target
    }
    result.exceptionOrNull()?.let { _error.value = it.message ?: "操作失败" }
    return result
  }

  fun deleteItem(server: EmbyServer, itemId: String) {
    viewModelScope.launch {
      runCatching { repository.deleteItem(server, itemId) }
        .onSuccess {
          // 媒体库列表的进程内缓存同步剔除：返回媒体库时不再渲染已删除的条目
          EmbyLibraryCache.removeItem(itemId)
          _error.value = null
          android.widget.Toast
            .makeText(getApplication(), "已删除", android.widget.Toast.LENGTH_SHORT)
            .show()
          refreshHome()
        }
        .onFailure {
          _error.value = it.message ?: "删除失败"
          android.widget.Toast
            .makeText(
              getApplication(),
              "删除失败：${it.message ?: "未知错误"}",
              android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
  }

  /**
   * 标记已播放 / 未播放，并把结果返回给调用方（详情页据此弹 Toast、失败时回滚 UI）。
   *
   * 这里刻意不做「内部 launch 后不管」：那样调用方无从得知成败，
   * 用户点了右上角的勾却没有任何反馈，服务端失败时也看不出来。
   */
  /** 标记已看 / 未看。同 [toggleFavorite]：以服务器回传的 UserData 为准。 */
  suspend fun setPlayed(server: EmbyServer, itemId: String, played: Boolean): Result<Unit> {
    val result = runCatching {
      val data = repository.setPlayed(server, itemId, played)
      // 只有服务器明确回「状态和我要的不一样」才算失败；拿不到 UserData 时不下失败结论
      if (data?.Played == !played) throw IllegalStateException("服务器未接受这次修改")
    }
    result.exceptionOrNull()?.let { _error.value = it.message ?: "操作失败" }
    return result
  }

  // ==================== 播放 ====================

  /**
   * 启动播放器播放 Emby 媒体。
   *
   * 剧集（Episode）会自动把同季后续剧集一起交给播放器作为播放列表，
   * 从而在播完当前集后自动接着播下一集。
   *
   * @param resumeSeconds >0 时从指定秒数续播，否则从头开始
   * @param reverseEngine true = 用「与默认相反」的内核播放（详情页长按播放按钮）
   */
  suspend fun play(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long = 0,
    reverseEngine: Boolean = false,
  ) {
    val itemId = item.Id ?: return
    val engine = resolveEngine(reverseEngine)

    // 剧集：构建从当前集开始的播放列表，实现"播完自动下一集"
    val playlist = if (item.Type == "Episode") {
      buildEpisodePlaylist(server, item)
    } else {
      emptyList()
    }

    if (playlist.size > 1) {
      launchPlaylist(server, playlist, resumeSeconds, engine = engine)
    } else {
      launchSingle(server, item, resumeSeconds, engine = engine)
    }

    // 上报播放开始，让服务器记录"正在播放"
    viewModelScope.launch {
      runCatching {
        repository.reportPlaybackStart(
          server = server,
          itemId = itemId,
          positionTicks = EmbyTicks.secondsToTicks(resumeSeconds),
        )
      }
    }
  }

  /**
   * 用**服务器转码**后的流播放当前媒体。
   *
   * 【什么时候用它】默认的 [play] 是「直连原文件」—— 最快、服务器零负担，但能不能播
   * 全看本机解不解得了这个编码。下面这两类源一定会失败，换内核也没用：
   *
   *  · 远程 STRM（`.strm` 里指向一个远端地址，源文件是 AVI）；
   *  · AVI + Xvid / DivX（MPEG-4 ASP）等老编码 —— 手机的硬解不出画面、软解又跟不上。
   *
   * 这时让服务器用 ffmpeg 转成 H.264 + AAC 再下发，客户端拿到的就是最普通的流。
   * 代价是**服务器要跑实时转码**（CPU 占用明显上升），所以做成显式入口、不自动触发。
   *
   * 与 [playWithExternalPlayer] 一样返回 [Result]：失败原因（转码被禁用、服务器连不上）
   * 要能原样说给用户听，闷在那儿只会让人以为按钮坏了。
   */
  suspend fun playTranscoded(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long = 0,
    reverseEngine: Boolean = false,
  ): Result<Unit> {
    val itemId = item.Id ?: return Result.failure(IllegalArgumentException("缺少媒体 Id"))
    return runCatching {
      val url =
        repository.transcodeStreamUrl(
          server = server,
          itemId = itemId,
          startTimeTicks = EmbyTicks.secondsToTicks(resumeSeconds),
        )
      launchSingle(
        server = server,
        item = item,
        resumeSeconds = resumeSeconds,
        engine = resolveEngine(reverseEngine),
        overrideUrl = url,
      )
    }
  }

  /**
   * 列出本机能打开这条流的**外部播放器**候选（已剔除自家两个播放页）。
   *
   * 枚举规则见 [queryExternalPlayers] —— 关键在于**不能只查一次带 URI 的 intent**，
   * 否则只声明了 `file` / `content` 的播放器会因为 scheme 对不上而全部消失。
   */
  fun listExternalPlayers(server: EmbyServer, item: EmbyItem): Result<List<ExternalPlayerOption>> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val players =
      queryExternalPlayers(
        context = app,
        uri = externalStreamUri(server, itemId),
        selfPackageName = app.packageName,
      )
    return if (players.isEmpty()) {
      Result.failure(IllegalStateException("手机里没有找到能播放视频的外部播放器"))
    } else {
      Result.success(players)
    }
  }

  /** 上次用过的外部播放器（`包名/Activity名`，空串 = 还没用过） */
  fun lastExternalPlayerKey(): String = browserPreferences.embyExternalPlayer.get()

  /**
   * 用**指定的外部播放器**打开当前媒体。
   *
   * 与 [play] 的区别：那条走的是 App 自带的两个内核（mpv / GSY），这条把流地址交给
   * 系统里别的播放器（MX、VLC、各类投屏 App…）。用的是静态直链（`static=true`），
   * 拿到 URL 的播放器自己拉流，完全不依赖我们的播放页。
   *
   * 两点取舍：不带续播位置（外部播放器不认我们的 `position` extra，硬塞没用），
   * 这条链路的进度也不会回传到 Emby 服务器。
   */
  fun playWithExternalPlayer(
    server: EmbyServer,
    item: EmbyItem,
    player: ExternalPlayerOption,
  ): Result<Unit> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val intent =
      externalPlayerLaunchIntent(
        uri = externalStreamUri(server, itemId),
        option = player,
        title = displayTitle(item),
      )
    return runCatching {
      app.startActivity(intent)
      // 记住这次的选择：下次列表里那一项会打上勾，并排到已知播放器里的原位置
      browserPreferences.embyExternalPlayer.set(player.key)
    }
  }

  /**
   * 兜底：交给系统自己的选择器。候选同样是剔除自家之后的那批，
   * 所以系统选择器里也不会冒出影屿自己。
   */
  fun playWithExternalPlayerChooser(server: EmbyServer, item: EmbyItem): Result<Unit> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val chooser =
      externalPlayerChooserIntent(
        context = app,
        uri = externalStreamUri(server, itemId),
        selfPackageName = app.packageName,
        title = displayTitle(item),
      )
        ?: return Result.failure(IllegalStateException("手机里没有找到能播放视频的外部播放器"))
    return runCatching { app.startActivity(chooser) }
  }

  /**
   * 交给外部播放器的直链。
   *
   * `static=true` = 服务器原样吐原文件，一个字节都不改 —— 外部播放器自带解码器，
   * 用不着服务器帮它转码。
   */
  private fun externalStreamUri(server: EmbyServer, itemId: String): Uri =
    Uri.parse(repository.videoStreamUrl(server, itemId, static = true))

  /**
   * 取同季剧集中「当前集及其之后」的列表；失败时只返回当前集。
   */
  private suspend fun buildEpisodePlaylist(
    server: EmbyServer,
    item: EmbyItem,
  ): List<EmbyItem> {
    val seriesId = item.SeriesId ?: return listOf(item)
    val episodes = runCatching { repository.getEpisodes(server, seriesId, item.SeasonId).Items }
      .getOrNull()
      ?: return listOf(item)
    if (episodes.isEmpty()) return listOf(item)

    val index = episodes.indexOfFirst { it.Id == item.Id }
    return if (index >= 0) episodes.subList(index, episodes.size) else listOf(item)
  }

  /**
   * 算出这次播放**实际要用**的内核。
   *
   * 默认就是设置里选的「默认播放内核」（设置 → GSY 播放器 → 默认播放内核），
   * 长按（[reverse]）且「长按反选内核」开关打开时才反选另一个。
   *
   * 注意这里必须返回「默认内核」而不是 null：返回 null 的话调用方只会把 intent
   * 指向 mpv 播放页，「默认内核 = GSYVideoPlayer」这个设置就形同虚设
   * （用户反馈的「设置里选了 GSY，播放起来还是 mpv」就是这个原因）。
   */
  fun resolveEngine(reverse: Boolean): EngineKind {
    val preferred = EngineKind.from(playerPreferences.playbackEngine.get())
    if (!reverse || !playerPreferences.longPressReverseEngine.get()) return preferred
    return preferred.reversed()
  }

  /**
   * 只有「真的被反选了」才返回非空。
   *
   * 详情页拿它决定要不要弹「用 xx 播放」的提示 —— 每次点播放都弹一条就太吵了，
   * 长按换内核才需要明确反馈。
   */
  fun resolveEngineOverride(reverse: Boolean): EngineKind? {
    if (!reverse || !playerPreferences.longPressReverseEngine.get()) return null
    return resolveEngine(reverse)
  }

  /**
   * 把内核选择写进 intent。
   *
   * 备用内核（GSY）走独立的 [GsyPlayerActivity]；
   * 主内核（mpv）则显式把内核名塞进 extra，让 [PlayerActivity] 明确按 mpv 播 ——
   * 免得「设置里默认内核 = GSY」时又被路由走一次。
   */
  private fun Intent.putEngine(engine: EngineKind?) {
    when (engine) {
      EngineKind.GSY -> setClass(getApplication(), GsyPlayerActivity::class.java)
      EngineKind.MPV -> putExtra(PlayerActivity.EXTRA_ENGINE, engine.name)
      null -> Unit
    }
  }

  /** 播放单个媒体 */
  private fun launchSingle(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long,
    engine: EngineKind? = resolveEngine(false),
    /**
     * 覆盖播放地址。null = 默认的「直连原文件」（`static=true`，服务器不动数据）。
     * 传值只有一条来源：[playTranscoded] 拿到的服务端转码地址。
     */
    overrideUrl: String? = null,
  ) {
    val itemId = item.Id ?: return
    val url = overrideUrl ?: repository.videoStreamUrl(server, itemId, static = true)
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
      setClass(getApplication(), PlayerActivity::class.java)
      putExtra("internal_launch", true)
      putExtra("launch_source", "emby")
      putExtra("title", displayTitle(item))
      putExtra("filename", item.Name ?: displayTitle(item))
      if (resumeSeconds > 0) {
        putExtra("position", (resumeSeconds * 1000).toInt())
      } else {
        // 显式「从头播放」：告知播放页别再拿本地续播记录覆盖进度。
        // 否则 savePositionOnQuit 开启时，applyPlaybackState 会把上次停下
        // 的位置套回 time-pos，「从头播放」就变成了「继续播放」。
        putExtra("play_from_start", true)
      }
      // 供播放页把进度回传给 Emby 服务器
      putEmbyPlaybackExtras(server, listOf(itemId))
      // 「记忆播放设置」的键：剧集用所属剧的 Id（整季共用一份速度 / 音轨），
      // 电影没有 SeriesId，就用它自己的 Id。
      putExtra("emby_series_key", item.SeriesId ?: itemId)
      putEngine(engine)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    getApplication<Application>().startActivity(intent)
  }

  /** 播放一组媒体（剧集连播 / 随机播放） */
  fun launchPlaylist(
    server: EmbyServer,
    items: List<EmbyItem>,
    resumeSeconds: Long = 0,
    /**
     * 整场从头播放（随机播放 / 随机播放收藏专用）。
     * 随机出来的列表里常混着看过的剧，若切集时恢复本地续播记录，
     * 已看完的会直接跳到片尾一秒就结束，所以本次会话内每个视频都从头放。
     */
    playFromStartAll: Boolean = false,
    /**
     * 指定内核；默认取设置里的「默认播放内核」（长按反选时由 [resolveEngine] 给出）
     */
    engine: EngineKind? = resolveEngine(false),
  ) {
    val uris = ArrayList<android.net.Uri>()
    val ids = ArrayList<String>()
    // 与 uris 下标一一对应的显示标题。播放器侧切集时用它，避免从
    // `/Videos/{id}/stream` 这种 URL 里猜出统一是 "stream" 的假标题。
    val titles = ArrayList<String>()
    // 与 uris 下标一一对应的「记忆键」：剧集用 SeriesId（整季共用一份速度 / 音轨），
    // 电影没有 SeriesId 就用自身 Id。播放器切集时按下标取，实现「同剧继承」。
    val seriesKeys = ArrayList<String>()
    items.forEach { item ->
      val id = item.Id ?: return@forEach
      uris.add(android.net.Uri.parse(repository.videoStreamUrl(server, id, static = true)))
      ids.add(id)
      titles.add(displayTitle(item))
      seriesKeys.add(item.SeriesId ?: id)
    }
    if (uris.isEmpty()) return

    val first = items.first()
    val intent = Intent(Intent.ACTION_VIEW, uris.first()).apply {
      setClass(getApplication(), PlayerActivity::class.java)
      putExtra("internal_launch", true)
      putExtra("launch_source", "emby")
      putExtra("title", displayTitle(first))
      putExtra("filename", first.Name ?: displayTitle(first))
      if (resumeSeconds > 0) {
        putExtra("position", (resumeSeconds * 1000).toInt())
      } else {
        // 显式「从头播放」：告知播放页别再拿本地续播记录覆盖进度。
        // 否则 savePositionOnQuit 开启时，applyPlaybackState 会把上次停下
        // 的位置套回 time-pos，「从头播放」就变成了「继续播放」。
        putExtra("play_from_start", true)
      }
      // 随机播放入口：整个播放会话内切到哪个视频都从头放（不恢复本地续播）
      if (playFromStartAll) {
        putExtra("play_from_start_all", true)
      }
      putParcelableArrayListExtra("playlist", uris)
      putStringArrayListExtra("playlist_titles", titles)
      putStringArrayListExtra("playlist_series_keys", seriesKeys)
      putExtra("playlist_index", 0)
      // 播放列表的 ID 顺序与 uris 一致，切集时据此把"正在播放"同步给服务器
      putEmbyPlaybackExtras(server, ids)
      putEngine(engine)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    getApplication<Application>().startActivity(intent)
  }

  /** 显示用的标题：剧集用「剧集名 S01E05」这类格式 */
  fun displayTitle(item: EmbyItem): String {
    val name = item.Name ?: return ""
    val season = item.ParentIndexNumber
    val episode = item.IndexNumber
    return if (item.Type == "Episode" && season != null && episode != null) {
      "S%02dE%02d %s".format(season, episode, name)
    } else {
      name
    }
  }

  /** 构造播放地址（用于播放列表等只需要 URL 的场景） */
  fun getStreamUrl(server: EmbyServer, itemId: String, static: Boolean = true): String =
    repository.videoStreamUrl(server, itemId, static)

  fun imageUrl(server: EmbyServer, item: EmbyItem, imageType: String = "Primary", maxWidth: Int = 480): String? {
    val id = item.Id ?: return null
    val tag = when (imageType) {
      "Backdrop" -> item.BackdropImageTags.firstOrNull()
      else -> item.ImageTags[imageType]
    }
    return repository.imageUrl(server, id, imageType, tag, maxWidth)
  }

  /**
   * 某媒体库的演员列表（媒体库「演员」分类用）。
   *
   * 走 Emby 的 /Persons 端点，IO 线程执行 —— 库大时是几十上百个人物的网络请求。
   */
  suspend fun getActors(server: EmbyServer, parentId: String?): List<EmbyItem> =
    withContext(Dispatchers.IO) { EmbyClient.getActors(server, parentId) }

  /**
   * 数每位演员在该库参与的作品数（PersonId → 条数）。
   *
   * /Persons 拿不到这个数（Fields 不支持 ChildCount），只能逐页把库拉一遍自己算，
   * 所以调用方要放在后台、别挡首屏。详见 [EmbyClient.getPersonWorkCounts]。
   */
  suspend fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    withContext(Dispatchers.IO) { EmbyClient.getPersonWorkCounts(server, parentId) }

  /** 刷新 / 刮削元数据。full=true 走全量重刮（FullRefresh），否则默认刷新（Default）。 */
  suspend fun refreshMetadata(
    server: EmbyServer,
    itemId: String,
    full: Boolean = false,
    replaceAllMetadata: Boolean = false,
    replaceAllImages: Boolean = false,
  ): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        EmbyClient.refreshItem(
          server = server,
          itemId = itemId,
          mode = if (full) "FullRefresh" else "Default",
          replaceAllMetadata = replaceAllMetadata,
          replaceAllImages = replaceAllImages,
        )
      }.isSuccess
    }

  /**
   * 「刷新元数据」：全量重刮 + 可选的强制覆盖。
   *
   * 与 [scanLibrary] 的区别是这边走 FullRefresh，会把片名 / 简介 / 演职员 / 图片重新刮一遍；
   * [replaceAllMetadata] / [replaceAllImages] 决定「已有的要不要一起换掉」——
   * 不勾时 Emby 只补缺失字段，已锁定的本地元数据会被保留。
   *
   * 同样是异步任务：返回 true 只代表服务器接受了请求，实际刮削要跑一阵子。
   */
  suspend fun refreshLibraryMetadata(
    server: EmbyServer,
    itemId: String,
    replaceAllMetadata: Boolean = false,
    replaceAllImages: Boolean = false,
  ): Boolean =
    refreshMetadata(
      server = server,
      itemId = itemId,
      full = true,
      replaceAllMetadata = replaceAllMetadata,
      replaceAllImages = replaceAllImages,
    )

  /**
   * 扫描媒体库 / 文件夹：等价于 Emby Web 端库菜单里的「扫描媒体库」。
   *
   * 走的还是官方那条 `POST /Items/{id}/Refresh`（Recursive=true），和 [refreshMetadata]
   * 是同一个接口 —— 差别只在语义：这里要的是「让服务器重新读一遍目录」，
   * 新拷进去的文件会入库、删掉的会清掉，而不是为了重刮海报简介。
   *
   * **它是异步任务**：接口立刻返回，服务器在后台慢慢扫，所以返回 true 只代表
   * 「服务器接受了」，不代表已经扫完 —— 列表不会自己变，结果要等下拉刷新才看得到。
   *
   * @param full false = 默认刷新（只处理新增 / 变更，快）；true = 全量重刮（连元数据带图片一起重来，慢）
   */
  suspend fun scanLibrary(server: EmbyServer, itemId: String, full: Boolean = false): Boolean =
    refreshMetadata(server, itemId, full)

  /** 编辑（更新）条目元数据：传入「带修改后的完整 item」整体 PUT 给服务器。 */
  suspend fun updateItemMetadata(server: EmbyServer, item: EmbyItem): Boolean =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.updateItem(server, item) }.isSuccess
    }

  // ══════════════════════════════════════════════════════════════════════
  // 图片管理（长按菜单 → 编辑图片）
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 重新拉一次条目。换图 / 删图后要靠它拿**新的图片 tag** ——
   * 图片地址里带着 tag，tag 不变地址就不变，图片库会直接把旧图从缓存里翻出来，
   * 表现就是「抽屉里还是老图，关掉再进才更新」。
   */
  suspend fun loadItem(server: EmbyServer, itemId: String): EmbyItem? =
    withContext(Dispatchers.IO) {
      runCatching { repository.getItem(server, itemId) }.getOrNull()
    }

  /** 条目当前挂着的图片清单（含尺寸与来源，即「图片源数据」） */
  suspend fun loadItemImages(server: EmbyServer, itemId: String): List<EmbyImageInfo> =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.getItemImages(server, itemId) }.getOrDefault(emptyList())
    }

  suspend fun deleteItemImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    index: Int = 0,
  ): Boolean = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.deleteItemImage(server, itemId, imageType, index) }.isSuccess
  }

  /** 本地换图：[mime] 必须是图片类型（image/jpeg / image/png …），否则服务器会拒 */
  suspend fun uploadItemImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    bytes: ByteArray,
    mime: String,
  ): Boolean = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.uploadItemImage(server, itemId, imageType, bytes, mime) }.isSuccess
  }

  suspend fun loadRemoteImageProviders(
    server: EmbyServer,
    itemId: String,
  ): List<EmbyImageProviderInfo> = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.getRemoteImageProviders(server, itemId) }.getOrDefault(emptyList())
  }

  /** 从元数据源搜某类图片；[providerName] 为空表示「所有图源」 */
  /** 同样返回 [Result]：空图和「请求失败」要分开提示，否则排查起来没有头绪 */
  suspend fun searchRemoteImages(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    providerName: String? = null,
  ): Result<List<EmbyRemoteImageInfo>> = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.searchRemoteImages(server, itemId, imageType, providerName) }
  }

  suspend fun downloadRemoteImage(
    server: EmbyServer,
    itemId: String,
    imageType: String,
    imageUrl: String,
    providerName: String? = null,
  ): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.downloadRemoteImage(server, itemId, imageType, imageUrl, providerName)
    }.isSuccess
  }

  // ══════════════════════════════════════════════════════════════════════
  // 识别 / 刮削（长按菜单 → 刮削元数据）
  // ══════════════════════════════════════════════════════════════════════

  /** 条目已绑定的外部 ID（Imdb / Tmdb / Tvdb），用于预填识别对话框 */
  suspend fun loadExternalIdInfos(
    server: EmbyServer,
    itemId: String,
  ): List<EmbyExternalIdInfo> = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.getExternalIdInfos(server, itemId) }.getOrDefault(emptyList())
  }

  /**
   * 远程检索元数据。
   *
   * 返回 [Result] 而不是「失败就给空列表」：空结果和「请求出错」对用户是两回事 ——
   * 前者是真的没匹配上，后者多半是权限 / 图源没配，提示文案必须能区分开。
   */
  suspend fun remoteSearchMetadata(
    server: EmbyServer,
    searchType: String,
    lookup: EmbyItemLookupInfo,
    itemId: String? = null,
  ): Result<List<EmbyRemoteSearchResult>> = withContext(Dispatchers.IO) {
    runCatching { EmbyClient.remoteSearch(server, searchType, lookup, itemId) }
  }

  suspend fun applyRemoteSearchResult(
    server: EmbyServer,
    itemId: String,
    result: EmbyRemoteSearchResult,
    replaceAllImages: Boolean = false,
  ): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.applyRemoteSearch(server, itemId, result, replaceAllImages)
    }.isSuccess
  }

  /**
   * 按演员 / 导演查作品：走 Emby 的 PersonIds 过滤。
   *
   * [includeItemTypes] 放在最后且带默认值，所以既有调用不受影响 ——
   * 详情页的「推荐」只要正片（Movie / Series），不想混进单集和 MV，就单独传一个窄一点的集合。
   */
  suspend fun loadPersonItems(
    server: EmbyServer,
    personId: String,
    startIndex: Int = 0,
    limit: Int = 60,
    includeItemTypes: List<String> = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.getItems(
        server = server,
        personIds = listOf(personId),
        includeItemTypes = includeItemTypes,
        sortBy = "SortName",
        sortOrder = "Ascending",
        recursive = true,
        startIndex = startIndex,
        limit = limit,
      ).Items
    }.getOrDefault(emptyList())
  }

  /**
   * 按「类型」查作品：走 Emby 的 Genres 过滤。
   *
   * 详情页点类型 chip 进来，和 [searchGlobal] 一样不传 ParentId —— 全库范围检索，
   * 只限定可播放类型，避免把 Folder / 合集这类容器混进结果。
   */
  suspend fun loadGenreItems(
    server: EmbyServer,
    genre: String,
    limit: Int = 120,
  ): List<EmbyItem> = loadItemsByFacet(server, genres = listOf(genre), limit = limit)

  /** 按「标签」查作品：走 Emby 的 Tags 过滤，规则同 [loadGenreItems]。 */
  suspend fun loadTagItems(
    server: EmbyServer,
    tag: String,
    limit: Int = 120,
  ): List<EmbyItem> = loadItemsByFacet(server, tags = listOf(tag), limit = limit)

  /** [loadGenreItems] / [loadTagItems] 的公共实现：只差 Genres / Tags 两个参数。 */
  private suspend fun loadItemsByFacet(
    server: EmbyServer,
    genres: List<String>? = null,
    tags: List<String>? = null,
    limit: Int = 120,
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.getItems(
        server = server,
        genres = genres,
        tags = tags,
        includeItemTypes = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
        sortBy = "SortName",
        sortOrder = "Ascending",
        recursive = true,
        startIndex = 0,
        limit = limit,
      ).Items
    }.getOrDefault(emptyList())
  }

  /**
   * 取文件夹内若干子项的封面图 URL，用于给「文件夹」条目拼多宫格封面。
   *
   * Emby 的 Folder / CollectionFolder / UserView 这类容器条目自身没有 Primary 图，
   * 直接请求图片接口会 404（也就是用户看到的「文件夹拿不到封面」）。
   * 这里改为查一次文件夹内部的前 N 个可播放子项，取它们的缩略图交给客户端拼宫格。
   *
   * 优先 Thumb（横版缩略图，宫格排布更协调），没有则退回 Primary。
   * 取不到（空文件夹 / 网络失败）时返回空列表，卡片会退回默认占位图标。
   *
   * @param count 最多取几张，宫格用 4 张
   */
  suspend fun loadFolderCoverUrls(
    server: EmbyServer,
    folderId: String,
    count: Int = 4,
  ): List<String> = runCatching {
    val page = repository.getItems(
      server = server,
      parentId = folderId,
      sortBy = "SortName",
      sortOrder = "Ascending",
      includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
      startIndex = 0,
      limit = count,
      recursive = true,
    )
    page.Items.mapNotNull { child ->
      val id = child.Id ?: return@mapNotNull null
      val thumb = child.ImageTags["Thumb"]
      val primary = child.ImageTags["Primary"]
      when {
        thumb != null -> repository.imageUrl(server, id, "Thumb", thumb, maxWidth = 320)
        primary != null -> repository.imageUrl(server, id, "Primary", primary, maxWidth = 320)
        else -> null
      }
    }
  }.getOrDefault(emptyList())

  fun clearError() {
    _error.value = null
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyViewModel(application) }
    }
  }
}

/** 分页加载结果 */
data class EmbyItemsPage(
  val items: List<EmbyItem>,
  val totalCount: Int,
)
