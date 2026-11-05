package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R

/**
 * 搜索筛选：把 Emby 的 `IncludeItemTypes` 收敛成一组用户看得懂的选项。
 *
 * 之所以需要它：默认的全库搜索只检索「可播放的视频本体」，合集（BoxSet）和演员（Person）
 * 是被排除掉的 —— 用户搜「某某合集」「某个演员」时什么都搜不到。这里让用户显式指定要找哪类东西。
 */
enum class EmbySearchFilter(
  val label: String,
  /** 对应 Emby 的 IncludeItemTypes；`null` = 不额外限制，沿用调用方的默认类型集 */
  val itemTypes: List<String>?,
) {
  ALL("全部", null),
  MOVIE("电影", listOf("Movie")),
  SERIES("剧集", listOf("Series")),
  EPISODE("单集", listOf("Episode")),
  BOXSET("合集", listOf("BoxSet")),
  PERSON("演员", listOf("Person")),
  ;

  companion object {
    /**
     * 勾选项 → Emby 的 `IncludeItemTypes`。
     *
     * 返回 `null` 表示「不加类型限制」，交给各自的默认值处理；
     * 「全部」永远不进这个集合（它由空集合表示），所以不会污染结果。
     */
    fun toItemTypes(selected: Set<EmbySearchFilter>): List<String>? =
      selected.flatMap { it.itemTypes.orEmpty() }.distinct().takeIf { it.isNotEmpty() }
  }
}

/**
 * 搜索筛选条。首页全库搜索与媒体库内搜索共用。
 *
 * 语义（按产品约定）：
 * - 默认「全部」＝不加任何类型限制；
 * - 「全部」与其余项互斥：勾了其他任一项，全部自动变成未选中；点「全部」则清空所有勾选；
 * - 其余项是复选框，可多选、可单独取消；全部取消掉之后又自动回到「全部」。
 *
 * 实现上「全部」不占一个可选状态 —— 空集合就等于全部，
 * 省掉一个需要时刻和别的勾选保持互斥的布尔量。
 *
 * 另有一条**独立维度**：搜索范围（[localOnly]）。它排在最前面、用一条竖线隔开 ——
 * 范围是单选、类型是多选，两种东西混在一排必须有视觉断点，否则用户会把
 * 「仅本目录」也当成「再多勾一个类型」。
 */
@Composable
fun EmbySearchFilterRow(
  selected: Set<EmbySearchFilter>,
  onSelectedChange: (Set<EmbySearchFilter>) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * 「仅本目录」当前状态。**null = 这一项不出现** —— 页面没有「本目录」概念时
   * （首页全库搜索、库根页）不要摆一个点了也没意义（还会搜出空结果）的选项。
   */
  localOnly: Boolean? = null,
  onLocalOnlyChange: (Boolean) -> Unit = {},
) {
  LazyRow(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    // 竖线要与 chip 中线对齐：LazyRow 默认 Top 对齐，分割线会顶在字上沿
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (localOnly != null) {
      item(key = "search-scope") {
        FilterChip(
          selected = localOnly,
          onClick = { onLocalOnlyChange(!localOnly) },
          label = {
            Text(
              text = stringResource(R.string.emby_search_scope_here),
              style = MaterialTheme.typography.labelLarge,
              maxLines = 1,
            )
          },
          // 刻意**不放对勾**：勾选时左侧突然多出 16dp 图标，chip 宽度会跳一下，
          // 一排 chip 跟着整体抖动 —— 用户原话「开启了之后多个√ 尺寸会变化」。
          // 选中态交给底色（Material 的 secondaryContainer 填充）表达，一样明确。
        )
      }
      item(key = "search-scope-divider") {
        VerticalDivider(
          modifier = Modifier.height(24.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )
      }
    }
    items(EmbySearchFilter.entries, key = { it.name }) { filter ->
      val checked =
        if (filter == EmbySearchFilter.ALL) selected.isEmpty() else filter in selected

      FilterChip(
        selected = checked,
        onClick = {
          onSelectedChange(
            when {
              // 「全部」= 一键清空，回到无筛选
              filter == EmbySearchFilter.ALL -> emptySet()
              filter in selected -> selected - filter
              else -> selected + filter
            },
          )
        },
        label = {
          Text(
            text = filter.label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
          )
        },
        // 同「仅本目录」：不放对勾，避免勾选瞬间宽度跳动。选中只看底色。
      )
    }
  }
}
