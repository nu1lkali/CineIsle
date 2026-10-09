package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「标记为不喜欢」的持久化行。
 *
 * ## 为什么主键是两个字段而不是自增 id
 *
 * 去重是**业务语义**（同一条视频只能有一条标记），而不是"我插入了几次"。
 * 用 `autoGenerate` 主键的话，重复标记会插出多行，去重就得靠查询时过滤 ——
 * 而"表里可能有多份重复"这件事会一路传染到每个读的地方。
 * 直接用 `(serverId, itemId)` 复合主键 ⇒ 去重由数据库保证，读出来的天然干净。
 *
 * `REPLACE` 冲突策略配合 upsert，见 [app.marlboroadvance.mpvex.database.dao.DislikeDao]。
 */
@Entity(
  tableName = "disliked_media",
  primaryKeys = ["serverId", "itemId"],
  indices = [Index(value = ["markedAt"])],
)
data class DislikedMediaEntity(
  val serverId: Long,
  val itemId: String,
  val title: String,
  /** 空串 = 没填备注（不是 null，见 [DislikeMark.reason]） */
  val reason: String,
  val markedAt: Long,
  /** 可能为空（本地播放 / 图片加载失败），故可空。 */
  val imageUrl: String? = null,
  /**
   * 当前是否处于已标记状态。`false` = 已取消标记但**记录与原因都留着**（管理页置灰显示）。
   * 与「删除」（整条不在表里）是两件不同的事，见 DislikeMark 的 KDoc。
   */
  val active: Boolean = true,
)