package app.marlboroadvance.mpvex.domain.emby

/**
 * 首页「媒体库」卡片的**置顶 / 顺序号**规则。
 *
 * 这里只做纯计算（不碰 UI、不碰存储）。存储由 `BrowserPreferences` 负责，
 * 这样同一套规则在界面、持久化、以及将来的别处入口都能复用，边界情况也便于单独推演。
 *
 * ## 模型
 * - **置顶**：最多 [MAX_PINNED] 个。列表里**下标 0 就是第一顺位** ——
 *   「最后置顶的排最前」，所以置顶动作是把 Id 插到表头。
 * - **序号**：每个库一个 ≥ 1 的整数，同一服务器内不重复。
 *   新出现的库自动取「当前最大号 + 1」，也就是排在最后，不打乱用户已经排好的顺序。
 *
 * ## 显示顺序
 * 1. 置顶的排在最前，彼此按置顶先后（最后置顶的第一）。
 * 2. 其余按序号升序；没有序号的排在有序号之后（兜底，正常不会出现）。
 * 3. 因此：**有置顶库时，「序号 1」的库也会被顶到所有置顶库之后** ——
 *    置顶的优先级永远高于序号，两个是各自独立的轴。
 *
 * ## 序号冲突
 * 把一个库的序号改成 N，而 N 已被别的库占用 → **两者互换**：
 * 它拿到 N，原来占着 N 的那个拿到它原来的号；若它原本没有号，占位者就变成「无号」。
 */
object LibraryOrdering {

  /** 最多能置顶几个媒体库 */
  const val MAX_PINNED = 2

  // ── 序列化：都是逗号分隔的小串，库 Id 是十六进制，不含逗号 ──

  fun parsePins(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

  fun serializePins(ids: List<String>): String = ids.joinToString(",")

  /** 形如 `id:1,id:3,id:2` */
  fun parseNumbers(raw: String?): Map<String, Int> =
    raw.orEmpty()
      .split(',')
      .mapNotNull { entry ->
        val sep = entry.indexOf(':')
        if (sep <= 0) return@mapNotNull null
        val id = entry.substring(0, sep).trim()
        val value = entry.substring(sep + 1).trim().toIntOrNull() ?: return@mapNotNull null
        if (id.isEmpty() || value < 1) null else id to value
      }
      .toMap()

  fun serializeNumbers(numbers: Map<String, Int>): String =
    numbers.entries.sortedBy { it.value }.joinToString(",") { "${it.key}:${it.value}" }

  // ── 排序 ──

  /**
   * 按「置顶优先、其次序号」给出显示顺序。
   *
   * 用的是稳定排序（Kotlin 的 sortedBy 底层是 TimSort）：序号相同或都没号时，
   * 保持服务器返回的原始顺序，不会因为一次重组就自己打乱。
   */
  fun sortLibraries(
    libraries: List<EmbyItem>,
    pins: List<String>,
    numbers: Map<String, Int>,
  ): List<EmbyItem> {
    if (libraries.size < 2) return libraries
    val byId = libraries.associateBy { it.Id }
    // 按置顶表的顺序取，顺带滤掉已经不在库列表里的陈旧 Id
    val pinned = pins.mapNotNull { byId[it] }
    val pinnedIds = pinned.mapNotNull { it.Id }.toHashSet()
    val rest = libraries
      .filter { it.Id !in pinnedIds }
      .sortedBy { numbers[it.Id] ?: Int.MAX_VALUE }
    return pinned + rest
  }

  // ── 置顶 ──

  /**
   * 置顶一个库。返回 (新的置顶表, 被挤出置顶的库 Id 或 null)。
   *
   * 已经在置顶表里再置顶 → 提到第一顺位（幂等，不会重复占名额）。
   * 超过 [MAX_PINNED] → 挤掉**最早置顶**的那个（表尾），保证动作永远可用，
   * 不用先跑去别的库取消置顶再回来。
   */
  fun pin(pins: List<String>, id: String): Pair<List<String>, String?> {
    val next = listOf(id) + pins.filter { it != id }
    return if (next.size <= MAX_PINNED) {
      next to null
    } else {
      next.take(MAX_PINNED) to next[MAX_PINNED]
    }
  }

  fun unpin(pins: List<String>, id: String): List<String> = pins.filter { it != id }

  // ── 序号 ──

  /**
   * 给还没有序号的库补号：从「当前最大号 + 1」开始，按传入顺序依次发。
   *
   * 这样新增的媒体库一定落在队尾，也不会和已有序号撞车；用户想调整再手动改。
   */
  fun ensureNumbers(libraryIds: List<String>, numbers: Map<String, Int>): Map<String, Int> {
    val missing = libraryIds.filter { numbers[it] == null }
    if (missing.isEmpty()) return numbers
    var next = (numbers.values.maxOrNull() ?: 0) + 1
    val out = numbers.toMutableMap()
    missing.forEach { out[it] = next++ }
    return out
  }

  /**
   * 把 [targetId] 的序号设为 [newNumber]，占用者与它互换。
   *
   * 非法输入（< 1）直接原样返回，让界面上的钳制逻辑说话，这里不再造第二种行为。
   */
  fun assignNumber(
    numbers: Map<String, Int>,
    targetId: String,
    newNumber: Int,
  ): Map<String, Int> {
    if (newNumber < 1) return numbers
    val old = numbers[targetId]
    if (old == newNumber) return numbers
    val out = numbers.toMutableMap()
    val occupant = numbers.entries
      .firstOrNull { it.value == newNumber && it.key != targetId }
      ?.key
    if (occupant != null) {
      if (old != null) out[occupant] = old else out.remove(occupant)
    }
    out[targetId] = newNumber
    return out
  }

  /** 当前这批库里最大的序号（界面上的调节上限用它，避免越点越大只跑到空档里） */
  fun maxNumber(numbers: Map<String, Int>, ids: Collection<String>): Int =
    ids.mapNotNull { numbers[it] }.maxOrNull() ?: 1
}
