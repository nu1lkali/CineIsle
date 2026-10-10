package app.marlboroadvance.mpvex.ui.browser.emby
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
    //  横屏点按钮只会提示「当前视频没有可标记的来源」（用户实测反馈）。
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
    return true
  }

  /** 播放页销毁时调用：上报停止并结束轮询 */
  fun stop() {
    pollingJob?.cancel()
    pollingJob = null
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
) {
  putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, server.id)
  putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(itemIds))
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
