package app.marlboroadvance.mpvex.domain.emby

/**
 * Emby REST 的**端点注册表**（无 host，只到 path 为止）。
 *
 * ## 为什么单独一层
 *
 * 在这之前 `"/Users/" + userId + "/Items"` 这类字面量散在 11 个文件里共 90 余处，
 * 其中 `GET /Items/{Id}/PlaybackInfo` 一个端点被手抄了 5 遍。改一个端点的代价是
 * 全仓搜索，而搜索又会带出注释里的示例文本 —— 真正的改动点反而不好找。
 *
 * 现在约定：
 * · **所有 Emby 端点只在这里出现一次**，其余文件只能调这里的函数；
 * · 参数一律是纯值（id / 索引 / 格式），不接收 [EmbyServer]，这样本对象不依赖
 *   任何运行期状态，也不会和 [EmbyEndpointResolver] 的地址解析纠缠；
 * · 返回值**不带** `/emby` 前缀 —— 前缀由 [EmbyHttpCore.absolute] 与
 *   [EmbyHttpCore.authedRequest] 统一补，避免第三个地方再拼一次。
 *
 * ## 加端点的规矩
 *
 * 已存在的一律复用（尤其是 [playbackInfo]，别在调用点再写一遍）；
 * 同一端点有 `Items` / `Videos` 两种官方形式时（字幕流、stream），
 * 两者都保留 —— 服务端都认，但历史调用点选了哪个就沿用哪个。
 */
object EmbyPaths {

  /**
   * Emby 的固定 URL 前缀。
   *
   * 需要「在已有 URL 上改路径」的场合（`Uri.buildUpon().path(...)`）必须用它，
   * 不要写 `"/emby"` 字面量 —— 反代改写前缀时这一处改了全仓才跟着变。
   */
  const val PREFIX: String = "/emby"

  // ══════════════════════════════════════════════════════════════════════
  // 系统 / 认证
  // ══════════════════════════════════════════════════════════════════════

  /** `GET /System/Info/Public` —— 不需要 token，登录前探测服务端版本用 */
  const val SYSTEM_INFO_PUBLIC: String = "/System/Info/Public"

  /** `POST /Users/AuthenticateByName` —— 唯一一个不需要 token 的用户端点 */
  const val AUTHENTICATE_BY_NAME: String = "/Users/AuthenticateByName"

  // ══════════════════════════════════════════════════════════════════════
  // 用户
  // ══════════════════════════════════════════════════════════════════════

  /** `GET /Users/{UserId}` —— 取当前用户资料（含 Policy.IsAdministrator） */
  fun user(userId: String): String = "/Users/$userId"

  /** `GET /Users/{UserId}/Views` —— 媒体库清单，库名的唯一来源 */
  fun userViews(userId: String): String = "/Users/$userId/Views"

  /** `GET /Users/{UserId}/Items` —— 用户视角的条目查询（可按 ParentId 限定目录） */
  fun userItems(userId: String): String = "/Users/$userId/Items"

  /** `GET /Users/{UserId}/Items/Resume` —— 继续观看 */
  fun userResume(userId: String): String = "/Users/$userId/Items/Resume"

  /** `GET /Users/{UserId}/Items/Latest` —— 最近添加 */
  fun userLatest(userId: String): String = "/Users/$userId/Items/Latest"

  /** `GET /Users/{UserId}/Items/{ItemId}` —— 带用户状态的单条目详情 */
  fun userItem(userId: String, itemId: String): String = "/Users/$userId/Items/$itemId"

  /** `GET /Users/{UserId}/Items/{ItemId}/Intros` —— 片头（预告/广告切片） */
  fun userItemIntros(userId: String, itemId: String): String =
    "/Users/$userId/Items/$itemId/Intros"

  /** `POST /Users/{UserId}/Items/{ItemId}/UserData` —— 用户数据直写 */
  fun userItemUserData(userId: String, itemId: String): String =
    "/Users/$userId/Items/$itemId/UserData"

  /** `POST /Users/{UserId}/Items/{ItemId}/HideFromResume` —— 从「继续观看」移除 */
  fun userItemHideFromResume(userId: String, itemId: String): String =
    "/Users/$userId/Items/$itemId/HideFromResume"

  /** `POST|DELETE /Users/{UserId}/FavoriteItems/{ItemId}` —— 收藏开关 */
  fun favoriteItem(userId: String, itemId: String): String =
    "/Users/$userId/FavoriteItems/$itemId"

  /** `POST|DELETE /Users/{UserId}/PlayedItems/{ItemId}` —— 已播放开关 */
  fun playedItem(userId: String, itemId: String): String =
    "/Users/$userId/PlayedItems/$itemId"

  /**
   * `POST|DELETE /UserItems/{ItemId}/UserData` —— **旧式**兼容路由。
   *
   * 只用于「清播放进度」这一条：部分服务端版本对 UserId 前缀的形式回 200 但不落库，
   * 旧式路由反而生效。保留原名以免调用点误以为是新接口。
   */
  fun legacyUserItemUserData(itemId: String): String = "/UserItems/$itemId/UserData"

  // ══════════════════════════════════════════════════════════════════════
  // 条目（Items）
  // ══════════════════════════════════════════════════════════════════════

  /** `GET|DELETE /Items/{ItemId}` */
  fun item(itemId: String): String = "/Items/$itemId"

  /** `POST /Items/{ItemId}/Refresh` —— 触发元数据刷新 */
  fun itemRefresh(itemId: String): String = "/Items/$itemId/Refresh"

  /** `GET /Items/{ItemId}/Images` —— 已挂图片清单 */
  fun itemImages(itemId: String): String = "/Items/$itemId/Images"

  /** `POST /Items/{ItemId}/Images/{Type}` —— 上传/更换图片 */
  fun itemImage(itemId: String, imageType: String): String =
    "/Items/$itemId/Images/$imageType"

  /** `DELETE|POST /Items/{ItemId}/Images/{Type}/{Index}` —— 删除指定图片 */
  fun itemImageAt(itemId: String, imageType: String, index: Int): String =
    "/Items/$itemId/Images/$imageType/$index"

  /** `GET /Items/{ItemId}/RemoteImages/Providers` —— 可用的远程图源 */
  fun itemRemoteImageProviders(itemId: String): String =
    "/Items/$itemId/RemoteImages/Providers"

  /** `GET /Items/{ItemId}/RemoteImages` —— 远程图搜索结果 */
  fun itemRemoteImages(itemId: String): String = "/Items/$itemId/RemoteImages"

  /** `POST /Items/{ItemId}/RemoteImages/Download` —— 下载某张远程图 */
  fun itemRemoteImageDownload(itemId: String): String =
    "/Items/$itemId/RemoteImages/Download"

  /** `GET /Items/{ItemId}/ExternalIdInfos` —— TMDB/IMDB 等外部 id */
  fun itemExternalIds(itemId: String): String = "/Items/$itemId/ExternalIdInfos"

  /** `GET /Items/{ItemId}/Ancestors` —— 祖先链（由近到远），目录导航用 */
  fun itemAncestors(itemId: String): String = "/Items/$itemId/Ancestors"

  /** `GET /Items/{ItemId}/Chapters` —— 章节 */
  fun itemChapters(itemId: String): String = "/Items/$itemId/Chapters"

  /** `GET /Items/{ItemId}/Download` —— 原文件下载（支持 Range，可断点续传） */
  fun itemDownload(itemId: String): String = "/Items/$itemId/Download"

  /** `GET /Items/{ItemId}/Similar` —— 相似推荐 */
  fun itemSimilar(itemId: String): String = "/Items/$itemId/Similar"

  /** `GET /Items/{ItemId}/InstantMix` —— 即时混排 */
  fun itemInstantMix(itemId: String): String = "/Items/$itemId/InstantMix"

  /**
   * `POST /Items/{ItemId}/PlaybackInfo` —— 播放能力协商。
   *
   * ⚠️ 这是**全仓出现频率最高**的端点（服务端字幕发现、直链解析、转码协商都要用），
   * 以前被手抄了 5 遍。新增调用一律走这里，别再写字面量。
   */
  fun playbackInfo(itemId: String): String = "/Items/$itemId/PlaybackInfo"

  // ══════════════════════════════════════════════════════════════════════
  // 远程搜索（元数据 / 字幕）
  // ══════════════════════════════════════════════════════════════════════

  /** `POST /Items/RemoteSearch/{SearchType}`（Movie / Series / Episode …） */
  fun remoteSearch(searchType: String): String = "/Items/RemoteSearch/$searchType"

  /** `POST /Items/RemoteSearch/Apply/{ItemId}` —— 应用识别结果 */
  fun remoteSearchApply(itemId: String): String = "/Items/RemoteSearch/Apply/$itemId"

  /**
   * `GET /Items/{ItemId}/RemoteSearch/Subtitles/{Language}`
   *
   * @param encodedLanguage 已做 URL 编码的语言码 —— 它要走 path 段，
   *   编码动作留在调用点（那里才有原始 language 字符串）。
   */
  fun remoteSubtitles(itemId: String, encodedLanguage: String): String =
    "/Items/$itemId/RemoteSearch/Subtitles/$encodedLanguage"

  /** `POST /Items/{ItemId}/RemoteSearch/Subtitles/{SubtitleId}` —— 让服务端持久化字幕 */
  fun remoteSubtitleById(itemId: String, subtitleId: String): String =
    "/Items/$itemId/RemoteSearch/Subtitles/$subtitleId"

  // ══════════════════════════════════════════════════════════════════════
  // 字幕流
  // ══════════════════════════════════════════════════════════════════════

  /**
   * `GET /Items/{ItemId}/{MediaSourceId}/Subtitles/{Index}/Stream.{Format}`
   *
   * 与 [videoSubtitleStream] 是**同一个端点的两种官方写法**，服务端都认。
   * 取字节用这条（历史实现如此），拼给播放器用另一条。
   */
  fun itemSubtitleStream(
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String,
  ): String = "/Items/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format"

  /** `GET /Videos/{ItemId}/{MediaSourceId}/Subtitles/{Index}/Stream.{Format}` */
  fun videoSubtitleStream(
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: String,
    format: String,
  ): String = "/Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format"

  // ══════════════════════════════════════════════════════════════════════
  // 视频
  // ══════════════════════════════════════════════════════════════════════

  /** `GET /Videos/{ItemId}/stream` —— query 由调用方拼（Static / MediaSourceId …） */
  fun videoStream(itemId: String): String = "/Videos/$itemId/stream"

  /** `POST /Videos/MergeVersions?Ids=…` —— 合并多版本 */
  const val VIDEOS_MERGE_VERSIONS: String = "/Videos/MergeVersions"

  /** `DELETE /Videos/{ItemId}/AlternateSources` —— 拆开版本 */
  fun videoAlternateSources(itemId: String): String =
    "/Videos/$itemId/AlternateSources"

  // ══════════════════════════════════════════════════════════════════════
  // 会话（播放上报）
  // ══════════════════════════════════════════════════════════════════════

  /** `POST /Sessions/Playing` —— 开始播放 */
  const val SESSIONS_PLAYING: String = "/Sessions/Playing"

  /** `POST /Sessions/Playing/Progress` —— 进度心跳 */
  const val SESSIONS_PLAYING_PROGRESS: String = "/Sessions/Playing/Progress"

  /** `POST /Sessions/Playing/Stopped` —— 停止播放 */
  const val SESSIONS_PLAYING_STOPPED: String = "/Sessions/Playing/Stopped"

  // ══════════════════════════════════════════════════════════════════════
  // 定时任务
  // ══════════════════════════════════════════════════════════════════════

  /** `GET /ScheduledTasks` —— 任务清单 */
  const val SCHEDULED_TASKS: String = "/ScheduledTasks"

  /** `POST /ScheduledTasks/Running/{TaskId}` —— 立即触发一个任务 */
  fun scheduledTaskRunning(taskId: String): String = "/ScheduledTasks/Running/$taskId"

  // ══════════════════════════════════════════════════════════════════════
  // 播放列表 / 合集
  // ══════════════════════════════════════════════════════════════════════

  /** `POST /Playlists` —— 新建播放列表 */
  const val PLAYLISTS: String = "/Playlists"

  /** `GET|POST|DELETE /Playlists/{PlaylistId}/Items` */
  fun playlistItems(playlistId: String): String = "/Playlists/$playlistId/Items"

  /** `POST /Playlists/{PlaylistId}/Items/{EntryId}/Move/{NewIndex}` */
  fun playlistItemMove(playlistId: String, entryId: String, newIndex: Int): String =
    "/Playlists/$playlistId/Items/$entryId/Move/$newIndex"

  /** `POST /Collections` —— 新建合集 */
  const val COLLECTIONS: String = "/Collections"

  /** `GET|POST|DELETE /Collections/{CollectionId}/Items` */
  fun collectionItems(collectionId: String): String =
    "/Collections/$collectionId/Items"

  // ══════════════════════════════════════════════════════════════════════
  // 显示偏好（服务端记住的视图设置）
  // ══════════════════════════════════════════════════════════════════════

  /** `GET|POST /DisplayPreferences/{Id}` */
  fun displayPreferences(displayPreferencesId: String): String =
    "/DisplayPreferences/$displayPreferencesId"

  // ══════════════════════════════════════════════════════════════════════
  // 分类字典 / 人物 / 剧集结构 / 直播
  // ══════════════════════════════════════════════════════════════════════

  /** `GET /Persons` —— 演员/职员 */
  const val PERSONS: String = "/Persons"

  /** `GET /Genres` —— 类型字典 */
  const val GENRES: String = "/Genres"

  /** `GET /Studios` —— 制片厂字典 */
  const val STUDIOS: String = "/Studios"

  /** `GET /Tags` —— 标签字典 */
  const val TAGS: String = "/Tags"

  /** `GET /Years` —— 年份字典 */
  const val YEARS: String = "/Years"

  /** `GET /OfficialRatings` —— 分级字典 */
  const val OFFICIAL_RATINGS: String = "/OfficialRatings"

  /** `GET /Shows/{SeriesId}/Seasons` */
  fun showSeasons(seriesId: String): String = "/Shows/$seriesId/Seasons"

  /** `GET /Shows/{SeriesId}/Episodes` */
  fun showEpisodes(seriesId: String): String = "/Shows/$seriesId/Episodes"

  /** `GET /Shows/NextUp` —— 下一集 */
  const val SHOWS_NEXT_UP: String = "/Shows/NextUp"

  /** `GET /LiveTv/Channels` */
  const val LIVE_TV_CHANNELS: String = "/LiveTv/Channels"

  /** `GET /LiveTv/Programs` */
  const val LIVE_TV_PROGRAMS: String = "/LiveTv/Programs"
}
