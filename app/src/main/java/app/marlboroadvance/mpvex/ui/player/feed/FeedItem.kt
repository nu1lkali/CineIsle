package app.marlboroadvance.mpvex.ui.player.feed

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import java.io.Serializable

/**
 * 视界流（仿抖音竖屏连播）里的一条视频。
 *
 * 走 `Serializable` 而不是 Parcelize：项目没有启用 kotlin-parcelize 插件，
 * 而这条数据要通过 Intent 从媒体库页传给播放器 Activity。
 *
 * @param itemId Emby 条目 Id —— 收藏、上报播放进度都靠它
 * @param title 画面底部显示的标题
 * @param url 直连播放地址（`videoStreamUrl(static = true)`）
 * @param isFavorite 进入时的收藏状态；列表页已经带过来了，省得进页面再查一遍
 * @param container 容器扩展名（`mkv` / `avi` / `rmvb` …），小写不带点。
 *   **只用于起播前预判**（见 [startPlanOf]）：内置内核的 Extractor 认不认这个容器，
 *   在起播前其实就能知道，没必要先失败两轮。拿不到就留 null，预判退回默认路径。
 * @param videoCodec 主视频轨编码（`h264` / `hevc` / `mpeg4` / `wmv3` …），同样只用于预判。
 *   它跟容器的区别见 [startPlanOf] 的说明 —— 容器决定「能不能拆开」，
 *   编码决定「拆开之后有没有人解得动」。
 */
data class FeedItem(
  val itemId: String,
  val title: String,
  val url: String,
  val isFavorite: Boolean = false,
  val container: String? = null,
  val videoCodec: String? = null,
) : Serializable {
  companion object {
    private const val serialVersionUID = 1L
  }
}

/**
 * 从 Emby 条目造一条流条目，顺手把**决定解码路径的那两个元数据**带上。
 *
 * 为什么专门写一个工厂：这两个字段以前在三个构造点各写一遍、各漏一处。
 * 它们不影响播放本身，只影响「要不要先白失败两轮」，所以漏了不会报错，
 * 只会让用户多等几秒 —— 正是这种不会报错的漏最容易一直漏下去。
 *
 * `MediaSources` 就在 `ITEM_FIELDS` 里，取它**不额外发请求**。
 */
internal fun feedItemOf(item: EmbyItem, title: String, url: String): FeedItem {
  val sources = item.MediaSources
  // ⚠️ **多版本条目不预判**（`MediaSources.size > 1`）。
  // 播放地址是 `/Videos/{id}/stream` 且不带 `MediaSourceId`，服务器给的是**它认为的
  // 默认版本**，而我们手上只有 `MediaSources[0]` —— 两者未必是同一个文件。
  // 拿 A 版本的容器去判 B 版本能不能放，就是一次纯靠运气的误判：
  // 真踩过「明明能放却被丢给 mpv」。宁可少省一轮，也不要错判。
  val source = sources?.singleOrNull()
  return FeedItem(
    itemId = item.Id.orEmpty(),
    title = title,
    url = url,
    isFavorite = item.UserData?.IsFavorite == true,
    container = source?.Container?.let(::containerToken),
    videoCodec = source?.MediaStreams
      ?.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
      ?.Codec
      ?.lowercase()
      ?.trim()
      ?.takeIf { it.isNotEmpty() },
  )
}

/**
 * 把 Emby 的 `Container` 归一成一个可比较的 token。
 *
 * 它有两种写法，必须都认：
 * - 单容器：`mkv`、`avi`、`rmvb`；
 * - ffprobe 的格式名连写：`mov,mp4,m4a,3gp,3g2,mj2`（Emby 偶尔原样透出）。
 *   取第一段就够 —— 第一段是它认为的「主容器」。
 */
private fun containerToken(raw: String): String? =
  raw.substringBefore(',')
    .trim()
    .lowercase()
    .removePrefix(".")
    .takeIf { it.isNotEmpty() }
