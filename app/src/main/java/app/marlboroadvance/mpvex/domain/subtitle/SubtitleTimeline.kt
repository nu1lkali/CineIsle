package app.marlboroadvance.mpvex.domain.subtitle

import java.io.File
import java.nio.charset.Charset

/**
 * 判定「源文件自身已被替换符破坏」的比例门槛。
 *
 * 真实样本（迅雷源同一部片的 19 条字幕）里，坏文件是 12.2% 与 15.6%，
 * 正常文件是 0 —— 中间地带极宽。取 0.2% 既不会误伤偶发一个 `�` 的正常字幕，
 * 也能在任何「成片丢字」的文件上立刻成立。
 *
 * 之所以要这个判据，是因为**严格 UTF-8 解码成功不代表文件是好的**：
 * 上游把 GBK/Big5 字节按 UTF-8 替换式解码后另存，丢失的字节就变成了字面量
 * `U+FFFD` 永久留在文件里。此时再换任何编码，得到的都是另一种乱码。
 */
private const val DAMAGED_RATIO = 0.002f

/** 判定损坏前至少要有这么多替换符，避免超短文件被一个字符带偏。 */
private const val DAMAGED_MIN_CHARS = 4

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
 * 中文站点的 SRT 常见 **GBK**，繁体站是 **Big5**，日文站是 **Shift_JIS / EUC-JP**，
 * 而下载器按字节原样落盘。这里按 BOM → 严格 UTF-8 → 多候选择优 → 二次编码还原
 * 逐级判定（见 [decodeDetailed]），否则列表里会是一串 `锟斤拷`，比不给看更糟。
 *
 * 判定结果同时是**挂载路径的编码来源**：`sub-add` 之前用同一份结论把字节归一成
 * UTF-8（见 `SubtitleEncoding`），否则预览与播放会各说各话 —— 预览按 Big5 解对了，
 * mpv 自己猜成 GBK，画面上就是乱码。
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

  /**
   * 一次解码的完整结论。
   *
   * @property text 解码后的文本
   * @property encoding 实际采用的编码标签（`UTF-8` / `GB18030` / `Big5` / `UTF-16LE` …）
   * @property damagedRatio 文本中**字面量** `U+FFFD` 占全部字符的比例。
   *   注意它和「解码失败替换符」不是一回事：严格 UTF-8 解码成功、但文件里本来就存着
   *   `U+FFFD` 时，这些字符是**已经丢失的数据**，任何编码都换不回来。
   * @property damaged 源文件自身已被替换符破坏（[damagedRatio] 越过门槛且数量足够）。
   * @property repaired 是否做过二次编码还原或逐行编码修复。为 true 说明原字节不是
   *   规范 UTF-8，挂载前必须重写成 UTF-8，否则 mpv 会按它自己的猜法再解一次。
   */
  data class Decoded(
    val text: String,
    val encoding: String,
    val damagedRatio: Float = 0f,
    val damaged: Boolean = false,
    val repaired: Boolean = false,
  ) {
    /**
     * 这份字节能否原样交给 mpv。
     *
     * 判据是「严格 UTF-8 且解码过程没改动过任何字符」。损坏文件不在其中做特殊处理：
     * 它本身是合法 UTF-8，mpv 直读得到的文本与预览完全一致（缺字就都缺字），
     * 重写成同样的字节毫无意义。
     */
    val mpvSafe: Boolean get() = !repaired && encoding == "UTF-8"
  }

  /** 从文件读并解析；读不到返回空表（调用方负责区分「空」与「失败」）。 */
  fun parseFile(file: File): List<SubtitleCue> = parseFileDetailed(file).cues

  /** 同 [parseFile]，但额外带出「是否因条目过多被截断」。 */
  fun parseFileDetailed(file: File): ParsedTimeline = parseFileInfo(file).timeline

  /**
   * 解析结果 + 解码结论。
   *
   * 面板需要后者才能如实说明「这条字幕本身缺字」—— 那是文件在上游就被替换符吃掉的
   * 字符，换编码救不回来；不说清楚，用户只会以为是我们解析坏了。
   */
  data class ParsedFile(
    val timeline: ParsedTimeline,
    val decoded: Decoded,
  )

  /** 读文件、判编码、解析，一次把结论都带出来。 */
  fun parseFileInfo(file: File): ParsedFile {
    if (!file.isFile) return ParsedFile(ParsedTimeline(emptyList()), Decoded("", "UTF-8"))
    val bytes = runCatching { file.readBytes() }.getOrNull()
      ?: return ParsedFile(ParsedTimeline(emptyList()), Decoded("", "UTF-8"))
    if (bytes.isEmpty()) return ParsedFile(ParsedTimeline(emptyList()), Decoded("", "UTF-8"))
    val decoded = decodeDetailed(bytes)
    return ParsedFile(parseTextDetailed(decoded.text, file.extension), decoded)
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
   * 解码字节：BOM 优先 → 严格 UTF-8 → 多候选择优 → 二次编码还原。
   *
   * ## 为什么不能「UTF-8 失败就退 GBK」
   *
   * 旧实现这么做，遇到了必然乱码：Big5（繁体站）、Shift_JIS（日文）、EUC-KR、
   * 以及**没有 BOM 的 UTF-16**，用 GBK 解都能「成功」—— 双字节编码彼此几乎不会报错，
   * 只是每个字都解成另一个字。界面上就是满屏乱码。
   *
   * ## 怎么选
   *
   * 每个候选编码都解一遍，按「解出来的文本像不像人话」打分（按字符数归一，
   * 否则长短不同的候选之间没法比）：
   * · 常用汉字 / 假名 / 谚文加分（[COMMON_CJK] 收录常用简体与繁体字）；
   * · 生僻汉字扣分 —— 解错时的典型结果就是一串没人用的字；
   * · 替换符 `�`、控制字符、私用区字符、拉丁扩展残留大幅扣分；
   * 取平均分最高的候选。UTF-16 无 BOM 也靠「控制字符（NUL）扣分」被自然淘汰。
   *
   * 严格 UTF-8 仍然先试：九成文件是 UTF-8，不必进入打分。
   *
   * ## 为什么候选表里必须有 UTF-8
   *
   * 严格解码是「全有或全无」的：一个坏字节就让整篇失败。若候选表里没有
   * UTF-8（替换式解码），那份**九成正确、只坏了一两个字节**的 UTF-8 文件
   * 会被硬塞给 GB18030 解，整篇 CJK 变成乱码 —— 比原来的两个坏字节糟得多。
   */
  fun decode(bytes: ByteArray): String = decodeDetailed(bytes).text

  /**
   * 同 [decode]，但把「用了哪个编码 / 是否已损坏 / 是否做过修复」一并带出来。
   *
   * ## 为什么必须有损坏闸门
   *
   * 严格 UTF-8 成功**不等于**文件是好的。上游把 GBK/Big5 字节按 UTF-8 替换式解码
   * 再另存，丢掉的字节就变成字面量 `U+FFFD` 永久留在文件里。此时：
   *
   * · 逐行修复会把这些 `U+FFFD` 行当成「解错了」，换成别的编码 —— 于是本来只是
   *   缺字的句子被改成韩文 / 拉丁乱码，比原样更糟（真实样本里 8/19 条都是这样）；
   * · 换整篇编码同理，只会得到与原文无关的第三种乱码。
   *
   * 所以一旦确认源文件已损坏，就**停在 UTF-8 直读结果上**，不做任何修复，
   * 让界面如实告诉用户「这个文件本身缺字」，而不是假装修好了。
   */
  fun decodeDetailed(bytes: ByteArray): Decoded {
    if (bytes.isEmpty()) return Decoded("", "UTF-8")
    // UTF-32 BOM 必须排在 UTF-16 之前：UTF-32LE 的 BOM 前两字节同样是 FF FE，
    // 先按 UTF-16LE 解会把后面每 4 字节当 2 个字符，整篇错位。
    utf32Bom(bytes)?.let { return settle(it, null, "UTF-32") }
    // UTF-16 BOM：一部分 ASS 是这样存的。不先认 BOM 的话，整篇会被当成非法字节。
    if (bytes.size >= 2) {
      val b0 = bytes[0].toInt() and 0xFF
      val b1 = bytes[1].toInt() and 0xFF
      if (b0 == 0xFF && b1 == 0xFE) {
        // 按偶数长度取：多出的半个字符直接丢掉，避免解码出替换符
        val even = (bytes.size - 2) - ((bytes.size - 2) % 2)
        val body = if (even > 0) even else bytes.size - 2
        // ⚠️ UTF-16 的 `\n` 是两字节，逐行修复的字节切分不成立 ⇒ 传 null 跳过
        return settle(String(bytes, 2, body, Charsets.UTF_16LE), null, "UTF-16LE")
      }
      if (b0 == 0xFE && b1 == 0xFF) {
        val even = (bytes.size - 2) - ((bytes.size - 2) % 2)
        val body = if (even > 0) even else bytes.size - 2
        return settle(String(bytes, 2, body, Charsets.UTF_16BE), null, "UTF-16BE")
      }
    }
    if (bytes.size >= 3 &&
      bytes[0] == 0xEF.toByte() &&
      bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte()
    ) {
      val body = bytes.copyOfRange(3, bytes.size)
      return settle(String(body, Charsets.UTF_8), body, "UTF-8")
    }
    val strictUtf8 =
      runCatching {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString()
      }.getOrNull()
    if (strictUtf8 != null) return settle(strictUtf8, bytes, "UTF-8")

    val scored = bestByScoring(bytes)
    return settle(scored.text, bytes, scored.encoding)
  }

  /**
   * 收口：先判断损坏，再决定要不要做二次编码还原 / 逐行修复。
   *
   * @param mixedLines 用于逐行修复的原始字节；传 null 表示不能按行切字节
   *   （UTF-16 / UTF-32 的换行是两字节以上）
   */
  private fun settle(text: String, mixedLines: ByteArray?, encoding: String): Decoded {
    if (text.isEmpty()) return Decoded("", encoding)
    val ratio = replacementRatio(text)
    if (isDamaged(text, ratio)) {
      // 已损坏：保持直读结果，绝不再换编码（换出来的东西与原文无关）
      return Decoded(text = text, encoding = encoding, damagedRatio = ratio, damaged = true)
    }
    val retried = repairDoubleEncoding(text)
    val mixed = if (mixedLines != null) repairMixedLines(mixedLines, retried) else retried
    return Decoded(
      text = mixed,
      encoding = encoding,
      damagedRatio = ratio,
      repaired = mixed != text,
    )
  }

  /** 字面量 `U+FFFD` 的占比。 */
  private fun replacementRatio(text: String): Float {
    if (text.isEmpty()) return 0f
    var count = 0
    for (ch in text) if (ch == '\uFFFD') count++
    return count.toFloat() / text.length
  }

  /**
   * 是否判定为「源文件已损坏」。
   *
   * 双条件：比例越线 **且** 绝对数量足够。只比例不数量时，一个 20 字的短片段
   * 出现 1 个 `�`（5%）会被误判；只数量不比例时，一句台词里几个 `�` 又会漏掉。
   */
  private fun isDamaged(text: String, ratio: Float): Boolean {
    if (ratio < DAMAGED_RATIO) return false
    var count = 0
    for (ch in text) if (ch == '\uFFFD') count++
    return count >= DAMAGED_MIN_CHARS
  }

  /** UTF-32 的 BOM（`FF FE 00 00` / `00 00 FE FF`）；没有就返回 null。 */
  private fun utf32Bom(bytes: ByteArray): String? {
    if (bytes.size < 8) return null
    fun at(index: Int) = bytes[index].toInt() and 0xFF
    val body = bytes.size - 4
    val usable = body - (body % 4)
    // 某些 ROM 上 UTF-32 不在标准字符集里，取不到就当作没有 BOM，交给后面的打分流程。
    return when {
      at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 ->
        runCatching { String(bytes, 4, usable, Charsets.UTF_32LE) }.getOrNull()
      at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF ->
        runCatching { String(bytes, 4, usable, Charsets.UTF_32BE) }.getOrNull()
      else -> null
    }
  }

  /**
   * 候选编码逐个解码，取**平均**可信度最高的一个。
   *
   * 归一化（除以字符数）是必要的：UTF-16 解出来只有一半字符，若用总分比较，
   * 字符数少的候选在全是负分时反而占便宜。
   *
   * 返回时把胜出的编码名一并带出 —— 挂载路径要按同一个编码把字节重写成 UTF-8，
   * 只回文本的话，那边就得再猜一次，两处结论会漂。
   */
  private fun bestByScoring(bytes: ByteArray): Scoring {
    var best: String? = null
    var bestName = "UTF-8"
    var bestScore = Double.NEGATIVE_INFINITY
    for (name in candidates(bytes)) {
      val text = runCatching { String(bytes, Charset.forName(name)) }.getOrNull() ?: continue
      if (text.isEmpty()) continue
      val score = plausibility(text) / text.length
      if (score > bestScore) {
        bestScore = score
        best = text
        bestName = name
      }
    }
    return Scoring(best ?: String(bytes, Charsets.UTF_8), bestName)
  }

  /** [bestByScoring] 的返回：解码文本 + 胜出的编码名。 */
  private data class Scoring(val text: String, val encoding: String)

  /**
   * 逐行校正：把「文件内部混了两种编码」的那几行单独重解。
   *
   * ## 为什么整篇选出一个编码还不够
   *
   * 用户报的现象是「**有的**字幕乱码」，而不是整篇乱码。整个文件统一解码时，
   * 只要选错编码就应该**全篇**都错；出现「一部分对、一部分错」只有一种解释：
   * 文件里混着两种编码（常见于把两三条字幕拼起来、或站点用不同脚本分批转码）。
   * 整篇打分只能取平均，少数行的编码与主体不同时，这些行必然整行报废。
   *
   * ## 怎么判「这一行不对」
   *
   * 逐行算 [plausibility] 的平均分：正常中文行通常在 1.5 分/字符以上，
   * 而被错误编码解出来的行会大面积落在生僻汉字（-0.7）或替换符（-20），
   * 平均分掉到 0.5 以下。低于门槛才值得重解，正常行一律不动 ——
   * 这是「宁可不修，也不能把对的改错」。
   *
   * 重解时要求新结果比现状**好 0.5 分/字符**以上，避免在两种都别扭的结果间反复横跳。
   *
   * ## 两个刻意的取舍
   *
   * · 候选里**不含日韩编码**（[LINE_CHARSETS]）。中日韩混排在同一个字幕文件里几乎
   *   不存在，而 GBK 字节用 EUC-KR 解会得到大片谚文、按现有打分反而比正确的中文更高分
   *   —— 逐行放开日韩，等于给「中文变韩文」开门。日文字幕文件由整篇判定负责。
   * · 行数对不上就直接放弃（老式 `\r` 单换行等）。字节切行只在 8-bit 编码族里成立：
   *   UTF-16 / UTF-32 已在上游按 BOM 返回，不会走到这里。
   */
  private fun repairMixedLines(bytes: ByteArray, text: String): String {
    if (text.isEmpty() || bytes.size < 64 || bytes.size > LINE_REPAIR_MAX_BYTES) return text
    val byteLines = splitByteLines(bytes)
    val lines = text.split('\n')
    // 行数不等说明「字节里的换行」与「解出来的换行」对不上（例如只按 \r 换行），
    // 此时按行重解的对应关系不成立，宁可整篇不动。
    if (byteLines.size != lines.size) return text

    val out = ArrayList<String>(lines.size)
    var changed = false
    for (index in lines.indices) {
      val raw = lines[index]
      val hasCr = raw.endsWith('\r')
      val plain = if (hasCr) raw.dropLast(1) else raw
      val fixed = repairLine(byteLines[index], plain)
      if (fixed == null) {
        out += raw
      } else {
        changed = true
        out += if (hasCr) fixed + "\r" else fixed
      }
    }
    return if (changed) out.joinToString("\n") else text
  }

  /** 按 `\n` 切字节行（保留空行），返回的每一段都不含换行符。 */
  private fun splitByteLines(bytes: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>(64)
    var start = 0
    for (index in bytes.indices) {
      if (bytes[index] == '\n'.code.toByte()) {
        out += bytes.copyOfRange(start, index)
        start = index + 1
      }
    }
    out += bytes.copyOfRange(start, bytes.size)
    return out
  }

  /**
   * 重解一行；返回 null 表示「这一行没问题，别动」。
   *
   * 纯 ASCII 行（序号、时间码、ASS 样式头）直接跳过：所有 8-bit 候选对 < 0x80 的
   * 字节解出来完全一致，试也是白试。
   */
  private fun repairLine(lineBytes: ByteArray, line: String): String? {
    if (lineBytes.isEmpty()) return null
    var hasHighByte = false
    for (byte in lineBytes) {
      if (byte < 0) {
        hasHighByte = true
        break
      }
    }
    if (!hasHighByte) return null
    val before = if (line.isEmpty()) 0.0 else plausibility(line) / line.length
    if (before >= LINE_TRUSTWORTHY) return null

    var best: String? = null
    var bestScore = before + LINE_GAIN
    for (name in LINE_CHARSETS) {
      val candidate = runCatching { String(lineBytes, Charset.forName(name)) }.getOrNull() ?: continue
      val candidateText = candidate.trimEnd('\r', '\n')
      if (candidateText.isEmpty()) continue
      val score = plausibility(candidateText) / candidateText.length
      if (score > bestScore) {
        bestScore = score
        best = candidateText
      }
    }
    return best?.let { repairDoubleEncoding(it) }
  }

  /**
   * 逐行重解时允许的候选（**故意不含** Shift_JIS / EUC-JP / EUC-KR，理由见
   * [repairMixedLines]）。windows-1252 留着是为了救「某几行是拉丁乱码」的极端情况。
   */
  private val LINE_CHARSETS = listOf("GB18030", "Big5", "UTF-8", "windows-1252")

  /** 一行平均分达到这个值就认为解码正确，不再尝试重解。 */
  private const val LINE_TRUSTWORTHY = 0.5

  /** 逐行替换的门槛：新结果必须比现状好这么多（每字符）才采用。 */
  private const val LINE_GAIN = 0.5

  /** 超过 8MB 不做逐行校正：字幕不可能这么大，逐行扫描的收益抵不上开销。 */
  private const val LINE_REPAIR_MAX_BYTES = 8 * 1024 * 1024

  /**
   * 候选编码：统计检测器的结论排在最前，再接固定候选表。
   *
   * 统计检测（[detectedByChardet]）在长样本上很准，但**短文本会瞎猜**
   * （GBK 常被报成 windows-1252），所以它只是候选之一，胜负仍由 [plausibility] 判定。
   */
  private fun candidates(bytes: ByteArray): List<String> {
    val out = LinkedHashSet<String>()
    detectedByChardet(bytes)?.let { name ->
      // UTF-16 结论必须与字节结构一致才收：GBK 文本按 UTF-16 解会落进谚文区，
      // 打分器会误判成韩文（实测踩过）。
      val isUtf16 = name.startsWith("UTF-16", ignoreCase = true)
      if (!isUtf16 || looksLikeUtf16(bytes)) out += name
    }
    out += fallbackCharsets(bytes)
    return out.toList()
  }

  /**
   * Mozilla universalchardet 的统计检测结果（字符集名，可能为 null）。
   *
   * 任何异常都吞掉：识别编码是「尽力而为」的辅助，不能因为它让整条字幕读不出来。
   */
  private fun detectedByChardet(bytes: ByteArray): String? =
    runCatching {
      val detector = org.mozilla.universalchardet.UniversalDetector(null)
      detector.handleData(bytes, 0, bytes.size)
      detector.dataEnd()
      detector.detectedCharset
    }.getOrNull()

  /**
   * 固定候选表。
   *
   * · `GB18030` 覆盖 GBK / GB2312；
   * · `Big5` 管繁体；
   * · `UTF-8` 兜「只坏了个别字节」的 UTF-8（严格解码已经失败才会走到这里）；
   * · `Shift_JIS` / `EUC-JP` 管日文，`EUC-KR` 管韩文；
   * · `windows-1252` 兜西文 / 已二次编码的拉丁残留。
   *
   * **UTF-16 / UTF-32 只有在字节结构像 UTF-16 时才列入**：GBK 中文的字节按
   * UTF-16BE 解，会落在谚文区（0xAC00–0xD7AF），打分器会误以为「这是韩文」。
   * 字幕文件一定带时间码等 ASCII 结构，UTF-16 下这些字符有一半字节是 0x00 ——
   * 用这个特征先筛一遍。
   */
  private fun fallbackCharsets(bytes: ByteArray): List<String> =
    if (looksLikeUtf16(bytes)) UTF16_CHARSETS else NON_UTF16_CHARSETS

  private val NON_UTF16_CHARSETS =
    listOf("GB18030", "Big5", "UTF-8", "Shift_JIS", "EUC-JP", "EUC-KR", "windows-1252")

  private val UTF16_CHARSETS =
    listOf(
      "GB18030",
      "Big5",
      "UTF-8",
      "UTF-16LE",
      "UTF-16BE",
      "Shift_JIS",
      "EUC-JP",
      "EUC-KR",
      "windows-1252",
    )

  /** 某种字节序下 0x00 的占比是否达到 UTF-16 的典型水平（≥1/8）。 */
  private fun looksLikeUtf16(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, 8 * 1024)
    if (limit < 16) return false
    var zerosEven = 0
    var zerosOdd = 0
    for (i in 0 until limit) {
      if (bytes[i] == 0.toByte()) {
        if (i % 2 == 0) zerosEven++ else zerosOdd++
      }
    }
    val threshold = (limit / 2) / 8
    return zerosEven > threshold || zerosOdd > threshold
  }

  /**
   * 还原「二次编码」：UTF-8 字节被当成 Latin-1 / CP1252 读过一遍再存成 UTF-8。
   *
   * 站点上这类文件很常见（转码脚本链走错一步），表现是满屏 `Ã¤Â¸Â­` 那种拉丁字母
   * 加变音符号的组合。它的字节其实是**合法的 UTF-8**，所有解码器都会「成功」，
   * 只能靠这种逆向重排救回来。
   *
   * 严格限制触发条件，避免把正常文本改坏：
   * · 文本里不能有码点 > 0xFF 的字符（真有中文就不可能是这种 mojibake）；
   * · 拉丁扩展字符（0x80–0xFF）至少 3 个，太少说明只是正常的外文标点；
   * · 还原后可信度必须**明显**变高（≥ 0.6 分/字符），否则保持原样。
   */
  private fun repairDoubleEncoding(text: String): String {
    if (text.isEmpty()) return text
    var latin = 0
    for (ch in text) {
      val code = ch.code
      if (code > 0xFF) return text
      if (code in 0x80..0xFF) latin++
    }
    if (latin < 3) return text
    val bytes = runCatching { text.toByteArray(Charsets.ISO_8859_1) }.getOrNull() ?: return text
    val retried =
      runCatching {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString()
      }.getOrNull() ?: return text
    if (retried.isEmpty()) return text
    val before = plausibility(text) / text.length
    val after = plausibility(retried) / retried.length
    return if (after - before >= 0.6) retried else text
  }

  /**
   * 常用汉字表（简 + 繁）。只用来给「解码结果像不像中文」打分，
   * 不做任何替换 —— 表里没有的字照样保留，只是不加分。
   */
  private val COMMON_CJK =
    (
      "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着" +
        "下自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进" +
        "好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明" +
        "性知全三又关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新" +
        "己制身果加西斯月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解" +
        "水名真论处走义各入几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量" +
        "再感建务做接必场件计管期市直德资命山金指克许统区保至队形社便空决治展马科司五基眼" +
        "書這個們為時來會說對開關後麼樣發現點與無話語頭問題實學應該經濟業務電話電腦網絡國" +
        "電影視頻時間內容繁體簡體語譯員樂買賣讀寫聽聲道車東門長馬鳥魚龍鳳華萬歲點線條變讓" +
        "從愛親師學問鄉間陽陰醫療護照顧資源資訊軟體硬體網路伺服器檔案設定執行編輯選擇確認"
    ).toSet()

  /**
   * 文本「像不像正确解码」的启发式评分。
   *
   * 判据是可解释的：正确解码出来的是常用字 + 标点；错误解码出来的是生僻汉字、
   * 替换符、控制字符。不做统计模型，也不引入第三方库。
   */
  private fun plausibility(text: String): Double {
    var score = 0.0
    for (ch in text) {
      val code = ch.code
      score +=
        when {
          ch == '\uFFFD' -> -20.0
          ch in COMMON_CJK -> 3.0
          code in 0x4E00..0x9FFF -> -0.7 // 生僻汉字：解错的典型落点
          code in 0x3400..0x4DBF -> -1.2 // 扩展 A：正常字幕几乎不会用
          code in 0x3040..0x30FF -> 2.5 // 假名
          code in 0xAC00..0xD7AF -> 2.5 // 谚文
          code in 0x3000..0x303F -> 1.5 // 中日标点
          code in 0xFF61..0xFF9F -> -1.5 // 半角片假名：真实字幕几乎不用，是 Shift_JIS 误判的典型残留
          code in 0xFF00..0xFFEF -> 0.5 // 全角符号
          code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> -15.0 // NUL 等控制符
          code in 0x7F..0x9F -> -10.0 // C1 控制符
          code in 0xE000..0xF8FF -> -12.0 // 私用区
          code in 0xA0..0x2FF -> -0.3 // Latin-1 扩展：mojibake 常见残留
          else -> 0.0
        }
    }
    return score
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
