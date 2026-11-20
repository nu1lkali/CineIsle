package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.LibraryNameIndex
import app.marlboroadvance.mpvex.domain.emby.nameTokens
import app.marlboroadvance.mpvex.domain.emby.pathLastSegment

/**
 * Emby 目录层级推导（经典界面与 Cine 界面共用的唯一实现）。
 *
 * ## 为什么需要这个文件
 *
 * 「返回上一级」的正确语义是**去父目录**（文件管理器口径），而不是弹历史栈。
 * 弹栈退的是「上一次看的是哪」，用目录导航跳层之后两者会分叉：
 * 从深目录跳回上层再按返回，会又弹回那个深目录。
 *
 * 要按层级返回，就必须能回答「某个目录的父目录是谁」。这个问题跟界面无关，
 * 只跟 Emby 的数据结构有关 —— 所以两套界面（`ui/browser/emby` 与 `ui/cine`）
 * **不应该各写一份**，否则口径一改就得改两处，漏一处就是一边好一边坏。
 *
 * ## 数据来源的约定
 *
 * 祖先链来自 `GET /Items/{Id}/Ancestors`。服务端返回顺序是**由近到远**
 * （直接父目录在前、最外层容器在后），`EmbyViewModel.loadItemAncestors` 已统一
 * 归一化为**由远到近**。本文件所有函数都按「由远到近」解释传入的链。
 *
 * ## ⚠️ 不要在这些函数里做网络请求
 *
 * 全部是纯函数：拿不到就是 null，由调用方决定要不要现查一次。这样测试不需要
 * 服务器，也不会因为「顺手发个请求」把返回手势拖出几百毫秒的延迟。
 */

/**
 * 从祖先链里取 [itemId] 的**直接父目录**。
 *
 * @param chain 祖先链，**由远到近**（最外层容器在前），且**不含 [itemId] 自己**
 * @param itemId 要查父目录的那一层
 * @return 直接父目录；[itemId] 就是链里最外层的下一层（即它父级是最外层容器）时
 *         返回 null —— 那时「返回」意味着离开这个库回首页，调用方需要自己处理
 */
internal fun parentInChain(chain: List<EmbyItem>, itemId: String): EmbyItem? {
  val pos = chain.indexOfFirst { it.Id == itemId }
  if (pos <= 0) return null
  return chain[pos - 1]
}

/**
 * 当前层在祖先链里的位置。
 *
 * - `-1`：链里没有这一层（链是**祖先**链，正常情况下不含自己；出现 -1 说明
 *   调用方传错了链，或者传的是自己的 Id 而不是祖先链）
 * - `0`：自己是最外层容器的直接子级 ⇒ 再返回就该离开这个库
 * - `> 0`：正常层级，父目录 = `chain[pos - 1]`
 */
internal fun depthInChain(chain: List<EmbyItem>, itemId: String): Int =
  chain.indexOfFirst { it.Id == itemId }

/**
 * 是否已经处于「库的最上层目录」—— 此时按返回等于离开库、回 App 首页。
 *
 * ⚠️ 判据是「自己在链里的位置」而不是「链的长度」：链的长度只说明**父级**有多深，
 * 说明不了自己在哪一层。位置 0 才是「父级就是最外层容器」。
 *
 * 链还没取到时返回 false（保守：按普通层级返回，别把人直接踢回首页）。
 */
internal fun isAtLibraryTopLevel(chain: List<EmbyItem>, itemId: String): Boolean =
  depthInChain(chain, itemId) == 0

/**
 * 目录层级里显示「这一层叫什么」。
 *
 * ## 为什么不能只用 `Name`
 *
 * `/Items/{Id}/Ancestors` 不带 Fields 时返回**精简对象**，名字类字段可能缺失，
 * `Name` 只剩磁盘目录名的一段甚至为空 —— 这时需要靠 `Path` 末段 / `SortName` 兜底。
 *
 * ## ⚠️ 但这个函数**不负责**「库根该叫什么」
 *
 * 有一类现象很容易误判成上面的「名字被裁短」：用户给媒体库起名「Movies_HD」，
 * 而它索引的磁盘目录叫 `HD`，祖先链里那一层就返回 `Name=HD`。
 * 实测 `Name`、`SortName`、`Path` 末段三者完全一致，**没有任何截断迹象** ——
 * 因为 `HD` 本来就是目录的真实名字，跟库名是两回事：
 *
 * - 库名 = 用户在 Emby 里手动设置的（`/Users/{uid}/Views` 的 `Name`）
 * - 目录名 = `/Items/{Id}/Ancestors` 里那些 `Folder` 条目的 `Name`
 *
 * 把库挂到「张三」目录，祖先链就返回「张三」；换成一长串无意义的名字也一样。
 * 这**不是客户端能推断或还原的问题**：从数据里看，那个名字就是正确的目录名。
 * 所以库根那一行的名字必须由调用方按 Id 查权威库名（见
 * `EmbyClient.libraryDisplayName`），再作为 [preferredName] 传进来；本函数只处理
 * 「精简对象导致名字字段缺失」这一类，绝不要拿 `Path` 去覆盖正常的 `Name`。
 *
 * 取值顺序：
 * 1. [preferredName]：调用方手上已知正确的名字（库根用权威库名、其余层可传本页标题）
 * 2. `Name`：正常情况服务端给的就是它；与 `Path` 末段一致时直接用
 * 3. `Path` 末段：仅当 `Name` 缺失、或看起来是从带下划线的全名里被裁出一段时
 * 4. `SortName`：排序名通常是全名，最后一层兜底
 *
 * @param item 该层条目
 * @param preferredName 调用方已知正确的名字（可空）
 * @return 显示名；全部为空时返回 null，由调用方决定兜底文案
 */
internal fun levelDisplayName(item: EmbyItem, preferredName: String? = null): String? {
  preferredName?.takeIf { it.isNotBlank() }?.let { return it }

  val name = item.Name?.takeIf { it.isNotBlank() }
  // Path 末段：兼容 Windows 反斜杠与 POSIX 正斜杠，去掉尾部斜杠再取最后一段
  val fromPath = pathLastSegment(item.Path)

  if (fromPath != null) {
    // 与磁盘名一致 ⇒ `Name` 就是原样，直接用
    if (name == null || name == fromPath) return fromPath
    // 名字带下划线分段、而 `Name` 只是其中一段 ⇒ 精简对象裁剪，用磁盘真名兜底
    if (looksTruncated(name, fromPath)) return fromPath
  }

  if (name != null) return name
  return item.SortName?.takeIf { it.isNotBlank() }
}

/**
 * [name] 看起来是 [fullName] 被**截掉首尾只留一段**之后的结果。
 *
 * 判据三条同时成立才算：
 * 1. [fullName] 用下划线分成两段以上；
 * 2. [name] 等于其中某一段（相等即「只剩这一段」）；
 * 3. [name] 比 [fullName] 短（本来就是同一个名字则不算）。
 *
 * 只认下划线这一种分隔形态，是因为实测出问题的就是它；换成更宽的规则
 * （任意子串）会误伤「用户真把目录改名成短名」的情况。
 */
private fun looksTruncated(name: String, fullName: String): Boolean {
  if (name.length >= fullName.length) return false
  if (!fullName.contains('_')) return false
  return fullName.split('_').any { it.isNotBlank() && it == name }
}

// ════════════════════════════════════════════════════════════════════════
// 库根那一行该显示什么名字
// ════════════════════════════════════════════════════════════════════════

/**
 * 在祖先链里找出「哪一行代表媒体库」，并给出它该显示的名字。
 *
 * ## 要解决的问题
 *
 * 用户给库起名「Movies_HD」、挂载目录叫 `/mnt/data/HD`，进入子目录后面包屑下拉
 * 显示成「首页 / HD / A / B / C」—— 库名被目录名顶掉了。挂到乱码目录同理。
 *
 * 根因是数据源不同，不是字符串被截断：
 * `/Items/{Id}/Ancestors` 返回的是**磁盘目录条目**，`Name` 天生就是目录名；
 * 用户设置的库名只存在于 `/Users/{uid}/Views`。所以必须在渲染前把那一行换掉。
 *
 * ## 判定顺序（前四条是凭据，第五条是启发式）
 *
 * 1. **Id 命中库根**：调用方已经知道库根 Id（[rootId]）与它的库名（[rootTitle]）。
 * 2. **Id 是某个库**：[index] 的 `byId` 里有它 —— 服务端把库根当普通目录返回时，
 *    也仍可能保留库 Id。
 * 3. **父级 Id 是某个库**：库根下面的第一层磁盘目录，`ParentId` 就是库本身。
 *    ⭐ 实测最常见的形态就是这一条：库根那一行被服务端标成 `Folder`、名字是 `HD`，
 *    它自己不是库，但它爹是库 —— 于是「HD」换成「Movies_HD」。
 * 4. **磁盘目录末段命中**：[index] 的 `bySegment` 里有 `Path` 的末段（`/mnt/data/HD` → `HD`）。
 *    这一条对上「乱码挂载目录」也成立，因为比对的是服务端给的路径，不是人起的名字。
 * 5. **名字分段命中**（启发式，兜底）：只有在 1–4 全都没命中时才用，而且**只认
 *    最浅的那一行（下标 1）**。库名按 `_ - .` 切段后与这一行的名字有交集
 *    （`Movies_HD` 与 `HD`）—— 这正是用户描述的「文本替换」，但只对一行生效，
 *    不会把深层同名子目录一起改掉。
 *
 * ## 为什么只认一行
 *
 * 库根有且只有一个。若放任每一行都去匹配，`/mnt/data/HD/HD` 这种同名子目录会被一起改名，
 * 甚至出现上下两行都叫「Movies_HD」的重复。所以命中即停，取**最浅**的那一行。
 *
 * ## 与「返回上一级」的关系
 *
 * 本函数只回答「显示什么」，不改层级、不改 Id。跳转仍然按 Id 走
 * （点库根 = 跳到那个目录的 Id），所以命名口径与导航行为互不牵连。
 *
 * @param chain 祖先链，**由远到近**（最外层在前），不含当前层
 * @param index 库名索引（见 `EmbyClient.libraryNameIndex`）；空表时只有第 1 条可能命中
 * @param rootId 调用方已知的库根 Id（可空）
 * @param rootTitle [rootId] 对应的库名（可空）
 * @param libraryName 当前这一条下钻链所属的库名（可空）；第 5 条启发式要用它
 * @return `行 Id → 该行显示的名字`，**最多一条**；认不出返回空表
 */
internal fun resolveLibraryRowNames(
  chain: List<EmbyItem>,
  index: LibraryNameIndex,
  rootId: String?,
  rootTitle: String?,
  libraryName: String?,
): Map<String, String> {
  if (chain.size < 2) return emptyMap()

  // 第 0 行永远显示「首页」（它代表 App 首页入口，不是库），不参与命名
  for (i in 1 until chain.size) {
    val level = chain[i]
    val id = level.Id?.takeIf { it.isNotBlank() } ?: continue
    val name = confidentLibraryName(level, index, rootId, rootTitle) ?: continue
    return mapOf(id to name)
  }

  // 凭据一条都没对上，才动启发式，而且**只认第 1 行**：
  // 库根下面那一层磁盘目录永远是最浅的真实目录，往下都是它的子目录 ——
  // 若是逐行比对，`Movie` 这种和库名 `Movies 4K` 沾边的深层子目录也会被改名。
  val knownLibraryName = libraryName?.takeIf { it.isNotBlank() } ?: return emptyMap()
  val wanted = nameTokens(knownLibraryName)
  if (wanted.isEmpty()) return emptyMap()
  val shallow = chain[1]
  val shallowId = shallow.Id?.takeIf { it.isNotBlank() } ?: return emptyMap()
  val rowTokens = nameTokens(shallow.Name) + nameTokens(pathLastSegment(shallow.Path))
  return if (rowTokens.any { it in wanted }) mapOf(shallowId to knownLibraryName) else emptyMap()
}

/**
 * 第 1–4 条判据：只用服务端给的凭据回答「这一行是不是媒体库」。
 *
 * 全部落空返回 null —— 由 [resolveLibraryRowNames] 决定要不要退到启发式。
 */
private fun confidentLibraryName(
  level: EmbyItem,
  index: LibraryNameIndex,
  rootId: String?,
  rootTitle: String?,
): String? {
  val id = level.Id?.takeIf { it.isNotBlank() }

  // ① 调用方手上就有：库根 Id + 库名（进库那一刻记下、一路继承下来的）
  if (id != null && rootId != null && id == rootId) {
    rootTitle?.takeIf { it.isNotBlank() }?.let { return it }
  }
  // ② 这一行本身是某个库（`/Views` 里登记过的 Id）
  if (id != null) index.byId[id]?.takeIf { it.isNotBlank() }?.let { return it }
  // ③ 它爹是某个库 ⇒ 它就是库根下面那一层磁盘目录
  val parentId = level.ParentId?.takeIf { it.isNotBlank() }
  if (parentId != null) index.byId[parentId]?.takeIf { it.isNotBlank() }?.let { return it }
  // ④ 磁盘目录末段对上某个库的挂载目录（乱码路径也照样对得上）
  val segment = (pathLastSegment(level.Path) ?: level.Name)?.lowercase()
  if (!segment.isNullOrBlank()) {
    index.bySegment[segment]?.takeIf { it.isNotBlank() }?.let { return it }
  }
  return null
}
