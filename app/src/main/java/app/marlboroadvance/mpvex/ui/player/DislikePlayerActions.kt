package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.domain.dislike.DislikeKey
import app.marlboroadvance.mpvex.domain.dislike.DislikeMark
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.dislike.DislikeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * 播放页的「标记为不喜欢」动作中心 —— mpv / GSY / 视界流**三套播放器共用这一个**。
 *
 * ## 为什么不直接在各播放器里读 [DislikeRepository]
 *
 * 三处都要同一套东西：当前条目的 key、选中态、单击切换、长按弹框。
 * 各写一份会出现「mpv 能标、GSY 标不了」「视界流切条后状态不刷新」这类不一致。
 * 这里做**唯一入口**，三处只管调 [bind] / [toggle] / [saveRemark] 并订阅 [isMarked]。
 *
 * ## 状态查询复杂度（需求点名的那一点）
 *
 * 切视频时 UI 只做两件事：
 * 1. [bind] 一次 —— **同步方法、内部一次 HashMap 查找**，不查库、不起协程；
 * 2. 订阅 [isMarked]（一个 `StateFlow`）。
 *
 * 所以按钮选中态与视频切换**在同一帧**完成，快速连播时不会看到「先亮后暗」。
 * 另外还有两道保险：
 * · [bind] 对相同 key 直接返回 ⇒ 连播反复绑定同一条时**零查询**；
 * · 需要一次取多条时用 `snapshotOf`（见 [DislikeRepository]）批量 O(k) 拿完。
 *
 * ## 生命周期
 *
 * 进程级单例，不持有 Activity/Context，不需要显式释放。
 * 播放页退出时调 [clear] 把状态复位 —— 否则下次进来会**短暂显示上一条视频的标记**，
 * 闪一下错的状态比什么都不显示更糟。
 */
object DislikePlayerActions {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _isMarked = MutableStateFlow(false)
  private val _currentMark = MutableStateFlow<DislikeMark?>(null)
  private val _currentKey = MutableStateFlow<DislikeKey?>(null)
  private val _toast = MutableStateFlow<String?>(null)

  /** 当前条目是否已标记 —— **播放器按钮判断选中态只看这个**。 */
  val isMarked: StateFlow<Boolean> = _isMarked.asStateFlow()

  /** 当前条目的完整标记（含备注）。未标记为 null。 */
  val currentMark: StateFlow<DislikeMark?> = _currentMark.asStateFlow()

  /** 当前条目的 key。null = 本次播放没有可标记的条目 ⇒ 按钮应隐藏。 */
  val currentKey: StateFlow<DislikeKey?> = _currentKey.asStateFlow()

  /**
   * 待弹的一次性提示。
   *
   * ⚠️ 用 StateFlow 而不是 SharedFlow：提示**可能被漏收集**（弹框动画还没起来就切换了），
   * SharedFlow 那种「只发一次」就永久丢了；StateFlow 保留最新一条，下次收集一定看得到。
   * 弹过后由 UI 调 [consumeToast] 清掉。
   */
  val toast: StateFlow<String?> = _toast.asStateFlow()

  /**
   * 当前条目的冗余标题 / 封面，标记时一并存进库里（管理页要显示，不必再联网查）。
   *
   * ⚠️ 它们是**当前这条的属性**，不是全局缓存 —— 见 [bind] 里关于覆盖方式的说明。
   */
  private var pendingTitle: String = ""
  private var pendingImage: String? = null

  /** 正在跑的元数据补全协程。换片时要掐掉，否则慢网络下会拿上一片的名字补到这一片上。 */
  private var metaJob: kotlinx.coroutines.Job? = null

  /**
   * 切换到另一条目。**每次播放列表里的新条目都要调。**
   *
   * 传 null 表示「这条没法标记」⇒ 按钮隐藏。
   *
   * ## 元数据（标题 / 封面）为什么必须**无条件覆盖**
   *
   * 原来是「非空才覆盖」（`title.takeIf { isNotBlank() }?.let { … }`），
   * 看着像"别把已有的好值冲掉"，实际是**张冠李戴**：
   * 看完 A（有片名）再看 B（B 这条没传片名）⇒ B 会被标成 A 的名字。
   * 播放页通常根本拿不到片名，这个"缓存"只会把错误名字盖到别的片子上。
   * ⇒ 片名是**当前这条**的属性，换片就该换掉；缺了就由 [ensureMeta] 去补。
   */
  fun bind(key: DislikeKey?, title: String = "", imageUrl: String? = null) {
    if (key == _currentKey.value) return // ★ 同一条：状态没变，直接返回，零查询

    pendingTitle = title
    pendingImage = imageUrl
    _currentKey.value = key
    refreshFromStore()
    if (key != null) ensureMeta(key)
  }

  /**
   * 片名 / 封面缺了就回服务器补一次。
   *
   * ## 为什么必须有这一步
   *
   * 播放页手上**只有 `serverId + itemId`**（详情页才会持有完整的 EmbyItem），
   * 所以从播放器里标记的记录 `title` 是空串 —— 管理页显示成「（无标题）」、
   * 缩略图也没有，用户根本认不出自己标的是哪一部。
   *
   * ## 为什么是"能补就补、补不到就算了"
   *
   * 这只是**展示信息**，缺了不至于让功能不可用；而它要走一次网络。
   * 服务器没登录 / 条目被删 / 请求超时都只能放弃 ——
   * 为一个标题把播放页卡住或弹错误，是拿主功能给边角料让路。
   * 补不到的记录还有管理页那次自愈兜底（见 `DislikeManageScreen`）。
   */
  private fun ensureMeta(key: DislikeKey) {
    if (pendingTitle.isNotBlank() && pendingImage != null) return
    metaJob?.cancel()
    metaJob = scope.launch {
      val repo = repoOrNull() ?: return@launch
      val emby =
        runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
          ?: return@launch
      val server = runCatching { emby.getServer(key.serverId) }.getOrNull() ?: return@launch
      if (!server.isLoggedIn) return@launch
      val item = runCatching { emby.getItem(server, key.itemId) }.getOrNull() ?: return@launch
      val name = item.Name?.takeIf { it.isNotBlank() } ?: return@launch
      // ⚠️ 拿到结果后**必须再确认一次**还停在同一条上：
      //  网络很慢时用户早切到下一集了，拿 A 的名字去补 B 就是上面说的张冠李戴。
      if (_currentKey.value != key) return@launch

      val image = item.ImageTags["Primary"]?.let { tag ->
        emby.imageUrl(server, key.itemId, tag = tag, maxWidth = 320)
      }
      pendingTitle = name
      if (image != null) pendingImage = image

      // 这条如果**已经被标记过**（库里有记录），顺手把标题补上 ——
      // 否则要等用户再点一次标记才会带上名字，而他已经不会再点了。
      // ⚠️ 走 `updateMeta` 而不是 `mark`：补全不是一次新的表态，不能刷新「标记时间」。
      if (repo.markOf(key) != null) {
        repo.updateMeta(key, name, image ?: pendingImage)
      }
    }
  }

  /**
   * 单击：未标记 → 标记；已标记 → 取消标记。
   *
   * **单击永不弹框** —— 弹框会挡住画面，播放器里点一下就被拦一次很难受。
   * 想写理由用长按。
   */
  fun toggle() {
    val key = _currentKey.value
    // ⚠️ 拿不到 key 时必须**给个说法**，不能静默 return。
    //  静默返回的后果是用户点按钮「什么都没发生」，只会以为功能坏了 ——
    //  而实际原因是这次播放没有可标记的条目（本地文件 / 非 Emby 来源）。
    if (key == null) {
      _toast.value = UNAVAILABLE_MESSAGE
      return
    }
    val repo = repoOrNull() ?: return
    if (repo.isDisliked(key)) {
      repo.unmark(key)
      _toast.value = "已取消标记"
    } else {
      repo.mark(key, pendingTitle, reason = null, imageUrl = pendingImage)
      _toast.value = "已标记为不喜欢"
    }
    refreshFromStore()
  }

  /**
   * 保存备注。
   *
   * **即使当前未标记，保存备注也等于同时标记** —— 用户主动写了「剧情烂」，
   * 却发现还得再点一下才生效，是很别扭的两步。
   */
  fun saveRemark(reason: String) {
    val key = _currentKey.value
    if (key == null) {
      _toast.value = UNAVAILABLE_MESSAGE
      return
    }
    val repo = repoOrNull() ?: return
    val trimmed = reason.trim()
    repo.mark(key, pendingTitle, reason = trimmed, imageUrl = pendingImage)
    _toast.value = if (trimmed.isEmpty()) "已标记为不喜欢" else "备注已保存"
    refreshFromStore()
  }

  /** 长按弹框的初始值 = 上次写的备注（没有则空串），避免用户改个字都要重打。 */
  fun currentReason(): String = _currentMark.value?.reason.orEmpty()

  /** 弹过提示后清掉，避免下次又弹同一个。 */
  fun consumeToast() {
    _toast.value = null
  }

  /**
   * 这次播放没法标记时的提示语。
   *
   * 三套播放器共用这一句：mpv / GSY / 视界流都可能出现「没有可标记的条目」
   * （本地文件、非 Emby 来源、服务器还没连上）。各写一份文案迟早不一致。
   */
  const val UNAVAILABLE_MESSAGE = "当前视频没有可标记的来源，无法标记不喜欢"

  /** 复位。播放页退出时调。 */
  fun clear() {
    _currentKey.value = null
    _currentMark.value = null
    _isMarked.value = false
    pendingTitle = ""
    pendingImage = null
  }

  /** 从内存快照重新读一次当前条目的状态（O(1)）。 */
  private fun refreshFromStore() {
    val key = _currentKey.value
    if (key == null) {
      _currentMark.value = null
      _isMarked.value = false
      return
    }
    val mark = repoOrNull()?.markOf(key)
    _currentMark.value = mark
    // ⚠️ 必须看 `active` 而不是「记录在不在」：取消标记是**保留记录 + active=false**
    // （好让下次重新标记时能带出上次写的理由），用 `mark != null` 判会让取消后按钮仍显示选中。
    _isMarked.value = mark?.active == true
  }

  private fun repoOrNull(): DislikeRepository? =
    runCatching { get<DislikeRepository>(DislikeRepository::class.java) }.getOrNull()

  init {
    // 外部（详情页 / 管理页批量取消）改了同一条的状态时，按钮跟着变。
    // 订阅 repository 的全量流 ⇒ 一处变更全 App 同步，不需要任何"通知播放器"的代码。
    // 这里刻意**不**按 key 过滤：过滤后仍要读全量列表才能找到，不如直接读完整列表
    // （列表在内存里，读取是零 IO 的）。
    scope.launch {
      val repo = repoOrNull() ?: return@launch
      repo.all.collect { list ->
        val key = _currentKey.value ?: return@collect
        val mark = list.firstOrNull { it.key == key }
        _currentMark.value = mark
        // 同上：判 active，不然外部「取消标记」后按钮不会灭。
        _isMarked.value = mark?.active == true
      }
    }
  }
}