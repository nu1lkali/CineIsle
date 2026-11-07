package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.network.FolderCoverKind
import app.marlboroadvance.mpvex.domain.network.FolderCoverPick
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.domain.network.folderCoverChildDirs
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.domain.network.pickFolderCover
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.download.NetworkDownloadCenter
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops.NetworkFileOps
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops.NetworkOpResult
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops.NetworkTransferMode
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos.NetworkPhotoImageLoader
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.DirSizeEntry
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.DirSizeUiState
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties.NetworkDirSizeStore
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkTool
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkToolboxExecutor
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.NetworkToolboxPlanner
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.RenameRuleBook
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.RenameRuleStore
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
  private val renameRuleStore: RenameRuleStore by inject()

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
   * 当前连接下**已收藏的目录路径集合**（长按子文件夹菜单的星形状态）。
   *
   * 与 [isCurrentFavorite] 是两件事、刻意分开：
   * - [isCurrentFavorite] 只答「我现在站的这一层收了没有」，顶栏 ⋮ 菜单用；
   * - 这个是「这一屏里哪些子文件夹已经收过」，长按菜单用。
   *
   * 刷新口径与列表同源（`loadFiles` 末尾一次查询），不走 Room Flow：
   * 一个浏览页一个 VM，进页面收一次就够；收藏改变时由 [toggleFavoriteFolder]
   * 就地增删，不必再查库。
   */
  private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
  val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

  /**
   * 当前连接是否支持**写操作**（重命名 / 删除）。目前只有 AList 支持。
   *
   * UI 据此决定要不要响应长按 —— 不支持的协议弹了菜单也是点了就报错，
   * 不如干脆不给入口。
   */
  private val _supportsWrite = MutableStateFlow(false)
  val supportsWrite: StateFlow<Boolean> = _supportsWrite.asStateFlow()

  /**
   * 文件夹封面缓存：文件夹 path → **封面引用**（**本页**可见的）。
   *
   * 「封面引用」是一个字符串，两种形态靠 scheme 区分（见
   * [app.marlboroadvance.mpvex.domain.network.isRemoteCoverRef]）：
   * - `http(s)://...` = 视频的服务端海报帧（AList `fs/list` 的 `thumb`，绝对 URL）；
   * - 其余 = 图片在挂载点内的相对 path（客户端取字节解码）。
   *
   * - value = null：这个文件夹**解析过了**、范围内没有可用封面 —— 别再列第二次；
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
   * 当前页是否停在连接的**根目录**（`/`）。
   *
   * 根目录在 AList 里是「存储挂载点列表」（阿里云盘 / 夸克 / 本地存储 …），不是媒体目录：
   * 给它下面每一项算封面既没有语义（一张盘里的某张图代表整个网盘？），代价也最高
   * （每个挂载点都要列一次目录，而挂载点底下往往几千条）。所以根目录**一律不给封面**，
   * 卡片保持默认文件夹图标 —— 也不写负缓存，退出根目录后子目录照常解析。
   *
   * ⚠️ 只对 ALIST 生效：SMB / WebDAV / FTP 的「根」通常就是共享目录本身，
   *    那里给封面是有意义的，维持原行为。
   */
  private val atConnectionRoot: Boolean = currentPath.trim('/').isEmpty()

  /** 本页连接的协议（loadFiles 里同步；封面的根目录判断要用，见 [atConnectionRoot]） */
  private var pageProtocol: NetworkProtocol? = null

  /**
   * 确保这个文件夹的封面已解析并缓存（没有就按下面的口径算一次）。
   *
   * ## 选择口径（素材全部由 AList 服务端提供，客户端不截帧、不合成）
   *
   * 1. 列本目录的**直接子级**，交给 [pickFolderCover] 挑一张：
   *    有服务端海报（`thumb` 非空）的视频**优先**，否则回退名称自然序第一张图片的 path；
   *    「仅含视频」走的也是这条 —— 图片候选为空时第 1 步直接命中视频海报。
   * 2. 本级挑不出来、且本目录**只有子文件夹**时向下穿透（广度优先、命中即停），
   *    深度与列目录次数各有上限，见 [COVER_SCAN_MAX_DEPTH] / [COVER_SCAN_MAX_LISTINGS]。
   * 3. 结果作为**封面引用**落 L1/L2，形态见 [_folderCovers] 的说明。
   *
   * 名称自然序是关键：与服务端返回顺序无关，同一文件夹每次算出来都是同一张；
   * 且与列表排序同口径（否则 `img9/img10` 这种命名下封面会取到第 10 张）。
   *
   * 三级查找：本页 Map → Repository L1（进程内 LRU）→ Repository L2（Room）。
   * 只有三级都没命中才真的列目录；列**成功**才回写持久缓存。
   * 持久缓存有**新鲜度**要求（12 小时 TTL + 目录 mtime 判据），过期即视为未命中并重算 ——
   * 见 [app.marlboroadvance.mpvex.repository.NetworkRepository.getFolderCover]。
   *
   * 安全地被重复调：已缓存 / 在途 / 连接没了都会静默返回。
   */
  fun ensureFolderCover(folderPath: String) {
    if (_folderCovers.value.containsKey(folderPath)) return
    // AList 根目录（存储挂载点列表）不加载封面：语义上不成立，成本还最高。
    // 直接返回、**不写负缓存** —— 这只是本页的策略，不是「这个目录没有封面」的结论。
    if (atConnectionRoot && pageProtocol == NetworkProtocol.ALIST) return
    // 本页已经失败到次数上限：不再发请求，等下拉刷新（会把计数清零）或重进页面
    if ((coverFailures[folderPath] ?: 0) >= COVER_MAX_PAGE_ATTEMPTS) return
    if (!coverInFlight.add(folderPath)) return
    viewModelScope.launch {
      try {
        // 该文件夹在**当前列表**里的修改时间：封面缓存的新鲜度判据之一
        //（缓存时间早于它 ⇒ 缓存之后这个目录被改过，封面要重算）。见 Repository.getFolderCover。
        // 拿不到（不在本页列表里）就传 0，只按 TTL 判。
        val dirModified = _rawFiles.value.firstOrNull { it.path == folderPath }?.lastModified ?: 0L
        // 1) 持久缓存命中（含负缓存：解析过了、范围内没有可用封面）→ 直接渲染，零请求。
        //    ⚠️ 过期（超过 12 小时）或目录 mtime 比缓存新 ⇒ Repository 返回 null，走下面的解析。
        val cached = repository.getFolderCover(connectionId, folderPath, dirModified)
        if (cached != null) {
          _folderCovers.update { it + (folderPath to cached.coverPath) }
          coverFailures.remove(folderPath)
          return@launch
        }
        // 2) 未命中：解析一次（Semaphore(3) 限流，一屏可见的文件夹可能十几个）。
        //    根目录列失败会**自动重试一次** —— 刚进页面那一刻连接还在建、会话刚过期
        //    都很常见，立刻重来一次多半就过了；再失败就不再空转，留给下拉刷新 / 点重载。
        val connection =
          cachedConnection
            ?: repository.getConnectionById(connectionId)?.also { cachedConnection = it }
            ?: return@launch
        val scan = scanFolderCover(connection, folderPath)
        val pick = scan.pick
        when {
          // 2a) 根目录都没列成：失败是暂时的，**不落盘**（见下方负缓存的说明）
          !scan.rootListed -> coverFailures.merge(folderPath, 1) { a, b -> a + b }

          // 2b) 命中：根目录与「真正产出封面的那一级」各记一行缓存。
          //     ⚠️ 中途穿透过的父目录**不记** —— 它们的完整解析结果与根目录不一定相同
          //     （同级里更靠前的兄弟目录，在它们的层级里可能先命中）。
          pick != null -> {
            repository.putFolderCover(connectionId, folderPath, pick.ref)
            val hit = scan.hitFolder
            if (hit != null && hit != folderPath) {
              repository.putFolderCover(connectionId, hit, pick.ref)
            }
            _folderCovers.update { it + (folderPath to pick.ref) }
            coverFailures.remove(folderPath)
            Log.d(TAG, "封面命中(${pick.kind}): $folderPath → $hit")
          }

          // 2c) 扫完确实没有：负缓存（下次进来零请求，也不会再为它发请求）
          scan.complete -> {
            repository.putFolderCover(connectionId, folderPath, null)
            _folderCovers.update { it + (folderPath to null) }
            coverFailures.remove(folderPath)
          }

          // 2d) 预算用完 / 某个子目录没列成 ⇒ 结论不完整，**不落盘**（否则会把
          //     「没查完」冻成「没有封面」缓存 12 小时）；本页计次，到上限静默放弃。
          else -> coverFailures.merge(folderPath, 1) { a, b -> a + b }
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

  // ── 封面解析（纯函数见 domain/network/FolderCoverSelector.kt） ──

  /** 穿透队列里的一项：待列的目录 + 它相对根目录的层级（根 = 0） */
  private data class PendingCoverDir(val path: String, val depth: Int)

  /**
   * 一次封面解析的结果。
   *
   * @param pick 选中的封面；null = 范围内没有可用封面
   * @param hitFolder 产出封面的那一级目录（null = 没命中）
   * @param rootListed 根目录**列成功**了吗。false = 网络/会话问题，不能当结论
   * @param complete 结论是否**完整**：false = 预算用完或有子目录没列成（不能当负缓存）
   */
  private data class CoverScan(
    val pick: FolderCoverPick?,
    val hitFolder: String?,
    val rootListed: Boolean,
    val complete: Boolean,
  )

  /**
   * 解析一个文件夹的封面：本级优先；本级没有就**逐层下探**，同层内比优先级。
   *
   * ## 穿透的触发条件
   *
   * - 本级 [pickFolderCover] 挑不出封面（没有带海报的视频、也没有图片）；
   * - 并且本级**没有媒体文件**（图片 / 视频一个都没有）。
   *
   * 第二条是成本闸门：一个装满「无海报视频」的电影目录如果也往下钻，一张卡片就要
   * 多列 5 次目录，一屏十几个文件夹就是几十次请求，换来的大概率还是空 —— 所以
   * 「有媒体文件但都没封面」一律就地停止，只有**纯结构目录**（类型 / 剧名 / 季）才穿透。
   *
   * ⚠️ 判据是「没有**媒体**文件」，不是「全是文件夹」：目录里躺一个 `.DS_Store`、
   *    一个 `.nfo` 或字幕都不该阻断穿透（那些文件本来也当不了封面）。
   *
   * ## 同层的优先级：视频海报 > 图片
   *
   * 只在**单个目录内部**比类型是不够的。`A/{视频, 图片}` 这种结构下，「图片」在名称
   * 自然序里排在「视频」前面（`图` U+56FE < `视` U+89C6），若按「谁先命中谁说了算」，
   * A 会永远拿图片子目录当封面，正好与「视频优先」相反 —— 这是用户报的原始症状。
   * 所以改成**一层一层地看**：同层里只要有一个子目录能给出视频海报就取它；整层只有
   * 图片时，才取自然序第一张。
   *
   * 代价（必须明说）：同层里先命中图片时**不能**立刻返回，得把这一层剩下的兄弟目录
   * 看完才知道有没有海报 —— 单次解析的列目录次数因此可能从 1 次涨到「本层子目录数」，
   * 由 [COVER_SCAN_MAX_LISTINGS] 兜底。先命中海报的那条路径仍然只花 1 次。
   *
   * ## 终止条件（四条，任意一条成立就停）
   *
   * 1. **本层命中视频海报 ⇒ 立即返回**（同层最优，自然序已定胜负）；
   * 2. **本层扫完只有图片 ⇒ 返回自然序第一张图片**，不再往更深处找（近的优先）；
   * 3. **深度上限** [COVER_SCAN_MAX_DEPTH]：根 = 0，最多下钻到第 3 层
   *    （库 → 类型 → 剧名 → 季，正好覆盖常见媒体树的四级结构）；
   * 4. **列目录次数上限** [COVER_SCAN_MAX_LISTINGS]：一次解析最多列 6 次目录。
   *
   * ⚠️ 穿透**只对 AList 生效**：只有 AList 的 `fs/list` 会带 `thumb`（服务端封面），
   *    SMB / WebDAV / FTP 的列目录是重量级往返（PROPFIND / 会话复用有限），
   *    保持「只看直接子级」的老行为，不给它们叠加 N 次请求。
   */
  private suspend fun scanFolderCover(
    connection: NetworkConnection,
    rootPath: String,
  ): CoverScan {
    val rootItems =
      listForCover(connection, rootPath, retry = true)
        ?: return CoverScan(pick = null, hitFolder = null, rootListed = false, complete = false)

    // 第 0 层：根目录自己（列表已在手上，不再多列一次）
    pickFolderCover(rootItems)?.let { pick ->
      return CoverScan(pick = pick, hitFolder = rootPath, rootListed = true, complete = true)
    }

    val penetrable =
      connection.protocol == NetworkProtocol.ALIST &&
        rootItems.none { it.isPhoto() || it.isVideoFile() }
    if (!penetrable) {
      // 没有可穿透的结构目录（或协议不支持）—— 扫描到此为止，且结论是完整的
      return CoverScan(pick = null, hitFolder = null, rootListed = true, complete = true)
    }

    var listings = 1
    var complete = true
    // 本层待列的目录；depth 从 1 起（根 = 0）
    var level: List<PendingCoverDir> = folderCoverChildDirs(rootItems).map { PendingCoverDir(it.path, 1) }
    // 本层已找到的图片封面：先兜在手里，继续看同层有没有带海报的视频目录
    var imageHit: CoverScan? = null

    while (level.isNotEmpty()) {
      val nextLevel = mutableListOf<PendingCoverDir>()
      var truncated = false

      for (dir in level) {
        if (listings >= COVER_SCAN_MAX_LISTINGS) {
          truncated = true
          break
        }
        val items = listForCover(connection, dir.path, retry = false)
        if (items == null) {
          // 这一支查不了（目录被删 / 权限 / 抖动）⇒ 未知分支，不敢当负缓存
          complete = false
          continue
        }
        listings++

        val pick = pickFolderCover(items)
        if (pick != null) {
          if (pick.kind == FolderCoverKind.VIDEO_THUMB) {
            // 同层最优，立即收官
            return CoverScan(pick = pick, hitFolder = dir.path, rootListed = true, complete = true)
          }
          if (imageHit == null) {
            imageHit = CoverScan(pick = pick, hitFolder = dir.path, rootListed = true, complete = true)
          }
          // ⚠️ 不 return：同层后面可能还有能给海报的视频目录（`A/{图片, 视频}` 就是这种）
          continue
        }

        // 本级没封面：只有**没有媒体文件**的纯结构目录才继续往下（见 KDoc 的成本闸门）
        if (dir.depth >= COVER_SCAN_MAX_DEPTH) continue
        if (items.any { it.isPhoto() || it.isVideoFile() }) continue
        folderCoverChildDirs(items).forEach { child ->
          nextLevel += PendingCoverDir(child.path, dir.depth + 1)
        }
      }

      // 本层看完了：有图片就收（近的优先，不再下探）
      val hit = imageHit
      if (hit != null) return hit
      if (truncated) {
        // 本层没找完：既不能下探（兄弟目录里可能有更浅的封面），也不能当负缓存
        complete = false
        break
      }
      level = nextLevel
    }

    return CoverScan(pick = null, hitFolder = null, rootListed = true, complete = complete)
  }

  /**
   * 列一次目录给封面用；[retry] 为 true 时按 [COVER_LIST_ATTEMPTS] 重试。
   *
   * 两条都必须走 [coverGate]（Semaphore(3)）：一屏可见的文件夹可能十几个，全并发
   * 会把 SMB 会话 / AList 登录态挤爆。失败一律返回 null（不抛）—— 封面的失败
   * 处理在调用方，这里只负责「拿到没拿到」。
   */
  private suspend fun listForCover(
    connection: NetworkConnection,
    path: String,
    retry: Boolean,
  ): List<NetworkFile>? {
    var attempt = 0
    while (true) {
      attempt++
      val outer = runCatching { coverGate.withPermit { repository.listFiles(connection, path) } }
      val page = outer.getOrNull()
      if (page != null && page.isSuccess) return page.getOrNull()
      Log.w(
        TAG,
        "封面列目录失败($attempt): $path | " +
          (outer.exceptionOrNull() ?: page?.exceptionOrNull())?.message,
      )
      if (!retry || attempt >= COVER_LIST_ATTEMPTS) return null
      delay(COVER_RETRY_DELAY_MS)
    }
  }

  /**
   * 进入目录后的**预热**：把本次列表里能提前算的都算掉，让「滚到眼前」这一步不再等网络。
   *
   * 两件事，都在后台、都不阻塞加载：
   * 1. **子文件夹封面**（前 [PRELOAD_FOLDER_LIMIT] 个，按列表基序）—— 一张文件夹卡要
   *    显示封面必须列一次子目录。不预热的话，一屏十几张卡是「谁先组合出来谁先排队」，
   *    表现就是封面一个接一个往外蹦；预热让它们在列表落地的同一刻全部入队，
   *    卡片组合出来时结果大概率已经在缓存里（仍走同一份 Semaphore(3)，不会挤爆会话）。
   * 2. **本级媒体缩略图字节**（前 [PRELOAD_THUMB_LIMIT] 个带 `thumb` 的条目）——
   *    只预热服务端已经给了缩略图的（AList 的 `thumb`，通常几 KB～几十 KB）。
   *    SMB / WebDAV / FTP 没有 `thumb`，预热等于把原图整份拉下来换几百毫秒，不做。
   *
   * ⚠️ 上限是必须的：这些都是**猜测性**流量。一个 300 个文件夹的库根目录若全预热，
   *    就是 300 次列目录（最坏按每次 6 跳算可达 1800 次请求），而用户可能只看得见
   *    前两屏。取 12 / 24 的依据：一屏约 9~12 格（3 列 × 4 行）+ Compose 的预取余量。
   *
   * ⚠️ 全程复用既有缓存，不会重复发请求：`ensureFolderCover` 命中 L1/L2 直接返回、
   *    Coil 命中内存/磁盘缓存直接返回。
   */
  private fun preloadCovers(items: List<NetworkFile>) {
    if (!browserPreferences.folderCoverEnabled.get()) return
    // 根目录完全不预热：下面每一项都是存储挂载点，解析封面等于把每个网盘各列一次，
    // 却没有可看的结果（卡片本来就保持图标，见 atConnectionRoot）
    if (atConnectionRoot && pageProtocol == NetworkProtocol.ALIST) return

    items
      .asSequence()
      .filter { it.isDirectory }
      .map { it.path }
      .take(PRELOAD_FOLDER_LIMIT)
      .forEach { ensureFolderCover(it) }

    val thumbs =
      items
        .asSequence()
        .filter { !it.isDirectory }
        .mapNotNull { file -> file.thumb?.takeIf { it.isNotBlank() } }
        .take(PRELOAD_THUMB_LIMIT)
        .toList()
    if (thumbs.isEmpty()) return
    val loader = NetworkPhotoImageLoader.get(application)
    thumbs.forEach { url ->
      // enqueue 是「发起即返回」，解码与落缓存都在 Coil 自己的调度器上。
      // 单张失败不该影响其它张，更不能让异常冒进 viewModelScope（没人接 ⇒ 崩进程）
      runCatching { loader.enqueue(NetworkPhotoImageLoader.thumbRequest(application, url)) }
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

  // ── 复制 / 移动 / 批量删除（只有 AList 有写接口）──

  /**
   * 给「选择目标文件夹」弹窗新建子目录。
   *
   * 与 [createFolder] 分开而不复用：那个是「在**当前目录**建，只收一段名字」，
   * 而弹窗里可以下钻到任意一层，路径必须由弹窗拼好（`当前层/名字`）传进来。
   * 重名判据在服务端 —— 这里不做「已存在就换名」，选目录时撞名应当直接报错。
   *
   * @param path 完整相对路径（如 `电影/新片`）
   */
  suspend fun createFolderAt(path: String): Result<Unit> {
    val client =
      writeClient()
        ?: return Result.failure(IllegalStateException("当前协议不支持新建文件夹"))
    val trimmed = path.trim().trim('/')
    if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("名称不能为空"))
    return client.mkdir(trimmed).onSuccess { NetworkDirSizeStore.invalidate(connectionId) }
  }

  /**
   * 给「选择目标文件夹」弹窗列目录（只读，但用 [writeClient]）：
   *
   * 这个弹窗只会在 `supportsWrite` 的协议下出现，用写客户端既能顺带判「还能不能写」，
   * 也省得再取一次读客户端（两者是同一个实例）。
   */
  suspend fun listDirForPicker(path: String): Result<List<NetworkFile>> {
    val client = writeClient() ?: return Result.failure(IllegalStateException("当前协议不支持此操作"))
    return client.listFiles(path)
  }

  /**
   * 把一组条目复制 / 移动到 [dstDir]（单条与批量走同一条路：传一个元素的列表即可）。
   *
   * @param onProgress (已完成, 总数)。⚠️ 从协程里同步回调，UI 侧只更新状态
   * @param onResult 结束回执。**成败必须给准信** —— 写操作作用在服务器上，
   *   静默结束会让用户以为「点了没反应」（与 [renameFile] / [deleteFile] 同一约定）
   */
  fun transferFiles(
    files: List<NetworkFile>,
    dstDir: String,
    mode: NetworkTransferMode,
    onProgress: (done: Int, total: Int) -> Unit,
    onResult: (NetworkOpResult) -> Unit,
  ) {
    if (files.isEmpty()) {
      onResult(NetworkOpResult())
      return
    }
    viewModelScope.launch {
      val client = writeClient()
      if (client == null) {
        onResult(NetworkOpResult(failed = files.size, firstError = "当前协议不支持${mode.verb}"))
        return@launch
      }
      val result =
        runCatching { NetworkFileOps.transfer(client, files, dstDir, mode, onProgress) }
          .getOrElse {
            NetworkOpResult(failed = files.size, firstError = it.message ?: "${mode.verb}失败")
          }
      // 目标目录（以及被搬空的源目录）的统计全部失效：一个连接整体失效最省心，
      // 与 renameFile / deleteFile 同一口径（见 NetworkDirSizeStore.invalidate）
      NetworkDirSizeStore.invalidate(connectionId)
      loadFiles()
      onResult(result)
    }
  }

  /**
   * 批量删除。
   *
   * ⚠️ **不可逆**（AList 没有回收站），UI 必须二次确认后再调。
   */
  fun deleteFiles(
    files: List<NetworkFile>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    onResult: (NetworkOpResult) -> Unit,
  ) {
    if (files.isEmpty()) {
      onResult(NetworkOpResult())
      return
    }
    viewModelScope.launch {
      val client = writeClient()
      if (client == null) {
        onResult(NetworkOpResult(failed = files.size, firstError = "当前协议不支持删除"))
        return@launch
      }
      val result =
        runCatching { NetworkFileOps.delete(client, files, onProgress) }
          .getOrElse { NetworkOpResult(failed = files.size, firstError = it.message ?: "删除失败") }
      NetworkDirSizeStore.invalidate(connectionId)
      loadFiles()
      onResult(result)
    }
  }

  // ── 工具箱（按类型归类 / 提取并整理 / 清空目录 …，只有 AList 可用）──

  private val _toolProgress = MutableStateFlow<ToolProgress?>(null)

  /**
   * 工具箱的实时状态。**挂在 ViewModel 上而不是面板的 `remember` 上** ——
   * 面板可以随时关掉，整理继续跑（几百个文件要几十秒，谁也不愿意盯着面板）。
   */
  val toolProgress: StateFlow<ToolProgress?> = _toolProgress.asStateFlow()

  /**
   * 批量重命名的规则本（命名预设 + 当前选中的规则）。
   *
   * ⚠️ 挂在 ViewModel 上而不是面板的 `remember`：面板关掉再开、换目录、甚至
   * 进程重启后，用户上次编辑的规则照旧 —— 这就是「规则能够记忆」。落盘由
   * [RenameRuleStore] 负责，这里只做 UI 与扫描之间的唯一数据源。
   */
  private val _renameBook = MutableStateFlow(renameRuleStore.book())
  val renameBook: StateFlow<RenameRuleBook> = _renameBook.asStateFlow()

  /** 面板改完规则就调它：先落盘、再推给订阅者，扫描时读到的永远是最新的一份 */
  fun saveRenameBook(book: RenameRuleBook) {
    renameRuleStore.save(book)
    _renameBook.value = book
  }

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
              renameBook = _renameBook.value,
              // 批量重命名用 UI 当前这份列表，保证规则页预览与正式清单同源同序
              renameEntries = files.value,
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
            NetworkToolboxExecutor(client).execute(plan) { phase, done, total, current, failed, lastError ->
              _toolProgress.value =
                ToolProgress(
                  tool = plan.tool,
                  phase = phase,
                  done = done,
                  total = total,
                  current = current,
                  failed = failed,
                  lastError = lastError,
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

  /**
   * 只重跑上一轮失败的动作，已成功的不重来。
   *
   * 重试是重放用户已经确认过的动作，所以不再弹一次确认清单；但进度条会从 0
   * 重新跑到失败项总数，结果页仍列出新的失败明细，可以接着重试。
   * 每次都是新的 Executor 实例 ⇒ 目录名缓存重新拉一遍，不会拿上轮的旧名单判撞名。
   */
  fun retryFailed(onFinished: (String) -> Unit) {
    val current =
      _toolProgress.value ?: run {
        onFinished("没有可重试的记录")
        return
      }
    val actions = current.result?.failedActions.orEmpty()
    if (actions.isEmpty()) {
      onFinished("没有需要重试的失败项")
      return
    }
    runToolbox(
      ToolPlan(
        tool = current.tool,
        root = "",
        actions = actions,
        summary = listOf("重试上一轮失败的 ${actions.size} 项（已成功的不重跑）"),
      ),
      onFinished,
    )
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
        // 封面的「根目录不加载」判据要用（见 atConnectionRoot）
        pageProtocol = connection.protocol
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
            // 进入目录后**立刻**把这一屏可见范围内的封面 / 缩略图预热起来，
            // 而不是等卡片组合出来才一张一张发请求（见 preloadCovers 的说明）。
            // 传排好序的那份：预热取「前 N 个」时顺序要与屏幕上看到的一致。
            preloadCovers(_rawFiles.value)
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
        // 子文件夹的收藏状态同理：一次 SQL 拿全集，长按菜单直接查 Set
        _favoritePaths.value = repository.getFavoritePaths(connectionId)
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
      // 两个状态集同步维护：这一层也可能出现在 [favoritePaths] 里（不冲突，只是对齐）
      _favoritePaths.update { if (added) it + currentPath else it - currentPath }
      onResult(added)
    }
  }

  /**
   * 收藏 / 取消收藏**列表里的某一个子文件夹**（长按菜单入口）。
   *
   * ## 为什么单开一条而不是复用 [toggleFavoriteCurrent]
   * 那条写死了 `currentPath` + `networkPathDisplayName(...)`：前者是「我站的这一层」，
   * 后者是为「单段路径」这个坑兜底。子文件夹的显示名就是 `file.name`（列目录已经给了
   * 真实名字，不需要从路径反推），路径是 `file.path` —— 用同一条函数反而要造假参数。
   *
   * ## 只改内存，不重列
   * 收藏是**本地行为**（`network_favorites` 表），跟服务器无关：改完就地增删
   * [favoritePaths]，长按菜单的星形立刻转过去；主界面「收藏的目录」section 走的是
   * Room Flow，自己会收到失效通知重新查，两边天然一致。
   *
   * @param onResult 切完回调（true = 加上去，false = 取下来），给调用方弹 toast 用
   */
  fun toggleFavoriteFolder(file: NetworkFile, onResult: (added: Boolean) -> Unit = {}) {
    viewModelScope.launch {
      // 收藏的语义只对目录成立；文件传进来直接忽略，不要写进快捷入口
      if (!file.isDirectory) return@launch
      val connection = repository.getConnectionById(connectionId) ?: return@launch
      val added = repository.toggleFavorite(connection, file.path, file.name)
      _favoritePaths.update { if (added) it + file.path else it - file.path }
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
   * @param onFinished 本函数**收工**时回调（成功拉起播放器 / 提前返回 / 出错都算），
   *   用来摘掉界面上的「正在打开」加载层。⚠️ 不提供它的话，调用方只能靠超时猜，
   *   猜短了 = 加载动画先消失、播放器还没出来（用户看到一段死寂），
   *   猜长了 = 起播失败时留一个转不停的圈。所以必须由这里给准信。
   *
   * 队列经 intent extras 传给播放器：`playlist`（Uri 表）+ `playlist_titles` +
   * `playlist_index`。⚠️ 另外传 `network_file_paths`（与 playlist 同序的原始网络
   * 路径表）—— 播放器靠它按当前下标算每片的进度键 / 找字幕，否则连播换片后
   * 进度全串到第一片头上（PlayerActivity.networkFilePaths 的注释有详述）。
   *
   * ⚠️ 这里的 `uriFor` 是**逐条串行**的。所以它必须走零请求的富签名快路
   * （`NetworkVideoUriBuilder` → `AlistClient.directUriOrNull`），否则 N 条视频
   * 就是 N 次网络往返，用户看到的是「点了十几秒后才弹播放器」。
   */
  fun playVideos(
    videos: List<NetworkFile>,
    startIndex: Int,
    onFinished: () -> Unit = {},
  ) {
    if (videos.isEmpty() || startIndex !in videos.indices) {
      onFinished()
      return
    }
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
      } finally {
        // 成功拉起 / 失败 / 被取消，三路都放行 —— 界面的加载层只认这一个信号
        onFinished()
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
   * @param onPartial 抽不满时回调（实际条数, 目标条数, 已扫目录数, 是否被时间预算掐停）。
   *   穿透模式有 6 秒墙钟预算（见 `NetworkFeedLauncher.SCAN_TIME_BUDGET_MS`），视频
   *   散在几百个文件夹里时必然少给 —— 必须说出来，否则用户以为抽片坏了
   * @param onEmpty 一条都没抽到时回调 —— 调用方据此给 toast。递归模式下调用方
   *   事先不知道有多少视频，只能**等抽完**才知道，所以结果必须回传而不是让它静默返回。
   */
  fun playRandomVideos(
    drillDown: Boolean = false,
    onPartial: (found: Int, want: Int, scannedDirs: Int, timedOut: Boolean) -> Unit =
      { _, _, _, _ -> },
    onEmpty: () -> Unit = {},
  ) {
    viewModelScope.launch {
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val pool: List<NetworkFile>
      if (drillDown) {
        val connection = repository.getConnectionById(connectionId) ?: return@launch
        val feed = NetworkFeedLauncher.collectVideos(
          repository = repository,
          connection = connection,
          startPath = currentPath,
          want = limit,
          drillDown = true,
        )
        if (feed.videos.size < limit) {
          onPartial(feed.videos.size, limit, feed.scannedDirs, feed.timedOut)
        }
        pool = feed.videos
      } else {
        pool = _rawFiles.value.filter { it.isVideoFile() }
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
   * @param onPartial 抽不满时回调（实际条数, 目标条数, 已扫目录数, 是否被时间预算掐停）
   *   —— 口径同 [playRandomVideos]：静默少给会被当成 bug
   * @param onEmpty 抽不到视频时回调，调用方负责 toast
   */
  fun startFeedPlayback(
    drillDown: Boolean = false,
    onPartial: (found: Int, want: Int, scannedDirs: Int, timedOut: Boolean) -> Unit =
      { _, _, _, _ -> },
    onEmpty: () -> Unit = {},
  ) {
    viewModelScope.launch {
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val connection = repository.getConnectionById(connectionId)
      if (connection == null) {
        onEmpty()
        return@launch
      }
      val feed = NetworkFeedLauncher.collectVideos(
        repository = repository,
        connection = connection,
        startPath = currentPath,
        want = limit,
        drillDown = drillDown,
      )
      if (feed.videos.isNotEmpty() && feed.videos.size < limit) {
        onPartial(feed.videos.size, limit, feed.scannedDirs, feed.timedOut)
      }
      val started = NetworkFeedLauncher.launch(
        context = application,
        application = application,
        repository = repository,
        connection = connection,
        videos = feed.videos,
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
     * 封面穿透的最大**层级**（根目录 = 0）。
     *
     * 3 层的理由是常见媒体树是「库 → 类型 → 剧名 → 季」四级：库的卡片往下钻三层
     * 正好能碰到「季」里的集（集自带服务端海报）。再深就属于异常结构，收益远小于
     * 请求成本。
     *
     * ⚠️ 到限即停，且**允许写负缓存**：深度上限是确定性策略，同一目录每次解析
     * 结果一致，不存在「这次没查到、下次能查到」。
     */
    private const val COVER_SCAN_MAX_DEPTH = 3

    /**
     * 一次封面解析最多列几次目录（根目录算 1 次）。
     *
     * 目录树可能很宽（一个类型下有上百个剧名），没有上限就是「一张卡片列上百次」。
     * 6 次的容量：根 + 5 个分支。超了按「结论不完整」处理（不落盘、本页计次），
     * 宁可留一个灰图标给下一次，也不把「没查完」冻成 12 小时的「没有封面」。
     */
    private const val COVER_SCAN_MAX_LISTINGS = 6

    /**
     * 进入目录后**预热**多少个子文件夹的封面。
     *
     * 12 ≈ 一屏（3 列 × 4 行）加上 Compose 预取的余量。再多就是拿请求换「可能滚不到
     * 的地方」，一个宽目录（几百个子文件夹）全预热会把会话和流量都吃掉。
     */
    private const val PRELOAD_FOLDER_LIMIT = 12

    /**
     * 进入目录后预热多少张媒体缩略图（只算带服务端 `thumb` 的条目）。
     *
     * 24 = 两屏的量：第一屏立刻可见，第二屏大概率是下一步滑动的地方。缩略图本身很小
     * （服务端已压过），但仍要封顶 —— 这是猜测性流量，不是用户明确要的。
     */
    private const val PRELOAD_THUMB_LIMIT = 24

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
