package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity

@Dao
interface PlaybackStateDao {
  @Upsert
  suspend fun upsert(playbackStateEntity: PlaybackStateEntity)

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  /**
   * 按前缀取一条 —— 只服务于「多版本媒体按版本记的进度」。
   *
   * 详情页要判断某个条目是否**已经有任一版本**的本地进度记录（有 ⇒ 没播过的版本显示 0；
   * 没有 ⇒ 退回服务端进度）。前缀形状见 [app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress]，
   * 其中不含 `%` / `_`，所以直接拼 `%` 不会有通配符歧义。
   */
  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle LIKE :prefix || '%' LIMIT 1")
  suspend fun getFirstByTitlePrefix(prefix: String): PlaybackStateEntity?

  @Query("DELETE FROM PlaybackStateEntity")
  suspend fun clearAllPlaybackStates()

  @Query("DELETE FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle")
  suspend fun deleteByTitle(mediaTitle: String)

  @Query(
    """
    UPDATE PlaybackStateEntity 
    SET mediaTitle = :newTitle 
    WHERE mediaTitle = :oldTitle
  """,
  )
  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )

  @Query("SELECT * FROM PlaybackStateEntity")
  suspend fun getAllPlaybackStates(): List<PlaybackStateEntity>

  @Upsert
  suspend fun upsertAll(playbackStates: List<PlaybackStateEntity>)
}
