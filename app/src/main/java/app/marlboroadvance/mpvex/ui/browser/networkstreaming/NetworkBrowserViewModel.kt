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
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.download.NetworkDownloadCenter
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.DirSizeEntry
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.DirSizeUiState
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.NetworkDirSizeStore
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkTool
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkToolboxExecutor
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkToolboxPlanner
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.ToolPlan
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.ToolProgress
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.ToolResult
import app.marlboroadvance.mpvex.preferences.NetworkGroupMode
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

  /**
   * 时间分组方式（镜像持久化偏好，见 BrowserPreferences.networkGroupMode）。
   *
   * 只改「怎么切堆」，实际切分在 UI 层用 [groupNetworkFilesByPeriod] 做 ——
   * 排序结果 [files] 不变，分组是它的纯函数视图，没必要再进一次 combine。
   */
  private val _groupMode = MutableStateFlow(browserPreferences.networkGroupMode.get())

  /**
   * 按文件类型强制分组（镜像持久化偏好，见 BrowserPreferences.networkTypeGrouping）。
   *
   * 与 [groupMode] 一样是 UI 层的切分口径：排序结果 [files] 不变，分组是它的纯函数
   * 视图（`groupNetworkFilesByType`）。放进 VM 只为了下面三件事：持久化、镜像成流、
   * 以及切换时 +1 tick 让页面回顶。
   */
  private val _typeGrouping = MutableStateFlow(browserPreferences.networkTypeGrouping.get())

  /**
   * 目录滚动位置记忆：key = `"$connectionId|$path"`，value = `firstVisibleItemIndex`。
   *
   * 为什么需要它：从照片查看器 / 播放器返回到浏览页时，滚动位置**不可靠**。
   *    `rememberLazyGridState()` 靠 `LazyGridState.Saver` 由导航框架的
   *    `SaveableStateHolder` 保存恢复，而那条链有几个已知不稳的点：
   *    ① 恢复发生在**条目还没组合出来**的时刻（`loadFiles` 是异步的），
   *       LazyGrid 在 `totalItemsCount == 0` 时把索引 clamp 成 0，等数据到位也回不去了；
   *    ② 多个浏览页 entry 的 saved state 可能串键，表现就是「换个文件夹回来位置是上一个的」。
   *    两种症状都出现过：有时停在中间，有时直接回顶端。
   *
   *    所以在 UI 侧**等条目落位后再显式 `scrollToItem`** 一次，位置才可预期。
   *
   * ⚠️ 放 ViewModel 而不是 `rememberSaveable`：本 ViewModel 是 Activity 作用域
   *    （多个浏览页 entry 共用），跨「进查看器再返回」活着；而 rememberSaveable 的
   *    生命周期绑在单个 entry 上，被 pop 就没了，正是要救的那个场景。
   * ⚠️ 只在**离开页面时**写（组合销毁那一刻，见 UI 侧 DisposableEffect），滚动过程中
   *    不写 —— 否则每次滚动都触发重组。
   */
  private val scrollPositions = java.util.concurrent.ConcurrentHashMap<String, Int>()

  /** 随机排序的种子：每次（重）选「随机」都换一个新种子 ⇒ 列表换一批顺序 */
  private val _randomSeed = MutableStateFlow(System.currentTimeMillis())

  /**
   * 「用户动过排序」的单调计数，每次有效排序操作 +1。
   *
   * 为什么不能只让 UI 观察 [sortType] / [sortOrder]：**重复点「随机」时这两个值都不变**
   *    （`setSortType` 只换 [randomSeed] 就 return），而列表确实重排了 ——
   *    UI 用值变化做回顶的触发条件，这一种就永远不触发，用户停在原来那一段，
   *    「换了一批」完全看不出来（反馈）。
   * 用一个专门的计数器当「动作发生了」的信号，与值是否变化解耦。
   * 具体「+1」的时机只有两处：① [setSortType] / [setSortOrder]（切维度、翻方向、
   *    重复点随机换批）；② [loadFiles] 里**随机排序下的下拉刷新**（种子换了 ⇒ 整副牌
   *    重洗，等同于换了一批顺序）。翻页 / 换目录 / 普通刷新都不碰它，所以不会误抢滚动位置。
   */
  private val _sortChangeTick = MutableStateFlow(0)

  val sortType: StateFlow<NetworkSortType> = _sortType.asStateFlow()
  val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()
  val groupMode: StateFlow<NetworkGroupMode> = _groupMode.asStateFlow()
  val typeGrouping: StateFlow<Boolean> = _typeGrouping.asStateFlow()

  /** 「动过排序」的计数，UI 拿它做「回到 item 0」的触发键 */
  val sortChangeTick: StateFlow<Int> = _sortChangeTick.asStateFlow()

  /** 记下某个目录的滚动位置（离开页面时调用）。见 [scrollPositions] */
  fun saveScrollPosition(connectionId: Long, path: String, index: Int) {
    if (index <= 0) {
      // 顶部的条目不用记：恢复成 0 本来就是默认行为，存了反而多一次无效滚动
      scrollPositions.remove("$connectionId|$path")
      return
    }
    scrollPositions["$connectionId|$path"] = index
  }

  /** 取某个目录上次的滚动位置；没有记录返回 0 */
  fun getScrollPosition(connectionId: Long, path: String): Int =
    scrollPositions["$connectionId|$path"] ?: 0

  /** 恢复之后清掉：下次从零进这个目录应停在顶部，而不是上次的残留 */
  fun clearScrollPosition(connectionId: Long, path: String) {
    scrollPositions.remove("$connectionId|$path")
  }

  /**
   * 注：「回顶时不要误触发」这件事**不再由 ViewModel 记账**。
   *
   * 曾经这里有一对 `consumedSortTick` / `consumeSortTick()`：因为
   * `LaunchedEffect(tick)` 在**每次全新组合时都会启动一次**（键不变也启动），而 nav3 只
   * 组合栈顶 entry —— 从查看器 / 播放器返回时本页是重新组合的，那个 effect 会立刻把
   * 用户弹回顶端。当时的解法是让 UI 记住「已消费到哪个 tick」。
   *
   * v137 起这套逻辑收进 `ui/utils/ScrollToTop.kt#ScrollToTopOnOrderChange`（用
   * `remember` 存住首次组合时的 key，天然不误触发），页面只需把 [sortChangeTick]
   * 当作信号传进去。ViewModel 只负责「顺序确实变了就 +1」，不必知道 UI 怎么消费。
   */

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

  /**
   * 是否正在列目录。
   *
   * ⚠️ **初值必须是 true**（v170）：每进一个目录都是新 VM + 全新组合，
   * `loadFiles()` 挂在 `LaunchedEffect` 里、首帧之后才跑。初始为 false 时
   * 首帧正好落在「没在加载 + 还没有条目 + totalCount=0」这一档 ⇒ 界面会先闪一下
   * **「空文件夹 · 该文件夹中没有文件或子目录」**，再被骨架屏顶掉。
   * 空目录本来就是极少数，这一闪纯属误导（用户以为目录是空的 / 没加载出来）。
   * 初值为 true 后首帧直接进骨架屏，数据到了再切内容，全程不跳档。
   */
  private val _isLoading = MutableStateFlow(true)
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
          //    ⚠️ 失败绝不能写：原来这里用 `listFiles(...).getOrNull()`，失败被吞成 null，
          //    等于把「这次没列成」记成「这个文件夹没有图片」并落盘 30 天 ——
          //    网络抖一下，这个文件夹的封面在**整个 TTL 内**都不再加载（
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
          // 改名会让路径变，但目录大小不变；真正需要失效的是「以后的统计别拿着旧路径」——
          // 一个连接整体失效最省心（见 NetworkDirSizeStore.invalidate 的说明）
          NetworkDirSizeStore.invalidate(connectionId)
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
          // 删掉东西以后，祖先目录的大小全变了 —— 缓存必须失效
          NetworkDirSizeStore.invalidate(connectionId)
          loadFiles()
          onResult(true, null)
        }
        .onFailure { onResult(false, it.message ?: "删除失败") }
    }
  }

  /**
   * 新建文件夹（`fs/mkdir`）。
   *
   * 与 [renameFile] / [deleteFile] 同一套回执约定：写操作作用在服务器上，
   * 成败必须有明确结果，UI 拿它弹 toast。
   *
   * @param name 新文件夹名（只做最基本的合法性校验，重名的判据在服务端）
   */
  fun createFolder(name: String, onResult: (Boolean, String?) -> Unit) {
    viewModelScope.launch {
      val client = writeClient()
      if (client == null) {
        onResult(false, "当前协议不支持新建文件夹")
        return@launch
      }
      val trimmed = name.trim()
      if (trimmed.isEmpty()) {
        onResult(false, "名称不能为空")
        return@launch
      }
      if (trimmed.contains('/')) {
        onResult(false, "名称不能包含 /")
        return@launch
      }
      val dir = currentPath.trim('/')
      val target = if (dir.isEmpty()) trimmed else "$dir/$trimmed"
      client.mkdir(target)
        .onSuccess {
          NetworkDirSizeStore.invalidate(connectionId)
          loadFiles()
          onResult(true, null)
        }
        .onFailure { onResult(false, it.message ?: "新建文件夹失败") }
    }
  }

  // ── 工具箱（按类型归类 / 提取并整理 / 清空目录 …，只有 AList 可用）──

  private val _toolProgress = MutableStateFlow<ToolProgress?>(null)

  /**
   * 工具箱的实时状态。**挂在 ViewModel 上而不是面板的 `remember` 上** ——
   * 面板可以随时关掉，整理继续跑（几百个文件要几十秒，谁也不愿意盯着面板）。
   */
  val toolProgress: StateFlow<ToolProgress?> = _toolProgress.asStateFlow()

  /** 同一时刻只允许一个整理任务：两个任务同时搬同一个目录，冲突处理会互相打架 */
  private var toolJob: kotlinx.coroutines.Job? = null

  /** 当前是不是连接根目录（`/` 与空串都算同一个位置） */
  private fun isAtRoot(): Boolean = currentPath.trim('/').isEmpty()

  /**
   * 扫描（**只读**，不动服务器），产出一份计划给确认框过目。
   *
   * @param onResult (计划, 失败原因)。二者必有其一非空 —— 扫描失败也要让用户看到原因，
   *   静默返回会让「点了没反应」变成新的抱怨点。
   */
  fun scanToolbox(tool: NetworkTool, onResult: (ToolPlan?, String?) -> Unit) {
    if (toolJob?.isActive == true) {
      onResult(null, "已有整理任务在运行")
      return
    }
    // 根目录只开放只读工具。整理 / 清理类在根上会动整块存储的顶层内容
    // （按类型归类会建「图片/视频」把顶层文件全搬走），而这个位置往往就是
    // 所有网盘挂载的汇合处 —— 一次误点代价太大。UI 那边同样置灰，这里是第二道闸。
    if (!tool.readOnly && isAtRoot()) {
      onResult(null, "根目录下不开放整理工具，请先进入具体目录")
      return
    }
    toolJob =
      viewModelScope.launch {
        try {
          val client = writeClient()
          if (client == null) {
            onResult(null, "当前协议不支持整理（只有 AList 有写接口）")
            return@launch
          }
          val plan =
            NetworkToolboxPlanner.plan(
              tool = tool,
              root = currentPath.trim('/'),
              // 必须写成具名实参：尾随 lambda 会绑到最后一个参数 onPhase 上
              list = { path -> client.listFiles(path).getOrThrow() },
            )
          onResult(plan, null)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "工具箱扫描失败: ${tool.name} | ${e.message}")
          onResult(null, e.message ?: "扫描失败")
        }
      }
  }

  /** 取消正在跑的整理任务（做到哪儿算哪儿，不回滚 —— 确认框已写明不可撤销） */
  fun cancelToolbox() {
    toolJob?.cancel()
  }

  /** 面板关掉时清状态，下次打开不会看到上一轮的结果 */
  fun resetToolbox() {
    if (toolJob?.isActive != true) _toolProgress.value = null
  }

  /**
   * 执行计划。
   *
   * 失败与取消都**不算异常退出**：已经改动的部分留在服务器上，所以两条路径都要
   * 刷新列表（让用户立刻看到「现在是什么样」）并回一句结果。
   */
  fun runToolbox(plan: ToolPlan, onFinished: (String) -> Unit) {
    if (toolJob?.isActive == true) {
      onFinished("已有整理任务在运行")
      return
    }
    _toolProgress.value =
      ToolProgress(tool = plan.tool, phase = "准备中…", done = 0, total = plan.actions.size)
    toolJob =
      viewModelScope.launch {
        val client = writeClient()
        if (client == null) {
          _toolProgress.value =
            _toolProgress.value?.copy(errorMessage = "当前协议不支持整理（只有 AList 有写接口）")
          return@launch
        }
        try {
          val result =
            NetworkToolboxExecutor(client).execute(plan) { phase, done, total, current, failed ->
              _toolProgress.value =
                ToolProgress(
                  tool = plan.tool,
                  phase = phase,
                  done = done,
                  total = total,
                  current = current,
                  failed = failed,
                )
            }
          _toolProgress.value = _toolProgress.value?.copy(result = result) ?: ToolProgress(
            tool = plan.tool,
            phase = "已完成",
            done = result.succeeded,
            total = result.succeeded + result.failed,
            result = result,
          )
          NetworkDirSizeStore.invalidate(connectionId)
          loadFiles()
          onFinished(toolResultText(plan.tool, result))
        } catch (e: CancellationException) {
          // 用户点了取消：已完成的部分照样反映到列表里
          _toolProgress.value = _toolProgress.value?.copy(cancelled = true)
          NetworkDirSizeStore.invalidate(connectionId)
          loadFiles()
        } catch (e: Exception) {
          Log.w(TAG, "工具箱执行失败: ${plan.tool.name} | ${e.message}")
          _toolProgress.value = _toolProgress.value?.copy(errorMessage = e.message ?: "整理失败")
          NetworkDirSizeStore.invalidate(connectionId)
          loadFiles()
        }
      }
  }

  private fun toolResultText(tool: NetworkTool, result: ToolResult): String {
    val head =
      when {
        result.succeeded == 0 && result.failed == 0 -> "${tool.title}：没有需要处理的文件"
        result.failed == 0 -> "${tool.title}完成：处理 ${result.succeeded} 项"
        else -> "${tool.title}：成功 ${result.succeeded} 项，失败 ${result.failed} 项"
      }
    val swept = if (result.sweptDirs > 0) "，另删除 ${result.sweptDirs} 个空目录" else ""
    return head + swept
  }

  // ── 属性面板 / 目录大小统计 ──

  /**
   * 当前连接的协议名（属性面板「存储」那一行：`我的 NAS · AList`）。
   *
   * 由 [loadFiles] 顺手填 —— 那里本来就取了一次 connection，
   * 不必为了一行文案再查一次数据库。
   */
  private val _protocolLabel = MutableStateFlow("")
  val protocolLabel: StateFlow<String> = _protocolLabel.asStateFlow()

  private var dirSizeJob: kotlinx.coroutines.Job? = null

  /** 缓存命中的结果（面板一打开就能显示，不必每次重算 —— 见 [NetworkDirSizeStore]） */
  fun cachedDirSize(file: NetworkFile): DirSizeEntry? =
    NetworkDirSizeStore.cached(connectionId, file.path)

  /**
   * 统计一个目录的总大小（递归 + 缓存）。
   *
   * ⚠️ 结果通过回调回给界面，而不是做成一条 StateFlow：**面板是单例的一份状态**，
   * 但用户可能连点几个目录的属性 —— 那属于「换了一个问题」，旧结果没有保留价值。
   * 回调写法让「这是第几次提问」天然对上，不必在 VM 里维护一份「当前在看哪个目录」。
   *
   * @param onState 先收 `running = true`（有旧结果时一并带上，界面继续显示旧数字），
   *   再收结果或失败原因
   */
  fun computeDirSize(file: NetworkFile, onState: (DirSizeUiState) -> Unit) {
    val cached = NetworkDirSizeStore.cached(connectionId, file.path)
    if (cached != null) {
      onState(DirSizeUiState(entry = cached))
      return
    }
    dirSizeJob?.cancel()
    onState(DirSizeUiState(running = true))
    dirSizeJob =
      viewModelScope.launch {
        try {
          val connection = cachedConnection
            ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
            ?: error("连接已不存在")
          val entry =
            NetworkDirSizeStore.compute(
              connectionId = connectionId,
              path = file.path,
              // 具名实参：尾随 lambda 会绑到最后一个参数 onPhase 上（v152 踩过）
              list = { path -> repository.listFiles(connection, path).getOrThrow() },
            )
          onState(DirSizeUiState(entry = entry))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "目录大小统计失败: ${file.path} | ${e.message}")
          onState(DirSizeUiState(error = e.message ?: "统计失败"))
        }
      }
  }

  fun cancelDirSize() {
    dirSizeJob?.cancel()
  }

  /**
   * 开始下载（文件 = 直接存到 Download/CineIsle，目录 = 打包成 zip）。
   *
   * ⚠️ 真正的任务跑在 [NetworkDownloadCenter]（进程级）上 —— 本方法只负责
   * 「把 connection 取出来交给引擎」，这样浏览页被销毁也不影响下载，
   * 界面也不必自己持有一份 `NetworkConnection`。
   *
   * @param onResult (是否已开始, 未开始的原因)。已经在下载时不排队 ——
   *   同时往 Download 写两个大文件，用户分不清哪个进度条是谁的。
   */
  fun startDownload(file: NetworkFile, onResult: (Boolean, String?) -> Unit) {
    viewModelScope.launch {
      val connection = cachedConnection
        ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
      if (connection == null) {
        onResult(false, "连接已不存在，请重新进入")
        return@launch
      }
      val started = NetworkDownloadCenter.start(application, connection, file)
      onResult(started, if (started) null else "已有下载任务在运行，请等它结束")
    }
  }

  // ── 双栏（平板）：上一级的文件夹列表 ──

  /**
   * 上一级的子文件夹（双栏左栏）。
   *
   * `null` = 还没列出来（界面显示转圈），空列表 = 列过了但没有兄弟目录。
   *
   * ⚠️ 只在**宽屏**由界面调用（[ensureSiblings]），窄屏一个请求都不多发 ——
   * 这个列表唯一的用途就是双栏导航。
   */
  private val _siblings = MutableStateFlow<List<NetworkFile>?>(null)
  val siblings: StateFlow<List<NetworkFile>?> = _siblings.asStateFlow()

  private var siblingsLoaded = false

  /** 宽屏首帧调用一次；列过就不再列（同一 ViewModel 的路径不变，兄弟目录也不会变） */
  fun ensureSiblings() {
    if (siblingsLoaded) return
    siblingsLoaded = true
    viewModelScope.launch {
      try {
        val connection = cachedConnection
          ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
        if (connection == null) {
          _siblings.value = emptyList()
          return@launch
        }
        val trimmed = currentPath.trim('/')
        // 根目录没有「上一级」——界面那边也不会显示左栏，这里给空列表兜底
        if (!trimmed.contains('/')) {
          _siblings.value = emptyList()
          return@launch
        }
        val parent = trimmed.substringBeforeLast('/')
        val list = repository.listFiles(connection, parent).getOrThrow()
        _siblings.value =
          list
            .filter { it.isDirectory }
            .sortedWith(compareBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name })
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "上一级目录列不出来: $currentPath | ${e.message}")
        // 列失败给空列表（不是 null）：界面会落到「没有其他文件夹」而不是永远转圈
        _siblings.value = emptyList()
      }
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
    // 随机排序下「刷新」必须连顺序一起换：排序流是
    // `combine(原始列表, 排序项, 方向, 随机种子)`，种子不变 ⇒ 同一份列表洗出来的
    // 顺序逐项一致，用户看到的就是「下拉刷新了但一个都没动」，像是刷新没生效。
    // ⚠️ 只在下拉刷新（invalidateCovers = true）时换种子：
    //    进目录 / 返回上一级不要换，否则同一个文件夹每次进出都换一批顺序，
    //    「刚才那张在第三行」这种位置记忆会彻底失效。
    val reshuffled = invalidateCovers && _sortType.value == NetworkSortType.Random
    if (reshuffled) {
      _randomSeed.value = System.currentTimeMillis()
    }
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // 长按菜单只对支持写操作的协议开放（当前只有 AList）
        _supportsWrite.value = connection.protocol == NetworkProtocol.ALIST
        // 属性面板「存储」那一行要用（`我的 NAS · AList`）
        _protocolLabel.value = connection.protocol.displayName

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
              // 「网络抖一下没加载出来」的封面下拉刷新也救不回来（正是这个）。
              val seenFolders = (_folderCovers.value.keys + coverFailures.keys).toList()
              _folderCovers.update { it - subFolders.toSet() }
              // 刷新 = 用户明确要求重来 ⇒ 失败计数一并清零，重试预算重新给满
              subFolders.forEach { coverFailures.remove(it) }
              viewModelScope.launch {
                repository.invalidateFolderCovers(connectionId, subFolders)
                seenFolders.forEach { ensureFolderCover(it) }
              }
            }
            // 随机排序 + 下拉刷新 = 整副牌重洗 ⇒ 与「切排序」一样必须回顶。
            // ⚠️ 信号只能在这里发（新列表已经落到 [_rawFiles] 之后）。
            //    在 `loadFiles()` 开头发的话，UI 收到 tick 时数据还是旧的：
            //    `requestScrollToItem(0)` 会落在旧列表上，等新数据 measure 时
            //    又按 `lastKnownFirstItemKey` 把「刷新前那一条」定位回中段 ——
            //    正是用户报的那个症状（网络位置随机排序 + 下拉）。
            if (reshuffled) _sortChangeTick.value += 1
          }
          .onFailure { e ->
            _error.value = e.message ?: "未知错误"
          }
        // 收藏状态与列表同源刷新：取消收藏后返回 / 重新进入都能看到正确星形
        _isCurrentFavorite.value = repository.isFavorite(connectionId, currentPath)
      } catch (e: Exception) {
        _error.value = e.message ?: "未知错误"
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
   *
   * ⚠️ 换种子这条路也必须给 [_sortChangeTick] +1：值没变但列表重排了，
   *    UI 靠 tick 回顶，少了它「随机换批」停在原位（见该字段的 KDoc）。
   */
  fun setSortType(type: NetworkSortType) {
    // 随机与时间分组**互斥**：洗牌会把时间桶打散，两件事语义冲突。
    // 切到随机就顺手把分组关掉（UI 那边分组项也会置灰）。
    if (type == NetworkSortType.Random && _groupMode.value.isActive) {
      _groupMode.value = NetworkGroupMode.None
      browserPreferences.networkGroupMode.set(NetworkGroupMode.None)
    }
    if (type == _sortType.value) {
      if (type == NetworkSortType.Random) {
        _randomSeed.value = System.currentTimeMillis()
        _sortChangeTick.value += 1
      }
      return
    }
    val order = if (type.naturalAscending) SortOrder.Ascending else SortOrder.Descending
    _sortType.value = type
    _sortOrder.value = order
    if (type == NetworkSortType.Random) {
      _randomSeed.value = System.currentTimeMillis()
    }
    _sortChangeTick.value += 1
    browserPreferences.networkSortType.set(type)
    browserPreferences.networkSortOrder.set(order)
  }

  /** 只翻方向（排序控件右边的上下箭头） */
  fun setSortOrder(order: SortOrder) {
    if (order == _sortOrder.value) return
    _sortOrder.value = order
    _sortChangeTick.value += 1
    browserPreferences.networkSortOrder.set(order)
  }

  /**
   * 切时间分组方式（不分组 / 按年份 / 按年月）。
   *
   * 选分组时**自动把排序切到「时间降序」**：按年份分组却按名字排，组内顺序与时间轴
   * 无关，几乎不是用户想要的（真实意图通常是「按年倒序看最近的」）。用户之后仍可
   * 手动改排序——桶的先后默认仍是新→旧，见 [groupNetworkFilesByPeriod]。
   *
   * 分组变化会改变整页布局，所以要 +1 tick 让 UI 回顶；否则停在原来的下标会「跳段」。
   */
  fun setGroupMode(mode: NetworkGroupMode) {
    if (mode == _groupMode.value) return
    _groupMode.value = mode
    browserPreferences.networkGroupMode.set(mode)
    if (mode.isActive && _sortType.value != NetworkSortType.Random) {
      // 内部会带上该维度的自然方向（时间 = 降序）并已 +1 tick
      setSortType(NetworkSortType.Date)
    }
    _sortChangeTick.value += 1
  }

  /**
   * 开关「按文件类型强制分组」。
   *
   * 只改切分口径，**不动排序维度与方向** —— 用户设好的排序在开关前后完全保留，
   * 只是从「照片视频混排」变成「各类各自成段、段内仍按那个排序」。
   * 段的先后顺序固定为 文件夹 → 视频 → 图片 → 其他（见 `NETWORK_TYPE_GROUP_ORDER`）。
   *
   * 整页顺序会变，所以 +1 tick 让页面回顶。
   */
  fun setTypeGrouping(enabled: Boolean) {
    if (enabled == _typeGrouping.value) return
    _typeGrouping.value = enabled
    browserPreferences.networkTypeGrouping.set(enabled)
    _sortChangeTick.value += 1
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

        // 播放地址三选一（SMB/FTP/WebDAV 走本地代理、AList 直连签名链、其余 ContentProvider）
        // 统一由 [NetworkVideoUriBuilder] 决定 —— 视界流（[NetworkFeedLauncher]）共用同一份，
        // 两条链路不会各自演化出「一边能拖进度、一边不能」这种差异。
        val useProxy = connection.protocol in NetworkVideoUriBuilder.PROXY_PROTOCOLS
        val uriBuilder = NetworkVideoUriBuilder(application, repository, connection)
        val stamp = System.currentTimeMillis()

        val uris = ArrayList<android.net.Uri>(videos.size)
        val titles = ArrayList<String>(videos.size)
        val networkPaths = ArrayList<String>(videos.size)
        videos.forEachIndexed { index, file ->
          uris.add(uriBuilder.uriFor(file, "${connectionId}_${stamp}_$index"))
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
        _error.value = e.message ?: "未知错误"
      }
    }
  }

  /**
   * 随机播放视频：洗牌后按「随机播放数量」设置截断（1~500 条护栏）。
   *
   * 与 Emby 媒体库工具行的随机播放同一口径（同一个设置项）。
   *
   * @param drillDown true = 穿透子文件夹（勾了「穿透子文件夹」），从当前目录逐层下钻累积；
   *   false = 只播当前目录（默认，与旧行为一致）
   * @param onEmpty 一条都没抽到时回调 —— 调用方据此给 toast。递归模式下调用方
   *   事先不知道有多少视频，只能**等抽完**才知道，所以结果必须回传而不是让它静默返回。
   */
  fun playRandomVideos(drillDown: Boolean = false, onEmpty: () -> Unit = {}) {
    viewModelScope.launch {
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val pool = if (drillDown) {
        val connection = repository.getConnectionById(connectionId) ?: return@launch
        NetworkFeedLauncher.collectVideos(
          repository = repository,
          connection = connection,
          startPath = currentPath,
          want = limit,
          drillDown = true,
        )
      } else {
        _rawFiles.value.filter { it.isVideoFile() }
      }
      if (pool.isEmpty()) {
        onEmpty()
        return@launch
      }
      playVideos(pool.shuffled().take(limit), 0)
    }
  }

  /**
   * 收集「随机播放图片」的队列（UI 拿它去开 `NetworkPhotoViewerScreen`）。
   *
   * ## 两种模式
   * - `drillDown = false`：当前目录的图片，原样返回（调用方自己洗牌 + 按
   *   `randomPlayCount` 截断，与旧行为逐字节一致）；
   * - `drillDown = true`：穿透子文件夹，**本级 + 每个子目录的各层**图片整层收下
   *   （不抽不截断，总量上限 [NetworkFeedLauncher.MAX_PHOTO_QUEUE]）。
   *   本级也算 —— 勾穿透不是「只看子文件夹」。
   *
   * ## 顺序：组间洗牌、组内保序
   * 目录来自 [NetworkFeedLauncher.collectPhotoGroups]，一组 = 一个目录的那一级图片。
   * 先看哪个相册是随机的（否则永远从第一个相册开头看），但同一相册内部保持
   * 目录里的原始顺序 —— 同一次拍摄的照片被洗散只会让幻灯片变成噪音。
   */
  suspend fun collectRandomPhotos(drillDown: Boolean = false): List<NetworkFile> {
    if (!drillDown) return _rawFiles.value.filter { it.isPhoto() }
    val connection = repository.getConnectionById(connectionId) ?: return emptyList()
    val groups = NetworkFeedLauncher.collectPhotoGroups(
      repository = repository,
      connection = connection,
      startPath = currentPath,
      drillDown = true,
    )
    return if (groups.size <= 1) groups.flatten() else groups.shuffled().flatten()
  }

  /**
   * 视界流播放（当前目录 / 含子文件夹）：随机抽一批交给仿抖音竖屏连播页。
   *
   * 与「随机播放」的区别只在最后一步 —— 不是丢给主播放器排队播，而是把整批地址
   * 交给 `VerticalFeedActivity` 自己管「滑到哪放哪」。取片范围、数量口径与
   * [playRandomVideos] 完全共用（同一个 `randomPlayCount` 设置项）。
   *
   * @param onEmpty 抽不到视频时回调，调用方负责 toast
   */
  fun startFeedPlayback(drillDown: Boolean = false, onEmpty: () -> Unit = {}) {
    viewModelScope.launch {
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val connection = repository.getConnectionById(connectionId)
      if (connection == null) {
        onEmpty()
        return@launch
      }
      val videos = NetworkFeedLauncher.collectVideos(
        repository = repository,
        connection = connection,
        startPath = currentPath,
        want = limit,
        drillDown = drillDown,
      )
      val started = NetworkFeedLauncher.launch(
        context = application,
        application = application,
        repository = repository,
        connection = connection,
        videos = videos,
        want = limit,
      )
      if (!started) onEmpty()
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
     * 需要走本地代理才能**拖动进度**的协议 —— 已挪到
     * [NetworkVideoUriBuilder.PROXY_PROTOCOLS]（播放队列与视界流共用同一份，
     * 见 `NetworkFeedLauncher.kt`）。这里不再保留第二份定义。
     */

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
 * ⚠️ **名称键必须走自然序，不能 `compareBy { it.name.lowercase() }`**（此前就是那么写的，
 *    用户「排序不生效」）：纯字典序把数字段**按字符**比 ⇒
 *    `e1018 < e1019 < e102 < e1020 < e1021`（'1' 后面那个 '0' 比 '2' 小），
 *    而人要的是 `e102 < e1018 < e1019 < e1020 < e1021`。
 *    这类 `e0xx` / `Ep 2 vs Ep 10` 的命名在媒体库里极常见，本地文件夹、本地视频、
 *    文件选择器三处早就用 [SortUtils.NaturalOrderComparator]（见 `SortUtils.kt`），
 *    网络模块当初漏了 —— 这次对齐。
 */
private fun networkFileComparator(type: NetworkSortType, order: SortOrder): Comparator<NetworkFile> {
  val foldersFirst = compareBy<NetworkFile> { !it.isDirectory }
  val key: Comparator<NetworkFile> = when (type) {
    // 自然序：把连续数字段按数值比（e102 < e1018）。大小写不敏感 + 跳过空白，
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
