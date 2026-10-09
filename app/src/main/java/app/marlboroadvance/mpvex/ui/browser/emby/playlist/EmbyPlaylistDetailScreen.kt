package app.marlboroadvance.mpvex.ui.browser.emby.playlist

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonList
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 单个 Emby 播放列表的条目页。
 *
 * 功能：分页浏览、拖拽排序、移除条目、播放全部。
 *
 * ## 关于拖拽排序
 *
 * 排序用的是「长按拖动手柄 → 跟手移动 → 松手提交」的模式（而不是列表整体按下就能拖），
 * 因为条目本身单击要播放、长按要看详情，全列表抢手势会和这两个操作打架。
 *
 * 拖动过程中先在本地改顺序（松手立刻能看到结果），再调
 * [EmbyPlaylistViewModel.moveEntry] 同步服务端；同步失败会拉回服务端真值纠正。
 */
@Serializable
data class EmbyPlaylistDetailScreen(
  val playlistId: String,
  val playlistName: String,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val vm: EmbyPlaylistViewModel = viewModel(
      factory = EmbyPlaylistViewModel.factory(application),
    )
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by embyVm.currentServer.collectAsState()

    val entries by vm.entries.collectAsState()
    val loading by vm.entriesLoading.collectAsState()
    val hasMore by vm.entriesHasMore.collectAsState()
    val totalText by vm.entryCountText.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var removeTarget by remember { mutableStateOf<EmbyItem?>(null) }

    // 拖拽排序状态。-1 = 没在拖。
    // draggingIndex = 被拖动的行；dragToIndex = 当前悬停的目标行；
    // dragOffsetY = 手指相对起点的累计纵向位移，用来算悬停到了哪一行。
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragToIndex by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // 只在 server 与 playlist 都就绪时拉一次。server 可能比页面晚一步才恢复，
    // 所以键里带上 server?.id，让它在服务器到达时**再**触发一次。
    LaunchedEffect(server?.id, playlistId) {
      val current = server ?: return@LaunchedEffect
      vm.loadEntries(current, playlistId)
    }

    LaunchedEffect(toast) {
      val message = toast ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.consumeToast()
    }

    // 触底续拉。派生 lastVisible 只在真正接近底部时才变，避免每滚一像素就重组
    val shouldLoadMore by remember(entries.size) {
      derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        last >= 0 && last >= entries.size - 5
      }
    }
    LaunchedEffect(shouldLoadMore, server?.id) {
      if (!shouldLoadMore) return@LaunchedEffect
      val current = server ?: return@LaunchedEffect
      if (hasMore && !loading) vm.loadMoreEntries(current, playlistId)
    }

    // 拖拽结束后提交一次排序。放在 LaunchedEffect 里而不是手势回调末尾：
    // 手势回调触发时 recomposition 还没跑，本地列表顺序还是旧的，容易拿到错的下标。
    LaunchedEffect(dragToIndex, draggingIndex) {
      val from = draggingIndex
      val to = dragToIndex
      if (from >= 0 && to >= 0 && from != to) {
        val current = server
        if (current != null) vm.moveEntry(current, playlistId, from, to)
      }
      draggingIndex = -1
      dragToIndex = -1
      dragOffsetY = 0f
    }

    Scaffold(
      topBar = {
        Column {
          TopAppBar(
            title = {
              Column {
                Text(
                  text = playlistName,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = totalText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            navigationIcon = {
              IconButton(onClick = { backStack.removeLastOrNull() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
              }
            },
            actions = {
              IconButton(
                onClick = {
                  val current = server
                  if (current != null) vm.loadEntries(current, playlistId)
                },
                enabled = server != null && !loading,
              ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
              }
            },
          )
        }
      },
      floatingActionButton = {
        // 空列表时 FAB 让位给空状态里的按钮，避免两个「新建」入口打架
        if (entries.isNotEmpty() && server != null) {
          ExtendedFloatingActionButton(
            onClick = {
              val current = server ?: return@ExtendedFloatingActionButton
              vm.playAll(current, playlistId) { }
            },
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            text = { Text("播放全部") },
            modifier = Modifier.navigationBarsPadding(),
          )
        }
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
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

          loading && entries.isEmpty() -> EmbySkeletonList(itemCount = 8)

          error != null && entries.isEmpty() -> EmbyEmptyState(
            message = error ?: "读取失败",
            buttonText = "重试",
            onAction = { vm.loadEntries(current, playlistId) },
            modifier = Modifier.align(Alignment.Center),
          )

          entries.isEmpty() -> EmbyEmptyState(
            message = "这个播放列表还是空的。到媒体库或详情页用「加入播放列表」往里加片子。",
            buttonText = "刷新",
            onAction = { vm.loadEntries(current, playlistId) },
            modifier = Modifier.align(Alignment.Center),
          )

          else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
          ) {
            itemsIndexed(
              items = entries,
              // 拖拽中**绝不能**换 key：换了 LazyColumn 会把正在拖的行重建掉，
              // 表现为「松手后顺序弹回去」。
              key = { index, item -> item.Id ?: "$index:${item.Name}" },
            ) { index, item ->
              val dragging = draggingIndex == index
              // 目标行高亮：拖到哪儿，一眼能看见。跟手移动本身由手柄的
              // pointerInput 完成（见 trailingContent），列表项只负责显示状态。
              val isTarget = dragToIndex == index && draggingIndex >= 0 && !dragging
              ListItem(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    when {
                      dragging -> MaterialTheme.colorScheme.surfaceVariant
                      isTarget -> MaterialTheme.colorScheme.primaryContainer
                      else -> Color.Transparent
                    },
                  )
                  // 单击进详情页（播放由详情页发起，和 Emby 其他页面一致）
                  //
                  // ⚠️ 必须用 `MediaItemId` 而不是 `Id`：
                  // `/Playlists/{Id}/Items` 返回的每一行是「播放列表里的一条目」，
                  // 它的 `Id` 是条目自己的 Id，拿去请求媒体详情服务端查不到 ⇒ 500。
                  // `MediaItemId` 才是媒体本身的 Id。老服务端不返回这个字段时退回 `Id`
                  // （那种情况下条目 Id 与媒体 Id 相同，能打开）。
                  .combinedClickable(
                    onClick = {
                      val id = item.MediaItemId ?: item.Id ?: return@combinedClickable
                      backStack.add(
                        app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen(
                          id,
                          item.Name ?: "媒体",
                        ),
                      )
                    },
                    onLongClick = { removeTarget = item },
                  ),
                leadingContent = {
                  EmbyImage(
                    url = embyVm.imageUrl(current, item, "Primary"),
                    contentDescription = null,
                    maxWidth = 180,
                    modifier = Modifier.size(width = 48.dp, height = 64.dp),
                  )
                },
                headlineContent = {
                  Text(
                    text = item.Name ?: "未命名",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                supportingContent = {
                  val parts = mutableListOf<String>()
                  item.Type?.let { parts.add(typeLabel(it)) }
                  item.SeriesName?.let { parts.add(it) }
                  item.ProductionYear?.let { parts.add(it.toString()) }
                  if (parts.isNotEmpty()) {
                    Text(
                      text = parts.joinToString(" · "),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                },
                trailingContent = {
                  // 手柄自己吃掉手势：长按进入拖动态，之后按 dy 累计量推算目标行。
                  // 只绑在**手柄**上而不是整行 —— 整行绑了会和单击/长按（详情/移除）打架。
                  val handleModifier = Modifier.pointerInput(index, entries.size) {
                    detectDragGesturesAfterLongPress(
                      onDragStart = {
                        draggingIndex = index
                        dragToIndex = index
                        dragOffsetY = 0f
                      },
                      onDrag = { change, dragAmount ->
                        change.consume()
                        val total = dragOffsetY + dragAmount.y
                        dragOffsetY = total
                        // 每一行高度取自 LazyList 的布局信息（不写死 dp：条目高度会随
                        // 标题折行变化，写死就会算偏）。找不到就保持当前目标。
                        val info = listState.layoutInfo
                        val rowHeight = info.visibleItemsInfo
                          .firstOrNull { it.index == index }?.size
                        if (rowHeight != null && rowHeight > 0) {
                          val step = (total / rowHeight).toInt()
                          dragToIndex = (index + step).coerceIn(0, entries.lastIndex)
                        }
                      },
                      onDragEnd = { /* 提交交给上面的 LaunchedEffect */ },
                      onDragCancel = {
                        draggingIndex = -1
                        dragToIndex = -1
                        dragOffsetY = 0f
                      },
                    )
                  }
                  Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handleModifier.padding(12.dp),
                  )
                },
              )
            }
            if (loading && entries.isNotEmpty()) {
              item {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
              }
            }
          }
        }
      }
    }

    // ── 移除确认 ──
    val removing = removeTarget
    if (removing != null) {
      AlertDialog(
        onDismissRequest = { removeTarget = null },
        title = { Text("从播放列表移除？") },
        text = {
          Text(
            "「${removing.Name ?: "这一项"}」会从这个播放列表里移除。" +
              "**媒体本身不会被删除**，只是不再出现在这个列表中。",
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              val s = server
              val id = removing.Id
              removeTarget = null
              if (s == null || id == null) return@TextButton
              scope.launch { vm.removeEntries(s, playlistId, listOf(id)) }
            },
          ) {
            Text("移除", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { removeTarget = null }) { Text("取消") }
        },
      )
    }
  }
}

/** Emby 的 Type 字段 → 中文标签。只覆盖播放列表里真会出现的几种。 */
private fun typeLabel(type: String): String = when (type) {
  "Movie" -> "电影"
  "Series" -> "剧集"
  "Episode" -> "单集"
  "MusicAlbum" -> "专辑"
  "MusicArtist" -> "艺术家"
  "Audio" -> "音频"
  "Video" -> "视频"
  "BoxSet" -> "合集"
  else -> type
}