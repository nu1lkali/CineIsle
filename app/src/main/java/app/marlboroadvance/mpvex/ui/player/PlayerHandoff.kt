package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter

/**
 * 播放内核之间的「交接单」。
 *
 * mpv 播放页（[PlayerActivity]）与 GSY 播放页（[GsyPlayerActivity]）是两个完全独立的
 * Activity —— 这不是将就，而是刻意隔离：备用内核出问题时影响面只在自己那一页。
 * 代价是**切换内核不能像换一个 View 那样顺手**，必须把「这次播放的全部上下文」
 * 序列化进 intent 交过去，否则切过去就只剩一个孤零零的视频：
 *
 *  · `playlist` / `playlist_index` —— 本次播放队列和「现在放到第几个」；
 *  · `playlist_titles` / `playlist_series_keys` —— 切集时的显示名，以及「记住每部剧的播放设置」的键；
 *  · `playlist_id` —— 自定义播放列表的 ID（用于回写播放历史 / 刷新"正在播放"标记）；
 *  · `position` —— 当前进度（毫秒），切过去接着放，而不是从头；
 *  · `emby_server_id` / `emby_item_ids` —— Emby 的进度回传信息，丢了服务器侧的
 *    「继续观看」就断档；
 *  · `headers` —— 网络流需要的请求头（User-Agent / Referer）。
 *
 * 两侧都先把这份单子**读成字段**（而不是一直揣着原始 intent），因为切集之后
 * 「当前播放的是哪一个」已经变了，只有从字段重建的 intent 才是对的。
 */
internal data class PlayerHandoff(
  /** 本次播放队列；单个视频时为空列表 */
  val playlist: List<Uri> = emptyList(),
  /** 与 [playlist] 下标一一对应的显示标题 */
  val titles: List<String> = emptyList(),
  /** 与 [playlist] 下标一一对应的「记忆设置」键（Emby 是 SeriesId） */
  val seriesKeys: List<String> = emptyList(),
  /** 当前播放的是 [playlist] 中的第几个 */
  val index: Int = 0,
  /** 自定义播放列表 ID（可选） */
  val playlistId: Int? = null,
  /** 当前进度（毫秒） */
  val positionMs: Long = 0L,
  /** 单文件播放时的「记忆设置」键（Emby 是 SeriesId） */
  val seriesKey: String? = null,
  /** Emby 服务器 ID（非 Emby 播放为 null） */
  val embyServerId: Long? = null,
  /** 与 [playlist] 下标一一对应的 Emby ItemId */
  val embyItemIds: List<String> = emptyList(),
  /** 网络流请求头，格式与 mpv 侧的 `headers` extra 相同 */
  val headers: Array<String>? = null,
) {
  /** 当前这一项在队列里取到的真实下标（越界时夹回合法范围） */
  val safeIndex: Int
    get() = index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))

  /** 当前这一项的显示标题 */
  fun titleAt(i: Int = safeIndex): String? = titles.getOrNull(i)?.takeIf { it.isNotBlank() }

  /** 当前这一项所属的「记忆设置」键 */
  fun seriesKeyAt(i: Int = safeIndex): String? =
    seriesKeys.getOrNull(i)?.takeIf { it.isNotBlank() } ?: seriesKey

  /** 当前这一项对应的 Emby ItemId */
  fun embyItemIdAt(i: Int = safeIndex): String? = embyItemIds.getOrNull(i)

  /**
   * 把本单子写进 intent。
   *
   * 注意 `position` 只在 > 0 时写入：mpv 侧 `POSITION_NOT_SET = 0`，
   * 写一个 0 进去等价于「从头播」，行为一致，这里索性省掉这个键。
   */
  fun writeTo(target: Intent): Intent = target.apply {
    if (playlist.isNotEmpty()) {
      putParcelableArrayListExtra(EXTRA_PLAYLIST, ArrayList(playlist))
      putExtra(EXTRA_INDEX, safeIndex)
      if (titles.isNotEmpty()) putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
      if (seriesKeys.isNotEmpty()) putStringArrayListExtra(EXTRA_SERIES_KEYS, ArrayList(seriesKeys))
    }
    playlistId?.let { putExtra(EXTRA_PLAYLIST_ID, it) }
    if (positionMs > 0) putExtra(EXTRA_POSITION, positionMs.toInt())
    seriesKey?.let { putExtra(EXTRA_SERIES_KEY, it) }

    embyServerId?.takeIf { it > 0 }?.let { putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, it) }
    if (embyItemIds.isNotEmpty()) {
      putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(embyItemIds))
    }
    headers?.let { putExtra(EXTRA_HEADERS, it) }
  }

  companion object {
    const val EXTRA_PLAYLIST = "playlist"
    const val EXTRA_TITLES = "playlist_titles"
    const val EXTRA_SERIES_KEYS = "playlist_series_keys"
    const val EXTRA_INDEX = "playlist_index"
    const val EXTRA_PLAYLIST_ID = "playlist_id"
    const val EXTRA_POSITION = "position"
    const val EXTRA_SERIES_KEY = "emby_series_key"
    const val EXTRA_HEADERS = "headers"

    /** 从 intent 读回一份交接单（缺项按空处理，绝不抛异常） */
    fun read(intent: Intent): PlayerHandoff {
      @Suppress("DEPRECATION")
      val playlist =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, Uri::class.java)
        } else {
          intent.getParcelableArrayListExtra(EXTRA_PLAYLIST)
        }) ?: emptyList<Uri>()

      val itemIds = intent.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS) ?: emptyList()

      return PlayerHandoff(
        playlist = playlist,
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList(),
        seriesKeys = intent.getStringArrayListExtra(EXTRA_SERIES_KEYS) ?: emptyList(),
        index = intent.getIntExtra(EXTRA_INDEX, 0),
        playlistId = intent.getIntExtra(EXTRA_PLAYLIST_ID, -1).takeIf { it != -1 },
        positionMs = intent.getIntExtra(EXTRA_POSITION, 0).toLong(),
        seriesKey = intent.getStringExtra(EXTRA_SERIES_KEY),
        embyServerId =
          intent.getLongExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, -1L).takeIf { it > 0 },
        embyItemIds = itemIds,
        headers = intent.getStringArrayExtra(EXTRA_HEADERS),
      )
    }
  }
}
