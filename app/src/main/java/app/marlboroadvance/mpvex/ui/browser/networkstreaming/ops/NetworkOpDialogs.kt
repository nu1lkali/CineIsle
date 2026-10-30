package app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 批量写操作进行中的**阻塞提示框**。
 *
 * 为什么不复用底部的下载进度条 / 上传面板：
 * - 这三件事可以同时发生，塞进同一条只会互相顶掉；
 * - 复制 / 移动是**用户刚点完就等结果**的短操作（AList 在服务端搬，通常几秒），
 *   一个带计数的遮罩就已经够，不需要可收起的面板。
 *
 * ⚠️ **不可取消**：AList 的 `fs/copy` / `fs/move` 一旦发出就没有中止接口
 * （服务端会跑完）。给一个「取消」按钮等于骗人 —— 点了任务照样在服务器上继续。
 * 所以这里只显示进度、不提供取消，`onDismissRequest` 也是空实现。
 */
@Composable
fun NetworkOpProgressDialog(label: String, done: Int, total: Int) {
  AlertDialog(
    onDismissRequest = { /* 故意不响应：任务已发出、服务端没有中止接口 */ },
    title = { Text(label) },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
          progress = { if (total <= 0) 0f else done.toFloat() / total },
          modifier = Modifier.fillMaxWidth().height(4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = if (total > 0) "$done / $total" else "准备中…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {},
  )
}
