package app.marlboroadvance.mpvex.domain.smartmerge

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.CodeKey
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.FilenameNormalizer
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.IdentifierExtractor
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.NoiseCleaner

/** 提取结果：保留原始片段与可比较键。 */
data class SmartMergeCodeIdentity(
  val raw: String,
  val letters: String,
  val numericRaw: String,
  val body: String,
  val suffix: String,
  val confidence: Double,
) {
  val display: String
    get() = when {
      letters.isBlank() -> numericRaw
      suffix.isBlank() -> "$letters-$numericRaw"
      else -> "$letters-$numericRaw$suffix"
    }
}

/**
 * 番号提取/标准化的 Smart Merge 入口。
 *
 * 复用字幕智能匹配里已经过真机样本验证的 `IdentifierExtractor` / `IdentifierNormalizer`；
 * 这两个文件目前位于 `ui.player.subtitle.smart`。阶段 1 的后续工作是把它们上移到
 * `domain/matching`，本类只是过渡封装，保证 Smart Merge 与字幕使用同一套口径。
 */
object SmartMergeCode {
  fun extract(texts: List<String>): SmartMergeCodeIdentity? {
    var best: SmartMergeCodeIdentity? = null
    for (raw in texts) {
      if (raw.isBlank()) continue
      val normalized = runCatching { FilenameNormalizer.normalize(raw).normalized }.getOrNull()
        ?: continue
      val cleaned = runCatching { NoiseCleaner.clean(normalized) }.getOrDefault(normalized)
      val ids = runCatching { IdentifierExtractor.extract(cleaned) }.getOrDefault(emptyList())
      for (id in ids) {
        val key = CodeKey.of(id)
        if (key.body.isBlank()) continue
        val candidate = SmartMergeCodeIdentity(
          raw = id.raw,
          letters = key.letters,
          numericRaw = key.numericRaw,
          body = key.body,
          suffix = key.suffix,
          confidence = id.confidence,
        )
        if (best == null || candidate.confidence > best.confidence) best = candidate
      }
    }
    return best
  }

  fun identify(item: SmartMergeItem): SmartMergeCodeIdentity? {
    val texts = buildList {
      add(item.name)
      item.originalTitle?.let(::add)
      item.path?.let(::add)
      // ProviderIds 的值有时直接就是番号（JavBus / MetaTube 常见）
      addAll(item.providerIds.values)
    }
    return extract(texts)
  }

  /** 元数据来源的启发式判断；无法确认时返回未知，不强行指定。 */
  fun sourceOf(providerIds: Map<String, String>): String {
    val all = providerIds.entries.joinToString("|") { "${it.key}=${it.value}" }.lowercase()
    return when {
      "metatube" in all -> SOURCE_METATUBE
      "javboss" in all -> SOURCE_JAVBOSS
      "javbus" in all -> SOURCE_JAVBUS
      else -> SOURCE_UNKNOWN
    }
  }
}

/** EmbyItem → SmartMergeItem 的投影。 */
object SmartMergeMapper {
  fun from(item: EmbyItem): SmartMergeItem? {
    val id = item.Id ?: return null
    val embySources = item.MediaSources.orEmpty()
    val sources = embySources.map(::sourceOf)
    val primary = embySources.firstOrNull()
    val streams = primary?.MediaStreams ?: embySources.flatMap { it.MediaStreams.orEmpty() }
    val video = streams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
    val subtitleStreams = streams.filter { it.Type.equals("Subtitle", ignoreCase = true) }
    val providerIds = item.ProviderIds
    val base = SmartMergeItem(
      id = id,
      name = item.Name.orEmpty(),
      originalTitle = item.OriginalTitle,
      type = item.Type,
      path = item.Path,
      providerIds = providerIds,
      source = SmartMergeCode.sourceOf(providerIds),
      productionYear = item.ProductionYear,
      premiereDate = item.PremiereDate,
      dateCreated = item.DateCreated,
      runtimeTicks = item.RunTimeTicks,
      overview = item.Overview,
      genres = item.Genres,
      studios = item.Studios.orEmpty().mapNotNull { it.Name },
      people = item.People.orEmpty().mapNotNull { it.Name },
      mediaSources = sources,
      imageTag = item.ImageTags["Primary"],
      alreadyMultiVersion = item.MediaSources.orEmpty().size > 1,
    )
    val code = SmartMergeCode.identify(base)
    val version = SmartMergeVersionDetector.detect(
      name = item.Name,
      path = item.Path,
      videoHeight = video?.Height,
      videoCodec = video?.Codec,
      subtitleStreams = subtitleStreams.map { it.Language to it.IsExternal },
    )
    return base.copy(
      code = code?.display,
      codeBody = code?.body,
      codeConfidence = code?.confidence ?: 0.0,
      version = version,
    )
  }

  private fun sourceOf(source: EmbyMediaSource): SmartMergeMediaSource {
    val streams = source.MediaStreams.orEmpty()
    val video = streams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
    val audio = streams.filter { it.Type.equals("Audio", ignoreCase = true) }
    val subs = streams.filter { it.Type.equals("Subtitle", ignoreCase = true) }
    return SmartMergeMediaSource(
      id = source.Id,
      name = source.Name,
      path = source.Path,
      container = source.Container,
      size = source.Size,
      bitrate = source.Bitrate,
      runtimeTicks = source.RunTimeTicks,
      width = video?.Width,
      height = video?.Height,
      videoCodec = video?.Codec,
      audioCodecs = audio.mapNotNull { it.Codec }.distinct(),
      subtitleLanguages = subs.mapNotNull { it.Language }.distinct(),
    )
  }
}
