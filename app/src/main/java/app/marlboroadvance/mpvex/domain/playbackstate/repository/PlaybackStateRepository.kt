package app.marlboroadvance.mpvex.domain.playbackstate.repository

import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity

interface PlaybackStateRepository {
  suspend fun upsert(playbackState: PlaybackStateEntity)

  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  /**
   * 按键前缀取一条。
   *
   * 目前只有多版本媒体的「按版本进度」用它：判断某个条目有没有**任一版本**的
   * 本地进度记录（见 [app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress]）。
   */
  suspend fun getVideoDataByTitlePrefix(prefix: String): PlaybackStateEntity?

  suspend fun clearAllPlaybackStates()

  suspend fun deleteByTitle(mediaTitle: String)

  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )
}
