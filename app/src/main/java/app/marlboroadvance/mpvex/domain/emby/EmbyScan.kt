package app.marlboroadvance.mpvex.domain.emby

/**
 * 「全量扫描」的查询条件。
 *
 * 为什么需要它：Emby 的 /Items 是 `StartIndex + Limit` 分页的（见
 * dev.emby.media `getUsersByUseridItems`：StartIndex = 从第几条开始、Limit = 最多返回几条，
 * 响应里给 `Items` + `TotalRecordCount`），一页只给 Limit 条。
 * 凡是**服务端没有对应筛选参数**的条件（比如按路径判断的「中文字幕」），
 * 客户端就必须自己把整个结果集翻完 —— 只筛第一页必然漏。
 *
 * 这里把「和 EmbyClient.getItems 同名的那些筛选参数」打成一个包，
 * 免得扫描函数再排 20 个形参。
 */
data class EmbyScanQuery(
  /** 库 / 剧集 / 季 ID；null 表示用户根 */
  val parentId: String? = null,
  val includeItemTypes: List<String>? = null,
  val excludeItemTypes: List<String>? = null,
  /** Emby 的 Filters（IsResumable / IsFavorite …），多值用 | 分隔 */
  val filters: List<String>? = null,
  val sortBy: String? = null,
  val sortOrder: String? = null,
  val recursive: Boolean = true,
  val genres: List<String>? = null,
  val tags: List<String>? = null,
  val years: List<Int>? = null,
  val officialRatings: List<String>? = null,
  val minCommunityRating: Float? = null,
  val isFavorite: Boolean? = null,
  /** 只扫指定 MediaType（Video / Audio / Photo / Book）；null 不限 */
  val mediaTypes: List<String>? = null,
  /** false = 只扫能直接播放的媒体本体，容器（Folder / Series / Season / BoxSet）不要 */
  val isFolder: Boolean? = null,
  val personIds: List<String>? = null,
  val isPlayed: Boolean? = null,
  val isHD: Boolean? = null,
  val is3D: Boolean? = null,
  val hasSubtitles: Boolean? = null,
  val studioIds: List<String>? = null,
)

/** 扫描进度分片：每拉完一页回调一次，UI 可以边扫边显示，不用等整库拉完 */
data class EmbyScanChunk(
  /** 已扫描（已拉取）的条目数 —— 注意是**服务端条目数**，不是命中数 */
  val scanned: Int,
  /** 服务端报告的该查询总条目数（第一页之后才有意义） */
  val total: Int,
  /** 这一页的原始条目（未过滤） */
  val items: List<EmbyItem>,
)

/**
 * 扫描每页向服务端要多少条。
 *
 * 有些 Emby 版本会对 Limit 做上限截断，所以扫描循环**不能**用
 * 「返回条数 < 请求条数」当结束条件（那会在第一页就退出，又是「只筛出一部分」），
 * 只能靠「返回空页」或「已翻到 TotalRecordCount」结束。
 */
internal const val SCAN_PAGE_SIZE = 200

/** 单次扫描的条数上限，兜底防止异常数据把手机拖死（超出时 UI 会显示已扫描数小于总数） */
internal const val SCAN_MAX_ITEMS = 50_000
