package app.marlboroadvance.mpvex.domain.subtitle.online

/**
 * 网络字幕自动下载的**媒体库适用范围**纯逻辑。
 *
 * 默认全部媒体库生效；用户可以在设置里排除确定没有字幕的库，避免对其中每个视频
 * 都向字幕源发检索请求。排除项按「服务器 + 账号 + 库」三元组存，防止两服库 Id 撞车
 * （Emby 的库 Id 只在单服内唯一）。
 *
 * 这里是纯字符串逻辑，不碰 Room / 网络，便于单测固定 key 格式与空值语义。
 */
object OnlineSubtitleLibraryScope {
  /**
   * 排除项在偏好集合里的存储形态。
   *
   * 与 `EmbyClient` 的 `serverKey` 同口径（`serverId:userId`），后面再拼库 Id。
   * 用 `|` 分隔而不是`:`，避免库 Id 本身带冒号时产生歧义。
   */
  fun key(
    serverId: Long,
    userId: String,
    libraryId: String,
  ): String = "$serverId:$userId|$libraryId"

  /**
   * 该媒体库是否已被排除。
   *
   * [libraryId] 为 null / 空白时返回 false（认不出库就放行，不让配置把功能弄丢）；
   * 排除集合为空时也返回 false —— 这是默认全库生效的路径，连父目录都不用查。
   */
  fun isExcluded(
    excludedKeys: Set<String>,
    serverId: Long,
    userId: String,
    libraryId: String?,
  ): Boolean {
    if (excludedKeys.isEmpty() || libraryId.isNullOrBlank()) return false
    return key(serverId, userId, libraryId) in excludedKeys
  }

  /** 优先匹配已知库 Id，再退回祖先链中的媒体库容器。 */
  fun resolveLibraryId(
    ancestors: List<Anchor>,
    knownLibraryIds: Set<String> = emptySet(),
  ): String? {
    if (ancestors.isEmpty()) return null
    if (knownLibraryIds.isNotEmpty()) {
      ancestors.firstOrNull { it.id.isNotBlank() && it.id in knownLibraryIds }?.let {
        return it.id
      }
    }
    return ancestors.firstOrNull {
      it.id.isNotBlank() && (it.type == COLLECTION_FOLDER || it.type == USER_VIEW)
    }?.id
  }

  /** 用媒体路径与用户可见媒体库根路径匹配，最长根路径优先。 */
  fun resolveLibraryIdByPath(
    itemPath: String?,
    libraries: List<LibraryRoot>,
  ): String? {
    val path = normalizePath(itemPath) ?: return null
    return libraries
      .mapNotNull { library ->
        val root = normalizePath(library.path) ?: return@mapNotNull null
        val ignoreCase = root.startsWith("//") || Regex("^[A-Za-z]:/").containsMatchIn(root)
        val matches = path == root || path.startsWith("$root/", ignoreCase = ignoreCase)
        if (matches) library.id.takeIf { it.isNotBlank() } to root.length else null
      }
      .maxByOrNull { it.second }
      ?.first
  }

  /** [resolveLibraryId] 的最小入参形态：只要 Id 和 Type，免得纯逻辑依赖 `EmbyItem`。 */
  data class Anchor(val id: String, val type: String?)

  /** 媒体库视图的 Id 与服务端返回的根路径。 */
  data class LibraryRoot(val id: String, val path: String?)

  private fun normalizePath(path: String?): String? =
    path
      ?.trim()
      ?.replace('\\', '/')
      ?.trimEnd('/')
      ?.takeIf { it.isNotBlank() }

  private const val COLLECTION_FOLDER = "CollectionFolder"
  private const val USER_VIEW = "UserView"
}
