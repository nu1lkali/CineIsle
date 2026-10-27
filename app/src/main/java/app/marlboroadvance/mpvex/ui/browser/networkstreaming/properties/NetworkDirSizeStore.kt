package app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils
import kotlinx.coroutines.CancellationException

/**
 * 一个目录的统计结果。
 *
 * @param truncated 撞到护栏提前收手（只统计了已扫到的部分）—— 必须让用户看见，
 *   否则「这个目录 2 GB」可能是假的，而用户会据此决定删不删。
 */
data class DirSizeEntry(
  val bytes: Long,
  val fileCount: Int,
  val dirCount: Int,
  val truncated: Boolean,
  val computedAtMs: Long = System.currentTimeMillis(),
) {
  val sizeText: String get() = MediaFormatUtils.formatFileSize(bytes)
}

/**
 * 目录大小统计：**递归 + 缓存**。
 *
 * ## 为什么要缓存
 *
 * 统计一个几千文件的目录要列几十上百次目录。属性面板是用来「反复看」的 ——
 * 点开看一次、合上再点开又全算一遍，网盘上每次都是一串真实请求（还可能触发限流）。
 *
 * 缓存挂在**进程**上而不是 ViewModel：ViewModel 按目录建实例（key = 连接+路径），
 * 换个目录就没了，那和不算缓存没区别。
 *
 * TTL 10 分钟：目录大小不像文件列表那样要求实时，但也不能永远信 —— 用户刚在
 * 别的地方传了东西，10 分钟内看不到变化属于可接受；写操作会**主动失效**（见 [invalidate]）。
 */
object NetworkDirSizeStore {
  private const val TAG = "NetworkDirSizeStore"

  /** 缓存有效期 */
  private const val TTL_MS = 10L * 60 * 1000

  /** 最多记多少个目录的结果（LRU） */
  private const val MAX_ENTRIES = 64

  // 三道护栏与工具箱同一口径：网盘上「算个大小」本身就是一次全扫，必须有边界
  private const val MAX_DEPTH = 12
  private const val MAX_DIRS = 600
  private const val MAX_FILES = 30_000

  private val cache =
    object : LinkedHashMap<String, DirSizeEntry>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirSizeEntry>?): Boolean =
        size > MAX_ENTRIES
    }

  private fun key(connectionId: Long, path: String): String = "$connectionId#${path.trim('/')}"

  /** 命中且未过期才算命中 */
  @Synchronized
  fun cached(
    connectionId: Long,
    path: String,
  ): DirSizeEntry? {
    val k = key(connectionId, path)
    val entry = cache[k] ?: return null
    if (System.currentTimeMillis() - entry.computedAtMs > TTL_MS) {
      cache.remove(k)
      return null
    }
    return entry
  }

  @Synchronized
  fun put(
    connectionId: Long,
    path: String,
    entry: DirSizeEntry,
  ) {
    cache[key(connectionId, path)] = entry
  }

  /**
   * 让这个连接的缓存整体失效。
   *
   * 为什么不精确到「这个目录 + 它的祖先」：一次 rename / delete / 移动会同时改变
   * **目标目录**和**源目录**的大小，两者可能完全不相干（跨目录移动），精确推导容易漏。
   * 缓存只有 64 条、重算一次最坏是一次目录扫描 —— 宁可多算，不要给错数。
   */
  @Synchronized
  fun invalidate(connectionId: Long) {
    val prefix = "$connectionId#"
    cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
  }

  /**
   * 递归统计 [path] 下所有文件的总字节数。
   *
   * 单目录列失败算「这一支跳过」而不是整轮失败 —— 与工具箱同一纪律：网盘偶发 403 很常见，
   * 为它把整个结果作废，用户看到的是「算不出来」。
   */
  suspend fun compute(
    connectionId: Long,
    path: String,
    list: suspend (String) -> List<NetworkFile>,
    onPhase: (String) -> Unit = {},
  ): DirSizeEntry {
    var bytes = 0L
    var files = 0
    var dirs = 0
    var truncated = false

    suspend fun walk(
      dir: String,
      depth: Int,
    ) {
      if (depth > MAX_DEPTH || dirs >= MAX_DIRS) {
        truncated = true
        return
      }
      dirs++
      onPhase(dir)
      val entries =
        try {
          list(dir)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "目录列不出来，跳过：$dir | ${e.message}")
          return
        }
      for (entry in entries) {
        if (entry.isDirectory) {
          walk(joinPath(dir, entry.name), depth + 1)
        } else {
          if (files >= MAX_FILES) {
            truncated = true
            return
          }
          files++
          bytes += entry.size.coerceAtLeast(0)
        }
      }
    }

    walk(path.trim('/'), 0)
    val entry = DirSizeEntry(bytes = bytes, fileCount = files, dirCount = dirs, truncated = truncated)
    put(connectionId, path, entry)
    return entry
  }

  /** `dir` + `name`（`dir` 为空 = 连接根） */
  private fun joinPath(
    dir: String,
    name: String,
  ): String {
    val left = dir.trim('/')
    return if (left.isEmpty()) name else "$left/$name"
  }
}
