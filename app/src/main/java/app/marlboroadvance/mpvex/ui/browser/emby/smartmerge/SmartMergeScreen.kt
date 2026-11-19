package app.marlboroadvance.mpvex.ui.browser.emby.smartmerge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.smartmerge.OP_DONE
import app.marlboroadvance.mpvex.domain.smartmerge.OP_FAILED
import app.marlboroadvance.mpvex.domain.smartmerge.OP_RUNNING
import app.marlboroadvance.mpvex.domain.smartmerge.OP_SPLIT
import app.marlboroadvance.mpvex.domain.smartmerge.OP_SPLIT_FAILED
import app.marlboroadvance.mpvex.domain.smartmerge.OP_VERIFY_FAILED
import app.marlboroadvance.mpvex.domain.smartmerge.SMART_MERGE_SPLIT_VERIFIED
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_CONFLICT
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_HIGH
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_IGNORED
import app.marlboroadvance.mpvex.domain.smartmerge.STATUS_MERGED
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeCandidate
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeItem
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeOperation
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeRules
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.components.ClassicPageScaffold
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable

@Serializable
object SmartMergeScreen : Screen {
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: SmartMergeViewModel = viewModel(
      factory = SmartMergeViewModel.factory(context.applicationContext as android.app.Application),
    )
    val ui by vm.ui.collectAsState()
    val state by vm.mergeState.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val paused by vm.paused.collectAsState()
    val progress by vm.progress.collectAsState()
    val message by vm.message.collectAsState()
    val notice by vm.notice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var historyQuery by remember { mutableStateOf("") }
    var selectedCandidate by remember { mutableStateOf<SmartMergeCandidate?>(null) }
    var preview by remember { mutableStateOf<Pair<SmartMergeCandidate, String?>?>(null) }
    var directMerge by remember { mutableStateOf<Pair<SmartMergeCandidate, String?>?>(null) }
    var showScanSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showTray by remember { mutableStateOf(false) }
    // 全屏 Dialog 会盖住页面级 Snackbar，所以清单生成的失败原因必须显示在弹窗内部。
    var manualError by remember { mutableStateOf<String?>(null) }
    var trayError by remember { mutableStateOf<String?>(null) }
    var rollback by remember { mutableStateOf<SmartMergeOperation?>(null) }
    var previewPlayback by remember { mutableStateOf<SmartMergePreviewSession?>(null) }

    LaunchedEffect(message) {
      val text = message ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(text)
      vm.clearMessage()
    }

    LaunchedEffect(notice) {
      val text = notice ?: return@LaunchedEffect
      snackbarHostState.showSnackbar(text)
      vm.clearNotice()
    }

    val pending = remember(state.candidates, query) {
      val q = query.trim()
      state.candidates
        .filter { it.status != STATUS_MERGED && it.status != STATUS_IGNORED }
        .filter {
          q.isBlank() || it.code.contains(q, ignoreCase = true) ||
            it.title.contains(q, ignoreCase = true)
        }
    }

    ClassicPageScaffold(
      snackbarHostState = snackbarHostState,
      topBar = {
        Column {
          BrowserTopBar(
            title = "智能合并",
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = 0,
            onCancelSelection = {},
            onBackClick = { backStack.removeLastOrNull() },
            additionalActions = {
              IconButton(
                onClick = {
                  manualError = null
                  showManual = true
                },
              ) {
                Icon(Icons.Filled.Search, contentDescription = "手动匹配")
              }
            },
          )
          TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("概览") })
            Tab(
              selected = tab == 1,
              onClick = { tab = 1 },
              text = { Text("待处理${if (pending.isNotEmpty()) " (${pending.size})" else ""}") },
            )
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("历史") })
          }
        }
      },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (scanning) {
          val fraction = if (progress.total > 0) {
            (progress.scanned.toFloat() / progress.total).coerceIn(0f, 1f)
          } else {
            0f
          }
          LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(3.dp),
          )
        }
        when (tab) {
          0 -> OverviewTab(
            ui = ui,
            state = state,
            scanning = scanning,
            paused = paused,
            progress = progress,
            onScan = { showScanSettings = true },
            onManual = {
              manualError = null
              showManual = true
            },
            onPauseScan = vm::pauseScan,
            onResumeScan = vm::resumeScan,
            onCancelScan = vm::cancelScan,
            onOpenPending = { tab = 1 },
          )
          1 -> PendingTab(
            pending = pending,
            trayCount = ui.tray.size,
            query = query,
            imageUrl = vm::itemImageUrl,
            onQueryChange = { query = it },
            onCandidateClick = { selectedCandidate = it },
            onOpenTray = {
              trayError = null
              showTray = true
            },
            onScan = { showScanSettings = true },
            lastScanAt = state.lastScanAt,
          )
          else -> HistoryTab(
            operations = state.operations,
            query = historyQuery,
            onQueryChange = { historyQuery = it },
            onRollback = { rollback = it },
          )
        }
      }
    }

    if (showScanSettings) {
      ScanSettingsSheet(
        ui = ui,
        rules = state.rules,
        scanning = scanning,
        onToggleLibrary = vm::toggleLibrary,
        onSelectAll = vm::selectAllLibraries,
        onInvert = vm::invertLibraries,
        onClear = vm::clearLibraries,
        onRulesChange = vm::updateRules,
        onScan = {
          vm.scanSelected()
          showScanSettings = false
        },
        onDismiss = { showScanSettings = false },
      )
    }

    selectedCandidate?.let { candidate ->
      SmartMergeCandidateDetailDialog(
        candidate = candidate,
        imageUrl = vm::itemImageUrl,
        onPreviewPlay = { item ->
          previewPlayback = SmartMergePreviewSession(candidate.items, item.id)
        },
        onDismiss = { selectedCandidate = null },
        onIgnore = {
          vm.ignore(candidate)
          selectedCandidate = null
        },
        onPreview = { primaryId ->
          selectedCandidate = null
          preview = candidate to primaryId
        },
        onDirectMerge = { primaryId ->
          selectedCandidate = null
          directMerge = candidate to primaryId
        },
        onRemoveItem = { itemId ->
          vm.removeCandidateItem(candidate, itemId)
          selectedCandidate = vm.mergeState.value.candidates.firstOrNull { it.id == candidate.id }
        },
      )
    }

    preview?.let { (candidate, primaryId) ->
      MergePreviewDialog(
        candidate = candidate,
        primaryId = primaryId,
        imageUrl = vm::itemImageUrl,
        onPreviewPlay = { item ->
          previewPlayback = SmartMergePreviewSession(candidate.items, item.id)
        },
        onDismiss = { preview = null },
        onConfirm = {
          vm.merge(candidate, primaryId)
          preview = null
        },
      )
    }

    if (showManual) {
      SmartMergeManualSearchDialog(
        ui = ui,
        imageUrl = vm::itemImageUrl,
        onQueryChange = vm::setManualQuery,
        onSearch = vm::manualSearch,
        onToggle = vm::toggleTrayItem,
        onOpenTray = {
          trayError = null
          showTray = true
        },
        onCreate = {
          val result = vm.createCandidateFromTray()
          if (result.success) {
            manualError = null
            showManual = false
            tab = 1
          } else {
            manualError = result.message
          }
        },
        onDismiss = {
          vm.clearManualSearch()
          manualError = null
          showManual = false
        },
        errorMessage = manualError,
      )
    }

    if (showTray) {
      SmartMergeTrayDialog(
        items = ui.tray,
        imageUrl = vm::itemImageUrl,
        onRemove = vm::removeFromTray,
        onClear = vm::clearTray,
        onCombine = {
          val result = vm.createCandidateFromTray()
          if (result.success) {
            trayError = null
            showTray = false
            showManual = false
            tab = 1
          } else {
            trayError = result.message
          }
        },
        onDismiss = {
          trayError = null
          showTray = false
        },
        errorMessage = trayError,
      )
    }

    directMerge?.let { (candidate, primaryId) ->
      SmartMergeDirectMergeDialog(
        candidate = candidate,
        primaryId = primaryId,
        onDismiss = { directMerge = null },
        onConfirm = {
          vm.merge(candidate, primaryId)
          directMerge = null
        },
      )
    }

    rollback?.let { operation ->
      RollbackDialog(
        operation = operation,
        onDismiss = { rollback = null },
        onConfirm = {
          vm.split(operation)
          rollback = null
        },
      )
    }

    previewPlayback?.let { session ->
      val selectedItem = session.items.firstOrNull { it.id == session.selectedId }
      val request = selectedItem?.let(vm::previewRequest)
      if (request != null) {
        SmartMergePreviewDialog(
          request = request,
          items = session.items,
          selectedId = session.selectedId,
          onSelectItem = { item -> previewPlayback = session.copy(selectedId = item.id) },
          onDismiss = { previewPlayback = null },
        )
      }
    }
  }
}

@Composable
private fun OverviewTab(
  ui: SmartMergeUiState,
  state: app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeState,
  scanning: Boolean,
  paused: Boolean,
  progress: app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeScanProgress,
  onScan: () -> Unit,
  onManual: () -> Unit,
  onPauseScan: () -> Unit,
  onResumeScan: () -> Unit,
  onCancelScan: () -> Unit,
  onOpenPending: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(20.dp, 18.dp, 20.dp, 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("Smart Merge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
          Text(
            "扫描重复作品，生成 Emby 原生多版本组合候选。组合前必须人工确认。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            StatPill("媒体库", ui.libraries.size.toString(), Modifier.weight(1f))
            StatPill("候选", state.candidates.count { it.status != STATUS_MERGED }.toString(), Modifier.weight(1f))
            StatPill("高置信", state.candidates.count { it.isHighConfidence && it.status != STATUS_MERGED }.toString(), Modifier.weight(1f))
            StatPill("历史", state.operations.size.toString(), Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = onScan,
              enabled = !scanning && ui.libraries.isNotEmpty(),
              modifier = Modifier.weight(1f),
            ) { Text(if (scanning) "扫描中…" else "开始扫描") }
            OutlinedButton(onClick = onOpenPending, enabled = state.candidates.isNotEmpty()) {
              Text("查看候选")
            }
          }
          OutlinedButton(
            onClick = onManual,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (ui.tray.isEmpty()) {
                "手动搜索并组合（A + B + C）"
              } else {
                "继续手动组合 · 待处理 ${ui.tray.size} 项"
              },
            )
          }
          if (scanning) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                "已扫描 ${progress.scanned} / ${progress.total}" + if (paused) "（已暂停）" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = if (paused) onResumeScan else onPauseScan) {
                Text(if (paused) "继续" else "暂停")
              }
              TextButton(onClick = onCancelScan) { Text("取消") }
            }
          }
          if (ui.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(Modifier.size(18.dp))
              Text("  正在读取服务器…", style = MaterialTheme.typography.bodySmall)
            }
          }
          ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    if (!ui.admin) {
      item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
          Text(
            "当前账号不是 Emby 管理员：可以扫描和查看候选，但不能执行合并。",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }

    item {
      Text("最近操作", style = MaterialTheme.typography.titleMedium)
    }
    if (state.operations.isEmpty()) {
      item {
        Text(
          "还没有组合记录。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      items(state.operations.take(3), key = { it.id }) { op ->
        OperationCard(op, onRollback = {}, allowRollback = false)
      }
    }
  }
}

@Composable
private fun PendingTab(
  pending: List<SmartMergeCandidate>,
  trayCount: Int,
  query: String,
  imageUrl: (SmartMergeItem) -> String?,
  onQueryChange: (String) -> Unit,
  onCandidateClick: (SmartMergeCandidate) -> Unit,
  onOpenTray: () -> Unit,
  onScan: () -> Unit,
  lastScanAt: Long,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text("搜索番号或标题") },
      )
    }
    if (trayCount > 0) {
      item {
        Card(
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
          ),
          onClick = onOpenTray,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                "待组合：$trayCount 项",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
              Text(
                "来自手动搜索的 A / B / C。确认清单后生成候选，再打开候选合并。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
            Button(onClick = onOpenTray) { Text("查看清单") }
          }
        }
      }
    }
    if (pending.isEmpty()) {
      item {
        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            if (lastScanAt == 0L) "还没有扫描结果" else "没有待处理候选",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            if (lastScanAt == 0L) "先选择媒体库并扫描。" else "已合并或已忽略的候选不会出现在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(onClick = onScan) { Text("扫描设置") }
        }
      }
    } else {
      items(pending, key = { it.id }) { candidate ->
        CandidateCard(
          candidate = candidate,
          imageUrl = candidate.items.firstOrNull()?.let(imageUrl),
          onClick = { onCandidateClick(candidate) },
        )
      }
    }
  }
}

@Composable
private fun HistoryTab(
  operations: List<SmartMergeOperation>,
  query: String,
  onQueryChange: (String) -> Unit,
  onRollback: (SmartMergeOperation) -> Unit,
) {
  val filtered = remember(operations, query) {
    val q = query.trim()
    if (q.isBlank()) operations
    else operations.filter { op ->
      op.candidateTitle?.contains(q, ignoreCase = true) == true ||
        op.candidateCode?.contains(q, ignoreCase = true) == true ||
        op.originalItemIds.any { it.contains(q, ignoreCase = true) } ||
        op.resultItemId?.contains(q, ignoreCase = true) == true
    }
  }
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text("搜索番号 / 标题 / ItemId") },
      )
    }
    if (filtered.isEmpty()) {
      item {
        Text(
          if (operations.isEmpty()) "还没有组合记录。" else "没有匹配的历史记录。",
          modifier = Modifier.padding(top = 48.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      items(filtered, key = { it.id }) { op ->
        OperationCard(op, onRollback = { onRollback(op) })
      }
    }
  }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScanSettingsSheet(
  ui: SmartMergeUiState,
  rules: SmartMergeRules,
  scanning: Boolean,
  onToggleLibrary: (String) -> Unit,
  onSelectAll: () -> Unit,
  onInvert: () -> Unit,
  onClear: () -> Unit,
  onRulesChange: (SmartMergeRules) -> Unit,
  onScan: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text("扫描设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "媒体库（已选 ${ui.selectedLibraryIds.size} / ${ui.libraries.size}）",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
          )
          TextButton(onClick = onSelectAll) { Text("全选") }
          TextButton(onClick = onInvert) { Text("反选") }
          TextButton(onClick = onClear) { Text("清空") }
        }
        if (ui.libraries.isEmpty()) {
          Text("没有可扫描的媒体库", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            ui.libraries.forEach { library ->
              val id = library.Id ?: return@forEach
              FilterChip(
                selected = id in ui.selectedLibraryIds,
                onClick = { onToggleLibrary(id) },
                label = { Text(library.Name ?: "媒体库", maxLines = 1) },
              )
            }
          }
        }

        HorizontalDivider()
        Text("智能规则", style = MaterialTheme.typography.titleSmall)
        Text(
          "番号始终参与；下面勾选的维度用于加权。修改后重新扫描生效。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          RuleChip("ProviderIds", rules.useProviderIds) {
            onRulesChange(rules.copy(useProviderIds = it))
          }
          RuleChip("标题", rules.useTitle) {
            onRulesChange(rules.copy(useTitle = it))
          }
          RuleChip("演员", rules.useActors) {
            onRulesChange(rules.copy(useActors = it))
          }
          RuleChip("时长", rules.useDuration) {
            onRulesChange(rules.copy(useDuration = it))
          }
          RuleChip("ProviderIds 单独成组", rules.allowProviderOnlyGroups) {
            onRulesChange(rules.copy(allowProviderOnlyGroups = it))
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("最低置信度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          listOf(40, 70, 90).forEach { score ->
            FilterChip(
              selected = rules.minScore == score,
              onClick = { onRulesChange(rules.copy(minScore = score)) },
              label = { Text("$score") },
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
        Button(
          onClick = onScan,
          enabled = !scanning && ui.selectedLibraryIds.isNotEmpty(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (scanning) "扫描中…" else "扫描选中媒体库")
        }
      }
  }
}

@Composable
private fun RuleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  FilterChip(
    selected = checked,
    onClick = { onCheckedChange(!checked) },
    label = { Text(label) },
  )
}

@Composable
private fun CandidateCard(
  candidate: SmartMergeCandidate,
  imageUrl: String?,
  onClick: () -> Unit,
) {
  Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 58.dp, height = 82.dp).clip(RoundedCornerShape(8.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            candidate.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          ConfidenceBadge(candidate)
        }
        Text(
          "${candidate.code} · ${candidate.itemIds.size} 个条目",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          candidate.versionSummary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          candidate.sourceSummary,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidate.conflicts.isNotEmpty()) {
          Text(
            "冲突：" + candidate.conflicts.joinToString("；"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun ConfidenceBadge(candidate: SmartMergeCandidate) {
  val color = when (candidate.status) {
    STATUS_HIGH -> MaterialTheme.colorScheme.primary
    STATUS_CONFLICT -> MaterialTheme.colorScheme.error
    STATUS_MERGED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
  }
  Text(
    "${candidate.confidence} 分",
    color = color,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun MergePreviewDialog(
  candidate: SmartMergeCandidate,
  primaryId: String?,
  imageUrl: (SmartMergeItem) -> String?,
  onPreviewPlay: (SmartMergeItem) -> Unit,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("确认组合") },
    text = {
      LazyColumn(
        modifier = Modifier.heightIn(max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          Text("将 ${candidate.itemIds.size} 个独立媒体项目组合为一个 Emby 多版本项目。")
          Text(
            "不会主动删除原始媒体文件。",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        items(candidate.items, key = { it.id }) { item ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ItemSummary(
              item = item,
              imageUrl = imageUrl(item),
              highlight = item.id == primaryId,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onPreviewPlay(item) }) {
              Icon(Icons.Filled.PlayArrow, contentDescription = "小窗预览")
            }
          }
        }
        item {
          Text(
            "服务端可能只保留其中一个 ItemId，收藏/播放进度/元数据是否合并由 Emby 决定。"
              + "组合前已在本机保存快照，但快照不能保证完整回退。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            "版本名规则：Emby 会剥掉多个文件名的公共前缀后再显示版本名。"
              + "例如 DGCEMD-229-4K 与 DGCEMD-229 可能只剩 229-4K / 229；"
              + "这是服务端命名行为，本 App 不会改文件名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = { Button(onClick = onConfirm) { Text("确认合并") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@Composable
private fun ItemSummary(
  item: SmartMergeItem,
  imageUrl: String?,
  modifier: Modifier = Modifier,
  highlight: Boolean = false,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    EmbyImage(
      url = imageUrl,
      contentDescription = null,
      modifier = Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(6.dp)),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        item.displayTitle,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        (item.code?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
          item.version.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      item.path?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * 账本里存的是协议常量（RUNNING / DONE …），历史页要显示给人看。
 *
 * 不在账本里直接存中文：状态是**判定结果**，中文只是展示层措辞，
 * 以后改文案不该影响已经落盘的历史记录。
 */
private fun operationStatusLabel(status: String): String = when (status) {
  OP_RUNNING -> "进行中"
  OP_DONE -> "已完成"
  OP_VERIFY_FAILED -> "结果待确认"
  OP_FAILED -> "失败"
  OP_SPLIT -> "已拆分"
  OP_SPLIT_FAILED -> "拆分失败"
  else -> status
}

@Composable
private fun OperationCard(
  operation: SmartMergeOperation,
  onRollback: () -> Unit,
  allowRollback: Boolean = true,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          operationStatusLabel(operation.status),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          // 失败类状态直接标红：历史里一眼能挑出「要人管」的那几条
          color = if (operation.status == OP_FAILED || operation.status == OP_SPLIT_FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        if (allowRollback && operation.status == OP_DONE && operation.resultItemId != null) {
          TextButton(onClick = onRollback) { Text("拆分（待验证）") }
        }
      }
      Text(
        (operation.candidateTitle ?: "组合记录") + " · 原始 ${operation.originalItemIds.size} 个条目",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      operation.resultItemId?.let {
        Text("结果 ItemId：$it", style = MaterialTheme.typography.labelSmall)
      }
      if (operation.resultVersionNames.isNotEmpty()) {
        Text(
          "Emby 返回版本名：" + operation.resultVersionNames.joinToString(" / "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      operation.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      if (operation.status == OP_DONE) {
        Text(
          if (SMART_MERGE_SPLIT_VERIFIED) {
            "拆分接口已验证；仍会重新查询结果，不承诺恢复 ItemId/元数据。"
          } else {
            "拆分接口尚未验证：阶段 0 完成前不会执行。"
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun RollbackDialog(
  operation: SmartMergeOperation,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("拆分？") },
    text = {
      Text(
        "将调用 Emby 的 remove alternate sources 接口（DELETE /Videos/{Id}/AlternateSources）。\n\n"
          + "⚠️ 该接口的拆分语义尚未在测试服验证，不能保证原 ItemId、收藏、播放进度与元数据恢复。"
      )
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        enabled = SMART_MERGE_SPLIT_VERIFIED,
      ) { Text(if (SMART_MERGE_SPLIT_VERIFIED) "执行拆分" else "待验证，暂不可用") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}
