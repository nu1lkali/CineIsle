package app.marlboroadvance.mpvex.domain.subtitle

import java.io.File
import java.nio.charset.Charset

/**
 * 一条字幕（一个时间轴条目）。
 *
 * @property startMs 起始毫秒
 * @property endMs 结束毫秒（解析失败或缺失时退化成 `startMs + 1`，只用于显示与跳转）
 * @property text 纯文本内容（已去掉 ASS 的 `{\...}` 标签、`\N` 换行等）
 */
data class SubtitleCue(
  val startMs: Long,
  val endMs: Long,
  val text: String,
) {
  /** 供列表显示的「起 → 止」时间串。 */
  val timeLabel: String
    get() = "${SubtitleTimeline.clock(startMs)} → ${SubtitleTimeline.clock(endMs)}"
}

/**
 * 本地字幕文件的**时间轴解析**（纯函数，无 Android 依赖）。
 *
 * ## 为什么需要它
 *
 * 字幕实验室原本只能「看画面 + 拧偏移」，用户看不到字幕本身写了什么、也不知道
 * 某一句在几分几秒 —— 想定位「从这句话开始就对不上了」只能靠拖进度条瞎找。
 * 把条目列出来、点一下跳到那一刻，校准才真正可操作。
 *
 * ## 支持的格式
 *
 * · **SRT** —— `00:00:01,000 --> 00:00:03,000`（逗号毫秒）
 * · **VTT** —— `00:00:01.000 --> 00:00:03.000`（点毫秒，可带 `align:` 等后缀）
 * · **ASS/SSA** —— `Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,文本`
 *
 * 其它扩展名不猜：按内容里有没有 `[Script Info]` / `-->` 判断，认不出就返回空表
 * （界面据此提示「这个格式不支持预览时间线」，而不是假装解析成功给一张空列表）。
 *
 * ## 编码
 *
 * 中文站点的 SRT 常见 **GBK**，而下载器按字节原样落盘。这里先按 UTF-8 严格解码，
 * 失败再退 GBK —— 不这样做的话，列表里会是一串 `锟斤拷`，比不给看更糟。
 */
object SubtitleTimeline {
  /** 单条字幕上限：再多的片子也不会有意义地超过这个数量，防手滑选中巨大文件。 */
  const val MAX_CUES = 6_000

  /** 单条字幕文本上限（字符）。极端情况下有整段台词塞一行，截断以免撑爆列表。 */
  private const val MAX_TEXT_CHARS = 300

  private val TS = Regex("(\\d{1,3}):(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?")
  /** VTT 允许省略小时：`00:01.500 --> 00:03.000`（分:秒.毫秒）。 */
  private val TS_SHORT = Regex("(\\d{1,3}):(\\d{1,2})[,.](\\d{1,3})")
  private val ASS_TAG = Regex("\\{[^}]*\\}")

  /**
   * 解析结果。
   *
   * [truncated] 表示条目数超过 [MAX_CUES]，尾部被截掉 —— 界面**必须**据此提示，
   * 否则用户会以为字幕只有前 [MAX_CUES] 条，把「截断」误当成「文件坏了」。
   */
  data class ParsedTimeline(
    val cues: List<SubtitleCue>,
    val truncated: Boolean = false,
  )

  /** 从文件读并解析；读不到返回空表（调用方负责区分「空」与「失败」）。 */
  fun parseFile(file: File): List<SubtitleCue> = parseFileDetailed(file).cues

  /** 同 [parseFile]，但额外带出「是否因条目过多被截断」。 */
  fun parseFileDetailed(file: File): ParsedTimeline {
    if (!file.isFile) return ParsedTimeline(emptyList())
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ParsedTimeline(emptyList())
    if (bytes.isEmpty()) return ParsedTimeline(emptyList())
    return parseTextDetailed(decode(bytes), file.extension)
  }

  /**
   * 解析文本。 [hint] 是文件扩展名（`srt` / `ass` / `vtt` …），只作为选择解析器的提示，
   * 真正判据仍是内容特征 —— 站点给的文件扩展名经常是错的。
   */
  fun parseText(
    raw: String,
    hint: String? = null,
  ): List<SubtitleCue> = parseTextDetailed(raw, hint).cues

  /** 同 [parseText]，但额外带出「是否因条目过多被截断」。 */
  fun parseTextDetailed(
    raw: String,
    hint: String? = null,
  ): ParsedTimeline {
    if (raw.isBlank()) return ParsedTimeline(emptyList())
    val text = raw.removePrefix("\uFEFF")
    val normalizedHint = hint?.lowercase()?.trim()
    val isAss =
      normalizedHint == "ass" ||
        normalizedHint == "ssa" ||
        text.contains("[Script Info]", ignoreCase = true) ||
        text.contains("\nDialogue:", ignoreCase = true)
    val cues = if (isAss) parseAss(text) else parseTimedText(text)
    return if (cues.size > MAX_CUES) {
      ParsedTimeline(cues.subList(0, MAX_CUES), truncated = true)
    } else {
      ParsedTimeline(cues)
    }
  }

  // ──────────────────────────── SRT / VTT ────────────────────────────

  private fun parseTimedText(text: String): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>()
    val buffer = StringBuilder()
    var startMs = -1L
    var endMs = -1L

    fun flush() {
      if (startMs >= 0 && buffer.isNotBlank()) {
        cues +=
          SubtitleCue(
            startMs = startMs,
            endMs = if (endMs > startMs) endMs else startMs + 1,
            text = cleanText(buffer.toString()),
          )
      }
      startMs = -1L
      endMs = -1L
      buffer.setLength(0)
    }

    text.lineSequence().forEach { rawLine ->
      val line = rawLine.trimEnd()
      val arrow = line.indexOf("-->")
      if (arrow >= 0) {
        // 新条目开始：上一条的正文到此为止
        flush()
        val from = parseTimestampMs(line.substring(0, arrow)) ?: return@forEach
        val to = parseTimestampMs(line.substring(arrow + 3)) ?: from
        startMs = from
        endMs = to
      } else if (line.isBlank()) {
        flush()
      } else if (startMs >= 0) {
        // `WEBVTT` / `NOTE ...` / 纯数字序号出现在正文区之外，靠 startMs < 0 自然挡掉
        if (buffer.isNotEmpty()) buffer.append('\n')
        buffer.append(line.trim())
      }
    }
    flush()
    return cues.filter { it.text.isNotBlank() }
  }

  private fun parseTimestampMs(segment: String): Long? {
    TS.find(segment)?.let { match ->
      val (h, m, s, ms) = match.destructured
      val millis = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
      return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + millis
    }
    // VTT 的「省略小时」写法
    TS_SHORT.find(segment)?.let { match ->
      val (m, s, ms) = match.destructured
      val millis = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
      return m.toLong() * 60_000L + s.toLong() * 1_000L + millis
    }
    return null
  }

  // ──────────────────────────── ASS / SSA ────────────────────────────

  /**
   * ASS 的 `Dialogue:` 行。
   *
   * 固定前 9 个字段（Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect），
   * 第 10 个字段往后**整体**是文本 —— 所以按 `,` 最多切成 10 段，绝不能分成无限段：
   * 文本里本来就有逗号。
   */
  private fun parseAss(text: String): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>()
    var inEvents = false
    text.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.startsWith("[")) {
        inEvents = line.equals("[Events]", ignoreCase = true)
        return@forEach
      }
      if (!inEvents) return@forEach
      if (!line.startsWith("Dialogue:", ignoreCase = true)) return@forEach
      val fields = line.substringAfter(':').split(',', limit = 10)
      if (fields.size < 10) return@forEach
      val start = assTimestamp(fields[1]) ?: return@forEach
      val end = assTimestamp(fields[2]) ?: start
      val body = cleanText(fields[9])
      if (body.isBlank()) return@forEach
      cues += SubtitleCue(startMs = start, endMs = if (end > start) end else start + 1, text = body)
    }
    return cues
  }

  /** ASS 时间戳：`0:00:01.00`（小时不补零，百分之一秒）。 */
  private fun assTimestamp(raw: String): Long? {
    val parts = raw.trim().split(':')
    if (parts.size < 3) return null
    val h = parts[0].trim().toLongOrNull() ?: return null
    val m = parts[1].trim().toLongOrNull() ?: return null
    val secPart = parts[2].trim().replace(',', '.')
    val whole = secPart.substringBefore('.').toLongOrNull() ?: return null
    val frac = secPart.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return h * 3_600_000L + m * 60_000L + whole * 1_000L + frac
  }

  // ──────────────────────────── 工具 ────────────────────────────

  /**
   * 去掉渲染标记，只留人能读的正文。
   *
   * ASS 的 `{\pos(320,40)}`、`{\an8}`、`{\c&HFFFFFF&}` 这类覆盖标签对「读一句台词」毫无意义，
   * 留着只会让列表变成乱码。`\N` / `\n` 是硬换行，`\h` 是不换行空格。
   */
  private fun cleanText(raw: String): String {
    val stripped = ASS_TAG.replace(raw, "")
      .replace("\\N", "\n")
      .replace("\\n", "\n")
      .replace("\\h", " ")
    val trimmed = stripped.lines().joinToString("\n") { it.trim() }.trim()
    return if (trimmed.length > MAX_TEXT_CHARS) trimmed.take(MAX_TEXT_CHARS) + "…" else trimmed
  }

  /**
   * 解码字节：先 UTF-8 严格模式，失败再 GBK。
   *
   * 严格模式是关键 —— `String(bytes, UTF_8)` 遇到非法字节会静默替换成 `�`，
   * 于是 GBK 文件也能「解码成功」，只是内容全废。
   */
  fun decode(bytes: ByteArray): String {
    if (bytes.size >= 3 &&
      bytes[0] == 0xEF.toByte() &&
      bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte()
    ) {
      return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    val strictUtf8 =
      runCatching {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString()
      }.getOrNull()
    if (strictUtf8 != null) return strictUtf8
    val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
    return gbk ?: String(bytes, Charsets.UTF_8)
  }

  /** `mm:ss` / `h:mm:ss`，列表里用。 */
  fun clock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
      String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
      String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }
  }
}
