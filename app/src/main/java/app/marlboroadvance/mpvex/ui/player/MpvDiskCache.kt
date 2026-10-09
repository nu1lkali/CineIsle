package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import java.io.File

/**
 * mpv 落盘播放缓存的目录管理（清单 #18）。
 *
 * ## 为什么单独一个类
 *
 * mpv 侧的接入只有两行（[MPVView] 里设 `cache-dir` / `cache-persize`），
 * 但**路径怎么算、怎么清、怎么报占用**这三件事有真坑，混进 `MPVView` 会把
 * 那个已经很长的 `initOptions` 撑得更难读，而且这几件事都值得单测。
 *
 * ## 目录选在 `cacheDir` 下而不是外部存储
 *
 * - 外部存储（`getExternalStoragePublicDirectory`）在 Android 10+ 需要
 *   `MANAGE_EXTERNAL_STORAGE` 权限才能读写，而且**用户一清媒体缓存就全没了** ——
 *   播放缓存本来就是可再生的数据，被顺手清掉不算损失，但体验上会让人以为设置没生效。
 * - `cacheDir`（应用私有缓存）无需任何权限，系统在存储紧张时会自动回收，
 *   这正是「可再生的缓存」该待的地方。截图目录反而该放外部存储（用户要能取到），
 *   那部分 `MPVView` 已经单独处理了。
 *
 * ## 三个必须记住的 mpv 侧约束
 *
 * 1. **`cache-dir` 必须是绝对路径**，给相对路径 mpv 会静默不落盘（不报错，只是不缓存）。
 * 2. **目录必须先 `mkdirs()`** —— mpv 不会替你建。
 * 3. **`cache-persize`（每流落盘上限）与 `demuxer-max-bytes`（内存缓冲上限）是两回事**，
 *    调大落盘上限**不会**让内存缓冲变大。内存那一侧由 `MPVView` 按系统版本固定，
 *    不在这里改。
 */
object MpvDiskCache {
  /** 缓存根目录名。放在 `cacheDir` 下，与截图等临时文件分开。 */
  private const val DIR_NAME = "mpv_stream_cache"

  /** 缓存占用超过这个比例就触发一次清理（对 mpv 的 LRU 阈值做一点余量）。 */
  private const val CLEANUP_THRESHOLD = 0.9

  /**
   * 缓存根目录（**不保证已存在**，用之前自己 `mkdirs` 或调 [ensureDir]）。
   *
   * 放在 `externalCacheDir`：应用私有 `cacheDir` 太小（系统一般只给几十 MB，
   * 而缓存默认就是几百 MB），写满了会直接抛 `IOException`。
   * 外部应用专属缓存不需要任何权限。
   */
  fun cacheRoot(context: Context): File {
    val base = context.externalCacheDir ?: context.cacheDir
    return File(base, DIR_NAME)
  }

  /** 确保目录存在，返回它；建不出来（比如存储满）返回 null。 */
  fun ensureDir(context: Context): File? =
    runCatching {
      val dir = cacheRoot(context)
      if (dir.isDirectory || dir.mkdirs()) dir else null
    }.getOrNull()

  /** 当前占用（字节）。目录不存在时返回 0。 */
  fun sizeBytes(context: Context): Long =
    runCatching {
      val dir = cacheRoot(context)
      if (!dir.isDirectory) return@runCatching 0L
      dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

  /** 删掉整个缓存目录。返回是否真的清掉了（目录本来就不算清成功 —— 那是幂等的）。 */
  fun clear(context: Context): Boolean =
    runCatching {
      val dir = cacheRoot(context)
      !dir.exists() || dir.deleteRecursively()
    }.getOrDefault(false)

  /**
   * 开播前调用：**超限就先清一遍**。
   *
   * 为什么需要主动清：mpv 自己的 LRU 只保证「单条流不超过 `cache-persize`」，
   * **不保证总量不超过什么**。用户连着看 10 部片子，每部都还没到单流上限，
   * 总量早就把存储撑满了 —— 到那时候 mpv 写不进去，表现为「缓存开着但没效果」。
   * 所以这里按总量兜一次底。
   *
   * @return 是否执行了清理
   */
  fun trimIfNeeded(
    context: Context,
    limitMb: Int,
  ): Boolean {
    val limit = limitMb.coerceIn(MIN_SIZE_MB, MAX_SIZE_MB).toLong() * 1024L * 1024L
    val current = sizeBytes(context)
    // 当前用量已在阈值内就不动 —— 每部片子开播都扫一遍目录是白费 IO
    if (current <= (limit * CLEANUP_THRESHOLD).toLong()) return false
    clear(context)
    ensureDir(context)
    return true
  }

  const val MIN_SIZE_MB = 64
  const val MAX_SIZE_MB = 2048

  /** 默认上限（MB）。与 [app.marlboroadvance.mpvex.preferences.PlayerPreferences.diskCacheSizeMb] 一致。 */
  const val DEFAULT_SIZE_MB = 512
}
