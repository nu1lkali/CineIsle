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
 * 新增网络连接的**底部抽屉**表单（AList / WebDAV / SMB / FTP 共用一套）。
 *
 * ## 为什么从 `AlertDialog` 改成抽屉
 *
 * 这个表单是最长的一个（名称 / 协议 / 主机 / 端口 / 路径 / 匿名 / HTTPS / 说明 / 账号密码，
 * 换 AList 时还要多两行）。放在 `AlertDialog` 里有两个硬伤：
 *
 *  1. **一开键盘就抖**：`AlertDialog` 的内容是**垂直居中**的，键盘弹出会压缩对话框窗口，
 *     窗口一缩、居中位置就变，正文又是 `verticalScroll` 的可变高度 ⇒ 键盘动画的每一帧
 *     都在重排，看起来就是整张表单在抖。底部抽屉是**贴底**的，从底部被顶起来时内容相对
 *     底部不动，天然没有这个问题；
 *  2. **`widthIn(min = 400.dp)`**：小屏机身宽度不到 400dp，对话框会被强行撑出屏幕。
 *
 * 抽屉还有额外好处：长表单可以随内容长高、超出屏幕时内部滚动，而不是挤在一个固定高度的对话框里。
 *
 * ⚠️ 与 [app.marlboroadvance.mpvex.ui.browser.sheets.PlayLinkSheet] 保持同一套抽屉范式
 *   （dragHandle + 可滚动 Column + 底部按钮行），别再各写一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionSheet(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  var name by remember { mutableStateOf("") }
  var protocol by remember { mutableStateOf(NetworkProtocol.SMB) }
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf(protocol.defaultPort.toString()) }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var path by remember { mutableStateOf("/") }
  var isAnonymous by remember { mutableStateOf(false) }
  var useHttps by remember { mutableStateOf(false) }
  var protocolMenuExpanded by remember { mutableStateOf(false) }

  val handleDismiss = {
    onDismiss()
  }

  val handleSave = {
    val connection =
      NetworkConnection(
        name = name.ifBlank { "${protocol.displayName} - $host" },
        protocol = protocol,
        host = host,
        port = port.toIntOrNull() ?: protocol.defaultPort,
        username = if (isAnonymous) "" else username,
        password = if (isAnonymous) "" else password,
        path = path.ifBlank { "/" },
        isAnonymous = isAnonymous,
        useHttps = useHttps,
      )
    onSave(connection)
  }

  // skipPartiallyExpanded：表单场景不需要「半展开」那一档 —— 直接全展开，
  // 免得用户先看到半个表单、还得手动往上拖一次才够用。
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
        text = stringResource(R.string.i18n_add_network_connection),
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

      // Anonymous and HTTPS checkboxes
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
