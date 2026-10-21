package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity

/**
 * 一条搜索历史，**按「服务器 + 用户」隔离**（主键带 [serverId]）。
 *
 * ## 为什么主键是 (serverId, keyword) 而不是 keyword
 *
 * 同一个词重复搜只更新时间与次数（不会堆重复项）——这个语义保留，
 * 但作用域从「全局」收窄到「一台服务器」：
 *
 * - `EmbyServer` 一行 = 一个「主机 + 用户名 + 密码」组合（登录后各自写
 *   userId / apiToken）。同一台物理服务器上不同用户是**不同的行**，
 *   所以 [serverId] 本身就是「服务器 + 用户」的复合标识。
 * - 用户常年在多台服务器 / 多个账号之间切换（家里一套、公司一套，
 *   或者同一台机器上自己和家人各一个号）。历史混在一起意味着
 *   **在 A 的搜索框里能看到 B 的搜索词**——既串数据，也可能泄露
 *   「对方看过什么」。按 serverId 分开之后，各搜各的、互不可见。
 *
 * 这与「标记为不喜欢」（[DislikedMediaEntity]）、下载任务、聚合库等
 * 所有 Emby 相关持久化数据的隔离粒度一致——全部按 serverId。
 *
 * 首页的「搜索全部媒体库」和媒体库内的搜索**共用这一张表**
 * （用户在哪儿搜过的词，换个地方再搜能直接点历史复用），
 * 但共用范围限于**同一服务器内**。
 */
@Entity(
  tableName = "search_history",
  primaryKeys = ["serverId", "keyword"],
)
data class SearchHistoryEntity(
  /** 归属的服务器（= EmbyServer.id，已隐含登录用户，见类注释） */
  val serverId: Long,
  /** 关键词（已 trim，比较时空格无关） */
  val keyword: String,
  /** 最后一次使用时间，列表按它倒序 */
  val lastUsedAt: Long,
  /** 累计使用次数，用于「同时间下更常用的排前面」 */
  val useCount: Int = 1,
)
