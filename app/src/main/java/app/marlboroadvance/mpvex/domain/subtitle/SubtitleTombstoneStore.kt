package app.marlboroadvance.mpvex.domain.subtitle

import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences

/**
 * [SubtitleTombstones] 的 SharedPreferences 适配层。
 *
 * 所有实例共用一把锁：在线库、播放器和设置页可能同时删除字幕，读改写账本不能互相覆盖。
 * 写入使用 [app.marlboroadvance.mpvex.preferences.preference.Preference.setBlocking]，
 * 只有同步提交成功才允许调用方卸载轨道 / 清理附属文件。
 */
class SubtitleTombstoneStore(private val preferences: SubtitlesPreferences) {
  fun snapshot(): List<SubtitleTombstones.Tombstone> = synchronized(MUTEX) {
    runCatching {
      SubtitleTombstones.parse(preferences.subtitleTombstones.get())
    }.getOrDefault(emptyList())
  }

  fun remember(tombstone: SubtitleTombstones.Tombstone): Boolean = rememberAll(listOf(tombstone))

  /** 一次删除需要的多条 tombstone 原子提交，避免只写成功一半却已经卸载轨道。 */
  fun rememberAll(tombstones: List<SubtitleTombstones.Tombstone>): Boolean = synchronized(MUTEX) {
    if (tombstones.isEmpty()) return true
    val current = snapshot()
    val additions = tombstones.filter { candidate ->
      current.none { SubtitleTombstones.sameIdentity(it, candidate) }
    }
    if (additions.isEmpty()) return true
    val encoded = SubtitleTombstones.serialize(current + additions)
    runCatching {
      preferences.subtitleTombstones.setBlocking(encoded) &&
        preferences.subtitleTombstones.get() == encoded
    }.getOrDefault(false)
  }

  fun rememberExternal(path: String?, sourceUri: String?): Boolean =
    SubtitleTombstones.external(path, sourceUri, System.currentTimeMillis())?.let(::remember) == true

  fun rememberOnline(
    serverKey: String,
    itemId: String,
    originId: String?,
    originUri: String?,
    storagePath: String?,
  ): Boolean = SubtitleTombstones.online(
    serverKey = serverKey,
    itemId = itemId,
    originId = originId,
    originUri = originUri,
    storagePath = storagePath,
    createdAt = System.currentTimeMillis(),
  )?.let(::remember) == true

  fun rememberEmby(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    streamIndex: Int,
  ): Boolean = SubtitleTombstones.emby(
    serverId = serverId,
    itemId = itemId,
    mediaSourceId = mediaSourceId,
    streamIndex = streamIndex,
    createdAt = System.currentTimeMillis(),
  )?.let(::remember) == true

  fun isExternalIgnored(path: String?, sourceUri: String?): Boolean {
    val key = SubtitleTombstones.externalKey(path, sourceUri) ?: return false
    return snapshot().filterIsInstance<SubtitleTombstones.Tombstone.External>()
      .any { it.matches(key.path, key.sourceUri) }
  }

  fun isOnlineIgnored(
    serverKey: String,
    itemId: String,
    originId: String?,
    originUri: String?,
    storagePath: String?,
  ): Boolean {
    val key = SubtitleTombstones.onlineKey(serverKey, itemId, originId, originUri, storagePath)
      ?: return false
    return snapshot().filterIsInstance<SubtitleTombstones.Tombstone.Online>()
      .any { it.matches(key) }
  }

  fun isEmbyIgnored(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    streamIndex: Int,
  ): Boolean {
    val key = SubtitleTombstones.embyKey(serverId, itemId, mediaSourceId, streamIndex) ?: return false
    return snapshot().filterIsInstance<SubtitleTombstones.Tombstone.Emby>()
      .any { it.matches(key) }
  }

  private companion object {
    val MUTEX = Any()
  }
}
