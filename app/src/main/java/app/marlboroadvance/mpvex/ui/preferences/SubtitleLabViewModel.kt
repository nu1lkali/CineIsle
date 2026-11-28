package app.marlboroadvance.mpvex.ui.preferences

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTimeline
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstoneStore
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleCleanup
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleDownloader
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibrary
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveImport
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveStore
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitlePersist
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleTarget
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleScanner
import app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SmartSubtitleMatcher
import app.marlboroadvance.mpvex.ui.subtitle.lab.LabPreviewSource
import app.marlboroadvance.mpvex.ui.subtitle.lab.LabPreviewSubtitle
import app.marlboroadvance.mpvex.ui.subtitle.lab.LabSeekRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * 字幕实验室的状态。
 *
 * ## 它管什么
 *
 * 四件事：**选一部片**（继续观看 / 搜媒体库）、**把这部片交给共享的搜索下载逻辑**、
 * **管理本地库里已下载的字幕**（偏移 / 优先 / 删除 / 文件信息）、
 * **准备预览所需的播放地址与字幕路径**。
 *
 * ## 它故意不管什么
 *
 * 不自己搜、不自己下、不自己解析字幕 —— 那些都在
 * [app.marlboroadvance.mpvex.ui.subtitle.online.OnlineSubtitleViewModel] 里；
 * 也不碰 mpv —— 画面与字幕挂载在 [app.marlboroadvance.mpvex.ui.subtitle.lab.LabPreviewEngine]。
 * 实验室只是换了一个入口与布局（需求 11.1：三个入口不得重复实现业务逻辑）。
 */
class SubtitleLabViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val preferences by inject<SubtitlesPreferences>(SubtitlesPreferences::class.java)
  private val library = OnlineSubtitleLibrary(application, preferences)
  private val tombstoneStore = SubtitleTombstoneStore(preferences)
  private val downloader = OnlineSubtitleDownloader()

  data class State(
    val loadingMedia: Boolean = false,
    val servers: List<EmbyServer> = emptyList(),
    /** 「继续观看」列表：进实验室时默认的候选媒体来源（用户刚看过，正是要补字幕的那部）。 */
    val mediaCandidates: List<EmbyItem> = emptyList(),
    val selectedServer: EmbyServer? = null,
    val selectedItem: EmbyItem? = null,
    val target: OnlineSubtitleTarget? = null,
    val library: List<DownloadedSubtitle> = emptyList(),
    val message: String? = null,
    val mediaError: String? = null,
    // ── 媒体库筛选（用户反馈 2：不能只能全库搜索）──
    /** 服务器上的媒体库列表（`/Users/{uid}/Views`）。 */
    val libraries: List<EmbyItem> = emptyList(),
    val loadingLibraries: Boolean = false,
    /** 选中的媒体库 Id；null = 全部媒体库（服务器全库搜索） */
    val selectedLibraryId: String? = null,
    // ── 媒体库搜索（需求 7.3：从 Emby 媒体库选视频作预览素材，不能只有「继续观看」）──
    val mediaQuery: String = "",
    val searchingMedia: Boolean = false,
    val searchResults: List<EmbyItem> = emptyList(),
    val searchError: String? = null,
    val searchDone: Boolean = false,
    // ── 预览 ──
    val previewSource: LabPreviewSource? = null,
    val previewSubtitle: LabPreviewSubtitle? = null,
    /** 预览里那部片的 key（`serverKey|itemId`）：换片时必须重建地址 */
    val previewMediaKey: String? = null,
    /** 待执行的「跳到某秒」请求（时间线点击） */
    val seekRequest: LabSeekRequest? = null,
    /** 正在重新下载的记录 id（界面据此显示「下载中」） */
    val redownloading: String? = null,
    /** 重新下载进度 0..1；null = 总量未知 */
    val redownloadProgress: Float? = null,
    /** 正在从「字幕保存目录」导入（界面据此禁用按钮） */
    val importingFromSaveDir: Boolean = false,
    // ── 字幕时间线（用户反馈 9：要能看到字幕内容与时间点，并能跳转）──
    /** 时间线属于哪条记录；null = 还没选 */
    val timelineRecordId: String? = null,
    val timelineCues: List<SubtitleCue> = emptyList(),
    val timelineLoading: Boolean = false,
    val timelineError: String? = null,
    /** 条目数超过 [SubtitleTimeline.MAX_CUES]，尾部被截断（界面需提示，不能装作完整）。 */
    val timelineTruncated: Boolean = false,
    // ── 本地库治理（按媒体分组 / 磁盘占用 / 孤儿清理）──
    val stats: LibraryStats = LibraryStats(),
    val cleaning: Boolean = false,
  )

  /**
   * 已下载字幕的占用概况。
   *
   * @property mediaCount 关联的影片数（同一部片下多条字幕算一部）
   * @property orphanCount 磁盘上存在、但没有任何记录指向的字幕文件数
   */
  data class LibraryStats(
    val recordCount: Int = 0,
    val mediaCount: Int = 0,
    val totalBytes: Long = 0L,
    val orphanCount: Int = 0,
    val orphanBytes: Long = 0L,
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  private var mediaSearchJob: Job? = null

  /** 跳转请求的单调令牌（见 [LabSeekRequest] 的说明）。 */
  private var seekToken: Long = 0L

  init {
    loadMediaCandidates()
    loadLibraries()
    refreshLibrary()
  }

  fun clearMessage() = _state.update { it.copy(message = null) }

  /** 拉「继续观看」。没有已登录服务器 / 网络失败都只是空列表 + 一句提示。 */
  fun loadMediaCandidates() {
    viewModelScope.launch {
      _state.update { it.copy(loadingMedia = true, mediaError = null) }
      val servers = runCatching { repository.getServers() }.getOrDefault(emptyList())
      val server = servers.firstOrNull { it.isLoggedIn } ?: servers.firstOrNull()
      if (server == null) {
        _state.update {
          it.copy(
            loadingMedia = false,
            servers = emptyList(),
            mediaCandidates = emptyList(),
            mediaError = "尚未配置 Emby 服务器",
          )
        }
        return@launch
      }
      val items =
        runCatching { repository.getResumeItems(server, limit = 24) }
          .getOrElse {
            _state.update { s ->
              s.copy(loadingMedia = false, mediaError = "读取播放记录失败：${it.message ?: "网络异常"}")
            }
            emptyList()
          }
      _state.update {
        it.copy(
          loadingMedia = false,
          servers = servers,
          selectedServer = it.selectedServer ?: server,
          mediaCandidates = items,
          mediaError = if (items.isEmpty()) "「继续观看」暂无内容" else null,
        )
      }
    }
  }

  // ──────────────────────── 媒体库 ────────────────────────

  /**
   * 拉媒体库列表（`/Users/{uid}/Views`）。
   *
   * 用户反馈 2：只能全库搜索时，同名影片（同一部片在电影库和「合集」库里各有一份）
   * 会一起冒出来，选错了就等于把字幕挂到另一条 ItemId 上 —— 后续播放根本不认。
   * 给定媒体库之后搜索走 `parentId`，结果天然收敛到那个库。
   */
  fun loadLibraries() {
    viewModelScope.launch {
      _state.update { it.copy(loadingLibraries = true) }
      val server =
        _state.value.selectedServer
          ?: runCatching { repository.getServers() }.getOrDefault(emptyList())
            .firstOrNull { it.isLoggedIn }
          ?: runCatching { repository.getServers() }.getOrDefault(emptyList()).firstOrNull()
      if (server == null) {
        _state.update { it.copy(loadingLibraries = false, libraries = emptyList()) }
        return@launch
      }
      val libs =
        runCatching { repository.getVirtualFolders(server) }
          .getOrDefault(emptyList())
          .filter { it.Id != null }
      _state.update {
        it.copy(
          loadingLibraries = false,
          libraries = libs,
          selectedServer = it.selectedServer ?: server,
          // 之前选中的库可能已经不在了（换服务器 / 换账号），静默退回全库
          selectedLibraryId = it.selectedLibraryId?.takeIf { id -> libs.any { lib -> lib.Id == id } },
        )
      }
    }
  }

  /** 切换媒体库筛选；已经有搜索词时顺手按新库重搜一次（不然界面会停在旧结果上）。 */
  fun selectLibrary(libraryId: String?) {
    if (_state.value.selectedLibraryId == libraryId) return
    _state.update { it.copy(selectedLibraryId = libraryId) }
    if (_state.value.mediaQuery.isNotBlank()) searchMedia()
  }

  /**
   * 封面地址。给「搜索影片」列表用 —— 只有片名时用户很难确认是不是同一部，
   * 尤其番号类文件名（`ABC-123.mkv`）在媒体库里可能对应好几种译名。
   */
  fun posterUrl(item: EmbyItem): String? {
    val server = _state.value.selectedServer ?: return null
    val id = item.Id ?: return null
    return runCatching { repository.imageUrl(server, id, "Primary", maxWidth = 240) }.getOrNull()
  }

  // ──────────────────────── 媒体库搜索 ────────────────────────

  fun setMediaQuery(value: String) {
    _state.update { it.copy(mediaQuery = value) }
  }

  fun clearMediaSearch() {
    mediaSearchJob?.cancel()
    _state.update {
      it.copy(mediaQuery = "", searchResults = emptyList(), searchError = null, searchDone = false, searchingMedia = false)
    }
  }

  /**
   * 在 Emby 媒体库里按名称搜片。
   *
   * 只取可播类型（Movie / Episode / Video / MusicVideo）—— 系列、季、合集没有稳定的
   * 「一个文件」，拿来配字幕只会把记录挂到错的地方。
   */
  fun searchMedia() {
    val query = _state.value.mediaQuery.trim()
    if (query.isBlank()) return
    val server = _state.value.selectedServer
    if (server == null) {
      _state.update { it.copy(searchError = "暂无可用的 Emby 服务器") }
      return
    }
    val libraryId = _state.value.selectedLibraryId
    val libraryName = _state.value.libraries.firstOrNull { it.Id == libraryId }?.Name
    mediaSearchJob?.cancel()
    _state.update { it.copy(searchingMedia = true, searchError = null) }
    mediaSearchJob =
      viewModelScope.launch {
        val result =
          runCatching {
            repository.searchItems(
              server = server,
              term = query,
              includeItemTypes = listOf("Movie", "Episode", "Video", "MusicVideo"),
              limit = 40,
              // parentId = 只在这个媒体库里搜；null = 全库
              parentId = libraryId,
            )
          }
        _state.update { current ->
          result.fold(
            onSuccess = { items ->
              val rows = items.Items.filter { it.Id != null }
              current.copy(
                searchingMedia = false,
                searchResults = rows,
                searchDone = true,
                searchError =
                  if (rows.isEmpty()) {
                    // ⚠️ 中文是合法标识符字符：`$scope没有匹配` 会被当成一个变量名，
                    // 必须写 `${scope}` 把边界点明。
                    val scope = libraryName?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: "媒体库里"
                    "${scope}没有匹配「$query」的可播放影片"
                  } else {
                    null
                  },
              )
            },
            onFailure = { error ->
              current.copy(
                searchingMedia = false,
                searchResults = emptyList(),
                searchDone = true,
                searchError = "搜索失败：${error.message ?: "网络异常"}",
              )
            },
          )
        }
      }
  }

  /** 选中一部片当目标媒体（继续观看与搜索结果共用同一个入口）。 */
  fun selectMedia(server: EmbyServer, item: EmbyItem) {
    val itemId = item.Id ?: return
    val target =
      OnlineSubtitleTarget(
        serverKey = server.id.toString(),
        itemId = itemId,
        // 文件名优先：番号在文件名里，用它匹配最准
        mediaTitle =
          item.Path
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: item.Name.orEmpty(),
        embyTitle = item.Name,
      )
    // 换片 ⇒ 上一部的预览作废（内核还在放旧片，地址与字幕都对不上了）
    _state.update {
      it.copy(
        selectedServer = server,
        selectedItem = item,
        target = target,
        previewSource = null,
        previewSubtitle = null,
        previewMediaKey = null,
        seekRequest = null,
        // 上一部的字幕时间线也一起清掉：留着会让人以为「这部片的第 3 句是这句台词」
        timelineRecordId = null,
        timelineCues = emptyList(),
        timelineError = null,
        timelineTruncated = false,
      )
    }
    // 这部片如果已经下过字幕，顺手把它的时间线备好 —— 用户点开就能看，不用再点一次
    downloadedFor(server.id.toString(), itemId)
      .sortedByDescending { it.preferred }
      .firstOrNull()
      ?.let { loadTimeline(it) }
  }

  // ──────────────────────── 本地库 ────────────────────────

  fun refreshLibrary() {
    viewModelScope.launch {
      val snapshot =
        withContext(Dispatchers.IO) {
          // 先 all() 再 usage()：all() 会顺手剔掉「记录还在、文件没了」的条目，
          // 之后统计出来的数字才与界面上看到的列表一致。
          val records = library.all()
          records to library.usage()
        }
      val records = snapshot.first
      val usage = snapshot.second
      _state.update {
        it.copy(
          library = records,
          stats =
            LibraryStats(
              recordCount = records.size,
              mediaCount = records.map { record -> record.serverKey to record.itemId }.distinct().size,
              totalBytes = usage.totalBytes,
              orphanCount = usage.orphanCount,
              orphanBytes = usage.orphanBytes,
            ),
        )
      }
    }
  }

  /**
   * 从「设置 → 字幕 → 字幕保存目录」导入与当前影片匹配的字幕。
   *
   * ## 解决什么
   *
   * 旧版本下载的、或用户手工拷进目录的字幕不在 `_cineisle_subtitles.tsv` 里，
   * 重装后的恢复流程覆盖不到它们 —— 「已下载」列表里永远看不到，只能在播放时靠
   * 目录扫描偶尔命中。
   *
   * ## 为什么必须由用户先选影片
   *
   * 目录里的文件名只有「标题.online.格式」，不含 `serverKey` / `itemId` / `originId`，
   * 反推索引只能靠猜。归属由当前选中的影片给出，主键才是准确的，写进清单也才安全。
   *
   * 匹配沿用播放侧同一套判分（[LocalSubtitleScanner] + [SmartSubtitleMatcher]），
   * 不另写一份规则。
   */
  fun importFromSaveDir() {
    val state = _state.value
    val server = state.selectedServer
    val item = state.selectedItem
    if (server == null || item == null) {
      _state.update { it.copy(message = "请先选择目标影片") }
      return
    }
    if (state.importingFromSaveDir) return
    val title = item.Name.orEmpty().ifBlank { item.Id.orEmpty() }
    val dirPath = preferences.subtitleSaveDirPath.get()
    val legacy = preferences.subtitleSaveFolder.get()
    if (dirPath.isBlank() && legacy.isBlank()) {
      _state.update { it.copy(message = "未设置字幕保存目录") }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(importingFromSaveDir = true, message = null) }
      val outcome =
        withContext(Dispatchers.IO) {
          val scan =
            LocalSubtitleScanner.scan(
              context = getApplication<Application>(),
              saveDirPath = dirPath,
              legacyTreeUri = legacy,
              query = SmartSubtitleMatcher.prepare(title),
              isIgnored = { uri ->
                tombstoneStore.isExternalIgnored(
                  uri.path.takeIf { uri.scheme == "file" || uri.scheme.isNullOrBlank() },
                  uri.toString(),
                )
              },
            )
          if (!scan.attempted || scan.hits.isEmpty()) {
            null
          } else {
            SubtitleSaveImport.importCandidates(
              context = getApplication<Application>(),
              library = library,
              serverKey = server.id.toString(),
              itemId = item.Id.orEmpty(),
              mediaTitle = title,
              saveDirPath = dirPath,
              legacyTreeUri = legacy,
              candidates = scan.hits,
            )
          }
        }
      _state.update {
        it.copy(
          importingFromSaveDir = false,
          message =
            when {
              outcome == null -> "保存目录中未找到与「$title」匹配的字幕"
              outcome.imported == 0 -> "导入失败：${outcome.failed} 条"
              outcome.failed > 0 -> "已导入 ${outcome.imported} 条，失败 ${outcome.failed} 条"
              else -> "已导入 ${outcome.imported} 条"
            },
        )
      }
      if (outcome != null && outcome.imported > 0) refreshLibrary()
    }
  }

  /**
   * 清理「磁盘上有、索引里没有」的字幕文件。
   *
   * 这一类残留不会自己消失也不会显示在列表里：索引被清空过、下载过程异常退出、
   * 旧版本留下的目录……用户在设置里怎么看都看不到它们，只有占用的空间是真的。
   */
  fun purgeOrphans() {
    if (_state.value.cleaning) return
    viewModelScope.launch {
      _state.update { it.copy(cleaning = true) }
      val before = withContext(Dispatchers.IO) { library.usage() }
      val after = withContext(Dispatchers.IO) { library.purgeOrphans() }
      val removed = (before.orphanCount - after.orphanCount).coerceAtLeast(0)
      val freed = (before.orphanBytes - after.orphanBytes).coerceAtLeast(0L)
      _state.update { it.copy(cleaning = false) }
      refreshLibrary()
      _state.update {
        it.copy(
          message =
            if (removed > 0) {
              "已清理 $removed 个未关联的字幕文件，释放 ${formatBytes(freed)}"
            } else {
              "本地库已整洁，没有需要清理的文件"
            },
        )
      }
    }
  }

  /** 时间轴偏移（毫秒）。会夹在 ±60s 内：再多就不是「校准」而是换了一条字幕。 */
  fun adjustOffset(record: DownloadedSubtitle, deltaMs: Long) {
    setOffset(record, record.offsetMs + deltaMs)
  }

  /**
   * 改偏移。**顺便把预览里那条的偏移同步过去**（需求 8：调偏移时画面立即跟着变）。
   *
   * mpv 侧只改运行期 `sub-delay`，不动字幕文件本身。
   */
  fun setOffset(record: DownloadedSubtitle, offsetMs: Long) {
    val clamped = offsetMs.coerceIn(-60_000L, 60_000L)
    _state.update { current ->
      current.copy(
        previewSubtitle =
          current.previewSubtitle
            ?.takeIf { it.recordId == record.id }
            ?.copy(offsetMs = clamped)
            ?: current.previewSubtitle,
      )
    }
    viewModelScope.launch {
      withContext(Dispatchers.IO) { library.setOffset(record.id, clamped) }
      refreshLibrary()
    }
  }

  /** 预览面板上的 ±100 / ±500：作用于「当前正在预览的那条」。 */
  fun adjustPreviewOffset(deltaMs: Long) {
    val state = _state.value
    val current = state.previewSubtitle ?: return
    val record = state.library.firstOrNull { it.id == current.recordId } ?: return
    setOffset(record, record.offsetMs + deltaMs)
  }

  fun setPreferred(record: DownloadedSubtitle) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        library.setPreferred(record.serverKey, record.itemId, record.id)
      }
      refreshLibrary()
      _state.update { it.copy(message = "已设为该媒体的优先字幕") }
    }
  }

  /**
   * 「使用」= 设为该媒体的优先字幕 + 写进手动绑定。
   *
   * 两者的作用点不同：本地库的 `preferred` 是给人看的标记，真正让播放器下次自动挂上的是
   * [SubtitleBindings]（按片名绑 Uri，播放器恢复外挂字幕走的就是它）。
   * 只做前者会变成「界面上写着优先、开播却什么也没有」。
   */
  fun useRecord(record: DownloadedSubtitle) {
    val file = library.fileOf(record)
    if (!file.isFile) {
      _state.update { it.copy(message = "文件已不存在，请重新下载") }
      return
    }
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        library.setPreferred(record.serverKey, record.itemId, record.id)
        val title = record.mediaTitle.ifBlank { record.title }
        val uriString = Uri.fromFile(file).toString()
        if (title.isNotBlank()) {
          runCatching { SubtitleBindings.bind(preferences, title, uriString) }
        }
        // 播放页的 `media-title` 是 Emby 的**显示标题**（intent 的 `title` extra 优先于
        // 文件名），而 `record.mediaTitle` 是文件名口径，归一化后两边对不上。
        // 这里把显示标题也登记一次，播放器恢复外挂字幕时才真的能命中。
        val embyTitle = _state.value.selectedItem?.Name?.trim().orEmpty()
        if (embyTitle.isNotBlank() && embyTitle != title) {
          runCatching { SubtitleBindings.bind(preferences, embyTitle, uriString) }
        }
      }
      refreshLibrary()
      _state.update { it.copy(message = "已应用：${record.title}（播放该媒体时自动挂载）") }
    }
  }

  /** 文件信息（需求 9「查看文件信息」）：路径 + 大小 + 下载时间 + 关联媒体。 */
  fun fileInfoOf(record: DownloadedSubtitle): String {
    val file = library.fileOf(record)
    val exists = file.isFile
    val size = if (exists) file.length() else record.sizeBytes
    val saveConfigured =
      SubtitleSaveStore.isConfigured(
        preferences.subtitleSaveDirPath.get(),
        preferences.subtitleSaveFolder.get(),
      )
    return buildString {
      appendLine("字幕：${record.title}")
      appendLine("来源：${record.source.label}　格式：${record.format.uppercase()}")
      record.language?.let { appendLine("语言：$it") }
      appendLine("大小：${formatBytes(size)}${if (exists) "" else "（文件已丢失）"}")
      appendLine("下载时间：${formatTime(record.downloadedAt)}")
      appendLine("偏移：${if (record.offsetMs == 0L) "未校准" else "${record.offsetMs} ms"}")
      appendLine("Emby 条目：${record.itemId}")
      appendLine("服务器：${record.serverKey}")
      appendLine(
        when {
          record.archived -> "字幕目录：已另存一份（设置 → 字幕）"
          !saveConfigured -> "字幕目录：未设置，仅存于应用内部目录"
          else -> "字幕目录：这次未另存（目录不可用）"
        },
      )
      append("内部路径：${file.absolutePath}")
    }
  }

  /**
   * 重新下载（需求 9）。
   *
   * 直接按记录里存的原始地址重放，不再走搜索 —— 站点排序变了也能拿到同一条。
   * 偏移量与「优先」标记由 `upsert` 保留，不会被清掉。
   */
  fun redownload(record: DownloadedSubtitle) {
    if (record.originUrl.isBlank()) {
      _state.update { it.copy(message = "该记录未保存下载地址，请重新搜索后再下载") }
      return
    }
    if (_state.value.redownloading != null) return
    viewModelScope.launch {
      _state.update { it.copy(redownloading = record.id, redownloadProgress = null) }
      val outcome =
        runCatching {
          OnlineSubtitlePersist.redownloadAndSave(
            context = getApplication(),
            library = library,
            preferences = preferences,
            record = record,
            downloader = downloader,
            saveFolderUri = preferences.subtitleSaveFolder.get(),
            // 用户反馈 7：下载过程要有进度，不能只有一个转圈
            onProgress = { progress ->
              _state.update { it.copy(redownloadProgress = progress) }
            },
          )
        }
      _state.update { it.copy(redownloading = null, redownloadProgress = null) }
      outcome
        .onSuccess { saved ->
          refreshLibrary()
          // 文件换了内容 ⇒ 时间线必须重解析，否则列表还是旧台词
          loadTimeline(saved)
          _state.update {
            it.copy(
              message =
                if (saved.archived) "已重新下载并另存到字幕目录：${saved.title}"
                else "已重新下载：${saved.title}",
            )
          }
        }
        .onFailure { error ->
          _state.update { it.copy(message = "重新下载失败：${error.message ?: "网络异常"}") }
        }
    }
  }

  fun delete(record: DownloadedSubtitle) {
    viewModelScope.launch {
      // 统一编排：权威副本 + 用户目录副本 + 清单行一起清（SAF 删除失败只聚合进结果）
      val result =
        OnlineSubtitleCleanup.delete(
          context = getApplication(),
          library = library,
          preferences = preferences,
          record = record,
        )
      refreshLibrary()
      if (!result.tombstonePersisted) {
        _state.update { it.copy(message = "删除记录保存失败，字幕仍保留") }
        return@launch
      }
      // 绑定若仍指向这个文件，「开播自动挂」就会挂到不存在的路径上，顺手解掉
      if (record.mediaTitle.isNotBlank()) {
        runCatching { SubtitleBindings.unbind(preferences, record.mediaTitle) }
      }
      _state.update { current ->
        current.copy(
          previewSubtitle = current.previewSubtitle?.takeIf { it.recordId != record.id },
          timelineRecordId = current.timelineRecordId?.takeIf { it != record.id },
          timelineCues = if (current.timelineRecordId == record.id) emptyList() else current.timelineCues,
          timelineTruncated =
            if (current.timelineRecordId == record.id) false else current.timelineTruncated,
          message = "已删除：${record.title}（字幕目录副本已保留并忽略）",
        )
      }
    }
  }

  // ──────────────────────── 字幕时间线 ────────────────────────

  /**
   * 读某条已下载字幕的**时间轴条目**（用户反馈 9）。
   *
   * 只看本地文件，不发网络请求：实验室的全部校准动作都建立在「这条字幕已经在本机」
   * 之上，而且解析是纯文本处理，成本可以忽略（除非文件特别大，见 [SubtitleTimeline]）。
   */
  fun loadTimeline(record: DownloadedSubtitle) {
    val current = _state.value
    // 同一条已经解析过：直接复用（点一句台词会顺带触发这里）
    if (current.timelineRecordId == record.id && current.timelineCues.isNotEmpty()) return
    val file = library.fileOf(record)
    _state.update {
      it.copy(
        timelineRecordId = record.id,
        timelineCues = emptyList(),
        timelineError = null,
        timelineTruncated = false,
        timelineLoading = true,
      )
    }
    viewModelScope.launch {
      val parsed =
        withContext(Dispatchers.IO) {
          if (!file.isFile) {
            null
          } else {
            runCatching { SubtitleTimeline.parseFileDetailed(file) }.getOrNull()
          }
        }
      val cues = parsed?.cues
      _state.update { current ->
        // 期间用户可能已经切到别的字幕，旧结果不能覆盖新的
        if (current.timelineRecordId != record.id) {
          current
        } else {
          current.copy(
            timelineLoading = false,
            timelineCues = cues.orEmpty(),
            timelineTruncated = parsed?.truncated == true,
            timelineError =
              when {
                !file.isFile -> "字幕文件已丢失，请重新下载"
                cues == null -> "时间线解析失败"
                cues.isEmpty() -> "该文件无可识别时间轴（格式可能不受支持）"
                else -> null
              },
          )
        }
      }
    }
  }

  /**
   * 点时间线里的一句 → 让预览跳到那一刻。
   *
   * 预览还没开、或者预览里挂的是别的字幕时，先把它切过来：否则「跳转」会落在一部
   * 不相干的片子上，用户看到的画面和这句话对不上，比不跳更让人困惑。
   */
  fun seekToCue(cue: SubtitleCue) {
    val state = _state.value
    val record = state.library.firstOrNull { it.id == state.timelineRecordId }
    if (record == null) {
      _state.update { it.copy(message = "暂无可用的字幕记录") }
      return
    }
    if (state.previewSource == null || state.previewSubtitle?.recordId != record.id) {
      previewRecord(record)
    }
    seekToken += 1
    _state.update { it.copy(seekRequest = LabSeekRequest(seekToken, cue.startMs / 1000.0)) }
  }

  // ──────────────────────── 预览 ────────────────────────

  /**
   * 开始预览：把「播放地址 + 起播位置」备好交给预览面板。
   *
   * 地址走 `videoStreamUrl`（`static=true`，`api_key` 已拼在查询串里），因此 mpv
   * 不需要任何额外请求头。起播位置取该条目的续播进度 —— 实验室的典型用法是
   * 「上次看到这儿、这几句时间轴不对」，从续播点进去最省事。
   */
  fun startPreview() {
    val state = _state.value
    val server = state.selectedServer
    val item = state.selectedItem
    if (server == null || item == null) {
      _state.update { it.copy(message = "请先选择目标影片") }
      return
    }
    val source = sourceFor(server, item)
    if (source == null) {
      _state.update { s -> s.copy(message = "获取播放地址失败，无法预览") }
      return
    }
    // 没手动挑字幕时，自动带上这部片「优先」或最新下载的那条 —— 预览一开就有字幕可看
    val autoSubtitle =
      state.previewSubtitle?.takeIf { state.previewMediaKey == "${server.id}|${item.Id}" }
        ?: downloadedFor(server.id.toString(), item.Id.orEmpty())
          .sortedByDescending { it.preferred }
          .firstOrNull()
          ?.let { it to library.fileOf(it) }
          ?.takeIf { it.second.isFile }
          ?.let { (record, file) ->
            LabPreviewSubtitle(
              recordId = record.id,
              path = file.absolutePath,
              title = record.title,
              offsetMs = record.offsetMs,
            )
          }
    _state.update {
      it.copy(
        previewSource = source,
        previewSubtitle = autoSubtitle,
        previewMediaKey = "${server.id}|${item.Id}",
      )
    }
    // 预览里挂着哪条字幕，时间线就跟着它 —— 用户想「跳到某一句」时不用再点一次
    autoSubtitle?.let { sub ->
      _state.value.library.firstOrNull { it.id == sub.recordId }?.let { loadTimeline(it) }
    }
  }

  /** 关闭预览（停播，内核留到离开页面时才销毁）。 */
  fun stopPreview() {
    _state.update {
      it.copy(previewSource = null, previewSubtitle = null, previewMediaKey = null, seekRequest = null)
    }
  }

  /**
   * 在预览里切到某条已下载字幕（需求 7.4：选哪条画面立刻变哪条）。
   *
   * 允许预览「别的片子」的字幕记录：本地库是全局的，用户翻到一条以前下的字幕想看看效果，
   * 这时把预览切到它所属的那部片（地址按记录的 `serverKey + itemId` 重新拼），
   * 而不是硬挂到当前选中的片上 —— 那等于拿 A 片的字幕配 B 片的画面。
   */
  fun previewRecord(record: DownloadedSubtitle) {
    val file = library.fileOf(record)
    if (!file.isFile) {
      _state.update { it.copy(message = "文件已不存在，请重新下载") }
      return
    }
    val state = _state.value
    val key = "${record.serverKey}|${record.itemId}"
    val source =
      if (state.previewSource != null && state.previewMediaKey == key) {
        state.previewSource
      } else {
        val server = state.servers.firstOrNull { it.id.toString() == record.serverKey }
        val item = state.selectedItem?.takeIf { it.Id == record.itemId && server?.id == state.selectedServer?.id }
        if (server == null) {
          _state.update { it.copy(message = "该字幕所属服务器已不存在，无法预览") }
          return
        }
        sourceFor(server, item, itemIdOverride = record.itemId, fallbackTitle = record.mediaTitle)
      }
    if (source == null) {
      _state.update { it.copy(message = "获取播放地址失败，无法预览") }
      return
    }
    _state.update {
      it.copy(
        previewSource = source,
        previewMediaKey = key,
        previewSubtitle =
          LabPreviewSubtitle(
            recordId = record.id,
            path = file.absolutePath,
            title = record.title,
            offsetMs = record.offsetMs,
          ),
      )
    }
    // 预览挂上哪条，时间线就跟到哪条（点台词跳转的前提）
    loadTimeline(record)
  }

  /**
   * 拼一条预览地址。`item` 为 null（记录来自别的片子、手上只有 Id）时用 [itemIdOverride]，
   * 起播位置取 0。
   */
  private fun sourceFor(
    server: EmbyServer,
    item: EmbyItem?,
    itemIdOverride: String? = null,
    fallbackTitle: String? = null,
  ): LabPreviewSource? {
    val itemId = item?.Id ?: itemIdOverride ?: return null
    val url =
      runCatching {
        repository.videoStreamUrl(
          server = server,
          itemId = itemId,
          static = true,
          // 版本在这里不重要：预览只看字幕同步，服务端默认版本即可
          mediaSourceId = null,
        )
      }.getOrNull() ?: return null
    val startSec = (item?.UserData?.PlaybackPositionTicks ?: 0L) / 10_000_000.0
    val title = item?.Name.orEmpty().ifBlank { fallbackTitle.orEmpty() }
    return LabPreviewSource(url = url, startSec = startSec, title = title)
  }

  private fun downloadedFor(
    serverKey: String,
    itemId: String,
  ): List<DownloadedSubtitle> =
    _state.value.library.filter { it.serverKey == serverKey && it.itemId == itemId }

  private fun formatBytes(bytes: Long): String =
    when {
      bytes <= 0L -> "未知"
      bytes < 1024L -> "$bytes B"
      bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
      else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    }

  private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "未知"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
  }
}
