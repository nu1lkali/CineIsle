package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 内置的默认标记列表（用户没改过配置时用这份）。
 *
 * 扁平一列，编译时按字符构成自动分类（见 [ChineseSubtitleMarks]）：
 * 拉丁 / 汉字词组 / 单个汉字 三类规则不同，所以不需要用户自己区分。
 */
private val DEFAULT_MARKER_LIST: List<String> = listOf(
  // 拉丁标记：`-C` 是主标记，其余是常见写法（匹配时不分大小写）。
  // 单独一个 `C` 也留着 —— `HMN-864.C.1080p` 这种点分隔写法同样要能命中。
  "-C", "-CHS", "-CHT",
  "C", "CHS", "CHT", "CHI", "CHINESE", "GB", "GBK", "BIG5", "SC", "TC", "CN",
  // 汉字词组：子串匹配（中文标记常互相粘连：简繁中字 / 官方中字）
  "中文字幕", "中字", "中文", "简体", "簡體", "繁体", "繁體",
  "简中", "繁中", "简英", "繁英", "双语", "雙語", "内封中字", "内嵌中字",
  // 单个汉字：整词匹配（简·爱 里的「简」不算标记）
  "简", "繁",
)

/**
 * 「中文字幕」路径标记配置（可配置，不写死在代码里）。
 *
 * 为什么按路径猜：Emby 的 MediaStreams 只描述**服务端已经识别到的**字幕流，
 * 而 smartstrm / 网盘这类资源是在**文件名/目录名**里写明下没下中字的
 * （`/volume1/docker/smartstrm/strm/av/新下/HMN-864-C/489155.com@HMN-864-C.(mp4).strm`）。
 * Emby 的 /Items 也没有「路径包含某标记」这种服务端筛选参数，所以只能在客户端按路径判断。
 *
 * [markers] 是一个扁平的标记列表，编译时按字符构成自动分成三类：
 *  - 单个汉字（`简` `繁`）→ **整词**匹配：`简·爱` 不会被当成「简体」标记
 *  - 多个汉字（`中字` `简体` `中文字幕`）→ **子串**匹配：中文标记常互相粘连（`简繁中字`、`官方中字`）
 *  - 其它（`-C` `CHS` `CHT` …）→ 拉丁标记，**两侧都要有边界**，所以 `-C` 不会命中 `-CD` / `-CH` / `-CM`
 *
 * [matchWholePath] = true（默认）时整条路径参与匹配 —— 标记常常只写在**目录**上
 * （上面那条路径的 `-C` 同时出现在目录和文件名里，只盯文件名就会漏）。
 * 关掉则只看末段文件名，用于路径前缀里恰好含标记的极端情况。
 */
@Serializable
data class ChineseSubtitleMarks(
  val markers: List<String> = DEFAULT_MARKER_LIST,
  val matchWholePath: Boolean = true,
) {
  /** 规则指纹：用来判断要不要重新编译正则、清缓存 */
  fun signature(): String = "$matchWholePath|${markers.joinToString("\u0001")}"

  fun toJson(): String = json.encodeToString(serializer(), this)

  companion object {
    /**
     * 默认标记列表（按用户惯例 `-C` 为主，其余是常见中文 / 中字写法）。
     *
     * 两个刻意的取舍，都能在「编辑标记」里删掉：
     * - 留着单独一个 `C`：`HMN-864.C.1080p` 这种点分隔写法要能命中，
     *   代价是 `Vitamin C` 这类片名会被误判；
     * - 留着 `TC` / `SC` / `CN`：`TC` 同时是 Telecine 片源标记。
     */
    val DEFAULT_MARKERS: List<String> = DEFAULT_MARKER_LIST

    /** 单个标记的长度上限，防止有人把整段正则塞进来 */
    private const val MAX_MARKER_LENGTH = 24
    private const val MAX_MARKER_COUNT = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun fromJson(raw: String?): ChineseSubtitleMarks =
      if (raw.isNullOrBlank()) {
        ChineseSubtitleMarks()
      } else {
        runCatching { json.decodeFromString(serializer(), raw) }
          .getOrElse { ChineseSubtitleMarks() }
      }

    /**
     * 解析用户输入的标记文本：换行 / 逗号 / 顿号 / 空格 / 分号 都算分隔符。
     * 空串 → 回退到默认标记（不做「一个都不匹配」这种大概率是误操作的配置）。
     */
    fun parse(
      text: String,
      matchWholePath: Boolean,
    ): ChineseSubtitleMarks {
      val parsed = text
        .split(Regex("[\\s,，、;；|]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= MAX_MARKER_LENGTH }
        .distinct()
        .take(MAX_MARKER_COUNT)
      return ChineseSubtitleMarks(
        markers = parsed.ifEmpty { DEFAULT_MARKERS },
        matchWholePath = matchWholePath,
      )
    }
  }
}

/** 一条命中的结果：命中的标记（配置里的原文）+ 真正命中它的那个路径串 */
data class ChineseSubtitleHit(
  val marker: String,
  val path: String,
)

/**
 * 标记 → 正则的编译器。
 *
 * 三类标记各生成一个 alternation，最后拼成**一个**正则 —— 一次扫描就能判完，
 * 而且整个对象只在配置变化时构建一次（[Regex] 是编译期产品，不是每次匹配都编译）。
 */
internal class ChineseSubtitleRules(
  val marks: ChineseSubtitleMarks,
) {
  private val all: List<String> = marks.markers.filter { it.isNotBlank() }

  /** 拉丁标记里自带左侧定界符的（`-C`）和纯字母数字的（`CHS`）分开处理 */
  private val latinPrefixed: List<String> =
    all.filter { !isHanOnly(it) && !it[0].isLetterOrDigit() }.sortedByDescending { it.length }

  private val latinPlain: List<String> =
    all.filter { !isHanOnly(it) && it[0].isLetterOrDigit() }.sortedByDescending { it.length }

  /** 多字汉字标记：子串匹配 */
  private val cjkPhrases: List<String> =
    all.filter { isHanOnly(it) && it.length > 1 }.sortedByDescending { it.length }

  /** 单字汉字标记：整词匹配 */
  private val cjkTokens: List<String> = all.filter { isHanOnly(it) && it.length == 1 }

  private val regex: Regex? = build()

  val signature: String = marks.signature()

  private fun build(): Regex? {
    val parts = ArrayList<String>(4)
    if (latinPrefixed.isNotEmpty()) {
      // "-C" 这类自带 `-` 定界符：左边不用再判（`-` 本身就是边界），
      // 右边要求不是字母数字 —— 于是 `-CD` / `-CH` / `-CM` 都不会命中。
      parts += "(?:${latinPrefixed.joinToString("|") { Regex.escape(it) }})(?![A-Za-z0-9])"
    }
    if (latinPlain.isNotEmpty()) {
      // "CHS" 这类纯字母数字：两侧都必须是非字母数字，
      // 所以 `CHSDVD`（粘连）不算，`Movie.CHS.1080p` 才算。
      parts += "(?<![A-Za-z0-9])(?:${latinPlain.joinToString("|") { Regex.escape(it) }})(?![A-Za-z0-9])"
    }
    if (cjkPhrases.isNotEmpty()) {
      parts += "(?:${cjkPhrases.joinToString("|") { Regex.escape(it) }})"
    }
    if (cjkTokens.isNotEmpty()) {
      // 单字必须整词：前后都不能再是汉字；「·」「・」也一起挡掉，
      // 否则《简·爱》里的「简」会被当成简体标记（这是旧实现的已知误伤）。
      parts += "(?<![\\u4e00-\\u9fff\\u00b7\\u30fb])(?:${cjkTokens.joinToString("|") { Regex.escape(it) }})(?![\\u4e00-\\u9fff\\u00b7\\u30fb])"
    }
    if (parts.isEmpty()) return null
    return runCatching { Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE) }.getOrNull()
  }

  /**
   * 在 [path] 上找标记。命中返回**配置里的标记原文**（大小写归一），未命中返回 null。
   */
  fun findMarker(path: String): String? {
    val target = if (marks.matchWholePath) {
      path
    } else {
      path.substringAfterLast('/').substringAfterLast('\\')
    }
    val matched = regex?.find(target)?.value ?: return null
    return all.firstOrNull { it.equals(matched, ignoreCase = true) } ?: matched
  }
}

private fun isHanOnly(s: String): Boolean = s.isNotEmpty() && s.all { it in '\u4e00'..'\u9fff' }

/**
 * 「中文字幕」路径判定的全局入口。
 *
 * 三个职责：
 * 1. **规则集中管理**：界面读到配置后调 [configure]，正则只在这里编译一次；
 * 2. **结果缓存**：同一条路径只判一次（扫一个几万条的库时命中率很高，重复计算很浪费），
 *    缓存满了整体清空，规则一变立即失效；
 * 3. **路径取值兜底**：顶层 `Path` 拿不到时用 `MediaSources[].Path` —
 *    多版本条目（同一部片子 1080p / 4K 两条源）标记可能只写在其中一条上，
 *    所以要把**所有** MediaSource 的路径都过一遍，不能只看第一条。
 */
object ChineseSubtitleFilter {
  /** 缓存上限：满了直接清空。判定本身只是一次正则扫描（微秒级），不值得做 LRU */
  private const val MAX_CACHE = 8192

  private val lock = Any()

  private var rules = ChineseSubtitleRules(ChineseSubtitleMarks())

  /** key = 路径原文（含 query 的 URL 也按原文存，避免解码歧义），value = 命中标记，null = 未命中 */
  private val cache = LinkedHashMap<String, String?>()

  /** 配置变化时重新编译规则并作废缓存（界面侧读偏好后调用） */
  fun configure(marks: ChineseSubtitleMarks) {
    synchronized(lock) {
      if (rules.signature == marks.signature()) return
      rules = ChineseSubtitleRules(marks)
      cache.clear()
    }
  }

  /** 当前生效的标记配置（编辑界面回显用） */
  fun currentMarks(): ChineseSubtitleMarks = synchronized(lock) { rules.marks }

  /**
   * 判定单条路径。路径为空 / 字段缺失 → 未命中（不算异常）。
   */
  fun hitOfPath(path: String?): ChineseSubtitleHit? {
    if (path.isNullOrBlank()) return null
    val marker = synchronized(lock) {
      if (cache.containsKey(path)) {
        cache[path]
      } else {
        val found = rules.findMarker(decode(path))
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[path] = found
        found
      }
    } ?: return null
    return ChineseSubtitleHit(marker, path)
  }

  /**
   * 判定一条媒体项的所有候选路径（顶层 Path 优先，其次**每个** MediaSource 的 Path）。
   * 命中即返回，附上具体是哪条路径命中的。
   */
  fun hitOf(item: EmbyItem): ChineseSubtitleHit? {
    val candidates = ArrayList<String>(2)
    item.Path?.takeIf { it.isNotBlank() }?.let { candidates += it }
    item.MediaSources?.forEach { source ->
      source.Path?.takeIf { it.isNotBlank() }?.let { candidates += it }
    }
    for (candidate in candidates) {
      hitOfPath(candidate)?.let { return it }
    }
    return null
  }

  /** 便捷：只关心是不是中文字幕视频 */
  fun matches(item: EmbyItem): Boolean = hitOf(item) != null

  /**
   * 批量过滤：返回 (媒体项, 命中信息) 的有序列表。
   * 调用方请放在 IO / Default 线程上（扫描整库时这一步是纯 CPU 活）。
   */
  fun filter(items: List<EmbyItem>): List<Pair<EmbyItem, ChineseSubtitleHit>> {
    val result = ArrayList<Pair<EmbyItem, ChineseSubtitleHit>>(items.size / 4 + 1)
    for (item in items) {
      hitOf(item)?.let { result += item to it }
    }
    return result
  }

  /** 直链可能是 percent-encode 过的（strm 解析出来的 URL），解码后再判，否则中文标记会被 `%E4%B8%AD` 挡住 */
  private fun decode(raw: String): String {
    val withoutQuery = raw.substringBefore('?')
    return runCatching { java.net.URLDecoder.decode(withoutQuery, "UTF-8") }
      .getOrDefault(withoutQuery)
  }
}
