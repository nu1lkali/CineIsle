package app.marlboroadvance.mpvex.ui.cine.person

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineSheetFrame
import app.marlboroadvance.mpvex.ui.cine.components.CineFilterChip
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterGrid
import app.marlboroadvance.mpvex.ui.cine.components.CineSearchField
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.toCineMedia
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.ScrollToTopOnOrderChange
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.random.Random

/**
 * Cine 版「演员 / 导演作品页」。
 *
 * ## 数据口径一个字没改
 * 作品列表仍是 `loadPersonItems`（按 PersonIds 全库查），收藏状态仍走
 * `loadPersonFavoriteState`（列表法打头，条目读取兜底）—— 这两条在兼容服务端上
 * 踩过的坑不再重踩。
 *
 * ## Cine 化的部分
 * - 顶栏玻璃条 + 头像圆章 + 身份/作品数
 * - 类型 Chip（全部 / 电影 / 剧集 / 单集）与身份 Chip（全部 / 演员 / 导演 / 编剧）
 *   合并成两行横滑，选中态走 `primaryContainer`
 * - 排序收进玻璃抽屉（名称 / 年份 / 评分 / 最新添加 / 随机），再点一次已选中的项反转方向
 * - 收藏红心钉在顶栏右侧，状态不等网络：进页面先用最小条目顶上，立刻可点
 */
@Composable
fun CinePersonScreen(
  personId: String,
  personName: String,
  personImageTag: String? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val scope = rememberCoroutineScope()
  val prefs = koinInject<BrowserPreferences>()
  val gridColumns by prefs.embyLibraryGridColumns.collectAsState()

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var personItem by remember { mutableStateOf<EmbyItem?>(null) }
  var hasLoaded by remember { mutableStateOf(false) }

  var typeTab by rememberSaveable { mutableStateOf(0) }
  var roleFilter by rememberSaveable { mutableStateOf(0) }
  var query by rememberSaveable { mutableStateOf("") }
  var searchOpen by remember { mutableStateOf(false) }
  var sortIndex by rememberSaveable { mutableStateOf(0) }
  var sortAsc by rememberSaveable { mutableStateOf(true) }
  var randomSeed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }
  var showSort by remember { mutableStateOf(false) }

  val typeTabs = remember { listOf("全部", "电影", "剧集", "单集") }
  val roleTabs = remember { listOf("全部", "演员", "导演", "编剧") }
  val sortOptions = remember { listOf("名称", "年份", "评分", "最新添加", "随机") }

  // 只把「确实观察到」的身份挂成 chip：只演戏的人不该看到「导演」这个点了必然空的筛选
  val availableRoles = remember(items, personId) {
    val observed = items.asSequence()
      .flatMap { it.People.orEmpty().asSequence() }
      .filter { it.Id == personId }
      .mapNotNull { it.Type?.lowercase() }
      .toSet()
    listOf(0, 1, 2, 3).filter { index ->
      if (observed.isEmpty()) {
        true
      } else {
        val apiType = when (index) {
          1 -> "actor"
          2 -> "director"
          3 -> "writer"
          else -> null
        }
        apiType == null || observed.contains(apiType)
      }
    }
  }
  LaunchedEffect(availableRoles) {
    if (roleFilter !in availableRoles) roleFilter = 0
  }

  suspend fun load() {
    val current = server ?: return
    loading = true
    error = null
    val list = viewModel.loadPersonItems(current, personId)
      .distinctBy { it.Id ?: it.Name ?: "" }
    items = list
    loading = false
    hasLoaded = true
    if (list.isEmpty()) error = "没有找到「$personName」的作品"
  }

  LaunchedEffect(personId, server?.id) {
    // 红心不等网络：先用最小条目顶上
    personItem = EmbyItem(Id = personId, Name = personName, Type = "Person")
    val current = server ?: return@LaunchedEffect
    val favorite = viewModel.loadPersonFavoriteState(current, personId)
    val loaded = if (favorite == null) viewModel.loadPersonById(current, personId) else null
    val base = loaded ?: EmbyItem(Id = personId, Name = personName, Type = "Person")
    personItem = when (favorite) {
      null -> base
      else -> base.copy(UserData = (base.UserData ?: EmbyUserData()).copy(IsFavorite = favorite))
    }
    load()
  }

  // 从详情页 / 合并工具页返回时刷新（组合恢复不会重跑上面的 effect）
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  androidx.compose.runtime.DisposableEffect(lifecycleOwner, personId, server?.id) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && hasLoaded) {
        scope.launch { load() }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val filtered = remember(items, typeTab, roleFilter, personId, query) {
    items
      .filter { item ->
        when (typeTab) {
          1 -> item.Type.equals("Movie", ignoreCase = true)
          2 -> item.Type.equals("Series", ignoreCase = true)
          3 -> item.Type.equals("Episode", ignoreCase = true)
          else -> true
        }
      }
      .filter { item ->
        val apiType = when (roleFilter) {
          1 -> "Actor"
          2 -> "Director"
          3 -> "Writer"
          else -> null
        }
        if (apiType == null) {
          true
        } else {
          val people = item.People ?: return@filter true
          val self = people.filter { it.Id == personId }
          self.isEmpty() || self.any { it.Type.equals(apiType, ignoreCase = true) }
        }
      }
      .filter { item ->
        query.isBlank() ||
          item.Name?.contains(query.trim(), ignoreCase = true) == true ||
          item.OriginalTitle?.contains(query.trim(), ignoreCase = true) == true
      }
  }

  val sorted = remember(filtered, sortIndex, sortAsc, randomSeed) {
    if (sortIndex == 4) {
      filtered.shuffled(Random(randomSeed))
    } else {
      val comparator: Comparator<EmbyItem> = when (sortIndex) {
        1 -> compareBy<EmbyItem, Int?>(nullsLast()) { it.ProductionYear }
        2 -> compareBy<EmbyItem, Double?>(nullsLast()) { it.CommunityRating }
        3 -> compareBy<EmbyItem, String?>(nullsLast()) { it.DateCreated }
        else -> compareBy<EmbyItem, String?>(
          nullsLast(String.CASE_INSENSITIVE_ORDER),
        ) { it.SortName ?: it.Name }
      }
      filtered.sortedWith(if (sortAsc) comparator else comparator.reversed())
    }
  }

  val gridState = rememberLazyGridState()
  // 换排序 / 反转 / 随机换一批就回顶 —— 统一走 ScrollToTopOnOrderChange
  // （内部 requestScrollToItem；直接 scrollToItem(0) 会被 LazyGrid 的 key 锚定抵掉，
  //  切随机后停在列表中间。规矩见 ui/utils/ScrollToTop.kt）。
  ScrollToTopOnOrderChange(
    key = Triple(sortIndex, sortAsc, randomSeed),
    gridState = gridState,
  )

  val mediaUi: List<CineMediaUi> = remember(server?.id, sorted) {
    val current = server ?: return@remember emptyList()
    sorted.map { viewModel.toCineMedia(current, it) }
  }

  val isFavorite = personItem?.UserData?.IsFavorite == true
  val avatarUrl = remember(server?.id, personImageTag) {
    val current = server ?: return@remember null
    val person = EmbyItem(
      Id = personId,
      Name = personName,
      Type = "Person",
      ImageTags = if (personImageTag != null) mapOf("Primary" to personImageTag) else emptyMap(),
    )
    viewModel.imageUrl(current, person, "Primary", 320)
  }

  // ── 头部区块：一句 lambda 三处复用（网格的 header 槽 / 加载态 / 空态）──
  //
  // v158 之前这一块钉在网格上方，一进页面就吃掉小半屏，海报只剩半屏可看。
  // 现在它的归属是**滚动内容**（`CinePosterGrid(header = ...)`），跟详情页 Hero
  // 一样「往上滚就让位」。
  //
  // ⚠️ 横向内边距由调用方通过 [hPad] 传：在网格里时网格自己已经有 `screenH` 的
  // contentPadding，这里再垫一次，头像与筛选就会比下面的海报多缩进一截。
  val headerContent: @Composable (Dp) -> Unit = { hPad ->
    Column(modifier = Modifier.fillMaxWidth()) {
      // ── 人物头部：头像 + 名字 + 身份 / 作品数 ──
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = hPad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(colors.skeleton),
        ) {
          EmbyImage(
            url = avatarUrl,
            contentDescription = personName,
            modifier = Modifier.fillMaxSize(),
            maxWidth = 320,
            contentScale = ContentScale.Crop,
          )
        }
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(start = 14.dp),
        ) {
          Text(
            text = personName,
            style = type.pageTitle.copy(fontSize = 22.sp),
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = buildString {
              append(if (availableRoles.size == 2) roleTabs[availableRoles.first { it != 0 }] else "人物")
              append(" · ${filtered.size} 部作品")
            },
            style = type.cardMeta,
            color = colors.onSurfaceVariant,
          )
        }
      }

      if (searchOpen) {
        CineSearchField(
          query = query,
          onQueryChange = { query = it },
          onClose = {
            searchOpen = false
            query = ""
          },
          placeholder = "搜索作品名 / 原名",
          modifier = Modifier.padding(horizontal = hPad),
        )
        Spacer(Modifier.height(10.dp))
      }

      // ── 类型 / 身份两行 Chip ──
      LazyRow(
        contentPadding = PaddingValues(horizontal = hPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(typeTabs.size) { index ->
          CineFilterChip(
            text = typeTabs[index],
            selected = typeTab == index,
            onClick = { typeTab = index },
          )
        }
      }
      if (availableRoles.size > 2) {
        Spacer(Modifier.height(8.dp))
        LazyRow(
          contentPadding = PaddingValues(horizontal = hPad),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(availableRoles) { index ->
            CineFilterChip(
              text = roleTabs[index],
              selected = roleFilter == index,
              onClick = { roleFilter = index },
            )
          }
        }
      }
      Spacer(Modifier.height(10.dp))
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = personName,
      onBack = { backStack.removeLastOrNull() },
      actions = {
        CineIconButton(
          icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
          contentDescription = if (isFavorite) "取消收藏" else "收藏",
          tint = if (isFavorite) colors.sectionAction else colors.onSurfaceVariant,
          onClick = {
            val current = server ?: return@CineIconButton
            val target = personItem ?: return@CineIconButton
            scope.launch {
              val result = viewModel.toggleFavorite(current, target, !isFavorite)
              result.getOrNull()?.let { nowFavorite ->
                personItem = target.copy(
                  UserData = (target.UserData ?: EmbyUserData()).copy(IsFavorite = nowFavorite),
                )
              }
              if (result.isFailure) {
                Toast.makeText(context, "操作失败，请稍后再试", Toast.LENGTH_SHORT).show()
              }
            }
          },
        )
        CineIconButton(
          icon = Icons.Outlined.Search,
          contentDescription = "搜索作品",
          tint = if (searchOpen) colors.sectionAction else colors.onSurfaceVariant,
          onClick = {
            searchOpen = !searchOpen
            if (!searchOpen) query = ""
          },
        )
        CineIconButton(
          icon = Icons.Filled.Shuffle,
          contentDescription = "排序与随机",
          tint = colors.onSurfaceVariant,
          onClick = { showSort = true },
        )
      },
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .cineHazeSource(LocalCineHaze.current),
    ) {
      // ⚠️ v158：头部（头像 / 搜索 / 两组筛选）不再是网格上方的固定区 ——
      // 整块搬进了 `CinePosterGrid` 的 header 槽，跟着网格一起滚走。
      // 改头部长什么样只改上面那个 `headerContent`，三处调用共用一份。

      when {
        // 加载 / 空态没有网格可挂，头部照旧贴顶（hPad 用 screenH 补回来）
        loading -> Column(modifier = Modifier.fillMaxSize()) {
          headerContent(space.screenH)
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              color = colors.progressFill,
              modifier = Modifier.size(30.dp),
              strokeWidth = 2.5.dp,
            )
          }
        }

        mediaUi.isEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
          headerContent(space.screenH)
          CineStatePanel(
            icon = Icons.Outlined.Search,
            title = if (query.isNotBlank()) "没有匹配的作品" else "没有作品",
            description = error ?: "换一个筛选条件试试",
            primaryLabel = "返回",
            onPrimary = { backStack.removeLastOrNull() },
          )
        }

        else -> CinePosterGrid(
          items = mediaUi,
          // 用设置里的列数：与媒体库页保持同一个密度口径
          cardWidth = (LocalContext.current.resources.displayMetrics.widthPixels /
            LocalContext.current.resources.displayMetrics.density / (gridColumns + 1)).dp,
          columns = gridColumns,
          state = gridState,
          contentPadding = PaddingValues(
            start = space.screenH,
            end = space.screenH,
            bottom = 34.dp,
          ),
          // 头部进网格（跨满整行）⇒ 滚动时让位给海报。hPad 传 0：
          // 横向留白已经由上面的 contentPadding 给了，再垫一次会比海报多缩进。
          header = { headerContent(0.dp) },
          onClick = { media ->
            val id = media.id
            if (id.isNotBlank()) {
              backStack.add(EmbyDetailScreen(itemId = id, title = media.title))
            }
          },
        )
      }
    }
  }

  if (showSort) {
    CineSheetFrame(
      title = "排序方式",
      onDismissRequest = { showSort = false },
      dismissLabel = "关闭",
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sortOptions.forEachIndexed { index, label ->
          val selected = index == sortIndex
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(if (selected) colors.chipSurface else colors.surface.copy(alpha = 0f))
              .clickable {
                when {
                  selected && index != 4 -> sortAsc = !sortAsc
                  selected -> randomSeed = Random.nextInt()
                  else -> {
                    sortIndex = index
                    sortAsc = index == 0
                  }
                }
                showSort = false
              }
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = label,
              style = type.itemTitle,
              color = if (selected) colors.sectionAction else colors.onSurface,
              modifier = Modifier.weight(1f),
            )
            if (selected) {
              Text(
                text = when {
                  index == 4 -> "再点换一批"
                  sortAsc -> "升序"
                  else -> "降序"
                },
                style = type.cardMeta,
                color = colors.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
