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
