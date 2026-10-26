package app.marlboroadvance.mpvex.ui.cine.servers

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyFailoverController
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetCheckRow
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetFrame
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetTextField
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassPanel
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch

/**
 * Cine 版「Emby 服务器管理」。
 *
 * ## 与 Classic 的差别
 * Classic 是 Material `Card` + `TopAppBar` + `AlertDialog` 表单；这里换成
 * 玻璃顶栏 + 玻璃卡片 + 玻璃上划表单（[CineSheetFrame]）。
 * 行为一条不少：切换当前服务器、加用户（地址锁定）、编辑、删除、以及
 * 「添加时先登录成功才落库」的那道闸门。
 *
 * ## 为什么状态徽章保留三态
 * 「当前 / 已登录但非当前 / 未登录」是这张卡唯一能一眼分清的层级信息。
 * 只改配色（`progressFill` / `surfaceContainer` / `errorContainer`）不改语义，
 * 老用户不需要重新学。
 */
@Composable
fun CineServerManageScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val servers by viewModel.servers.collectAsState()
  val currentServer by viewModel.currentServer.collectAsState()
  val failoverEnabled by viewModel.failoverEnabled.collectAsState()
  val failoverState by viewModel.failoverState.collectAsState()
  // 主 → 备映射：必须 collect（设完备用要立刻反映到卡片上）
  val failoverBackups by viewModel.failoverBackups.collectAsState()
  val scope = rememberCoroutineScope()

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var showAdd by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<EmbyServer?>(null) }
  var addUserOn by remember { mutableStateOf<EmbyServer?>(null) }
  var pendingDelete by remember { mutableStateOf<EmbyServer?>(null) }
  var backupPicker by remember { mutableStateOf<EmbyServer?>(null) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = "Emby 服务器",
      onBack = { backStack.removeLastOrNull() },
      actions = {
        CineIconButton(
          icon = Icons.Filled.Add,
          contentDescription = "添加服务器",
          tint = colors.onSurface,
          onClick = { showAdd = true },
        )
      },
    )

    Text(
      text = "共 ${servers.size} 台 · 点卡片切换当前服务器",
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(start = space.screenH, top = 12.dp, bottom = 4.dp),
    )

    if (servers.isEmpty()) {
      CineStatePanel(
        icon = Icons.Filled.Dns,
        title = "还没有添加任何 Emby 服务器",
        description = "填地址、端口和账号即可接入；登录成功后才保存配置",
        primaryLabel = "添加服务器",
        onPrimary = { showAdd = true },
        modifier = Modifier.weight(1f),
      )
    } else {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .cineHazeSource(LocalCineHaze.current),
        contentPadding = PaddingValues(
          start = space.screenH,
          end = space.screenH,
          top = 8.dp,
          bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // 主备自动接管：两台以上才有意义（单台连个备用都没得切）
        if (servers.size >= 2) {
          item(key = "failover") {
            CineFailoverCard(
              enabled = failoverEnabled,
              state = failoverState,
              onToggle = { viewModel.setFailoverEnabled(it) },
            )
          }
        }
        items(servers, key = { it.id }) { server ->
          CineServerCard(
            server = server,
            isCurrent = server.id == currentServer?.id,
            backupName = failoverBackups[server.id]
              ?.let { id -> servers.firstOrNull { it.id == id }?.name },
            onSelect = { viewModel.switchServer(server) },
            onAddUser = { addUserOn = server },
            onEdit = { editing = server },
            onDelete = { pendingDelete = server },
            onSetBackup = { backupPicker = server },
          )
        }
      }
    }
  }

  if (showAdd) {
    CineServerForm(
      title = "添加服务器",
      initial = null,
      requireLogin = true,
      onDismiss = { showAdd = false },
      onSubmit = { name, host, port, https, username, password ->
        // 登录成功即已落库，直接在表单内部关掉
        viewModel.addServer(
          EmbyServer(
            name = name,
            host = host,
            port = port,
            useHttps = https,
            username = username,
            password = password,
          ),
        )
        showAdd = false
      },
      onLogin = { name, host, port, https, username, password ->
        viewModel.loginAndSave(name, host, port, https, username, password) != null
      },
      loginError = { viewModel.error.value },
    )
  }

  addUserOn?.let { server ->
    CineServerForm(
      title = "添加用户",
      initial = null,
      sameServerAs = server,
      requireLogin = true,
      onDismiss = { addUserOn = null },
      onSubmit = { _, _, _, _, _, _ -> addUserOn = null },
      onLogin = { name, host, port, https, username, password ->
        viewModel.loginAndSave(name, host, port, https, username, password) != null
      },
      loginError = { viewModel.error.value },
    )
  }

  editing?.let { server ->
    CineServerForm(
      title = "编辑服务器",
      initial = server,
      requireLogin = false,
      onDismiss = { editing = null },
      onSubmit = { name, host, port, https, username, password ->
        viewModel.updateServer(
          server.copy(
            name = name,
            host = host,
            port = port,
            useHttps = https,
            username = username,
            password = password,
          ),
        )
        editing = null
      },
      onLogin = { _, _, _, _, _, _ -> true },
      loginError = { null },
    )
  }

  pendingDelete?.let { server ->
    CineSheetFrame(
      title = "删除服务器",
      onDismissRequest = { pendingDelete = null },
      confirmLabel = "删除",
      confirmError = true,
      onConfirm = {
        viewModel.deleteServer(server)
        pendingDelete = null
      },
    ) {
      Text(
        text = "确定删除「${server.name}」吗？",
        style = type.itemTitle,
        color = colors.onSurface,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "仅移除本机的连接配置，不会删除服务器上的媒体。",
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
      )
    }
  }

  backupPicker?.let { primary ->
    CineBackupPicker(
      primary = primary,
      candidates = servers.filter { it.id != primary.id },
      selectedId = failoverBackups[primary.id],
      onPick = { id ->
        viewModel.setFailoverBackup(primary.id, id)
        backupPicker = null
      },
      onDismiss = { backupPicker = null },
    )
  }
}

/**
 * 「自动切换备用服务器」总开关 + 当前接管状态（Cine 玻璃版）。
 *
 * 状态行是这套后台逻辑唯一的可见面：不写出来，用户根本不知道它有没有动过。
 */
@Composable
private fun CineFailoverCard(
  enabled: Boolean,
  state: EmbyFailoverController.FailoverState,
  onToggle: (Boolean) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  CineGlassPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "自动切换备用服务器",
            style = type.itemTitle,
            color = colors.onSurface,
          )
          Text(
            text = cineFailoverStatusText(enabled, state),
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
          )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = "一次真实请求失败后补一次轻量探活，两者都失败才切换；" +
          "之后每 20 秒探一次原服务器，恢复即切回。手动点选的服务器不会被改回来。",
        style = type.cardMeta,
        color = colors.onSurfaceVariant.copy(alpha = 0.78f),
      )
    }
  }
}

private fun cineFailoverStatusText(
  enabled: Boolean,
  state: EmbyFailoverController.FailoverState,
): String =
  when {
    !enabled -> "已关闭：连不上时保持当前服务器不动"
    state is EmbyFailoverController.FailoverState.Degraded ->
      "已切换到「${state.to.name}」，等待「${state.from.name}」恢复"
    state is EmbyFailoverController.FailoverState.NoBackup ->
      "「${state.from.name}」连不上，而这台没有设置备用"
    state is EmbyFailoverController.FailoverState.Failed ->
      "「${state.from.name}」与备用服务器都连不上"
    else -> "已开启：当前服务器通讯正常"
  }

/** 给某台挑一台备用（null = 不设置） */
@Composable
private fun CineBackupPicker(
  primary: EmbyServer,
  candidates: List<EmbyServer>,
  selectedId: Long?,
  onPick: (Long?) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  CineSheetFrame(
    title = "「${primary.name}」的备用服务器",
    onDismissRequest = onDismiss,
  ) {
    Text(
      text = "这台连不上时自动切到选中的那台；它恢复后自动切回来。",
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    val options: List<Pair<Long?, String>> =
      listOf<Pair<Long?, String>>(null to "不设置备用") + candidates.map { it.id to it.name }
    options.forEach { (id, label) ->
      val selected = id == selectedId
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onPick(id) }
          .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label,
          style = type.itemTitle,
          color = if (selected) colors.sectionAction else colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (selected) {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = colors.sectionAction,
          )
        }
      }
    }
  }
}

/** 单个服务器卡：状态徽章 + 名称（当前标记）+ 地址 + 登录态 + 动作行。 */
@Composable
private fun CineServerCard(
  server: EmbyServer,
  isCurrent: Boolean,
  backupName: String?,
  onSelect: () -> Unit,
  onAddUser: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onSetBackup: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme
  val shape = remember { RoundedCornerShape(50) }

  CineGlassPanel(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .clickable(onClick = onSelect)
        .padding(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
              when {
                !server.isLoggedIn -> colors.dangerContainer
                isCurrent -> colors.progressFill
                else -> colors.chipSurface
              },
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = when {
              !server.isLoggedIn -> Icons.Filled.Error
              isCurrent -> Icons.Filled.CheckCircle
              else -> Icons.Filled.Dns
            },
            contentDescription = null,
            tint = when {
              !server.isLoggedIn -> colors.onDangerContainer
              isCurrent -> scheme.onPrimary
              else -> colors.onSurfaceVariant
            },
            modifier = Modifier.size(19.dp),
          )
        }

        Column(
          modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = server.name,
              style = type.itemTitle,
              color = colors.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrent) {
              Spacer(Modifier.size(8.dp))
              Box(
                modifier = Modifier
                  .clip(shape)
                  .background(colors.progressFill)
                  .padding(horizontal = 7.dp, vertical = 1.dp),
              ) {
                Text(
                  text = "当前",
                  style = type.badge,
                  color = scheme.onPrimary,
                  maxLines = 1,
                )
              }
            }
          }
          Text(
            text = server.baseUrl,
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = when {
              !server.isLoggedIn -> "未登录 · 点卡片会重新登录"
              server.serverName.isNotBlank() -> "${server.serverName} · ${server.version}"
              else -> "已登录"
            },
            style = type.cardMeta,
            color = if (server.isLoggedIn) colors.sectionAction else scheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (server.isLoggedIn && server.username.isNotBlank()) {
            Text(
              text = "账号 ${server.username}",
              style = type.cardMeta,
              color = colors.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // 主备关系：配了就把名字写出来，不用点进对话框才知道配没配
          Text(
            text = backupName?.let { "备用：$it" } ?: "未设置备用服务器",
            style = type.cardMeta,
            color = if (backupName != null) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CineIconButton(
          icon = Icons.Filled.SwapHoriz,
          contentDescription = "设置备用服务器",
          tint = colors.onSurfaceVariant,
          onClick = onSetBackup,
          size = 38.dp,
          iconSize = 18.dp,
        )
        CineIconButton(
          icon = Icons.Filled.PersonAdd,
          contentDescription = "在这台服务器上添加另一个用户",
          tint = colors.onSurfaceVariant,
          onClick = onAddUser,
          size = 38.dp,
          iconSize = 18.dp,
        )
        CineIconButton(
          icon = Icons.Filled.Edit,
          contentDescription = "编辑",
          tint = colors.onSurfaceVariant,
          onClick = onEdit,
          size = 38.dp,
          iconSize = 18.dp,
        )
        CineIconButton(
          icon = Icons.Filled.Delete,
          contentDescription = "删除",
          tint = scheme.error,
          onClick = onDelete,
          size = 38.dp,
          iconSize = 18.dp,
        )
      }
    }
  }
}

/**
 * 服务器表单（添加 / 加用户 / 编辑共用）。
 *
 * @param sameServerAs 非空 = 在这台服务器上加用户：地址三件套预填并锁死
 * @param requireLogin true 时先登录验证，成功才落库
 * @param onLogin 返回 true 表示登录并保存成功（调用方据此决定是否关表单）
 */
@Composable
private fun CineServerForm(
  title: String,
  initial: EmbyServer?,
  requireLogin: Boolean,
  onDismiss: () -> Unit,
  onSubmit: (name: String, host: String, port: Int, https: Boolean, username: String, password: String) -> Unit,
  onLogin: suspend (name: String, host: String, port: Int, https: Boolean, username: String, password: String) -> Boolean,
  loginError: () -> String?,
  sameServerAs: EmbyServer? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scope = rememberCoroutineScope()
  val lockAddress = sameServerAs != null
  val addressSource = sameServerAs ?: initial

  var name by remember { mutableStateOf(addressSource?.name ?: "") }
  var host by remember { mutableStateOf(addressSource?.host ?: "") }
  var port by remember { mutableStateOf((addressSource?.port ?: DEFAULT_HTTP_PORT).toString()) }
  var username by remember { mutableStateOf(initial?.username ?: "") }
  var password by remember { mutableStateOf(initial?.password ?: "") }
  var useHttps by remember { mutableStateOf(addressSource?.useHttps ?: false) }
  var connecting by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  fun submit() {
    if (connecting) return
    val (rawHost, embeddedPort) = splitHostPort(host)
    if (rawHost.isBlank()) {
      errorMessage = "请填写服务器地址"
      return
    }
    val finalPort = embeddedPort ?: port.trim().toIntOrNull()
    if (finalPort == null || finalPort !in 1..65535) {
      errorMessage = "端口请填写 1~65535 之间的数字"
      return
    }
    val finalName = name.ifBlank { rawHost }
    errorMessage = null

    if (!requireLogin) {
      onSubmit(finalName, rawHost, finalPort, useHttps, username, password)
      return
    }

    connecting = true
    scope.launch {
      val ok = onLogin(finalName, rawHost, finalPort, useHttps, username, password)
      connecting = false
      if (ok) onDismiss() else errorMessage = loginError() ?: "登录失败，请检查地址与账号密码"
    }
  }

  CineSheetFrame(
    title = title,
    onDismissRequest = onDismiss,
    confirmLabel = when {
      connecting -> if (requireLogin) "连接中…" else "保存中…"
      requireLogin -> "登录并添加"
      else -> "保存"
    },
    confirmEnabled = !connecting,
    onConfirm = { submit() },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CineSheetTextField(value = name, onValueChange = { name = it }, label = "名称")
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CineSheetTextField(
          value = host,
          onValueChange = { input ->
            // 整段粘 http(s)://host:port 时自动拆开，省得手拆
            val parsed = if (input.contains("://")) parseAddress(input) else null
            if (parsed != null) {
              host = parsed.first
              port = parsed.second.toString()
              useHttps = parsed.third
            } else {
              host = input.trim()
            }
          },
          label = "服务器地址",
          modifier = Modifier.weight(1f),
        )
        if (!lockAddress) {
          Box(modifier = Modifier.weight(0.45f)) {
            CineSheetTextField(
              value = port,
              onValueChange = { input -> port = input.filter(Char::isDigit).take(5) },
              label = "端口",
            )
          }
        }
      }
      Text(
        text = if (lockAddress) {
          "地址已按所选服务器填好并锁定，只需填这个用户的账号密码；两个用户的进度、收藏互不相通"
        } else {
          "地址只填 IP 或域名；端口默认 8096，勾选 HTTPS 后默认 443"
        },
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
      )
      CineSheetTextField(value = username, onValueChange = { username = it }, label = "用户名")
      CineSheetTextField(value = password, onValueChange = { password = it }, label = "密码")
      if (!lockAddress) {
        CineSheetCheckRow(
          checked = useHttps,
          onCheckedChange = { checked ->
            useHttps = checked
            val current = port.trim()
            port = when {
              checked && (current.isEmpty() || current == DEFAULT_HTTP_PORT.toString()) ->
                HTTPS_PORT.toString()
              !checked && (current.isEmpty() || current == HTTPS_PORT.toString()) ->
                DEFAULT_HTTP_PORT.toString()
              else -> port
            }
          },
          title = "使用 HTTPS",
          subtitle = "反代域名多数走 443；Emby 自带的 8920 需要手填端口",
        )
      }
      if (connecting) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
          Spacer(Modifier.size(8.dp))
          Text("正在验证账号…", style = type.cardMeta, color = colors.onSurfaceVariant)
        }
      }
      errorMessage?.let {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.dangerContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
          Text(text = it, style = type.cardMeta, color = colors.onDangerContainer)
        }
      }
    }
  }
}

private const val DEFAULT_HTTP_PORT = 8096
private const val HTTPS_PORT = 443

/** 从地址文本里拆出主机与端口；容错 `host` / `host:8096` / `http://host:8096/emby`。 */
private fun splitHostPort(raw: String): Pair<String, Int?> {
  var value = raw.trim().substringAfter("://", raw.trim()).substringBefore('/')
  val colon = value.lastIndexOf(':')
  if (colon > 0) {
    val embedded = value.substring(colon + 1).toIntOrNull()
    if (embedded != null) return value.substring(0, colon) to embedded
  }
  return value to null
}

/** 解析 `http(s)://host:port[/path]`；纯 host 也能解析（端口用协议默认值）。 */
private fun parseAddress(raw: String): Triple<String, Int, Boolean>? {
  var input = raw.trim().trimEnd('/')
  if (input.isBlank()) return null
  val https = input.startsWith("https://", ignoreCase = true)
  input = input.removePrefix("http://").removePrefix("https://").trim()
  if (input.isBlank()) return null
  val slash = input.indexOf('/')
  if (slash >= 0) input = input.substring(0, slash)
  return if (input.contains(':')) {
    val host = input.substringBefore(':').trim()
    val port = input.substringAfter(':').trim().toIntOrNull() ?: return null
    if (host.isBlank()) null else Triple(host, port, https)
  } else {
    Triple(input, if (https) HTTPS_PORT else DEFAULT_HTTP_PORT, https)
  }
}

/** 表单里数字端口用数字键盘：`CineSheetTextField` 没有键盘参数，这里单独标注说明。 */
private val PORT_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Number)
