package app.marlboroadvance.mpvex.domain.emby

// ════════════════════════════════════════════════════════════════════════
// FolderLatestAddedLoader —— 把「按文件夹内最新入库时间排序」落地成请求
// ════════════════════════════════════════════════════════════════════════
//
// 分成两层是刻意的：
//  · [FolderLatestAdded] 是**纯逻辑**（路径归因、排序规则），不碰网络；
//  · 本文件只负责「怎么问服务端、什么时候停、结果存哪儿」。
//
// 这样纯逻辑能进 JVM 单测（本机跑不了 androidUnitTest），而网络部分
// 只在真正的页面里跑，出问题一眼能看出是哪一层。

import android.content.Context
import app.marlboroadvance.mpvex.domain.emby.isFolderLikeForScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 拉取策略。
 *
 * 常量都是**上限**，不是目标：正常库里第一页就够（该层的新片全在第一页），
 * 只有「某个目录彻底没有新片、它最新的片在三年五年前」时才会一直翻下去。
 */
internal object FolderLatestPolicy {
  /** 每页条数。Emby 对上百级的 Limit 都认，200 是官方客户端常用值 */
  const val PAGE_SIZE = 200

  /**
   * 最多翻多少页。
   *
   * 200 × 40 = 8000 条。这个库量级下，没算出时间的目录基本可以认为
   * 里面没有能被这一层认领的媒体（或它们早就排到八千条以后）。
   * 再往上翻的收益极低，而每一页都是一次可能几百毫秒的请求 ——
   * 宁可给一个「部分结果」也不要把用户卡在加载态上。
   */
  const val MAX_PAGES = 40

  /**
   * 连续多少页「一条新目录都没填上」就收手。
   *
   * 这是比 [MAX_PAGES] 更常用的一条：库里有几个早就停更的老目录，
   * 它们的片排在很后面，翻到那儿之前会有几十页对结论毫无贡献。
   * 连续两页都没贡献，说明剩下那些目录在可预见的范围内也不会有 —— 停。
   */
  const val MAX_IDLE_PAGES = 2

  /**
   * 同时计算多少个目录。
   *
   * 为什么需要并发：一次对**整个库**做递归查询，库有几千条时首屏要等好几秒，
   * 而且服务端要遍历全部条目；改成「每个一级子目录各查一次」之后，单个请求的
   * 结果集小得多、能走服务端的目录索引，整体反而更快，但串行发 N 个请求又会
   * 把时间累加起来。4 路并发是与聚合检索（`Semaphore(4)`）一致的取值，
   * 对家用 Emby 的并发承受能力比较友好。
   */
  const val CONCURRENCY = 4

  /**
   * 最多往下钻几层。
   *
   * 第一层（库根的直接子目录）是必须的；再往下钻是为了「一级目录下面只是
   * 一堆子目录、真正的片在第二层」这种常见布局（`电影/2024/…`）。
   * 两层通常足够，再多收益随深度快速衰减，而请求数是指数增长的。
   */
  const val MAX_DEPTH = 3
}

/**
 * 算一层目录的「最新入库时间」顺序。
 *
 * ## 为什么要分目录去查（v344 重构）
 * 旧实现对整个库发**一条**递归查询。问题有三：
 *  1. 首屏延迟与库大小线性相关 —— 几千条时用户要干等好几秒；
 *  2. 只能靠「连续两页没贡献」猜着停，容易在真正的老目录上翻空几十页；
 *  3. 结果只能一次性给出，中途没有任何反馈。
 * 现在改成按一级子目录拆开、各自有限深度递归，并**边算边回调**
 * （[onPartial]），用户能立刻看到顺序开始变化。
 *
 * ## 归因仍然以 Path 为准
 * 每个子任务只负责「这个一级子目录内部」，命中即记在它名下 —— 不需要再猜
 * 更深层归属（那是 [matchChildFolder] 在扁平结果集里干的事）。但**同一个子目录
 * 内部**仍取最新一条，与扁平版本语义一致。
 *
 * 供页面在后台协程里调用。所有异常都**不往外抛**：
 * 拿不到就返回 null，调用方退回「原始顺序」—— 排序失败不该让整页打不开。
 *
 * @param parentId 当前这一层的目录 Id（库 Id 或文件夹 Id）
 * @param children 这一层的直接子目录（按当前网格里的原顺序）
 * @param includeTypes 递归查询时要纳入的**媒体类型**。必须与用户当前视图
 *   「能看到的媒体」一致，否则「文件夹里有片、却算不出时间」。
 * @param onPartial 每算完一批子目录就回调一次「目前已知的 目录Id → 最新时间」。
 *   在调用方的协程上下文里执行（页面里就是主线程），可以直接更新 UI 状态。
 *   可能被调用多次；最终结论仍以返回值为准。
 * @return 顺序结果；一页都没拉到（网络错 / 服务端 500）时返回 null
 */
internal suspend fun computeFolderLatestOrder(
  parentId: String,
  children: List<EmbyItem>,
  includeTypes: List<String>,
  onPartial: suspend (Map<String, String>) -> Unit = {},
  fetchPage: suspend (
    parentId: String,
    startIndex: Int,
    limit: Int,
    includeTypes: List<String>,
  ) -> EmbyItemsResult,
): FolderLatestOrder? {
  if (children.isEmpty()) {
    return FolderLatestOrder(emptyList(), emptyMap(), complete = true)
  }
  val childIdsInOriginalOrder = children.mapNotNull { it.Id?.takeIf { id -> id.isNotBlank() } }
  if (childIdsInOriginalOrder.isEmpty()) {
    // 一条 Id 都没有：没法排，也算不出东西。返回空顺序让调用方原样显示
    return null
  }
  val childPathById = children
    .mapNotNull { item ->
      val id = item.Id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val path = normalizePath(item.Path) ?: return@mapNotNull null
      id to path
    }
    .toMap()

  val scan = FolderLatestScan(childIdsInOriginalOrder, childPathById)
  val mergedMutex = Mutex()
  val gate = Semaphore(FolderLatestPolicy.CONCURRENCY)
  /** 已算出的「一级子目录 → 最新时间」，并发写入受 [mergedMutex] 保护 */
  val latestById = LinkedHashMap<String, String>()
  var gotAnyPage = false

  /**
   * 递归算 [folderId] 这个目录（最多下钻到 [FolderLatestPolicy.MAX_DEPTH] 层）
   * 里最新的 media 时间。
   *
   * @return 这个目录下能找到的最新 `DateCreated`，找不到返回 null
   */
  suspend fun scanFolder(
    folderId: String,
    depth: Int,
  ): String? {
    var newest: String? = null
    var startIndex = 0
    var page = 0
    var idlePages = 0
    while (page < FolderLatestPolicy.MAX_PAGES) {
      val result = gate.withPermit {
        runCatching { fetchPage(folderId, startIndex, FolderLatestPolicy.PAGE_SIZE, includeTypes) }
          .getOrNull()
      } ?: break
      mergedMutex.withLock { gotAnyPage = true }
      val items = result.Items
      if (items.isEmpty()) break

      var contributed = false
      for (item in items) {
        // 目录类条目：它自己的 DateCreated 不代表「里面有新东西」
        // （见 FOLDER_LATEST_CONTAINER_TYPES）。深度还有余量时继续往下钻 ——
        // `电影/2024/…` 这类「一级目录只有子目录」的布局靠这一步才算出时间。
        if (isFolderLikeForScan(item)) {
          val childId = item.Id?.takeIf { it.isNotBlank() }
          if (childId != null && depth < FolderLatestPolicy.MAX_DEPTH) {
            val deep = scanFolder(childId, depth + 1)
            if (deep != null && (newest == null || deep > newest!!)) {
              newest = deep
              contributed = true
            }
          }
          continue
        }
        val created = item.DateCreated?.takeIf { it.isNotBlank() } ?: continue
        val current = newest
        if (current == null || created > current) {
          newest = created
          contributed = true
        }
      }
      idlePages = if (contributed) 0 else idlePages + 1

      startIndex += items.size
      if (items.size < FolderLatestPolicy.PAGE_SIZE) break
      if (result.TotalRecordCount in 1..startIndex) break
      // 已经有结果、且连续两页没有更晚的贡献 ⇒ 后面不会更新了
      if (newest != null && idlePages >= FolderLatestPolicy.MAX_IDLE_PAGES) break
      page++
    }
    return newest
  }

  // 一级子目录并发处理；每算完一个就并进合并表并回调，
  // 让调用方可以「边算边显示」而不是等全部算完。
  coroutineScope {
    childIdsInOriginalOrder.map { childId ->
      async {
        val newest = scanFolder(childId, depth = 1) ?: return@async
        val snapshot =
          mergedMutex.withLock {
            scan.record(childId, newest)
            latestById[childId] = newest
            HashMap(latestById)
          }
        // 回调在父协程的上下文里跑（页面里即主线程），可直接更新 UI 状态
        onPartial(snapshot)
      }
    }.awaitAll()
  }

  if (!gotAnyPage) return null
  // 最终顺序仍由 scan 产出：它负责「有时间的在前、空目录垫后、同时间保原序」
  return scan.toOrder()
}

/**
 * 进程内缓存：一层目录一份排序结论。
 *
 * ## 为什么必须缓存
 * 每次进一个文件夹都重算一遍，等于每次都要重新翻一遍库。用户「点进文件夹
 * → 返回 → 再点进另一个文件夹」时，这些结论完全没变，没有任何理由重算。
 *
 * ## key 的口径
 * `serverId | userId | parentId`。三个都必要：
 *  · serverId / userId —— 同一台服务器上两个人的「入库时间」其实一样，
 *    但可见范围不同（隐私库），结论可能不同，不能互相复用；
 *  · parentId —— 不同目录是不同的问题。
 * **sortBy 不进 key**：缓存存的是「目录 → 最新时间」这个事实，与排序方式无关；
 * 换排序方式只需要重新排一次 id 列表（O(n log n)），不用重新联网。
 *
 * ## 失效
 * 有 TTL（[TTL_MS]），过期后下次进入会重算；用户下拉刷新时走 `force` 重算。
 * 不做「库变更通知」那套：Emby 的 WebSocket 在兼容层上未必有，靠它会让
 * 缓存永远不刷新 —— 定时过期是唯一在所有服务端上都成立的方案。
 *
 * ## 已知取舍：TTL 内新增的目录
 * 结论里只记着「当时那一层的目录」。半小时内用户新建了一个目录，它会因为
 * 不在 `orderedIds` 里而**排在最后**（[applyFolderLatestOrder] 的兜底），
 * 而不是按它实际的最新媒体时间排。这是刻意接受的：为了避免这一点去每半小时
 * 外都重新联网，代价远大于收益；下拉刷新一次即可纠正。
 */
internal object FolderLatestCache {
  /** 结论的有效期。半小时：够撑过「逛一圈再回来」，又不至于让新片迟迟不冒头 */
  const val TTL_MS = 30 * 60 * 1000L

  private class Entry(
    val order: FolderLatestOrder,
    val at: Long,
  )

  private const val MAX_ENTRIES = 24
  private val entries = LinkedHashMap<String, Entry>()

  /**
   * 保护 [entries] 的锁。
   *
   * ⚠️ 用普通的 `synchronized` 而不是 `Mutex`：这里有两类访问者 ——
   *    挂起函数（`get`/`put`）与非挂起的 [peek]。若挂起路径用 `Mutex`、
   *    peek 用别的锁，两者不互斥，`LinkedHashMap` 会被并发改结构（可能死循环）。
   *    这些临界区极短（只做几次 map 操作），持锁期间不做 IO，所以用同步锁
   *    不会阻塞调度器；真正的磁盘读写都在锁外进行。
   */
  private val lock = Any()

  /** 磁盘落地目录。null = 未初始化（线程内存模式，单测与纯 JVM 场景仍可用）。 */
  @Volatile
  private var diskDir: File? = null

  @Volatile
  private var loadedFromDisk = false

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * 绑定磁盘目录并读取上次进程留下的结论（v352 预解析）。
   *
   * 为什么必须落盘：缓存原本只在内存里，App 被杀掉后重进，记忆仍然停在
   * 「内容更新时间」这一档，于是每次冷启动都得重算一遍 —— 用户看到的就是
   * 「进入媒体库先干等重组」。落盘后冷启动直接命中，网格一次成形。
   *
   * 幂等：重复调用只做一次读盘。读盘失败一律当作「没有缓存」，不影响排序可用性。
   */
  suspend fun attachDir(context: Context) {
    if (loadedFromDisk) return
    val dir = File(context.cacheDir, "folder_latest")
    diskDir = dir
    val restored = withContext(Dispatchers.IO) {
      runCatching {
        if (!dir.isDirectory) return@runCatching emptyList<Pair<String, Entry>>()
        dir.listFiles()
          .orEmpty()
          .filter { it.isFile && it.name.endsWith(".json") }
          .mapNotNull { file ->
            runCatching {
              val stored = json.decodeFromString<StoredOrder>(file.readText())
              // 超出 TTL 的直接丢掉：那台服务器上的内容很可能已经变了
              if (System.currentTimeMillis() - stored.at > TTL_MS) {
                file.delete()
                null
              } else {
                stored.key to Entry(stored.toOrder(), stored.at)
              }
            }.getOrNull()
          }
      }.getOrDefault(emptyList())
    }
    synchronized(lock) {
      if (!loadedFromDisk) {
        restored.forEach { (key, entry) -> if (!entries.containsKey(key)) entries[key] = entry }
        loadedFromDisk = true
      }
    }
  }

  fun key(
    serverId: Long,
    userId: String?,
    parentId: String,
  ): String = "$serverId|${userId.orEmpty()}|$parentId"

  suspend fun get(
    key: String,
    now: Long = System.currentTimeMillis(),
  ): FolderLatestOrder? = synchronized(lock) {
    val entry = entries[key] ?: return null
    if (now - entry.at > TTL_MS) {
      entries.remove(key)
      return null
    }
    // 命中的挪到末尾（LRU）：LinkedHashMap 的插入序就是淘汰序
    entries.remove(key)
    entries[key] = entry
    entry.order
  }

  /**
   * 同步只读（v354）：拿一份**当前内存里**仍新鲜的结论，供 Composable 初始化状态用。
   *
   * 为什么需要它：`get` 是 suspend 的，不能在 `remember { ... }` 里调用，于是
   * 页面状态的初始值只能是 null —— 必须先按原顺序渲染一帧，等 effect 跑完才换成
   * 排好的顺序。这种「先乱后好」在**从子目录返回**时尤其刺眼：返回会把上一层
   * 的 Composable 销毁重建，`remember` 状态随之丢失，于是用户明明什么都没改，
   * 却也看见列表重组了一次。
   *
   * 换成本方法做初始值后，重建的那一帧就已经是缓存里的顺序，全程零重排。
   *
   * ⚠️ 刻意**不做** LRU 重排、不删除过期项（那需要写操作，得持锁且可能要删盘）：
   *    这里只读，过期的按「没有」返回，真正的清理仍由 [get] 负责。
   */
  fun peek(
    key: String,
    now: Long = System.currentTimeMillis(),
  ): FolderLatestOrder? = synchronized(lock) {
    val entry = entries[key] ?: return null
    if (now - entry.at > TTL_MS) return null
    entry.order
  }

  suspend fun put(
    key: String,
    order: FolderLatestOrder,
    now: Long = System.currentTimeMillis(),
  ) {
    val evicted = ArrayList<Pair<String, Entry>>()
    synchronized(lock) {
      entries.remove(key)
      entries[key] = Entry(order, now)
      while (entries.size > MAX_ENTRIES) {
        val oldest = entries.keys.firstOrNull() ?: break
        entries.remove(oldest)?.let { evicted += oldest to it }
      }
    }
    // 内存里被淘汰的，磁盘上也要删掉，否则盘面文件无界增长
    if (evicted.isNotEmpty()) {
      withContext(Dispatchers.IO) {
        evicted.forEach { (oldKey, _) -> runCatching { fileOf(oldKey)?.delete() } }
      }
    }
    withContext(Dispatchers.IO) { persist(key, order, now) }
  }

  /** 整片作废（切服务器 / 用户手动刷新全部时用） */
  suspend fun clear() {
    synchronized(lock) { entries.clear() }
    withContext(Dispatchers.IO) {
      runCatching { diskDir?.listFiles()?.forEach { it.delete() } }
    }
  }

  /**
   * 只作废其中一条。
   *
   * 下拉刷新时用：用户要的是「这一个目录重新算」，不该顺手把别的目录
   * （以及别的库）的结论一起丢掉 —— 那些结论一点没变，重算纯属浪费请求。
   */
  suspend fun invalidate(key: String) {
    synchronized(lock) { entries.remove(key) }
    withContext(Dispatchers.IO) { runCatching { fileOf(key)?.delete() } }
  }

  // ── 磁盘落地 ──────────────────────────────────────────────────────────

  /**
   * 结论 → 磁盘。只存 [orderedIds] + [newestByFolderId]：
   * `complete` 是可重建的派生量（有时间的目录数 == orderedIds 里在 map 里的个数），
   * 少存一个字段，就少一处可能与事实不一致的地方。
   */
  private fun persist(
    key: String,
    order: FolderLatestOrder,
    at: Long,
  ) {
    val dir = diskDir ?: return
    runCatching {
      if (!dir.isDirectory && !dir.mkdirs()) return
      val payload = StoredOrder(
        key = key,
        at = at,
        orderedIds = order.orderedIds,
        newestByFolderId = order.newestByFolderId,
      )
      // 临时文件 + rename：写到一半被杀掉也不会留下半截 JSON 让下次解析失败
      val target = File(dir, fileNameOf(key))
      val tmp = File(dir, "${fileNameOf(key)}.tmp")
      tmp.writeText(json.encodeToString(payload))
      if (!tmp.renameTo(target)) {
        target.writeText(tmp.readText())
        tmp.delete()
      }
    }
  }

  private fun fileOf(key: String): File? = diskDir?.let { File(it, fileNameOf(key)) }

  /** key 里含 `|` 和 `/` 等路径敏感字符，统一换成 `_`；解析不依赖文件名，只依赖内容。 */
  private fun fileNameOf(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"

  @Serializable
  private class StoredOrder(
    val key: String,
    val at: Long,
    val orderedIds: List<String>,
    val newestByFolderId: Map<String, String>,
  ) {
    fun toOrder(): FolderLatestOrder = FolderLatestOrder(
      orderedIds = orderedIds,
      newestByFolderId = newestByFolderId,
      // 从磁盘读回来的都是「已跑完一轮」的结论（只有最终结论才写盘），
      // 是否真的全覆盖由数据本身决定：有时间目录数 == 已排目录数即视为完整。
      complete = orderedIds.size == newestByFolderId.size,
    )
  }
}

/**
 * 预解析（v352）：在用户「切到内容更新时间档」之前，就把这一层目录的顺序算好。
 *
 * ## 为什么需要
 * v350 的遮罩只是把「跳到底部 → 重组 → 回顶」藏起来，用户仍然要干等几秒。
 * 用户真正想要的是「点下去就该是排好的」。既然这一档的记忆使很多用户会反复
 * 进入同样的库，那就把计算提前到**用户还没点之前**：
 *  · 冷启动读盘（[FolderLatestCache.attachDir]）—— 上次算的结论直接可用；
 *  · 进库后先拉这一层的子目录清单，先看缓存；没缓存就在后台算一份存起来。
 *
 * ## 边界（不做的事）
 *  · **不做全库扫描**：只预热「当前这一层的直接子目录」，与进库后真正要显示
 *    的那一层完全一致。多预热一层等于替用户做了一次他可能根本不去的请求。
 *  · **不与用户操作抢资源**：预热降级为 1 路串行（正常计算是 4 路并发），
 *    且同样受 [FolderLatestPolicy.MAX_PAGES] / [MAX_IDLE_PAGES] 约束。
 *  · **失败静默**：预热是纯优化，任何异常都不上报、不提示、不影响页面。
 *  · **同一 key 正在预热时不重复发**：页面自己开始真算时会接管。
 */
internal object FolderLatestPrewarm {
  /** 正在预热中的 key，避免「进库一次、列表变一次」重复发同一份请求。 */
  private val running = HashSet<String>()
  private val mutex = Mutex()

  /**
   * 判断这一层要不要预热：缓存里已有新鲜结论就不用。
   *
   * 单独抽出来，是为了让调用方能在**发起重量级网络请求之前**先廉价地判一次，
   * 避免每次都白拉起一个协程。
   */
  suspend fun isNeeded(key: String): Boolean =
    FolderLatestCache.get(key) == null && mutex.withLock { key !in running }

  /**
   * 在后台把这一层的顺序算好写进缓存。
   *
   * @param fetchChildren 拉这一层直接子目录（与页面里 `loadItemsResult` 同一口径）
   * @param fetchPage 分页递归查询（与页面里传给 `computeFolderLatestOrder` 的一致）
   */
  suspend fun warm(
    parentId: String,
    key: String,
    includeTypes: List<String>,
    children: suspend () -> List<EmbyItem>,
    fetchPage: suspend (
      parentId: String,
      startIndex: Int,
      limit: Int,
      includeTypes: List<String>,
    ) -> EmbyItemsResult,
  ) {
    if (!isNeeded(key)) return
    mutex.withLock {
      if (key in running) return
      running += key
    }
    try {
      val folders = runCatching { children() }.getOrNull().orEmpty().filter { isFolderLikeForScan(it) }
      if (folders.isEmpty()) return
      // onPartial 传空：预热期间没有 UI 要刷新，逐层回调节省的是内存与线程切换，
      // 唯一有价值的产出是最终结论。
      val order = computeFolderLatestOrder(
        parentId = parentId,
        children = folders,
        includeTypes = includeTypes,
        onPartial = {},
        fetchPage = fetchPage,
      ) ?: return
      // 并发里可能用户已经自己算完并存了一份（更新），不覆盖更晚的那份
      if (FolderLatestCache.get(key) == null) FolderLatestCache.put(key, order)
    } catch (_: Throwable) {
      // 预热失败静默：下次进这一档，页面自己会按正常路径算
    } finally {
      mutex.withLock { running -= key }
    }
  }
}
