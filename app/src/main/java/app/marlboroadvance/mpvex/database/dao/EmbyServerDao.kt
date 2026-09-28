package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.flow.Flow

@Dao
interface EmbyServerDao {
  @Query("SELECT * FROM emby_servers ORDER BY createdAt ASC")
  fun observeAll(): Flow<List<EmbyServer>>

  @Query("SELECT * FROM emby_servers ORDER BY createdAt ASC")
  suspend fun getAll(): List<EmbyServer>

  @Query("SELECT * FROM emby_servers WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): EmbyServer?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(server: EmbyServer): Long

  @Update
  suspend fun update(server: EmbyServer)

  @Delete
  suspend fun delete(server: EmbyServer)

  @Query("DELETE FROM emby_servers WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("UPDATE emby_servers SET userId = :userId, apiToken = :apiToken, serverName = :serverName, version = :version, lastConnected = :lastConnected WHERE id = :id")
  suspend fun updateCredentials(
    id: Long,
    userId: String,
    apiToken: String,
    serverName: String,
    version: String,
    lastConnected: Long,
  )
}
