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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random
import org.koin.compose.koinInject

/**
 * 演员 / 导演作品页：点详情页演职员头像进来，按 PersonIds 查 TA 参与过的条目。
 *
 * Emby 的 /Items 支持 PersonIds 过滤，所以这里不需要额外的「按名字搜」逻辑，
 * 直接用详情页拿到的 PersonId 查，结果精确。
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
    // 随机播放数量上限读用户设置，与媒体库的随机按钮同一口径
    val browserPreferences = koinInject<BrowserPreferences>()
    // 每行个数与媒体库同源（2~6）：原先写死 3 列，用户把媒体库调到 5 列后
    // 点演员进来会突变回 3 列，正是「与其它入口对不上」的那类观感问题。
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
    // 作品类型 tab：全部 / 电影 / 剧集 / 单集（按条目 Type 聚合）
    // ⚠️ 用 rememberSaveable 而不是 remember：这两个页签是**纯本地切片**（不发请求），
    // 旋转屏幕时它们本来完全有能力恢复。用 remember 的话转个屏就跳回「全部」，
    // 用户会以为作品列表变了。两个枚举都能被 Saver 自动存下（枚举有 name）。
    var itemTab by rememberSaveable { mutableStateOf(PersonItemTab.ALL) }
    // 参与身份筛选：全部 / 演员 / 导演 / 编剧（按条目自带的 People 列表本地筛）
    var roleFilter by rememberSaveable { mutableStateOf(PersonRoleFilter.ALL) }

    // ── 搜索 / 排序（与收藏页同一套：纯本地，不发请求）──
    // 本页作品是**一次性拉全**的（见 [loadPersonItems] 的翻页循环），所以本地搜索能覆盖全部作品
    // —— 不像收藏页那样存在「只搜到已翻到的页」的问题，这里搜不到就是真没有。
    // 用 rememberSaveable：旋转屏幕后输入框里打的字不该丢。
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 排序选择**落盘**：从作品页点进详情页再返回时，本页的组合状态会重建，
    // 只放在组合里的话就被打回默认排序（收藏页的排序是同一套考虑）。
    var sort by remember {
      mutableStateOf(
        PersonSort.entries.firstOrNull { it.name == browserPreferences.embyPersonSort.get() }
          ?: PersonSort.NAME,
      )
    }
    // 排序方向（true = 升序）同样落盘：只存排序项的话，返回后「名称 ↓」会变成「名称 ↑」，
    // 同一屏上方向悄悄翻转比整体重置更让人困惑。
    var sortAsc by remember { mutableStateOf(browserPreferences.embyPersonSortAsc.get()) }
    // ── 随机排序的种子 ──
    // ⚠️ 随机**不能每次重组都重洗**：搜索框敲一个字、切一次筛选都会触发重组，
    // 若直接 `shuffled()`，列表会在手指底下不停跳动（看着就是列表自己在抽搐）。
    // 种子只在「切到随机 / 再点一次随机」时变，其余时刻顺序是稳定的。
    // 用 rememberSaveable：旋转屏幕后不该换一批顺序。
    var randomSeed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }

    // 过滤与排序分开两步、各自 remember：过滤只跟筛选条件有关，排序只跟排序设置有关，
    // 于是「敲搜索词」不会白跑排序、「换排序」也不会白跑过滤。
    val filteredItems = remember(items, itemTab, roleFilter, personId, searchQuery) {
      items
        .filter { itemTab.matches(it) && roleFilter.matches(it, personId) }
        .filter { searchQuery.isBlank() || it.matchesKeyword(searchQuery.trim()) }
    }
    val visibleItems = remember(filteredItems, sort, sortAsc, randomSeed) {
      sort.apply(filteredItems, sortAsc, randomSeed)
    }

    // 「参与身份」只在**确实观察到**该身份时才提供对应 chip：
    // 一个只演戏、从不导也不写的人，作品页再挂「导演 / 编剧」两个 chip，点下去永远是空 ——
    // 既没用又让人以为数据缺了。这里根据已加载作品的 People 列表算出 TA 真实拥有的身份。
    // ⚠️ 完全拿不到 People 数据时（部分服务端不返回该字段）退回「全部显示」，
    // 与 PersonRoleFilter.matches 的宽松口径一致：不确定就都给，不制造假空白。
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
    // 选中的身份若因数据变化不再可用（例如只演不导的人不再显示「导演」chip），
    // 自动落回「全部」—— 否则会停在一个已隐藏的筛选上，看到一片空白。
    LaunchedEffect(availableRoles) {
      if (roleFilter !in availableRoles) roleFilter = PersonRoleFilter.ALL
    }

    // 本页是「演员 / 导演 / 编剧」共用的，界面上的称呼要跟着 TA 真实拥有的身份走：
    // 只导不演的人不该被叫「演员」。多身份或拿不到 People 时退回中性的「人物」。
    val personLabel =
      remember(availableRoles) {
        val concrete = availableRoles.filter { it != PersonRoleFilter.ALL }
        if (concrete.size == 1) concrete[0].label else "人物"
      }

    /**
     * 随机播放这位演员 / 导演的作品：把**当前筛选出的**列表打乱后丢给主播放器排队连播。
     *
     * 取「当前可见列表」而不是全量：用户筛了「只看电影」或某个身份，随机播放就该只在这些
     * 里抽，否则点下去放出的是被筛掉的内容，跟眼前对不上。数量上限读用户设置（与媒体库的
     * 随机按钮同一口径）。随机出的列表里常混着看过的剧，所以每条都从头放
     * （playFromStartAll = true），避免切集时恢复进度直接跳到片尾。
     */
    fun startRandomPlayback() {
      val current = server ?: return
      val limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500)
      val pool = visibleItems.shuffled().take(limit)
      if (pool.isEmpty()) {
        Toast.makeText(context, "当前筛选下没有可播放的作品", Toast.LENGTH_SHORT).show()
        return
      }
      viewModel.launchPlaylist(current, pool, playFromStartAll = true)
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
      // 红心是 `enabled = personItem != null`，赋值晚一步，用户看到的就是「置灰点不了」。
      // UserData 未知时按「未收藏」处理：此时点击发的是「收藏」POST，对已收藏的人
      // 是幂等的，随后的回读会给出真实状态，语义无损。
      personItem = EmbyItem(Id = personId, Name = personName, Type = "Person")
      val current = server ?: return@LaunchedEffect

      // ── 收藏状态：**先读状态，再拉作品** ──
      // ⚠️⚠️ 旧写法是 `val loaded = loadPersonById(...) ?: return@LaunchedEffect`，
      // 把「读收藏状态」挂在 loadPersonById 的非空返回值后面。而 loadPersonById 的三条路径
      // 在这类兼容服务端上对**演员会全部失败**：`/Persons?Ids` 不被支持（永远返回空）、
      // `/Users/{uid}/Items/{id}` 卡满 30 秒后回 HTTP 500（实测 1114461 就是）。
      // 于是它返回 null → 整个 LaunchedEffect 提前 return → **唯一可靠的收藏列表法
      // 一次都没执行** → 红心永远是灰的。而导演恰好走通了单条读（40ms 带 UserData），
      // 所以现象是「导演页红心正常、演员页红心置灰」。
      // 修法：收藏状态独立读、不再以条目读取为前提；列表法（54ms）打头，条目读取降级为兜底。
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

    // 回到本页时刷新列表：从「演职员合并」工具页 / 详情页返回时，本页只是从返回栈恢复，
    // 上面的 LaunchedEffect 不会重跑 —— 合并把作品挪给保留项之后不重拉的话，
    // 看到的还是离开前的旧数据（「明明合并了却还是旧的」多半是这个）。
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
          // 与详情页同一套交互：乐观更新红心 → 请求服务器 → 按结果修正并弹 Toast；
          // 动效复用 FavoriteHeartIcon（弹跳 + 星光 + 红心渐变）。
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
            // 与媒体详情页同款：弹跳 + 星光 + 红心渐变（状态驱动，点击时先乐观更新）
            // 尺寸对齐详情页的 24dp，避免「顶栏这颗比详情页小一圈」的观感落差
            FavoriteHeartIcon(
              isFavorite = isFavorite,
              isToggling = false,
              iconSize = 24.dp,
              idleColor = LocalContentColor.current,
            )
          }
        },
      )

      // ── 头部：演员头像 + 名字 ──
      val avatarUrl = remember(personId, personImageTag, server) {
        server?.let { EmbyClient.imageUrl(it, personId, "Primary", personImageTag, 300) }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
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
          // 人物 ID：排查「同名分裂成多条 Person」问题时最有用的信息 ——
          // 服务器上看是同一个人，App 里可能是两个不同的 Id。长按复制。
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
        )
        FilterChipRow(
          // 只列出这个人真实拥有的身份（见 availableRoles）：只演戏的人不再出现「导演 / 编剧」
          options = availableRoles,
          selected = roleFilter,
          label = { it.label },
          onSelect = { roleFilter = it },
        )
      }

      // ── 搜索 + 排序 ──
      // 与收藏页的 FavoriteSearchSortRow 同一套版式/口径：左边即时搜索、右边当前排序 + 下拉。
      // 高产演员能有几百部作品，只能靠这两样在海里捞片。
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
      )

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          isLoading && items.isEmpty() -> {
            // 一行三列海报卡，和下面真实结果的版式对齐
            EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)
          }

          error != null && items.isEmpty() -> {
            Text(
              text = error ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            )
          }

          // 有数据时一律进网格分支：被筛空（搜索词 / 类型 / 身份）也**保持网格挂载**，
          // 只在上层叠一句提示（见网格后面那段）—— 见下面空态注释里为什么要这么做。
          else -> {
            LazyVerticalGrid(
              // 与媒体库「演员」分类、收藏页同一套规格：列数跟随「每行个数」设置
              // （原先用 Adaptive(110dp)，与其它入口的卡片大小对不上，用户反馈已统一）
              columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
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
            // ⚠️ 原来是 when 的第三个分支、和网格二选一：只要中间态筛出 0 条，
            // 整个 LazyVerticalGrid 就被卸载，再敲一个字（或删一个字）又整体重建，
            // 于是所有卡片重新组合、封面全部重新走一遍加载 —— 观感就是
            // 「搜索框下面的封面闪一下」（用户反馈）。网格常驻后，筛空 → 筛出结果
            // 只是列表内容在变，已经在屏上的卡片留在原槽位，不会再整批重来。
            // 搜索词命中不到时把词说出来 —— 「没有匹配『xxx』的作品」比笼统的
            // 「没有作品」能让人一眼看出是自己的词有问题，而不是功能坏了。
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
 * 宁可宽松（多显示几条），也不要制造「筛选后没东西」的假象。
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
 * 与收藏页的 `FavoriteSort` 同一套口径：选「名称」时用 [EmbyItem.SortName]（Emby 内部的
 * 排序名，已剥离 "The/A" 这类前缀）而不是 Name —— 服务端返回的顺序本来就是按 SortName 排的，
 * 本地沿用同一个键才不会「切一下排序突然乱序」。
 *
 * 方向（升 / 降）由调用方传入 [apply]，菜单里**再点一次已选中的项就反转方向**
 * （与媒体库页 / 首页的排序菜单同一套交互，不单列「升序 / 降序」两个选项）。
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
    // ⚠️ 随机必须用**固定种子**：直接 shuffled() 会在每次重组时洗出不同顺序，
    // 敲个搜索词列表就在手指底下乱跳。种子见调用方的 randomSeed。
    RANDOM -> list.shuffled(Random(seed))
    else -> {
      val comparator: Comparator<EmbyItem> = when (this) {
        NAME -> compareBy { (it.SortName ?: it.Name ?: "").lowercase() }
        YEAR -> compareBy { it.ProductionYear ?: 0 }
        RATING -> compareBy { it.CommunityRating ?: 0.0 }
        // DateCreated 是 ISO-8601 串，字典序即时间序（同格式下成立）
        ADDED -> compareBy { it.DateCreated ?: "" }
        RANDOM -> compareBy { 0 }
      }
      // 用 sortedWith 而不是「排完再 reversed()」：后者会把键值相同的那些条目的
      // 相对顺序也翻过来（同一个年份的片子顺序变了，观感像列表自己动了一下）
      if (asc) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
    }
  }
}

/**
 * 搜索关键字匹配：**片名 + 原名**。
 *
 * 带上原名是为了「用英文原名搜中文标题」（或反过来）—— 库里标题和原名常常是两个语言，
 * 只匹配其中一个会让人以为搜索坏了。
 */
private fun EmbyItem.matchesKeyword(keyword: String): Boolean =
  Name?.contains(keyword, ignoreCase = true) == true ||
    OriginalTitle?.contains(keyword, ignoreCase = true) == true

/**
 * 作品列表顶部的「搜索框 + 排序」一行（与收藏页的 `FavoriteSearchSortRow` 同款版式）。
 *
 * 搜索与排序都是**纯本地、不打服务器**：本页作品是一次性拉全的，过滤 / 重排零成本，
 * 敲一个字就重算一次也不会有网络开销。高产演员几百部作品时，这两样是唯一的捞片手段。
 */
@Composable
private fun PersonSearchSortRow(
  query: String,
  onQueryChange: (String) -> Unit,
  sort: PersonSort,
  asc: Boolean,
  /** 选了某个排序项 + 方向（重复点当前项被本函数判定为「反转方向」，不会走到这里） */
  onSortChange: (PersonSort, Boolean) -> Unit,
  /** 已经是随机排序时再点一次：换一批（重新洗牌） */
  onReshuffle: () -> Unit,
) {
  var sortMenu by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
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
          // ⚠️ 展开前先收掉输入框焦点：搜索态下焦点还在输入框上，菜单关闭时会把焦点
          // 「还」给它，于是每切一次排序就弹一次软键盘。焦点提前清掉后就没得可还。
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
                    // 与首页 / 媒体库同一套：选中的那一项**直接标出当前方向**（↑ 升 / ↓ 降），
                    // 不单列「升序 / 降序」两个选项 —— 菜单短一半，方向也一眼可见
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
                // 方向副标题：让「名称 / 年份 / 评分」在这个方向下到底怎么排一目了然
                Text(
                  text = if (!option.hasDirection) {
                    option.ascHint
                  } else if (selected) {
                    // 已选中：副标题跟着当前方向走（现在到底是怎么排的）
                    if (asc) option.ascHint else option.descHint
                  } else {
                    // 未选中：副标题预告「点下去会变成哪个方向」（= 该项的自然方向）
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
                // 换排序项：方向重置为该项的自然方向（名称升序、年份降序…）
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
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
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
