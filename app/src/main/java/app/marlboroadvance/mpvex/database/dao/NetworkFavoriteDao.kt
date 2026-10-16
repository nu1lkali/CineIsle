package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.marlboroadvance.mpvex.domain.network.NetworkFavorite
import kotlinx.coroutines.flow.Flow

/**
 * DAO for favorite network directories (quick access entries)
 */
@Dao
interface NetworkFavoriteDao {
  @Query("SELECT * FROM network_favorites ORDER BY addedAt DESC")
  fun getAllFavorites(): Flow<List<NetworkFavorite>>

  @Query("SELECT * FROM network_favorites ORDER BY addedAt DESC")
  suspend fun getAllFavoritesList(): List<NetworkFavorite>

  @Query("SELECT * FROM network_favorites WHERE connectionId = :connectionId AND path = :path LIMIT 1")
  suspend fun getFavorite(connectionId: Long, path: String): NetworkFavorite?

  @Query("SELECT EXISTS(SELECT 1 FROM network_favorites WHERE connectionId = :connectionId AND path = :path)")
  suspend fun isFavorite(connectionId: Long, path: String): Boolean

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(favorite: NetworkFavorite): Long

  @Query("DELETE FROM network_favorites WHERE connectionId = :connectionId AND path = :path")
  suspend fun delete(connectionId: Long, path: String)

  @Query("DELETE FROM network_favorites WHERE id = :id")
  suspend fun deleteById(id: Long)

  /** 连接被删时清掉它的收藏（否则快捷入口点进去是死的） */
  @Query("DELETE FROM network_favorites WHERE connectionId = :connectionId")
  suspend fun deleteByConnection(connectionId: Long)
}
