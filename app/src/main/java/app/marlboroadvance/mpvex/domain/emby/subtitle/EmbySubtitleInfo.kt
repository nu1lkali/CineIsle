package app.marlboroadvance.mpvex.domain.emby.subtitle

/**
 * 一条字幕**从哪来**。
 *
 * 这个分类决定了「怎么把它挂到播放内核上」，不是给用户看的标签：
 * · [EMBEDDED] 容器内嵌 —— mpv 打开文件时自己就看到了，**不需要下载**，只需要选中；
 * · [EXTERNAL] 服务端旁挂的独立文件 —— 必须走 Emby 字幕接口取字节再 `sub-add`；
 * · [SERVER_DELIVERED] 服务端给了完整交付地址（`DeliveryUrl`）—— 按它给的地址取，
 *   不要自己拼 URL；
 * · [IMAGE_BASED] 图像字幕（PGS / DVD / VobSub）—— 内嵌时由 mpv 原生渲染，
 *   外挂时**不能当文本字幕下载**（`Stream.srt` 拿不到，服务端不 OCR）。
 * · [UNKNOWN] 拿不到足够信息（字段缺失 / 交付方式为烧录）。
 */
enum class SubtitleSourceType {
  EXTERNAL,
  EMBEDDED,
  SERVER_DELIVERED,
  IMAGE_BASED,
  UNKNOWN,
}

/**
 * 从 `MediaStreams[]` 摘出来的、**与序列化无关**的字幕字段集合。
 *
 * 单独抽一层的唯一目的是：把「Emby DTO → 字幕模型」的映射与判断逻辑做成纯数据进出，
 * 这样不用起 Gradle（本机会卡死，见 MEMORY.md）也能在 JVM 上直接跑单测。
 */
data class SubtitleStreamFacts(
  val index: Int? = null,
  val codec: String? = null,
  val language: String? = null,
  val displayTitle: String? = null,
  val displayLanguage: String? = null,
  val isExternal: Boolean? = null,
  val isTextSubtitle: Boolean? = null,
  val isDefault: Boolean? = null,
  val isForced: Boolean? = null,
  val isHearingImpaired: Boolean? = null,
  val deliveryMethod: String? = null,
  val deliveryUrl: String? = null,
  val deliveryFormat: String? = null,
  val isExternalUrl: Boolean? = null,
  val isChunkedResponse: Boolean? = null,
  val path: String? = null,
)

/**
 * 统一的字幕描述。UI、选择算法、下载器都只认这一种结构。
 *
 * 所有字段都当「可能缺失」处理 —— Emby 的字段齐全度随版本、随媒体类型变化很大，
 * **任何一个字段为空都不允许让播放流程崩掉**（需求 3.2）。
 */
data class SubtitleInfo(
  /** 这条字幕所属的媒体源。字幕索引只在**同一媒体源内**有意义 */
  val mediaSourceId: String?,
  /** Emby 的媒体流索引。⚠️ 不是 `MediaStreams` 数组下标 */
  val streamIndex: Int,
  /** 服务端原始语言码（`chi` / `eng` / `zh-CN` …），原样保留便于排查 */
  val language: String?,
  /** 归一化后的语言键（`zh-hans` / `en` …），见 [EmbySubtitleLanguage] */
  val languageKey: String,
  /** 展示名：优先 `DisplayTitle`，其次语言名 + 序号 */
  val displayName: String,
  val codec: String?,
  /** 实际交付 / 落盘的格式（小写，无点） */
  val format: String?,
  val isExternal: Boolean,
  val isTextSubtitle: Boolean,
  val isDefault: Boolean,
  val isForced: Boolean,
  val isHearingImpaired: Boolean,
  val deliveryMethod: String?,
  val deliveryUrl: String?,
  val isExternalUrl: Boolean,
  val isChunkedResponse: Boolean,
  val sourceType: SubtitleSourceType,
  /** 服务端文件路径。**只用于显示与诊断，绝不能当客户端路径去读** */
  val path: String? = null,
) {
  /** 同一条字幕的稳定标识（媒体源 + 流索引），用于去重 / 记忆 */
  val key: String get() = "${mediaSourceId.orEmpty()}#$streamIndex"

  /** 缓存键的一段：`ItemId` 由调用方拼在前面（见 EmbySubtitleCache） */
  val cacheDiscriminator: String get() = "${mediaSourceId.orEmpty()}|$streamIndex|${format.orEmpty()}"

  /**
   * 能不能**下载下来当外挂字幕挂载**。空列表 = 不能（此时只能靠内嵌轨道或服务端烧录）。
   *
   * 顺序即优先级，第一个是首选格式。ASS/SSA 首选 `ass` 是为了保住字体、颜色、
   * 定位与描边；拿不到才逐级回退，回退时会记一条「样式降级」日志。
   */
  val externalRequestFormats: List<String>
    get() = when (sourceType) {
      SubtitleSourceType.EMBEDDED, SubtitleSourceType.UNKNOWN -> emptyList()
      else -> EmbySubtitleFormats.requestCandidates(format ?: codec)
    }

  /** 能不能交给 mpv 当外挂字幕 */
  val canMountExternally: Boolean get() = externalRequestFormats.isNotEmpty()

  /**
   * 为什么不支持 —— 给用户与日志一句人话。支持时返回 null。
   *
   * ⚠️ 这里是「格式不支持」的唯一出口。**不能把不支持当成下载失败**（需求 3.4）：
   * 两者在 UI 上是不同的两条信息。
   */
  val unsupportedReason: String?
    get() = when {
      sourceType == SubtitleSourceType.EMBEDDED -> null
      deliveryMethod?.equals("Encode", ignoreCase = true) == true ->
        "服务端交付方式为烧录（Encode），无法取出独立字幕文件"
      sourceType == SubtitleSourceType.IMAGE_BASED -> when {
        format == "sup" -> null
        else -> "图像字幕（${(codec ?: format ?: "未知").uppercase()}）无法转成文本，服务端不做 OCR"
      }
      canMountExternally -> null
      else -> "播放器不支持该字幕格式（${(codec ?: format ?: "未知").uppercase()}）"
    }

  /** `简体中文 · ASS · 外挂 · 默认` —— 日志与 UI 共用的一句话描述 */
  fun describe(): String = buildString {
    append(EmbySubtitleLanguage.label(languageKey))
    format?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.uppercase()) }
    append(
      when (sourceType) {
        SubtitleSourceType.EMBEDDED -> " · 内嵌"
        SubtitleSourceType.EXTERNAL -> " · 外挂"
        SubtitleSourceType.SERVER_DELIVERED -> " · 服务端交付"
        SubtitleSourceType.IMAGE_BASED -> " · 图像"
        SubtitleSourceType.UNKNOWN -> " · 未知来源"
      },
    )
    if (isDefault) append(" · 默认")
    if (isForced) append(" · 强制")
    if (isHearingImpaired) append(" · 听障")
  }
}

/** 格式判定与请求候选 —— 纯函数，集中在一处，避免各处各猜一套。 */
object EmbySubtitleFormats {
  /** 图像字幕（没有文本可给） */
  private val IMAGE_CODECS = setOf(
    "hdmv_pgs_subtitle", "pgs", "pgssub", "pgs_subtitle", "sup",
    "dvd_subtitle", "dvdsub", "dvd_sub", "vobsub", "dvbsub",
    "dvb_subtitle", "xsub", "dvb_teletext", "teletext", "dvb-teletext",
  )

  /** mpv 能直接读的文本字幕扩展名（与 SubtitleFileTypes 的口径保持兼容） */
  private val TEXT_FORMATS = setOf(
    "srt", "ass", "ssa", "vtt", "ttml", "dfxp", "xml", "smi", "sami",
    "sbv", "mpl", "lrc", "txt", "sub", "json", "itt", "imsc", "usf", "rt",
  )

  fun isImageCodec(codec: String?): Boolean {
    val c = codec?.lowercase()?.replace('-', '_') ?: return false
    return c in IMAGE_CODECS
  }

  /** 把 codec / deliveryFormat / 扩展名统一成「小写、无点」的格式名 */
  fun normalizeFormat(raw: String?): String? {
    val t = raw?.trim()?.lowercase()?.removePrefix(".") ?: return null
    if (t.isBlank()) return null
    return when (t) {
      "subrip", "s_text/srt", "srt" -> "srt"
      "ssa" -> "ssa"
      "ass", "s_text/ass" -> "ass"
      "webvtt", "vtt", "s_text/webvtt" -> "vtt"
      "mov_text", "tx3g" -> "srt"
      "microdvd" -> "srt"
      "hdmv_pgs_subtitle", "pgs", "pgssub", "pgs_subtitle" -> "sup"
      "dvd_subtitle", "dvdsub", "dvd_sub", "vobsub", "vobsub_subtitle" -> "sub"
      else -> t
    }
  }

  fun isTextFormat(format: String?): Boolean {
    val f = normalizeFormat(format) ?: return false
    return f in TEXT_FORMATS
  }

  /**
   * 向 `Stream.{Format}` 请求时的格式候选（按优先级）。
   *
   * 只列 Emby 真能产出的目标格式：`srt` / `ass` / `ssa` / `vtt` / `sub` / `sup`。
   * · ASS/SSA：**先要原格式**保住样式，失败才退 srt；
   * · 文本类：先要 srt（最通用）；
   * · 图像类：只有 PGS 有机会（`.sup` 由 mpv 自己渲染），DVD/VobSub 需要
   *   `.idx`+`.sub` 成对文件，单个流拿不到，直接返回空（= 不支持）。
   */
  fun requestCandidates(rawFormat: String?): List<String> {
    val f = normalizeFormat(rawFormat)
    return when {
      f == null -> listOf("srt")
      f == "ass" -> listOf("ass", "srt")
      f == "ssa" -> listOf("ass", "ssa", "srt")
      f == "vtt" -> listOf("vtt", "srt")
      f == "sup" -> listOf("sup")
      f == "sub" -> emptyList()
      f in TEXT_FORMATS -> listOf("srt")
      else -> listOf("srt")
    }
  }

  /** 落盘用的扩展名；取候选里的第一个（即最终拿到的格式）。 */
  fun fileExtension(requestFormat: String): String =
    normalizeFormat(requestFormat)?.takeIf { it in TEXT_FORMATS || it == "sup" } ?: "srt"
}

/** Emby 字幕流 → [SubtitleInfo]。纯函数，任何字段为空都不会抛。 */
object EmbySubtitleMapper {
  fun fromFacts(facts: SubtitleStreamFacts, mediaSourceId: String?): SubtitleInfo? {
    val index = facts.index ?: return null
    val format = EmbySubtitleFormats.normalizeFormat(
      facts.deliveryFormat ?: facts.codec ?: facts.path?.substringAfterLast('.', ""),
    )
    val image = EmbySubtitleFormats.isImageCodec(facts.codec) ||
      (facts.isTextSubtitle == false && EmbySubtitleFormats.isImageCodec(format))
    val isText = !image && (facts.isTextSubtitle != false || EmbySubtitleFormats.isTextFormat(format))

    val externalUrl = facts.deliveryUrl?.takeIf { it.isNotBlank() }
    val sourceType = when {
      image -> SubtitleSourceType.IMAGE_BASED
      externalUrl != null && facts.isExternalUrl == true -> SubtitleSourceType.SERVER_DELIVERED
      facts.isExternal == true -> SubtitleSourceType.EXTERNAL
      facts.deliveryMethod?.equals("Encode", ignoreCase = true) == true -> SubtitleSourceType.UNKNOWN
      else -> SubtitleSourceType.EMBEDDED
    }

    val languageKey = EmbySubtitleLanguage.resolve(
      facts.language,
      facts.displayLanguage,
      facts.displayTitle,
      // 旁挂文件名里常写「xxx.chs.ass」/「xxx.繁体.srt」，是简繁的可靠线索
      facts.path,
    )

    val name = facts.displayTitle?.takeIf { it.isNotBlank() }
      ?: buildString {
        append(EmbySubtitleLanguage.label(languageKey))
        format?.let { append(" · ").append(it.uppercase()) }
      }

    return SubtitleInfo(
      mediaSourceId = mediaSourceId,
      streamIndex = index,
      language = facts.language,
      languageKey = languageKey,
      displayName = name,
      codec = facts.codec,
      format = format,
      isExternal = facts.isExternal == true,
      isTextSubtitle = isText,
      isDefault = facts.isDefault == true,
      isForced = facts.isForced == true,
      isHearingImpaired = facts.isHearingImpaired == true,
      deliveryMethod = facts.deliveryMethod,
      deliveryUrl = externalUrl,
      isExternalUrl = facts.isExternalUrl == true,
      isChunkedResponse = facts.isChunkedResponse == true,
      sourceType = sourceType,
      path = facts.path,
    )
  }
}
