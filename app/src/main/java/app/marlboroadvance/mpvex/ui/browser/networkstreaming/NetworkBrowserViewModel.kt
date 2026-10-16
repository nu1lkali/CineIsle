package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.repository.NetworkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * 列目录的**原始结果**（未按用户排序重排，但已按「文件夹优先 + 名称」归一，
   * 作为稳定基准序 —— 上面的排序是稳定排序，同键时保持这个顺序）。
   */
  private val _rawFiles = MutableStateFlow<List<NetworkFile>>(emptyList())

  /** 排序维度 / 方向（镜像持久化偏好，见 BrowserPreferences.networkSortType） */
  private val _sortType = MutableStateFlow(browserPreferences.networkSortType.get())
  private val _sortOrder = MutableStateFlow(browserPreferences.networkSortOrder.get())

  /** 随机排序的种子：每次（重）选「随机」都换一个新种子 ⇒ 列表换一批顺序 */
  private val _randomSeed = MutableStateFlow(System.currentTimeMillis())

  val sortType: StateFlow<NetworkSortType> = _sortType.asStateFlow()
  val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

  /**
   * 当前目录内容（已排序）。
   *
   * 目录是一次性拉全量的，排序是纯本地重排、**不发请求**；所以做成
   * `combine(原始列表, 排序项, 方向, 随机种子)` 的组合流：切排序立刻生效，
   * 也不用为了换个排序去重新列目录。
   * 文件夹永远排在媒体前面（它们是导航），各自再按排序键排。
   */
  val files: StateFlow<List<NetworkFile>> =
    combine(_rawFiles, _sortType, _sortOrder, _randomSeed) { list, type, order, seed ->
      when (type) {
        // 随机：文件夹与媒体各自洗牌（文件夹仍是导航，恒在前）。
        // 种子放流里而不是排序时现取 —— 否则每次重组都换一批顺序，画面直接疯掉。
        NetworkSortType.Random -> {
          val random = Random(seed)
          val (folders, media) = list.partition { it.isDirectory }
          folders.shuffled(random) + media.shuffled(random)
        }
        else -> list.sortedWith(networkFileComparator(type, order))
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** 当前目录是否已收藏（顶栏星形按钮的状态）。loadFiles 时刷新 */
  private val _isCurrentFavorite = MutableStateFlow(false)
  val isCurrentFavorite: StateFlow<Boolean> = _isCurrentFavorite.asStateFlow()

  /**
   * 文件夹封面缓存：文件夹 path → 「第一张图片」的 path（**本页**可见的）。
   *
   * - value = null：这个文件夹列过了，没有图片（或本次列失败）—— 别再列第二次；
   * - 只进缓存不淘汰：一个浏览页面就对应这几个文件夹，滑回去直接命中。
   *
   * ⚠️ 这只是**本页**的可见缓存；跨页面 / 跨重启的持久化在 Repository 的两级
   *   缓存里（L1 进程内 LRU + L2 Room 的 `folder_cover_cache` 表）——
   *   见 [NetworkRepository.getFolderCover]。所以「每开一次重复资源封面重拉」
   *   的问题不靠这个 Map 解决，靠的是 Repository 那两层。
   *
   * ⚠️ 封面是**懒加载**的：由 UI 在卡片可见时调 [ensureFolderCover] 触发
   *   （LazyGrid 只组合可见项 ⇒ 只有看得见的文件夹才花这次列目录请求），
   *   不是进页面就把所有子目录列一遍。
   */
  private val _folderCovers = MutableStateFlow<Map<String, String?>>(emptyMap())
  val folderCovers: StateFlow<Map<String, String?>> = _folderCovers.asStateFlow()

  /** 在途封面请求去重：同一文件夹被多张卡 / 快速滑动重复触发也只列一次 */
  private val coverInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** 封面列目录限流：一屏可见的文件夹可能十几个，全并发会挤爆 SMB 会话 */
  private val coverGate = Semaphore(3)

  /** 连接对象缓存：封面列目录与 loadFiles 同一个连接，不必每次查库 */
  private var cachedConnection: NetworkConnection? = null

  /**
   * 确保这个文件夹的封面已缓存（没有就列一次目录）。
   *
   * 「第一张图片」按**名称序**取（`minByOrNull { it.name.lowercase() }`）——
   * 与服务端返回顺序无关，同一文件夹每次进来封面稳定。
   * 只认直接子级里的图片，**不递归**子文件夹（递归 = 一张卡片要 N 次列目录，
   * 那就不是「尽可能减少请求」了）。
   *
   * 三级查找：本页 Map → Repository L1（进程内 LRU）→ Repository L2（Room）。
   * 只有三级都没命中才真的列目录；列**成功**才回写持久缓存。
   *
   * 安全地被重复调：已缓存 / 在途 / 连接没了都会静默返回。
   */
  fun ensureFolderCover(folderPath: String) {
    if (_folderCovers.value.containsKey(folderPath)) return
    if (!coverInFlight.add(folderPath)) return
    viewModelScope.launch {
      try {
        // 1) 持久缓存命中（含负缓存：列过了、没图片）→ 直接渲染，零请求
        val cached = repository.getFolderCover(connectionId, folderPath)
        if (cached != null) {
          _folderCovers.update { it + (folderPath to cached.coverPath) }
          return@launch
        }
        // 2) 未命中：列一次目录（Semaphore(3) 限流，一屏可见的文件夹可能十几个）
        val connection = cachedConnection
          ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
          ?: return@launch
        val cover = coverGate.withPermit {
          repository.listFiles(connection, folderPath)
            .getOrNull()
            ?.filter { it.isPhoto() }
            ?.minByOrNull { it.name.lowercase() }
            ?.path
        }
        // 3) 列成功才写持久缓存：失败是暂时的（网络抖动 / 会话过期），
        //    不能把「这次没列成」记成「没有图片」缓存 30 天
        repository.putFolderCover(connectionId, folderPath, cover)
        _folderCovers.update { it + (folderPath to cover) }
      } catch (e: Exception) {
        Log.w(TAG, "封面列目录失败: $folderPath | ${e.message}")
        // 本页记 null（负缓存）：滑动里不反复炸；重试交给下拉刷新 / 重启
        _folderCovers.update { it + (folderPath to null) }
      } finally {
        coverInFlight.remove(folderPath)
      }
    }
  }

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _rawFiles.value = fileList.sortedWith(
              compareBy<NetworkFile> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
            )
            // 封面可能变了（子文件夹里加了 / 删了照片）：失效这些子文件夹的缓存，
            // 已看过的按新内容重取（值变了卡面自然刷新），没看过的仍然懒加载。
            // **只失效不预热** —— 预热等于把整个目录重新列一遍，那就不是懒加载了。
            val subFolders = fileList.filter { it.isDirectory }.map { it.path }
            val seenFolders = _folderCovers.value.keys.toList()
            _folderCovers.update { it - subFolders.toSet() }
            viewModelScope.launch {
              repository.invalidateFolderCovers(connectionId, subFolders)
              seenFolders.forEach { ensureFolderCover(it) }
            }
          }
          .onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
        // 收藏状态与列表同源刷新：取消收藏后返回 / 重新进入都能看到正确星形
        _isCurrentFavorite.value = repository.isFavorite(connectionId, currentPath)
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
      }
    }
  }

  /**
   * 收藏 / 取消收藏**当前目录**（顶栏星形）。已收藏时再调 = 取消。
   *
   * 收藏名取目录名（根目录退化为连接名）—— 与 NetworkFavorite 的注释一致：
   * 这是快照，连接改名 / 目录挪走都不影响已存快捷入口的显示。
   *
   * @param onResult 切完回调（true = 加上去，false = 取下来），给调用方弹 toast 用。
   *   在主线程回调（viewModelScope 默认调度器）。
   */
  fun toggleFavoriteCurrent(onResult: (added: Boolean) -> Unit = {}) {
    viewModelScope.launch {
      val connection = repository.getConnectionById(connectionId) ?: return@launch
      val dirName = currentPath
        .trimEnd('/')
        .substringAfterLast('/', "")
        .ifBlank { connection.name }
      val added = repository.toggleFavorite(connection, currentPath, dirName)
      _isCurrentFavorite.value = added
      onResult(added)
    }
  }

  /**
   * 切排序维度。同时把方向切到该维度的**自然方向**（名称/类型=升序、时间/大小=降序）——
   * 顶栏排序控件右边的箭头会跟着变，不用用户每换一个维度再手动点一次方向。
   *
   * 重复点「随机」= 换一批顺序（新种子），其他维度重复点无操作。
   */
  fun setSortType(type: NetworkSortType) {
    if (type == _sortType.value) {
      if (type == NetworkSortType.Random) {
        _randomSeed.value = System.currentTimeMillis()
      }
      return
    }
    val order = if (type.naturalAscending) SortOrder.Ascending else SortOrder.Descending
    _sortType.value = type
    _sortOrder.value = order
    if (type == NetworkSortType.Random) {
      _randomSeed.value = System.currentTimeMillis()
    }
    browserPreferences.networkSortType.set(type)
    browserPreferences.networkSortOrder.set(order)
  }

  /** 只翻方向（排序控件右边的上下箭头） */
  fun setSortOrder(order: SortOrder) {
    if (order == _sortOrder.value) return
    _sortOrder.value = order
    browserPreferences.networkSortOrder.set(order)
  }



  /**
   * 队列播放一批视频（普通连播 / 随机播放共用）。
   *
   * @param videos 完整队列，顺序即播放顺序
   * @param startIndex 从哪一条开始
   *
   * 队列经 intent extras 传给播放器：`playlist`（Uri 表）+ `playlist_titles` +
   * `playlist_index`。⚠️ 另外传 `network_file_paths`（与 playlist 同序的原始网络
   * 路径表）—— 播放器靠它按当前下标算每片的进度键 / 找字幕，否则连播换片后
   * 进度全串到第一片头上（PlayerActivity.networkFilePaths 的注释有详述）。
   */
  fun playVideos(videos: List<NetworkFile>, startIndex: Int) {
    if (videos.isEmpty() || startIndex !in videos.indices) return
    viewModelScope.launch {
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS
        val stamp = System.currentTimeMillis()

        val uris = ArrayList<android.net.Uri>(videos.size)
        val titles = ArrayList<String>(videos.size)
        val networkPaths = ArrayList<String>(videos.size)
        videos.forEachIndexed { index, file ->
          val uri = if (useProxy) {
            val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
            val streamId = "${connectionId}_${stamp}_$index"
            val proxyUrl = proxy.registerStream(
              streamId = streamId,
              connection = connection,
              filePath = file.path,
              fileSize = file.size,
              mimeType = file.mimeType ?: "video/mp4",
            )
            android.net.Uri.parse(proxyUrl)
          } else {
            NetworkStreamingProvider.setConnection(connectionId, connection)
            NetworkStreamingProvider.getUri(application, connectionId, file.path)
          }
          uris.add(uri)
          titles.add(file.name)
          networkPaths.add(file.path)
        }

        val intent = Intent(Intent.ACTION_VIEW, uris[startIndex])
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putParcelableArrayListExtra("playlist", uris)
        intent.putStringArrayListExtra("playlist_titles", titles)
        intent.putStringArrayListExtra("network_file_paths", networkPaths)
        intent.putExtra("playlist_index", startIndex)
        // 单值 extra 仍写第一条：播放器非队列路径（字幕/标识）会回退到它
        intent.putExtra("title", videos[startIndex].name)
        intent.putExtra("filename", videos[startIndex].name)
        intent.putExtra("network_file_path", videos[startIndex].path)
        intent.putExtra("network_connection_id", connectionId)
        intent.setDataAndType(uris[startIndex], videos[startIndex].mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Error playing videos", e)
        _error.value = e.message ?: "Unknown error"
      }
    }
  }

  /**
   * 随机播放当前目录的视频：洗牌后按「随机播放数量」设置截断（1~500 条护栏）。
   *
   * 与 Emby 媒体库工具行的随机播放同一口径（同一个设置项）。没有可播视频时
   * 由调用方给 toast 提示，这里静默返回。
   */
  fun playRandomVideos() {
    viewModelScope.launch {
      val pool = _rawFiles.value.filter { it.isVideoFile() }
      if (pool.isEmpty()) return@launch
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val picked = pool.shuffled().take(limit)
      playVideos(picked, 0)
    }
  }

  /** 单文件播放（保留旧入口语义）：队列就是它自己 */
  fun playVideo(file: NetworkFile) {
    playVideos(listOf(file), 0)
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}

/**
 * 「随机播放数量」设置、没取到就明确护栏 —— 与 Emby 媒体库同一设置项同一口径
 * （EmbyLibraryScreen 里那份是同名私有扩展，值必须一致：改这里记得改那边）。
 */
private fun Preference<Int>.randomPlayLimit(): Int = get().coerceIn(1, 500)

/**
 * 网络目录的排序比较器：**文件夹恒在媒体前面**（它们是导航，与本地文件夹浏览同一惯例），
 * 各自再按 [type] 的键排。[order] 只作用于排序键，**不翻转「文件夹优先」**——
 * 直接把整个比较器 reversed() 会把文件夹甩到后面去。
 *
 * 同键时按名称升序兜底：排序结果稳定可预期（基准序本身就是名称序，
 * 加了兜底即使基准序变了同键项也仍然 A-Z）。
 */
private fun networkFileComparator(type: NetworkSortType, order: SortOrder): Comparator<NetworkFile> {
  val foldersFirst = compareBy<NetworkFile> { !it.isDirectory }
  val key: Comparator<NetworkFile> = when (type) {
    NetworkSortType.Name -> compareBy { it.name.lowercase() }
    NetworkSortType.Date -> compareBy { it.lastModified }
    NetworkSortType.Size -> compareBy { it.size }
    // 类型：与列表视图的 section 顺序一致（文件夹 → 图片 → 视频 → 其他）
    NetworkSortType.Type -> compareBy { it.type().typeRank() }
    // 随机不走比较器 —— VM 的 combine 里有单独的洗牌分支（种子进流，重组不换序）
    NetworkSortType.Random -> compareBy { 0 }
  }
  val directed = if (order.isAscending) key else key.reversed()
  return foldersFirst.then(directed.thenBy { it.name.lowercase() })
}

/** 类型排序权重：与列表视图 section 顺序（文件夹 / 图片 / 视频 / 其他）一致 */
private fun NetworkFileType.typeRank(): Int =
  when (this) {
    NetworkFileType.FOLDER -> 0
    NetworkFileType.PHOTO -> 1
    NetworkFileType.VIDEO -> 2
    NetworkFileType.OTHER -> 3
  }
