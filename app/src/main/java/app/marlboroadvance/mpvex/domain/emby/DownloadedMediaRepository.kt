package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.dao.DownloadedMediaDao
import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 已下载内容仓库（清单 4.2）。
 *
 * 存在的意义是把「离线可浏览」这件事收敛到一处：首页降级、离线内容页、
 * 播放前的可播性检查都从这里拿数据，三处看到的永远是同一份。
 *
 * **所有读取都是纯 Room 读**，不碰网络 —— 这正是离线时能用的原因。
 */
class DownloadedMediaRepository(
  private val dao: DownloadedMediaDao,
) {
  /**
   * 已落盘的离线内容。
   *
   * @param serverId 传 `null` = 不限服务器（用户想看「所有服务器下过的东西」）；
   *   传具体 id 则只看那台服务器的。换服务器的场景下这个参数是必须的 ——
   *   不然会列出别的主机下、但仍然占着本机空间的条目，点了却播不了。
   */
  fun observeDownloaded(serverId: Long? = null): Flow<List<DownloadedMediaEntity>> =
    dao.observeDownloadedByServer(serverId)

  fun observeCount(): Flow<Int> = dao.observeDownloadedCount()

  fun observeTotalBytes(): Flow<Long> = dao.observeDownloadedBytes()

  suspend fun getDownloaded(serverId: Long? = null): List<DownloadedMediaEntity> =
    dao.getDownloaded(serverId)

  suspend fun find(itemId: String): DownloadedMediaEntity? = dao.find(itemId)

  /** 某条目有没有下好的本地文件 */
  fun observeDownloadedIds(): Flow<Set<String>> =
    dao.observeDownloaded().map { list -> list.mapNotNull { it.localUri }.toSet() }

  /** 只摘记录、不删文件（用户在离线列表里「从列表移除」） */
  suspend fun removeRecord(itemId: String) = dao.deleteRecord(itemId)
}
