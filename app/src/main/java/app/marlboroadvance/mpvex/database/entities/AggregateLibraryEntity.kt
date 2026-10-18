package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「聚合库」：把同一台服务器上**多个同类型**的 Emby 媒体库，在本 App 内聚合成一个视图。
 *
 * ## 它不是什么
 * 它不是 Emby 服务端的 VirtualFolder（虚拟库）：本 App **不写服务器**，
 * 建出来的只是本地一条「成员库清单」，首页多一张卡片、点进去多一个页面。
 * 服务端无「合并库」原生概念（`LibraryStructureService` 只能建带路径并集的新物理库，
 * 单 CollectionType、要管理员密钥、动的是服务器目录结构），所以本地聚合是唯一
 * 「不动服务器、还能混着看」的形态。
 *
 * ## 成员库 Id 的存法
 * [memberLibraryIds] 是**逗号分隔的字符串**而不是关联表：
 * - 成员数量天然有界（用户不会把 50 个库合成一个），一条记录几百字节封顶；
 * - 读的时候整条读走、`split` 一下就用，没有 N+1 查询；
 * - 改成员就是改这一列的字符串，编辑路径只有一条（管理页整体替换），不存在并发 diff。
 *
 * 库 Id 是 Emby 的十六进制串（如 `a1b2c3...`），**不含逗号**，所以这个序列化是无损的
 * （与 `LibraryOrdering` 对置顶表 / 顺序号的序列化同一假设）。
 *
 * ## 为什么带 [collectionType]
 * 同类型是产品约束（多个电影库合并、多个剧集库合并）。建的时候校验、
 * 读的时候若某成员库在服务端被换过类型，首页/详情页据此给出「类型不一致」提示，
 * 而不是默默把两种内容混在一屏里。
 */
@Entity(
  tableName = "aggregate_libraries",
  indices = [Index(value = ["serverId"])],
)
data class AggregateLibraryEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  /** 归属的服务器（[app.marlboroadvance.mpvex.domain.emby.EmbyServer.id]） */
  val serverId: Long,
  /** 用户给这个聚合库起的名字（首页卡片与页顶都显示它） */
  val name: String,
  /** 成员库 Id，逗号分隔；至少 2 个才有意义（1 个 = 直接用原库） */
  val memberLibraryIds: String,
  /** 成员库共有的 CollectionType（movies / tvshows / music …），可能为空 */
  val collectionType: String? = null,
  /** 同服务器内的手工排序位，越小越靠前 */
  val sortIndex: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
)
