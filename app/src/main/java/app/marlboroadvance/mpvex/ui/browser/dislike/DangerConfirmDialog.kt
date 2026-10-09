package app.marlboroadvance.mpvex.ui.browser.dislike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「高危操作」确认框 —— **必须手动输入指定词才放行**。
 *
 * ## 为什么不用普通的确认框
 *
 * 「删除影片」是**物理删除服务器上的文件**，不可逆。普通的「确定 / 取消」弹窗
 * 用户扫一眼标题就顺手点了确定 —— 那点摩擦力拦不住误触（尤其是从侧滑按钮点进来的，
 * 本来就是误触高发路径）。要求手输一个词，等于强制用户先看清自己在干什么。
 *
 * ## 为什么单条和批量共用同一个组件
 *
 * 两个入口的危险等级相同、要传达的警告也相同。共用一个组件能保证：
 * 措辞（[title] / [warning]）与交互（输入解锁确定按钮）**永远一致** ——
 * 分成两套实现的话，改一处漏一处是迟早的事。
 *
 * @param title 标题，一句话说清要发生什么（如「删除这 3 个视频文件？」）。
 * @param warning 警告正文。**必须写清"这是物理删除、不可恢复、影响服务器"**。
 * @param itemLines 要删除的条目名列表（单条就传一条）。逐条列出来，
 *   让用户能核对"到底删哪几个"，而不是只看到一个数字。
 * @param confirmKeyword 需要手输的词，必须与确定按钮上的提示一致。
 * @param onConfirm 输入正确并点确定后触发。
 */
@Composable
fun DangerConfirmDialog(
  title: String,
  warning: String,
  itemLines: List<String>,
  confirmKeyword: String = "删除",
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  // rememberSaveable：旋屏/切后台重建时不丢已输入的内容
  var typed by rememberSaveable { mutableStateOf("") }
  // 严格相等（去掉首尾空白）：不区分大小写会放过 "Delete " 这类手滑
  val unlocked = typed.trim() == confirmKeyword

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = warning,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )

        // 待删清单。最多显示 8 条，多出来的用「…等 N 个」收口 ——
        // 批量删几百条时把所有名字都列出来会把对话框撑爆，反而看不清。
        if (itemLines.isNotEmpty()) {
          Text(
            text = "将删除以下内容：",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
          )
          itemLines.take(MAX_LISTED).forEach { line ->
            Text(
              text = "· $line",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (itemLines.size > MAX_LISTED) {
            Text(
              text = "…… 等共 ${itemLines.size} 个",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Text(
          text = "如确认，请在下方输入「$confirmKeyword」：",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        OutlinedTextField(
          value = typed,
          onValueChange = { typed = it },
          singleLine = true,
          placeholder = { Text(confirmKeyword) },
          // 输入正确时边框转成错误色，给一个"解锁了"的视觉反馈
          isError = typed.isNotEmpty() && !unlocked,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        // 没输对就不给点 —— 这是本组件存在的全部意义
        enabled = unlocked,
      ) {
        Text(
          confirmKeyword,
          fontWeight = FontWeight.Bold,
          color =
            if (unlocked) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

/** 清单里最多逐条列出多少个名字，超出部分收口成「等共 N 个」。 */
private const val MAX_LISTED = 8
