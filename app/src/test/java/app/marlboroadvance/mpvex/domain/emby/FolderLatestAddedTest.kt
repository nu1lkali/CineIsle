package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按文件夹内最新入库时间排序」的纯逻辑测试。
 *
 * 覆盖的是方案里点名要验的场景里**不依赖服务端**的那部分：
 * 路径归因、排序规则、提前终止、结果守恒。
 */
class FolderLatestAddedTest {
  private fun item(
    id: String,
    path: String?,
    created: String?,
    parentId: String? = null,
  ) = EmbyItem(Id = id, Path = path, DateCreated = created, ParentId = parentId)

  private fun folder(
    id: String,
    path: String?,
  ) = EmbyItem(Id = id, Path = path, Type = "Folder")

  // ── 路径规整 ──

  @Test
  fun normalize_unifiesSeparators() {
    // ⚠️ 盘符必须保留：`D:/media/A` 与 `/media/A` 是**两个不同**的位置
    // （前者是被挂载的 Windows 盘，后者是服务端自己看到的路径）。
    // 把盘符吃掉会让本来互不相干的两个目录被当成同一个。
    assertEquals("D:/media/A/x.mkv", normalizePath("D:\\media\\A\\x.mkv"))
    assertEquals("/media/A/x.mkv", normalizePath("\\media\\A\\x.mkv"))
    assertEquals("/media/A/x.mkv", normalizePath("/media//A///x.mkv"))
    assertEquals("/media/A", normalizePath("/media/A/"))
    assertNull(normalizePath(null))
    assertNull(normalizePath("  "))
  }

  // ── 路径边界匹配（最容易写错的一条） ──

  @Test
  fun isUnderPath_respectsSegmentBoundary() {
    assertTrue(isUnderPath("/media/A/x.mkv", "/media/A"))
    assertTrue(isUnderPath("/media/A/sub/x.mkv", "/media/A"))
    // 这一条是整个功能的关键：/media/A 不是 /media/AB 的前缀「目录」
    assertFalse(isUnderPath("/media/AB/x.mkv", "/media/A"))
    // 反过来也一样
    assertFalse(isUnderPath("/media/A/x.mkv", "/media/AB"))
    // 自己不算「在里面」
    assertFalse(isUnderPath("/media/A", "/media/A"))
  }

  @Test
  fun matchChildFolder_picksLongestPrefix() {
    val index = mapOf(
      "/media/A" to "a",
      "/media/A/B" to "ab",
      "/media/AB" to "ab2",
    )
    assertEquals("a", matchChildFolder("/media/A/x.mkv", index))
    assertEquals("ab", matchChildFolder("/media/A/B/x.mkv", index))
    assertEquals("ab2", matchChildFolder("/media/AB/x.mkv", index))
    assertNull(matchChildFolder("/media/AC/x.mkv", index))
    assertNull(matchChildFolder(null, index))
  }

  @Test
  fun matchChildFolder_doesNotMisattributeSimilarPrefix() {
    // 两个目录 `/srv/media/A` 与 `/srv/media/AB`，媒体只在 AB 里
    val children = listOf(
      folder("idA", "/srv/media/A"),
      folder("idAB", "/srv/media/AB"),
    )
    val paths = children.mapNotNull { c -> c.Path?.let { p -> normalizePath(p)?.let { n -> c.Id!! to n } } }.toMap()
    val scan = FolderLatestScan(listOf("idA", "idAB"), paths)
    scan.feed(listOf(item("m1", "/srv/media/AB/movie.mkv", "2026-01-01T00:00:00Z")))
    val newest = scan.toOrder().newestByFolderId
    assertNull(newest["idA"])
    assertEquals("2026-01-01T00:00:00Z", newest["idAB"])
  }

  // ── 深埋的媒体归到直接子目录 ──

  @Test
  fun deepMediaMapsToDirectChild() {
    val children = listOf(folder("A", "/media/A"), folder("B", "/media/B"))
    val paths = mapOf("A" to "/media/A", "B" to "/media/B")
    val scan = FolderLatestScan(listOf("A", "B"), paths)
    scan.feed(
      listOf(
        item("m", "/media/A/2024/电影/片子.mkv", "2024-05-01T00:00:00Z", parentId = "无名季"),
      ),
    )
    assertEquals("2024-05-01T00:00:00Z", scan.toOrder().newestByFolderId["A"])
  }

  // ── 没有 Path 时退回 ParentId ──

  @Test
  fun fallsBackToParentIdWhenPathMissing() {
    val children = listOf(folder("A", "/media/A"), folder("B", "/media/B"))
    val paths = mapOf("A" to "/media/A", "B" to "/media/B")
    val scan = FolderLatestScan(listOf("A", "B"), paths)
    scan.feed(listOf(item("m", null, "2025-03-03T00:00:00Z", parentId = "B")))
    assertEquals("2025-03-03T00:00:00Z", scan.toOrder().newestByFolderId["B"])
  }

  @Test
  fun unattributableMediaIsIgnored() {
    // 既没 Path、ParentId 也不在这一层：绝不能瞎归
    val scan = FolderLatestScan(listOf("A"), mapOf("A" to "/media/A"))
    scan.feed(listOf(item("m", null, "2025-01-01T00:00:00Z", parentId = "别处")))
    assertTrue(scan.toOrder().newestByFolderId.isEmpty())
  }

  // ── 取最新的一条 ──

  @Test
  fun keepsNewestAndIgnoresOlder() {
    val scan = FolderLatestScan(listOf("A"), mapOf("A" to "/media/A"))
    // 服务端已降序：第一条就该被采纳，后面更旧的必须丢掉（不能覆盖）
    scan.feed(
      listOf(
        item("m1", "/media/A/new.mkv", "2026-06-01T00:00:00Z"),
        item("m2", "/media/A/old.mkv", "2019-01-01T00:00:00Z"),
      ),
    )
    assertEquals("2026-06-01T00:00:00Z", scan.toOrder().newestByFolderId["A"])
  }

  @Test
  fun missingDateCreatedIsSkipped() {
    val scan = FolderLatestScan(listOf("A"), mapOf("A" to "/media/A"))
    scan.feed(listOf(item("m1", "/media/A/x.mkv", null)))
    assertTrue(scan.toOrder().newestByFolderId.isEmpty())
  }

  // ── 排序规则 ──

  @Test
  fun order_mediaFirstEmptyLastOriginalOrderPreserved() {
    val original = listOf("empty1", "old", "new", "empty2", "mid")
    val newest = mapOf(
      "old" to "2019-01-01T00:00:00Z",
      "new" to "2026-06-01T00:00:00Z",
      "mid" to "2023-03-03T00:00:00Z",
    )
    assertEquals(
      listOf("new", "mid", "old", "empty1", "empty2"),
      orderIds(original, newest),
    )
  }

  @Test
  fun order_tiesKeepOriginalOrder() {
    val original = listOf("b", "a")
    val newest = mapOf(
      "a" to "2026-01-01T00:00:00Z",
      "b" to "2026-01-01T00:00:00Z",
    )
    // 同一时间戳：保持服务端原顺序，不因为名字前后而换位
    assertEquals(listOf("b", "a"), orderIds(original, newest))
  }

  // ── 提前终止 ──

  @Test
  fun scanBecomesCompleteOnceAllChildrenTimed() {
    val scan = FolderLatestScan(listOf("A", "B"), mapOf("A" to "/media/A", "B" to "/media/B"))
    assertFalse(scan.isComplete)
    scan.feed(listOf(item("m1", "/media/A/a.mkv", "2026-01-01T00:00:00Z")))
    assertFalse(scan.isComplete)
    scan.feed(listOf(item("m2", "/media/B/b.mkv", "2026-01-02T00:00:00Z")))
    assertTrue(scan.isComplete)
  }

  @Test
  fun feedReportsFilledCount() {
    val scan = FolderLatestScan(listOf("A"), mapOf("A" to "/media/A"))
    assertEquals(0, scan.feed(listOf(item("m0", "/media/Z/z.mkv", "2026-01-01T00:00:00Z"))))
    assertEquals(1, scan.feed(listOf(item("m1", "/media/A/a.mkv", "2026-01-01T00:00:00Z"))))
    // 同一目录的第二条不会再「填」一次
    assertEquals(0, scan.feed(listOf(item("m2", "/media/A/b.mkv", "2018-01-01T00:00:00Z"))))
  }

  // ── 重排不增删 ──

  @Test
  fun applyOrderNeverDropsOrAddsItems() {
    val folders = listOf(folder("e1", null), folder("a", "/media/A"), folder("e2", null))
    val order = FolderLatestOrder(
      orderedIds = listOf("a", "e1", "e2"),
      newestByFolderId = mapOf("a" to "2026-01-01T00:00:00Z"),
      complete = true,
    )
    val sorted = applyFolderLatestOrder(folders, order) { it.Id }
    assertEquals(3, sorted.size)
    assertEquals(listOf("a", "e1", "e2"), sorted.map { it.Id })
  }

  @Test
  fun applyOrderKeepsUnknownItemsAtEnd() {
    val items = listOf("x", "a", "y")
    val order = FolderLatestOrder(listOf("a"), emptyMap(), complete = true)
    // 扫描期间新增的两条（x / y）排在后面，且保持原相对顺序
    assertEquals(listOf("a", "x", "y"), applyFolderLatestOrder(items, order) { it })
  }

  @Test
  fun applyOrderKeepsUnknownRelativeOrder() {
    // 清单被截断的情形：a / b 有结论，x / y / z 没进清单。
    // 三条都必须留下，且彼此顺序不变 —— 不能因为「排不上」就丢掉。
    val items = listOf("x", "b", "y", "a", "z")
    val order = FolderLatestOrder(listOf("a", "b"), emptyMap(), complete = false)
    assertEquals(
      listOf("a", "b", "x", "y", "z"),
      applyFolderLatestOrder(items, order) { it },
    )
  }

  @Test
  fun applyOrderHandlesExplicitDateValues() {
    // 端到端：三个目录，两种时间 + 一个空目录，验证「新 → 旧 → 空」
    val children = listOf(
      folder("old", "/m/old"),
      folder("empty", "/m/empty"),
      folder("new", "/m/new"),
    )
    val paths = mapOf("old" to "/m/old", "empty" to "/m/empty", "new" to "/m/new")
    val scan = FolderLatestScan(children.map { it.Id!! }, paths)
    scan.feed(
      listOf(
        item("1", "/m/new/a.mkv", "2026-06-01T00:00:00Z"),
        item("2", "/m/old/a.mkv", "2019-01-01T00:00:00Z"),
      ),
    )
    val order = scan.toOrder()
    assertEquals(listOf("new", "old", "empty"), applyFolderLatestOrder(children, order) { it.Id }.map { it.Id })
  }

  /**
   * 升序（用户点了排序面板的箭头）。
   *
   * 期望：老目录在前、新目录在后，**但空目录仍然在最后** ——
   * 空目录不是「最早」的，它是「没有这个信息」。
   */
  @Test
  fun applyOrderAscendingKeepsEmptyLast() {
    val children = listOf(
      folder("old", "/m/old"),
      folder("empty", "/m/empty"),
      folder("new", "/m/new"),
    )
    val paths = mapOf("old" to "/m/old", "empty" to "/m/empty", "new" to "/m/new")
    val scan = FolderLatestScan(children.map { it.Id!! }, paths)
    scan.feed(
      listOf(
        item("1", "/m/new/a.mkv", "2026-06-01T00:00:00Z"),
        item("2", "/m/old/a.mkv", "2019-01-01T00:00:00Z"),
      ),
    )
    val order = scan.toOrder()
    val sorted = applyFolderLatestOrder(children, order, ascending = true) { it.Id }
    assertEquals(listOf("old", "new", "empty"), sorted.map { it.Id })
  }

  @Test
  fun applyOrderAscendingWithTwoEmptyFolders() {
    val children = listOf(
      folder("e1", "/m/e1"),
      folder("b", "/m/b"),
      folder("e2", "/m/e2"),
      folder("a", "/m/a"),
    )
    val paths = mapOf("e1" to "/m/e1", "b" to "/m/b", "e2" to "/m/e2", "a" to "/m/a")
    val scan = FolderLatestScan(children.map { it.Id!! }, paths)
    scan.feed(
      listOf(
        item("1", "/m/a/a.mkv", "2026-01-01T00:00:00Z"),
        item("2", "/m/b/b.mkv", "2020-01-01T00:00:00Z"),
      ),
    )
    val order = scan.toOrder()
    // 降序：a(2026) → b(2020) → 空目录（保持原相对顺序 e1、e2）
    assertEquals(listOf("a", "b", "e1", "e2"), applyFolderLatestOrder(children, order) { it.Id }.map { it.Id })
    // 升序：b(2020) → a(2026) → 空目录不变
    assertEquals(
      listOf("b", "a", "e1", "e2"),
      applyFolderLatestOrder(children, order, ascending = true) { it.Id }.map { it.Id },
    )
  }
}
