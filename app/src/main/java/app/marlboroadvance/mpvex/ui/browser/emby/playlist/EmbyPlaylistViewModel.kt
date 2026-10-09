package app.marlboroadvance.mpvex.ui.browser.emby.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyBatchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 「Emby 服务端播放列表」的 ViewModel：列表页 + 单个播放列表的条目管理。
 *
 * ## 与本地播放列表的关系
 *
 * 项目里已有一套本地播放列表（Room 的 `PlaylistEntity` / `PlaylistRepository`，
 * 入口在 `ui/browser/playlist/`）。**两者刻意不互通**：
 *
 * | | 本地播放列表 | Emby 播放列表（本类） |
 * |---|---|---|
 * | 存储 | Room，本机 | Emby 服务器 |
 * | 条目标识 | 本地绝对路径 | Emby ItemId |
 * | 多端同步 | 不同步 | 网页/手机/电视端共享 |
 * | 来源 | 手动添加 / M3U 导入 | Emby 客户端 / 网页端创建 |
 *
 * 强行打通需要「本地路径 ↔ ItemId」映射，而 smartstrm / 网盘这类资源的
 * `Path` 往往指向不存在的虚拟路径，匹配率极低。所以各做各的。
 *
 * ## 为什么条目页要分页
 *
 * 一个播放列表可能有几百上千条（连播清单、专辑合集）。一次性全拉会让
 * 「加入播放列表」时把整串都预解析直链，既慢又可能一次性打满连接。
 * 播放时才按当前页取 —— 见 [playAll]。
 */
class EmbyPlaylistViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)

  // ── 列表页状态 ──
  private val _playlists = MutableStateFlow<List<EmbyItem>>(emptyList())
  val playlists: StateFlow<List<EmbyItem>> = _playlists.asStateFlow()

  private val _listLoading = MutableStateFlow(false)
  val listLoading: StateFlow<Boolean> = _listLoading.asStateFlow()

  /**
   * 条目分页游标。
   *
   * 逻辑全在 [PlaylistPagingCursor] 里 —— 那里能跑 JVM 单测，本类只负责把
   * 游标状态映射成 Compose 能观察的 [StateFlow]。
   *
   * 不是线程安全的：只在 [viewModelScope] 里读写，天然单线程。
   */
  private val cursor = PlaylistPagingCursor(ENTRY_PAGE_SIZE)

  private val _entries = MutableStateFlow<List<EmbyItem>>(emptyList())
  val entries: StateFlow<List<EmbyItem>> = _entries.asStateFlow()

  private val _entriesTotal = MutableStateFlow(0)
  val entriesTotal: StateFlow<Int> = _entriesTotal.asStateFlow()

  private val _entriesLoading = MutableStateFlow(false)
  val entriesLoading: StateFlow<Boolean> = _entriesLoading.asStateFlow()

  private val _entriesHasMore = MutableStateFlow(true)
  val entriesHasMore: StateFlow<Boolean> = _entriesHasMore.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** 一次性提示（操作结果 toast），用完即清 —— 避免转屏后重复弹。 */
  private val _toast = MutableStateFlow<String?>(null)
  val toast: StateFlow<String?> = _toast.asStateFlow()

  fun consumeToast() {
    _toast.value = null
  }

  fun clearError() {
    _error.value = null
  }

  // ═══════════════════ 列表页 ═══════════════════

  fun loadPlaylists(server: EmbyServer, force: Boolean = false) {
    if (_listLoading.value) return
    if (!force && _playlists.value.isNotEmpty()) return
    _listLoading.value = true
    _error.value = null
    viewModelScope.launch {
      runCatching { repository.getPlaylists(server, 0, PLAYLIST_PAGE_SIZE) }
        .onSuccess { result ->
          _playlists.value = result.Items
        }
        .onFailure {
          // 读失败**不要**静默空列表 —— 用户要能区分「一个播放列表都没有」
          // 和「服务端连不上」，后者需要提示去检查服务器配置。
          _error.value = it.message ?: "读取播放列表失败"
        }
      _listLoading.value = false
    }
  }

  /**
   * 新建播放列表。
   *
   * 刻意用回调而不是 `runBlocking` 返回 Id —— ViewModel 里阻塞主线程是清单 3.5
   * 要清掉的反模式，真同步拿结果只会诱使 UI 侧再包一层。
   *
   * @param itemIds 首批条目；为空则建空列表（用户随后往里加）
   * @param onCreated 新列表 Id；失败传 null（错误已写入 [error]）
   */
  fun createPlaylist(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    onCreated: (String?) -> Unit,
  ) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      _error.value = "播放列表名称不能为空"
      onCreated(null)
      return
    }
    viewModelScope.launch {
      runCatching { repository.createPlaylist(server, trimmed, itemIds) }
        .onSuccess { result ->
          val id = result.Id
          if (id == null) {
            _error.value = "服务端没有返回播放列表 Id"
            onCreated(null)
          } else {
            _toast.value = "已创建「$trimmed」"
            // 直接重载而不是手工往列表里插一条：
            // 创建接口只回 Id/Name/条数，插进去的对象缺一堆字段（图片、类型、ChildCount…），
            // 列表项会渲染成半空白。宁可多一个请求，也不要给用户看一条假数据。
            loadPlaylists(server, force = true)
            onCreated(id)
          }
        }
        .onFailure {
          _error.value = it.message ?: "创建失败"
          onCreated(null)
        }
    }
  }

  /**
   * 删除播放列表**本身**（连同条目）。
   *
   * ⚠️ 走 `DELETE /Items/{Id}` —— 见 [app.marlboroadvance.mpvex.domain.emby.EmbyClient.deletePlaylist]
   * 关于「Emby 没有删除播放列表专用端点」的说明。不可恢复，UI 必须二次确认。
   */
  fun deletePlaylist(server: EmbyServer, playlistId: String) {
    viewModelScope.launch {
      runCatching { repository.deletePlaylist(server, playlistId) }
        .onSuccess {
          _playlists.value = _playlists.value.filterNot { it.Id == playlistId }
          _toast.value = "已删除"
        }
        .onFailure { _error.value = it.message ?: "删除失败" }
    }
  }

  // ═══════════════════ 条目页 ═══════════════════

  /** 首屏加载（重进页面时重置分页状态）。 */
  fun loadEntries(server: EmbyServer, playlistId: String) {
    cursor.reset()
    _entries.value = emptyList()
    _entriesTotal.value = 0
    _entriesHasMore.value = true
    loadMoreEntries(server, playlistId, reset = true)
  }

  /**
   * 加载更多条目。
   *
   * 分页/去重/判到底全在 [PlaylistPagingCursor] 里，本方法只负责发请求与
   * 把结果灌进 StateFlow。
   */
  fun loadMoreEntries(server: EmbyServer, playlistId: String, reset: Boolean = false) {
    if (_entriesLoading.value) return
    if (!reset && !_entriesHasMore.value) return
    _entriesLoading.value = true
    viewModelScope.launch {
      runCatching {
        repository.getPlaylistItems(server, playlistId, cursor.consumed, ENTRY_PAGE_SIZE)
      }.onSuccess { result ->
        cursor.accept(result)
        _entries.value = cursor.items
        _entriesTotal.value = result.TotalRecordCount
        _entriesHasMore.value = cursor.hasMore
      }.onFailure {
        _error.value = it.message ?: "读取条目失败"
        // 追加失败不该把「已到底」设上，否则用户再也拉不动了。
        if (!reset) {
          cursor.markAppendFailed()
          _entriesHasMore.value = cursor.hasMore
        }
      }
      _entriesLoading.value = false
    }
  }

  /** 往播放列表追加条目（去重后逐条加，返回成功/失败条数）。 */
  fun addItems(server: EmbyServer, playlistId: String, itemIds: List<String>) {
    if (itemIds.isEmpty()) return
    viewModelScope.launch {
      runCatching { repository.addToPlaylist(server, playlistId, itemIds) }
        .onSuccess { result ->
          val added = result.ItemAddedCount
          _toast.value = if (added > 0) "已加入 $added 项" else "这些条目已在列表中"
          if (added > 0) loadMoreEntries(server, playlistId)
        }
        .onFailure { _error.value = it.message ?: "加入失败" }
    }
  }

  /**
   * 从播放列表移除条目。
   *
   * @param entryIds 传**播放列表内的条目 Id**（[EmbyItem.Id]），
   *   不是媒体 Id —— 服务端要求前者。
   */
  fun removeEntries(server: EmbyServer, playlistId: String, entryIds: List<String>) {
    if (entryIds.isEmpty()) return
    viewModelScope.launch {
      runCatching { repository.removeFromPlaylist(server, playlistId, entryIds) }
        .onSuccess {
          val removed = entryIds.toSet()
          val remaining = _entries.value.filterNot { it.Id in removed }
          // 必须走 cursor：否则 cursor.items 还留着旧列表，
          // 下一次翻页去重时会把刚删掉的条目又「补」回来。
          cursor.replaceItems(remaining)
          _entries.value = remaining
          _entriesTotal.value = (_entriesTotal.value - entryIds.size).coerceAtLeast(0)
          _toast.value = "已移除 ${entryIds.size} 项"
        }
        .onFailure { _error.value = it.message ?: "移除失败" }
    }
  }

  /**
   * 移动条目位置（拖拽排序后调用）。
   *
   * 先本地改顺序让 UI 立刻响应，再同步服务端；失败时重新拉一遍纠正 ——
   * 服务端的 Move 端点在部分第三方服务端上行为不一致，本地先动更安全。
   */
  fun moveEntry(server: EmbyServer, playlistId: String, from: Int, to: Int) {
    val list = _entries.value
    if (from !in list.indices || to !in list.indices || from == to) return
    val entryId = list[from].Id ?: return

    val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
    cursor.replaceItems(reordered)
    _entries.value = reordered

    viewModelScope.launch {
      runCatching { repository.movePlaylistItem(server, playlistId, entryId, to) }
        .onFailure {
          _error.value = it.message ?: "排序失败，已恢复原顺序"
          // 顺序没生效 ⇒ 拉回服务端真值，否则界面会一直停在错的位置
          loadMoreEntries(server, playlistId, reset = true)
        }
    }
  }

  /**
   * 播放整个播放列表。
   *
   * 复用 [app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel.launchPlaylist]，
   * 它已经处理好三件事，**不要在这里重写**：
   *  1. 三条平行数组（Uri / 标题 / 记忆键）下标严格对应；
   *  2. 开了「预解析直链」时同步带上请求头，否则解析成功也 403；
   *  3. 上报 Emby 播放信息。
   *
   * 这里负责的是**分页拉完整个列表**再交给它 —— 播放器需要完整的
   * 条目数组才能连播，不能只给当前页。
   *
   * @param onReady 拿到要启动播放的 ViewModel 后回调（用于切到 GSY 内核）
   */
  fun playAll(
    server: EmbyServer,
    playlistId: String,
    onReady: (EmbyViewModel) -> Unit,
  ) {
    _entriesLoading.value = true
    viewModelScope.launch {
      runCatching {
        // 全量拉完：播放器要的是完整列表，不是当前这一页。
        // `seen` 只建一次（每轮重建是 O(n²)，几千条时会白烧一堆 CPU），
        // `start` 按**服务端给的条数**推进，不按去重后的 all.size ——
        // 同一集在播放列表里加两次是常态，用 all.size 会拉回已看过的区间。
        val all = ArrayList<EmbyItem>()
        val seen = HashSet<String>(512)
        var start = 0
        // 最近一次服务端报的总条数；退出循环后用来判断是不是被 MAX_PLAY_ALL_ITEMS 截断的
        var lastTotal = 0
        // 撞到上限就停手，并如实告诉用户「只放了一半」——静默截断会让人以为列表就这些
        var truncated = false
        while (all.size < MAX_PLAY_ALL_ITEMS) {
          val page = repository.getPlaylistItems(server, playlistId, start, ENTRY_PAGE_SIZE)
          if (page.Items.isEmpty()) break
          val fresh = page.Items.filter { it.Id == null || seen.add(it.Id) }
          all.addAll(fresh)
          start += page.Items.size
          // 整页都是重复 ⇒ 服务端在原地打转，再翻也是白翻
          if (fresh.isEmpty()) break
          lastTotal = page.TotalRecordCount
          if (lastTotal > 0 && start >= lastTotal) break
        }
        if (lastTotal > 0 && start < lastTotal) truncated = true
        if (truncated) _toast.value = "播放列表过长，只播放前 ${all.size} 项"
        all
      }.onSuccess { all ->
        if (all.isEmpty()) {
          _toast.value = "这个播放列表是空的"
        } else {
          // 复用 EmbyViewModel 的连播入口：403 预解析 / 请求头 / 上报都在那里
          val embyVm = ViewModelProvider(
            getApplication(),
            EmbyViewModel.factory(getApplication()),
          )[EmbyViewModel::class.java]
          // ⚠️ 必须把「播放列表里的条目」换回「媒体本身」再播：
          // `/Playlists/{Id}/Items` 返回的行 `Id` 是**条目**的 Id，
          // 拿它拼 `/Videos/{id}/stream` 服务端认不出 ⇒ 播不出来。
          // 其余字段（标题 / SeriesId / 媒体源）本来就在条目上，照搬即可。
          val playable = all.map { item ->
            val mediaId = item.MediaItemId
            if (mediaId == null || mediaId == item.Id) item else item.copy(Id = mediaId)
          }
          embyVm.launchPlaylist(server, playable)
          onReady(embyVm)
        }
      }.onFailure {
        _error.value = it.message ?: "读取播放列表失败"
      }
      _entriesLoading.value = false
    }
  }

  /**
   * 「N 项」文案。
   *
   * 刻意做成 [StateFlow] 而不是 `fun entryCountText(): String` —— 后者在 Composable 里
   * 读 `entriesTotal.value` **不会**触发重组，数字会卡在第一次渲染时的值不更新。
   */
  val entryCountText: StateFlow<String> =
    combine(_entriesTotal, _entries) { total, list ->
      if (total > 0) "$total 项" else "${list.size} 项"
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = "0 项",
    )

  companion object {
    /** 播放列表列表页每页条数。用户一般只有十几个播放列表，100 足够一次拉完。 */
    const val PLAYLIST_PAGE_SIZE = 100

    /** 单个播放列表的条目页每页条数。 */
    const val ENTRY_PAGE_SIZE = 100

    /**
     * 「播放全部」时最多拉多少条。
     *
     * 兜底上限：条目页是分页的，理论上几千条的播放列表也能全拉，
     * 但那意味着几千次直链预解析请求（开了预解析时）会打满连接。
     * 超限时静默截断并提示。
     */
    const val MAX_PLAY_ALL_ITEMS = 5_000

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyPlaylistViewModel(application) }
    }
  }
}

/** 批量操作结果的中文提示（与 [EmbyBatchResult] 配套）。 */
fun EmbyBatchResult.describe(): String = when {
  failed == 0 && ok > 0 -> "成功 $ok 项"
  ok == 0 && failed > 0 -> "全部失败：${firstError ?: "未知原因"}"
  else -> "成功 $ok 项，失败 $failed 项${firstError?.let { "（$it）" } ?: ""}"
}
