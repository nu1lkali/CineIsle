package app.marlboroadvance.mpvex.domain.smartmerge

import android.content.Context
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Smart Merge 的进程级协调器（Koin single）。
 *
 * 扫描跑在自己的 [scope] 里，页面退出不会取消 —— 这与需求里「扫描任务需要能在页面退出后
 * 继续执行」一致。进程被杀时任务会停，但扫描结果/操作账本已经落盘，下次进入可继续查看。
 *
 * 这里不做「自动合并」：所有 merge 都必须由 UI 明确调用。
 */
class SmartMergeManager(
  private val repository: EmbyRepository,
  context: Context,
) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val journal = SmartMergeJournal(File(context.filesDir, "smart_merge"))
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow(SmartMergeState())
  val state: StateFlow<SmartMergeState> = _state.asStateFlow()

  private val _scanProgress = MutableStateFlow(SmartMergeScanProgress())
  val scanProgress: StateFlow<SmartMergeScanProgress> = _scanProgress.asStateFlow()

  private val _scanning = MutableStateFlow(false)
  val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

  private val _paused = MutableStateFlow(false)
  val paused: StateFlow<Boolean> = _paused.asStateFlow()
  @Volatile private var pauseRequested = false

  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  private var activeServerId: Long = 0L
  private var scanJob: Job? = null

  fun activate(serverId: Long) {
    if (serverId == activeServerId) return
    activeServerId = serverId
    _state.value = journal.load(serverId)
    _scanProgress.value = SmartMergeScanProgress()
  }

  fun scan(server: EmbyServer, libraryIds: List<String>) {
    if (_scanning.value) return
    activate(server.id)
    scanJob = scope.launch {
      _scanning.value = true
      _scanProgress.value = SmartMergeScanProgress()
      _message.value = null
      try {
        val all = LinkedHashMap<String, SmartMergeItem>()
        var total = 0
        var scanned = 0
        for (libraryId in libraryIds.ifEmpty { listOf("") }) {
          var start = 0
          while (true) {
            val page = repository.getItems(
              server = server,
              parentId = libraryId.takeIf { it.isNotBlank() },
              includeItemTypes = listOf("Movie", "Video"),
              recursive = true,
              isFolder = false,
              startIndex = start,
              limit = SCAN_PAGE_SIZE,
              fields = SMART_MERGE_FIELDS,
            )
            if (start == 0) total += page.TotalRecordCount
            page.Items.forEach { raw ->
              SmartMergeMapper.from(raw)?.let { item ->
                all[item.id] = item
              }
            }
            scanned += page.Items.size
            _scanProgress.value = SmartMergeScanProgress(
              scanned = scanned,
              total = total,
              candidateCount = _state.value.candidates.size,
            )
            while (pauseRequested) {
              delay(200)
            }
            if (page.Items.isEmpty() || page.Items.size < SCAN_PAGE_SIZE) break
            start += page.Items.size
            if (start >= 100_000) break
          }
        }
        val candidates = SmartMergeMatcher.buildCandidates(
          serverId = server.id,
          items = all.values.toList(),
          ignoreRules = _state.value.ignoreRules,
          rules = _state.value.rules,
        )
        val next = journal.prune(
          _state.value.copy(
            serverId = server.id,
            candidates = candidates,
            lastScanAt = System.currentTimeMillis(),
            scannedCount = all.size,
            lastScanLibraryIds = libraryIds,
          ),
        )
        _state.value = next
        journal.save(next)
        _scanProgress.value = _scanProgress.value.copy(candidateCount = candidates.size)
        _message.value = "扫描完成：${all.size} 个条目，${candidates.size} 个候选组"
      } catch (e: CancellationException) {
        _message.value = "扫描已取消"
        throw e
      } catch (e: Throwable) {
        _message.value = "扫描失败：${e.message ?: e::class.simpleName}"
      } finally {
        _scanning.value = false
        _paused.value = false
        pauseRequested = false
      }
    }
  }

  fun pauseScan() {
    if (!_scanning.value) return
    pauseRequested = true
    _paused.value = true
    _message.value = "扫描已暂停"
  }

  fun resumeScan() {
    pauseRequested = false
    _paused.value = false
    if (_scanning.value) _message.value = "继续扫描"
  }

  fun cancelScan() {
    pauseRequested = false
    _paused.value = false
    scanJob?.cancel()
    scanJob = null
    _scanning.value = false
  }

  /** 从候选组移除一个条目；剩余不足两个时拒绝。 */
  fun removeCandidateItem(candidate: SmartMergeCandidate, itemId: String) {
    val remaining = candidate.items.filterNot { it.id == itemId }
    if (remaining.size < 2) {
      _message.value = "至少保留两个条目；如果只剩一个，请直接忽略该候选"
      return
    }
    val rebuilt = SmartMergeMatcher.candidateFromSelection(
      serverId = candidate.serverId,
      items = remaining,
      rules = _state.value.rules,
    ) ?: candidate.copy(
      itemIds = remaining.map { it.id },
      items = remaining,
    )
    val next = _state.value.copy(
      candidates = _state.value.candidates.map {
        if (it.id == candidate.id) rebuilt.copy(id = candidate.id) else it
      },
    )
    _state.value = next
    journal.save(next)
    _message.value = "已从候选组移除条目"
  }

  suspend fun search(server: EmbyServer, query: String): List<SmartMergeItem> =
    withContext(Dispatchers.IO) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) return@withContext emptyList()
      val rawItems = if (trimmed.matches(Regex("[0-9a-fA-F]{24,40}"))) {
        repository.getItemsByIds(server, listOf(trimmed))
      } else {
        repository.getItems(
          server = server,
          searchTerm = trimmed,
          includeItemTypes = listOf("Movie", "Video"),
          recursive = true,
          isFolder = false,
          limit = 60,
          fields = SMART_MERGE_FIELDS,
        ).Items
      }
      rawItems.mapNotNull(SmartMergeMapper::from)
    }

  fun addManualCandidate(candidate: SmartMergeCandidate) {
    val current = _state.value
    val next = current.copy(
      candidates = (listOf(candidate) + current.candidates.filterNot { it.id == candidate.id })
        .sortedByDescending { it.confidence },
    )
    _state.value = next
    journal.save(next)
  }

  fun updateRules(rules: SmartMergeRules) {
    val next = _state.value.copy(rules = rules)
    _state.value = next
    journal.save(next)
  }

  fun ignore(candidate: SmartMergeCandidate, reason: String? = null) {
    val current = _state.value
    val rule = SmartMergeIgnoreRule(
      id = "${candidate.serverId}:code:${candidate.code.lowercase()}",
      serverId = candidate.serverId,
      type = "code",
      value = candidate.code,
      reason = reason,
      createdAt = System.currentTimeMillis(),
    )
    val next = current.copy(
      candidates = current.candidates.map {
        if (it.id == candidate.id) it.copy(status = STATUS_IGNORED) else it
      },
      ignoreRules = (current.ignoreRules + rule).distinctBy { it.id },
    )
    _state.value = next
    journal.save(next)
    _message.value = "已忽略 ${candidate.code}"
  }

  fun merge(
    server: EmbyServer,
    candidate: SmartMergeCandidate,
    selectedPrimaryId: String? = null,
  ) {
    scope.launch { performMerge(server, candidate, selectedPrimaryId) }
  }

  /**
   * 与 [merge] 相同，但**挂起等待结果**。
   *
   * 媒体库长按「合并同番号」需要在合并完成后立刻把被合并掉的卡片从本地列表里移除，
   * 不能等用户手动下拉刷新。安全检查、快照、MergeVersions、重新查询验证全部复用
   * [performMerge]，所以两条入口的行为与历史记录完全一致。
   */
  suspend fun mergeNow(
    server: EmbyServer,
    candidate: SmartMergeCandidate,
    selectedPrimaryId: String? = null,
  ): SmartMergeOperation? = withContext(Dispatchers.IO) {
    performMerge(server, candidate, selectedPrimaryId)
  }

  @Suppress("UNUSED_PARAMETER")
  private suspend fun performMerge(
    server: EmbyServer,
    candidate: SmartMergeCandidate,
    selectedPrimaryId: String?,
  ): SmartMergeOperation? {
    _message.value = null
    return try {
      if (candidate.status == STATUS_MERGED) {
        _message.value = "这个候选已经合并过，不能重复合并"
        return null
      }
      if (candidate.status == STATUS_IGNORED) {
        _message.value = "这个候选已被忽略"
        return null
      }
      if (!EmbyClient.fetchIsAdmin(server)) {
        _message.value = "需要 Emby 管理员权限才能合并"
        return null
      }
      val ids = candidate.itemIds.distinct()
      if (ids.size < 2) {
        _message.value = "至少需要两个条目"
        return null
      }
      // 防止「已经合过、但本地状态没更新」：原始 Id 至少要有两个还在。
      val existing = runCatching { repository.getItemsByIds(server, ids) }.getOrDefault(emptyList())
      if (existing.mapNotNull { it.Id }.distinct().size < 2) {
        _message.value = "条目已发生变化（可能已经合并或删除），请重新扫描"
        return null
      }
      if (existing.any { it.MediaSources.orEmpty().size > 1 }) {
        _message.value = "其中已有条目是多版本状态，可能已经合并过；请重新扫描"
        return null
      }
      val operationId = "merge-${System.currentTimeMillis()}"
      val snapshots = ids.mapNotNull { itemId ->
        runCatching {
          val item = EmbyClient.getItem(server, itemId)
          journal.saveRawSnapshot(operationId, itemId, json.encodeToString(item))
          SmartMergeItemSnapshot(
            itemId = itemId,
            name = item.Name.orEmpty(),
            path = item.Path,
            providerIds = item.ProviderIds,
            mediaSourcePaths = item.MediaSources.orEmpty().mapNotNull { it.Path },
            userDataJson = item.UserData?.let { json.encodeToString(it) },
            rawFile = "$operationId/$itemId.json",
          )
        }.getOrNull()
      }
      val running = SmartMergeOperation(
        id = operationId,
        serverId = server.id,
        candidateId = candidate.id,
        candidateCode = candidate.code,
        candidateTitle = candidate.title,
        originalItemIds = ids,
        snapshots = snapshots,
        status = OP_RUNNING,
        serverVersion = server.version,
        createdAt = System.currentTimeMillis(),
      )
      saveOperation(running)

      EmbyClient.mergeVersions(server, ids)

      // 官方返回空 body，必须重新查询验证：优先找「MediaSources 数量 >= 2」的条目。
      val after = runCatching { repository.getItemsByIds(server, ids) }.getOrDefault(emptyList())
      val snapshotPaths = snapshots.flatMap { it.mediaSourcePaths }.toSet()
      val byId = after.firstOrNull { item ->
        val paths = item.MediaSources.orEmpty().mapNotNull { it.Path }
        paths.count { it in snapshotPaths } >= 2
      } ?: after.firstOrNull()
      // 原 ItemId 可能全部失效：再用番号/标题在库内找一次多源条目。
      val bySearch = if (byId == null) {
        val term = candidate.code.ifBlank { candidate.title }
        runCatching {
          repository.getItems(
            server = server,
            searchTerm = term,
            includeItemTypes = listOf("Movie", "Video"),
            recursive = true,
            isFolder = false,
            limit = 20,
            fields = SMART_MERGE_FIELDS,
          ).Items.firstOrNull { item ->
            val paths = item.MediaSources.orEmpty().mapNotNull { it.Path }
            paths.count { it in snapshotPaths } >= 2 ||
              item.MediaSources.orEmpty().size >= 2
          }
        }.getOrNull()
      } else {
        null
      }
      val result = byId ?: bySearch
      val resultVersionNames = result?.MediaSources.orEmpty().mapNotNull { source ->
        source.Name?.takeIf { it.isNotBlank() }
          ?: source.Path?.substringAfterLast('/')?.substringAfterLast('\\')
      }
      val done = running.copy(
        resultItemId = result?.Id,
        resultVersionNames = resultVersionNames,
        status = if (result != null) OP_DONE else OP_VERIFY_FAILED,
        completedAt = System.currentTimeMillis(),
        error = if (result == null) "合并接口已返回，但未查询到组合后的条目；请在 Emby 中人工确认" else null,
      )
      saveOperation(done)
      if (done.status == OP_DONE) {
        val next = _state.value.copy(
          candidates = _state.value.candidates.map {
            if (it.id == candidate.id) it.copy(status = STATUS_MERGED) else it
          },
        )
        _state.value = next
        journal.save(next)
        _message.value = "已提交合并：${candidate.title}"
      } else {
        _message.value = done.error
      }
      done
    } catch (e: Throwable) {
      _message.value = "合并失败：${e.message ?: e::class.simpleName}"
      null
    }
  }

  fun split(server: EmbyServer, operation: SmartMergeOperation) {
    scope.launch {
      if (!SMART_MERGE_SPLIT_VERIFIED) {
        _message.value = "拆分接口尚未在测试服验证，已阻止执行；请先按阶段 0 清单验证"
        return@launch
      }
      val itemId = operation.resultItemId
      if (itemId.isNullOrBlank()) {
        _message.value = "没有可拆分的组合结果"
        return@launch
      }
      try {
        if (!EmbyClient.fetchIsAdmin(server)) {
          _message.value = "需要 Emby 管理员权限才能拆分"
          return@launch
        }
        EmbyClient.removeAlternateSources(server, itemId)
        val next = operation.copy(
          status = OP_SPLIT,
          completedAt = System.currentTimeMillis(),
          error = null,
        )
        saveOperation(next)
        _message.value = "已调用拆分接口；请到 Emby 中核对文件与条目"
      } catch (e: Throwable) {
        saveOperation(
          operation.copy(
            status = OP_SPLIT_FAILED,
            completedAt = System.currentTimeMillis(),
            error = e.message ?: e::class.simpleName,
          ),
        )
        _message.value = "拆分失败：${e.message ?: e::class.simpleName}"
      }
    }
  }

  fun clearMessage() {
    _message.value = null
  }

  private fun saveOperation(operation: SmartMergeOperation) {
    val current = _state.value
    val operations = (listOf(operation) + current.operations.filterNot { it.id == operation.id })
      .take(50)
    val next = journal.prune(current.copy(operations = operations))
    _state.value = next
    journal.save(next)
  }

  companion object {
    private const val SCAN_PAGE_SIZE = 200

    /**
     * 扫描字段：必须包含 ProviderIds / MediaStreams / People / Runtime / Path。
     * 不用默认 ITEM_FIELDS（缺 ProviderIds / MediaStreams / OriginalTitle）。
     */
    private const val SMART_MERGE_FIELDS =
      "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources," +
        "MediaStreams,Tags,SortName,ProductionLocations,Path,Size,ProviderIds,OriginalTitle," +
        "ProductionYear,PremiereDate,DateCreated,RunTimeTicks"
  }
}
