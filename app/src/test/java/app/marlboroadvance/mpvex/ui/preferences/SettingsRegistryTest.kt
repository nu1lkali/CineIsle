package app.marlboroadvance.mpvex.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SettingsRegistry] 的元数据完整性检查：入口漏标题 / 重复 id / 空分组都会让设置搜索漂移。 */
class SettingsRegistryTest {

  @Test
  fun `入口 id 唯一且都有标题摘要`() {
    val all = SettingsRegistry.all
    assertEquals(all.size, all.map { it.id }.toSet().size)
    all.forEach { entry ->
      assertTrue("id 不能为空", entry.id.isNotBlank())
      assertTrue("分组不能为空", entry.section.isNotBlank())
      assertTrue(
        "入口 ${entry.id} 必须有标题资源或字面量",
        entry.titleRes != null || !entry.titleText.isNullOrBlank(),
      )
      assertTrue(
        "入口 ${entry.id} 必须有摘要资源或字面量",
        entry.summaryRes != null || !entry.summaryText.isNullOrBlank(),
      )
      assertNotNull("入口 ${entry.id} 必须有目标页面", entry.screen)
    }
  }

  @Test
  fun `常用入口都登记在清单里`() {
    val ids = SettingsRegistry.all.map { it.id }.toSet()
    assertTrue("appearance" in ids)
    assertTrue("player" in ids)
    assertTrue("decoder" in ids)
    assertTrue("subtitles" in ids)
    assertTrue("aggregate_library" in ids)
    assertTrue("about" in ids)
  }

  @Test
  fun `每个导航入口都能被设置搜索索引到`() {
    val indexed = SearchablePreferences.allPreferences
    SettingsRegistry.all.forEach { entry ->
      val hit = indexed.any { pref ->
        (entry.titleRes != null && pref.titleRes == entry.titleRes) ||
          (entry.titleText != null && pref.title == entry.titleText && pref.screen == entry.screen)
      }
      assertTrue("入口 ${entry.id} 没有进入设置搜索索引", hit)
    }
  }
}
