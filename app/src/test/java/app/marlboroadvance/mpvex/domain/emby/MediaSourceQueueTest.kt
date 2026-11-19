package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多版本连播队列的排序 / 轮转单测。
 *
 * 重点盯住用户报的那类现象：**第 1 集之后跳到第 8 集再回到第 2 集** ——
 * 根因就是「按字典序排数字」（"10" < "2"）与「没有稳定兜底」。
 */
class MediaSourceQueueTest {
  private fun entry(
    id: String,
    title: String,
    path: String? = null,
  ) = VersionQueueEntry(id = id, title = title, filePath = path)

  // ── 自然顺序：数字按数值而不是字典 ──

  @Test
  fun `natural order sorts numbers by value`() {
    val sorted = sortVersionEntries(
      listOf(
        entry("1", "第10集"),
        entry("2", "第2集"),
        entry("3", "第8集"),
        entry("4", "第1集"),
      ),
      VersionQueueOrder.NATURAL,
    )
    assertEquals(
      listOf("第1集", "第2集", "第8集", "第10集"),
      sorted.map { it.title },
    )
  }

  @Test
  fun `natural order handles cd part numbers`() {
    val sorted = sortVersionEntries(
      listOf(
        entry("a", "Movie CD10"),
        entry("b", "Movie CD2"),
        entry("c", "Movie CD1"),
      ),
      VersionQueueOrder.NATURAL,
    )
    assertEquals(listOf("Movie CD1", "Movie CD2", "Movie CD10"), sorted.map { it.title })
  }

  @Test
  fun `natural order puts prefix before longer text`() {
    val sorted = sortVersionEntries(
      listOf(entry("1", "版本 2 加长版"), entry("2", "版本 2")),
      VersionQueueOrder.NATURAL,
    )
    // 数值段相同 ⇒ 短的在前；这条只要求「确定性」，不要求业务含义
    assertEquals(listOf("版本 2", "版本 2 加长版"), sorted.map { it.title })
  }

  // ── 字母顺序 A → B → C ──

  @Test
  fun `alphabetical order is a to z ignoring case`() {
    val sorted = sortVersionEntries(
      listOf(entry("1", "C 版"), entry("2", "a 版"), entry("3", "B 版")),
      VersionQueueOrder.ALPHABETICAL,
    )
    assertEquals(listOf("a 版", "B 版", "C 版"), sorted.map { it.title })
  }

  // ── 文件名顺序 ──

  @Test
  fun `file name order uses path base name`() {
    val sorted = sortVersionEntries(
      listOf(
        entry("1", "同名", "/media/movie.CD10.mkv"),
        entry("2", "同名", "/media/movie.CD2.mkv"),
        entry("3", "同名", "/media/movie.CD1.mkv"),
      ),
      VersionQueueOrder.FILE_NAME,
    )
    // 输入顺序 CD10(id1) / CD2(id2) / CD1(id3) ⇒ 按文件名应是 CD1 → CD2 → CD10
    assertEquals(listOf("3", "2", "1"), sorted.map { it.id })
  }

  @Test
  fun `file name order falls back to title when path missing`() {
    val sorted = sortVersionEntries(
      listOf(
        entry("1", "B 版"),
        entry("2", "A 版", null),
      ),
      VersionQueueOrder.FILE_NAME,
    )
    assertEquals(listOf("2", "1"), sorted.map { it.id })
  }

  @Test
  fun `bare file name strips dirs extension and query`() {
    assertEquals("movie", bareFileName("/a/b/movie.mkv"))
    assertEquals("movie", bareFileName("D:\\media\\movie.mp4?token=1"))
    assertEquals("", bareFileName(null))
    assertEquals("", bareFileName("   "))
  }

  // ── 稳定性 / 缺失信息兜底 ──

  @Test
  fun `identical keys keep server order`() {
    val input = listOf(entry("x", "同名"), entry("y", "同名"), entry("z", "同名"))
    val once = sortVersionEntries(input, VersionQueueOrder.NATURAL)
    val twice = sortVersionEntries(once, VersionQueueOrder.NATURAL)
    assertEquals(listOf("x", "y", "z"), once.map { it.id })
    assertEquals(once.map { it.id }, twice.map { it.id })
  }

  @Test
  fun `blank title still produces deterministic order`() {
    val sorted = sortVersionEntries(
      listOf(entry("b", ""), entry("a", "  "), entry("c", "4K")),
      VersionQueueOrder.NATURAL,
    )
    // 空标题不丢、不抛异常；空白排在有名字的前面（码点更小），顺序仍然确定性
    assertEquals(3, sorted.size)
    assertEquals("c", sorted.last().id)
    assertEquals(setOf("a", "b", "c"), sorted.map { it.id }.toSet())
  }

  // ── 轮转：当前项在前，其后严格按规则衔接 ──

  @Test
  fun `queue rotates to current item and keeps rule order after it`() {
    val entries = listOf(
      entry("a", "A"),
      entry("b", "B"),
      entry("c", "C"),
      entry("d", "D"),
    )
    val queue = buildVersionQueue(entries, currentId = "c", order = VersionQueueOrder.NATURAL)
    assertEquals(listOf("c", "d", "a", "b"), queue.map { it.id })
  }

  @Test
  fun `queue keeps sorted order when current id not found`() {
    val entries = listOf(entry("10", "第10集"), entry("2", "第2集"), entry("1", "第1集"))
    val queue = buildVersionQueue(entries, currentId = "missing", order = VersionQueueOrder.NATURAL)
    assertEquals(listOf("1", "2", "10"), queue.map { it.id })
  }

  @Test
  fun `queue of one item is untouched`() {
    val entries = listOf(entry("only", "唯一版本"))
    assertEquals(entries, buildVersionQueue(entries, "only", VersionQueueOrder.NATURAL))
  }

  // ── 自定义顺序 ──

  @Test
  fun `custom order follows explicit list and appends the rest naturally`() {
    val entries = listOf(
      entry("a", "A 版"),
      entry("b", "B 版"),
      entry("c", "C 版"),
      entry("d", "D 版"),
    )
    val queue = buildVersionQueue(
      entries = entries,
      currentId = null,
      order = VersionQueueOrder.CUSTOM,
      customOrder = listOf("d", "b"),
    )
    assertEquals(listOf("d", "b", "a", "c"), queue.map { it.id })
  }

  @Test
  fun `custom order ignores unknown ids`() {
    val entries = listOf(entry("a", "A"), entry("b", "B"))
    val queue = buildVersionQueue(
      entries,
      currentId = null,
      order = VersionQueueOrder.CUSTOM,
      customOrder = listOf("gone", "b"),
    )
    assertEquals(listOf("b", "a"), queue.map { it.id })
  }

  @Test
  fun `parse and encode custom order round trip`() {
    assertEquals(listOf("a", "b"), parseCustomVersionOrder(" a , b ,, a "))
    assertEquals("a,b", encodeCustomVersionOrder(listOf("a", " b ", "a", "")))
    assertTrue(parseCustomVersionOrder(null).isEmpty())
    assertTrue(parseCustomVersionOrder("").isEmpty())
  }

  // ── MediaSource 适配层：Id 缺失 / 标题兜底 ──

  @Test
  fun `version queue entry requires id and falls back to id as title`() {
    assertNull(versionQueueEntry(EmbyMediaSource(Id = null, Name = "x"), "x"))
    assertNull(versionQueueEntry(EmbyMediaSource(Id = "  ", Name = "x"), " "))
    val ok = versionQueueEntry(EmbyMediaSource(Id = "s1", Name = "4K"), "  ")
    assertEquals("s1", ok?.id)
    assertEquals("s1", ok?.title)
  }

  @Test
  fun `media source queue drops unidentified sources and keeps the rest`() {
    val sources = listOf(
      EmbyMediaSource(Id = "s10", Name = "第10版"),
      EmbyMediaSource(Id = null, Name = "坏数据"),
      EmbyMediaSource(Id = "s2", Name = "第2版"),
      EmbyMediaSource(Id = "s1", Name = "第1版"),
    )
    val queue = buildMediaSourceQueue(
      sources = sources,
      currentId = "s2",
      order = VersionQueueOrder.NATURAL,
      labelOf = { it.Name.orEmpty() },
    )
    assertEquals(listOf("s2", "s10", "s1"), queue.map { it.Id })
  }

  @Test
  fun `media source queue does not lose entries when ids repeat`() {
    val sources = listOf(
      EmbyMediaSource(Id = "dup", Name = "A"),
      EmbyMediaSource(Id = "dup", Name = "B"),
    )
    val queue = buildMediaSourceQueue(
      sources = sources,
      currentId = "dup",
      order = VersionQueueOrder.NATURAL,
      labelOf = { it.Name.orEmpty() },
    )
    assertEquals(2, queue.size)
  }

  @Test
  fun `default custom order is the natural order`() {
    val sources = listOf(
      EmbyMediaSource(Id = "s10", Name = "第10版"),
      EmbyMediaSource(Id = "s2", Name = "第2版"),
    )
    assertEquals(
      listOf("s2", "s10"),
      defaultCustomOrder(sources) { it.Name.orEmpty() },
    )
  }

  // ── 剧集连播队列：修「第1集 → 第8集 → 第2集」 ──

  private fun episode(
    id: String,
    season: Int?,
    number: Int?,
    name: String? = null,
  ) = EmbyItem(
    Id = id,
    Name = name ?: "第${number ?: "?"}集",
    Type = "Episode",
    ParentIndexNumber = season,
    IndexNumber = number,
  )

  @Test
  fun `episode queue fixes server disorder`() {
    // 服务端按入库时间返回的乱序：1 → 8 → 2 → 10
    val sorted = sortEpisodesForQueue(
      listOf(
        episode("e1", 1, 1),
        episode("e8", 1, 8),
        episode("e2", 1, 2),
        episode("e10", 1, 10),
      ),
    )
    assertEquals(listOf("e1", "e2", "e8", "e10"), sorted.map { it.Id })
  }

  @Test
  fun `episode queue sorts by season before number`() {
    val sorted = sortEpisodesForQueue(
      listOf(
        episode("s2e1", 2, 1),
        episode("s1e5", 1, 5),
        episode("s1e1", 1, 1),
      ),
    )
    assertEquals(listOf("s1e1", "s1e5", "s2e1"), sorted.map { it.Id })
  }

  @Test
  fun `episode queue keeps unnumbered extras after numbered ones`() {
    val sorted = sortEpisodesForQueue(
      listOf(
        episode("extra", 1, null, "花絮"),
        episode("e2", 1, 2),
        episode("e1", 1, 1),
      ),
    )
    assertEquals(listOf("e1", "e2", "extra"), sorted.map { it.Id })
  }

  @Test
  fun `episode queue falls back to natural name order`() {
    val sorted = sortEpisodesForQueue(
      listOf(
        episode("a", null, null, "第10集"),
        episode("b", null, null, "第2集"),
        episode("c", null, null, "第1集"),
      ),
    )
    assertEquals(listOf("c", "b", "a"), sorted.map { it.Id })
  }

  @Test
  fun `episode queue is stable for identical keys`() {
    val input = listOf(
      episode("x", 1, 3, "同名"),
      episode("y", 1, 3, "同名"),
      episode("z", 1, 3, "同名"),
    )
    val once = sortEpisodesForQueue(input)
    val twice = sortEpisodesForQueue(once)
    assertEquals(listOf("x", "y", "z"), once.map { it.Id })
    assertEquals(once.map { it.Id }, twice.map { it.Id })
  }

  @Test
  fun `episode queue single item untouched`() {
    val only = listOf(episode("e1", 1, 1))
    assertEquals(only, sortEpisodesForQueue(only))
  }

  @Test
  fun `episode playlist slice starts at current and stays ordered`() {
    // 模拟 EmbyViewModel.buildEpisodePlaylist：先排序，再从当前集切到最后
    val fetched = listOf(
      episode("e1", 1, 1),
      episode("e8", 1, 8),
      episode("e2", 1, 2),
      episode("e10", 1, 10),
      episode("e3", 1, 3),
    )
    val sorted = sortEpisodesForQueue(fetched)
    val index = sorted.indexOfFirst { it.Id == "e2" }
    val queue = sorted.subList(index, sorted.size)
    assertEquals(listOf("e2", "e3", "e8", "e10"), queue.map { it.Id })
  }
}
