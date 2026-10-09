package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.DislikedMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DislikeDao {
  /** 按标记时间倒序 —— 管理页的展示顺序即「最近标记的排最前」。 */
  @Query("SELECT * FROM disliked_media ORDER BY markedAt DESC")
  fun observeAll(): Flow<List<DislikedMediaEntity>>

  @Query("SELECT * FROM disliked_media")
  suspend fun getAll(): List<DislikedMediaEntity>

  @Query("SELECT COUNT(*) FROM disliked_media")
  suspend fun count(): Int

  /**
   * 写入 / 覆盖一条标记。
   *
   * 用 `@Upsert` 而不是「先查再决定 insert 还是 update」：
   * 后者在两个入口（播放器、详情页）同时标记同一条时会产生竞态，
   * 而复合主键 + Upsert 让这件事由数据库原子处理。
   */
  @Upsert
  suspend fun upsert(entity: DislikedMediaEntity)

  @Query("DELETE FROM disliked_media WHERE serverId = :serverId AND itemId = :itemId")
  suspend fun delete(serverId: Long, itemId: String)

  @Query("DELETE FROM disliked_media")
  suspend fun deleteAll()

  /**
   * **整体替换**为给定集合。
   *
   * ⚠️ 为什么不是「逐条 diff」：diff 要处理「新增/删除/修改」三种情况，
   * 漏掉任何一种都表现为"数据与界面不一致"，而且极难复现。
   * 这里的写入量是「用户标记过的条目数」（几百行），一次事务整体覆盖
   * 既简单又原子 —— 快照本来就是全量持有的（[app.marlboroadvance.mpvex.domain.dislike.DislikeStore]），
   * 用整体替换与内存模型完全一致，不存在"内存有、库里没有"的中间态。
   *
   * 放进一个事务，避免中途失败留下半份数据。
   */
  @Transaction
  suspend fun replaceAll(entities: List<DislikedMediaEntity>) {
    deleteAll()
    if (entities.isNotEmpty()) upsertAll(entities)
  }

  @Upsert
  suspend fun upsertAll(entities: List<DislikedMediaEntity>)
}