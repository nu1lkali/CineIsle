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
