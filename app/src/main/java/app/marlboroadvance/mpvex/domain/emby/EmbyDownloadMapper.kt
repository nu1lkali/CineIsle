package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import app.marlboroadvance.mpvex.database.entities.EmbyDownloadEntity

/**
 * 下载任务 / 已下载内容 与 Room 实体之间的映射（清单 4.2）。
 *
 * 抽出来的理由和 `PagedList` 一样：**映射是最容易出静默错误的地方**
 * （少映射一个字段不会崩，只会「下载记录丢了进度」这种查起来很烦的 bug），
 * 所以让它能在 JVM 单测里被直接断言。
 *
 * 这里是 domain 层依赖 database 层的**唯一**一处（其余都是 database → domain）。
 * 之所以能接受：项目里 [EmbyServer] 本来就是 domain 数据类直接当 Entity 用的，
 * 这条边界本来就不是严格分层的。
 */

/** Room 行 → 运行时任务对象 */
fun EmbyDownloadEntity.toTask(): EmbyDownloadTask = EmbyDownloadTask(
  itemId = itemId,
  serverId = serverId,
  title = title,
  fileName = fileName,
  url = url,
  totalBytes = totalBytes,
  downloadedBytes = downloadedBytes,
  // ⚠️ 未知状态名要兜底而不是 valueOf 直接抛：
  // 枚举将来被删/改名时，库里存的老数据会让整个 App 在冷启动时崩。
  // 兜成 PAUSED 的语义是「有这条记录但现在没在跑」，用户点继续即可。
  status = runCatching { EmbyDownloadStatus.valueOf(status) }.getOrDefault(EmbyDownloadStatus.PAUSED),
  message = message,
  targetUri = targetUri,
  locationLabel = locationLabel,
  createdAt = createdAt,
)

/**
 * 运行时任务对象 → Room 行。
 *
 * [speedBps] 刻意不映射：它是运行期采样值，存进去只会让「速度」在重启后
 * 显示成一个早就失效的旧数字（比显示 0 更误导）。
 */
fun EmbyDownloadTask.toEntity(): EmbyDownloadEntity = EmbyDownloadEntity(
  itemId = itemId,
  serverId = serverId,
  title = title,
  fileName = fileName,
  url = url,
  totalBytes = totalBytes,
  downloadedBytes = downloadedBytes,
  status = status.name,
  message = message,
  targetUri = targetUri,
  locationLabel = locationLabel,
  createdAt = createdAt,
)

/**
 * 媒体条目 → 已下载内容行（**入队那一刻**调用，此时还没下完，`localUri` 为 null）。
 *
 * @param localUri 落盘位置；下载完成时再回填
 * @param fileSize 文件大小；未知给 -1
 * @param completedAt 完成时刻；未完成给 0
 *
 * 只取**离线浏览用得到**的字段，不整个 `encodeToString` 存 `EmbyItem` ——
 * 理由见 [DownloadedMediaEntity] 的 KDoc（递归嵌套的 Seasons/Episodes 会让单条记录膨胀到几百 KB）。
 */
fun EmbyItem.toDownloadedMediaEntity(
  serverId: Long,
  localUri: String? = null,
  fileSize: Long = -1L,
  completedAt: Long = 0L,
): DownloadedMediaEntity? {
  val id = Id ?: return null
  return DownloadedMediaEntity(
    itemId = id,
    serverId = serverId,
    title = Name ?: SeriesName ?: "未命名",
    type = Type,
    seriesName = SeriesName,
    seasonNumber = ParentIndexNumber,
    episodeNumber = IndexNumber,
    overview = Overview,
    runTimeTicks = RunTimeTicks,
    productionYear = ProductionYear,
    communityRating = CommunityRating,
    officialRating = OfficialRating,
    // ⚠️ 只取 Primary：离线页卡片用的是竖版海报，Backdrop 是横图，取了也用不上
    imageTag = ImageTags["Primary"],
    fileName = null,
    localUri = localUri,
    fileSize = fileSize,
    completedAt = completedAt,
  )
}
