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
    val path = PlayerLib.getPropertyString("path") ?: return null
    val match = ITEM_ID_REGEX.find(path) ?: return null
    val id = match.groupValues.getOrNull(1) ?: return null
    // 只认可本次播放列表里的 ID，避免 mpv 自动连播到无关文件时误报
    return id.takeIf { itemIds.contains(it) }
  }

  private val ITEM_ID_REGEX = Regex("/Videos/([^/?]+)/stream")

  // ─── Intent extra 键（与 EmbyViewModel 启动播放器时写入的保持一致）───

  const val EXTRA_SERVER_ID = "emby_server_id"
  const val EXTRA_ITEM_IDS = "emby_item_ids"

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
 */
internal fun Intent.putEmbyPlaybackExtras(
  server: EmbyServer,
  itemIds: List<String>,
) {
  putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, server.id)
  putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(itemIds))
}
