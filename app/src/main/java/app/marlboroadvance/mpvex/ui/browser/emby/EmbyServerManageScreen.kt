package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Emby 服务器管理页：新增、编辑、删除、切换服务器。
 */
@Serializable
object EmbyServerManageScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val servers by viewModel.servers.collectAsState()
    val currentServer by viewModel.currentServer.collectAsState()

    var editing by remember { mutableStateOf<EmbyServer?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EmbyServer?>(null) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Emby 服务器") },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
      floatingActionButton = {
        FloatingActionButton(onClick = { showAddDialog = true }) {
          Icon(Icons.Default.Add, contentDescription = "添加服务器")
        }
      },
    ) { padding ->
      if (servers.isEmpty()) {
        EmbyEmptyState(
          message = "还没有添加任何 Emby 服务器",
          buttonText = "添加服务器",
          onAction = { showAddDialog = true },
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        )
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(servers, key = { it.id }) { server ->
            ServerCard(
              server = server,
              isCurrent = server.id == currentServer?.id,
              onSelect = { viewModel.switchServer(server) },
              onEdit = { editing = server },
              onDelete = { pendingDelete = server },
            )
          }
        }
      }
    }

    if (showAddDialog) {
      ServerEditDialog(
        title = "添加服务器",
        initial = null,
        onDismiss = { showAddDialog = false },
        onConfirm = { name, host, port, useHttps, username, password ->
          showAddDialog = false
          // 登录成功后才落库，避免保存无效配置
          viewModel.addServer(
            EmbyServer(
              name = name,
              host = host,
              port = port,
              useHttps = useHttps,
              username = username,
              password = password,
            ),
          )
        },
        requireLogin = true,
      )
    }

    editing?.let { server ->
      ServerEditDialog(
        title = "编辑服务器",
        initial = server,
        onDismiss = { editing = null },
        onConfirm = { name, host, port, useHttps, username, password ->
          editing = null
          viewModel.updateServer(
            server.copy(
              name = name,
              host = host,
              port = port,
              useHttps = useHttps,
              username = username,
              password = password,
            ),
          )
        },
        requireLogin = false,
      )
    }

    pendingDelete?.let { server ->
      ConfirmDialog(
        title = "删除服务器",
        subtitle = "确定删除「${server.name}」吗？仅移除本机的连接配置，不会删除服务器上的媒体。",
        onConfirm = {
          viewModel.deleteServer(server)
          pendingDelete = null
        },
        onCancel = { pendingDelete = null },
      )
    }
  }
}

@Composable
private fun ServerCard(
  server: EmbyServer,
  isCurrent: Boolean,
  onSelect: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    onClick = onSelect,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surface
      },
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = when {
          !server.isLoggedIn -> Icons.Default.Error
          isCurrent -> Icons.Default.CheckCircle
          else -> Icons.Default.Dns
        },
        contentDescription = null,
        tint = when {
          !server.isLoggedIn -> MaterialTheme.colorScheme.error
          isCurrent -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
      )

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(start = 12.dp),
      ) {
        Text(text = server.name, style = MaterialTheme.typography.titleMedium)
        Text(
          text = server.baseUrl,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = when {
            !server.isLoggedIn -> "未登录"
            server.serverName.isNotBlank() -> "${server.serverName} · ${server.version}"
            else -> "已登录"
          },
          style = MaterialTheme.typography.bodySmall,
          color = if (server.isLoggedIn) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.error
          },
        )
      }

      IconButton(onClick = onEdit) {
        Icon(Icons.Default.Edit, contentDescription = "编辑")
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = "删除")
      }
    }
  }
}

/**
 * 服务器编辑对话框。
 *
 * @param requireLogin true 时会先用账号密码登录验证，成功后才保存（添加场景）
 */
@Composable
private fun ServerEditDialog(
  title: String,
  initial: EmbyServer?,
  requireLogin: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (name: String, host: String, port: Int, useHttps: Boolean, username: String, password: String) -> Unit,
) {
  val context = LocalContext.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val scope = rememberCoroutineScope()

  var name by remember { mutableStateOf(initial?.name ?: "") }
  var address by remember { mutableStateOf(initial?.baseUrl ?: "http://") }
  var username by remember { mutableStateOf(initial?.username ?: "") }
  var password by remember { mutableStateOf(initial?.password ?: "") }
  // 勾选框初始值：优先取已存服务器的 useHttps，编辑场景再兜底按地址里的 scheme 推一次，
  // 避免「地址是 https:// 但框没勾」的初始错位（提交时以这个框为准）
  var useHttps by remember {
    mutableStateOf(
      initial?.useHttps ?: (initial?.baseUrl?.startsWith("https://", ignoreCase = true) == true),
    )
  }
  var isConnecting by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("名称") },
          placeholder = { Text("例如：家里的 Emby") },
          singleLine = true,
        )
        OutlinedTextField(
          value = address,
          onValueChange = { input ->
            address = input
            // 用户手输显式 scheme 时同步勾选框，避免「地址写着 https:// 但框没勾」的错位
            when {
              input.startsWith("https://", ignoreCase = true) -> useHttps = true
              input.startsWith("http://", ignoreCase = true) -> useHttps = false
            }
          },
          label = { Text("服务器地址") },
          placeholder = { Text("http://192.168.1.10:8096") },
          singleLine = true,
          supportingText = {
            Text("可写 https://域名 或 192.168.1.10:8096；不写端口时 https 默认 443、http 默认 8096")
          },
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("用户名") },
          singleLine = true,
        )
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("密码") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = useHttps,
            onCheckedChange = { checked ->
              useHttps = checked
              // 勾选框是最终裁决：同步改写地址里的 scheme，保证「看到的」和「实际用的」一致。
              // （修复：以前 confirm 用的是 parseServerAddress 从地址文本推出的 https，
              //   地址写裸 IP 时勾了等于没勾，请求仍然走 http。）
              address = when {
                checked && address.startsWith("http://", ignoreCase = true) ->
                  "https://" + address.substring("http://".length)
                !checked && address.startsWith("https://", ignoreCase = true) ->
                  "http://" + address.substring("https://".length)
                else -> address
              }
            },
          )
          Text("使用 HTTPS")
        }
        errorMessage?.let {
          Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (isConnecting) return@Button
          val parsed = parseServerAddress(address)
          if (parsed == null) {
            errorMessage = "服务器地址格式不正确"
            return@Button
          }
          val (host, port, _) = parsed
          // scheme 以勾选框为准（parseServerAddress 只负责拆 host/port）；
          // 勾选框已与地址文本双向同步，这里不会再出现「勾了却走 http」的情况
          val finalHttps = useHttps
          val finalName = name.ifBlank { host }

          if (!requireLogin) {
            onConfirm(finalName, host, port, finalHttps, username, password)
            return@Button
          }

          // 添加场景：先登录验证，成功后 loginAndSave 已落库，直接关闭对话框
          isConnecting = true
          errorMessage = null
          scope.launch {
            val saved = viewModel.loginAndSave(finalName, host, port, finalHttps, username, password)
            isConnecting = false
            if (saved != null) {
              onDismiss()
            } else {
              errorMessage = viewModel.error.value ?: "登录失败，请检查地址与账号密码"
            }
          }
        },
      ) {
        // 连接中也要保留文字（只留转圈的话，网络异常要等 15~60s 超时才恢复，
        // 期间按钮看起来就是空白的）；小号转圈放进 Row，不再被按钮高度裁掉
        if (isConnecting) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
              modifier = Modifier.padding(start = 8.dp),
              text = if (requireLogin) "连接中…" else "保存中…",
            )
          }
        } else {
          Text(if (requireLogin) "登录并添加" else "保存")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("取消")
      }
    },
  )
}

/**
 * 解析服务器地址。
 *
 * 支持：`http://host:8096`、`https://host:8920`、`host`、`host:8096`、`http://host`
 *
 * @return (host, port, useHttps)，解析失败返回 null
 */
private fun parseServerAddress(raw: String): Triple<String, Int, Boolean>? {
  var input = raw.trim().trimEnd('/')
  if (input.isBlank()) return null

  val useHttps = input.startsWith("https://", ignoreCase = true)
  input = input
    .removePrefix("http://")
    .removePrefix("https://")
    .trim()

  if (input.isBlank()) return null

  // 去掉路径部分（如 /emby），客户端只关心 host:port
  val slashIndex = input.indexOf('/')
  if (slashIndex >= 0) input = input.substring(0, slashIndex)

  return if (input.contains(":")) {
    val parts = input.split(":")
    val host = parts.getOrNull(0)?.trim().orEmpty()
    val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    if (host.isBlank()) null else Triple(host, port, useHttps)
  } else {
    // 端口缺省值：https → 443（标准 HTTPS，反代 / 域名场景最常见），http → 8096（Emby 默认）。
    // 用 Emby 自带的 https 端口 8920 的话，地址里显式写 :8920 即可。
    val defaultPort = if (useHttps) 443 else 8096
    Triple(input, defaultPort, useHttps)
  }
}
