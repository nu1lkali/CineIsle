package app.marlboroadvance.mpvex.utils.cache

/**
 * 容量受限的 LRU 缓存，**纯 Kotlin、无Android 依赖**（清单 #20）。
 *
 * 抽出来有两个理由：
 *  1. **可单测**。原来的 [app.marlboroadvance.mpvex.ui.player.controls.components.sheets.LRUBitmapCache]
 *     直接持有 `android.graphics.Bitmap`，本地 JVM 单测里 Bitmap 的方法体是 `Stub!`（直接抛），
 *     所以「淘汰顺序对不对」「替换同key 要不要回收旧值」这些只能靠手点验证 ——
 *     而它们恰恰是**最容易写错**的部分。
 *  2. **淘汰回调与容器解耦**。调用方（Bitmap 缓存）需要在淘汰时 `recycle()`，
 *     但「什么时候淘汰」是纯算法、「淘汰之后做什么」是资源管理，混在一个类里就都没法单独测。
 *
 * ## 语义（这几条是刻意选的，写之前先想清楚）
 *
 * - **accessOrder = true**：读一次也算「最近用过」。缩略图缓存的典型访问模式是
 *   反复 get 同一批 key（Compose 每次重组都 get），没有 accessOrder 的话
 *   容量一满就会按插入顺序把「一直在用的」全淘汰掉。
 * - **替换同 key 时的旧值会走 [onEvicted]**：这是最容易漏的一条 ——
 *   「同一集反复刷新缩略图」每刷一次漏一张真实资源（Bitmap 的 native 像素）。
 * - **不会误回收「同一个实例」**：[onEvicted] 收到的旧值与新值可能是同一对象
 *   （调用方重用了缓存），所以约定由调用方在回调里判 `!==`。
 * - **容量为 0 时 put 什么都不会留下**（每次都被淘汰），这是对的：
 *   「不要缓存」和「缓存但立刻淘汰」等价。
 * - **V 不加 `Any` 约束，允许存 null**：有些缓存需要用 null 表示「查过了，确实没有」
 *   （否则每次读都重新去查一遍）。`containsKey` 能区分「没有这个 key」与「key 在但值是 null」。
 *
 * @param maxSize 最大条目数，必须 > 0
 * @param onEvicted 被移出缓存的**旧值**（不是 key）。会被 `clear()` 与自动淘汰各调一次。
 */
class LruCache<K : Any, V>(
  private val maxSize: Int,
  private val onEvicted: (V) -> Unit = {},
) {
  init {
    require(maxSize > 0) { "maxSize must be > 0, was $maxSize" }
  }

  // 初始容量给 maxSize+1 是 LinkedHashMap resize 的常规做法：
  // 让它在达到 maxSize 时**还没触发扩容**，减少一次 rehash。
  private val cache = object : LinkedHashMap<K, V>(maxSize + 1, 1f, true) {}

  /** 线程安全：Compose 侧可能在不同线程读、后台线程写。 */
  operator fun get(key: K): V? = synchronized(this) { cache[key] }

  operator fun set(key: K, value: V) = synchronized(this) {
    val previous = cache.put(key, value)
    if (previous != null && previous !== value) {
      onEvicted(previous)
    }
    while (cache.size > maxSize) {
      val eldestKey = cache.keys.firstOrNull() ?: break
      val eldest = cache.remove(eldestKey) ?: break
      onEvicted(eldest)
    }
  }

  operator fun contains(key: K): Boolean = synchronized(this) { cache.containsKey(key) }

  fun containsKey(key: K): Boolean = synchronized(this) { cache.containsKey(key) }

  fun size(): Int = synchronized(this) { cache.size }

  fun isEmpty(): Boolean = synchronized(this) { cache.isEmpty() }

  fun keys(): Set<K> = synchronized(this) { cache.keys.toSet() }

  /** 取出但不改变 LRU 次序（不当作「用过」）。 */
  fun peek(key: K): V? =
    synchronized(this) {
      // ⚠️ 不能直接用 `cache[key]`：accessOrder = true 时读一次就会把它挪到队尾，
      // 那就不是 peek 了。LinkedHashMap 没有公开的 peek，只能重建一次视图。
      // 容量不大（几十条），这个开销可以接受。
      val snapshot = LinkedHashMap<K, V>(cache)
      snapshot[key]
    }

  /** 清空并把所有值交给 [onEvicted] 一次。 */
  fun clear() = synchronized(this) {
    val values = cache.values.toList()
    cache.clear()
    // 先 clear 再回调：万一回调里又碰到这个缓存，不会看到半清不明的状态
    values.forEach(onEvicted)
  }
}