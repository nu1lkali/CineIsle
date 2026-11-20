package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.runtime.Immutable
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleSelection

/**
 * 详情页字幕卡的状态。
 *
 * ⚠️ 这份状态**跟 itemId 一起重建**（挂在 [EmbyDetailContentState] 上），因为字幕清单
 * 属于「具体条目 + 具体媒体源」：换一条片子、换一个版本都必须重新发现，
 * 复用上一份只会把 4K 版的中文字幕挂到 1080P 版上。
 */
internal enum class DetailSubtitlePhase {
  /** 还没开始（条目未到 / 剧集未解析出目标集） */
  IDLE,

  /** 正在读 PlaybackInfo */
  LOADING,

  /** 读到结果：可能为空列表（空 ≠ 失败） */
  READY,

  /** 拿不到（网络失败 / 服务端报错）；[DetailSubtitleUi.hasData] 为真时表示「旧数据还在」 */
  FAILED,
}

@Immutable
internal data class DetailSubtitleUi(
  val phase: DetailSubtitlePhase = DetailSubtitlePhase.IDLE,
  val subtitles: List<SubtitleInfo> = emptyList(),
  /** 与播放器同一个选择器算出来的「会挂哪一条」；null = 还没算或没有候选 */
  val pick: SubtitleSelection? = null,
  /** 该媒体源的流总数：区分「没有字幕」与「压根没拿到流」 */
  val totalStreams: Int = 0,
  /** 这份清单属于哪个媒体源（发现结果回写，用来核对版本有没有切错） */
  val mediaSourceId: String? = null,
  /** 本次请求带的媒体源（null = 交给服务端挑默认源）；判断「旧结果能不能回填」用它 */
  val requestedSourceId: String? = null,
  /** 实际查的条目 Id（Series 是目标集的 Id，不是剧集本身的 Id） */
  val targetItemId: String = "",
  /** 目标集的显示名（「第 3 集」）；非剧集为 null */
  val targetLabel: String? = null,
  /** 当前版本名（「4K HDR」）；单版本条目为 null */
  val versionLabel: String? = null,
  val error: String? = null,
  /** 本次刷新失败、展示的是上一次的结果 */
  val stale: Boolean = false,
) {
  val hasData: Boolean get() = subtitles.isNotEmpty()

  val pickedKey: String? get() = pick?.chosen?.key
}
