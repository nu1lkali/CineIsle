package app.marlboroadvance.mpvex.ui.cine

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterCard
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import kotlinx.coroutines.delay

/** 搜索防抖：每敲一个字就发一次请求，对十万级库的服务端是灾难 */
private const val SEARCH_DEBOUNCE_MS = 320L

/** 单次搜索上限：与 Classic 的全库搜索一致（结果再多也只能往下翻，没有分页） */
private const val SEARCH_LIMIT = 60

/**
 * 全屏搜索页。
 *
 * ## 为什么是「全屏页」而不是首页顶部就地展开
 * 1. 首页的搜索胶囊压在 Hero 上，就地展开会把 Hero 的视觉重心抢走；
 * 2. 软键盘弹起（约 300dp）后 Hero 会被顶掉一半，滚动位置也跟着乱；
 * 3. 搜索结果需要网格空间 —— 在 Hero 下方挤几行卡片等于没有结果页。
 *
 * ## 数据
 * 复用 [EmbyViewModel.search]（同样的「演员 + 媒体」两路合并、同样的上限），
 * 所以 Cine 搜出来的和 Classic 搜出来的是同一批东西，只是排版不同。
 */
@Composable
fun CineSearchOverlay(
  onClose: () -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val keyboard = LocalSoftwareKeyboardController.current
  val play = rememberCinePlay()

  var query by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<CineMediaUi>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var lastTerm by remember { mutableStateOf("") }

  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  // 系统返回键先退搜索（搜索是叠在首页之上的一层）
  BackHandler(onBack = onClose)

  LaunchedEffect(query, server?.id) {
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
      }
      .onFailure { errorText = it.message }
    searching = false
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
      .background(colors.surface),
  ) {
    // ── 输入行 ──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(start = 6.dp, end = space.screenH, top = 6.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = null,
          tint = colors.onSurface,
          modifier = Modifier.size(20.dp),
        )
      }

      SearchField(
        query = query,
        onQueryChange = { query = it },
        focusRequester = focusRequester,
        onSearch = { keyboard?.hide() },
        modifier = Modifier.weight(1f),
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        query.isBlank() -> CineStatePanel(
          icon = Icons.Outlined.Search,
          title = stringResource(R.string.cine_search_prompt_title),
          description = stringResource(R.string.cine_search_prompt_desc),
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
            onOpenDetail(media.item)
          },
          // 键盘弹起时网格要跟着缩，否则最后一行永远在键盘下面
          modifier = Modifier.imePadding(),
        )
      }
    }
  }
}

@Composable
private fun SearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  focusRequester: FocusRequester,
  onSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = modifier
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
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
          contentDescription = null,
          tint = colors.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

/**
 * 「全部」网格页。
 *
 * 分区只露 4 张（宽屏）/ 横滑（手机），剩下的走这里 —— 不把「全部」做成死按钮。
 */
@Composable
fun CineGridOverlay(
  title: String,
  items: List<CineMediaUi>,
  onClose: () -> Unit,
  onOpenDetail: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space
  val colors = CineTheme.colors
  val type = CineTheme.type
  val play = rememberCinePlay()

  val wide = rememberCineWide()
  val columns = if (wide) 5 else 3
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth = (
    (screenWidth - space.screenH * 2 - space.itemGap * (columns - 1)) / columns
    ).coerceAtLeast(88.dp)

  BackHandler(onBack = onClose)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(start = 6.dp, end = space.screenH, top = 6.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = null,
          tint = colors.onSurface,
          modifier = Modifier.size(20.dp),
        )
      }
      Spacer(Modifier.width(4.dp))
      Text(
        text = title,
        style = type.sectionTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = items.size.toString(),
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }

    CineMediaGrid(
      items = items,
      cardWidth = cardWidth,
      columns = columns,
      onClick = { play(it.item, it.resumeSeconds) },
      onLongClick = { onOpenDetail(it.item) },
    )
  }
}

/**
 * 海报网格（搜索页与「全部」页共用）。
 *
 * 卡片宽度由调用方按列数算好传进来 —— 与横滑行里同样的 `CinePosterCard`，
 * 所以标题自适应、角标、进度条的表现完全一致，不会出现「网格里的卡片和首页不一样」。
 */
@Composable
private fun CineMediaGrid(
  items: List<CineMediaUi>,
  cardWidth: Dp,
  columns: Int,
  onClick: (CineMediaUi) -> Unit,
  onLongClick: (CineMediaUi) -> Unit,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space

  LazyVerticalGrid(
    columns = GridCells.Fixed(columns),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = space.screenH,
      end = space.screenH,
      top = 6.dp,
      bottom = space.contentBottomInset,
    ),
    horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    // 16 = 图下那一行元信息的行高 + 8dp 间距，少了它两行卡片会挤在一起
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    itemsIndexed(items) { _, media ->
      CinePosterCard(
        media = media,
        width = cardWidth,
        onClick = { onClick(media) },
        onLongClick = { onLongClick(media) },
      )
    }
  }
}
