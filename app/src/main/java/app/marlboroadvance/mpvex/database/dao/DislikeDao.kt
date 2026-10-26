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
   * 把一次变更**增量**写进库：只碰被改动的行，其余行原样不动。
   *
   * ## 为什么不能再用「整表替换」
   *
   * 原来的写法是 `deleteAll() + upsertAll(全量快照)`，它依赖一个前提：
   * **内存快照 == 库里的全部数据**。而这个前提在启动阶段是不成立的 ——
   * [app.marlboroadvance.mpvex.domain.dislike.DislikeRepository.load] 是异步的，
   * 在它读回来之前内存里只有「用户这几秒内新标的几条」。
   * 此时任何一次写入都会把这半份快照当成真相覆盖回去，
   * **库里原有的记录被连带删除** —— 用户看到的就是「以前标的全没了」。
   *
   * 增量写没有这个前提：写哪条就只动哪条，内存里有多少都不影响其它行。
   *
   * @param upserts 新增或内容有变化的行
   * @param removed 需要在库里删掉的行（主键对）
   */
  @Transaction
  suspend fun applyDiff(
    upserts: List<DislikedMediaEntity>,
    removed: List<Pair<Long, String>>,
  ) {
    if (upserts.isNotEmpty()) upsertAll(upserts)
    removed.forEach { (serverId, itemId) -> delete(serverId, itemId) }
  }

  /**
   * **整体替换**为给定集合。
   *
   * ⚠️ 只在「清空全部」这一种场合用（传空列表 ⇒ 等价于 `deleteAll()`）。
   * 常规变更走 [applyDiff] —— 全量覆盖的前提（内存即全库）在启动阶段不成立。
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