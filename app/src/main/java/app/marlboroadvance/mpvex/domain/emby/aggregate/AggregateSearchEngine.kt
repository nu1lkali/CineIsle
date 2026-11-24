package app.marlboroadvance.mpvex.domain.emby.aggregate

import app.marlboroadvance.mpvex.domain.emby.EmbyApiException
import app.marlboroadvance.mpvex.domain.emby.EmbyErrorKind
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemFields
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 聚合搜索的取数与归并。
 *
 * ## 为什么放在 domain 层而不是 ViewModel 里
 *
 * 取数与合并都是**纯逻辑**（除了网络调用本身），放这里才能被独立 JVM 断言直接测 ——
 * 本机 `compileStandardDebugUnitTestKotlin` 会卡死，UI 层没有单测通道。
 *
 * ## 与现有搜索的关系
 *
 * 这里只调 `EmbyRepository` 的公开方法，**不复用** `EmbyViewModel.searchGlobal`
 * （那是单服务器的、且绑着首页的状态机）。两条链路在 Repository 层交汇，各自向上独立。
 */
class AggregateSearchEngine(private val repository: EmbyRepository) {

  /**
   * 并发查询，**每台返回一次就发一次**（不攒到最后）。
   *
   * 一台慢不能拖住其余：`awaitAll` 那种写法会让「8 台里有 1 台在网盘上卡 6 秒」
   * 变成整页等 6 秒。这里逐台 send，UI 收到就合并刷新。
   *
   * @param servers 参与本次搜索的服务器（已按用户配置顺序排好，
   *   `serverOrder` 用下标传下去，保证排序稳定）
   */
  fun search(
    servers: List<EmbyServer>,
    term: String,
    itemTypes: List<String>?,
  ): Flow<ServerBatch> = channelFlow {
    // 并发上限：弱网下多台并发反而整体更慢，且可能触发服务端限流
    val permits = Semaphore(AggregateSearchLimits.MAX_CONCURRENCY)
    servers.forEachIndexed { index, server ->
      launch(Dispatchers.IO) {
        // 用户改词 / 退出页面时，外层 flow 被取消 ⇒ 这里也跟着结束
        permits.withPermit {
          send(queryOne(server, index, term, itemTypes))
        }
      }
    }
  }

  /**
   * 查一台。**永不抛异常**：失败与「没发请求」都折成 [ServerBatch]，
   * 由 UI 决定怎么标（一台失败不能影响其余）。
   */
  suspend fun queryOne(
    server: EmbyServer,
    serverOrder: Int,
    term: String,
    itemTypes: List<String>?,
  ): ServerBatch {
    val startedAt = System.currentTimeMillis()
    fun elapsed() = System.currentTimeMillis() - startedAt

    // ⚠️ 必须用 noSwitch 版本：ensureLoggedIn 成功后会把「当前服务器」换成它，
    // 用户在聚合搜索里搜一次就被切走服务器，是绝对不能接受的副作用。
    val ready =
      runCatching { repository.ensureLoggedInNoSwitch(server) }.getOrNull()
        ?: return ServerBatch(
          server = server,
          serverOrder = serverOrder,
          skipped = true,
          failureMessage = "未登录，需在服务器管理中重新登录",
          elapsedMs = elapsed(),
        )

    return try {
      val items =
        withTimeout(AggregateSearchLimits.PER_SERVER_TIMEOUT_MS) {
          fetch(ready, term, itemTypes)
        }
      ServerBatch(
        server = ready,
        serverOrder = serverOrder,
        items = items,
        elapsedMs = elapsed(),
      )
    } catch (e: TimeoutCancellationException) {
      // 外层被取消（改词 / 退出）时也会抛这个异常 —— 先确认仍在活动状态，
      // 否则会把「用户取消了」误报成「这台超时」。
      currentCoroutineContext().ensureActive()
      ServerBatch(
        server = ready,
        serverOrder = serverOrder,
        error = e,
        failureMessage = "超过 ${AggregateSearchLimits.PER_SERVER_TIMEOUT_MS / 1000} 秒未响应",
        elapsedMs = elapsed(),
      )
    } catch (e: Throwable) {
      currentCoroutineContext().ensureActive()
      ServerBatch(
        server = ready,
        serverOrder = serverOrder,
        error = e,
        failureMessage = briefReason(e),
        elapsedMs = elapsed(),
      )
    }
  }

  /**
   * 归并成一个快照。
   *
   * @param batches 已到达的批次（可能还没全部到达，UI 每次收到新批次都重新调一次）
   * @param currentServerId 用于让「当前服务器的那一份」成为默认打开项
   * @param sort 行的排序方式。**先排序后截断** —— 反过来会变成「在已截断的样本里排序」，
   *   用户选的「体积最大」就未必是结果里体积最大的那几条
   */
  fun snapshot(
    batches: List<ServerBatch>,
    mode: AggregateMergeMode,
    term: String,
    currentServerId: Long?,
    sort: AggregateSort = AggregateSort.MATCH,
  ): AggregateSnapshot {
    val rawCount = batches.sumOf { it.items.size }
    return when (mode) {
      AggregateMergeMode.GROUP -> groupSnapshot(batches, term, rawCount, sort)
      AggregateMergeMode.MERGE -> mergeSnapshot(batches, term, currentServerId, rawCount, sort)
    }
  }

  private fun mergeSnapshot(
    batches: List<ServerBatch>,
    term: String,
    currentServerId: Long?,
    rawCount: Int,
    sort: AggregateSort,
  ): AggregateSnapshot {
    val hitComparator = AggregateSearchRank.compareHits(term, currentServerId)
    // 段内（同一部片的多份副本）始终按「默认打开项」的规则排，
    // 用户选的排序只作用于**行与行之间**：换个体积排序不该把「默认打开哪台」也换掉
    val grouped =
      batches
        .flatMap { it.hits() }
        .groupBy { AggregateSearchKey.of(it) }
        .map { (key, hits) -> AggregateEntry(key, hits.sortedWith(hitComparator)) }

    val entries = AggregateSearchSort.sortEntries(grouped, sort, term)
    val limited = entries.take(AggregateSearchLimits.MAX_ENTRIES)
    return AggregateSnapshot(
      entries = limited,
      rawCount = rawCount,
      truncated = limited.size < entries.size,
    )
  }

  private fun groupSnapshot(
    batches: List<ServerBatch>,
    term: String,
    rawCount: Int,
    sort: AggregateSort,
  ): AggregateSnapshot {
    // 分组视图不合并，段内也不需要在「当前服务器优先」上做区分（段本来就是同一台）
    var budget = AggregateSearchLimits.MAX_ENTRIES
    val sections = ArrayList<AggregateSection>(batches.size)
    batches
      .filter { it.items.isNotEmpty() }
      .sortedBy { it.serverOrder }
      .forEach { batch ->
        if (budget <= 0) return@forEach
        val hits = AggregateSearchSort.sortHits(batch.hits(), sort, term).take(budget)
        budget -= hits.size
        sections += AggregateSection(batch.server.id, batch.server.name, hits)
      }
    return AggregateSnapshot(
      sections = sections,
      rawCount = rawCount,
      truncated = rawCount > sections.sumOf { it.hits.size },
    )
  }

  // ─── 取数 ───

  private suspend fun fetch(
    server: EmbyServer,
    term: String,
    itemTypes: List<String>?,
  ): List<EmbyItem> {
    // 演员走 /Persons，与媒体是两条独立流（`/Items` 那条端点搜不出人）
    val wantsPerson = itemTypes == null || itemTypes.any { it.equals(PERSON_TYPE, true) }
    val persons =
      if (wantsPerson) {
        runCatching {
          repository.searchPersons(server, term, null, AggregateSearchLimits.PER_SERVER)
        }.getOrDefault(emptyList())
      } else {
        emptyList()
      }

    val mediaTypes =
      itemTypes?.filterNot { it.equals(PERSON_TYPE, true) }?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_MEDIA_TYPES.takeIf { itemTypes == null }

    val media =
      if (mediaTypes.isNullOrEmpty()) {
        emptyList()
      } else {
        runCatching {
          repository.searchItems(
            server = server,
            term = term,
            includeItemTypes = mediaTypes,
            limit = AggregateSearchLimits.PER_SERVER,
            // CORE 不含 ProviderIds，跨服务器去重必须显式索取
            fields = MEDIA_FIELDS,
          ).Items
        }.getOrDefault(emptyList())
      }
    return persons + media
  }

  /**
   * 失败原因 → 一句工程化短句。
   *
   * 不给用户看 `Emby API 500: <html>` 那种东西（`EmbyApiException.message` 就是这样）。
   * 分类口径直接沿用 [EmbyApiException.errorKind]，避免在聚合这里另造一套判断。
   */
  private fun briefReason(e: Throwable): String =
    when (e) {
      is EmbyApiException ->
        when (e.errorKind) {
          EmbyErrorKind.NETWORK -> "网络不可达"
          EmbyErrorKind.AUTH -> "鉴权失败（${e.code}）"
          EmbyErrorKind.NOT_FOUND -> "资源不存在（404）"
          EmbyErrorKind.SERVER -> "服务器出错（${e.code}）"
          EmbyErrorKind.UNKNOWN -> "请求失败（${e.code}）"
        }
      is IOException -> "网络不可达"
      else -> e.message?.takeIf { it.isNotBlank() } ?: "请求失败"
    }

  private fun ServerBatch.hits(): List<ServerHit> =
    items.map { ServerHit(server, serverOrder, it) }

  companion object {
    /** 与 `/Persons` 打交道的类型名；大小写按服务端实际值 */
    private const val PERSON_TYPE = "Person"

    /**
     * 不传类型时的默认媒体类型。
     *
     * 与首页全库搜索保持一致：容器（Folder / CollectionFolder 等）不在其中，
     * 否则结果里会混进「点进去还要再下钻一层」的目录项。
     */
    private val DEFAULT_MEDIA_TYPES =
      listOf("Movie", "Series", "Episode", "Video", "MusicVideo")

    /** 搜索用的字段清单：CORE + 去重必需的 ProviderIds */
    private val MEDIA_FIELDS = "${EmbyItemFields.CORE},ProviderIds"
  }
}
