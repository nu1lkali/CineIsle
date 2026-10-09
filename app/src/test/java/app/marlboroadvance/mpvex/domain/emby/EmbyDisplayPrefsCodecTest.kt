package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyDisplayPrefsCodec.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbyDisplayPrefsCodec] 的单测。
 *
 * 这个 codec 是纯函数（不碰 Android、不碰网络），所以能直接 JVM 跑。
 * 它测的是**最容易静默出错**的那一类：键名写错、数值越界、类型对不上 ——
 * 这些在真机上表现为「同步了但没生效」，不写测试根本发现不了。
 */
class EmbyDisplayPrefsCodecTest {

  // ─── encode ───

  @Test
  fun `默认快照编出五个键`() {
    // 默认 viewMode 是空串（= 用默认）⇒ 按 encode 的规则不写键，所以是 5 个不是 6 个
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot())
    assertEquals(5, prefs.size)
    assertFalse(prefs.containsKey(EmbyDisplayPrefsCodec.KEY_VIEW_MODE))
    assertEquals("3", prefs[EmbyDisplayPrefsCodec.KEY_GRID_COLUMNS])
    assertEquals("12", prefs[EmbyDisplayPrefsCodec.KEY_CARD_CORNER])
    assertEquals("8", prefs[EmbyDisplayPrefsCodec.KEY_CARD_SPACING])
    assertEquals("true", prefs[EmbyDisplayPrefsCodec.KEY_SHOW_CARD_BADGES])
    assertEquals("POSTER", prefs[EmbyDisplayPrefsCodec.KEY_CARD_STYLE])
  }

  @Test
  fun `空串的 viewMode 不写进键`() {
    // 空串代表「用默认」，写成 "" 会让别的客户端以为用户显式清空过
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot(viewMode = ""))
    assertFalse(prefs.containsKey(EmbyDisplayPrefsCodec.KEY_VIEW_MODE))
  }

  @Test
  fun `有值的 viewMode 会写进键`() {
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot(viewMode = "GRID"))
    assertEquals("GRID", prefs[EmbyDisplayPrefsCodec.KEY_VIEW_MODE])
    assertEquals(6, prefs.size)
  }

  @Test
  fun `排序不写进 CustomPrefs`() {
    // SortBy / SortOrder 是 DTO 上的独立字段，塞进 CustomPrefs 服务端不报错但读不到
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot(sortBy = "DateCreated", sortOrder = "Descending"))
    assertFalse(prefs.values.contains("DateCreated"))
    assertFalse(prefs.values.contains("Descending"))
  }

  @Test
  fun `全部键都带 mpvex 前缀`() {
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot(viewMode = "LIST"))
    assertTrue(prefs.keys.all { it.startsWith("mpvex.") })
  }

  // ─── encode 的夹取 ───

  @Test
  fun `列数越界时夹回合法区间`() {
    assertEquals("6", EmbyDisplayPrefsCodec.encodePrefs(Snapshot(gridColumns = 99))["mpvex.gridColumns"])
    assertEquals("2", EmbyDisplayPrefsCodec.encodePrefs(Snapshot(gridColumns = -5))["mpvex.gridColumns"])
  }

  @Test
  fun `圆角与间距越界时夹回合法区间`() {
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(Snapshot(cardCorner = 999, cardSpacing = 0))
    assertEquals("24", prefs["mpvex.cardCorner"])
    assertEquals("2", prefs["mpvex.cardSpacing"])
  }

  // ─── decode ───

  @Test
  fun `完整往返后各项一致`() {
    val original = Snapshot(
      viewMode = "TIMELINE",
      gridColumns = 5,
      cardStyle = "BACKDROP",
      cardCorner = 4,
      cardSpacing = 16,
      showCardBadges = false,
      sortBy = "DateCreated",
      sortOrder = "Descending",
    )
    val prefs = EmbyDisplayPrefsCodec.encodePrefs(original)
    val back = EmbyDisplayPrefsCodec.decode(prefs, "DateCreated", "Descending")
    assertEquals(original, back)
  }

  @Test
  fun `空map 逐字段回落到fallback`() {
    val fallback = Snapshot(gridColumns = 6, cardCorner = 20)
    val back = EmbyDisplayPrefsCodec.decode(emptyMap(), null, null, fallback)
    assertEquals(fallback, back)
  }

  @Test
  fun `认不出的键直接被忽略`() {
    // 别的客户端写的键不能进我们的快照，也不能让解码失败
    val back = EmbyDisplayPrefsCodec.decode(
      mapOf("someOtherClientKey" to "???", "mpvex.gridColumns" to "4"),
      null,
      null,
    )
    assertEquals(4, back.gridColumns)
  }

  @Test
  fun `非数字的列数退回fallback 而不是 0`() {
    val back = EmbyDisplayPrefsCodec.decode(
      mapOf("mpvex.gridColumns" to "abc"),
      null,
      null,
      Snapshot(gridColumns = 3),
    )
    assertEquals(3, back.gridColumns)
  }

  @Test
  fun `服务端的越界值会被夹回而不是照搬`() {
    val back = EmbyDisplayPrefsCodec.decode(
      mapOf(
        "mpvex.gridColumns" to "99",
        "mpvex.cardCorner" to "-10",
        "mpvex.cardSpacing" to "1000",
      ),
      null,
      null,
    )
    assertEquals(6, back.gridColumns)
    assertEquals(0, back.cardCorner)
    assertEquals(24, back.cardSpacing)
  }

  @Test
  fun `只有小写 true 才算开`() {
    assertTrue(EmbyDisplayPrefsCodec.decode(mapOf("mpvex.showCardBadges" to "true"), null, null).showCardBadges)
    // "True" / "1" 在不同服务端版本上行为不一致，本 App 只认小写
    assertFalse(EmbyDisplayPrefsCodec.decode(mapOf("mpvex.showCardBadges" to "True"), null, null).showCardBadges)
    assertFalse(EmbyDisplayPrefsCodec.decode(mapOf("mpvex.showCardBadges" to "1"), null, null).showCardBadges)
  }

  @Test
  fun `空串的 viewMode 视为没有`() {
    val back = EmbyDisplayPrefsCodec.decode(
      mapOf("mpvex.viewMode" to ""),
      null,
      null,
      Snapshot(viewMode = "GRID"),
    )
    assertEquals("GRID", back.viewMode)
  }

  @Test
  fun `排序从 DTO 字段读而不是从 CustomPrefs`() {
    val back = EmbyDisplayPrefsCodec.decode(
      mapOf("mpvex.gridColumns" to "2"),
      "CommunityRating",
      "Ascending",
    )
    assertEquals("CommunityRating", back.sortBy)
    assertEquals("Ascending", back.sortOrder)
  }

  @Test
  fun `DTO 排序字段为空串时回落`() {
    val back = EmbyDisplayPrefsCodec.decode(emptyMap(), "", "", Snapshot(sortBy = "SortName"))
    assertEquals("SortName", back.sortBy)
  }

  // ─── merge ───

  @Test
  fun `merge 保留服务端已有的别人的键`() {
    // POST 是整体覆盖，不 merge 就会把同分区里别人的键全抹掉
    val existing = EmbyDisplayPreferences(
      Id = "lib-1",
      CustomPrefs = mapOf("someoneElse" to "keep-me"),
    )
    val merged = EmbyDisplayPrefsCodec.merge(existing, Snapshot(gridColumns = 5), "mpvex-android")
    assertEquals("keep-me", merged.CustomPrefs["someoneElse"])
    assertEquals("5", merged.CustomPrefs["mpvex.gridColumns"])
  }

  @Test
  fun `merge 用新值覆盖同名的旧值`() {
    val existing = EmbyDisplayPreferences(
      CustomPrefs = mapOf("mpvex.gridColumns" to "2"),
    )
    val merged = EmbyDisplayPrefsCodec.merge(existing, Snapshot(gridColumns = 6), "mpvex-android")
    assertEquals("6", merged.CustomPrefs["mpvex.gridColumns"])
  }

  @Test
  fun `merge 带上 client 与 id`() {
    val merged = EmbyDisplayPrefsCodec.merge(
      EmbyDisplayPreferences(Id = "lib-9"),
      Snapshot(),
      "mpvex-android",
    )
    assertEquals("lib-9", merged.Id)
    assertEquals("mpvex-android", merged.Client)
  }

  @Test
  fun `merge 把排序写进 DTO 字段`() {
    val merged = EmbyDisplayPrefsCodec.merge(
      EmbyDisplayPreferences(),
      Snapshot(sortBy = "DateCreated", sortOrder = "Descending"),
      "mpvex-android",
    )
    assertEquals("DateCreated", merged.SortBy)
    assertEquals("Descending", merged.SortOrder)
  }

  // ─── differsFrom ───

  @Test
  fun `相同快照不报差异`() {
    assertFalse(EmbyDisplayPrefsCodec.differsFrom(Snapshot(), Snapshot()))
  }

  @Test
  fun `任一字段不同即报差异`() {
    assertTrue(EmbyDisplayPrefsCodec.differsFrom(Snapshot(gridColumns = 4), Snapshot()))
    assertTrue(EmbyDisplayPrefsCodec.differsFrom(Snapshot(showCardBadges = false), Snapshot()))
    assertTrue(EmbyDisplayPrefsCodec.differsFrom(Snapshot(sortOrder = "Ascending"), Snapshot()))
  }

  // ─── 常量钉死 ───

  @Test
  fun `键名常量不会悄悄改`() {
    // 改了这些键 = 存量用户服务端那份直接读不出来（等于静默清空设置）
    assertEquals("mpvex.viewMode", EmbyDisplayPrefsCodec.KEY_VIEW_MODE)
    assertEquals("mpvex.gridColumns", EmbyDisplayPrefsCodec.KEY_GRID_COLUMNS)
    assertEquals("mpvex.cardStyle", EmbyDisplayPrefsCodec.KEY_CARD_STYLE)
    assertEquals("mpvex.cardCorner", EmbyDisplayPrefsCodec.KEY_CARD_CORNER)
    assertEquals("mpvex.cardSpacing", EmbyDisplayPrefsCodec.KEY_CARD_SPACING)
    assertEquals("mpvex.showCardBadges", EmbyDisplayPrefsCodec.KEY_SHOW_CARD_BADGES)
  }

  @Test
  fun `区间常量与设置页滑杆一致`() {
    assertEquals(2, EmbyDisplayPrefsCodec.MIN_GRID_COLUMNS)
    assertEquals(6, EmbyDisplayPrefsCodec.MAX_GRID_COLUMNS)
    assertEquals(0, EmbyDisplayPrefsCodec.MIN_CARD_CORNER)
    assertEquals(24, EmbyDisplayPrefsCodec.MAX_CARD_CORNER)
    assertEquals(2, EmbyDisplayPrefsCodec.MIN_CARD_SPACING)
    assertEquals(24, EmbyDisplayPrefsCodec.MAX_CARD_SPACING)
  }

  @Test
  fun `分区 client 常量不会改`() {
    // 改了它，之前所有设备写的服务端偏好都读不到了
    assertEquals("mpvex-android", EmbyClient.DISPLAY_PREFS_CLIENT)
  }
}
