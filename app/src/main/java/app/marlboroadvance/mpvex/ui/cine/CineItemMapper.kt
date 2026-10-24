package app.marlboroadvance.mpvex.ui.cine

import androidx.compose.runtime.Immutable
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.playbackProgress
import app.marlboroadvance.mpvex.ui.browser.emby.remainingSeconds

/**
 * `EmbyItem` → Cine 展示模型。
 *
 * ## 为什么单独一层，而不是在组件里直接读 `EmbyItem`
 * 设计稿对每个位置的字段有明确要求（角标写什么、元信息排哪几个字段、图下那一行给谁）。
 * 如果由各个卡片组件自己拼字符串，同一个条目在「最新加入」和「随便看看」里可能
 * 显示成两种样子，而且改文案要翻遍所有组件。
 *
 * 这里是**唯一的字段映射处**：组件只消费 [CineMediaUi]，不再碰 `EmbyItem` 的字段名。
 *
 * ## 空值一律降级，不编造
 * 设计稿里的示例文案（「4K HDR」「杜比全景声」「S2 · 已更 6 集」）对应的是具体服务端数据。
 * 真实数据里没有的，这里**直接不给**（角标没有就不画、元信息缺项就不占位），
 * 绝不用「未知」「暂无」去填 —— 空着比填一句废话干净。
 */
@Immutable
data class CineMediaUi(
  val id: String,
  val item: EmbyItem,
  /** 卡片标题（海报压图 / 继续观看图下） */
  val title: String,
  /** 继续观看卡的标题：剧集用剧名，单集不会拿剧集名去冒充 */
  val wideTitle: String,
  /** 图下一行元信息：`2023 · 剧情 / 冒险` */
  val meta: String,
  /** 继续观看卡的图下元信息：`第 4 集 · 雨中的证词` / `1小时47分` */
  val wideMeta: String,
  /** 海报右上角角标（最多一个，`null` = 不画） */
  val badge: CineBadgeUi?,
  /** 继续观看卡左上角：`S1 · E4` / `影片` */
  val episodeTag: String,
  /** 播放进度 0~1；`null` = 不画进度条 */
  val progress: Float?,
  /** 继续观看卡左下角：`剩 38 分钟` */
  val remaining: String?,
  val posterUrl: String?,
  val thumbUrl: String?,
  val heroUrl: String?,
  val isFavorite: Boolean,
  /** 续播起点（秒）；0 = 从头 */
  val resumeSeconds: Long,
  val typeLabel: String,
  /** Hero 的角标行（强 + 弱 + 弱，最多 3 个） */
  val heroBadges: List<CineBadgeUi>,
  /** Hero 的元信息行分段，UI 之间插分隔点 */
  val heroMetaParts: List<String>,
  /** Hero 主按钮文案依据：有进度 = 继续观看 */
  val hasProgress: Boolean,
  /**
   * 标题艺术字（Emby `ClearLogo`，透明 PNG）。
   *
   * 详情页标题区的**首选**表现：剧集/电影的海报艺术字带字体设计，比任何系统字体都准。
   * `null` 表示这一条没有 Logo —— 此时退回居中文字标题（不能既没有图又没有字，
   * 用户会不知道自己在看什么片子）。
   */
  val logoUrl: String?,
  /**
   * 详情页信息区的第一行：`12 分钟` / `2026年9月12日` / `4K` / `SDR` / `25FPS` / `679M`。
   *
   * 全部来自 `MediaSources` 的客观字段，**拿不到就不进列表**（不写「未知」占位）。
   * Series 没有 MediaSources，所以这一行退化成「N 季 · 年份」。
   */
  val techSpecs: List<String>,
  /** 详情页信息区的类型行：`动画 · 动作冒险 · 科幻奇幻`（最多 3 个） */
  val genresLine: String?,
  /** 已看（详情页次操作圆钮的选中态） */
  val isPlayed: Boolean,
)

/** 角标：[ghost] = 压图半透明白（弱），否则走 `tertiaryContainer`（强） */
@Immutable
data class CineBadgeUi(
  val text: String,
  val ghost: Boolean = false,
)

/** 海报图解码宽度：132dp@3x ≈ 396px，留一点余量 */
private const val POSTER_DECODE_WIDTH = 420

/** 继续观看（16:9 缩略）解码宽度：212dp@3x ≈ 636px */
private const val THUMB_DECODE_WIDTH = 720

/** Hero 解码宽度：满宽 393dp@3x ≈ 1179px */
private const val HERO_DECODE_WIDTH = 1280

/** 标题 Logo（ClearLogo，透明 PNG）解码宽度：显示上限 220dp@3x ≈ 660px */
private const val LOGO_DECODE_WIDTH = 660

/**
 * 把一条 Emby 条目映射成 Cine 各处的展示模型。
 *
 * `imageUrl` / `displayTitle` 都走 ViewModel（它们内含"相册走 PrimaryImageItemId"
 * 之类的既有规则），这里不重复实现 URL 拼装。
 */
internal fun EmbyViewModel.toCineMedia(server: EmbyServer, item: EmbyItem): CineMediaUi {
  val name = item.Name?.trim().takeUnless { it.isNullOrBlank() } ?: "未命名"
  val isEpisode = item.Type.equals("Episode", ignoreCase = true)
  val typeLabel = item.typeLabel()
  val seriesName = item.SeriesName?.trim().takeUnless { it.isNullOrBlank() }

  val year = item.ProductionYear?.toString()
  val genres = item.Genres.take(2).joinToString(" / ").takeUnless { it.isBlank() }
  // 时长写成设计稿的紧凑形式（`2h 12m` / `47m`）：Hero 元信息行要塞下
  // 「年份 · 类型 · 时长 · 评分」四项，写成「2小时12分」会把它挤成两行
  val duration = runtimeText(item.RunTimeTicks)
  val rating = item.CommunityRating?.let { "%.1f".format(it) }

  // 图下一行：年份 → 类型（最多两个），都没有才退回类型名。
  // 用「 · 」而不是「 ｜ 」：设计稿里这一行是弱信息，分隔符要轻。
  val meta = listOfNotNull(year, genres).joinToString(" · ").ifBlank { typeLabel }

  // 继续观看图下那一行（设计稿：`第 4 集 · 雨中的证词`）。
  // ⚠️ 只写集号**不写季号**：卡图左上角的角标已经是 `S1 · E4`，
  //    再写一遍「第 1 季第 4 集」纯属重复，还会把单集名挤到省略号。
  // ⚠️ 单集名与剧名相同时不重复写一遍（很多剧的单集名就叫「第 1 集」）。
  val episodeShort = item.episodeShortLabel()
  val wideMeta = when {
    isEpisode && episodeShort != null -> {
      if (name != seriesName && seriesName != null) "$episodeShort · $name" else episodeShort
    }
    duration != null -> duration
    else -> genres ?: typeLabel
  }

  val facts = item.mediaFacts()
  val resolution = facts.resolutionLabel()
  val hdr = facts.hdrLabel
  val audio = facts.audioLabel

  // 剧集角标：Series 的 `ChildCount` = 季数（Emby 不给总集数，所以不编「已更 N 集」）
  val seasonLabel = if (item.Type.equals("Series", ignoreCase = true)) {
    (item.ChildCount ?: 0).takeIf { it > 0 }?.let { "$it 季" }
  } else {
    null
  }

  // 海报角标只留一个。优先级 = 画质 > 季数 > 评分：
  // 用户扫一眼卡片时，「这张是 4K / HDR」比评分更能决定要不要点。
  val badge = when {
    hdr != null -> CineBadgeUi(hdr, ghost = false)
    resolution != null -> CineBadgeUi(resolution, ghost = false)
    seasonLabel != null -> CineBadgeUi(seasonLabel, ghost = true)
    rating != null -> CineBadgeUi(rating, ghost = true)
    else -> null
  }

  val heroBadges = buildList {
    val strong = listOfNotNull(resolution, hdr).joinToString(" ")
    if (strong.isNotBlank()) add(CineBadgeUi(strong, ghost = false))
    // 第二枚优先音频格式（设计稿是「杜比全景声」的位置）：音轨信息是 Emby 用户选片的真实依据，
    // 分级标签更多是合规信息。没有音轨标签时退回分级，再没有就空着。
    val second = audio ?: item.OfficialRating?.trim()?.takeUnless { it.isBlank() }
    if (second != null) add(CineBadgeUi(second, ghost = true))
    val third = when {
      // 单集：用紧凑写法 `S2 · E6`，比「第 2 季第 6 集」短一半，不会把角标行撑满
      item.Type.equals("Episode", ignoreCase = true) -> {
        val s = item.ParentIndexNumber
        val e = item.IndexNumber
        when {
          s != null && e != null -> "S$s · E$e"
          e != null -> "E$e"
          else -> null
        }
      }
      seasonLabel != null -> seasonLabel
      else -> null
    }
    if (third != null && size < 3) add(CineBadgeUi(third, ghost = true))
  }

  val heroMetaParts = listOfNotNull(year, genres, duration, rating)

  // 标题艺术字：必须先看 ImageTags 里到底有没有 Logo 再拼 URL ——
  // imageUrl 对非 Primary 类型不检查 tag 是否存在，没图也会给出一条 404 的地址
  val logoUrl = if (item.ImageTags["Logo"] != null) {
    imageUrl(server, item, "Logo", LOGO_DECODE_WIDTH)
  } else {
    null
  }

  return CineMediaUi(
    id = item.Id.orEmpty(),
    item = item,
    title = name,
    wideTitle = if (isEpisode) seriesName ?: name else name,
    meta = meta,
    wideMeta = wideMeta,
    badge = badge,
    episodeTag = item.episodeTag(typeLabel),
    progress = playbackProgress(item),
    remaining = remainingSeconds(item)?.let(::formatRemaining),
    posterUrl = imageUrl(server, item, "Primary", POSTER_DECODE_WIDTH),
    thumbUrl = imageUrl(server, item, "Backdrop", THUMB_DECODE_WIDTH),
    heroUrl = imageUrl(server, item, "Backdrop", HERO_DECODE_WIDTH),
    isFavorite = item.UserData?.IsFavorite == true,
    resumeSeconds = EmbyTicks.ticksToSeconds(item.UserData?.PlaybackPositionTicks ?: 0L),
    typeLabel = typeLabel,
    heroBadges = heroBadges,
    heroMetaParts = heroMetaParts,
    hasProgress = (item.UserData?.PlaybackPositionTicks ?: 0L) > 0L,
    logoUrl = logoUrl,
    techSpecs = item.techSpecs(),
    genresLine = item.Genres
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .take(3)
      .joinToString(" · ")
      .takeUnless { it.isBlank() },
    isPlayed = item.UserData?.Played == true,
  )
}

/** `剩 38 分钟` / `剩 1 小时 2 分`（设计稿的措辞比老卡片「剩余 12分30秒」更克制） */
private fun formatRemaining(seconds: Long): String = if (seconds >= 3600) {
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  if (m > 0) "剩 $h 小时 $m 分" else "剩 $h 小时"
} else {
  "剩 ${(seconds / 60).coerceAtLeast(1)} 分钟"
}

/** `S1 · E4`；非单集退回类型名（`影片` / `剧集`） */
private fun EmbyItem.episodeTag(typeLabel: String): String {
  val season = ParentIndexNumber
  val episode = IndexNumber
  return if (Type.equals("Episode", ignoreCase = true) && season != null && episode != null) {
    "S$season · E$episode"
  } else if (Type.equals("Movie", ignoreCase = true)) {
    "影片"
  } else {
    typeLabel
  }
}

/** `第 4 集`；没有集号时返回 null（不编「第 1 集」） */
private fun EmbyItem.episodeShortLabel(): String? {
  if (!Type.equals("Episode", ignoreCase = true)) return null
  return IndexNumber?.let { "第 $it 集" }
}

/**
 * 运行时长的紧凑写法：`2h 12m` / `47m` / `2h`。取不到（或为 0）返回 null。
 *
 * 用 h/m 缩写而不是「2小时12分」：设计稿的元信息行里有四个字段，
 * 中文写法在 353dp 宽的 Hero 上会把最后一项评分顶出可视区。
 */
private fun runtimeText(ticks: Long?): String? {
  val totalMinutes = EmbyTicks.ticksToSeconds(ticks ?: 0L) / 60
  if (totalMinutes <= 0) return null
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return when {
    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
    hours > 0 -> "${hours}h"
    else -> "${minutes}m"
  }
}

private fun EmbyItem.typeLabel(): String = when (Type?.lowercase()) {
  "movie" -> "电影"
  "series" -> "剧集"
  "episode" -> "单集"
  "boxset" -> "合集"
  "person" -> "演员"
  "musicalbum" -> "专辑"
  "musicartist" -> "音乐人"
  "audio" -> "音乐"
  "video" -> "视频"
  "photo" -> "照片"
  "folder", "collectionfolder" -> "文件夹"
  else -> Type?.takeIf { it.isNotBlank() } ?: "媒体"
}

/** 音视频流里能拿到的客观事实（拿不到就是 null，不做推测） */
private data class MediaFacts(
  val height: Int?,
  val fps: Double?,
  val sizeBytes: Long?,
  val hasVideoStream: Boolean,
  val hdrLabel: String?,
  val audioLabel: String?,
) {
  fun resolutionLabel(): String? {
    val h = height ?: return null
    return when {
      h >= 2000 -> "4K"
      h >= 1000 -> "1080P"
      h >= 700 -> "720P"
      else -> null
    }
  }

  /**
   * 色域标签：**有视频流但非 HDR 时明确写 `SDR`**。
   *
   * 与 [hdrLabel] 的区别：后者回答「是不是 HDR」，不是就 null；
   * 详情页那一行是「规格陈述」，前面写了 4K、后面写了 25FPS，中间唯独缺色域
   * 会让人以为字段没取到。所以这里把「非 HDR」显式表达出来。
   */
  fun rangeLabel(): String? = hdrLabel ?: if (hasVideoStream) "SDR" else null

  fun fpsLabel(): String? {
    val value = fps ?: return null
    val rounded = value.toInt()
    // 0 / 缺失会变成 "0FPS"；大于 240 的是异常值（Emby 偶有 1000fps 的损坏元数据）
    return if (rounded in 1..240) "${rounded}FPS" else null
  }

  fun sizeLabel(): String? {
    val bytes = sizeBytes ?: return null
    if (bytes <= 0L) return null
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1fG".format(gb) else "${(bytes / 1024.0 / 1024.0).toLong()}M"
  }
}

private fun EmbyItem.mediaFacts(): MediaFacts {
  val sources = MediaSources.orEmpty()
  val streams = sources.asSequence()
    .flatMap { source -> source.MediaStreams?.asSequence() ?: emptySequence() }
    .toList()
  // 文件大小挂在 MediaSource 上，即使 MediaStreams 被服务端裁掉也还在
  val size = sources.firstOrNull()?.Size
  if (streams.isEmpty()) {
    return MediaFacts(null, null, size, false, null, null)
  }
  val video = streams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
  val audio = streams.firstOrNull { it.Type.equals("Audio", ignoreCase = true) }
  return MediaFacts(
    height = video?.Height,
    fps = video?.FrameRate,
    sizeBytes = size,
    hasVideoStream = video != null,
    hdrLabel = video?.hdrLabel(),
    audioLabel = audio.audioLabel(),
  )
}

/**
 * 详情页信息行（结构参考：`12 分钟  2026年9月12日  4K  SDR  25FPS  679M`）。
 *
 * 顺序固定「时长 → 日期 → 画质 → 色域 → 帧率 → 大小」：用户扫这一行时先关心
 * 「多长、什么时候的」，再看技术规格。任一项拿不到就跳过，不留空位。
 *
 * Series 没有 `MediaSources`（Emby 只给单集和影片），所以剧集退化成「N 季 · 年份」——
 * 硬凑画质会显示成「4K」，而实际每一集的画质都可能不同，那是假信息。
 */
private fun EmbyItem.techSpecs(): List<String> {
  val facts = mediaFacts()
  val isSeries = Type.equals("Series", ignoreCase = true)
  return buildList {
    if (isSeries) {
      (ChildCount ?: 0).takeIf { it > 0 }?.let { add("$it 季") }
    } else {
      runtimeHuman(RunTimeTicks)?.let { add(it) }
    }
    premiereDateLabel()?.let { add(it) }
    if (!isSeries) {
      facts.resolutionLabel()?.let { add(it) }
      facts.rangeLabel()?.let { add(it) }
      facts.fpsLabel()?.let { add(it) }
    }
    facts.sizeLabel()?.let { add(it) }
  }
}

/** `12 分钟` / `1 小时 47 分`（详情页用完整中文；Hero 塞得紧，那边用 `1h 47m`） */
private fun runtimeHuman(ticks: Long?): String? {
  val totalMinutes = EmbyTicks.ticksToSeconds(ticks ?: 0L) / 60
  if (totalMinutes <= 0) return null
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return when {
    hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
    hours > 0 -> "$hours 小时"
    else -> "$minutes 分钟"
  }
}

/**
 * `2026年9月12日`。
 *
 * `PremiereDate` 是 `2026-09-12T00:00:00.0000000Z` 这样的 ISO 串，这里只取前 10 位自己拆。
 * 不走 `SimpleDateFormat` 有两个原因：映射层是高频纯函数，多一个 formatter 实例不值得；
 * 带时区解析还会把「9 月 12 日首播」在东八区显示成 9 月 11 日。
 */
private fun EmbyItem.premiereDateLabel(): String? {
  val raw = PremiereDate?.take(10) ?: return null
  val parts = raw.split('-')
  if (parts.size != 3) return null
  val year = parts[0].toIntOrNull() ?: return null
  val month = parts[1].toIntOrNull() ?: return null
  val day = parts[2].toIntOrNull() ?: return null
  if (month !in 1..12 || day !in 1..31) return null
  return "${year}年${month}月${day}日"
}

private fun EmbyMediaStream.hdrLabel(): String? {
  val rangeType = VideoRangeType?.uppercase()
  return when (rangeType) {
    "DOLBYVISION" -> "DV"
    "HDR10", "HDR10PLUS" -> "HDR"
    "HLG" -> "HLG"
    else -> if (VideoRange.equals("HDR", ignoreCase = true)) "HDR" else null
  }
}

/**
 * 音频格式角标（`ATMOS` / `DTS:X` / `TrueHD`）。
 *
 * 用技术缩写而不是「杜比全景声」这类译名：这是**语言无关**的行业写法，
 * 映射层跑在非 composable 上下文里，取不到 string 资源；写死中文会在英文界面里出戏。
 * 三个字段都要看 —— Emby 有时把 Atmos 放在 `Profile`、有时只在 `DisplayTitle` 里。
 */
private fun EmbyMediaStream?.audioLabel(): String? {
  if (this == null) return null
  val haystack = listOfNotNull(DisplayTitle, Profile, Codec).joinToString(" ")
  if (haystack.isBlank()) return null
  return when {
    haystack.contains("atmos", ignoreCase = true) -> "ATMOS"
    haystack.contains("dts:x", ignoreCase = true) ||
      haystack.contains("dts-x", ignoreCase = true) -> "DTS:X"
    haystack.contains("dts-hd", ignoreCase = true) -> "DTS-HD"
    haystack.contains("truehd", ignoreCase = true) -> "TrueHD"
    else -> null
  }
}
