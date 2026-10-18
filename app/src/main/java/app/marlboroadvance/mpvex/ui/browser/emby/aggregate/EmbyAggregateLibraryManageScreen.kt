package app.marlboroadvance.mpvex.ui.browser.emby.aggregate

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import kotlinx.serialization.Serializable

/**
 * 「聚合库」管理页（设置 → Emby 媒体库与元数据 → 聚合库）。
 *
 * 页面形态：一张列表 + 右下角「新建」。行内显示「名字 + N 个成员库 + 成员名」，
 * 点行 = 编辑（改名 / 改成员），行尾垃圾桶 = 删除（二次确认）。
 *
 * @param editId 非空 = 直接打开这个聚合库的编辑框（从聚合库详情页的「编辑」进来）
 */
@Serializable
data class EmbyAggregateLibraryManageScreen(
  val editId: Long? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current

    val vm: EmbyAggregateLibraryManageViewModel = viewModel(
      factory = EmbyAggregateLibraryManageViewModel.factory(application),
    )
    val server by vm.server.collectAsState()
    val aggregates by vm.aggregates.collectAsState()
    val libraries by vm.libraries.collectAsState()
    val loading by vm.loading.collectAsState()
    val toast by vm.toast.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var editorTarget by remember { mutableStateOf<AggregateLibraryEntity?>(null) }
    var editorCreating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AggregateLibraryEntity?>(null) }

    LaunchedEffect(Unit) { vm.start() }

    // 从详情页带过来的 editId：等列表到位后直接掀编辑框
    LaunchedEffect(editId, aggregates.isNotEmpty()) {
      val id = editId ?: return@LaunchedEffect
      if (aggregates.isNotEmpty()) {
        editorTarget = aggregates.firstOrNull { it.id == id }
      }
    }

    LaunchedEffect(toast) {
      val message = toast ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(message)
      vm.consumeToast()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text("聚合库")
              // 多服务器 / 多用户：聚合库是按服务器分桶的，标题下钉一句当前在管哪台，
              // 避免在 A 服务器上建完、跑去 B 服务器找不到
              server?.let { current ->
                Text(
                  text = current.name,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
      floatingActionButton = {
        if (libraries.isNotEmpty()) {
          FloatingActionButton(onClick = { editorCreating = true }) {
            Icon(Icons.Default.Add, contentDescription = "新建聚合库")
          }
        }
      },
    ) { innerPadding ->
      Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        when {
          loading && aggregates.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) { CircularProgressIndicator() }

          aggregates.isEmpty() -> EmbyEmptyState(
            message = if (libraries.isEmpty()) {
              "这台服务器上没有可合并的媒体库"
            } else {
              "还没有聚合库\n把多个同类型媒体库合成一个视图"
            },
            buttonText = "刷新",
            onAction = { vm.start() },
            modifier = Modifier.fillMaxSize(),
          )

          else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 8.dp,
              bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(aggregates, key = { it.id }) { entity ->
              AggregateRow(
                entity = entity,
                libraries = libraries,
                onEdit = { editorTarget = entity },
                onDelete = { deleteTarget = entity },
              )
            }
          }
        }
      }
    }

    if (editorCreating || editorTarget != null) {
      val editing = editorTarget
      AggregateEditorDialog(
        existing = editing,
        libraries = libraries,
        onDismiss = { editorCreating = false; editorTarget = null },
        onConfirm = { name, memberIds, collectionType ->
          vm.save(editing?.id, name, memberIds, collectionType)
          editorCreating = false
          editorTarget = null
        },
      )
    }

    deleteTarget?.let { target ->
      AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("删除聚合库") },
        text = {
          Text(
            "只删除这个聚合视图，成员库本身不受影响。\n\n「${target.name}」将被移除。",
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              vm.delete(target.id)
              deleteTarget = null
            },
          ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
          TextButton(onClick = { deleteTarget = null }) { Text("取消") }
        },
      )
    }
  }
}

@Composable
private fun AggregateRow(
  entity: AggregateLibraryEntity,
  libraries: List<EmbyItem>,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val members = remember(entity.memberLibraryIds) {
    AggregateLibraryRepository.membersOf(entity.memberLibraryIds)
  }
  val nameById = remember(libraries) { libraries.associateBy { it.Id } }
  val missing = members.count { nameById[it] == null }
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Default.Layers,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp).padding(end = 4.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = entity.name,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = buildString {
            append("${members.size} 个成员库")
            val typeLabel = collectionTypeLabel(entity.collectionType)
            if (typeLabel != null) append(" · $typeLabel")
            if (missing > 0) append(" · $missing 个已失效")
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = members.map { id -> nameById[id]?.Name ?: "（已删除）" }.joinToString("、"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onEdit) {
        Icon(Icons.Default.Edit, contentDescription = "编辑")
      }
      IconButton(onClick = onDelete) {
        Icon(
          Icons.Default.Delete,
          contentDescription = "删除",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/**
 * 新建 / 编辑框。
 *
 * 同类型约束在这里落地：勾选第一个库之后，其余**不同类型**的库置灰不可勾，
 * 顶部给一句当前锁定的类型。已经失效的成员（服务端删了）保留勾态可取消勾选，
 * 但不能重新勾回来。
 */
@Composable
private fun AggregateEditorDialog(
  existing: AggregateLibraryEntity?,
  libraries: List<EmbyItem>,
  onDismiss: () -> Unit,
  onConfirm: (name: String, memberIds: List<String>, collectionType: String?) -> Unit,
) {
  var name by remember { mutableStateOf(existing?.name ?: "") }
  val initialMembers = remember(existing) {
    existing?.let { AggregateLibraryRepository.membersOf(it.memberLibraryIds).toSet() } ?: emptySet()
  }
  var selected by remember { mutableStateOf(initialMembers) }

  val byId = remember(libraries) { libraries.associateBy { it.Id } }
  // 锁定类型 = 第一个勾选库的类型；没勾选时（新建，或编辑时全取消了）退回建库时的类型。
  // 锁定的意义：防止在一个聚合库里混进两种类型的库。
  val lockedType: String? = selected.firstOrNull()?.let { byId[it]?.CollectionType }
    ?: existing?.collectionType
  val staleMembers = remember(existing, libraries) {
    initialMembers.filter { byId[it] == null }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existing == null) "新建聚合库" else "编辑聚合库") },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("名称") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = buildString {
            append("选择要合并的媒体库（至少 2 个）")
            val typeLabel = lockedType?.let { collectionTypeLabel(it) }
            if (typeLabel != null) append(" · 已锁定：$typeLabel")
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        if (staleMembers.isNotEmpty()) {
          Text(
            text = "有 ${staleMembers.size} 个成员库已在服务端删除，取消勾选后保存即可移除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 4.dp),
          )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
          items(libraries, key = { it.Id ?: it.Name ?: "" }) { library ->
            val id = library.Id
            val sameType = lockedType == null ||
              library.CollectionType.isNullOrBlank() ||
              library.CollectionType.equals(lockedType, ignoreCase = true)
            val enabled = sameType || id in selected
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = id != null && id in selected,
                enabled = enabled && id != null,
                onCheckedChange = { checked ->
                  if (id == null) return@Checkbox
                  selected = if (checked) selected + id else selected - id
                },
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = library.Name ?: "未命名媒体库",
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                  } else {
                    MaterialTheme.colorScheme.outline
                  },
                )
                val sub = collectionTypeLabel(library.CollectionType)
                if (sub != null) {
                  Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = name.isNotBlank() && selected.size >= 2,
        onClick = {
          onConfirm(name, selected.toList(), lockedType)
        },
      ) { Text(if (existing == null) "创建" else "保存") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/** CollectionType → 中文名（与首页库卡片图标的判断同一套取值） */
private fun collectionTypeLabel(type: String?): String? =
  when (type?.lowercase()) {
    "movies" -> "电影"
    "tvshows" -> "剧集"
    "music" -> "音乐"
    "photos" -> "照片"
    "homevideos" -> "家庭视频"
    "books" -> "图书"
    "mixed" -> "混合"
    "boxsets" -> "合集"
    "livetv" -> "直播电视"
    else -> null
  }
