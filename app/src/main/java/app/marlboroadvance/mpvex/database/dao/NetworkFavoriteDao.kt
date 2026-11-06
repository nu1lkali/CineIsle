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

  /**
   * 某台连接下**全部**已收藏的目录路径（一次查询，不按条问）。
   *
   * 给「长按子文件夹菜单」的星形状态用：一屏几十个文件夹，逐个 `isFavorite` 就是
   * 几十条 SQL；这里一条全拿回来，UI 侧用 `Set` 判存在即可。
   */
  @Query("SELECT path FROM network_favorites WHERE connectionId = :connectionId")
  suspend fun getPaths(connectionId: Long): List<String>

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
