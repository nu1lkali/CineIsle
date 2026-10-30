package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.FavoriteMarkEntity

/**
 * 收藏时间账本的读写（见 [FavoriteMarkEntity] 的 KDoc：服务端没有这个字段，只能本地记）。
 *
 * 全部按 `serverId` 隔离：同一条目在两个服务器上是两笔独立的收藏。
 */
@Dao
interface FavoriteMarkDao {
  /** 某台服务器上所有记过时间的收藏。收藏页进页面时读一次，之后全程在内存里排 */
  @Query("SELECT * FROM favorite_marks WHERE serverId = :serverId")
  suspend fun marksOf(serverId: Long): List<FavoriteMarkEntity>

  /**
   * 记一笔收藏时间。
   *
   * 用 `@Upsert`：同一条目重复收藏（取消后又收藏）时直接改时间，
   * 不需要「先查再 insert/update」那套会引入竞态的写法。
   */
  @Upsert
  suspend fun upsert(entity: FavoriteMarkEntity)

  @Upsert
  suspend fun upsertAll(entities: List<FavoriteMarkEntity>)

  /** 取消收藏：整行删掉。保留一行旧时间只会让「重新收藏」读到过期值 */
  @Query("DELETE FROM favorite_marks WHERE serverId = :serverId AND itemId = :itemId")
  suspend fun delete(serverId: Long, itemId: String)

  @Query("DELETE FROM favorite_marks WHERE serverId = :serverId AND itemId IN (:itemIds)")
  suspend fun deleteAll(serverId: Long, itemIds: List<String>)
}
