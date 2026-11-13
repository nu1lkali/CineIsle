package app.marlboroadvance.mpvex.ui.player.subtitle

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.ConfidenceLevel
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MatchLogger
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MatchReason
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SubtitleMatchResult
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MediaQuery
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SmartSubtitleMatcher
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.SubtitleCache
import app.marlboroadvance.mpvex.utils.media.SubtitleFileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字幕保存目录里的一条候选字幕。
 *
 * @property name 文件名（展示用）
 * @property relativePath 相对字幕目录的路径（日志与去重用）
 * @property uri 可交给播放器的 `content://` Uri
 * @property score 0..100 的综合分（已应用冲突上限）
 * @property reason 主依据，UI 映射成一句人话
 * @property confidence 置信度等级
 * @property canAutoMatch 是否满足全部自动采用条件。UI 据此打「可直接使用」角标，
 *   但**不会**自动注入轨道 —— 是否挂载始终由用户点选决定
 * @property codeText 这条字幕解析出的主编号原文（`n0907`）。展示与「确认别名」用
 * @property verified 时间轴与片长吻合（见 [SubtitleTimingProbe]）。只是更强的佐证，不是入选条件
 */
data class LocalSubtitleCandidate(
  val name: String,
  val relativePath: String,
  val uri: String,
  val score: Double,
  val reason: MatchReason,
  val confidence: ConfidenceLevel,
  val canAutoMatch: Boolean,
  val sizeBytes: Long,
  val codeText: String? = null,
  val verified: Boolean = false,
)

/**
 * 扫描结果。
 *
 * [scanning] / [attempted] / [hits] 三者分开而不是用可空表示，是因为 UI 要区分四种状态：
 * 「没条件、压根没扫」/「还在扫」（什么都不画）/「扫完没命中」（给一句提示）/「扫完有命中」（列出来）。
 * 只用 null 区分会分不清前两者，面板一打开就会闪一下「没有匹配」。
 *
 * @property queryCodeText 片名解析出的主编号原文。与 [LocalSubtitleCandidate.codeText]
 *   一起构成「确认别名」的那一对，UI 只在系统自己也不确定时才展示
 * @property attempted 是否真的扫过（目录没配、没有可用条件时都是 false）
 */
data class LocalSubtitleScan(
  val scanning: Boolean = false,
  val attempted: Boolean = false,
  val hits: List<LocalSubtitleCandidate> = emptyList(),
  val queryCodeText: String? = null,
)

/**
 * 在「字幕保存目录」（设置 → 字幕 → 字幕保存目录，SAF tree Uri）里找与当前片子相关的字幕。
 *
 * ## 为什么分两段
 *
 * 第一段只枚举文件名（[MAX_FILES] 内），第二段把整批名字一次交给
 * [SmartSubtitleMatcher]。后者内部有预处理缓存（[app.marlboroadvance.mpvex.ui.player.subtitle.smart.SubtitleCache]），
 * 重复打开面板时只有第一次要跑正则。
 *
 * 逐个文件调 `match` 是老写法：那样每个文件都要重新归一化查询，
 * 而且拿不到「Top1 与 Top2 差多少」这个全局信息 —— 而这个差值恰恰是
 * 「能不能自动采用」的必要条件之一。
 *
 * ## 预算
 *
 * SAF 的 `listFiles()` 每层都是一次跨进程查询，在大目录上很慢。所以三条硬闸：
 * 深度 ≤ [MAX_DEPTH]、目录数 ≤ [MAX_DIRS]、文件数 ≤ [MAX_FILES]，
 * 再加一个 [TIME_BUDGET_MS] 的墙钟预算。**超时是正常结果**（返回已扫到的部分），
 * 绝不把「扫得慢」暴露成「没有字幕」以外的错误。
 */
object LocalSubtitleScanner {
  private const val TAG = "LocalSubtitleScanner"

  /** 用户一般把字幕放在「年/番号/」或「番号/」这种层级里，3 层够用。 */
  private const val MAX_DEPTH = 3
  private const val MAX_DIRS = 200
  private const val MAX_FILES = 800
  private const val TIME_BUDGET_MS = 2_500L

  /** 最多列几条。多了会把真正的轨道列表挤下去，反而更难找。 */
  const val MAX_RESULTS = 5

  /** 最多探测几条的时间轴。比 [MAX_RESULTS] 多一条，好让「略低分但时长吻合」的那条有机会上来。 */
  private const val MAX_PROBE = 6

  /**
   * 时间轴吻合的判定窗口。
   *
   * 片尾曲通常没有字幕，末条比片长短一两分钟是**正常**的，所以窗口给到 2 分钟；
   * 再宽就没有区分度了。
   */
  private const val TIMING_TOLERANCE_MS = 120_000L

  suspend fun scan(
    context: Context,
    treeUri: String,
    query: MediaQuery,
    durationMs: Long? = null,
  ): LocalSubtitleScan {
    if (treeUri.isBlank() || query.isEmpty) return LocalSubtitleScan()
    return withContext(Dispatchers.IO) {
      try {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null || !root.isDirectory || !root.canRead()) {
          Log.w(TAG, "subtitle folder unavailable: $treeUri")
          return@withContext LocalSubtitleScan()
        }

        val found = mutableListOf<Entry>()
        val queue = ArrayDeque<DirNode>()
        queue.addLast(DirNode(root, 0, ""))
        var dirs = 0
        var files = 0
        val deadline = SystemClock.elapsedRealtime() + TIME_BUDGET_MS

        while (queue.isNotEmpty()) {
          if (dirs >= MAX_DIRS || files >= MAX_FILES) break
          if (SystemClock.elapsedRealtime() > deadline) break

          val node = queue.removeFirst()
          dirs++
          val children =
            try {
              node.dir.listFiles()
            } catch (t: Throwable) {
              Log.w(TAG, "listFiles failed at ${node.prefix}: ${t.message}")
              continue
            }

          for (child in children) {
            if (SystemClock.elapsedRealtime() > deadline) break
            val name = child.name ?: continue
            if (name.startsWith(".")) continue

            if (child.isDirectory) {
              if (node.depth + 1 < MAX_DEPTH) {
                queue.addLast(DirNode(child, node.depth + 1, node.prefix + name + "/"))
              }
              continue
            }

            if (!isSubtitleFile(name)) continue
            files++
            found +=
              Entry(
                name = name,
                relativePath = node.prefix + name,
                uri = child.uri.toString(),
                sizeBytes = child.length(),
              )
          }
        }

        if (found.isEmpty()) return@withContext LocalSubtitleScan(attempted = true)

        val results = SmartSubtitleMatcher.match(query, found.map { it.relativePath })
        Log.d(TAG, MatchLogger.describe(query.label.orEmpty(), query.facts, results, MATCH_CONFIG))
        if (results.isEmpty()) return@withContext LocalSubtitleScan(attempted = true)

        val byPath = found.associateBy { it.relativePath }
        val preferred = SubtitlePickMemory.recall(SubtitlePickMemory.keyOf(query))

        val candidates =
          results
            .mapNotNull { result ->
              val entry = byPath[result.relativePath] ?: return@mapNotNull null
              result.toCandidate(entry)
            }
            .sortedWith(
              compareByDescending<LocalSubtitleCandidate> { preferred != null && it.uri == preferred }
                .thenByDescending { it.score }
                .thenBy { it.relativePath.length }
            )

        LocalSubtitleScan(
          scanning = false,
          attempted = true,
          hits = verifyTiming(context, candidates, durationMs),
          queryCodeText = query.facts.identifiers.firstOrNull()?.raw,
        )
      } catch (t: Throwable) {
        Log.w(TAG, "scan failed: ${t.message}")
        LocalSubtitleScan()
      }
    }
  }

  private val MATCH_CONFIG = app.marlboroadvance.mpvex.ui.player.subtitle.smart.MatchConfig()

  private fun SubtitleMatchResult.toCandidate(entry: Entry): LocalSubtitleCandidate =
    LocalSubtitleCandidate(
      name = entry.name,
      relativePath = entry.relativePath,
      uri = entry.uri,
      score = score,
      reason = primaryReason,
      confidence = confidenceLevel,
      canAutoMatch = canAutoMatch,
      sizeBytes = entry.sizeBytes,
      // 缓存命中，成本可忽略；只取原文形态（`n0907`）给 UI 展示
      codeText = SubtitleCache.factsOf(entry.relativePath).identifiers.firstOrNull()?.raw,
    )

  /**
   * 名字判分之后的第二道：**打开前几条，看时间轴跟片长对不对得上**。
   *
   * 这一步对应「人会怎么做」：同名同番号有好几条时，人会点开看一眼时长。
   * 只对前 [MAX_PROBE] 条做（每条最多读 1 MiB），全量读文件不可接受。
   *
   * 与 v252 的教训保持一致：时间轴**只做标记与同分排序**，绝不加减分数 ——
   * 一旦让它动分数，就会跨过冲突上限，把「番号对不上」的候选推上去。
   */
  private suspend fun verifyTiming(
    context: Context,
    ordered: List<LocalSubtitleCandidate>,
    durationMs: Long?,
  ): List<LocalSubtitleCandidate> {
    if (durationMs == null || durationMs <= 0) return ordered.take(MAX_RESULTS)

    val head = ordered.take(MAX_PROBE)
    val rest = ordered.drop(MAX_PROBE)
    val probed =
      head.map { candidate ->
        val timing = SubtitleTimingProbe.probe(context, candidate.uri) ?: return@map candidate
        candidate.copy(verified = coincides(timing.lastCueMs, durationMs))
      }
    return (probed + rest)
      .sortedWith(
        compareByDescending<LocalSubtitleCandidate> { it.score }
          .thenByDescending { it.verified }
          .thenBy { it.relativePath.length }
      )
      .take(MAX_RESULTS)
  }

  /** 末条字幕的结束时间与片长是否落在同一个区间（见 [TIMING_TOLERANCE_MS]）。 */
  internal fun coincides(lastCueMs: Long, durationMs: Long): Boolean =
    lastCueMs > 0 && durationMs > 0 && kotlin.math.abs(lastCueMs - durationMs) <= TIMING_TOLERANCE_MS

  private fun isSubtitleFile(fileName: String): Boolean = SubtitleFileTypes.isSubtitle(fileName)

  private data class DirNode(val dir: DocumentFile, val depth: Int, val prefix: String)

  private data class Entry(
    val name: String,
    val relativePath: String,
    val uri: String,
    val sizeBytes: Long,
  )
}
