package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 版本选择卡片的一行数据（与 Emby / Smart Merge 两套模型解耦）。 */
data class EmbyVersionOption(
  val id: String,
  val title: String,
  /** 左上角分辨率角标，空串则不画 */
  val badge: String = "",
  /** 技术规格标签：编码 / HDR / 音频 / 容器 / 体积 / 码率 */
  val spec: List<String> = emptyList(),
  /** 是否使用了本机手动补全名 */
  val customName: Boolean = false,
  /** 文件名 / 路径的次要信息 */
  val path: String? = null,
)

/**
 * 版本选择底部抽屉。
 *
 * 之前用 DropdownMenu：每条版本要塞「标题 + 完整规格 + 编辑按钮」，几行字叠在一起、
 * 菜单又窄，数据一多就很挤。改成底部抽屉后：
 *  - 卡片横向占满，标题最多两行；
 *  - 分辨率放角标，其余规格拆成小标签（自动换行），一眼能扫；
 *  - 选中项用主题色描边 + 勾，编辑按钮固定在右上角；
 *  - 数据一个不少，长名字 / 长文件名也有地方换行。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmbyVersionPickerSheet(
  options: List<EmbyVersionOption>,
  selectedId: String?,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
  onEditName: ((String) -> Unit)? = null,
  /**
   * 非空时在列表底部显示「调整连播顺序」入口。
   *
   * 为什么要放在这个抽屉里：版本（`MediaSources`）和连播顺序是同一件事的两个面 ——
   * 用户在这一屏认识「有哪几个版本」，紧接着就会想「播完这个该接哪个」。
   * 放到设置页反而要多跳两层。
   */
  onEditOrder: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
      Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
          "选择版本",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          "${options.size} 个版本 · 点击卡片切换" +
            if (onEditName != null) " · 右上角铅笔可改本机名称" else "",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.size(8.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 560.dp)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        options.forEach { option ->
          VersionOptionCard(
            option = option,
            selected = option.id == selectedId,
            onSelect = { onSelect(option.id) },
            onEditName = onEditName?.let { edit -> { edit(option.id) } },
          )
        }
        if (onEditOrder != null) {
          Spacer(modifier = Modifier.size(4.dp))
          OutlinedButton(
            onClick = onEditOrder,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(
              Icons.Filled.SwapVert,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("调整连播顺序")
          }
          Text(
            "决定同一影片多个版本之间的衔接次序（保存后切换为「自定义顺序」）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
        Spacer(modifier = Modifier.size(4.dp))
      }
    }
  }
}

@Composable
private fun VersionOptionCard(
  option: EmbyVersionOption,
  selected: Boolean,
  onSelect: () -> Unit,
  onEditName: (() -> Unit)?,
) {
  OutlinedCard(
    onClick = onSelect,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.outlinedCardColors(
      containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      },
    ),
    border = BorderStroke(
      width = if (selected) 1.5.dp else 1.dp,
      color = if (selected) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
      },
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.Top) {
        if (option.badge.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Text(
              option.badge,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          if (option.customName) {
            Text(
              "本机名称",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Text(
            option.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (selected) {
          Icon(
            Icons.Filled.Check,
            contentDescription = "当前版本",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
          )
        }
        if (onEditName != null) {
          IconButton(
            onClick = onEditName,
            modifier = Modifier.size(34.dp),
          ) {
            Icon(
              Icons.Filled.Edit,
              contentDescription = "编辑版本名",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      if (option.spec.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          option.spec.forEach { SpecPill(it) }
        }
      }

      option.path?.takeIf { it.isNotBlank() }?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SpecPill(text: String) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
