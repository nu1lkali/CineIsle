package app.marlboroadvance.mpvex.ui.browser.emby.collection

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySectionHeader
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonList
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 合集（BoxSet）列表页（清单 #15）。
 *
 * 页面形态用**行式列表**而不是网格：合集通常只有十来个，
 * 且用户要看的是「名字 + 有几部」，网格的海报大图在这里没什么用。
 *
 * @param onOpenItems 打开某个合集的条目页
 */
@Serializable
object EmbyCollectionScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current

    val vm: EmbyCollectionViewModel = viewModel(
      factory = EmbyCollectionViewModel.factory(application),
    )
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by embyVm.currentServer.collectAsState()

    val collections by vm.collections.collectAsState()
    val loading by vm.listLoading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var createDialogShown by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EmbyItem?>(null) }

    // 没有服务器时什么都不做：「还没配服务器」与「没有合集」必须能区分开
    LaunchedEffect(server?.id) {
      val current = server ?: return@LaunchedEffect
      vm.loadCollections(current, force = true)
    }

    LaunchedEffect(error) {
      val message = error ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.clearError()
    }

    LaunchedEffect(toast) {
      val message = toast ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.consumeToast()
    }

    if (createDialogShown) {
      var name by remember { mutableStateOf("") }
      AlertDialog(
        onDismissRequest = { createDialogShown = false },
        title = { Text("新建合集") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              label = { Text("合集名称") },
              singleLine = true,
            )
            Text(
              text = "建好后进去可以往里加影片。合集里的同一部片子不能重复添加。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        confirmButton = {
          TextButton(
            enabled = name.isNotBlank(),
            onClick = {
              val current = server ?: return@TextButton
              createDialogShown = false
              vm.createCollection(current, name) { id ->
                if (id != null) {
                  backStack.add(EmbyCollectionItemsScreen(id, name))
                }
              }
            },
          ) { Text("创建") }
        },
        dismissButton = {
          TextButton(onClick = { createDialogShown = false }) { Text("取消") }
        },
      )
    }

    deleteTarget?.let { target ->
      AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("删除合集？") },
        text = {
          Text("「${target.Name ?: "这个合集"}」会被删掉，里面的影片不受影响。")
        },
        confirmButton = {
          TextButton(onClick = {
            val current = server
            deleteTarget = null
            if (current != null) vm.deleteCollection(current, target)
          }) { Text("删除") }
        },
        dismissButton = {
          TextButton(onClick = { deleteTarget = null }) { Text("取消") }
        },
      )
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("合集") },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
          actions = {
            IconButton(
              onClick = { val current = server; if (current != null) vm.loadCollections(current, force = true) },
              enabled = !loading,
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { createDialogShown = true }) {
              Icon(Icons.Default.Add, contentDescription = "新建合集")
            }
          },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
      when {
        loading && collections.isEmpty() -> {
          // 原来是居中一句「加载中…」，连转圈都没有 —— 全项目最弱的加载反馈，
          // 用户会以为页面卡死了。换成骨架屏：合集是网格列表，
          // 规格与 [EmbySkeletonGrid] 的默认 3 列一致。
          EmbySkeletonGrid(modifier = Modifier.padding(padding))
        }

        collections.isEmpty() -> {
          EmbyEmptyState(
            message = "还没有合集。合集可以把同一部作品的不同版本收在一起。",
            buttonText = "新建合集",
            onAction = { createDialogShown = true },
            modifier = Modifier.padding(padding),
          )
        }

        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
          ) {
            items(collections, key = { it.Id ?: it.hashCode().toString() }) { item ->
              CollectionRow(
                item = item,
                imageUrl = item.Id?.let { id -> server?.let { embyVm.imageUrl(it, item, "Primary", 180) } },
                onClick = {
                  val id = item.Id
                  if (id != null) backStack.add(EmbyCollectionItemsScreen(id, item.Name ?: "合集"))
                },
                onDelete = { deleteTarget = item },
              )
            }
          }
        }
      }
    }
  }
}

/** 合集一行：封面 + 名字 + 条目数 + 更多菜单。 */
@Composable
private fun CollectionRow(
  item: EmbyItem,
  imageUrl: String?,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Card(shape = MaterialTheme.shapes.small) {
      EmbyImage(
        url = imageUrl,
        contentDescription = item.Name,
        modifier = Modifier.size(width = 56.dp, height = 84.dp),
        placeholder = Icons.Default.Album,
      )
    }
    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = item.Name ?: "未命名合集",
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        // ChildCount 是服务端算的，可能与实际有差 —— 显示它没问题，
        // 但绝不能拿它当「是否为空」的判断条件（见 EmbyClient.getCollections）
        text = "${item.ChildCount ?: 0} 项",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Box {
      IconButton(onClick = { menuExpanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "更多")
      }
      DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(
          text = { Text("删除合集") },
          onClick = {
            menuExpanded = false
            onDelete()
          },
        )
      }
    }
  }
}

/**
 * 单个合集的条目页。
 *
 * **没有拖拽排序**：合集不存在 `Move` 端点（只有播放列表有）。
 * 需要调顺序只能改服务端，本类提供不了这个能力。
 */
@Serializable
data class EmbyCollectionItemsScreen(val collectionId: String, val title: String) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current

    val vm: EmbyCollectionViewModel = viewModel(
      factory = EmbyCollectionViewModel.factory(application),
    )
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by embyVm.currentServer.collectAsState()

    val items by vm.items.collectAsState()
    val loading by vm.itemsLoading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val countText by vm.itemCountText.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var pickerShown by remember { mutableStateOf(false) }

    LaunchedEffect(server?.id, collectionId) {
      val current = server ?: return@LaunchedEffect
      vm.loadItems(current, collectionId)
    }

    LaunchedEffect(error) {
      val message = error ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.clearError()
    }

    LaunchedEffect(toast) {
      val message = toast ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.consumeToast()
    }

    if (pickerShown) {
      CollectionPickerDialog(
        existingIds = items.mapNotNull { it.Id }.toSet(),
        onPick = { picked ->
          val current = server ?: return@CollectionPickerDialog
          pickerShown = false
          vm.addItems(current, collectionId, picked)
        },
        onDismiss = { pickerShown = false },
      )
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
          actions = {
            IconButton(onClick = { pickerShown = true }) {
              Icon(Icons.Default.Add, contentDescription = "添加影片")
            }
          },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        EmbySectionHeader(title = countText)
        when {
          loading && items.isEmpty() -> {
            // 同上：原来是纯文字「加载中…」。合集详情是紧凑行列表，用 List 规格。
            EmbySkeletonList(itemCount = 6)
          }

          items.isEmpty() -> {
            EmbyEmptyState(
              message = "这个合集还是空的。",
              buttonText = "添加影片",
              onAction = { pickerShown = true },
            )
          }

          else -> {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
              items(items, key = { it.Id ?: it.hashCode().toString() }) { item ->
                CollectionItemRow(
                  item = item,
                  imageUrl = server?.let { embyVm.imageUrl(it, item, "Primary", 180) },
                  onClick = {
                    val id = item.Id
                    if (id != null) {
                      backStack.add(
                        app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen(
                          id,
                          item.Name ?: "",
                        ),
                      )
                    }
                  },
                  onRemove = {
                    val current = server ?: return@CollectionItemRow
                    vm.removeItems(current, collectionId, listOf(item))
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/** 合集里的一行：封面 + 片名 + 年份 + 移出按钮。 */
@Composable
private fun CollectionItemRow(
  item: EmbyItem,
  imageUrl: String?,
  onClick: () -> Unit,
  onRemove: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Card(shape = MaterialTheme.shapes.small) {
      EmbyImage(
        url = imageUrl,
        contentDescription = item.Name,
        modifier = Modifier.size(width = 44.dp, height = 64.dp),
        placeholder = Icons.Default.Album,
      )
    }
    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = item.Name ?: "未命名",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = listOfNotNull(
          item.ProductionYear?.toString(),
          item.Type,
          item.OfficialRating,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TextButton(onClick = onRemove) { Text("移出") }
  }
}

/**
 * 「添加影片」选择框。
 *
 * 候选从**继续观看**和**最新加入**里取，而不是全库搜索：
 * 绝大多数合集成员是用户近期看过的片，这个来源命中率远高于让用户从头翻。
 * 需要更冷门的片时，用户可以先去媒体库里搜到再从详情页加 —— 那条路更长但更准。
 *
 * 已在合集里的（[existingIds]）显示为已选中且**不可再点**：
 * 合集不允许同一部重复，界面必须直接表达这条约束，而不是点了没反应。
 */
@Composable
private fun CollectionPickerDialog(
  existingIds: Set<String>,
  onPick: (List<EmbyItem>) -> Unit,
  onDismiss: () -> Unit,
) {
  val embyVm: EmbyViewModel = viewModel(
    factory = EmbyViewModel.factory(androidx.compose.ui.platform.LocalContext.current.applicationContext as Application),
  )
  val server by embyVm.currentServer.collectAsState()
  val resume by embyVm.resumeItems.collectAsState()
  val latest by embyVm.latestItems.collectAsState()

  val candidates = remember(resume, latest, existingIds) {
    (resume + latest).distinctBy { it.Id }.filter { it.Id != null && it.Id !in existingIds }
  }
  var selected by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("添加影片") },
    text = {
      if (candidates.isEmpty()) {
        Text("最近没有可添加的内容。合集里的影片不能重复添加。")
      } else {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "已选 ${selected.size} 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // 候选可能十几个，用 FlowRow 而不是 Row：Row 会把非 weight 子项压扁
          // （项目红线：中文按钮排不下的原因就是 Row 压缩非 weight 子项）
          androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            candidates.forEach { item ->
              val isSelected = selected.any { it.Id == item.Id }
              androidx.compose.material3.FilterChip(
                selected = isSelected,
                onClick = {
                  selected = if (isSelected) {
                    selected.filter { it.Id != item.Id }
                  } else {
                    selected + item
                  }
                },
                label = {
                  Text(
                    text = item.Name ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = selected.isNotEmpty(),
        onClick = { onPick(selected) },
      ) { Text("添加 ${selected.size} 项") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}
