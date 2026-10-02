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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

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

    // 全局播放偏好：与具体哪台服务器无关，所以不挂在某个 ServerCard 上，
    // 单独放在列表最上面一张「播放设置」卡里。
    val browserPreferences = koinInject<BrowserPreferences>()
    val resolveDirectLink by browserPreferences.embyResolveDirectLink.collectAsState()
    val clearProgressOnDownload by browserPreferences.embyClearProgressOnDownload.collectAsState()

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
          // 全局播放设置（与服务器无关），排在服务器卡片前面
          item {
            PlaybackSettingsCard(
              resolveDirectLink = resolveDirectLink,
              onToggleResolveDirectLink = {
                browserPreferences.embyResolveDirectLink.set(it)
              },
              clearProgressOnDownload = clearProgressOnDownload,
              onToggleClearProgressOnDownload = {
                browserPreferences.embyClearProgressOnDownload.set(it)
              },
            )
          }
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

/**
 * 「播放设置」卡片：与服务端无关的全局开关。
 *
 * 目前只有「预解析直链（302）」一项：针对网盘 / STRM 这类会 302 跳转、直链还带有效期的源。
 * 默认关闭 —— 对直出型服务器（绝大多数）多一次往返没有收益，交给用户按自己的源决定。
 */
@Composable
private fun PlaybackSettingsCard(
  resolveDirectLink: Boolean,
  onToggleResolveDirectLink: (Boolean) -> Unit,
  clearProgressOnDownload: Boolean,
  onToggleClearProgressOnDownload: (Boolean) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "播放设置",
        style = MaterialTheme.typography.titleMedium,
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "预解析直链（302）",
            style = MaterialTheme.typography.titleSmall,
          )
          Text(
            text = "播放前先取出跳转后的真实地址。用于网盘 / STRM 这类带 302 跳转、" +
              "直链有有效期的源；解析失败会自动回退，不影响正常播放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = resolveDirectLink,
          onCheckedChange = onToggleResolveDirectLink,
        )
      }
      // ── 下载完成后清除服务端播放进度（默认关）──
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "下载完成后清除服务端播放进度",
            style = MaterialTheme.typography.titleSmall,
          )
          Text(
            text = "下载完一条就把服务器上这条的续播位置清零，「继续观看」不再挂着它。" +
              "只清进度，不动「已看」标记。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = clearProgressOnDownload,
          onCheckedChange = onToggleClearProgressOnDownload,
        )
      }
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
  // 地址与端口拆成两个输入框：地址只填主机（IP / 域名），端口单独填
  var host by remember { mutableStateOf(initial?.host ?: "") }
  var port by remember { mutableStateOf((initial?.port ?: DEFAULT_HTTP_PORT).toString()) }
  var username by remember { mutableStateOf(initial?.username ?: "") }
  var password by remember { mutableStateOf(initial?.password ?: "") }
  var useHttps by remember { mutableStateOf(initial?.useHttps ?: false) }
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
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = host,
            onValueChange = { input ->
              // 容错：整段粘 http(s)://host:port 时自动拆到两个框，省得手拆
              val parsedUrl = if (input.contains("://")) parseServerAddress(input) else null
              if (parsedUrl != null) {
                host = parsedUrl.first
                port = parsedUrl.second.toString()
                useHttps = parsedUrl.third
              } else {
                host = input.trim()
              }
            },
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.10") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = port,
            onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
            label = { Text("端口") },
            placeholder = { Text((if (useHttps) HTTPS_PORT else DEFAULT_HTTP_PORT).toString()) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.5f),
          )
        }
        Text(
          text = "地址只填 IP 或域名；端口默认 8096，勾选 HTTPS 后默认 443",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
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
              // 端口跟着「默认值」走：只在另一个协议的默认端口上切换，
              // 用户手填过的自定义端口（如 8920）不动
              val current = port.trim()
              port = when {
                checked && (current.isEmpty() || current == DEFAULT_HTTP_PORT.toString()) ->
                  HTTPS_PORT.toString()
                !checked && (current.isEmpty() || current == HTTPS_PORT.toString()) ->
                  DEFAULT_HTTP_PORT.toString()
                else -> port
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
          // 地址框理论上只有主机；容错处理「主机里还带着端口」的旧习惯写法
          val (rawHost, embeddedPort) = splitHostAndPort(host)
          if (rawHost.isBlank()) {
            errorMessage = "请填写服务器地址"
            return@Button
          }
          val finalHttps = useHttps
          val portText = port.trim()
          val finalPort = when {
            embeddedPort != null -> embeddedPort
            portText.isEmpty() -> if (finalHttps) HTTPS_PORT else DEFAULT_HTTP_PORT
            else -> portText.toIntOrNull() ?: -1
          }
          if (finalPort !in 1..65535) {
            errorMessage = "端口请填写 1~65535 之间的数字"
            return@Button
          }
          val finalName = name.ifBlank { rawHost }

          if (!requireLogin) {
            onConfirm(finalName, rawHost, finalPort, finalHttps, username, password)
            return@Button
          }

          // 添加场景：先登录验证，成功后 loginAndSave 已落库，直接关闭对话框
          isConnecting = true
          errorMessage = null
          scope.launch {
            val saved = viewModel.loginAndSave(finalName, rawHost, finalPort, finalHttps, username, password)
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

/** Emby 默认 HTTP 端口 */
private const val DEFAULT_HTTP_PORT = 8096

/** HTTPS 默认端口（标准 443；Emby 自带的 8920 需要手填） */
private const val HTTPS_PORT = 443

/**
 * 从地址文本里拆出主机与端口（容错用）。
 *
 * 支持 `host`、`host:8096`、`http://host:8096/emby` 这几种写法；
 * 拿不到端口时返回 null，由「端口」输入框的值兜底。
 */
private fun splitHostAndPort(raw: String): Pair<String, Int?> {
  var s = raw.trim().substringAfter("://", raw.trim())
  s = s.substringBefore('/')
  val colon = s.lastIndexOf(':')
  if (colon > 0) {
    val embedded = s.substring(colon + 1).toIntOrNull()
    if (embedded != null) return s.substring(0, colon) to embedded
  }
  return s to null
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
    // 用 Emby 自带的 https 端口 8920 的话，端口框里填 8920 即可。
    val defaultPort = if (useHttps) HTTPS_PORT else DEFAULT_HTTP_PORT
    Triple(input, defaultPort, useHttps)
  }
}
