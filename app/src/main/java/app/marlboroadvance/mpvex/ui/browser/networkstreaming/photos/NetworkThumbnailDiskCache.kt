package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网络照片**缩略图**的二级磁盘缓存（存压缩后的 JPEG）。
 *
 * ## 为什么需要它
 *
 * Coil 的 DiskCache 存的是**解码前的原始字节**（整张 JPEG，网络照片常见 3–8MB）。
 * 一个 1000 张的目录想全缓存要 3–8GB —— 任何合理上限都只装得下几十张，
 * 滚过去一段再滚回来必然重新下载。这就是「滚得多了缩略图就丢」的根因，
 * 单纯把上限从 128MB 调到 512MB 只是把「几十张」变成「一百多张」，治不了本。
 *
 * 这里存的是一张**已经缩到显示尺寸**的 JPEG：360px、质量 [JPEG_QUALITY]，
 * 约 30–60KB。同样 256MB 能放 **4000–8000 张**，差两个数量级。
 *
 * ## 质量取舍（用户明确要求「不要压缩太严重」）
 *
 * - **尺寸**：按请求的 `options.size` 采样解码，不会超过实际显示分辨率，
 *   所以「变小」只发生在像素数上，不发生在清晰度上 —— 屏幕上看到的就是原尺寸的图。
 * - **质量 85**：JPEG 的常规「视觉无损」档。再往下（<75）照片的渐变区
 *   （天空、肤色）会出现肉眼可见的色带，那才叫压缩太严重。
 * - 不主动降低色度采样：默认 4:2:0 对照片缩略图几乎无感，但能省约 20% 体积。
 *
 * ## 与 Coil 磁盘缓存的关系
 *
 * 两者**并存、互不干扰**：Coil 那份存原图（供全屏 `BUCKET_FULL` 用），
 * 这份存缩略图（供网格 / 列表 / 胶片条用）。命中这份时完全不走网络。
 */
object NetworkThumbnailDiskCache {
  /** 目录名（挂在应用私有 `cacheDir` 下，系统清缓存时会一并清掉，正是我们要的）。 */
  private const val DIR_NAME = "network_thumb_cache"

  /**
   * 容量上限。
   *
   * 按每张 40KB 估算，256MB ≈ **6500 张**。这个量级足以覆盖「一个目录里
   * 来回滚动看几千张图」的极端场景，再多也不会更快（用户记不住第 7000 张）。
   */
  private const val LIMIT_BYTES = 256L * 1024 * 1024

  /** 触发清理后要降到上限的多少（80%），留出缓冲避免反复触发。 */
  private const val TRIM_TARGET_RATIO = 0.8

  /** JPEG 质量。见类注释的取舍说明，**不要**为了省空间往下调。 */
  private const val JPEG_QUALITY = 85

  /**
   * 每写这么多次才检查一次容量。
   *
   * 每次写入都遍历目录（几千个 entry）不划算；32 次检查一次，
   * 平均超标幅度最多 32 × 40KB ≈ 1.3MB，可以忽略。
   */
  private const val TRIM_CHECK_EVERY = 32

  private const val TAG = "NetThumbCache"

  private val writeCounter = AtomicInteger(0)

  /** 缓存目录（确保存在）。 */
  fun dir(context: Context): File =
    File(context.cacheDir, DIR_NAME).apply {
      if (!isDirectory) mkdirs()
    }

  /**
   * key → 文件名。
   *
   * 用 MD5 而不是 `String.hashCode()`：后者 32 位、碰撞概率在几千张时已经
   * 不可忽略，一旦碰撞就会「A 的缩略图显示成 B」—— 这种错图 bug 极难排查。
   */
  fun fileName(key: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2 + 4) {
      digest.forEach { b -> append("%02x".format(b)) }
      append(".jpg")
    }
  }

  /** 命中返回文件（保证非空），否则 null。 */
  fun cachedFile(context: Context, key: String): File? {
    val file = File(dir(context), fileName(key))
    return if (file.isFile && file.length() > 0L) file else null
  }

  /**
   * 把已解码的位图压成 JPEG 存进去，返回落盘后的文件。
   *
   * ⚠️ **有磁盘 IO + 一次 JPEG 编码**，调用方必须在 IO 线程。
   * ⚠️ 已存在则直接返回（多张卡片并发命中同一张图时只写一次）。
   *
   * 原子性：先写 `.tmp` 再 `renameTo` —— 直接写目标文件的话，
   * 进程在中途被杀会留下一个**半截 JPEG**，下次命中它只会解出一半的图
   * （绿屏/灰色下半张），而且因为它「存在且非空」会一直命中，永远修不好。
   */
  fun store(context: Context, key: String, bitmap: Bitmap): File? {
    return runCatching {
      val target = File(dir(context), fileName(key))
      if (target.isFile && target.length() > 0L) return target

      // 用 createTempFile 而不是固定名 `.tmp`：同一张图可能被「网格卡」和
      // 「胶片条 / 预取」并发请求，固定名会让两个线程写同一个临时文件，
      // 互相踩数据。随机后缀保证各写各的，最后 renameTo 由最后完成者胜出。
      val tmp = File.createTempFile("${target.name}_", ".part", target.parentFile)
      FileOutputStream(tmp).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.flush()
        // 落盘再改名：否则 rename 成功但数据还在页缓存里，断电后会变成空文件
        out.fd.sync()
      }
      if (tmp.length() <= 0L) {
        tmp.delete()
        return null
      }
      if (!tmp.renameTo(target)) {
        tmp.delete()
        return null
      }
      if (writeCounter.incrementAndGet() % TRIM_CHECK_EVERY == 0) trim(context)
      target
    }.onFailure { Log.w(TAG, "写缩略图缓存失败: $key", it) }.getOrNull()
  }

  /**
   * 超限时按「最久未修改」淘汰到 [TRIM_TARGET_RATIO]。
   *
   * 用 lastModified 而不是 atime：Android 上挂载参数通常是 `noatime`，
   * atime 根本不更新。lastModified 在「命中后补一次 setLastModified」的配合下
   * 就是近似的 LRU —— 见 [touch]。
   */
  private fun trim(context: Context) {
    runCatching {
      val files = dir(context).listFiles()?.filter { it.isFile } ?: return
      var total = files.sumOf { it.length() }
      if (total <= LIMIT_BYTES) return

      val target = (LIMIT_BYTES * TRIM_TARGET_RATIO).toLong()
      files
        .filter { !it.name.endsWith(".part") }
        .sortedBy { it.lastModified() }
        .forEach { file ->
          if (total <= target) return@forEach
          val size = file.length()
          if (file.delete()) total -= size
        }
      Log.d(TAG, "缩略图缓存已清理，当前 ${total / 1024} KB")
    }.onFailure { Log.w(TAG, "清理缩略图缓存失败", it) }
  }

  /**
   * 命中时刷新修改时间，让 [trim] 的「最久未修改」更接近真正的 LRU。
   *
   * 不刷新的话，一张第一天看过、之后每天滚过十次的图，会因为 mtime 最老
   * 而优先被删掉 —— 那正是最不该删的。
   *
   * ⚠️ 成本是一次 `utimes` 系统调用，比读一次文件便宜得多，可以每次命中都做。
   */
  fun touch(file: File) {
    runCatching { file.setLastModified(System.currentTimeMillis()) }
  }

  /** 清空（设置页「清除图片缓存」用）。 */
  fun clear(context: Context) {
    runCatching {
      dir(context).listFiles()?.forEach { it.delete() }
    }.onFailure { Log.w(TAG, "清空缩略图缓存失败", it) }
  }

  /** 当前占用字节数（设置页展示用）。 */
  fun sizeBytes(context: Context): Long =
    runCatching {
      dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)
}
