package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.AggregateLibraryDao
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「聚合库」仓库：DAO 之上做成员 Id 的序列化 / 解析收口。
 *
 * **序列化只此一处**：Entity 里存的是逗号分隔串，任何地方要拿到「成员库 Id 列表」
 * 都走 [membersOf] / [serializeMembers]，别在界面里自己 split ——
 * 手写第二份早晚会和 Entity 的约定对不上（对不上的表现是「编辑时成员少了一个」，
 * 极难复现）。
 */
class AggregateLibraryRepository(private val dao: AggregateLibraryDao) {

  fun observeByServer(serverId: Long): Flow<List<AggregateLibraryEntity>> =
    dao.observeByServer(serverId)

  suspend fun getByServer(serverId: Long): List<AggregateLibraryEntity> =
    dao.getByServer(serverId)

  suspend fun getAll(): List<AggregateLibraryEntity> = dao.getAll()

  suspend fun getById(id: Long): AggregateLibraryEntity? = dao.getById(id)

  suspend fun upsert(entity: AggregateLibraryEntity): Long = dao.upsert(entity)

  suspend fun deleteById(id: Long) = dao.deleteById(id)

  suspend fun deleteByServer(serverId: Long) = dao.deleteByServer(serverId)

  /** 下一个排序位：当前最大 + 1（新建的聚合库排在已有之后） */
  suspend fun nextSortIndex(serverId: Long): Int =
    (dao.getByServer(serverId).maxOfOrNull { it.sortIndex } ?: 0) + 1

  companion object {
    /**
     * 成员库 Id 列表 → 存储串。去重、保序（用户勾选顺序即展示顺序）。
     *
     * 空串 = 没有成员（不允许持久化这种状态，见管理页校验；这里只是兜底）。
     */
    fun serializeMembers(ids: List<String>): String =
      ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /** 存储串 → 成员库 Id 列表。顺序与写入时一致。 */
    fun membersOf(raw: String): List<String> =
      raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Flow 版：把 Entity 流映射成「Id 列表」流（首页只关心有哪些库要聚合） */
    fun Flow<List<AggregateLibraryEntity>>.mapMembers(): Flow<List<List<String>>> =
      map { list -> list.map { membersOf(it.memberLibraryIds) } }
  }
}
