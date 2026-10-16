package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiConnectedNoInternet4
import androidx.compose.material.icons.rounded.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFavorite
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkConnectionCard
import app.marlboroadvance.mpvex.ui.browser.cards.protocolAccent
import app.marlboroadvance.mpvex.ui.browser.cards.protocolBadge
import app.marlboroadvance.mpvex.ui.browser.dialogs.AddConnectionSheet
import app.marlboroadvance.mpvex.ui.browser.dialogs.EditConnectionSheet
import app.marlboroadvance.mpvex.ui.browser.states.EmptyState
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.marlboroadvance.mpvex.preferences.FolderViewMode

@Serializable
object NetworkStreamingScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel: NetworkStreamingViewModel =
      viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as android.app.Application))

    val connections by viewModel.connections.collectAsState()
    val connectionStatuses by viewModel.connectionStatuses.collectAsState()
    val browserPreferences = koinInject<app.marlboroadvance.mpvex.preferences.BrowserPreferences>()
    // 收藏的目录（快捷入口）：直接 collect 仓库的 Flow，
    // 浏览页加/减收藏后主界面立刻同步（Room Flow 驱动）
    val repository = koinInject<app.marlboroadvance.mpvex.repository.NetworkRepository>()
    val favorites by repository.getAllFavorites()
      .collectAsState(initial = emptyList())
    var showAddSheet by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<NetworkConnection?>(null) }
    val navigationBarHeight = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current

    // LazyList state for scroll tracking
    val listState = LazyListState()

    // Track scroll direction to show/hide FAB
    var previousFirstVisibleItemIndex by remember { mutableIntStateOf(0) }
    var previousFirstVisibleItemScrollOffset by remember { mutableIntStateOf(0) }
    
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    
    val isFabVisible by remember {
      derivedStateOf {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset

        // Show FAB when at the top
        if (currentIndex == 0 && currentOffset == 0) {
          true
        } else {
          // Show when scrolling up, hide when scrolling down
          val isScrollingUp = currentIndex < previousFirstVisibleItemIndex ||
            (currentIndex == previousFirstVisibleItemIndex && currentOffset < previousFirstVisibleItemScrollOffset)

          previousFirstVisibleItemIndex = currentIndex
          previousFirstVisibleItemScrollOffset = currentOffset

          isScrollingUp
        }
      }
    }

    Scaffold(
        topBar = {
          BrowserTopBar(
            title = "网络",
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = 0,
            onBackClick = null, // No back button for network screen (root tab)
            onCancelSelection = { },
          onSortClick = null,
          // Search functionality disabled for production
          onSearchClick = null,
          onSettingsClick = {
            backstack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen)
          },
          onDeleteClick = null,
          onRenameClick = null,
          isSingleSelection = false,
          onInfoClick = null,
          onShareClick = null,
          onPlayClick = null,
          onSelectAll = null,
          onInvertSelection = null,
          onDeselectAll = null,
        )
      },
      floatingActionButton = {
        val navigationBarHeight = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current
        if (isFabVisible) {
          ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("添加连接") },
            modifier = Modifier.padding(bottom = navigationBarHeight)
          )
        }
      },
    ) { padding ->
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentPadding = PaddingValues(
          start = 16.dp, 
          end = 16.dp, 
          top = 16.dp, 
          bottom = navigationBarHeight
        ),
      ) {
          // Section 1: Stream Link
          item {
            StreamLinkSection(
              onPlayLink = { url ->
                MediaUtils.playFile(url, context, "network_stream")
              },
            )
          }

          // Section 1.5: 收藏的目录（快捷入口）
          // 空则整段隐藏 —— 没有收藏时不该占着一行标题
          if (favorites.isNotEmpty()) {
            item {
              FavoriteDirsSection(
                favorites = favorites,
                connections = connections,
                onOpen = { favorite ->
                  val conn = connections.firstOrNull { it.id == favorite.connectionId }
                  if (conn == null) {
                    // 连接被删了：收藏还在（快照不联动），说清楚别静默无响应
                    android.widget.Toast.makeText(
                      context,
                      "「${favorite.name}」的连接已删除，请移除该收藏",
                      android.widget.Toast.LENGTH_SHORT,
                    ).show()
                  } else {
                    backstack.add(
                      NetworkBrowserScreen(
                        connectionId = favorite.connectionId,
                        connectionName = conn.name,
                        currentPath = favorite.path,
                      ),
                    )
                  }
                },
                onDelete = { favorite ->
                  coroutineScope.launch {
                    repository.deleteFavorite(favorite.id)
                  }
                },
              )
            }
          }

          // Section 2: Local Network header
          item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.i18n_local_network))
          }

          // Show empty state or connection list
          if (connections.isEmpty()) {
            item {
              Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Icon(
                    imageVector = Icons.Rounded.SignalWifiStatusbarConnectedNoInternet4,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                  )
                  Spacer(modifier = Modifier.height(16.dp))
                  Text(
                    text = stringResource(R.string.i18n_no_network_connections),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, // a
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.i18n_network_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
            }
          } else {
            items(connections, key = { it.id }) { connection ->
              val status = connectionStatuses[connection.id]
              NetworkConnectionCard(
                connection = connection,
                onConnect = { conn ->
                  viewModel.connect(conn)
                },
                onDisconnect = { conn -> viewModel.disconnect(conn) },
                onEdit = { conn -> editingConnection = conn },
                onDelete = { conn -> viewModel.deleteConnection(conn) },
                onBrowse = { conn ->
                  // Navigate to browser screen if connected
                  if (status?.isConnected == true) {
                    backstack.add(
                      NetworkBrowserScreen(
                        connectionId = conn.id,
                        connectionName = conn.name,
                        currentPath = "/",  // Always start at root - conn.path is already included in connection
                      ),
                    )
                  }
                },
                onAutoConnectChange = { conn, autoConnect ->
                  viewModel.updateConnection(conn.copy(autoConnect = autoConnect))
                },
                onTogglePin = { conn ->
                  viewModel.setPinned(conn.id, !conn.pinned)
                },
                isConnected = status?.isConnected ?: false,
                isConnecting = status?.isConnecting ?: false,
                error = status?.error,
                modifier = Modifier.padding(bottom = 10.dp),
              )
            }
          }
        }

      // Add Connection Sheet
      AddConnectionSheet(
        isOpen = showAddSheet,
        onDismiss = { showAddSheet = false },
        onSave = { connection ->
          viewModel.addConnection(connection)
          showAddSheet = false
        },
      )

      // Edit Connection Sheet
      editingConnection?.let { connection ->
        EditConnectionSheet(
          connection = connection,
          isOpen = true,
          onDismiss = { editingConnection = null },
          onSave = { updatedConnection ->
            viewModel.updateConnection(updatedConnection)
            editingConnection = null
          },
        )
      }
    }
  }
}

/**
 * 段落标题（网络模块统一版式）。
 *
 * 三个段落（直链 / 收藏的目录 / 局域网）共用同一个组件 —— 之前三处各写一份
 * `Text(style=titleLarge, Bold, primary)`，样式一致性全靠人肉同步，迟早跑偏。
 */
@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(vertical = 8.dp),
  )
}

/**
 * 「收藏的目录」快捷入口段：横排卡片，点一下直达该目录
 * （与在连接列表里一层层点进去等价，但省得每次找连接）。
 *
 * ## v69 版式（与 [NetworkConnectionCard] 同一套设计语言）
 *
 * - **协议徽标代替通用文件夹图标**：徽标直接用所属连接的协议代号与强调色，
 *   和下面的连接卡视觉上串成一套 —— 同时一眼能看出这个目录是 SMB 还是 AList 上的；
 * - **信息两条**：目录名（主）+ 连接名（次）。同名目录在不同连接是两个入口，
 *   必须显示连接名才分得清；
 * - **删除钮收进右上角、视觉降级**：它是低频且带破坏性的操作，不该和
 *   「点开目录」这个高频动作抢注意力，所以只留一个淡淡的 ✕；
 * - **连接已删除的收藏**：整卡去色 + 副标题写「连接已删除」，点击由调用方给 Toast
 *   说明（不静默无响应）。
 */
@Composable
private fun FavoriteDirsSection(
  favorites: List<NetworkFavorite>,
  connections: List<NetworkConnection>,
  onOpen: (NetworkFavorite) -> Unit,
  onDelete: (NetworkFavorite) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
  ) {
    SectionHeader(stringResource(R.string.i18n_favorite_dirs))
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(favorites, key = { it.id }) { favorite ->
        val conn = connections.firstOrNull { it.id == favorite.connectionId }
        FavoriteDirCard(
          name = favorite.name,
          connectionName = conn?.name ?: favorite.connectionName,
          protocol = conn?.protocol,
          alive = conn != null,
          onOpen = { onOpen(favorite) },
          onDelete = { onDelete(favorite) },
        )
      }
    }
  }
}

/**
 * 单个收藏目录卡。高度固定 96dp —— 与旧版一致，是「徽标行 + 两行文字」在
 * 12sp/20sp/16sp 三档字号下的足够空间（详见 KDoc 上的版式说明）。
 */
@Composable
private fun FavoriteDirCard(
  name: String,
  connectionName: String,
  protocol: NetworkProtocol?,
  alive: Boolean,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  val accent = if (protocol != null) {
    protocolAccent(protocol)
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  Card(
    onClick = onOpen,
    modifier = Modifier.size(width = 156.dp, height = 96.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // 协议徽标（小一号，24dp —— 快捷入口是次要信息层，不抢主列表的视觉重量）
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
              accent.copy(alpha = if (alive) 0.16f else 0.08f),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = if (protocol != null) protocolBadge(protocol) else "?",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (alive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Text(
          text = name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = if (alive) {
            MaterialTheme.colorScheme.onSurface
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          maxLines = 1,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )

        Text(
          text = if (alive) {
            connectionName
          } else {
            stringResource(R.string.i18n_connection_deleted)
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
      }

      // 移除收藏：低频 + 破坏性 ⇒ 只留一个淡 ✕，不与「点开目录」抢注意力
      IconButton(
        onClick = onDelete,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .size(32.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = stringResource(R.string.i18n_remove_favorite),
          modifier = Modifier.size(15.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun StreamLinkSection(
  onPlayLink: (String) -> Unit,
) {
  val context = LocalContext.current
  var linkUrl by rememberSaveable { mutableStateOf("") }

  Column(
    modifier = Modifier.fillMaxWidth(),
  ) {
    SectionHeader(stringResource(R.string.i18n_stream_link))
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
      ),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
      ) {
        OutlinedTextField(
          value = linkUrl,
          onValueChange = { linkUrl = it },
          label = { Text("视频地址") },
          placeholder = {
            Text(
              text = "https://example.com/video.mp4",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          leadingIcon = {
            Icon(
              imageVector = Icons.Filled.Link,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
          FilledTonalButton(
            onClick = {
              val clipboardManager =
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
              val clipData = clipboardManager?.primaryClip
              if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank()) {
                  linkUrl = text
                }
              }
            },
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
          ) {
            Icon(
              imageVector = Icons.Filled.ContentPaste,
              contentDescription = null,
              modifier = Modifier.padding(end = 8.dp),
            )
            Text(
              text = stringResource(R.string.i18n_paste),
              fontWeight = FontWeight.Bold,
            )
          }

          FilledTonalButton(
            onClick = {
              if (linkUrl.isNotBlank()) {
                onPlayLink(linkUrl)
                linkUrl = ""
              }
            },
            enabled = linkUrl.isNotBlank(),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          ) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              modifier = Modifier.padding(end = 8.dp),
            )
            Text(
              text = stringResource(R.string.i18n_play),
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}
