package app.marlboroadvance.mpvex.ui.browser.emby.aggsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
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

  /**
   * 参与范围（UI 可观察）。`null` = 全部参与。
   *
   * 必须暴露成 StateFlow 而不是只留一个同步读方法：chip 的填充态、
   * 「已选 N/M 台」这类文案都依赖它，靠 `outcomes` 变化顺带触发重组太隐晦，
   * 一旦某条路径只改范围不改 outcomes，界面就会停在旧选中态。
   */
  val scopeIds: StateFlow<Set<Long>?> = _scopeIds.asStateFlow()

  // ─── 结果 ───

  private val _outcomes = MutableStateFlow<List<ServerOutcome>>(emptyList())
  val outcomes: StateFlow<List<ServerOutcome>> = _outcomes.asStateFlow()

  private val _snapshot = MutableStateFlow(AggregateSnapshot())
  val snapshot: StateFlow<AggregateSnapshot> = _snapshot.asStateFlow()

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  /** 正在补页（与首屏的 [running] 分开：翻页时不该让状态行说「检索中」） */
  private val _loadingMore = MutableStateFlow(false)
  val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

  /**
   * 还有可继续拉取的页。
   *
   * 判据是「某台尚未到底、未失败、未达单台页数上限」，而不是「已渲染条数 < 上限」——
   * 后者在结果本就稀疏时会一直为真，触底就无限重试同一页。
   */
  private val _hasMore = MutableStateFlow(false)
  val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

  /** 已累积到全局安全上限 [AggregateSearchLimits.MAX_ENTRIES]（界面提示「已达上限」） */
  private val _atLimit = MutableStateFlow(false)
  val atLimit: StateFlow<Boolean> = _atLimit.asStateFlow()

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

  /** 当前会话已到达的批次，按 serverId 索引（翻页时是**累积后**的合并结果） */
  private val arrived = LinkedHashMap<Long, ServerBatch>()

  /** 各台媒体流的下一页起点（服务端口径，不是已渲染条数） */
  private val nextIndex = HashMap<Long, Int>()

  /** 各台已拉取的页数（含首屏那一页），用于单台上限 */
  private val pages = HashMap<Long, Int>()

  /** 已经到底的台（返回不满一页 / 到总数 / 该页失败） */
  private val exhaustedServers = HashSet<Long>()

  init {
    // 首屏就把参与范围铺出来：用户一进来能看见「哪几台参与、哪几台不参与」，
    // **先选定范围、再输入关键词** —— 没选中的台一个请求都不会收到。
    refreshOutcomes()
    // ⚠️ `servers` 是 stateIn(Eagerly) 出来的，构造时还是空列表：只在 init 里铺一次
    //    会导致「刚进页面看不到任何 chip，搜过一次之后才出现」。
    //    这里订阅列表变化重新铺一遍（服务器增删也走这条）。
    viewModelScope.launch {
      servers.collect { list -> syncWithServers(list) }
    }
  }

  /**
   * 服务器列表变化时的对齐。
   *
   * 三种情况必须区分：
   * - **没有会话**（还没输入关键词）：整条按当前偏好重铺 Pending，用户此刻就能选；
   * - **会话进行中**：保留已到达的状态，只补上新出现的参与台、剔除已消失的台；
   * - **刚启动**：`previousIds` 为空，等价于第一种。
   *
   * 不在这里重发整轮请求：列表变化不是「用户改主意」，重查所有台既慢又浪费；
   * 新出现的台单独补查一台即可。
   */
  private fun syncWithServers(all: List<EmbyServer>) {
    val participants = all.filter { isParticipating(it.id) }
    val previousIds = sessionServers.map { it.id }.toSet()
    val currentIds = participants.map { it.id }.toSet()

    sessionServers = participants
    // ⚠️ 铺的是**全部**服务器，不是只有参与的那些：
    //    chip 条是唯一能把某台切回参与范围的地方，取消参与的台若从条上消失，
    //    用户就只能靠「全选」把它找回来。
    _outcomes.value =
      all.map { server ->
        val known = _outcomes.value.firstOrNull { it.serverId == server.id }
        // 会话进行中才保留已知状态；否则一律 Pending（上一轮的结果不该留在 chip 上）
        if (sessionTerm.isNotEmpty() && known != null) {
          known
        } else {
          ServerOutcome(server.id, server.name, ServerPhase.Pending)
        }
      }

    if (sessionTerm.isEmpty()) return

    // 台被删 / 被取消参与 ⇒ 丢掉它的批次，避免结果里留着一条打不开的
    (previousIds - currentIds).forEach { dropServer(it) }
    publishSnapshot()
    // 新出现的参与台：只补查它自己
    participants.filter { it.id !in previousIds }.forEach { querySingle(it) }
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

  /**
   * 取消全部参与（「取消全选」）。
   *
   * 不允许出现「一台都不参与」——那会让页面永远搜不出东西、也没有恢复入口，
   * 所以保留**当前服务器**一台（不在列表里时保留第一台）。
   * 保留当前服务器而不是第一台：用户刚看过的那台通常是这次想查的那台。
   */
  fun deselectAllServers() {
    val all = servers.value
    if (all.size < 2) return
    val keep = all.firstOrNull { it.id == currentServer.value?.id } ?: all.first()
    applyScope(all, setOf(keep.id))
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
    (wasParticipating - participants.map { it.id }.toSet()).forEach { id -> dropServer(id) }
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
    nextIndex.clear()
    pages.clear()
    exhaustedServers.clear()
    _atLimit.value = false
    _hasMore.value = false
    _loadingMore.value = false
    _snapshot.value = AggregateSnapshot()
    // 同 syncWithServers：chip 条要保**全部**服务器，未参与的留 Pending（灰 chip）
    val participants = sessionServers.map { it.id }.toSet()
    _outcomes.value =
      all.map { server ->
        ServerOutcome(
          serverId = server.id,
          name = server.name,
          phase = if (server.id in participants) ServerPhase.Running else ServerPhase.Pending,
        )
      }
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
      recomputePaging()
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

  /**
   * 收下一批。
   *
   * @param append true = 翻页结果，与已到达的条目合并（按条目 Id 去重）；
   *   false = 该台的首屏结果，直接替换
   */
  private fun acceptBatch(batch: ServerBatch, append: Boolean = false) {
    if (sessionServers.none { it.id == batch.server.id }) return
    val id = batch.server.id
    val prev = if (append) arrived[id] else null
    val combined =
      if (prev == null) {
        batch
      } else {
        batch.copy(
          items = mergeItems(prev.items, batch.items),
          // 合并后的批次不代表某一页，起点归零免得被误当作分页游标
          startIndex = 0,
        )
      }
    arrived[id] = combined
    nextIndex[id] = batch.nextStartIndex
    pages[id] = (pages[id] ?: 0) + 1
    // 取数失败也算到底：否则触底会对着同一页无限重试
    if (batch.exhausted || batch.error != null || batch.skipped) exhaustedServers += id

    val phase =
      when {
        batch.skipped -> ServerPhase.Skipped
        // 补页失败时保留已拿到的结果，不把整台降级成失败（chip 变红会让人以为一条都没有）
        batch.error != null ->
          if (append && combined.items.isNotEmpty()) ServerPhase.Ok else ServerPhase.Failed
        combined.items.isEmpty() -> ServerPhase.Empty
        else -> ServerPhase.Ok
      }
    _outcomes.update { list ->
      list.map { outcome ->
        if (outcome.serverId != id) {
          outcome
        } else {
          outcome.copy(
            phase = phase,
            count = combined.items.size,
            elapsedMs = batch.elapsedMs,
            message = batch.failureMessage,
          )
        }
      }
    }
    publishSnapshot()
    recomputePaging()
    _running.value = _outcomes.value.any { !it.phase.isTerminal }
  }

  /**
   * 触底补页：对**尚未到底**的参与台各拉一页。
   *
   * 串行而不是并发：翻页是低频动作，一次放大成 N 个并发请求只会把弱网拖慢，
   * 而首屏那次并发是为了「先回来的先出现」，两者目的不同。
   */
  fun loadMore() {
    if (_loadingMore.value || !_hasMore.value) return
    val seq = sessionSeq
    val term = sessionTerm
    if (term.isEmpty()) return

    val targets =
      sessionServers.filter { server ->
        val id = server.id
        val landed = arrived[id]
        id !in exhaustedServers &&
          (pages[id] ?: 0) < AggregateSearchLimits.MAX_PAGES_PER_SERVER &&
          landed != null &&
          landed.error == null &&
          !landed.skipped
      }
    if (targets.isEmpty()) {
      recomputePaging()
      return
    }

    _loadingMore.value = true
    viewModelScope.launch {
      for (server in targets) {
        if (seq != sessionSeq) break
        val start = nextIndex[server.id] ?: 0
        val batch = engine.queryOne(server, serverOrderOf(server), term, itemTypes = null, startIndex = start)
        if (seq != sessionSeq) break
        if (sessionServers.none { it.id == server.id }) continue
        acceptBatch(batch, append = true)
      }
      if (seq == sessionSeq) {
        _loadingMore.value = false
        recomputePaging()
      }
    }
  }

  /**
   * 重算「还能不能翻页」与「是否到达安全上限」。
   *
   * 上限按**已累积的原始条数**判（各台 items 之和），而不是合并后的行数：
   * 合并视图下去重会削掉条目，按行数判会让上限永远达不到。
   */
  private fun recomputePaging() {
    val raw = arrived.values.sumOf { it.items.size }
    val atLimit = raw >= AggregateSearchLimits.MAX_ENTRIES
    _atLimit.value = atLimit
    _hasMore.value =
      !atLimit &&
        sessionTerm.isNotEmpty() &&
        sessionServers.any { server ->
          val id = server.id
          val landed = arrived[id]
          id !in exhaustedServers &&
            (pages[id] ?: 0) < AggregateSearchLimits.MAX_PAGES_PER_SERVER &&
            landed != null &&
            landed.error == null &&
            !landed.skipped
        }
  }

  /** 丢掉一台的全部会话痕迹（批次 / 游标 / 页数 / 到底标记） */
  private fun dropServer(id: Long) {
    arrived.remove(id)
    nextIndex.remove(id)
    pages.remove(id)
    exhaustedServers.remove(id)
  }

  /**
   * 合并两页条目并按条目 Id 去重。
   *
   * 服务端翻页窗口在「同一秒内新增了条目」时会滑动，跨页重复是可能发生的；
   * 没有 Id 的条目用「名称 + 类型」兜底键，避免全部被当成同一条。
   */
  private fun mergeItems(old: List<EmbyItem>, fresh: List<EmbyItem>): List<EmbyItem> {
    if (fresh.isEmpty()) return old
    if (old.isEmpty()) return fresh
    val seen = HashSet<String>((old.size + fresh.size) * 2)
    val out = ArrayList<EmbyItem>(old.size + fresh.size)
    for (item in old + fresh) {
      val key = item.Id?.takeIf { it.isNotBlank() } ?: "${item.Name}|${item.Type}"
      if (seen.add(key)) out.add(item)
    }
    return out
  }

  private fun abandonSession() {
    sessionSeq += 1
    sessionTerm = ""
    arrived.clear()
    nextIndex.clear()
    pages.clear()
    exhaustedServers.clear()
    sessionServers = emptyList()
    _snapshot.value = AggregateSnapshot()
    _running.value = false
    _loadingMore.value = false
    _hasMore.value = false
    _atLimit.value = false
    refreshOutcomes()
  }

  /**
   * 按当前参与范围铺一遍 chip 条。
   *
   * ⚠️ 铺的是**全部**服务器而不是只有参与的那些：chip 条是唯一能把某台切回
   * 参与范围的地方，未参与的台若从条上消失，用户就只能靠「全选」找回。
   * 未参与的台给 [ServerPhase.Pending]，渲染成灰 chip，不参与也不会发请求。
   */
  private fun refreshOutcomes() {
    val all = servers.value
    sessionServers = all.filter { isParticipating(it.id) }
    _outcomes.value =
      all.map { server ->
        val known = _outcomes.value.firstOrNull { it.serverId == server.id }
        // 会话进行中才保留已知状态；否则一律 Pending（上一轮结果不该留在 chip 上）
        if (sessionTerm.isNotEmpty() && known != null) {
          known
        } else {
          ServerOutcome(server.id, server.name, ServerPhase.Pending)
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
