package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.domain.network.FolderCoverCacheEntity

/**
 * DAO for folder cover cache (first photo path per network folder)
 *
 * 只被 [app.marlboroadvance.mpvex.repository.NetworkRepository] 直接使用 ——
 * 上层（ViewModel）永远走 Repository 的 L1→L2 两级查询，不碰这张表。
 */
@Dao
interface FolderCoverCacheDao {
  @Query("SELECT * FROM folder_cover_cache WHERE connectionId = :connectionId AND path = :path LIMIT 1")
  suspend fun get(connectionId: Long, path: String): FolderCoverCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: FolderCoverCacheEntity)

  /** 刷新当前目录后，按子文件夹 path 批量失效（封面可能已经变了） */
  @Query("DELETE FROM folder_cover_cache WHERE connectionId = :connectionId AND path IN (:paths)")
  suspend fun deleteByPaths(connectionId: Long, paths: List<String>)

  /** 连接被删时清掉它的封面缓存（否则行会越积越多） */
  @Query("DELETE FROM folder_cover_cache WHERE connectionId = :connectionId")
  suspend fun deleteByConnection(connectionId: Long)

  /**
   * 清空整表。
   *
   * 唯一用途：**封面选择算法换口径**时一次性作废旧结果（见
   * [app.marlboroadvance.mpvex.domain.network.FOLDER_COVER_CACHE_VERSION]）。
   * 不清的话，用户升级后看到的仍是旧算法挑出来的封面，要等 30 天 TTL 或手动下拉刷新，
   * 表现就是「改了跟没改一样」。
   */
  @Query("DELETE FROM folder_cover_cache")
  suspend fun clearAll()

  /** TTL 清理：删掉超过 ttlMillis 没刷新过的行 */
  @Query("DELETE FROM folder_cover_cache WHERE updatedAt < :cutoff")
  suspend fun deleteOlderThan(cutoff: Long)
}
