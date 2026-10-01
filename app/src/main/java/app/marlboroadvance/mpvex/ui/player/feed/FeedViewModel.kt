package app.marlboroadvance.mpvex.ui.player.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 服务端分页时每页多少条 */
private const val PAGE_SIZE = 30

/** 滑到倒数第几条时就去把下一页拉下来 */
private const val LOAD_MORE_THRESHOLD = 5

/** 「能直接播」的条目类型，与媒体库页筛选可播放条目的口径保持一致 */
private val PLAYABLE_TYPES = listOf("Movie", "Episode", "Video", "MusicVideo", "TvProgram")

/**
 * 视界流（仿抖音竖屏上下滑连播）的状态容器。
 *
 * ── 与 Flutter 版的关键差异，写在最前面 ──
 * mpv-android 的 MPVLib 是**进程级单例**（`MPVLib.create` / `destroy` 全局一份），
 * 一个进程内不可能同时存在两个解码实例。Flutter 版是靠多实例播放内核建多条
 * 「前后各预加载 1 条」（`_preloadRange = 1`），这套在这里**物理上做不到**。
 *
 * 所以本页的取舍是：
 * - **保留滑动窗口的语义**：仍然维护 ±1 的邻居范围，窗口内的条目保持地址可用、
 *   窗口外的被划出去（见 [warmRange]）；只是「预加载」降级为地址与元数据层面，
 *   真正的解码缓冲必须等它成为当前条目才有；
 * - **换片即用**：相邻条目的直连地址在进入时就已经算好，滑动过去不需要再走
 *   网络解析，这就是这套补偿能拿到的主要收益。
 *
 * 分页加载是真的（[maybeLoadMore]），接近队尾时继续向服务端要下一页随机条目。
 */
class FeedViewModel(
  initialItems: List<FeedItem>,
  private val embyRepository: EmbyRepository,
  /** 媒体库 Id；为空表示跨库随机，届时无法续拉下一页 */
  private val libraryId: String?,
  /** 只随机收藏条目（与媒体库工具行上的「随机播放收藏」同义） */
  private val favoritesOnly: Boolean,
) : ViewModel() {

  /** 播放队列。整个列表是可观察的，追加下一页会直接反映到 UI 上的计数。 */
  val items = mutableStateListOf<FeedItem>().apply { addAll(initialItems) }

  /** 当前下标。`-1` 表示队列是空的（进页面就应该 finish，这里是兜底状态）。 */
  var index by mutableIntStateOf(if (initialItems.isEmpty()) -1 else 0)

  // ── 播放状态：由 Activity 收到 mpv 事件后写进来 ──
  var positionSec by mutableDoubleStateOf(0.0)
  var durationSec by mutableDoubleStateOf(0.0)
  var paused by mutableStateOf(false)
  var buffering by mutableStateOf(false)
  var controlsVisible by mutableStateOf(true)
  var landscape by mutableStateOf(false)

  /**
   * 每个下标对应的失败原因。
   *
   * 留着是为了在画面上给一句人话 + 重试，而不是让用户对着黑屏干等 ——
   * 这是从 Flutter 版 `_initErrors` 原样搬过来的思路。
   */
  val errors = mutableStateMapOf<Int, String>()

  /** 底部那条「低调提示条」的文案，几秒后自动清掉 */
  var hint by mutableStateOf<String?>(null)
  private var hintJob: Job? = null

  /**
   * 换片令牌：每次要加载新片 +1。
   *
   * Activity 侧用 LaunchedEffect(playToken) 观察它并据此调用 playFile，
   * 这样「UI 要换片」和「真正下发 loadfile」解耦，ViewModel 不需要持有 View。
   */
  var playToken by mutableIntStateOf(0)
    private set

  /** 当前是否有下一页可续拉 */
  private var exhausted = false
  private var loadingMore = false
  private var nextStartIndex = initialItems.size

  val current: FeedItem? get() = items.getOrNull(index)
  val hasNext: Boolean get() = index in 0 until items.lastIndex
  val hasPrev: Boolean get() = index > 0

  /**
   * ±1 的邻居窗口（即 Flutter 版的 `_cacheRange`）。
   *
   * 返回值只用来驱动「保持地址可用」这件事：窗口内外的条目在数据结构上没有区别
   * —— emby 的直连地址是一次性算好的，不需要再second请求。窗口语义保留是为了
   * 将来若换成多实例内核时，这里就是「该预创建 / 该释放」的唯一切面。
   */
  fun warmRange(): IntRange {
    if (index < 0) return IntRange.EMPTY
    return (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(items.lastIndex)
  }

  fun goNext(): Boolean {
    if (!hasNext) {
      maybeLoadMore()
      return false
    }
    maybeLoadMore()
    moveTo(index + 1)
    return true
  }

  fun goPrev(): Boolean {
    if (!hasPrev) return false
    moveTo(index - 1)
    return true
  }

  /**
   * 挪到新下标。
   *
   * 这里**不能**给 [playToken] 加一 —— 换页时真正该做的是让实例池把相邻那条准备好
   * （`ExoPlayerPool.onPageChanged`），而 playToken 一旦变化，Activity 侧会走
   * `reloadCurrent` 把这一槽的实例释放重建，刚攒好的缓冲全没了，「滑过去就有画面」
   * 也就无从谈起。令牌只服务于「重载当前这条」这一件事。
   */
  private fun moveTo(newIndex: Int) {
    if (newIndex == index || newIndex !in items.indices) return
    index = newIndex
    positionSec = 0.0
    // durationSec / buffered / paused **刻意不在这里归零**。
    //
    // 归零会让底部进度条在一帧之内从「滑块」塌成「一条细线」，下一拍心跳再把它撑
    // 回来 —— 一缩一放就是用户看到的「每切一条进度条就闪一次」。这一条的真实状态
    // 往往就在旁边的实例上（相邻页早就 prepared 好了），由 Activity 在翻页的同一帧
    // 里用 [applySnapshot] 覆盖；真的拿不到实例时也由它统一清回加载态。
  }

  /**
   * 用一份实例快照覆盖播放状态（翻页时由 Activity 在同一帧内调用）。
   *
   * [snapshot] 为 null 表示这一页还没有实例 —— 此时把状态清回「未知」，UI 显示加载态。
   * 这条路径只在「滑到一条还没准备好的新页」时走到，是不可避免的那一小段等待。
   */
  internal fun applySnapshot(snapshot: FeedPlaybackSnapshot?) {
    if (snapshot == null) {
      durationSec = 0.0
      positionSec = 0.0
      buffered = false
      paused = false
      return
    }
    durationSec = snapshot.durationMs / 1000.0
    positionSec = snapshot.positionMs / 1000.0
    paused = !snapshot.playing
    buffered = snapshot.frameVisible
  }

  /**
   * Pager 翻页到这里时反过来通知 ViewModel —— 与 [goNext] / [goPrev] 的区别只在于
   * **触发方不同**：那两个是「播完了自动走」，这个是「人手动滑走了」。
   *
   * 幂等很重要：UI 侧存在「页码变化 → 写回下标」和「下标变化 → 滚到该页」两条
   * 互相反馈的路径，一旦这个方法不是幂等的，两个 LaunchedEffect 就会互相打起来。
   */
  fun syncFromPager(newIndex: Int) {
    if (newIndex in items.indices && newIndex != index) moveTo(newIndex)
    // 手动滑的时候不会经过 goNext，续拉的触发点得自己补上
    if (newIndex >= items.lastIndex - 3) maybeLoadMore()
  }

  /** 重载当前视频前记下的进度，重载完要跳回去 */
  var reloadSeekSec by mutableDoubleStateOf(0.0)
    private set

  /**
   * 取走「重载前的位置」并清零。
   *
   * 一次性消费很重要：不清零的话，下一次正常换片也会被强行跳到这次记下的进度上。
   */
  fun consumeReloadSeek(): Double {
    val v = reloadSeekSec
    reloadSeekSec = 0.0
    return v
  }

  /**
   * 当前这条是不是**已经真正把帧画到屏幕上了** —— 加载态的结束判据。
   *
   * 这个判据被试错着往后挪过两次，每一次「晚一点撤」都会好一点，原因值得记住：
   * 1. 内核 `STATE_READY` 只是「解码器建好了、缓冲够了」，早于任何一帧；
   * 2. `Player.Listener.onRenderedFirstFrame` 是「这一帧**交给了输出面**」，
   *    而 TextureView 还得再走一遍自己的绘制流程才把像素显示出来。
   * 所以真正的判据是**帧真的画到了那块 TextureView 上**
   * （`SurfaceTextureListener.onSurfaceTextureUpdated`，见 `ExoPlayerPool.frameOnScreen`）。
   * 判据早一步，用户看到的就是「转圈消失 → 黑屏 → 画面浮现」那一下闪烁。
   */
  var buffered by mutableStateOf(false)

  fun requestReload() {
    val keep = positionSec
    reloadSeekSec = if (keep > 1.0) keep else 0.0
    errors.remove(index)
    positionSec = 0.0
    durationSec = 0.0
    buffered = false
    playToken++
  }

  /**
   * 接近队尾时续拉下一页。
   *
   * 服务端 `SortBy=Random` 是每次请求独立随机，同一 offset 段不会重复。
   * 拉回来的条目按 Id 去重后追加 —— 去重是必须的：连续两次随机抽出同一条的情况
   * （尤其小库）很常见，重复条目会让用户以为「这条刚刚刷过了」。
   */
  private fun maybeLoadMore() {
    if (exhausted || loadingMore || libraryId == null) return
    if (index < items.lastIndex - LOAD_MORE_THRESHOLD) return
    loadingMore = true
    viewModelScope.launch {
      runCatching {
        val server = embyRepository.ensureLoggedIn(embyRepository.awaitCurrentServer())
          ?: return@runCatching
        val result = embyRepository.getItems(
          server = server,
          parentId = libraryId,
          includeItemTypes = PLAYABLE_TYPES,
          sortBy = "Random",
          sortOrder = "Ascending",
          startIndex = nextStartIndex,
          limit = PAGE_SIZE,
          recursive = true,
          mediaTypes = listOf("Video"),
          isFolder = false,
          isFavorite = if (favoritesOnly) true else null,
        )
        val existing = items.mapTo(HashSet()) { it.itemId }
        val added = result.Items.mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          if (id in existing) return@mapNotNull null
          FeedItem(
            itemId = id,
            title = item.Name ?: "未命名",
            url = embyRepository.videoStreamUrl(server, id, static = true),
            isFavorite = item.UserData?.IsFavorite == true,
          )
        }
        nextStartIndex += result.Items.size
        if (result.Items.isEmpty() || added.isEmpty()) exhausted = true
        items.addAll(added)
      }.onFailure {
        // 续拉失败不影响当前播放：既不提示也不重试，用户滑到尽头自然停住
        exhausted = true
      }
      loadingMore = false
    }
  }

  /**
   * 收藏 / 取消收藏：乐观更新 → 请求 → 用服务端状态校正，失败回滚。
   *
   * 与 Flutter 版 `_toggleEmbyFavorite` 同一套节律：先改 UI 让点击有即时反馈，
   * 再拿服务器的真实结果盖上去。
   */
  fun toggleFavorite() {
    val idx = index
    val item = items.getOrNull(idx) ?: return
    val target = !item.isFavorite
    items[idx] = item.copy(isFavorite = target)
    viewModelScope.launch {
      val result = runCatching {
        val server = embyRepository.ensureLoggedIn(embyRepository.awaitCurrentServer())
          ?: error("未连接到服务器")
        embyRepository.setFavorite(server, item.itemId, target)
      }
      val confirmed = result.getOrNull()?.IsFavorite
      if (confirmed == target) {
        showHint(target.toStringForHint())
      } else {
        // 回滚
        items.getOrNull(idx)?.let { items[idx] = it.copy(isFavorite = !target) }
        showHint("操作失败：${result.exceptionOrNull()?.message ?: "服务器未接受"}")
      }
    }
  }

  private fun Boolean.toStringForHint() = if (this) "已加入收藏" else "已取消收藏"

  /** 弹一条看得见但不抢戏的提示，[keep] 毫秒后自动消失 */
  fun showHint(text: String, keep: Long = 3000L) {
    hintJob?.cancel()
    hint = text
    hintJob = viewModelScope.launch {
      delay(keep)
      hint = null
    }
  }

  /** 当前这条是否正在经历失败（画面上据此画错误态而不是转圈） */
  fun errorOf(idx: Int): String? = errors[idx]

  companion object {
    fun factory(
      initialItems: List<FeedItem>,
      embyRepository: EmbyRepository,
      libraryId: String?,
      favoritesOnly: Boolean,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FeedViewModel(initialItems, embyRepository, libraryId, favoritesOnly) as T
    }
  }
}
