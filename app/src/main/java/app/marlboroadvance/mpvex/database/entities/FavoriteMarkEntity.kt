package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity

/**
 * 「这条媒体是**什么时候**被我收藏的」的本地账本。
 *
 * ## 为什么需要自己记
 *
 * Emby / Jellyfin 的收藏只是一个 **布尔状态**（`UserData.IsFavorite`），
 * 服务端**没有**「收藏时间」这个字段 —— 实证三条：
 *
 * 1. `UserItemDataDto`（本项目对应 `EmbyUserData`）只有
 *    `Played / PlayCount / IsFavorite / PlaybackPositionTicks / PlayedPercentage /
 *    UnplayedItemCount / LastPlayedDate`，没有任何 favoritedAt；
 * 2. `ItemSortBy` 枚举全量里也没有 `DateFavorited` 一类取值
 *    （只有 `DateCreated` / `DatePlayed` / `DateLastContentAdded`）；
 * 3. Jellyfin 官方功能请求 #4193「sort favourites by the date they were favourited」
 *    至今未实现，原话就是「收藏目前只是 yes/no 状态」。
 *
 * 所以「最近收藏的排最前」这件事**只能本机记**：在这台设备上点红心的那一刻落一行。
 * 代价说清楚：在别的客户端（Emby Web / 其它设备）收藏的条目本机没有记录，
 * 排序时用它的入库时间（`DateCreated`）当近似值兜底 —— 见收藏页 `FavoriteSort.FAVORITED`。
 *
 * ## 为什么主键是 (serverId, itemId)
 *
 * 同一条目在不同服务器上可以是两个独立的收藏，时间各算各的；
 * 而「同一条目只能有一行」是**业务语义**，交给数据库保证比每次查询时去重可靠。
 */
@Entity(
  tableName = "favorite_marks",
  primaryKeys = ["serverId", "itemId"],
)
data class FavoriteMarkEntity(
  val serverId: Long,
  val itemId: String,
  /** 收藏时刻（epoch millis）。取消收藏时整行删除，不做软删 —— 这里没有「原因」要保留 */
  val favoritedAt: Long,
)
