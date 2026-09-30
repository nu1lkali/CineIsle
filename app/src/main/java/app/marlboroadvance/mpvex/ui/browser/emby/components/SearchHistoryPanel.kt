package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 搜索历史面板。首页全库搜索与媒体库内搜索共用。
 *
 * 只在搜索框为空时出现 —— 一旦开始输入，界面就该让位给结果/进度，
 * 而不是继续占着历史列表（这也是 YouTube / 各家商店的通行做法）。
 *
 * @param history 已按「最近使用 + 使用次数」排好序的关键词，由仓库给出
 * @param onPick 点某个词：把它填进搜索框并立即开搜
 * @param onRemove 删掉某一个词
 * @param onClearAll 清空全部
 * @param emptyHint 没有任何历史时显示的一行灰字提示
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchHistoryPanel(
  history: List<String>,
  onPick: (String) -> Unit,
  onRemove: (String) -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier,
  emptyHint: String,
) {
  if (history.isEmpty()) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.History,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp),
      )
      Text(
        text = emptyHint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.History,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = "搜索历史",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
      )
      // 把「清空」推到最右，和下面的词条区分开
      Spacer(modifier = Modifier.weight(1f))
      TextButton(onClick = onClearAll) {
        Text("清空", style = MaterialTheme.typography.labelLarge)
      }
    }

    FlowRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      history.forEach { keyword ->
        InputChip(
          selected = false,
          onClick = { onPick(keyword) },
          label = {
            Text(
              text = keyword,
              maxLines = 1,
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          trailingIcon = {
            // 小叉是「次要操作」，单独做成一个小热区并去掉涟漪 —— 给整个 Chip 加涟漪会显得很吵，
            // 同时也要避免它和「点词组去搜索」抢点击目标。
            val interaction = remember { MutableInteractionSource() }
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = "删除「$keyword」",
              modifier = Modifier
                .size(16.dp)
                .clickable(interactionSource = interaction, indication = null) {
                  onRemove(keyword)
                },
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
        )
      }
    }
  }
}
