package app.marlboroadvance.mpvex.domain.smartmerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMergeCodeTest {

  @Test
  fun `常见番号写法归一到同一主体`() {
    val variants = listOf("ABC-123", "abc123", "ABC_123", "ABC 123", "ABC-123 LTX", "ABC-123 中文字幕")
    val bodies = variants.mapNotNull { SmartMergeCode.extract(listOf(it))?.body }.toSet()
    assertEquals(setOf("ABC123"), bodies)
    assertEquals("ABC-123", SmartMergeCode.extract(listOf("ABC-123"))?.display)
  }

  @Test
  fun `ProviderIds 里的番号也能提取`() {
    val code = SmartMergeCode.extract(listOf("SSIS-001", "a completely unrelated title"))
    assertNotNull(code)
    assertEquals("SSIS1", code?.body)
    assertEquals("SSIS-001", code?.display)
  }

  @Test
  fun `前导零变体归一到同一主体`() {
    val a = SmartMergeCode.extract(listOf("neob-0017"))
    val b = SmartMergeCode.extract(listOf("neob-017"))
    assertEquals("NEOB17", a?.body)
    assertEquals("NEOB17", b?.body)
    assertEquals("NEOB-0017", a?.display)
    assertEquals("NEOB-017", b?.display)
  }

  @Test
  fun `来源判断只在有证据时给出`() {
    assertEquals(SOURCE_METATUBE, SmartMergeCode.sourceOf(mapOf("MetaTube" to "abc")))
    assertEquals(SOURCE_JAVBOSS, SmartMergeCode.sourceOf(mapOf("JavBoss" to "abc")))
    assertEquals(SOURCE_JAVBUS, SmartMergeCode.sourceOf(mapOf("JavBus" to "abc")))
    assertEquals(SOURCE_UNKNOWN, SmartMergeCode.sourceOf(mapOf("Tmdb" to "123")))
  }
}
