package app.marlboroadvance.mpvex.ui.browser.emby.smartmerge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeCandidate
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_IGNORED
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_MERGED
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeManager
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeMatcher
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeOperation
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class SmartMergePreviewRequest(
  val url: String,
  val headers: Map<String, String>,
  val title: String,
)

data class SmartMergeUiState(
  val server: EmbyServer? = null,
  val libraries: List<EmbyItem> = emptyList(),
  val selectedLibraryIds: Set<String> = emptySet(),
  val admin: Boolean = false,
  val loading: Boolean = true,
  val error: String? = null,
  val manualQuery: String = "",
  val manualLoading: Boolean = false,
  val manualResults: List<SmartMergeItem> = emptyList(),
  /**
   * 「待处理清单」：从多次手动搜索结果里逐个加入的条目。
   *
   * 之前每次搜索都会覆盖上一批的勾选，所以「先搜 A、再搜 B、再搜 C，最后一起组合」
   * 根本做不到。现在清单独立于搜索结果：换关键词、清空结果都不会丢，
   * 只有组合成功 / 手动移除 / 切换服务器时才清空。
   */
  val tray: List<SmartMergeItem> = emptyList(),
)

/** 「组合为候选」的结果：成功时页面可以关掉清单；失败时把原因显示在弹窗里。 */
data class SmartMergeTrayResult(
  val success: Boolean,
  val message: String,
)

class SmartMergeViewModel(application: Application) : AndroidViewModel(application), KoinComponent {
  private val repository by inject<EmbyRepository>()
  private val manager by inject<SmartMergeManager>()

  val mergeState = manager.state
  val scanning = manager.scanning
  val paused = manager.paused
  val progress = manager.scanProgress
  val message = manager.message

  private val _ui = MutableStateFlow(SmartMergeUiState())
  val ui: StateFlow<SmartMergeUiState> = _ui.asStateFlow()

  /** 一次性提示（加入失败、候选生成成功等），由页面用 Snackbar 消费。 */
  private val _notice = MutableStateFlow<String?>(null)
  val notice: StateFlow<String?> = _notice.asStateFlow()

  fun clearNotice() {
    _notice.value = null
  }

  init {
    viewModelScope.launch {
      repository.currentServer.collect { server ->
        val sameServer = _ui.value.server?.id == server?.id
        _ui.update {
          it.copy(
            server = server,
            loading = server != null,
            // 换服务器后旧服务器的条目 Id 不再有效，清单必须清掉
            tray = if (sameServer) it.tray else emptyList(),
          )
        }
        if (server == null) {
          _ui.update { it.copy(loading = false, error = "还没有连接 Emby 服务器") }
          return@collect
        }
        manager.activate(server.id)
        val libraries = runCatching { repository.getVirtualFolders(server) }.getOrDefault(emptyList())
        val admin = withContext(Dispatchers.IO) { EmbyClient.fetchIsAdmin(server) }
        _ui.update {
          it.copy(
            server = server,
            libraries = libraries,
            selectedLibraryIds = libraries.mapNotNull { lib -> lib.Id }.toSet(),
            admin = admin,
            loading = false,
            error = null,
          )
        }
      }
    }
  }

  fun toggleLibrary(id: String) {
    _ui.update {
      val next = if (id in it.selectedLibraryIds) it.selectedLibraryIds - id else it.selectedLibraryIds + id
      it.copy(selectedLibraryIds = next)
    }
  }

  fun selectAllLibraries() {
    _ui.update { it.copy(selectedLibraryIds = it.libraries.mapNotNull { lib -> lib.Id }.toSet()) }
  }

  fun invertLibraries() {
    _ui.update {
      val all = it.libraries.mapNotNull { lib -> lib.Id }.toSet()
      it.copy(selectedLibraryIds = all - it.selectedLibraryIds)
    }
  }

  fun clearLibraries() {
    _ui.update { it.copy(selectedLibraryIds = emptySet()) }
  }

  fun updateRules(rules: SmartMergeRules) = manager.updateRules(rules)

  fun itemImageUrl(item: SmartMergeItem): String? {
    val server = _ui.value.server ?: return null
    return EmbyClient.imageUrl(server, item.id, "Primary", item.imageTag, maxWidth = 220)
  }

  /**
   * 小窗预览播放用的请求：直连原文件 + 与主播放器一致的 UA/Referer。
   * 不拉起全屏 PlayerActivity，避免用户「点一下预览就回不来了」。
   */
  fun previewRequest(item: SmartMergeItem): SmartMergePreviewRequest? {
    val server = _ui.value.server ?: return null
    val sourceId = item.mediaSources.firstOrNull()?.id
    val url = repository.videoStreamUrl(
      server = server,
      itemId = item.id,
      static = true,
      mediaSourceId = sourceId,
    )
    val scheme = if (server.useHttps) "https" else "http"
    val headers = mapOf(
      "User-Agent" to EmbyClient.STREAM_USER_AGENT,
      "Referer" to "$scheme://${server.host}:${server.port}",
    )
    return SmartMergePreviewRequest(url = url, headers = headers, title = item.displayTitle)
  }

  fun scanSelected() {
    val server = _ui.value.server ?: return
    manager.scan(server, _ui.value.selectedLibraryIds.toList())
  }

  fun scanAll() {
    val server = _ui.value.server ?: return
    manager.scan(server, _ui.value.libraries.mapNotNull { it.Id })
  }

  fun cancelScan() = manager.cancelScan()

  fun pauseScan() = manager.pauseScan()

  fun resumeScan() = manager.resumeScan()

  fun removeCandidateItem(candidate: SmartMergeCandidate, itemId: String) {
    manager.removeCandidateItem(candidate, itemId)
  }

  fun merge(candidate: SmartMergeCandidate, primaryId: String?) {
    val server = _ui.value.server ?: return
    manager.merge(server, candidate, primaryId)
  }

  fun split(operation: SmartMergeOperation) {
    val server = _ui.value.server ?: return
    manager.split(server, operation)
  }

  fun ignore(candidate: SmartMergeCandidate) = manager.ignore(candidate)

  fun clearMessage() = manager.clearMessage()

  fun setManualQuery(query: String) {
    _ui.update { it.copy(manualQuery = query) }
  }

  fun manualSearch() {
    val server = _ui.value.server ?: return
    val query = _ui.value.manualQuery.trim()
    if (query.isEmpty()) return
    viewModelScope.launch {
      _ui.update { it.copy(manualLoading = true) }
      val results = runCatching { manager.search(server, query) }.getOrDefault(emptyList())
      _ui.update {
        it.copy(
          manualLoading = false,
          manualResults = results,
        )
      }
    }
  }

  /**
   * 把搜索结果加入 / 移出「待处理清单」。
   *
   * 清单跨搜索保留，所以用户可以先搜 A 加入，再改关键词搜 B、C 加入，
   * 最后一次性组合。已经是多版本的条目不接受加入（Emby 不允许重复组合，
   * 也避免把已合并的结果再喂给 MergeVersions）。
   */
  fun toggleTrayItem(item: SmartMergeItem) {
    if (item.alreadyMultiVersion) {
      _notice.value = "「${item.displayTitle}」已经是多版本条目，不能再加入"
      return
    }
    _ui.update { state ->
      val exists = state.tray.any { it.id == item.id }
      state.copy(
        tray = if (exists) state.tray.filterNot { it.id == item.id } else state.tray + item,
      )
    }
  }

  fun removeFromTray(id: String) {
    _ui.update { state -> state.copy(tray = state.tray.filterNot { it.id == id }) }
  }

  fun clearTray() {
    _ui.update { it.copy(tray = emptyList()) }
  }

  /**
   * 用「待处理清单」生成一个候选。
   *
   * @return `success=true` 时调用方应关闭清单并跳到「待处理」；`false` 时把
   *         [SmartMergeTrayResult.message] 直接显示在弹窗里 —— 全屏 Dialog 会盖住
   *         Snackbar，之前失败时用户看到的就是「点了没反应」。
   *         真正调用 Emby MergeVersions 仍然在候选详情页，这里只生成候选。
   */
  fun createCandidateFromTray(): SmartMergeTrayResult {
    val state = _ui.value
    val server = state.server
      ?: return SmartMergeTrayResult(false, "还没有连接 Emby 服务器")
    if (state.tray.size < 2) {
      return SmartMergeTrayResult(false, "至少需要 2 个条目才能组合")
    }
    val trayIds = state.tray.map { it.id }.sorted()
    val duplicate = mergeState.value.candidates.firstOrNull { existing ->
      existing.itemIds.sorted() == trayIds &&
        existing.status != STATUS_MERGED &&
        existing.status != STATUS_IGNORED
    }
    if (duplicate != null) {
      // 不是失败：这组条目已经生成过候选，直接清空清单并让用户去「待处理」确认。
      _ui.update {
        it.copy(
          tray = emptyList(),
          manualQuery = "",
          manualResults = emptyList(),
        )
      }
      val message = "这组条目已经有候选（${duplicate.code}），已在「待处理」里"
      _notice.value = message
      return SmartMergeTrayResult(true, message)
    }
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = server.id,
      items = state.tray,
      rules = mergeState.value.rules,
    ) ?: return SmartMergeTrayResult(
      false,
      "无法用这些条目生成候选，请检查条目是否有效，或重新搜索再试",
    )
    manager.addManualCandidate(candidate)
    _ui.update {
      it.copy(
        tray = emptyList(),
        manualQuery = "",
        manualResults = emptyList(),
      )
    }
    val message = "已生成候选，请在「待处理」中确认合并"
    _notice.value = message
    return SmartMergeTrayResult(true, message)
  }

  /** 关闭手动搜索页：只清当前搜索词与结果，**保留待处理清单**。 */
  fun clearManualSearch() {
    _ui.update {
      it.copy(manualQuery = "", manualResults = emptyList())
    }
  }

  companion object {
    fun factory(application: Application) = viewModelFactory {
      initializer { SmartMergeViewModel(application) }
    }
  }
}
