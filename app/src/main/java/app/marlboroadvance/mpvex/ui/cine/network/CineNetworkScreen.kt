package app.marlboroadvance.mpvex.ui.cine.network

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SignalWifiConnectedNoInternet4
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFavorite
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.ui.browser.cards.protocolAccent
import app.marlboroadvance.mpvex.ui.browser.cards.protocolBadge
import app.marlboroadvance.mpvex.ui.browser.dialogs.AddConnectionSheet
import app.marlboroadvance.mpvex.ui.browser.dialogs.EditConnectionSheet
import app.marlboroadvance.mpvex.ui.browser.emby.components.VideoFeedIcon
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkBrowserScreen
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkFeedLauncher
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingViewModel
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetFrame
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetTextField
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassPanel
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineSearchField
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 版「网络」tab。
 *
 * ## 三段式，与 Classic 的信息结构一致
 * 直链 → 收藏的目录 → 已保存的连接。三段都是**内存里就有的数据**，
 * 所以搜索是纯字符串过滤，不打扰服务端。
 *
 * ## 换的是皮，不是协议栈
 * 连接 / 断开 / 置顶 / 编辑 / 删除全部走 Classic 的 [NetworkStreamingViewModel]；
 * 一层层浏览目录仍是 [NetworkBrowserScreen]（协议客户端那套东西一行没动，
 * 它有自己的面包屑与多选，属于另一个页面）。
 *
 * ## 为什么要保留「添加连接」的 M3 表单
 * 四种协议（SMB / FTP / WebDAV / AList）的字段与默认值差异很大，Classic 的
 * [AddConnectionSheet] 已经把这些坑踩平了（端口默认值、匿名开关、路径规范化）。
 * 为了避免「Cine 版表单少一个字段就存不上」这类问题，表单继续复用，只换外层的页面壳。
 */
@Composable
fun CineNetworkScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val viewModel: NetworkStreamingViewModel = viewModel(
    factory = NetworkStreamingViewModel.factory(context.applicationContext as Application),
  )
  val repository = koinInject<app.marlboroadvance.mpvex.repository.NetworkRepository>()
  val browserPreferences = koinInject<app.marlboroadvance.mpvex.preferences.BrowserPreferences>()
  val connections by viewModel.connections.collectAsState()
  val statuses by viewModel.connectionStatuses.collectAsState()
  val favorites by repository.getAllFavorites().collectAsState(initial = emptyList())

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var searchOpen by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var showAdd by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<NetworkConnection?>(null) }
  var pendingDelete by remember { mutableStateOf<NetworkConnection?>(null) }

  val keyword = query.trim()
  val visible = remember(connections, keyword) {
    if (keyword.isEmpty()) {
      connections
    } else {
      connections.filter {
        it.name.contains(keyword, ignoreCase = true) ||
          it.host.contains(keyword, ignoreCase = true) ||
          it.protocol.displayName.contains(keyword, ignoreCase = true) ||
          "${it.host}:${it.port}".contains(keyword, ignoreCase = true) ||
          it.username.contains(keyword, ignoreCase = true)
      }
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = "网络",
      actions = {
        CineIconButton(
          icon = Icons.Outlined.Search,
          contentDescription = "搜索连接",
          tint = if (searchOpen) colors.sectionAction else colors.onSurfaceVariant,
          onClick = {
            searchOpen = !searchOpen
            if (!searchOpen) query = ""
          },
        )
        CineIconButton(
          icon = Icons.Filled.Add,
          contentDescription = "添加连接",
          tint = colors.onSurface,
          onClick = { showAdd = true },
        )
        CineIconButton(
          icon = Icons.Filled.Settings,
          contentDescription = "设置",
          tint = colors.onSurfaceVariant,
          onClick = { backStack.add(PreferencesScreen) },
        )
      },
    )

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .cineHazeSource(LocalCineHaze.current),
      contentPadding = PaddingValues(
        start = space.screenH,
        end = space.screenH,
        top = 12.dp,
        bottom = space.contentBottomInset,
      ),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (searchOpen) {
        item(key = "search") {
          CineSearchField(
            query = query,
            onQueryChange = { query = it },
            onClose = {
              searchOpen = false
              query = ""
            },
            placeholder = "搜索名称 / 地址 / 协议",
          )
        }
      }

      // ── 直链 ──
      if (keyword.isEmpty()) {
        item(key = "stream-link") {
          CineStreamLinkPanel(
            onPlay = { url -> MediaUtils.playFile(url, context, "network_stream") },
          )
        }
      }

      // ── 收藏的目录 ──
      if (keyword.isEmpty() && favorites.isNotEmpty()) {
        item(key = "favorite-header") {
          Text(
            text = "收藏的目录",
            style = type.sectionTitle.copy(fontSize = 16.sp),
            color = colors.sectionAction,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
          )
        }
        item(key = "favorite-row") {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(favorites, key = { it.id }) { favorite ->
              val conn = connections.firstOrNull { it.id == favorite.connectionId }
              CineFavoriteDirCard(
                favorite = favorite,
                connection = conn,
                onOpen = {
                  if (conn == null) {
                    Toast.makeText(
                      context,
                      "「${favorite.name}」的连接已删除，请移除该收藏",
                      Toast.LENGTH_SHORT,
                    ).show()
                  } else {
                    backStack.add(
                      NetworkBrowserScreen(
                        connectionId = favorite.connectionId,
                        connectionName = conn.name,
                        currentPath = favorite.path,
                      ),
                    )
                  }
                },
                onRemove = { scope.launch { repository.deleteFavorite(favorite.id) } },
              )
            }
          }
        }
      }

      // ── 已保存的连接 ──
      item(key = "connection-header") {
        Text(
          text = if (keyword.isEmpty()) "网络位置" else "搜索结果",
          style = type.sectionTitle.copy(fontSize = 16.sp),
          color = colors.sectionAction,
          modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
      }

      if (visible.isEmpty()) {
        item(key = "empty") {
          CineStatePanel(
            icon = Icons.Outlined.SignalWifiConnectedNoInternet4,
            title = if (keyword.isEmpty()) "还没有网络连接" else "没有匹配的连接",
            description = if (keyword.isEmpty()) {
              "支持 SMB / FTP / WebDAV / AList；添加后可在这里直接浏览并起播"
            } else {
              "可以搜名称、地址或协议（SMB / FTP / WebDAV / AList）"
            },
            primaryLabel = if (keyword.isEmpty()) "添加连接" else null,
            onPrimary = if (keyword.isEmpty()) ({ showAdd = true }) else null,
          )
        }
      } else {
        items(visible, key = { it.id }) { connection ->
          val status = statuses[connection.id]
          CineConnectionCard(
            connection = connection,
            connected = status?.isConnected == true,
            connecting = status?.isConnecting == true,
            error = status?.error,
            onConnect = { viewModel.connect(connection) },
            onDisconnect = { viewModel.disconnect(connection) },
            onBrowse = {
              backStack.add(
                NetworkBrowserScreen(
                  connectionId = connection.id,
                  connectionName = connection.name,
                  currentPath = "/",
                ),
              )
            },
            onTogglePin = { viewModel.setPinned(connection.id, !connection.pinned) },
            onEdit = { editing = connection },
            onDelete = { pendingDelete = connection },
            onFeedPlay = {
              Toast.makeText(context, "正在抽取视频…", Toast.LENGTH_SHORT).show()
              scope.launch {
                val limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500)
                val videos = NetworkFeedLauncher.collectVideos(
                  repository = repository,
                  connection = connection,
                  startPath = "/",
                  want = limit,
                  drillDown = true,
                )
                val started = NetworkFeedLauncher.launch(
                  context = context,
                  application = context.applicationContext as Application,
                  repository = repository,
                  connection = connection,
                  videos = videos,
                  want = limit,
                )
                if (!started) {
                  Toast.makeText(
                    context,
                    "「${connection.name}」里没有找到视频",
                    Toast.LENGTH_SHORT,
                  ).show()
                }
              }
            },
          )
        }
      }
    }
  }

  AddConnectionSheet(
    isOpen = showAdd,
    onDismiss = { showAdd = false },
    onSave = { connection ->
      viewModel.addConnection(connection)
      showAdd = false
    },
  )

  editing?.let { connection ->
    EditConnectionSheet(
      connection = connection,
      isOpen = true,
      onDismiss = { editing = null },
      onSave = { updated ->
        viewModel.updateConnection(updated)
        editing = null
      },
    )
  }

  pendingDelete?.let { connection ->
    CineSheetFrame(
      title = "删除连接",
      onDismissRequest = { pendingDelete = null },
      confirmLabel = "删除",
      confirmError = true,
      onConfirm = {
        viewModel.deleteConnection(connection)
        pendingDelete = null
      },
    ) {
      Text(
        text = "确定删除「${connection.name}」吗？",
        style = type.itemTitle,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "只移除本机保存的地址与账号，不会动服务器上的文件。",
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
      )
    }
  }
}

/** 直链段：一个输入框 + 粘贴 / 播放两颗药丸。 */
@Composable
private fun CineStreamLinkPanel(onPlay: (String) -> Unit) {
  val context = LocalContext.current
  val colors = CineTheme.colors
  val type = CineTheme.type
  var url by rememberSaveable { mutableStateOf("") }

  CineGlassPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.Link,
          contentDescription = null,
          tint = colors.sectionAction,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "直链播放", style = type.itemTitle, color = colors.onSurface)
      }
      Spacer(Modifier.height(10.dp))
      CineSheetTextField(
        value = url,
        onValueChange = { url = it },
        label = "视频地址",
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CineNetPill(
          label = "粘贴",
          icon = Icons.Filled.ContentPaste,
          onClick = {
            val manager =
              context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = manager?.primaryClip
              ?.takeIf { it.itemCount > 0 }
              ?.getItemAt(0)
              ?.text
              ?.toString()
            if (!text.isNullOrBlank()) url = text else {
              Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
            }
          },
        )
        CineNetPill(
          label = "播放",
          icon = Icons.Filled.PlayArrow,
          filled = true,
          enabled = url.isNotBlank(),
          onClick = {
            if (url.isNotBlank()) {
              onPlay(url)
              url = ""
            }
          },
        )
      }
    }
  }
}

/** 收藏的目录：横排小卡，右上角一颗淡 ✕ 用于移除。 */
@Composable
private fun CineFavoriteDirCard(
  favorite: NetworkFavorite,
  connection: NetworkConnection?,
  onOpen: () -> Unit,
  onRemove: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val alive = connection != null
  val accent = connection?.protocol?.let { protocolAccent(it) } ?: colors.onSurfaceVariant

  CineGlassPanel(
    modifier = Modifier
      .width(158.dp)
      .height(96.dp),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .clickable(onClick = onOpen)
          .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = if (alive) 0.16f else 0.08f)),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = connection?.protocol?.let { protocolBadge(it) } ?: "?",
            style = type.badge,
            color = if (alive) accent else colors.onSurfaceVariant,
          )
        }
        Text(
          text = favorite.name,
          style = type.cardMeta.copy(fontSize = 13.sp),
          color = if (alive) colors.onSurface else colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = connection?.name ?: "连接已删除",
          style = type.cardMeta,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(2.dp)
          .size(30.dp)
          .clip(CircleShape)
          .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "移除收藏",
          tint = colors.onSurfaceVariant,
          modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}

/** 连接卡：协议徽标 + 名称/地址 + 状态 + 动作。 */
@Composable
private fun CineConnectionCard(
  connection: NetworkConnection,
  connected: Boolean,
  connecting: Boolean,
  error: String?,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onBrowse: () -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  /** 视界流播放：从这台存储的根目录递归抽一批视频进竖屏连播 */
  onFeedPlay: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme
  val accent = protocolAccent(connection.protocol)

  CineGlassPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = protocolBadge(connection.protocol),
            style = type.badge,
            color = accent,
          )
        }
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = connection.name,
              style = type.itemTitle,
              color = colors.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (connection.pinned) {
              Spacer(Modifier.width(6.dp))
              Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "已置顶",
                tint = colors.sectionAction,
                modifier = Modifier.size(13.dp),
              )
            }
          }
          Text(
            text = "${connection.host}:${connection.port}",
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = when {
              connecting -> "连接中…"
              connected -> "已连接"
              error != null -> "连接失败：$error"
              else -> "未连接 · 点「连接」后即可浏览"
            },
            style = type.cardMeta,
            color = when {
              error != null -> scheme.error
              connected -> colors.sectionAction
              else -> colors.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (connected) {
          CineNetPill(label = "浏览", filled = true, onClick = onBrowse)
          CineNetPill(label = "断开", onClick = onDisconnect)
        } else {
          CineNetPill(
            label = if (connecting) "连接中…" else "连接",
            filled = true,
            enabled = !connecting,
            onClick = onConnect,
          )
        }
        Spacer(Modifier.weight(1f))
        // 视界流：与「浏览」同级的一次性动作，但语义是「播放」而非「导航」，
        // 所以只给图标、放在置顶左边 —— 与经典版把它收在 ⋮ 里的层级一致。
        CineIconButton(
          icon = VideoFeedIcon,
          contentDescription = "视界流播放",
          tint = colors.onSurfaceVariant,
          onClick = onFeedPlay,
          size = 34.dp,
          iconSize = 18.dp,
        )
        CineIconButton(
          icon = Icons.Filled.PushPin,
          contentDescription = if (connection.pinned) "取消置顶" else "置顶",
          tint = if (connection.pinned) colors.sectionAction else colors.onSurfaceVariant,
          onClick = onTogglePin,
          size = 34.dp,
          iconSize = 16.dp,
        )
        CineIconButton(
          icon = Icons.Filled.Edit,
          contentDescription = "编辑",
          tint = colors.onSurfaceVariant,
          onClick = onEdit,
          size = 34.dp,
          iconSize = 16.dp,
        )
        CineIconButton(
          icon = Icons.Filled.Delete,
          contentDescription = "删除",
          tint = scheme.error,
          onClick = onDelete,
          size = 34.dp,
          iconSize = 16.dp,
        )
      }
    }
  }
}

/** 药丸按钮：主操作用实心，其余用 Chip 底。 */
@Composable
private fun CineNetPill(
  label: String,
  onClick: () -> Unit,
  filled: Boolean = false,
  enabled: Boolean = true,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme
  val shape = remember { RoundedCornerShape(50) }
  val background = when {
    !enabled -> colors.chipSurface.copy(alpha = 0.5f)
    filled -> colors.progressFill
    else -> colors.chipSurface
  }
  val contentColor = when {
    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
    filled -> scheme.onPrimary
    else -> colors.onSurface
  }

  Row(
    modifier = Modifier
      .height(34.dp)
      .clip(shape)
      .background(background)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(15.dp),
      )
      Spacer(Modifier.width(5.dp))
    }
    Text(
      text = label,
      style = type.cardMeta.copy(fontSize = 12.5.sp),
      color = contentColor,
      maxLines = 1,
    )
  }
}
