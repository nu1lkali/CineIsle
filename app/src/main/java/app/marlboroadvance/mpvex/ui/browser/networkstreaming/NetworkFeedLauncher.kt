package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import app.marlboroadvance.mpvex.ui.player.feed.FeedItem
import app.marlboroadvance.mpvex.ui.player.feed.VerticalFeedActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 网络存储的「视界流」链路：抽片 → 算播放地址 → 交给 [VerticalFeedActivity]。
 *
 * ## 为什么不塞进 [NetworkBrowserViewModel]
 * 入口有两个，且都不在浏览页里：
 * - 网络首页的**连接卡**（经典 = 卡片 ⋮ 菜单，新 UI = 卡片上的图标）；
 * - 目录浏览页的 **⋮ 更多菜单**（两个 UI 共用同一页）。
 *
 * 连接卡那边根本没有 [NetworkBrowserViewModel]（它按目录建实例），所以这段逻辑
 * 只能做成**不依赖页面状态**的独立入口，三处调用同一个函数。
 *
 * ## 取片范围
 * - `drillDown = false`：只取当前目录（与「随机播放视频」同口径，即时）；
 * - `drillDown = true`：从起始目录**逐层下钻**（广度优先）累积视频，直到凑够
 *   [want] 条或花完目录预算（见 [dirBudget]）。连接根目录通常一个视频都没有，
 *   不递归的话从卡片点进去永远是「没有视频」。
 *
 * ⭐ 目录预算与「抽不满」的诚实交代见 [collectVideos] 的 KDoc —— 那是 v222 之前
 * 「开 500 只抽到 30 / 41」的根因所在（池子被 `MAX_POOL = 300` 钉死，
 * 下钻又被 `MAX_SCAN_DIRS = 40` 钉死，两个上限都比 `want` 小）。
 *
 * 同一个下钻器 [walk] 也供**随机播放图片**使用（见 [collectPhotoGroups]）：
 * 视频要「凑够数量就停」，图片要「每一级都收全」，差别只在 visit 的返回条件。
 * 分成两份实现的话，将来改扫描深度 / 错误处理必然只改一处。
 *
 * ## 数量
 * 统一读设置项 `randomPlayCount`（与「随机播放 / 随机播放收藏 / Emby 视界流」同一个），
 * 调用方传进来，这里不做二次兜底 —— 一个数字只能有一个出处。
 */
object NetworkFeedLauncher {
  /**
   * 递归抽**图片**时最多探多少个目录。
   *
   * 图片穿透是「这一级有几张收几张」，探到 [MAX_PHOTO_QUEUE] 就停，不追某个目标
   * 数量，所以按固定目录数封顶：NAS 上一个库通常是 `剧名/季/集` 三层，40 个目录
   * 足以覆盖一屏之外的十几个剧集；再往上加，用户点完要盯着 toast 等十几秒。
   *
   * ⚠️ 视频**不适用**这个口径（视频有明确的 `want`，预算要跟它对上），见 [dirBudget]。
   */
  const val MAX_SCAN_DIRS = 40

  /** 视频抽片时的目录预算下限：小请求（want = 10）也别比旧版探得更浅 */
  private const val MIN_SCAN_DIRS = 40

  /**
   * 每抽 1 个视频，预留几个目录的扫描预算。
   *
   * 定 3：一个目录通常有好几条视频（乐观），但「视频散在很多很多文件夹里」的库
   * 里平均只有一两条（悲观）。按 1:3 预留 —— 乐观时根本到不了上限（`visit` 一
   * 凑够就停），悲观时也能拿到三分之一的量，剩下的由「抽不满就告诉用户」兜住。
   */
  private const val DIRS_PER_WANTED_VIDEO = 3

  /**
   * 目录预算上限（**可以并发列目录**的协议，见 [canScanInParallel]）。
   *
   * 600 个目录 × 平均 80ms ÷ [SCAN_CONCURRENCY](4) ≈ 12 秒 —— 这已经是「点完
   * 愿意等」的上限，再往上用户会以为卡死了。
   */
  private const val MAX_SCAN_DIRS_PARALLEL = 600

  /**
   * 目录预算上限（**只能串行**的协议：FTP / SMB）。
   *
   * ⚠️ FTP 绝对不能并发：[FtpClient] 整个连接共用一个 `FTPClient` 实例，两条
   * 命令交叉写进同一条控制连接就是协议流错乱（会把别人的目录列回来，甚至整场
   * 会话卡死）。SMB 同理保守处理。串行 160 × 80ms ≈ 13 秒，与上面并发上限是
   * 同一个「愿意等」的口径。
   */
  private const val MAX_SCAN_DIRS_SERIAL = 160

  /** 同时最多几个目录「正在列」。只对 [canScanInParallel] 的协议生效 */
  private const val SCAN_CONCURRENCY = 4

  /**
   * ⏱ 一次抽片的**墙钟预算**：超过就用手上已有的视频开播。
   *
   * 这是「抽得更多」与「别等太久」之间真正的闸门 —— 目录数上限只是**预算**，
   * 换算成时间要乘上每次往返的耗时，而那个耗时完全不可控（同一个 AList，
   * 本地 NVMe 上 20ms、挂个 115 网盘可能 400ms ⇒ 600 个目录从 3 秒到 60 秒）。
   * 所以上限必须按**时间**给，不能只按目录数给：
   * - 6 秒 ≈ 点完「视界流播放」后愿意盯着看的上限；配合进度 toast 还能再忍一点；
   * - 视频真的散在几百个文件夹里时，6 秒只能抽到几十条 —— 那就**播这几十条**
   *   并把「只找到 N 个」说清楚，而不是让用户干等两分钟凑满 500。
   *
   * ⚠️ 第一片（起始目录）无论如何都扫：连「这棵树里到底有没有视频」都答不上来
   *    就直接弹「没有视频」是最糟的失败方式。
   */
  private const val SCAN_TIME_BUDGET_MS = 6_000L

  /**
   * 下钻时的**抽签池**倍数：池子 = want × 这个倍数（上限 [MAX_POOL]）。
   *
   * ⚠️ 这条关系里只有「池子 ≥ want」是硬要求，倍数本身是取舍：
   * 池子越大候选越均匀，但每多一条都要多探目录。定 2 而不是 4 —— 抽 500 时
   * 4 倍要求收 2000 条候选，等于把目录预算全烧在「多余的花样」上。
   */
  private const val POOL_FACTOR = 2

  /**
   * 抽签池硬上限。
   *
   * ⚠️ 它曾经是 `300`，而 `want` 能拉到 500 ⇒ 「开 500 个，池子却只有 300」，
   * 不管扫多少目录都不可能抽满（用户反馈「这次 30 那次 41」的一半原因在这里，
   * 另一半是目录数被钉在 40）。现在它只用来挡住病态内存占用，必须 ≥ want 上限。
   */
  private const val MAX_POOL = 1200

  /**
   * 穿透子文件夹收集图片时，队列最多装多少张。
   *
   * 与视频**刻意不同**：视频按设置项 `randomPlayCount` 随机截断，图片在穿透模式下
   * **不抽、不截断** —— 用户的要求是「穿透到的那个目录的图片（仅那一级）都加到队列去」，
   * 该级有几张就收几张。但队列仍需要一条总上限：一个四层的相册树攒出几万条元数据，
   * 幻灯片看到天亮也看不完，还会拖慢查看器首帧。定 2000 张 ≈ 连看半小时以上。
   */
  const val MAX_PHOTO_QUEUE = 2000

  /**
   * 收集一批可播视频（未洗牌、未截断）。
   *
   * ## 数量账（v222 重做）
   * 旧实现有两道**都比 `want` 小**的硬上限，抽 500 时注定抽不满：
   * ```
   * poolCap  = min(want × 4, 300)   // want = 500 ⇒ 池子只有 300
   * maxDirs  = 40                   // 视频散在几百个文件夹里 ⇒ 只够探到 30~41 条
   * ```
   * 而且 `MAX_SCAN_DIRS` 是固定值，跟 `want` 无关 —— 要 10 条和要 500 条扫一样深。
   * 现在两条都改成「跟 want 挂钩」：
   * - 池子 = `want × 2`（上限 [MAX_POOL]，≥ want 上限，永远够）；
   * - 目录预算 = `want × 3`（[MIN_SCAN_DIRS] ~ [dirBudget] 的协议上限），
   *   并且对 HTTP 协议**并发列目录**（[SCAN_CONCURRENCY]）把这笔预算的时间压回来。
   *
   * ## 抽不满怎么办
   * 树里真的没那么多视频时，任何算法都变不出来 —— 所以结果带上实际扫描的目录数
   * （[FeedPool.scannedDirs]），由调用方明说「只找到 N 个（已扫 M 个文件夹）」。
   * 静默给 41 条才是 bug，41 条本身不是。
   *
   * @param startPath 起始目录（连接卡传 `/`）
   * @param want 目标条数（= 设置项里的随机播放数量）
   * @param drillDown 是否穿透子文件夹
   * @param onProgress 扫描进度回调（已扫目录数, 已收视频数）。在调用方的调度器上回调，
   *   只用来更新提示文案，别在里面做重活
   */
  suspend fun collectVideos(
    repository: NetworkRepository,
    connection: NetworkConnection,
    startPath: String,
    want: Int,
    drillDown: Boolean,
    onProgress: (scannedDirs: Int, found: Int) -> Unit = { _, _ -> },
  ): FeedPool {
    if (want <= 0) return FeedPool(emptyList(), 0, timedOut = false)
    // ⚠️ 抽签池必须比 want 大（否则「穿透」等于没开：洗牌只能洗已经收进来的这批，
    //    看着随机、其实被目录顺序钉死），但也**必须 ≥ want** —— 这条是硬要求。
    val poolCap = if (drillDown) (want * POOL_FACTOR).coerceIn(want, MAX_POOL) else Int.MAX_VALUE
    val found = ArrayList<NetworkFile>(minOf(poolCap, 256))
    val scan = walk(
      repository = repository,
      connection = connection,
      startPath = startPath,
      drillDown = drillDown,
      maxDirs = if (drillDown) dirBudget(want, connection.protocol) else 1,
      // 穿透才可能扫很久；只播当前目录时时间预算是多余的
      maxMillis = if (drillDown) SCAN_TIME_BUDGET_MS else 0L,
      concurrency =
        if (drillDown && canScanInParallel(connection.protocol)) SCAN_CONCURRENCY else 1,
      onScanned = { dirs -> onProgress(dirs, found.size) },
    ) { entries ->
      entries.forEach { file ->
        if (!file.isDirectory && file.isVideoFile() && found.size < poolCap) found.add(file)
      }
      found.size < poolCap
    }
    return FeedPool(found, scan.scannedDirs, scan.timedOut)
  }

  /**
   * 这次抽片最多探多少个目录 —— 由 `want` 和协议一起决定（推导见 [DIRS_PER_WANTED_VIDEO]）。
   *
   * 为什么按协议分两档：并发能把「几百个目录」的时间压到十几秒，而 FTP / SMB
   * 只能串行（[MAX_SCAN_DIRS_SERIAL] 的 KDoc 解释了 FTP 为什么不能并发）。
   * 用同一个上限的话，等于让 FTP 用户等一分钟，或者让 AList 用户永远抽不满。
   */
  private fun dirBudget(want: Int, protocol: NetworkProtocol): Int {
    val ceiling =
      if (canScanInParallel(protocol)) MAX_SCAN_DIRS_PARALLEL else MAX_SCAN_DIRS_SERIAL
    return (want * DIRS_PER_WANTED_VIDEO).coerceIn(MIN_SCAN_DIRS, ceiling)
  }

  /**
   * 这个协议能不能**同时列多个目录**。
   *
   * 只有底层是 HTTP 客户端的两个能：AList 与 WebDAV 都跑在 OkHttp 上（连接池 +
   * 分发器本来就是为并发设计的，签名缓存也是 `ConcurrentHashMap`）。
   * FTP 是**一条控制连接**，SMB 的 jcifs 会话也不值得为此冒险 —— 列目录一旦
   * 串了协议流，用户看到的是「列出来的文件不是这个目录的」，比慢一秒糟得多。
   */
  private fun canScanInParallel(protocol: NetworkProtocol): Boolean =
    protocol == NetworkProtocol.ALIST || protocol == NetworkProtocol.WEBDAV

  /**
   * 按**目录**分组收集图片：每到一个目录 = 一组，组内就是该目录这一级的全部图片。
   *
   * ## 为什么返回「组」而不是平铺一张表
   * 相册语义里同一次拍摄的照片该连着看，把它洗乱只是噪音；而「先看哪个相册」随机一点
   * 才有「随机播放」的意思。所以分组交回调用方，由它决定组间洗牌、组内保序。
   *
   * ## 「仅那一级」
   * 列目录本身就是**只看一级**（SMB / WebDAV / FTP / AList 都是即时子项列表），
   * 所以每组天然只含该目录的直属图片，不会把子目录的图片混进来 —— 下钻由 [walk] 负责，
   * 每层各自成组。
   *
   * ## 本级也算
   * 起始目录（用户当前所在的那一层）**同样是一组**：`walk` 第一个探的就是它，
   * 勾上穿透不等于「只看子文件夹」，本级 + 各子目录层级全在队列里。
   *
   * @param drillDown false 时只有起始目录一组（与旧行为一致）
   * @param maxPhotos 队列总上限，到了就不再往深走（见 [MAX_PHOTO_QUEUE]）
   */
  suspend fun collectPhotoGroups(
    repository: NetworkRepository,
    connection: NetworkConnection,
    startPath: String,
    drillDown: Boolean,
    maxPhotos: Int = MAX_PHOTO_QUEUE,
  ): List<List<NetworkFile>> {
    if (maxPhotos <= 0) return emptyList()
    val groups = ArrayList<List<NetworkFile>>()
    var total = 0
    walk(repository, connection, startPath, drillDown) { entries ->
      val level = entries.filter { !it.isDirectory && it.isPhoto() }
      if (level.isNotEmpty()) {
        groups.add(level)
        total += level.size
      }
      total < maxPhotos
    }
    return groups
  }

  /**
   * 广度优先遍历目录（视频与图片共用这一条，避免两份下钻逻辑各修各的）。
   *
   * - `drillDown = false`：只列起始目录一次；
   * - `drillDown = true`：**按层**推进 —— 起始目录 → 第 1 层全部子目录 →
   *   第 2 层全部子目录 ……，最多 [maxDirs] 个目录。
   *
   * ## 为什么改成「按层」而不是「一个队列逐个取」
   * ① 公平：同层所有目录列完才下潜，不会因为某个目录排在前面就把预算全吃在
   *    它那条支路上 —— 「视频散在很多很多文件夹里」时这一点直接决定抽得到多少。
   * ② 能并发：同一层的目录互相独立，才能一次发 [concurrency] 个请求。
   *
   * ## 并发的边界（很重要）
   * **只有「列目录」这一步并行**，[visit] 一律回到当前协程顺序执行：
   * 两个调用方（[collectVideos] / [collectPhotoGroups]）都是往普通 ArrayList 里塞，
   * 并发 visit 会直接踩坏它们；把并行限制在网络往返这一段，两边逻辑一个字都不用改。
   * 另外按 [concurrency] 切片而不是整层一次丢出去 —— 一层可能有几百个目录，
   * 全丢进去等于同时握住几百份目录列表（几万条 [NetworkFile]）。
   *
   * [visit] 拿到「某个目录的一级子项」，返回 `false` 表示已经凑够，立刻停止下钻。
   *
   * @param maxMillis 墙钟预算（0 = 不限）。超了就在**下一片之前**收手 —— 手上已经
   *   收进来的视频照常返回，绝不为了凑数把用户钉在等号上（见 [SCAN_TIME_BUDGET_MS]）
   * @param onScanned 每列完一小批目录回调一次（累计已扫目录数），给进度提示用
   * @return 实际列过的目录数 + 是否是被时间预算掐停的
   */
  private suspend fun walk(
    repository: NetworkRepository,
    connection: NetworkConnection,
    startPath: String,
    drillDown: Boolean,
    maxDirs: Int = MAX_SCAN_DIRS,
    maxMillis: Long = 0L,
    concurrency: Int = 1,
    onScanned: (Int) -> Unit = {},
    visit: (List<NetworkFile>) -> Boolean,
  ): ScanOutcome {
    if (!drillDown) {
      val entries = listQuietly(repository, connection, startPath) ?: return ScanOutcome(0, false)
      visit(entries)
      return ScanOutcome(1, false)
    }
    val deadline = if (maxMillis > 0L) SystemClock.elapsedRealtime() + maxMillis else Long.MAX_VALUE
    var frontier = listOf(startPath)
    var scanned = 0
    // 第一片（起始目录）必须扫：否则连「这棵树里有没有视频」都答不上来
    var tookFirstChunk = false
    while (frontier.isNotEmpty() && scanned < maxDirs) {
      // 预算可能在层中间用完：这一层只取还剩的额度，多的留到下一次（不预支）
      val room = maxDirs - scanned
      val batch = if (frontier.size > room) frontier.subList(0, room) else frontier
      val next = ArrayList<String>()
      var timeUp = false
      for (chunk in batch.chunked(concurrency)) {
        if (tookFirstChunk && SystemClock.elapsedRealtime() > deadline) {
          timeUp = true
          break
        }
        tookFirstChunk = true
        // ⚠️ 计数必须在**发出请求之前**加，且按小批加 —— 提前把整层都记进
        //    `scanned` 会让「已扫 N 个目录」在超时收手时虚报一整层。
        scanned += chunk.size
        val listings = if (concurrency <= 1) {
          chunk.map { path -> listQuietly(repository, connection, path) }
        } else {
          coroutineScope {
            chunk.map { path -> async { listQuietly(repository, connection, path) } }.awaitAll()
          }
        }
        for (entries in listings) {
          if (entries == null) continue
          if (!visit(entries)) return ScanOutcome(scanned, false)
          // 子目录**随机入队**：本级恒是第一个被探到的（用户所在这一层必进候选），
          // 但子目录之间谁先谁后不该等于「它在列表里排第几」—— 预算先满时，
          // 排后面的目录会一辈子轮不到，那就不是随机了。
          entries.filter { it.isDirectory }.shuffled().forEach { next.add(it.path) }
        }
        onScanned(scanned)
      }
      if (timeUp) return ScanOutcome(scanned, true)
      frontier = next
    }
    return ScanOutcome(scanned, false)
  }

  /**
   * 列一个目录，失败返回 null。
   *
   * 单个目录失败（权限 / 已删除 / 会话过期）不该毁掉整批：跳过，继续探下一个。
   * 取消要原样抛出，否则 `viewModelScope` 的取消会被吞成「空目录」。
   */
  private suspend fun listQuietly(
    repository: NetworkRepository,
    connection: NetworkConnection,
    path: String,
  ): List<NetworkFile>? = try {
    repository.listFiles(connection, path).getOrNull()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  /**
   * 把视频表转成视界流的播放队列（已洗牌 + 截断）。
   *
   * 地址解析与主播放器**共用** [NetworkVideoUriBuilder]：SMB / FTP / WebDAV 走本地
   * 代理（会话不能给播放器直接用）、AList 直连签名直链、其余走 ContentProvider。
   * 单独失败的那一条直接跳过 —— 视界流是「刷完就走」，一条取不到地址不该让整批拒绝启动。
   *
   * @param libraryId 恒传 null：网盘内容不绑定 Emby 库，`null` 同时关闭「续拉下一页」，
   *   并让视界流隐藏收藏 / 不喜欢这两个 Emby 专属按钮（见 FeedViewModel.embyBound）。
   */
  suspend fun launch(
    context: Context,
    application: Application,
    repository: NetworkRepository,
    connection: NetworkConnection,
    videos: List<NetworkFile>,
    want: Int,
  ): Boolean {
    if (videos.isEmpty()) return false
    val picked = videos.shuffled().take(want)
    val builder = NetworkVideoUriBuilder(application, repository, connection)
    val stamp = System.currentTimeMillis()
    val items = ArrayList<FeedItem>(picked.size)
    picked.forEachIndexed { index, file ->
      val uri = try {
        builder.uriFor(file, "feed_${connection.id}_${stamp}_$index")
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      } ?: return@forEachIndexed
      items.add(
        FeedItem(
          itemId = file.path,
          title = file.name,
          url = uri.toString(),
          isFavorite = false,
          // 网盘没有 Emby 那套元数据，但容器可以从文件名拿 —— rmvb / wmv 这类
          // 内置内核拆不开的容器，靠它就能在起播前直接交给 mpv。
          container = file.name
            .substringAfterLast('.', "")
            .trim()
            .lowercase()
            .takeIf { it.isNotEmpty() },
        ),
      )
    }
    if (items.isEmpty()) return false
    VerticalFeedActivity.launch(
      context = context,
      items = items,
      libraryId = null,
      favoritesOnly = false,
    )
    return true
  }
}

/**
 * 一次抽片的结果：候选视频 + 这次**一共列了多少个目录**。
 *
 * ## 为什么要带扫描数
 * 视频散在几百个文件夹里时，`want` 是抽不满的（时间预算 6 秒 + 目录预算，
 * 见 `SCAN_TIME_BUDGET_MS` / `dirBudget`）。静默地只给 41 条、或者只说一句
 * 「没有视频」，用户只会以为是 bug —— 带上「已扫 600 个文件夹」才能把话说清：
 * **不是没找到，是这棵树里就这么点（或者太散，给的时间不够）**。
 *
 * [scannedDirs] 同时也是「这次花了多少次网络往返」的账，出问题时看这一条。
 *
 * @param timedOut true = 是被**时间预算**掐停的（树里可能还有视频，只是等不起了）。
 *   这时文案该说「太分散」，与「这里确实只有这么多」是两种不同的坏消息
 */
data class FeedPool(
  val videos: List<NetworkFile>,
  val scannedDirs: Int,
  val timedOut: Boolean,
)

/**
 * [NetworkFeedLauncher.walk] 的收工账：列了几个目录、是不是被时间预算掐停的。
 *
 * 单独一个类型而不是 `return Int`：视频那条路要据此告诉用户「是太散还是真没有」，
 * 只回一个数字就得靠副作用往外带，迟早有人漏读。
 */
private data class ScanOutcome(
  val scannedDirs: Int,
  val timedOut: Boolean,
)

/**
 * 网络视频 → 播放地址的唯一出处。
 *
 * 三种协议三条路，**只有这里写一次**：原来是 `playVideos` 里的内联分支，
 * 现在主播放器队列与视界流共用，避免「一边修了另一边没修」。
 *
 * - **AList**：服务端直出签名直链（支持 Range ⇒ 能拖动），直连不过代理；
 *   签名优先用 `fs/list` 带回来的那一份**本地拼**（零请求，见 `directUriOrNull`），
 *   只有拿不到签名时才 `fs/get` 兜底一次。
 * - **SMB / FTP / WebDAV**：走本地代理（会话对象不能交给播放器，代理支持 Range）；
 * - **其余**：ContentProvider 单向管道兜底（拖不了进度，但能播）。
 */
internal class NetworkVideoUriBuilder(
  private val application: Application,
  private val repository: NetworkRepository,
  private val connection: NetworkConnection,
) {
  private val useProxy = connection.protocol in PROXY_PROTOCOLS

  /** AList 的直连客户端（要拿签名直链）。首次使用时建一次，后续复用 */
  private var directClient: app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient? =
    null

  /**
   * @param streamId 代理流 Id，必须**全局唯一**（同一条视频被登记两次会互相顶掉）。
   *   主播放器队列用 `连接Id_时间戳_下标`，视界流用 `feed_...` 前缀区分。
   */
  suspend fun uriFor(file: NetworkFile, streamId: String): Uri {
    if (connection.protocol == NetworkProtocol.ALIST) {
      val client = directClient ?: repository.clientFor(connection).also { directClient = it }
      // ⭐ ① 零请求快路：`fs/list` 已经把每条的 `sign` 带回来了（[NetworkFile.sign]），
      //    本地拼一条 `/d/...?sign=` 就够 —— 支持 Range，播放器直连。
      //
      //    ⚠️ 这一步是「点视频要等十几秒才弹播放器」的解药，别改回只走 fs/get：
      //    连播队列是**整个目录逐条算地址**，`getFileUri` 每条一次 `fs/get`，
      //    100 条视频 = 100 次串行往返。真机上就是这个现象的根因。
      client.directUriOrNull(file.path, file.sign)?.let { return it }
      // ② 手里没有签名（服务端没开签名 / 签名已证伪 / 非 list 来源的条目）
      //    才发那一次 fs/get，让它给 raw_url 或一个新鲜签名。
      return client.getFileUri(file.path).getOrThrow()
    }
    if (useProxy) {
      val proxy = NetworkStreamingProxy.getInstance()
      val proxyUrl = proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = file.path,
        fileSize = file.size,
        mimeType = file.mimeType ?: "video/mp4",
      )
      return Uri.parse(proxyUrl)
    }
    NetworkStreamingProvider.setConnection(connection.id, connection)
    return NetworkStreamingProvider.getUri(application, connection.id, file.path)
  }

  companion object {
    /**
     * 需要走本地代理才能**拖动进度**的协议。
     *
     * ⚠️ AList **刻意不在**这里：它的 `/d/...?sign=` 与 `raw_url` 本身就是支持
     * Range 的 http 直链，播放器直连即可。把它加进来只会白绕一层本地代理。
     */
    val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )
  }
}
