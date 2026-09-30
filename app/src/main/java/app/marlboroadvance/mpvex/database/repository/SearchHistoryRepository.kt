package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.SearchHistoryDao
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史的读写。
 *
 * 首页全库搜索与媒体库内搜索**共用一份历史**（同一个词在哪儿搜都算数），
 * 上限 [MAX_ENTRIES] 条，超出的按「最久没用」淘汰。
 */
class SearchHistoryRepository(private val dao: SearchHistoryDao) {
  /** 最近 [limit] 个关键词，随数据库变化自动推送 */
  fun observe(limit: Int = MAX_ENTRIES): Flow<List<String>> =
    dao.observe(limit).map { list -> list.map { it.keyword } }

  /**
   * 记一条搜索历史。
   *
   * 已存在则只更新时间与次数（主键就是关键词）；空白词直接忽略，
   * 避免「点一下就多一条空记录」。
   */
  suspend fun record(rawQuery: String) {
    val keyword = rawQuery.trim()
    if (keyword.isEmpty()) return

    val existing = dao.find(keyword)
    dao.upsert(
      SearchHistoryEntity(
        keyword = keyword,
        lastUsedAt = System.currentTimeMillis(),
        useCount = (existing?.useCount ?: 0) + 1,
      ),
    )
    dao.trim(MAX_ENTRIES)
  }

  suspend fun remove(keyword: String) = dao.delete(keyword)

  suspend fun clear() = dao.clear()

  companion object {
    /** 最多保留多少条历史 */
    const val MAX_ENTRIES = 20
  }
}
