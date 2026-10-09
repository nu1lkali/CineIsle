package app.marlboroadvance.mpvex.ui.player.controls

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPlaybackReporter
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject

/**
 * 「当前正在播放的 Emby 媒体」上下文，供在线字幕搜索用。
 *
 * @property server 对应服务器（按 `EXTRA_SERVER_ID` 查出来，不是「当前服务器」——
 *   用户可能在播放页里切过服务器，但正在播的这部片子属于另一台）
 * @property itemId 当前这一集的 ItemId
 * @property mediaSourceId 当前媒体源 Id；**可能为空**（启动时没带 MediaSources 字段）
 * @property itemName 媒体名（`mediaTitle` 为空时兜底给字幕目录命名用）
 */
data class EmbySubtitleContext(
  val server: EmbyServer,
  val itemId: String,
  val mediaSourceId: String?,
  val itemName: String,
)

/**
 * 从宿主 Activity 的 intent 里解析 [EmbySubtitleContext]；不是 Emby 播放就返回 null。
 *
 * ## 为什么要按「切集后的当前下标」取值
 *
 * 播放器支持连播（`playlist` + `playlist_index`），intent 里带的是**整串**的
 * itemIds / mediaSourceIds。所以每切一集都要按当前 `playlist_index` 重新取 ——
 * 固定取第 0 个的话，连播到第 5 集会去搜第 1 集的字幕（用户看到的是「搜出来的字幕
 * 全对不上」，但很难联想到是下标错了）。
 */
@Composable
fun rememberEmbySubtitleContext(
  playlistIndex: () -> Int = { 0 },
): EmbySubtitleContext? {
  val repository = koinInject<EmbyRepository>()
  val context = LocalContext.current
  val activity = context as? Activity

  val intent = activity?.intent
  val serverId = intent?.getLongExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, -1L) ?: -1L
  val itemIds = remember(intent) {
    intent?.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS) ?: emptyList()
  }
  val mediaSourceIds = remember(intent) {
    intent?.getStringArrayListExtra(EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID) ?: emptyList()
  }

  // 当前下标要在**每次重组**都重算：切集会变，而这里没有 StateFlow 可 collect
  // （playlist_index 不进 ViewModel 状态）。所以用「下标变化时重取」的写法。
  val index = playlistIndex()
  val itemId = remember(itemIds, index) { itemIds.getOrNull(index) }
  val mediaSourceId = remember(mediaSourceIds, index) {
    mediaSourceIds.getOrNull(index)?.takeIf { it.isNotBlank() }
  }

  var server by remember(serverId) { mutableStateOf<EmbyServer?>(null) }
  LaunchedEffect(serverId) {
    server = if (serverId > 0) repository.getServer(serverId) else null
  }

  return remember(server, itemId, mediaSourceId) {
    val s = server
    val id = itemId
    if (s == null || id.isNullOrBlank()) null
    else EmbySubtitleContext(
      server = s,
      itemId = id,
      mediaSourceId = mediaSourceId,
      itemName = id,
    )
  }
}