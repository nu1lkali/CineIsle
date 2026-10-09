package app.marlboroadvance.mpvex.ui.player

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「不喜欢原因」输入框 —— 长按播放器/列表里的不喜欢按钮时弹出。
 *
 * 纯 UI 组件，**不碰任何状态**：初值由 [initialValue] 传入、保存回调 [onConfirm] 抛出。
 * 这样三套播放器 + 详情页可以共用同一个框，而不必各自实现一遍状态。
 *
 * @param showMarked 当前是否已标记。已标记时标题是「修改备注」，
 *   否则是「标记为不喜欢」—— 措辞要跟着实际会发生的事变，
 *   否则用户看到「修改备注」却发现这是第一次标记，会以为界面出错了。
 */
@Composable
fun DislikeRemarkDialog(
  initialValue: String,
  showMarked: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  // rememberSaveable：旋屏/切后台重建时不丢用户已输入的字
  var text by rememberSaveable { mutableStateOf(initialValue) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (showMarked) "修改备注" else "标记为不喜欢") },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = if (showMarked) {
            "写一句原因，方便以后回想为什么不想看它。"
          } else {
            "写一句原因（可不填）。保存即标记为不喜欢。"
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          singleLine = false,
          maxLines = 4,
          placeholder = { Text("比如：剧情烂 / 太拖沓 / 画质差") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(text) }) {
        // 未标记时按钮是「保存并标记」而不是含糊的「确定」——
        // 这个按钮会真的改变标记状态，措辞要说清楚。
        Text(if (showMarked) "保存" else "保存并标记")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}