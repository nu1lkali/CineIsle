package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyEndpoint
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

/**
 * 「第二地址」编辑器：给同一台服务器再配一个入口（家里内网 / 外面 frp 公网）。
 *
 * 只做一件事，所以没并进服务器编辑对话框 —— 那个已经有六个字段，
 * 再塞三项进去会让人以为「不填就加不了服务器」。
 *
 * 留空保存 = 取消第二地址；主地址不在这里改。Classic 与 Cine 两套服务器管理页共用。
 */
@Composable
internal fun AltEndpointDialog(
  server: EmbyServer,
  initial: EmbyEndpoint?,
  onDismiss: () -> Unit,
  onSave: (EmbyEndpoint?) -> Unit,
) {
  var host by remember { mutableStateOf(initial?.host.orEmpty()) }
  var port by remember { mutableStateOf(initial?.port?.toString().orEmpty()) }
  var useHttps by remember { mutableStateOf(initial?.useHttps ?: false) }

  val portValue = port.toIntOrNull()
  val hostBlank = host.isBlank()
  val valid = hostBlank || (portValue != null && portValue in 1..65535)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("第二地址") },
    text = {
      Column {
        Text(
          text = "同一台服务器的另一个入口，例如家里的内网地址。" +
            "应用会在网络变化时同时探测两个地址，谁先连通就用谁；" +
            "两个都不通才会判定这台服务器有问题。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          label = { Text("主机（IP 或域名）") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = port,
          onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
          label = { Text("端口") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "使用 HTTPS",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
          )
          Switch(checked = useHttps, onCheckedChange = { useHttps = it })
        }
        Text(
          text = "留空保存即取消第二地址。主地址仍是 ${server.host}:${server.port}，不在此处修改。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val p = portValue
          if (hostBlank || p == null) {
            onSave(null)
          } else {
            onSave(EmbyEndpoint(host.trim(), p, useHttps))
          }
        },
        enabled = valid,
      ) {
        Text("保存")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}
