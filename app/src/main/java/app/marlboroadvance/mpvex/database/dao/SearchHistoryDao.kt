package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史的读写，**所有方法都按 [SearchHistoryEntity.serverId] 隔离**。
 *
 * 调用方（首页全库搜索 / 媒体库搜索）负责传入「当前服务器」的 id ——
 * 即 `EmbyViewModel.currentServer.id`。换服务器后 Flow 由调用方重建
 * （observe 的 remember key 带 serverId），历史面板即时切换。
 */
@Dao
interface SearchHistoryDao {
  /** 最近用过的关键词（仅当前服务器），供界面展示 */
  @Query(
    "SELECT * FROM search_history WHERE serverId = :serverId " +
      "ORDER BY lastUsedAt DESC, useCount DESC LIMIT :limit",
  )
  fun observe(serverId: Long, limit: Int): Flow<List<SearchHistoryEntity>>

  @Query(
    "SELECT * FROM search_history WHERE serverId = :serverId " +
      "ORDER BY lastUsedAt DESC, useCount DESC LIMIT :limit",
  )
  suspend fun recent(serverId: Long, limit: Int): List<SearchHistoryEntity>

  @Query("SELECT * FROM search_history WHERE serverId = :serverId AND keyword = :keyword LIMIT 1")
  suspend fun find(serverId: Long, keyword: String): SearchHistoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(item: SearchHistoryEntity)

  @Query("DELETE FROM search_history WHERE serverId = :serverId AND keyword = :keyword")
  suspend fun delete(serverId: Long, keyword: String)

  /** 只清当前服务器的历史 —— 别的服务器的记录一动不动 */
  @Query("DELETE FROM search_history WHERE serverId = :serverId")
  suspend fun clear(serverId: Long)

  /**
   * 只保留当前服务器最近 [keep] 条，多出来的删掉（防止单服务器历史无限增长）。
   *
   * 按服务器各自封顶（而不是全表一个上限）：A 服务器搜得再凶，
   * 也不会把 B 服务器的历史挤掉。
   */
  @Query(
    """
    DELETE FROM search_history
    WHERE serverId = :serverId AND keyword NOT IN (
      SELECT keyword FROM search_history
      WHERE serverId = :serverId
      ORDER BY lastUsedAt DESC, useCount DESC
      LIMIT :keep
    )
    """,
  )
  suspend fun trimForServer(serverId: Long, keep: Int)
}
