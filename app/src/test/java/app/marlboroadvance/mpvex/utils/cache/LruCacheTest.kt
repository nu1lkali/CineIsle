package app.marlboroadvance.mpvex.utils.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LruCache] 的纯逻辑测试（清单 #20）。
 *
 * ## 为什么值得单独测
 *
 * 这个类是被刻意抽出来的：它的调用方 [LRUBitmapCache] 持有 `android.graphics.Bitmap`，
 * 而本地 JVM 单测拿到的 `android.jar` 只有方法签名、方法体一律抛 `Stub!` ——
 * 也就是说 `Bitmap.recycle()` / `isRecycled` 在单测里**根本调不动**，
 * 「淘汰有没有真的回收」只能靠真机手点。
 *
 * 抽成无 Android 依赖的泛型容器后，淘汰决策可以被完整钉死：
 * 淘汰顺序、替换同 key 是否回调、`clear` 是否把值全部交出去、null 值能否缓存。
 * 这些恰好是**写错之后不会崩、只会慢慢漏**的地方 —— 最需要测试的一类。
 */
class LruCacheTest {

  // ── 基本读写 ─────────────────────────────────────────────────────────────

  @Test
  fun `存进去能读出来`() {
    val cache = LruCache<String, String>(maxSize = 4)
    cache["a"] = "A"
    assertEquals("A", cache["a"])
    assertEquals(1, cache.size())
  }

  @Test
  fun `没存过的 key 读出来是 null`() {
    val cache = LruCache<String, String>(maxSize = 4)
    assertNull(cache["missing"])
  }

  @Test
  fun `containsKey 区分 键不存在 与 值就是 null`() {
    val cache = LruCache<String, String?>(maxSize = 4)
    assertFalse(cache.containsKey("a"))

    // ⚠️ 这一条是 LruCache 允许 V 为可空的理由：
    // 「查过了，确实没有值」和「压根没查过」必须能区分，
    // 否则上层每次读都会重新去查一遍（缩略图场景 = 每次重组都读一次 MediaStore）。
    cache["a"] = null
    assertTrue(cache.containsKey("a"))
    assertNull(cache["a"])
  }

  @Test
  fun `空缓存的 isEmpty 是真的`() {
    val cache = LruCache<String, String>(maxSize = 4)
    assertTrue(cache.isEmpty())
    cache["a"] = "A"
    assertFalse(cache.isEmpty())
  }

  // ── 容量与淘汰 ───────────────────────────────────────────────────────────

  @Test
  fun `没超容量时不淘汰`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 3) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    cache["c"] = "C"

    assertEquals(0, evicted.size)
    assertEquals(3, cache.size())
  }

  @Test
  fun `超容量时淘汰最久没用的`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    cache["c"] = "C" // 容量满了，淘汰 a

    assertEquals(listOf("A"), evicted)
    assertNull(cache["a"])
    assertEquals("B", cache["b"])
    assertEquals("C", cache["c"])
  }

  @Test
  fun `读一次算用过 不会被淘汰`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    cache["a"] // 访问 a → a 变成最近用过，b 才是最久没用的
    cache["c"] = "C"

    assertEquals(listOf("B"), evicted)
    assertEquals("A", cache["a"])
    assertNull(cache["b"])
  }

  @Test
  fun `peek 不改变淘汰次序`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    assertEquals("A", cache.peek("a")) // peek 不算「用过」
    cache["c"] = "C"

    // a 因为被 peek 过而**没有**被挪到队尾，所以最久没用的还是 a
    assertEquals(listOf("A"), evicted)
  }

  @Test
  fun `一次塞进超量条目 会全部按序淘汰`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    cache["c"] = "C"
    cache["d"] = "D"
    cache["e"] = "E"

    // 依次淘汰 a、b、c，留在缓存里的必须是最后两个 d、e
    assertEquals(listOf("A", "B", "C"), evicted)
    assertEquals(2, cache.size())
    assertEquals(setOf("d", "e"), cache.keys())
  }

  // ── 替换同一个 key ───────────────────────────────────────────────────────

  @Test
  fun `替换同一个 key 会把旧值交给淘汰回调`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 4) { evicted += it }

    cache["a"] = "旧"
    cache["a"] = "新"

    // ⚠️ 这条是真实的泄漏路径：「同一集反复刷新缩略图」每刷一次漏一张 512×512。
    assertEquals(listOf("旧"), evicted)
    assertEquals("新", cache["a"])
    assertEquals(1, cache.size())
  }

  @Test
  fun `替换成同一个实例不回调`() {
    val evicted = mutableListOf<StringBuilder>()
    val shared = StringBuilder("同一个实例")
    val cache = LruCache<String, StringBuilder>(maxSize = 4) { evicted += it }

    cache["a"] = shared
    cache["a"] = shared

    // 回调里会做 recycle()，把同一个实例回收两次是灾难（第二次抛 IllegalStateException）
    assertEquals(0, evicted.size)
    assertEquals(1, cache.size())
  }

  @Test
  fun `替换不会额外占用容量`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "1"
    cache["a"] = "2"
    cache["a"] = "3"

    assertEquals(listOf("1", "2"), evicted)
    assertEquals(1, cache.size())
  }

  // ── clear ────────────────────────────────────────────────────────────────

  @Test
  fun `clear 会把所有值交给淘汰回调`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 10) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"
    cache["c"] = "C"
    cache.clear()

    assertEquals(setOf("A", "B", "C"), evicted.toSet())
    assertEquals(0, cache.size())
    assertTrue(cache.isEmpty())
  }

  @Test
  fun `clear 空缓存不回调`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 10) { evicted += it }

    cache.clear()

    assertEquals(0, evicted.size)
  }

  @Test
  fun `clear 之后还能继续用`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 2) { evicted += it }

    cache["a"] = "A"
    cache.clear()
    cache["b"] = "B"

    assertEquals(listOf("A"), evicted)
    assertEquals("B", cache["b"])
  }

  // ── 构造参数 ─────────────────────────────────────────────────────────────

  @Test(expected = IllegalArgumentException::class)
  fun `容量为零直接拒绝`() {
    // 不拒绝的话就是一个「put 什么都被立刻淘汰」的静默失效缓存 ——
    // 比报错难查得多，因为调用方会以为缓存生效了。
    LruCache<String, String>(maxSize = 0)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `负容量直接拒绝`() {
    LruCache<String, String>(maxSize = -1)
  }

  @Test
  fun `容量一就是只留最新一个`() {
    val evicted = mutableListOf<String>()
    val cache = LruCache<String, String>(maxSize = 1) { evicted += it }

    cache["a"] = "A"
    cache["b"] = "B"

    assertEquals(listOf("A"), evicted)
    assertEquals(1, cache.size())
    assertEquals("B", cache["b"])
  }
}