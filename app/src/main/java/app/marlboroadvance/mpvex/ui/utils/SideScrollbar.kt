package app.marlboroadvance.mpvex.ui.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.LazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarSettings
import org.koin.compose.koinInject

/**
 * 侧边可拖动滚动条（nanihadesuka）的**统一外壳** —— App 内所有长列表要显示滚动条，
 * 都必须套这两个壳（网格套 [LazyGridSideScrollbar]、列表套 [LazyListSideScrollbar]），
 * 不要绕过壳直接调库。壳负责三件事：
 *
 *  1. **全局开关**：读「设置 → 界面与外观 → 侧边滚动条」
 *     （[BrowserPreferences.sideScrollbarEnabled]）。关掉时**不套壳**、直接渲染内容 ——
 *     不是把 thumb 透明度归零：透明 thumb 仍占触摸拖动区，会留下一道摸得到却看不见的死区。
 *  2. **出现时机**：回到顶部（露出来会挡第一屏内容）或条目不够多（没有滚动需求）时
 *     淡出；与网络浏览模块上线时的行为保持一致。
 *  3. **外观**：thumb 用主题 primary 色，未选中 40% 透明、拖动中全不透明。
 *
 * ## 库 API 事实（javap 核验，LazyColumnScrollbar 2.2.0）
 *  - `LazyVerticalGridScrollbar(state, modifier, settings, indicator, content)` 的
 *    **content 是普通 `@Composable () -> Unit`**（无 BoxScope / LazyGridScope 接收者），
 *    所以调用方把 `LazyVerticalGrid` / `LazyColumn` 整个塞进来即可，align 之类的外层
 *    修饰符留在壳外面。
 *  - `ScrollbarSettings` 第一个构造参数就是 `enabled`，但**不要**靠它做开关：
 *    关掉后库里仍会布置拖动热区（本壳的「不套壳」才是零残留）。
 */

/** 条目少于此数不显示滚动条：一屏多一点点的列表，滚动条只会添乱 */
private const val MIN_ITEMS_FOR_SCROLLBAR = 20

/** 滚动条淡入淡出时长（ms）：和网络模块上线时的观感一致 */
private const val SCROLLBAR_ALPHA_TWEEN_MS = 200

/**
 * 网格版外壳：给 `LazyVerticalGrid`（含 stickyHeader 的年份时间轴）套侧边滚动条。
 *
 * 用法：
 * ```kotlin
 * LazyGridSideScrollbar(state = gridState) {
 *   LazyVerticalGrid(state = gridState, ...) { ... }
 * }
 * ```
 */
@Composable
fun LazyGridSideScrollbar(
  state: LazyGridState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val enabled by koinInject<BrowserPreferences>().sideScrollbarEnabled.collectAsState()
  // ⚠️ 两个判据都必须走 derivedStateOf：裸读 firstVisibleItemIndex 会让**每帧滚动**
  //    都重组一次本壳（进而重建 ScrollbarSettings）。derivedStateOf 只在布尔值
  //    真正翻转时才通知，滚动过程中零额外重组（网络模块上线时就是这么写的）。
  val isAtTop by remember {
    derivedStateOf {
      state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems by remember {
    derivedStateOf { state.layoutInfo.totalItemsCount > MIN_ITEMS_FOR_SCROLLBAR }
  }
  if (!enabled) {
    content()
  } else {
    LazyVerticalGridScrollbar(
      state = state,
      modifier = modifier,
      settings = sideScrollbarSettings(isAtTop = isAtTop, hasEnoughItems = hasEnoughItems),
      content = content,
    )
  }
}

/**
 * 列表版外壳：给 `LazyColumn`（如网络浏览的分 section 列表）套侧边滚动条。
 */
@Composable
fun LazyListSideScrollbar(
  state: LazyListState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val enabled by koinInject<BrowserPreferences>().sideScrollbarEnabled.collectAsState()
  val isAtTop by remember {
    derivedStateOf {
      state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
    }
  }
  val hasEnoughItems by remember {
    derivedStateOf { state.layoutInfo.totalItemsCount > MIN_ITEMS_FOR_SCROLLBAR }
  }
  if (!enabled) {
    content()
  } else {
    LazyColumnScrollbar(
      state = state,
      modifier = modifier,
      settings = sideScrollbarSettings(isAtTop = isAtTop, hasEnoughItems = hasEnoughItems),
      content = content,
    )
  }
}

/**
 * 按「是否在顶部 / 条目够不够多」算 [ScrollbarSettings]。
 *
 * 入参是两个布尔值（调用方已用 derivedStateOf 收敛过），所以本函数每帧重建也无妨 ——
 * 只有阈值翻转时才会被重新调用。
 */
@Composable
private fun sideScrollbarSettings(isAtTop: Boolean, hasEnoughItems: Boolean): ScrollbarSettings {
  val scrollbarAlpha by animateFloatAsState(
    targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
    animationSpec = tween(durationMillis = SCROLLBAR_ALPHA_TWEEN_MS),
    label = "scrollbarAlpha",
  )
  return ScrollbarSettings(
    thumbUnselectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * scrollbarAlpha),
    thumbSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
  )
}
