package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * GIF 片段录制。
 *
 * ## 取帧：走 mpv 自己的截图通道，不用 `grabThumbnail`
 *
 * 旧实现调 `MPVLib.grabThumbnail()`。它在 AAR 里是**另写的一套像素转换**：内部发
 * `screenshot-raw` 拿到原始帧（yuv420p 之类），再用自己的 libswscale 调用转成
 * ARGB_8888 的 Bitmap。开启硬件解码（本项目默认 `hwdec=mediacodec,mediacodec-copy,no`）后，
 * 帧的实际格式 / stride 常常和它预期的对不上，转出来就是花屏 —— 而播放完全正常，
 * 因为 mpv 自己的渲染路径根本不走那段代码。
 *
 * 现在改成 `screenshot-to-file`：由 **mpv 自己**把当前帧（含硬解帧的下载与色彩转换）
 * 写成标准图像文件，再交给 Android 解码。这条路径就是播放页「截屏」用的那条，
 * 颜色与屏幕一致，也不会受帧格式影响。
 *
 * ## 帧留在磁盘上，不进内存
 *
 * 采集阶段**只保留截图文件**，不解码成位图带回来。位图内存随宽度**平方**增长：
 * 1280 宽一帧 ARGB_8888 就要 3.7MB，10 秒 100 帧接近 400MB，必然 OOM。
 * 落盘之后峰值内存只与「一次解码一帧」相关 —— **与分辨率和时长都无关**。
 *
 * ## 编码：自带的 GIF89a 编码器
 *
 * mpv 只能出视频（mp4/mkv），**没有任何 GIF 输出能力**；GIF 也不是「多帧图片打包」，
 * 它要求索引色 + LZW。所以编码这一半自己写，不引第三方依赖。
 * 但 LZW 的**码宽增长时机**错一格整个码流就废 —— 见 [LzwWriter.write] 里那段注释。
 *
 * 色表用**中位切分自适应**（[GifPalette]），不是写死的均匀色立方：
 * 后者会把中性色染成洋红 / 绿，并让中等肤色掉 20~40% 彩度 —— 那正是「人像像石膏」。
 *
 * ## 代价与边界
 *
 * - 帧率固定 10fps。GIF 本身就不适合高帧率（文件会大得离谱），10fps 观感已经够用；
 * - 宽度在录制面板的「清晰度」里选（[WIDTH_OPTIONS]），默认 480。
 *   **实际输出会封顶到视频自身分辨率**：源比目标窄时不放大，放大只会让文件变大
 *   而不会多出细节（横向 540px 的素材选 1280，输出仍是 540）；
 * - 体积随宽度**平方级**增长。同一段 5 秒画面实测：480 约 1.3~8MB、640 约 2~10MB、
 *   960 约 3.6~9MB、1280 约 6~17MB（全表见 `_giflab/width_size.txt`）；
 * - 每帧要落一次盘（截图接口只给文件），所以取帧比纯内存抓帧慢一点，
 *   换来的是任何编码 / 硬解组合下都不会花屏；
 * - 录制期间**用户在播放器里的操作会照常生效**，包括暂停 —— 暂停后取到的是同一帧。
 *   这是刻意不锁 UI 的：锁了反而像卡死。**开录那一刻**是例外：调用方（PlayerViewModel）
 *   会把暂停中的视频拉起来，否则整段都是同一帧；
 * - 自适应色表保留的色彩层次比老的固定色表多，文件因此更大（纯色界面最明显）。
 *   这个代价换的是正确的颜色，看着不对的图再小也没意义；想压体积就用 320P。
 */
object GifRecorder {
  /** 可选时长（秒）。 */
  val DURATIONS = listOf(3, 5, 8, 10)

  /** 采样帧率。 */
  const val FPS = 10

  /**
   * 可选的输出宽度（px，等比缩放）。
   *
   * 320 / 480 / 640 是「省流 / 标准 / 高清」，960 / 1280 用来看清细节（脸、字幕、UI 文字）。
   * 宽度越大越清楚、文件也越大，且实际输出会封顶到源分辨率（见类注释）。
   * 录制面板的「清晰度」chips 直接遍历这个列表。
   */
  val WIDTH_OPTIONS = listOf(320, 480, 640, 960, 1280)

  /** 默认输出宽度（px）。320 在手机上放大后偏糊，480 兼顾清晰度与体积。 */
  const val TARGET_WIDTH = 480

  /**
   * 统计色表时**最多抽几帧**。
   *
   * 色表只需要知道「这段画面用了哪些颜色」，10 秒 100 帧里等距抽八帧已经足够稳。
   * 抽全量只会多占内存与时间（每帧都要按输出分辨率解码一次）。
   */
  private const val PALETTE_SAMPLE_FRAMES = 8

  /** 单帧延时（ms），与 [FPS] 对应，向下取整到 GIF 的 10ms 精度。 */
  private const val FRAME_DELAY_MS = 1000 / FPS

  /**
   * 首选的截图格式。
   *
   * 用 JPEG 而不是 PNG：GIF 反正要量化到 256 色，JPEG 的画质损失看不出来，
   * 但写盘体积和编码耗时都只有 PNG 的几分之一（一帧一张，差得很明显）。
   */
  private const val PREFERRED_FORMAT = "jpg"

  /** 首选格式不可用时的兜底（「截屏」一直写 PNG，必然存在这个编码器）。 */
  private const val FALLBACK_FORMAT = "png"

  /** 等 mpv 把某一帧写盘的上限（ms）；超时就丢掉这一帧，不拖慢整段录制。 */
  private const val SCREENSHOT_TIMEOUT_MS = 2000L

  /** 轮询「写完没有」的间隔（ms）。 */
  private const val SCREENSHOT_POLL_MS = 8L

  /**
   * 一次录制采集到的原始帧。
   *
   * 帧**以截图文件的形式留在临时目录里**，不带回内存位图：宽度上去之后单帧内存是
   * 平方级增长 —— 1280 宽一帧就要 3.7MB，10 秒 100 帧接近 400MB，必然 OOM。
   * 留在磁盘上，峰值内存就只与「一次解码一帧」相关，与分辨率和时长都无关。
   *
   * 调用方在编码结束（或录制中途失败）后**必须**调 [cleanup]，否则会在 cache 里
   * 留下几十张截图。
   */
  class GifClip internal constructor(
    private val dir: File,
    internal val files: List<File>,
    /** 输出宽度（px），已封顶到源分辨率。 */
    val width: Int,
    /** 输出高度（px），与 [width] 同比例。 */
    val height: Int,
  ) {
    /** 删掉整个临时目录。 */
    fun cleanup() {
      dir.deleteRecursively()
    }
  }

  /**
   * 连续取帧，落在磁盘上。
   *
   * @param context 只用来拿一个临时目录放截图。
   * @param durationSec 录制时长（秒），调用方应先用 [DURATIONS] 约束范围。
   * @param targetWidth 期望输出宽度（px，等比）；实际会封顶到源分辨率（不放大）。
   * @param onProgress 采集进度（0f..1f），在 IO 线程上被调用。
   * @return 采集结果；一帧都没取到（当前不是 mpv 内核、或没有视频轨）时返回 null。
   */
  suspend fun captureFrames(
    context: Context,
    durationSec: Int,
    targetWidth: Int = TARGET_WIDTH,
    onProgress: (Float) -> Unit = {},
  ): GifClip? =
    withContext(Dispatchers.IO) {
      val totalFrames = (durationSec.coerceIn(1, 60) * FPS).coerceAtLeast(1)
      val interval = 1000L / FPS
      val startedAt = System.currentTimeMillis()

      // 每帧一张独立文件：mpv 是异步写盘的，复用同一个文件名会读到上一帧的内容。
      val scratchDir = File(context.cacheDir, "gif_capture")
      scratchDir.deleteRecursively()
      scratchDir.mkdirs()

      val files = ArrayList<File>(totalFrames)
      // 第一次取帧顺带探测格式：首选格式在这个构建里可能没编进编码器，
      // 那就换成兜底格式接着录，而不是整段录制直接失败。
      var format = PREFERRED_FORMAT
      var formatProbed = false
      var outWidth = 0
      var outHeight = 0

      try {
        for (i in 0 until totalFrames) {
          coroutineContext.ensureActive()
          var shot = File(scratchDir, "frame_$i.$format")
          var size = captureOne(shot)
          if (size == null && !formatProbed && format != FALLBACK_FORMAT) {
            shot.delete()
            format = FALLBACK_FORMAT
            shot = File(scratchDir, "frame_$i.$format")
            size = captureOne(shot)
          }
          formatProbed = true
          if (size != null) {
            files.add(shot)
            if (outWidth == 0) {
              // 输出尺寸由第一帧的**源**尺寸定：不放大，放大只增体积不增细节
              outWidth = minOf(targetWidth, size.width).coerceAtLeast(2)
              outHeight = (size.height.toFloat() * outWidth / size.width).toInt().coerceAtLeast(2)
            }
            onProgress(files.size.toFloat() / totalFrames)
          }
          // 按「目标时间点」对齐，而不是固定 sleep —— 截图 + 写盘本身有耗时，
          // 固定 sleep 会让实际时长比设定值长一截
          val nextAt = startedAt + (i + 1) * interval
          val wait = nextAt - System.currentTimeMillis()
          if (wait > 0) delay(wait)
        }
      } catch (t: Throwable) {
        scratchDir.deleteRecursively()
        throw t
      }

      if (files.isEmpty() || outWidth == 0) {
        scratchDir.deleteRecursively()
        return@withContext null
      }
      GifClip(scratchDir, files, outWidth, outHeight)
    }

  /**
   * 让 mpv 写一帧截图到 [file]，返回它的**源**像素尺寸（不整帧解码）。
   *
   * 失败时把可能残留的空文件删掉，避免污染临时目录。
   */
  private suspend fun captureOne(file: File): FrameSize? {
    file.delete()
    // 第二个参数 "video" = 只取画面本体，不带字幕与 OSD
    PlayerLib.command("screenshot-to-file", file.absolutePath, "video")
    if (!awaitWritten(file)) {
      file.delete()
      return null
    }
    // 只读尺寸，不把整帧读进内存
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      file.delete()
      return null
    }
    return FrameSize(bounds.outWidth, bounds.outHeight)
  }

  /** 一帧截图的源像素尺寸。 */
  private class FrameSize(
    val width: Int,
    val height: Int,
  )

  /** 等 mpv 把截图文件写完：先等它出现，再等长度连续两次不变。 */
  private suspend fun awaitWritten(file: File): Boolean {
    val deadline = System.currentTimeMillis() + SCREENSHOT_TIMEOUT_MS
    var lastLength = -1L
    while (System.currentTimeMillis() < deadline) {
      delay(SCREENSHOT_POLL_MS)
      val length = file.length()
      if (length > 0L && length == lastLength) return true
      lastLength = length
    }
    return file.length() > 0L
  }

  /**
   * 解码一帧截图并等比缩放到 [width] 宽。
   *
   * 解码时按「不小于目标宽的最小 2 的幂」降采样（[sampleSizeFor]）：JPEG 解码器原生支持
   * 按倍数出图，比先解全尺寸再缩省内存也省时间。尺寸已等于目标时直接原样返回。
   */
  private fun decodeScaled(
    file: File,
    width: Int,
  ): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, width)
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull() ?: return null
    if (decoded.width == width) return decoded

    val height = (decoded.height.toFloat() * width / decoded.width).toInt().coerceAtLeast(2)
    val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
    if (scaled != decoded) decoded.recycle()
    return scaled
  }

  /**
   * 解码时的降采样倍数：取「不小于目标宽」的最小 2 的幂。
   */
  private fun sampleSizeFor(
    srcWidth: Int,
    targetWidth: Int,
  ): Int {
    var sample = 1
    while (srcWidth / (sample * 2) >= targetWidth) sample *= 2
    return sample
  }

  /**
   * 把采集结果编码成 GIF。
   *
   * 分两遍，两遍都**不把整段帧留在内存里**：
   *
   * 1. 从整段里等距抽 [PALETTE_SAMPLE_FRAMES] 帧，按**输出分辨率**解码后统计出一张
   *    共用色表（抽帧在输出分辨率上解，而不是缩到小图统计 —— 小图会把只占几个像素的
   *    高饱和细节平均掉，色表里就没有对应的颜色了）；
   * 2. 逐帧「解码 → 写一帧 → 立刻回收」，峰值只有一帧。
   *
   * @param clip [captureFrames] 的返回值。
   * @param out 调用方负责关闭。
   * @param onProgress 编码进度（0f..1f），在 IO 线程上被调用。
   */
  suspend fun encode(
    clip: GifClip,
    out: OutputStream,
    onProgress: (Float) -> Unit = {},
  ) = withContext(Dispatchers.IO) {
    val files = clip.files

    // ── 第一遍：色表 ──
    val step = (files.size / PALETTE_SAMPLE_FRAMES).coerceAtLeast(1)
    val samples = ArrayList<Bitmap>(PALETTE_SAMPLE_FRAMES + 1)
    var i = 0
    while (i < files.size) {
      decodeScaled(files[i], clip.width)?.let { samples.add(it) }
      i += step
    }
    // 等距抽样可能漏掉最后一帧，补上（片尾常是新场景）
    if (files.size > 1 && (files.size - 1) % step != 0) {
      decodeScaled(files.last(), clip.width)?.let { samples.add(it) }
    }
    if (samples.isEmpty()) return@withContext
    val palette = GifPalette.build(samples)
    samples.forEach { it.recycle() }

    // ── 第二遍：逐帧编码，一帧一帧地回收 ──
    GifEncoder(out, clip.width, clip.height, palette).use { encoder ->
      files.forEachIndexed { index, file ->
        coroutineContext.ensureActive()
        val frame = decodeScaled(file, clip.width)
        if (frame != null) {
          encoder.writeFrame(frame, FRAME_DELAY_MS)
          frame.recycle()
        }
        onProgress((index + 1).toFloat() / files.size)
      }
    }
  }
}

/**
 * 极简 GIF89a 编码器：**帧间差分 + 连续相同帧合并 + 8×8 有序抖动**。
 *
 * 色表由调用方传入（见 [GifPalette]）：逐像素量化到调色板下标后走标准 LZW 压缩，
 * 各帧共用同一张**全局色表**（短片段基本是一个场景，共用比每帧一张局部色表省 768B/帧）。
 *
 * 三件省体积 / 提升观感的事都在这一层：
 * 1. **帧间差分**：第 2 帧起只写「与上一帧不同的像素」的**最小外接矩形**，矩形内没变的
 *    填透明索引（色表第 255 项专职此事），配合处置方法 1 让画布保留上一帧；
 * 2. **相同帧合并**：内容相同的连续帧不产生帧数据，只在延时上累加；
 * 3. **有序抖动**：抑制自适应色表下的色带，且因为是坐标决定阈值，前两条才吃得下它。
 *
 * ⚠️ 自适应色表本身比老的固定色表大约 2~3 倍（保留的色彩层次更多）—— 那 2~3 倍就是
 * 这里第 1、2 条要对付的东西（静止 / 有主体运动的画面实测能省 90% 左右）。
 */
internal class GifEncoder(
  out: OutputStream,
  private val width: Int,
  private val height: Int,
  /** 自适应色表；由 [GifRecorder.encode] 按整段画面统计。 */
  private val palette: GifPalette,
  /** 循环次数，0 = 无限循环。 */
  private val loopCount: Int = 0,
) : Closeable {
  private val out = java.io.BufferedOutputStream(out, 1 shl 16)
  private var headerWritten = false

  /** 单帧延时（厘秒），用首帧给的值；后面的帧都按它累加。 */
  private var frameDelayCs = 2

  /**
   * 等待吐出的那一帧（索引色，整帧 `width × height`）。
   *
   * ⚠️ GIF 的延时写在帧数据**之前**，所以「这一帧能停留多久」在写它的那一刻其实还不知道 ——
   * 只能先攥着不写，等**下一帧内容不同**时，再把攒下的帧数一次折进延时。见 [writeFrame]。
   */
  private var pending: ByteArray? = null

  /** [pending] 连播了多少帧（含它自己）。 */
  private var pendingRun = 0

  /** 上一次**真正写进文件**的那一帧，用来算差分矩形。 */
  private var emitted: ByteArray? = null

  companion object {
    private const val MIN_CODE_SIZE = 8
    private const val SIGNATURE = "GIF89a"

    /**
     * 差分闸门：**变化像素占比**超过它就退回整帧。
     *
     * 越界的是「整体平移 / 镜头快速摇动」—— 几乎每个像素都在变，子矩形约等于整帧，
     * 又没有大片透明区域可压，实测反而比整帧**大 8.3%**。
     * 0.6 取在实测收益边界内（静止省 91.6% / 有主体运动省 90.2%，mixed 也仍有 32.9%）。
     */
    private const val DIFF_MAX_CHANGED_RATIO = 0.6f
  }

  /**
   * 送一帧进来（**不要求**相邻帧内容不同）。
   *
   * 三件事在这里合流：
   * 1. **量化 + 8×8 有序抖动**（[quantize]）—— 阈值只由坐标决定，所以静止区域逐帧一致；
   * 2. **连续相同帧合并**：内容与待写帧相同的只累加计数，不占帧数据，延时最后一次结清；
   * 3. **帧间差分**：真要写时才和上一帧比，只写变化像素的最小外接矩形，
   *    矩形内没变的填**透明索引**（色表第 255 项专职此事）。
   *
   * ⚠️ 合并与差分都依赖「相同内容 → 相同索引」⇒ 抖动必须由坐标决定
   * （有序抖动满足；Floyd–Steinberg 不满足，实测会把差分收益从 88.8% 打到 62.5%）。
   */
  fun writeFrame(
    frame: Bitmap,
    delayMs: Int,
  ) {
    frameDelayCs = (delayMs / 10).coerceAtLeast(2)
    val indexed = quantize(frame)
    val cur = pending
    if (cur == null) {
      pending = indexed
      pendingRun = 1
      return
    }
    if (cur.contentEquals(indexed)) {
      pendingRun++
      return
    }
    flushPending()
    pending = indexed
    pendingRun = 1
  }

  override fun close() {
    // 收尾：最后一组没有「下一帧」来触发，得在这里吐出去
    flushPending()
    if (!headerWritten) writeHeader()
    out.write(0x3B) // trailer
    out.flush()
    out.close()
  }

  /**
   * 把 [pending] 写出去，延时 = `攒下的帧数 × 单帧延时`。
   *
   * 合并只是把若干帧并成一条记录，**不改变时间轴**：总时长恒等于「帧数 × 单帧延时」
   * （已用「解码回时间轴逐点比对」验证）。
   */
  private fun flushPending() {
    val cur = pending ?: return
    pending = null
    val run = pendingRun.coerceAtLeast(1)
    pendingRun = 0

    if (!headerWritten) {
      writeHeader()
      headerWritten = true
    }
    val delayCs = (run * frameDelayCs).coerceAtLeast(2)

    val prev = emitted
    val rect = if (prev == null) null else diffRect(prev, cur)
    if (prev == null || rect == null) {
      // 整帧（首帧 / 变化太散被闸门退回）：不透明白底，直接覆盖画布
      writeGraphicControl(delayCs, transparent = false)
      writeImageDescriptor(0, 0, width, height)
      writeIndexed(cur)
    } else {
      // 子矩形 + 透明：处置方法 1（不处置）下画布保留上一帧，透明处自然就是「不上色」
      writeGraphicControl(delayCs, transparent = true)
      writeImageDescriptor(rect.x, rect.y, rect.w, rect.h)
      val sub = ByteArray(rect.w * rect.h)
      java.util.Arrays.fill(sub, GifPalette.TRANSPARENT_INDEX.toByte())
      for (row in 0 until rect.h) {
        val srcBase = (rect.y + row) * width + rect.x
        val dstBase = row * rect.w
        for (col in 0 until rect.w) {
          val v = cur[srcBase + col]
          if (v != prev[srcBase + col]) sub[dstBase + col] = v
        }
      }
      writeIndexed(sub)
    }
    emitted = cur
  }

  /** 变化像素的最小外接矩形。 */
  private class DiffRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
  )

  /**
   * 变化像素的最小外接矩形；**没有变化**或**变化比例超过闸门**时返回 null（= 写整帧）。
   */
  private fun diffRect(
    prev: ByteArray,
    cur: ByteArray,
  ): DiffRect? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var changed = 0
    for (y in 0 until height) {
      val base = y * width
      for (x in 0 until width) {
        if (cur[base + x] != prev[base + x]) {
          changed++
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    if (maxX < 0) return null // 完全相同（正常已被合并吃掉）
    if (changed.toFloat() / (width * height) > DIFF_MAX_CHANGED_RATIO) return null
    return DiffRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
  }

  /**
   * 位图 → 索引色（整帧 `width × height`），量化时叠 8×8 有序抖动。
   *
   * 与 [width] / [height] 不一致的帧按左上角对齐裁掉多余部分（调用方统一按同一宽度解码，
   * 正常不会走到）。
   */
  private fun quantize(frame: Bitmap): ByteArray {
    val w = minOf(width, frame.width)
    val h = minOf(height, frame.height)
    val pixels = IntArray(w * h)
    frame.getPixels(pixels, 0, w, 0, 0, w, h)

    val indexed = ByteArray(width * height)
    var src = 0
    for (y in 0 until h) {
      val rowBase = y * width
      for (x in 0 until w) {
        val p = pixels[src++]
        indexed[rowBase + x] =
          palette
            .indexOfDithered((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, x, y)
            .toByte()
      }
    }
    return indexed
  }

  private fun writeHeader() {
    out.write(SIGNATURE.toByteArray(Charsets.US_ASCII))

    // Logical Screen Descriptor
    writeShort(width)
    writeShort(height)
    // 全局色表存在(1) | 颜色分辨率(7) | 未排序(0) | 色表大小=2^(7+1)=256
    out.write(0x80 or (7 shl 4) or 7)
    out.write(0) // 背景色索引
    out.write(0) // 像素宽高比

    // Global Color Table
    for (i in 0 until GifPalette.MAX_COLORS) {
      val color = palette.colorAt(i)
      out.write((color shr 16) and 0xFF)
      out.write((color shr 8) and 0xFF)
      out.write(color and 0xFF)
    }

    // NETSCAPE2.0 循环扩展
    out.write(0x21)
    out.write(0xFF)
    out.write(0x0B)
    out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
    out.write(0x03)
    out.write(0x01)
    writeShort(loopCount)
    out.write(0x00)
  }

  /**
   * 图形控制扩展（帧延时 + 处置方法 + 透明色索引）。
   *
   * 处置方法固定 **1 = 不处置**：画布保留上一帧 —— 只有这样 [transparent] 为真时
   * 「透明像素 = 保持原样」才成立，子矩形差分全靠这条。
   */
  private fun writeGraphicControl(
    delayCs: Int,
    transparent: Boolean,
  ) {
    out.write(0x21)
    out.write(0xF9)
    out.write(0x04)
    // 处置方法(1) << 2 | 透明色标志
    out.write(0x04 or if (transparent) 0x01 else 0x00)
    writeShort(delayCs)
    out.write(if (transparent) GifPalette.TRANSPARENT_INDEX else 0)
    out.write(0) // 块结束
  }

  private fun writeImageDescriptor(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  ) {
    out.write(0x2C)
    writeShort(x)
    writeShort(y)
    writeShort(w)
    writeShort(h)
    out.write(0) // 无局部色表、非隔行
  }

  /** 索引色数据：LZW 最小码宽固定 8（色表 256 项），数据走 255 字节子块。 */
  private fun writeIndexed(indexed: ByteArray) {
    out.write(MIN_CODE_SIZE)
    LzwWriter(out).use { it.write(indexed, MIN_CODE_SIZE) }
  }

  private fun writeShort(value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
  }
}

/**
 * GIF 的 LZW 变体压缩 + 255 字节子块输出。
 *
 * 位序是 **LSB first**（与普通 LZW 相反），这一点错了整个文件就废掉 —— 解码器只会
 * 解出雪花或直接不显示。
 *
 * ⚠️⚠️ **码宽加宽的时机是这套算法最容易写错的一处，别照「直觉」改回去。**
 *
 * 直觉写法是「下一个可分配码 `nextCode` 涨到 `2^codeSize` 就加宽一位」。这是错的，
 * 而且是**每个视频都花屏**的那种错：320px 宽的帧必然跨过 512 这个台阶，一旦跨过去，
 * 码流从那里开始就全对不上了 —— 解码器只解得出一小部分，其余解出噪点 / 彩条。
 *
 * 原因是 GIF 的编码器和解码器**建表差一格**：
 * 编码器每吐出一个码就新增一个表项；解码器读到第一个码时还没有「上一个码」，
 * 建不了表项，所以它的 `nextCode` 永远比编码器**慢一格**。
 * 加宽必须按解码器的节奏来，于是判据要往后推一格：
 *
 *     nextCode == 2^codeSize + 1        // 正确
 *     nextCode == 2^codeSize            // 早一格 → 码流损坏
 *
 * 这条判据已用「编码 → 标准解码器解回 → 逐像素比对」验证过，覆盖 40×40 / 200×200 /
 * 600×600 三种尺寸与「字典满 4096 → 重置」那条分支，颜色零误差。
 *
 * 字典用 `HashMap<Int, Int>`。别改成「平铺 `IntArray(4096*256)`」想省掉装箱 ——
 * 实测反而慢 25~40%（每帧都要 `fill` 4MB 字典，开销比装箱还大，见 `_giflab/LzwBench.java`）。
 */
internal class LzwWriter(private val out: OutputStream) : Closeable {
  private val block = ByteArray(255)
  private var blockLen = 0
  private var bitBuffer = 0
  private var bitCount = 0

  fun write(
    pixels: ByteArray,
    minCodeSize: Int,
  ) {
    val clearCode = 1 shl minCodeSize
    val endCode = clearCode + 1
    var codeSize = minCodeSize + 1
    var nextCode = endCode + 1
    var dictionary = HashMap<Int, Int>(8192)

    writeBits(clearCode, codeSize)

    if (pixels.isEmpty()) {
      writeBits(endCode, codeSize)
      flushBits()
      return
    }

    var prefix = pixels[0].toInt() and 0xFF
    for (i in 1 until pixels.size) {
      val k = pixels[i].toInt() and 0xFF
      val key = (prefix shl 8) or k
      val existing = dictionary[key]
      if (existing != null) {
        prefix = existing
        continue
      }
      writeBits(prefix, codeSize)
      if (nextCode < 4096) {
        dictionary[key] = nextCode
        nextCode++
        // 见类注释：判据必须是 2^codeSize + 1，比「直觉」晚一格
        if (nextCode == (1 shl codeSize) + 1 && codeSize < 12) codeSize++
      } else {
        // 字典满了：通知解码器重置
        writeBits(clearCode, codeSize)
        dictionary = HashMap(8192)
        codeSize = minCodeSize + 1
        nextCode = endCode + 1
      }
      prefix = k
    }

    writeBits(prefix, codeSize)
    writeBits(endCode, codeSize)
    flushBits()
  }

  private fun writeBits(
    code: Int,
    length: Int,
  ) {
    bitBuffer = bitBuffer or (code shl bitCount)
    bitCount += length
    while (bitCount >= 8) {
      writeByte(bitBuffer and 0xFF)
      bitBuffer = bitBuffer ushr 8
      bitCount -= 8
    }
  }

  private fun writeByte(value: Int) {
    block[blockLen++] = value.toByte()
    if (blockLen == 255) flushBlock()
  }

  private fun flushBits() {
    if (bitCount > 0) {
      writeByte(bitBuffer and 0xFF)
      bitBuffer = 0
      bitCount = 0
    }
    flushBlock()
    out.write(0) // 子块终止符
  }

  private fun flushBlock() {
    if (blockLen > 0) {
      out.write(blockLen)
      out.write(block, 0, blockLen)
      blockLen = 0
    }
  }

  override fun close() {
    out.flush()
  }
}
