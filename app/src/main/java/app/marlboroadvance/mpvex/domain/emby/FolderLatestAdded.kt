package app.marlboroadvance.mpvex.domain.emby

// ════════════════════════════════════════════════════════════════════════
// FolderLatestAdded —— 「按文件夹内最新入库时间排序」的纯逻辑
// ════════════════════════════════════════════════════════════════════════
//
// ## 为什么不是「服务端直接排」
// Emby 的 `/Items?SortBy=DateCreated` 排的是**条目自己**的入库时间。文件夹条目
// 自己的 `DateCreated` 是「这个目录被扫描到的时刻」——一次性写进库，此后往里加
// 多少片子都不会变。所以服务端排序给出的「最新」，实际上是「这个目录什么时候
// 第一次被建出来」，与用户想要的「哪个目录刚进了新片」完全不是一回事。
//
// ⇒ 只能客户端算：递归拉一遍该层所有的媒体条目（服务端已按 DateCreated 降序），
//   再把每条媒体「归」到它的直接子目录上，取每个目录中最新的一条。
//
// ## 归因为什么用路径而不是 ParentId
// 一条媒体可能埋在 `A/B/C/D.mkv` 这样三四层下，它的 `ParentId` 指向 `C`，
// 而界面上这一层摆的是 `A`。要把 `D.mkv` 归到 `A`，只能靠 `Path` 前缀匹配。
//
// ⚠️ **绝不能用 `contains`**：`/media/A` 与 `/media/AB` 互为子串，
//    `contains` 会把 `/media/AB/x.mkv` 错判成在 `A` 里面，两个目录的最新媒体
//    互相串味。必须按**路径分段边界**匹配（见 [normalizePath] / [matchChildFolder]）。
//
// ⚠️ **也不能只按名字**：同名目录（两个「电影」分别在两个盘）会撞车，
//    所以归因后仍要回到「这条媒体属于哪个 childId」，用 Id 记结果。
//
// ## 提前终止
// 结果集已按 DateCreated 降序，第一页的第一条就已经是这个目录里最新的媒体。
// 边扫边填，一旦「所有有媒体的子目录」都填上了就停 —— 不必翻完整个库
// （十万级的库翻到底要几百次请求）。
//
// ## 已知限制（写在这里，避免以后当成 bug 报）
//  · 只有当子目录下**有媒体**时才能算出时间；纯中转目录（下面只有目录）
//    里的媒体算在**最深那一层的直接子目录**上 —— 那正是用户要的语义
//    （「B 目录刚进了片子」），而不是把时间冒泡到最外层的 A。
//  · 没有 `Path` 字段的服务端（个别兼容层）只能退回 `ParentId`：只认直接子项，
//    更深层的媒体归不到这一层，那些目录会排到末尾。宁可少算，不错算。

/**
 * 把服务器返回的路径规整成「统一分隔符、无尾分隔符」的形式，用于前缀比较。
 *
 * 做三件事：
 *  1. `\` → `/`（Windows 服务端回的是 `D:\media\x.mkv`）；
 *  2. 折叠连续的 `/`；
 *  3. 去掉末尾的 `/`。
 *
 * ⚠️ 不做大小写折叠：Linux 服务端上的 `Movies` 与 `movies` 是两个目录，
 *    统一小写会把它们 AND 到一起。需要忽略大小写的是 Windows 服务端，
 *    那种情况下两边的路径写法本来就一致，不需要我们改。
 */
internal fun normalizePath(raw: String?): String? {
  val trimmed = raw?.trim().orEmpty()
  if (trimmed.isEmpty()) return null
  val unified = trimmed.replace('\\', '/')
  val collapsed = StringBuilder(unified.length)
  var lastWasSlash = false
  for (ch in unified) {
    val isSlash = ch == '/'
    if (isSlash && lastWasSlash) continue
    collapsed.append(ch)
    lastWasSlash = isSlash
  }
  return collapsed.toString().trimEnd('/').ifEmpty { null }
}

/**
 * 这条路径是不是落在 [prefix] 这个目录**里面**（按分段边界判断）。
 *
 * 判据只有一条：路径以 `prefix + "/"` 开头。这样
 *  · `/media/A/x.mkv`    ✅ 在 `/media/A` 里
 *  · `/media/AB/x.mkv`   ❌ 不在 `/media/A` 里（下一段是 `AB` 不是 `A`）
 *  · `/media/A`          ❌ 它不是「里面」，它就是自己
 *
 * 根目录 `/` 单独处理：任何绝对路径都在它里面。
 */
internal fun isUnderPath(
  path: String,
  prefix: String,
): Boolean {
  if (path == prefix) return false
  if (prefix.isEmpty()) return true
  // 根目录规整后会变成空串，这里兜住（`normalizePath` 对 "/" 返回 null，
  // 但调用方可能直接传构造好的值）
  if (prefix == "/") return path.startsWith("/")
  return path.startsWith("$prefix/")
}

/**
 * 一次归因的输入：一个直接子目录的 Id 与它自己的路径。
 *
 * [path] 为 null（服务端没给 Path）时该目录无法参与路径匹配，
 * 只能靠 [id] 与媒体的 `ParentId` 对上。
 */
internal data class ChildFolder(
  val id: String,
  val path: String?,
)

/**
 * 目录类条目（Emby 的 `BaseItemDto.Type`）。
 *
 * 这些条目的 `DateCreated` 是「这个目录被扫描到的时刻」，一次性写死、此后不随
 * 内容变动 —— 拿它当「目录里最新入库时间」正是本功能要解决的误解，所以归因时
 * 一律排除。⚠️ 必须与 `EmbyLibraryScreen` 的 `FOLDER_TYPES` 保持同一份口径：
 * 那边的「文件夹」档就是这三种，这边多一个少一个都会导致**屏幕上摆着的目录**
 * 与**参与归因的目录**不是同一批。
 *
 * 【注意】不含 `BoxSet` / `Series` / `Season`：它们是容器，但成员变动时 Emby 会
 * 刷新它们的 `DateCreated`，属于有效的「最近有新增」信号。
 */
internal val FOLDER_LATEST_CONTAINER_TYPES = setOf("Folder", "CollectionFolder", "UserView")

/**
 * 把一条媒体的路径归到「这一层」的某个直接子目录上，找不到返回 null。
 *
 * ## 为什么取**最长**前缀
 * 路径树是嵌套的：`/media/A` 与 `/media/A/B` 都可能是这一层的子目录？
 * 不可能是 —— 它们在同一层就不会互为前缀。但**兼容层**有时会把同一批内容
 * 用两种路径暴露（例如映射盘 `D:/media` 与 `/media` 同时存在），
 * 这时两条前缀都命中，取最长的那条才是更精确的那个目录。
 *
 * ## [pathToId] 的约定
 * 调用方先把所有子目录按规整后的路径建好索引。同一路径对应多个 Id 时
 * （同名目录挂载了两次）**保留第一个**：宁可把它算在一个确定的地方，
 * 也不要在两处各算一半。
 */
internal fun matchChildFolder(
  mediaPath: String?,
  pathToId: Map<String, String>,
): String? {
  val path = normalizePath(mediaPath) ?: return null
  var bestId: String? = null
  var bestLen = -1
  for ((prefix, id) in pathToId) {
    if (!isUnderPath(path, prefix)) continue
    if (prefix.length > bestLen) {
      bestLen = prefix.length
      bestId = id
    }
  }
  return bestId
}

/**
 * 一轮扫描的累积结果。
 *
 * 这里的字段全是「扫到哪儿了」的中间态，最终产出见 [FolderLatestOrder]。
 */
internal class FolderLatestScan(
  /** 这一层的全部直接子目录（按**原顺序**，用于给没命中的目录保底排序） */
  val childIdsInOriginalOrder: List<String>,
  /** 子目录 Id → 规整后的路径（没有 Path 的不进这个 map） */
  childPathById: Map<String, String>,
) {
  private val childIdSet: Set<String> = childIdsInOriginalOrder.toHashSet()
  private val pathToId: Map<String, String> = childPathById.entries
    // 反向：路径 → Id。同一路径保留先出现的那个（见 matchChildFolder 的说明）
    .groupBy({ it.value }, { it.key })
    .mapValues { (_, ids) -> ids.first() }
  private val newestById: MutableMap<String, String> = HashMap(childIdsInOriginalOrder.size)

  /** 还有多少个子目录没算出时间 —— 归零即可停止翻页 */
  val missingCount: Int get() = childIdSet.size - newestById.keys.count { it in childIdSet }
  val isComplete: Boolean get() = missingCount <= 0

  /**
   * 喂一批媒体条目（**必须已按 DateCreated 降序**）。
   *
   * @return 本批里新算出的子目录个数，0 说明这批对结论毫无贡献 ——
   *   调用方可据此判断要不要继续翻页（见 `FolderLatestAddedLoader`）。
   */
  fun feed(items: List<EmbyItem>): Int {
    var filled = 0
    for (item in items) {
      // ⚠️ 没有时间戳的条目**必须跳过而不是终止**：服务端降序是按「有时间的那批」
      //    排序的，缺 DateCreated 的条目（部分驱动 / 手工塞进去的文件）会被排到
      //    整个结果集的**末尾**，同批里混着它们不代表后面的更旧。以前这里用
      //    `continue` 是对的，但要确保不要写成 break —— 一条脏数据不该让
      //    整个目录被判成「没有时间」而垫底。
      val created = item.DateCreated?.takeIf { it.isNotBlank() } ?: continue
      val folderId = resolveFolderId(item) ?: continue
      // 服务端已降序，第一条就是最新的：已有值说明后面只会更旧，直接跳过。
      if (newestById.containsKey(folderId)) continue
      newestById[folderId] = created
      filled++
    }
    return filled
  }

  /**
   * 这条媒体属于哪个直接子目录。
   *
   * 两条路，按可靠性排序：
   *  1. **Path 前缀**（准）：能认出深埋在子目录里的媒体；文件夹本身（Type 是
   *     Folder / CollectionFolder / UserView）永远不算 —— 它自己的 Path 就是
   *     它所在的位置，拿它的 DateCreated（= 这个目录被扫描到的时刻）当
   *     「里面最新入库时间」，正是本功能要避免的那个误解；
   *  2. **ParentId**（兜底）：服务端没给 Path 时，只认「直接子项」。
   *
   * ⚠️ 两条都不能用名字猜 —— 名字里出现目录名纯属巧合的可能性远大于真实对应。
   */
  private fun resolveFolderId(item: EmbyItem): String? {
    if (item.Type in FOLDER_LATEST_CONTAINER_TYPES) return null
    matchChildFolder(item.Path, pathToId)?.let { return it }
    val parentId = item.ParentId
    // 兜底只看直接父级：`ParentId == 某个子目录` 意味着这条就在它下面一层
    if (parentId != null && parentId in childIdSet) return parentId
    return null
  }

  /** 产出最终顺序（见 [FolderLatestOrder]） */
  fun toOrder(): FolderLatestOrder = FolderLatestOrder(
    orderedIds = orderIds(childIdsInOriginalOrder, newestById),
    newestByFolderId = newestById.toMap(),
    complete = isComplete,
  )

  /**
   * 直接记一条「这个子目录的最新时间」。
   *
   * 供**按目录分治**的扫描路径使用（见 `FolderLatestAddedLoader`）：那条路
   * 每个子目录独立查一次，命中时已经知道归属，不必再走 [feed] 的路径归因。
   * 仍然只保留更晚的那条，避免并发把旧值盖掉。
   */
  fun record(
    childId: String,
    created: String,
  ) {
    if (childId !in childIdSet) return
    val old = newestById[childId]
    if (old == null || created > old) newestById[childId] = created
  }
}

/**
 * 扫描时「这一条是不是目录」。
 *
 * 与 UI 层的 `isFolderLike` 同一批类型，但**多认 `BoxSet`**（UI 也认它的），
 * 单独导出一份是为了让纯逻辑层不依赖界面包、能进 JVM 单测。
 */
internal fun isFolderLikeForScan(item: EmbyItem): Boolean =
  item.Type in FOLDER_LATEST_CONTAINER_TYPES || item.Type == "BoxSet"

/**
 * 排序规则：**有时间的在前（时间新的在前），没时间的在后且保持原顺序**。
 *
 * 抽成独立函数是为了能被单测直接打 —— 这段判定是整个功能的结论，
 * 任何「顺手改一下比较器」的冲动都该先过一遍测试。
 *
 * 细节：
 *  · 纯时间降序，不掺名字：同一秒入库的两个目录（批量扫描时很常见）
 *    保持服务端给的顺序（[idsInOriginalOrder] 的顺序就是它），
 *    这样反复进页面看到的顺序是一致的，不会「每次进来两个目录互换位置」。
 *  · 空目录恒排在最后：它们不是「最旧的」，而是「没有这个信息」，
 *    混进时间序里会让用户以为「这个空目录比 2019 年的老片还旧」。
 */
internal fun orderIds(
  idsInOriginalOrder: List<String>,
  newestByFolderId: Map<String, String>,
): List<String> {
  val withTime = ArrayList<String>(idsInOriginalOrder.size)
  val withoutTime = ArrayList<String>(idsInOriginalOrder.size)
  for (id in idsInOriginalOrder) {
    if (newestByFolderId[id] != null) withTime.add(id) else withoutTime.add(id)
  }
  // 稳定排序：sortedWith 在 Kotlin 里对 List 是稳定的，同键保持原序
  val sorted = withTime.sortedWith(
    // DateCreated 是 ISO-8601 字符串，字典序即时间序；降序 = 新在前
    compareByDescending<String> { newestByFolderId[it].orEmpty() },
  )
  return sorted + withoutTime
}

/**
 * 一轮计算的最终产出。
 *
 * @param orderedIds 排序后的直接子目录 Id 列表。**包含这一层的全部子目录** ——
 *   调用方据此重排网格，不增不减。
 * @param newestByFolderId 子目录 Id → 它内部最新媒体的 `DateCreated`
 *   （没算出来的目录不在这个 map 里）
 * @param complete 是否「所有子目录都算出了时间」。false 表示扫到上限就停了
 *   （库太大 / 中途失败），此时结果仍是可用的 —— 算出来的那些是真值，
 *   没算出来的排到最后。**不要**把它当成「这些目录是空的」。
 */
internal data class FolderLatestOrder(
  val orderedIds: List<String>,
  val newestByFolderId: Map<String, String>,
  val complete: Boolean,
)

/**
 * 按 [FolderLatestOrder] 重排一组子目录条目。
 *
 * 只重排、不增删：输出与输入的**元素集合完全一致**，只是顺序变了。
 * 这一条是硬约束 —— 排序 bug 最多让顺序不对，绝不能让用户看见目录少了。
 *
 * ## 不在 [FolderLatestOrder.orderedIds] 里的条目怎么办
 * 「拉目录清单」与「网格分页」是两条独立的请求，可能对不齐：网格这一页
 * 里有、清单里没有（清单被 500 条截断，或刚新增了一个目录）。
 * 这类条目**按它们自己在这一层的位置**插进去，而不是一律扔到最后：
 * 做法是把清单当作「已知目录的完整顺序」，对未知的目录沿用它前后的
 * 已排序邻居来确定区间 —— 简单可行的近似是：未知条目**保持在输入里的相对
 * 位置**，整体排在所有已知条目之后，但彼此不去打乱。
 *
 * 换句话说：已知的按结论排，未知的按原样垫在后面。这样即使清单不全，
 * 前 500 个目录的顺序是对的（用户最常看的就是它们），不会因为
 * 「有 5 个目录没排上」而把几千个目录全部退化成原顺序。
 *
 * ## [ascending]
 * 排序面板上「点已选中项翻方向」是这个 App 的统一手势，这一档也得认：
 *  · false（默认，= Descending）：最新入库的目录在前；
 *  · true：最旧的在前，**但空目录仍然排在最后**。
 *
 * 空目录不跟着翻是怎么做的：两边都先把「有时间」与「没时间」分开，
 * 只为「有时间」那组排方向，最后再把空目录接在后面。所以升序时看到的
 * 是「2019 年的老目录 → 2026 年的新目录 → 一堆空目录」，
 * 而不是把空目录甩到最前面去。
 */
internal fun <T> applyFolderLatestOrder(
  items: List<T>,
  order: FolderLatestOrder,
  ascending: Boolean = false,
  idOf: (T) -> String?,
): List<T> {
  if (items.isEmpty()) return items
  val rank = HashMap<String, Int>(order.orderedIds.size * 2)
  if (ascending) {
    // 升序：让「有时间」的那批反向，空目录的位置保持不动。
    // 做法是先按降序建 rank，再在整体序里翻转「有时间」段。
    //
    // 更简单可靠的办法是直接按时间重建一份升序 id 列表 —— 但这里手上只有
    // 降序的 rank，没有每个目录的时间值。用下面的「段内反转」等价实现：
    // 降序顺序里「有时间」的目录连续排在最前（orderIds 保证），
    // 反转这一段就等于升序，且空目录段完全不受影响。
    val timedCount = order.orderedIds.size - countUntimedAtTail(order)
    order.orderedIds.forEachIndexed { index, id ->
      // 落在「有时间」段内的做镜像，空目录段保持原 rank
      rank[id] = if (index < timedCount) timedCount - 1 - index else index
    }
  } else {
    order.orderedIds.forEachIndexed { index, id -> rank[id] = index }
  }
  // sortedWith 是稳定排序：未知条目 rank 取 Int.MAX_VALUE，彼此保持原相对顺序
  return items.sortedWith(compareBy { rank[idOf(it)] ?: Int.MAX_VALUE })
}

/**
 * [FolderLatestOrder.orderedIds] 末尾有多少个「没有时间」的目录。
 *
 * orderIds 的产出保证「有时间的在前、空目录在后」，所以从尾部往前数，
 * 一直数到第一个有时间的就是空目录的个数。判定依据是
 * [FolderLatestOrder.newestByFolderId] 里有没有这条。
 */
private fun countUntimedAtTail(order: FolderLatestOrder): Int {
  var count = 0
  for (i in order.orderedIds.indices.reversed()) {
    if (order.newestByFolderId.containsKey(order.orderedIds[i])) break
    count++
  }
  return count
}
