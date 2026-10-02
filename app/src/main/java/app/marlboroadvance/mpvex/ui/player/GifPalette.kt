package app.marlboroadvance.mpvex.ui.player

import android.graphics.Bitmap

/**
 * GIF 用的**自适应调色板**（中位切分 / median cut）与配套的最近色查找表。
 *
 * ## 为什么不再用固定调色板
 *
 * 早先用的是 6×7×6 = 252 色的**固定**调色板（R/B 分 6 级、G 分 7 级）。它有两个
 * 系统性缺陷，实测（480px 宽的真实画面，见 `_giflab/`）：
 *
 * 1. **中性色被染色。** R/B 步长 51、G 步长 42，两者格点对不齐，于是凡接近灰的像素
 *    都会被推向「R=B>G」或「R=B<G」，也就是**洋红 / 绿**：
 *    灰 128 → (153,126,153)，灰 24 → (0,42,0)，单通道最大偏差达 **45 级**。
 *    山体、暗部、灰墙、阴影里的皮肤都会出现大片洋红/绿斑。
 * 2. **中等肤色掉彩度。** 肤色色卡实测平均 ΔE 13.5（最大 23.3），彩度偏差在
 *    −41% ~ +37% 之间乱跳 —— 掉彩度的那一档看起来发灰发白，就是「人像像石膏」的来源。
 *
 * 换成按**画面实际用色**分配色位的中位切分后，同一批样本的 ΔE 降到 1.2~2.4，
 * 肤色区域 ΔE 2.3~6.2，灰阶偏色归零。
 *
 * ## 实现要点
 *
 * - 直方图按 **5bit/通道（32768 格）** 统计，格内累加 8bit 原始和，代表色取均值；
 * - 切分策略：每次挑 `像素数 × 最长边` 最大的箱切，兼顾「面积大」与「色域宽」，
 *     人像这种大面积且色调连续的区块能分到更多色位；
 * - 排序用 **5bit 计数排序**（键只有 0..31），比通用比较排序快一个量级；
 * - 映射走 32768 格查找表，逐像素 O(1)。
 */
internal class GifPalette(
  /** 256 个颜色，0xRRGGBB；不足 256 时尾部补黑。**下标 255 留给透明**，不用来放颜色。 */
  val colors: IntArray,
  /** 32768（=32³）格的最近色查找表，值 = [colors] 的下标（恒定 < [OPAQUE_COLORS]）。 */
  private val lut: IntArray,
  /**
   * 抖动幅度（0..255 的 RGB 尺度）= 调色板**最近邻色距的中位数**。
   *
   * 取中位数而不是平均值：色表里既有挨得很近的颜色（一大片渐变分到几十个色位），
   * 也有离得很远的（暗部 / 高光只分到一个色位）；平均值会被前者的极小值拉塌，
   * 结果抖动几乎不起作用。中位数代表的才是「典型的一步有多大」。
   *
   * 为 0 表示不抖动（空色表等退化情况）。
   */
  val ditherSpread: Float,
) {
  /** 8bit RGB → 调色板下标。与 [build] 里建表时的口径一致（不抖动）。 */
  fun indexOf(
    r: Int,
    g: Int,
    b: Int,
  ): Int = lookup(r, g, b)

  /**
   * 带 **8×8 有序抖动（Bayer）** 的取色：[x] / [y] 是像素在帧内的坐标。
   *
   * **为什么不抖**：自适应色表下画面依然会出现**色带** —— 平滑渐变（天空、暗部、
   * 肤色过渡）被量化成几级平台，实测平台平均长度是源图的 **2.96 倍**，肉眼就是一圈圈台阶。
   *
   * **为什么用「有序」而不是 Floyd–Steinberg**：
   * 1. 色带抑制更好（平台长度比 **0.71** vs FS 的 0.93，1.0 = 与源图一致）；
   * 2. 体积更小（FS 把误差扩散得到处都是噪声）；
   * 3. ⭐ **阈值只由坐标决定 ⇒ 静止区域的输出逐帧完全一致**，帧间差分才吃得下它
   *    （实测差分收益 88.8%，FS 只剩 62.5%）。
   */
  fun indexOfDithered(
    r: Int,
    g: Int,
    b: Int,
    x: Int,
    y: Int,
  ): Int {
    val spread = ditherSpread
    if (spread <= 0f) return lookup(r, g, b)
    // 矩阵值 0..63 → 阈值 (v+0.5)/64 ∈ (0,1) → 平移到 −0.5..+0.5 = 该像素的偏置
    val t = (BAYER8[((y and 7) shl 3) or (x and 7)] + 0.5f) / 64f - 0.5f
    val off = (t * spread).toInt()
    return lookup(
      (r + off).coerceIn(0, 255),
      (g + off).coerceIn(0, 255),
      (b + off).coerceIn(0, 255),
    )
  }

  /**
   * 32768 格查找表的裸查询。
   *
   * ⚠️ 输入必须已在 0..255：`shr 3` 对负数或超过 255 的值会算出越界下标（数组越界崩溃）。
   * [indexOfDithered] 叠了偏置，所以那里必须先 `coerceIn`。
   */
  private fun lookup(
    r: Int,
    g: Int,
    b: Int,
  ): Int = lut[((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)]

  /** 取调色板颜色（0xRRGGBB）。 */
  fun colorAt(index: Int): Int = colors[index]

  companion object {
    /** 直方图每通道位数：5bit = 32 级，共 32768 格。 */
    private const val BITS = 5
    private const val LEVELS = 1 shl BITS
    private const val BINS = LEVELS * LEVELS * LEVELS

    /** GIF 单张色表上限（也决定色表大小字段 = 256 项）。 */
    const val MAX_COLORS = 256

    /**
     * 留给**透明**的色表槽位。
     *
     * 帧间差分靠「透明像素 = 保持上一帧」把整帧缩成一个子矩形，所以 256 项里必须留一项
     * 给透明，实际颜色只有 255 项。少这一项对观感没有影响，但**少了它差分就没法做**
     * （透明索引必须落在色表范围内，且 LZW 的最小码宽固定 8）。
     */
    const val TRANSPARENT_INDEX = MAX_COLORS - 1

    /** 真正用来表示颜色的项数。 */
    const val OPAQUE_COLORS = MAX_COLORS - 1

    /** 采样步长：每 N 个像素取一个进直方图。统计量不需要全量，2 已经足够稳。 */
    private const val DEFAULT_SAMPLE_STEP = 2

    /**
     * 用整段片段统计出一张共用调色板。
     *
     * 短视频通常一个场景，共用色表比「每帧一张局部色表」文件更小、实现更简单，
     * 而观感差别可以忽略（局部色表每帧多 768 字节，10 秒 100 帧就是 77KB）。
     */
    fun build(
      frames: List<Bitmap>,
      sampleStep: Int = DEFAULT_SAMPLE_STEP,
    ): GifPalette {
      val step = sampleStep.coerceAtLeast(1)
      val counts = IntArray(BINS)
      val sumR = LongArray(BINS)
      val sumG = LongArray(BINS)
      val sumB = LongArray(BINS)

      for (frame in frames) {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) continue
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        var i = 0
        while (i < pixels.size) {
          val p = pixels[i]
          val r = (p shr 16) and 0xFF
          val g = (p shr 8) and 0xFF
          val b = p and 0xFF
          val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
          counts[bin]++
          sumR[bin] += r.toLong()
          sumG[bin] += g.toLong()
          sumB[bin] += b.toLong()
          i += step
        }
      }

      // 收拢非空格，后面所有排序 / 统计都只在这份紧凑数组上做
      var n = 0
      for (bin in 0 until BINS) if (counts[bin] > 0) n++
      if (n == 0) {
        // 一帧都没采到样本（空图 / 全透明）：全黑调色板兜底，LUT 全指 0
        return GifPalette(IntArray(MAX_COLORS), IntArray(BINS), 0f)
      }

      val binCount = LongArray(n)
      val binSumR = LongArray(n)
      val binSumG = LongArray(n)
      val binSumB = LongArray(n)
      val binR = IntArray(n)
      val binG = IntArray(n)
      val binB = IntArray(n)
      var k = 0
      for (bin in 0 until BINS) {
        if (counts[bin] == 0) continue
        binCount[k] = counts[bin].toLong()
        binSumR[k] = sumR[bin]
        binSumG[k] = sumG[bin]
        binSumB[k] = sumB[bin]
        binR[k] = (bin shr 10) and 31
        binG[k] = (bin shr 5) and 31
        binB[k] = bin and 31
        k++
      }

      val order = IntArray(n) { it }
      val boxes = ArrayList<Box>(OPAQUE_COLORS)

      fun makeBox(
        start: Int,
        end: Int,
      ): Box {
        var count = 0L
        var rMin = 31
        var rMax = 0
        var gMin = 31
        var gMax = 0
        var bMin = 31
        var bMax = 0
        for (i in start until end) {
          val bi = order[i]
          count += binCount[bi]
          val r = binR[bi]
          val g = binG[bi]
          val b = binB[bi]
          if (r < rMin) rMin = r
          if (r > rMax) rMax = r
          if (g < gMin) gMin = g
          if (g > gMax) gMax = g
          if (b < bMin) bMin = b
          if (b > bMax) bMax = b
        }
        return Box(start, end, count, rMin, rMax, gMin, gMax, bMin, bMax)
      }

      boxes.add(makeBox(0, n))

      // ── 反复切分，直到 256 个箱或没有可切的箱 ──
      val sortTmp = IntArray(n)
      val hist = IntArray(LEVELS)
      while (boxes.size < OPAQUE_COLORS) {
        var bestIndex = -1
        var bestPriority = -1L
        for (i in boxes.indices) {
          val bx = boxes[i]
          if (bx.end - bx.start < 2) continue
          val side = maxOf(bx.rMax - bx.rMin, bx.gMax - bx.gMin, bx.bMax - bx.bMin)
          if (side <= 0) continue
          val priority = bx.count * side
          if (priority > bestPriority) {
            bestPriority = priority
            bestIndex = i
          }
        }
        if (bestIndex < 0) break

        val bx = boxes[bestIndex]
        val start = bx.start
        val end = bx.end
        val rSpan = bx.rMax - bx.rMin
        val gSpan = bx.gMax - bx.gMin
        val bSpan = bx.bMax - bx.bMin
        val key =
          when {
            rSpan >= gSpan && rSpan >= bSpan -> binR
            gSpan >= bSpan -> binG
            else -> binB
          }

        // 5bit 键 → 计数排序（稳定），等价于「按该轴排序」。
        //
        // ⚠️⚠️ 这里全程用**绝对下标**：[hist] 的初值是 `start` 而不是 0，所以排序结果落在
        // `sortTmp[start until end]`，回填时必须 `arraycopy(srcPos = start)`。
        // 曾经写成 `srcPos = 0`：第一次切分（start == 0）恰好正确，之后每一次都从
        // `sortTmp` 的**上一轮残留**里拷数据，把 `order` 搅成大量重复项 —— 调色板随之
        // 塌缩到十几个颜色，画面被压成几块色斑（实测前 8 色占 99.8% 像素，MAE 105/255）。
        // 这条口径已用「逐行 Python 复刻 → 与 numpy 参考实现比对」验证：修正后两者
        // 在真实图片上的逐像素平均差仅 0.03~0.05（差异只来自中位切点的取整）。
        java.util.Arrays.fill(hist, 0)
        for (i in start until end) hist[key[order[i]]]++
        var acc = start
        for (l in 0 until LEVELS) {
          val h = hist[l]
          hist[l] = acc
          acc += h
        }
        for (i in start until end) {
          val bi = order[i]
          sortTmp[hist[key[bi]]++] = bi
        }
        System.arraycopy(sortTmp, start, order, start, end - start)

        // 按累计像素数取中位，切点两侧都必须非空
        val half = (bx.count + 1) / 2
        var cumulative = 0L
        var splitLen = 1
        for (j in start until end - 1) {
          cumulative += binCount[order[j]]
          splitLen = j - start + 1
          if (cumulative >= half) break
        }
        if (splitLen > end - start - 1) splitLen = end - start - 1
        if (splitLen < 1) splitLen = 1

        boxes[bestIndex] = makeBox(start, start + splitLen)
        boxes.add(makeBox(start + splitLen, end))
      }

      // ── 每个箱的加权平均色 = 调色板一项；像素多的箱排前面 ──
      val sorted = boxes.sortedByDescending { it.count }
      val colors = IntArray(MAX_COLORS)
      var written = 0
      for (bx in sorted) {
        var total = 0L
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (i in bx.start until bx.end) {
          val bi = order[i]
          total += binCount[bi]
          sr += binSumR[bi]
          sg += binSumG[bi]
          sb += binSumB[bi]
        }
        if (total <= 0L) continue
        val r = ((sr + total / 2) / total).toInt().coerceIn(0, 255)
        val g = ((sg + total / 2) / total).toInt().coerceIn(0, 255)
        val b = ((sb + total / 2) / total).toInt().coerceIn(0, 255)
        colors[written++] = (r shl 16) or (g shl 8) or b
        if (written == OPAQUE_COLORS) break
      }

      // ── 32768 格最近色查找表 ──
      // 非空格用「格内平均色」作查询色（比格中心准），空格退回格中心。
      val lut = IntArray(BINS)
      for (bin in 0 until BINS) {
        val c = counts[bin]
        val qr: Int
        val qg: Int
        val qb: Int
        if (c > 0) {
          qr = ((sumR[bin] + c / 2) / c).toInt().coerceIn(0, 255)
          qg = ((sumG[bin] + c / 2) / c).toInt().coerceIn(0, 255)
          qb = ((sumB[bin] + c / 2) / c).toInt().coerceIn(0, 255)
        } else {
          qr = ((bin shr 10) and 31) * 8 + 4
          qg = ((bin shr 5) and 31) * 8 + 4
          qb = (bin and 31) * 8 + 4
        }
        var best = Int.MAX_VALUE
        var bestIndex = 0
        for (i in 0 until written) {
          val pc = colors[i]
          val dr = qr - ((pc shr 16) and 0xFF)
          val dg = qg - ((pc shr 8) and 0xFF)
          val db = qb - (pc and 0xFF)
          val d = dr * dr + dg * dg + db * db
          if (d < best) {
            best = d
            bestIndex = i
          }
        }
        lut[bin] = bestIndex
      }

      return GifPalette(colors, lut, spreadOf(colors, written))
    }

    /**
     * 8×8 Bayer 有序抖动矩阵（标准排列，值 0..63，行优先）。
     *
     * 阈值只由坐标决定 —— 这正是「静止区域逐帧一致、帧间差分吃得下」的原因。
     */
    private val BAYER8 =
      intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
      )

    /**
     * 抖动幅度 = 各颜色**到最近邻的距离**的中位数（RGB 欧氏）。
     *
     * 用「最近邻距离」而不是「平均间距」：色表是按画面用色分布的，同一个色表里
     * 既有挤在一起的（渐变区）也有孤零零的（高光/暗部）；平均值会被极端值带偏，
     * 中位数才对得上「典型的一步有多大」。
     */
    private fun spreadOf(
      colors: IntArray,
      count: Int,
    ): Float {
      if (count < 2) return 0f
      val nearest = IntArray(count)
      for (i in 0 until count) {
        val ci = colors[i]
        val r = (ci shr 16) and 0xFF
        val g = (ci shr 8) and 0xFF
        val b = ci and 0xFF
        var best = Int.MAX_VALUE
        for (j in 0 until count) {
          if (i == j) continue
          val cj = colors[j]
          val dr = r - ((cj shr 16) and 0xFF)
          val dg = g - ((cj shr 8) and 0xFF)
          val db = b - (cj and 0xFF)
          val d = dr * dr + dg * dg + db * db
          if (d < best) best = d
        }
        nearest[i] = best
      }
      nearest.sort()
      return kotlin.math.sqrt(nearest[count / 2].toFloat())
    }

    /** 一个色箱：指向 [order] 的一段，外加该段的像素数与各通道 5bit 范围。 */
    private class Box(
      val start: Int,
      val end: Int,
      val count: Long,
      val rMin: Int,
      val rMax: Int,
      val gMin: Int,
      val gMax: Int,
      val bMin: Int,
      val bMax: Int,
    )
  }
}
