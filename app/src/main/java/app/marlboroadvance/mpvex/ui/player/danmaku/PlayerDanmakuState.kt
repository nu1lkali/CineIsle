package app.marlboroadvance.mpvex.ui.player.danmaku

import android.util.Log
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuMatcher
import app.marlboroadvance.mpvex.domain.danmaku.DandanAnime
import app.marlboroadvance.mpvex.domain.danmaku.DandanEpisode
import app.marlboroadvance.mpvex.preferences.DanmakuDensity
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.DanmakuRenderingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 渲染引擎要用的显示参数。 */
data class DanmakuRenderParams(
  val enabled: Boolean = false,
  val alphaPercent: Int = 100,
  val fontScale: Float = 1f,
  val speedScale: Float = 1f,
  val areaRatio: Float = 1f,
  /** 同屏最大条数；0 = 不限。 */
  val maxOnScreen: Int = 0,
  /** 防重叠（同轨道会撞车时换轨道）。 */
  val preventOverlapping: Boolean = true,
)

/**
 * 弹幕在播放页的状态与加载。
 *
 * ## 为什么是独立类而不是塞进 PlayerViewModel
 *
 * 弹幕是**新增的一整块**功能（状态、加载、匹配、过滤），而 PlayerViewModel 已经有两千多行。
 * 更重要的是：这个类只依赖 [DanmakuPreferences]（Koin 注入）和 [CoroutineScope]，
 * 不碰 mpv、不碰 Activity —— 所以能脱离播放器单测。
 *
 * ## 生命周期
 *
 * 由 [PlayerActivity] 在 `onCreate` 建、`onDestroy` 调 [dispose]。
 * 换集时调 [onEpisodeChanged]，它会**取消上一次加载**再按新片名重新匹配 ——
 * ⚠️ 不取消的话，上一集的慢响应会回来把新一集的弹幕覆盖掉（典型竞态）。
 */
class PlayerDanmakuState(
  private val preferences: DanmakuPreferences,
  private val scope: CoroutineScope,
) {

  private companion object {
    const val TAG = "PlayerDanmaku"
    /** 密度档位 → 同屏条数上限（0 = 不限）。 */
    val DENSITY_TO_MAX = mapOf(
      DanmakuDensity.Sparse to 15,
      DanmakuDensity.Normal to 30,
      DanmakuDensity.Dense to 60,
    )
  }

  private val _comments = MutableStateFlow<List<DanmakuComment>>(emptyList())
  val comments: StateFlow<List<DanmakuComment>> = _comments.asStateFlow()

  private val _sheet = MutableStateFlow(DanmakuSheetState())
  val sheet: StateFlow<DanmakuSheetState> = _sheet.asStateFlow()

  /** 当前集的稳定标识；变了就重建弹幕引擎。 */
  private val _episodeKey = MutableStateFlow("")
  val episodeKey: StateFlow<String> = _episodeKey.asStateFlow()

  /**
   * 用户**手动**选定的番（null = 没手动选过）。
   *
   * 存在的意义见 [DanmakuPreferences.rememberSelection]：手动选过之后切集，
   * 应当沿用这部番、只换集号，而不是回头去按片名重新自动匹配。
   * 这是「记住选择」和「自动匹配」两种行为之间唯一需要持久的状态。
   */
  private var manualAnime: DandanAnime? = null

  /**
   * 当前这一集所属「剧」的记忆键（Emby 是 SeriesId，本地播放为 null）。
   *
   * 时间轴偏移按它分别记住：换片就要换键，否则会把上一部的偏移套到这一部上。
   */
  private var currentSeriesKey: String? = null

  /**
   * 渲染参数。
   *
   * 单独一个流而不是在 Composable 里逐个 collect 偏好：弹幕叠层每秒要重组多次，
   * 每次都去读五个偏好会明显拖慢；合成一个对象后，值不变就不重组。
   */
  private val _renderParams = MutableStateFlow(readRenderParams())
  val renderParams: StateFlow<DanmakuRenderParams> = _renderParams.asStateFlow()

  private fun readRenderParams(): DanmakuRenderParams = DanmakuRenderParams(
    enabled = preferences.enabled.get() &&
      preferences.engine.get() != DanmakuRenderingEngine.Off &&
      _comments.value.isNotEmpty(),
    alphaPercent = preferences.alphaPercent.get(),
    fontScale = preferences.fontScale.get(),
    speedScale = preferences.speedScale.get(),
    areaRatio = preferences.areaRatio.get(),
    maxOnScreen = DENSITY_TO_MAX[preferences.density.get()] ?: 30,
    preventOverlapping = preferences.preventOverlapping.get(),
  )

  private fun refreshRenderParams() {
    _renderParams.value = readRenderParams()
  }

  /**
   * 把「偏好 + 当前已加载情况」重新灌进面板状态（[sheet]）。
   *
   * ## ⚠️ 为什么必须是这一条通路，而不是各处 `.copy(...)` 拼补
   *
   * 面板是 `DanmakuSheetState` 的一整个快照，字段分两类：
   * - **偏好类**（开关、滑块、凭证…）：真值在 [DanmakuPreferences] 里；
   * - **会话类**（已匹配的集名、条数、loading、error）：真值在这个类里。
   *
   * 原先 [setEnabled] 只写偏好、不刷面板，而其它 setter 只改某一个字段 ——
   * 结果是面板上的 `enabled` 永远停在构造时的 `false`（`readSheetState()` 全项目零调用）。
   * 统一走这里，就不会再出现「某个 setter 忘了同步自己那个字段」。
   *
   * ⚠️ v117：设为 public 是因为播放面板每次**打开**都要主动刷一次
   *    （见 `PlayerSheets` 的 `Sheets.Danmaku` 分支）—— 偏好的真值可能被
   *    「设置-弹幕」页改过，而那个页的开关是**直接写 preference** 的，
   *    不走本类的 setter；`_sheet` 的初始值是 `DanmakuSheetState()`
   *    （enabled=false），不刷就会出现「设置里开了弹幕、自动匹配也拉到了
   *    弹幕，播放面板的总开关却是灭的」。
   */
  fun refreshSheet() {
    _sheet.value = readSheetState()
  }

  private var loadJob: Job? = null

  /** 当前凭证（host + AppId/AppSecret 组成）。每次现读，改了立即生效。 */
  private fun host(): String = preferences.host.get().ifBlank { DanmakuClient.DEFAULT_HOST }

  private fun credentials() = DanmakuClient.Credentials(
    appId = preferences.appId.get(),
    appSecret = preferences.appSecret.get(),
  )

  /** 阻塞词列表（每次从偏好切一次，别在渲染路径上反复 split）。 */
  private fun blockWords(): List<String> =
    preferences.blockWords.get().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

  fun onEpisodeChanged(seriesKey: String?, title: String, episodeIndex: Int) {
    // ⚠️ 片名和集号都没有 = **这一集没有任何可用的匹配信息**（例如片名还没解析出来）。
    //    此时什么都不能动：把 `pendingTitle` 覆成空串，会让后续「打开开关」的补拉
    //    永远死在「还没拿到片名」这条分支上；把 `_episodeKey` 覆成空串，会让引擎
    //    在已有弹幕的情况下被 stop 掉。直接返回，等下一次带片名的调用。
    //    ⚠️ 但首次起播是 `player.playFile(uri)`，压根不走这里 —— 见
    //    [PlayerActivity.handleFileLoaded] 里那次补调。
    if (title.isBlank() && episodeIndex == 0) {
      Log.w(TAG, "onEpisodeChanged 跳过：片名与集号都为空（seriesKey=$seriesKey）")
      return
    }
    // ⚠️ 先取消上一次：旧请求返回后会把这里覆盖成旧片的弹幕。
    loadJob?.cancel()
    loadJob = null
    _comments.value = emptyList()
    refreshRenderParams()

    // ── 换剧的处理：手动选过的番、时间轴偏移都要跟着换 ──
    //
    // ① 手动选番只在**同一部剧**内跨集沿用。换剧了必须作废 ——
    //    否则在看 A 剧时手动选了番，切到 B 剧还会拿 A 的番去匹配，
    //    用户看到的是完全无关的弹幕（比"没弹幕"更糟：错得毫无提示）。
    // ② 时间轴偏移按剧存：换剧后 [offsetForCurrent] 自然读到 B 剧自己的值。
    val seriesChanged = seriesKey != currentSeriesKey
    currentSeriesKey = seriesKey
    if (seriesChanged) manualAnime = null

    // 片名变了 ⇒ 引擎必须重建（叠层靠 episodeKey 判断）。
    //
    // ⚠️ 两样东西都缺（片名空、集号 0）时，**不能**把 key 定成 "|0"：
    //    那样「播放页重进一次」和「换了一部没有任何标识的片」拿到的是同一个 key，
    //    `LaunchedEffect` 认为没换集，于是不重建引擎 —— 新片沿用旧片的弹幕。
    //    这种情况下先留空，等 [loadEpisode] / [loadForTitle] 拉到数据后由
    //    [syncEpisodeKey] 用 dandanplay 侧的真实身份补上（那才是稳定的）。
    _episodeKey.value = if (title.isBlank() && episodeIndex == 0) "" else "${seriesKey ?: title}|$episodeIndex"
    loadedEpisodeIdentity = ""

    // ⚠️ 记在开关判断**之前**：开关关着时也要记，否则「关着看了一段、再打开开关」
    //    就没人知道该拉哪一集（见 [pendingTitle] 的说明）。
    pendingTitle = title
    pendingEpisodeIndex = episodeIndex

    if (!preferences.enabled.get() || preferences.engine.get() == DanmakuRenderingEngine.Off) {
      _sheet.value = _sheet.value.copy(matchedTitle = null, commentCount = 0, error = null)
      return
    }
    // ── 「记住手动选择」：手动选过的番，切集后沿用同一部番、只换集号 ──
    //
    // ⚠️ 这一段必须在 autoMatch 判断**之前**：用户手动选番之后又把「自动匹配」关掉，
    // 是完全合理的选择（「我这季就固定用这一部番，别去搜了」）。放在后面的话，
    // 关掉自动匹配 = 连手动选的也一起没了，每集都得重新点。
    // 但「手动点的那一集」一定要丢 —— 下一集的集号已经变了，沿用等于放错弹幕。
    val remembered = manualAnime
    if (remembered != null && preferences.rememberSelection.get()) {
      loadFromAnime(remembered, episodeIndex)
      return
    }
    if (!preferences.autoMatch.get()) {
      _sheet.value = _sheet.value.copy(matchedTitle = null, commentCount = 0, error = null)
      return
    }
    loadForTitle(title, episodeIndex)
  }

  /**
   * 沿用已选定的番，按新集号取一集。
   *
   * 与 [loadForTitle] 分开是因为错误文案不一样：「片名搜不到」和
   * 「这部番里没有第 N 集」是两回事，混成一句会让用户以为凭证又坏了。
   */
  private fun loadFromAnime(anime: DandanAnime, episodeIndex: Int) {
    loadJob?.cancel()
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    loadJob = scope.launch {
      val episodes = anime.episodes.ifEmpty {
        DanmakuClient.getEpisodes(host(), anime.animeId, credentials())
      }
      val episode = DanmakuMatcher.pickEpisode(episodes, episodeIndex)
      if (episode == null) {
        _sheet.value = _sheet.value.copy(
          loading = false,
          error = "「${anime.animeTitle}」里找不到第 ${episodeIndex + 1} 集",
        )
        return@launch
      }
      loadEpisode(anime.animeTitle, episode)
    }
  }

  /** 按片名自动匹配并加载。 */
  fun loadForTitle(title: String, episodeIndex: Int) {
    loadJob?.cancel()
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    loadJob = scope.launch {
      val matched =
        DanmakuMatcher.autoMatch(
          host(),
          title,
          episodeIndex,
          credentials(),
          withRelated = preferences.withRelated.get(),
        )
      // 屏蔽词在这层就滤掉：不给渲染引擎塞无用条目。
      val filtered = DanmakuMatcher.applyBlockWords(matched.comments, blockWords())
      _comments.value = filtered
      // 自动匹配同样要定一个稳定 key（搜索路径拿到的分集 id 就是这集的真身份）
      matched.episode?.let { syncEpisodeKey(matched.anime?.animeTitle ?: title, it) }
      refreshRenderParams()
      _sheet.value = _sheet.value.copy(
        loading = false,
        matchedTitle = matched.episode?.episodeTitle?.ifBlank { matched.anime?.animeTitle }
          ?: matched.anime?.animeTitle,
        commentCount = filtered.size,
        error = matched.error,
      )
      Log.i(TAG, "弹幕加载完成：${filtered.size} 条（${matched.error ?: "ok"}），key=${_episodeKey.value}")
    }
  }

  /** 面板里的手动搜番。 */
  suspend fun search(keyword: String): List<DandanAnime> {
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    val result = DanmakuMatcher.search(host(), keyword, credentials())
    _sheet.value = _sheet.value.copy(
      loading = false,
      error = if (result.isEmpty()) DanmakuClient.lastError ?: "没搜到「$keyword」" else null,
    )
    return result
  }

  /**
   * 手动选定一个番 ⇒ 按**当前播放的集号**取它那一集。
   *
   * 不用搜索结果里的第一集：用户搜的是「系列」，要匹配的是「当前这一集」。
   */
  suspend fun pick(anime: DandanAnime, episodeIndex: Int) {
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    val episodes = anime.episodes.ifEmpty { DanmakuClient.getEpisodes(host(), anime.animeId, credentials()) }
    val episode = DanmakuMatcher.pickEpisode(episodes, episodeIndex)
    if (episode == null) {
      _sheet.value = _sheet.value.copy(loading = false, error = "「${anime.animeTitle}」里找不到对应的集")
      return
    }
    manualAnime = anime
    loadEpisode(anime.animeTitle, episode)
  }

  /**
   * 拉某部番的分集列表 —— 供面板做「手动选集」。
   *
   * 为什么不能直接用搜索结果里的 `episodes`：官方搜索端点**不返回分集**，
   * 只有部分自建服务端会带。所以这里缺了就补一次 `bangumi` 请求。
   */
  suspend fun episodeList(anime: DandanAnime): List<DandanEpisode> =
    anime.episodes.ifEmpty { DanmakuClient.getEpisodes(host(), anime.animeId, credentials()) }

  /**
   * 用户手动点了某一集 ⇒ 直接用这一集，不再猜集号。
   *
   * ⚠️ 这条路径**必须绕开集号匹配**：用户已经明确说了「就要这一集」，
   * 再按集号挑一次只会把他选的集换掉（跨季 / 总集数偏移时尤其明显）。
   */
  suspend fun pickEpisode(anime: DandanAnime, episode: DandanEpisode) {
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    manualAnime = anime
    loadEpisode(anime.animeTitle, episode)
  }

  /**
   * 真正拉弹幕并落到渲染状态（两条手动路径共用）。
   *
   * 时间轴偏移在这里**一次性**应用：改偏移时重新过一遍这份原始列表（见 [setTimeOffset]），
   * 而不是在渲染层每帧加 —— 后者会让每一条弹幕的时间都变得不可缓存。
   */
  private suspend fun loadEpisode(animeTitle: String, episode: DandanEpisode) {
    lastLoadedEpisode = episode
    lastLoadedAnimeTitle = animeTitle
    val raw =
      DanmakuMatcher.fetchByEpisode(
        host(),
        episode.episodeId,
        credentials(),
        withRelated = preferences.withRelated.get(),
      )
    val filtered = applyOffset(DanmakuMatcher.applyBlockWords(raw, blockWords()))
    _comments.value = filtered
    // ⚠️ 先定 key 再刷渲染参数：灌数据那层靠 key 判断「是不是新的一集」。
    syncEpisodeKey(animeTitle, episode)
    refreshRenderParams()
    _sheet.value = _sheet.value.copy(
      loading = false,
      matchedTitle = episode.episodeTitle.ifBlank { animeTitle },
      commentCount = filtered.size,
      error = if (filtered.isEmpty()) "这一集没有弹幕" else null,
    )
  }

  /**
   * 拉到数据之后，用 dandanplay 侧的真实身份给 [episodeKey] 补一个稳定值。
   *
   * ## 为什么需要这一步
   *
   * key 原本由「剧集键 + 片名 + 集号」拼出来，而本地 / 网盘播放时前两者都可能是空串，
   * 于是 key 退化成 `"|0"` —— 日志里看到的 `key=` 就是这种情况。
   * 更麻烦的是它**并不可靠**：同一个空 key 会被不同片共用，换片时引擎不重建。
   *
   * 拿到 `episodeId` 之后就有真正唯一的东西了（dandanplay 里一集一个 id），
   * 拼成 `"番名#episodeId"` 既有可读性又不会撞。
   *
   * ⚠️ 只在 `episodeId > 0` 时才覆盖：搜索接口返回的番不带分集 id 时不能把 key 写成空。
   */
  private fun syncEpisodeKey(animeTitle: String, episode: DandanEpisode) {
    if (episode.episodeId <= 0) return
    val identity = "${animeTitle}#${episode.episodeId}"
    if (identity == loadedEpisodeIdentity) return
    loadedEpisodeIdentity = identity
    _episodeKey.value = identity
  }

  /** 上一份**未经偏移、未经屏蔽词**的弹幕（改偏移 / 改屏蔽词时重新派生，不能在上一次结果上叠加）。 */
  private var lastRawComments: List<DanmakuComment> = emptyList()
  private var lastLoadedEpisode: DandanEpisode? = null
  private var lastLoadedAnimeTitle: String = ""

  /**
   * 当前正在播的这一集在 dandanplay 里的稳定标识（`"番名#episodeId"`）。
   *
   * 只用来给 [episodeKey] 拼一个能在「拉到数据之后」才确定的身份 ——
   * 光靠片名 + 集号不够：本地播放时两者都可能是空串（详见 [syncEpisodeKey]）。
   */
  private var loadedEpisodeIdentity: String = ""

  /**
   * 当前正在播的这一集的「片名 + 集号」，**无论弹幕开关是开是关都记**。
   *
   * ## 为什么必须有这一份
   *
   * 拉弹幕的唯一入口是 [onEpisodeChanged]（切集时调用）。但如果用户开播时弹幕是关的、
   * 后来在抽屉里把开关打开 —— 没有任何「换集」事件发生，就**永远不会去拉**：
   * 开关亮了、副标题写着「未匹配」、画面上一条弹幕都没有。
   *
   * 所以这里无条件记录目标，[setEnabled] 打开时拿它补拉一次。
   * ⚠️ 不能改用 [lastLoadedEpisode]：那只在**成功加载之后**才赋值，
   *   而「从没加载过」恰恰就是要补拉的场景。
   */
  private var pendingTitle: String = ""
  private var pendingEpisodeIndex: Int = 0

  /** 当前剧实际生效的偏移（按剧覆盖优先，没有覆盖就跟随全局）。 */
  private fun offsetForCurrent(): Int = preferences.timeOffsetFor(currentSeriesKey)

  /** 按当前剧的偏移量平移时间轴；偏移为 0 时原样返回（零拷贝）。 */
  private fun applyOffset(list: List<DanmakuComment>): List<DanmakuComment> {
    val offsetMs = offsetForCurrent()
    if (offsetMs == 0 || list.isEmpty()) {
      lastRawComments = list
      return list
    }
    val delta = offsetMs / 1000.0
    lastRawComments = list
    // ⚠️ 负数时间要裁掉：弹幕库对 <0 的条目不保证行为一致（有的丢、有的堆在 0 秒）。
    return list.map { it.copy(timeSec = (it.timeSec + delta).coerceAtLeast(0.0)) }
  }

  /**
   * 改时间轴偏移：**只作用于当前这部剧**，并就地重算当前这份弹幕（不重新联网）。
   *
   * ## 为什么不能直接写全局值
   *
   * 偏移是「片源与弹幕源的版本差」，是片子的属性而不是用户偏好：
   * 某部蓝光原盘多一段片头要 -40s，换一部 web 源立刻要 0。
   * 写全局的话，用户每换一部片都得先把上一部调的值清零 —— 这正是加按剧记忆要解决的。
   *
   * ⚠️ 必须在原始列表上重算（[lastRawComments]），不能在当前结果上再叠加：
   * 否则来回拖滑杆会累积成几十秒，那条弹幕就彻底消失了。
   */
  fun setTimeOffset(ms: Int) {
    preferences.setSeriesOffset(currentSeriesKey, ms)
    val raw = lastRawComments
    // ⚠️ 即使当前没弹幕（`raw` 为空）也要刷一次面板：
    //    否则「先在没弹幕时调好偏移、再打开开关」会看到滑杆弹回原位。
    if (raw.isNotEmpty()) {
      _comments.value = applyOffset(raw)
      refreshRenderParams()
    }
    // 面板要显示「已按本剧记住」
    refreshSheet()
  }

  /** 清掉本剧的偏移覆盖，回落到全局默认。 */
  fun clearTimeOffset() {
    preferences.setSeriesOffset(currentSeriesKey, null)
    val raw = lastRawComments
    if (raw.isNotEmpty()) {
      _comments.value = applyOffset(raw)
      refreshRenderParams()
    }
    refreshSheet()
  }

  // ── 偏好写入（面板里每个开关都直接落盘，立刻生效）──
  /**
   * 弹幕总开关。
   *
   * ## ⚠️ 打开时必须**主动去拉一次**
   *
   * 拉弹幕的唯一触发点是 [onEpisodeChanged]（切集）。原先这里只写偏好 + 刷渲染参数，
   * 于是出现用户看到的现象：**开播时开关是关的，进抽屉把开关打开，画面依然一条都没有**
   * （下一次切集前都不会有人去拉）。开关「点不动 / 没反应」的真实观感就来自这里 ——
   * 状态确实翻了，但画面上没有任何变化可供验证。
   *
   * 打开时根据是否已有弹幕分三种情况：
   * - 已有弹幕（之前拉过、中途关掉又打开）⇒ 直接恢复渲染，不联网；
   * - 没有弹幕但记得待播的片名 ⇒ 按原逻辑重新走一遍匹配（手动选番优先，其次自动匹配）；
   * - 什么都不知道（还没进过 [onEpisodeChanged]）⇒ 只开开关，等切集时自然加载。
   *
   * 关闭时把已拉到的弹幕清掉：一是省内存，二是避免「关掉再打开」时闪一下陈旧的弹幕。
   */
  fun setEnabled(v: Boolean) {
    // ⚠️ 不要写「值没变就直接 return」：这里不再是幂等 setter，而是用户的
    //    「打开它」这个意图。同一个值被点两次很常见（以为没生效又点了一下），
    //    直接 return 会让第二次点击彻底无响应 —— 正是「开关点不动」的观感来源。
    //
    // ⚠️⚠️ 落库之后**必须立刻刷 `_sheet`**（v109 修的线上问题）：
    //    面板读的是 `sheet` 这个 flow（`PlayerSheets.kt` 里 `danmakuState.sheet`），
    //    而 `_sheet` 初始值是 `DanmakuSheetState()`，`enabled` 默认 false。
    //    而且 `readSheetState()` 在 v109 之前**全项目一次都没被调用过**
    //    （它只是「写好了但没人接」）⇒ 面板上的开关永远读 false，用户点了立刻弹回，
    //    日志里连续 8 次都是「false → true」。
    //    偏好本身是对的，所以弹幕其实一直能拉到 —— 只是开关自己不亮，
    //    用户以为没打开，就再也不会看到画面上的弹幕。
    preferences.enabled.set(v)
    refreshSheet()
    Log.i(TAG, "setEnabled($v)：pending=\"$pendingTitle\"#${pendingEpisodeIndex + 1}，已有 ${_comments.value.size} 条")
    if (!v) {
      loadJob?.cancel()
      loadJob = null
      _comments.value = emptyList()
      // 清掉「已加载」的记忆，否则下次打开会误以为「已有弹幕」而跳过补拉
      lastLoadedEpisode = null
      lastLoadedAnimeTitle = ""
      loadedEpisodeIdentity = ""
      _sheet.value = _sheet.value.copy(
        loading = false,
        matchedTitle = null,
        commentCount = 0,
        // ⚠️ 关掉时把旧错误一并清掉：上一条「弹幕引擎已关闭」会一直挂在关闭态下，
        //    用户下次打开前看到的全是失败信息，像是开关坏了。
        error = null,
      )
      refreshRenderParams()
      return
    }
    // 打开：引擎被设成 Off 时打开开关也是白搭，明确说清楚而不是静默
    if (preferences.engine.get() == DanmakuRenderingEngine.Off) {
      // ⚠️ 这种情况开关**已经**在偏好里打开了，所以视觉上该亮着，只是不渲染
      //    （`readRenderParams` 会因为引擎 Off 而给它 false）。提示语说明了原因。
      _sheet.value = _sheet.value.copy(error = "弹幕引擎已关闭，请到设置里改成「原生」后再打开")
      refreshRenderParams()
      return
    }
    if (_comments.value.isNotEmpty()) {
      // 之前拉过、中途关掉又打开：直接恢复渲染（关掉时已经清空了）。
      // ⚠️ 其实上面那个 `!v` 分支已经把列表清空了，所以这里通常是「刚拉完又点了一下」。
      refreshRenderParams()
      return
    }
    refreshRenderParams()
    val title = pendingTitle
    if (title.isBlank()) {
      // 还没进过 onEpisodeChanged（极少见：叠层是在播放页里建的，理论上必先切集）。
      // 但静默返回会让用户完全看不到反应，所以给一句可读的说明。
      _sheet.value = _sheet.value.copy(error = "还没拿到片名，稍等片刻再点一次试试")
      return
    }
    // 手动选过番（且勾了「记住手动选择」）优先 —— 与 onEpisodeChanged 的优先级保持一致
    val remembered = manualAnime
    if (remembered != null && preferences.rememberSelection.get()) {
      loadFromAnime(remembered, pendingEpisodeIndex)
      return
    }
    if (!preferences.autoMatch.get()) {
      _sheet.value = _sheet.value.copy(error = "自动匹配已关闭，请手动搜索番剧")
      return
    }
    loadForTitle(title, pendingEpisodeIndex)
  }
  // ⚠️ 下面这几个「单一偏好」的 setter 一律「写偏好 → （影响渲染的再 `refreshRenderParams()`）→ `refreshSheet()`」：
  //    - `_renderParams` 决定**叠层要不要画、字号多大**，走渲染路径；
  //    - `_sheet` 决定**抽屉里控件显示成什么样**，走面板路径。
  //    两个流口径不同，各自都得刷，别以为刷了一个另一个就跟着变。
  //    原先只有前者，是 v109「开关点了弹回」的直接原因。
  fun setHost(v: String) { preferences.host.set(v.trim()); refreshSheet() }

  fun setAppId(v: String) { preferences.appId.set(v.trim()); refreshSheet() }

  fun setAppSecret(v: String) { preferences.appSecret.set(v.trim()); refreshSheet() }

  /**
   * 「自动匹配」开关。
   *
   * ⚠️ 打开时也要补拉一次：用户在「关掉自动匹配 → 手动搜也懒得搜」的空白态下
   * 把自动匹配重新打开，同样没有任何换集事件去触发加载。
   * 与 [setEnabled] 共用同一套「有弹幕就不动、没弹幕就按待播信息拉」的判断。
   */
  fun setAutoMatch(v: Boolean) {
    if (preferences.autoMatch.get() == v) return
    preferences.autoMatch.set(v)
    refreshSheet()
    // 关掉自动匹配不清已加载的弹幕：用户可能只是想「以后别乱搜」，当前这份仍然有效
    if (!v) return
    if (!preferences.enabled.get()) return
    if (_comments.value.isNotEmpty()) return
    if (pendingTitle.isBlank()) return
    // 手动选过的番优先，与其它入口口径一致
    val remembered = manualAnime
    if (remembered != null && preferences.rememberSelection.get()) {
      loadFromAnime(remembered, pendingEpisodeIndex)
      return
    }
    loadForTitle(pendingTitle, pendingEpisodeIndex)
  }

  /**
   * 「合并关联剧集弹幕」开关：改了要重新拉一次当前集的弹幕才生效。
   *
   * ⚠️ 还没加载过（[lastLoadedEpisode] 为空）时不能直接 return ——
   * 用户在「弹幕开着但没匹配上」的状态下切这个开关，同样期待它去拉一次。
   * 此时退回按 [pendingTitle] 走一遍匹配（拉到的就是带新开关值的那一份）。
   */
  fun setWithRelated(v: Boolean) {
    if (preferences.withRelated.get() == v) return
    preferences.withRelated.set(v)
    refreshSheet()
    val episode = lastLoadedEpisode
    if (episode != null) {
      scope.launch { loadEpisode(lastLoadedAnimeTitle.ifBlank { pendingTitle }, episode) }
      return
    }
    if (!preferences.enabled.get() || _comments.value.isNotEmpty()) return
    if (pendingTitle.isBlank()) return
    val remembered = manualAnime
    if (remembered != null && preferences.rememberSelection.get()) {
      loadFromAnime(remembered, pendingEpisodeIndex)
      return
    }
    if (preferences.autoMatch.get()) loadForTitle(pendingTitle, pendingEpisodeIndex)
  }

  fun setRememberSelection(v: Boolean) { preferences.rememberSelection.set(v); refreshSheet() }

  fun clearManualSelection() {
    manualAnime = null
  }
  fun setFontScale(v: Float) { preferences.fontScale.set(v); refreshRenderParams(); refreshSheet() }
  fun setSpeedScale(v: Float) { preferences.speedScale.set(v); refreshRenderParams(); refreshSheet() }
  fun setAlpha(v: Int) { preferences.alphaPercent.set(v); refreshRenderParams(); refreshSheet() }
  fun setArea(v: Float) { preferences.areaRatio.set(v); refreshRenderParams(); refreshSheet() }
  fun setBlockWords(v: String) {
    preferences.blockWords.set(v)
    // ⚠️ 必须在**原始列表**上重滤，不能在当前这份（已经滤过的）上再滤：
    //    先写「测试」→ 再删掉这个词时，被滤掉的条目已经不在列表里了，
    //    在当前列表上再滤一遍只会越滤越少，而且**永远回不来**（用户观感是「弹幕越设越少」）。
    //    与时间轴偏移同一个套路（见 [applyOffset]）。
    val raw = lastRawComments
    if (raw.isNotEmpty()) {
      val filtered = applyOffset(DanmakuMatcher.applyBlockWords(raw, blockWords()))
      _comments.value = filtered
      _sheet.value = _sheet.value.copy(commentCount = filtered.size)
      refreshRenderParams()
    }
    refreshSheet()
  }
  fun setDensity(key: String) {
    val d = DanmakuDensity.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    if (d != null) { preferences.density.set(d); refreshRenderParams(); refreshSheet() }
  }
  /**
   * 「防止弹幕重叠」开关。
   *
   * ⚠️⚠️ v117：这个 setter 曾是全类**唯一不调 [refreshSheet]** 的（只刷了
   *    `_renderParams`）。面板是 [DanmakuSheetState] 的一整个快照，不刷面板
   *    就等于开关停在旧值上，用户实测两种症状：
   *    1. 「关了之后就再也打不开了」—— 点一下偏好已改、UI 没变，再点还是旧值；
   *    2. 「开这个同时那个就会关掉」—— 之后碰任何一个**会**刷面板的控件
   *       （自动匹配/记住选择/密度/滑杆……），这份旧值一次性追平，于是另一个
   *       开关莫名其妙地跟着翻面，观感就是所有开关都错乱了。
   *    渲染侧本来就是通的（[refreshRenderParams] → 叠层 effect 重写
   *    `DanmakuContext`），坏的只是面板回显。
   */
  fun setPreventOverlapping(v: Boolean) {
    preferences.preventOverlapping.set(v)
    refreshRenderParams()
    refreshSheet()
  }

  /** 把偏好读成面板要的状态。 */
  fun readSheetState(): DanmakuSheetState = DanmakuSheetState(
    enabled = preferences.enabled.get(),
    host = preferences.host.get(),
    appId = preferences.appId.get(),
    appSecret = preferences.appSecret.get(),
    autoMatch = preferences.autoMatch.get(),
    withRelated = preferences.withRelated.get(),
    fontScale = preferences.fontScale.get(),
    speedScale = preferences.speedScale.get(),
    alphaPercent = preferences.alphaPercent.get(),
    areaRatio = preferences.areaRatio.get(),
    densityKey = preferences.density.get().name,
    preventOverlapping = preferences.preventOverlapping.get(),
    blockWords = preferences.blockWords.get(),
    timeOffsetMs = offsetForCurrent(),
    seriesOffsetApplied = preferences.hasSeriesOffset(currentSeriesKey),
    rememberSelection = preferences.rememberSelection.get(),
    matchedTitle = _sheet.value.matchedTitle,
    commentCount = _comments.value.size,
    loading = _sheet.value.loading,
    error = _sheet.value.error,
  )

  fun dispose() {
    loadJob?.cancel()
    loadJob = null
    _comments.value = emptyList()
  }
}