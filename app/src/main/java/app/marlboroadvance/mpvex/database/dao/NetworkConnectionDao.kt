package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing network connections in the database
 */
@Dao
interface NetworkConnectionDao {
  /**
   * 全部连接：**置顶的排前面**，同档内按 id（即添加顺序）。
   *
   * 排序交给 SQL 而不是 UI `.sortedBy` —— UI 层排序容易在增删后忘记重排，
   * 而且 Room 的 Flow 只在表数据变化时才发新值，靠 UI 排序会让置顶后的列表
   * 在下次数据变化前一直停在旧顺序。
   */
  @Query("SELECT * FROM network_connections ORDER BY pinned DESC, id ASC")
  fun getAllConnections(): Flow<List<NetworkConnection>>

  @Query("SELECT * FROM network_connections WHERE id = :id")
  suspend fun getConnectionById(id: Long): NetworkConnection?

  @Query("SELECT * FROM network_connections WHERE autoConnect = 1 ORDER BY id ASC")
  suspend fun getAutoConnectConnections(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(connection: NetworkConnection): Long

  @Update
  suspend fun update(connection: NetworkConnection)

  @Delete
  suspend fun delete(connection: NetworkConnection)

  @Query("UPDATE network_connections SET lastConnected = :timestamp WHERE id = :id")
  suspend fun updateLastConnected(
    id: Long,
    timestamp: Long,
  )

  /**
   * 只翻置顶位。
   *
   * 刻意不复用 `@Update`：走整体 update 会把 [NetworkConnection.password] 原样写回，
   * 而 Repository 的 `updateConnection` 还会**顺带断开活跃连接**（见其 KDoc）——
   * 置顶只是改展示顺序，不该把正在用的连接踢掉。
   */
  @Query("UPDATE network_connections SET pinned = :pinned WHERE id = :id")
  suspend fun updatePinned(
    id: Long,
    pinned: Boolean,
  )

  @Query("DELETE FROM network_connections WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  suspend fun getAllConnectionsList(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(connections: List<NetworkConnection>)
}
