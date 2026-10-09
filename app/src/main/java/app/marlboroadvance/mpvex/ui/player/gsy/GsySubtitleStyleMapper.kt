package app.marlboroadvance.mpvex.ui.player.gsy

import android.view.Gravity
import android.view.ViewGroup
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import com.shuyu.gsyvideoplayer.subtitle.GSYSubtitleStyle

/**
 * GSY 字幕样式 ⇄ 项目字幕偏好 的映射（清单 #19 的一半）。
 *
 * ## 为什么要单独抽一个文件
 *
 * mpv 侧的字幕样式是**四十多个 mpv option**（`MPVView.setupSubtitlesOptions()` 拼一串
 * `setOptionString`），GSY 侧是**一个 `GSYSubtitleStyle` 对象**。两边形状完全不同，
 * 硬塞进 `GsyPlayerActivity` 只会让那个本来就很大的类再胖一圈，而这段逻辑
 * 恰恰是最需要被测试的 —— 纯数值映射，不碰 Context、不碰网络、不碰播放器。
 *
 * 抽出来之后：
 *  - 映射规则可以被 JVM 单测直接覆盖（`app/src/test/.../GsySubtitleStyleMapperTest.kt`）；
 *  - 改字幕样式时不用碰 Activity 那一大坨状态机；
 *  - 「GSY 能调哪些、调不了哪些」这件事在 KDoc 里说清楚，而不是散落在 UI 分支里。
 *
 * ## ⚠️ `bottomMarginDp` 是 GSY 库自己的死字段，**不要用**
 *
 * 反编译 `GSYSubtitleView.applyPosition()` 的字节码后确认，它只读了三个字段：
 * `getHorizontalMarginDp()`（→ left/rightMargin）、
 * `getVerticalMarginDp()`（→ **在 gravity 竖直部分是 TOP 时给 topMargin，BOTTOM 时给 bottomMargin**）、
 * `getLayoutGravity()`（→ FrameLayout.gravity）。
 *
 * `getBottomMarginDp()` **从头到尾没被读过** —— `GSYSubtitleStyle.Builder.setPosition(int,int,int)`
 * 和 `setBottomMarginDp(int)` 写进去的值，渲染时完全不生效。
 *
 * 所以「字幕离底边多远」在本项目里是这么实现的：把间距填进
 * **verticalMarginDp**，并让 gravity 的竖直部分**保持 BOTTOM**（见 [gravity]），
 * 于是 `applyPosition` 走 `bottomMargin` 分支 —— 这是唯一真正生效的路径。
 * 写成 `setPosition(0, 0, margin)` 的话，**样式对象看着有值、屏幕上纹丝不动**，
 * 是最难查的那种 bug。
 *
 * ## 两边能力差在哪（**别把 mpv 的选项直接搬到 GSY 上**）
 *
 * | 项目偏好 | mpv | GSY | 说明 |
 * |---|---|---|---|
 * | 字号 `fontSize` | ✅ | ✅ | 直接映射，单位不同（见 [scaledFontSizeSp]） |
 * | 文字色 `textColor` | ✅ | ✅ | 都是 ARGB int |
 * | 背景色 `backgroundColor` | ✅ | ✅ | 都是 ARGB int |
 * | 描边色 `borderColor` | ✅ | ⚠️ | GSY 没有描边，映射到**阴影色**（视觉上最接近） |
 * | 描边粗细 `borderSize` | ✅ | ⚠️ | 映射到**阴影半径**，见 [shadowRadius] |
 * | 阴影偏移 `shadowOffset` | ✅ | ✅ | GSY 的阴影本来就带 XY 偏移 |
 * | 对齐 `justification` | ✅ | ⚠️ | 只能映射左/中/右，`Auto` 落到居中 |
 * | 位置 `subPos` | ✅ | ⚠️ | 映射成**下边距**，只保留靠底那一段 |
 * | 字体名 `font` | ✅ | ❌ | `GSYSubtitleStyle` 没有字体入口 |
 * | 粗体 / 斜体 | ✅ | ❌ | 同上 |
 * | 描边样式 `borderStyle` | ✅ | ❌ | GSY 只有阴影，没有纯描边模式 |
 * | ASS 覆盖 | ✅ | ❌ | GSY 自己解 SRT / WebVTT，压根不认 ASS |
 * | 缩放 `subScale` | ✅ | ❌ | 字号本身已由 [scaledFontSizeSp] 处理 |
 *
 * ❌ 的项**不静默丢弃**：见 [unsupportedByGsy]，字幕样式面板会把它们显式置灰并写清原因。
 */
object GsySubtitleStyleMapper {

  /**
   * GSY 字号的可调区间（sp）。
   *
   * mpv 的 `sub-font-size` 默认 55、习惯范围 0~200；GSY 是普通 `TextView.setTextSize(sp)`，
   * 同一套数字会大得离谱（55sp 的字在手机上能有半屏高）。所以换算成一套
   * **手机上真正能用**的范围，数值钉在这里别随手改。
   */
  const val MIN_FONT_SIZE_SP = 12f
  const val MAX_FONT_SIZE_SP = 48f

  /**
   * mpv 侧的默认字号（`SubtitlesPreferences.fontSize` 的默认值 55），换算的**锚点**。
   *
   * 钉死这个值：改它会连带改变「默认字幕在 GSY 下多大」。设 0 会除零。
   */
  const val DEFAULT_MPV_FONT_SIZE = 55f

  /**
   * 锚点映射到的 sp 值。
   *
   * 18sp 是「手机上看着舒服」的中间值：太小（<14）在大屏上要凑近看，
   * 太大（>26）竖屏会盖住半个画面。
   */
  const val DEFAULT_FONT_SIZE_SP = 18f

  /**
   * mpv 侧 `sub_pos`（0~100，100 = 贴底）映射到「距底多少 dp」时的上限。
   *
   * 不用屏幕高度的百分比，因为 `GSYSubtitleStyle` 的间距是固定 dp ——
   * 换算成百分比还得知道屏幕多高，而播放页横竖屏会变（变一次就得重算一次样式）。
   */
  const val MAX_BOTTOM_MARGIN_DP = 120

  /** 阴影半径上下限（px，最终传给 `setShadowLayer` 的 radius） */
  const val MIN_SHADOW_RADIUS = 0f
  const val MAX_SHADOW_RADIUS = 12f

  /**
   * 字号换算：mpv 的 0~200 相对刻度 → GSY 的 [MIN_FONT_SIZE_SP]~[MAX_FONT_SIZE_SP]。
   *
   * ## 为什么不能用「线性拉伸整个值域」
   *
   * mpv 的 0~200 是**整个值域**，但用户实际只在中间一小段活动（默认 55，
   * 常用 30~100）。如果按 0→12sp、200→48sp 线性拉伸，**默认值 55 会被映射到 48sp**
   * —— 那是最上限，在手机上能有半屏高，等于「默认字幕根本没法看」。
   *
   * 所以这里用**以 55 为锚点的分段线性**：
   *  - 55（默认）→ [DEFAULT_FONT_SIZE_SP]（18，手机上舒服的中间值）；
   *  - 比 55 小 ⇒ 向下往 [MIN_FONT_SIZE_SP] 走；
   *  - 比 55 大 ⇒ 向上往 [MAX_FONT_SIZE_SP] 走。
   *
   * 两段斜率不同（36/55 vs 30/145）是有意的：下半段是「常用区」，要更敏感；
   * 上半段是「给投屏 / 大屏用的极端档」，压一点避免轻易顶到上限。
   */
  fun scaledFontSizeSp(mpvFontSize: Int): Float {
    val clamped = mpvFontSize.coerceIn(0, 200).toFloat()
    return if (clamped <= DEFAULT_MPV_FONT_SIZE) {
      val ratio = clamped / DEFAULT_MPV_FONT_SIZE
      MIN_FONT_SIZE_SP + ratio * (DEFAULT_FONT_SIZE_SP - MIN_FONT_SIZE_SP)
    } else {
      val over = (clamped - DEFAULT_MPV_FONT_SIZE) / (200f - DEFAULT_MPV_FONT_SIZE)
      DEFAULT_FONT_SIZE_SP + over * (MAX_FONT_SIZE_SP - DEFAULT_FONT_SIZE_SP)
    }.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
  }

  /**
   * 描边粗细 → 阴影半径。
   *
   * GSY 没有「描边」只有「阴影」，但视觉上描边和阴影在纯色背景上几乎一样 ——
   * 所以把 mpv 的 `sub-border-size` 拿来当阴影半径用，是最接近的还原。
   * mpv 范围 0~25，这里夹到 [MIN_SHADOW_RADIUS]~[MAX_SHADOW_RADIUS]。
   */
  fun shadowRadius(borderSize: Int): Float =
    borderSize.coerceIn(0, 25).toFloat().coerceIn(MIN_SHADOW_RADIUS, MAX_SHADOW_RADIUS)

  /**
   * 对齐 → `android.view.Gravity`。
   *
   * **竖直部分恒为 [Gravity.BOTTOM]** —— 这不是随手写的：`applyPosition()` 靠
   * gravity 的竖直部分决定把 `verticalMarginDp` 算给 topMargin 还是 bottomMargin，
   * 写成 TOP 的话 [bottomMarginDp] 的语义就整个反过来了（字幕会跑到画面顶上）。
   *
   * mpv 的 `Auto`（按内容决定）在单行字幕下基本等于居中，所以也落到
   * [Gravity.CENTER_HORIZONTAL] —— 落左对齐会让「自动」看起来像随机跳变，
   * 比不精确更让人困惑。
   */
  fun gravity(justification: SubtitleJustification): Int =
    when (justification) {
      SubtitleJustification.Left -> Gravity.START or Gravity.BOTTOM
      SubtitleJustification.Center -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
      SubtitleJustification.Right -> Gravity.END or Gravity.BOTTOM
      SubtitleJustification.Auto -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
    }

  /**
   * 字幕位置（`sub_pos`，0~100）→ 距底多少 dp。
   *
   * mpv 的 `sub_pos` 语义是**字幕基线在画面高度上的百分比**，100 = 贴底、0 = 贴顶。
   * `GSYSubtitleStyle` 只有固定的 dp 间距，两者不是线性可逆的（贴顶要算屏幕高度，
   * 而播放页横竖屏会变），所以只取**靠底那一段** —— 用户真正会调的区域。
   */
  fun bottomMarginDp(subPos: Int): Int {
    val clamped = subPos.coerceIn(0, 100)
    val ratio = (100 - clamped).toFloat() / 100f
    return (ratio * MAX_BOTTOM_MARGIN_DP).toInt().coerceIn(0, MAX_BOTTOM_MARGIN_DP)
  }

  /**
   * 按当前偏好造一个 `GSYSubtitleStyle`。
   *
   * [density] 传 `DisplayMetrics.density`；之所以**不**在内部读，是因为那会让本函数
   * 依赖 Android 静态状态而没法纯 JVM 测 —— 间距换算正是最需要被断言的部分。
   *
   * 描边样式（`SubtitlesBorderStyle`）在这里**被有意忽略** —— GSY 只有阴影没有描边，
   * 把「纯描边」映射成带阴影的样式会让用户觉得设置没生效，不如统一都带一点
   * 阴影（默认 `borderSize=3` ⇒ 半径 3px，肉眼几乎看不出差别）。
   */
  fun styleFrom(
    density: Float,
    prefs: SubtitlesPreferences,
    fontSize: Int = prefs.fontSize.get(),
    textColor: Int = prefs.textColor.get(),
    backgroundColor: Int = prefs.backgroundColor.get(),
    borderColor: Int = prefs.borderColor.get(),
    borderSize: Int = prefs.borderSize.get(),
    shadowOffset: Int = prefs.shadowOffset.get(),
    justification: SubtitleJustification = prefs.justification.get(),
    subPos: Int = prefs.subPos.get(),
  ): GSYSubtitleStyle =
    GSYSubtitleStyle
      .Builder()
      .setTextColor(textColor)
      .setTextSizeSp(scaledFontSizeSp(fontSize))
      .setBackgroundColor(backgroundColor)
      // 描边色/粗细 → 阴影色/半径：GSY 没有描边，这是视觉上最接近的还原。
      // dx/dy 是**像素**（setShadowLayer 的原生单位），所以这里要乘 density。
      .setShadow(
        borderColor,
        shadowRadius(borderSize),
        shadowOffset * density,
        shadowOffset * density,
      )
      // 间距走 verticalMarginDp（见类 KDoc：bottomMarginDp 是死字段），
      // GSY 内部会按 gravity=BOTTOM 把它算成 bottomMargin。
      .setVerticalMarginDp(bottomMarginDp(subPos))
      .setLayoutGravity(gravity(justification))
      .build()

  /**
   * 当前这套能力里，**用户在设置页开了但 GSY 注定做不到**的项。
   *
   * 用途：字幕样式面板在 GSY 下要把这些项显式置灰并写清原因，
   * 而不是让用户调完发现没反应（那正是 [EngineKind] KDoc 里说的更糟的体验）。
   */
  fun unsupportedByGsy(prefs: SubtitlesPreferences): Set<String> {
    val out = mutableSetOf<String>()
    if (prefs.font.get().isNotBlank()) out += "字体名"
    if (prefs.bold.get()) out += "粗体"
    if (prefs.italic.get()) out += "斜体"
    if (prefs.overrideAssSubs.get()) out += "ASS 样式覆盖"
    if (prefs.justification.get() == SubtitleJustification.Auto) out += "自动对齐"
    return out
  }

  /**
   * 字幕视图的 LayoutParams 是不是 [ViewGroup.MarginLayoutParams]。
   *
   * `applyPosition()` 第一件事就是 `if (!(lp instanceof MarginLayoutParams)) return;`
   * —— 布局里那个 `GSYSubtitleView` 如果没放在带 margin 的容器里，**所有间距设置一律不生效**。
   *
   * 这个函数给设置页用：样式调完发现字幕贴不了边，先查这个而不是查映射逻辑。
   */
  fun isMarginContainer(params: ViewGroup.LayoutParams?): Boolean = params is ViewGroup.MarginLayoutParams
}
