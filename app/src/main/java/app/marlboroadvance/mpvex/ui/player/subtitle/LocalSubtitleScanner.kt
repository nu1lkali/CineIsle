package app.marlboroadvance.mpvex.ui.player.subtitle

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.utils.media.SubtitleFileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字幕保存目录里的一条候选字幕。
 *
 * @property name 文件名（展示用）
 * @property relativePath 相对字幕目录的路径，如 `SSIS-123/首华.zh.srt`（匹配与去重都用它）
 * @property uri 可交给播放器的 `content://` Uri
 */
data class LocalSubtitleCandidate(
  val name: String,
  val relativePath: String,
  val uri: String,
  val score: Int,
  val reason: LocalSubtitleMatch.Reason,
  val sizeBytes: Long,
)

/**
 * 扫描结果。
 *
 * [scanning] / [attempted] / [hits] 三者分开而不是用可空表示，是因为 UI 要区分四种状态：
 * 「没条件、压根没扫」/「还在扫」（什么都不画）/「扫完没命中」（给一句提示）/「扫完有命中」（列出来）。
 * 只用 null 区分会分不清前两者，面板一打开就会闪一下「没有匹配」。
 *
 * @property attempted 是否真的扫过（目录没配、没有可用条件时都是 false）
 */
data class LocalSubtitleScan(
  val scanning: Boolean = false,
  val attempted: Boolean = false,
  val hits: List<LocalSubtitleCandidate> = emptyList(),
)

/**
 * 在「字幕保存目录」（设置 → 字幕 → 字幕保存目录，SAF tree Uri）里找与当前片子相关的字幕。
 *
 * ## 为什么不全量解析字幕内容
 *
 * 代价不成比例：目录里可能有上千个文件，逐个解码文本要几秒到几十秒。
 * 这里只用**名字**（含所在子目录名）判分，命中才列出来 ——
 * 用户点一下就把字幕挂上了，判错的成本只是那一下。
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

  suspend fun scan(
    context: Context,
    treeUri: String,
    query: LocalSubtitleMatch.Query,
  ): LocalSubtitleScan {
    if (treeUri.isBlank() || query.isEmpty) return LocalSubtitleScan()
    return withContext(Dispatchers.IO) {
      try {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null || !root.isDirectory || !root.canRead()) {
          Log.w(TAG, "subtitle folder unavailable: $treeUri")
          return@withContext LocalSubtitleScan()
        }

        val found = mutableListOf<LocalSubtitleCandidate>()
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

            files++
            if (!isSubtitleFile(name)) continue
            val relative = node.prefix + name
            val hit = LocalSubtitleMatch.match(query, relative) ?: continue
            found +=
              LocalSubtitleCandidate(
                name = name,
                relativePath = relative,
                uri = child.uri.toString(),
                score = hit.score,
                reason = hit.reason,
                sizeBytes = child.length(),
              )
          }
        }

        // 排序：上次用过的那条永远第一（用户自己的选择，比任何猜测都准），
        // 其余按证据强度，同分时短路径优先（`番号/zh.srt` 比 `番号/备份/旧版/zh.srt` 更像是用户要的）
        val preferred = SubtitlePickMemory.recall(SubtitlePickMemory.keyOf(query))
        val hits =
          found
            .distinctBy { it.uri }
            .sortedWith(
              compareByDescending<LocalSubtitleCandidate> { preferred != null && it.uri == preferred }
                .thenByDescending { it.score }
                .thenBy { it.relativePath.length }
            )
            .take(MAX_RESULTS)
        LocalSubtitleScan(scanning = false, attempted = true, hits = hits)
      } catch (t: Throwable) {
        Log.w(TAG, "scan failed: ${t.message}")
        LocalSubtitleScan()
      }
    }
  }

  /**
   * 判扩展名。直接用 [SubtitleFileTypes] 的全局白名单，避免两处口径漂移
   * （这里能列出来的，`loadSubtitle` 必须也接受，否则点一下报「字幕格式不支持」）。
   */
  private fun isSubtitleFile(fileName: String): Boolean = SubtitleFileTypes.isSubtitle(fileName)

  private data class DirNode(val dir: DocumentFile, val depth: Int, val prefix: String)
}
