package app.marlboroadvance.mpvex.domain.dislike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DislikeStore] 的行为测试。
 *
 * ## 为什么这个类必须有测试
 *
 * 它是整个「标记为不喜欢」功能唯一的真相来源，四个入口（详情页 / mpv / GSY / 视界流）
 * 都只认它算出来的状态。而它的失败模式**全是静默的**：
 * · 去重漏了 ⇒ 同一个片子在列表里出现两行，用户以为标记了两次；
 * · `active` 判错 ⇒ 「取消了但按钮还是亮的」，而界面上没有任何报错；
 * · 竞态 ⇒ 「批量取消只取消了一个」，只在快速连点时偶发。
 *
 * 这些都不会崩、不会打日志，只能靠测试钉死。
 */
class DislikeStoreTest {

  private fun key(id: String, server: Long = 1L) = DislikeKey(serverId = server, itemId = id)

  private fun mark(
    store: DislikeStore,
    id: String,
    reason: String? = null,
    server: Long = 1L,
    at: Long = 1_000L,
  ) = store.mark(key(id, server), title = "片名-$id", reason = reason, markedAt = at)

  // ── 基本标记 / 取消 ───────────────────────────────────────────────

  @Test
  fun `未标记时 isDisliked 为 false`() {
    val store = DislikeStore()
    assertFalse(store.isDisliked(key("a")))
  }

  @Test
  fun `标记后 isDisliked 为 true 且 markOf 能取回`() {
    val store = DislikeStore()
    mark(store, "a", reason = "剧情烂")

    assertTrue(store.isDisliked(key("a")))
    val got = store.markOf(key("a"))
    assertNotNull(got)
    assertEquals("剧情烂", got!!.reason)
    assertEquals("片名-a", got.title)
  }

  @Test
  fun `取消标记后 isDisliked 为 false`() {
    val store = DislikeStore()
    mark(store, "a")
    assertTrue(store.deactivate(key("a")))

    assertFalse(store.isDisliked(key("a")))
  }

  // ── 「取消标记 ≠ 删除」：这是用户明确要求的两件事 ─────────────────

  @Test
  fun `取消标记保留记录与原因`() {
    val store = DislikeStore()
    mark(store, "a", reason = "节奏太慢")
    store.deactivate(key("a"))

    // 记录还在 ⇒ 重新标记时能带出上次写的理由
    val got = store.markOf(key("a"))
    assertNotNull("取消标记必须保留记录", got)
    assertEquals("节奏太慢", got!!.reason)
    assertFalse("但状态是未标记", got.active)
  }

  @Test
  fun `删除会抹掉整条记录`() {
    val store = DislikeStore()
    mark(store, "a", reason = "剧情烂")
    assertTrue(store.purge(key("a")))

    assertNull("删除后连 markOf 都取不到", store.markOf(key("a")))
    assertTrue(store.all.value.isEmpty())
  }

  @Test
  fun `删除不存在的条目返回 false`() {
    val store = DislikeStore()
    assertFalse(store.purge(key("nope")))
    assertFalse(store.deactivate(key("nope")))
  }

  @Test
  fun `对已取消的条目再次取消返回 false`() {
    val store = DislikeStore()
    mark(store, "a")
    assertTrue(store.deactivate(key("a")))
    // 幂等：已经不在标记态，再取消没有变化
    assertFalse(store.deactivate(key("a")))
  }

  // ── 去重：同一视频重复标记只能有一条 ──────────────────────────────

  @Test
  fun `同一视频重复标记不会产生第二条`() {
    val store = DislikeStore()
    mark(store, "a")
    mark(store, "a")
    mark(store, "a")

    assertEquals(1, store.all.value.size)
  }

  @Test
  fun `不同 serverId 的同一 itemId 视为两条`() {
    val store = DislikeStore()
    mark(store, "same-id", server = 1L)
    mark(store, "same-id", server = 2L)

    // 这是复合主键存在的全部理由：不同 Emby 服务器的 itemId 各自独立，撞车是真实风险
    assertEquals(2, store.all.value.size)
    assertTrue(store.isDisliked(key("same-id", server = 1L)))
    assertTrue(store.isDisliked(key("same-id", server = 2L)))
  }

  // ── mark 的字段合并规则 ──────────────────────────────────────────

  @Test
  fun `reason 传 null 时沿用旧备注`() {
    val store = DislikeStore()
    mark(store, "a", reason = "演员尬演")
    // 重复点一次标记但没填理由 —— 不能把上次写的理由抹掉
    mark(store, "a", reason = null)

    assertEquals("演员尬演", store.markOf(key("a"))!!.reason)
  }

  @Test
  fun `重新标记会刷新时间戳并恢复激活态`() {
    val store = DislikeStore()
    mark(store, "a", at = 100L)
    store.deactivate(key("a"))
    mark(store, "a", at = 200L)

    val got = store.markOf(key("a"))!!
    assertTrue(got.active)
    assertEquals(200L, got.markedAt)
  }

  @Test
  fun `取消标记不改动 markedAt`() {
    val store = DislikeStore()
    mark(store, "a", at = 500L)
    store.deactivate(key("a"))

    // markedAt 记的是「什么时候标记的」；挪用它会让管理页时间显示掩盖原始标记时间
    assertEquals(500L, store.markOf(key("a"))!!.markedAt)
  }

  @Test
  fun `标题为空时沿用旧标题`() {
    val store = DislikeStore()
    mark(store, "a")
    store.mark(key("a"), title = "", reason = "x", markedAt = 1L)

    assertEquals("片名-a", store.markOf(key("a"))!!.title)
  }

  // ── 快照查询（用户点名的复杂度要求） ──────────────────────────────

  @Test
  fun `snapshotOf 只回传命中的条目`() {
    val store = DislikeStore()
    mark(store, "a")
    mark(store, "b")
    mark(store, "c")

    val snap = store.snapshotOf(listOf(key("a"), key("c"), key("不存在")))

    assertEquals(2, snap.size)
    assertNotNull(snap[key("a")])
    assertNotNull(snap[key("c")])
  }

  @Test
  fun `snapshotOf 空入参返回空表`() {
    val store = DislikeStore()
    mark(store, "a")
    assertTrue(store.snapshotOf(emptyList()).isEmpty())
  }

  // ── 批量操作 ─────────────────────────────────────────────────────

  @Test
  fun `deactivateAll 只数真正处于标记态的`() {
    val store = DislikeStore()
    mark(store, "a")
    mark(store, "b")
    mark(store, "c")
    store.deactivate(key("b"))

    val n = store.deactivateAll(listOf(key("a"), key("b"), key("c")))

    // b 本来就已取消，不该被重复计数
    assertEquals(2, n)
    assertEquals(3, store.all.value.size)
  }

  @Test
  fun `purgeAll 连已取消的记录一起删`() {
    val store = DislikeStore()
    mark(store, "a")
    mark(store, "b")
    store.deactivate(key("a"))

    val n = store.purgeAll(listOf(key("a"), key("b")))

    assertEquals(2, n)
    assertTrue(store.all.value.isEmpty())
  }

  @Test
  fun `批量入参为空时直接返回 0 且不触发回调`() {
    val store = DislikeStore()
    var mutations = 0
    store.onMutate = { mutations++ }
    mark(store, "a")
    mutations = 0

    assertEquals(0, store.deactivateAll(emptyList()))
    assertEquals(0, store.purgeAll(emptyList()))
    assertEquals("没有变更就不该回调（否则会白落一次盘）", 0, mutations)
  }

  // ── 列表顺序 ─────────────────────────────────────────────────────

  @Test
  fun `all 按标记时间倒序`() {
    val store = DislikeStore()
    mark(store, "old", at = 100L)
    mark(store, "new", at = 300L)
    mark(store, "mid", at = 200L)

    // 注意断言的是 title（mark() 辅助函数里 title = "片名-$id"），不是裸 id
    assertEquals(listOf("片名-new", "片名-mid", "片名-old"), store.all.value.map { it.title })
  }

  @Test
  fun `loadAll 后顺序同样按时间倒序`() {
    val store = DislikeStore()
    store.loadAll(
      listOf(
        DislikeMark(key("a"), "A", "", 100L),
        DislikeMark(key("b"), "B", "", 300L),
      ),
    )

    assertEquals(listOf("B", "A"), store.all.value.map { it.title })
    assertTrue(store.isDisliked(key("b")))
  }

  // ── clear ────────────────────────────────────────────────────────

  @Test
  fun `clear 清空一切`() {
    val store = DislikeStore()
    mark(store, "a")
    mark(store, "b")

    assertEquals(2, store.clear())
    assertTrue(store.all.value.isEmpty())
    assertEquals("重复 clear 返回 0", 0, store.clear())
  }

  // ── 回调契约 ─────────────────────────────────────────────────────

  @Test
  fun `onMutate 收到的是变更后的完整列表`() {
    val store = DislikeStore()
    var last: List<DislikeMark> = emptyList()
    store.onMutate = { last = it }

    mark(store, "a", reason = "烂")
    assertEquals(1, last.size)
    assertEquals("烂", last[0].reason)
  }

  // ── 并发：deactivate 必须整体加锁，否则「只取消了一个」 ──────────────

  @Test
  fun `并发取消多个条目不会互相覆盖`() {
    val store = DislikeStore()
    val ids = (1..50).map { "item-$it" }
    ids.forEach { mark(store, it) }

    val threads =
      ids.map { id ->
        Thread { repeat(20) { store.deactivate(key(id)) } }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    // 若判定与改写不在同一锁内，后写的会拿旧列表覆盖先写的，最终只取消了一部分
    assertEquals(50, store.all.value.count { !it.active })
  }

  @Test
  fun `并发标记同一 key 最终仍只有一条`() {
    val store = DislikeStore()
    val threads =
      (1..20).map {
        Thread { repeat(25) { mark(store, "hot", reason = "r$it") } }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertEquals(1, store.all.value.size)
    assertTrue(store.isDisliked(key("hot")))
  }
}
