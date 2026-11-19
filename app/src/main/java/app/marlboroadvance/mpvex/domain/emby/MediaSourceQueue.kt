package app.marlboroadvance.mpvex.domain.emby

/**
 * 多版本媒体的**自动连播队列**：把同一部影片的其它版本排到当前播放项之后。
 *
 * ## 为什么需要它
 *
 * Emby 的 `MediaSources` 是「同一部影片的多个文件」（4K / 1080P / 导演剪辑版…）。
 * 详情页只播用户选中的那一个版本，看完就结束。这里把其余版本也放进播放队列，
 * 让「下一集」按钮 / 自动连播能按版本切换过去。
 *
 * ## 排序是「稳定」的
 *
 * 三条硬约束，都是为了不出现「第 1 集 → 第 8 集 → 第 2 集」这种乱序：
 *  1. **单调**：同名同号的两个版本经过一次排序后位置不再变化（比较器用原下标兜底）；
 *  2. **数值而非字典**：`第2版` 必须排在 `第10版` 前面（自然顺序按数字段比大小）；
 *  3. **缺失有兜底**：`Name` / `Path` 都拿不到时用 `MediaSourceId` 参与比较，
 *     绝不因为「认不出来」就把条目丢掉或排到随机位置。
 *
 * ## 「当前项在前」= 轮转，不是重排
 *
 * 用户点的可能就是中间那个版本（比如先看 1080P）。队列做法是把**排好序的环**从
 * 当前项处切开：`A B C D` + 当前 C ⇒ `C D A B`。这样「当前项之后的每一条」都严格
 * 按规则衔接；如果把当前项以外的按规则附在末尾（`C A B D`），下一条就会从 C 跳到 A，
 * 恰好就是用户抱怨的那种乱序。
 */
enum class VersionQueueOrder(
  /** 设置里持久化的值。**不要改**，改名等于让老用户的设置丢失。 */
  val key: String,
) {
  /** 自然顺序（默认）：名字里的数字按数值比较，`2` 排在 `10` 前面。 */
  NATURAL("natural"),

  /** 字母顺序：`A → B → C`，忽略大小写。 */
  ALPHABETICAL("alphabetical"),

  /** 文件名顺序：优先取 `MediaSources[].Path` 的文件名（不含扩展名），按自然顺序比较。 */
  FILE_NAME("file_name"),

  /** 自定义顺序：用户为这个条目手动编排的次序；未列入的版本按自然顺序补在后面。 */
  CUSTOM("custom"),
  ;

  companion object {
    fun fromKey(key: String?): VersionQueueOrder =
      entries.firstOrNull { it.key == key } ?: NATURAL
  }
}

/**
 * 参与排序的一个版本（纯数据，方便单测）。
 *
 * @param id `MediaSources[i].Id` —— Emby 保证条目内唯一，重复 Id 会被 [buildVersionQueue] 按原顺序消费
 * @param title 显示名（本机自定义名 > Emby `Name` > 自动拼出的短名）
 * @param filePath `MediaSources[i].Path`；可空（部分列表查询不带这个字段）
 */
data class VersionQueueEntry(
  val id: String,
  val title: String,
  val filePath: String? = null,
)

/**
 * 交给播放器队列的一项。
 *
 * 与 [EmbyItem] 的区别：同一部影片的多个版本共用同一个 [item]，只靠 [mediaSourceId]
 * 区分。以前「一项 = 一个 itemId」的假设在队列里会撞车（`Map<itemId, sourceId>` 覆盖、
 * 直链表按 itemId 命中所有版本），所以单独立一个类型把这些歧义挡在启动侧。
 */
data class EmbyPlaylistEntry(
  val item: EmbyItem,
  /** 这一项要播的版本；null = 该条目自己的默认版本 */
  val mediaSourceId: String? = null,
  /** 这一项的版本显示名（播放页的媒体信息 / 队列标题用） */
  val mediaSourceLabel: String? = null,
  /**
   * 队列里显示的标题；null = 用 [item] 的常规标题。
   *
   * 多版本队列必须覆盖它：否则队列里会出现 3 条一模一样的片名，用户分不清
   * 哪一条是 4K、哪一条是 1080P。播放页顶栏读的是 `filename`（不受它影响）。
   */
  val titleOverride: String? = null,
) {
  /** ItemId + 版本的联合键。直链表 / 预热去重都靠它，不能只用 ItemId。 */
  val playbackKey: String
    get() = "${item.Id.orEmpty()}#${mediaSourceId.orEmpty()}"
}

/**
 * 解析本机保存的自定义顺序（`id1,id2,id3`）。
 *
 * 容错：空白、重复、空段全部丢掉 —— 服务器换了版本后旧 Id 会失效，
 * 解析阶段不该抛异常，让排序那边自然把失效项忽略掉。
 */
fun parseCustomVersionOrder(raw: String?): List<String> {
  if (raw.isNullOrBlank()) return emptyList()
  return raw.split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
}

/** 把自定义顺序编码回偏好字符串。 */
fun encodeCustomVersionOrder(ids: List<String>): String =
  ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

/**
 * 生成「一份」版本条目：Id 为空（服务端没给，无法拼播放地址）的版本直接排除。
 */
fun versionQueueEntry(
  source: EmbyMediaSource,
  title: String,
): VersionQueueEntry? {
  val id = source.Id?.takeIf { it.isNotBlank() } ?: return null
  return VersionQueueEntry(
    id = id,
    title = title.trim().ifBlank { id },
    filePath = source.Path,
  )
}

/**
 * 按规则排序（不含轮转）。
 *
 * @param customOrder 仅 [VersionQueueOrder.CUSTOM] 使用；未列入的条目按自然顺序接在其后。
 */
fun sortVersionEntries(
  entries: List<VersionQueueEntry>,
  order: VersionQueueOrder,
  customOrder: List<String> = emptyList(),
): List<VersionQueueEntry> {
  if (entries.size <= 1) return entries

  val rank = if (order == VersionQueueOrder.CUSTOM) {
    customOrder.withIndex().associate { (index, id) -> id to index }
  } else {
    emptyMap()
  }

  val comparator: Comparator<VersionQueueEntry> =
    when (order) {
      VersionQueueOrder.NATURAL -> Comparator { a, b ->
        firstNonZero(
          compareNatural(a.title, b.title),
          compareNatural(fileKey(a), fileKey(b)),
        )
      }

      VersionQueueOrder.ALPHABETICAL -> Comparator { a, b ->
        firstNonZero(
          a.title.compareTo(b.title, ignoreCase = true),
          compareNatural(fileKey(a), fileKey(b)),
          a.title.compareTo(b.title),
        )
      }

      VersionQueueOrder.FILE_NAME -> Comparator { a, b ->
        firstNonZero(
          compareNatural(fileKey(a), fileKey(b)),
          compareNatural(a.title, b.title),
        )
      }

      VersionQueueOrder.CUSTOM -> Comparator { a, b ->
        val ra = rank[a.id]
        val rb = rank[b.id]
        when {
          ra != null && rb != null -> ra.compareTo(rb)
          // 列过名字的排前面：用户编排的那几条才算「顺序」，其余是补位
          ra != null -> -1
          rb != null -> 1
          else -> firstNonZero(
            compareNatural(a.title, b.title),
            compareNatural(fileKey(a), fileKey(b)),
          )
        }
      }
    }

  // 用原下标兜底：完全无法区分时保持服务端给的顺序（这一条就是「稳定」的定义）。
  return entries.withIndex()
    .sortedWith(
      Comparator { x, y ->
        val result = comparator.compare(x.value, y.value)
        if (result != 0) result else x.index.compareTo(y.index)
      },
    )
    .map { it.value }
}

/**
 * 构建播放队列：先排序，再把环从 [currentId] 处切开。
 *
 * - [currentId] 为空 / 不在列表里 ⇒ 返回纯排序结果（不猜当前项）；
 * - 列表不足 2 条 ⇒ 原样返回（调用方应继续走原来的单条播放路径）。
 */
fun buildVersionQueue(
  entries: List<VersionQueueEntry>,
  currentId: String?,
  order: VersionQueueOrder,
  customOrder: List<String> = emptyList(),
): List<VersionQueueEntry> {
  val ordered = sortVersionEntries(entries, order, customOrder)
  if (ordered.size <= 1) return ordered
  val start = ordered.indexOfFirst { it.id == currentId }
  if (start <= 0) return ordered
  return ordered.drop(start) + ordered.take(start)
}

/**
 * [buildVersionQueue] 的 `EmbyMediaSource` 版本。
 *
 * @param labelOf 显示名生成器（调用方传 `embyMediaSourceTitle`，把本机自定义名也带上）
 */
fun buildMediaSourceQueue(
  sources: List<EmbyMediaSource>,
  currentId: String?,
  order: VersionQueueOrder,
  labelOf: (EmbyMediaSource) -> String,
  customOrder: List<String> = emptyList(),
): List<EmbyMediaSource> {
  val usable = sources.filter { !it.Id.isNullOrBlank() }
  if (usable.size < 2) return usable
  val entries = usable.mapNotNull { source -> versionQueueEntry(source, labelOf(source)) }
  val ordered = buildVersionQueue(entries, currentId, order, customOrder)
  // 按 Id 逐个消费而不是 associateBy：防御服务端偶发重复 Id（重复时不丢条目）
  val remaining = usable.toMutableList()
  return ordered.mapNotNull { entry ->
    val hit = remaining.firstOrNull { it.Id == entry.id } ?: return@mapNotNull null
    remaining.remove(hit)
    hit
  }
}

/**
 * 默认的自定义顺序（= 自然顺序）——给「自定义」编辑器做初始值，
 * 用户只需微调两三行，不用从空白开始排。
 */
fun defaultCustomOrder(
  sources: List<EmbyMediaSource>,
  labelOf: (EmbyMediaSource) -> String,
): List<String> =
  sortVersionEntries(
    sources.mapNotNull { source -> versionQueueEntry(source, labelOf(source)) },
    VersionQueueOrder.NATURAL,
  ).map { it.id }

/** 参与比较的文件名键：Path 的文件名去扩展名；没有 Path 时退回标题。 */
private fun fileKey(entry: VersionQueueEntry): String =
  bareFileName(entry.filePath).ifBlank { entry.title }.ifBlank { entry.id }

/**
 * 取文件名的「可比较形态」：去掉目录、扩展名与查询串。
 *
 * 兼容两种路径分割符：Emby 跑在 Windows 上会回 `D:\media\xxx.mkv`。
 */
fun bareFileName(path: String?): String {
  val trimmed = path?.trim().orEmpty()
  if (trimmed.isEmpty()) return ""
  val name = trimmed.replace('\\', '/').substringAfterLast('/')
  val withoutQuery = name.substringBefore('?').substringBefore('#')
  return withoutQuery.substringBeforeLast('.', withoutQuery).trim()
}

/**
 * 自然顺序比较：数字段按数值大小，其余按字符（忽略大小写）比较。
 *
 * 例：`第2集` < `第10集`，`CD2` < `CD10`，`a2` = `A02` 之后（数值相同、前导零更少的在前）。
 */
fun compareNatural(a: String, b: String): Int {
  var i = 0
  var j = 0
  while (i < a.length && j < b.length) {
    val ca = a[i]
    val cb = b[j]
    if (ca.isDigit() && cb.isDigit()) {
      var endA = i
      while (endA < a.length && a[endA].isDigit()) endA++
      var endB = j
      while (endB < b.length && b[endB].isDigit()) endB++
      // 去掉前导零后比「位数 → 字典序」，避免 20 位数字溢出 Long
      val numA = a.substring(i, endA).trimStart('0')
      val numB = b.substring(j, endB).trimStart('0')
      if (numA.length != numB.length) return numA.length - numB.length
      val byDigits = numA.compareTo(numB)
      if (byDigits != 0) return byDigits
      // 数值相同：前导零少的排前面（"2" 在 "02" 前）
      if (endA - i != endB - j) return (endA - i) - (endB - j)
      i = endA
      j = endB
    } else {
      val la = ca.lowercaseChar()
      val lb = cb.lowercaseChar()
      if (la != lb) return la.compareTo(lb)
      i++
      j++
    }
  }
  return when {
    i < a.length -> 1
    j < b.length -> -1
    else -> 0
  }
}

/** 返回第一个非 0 的比较结果（`?: 0`）。 */
private fun firstNonZero(vararg results: Int): Int = results.firstOrNull { it != 0 } ?: 0

/**
 * 剧集连播队列的**稳定排序**：先季号、再集号，缺集号时按名字自然顺序，最后用 Id 兜底。
 *
 * ## 为什么必须自己排（这是「第1集 → 第8集 → 第2集」的真正根因）
 *
 * `/Shows/{Id}/Episodes` **不保证顺序**。服务端有排序参数时按参数来，没有时不同版本
 * 会按「入库时间 / 名字字符串 / 内部 Id」给结果 —— 其中按名字字符串排序就是
 * `第10集 < 第2集`，按入库时间排序则完全看扫描顺序。启动侧以前直接 `subList(index, size)`，
 * 于是队列顺序原封不动继承了这些乱序。
 *
 * ## 三条兜底
 *
 * 1. 季号：两侧都有才比较（缺季号的一方不硬塞到某季，避免特别篇被甩到整季之后）；
 * 2. 集号：有集号的排在没集号的前面（花絮 / 特别篇通常没有 `IndexNumber`）；
 * 3. 都缺 ⇒ 名字自然顺序（`第2集` 在 `第10集` 前），再相同则按 Id ——
 *    保证任何输入都有确定结果，不依赖服务端返回顺序。
 */
fun sortEpisodesForQueue(episodes: List<EmbyItem>): List<EmbyItem> {
  if (episodes.size <= 1) return episodes
  return episodes.withIndex()
    .sortedWith { a, b ->
      val result = compareEpisodes(a.value, b.value)
      if (result != 0) result else a.index.compareTo(b.index)
    }
    .map { it.value }
}

private fun compareEpisodes(
  a: EmbyItem,
  b: EmbyItem,
): Int {
  val seasonA = a.ParentIndexNumber
  val seasonB = b.ParentIndexNumber
  if (seasonA != null && seasonB != null) {
    val bySeason = seasonA.compareTo(seasonB)
    if (bySeason != 0) return bySeason
  }

  val episodeA = a.IndexNumber
  val episodeB = b.IndexNumber
  if (episodeA != null && episodeB != null) {
    val byEpisode = episodeA.compareTo(episodeB)
    if (byEpisode != 0) return byEpisode
  } else if (episodeA != null) {
    return -1
  } else if (episodeB != null) {
    return 1
  }

  val byName = compareNatural(a.Name.orEmpty(), b.Name.orEmpty())
  if (byName != 0) return byName
  return a.Id.orEmpty().compareTo(b.Id.orEmpty())
}
