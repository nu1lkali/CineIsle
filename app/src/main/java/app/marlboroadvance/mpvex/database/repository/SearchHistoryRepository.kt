package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.SearchHistoryDao
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 搜索历史的读写，**按「服务器 + 用户」隔离**（每个方法第一个参数都是 serverId）。
 *
 * serverId 传 `EmbyServer.id` —— 一行 EmbyServer 就是一个「主机 + 用户」组合，
 * 所以它同时隔离了服务器与用户，与 dislike / 下载 / 聚合库同一套粒度。
 *
 * ## 切换服务器时的行为
 *
 * - **加载**：observe(serverId) 的 Flow 由界面按当前服务器重建
 *   （remember key 带 serverId），切过去后面板即时换成新服务器的历史，
 *   不需要手动清什么。
 * - **写入**：record 永远写进当前服务器名下，天然不会串到别的服务器。
 * - **清理**：remove / clear 只作用于当前服务器 —— 「清空」清的
 *   只是你此刻所在服务器的历史，别的服务器一条不少。
 *
 * 首页全库搜索与媒体库内搜索**共用同一服务器的一份历史**。
 */
class SearchHistoryRepository(private val dao: SearchHistoryDao) {
  /**
   * 最近 [limit] 个关键词（仅 [serverId] 名下），随数据库变化自动推送。
   *
   * [serverId] 为 null 表示当前没有选中的服务器（未登录 / 服务器被删空）——
   * 此时返回空列表：没有上下文既读不到任何历史，也不该把别处的数据露出来。
   */
  fun observe(serverId: Long?, limit: Int = MAX_ENTRIES): Flow<List<String>> {
    if (serverId == null) return flowOf(emptyList())
    return dao.observe(serverId, limit).map { list -> list.map { it.keyword } }
  }

  /**
   * 记一条搜索历史（归属 [serverId]）。
   *
   * 同一服务器下已存在则只更新时间与次数；空白词直接忽略，
   * 避免「点一下就多一条空记录」。记完顺手把该服务器裁到
   * [MAX_ENTRIES] 条（只裁自己，不碰别的服务器）。
   */
  suspend fun record(serverId: Long, rawQuery: String) {
    val keyword = rawQuery.trim()
    if (keyword.isEmpty()) return

    val existing = dao.find(serverId, keyword)
    dao.upsert(
      SearchHistoryEntity(
        serverId = serverId,
        keyword = keyword,
        lastUsedAt = System.currentTimeMillis(),
        useCount = (existing?.useCount ?: 0) + 1,
      ),
    )
    dao.trimForServer(serverId, MAX_ENTRIES)
  }

  /** 删掉 [serverId] 名下的某一个词 */
  suspend fun remove(serverId: Long, keyword: String) = dao.delete(serverId, keyword)

  /** 清空 [serverId] 名下的全部历史（其他服务器不受影响） */
  suspend fun clear(serverId: Long) = dao.clear(serverId)

  companion object {
    /** 每台服务器最多保留多少条历史 */
    const val MAX_ENTRIES = 20
  }
}
