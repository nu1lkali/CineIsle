package app.marlboroadvance.mpvex.ui.browser.dislike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.marlboroadvance.mpvex.domain.dislike.DislikeMark
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.ui.browser.emby.putEmbyPlaybackExtras
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get

/**
 * 从管理页直接播放一条被标记的媒体。
 *
 * ## 为什么不复用 [app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel] 的播放入口
 *
 * 那个入口要的是内存里的 `EmbyItem`，而本页只有一条**精简记录**
 * （title / itemId / serverId）—— 用户当初标记时不会顺手把整份元数据存下来。
 * 所以这里重新取一次服务器配置与条目。
 *
 * ## 失败一律提示、绝不崩
 *
 * 这条记录可能是**陈旧的**：片子从库里删了、服务器下线了、token 过期了。
 * 管理页是"用户回来看历史"的地方，此时最糟的是**闪退** ——
 * 那等于"只要我标记过的东西被删了，这个 App 就打不开"。
 *
 * ⚠️ 所有 suspend 调用都必须包在 [withContext] 里或经由 suspend 上下文，
 *   `runCatching` 不会把非 suspend 上下文变成 suspend 上下文 ——
 *   在非 suspend 函数里直接 `runCatching { repository.getServer(id) }` 编译不过
 *   （报 "Suspension functions can only be called within coroutine body"）。
 */
suspend fun playFromManageScreen(context: Context, mark: DislikeMark) {
  val toast: suspend (String) -> Unit = { msg ->
    withContext(Dispatchers.Main) {
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  val serverId = mark.key.serverId
  if (serverId <= 0L) {
    // 本地文件播放进不来这条路径（本地条目没有稳定的服务器身份可用于去重与回查）。
    // 真进来了也只能提示。
    toast("这条记录没有服务器信息，无法播放")
    return
  }

  val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
  if (repository == null) {
    toast("无法访问 Emby 服务")
    return
  }

  val server = runCatching { repository.getServer(serverId) }.getOrNull()
  if (server == null) {
    toast("原服务器已不存在")
    return
  }
  if (!server.isLoggedIn) {
    toast("Emby 服务器未登录")
    return
  }

  // 拿一次条目主要是为了确认它还在（并拿准确的标题 / SeriesId）。
  // getItem 抛异常而不是返回 null，所以只能用 runCatching 兜。
  val item = runCatching { repository.getItem(server, mark.key.itemId) }.getOrNull()
  if (item == null) {
    toast("该条目已不存在于服务器")
    return
  }

  val itemId = item.Id
  if (itemId.isNullOrBlank()) {
    toast("该条目已不存在于服务器")
    return
  }

  val url = repository.videoStreamUrl(server, itemId, static = true)
  val title = item.Name?.takeIf { it.isNotBlank() } ?: mark.title

  withContext(Dispatchers.Main) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      setClass(context, PlayerActivity::class.java)
      putExtra("internal_launch", true)
      putExtra("launch_source", "emby")
      putExtra("title", title)
      putExtra("filename", title)
      // 从管理页发起 = 用户想重新看它，从头播。
      // 沿用本地续播记录的话，"上次看到 40 分钟"会让人以为是接着上次看。
      putExtra("play_from_start", true)
      putExtra("emby_series_key", item.SeriesId ?: itemId)
      // ⚠️ 用统一的 extras 写入函数（与详情页/列表页启动播放完全同一套）。
      // 少任何一个 key 的表现是"收藏键 / 不喜欢键整个不出现"，而不是报错 —— 很难查。
      putEmbyPlaybackExtras(server, listOf(itemId))
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
      .onFailure { toast("无法启动播放器：${it.message}") }
  }
}
