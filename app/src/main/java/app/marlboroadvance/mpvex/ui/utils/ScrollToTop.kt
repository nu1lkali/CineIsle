package app.marlboroadvance.mpvex.ui.utils

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * 列表「回到第一项」的**唯一**正确入口。
 *
 * ## 这个文件为什么存在
 * 同一个 bug 在两个月里被用户报了三次：
 *  · v124 收藏页「换排序后没回到第一条」；
 *  · v127 媒体库「随机排序 + 下拉刷新，列表停在中间」；
 *  · v137 网络位置「随机排序下拉，又出现那个 item(0) 的问题」。
 *
 * 每次都是「某处把 `scrollToItem(0)` 改成 `requestScrollToItem(0)`」的单点补丁 ——
 * 补一处、漏三处。根因不是手滑，而是**错的写法更符合直觉**：
 *
 * ```
 * LaunchedEffect(sortKey) { state.scrollToItem(0) }   // 看起来完全正确，实际是错的
 * ```
 *
 * ## 为什么 `scrollToItem(0)` 会失效
 * LazyList / LazyGrid 记住的不只是「第几项」，还有「第一屏那条的 key」
 * （`lastKnownFirstItemKey`）。数据换序后，框架会把**原来第一屏那条**重新定位到它在
 * 新顺序里的位置 —— 这是为了让「中间插入一条」不跳屏，属于特性。
 *
 * `scrollToItem(0)` 是**立刻**执行的：它作用在**旧列表**上，滚到 0 就算完；紧接着新顺序
 * measure，框架又按 key 把那条锚回去。于是画面停在列表**中间**：
 *  · 切随机排序 → 整副牌重洗 → 停在中间；
 *  · 下拉刷新 + 随机 → 同上，看起来像「刷新没生效」；
 *  · 收藏页 / 演员页切排序 → 停在中段。
 *
 * `requestScrollToItem(0)` 不一样：它把定位请求挂到**下一次 measure**，并且**丢弃旧锚点
 * key** ⇒ 新数据第一次布局就直接落在第 0 项，不受 key 锚定影响。
 *
 * ## 规矩（写代码时照这个判断）
 * 凡「顺序**可能变**」的场合 —— 切排序 / 翻方向 / 换随机种子 / 换筛选 / 换分类标签 /
 * 整批替换 / 下拉刷新 —— **只能**用 [scrollToTop] 或 [ScrollToTopOnOrderChange]。
 *
 * `state.scrollToItem(n)` 只留给**恢复历史位置**（n 来自保存下来的滚动索引，那是
 * 「回到用户上次的位置」，不是「回顶」），例如媒体库的 `restore.scrollIndex`、
 * 网络浏览页从查看器返回时的 `saved`。
 *
 * ## 时序也是坑的一半
 * `requestScrollToItem` 仍然要发在**新数据已经落地之后**。若在「请求发出」那一刻就滚，
 * 而数据还在路上，回填时框架又会按 key 锚一次。
 * 所以「异步加载 + 回顶」的正确顺序是：
 * **数据赋值 → 信号的计数 +1 → UI 收到信号再回顶**
 * （实例见 `NetworkBrowserViewModel.loadFiles`：随机排序下拉刷新时，是在新列表写进
 * `_rawFiles` 之后才 `_sortChangeTick.value += 1`）。
 */

/** 回到第一项（挂到下一次 measure，丢弃旧锚点 key）。理由见本文件顶部 KDoc。 */
fun LazyGridState.scrollToTop() {
  requestScrollToItem(0)
}

/** 回到第一项（挂到下一次 measure，丢弃旧锚点 key）。理由见本文件顶部 KDoc。 */
fun LazyListState.scrollToTop() {
  requestScrollToItem(0)
}

/**
 * 带滚动动画地回到第一项。**只给「用户主动点回顶」用**（如作品页紧凑条上的回顶钮）。
 *
 * 与 [scrollToTop] 的分工：那个是瞬时定位，专门对付「数据换序后框架按 key 把列表
 * 锚回中间」。用户点按钮时列表**没有换序**，不存在重锚问题，所以这里可以用普通
 * 动画滚动 —— 一屏海报「刷」地滑上去比瞬间跳顶更符合直觉。
 *
 * ⚠️ 同一个列表**换序之后**的回顶仍然只能用 [scrollToTop] / [ScrollToTopOnOrderChange]。
 */
suspend fun LazyGridState.animateToTop() {
  animateScrollToItem(0)
}

/**
 * 「顺序变了就回顶」的组合式封装：**首次组合不触发**。
 *
 * @param key 顺序变化的信号。只要是「顺序真的变了才会变值」的稳定值即可：
 *  · 客户端的排序 / 筛选状态 → `Triple(sort, sortAsc, randomSeed)`；
 *  · ViewModel 发出来的单调计数 → `sortChangeTick`。
 *
 * ## 为什么不让调用方自己写 `LaunchedEffect(key) { state.scrollToTop() }`
 * 少写一行、少记一条规矩，但更要紧的是 **首次组合不能触发**：
 * 从详情页 / 照片查看器 / 播放器返回时，页面是**重新组合**的，`LaunchedEffect` 会再跑
 * 一次（键没变也跑，这是它的语义）。直接滚的话，用户一返回就被弹回顶端 ——
 * 这正是网络浏览页当年加「已消费 tick」的原因。
 * 这里用 [remember] 把「上次处理过的 key」存在首次组合时的值上，天然躲开这个坑；
 * 调用方不必再维护 consume 记账。
 */
@Composable
fun ScrollToTopOnOrderChange(
  key: Any?,
  gridState: LazyGridState? = null,
  listState: LazyListState? = null,
) {
  // 刻意用普通对象而不是 mutableStateOf：写它不该触发重组
  val lastKey = remember { LastHandledKey(key) }
  LaunchedEffect(key) {
    if (lastKey.value == key) return@LaunchedEffect
    lastKey.value = key
    gridState?.scrollToTop()
    listState?.scrollToTop()
  }
}

/** 只是「上次处理过的 key」的容器，见 [ScrollToTopOnOrderChange]。 */
private class LastHandledKey(var value: Any?)
