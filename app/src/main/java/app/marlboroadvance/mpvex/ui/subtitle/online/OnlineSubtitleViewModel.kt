package app.marlboroadvance.mpvex.ui.subtitle.online

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTimeline
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitle
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleCleanup
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleDownloader
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibrary
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitlePersist
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleSearchManager
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleSource
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleTarget
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleMatchLevel
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSearchOutcome
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings
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
 * 网络字幕（迅雷 / SubtitleCat）的搜索与下载状态。
 *
 * ## 为什么三个入口共用一个 ViewModel
 *
 * 需求 11.1 明确要求「三个入口共享同一套业务逻辑与同一份数据」。搜索、下载、
 * 缓存、去重、落盘、绑定都在这里做；页面只负责画。
 * 页面之间的差别只有 [OnlineSubtitleTarget]（媒体上下文）和布局。
 */
class OnlineSubtitleViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences by inject<SubtitlesPreferences>(SubtitlesPreferences::class.java)

  private val searchManager = OnlineSubtitleSearchManager()
  private val downloader = OnlineSubtitleDownloader()
  private val library = OnlineSubtitleLibrary(application, preferences)

  /** 一条候选的下载状态。progress = null 表示服务器没给长度（不确定进度）。 */
  data class DownloadState(val progress: Float?, val error: String? = null)

  /**
   * 搜索结果的时间线预览。
   *
   * 与「下载」是两件不同的事：这里只把字幕内容读进内存解析，**不落盘、不进本地库**。
   * 用户先看到「这句台词在 03:12 出现」，再决定要不要下这条 —— 不必为了试一条
   * 错版本而反复下载 / 删除。
   */
  data class TimelinePreview(
    val originId: String,
    val title: String,
    val loading: Boolean = true,
    val cues: List<SubtitleCue> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
    /** true = 来自「已下载」列表（读本地文件）；false = 来自搜索结果（可能需要联网取内容）。 */
    val fromLibrary: Boolean = false,
  )

  data class UiState(
    val keyword: String = "",
    /** 关键词从哪来（`影片编号` / `清理后的标题` / `手动输入`…），面板上要回显。 */
    val keywordOrigin: String = "",
    val keywordWasFallback: Boolean = false,
    val enabledSources: Set<OnlineSubtitleSource> = OnlineSubtitleSource.DEFAULT_ENABLED,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val items: List<OnlineSubtitle> = emptyList(),
    val failure: String? = null,
    val notice: String? = null,
    val downloads: Map<String, DownloadState> = emptyMap(),
    /** 该媒体已下载的字幕（本次会话内随下载/删除实时更新）。 */
    val downloaded: List<DownloadedSubtitle> = emptyList(),
    /** 下载完成的那条，对应到候选 id，用于列表里打「已下载」。 */
    val downloadedOriginIds: Set<String> = emptySet(),
    /** 搜索结果的时间线预览（null = 没打开）。 */
    val timelinePreview: TimelinePreview? = null,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var target: OnlineSubtitleTarget? = null
  private var searchJob: Job? = null
  private var timelineJob: Job? = null
  private val downloadJobs = mutableMapOf<String, Job>()

  /**
   * 绑定目标媒体：预填关键词、读出已下载字幕。
   *
   * 目标变化（换片 / 换集）时清空上一次的搜索结果 —— 留着旧候选只会让人下错字幕。
   */
  fun bindTarget(newTarget: OnlineSubtitleTarget) {
    val changed = target?.itemId != newTarget.itemId || target?.serverKey != newTarget.serverKey
    target = newTarget
    val hint = newTarget.suggestKeyword()
    _state.update { current ->
      current.copy(
        keyword = if (changed || current.keyword.isBlank()) hint.keyword else current.keyword,
        keywordOrigin = hint.origin,
        keywordWasFallback = hint.fallback,
        items = if (changed) emptyList() else current.items,
        searched = if (changed) false else current.searched,
        failure = null,
        notice = null,
        downloads = emptyMap(),
        timelinePreview = null,
      )
    }
    refreshLibrary()
  }

  fun setKeyword(value: String) {
    _state.update { it.copy(keyword = value, keywordOrigin = "手动输入", keywordWasFallback = false) }
  }

  fun toggleSource(source: OnlineSubtitleSource) {
    _state.update { current ->
      val next =
        if (source in current.enabledSources) current.enabledSources - source
        else current.enabledSources + source
      current.copy(enabledSources = next, failure = null)
    }
  }

  fun clearNotice() {
    _state.update { it.copy(notice = null, failure = null) }
  }

  /** 取消正在进行的搜索（用户改关键词 / 关面板）。 */
  fun cancelSearch() {
    searchJob?.cancel()
    searchJob = null
    _state.update { it.copy(searching = false) }
  }

  /**
   * 搜索。
   *
   * 关键词为空时直接提示，不发请求 —— 站点对空查询的响应通常是一张「热门列表」，
   * 把那种结果当搜索结果展示只会让人误以为匹配到了。
   */
  fun search(overrideKeyword: String? = null) {
    val current = target ?: return
    val keyword = (overrideKeyword ?: _state.value.keyword).trim()
    if (keyword.isBlank()) {
      _state.update { it.copy(failure = "请输入番号或片名再搜索") }
      return
    }
    if (_state.value.enabledSources.isEmpty()) {
      _state.update { it.copy(failure = "至少启用一个字幕来源") }
      return
    }
    searchJob?.cancel()
    _state.update {
      it.copy(
        searching = true,
        searched = true,
        keyword = keyword,
        failure = null,
        notice = null,
        downloads = emptyMap(),
      )
    }
    searchJob =
      viewModelScope.launch {
        val outcome =
          runCatching {
            searchManager.search(
              keyword = keyword,
              enabled = _state.value.enabledSources,
              mediaTitle = current.matchName(),
            )
          }.getOrElse { SubtitleSearchOutcome(keyword = keyword) }

        _state.update {
          it.copy(
            searching = false,
            items = outcome.items,
            failure = when {
              outcome.items.isNotEmpty() -> null
              outcome.allFailed -> outcome.failureSummary ?: "所有字幕来源都不可用"
              else -> null
            },
            notice = outcome.failureSummary?.takeIf { outcome.items.isNotEmpty() && outcome.allFailed.not() },
          )
        }
      }
  }

  /**
   * 下载一条候选并落进本地库。
   *
   * @return 落盘后的 Uri（可直接交给播放器挂载）；失败返回 null，原因在 [UiState.downloads]。
   */
  suspend fun download(item: OnlineSubtitle): Uri? {
    val current = target ?: return null
    // 已经下过：直接复用，不重复下载（需求 11.1 最后一段）
    library.find(current.serverKey, current.itemId, item.id)?.let { existing ->
      val file = library.fileOf(existing)
      if (file.isFile) return Uri.fromFile(file)
    }

    val job =
      viewModelScope.launch {
        _state.update { it.copy(downloads = it.downloads + (item.id to DownloadState(0f))) }
        val dir = library.mediaDir(current.serverKey, current.itemId)
        // 文件主干带来源与地址短哈希：同一站点下同名但不同内容的候选（不同版本 / 不同发布组）
        // 不能互相覆盖 —— 覆盖后两条记录会指向同一份内容，用户根本看不出差别。
        val baseName = OnlineSubtitleLibrary.downloadBaseName(item)
        val result =
          runCatching {
            downloader.download(item, dir, baseName) { progress ->
              _state.update {
                it.copy(downloads = it.downloads + (item.id to DownloadState(progress)))
              }
            }
          }
        result
          .onSuccess { downloaded ->
            // 建档 / 另存字幕目录 / 写索引都在 [OnlineSubtitlePersist] 里（与实验室的
            // 「重新下载」共用同一份实现，需求 11.1）
            val record =
              OnlineSubtitlePersist.save(
                context = getApplication(),
                library = library,
                preferences = preferences,
                serverKey = current.serverKey,
                itemId = current.itemId,
                mediaTitle = current.mediaTitle.ifBlank { current.embyTitle.orEmpty() },
                item = item,
                result = downloaded,
                saveFolderUri = preferences.subtitleSaveFolder.get(),
              )
            // 与既有「手动绑定」打通：记进 SubtitleBindings 后，下次开播会自动挂回来，
            // 不必再触发一次在线搜索。
            //
            // ⚠️ 这里必须**两个口径各登记一次**：
            //  · `mediaTitle`（原始文件名，含番号）—— 详情页 / 实验室的口径，也是匹配器的口径；
            //  · `embyTitle`（Emby 显示标题）—— 播放页的 `media-title` 用的是它
            //    （intent 的 `title` extra 优先于 filename），归一化后和文件名对不上。
            // 只登记前者时，「详情页下完 → 点播放」会走到 `restoreBoundSubtitle` 却查不到绑定，
            // 于是既不自动挂、也不自动选中，用户只能到「网络字幕」里手点一下。
            // `SubtitleBindings` 的键是归一化字干，两个口径互不干扰，重复登记是安全的。
            val bindTitle = current.mediaTitle.ifBlank { current.embyTitle.orEmpty() }
            if (bindTitle.isNotBlank()) {
              SubtitleBindings.bind(preferences, bindTitle, Uri.fromFile(downloaded.file).toString())
            }
            val playerTitle = current.embyTitle?.trim().orEmpty()
            if (playerTitle.isNotBlank() && playerTitle != bindTitle) {
              SubtitleBindings.bind(preferences, playerTitle, Uri.fromFile(downloaded.file).toString())
            }
            refreshLibrary()
            _state.update {
              it.copy(
                downloads = it.downloads - item.id,
                notice =
                  if (record.archived) {
                    "已下载并另存到字幕目录：${item.title}"
                  } else {
                    "已下载：${item.title}"
                  },
              )
            }
          }
          .onFailure { error ->
            _state.update {
              it.copy(
                downloads =
                  it.downloads + (item.id to DownloadState(null, error.message ?: "下载失败")),
                notice = null,
              )
            }
          }
      }
    // 等它跑完再返回 Uri：上层（播放器挂载）需要知道结果
    downloadJobs[item.id] = job
    // 用户点了取消 ⇒ 这里会是 CancellationException，不能让它冒到调用方
    runCatching { job.join() }
    downloadJobs.remove(item.id)
    val record =
      library.find(current.serverKey, current.itemId, item.id) ?: return null
    val file = library.fileOf(record)
    return if (file.isFile) Uri.fromFile(file) else null
  }

  fun cancelDownload(itemId: String) {
    downloadJobs.remove(itemId)?.cancel()
    _state.update { it.copy(downloads = it.downloads - itemId) }
  }

  /** 重试失败的下载。 */
  fun retry(item: OnlineSubtitle) {
    _state.update { it.copy(downloads = it.downloads - item.id) }
    viewModelScope.launch { download(item) }
  }

  /**
   * 打开某条搜索结果的时间线预览。
   *
   * 已下载过的条目直接读本地文件（零请求）；没下载的把内容读进内存再解析，
   * **不写盘**。解析规则复用字幕实验室那一份 [SubtitleTimeline]，两边看到的时间轴
   * 口径完全一致。
   */
  fun previewTimeline(item: OnlineSubtitle) {
    val current = target ?: return
    startTimelinePreview(item.id, item.title, fromLibrary = false) {
      val record = library.find(current.serverKey, current.itemId, item.id)
      val localFile = record?.let { library.fileOf(it) }
      if (localFile != null && localFile.isFile) {
        SubtitleTimeline.parseFileDetailed(localFile)
      } else {
        val fetched = downloader.fetch(item)
        SubtitleTimeline.parseTextDetailed(
          SubtitleTimeline.decode(fetched.bytes),
          fetched.format,
        )
      }
    }
  }

  /**
   * 打开「已下载」列表里某条字幕的时间线预览。
   *
   * 只读本地文件，零请求；文件被外部删掉时给一句可操作的说明，而不是空列表。
   */
  fun previewTimeline(record: DownloadedSubtitle) {
    startTimelinePreview(record.originId, record.title, fromLibrary = true) {
      val file = library.fileOf(record)
      if (!file.isFile) throw java.io.IOException("字幕文件已不存在，请重新下载该字幕")
      SubtitleTimeline.parseFileDetailed(file)
    }
  }

  /**
   * 预览流程的公共部分：先置 loading、取消上一条请求，再在 IO 里取内容解析。
   *
   * 状态更新前核对 `originId`：用户可能已经关掉弹窗或换了一条，迟到的结果不许覆盖。
   */
  private fun startTimelinePreview(
    originId: String,
    title: String,
    fromLibrary: Boolean,
    load: suspend () -> SubtitleTimeline.ParsedTimeline,
  ) {
    timelineJob?.cancel()
    _state.update {
      it.copy(
        timelinePreview = TimelinePreview(
          originId = originId,
          title = title,
          fromLibrary = fromLibrary,
        ),
      )
    }
    timelineJob =
      viewModelScope.launch {
        val result =
          withContext(Dispatchers.IO) {
            try {
              Result.success(load())
            } catch (cancel: kotlinx.coroutines.CancellationException) {
              throw cancel
            } catch (error: Throwable) {
              Result.failure(error)
            }
          }
        _state.update { state ->
          if (state.timelinePreview?.originId != originId) {
            state
          } else {
            val parsed = result.getOrNull()
            state.copy(
              timelinePreview =
                TimelinePreview(
                  originId = originId,
                  title = title,
                  loading = false,
                  cues = parsed?.cues.orEmpty(),
                  truncated = parsed?.truncated == true,
                  error = result.exceptionOrNull()?.let(::humanizeTimelineError),
                  fromLibrary = fromLibrary,
                ),
            )
          }
        }
      }
  }

  /** 关闭时间线预览（同时取消还在跑的请求）。 */
  fun closeTimelinePreview() {
    timelineJob?.cancel()
    timelineJob = null
    _state.update { it.copy(timelinePreview = null) }
  }

  /**
   * 网络层的英文异常不直接甩给用户：
   * 自己抛的都是中文说明（「返回的是网页而不是字幕文件」），原样展示；
   * 其余（`UnknownHostException` / `SocketTimeoutException`…）收敛成一句人话。
   */
  private fun humanizeTimelineError(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return when {
      message.isEmpty() -> "无法获取字幕内容"
      message.any { it.code in 0x4E00..0x9FFF } -> message
      else -> "无法获取字幕内容，请检查网络后重试"
    }
  }

  /** 重新读本地库（下载 / 删除 / 参数变更后调）。 */
  fun refreshLibrary() {
    val current = target ?: return
    viewModelScope.launch {
      val records =
        withContext(Dispatchers.IO) { library.forMedia(current.serverKey, current.itemId) }
      _state.update {
        it.copy(
          downloaded = records.sortedByDescending { r -> r.downloadedAt },
          downloadedOriginIds = records.map { r -> r.originId }.toSet(),
        )
      }
    }
  }

  fun setOffset(record: DownloadedSubtitle, offsetMs: Long) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { library.setOffset(record.id, offsetMs) }
      refreshLibrary()
    }
  }

  fun setPreferred(record: DownloadedSubtitle) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        library.setPreferred(record.serverKey, record.itemId, record.id)
      }
      refreshLibrary()
    }
  }

  fun delete(record: DownloadedSubtitle) {
    viewModelScope.launch {
      // 统一编排：权威副本 + 用户目录副本 + 清单行一起清，失败聚合在结果里
      val result =
        OnlineSubtitleCleanup.delete(
          context = getApplication(),
          library = library,
          preferences = preferences,
          record = record,
        )
      // 绑定还指着已删除的文件时会「开播挂不上」，顺手解掉
      val title = record.mediaTitle
      if (title.isNotBlank()) {
        runCatching { SubtitleBindings.unbind(preferences, title) }
      }
      refreshLibrary()
      if (result.mirrorFailed) {
        _state.update { it.copy(notice = "已删除字幕，但字幕目录里的副本清理失败，请手动删除") }
      }
    }
  }

  fun uriOf(record: DownloadedSubtitle): Uri? {
    val file = library.fileOf(record)
    return if (file.isFile) Uri.fromFile(file) else null
  }

  /** 面板标题上那句「已关联 N 条」用。 */
  fun librarySnapshot(): List<DownloadedSubtitle> = _state.value.downloaded

  /** 全部本地库（字幕实验室的「已下载字幕」页用，不限当前媒体）。 */
  suspend fun allDownloaded(): List<DownloadedSubtitle> = withContext(Dispatchers.IO) { library.all() }

  suspend fun deleteGlobal(record: DownloadedSubtitle) {
    OnlineSubtitleCleanup.delete(
      context = getApplication(),
      library = library,
      preferences = preferences,
      record = record,
    )
    val title = record.mediaTitle
    if (title.isNotBlank()) runCatching { SubtitleBindings.unbind(preferences, title) }
  }

  override fun onCleared() {
    searchJob?.cancel()
    timelineJob?.cancel()
    downloadJobs.values.forEach { it.cancel() }
    super.onCleared()
  }

  companion object {
    /** 与其它页面的 ViewModel 工厂保持同一种写法。 */
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { OnlineSubtitleViewModel(application) }
    }

    /** 匹配等级在列表里的排序权重（高匹配优先）。 */
    fun levelWeight(level: SubtitleMatchLevel): Int =
      when (level) {
        SubtitleMatchLevel.HIGH -> 4
        SubtitleMatchLevel.GOOD -> 3
        SubtitleMatchLevel.FAIR -> 2
        SubtitleMatchLevel.LOW -> 1
        SubtitleMatchLevel.UNKNOWN -> 0
      }
  }
}
