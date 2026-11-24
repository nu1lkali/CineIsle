package app.marlboroadvance.mpvex.ui.browser.emby.aggsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateEntry
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateMergeMode
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSearchEngine
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSearchLimits
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSnapshot
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSort
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerBatch
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerOutcome
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerPhase
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 聚合搜索页的状态机。
 *
 * ## 与首页全库搜索的关系
 *
 * **完全独立**：不读也不写 [app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel.searchGlobal]
 * 的那套状态（`searchQuery` / `searchResults` / `EmbySearchMemory` 一概不碰）。
 * 两条链路唯一的交汇点是 [EmbyRepository] 的公开方法。
 *
 * 因此这里的搜索历史也独立分区（[HISTORY_SERVER_ID]），不会污染任何一台真实服务器的历史。
 *
 * ## 逐台渐进
 *
 * [AggregateSearchEngine.search] 每台返回一次就发一次；本类收到一批就重算一次快照，
 * 所以「先回的那台先出现」，不会等最慢的一台。改了关键词时旧会话立即作废
 * （[sessionSeq] 递增），迟到批次直接丢弃 —— 否则会把上一轮的结果混进新一轮。
 */
class AggregateSearchViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val browserPreferences by inject<BrowserPreferences>(BrowserPreferences::class.java)
  private val searchHistoryRepository by inject<SearchHistoryRepository>(
    SearchHistoryRepository::class.java,
  )

  private val engine = AggregateSearchEngine(repository)

  /**
   * 全部已配置服务器（顺序即用户配置顺序，参与范围与排序都依赖它）。
   *
   * `stateIn(Eagerly)` 而不是直接暴露 `repository.servers`：本类多处需要**同步快照**
   * （参与范围求交、`serverOrder`、范围变更时的新增 / 移除差集）。拿不到 `.value`
   * 就得把这些函数全改成 suspend，而它们都是 UI 直接点的同步动作。
   */
  val servers: StateFlow<List<EmbyServer>> =
    repository.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val currentServer: StateFlow<EmbyServer?> = repository.currentServer

  // ─── 关键词 ───

  private val _term = MutableStateFlow("")
  val term: StateFlow<String> = _term.asStateFlow()

  // ─── 参与范围 ───

  /**
   * 参与本次聚合的服务器 id 集合。
   *
   * `null` = **全部参与**（偏好为空串）。存「包含集」而不是「排除集」：
   * 服务器被删除后遗留的 id 会在与当前列表求交时自然收敛，不会让状态发散。
   */
  private val _scopeIds = MutableStateFlow<Set<Long>?>(readScope())

  // ─── 结果 ───

  private val _outcomes = MutableStateFlow<List<ServerOutcome>>(emptyList())
  val outcomes: StateFlow<List<ServerOutcome>> = _outcomes.asStateFlow()

  private val _snapshot = MutableStateFlow(AggregateSnapshot())
  val snapshot: StateFlow<AggregateSnapshot> = _snapshot.asStateFlow()

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  private val _mergeMode = MutableStateFlow(browserPreferences.embyAggregateMergeMode.get())
  val mergeMode: StateFlow<AggregateMergeMode> = _mergeMode.asStateFlow()

  private val _sort = MutableStateFlow(browserPreferences.embyAggregateSort.get())
  val sort: StateFlow<AggregateSort> = _sort.asStateFlow()

  /** 长按结果卡片后展开的「逐台副本」抽屉；null = 未展开 */
  private val _drawerEntry = MutableStateFlow<AggregateEntry?>(null)
  val drawerEntry: StateFlow<AggregateEntry?> = _drawerEntry.asStateFlow()

  /** 聚合搜索自己的历史分区（与任何真实服务器隔离） */
  val history: StateFlow<List<String>> =
    searchHistoryRepository
      .observe(HISTORY_SERVER_ID)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  // ─── 会话内部状态（不驱动 UI） ───

  private var searchJob: Job? = null

  /** 会话代数：每次开新会话 +1，迟到批次凭它作废 */
  private var sessionSeq = 0

  /** 当前会话的关键词（去空白后），重算快照要用 */
  private var sessionTerm = ""

  /** 当前会话的参与服务器（顺序稳定，合并与分组都按它排） */
  private var sessionServers: List<EmbyServer> = emptyList()

  /** 当前会话已到达的批次，按 serverId 索引 */
  private val arrived = LinkedHashMap<Long, ServerBatch>()

  init {
    // 首屏就把参与范围铺出来：用户一进来能看见「哪几台参与、哪几台不参与」
    refreshOutcomes()
  }

  // ==================== 输入 ====================

  /**
   * 输入变更。防抖 [AggregateSearchLimits.DEBOUNCE_MS] 后开新会话。
   *
   * 清空输入时**立即**收掉结果：留着上一批结果会让人以为「空词也在搜」。
   */
  fun onTermChange(raw: String) {
    _term.value = raw
    searchJob?.cancel()

    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
      abandonSession()
      return
    }
    searchJob = viewModelScope.launch {
      delay(AggregateSearchLimits.DEBOUNCE_MS)
      beginSession(trimmed)
    }
  }

  /** 键盘搜索键 / 右侧「搜索」按钮：跳过防抖立即查一次 */
  fun submit() {
    val trimmed = _term.value.trim()
    if (trimmed.isEmpty()) return
    searchJob?.cancel()
    viewModelScope.launch {
      recordHistory(trimmed)
      beginSession(trimmed)
    }
  }

  /** 点历史胶囊：填词并立即搜索 */
  fun pickHistory(keyword: String) {
    _term.value = keyword
    searchJob?.cancel()
    viewModelScope.launch {
      if (keyword.isNotBlank()) recordHistory(keyword.trim())
      beginSession(keyword.trim())
    }
  }

  fun removeHistory(keyword: String) {
    viewModelScope.launch { searchHistoryRepository.remove(HISTORY_SERVER_ID, keyword) }
  }

  fun clearHistory() {
    viewModelScope.launch { searchHistoryRepository.clear(HISTORY_SERVER_ID) }
  }

  // ==================== 参与范围 ====================

  /** 某台是否参与。`null` 作用域 = 全部参与 */
  fun isParticipating(serverId: Long): Boolean = _scopeIds.value?.contains(serverId) ?: true

  /** chip 点击：参与 ⇄ 不参与。至少保留一台，最后一台不允许取消 */
  fun toggleServer(serverId: Long) {
    val all = servers.value
    val current = resolveScope(all)
    val next = if (serverId in current) current - serverId else current + serverId
    if (next.isEmpty()) return
    applyScope(all, next)
  }

  /** 长按 chip：只搜这一台 */
  fun searchOnlyServer(serverId: Long) {
    val all = servers.value
    if (all.none { it.id == serverId }) return
    applyScope(all, setOf(serverId))
  }

  /** 一次性把参与范围恢复成全部 */
  fun selectAllServers() {
    val all = servers.value
    applyScope(all, all.map { it.id }.toSet())
  }

  private fun applyScope(all: List<EmbyServer>, next: Set<Long>) {
    val normalized = next.intersect(all.map { it.id }.toSet())
    if (normalized.isEmpty()) return

    val isAll = normalized.size == all.size
    _scopeIds.value = if (isAll) null else normalized
    // 全选写空串：以后新加的服务器才会自动参与
    browserPreferences.embyAggregateServerIds.set(
      if (isAll) "" else normalized.sorted().joinToString(","),
    )

    if (sessionTerm.isEmpty()) {
      refreshOutcomes()
      return
    }

    val wasParticipating = sessionServers.map { it.id }.toSet()
    val participants = all.filter { isParticipating(it.id) }
    sessionServers = participants

    // 取消参与：本地剔除即可，不必发请求
    (wasParticipating - participants.map { it.id }.toSet()).forEach { id -> arrived.remove(id) }
    // 新增参与：只补查这一台（其余批次还在，不重头再来）
    val toQuery = participants.filter { it.id !in wasParticipating }
    refreshOutcomes()
    publishSnapshot()
    toQuery.forEach { querySingle(it) }
  }

  // ==================== 组织方式 ====================

  fun toggleMergeMode() {
    val next =
      if (_mergeMode.value == AggregateMergeMode.MERGE) {
        AggregateMergeMode.GROUP
      } else {
        AggregateMergeMode.MERGE
      }
    _mergeMode.value = next
    browserPreferences.embyAggregateMergeMode.set(next)
    publishSnapshot()
  }

  /**
   * 换排序。
   *
   * 纯客户端重排：不重发请求、不动已到达的批次，只按新口径重算快照。
   * 排序**只影响行顺序**，「默认打开哪一台」仍由 `compareHits` 决定（见 [AggregateSort]）。
   */
  fun setSort(next: AggregateSort) {
    if (_sort.value == next) return
    _sort.value = next
    browserPreferences.embyAggregateSort.set(next)
    publishSnapshot()
  }

  // ==================== 抽屉 ====================

  fun openDrawer(entry: AggregateEntry) {
    _drawerEntry.value = entry
  }

  fun closeDrawer() {
    _drawerEntry.value = null
  }

  // ==================== 重试 ====================

  /** 重试失败 / 跳过的那几台（成功的不重查，避免整页闪） */
  fun retryFailed() {
    val targets =
      sessionServers.filter { server ->
        val phase = _outcomes.value.firstOrNull { it.serverId == server.id }?.phase
        phase == ServerPhase.Failed || phase == ServerPhase.Skipped
      }
    targets.forEach { querySingle(it) }
  }

  /** 整轮重查（下拉刷新 / 失败重试） */
  fun refresh() {
    val trimmed = _term.value.trim()
    if (trimmed.isEmpty()) return
    searchJob?.cancel()
    searchJob = viewModelScope.launch { beginSession(trimmed) }
  }

  // ==================== 会话 ====================

  /**
   * 开一次新会话：清空旧批次 → 并发查参与服务器 → 每批到达就刷快照。
   *
   * ⚠️ 这里不 `try/catch` 包住 `collect`：`search()` 内部已把单台失败折成批次，
   * 还能抛出来的只有「外层被取消」，交给协程取消语义处理即可。
   */
  private suspend fun beginSession(term: String) {
    sessionSeq += 1
    val seq = sessionSeq
    sessionTerm = term

    val all = servers.value
    sessionServers = all.filter { isParticipating(it.id) }
    arrived.clear()
    _snapshot.value = AggregateSnapshot()
    _outcomes.value =
      sessionServers.map { ServerOutcome(it.id, it.name, ServerPhase.Running) }
    _running.value = sessionServers.isNotEmpty()

    if (sessionServers.isEmpty()) {
      _running.value = false
      return
    }

    engine.search(sessionServers, term, itemTypes = null).collect { batch ->
      if (seq != sessionSeq) return@collect
      acceptBatch(batch)
    }
    if (seq == sessionSeq) {
      _running.value = false
      publishSnapshot()
    }
  }

  /** 补查一台（参与范围变化 / 重试单台），结果并进当前会话 */
  private fun querySingle(server: EmbyServer) {
    val seq = sessionSeq
    if (sessionTerm.isEmpty()) return
    viewModelScope.launch {
      markRunning(server.id)
      val batch = engine.queryOne(server, serverOrderOf(server), sessionTerm, itemTypes = null)
      // 会话已经换了 / 用户又取消了这台 ⇒ 结果作废
      if (seq != sessionSeq) return@launch
      if (sessionServers.none { it.id == server.id }) return@launch
      acceptBatch(batch)
      _running.value = _outcomes.value.any { !it.phase.isTerminal }
    }
  }

  private fun acceptBatch(batch: ServerBatch) {
    if (sessionServers.none { it.id == batch.server.id }) return
    arrived[batch.server.id] = batch
    val phase =
      when {
        batch.skipped -> ServerPhase.Skipped
        batch.error != null -> ServerPhase.Failed
        batch.items.isEmpty() -> ServerPhase.Empty
        else -> ServerPhase.Ok
      }
    _outcomes.update { list ->
      list.map { outcome ->
        if (outcome.serverId != batch.server.id) {
          outcome
        } else {
          outcome.copy(
            phase = phase,
            count = batch.items.size,
            elapsedMs = batch.elapsedMs,
            message = batch.failureMessage,
          )
        }
      }
    }
    publishSnapshot()
    _running.value = _outcomes.value.any { !it.phase.isTerminal }
  }

  private fun abandonSession() {
    sessionSeq += 1
    sessionTerm = ""
    arrived.clear()
    sessionServers = emptyList()
    _snapshot.value = AggregateSnapshot()
    _running.value = false
    refreshOutcomes()
  }

  /** 按当前参与范围铺一遍「未开始」状态（没在搜索时也要看得见 chip 的参与态） */
  private fun refreshOutcomes() {
    val all = servers.value
    val participants = all.filter { isParticipating(it.id) }
    sessionServers = participants
    _outcomes.value =
      participants.map { server ->
        val known = _outcomes.value.firstOrNull { it.serverId == server.id }
        if (sessionTerm.isEmpty()) {
          ServerOutcome(server.id, server.name, ServerPhase.Pending)
        } else {
          known ?: ServerOutcome(server.id, server.name, ServerPhase.Pending)
        }
      }
  }

  private fun publishSnapshot() {
    val batches = sessionServers.mapNotNull { arrived[it.id] }
    _snapshot.value =
      engine.snapshot(
        batches = batches,
        mode = _mergeMode.value,
        term = sessionTerm,
        currentServerId = repository.currentServer.value?.id,
        sort = _sort.value,
      )
  }

  private fun markRunning(serverId: Long) {
    _outcomes.update { list ->
      list.map {
        if (it.serverId == serverId) it.copy(phase = ServerPhase.Running, message = null) else it
      }
    }
    _running.value = true
  }

  private fun serverOrderOf(server: EmbyServer): Int =
    servers.value.indexOfFirst { it.id == server.id }.coerceAtLeast(0)

  private fun recordHistory(keyword: String) {
    if (keyword.isEmpty()) return
    viewModelScope.launch { searchHistoryRepository.record(HISTORY_SERVER_ID, keyword) }
  }

  // ==================== 参与范围解析 ====================

  private fun readScope(): Set<Long>? {
    val raw = browserPreferences.embyAggregateServerIds.get()
    if (raw.isBlank()) return null
    val ids = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    return ids.ifEmpty { null }
  }

  /**
   * 参与集与当前服务器列表求交。
   *
   * 交集为空（例如存的 id 全被删了）时退回「全部参与」——
   * 显示「一台都不参与」会让页面永远搜不出东西，且用户找不到恢复入口。
   */
  private fun resolveScope(all: List<EmbyServer>): Set<Long> {
    val allIds = all.map { it.id }.toSet()
    val declared = _scopeIds.value ?: return allIds
    val live = declared.intersect(allIds)
    return live.ifEmpty { allIds }
  }

  companion object {
    /**
     * 聚合搜索的历史分区 id。
     *
     * 负数不会被任何 `EmbyServer.id` 占用（Room 自增主键从 1 开始），
     * 所以这一份历史与「每台服务器各自的搜索历史」天然隔开。
     */
    const val HISTORY_SERVER_ID = -1L

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { AggregateSearchViewModel(application) }
    }
  }
}
