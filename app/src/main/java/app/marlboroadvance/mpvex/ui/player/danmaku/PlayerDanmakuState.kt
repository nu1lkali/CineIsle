package app.marlboroadvance.mpvex.ui.player.danmaku

import android.util.Log
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuComment
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuMatcher
import app.marlboroadvance.mpvex.domain.danmaku.DandanAnime
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
  )

  private fun refreshRenderParams() {
    _renderParams.value = readRenderParams()
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
    // ⚠️ 先取消上一次：旧请求返回后会把这里覆盖成旧片的弹幕。
    loadJob?.cancel()
    loadJob = null
    _comments.value = emptyList()
    refreshRenderParams()

    // 片名变了 ⇒ 引擎必须重建（叠层靠 episodeKey 判断）。
    _episodeKey.value = "${seriesKey ?: title}|$episodeIndex"

    if (!preferences.enabled.get() || preferences.engine.get() == DanmakuRenderingEngine.Off) {
      _sheet.value = _sheet.value.copy(matchedTitle = null, commentCount = 0, error = null)
      return
    }
    if (!preferences.autoMatch.get()) {
      _sheet.value = _sheet.value.copy(matchedTitle = null, commentCount = 0, error = null)
      return
    }
    loadForTitle(title, episodeIndex)
  }

  /** 按片名自动匹配并加载。 */
  fun loadForTitle(title: String, episodeIndex: Int) {
    loadJob?.cancel()
    _sheet.value = _sheet.value.copy(loading = true, error = null)
    loadJob = scope.launch {
      val matched = DanmakuMatcher.autoMatch(host(), title, episodeIndex, credentials())
      // 屏蔽词在这层就滤掉：不给渲染引擎塞无用条目。
      val filtered = DanmakuMatcher.applyBlockWords(matched.comments, blockWords())
      _comments.value = filtered
      refreshRenderParams()
      _sheet.value = _sheet.value.copy(
        loading = false,
        matchedTitle = matched.episode?.episodeTitle?.ifBlank { matched.anime?.animeTitle }
          ?: matched.anime?.animeTitle,
        commentCount = filtered.size,
        error = matched.error,
      )
      Log.i(TAG, "弹幕加载完成：${filtered.size} 条（${matched.error ?: "ok"}）")
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
    val filtered = DanmakuMatcher.applyBlockWords(
      DanmakuMatcher.fetchByEpisode(host(), episode.episodeId, credentials()),
      blockWords(),
    )
    _comments.value = filtered
    refreshRenderParams()
    _sheet.value = _sheet.value.copy(
      loading = false,
      matchedTitle = episode.episodeTitle.ifBlank { anime.animeTitle },
      commentCount = filtered.size,
      error = if (filtered.isEmpty()) "这一集没有弹幕" else null,
    )
  }

  // ── 偏好写入（面板里每个开关都直接落盘，立刻生效）──
  fun setEnabled(v: Boolean) { preferences.enabled.set(v); refreshRenderParams() }
  fun setHost(v: String) = preferences.host.set(v.trim())

  fun setAppId(v: String) = preferences.appId.set(v.trim())

  fun setAppSecret(v: String) = preferences.appSecret.set(v.trim())
  fun setAutoMatch(v: Boolean) = preferences.autoMatch.set(v)
  fun setFontScale(v: Float) { preferences.fontScale.set(v); refreshRenderParams() }
  fun setSpeedScale(v: Float) { preferences.speedScale.set(v); refreshRenderParams() }
  fun setAlpha(v: Int) { preferences.alphaPercent.set(v); refreshRenderParams() }
  fun setArea(v: Float) { preferences.areaRatio.set(v); refreshRenderParams() }
  fun setBlockWords(v: String) {
    preferences.blockWords.set(v)
    // ⚠️ 屏蔽词改了要**就地重滤**当前这份弹幕，不能要求用户切一集才生效。
    val words = v.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
    _comments.value = _comments.value.filter { c -> words.none { c.text.contains(it, ignoreCase = true) } }
  }
  fun setDensity(key: String) {
    val d = DanmakuDensity.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    if (d != null) { preferences.density.set(d); refreshRenderParams() }
  }

  /** 把偏好读成面板要的状态。 */
  fun readSheetState(): DanmakuSheetState = DanmakuSheetState(
    enabled = preferences.enabled.get(),
    host = preferences.host.get(),
    appId = preferences.appId.get(),
    appSecret = preferences.appSecret.get(),
    autoMatch = preferences.autoMatch.get(),
    fontScale = preferences.fontScale.get(),
    speedScale = preferences.speedScale.get(),
    alphaPercent = preferences.alphaPercent.get(),
    areaRatio = preferences.areaRatio.get(),
    densityKey = preferences.density.get().name,
    blockWords = preferences.blockWords.get(),
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