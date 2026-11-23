package app.marlboroadvance.mpvex.domain.subtitle.online

/**
 * 一次「给这部媒体找在线字幕」的上下文。
 *
 * 三个入口（播放页 / 详情页 / 字幕实验室）都把它算出来再交给同一套业务逻辑，
 * 各页面只负责「从哪里拿到这几个值」。
 *
 * @property serverKey Emby 服务器标识（`EmbyServer.id`）。同一个 ItemId 在不同服务器
 *   上可能是完全不同的片子，所以必须带上（需求 10 末段）。
 * @property itemId Emby ItemId。
 * @property mediaTitle 用于匹配与落盘目录的媒体名：优先**原始文件名**（含扩展名），
 *   拿不到时退化成片名。匹配器按文件名口径解析，用片名会丢掉番号。
 * @property embyTitle Emby 展示标题，仅当 [mediaTitle] 为空时作为关键词兜底。
 * @property mediaSourceId 多版本媒体的当前版本（可为 null）。
 */
data class OnlineSubtitleTarget(
  val serverKey: String,
  val itemId: String,
  val mediaTitle: String,
  val embyTitle: String? = null,
  val mediaSourceId: String? = null,
) {
  /** 关键词建议（打开面板时预填）。 */
  fun suggestKeyword(): SubtitleKeywordHint =
    OnlineSubtitleKeyword.suggest(
      rawTitle = mediaTitle.takeIf { it.isNotBlank() },
      embyTitle = embyTitle?.takeIf { it.isNotBlank() },
    )

  /** 匹配用的名字：没有原始文件名时用片名，两者都没有就不打分。 */
  fun matchName(): String? =
    mediaTitle.takeIf { it.isNotBlank() } ?: embyTitle?.takeIf { it.isNotBlank() }
}
