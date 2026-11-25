package app.marlboroadvance.mpvex.domain.emby.aggregate

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

/**
 * 聚合搜索的数据模型。
 *
 * ## 为什么每条结果都外挂服务器标识
 *
 * [EmbyItem] 是服务端条目的直接映射，自身**不含**「来自哪台服务器」——
 * 同一个 `Id` 在两台上完全可能指向两部不同的片子。聚合结果由多台拼成，
 * 一旦丢掉来源，图片 URL、详情页、播放都会指向错误的地方。所以来源必须外挂，
 * 而不是塞进 [EmbyItem]（那会污染所有既有调用）。
 */

/** 一条结果 + 它来自哪台服务器。 */
data class ServerHit(
  /**
   * 来源服务器**整条**带上，而不是只留 id。
   *
   * 卡片取封面、点开前切服务器、拼播放地址都要这台服务器的地址与 token；
   * 只留 id 就得让 UI 再拿 id 去列表里反查一次，多一处「查不到怎么办」的分叉。
   */
  val server: EmbyServer,
  /** 用户配置顺序（`servers` 列表下标），用于稳定排序与「默认打开哪台」 */
  val serverOrder: Int,
  val item: EmbyItem,
) {
  val serverId: Long get() = server.id

  val serverName: String get() = server.name

  val itemId: String? get() = item.Id

  /**
   * 文件体积：条目级 `Size` 优先，取不到退第一个媒体源的 `Size`。
   *
   * 两者都没有返回 null（而不是 0）—— 「不知道多大」和「0 字节」是两件事，
   * 混成一个会让排序把未知体积的条目排到最前。
   */
  val sizeBytes: Long?
    get() =
      item.Size?.takeIf { it > 0 }
        ?: item.MediaSources?.firstOrNull()?.Size?.takeIf { it > 0 }

  /**
   * 画质标签：`2160` 以上写作 4K，其余按 1080P / 720P / 480P 归档。
   *
   * 取首个视频流的 `Height`（`MediaStream.Type == "Video"`）。取不到返回 null ——
   * 界面上就不写这一段，而不是补一句「未知」。
   */
  val resolutionLabel: String?
    get() {
      val height =
        item.MediaSources
          ?.asSequence()
          ?.flatMap { it.MediaStreams?.asSequence() ?: emptySequence() }
          ?.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
          ?.Height
          ?: return null
      return when {
        height >= 2160 -> "4K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        else -> null
      }
    }

  /**
   * 该台上「看过多少」。用于服务器抽屉里的「已看 36 分钟」——
   * 这是比画质更能决定用户点哪一台的信息。
   */
  val playedPositionMs: Long
    get() = (item.UserData?.PlaybackPositionTicks ?: 0L) / 10_000L

  /**
   * 观看进度比例（0..1）。
   *
   * 用**比例**而不是绝对位置：同一部片在不同服务器的版本时长可能不同
   * （导演剪辑版 / 重新封装），按绝对位置比会把「看到 40 分钟的长版本」判成
   * 比「看到 35 分钟的短版本」更靠后。
   * 位置或总时长任一缺失都返回 0 —— 「不知道进度」不该参与比较。
   */
  val playedRatio: Float
    get() {
      val pos = item.UserData?.PlaybackPositionTicks ?: 0L
      val total = item.RunTimeTicks ?: 0L
      if (pos <= 0 || total <= 0) return 0f
      return (pos.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * 合并后的一条：同一部影片在多台上的全部副本。
 *
 * [hits] 已按偏好排好序，`hits[0]` 就是默认打开的那一份（规则见 [AggregateSearchRank]）。
 */
data class AggregateEntry(
  /** 去重键，见 [AggregateSearchKey]。只用于分组与调试，不展示给用户 */
  val key: String,
  val hits: List<ServerHit>,
) {
  val primary: ServerHit get() = hits.first()
  val serverCount: Int get() = hits.size
  val item: EmbyItem get() = primary.item
}

/**
 * 分组视图（`merge_mode = group`）的一段。
 *
 * 与 [AggregateEntry] 的区别只是分桶维度：这里按服务器分，不跨服务器合并。
 */
data class AggregateSection(
  val serverId: Long,
  val serverName: String,
  val hits: List<ServerHit>,
)

/**
 * 单台服务器的查询状态。
 *
 * [Empty] 与 [Ok] 分开，是因为两者要给用户的信号不同：`Empty` 是「这台搜了，没有」，
 * `Ok` 是「有结果」。合成一个「成功」会让服务器条上的计数失去意义。
 */
enum class ServerPhase {
  /** 尚未开始（首屏 / 已取消参与） */
  Pending,

  /** 请求中 */
  Running,

  /** 返回了结果 */
  Ok,

  /** 请求成功但 0 条 */
  Empty,

  /** 请求失败（超时、网络、5xx） */
  Failed,

  /** 没发请求：未登录 / 无凭据 / 用户未选它 */
  Skipped,
  ;

  val isTerminal: Boolean
    get() = this == Ok || this == Empty || this == Failed || this == Skipped
}

/** 一台服务器的查询结果与耗时，供服务器条与底部状态行展示。 */
data class ServerOutcome(
  val serverId: Long,
  val name: String,
  val phase: ServerPhase = ServerPhase.Pending,
  val count: Int = 0,
  val elapsedMs: Long = 0,
  /** Failed / Skipped 时的原因（已是可展示的文案，不含异常栈） */
  val message: String? = null,
)

/**
 * 一台服务器的一次返回。
 *
 * [error] 与 [items] 互斥：有错时 [items] 保持空。不抛异常是因为一台失败不能中断其余，
 * 这个判断在 [AggregateSearchEngine.search] 里逐台收口。
 */
data class ServerBatch(
  val server: EmbyServer,
  val serverOrder: Int,
  val items: List<EmbyItem> = emptyList(),
  val error: Throwable? = null,
  /** Failed / Skipped 直接给用户看的短句，例如「未登录」「6 秒内未响应」 */
  val failureMessage: String? = null,
  val elapsedMs: Long = 0,
  /** true = 没发请求（未登录等），用于区分 Failed 与 Skipped */
  val skipped: Boolean = false,
  /** 本页在服务端分页窗口里的起点（只对媒体流有意义，演员结果只在首页取） */
  val startIndex: Int = 0,
  /**
   * 媒体流的下一页起点。
   *
   * ⚠️ 不等于 `startIndex + items.size`：`items` 里还混着演员结果，
   * 两条流在服务端是两个独立的分页窗口，用总数推进会跳过条目。
   */
  val nextStartIndex: Int = 0,
  /** 该台的媒体结果已到底（返回不满一页，或已到服务端 TotalRecordCount） */
  val exhausted: Boolean = false,
)

/** 结果的组织方式。 */
enum class AggregateMergeMode {
  /** 同一部影片跨服务器合并为一条（默认） */
  MERGE,

  /** 按服务器分段，完全不合并 */
  GROUP,
}

/**
 * 一次搜索的完整快照。
 *
 * [entries] 与 [sections] 只会有一个非空 —— 由 `merge_mode` 决定，UI 按同一字段分支，
 * 不需要自己判断该渲染哪份。
 */
data class AggregateSnapshot(
  val entries: List<AggregateEntry> = emptyList(),
  val sections: List<AggregateSection> = emptyList(),
  /** 合并前的原始条数（= 各台返回条数之和），用于「原始 N 条」 */
  val rawCount: Int = 0,
  /** 是否因 [AggregateSearchLimits.MAX_ENTRIES] 截断 */
  val truncated: Boolean = false,
) {
  val isEmpty: Boolean
    get() = entries.isEmpty() && sections.isEmpty()

  /** 当前视图下的条目数（合并视图 = 合并后条数，分组视图 = 原始条数） */
  val visibleCount: Int
    get() = if (sections.isNotEmpty()) sections.sumOf { it.hits.size } else entries.size
}

/** 聚合搜索的几个上限。集中在这里，便于与方案文档对齐。 */
object AggregateSearchLimits {
  /** 每台每页取多少条。既是首屏条数，也是翻页时单页步长 */
  const val PER_SERVER = 60

  /**
   * 合并后最多渲染多少条 —— **安全上限，不是首屏预算**。
   *
   * 首屏只加载每台一页；此后由用户滚动触底逐页追加，累积到这里为止，
   * 避免「一直翻下去把内存和无障碍树撑爆」。到达后由界面提示已达上限。
   */
  const val MAX_ENTRIES = 1200

  /** 单台最多翻多少页（`PER_SERVER × 该值` 即单台可取的条数上限） */
  const val MAX_PAGES_PER_SERVER = 20

  /** 同时打几台。弱网下 8 台并发反而整体更慢，且可能触发服务端限流 */
  const val MAX_CONCURRENCY = 4

  /** 单台超时。大库 `SearchTerm` 实测 1–3 秒，给慢盘 / 网盘留余量 */
  const val PER_SERVER_TIMEOUT_MS = 6_000L

  /** 输入防抖，与现有搜索一致 */
  const val DEBOUNCE_MS = 400L
}
