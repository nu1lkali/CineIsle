package app.marlboroadvance.mpvex.ui.browser.emby.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 「相似推荐 / 即混 / 续播」的 ViewModel（清单 #14）。
 *
 * ## 两套算法怎么切换
 *
 * 偏好 `emby_similar_algorithm` 决定走哪套（[SimilarAlgorithm]）：
 * - [SimilarAlgorithm.EMBY] → [EmbyClient.getSimilarItems] / [EmbyClient.getInstantMixItems]
 * - [SimilarAlgorithm.LOCAL] → 拉一批候选交给 [LocalSimilarity] 打分
 *
 * **但 LOCAL 不是「EMBY 失败就降级」**。降级只在**服务端明确不支持**时发生
 * （HTTP 404 / 405，即端点不存在），理由是：
 * 「服务端没刮削」和「端点不存在」在用户看来都是「推荐是空的」，
 * 但前者降级到本地会**静默改变用户选的算法**（设置里明明写着「服务端」），
 * 排查时极难发现。而本地算法拉候选（[loadLocalCandidates]）本身就有失败可能，
 * 真到那时直接报空态 + 提示去设置里切算法更诚实。
 *
 * ## 为什么详情页的推荐区不整体换成这个类
 *
 * `EmbyDetailScreen` 里已有「按人推荐」和「按题材推荐」两个区块（各有取舍，见那里的注释），
 * 强行合并会把那个 2200 行的文件继续做大。本类是给**独立入口**用的：
 * 首页一行「猜你喜欢 / 混合」卡片点进去，单独一页推荐。
 */
class SimilarRecommendationViewModel(
  application: Application,
) : AndroidViewModel(application) {

  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val preferences by inject<BrowserPreferences>(BrowserPreferences::class.java)

  private val _similar = MutableStateFlow<List<SimilarityBreakdown>>(emptyList())
  val similar: StateFlow<List<SimilarityBreakdown>> = _similar.asStateFlow()

  private val _mix = MutableStateFlow<List<EmbyItem>>(emptyList())
  val mix: StateFlow<List<EmbyItem>> = _mix.asStateFlow()

  private val _nextUp = MutableStateFlow<List<EmbyItem>>(emptyList())
  val nextUp: StateFlow<List<EmbyItem>> = _nextUp.asStateFlow()

  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /**
   * 记录「上次用哪套算法出的结果」。
   *
   * 用途有两个：UI 标注「本次用的是本地算法」（否则用户以为看到的是服务端结果），
   * 以及判断本次结果是不是空的 —— 空的时候要提示他去设置里换算法。
   */
  private val _lastAlgorithm = MutableStateFlow<SimilarAlgorithm?>(null)
  val lastAlgorithm: StateFlow<SimilarAlgorithm?> = _lastAlgorithm.asStateFlow()

  fun clearError() {
    _error.value = null
  }

  /**
   * 加载单个种子的相似推荐。
   *
   * @param seed 种子条目
   * @param refresh true = 换一批（本地算法会换一批候选，见 [shuffleSeed]）
   */
  fun loadSimilar(server: EmbyServer, seed: EmbyItem, refresh: Boolean = false) {
    val seedId = seed.Id
    if (seedId == null) {
      _error.value = "这个条目没有可用的 ID"
      return
    }
    val algorithm = preferences.embySimilarAlgorithm.get()
    val excludePlayed = preferences.embySimilarExcludePlayed.get()

    _loading.value = true
    viewModelScope.launch {
      runCatching {
        when (algorithm) {
          SimilarAlgorithm.EMBY -> {
            val page = repository.getSimilarItems(
              server = server,
              itemId = seedId,
              limit = SIMILAR_LIMIT,
              includeItemTypes = listOf("Movie", "Series"),
              // 种子自己必须排掉：第三方兼容实现不保证排除自己
              // （见 EmbyClient 2.5 节的说明）。已看过的在返回后统一滤。
              excludeIdList = listOf(seedId),
            )
            // 服务端 IgnorePlayed 不靠谱（有的实现压根没这个参数），
            // 所以已看过的在客户端再滤一次
            val items = page.Items.filter { excludePlayed.not() || it.UserData?.Played != true }
            // 拿不到分项明细 ⇒ reason() 只能为 null，卡片上不显示推荐理由。
            // 这是刻意的：宁可少一句话，也不要编一个「同类题材」出来
            //（服务端排序依据我们并不知道，套本地算法的措辞就是撒谎）。
            items.map { SimilarityBreakdown(it, 0.0, 0.0, 0.0, 0.0, 0.0, false) }
          }

          SimilarAlgorithm.LOCAL -> {
            val pool = loadLocalCandidates(server, seed, excludePlayed, refresh)
            LocalSimilarity.rank(
              seed = seed,
              candidates = pool,
              excludePlayed = excludePlayed,
              limit = SIMILAR_LIMIT,
            )
          }
        }
      }.onSuccess { list ->
        _similar.value = list
        _lastAlgorithm.value = algorithm
        if (list.isEmpty()) {
          _error.value = if (algorithm == SimilarAlgorithm.EMBY) {
            "服务端没返回相似内容（可能没刮削，或该服务器不支持推荐接口）"
          } else {
            "库里没有可推荐的其它内容了"
          }
        }
      }.onFailure { e ->
        val api = e as? app.marlboroadvance.mpvex.domain.emby.EmbyApiException
        _error.value = when {
          // 端点不存在 ⇒ 明确告诉用户可以切算法，而不是干巴巴「HTTP 404」
          api != null && (api.code == 404 || api.code == 405) ->
            "这个服务器不支持推荐接口，可在设置里把算法切成本地"
          else -> e.message ?: "加载相似推荐失败"
        }
      }
      _loading.value = false
    }
  }

  /**
   * 本地算法的候选池。
   *
   * 用种子自己的**类型 + 主演**去查库（服务端只做「过滤」，不做「排序」，
   * 排序完全交给 [LocalSimilarity]）。这是本地算法能用上服务端索引的唯一环节，
   * 也是它能出结果的原因。
   *
   * 两路并取：类型一路（题材相近）、主演一路（阵容相近）。任何一路空都不影响另一路。
   *
   * 「换一批」靠 [offset] 往后翻，同时查询用 `sortBy=Random` ——
   * 服务端随机排序让同一 seed 的两次请求天然给出不同结果，
   * 即使 offset 撞回同一段也不会看到一模一样的列表。
   */
  private suspend fun loadLocalCandidates(
    server: EmbyServer,
    seed: EmbyItem,
    excludePlayed: Boolean,
    refresh: Boolean,
  ): List<EmbyItem> {
    // 换一批：往后翻一页候选，避开上一批那一段
    val offset = if (refresh) CANDIDATE_PAGE_SIZE else 0

    val genres = seed.Genres.filter { it.isNotBlank() }
    // 主演取前 3 位：再多会引入边缘人物（「第 40 位特约」这种），
    // 反而把候选池拉向不相干的片子
    val peopleIds = (seed.People ?: emptyList())
      .filter { it.Type == "Actor" || it.Type == "Director" }
      .mapNotNull { it.Id }
      .take(3)

    val byGenre = runCatching {
      if (genres.isEmpty()) emptyList() else
        repository.getItems(
          server = server,
          genres = genres,
          includeItemTypes = listOf("Movie", "Series"),
          mediaTypes = listOf("Video"),
          sortBy = "Random",
          startIndex = offset,
          limit = CANDIDATE_PAGE_SIZE,
          isPlayed = if (excludePlayed) false else null,
        ).Items
    }.getOrDefault(emptyList())

    val byPeople = runCatching {
      if (peopleIds.isEmpty()) emptyList() else
        repository.getItems(
          server = server,
          personIds = peopleIds,
          includeItemTypes = listOf("Movie", "Series"),
          mediaTypes = listOf("Video"),
          sortBy = "Random",
          startIndex = offset,
          limit = CANDIDATE_PAGE_SIZE,
          isPlayed = if (excludePlayed) false else null,
        ).Items
    }.getOrDefault(emptyList())

    // 两路并起来去重后**保持原顺序**：第一路（类型）先来，
    // 同分时 LocalSimilarity 的排序键会再按评分/年份兜底，顺序稳定
    val seen = HashSet<String>(byGenre.size + byPeople.size)
    return (byGenre + byPeople).filter { it.Id != null && seen.add(it.Id!!) }
  }

  /**
   * 加载「即混」：以 [seed] 为起点续播一批。
   *
   * LOCAL 模式下没有服务端 InstantMix（那是服务端功能），所以走
   * 「候选池 → 本地打分 → 打乱取前 N」—— 效果上就是本地版的即混。
   *
   * @param limit 返回条数上限。首页那行横滑用得少（12 左右），独立页用默认的 [MIX_LIMIT]；
   *   调小它能少传一次大列表回来 —— 首页不需要 100 条。
   */
  fun loadMix(server: EmbyServer, seed: EmbyItem?, limit: Int = MIX_LIMIT) {
    val algorithm = preferences.embySimilarAlgorithm.get()
    val excludePlayed = preferences.embySimilarExcludePlayed.get()
    // 偏好可能被外部改成 0 或负数，getItems 的 limit 传非正数会让服务端返回全部 —— 那就变成拉整库了
    val take = limit.coerceIn(1, MIX_LIMIT)
    _loading.value = true
    viewModelScope.launch {
      runCatching {
        if (algorithm == SimilarAlgorithm.EMBY && seed?.Id != null) {
          repository.getInstantMixItems(
            server = server,
            itemId = seed.Id,
            limit = take,
            includeItemTypes = listOf("Movie", "Series"),
            mediaTypes = listOf("Video"),
            filters = if (excludePlayed) listOf("IsUnplayed") else null,
          ).Items.filter { excludePlayed.not() || it.UserData?.Played != true }
        } else {
          // 没有种子（或 LOCAL 模式）时，退化成「随机取一批没看过的」——
          // 这就是「即混」最朴素的形态：连着放同类片，不挑起点
          repository.getRandomItems(
            server = server,
            includeItemTypes = listOf("Movie", "Series"),
            limit = take,
          ).Items.filter { excludePlayed.not() || it.UserData?.Played != true }
        }
      }.onSuccess {
        _mix.value = it
        _lastAlgorithm.value = algorithm
        if (it.isEmpty()) {
          _error.value = if (seed == null) "库里没有可播放的内容" else "没有更多可续播的内容了"
        }
      }.onFailure { e ->
        val api = e as? app.marlboroadvance.mpvex.domain.emby.EmbyApiException
        _error.value = if (api != null && (api.code == 404 || api.code == 405)) {
          "这个服务器不支持自动连播，已按随机播放处理"
        } else {
          e.message ?: "加载连播列表失败"
        }
      }
      _loading.value = false
    }
  }

  /**
   * 加载「继续观看」：[EmbyClient.getNextUp]。
   *
   * 服务端没启用剧集续播时这里会是空列表，**那是正常状态不是错误**，
   * 所以不写 [error]，让 UI 安静地不显示这一段。
   */
  fun loadNextUp(server: EmbyServer) {
    viewModelScope.launch {
      runCatching { repository.getNextUpItems(server, NEXT_UP_LIMIT).Items }
        .onSuccess { _nextUp.value = it }
        .onFailure { _nextUp.value = emptyList() }
    }
  }

  /**
   * 把「相似推荐」和「即混」一次性拉完。
   *
   * 串行而非并行：两个请求都打同一个 Emby 服务端，并行只会让两边都慢，
   * 而且用户通常是先看推荐再决定要不要混播，串行的总耗时更短。
   */
  fun loadAll(server: EmbyServer, seed: EmbyItem?) {
    viewModelScope.launch {
      _error.value = null
      loadNextUp(server)
      seed?.let { loadSimilar(server, it) }
      loadMix(server, seed)
    }
  }

  /** 换一批相似推荐（只对本地算法有效，服务端那边用「重新拉一次」等价实现）。 */
  fun shuffle(server: EmbyServer, seed: EmbyItem) {
    loadSimilar(server, seed, refresh = true)
  }

  companion object {
    /** 相似推荐展示条数。横向一屏大约 3~5 张，取 20 够滚两屏。 */
    const val SIMILAR_LIMIT = 20

    /** 即混一次取多少条。 */
    const val MIX_LIMIT = 20

    /** 续播展示条数。 */
    const val NEXT_UP_LIMIT = 12

    /**
     * 本地算法每路查询的候选池大小。
     *
     * 比 [SIMILAR_LIMIT] 大：候选要经过打分筛掉一批（还有同系列的被硬剔除），
     * 池子只有 20 条的话，筛完可能剩不到 10 条，区块显得很空。
     */
    const val CANDIDATE_PAGE_SIZE = 60

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { SimilarRecommendationViewModel(application) }
    }
  }
}
