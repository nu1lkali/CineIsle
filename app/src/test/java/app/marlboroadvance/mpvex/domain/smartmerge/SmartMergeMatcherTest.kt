package app.marlboroadvance.mpvex.domain.smartmerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMergeMatcherTest {

  private fun item(
    id: String,
    name: String,
    code: String? = "ABC-123",
    body: String? = "ABC123",
    source: String = SOURCE_METATUBE,
    year: Int = 2024,
    people: List<String> = listOf("演员A"),
    providerIds: Map<String, String> = mapOf("MetaTube" to "abc"),
  ) = SmartMergeItem(
    id = id,
    name = name,
    code = code,
    codeBody = body,
    codeConfidence = 0.9,
    source = source,
    productionYear = year,
    people = people,
    providerIds = providerIds,
    runtimeTicks = 7_200_000_000L,
    version = SmartMergeVersionProfile(label = "1080P"),
  )

  @Test
  fun `同番号两个条目生成候选`() {
    val candidates = SmartMergeMatcher.buildCandidates(
      serverId = 1L,
      items = listOf(
        item("1", "ABC-123", source = SOURCE_METATUBE, providerIds = mapOf("MetaTube" to "abc")),
        item("2", "ABC-123 LTX", source = SOURCE_JAVBOSS, providerIds = mapOf("JavBoss" to "xyz")),
      ),
    )
    assertEquals(1, candidates.size)
    assertEquals("ABC-123", candidates.first().code)
    assertEquals(2, candidates.first().itemIds.size)
    assertTrue(candidates.first().confidence >= 40)
  }

  @Test
  fun `演员完全无重合会进入冲突并封顶`() {
    val candidates = SmartMergeMatcher.buildCandidates(
      serverId = 1L,
      items = listOf(
        item("1", "ABC-123", people = listOf("演员A")),
        item("2", "ABC-123", people = listOf("演员B")),
      ),
    )
    assertEquals(1, candidates.size)
    assertTrue(candidates.first().conflicts.isNotEmpty())
    assertTrue(candidates.first().confidence <= 69)
    assertEquals(STATUS_CONFLICT, candidates.first().status)
  }

  @Test
  fun `忽略番号后不再生成候选`() {
    val rule = SmartMergeIgnoreRule(
      id = "r1",
      serverId = 1L,
      type = "code",
      value = "ABC-123",
    )
    val candidates = SmartMergeMatcher.buildCandidates(
      serverId = 1L,
      items = listOf(item("1", "ABC-123"), item("2", "ABC-123 LTX")),
      ignoreRules = listOf(rule),
    )
    assertTrue(candidates.isEmpty())
  }

  @Test
  fun `多版本条目不再推荐`() {
    val candidates = SmartMergeMatcher.buildCandidates(
      serverId = 1L,
      items = listOf(
        item("1", "ABC-123").copy(alreadyMultiVersion = true),
        item("2", "ABC-123 LTX"),
      ),
    )
    assertTrue(candidates.isEmpty())
  }

  @Test
  fun `ProviderIds 单独成组按规则开关生效`() {
    val a = item("1", "无番号 A", code = null, body = null, providerIds = mapOf("JavBus" to "abc123"))
    val b = item("2", "无番号 B", code = null, body = null, providerIds = mapOf("JavBus" to "abc123"))
    val off = SmartMergeMatcher.buildCandidates(1L, listOf(a, b), rules = SmartMergeRules())
    assertTrue(off.isEmpty())
    val on = SmartMergeMatcher.buildCandidates(
      1L,
      listOf(a, b),
      rules = SmartMergeRules(allowProviderOnlyGroups = true),
    )
    assertEquals(1, on.size)
    assertTrue(on.first().code.startsWith("PROVIDER:"))
  }

  @Test
  fun `最低置信度规则会过滤低分候选`() {
    val items = listOf(
      item("1", "ABC-123", people = listOf("演员A")),
      item("2", "ABC-123 LTX", people = listOf("演员A")),
    )
    val low = SmartMergeMatcher.buildCandidates(1L, items, rules = SmartMergeRules(minScore = 40))
    assertTrue(low.isNotEmpty())
    val high = SmartMergeMatcher.buildCandidates(1L, items, rules = SmartMergeRules(minScore = 100))
    assertTrue(high.isEmpty())
  }

  @Test
  fun `手动选择两个条目可创建候选`() {
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = 1L,
      items = listOf(item("1", "ABC-123"), item("2", "ABC-123 中文字幕")),
    )
    assertNotNull(candidate)
    assertEquals(2, candidate!!.itemIds.size)
    assertFalse(candidate.itemIds.isEmpty())
  }

  @Test
  fun `手动选择不同番号会标冲突并封顶`() {
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = 1L,
      items = listOf(
        item("1", "ABC-123"),
        item("2", "XYZ-999", code = "XYZ-999", body = "XYZ999"),
      ),
    )
    assertNotNull(candidate)
    assertTrue(candidate!!.conflicts.isNotEmpty())
    assertTrue(candidate.confidence <= 69)
    assertEquals(STATUS_CONFLICT, candidate.status)
  }

  @Test
  fun `手动选择无番号条目也能组合`() {
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = 1L,
      items = listOf(
        item("1", "无番号 A", code = null, body = null),
        item("2", "无番号 B", code = null, body = null),
      ),
    )
    assertNotNull(candidate)
    assertEquals("手动组合", candidate!!.code)
    assertEquals(2, candidate.itemIds.size)
  }

  @Test
  fun `手动选择混入多版本条目会标冲突`() {
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = 1L,
      items = listOf(
        item("1", "ABC-123"),
        item("2", "ABC-123 4K").copy(alreadyMultiVersion = true),
      ),
    )
    assertNotNull(candidate)
    assertTrue(candidate!!.conflicts.any { it.contains("多版本") })
    assertEquals(STATUS_CONFLICT, candidate.status)
  }

  @Test
  fun `手动选择重复条目会被去重`() {
    val same = item("1", "ABC-123")
    val candidate = SmartMergeMatcher.candidateFromSelection(
      serverId = 1L,
      items = listOf(same, same.copy(name = "ABC-123 另一个名字")),
    )
    assertEquals(null, candidate)
  }
}
