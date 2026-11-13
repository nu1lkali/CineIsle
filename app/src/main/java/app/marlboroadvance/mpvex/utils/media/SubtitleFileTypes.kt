package app.marlboroadvance.mpvex.utils.media

/**
 * 「哪些扩展名算字幕」的**唯一口径**。
 *
 * 原来这份清单是 `PlayerViewModel` 私有 companion 里的一串字面量，
 * 只有「添加字幕」那条路能看见；而字幕目录推荐（`LocalSubtitleScanner`）
 * 也必须用同一份，否则会出现「列表里列出来了，点一下报字幕格式不支持」。
 *
 * 白名单刻意列得宽：mpv 自己能解的就都放进来，宁可多认几个不常见的，
 * 也不要让用户的冷门格式被判成「不是字幕」。
 */
object SubtitleFileTypes {
  val EXTENSIONS: Set<String> = setOf(
    // 常见 & 现代
    "srt", "vtt", "ass", "ssa",
    // DVD / Blu-ray
    "sub", "idx", "sup",
    // 流媒体 / XML / 专业
    "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",
    // 在线平台
    "sbv", "srv1", "srv2", "srv3", "json",
    // 遗留 & 冷门
    "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",
    // 广播字幕
    "scc", "vttx",
    // 卡拉OK / 歌词
    "lrc", "krc",
    // 兜底 / 纯文本
    "txt", "pgs",
  )

  fun isSubtitle(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
