package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository
import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleFilter
import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleHit
import app.marlboroadvance.mpvex.domain.emby.ChineseSubtitleMarks
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyScanQuery
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyFavoriteRandomIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyProgressBar
import app.marlboroadvance.mpvex.ui.browser.emby.components.VideoFeedIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyItemActionsDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaActionsDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilterRow
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonList
import app.marlboroadvance.mpvex.ui.browser.emby.components.SearchHistoryPanel
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.ui.player.feed.FeedItem
import app.marlboroadvance.mpvex.ui.player.feed.VerticalFeedActivity
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Emby 媒体库浏览页（二级页面）。
 *
 * 页面结构：
 * 1. 顶栏：返回 + 媒体库名称 + 搜索
 * 2. 分类行：全部 / 继续播放 / 合集 / 收藏 / 文件夹
 * 3. 工具行：媒体数量 + 随机播放 + 视图样式 + 排序
 * 4. 媒体网格（海报 / 背景图 / 横幅三种样式）
 *
 * 层级导航：媒体库 →（影视剧）季列表 → 剧集列表 → 详情。
 * 单击行为：容器类继续下钻，其余打开详情页，播放由详情页发起。
 */
/**
 * 退出搜索时的内容快照：词 + 整批结果 + 总数 + 滚动位置。
 * 再点放大镜进来时用它**原样回放** —— 不发请求、不看 cacheKey，
 * 切没切过分类、点得多快都不会闪出别的列表（见搜索 effect 的恢复分支）。
 */
private data class EmbySearchSnapshot(
  val query: String,
  val items: List<EmbyItem>,
  val totalCount: Int,
  val scrollIndex: Int,
  val scrollOffset: Int,
)

@Serializable
data class EmbyLibraryScreen(
  val libraryId: String,
  val title: String,
  val collectionType: String? = null,
  val includeItemTypes: List<String>? = null,
  /** 非空时本页 = 该人员的作品列表（预置 PersonIds 筛选），点击演员进作品用 */
  val personId: String? = null,
  val personName: String? = null,
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

    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    // 上一次搜索词：退出搜索时留存，再点放大镜进来时原样恢复 ——
    // 配合本页缓存（cacheKey 一致即命中），结果直接可见、不用重新搜
    var lastSearchQuery by remember { mutableStateOf("") }
    // 退出搜索时拍下的内容快照 + 「刚进入搜索」的恢复标记：
    // 恢复只在进入的那一次生效，之后改词 / 换排序照常走防抖与网络
    var lastSearchSnapshot by remember { mutableStateOf<EmbySearchSnapshot?>(null) }
    var pendingSearchRestore by remember { mutableStateOf(false) }
    // 类型筛选：空集合 = 「全部」= 不加类型限制
    var searchFilters by remember { mutableStateOf(emptySet<EmbySearchFilter>()) }
    // 上一次「由输入触发」的搜索词。用来区分这次重查是打字引起的（要 400ms 防抖），
    // 还是切排序 / 换类型筛选引起的（离散操作，立即重查）。
    var lastTypedQuery by remember { mutableStateOf("") }
    var showStyleDialog by remember { mutableStateOf(false) }

    // 搜索历史：与首页全库搜索共用同一张表 / 同一份历史
    val searchHistoryRepository = koinInject<SearchHistoryRepository>()
    val searchHistoryFlow = remember { searchHistoryRepository.observe() }
    val searchHistory by searchHistoryFlow.collectAsState(initial = emptyList())
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 排序方式 / 方向 / 卡片样式走偏好存储：重新进入媒体库、甚至重启 App 都沿用上次的选择
    val browserPreferences = koinInject<BrowserPreferences>()
    val sortBy by browserPreferences.embyLibrarySortBy.collectAsState()
    val sortOrderOverride by browserPreferences.embyLibrarySortOrder.collectAsState()
    val cardStyleName by browserPreferences.embyLibraryCardStyle.collectAsState()
    val cardStyle = remember(cardStyleName) { embyCardStyleFromName(cardStyleName) }

    // ── 视图模式 / 网格列数 ──
    // 和排序一样按用户选择持久化：从文件夹返回、重启 App 都保留上次选的排法。
    // （必须放在 browserPreferences 声明之后，否则引用不到。）
    val savedViewMode by browserPreferences.embyLibraryViewMode.collectAsState()
    val viewMode = remember(savedViewMode) { EmbyLibraryViewMode.fromKey(savedViewMode) }
    val gridColumns by browserPreferences.embyLibraryGridColumns.collectAsState()
    // 卡片右上角心形快捷收藏开关（默认开）。关掉后退回只读角标。
    val quickFavoriteEnabled by browserPreferences.embyQuickFavorite.collectAsState()
    // 搜索联想词列表是否收起（默认展开）。联想只是参考，用户收起后一直记着。
    val suggestCollapsed by browserPreferences.embySearchSuggestCollapsed.collectAsState()
    // 联想词的「自动收起」标记：不是用户手动收的，而是程序按下面的判据自己收的。
    // 单独一个状态而不是直接写偏好 —— 自动行为不该污染用户的手动选择。
    var suggestAutoCollapsed by remember { mutableStateOf(false) }
    // 快捷收藏的反馈 Toast：复用同一个实例、每次先 cancel，
    // 避免连点几张卡片时 Toast 在系统里排成一长串、越积越久。
    val quickFavoriteToast = remember { mutableStateOf<Toast?>(null) }
    // 没手动切过时跟随该排序项的自然方向：名称 / 年份升序，其余（加入时间、评分…）降序
    val sortOrder = sortOrderOverride.takeIf { it.isNotBlank() }
      ?: if (sortBy == "SortName" || sortBy == "ProductionYear") "Ascending" else "Descending"

    // ── 分类（全部 / 继续播放 / 合集 / 收藏 / 文件夹）──
    // 和排序、筛选一样**按库持久化**：从库里下钻进一个文件夹再返回时，这一页的组合会被重建，
    // 只放在 remember 里的话分类会被打回「全部」。落盘后返回、甚至重启 App 都还在上次的档位。
    val savedCategory = remember(libraryId) {
      EmbyCategory.entries.firstOrNull {
        it.name == browserPreferences.embyLibraryCategory(libraryId).get()
      } ?: EmbyCategory.ALL
    }
    var category by remember(libraryId) { mutableStateOf(savedCategory) }
    // 「演员」分类 = 展示本库演员（头像网格 + 作品数角标）。
    // 进到某位演员的作品子页时 personId 非空，那时列表是媒体，不算演员模式
    val isActorListMode = category == EmbyCategory.ACTOR && personId == null

    // ── 筛选条件 ──
    // 各项可叠加；空集合、null、false 都代表「不限」。
    // 初始值从偏好里恢复（每个库一份 JSON），没筛过就是全空。
    val savedFilter = remember(libraryId) {
      runCatching {
        val raw = browserPreferences.embyLibraryFilter(libraryId).get()
        if (raw.isBlank()) {
          EmbyLibraryFilterState()
        } else {
          Json.decodeFromString<EmbyLibraryFilterState>(raw)
        }
      }.getOrDefault(EmbyLibraryFilterState())
    }
    var showFilterDialog by remember { mutableStateOf(false) }
    var selectedGenres by remember(libraryId) { mutableStateOf(savedFilter.genres.toSet()) }
    var selectedTags by remember(libraryId) { mutableStateOf(savedFilter.tags.toSet()) }
    var selectedYears by remember(libraryId) { mutableStateOf(savedFilter.years.toSet()) }
    var selectedRatings by remember(libraryId) {
      mutableStateOf(savedFilter.officialRatings.toSet())
    }
    // 演员作品子页：personId 非空时直接把它当成已选中的人员筛选，
    // 于是这一页进来就是「该演员在这一库里的作品」
    var selectedPersonIds by remember(libraryId) {
      mutableStateOf(personId?.let { setOf(it) } ?: savedFilter.personIds.toSet())
    }
    var selectedStudioIds by remember(libraryId) { mutableStateOf(savedFilter.studioIds.toSet()) }
    var playedFilter by remember(libraryId) { mutableStateOf(savedFilter.isPlayed) }
    var hdFilter by remember(libraryId) { mutableStateOf(savedFilter.isHD) }
    var threeDFilter by remember(libraryId) { mutableStateOf(savedFilter.is3D) }
    var subtitlesFilter by remember(libraryId) { mutableStateOf(savedFilter.hasSubtitles) }
    var chineseSubsOnly by remember(libraryId) { mutableStateOf(savedFilter.chineseSubsOnly) }
    // ── 「中文字幕」标记配置 ──
    // 标记列表存在偏好里（可编辑、可恢复默认），不写死在代码里。
    // 在 remember 里同步 configure（而不是 LaunchedEffect）：保证任何扫描开始之前
    // 正则已经编译好，不会出现「先用旧规则扫一遍、再用新规则扫一遍」。
    val chineseMarksRaw by browserPreferences.embyChineseSubtitleMarks.collectAsState()
    val chineseMarks = remember(chineseMarksRaw) {
      ChineseSubtitleMarks.fromJson(chineseMarksRaw).also { ChineseSubtitleFilter.configure(it) }
    }
    var showMarksEditor by remember { mutableStateOf(false) }
    var minRating by remember(libraryId) { mutableStateOf(savedFilter.minRating) }
    var favoriteOnly by remember(libraryId) { mutableStateOf(savedFilter.favoriteOnly) }
    // 该库实际出现过的可选项，进页面时拉一次
    var filterOptions by remember { mutableStateOf(EmbyFilterOptions()) }
    val hasActiveFilter =
      selectedGenres.isNotEmpty() || selectedTags.isNotEmpty() ||
        selectedYears.isNotEmpty() || selectedRatings.isNotEmpty() ||
        selectedPersonIds.isNotEmpty() || selectedStudioIds.isNotEmpty() ||
        playedFilter != null || hdFilter != null || threeDFilter != null ||
        subtitlesFilter != null || chineseSubsOnly || minRating != null || favoriteOnly

    /** 把当前筛选条件写回偏好，下次进这个库还带着 */
    fun persistFilter() {
      val state = EmbyLibraryFilterState(
        genres = selectedGenres.toList(),
        tags = selectedTags.toList(),
        years = selectedYears.toList(),
        officialRatings = selectedRatings.toList(),
        personIds = selectedPersonIds.toList(),
        studioIds = selectedStudioIds.toList(),
        isPlayed = playedFilter,
        isHD = hdFilter,
        is3D = threeDFilter,
        hasSubtitles = subtitlesFilter,
        chineseSubsOnly = chineseSubsOnly,
        minRating = minRating,
        favoriteOnly = favoriteOnly,
      )
      runCatching { browserPreferences.embyLibraryFilter(libraryId).set(Json.encodeToString(state)) }
    }

    // 列表缓存 key：库 + 分类 + 排序 + 搜索词。
    // 从详情页 / 播放器返回时这个 key 不变，于是直接命中缓存、不再发请求；
    // 只有用户下拉刷新或改了筛选条件才会真正重新拉取。
    val cacheKey = "$libraryId|${category.name}|$sortBy|$sortOrder|$searchQuery|" +
      "g=${selectedGenres.sorted().joinToString(",")}|" +
      "t=${selectedTags.sorted().joinToString(",")}|" +
      "y=${selectedYears.sorted().joinToString(",")}|" +
      "r=${selectedRatings.sorted().joinToString(",")}|" +
      "p=${selectedPersonIds.sorted().joinToString(",")}|" +
      "s=${selectedStudioIds.sorted().joinToString(",")}|" +
      "played=$playedFilter|hd=$hdFilter|3d=$threeDFilter|sub=$subtitlesFilter|" +
      "csub=$chineseSubsOnly|" +
      // 标记配置也算条件：改了标记 / 匹配范围，命中结果就变了，必须重新扫
      "cmarks=${chineseMarks.signature()}|" +
      "m=$minRating|fav=$favoriteOnly|" +
      // 搜索筛选也算条件：换类型的结果是另一批，不能复用同一桶缓存
      "st=${searchFilters.map { it.name }.sorted().joinToString(",")}"
    val cachedEntry = remember(cacheKey) { EmbyLibraryCache.get(cacheKey) }

    // 搜索路径不从这里起值：搜索的「退出再进」由上面的快照（词+结果+滚动位置）回放，
    // 不读 cacheKey 缓存 —— 历史竞态写进缓存的脏条目（如全库媒体）不会闪出来。
    val cachedItems =
      if (searchQuery.isBlank()) cachedEntry?.items ?: emptyList() else emptyList()
    var items by remember(cacheKey) { mutableStateOf(cachedItems) }
    var totalCount by remember(cacheKey) {
      mutableIntStateOf(if (searchQuery.isBlank()) cachedEntry?.totalCount ?: 0 else 0)
    }
    // ── 「演员」分类的独立数据 ──
    // 不走上面媒体那套分页 / 缓存 / 中文字幕扫描：/Persons 一次性把演员给全，
    // 既没有下一页要续拉，也没有字幕可扫。单独存一份，两条路径互不干扰。
    var actorItems by remember(cacheKey) { mutableStateOf(emptyList<EmbyItem>()) }
    var actorTotal by remember(cacheKey) { mutableIntStateOf(0) }
    var actorLoading by remember(cacheKey) { mutableStateOf(false) }
    var actorError by remember(cacheKey) { mutableStateOf<String?>(null) }
    var isLoading by remember(cacheKey) {
      mutableStateOf(
        if (searchQuery.isBlank()) cachedEntry == null || cachedEntry.items.isEmpty() else true,
      )
    }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val isRefreshing = remember { mutableStateOf(false) }
    // itemId → 命中的「中文字幕」标记：列表卡片直接显示是哪条标记命中的
    var chineseHits by remember(cacheKey) {
      mutableStateOf(if (searchQuery.isBlank()) cachedEntry?.hits ?: emptyMap() else emptyMap())
    }
    // 客户端筛选必须扫全库，这两个状态让 UI 能显示「已扫多少 / 命中多少」
    var scanScanned by remember(cacheKey) {
      mutableIntStateOf(if (searchQuery.isBlank()) cachedEntry?.scannedCount ?: 0 else 0)
    }
    var isScanning by remember(cacheKey) { mutableStateOf(false) }
    // 正在跑的扫描任务：换筛选条件 / 退出页面时取消，避免旧扫描继续拉数据
    val scanJob = remember { mutableStateOf<Job?>(null) }
    // 长按选中的条目：非空时弹操作框（文件夹与媒体是两套菜单）
    var actionTarget by remember { mutableStateOf<EmbyItem?>(null) }
  /** 长按发生的位置（root 坐标）：菜单锚在这里展开，而不是屏幕中间 */
  var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 文件夹宫格封面：初值取自进程内缓存，从子页面返回时不会重新请求
    val folderCovers = remember {
      mutableStateMapOf<String, List<String>>().apply { putAll(EmbyFolderCoverCache.snapshot()) }
    }

    // 用缓存里的滚动位置初始化，返回时能停在原来那一屏
    val gridState = rememberLazyGridState(
      initialFirstVisibleItemIndex = cachedEntry?.scrollIndex ?: 0,
      initialFirstVisibleItemScrollOffset = cachedEntry?.scrollOffset ?: 0,
    )

    // 换排序方式 / 切升降序之后，列表内容整体换了一批，滚动位置必须回到第一条 ——
    // 否则用户停在「第 200 个」，切完排序还停在 200 号位，看到的是一批顺序完全不同的条目。
    // 首次进入不触发：那时要保留上面从缓存恢复出来的位置。
    // `gridState` 是 remember 出来的（同一个对象跨排序存活），所以必须显式归位。
    var lastSortKey by remember { mutableStateOf("$sortBy|$sortOrder") }
    LaunchedEffect(sortBy, sortOrder) {
      val key = "$sortBy|$sortOrder"
      if (key != lastSortKey) {
        lastSortKey = key
        // 不带动画：整批内容都换了，滑动过去毫无意义还显得卡
        gridState.scrollToItem(0)
      }
    }

    // 季/集层级由 includeItemTypes 固定，优先于分类筛选
    val effectiveTypes: List<String>? = includeItemTypes
      ?: category.itemTypes
      // 「全部」按库类型限定条目类型；否则 Emby 会把季、集和文件夹一起返回，列表是混的
      ?: if (category == EmbyCategory.ALL) allItemTypesFor(collectionType) else null
    val effectiveFilters: List<String>? = if (includeItemTypes == null) category.filters else null
    val recursive: Boolean = category != EmbyCategory.FOLDER
    // Emby 的 Recursive 查询默认会把文件夹一并返回。
    // 用户期望「全部」直接铺媒体，只有切到「文件夹」分类才看到文件夹，所以这里显式排除。
    // 「全部」只保留媒体本体：合集（BoxSet）单独放到「合集」分类里看，这里排除掉。
    // 例外：专门的「合集库」（collectionType = boxsets）本身装的就是合集，
    // 若也排除会把它剔空，所以只对非合集库生效。
    val excludeBoxSet = category == EmbyCategory.ALL &&
      collectionType?.lowercase() != "boxsets"
    val effectiveExclude: List<String>? =
      if (includeItemTypes == null && category != EmbyCategory.FOLDER) {
        if (excludeBoxSet) FOLDER_TYPES + "BoxSet" else FOLDER_TYPES
      } else null
    // 只靠 ExcludeItemTypes 剔不干净：它按**类型名**筛，漏掉的类型（Season、MusicAlbum、
    // 以及库里混进的音频 / 图片 / 电子书）照样回来。这两个参数是官方的另外两道闸：
    // MediaTypes 按媒体形态筛（Video），IsFolder=false 把「本身不能播」的容器整体挡掉。
    // 「文件夹」「合集」分类要的就是容器，所以那两种分类不传。
    val effectiveMediaTypes: List<String>? =
      if (category == EmbyCategory.FOLDER || category == EmbyCategory.BOXSET) {
        null
      } else {
        mediaTypesFor(collectionType, includeItemTypes)
      }
    val effectiveIsFolder: Boolean? = if (effectiveMediaTypes == null) null else false

    fun cacheItems() {
      EmbyLibraryCache.putItems(
        key = cacheKey,
        items = items,
        totalCount = totalCount,
        hits = chineseHits,
        scannedCount = scanScanned,
        // 扫描中途被打断时缓存里只有前半截命中，标记成「不完整」，下次进来重扫
        complete = !isScanning,
      )
    }

    /**
     * 「中文字幕」的全量扫描。
     *
     * 为什么不能沿用「拉一页筛一页」：Emby 的 /Items 一页只给 PAGE_SIZE 条，
     * 客户端筛掉的条目**不占 StartIndex** —— 拿筛完的 items.size 当下一次的起始下标，
     * 翻页窗口会一直重叠、反复拉同一段，命中条目永远只有开头那一小撮
     * （表现就是「只能筛出一部分中文字幕视频」）。
     * 所以这里改成：把整个库逐页拉到底，逐页过滤、逐页把命中结果抛给 UI。
     */
    suspend fun runScan(current: EmbyServer) {
      // 已经有结果的 Id 集合：扫描期间库变了可能导致某条被重复返回，按 Id 去重
      val seen = HashSet<String>()
      items.forEach { item -> item.Id?.let { seen.add(it) } }
      val scanned = viewModel.scanItems(
        server = current,
        query = EmbyScanQuery(
          parentId = libraryId,
          includeItemTypes = effectiveTypes,
          excludeItemTypes = effectiveExclude,
          mediaTypes = effectiveMediaTypes,
          isFolder = effectiveIsFolder,
          filters = effectiveFilters,
          sortBy = sortBy,
          sortOrder = sortOrder,
          recursive = recursive,
          genres = selectedGenres.toList().takeIf { it.isNotEmpty() },
          tags = selectedTags.toList().takeIf { it.isNotEmpty() },
          years = selectedYears.toList().takeIf { it.isNotEmpty() },
          officialRatings = selectedRatings.toList().takeIf { it.isNotEmpty() },
          minCommunityRating = minRating,
          isFavorite = if (favoriteOnly) true else null,
          personIds = selectedPersonIds.toList().takeIf { it.isNotEmpty() },
          isPlayed = playedFilter,
          isHD = hdFilter,
          is3D = threeDFilter,
          hasSubtitles = subtitlesFilter,
          studioIds = selectedStudioIds.toList().takeIf { it.isNotEmpty() },
        ),
      ) { chunk ->
        // 回调在 IO 线程：正则过滤是纯 CPU 活，放这里不会占 UI 线程
        val matched = ChineseSubtitleFilter.filter(chunk.items)
        val fresh = matched.filter { (item, _) -> item.Id?.let { seen.add(it) } == true }
        withContext(Dispatchers.Main) {
          items = items + fresh.map { it.first }
          chineseHits = chineseHits + fresh.associate { it.first.Id.orEmpty() to it.second }
          scanScanned = chunk.scanned
          if (chunk.total > 0) totalCount = chunk.total
          cacheItems()
        }
      }
      withContext(Dispatchers.Main) {
        scanScanned = scanned
        isScanning = false
        isLoading = false
        cacheItems()
      }
    }

    /** 启动（或重启）一次扫描。旧的扫描先取消，免得两个扫描同时写同一份状态 */
    fun startScan(current: EmbyServer) {
      scanJob.value?.cancel()
      error = null
      isScanning = true
      scanJob.value = scope.launch {
        try {
          runScan(current)
        } catch (e: CancellationException) {
          // 被接替的扫描 / 页面退出取消：状态交给接替者，这里不要动
          throw e
        } catch (e: Throwable) {
          isScanning = false
          isLoading = false
          error = e.message ?: "加载失败"
        }
      }
    }

    // 加载代际计数：每次 load() 递增。响应回来时若代际已前进（期间用户又触发了别的加载），
    // 本次结果必须整批丢弃 —— 否则「退出搜索时遗留的全库媒体请求」会在用户进入搜索后
    // 姗姗返回，把搜索结果覆盖成浏览列表（用户实测「恢复搜索却显示所有媒体」就是它）。
    var loadGeneration by remember { mutableStateOf(0) }

    suspend fun load(reset: Boolean) {
      // 冷启动时当前服务器可能还没恢复，这里等一下，避免静默不加载
      val current = viewModel.currentServerOrAwait() ?: return
      val gen = ++loadGeneration
      // 「中文字幕」是客户端按路径判定的，服务端没有对应参数 —— 只能扫全库
      if (chineseSubsOnly && searchQuery.isBlank()) {
        startScan(current)
        return
      }
      if (reset) isLoading = true
      error = null
      runCatching {
        if (searchQuery.isBlank()) {
          viewModel.loadItems(
            server = current,
            parentId = libraryId,
            includeItemTypes = effectiveTypes,
            filters = effectiveFilters,
            sortBy = sortBy,
            sortOrder = sortOrder,
            startIndex = if (reset) 0 else items.size,
            limit = PAGE_SIZE,
            recursive = recursive,
            excludeItemTypes = effectiveExclude,
            genres = selectedGenres.toList().takeIf { it.isNotEmpty() },
            tags = selectedTags.toList().takeIf { it.isNotEmpty() },
            years = selectedYears.toList().takeIf { it.isNotEmpty() },
            officialRatings = selectedRatings.toList().takeIf { it.isNotEmpty() },
            minCommunityRating = minRating,
            isFavorite = if (favoriteOnly) true else null,
            mediaTypes = effectiveMediaTypes,
            isFolder = effectiveIsFolder,
            personIds = selectedPersonIds.toList().takeIf { it.isNotEmpty() },
            isPlayed = playedFilter,
            isHD = hdFilter,
            is3D = threeDFilter,
            hasSubtitles = subtitlesFilter,
            studioIds = selectedStudioIds.toList().takeIf { it.isNotEmpty() },
          )
        } else {
          val result = viewModel.search(
            server = current,
            term = searchQuery,
            itemTypes = EmbySearchFilter.toItemTypes(searchFilters),
            // 排序参数照旧传给服务端（它认就更好），但真正决定顺序的是下面的客户端排序：
            // 服务端对 SearchTerm + SortBy 这个组合不保证生效，实测切了排序顺序不变。
            sortBy = sortBy,
            sortOrder = sortOrder,
            // 搜索不分页、整批返回，所以把上限提到一页的量，客户端排序才有足够样本
            limit = PAGE_SIZE,
          )
          EmbyItemsPage(
            // 排序放到后台线程做：几百上千条的比较是纯 CPU 活，不该占主线程。
            // 排完连同渲染一起交给 UI —— 中间没有「先出一版乱序的再跳一遍」的过程。
            withContext(Dispatchers.Default) { applyClientSort(result, sortBy, sortOrder) },
            result.size,
          )
        }
      }.onSuccess { page ->
        // 已被更新的加载接替：本次结果过期，整批丢弃（历史记录也不要记）
        if (gen != loadGeneration) return@onSuccess
        // 搜索命中才记历史：一个字都没查到的词记下来只会污染列表；
        // 键盘上显式按「搜索」的那次在上面的 keyboardActions 里已经记过了。
        if (searchQuery.isNotBlank() && page.items.isNotEmpty()) {
          searchHistoryRepository.record(searchQuery)
        }
        if (chineseSubsOnly) {
          // 搜索结果本身就是完整的一批（/Items?SearchTerm 一次性给完），直接筛。
          // 筛选 + 排序一起在后台线程做完，回到主线程只进行一次赋值 —— 统一渲染，没有中间态。
          val (sorted, hits) = withContext(Dispatchers.Default) {
            val matched = ChineseSubtitleFilter.filter(page.items)
            applyClientSort(matched.map { it.first }, sortBy, sortOrder) to
              matched.associate { it.first.Id.orEmpty() to it.second }
          }
          items = sorted
          chineseHits = hits
          // 客户端筛过之后服务端总数对不上，数量行显示可见命中数
          totalCount = sorted.size
        } else {
          items = if (reset) {
            page.items
          } else {
            // 分页时按 Id 去重后追加
            items + page.items.filter { new -> items.none { it.Id == new.Id } }
          }
          totalCount = page.totalCount
        }
        cacheItems()
        // 搜索的结果每批都是整批替换，排完序要停在第一条。
        //
        // 这一步放在数据落位**之后**才可靠：排序变化那个 LaunchedEffect 是在请求发出之前跑的，
        // 而搜索这条路要先过防抖、再走网络，等结果回来时那次归位早就被列表重建冲掉了 ——
        // 表现就是「切完排序列表还停在原来的位置」。
        if (searchQuery.isNotBlank() && reset) {
          gridState.scrollToItem(0)
        }
      }.onFailure {
        // 过期的失败同样丢弃：别用旧请求的错误盖掉新请求的结果
        if (gen != loadGeneration) return@onFailure
        error = it.message ?: "加载失败"
      }
      // 过期请求不碰 isLoading：收尾交给当前那次加载
      if (gen == loadGeneration) isLoading = false
    }

    /**
     * 拉「演员」分类的数据。
     *
     * 与 [load] 完全分开：一次请求拿全（/Persons），不分页、不进媒体缓存、
     * 不做中文字幕扫描。失败只影响这一屏，给个重试按钮即可。
     */
    suspend fun loadActors() {
      val current = viewModel.currentServerOrAwait() ?: return
      actorLoading = true
      actorError = null
      val loaded = runCatching {
        viewModel.getActors(current, libraryId)
      }
      loaded.onSuccess { list ->
        // 按网格 key 的同一口径去重：服务端重复返回同一演员时，
        // 重复 key 会把 LazyVerticalGrid 撞崩（与媒体库搜索同款问题）
        actorItems = list.distinctBy { it.Id ?: it.Name ?: "" }
        actorTotal = actorItems.size
      }.onFailure {
        actorError = it.message ?: "加载演员失败"
      }
      actorLoading = false

      // 作品数角标：/Persons 的 Fields 不支持 ChildCount，服务端从不返回该字段
      // （见 EmbyClient.getPersonWorkCounts 的说明），所以自己数一遍补上。
      // 放在 loading 结束之后 —— 头像网格先出来，数字随后补，不让首屏等这次全库统计。
      // 服务端哪天真的返回了 ChildCount，就跳过统计、直接用它。
      val list = loaded.getOrNull().orEmpty()
      if (list.isEmpty() || list.any { it.ChildCount != null }) return
      val counts = viewModel.getPersonWorkCounts(current, libraryId)
      if (counts.isEmpty()) return
      actorItems = actorItems.map { person ->
        val n = person.Id?.let { counts[it] } ?: 0
        if (n > 0) person.copy(ChildCount = n) else person
      }
    }

    // 首次进入 / 筛选条件变化：只有没有可用缓存时才请求。
    // 有缓存说明是刚从详情页或播放器返回，直接复用列表，不刷新。
    // 首次运行不归位 = 「从详情页返回要停在离开时的位置」；之后凡 cacheKey 变化
    // （切分类 / 改筛选 / 改排序）呈现的都是新的一批内容，网格必须归位到第一条
    // —— 否则就是「从中间开始显示」（用户反馈）。「退出搜索」是唯一例外：
    // 那批内容没变，要连位置一起接回来（见下面 restoringBrowse 分支）。
    var firstBrowseKeyRun by remember { mutableStateOf(true) }
    // 「退出搜索」要回到的是**同一批**浏览数据（items 从缓存原样起值、也不重拉），
    // 所以滚动位置也得接回去；其余 cacheKey 变化（切分类 / 改筛选 / 改排序）呈现的是
    // 新的一批内容，仍要归位到第一条。两者靠这个一次性标记区分。
    var pendingBrowseRestore by remember { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
      // 搜索走下面带防抖的那个 effect，这里跳过，避免每敲一个字就立刻发一次请求
      if (searchQuery.isNotBlank()) return@LaunchedEffect
      val restoringBrowse = pendingBrowseRestore
      pendingBrowseRestore = false
      when {
        // 退出搜索：内容与滚动位置都原样接回，不归位
        restoringBrowse -> {
          // 顺手把「首次运行」标记消费掉：用户可能一进库就直接搜索、上面这一次
          // 浏览态的运行从没发生过，不消费的话它会把之后第一次切分类的归位也吃掉。
          firstBrowseKeyRun = false
          val restore = EmbyLibraryCache.get(cacheKey)
          gridState.scrollToItem(restore?.scrollIndex ?: 0, restore?.scrollOffset ?: 0)
        }
        firstBrowseKeyRun -> firstBrowseKeyRun = false
        else -> gridState.scrollToItem(0)
      }
      // 「演员」分类：走独立的演员加载，跳过媒体那套分页 / 缓存 / 扫描
      if (isActorListMode) {
        if (actorItems.isEmpty() && !actorLoading) loadActors()
        return@LaunchedEffect
      }
      val entry = EmbyLibraryCache.get(cacheKey)
      // 扫描没跑完的缓存（items 只含前半截命中）不能当完整结果复用，重扫
      val stale = chineseSubsOnly && entry != null && !entry.complete
      if (entry == null || entry.items.isEmpty() || stale) load(reset = true)
    }

    // 筛选可选项（类型 / 标签 / 年份 / 分级）按库拉一次，
    // 只在「库」或「服务器」变化时重新取
    LaunchedEffect(libraryId, server) {
      val current = server ?: viewModel.currentServerOrAwait() ?: return@LaunchedEffect
      filterOptions = viewModel.loadFilterOptions(current, libraryId)
    }

    // 离开页面时记下滚动位置，返回时才能回到原来的位置
    DisposableEffect(cacheKey) {
      onDispose {
        EmbyLibraryCache.putScroll(
          cacheKey,
          gridState.firstVisibleItemIndex,
          gridState.firstVisibleItemScrollOffset,
        )
      }
    }

    LaunchedEffect(gridState, items.size) {
      snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .collect { lastVisible ->
          // 客户端筛选走全量扫描，没有「下一页」可翻；扫描中也不要再触发普通分页。
          // 演员列表同理：/Persons 一次性给全，不可能有下一页
          val canLoadMore = !isLoading && !isScanning && !chineseSubsOnly && !isActorListMode &&
            items.size < totalCount && lastVisible != null
          if (canLoadMore && lastVisible >= items.size - 6) {
            load(reset = false)
          }
        }
    }

    // 换筛选条件 / 退出页面：把正在跑的全量扫描停掉（一个库可能上百个请求）
    DisposableEffect(cacheKey) {
      onDispose { scanJob.value?.cancel() }
    }

    // 详情页删除后同步：EmbyLibraryCache.lastRemovedItemId 是快照状态，
    // 列表组合还活着时也能立即触发重组，把已删除的条目从渲染里剔除
    val lastRemovedItemId = EmbyLibraryCache.lastRemovedItemId
    val visibleItems =
      (if (lastRemovedItemId != null) items.filterNot { it.Id == lastRemovedItemId } else items)
        // 顺手剔掉服务端混进来的幽灵条目（详见 isGhostItem）：它与官方客户端的条目数对不上，
        // 点进去还会崩。列表数量行用的是 totalCount（服务端给的），不在这里减，
        // 所以「共 41 个」这类数字仍以服务端为准。
        .filterNot { isGhostItem(it) }
        // 服务端可能在结果里把**同一个条目返回两次**（SmartStrm 实测搜索「雲」时
        // 同一 Id 出现两条），而下面网格的 key 正是「Id ?: Name」—— 重复条目会直接
        // 把 LazyVerticalGrid 撞崩（IllegalArgumentException: Key was already used）。
        // 按同一口径去重：既修崩溃，「命中 N 项」的计数也变准了。
        .distinctBy { it.Id ?: it.Name ?: "" }
        // 本地再按同一口径剔一遍。
        //
        // 为什么不能只靠服务端：MediaTypes / ExcludeItemTypes / IsFolder 这些参数
        // 在官方 Emby 上生效，但兼容层（SmartStrm 之类）未必全部支持 ——
        // 少了任何一个，库里混进来的音频、图片、电子书、Season 之类就会漏到列表里。
        // 本地兜底之后，无论服务端认不认这些参数，结果都是一致的。
        .filter { item ->
          val type = item.Type
          // 搜索命中的演员是**合法结果**，不能跟着媒体一起被剔掉。
          //
          // 下面两条兜底都是按「媒体形态」判的（有没有 MediaType、在不在 PLAYABLE_TYPES 里），
          // 而 Person 两样都不占：它没有 MediaType，也不是可播放类型。于是无论库的类型白名单
          // 认不认得出来，演员都会被判为「非媒体」剔走 —— 这正是「同一句话在首页搜得到演员、
          // 在媒体库里一个人都搜不到」的原因（首页没有这层过滤）。
          //
          // 只在搜索时放行：浏览路径服务端本来就不会返回 Person（`/Users/{id}/Items`
          // 那条端点拿不到人物），放行它没有副作用。
          val isPersonHit = searchQuery.isNotBlank() && type.equals("Person", ignoreCase = true)
          if (type != null) {
            if (effectiveExclude?.any { it.equals(type, ignoreCase = true) } == true) return@filter false
            if (!isPersonHit && effectiveTypes?.none { it.equals(type, ignoreCase = true) } == true) {
              return@filter false
            }
          }
          // MediaType 明确不是目标形态的一律剔掉；为空的大多是容器，交给上面的类型白名单
          val mediaType = item.MediaType
          val want = effectiveMediaTypes
          if (mediaType != null && want != null && want.none { it.equals(mediaType, ignoreCase = true) }) {
            return@filter false
          }
          // 未知库（CollectionType 为空）拿不到类型白名单，此时只能靠 MediaType ——
          // 但 Genre / Tag / Studio 这类「非媒体实体」MediaType 也是空，上面漏得干干净净。
          // 实测这类库会返回一堆名为 `"frames":2670` 的 Genre 条目（NFO 里塞了 JSON），
          // 所以这里再按「是否可播放」兜一次。
          if (type != null && want != null && effectiveTypes == null) {
            val playable = PLAYABLE_TYPES.any { it.equals(type, ignoreCase = true) }
            val isVideo = mediaType?.equals("Video", ignoreCase = true) == true
            if (!playable && !isVideo && !isPersonHit) return@filter false
          }
          true
        }

    // 搜索结果的类型筛选统一由服务端的类型筛选条（`EmbySearchFilterRow`）负责 ——
    // 这里原本还叠了一层「本地按 Type 切一刀」的结果分栏，但两行 chip 的标签一模一样
    // （全部 / 电影 / 剧集 / 单集 / 合集 / 演员），用户根本分不清哪个才算数，属于重复入口。
    // 已删掉本地那一层：类型筛选只保留一处，改它就重新查库，语义唯一。
    val displayItems = visibleItems

    /**
     * 卡片右上角心形「快捷收藏」。
     *
     * 之前的实现只把请求发出去、**完全不动本地列表**：服务器那边收藏成功了，
     * 卡片上的红心却仍是旧样子（未收藏还是描边），要点进详情页再退回来才同步 ——
     * 就是用户反馈的「能触发收藏但红心不更新」。
     *
     * 这里改成**乐观更新 + 失败回滚**：
     * 1. 先按取反把本地这一条（以及进程内缓存）改掉，红心立刻变，不用等网络往返；
     * 2. 立刻弹一句 Toast 反馈（已收藏 / 已取消收藏），点一下就有回应，不必盯着红心看；
     * 3. 再发请求，失败就把状态改回去、用失败提示替换掉刚才那句成功提示，
     *    绝不让界面停在假的成功态上；
     * 4. 同时写 [EmbyLibraryCache]，切换筛选条件（cacheKey 变）重新进列表时红心不会弹回。
     */
    fun toggleFavoriteQuick(
      item: EmbyItem,
      srv: EmbyServer,
    ) {
      val id = item.Id ?: return
      // 目标状态取「当前列表里的真实状态」，而不是入参 item 里的快照：
      // 卡片按 Id 复用、回调又可能晚一拍，入参未必是最新的（曾经就是它导致
      // 「只能收藏、取消不掉」）。列表状态是我们刚刚乐观改过的那一份，最可信。
      val liveFavorite =
        items.firstOrNull { it.Id == id }?.UserData?.IsFavorite
          ?: EmbyLibraryCache.favoriteStateOf(id)
          ?: (item.UserData?.IsFavorite == true)
      val target = !liveFavorite

      fun applyLocal(fav: Boolean) {
        items =
          items.map { cur ->
            if (cur.Id == id) {
              cur.copy(UserData = (cur.UserData ?: EmbyUserData()).copy(IsFavorite = fav))
            } else {
              cur
            }
          }
        EmbyLibraryCache.updateFavorite(id, fav)
      }

      // 复用同一个 Toast 实例：新的先 cancel 掉旧的，连点也不会排队堆积。
      fun toast(msg: String) {
        quickFavoriteToast.value?.cancel()
        quickFavoriteToast.value =
          Toast.makeText(context, msg, Toast.LENGTH_SHORT).also { it.show() }
      }

      applyLocal(target)
      // 乐观更新已经把红心换色了，这里补一句明确反馈，点下去立刻有回应。
      toast(if (target) "已收藏" else "已取消收藏")
      scope.launch {
        val result = viewModel.toggleFavorite(srv, item, target)
        if (result.isFailure) {
          applyLocal(!target)
          toast(result.exceptionOrNull()?.message ?: "操作失败")
        }
      }
    }

    // 搜索联想候选：**结果里的片名 + 历史搜索词**，纯本地包含匹配。
    //
    // 为什么不走服务端 `/Search/Hints`：每敲一个字都发一次请求太重，而且那个端点
    // 并非所有服务端实现（兼容层）都支持，失败时联想就整个没了。本地匹配在
    // 没网 / 服务端不支持时照样能用，代价只是「候选只覆盖已经拉到的那批结果」——
    // 对「记不全片名、想点一下补全」这个诉求已经够了。
    val searchSuggestions = remember(searchQuery, searchHistory, displayItems) {
      val q = searchQuery.trim()
      if (q.isEmpty()) {
        emptyList()
      } else {
        val fromHistory = searchHistory.filter {
          it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true)
        }
        val fromItems = displayItems.asSequence()
          .mapNotNull { it.Name }
          .filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
          .distinct()
        // 只留 5 条：联想是「顺手补全」，条目一多反而要逐条扫，比手打还慢。
        (fromHistory + fromItems).distinct().take(5).toList()
      }
    }

    /**
     * 「随机播放」的取数。**口径：随机 = 把当前屏幕上这批结果打乱。**
     *
     * 屏幕上已经有一整批结果时（搜索、或「中文字幕」的全库扫描）直接洗牌 ——
     * 这批就是用户眼前看到的东西，洗它最符合直觉，也省得去赌服务端认不认
     * `SearchTerm` + `SortBy=Random` 这个组合。
     *
     * 没搜索时屏幕上的只是「一页」（库内浏览是分页续拉的），洗一页等于把随机范围
     * 缩到极小，所以那种情况仍然交给服务端从整个（已筛选的）库里按 `SortBy=Random` 抽。
     *
     * 改造前这里只传 `parentId + PLAYABLE_TYPES`，等于「整个库随机」：
     * 搜了某个词、或筛了类型/标签/年份之后再点随机，放的还是全库内容，跟眼前这批对不上。
     *
     * [favoritesOnly] 对应「随机播放收藏」那个键：在同样的范围上再限定只看收藏。
     */
    suspend fun loadRandomForView(
      current: EmbyServer,
      favoritesOnly: Boolean = false,
    ): List<EmbyItem> {
      // 搜索 / 「中文字幕」扫描：结果集已经整批在手上 → 直接洗牌
      if (searchQuery.isNotBlank() || chineseSubsOnly) {
        val pool = visibleItems.filter { !favoritesOnly || it.UserData?.IsFavorite == true }
        // 优先只随机「能直接播」的条目；整批都是剧集 / 合集这类容器时才退回整批，
        // 否则点下去会直接提示「没有可播放内容」
        val playable = pool.filter { item -> PLAYABLE_TYPES.any { it == item.Type } }
        return (playable.ifEmpty { pool })
          .shuffled()
          .take(browserPreferences.randomPlayCount.randomPlayLimit())
      }

      // 随机只能落在「能直接播」的条目上。当前分类若只含 Series / BoxSet / Folder
      // 这类容器（剧集库、合集分类、文件夹分类），交集为空 → 退回「本库可播放条目」，
      // 也就是改造前的老行为；其它筛选项仍然生效。
      val playableOfView = (effectiveTypes ?: PLAYABLE_TYPES).filter { it in PLAYABLE_TYPES }
      val randomTypes = playableOfView.takeIf { it.isNotEmpty() } ?: PLAYABLE_TYPES

      // 随机播放 / 视界流取多少条：统一读设置项
      val randomLimit = browserPreferences.randomPlayCount.randomPlayLimit()

      return viewModel.loadItems(
        server = current,
        parentId = libraryId,
        includeItemTypes = randomTypes,
        filters = effectiveFilters,
        sortBy = "Random",
        sortOrder = "Ascending",
        startIndex = 0,
        limit = randomLimit,
        // 固定用 recursive：容器类分类（如「文件夹」）本身递归不出可播条目
        recursive = true,
        excludeItemTypes = effectiveExclude,
        // 随机只落在能播的条目上，所以这里固定按视频形态 + 非容器来取
        mediaTypes = effectiveMediaTypes ?: listOf("Video"),
        isFolder = false,
        genres = selectedGenres.toList().takeIf { it.isNotEmpty() },
        tags = selectedTags.toList().takeIf { it.isNotEmpty() },
        years = selectedYears.toList().takeIf { it.isNotEmpty() },
        officialRatings = selectedRatings.toList().takeIf { it.isNotEmpty() },
        minCommunityRating = minRating,
        isFavorite = if (favoritesOnly || favoriteOnly) true else null,
        personIds = selectedPersonIds.toList().takeIf { it.isNotEmpty() },
        isPlayed = playedFilter,
        isHD = hdFilter,
        is3D = threeDFilter,
        hasSubtitles = subtitlesFilter,
        studioIds = selectedStudioIds.toList().takeIf { it.isNotEmpty() },
      ).items
    }

    /** 随机播放的统一入口：取一批、没取到就明确提示（以前是静默无反应） */
    fun startRandomPlayback(favoritesOnly: Boolean) {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val random = loadRandomForView(current, favoritesOnly = favoritesOnly)
        if (random.isEmpty()) {
          android.widget.Toast
            .makeText(
              context,
              if (favoritesOnly) "当前范围内没有可随机播放的收藏" else "当前范围内没有可随机播放的内容",
              android.widget.Toast.LENGTH_SHORT,
            )
            .show()
          return@launch
        }
        // 随机列表里可能混着看过的剧，切过去若恢复进度会直接跳到片尾，所以每个视频都从头放
        viewModel.launchPlaylist(current, random, playFromStartAll = true)
      }
    }

    /**
     * 「视界流」入口：从当前库随机抽一批，进仿抖音竖屏上下滑连播的页面。
     *
     * 随机取片和上面两个随机按钮走同一条路径（数量设置、容器类分类的兜底都共用），
     * 差别只在最后一步：不是丢给主播放器排队播，而是把整批直连地址交给
     * VerticalFeedActivity，让它自己管「滑到哪放哪」。
     */
    fun startFeedPlayback(favoritesOnly: Boolean) {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val random = loadRandomForView(current, favoritesOnly = favoritesOnly)
        val items = random.mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          FeedItem(
            itemId = id,
            title = viewModel.displayTitle(item),
            url = viewModel.getStreamUrl(current, id),
            isFavorite = item.UserData?.IsFavorite == true,
          )
        }
        if (items.isEmpty()) {
          android.widget.Toast
            .makeText(
              context,
              if (favoritesOnly) "当前范围内没有可随机播放的收藏" else "当前范围内没有可随机播放的内容",
              android.widget.Toast.LENGTH_SHORT,
            )
            .show()
          return@launch
        }
        VerticalFeedActivity.launch(context, items, libraryId, favoritesOnly)
      }
    }

    /**
     * 收掉输入法焦点。
     *
     * 进入搜索后焦点大概率还留在输入框上，此时点工具行上的任何按钮（排序 / 筛选 / 样式 …）
     * 弹出层关闭时会把焦点「还」给输入框，软键盘跟着弹出来 ——
     * 表现就是「每切一次排序方式就弹一次键盘」。隐藏键盘（`hide()`）治不了，
     * 焦点还在就会有下一次，必须把焦点本身清掉。
     */
    fun releaseInputFocus() {
      focusManager.clearFocus()
    }

    /**
     * 退出搜索态（回到「这个库的媒体列表」）。
     *
     * 抽成函数是为了让**系统返回**与工具行上的搜索按钮走**同一条**路径 ——
     * 两处各写一份的话，快照 / 接回滚动位置这些副作用迟早会走偏。
     *
     * ⚠️ **退出搜索不重新加载列表**：浏览列表本来就有缓存，再拉一次纯属浪费，
     * 随机排序下还会整批换一个顺序（用户报障「返回后数据重载、随机排序又重排一遍」）。
     */
    fun exitSearch() {
      // 把「词 + 结果 + 滚动位置」整个拍成本地快照，再进时原样回放 ——
      // 不重搜、不闪中间态（点得多快都一样）
      lastSearchQuery = searchQuery
      lastSearchSnapshot =
        EmbySearchSnapshot(
          query = searchQuery,
          items = items,
          totalCount = totalCount,
          scrollIndex = gridState.firstVisibleItemIndex,
          scrollOffset = gridState.firstVisibleItemScrollOffset,
        )
      searchActive = false
      searchQuery = ""
      releaseInputFocus()
      // 回去时把离开前的滚动位置接回去（配合上面的 LaunchedEffect(cacheKey)）
      pendingBrowseRestore = true
      // ⚠️ 这里**不要**再 load(reset = true)。
      // cacheKey 一变，items / totalCount / isLoading / chineseHits 都会按 remember(cacheKey)
      // 从**浏览分桶**重新起值（缓存是 16 槽 LRU，搜索分桶挤不掉浏览分桶）；
      // 上面那个 LaunchedEffect(cacheKey) 命中缓存也不会重拉 —— 列表本来就能原样回来。
      // 多发这一枪只会白跑一次请求；而且随机排序是**服务端**出的（客户端 applyClientSort
      // 只用在搜索 / 中文字幕两条路上），回一批新顺序 = 列表被整个重排（用户报障）。
      // 唯一还要保留的副作用：作废在途请求，别让姗姗返回的搜索结果盖掉浏览列表。
      loadGeneration++
    }

    // 搜索态下系统返回（含边缘滑动）**先退搜索**，而不是直接退掉整个库页面。
    // 返回键对应「上一层」，而搜索是叠在列表之上的一层 —— 直接出栈不符合预期。
    // enabled = searchActive ⇒ 非搜索态完全不拦截，交给系统正常出栈。
    BackHandler(enabled = searchActive) { exitSearch() }

    Column(modifier = Modifier.fillMaxSize()) {
      // ── 1. 顶栏：返回 + 库名 + 搜索 ──
      TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          // 图标跟着搜索态走：没搜时是放大镜，进搜索后换成「放大镜 + 斜杠」。
          // 图标一直不变的话，用户进了搜索界面就找不到退出来的入口 ——
          // 同一个位置既是「进入」又是「退出」，长得却一模一样。
          //
          // 这里用 SearchOff 而不是通用的 ✕：左边紧挨着就是返回键（←），
          // 再摆一个 ✕ 会让同一个工具栏上出现两个「离开」性质的图标，
          // 一个退搜索、一个退整个库，很容易点错。SearchOff 保留放大镜意象，
          // 一眼还能看出它跟搜索有关。
          IconButton(onClick = {
            if (searchActive) {
              exitSearch()
            } else {
              searchActive = true
              val snap = lastSearchSnapshot
              if (snap != null) {
                // 有快照：恢复词本身，列表内容由搜索 effect 用快照回放
                pendingSearchRestore = true
                searchQuery = snap.query
              } else if (lastSearchQuery.isNotBlank()) {
                searchQuery = lastSearchQuery
              }
            }
          }) {
            Icon(
              imageVector = if (searchActive) Icons.Default.SearchOff else Icons.Default.Search,
              contentDescription = if (searchActive) "退出搜索" else "搜索",
            )
          }
        },
      )

      // ── 2. 分类行 ──
      // 搜索时藏起来：那一排「全部 / 继续播放 / 合集 / 收藏 / 文件夹」
      // 跟搜索结果没有对应关系，留着只是挤掉一整行，还容易让人以为结果按它筛过。
      // 只是不显示，分类状态本身保留，退出搜索回到原来的样子。
      // 只要是演员作品子页（personId 非空）就藏：那一页本身就是「已按该演员筛过」的结果，
      // 再摆一排「全部 / 继续播放 …」只会让人以为列表没被筛过
      if (includeItemTypes == null && !searchActive && personId == null) {
        CategoryChips(
          selected = category,
          onSelect = {
            category = it
            // 选完立刻落盘：下次进这个库（含从文件夹返回、重启 App）都停在这一档
            browserPreferences.embyLibraryCategory(libraryId).set(it.name)
          },
          // 「只看未看」快捷 chip：对应筛选里的 playedFilter，这里给一个一键入口 ——
          // 找「还没看的」是最高频的诉求，不该藏在筛选弹窗里。
          // 只在「未看 / 不限」之间循环，不动已看筛选（那个仍归筛选弹窗管）。
          unwatchedOnly = playedFilter == false,
          onToggleUnwatched = {
            playedFilter = if (playedFilter == false) null else false
            persistFilter()
          },
        )
      }

      // ── 3. 工具行：数量 + 随机播放 + 视图 + 排序 ──
      // 演员列表与「某演员作品」子页都不可随机播放，也没有样式 / 排序 / 再筛选可言，
      // 这些按钮留着只会让人点了没反应 —— 收掉，只保留左边的数量
      val hideToolbarButtons = isActorListMode || personId != null
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = when {
            // 「演员」分类统计的是演员人数，不是媒体条目数
            isActorListMode -> if (actorLoading) "加载演员中…" else "$actorTotal 位演员"
            // 客户端筛选（中文字幕）走全量扫描：扫的时候显示进度，扫完显示命中数。
            // 服务端 totalCount 是「整个库的条目数」，客户端筛过之后对不上，所以分开显示。
            isScanning -> "扫描中… 已扫 $scanScanned" +
              (if (totalCount > 0) " / $totalCount" else "") +
              " · 命中 ${visibleItems.size}"
            chineseSubsOnly -> buildString {
              append("命中 ${visibleItems.size} 项")
              if (scanScanned > 0) {
                append(" · 已扫 $scanScanned")
                if (totalCount > 0) append(" / $totalCount")
              }
            }
            totalCount > 0 -> "$totalCount 项"
            else -> ""
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))

        if (!hideToolbarButtons) {
          IconButton(onClick = { releaseInputFocus(); startRandomPlayback(favoritesOnly = false) }) {
            Icon(Icons.Default.Shuffle, contentDescription = "随机播放")
          }
          // 同上，只是范围再限定「已收藏」：避免随机到没看过的
          IconButton(onClick = { releaseInputFocus(); startRandomPlayback(favoritesOnly = true) }) {
            // 区别于上面的「随机播放」：用它自己的 Emby 收藏随机图标，
            // 原先用的 ShuffleOn 只比 Shuffle 多一条下划线，并排根本分不出来
            Icon(EmbyFavoriteRandomIcon, contentDescription = "随机播放收藏")
          }
          // 视界流：随机一批进仿抖音竖屏上下滑连播页。
          // 图标刻意不用「随机」语义 —— 前两个按钮已经把那层意思占满了，
          // 第三个得靠形状本身说话，否则一排三个图标看着像三个一样的按钮。
          IconButton(onClick = { releaseInputFocus(); startFeedPlayback(favoritesOnly = false) }) {
            Icon(VideoFeedIcon, contentDescription = "视界流")
          }
          // 筛选：有生效条件时图标高亮，让人一眼看出列表不是全量
          IconButton(onClick = { releaseInputFocus(); showFilterDialog = true }) {
            Icon(
              imageVector = Icons.Default.FilterAlt,
              contentDescription = "筛选",
              tint = if (hasActiveFilter) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          }
          IconButton(onClick = { releaseInputFocus(); showStyleDialog = true }) {
            Icon(Icons.Default.GridView, contentDescription = "视图样式")
          }
          SortMenu(
            currentSort = sortBy,
            currentOrder = sortOrder,
            onSortChange = { newSort ->
              browserPreferences.embyLibrarySortBy.set(newSort)
              // 换排序项就丢掉手动方向，回到该排序项的自然方向
              browserPreferences.embyLibrarySortOrder.set("")
            },
            onOrderChange = { newOrder ->
              browserPreferences.embyLibrarySortOrder.set(newOrder)
            },
            // 展开菜单之前先收焦点：菜单一关，焦点若已经不在输入框上，
            // 就没有「还给输入框」这回事，软键盘也就不会跟着弹出来
            onRequestClearFocus = ::releaseInputFocus,
          )
        }
      }

      // 兜底：排序方式 / 升降序一变，就确保焦点不在输入框上。
      // 菜单展开前已经清过一次，这里再兜一层 —— Popup 与主窗口之间的焦点交接
      // （以及随之而来的输入法显隐）在不同系统版本上时序不完全一致，
      // 与其猜哪一刀生效，不如两处都落下。搜索态以外没有输入框，清了也无害。
      LaunchedEffect(sortBy, sortOrder) {
        if (searchActive) focusManager.clearFocus()
      }

      // ── 联想词什么时候该自动收起 ──
      // 两条判据，都是「用户的心思已经不在联想上了」的信号：
      //  ① **键盘从弹起变回收起**（而不是「当前没弹键盘」）：打字结束的那一刻收起，
      //     把空间让给结果。用「变化」而不是「状态」判断是有意的 —— 进搜索页时键盘
      //     本来就没弹，用状态判断会一进来就收起，那才是真的莫名。
      //     键盘再次弹起（回去改词）→ 立刻展开。
      //  ② **用户开始滚动结果**：注意力明确转到结果上，本次搜索内保持收起。
      // 手动点标题行随时可以覆盖（见 onToggleCollapsed），且不会写进偏好。
      // ⚠️ 这几段必须放在 `if (searchActive)` **外面**：放进条件分支里的话，
      // 退出搜索的那一瞬间整块会先离开组合，`LaunchedEffect` 的重置分支根本不会执行。
      val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
      var imeWasVisible by remember { mutableStateOf(false) }
      LaunchedEffect(imeVisible, searchActive) {
        if (!searchActive) return@LaunchedEffect
        if (imeVisible) {
          imeWasVisible = true
          suggestAutoCollapsed = false
        } else if (imeWasVisible) {
          imeWasVisible = false
          suggestAutoCollapsed = true
        }
      }
      LaunchedEffect(gridState.isScrollInProgress, searchActive) {
        if (searchActive && gridState.isScrollInProgress && searchQuery.isNotBlank()) {
          suggestAutoCollapsed = true
        }
      }
      // 退出搜索时清掉自动档与键盘记忆：下次进来是干净状态，由上面的判据重新决定
      LaunchedEffect(searchActive) {
        if (!searchActive) {
          suggestAutoCollapsed = false
          imeWasVisible = false
        }
      }

      if (searchActive) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          placeholder = { Text("搜索媒体…") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(
            // 键盘上按「搜索」= 用户明确表态要搜这个词，直接落一条历史
            onSearch = {
              keyboardController?.hide()
              scope.launch { searchHistoryRepository.record(searchQuery) }
            },
          ),
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = {
                searchQuery = ""
                scope.launch { load(reset = true) }
              }) {
                Icon(Icons.Default.Clear, contentDescription = "清空")
              }
            }
          },
        )
        // 搜索联想：点一下就把候选词填进输入框，省掉手动敲完整个片名。
        // 高度由卡片自己控制（封顶 + 可收起 + 自动收起），不会把下面的结果挤没。
        val suggestionsCollapsed = suggestCollapsed || suggestAutoCollapsed
        EmbySearchSuggestionRow(
          suggestions = searchSuggestions,
          query = searchQuery,
          collapsed = suggestionsCollapsed,
          onToggleCollapsed = {
            // 手动点击一律作数：清掉自动档，再把偏好翻到「与当前相反」的那个状态。
            // 不清自动档的话，自动收起的当口点「展开」会毫无反应（自动档仍为 true）。
            val wantCollapsed = !suggestionsCollapsed
            suggestAutoCollapsed = false
            browserPreferences.embySearchSuggestCollapsed.set(wantCollapsed)
          },
          onPick = { keyword ->
            searchQuery = keyword
            keyboardController?.hide()
            scope.launch { searchHistoryRepository.record(keyword) }
          },
        )
        // 搜索的触发源不只是输入文字，还包括切排序、换类型筛选、开关「中文字幕」等 ——
        // 所以这里直接用 cacheKey 当 key：它已经聚合了所有会影响结果集的输入。
        //
        // 修的是一个真实 bug：之前只用 (searchQuery, searchFilters) 当 key，
        // 于是「在搜索结果里切排序」时这个 effect 不重跑，而 cacheKey 变化已经把
        // `isLoading = remember(cacheKey) { ... }` 重置成 true → 界面永远停在转圈。
        // 用 cacheKey 之后，凡是结果会变的情况都必然重新加载，不可能再卡住。
        //
        // 是否防抖按「变化来源」决定：打字等 400ms，其余离散操作（点排序 / 点筛选）立即重查，
        // 免得切个排序还要干等半秒。
        LaunchedEffect(cacheKey) {
          // 非搜索状态由上面那个 effect 负责（那条路径不需要防抖）
          if (searchQuery.isBlank()) return@LaunchedEffect
          // 「退出搜索再进来」的恢复路径：用退出时拍下的**本地快照**直接回放
          //（词 + 结果 + 滚动位置一起回）—— 不发请求、不看 cacheKey，
          // 切没切过分类、点得多快都一样。只在「刚进入搜索」的那一次生效，
          // 之后改词 / 换排序照常走防抖与网络。快照不可用就落到下面的正常搜索。
          if (pendingSearchRestore) {
            pendingSearchRestore = false
            val snap = lastSearchSnapshot
            if (snap != null && snap.query == searchQuery) {
              items = snap.items
              totalCount = snap.totalCount
              chineseHits = emptyMap()
              isLoading = false
              gridState.scrollToItem(snap.scrollIndex, snap.scrollOffset)
              return@LaunchedEffect
            }
          }
          if (searchQuery != lastTypedQuery) {
            lastTypedQuery = searchQuery
            kotlinx.coroutines.delay(400)
          }
          load(reset = true)
        }
        // 类型筛选：默认「全部」不加限制；勾了电影/合集/演员这类就按类型查。
        // 搜索里**只有这一处**类型筛选（原先下面还有一条标签完全相同的本地分栏，
        // 已删除 —— 两个一模一样的入口会让人不知道以哪个为准）。
        EmbySearchFilterRow(
          selected = searchFilters,
          onSelectedChange = { searchFilters = it },
        )
      }

      // ── 4. 媒体网格 ──
      // 用下拉刷新包裹：返回页面不会自动刷新，只有用户主动下拉才重新拉取
      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        // 演员分类下拉刷新要重拉演员，而不是去重拉媒体
        onRefresh = { if (isActorListMode) loadActors() else load(reset = true) },
        gridState = gridState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        when {
          // ── 「演员」分类：本库演员网格（头像 + 左下角作品数角标），点击进该演员的作品 ──
          isActorListMode && !searchActive && actorLoading && actorItems.isEmpty() ->
            // 演员是固定三列的头像网格，骨架屏也用三列，落下时不位移
            EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)

          isActorListMode && !searchActive && actorError != null && actorItems.isEmpty() ->
            EmbyEmptyState(
              message = actorError ?: "加载演员失败",
              buttonText = "重试",
              onAction = { scope.launch { loadActors() } },
              modifier = Modifier.align(Alignment.Center),
            )

          isActorListMode && !searchActive && actorItems.isEmpty() -> EmbyEmptyState(
            message = "该媒体库没有演员",
            buttonText = "刷新",
            onAction = { scope.launch { loadActors() } },
            modifier = Modifier.align(Alignment.Center),
          )

          isActorListMode && !searchActive -> {
            LazyVerticalGrid(
              state = gridState,
              // 人物只有一张头像图，网格固定海报（一行三个），不跟媒体卡片样式切换
              columns = GridCells.Fixed(3),
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(actorItems, key = { it.Id ?: it.Name ?: "" }) { person ->
                val s = server ?: return@items
                EmbyMediaCard(
                  title = person.Name.orEmpty(),
                  subtitle = null,
                  // 人物只有 Primary（头像），不跟着 cardStyle 跑去取 Backdrop
                  imageUrl = viewModel.imageUrl(s, person, "Primary", 480),
                  progress = null,
                  // /Persons 端点带 EnableUserData 时会回传 IsFavorite（见 EmbyClient.getActors），
                  // 已收藏的演员卡片右上角显示红心角标
                  isFavorite = person.UserData?.IsFavorite == true,
                  // 左下角角标 = 该演员在本库参与的作品数
                  badgeText = person.ChildCount?.takeIf { it > 0 }?.let { "$it 部" },
                  style = EmbyCardStyle.POSTER,
                  fillWidth = true,
                  onClick = {
                    val pid = person.Id ?: return@EmbyMediaCard
                    // 与详情页点演职员头像统一：都进 EmbyPersonScreen（右上角收藏红心、
                    // 全库作品清单、三列海报卡）。原先这里进的是带 personId 预筛的媒体库页
                    // —— 和详情页那条路由是两个页面，红心与卡片规格都对不上（用户反馈），
                    // 现在收口到同一条路由。
                    backStack.add(
                      EmbyPersonScreen(
                        personId = pid,
                        personName = person.Name.orEmpty(),
                        personImageTag = person.ImageTags["Primary"],
                      ),
                    )
                  },
                  // 长按弹出操作菜单（复用媒体条目那套）：演员只支持「收藏 / 取消收藏」，
                  // 已看 / 编辑元数据 / 刮削 / 删除这些对 Person 没有意义（菜单内部按类型裁剪）
                  onLongClick = if (person.Id != null) {
                    { offset -> actionTarget = person; menuAnchor = offset }
                  } else {
                    null
                  },
                )
              }
            }
          }

          // 刚点开搜索、还没输入：顶掉网格，用历史词占位
          searchActive && searchQuery.isBlank() -> SearchHistoryPanel(
            history = searchHistory,
            onPick = { keyword -> searchQuery = keyword },
            onRemove = { keyword -> scope.launch { searchHistoryRepository.remove(keyword) } },
            onClearAll = { scope.launch { searchHistoryRepository.clear() } },
            modifier = Modifier
              .align(Alignment.TopStart)
              .fillMaxWidth(),
            emptyHint = "输入关键词，搜索当前媒体库",
          )

          isLoading && items.isEmpty() -> {
            // 骨架屏替代居中转圈：先把「待会儿会出现什么」按当前版式画出来，
            // 内容真正落下来时不会整屏跳变。
            if (isScanning) {
              // 「中文字幕」全量扫描可能要拉几十页：光有骨架屏看不出还在动，
              // 这一档额外给一个文字进度，其余情况直接用骨架屏。
              Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                CircularProgressIndicator()
                Text(
                  text = "正在扫描媒体库… 已扫 $scanScanned" +
                    (if (totalCount > 0) " / $totalCount" else ""),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else if (viewMode == EmbyLibraryViewMode.COMPACT) {
              EmbySkeletonList()
            } else {
              EmbySkeletonGrid(
                columns = if (cardStyle == EmbyCardStyle.POSTER) {
                  gridColumns.coerceIn(2, 6)
                } else {
                  3
                },
                ratio = cardStyle.ratio,
              )
            }
          }

          error != null && items.isEmpty() -> EmbyEmptyState(
            message = error ?: "加载失败",
            buttonText = "重试",
            onAction = { scope.launch { load(reset = true) } },
            modifier = Modifier.align(Alignment.Center),
          )

          items.isEmpty() && !isLoading -> EmbyEmptyState(
            message = if (chineseSubsOnly) "没有匹配「中文字幕」标记的媒体" else "没有找到媒体",
            buttonText = "刷新",
            onAction = { scope.launch { load(reset = true) } },
            modifier = Modifier.align(Alignment.Center),
          )

          else -> {
            when (viewMode) {
              // ── 紧凑列表：一行一条（左侧小海报 + 片名 / 副标题 + 心形），信息密度最高 ──
              EmbyLibraryViewMode.COMPACT -> LazyVerticalGrid(
                state = gridState,
                // 复用网格容器、只留一列，而不是换成 LazyColumn：这样能共用同一个
                // 下拉刷新所依赖的 gridState，不必再维护第二份滚动状态。
                columns = GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                items(displayItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                  val s = server ?: return@items
                  EmbyCompactRow(
                    title = viewModel.displayTitle(item),
                    subtitle = itemSubtitle(item),
                    imageUrl = viewModel.imageUrl(s, item, "Primary", 240),
                    progress = itemProgress(item),
                    isFavorite = item.UserData?.IsFavorite == true,
                    onClick = { openItem(item, backStack, s, context) },
                    onLongClick = { offset ->
                      actionTarget = item
                      menuAnchor = offset
                    },
                    onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
                  )
                }
              }

              // ── 网格 / 年份时间轴：都走同一个网格，时间轴只是多插了年份标题 ──
              else -> LazyVerticalGrid(
                state = gridState,
                // 海报按用户选的每行个数（2~6）；横版 / 横幅样式本身更宽，
                // 仍按最小宽度自适应，避免被压得过小。
                columns =
                  if (cardStyle == EmbyCardStyle.POSTER) {
                    GridCells.Fixed(gridColumns.coerceIn(2, 6))
                  } else {
                    GridCells.Adaptive(minSize = cardStyle.width)
                  },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                if (viewMode == EmbyLibraryViewMode.YEAR) {
                  // 年份时间轴：按播出年份分组，新年份在前；没有年份的归到「年份未知」。
                  // 标题横跨整行（span = 整行），不会被挤在某一格里。
                  val grouped = displayItems
                    .groupBy { it.ProductionYear ?: 0 }
                    // 显式给出泛型参数：`compareByDescending { it }` 的 lambda 参数类型
                    // 在这种链式上下文里推不出来，会报「缺少 operator 修饰符」
                    .toSortedMap(compareByDescending<Int> { it })
                  grouped.forEach { (year, group) ->
                    item(
                      key = "year_header_$year",
                      span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                    ) {
                      EmbyYearHeader(if (year > 0) "$year" else "年份未知", group.size)
                    }
                    items(group, key = { it.Id ?: it.Name ?: "" }) { item ->
                      val s = server ?: return@items
                      EmbyLibraryMediaCell(
                        item = item,
                        server = s,
                        viewModel = viewModel,
                        cardStyle = cardStyle,
                        folderCovers = folderCovers,
                        onClick = { openItem(item, backStack, s, context) },
                        onLongClick = { offset ->
                          actionTarget = item
                          menuAnchor = offset
                        },
                        onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
                      )
                    }
                  }
                } else {
                  items(displayItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                    val s = server ?: return@items
                    EmbyLibraryMediaCell(
                      item = item,
                      server = s,
                      viewModel = viewModel,
                      cardStyle = cardStyle,
                      folderCovers = folderCovers,
                      onClick = { openItem(item, backStack, s, context) },
                      onLongClick = { offset ->
                        actionTarget = item
                        menuAnchor = offset
                      },
                      onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

      // ── 长按操作框：文件夹走「扫描 / 刷新元数据」，媒体走完整的那一套 ──
      val target = actionTarget
      val targetServer = server
      if (target != null && targetServer != null) {
        // 锚定到长按的那一点：菜单从手指旁边展开
        Box(
          modifier = Modifier.offset {
            androidx.compose.ui.unit.IntOffset(
              menuAnchor.x.roundToInt(),
              menuAnchor.y.roundToInt(),
            )
          },
        ) {
        val targetId = target.Id
        val targetName = viewModel.displayTitle(target)
        if (isScannableFolder(target)) {
          EmbyItemActionsDialog(
            name = targetName,
            kindLabel = "文件夹",
            onDismissRequest = { actionTarget = null },
            onScan = {
              actionTarget = null
              runEmbyLibraryAction(
                context = context,
                scope = scope,
                server = targetServer,
                itemId = targetId,
                name = targetName,
                okMessage = "已通知服务器扫描「$targetName」，稍后下拉刷新查看新文件",
                action = { srv, id -> runCatching { viewModel.scanLibrary(srv, id) } },
              )
            },
            onRefreshMetadata = { replaceMetadata, replaceImages ->
              actionTarget = null
              runEmbyLibraryAction(
                context = context,
                scope = scope,
                server = targetServer,
                itemId = targetId,
                name = targetName,
                okMessage = "已通知服务器刷新「$targetName」的元数据，稍后下拉刷新查看",
                action = { srv, id ->
                  runCatching { viewModel.refreshLibraryMetadata(srv, id, replaceMetadata, replaceImages) }
                },
              )
            },
          )
        } else {
          EmbyMediaActionsDialog(
            server = targetServer,
            item = target,
            viewModel = viewModel,
            onDismissRequest = { actionTarget = null },
            onChanged = { updated ->
              val id = updated.Id
              if (id != null) {
                items = items.map { if (it.Id == id) updated else it }
                // 演员分类的长按（收藏/取消收藏演员）改的是 actorItems 这份独立数据，
                // 不同步的话菜单关了红心不刷新
                if (actorItems.any { it.Id == id }) {
                  actorItems = actorItems.map { if (it.Id == id) updated else it }
                }
              }
            },
            onDeleted = { id ->
              items = items.filterNot { it.Id == id }
              EmbyLibraryCache.removeItem(id)
            },
            // 图片 / 元数据换过之后，列表里这条的 ImageTags 已经旧了，重拉一次才看得到新封面
            onImagesChanged = { scope.launch { load(reset = true) } },
          )
        }
        } // 关闭锚定 Box（菜单跟着长按的那一点走）
      }

    // ── 筛选弹窗：类型 / 标签 / 年份 / 官方分级 / 评分 / 只看收藏 ──
    // 弹窗里改的是草稿副本，只有点「确定」才写回，避免每点一个 chip 就发一次请求。
    // 点弹窗外部 / 返回键 = 放弃本次改动。
    // ── 筛选面板：底部上划，每个维度一个下拉；选中立即生效，不用点确定 ──
    // 之所以不用「草稿 + 确定」：筛选的结果在下面列表里是实时可见的，
    // 每点一项就刷新一次，比「点完确定才知道对不对」少一次试错。
    if (showFilterDialog) {
      // skipPartiallyExpanded：面板只有「展开 / 收起」两态，不允许停在半展开。
      // 半展开态会和内容里的下拉列表抢嵌套滚动 —— 表现为拖不动、滑一下就
      // 变成拖面板、点击偶尔被吞。
      val filterSheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(
        onDismissRequest = { showFilterDialog = false },
        sheetState = filterSheetState,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "筛选",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f),
            )
            // 恢复默认：清空全部条件并落盘，面板保持打开，列表立刻回到全量
            TextButton(onClick = {
              selectedGenres = emptySet()
              selectedTags = emptySet()
              selectedYears = emptySet()
              selectedRatings = emptySet()
              minRating = null
              favoriteOnly = false
              selectedPersonIds = emptySet()
              selectedStudioIds = emptySet()
              playedFilter = null
              hdFilter = null
              threeDFilter = null
              subtitlesFilter = null
              chineseSubsOnly = false
              persistFilter()
            }) {
              Text("恢复默认")
            }
          }

          // 类型：多选
          FilterDropdown(
            label = "类型",
            options = filterOptions.genres,
            selectedNames = filterOptions.genres.filter { it in selectedGenres }.toSet(),
            onToggle = { name ->
              selectedGenres = if (name in selectedGenres) selectedGenres - name else selectedGenres + name
              persistFilter()
            },
            onClear = { selectedGenres = emptySet(); persistFilter() },
          )
          // 标签：多选
          FilterDropdown(
            label = "标签",
            options = filterOptions.tags,
            selectedNames = filterOptions.tags.filter { it in selectedTags }.toSet(),
            onToggle = { name ->
              selectedTags = if (name in selectedTags) selectedTags - name else selectedTags + name
              persistFilter()
            },
            onClear = { selectedTags = emptySet(); persistFilter() },
          )
          // 年份：下拉里是字符串，切回 Int 再存（多选）
          FilterDropdown(
            label = "年份",
            options = filterOptions.years.map { it.toString() },
            selectedNames = selectedYears.map { it.toString() }.toSet(),
            onToggle = { v ->
              v.toIntOrNull()?.let { y ->
                selectedYears = if (y in selectedYears) selectedYears - y else selectedYears + y
                persistFilter()
              }
            },
            onClear = { selectedYears = emptySet(); persistFilter() },
          )
          // 官方分级：多选
          FilterDropdown(
            label = "官方分级",
            options = filterOptions.officialRatings,
            selectedNames = filterOptions.officialRatings.filter { it in selectedRatings }.toSet(),
            onToggle = { name ->
              selectedRatings = if (name in selectedRatings) selectedRatings - name else selectedRatings + name
              persistFilter()
            },
            onClear = { selectedRatings = emptySet(); persistFilter() },
          )
          // 演员 / 导演：显示名字，按 Id 筛（同名不同人只能靠 Id 区分），多选
          FilterDropdown(
            label = "演员 / 导演",
            options = filterOptions.persons.map { it.name },
            selectedNames = filterOptions.persons
              .filter { it.id in selectedPersonIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val person = filterOptions.persons.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedPersonIds = if (person.id in selectedPersonIds) {
                selectedPersonIds - person.id
              } else {
                selectedPersonIds + person.id
              }
              persistFilter()
            },
            onClear = { selectedPersonIds = emptySet(); persistFilter() },
          )
          // 工作室：多选
          FilterDropdown(
            label = "工作室",
            options = filterOptions.studios.map { it.name },
            selectedNames = filterOptions.studios
              .filter { it.id in selectedStudioIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val studio = filterOptions.studios.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedStudioIds = if (studio.id in selectedStudioIds) {
                selectedStudioIds - studio.id
              } else {
                selectedStudioIds + studio.id
              }
              persistFilter()
            },
            onClear = { selectedStudioIds = emptySet(); persistFilter() },
          )
          // 播放状态：单选，再点一次已选的那项就取消
          FilterDropdown(
            label = "播放状态",
            options = listOf("已看", "未看"),
            selectedNames = when (playedFilter) {
              true -> setOf("已看")
              false -> setOf("未看")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "已看"
              playedFilter = if (playedFilter == target) null else target
              persistFilter()
            },
            onClear = { playedFilter = null; persistFilter() },
          )
          // 清晰度：单选
          FilterDropdown(
            label = "清晰度",
            options = listOf("高清", "标清"),
            selectedNames = when (hdFilter) {
              true -> setOf("高清")
              false -> setOf("标清")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "高清"
              hdFilter = if (hdFilter == target) null else target
              persistFilter()
            },
            onClear = { hdFilter = null; persistFilter() },
          )
          // 3D：单选
          FilterDropdown(
            label = "3D",
            options = listOf("3D", "非 3D"),
            selectedNames = when (threeDFilter) {
              true -> setOf("3D")
              false -> setOf("非 3D")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "3D"
              threeDFilter = if (threeDFilter == target) null else target
              persistFilter()
            },
            onClear = { threeDFilter = null; persistFilter() },
          )
          // 字幕：单选
          FilterDropdown(
            label = "字幕",
            options = listOf("有字幕", "无字幕"),
            selectedNames = when (subtitlesFilter) {
              true -> setOf("有字幕")
              false -> setOf("无字幕")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "有字幕"
              subtitlesFilter = if (subtitlesFilter == target) null else target
              persistFilter()
            },
            onClear = { subtitlesFilter = null; persistFilter() },
          )
          // 评分：单选
          FilterDropdown(
            label = "评分",
            options = RATING_OPTIONS.map { it.first }.filter { it != "不限" },
            selectedNames = RATING_OPTIONS
              .firstOrNull { it.second == minRating }
              ?.let { setOf(it.first) }
              ?: emptySet(),
            onToggle = { v ->
              val target = RATING_OPTIONS.firstOrNull { it.first == v }?.second
              minRating = if (minRating == target) null else target
              persistFilter()
            },
            onClear = { minRating = null; persistFilter() },
          )

          // 中文字幕：按路径标记做客户端匹配（Emby 服务端没有这类筛选）。
          // 打开后会把整个库逐页扫一遍（服务端筛不了，只能拉全再筛），
          // 标记列表可编辑，规则见 [ChineseSubtitleMarks]。
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "中文字幕（按路径标记）",
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = "匹配 ${chineseMarks.markers.size} 个标记：" +
                  chineseMarks.markers.take(6).joinToString(" / ") + " …",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = chineseSubsOnly,
              onCheckedChange = {
                chineseSubsOnly = it
                persistFilter()
              },
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "匹配范围：" +
                if (chineseMarks.matchWholePath) "整条路径（含目录名）" else "仅文件名",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showMarksEditor = true }) {
              Text("编辑标记")
            }
          }

          // 只看收藏：打开 = 只在收藏里套用上面的筛选；关掉 = 不限（不是「只看未收藏」）
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "只看收藏",
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = favoriteOnly,
              onCheckedChange = {
                favoriteOnly = it
                persistFilter()
              },
            )
          }
        }
      }
    }


    // 视图设置：**排布方式**（网格 / 紧凑列表 / 年份时间轴）+ 卡片样式 + 每行个数。
    // 合成一个弹窗（需求里说的「合并视图入口」）：原来这里只有卡片样式，
    // 现在把「怎么排」「排几个」也收进来，一处就能把整个列表外观调完。
    if (showStyleDialog) {
      AlertDialog(
        onDismissRequest = { showStyleDialog = false },
        title = { Text("视图") },
        text = {
          Column(
            modifier = Modifier
              .heightIn(max = 440.dp)
              .verticalScroll(rememberScrollState()),
          ) {
            Text("排布方式", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            EmbyLibraryViewMode.entries.forEach { mode ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(
                  selected = mode == viewMode,
                  onClick = { browserPreferences.embyLibraryViewMode.set(mode.key) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.label)
              }
            }

            // 紧凑列表没有「卡片」这个概念，样式与列数对它都没有意义，整块收掉
            if (viewMode != EmbyLibraryViewMode.COMPACT) {
              Spacer(modifier = Modifier.height(12.dp))
              Text("卡片样式", style = MaterialTheme.typography.titleSmall)
              Spacer(modifier = Modifier.height(4.dp))
              EmbyCardStyle.entries.forEach { style ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  RadioButton(
                    selected = style == cardStyle,
                    onClick = { browserPreferences.embyLibraryCardStyle.set(style.name) },
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(style.label)
                }
              }
            }

            // 列数只对「海报」生效：背景图 / 横幅是宽图，列数由自身最小宽度自适应决定，
            // 硬塞进固定列数会被压得又窄又小。
            if (viewMode != EmbyLibraryViewMode.COMPACT && cardStyle == EmbyCardStyle.POSTER) {
              Spacer(modifier = Modifier.height(12.dp))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = "每行个数",
                  style = MaterialTheme.typography.titleSmall,
                  modifier = Modifier.weight(1f),
                )
                IconButton(
                  onClick = {
                    browserPreferences.embyLibraryGridColumns
                      .set((gridColumns - 1).coerceAtLeast(2))
                  },
                  enabled = gridColumns > 2,
                ) {
                  Icon(Icons.Default.Remove, contentDescription = "减少每行个数")
                }
                Text(
                  text = gridColumns.coerceIn(2, 6).toString(),
                  style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                  onClick = {
                    browserPreferences.embyLibraryGridColumns
                      .set((gridColumns + 1).coerceAtMost(6))
                  },
                  enabled = gridColumns < 6,
                ) {
                  Icon(Icons.Default.Add, contentDescription = "增加每行个数")
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showStyleDialog = false }) {
            Text("完成")
          }
        },
      )
    }

    // 中文字幕标记编辑器：改完写回偏好 → cacheKey 变化 → 自动重新扫描一遍。
    // 之所以要给用户开口子：库里出现新的标记写法（比如新资源组用 `[CM]`）时，
    // 不该为了加一个字符串重新发版。
    if (showMarksEditor) {
      // 草稿放在弹窗外层：确认按钮要读到它，放在 text 里就出了作用域
      var marksDraft by remember(chineseMarksRaw) {
        mutableStateOf(chineseMarks.markers.joinToString("\n"))
      }
      var wholePathDraft by remember(chineseMarksRaw) {
        mutableStateOf(chineseMarks.matchWholePath)
      }
      AlertDialog(
        onDismissRequest = { showMarksEditor = false },
        title = { Text("中文字幕标记") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "一行一个（逗号 / 空格分隔也行）。汉字标记按整词或子串匹配；" +
                "其余标记两侧都要求边界，所以 -C 不会命中 -CD / -CH / -CM。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = marksDraft,
              onValueChange = { marksDraft = it },
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 220.dp),
              textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "匹配整条路径（关掉只匹配文件名）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
              )
              Switch(checked = wholePathDraft, onCheckedChange = { wholePathDraft = it })
            }
            Text(
              text = "生效 ${ChineseSubtitleMarks.parse(marksDraft, wholePathDraft).markers.size} 个标记",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        confirmButton = {
          TextButton(onClick = {
            val marks = ChineseSubtitleMarks.parse(marksDraft, wholePathDraft)
            browserPreferences.embyChineseSubtitleMarks.set(marks.toJson())
            showMarksEditor = false
          }) {
            Text("保存并重扫")
          }
        },
        dismissButton = {
          Row {
            TextButton(onClick = {
              marksDraft = ChineseSubtitleMarks.DEFAULT_MARKERS.joinToString("\n")
              wholePathDraft = true
            }) {
              Text("恢复默认")
            }
            TextButton(onClick = { showMarksEditor = false }) {
              Text("取消")
            }
          }
        },
      )
    }
  }
}

/**
 * 媒体库网格里的单张媒体卡。
 *
 * 抽成独立函数是为了让「网格」与「年份时间轴」共用同一份渲染逻辑 ——
 * 时间轴相对网格只多了年份组标题，卡片本身完全一样。
 */
@Composable
private fun EmbyLibraryMediaCell(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  cardStyle: EmbyCardStyle,
  folderCovers: MutableMap<String, List<String>>,
  onClick: () -> Unit,
  onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)?,
  onToggleFavorite: (() -> Unit)?,
) {
  val itemId = item.Id
  // 文件夹 / 合集这类容器条目自身没有封面图，改用内部视频的缩略图拼宫格
  val needsMosaic = itemId != null && isFolderLike(item) && item.ImageTags["Primary"] == null
  val folderCover: List<String>? = if (needsMosaic && itemId != null) {
    val cached = folderCovers[itemId]
    LaunchedEffect(itemId, server.id) {
      if (cached == null && EmbyFolderCoverCache.beginLoad(itemId)) {
        val urls = viewModel.loadFolderCoverUrls(server, itemId, 4)
        EmbyFolderCoverCache.put(itemId, urls)
        folderCovers[itemId] = urls
      }
    }
    cached
  } else {
    null
  }

  EmbyMediaCard(
    title = viewModel.displayTitle(item),
    subtitle = itemSubtitle(item),
    imageUrl = viewModel.imageUrl(server, item, imageTypeFor(cardStyle), 480),
    fallbackImageUrl = viewModel.imageUrl(server, item, "Primary", 480),
    mosaicUrls = folderCover,
    // 搜索时勾「演员」搜出来的 Person 也带作品数，跟演员分类里同一套角标
    badgeText = item.ChildCount?.takeIf { item.Type == "Person" && it > 0 }?.toString(),
    progress = itemProgress(item),
    isFavorite = item.UserData?.IsFavorite == true,
    onClick = onClick,
    style = cardStyle,
    fillWidth = cardStyle == EmbyCardStyle.POSTER,
    onLongClick = onLongClick,
    onToggleFavorite = onToggleFavorite,
  )
}

/**
 * 紧凑列表的一行。
 *
 * 左侧一块 3:4 小海报（进度条贴在它底部），右侧片名 / 副标题，最右是心形快捷收藏。
 * 不放封面宫格、作品数角标这类装饰 —— 这一档的诉求就是「一屏扫到尽可能多条」。
 */
@Composable
private fun EmbyCompactRow(
  title: String,
  subtitle: String?,
  imageUrl: String?,
  progress: Float?,
  isFavorite: Boolean,
  onClick: () -> Unit,
  onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)?,
  onToggleFavorite: (() -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .combinedClickable(
        onClick = onClick,
        // 紧凑行拿不到手指坐标（combinedClickable 不回传），长按菜单就锚在左上角。
        // 这一档本来就是「快速扫列表」的用法，菜单落哪里不影响可用性。
        onLongClick = { onLongClick?.invoke(androidx.compose.ui.geometry.Offset.Zero) },
      )
      .padding(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .width(52.dp)
        .aspectRatio(3f / 4f)
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = title,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
      )
      if (progress != null && progress > 0f) {
        EmbyProgressBar(
          progress = progress,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(3.dp),
        )
      }
    }
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    val onFav = onToggleFavorite
    if (onFav != null) {
      IconButton(onClick = onFav) {
        Icon(
          imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
          contentDescription = if (isFavorite) "取消收藏" else "收藏",
          tint = if (isFavorite) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier.size(20.dp),
        )
      }
    } else if (isFavorite) {
      // 快捷收藏关闭：退回只读角标，只在已收藏时露一颗实心红心，不占可点区域
      Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = "已收藏",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .padding(horizontal = 8.dp)
          .size(20.dp),
      )
    }
  }
}

/** 年份时间轴的组标题：横跨整行，左边年份、右边条数 */
@Composable
private fun EmbyYearHeader(year: String, count: Int) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = year,
      style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = "$count 项",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 搜索联想：输入框下方浮出一张圆角建议卡，一行一个候选词，点一下直接填进输入框。
 *
 * ## 高度为什么要「封顶 + 可收起」
 * 联想只是**顺手补全的参考**，真正的主角是下面的搜索结果。早先这张卡会随候选条数
 * 长到五行（≈220dp），键盘一弹，下面 `weight(1f)` 的结果网格只剩一条缝。
 * 所以这里改三件事：
 *  1. **高度封顶**：展开时最多三行出头（[SUGGEST_LIST_MAX_HEIGHT]），更多候选在卡片
 *     内部滚动 —— 是「随内容长、但长到上限就停」的弹性，不是写死一个固定高度；
 *  2. **可收起**：点标题行即可整卡收起（只留一行），收起状态记在偏好里，
 *     不需要联想的用户收一次就一直是收起的；
 *  3. **也会自己收**：打字结束（键盘收起）或用户开始滚动结果时自动收起、把空间让给结果，
 *     回去改词（键盘弹起）又自动展开。判据见调用点，这里只管显示。
 *
 * 仍不用覆盖式下拉菜单：结果网格就在下面，盖住它会让人没法边看边改词。
 */
@Composable
private fun EmbySearchSuggestionRow(
  suggestions: List<String>,
  query: String,
  collapsed: Boolean,
  onToggleCollapsed: () -> Unit,
  onPick: (String) -> Unit,
) {
  if (suggestions.isEmpty()) return
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    // ── 标题行：整行可点，用来收起 / 展开 ──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onToggleCollapsed() }
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "联想词",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = "共 ${suggestions.size} 条",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      if (collapsed) {
        Text(
          text = "展开",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
      }
      Icon(
        imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
        contentDescription = if (collapsed) "展开联想词" else "收起联想词",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }

    if (!collapsed) {
      Column(
        // 封顶三行出头：再多就在这张卡里滚动，不去挤下面的结果网格
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = SUGGEST_LIST_MAX_HEIGHT)
          .verticalScroll(rememberScrollState()),
      ) {
        suggestions.forEachIndexed { index, keyword ->
          if (index > 0) {
            HorizontalDivider(
              modifier = Modifier.padding(start = 36.dp),
              thickness = 0.5.dp,
              color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
          }
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPick(keyword) }
              .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = highlightMatch(keyword, query),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
  }
}

/** 联想列表展开时的最大高度：约三行半（每行 ≈41dp + 分隔线），再多就在卡内滚动 */
private val SUGGEST_LIST_MAX_HEIGHT = 144.dp

/** 把 [query] 在 [text] 里命中的那一段加粗，其余保持常规字重 —— 便于一眼对上是哪几个字匹配上的。 */
private fun highlightMatch(text: String, query: String): AnnotatedString {
  val q = query.trim()
  if (q.isEmpty()) return AnnotatedString(text)
  val start = text.indexOf(q, ignoreCase = true)
  if (start < 0) return AnnotatedString(text)
  val end = start + q.length
  return buildAnnotatedString {
    append(text.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(start, end)) }
    append(text.substring(end))
  }
}

/** 不同卡片样式使用的图片类型：横版样式优先用背景图，海报用主封面 */
private fun imageTypeFor(style: EmbyCardStyle): String =
  if (style == EmbyCardStyle.POSTER) "Primary" else "Backdrop"

/**
 * 媒体库的**视图模式**。与 [EmbyCardStyle]（卡片长什么样）是两个正交的轴：
 * 这个决定「怎么排」，那个决定「每张卡长什么样」。
 *
 * - [GRID]：网格（一行 2~6 个，样式可选海报 / 背景图 / 横幅）—— 默认
 * - [COMPACT]：紧凑列表，一行一条，左侧小图 + 标题 / 副标题，信息密度最高
 * - [YEAR]：年份时间轴，按播出年份分组的网格，找老片快
 */
private enum class EmbyLibraryViewMode {
  GRID,
  COMPACT,
  YEAR,
  ;

  /** 持久化键（英文），**不要改** —— 改了老用户的视图偏好会失效 */
  val key: String
    get() =
      when (this) {
        GRID -> "Grid"
        COMPACT -> "Compact"
        YEAR -> "Year"
      }

  val label: String
    get() =
      when (this) {
        GRID -> "网格"
        COMPACT -> "紧凑列表"
        YEAR -> "年份时间轴"
      }

  companion object {
    fun fromKey(raw: String?): EmbyLibraryViewMode =
      entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: GRID
  }
}

/**
 * 库内分类筛选。
 *
 * @param itemTypes 传给 Emby 的 IncludeItemTypes
 * @param filters 传给 Emby 的 Filters
 */
private enum class EmbyCategory(
  val label: String,
  val itemTypes: List<String>?,
  val filters: List<String>?,
) {
  ALL("全部", null, null),
  RESUMABLE("继续播放", null, listOf("IsResumable")),
  BOXSET("合集", listOf("BoxSet"), null),
  FAVORITE("收藏", null, listOf("IsFavorite")),
  FOLDER("文件夹", listOf("Folder", "CollectionFolder", "UserView"), null),
  /**
   * 演员：不是媒体条目，是**本库出现过的演员**。
   * 走 /Persons 端点单独拉（见 loadActors），不进媒体那套分页 / 筛选 / 扫描。
   */
  ACTOR("演员", null, null),
}

@Composable
private fun CategoryChips(
  selected: EmbyCategory,
  onSelect: (EmbyCategory) -> Unit,
  /** 「只看未看」快捷 chip 是否打开 */
  unwatchedOnly: Boolean = false,
  onToggleUnwatched: (() -> Unit)? = null,
) {
  androidx.compose.foundation.lazy.LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(EmbyCategory.entries.size) { index ->
      val category = EmbyCategory.entries[index]
      FilterChip(
        selected = category == selected,
        onClick = { onSelect(category) },
        label = { Text(category.label) },
      )
    }
    // 「只看未看」：与分类并列的快捷开关 —— 分类管「看哪个来源」，
    // 它管「看没看过」，两个轴独立，所以并排放而不是塞进分类里。
    if (onToggleUnwatched != null) {
      item(key = "unwatched_only") {
        FilterChip(
          selected = unwatchedOnly,
          onClick = onToggleUnwatched,
          label = { Text("只看未看") },
          leadingIcon = if (unwatchedOnly) {
            {
              Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            }
          } else {
            null
          },
        )
      }
    }
  }
}

/**
 * 「幽灵条目」：服务端偶尔混进列表、但实际上打不开的条目。
 *
 * 典型来源：库里放着指向远程 ISO 的 strm（文件内容就是 `http://cdn/xxx.iso` 这种 URL）。
 * Emby 在解析它时会把 URL 的 scheme 部分当成一个**名为 `http:` 的条目**返回，
 * Id 也跟着变成那个 URL 片段。结果就是：
 *
 * - 列表比官方客户端多出一条（用户实测：别的客户端 40 个，这里 41 个）；
 * - 点它一下就闪退 —— 拿一个「不是条目 Id 的字符串」去当 ParentId / ItemId 发请求，
 *   后续拼出来的地址是坏的。
 *
 * 判据刻意只取几条很保守的特征，正常媒体（Id 是数字 / GUID、名字是片名）不会被误伤：
 *
 * - Id 里带 `://`（Emby 的条目 Id 永远不含这个）；
 * - 名字就叫 `http:` / `https:`（URL scheme 被当成目录名时的产物）；
 * - 名字 / Id 里出现 JSON 的 `"键":` 片段 —— 元数据文件里是一段 JSON 时，
 *   Emby 会把其中一节解析成「条目」，名字长成 `frames":2670` 这样；
 * - 名字以 `{` / `[` 开头（整段 JSON 被当成名字）。
 */
private val JSON_FRAGMENT = Regex("\"[A-Za-z_][A-Za-z0-9_]*\"\\s*:")

private fun isGhostItem(item: EmbyItem): Boolean {
  if (item.Id?.contains("://") == true) return true
  val name = item.Name?.trim().orEmpty()
  if (name.equals("http:", ignoreCase = true) || name.equals("https:", ignoreCase = true)) return true
  if (JSON_FRAGMENT.containsMatchIn(name)) return true
  if (item.Id?.let { JSON_FRAGMENT.containsMatchIn(it) } == true) return true
  return name.startsWith("{") || name.startsWith("[")
}

/**
 * 单击媒体项：容器类继续下钻，其余打开详情页。
 *
 * - 影视剧（Series）→ 季列表
 * - 季（Season）→ 该季的剧集列表
 * - 其它（电影 / 单集 / 视频）→ 详情页，播放由详情页发起
 *
 * [context] 只用来在「打不开」时弹一句话：这类条目（见 [isGhostItem]）以前是静默崩溃，
 * 现在至少让用户知道为什么没反应。
 */
private fun openItem(
  item: EmbyItem,
  backStack: androidx.navigation3.runtime.NavBackStack<Screen>,
  server: EmbyServer,
  context: android.content.Context,
) {
  val id = item.Id
  if (id.isNullOrBlank() || id.contains("://")) {
    Toast.makeText(context, "这个条目打不开（不是有效的媒体 ID）", Toast.LENGTH_SHORT).show()
    return
  }
  runCatching {
    when (item.Type) {
      "Series" -> backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = item.Name ?: "",
          collectionType = item.CollectionType,
          includeItemTypes = listOf("Season"),
        ),
      )

      "Season" -> backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = item.Name ?: "",
          includeItemTypes = listOf("Episode"),
        ),
      )

      "CollectionFolder", "Folder", "UserView", "BoxSet" -> backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = item.Name ?: "",
          collectionType = item.CollectionType,
        ),
      )

      // 「演员」筛选搜出来的是 Person，它本身不是可播放媒体 —— 点进去看 TA 的作品列表
      "Person" -> backStack.add(
        EmbyPersonScreen(
          personId = id,
          personName = item.Name ?: "",
          personImageTag = item.ImageTags["Primary"],
        ),
      )

      else -> backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
    }
  }.onFailure {
    Toast.makeText(
      context,
      "打开失败：${it.message ?: "未知错误"}",
      Toast.LENGTH_SHORT,
    ).show()
  }
}

/**
 * 筛选弹窗里的一组可多选 chip（类型 / 标签 / 年份 / 官方分级 / 评分共用）。
 *
 * 选项为空时整组隐藏：说明该维度服务端没给或这个库用不上，
 * 与其显示一个空标题，不如直接不占地方。
 */
/**
 * 筛选面板里的「下拉选择」：一行摘要 + 点开后纵向铺开全部选项。
 *
 * 原来是横向滑动的 chip 组 —— 类型、演员这种动辄几十项的维度横向滑根本没法找，
 * 而且滑到后面完全不知道还剩多少。改成下拉后一屏能扫十几项，末尾还有「不限」一键清空。
 *
 * 选项为空时整组隐藏：说明这个维度服务端没给或该库没有，不占地方。
 * 展开区最高 260dp，超出后自己在组内滚动，不会把整个面板撑得很长。
 */
@Composable
private fun FilterDropdown(
  label: String,
  options: List<String>,
  selectedNames: Set<String>,
  onToggle: (String) -> Unit,
  onClear: () -> Unit,
) {
  if (options.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  // 摘要：没选显示「不限」，选得少就全列出来，选得多只报数量，避免一行塞不下
  val summary = when {
    selectedNames.isEmpty() -> "不限"
    selectedNames.size <= 2 -> selectedNames.joinToString("、")
    else -> "已选 ${selectedNames.size} 项"
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .clickable { expanded = !expanded }
        .padding(horizontal = 14.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 260.dp)
          .verticalScroll(rememberScrollState())
          .padding(top = 4.dp),
      ) {
        // 「不限」永远放在第一位，点它等于清空这一组
        FilterDropdownRow(
          text = "不限",
          checked = selectedNames.isEmpty(),
          onClick = { if (selectedNames.isNotEmpty()) onClear() },
        )
        options.forEach { option ->
          FilterDropdownRow(
            text = option,
            checked = option in selectedNames,
            onClick = { onToggle(option) },
          )
        }
      }
    }
  }
}

@Composable
private fun FilterDropdownRow(
  text: String,
  checked: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(checked = checked, onCheckedChange = { onClick() })
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
  }
}


@Composable
private fun SortMenu(
  currentSort: String,
  currentOrder: String,
  onSortChange: (String) -> Unit,
  onOrderChange: (String) -> Unit,
  /**
   * 展开前调用：把输入框的焦点收掉。
   *
   * 搜索态下焦点常常还在输入框上，`DropdownMenu` 关闭时会把焦点「还」给它，
   * 于是每切一次排序就弹一次软键盘。焦点提前清掉后就没得可还。
   * 注意得清**焦点**（`clearFocus`）而不是只 `hide()` 键盘 —— 焦点还在，下次照样弹。
   */
  onRequestClearFocus: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { onRequestClearFocus(); expanded = true }) {
      Icon(Icons.Default.Sort, contentDescription = "排序")
    }
    // 点菜单外面 / 按返回取消时同样清一次（这时已经没有焦点可收，但保持语义一致）
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false; onRequestClearFocus() },
    ) {
      // 上半区：排序项。勾中的那一项高亮，并直接显示它当前的方向（↑ 升 / ↓ 降）
      SORT_OPTIONS.forEach { (value, label) ->
        DropdownMenuItem(
          text = {
            Text(
              text = buildString {
                append(label)
                if (value == currentSort) {
                  append(if (currentOrder == "Ascending") " ↑" else " ↓")
                }
              },
              color = if (value == currentSort) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          },
          onClick = {
            if (value == currentSort) {
              // 再点一次已选中的排序项 = 只反转方向，不换排序项
              onOrderChange(if (currentOrder == "Ascending") "Descending" else "Ascending")
            } else {
              // 换排序项：由调用方把方向重置为该排序项的自然方向
              onSortChange(value)
            }
            expanded = false
          },
        )
      }
      // 方向不单列选项：上面每个排序项的标签会带 ↑ / ↓，
      // 再点一次已选中的项就反转方向，两个入口合并成一个，菜单也短一半。
    }
  }
}

/** 排序方式：值与 Emby /Items 的 SortBy 参数一致 */
private val SORT_OPTIONS = listOf(
  "SortName" to "名称",
  "DateCreated" to "加入时间",
  "DatePlayed" to "播放时间",
  "ProductionYear" to "发行年份",
  "PremiereDate" to "首播日期",
  "CommunityRating" to "评分",
  "CriticRating" to "影评评分",
  "Runtime" to "时长",
  "PlayCount" to "播放次数",
  // 文件大小：EmbyItem.Size 已在 ITEM_FIELDS 里显式索取（老服务端没有时退回
  // MediaSources 的大小），搜索路径的 applyClientSort 也已支持 "Size"
  "Size" to "大小",
  "Random" to "随机",
)

/** 最低评分档位：显示文案 → 传给 Emby 的 MinCommunityRating（null 表示不限） */
private val RATING_OPTIONS: List<Pair<String, Float?>> = listOf(
  "不限" to null,
  "6 分以上" to 6f,
  "7 分以上" to 7f,
  "8 分以上" to 8f,
  "9 分以上" to 9f,
)

/** 可播放的媒体类型（随机播放时使用） */
private val PLAYABLE_TYPES = listOf("Movie", "Episode", "Video", "MusicVideo")

/**
 * 搜索结果的**客户端排序**。
 *
 * 为什么要在本地排：Emby 的 `/Items?SearchTerm=…` 是按「相关度」返回的，
 * `SortBy` 跟 `SearchTerm` 一起传时服务端并不保证生效 ——
 * 表现就是「搜完再点排序，顺序纹丝不动」。
 *
 * 搜索本身是一次性把命中结果整批拿回来的（不像库内浏览那样分页续拉），
 * 所以本地排一遍最直接，顺序也必然跟用户选的一致。
 *
 * 用于比较的字段取不到时返回原列表（稳定排序），不会把列表打乱 ——
 * 这比「排了个寂寞」更难排查，所以宁可不排。
 *
 * [sortBy] 为 `Random` 时直接洗牌，对应排序菜单里的「随机」。
 */
private fun applyClientSort(
  items: List<EmbyItem>,
  sortBy: String,
  sortOrder: String,
): List<EmbyItem> {
  if (sortBy == "Random") return items.shuffled()
  val comparator: Comparator<EmbyItem> =
    when (sortBy) {
      // 名称统一用 SortName（Emby 内部排序名，能正确处理「第 2 季」这类），取不到再退回 Name
      "SortName" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.SortName ?: it.Name ?: "" }
      // ISO-8601 字符串按字典序比较即等于按时间比较
      "DateCreated" -> compareBy { it.DateCreated ?: "" }
      "PremiereDate" -> compareBy { it.PremiereDate ?: "" }
      "DatePlayed" -> compareBy { it.UserData?.LastPlayedDate ?: "" }
      "ProductionYear" -> compareBy { it.ProductionYear ?: 0 }
      "CommunityRating" -> compareBy { it.CommunityRating ?: -1.0 }
      "CriticRating" -> compareBy { it.CriticRating ?: -1.0 }
      "Runtime" -> compareBy { it.RunTimeTicks ?: 0L }
      // 文件大小：顶层 Size 由 Fields=Size 带回；老服务端或详情类请求可能没有，
      // 这时退回 MediaSources 里第一个源的大小（实测两者一致），都没有就按 0
      "Size" -> compareBy { it.Size ?: it.MediaSources?.firstOrNull()?.Size ?: 0L }
      "PlayCount" -> compareBy { it.UserData?.PlayCount ?: 0 }
      else -> null
    } ?: return items
  val ordered = items.sortedWith(comparator)
  return if (sortOrder == "Descending") ordered.reversed() else ordered
}

/**
 * 随机播放一次取多少条 —— 读设置项并做护栏。
 *
 * 下限 1 是硬性的：填 0 会让「随机播放」变成一个永远点不出东西的按钮；
 * 上限 500 是软性的：再多服务端一次也给不出有意义的结果，而且这批 Uri
 * 要经 Intent 传给播放器，太大有撑爆 Binder 事务上限的风险。
 */
private fun Preference<Int>.randomPlayLimit(): Int = get().coerceIn(1, 500)

/** 文件夹类条目。只在「文件夹」分类里显示，其它分类一律排除。 */
private val FOLDER_TYPES = listOf("Folder", "CollectionFolder", "UserView")

/**
 * 是否需要「多宫格封面」的容器类条目。
 *
 * 这些条目在 Emby 里只是容器，自身没有 Primary 图（请求图片接口直接 404），
 * 所以要用内部子媒体的缩略图拼封面。
 */
private fun isFolderLike(item: EmbyItem): Boolean =
  item.Type in FOLDER_TYPES || item.Type == "BoxSet"

/**
 * 能「扫描」的条目：媒体库（UserView / CollectionFolder）和真实文件夹。
 *
 * 与 [isFolderLike] 的差别是刻意排除 BoxSet（合集）—— 它只是个虚拟分组，
 * 背后没有对应的物理目录，让服务器去扫它扫不出任何东西。
 */
private fun isScannableFolder(item: EmbyItem): Boolean = item.Type in FOLDER_TYPES

/**
 * 文件夹宫格封面的进程内缓存（按文件夹 Id 存取）。
 *
 * 一个文件夹的封面要额外发一次 /Items 查询才能拿到子项缩略图，比较贵；
 * 缓存后滚动列表、切分类、从子页面返回都不必重复请求。
 * 取不到的（空文件夹）会缓存成空列表，避免反复重试把服务器打满。
 */
private object EmbyFolderCoverCache {
  private const val MAX_ENTRIES = 200

  private val entries = LinkedHashMap<String, List<String>>()
  private val loading = HashSet<String>()

  @Synchronized
  fun snapshot(): Map<String, List<String>> = LinkedHashMap(entries)

  /** 返回 true 表示这次由调用方发起加载（同一文件夹不会被并发重复请求） */
  @Synchronized
  fun beginLoad(folderId: String): Boolean {
    if (entries.containsKey(folderId) || loading.contains(folderId)) return false
    loading.add(folderId)
    return true
  }

  @Synchronized
  fun put(
    folderId: String,
    urls: List<String>,
  ) {
    loading.remove(folderId)
    entries.remove(folderId)
    entries[folderId] = urls
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}

/**
 * 「全部」分类下按媒体库类型限定条目类型。
 *
 * Emby 的 Recursive 查询在不指定类型时会把季、集、文件夹一起返回，
 * 列表会变得很杂；官方客户端的做法就是按库类型取「顶层条目」。
 * 返回 null 表示不限制类型（未知/混合库）。
 */
private fun allItemTypesFor(collectionType: String?): List<String>? =
  when (collectionType?.lowercase()) {
    "movies" -> listOf("Movie")
    "tvshows" -> listOf("Series")
    "music" -> listOf("MusicAlbum")
    "musicvideos" -> listOf("MusicVideo")
    "boxsets" -> listOf("BoxSet")
    "homevideos" -> listOf("Video")
    "photos" -> listOf("PhotoAlbum")
    "books" -> listOf("Book")
    else -> null
  }

/**
 * 「全部」这类视图要传给服务端的 `MediaTypes`（Emby 的媒体形态口径）。
 *
 * 和 [allItemTypesFor] 不是一回事：那是**条目类型**（Movie / Episode …），
 * 这是**媒体形态**（Video / Audio / Photo / Book）。库里混进来的音频、图片、
 * 电子书，条目类型可能仍在白名单里，但 MediaType 一定不是 Video ——
 * 所以「只要视频」必须两个口径一起传，缺一个就剔不干净。
 *
 * @param includeItemTypes 剧集下钻时指定的层级类型（Season / Episode），优先于库类型
 * @return null 表示不限制（容器类视图本来就没有 MediaType）
 */
private fun mediaTypesFor(collectionType: String?, includeItemTypes: List<String>?): List<String>? {
  // 下钻层级优先：单集是可播放的视频；季是容器，没有 MediaType
  if (includeItemTypes != null) {
    return if (includeItemTypes.all { it.equals("Episode", ignoreCase = true) }) {
      listOf("Video")
    } else {
      null
    }
  }
  return when (collectionType?.lowercase()) {
    "movies", "homevideos", "musicvideos", "mixed" -> listOf("Video")
    "music" -> listOf("Audio")
    "photos" -> listOf("Photo")
    "books" -> listOf("Book")
    // 剧集库的顶层是 Series、合集库是 BoxSet —— 都是容器，MediaType 为空，
    // 传 Video 会把整个列表剔空，所以这两种不传
    "tvshows", "boxsets" -> null
    // 未知 / 混合库按视频处理：这类库基本都是视频，且用户要的就是「只要视频」
    else -> listOf("Video")
  }
}

private fun itemSubtitle(item: EmbyItem): String? {
  val year = item.ProductionYear
  val count = item.ChildCount
  return when (item.Type) {
    "Series" -> count?.let { "$it 季" }
    "Season" -> count?.let { "$it 集" }
    else -> year?.toString()
  }
}

/**
 * 卡片副标题 + 「中文字幕」命中标记。
 *
 * 把命中的标记（配置里的原文，如 `-C`）标在卡片上：一眼能看出这条为什么被筛出来，
 * 也能顺便验证自己配的标记有没有误伤。
 */
private fun itemSubtitleWithMark(
  item: EmbyItem,
  chineseHit: ChineseSubtitleHit?,
): String? {
  val base = itemSubtitle(item)
  val mark = chineseHit?.marker?.takeIf { it.isNotBlank() }?.let { "中字 $it" } ?: return base
  return if (base.isNullOrBlank()) mark else "$base · $mark"
}

private fun itemProgress(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private const val PAGE_SIZE = 100

/** 枚举名 → [EmbyCardStyle]，取值异常时回退到海报样式 */
private fun embyCardStyleFromName(name: String): EmbyCardStyle =
  EmbyCardStyle.entries.firstOrNull { it.name == name } ?: EmbyCardStyle.POSTER

/**
 * 媒体库列表的进程内缓存，按「库 + 分类 + 排序 + 搜索词」分桶。
 *
 * 媒体库页在导航离开后会被销毁重组，如果每次都重新请求，从详情页 / 播放器返回
 * 时列表就会闪一下、跳回顶部。这里把结果和滚动位置一起缓存：
 * - [putItems] 在加载成功后写入，[putScroll] 在离开页面时写入；
 * - 页面首屏命中缓存时直接复用，不发起请求；
 * - 用户下拉刷新或改动筛选条件（key 变化）时照常重新拉取。
 *
 * 仅做进程内缓存、不落盘 —— 重启 App 后重新拉取是符合预期的。
 */
internal object EmbyLibraryCache {
  private const val MAX_ENTRIES = 16

  class Entry {
    var items: List<EmbyItem> = emptyList()
    var totalCount: Int = 0
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
    /** itemId → 「中文字幕」命中标记（客户端筛选时才有） */
    var hits: Map<String, ChineseSubtitleHit> = emptyMap()
    /** 全量扫描已扫过的条目数（普通分页时不用） */
    var scannedCount: Int = 0
    /** 结果是否完整：扫描中途被打断时是 false，下次进页面要重扫 */
    var complete: Boolean = true
  }

  private val entries = LinkedHashMap<String, Entry>()

  /**
   * 最近一次删除的媒体 Id（快照状态）。
   *
   * 媒体库页的组合在详情页压栈期间可能仍然存活，光改缓存里的 Entry 不会让它
   * 重组 —— 列表读这个快照状态做渲染时剔除，删除后返回立即生效。
   */
  var lastRemovedItemId: String? by androidx.compose.runtime.mutableStateOf(null)
    private set

  @Synchronized
  fun get(key: String): Entry? = entries[key]

  @Synchronized
  fun putItems(
    key: String,
    items: List<EmbyItem>,
    totalCount: Int,
    hits: Map<String, ChineseSubtitleHit> = emptyMap(),
    scannedCount: Int = 0,
    complete: Boolean = true,
  ) {
    val entry = entries.getOrPut(key) { Entry() }
    entry.items = items
    entry.totalCount = totalCount
    entry.hits = hits
    entry.scannedCount = scannedCount
    entry.complete = complete
    touch(key, entry)
  }

  @Synchronized
  fun putScroll(
    key: String,
    index: Int,
    offset: Int,
  ) {
    val entry = entries.getOrPut(key) { Entry() }
    entry.scrollIndex = index
    entry.scrollOffset = offset
    touch(key, entry)
  }

  /**
   * 从所有缓存的分桶里移除已删除的媒体。
   *
   * 详情页删除成功后调用：媒体库页返回时直接复用缓存（不重新请求），
   * 不同步剔除的话，被删掉的条目还会留在列表里。
   */
  @Synchronized
  fun removeItem(itemId: String) {
    entries.values.forEach { entry ->
      if (entry.items.any { it.Id == itemId }) {
        entry.items = entry.items.filterNot { it.Id == itemId }
        entry.totalCount = (entry.totalCount - 1).coerceAtLeast(0)
        if (entry.hits.containsKey(itemId)) entry.hits = entry.hits - itemId
      }
    }
    lastRemovedItemId = itemId
  }

  /**
   * 整片作废所有分桶缓存。
   *
   * 「撤销演职员合并」时用：合并那会儿把被并掉的演员卡从各分桶里 [removeItem] 掉了，
   * 撤销之后它们**又回到作品里**，但缓存里已经没有它们，光靠 `removeItem` 的
   * 单向剔除救不回来 —— 只能整片丢掉，让下次进页面重新拉。
   * 代价是回库时会重新请求一次（可接受：撤销本来就是低频操作）。
   */
  @Synchronized
  fun clear() {
    entries.clear()
    lastRemovedItemId = null
  }

  /**
   * 同步某条媒体的收藏状态到所有缓存分桶。
   *
   * 卡片上的快捷收藏只改了当前列表的组合状态，换个筛选条件（cacheKey 变）重新进列表
   * 时会从缓存取值 —— 缓存不同步的话，红心会「弹回」未收藏。这里把每个分桶里
   * 同 Id 的条目就地替换成新状态。
   */
  @Synchronized
  fun updateFavorite(
    itemId: String,
    isFavorite: Boolean,
  ) {
    entries.values.forEach { entry ->
      if (entry.items.any { it.Id == itemId }) {
        entry.items =
          entry.items.map { item ->
            if (item.Id == itemId) {
              item.copy(
                UserData = (item.UserData ?: EmbyUserData()).copy(IsFavorite = isFavorite),
              )
            } else {
              item
            }
          }
      }
    }
  }

  /**
   * 查某条媒体在缓存里的收藏状态。
   *
   * 卡片心形的点击回调可能比列表状态晚一拍（或拿到旧快照），用它兜底取真实状态，
   * 避免「明明已收藏、却还按收藏方向再发一次请求」——那就是「取消不掉收藏」的成因。
   * 缓存里根本没有这条时返回 null，由调用方决定退回哪个值。
   */
  @Synchronized
  fun favoriteStateOf(itemId: String): Boolean? {
    entries.values.forEach { entry ->
      entry.items.firstOrNull { it.Id == itemId }?.let { return it.UserData?.IsFavorite ?: false }
    }
    return null
  }

  /** 最近使用的挪到末尾，超出上限时淘汰最久未使用的 */
  private fun touch(
    key: String,
    entry: Entry,
  ) {
    entries.remove(key)
    entries[key] = entry
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}

/**
 * 「中文字幕」判定已全部挪到 [ChineseSubtitleFilter]（domain/emby）：
 * 标记列表可配置、正则预编译、结果带缓存、并按「顶层 Path + 每条 MediaSource 的 Path」
 * 多候选路径判定。这里不再保留旧的「只看文件名 token」实现 —— 那套规则漏在两点：
 * 1. 只匹配末段文件名，标记只写在目录名的条目全部筛不出来；
 * 2. 走的是「切 token 后整词比较」，`HMN-864-C` 之后若还有别的段落就命中不到。
 */

