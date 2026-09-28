package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * Emby 模块共享 ViewModel。
 *
 * 负责：当前服务器、媒体库/继续观看/最新加入数据加载、收藏、删除、播放启动。
 * [EmbyRepository] 是 Koin 单例，因此多个页面拿到的当前服务器是一致的。
 */
class EmbyViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)

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
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null
      // 冷启动时服务器是异步恢复的，这里必须等它恢复完，否则会误判成「没有服务器」
      val server = repository.ensureLoggedIn(repository.awaitCurrentServer())
      if (server == null) {
        _libraries.value = emptyList()
        _resumeItems.value = emptyList()
        _latestItems.value = emptyList()
        _isLoading.value = false
        return@launch
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
    )
    return EmbyItemsPage(result.Items, result.TotalRecordCount)
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

  suspend fun search(
    server: EmbyServer,
    term: String,
  ): List<EmbyItem> = repository.searchItems(server, term).Items

  // ==================== 媒体操作 ====================

  suspend fun toggleFavorite(server: EmbyServer, item: EmbyItem): Boolean {
    val nowFavorite = item.UserData?.IsFavorite == true
    return runCatching {
      if (nowFavorite) repository.unfavorite(server, item.Id!!) else repository.favorite(server, item.Id!!)
      !nowFavorite
    }.onFailure { _error.value = it.message ?: "操作失败" }.getOrDefault(nowFavorite)
  }

  fun deleteItem(server: EmbyServer, itemId: String) {
    viewModelScope.launch {
      runCatching { repository.deleteItem(server, itemId) }
        .onFailure { _error.value = it.message ?: "删除失败" }
        .onSuccess { refreshHome() }
    }
  }

  fun markPlayed(server: EmbyServer, itemId: String, played: Boolean) {
    viewModelScope.launch {
      runCatching {
        if (played) repository.markPlayed(server, itemId) else repository.markUnplayed(server, itemId)
      }.onFailure { _error.value = it.message ?: "操作失败" }
    }
  }

  // ==================== 播放 ====================

  /**
   * 启动播放器播放 Emby 媒体。
   *
   * 剧集（Episode）会自动把同季后续剧集一起交给播放器作为播放列表，
   * 从而在播完当前集后自动接着播下一集。
   *
   * @param resumeSeconds >0 时从指定秒数续播，否则从头开始
   */
  suspend fun play(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long = 0,
  ) {
    val itemId = item.Id ?: return

    // 剧集：构建从当前集开始的播放列表，实现"播完自动下一集"
    val playlist = if (item.Type == "Episode") {
      buildEpisodePlaylist(server, item)
    } else {
      emptyList()
    }

    if (playlist.size > 1) {
      launchPlaylist(server, playlist, resumeSeconds)
    } else {
      launchSingle(server, item, resumeSeconds)
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

  /** 播放单个媒体 */
  private fun launchSingle(
    server: EmbyServer,
    item: EmbyItem,
    resumeSeconds: Long,
  ) {
    val itemId = item.Id ?: return
    val url = repository.videoStreamUrl(server, itemId, static = true)
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
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    getApplication<Application>().startActivity(intent)
  }

  /** 播放一组媒体（剧集连播 / 随机播放） */
  fun launchPlaylist(
    server: EmbyServer,
    items: List<EmbyItem>,
    resumeSeconds: Long = 0,
  ) {
    val uris = ArrayList<android.net.Uri>()
    val ids = ArrayList<String>()
    // 与 uris 下标一一对应的显示标题。播放器侧切集时用它，避免从
    // `/Videos/{id}/stream` 这种 URL 里猜出统一是 "stream" 的假标题。
    val titles = ArrayList<String>()
    items.forEach { item ->
      val id = item.Id ?: return@forEach
      uris.add(android.net.Uri.parse(repository.videoStreamUrl(server, id, static = true)))
      ids.add(id)
      titles.add(displayTitle(item))
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
      putParcelableArrayListExtra("playlist", uris)
      putStringArrayListExtra("playlist_titles", titles)
      putExtra("playlist_index", 0)
      // 播放列表的 ID 顺序与 uris 一致，切集时据此把"正在播放"同步给服务器
      putEmbyPlaybackExtras(server, ids)
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
