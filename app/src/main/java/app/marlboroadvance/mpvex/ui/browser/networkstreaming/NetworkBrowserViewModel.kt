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
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
   * 当前连接是否支持**写操作**（重命名 / 删除）。目前只有 AList 支持。
   *
   * UI 据此决定要不要响应长按 —— 不支持的协议弹了菜单也是点了就报错，
   * 不如干脆不给入口。
   */
  private val _supportsWrite = MutableStateFlow(false)
  val supportsWrite: StateFlow<Boolean> = _supportsWrite.asStateFlow()

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

  /**
   * 列目录**失败**过的文件夹 → 本页累计失败次数。
   *
   * ⚠️ 必须和 [folderCovers] 里的 `null` 严格区分：
   * - `folderCovers[path] == null` = 列成功了、里面确实没图片（有效负缓存，别再列）；
   * - 出现在这里 = 这次**没列成**（网络抖动 / 会话过期），还没到放弃的时候。
   *
   * 两者混成同一个 Map 的后果就是：网络抖一下写进 null，这个封面在本页里再也不加载，
   * 用户只能靠下拉刷新或重进页面救回来。
   *
   * 计次是为了**封顶**：失败项不写进 [folderCovers] ⇒ 卡片滚出又滚回来（LazyGrid 会
   * 重新组合、`LaunchedEffect(path)` 会重跑）时还能再试一次，但不能无限重试 ——
   * 目录真的被删了时，来回滑一次发两轮请求是没法接受的。到上限就静默放弃，
   * 等下拉刷新（会清零）或重进页面。
   */
  private val coverFailures: MutableMap<String, Int> = ConcurrentHashMap()

  /** 封面列目录限流：一屏可见的文件夹可能十几个，全并发会挤爆 SMB 会话 */
  private val coverGate = Semaphore(3)

  /** 连接对象缓存：封面列目录与 loadFiles 同一个连接，不必每次查库 */
  private var cachedConnection: NetworkConnection? = null

  /**
   * 确保这个文件夹的封面已缓存（没有就列一次目录）。
   *
   * 「第一张图片」按**名称自然序**取（`minWithOrNull(NaturalOrderComparator)`）——
   * 与服务端返回顺序无关，同一文件夹每次进来封面稳定；且与列表排序同口径
   * （否则 `img9/img10` 这种命名下封面会取到第 10 张、列表第一却是第 9 张）。
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
    // 本页已经失败到次数上限：不再发请求，等下拉刷新（会把计数清零）或重进页面
    if ((coverFailures[folderPath] ?: 0) >= COVER_MAX_PAGE_ATTEMPTS) return
    if (!coverInFlight.add(folderPath)) return
    viewModelScope.launch {
      try {
        // 1) 持久缓存命中（含负缓存：列过了、没图片）→ 直接渲染，零请求
        val cached = repository.getFolderCover(connectionId, folderPath)
        if (cached != null) {
          _folderCovers.update { it + (folderPath to cached.coverPath) }
          coverFailures.remove(folderPath)
          return@launch
        }
        // 2) 未命中：列一次目录（Semaphore(3) 限流，一屏可见的文件夹可能十几个）。
        //    失败**自动重试一次** —— 刚进页面那一刻连接还在建、会话刚过期都很常见，
        //    立刻重来一次多半就过了；再失败就不再空转，留给下拉刷新 / 点重载。
        var attempt = 0
        var cover: String? = null
        var listedOk = false
        while (true) {
          attempt++
          val connection = cachedConnection
            ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
            ?: return@launch
          val outer = runCatching { coverGate.withPermit { repository.listFiles(connection, folderPath) } }
          val page = outer.getOrNull()
          if (page != null && page.isSuccess) {
            cover =
              page
                .getOrNull()
                ?.filter { it.isPhoto() }
                // ⚠️ 走**自然序**取第一张，不能 `minByOrNull { it.name.lowercase() }`：
                //    字典序会把 `img10.jpg` 判成比 `img9.jpg` 小 ⇒ 封面取的是第 10 张，
                //    而网格里排在第一的是第 9 张，两者对不上。与列表排序同口径。
                ?.minWithOrNull(compareBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name })
                ?.path
            listedOk = true
            break
          }
          Log.w(
            TAG,
            "封面列目录失败($attempt): $folderPath | " +
              (outer.exceptionOrNull() ?: page?.exceptionOrNull())?.message,
          )
          if (attempt >= COVER_LIST_ATTEMPTS) break
          delay(COVER_RETRY_DELAY_MS)
        }
        if (listedOk) {
          // 3) 列**成功**才写持久缓存（含「确实没图片」的负缓存）。
          //    ⚠️⚠️ 失败绝不能写：原来这里用 `listFiles(...).getOrNull()`，失败被吞成 null，
          //    等于把「这次没列成」记成「这个文件夹没有图片」并落盘 30 天 ——
          //    网络抖一下，这个文件夹的封面在**整个 TTL 内**都不再加载（用户反馈的
          //    「图片文件夹没封面、点进去却有图」就是这么来的）。
          repository.putFolderCover(connectionId, folderPath, cover)
          _folderCovers.update { it + (folderPath to cover) }
          coverFailures.remove(folderPath)
        } else {
          // 两次都没列成：本页**不当负缓存**（滚回来还能再试，见 coverFailures 的说明），
          // 只把计数 +1，到上限才放弃。
          coverFailures.merge(folderPath, 1) { a, b -> a + b }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 兜底：缓存读写的数据库异常不能让浏览页直接崩（viewModelScope 里没人接）
        Log.w(TAG, "封面处理异常: $folderPath | ${e.message}")
        coverFailures.merge(folderPath, 1) { a, b -> a + b }
      } finally {
        coverInFlight.remove(folderPath)
      }
    }
  }

  // ── 写操作（目前只有 AList 有官方接口） ──

  /**
   * 取可用于写操作的客户端；协议不支持时返回 null。
   *
   * 用 `as? AlistClient` 同时完成「取客户端」与「判断是否支持写」两件事 ——
   * SMB / FTP / WebDAV 三个客户端都不是 AlistClient，自然返回 null。
   */
  private suspend fun writeClient(): AlistClient? {
    val connection = cachedConnection
      ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
      ?: return null
    return runCatching { repository.clientFor(connection) }.getOrNull() as? AlistClient
  }

  /**
   * 重命名。
   *
   * @param onResult (成功?, 失败原因)。UI 拿它弹 Toast —— 写操作是作用在服务器上的，
   *   成败必须给明确回执（参考 Emby「扫描媒体库」的做法）。
   */
  fun renameFile(file: NetworkFile, newName: String, onResult: (Boolean, String?) -> Unit) {
    viewModelScope.launch {
      val client = writeClient()
      if (client == null) {
        onResult(false, "当前协议不支持重命名")
        return@launch
      }
      client.rename(file.path, newName)
        .onSuccess {
          loadFiles()
          onResult(true, null)
        }
        .onFailure { onResult(false, it.message ?: "重命名失败") }
    }
  }

  /**
   * 删除。
   *
   * ⚠️ **不可逆**（AList 没有回收站），UI 必须二次确认后再调。
   */
  fun deleteFile(file: NetworkFile, onResult: (Boolean, String?) -> Unit) {
    viewModelScope.launch {
      val client = writeClient()
      if (client == null) {
        onResult(false, "当前协议不支持删除")
        return@launch
      }
      // fs/remove 要的是「父目录 + 文件名列表」，不是完整路径
      val lastSlash = file.path.lastIndexOf('/')
      val dir = if (lastSlash >= 0) file.path.substring(0, lastSlash) else ""
      client.remove(dir, listOf(file.name))
        .onSuccess {
          loadFiles()
          onResult(true, null)
        }
        .onFailure { onResult(false, it.message ?: "删除失败") }
    }
  }

  /**
   * Load files in the current directory
   *
   * @param invalidateCovers 是否顺带失效子文件夹的封面缓存。**只有下拉刷新才传 true**。
   *
   *   ⚠️ 原来这个失效动作是**无条件**做的，于是「进入子文件夹 → 返回上一级」
   *   会把本页十几个子文件夹的封面缓存（本页 Map + Repository L1 + Room L2）
   *   全部清掉，逼着每个子文件夹**重新列一次目录**、封面图再重新下载一遍 ——
   *   懒加载与三级缓存全废，来回切几层目录就是成百上千个请求。
   *   （「100 个文件夹只有前 30 个能加载图片」被这个放大器推得更严重。）
   *
   *   现在只有用户显式下拉刷新（= 我要最新）才失效；正常进入 / 返回走缓存，零请求。
   */
  fun loadFiles(invalidateCovers: Boolean = false) {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // 长按菜单只对支持写操作的协议开放（当前只有 AList）
        _supportsWrite.value = connection.protocol == NetworkProtocol.ALIST

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _rawFiles.value = fileList.sortedWith(
              // 基准序也走自然序：虽然最终顺序由 networkFileComparator 决定，
              // 但基准序是它的兜底链，字典序会在这里留下一个不一致的暗面
              // （同名/同键项之间退回 '0' < '2' 的字符比）。
              compareBy<NetworkFile> { !it.isDirectory }
                .thenBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name },
            )
            // 只有下拉刷新才动封面缓存：子文件夹里的照片可能增删了，
            // 已看过的失效重取（值变了卡面自然刷新），没看过的仍然懒加载
            // （**只失效不预热** —— 预热等于把整个目录重新列一遍，那就不是懒加载了）。
            // 正常进入 / 返回上一级**什么都不做**：三级缓存直接命中，零请求。
            if (invalidateCovers) {
              val subFolders = fileList.filter { it.isDirectory }.map { it.path }
              // 已拿到结果的 + 上次列失败的，两类都要重取 —— 漏掉后面那类的话，
              // 「网络抖一下没加载出来」的封面下拉刷新也救不回来（用户反馈的正是这个）。
              val seenFolders = (_folderCovers.value.keys + coverFailures.keys).toList()
              _folderCovers.update { it - subFolders.toSet() }
              // 刷新 = 用户明确要求重来 ⇒ 失败计数一并清零，重试预算重新给满
              subFolders.forEach { coverFailures.remove(it) }
              viewModelScope.launch {
                repository.invalidateFolderCovers(connectionId, subFolders)
                seenFolders.forEach { ensureFolderCover(it) }
              }
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
      // ⚠️ 必须走 [networkPathDisplayName]：旧写法 `substringAfterLast('/', "")` 会把
      //    单段路径（第一层）判成空串 ⇒ 收藏名退化成连接名（顶栏标题同一个坑，
      //    已一起修）。完整推导见那个函数的 KDoc。
      val dirName = networkPathDisplayName(currentPath, connection.name)
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

        // 播放地址三选一：
        // - SMB / FTP / WebDAV：走本地代理（会话不能给播放器用，代理支持 Range ⇒ 能拖动）
        // - AList：服务端直出签名直链 / raw_url，mpv 原生支持 Range ⇒ **直连**，
        //   既不过代理也不过 ContentProvider 管道（管道是单向流，拖不了进度）
        // - 其余：ContentProvider 管道兜底
        val useProxy = connection.protocol in PROXY_PROTOCOLS
        val directUriClient =
          if (connection.protocol == NetworkProtocol.ALIST) repository.clientFor(connection) else null
        val stamp = System.currentTimeMillis()

        val uris = ArrayList<android.net.Uri>(videos.size)
        val titles = ArrayList<String>(videos.size)
        val networkPaths = ArrayList<String>(videos.size)
        videos.forEachIndexed { index, file ->
          val uri = if (directUriClient != null) {
            directUriClient.getFileUri(file.path).getOrThrow()
          } else if (useProxy) {
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

    /**
     * 文件夹封面「列目录」最多试几次（含首次）。
     *
     * 定 2 而不是 3+：失败基本是「刚连上 / 会话过期」这类一次性状态，第二把就能过；
     * 真正的错（目录被删、权限不够）重试多少次都一样，多试只是让一屏十几个文件夹
     * 一起在后台排队等超时。
     */
    private const val COVER_LIST_ATTEMPTS = 2

    /** 两次列目录之间的间隔：给连接 / 会话一点恢复时间，又不至于让用户盯着空封面对等 */
    private const val COVER_RETRY_DELAY_MS = 1_200L

    /**
     * 同一个文件夹在本页里最多尝试几「轮」（一轮 = 上面 [COVER_LIST_ATTEMPTS] 次列目录）。
     *
     * 定 2：首次进页面一轮，滚回来再来一轮 —— 真删掉的目录不会因为用户来回滑
     * 就一直发请求；而「网络抖了一下」几乎都能在这两轮里自愈。
     */
    private const val COVER_MAX_PAGE_ATTEMPTS = 2

    /**
     * 需要走本地代理才能**拖动进度**的协议。
     *
     * ⚠️ AList **刻意不在**这里：它的 `/d/...?sign=` 与 `raw_url` 本身就是支持
     * Range 的 http 直链，播放器直连即可（见 [playVideos] 里的 `directUriClient`
     * 分支）。把它加进来只会白绕一层本地代理。
     */
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
 * 同键时按名称**自然序**兜底：排序结果稳定可预期（基准序本身就是名称序，
 * 加了兜底即使基准序变了同键项也仍然 A-Z）。
 *
 * ⚠️ **名称键必须走自然序，不能 `compareBy { it.name.lowercase() }`**（v79 之前就是那么写的，
 *    用户实测「排序不生效」）：纯字典序把数字段**按字符**比 ⇒
 *    `e1018 < e1019 < e102 < e1020 < e1021`（'1' 后面那个 '0' 比 '2' 小），
 *    而人要的是 `e102 < e1018 < e1019 < e1020 < e1021`。
 *    这类 `e0xx` / `Ep 2 vs Ep 10` 的命名在媒体库里极常见，本地文件夹、本地视频、
 *    文件选择器三处早就用 [SortUtils.NaturalOrderComparator]（见 `SortUtils.kt`），
 *    网络模块当初漏了 —— 这次对齐。
 */
private fun networkFileComparator(type: NetworkSortType, order: SortOrder): Comparator<NetworkFile> {
  val foldersFirst = compareBy<NetworkFile> { !it.isDirectory }
  val key: Comparator<NetworkFile> = when (type) {
    // ⭐ 自然序：把连续数字段按数值比（e102 < e1018）。大小写不敏感 + 跳过空白，
    //    与本地文件夹 / 文件选择器同一份 DEFAULT 实例、同一口径。
    NetworkSortType.Name -> compareBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name }
    NetworkSortType.Date -> compareBy { it.lastModified }
    NetworkSortType.Size -> compareBy { it.size }
    // 类型：与列表视图的 section 顺序一致（文件夹 → 图片 → 视频 → 其他）
    NetworkSortType.Type -> compareBy { it.type().typeRank() }
    // 随机不走比较器 —— VM 的 combine 里有单独的洗牌分支（种子进流，重组不换序）
    NetworkSortType.Random -> compareBy { 0 }
  }
  val directed = if (order.isAscending) key else key.reversed()
  // 兜底也用自然序：否则同日期/同大小的项之间会退回字典序，与「按名称」排序自相矛盾
  return foldersFirst.then(directed).thenBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name }
}

/** 类型排序权重：与列表视图 section 顺序（文件夹 / 图片 / 视频 / 其他）一致 */
private fun NetworkFileType.typeRank(): Int =
  when (this) {
    NetworkFileType.FOLDER -> 0
    NetworkFileType.PHOTO -> 1
    NetworkFileType.VIDEO -> 2
    NetworkFileType.OTHER -> 3
  }
