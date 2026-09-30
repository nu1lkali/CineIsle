package app.marlboroadvance.mpvex.ui.player
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.util.Log
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.serialization.json.Json

/**
 * 「每部剧记住播放速度 / 音轨」的读写。
 *
 * 键是 Emby 的 SeriesId（剧集）或 ItemId（电影），由播放页从 intent 里取；
 * 本地播放没有这个键，所有读写都会直接返回，不产生任何副作用。
 *
 * 存的是 JSON 字符串（SeriesKey → 值），两个开关各自独立：
 * 关掉开关只停止**写入**，已存的数据保留，重新打开仍然生效。
 */
object PlaybackMemory {
  private const val TAG = "PlaybackMemory"
  private val json = Json { ignoreUnknownKeys = true }

  // ==================== 播放速度 ====================

  /** 取某个剧记住的倍速；没记过 / 没开开关 / 没有 key 都返回 null */
  fun speedFor(
    prefs: PlayerPreferences,
    key: String?,
  ): Float? {
    if (key.isNullOrBlank()) return null
    return readMap<Float>(prefs.rememberedSpeeds.get())[key]
  }

  /** 记住某个剧的倍速。开关关闭时直接忽略（不写盘）。 */
  fun saveSpeed(
    prefs: PlayerPreferences,
    key: String?,
    speed: Float,
  ) {
    if (key.isNullOrBlank() || speed <= 0f) return
    if (!prefs.rememberSpeedPerSeries.get()) return
    val map = readMap<Float>(prefs.rememberedSpeeds.get()).toMutableMap()
    map[key] = speed
    runCatching { prefs.rememberedSpeeds.set(json.encodeToString(map)) }
      .onFailure { Log.w(TAG, "save speed failed", it) }
  }

  // ==================== 音轨 ====================

  /** 取某个剧记住的音轨指纹 */
  fun audioFingerprintFor(
    prefs: PlayerPreferences,
    key: String?,
  ): String? {
    if (key.isNullOrBlank()) return null
    return readMap<String>(prefs.rememberedAudioTracks.get())[key]
  }

  /** 记住某个剧当前选中的音轨。开关关闭时直接忽略。 */
  fun saveAudioTrack(
    prefs: PlayerPreferences,
    key: String?,
    track: TrackNode,
  ) {
    if (key.isNullOrBlank()) return
    if (!prefs.rememberAudioTrackPerSeries.get()) return
    val fingerprint = fingerprintOf(track)
    val map = readMap<String>(prefs.rememberedAudioTracks.get()).toMutableMap()
    map[key] = fingerprint
    runCatching { prefs.rememberedAudioTracks.set(json.encodeToString(map)) }
      .onFailure { Log.w(TAG, "save audio track failed", it) }
  }

  /**
   * 音轨指纹：**语言 + 编码 + 声道数**，例如 `jpn|aac|2`。
   *
   * 不用 mpv 的 track id —— 同一季里某集多挂一条评论轨，
   * 后面所有轨的 id 就会整体错位，按 id 记会选到完全不相干的音轨。
   */
  private fun fingerprintOf(track: TrackNode): String =
    listOf(
      track.lang?.lowercase() ?: "",
      track.codec?.lowercase() ?: "",
      (track.demuxChannelCount ?: track.audioChannels)?.toString() ?: "",
    ).joinToString("|")

  /**
   * 在当前文件里找与指纹匹配的音轨，返回它的 mpv track id；找不到返回 null。
   *
   * 直接从 MPV 的 track-list 读，而不是读 ViewModel 里的 StateFlow ——
   * 后者由属性观察回调异步填充，切集瞬间可能还没更新。
   */
  fun findAudioTrackId(fingerprint: String): Int? {
    if (fingerprint.isBlank()) return null
    val count = PlayerLib.getPropertyInt("track-list/count") ?: 0
    for (i in 0 until count) {
      if (PlayerLib.getPropertyString("track-list/$i/type") != "audio") continue
      val lang = PlayerLib.getPropertyString("track-list/$i/lang") ?: ""
      val codec = PlayerLib.getPropertyString("track-list/$i/codec") ?: ""
      val channels = PlayerLib.getPropertyInt("track-list/$i/demux-channel-count")?.toString() ?: ""
      val current = listOf(lang.lowercase(), codec.lowercase(), channels).joinToString("|")
      if (current == fingerprint) {
        return PlayerLib.getPropertyInt("track-list/$i/id")
      }
    }
    return null
  }

  // ==================== 内部 ====================

  private inline fun <reified T> readMap(raw: String): Map<String, T> =
    if (raw.isBlank()) {
      emptyMap()
    } else {
      runCatching { json.decodeFromString<Map<String, T>>(raw) }.getOrDefault(emptyMap())
    }
}
