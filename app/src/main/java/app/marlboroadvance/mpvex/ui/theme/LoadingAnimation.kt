package app.marlboroadvance.mpvex.ui.theme

import androidx.annotation.RawRes
import app.marlboroadvance.mpvex.R

/**
 * 可选的加载动画（Lottie）。
 *
 * 搬的是 Yamby 内置的那一套（`res/raw/yamby_*.json`，Apache-2.0）+ 自研若干，
 * 用 lottie-compose 渲染。
 *
 * ## 两类来源的分工
 *
 * · **Yamby 搬来的**（`Yamby1..7`）：成品度高、体积大（5KB~68KB）。
 * · **自研的**（`Cine*`）：手写 json，每个 1~5KB，循环点严格对齐、无「播完停住」的尾巴。
 *   整屏加载可以随手用，列表里几十个占位也不掉帧。
 *
 * ⚠️ `storageKey` 写进 SharedPreferences，**发布后不可改名**（改名等于静默重置用户选择）。
 * ⚠️ 但**显示名（`labelRes`）随便改** —— 名字起得不准用户根本分不清哪个是哪个
 *   （曾把 6 个各不相同的动画全叫「波纹一~六」，用户以为只有 4 个选项）。
 */
enum class LoadingAnimation(
  val storageKey: String,
  val labelRes: Int,
  @RawRes val resId: Int,
) {
  /** 跟随主题：无动画（用旋转指示器）。永远保留，用户要有退路。 */
  System("system", app.marlboroadvance.mpvex.R.string.loading_anim_system, 0),

  // ── Yamby：按画面内容命名，别用「波纹一/二/三」这种编号 ──────────────
  Yamby1("yamby1", app.marlboroadvance.mpvex.R.string.loading_anim_yamby1, R.raw.yamby_custom_loading1),
  Yamby2("yamby2", app.marlboroadvance.mpvex.R.string.loading_anim_yamby2, R.raw.yamby_custom_loading2),
  Yamby3("yamby3", app.marlboroadvance.mpvex.R.string.loading_anim_yamby3, R.raw.yamby_custom_loading3),
  Yamby4("yamby4", app.marlboroadvance.mpvex.R.string.loading_anim_yamby4, R.raw.yamby_custom_loading4),
  Yamby5("yamby5", app.marlboroadvance.mpvex.R.string.loading_anim_yamby5, R.raw.yamby_custom_loading5),
  Yamby6("yamby6", app.marlboroadvance.mpvex.R.string.loading_anim_yamby6, R.raw.yamby_custom_loading6),
  Yamby7("yamby7", app.marlboroadvance.mpvex.R.string.loading_anim_yamby7, R.raw.yamby_loading2),

  // ── 自研 ────────────────────────────────────────────────────────────
  /** 呼吸圆环（矢量转 Lottie，体积约 1KB）。 */
  CineBreath(
    "cine_breath",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_breath,
    R.raw.cine_loading_breath,
  ),

  /** 双环旋转。 */
  CineRings(
    "cine_rings",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_rings,
    R.raw.cine_loading_rings,
  ),

  /** 三点依次跳动。 */
  CinePulseDots(
    "cine_pulse_dots",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_pulse_dots,
    R.raw.cine_loading_pulse_dots,
  ),

  /** 同心圆环向外扩散淡出。 */
  CineRipple(
    "cine_ripple",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_ripple,
    R.raw.cine_loading_ripple,
  ),

  /** 双弧反向旋转。 */
  CineSpinArcs(
    "cine_spin_arcs",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_spin_arcs,
    R.raw.cine_loading_spin_arcs,
  ),

  /** 五条竖杠上下跳动（均衡器）。 */
  CineBars(
    "cine_bars",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_bars,
    R.raw.cine_loading_bars,
  ),

  /** 光晕呼吸（外圈扩散 + 实心核缩放）。 */
  CineHalo(
    "cine_halo",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_halo,
    R.raw.cine_loading_halo,
  ),
  ;

  val hasLottie: Boolean get() = resId != 0

  companion object {
    /** 反解 storageKey；认不出来回 [System]（必须有退路，不能给一个空动画）。 */
    fun fromKey(key: String?): LoadingAnimation =
      entries.firstOrNull { it.storageKey == key } ?: System
  }
}