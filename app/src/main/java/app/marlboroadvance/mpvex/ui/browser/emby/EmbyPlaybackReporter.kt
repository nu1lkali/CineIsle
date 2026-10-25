package app.marlboroadvance.mpvex.ui.browser.emby
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * Emby 播放进度上报器。
 *
 * 职责：把 mpv 的播放状态实时同步回 Emby 服务器——
 * - 开始播放：POST /Sessions/Playing
 * - 播放中 / 暂停：POST /Sessions/Playing/Progress
 * - 切换下一集：上一集 Stopped + 新一集 Start（服务端"正在播放"随之更新）
 * - 退出播放页：POST /Sessions/Playing/Stopped
 *
 * 实现方式：直接读 MPVLib 的静态属性（time-pos / pause / path），
 * 以轮询的方式工作，因此不需要改动 PlayerActivity 内部的播放控制逻辑，
 * 只需在 Activity 上挂一个 lifecycle 观察者即可。
 */
object EmbyPlaybackReporter {
  private const val TAG = "EmbyReporter"

  /** 上报间隔：Emby 官方客户端也是每 10 秒一次进度上报 */
  private const val PROGRESS_INTERVAL_MS = 10_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var pollingJob: Job? = null

  private var serverId: Long = -1L
  private var itemIds: List<String> = emptyList()
  private var currentItemId: String? = null
  private var playSessionId: String = ""
  private var lastReportedTicks: Long = 0L

  /** 与 [itemIds] **同序同长**的媒体源 Id（多版本条目预热时要用与播放一致的那个） */
  private var mediaSourceIds: List<String> = emptyList()

  /**
   * 已经预热过的 `"服务器Id:条目Id"`。
   *
   * 服务端 probe 完就写库了，同一条**没必要再打一次**；这份记录让「同一场播放里
   * 来回切集」不会重复预热，也让本类对服务器的请求总数 = 条目数（每条最多一次）。
   */
  private val warmedKeys = HashSet<String>()

  /**
   * 预热任务（单槽）。
   *
   * ⚠️ 必须串行：用户明确要求「不能同时向 CDN 发大量请求」。这里用**一个 Job 槽**
   * 保证同一时刻最多一条预热在飞 —— 上一个还没回来（服务端 probe 慢时可能十几秒）
   * 就直接放弃新的预热，而不是排队堆起来。预热是纯优化，少做一次没有后果。
   */
  private var warmJob: Job? = null

  /**
   * 排队中的下一条预热（`itemId` / 媒体源 Id）。
   *
   * 只保留**最新**一个：切集是往前走的，排一队旧的没有意义；同时保证「永远只有一个
   * 预热在飞」这条硬约束 —— 用户在意的是网盘 CDN 的风控，并发请求是大忌。
   */
  private var pendingWarm: Pair<String, String?>? = null

  /**
   * 播放页创建时调用：上报开始并启动轮询。
   *
   * @return true 表示这次播放来自 Emby，已接管上报
   */
  fun start(intent: Intent?): Boolean {
    val ids = intent?.getStringArrayListExtra(EXTRA_ITEM_IDS)
    val sid = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
    if (ids.isNullOrEmpty() || sid <= 0) return false

    serverId = sid
    itemIds = ids
    currentItemId = ids.firstOrNull()
    lastReportedTicks = 0L
    mediaSourceIds =
      intent.getStringArrayListExtra(EXTRA_MEDIA_SOURCE_ID)?.toList() ?: emptyList()

    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return false

    val firstId = currentItemId ?: return false
    // 用启动时指定的续播位置作为起点
    val startPositionSeconds = intent.getIntExtra("position", 0) / 1000
    scope.launch {
      runCatching {
        val server = repository.getServer(sid) ?: return@launch
        playSessionId = repository.reportPlaybackStart(
          server = server,
          itemId = firstId,
          positionTicks = EmbyTicks.secondsToTicks(startPositionSeconds.toLong()),
        )
      }
    }

    // 让播放页的 Emby 快捷操作（收藏等）拿到当前 server / item
    EmbyPlayerActions.bind(intent)
    // ⚠️「不喜欢」同样必须**起播时就 bind**。
    //  之前只在 `reportProgressOnce()` 里、且仅当检测到切集时才 bind ——
    //  于是刚进播放页那一整段时间 `DislikePlayerActions.currentKey` 还是 null，
    //  横屏点按钮只会提示「当前视频没有可标记的来源」（反馈）。
    //  切集时那次 bind 保留：它是「画面已经切到下一集」的权威时机。
    //  bind 对相同 key 幂等（直接 return，零查询），两处都调没有额外开销。
    app.marlboroadvance.mpvex.ui.player.DislikePlayerActions.bind(
      app.marlboroadvance.mpvex.domain.dislike.DislikeKey(sid, firstId),
      // ⚠️ 片名必须传。播放器里标记时**只有 id**，不传的话存进库里的记录
      //   `title` 是空串 —— 管理页只能显示「（无标题）」，用户认不出标的是哪一部。
      //   播放页本来就有片名（启动播放器时作为 title extra 传进来的），顺手带上即可。
      title = intent?.getStringExtra("title").orEmpty(),
    )

    startPolling()
    // 第一条已经在放了 ⇒ 顺手把**下一条**预热掉（服务端 probe 完，切过去就秒开）。
    warmNextItem(firstId)
    return true
  }

  /** 播放页销毁时调用：上报停止并结束轮询 */
  fun stop() {
    pollingJob?.cancel()
    pollingJob = null
    // 预热只是优化：用户已经离开播放页了，没必要再占着一条请求
    warmJob?.cancel()
    warmJob = null
    pendingWarm = null
    EmbyPlayerActions.release()

    val itemId = currentItemId ?: return
    val ticks = readPositionTicks()
    val sid = serverId
    currentItemId = null

    scope.launch {
      runCatching {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(sid) ?: return@launch
        repository.reportPlaybackStopped(
          server = server,
          itemId = itemId,
          playSessionId = playSessionId,
          positionTicks = ticks,
        )
      }
    }
  }

  /**
   * 预热「[currentId] 的下一条」。
   *
   * ## 为什么需要它（v142）
   *
   * 随机播放 / 连播都是「起播才去请求这一条」。strm 条目在服务端还没 probe 过源文件时，
   * 那次请求要先让服务端读远端文件、把大小时长写库 —— 能挂到分钟级。用户在播放页
   * 点「下一个」，看到的就是又卡住了；同一部片第二次播就快了，因为服务端已经有缓存。
   *
   * 所以这里提前一条：当前这条放起来之后（[WARM_DELAY_MS] 之后，别跟它抢带宽），
   * 用 [EmbyClient.warmStream] 让服务端先把下一条 probe 完。
   *
   * ## 三条硬约束（都是「别把服务器打爆」）
   *
   * · **只预热下一条**：不做整列表预取，请求数 = 条目数 × 1；
   * · **串行**：单槽 [warmJob]，上一个没回来就不起新的；期间新的请求只**记成待办**
   *   （[pendingWarm]），当前这条跑完再接着做 —— 不排队堆积、不并发；
   * · **不跟 302**：[EmbyClient.warmStream] 内部 `followRedirects=false`，
   *   请求只到我们自己的 Emby 服务器，**一个字节都不打网盘 CDN**。
   *
   * ⚠️ 本服务器的形态是「Emby 只存 .strm，真实文件在网盘 CDN 上」，所以这一条尤其要紧：
   * 预热**不增加** CDN 的总请求量（那次服务端读远端文件的 probe 本来就要在切播时发生），
   * 只是把它从「用户等待时」提前到「当前这条还在播时」。
   *
   * 预热失败 / 超时都没有后果：切到那一条时播放器自己再请求一次，行为与没有预热时一致。
   */
  private fun warmNextItem(currentId: String) {
    val idx = itemIds.indexOf(currentId)
    if (idx < 0 || idx + 1 >= itemIds.size) return
    val nextId = itemIds[idx + 1]
    val key = "$serverId:$nextId"
    if (key in warmedKeys) return
    val sourceId = mediaSourceIds.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }

    // ⚠️ 顺序要紧：先判「有没有在飞」，再记账。反过来的话，被跳过的那条会被误记成
    //    「已预热」，之后就再也不会真的预热它了。
    if (warmJob?.isActive == true) {
      // 串行但不丢：只保留**最新**一个待办（旧的已经过时了，没必要排一队）
      pendingWarm = nextId to sourceId
      return
    }
    startWarm(nextId, sourceId)
  }

  /** 真正发一条预热（调用方保证同一时刻只有一个在飞） */
  private fun startWarm(itemId: String, mediaSourceId: String?) {
    val key = "$serverId:$itemId"
    if (key in warmedKeys) return
    warmedKeys.add(key)
    // 记录别无限长：一场播放的条目数有限，真到几千条时清一次（服务端本来就已缓存）
    if (warmedKeys.size > 512) warmedKeys.clear()
    warmJob = scope.launch {
      // 先让当前这条自己的缓冲跑起来，别跟它抢链路
      delay(WARM_DELAY_MS)
      runCatching {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(serverId) ?: return@launch
        EmbyClient.warmStream(server, itemId, mediaSourceId = mediaSourceId)
      }
      // 跑完再看有没有排队的：有就接着做（仍然只有一个在飞）
      val queued = pendingWarm
      pendingWarm = null
      if (queued != null) startWarm(queued.first, queued.second)
    }
  }

  /**
   * 预热前的等待：当前这条刚起播时链路最紧张（拉容器头 + 首帧 + 缓冲），
   * 这时候再压一条预热上去，帮倒忙的概率比帮忙大。6 秒后当前这条基本已经稳住。
   */
  private const val WARM_DELAY_MS = 6_000L

  private fun startPolling() {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      // 等 mpv 加载完文件再读属性，避免读到空值
      delay(PROGRESS_INTERVAL_MS)
      while (isActive) {
        reportProgressOnce()
        delay(PROGRESS_INTERVAL_MS)
      }
    }
  }

  /**
   * 一次进度上报。
   *
   * 若发现 mpv 当前播放的文件已经变成播放列表里的另一项（播完自动切下一集，
   * 或用户手动切换），则先结束上一集再开始新一集，让服务端的"正在播放"跟着走。
   */
  private suspend fun reportProgressOnce() {
    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return
    val server = repository.getServer(serverId) ?: return

    val positionTicks = readPositionTicks()
    val isPaused = readIsPaused()
    val playingItemId = readCurrentItemId() ?: currentItemId ?: return

    if (playingItemId != currentItemId) {
      // ── 切换了媒体：结束上一集，开始新一集 ──
      val previousItemId = currentItemId
      val previousTicks = lastReportedTicks
      currentItemId = playingItemId
      // 同步播放页 UI（收藏按钮等）到新的这一集
      EmbyPlayerActions.onItemChanged(playingItemId)
      // 「不喜欢」按钮同样要跟着切。走**内存快照**（一次 HashMap 查找，无 IO），
      // 所以连播时按钮状态与画面在同一帧完成，不会出现"先亮后暗"
      app.marlboroadvance.mpvex.ui.player.DislikePlayerActions.bind(
        app.marlboroadvance.mpvex.domain.dislike.DislikeKey(serverId, playingItemId),
      )

      if (previousItemId != null) {
        runCatching {
          repository.reportPlaybackStopped(
            server = server,
            itemId = previousItemId,
            playSessionId = playSessionId,
            positionTicks = previousTicks,
          )
        }
      }
      runCatching {
        playSessionId = repository.reportPlaybackStart(
          server = server,
          itemId = playingItemId,
          positionTicks = 0,
        )
      }
      lastReportedTicks = 0
      // 切到下一条了 ⇒ 立刻把「再下一条」预热掉。这就是 v142 修「随机播放切下一个
      // 还是卡住」的那一刀：卡的不是客户端，是服务端还没 probe 过那条 strm 的远端源。
      warmNextItem(playingItemId)
      return
    }

    lastReportedTicks = positionTicks
    runCatching {
      repository.reportPlaybackProgress(
        server = server,
        itemId = playingItemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
      )
    }
  }

  // ─── mpv 属性读取 ───

  private fun readPositionTicks(): Long {
    val seconds = PlayerLib.getPropertyInt("time-pos") ?: 0
    return EmbyTicks.secondsToTicks(seconds.toLong())
  }

  private fun readIsPaused(): Boolean = PlayerLib.getPropertyBoolean("pause") == true

  /**
   * 从当前播放路径里解析出 Emby 媒体 ID（经 PlayerLib 门面读，mpv / Exo 都适用）。
   *
   * 播放地址形如 `/emby/Videos/{itemId}/stream?static=true&api_key=...`
   */
  private fun readCurrentItemId(): String? {
    // 只认可本次播放列表里的 ID，避免 mpv 自动连播到无关文件时误报
    return parseItemIdFromPath(PlayerLib.getPropertyString("path"))
      ?.takeIf { itemIds.contains(it) }
  }

  /**
   * 从 mpv 当前播放路径里解析 Emby 媒体 ID；不是 Emby 流（或没解析出）返回 null。
   *
   * `public` 是给播放页用的：它要按「正在播的这一条」构造按版本的进度键，
   * 而**不能用 `playlistIndex` 去索引 intent 里的 itemIds** —— 用户拖动过播放队列后
   * 下标就与 intent 的原始顺序错位了。从路径反查 Id 与队列顺序无关，天然免疫这种错位。
   */
  fun parseItemIdFromPath(path: String?): String? =
    path?.let { ITEM_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
      ?.takeIf { it.isNotBlank() }

  private val ITEM_ID_REGEX = Regex("/Videos/([^/?]+)/stream")

  // ─── Intent extra 键（与 EmbyViewModel 启动播放器时写入的保持一致）───

  const val EXTRA_SERVER_ID = "emby_server_id"
  const val EXTRA_ITEM_IDS = "emby_item_ids"

  /**
   * 当前播放的媒体源 Id（在线字幕搜索要用）。
   *
   * 为什么单独一个 extra 而不是让播放页反查：反查要走
   * `GET /Items/{Id}` 再从 `MediaSources` 里挑，播放页是**每切一集都变**的，
   * 而这个值在启动播放器时就已经知道了。多版本媒体（4K/1080P 各带不同字幕）
   * 时挑错就搜到别的版本的字幕，所以必须由启动侧显式传。
   */
  const val EXTRA_MEDIA_SOURCE_ID = "emby_media_source_id"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的多版本名列表（取不到的用空串占位）。
   *
   * 播放页顶栏会在片名后面追加当前这一条的版本名（如「片名 · 1080P HEVC」）——
   * 用户在多版本条目里选了哪个版本，播起来得能看见，否则「切了版本」没有任何反馈。
   * 用列表而不是单值，理由同 [EXTRA_MEDIA_SOURCE_ID]：连播切集后版本会变。
   */
  const val EXTRA_MEDIA_SOURCE_NAMES = "emby_media_source_names"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的「原始剧目顺序号」，编码成
   * `ParentIndexNumber*1000 + IndexNumber`（任一为空则存空串）。
   *
   * ⚠️ 为什么弹幕非要这个：`PlayerActivity` 传给弹幕匹配的是「播放队列下标」，
   * 那是**队列里的第几个**，不是「第几集」。用户从第 5 集点进详情页播放时，
   * 队列只有 1 条 ⇒ 下标 0 ⇒ 会被当成第 1 集去取弹幕。
   * 有了这个字段，弹幕按真实集号匹配（跨季也成立：index 随 Season 累积）。
   *
   * 为什么不用 [EXTRA_ITEM_IDS] 反查服务端：那是异步的，而弹幕加载在切集瞬间就要用，
   * 多一次网络往返只在换集时失败率更高，还会与「预解析直链」的路径反查打架。
   */
  const val EXTRA_EPISODE_NUMBERS = "emby_episode_numbers"

  /**
   * 挂在 PlayerActivity 上的生命周期观察者。
   *
   * 用法：`lifecycle.addObserver(EmbyPlaybackLifecycleObserver(intent))`
   */
  class EmbyPlaybackLifecycleObserver(
    private val intent: Intent?,
  ) : DefaultLifecycleObserver {
    override fun onCreate(owner: LifecycleOwner) {
      start(intent)
    }

    override fun onDestroy(owner: LifecycleOwner) {
      stop()
    }
  }
}

/**
 * 供 EmbyViewModel 调用：写入上报所需的 extra。
 *
 * @param mediaSourceIds 与 [itemIds] **同序同长**的媒体源 Id 列表；
 *   取不到的用空串占位（在线字幕搜索会跳过空串）。
 *   用列表而不是单值，是因为播放器支持连播 —— 切集后当前媒体源会变，
 *   播放页按当前 `playlistIndex` 取对应那个。
 */
internal fun Intent.putEmbyPlaybackExtras(
  server: EmbyServer,
  itemIds: List<String>,
  mediaSourceIds: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的版本显示名（空串 = 这一条没有多版本 / 没选版本）。
   * 播放页顶栏据此在片名后追加「· <版本名>」。
   */
  mediaSourceNames: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的「S*E*」原始序号，见 [EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS]。
   * 非剧集（电影 / 本地文件）传空表即可 —— 播放页拿不到就退回队列下标。
   */
  episodeNumbers: List<String> = emptyList(),
) {
  putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, server.id)
  putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(itemIds))
  if (episodeNumbers.isNotEmpty()) {
    putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS, ArrayList(episodeNumbers))
  }
  if (mediaSourceIds.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID,
      ArrayList(mediaSourceIds),
    )
  }
  if (mediaSourceNames.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_NAMES,
      ArrayList(mediaSourceNames),
    )
  }
}
