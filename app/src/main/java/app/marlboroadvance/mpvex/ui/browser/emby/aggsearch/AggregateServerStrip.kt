package app.marlboroadvance.mpvex.ui.browser.emby.aggsearch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerOutcome
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerPhase

/**
 * 服务器参与范围条。
 *
 * ## 一个 chip 同时表达两件事
 *
 * - **填充与否** = 这台是否参与本次检索（可点，点击切换）；
 * - **左侧状态点** = 这台此刻的查询状态（进行中 / 已返回 / 空 / 失败 / 未开始）。
 *
 * 两件事必须放在同一个控件里：分成「选择区」和「状态区」两条会让人对不上「这个数字是哪台的」。
 *
 * ## 为什么长按 = 只搜这一台
 *
 * 「排除其余全部」是排查「到底哪台有这个片」的最短路径，比逐个点掉便宜。
 * 长按不额外开菜单，落点明确：只剩这一台参与，并立即重查。
 *
 * ## 为什么手写而不是 FilterChip
 *
 * `FilterChip` 的 `onClick` 自己吃掉手势，长按要再挂一层就会与它抢事件；
 * 这里用 [combinedClickable] 一次挂好两个手势，交互稳定。形状沿用项目里
 * 搜索历史胶囊那一套（圆角胶囊 + 发丝边），但**填充只用于表达「参与」**：
 * 未参与是透明底 + `outline` 描边，避免两种状态都出现浅蓝底色而无法分辨。
 *
 * ## 参与范围必须是参数，不能是 lambda
 *
 * ⚠️ 早期版本传 `vm::isParticipating`（方法引用），它内部读 `StateFlow.value`——
 * 那不是 Compose 能订阅的 snapshot state；而 `LazyRow` 按 `serverId` 复用 item，
 * `ServerOutcome` 内容没变时 item 被整体 skip，`selected` 永远不重算。
 * 结果是点「全选」后标题行的「已选 N/M 台」变了，chip 却只有一部分跟着变。
 * 现在把 [scopeIds] 作为参数传入，并把 `selected` 折进 items 的**数据**：
 * 它成了 item 的输入值，范围一变该 item 必然重组。
 */
@Composable
fun AggregateServerStrip(
  outcomes: List<ServerOutcome>,
  /** 参与集；`null` = 全部参与 */
  scopeIds: Set<Long>?,
  onToggle: (Long) -> Unit,
  onOnly: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  // `selected` 必须是 item 数据的一部分，不能在 itemContent 里靠捕获的外部 lambda 现算。
  val rows =
    outcomes.map { outcome ->
      outcome to (scopeIds?.contains(outcome.serverId) ?: true)
    }
  LazyRow(
    modifier = modifier.fillMaxWidth(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(rows, key = { it.first.serverId }) { (outcome, selected) ->
      ServerChip(
        outcome = outcome,
        selected = selected,
        onClick = { onToggle(outcome.serverId) },
        onLongClick = { onOnly(outcome.serverId) },
      )
    }
  }
}

@Composable
private fun ServerChip(
  outcome: ServerOutcome,
  selected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val shape = RoundedCornerShape(50)
  // ⚠️ 未参与必须用**透明**底，不能用 `surfaceContainerHigh`：本主题里它也是偏蓝的浅色，
  //    与 `primaryContainer` 并排时两者都像「选中」，用户无法分辨（现场反馈）。
  //    透明 + 描边读作「未选」；填充只有一种含义 = 参与。
  val container = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
  val content =
    if (selected) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    }
  val stroke =
    if (selected) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
      MaterialTheme.colorScheme.outline
    }

  Row(
    modifier = Modifier
      .height(34.dp)
      .clip(shape)
      .background(container)
      .border(width = 1.dp, color = stroke, shape = shape)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(start = 11.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    // 状态点：未参与的台不显示 —— 它本来就没查，给个灰点反而像「查了但没结果」
    if (selected) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(phaseColor(outcome.phase)),
      )
    }
    Text(
      text = outcome.name,
      style = MaterialTheme.typography.labelLarge,
      // 选中再叠一层字重差：只靠色块，色弱用户与低亮度屏上仍然难分辨
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      color = content,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 132.dp),
    )
    val trailing = trailingLabel(outcome, selected)
    if (trailing != null) {
      Text(
        text = trailing,
        style = MaterialTheme.typography.labelSmall,
        color = content.copy(alpha = 0.7f),
        maxLines = 1,
      )
    }
  }
}

/**
 * 尾部计数。
 *
 * 只在**有意义**的时候出现：
 * - `Ok` → 条数；
 * - `Empty` → 「空」；
 * - `Failed` / `Skipped` → 「失败」/「未登录」；
 * - 未参与 → 不显示（灰 chip 已经表达了）；
 * - 进行中 / 未开始 → 不显示（状态点已经表达了，写「查询中」会把胶囊撑得很长）。
 */
private fun trailingLabel(outcome: ServerOutcome, selected: Boolean): String? {
  if (!selected) return null
  return when (outcome.phase) {
    ServerPhase.Ok -> outcome.count.toString()
    ServerPhase.Empty -> "空"
    ServerPhase.Failed -> "失败"
    ServerPhase.Skipped -> "未登录"
    ServerPhase.Running, ServerPhase.Pending -> null
  }
}

/** 状态点颜色。刻意不用红色表示「空」：空是正常结果，不是错误 */
@Composable
private fun phaseColor(phase: ServerPhase): Color =
  when (phase) {
    ServerPhase.Running -> MaterialTheme.colorScheme.primary
    ServerPhase.Ok -> MaterialTheme.colorScheme.primary
    ServerPhase.Empty -> MaterialTheme.colorScheme.outline
    ServerPhase.Failed -> MaterialTheme.colorScheme.error
    ServerPhase.Skipped -> MaterialTheme.colorScheme.outlineVariant
    ServerPhase.Pending -> MaterialTheme.colorScheme.outlineVariant
  }
