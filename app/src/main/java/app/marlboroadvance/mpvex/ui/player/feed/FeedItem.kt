package app.marlboroadvance.mpvex.ui.player.feed

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
 */
data class FeedItem(
  val itemId: String,
  val title: String,
  val url: String,
  val isFavorite: Boolean = false,
) : Serializable {
  companion object {
    private const val serialVersionUID = 1L
  }
}
