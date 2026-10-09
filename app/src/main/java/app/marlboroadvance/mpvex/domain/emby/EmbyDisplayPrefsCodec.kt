package app.marlboroadvance.mpvex.domain.emby

/**
 * 显示偏好在「本地偏好」与「服务端 `DisplayPreferences`」之间的映射。
 *
 * ## 为什么单独抽一个纯 Kotlin 文件
 *
 * 同步逻辑里最容易出错的不是网络调用，而是**键名对不上**和**类型被静默吃掉**：
 * 服务端 `CustomPrefs` 是 `Map<String, String>`，写进去 `"3"` 和写进去 `"GRID"`
 * 都不会报错，下次读回来才发现认不出。这类 bug 只有单测能挡住，所以把
 * 编码 / 解码全部收在这里 —— **不依赖 Android、不依赖网络，可直接 JVM 跑单测**。
 *
 * ## 键名为什么带 `mpvex.` 前缀
 *
 * 分区键`Client` 已经把本App 与 Emby Web 隔开了（见 [EmbyClient] 5.8 节），
 * 两边理论上不会打架。但同一个 `DisplayPreferencesId` 下如果哪天有人把分区键改成
 * 共享的，前缀就是唯一的保险 —— 宁可服务端多存几个看不懂的键，也不要覆盖掉
 * 网页端的排版偏好。
 *
 * ## 排序为什么不走CustomPrefs
 *
 * `SortBy` / `SortOrder` 是 `DisplayPreferences` DTO 上的**独立字段**，
 * 不是 `CustomPrefs` 里的键（见 DTO 定义）。塞进 `CustomPrefs` 服务端不会报错，
 * 但网页端 / 其它客户端读不到 —— 是那种「看起来成功了其实没生效」的坑。
 */
object EmbyDisplayPrefsCodec {

  const val KEY_VIEW_MODE = "mpvex.viewMode"
  const val KEY_GRID_COLUMNS = "mpvex.gridColumns"
  const val KEY_CARD_STYLE = "mpvex.cardStyle"
  const val KEY_CARD_CORNER = "mpvex.cardCorner"
  const val KEY_CARD_SPACING = "mpvex.cardSpacing"
  const val KEY_SHOW_CARD_BADGES = "mpvex.showCardBadges"

  /**
   * 本App 的显示偏好快照。
   *
   * 全部字段给默认值，方便单测里只关心其中一项时随手构造。
   * 取值范围（列数2~6、圆角 0~24、间距 2~24）与设置页的滑杆范围保持一致 ——
   * **服务端存的值也走同一套夹取**，防止别的客户端写进一个离谱值把界面搞坏。
   */
  data class Snapshot(
    val viewMode: String = "",
    val gridColumns: Int = 3,
    val cardStyle: String = "POSTER",
    val cardCorner: Int = 12,
    val cardSpacing: Int = 8,
    val showCardBadges: Boolean = true,
    /** 对应 `DisplayPreferences.SortBy`（不是 CustomPrefs） */
    val sortBy: String = "SortName",
    /** 对应 `DisplayPreferences.SortOrder` */
    val sortOrder: String = "",
  )

  /**
   * 把快照编码成 `CustomPrefs`。
   *
   * 只写「有值」的键：`viewMode` / `sortOrder` 是空串代表「用默认」，
   * 写成空串会让别的客户端以为用户显式清空过。
   */
  fun encodePrefs(snapshot: Snapshot): Map<String, String> = buildMap {
    snapshot.viewMode.takeIf { it.isNotBlank() }?.let { put(KEY_VIEW_MODE, it) }
    put(KEY_GRID_COLUMNS, snapshot.gridColumns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS).toString())
    snapshot.cardStyle.takeIf { it.isNotBlank() }?.let { put(KEY_CARD_STYLE, it) }
    put(KEY_CARD_CORNER, snapshot.cardCorner.coerceIn(MIN_CARD_CORNER, MAX_CARD_CORNER).toString())
    put(KEY_CARD_SPACING, snapshot.cardSpacing.coerceIn(MIN_CARD_SPACING, MAX_CARD_SPACING).toString())
    put(KEY_SHOW_CARD_BADGES, snapshot.showCardBadges.toString())
  }

  /**
   * 从服务端的 `CustomPrefs` + DTO 字段解出快照。
   *
   * 三条容错规则，缺一不可：
   *  · **认不出的键直接丢**，绝不猜（宁可少同步一项，也不要把界面改成另一个意思）；
   *  · **数值越界夹回合法区间**（别的客户端可能写过 `gridColumns=99`）；
   *  · **布尔只认 `true`**，其它一律当 `false` —— 服务端存的是字符串，
   *    `"True"` / `"1"` 在不同版本上行为不一致，本App 写入时固定用小写 `true`/`false`。
   *
   * @param fallback 服务端没有可用值时逐字段回落（一般是本地的当前值）
   */
  fun decode(
    prefs: Map<String, String>,
    sortBy: String?,
    sortOrder: String?,
    fallback: Snapshot = Snapshot(),
  ): Snapshot = Snapshot(
    viewMode = prefs[KEY_VIEW_MODE]?.takeIf { it.isNotBlank() } ?: fallback.viewMode,
    gridColumns = prefs[KEY_GRID_COLUMNS]?.toIntOrNull()
      ?.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS) ?: fallback.gridColumns,
    cardStyle = prefs[KEY_CARD_STYLE]?.takeIf { it.isNotBlank() } ?: fallback.cardStyle,
    cardCorner = prefs[KEY_CARD_CORNER]?.toIntOrNull()
      ?.coerceIn(MIN_CARD_CORNER, MAX_CARD_CORNER) ?: fallback.cardCorner,
    cardSpacing = prefs[KEY_CARD_SPACING]?.toIntOrNull()
      ?.coerceIn(MIN_CARD_SPACING, MAX_CARD_SPACING) ?: fallback.cardSpacing,
    showCardBadges = prefs[KEY_SHOW_CARD_BADGES]?.let { it == "true" } ?: fallback.showCardBadges,
    sortBy = sortBy?.takeIf { it.isNotBlank() } ?: fallback.sortBy,
    sortOrder = sortOrder?.takeIf { it.isNotBlank() } ?: fallback.sortOrder,
  )

  /**
   * 合并写回服务端要用的 `DisplayPreferences`。
   *
   * ⚠️ `POST /DisplayPreferences/{id}` 是**整体覆盖**，所以必须先读出原有的
   * `CustomPrefs` 再把自己的键放进去 —— 直接拿新 map 覆盖会把同一分区里
   * 其它客户端写的键全抹掉。
   */
  fun merge(
    existing: EmbyDisplayPreferences,
    snapshot: Snapshot,
    client: String,
  ): EmbyDisplayPreferences = existing.copy(
    Id = existing.Id,
    CustomPrefs = existing.CustomPrefs + encodePrefs(snapshot),
    SortBy = snapshot.sortBy,
    SortOrder = snapshot.sortOrder,
    Client = client,
  )

  /** 快照里有没有任何一项与 [fallback] 不同（用来判断「服务端有没有真的存过东西」） */
  fun differsFrom(snapshot: Snapshot, fallback: Snapshot): Boolean = snapshot != fallback

  const val MIN_GRID_COLUMNS = 2
  const val MAX_GRID_COLUMNS = 6
  const val MIN_CARD_CORNER = 0
  const val MAX_CARD_CORNER = 24
  const val MIN_CARD_SPACING = 2
  const val MAX_CARD_SPACING = 24
}
