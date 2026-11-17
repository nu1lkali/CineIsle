package app.marlboroadvance.mpvex.domain.smartmerge

/**
 * 版本特征识别。
 *
 * 只回答「这是哪一版」，**不参与作品身份判定**。番号/标题/ProviderIds 才是身份；
 * 分辨率、编码、字幕只用来区分同作品下的不同版本，以及生成展示标签。
 */
object SmartMergeVersionDetector {
  private val LTX = Regex("(?i)(?:^|[^a-z0-9])LTX(?:[^a-z0-9]|$)")
  private val SPECIAL = Regex("(?i)(?:特别版|特別版|special|uncut|extended|director.?s.?cut)")
  private val REPAIRED = Regex("(?i)(?:修复版|修復版|repair|fixed|v2|rev2)")
  private val CHINESE = Regex("(?i)(?:中文字幕|中字|简中|繁中|简体|繁体|繁體|chs|cht|chinese|zh)")
  private val EMBEDDED = Regex("(?i)(?:内嵌|內嵌|内封|內封|embedded|mux)")
  private val EXTERNAL = Regex("(?i)(?:外挂|外掛|external|srt|ass|ssa)")
  private val MULTI = Regex("(?i)(?:双语|雙語|multi|multi.?sub)")

  private val REMUX = Regex("(?i)(?:remux)")
  private val BLURAY = Regex("(?i)(?:bluray|blu-ray|bdrip|bdremux)")
  private val WEBDL = Regex("(?i)(?:web-?dl|webrip|web)")
  private val HDTV = Regex("(?i)(?:hdtv)")
  private val _4K = Regex("(?i)(?:4k|2160p|uhd)")
  private val _1080 = Regex("(?i)(?:1080p|1080i|fhd)")
  private val _720 = Regex("(?i)(?:720p)")

  fun detect(
    name: String?,
    path: String?,
    videoHeight: Int?,
    videoCodec: String?,
    subtitleStreams: List<Pair<String?, Boolean?>> = emptyList(),
  ): SmartMergeVersionProfile {
    val text = listOfNotNull(name, path).joinToString(" ")
    val type =
      when {
        LTX.containsMatchIn(text) -> TYPE_LTX
        SPECIAL.containsMatchIn(text) -> TYPE_SPECIAL
        REPAIRED.containsMatchIn(text) -> TYPE_REPAIRED
        text.isNotBlank() -> TYPE_NORMAL
        else -> TYPE_UNKNOWN
      }

    val subtitles =
      when {
        MULTI.containsMatchIn(text) -> SUBTITLES_MULTI
        EMBEDDED.containsMatchIn(text) -> SUBTITLES_EMBEDDED
        EXTERNAL.containsMatchIn(text) -> SUBTITLES_EXTERNAL
        CHINESE.containsMatchIn(text) -> SUBTITLES_CHINESE
        subtitleStreams.any {
          it.first?.lowercase()?.let { lang ->
            lang in setOf("chi", "zho", "zh", "chs", "cht", "cn")
          } == true
        } -> SUBTITLES_CHINESE
        subtitleStreams.any { it.second == true } -> SUBTITLES_EXTERNAL
        subtitleStreams.isNotEmpty() -> SUBTITLES_EMBEDDED
        else -> SUBTITLES_NONE
      }

    val quality =
      when {
        (videoHeight ?: 0) >= 2000 || _4K.containsMatchIn(text) -> QUALITY_4K
        (videoHeight ?: 0) >= 1000 || _1080.containsMatchIn(text) -> QUALITY_1080P
        (videoHeight ?: 0) >= 700 || _720.containsMatchIn(text) -> QUALITY_720P
        videoHeight != null && videoHeight > 0 -> QUALITY_SD
        else -> QUALITY_UNKNOWN
      }

    val codec =
      when (videoCodec?.lowercase()) {
        "hevc", "h265" -> CODEC_HEVC
        "h264", "avc" -> CODEC_H264
        "av1" -> CODEC_AV1
        else -> CODEC_UNKNOWN
      }

    val source =
      when {
        REMUX.containsMatchIn(text) -> "REMUX"
        BLURAY.containsMatchIn(text) -> "BLURAY"
        WEBDL.containsMatchIn(text) -> "WEB-DL"
        HDTV.containsMatchIn(text) -> "HDTV"
        else -> SOURCE_UNKNOWN
      }

    val parts = buildList {
      if (type != TYPE_NORMAL && type != TYPE_UNKNOWN) add(type)
      if (quality != QUALITY_UNKNOWN) add(quality)
      if (codec != CODEC_UNKNOWN) add(codec)
      if (source != SOURCE_UNKNOWN) add(source)
      when (subtitles) {
        SUBTITLES_CHINESE -> add("中字")
        SUBTITLES_EMBEDDED -> add("内嵌字幕")
        SUBTITLES_EXTERNAL -> add("外挂字幕")
        SUBTITLES_MULTI -> add("多语字幕")
      }
    }
    return SmartMergeVersionProfile(
      label = parts.ifEmpty { listOf("未知版本") }.joinToString(" · "),
      type = type,
      subtitles = subtitles,
      quality = quality,
      codec = codec,
      source = source,
      flags = parts,
    )
  }
}
