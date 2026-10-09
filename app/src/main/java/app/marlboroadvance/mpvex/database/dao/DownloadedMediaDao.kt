package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * 已下载内容表（清单 4.2）。
 *
 * **全部方法都是纯 Room 读** —— 不需要网络、不需要服务器在线。
 * 离线内容页就靠这一点在断网时照样列出已下的片子。
 *
 * ## 表里有「还没下完的行」是故意的
 * 任务在 [EmbyDownloadManager.enqueue] 那一刻就把元数据写进来了（此时 `localUri` 还是 null），
 * 下载完成时再回填 `localUri` / `fileSize` / `completedAt`。
 *
 * 为什么不等下完再写：下载可能中断、可能进程被杀，续传时**手上已经没有原始 [app.marlboroadvance.mpvex.domain.emby.EmbyItem] 了**
 * （那个对象来自详情页，早就没了）。元数据必须提前落库，否则「续传完成后写离线记录」这步会丢字段。
 *
 * 所以**判断「是不是真的可离线浏览」的判据是 `localUri IS NOT NULL`**，
 * 所有面向用户的查询都带这个条件。
 */
@Dao
interface DownloadedMediaDao {
  /** 已落盘（`localUri` 非空）的全部内容，按下载完成时间倒序（「最近下的」在最前） */
  @Query(
    """
    SELECT * FROM emby_downloaded_media
    WHERE localUri IS NOT NULL
    ORDER BY completedAt DESC
    """,
  )
  fun observeDownloaded(): Flow<List<DownloadedMediaEntity>>

  /**
   * 某个服务器上已落盘的内容。
   *
   * @param serverId 传 `null` 表示**不限服务器**（看全部）。
   *   写成 `(:serverId IS NULL OR serverId = :serverId)` 而不是让调用方分两次查询：
   *   Room 的 `IS NULL` 判断在 SQL 层做，Flow 只会因为真正相关的行变化才发射。
   */
  @Query(
    """
    SELECT * FROM emby_downloaded_media
    WHERE localUri IS NOT NULL AND (:serverId IS NULL OR serverId = :serverId)
    ORDER BY completedAt DESC
    """,
  )
  fun observeDownloadedByServer(serverId: Long?): Flow<List<DownloadedMediaEntity>>

  /** 一次性快照，用于「共下了多少个 / 占了多少空间」这类统计 */
  @Query("SELECT * FROM emby_downloaded_media WHERE localUri IS NOT NULL AND (:serverId IS NULL OR serverId = :serverId)")
  suspend fun getDownloaded(serverId: Long? = null): List<DownloadedMediaEntity>

  @Query("SELECT * FROM emby_downloaded_media WHERE itemId = :itemId LIMIT 1")
  suspend fun find(itemId: String): DownloadedMediaEntity?

  @Query("SELECT COUNT(*) FROM emby_downloaded_media WHERE localUri IS NOT NULL")
  fun observeDownloadedCount(): Flow<Int>

  @Query(
    "SELECT COALESCE(SUM(CASE WHEN fileSize > 0 THEN fileSize ELSE 0 END), 0) " +
      "FROM emby_downloaded_media WHERE localUri IS NOT NULL",
  )
  fun observeDownloadedBytes(): Flow<Long>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(media: DownloadedMediaEntity)

  /**
   * 删一条已下载内容**记录**。
   *
   * 名字里刻意带 `Record` 而不是 `delete` ——
   * 删记录 ≠ 删文件。文件由调用方（下载管理页「删除文件」按钮）单独删，
   * 两者必须分开：只想把条目从离线列表里摘掉时，不该顺手把用户下的片子也删了。
   */
  @Query("DELETE FROM emby_downloaded_media WHERE itemId = :itemId")
  suspend fun deleteRecord(itemId: String)

  @Query("DELETE FROM emby_downloaded_media")
  suspend fun clear()
}
