package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 起播后的「无字幕兜底」：检索网络字幕 → 达阈值才下载落盘。
 *
 * ## 触发条件（由调用方把关，本类只负责执行）
 *
 * 只有「本次播放没有任何可用字幕」时才该调用：无手动绑定、无已下载网络字幕、
 * Emby 也没有选出一条。任何一条成立都不该再跑这里 —— 那会顶掉用户的选择。
 *
 * ## 为什么阈值默认 85
 *
 * 与 `SubtitleMatchLevel.HIGH` 同口径：85 分以上才算「编号 / 文件名层面确实对得上」。
 * 低于它的候选会挂上去给用户看错轴的字幕，比没有字幕更糟。
 *
 * ## 失败一律静默
 *
 * 站点抽风、关键词提取不出、候选不够准，都是常态。自动兜底不该在每次起播时
 * 弹一次失败提示 —— 用户没有主动发起这次请求，失败就没有打扰的理由。
 */
object OnlineSubtitleAutoFetch {
  private const val TAG = "SubtitleAutoFetch"

  /** 整轮预算：检索（两个来源并行）+ 下载。超出即放弃，不拖住播放侧。 */
  private const val TOTAL_BUDGET_MS = 45_000L

  /** 结果与给用户的一句话（仅成功挂载时展示，其余状态静默） */
  data class Result(
    val status: Status,
    val record: DownloadedSubtitle? = null,
    val score: Int = 0,
    val keyword: String = "",
    /** 可直接用于 Toast / Snackbar 的单行文案；空串表示不必提示 */
    val message: String = "",
  ) {
    enum class Status {
      /** 设置里未开启 */
      DISABLED,

      /** 提取不出检索关键词 */
      NO_KEYWORD,

      /** 检索无候选 */
      NO_CANDIDATE,

      /** 有候选但低于阈值 */
      BELOW_THRESHOLD,

      /** 已落盘，可以挂载 */
      SAVED,

      /** 检索 / 下载失败 */
      FAILED,
    }
  }

  /**
   * @param threshold 匹配度阈值（0–100）
   * @param saveFolderUri 「设置 → 字幕」的保存目录（SAF 兼容值）；绝对路径由偏好内部优先取
   */
  suspend fun run(
    context: Context,
    library: OnlineSubtitleLibrary,
    preferences: SubtitlesPreferences,
    target: OnlineSubtitleTarget,
    threshold: Int,
    saveFolderUri: String = "",
    searchManager: OnlineSubtitleSearchManager = OnlineSubtitleSearchManager(),
    downloader: OnlineSubtitleDownloader = OnlineSubtitleDownloader(),
  ): Result =
    withContext(Dispatchers.IO) {
      if (target.serverKey.isBlank() || target.itemId.isBlank()) return@withContext failed()

      val keyword = target.suggestKeyword().keyword.trim()
      if (keyword.isEmpty()) {
        return@withContext Result(Result.Status.NO_KEYWORD)
      }

      val outcome =
        withTimeoutOrNull(TOTAL_BUDGET_MS) {
          searchManager.search(
            keyword = keyword,
            enabled = OnlineSubtitleSource.DEFAULT_ENABLED,
            mediaTitle = target.matchName(),
          )
        } ?: return@withContext Result(Result.Status.FAILED, keyword = keyword)

      val best = outcome.items.firstOrNull()
      if (best == null) {
        return@withContext Result(Result.Status.NO_CANDIDATE, keyword = keyword)
      }
      if (best.matchScore < threshold) {
        // 低于阈值视为未命中：不改变任何播放状态，不提示（同 KDoc「失败一律静默」）
        return@withContext Result(
          status = Result.Status.BELOW_THRESHOLD,
          score = best.matchScore,
          keyword = keyword,
        )
      }

      // 同一条已经下过：直接复用，不重复占用磁盘与流量
      library.find(target.serverKey, target.itemId, best.id)?.let { existing ->
        return@withContext Result(
          status = Result.Status.SAVED,
          record = existing,
          score = best.matchScore,
          keyword = keyword,
          message = "已挂载网络字幕：${existing.title}（匹配度 ${best.matchScore}%）",
        )
      }

      val record =
        runCatching {
            val download =
              withTimeoutOrNull(TOTAL_BUDGET_MS) {
                downloader.download(
                  item = best,
                  targetDir = library.mediaDir(target.serverKey, target.itemId),
                  baseName = OnlineSubtitleLibrary.downloadBaseName(best),
                )
              } ?: error("下载超时")
            OnlineSubtitlePersist.save(
              context = context,
              library = library,
              preferences = preferences,
              serverKey = target.serverKey,
              itemId = target.itemId,
              mediaTitle = target.mediaTitle.ifBlank { target.embyTitle.orEmpty() },
              item = best,
              result = download,
              saveFolderUri = saveFolderUri,
            )
          }
          .getOrElse {
            Log.w(TAG, "auto fetch save failed: ${it.message}")
            return@withContext Result(Result.Status.FAILED, keyword = keyword)
          }

      Result(
        status = Result.Status.SAVED,
        record = record,
        score = best.matchScore,
        keyword = keyword,
        message = "已挂载网络字幕：${record.title}（匹配度 ${best.matchScore}%）",
      )
    }

  private fun failed() = Result(Result.Status.FAILED)
}
