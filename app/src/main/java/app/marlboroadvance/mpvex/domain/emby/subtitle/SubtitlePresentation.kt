package app.marlboroadvance.mpvex.domain.emby.subtitle

/**
 * 详情页字幕展示的**文案口径**（纯函数、不碰 Android）。
 *
 * 与播放器的 `SubtitleInfo.describe()` 是两种用途：
 * · [SubtitleInfo.describe] 是**日志 / 一句话摘要**（`简体中文 · ASS · 外挂 · 默认`）；
 * · 这里拆成「主标题 / 副标题 / 格式块 / 徽标 / 是否可用」五段，供卡片与抽屉分行排版。
 *
 * 为什么单独一层：这些判断（哪一段是可读语言名、哪一段是服务端原文、哪种来源该标「外挂」）
 * 一散进 Composable 就会跟布局缠在一起，改文案要动 UI、也没法在 JVM 上单测。
 */
data class SubtitleRowModel(
  /** 稳定键（媒体源 + 流索引），用于列表 key 与「将使用」比对 */
  val key: String,
  /** Emby 的流索引。排查问题时对得上服务端日志，日常只是右侧一个弱化的小号数字 */
  val streamIndex: Int,
  /** 主标题：归一化后的语言名（简体中文 / 繁体中文 / 英语…） */
  val title: String,
  /** 副标题：来源 + 服务端原文（为空则不显示） */
  val detail: String?,
  /** 左侧格式块（ASS / SRT / PGS）；拿不到格式时给 SUB */
  val formatLabel: String,
  /** 徽标：默认 / 强制 / 听障 */
  val tags: List<String>,
  /** 是否能交给播放器（内嵌恒为 true，外挂看格式） */
  val available: Boolean,
  /** 不可用原因（可用时为 null）；直接取 [SubtitleInfo.unsupportedReason] */
  val unavailableReason: String?,
)

object SubtitlePresentation {
  /** 卡片上最多铺几个语言 chip，超出折成「+N」 */
  const val MAX_LANGUAGE_CHIPS = 4

  /**
   * 主标题。
   *
   * ⚠️ **不能**直接用 `displayName`：那是 `DisplayTitle` 原文，中文服务器给「简体中文」，
   * 英文服务器给「Chinese (Simplified)」—— 服务器换个语言设置，同一份字幕就换个说法。
   * 语言名一律走 [EmbySubtitleLanguage.label] 的归一化口径。
   */
  fun titleOf(subtitle: SubtitleInfo): String {
    val label = EmbySubtitleLanguage.label(subtitle.languageKey)
    return label.takeIf { it.isNotBlank() } ?: "未指定语言"
  }

  /** 左侧格式块。`format` 已归一化为小写无点（srt / ass / sup），这里只放大写。 */
  fun formatLabelOf(subtitle: SubtitleInfo): String =
    subtitle.format?.takeIf { it.isNotBlank() }?.uppercase()
      ?: subtitle.codec?.takeIf { it.isNotBlank() }?.uppercase()?.take(4)
      ?: "SUB"

  /** 来源标签。与 `SubtitleSourceType` 一一对应，别在 UI 里另写 when。 */
  fun sourceLabelOf(subtitle: SubtitleInfo): String = when (subtitle.sourceType) {
    SubtitleSourceType.EMBEDDED -> "内嵌"
    SubtitleSourceType.EXTERNAL -> "外挂"
    SubtitleSourceType.SERVER_DELIVERED -> "服务端交付"
    SubtitleSourceType.IMAGE_BASED -> "图像"
    SubtitleSourceType.UNKNOWN -> "未知来源"
  }

  /** 副标题：`外挂 · 中英双语`。服务端原文与主标题重复时只留来源。 */
  fun detailOf(subtitle: SubtitleInfo): String? {
    val title = titleOf(subtitle)
    val original = subtitle.displayName.takeIf { it.isNotBlank() && it != title }
    return listOfNotNull(sourceLabelOf(subtitle), original).joinToString(" · ").takeIf { it.isNotBlank() }
  }

  /** 徽标。顺序固定：默认 → 强制 → 听障（与播放器面板的阅读顺序一致）。 */
  fun tagsOf(subtitle: SubtitleInfo): List<String> = buildList {
    if (subtitle.isDefault) add("默认")
    if (subtitle.isForced) add("强制")
    if (subtitle.isHearingImpaired) add("听障")
  }

  fun rowOf(subtitle: SubtitleInfo): SubtitleRowModel = SubtitleRowModel(
    key = subtitle.key,
    streamIndex = subtitle.streamIndex,
    title = titleOf(subtitle),
    detail = detailOf(subtitle),
    formatLabel = formatLabelOf(subtitle),
    tags = tagsOf(subtitle),
    available = subtitle.unsupportedReason == null,
    unavailableReason = subtitle.unsupportedReason,
  )

  /**
   * 列表顺序：**「将使用」那条置顶**，其余保持服务端返回顺序。
   *
   * 不按语言偏好重排：顺序一变，用户下次进来看同一部片，每条的位置就全变了。
   * `sortedByDescending` 是稳定排序，同组内相对顺序不变。
   */
  fun rowsOf(subtitles: List<SubtitleInfo>, pickedKey: String?): List<SubtitleRowModel> {
    val rows = subtitles.map(::rowOf)
    if (pickedKey == null) return rows
    return rows.sortedByDescending { it.key == pickedKey }
  }

  /**
   * 语言概览：去重后最多 [MAX_LANGUAGE_CHIPS] 个。
   *
   * @return first = 显示的标签，second = 被折叠掉的数量（0 表示没折叠）
   */
  fun languageChips(subtitles: List<SubtitleInfo>): Pair<List<String>, Int> {
    val distinct = subtitles.map(::titleOf).distinct()
    return distinct.take(MAX_LANGUAGE_CHIPS) to (distinct.size - MAX_LANGUAGE_CHIPS).coerceAtLeast(0)
  }

  /** 全部条数都为 false 时，卡片要换一句话说「有字幕但都取不出来」。 */
  fun allUnavailable(subtitles: List<SubtitleInfo>): Boolean =
    subtitles.isNotEmpty() && subtitles.all { it.unsupportedReason != null }
}

/**
 * 「这部片用哪条字幕」的本地记忆（偏好 `sub_emby_choices` 的解析）。
 *
 * 存储格式是**多行 `itemId\t字幕key`**。播放器（`EmbySubtitleController`）与详情页
 * 必须按同一套解析，否则会出现「详情页说会用简中、播放页却用了别的」。
 */
object EmbySubtitleChoices {
  private const val SEPARATOR = '\t'

  fun keyOf(raw: String, itemId: String): String? {
    if (raw.isBlank() || itemId.isBlank()) return null
    return raw.lineSequence()
      .mapNotNull { line ->
        val tab = line.indexOf(SEPARATOR)
        if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
      }
      .firstOrNull { it.first == itemId }
      ?.second
      ?.takeIf { it.isNotBlank() }
  }

  /** 写回一行：先删掉该条目的旧记录，再按需追加新记录。空 = 取消记忆。 */
  fun withChoice(raw: String, itemId: String, subtitleKey: String?): String {
    val kept = raw.lineSequence()
      .filter { it.isNotBlank() }
      .filterNot { it.substringBefore(SEPARATOR) == itemId }
      .toMutableList()
    if (subtitleKey != null) kept.add("$itemId$SEPARATOR$subtitleKey")
    return kept.joinToString("\n")
  }
}
