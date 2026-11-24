package app.marlboroadvance.mpvex.ui.player.controls

/**
 * 画面按 fit 居中铺进窗口后，上下各留出的黑边高度（dp）。
 *
 * 竖屏播放横屏影片时这两个值很大（16:9 的片在 360×800dp 的屏上只占约 202dp 高，
 * 上下各约 299dp），正是「不挡画面」可以用来放面板的空间。
 */
internal data class LetterboxBars(
  val top: Float,
  val bottom: Float,
)

/**
 * 算出上下黑边的高度。
 *
 * 口径与 `GestureHandler` 里那套 fit 逻辑一致：
 *     画面宽高比 ≥ 屏幕宽高比 ⇒ 宽度顶满，高度 = 宽 / 画幅；
 *     否则高度顶满，宽度 = 高 × 画幅。
 * 剩下的高度对半分给上下两条黑边（mpv 默认 `video-align` 居中）。
 *
 * @param aspect `video-params/aspect`，读不到传 null
 * @return 画面几乎铺满（没有可用黑边）时返回 null
 */
internal fun letterboxBars(
  aspect: Double?,
  viewWidthDp: Float,
  viewHeightDp: Float,
): LetterboxBars? {
  if (viewWidthDp <= 0f || viewHeightDp <= 0f) return null
  // 画幅为 0 / NaN / 离谱值时不能拿去算 —— 那会把黑边算成负的或整屏
  val ratio = aspect?.takeIf { it.isFinite() && it in 0.1..10.0 }?.toFloat() ?: return null

  val screenRatio = viewWidthDp / viewHeightDp
  val pictureHeight = if (ratio >= screenRatio) viewWidthDp / ratio else viewHeightDp
  val bar = (viewHeightDp - pictureHeight) / 2f
  // 竖屏放竖屏片、或画面本来就把高度顶满：没有黑边可利用
  return if (bar < 1f) null else LetterboxBars(top = bar, bottom = bar)
}
