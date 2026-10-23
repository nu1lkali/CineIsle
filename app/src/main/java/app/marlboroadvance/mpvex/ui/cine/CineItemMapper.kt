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
}

private fun EmbyItem.mediaFacts(): MediaFacts {
  val streams = MediaSources?.asSequence()
    ?.flatMap { source -> source.MediaStreams?.asSequence() ?: emptySequence() }
    ?.toList()
    ?: return MediaFacts(null, null, null)
  val video = streams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
  val audio = streams.firstOrNull { it.Type.equals("Audio", ignoreCase = true) }
  return MediaFacts(
    height = video?.Height,
    hdrLabel = video?.hdrLabel(),
    audioLabel = audio.audioLabel(),
  )
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
