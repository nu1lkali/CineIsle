package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import org.koin.java.KoinJavaComponent

/**
 * 启动预热（v353）：**打开 App 就开始算**，而不是等用户点进某个库。
 *
 * ## 用户的要求
 * 「上次排序是内容更新时间 / 手动切到它，都会有一个重组过程；希望后台先解析好，
 * 保留数据，切过去直接刷出来。」加上「打开 App 就开始，而不是进入某个媒体库才开始」。
 *
 * ## 做法
 * 服务器就绪后，把**最近访问过的那些库**（偏好里记着每个库的分类）里、
 * 分类在「文件夹」档的，逐个算一遍目录顺序写进 [FolderLatestCache]。
 * 这样用户稍后进任意一个库、切到这一档，结论都是现成的。
 *
 * ## 刻意的边界
 *  · **只预热文件夹档**：别的分类下这个排序没有语义，算了也用不上。
 *  · **不预热全部库**：只认偏好里出现过分类记录的库（即用户真的打开过的），
 *    避免为了一个可能永不访问的库拉一堆请求。
 *  · **串行、失败静默**：一次只算一个，任何一个失败都不影响下一个，
 *    更不影响启动流程。整段跑完可能几十秒，但它全在后台。
 *  · **不越过隐私门禁**：[run] 由 `runConsentedStartup` 调用。
 */
internal object FolderLatestStartupPrewarm {
  /** 文件夹档的分类名，与 UI 层 `EmbyCategory.FOLDER.name` 一致。 */
  private const val FOLDER_CATEGORY = "FOLDER"

  /** 一次启动最多预热几个库。再多就超出「顺手预热」的范围了。 */
  private const val MAX_LIBRARIES = 3

  suspend fun run(server: EmbyServer) {
    val repository: EmbyRepository =
      KoinJavaComponent.get(EmbyRepository::class.java)
    val prefs: BrowserPreferences =
      KoinJavaComponent.get(BrowserPreferences::class.java)

    // 这一台服务器下的库清单。拿不到就什么都不做 —— 预热是纯优化。
    val libraries = runCatching {
      repository.getItems(
        server = server,
        parentId = null,
        includeItemTypes = listOf("CollectionFolder"),
        recursive = false,
        limit = 100,
      ).Items.orEmpty()
    }.getOrNull().orEmpty()
    if (libraries.isEmpty()) return

    // 只认用户真的打开过、且分类停在「文件夹」的库。
    val targets = libraries
      .asSequence()
      .mapNotNull { library -> library.Id?.takeIf { it.isNotBlank() } }
      .filter { id ->
        runCatching { prefs.embyLibraryCategory(id).get() }.getOrNull() == FOLDER_CATEGORY
      }
      .take(MAX_LIBRARIES)
      .toList()
    if (targets.isEmpty()) return

    for (libraryId in targets) {
      val key = FolderLatestCache.key(server.id, server.userId, libraryId)
      if (!FolderLatestPrewarm.isNeeded(key)) continue
      FolderLatestPrewarm.warm(
        parentId = libraryId,
        key = key,
        includeTypes = FOLDER_LATEST_PREWARM_TYPES,
        children = {
          repository.getItems(
            server = server,
            parentId = libraryId,
            includeItemTypes = listOf(
              "Folder",
              "CollectionFolder",
              "BoxSet",
            ),
            recursive = false,
            limit = 500,
          ).Items.orEmpty()
        },
      ) { parentId, startIndex, limit, types ->
        repository.getItems(
          server = server,
          parentId = parentId,
          includeItemTypes = types.takeIf { it.isNotEmpty() },
          sortBy = "DateCreated",
          sortOrder = "Descending",
          startIndex = startIndex,
          limit = limit,
          recursive = true,
        )
      }
    }
  }

  /**
   * 预热的递归类型白名单。
   *
   * 与页面里那份 `FOLDER_LATEST_MEDIA_TYPES` 必须一致：预热存的是「目录 → 最新
   * 入库时间」这个事实，口径不一致会让预热的结论和页面自己算的对不上。
   * 这里独立成一份，是因为页面那份是 private，且 domain 层不该反向依赖 UI。
   */
  private val FOLDER_LATEST_PREWARM_TYPES = listOf(
    "Movie",
    "Episode",
    "Video",
    "Series",
    "Season",
    "BoxSet",
    "MusicVideo",
    "MusicAlbum",
  )
}
