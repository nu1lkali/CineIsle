package app.marlboroadvance.mpvex.ui.browser.emby

import android.content.Intent
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * 播放页的 Emby 快捷操作中心。
 *
 * 解决的问题：PlayerActivity / PlayerControls 是通用播放器代码，不应该知道 Emby 的存在。
 * 这里用一个进程内单例把"Emby 态"暴露成 StateFlow，播放器 UI 只管订阅 + 调 [toggleFavorite]，
 * 所有网络请求、登录校验、防重复点击都在内部消化。
 *
 * 生命周期由 [EmbyPlaybackReporter] 驱动（它已经挂在 PlayerActivity 的 lifecycle 上）：
 * - start() -> [bind]
 * - stop()  -> [release]
 * - 切换媒体 -> [onItemChanged]
 */
object EmbyPlayerActions {
  private const val TAG = "EmbyPlayerActions"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _isEmbyPlayback = MutableStateFlow(false)
  private val _isFavorite = MutableStateFlow(false)
  private val _isToggling = MutableStateFlow(false)
  private val _message = MutableSharedFlow<String>(extraBufferCapacity = 2)

  /** 本次播放是否来自 Emby。为 false 时播放器应隐藏 Emby 相关按钮。 */
  val isEmbyPlayback: StateFlow<Boolean> = _isEmbyPlayback.asStateFlow()

  /** 当前媒体的收藏状态（仅在 [isEmbyPlayback] 为 true 时有意义） */
  val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

  /** 收藏请求进行中 —— 用于 UI 置灰 + 防重复点击 */
  val isToggling: StateFlow<Boolean> = _isToggling.asStateFlow()

  /** 一次性提示（未登录、请求失败等），播放器用 LaunchedEffect 收集后弹 Toast */
  val message: SharedFlow<String> = _message.asSharedFlow()

  private var serverId: Long = -1L
  private var itemId: String? = null
  private var toggleJob: Job? = null

  /**
   * 绑定一次播放。读取 intent 里由 [putEmbyPlaybackExtras] 写入的 server / item 信息，
   * 并立即拉取一次收藏状态。
   */
  fun bind(intent: Intent?) {
    val sid = intent?.getLongExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, -1L) ?: -1L
    val ids = intent?.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS)
    if (sid <= 0L || ids.isNullOrEmpty()) {
      release()
      return
    }
    serverId = sid
    itemId = ids.firstOrNull()
    _isEmbyPlayback.value = itemId != null
    refresh()
  }

  /**
   * 播放列表切换到另一集时调用：重新拉取该集的收藏状态。
   *
   * 由 [EmbyPlaybackReporter] 在检测到 mpv 的 path 变化时触发，
   * 这样"上一个 / 下一个"按钮切换后收藏图标能自动同步。
   */
  fun onItemChanged(newItemId: String?) {
    if (newItemId == null || newItemId == itemId) return
    itemId = newItemId
    refresh()
  }

  /** 播放页退出 */
  fun release() {
    toggleJob?.cancel()
    toggleJob = null
    serverId = -1L
    itemId = null
    _isEmbyPlayback.value = false
    _isFavorite.value = false
    _isToggling.value = false
  }

  /** 主动拉取一次收藏状态（比如用户从详情页返回播放页） */
  fun refresh() {
    val sid = serverId
    val id = itemId
    if (sid <= 0L || id == null) return

    scope.launch {
      val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
        ?: return@launch
      val server = runCatching { repository.getServer(sid) }.getOrNull() ?: return@launch
      if (!server.isLoggedIn) {
        _message.tryEmit("Emby 服务器未登录")
        return@launch
      }
      val item = runCatching { repository.getItem(server, id) }.getOrNull() ?: return@launch
      _isFavorite.value = item.UserData?.IsFavorite == true
    }
  }

  /**
   * 切换收藏。
   *
   * 边界处理：
   * - 非 Emby 播放 / 无 item：直接忽略
   * - 上一次请求仍在进行（[isToggling]）：忽略本次点击，避免重复请求
   * - 服务器未登录：发一条提示，不改变本地状态
   * - 请求失败：回滚本地状态并发提示
   */
  fun toggleFavorite() {
    val sid = serverId
    val id = itemId
    if (sid <= 0L || id == null) return
    if (_isToggling.value) return

    val target = !_isFavorite.value
    val previous = _isFavorite.value
    _isToggling.value = true
    // 乐观更新：先让图标动起来，失败再回滚
    _isFavorite.value = target

    toggleJob?.cancel()
    toggleJob =
      scope.launch {
        val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
        if (repository == null) {
          rollback(previous, "无法访问 Emby 服务")
          return@launch
        }
        val server = runCatching { repository.getServer(sid) }.getOrNull()
        if (server == null) {
          rollback(previous, "Emby 服务器不存在")
          return@launch
        }
        if (!server.isLoggedIn) {
          rollback(previous, "Emby 服务器未登录")
          return@launch
        }

        val result =
          runCatching {
            if (target) repository.favorite(server, id) else repository.unfavorite(server, id)
          }

        if (result.isFailure) {
          rollback(previous, "收藏操作失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
          return@launch
        }
        _message.tryEmit(if (target) "已加入收藏" else "已取消收藏")
        _isToggling.value = false
      }
  }

  private fun rollback(previous: Boolean, reason: String) {
    _isFavorite.value = previous
    _isToggling.value = false
    _message.tryEmit(reason)
  }
}
