package app.marlboroadvance.mpvex.domain.subtitle.online

import android.util.Log
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MatchReason
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SmartSubtitleMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 匹配评分：把「在线候选」按**与当前媒体的相似度**打分并排序。
 *
 * ## 复用既有匹配器，不另写一套
 *
 * 项目里已经有一套为「本地字幕目录」打磨过的判据链（编号三段结构、边界、
 * 前缀/后缀弱关系降档、冲突上限……），它解决的正是同一个问题：
 * 「这条字幕是不是这部片的」。[SmartSubtitleMatcher.rank] 只要喂进去一个
 * 候选路径就能打分，所以这里只做**适配**：把在线候选的标题拼成一个候选路径。
 *
 * 这样做的直接好处是需求 3.2 里那句「`n0907` 与 `n09070` 不能被判成完全匹配」
 * 天然成立 —— 那是 [app.marlboroadvance.mpvex.ui.player.subtitle.smart.IdentifierNormalizer]
 * 已有的强约束，不是这里引入的。
 */
object OnlineSubtitleMatcher {
  private const val TAG = "OnlineSubtitleMatch"

  /**
   * 给候选打分。
   *
   * @param mediaTitle 当前媒体的原始名（文件名或 Emby 标题）。空 ⇒ 全部返回
   *   [SubtitleMatchLevel.UNKNOWN]，界面显示「未评估」而不是伪造分数。
   */
  fun apply(mediaTitle: String?, items: List<OnlineSubtitle>): List<OnlineSubtitle> {
    if (mediaTitle.isNullOrBlank() || items.isEmpty()) return items

    val query =
      runCatching { SmartSubtitleMatcher.prepare(mediaTitle) }.getOrElse {
        Log.w(TAG, "prepare query failed: ${it.message}")
        return items
      }
    if (query.isEmpty) return items

    val scored =
      items.map { item ->
        // 候选路径 = 标题 + 扩展名：匹配器按「文件名」的口径解析，
        // 少了扩展名会把结尾的 `.2020` 当成版本号之外的噪声。
        val path = "${item.title}.${item.format ?: "srt"}"
        val result =
          runCatching { SmartSubtitleMatcher.rank(query, listOf(path)).firstOrNull() }.getOrNull()
        if (result == null) {
          item
        } else {
          val rawScore = result.score.roundToInt().coerceIn(0, 100)
          // 「上限被压过」= 最终分明显低于未压制的原始分（冲突标记在打分时就把上限写死了）。
          // 直接用 rawScore 与 score 的差判断，不去抄一遍 ConflictDetector 的私有标记集 ——
          // 那边一改，这里自动跟上。
          val capped = result.score < result.rawScore - 0.5
          // 被冲突标记压过上限的（如「同一部片但另一集」）不给高匹配 ——
          // 分数再高也只是一条会误导人的结果。
          val level =
            if (capped) {
              SubtitleMatchLevel.FAIR.takeIf { rawScore >= 50 } ?: SubtitleMatchLevel.LOW
            } else {
              SubtitleMatchLevel.of(rawScore)
            }
          item.copy(
            matchScore = rawScore,
            matchLevel = level,
            matchReason = reasonText(result.reasons.firstOrNull() ?: MatchReason.TEXT_ONLY),
          )
        }
      }

    return scored.sortedWith(
      compareByDescending<OnlineSubtitle> { it.matchScore }
        .thenBy { it.source.ordinal }
        .thenBy { it.title },
    )
  }

  /** 匹配依据 → 一句人话。与播放器字幕面板的用词保持一致。 */
  private fun reasonText(reason: MatchReason): String =
    when (reason) {
      MatchReason.STEM_EXACT -> "文件名一致"
      MatchReason.STEM_PREFIX -> "文件名前缀一致"
      MatchReason.CODE_EXACT -> "编号完全一致"
      MatchReason.CODE_FORMAT_VARIANT -> "编号分隔符不同"
      MatchReason.CODE_ALIAS -> "命中已确认别名"
      MatchReason.CODE_ZERO_VARIANT -> "编号前导零不同"
      MatchReason.CODE_SUFFIX_VARIANT -> "编号后缀不同"
      MatchReason.CODE_PARTIAL -> "仅部分数字相同"
      MatchReason.CRC_SAME -> "发布组校验值一致"
      MatchReason.TITLE_TOKENS -> "标题词重合"
      MatchReason.VERSION_FLAGS -> "版本标记一致"
      MatchReason.TIMING_VERIFIED -> "时间轴吻合"
      MatchReason.TEXT_ONLY -> "仅文字相似"
    }
}

/**
 * 在线字幕搜索的统一门面。
 *
 * ## 三条硬约定
 *
 * 1. **并行**：两个来源同时发请求，总耗时 ≈ 较慢的那个，而不是两者之和。
 * 2. **失败隔离**：任一个来源抛异常 / 超时，只记录在该来源的 [ProviderOutcome] 里，
 *    其余来源结果照常返回（需求 2.3 / 12.7）。
 * 3. **可取消**：整个方法是 `suspend` 的，调用方（面板）一取消，内部 `async` 一起取消。
 *
 * ## 超时为什么放在管理层
 *
 * Provider 内部是阻塞 IO，不能自己 reliably 取消；外层用 `withTimeoutOrNull` 保证
 * **界面**最多等 [perProviderTimeoutMs]，泄漏的那一次请求由 OkHttp 的 readTimeout 收尾。
 */
class OnlineSubtitleSearchManager(
  private val providers: List<SubtitleProvider> =
    listOf(ThunderSubtitleProvider(), SubtitleCatProvider()),
  private val perProviderTimeoutMs: Long = 25_000,
) {
  /**
   * 搜一批候选。
   *
   * @param keyword 已清洗的关键词（[OnlineSubtitleKeyword] 或用户手输）
   * @param enabled 启用的来源；传空集合则直接返回空结果（不发任何请求）
   * @param mediaTitle 用于匹配打分的媒体名；null ⇒ 不打分
   * @param onProviderDone 某个来源出结果时回调（界面可逐条增量展示）。
   *   回调在 IO 线程，实现方自行切主线程。
   */
  suspend fun search(
    keyword: String,
    enabled: Set<OnlineSubtitleSource>,
    mediaTitle: String? = null,
    onProviderDone: ((ProviderOutcome) -> Unit)? = null,
  ): SubtitleSearchOutcome {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty() || enabled.isEmpty()) {
      return SubtitleSearchOutcome(keyword = trimmed)
    }

    // 结果缓存（需求 2.4）：同一个关键词 + 同一组来源，10 分钟内不重复打站点。
    // 只缓存「合并去重后」的原始候选，匹配分数仍然按当前媒体重新算 ——
    // 否则「换一部片搜同一个词」会拿到上一部片的评分。
    val cacheKey = "$trimmed|" + enabled.map { it.key }.sorted().joinToString(",")
    cached(cacheKey)?.let { hit ->
      return SubtitleSearchOutcome(
        keyword = trimmed,
        items = OnlineSubtitleMatcher.apply(mediaTitle, hit),
      )
    }

    val outcomes =
      coroutineScope {
        val jobs =
          providers
            .filter { it.source in enabled }
            .map { provider ->
              async {
                val started = System.currentTimeMillis()
                val results =
                  runCatching {
                    withTimeoutOrNull(perProviderTimeoutMs) { provider.search(trimmed) }
                  }
                val elapsed = System.currentTimeMillis() - started
                val outcome =
                  when {
                    results.isFailure ->
                      ProviderOutcome(
                        source = provider.source,
                        error = humanError(results.exceptionOrNull()),
                        elapsedMs = elapsed,
                      )

                    results.getOrNull() == null ->
                      ProviderOutcome(
                        source = provider.source,
                        error = "超时未响应",
                        elapsedMs = elapsed,
                      )

                    else ->
                      ProviderOutcome(
                        source = provider.source,
                        results = results.getOrNull().orEmpty(),
                        elapsedMs = elapsed,
                      )
                  }
                // 单个来源失败只记日志：这是预期内的常态（站点抽风 / 被限流）
                if (outcome.error != null) {
                  Log.w(TAG, "${provider.source.label} 搜索失败：${outcome.error}")
                }
                onProviderDone?.invoke(outcome)
                outcome
              }
            }
        jobs.map { it.await() }
      }

    val merged = dedupe(outcomes.flatMap { it.results })
    // 只有「至少一个来源真的回来了」才进缓存：全站抽风时缓存空结果，
    // 会让用户接下来十分钟都搜不到东西。
    if (outcomes.any { it.error == null }) remember(cacheKey, merged)
    val ranked = OnlineSubtitleMatcher.apply(mediaTitle, merged)
    return SubtitleSearchOutcome(
      keyword = trimmed,
      items = ranked,
      outcomes = outcomes,
    )
  }

  /**
   * 去重。
   *
   * 两层键：
   * · 同一个下载地址 ⇒ 一定是同一条（不同关键词搜出来的重复最常见的形态）；
   * · 同来源 + 同归一化标题 ⇒ 站点自己重复收录，保留先出现的（信息更全）。
   */
  private fun dedupe(items: List<OnlineSubtitle>): List<OnlineSubtitle> {
    val seenUrls = HashSet<String>()
    val seenTitles = HashSet<String>()
    val out = ArrayList<OnlineSubtitle>(items.size)
    for (item in items) {
      if (!seenUrls.add(item.downloadUrl)) continue
      val titleKey = "${item.source.key}|${item.title.lowercase().replace(Regex("\\s+"), " ").trim()}"
      if (!seenTitles.add(titleKey)) continue
      out += item
    }
    return out
  }

  private fun humanError(t: Throwable?): String =
    when (t) {
      null -> "未知错误"
      is java.net.SocketTimeoutException -> "连接超时"
      is java.net.UnknownHostException -> "无法解析域名"
      is java.io.IOException -> "网络异常"
      else -> t.message?.take(60)?.ifBlank { null } ?: "搜索失败"
    }

  // ──────────────────────── 结果缓存 ────────────────────────

  private data class CacheEntry(
    val merged: List<OnlineSubtitle>,
    val at: Long,
  )

  private val cache = LinkedHashMap<String, CacheEntry>()
  private val cacheLock = Any()

  private fun cached(key: String): List<OnlineSubtitle>? =
    synchronized(cacheLock) {
      val entry = cache[key] ?: return null
      if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
        cache.remove(key)
        null
      } else {
        entry.merged
      }
    }

  private fun remember(
    key: String,
    merged: List<OnlineSubtitle>,
  ) {
    synchronized(cacheLock) {
      // 只留最近若干条：缓存是「手滑重搜」的补丁，不是离线库
      while (cache.size >= CACHE_MAX_ENTRIES) {
        cache.keys.firstOrNull()?.let(cache::remove)
      }
      cache[key] = CacheEntry(merged, System.currentTimeMillis())
    }
  }

  private companion object {
    const val TAG = "OnlineSubtitleSearch"
    const val CACHE_TTL_MS = 10 * 60 * 1000L
    const val CACHE_MAX_ENTRIES = 12
  }
}
