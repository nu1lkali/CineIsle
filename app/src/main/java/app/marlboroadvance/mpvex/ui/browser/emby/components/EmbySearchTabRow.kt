package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 与 [EmbySearchFilterRow] 在同一屏上下相邻：筛选 chip 在搜索框下面，本页签在结果区上面。
 * 两者语义不同 —— 上面那排是**多选复选**（决定问服务端要什么，改一次重发一次请求），
 * 这排是**单选**（在已拿到的结果里先看哪一堆，切一下零请求）。
 *
 * ⚠️ 两排标签用词故意保持一致（全部 / 电影 / 剧集 / 单集 / 合集 / 演员），
 * 所以「长得一模一样」反而成了困惑源：用户分不清哪排才算数。
 * 现在靠**形状**区分：筛选 chip 是默认胶囊，这一排改成 10dp 小圆角方块，
 * 一眼能分出「上排是条件、下排是结果分组」。交互与数据一行没动。
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
        shape = RoundedCornerShape(10.dp),
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
