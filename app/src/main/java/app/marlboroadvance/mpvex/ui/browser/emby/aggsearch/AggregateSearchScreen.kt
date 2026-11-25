package app.marlboroadvance.mpvex.ui.browser.emby.aggsearch

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateEntry
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateMergeMode
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSection
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSnapshot
import app.marlboroadvance.mpvex.domain.emby.aggregate.AggregateSort
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerHit
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerOutcome
import app.marlboroadvance.mpvex.domain.emby.aggregate.ServerPhase
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyEmptyState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchTab
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchTabRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchTopBar
import app.marlboroadvance.mpvex.ui.browser.emby.playbackProgress
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 聚合搜索页（多服务器并发检索）。
 *
 * ## 与首页全库搜索的边界
 *
 * 这一页**不复用**首页搜索的任何状态：入口是独立路由，关键词、结果、历史都在
 * [AggregateSearchViewModel] 里自持（历史写 `serverId = -1` 的独立分区）。
 * 首页那套 `searchActive` / `searchQuery` / `EmbySearchMemory` 一行没改。
 *
 * 复用的只有**无状态展示组件**（搜索框、页签条、卡片），它们本身不持有搜索状态。
 *
 * ## 页面语言
 *
 * 全部使用工程化表述：参与范围 / 合并视图 / 分组视图 / 已返回 / 原始条数 /
 * 默认打开项。不用「一起搜」「合起来看」这类口语。
 */
@Serializable
object AggregateSearchScreen : Screen {
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val vm: AggregateSearchViewModel =
      viewModel(factory = AggregateSearchViewModel.factory(application))
    val embyVm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))

    val term by vm.term.collectAsState()
    val outcomes by vm.outcomes.collectAsState()
    val snapshot by vm.snapshot.collectAsState()
    val running by vm.running.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val atLimit by vm.atLimit.collectAsState()
    val mergeMode by vm.mergeMode.collectAsState()
    val sort by vm.sort.collectAsState()
    val drawerEntry by vm.drawerEntry.collectAsState()
    val servers by vm.servers.collectAsState()
    val scopeIds by vm.scopeIds.collectAsState()
    val history by vm.history.collectAsState()

    val browserPreferences = koinInject<BrowserPreferences>()
    val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()
    val cardStyleName by browserPreferences.embyLibraryCardStyle.collectAsState()
    val cardStyle =
      remember(cardStyleName) {
        EmbyCardStyle.entries.firstOrNull { it.name == cardStyleName } ?: EmbyCardStyle.POSTER
      }

    val focusRequester = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(EmbySearchTab.ALL) }
    // 换词 ⇒ 回「全部」页签：新结果里可能没有此前选中的类型，
    // 停在一个空页签上会让人以为搜不到东西。
    LaunchedEffect(term) { tab = EmbySearchTab.ALL }

    /** 打开一条结果：换服务器（若不同）→ 进详情 / 演员页 */
    val openHit: (ServerHit) -> Unit = { hit ->
      val id = hit.itemId
      if (!id.isNullOrBlank()) {
        if (embyVm.currentServer.value?.id != hit.serverId) {
          // 走「用户选择」路径：会停掉主备自动接管，不会刚点完又被抢回去
          embyVm.switchServer(hit.server)
          scope.launch { snackbar.showSnackbar("当前服务器已切换为「${hit.serverName}」") }
        }
        if (hit.item.Type.equals("Person", ignoreCase = true)) {
          backStack.add(
            EmbyPersonScreen(
              personId = id,
              personName = hit.item.Name ?: "",
              personImageTag = hit.item.ImageTags["Primary"],
            ),
          )
        } else {
          backStack.add(EmbyDetailScreen(itemId = id, title = hit.item.Name ?: ""))
        }
      }
      vm.closeDrawer()
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        EmbySearchTopBar(
          query = term,
          onQueryChange = vm::onTermChange,
          onSubmit = vm::submit,
          onBack = { backStack.removeLastOrNull() },
          focusRequester = focusRequester,
          hint =
            if (servers.size > 1) {
              "在 ${servers.size} 台服务器中检索…"
            } else {
              "在当前服务器中检索…"
            },
        )

        // 参与范围：**输入关键词之前就能选**。没选中的台不会收到任何请求，
        // 所以这块必须在空词态也可见（不能等搜过一次才出现）。
        // ⚠️ 判定必须基于 `scopeIds` 这个状态流，不能只在点击回调里同步读：
        //    chip 填充态与「已选 N/M 台」都要跟着偏好走，否则恢复页面时会显示旧值。
        val selectedCount =
          remember(servers, scopeIds) {
            val declared = scopeIds
            if (declared == null) servers.size else servers.count { it.id in declared }
          }
        if (servers.size >= 2) {
          AggregateScopeHeader(
            selected = selectedCount,
            total = servers.size,
            onSelectAll = vm::selectAllServers,
            onDeselectAll = vm::deselectAllServers,
          )
          AggregateServerStrip(
            outcomes = outcomes,
            scopeIds = scopeIds,
            onToggle = vm::toggleServer,
            onOnly = vm::searchOnlyServer,
            modifier = Modifier.padding(bottom = 6.dp),
          )
        }

        AggregateToolbar(
          mergeMode = mergeMode,
          onToggleMode = vm::toggleMergeMode,
          sort = sort,
          onSortChange = vm::setSort,
          status =
            toolbarStatus(
              term = term,
              mergeMode = mergeMode,
              snapshot = snapshot,
              outcomes = outcomes,
              running = running,
              selectedCount = selectedCount,
            ),
          running = running,
          canRetry =
            outcomes.any {
              it.phase == ServerPhase.Failed || it.phase == ServerPhase.Skipped
            },
          onRetry = vm::retryFailed,
        )

        if (term.isBlank()) {
          AggregateLanding(
            serversCount = servers.size,
            history = history,
            onPickHistory = vm::pickHistory,
            onRemoveHistory = vm::removeHistory,
            onClearHistory = vm::clearHistory,
            onOpenServerManage = { backStack.add(EmbyServerManageScreen) },
          )
        } else {
          val flatItems =
            remember(snapshot) {
              if (snapshot.sections.isNotEmpty()) {
                snapshot.sections.flatMap { section -> section.hits.map { it.item } }
              } else {
                snapshot.entries.map { it.item }
              }
            }
          val counts = remember(flatItems) { EmbySearchTab.countByTab(flatItems) }

          EmbySearchTabRow(
            counts = counts,
            total = flatItems.size,
            selected = tab,
            onSelectedChange = { tab = it },
          )

          val visibleEntries =
            remember(snapshot.entries, tab) {
              if (tab == EmbySearchTab.ALL) {
                snapshot.entries
              } else {
                snapshot.entries.filter { EmbySearchTab.classify(it.item) == tab }
              }
            }
          val visibleSections =
            remember(snapshot.sections, tab) {
              if (tab == EmbySearchTab.ALL) {
                snapshot.sections
              } else {
                snapshot.sections
                  .map { section ->
                    section.copy(
                      hits = section.hits.filter { EmbySearchTab.classify(it.item) == tab },
                    )
                  }
                  .filter { it.hits.isNotEmpty() }
              }
            }

          val hasContent =
            if (mergeMode == AggregateMergeMode.MERGE) {
              visibleEntries.isNotEmpty()
            } else {
              visibleSections.isNotEmpty()
            }

          when {
            // 首屏还没有任何一批到达：不画空态 —— 空态的意思是「搜完了没有」
            !hasContent && running && snapshot.rawCount == 0 ->
              // 只数**仍在查询**的台：未参与的台一直是 Pending，算进去会把 2 台说成 3 台
              AggregateProgress(
                serverCount = outcomes.count { it.phase == ServerPhase.Running },
              )

            !hasContent && !running && snapshot.rawCount == 0 ->
              EmbyEmptyState(
                message = emptyReason(outcomes),
                buttonText = "重试",
                onAction = vm::refresh,
                modifier = Modifier.fillMaxSize(),
              )

            !hasContent ->
              EmbyEmptyState(
                message = "该分类下没有结果",
                buttonText = "查看全部",
                onAction = { tab = EmbySearchTab.ALL },
                modifier = Modifier.fillMaxSize(),
              )

            else ->
              LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns.coerceAtLeast(1)),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                if (mergeMode == AggregateMergeMode.MERGE) {
                  items(visibleEntries, key = { it.key }) { entry ->
                    AggregateCard(
                      entry = entry,
                      cardStyle = cardStyle,
                      imageUrl = { hit -> embyVm.imageUrl(hit.server, hit.item, "Primary", 480) },
                      title = { hit -> embyVm.displayTitle(hit.item) },
                      onClick = { openHit(entry.primary) },
                      onLongClick = { vm.openDrawer(entry) },
                    )
                  }
                } else {
                  visibleSections.forEach { section ->
                    item(
                      key = "section-${section.serverId}",
                      span = { GridItemSpan(maxLineSpan) },
                    ) {
                      SectionHeader(section)
                    }
                    items(section.hits, key = { "${it.serverId}:${it.itemId}" }) { hit ->
                      AggregateCard(
                        // 分组视图不合并：每条自带一个只有一份的 entry，
                        // 走同一套卡片代码，不额外分叉
                        entry = AggregateEntry(key = "${hit.serverId}:${hit.itemId}", hits = listOf(hit)),
                        cardStyle = cardStyle,
                        imageUrl = { h -> embyVm.imageUrl(h.server, h.item, "Primary", 480) },
                        title = { h -> embyVm.displayTitle(h.item) },
                        onClick = { openHit(hit) },
                        onLongClick = null,
                      )
                    }
                  }
                }

                // 翻页脚注：进入视野即自动补一页，也留一个手动入口。
                // 只在有参与台尚未到底（或已达上限）时有内容，空态走不到这里。
                item(key = "aggregate-load-more", span = { GridItemSpan(maxLineSpan) }) {
                  AggregateLoadMoreFooter(
                    hasMore = hasMore,
                    loading = loadingMore,
                    atLimit = atLimit,
                    loadedCount = snapshot.rawCount,
                    onLoad = vm::loadMore,
                  )
                }
              }
          }
        }
      }

      SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
      )
    }

    drawerEntry?.let { entry ->
      AggregateCopySheet(
        entry = entry,
        onDismiss = vm::closeDrawer,
        onOpen = openHit,
      )
    }
  }
}

// ==================== 局部组件 ====================

/**
 * 翻页脚注。
 *
 * 进入视野即自动补页（`LaunchedEffect` 在脚注首次组合时执行），同时保留手动入口 ——
 * 弱网下自动补页失败时，用户仍能重试而不必整页下拉刷新。
 */
@Composable
private fun AggregateLoadMoreFooter(
  hasMore: Boolean,
  loading: Boolean,
  atLimit: Boolean,
  loadedCount: Int,
  onLoad: () -> Unit,
) {
  LaunchedEffect(hasMore, loading) {
    if (hasMore && !loading) onLoad()
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when {
      loading -> {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.6.dp)
        Spacer(Modifier.width(8.dp))
        Text(
          text = "正在加载下一页",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      atLimit ->
        Text(
          text = "已加载 $loadedCount 条，达到单次浏览上限",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      hasMore -> TextButton(onClick = onLoad) { Text("加载更多") }
      else ->
        Text(
          text = "已全部加载（$loadedCount 条）",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
  }
}

@Composable
private fun AggregateToolbar(
  mergeMode: AggregateMergeMode,
  onToggleMode: () -> Unit,
  sort: AggregateSort,
  onSortChange: (AggregateSort) -> Unit,
  status: String,
  running: Boolean,
  canRetry: Boolean,
  onRetry: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ModeChip(
        label = "合并视图",
        selected = mergeMode == AggregateMergeMode.MERGE,
        onClick = { if (mergeMode != AggregateMergeMode.MERGE) onToggleMode() },
      )
      ModeChip(
        label = "分组视图",
        selected = mergeMode == AggregateMergeMode.GROUP,
        onClick = { if (mergeMode != AggregateMergeMode.GROUP) onToggleMode() },
      )
      Spacer(Modifier.weight(1f))
      SortMenu(current = sort, onSelect = onSortChange)
    }

    // 状态行单独一行：台数 + 条数在窄屏上跟上面两个 chip 挤一行会把文字压成省略号
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (running) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.4.dp)
        Spacer(Modifier.width(2.dp))
      }
      Text(
        text = status,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      if (canRetry && !running) {
        TextButton(
          onClick = onRetry,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
          Text("重试失败项", style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

/**
 * 排序菜单。
 *
 * 只有**当前结果集能被可靠重排**的维度，且方向固定（见 [AggregateSort]）。
 * 不跟着媒体库页那 11 项走：那边是服务端全库排序，这边是客户端对样本重排，
 * 混用会让用户以为「按播放次数排序」也是全库口径。
 */
@Composable
private fun SortMenu(
  current: AggregateSort,
  onSelect: (AggregateSort) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { expanded = true },
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Sort,
        contentDescription = null,
        modifier = Modifier.size(15.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(4.dp))
      Text(
        text = current.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      AggregateSort.entries.forEach { option ->
        DropdownMenuItem(
          text = {
            Text(
              text = option.label,
              style = MaterialTheme.typography.bodyMedium,
              color =
                if (option == current) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          },
          onClick = {
            expanded = false
            onSelect(option)
          },
        )
      }
    }
  }
}

/**
 * 参与范围的标题行：`参与范围 · 已选 2/3 台` + 右侧「全选 / 取消全选」。
 *
 * 存在的理由是**顺序**：用户要先知道「这次会问哪几台」，再输入关键词。
 * 只给一排 chip 而不说明「这些是什么、选中了几台」，很难看出「没选的不会发请求」。
 *
 * ⚠️ 右侧动作**常驻**，不按状态增删：此前是「全选时才不画按钮」，
 * 点掉一台后按钮突然出现，标题与 chip 条整体被顶下去（现场反馈「有跳动」）。
 * 现在槽位固定最小宽度，只切换文案 —— 行高、位置都不动。
 */
@Composable
private fun AggregateScopeHeader(
  selected: Int,
  total: Int,
  onSelectAll: () -> Unit,
  onDeselectAll: () -> Unit,
) {
  val isAll = selected >= total
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "参与范围",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = if (isAll) "全部参与（$total 台）" else "已选 $selected/$total 台",
      style = MaterialTheme.typography.labelSmall,
      color =
        if (isAll) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.primary
        },
      maxLines = 1,
    )
    Spacer(Modifier.weight(1f))
    // 固定槽位：文案在「全选」「取消全选」之间切换时按钮外框尺寸不变
    Box(
      modifier = Modifier.widthIn(min = 78.dp),
      contentAlignment = Alignment.CenterEnd,
    ) {
      TextButton(
        onClick = if (isAll) onDeselectAll else onSelectAll,
        // ⚠️ 不给这个按钮加固定 `height`：M3 按钮会被裁字（见 ui-patterns 的结论），
        //    这里靠 contentPadding 收窄即可
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
      ) {
        Text(
          text = if (isAll) "取消全选" else "全选",
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun ModeChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier = Modifier
      .height(28.dp)
      .clip(shape)
      .background(
        if (selected) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceContainerHigh
        },
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      color =
        if (selected) {
          MaterialTheme.colorScheme.onSecondaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      maxLines = 1,
    )
  }
}

@Composable
private fun SectionHeader(section: AggregateSection) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = section.serverName,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    Text(
      text = "${section.hits.size} 项",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 结果卡片。
 *
 * 单台命中的条目**不加任何服务器标记** —— 加了反而让「多数只有一台有」的常态变成噪音。
 * 多台命中时右上角给「N 台」角标，长按展开逐台副本（见 [AggregateCopySheet]）。
 */
@Composable
private fun AggregateCard(
  entry: AggregateEntry,
  cardStyle: EmbyCardStyle,
  imageUrl: (ServerHit) -> String?,
  title: (ServerHit) -> String,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
) {
  val hit = entry.primary
  EmbyMediaCard(
    title = title(hit),
    subtitle = cardSubtitle(hit),
    imageUrl = imageUrl(hit),
    progress = playbackProgress(hit.item),
    isFavorite = hit.item.UserData?.IsFavorite == true,
    onClick = onClick,
    style = cardStyle,
    badgeText = if (entry.serverCount > 1) "${entry.serverCount} 台" else null,
    onLongClick =
      if (entry.serverCount > 1 && onLongClick != null) {
        { _ -> onLongClick() }
      } else {
        null
      },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AggregateLanding(
  serversCount: Int,
  history: List<String>,
  onPickHistory: (String) -> Unit,
  onRemoveHistory: (String) -> Unit,
  onClearHistory: () -> Unit,
  onOpenServerManage: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "聚合检索",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (serversCount < 2) {
      Text(
        text = "需要两台以上服务器",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onOpenServerManage, contentPadding = PaddingValues(0.dp)) {
        Text("服务器管理")
      }
      return@Column
    }

    if (history.isEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Dns,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "输入关键词开始检索",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
      }
      return@Column
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "最近检索",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      TextButton(onClick = onClearHistory, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("清空", style = MaterialTheme.typography.labelSmall)
      }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      history.forEach { keyword ->
        HistoryChip(
          keyword = keyword,
          onPick = { onPickHistory(keyword) },
          onRemove = { onRemoveHistory(keyword) },
        )
      }
    }
  }
}

@Composable
private fun HistoryChip(
  keyword: String,
  onPick: () -> Unit,
  onRemove: () -> Unit,
) {
  val shape = RoundedCornerShape(50)
  Row(
    modifier = Modifier
      .height(34.dp)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .clickable(onClick = onPick)
      .padding(start = 14.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = keyword,
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 168.dp),
    )
    Box(
      modifier = Modifier
        .size(30.dp)
        .clip(shape)
        .clickable(onClick = onRemove),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Cancel,
        contentDescription = "删除「$keyword」",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

@Composable
private fun AggregateProgress(serverCount: Int) {
  Column(
    modifier = Modifier.fillMaxSize().padding(top = 64.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    Text(
      text = if (serverCount > 0) "正在查询 $serverCount 台服务器" else "正在查询",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// ==================== 文案 ====================

/**
 * 工具栏状态行：只写事实（参与几台、已返回几台、多少条），不写「正在努力加载」这类话。
 *
 * ⚠️ 分母是**参与台数**而不是 `outcomes.size`：chip 条里还挂着未参与的台，
 * 用 `outcomes.size` 当分母会出现「已返回 2/3 台」而实际只问了 2 台。
 */
private fun toolbarStatus(
  term: String,
  mergeMode: AggregateMergeMode,
  snapshot: AggregateSnapshot,
  outcomes: List<ServerOutcome>,
  running: Boolean,
  selectedCount: Int,
): String {
  if (outcomes.isEmpty()) return "未选择服务器"
  if (term.isBlank()) {
    // 空词态没有结果可言，报「合并后 0 条」会像搜过了
    return if (selectedCount > 0) "参与 $selectedCount 台 · 等待关键词" else "未选择服务器"
  }
  val done = outcomes.count { it.phase.isTerminal }
  return if (running) {
    "已返回 $done/$selectedCount 台"
  } else if (mergeMode == AggregateMergeMode.MERGE) {
    "合并后 ${snapshot.entries.size} 条 · 原始 ${snapshot.rawCount} 条"
  } else {
    "原始 ${snapshot.rawCount} 条 · ${snapshot.sections.size} 段"
  }
}

/** 空结果的原因：优先说失败，其次说未登录，最后才是「没有命中」 */
private fun emptyReason(outcomes: List<ServerOutcome>): String {
  val failed = outcomes.filter { it.phase == ServerPhase.Failed }
  if (failed.isNotEmpty()) {
    val first = failed.first()
    val reason = first.message ?: "请求失败"
    return if (failed.size == 1) {
      "「${first.name}」查询失败：$reason"
    } else {
      "${failed.size} 台服务器查询失败，首条原因：$reason"
    }
  }
  val skipped = outcomes.filter { it.phase == ServerPhase.Skipped }
  if (skipped.isNotEmpty()) {
    return "「${skipped.first().name}」未发送请求：${skipped.first().message ?: "需重新登录"}"
  }
  return "参与服务器均未命中该关键词"
}

/** 卡片副标题：年份 · 画质；两样都没有时退回类型名，不写占位词 */
private fun cardSubtitle(hit: ServerHit): String? {
  val parts = ArrayList<String>(2)
  hit.item.ProductionYear?.let { parts += it.toString() }
  hit.resolutionLabel?.let { parts += it }
  if (parts.isEmpty()) {
    parts += typeLabel(hit.item.Type)
  }
  return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun typeLabel(type: String?): String =
  when (type?.lowercase()) {
    "movie" -> "电影"
    "series" -> "剧集"
    "episode" -> "单集"
    "boxset" -> "合集"
    "person" -> "演员"
    "video" -> "视频"
    "musicvideo" -> "音乐视频"
    else -> type.orEmpty()
  }
