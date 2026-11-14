package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      history.forEach { keyword ->
        HistoryKeywordPill(
          keyword = keyword,
          onPick = { onPick(keyword) },
          onRemove = { onRemove(keyword) },
        )
      }
    }
  }
}

/** 词条高度：比 M3 默认 chip 略高一点，给删除区留出竖向空间 */
private val HistoryPillHeight = 34.dp

/**
 * 文本区的**保底宽度**（含左右内边距）。
 *
 * 这个值是整条改造的核心：一个字符的关键词原本会把 chip 撑到 ~48dp，
 * 删除叉紧贴文字右侧、两者间距只有一个 8dp 的 chip 内边距，拇指想「点词组」
 * 却落在叉上就删了。给文本区一个 76dp 的地板后，单字符词条的总宽变成
 * 76 + 1 + 28 ≈ 105dp，删除区被推到最右侧、与文字之间固定有 50dp 以上的空地。
 */
private val HistoryPillLabelMinWidth = 76.dp

/** 删除区宽度：固定一条，不随词条长短浮动（「删除按钮固定在最右侧」的由来） */
private val HistoryPillDeleteWidth = 28.dp

/**
 * 单条搜索历史词。
 *
 * 布局是**两段式**，而不是「一整块可点区域里塞一个叉」：
 * ```
 * ┌──────────────────────────┬──┬────┐
 * │ 关键词（左对齐，溢出省略） │▏ │ ✕  │
 * └──────────────────────────┴──┴────┘
 *  ←──── 点这里 = 用这个词搜索 ───→   ↑
 *                                    点这里 = 删除
 * ```
 * 三条与误触直接相关的取值：
 *  1. 文本区 `defaultMinSize(76.dp)` —— 内容再短也占住一块宽度，绝不塌到只剩图标宽；
 *  2. 两区之间有一条 0.5dp 的发丝线 —— 让「搜」和「删」在视觉上分成两块，
 *     而不是一段连续的热区；
 *  3. 删除区固定 28dp 宽、贴到词条最右 —— 宽度不随词条长度变化，且与文字之间
 *     至少有 50dp 的空白（单字符词条时）。相比改造前「16dp 的叉 + 8dp 间距」，
 *     删除占词条的比例从约 1/3 降到约 1/4，而它离文字的距离翻了六倍多。
 *     这里没有把它缩到更窄：16dp 已经低于可靠点击下限，再小就是「删不掉」。
 */
@Composable
private fun HistoryKeywordPill(
  keyword: String,
  onPick: () -> Unit,
  onRemove: () -> Unit,
) {
  val shape = RoundedCornerShape(8.dp)
  Row(
    modifier = Modifier
      .height(HistoryPillHeight)
      .clip(shape)
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        shape = shape,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // ── 文本区：点它 = 用这个词搜索 ──
    Box(
      modifier = Modifier
        .defaultMinSize(minWidth = HistoryPillLabelMinWidth)
        .fillMaxHeight()
        .clickable(onClick = onPick)
        .padding(start = 14.dp, end = 12.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      Text(
        text = keyword,
        maxLines = 1,
        // 长片名不把词条拉成一整行：封顶 180dp 后溢出省略，
        // 删除区因此永远停在词条自己的右边缘，不会跑到屏幕最右边去。
        modifier = Modifier.widthIn(max = 180.dp),
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    // ── 分隔线：把「搜」和「删」切成两个看得见的区 ──
    Box(
      modifier = Modifier
        .width(0.5.dp)
        .height(14.dp)
        .background(MaterialTheme.colorScheme.outlineVariant),
    )

    // ── 删除区：小热区、贴最右、去掉涟漪（涟漪会让人以为整块都能点） ──
    val interaction = remember { MutableInteractionSource() }
    Box(
      modifier = Modifier
        .width(HistoryPillDeleteWidth)
        .fillMaxHeight()
        .clickable(
          interactionSource = interaction,
          indication = null,
          onClickLabel = "删除该搜索历史",
          onClick = onRemove,
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "删除「$keyword」",
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
