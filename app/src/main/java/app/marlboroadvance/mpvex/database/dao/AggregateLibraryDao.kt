package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「聚合库」的存取。
 *
 * 只有一台服务器上会建聚合库，所以查询一律按 serverId 收窄；
 * 换服务器时首页拿到的就是那台自己的聚合库，互不串。
 */
@Dao
interface AggregateLibraryDao {
  @Query("SELECT * FROM aggregate_libraries WHERE serverId = :serverId ORDER BY sortIndex ASC, createdAt ASC")
  fun observeByServer(serverId: Long): Flow<List<AggregateLibraryEntity>>

  @Query("SELECT * FROM aggregate_libraries WHERE serverId = :serverId ORDER BY sortIndex ASC, createdAt ASC")
  suspend fun getByServer(serverId: Long): List<AggregateLibraryEntity>

  @Query("SELECT * FROM aggregate_libraries ORDER BY sortIndex ASC, createdAt ASC")
  suspend fun getAll(): List<AggregateLibraryEntity>

  @Query("SELECT * FROM aggregate_libraries WHERE id = :id")
  suspend fun getById(id: Long): AggregateLibraryEntity?

  /**
   * 写 / 覆盖一条聚合库。
   *
   * 用 `@Upsert`：主键自增，「新建」时 id=0 走 insert，「编辑」时带上原 id 走 update，
   * 与「先查再决定 insert 还是 update」相比没有竞态窗口（管理页连续点两次「保存」
   * 不会插出两条）。
   */
  @Upsert
  suspend fun upsert(entity: AggregateLibraryEntity): Long

  @Query("DELETE FROM aggregate_libraries WHERE id = :id")
  suspend fun deleteById(id: Long)

  /** 删服务器时级联清理（服务器没了，聚合库的成员库 Id 全都失效） */
  @Query("DELETE FROM aggregate_libraries WHERE serverId = :serverId")
  suspend fun deleteByServer(serverId: Long)
}
