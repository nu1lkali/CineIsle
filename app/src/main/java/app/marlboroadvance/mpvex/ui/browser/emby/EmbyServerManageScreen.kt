package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyEndpoint
import app.marlboroadvance.mpvex.domain.emby.EmbyFailoverController
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.primaryEndpoint
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyBrandGreen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyLogo
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 列表底部留白：必须避开右下角的 FAB。
 *
 * Scaffold 的 FAB 是**浮在内容之上**的（不占 `innerPadding`），所以给它 16dp 的
 * contentPadding 时，滚到底的那一张卡片右侧的「备用 / 更多」按钮正好躺在 FAB
 * 底下 —— 而且列表已经到底，滚不开，那个按钮就成了死区。
 *
 * 遮挡带 = FAB 本体 56dp + Scaffold 默认底边距 16dp = 72dp；再留一档卡片间距
 * （12dp）和一点余量 ⇒ 96dp，滚到底时最后一张卡完整浮在 FAB 之上。
 */
private val LIST_BOTTOM_CLEARANCE = 96.dp

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
    val failoverEnabled by viewModel.failoverEnabled.collectAsState()
    val failoverState by viewModel.failoverState.collectAsState()
    // 主 → 备映射：必须 collect（设完备用要立刻反映到卡片上）
    val failoverBackups by viewModel.failoverBackups.collectAsState()
    // 双入口：配置了什么、此刻实际在用哪个，都必须 collect
    val endpointAlts by viewModel.endpointAlts.collectAsState()
    val activeEndpoints by viewModel.activeEndpoints.collectAsState()

    var editing by remember { mutableStateOf<EmbyServer?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    // a7：在这台服务器上再登一个用户（地址预填锁定，只换账号密码）
    var addUserOn by remember { mutableStateOf<EmbyServer?>(null) }
    var pendingDelete by remember { mutableStateOf<EmbyServer?>(null) }
    // 「给这台设一台备用」的选择器
    var backupPicker by remember { mutableStateOf<EmbyServer?>(null) }
    // 「给这台配第二地址（内网 / 公网）」的编辑器
    var altPicker by remember { mutableStateOf<EmbyServer?>(null) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            // 标题下挂一行小字计数：这是台数管理页，第一眼就该知道「一共有几台」，
            // 不用往下滑数卡片。空态时也会显示「共 0 台」，和空态文案互相印证。
            Column {
              Text(
                text = "Emby 服务器",
                style = MaterialTheme.typography.titleLarge,
              )
              Text(
                text = "共 ${servers.size} 台",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
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
          contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = LIST_BOTTOM_CLEARANCE,
          ),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // 主备自动接管：只有两台以上才有意义（一台连备用都没得切），
          // 所以单台时整块不出现，免得用户以为「开了也没用」。
          if (servers.size >= 2) {
            item(key = "failover") {
              FailoverCard(
                enabled = failoverEnabled,
                state = failoverState,
                onToggle = { viewModel.setFailoverEnabled(it) },
              )
            }
          }
          items(servers, key = { it.id }) { server ->
            ServerCard(
              server = server,
              isCurrent = server.id == currentServer?.id,
              // 这台配的备用（null = 没配）：直接显示名字，不用点进去才知道配没配
              backupName = failoverBackups[server.id]
                ?.let { id -> servers.firstOrNull { it.id == id }?.name },
              canSetBackup = servers.size >= 2,
              altEndpoint = endpointAlts[server.id],
              activeEndpoint = activeEndpoints[server.id] ?: server.primaryEndpoint(),
              onSelect = { viewModel.switchServer(server) },
              onEdit = { editing = server },
              onDelete = { pendingDelete = server },
              onAddUser = { addUserOn = server },
              onSetBackup = { backupPicker = server },
              onSetAlt = { altPicker = server },
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

    // a7：同一服务器再登一个用户。地址三件套预填并锁死，只填新账号密码。
    addUserOn?.let { server ->
      ServerEditDialog(
        title = "添加用户",
        initial = null,
        sameServerAs = server,
        onDismiss = { addUserOn = null },
        onConfirm = { _, _, _, _, _, _ -> },
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

    backupPicker?.let { primary ->
      BackupPickerDialog(
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

    altPicker?.let { target ->
      AltEndpointDialog(
        server = target,
        initial = endpointAlts[target.id],
        onDismiss = { altPicker = null },
        onSave = { endpoint ->
          viewModel.setAltEndpoint(target.id, endpoint)
          altPicker = null
        },
      )
    }
  }
}

// 第二地址对话框的实现见 AltEndpointDialog.kt（Classic 与 Cine 两套管理页共用）


/**
 * 「自动切换备用服务器」总开关 + 当前接管状态。
 *
 * 为什么不只放一个 Switch：这套逻辑是**后台自己动的**，用户唯一能看见「它动没动」
 * 的地方就是这一行状态；只留开关，主备切换就是个黑盒。
 *
 * 状态用 errorContainer 打底只在「确实出事了」（已降级 / 没备可用 / 都连不上）时出现，
 * 正常情况下保持和普通卡片一样的中性色，免得明明没事也一片红。
 */
@Composable
private fun FailoverCard(
  enabled: Boolean,
  state: EmbyFailoverController.FailoverState,
  onToggle: (Boolean) -> Unit,
) {
  val troubled =
    state is EmbyFailoverController.FailoverState.Degraded ||
      state is EmbyFailoverController.FailoverState.NoBackup ||
      state is EmbyFailoverController.FailoverState.Failed
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (troubled) {
        MaterialTheme.colorScheme.errorContainer
      } else {
        MaterialTheme.colorScheme.surface
      },
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (troubled) {
        MaterialTheme.colorScheme.error
      } else {
        MaterialTheme.colorScheme.outlineVariant
      },
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("自动切换备用服务器", style = MaterialTheme.typography.titleMedium)
          Text(
            text = failoverStatusText(enabled, state),
            style = MaterialTheme.typography.bodySmall,
            color = if (troubled) {
              MaterialTheme.colorScheme.onErrorContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
      }
      Text(
        text = "判定方式：一次真实请求失败后补一次轻量探活，两者都失败才切换；" +
          "之后每 20 秒探一次原服务器，恢复即自动切回。" +
          "手动点选的服务器不会被后台改回来。",
        style = MaterialTheme.typography.labelSmall,
        color = if (troubled) {
          MaterialTheme.colorScheme.onErrorContainer
        } else {
          MaterialTheme.colorScheme.outline
        },
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

/** 状态 → 一句话（列表里只看这一行就知道现在是谁在服务） */
private fun failoverStatusText(
  enabled: Boolean,
  state: EmbyFailoverController.FailoverState,
): String =
  when {
    !enabled -> "已关闭：连不上时保持当前服务器不动"
    state is EmbyFailoverController.FailoverState.Degraded ->
      "已切换到「${state.to.name}」，正在等待「${state.from.name}」恢复"
    state is EmbyFailoverController.FailoverState.NoBackup ->
      "「${state.from.name}」连不上，而这台还没有设置备用服务器"
    state is EmbyFailoverController.FailoverState.Failed ->
      "「${state.from.name}」与备用服务器都连不上"
    else -> "已开启：当前服务器通讯正常"
  }

/**
 * 给某台服务器挑一台备用。
 *
 * 只列「别的服务器」；选项里始终保留「不设置备用」，否则配过一次就没有回头路。
 */
@Composable
private fun BackupPickerDialog(
  primary: EmbyServer,
  candidates: List<EmbyServer>,
  selectedId: Long?,
  onPick: (Long?) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("「${primary.name}」的备用服务器") },
    text = {
      Column {
        Text(
          text = "这台连不上时自动切到选中的那台；它恢复后自动切回来。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // null = 不设置备用
        val options: List<Pair<Long?, String>> =
          listOf<Pair<Long?, String>>(null to "不设置备用") +
            candidates.map { it.id to it.name }
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
              style = MaterialTheme.typography.bodyLarge,
              color = if (selected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
              modifier = Modifier.weight(1f),
            )
            if (selected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("关闭") }
    },
  )
}

/**
 * 服务器卡片：点卡片 = 切换到这台服务器；右下角三个图标分别管
 * 「同机加用户 / 编辑 / 删除」。
 *
 * 版式要点（第三版，前两版都被嫌「挤」「分不清哪台是当前的」）：
 * - **状态徽章**：左侧 40dp 圆形 tonal 底 + 状态图标（未登录 = errorContainer、
 *   当前 = primary、其它在线 = surfaceVariant），三态不看文字也能分辨；
 * - **「当前」小胶囊**：当前服务器的名字旁钉一枚 primary 底小标签 ——
 *   单靠 secondaryContainer 底色，夜间模式 / 色弱用户分不清「当前」和「普通」；
 * - **动作按钮独立成行**（右下角一排）：前两版把三个 IconButton 和信息挤在同一行，
 *   名字列被 3×48dp 的按钮区压得只剩一百多 dp，长服务器名必被截断；
 *   拆开后信息列拿满宽，动作行右对齐，两类内容互不挤压；
 * - **普通卡 outlineVariant 细边 / 当前卡 primary 实边**：卡片底色接近页面背景，
 *   没有边就看不出「这是一张卡」；当前卡用 primary 边把层级再拔高一格。
 */
@Composable
private fun ServerCard(
  server: EmbyServer,
  isCurrent: Boolean,
  backupName: String?,
  /** 不足两台服务器时，没有「另一台」可选 —— 备用相关的入口与提示整块不出现 */
  canSetBackup: Boolean,
  /** 这台配的第二地址（null = 只有一个入口） */
  altEndpoint: EmbyEndpoint?,
  /** 这台**此刻**实际在用的入口（可能已经自动切到第二地址） */
  activeEndpoint: EmbyEndpoint,
  onSelect: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onAddUser: () -> Unit,
  onSetBackup: () -> Unit,
  onSetAlt: () -> Unit,
) {
  Card(
    onClick = onSelect,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surface
      },
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (isCurrent) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outlineVariant
      },
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        // 状态徽章：圆形 tonal 底 + 状态图标（40dp：20dp 图标 + 上下 10dp 内边距）
        Surface(
          shape = CircleShape,
          color = when {
            !server.isLoggedIn -> MaterialTheme.colorScheme.errorContainer
            isCurrent -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
          },
        ) {
          Icon(
            imageVector = when {
              !server.isLoggedIn -> Icons.Default.Error
              isCurrent -> Icons.Default.CheckCircle
              else -> Icons.Default.Dns
            },
            contentDescription = null,
            tint = when {
              !server.isLoggedIn -> MaterialTheme.colorScheme.onErrorContainer
              isCurrent -> MaterialTheme.colorScheme.onPrimary
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
              .padding(10.dp)
              .size(20.dp),
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
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              // fill = false：名字短时小胶囊紧跟在名字后面，而不是被顶到行尾
              modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrent) {
              Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(start = 8.dp),
              ) {
                Text(
                  text = "当前",
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
              }
            }
          }
          Text(
            text = server.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          // a7：账号名单独一行 —— 同一台服务器下多个用户各占一张卡，
          // 没有这一行时两张卡只差一个 Id，用户根本分不清该点哪张。
          if (server.isLoggedIn && server.username.isNotBlank()) {
            Text(
              text = "账号 ${server.username}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // 主备关系：配了就把名字写出来（不然只能点进对话框才知道配没配）。
          // 只有一台服务器时整行不出现 —— 「未设置备用服务器」对单机用户是无意义噪音。
          if (canSetBackup) {
            Text(
              text = backupName?.let { "备用：$it" } ?: "未设置备用服务器",
              style = MaterialTheme.typography.bodySmall,
              color = if (backupName != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
              } else {
                MaterialTheme.colorScheme.outline
              },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // 双入口：只在配了第二地址时出现（没配的服务器不该多一行噪音）。
          // 显示的是**此刻实际在用**的地址 —— 用户在外面按一下就能确认「现在走的是公网」。
          if (altEndpoint != null) {
            val usingAlt = activeEndpoint.baseUrl != server.primaryEndpoint().baseUrl
            Text(
              text = "当前入口：${activeEndpoint.host}:${activeEndpoint.port}" +
                if (usingAlt) "（第二地址）" else "（主地址）",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      // 动作行：右下角一排图标按钮。独立成行后信息列拿满宽，
      // 三个按钮也不再和服务器名字抢横向空间
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 这台连不上时换到另一台服务器（见 EmbyFailoverController）。
        // 只有一台时没有目标可选，按钮不出现，免得点开一个空面板。
        if (canSetBackup) {
          IconButton(onClick = onSetBackup) {
            Icon(Icons.Default.SwapHoriz, contentDescription = "换到另一台服务器")
          }
        }
        // 同一台的内网 / 公网双入口（见 EmbyEndpointResolver）
        IconButton(onClick = onSetAlt) {
          Icon(Icons.Default.Language, contentDescription = "同一台的另一条线路")
        }
        // a7：在这台服务器上再登一个用户（地址已填过，不用重打）
        IconButton(onClick = onAddUser) {
          Icon(Icons.Default.PersonAdd, contentDescription = "在这台服务器上添加另一个用户")
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
}

/**
 * 服务器编辑对话框。
 *
 * @param requireLogin true 时会先用账号密码登录验证，成功后才保存（添加场景）
 * @param sameServerAs 非空 = 「在这台服务器上添加另一个用户」（a7）：
 *   地址三件套按它预填并锁死，只留账号密码给用户填。
 */
@Composable
private fun ServerEditDialog(
  title: String,
  initial: EmbyServer?,
  requireLogin: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (name: String, host: String, port: Int, useHttps: Boolean, username: String, password: String) -> Unit,
  sameServerAs: EmbyServer? = null,
) {
  val context = LocalContext.current
  val viewModel: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(context.applicationContext as Application),
  )
  val scope = rememberCoroutineScope()

  // 添加用户模式：地址取自 sameServerAs，名称沿用它的名字，账号密码留空
  val addressSource = sameServerAs ?: initial
  val lockAddress = sameServerAs != null
  var name by remember { mutableStateOf(addressSource?.name ?: "") }
  // 地址与端口拆成两个输入框：地址只填主机（IP / 域名），端口单独填
  var host by remember { mutableStateOf(addressSource?.host ?: "") }
  var port by remember { mutableStateOf((addressSource?.port ?: DEFAULT_HTTP_PORT).toString()) }
  var username by remember { mutableStateOf(initial?.username ?: "") }
  var password by remember { mutableStateOf(initial?.password ?: "") }
  var useHttps by remember { mutableStateOf(addressSource?.useHttps ?: false) }
  var isConnecting by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      // 标题旁带 Emby 标志：这个弹窗是「连一个 Emby 服务」的入口，
      // 光看文字分不清是加 Emby 还是加别的源。
      // ⚠️ 图标给固定 size + 不参与文字基线对齐，否则中文标题会被顶歪。
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // 用 components/EmbyLogo.kt 里的官方矢量（Simple Icons 路径）+ 品牌绿，
        // 与首页 Emby 维护入口同一个 EmbyLogo，两处视觉必须一致。
        Icon(
          imageVector = EmbyLogo,
          contentDescription = null,
          tint = EmbyBrandGreen,
          modifier = Modifier.size(20.dp),
        )
        Text(title)
      }
    },
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
            enabled = !lockAddress,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = port,
            onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
            label = { Text("端口") },
            placeholder = { Text((if (useHttps) HTTPS_PORT else DEFAULT_HTTP_PORT).toString()) },
            singleLine = true,
            enabled = !lockAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.5f),
          )
        }
        Text(
          text = if (lockAddress) {
            "地址已按所选服务器填好并锁定，只需填写这个用户的账号密码；" +
              "两个用户的播放进度、收藏、标记互不相通"
          } else {
            "地址只填 IP 或域名；端口默认 8096，勾选 HTTPS 后默认 443"
          },
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
            enabled = !lockAddress,
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
        // 错误信息独立成块（满宽 errorContainer）：和网络连接卡片同一套做法。
        // 散在输入框末尾的一行红字既容易被忽略，地址/端口类的长错误也读不完。
        errorMessage?.let {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = it,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
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
