package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.ScrollToTopOnOrderChange
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random
import org.koin.compose.koinInject

/**
 * 演员 / 导演作品页：点详情页演职员头像进来，按 PersonIds 查 TA 参与过的条目。
 *
 * Emby 的 /Items 支持 PersonIds 过滤，直接用详情页拿到的 PersonId 查，结果精确。
 */
@Serializable
data class EmbyPersonScreen(
  val personId: String,
  val personName: String,
  /** 演员头像的 image tag；空则显示占位人形图标 */
  val personImageTag: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()
    val scope = rememberCoroutineScope()
    val browserPreferences = koinInject<BrowserPreferences>()
    val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 演员本人的条目（含 UserData.IsFavorite）：右上角收藏红心的状态来源。
    // 进页面查一次；收藏/取消收藏成功后本地翻转，不再回查服务器。
    var personItem by remember { mutableStateOf<EmbyItem?>(null) }
    // 是否完成过一次加载：ON_RESUME 刷新只在「已经加载过」时才做，避免首次进入重复拉取
    var hasLoaded by remember { mutableStateOf(false) }

    // ── 作品聚合 / 筛选（纯本地，切 tab 不发请求）──
    // ⚠️ 用 rememberSaveable：这两个页签是**纯本地切片**，旋转屏幕时本来就有能力恢复，
    // 用 remember 的话转个屏就跳回「全部」，用户会以为作品列表变了。
    var itemTab by rememberSaveable { mutableStateOf(PersonItemTab.ALL) }
    // 参与身份筛选：全部 / 演员 / 导演 / 编剧（按条目自带的 People 列表本地筛）
    var roleFilter by rememberSaveable { mutableStateOf(PersonRoleFilter.ALL) }

    // ── 搜索 / 排序（纯本地，不发请求）──
    // 本页作品是**一次性拉全**的，所以本地搜索能覆盖全部作品，搜不到就是真没有。
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 排序选择与方向都**落盘**：从作品页点进详情页再返回时组合状态会重建，
    // 只放在组合里的话就被打回默认排序（方向悄悄翻转比整体重置更让人困惑）。
    var sort by remember {
      mutableStateOf(
        PersonSort.entries.firstOrNull { it.name == browserPreferences.embyPersonSort.get() }
          ?: PersonSort.NAME,
      )
    }
    var sortAsc by remember { mutableStateOf(browserPreferences.embyPersonSortAsc.get()) }
    // ⚠️ 随机**不能每次重组都重洗**：敲一个搜索字、切一次筛选都会触发重组，
    // 直接 shuffled() 的话列表会在手指底下不停跳。种子只在「切到随机 / 再点一次」时变。
    var randomSeed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }

    // 过滤与排序分开两步、各自 remember：敲搜索词不白跑排序，换排序也不白跑过滤。
    val filteredItems = remember(items, itemTab, roleFilter, personId, searchQuery) {
      items
        .filter { itemTab.matches(it) && roleFilter.matches(it, personId) }
        .filter { searchQuery.isBlank() || it.matchesKeyword(searchQuery.trim()) }
    }
    val visibleItems = remember(filteredItems, sort, sortAsc, randomSeed) {
      sort.apply(filteredItems, sortAsc, randomSeed)
    }

    val gridState = rememberLazyGridState()
    // 换了排序就回到第一条：翻到第一百部再切排序，视口还停在原处的话看到的完全是
    // 另一批内容，会让人以为「排序没生效」。与媒体库页「排序一变就回顶」同一套行为。
    // ⚠️ 统一走 ScrollToTopOnOrderChange（内部 requestScrollToItem），直接 scrollToItem(0)
    //    会被 LazyGrid 的 key 锚定抵掉 —— 见 ui/utils/ScrollToTop.kt。
    ScrollToTopOnOrderChange(
      key = Triple(sort, sortAsc, randomSeed),
      gridState = gridState,
    )

    // 「参与身份」只在**确实观察到**该身份时才提供对应 chip：只演戏的人再挂
    // 「导演 / 编剧」chip，点下去永远是空。完全拿不到 People 数据时（部分服务端
    // 不返回该字段）退回「全部显示」，与 PersonRoleFilter.matches 的宽松口径一致。
    val availableRoles =
      remember(items, personId) {
        val observed =
          items.asSequence()
            .flatMap { it.People.orEmpty().asSequence() }
            .filter { it.Id == personId }
            .mapNotNull { it.Type?.lowercase() }
            .toSet()
        PersonRoleFilter.entries.filter { role ->
          if (observed.isEmpty()) {
            true
          } else {
            role.apiType == null || observed.contains(role.apiType.lowercase())
          }
        }
      }
    // 选中的身份若因数据变化不再可用，自动落回「全部」—— 否则会停在一个已隐藏的
    // 筛选上，看到一片空白。
    LaunchedEffect(availableRoles) {
      if (roleFilter !in availableRoles) roleFilter = PersonRoleFilter.ALL
    }

    // 界面上的称呼跟着 TA 真实拥有的身份走：只导不演的人不该被叫「演员」。
    // 多身份或拿不到 People 时退回中性的「人物」。
    val personLabel =
      remember(availableRoles) {
        val concrete = availableRoles.filter { it != PersonRoleFilter.ALL }
        if (concrete.size == 1) concrete[0].label else "人物"
      }

    /**
     * 随机播放：把**当前筛选出的**列表打乱后丢给主播放器排队连播。
     *
     * 取「当前可见列表」而不是全量：用户筛了「只看电影」或某个身份，随机播放就该只
     * 在这些里抽。随机出的列表里常混着看过的剧，所以每条都从头放。
     */
    fun startRandomPlayback() {
      val current = server ?: return
      val limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500)
      val pool = visibleItems.shuffled().take(limit)
      if (pool.isEmpty()) {
        Toast.makeText(context, "当前筛选下没有可播放的作品", Toast.LENGTH_SHORT).show()
        return
      }
      viewModel.launchRandomPlayback(current, pool)
    }

    suspend fun load() {
      val current = server
      if (current == null) return
      isLoading = true
      error = null
      // 网格 key = Id ?: Name：按同一口径去重，防止服务端重复条目把网格撞崩
      val list = viewModel.loadPersonItems(current, personId).distinctBy { it.Id ?: it.Name ?: "" }
      items = list
      isLoading = false
      hasLoaded = true
      if (list.isEmpty()) error = "没有找到「$personName」的作品"
    }

    LaunchedEffect(personId, server) {
      // ⚠️ 第一件事：用最小条目把 personItem 顶上，红心**不等任何网络请求立即可点**。
      // UserData 未知时按「未收藏」处理：此时点击发的是「收藏」POST，对已收藏的人是幂等的。
      personItem = EmbyItem(Id = personId, Name = personName, Type = "Person")
      val current = server ?: return@LaunchedEffect

      // ── 收藏状态：**先读状态，再拉作品** ──
      // ⚠️ 不能把读收藏状态挂在 loadPersonById 的非空返回后面：它的三条路径在这类
      // 兼容服务端上对演员会全部失败（/Persons?Ids 不被支持、/Users/{uid}/Items/{id}
      // 可能 500），一旦提前 return，唯一可靠的收藏列表法一次都不会执行，红心永远灰。
      // 列表法打头，条目读取降级为兜底。
      val fav = viewModel.loadPersonFavoriteState(current, personId)
      // 列表法也给不出结论时，才退回按 Id 读条目（它可能自带 UserData）
      val loaded = if (fav == null) viewModel.loadPersonById(current, personId) else null
      val base = loaded ?: EmbyItem(Id = personId, Name = personName, Type = "Person")
      personItem = when (fav) {
        null -> base
        else -> base.copy(UserData = (base.UserData ?: EmbyUserData()).copy(IsFavorite = fav))
      }

      // 作品列表放最后：高产人物要翻好几页，别让它挡着红心出状态
      load()
    }

    // 回到本页时刷新列表：从「演职员合并」工具页 / 详情页返回时本页只是从返回栈恢复，
    // 上面的 LaunchedEffect 不会重跑 —— 不重拉的话看到的还是离开前的旧数据。
    val personLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(personLifecycleOwner, personId, server) {
      val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && hasLoaded) {
          scope.launch { load() }
        }
      }
      personLifecycleOwner.lifecycle.addObserver(observer)
      onDispose { personLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 头部区块：头像行 / 两组筛选 / 搜索 + 排序 ──
    //
    // v158：这一整块**不再钉在网格上方**，而是作为网格的第一个条目（跨满整行）
    // 跟着海报一起滚走 —— 原先它一进页面就吃掉小半屏，用户滚不起来就只能看
    // 半屏海报。与媒体详情页「Hero 往上滚就让位」是同一种手感。
    //
    // ⚠️ 横向内边距由 [hPad] 传入，不能写死：在网格里时网格自己已经有 12dp 的
    // contentPadding，这里再垫 16dp 会比下面的海报多缩进一截。
    // 加载 / 空态没有网格可挂，才用 16dp 贴边。
    val headerContent: @Composable (Dp) -> Unit = { hPad ->
      Column(modifier = Modifier.fillMaxWidth()) {
        val avatarUrl = remember(personId, personImageTag, server) {
          server?.let { EmbyClient.imageUrl(it, personId, "Primary", personImageTag, 300) }
        }
        // ── 演员头像 + 名字 ──
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = hPad, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(72.dp),
          ) {
            EmbyImage(
              url = avatarUrl,
              contentDescription = personName,
              modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
              maxWidth = 300,
              placeholder = Icons.Default.Person,
            )
          }
          Spacer(modifier = Modifier.width(16.dp))
          Column {
            Text(
              text = personName,
              style = MaterialTheme.typography.titleLarge,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.longPressToCopy(personName, "姓名"),
            )
            Text(
              text = if (isLoading) "加载中…" else "共 ${visibleItems.size} 部作品",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 人物 ID：排查「同名分裂成多条 Person」问题时最有用的信息，长按复制。
            // 前缀用 personLabel（演员 / 导演 / 编剧 / 人物），不写死成「演员」。
            Text(
              text = "$personLabel ID：$personId",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.longPressToCopy(personId, "$personLabel ID"),
            )
          }
        }

        // 类型 / 身份两组筛选 chips：只过滤本页已加载的数据，切 tab 不发请求
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          FilterChipRow(
            options = PersonItemTab.entries,
            selected = itemTab,
            label = { it.label },
            onSelect = { itemTab = it },
            hPad = hPad,
          )
          FilterChipRow(
            // 只列出这个人真实拥有的身份（见 availableRoles）：只演戏的人不再出现「导演 / 编剧」
            options = availableRoles,
            selected = roleFilter,
            label = { it.label },
            onSelect = { roleFilter = it },
            hPad = hPad,
          )
        }

        // ── 搜索 + 排序 ──
        PersonSearchSortRow(
          query = searchQuery,
          onQueryChange = { searchQuery = it },
          sort = sort,
          asc = sortAsc,
          onSortChange = { newSort, newAsc ->
            sort = newSort
            sortAsc = newAsc
            browserPreferences.embyPersonSort.set(newSort.name)
            browserPreferences.embyPersonSortAsc.set(newAsc)
            // 切到随机时给一个新种子：不然沿用旧种子洗出来的还是看得眼熟的那一批
            if (newSort == PersonSort.RANDOM) randomSeed = Random.nextInt()
          },
          onReshuffle = { randomSeed = Random.nextInt() },
          hPad = hPad,
        )
      }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Text(
            text = personName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.longPressToCopy(personName, "姓名"),
          )
        },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
            )
          }
        },
        actions = {
          // ── 随机播放这位演员 / 导演的作品（在当前筛选范围内打乱连播）──
          IconButton(
            enabled = visibleItems.isNotEmpty(),
            onClick = { startRandomPlayback() },
          ) {
            Icon(
              imageVector = Icons.Default.Shuffle,
              contentDescription = "随机播放作品",
            )
          }
          // ── 右上角收藏红心 ──
          // 与详情页同一套交互：乐观更新红心 → 请求服务器 → 按结果修正并弹 Toast。
          val isFavorite = personItem?.UserData?.IsFavorite == true
          IconButton(
            enabled = personItem != null,
            onClick = {
              val current = personItem ?: return@IconButton
              val currentServer = server ?: return@IconButton
              val wasFavorite = current.UserData?.IsFavorite == true
              // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
              personItem = current.copy(
                UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = !wasFavorite),
              )
              scope.launch {
                val result = viewModel.toggleFavorite(currentServer, current)
                val nowFavorite = result.getOrNull()
                personItem = current.copy(
                  UserData = (current.UserData ?: EmbyUserData())
                    .copy(IsFavorite = nowFavorite ?: wasFavorite),
                )
                Toast.makeText(
                  context,
                  when (nowFavorite) {
                    true -> "已收藏「$personName」"
                    false -> "已取消收藏「$personName」"
                    null -> "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                  },
                  Toast.LENGTH_SHORT,
                ).show()
              }
            },
          ) {
            // 与媒体详情页同款：弹跳 + 星光 + 红心渐变；尺寸对齐详情页的 24dp
            FavoriteHeartIcon(
              isFavorite = isFavorite,
              isToggling = false,
              iconSize = 24.dp,
              idleColor = LocalContentColor.current,
            )
          }
        },
      )

      // 头部的三块（头像行 / 两组筛选 / 搜索排序）已整体上移成 `headerContent`，
      // 由网格作为第一个条目渲染 —— 这里不再固定占位（见 v158 说明）。

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          isLoading && items.isEmpty() -> {
            // 骨架屏没有网格可挂，头部贴顶显示（hPad = 16dp）
            Column(modifier = Modifier.fillMaxSize()) {
              headerContent(16.dp)
              EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)
            }
          }

          error != null && items.isEmpty() -> {
            // 出错也要看得见是谁、能返回 —— 头部照旧贴顶（hPad = 16dp）
            Column(modifier = Modifier.fillMaxSize()) {
              headerContent(16.dp)
              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = error ?: "",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(24.dp),
                )
              }
            }
          }

          // 有数据时一律进网格分支：被筛空也**保持网格挂载**，只在上层叠一句提示
          else -> {
            LazyVerticalGrid(
              state = gridState,
              // 列数跟随「每行个数」设置（与媒体库「演员」分类、收藏页同一套规格）
              columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              // 头部作为第一个条目（跨满整行）：跟着海报一起滚走，不再固定吃小半屏。
              // hPad 传 0 —— 横向 12dp 已由 contentPadding 给了，与海报对齐。
              item(span = { GridItemSpan(maxLineSpan) }) { headerContent(0.dp) }
              items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                EmbyMediaCard(
                  title = item.Name ?: "",
                  subtitle = item.ProductionYear?.toString(),
                  imageUrl = server?.let { viewModel.imageUrl(it, item, "Primary", 400) },
                  progress = null,
                  isFavorite = item.UserData?.IsFavorite == true,
                  fillWidth = true,
                  modifier = Modifier.animateItem(),
                  onClick = {
                    item.Id?.let { id ->
                      backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
                    }
                  },
                )
              }
            }

            // ── 空态：叠在网格**上层**，网格照常挂载 ──
            // ⚠️ 不能与网格二选一：中间态筛出 0 条就把网格卸载，再敲一个字又整体重建
            // ⇒ 卡片全部重新组合、封面全部重新加载 ⇒「搜索框下面的封面闪一下」。
            // 搜索词命中不到时把词说出来，能让人一眼看出是自己的词有问题。
            if (visibleItems.isEmpty() && !isLoading) {
              val keyword = searchQuery.trim()
              Text(
                text = if (keyword.isEmpty()) {
                  "这个筛选条件下没有作品"
                } else {
                  "没有匹配「$keyword」的作品"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                  .align(Alignment.Center)
                  .padding(24.dp),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 作品类型聚合 tab（本地过滤，不改变服务器查询）。
 *
 * 演员作品清单里 Movie / Series / Episode 常常混在一起（尤其是剧集演员），
 * 所以按 Type 拆成几个 tab，用户想只看电影或只看剧集时一键切换。
 */
private enum class PersonItemTab(val label: String) {
  ALL("全部"),
  MOVIE("电影"),
  SERIES("剧集"),
  EPISODE("单集"),
  ;

  fun matches(item: EmbyItem): Boolean = when (this) {
    ALL -> true
    MOVIE -> item.Type.equals("Movie", ignoreCase = true)
    SERIES -> item.Type.equals("Series", ignoreCase = true)
    EPISODE -> item.Type.equals("Episode", ignoreCase = true)
  }
}

/**
 * 「参与身份」筛选：同一部片里这个人可能是主演、也可能是导演 / 编剧，
 * 按 [apiType]（Emby 的 People.Type 英文值）过滤条目的 People 列表。
 *
 * ⚠️ People 缺失时**不过滤**（返回 true）：服务端没返回 People 字段的情况真实存在，
 * 若此时一律判 false，用户切到「导演」会看到空白页、误以为没有作品。
 */
private enum class PersonRoleFilter(val label: String, val apiType: String?) {
  ALL("全部", null),
  ACTOR("演员", "Actor"),
  DIRECTOR("导演", "Director"),
  WRITER("编剧", "Writer"),
  ;

  fun matches(item: EmbyItem, personId: String): Boolean {
    val type = apiType ?: return true
    val people = item.People ?: return true
    val self = people.filter { it.Id == personId }
    if (self.isEmpty()) return true
    return self.any { it.Type.equals(type, ignoreCase = true) }
  }
}

/**
 * 作品列表的排序方式（本地排序，不发请求）。
 *
 * 与收藏页的 `FavoriteSort` 同一套口径：选「名称」时用 [EmbyItem.SortName]（已剥离
 * "The/A" 这类前缀）而不是 Name —— 服务端返回的顺序本来就是按 SortName 排的。
 *
 * 方向由调用方传入 [apply]，菜单里**再点一次已选中的项就反转方向**。
 */
private enum class PersonSort(
  val label: String,
  /** 升序时的方向提示 */
  val ascHint: String,
  /** 降序时的方向提示 */
  val descHint: String,
  /**
   * 没手动切过方向时的自然方向：名称升序，其余（年份 / 评分 / 添加时间）降序。
   * 与媒体库页同一套规则 —— 两边不一致会出现「库里按名称升序、这里按名称降序」。
   */
  val naturalAsc: Boolean,
) {
  NAME("名称", "A → Z", "Z → A", naturalAsc = true),
  YEAR("年份", "旧 → 新", "新 → 旧", naturalAsc = false),
  RATING("评分", "低 → 高", "高 → 低", naturalAsc = false),
  ADDED("最新添加", "旧 → 新", "新 → 旧", naturalAsc = false),
  // 随机：不分升降序（那两个箭头对它没意义），**再点一次就换一批**（重新洗牌）
  RANDOM("随机", "再点一次换一批", "再点一次换一批", naturalAsc = true),
  ;

  /** 是否带方向（随机没有） */
  val hasDirection: Boolean get() = this != RANDOM

  fun apply(list: List<EmbyItem>, asc: Boolean, seed: Int = 0): List<EmbyItem> = when (this) {
    // ⚠️ 随机必须用**固定种子**：直接 shuffled() 会在每次重组时洗出不同顺序，列表会在手指底下乱跳
    RANDOM -> list.shuffled(Random(seed))
    NAME -> sortByKey(list, asc) { (it.SortName ?: it.Name)?.lowercase() }
    YEAR -> sortByKey(list, asc) { it.ProductionYear }
    RATING -> sortByKey(list, asc) { it.CommunityRating }
    // DateCreated 是 ISO-8601 串，字典序即时间序（同格式下成立）
    ADDED -> sortByKey(list, asc) { it.DateCreated }
  }
}

/**
 * 按可空键排序，**键值缺失的条目一律沉底**（无论升 / 降序）。
 *
 * 沉底而不是当 0 处理：用 `?: 0` 兜底的话，没有年份的片子会挤在「0 年」那一档，
 * 选升序时它们会全顶在最前面 —— 比排在最后更像「排序坏了」。这也是为什么不能简单
 * `sortedWith(cmp.reversed())`：反转会把「无值组」一起翻到最前面。
 */
private inline fun <T : Comparable<T>> sortByKey(
  list: List<EmbyItem>,
  asc: Boolean,
  crossinline key: (EmbyItem) -> T?,
): List<EmbyItem> {
  val (withKey, withoutKey) = list.partition { key(it) != null }
  val comparator = compareBy(key)
  // sortedWith 是稳定排序：键值相同的条目保持原相对顺序（同年份的片子不会莫名换位）
  val sorted = if (asc) withKey.sortedWith(comparator) else withKey.sortedWith(comparator.reversed())
  return sorted + withoutKey
}

/** 搜索关键字匹配：**片名 + 原名**，支持「用英文原名搜中文标题」。 */
private fun EmbyItem.matchesKeyword(keyword: String): Boolean =
  Name?.contains(keyword, ignoreCase = true) == true ||
    OriginalTitle?.contains(keyword, ignoreCase = true) == true

/**
 * 作品列表顶部的「搜索框 + 排序」一行（与收藏页的 `FavoriteSearchSortRow` 同款版式）。
 *
 * 搜索与排序都是**纯本地、不打服务器**：本页作品是一次性拉全的，过滤 / 重排零成本。
 */
@Composable
private fun PersonSearchSortRow(
  query: String,
  onQueryChange: (String) -> Unit,
  sort: PersonSort,
  asc: Boolean,
  onSortChange: (PersonSort, Boolean) -> Unit,
  onReshuffle: () -> Unit,
  /** 同 [FilterChipRow]：放进网格时传 0，贴顶时用 16dp */
  hPad: Dp = 16.dp,
) {
  var sortMenu by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = hPad, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      modifier = Modifier.weight(1f),
      singleLine = true,
      shape = MaterialTheme.shapes.large,
      placeholder = { Text("搜索作品") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      trailingIcon = {
        if (query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) {
            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
          }
        }
      },
    )
    Spacer(modifier = Modifier.width(8.dp))
    Box {
      // 触发区直接标出「当前按什么排序」，不用点开就知道现在排的是什么
      Surface(
        modifier = Modifier.clickable {
          // ⚠️ 展开前先收掉输入框焦点：菜单关闭时会把焦点「还」给它，每切一次排序弹一次软键盘
          focusManager.clearFocus()
          sortMenu = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Sort,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(text = sort.label, style = MaterialTheme.typography.labelLarge)
          // 触发器上也带方向：不看菜单就知道现在是升还是降（随机没有方向，给骰子图标）
          Icon(
            imageVector = if (!sort.hasDirection) {
              Icons.Default.Shuffle
            } else if (asc) {
              Icons.Default.ArrowUpward
            } else {
              Icons.Default.ArrowDownward
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
        PersonSort.entries.forEach { option ->
          val selected = option == sort
          DropdownMenuItem(
            text = {
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                  // 选中的那一项**直接标出当前方向**（↑ 升 / ↓ 降），不单列「升序 / 降序」两个选项
                  text = buildString {
                      append(option.label)
                      if (selected && option.hasDirection) {
                        append(if (asc) " ↑" else " ↓")
                      }
                    },
                    color = if (selected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                  )
                }
                // 方向副标题：选中项显示当前方向，未选中项预告「点下去会变成哪个方向」
                Text(
                  text = if (!option.hasDirection) {
                    option.ascHint
                  } else if (selected) {
                    if (asc) option.ascHint else option.descHint
                  } else {
                    if (option.naturalAsc) option.ascHint else option.descHint
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            },
            onClick = {
              sortMenu = false
              when {
                // 再点一次已选中的项 = 只反转方向（随机则是换一批），不换排序项
                selected && option.hasDirection -> onSortChange(option, !asc)
                selected -> onReshuffle()
                // 换排序项：方向重置为该项的自然方向
                else -> onSortChange(option, option.naturalAsc)
              }
            },
          )
        }
      }
    }
  }
}

/** 一行可横向滚动的单选 FilterChip，类型 / 身份两组筛选共用。 */
@Composable
private fun <T> FilterChipRow(
  options: List<T>,
  selected: T,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  /** 横向内边距：放进网格时传 0（网格已有 contentPadding），贴顶时用 16dp */
  hPad: Dp = 16.dp,
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = hPad),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    items(options) { option ->
      FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(label(option)) },
      )
    }
  }
}
