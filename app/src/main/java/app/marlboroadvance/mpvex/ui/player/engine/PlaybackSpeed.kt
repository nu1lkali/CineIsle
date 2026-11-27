package app.marlboroadvance.mpvex.ui.player.engine

import java.util.Locale
import kotlin.math.round

/**
 * 播放倍速的**单一真源**：范围、精度、横滑映射、显示格式全部收口在这里。
 *
 * ## 为什么需要它
 *
 * 倍速此前散在四处各写各的：`GestureHandler` 长按横滑用硬编码 preset 索引跳档
 * （步长不均匀、上限写死 4.0），`SpeedControlSlider` 用 0.05 步进且轨道按索引等距，
 * `PlaybackSpeedSheet` 的 Slider 是 0.1..4.0 + 0.05 snap，`PlayerControls` 兜底又是
 * `Math.round(raw * 20) / 20f`。口径不一，才有了「步长固定、精度上不去、到不了 6x」
 * 的反馈。现在所有入口都经本对象量化，保证**同一个数值走到哪都是同一个值**。
 *
 * ## 口径
 *
 * - 范围 `0.1..6.0`（[MIN] / [MAX]），精度 `0.1`（[STEP]）。
 * - [quantize] 是唯一的「合法化」入口：四舍五入到 0.1 后夹到边界，NaN 归 [MIN]。
 * - [fromSwipe] 是横滑的**连续线性映射**：起点速度 + 相对位移比例 × 跨度 × 灵敏度，
 *   再量化。不再有 preset 索引，所以 0.1 与 6.0 之间每一格都是等宽的 0.1，
 *   4.0~6.0 只是轨道右侧自然的 1/3，不会挤成一团。
 * - [display] 恒显示一位小数（`1.0` 而不是 `1`、`1.25` 量化后显示 `1.3`），
 *   Locale.US 保证小数点不受系统语言影响。
 */
object PlaybackSpeed {
  /** 最低倍速。 */
  const val MIN: Float = 0.1f

  /** 最高倍速。 */
  const val MAX: Float = 6.0f

  /** 量化步长（精度）。 */
  const val STEP: Float = 0.1f

  /** 可调跨度：`MAX - MIN = 5.9`，横滑映射的分母。 */
  const val SPAN: Float = MAX - MIN

  /**
   * 把任意浮点速度合法化到 `0.1..6.0`，精度 0.1。
   *
   * 所有写 `PlayerLib.setPropertyFloat("speed", ...)` 的入口都应先过这里，
   * 防止浮点累积误差（`0.1f + 0.2f = 0.30000001f` 之类）泄漏到 UI 和 mpv。
   */
  fun quantize(value: Float): Float {
    if (value.isNaN()) return MIN
    val stepped = round(value / STEP) * STEP
    return when {
      stepped < MIN -> MIN
      stepped > MAX -> MAX
      else -> stepped
    }
  }

  /**
   * 长按后的横滑调速：把「按下时的速度 + 相对位移」线性映射到合法倍速。
   *
   * @param startSpeed 起滑时的速度（内部先量化，避免脏起点）。
   * @param deltaX 当前 X 相对起滑 X 的位移（像素，右正左负）。
   * @param screenWidth 手势区宽度（像素）；`<= 0` 时只允许量化起点，不放大位移。
   * @param sensitivity 灵敏度，默认 3.5：滑过整个手势区宽度 ≈ 跨过 3.5 个量程。
   */
  fun fromSwipe(
    startSpeed: Float,
    deltaX: Float,
    screenWidth: Float,
    sensitivity: Float = 3.5f,
  ): Float {
    val base = quantize(startSpeed)
    if (screenWidth <= 0f) return base
    return quantize(base + (deltaX / screenWidth) * SPAN * sensitivity)
  }

  /** 恒一位小数的展示文本（量化后）。 */
  fun display(value: Float): String = String.format(Locale.US, "%.1f", quantize(value))
}
