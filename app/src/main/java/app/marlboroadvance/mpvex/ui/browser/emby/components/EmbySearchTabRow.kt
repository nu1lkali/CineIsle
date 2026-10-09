package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchTab

/**
 * 搜索结果的分类页签条（清单 4.1）。
 *
 * 视觉上刻意做成和 [EmbySearchFilterRow] 一样的 chip 条 —— 两者在同一屏上下相邻
 * （筛选 chip 在搜索框下面，页签在结果区上面），长得一样才能让用户读出
 * 「上面那排决定搜什么、这排决定看哪堆」是同一种控件的两层。
 * 区别在交互：[EmbySearchFilterRow] 是**多选复选**（可同时勾电影+剧集），
 * 本组件是**单选**（一次只看一堆），所以这里不显示对勾图标，只靠 chip 的选中底色区分。
 *
 * @param counts 各页签的条数（[EmbySearchTab.countByTab] 的结果）；用于角标与显隐
 * @param total 全部结果条数；[EmbySearchTab.ALL] 的角标
 * @param selected 当前选中的页签
 * @param onSelectedChange 切页签。**纯 UI 事件**，不触发任何网络请求（见 [EmbySearchTab] 的说明）
 */
@Composable
fun EmbySearchTabRow(
  counts: Map<EmbySearchTab, Int>,
  total: Int,
  selected: EmbySearchTab,
  onSelectedChange: (EmbySearchTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  // 页签只列 [ALL] + 有条目的具体类型：[OTHER] 不设独立页签（条目仍在 ALL 里可见），
  // 0 条的类型也不列 —— 除非它正被选中，否则用户点不到一个空页签。
  val tabs = remember(counts, selected) {
    buildList {
      add(EmbySearchTab.ALL)
      EmbySearchTab.TYPED.forEach { tab ->
        val count = counts[tab] ?: 0
        if (EmbySearchTab.shouldShowBadge(tab, count, tab == selected)) add(tab)
      }
    }
  }

  LazyRow(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(tabs, key = { it.name }) { tab ->
      val count = if (tab == EmbySearchTab.ALL) total else (counts[tab] ?: 0)
      FilterChip(
        selected = tab == selected,
        onClick = { if (tab != selected) onSelectedChange(tab) },
        label = {
          // 角标用「标签 12」而不是把数字塞进括号：中文标签 + 半角括号在 chip 里容易挤，
          // 空格分隔的写法在标题栏/横排 chip 这种窄容器里更耐挤（见 ui-patterns 的 FlowRow 结论）。
          Text(
            text = if (count > 0) "${tab.label} $count" else tab.label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            textAlign = TextAlign.Center,
          )
        },
      )
    }
  }
}
