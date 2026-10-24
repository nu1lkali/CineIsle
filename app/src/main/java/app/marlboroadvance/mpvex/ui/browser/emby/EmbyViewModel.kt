package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyBatchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyExternalIdInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyImageProviderInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemLookupInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyItemsResult
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSearchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyScanChunk
import app.marlboroadvance.mpvex.domain.emby.EmbyScanQuery
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.StreamProbe
import app.marlboroadvance.mpvex.domain.emby.DuplicateGroup
import app.marlboroadvance.mpvex.domain.emby.MergeItemRef
import app.marlboroadvance.mpvex.domain.emby.MergeOutcome
import app.marlboroadvance.mpvex.domain.emby.MergePhase
import app.marlboroadvance.mpvex.domain.emby.OrphanCleanOutcome
import app.marlboroadvance.mpvex.domain.emby.PersonMergeBatch
import app.marlboroadvance.mpvex.domain.emby.PersonMergeJournal
import app.marlboroadvance.mpvex.domain.emby.PersonMerger
import app.marlboroadvance.mpvex.domain.emby.PersonRef
import app.marlboroadvance.mpvex.domain.emby.UndoOutcome
import app.marlboroadvance.mpvex.domain.emby.autoMergeableGroups
import app.marlboroadvance.mpvex.domain.emby.PersonMergeRule
import app.marlboroadvance.mpvex.domain.emby.clusterDuplicatePersons
import app.marlboroadvance.mpvex.domain.emby.primaryImageRef
import app.marlboroadvance.mpvex.domain.emby.toPersonRef
import app.marlboroadvance.mpvex.domain.iso.RemoteIsoPlayback
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.ui.browser.emby.displayprefs.EmbyDisplayPrefsSync
import app.marlboroadvance.mpvex.ui.player.GsyPlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.player.engine.reversed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 全库搜索没选任何筛选时的默认类型集：只保留可播放的视频本体 */
private const val PERSON_TYPE = "Person"

private val GLOBAL_SEARCH_DEFAULT_TYPES =
  listOf("Movie", "Series", "Episode", "Video", "MusicVideo")

/**
 * **自身没有视频文件**的条目类型 —— 对它们请求 `/Videos/{id}/stream` 必崩。
 *
 * `Series` / `Season` 是容器（内容在子条目上）；`BoxSet` / `Folder` / `CollectionFolder`
 * 是目录；相册 / 歌单 / 音乐人同理。缺了这条判据的表现就是「点播放直接闪退」。
 */
private val CONTAINER_TYPES = setOf(
  "Series",
  "Season",
  "BoxSet",
  "Folder",
  "CollectionFolder",
  "PhotoAlbum",
  "MusicAlbum",
  "MusicArtist",
  "Playlist",
  "Book",
  "AudioBook",
)

/**
 * 远程 ISO 探测的总预算（毫秒）—— 见 [EmbyViewModel.prepareRemoteIso]。
 *
 * 判据只是「读 2KB 的 PVD」，正常几十毫秒就回来了。给 2.5 秒是因为第一跳可能要走
 * 服务端 / 网盘的 302 + 建连；再慢就没有探测价值：strm 指向的绝大多数是普通视频
 * （wmv / mp4），为了确认「它不是 ISO」让用户干等几分钟是纯亏。
 *
 * ⚠️ 这条预算与「302 预解析」的 2 秒（`EmbyClient` 里的
 * `STREAM_PROBE_CALL_TIMEOUT_MS`）一起构成**起播前的总等待上限**：
 * 两者都是阻塞在 `play()` 里、播放器要等它们返回才拉起 —— 少任何一道闸，
 * 「第一次点播放一直卡在加载中」就会回来。
 */
private const val ISO_PROBE_BUDGET_MS = 2_500L

/**
 * 「演职员合并」的运行态快照。
 *
 * 进度**必须是 ViewModel 上的状态**而不是页面里的 `remember`：
 * 合并几十条作品要跑一两分钟，用户很可能切走再回来（甚至退到设置页），
 * 挂在组合里的话一离开就断了、回来看到的是「什么都没发生」。
 *
 * @param message 跑完/取消/失败后要给用户看的一句话；页面弹过提示后调
 *   [EmbyViewModel.clearMergeMessage] 清掉，避免旋转屏幕又弹一遍。
 */
data class MergeUiState(
  val running: Boolean = false,
  /** 进度框标题（单组合并 = 保留项名字；自动合并 = 「自动合并同名 (3/12)」） */
  val title: String = "",
  val phase: MergePhase = MergePhase.COLLECTING,
  val done: Int = 0,
  val total: Int = 0,
  /** 正在处理的作品名 / 正在扫描的演员数 */
  val currentName: String = "",
  val wrote: Int = 0,
  val failed: Int = 0,
  val message: String? = null,
)

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
  private val displayPrefsSync by inject<EmbyDisplayPrefsSync>(EmbyDisplayPrefsSync::class.java)
  // 「聚合库」：删服务器时要级联清掉这台服务器上的聚合库（成员库 Id 全都失效了）
  private val aggregateLibraryRepository by inject<app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository>(
    app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository::class.java,
  )

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

  /**
   * 首页数据加载完成过几次（每次 [loadHome] 结束 +1，含失败与空服务器分支）。
   *
   * 用途只有一个：「随便看看」那一行横滑要排在首页三请求**之后**再拉
   * （见 [awaitHomeLoaded]）。它不驱动任何 UI 状态，所以做成计数器而不是
   * Boolean —— 下拉刷新后不能再变回 false，否则那一行会被反复重拉。
   */
  private val _homeLoads = MutableStateFlow(0)

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
      // 先级联清聚合库，再删服务器本身：反过来的话中途失败会留下
      // 「服务器没了、聚合库还在」的孤儿行（首页 observe 不到，但管理页翻得出来）
      runCatching { aggregateLibraryRepository.deleteByServer(server.id) }
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

  /**
   * 首页数据加载。
   *
   * ## ⚠️ 三发**各自独立**，不要合并成一个 `runCatching`（这条是用户真机报出来的）
   *
   * 原来三句是串在同一个 `runCatching` 里的：`getVirtualFolders` 一抛异常，
   * 后面两发根本不会发，于是「继续观看」「最新加入」也跟着一起消失 ——
   * 服务端只要有一小部分查询能力出问题（比如它自己的 SQLite 库报错），
   * 整个首页就全空了。
   *
   * 现在每一发自己 `runCatching`：
   * - **媒体库**挂了才算首页失败（没有库就确实什么也点不了，进错误态）；
   * - 继续观看 / 最新加入挂了就只是那一块空着，其余照常显示。
   *
   * 顺带说明：`SQLitePCL.pretty.SQLiteException` 是**服务端**（.NET）的 SQLite
   * 驱动抛的，不是本机数据库。本项目这三个请求的参数一个都没改过，
   * 所以真出现这个错要去查服务端（库被锁 / 磁盘满 / 库损坏），不是改客户端能绕过的。
   *
   * ## ⭐ 三发**并行**（2026-10-15，大媒体库下首页慢的修复）
   *
   * 原来是串行的：媒体库 → 继续观看 → 最新加入 一发一发等，首页骨架屏要转到
   * **三者耗时之和**。十万级条目的库上，每一发都可能是几百毫秒到数秒（尤其是
   * `/Items/Latest` 不带 `ParentId` 要跨库扫，`SortBy=Random` 更是没法走索引），
   * 加起来就是用户看到的「打开首页特别慢，有时候还超时」。
   *
   * 这三发互不依赖（各查各的维度），所以改成 `coroutineScope { async {} }` 同时发，
   * 首页耗时从「和」变成「最大者」。`isLoading` 仍然等三发全部结束才置 false ——
   * 语义不变，只是不再把时间白白串起来。
   *
   * ⚠️ 并行**不能**改掉上面那条铁律：三发依旧是各自独立的 `runCatching`，
   * 一发放鸽子不能拖垮另外两块。
   */
  private suspend fun loadHome() {
    _isLoading.value = true
    _error.value = null
    try {
      // 冷启动时服务器是异步恢复的，这里必须等它恢复完，否则会误判成「没有服务器」
      val server = repository.ensureLoggedIn(repository.awaitCurrentServer())
      if (server == null) {
        _libraries.value = emptyList()
        _resumeItems.value = emptyList()
        _latestItems.value = emptyList()
        return
      }

      coroutineScope {
        val libsDeferred = async { runCatching { repository.getVirtualFolders(server) } }
        val resumeDeferred = async { runCatching { repository.getResumeItems(server, limit = 20) } }
        val latestDeferred = async { runCatching { repository.getLatestItems(server, limit = 24) } }

        val libs = libsDeferred.await()
        _libraries.value = libs.getOrElse { e ->
          _error.value = e.message ?: "加载失败"
          emptyList()
        }

        // 下面两发失败只留空列表，不碰 _error —— 首页已经有内容了，
        // 为「继续观看没加载出来」把整页标成失败是本末倒置。
        _resumeItems.value = resumeDeferred.await().getOrElse { emptyList() }
        _latestItems.value = latestDeferred.await().getOrElse { emptyList() }

        // 显示偏好自动恢复（清单 #16）。放在数据加载**之后**：
        // 拉回来的偏好要影响本次已经渲染的首屏，重新触发一次重组就够了，
        // 而放在前面会让首屏用旧偏好渲染一遍再跳变。
        // 失败**不写 _error** —— 显示偏好是锦上添花，为它把整个首页标成「加载失败」不合理。
        // ⚠️ 把已经拉到的媒体库列表传进去：它内部按「每个媒体库一份」读偏好，
        // 老路会**自己再发一次 /Views**，纯属重复请求。
        runCatching {
          displayPrefsSync.pullIfEnabled(server, libraries = libs.getOrNull())
        }
      }
    } finally {
      _isLoading.value = false
      _homeLoads.value = _homeLoads.value + 1
    }
  }

  /**
   * 等首页数据至少加载过一次（含失败 / 没有服务器的情况 —— 那些也算「加载完了」，
   * 不然首页出不来时，下面那些区块会跟着一起永远不出现）。
   *
   * 给「随便看看」那一行横滑用：它在首页最底部，用户得滚好久才看得见，
   * 没道理跟「媒体库 / 继续观看 / 最新加入」那三发抢服务端。
   * 十万级条目的库上每一发都可能是数百毫秒到数秒，四发同时打过去只会让首屏更晚出来。
   *
   * @param timeoutMs 最长等多久。**必须有**：首页三发万一卡在服务端（读超时是 60s，
   *   还会重试），不能连带这一行也永远不出现 —— 到点就放行，让它自己发。
   */
  suspend fun awaitHomeLoaded(timeoutMs: Long = HOME_LOAD_WAIT_MS) {
    if (_homeLoads.value > 0) return
    withTimeoutOrNull(timeoutMs) { _homeLoads.first { it > 0 } }
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
    /** 类型 / 标签的 Id 筛选（逗号分隔）：优先于按名字的 [genres] / [tags] */
    genreIds: List<String>? = null,
    tagIds: List<String>? = null,
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

  /**
   * 直播电视频道列表（`/LiveTv/Channels`）。
   *
   * 频道条目的 `Type` 是 `TvChannel`，可以直接喂给 [play] 起播。
   * 服务端没启用直播电视时返回空列表 —— 那是「没有这个功能」，不是错误。
   */
  suspend fun loadLiveTvChannels(server: EmbyServer): List<EmbyItem> =
    withContext(Dispatchers.IO) { repository.getLiveTvChannels(server) }

  /**
   * 直播电视节目单（a2 + c1）。
   *
   * @param channelId 非 null = 单个频道的时间轴（频道详情页）；
   *                  null = 所有频道在窗口内的节目（频道网格的「正在播出」一次拿全）
   * @param hoursAhead 从「现在」起往后数多少小时
   *
   * 时间窗在这里算（ISO 8601 UTC），调用方不用碰日期格式。
   */
  suspend fun loadLiveTvPrograms(
    server: EmbyServer,
    channelId: String? = null,
    hoursAhead: Int = 3,
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    val now = Instant.now()
    repository.getLiveTvPrograms(
      server = server,
      channelId = channelId,
      startTime = DateTimeFormatter.ISO_INSTANT.format(now),
      endTime = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(hoursAhead.toLong() * 3600)),
    )
  }

  /** 取某媒体库的筛选可选项（类型 / 标签 / 年份 / 分级），供筛选面板使用。 */
  suspend fun loadFilterOptions(
    server: EmbyServer,
    parentId: String?,
  ): EmbyFilterOptions = withContext(Dispatchers.IO) {
    runCatching { repository.getFilterOptions(server, parentId) }
      .getOrDefault(EmbyFilterOptions())
  }

  /**
   * 季列表。
   *
   * [limit] 默认给一个上限（清单 1.1）：原先不传 Limit，服务端一次把全部季连同
   * People / Studios / MediaSources 一起返回，长剧（几十季）白拉一大堆字段。
   * 季数超过上限的剧（几乎没有）会在日志里留痕，不静默截断。
   */
  suspend fun loadSeasons(
    server: EmbyServer,
    seriesId: String,
    startIndex: Int = 0,
    limit: Int = 50,
  ): EmbyItemsPage {
    val result = repository.getSeasons(server, seriesId, startIndex, limit)
    if (result.TotalRecordCount > startIndex + result.Items.size) {
      android.util.Log.w(
        "EmbyViewModel",
        "季列表被分页截断：共 ${result.TotalRecordCount} 季，本次只取到 ${result.Items.size}",
      )
    }
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  /** 某一季下的剧集列表（分页参数见 [loadSeasons]） */
  suspend fun loadEpisodes(
    server: EmbyServer,
    seriesId: String,
    seasonId: String?,
    startIndex: Int = 0,
    limit: Int = EPISODE_PAGE_SIZE,
  ): EmbyItemsPage {
    val result = repository.getEpisodes(server, seriesId, seasonId, startIndex, limit)
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  suspend fun loadFavorites(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsPage {
    val result = repository.getFavoriteItems(server, startIndex, limit)
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
  }

  /**
   * 收藏的演员列表（收藏页「演员」tab）。
   *
   * 按服务器做了一份进程内缓存：收藏页在 影片/演员 两个 tab 之间来回切时
   * 不重复发请求（[EmbyClient.getFavoritePersons] 是全量拉一遍再过滤，开销不小）。
   *
   * 缓存失效：演员被收藏 / 取消收藏时（长按菜单、演员作品页红心）必须调
   * [invalidateFavoritePersonsCache]，否则回到收藏页看到的还是旧列表。
   * 另外 60 秒自动过期 —— 兜底 App 其它入口（比如以后加的）改了收藏状态却忘了通知。
   */
  suspend fun loadFavoritePersons(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoritePersonsCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoritePersons(server) }.getOrDefault(emptyList())
    }
    favoritePersonsCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏演员列表的进程内缓存：serverId → (缓存时间, 名单) */
  private var favoritePersonsCache: Triple<Long, Long, List<EmbyItem>>? = null

  /** 演员收藏状态发生变化时调用：把缓存作废，收藏页下次进来重新拉 */
  fun invalidateFavoritePersonsCache() {
    favoritePersonsCache = null
  }

  /**
   * 收藏的**类型**（Genre）列表（收藏页「类型」tab）。
   *
   * 口径与 [loadFavoritePersons] 一致：按服务器做进程内缓存 + 60 秒自动过期，
   * 收藏页在几个 tab 之间来回切时不重复发请求。收藏状态变化时调
   * [invalidateFavoriteFacetCache] 作废。
   */
  suspend fun loadFavoriteGenres(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoriteGenresCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoriteGenres(server).Items }.getOrDefault(emptyList())
    }
    favoriteGenresCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏**工作室**（Studio）列表，口径同 [loadFavoriteGenres]。 */
  suspend fun loadFavoriteStudios(server: EmbyServer, forceRefresh: Boolean = false): List<EmbyItem> {
    val cached = favoriteStudiosCache
    if (!forceRefresh && cached != null && cached.first == server.id &&
      SystemClock.elapsedRealtime() - cached.second < FAVORITE_PERSONS_CACHE_TTL_MS
    ) {
      return cached.third
    }
    val list = withContext(Dispatchers.IO) {
      runCatching { repository.getFavoriteStudios(server).Items }.getOrDefault(emptyList())
    }
    favoriteStudiosCache = Triple(server.id, SystemClock.elapsedRealtime(), list)
    return list
  }

  /** 收藏类型 / 工作室列表的进程内缓存：serverId → (缓存时间, 名单) */
  private var favoriteGenresCache: Triple<Long, Long, List<EmbyItem>>? = null
  private var favoriteStudiosCache: Triple<Long, Long, List<EmbyItem>>? = null

  /** 类型 / 工作室收藏状态变化时调用：把缓存作废，收藏页下次进来重新拉 */
  fun invalidateFavoriteFacetCache() {
    favoriteGenresCache = null
    favoriteStudiosCache = null
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
   *
   * ⚠️ **这里刻意不做分页**（清单 1.1 的例外）：结果由**两条独立流**拼成 ——
   * 人物走 `/Persons`、媒体走 `/Items`，两者的 offset 空间互不相通，
   * 拼起来的混合列表根本没有「下一页」这个概念（要分页得维护两套游标再归并，
   * 复杂度远大于收益）。所以这里取一个偏大的上限，[GLOBAL_SEARCH_LIMIT]，
   * 并在 [EmbyGlobalSearchResult.totalCount] 里报服务端总数，让 UI 老实告诉用户
   * 「只显示前 N 条」，而不是让人以为搜全了。
   */
  suspend fun searchGlobal(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>? = null,
  ): EmbyGlobalSearchResult =
    withContext(Dispatchers.IO) {
      // 与 [search] 同理：Person 只能从 `/Persons` 拿，`/Items` 那条端点搜不出人；
      // 并且这里同样带上「服务端不认 SearchTerm 就本地过滤」的兜底
      val persons = searchPersons(server, term, GLOBAL_SEARCH_LIMIT, itemTypes)
      val mediaTypes = itemTypes?.filter { it != PERSON_TYPE }?.takeIf { it.isNotEmpty() }
        ?: GLOBAL_SEARCH_DEFAULT_TYPES.takeIf { itemTypes == null }
      val media = if (mediaTypes.isNullOrEmpty()) {
        EmbyItemsResult(emptyList(), 0)
      } else {
        runCatching {
          repository.searchItems(
            server = server,
            term = term,
            includeItemTypes = mediaTypes,
            limit = GLOBAL_SEARCH_LIMIT,
          )
        }.getOrDefault(EmbyItemsResult(emptyList(), 0))
      }
      EmbyGlobalSearchResult(
        items = persons + media.Items,
        // 只能拿媒体侧的总数（`/Persons` 那条走 [searchPersons] 时总数拿不到 ——
        // 它有「服务端搜不到就退回全量演员本地过滤」的兜底路径，那种情况下没有权威总数）。
        // 所以 totalCount 是**媒体部分的**总数，UI 提示截断时要说明「至少还有这么多条」。
        totalCount = media.TotalRecordCount,
        personCount = persons.size,
      )
    }


  // ==================== 媒体操作 ====================

  /**
   * 切换收藏。返回 [Result]：成功时携带**切换后**的状态（true = 已收藏），失败时携带异常，
   * 由调用方决定提示文案与是否回滚本地状态 —— 详情页要据此弹 Toast，
   * 所以这里不自己吞掉错误（内部同步写 [_error] 供其他观察者使用）。
   *
   * [favorite] 是要落到的**目标状态**，默认按 [item] 当前状态取反。调用方（详情页 /
   * 媒体库卡片）在点了红心之后，应当把自己**已经乐观切换到的那个目标**显式传进来 ——
   * 否则一旦传进来的 [item] 是旧快照，这里算出的方向就可能和用户看到的方向相反，
   * 表现就是「红心只能点亮、取消不了」。
   *
   * **成败以服务器回读的真实状态为准**：兼容服务端的回执（200 + UserData）并不可信 ——
   * 会「回 200 但没落库」。这个回读最早只做在演员收藏上（那是最先踩坑的地方），
   * 现在媒体条目的**取消收藏**也必须做（同一个坑，用户反馈「媒体详情页红心只能点亮
   * 不能取消」）。回执本身就与目标一致时走快路径、不回读；否则回读确认，
   * 确认下来没生效就换更用力的写法补一次（取消收藏会补标准 DELETE），再做最终确认。
   */
  suspend fun toggleFavorite(
    server: EmbyServer,
    item: EmbyItem,
    favorite: Boolean = item.UserData?.IsFavorite != true,
  ): Result<Boolean> {
    val itemId = item.Id ?: return Result.failure(IllegalArgumentException("缺少条目 Id"))
    val target = favorite
    val isPerson = item.Type?.equals("Person", ignoreCase = true) == true
    val result = runCatching {
      val data = repository.setFavorite(server, itemId, target)
      // 回执里的状态；**只有「与目标一致」才直接采信**，否则一律回读服务器真值
      var confirmed = data?.IsFavorite
      var authoritative = confirmed == target

      if (isPerson) {
        // 演员收藏的回执在这类兼容服务端上不可信：返回 200、回执看似成功，
        // 服务器却没落库（Emby Web 验证仍是未收藏）——用户碰到的「假成功」。
        // 所以 Person 一律回读，回执只作参考。
        // 部分服务端是「先回 200 再异步落库」，先等一拍再读，避免把刚提交的成功误判成失败
        delay(400)
        var read = readbackFavoriteState(server, item, itemId)
        if (read == null) {
          delay(600)
          read = readbackFavoriteState(server, item, itemId)
        }
        if (read != null) {
          confirmed = read
          authoritative = true
        }
      } else if (!authoritative) {
        // 媒体条目：回执没给出「等于目标」的状态（空 body / 旧值 / 解析不出），回读一次。
        // 「取消收藏」被服务端静默忽略就是从这里被抓出来的。
        delay(300)
        var read = readbackFavoriteState(server, item, itemId)
        if (read == null) {
          delay(400)
          read = readbackFavoriteState(server, item, itemId)
        }
        if (read != null) {
          confirmed = read
          authoritative = true
        }
      }

      // 回读明确显示没生效：换更用力的写法再补一次，然后做最终确认。
      // 取消收藏用 [EmbyRepository.unfavoriteHard]（补一枪标准 DELETE）——
      // `POST .../Delete` 在部分兼容服务端上只是回 200、并不落库。
      if (authoritative && confirmed != target) {
        delay(200)
        if (target) {
          repository.setFavorite(server, itemId, true)
        } else {
          repository.unfavoriteHard(server, itemId)
        }
        delay(300)
        readbackFavoriteState(server, item, itemId)?.let { confirmed = it }
      }

      // 只有「明确确认与目标不符」才算失败；读不到状态时不下失败结论
      if (authoritative && confirmed != target) {
        throw IllegalStateException(
          if (isPerson) "服务器未接受这次收藏（该服务端可能不支持收藏演员）" else "服务器未接受这次操作",
        )
      }
      target
    }
    // 演员的收藏状态变了：收藏页「演员」tab 的缓存不再可信，作废掉
    if (isPerson) {
      invalidateFavoritePersonsCache()
    }
    result.exceptionOrNull()?.let { _error.value = it.message ?: "操作失败" }
    return result
  }

  /**
   * 收藏后回读真实状态（true=已收藏 / false=未收藏 / null=读不到，无法确认）。
   *
   * Person 走 [loadPersonFavoriteState]（单条轻量读 + `IsFavorite=true` 列表查询的
   * 多路径真值读取）；媒体走 [getItem]。
   *
   * ⚠️ Person 不能再走 [getPersonById] 那条 `/Persons?Ids=`：它与进页面时的加载请求
   * 是**同一个 URL**，服务端（或中间层）按 URL 缓存响应，收藏成功后回读拿到的还是
   * 缓存里的旧 UserData（IsFavorite=false）—— 于是「明明收藏成功」被误判成
   * 「服务器未接受」，红心回滚成灰色，体感就是点不动。换 URL 形态是对症药。
   */
  private suspend fun readbackFavoriteState(
    server: EmbyServer,
    item: EmbyItem,
    itemId: String,
  ): Boolean? =
    withContext(Dispatchers.IO) {
      runCatching {
        if (item.Type?.equals("Person", ignoreCase = true) == true) {
          repository.getPersonFavoriteState(server, itemId)
        } else {
          repository.getItem(server, itemId).UserData?.IsFavorite
        }
      }.getOrNull()
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
  /** 标记已看 / 未看。同 [toggleFavorite]：以服务器回传的 UserData 为准。
   *
   * a8：走 [EmbyRepository.setPlayedWithClearPosition] —— 「已看 + 清进度」
   * 一个请求写完（旧路径只翻 Played 位，会在服务端留下陈旧的续播点）。
   * 该路径内部对不认 UserData 直写端点的服务端有 PlayedItems 兜底。 */
  suspend fun setPlayed(server: EmbyServer, itemId: String, played: Boolean): Result<Unit> {
    val result = runCatching {
      val data = repository.setPlayedWithClearPosition(server, itemId, played)
      // 只有服务器明确回「状态和我要的不一样」才算失败；拿不到 UserData 时不下失败结论
      if (data?.Played == !played) throw IllegalStateException("服务器未接受这次修改")
    }
    result.exceptionOrNull()?.let { _error.value = it.message ?: "操作失败" }
    return result
  }

  // ==================== 批量操作 / 继续观看清除 / 直链预解析 ====================

  /**
   * 批量收藏 / 取消收藏。
   *
   * 走 [EmbyClient.setFavoriteMany]（Emby 无批量端点，客户端逐条汇总，单条失败不中断整批）。
   * 只要这批里有演员（Person），顺带把「收藏演员」的进程内缓存作废 ——
   * 否则退回收藏页「演员」tab 看到的还是旧名单。
   */
  suspend fun setFavoriteBatch(
    server: EmbyServer,
    items: List<EmbyItem>,
    favorite: Boolean,
  ): EmbyBatchResult = withContext(Dispatchers.IO) {
    val ids = items.mapNotNull { it.Id }
    if (ids.isEmpty()) return@withContext EmbyBatchResult(0, 0, null)
    val result = runCatching { EmbyClient.setFavoriteMany(server, ids, favorite) }
      .getOrElse { EmbyBatchResult(0, ids.size, it.message) }
    if (items.any { it.Type?.equals("Person", ignoreCase = true) == true }) {
      invalidateFavoritePersonsCache()
    }
    _error.value = if (result.failed > 0) result.firstError ?: "部分操作失败" else null
    result
  }

  /** 批量标记已看 / 未看。与 [setFavoriteBatch] 同构。 */
  suspend fun setPlayedBatch(
    server: EmbyServer,
    itemIds: List<String>,
    played: Boolean,
  ): EmbyBatchResult = withContext(Dispatchers.IO) {
    if (itemIds.isEmpty()) return@withContext EmbyBatchResult(0, 0, null)
    val result = runCatching { EmbyClient.setPlayedMany(server, itemIds, played) }
      .getOrElse { EmbyBatchResult(0, itemIds.size, it.message) }
    _error.value = if (result.failed > 0) result.firstError ?: "部分操作失败" else null
    result
  }

  /**
   * 把一条媒体从服务器的「继续观看」里移除（清掉服务器记录的播放位置）。
   *
   * 见 [EmbyClient.hideFromResume]：三种请求形态逐层兜底。返回 true 只代表
   * 「请求被服务端接受」，UI 侧仍做本地乐观移除，不依赖这里回读。
   */
  suspend fun removeFromResume(server: EmbyServer, itemId: String): Boolean =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.hideFromResume(server, itemId) }.getOrDefault(false)
    }

  /**
   * 本地把一条从「继续观看」列表里摘掉。
   *
   * 配合 [removeFromResume] 的乐观移除：不管服务端是否真的落库，先让用户看到它消失，
   * 免得「点了移除还杵在那儿」——兼容服务端有「回 200 不生效」的先例。
   */
  fun dropResumeItem(itemId: String) {
    _resumeItems.value = _resumeItems.value.filterNot { it.Id == itemId }
  }

  /**
   * 预解析「302 直链」：拿 `/Videos/{id}/stream` 背后跳转到的真实地址。
   *
   * 失败返回 null，调用方回退到原始流地址 —— 解析失败绝不挡播放。
   *
   * ## 为什么必须限时（v139 修的「第一次播放卡在加载中」）
   *
   * 调用方是 [play]，它**拿到本函数的返回值之后才拉起播放器** —— 这条请求因此
   * 直接决定「点播放到播放页出现」要等多久。而 strm 条目在服务端还没 probe 过
   * 源文件时（详情页「没有媒体信息」正是这个状态），`/Videos/{id}/stream` 会先让
   * 服务端去读远端文件、把大小/时长写库，这一次能挂到分钟级。
   *
   * 于是有了那个典型现象：
   * · **第一次**点播放 → 卡在这里，播放器一直不出现；
   * · 返回上一级重新进详情页 → 服务端 probe 已完成、媒体信息补上了，探测秒回 → 立马能播。
   *
   * 两道闸：单次调用 3 秒（[EmbyClient.resolveStreamUrl] 的 `callTimeoutMs`），
   * 且**只发一次不重试** —— 慢路径重试注定还是慢，只是把等待翻倍。
   * 超时按 [StreamProbe.Unknown] 处理，地址原样交给播放器去跟 302。
   */
  suspend fun resolveDirectUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    /** 多版本媒体要指定的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): StreamProbe = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.resolveStreamUrl(
        server = server,
        itemId = itemId,
        static = static,
        // 起播路径上不重试：见上面的说明。
        attempts = 1,
        mediaSourceId = mediaSourceId,
      )
    }.getOrElse { StreamProbe.Unknown }
  }

  /** 是否开启「预解析直链」（设置项）。播放路径据此决定要不要多走一次解析。 */
  fun shouldResolveDirectLink(): Boolean = browserPreferences.embyResolveDirectLink.get()

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
    /**
     * 多版本媒体要播放的那个版本（`item.MediaSources[i].Id`）。
     * null = 不指定，由服务端挑默认版本（单版本媒体照旧走这条路）。
     */
    mediaSourceId: String? = null,
    /**
     * [mediaSourceId] 对应的**显示名**（与详情页下拉框里显示的一模一样）。
     * 由 UI 传进来而不是在这里重算 —— 保证「选的」和「播的时候顶栏显示的」是同一个标签。
     */
    mediaSourceLabel: String? = null,
    /**
     * 电视剧「从头播放」：**强制**落在第一集、并且忽略进度。
     *
     * ⚠️ 必须单独一个开关，不能靠 `resumeSeconds == 0` 推断：
     *   普通「播放」在没看过时也是 0，但那种情况该挑「第一集未看的」；
     *   而「从头播放」的语义是「第一集开头」，哪怕第 1 集已经看完。
     */
    fromStart: Boolean = false,
  ) {
    val itemId = item.Id ?: return
    val engine = resolveEngine(reverseEngine)

    // ⚠️ 容器类条目**绝不能**直接交给播放器。
    // `Series` / `Season` / 文件夹自身没有媒体文件，`/Videos/{id}/stream` 会回一个
    // 404 或空 body；播放器拿到「HTTP 成功但 0 字节」不会当场报错，而是一路推进到
    // mpv 初始化，最后在没有可解码帧那条路径上崩掉 —— 用户看到的就是「点播放闪退」。
    // 电视剧的正确行为是「点封面 = 从上一集没看完的地方接着看」：
    // 先取分集列表，优先挑有进度的第一集，其次挑第一集未看的，最后退回首集。
    val playTarget: EmbyItem = if (isContainerOnly(item) && item.Type == "Series") {
      val episodes = runCatching {
        withContext(Dispatchers.IO) { repository.getEpisodes(server, itemId, null, 0, null).Items }
      }.getOrDefault(emptyList())
      if (fromStart) {
        // 「从头播放」= 第一集开头，哪怕第 1 集早看完了。
        episodes.firstOrNull() ?: return
      } else {
        episodes.firstOrNull { (it.UserData?.PlaybackPositionTicks ?: 0L) > 0L }
          ?: episodes.firstOrNull { it.UserData?.Played != true }
          ?: episodes.firstOrNull()
          ?: return  // 一集都没有（还没刮削完）：什么都不做，比崩掉好
      }
    } else if (isContainerOnly(item)) {
      // 季节 / 文件夹 / 合集：不知道用户想要哪一集，只能安静返回。
      // 详情页已把这类条目的播放按钮改成「选择分集」，走到这里属于兜底。
      return
    } else {
      item
    }
    val itemIdToPlay = playTarget.Id ?: return
    val playItem = playTarget
    // 「从头播放」一律从头：哪怕调用方顺手传了续播秒数也不认。
    // （分集里的「从头播放本季」走 onPlayEpisode(ep, 0)，本就不带秒数，
    //   这里兜的是「Series 大按钮 + 老版本调用方」这类组合。）
    val effectiveResume = if (fromStart) 0L else resumeSeconds

    // 剧集：构建从当前集开始的播放列表，实现"播完自动下一集"
    val playlist = if (playItem.Type == "Episode") {
      buildEpisodePlaylist(server, playItem)
    } else {
      emptyList()
    }

    if (playlist.size > 1) {
      // 「预解析直链」开关打开时，先逐条把 302 背后的真实地址取出来（见 resolveIfEnabled）。
      // 解析失败/未发生跳转返回 null，播放列表里对应位置回退到原始地址。
      // ⚠️ 连播列表里只取「拿到的地址」，被拒（Denied）的集不回退也不报错 ——
      //    不能因为某一集死链就把整场播放拦下来，那一集播到时会由播放器自己报错。
      // ⚠️ 版本选择只作用于「用户当前点播的这一集」：后面自动连播的集仍是各自的默认版本。
      val directUrls = playlist
        .mapNotNull { it.Id }
        .mapNotNull { id ->
          val sourceForThisItem = if (id == itemIdToPlay) mediaSourceId else null
          resolveIfEnabled(server, id, sourceForThisItem)?.urlOrNull?.let { id to it }
        }
        .toMap()
      launchPlaylist(
        server = server,
        items = playlist,
        resumeSeconds = effectiveResume,
        engine = engine,
        directUrls = directUrls,
        firstMediaSourceId = mediaSourceId,
        firstMediaSourceName = mediaSourceLabel,
      )
    } else {
      // 单片播放。
      // ⚠️ ISO 探测**不能挂在 `resolveIfEnabled` 下面** ——
      // 「预解析直链」是个无关的优化开关（默认关，见 BrowserPreferences），
      // 挂在它底下等于「用户没开这个开关就永远用不到 ISO 播放」。
      // 而 ISO 探测自己是会判断的：花 2KB 读一下 PVD，不是 ISO 就立刻返回。
      // ── 多版本：先向服务器要「这个版本」的播放地址 ──
      // ⚠️ 不能只自己拼 `/Videos/{id}/stream?MediaSourceId=…`：官方文档把
      //   `PlaySessionId` 与 `MediaSourceId` 一起列为该端点的**必需参数**，
      //   少了前者服务器会无视我们选的版本、直接吐默认版本 —— 现象就是
      //   「选了不带字幕的 B、播出来还是带字幕的 A」，关掉 302 预解析也一样。
      //   单版本媒体（mediaSourceId == null）完全不走这条路，行为与改动前一致。
      val versionUrl = if (mediaSourceId != null) {
        withContext(Dispatchers.IO) {
          runCatching { repository.directStreamUrl(server, itemIdToPlay, mediaSourceId) }.getOrNull()
        }
      } else {
        null
      }
      // ① 预解析（仅在「预解析直链」开关打开时才发请求）。
      //    返回值现在一并带出「能不能播」的结论 —— 用的是**本来就会发的那一次请求**。
      val directProbe = versionUrl?.let { StreamProbe.Ok(it) }
        ?: resolveIfEnabled(server, itemIdToPlay, mediaSourceId)

      // 明确被拒（401 / 403 / 404 / 410）⇒ 起播前就拦下，改成播放页里的错误卡片。
      // 把死链交给 mpv 会让 libmpv 在 native 层崩掉（整个 App 消失、Java 层拦不到），
      // 而这次判定没有额外打 CDN —— 请求是「预解析」本来就有的那一次。
      (directProbe as? StreamProbe.Denied)?.let { denial ->
        launchSingle(
          server = server,
          item = playItem,
          resumeSeconds = effectiveResume,
          engine = engine,
          mediaSourceId = mediaSourceId,
          mediaSourceLabel = mediaSourceLabel,
          playbackError = denial.message,
        )
        return
      }
      val directUrl = directProbe?.urlOrNull

      // ② ISO 探测：只有容器**可能**是原盘 / STRM 时才值得发这次解析请求。
      //    v127 及以前不管 mkv 还是 mp4 都会为它白发一次 302 解析（请求在容器预筛
      //    之前就发出去了）；v128 起按容器先筛，普通片源直接零请求。
      //    ISO 容器要用**选中版本**的容器名，否则多版本下（4K 是 ISO、1080P 是 mkv）
      //    会拿错容器的预筛结论，把真 ISO 拦在探测之前。
      val selectedContainer = playItem.MediaSources
        ?.firstOrNull { it.Id == mediaSourceId }
        ?.Container
        ?: playItem.MediaSources?.firstOrNull()?.Container
      var isoSourceUrl: String? = null
      if (mayBeIsoContainer(selectedContainer)) {
        val isoProbe = directProbe
          ?: resolveDirectUrl(server, itemIdToPlay, mediaSourceId = mediaSourceId)
        (isoProbe as? StreamProbe.Denied)?.let { denial ->
          launchSingle(
            server = server,
            item = playItem,
            resumeSeconds = effectiveResume,
            engine = engine,
            mediaSourceId = mediaSourceId,
            mediaSourceLabel = mediaSourceLabel,
            playbackError = denial.message,
          )
          return
        }
        isoSourceUrl = isoProbe.urlOrNull
      }
      val isoUrl = isoSourceUrl?.let {
        prepareRemoteIso(
          directUrl = it,
          container = selectedContainer,
          renewDirect = {
            // 直链中途过期 → 重新走一次 302 解析换新的。
            // ⚠️ 与探测必须同一套 UA，resolveDirectUrl 内部已经保证了。
            resolveDirectUrl(server, itemIdToPlay, mediaSourceId = mediaSourceId).urlOrNull
          },
        )
      }
      launchSingle(
        server = server,
        item = playItem,
        resumeSeconds = effectiveResume,
        engine = engine,
        overrideUrl = isoUrl ?: directUrl,
        mediaSourceId = mediaSourceId,
        mediaSourceLabel = mediaSourceLabel,
      )
    }

    // 上报播放开始，让服务器记录"正在播放"
    viewModelScope.launch {
      runCatching {
        repository.reportPlaybackStart(
          server = server,
          itemId = itemIdToPlay,
          positionTicks = EmbyTicks.secondsToTicks(resumeSeconds),
          mediaSourceId = mediaSourceId,
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
    /** 多版本媒体要转码的那个版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
    /** 多版本媒体：版本显示名（播放页顶栏会追加在片名后面） */
    mediaSourceLabel: String? = null,
  ): Result<Unit> {
    val itemId = item.Id ?: return Result.failure(IllegalArgumentException("缺少媒体 Id"))
    return runCatching {
      val url =
        repository.transcodeStreamUrl(
          server = server,
          itemId = itemId,
          startTimeTicks = EmbyTicks.secondsToTicks(resumeSeconds),
          mediaSourceId = mediaSourceId,
        )
      launchSingle(
        server = server,
        item = item,
        resumeSeconds = resumeSeconds,
        engine = resolveEngine(reverseEngine),
        overrideUrl = url,
        mediaSourceId = mediaSourceId,
        mediaSourceLabel = mediaSourceLabel,
      )
    }
  }

  /**
   * 列出本机能打开这条流的**外部播放器**候选（已剔除自家两个播放页）。
   *
   * 枚举规则见 [queryExternalPlayers] —— 关键在于**不能只查一次带 URI 的 intent**，
   * 否则只声明了 `file` / `content` 的播放器会因为 scheme 对不上而全部消失。
   */
  fun listExternalPlayers(
    server: EmbyServer,
    item: EmbyItem,
    /** 多版本媒体要交给外部播放器的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): Result<List<ExternalPlayerOption>> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val players =
      queryExternalPlayers(
        context = app,
        uri = externalStreamUri(server, itemId, mediaSourceId),
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
    /** 多版本媒体要交给外部播放器的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): Result<Unit> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val intent =
      externalPlayerLaunchIntent(
        uri = externalStreamUri(server, itemId, mediaSourceId),
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
  fun playWithExternalPlayerChooser(
    server: EmbyServer,
    item: EmbyItem,
    /** 多版本媒体要交给外部播放器的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): Result<Unit> {
    val itemId = item.Id
      ?: return Result.failure(IllegalStateException("这个条目没有可播放的媒体 Id"))
    val app = getApplication<Application>()
    val chooser =
      externalPlayerChooserIntent(
        context = app,
        uri = externalStreamUri(server, itemId, mediaSourceId),
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
  private fun externalStreamUri(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
  ): Uri =
    Uri.parse(repository.videoStreamUrl(server, itemId, static = true, mediaSourceId = mediaSourceId))

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
   * （「设置里选了 GSY，播放起来还是 mpv」就是这个原因）。
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

  /**
 * 这个地址是不是 [RemoteIsoPlayback]起的**本地** ISO 代理。
 *
 * 要分出来是因为请求头不能一视同仁：直链要带与探测一致的 UA / Referer
 * （否则签名校验失败），而本地代理是mpv → localhost → CDN 的中转，
 * 代理自己会补对头的 UA，给它加 Referer 只会让上游看到一堆乱 headers。
 */
private fun isLocalIsoProxyUrl(url: String): Boolean =
  url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

/**
   * 试把这个条目当「远程 ISO 原盘」播，返回给播放器的本地代理地址。
   *
   * ## 为什么要单独一条路
   *
   * 蓝光/DVD 原盘的目录结构（`BDMV/STREAM`、`VIDEO_TS`）在**镜像内部**，
   * 服务器那条流地址给的是整张 ISO —— mpv 拿到会当成普通字节流，
   * 找不到 MPEG-TS 的 `0x47` 同步就一直缓冲黑屏。
   * 所以做法是：客户端自己解析 ISO 目录、取出主影片那一个文件，
   * 起一个本地 Range 代理把它伪装成普通媒体文件交给 mpv
   * （纯客户端解码，**不需要服务端转码**）。
   *
   * ## 失败必须安静回退
   *
   * 探测不出来（不是 ISO / 签名过期 / 网络不通）就返回 null，
   * 调用方继续走原来的直链播放。远程 ISO 是增强功能，**不能变成拦路石**。
   *
   * @param directUrl 已解析好的直链（`resolveStreamUrl` 的结果）。
   * @param renewDirect 直链过期时的重新解析入口。正常条目传得上，
   *   这样一部两小时的电影中途链接过期也不会断。
   * @return 代理地址；不是 ISO / 探测失败返回 null。
   */
  /**
   * 容器名是否**可能**是原盘 / STRM —— 决定要不要为 ISO 探测发那次 302 解析请求。
   *
   * 只有明确报出 `iso` / `strm`（或服务器根本没给容器名）才值得探。
   * 明确是 mkv / mp4 / avi 的直接跳过：以前这些片源每次起播都会**白发一次**解析请求
   * （请求在 `prepareRemoteIso` 的容器预筛之前就发出去了），v128 起省掉。
   *
   * STRM 必须留下：它指向的可能是 ISO，而且它本来就是最需要探测死链的一类
   * （网盘直链过期后交给 mpv 会 native 崩溃）。
   */
  private fun mayBeIsoContainer(container: String?): Boolean {
    val c = container?.trim()?.lowercase() ?: return true
    return c == "iso" || c == "strm"
  }

  private suspend fun prepareRemoteIso(
    directUrl: String,
    renewDirect: (suspend () -> String?)? = null,
    /** 条目自报的容器（`MediaSources[0].Container`，Emby 扫描时就知道）。可空。 */
    container: String? = null,
  ): String? {
    if (!directUrl.startsWith("http://") && !directUrl.startsWith("https://")) return null

    // 预筛：能**确定**不是 ISO 就别发探测请求了。
    // 两个判据都必须是「否定式」的 —— 只要存在任何疑点就继续探测：
    // · 容器明确报了别的名字（mkv / mp4 / avi…）⇒ 肯定不是 ISO；
    // · URL 路径里没有 .iso ⇒ 只是「看不出来」，**不能**据此排除，
    //   因为 CDN 常把路径混淆成随机串（真 ISO 也可能叫 `/a/9f3c2b`）。
    //   这种情况交给 PVD 探测定夺，那才是唯一可靠的判据。
    if (container != null && !container.equals("iso", ignoreCase = true)) {
      val isStrm = container.equals("strm", ignoreCase = true)
      val pathLooksIso = Uri.parse(directUrl).path.orEmpty().lowercase().contains(".iso")
      if (!isStrm && !pathLooksIso) return null
    }
    // ⚠️ 这一段同样在**起播关键路径**上（`play()` 要等它才拉起播放器），所以给硬预算。
    // 探测本身只是「读 2KB 看是不是 CD001」，正常几十毫秒；慢的一律当「不是 ISO」，
    // 立刻回退普通播放 —— 远程 ISO 是增强功能，宁可播得普通，也不能让用户对着转圈等。
    // 超时后底层 OkHttp 线程会自己跑到它自己的超时，注册出来的代理路由由
    // IsoRangeProxy 按「30 分钟没人用」回收，不用这里操心。
    val prepared =
      withTimeoutOrNull(ISO_PROBE_BUDGET_MS) {
        runCatching {
          withContext(Dispatchers.IO) {
            RemoteIsoPlayback.prepareFromDirectUrl(directUrl, onUrlExpired = renewDirect)
          }
        }.getOrNull()
      }
    return prepared?.playbackUrl
  }

  /** 播放单个媒体 */
  private fun launchSingle(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long,
    engine: EngineKind? = resolveEngine(false),
    /**
     * 覆盖播放地址。null = 默认的「直连原文件」（`static=true`，服务器不动数据）。
     * 两个来源：
     * · [playTranscoded] 拿到的服务端转码地址；
     * · **本地 ISO Range 代理地址**（远程 ISO 原盘，见 [prepareRemoteIso]）。
     */
    overrideUrl: String? = null,
    /** 多版本媒体：本次播放的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
    /** 多版本媒体：本次播放的版本显示名（播放页顶栏会追加在片名后面） */
    mediaSourceLabel: String? = null,
    /**
     * 起播前就被判定为不可播（死链）的原因。非 null 时播放页**不会调 playFile**，
     * 而是直接把这句话显示成错误卡片 —— 用来替代「把死链交给 mpv → native 崩溃」。
     */
    playbackError: String? = null,
  ) {
    val itemId = item.Id ?: return
    val url = overrideUrl
      ?: repository.videoStreamUrl(server, itemId, static = true, mediaSourceId = mediaSourceId)
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
      setClass(getApplication(), PlayerActivity::class.java)
      putExtra("internal_launch", true)
      putExtra("launch_source", "emby")
      if (playbackError != null) putExtra("play_error", playbackError)
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
      // 供播放页把进度回传给 Emby 服务器；带上版本 Id / 版本名 ——
      // 前者让在线字幕搜索找对版本，后者让播放页顶栏显示「正在播的是哪个版本」
      putEmbyPlaybackExtras(
        server = server,
        itemIds = listOf(itemId),
        mediaSourceIds = mediaSourceId?.let { listOf(it) } ?: emptyList(),
        mediaSourceNames = mediaSourceLabel?.takeIf { it.isNotBlank() }?.let { listOf(it) }
          ?: emptyList(),
        episodeNumbers = listOf(encodeEpisodeNumber(item)),
      )
      // 「记忆播放设置」的键：剧集用所属剧的 Id（整季共用一份速度 / 音轨），
      // 电影没有 SeriesId，就用它自己的 Id。
      putExtra("emby_series_key", item.SeriesId ?: itemId)
      putEngine(engine)
      // 这条是「预解析」探出来的直链：探测与真正拉流必须用同一套请求头。
      // 网盘直链（115 这类）常把 UA / Referer 算进签名校验，两边不一致就会被 403
      // —— 现象正是「解析成功、播放必失败」。播放器侧的 headers 约定：
      // 第 0/1 位是 User-Agent，其后两两成对（见 PlayerActivity.setHttpHeadersFromExtras）。
      // Referer 统一给**服务器**的来源，而不是直链自己（自引用常被网盘判为非法）。
      if (overrideUrl != null && !isLocalIsoProxyUrl(overrideUrl)) putStreamHeaders(server)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    getApplication<Application>().startActivity(intent)
  }

  /**
   * 给播放 intent 补上「与预解析探测一致」的请求头。
   *
   * 只在**确实用了预解析直链**时才加（普通直出路径播放器原本就能播，
   * 平白改 UA / Referer 反而可能打破现有可用组合）。
   */
  private fun Intent.putStreamHeaders(server: EmbyServer) {
    val origin = "${if (server.useHttps) "https" else "http"}://${server.host}:${server.port}"
    putExtra(
      "headers",
      arrayOf("User-Agent", EmbyClient.STREAM_USER_AGENT, "Referer", origin),
    )
  }

  /**
   * 「预解析开关打开」时才去解析直链；关闭 → 直接返回 null（用原始流地址，零额外开销）。
   *
   * 返回的是 [StreamProbe] 而不是地址，因为这次请求顺带就能判定死链（见
   * [EmbyClient.resolveStreamUrl]）。调用方取地址用 [StreamProbe.urlOrNull]，
   * 想拦死链就判 [StreamProbe.Denied]。
   */
  private suspend fun resolveIfEnabled(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String? = null,
  ): StreamProbe? =
    if (shouldResolveDirectLink()) resolveDirectUrl(server, itemId, mediaSourceId = mediaSourceId)
    else null

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
    /**
     * itemId → 预解析出来的直链。空 map = 全部走原始流地址（默认行为）。
     * 只有开启「预解析直链」且该条确实发生 302 跳转时才会有内容。
     */
    directUrls: Map<String, String> = emptyMap(),
    /**
     * 多版本媒体：**首条**（即用户在详情页点播的那一条）要用的版本 Id。
     * 只作用于首条，后面自动连播的条目各自用默认版本 —— 用户只为当前这条选了版本。
     */
    firstMediaSourceId: String? = null,
    /** [firstMediaSourceId] 对应的显示名；播放页顶栏据此显示「正在播的是哪个版本」。 */
    firstMediaSourceName: String? = null,
  ) {
    val uris = ArrayList<android.net.Uri>()
    val ids = ArrayList<String>()
    // 与 uris 下标一一对应的显示标题。播放器侧切集时用它，避免从
    // `/Videos/{id}/stream` 这种 URL 里猜出统一是 "stream" 的假标题。
    val titles = ArrayList<String>()
    // 与 uris 下标一一对应的「记忆键」：剧集用 SeriesId（整季共用一份速度 / 音轨），
    // 电影没有 SeriesId 就用自身 Id。播放器切集时按下标取，实现「同剧继承」。
    val seriesKeys = ArrayList<String>()
    // 与上面四条同序的媒体源 Id，供播放页的「在线字幕搜索」用。
    // 取不到就填空串占位 —— 播放页会跳过空串（那时在线搜索按钮自动禁用）。
    val mediaSourceIds = ArrayList<String>()
    // 与上面同序的版本显示名，供播放页顶栏显示「正在播的是哪个版本」。
    // 只有「用户点播的那一条」才会有值（用户只为这一条选了版本），其余一律空串占位。
    val mediaSourceNames = ArrayList<String>()
    // 与上面同序的「S*E*」原始序号，供弹幕按真实集号匹配（队列下标 ≠ 集号）。
    val episodeNumbers = ArrayList<String>()
    // 与上面同序的**剧名**（Emby 的 SeriesName）。弹幕自动匹配要搜的是剧名 ——
    // 拿分集名（item.Name，如「都市传说」）去搜番是搜不到的。电影没有 SeriesName ⇒ 空串，
    // 播放器侧会退回用显示名。
    val seriesNames = ArrayList<String>()
    val firstNameId = items.firstOrNull()?.Id
    items.forEach { item ->
      val id = item.Id ?: return@forEach
      // 首条若选了版本就用它；其余条目一律用各自的默认源（第一条源）
      val sourceId = if (id == firstNameId && firstMediaSourceId != null) {
        firstMediaSourceId
      } else {
        item.MediaSources?.firstOrNull()?.Id
      }
      uris.add(
        android.net.Uri.parse(
          directUrls[id]
            ?: repository.videoStreamUrl(server, id, static = true, mediaSourceId = sourceId),
        ),
      )
      ids.add(id)
      titles.add(displayTitle(item))
      seriesKeys.add(item.SeriesId ?: id)
      // ⚠️ 剧名：弹幕自动匹配要搜的是「某科学的超电磁炮」，
      //    不是某一集的 Name（如「都市传说」）。电影没有 SeriesName，该项留空
      //    ⇒ 播放器侧会退回用显示名，行为不变。
      seriesNames.add(item.SeriesName.orEmpty())
      mediaSourceIds.add(sourceId ?: "")
      mediaSourceNames.add(
        if (id == firstNameId) firstMediaSourceName.orEmpty() else "",
      )
      episodeNumbers.add(encodeEpisodeNumber(item))
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
      putStringArrayListExtra("playlist_series_names", seriesNames)
      // 单文件/单条播放时也有剧名可用于匹配（电影为空串 ⇒ 退回显示名）
      putExtra("emby_series_name", items.firstOrNull()?.SeriesName.orEmpty())
      putExtra("playlist_index", 0)
      // 播放列表的 ID 顺序与 uris 一致，切集时据此把"正在播放"同步给服务器
      putEmbyPlaybackExtras(server, ids, mediaSourceIds, mediaSourceNames, episodeNumbers)
      putEngine(engine)
      // 列表里只要有用预解析直链换掉的条目，就得同步请求头（理由见 [putStreamHeaders]）
      if (directUrls.isNotEmpty()) putStreamHeaders(server)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    getApplication<Application>().startActivity(intent)
  }

  /**
   * 这个条目**自身**是否真的有视频文件可播。
   *
   * ⚠️ 这正是一次线上闪退的直接原因：`Series` / `Season` / 文件夹 / 合集本身没有媒体文件，
   * 拿它们的 Id 去请求 `/Videos/{id}/stream`，服务端回 404 或一个空 body；
   * 播放器拿到「成功的 HTTP、0 字节」会一路走到 mpv 初始化，最后在
   * 「没有任何可解码帧」这条路径上崩掉 —— 现象就是「点播放 App 直接闪退」。
   *
   * 判据用白名单而不是黑名单：宁可漏判（退化成现在的行为）也不能误判，
   * 误判会把能播的东西拦下来。`Episode` 必须在内 —— 它才是电视剧真正的播放单元。
   */
  fun isContainerOnly(item: EmbyItem): Boolean = item.Type?.let { it in CONTAINER_TYPES } ?: true

  /**
   * 详情页的分集列表（电视剧用）。
   *
   * 一次拉全剧所有集（`SeasonId = null`），因为：
   *  · 详情页要按季分组显示，逐季发请求会让「第一屏」等 N 次往返；
   *  · Emby 的 `/Shows/{id}/Episodes` 本来就支持不带 SeasonId 全量返回。
   *
   * 失败**不抛**：分集区只是详情页的增强，拿不到就退回「只有播放按钮」的老样子，
   * 不能让整页变成错误态。
   */
  suspend fun loadSeriesEpisodes(server: EmbyServer, seriesId: String): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      runCatching { repository.getEpisodes(server, seriesId, null, 0, null).Items }.getOrDefault(emptyList())
    }

  /**
   * 按 Id 批量取条目（有序、缺项跳过）。
   *
   * 用途：单集详情页的「同季其它集」——`BaseItemDto.SiblingIds` 只给 Id 不给内容，
   * 而 `/Items?Ids=a,b,c` 一次就能把它们连同 UserData（进度/已看）一起拿回来。
   *
   * ⚠️ 不用 `Recursive` 参数：`Ids` 查询本身就是精确命中，加 `Recursive=true`
   * 在部分第三方服务端会把参数组合当成非法。
   */
  suspend fun loadItemsByIds(server: EmbyServer, ids: List<String>): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      if (ids.isEmpty()) return@withContext emptyList()
      // 服务端对超长 Ids 有 URL 长度限制，按 60 个一批（32 位 Id 约 2KB，安全）
      runCatching {
        ids.chunked(60).flatMap { chunk ->
          EmbyClient.getItems(
            server = server,
            ids = chunk,
            includeItemTypes = listOf("Episode"),
            limit = chunk.size,
            fields = "Overview,MediaSources,MediaStreams,People,Genres,Studios,BasicSyncInfo",
          ).Items.filter { it.Id != null && it.Id in chunk }
        }
      }.getOrDefault(emptyList())
    }

  /**
   * 把剧集的「S*E*」原始序号编码成一个字符串，随播放 intent 下发给播放页。
   *
   * 格式 `ParentIndexNumber*1000 + IndexNumber`，例如 S02E05 → `"2005"`；
   * 非剧集（电影 / 本地文件）返回空串 —— 播放页见到空串就退回队列下标。
   *
   * ⚠️ 为什么要编码而不是直接传 "S02E05"：播放页最终要的是一个**可比较的集号**
   * 去和弹幕库的 `episodeNumber` 对齐，字符串还要再解析一次；直接传数字最省事。
   * 乘 1000 是因为单季集数按惯例远小于 1000，跨季仍能保持单调递增。
   */
  private fun encodeEpisodeNumber(item: EmbyItem): String {
    val season = item.ParentIndexNumber ?: return ""
    val episode = item.IndexNumber ?: return ""
    return (season * 1000 + episode).toString()
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
  fun getStreamUrl(
    server: EmbyServer,
    itemId: String,
    static: Boolean = true,
    /** 多版本媒体要指定的版本（`MediaSources[i].Id`）；null = 服务端默认版本 */
    mediaSourceId: String? = null,
  ): String =
    repository.videoStreamUrl(server, itemId, static, mediaSourceId = mediaSourceId)

  fun imageUrl(server: EmbyServer, item: EmbyItem, imageType: String = "Primary", maxWidth: Int = 480): String? {
    // Primary 统一走 primaryImageRef()：相册 / 文件夹自身没有主图，真正带图的
    // 是 PrimaryImageItemId 指向的条目（见 domain/emby/EmbyPrimaryImage.kt）。
    if (imageType == "Primary") {
      val ref = item.primaryImageRef() ?: return null
      return repository.imageUrl(server, ref.itemId, imageType, ref.tag, maxWidth)
    }
    val id = item.Id ?: return null
    val tag = when (imageType) {
      "Backdrop" -> item.BackdropImageTags.firstOrNull()
      else -> item.ImageTags[imageType]
    }
    return repository.imageUrl(server, id, imageType, tag, maxWidth)
  }

  // ── 照片（图集）──
  // 全部是薄封装（UI 不直接摸 EmbyClient），IO 线程执行。
  // 相册 / 照片两个查询的参数语义见 EmbyClient.getPhotoAlbums / getPhotos。

  /**
   * 照片显示图 URL。原格式返回（HEIC 由 EmbyImageLoader 客户端解码）；
   * [format] = "jpg" 时让服务端转码，作为老系统 / AVIF 的兜底地址。
   */
  fun photoImageUrl(
    server: EmbyServer,
    item: EmbyItem,
    maxWidth: Int = 720,
    format: String? = null,
  ): String? {
    // 相册条目的预览图在 PrimaryImageItemId 上，按自身 Id 取必 404（见 EmbyPrimaryImage）
    val ref = item.primaryImageRef() ?: return null
    return EmbyClient.photoImageUrl(server, ref.itemId, ref.tag, maxWidth, format = format)
  }

  /** 照片原文件下载地址（「保存原图」用，不带缩放参数） */
  fun photoDownloadUrl(server: EmbyServer, item: EmbyItem): String? {
    val id = item.Id ?: return null
    return EmbyClient.photoDownloadUrl(server, id)
  }

  /** 相册（PhotoAlbum）列表；recursive=false，相册层级不拉平 */
  suspend fun loadPhotoAlbums(
    server: EmbyServer,
    parentId: String,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPhotoAlbums(server, parentId, startIndex, limit, sortBy, sortOrder)
  }

  /** 照片（Photo）列表；sortBy = "Random" 即随机一批（随机播放入口用） */
  suspend fun loadPhotos(
    server: EmbyServer,
    parentId: String,
    sortBy: String? = "SortName",
    sortOrder: String? = "Ascending",
    startIndex: Int = 0,
    limit: Int = 100,
    recursive: Boolean = true,
  ): EmbyItemsResult = withContext(Dispatchers.IO) {
    EmbyClient.getPhotos(server, parentId, startIndex, limit, sortBy, sortOrder, recursive)
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
   * /Persons 拿不到这个数（Fields 不支持 ChildCount），只能自己算，所以调用方要放在
   * 后台、别挡首屏。详见 [EmbyClient.getPersonWorkCounts]。
   *
   * ⚠️ 这条是**整库逐页扫描**（最坏 20 个请求），只给 [PersonMerge] / 孤立演员清理用；
   * 列表角标请走 [getPersonWorkCountsCached]（自适应 + 带缓存）。
   */
  suspend fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    withContext(Dispatchers.IO) { EmbyClient.getPersonWorkCounts(server, parentId) }

  /**
   * 演员角标用的作品数：**自适应 + 带缓存**（列表页专用，见 1.4 优化）。
   *
   * 原来的实现每次进「演员」分类都要把整库逐页拉一遍（500/页、上限 1 万条 ⇒ 最多
   * 20 次串行请求），而角标只是次要信息。现在：
   *
   * 1. 交给 [EmbyClient.getPersonWorkCountsAdaptive] 按库规模挑路 ——
   *    小库整库扫一次算齐，大库只逐个问前 60 个；
   * 2. 结果按 (服务器, 库) 缓存 [PERSON_WORK_COUNTS_TTL_MS] —— 切分类、进出页面、
   *    切tab 都不再重算。
   *
   * 缓存失效：刷新媒体库 / 扫描媒体库后调 [invalidatePersonWorkCountsCache]（角标会变）。
   */
  suspend fun getPersonWorkCountsCached(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> {
    val key = "${server.id}|$parentId"
    val hit = personWorkCountsCache
    if (hit != null && hit.first == key &&
      SystemClock.elapsedRealtime() - hit.second < PERSON_WORK_COUNTS_TTL_MS
    ) {
      // 缓存是整张表，直接切片给这一轮要的演员
      val ids = personIds.filter { it.isNotBlank() }.toSet()
      return if (ids.isEmpty()) emptyMap() else hit.third.filterKeys { it in ids }
    }
    val counts = withContext(Dispatchers.IO) {
      runCatching {
        EmbyClient.getPersonWorkCountsAdaptive(server, parentId, personIds)
      }.getOrDefault(emptyMap())
    }
    if (counts.isNotEmpty()) {
      personWorkCountsCache = Triple(key, SystemClock.elapsedRealtime(), counts)
    }
    return counts
  }

  /**
   * 演员作品数角标的进程内缓存：`(serverId|parentId)` → (缓存时间, PersonId → 条数)。
   *
   * 单独一份而不是并进 [actorsCache]：那份是「全量演员名单」（跨库、无 parentId），
   * 这份是「某个库里的作品数」，键不一样，失效时机也不一样。
   */
  private var personWorkCountsCache: Triple<String, Long, Map<String, Int>>? = null

  /** 媒体库内容变了（刷新 / 扫描 / 合并演职员）时调用：角标数字会变，缓存作废 */
  fun invalidatePersonWorkCountsCache() {
    personWorkCountsCache = null
  }

  /** 刷新 / 刮削元数据。full=true 走全量重刮（FullRefresh），否则默认刷新（Default）。 */
  suspend fun refreshMetadata(
    server: EmbyServer,
    itemId: String,
    full: Boolean = false,
    replaceAllMetadata: Boolean = false,
    replaceAllImages: Boolean = false,
  ): Boolean {
    val ok = withContext(Dispatchers.IO) {
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
    // 刷新 / 扫描都会改动库里的演职员 ⇒ 演员角标的数字全变，缓存作废。
    // 放在这里而不是各个调用点：refreshMetadata 是「刷新元数据」「扫描媒体库」
    // 两条路的共同入口（见 refreshLibraryMetadata / scanLibrary），一处收口。
    if (ok) invalidatePersonWorkCountsCache()
    return ok
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
    limit: Int = 100,
    includeItemTypes: List<String> = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
  ): List<EmbyItem> = withContext(Dispatchers.IO) {
    runCatching {
      // ⚠️ 必须分页拉全：高产演员能有几百部作品，只拉一页 60 条
      // 会让人以为「合并丢了一半作品」。TotalRecordCount 是服务端给的总数，
      // 循环到拉完为止；maxItems 是异常数据兜底，防止无限循环拖死设备。
      val out = ArrayList<EmbyItem>()
      val maxItems = 2_000
      var index = startIndex
      var total = Int.MAX_VALUE
      while (index < total && out.size < maxItems) {
        val resp = EmbyClient.getItems(
          server = server,
          personIds = listOf(personId),
          includeItemTypes = includeItemTypes,
          sortBy = "SortName",
          sortOrder = "Ascending",
          recursive = true,
          startIndex = index,
          limit = limit,
          // 作品页要按年份 / 评分 / 最新添加做**本地排序**：这几个字段 Emby 默认不回传，
          // 必须显式索取，否则键值全是 null、排序退化成原顺序（用户反馈
          // 「只有名称排序和随机排序有效果」就是这么来的）
          fields = EmbyClient.ITEM_FIELDS_WITH_SORT_KEYS,
        )
        if (resp.Items.isEmpty()) break
        out += resp.Items
        total = resp.TotalRecordCount
        index += resp.Items.size
      }
      out
    }.getOrDefault(emptyList())
  }

  /**
   * 按 Id 查单个人员（含 UserData.IsFavorite）。
   *
   * 演员作品页的收藏红心用：进来时查一次当前状态，收藏/取消收藏后据此刷新红心。
   * 查不到（服务端异常等）返回 null，红心保持未收藏态且不可点。
   */
  suspend fun loadPersonById(server: EmbyServer, personId: String): EmbyItem? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonById(server, personId) }

  /**
   * 读单个人员的收藏真值（true=已收藏 / false=未收藏 / null=读不到）。
   *
   * 两个用途：① 演员作品页红心的**初值兜底** —— [loadPersonById] 没带回 UserData 时
   * （服务端不回传 / `Ids` 不被支持）用它补一次，否则已收藏的演员进来红心是灰的；
   * ② [toggleFavorite] 收藏后的**回读**（见 [readbackFavoriteState]）。
   */
  suspend fun loadPersonFavoriteState(server: EmbyServer, personId: String): Boolean? =
    withContext(Dispatchers.IO) { EmbyClient.getPersonFavoriteState(server, personId) }

  /**
   * 加载「类型 / 标签 / 工作室」结果页顶栏收藏红心所需的条目。
   *
   * 工作室用详情页传来的 Id（[filterId]）；类型优先用调用方给的 Id（收藏页的类型
   * 条目本身就在手上），拿不到再按名字反查 —— Emby 的类型条目是带 `UserData`
   * 的正式条目，可被收藏，但详情页的类型 chip 只给了名字，需要反查一次拿到 Id；
   * 标签没有独立条目 Id，**不支持收藏**，返回 null（UI 据此隐藏红心）。
   *
   * 拿到的是携带 `UserData` 的完整条目，红心的「是否已收藏」直接读 `UserData.IsFavorite`。
   */
  suspend fun loadFacetItem(
    server: EmbyServer,
    kind: String,
    keyword: String,
    filterId: String,
  ): EmbyItem? = withContext(Dispatchers.IO) {
    val id = when (kind) {
      "studio" -> filterId.takeIf { it.isNotBlank() }
      "genre" -> filterId.takeIf { it.isNotBlank() } ?: resolveGenreId(server, keyword)
      else -> null
    } ?: return@withContext null
    runCatching { EmbyClient.getItem(server, id) }.getOrNull()
  }

  /**
   * 按名字反查类型（Genre）条目的 Id：走官方 `/Genres` 聚合端点
   * （见 [EmbyClient.getGenreIdByName]）。
   *
   * ⚠️ 历史实现是 `/Items?IncludeItemTypes=Genre&SearchTerm=<名字>&Recursive=false`。
   * 不带 `ParentId` 时非递归查询只扫用户根，而用户根下只有媒体库视图、没有类型条目，
   * 于是**永远返回空** —— 「进类型页看不到顶栏收藏红心」就是这么来的。
   */
  private suspend fun resolveGenreId(server: EmbyServer, name: String): String? =
    if (name.isBlank()) null else EmbyClient.getGenreIdByName(server, name)

  // ══════════════════════════════════════════════════════════════════════
  // 演职员合并（Person 去重）
  // 一条铁律：**判重是纯函数、执行顺序限速、账本必须落盘**。
  // 写服务器元数据是「影响全局、而且不报错」的操作 —— 一旦误合，
  // 别人的作品会挂到你名下，而且**你看不到任何异常**。
  // 所以这里没有任何自动触发的路径：每一批合并都由用户在确认弹窗里点过。
  // ══════════════════════════════════════════════════════════════════════

  /** 撤销账本：一个批次一个 JSON 文件，落在 App 私有目录（见 [PersonMergeJournal] 的说明） */
  private val personMergeJournal by lazy {
    PersonMergeJournal(java.io.File(getApplication<Application>().filesDir, "person_merge"))
  }

  private val personMerger by lazy { PersonMerger(personMergeJournal) }

  private val _mergeState = MutableStateFlow(MergeUiState())
  val mergeState: StateFlow<MergeUiState> = _mergeState.asStateFlow()

  private var mergeJob: Job? = null

  /**
   * 扫重复演员：拉全量人员 → 纯函数聚类 → **只给进了重复组的人**补作品数。
   *
   * 为什么只补组内的人：作品数要一个人一次请求，全库上千人就是上千次。
   * 而只有重复组才需要它（挑保留项、以及给人看「谁作品多」）。
   */
  suspend fun scanDuplicateGroups(
    server: EmbyServer,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
    val refs = EmbyClient.getPersonsDetailed(server).mapNotNull { it.toPersonRef() }
    val groups = clusterDuplicatePersons(refs)
    val needCount = groups.flatMap { g -> g.members.map { it.id } }.distinct()
    if (needCount.isEmpty()) return@withContext emptyList()
    val counts = HashMap<String, Int>(needCount.size)
    needCount.forEachIndexed { index, id ->
      currentCoroutineContext().ensureActive()
      onProgress?.invoke(index + 1, needCount.size)
      counts[id] = EmbyClient.getPersonWorkCount(server, id)
    }
    groups.map { g -> g.copy(members = g.members.map { m -> m.copy(workCount = counts[m.id] ?: m.workCount) }) }
  }

  /** 当前登录账号是不是管理员 —— 不是的话整个「写服务器」的能力都要禁掉 */
  suspend fun isEmbyAdmin(server: EmbyServer): Boolean =
    withContext(Dispatchers.IO) { EmbyClient.fetchIsAdmin(server) }

  /** 按名字搜演员（工具页搜索框）。走 /Persons、跨库 */
  suspend fun searchPersonsForMerge(server: EmbyServer, term: String, limit: Int = 60): List<EmbyItem> =
    withContext(Dispatchers.IO) {
      runCatching { EmbyClient.searchPersons(server, term, null, limit) }.getOrDefault(emptyList())
    }

  /**
   * 按 Id 批量取作品数（一次请求一个人，只读 TotalRecordCount，很轻）。
   *
   * ⚠️ 循环里必须夹 `ensureActive()`：这几个是**阻塞**请求（`EmbyClient` 全是非挂起函数），
   * 中间没有挂起点，不加这句的话「用户退出页面」根本打断不了循环 ——
   * 几百个人的全量核对会一直跑到头，白烧流量还占着 IO。
   */
  suspend fun loadPersonWorkCounts(
    server: EmbyServer,
    ids: List<String>,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): Map<String, Int> = withContext(Dispatchers.IO) {
    val unique = ids.distinct()
    if (unique.isEmpty()) return@withContext emptyMap()
    val out = LinkedHashMap<String, Int>(unique.size)
    unique.forEachIndexed { index, id ->
      currentCoroutineContext().ensureActive()
      onProgress?.invoke(index + 1, unique.size)
      out[id] = EmbyClient.getPersonWorkCount(server, id)
    }
    out
  }

  /** 手动绑定的「预览」：先看看会影响到几部作品，再让用户点确认 */
  suspend fun previewMergeItems(server: EmbyServer, members: List<PersonRef>): List<MergeItemRef> =
    withContext(Dispatchers.IO) { personMerger.collectAffectedItems(server, members.map { it.id }) }

  /** 合并历史（按时间倒序） */
  fun mergeHistory(): List<PersonMergeBatch> = personMergeJournal.list()

  /** 账本里出现过的所有「被并掉的 Person Id」（清理孤立演员默认只在这个范围里找） */
  fun knownMergeMemberIds(): Set<String> = personMergeJournal.knownMemberIds()

  /**
   * 服务器上**全部人员**的 Id。
   *
   * 只给「孤立演员」的**全库**核对用（账本范围之外的也一起看）。这一步本身是分页拉
   * `/Persons`，还算轻；重的是后面**逐个**问作品数（一人一次请求）—— 所以调用方
   * 必须先跟用户确认、并对数量设上限（见 `PersonMergeScreen` 的 800 上限）。
   */
  suspend fun allPersonIds(server: EmbyServer): List<String> =
    withContext(Dispatchers.IO) { EmbyClient.getPersonsDetailed(server).mapNotNull { it.Id } }

  /**
   * 合并一组：把 [members] 全部并入 [canonical]。
   *
   * 跑在 [viewModelScope] 而不是页面作用域：用户切走 / 退页都不能中断一次写了一半的合并。
   */
  fun startMerge(server: EmbyServer, canonical: PersonRef, members: List<PersonRef>) {
    runMergeJob {
      val outcome = personMerger.merge(server, canonical, members) { p ->
        _mergeState.value = MergeUiState(
          running = true,
          title = canonical.name,
          phase = p.phase,
          done = p.done,
          total = p.total,
          currentName = p.currentName,
          wrote = p.wrote,
          failed = p.failed,
        )
      }
      refreshAfterMerge(members.map { it.id })
      val autoNote = autoCleanIfEnabled(server, members.map { it.id })
      _mergeState.value = MergeUiState(
        message = mergeSummary("「${canonical.name}」", outcome) + autoNote,
      )
    }
  }

  /**
   * 自动合并一整批组。
   *
   * @param rule 判重规则（用户在工具页选的）。⚠️ **必须传**：调用方给的是已经按同一规则
   *   筛过的列表，这里若回落到默认 STRICT 再筛一次，宽松规则下选中的组会被静默丢掉
   *   —— 确认弹窗写着 30 组、实际只合了 12 组，且不报错。
   */
  fun startAutoMerge(
    server: EmbyServer,
    groups: List<DuplicateGroup>,
    rule: PersonMergeRule,
  ) {
    val todo = autoMergeableGroups(groups, rule)
    if (todo.isEmpty()) {
      _mergeState.value = MergeUiState(message = "没有可自动合并的同名组")
      return
    }
    runMergeJob {
      var groupsDone = 0
      var wrote = 0
      var failed = 0
      var skipped = 0
      val removed = LinkedHashSet<String>()
      for (group in todo) {
        val canonical = group.suggestedCanonical
        val members = group.members.filter { it.id != canonical.id }
        val label = "自动合并同名（${groupsDone + 1}/${todo.size}）"
        _mergeState.value = MergeUiState(
          running = true, title = label, phase = MergePhase.COLLECTING,
          total = todo.size, done = groupsDone,
        )
        val outcome = personMerger.merge(server, canonical, members) { p ->
          _mergeState.value = MergeUiState(
            running = true, title = label, phase = p.phase,
            done = groupsDone, total = todo.size, currentName = p.currentName,
            wrote = wrote + p.wrote, failed = failed + p.failed,
          )
        }
        groupsDone++
        wrote += outcome.wrote
        failed += outcome.failed
        skipped += outcome.skipped
        removed += members.map { it.id }
      }
      refreshAfterMerge(removed)
      val autoNote = autoCleanIfEnabled(server, removed)
      _mergeState.value = MergeUiState(
        message = "自动合并完成：$groupsDone 组 → 改写 $wrote 部作品" +
          (if (skipped > 0) "，另有 $skipped 部本来就是它" else "") +
          (if (failed > 0) "，失败 $failed 部" else "") + autoNote,
      )
    }
  }

  /** 撤销一批合并 */
  fun startUndo(server: EmbyServer, batch: PersonMergeBatch) {
    runMergeJob {
      _mergeState.value = MergeUiState(running = true, title = "撤销合并", phase = MergePhase.WRITING)
      val outcome = personMerger.undo(server, batch) { done, total ->
        _mergeState.value = MergeUiState(
          running = true, title = "撤销合并", phase = MergePhase.WRITING, done = done, total = total,
        )
      }
      // 撤销会把被并掉的演员「还回」作品里，但缓存当时已经单向剔除了它们 ——
      // 单向剔除救不回来，只能整片作废让下次进页面重新拉
      EmbyLibraryCache.clear()
      invalidateFavoritePersonsCache()
      _mergeState.value = MergeUiState(message = undoSummary(outcome))
    }
  }

  /**
   * 清理孤立演员：**触发服务端自己的「刷新人员」任务**，而不是我们直接删 Person。
   *
   * @param auto true = 合并完成后由开关自动跑，false = 用户点了按钮
   */
  fun startCleanOrphans(server: EmbyServer, candidateIds: Collection<String>, auto: Boolean) {
    runMergeJob {
      _mergeState.value = MergeUiState(
        running = true,
        title = if (auto) "自动清理孤立演员" else "清理孤立演员",
        phase = MergePhase.WRITING,
      )
      val result = personMerger.cleanOrphans(server, candidateIds) { done, total ->
        _mergeState.value = MergeUiState(
          running = true, title = "清理孤立演员", phase = MergePhase.WRITING,
          done = done, total = total, currentName = "正在核对演员作品数",
        )
      }
      _mergeState.value = MergeUiState(message = orphanSummary(result, auto))
    }
  }

  /** 进度框上的「取消」 */
  fun cancelMerge() {
    mergeJob?.cancel()
  }

  /** 结果提示已弹过，清掉 —— 免得旋转屏幕又弹一遍 */
  fun clearMergeMessage() {
    _mergeState.update { it.copy(message = null) }
  }

  /**
   * 合并完按开关决定要不要顺手清理孤立演员，返回要追加到结果文案后面的后缀（不需要就是空串）。
   *
   * ⚠️ 这里**直接调引擎**而不是再走 [startCleanOrphans]：那个入口用的是同一个 [mergeJob] 槽位，
   * job 还在跑时会被「已有任务在跑」挡掉，表现就是「开关明明开着、却从来没清理过」。
   */
  private suspend fun autoCleanIfEnabled(server: EmbyServer, memberIds: Collection<String>): String {
    if (memberIds.isEmpty()) return ""
    if (!browserPreferences.embyAutoCleanOrphans.get()) return ""
    val result = personMerger.cleanOrphans(server, memberIds)
    return if (result.orphanCount == 0) "" else "；" + orphanSummary(result, auto = true)
  }

  private fun runMergeJob(block: suspend () -> Unit) {
    if (mergeJob?.isActive == true) return
    mergeJob = viewModelScope.launch {
      _mergeState.value = MergeUiState(running = true)
      try {
        block()
      } catch (e: CancellationException) {
        // 取消不是失败：账本在引擎里已经落盘（哪怕协程被取消了），提示里要把这点说出来
        _mergeState.value = MergeUiState(message = "已取消：已经写入的部分仍可在「合并历史」里撤销")
        throw e
      } catch (e: Throwable) {
        _mergeState.value = MergeUiState(message = "操作失败：${e.message ?: "未知错误"}")
      }
    }
  }

  /**
   * 合并之后收拾缓存：被并掉的那几个演员条目**当场**从各列表里消失，不用等用户手动刷新
   * （[EmbyLibraryCache.removeItem] 会置一个 compose 状态，媒体库页即便还活着也会立刻重组剔除）。
   */
  private fun refreshAfterMerge(memberIds: Collection<String>) {
    memberIds.forEach { EmbyLibraryCache.removeItem(it) }
    // 收藏页的「演员」tab 是按服务器缓存的整份名单，也得作废
    invalidateFavoritePersonsCache()
    // 作品数角标同样作废：合并会把 N 部作品的演职员改写成一个人，角标数字全变
    invalidatePersonWorkCountsCache()
  }

  private fun mergeSummary(subject: String, o: MergeOutcome): String = buildString {
    // 回读（含换动词重写、补扫之后）仍看到被并条目挂在原处 —— 明确告知「没写进去」，
    // 同时说明数据没有被破坏、可撤销；括号里是排障现场，用户原样发回即可定位。
    if (!o.verified) {
      append(subject).append("：已向服务器提交 ").append(o.wrote).append(" 部作品的改写，")
      append("但抽查发现服务器没有接受这次改动（数据没有被破坏，可在「合并历史」里撤销；")
      append("若重试仍失败，请把括号里的内容发回以便定位）")
      if (o.failed > 0) append("；另有 ").append(o.failed).append(" 部提交失败")
      o.writeVerbs?.let { append("；服务器接受的写入方式：").append(it) }
      o.verifyDetail?.let { append("（").append(it).append("）") }
      return@buildString
    }
    append(subject).append("：改写 ").append(o.wrote).append(" 部作品")
    if (o.repaired > 0) append("（其中 ").append(o.repaired).append(" 部为二次补写）")
    if (o.skipped > 0) append("，另有 ").append(o.skipped).append(" 部本来就是它")
    if (o.failed > 0) append("，失败 ").append(o.failed).append(" 部")
  }

  private fun undoSummary(o: UndoOutcome): String = buildString {
    append("已撤销并恢复 ").append(o.restored).append(" 部作品")
    if (o.renamesRestored > 0) append("，").append(o.renamesRestored).append(" 条演员已恢复原名")
    if (o.skipped > 0) append("，跳过 ").append(o.skipped).append(" 部（之后被别处改过，未覆盖）")
    if (o.failed > 0) append("，失败 ").append(o.failed).append(" 部")
  }

  private fun orphanSummary(result: OrphanCleanOutcome, auto: Boolean): String = when {
    result.orphanCount == 0 -> "没有发现「已无任何作品」的演员，无需清理"
    result.noTask -> "发现 ${result.orphanCount} 位已无作品的演员，但服务器上没有可用的「刷新人员」任务，请在 Emby 后台手动跑一次"
    auto -> "已自动通知服务器执行「${result.taskName}」，将回收 ${result.orphanCount} 位已无作品的演员（后台任务，稍后生效）"
    else -> "已通知服务器执行「${result.taskName}」，将回收 ${result.orphanCount} 位已无作品的演员（后台任务，稍后生效）"
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
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage = loadItemsByFacet(server, genres = listOf(genre), sortBy = sortBy, startIndex = startIndex, limit = limit)

  /** 按「标签」查作品：走 Emby 的 Tags 过滤，规则同 [loadGenreItems]。 */
  suspend fun loadTagItems(
    server: EmbyServer,
    tag: String,
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage = loadItemsByFacet(server, tags = listOf(tag), sortBy = sortBy, startIndex = startIndex, limit = limit)

  /**
   * 按「工作室」查作品。
   *
   * ⚠️ **优先用 Id**（`StudioIds`）：Emby 的 `BaseItemsRequest` 里它是官方过滤参数，
   * 且条目自带的 `Studios` 就是 `NameGuidPair`（有 Id），拿得到就不必按名字碰运气。
   * 只有 Id 缺失（少数服务端可能只回名字）时才退回按名字的 `Studios` 参数 ——
   * 它同样是官方参数（和 `Genres` / `Tags` 一样用 `|` 分隔），是个正经兜底。
   *
   * 两者都空时直接返回空页：传空值给服务端等于「不过滤」，结果页会列出一整库的片子，
   * 比空列表更误导用户。
   */
  suspend fun loadStudioItems(
    server: EmbyServer,
    studioId: String,
    studioName: String = "",
    startIndex: Int = 0,
    limit: Int = 120,
    sortBy: String = "SortName",
  ): EmbyItemsPage {
    val id = studioId.takeIf { it.isNotBlank() }
    val name = studioName.takeIf { it.isNotBlank() }
    if (id == null && name == null) return EmbyItemsPage(emptyList(), 0)
    return loadItemsByFacet(
      server = server,
      studioIds = id?.let { listOf(it) },
      studios = if (id == null) name?.let { listOf(it) } else null,
      sortBy = sortBy,
      startIndex = startIndex,
      limit = limit,
    )
  }

  /**
   * [loadGenreItems] / [loadTagItems] / [loadStudioItems] 的公共实现：
   * 只差 Genres / Tags / StudioIds / Studios 这几个过滤参数。
   */
  private suspend fun loadItemsByFacet(
    server: EmbyServer,
    genres: List<String>? = null,
    tags: List<String>? = null,
    studioIds: List<String>? = null,
    studios: List<String>? = null,
    sortBy: String = "SortName",
    startIndex: Int = 0,
    limit: Int = 120,
  ): EmbyItemsPage = withContext(Dispatchers.IO) {
    runCatching {
      EmbyClient.getItems(
        server = server,
        genres = genres,
        tags = tags,
        studioIds = studioIds,
        studios = studios,
        includeItemTypes = listOf("Movie", "Series", "Episode", "Video", "MusicVideo"),
        sortBy = sortBy,
        sortOrder = "Ascending",
        recursive = true,
        startIndex = startIndex,
        limit = limit,
      )
    }.fold(
      onSuccess = { EmbyItemsPage(it.Items, it.TotalRecordCount) },
      // ⚠️ 这里以前是 getOrDefault(emptyList())：网络一抖，标签页就变成「该标签下没有作品」，
      // 用户分不清是真没有还是拉失败。返回 total=0 的空页，让 UI 的错误提示有东西可显示。
      onFailure = { e ->
        android.util.Log.w(
          "EmbyViewModel",
          "按 Genres/Tags/Studios 取作品失败 genres=$genres tags=$tags studioIds=$studioIds studios=$studios",
          e,
        )
        EmbyItemsPage(emptyList(), 0)
      },
    )
  }

  /**
   * 一次取回「当前这一屏所有文件夹」的宫格封面，按文件夹 Id 分组。
   *
   * 存在的理由 —— 给服务端减压：容器条目（Folder / BoxSet / CollectionFolder）自身没有封面，
   * 只能拿内部子项的缩略图拼宫格。原先是**每个文件夹各发一次** /Items（见 [loadFolderCoverUrls]），
   * 一个「合集」列表摆 30 个合集就是 30 次请求。这里改成对当前父节点做 Recursive 查询、
   * 拿子项的 `ParentId` 当分组键 —— 一屏的封面共用这几次请求，请求数从 O(文件夹数) 降到 O(1~3)。
   *
   * 三处刻意的收窄（都是为了减压）：
   * 1. `Fields` 只留 `ParentId,ImageTags` —— 默认那份 `Fields` 带
   *    `MediaSources` / `Overview` / `People`，一页几百条的响应体能大十几倍，而拼封面一个都用不上。
   * 2. **多次取样**（每次 [FOLDER_COVER_BATCH_PAGE_SIZE] 条，最多 [FOLDER_COVER_BATCH_MAX_PAGES] 次），
   *    并且「每个文件夹都凑够 4 张」时立即收工 —— 子项在整棵子树里按名称全局排序时，
   *    一次取样不够会让排在后面的文件夹一条都摸不到。
   * 3. 两种取样口径（[random]）：
   *    - 顺序（`false`）：`SortBy=SortName` 翻页，结果稳定、靠翻页让每个文件夹都分得到；
   *    - 随机（`true`）：每次都让服务端 `SortBy=Random` **重抽一整批**（`StartIndex` 固定 0），
   *      所以每次取样拿到的都是**不同的一批条目** —— 这正是「下拉刷新能换一批封面」的关键。
   *      ⚠️ 随机模式千万别改成「按名称翻页 + 客户端洗牌」：那样每次拿到的是**同一批**条目，
   *      洗牌只换位置不换内容，用户看到的就是「没换图」（踩过这个坑）。
   *
   * ⚠️ 返回的 map **只含「凑满 `perFolder` 张」的文件夹**。不足的一律交白卷，由调用方退回
   * 单文件夹请求去补齐 —— 这一条是**正确性要求**，不是优化：半成品（1~2 张）一旦交出去，
   * 卡片看到「已经有封面」就不再兜底，于是明明能凑四个的文件夹只显示 1~2 个宫格。
   * ⚠️ 与 [loadFolderCoverUrls] 一样会跳过没封面的条目、一路往后凑，所以「前几个没图就整张空白」
   * 不会发生。
   *
   * @param parentId 当前浏览的父节点（媒体库 / 文件夹），即列表接口用的那个 parent
   * @param folderIds 需要拼封面的文件夹 Id（调用方已按「容器 && 无 Primary 图」筛过）
   * @param random true＝每个文件夹从候选里**洗牌**取几张（每切一次取样都不同）；
   *   false＝按名称顺序取前几张。两种都只交出凑满的文件夹。
   * @param perFolder 每个文件夹要凑几张，宫格用 4 张
   */
  suspend fun loadFolderCoverBatch(
    server: EmbyServer,
    parentId: String,
    folderIds: List<String>,
    random: Boolean = false,
    perFolder: Int = 4,
  ): Map<String, List<String>> = runCatching {
    if (folderIds.isEmpty()) return@runCatching emptyMap()
    val wanted = folderIds.toHashSet()
    // 每个文件夹最多留几张候选：只要 4 张，但随机模式要多留一点才有洗牌的余地。
    val poolSize = if (random) perFolder * FOLDER_COVER_BATCH_POOL_FACTOR else perFolder
    val pools = LinkedHashMap<String, ArrayList<String>>()
    // 同一个文件夹内按子项 Id 去重（服务端对 Recursive 查询偶尔会重复返回同一条）
    val seen = HashMap<String, HashSet<String>>()
    var startIndex = 0
    var attempt = 0
    while (attempt < FOLDER_COVER_BATCH_MAX_PAGES) {
      attempt++
      val res = repository.getItems(
        server = server,
        parentId = parentId,
        // 随机模式让服务端重抽（每次一批新样本 ⇒ 刷新真的换图）；顺序模式按名称翻页（稳定、覆盖全）。
        sortBy = if (random) "Random" else "SortName",
        sortOrder = if (random) null else "Ascending",
        includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
        isFolder = false,
        // ⚠️ 随机排序下 StartIndex 没有意义（每次返回的都是新的一批），固定 0 让服务端一次次重抽。
        startIndex = if (random) 0 else startIndex,
        limit = FOLDER_COVER_BATCH_PAGE_SIZE,
        recursive = true,
        fields = FOLDER_COVER_FIELDS,
      )
      val items = res.Items
      if (items.isEmpty()) break
      for (child in items) {
        val pid = child.ParentId ?: continue
        if (pid !in wanted) continue
        val pool = pools.getOrPut(pid) { ArrayList(poolSize) }
        if (pool.size >= poolSize) continue
        val id = child.Id ?: continue
        if (!seen.getOrPut(pid) { HashSet() }.add(id)) continue
        val url = folderCoverUrlFor(server, child, id) ?: continue
        pool.add(url)
      }
      if (!random) startIndex += items.size
      // 一个都没落进任何目标文件夹：说明这批条目根本不在这些文件夹下（典型是「合集」——
      // 它只是虚拟分组，片子的 ParentId 指向各自的媒体库，不指向合集），再取也是白取。
      if (pools.isEmpty()) break
      // 每个文件夹都凑够 4 张了就收工，别把整库翻完
      if (pools.count { it.value.size >= perFolder } == wanted.size) break
      // 服务端返回的条数少于请求条数 ⇒ 子树里就这么多、已经取全了，两种模式都该收工
      if (items.size < FOLDER_COVER_BATCH_PAGE_SIZE) break
    }
    // ⚠️ 只交出**凑满 perFolder 张**的文件夹，不足的宁可交白卷，让卡片走单文件夹兜底去补齐。
    //   以前把「这一批只扫到 1~2 张」的半成品也交出去，卡片看见 `cached != null` 就不再兜底，
    //   于是明明能凑四个的文件夹只显示 1~2 个宫格（ bug）。
    //   —— 交白卷的代价只是那一个文件夹多一次单文件夹请求，总次数仍远低于「每个文件夹各一次」。
    pools
      .filterValues { it.size >= perFolder }
      .mapValues { (_, pool) ->
        if (random) pool.shuffled().take(perFolder) else pool.take(perFolder)
      }
  }.getOrElse { e ->
    // ⚠️ 取消必须原样抛出，不能当成「没有封面」：调用方（卡片）拿到空列表会写进缓存，
    // 而「换代被取消」的旧请求正好会在刷新瞬间把封面覆盖成空 —— 就是用户看到的白闪。
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    emptyMap()
  }

  /**
   * 取文件夹内若干子项的封面图 URL，用于给「文件夹」条目拼多宫格封面。
   *
   * Emby 的 Folder / CollectionFolder / UserView 这类容器条目自身没有 Primary 图，
   * 直接请求图片接口会 404（也就是用户看到的「文件夹拿不到封面」）。
   * 这里改为查文件夹内部的**可播放子项**，取它们的缩略图交给客户端拼宫格。
   *
   * 优先 Thumb（横版缩略图，宫格排布更协调），没有则退回 Primary。
   *
   * ⚠️ 关键在于**凑足**：以前只取排序后的前 `count` 个，谁没封面就被滤掉 ——
   * 于是「前四个恰好都没封面」时整个文件夹退回占位图标（空白问题），
   * 而且第 5 个之后哪怕有图也永远看不到。现在改成：从更大的候选池里**跳过没封面的**，
   * 一路往后凑，直到凑满 `count` 张或把文件夹翻完为止。
   *
   * @param count 最多取几张，宫格用 4 张
   * @param random true＝随机取样（排序交给服务端的 Random，多次换样本）；false＝按名称顺序固定取前几张。
   *   两种模式都会跳过无封面条目，只是取样顺序不同。
   */
  suspend fun loadFolderCoverUrls(
    server: EmbyServer,
    folderId: String,
    count: Int = 4,
    random: Boolean = false,
  ): List<String> = runCatching {
    val urls = ArrayList<String>(count)
    // 跨页去重：随机模式靠它避免同一张图重复入选；顺序模式靠它扛服务端可能返回的重复项。
    val seen = HashSet<String>()
    // 已消费的**服务端**条目数（不是 [urls] 的大小 —— 去掉无封面的条目后两者不相等）。
    var consumed = 0
    var attempt = 0
    val maxAttempt = if (random) FOLDER_COVER_RANDOM_ATTEMPTS else FOLDER_COVER_MAX_PAGES
    while (urls.size < count && attempt < maxAttempt) {
      attempt++
      val page = repository.getItems(
        server = server,
        parentId = folderId,
        // 随机模式让服务端打乱；顺序模式按名称，结果稳定可预期。
        sortBy = if (random) "Random" else "SortName",
        sortOrder = if (random) null else "Ascending",
        includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
        // 容器（Folder / Series / Season / BoxSet）不能播放、也不该占宫格位置，挡掉。
        isFolder = false,
        // 随机模式每次都是新样本，从头取；顺序模式从上次消费处接着翻。
        startIndex = if (random) 0 else consumed,
        limit = if (random) FOLDER_COVER_RANDOM_POOL else FOLDER_COVER_PAGE_SIZE,
        recursive = true,
      )
      val items = page.Items
      if (items.isEmpty()) break
      val before = urls.size
      for (child in items) {
        if (urls.size >= count) break
        val id = child.Id ?: continue
        if (!seen.add(id)) continue
        val url = folderCoverUrlFor(server, child, id) ?: continue
        urls.add(url)
      }
      if (random) {
        // 这一批随机样本一张新图都没带来：要么文件夹里带图的本来就没几张，
        // 要么服务端忽略了 Random（每次都回同一批）。继续重抽也是白费请求，直接收工。
        if (urls.size == before) break
        continue
      }
      consumed += items.size
      // 服务端可能截断 Limit；拿「返回条数 < 请求条数」当「已到末页」的信号。
      if (items.size < FOLDER_COVER_PAGE_SIZE) break
    }
    urls
  }.getOrElse { e ->
    // ⚠️ 取消原样抛出：这一条**极重要** —— 调用方拿到空列表会写进缓存，
    // 于是「下拉刷新时被取消的旧请求」会在新封面还没到之前先把封面覆盖成空（白闪）。
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    emptyList()
  }

  /**
   * 单个子项能用作宫格封面的图片地址：**优先 Thumb**（横版缩略图，宫格排布更协调），
   * 没有 Thumb 才退回 Primary；两者都没有返回 null（调用方负责跳过它、继续往后凑）。
   *
   * 抽出来是因为有两条取封面的路径（批量 [loadFolderCoverBatch] 与单文件夹 [loadFolderCoverUrls]），
   * 「Thumb 优于 Primary」这条偏好只能有一处，否则两边迟早选出不一样的脸。
   */
  private fun folderCoverUrlFor(
    server: EmbyServer,
    child: EmbyItem,
    id: String,
  ): String? {
    val thumb = child.ImageTags["Thumb"]
    val primary = child.ImageTags["Primary"]
    return when {
      thumb != null -> repository.imageUrl(server, id, "Thumb", thumb, maxWidth = 320)
      primary != null -> repository.imageUrl(server, id, "Primary", primary, maxWidth = 320)
      else -> null
    }
  }

  fun clearError() {
    _error.value = null
  }

  companion object {
    /** 收藏演员缓存的存活时间：超过就当过期重新拉，防止别处改了收藏状态这里还留着旧数据 */
    private const val FAVORITE_PERSONS_CACHE_TTL_MS = 60_000L

    /**
     * 演员作品数角标缓存的存活时间。
     *
     * 比收藏演员那份（60 秒）长得多：角标数字只随「媒体库里多了/少了作品」而变，
     * 而那件事必然伴随一次刷新/扫描（会显式调invalidate），所以兜底过期可以很宽松，
     * 换取「来回切分类/进出页面」时零请求。
     */
    private const val PERSON_WORK_COUNTS_TTL_MS = 10 * 60_000L

    /**
     * 某一季的剧集一页拉多少条（清单 1.1）。
     *
     * 一季 50 集已覆盖绝大多数剧；长剧（一季 100+ 集，多见于动画）会分页续拉。
     */
    const val EPISODE_PAGE_SIZE = 100

    /**
     * 文件夹宫格封面：顺序模式下每页翻多少条。
     *
     * 比只需要 4 张的缺口大得多，是为了**跳过没封面的条目**后还有余量能凑满 ——
     * 一页 100 条足够覆盖绝大多数文件夹；极少数超大文件夹由 [FOLDER_COVER_MAX_PAGES] 兜底。
     */
    private const val FOLDER_COVER_PAGE_SIZE = 100

    /** 顺序模式最多翻几页（100 × 3 = 300 条扫描上限），防止超大文件夹把设备拖住。 */
    private const val FOLDER_COVER_MAX_PAGES = 3

    /** 随机模式每次抽样的池子大小：抽一批再从里头挑有封面的。 */
    private const val FOLDER_COVER_RANDOM_POOL = 48

    /** 随机模式最多重抽几次：随机样本有波动，一两次抽不到 4 张有封面的就再抽一批。 */
    private const val FOLDER_COVER_RANDOM_ATTEMPTS = 3

    /**
     * 批量取封面时每次取多少条。
     *
     * 之所以要**取多次**而不是「一次取够」：
     * 顺序模式下子项在整棵子树里按名称全局排序，一次取不够时排在后面的文件夹一条都摸不到；
     * 随机模式下每次都换一批样本，多取几次才能把每个文件夹都覆盖到。
     */
    private const val FOLDER_COVER_BATCH_PAGE_SIZE = 200

    /**
     * 批量取封面最多取样几次（顺序模式＝翻页，随机模式＝重抽）。
     *
     * 上限 `200 × 3 = 600` 条，兜住超大媒体库，同时不至于把库整个翻一遍。
     */
    private const val FOLDER_COVER_BATCH_MAX_PAGES = 3

    /**
     * 随机模式下每个文件夹多留几倍候选，供最后洗牌用。
     *
     * 与「凑满 4 张」无关 —— 4 张只是最终要的张数，多留几张才有随机可选的余地。
     */
    private const val FOLDER_COVER_BATCH_POOL_FACTOR = 3

    /**
     * 批量取封面时索要的字段 —— 只要「分组键 + 图片标记」。
     *
     * 这个参数的**唯一作用是把默认的大字段顶掉**：默认那份 `Fields` 带
     * `MediaSources` / `Overview` / `People` / `Path` 等，一页几百条时响应体会大出一个数量级，
     * 而拼一张宫格封面一个都用不上。`ImageTags` / `ParentId` 本身在基础 DTO 里就有，
     * 列出来只是为了让「分组靠 ParentId、取图靠 ImageTags」在请求里一眼可见。
     */
    private const val FOLDER_COVER_FIELDS = "ParentId,ImageTags"

    /**
     * 全库搜索一次取多少条（清单 1.1 的例外，理由见 [searchGlobal] 的 KDoc）。
     *
     * 比原来的 100 略高：搜索结果的「浏览价值」随条数快速衰减，取太多反而让网格难用。
     */
    const val GLOBAL_SEARCH_LIMIT = 120

    /**
     * [awaitHomeLoaded] 最长等多久（毫秒）。
     *
     * 正常局域网下首页三发并行也就一秒内回来；给到 8 秒是留给「服务端在扫大表」
     * 的余量。再往上就不等了 —— 那一行横滑在首页最底部，为它拖住用户不值当。
     */
    private const val HOME_LOAD_WAIT_MS = 8_000L

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

/**
 * 全库搜索的结果（人物 + 媒体两条流拼成）。
 *
 * @param items 拼好的结果：人物在前、媒体在后（沿用旧行为）
 * @param totalCount **媒体部分**的服务端总数；人物那部分拿不到权威总数，见 [EmbyViewModel.searchGlobal]
 * @param personCount 本次实际返回的人物条数
 */
data class EmbyGlobalSearchResult(
  val items: List<EmbyItem>,
  val totalCount: Int,
  val personCount: Int,
) {
  /**
   * 结果是不是被上限截断了。
   *
   * UI 据此显示「只显示前 N 条，请输入更精确的关键词」——
   * 静默截断会让用户以为「搜全了就是这个结果」，从而错过想找的片。
   */
  val truncated: Boolean get() = totalCount > 0 && totalCount > items.size - personCount
}
