package app.marlboroadvance.mpvex.ui.browser.emby.aggregate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository
import app.marlboroadvance.mpvex.domain.emby.AggregateItemPager
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 「聚合库」详情页的取数。
 *
 * 数据流：本类持有一个 [AggregateItemPager]（多泳道游标归并），
 * 首屏 [prime] + 取一屏，触底 [loadMore] 再取一屏；排序变化 / 下拉刷新
 * 就**换一个新 pager**（随机模式下还换新种子 = 换一批）。
 *
 * ⚠️ pager 的 loader 里带的是「当前服务器」：冷启动时服务器可能还没从库里恢复完，
 * 所以每次加载都走 `awaitCurrentServer + ensureLoggedIn`，别直接用 currentServer 的 value。
 */
class EmbyAggregateLibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val aggregateRepository by inject<AggregateLibraryRepository>(AggregateLibraryRepository::class.java)
  private val browserPreferences by inject<BrowserPreferences>(BrowserPreferences::class.java)

  private val _aggregate = MutableStateFlow<AggregateLibraryEntity?>(null)
  val aggregate: StateFlow<AggregateLibraryEntity?> = _aggregate.asStateFlow()

  private val _items = MutableStateFlow<List<EmbyItem>>(emptyList())
  val items: StateFlow<List<EmbyItem>> = _items.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _loadingMore = MutableStateFlow(false)
  val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** 部分成员库加载失败时的提示（首屏全失败时转而显示 [_error]） */
  private val _laneErrors = MutableStateFlow<List<String>>(emptyList())
  val laneErrors: StateFlow<List<String>> = _laneErrors.asStateFlow()

  /** 成员库在服务端已不存在 / 类型对不上的提示（聚合库本身仍可用，剩下的库照常聚合） */
  private val _memberHints = MutableStateFlow<List<String>>(emptyList())
  val memberHints: StateFlow<List<String>> = _memberHints.asStateFlow()

  private val _hasMore = MutableStateFlow(true)
  val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

  /** 服务端口径的总数（各成员库之和，跨库重复未去重 ⇒ 略偏大，仅作数量展示） */
  private val _estimatedTotal = MutableStateFlow(0)
  val estimatedTotal: StateFlow<Int> = _estimatedTotal.asStateFlow()

  /** 当前生效的排序（与媒体库页同一份全局偏好，换页不丢） */
  private val _sortBy = MutableStateFlow(browserPreferences.embyLibrarySortBy.get())
  val sortBy: StateFlow<String> = _sortBy.asStateFlow()
  private val _sortOrder = MutableStateFlow(effectiveSortOrder(_sortBy.value))
  val sortOrder: StateFlow<String> = _sortOrder.asStateFlow()

  private var pager: AggregateItemPager? = null
  private var loadJob: Job? = null

  /** 随机种子：下拉刷新 / 换一批时换一个新种子（= 整副牌重洗） */
  private var randomSeed = System.nanoTime()

  /**
   * 进入页面：读聚合库定义 → 校验成员库 → 首屏加载。
   *
   * 聚合库被删了（管理页删的）时 [aggregate] 保持 null，页面显示「聚合库不存在」。
   */
  fun start(aggregateId: Long) {
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
      val entity = aggregateRepository.getById(aggregateId)
      _aggregate.value = entity
      if (entity == null) {
        _error.value = "聚合库不存在或已被删除"
        return@launch
      }
      // ⚠️ 多服务器 / 多用户防串栏：聚合库是按 serverId 分桶的，当前服务器要不是
      //   它的归属服务器（理论上首页只会点开本服务器的，这里是纵深防御），
      //   拿当前服务器的 token 去取别人服务器的库 Id，轻则 404、重则串了内容。
      val current = repository.awaitCurrentServer()
      if (current != null && entity.serverId != current.id) {
        _error.value = "这个聚合库不属于当前服务器"
        return@launch
      }
      checkMembers(entity)
      load(reset = true)
    }
  }

  /** 成员库健康检查：删了的、类型变了的，各自给一句提示（不影响其余库聚合） */
  private suspend fun checkMembers(entity: AggregateLibraryEntity) {
    val members = AggregateLibraryRepository.membersOf(entity.memberLibraryIds)
    val server = repository.ensureLoggedIn(repository.awaitCurrentServer()) ?: return
    val libraries = runCatching { repository.getVirtualFolders(server) }.getOrDefault(emptyList())
    val byId = libraries.associateBy { it.Id }
    val hints = mutableListOf<String>()
    val missing = members.filter { byId[it] == null }
    if (missing.isNotEmpty()) {
      hints.add("${missing.size} 个成员库已在服务器上删除，已跳过")
    }
    val type = entity.collectionType
    if (!type.isNullOrBlank()) {
      val mismatched = members.mapNotNull { id -> byId[id] }
        .filter { !it.CollectionType.isNullOrBlank() && !it.CollectionType.equals(type, ignoreCase = true) }
      if (mismatched.isNotEmpty()) {
        hints.add("${mismatched.size} 个成员库的类型已与建库时不一致，内容仍会一起显示")
      }
    }
    _memberHints.value = hints
  }

  /** 首屏 / 重载。reset=true 换新 pager 从头来；false = 续拉 */
  fun load(reset: Boolean) {
    if (_isLoading.value || _loadingMore.value) return
    viewModelScope.launch {
      val entity = _aggregate.value ?: return@launch
      val server = repository.ensureLoggedIn(repository.awaitCurrentServer()) ?: run {
        _error.value = "服务器未连接"
        return@launch
      }
      if (reset) {
        _isLoading.value = true
        _error.value = null
        _laneErrors.value = emptyList()
        _items.value = emptyList()
        pager = createPager(entity, server)
      } else {
        _loadingMore.value = true
      }
      val current = pager ?: return@launch
      try {
        if (reset) current.prime()
        val batch = current.next(PAGE_BATCH)
        _items.value = if (reset) batch else _items.value + batch
        _hasMore.value = current.hasMore
        _estimatedTotal.value = current.estimatedTotal
        _laneErrors.value = current.laneErrors
        // 首屏一条都没拿到、且有泳道报错 ⇒ 整页错误态（错误由 laneErrors 汇总而来）
        if (reset && batch.isEmpty()) {
          _error.value = current.laneErrors.firstOrNull() ?: "没有可显示的内容"
        }
      } catch (e: Exception) {
        _error.value = e.message ?: "加载失败"
      } finally {
        _isLoading.value = false
        _loadingMore.value = false
      }
    }
  }

  fun loadMore() {
    if (_isLoading.value || _loadingMore.value || !_hasMore.value) return
    load(reset = false)
  }

  /** 下拉刷新：换种子重来（随机模式下即「换一批」；其余模式等同重载） */
  fun refresh() {
    randomSeed = System.nanoTime()
    load(reset = true)
  }

  /** 换排序项 / 转方向：偏好落盘 + 重载（顺序整体变了，页面由调用方滚回顶部） */
  fun applySort(sortBy: String, sortOrder: String) {
    browserPreferences.embyLibrarySortBy.set(sortBy)
    browserPreferences.embyLibrarySortOrder.set(sortOrder)
    _sortBy.value = sortBy
    _sortOrder.value = sortOrder
    load(reset = true)
  }

  /**
   * 随机播放取一批：现拉一个随机 pager（固定种子）抽 [count] 条。
   *
   * 不复用浏览 pager —— 浏览可能已经翻得很深，从那儿抽只是「把看过的再洗一遍」。
   */
  suspend fun drawRandomPlaylist(count: Int): List<EmbyItem> {
    val entity = _aggregate.value ?: return emptyList()
    val server = repository.ensureLoggedIn(repository.awaitCurrentServer()) ?: return emptyList()
    val members = AggregateLibraryRepository.membersOf(entity.memberLibraryIds)
    val randomPager = AggregateItemPager(
      memberLibraryIds = members,
      sortBy = "Random",
      sortOrder = "Ascending",
      seed = randomSeed,
      loadPage = { libraryId, startIndex, limit ->
        repository.getItems(
          server = server,
          parentId = libraryId,
          sortBy = "Random",
          sortOrder = "Ascending",
          startIndex = startIndex,
          limit = limit,
          recursive = true,
          mediaTypes = listOf("Video"),
          isFolder = false,
        )
      },
    )
    randomPager.prime()
    return randomPager.next(count)
  }

  private fun createPager(entity: AggregateLibraryEntity, server: EmbyServer): AggregateItemPager {
    val members = AggregateLibraryRepository.membersOf(entity.memberLibraryIds)
    val sortBy = _sortBy.value
    val sortOrder = _sortOrder.value
    return AggregateItemPager(
      memberLibraryIds = members,
      sortBy = sortBy,
      sortOrder = sortOrder,
      seed = randomSeed,
      loadPage = { libraryId, startIndex, limit ->
        repository.getItems(
          server = server,
          parentId = libraryId,
          sortBy = sortBy,
          sortOrder = sortOrder,
          startIndex = startIndex,
          limit = limit,
          recursive = true,
        )
      },
    )
  }

  /**
   * 没手动切过时跟随该排序项的自然方向：名称 / 年份升序，其余（加入时间、评分…）降序。
   * 与媒体库页同一套规则 —— 两边不一致会出现「库里按名称升序、聚合库里按名称降序」。
   */
  private fun effectiveSortOrder(sortBy: String): String {
    val stored = browserPreferences.embyLibrarySortOrder.get()
    return stored.takeIf { it.isNotBlank() }
      ?: if (sortBy == "SortName" || sortBy == "ProductionYear") "Ascending" else "Descending"
  }

  companion object {
    /** 一次续拉多少条。与 pager 的页大小解耦：一屏网格 ~20-30 条，给 40 留出余量 */
    private const val PAGE_BATCH = 40

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyAggregateLibraryViewModel(application) }
    }
  }
}
