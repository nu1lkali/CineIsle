package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem

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
 * ## 为什么要单独一个函数，而不是直接用 `Name`
 *
 * `GET /Items/{Id}/Ancestors` 返回的容器对象，其 `Name` **不总是用户起的那个名字**。
 * 实测过一类库（名字里带下划线的挂载库）：库根单独打开时标题完全正确，
 * 但从子目录拉祖先链回来，同一层的 `Name` 就只剩其中一段 —— 看起来像前缀被剥掉了。
 * 没有下划线的库则一切正常，所以这个差异跟**命名形态**有关，不是偶然。
 *
 * ## 判据不能只靠「像不像」
 *
 * 曾经按「`Name` 是 `Path` 末段的子串、且短一半以上」来判定名字被裁剪，但这只是
 * **推测**：`Name` 里根本不含 `Path` 末段时（服务端给了别的短名）这条就完全失效。
 * 所以现在改成**认形态**：名字由两段以上、用下划线连接时，任何一段单独出现
 * 都不是完整名字 —— 库里不会有人真的把一个目录叫「编号」。
 *
 * 因此按「可靠程度」从高到低取：
 *
 * 1. [preferredName]：调用方手上已知正确的名字（例如本页标题、库根页的 title）。
 *    这是最可信的 —— 它是从带 Fields 的接口或用户可见的页面标题来的。
 * 2. `Name` 与 `Path` 末段**一致**时用 `Name`（名字没被处理过，最干净）。
 * 3. `Path` 的末段：这是**磁盘上的真实目录名**，不经过服务端的显示名处理，
 *    所以即使 `Name` 被裁剪过，这里也是完整的。
 * 4. `Name`：没有 `Path` 可比时（网络 / 虚拟条目）只能信它。
 * 5. `SortName`：排序名通常是全名，作为又一层兜底。
 *
 * @param item 该层条目
 * @param preferredName 调用方已知正确的名字（可空）
 * @return 显示名；全部为空时返回 null，由调用方决定兜底文案
 */
internal fun levelDisplayName(item: EmbyItem, preferredName: String? = null): String? {
  preferredName?.takeIf { it.isNotBlank() }?.let { return it }

  val name = item.Name?.takeIf { it.isNotBlank() }
  // Path 末段：兼容 Windows 反斜杠与 POSIX 正斜杠，去掉尾部斜杠再取最后一段
  val fromPath =
    item.Path
      ?.trim()
      ?.trimEnd('\\', '/')
      ?.substringAfterLast('\\')
      ?.substringAfterLast('/')
      ?.takeIf { it.isNotBlank() }

  if (fromPath != null) {
    // 与磁盘名一致 ⇒ `Name` 就是原样，直接用
    if (name == null || name == fromPath) return fromPath
    // 名字带下划线分段、而 `Name` 只是其中一段 ⇒ 是裁剪产物，用磁盘真名
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
