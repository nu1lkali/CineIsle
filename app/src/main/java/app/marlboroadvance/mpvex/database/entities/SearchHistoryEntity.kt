package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条搜索历史。
 *
 * 主键直接是关键词本身 —— 同一个词重复搜只更新时间与次数，不会堆出一串重复项。
 *
 * 首页的「搜索全部媒体库」和媒体库内的搜索**共用这一张表**：
 * 用户在哪儿搜过的词，换个地方再搜能直接点历史复用，比按库切分更好用。
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
  /** 关键词（已 trim，比较时空格无关） */
  @PrimaryKey val keyword: String,
  /** 最后一次使用时间，列表按它倒序 */
  val lastUsedAt: Long,
  /** 累计使用次数，用于「同时间下更常用的排前面」 */
  val useCount: Int = 1,
)
