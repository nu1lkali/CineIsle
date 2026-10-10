package app.marlboroadvance.mpvex.domain.emby

/**
 * 多版本媒体的「按版本」播放进度键。
 *
 * ## 为什么需要它
 *
 * Emby 的续播进度（`UserData.PlaybackPositionTicks`）是**按条目**存的 ——
 * 同一个片子的 4K / 1080P / 导演剪辑版共用一个进度。于是「4K 看到 30 分钟，
 * 切成 1080P，结果从 30 分钟继续」这种事必然发生：服务端压根没有「版本」这个概念。
 *
 * 服务端给不了，就只能在本地记：每个版本一条记录，各记各的。
 *
 * ## 键的形状
 *
 * ```
 * embyver:<serverId>:<itemId>:<mediaSourceId>
 * ```
 *
 * 三段都取自**稳定 Id**（Emby 的 ItemId / MediaSources[i].Id 都是 GUID 形状的字符串，
 * 不含 `:`），所以键可以被稳定地重建 —— 播放页和详情页各自独立算一遍也能算出同一个键，
 * 不需要谁去读谁的数据库。
 *
 * ⚠️ 这个键只是 `PlaybackStateEntity.mediaTitle` 的一种取值（见
 * `domain/playbackstate/repository/PlaybackStateRepository`），复用同一张表，
 * **不新增表、不改 schema**，因此不涉及 Room 迁移。
 */
object EmbyVersionProgress {
  /** 与普通媒体标识区分开的前缀；`PlaybackStateOps` 的删除 / 重命名清理不会碰到它。 */
  const val PREFIX = "embyver:"

  /** 某条目某个版本的进度键。 */
  fun key(
    serverId: Long,
    itemId: String,
    mediaSourceId: String,
  ): String = "$PREFIX$serverId:$itemId:$mediaSourceId"

  /**
   * 同一个 (服务器, 条目) 下**所有版本**的键前缀。
   *
   * 用途：详情页要判断「这个条目到底有没有按版本记过进度」——
   * 有（哪怕记的是别的版本）才说明本地这套按版本进度已经生效，此时某个从没播过的
   * 版本应当显示 0；一条都没有则退回服务端进度（可能是在别的 Emby 客户端看的）。
   */
  fun itemPrefix(
    serverId: Long,
    itemId: String,
  ): String = "$PREFIX$serverId:$itemId:"

  /** 这个键是不是「按版本进度」键。 */
  fun isVersionKey(mediaTitle: String): Boolean = mediaTitle.startsWith(PREFIX)
}
