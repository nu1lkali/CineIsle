package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.dao.FavoriteMarkDao
import app.marlboroadvance.mpvex.database.entities.FavoriteMarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 收藏时间账本的门面（进程级单例，见 DatabaseModule）。
 *
 * 存在的理由只有一个：**Emby 不记录收藏时间**（见 [FavoriteMarkEntity] 的 KDoc），
 * 「最近收藏的排最前」只能由客户端在点红心那一刻自己落一行。
 *
 * ## 写入点必须收敛
 *
 * 只有真的把收藏状态**提交给服务端**之后才记账。两个入口，覆盖全部路径：
 *
 * 1. **单条**：`EmbyRepository.setFavorite / favorite / unfavorite / unfavoriteHard`。
 *    详情页与媒体库卡片（EmbyViewModel）、播放器控件（EmbyPlayerActions）、
 *    视界流红心（FeedViewModel）三条路最终都落到这里。
 * 2. **批量**：`EmbyViewModel.setFavoriteBatch`。它直接调静态的
 *    `EmbyClient.setFavoriteMany`，绕过了仓库，所以必须单独记一次。
 *
 * 若散落到各个页面，迟早漏一条；漏一条的表现是「排序里它凭空沉到最后」，极难排查。
 * 失败不抛：记账是**附带的**，它挂了不该让「收藏」这个主动作失败。
 */
class FavoriteClock(private val dao: FavoriteMarkDao) {

  /** itemId → 收藏时刻（epoch millis）。读失败当作「没有记录」返回空表 */
  suspend fun marks(serverId: Long): Map<String, Long> =
    withContext(Dispatchers.IO) {
      runCatching { dao.marksOf(serverId).associate { it.itemId to it.favoritedAt } }
        .getOrDefault(emptyMap())
    }

  /**
   * 给一批条目盖上「现在」这个收藏时间。
   *
   * 时间戳只取一次：批量收藏 30 条时它们就是同一批，逐条取 `currentTimeMillis()`
   * 会让同一批内部产生几十毫秒的假差异，排序结果随之稳定但不真实。
   */
  suspend fun stamp(serverId: Long, itemIds: Collection<String>, at: Long = System.currentTimeMillis()) {
    val rows =
      itemIds.filter { it.isNotBlank() }.distinct()
        .map { FavoriteMarkEntity(serverId = serverId, itemId = it, favoritedAt = at) }
    if (rows.isEmpty()) return
    withContext(Dispatchers.IO) {
      runCatching { dao.upsertAll(rows) }
    }
  }

  /** 取消收藏：把记录一并删掉（重新收藏时会重新记时间） */
  suspend fun forget(serverId: Long, itemIds: Collection<String>) {
    val ids = itemIds.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return
    withContext(Dispatchers.IO) {
      runCatching { dao.deleteAll(serverId, ids) }
    }
  }
}
