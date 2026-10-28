package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Context
import android.net.Uri
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
 *   [want] 条或探完 [MAX_SCAN_DIRS] 个目录。连接根目录通常一个视频都没有，
 *   不递归的话从卡片点进去永远是「没有视频」。
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
   * 递归抽片时最多探多少个目录。
   *
   * 定 40：NAS 上一个库通常是 `剧名/季/集` 三层，40 个目录足以覆盖一屏之外的
   * 十几个剧集；再往上加，用户点完要盯着 toast 等十几秒（每次列目录都是一次
   * 网络往返），体验比「少抽几条」更糟。
   */
  const val MAX_SCAN_DIRS = 40

  /**
   * 下钻时的**抽签池**倍数：池子 = want × 这个倍数（上限 [MAX_POOL]）。
   *
   * 定 4：足够让「本级 + 三四个子目录」都进候选，又不至于把 40 个目录全探一遍
   * —— 每个目录都是一次网络往返，探得越久用户盯着 toast 的时间越长。
   */
  private const val POOL_FACTOR = 4

  /** 抽签池硬上限：数量设置被拉到 500 时，不能真去收 2000 条候选 */
  private const val MAX_POOL = 300

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
   * @param startPath 起始目录（连接卡传 `/`）
   * @param want 目标条数（= 设置项里的随机播放数量）
   * @param drillDown 是否穿透子文件夹
   */
  suspend fun collectVideos(
    repository: NetworkRepository,
    connection: NetworkConnection,
    startPath: String,
    want: Int,
    drillDown: Boolean,
  ): List<NetworkFile> {
    if (want <= 0) return emptyList()
    // ⚠️ 抽签池必须比 want 大，否则「穿透」等于没开：
    //    下钻是从**本级**开始的 BFS，收够 want 就停 ⇒
    //    ① 本级视频够数时，队列里一条子目录的都进不来；
    //    ② 本级不够时，也永远只轮到 BFS 最靠前的几个目录。
    //    洗牌只能洗「已经收进来的这批」，看着随机，其实被目录顺序钉死了。
    //    所以先收 want × [POOL_FACTOR] 条，再交给调用方 `shuffled().take(want)`；
    //    目录数本来就被 [MAX_SCAN_DIRS] 限住，池子放大不会多一次网络请求。
    val poolCap = if (drillDown) (want * POOL_FACTOR).coerceAtMost(MAX_POOL) else Int.MAX_VALUE
    val found = ArrayList<NetworkFile>(minOf(poolCap, 256))
    walk(repository, connection, startPath, drillDown) { entries ->
      entries.forEach { file ->
        if (!file.isDirectory && file.isVideoFile() && found.size < poolCap) found.add(file)
      }
      found.size < poolCap
    }
    return found
  }

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
   * - `drillDown = true`：起始目录 → 逐层子目录，最多 [maxDirs] 个目录。
   *
   * [visit] 拿到「某个目录的一级子项」，返回 `false` 表示已经凑够，停止下钻。
   */
  private suspend fun walk(
    repository: NetworkRepository,
    connection: NetworkConnection,
    startPath: String,
    drillDown: Boolean,
    maxDirs: Int = MAX_SCAN_DIRS,
    visit: (List<NetworkFile>) -> Boolean,
  ) {
    if (!drillDown) {
      val entries = listQuietly(repository, connection, startPath) ?: return
      visit(entries)
      return
    }
    val pending = ArrayDeque<String>()
    pending.add(startPath)
    var scanned = 0
    while (pending.isNotEmpty() && scanned < maxDirs) {
      val path = pending.removeFirst()
      scanned++
      val entries = listQuietly(repository, connection, path) ?: continue
      if (!visit(entries)) return
      // 子目录**随机入队**：本级恒是第一个被探到的（用户所在这一层必进候选），
      // 但子目录之间谁先谁后不该等于「它在列表里排第几」—— 池子先满时，
      // 排后面的目录会一辈子轮不到，那就不是随机了。
      entries.filter { it.isDirectory }.shuffled().forEach { pending.add(it.path) }
    }
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
