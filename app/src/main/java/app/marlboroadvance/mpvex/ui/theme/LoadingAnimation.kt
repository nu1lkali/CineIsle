package app.marlboroadvance.mpvex.ui.theme

import androidx.annotation.RawRes
import app.marlboroadvance.mpvex.R

/**
 * 可选的加载动画（Lottie）。
 *
 * 搬的是 Yamby 内置的那一套（`res/raw/yamby_*.json`，Apache-2.0）+ 自研两套，
 * 用 lottie-compose 渲染。
 *
 * ⚠️ `storageKey` 写进 SharedPreferences，**发布后不可改名**（改名等于静默重置用户选择）。
 */
enum class LoadingAnimation(
  val storageKey: String,
  val labelRes: Int,
  @RawRes val resId: Int,
) {
  /** 跟随主题：无动画（用旋转指示器）。永远保留，用户要有退路。 */
  System("system", app.marlboroadvance.mpvex.R.string.loading_anim_system, 0),

  Yamby1("yamby1", app.marlboroadvance.mpvex.R.string.loading_anim_yamby1, R.raw.yamby_custom_loading1),
  Yamby2("yamby2", app.marlboroadvance.mpvex.R.string.loading_anim_yamby2, R.raw.yamby_custom_loading2),
  Yamby3("yamby3", app.marlboroadvance.mpvex.R.string.loading_anim_yamby3, R.raw.yamby_custom_loading3),
  Yamby4("yamby4", app.marlboroadvance.mpvex.R.string.loading_anim_yamby4, R.raw.yamby_custom_loading4),
  Yamby5("yamby5", app.marlboroadvance.mpvex.R.string.loading_anim_yamby5, R.raw.yamby_custom_loading5),
  Yamby6("yamby6", app.marlboroadvance.mpvex.R.string.loading_anim_yamby6, R.raw.yamby_custom_loading6),

  /** 自研：呼吸圆环（矢量转 Lottie，体积约 1KB）。 */
  CineBreath(
    "cine_breath",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_breath,
    R.raw.cine_loading_breath,
  ),

  /** 自研：双环旋转。 */
  CineRings(
    "cine_rings",
    app.marlboroadvance.mpvex.R.string.loading_anim_cine_rings,
    R.raw.cine_loading_rings,
  ),
  ;

  val hasLottie: Boolean get() = resId != 0

  companion object {
    /** 反解 storageKey；认不出来回 [System]（必须有退路，不能给一个空动画）。 */
    fun fromKey(key: String?): LoadingAnimation =
      entries.firstOrNull { it.storageKey == key } ?: System
  }
}