package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
  /** 最近用过的关键词，供界面展示 */
  @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC, useCount DESC LIMIT :limit")
  fun observe(limit: Int): Flow<List<SearchHistoryEntity>>

  @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC, useCount DESC LIMIT :limit")
  suspend fun recent(limit: Int): List<SearchHistoryEntity>

  @Query("SELECT * FROM search_history WHERE keyword = :keyword LIMIT 1")
  suspend fun find(keyword: String): SearchHistoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(item: SearchHistoryEntity)

  @Query("DELETE FROM search_history WHERE keyword = :keyword")
  suspend fun delete(keyword: String)

  @Query("DELETE FROM search_history")
  suspend fun clear()

  /** 只保留最近 [keep] 条，多出来的删掉（防止表无限增长） */
  @Query(
    """
    DELETE FROM search_history
    WHERE keyword NOT IN (
      SELECT keyword FROM search_history
      ORDER BY lastUsedAt DESC, useCount DESC
      LIMIT :keep
    )
    """,
  )
  suspend fun trim(keep: Int)
}
