package app.marlboroadvance.mpvex.ui.player.gsy

import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.ui.player.gsy.GsySubtitleStyleMapper.bottomMarginDp
import app.marlboroadvance.mpvex.ui.player.gsy.GsySubtitleStyleMapper.gravity
import app.marlboroadvance.mpvex.ui.player.gsy.GsySubtitleStyleMapper.scaledFontSizeSp
import app.marlboroadvance.mpvex.ui.player.gsy.GsySubtitleStyleMapper.shadowRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mpv 字幕刻度 → GSY 样式数值的映射测试（清单 #19）。
 *
 * ## 为什么这些纯函数必须有测试
 *
 * 它们是**两个内核之间唯一的翻译层**，而这类代码的失败模式全是「静默的」：
 * 写错了不报错、不崩，只是字幕看起来「有点怪」—— 用户会认为是播放器 bug，
 * 开发者却查不到任何异常。能把它们钉死在单测里，就能在打包前发现。
 *
 * 特别要盯住的是**单位换算**：mpv 的字号是「视频高度百分比」（0~200），
 * GSY 是 sp。直接搬数字会差 3~4 倍，而这种错误在真机上只是「字好大」，
 * 极易被当成「就这样吧」放过去。
 *
 * ## 为什么断言里不出现 [android.view.Gravity]
 *
 * 本地 JVM 单测拿到的 `android.jar` 只有方法签名、**方法体一律抛 `RuntimeException("Stub!")`**，
 * 连 `Gravity.BOTTOM` 这种常量都取不到。所以下面用 `Gravity` 的**字面数值**
 * （BOTTOM=0x50、START=0x00800003、CENTER_HORIZONTAL=0x01，掩码 0x70 / 0x07）来断言 ——
 * 这些值是 AOSP 里的稳定 API 契约，顺便也一起钉住。
 */
class GsySubtitleStyleMapperTest {

  /** `Gravity.BOTTOM` = 0x50 */
  private val gravityBottom = 0x50

  /** `Gravity.VERTICAL_GRAVITY_MASK` = 0x70 */
  private val verticalMask = 0x70

  /** `Gravity.HORIZONTAL_GRAVITY_MASK` = 0x07 */
  private val horizontalMask = 0x07

  // ── 字号：mpv 刻度 → sp ──────────────────────────────────────────────────

  @Test
  fun `字号映射 单调递增`() {
    // 非单调会让「调大一点」有时反而变小，这种 bug 极难从界面上看出来
    var previous = 0f
    for (size in 0..200 step 5) {
      val mapped = scaledFontSizeSp(size)
      assertTrue("size=$size mapped=$mapped 应不小于上一点", mapped >= previous)
      previous = mapped
    }
  }

  @Test
  fun `字号映射 全部落在有效区间内`() {
    for (size in listOf(-100, 0, 1, 55, 200, 999, Int.MAX_VALUE, Int.MIN_VALUE)) {
      val mapped = scaledFontSizeSp(size)
      assertTrue(
        "size=$size mapped=$mapped 越界",
        mapped >= GsySubtitleStyleMapper.MIN_FONT_SIZE_SP && mapped <= GsySubtitleStyleMapper.MAX_FONT_SIZE_SP,
      )
    }
  }

  @Test
  fun `字号映射 越界输入被夹取而不是溢出`() {
    // mpv 值域是 0~200；超出后要夹取，不能让 sp 变成天文数字（会崩渲染）
    assertEquals(scaledFontSizeSp(0), scaledFontSizeSp(-500))
    assertEquals(scaledFontSizeSp(200), scaledFontSizeSp(500))
  }

  @Test
  fun `字号映射 mpv 默认值 55 落在手机舒服的范围内`() {
    // 55 是 mpv 侧默认值，映射结果必须在 12~26 之间且偏小端 ——
    // 55sp 在手机上能有半屏高，那样默认字幕就没法看了。
    // 这条**曾经挂过**：最早写成「0→12、200→48 的整段线性拉伸」，
    // 结果默认值 55 被拉到 48sp（正好是上限），默认字幕大到没法看。
    val mapped = scaledFontSizeSp(55)
    assertTrue("55 → $mapped，不应在 12~26 之间", mapped in 12f..26f)
  }

  @Test
  fun `字号映射 默认值精确落在锚点上`() {
    // 锚点是刻意选的：55 → 18，落在 12~48 的中段偏小
    assertEquals(GsySubtitleStyleMapper.DEFAULT_FONT_SIZE_SP, scaledFontSizeSp(55), 0.001f)
  }

  @Test
  fun `字号映射 锚点以下走到下限、以上走到上限`() {
    // 两段斜率不同，但两端必须都走满 ——
    // 否则会出现「已经拉到 200 了还是没到上限」
    assertEquals(GsySubtitleStyleMapper.MIN_FONT_SIZE_SP, scaledFontSizeSp(0), 0.001f)
    assertEquals(GsySubtitleStyleMapper.MAX_FONT_SIZE_SP, scaledFontSizeSp(200), 0.001f)
  }

  @Test
  fun `字号映射 常用区比高端区更敏感`() {
    // 下半段 0~55 跨 6sp，上半段 55~200 跨 30sp ⇒ 同样是 10 个刻度，
    // 常用区（下半段）变化应当更小，避免用户「轻轻一调就跳一大截」
    val lowerStep = scaledFontSizeSp(10) - scaledFontSizeSp(0)
    val upperStep = scaledFontSizeSp(200) - scaledFontSizeSp(190)
    assertTrue("常用区斜率($lowerStep) 应不大于高端区($upperStep)", lowerStep <= upperStep)
  }

  // ── 阴影半径：描边粗细 → 阴影半径 ───────────────────────────────────────

  @Test
  fun `阴影半径映射 零就是零`() {
    // 0 必须真的表示「不要阴影」而不是「半径 0 的模糊阴影」——
    // 后者看起来像一层脏影，是用户会投诉的观感问题
    assertEquals(0f, shadowRadius(0), 0.001f)
  }

  @Test
  fun `阴影半径映射 全部落在有效区间内`() {
    for (size in listOf(-10, 0, 3, 25, 1000)) {
      val r = shadowRadius(size)
      assertTrue(
        "size=$size r=$r 越界",
        r >= GsySubtitleStyleMapper.MIN_SHADOW_RADIUS && r <= GsySubtitleStyleMapper.MAX_SHADOW_RADIUS,
      )
    }
  }

  @Test
  fun `阴影半径映射 单调不减`() {
    var previous = 0f
    for (size in 0..25) {
      val r = shadowRadius(size)
      assertTrue("size=$size r=$r", r >= previous)
      previous = r
    }
  }

  // ── 对齐 → Gravity ───────────────────────────────────────────────────────

  @Test
  fun `对齐映射 竖直方向恒为底部`() {
    // 这是最容易写错的一处：GSYSubtitleView.applyPosition() 靠 gravity 的竖直部分
    // 决定把间距算给 topMargin 还是 bottomMargin。写成 TOP 的话字幕位置整个反过来。
    for (value in SubtitleJustification.entries) {
      assertEquals(
        "$value 应贴底",
        gravityBottom,
        gravity(value) and verticalMask,
      )
    }
  }

  @Test
  fun `对齐映射 三个方向各不相同`() {
    val left = gravity(SubtitleJustification.Left) and horizontalMask
    val center = gravity(SubtitleJustification.Center) and horizontalMask
    val right = gravity(SubtitleJustification.Right) and horizontalMask
    assertEquals("左/中/右应互不相同", 3, setOf(left, center, right).size)
  }

  @Test
  fun `对齐映射 Auto 落到居中`() {
    // Auto 在单行字幕下基本等于居中；落左对齐会让「自动」看起来像随机跳变
    assertEquals(
      gravity(SubtitleJustification.Center),
      gravity(SubtitleJustification.Auto),
    )
  }

  // ── 位置：sub_pos → 距底 dp ──────────────────────────────────────────────

  @Test
  fun `位置映射 贴底时边距为零`() {
    // sub_pos=100 是 mpv 侧「字幕贴底」的默认值，边距必须是 0，
    // 否则默认状态下字幕会凭空离底一截
    assertEquals(0, bottomMarginDp(100))
  }

  @Test
  fun `位置映射 数值越大字幕越靠下`() {
    // sub_pos 增大 = 往下移 = 距底边距变小。反了就正好把字幕推到画面外
    assertTrue(bottomMarginDp(20) > bottomMarginDp(80))
  }

  @Test
  fun `位置映射 越界输入被夹取`() {
    assertEquals(bottomMarginDp(0), bottomMarginDp(-100))
    assertEquals(bottomMarginDp(100), bottomMarginDp(9999))
  }

  @Test
  fun `位置映射 全部落在有效区间内`() {
    for (pos in listOf(-1, 0, 1, 50, 99, 100, 101, Int.MAX_VALUE, Int.MIN_VALUE)) {
      val margin = bottomMarginDp(pos)
      assertTrue(
        "pos=$pos margin=$margin 越界",
        margin >= 0 && margin <= GsySubtitleStyleMapper.MAX_BOTTOM_MARGIN_DP,
      )
    }
  }

  // ── 常量钉死 ────────────────────────────────────────────────────────────

  @Test
  fun `常量 字号区间是有效区间`() {
    assertTrue(GsySubtitleStyleMapper.MIN_FONT_SIZE_SP < GsySubtitleStyleMapper.MAX_FONT_SIZE_SP)
    assertEquals(12f, GsySubtitleStyleMapper.MIN_FONT_SIZE_SP, 0.001f)
    assertEquals(48f, GsySubtitleStyleMapper.MAX_FONT_SIZE_SP, 0.001f)
  }

  @Test
  fun `常量 阴影区间是有效区间`() {
    assertEquals(0f, GsySubtitleStyleMapper.MIN_SHADOW_RADIUS, 0.001f)
    assertEquals(12f, GsySubtitleStyleMapper.MAX_SHADOW_RADIUS, 0.001f)
  }

  @Test
  fun `常量 下边距上限是合理值`() {
    // 120dp 差不多是竖屏高度的 1/5，再大字幕就贴到画面中间了
    assertEquals(120, GsySubtitleStyleMapper.MAX_BOTTOM_MARGIN_DP)
  }
}
