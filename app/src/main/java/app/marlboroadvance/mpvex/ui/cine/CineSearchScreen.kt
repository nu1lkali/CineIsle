package app.marlboroadvance.mpvex.ui.cine

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.EmbySearchMemory
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.recommendImageUrl
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SearchRecommendViewModel
import app.marlboroadvance.mpvex.ui.cine.components.CineChevronIcon
import app.marlboroadvance.mpvex.ui.cine.components.CineSectionAction
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 搜索防抖：每敲一个字就发一次请求，对十万级库的服务端是灾难 */
private const val SEARCH_DEBOUNCE_MS = 320L

/** 单次搜索上限：与 Classic 的全库搜索一致（结果再多也只能往下翻，没有分页） */
private const val SEARCH_LIMIT = 60

/**
 * 全屏搜索页（Cine 版，v257 按设计稿重做）。
 *
 * ## 三层内容
 * 1. **空词** → 搜索历史 + 推荐。两块在同一个 `LazyColumn` 里上下排，
 *    历史条数再多也只是把推荐往下推，不会出现两个各自滚动的区域抢手势。
 * 2. **有词** → 结果网格（点一下直接播、长按进详情，沿用原有语义）。
 * 3. **推荐「查看更多」** → 整页列表（叠在这一层之上，返回先收它）。
 *
 * ## 数据
 * - 搜索：复用 [EmbyViewModel.search]（与 Classic 同一批结果，只是排版不同）。
 * - 推荐：[SearchRecommendViewModel]，四路服务端数据混合。它的缓存是**进程级**的，
 *   所以「输入→删空→再输入」不会把推荐反复重拉。
 *
 * ## 焦点
 * 进入时不抢焦点只在「从详情页返回」时生效 —— 返回的用户想看结果，不是想立刻打字。
 */
@Composable
fun CineSearchOverlay(
  /** 与 [CineRoot] 共用的留存票据（见 [EmbySearchMemory]）；决定「返回」还是「重新进入」 */
  ticket: String,
  onClose: () -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val recommendViewModel: SearchRecommendViewModel = viewModel(
    factory = SearchRecommendViewModel.factory(app),
  )
  val server by viewModel.currentServer.collectAsState()
  val recommendState by recommendViewModel.state.collectAsState()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val keyboard = LocalSoftwareKeyboardController.current
  val play = rememberCinePlay()

  // 搜索历史：与 Classic / 媒体库内搜索共用同一张表、同一份「服务器 + 用户」隔离
  val historyRepository = koinInject<SearchHistoryRepository>()
  val historyFlow = remember(server?.id) { historyRepository.observe(server?.id) }
  val history by historyFlow.collectAsState(initial = emptyList())
  val scope = rememberCoroutineScope()

  // 从详情页返回：词与结果由留存表带回（见文件末尾 openDetailWithMemory）
  val restored = remember {
    // 服务器对不上就不复用：结果是那台服务端给的，换一台还回放等于显示不存在的条目
    EmbySearchMemory.take("$ticket|content")?.takeIf { it.serverId == null || it.serverId == server?.id }
  }
  var pendingRestore by remember { mutableStateOf(restored != null) }
  var query by remember { mutableStateOf(restored?.query ?: "") }
  var results by remember { mutableStateOf<List<CineMediaUi>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var lastTerm by remember { mutableStateOf(restored?.query ?: "") }

  val focusRequester = remember { FocusRequester() }

  // 系统返回键：退搜索（推荐已经是一层可滚列表，不再有「查看更多」的二级页）
  BackHandler { onClose() }

  // 推荐只在没有关键词时取（有词时整页是结果，取推荐纯属浪费一次请求）
  LaunchedEffect(server?.id, query.isBlank()) {
    if (query.isBlank()) recommendViewModel.start(server)
  }

  LaunchedEffect(query, server?.id) {
    // 返回路径：把留存的那批结果重新映射回 UI 模型，不发请求
    if (pendingRestore) {
      pendingRestore = false
      val snap = restored
      val current = server ?: run {
        searching = false
        return@LaunchedEffect
      }
      if (snap != null) {
        results = snap.items.map { viewModel.toCineMedia(current, it) }
        lastTerm = snap.query
      }
      searching = false
      return@LaunchedEffect
    }
    val term = query.trim()
    errorText = null
    if (term.isEmpty()) {
      results = emptyList()
      searching = false
      lastTerm = ""
      return@LaunchedEffect
    }
    searching = true
    delay(SEARCH_DEBOUNCE_MS)
    val current = server
    if (current == null) {
      searching = false
      errorText = context.getString(R.string.cine_search_no_server)
      return@LaunchedEffect
    }
    val found = runCatching { viewModel.search(current, term, limit = SEARCH_LIMIT) }
    found
      .onSuccess { items ->
        results = items.map { viewModel.toCineMedia(current, it) }
        lastTerm = term
        // 有结果才记历史：一个字都没命中的多半是打岔，记下来只会污染列表
        if (items.isNotEmpty()) historyRepository.record(current.id, term)
      }
      .onFailure { errorText = it.message }
    searching = false
  }

  /** 进详情页之前把「词 + 结果」拍进留存表，返回时原样带回 */
  val openDetailWithMemory: (EmbyItem) -> Unit = { item ->
    if (query.isNotBlank()) {
      val snap = EmbySearchMemory.Snapshot(
        query = query,
        items = results.map { it.item },
        serverId = server?.id,
      )
      EmbySearchMemory.attach("$ticket|content", snap)
      EmbySearchMemory.attach("$ticket|open", snap)
    }
    onOpenDetail(item)
  }

  val wide = rememberCineWide()
  val columns = if (wide) 5 else 3
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(88.dp)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface)
      // 键盘弹起时整页缩到键盘之上：顶栏留在原位不动，列表与网格跟着收，
      // 否则最后一行卡片永远压在键盘下面（结果页与空词态都要，所以挂在这一层）
      .imePadding(),
  ) {
    CineSearchTopBar(
      query = query,
      onQueryChange = { query = it },
      onSubmit = {
        keyboard?.hide()
        val term = query.trim()
        if (term.isNotEmpty()) {
          scope.launch { server?.let { historyRepository.record(it.id, term) } }
        }
      },
      onBack = onClose,
      focusRequester = focusRequester,
      autoFocus = restored == null,
    )

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        query.isBlank() -> CineSearchLanding(
          history = history,
          onPickHistory = { keyword ->
            query = keyword
            keyboard?.hide()
            scope.launch { server?.let { historyRepository.record(it.id, keyword) } }
          },
          onRemoveHistory = { keyword ->
            scope.launch { server?.let { historyRepository.remove(it.id, keyword) } }
          },
          onClearHistory = {
            scope.launch { server?.let { historyRepository.clear(it.id) } }
          },
          recommendItems = recommendState.items,
          recommendLoading = recommendState.loading,
          recommendError = recommendState.error,
          recommendLoadingMore = recommendState.loadingMore,
          recommendEndReached = recommendState.endReached,
          imageUrl = { item ->
            server?.let { s -> recommendImageUrl(viewModel, s, item) }
          },
          onOpenRecommend = { openDetailWithMemory(it) },
          onRetryRecommend = { recommendViewModel.retry(server) },
          onLoadMoreRecommend = { recommendViewModel.loadMore(server) },
          onRefresh = { recommendViewModel.refreshAndWait(server) },
          onDismissKeyboard = { keyboard?.hide() },
        )

        errorText != null -> CineStatePanel(
          icon = Icons.Outlined.SearchOff,
          title = stringResource(R.string.cine_state_error_title),
          description = errorText,
          isError = true,
        )

        searching && results.isEmpty() -> Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.TopCenter,
        ) {
          CircularProgressIndicator(
            color = colors.progressFill,
            strokeWidth = 2.5.dp,
            modifier = Modifier
              .padding(top = 48.dp)
              .size(26.dp),
          )
        }

        results.isEmpty() -> CineStatePanel(
          icon = Icons.Outlined.SearchOff,
          title = stringResource(R.string.cine_search_empty_title),
          description = context.getString(R.string.cine_search_empty_desc, lastTerm.ifBlank { query }),
        )

        else -> CineMediaGrid(
          items = results,
          cardWidth = cardWidth,
          columns = columns,
          onClick = { media ->
            keyboard?.hide()
            play(media.item, media.resumeSeconds)
          },
          onLongClick = { media ->
            keyboard?.hide()
            openDetailWithMemory(media.item)
          },
        )
      }
    }
  }
}

/** 顶栏：返回 + 玻璃胶囊输入框 + 主色搜索键 */
@Composable
private fun CineSearchTopBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  focusRequester: FocusRequester,
  autoFocus: Boolean,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  if (autoFocus) {
    // 放在组件内部：调用方那一层还没组合出输入框，requestFocus 会找不到节点
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .clickable(onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
        contentDescription = stringResource(R.string.cine_search_back),
        tint = colors.onSurface,
        modifier = Modifier.size(20.dp),
      )
    }

    Row(
      modifier = Modifier
        .weight(1f)
        .height(44.dp)
        .clip(shape)
        .background(colors.chipSurface)
        .border(1.dp, colors.chipBorder, shape)
        .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Search,
        contentDescription = null,
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(17.dp),
      )
      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
          Text(
            text = stringResource(R.string.cine_search_hint),
            style = type.itemTitle,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = query,
          onValueChange = onQueryChange,
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
          textStyle = type.itemTitle.copy(color = colors.onSurface),
          singleLine = true,
          cursorBrush = SolidColor(colors.progressFill),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
      }
      if (query.isNotEmpty()) {
        Box(
          modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable { onQueryChange("") },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cine_search_clear_input),
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }

    Text(
      text = stringResource(R.string.cine_search_action),
      style = type.button,
      color = colors.progressFill,
      maxLines = 1,
      modifier = Modifier
        .clip(RoundedCornerShape(50))
        .clickable(onClick = onSubmit)
        .padding(horizontal = 8.dp, vertical = 8.dp),
    )
  }
}

/**
 * 空词态：搜索历史 + 推荐。
 *
 * 整页一个 `LazyColumn`：历史是一组 FlowRow 胶囊，推荐是若干行两列卡片。
 * 两个区块共用一个滚动容器，避免「内外两层各滚各的」那种滑不动的观感。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CineSearchLanding(
  history: List<String>,
  onPickHistory: (String) -> Unit,
  onRemoveHistory: (String) -> Unit,
  onClearHistory: () -> Unit,
  recommendItems: List<EmbyItem>,
  recommendLoading: Boolean,
  recommendError: String?,
  recommendLoadingMore: Boolean,
  recommendEndReached: Boolean,
  imageUrl: (EmbyItem) -> String?,
  onOpenRecommend: (EmbyItem) -> Unit,
  onRetryRecommend: () -> Unit,
  onLoadMoreRecommend: () -> Unit,
  onRefresh: suspend () -> Unit,
  onDismissKeyboard: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val context = LocalContext.current
  val isRefreshing = remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val rows = remember(recommendItems) { recommendItems.chunked(2) }
  var confirmClear by remember { mutableStateOf(false) }

  // 触底续页：没有「查看更多」入口之后，推荐就靠滚动自己长出来
  LaunchedEffect(listState, rows.size, recommendEndReached, recommendLoadingMore) {
    if (rows.isEmpty()) return@LaunchedEffect
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
      .collect { last ->
        if (!recommendEndReached && !recommendLoadingMore && last >= rows.size - 2) {
          onLoadMoreRecommend()
        }
      }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text(stringResource(R.string.cine_search_clear_title)) },
      text = {
        Text(stringResource(R.string.cine_search_clear_desc, history.size))
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmClear = false
            onClearHistory()
          },
        ) { Text(stringResource(R.string.cine_search_clear_confirm)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) {
          Text(stringResource(R.string.cine_action_cancel))
        }
      },
    )
  }

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    // 点空白处收键盘挂在这一层（容器）上：既不拦滚动，也不抢子项点击。
    // ⚠️ 别写成「列表的兄弟节点铺满全屏」—— 那会盖在列表上面，滚动与卡片点击全部失效。
    modifier = Modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onDismissKeyboard,
      ),
    listState = listState,
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = space.contentBottomInset),
    ) {
      item(key = "cine-search-history") {
        Column(modifier = Modifier.padding(bottom = 10.dp)) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = space.screenH, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Outlined.History,
              contentDescription = null,
              tint = colors.onSurfaceVariant,
              modifier = Modifier.size(17.dp),
            )
            Text(
              text = stringResource(R.string.cine_search_history_title),
              style = type.pageBarTitle,
              color = colors.onSurface,
              modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
              maxLines = 1,
            )
            if (history.isNotEmpty()) {
              Row(
                modifier = Modifier
                  .clip(RoundedCornerShape(50))
                  .clickable { confirmClear = true }
                  .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteOutline,
                  contentDescription = null,
                  tint = colors.onSurfaceVariant,
                  modifier = Modifier.size(15.dp),
                )
                Text(
                  text = stringResource(R.string.cine_search_history_clear),
                  style = type.cardMeta,
                  color = colors.onSurfaceVariant,
                  maxLines = 1,
                )
              }
            }
          }

          if (history.isEmpty()) {
            Text(
              text = stringResource(R.string.cine_search_history_empty),
              style = type.cardMeta,
              color = colors.onSurfaceVariant,
              modifier = Modifier.padding(
                start = space.screenH,
                end = space.screenH,
                top = 4.dp,
              ),
            )
          } else {
            FlowRow(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = space.screenH, vertical = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              history.forEach { keyword ->
                CineHistoryChip(
                  keyword = keyword,
                  onPick = { onPickHistory(keyword) },
                  onRemove = {
                    onRemoveHistory(keyword)
                    Toast.makeText(
                      context,
                      context.getString(R.string.cine_search_history_removed, keyword),
                      Toast.LENGTH_SHORT,
                    ).show()
                  },
                )
              }
            }
          }
        }
      }

      item(key = "cine-recommend-header") {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = space.screenH, end = space.screenH, bottom = space.sectionHeadGap),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = colors.progressFill,
            modifier = Modifier.size(17.dp),
          )
          Text(
            text = stringResource(R.string.cine_search_recommend_title),
            style = type.sectionTitle,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier
              .weight(1f)
              .padding(start = 8.dp),
          )
        }
      }

      when {
        recommendLoading && recommendItems.isEmpty() -> items(3, key = { "cine-recommend-skeleton-$it" }) {
          CineRecommendSkeletonRow()
          Spacer(Modifier.height(space.itemGap))
        }

        recommendItems.isEmpty() && recommendError != null ->
          item(key = "cine-recommend-error") {
            CineStatePanel(
              icon = Icons.Outlined.SearchOff,
              title = stringResource(R.string.cine_search_recommend_empty_title),
              description = recommendError,
              isError = true,
              primaryLabel = stringResource(R.string.cine_search_retry),
              onPrimary = onRetryRecommend,
            )
          }

        recommendItems.isEmpty() -> item(key = "cine-recommend-empty") {
          CineStatePanel(
            icon = Icons.Filled.AutoAwesome,
            title = stringResource(R.string.cine_search_recommend_empty_title),
            description = stringResource(R.string.cine_search_recommend_empty_desc),
          )
        }

        else -> items(rows, key = { row -> row.firstOrNull()?.Id ?: row.hashCode().toString() }) { row ->
          CineRecommendRow(
            items = row,
            imageUrl = imageUrl,
            onClick = onOpenRecommend,
            modifier = Modifier.padding(bottom = space.itemGap),
          )
        }
      }

      // 续页脚：只在真有下一页、且正在取的时候占一行
      if (recommendLoadingMore) {
        item(key = "cine-recommend-more") {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 44.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              color = colors.progressFill,
              strokeWidth = 2.dp,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

/** 单条历史胶囊：时钟图标 + 关键词；点击即搜，长按删除单条 */
@Composable
private fun CineHistoryChip(
  keyword: String,
  onPick: () -> Unit,
  onRemove: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = Modifier
      .height(34.dp)
      .clip(shape)
      .background(colors.chipSurface)
      .border(1.dp, colors.chipBorder, shape)
      .clickable(onClick = onPick)
      .padding(start = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = keyword,
      // Cine 字阶里没有 12.5sp 这一档；胶囊比卡片标题略小，直接落一个显式字号
      style = type.cardMeta.copy(fontSize = 12.5.sp),
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .widthIn(max = 168.dp)
        .defaultMinSize(minWidth = 48.dp)
        .padding(vertical = 8.dp),
    )
    Spacer(Modifier.width(8.dp))
    Box(
      modifier = Modifier
        .width(1.dp)
        .height(14.dp)
        .background(colors.chipBorder),
    )
    Box(
      modifier = Modifier
        .width(36.dp)
        .fillMaxHeight()
        .clickable(onClick = onRemove),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Outlined.Close,
        contentDescription = stringResource(R.string.cine_search_history_removed, keyword),
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(13.dp),
      )
    }
  }
}

/**
 * 推荐卡（16:9）：封面从下往上渐隐到页面底色，标题与元信息压在最底部。
 *
 * 为什么「渐隐到 surface」而不是渐隐到黑：
 * Cine 的页面底色本身就是近黑，渐到 surface 能让卡片与背景**无缝融合**（设计稿的要求），
 * 而纯黑在浅色主题下会变成一块突兀的黑斑。
 */
@Composable
private fun CineRecommendCard(
  item: EmbyItem,
  imageUrl: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = LocalCardCorner.current
  val shape = remember(corner) { RoundedCornerShape(corner) }
  val title = item.Name?.takeIf { it.isNotBlank() } ?: "未命名"
  val meta = recommendMeta(item)
  val hasImage = !imageUrl.isNullOrBlank()

  Box(
    modifier = modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .clip(shape)
      .background(colors.skeleton)
      .clickable(onClick = onClick),
  ) {
    if (hasImage) {
      app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage(
        url = imageUrl,
        contentDescription = title,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        maxWidth = 480,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              0f to androidx.compose.ui.graphics.Color.Transparent,
              0.42f to colors.surface.copy(alpha = 0.12f),
              0.78f to colors.surface.copy(alpha = 0.82f),
              1f to colors.surface,
            ),
          ),
      )
    }

    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(start = 10.dp, end = 10.dp, bottom = 7.dp),
      verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = colors.onSurface,
          modifier = Modifier.size(13.dp),
        )
        Text(
          text = title,
          style = type.itemTitle.copy(fontSize = type.posterTitle.fontSize),
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      Text(
        text = meta,
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** 推荐卡的一行（两列）。最后一行不满时补空位，避免剩下的卡片被拉宽 */
@Composable
private fun CineRecommendRow(
  items: List<EmbyItem>,
  imageUrl: (EmbyItem) -> String?,
  onClick: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = space.screenH),
    horizontalArrangement = Arrangement.spacedBy(space.itemGap),
  ) {
    items.forEach { item ->
      CineRecommendCard(
        item = item,
        imageUrl = imageUrl(item),
        onClick = { onClick(item) },
        modifier = Modifier.weight(1f),
      )
    }
    repeat((2 - items.size).coerceAtLeast(0)) {
      Spacer(modifier = Modifier.weight(1f))
    }
  }
}

/** 推荐骨架行 */
@Composable
private fun CineRecommendSkeletonRow() {
  val colors = CineTheme.colors
  val space = CineTheme.space
  val corner = LocalCardCorner.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = space.screenH),
    horizontalArrangement = Arrangement.spacedBy(space.itemGap),
  ) {
    repeat(2) {
      Box(
        modifier = Modifier
          .weight(1f)
          .aspectRatio(16f / 9f)
          .clip(RoundedCornerShape(corner))
          .background(colors.skeleton),
      )
    }
  }
}


/** 推荐卡的元信息：`2023 · 科幻 / 冒险`（与 Classic 同口径） */
private fun recommendMeta(item: EmbyItem): String {
  val year = item.ProductionYear?.toString()
  val genres = item.Genres
    .asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString(" / ")
    .takeUnless { it.isBlank() }
  val typeLabel = when (item.Type?.lowercase()) {
    "movie" -> "电影"
    "series" -> "剧集"
    "episode" -> "单集"
    "boxset" -> "合集"
    else -> item.Type?.takeIf { it.isNotBlank() }
  }
  return listOfNotNull(year, genres).joinToString(" · ").ifBlank { typeLabel.orEmpty() }
}
