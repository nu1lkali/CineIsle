package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleMarks
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

/**
 * Preferences for the video browser (folder and video lists)
 */
class BrowserPreferences(
  private val preferenceStore: PreferenceStore,
  context: android.content.Context,
) {
  // Folder sorting preferences
  val folderSortType = preferenceStore.getEnum("folder_sort_type", FolderSortType.Title)
  val folderSortOrder = preferenceStore.getEnum("folder_sort_order", SortOrder.Ascending)

  /**
   * 随机播放取多少条。**所有随机入口统一读这里**：
   * 媒体库工具行上的「随机播放 / 随机播放收藏」、以及视界流（仿抖音竖屏连播）。
   */
  val randomPlayCount = preferenceStore.getInt("random_play_count", 100)

  // Video sorting preferences
  val videoSortType = preferenceStore.getEnum("video_sort_type", VideoSortType.Title)
  val videoSortOrder = preferenceStore.getEnum("video_sort_order", SortOrder.Ascending)

  val folderViewMode = preferenceStore.getEnum("folder_view_mode", FolderViewMode.AlbumView)

  private val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
  val folderGridColumnsPortrait = preferenceStore.getInt("folder_grid_columns_portrait", if (isTablet) 4 else 3)
  val folderGridColumnsLandscape = preferenceStore.getInt("folder_grid_columns_landscape", 5)

  val videoGridColumnsPortrait = preferenceStore.getInt("video_grid_columns_portrait", if (isTablet) 4 else 2)
  val videoGridColumnsLandscape = preferenceStore.getInt("video_grid_columns_landscape", 4)

  // Visibility preferences for video card chips
  val showVideoThumbnails = preferenceStore.getBoolean("show_video_thumbnails", true)
  val showSizeChip = preferenceStore.getBoolean("show_size_chip", true)
  // Metadata-dependent chips (disabled by default for better performance)
  val showResolutionChip = preferenceStore.getBoolean("show_resolution_chip", false)
  val showFramerateInResolution = preferenceStore.getBoolean("show_framerate_in_resolution", false)
  val showSubtitleIndicator = preferenceStore.getBoolean("show_subtitle_indicator", false)
  val showProgressBar = preferenceStore.getBoolean("show_progress_bar", true)
  val mediaLayoutMode = preferenceStore.getEnum("media_layout_mode", MediaLayoutMode. LIST)

  // Visibility preferences for folder card chips
  val showTotalVideosChip = preferenceStore.getBoolean("show_total_videos_chip", true)
  // Metadata-dependent chips (disabled by default for better performance)
  val showTotalDurationChip = preferenceStore.getBoolean("show_total_duration_chip", false)
  val showTotalSizeChip = preferenceStore.getBoolean("show_total_size_chip", true)
  val showDateChip = preferenceStore.getBoolean("show_date_chip", false)
  val showFolderPath = preferenceStore.getBoolean("show_folder_path", true)

  // Auto-scroll to last played media preference (like MX Player)
  val autoScrollToLastPlayed = preferenceStore.getBoolean("auto_scroll_to_last_played", false)

  // Watched threshold preference (percentage 1-100)
  val watchedThreshold = preferenceStore.getInt("watched_threshold", 95)

  // ── Emby 媒体库浏览偏好 ──

  /**
   * 媒体库内的排序方式。
   *
   * 取值与 Emby `/Items` 的 `SortBy` 参数一致（SortName / DateCreated / …），
   * 持久化后重新进入媒体库仍沿用上次选择。
   */
  val embyLibrarySortBy = preferenceStore.getString("emby_library_sort_by", "SortName")

  /** 媒体库内的卡片样式名（对应 [EmbyCardStyle] 的枚举名：POSTER / BACKDROP / BANNER） */
  val embyLibraryCardStyle = preferenceStore.getString("emby_library_card_style", "POSTER")

  /**
   * 媒体库排序方向。**空串 = 跟随该排序项的自然方向**（名称升序、加入时间降序…）；
   * 用户在库里点过升降序箭头后写死成 "Ascending" / "Descending"。
   *
   * 换排序项时会清空回空串重新跟随 —— 否则「名称」点成降序后切到「加入时间」，
   * 会变成最旧的排在最前面，几乎没人想要。
   */
  val embyLibrarySortOrder = preferenceStore.getString("emby_library_sort_order", "")

  /**
   * 每个媒体库各自记住的筛选条件（[EmbyLibraryFilterState] 的 JSON 串，空串 = 没筛过）。
   *
   * key 带上 libraryId：电影库和剧集库的筛选互不干扰。
   */
  fun embyLibraryFilter(libraryId: String): Preference<String> =
    preferenceStore.getString("emby_library_filter_$libraryId", "")

  /**
   * 每个媒体库各自记住的**分类**（全部 / 继续播放 / 合集 / 收藏 / 文件夹，存枚举名）。
   *
   * 空串 = 没选过 → 用「全部」。key 带 libraryId，跨库互不干扰。
   *
   * **为什么必须落盘而不是只 `remember`**：从库里下钻进一个文件夹（或剧集 → 季）再返回时，
   * 这一页的组合会被重建，`remember` 里的分类直接丢、被打回「全部」。
   * 和排序 / 筛选一样存进偏好，返回与重启都能回到用户上次选的那一档。
   */
  fun embyLibraryCategory(libraryId: String): Preference<String> =
    preferenceStore.getString("emby_library_category_$libraryId", "")

  /**
   * 首页「媒体库」卡片的**置顶列表**（逗号分隔的库 Id）。
   *
   * 顺序即优先级：第 0 个排最前 —— 也就是「最后置顶的排第一」，
   * 所以置顶动作是把 Id 插到表头（规则见 [app.marlboroadvance.mpvex.domain.emby.LibraryOrdering]）。
   *
   * **按服务器分开存**：不同服务器的库 Id 完全不同，混在一起会互相挤名额。
   */
  fun embyLibraryPins(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_library_pins_$serverId", "")

  /**
   * 首页「媒体库」卡片的**顺序号**（`id:号` 逗号分隔）。
   *
   * 置顶的库固定排在最前，所以序号实际决定的是「非置顶那一段」的先后；
   * 有置顶库时，序号 1 的库也排在所有置顶库之后。
   */
  fun embyLibraryNumbers(serverId: Long): Preference<String> =
    preferenceStore.getString("emby_library_numbers_$serverId", "")

  /**
   * 「中文字幕」路径标记配置（[ChineseSubtitleMarks] 的 JSON 串，空串 = 用默认标记）。
   *
   * 全局一份、不按库分：标记是**资源命名惯例**，跟具体哪个库无关。
   * 放在偏好里而不是写死在代码里，是为了库里出现新的标记写法时能自己加，
   * 不必重新发版。
   */
  val embyChineseSubtitleMarks = preferenceStore.getString("emby_chinese_subtitle_marks", "")

  /**
   * 「用外部播放器打开」记住的播放器，存 `包名/Activity名`（空串 = 还没用过）。
   *
   * 只用来在选择弹窗里标出「上次使用」那一项，**不改变列表顺序** ——
   * 顺序仍由 [KNOWN_EXTERNAL_PLAYERS] 的推荐位次决定，免得点了一次
   * 某个播放器之后列表就整个重排、下次找不到别的。
   */
  val embyExternalPlayer = preferenceStore.getString("emby_external_player", "")

  /**
   * 收藏页选中的内容类型（影片 / 演员，存枚举名，空串 = 影片）。
   *
   * **为什么落盘**：从收藏页点进某位演员的作品页再返回时，这一屏的
   * `remember` 状态会整个重建，只放在组合里的话就被打回「影片」。
   * 存进偏好后，返回、甚至重启 App 都还停在用户上次选的类型。
   */
  val embyFavoritesTab = preferenceStore.getString("emby_favorites_tab", "")

  /**
   * 收藏页的排序方式（存枚举名，空串 = 默认「名称」）。
   *
   * 收藏页是一次性拉全量（200 条）后本地排序，所以排序切换是即时的、不再发请求。
   */
  val embyFavoritesSort = preferenceStore.getString("emby_favorites_sort", "")

  /**
   * 「预解析直链（302）」开关。
   *
   * 打开后，播放前先把 `/Videos/{id}/stream` 背后跳转到的真实直链取出来再交给播放器。
   * 针对网盘 / STRM 这类**有 302 跳转、且直链带有效期**的源 ——
   * 某些播放器内核不跟跳转、或播到一半直链过期会卡死，预解析能绕开这两个坑。
   *
   * 默认**关闭**：多一次网络往返，对直出型的服务器（绝大多数）没有收益；
   * 由用户按自己的源决定要不要开（解析失败会自动回退到原始地址，不会挡住播放）。
   */
  val embyResolveDirectLink = preferenceStore.getBoolean("emby_resolve_direct_link", false)

  /**
   * 媒体库内的**视图模式**（[EmbyLibraryViewMode] 的枚举名，空串 = 网格）。
   *
   * 三种：网格（海报 / 背景图 / 横幅三选一）、紧凑列表（一行一条，信息密度最高）、
   * 年份时间轴（按播出年份分组，跨年找片快）。落盘的理由和分类一样 ——
   * 从库里钻进文件夹再返回时这一页会被重建，只放 `remember` 会掉回默认值。
   */
  val embyLibraryViewMode = preferenceStore.getString("emby_library_view_mode", "")

  /**
   * 海报网格**每行几个卡片**（2~6，默认 3）。
   *
   * 只对「海报」样式生效：背景图 / 横幅是宽图，行数由自身最小宽度自适应决定，
   * 硬塞进固定列数会被压得又窄又小。屏幕大的设备可以调到 5~6，
   * 手机上一行 2~3 个看得清封面上的字。
   */
  val embyLibraryGridColumns = preferenceStore.getInt("emby_library_grid_columns", 3)

  /**
   * 「下载完成后清除服务端播放进度」开关。
   *
   * 打开后，一条媒体下载完成时会顺手把 Emby 服务器上这条的**播放位置**清零 ——
   * 片子已经在本地了，「继续观看」再挂着「上次看到 42 分钟」就是噪音。
   *
   * 默认**关闭**：这是会改动服务器数据的行为，未必人人想要（有人本地留一份、
   * 服务器上也继续追进度）。只清播放位置，不动「已看」标记。
   */
  val embyClearProgressOnDownload =
    preferenceStore.getBoolean("emby_clear_progress_on_download", false)

  // ── Emby 卡片外观（统一项）──
  //
  // 这三项是「把散落的视觉常量收口成设置」：圆角原来写死在 EMBY_CARD_CORNER，
  // 角标（进度 / 作品数）也是硬开着。收口之后卡片的样子只有**一个来源**，
  // 用户在「设置 → Emby 媒体库 → 卡片外观」里改一次，全 App 的卡片一起变。

  /** 卡片圆角（dp），范围 0~24，默认 12 */
  val embyCardCorner = preferenceStore.getInt("emby_card_corner", 12)

  /** 卡片间距（dp），网格与紧凑列表共用，范围 2~24，默认 8 */
  val embyCardSpacing = preferenceStore.getInt("emby_card_spacing", 8)

  /**
   * 是否显示封面角标（播放进度条 / 作品数）。
   *
   * 关掉后卡片更干净，代价是看不出「看到哪儿了」；默认**开**，
   * 因为进度条是大部分人判断「要不要接着看」的主要线索。
   */
  val embyShowCardBadges = preferenceStore.getBoolean("emby_show_card_badges", true)

  // ── Emby 浏览交互开关 ──

  /**
   * 卡片右上角「快捷收藏」心形开关。
   *
   * 打开时媒体库卡片右上角的心形是**可点按钮**（点一下收藏 / 取消收藏）；
   * 关闭后退回原来的只读角标 —— 只在已收藏时显示一颗实心红心，避免误触。
   * 默认**开**：快捷收藏是这一批需求里的核心交互。
   */
  val embyQuickFavorite = preferenceStore.getBoolean("emby_quick_favorite", true)

  /**
   * 详情页是否显示推荐区（「推荐」+「同类型推荐」两个横向区块）。
   *
   * 关掉后媒体详情页只留影片信息与演职员，不再往下展示推荐内容 ——
   * 页面更短，也省掉两次推荐相关的网络请求。默认**开**。
   */
  val embyShowRecommendations = preferenceStore.getBoolean("emby_show_recommendations", true)

  /**
   * 详情页「同类型」推荐是否**随机选取类型**。
   *
   * 一部片子往往属于多个类型（剧情 / 惊悚 / 犯罪…）。固定取第一个类型的话，
   * 同一部片每次打开看到的推荐都一模一样。打开后每次进详情页从它的类型里随机挑一个，
   * 于是每次刷新都能看到不同题材的同类片。默认**开**。
   */
  val embyRandomGenreRecommend =
    preferenceStore.getBoolean("emby_random_genre_recommend", true)

  /**
   * 「合并完成后自动清理孤立演员」开关。
   *
   * 打开后，每次合并成功会顺手触发服务端的「刷新人员」任务，把**已经没有任何作品**的
   * 演员条目回收掉（合并剩下的空壳变体）。
   *
   * 默认**关闭**，和「合并」本身同一个口径：它虽然风险低（只是让服务端回收没用的条目、
   * 幂等、不会删任何影片），但**它会静默触发一个服务端任务** ——
   * 万一那个任务顺带扫了正在刮削中的条目，排查起来很麻烦。
   * 所以要开自己开，不替用户决定。
   */
  val embyAutoCleanOrphans = preferenceStore.getBoolean("emby_auto_clean_orphans", false)

  /**
   * 搜索联想词列表是否**收起**。
   *
   * 联想只是「顺手补全」的参考，不是主角 —— 下面才是真正的搜索结果。展开时最多占
   * 三行出头的高度（再多就在卡片内部滚动），收起后只留一行标题。
   * 记的是「收起」而不是「展开」：默认展开，用户点一次收起后就一直保持收起
   * （默认值 false = 展开，与改之前的表现一致）。
   */
  val embySearchSuggestCollapsed = preferenceStore.getBoolean("emby_search_suggest_collapsed", false)
}

/**
 * Sort order options
 */
enum class SortOrder {
  Ascending,
  Descending,
  ;

  val isAscending: Boolean
    get() = this == Ascending
}

/**
 * Folder sorting options
 */
enum class FolderSortType {
  Title,
  Date,
  Size,
  VideoCount,
  ;

  /** 持久化用的稳定键（英文），**不要改动** —— 改了老用户的排序偏好会失效 */
  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Date -> "Date"
        Size -> "Size"
        VideoCount -> "Count"
      }

  /** 界面上显示的中文名 */
  val label: String
    get() =
      when (this) {
        Title -> "标题"
        Date -> "日期"
        Size -> "大小"
        VideoCount -> "视频数"
      }
}

/**
 * Video sorting options
 */
enum class VideoSortType {
  Title,
  Duration,
  Date,
  Size,
  ;

  /** 持久化用的稳定键（英文），**不要改动** —— 改了老用户的排序偏好会失效 */
  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Duration -> "Duration"
        Date -> "Date"
        Size -> "Size"
      }

  /** 界面上显示的中文名 */
  val label: String
    get() =
      when (this) {
        Title -> "标题"
        Duration -> "时长"
        Date -> "日期"
        Size -> "大小"
      }
}

/**
 * Folder view mode options
 */
enum class FolderViewMode {
  AlbumView,
  FileManager,
  ;

  val displayName: String
    get() =
      when (this) {
        AlbumView -> "Folder View"
        FileManager -> "Tree View"
      }
}

enum class MediaLayoutMode {
  LIST,
  GRID,
  ;

  val displayName:  String
    get() = when (this) {
      LIST -> "List"
      GRID -> "Grid"
    }
}
