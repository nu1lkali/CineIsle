package app.marlboroadvance.mpvex.ui.player.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一条字幕的时间轴概况。
 *
 * @property lastCueMs 最后一条字幕的结束时间（毫秒）。SRT/VTT 取结束时间，ASS 取行尾时间
 * @property cueCount 条目数。SRT 数 `-->`，ASS 数 `Dialogue:`
 */
data class SubtitleTiming(val lastCueMs: Long, val cueCount: Int)

/**
 * 从字幕文件里读出「它覆盖了多长时间」。
 *
 * ## 为什么需要（以及为什么只当**佐证**）
 *
 * 只靠文件名判定的天花板就在这里：同一部片子的中文字幕往往有好几条
 * （不同字幕组、不同版本、`CD1`/`CD2` 分段、机器识别重跑），文件名一模一样，
 * 光看名字**根本无法区分**。人会怎么做？——点开看一眼时长对不对。
 *
 * 但时长**不能当判据**（这一点在 v252 已经踩过）：片尾曲常没有字幕、字幕组招新广告会
 * 把末条推到片长之外、分段字幕只覆盖一半、加长版差几分钟、帧率漂移线性偏移。
 * 所以这里只输出一个「吻合 / 不吻合」的**弱信号**，由
 * [LocalSubtitleScanner.timingDelta] 折算成**小于一个档位**的加减分：
 * 它能在同一档内改变先后，永远不能把一条证据不足的字幕抬到证据充分的字幕之前。
 *
 * ## 为什么读整个文件却不怕慢
 *
 * 只对**排序前几名**做（[LocalSubtitleScanner.MAX_PROBE] 条），而且每个文件最多读
 * [MAX_BYTES]；SRT/ASS 通常几十到几百 KB。判分阶段仍然只看名字，全量探测才不可接受。
 */
object SubtitleTimingProbe {
  private const val TAG = "SubtitleTimingProbe"

  /** 单个文件最多读这么多。再大的 ASS（带特效）也够拿到末条时间。 */
  const val MAX_BYTES = 1 shl 20

  /** `00:01:23,456`（SRT/VTT）与 `0:01:23.45`（ASS）两种写法一起收。 */
  private val TIME_REGEX = Regex("""(\d{1,3}):(\d{2}):(\d{2})[.,](\d{2,3})""")

  /** SRT/VTT 每条字幕一行时间轴两端，`-->` 的条数就是字幕条数。 */
  private val SRT_ARROW_REGEX = Regex("""-->""")

  /** ASS/SSA 每条字幕一行 `Dialogue:`。 */
  private val ASS_DIALOGUE_REGEX = Regex("""(?m)^\s*Dialogue:""")

  /**
   * 纯函数：从字幕文本里取时间轴。认不出任何时间戳时返回 null（不是「时长 0」）。
   */
  fun parse(text: String): SubtitleTiming? {
    if (text.isEmpty()) return null
    var last = 0L
    var count = 0
    for (match in TIME_REGEX.findAll(text)) {
      val hours = match.groupValues[1].toLongOrNull() ?: continue
      val minutes = match.groupValues[2].toLongOrNull() ?: continue
      val seconds = match.groupValues[3].toLongOrNull() ?: continue
      val fraction = match.groupValues[4]
      // 两位小数是百分秒（ASS），三位是毫秒（SRT）
      val millis =
        when (fraction.length) {
          1 -> fraction.toLong() * 100
          2 -> fraction.toLong() * 10
          else -> fraction.toLong()
        }
      val total = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
      if (total > last) last = total
      count++
    }
    if (count == 0 || last <= 0) return null

    val cues =
      when {
        SRT_ARROW_REGEX.containsMatchIn(text) -> SRT_ARROW_REGEX.findAll(text).count()
        ASS_DIALOGUE_REGEX.containsMatchIn(text) -> ASS_DIALOGUE_REGEX.findAll(text).count()
        // 认不出格式时按「每条两个时间戳」估
        else -> count / 2
      }
    return SubtitleTiming(lastCueMs = last, cueCount = cues)
  }

  /**
   * 读一条字幕文件的时间轴。任何异常（文件被删、权限丢了、编码乱七八糟）都返回 null ——
   * 这只是排序佐证，失败就退回「只看名字」的结果，绝不因为它失败而少一条候选。
   */
  suspend fun probe(context: Context, uriString: String): SubtitleTiming? =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
          parse(decode(readAtMost(input, MAX_BYTES)))
        }
      }
        .onFailure { Log.d(TAG, "probe failed: ${uriString.takeLast(40)} (${it.message})") }
        .getOrNull()
    }

  private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
      val read = input.read(buffer, offset, limit - offset)
      if (read <= 0) break
      offset += read
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
  }

  /**
   * 解码。
   *
   * 只为了拿到 ASCII 的时间戳，所以没有 BOM 时按 ISO-8859-1 逐字节映射：
   * GBK / Big5 的中文会变成乱码，但数字与冒号永远是原样 —— 这正是我们要的部分，
   * 而且不用先猜编码（猜错会把时间戳一起弄坏）。
   */
  private fun decode(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte()
    ) {
      return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    return String(bytes, Charsets.ISO_8859_1)
  }
}
