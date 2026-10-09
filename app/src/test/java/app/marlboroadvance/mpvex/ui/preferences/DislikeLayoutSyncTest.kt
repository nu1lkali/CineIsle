package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.ui.preferences.DislikeLayoutSync.DEFAULT_CLUSTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DislikeLayoutSync] 的测试 —— 「播放器按钮开关」与「控件布局编辑器」的双向同步。
 *
 * ## 为什么这段字符串手术必须有测试
 *
 * 它直接**改写用户手摆的控件布局**，而 CSV 是逗号分隔的枚举名 ——
 * 分割 / 拼接写错一位，表现是「某个别的按钮凭空消失」或「布局整条作废」，
 * 而 App 不会报任何错。用户在布局编辑器里会看到一堆按钮位置乱了，却查不到原因。
 *
 * 另外 `removeFrom` 如果用 `contains` 而不是完整 token 比对，
 * 会出现「EMBY_FAVORITE 里含 FAVORITE」这类误删 —— 这条必须钉死。
 */
class DislikeLayoutSyncTest {

  private val dislike = PlayerButton.DISLIKE

  // ── removeFrom ───────────────────────────────────────────────────

  @Test
  fun `removeFrom 移除指定 token`() {
    assertEquals(
      "EMBY_FAVORITE,CAST",
      DislikeLayoutSync.removeFrom("EMBY_FAVORITE,DISLIKE,CAST", dislike),
    )
  }

  @Test
  fun `removeFrom 不用子串匹配 不会误删 EMBY_FAVORITE`() {
    // EMBY_FAVORITE 里含 FAVORITE 子串 —— 用 contains 就会连收藏键一起删掉
    val csv = "EMBY_FAVORITE,CAST"
    assertEquals(csv, DislikeLayoutSync.removeFrom(csv, dislike))
  }

  @Test
  fun `removeFrom 忽略大小写与空格`() {
    assertEquals(
      "CAST",
      DislikeLayoutSync.removeFrom(" dislike , CAST ", dislike),
    )
  }

  @Test
  fun `removeFrom 目标不存在时原样返回`() {
    val csv = "EMBY_FAVORITE,CAST"
    assertEquals(csv, DislikeLayoutSync.removeFrom(csv, dislike))
  }

  @Test
  fun `removeFrom 空串返回空串`() {
    assertEquals("", DislikeLayoutSync.removeFrom("", dislike))
  }

  @Test
  fun `removeFrom 清掉尾随逗号 不留空 token`() {
    // 留一个空 token 的话，下次解析会多出一个无名按钮
    assertEquals("CAST", DislikeLayoutSync.removeFrom("CAST,DISLIKE,", dislike))
  }

  // ── containsIn ───────────────────────────────────────────────────

  @Test
  fun `containsIn 命中与不命中`() {
    assertTrue(DislikeLayoutSync.containsIn("CAST,DISLIKE", dislike))
    assertFalse(DislikeLayoutSync.containsIn("CAST,EMBY_FAVORITE", dislike))
    assertFalse(DislikeLayoutSync.containsIn("", dislike))
  }

  // ── addTo ────────────────────────────────────────────────────────

  @Test
  fun `addTo 加到末尾`() {
    assertEquals(
      "CAST,EMBY_FAVORITE,DISLIKE",
      DislikeLayoutSync.addTo("CAST,EMBY_FAVORITE", dislike),
    )
  }

  @Test
  fun `addTo 已经存在时是幂等的`() {
    val csv = "CAST,DISLIKE"
    assertEquals(csv, DislikeLayoutSync.addTo(csv, dislike))
  }

  @Test
  fun `addTo 空串得到单元素`() {
    assertEquals("DISLIKE", DislikeLayoutSync.addTo("", dislike))
  }

  // ── planTurnOff ──────────────────────────────────────────────────

  @Test
  fun `planTurnOff 清掉所有簇里的按钮 并报改动数`() {
    val current =
      mapOf(
        "top_left_controls" to "DISLIKE",
        "top_right_controls" to "CAST,DISLIKE",
        "bottom_right_controls_v2" to "SUBTITLE",
        "bottom_left_controls" to "ASPECT",
        "portrait_bottom_controls_v3" to "SPEED",
      )

    val plan = DislikeLayoutSync.planTurnOff(current)

    assertEquals(2, plan.changedClusters)
    assertEquals("", plan.writes["top_left_controls"])
    assertEquals("CAST", plan.writes["top_right_controls"])
    // 没含该按钮的簇不该被写回（写回也是同值，但会让调用方多记一次"改过"）
    assertFalse(plan.writes.containsKey("bottom_right_controls_v2"))
    assertFalse(plan.writes.containsKey("bottom_left_controls"))
    assertFalse(plan.writes.containsKey("portrait_bottom_controls_v3"))
  }

  @Test
  fun `planTurnOff 布局里没有按钮时不产生任何写入`() {
    val current =
      mapOf(
        "top_left_controls" to "SUBTITLE",
        "top_right_controls" to "CAST",
        "bottom_right_controls_v2" to "ASPECT",
        "bottom_left_controls" to "SPEED",
        "portrait_bottom_controls_v3" to "VOLUME",
      )

    val plan = DislikeLayoutSync.planTurnOff(current)

    assertEquals(0, plan.changedClusters)
    assertTrue(plan.writes.isEmpty())
  }

  // ── planTurnOn ───────────────────────────────────────────────────

  @Test
  fun `planTurnOn 加回记录的簇`() {
    val current =
      mapOf(
        "top_left_controls" to "SUBTITLE",
        "top_right_controls" to "CAST",
        "bottom_right_controls_v2" to "ASPECT",
        "bottom_left_controls" to "SPEED",
        "portrait_bottom_controls_v3" to "VOLUME",
      )

    val plan =
      DislikeLayoutSync.planTurnOn(current, clusterKey = "bottom_right_controls_v2")

    assertEquals(mapOf("bottom_right_controls_v2" to "ASPECT,DISLIKE"), plan.writes)
    assertEquals(1, plan.changedClusters)
  }

  @Test
  fun `planTurnOn 簇无效时退回默认簇`() {
    val current = mapOf(DEFAULT_CLUSTER to "CAST")

    val plan = DislikeLayoutSync.planTurnOn(current, clusterKey = "不存在的簇")

    assertEquals(mapOf(DEFAULT_CLUSTER to "CAST,DISLIKE"), plan.writes)
  }

  @Test
  fun `planTurnOn 记录为 null 时退回默认簇`() {
    val current = mapOf(DEFAULT_CLUSTER to "CAST")
    val plan = DislikeLayoutSync.planTurnOn(current, clusterKey = null)
    assertEquals(mapOf(DEFAULT_CLUSTER to "CAST,DISLIKE"), plan.writes)
  }

  @Test
  fun `planTurnOn 默认簇不在配置里时也能加`() {
    // 老版本偏好可能压根没有这个键 —— 不能因此什么都不做
    val plan = DislikeLayoutSync.planTurnOn(emptyMap(), clusterKey = null)
    assertEquals(mapOf(DEFAULT_CLUSTER to "DISLIKE"), plan.writes)
  }

  @Test
  fun `planTurnOn 已经在那条簇里时是幂等的`() {
    val current = mapOf(DEFAULT_CLUSTER to "CAST,DISLIKE")
    val plan = DislikeLayoutSync.planTurnOn(current, clusterKey = DEFAULT_CLUSTER)

    assertTrue(plan.writes.isEmpty())
    assertEquals(0, plan.changedClusters)
  }

  // ── 关→开 往返：用户最关心的实际场景 ──────────────────────────────

  @Test
  fun `关掉再打开会回到原来的簇`() {
    val original = mapOf("bottom_left_controls" to "SPEED,CAST")
    val cluster = "bottom_left_controls"

    val off = DislikeLayoutSync.planTurnOff(original)
    val afterOff = original.toMutableMap().apply { putAll(off.writes) }
    assertEquals("SPEED,CAST", afterOff[cluster])

    val on = DislikeLayoutSync.planTurnOn(afterOff, clusterKey = cluster)
    assertEquals("SPEED,CAST,DISLIKE", on.writes[cluster])
  }
}
