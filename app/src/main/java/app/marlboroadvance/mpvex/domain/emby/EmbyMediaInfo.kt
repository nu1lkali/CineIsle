package app.marlboroadvance.mpvex.domain.emby

import kotlin.math.abs
import kotlin.math.round

/**
 * Emby 媒体信息表 —— **详情页与播放页共用**的唯一事实源。
 *
 * 原先这套东西（`MediaInfoEntry` + 组装逻辑 + 一堆格式化函数）是 `EmbyDetailScreen`
 * 的私有实现，播放页要用只能复制一份；两份一旦各自演进就会「同一个条目、两个页面
 * 显示不一样」。所以整体搬到这里，两边都调 [buildEmbyMediaInfoEntries]。
 *
 * 纯 Kotlin、不依赖 Compose —— 播放页那个弹窗只负责把返回的 [MediaInfoEntry] 画出来。
 */

/** 信息表的一行：分区标题（[Section]）或键值行（[Row]） */
sealed interface MediaInfoEntry {
  data class Section(
    val title: String,
  ) : MediaInfoEntry

  data class Row(
    val label: String,
    val value: String,
  ) : MediaInfoEntry
}

/**
 * 把 Emby 返回的字段整理成「分区 + 键值行」。
 *
 * 顺序：基本信息（含首次入库 / 文件路径）→ 每个视频轨道 → 每个音频轨道 → 字幕轨道。
 * 值为空的项直接跳过，不会出现「未知 / N/A」这种噪音行。
 *
 * @param selectedMediaSourceId 多版本媒体里**当前选中的版本**；null = 用默认版本（第一条源）。
 */
fun buildEmbyMediaInfoEntries(
  item: EmbyItem,
  selectedMediaSourceId: String? = null,
): List<MediaInfoEntry> {
  val out = mutableListOf<MediaInfoEntry>()

  fun add(label: String, value: String?) {
    if (!value.isNullOrBlank()) out += MediaInfoEntry.Row(label, value)
  }

  fun section(title: String) {
    out += MediaInfoEntry.Section(title)
  }

  // ── 基本信息 ──
  // 多版本媒体：取「当前选中版本」对应的源，而不是永远拿第一条。
  // 版本名 / 容器 / 文件大小 / 总码率 / 音视频轨道都随切换而变；选中 Id 失效时回退第一条，
  // 与播放侧 effectiveEmbyMediaSourceId 的兜底规则保持一致。
  val allSources = item.MediaSources.orEmpty()
  val source =
    effectiveEmbyMediaSourceId(item, selectedMediaSourceId)
      ?.let { id -> allSources.firstOrNull { it.Id == id } }
      ?: allSources.firstOrNull()

  if (allSources.count { !it.Id.isNullOrBlank() } > 1 && source != null) {
    add("版本", embyMediaSourceLabel(source))
  }
  // ⚠️ 这里只能给**条目级**的首次入库时间：Emby 的 `MediaSourceInfo` 里根本没有日期字段
  // （官方文档的属性清单、本项目 `embyclient-reference` 生成的 MediaSourceInfo 都没有
  // `DateCreated` / `DateModified`）—— **服务端不提供「每个版本各自的入库时间」**。
  // 所以给老条目补一个新版本后这里仍是老日期；措辞用「首次入库」，别让人当成当前版本的时间。
  add("首次入库", formatIsoDate(item.DateCreated))
  add("首播日期", formatIsoDate(item.PremiereDate))
  add("时长", item.RunTimeTicks?.takeIf { it > 0 }?.let { formatEmbyDurationFull(it) })
  add("年份", item.ProductionYear?.toString())
  add("分级", item.OfficialRating)
  add("评分", item.CommunityRating?.let { "★ %.1f".format(it) })
  add("制作", item.Studios?.firstOrNull()?.Name)
  add("容器", source?.Container?.uppercase())
  add("文件大小", source?.Size?.takeIf { it > 0 }?.let { formatFileSize(it) })
  add("总码率", source?.Bitrate?.takeIf { it > 0 }?.let { formatBitrate(it) })
  // ⚠️ 文件路径是**每个版本各自**的属性（EmbyMediaSource.Path）：
  // 默认版本的 Path 恰好等于 item.Path，所以拿 item.Path 时「换到另一个版本仍是原路径」。
  // 多版本切换后要显示当前选中版本的盘路径，回退才用条目级 item.Path。
  add("文件路径", source?.Path ?: item.Path)
  if (out.isEmpty()) {
    add("名称", item.Name)
  }

  val streams = source?.MediaStreams.orEmpty()

  // ── 视频轨道 ──
  val videos = streams.filter { it.Type == "Video" }
  videos.forEachIndexed { index, v ->
    section(if (videos.size > 1) "视频轨道 ${index + 1}" else "视频")
    add("类型", "视频 (Video)")
    add("语言", languageLabel(v.Language))
    add("编码器", codecLine(v))
    add("编码标识", v.CodecTag)
    if (v.Width != null && v.Height != null) add("分辨率", "${v.Width}×${v.Height}")
    add("画面比例", v.AspectRatio)
    add("帧率", frameRateLabel(v))
    add("动态范围", (v.VideoRangeType ?: v.VideoRange)?.uppercase())
    add("比特率", v.BitRate?.takeIf { it > 0 }?.let { formatBitrate(it) })
    add("位深度", v.BitDepth?.let { "$it bit" })
    add("像素格式", v.PixelFormat)
    add("色彩空间", v.ColorSpace)
    add("传输特性", v.ColorTransfer)
    add("色彩原色", v.ColorPrimaries)
    v.IsInterlaced?.let { add("扫描方式", if (it) "隔行 (Interlaced)" else "逐行 (Progressive)") }
    v.RefFrames?.takeIf { it > 0 }?.let { add("参考帧", "$it 帧") }
  }

  // ── 音频轨道 ──
  val audios = streams.filter { it.Type == "Audio" }
  audios.forEachIndexed { index, a ->
    section(if (audios.size > 1) "音频轨道 ${index + 1}" else "音频")
    add("类型", "音频 (Audio)")
    add("语言", languageLabel(a.Language))
    add("编码器", codecLine(a))
    add("编码标识", a.CodecTag)
    add("声道", a.Channels?.let { c -> a.ChannelLayout?.let { "$c ($it)" } ?: "$c" })
    add("采样率", a.SampleRate?.takeIf { it > 0 }?.let { "$it Hz" })
    add("比特率", a.BitRate?.takeIf { it > 0 }?.let { formatBitrate(it) })
    add("位深度", a.BitDepth?.let { "$it bit" })
    a.IsDefault?.let { add("默认音轨", if (it) "是" else "否") }
  }

  // ── 字幕轨道 ──
  val subtitles = streams.filter { it.Type == "Subtitle" }
  if (subtitles.isNotEmpty()) {
    section("字幕（共 ${subtitles.size} 条）")
    subtitles.forEachIndexed { index, s ->
      val tags = buildList {
        if (s.IsDefault == true) add("默认")
        if (s.IsForced == true) add("强制")
        add(if (s.IsExternal == true) "外挂" else "内嵌")
      }
      add(
        "字幕 ${index + 1}",
        listOfNotNull(
          languageLabel(s.Language) ?: s.DisplayTitle,
          s.Codec?.uppercase(),
        ).joinToString(" · ") + " (${tags.joinToString("/")})",
      )
    }
  }

  return out
}

/**
 * 多版本媒体：算出「这次实际要用的版本 Id」。
 *
 * 只有确实存在多个版本时才返回非空 —— 单版本媒体返回 null，播放 URL 里就不会带
 * `MediaSourceId`，行为与改动前完全一致。选中的 Id 失效时（刷新后源被换掉）
 * 回退到默认版本（第一条源，与 Emby 服务端的默认一致）。
 */
fun effectiveEmbyMediaSourceId(item: EmbyItem?, selected: String?): String? {
  val sources = item?.MediaSources.orEmpty().filter { !it.Id.isNullOrBlank() }
  if (sources.size <= 1) return null
  return sources.firstOrNull { it.Id == selected }?.Id ?: sources.first().Id
}

/**
 * 一个媒体源的显示名（如 `4K · HEVC · 12.3 GB`）。
 *
 * 优先用服务端给的 `Name`（网页端下拉显示的也是它）—— 两边显示同一个词，
 * 用户才不会觉得「选的和播的不是一个版本」。没有 `Name` 才按分辨率 / 编码 / 体积拼。
 */
fun embyMediaSourceLabel(source: EmbyMediaSource): String {
  source.Name?.takeIf { it.isNotBlank() }?.let { return it }
  val video = source.MediaStreams?.firstOrNull { it.Type == "Video" }
  val parts = mutableListOf<String>()
  video?.Height?.takeIf { it > 0 }?.let { h ->
    parts += when {
      h >= 2000 -> "4K"
      h >= 1000 -> "1080P"
      h >= 700 -> "720P"
      h >= 500 -> "576P"
      else -> "${h}P"
    }
  }
  video?.Codec?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
  source.Size?.takeIf { it > 0 }?.let { parts += formatFileSize(it) }
  if (parts.isEmpty()) source.Container?.uppercase()?.let { parts += it }
  return parts.joinToString(" · ").ifBlank { "版本" }
}

/** `HEVC (Main 10 · L4.1)` 这样的编码器描述；没有档次/级别时只给编码名 */
private fun codecLine(stream: EmbyMediaStream): String? {
  val codec = stream.Codec?.uppercase() ?: return null
  val extras = buildList {
    stream.Profile?.takeIf { it.isNotBlank() }?.let { add(it) }
    stream.Level?.let { add("L$it") }
  }
  return if (extras.isEmpty()) codec else "$codec (${extras.joinToString(" · ")})"
}

/** 帧率：优先标称值，其次实际值；整数帧率不带小数点 */
private fun frameRateLabel(stream: EmbyMediaStream): String? {
  val fps = stream.FrameRate ?: stream.RealFrameRate ?: stream.AverageFrameRate ?: return null
  if (fps <= 0.0) return null
  val rounded = round(fps * 100) / 100.0
  return if (abs(rounded - rounded.toInt()) < 0.005) "${rounded.toInt()} fps" else "%.2f fps".format(rounded)
}

/** `2024-01-15T10:23:45.0000000Z` → `2024-01-15` */
private fun formatIsoDate(raw: String?): String? {
  if (raw.isNullOrBlank()) return null
  val datePart = raw.substringBefore('T')
  return datePart.takeIf { it.length >= 8 }
}

/** ISO 639-2/1 语言码 → 中文名（带原码），未知码原样返回 */
private fun languageLabel(code: String?): String? {
  if (code.isNullOrBlank()) return null
  return when (code.lowercase()) {
    "chi", "zho", "zh", "chs", "cht" -> "中文 ($code)"
    "eng", "en" -> "英语 ($code)"
    "jpn", "ja" -> "日语 ($code)"
    "kor", "ko" -> "韩语 ($code)"
    "fra", "fre", "fr" -> "法语 ($code)"
    "deu", "ger", "de" -> "德语 ($code)"
    "spa", "es" -> "西班牙语 ($code)"
    "rus", "ru" -> "俄语 ($code)"
    "und" -> "未指定"
    else -> code
  }
}

private fun formatBitrate(bps: Long): String = when {
  bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
  bps >= 1_000 -> "%d kbps".format(bps / 1_000)
  else -> "$bps bps"
}

/**
 * tick 时长 → 中文可读文本，**秒级也一定有数字**。
 *
 * 与首页/卡片用的 `formatDuration`（只到分钟、不足 1 分钟返回空串）的区别就在这里：
 * 详情页 / 播放页的「时长」如果不足 1 分钟会显示成空括号，看起来像没数据。
 */
fun formatEmbyDurationFull(ticks: Long): String {
  val totalSeconds = EmbyTicks.ticksToSeconds(ticks).coerceAtLeast(0L)
  if (totalSeconds <= 0L) return "0 秒"
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return when {
    hours > 0 -> "${hours}小时${minutes}分"
    minutes > 0 -> "${minutes}分${seconds}秒"
    else -> "${seconds}秒"
  }
}

private fun formatFileSize(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  return when {
    mb >= 1024 -> "%.2f GB".format(mb / 1024)
    else -> "%.0f MB".format(mb)
  }
}
