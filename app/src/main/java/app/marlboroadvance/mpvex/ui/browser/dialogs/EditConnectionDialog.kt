package app.marlboroadvance.mpvex.ui.browser.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol

/**
 * 编辑网络连接的**底部抽屉**表单。
 *
 * 与 [AddConnectionSheet] 是同一套版式、同一套字段（只有初值来源与保存方式不同），
 * 改动时**两边一起改** —— 这两个表单历史上已经漂移过一次（名称/协议那一行的
 * 权重一边 0.50/0.50、一边 0.60/0.40）。
 *
 * 为什么用抽屉而不是 `AlertDialog`（键盘一开就抖、`widthIn(min=400dp)` 撑出小屏）
 * 见 [AddConnectionSheet] 的 KDoc。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditConnectionSheet(
  connection: NetworkConnection,
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  var name by remember(connection.id) { mutableStateOf(connection.name) }
  var protocol by remember(connection.id) { mutableStateOf(connection.protocol) }
  var host by remember(connection.id) { mutableStateOf(connection.host) }
  var port by remember(connection.id) { mutableStateOf(connection.port.toString()) }
  var username by remember(connection.id) { mutableStateOf(connection.username) }
  var password by remember(connection.id) { mutableStateOf(connection.password) }
  var path by remember(connection.id) { mutableStateOf(connection.path) }
  var isAnonymous by remember(connection.id) { mutableStateOf(connection.isAnonymous) }
  var useHttps by remember(connection.id) { mutableStateOf(connection.useHttps) }
  var protocolMenuExpanded by remember { mutableStateOf(false) }

  val handleDismiss = {
    onDismiss()
  }

  val handleSave = {
    val updatedConnection =
      connection.copy(
        name = name,
        protocol = protocol,
        host = host,
        port = port.toIntOrNull() ?: protocol.defaultPort,
        username = if (isAnonymous) "" else username,
        password = if (isAnonymous) "" else password,
        path = path.ifBlank { "/" },
        isAnonymous = isAnonymous,
        useHttps = useHttps,
      )
    onSave(updatedConnection)
  }

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = handleDismiss,
    sheetState = sheetState,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.i18n_edit_connection),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
      )

      // Name and Protocol in one row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Connection Name
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("名称", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(0.50f),
          singleLine = true,
        )

        // Protocol Dropdown
        ExposedDropdownMenuBox(
          expanded = protocolMenuExpanded,
          onExpandedChange = { protocolMenuExpanded = it },
          modifier = Modifier.weight(0.50f),
        ) {
          OutlinedTextField(
            value = protocol.displayName,
            onValueChange = { },
            readOnly = true,
            label = { Text("协议", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolMenuExpanded) },
            modifier = Modifier
              .fillMaxWidth()
              .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(
            expanded = protocolMenuExpanded,
            onDismissRequest = { protocolMenuExpanded = false },
          ) {
            NetworkProtocol.entries.forEach { proto ->
              DropdownMenuItem(
                text = { Text(proto.displayName) },
                onClick = {
                  protocol = proto
                  port = proto.defaultPort.toString()
                  protocolMenuExpanded = false
                },
              )
            }
          }
        }
      }

      // Host
      OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("主机 / IP 地址", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("192.168.1.100", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      )

      // Port and Path in one row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Port
        OutlinedTextField(
          value = port,
          onValueChange = { port = it },
          label = { Text("端口", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(0.3f),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        // Path
        OutlinedTextField(
          value = path,
          onValueChange = { path = it },
          label = { Text("路径", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(0.7f),
          singleLine = true,
          placeholder = { Text("/", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
      }

      // Anonymous checkbox
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Checkbox(
          checked = isAnonymous,
          onCheckedChange = { isAnonymous = it },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("匿名 / 访客访问")
      }

      // HTTPS 开关（HTTP 系协议才有意义：WebDAV / AList）
      if (protocol == NetworkProtocol.WEBDAV || protocol == NetworkProtocol.ALIST) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Checkbox(
            checked = useHttps,
            onCheckedChange = {
              useHttps = it
              // 端口还停在 WebDAV 的默认值上才自动跟着切；AList 的 5244 不随 https 变，
              // 用户手改过端口也别动他的
              if (protocol == NetworkProtocol.WEBDAV) {
                if (it && port == "80") {
                  port = "443"
                } else if (!it && port == "443") {
                  port = "80"
                }
              }
            },
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("使用 HTTPS（安全连接）")
        }
      }

      // AList 是独立协议，说明一下登录口径（留空 = 访客）
      if (protocol == NetworkProtocol.ALIST) {
        Text(
          text = stringResource(R.string.i18n_network_alist_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Username and Password in one row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Username
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("用户名", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(0.50f),
          singleLine = true,
          enabled = !isAnonymous,
        )

        // Password
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("密码", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          modifier = Modifier.weight(0.50f),
          singleLine = true,
          enabled = !isAnonymous,
        )
      }

      // 底部按钮行（原来 AlertDialog 的 confirm / dismiss）
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedButton(
          onClick = handleDismiss,
          modifier = Modifier.weight(1f),
        ) {
          Text("取消", fontWeight = FontWeight.Medium)
        }
        Button(
          onClick = handleSave,
          enabled = host.isNotBlank() && (isAnonymous || username.isNotBlank()),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = stringResource(R.string.i18n_save),
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}
