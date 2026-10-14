package app.marlboroadvance.mpvex.ui.browser.emby.playlist

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonList
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Emby 服务端播放列表 —— 列表页。
 *
 * 与本地播放列表（`ui/browser/playlist/PlaylistScreen`）**刻意并存**而非合并：
 * 前者存在服务器、多端共享，后者在本机、存绝对路径。理由见
 * [EmbyPlaylistViewModel] 类注释里的对照表。
 *
 * 页面形态刻意用**列表**而不是网格：Emby 播放列表没有海报图（`ImageTags` 通常为空），
 * 网格排出来会是一排空白方块，反而是「标题 + 项数 + 更多菜单」这种行式更好认。
 */
@Serializable
object EmbyPlaylistScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val vm: EmbyPlaylistViewModel = viewModel(
      factory = EmbyPlaylistViewModel.factory(application),
    )
    // 服务器沿用 EmbyViewModel 的当前选择 —— 不在这里另存一份，
    // 否则用户在服务器菜单切了服务器，本页还盯着旧服务器不放。
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by embyVm.currentServer.collectAsState()

    val playlists by vm.playlists.collectAsState()
    val loading by vm.listLoading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var createDialogShown by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EmbyItem?>(null) }

    // 首次进入（且已选中服务器）才拉数据。没有服务器时什么都不做：
    // 空列表和「还没配服务器」是两回事，不能都显示成「还没有播放列表」。
    LaunchedEffect(server?.id) {
      val current = server
      if (current != null) vm.loadPlaylists(current)
    }

    // 操作结果提示。用 collectAsState 而不是 LaunchedEffect + vm.toast：
    // 后者在 toast 值**没变**的连续两次操作里不会重新触发（StateFlow 会去重相同值）。
    LaunchedEffect(toast) {
      val message = toast ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.consumeToast()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("播放列表") },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
          actions = {
            IconButton(
              onClick = {
                val current = server
                if (current != null) vm.loadPlaylists(current, force = true)
              },
              enabled = server != null && !loading,
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
          },
        )
      },
      floatingActionButton = {
        // 没有服务器时不给建 —— 建出来的播放列表会挂在不存在的服务器上。
        if (server != null) {
          ExtendedFloatingActionButton(
            onClick = { createDialogShown = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新建") },
            modifier = Modifier.navigationBarsPadding(),
          )
        }
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          // 只吃底部内边距：顶栏自己管了，列表要能顶到栏下面滑。
          .padding(bottom = padding.calculateBottomPadding()),
      ) {
        val current = server
        when {
          current == null -> EmbyEmptyState(
            message = "还没有添加 Emby 服务器",
            buttonText = "添加服务器",
            onAction = { backStack.add(EmbyServerManageScreen) },
            modifier = Modifier.align(Alignment.Center),
          )

          loading && playlists.isEmpty() -> EmbySkeletonList(itemCount = 8)

          error != null && playlists.isEmpty() -> EmbyEmptyState(
            message = error ?: "读取失败",
            buttonText = "重试",
            onAction = {
              val s = server
              if (s != null) vm.loadPlaylists(s, force = true)
            },
            isError = true,
            modifier = Modifier.align(Alignment.Center),
          )

          playlists.isEmpty() -> EmbyEmptyState(
            message = "还没有播放列表。可以在 Emby 网页端或 App 里建一个，或点右下角「新建」。",
            buttonText = "新建",
            onAction = { createDialogShown = true },
            modifier = Modifier.align(Alignment.Center),
          )

          else -> LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(playlists, key = { it.Id ?: it.hashCode().toString() }) { playlist ->
              EmbyPlaylistRow(
                playlist = playlist,
                onClick = {
                  val id = playlist.Id
                  if (id != null) {
                    backStack.add(
                      EmbyPlaylistDetailScreen(
                        playlistId = id,
                        playlistName = playlist.Name ?: "播放列表",
                      ),
                    )
                  }
                },
                onDelete = { deleteTarget = playlist },
              )
            }
          }
        }
      }
    }

    // ── 新建对话框 ──
    if (createDialogShown) {
      var nameInput by remember { mutableStateOf("") }
      AlertDialog(
        onDismissRequest = { createDialogShown = false },
        title = { Text("新建播放列表") },
        text = {
          Column {
            OutlinedTextField(
              value = nameInput,
              onValueChange = { nameInput = it },
              label = { Text("名称") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Text(
              text = "先建空的，之后在详情页往里加片子。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        confirmButton = {
          TextButton(
            enabled = nameInput.isNotBlank() && !loading,
            onClick = {
              val current = server ?: return@TextButton
              val typed = nameInput
              createDialogShown = false
              scope.launch {
                vm.createPlaylist(current, typed) { }
              }
            },
          ) { Text("创建") }
        },
        dismissButton = {
          TextButton(onClick = { createDialogShown = false }) { Text("取消") }
        },
      )
    }

    // ── 删除确认（不可恢复，必须二次确认）──
    val removing = deleteTarget
    if (removing != null) {
      AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("删除播放列表？") },
        text = {
          Text(
            "「${removing.Name ?: "未命名"}」会连同里面的条目一起从**服务器**上删除，" +
              "其他设备（网页端、电视端）也会一起没了。这个操作无法撤销。",
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              val current = server
              val id = removing.Id
              deleteTarget = null
              if (current == null || id == null) return@TextButton
              scope.launch { vm.deletePlaylist(current, id) }
            },
          ) {
            Text("删除", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { deleteTarget = null }) { Text("取消") }
        },
      )
    }
  }
}

/**
 * 播放列表行：图标 + 名称 + 项数 + 更多菜单。
 *
 * 项数取 `ChildCount`。Emby 的 Playlist 类型会带上这个字段；拿不到就不显示数字，
 * 而不是显示「0 项」骗人。
 */
@Composable
private fun EmbyPlaylistRow(
  playlist: EmbyItem,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(32.dp),
    )
    Spacer(Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = playlist.Name ?: "未命名播放列表",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      playlist.ChildCount?.let { count ->
        Text(
          text = "$count 项",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Box {
      IconButton(onClick = { menuExpanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "更多")
      }
      DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false },
      ) {
        DropdownMenuItem(
          text = { Text("删除") },
          onClick = {
            menuExpanded = false
            onDelete()
          },
        )
      }
    }
  }
}