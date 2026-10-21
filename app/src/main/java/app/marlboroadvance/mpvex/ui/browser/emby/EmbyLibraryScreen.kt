package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import app.marlboroadvance.mpvex.domain.emby.EmbyApiException
import app.marlboroadvance.mpvex.domain.emby.EmbyFilterOptions
import app.marlboroadvance.mpvex.domain.emby.EmbyLibraryFilterState
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyScanQuery
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.shouldResynthesizeCover
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshGridBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyFavoriteRandomIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyProgressBar
import app.marlboroadvance.mpvex.ui.browser.emby.components.MEDIA_BADGE_BG
import app.marlboroadvance.mpvex.ui.browser.emby.components.FOLDER_ICON_BLUE
import app.marlboroadvance.mpvex.ui.browser.emby.components.LocalEmbyAppearance
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
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoBrowserScreen
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoCard
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoGridScreen
import app.marlboroadvance.mpvex.ui.browser.emby.photo.photoSubtitle
import app.marlboroadvance.mpvex.ui.player.feed.FeedItem
import app.marlboroadvance.mpvex.ui.player.feed.VerticalFeedActivity
import app.marlboroadvance.mpvex.ui.utils.LazyGridSideScrollbar
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
  /**
   * 本页是「点进一个真实文件夹（Type=Folder）」下钻出来的页面。
   *
   * 为什么要单独标出来：库类型（CollectionType）只描述**库**的分类视角，
   * 不能拿来约束**文件夹内部**装了什么。一个「电影」库里，没被 Emby 识别成
   * Movie 的子目录会退化成普通 Folder，里面可能是 `Type=Video` 的视频、
   * 也可能是更深一层的目录 —— 若在这种页面上继续套 `IncludeItemTypes=Movie`
   * + `MediaTypes=Video` + `IsFolder=false`，服务端会把这一层的内容**整层滤空**
   * （症状：点进文件夹只剩几个子目录、视频全不见，而 Emby Web / Yamby 都显示得出来）。
   *
   * true 时本页改用「内容原样」口径：不限条目类型 / 不限媒体形态 / 不限是不是容器，
   * 这一层真实装了什么就铺什么（唯一例外见 [contentAsIsExclude]）。
   */
  val isFolderDive: Boolean = false,
) : Screen {
  // ⚠️ ExperimentalFoundationApi 是为了 `LazyGridScope.stickyHeader`（年份吸顶，清单 4.1）。
  // 它在 foundation 1.7 里从 Experimental 升为 Stable 了，但为兼容旧版本仍保留注解写法。
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // 卡片标题行数（1 或 2，默认 1）。长片名一行放不下会被省略号截掉，调 2 行能看全，
    // 代价是卡片变高、一屏少看几条。
    val titleLines by browserPreferences.embyLibraryTitleLines.collectAsState()
    // 卡片右上角心形快捷收藏开关（默认开）。关掉后退回只读角标。
    val quickFavoriteEnabled by browserPreferences.embyQuickFavorite.collectAsState()
    // 搜索联想词列表是否收起（默认展开）。联想只是参考，用户收起后一直记着。
    val suggestCollapsed by browserPreferences.embySearchSuggestCollapsed.collectAsState()
    // 文件夹宫格封面是否随机合成（默认开）。改它会换一套缓存键，来回切不会串图。
    val folderRandomCover by browserPreferences.embyFolderRandomCover.collectAsState()
    // 随机播放的三个入口：单击下钻 or 长按下钻（默认前者）。用户可反转这组分工。
    val randomDrillDownOnTap by browserPreferences.embyRandomDrillDownOnTap.collectAsState()
    // 手势提示（Toast）总开关：嫌吵的人可以关掉，只留「取不到内容」那种失败提示。
    val randomGestureToast by browserPreferences.embyRandomGestureToast.collectAsState()
    // 合成策略：多久才允许把拼好的宫格封面换一批（默认「每次进入都重新合成」＝旧行为）。
    // 只影响「什么时候重取」，不影响取样方式（那是上面那个开关的事）。
    val coverRefreshPolicy by browserPreferences.embyFolderCoverRefreshPolicy.collectAsState()
    val coverRefreshHours by browserPreferences.embyFolderCoverRefreshHours.collectAsState()
    val coverRefreshDays by browserPreferences.embyFolderCoverRefreshDays.collectAsState()
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

    // ── 「照片视频混合库」的处置：内容原样铺开，不再让用户手选档位 ──
    //
    // 家庭视频 / 照片这类库（collectionType = homevideos / mixed / 未知）里，同一个
    // 目录可能既有视频又有照片，也可能只有其中一种。以前的做法是在顶部摆一排
    // 「仅媒体 / 全部 / 仅照片」让用户先声明「我要看哪种」，两个硬伤：
    //   ① 默认档「仅媒体」= 只要视频，于是**一个只装照片的目录点进去是空的**
    //      （服务端被 MediaTypes=Video 滤光）—— 用户看到的就是「点进去什么都没有」；
    //   ② 用户得先知道这层装的是什么才能选对档位，而他要的恰恰是「点进去看看」。
    //
    // 现在改成：这一层**真实装了什么就原样铺什么**（容器 / 视频 / 照片各用各自的卡片），
    // 与 Emby Web 的行为一致，也是唯一不需要用户先做判断的方案。
    // 纯视频库（movies / tvshows）与纯照片库（photos）不受影响，口径逐字节不变。
    val photoCapable = includeItemTypes == null && personId == null &&
      collectionType?.lowercase() in setOf(null, "mixed", "homevideos")
    // 「内容原样」只作用于「全部」分类 —— 其余分类（合集 / 收藏 / 文件夹 / 演员）
    // 各有自己的限定口径，不该被它改写。
    //
    // 第二种进入「内容原样」的路径：本页是**点进真实文件夹**下钻来的（[isFolderDive]）。
    // 库类型管的是「库怎么分类」，管不了「这个文件夹里装了什么」——「电影」库里一个
    // 没被识别成 Movie 的子目录，里面可能全是 `Type=Video` 的视频；继续套库类型的
    // 白名单会把这层滤空。Emby Web 与 Yamby 在文件夹里都是「装什么铺什么」。
    val contentAsIs = (photoCapable || isFolderDive) && category == EmbyCategory.ALL
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
    // 筛选弹窗的开关。用 rememberSaveable：同一次 alive 期内旋转屏幕时，
    // 弹窗不该自己关掉 —— 用户可能正在里面勾选，一转屏全白干。
    // （下面那些 selected* 筛选项是**跨会话**语义，走 SharedPreferences 落盘，
    //  与这里的 saveable 是互补的两层，别混。）
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
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

    // ── 筛选抽屉的「草稿」状态 ──
    // 用户要求：抽屉里怎么勾都不立刻生效，「确定」才应用（现在是一加类目就重筛一次）。
    // 做法是抽屉内所有控件都操作这一组草稿，点确定时一次性灌回上面的正式状态并落盘重载。
    // 用 showFilterDialog 作 key：每次打开都从当前正式状态重新快照，取消/关掉即丢弃草稿。
    var draftGenres by remember(showFilterDialog) { mutableStateOf(selectedGenres) }
    var draftTags by remember(showFilterDialog) { mutableStateOf(selectedTags) }
    var draftYears by remember(showFilterDialog) { mutableStateOf(selectedYears) }
    var draftRatings by remember(showFilterDialog) { mutableStateOf(selectedRatings) }
    var draftPlayed by remember(showFilterDialog) { mutableStateOf(playedFilter) }
    var draftSubtitles by remember(showFilterDialog) { mutableStateOf(subtitlesFilter) }
    var draftChinese by remember(showFilterDialog) { mutableStateOf(chineseSubsOnly) }
    var draftMinRating by remember(showFilterDialog) { mutableStateOf(minRating) }
    var draftFavorite by remember(showFilterDialog) { mutableStateOf(favoriteOnly) }

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
    // ⭐ 中文字幕模式下**不要**把搜索词算进 key。
    //
    // 中文字幕是按路径在客户端判的，扫描已经把整个库翻完，得到的就是完整的命中集。
    // 此时搜索只该在这份本地数据上做名字匹配（见下面的 localSearch），不该再问服务端。
    // 而一旦把搜索词算进 key，一敲字就换一个新桶，扫完那份数据在重组时就被丢掉了 ——
    // 只剩「再向服务端要一小截」这条路，于是出现「筛选里有、搜索却搜不到」。
    val cacheKey = "$libraryId|${category.name}|$sortBy|$sortOrder|" +
      "${if (chineseSubsOnly) "" else searchQuery}|" +
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
    //
    // ⭐ 中文字幕是例外：它的 key 里没有搜索词，cachedEntry 就是「扫完那份命中集」，
    // 搜索要在这份上继续筛，所以这里必须照常起值（见下面的 localSearch）。
    val cachedItems =
      if (searchQuery.isBlank() || chineseSubsOnly) {
        cachedEntry?.items ?: emptyList()
      } else {
        emptyList()
      }
    var items by remember(cacheKey) { mutableStateOf(cachedItems) }
    var totalCount by remember(cacheKey) {
      mutableIntStateOf(
        if (searchQuery.isBlank() || chineseSubsOnly) cachedEntry?.totalCount ?: 0 else 0
      )
    }
    // ── 分页游标（与 PagedList.consumed 同一口径）──
    // 服务端**实际返回过**多少条 —— 下一页的 StartIndex 只能用它，绝不能用 items.size：
    // 客户端过滤（幽灵条目 / distinctBy 去重 / 类型白名单）会让 items.size 小于真实消耗，
    // 拿它当起始下标会让翻页窗口回退、同一段反复拉；一旦某页「全新条目为 0」，
    // items.size 冻结 ⇒ 同一个 StartIndex 被无限重拉 ⇒ 一直转圈还没响应
    // （十万级库里服务端混入重复 / 幽灵条目时必现）。缓存里一并存取：
    // 从缓存恢复列表时，续页游标也要跟着恢复，否则返回后续页会回到开头重拉。
    var consumedCount by remember(cacheKey) {
      mutableIntStateOf(
        if (searchQuery.isBlank() || chineseSubsOnly) cachedEntry?.consumedCount ?: 0 else 0
      )
    }
    // 续页闸门：触底回调每个滚动帧都会评估，没有它会把同一页并发拉好几遍
    // （照片网格 loadingMore 同款教训）
    var loadingMore by remember(cacheKey) { mutableStateOf(false) }
    // ── 「演员」分类的独立数据 ──
    // 不走上面媒体那套分页 / 缓存 / 中文字幕扫描：/Persons 一次性把演员给全，
    // 既没有下一页要续拉，也没有字幕可扫。单独存一份，两条路径互不干扰。
    var actorItems by remember(cacheKey) { mutableStateOf(emptyList<EmbyItem>()) }
    var actorTotal by remember(cacheKey) { mutableIntStateOf(0) }
    var actorLoading by remember(cacheKey) { mutableStateOf(false) }
    var actorError by remember(cacheKey) { mutableStateOf<String?>(null) }
    var isLoading by remember(cacheKey) {
      mutableStateOf(
        if (searchQuery.isBlank() || chineseSubsOnly) {
          // 中文字幕模式下仍在用扫完那份：有数据就不该再转圈
          cachedEntry == null || cachedEntry.items.isEmpty()
        } else {
          true
        },
      )
    }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val isRefreshing = remember { mutableStateOf(false) }
    // itemId → 命中的「中文字幕」标记：列表卡片直接显示是哪条标记命中的
    var chineseHits by remember(cacheKey) {
      // 中文字幕模式下搜索也沿用扫完那份的命中记录：
      // 否则卡片上「命中了哪个标记」的角标会在搜索时整片消失。
      mutableStateOf(
        if (searchQuery.isBlank() || chineseSubsOnly) {
          cachedEntry?.hits ?: emptyMap()
        } else {
          emptyMap()
        }
      )
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
    // 「内容原样」档（混合库的「全部」）：条目类型完全不限 —— 容器 / 视频 / 照片
    // 都要，靠下面的 effectiveExclude + 本地过滤剔掉非内容的杂项。
    val effectiveTypes: List<String>? = when {
      includeItemTypes != null -> includeItemTypes
      contentAsIs -> null
      category.itemTypes != null -> category.itemTypes
      // 「全部」按库类型限定条目类型；否则 Emby 会把季、集和文件夹一起返回，列表是混的
      category == EmbyCategory.ALL -> allItemTypesFor(collectionType)
      else -> null
    }
    val effectiveFilters: List<String>? = if (includeItemTypes == null) category.filters else null
    // 「内容原样」档**不递归**：这一层装了什么就铺什么（文件夹是文件夹、照片是照片），
    // 和 Emby Web 一样一层一层点进去。递归会把这层和它下面所有层级的东西混在一起，
    // 既看不出一层里有几个目录，也让分页失去意义。
    val recursive: Boolean = category != EmbyCategory.FOLDER && !contentAsIs
    // Emby 的 Recursive 查询默认会把文件夹一并返回。
    // 用户期望「全部」直接铺媒体，只有切到「文件夹」分类才看到文件夹，所以这里显式排除。
    // 「全部」只保留媒体本体：合集（BoxSet）单独放到「合集」分类里看，这里排除掉。
    // 例外：专门的「合集库」（collectionType = boxsets）本身装的就是合集，
    // 若也排除会把它剔空，所以只对非合集库生效。
    val excludeBoxSet = category == EmbyCategory.ALL &&
      collectionType?.lowercase() != "boxsets"
    // 「内容原样」档的排除清单：**容器一个都不排**（文件夹与相册都要当卡片显示、
    // 点得进去），只排掉确实不是内容的那些（季 / 曲目 / 有声书 / 电子书）。
    // 这几类在服务端没传 MediaTypes 时会跟着漏回来，本地过滤还有一道兜底。
    //
    // 文件夹下钻时**不能排 Season**：Season 也是「文件夹里真实存在的子目录」，
    // 排掉它就成了「点进剧集目录看不到季」——与「装什么铺什么」相悖。
    val contentAsIsExclude = if (isFolderDive) {
      listOf("MusicAlbum", "MusicTrack", "AudioBook", "Book")
    } else {
      listOf("Season", "MusicAlbum", "MusicTrack", "AudioBook", "Book")
    }
    val effectiveExclude: List<String>? = when {
      includeItemTypes != null -> null
      category == EmbyCategory.FOLDER -> null
      contentAsIs -> contentAsIsExclude
      excludeBoxSet -> FOLDER_TYPES + "BoxSet"
      else -> FOLDER_TYPES
    }
    // 只靠 ExcludeItemTypes 剔不干净：它按**类型名**筛，漏掉的类型（Season、MusicAlbum、
    // 以及库里混进的音频 / 图片 / 电子书）照样回来。这两个参数是官方的另外两道闸：
    // MediaTypes 按媒体形态筛（Video），IsFolder=false 把「本身不能播」的容器整体挡掉。
    // 「文件夹」「合集」分类要的就是容器，所以那两种分类不传。
    val effectiveMediaTypes: List<String>? = when {
      // 内容原样：视频（Video）和照片（Photo）两种形态都要，传了就成了二选一
      contentAsIs -> null
      category == EmbyCategory.FOLDER || category == EmbyCategory.BOXSET -> null
      else -> mediaTypesFor(collectionType, includeItemTypes)
    }
    // 「内容原样」档**不传 IsFolder**：文件夹 / 相册这些容器本身就是要显示、
    // 要被点进去的卡片，传 false 会把它们全挡掉（那正是「点进文件夹一片空」的成因）。
    // 其余情况沿用老口径：effectiveMediaTypes 有值就说明只要媒体本体，挡掉容器。
    val effectiveIsFolder: Boolean? = when {
      contentAsIs -> null
      effectiveMediaTypes == null -> null
      else -> false
    }

    fun cacheItems() {
      EmbyLibraryCache.putItems(
        key = cacheKey,
        items = items,
        totalCount = totalCount,
        hits = chineseHits,
        scannedCount = scanScanned,
        // 分页游标一起存：从缓存恢复列表时续页位置才不会退回开头
        consumedCount = consumedCount,
        // 扫描中途被打断时缓存里只有前半截命中，标记成「不完整」，下次进来重扫
        complete = !isScanning,
      )
    }

    /**
     * 「类型 / 标签」是否命中当前筛选（维度内并集、维度间交集）。
     *
     * 这两维刻意不走服务端参数 —— `/Items?Genres=` / `?Tags=` 在部分 Emby 上会
     * 抛 SQLiteException（500）。改为拉回来本地按 Genres / Tags 判，结果一致且稳。
     * 字段已在 `ITEM_FIELDS` 里，不额外增加请求。
     */
    fun matchesGenreTagFilter(item: EmbyItem): Boolean =
      matchesSelectedNames(selectedGenres, item.Genres) &&
        matchesSelectedNames(selectedTags, item.Tags)

    /**
     * 把选中的类型 / 标签**名字**映射成服务端要的 **Id**（`GenreIds` / `TagIds`）。
     *
     * 按名字筛（`Genres=` / `Tags=`）会在部分服务端踩到坏列直接 500；
     * 按 Id 筛才是网页端的做法、也不会炸（见 `EmbyClient.getItems` 的 genreIds 说明）。
     * 名字→Id 的对照表来自 `/Genres`、`/Tags` 端点（[EmbyFilterOptions.genreItems]）。
     * 名字在表里查不到（换了库 / 选项变了）就跳过那一个 —— 宁可筛少，不要发个错 Id 查空。
     */
    fun genreIdsOf(names: Set<String>): List<String> =
      names.mapNotNull { n -> filterOptions.genreItems.firstOrNull { it.name == n }?.id }

    fun tagIdsOf(names: Set<String>): List<String> =
      names.mapNotNull { n -> filterOptions.tagItems.firstOrNull { it.name == n }?.id }

    /**
     * 这台服务器用 `GenreIds` / `TagIds` 筛可不可用。
     *
     * 未知时先按「可用」试一次（绝大多数 Emby 都支持）；试出 5xx 就记成不可用，
     * 之后直接走客户端本地筛，不会再每次白跑一趟失败请求。
     */
    fun supportsGenreIdFilter(serverId: Long): Boolean =
      browserPreferences.embyGenreIdFilterSupport(serverId).get() != "0"

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
      val searching = searchQuery.isNotBlank()
      val scanned = viewModel.scanItems(
        server = current,
        query = EmbyScanQuery(
          // 搜索时保持原来的**全服**范围（不传 ParentId），不悄悄缩到当前库
          parentId = if (searching) null else libraryId,
          // 类型同理沿用搜索那套（用户在搜索筛选里勾的），不套库视图的 effectiveTypes
          includeItemTypes = if (searching) {
            EmbySearchFilter.toItemTypes(searchFilters)
          } else {
            effectiveTypes
          },
          excludeItemTypes = if (searching) null else effectiveExclude,
          // 搜索时不再按「媒体形态 / 是不是容器」在**请求层**收窄：
          // 原来的搜索分支也没收窄（容器与否是拉回来之后在本地判的）。
          mediaTypes = if (searching) null else effectiveMediaTypes,
          isFolder = if (searching) null else effectiveIsFolder,
          filters = if (searching) null else effectiveFilters,
          // ⭐ 搜索词交给服务端：扫的是「命中那批」而不是整库，
          // 但扫描照样一页一页翻到底 —— 这才是「筛选有、搜索也有」的关键。
          searchTerm = searchQuery.takeIf { it.isNotBlank() },
          sortBy = sortBy,
          sortOrder = sortOrder,
          recursive = recursive,
          // 类型 / 标签刻意**不传给服务端**：这两个「按名字筛」的参数会让部分 Emby
          // 服务端的 /Items 直接抛 SQLiteException（500），且不同版本表现不一。
          // 改为拉回来之后在客户端按 item.Genres / item.Tags 过滤，结果一致但不再炸。
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
        // 回调在 IO 线程：这几步都是纯 CPU 活，放这里不会占 UI 线程
        // 先按类型 / 标签过滤掉不匹配的（服务端那两个参数不能用，见上面的说明）；
        // 中文字幕只在开关打开时才参与 —— 否则会把「只按类型/标签筛」的条目误杀。
        val byGenreTag = chunk.items.filter { matchesGenreTagFilter(it) }
        val matched: List<Pair<EmbyItem, ChineseSubtitleHit?>> = if (chineseSubsOnly) {
          ChineseSubtitleFilter.filter(byGenreTag)
        } else {
          byGenreTag.map { it to null }
        }
        val fresh = matched.filter { (item, _) -> item.Id?.let { seen.add(it) } == true }
        withContext(Dispatchers.Main) {
          items = items + fresh.map { it.first }
          chineseHits = chineseHits + fresh
            .filter { (_, hit) -> hit != null }
            .associate { (item, hit) -> item.Id.orEmpty() to hit!! }
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
    fun startScan(current: EmbyServer, reset: Boolean = true) {
      scanJob.value?.cancel()
      error = null
      isScanning = true
      // 换了筛选条件就是另一批结果：先把旧的清掉，否则新结果会追加在旧结果后面。
      // reset=false（续拉）时不动，保留已有结果。
      if (reset) {
        items = emptyList()
        chineseHits = emptyMap()
        scanScanned = 0
        totalCount = 0
        isLoading = true
      }
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

    // 归位标记：数据**落位之后**把网格滚回顶部。
    //
    // 为什么不能在触发处直接滚：那时新数据还没到，LazyGrid 会先按旧内容记下第一屏的 key，
    // 等新数据落位后再把那条重新定位到它在新顺序里的位置 —— 当场归位会被抵掉。
    // 真正生效的那次归位在 [load] 的 onSuccess（数据已落位）里做。
    // 置它的地方：切分类 / 换排序（含随机）/ 改筛选 / 下拉刷新。
    // 首次进入（要恢复上次浏览位置）与「退出搜索接回位置」两种情况**不会**置它。
    var pendingScrollTop by remember { mutableStateOf(false) }

    suspend fun load(reset: Boolean) {
      // 冷启动时当前服务器可能还没恢复，这里等一下，避免静默不加载
      val current = viewModel.currentServerOrAwait() ?: return
      // 续页同一时刻只允许一个在飞：触底回调每个滚动帧都会评估，没有这道闸门
      // 会把同一个 StartIndex 并发拉好几遍（照片网格同款做法）
      if (!reset && loadingMore) return
      val gen = ++loadGeneration
      val hasGenreTagFilter = selectedGenres.isNotEmpty() || selectedTags.isNotEmpty()
      // 需要「逐页拉全 + 客户端过滤」的两种情况：
      //  · 中文字幕：按路径判定，服务端根本没有这个筛选参数；
      //  · 类型 / 标签：本该由服务端的 GenreIds / TagIds 筛（网页端就是这么做的），
      //    但万一那台服务器连 Id 也不认，就退化成客户端本地筛。
      // 两者都得把整个结果集拉完才准，所以统一走扫描那条路。
      val needClientFilter = chineseSubsOnly ||
        (hasGenreTagFilter && !supportsGenreIdFilter(current.id))
      // ⭐ 这里**不再**要求 `searchQuery.isBlank()`。
      //
      // 以前搜索走的是下面那条「一次性取回、不翻页」的分支（limit = PAGE_SIZE），
      // 于是客户端筛选（中文字幕 / 类型标签）只能在一个**被截断的样本**里筛 ——
      // 表现就是用户报的「筛选列表里有、在筛选基础上搜索却搜不到」。
      // 两条路看到的不是同一批数据，筛选翻完了整个库，搜索只看了第一页。
      //
      // 现在搜索也走扫描：`EmbyScanQuery` 带上 SearchTerm，服务端只返回命中那批，
      // 扫描再一页一页把它翻到底，口径与纯筛选**完全一致** —— 筛得到的，搜就一定搜得到。
      // ⭐ 中文字幕 + 搜索：命中集已经在本地扫完了（扫描翻完整个库 + 按路径筛过）。
      //   搜索只该在这份数据上做本地名字匹配（见下面的 localSearch）——
      //   **不要**再发请求、也不要重扫：那只会拿回一个被截断的样本，
      //   于是又回到「筛选里有、搜索却搜不到」。零请求，敲一个字就地筛。
      if (chineseSubsOnly && searchQuery.isNotBlank()) return
      if (needClientFilter) {
        startScan(current, reset = reset)
        return
      }
      if (reset) {
        isLoading = true
        // 重拉第一页：分页游标一起归零（从缓存恢复的那次不走 load，不受影响）
        consumedCount = 0
      } else {
        loadingMore = true
      }
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
            // ⭐ 用 consumedCount（服务端实际给过多少条），不用 items.size ——
            // 后者被客户端过滤/去重削过，会让翻页窗口回退、同一页反复拉，
            // 「全新条目为 0」时更是直接把 items.size 冻住、无限重拉同一页。
            startIndex = if (reset) 0 else consumedCount,
            limit = PAGE_SIZE,
            recursive = recursive,
            excludeItemTypes = effectiveExclude,
            // 类型 / 标签一律用 **Id** 筛（GenreIds / TagIds）—— 这是网页端的做法，
            // 也避开了「按名字筛」在部分服务端踩坏列抛 500 的坑。
            // 名字版的 genres / tags 恒不传（要按名字筛就得走上面的客户端扫描）
            genres = null,
            tags = null,
            genreIds = genreIdsOf(selectedGenres).takeIf { it.isNotEmpty() },
            tagIds = tagIdsOf(selectedTags).takeIf { it.isNotEmpty() },
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
            // 类型 / 标签同样在客户端筛（搜索接口没有 GenreIds / TagIds）。
            withContext(Dispatchers.Default) {
              val kept = result.filter { matchesGenreTagFilter(it) }
              applyClientSort(kept, sortBy, sortOrder)
            },
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
          val beforeSize = items.size
          items = if (reset) {
            page.items
          } else {
            // 分页时按 Id 去重后追加。⚠️ 必须用 HashSet —— 老写法
            // `items.none { it.Id == new.Id }` 是 O(n×页大小)：大库翻深之后
            // 每页要把整份列表比一遍，全在主线程，越翻越卡。
            val seen = items.mapTo(HashSet()) { it.Id ?: it.Name ?: "" }
            items + page.items.filter { seen.add(it.Id ?: it.Name ?: "") }
          }
          // 游标按**服务端给了多少**推进，与本地去重结果无关
          if (reset) consumedCount = page.items.size else consumedCount += page.items.size
          totalCount = page.totalCount
          // ⭐ 这一页一条新的都没有（或服务端干脆返回空页）：它已经在我们见过的地方
          // 打转了 —— SmartStrm 兼容层忽略 StartIndex、或服务端重复条目被客户端
          // 去重削光时都会这样。把 totalCount 对齐已消耗，续页条件
          // （consumedCount < totalCount）自然失效；不拦的话同一个 StartIndex 会被
          // 无限重拉，表现就是「一直加载中、界面没响应」。
          if (!reset && (page.items.isEmpty() || items.size == beforeSize)) {
            totalCount = consumedCount
          }
        }
        cacheItems()
        // 整批替换（reset）之后要停在第一条。
        //
        // 这一步放在数据落位**之后**才可靠：触发方的 LaunchedEffect 是在请求发出之前跑的，
        // 那时新数据还没到，归位会被 LazyGrid 的「按 key 把原第一条重新定位」抵掉 ——
        // 表现就是「切完排序 / 随机 / 下拉刷新后，列表停在中间」。
        //  · pendingScrollTop：切分类 / 换排序（含随机）/ 改筛选（含类型标签多选）/ 下拉刷新；
        //  · searchQuery 非空：搜索结果每批都是整批替换，同样要停第一条。
        if (reset && (pendingScrollTop || searchQuery.isNotBlank())) {
          pendingScrollTop = false
          gridState.scrollToItem(0)
        }
      }.onFailure { e ->
        // 过期的失败同样丢弃：别用旧请求的错误盖掉新请求的结果
        if (gen != loadGeneration) return@onFailure
        // ⭐ 降级：服务端用 GenreIds / TagIds 筛若返回 5xx（某些服务端的库表结构有残缺），
        // 说明这台服务器连按 Id 筛也走不通 —— 记住它，并**立刻**改用客户端本地筛重试一次。
        // 之所以不等下次：偏好变更不触发重组，这里必须自己重跑，用户才不用手动再点一次。
        val serverError = (e as? EmbyApiException)?.code?.let { it >= 500 } == true
        val usedIdFilter = hasGenreTagFilter &&
          (genreIdsOf(selectedGenres).isNotEmpty() || tagIdsOf(selectedTags).isNotEmpty())
        if (serverError && usedIdFilter) {
          browserPreferences.embyGenreIdFilterSupport(current.id).set("0")
          isLoading = false
          load(reset = true)
          return@onFailure
        }
        error = e.message ?: "加载失败"
      }
      // 过期请求不碰 isLoading：收尾交给当前那次加载
      if (gen == loadGeneration) isLoading = false
      // loadingMore 自己收：过期的那次也要放开闸门，否则一次被接替的续页
      // 会让这一页此后再也不续页（照片网格同款教训）
      loadingMore = false
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
      // 放在 loading 结束之后 —— 头像网格先出来，数字随后补，不让首屏等这次统计。
      // 服务端哪天真的返回了 ChildCount，就跳过统计、直接用它。
      //
      // 走 getPersonWorkCountsCached 而不是原始的 getPersonWorkCounts：自适应库规模
      // + 带10 分钟缓存，避免每次进「演员」分类都把整库逐页拉一遍（见 1.4 优化）。
      val list = loaded.getOrNull().orEmpty()
      if (list.isEmpty() || list.any { it.ChildCount != null }) return
      val ids = list.mapNotNull { it.Id }
      if (ids.isEmpty()) return
      val counts = viewModel.getPersonWorkCountsCached(current, libraryId, ids)
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
        // 切分类 / 换排序（含「随机」）/ 改筛选（含类型标签的多选）：呈现的是新的一批内容，
        // 必须归位到第一条。
        // ⚠️ 这里只**置标记**，真正的归位放到 load() 数据落位之后做：
        //   此刻新数据还没到，LazyGrid 会先按旧内容记下第一屏的 key，等新数据落位后
        //   再把那条重新定位到它在新顺序里的位置 —— 当场归位会被它抵掉，
        //   表现就是「切了排序/随机，画面停在列表中间」（随机排序整副牌重洗时最明显）。
        //   立刻滚一次只为点击后即时反馈，落位后那次才是真正生效的那次。
        else -> {
          pendingScrollTop = true
          gridState.scrollToItem(0)
        }
      }
      // 「演员」分类：走独立的演员加载，跳过媒体那套分页 / 缓存 / 扫描
      if (isActorListMode) {
        if (actorItems.isEmpty() && !actorLoading) loadActors()
        return@LaunchedEffect
      }
      val entry = EmbyLibraryCache.get(cacheKey)
      // 扫描没跑完的缓存（items 只含前半截命中）不能当完整结果复用，重扫。
      // 走扫描的条件与 load() 里**严格一致**：中文字幕，或「类型/标签筛选 + 这台服务器不支持 Id 筛」。
      val hasGenreTagFilter = selectedGenres.isNotEmpty() || selectedTags.isNotEmpty()
      val serverSnapshot = server
      val needsScan = chineseSubsOnly ||
        (hasGenreTagFilter && serverSnapshot != null && !supportsGenreIdFilter(serverSnapshot.id))
      val stale = needsScan && entry != null && !entry.complete
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

    // 触底预取距离：「预测性懒加载」开关（开 ≈ 一屏 / 关 ≈ 贴近末尾），全 App 分页列表统一
    val prefetchDistance = rememberPredictivePreloadDistance(browserPreferences)
    LaunchedEffect(gridState, items.size, prefetchDistance) {
      snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .collect { lastVisible ->
          // 客户端筛选走全量扫描，没有「下一页」可翻；扫描中也不要再触发普通分页。
          // 判定与 load() 严格一致：中文字幕，或「类型/标签 + 服务器不支持 Id 筛」。
          // 演员列表同理：/Persons 一次性给全，不可能有下一页
          val hasGenreTagFilter = selectedGenres.isNotEmpty() || selectedTags.isNotEmpty()
          val serverSnapshot = server
          val scanningFilter = chineseSubsOnly ||
            (hasGenreTagFilter && serverSnapshot != null && !supportsGenreIdFilter(serverSnapshot.id))
          val canLoadMore = !isLoading && !isScanning && !scanningFilter && !isActorListMode &&
            !loadingMore && consumedCount < totalCount && lastVisible != null
          if (canLoadMore && lastVisible >= items.size - prefetchDistance) {
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
    // 「中文字幕 + 有搜索词」= 本地二次筛选模式：
    // 命中集已经扫完在本地，搜索只是它的子集筛选，全程零请求
    // （配套的三处：cacheKey 不含搜索词、load() 早退、下面这条名字过滤）
    val localSearch = chineseSubsOnly && searchQuery.isNotBlank()
    // ⭐ 整份过滤链必须 remember：这是 5 遍 O(n)（含 distinctBy 建 HashSet），
    // 不记忆的话**每次重组**（滚动、扫描进度文字、红心快照……都会触发）都对整个
    // items 重跑一遍 —— 大库翻深之后主线程全耗在这上面，界面发涩、像卡死。
    // 只列真正参与判断的依赖；items 引用每页都会变（该变时才重算）。
    val visibleItems = remember(
      items,
      lastRemovedItemId,
      localSearch,
      searchQuery,
      effectiveExclude,
      effectiveTypes,
      effectiveMediaTypes,
      contentAsIs,
    ) {
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
        // ── 中文字幕模式下，搜索就是这一层：在扫完的命中集上按名字包含匹配 ──
        // 这份数据已经是「整个库翻完 + 按路径判过中文字幕」的完整结果，
        // 搜索只是取它的子集 —— 不联网、不重扫，所以「筛得到的就一定搜得到」。
        // 非中文字幕模式（localSearch = false）这一层恒真，行为与从前完全一致。
        .filter { item ->
          !localSearch || item.Name?.contains(searchQuery.trim(), ignoreCase = true) == true
        }
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
          // 「内容原样」档：服务端既没传 MediaTypes（视频 / 照片两种形态都要）
          // 也没传 IsFolder（容器要留作可下钻的卡片），于是音频 / 电子书 / Genre 这类
          // 杂项会跟着漏回来（effectiveExclude 已按类型名排掉大部分，但兼容层未必认这些参数），
          // 这里按「容器 / 照片 / 可播放」三条留最后一道。
          if (contentAsIs) {
            // ⚠️ [EmbyItem] 没有 IsFolder 字段（服务端 BaseItemDto 有，本地模型没映射），
            //    容器判定只能靠类型名。isFolderLike 已含 Folder/CollectionFolder/UserView/BoxSet。
            //    文件夹下钻时把 Series / Season 也算容器 —— 它们在「文件夹里」就是子目录，
            //    排掉会让下钻进来的一层凭空少几张可点的卡片。
            val isContainer = isFolderLike(item) ||
              type.equals("PhotoAlbum", ignoreCase = true) ||
              (isFolderDive && (type.equals("Series", ignoreCase = true) ||
                type.equals("Season", ignoreCase = true)))
            val isPhoto = type.equals("Photo", ignoreCase = true) ||
              mediaType?.equals("Photo", ignoreCase = true) == true
            val isVideoLike = mediaType?.equals("Video", ignoreCase = true) == true ||
              PLAYABLE_TYPES.any { it.equals(type, ignoreCase = true) }
            if (!isContainer && !isPhoto && !isVideoLike) return@filter false
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
      /**
       * 是否**下钻**（把子文件夹一起纳入随机范围）。
       *
       * true = 老的默认行为：`recursive = true`，从当前目录往下递归取；
       * false = 只取当前这一层（长按入口用），目录里没有直接可播条目时自然为空。
       */
      drillDown: Boolean = true,
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
        // 是否递归到子文件夹由调用方决定：默认下钻（老行为），长按入口只取当前层
        recursive = drillDown,
        excludeItemTypes = effectiveExclude,
        // 随机只落在能播的条目上，所以这里固定按视频形态 + 非容器来取
        mediaTypes = effectiveMediaTypes ?: listOf("Video"),
        isFolder = false,
        // genres / tags 不传服务端（会 500），下面拿回来按 Genres / Tags 本地过滤。
        genres = null,
        tags = null,
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
      ).items.filter { matchesGenreTagFilter(it) }
    }

    /**
     * 随机播放 / 视界流启动前的一句提示：**只说这次是什么行为**。
     *
     * ⚠️ 不要在这里提「另一半手势」。上一版写「随机播放：含子文件夹 · 长按只播当前目录」，
     * 用户单击后看到「长按」二字，第一反应是「提示写反了 / 我是不是按错了」——
     * 这句提示的作用只是确认「这次取片范围」，不是在教手势。
     * 手势分工的说明放在设置项的副标题里（那句是静态说明，不会被误读成动作回执）。
     *
     * 受 [randomGestureToast] 总开关控制；失败提示（[emptyCurrentLevelHint]）不受它控制。
     */
    fun showGestureHint(label: String, drillDown: Boolean) {
      if (!randomGestureToast) return
      val text = if (drillDown) "$label（含子文件夹）" else "$label（仅当前目录）"
      android.widget.Toast
        .makeText(context, text, android.widget.Toast.LENGTH_SHORT)
        .show()
    }

    /**
     * 「只播当前目录」取不到东西时的说明。
     *
     * 单说「没有可播放内容」在纯目录页上是半个假话 —— 内容就在子文件夹里，
     * 只是这次没往下钻。把另一半手势直接写在提示里，用户不用去猜。
     */
    fun emptyCurrentLevelHint(): String {
      val drillGesture = if (randomDrillDownOnTap) "单击" else "长按"
      return "当前目录没有可直接播放的内容 · ${drillGesture}可含子文件夹"
    }

    /**
     * 随机播放的统一入口：取一批、没取到就明确提示（以前是静默无反应）。
     *
     * @param drillDown true = 下钻（含子文件夹，默认单击）；false = 只播当前目录（长按）
     */
    fun startRandomPlayback(favoritesOnly: Boolean, drillDown: Boolean = true) {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val random =
          loadRandomForView(current, favoritesOnly = favoritesOnly, drillDown = drillDown)
        if (random.isEmpty()) {
          android.widget.Toast
            .makeText(
              context,
              when {
                favoritesOnly -> "当前范围内没有可随机播放的收藏"
                drillDown -> "当前范围内没有可随机播放的内容"
                else -> emptyCurrentLevelHint()
              },
              android.widget.Toast.LENGTH_SHORT,
            )
            .show()
          return@launch
        }
        // 进播放器**之前**提示：这次到底是「含子文件夹」还是「只播当前目录」。
        // 两种手势都弹 —— 用户反馈只给下钻弹、长按静悄悄，反而更像没生效。
        showGestureHint(if (favoritesOnly) "随机播放收藏" else "随机播放", drillDown)
        // 随机列表里可能混着看过的剧，切过去若恢复进度会直接跳到片尾，所以每个视频都从头放
        viewModel.launchPlaylist(current, random, playFromStartAll = true)
      }
    }

    /**
     * 「随机播放图片」：混合库「仅照片」档的随机入口。
     *
     * 与视频随机播放同构：递归取整个库的照片（相册层级拉平）、数量用同一个
     * 「随机播放数量」设置、没取到就明确提示 —— 绝不静默无反应。
     * 落点直接是全屏照片浏览器（不进视频播放器）。
     */
    fun startRandomPhotoPlayback() {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val random = viewModel.loadPhotos(
          server = current,
          parentId = libraryId,
          sortBy = "Random",
          sortOrder = "Ascending",
          startIndex = 0,
          limit = browserPreferences.randomPlayCount.randomPlayLimit(),
          recursive = true,
        ).Items
        if (random.isEmpty()) {
          android.widget.Toast
            .makeText(context, "当前范围内没有照片", android.widget.Toast.LENGTH_SHORT)
            .show()
          return@launch
        }
        backStack.add(
          EmbyPhotoBrowserScreen(
            initialPhotos = random,
            initialIndex = 0,
            parentId = libraryId,
            title = title,
          ),
        )
      }
    }

    /**
     * 「视界流」入口：从当前库随机抽一批，进仿抖音竖屏上下滑连播的页面。
     *
     * 随机取片和上面两个随机按钮走同一条路径（数量设置、容器类分类的兜底都共用），
     * 差别只在最后一步：不是丢给主播放器排队播，而是把整批直连地址交给
     * VerticalFeedActivity，让它自己管「滑到哪放哪」。
     *
     * @param drillDown true = 下钻（含子文件夹，默认单击）；false = 只取当前目录（长按）
     */
    fun startFeedPlayback(favoritesOnly: Boolean, drillDown: Boolean = true) {
      scope.launch {
        val current = viewModel.currentServerOrAwait() ?: return@launch
        val random =
          loadRandomForView(current, favoritesOnly = favoritesOnly, drillDown = drillDown)
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
              when {
                favoritesOnly -> "当前范围内没有可随机播放的收藏"
                drillDown -> "当前范围内没有可随机播放的内容"
                else -> emptyCurrentLevelHint()
              },
              android.widget.Toast.LENGTH_SHORT,
            )
            .show()
          return@launch
        }
        // 与「随机播放」同一个意思：视界流同样要说明这次取片的范围（两种手势都弹）。
        showGestureHint("视界流", drillDown)
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
      // 工具行给哪几个按钮，由**这一页实际装了什么**决定（不再由用户手选档位决定）：
      //  · 有照片 ⇒ 给「随机播放图片」
      //  · 有非容器的可播放条目、**或者有子文件夹** ⇒ 给视频那套（随机播放 / 收藏随机 /
      //    视界流 / 筛选 / 视图）
      //  · 首屏还没加载出来 ⇒ 按「两种都给」处理，避免工具行先空一排、内容到了再跳出来
      //
      // ⚠️ 「有子文件夹」必须算进来：随机播放 / 视界流默认**下钻**，纯目录页也是有效入口。
      //    以前只数 `!isFolderLike` 的条目，纯子文件夹目录（0 个直接媒体）会把整组按钮收掉，
      //    用户看到的就是「工具栏突然少了一半」。
      val pagePhotoCount = items.count { it.Type == "Photo" }
      val pagePlayableCount = items.count { it.Type != "Photo" && !isFolderLike(it) }
      val pageFolderCount = items.count { isFolderLike(it) }
      val showPhotoRandom = pagePhotoCount > 0
      val showVideoActions = pagePlayableCount > 0 || pageFolderCount > 0 || items.isEmpty()
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
            // ⚠️ 扫完**不再重复「已扫 X / Y」**：那时它恒等于 Y/Y，是纯噪音，
            // 却把文案撑到会被省略号截断 —— 命中数到四位数、五位数时尤其明显
            // （「命中 813 项 · 已扫 12345 / 12345」一行根本放不下，看到的是「命中 813…」）。
            // 命中数是这时候唯一要紧的数，让它短到任何数量级都一行放得下。
            chineseSubsOnly -> "命中 ${visibleItems.size} 项"
            totalCount > 0 -> "$totalCount 项"
            else -> ""
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          // ⚠️ 这行文案的长度会随筛选状态大幅变化（「1234 项」→「命中 328 项 · 已扫 1234 / 1234」）。
          // 不约束宽度的话它先按内容宽测量，把右边那排工具按钮整排顶出屏幕 ——
          // 现象就是「开了中文字幕筛选、扫完工具栏只剩几个按钮」：其余按钮不是不显示，是被挤出去了。
          // weight(1f)：先量按钮、剩下的空间**全归**这行字。
          // ⚠️ fill 必须留默认 true —— 用 fill=false 的话这行字只占内容宽，
          // 按钮会紧贴文字排、**不再靠右**，右边留一大块空白（这就是删掉 Spacer 后的回归）。
          // 撑满才能保证按钮永远贴在最右端；字放不下就截断成省略号，按钮一个都不会少。
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        if (!hideToolbarButtons) {
          // 随机播放 / 收藏随机 / 视界流 / 筛选都是**视频语义**：这一页没有任何可播放
          // 条目（纯照片页 / 纯目录页）时它们点了只会把人带进视频播放器或空结果，收掉。
          if (showVideoActions) {
            // 三个随机入口共用一组手势：**单击下钻、长按只播当前目录**（默认），
            // 用户可在设置里把两者调反（[randomDrillDownOnTap]）。
            // 纯目录页（只有子文件夹）也能用 —— 下钻那一下会往里递归着随机。
            ToolbarIconAction(
              icon = Icons.Default.Shuffle,
              contentDescription = "随机播放",
              onClick = {
                releaseInputFocus()
                startRandomPlayback(favoritesOnly = false, drillDown = randomDrillDownOnTap)
              },
              onLongClick = {
                releaseInputFocus()
                startRandomPlayback(favoritesOnly = false, drillDown = !randomDrillDownOnTap)
              },
            )
            // 同上，只是范围再限定「已收藏」：避免随机到没看过的
            ToolbarIconAction(
              // 区别于上面的「随机播放」：用它自己的 Emby 收藏随机图标，
              // 原先用的 ShuffleOn 只比 Shuffle 多一条下划线，并排根本分不出来
              icon = EmbyFavoriteRandomIcon,
              contentDescription = "随机播放收藏",
              onClick = {
                releaseInputFocus()
                startRandomPlayback(favoritesOnly = true, drillDown = randomDrillDownOnTap)
              },
              onLongClick = {
                releaseInputFocus()
                startRandomPlayback(favoritesOnly = true, drillDown = !randomDrillDownOnTap)
              },
            )
            // 视界流：随机一批进仿抖音竖屏上下滑连播页。
            // 图标刻意不用「随机」语义 —— 前两个按钮已经把那层意思占满了，
            // 第三个得靠形状本身说话，否则一排三个图标看着像三个一样的按钮。
            ToolbarIconAction(
              icon = VideoFeedIcon,
              contentDescription = "视界流",
              onClick = {
                releaseInputFocus()
                startFeedPlayback(favoritesOnly = false, drillDown = randomDrillDownOnTap)
              },
              onLongClick = {
                releaseInputFocus()
                startFeedPlayback(favoritesOnly = false, drillDown = !randomDrillDownOnTap)
              },
            )
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
          }
          // 有照片就给「随机播放图片」（递归取整个范围的照片，落全屏照片浏览器）。
          // 混合页两个入口并存是有意的：随机看照片和随便挑个视频看，是两件事。
          if (showPhotoRandom) {
            IconButton(onClick = { releaseInputFocus(); startRandomPhotoPlayback() }) {
              Icon(Icons.Default.Image, contentDescription = "随机播放图片")
            }
          }
          // 视图样式（海报 / 横版 / 紧凑）：照片卡是**统一等比**的，跟着切样式会把它
          // 压成海报比例或拉成一行一条 —— 这一页有照片时收掉，保住照片的版式。
          if (showVideoActions) {
            IconButton(onClick = { releaseInputFocus(); showStyleDialog = true }) {
              Icon(Icons.Default.GridView, contentDescription = "视图样式")
            }
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
      //
      // ── 文件夹宫格封面：一次批量取，别让每个文件夹各发一次请求 ──
      // 容器条目自身没有封面，只能拿内部子项的缩略图拼。为了给服务端减压，这里对**当前父节点**
      // 发**一次** Recursive 查询、按子项 ParentId 分组（见 `EmbyViewModel.loadFolderCoverBatch`），
      // 一次覆盖这一屏所有文件夹。请求数从「每个文件夹一次」降到「每屏一次」。
      // 批量没覆盖到的（子项都没图、或排在扫描窗口之外）才由卡片自己补一次单文件夹请求
      // —— 见 `EmbyLibraryMediaCell` 里的 `folderCoverBatchReady` 守卫。
      val mosaicFolderIds = remember(displayItems, folderRandomCover) {
        displayItems.asSequence()
          // ⚠️ 与卡片（EmbyLibraryMediaCell）共用同一个判定函数：两处各写一份内联判定，
          //    一旦漂移就会出现「进了批量预取但卡片不认」的半套行为。
          .filter { needsFolderMosaic(it) }
          .mapNotNull { it.Id }
          .distinct()
          .toList()
      }
      // 「批量已经覆盖到哪一版文件夹集合」—— 用**签名比对**而不是布尔标志：
      // 布尔标志在「列表刚加载出来」那一帧会被卡片读到旧的 true，卡片就抢在批量之前
      // 自己把请求发了，于是变成「批量 + 每个文件夹」双份请求，比不批量还费。
      // 「封面代数」：下拉刷新时靠它换代，而不是靠清缓存。必须同时进三处 ——
      // ① 进签名（mosaicSignature）：批量重新跑，`folderCoverBatchReady` 在批量出结果前短暂回落，
      //    卡片不会抢在批量之前自己发一遍请求；
      // ② 让卡片的重取 key 变化：走**单文件夹兜底**的那些文件夹（批量覆盖不到的，比如合集）
      //    也会跟着重取 —— 少了这一条，只有批量覆盖到的那批会换图，兜底那批永远不变
      //    （用户反馈的「下拉刷新换图失效」）。
      // ③ 传给 `EmbyFolderCoverCache`：同一代里已有值就不再取、换代后才允许再取
      //    ⇒ 刷新**不需要清缓存**，旧封面原地留着显示，等新封面回来再替换
      //    （用户反馈的「空占位有点丑」）。
      // 初值取自缓存而不是写死 0：页面重建时若从 0 数起，会和缓存里条目的代数对不上，
      // 变成每次进页面都白重取一遍、进程内缓存白做。
      var folderCoverGen by remember { mutableStateOf(EmbyFolderCoverCache.currentGeneration()) }

      // ── 合成策略：这次进库要不要把已经拼好的封面换一批 ──
      //
      // 判据只看「距上次整体合成过了多久」（时间戳落盘，见 BrowserPreferences）。
      // ⚠️ 必须在下面那个批量取封面的 effect **之前**：换代后 mosaicSignature 会跟着变，
      //    批量 effect 自然重跑 —— 顺序反过来的话这一次进库会用旧代数取一遍、白费一轮请求。
      // ⚠️ 关掉「合成随机封面图」时整段跳过：固定模式取样结果是确定的，重取还是同一批图。
      // ⚠️ 键里带策略与本数：改完设置回到库里能立刻按新规矩重判（不必重启 App）。
      LaunchedEffect(
        server?.id,
        folderRandomCover,
        coverRefreshPolicy,
        coverRefreshHours,
        coverRefreshDays,
      ) {
        if (server == null || !folderRandomCover) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val last = browserPreferences.embyFolderCoverLastSynthAt.get()
        if (shouldResynthesizeCover(coverRefreshPolicy, last, now, coverRefreshHours, coverRefreshDays)) {
          // 只换代、不清缓存：旧封面留在原地继续显示，新封面回来后同键覆盖（见 cache 的 KDoc）。
          EmbyFolderCoverCache.bumpGeneration()
          folderCoverGen = EmbyFolderCoverCache.currentGeneration()
          browserPreferences.embyFolderCoverLastSynthAt.set(now)
        }
      }
      val mosaicSignature = remember(libraryId, mosaicFolderIds, folderRandomCover, server?.id, folderCoverGen) {
        "$libraryId|$folderRandomCover|${server?.id}|$folderCoverGen|" + mosaicFolderIds.joinToString(",")
      }
      var batchCoveredSignature by remember { mutableStateOf<String?>(null) }
      val folderCoverBatchReady = batchCoveredSignature == mosaicSignature
      LaunchedEffect(mosaicSignature) {
        val srv = server
        // 快照当前代数：写入时要带上它，这样「迟到的上一代结果」不会盖掉新代。
        val gen = folderCoverGen
        // ⭐⭐ 只取「这一代里还没定下来」的文件夹 —— 已经拼好的**一律不重取**。
        //
        //    这里是「滑动一下 → 返回上一级 → 再进这个文件夹，有些四宫格又重新生成」
        //    的主因：签名里带着 `mosaicFolderIds`（这一屏所有需要宫格的文件夹 Id），
        //    而无限加载会不断往里加新的 ⇒ 签名一变，这个 effect 就被**取消重跑**，
        //    连已经拼好的那批一起重新取样。随机取样每次结果都不同 ⇒ 已经显示着的
        //    封面当场洗牌换脸。
        //    看着像「只有一部分在变、还挺随机」，是因为子项不超过 4 个的文件夹
        //    怎么抽都是同一批图（抽不出新花样），子项多的那些每次都不一样。
        //
        // ⚠️ 换代（下拉刷新 / 「每次进入都重新合成」）时 `gen` 变了 ⇒ 缓存里条目的代
        //    都小于新代 ⇒ pending = 全部，行为与从前一致（整批换脸）。
        val pending =
          mosaicFolderIds.filter { fid ->
            fid.isNotBlank() && !EmbyFolderCoverCache.isResolved(folderCoverKey(fid, folderRandomCover), gen)
          }
        if (srv == null || pending.isEmpty()) {
          batchCoveredSignature = mosaicSignature
          return@LaunchedEffect
        }
        try {
          viewModel
            .loadFolderCoverBatch(
              server = srv,
              parentId = libraryId,
              folderIds = pending,
              random = folderRandomCover,
            )
            .forEach { (folderId, urls) ->
              val key = folderCoverKey(folderId, folderRandomCover)
              EmbyFolderCoverCache.put(key, urls, gen)
              folderCovers[key] = urls
            }
        } finally {
          // ⚠️ 请求失败也必须放行：否则所有文件夹的封面都卡在「等批量」上，谁也不去取，整屏空白。
          //
          // ⚠️ 但**只在自己没被取消时写**：签名变化会先取消老协程再起新的，老协程的
          //    finally 可能排在新协程**完成之后**才执行，那时写进去的是**旧签名**，
          //    直接把新签名覆盖掉 ⇒ `folderCoverBatchReady` 变成 false 且再没人来写，
          //    所有卡片永远卡在「等批量」，整屏封面不加载。
          //    （`isActive` 在正常收尾的 finally 里仍是 true —— 那时协程还没完成；
          //     只有被取消时根因已置上，它才是 false。）
          if (currentCoroutineContext().isActive) {
            batchCoveredSignature = mosaicSignature
          }
        }
      }

      PullRefreshGridBox(
        isRefreshing = isRefreshing,
        // 演员分类下拉刷新要重拉演员，而不是去重拉媒体。
        // 媒体：顺带把这一屏的文件夹封面**换代**重取（批量覆盖到的与走兜底的都会换，见上）。
        // ⭐ 刷新后回到顶部（下拉刷新的标准语义）。
        //
        // 不显式归位的话，LazyGrid 会**按条目 key 锚定滚动位置**：一旦刷新后顺序变了，
        // 框架会把「刷新前第一屏的那条」重新定位到它在新列表里现在所在的位置 ——
        // 表现就是「下拉刷新后画面跳到列表中间」。
        //  · 随机排序：服务端整副牌重洗，锚点条目被甩到中间，跳得最明显（用户反馈）；
        //  · 混合库 / 家庭视频库：照片与视频混排，顺序也常变；
        //  · 纯视频库：排序固定、刷新前后同一批同一序，锚点恰好还在原位，所以看不出问题。
        // ⚠️ 媒体走 `pendingScrollTop`（由 load 在数据落位后归位）；演员列表不经过 load，
        //   它是一次性拉全的，直接 await 后归位即可。
        onRefresh = {
          if (isActorListMode) {
            loadActors()
            gridState.scrollToItem(0)
          } else {
            // ⭐ 只换代，**不清缓存、也不清 `folderCovers`** —— 缓存里的旧封面正是刷新期间要继续
            //   显示的东西；清掉就会露出一片空占位（用户反馈「空占位有点丑」）。
            //   老条目原地留着显示，新封面取回后按同键覆盖（宫格那边再淡入替换）。
            // ⚠️ 固定模式（关了「合成随机封面图」）结果是确定的，重取只会拿到同一批
            //   ⇒ 没必要换代，省掉一整轮请求。
            if (folderRandomCover) {
              EmbyFolderCoverCache.bumpGeneration()
              folderCoverGen = EmbyFolderCoverCache.currentGeneration()
              // ⚠️ 手动刷新必须**同时记时间戳**：只换代不记时间的话，退出再进来会被
              //    「满 X 小时 / X 天」判成超期 ⇒ 刚刷完又换一批（白费一整轮请求）。
              //    手动意图优先于策略，所以这里无条件写 now，不看 policy。
              browserPreferences.embyFolderCoverLastSynthAt.set(System.currentTimeMillis())
            }
            pendingScrollTop = true
            load(reset = true)
          }
        },
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
            // 滚动条走统一外壳（全局开关 + 出现时机 + 外观，见 ui/utils/SideScrollbar.kt）
            LazyGridSideScrollbar(state = gridState) {
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
                  modifier = Modifier.animateItem(),
                )
              }
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
                // 48dp：整页加载态统一尺寸（原为默认 40dp）
                app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(size = 48.dp)
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
            // 拉失败了 ⇒ Oops 指示牌（与下面的「这个目录是空的」区分开）
            isError = true,
            modifier = Modifier.align(Alignment.Center),
          )

          items.isEmpty() && !isLoading -> EmbyEmptyState(
            message = when {
              // 「内容原样」档的空库：这一层确实什么都没有（不是被口径滤空了），
              // 措辞说清楚「空」，别让用户以为加载失败
              contentAsIs -> "这个目录是空的"
              chineseSubsOnly -> "没有匹配「中文字幕」标记的媒体"
              else -> "没有找到媒体"
            },
            buttonText = "刷新",
            onAction = { scope.launch { load(reset = true) } },
            modifier = Modifier.align(Alignment.Center),
          )

          else -> {
            when (viewMode) {
              // ── 紧凑列表：一行一条（左侧小海报 + 片名 / 副标题 + 心形），信息密度最高 ──
              // 滚动条走统一外壳（全局开关 + 出现时机 + 外观，见 ui/utils/SideScrollbar.kt）
              EmbyLibraryViewMode.COMPACT -> LazyGridSideScrollbar(state = gridState) {
                LazyVerticalGrid(
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
                  // 照片行：缩略图走照片端点，副标题换成相机 / 尺寸（年份对照片没意义）
                  val isPhotoRow = item.Type == "Photo"
                  EmbyCompactRow(
                    title = viewModel.displayTitle(item),
                    subtitle = if (isPhotoRow) photoSubtitle(item) else itemSubtitle(item),
                    imageUrl = if (isPhotoRow) {
                      viewModel.photoImageUrl(s, item, 240)
                    } else {
                      viewModel.imageUrl(s, item, "Primary", 240)
                    },
                    progress = playbackProgress(item),
                    isFavorite = item.UserData?.IsFavorite == true,
                    // 文件夹类型图标：与网格卡共用 isFolderLike 判定
                    isFolder = isFolderLike(item),
                    onClick = {
                      val photos = displayItems.filter { it.Type == "Photo" }
                      openItem(
                        item,
                        backStack,
                        s,
                        context,
                        photoQueue = photos,
                        photoIndex = photos.indexOfFirst { it.Id == item.Id }.coerceAtLeast(0),
                        photoParentId = libraryId,
                        collectionType = collectionType,
                      )
                    },
                    onLongClick = { offset ->
                      actionTarget = item
                      menuAnchor = offset
                    },
                    onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
                    modifier = Modifier.animateItem(),
                  )
                }
                }
              }

              // ── 网格 / 年份时间轴：都走同一个网格，时间轴只是多插了年份标题 ──
              // 滚动条走统一外壳（全局开关 + 出现时机 + 外观，见 ui/utils/SideScrollbar.kt）
              else -> LazyGridSideScrollbar(state = gridState) {
                LazyVerticalGrid(
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
    // 标题用 stickyHeader —— 往下滚时它吸在网格顶部，
  // 随时告诉你「现在看的是哪一年」。这一点对「时间轴」这个视图模式是本质的：
       // 不吸顶的话它就和普通分组列表没区别了（清单 4.1）。
     //
       // ⚠️ stickyHeader 里**不能**再写 `span` —— stickyHeader 本身就是整行占满的，
       // 给它 span 反而会报编译错。
  val grouped = displayItems
      .groupBy { it.ProductionYear ?: 0 }
   // 显式给出泛型参数：`compareByDescending { it }` 的 lambda 参数类型
           // 在这种链式上下文里推不出来，会报「缺少 operator 修饰符」
   .toSortedMap(compareByDescending<Int> { it })
   grouped.forEach { (year, group) ->
  stickyHeader(key = "year_header_$year") {
    EmbyYearHeader(
   year = if (year > 0) "$year" else "年份未知",
    count = group.size,
    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
   )
   }
           items(group, key = { it.Id ?: it.Name ?: "" }) { item ->
        val s = server ?: return@items
 EmbyLibraryMediaCell(
        item = item,
      server = s,
      viewModel = viewModel,
      cardStyle = cardStyle,
          folderCovers = folderCovers,
          folderRandomCover = folderRandomCover,
          folderCoverBatchReady = folderCoverBatchReady,
          folderCoverGen = folderCoverGen,
   onClick = {
            // 混合库照片档：队列取当前列表里的照片快照（点击时才算，不随重组白算）
            val photos = displayItems.filter { it.Type == "Photo" }
            openItem(
              item,
              backStack,
              s,
              context,
              photoQueue = photos,
              photoIndex = photos.indexOfFirst { it.Id == item.Id }.coerceAtLeast(0),
              photoParentId = libraryId,
              collectionType = collectionType,
            )
          },
    onLongClick = { offset ->
  actionTarget = item
               menuAnchor = offset
         },
          onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
          titleMaxLines = titleLines,
          // 「剩余时长」角标只在「继续播放」分类里给（用户反馈：别的分类里是噪音）
          showRemaining = category == EmbyCategory.RESUMABLE,
          modifier = Modifier.animateItem(),
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
                      folderRandomCover = folderRandomCover,
                      folderCoverBatchReady = folderCoverBatchReady,
                      folderCoverGen = folderCoverGen,
                      onClick = {
                        // 混合库照片档：队列取当前列表里的照片快照（点击时才算，不随重组白算）
                        val photos = displayItems.filter { it.Type == "Photo" }
                        openItem(
                          item,
                          backStack,
                          s,
                          context,
                          photoQueue = photos,
                          photoIndex = photos.indexOfFirst { it.Id == item.Id }.coerceAtLeast(0),
                          photoParentId = libraryId,
                          collectionType = collectionType,
                        )
                      },
                      onLongClick = { offset ->
                        actionTarget = item
                        menuAnchor = offset
                      },
                      onToggleFavorite = if (quickFavoriteEnabled) ({ toggleFavoriteQuick(item, s) }) else null,
                      titleMaxLines = titleLines,
                      // 「剩余时长」角标只在「继续播放」分类里给（用户反馈：别的分类里是噪音）
                      showRemaining = category == EmbyCategory.RESUMABLE,
                      modifier = Modifier.animateItem(),
                    )
                  }
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

    // ── 筛选面板（2026-10-09 改为「加号 + 复选对话框 + 圆角标签」版式）──
    //
    // 版式：抽屉里每个「多选」类目一行 = 类目名 + 右侧 `+` 圆钮。
    // 点 `+` 弹一个复选框对话框（取消 / 确定），确定后把选中的项作为
    // **带叉号的圆角标签**追加到该类目下方；点标签上的叉即可单独移除。
    //
    // 为什么改成这样：原先每个类目一个就地展开的下拉，12 个维度叠起来又长又乱，
    // 展开/收起还会引起「弹窗跳动」与「点选项没反应」等一串问题（见 2026-10-09 回退记录）。
    // 现在把「选什么」收进独立的对话框，抽屉只负责展示「已选了什么」，
    // 层次清楚、也不再有任何就地展开的重排。
    //
    // 形态分工：
    // - 多选类目（类型 / 标签 / 年份 / 官方分级 / 评分）→ 加号 + 对话框 + 圆角标签
    // - 单选类目（播放状态 / 字幕）→ 保留下拉收缩框（语义上就是「从几项里挑一个」）
    // - 开关类（中文字幕 / 只看收藏）→ Switch
    // 已删除：演员 / 导演、工作室、清晰度、3D（用户要求去掉这些维度）。
    if (showFilterDialog) {
      // skipPartiallyExpanded：面板只有「展开 / 收起」两态，不允许停在半展开。
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
            // 恢复默认：清空全部草稿条件。注意**只改草稿**，要点「确定」才真的应用，
            // 这样用户在抽屉里点错了还能用「取消」整体撤销。
            // 演员 / 工作室 / 清晰度 / 3D 这几个维度 UI 入口已撤，但老偏好里可能还留着值，
            // 不一并清掉的话「恢复默认」之后列表仍被它们过滤着（用户会以为没恢复干净）。
            TextButton(onClick = {
              draftGenres = emptySet()
              draftTags = emptySet()
              draftYears = emptySet()
              draftRatings = emptySet()
              draftMinRating = null
              draftFavorite = false
              draftPlayed = null
              draftSubtitles = null
              draftChinese = false
              // 无草稿的隐藏维度直接清正式值（它们不参与抽屉的编辑/取消语义）
              selectedPersonIds = emptySet()
              selectedStudioIds = emptySet()
              hdFilter = null
              threeDFilter = null
            }) {
              Text("恢复默认")
            }
          }

          // 类型：多选（加号 → 复选对话框 → 圆角标签）
          FilterCategoryRow(
            label = "类型",
            allOptions = filterOptions.genres,
            selected = draftGenres,
            onConfirm = { picked -> draftGenres = picked },
            onRemove = { name -> draftGenres = draftGenres - name },
          )
          // 标签：多选
          FilterCategoryRow(
            label = "标签",
            allOptions = filterOptions.tags,
            selected = draftTags,
            onConfirm = { picked -> draftTags = picked },
            onRemove = { name -> draftTags = draftTags - name },
          )
          // 年份：多选（对外是字符串，切回 Int 再存）
          FilterCategoryRow(
            label = "年份",
            allOptions = filterOptions.years.map { it.toString() },
            selected = draftYears.map { it.toString() }.toSet(),
            onConfirm = { picked ->
              draftYears = picked.mapNotNull { it.toIntOrNull() }.toSet()
            },
            onRemove = { v ->
              v.toIntOrNull()?.let { draftYears = draftYears - it }
            },
          )
          // 官方分级：多选
          FilterCategoryRow(
            label = "官方分级",
            allOptions = filterOptions.officialRatings,
            selected = draftRatings,
            onConfirm = { picked -> draftRatings = picked },
            onRemove = { name -> draftRatings = draftRatings - name },
          )
          // 评分：单选语义，但沿用「加号 + 对话框」；确定后同样以圆角标签展示
          FilterCategoryRow(
            label = "评分",
            allOptions = RATING_OPTIONS.map { it.first }.filter { it != "不限" },
            selected = RATING_OPTIONS.firstOrNull { it.second == draftMinRating }
              ?.let { setOf(it.first) } ?: emptySet(),
            onConfirm = { picked ->
              // 单选：取最后一个（对话框内是单选行为，picked 至多一个）
              draftMinRating = picked.lastOrNull()?.let { v ->
                RATING_OPTIONS.firstOrNull { it.first == v }?.second
              }
            },
            onRemove = { draftMinRating = null },
          )

          // 播放状态：单选，再点一次已选的那项就取消（保留下拉收缩框）
          FilterDropdown(
            label = "播放状态",
            options = listOf("已看", "未看"),
            selectedNames = when (draftPlayed) {
              true -> setOf("已看")
              false -> setOf("未看")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "已看"
              draftPlayed = if (draftPlayed == target) null else target
            },
            onClear = { draftPlayed = null },
          )
          // 字幕：单选（保留下拉收缩框）
          FilterDropdown(
            label = "字幕",
            options = listOf("有字幕", "无字幕"),
            selectedNames = when (draftSubtitles) {
              true -> setOf("有字幕")
              false -> setOf("无字幕")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "有字幕"
              draftSubtitles = if (draftSubtitles == target) null else target
            },
            onClear = { draftSubtitles = null },
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
              checked = draftChinese,
              onCheckedChange = { draftChinese = it },
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
              checked = draftFavorite,
              onCheckedChange = { draftFavorite = it },
            )
          }

          // ── 底部动作条：草稿在这里一次性提交 ──
          // 用户要求「改完点确定才筛」，所以抽屉里任何勾选都不立刻生效。
          // 「确定」把草稿灌回正式状态 + 落盘 + 重载（条件没变就不重拉）；
          // 「取消」直接关抽屉，草稿随下次打开时的重新快照丢弃。
          Spacer(modifier = Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = { showFilterDialog = false },
              modifier = Modifier.weight(1f),
            ) {
              Text("取消")
            }
            Button(
              onClick = {
                val genres = draftGenres
                val tags = draftTags
                val years = draftYears
                val ratings = draftRatings
                val played = draftPlayed
                val subs = draftSubtitles
                val chinese = draftChinese
                val minR = draftMinRating
                val fav = draftFavorite
                val changed = genres != selectedGenres || tags != selectedTags ||
                  years != selectedYears || ratings != selectedRatings ||
                  played != playedFilter || subs != subtitlesFilter ||
                  chinese != chineseSubsOnly || minR != minRating || fav != favoriteOnly
                selectedGenres = genres
                selectedTags = tags
                selectedYears = years
                selectedRatings = ratings
                playedFilter = played
                subtitlesFilter = subs
                chineseSubsOnly = chinese
                minRating = minR
                favoriteOnly = fav
                persistFilter()
                showFilterDialog = false
                // 条件真的变了才重新加载；否则只是关了抽屉，不白跑一次请求
                if (changed) scope.launch { load(reset = true) }
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("确定")
            }
          }
        }
      }
    }


    // 视图设置：**排布方式** + 卡片样式 + 每行个数 + 标题行数。
    //
    // 设计约束（重做过一版）：**一屏放下、不滚动**。上一版每一项都铺成一整行
    // 带单选钮的列表，四个分区叠下来超出屏幕、必须滚动，既显得笨重又把
    // 「随手调一下」这件事变得很沉。现在改成：
    // - 排布方式 / 卡片样式用 `FilterChip` 横排 —— 一眼看全所有选项，点一下即生效；
    // - 两个 `− N +` 数字调节器收进 `Row`，各占一半宽，不再各占一整行；
    // - 不写长说明（说明挪进选项文案本身，比如「年份」）。
    // 即选即存（没有「确定」按钮），所以底部只留一个「完成」。
    if (showStyleDialog) {
      AlertDialog(
        onDismissRequest = { showStyleDialog = false },
        title = { Text("视图") },
        text = {
          Column(modifier = Modifier.fillMaxWidth()) {
            // ── 排布方式 ──
            SectionLabel("排布方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EmbyLibraryViewMode.entries.forEach { mode ->
                FilterChip(
                  selected = mode == viewMode,
                  onClick = { browserPreferences.embyLibraryViewMode.set(mode.key) },
                  label = { Text(mode.label) },
                  leadingIcon = if (mode == viewMode) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                  } else {
                    null
                  },
                )
              }
            }

            // 紧凑列表没有「卡片」这个概念：样式 / 列数 / 标题行数全都不适用，
            // 下面三块整体收掉，弹窗也顺势变矮。
            if (viewMode != EmbyLibraryViewMode.COMPACT) {
              Spacer(modifier = Modifier.height(16.dp))
              SectionLabel("卡片样式")
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmbyCardStyle.entries.forEach { style ->
                  FilterChip(
                    selected = style == cardStyle,
                    onClick = { browserPreferences.embyLibraryCardStyle.set(style.name) },
                    label = { Text(style.label) },
                    leadingIcon = if (style == cardStyle) {
                      { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                      null
                    },
                  )
                }
              }

              Spacer(modifier = Modifier.height(16.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 列数只对「海报」生效：背景图 / 横幅是宽图，列数由自身最小宽度
                // 自适应决定，硬塞进固定列数会被压得又窄又小。
                if (cardStyle == EmbyCardStyle.POSTER) {
                  CompactStepper(
                    label = "每行个数",
                    value = gridColumns.coerceIn(2, 6),
                    min = 2,
                    max = 6,
                    onChange = { browserPreferences.embyLibraryGridColumns.set(it) },
                    modifier = Modifier.weight(1f),
                  )
                }
                CompactStepper(
                  label = "标题行数",
                  value = titleLines.coerceIn(1, 2),
                  min = 1,
                  max = 2,
                  onChange = { browserPreferences.embyLibraryTitleLines.set(it) },
                  modifier = Modifier.weight(1f),
                )
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
  /** 文件夹宫格封面是否随机合成：影响取图顺序，也参与缓存键 */
  folderRandomCover: Boolean,
  /** 这一屏的「批量取封面」是否已经出结果；false 时先别自己发请求，免得和批量重复拉一遍 */
  folderCoverBatchReady: Boolean,
  /**
   * 「封面代数」：下拉刷新时 +1，进 [LaunchedEffect] 的 key。
   *
   * 它保证**走单文件夹兜底**的那些文件夹（批量覆盖不到的，比如合集）也跟着重取 ——
   * 否则刷新只有批量覆盖到的那批会换图，兜底那批永远不变（用户反馈的「刷新换图失效」）。
   */
  folderCoverGen: Int,
  onClick: () -> Unit,
  onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)?,
  onToggleFavorite: (() -> Unit)?,
  titleMaxLines: Int = 1,
  /**
   * 是否在卡片左下角显示「剩余 xx分xx秒」。
   *
   * 只有「继续播放」分类传 true：那一档里每一条都是续播中的，剩余时长才是有用信息。
   * 放到其它分类（全部 / 合集 / 收藏）就是噪音 —— 用户明确要求「只在该类目显示」。
   * 演员条目的「N 部」角标与它无关，不受这个开关影响。
   */
  showRemaining: Boolean = false,
  /** 列表 item 传进来的修饰符（`Modifier.animateItem()`），挂在卡片根节点上做重排位移 */
  modifier: Modifier = Modifier,
) {
  // 混合库照片档：照片与视频是两套卡片（无进度 / 无收藏角标 / 瀑布流高度 /
  // 副标题是相机型号而不是年份），在这里就分道 —— 把照片塞进视频卡里，
  // 版式和信息都是错的。照片模式下服务端只会回 Photo，所以这条分支对纯视频列表零影响。
  if (item.Type == "Photo") {
    EmbyPhotoCard(
      item = item,
      displayUrl = viewModel.photoImageUrl(server, item),
      // 原格式（可能是 HEIC/AVIF）解不动时，退回服务端 format=jpg 转码
      fallbackUrl = viewModel.photoImageUrl(server, item, format = "jpg"),
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
    )
    return
  }
  val itemId = item.Id
  // 文件夹 / 合集这类容器条目自身没有封面图，改用内部视频的缩略图拼宫格。
  // ⚠️ 判定与「批量预取」共用 needsFolderMosaic()，**不要**在这里加
  //    「服务端给了借来的预览图就不拼」之类的豁免 —— 那个豁免会让被借图的
  //    视频文件夹只剩一宫格（真机现象），详见该函数的 KDoc。
  val needsMosaic = needsFolderMosaic(item)
  val folderCover: List<String>? = if (needsMosaic && itemId != null) {
    // 缓存键带上「随机 / 固定」：来回切开关时两种结果各存一份，不会互相串图。
    val coverKey = folderCoverKey(itemId, folderRandomCover)
    LaunchedEffect(coverKey, server.id, folderCoverBatchReady, folderCoverGen) {
      // 「要不要取」交给缓存按**代数**判断：同代里已有值 ⇒ beginLoad 返回 false
      //（滚动、重组的重复进入都不会重复请求）；换代后（下拉刷新）⇒ 返回 true，
      // 于是连**批量没覆盖到**的那批也会重取。
      // 批量那一步同理：`folderCoverBatchReady` 为 false 时先等它，免得「批量 + 逐个」双份请求。
      // ⚠️ 这里**不再**判断 `cached == null`：刷新时旧封面就在缓存里、而那正是要继续显示的一张，
      //    若因「已经有了」跳过重取，刷新换图就永远不会发生。
      if (folderCoverBatchReady && EmbyFolderCoverCache.beginLoad(coverKey, folderCoverGen)) {
        loadAndStoreFolderCover(viewModel, server, itemId, folderRandomCover, folderCoverGen, folderCovers)
      }
    }
    // 显示这份映射里的当前值：刷新时是旧封面（不清），新封面回来按同键覆盖后自动换新。
    folderCovers[coverKey]
  } else {
    null
  }

  EmbyMediaCard(
    title = viewModel.displayTitle(item),
    subtitle = itemSubtitle(item),
    imageUrl = viewModel.imageUrl(server, item, imageTypeFor(cardStyle), 480),
    fallbackImageUrl = viewModel.imageUrl(server, item, "Primary", 480),
    mosaicUrls = folderCover,
    // 左下角角标：演员是「作品数」（跟分类无关，一直显示）；
    // 「剩余时长」**只在「继续播放」分类里给**（用户反馈：别的分类里它只是噪音）。
    badgeText = item.ChildCount?.takeIf { item.Type == "Person" && it > 0 }?.toString()
      ?: remainingLabel(item).takeIf { showRemaining },
    progress = playbackProgress(item),
    isFavorite = item.UserData?.IsFavorite == true,
    onClick = onClick,
    style = cardStyle,
    fillWidth = cardStyle == EmbyCardStyle.POSTER,
    onLongClick = onLongClick,
    onToggleFavorite = onToggleFavorite,
    titleMaxLines = titleMaxLines,
    // 文件夹类型图标：与宫格封面 / 「点进去还是播放」共用 isFolderLike 这一个判定，
    // 避免卡片与批量逻辑各写一份、改一处漏一处（见 needsFolderMosaic 的 KDoc）。
    isFolder = isFolderLike(item),
    modifier = modifier,
  )
}

/**
 * 工具行图标按钮，**支持长按**。
 *
 * 为什么不用 `IconButton`：它只有 `onClick`，接不了长按；而「单击 = 下钻随机 /
 * 长按 = 不下钻」这组语义必须落在同一个图标上（否则工具行要多排一个按钮、
 * 一排图标也更挤）。自铺 48dp 圆形触控区 + `combinedClickable`，
 * 尺寸与 M3 `IconButton` 一致，所以和旁边的筛选 / 视图图标不会高低不齐。
 */
@Composable
private fun ToolbarIconAction(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
) {
  Box(
    modifier = Modifier
      .size(48.dp)
      .clip(CircleShape)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = contentDescription)
  }
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
  /** 是否为文件夹类条目：true 时在小海报左下角显示文件夹图标（与网格卡同一约定） */
  isFolder: Boolean = false,
  /** 列表 item 传进来的修饰符（`Modifier.animateItem()`），挂在根 Row 上做重排位移 */
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
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
      // 文件夹类型图标：与网格卡同一套底色与位置（左下角、让开底部进度条）。
      // 紧凑行的小海报只有 52dp 宽，图标相应缩到 10dp，但仍能一眼分出「进入 / 播放」。
      if (isFolder) {
        Surface(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 3.dp, bottom = 6.dp),
          shape = RoundedCornerShape(4.dp),
          color = MEDIA_BADGE_BG,
        ) {
          Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = "文件夹",
            tint = if (LocalEmbyAppearance.current.folderIconBlue) {
              FOLDER_ICON_BLUE
            } else {
              Color.White
            },
            modifier = Modifier
              .padding(horizontal = 2.dp, vertical = 2.dp)
              .size(10.dp),
          )
        }
      }
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

/**
 * 年份时间轴的组标题：**吸顶**（清单 4.1）。
 *
 * 原来它是一个普通 `item(span = 整行)`，会随网格一起滚走 ——
 * 那就只是「按年份分了段的列表」，看不出「时间轴」的意思。
 * 改成 `stickyHeader` 之后，往下滚时年份一直挂在网格顶部。
 *
 * @param modifier 外部传入背景色：吸顶元素**必须有不透明背景**，
 *   否则下层卡片会透上来，看着像重影。
 */
@Composable
private fun EmbyYearHeader(
  year: String,
  count: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
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

/**
 * 「视图」弹窗里的分区小标题。
 *
 * 弹窗要挤在一屏内，标题不能像正文那样占一整行，所以用 `labelLarge` + 主题色的
 * 克制样式，只起到「下面这排属于哪一项」的提示作用。
 */
@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 6.dp),
  )
}

/**
 * 「视图」弹窗里的紧凑数字调节器：`标签` 在上，`− N +` 在下。
 *
 * 为什么不复用上一版的写法（标签占一整行、加减键各一个 `IconButton`）：
 * 那样一个调节器就要占掉整整一行，四个分区叠起来必然超屏、只能滚动。
 * 这里把加减键缩成 32dp 的小按钮，两个调节器并排放进同一个 `Row`，高度减半。
 *
 * 与旧版一致：**钳到边界时对应按钮自动禁用**（`enabled = value > min / < max`），
 * 让人一眼看出已经到头了，点了不会没反应。
 */
@Composable
private fun CompactStepper(
  label: String,
  value: Int,
  min: Int,
  max: Int,
  onChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        IconButton(
          onClick = { onChange((value - 1).coerceAtLeast(min)) },
          enabled = value > min,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(Icons.Default.Remove, contentDescription = "减少$label", modifier = Modifier.size(18.dp))
        }
        Text(
          text = value.toString(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
          onClick = { onChange((value + 1).coerceAtMost(max)) },
          enabled = value < max,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(Icons.Default.Add, contentDescription = "增加$label", modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

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
        // ⚠️ 必须是短词：这一行三个 chip 并排，`年份时间轴` 五个字挤不下会折成两行，
        // 三个 chip 高度不齐、看着很割裂。含义靠 `key` / 枚举名与分组标题保留。
        YEAR -> "年份"
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
  /**
   * 文件夹。
   *
   * ⚠️ 必须带上 `PhotoAlbum`：Emby 把**相册**也当成一种容器条目（`Type=PhotoAlbum`），
   * 而它不在 `Folder/CollectionFolder/UserView` 里。线上实测库 1133915（家庭视频）：
   * 顶层 87 个「文件夹」= 84 个 PhotoAlbum + 3 个普通 Folder ——
   * 不带 PhotoAlbum 时这一档只显示 3 项，与 Web 端（87）严重不符。
   * 加进白名单后服务端返回 87，与 Web 一致（`IncludeItemTypes` 传的就是这四个）。
   */
  FOLDER("文件夹", listOf("Folder", "CollectionFolder", "UserView", "PhotoAlbum"), null),
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
 * 过滤「幽灵条目」：服务端混进列表、但本质上不是媒体的条目。
 *
 * 判据刻意只取几条很保守的特征，正常媒体（Id 是数字 / GUID、名字是片名）不会被误伤：
 *
 * - Id 里带 `://`（Emby 的条目 Id 永远不含这个）——
 *   扫描时把文件内容当成条目名的产物，比如名字就叫 `http:`（URL 的 scheme 那截）；
 * - 名字 / Id 里出现 JSON 的 `"键":` 片段 —— 元数据文件里是一段 JSON 时，
 *   Emby 会把其中一节解析成「条目」，名字长成 `frames":2670` 这样；
 * - 名字以 `{` / `[` 开头（整段 JSON 被当成名字）。
 *
 * ⚠️ **不要**因为「库里有 strm 指向远程 ISO」就把URL 型条目放进来 ——
 * 那两件事没有因果关系。曾把它们保留并接上 ISO 播放，是把两个不相干的现象
 * 硬连成一块（详见 `MEMORY.md` 的 ISO 章节）。ISO 播放只走「正常条目」那条路。
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
  /** 混合库照片档：当前列表里的照片队列快照（点开哪张就从哪张播起） */
  photoQueue: List<EmbyItem> = emptyList(),
  /** 该照片在 [photoQueue] 里的下标 */
  photoIndex: Int = 0,
  /** 照片队列的父级 Id（库 Id）：浏览器翻到底时按它继续拉下一页 */
  photoParentId: String = "",
  /**
   * 当前页（父页）的媒体库类型，供子页**继承**上下文。
   *
   * Emby 只在库根（CollectionFolder）上写 CollectionType，子文件夹拿到的
   * [EmbyItem.CollectionType] 通常是 null。若直接把它传给子页，子页会以为自己是
   * 「未知库」——丢掉「这是家庭视频 / 照片库」的语义，查询口径（MediaTypes /
   * 内容原样档）跟着错位，症状是「点进文件夹后照片目录一片空」。
   */
  collectionType: String? = null,
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

      // 相册：Emby 的「文件夹」分类里也会有它（照片库 / 家庭视频库），但装的是
      // 照片不是视频 —— 必须进专用照片网格（瀑布流 + 排序 + 随机播放）。
      // ⚠️ 不能走下面的 Folder 分支：那边进的是视频库页，默认「仅媒体」口径
      //    （MediaTypes=Video + IsFolder=false）会把照片条目全滤掉，点开是空页。
      "PhotoAlbum" -> backStack.add(
        EmbyPhotoGridScreen(
          libraryId = id,
          title = item.Name ?: "",
          initialAlbumId = id,
          initialAlbumName = item.Name ?: "",
        ),
      )

      // 真实文件夹（Type=Folder）：**必须**用「内容原样」口径打开。
      // 库类型（movie/tvshows）是对「库」的分类，不是对「文件夹里装了什么」的约束；
      // 继续套库类型白名单 + IsFolder=false 会把这一层整层滤空 ——
      // 症状就是「点进文件夹只剩子目录、里面的视频全不见」。见 [isFolderDive] 的说明。
      "Folder" -> backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = item.Name ?: "",
          collectionType = item.CollectionType ?: collectionType,
          isFolderDive = true,
        ),
      )

      "CollectionFolder", "UserView", "BoxSet" -> backStack.add(
        EmbyLibraryScreen(
          libraryId = id,
          title = item.Name ?: "",
          // ⚠️ 必须**继承父页的 collectionType**：Emby 只在库根（CollectionFolder）上
          // 写 CollectionType，子文件夹拿到的通常是 null。若直接传 item.CollectionType，
          // 子页会以为自己是「未知库」—— 于是丢掉「这是家庭视频/照片库」的上下文，
          // 查询口径（MediaTypes / 内容原样档）全跟着错位。这正是「点进文件夹后
          // 照片目录一片空」的根因之一。item 自己带了就用它（极少见），否则继承。
          collectionType = item.CollectionType ?: collectionType,
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

      // 混合库照片档：照片直接进全屏浏览器（不进视频详情页 —— 那边没有照片的展示逻辑）。
      // 队列 = 当前列表里的照片快照，续页按 photoParentId 拉。
      "Photo" -> backStack.add(
        EmbyPhotoBrowserScreen(
          initialPhotos = photoQueue,
          initialIndex = photoIndex,
          parentId = photoParentId,
          title = item.Name ?: "",
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
 * 筛选面板里的「类目行」：一行类目名 + 右侧 `+` 圆钮；下方铺已选中的圆角标签。
 *
 * 交互（2026-10-09 新版式）：
 * - 点 `+` → 弹 [FilterOptionPickerDialog]（复选框列表 + 取消 / 确定）；
 * - 点确定 → 回传整份新选中集合（`onConfirm`），对话框关闭、抽屉保持打开；
 * - 已选项在下方以**带叉号的圆角标签**呈现，点标签上的 `×` 单独移除该项（`onRemove`）。
 *
 * 为什么不做成就地展开的下拉：12 个维度一旦就地展开又长又乱，
 * 展开/收起还会引起「弹窗跳动」与「点选项没反应」等一串问题（见 2026-10-09 记录）。
 * 把「选什么」收进独立对话框后，抽屉只负责展示「已选了什么」，层次清楚、无重排。
 *
 * [allOptions] 为空时整组隐藏（服务端没给或该库没有这个维度），不占地方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterCategoryRow(
  label: String,
  allOptions: List<String>,
  selected: Set<String>,
  onConfirm: (Set<String>) -> Unit,
  onRemove: (String) -> Unit,
) {
  if (allOptions.isEmpty()) return
  var showPicker by remember { mutableStateOf(false) }
  // 已选但当前选项列表里没有的（例如换了库、或标记配置变了）也要显示，
  // 否则用户看得到筛选在生效、却在抽屉里找不到是哪个条件。
  val shown = allOptions.filter { it in selected } + selected.filter { it !in allOptions }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f),
      )
      // 「+」：形状明确的圆形按钮，点开复选对话框
      FilledTonalIconButton(
        onClick = { showPicker = true },
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = "添加$label",
          modifier = Modifier.size(18.dp),
        )
      }
    }
    // 已选项：带叉号的圆角标签，点叉单独移除
    if (shown.isNotEmpty()) {
      FlowRow(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        shown.forEach { name ->
          FilterTag(text = name, onRemove = { onRemove(name) })
        }
      }
    }
  }

  if (showPicker) {
    FilterOptionPickerDialog(
      title = label,
      allOptions = allOptions,
      initiallySelected = selected,
      onDismiss = { showPicker = false },
      onConfirm = { picked ->
        onConfirm(picked)
        showPicker = false
      },
    )
  }
}

/** 已选筛选条件的一枚圆角标签：文字 + 尾部 `×`；点 `×` 移除该项。 */
@Composable
private fun FilterTag(
  text: String,
  onRemove: () -> Unit,
) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .background(MaterialTheme.colorScheme.secondaryContainer)
      .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    // 只有叉号可点（不用整枚标签可点）：避免误触把标签删掉
    IconButton(
      onClick = onRemove,
      modifier = Modifier.size(20.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "移除 $text",
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

/**
 * 「类目 → 复选框列表 → 取消 / 确定」的选项对话框。
 *
 * 内部维护一份**草稿**（`draft`）：勾选只改草稿，点「确定」才回传，
 * 这样用户在对话框里来回勾的结果是一次性生效的，也才谈得上「取消」。
 *
 * 选项多时列表自身滚动（封顶 380dp），不会把对话框撑出屏幕。
 */
@Composable
private fun FilterOptionPickerDialog(
  title: String,
  allOptions: List<String>,
  initiallySelected: Set<String>,
  onDismiss: () -> Unit,
  onConfirm: (Set<String>) -> Unit,
) {
  // key 用 initiallySelected：每次从抽屉打开都从「当前已选」起勾
  var draft by remember(initiallySelected) { mutableStateOf(initiallySelected) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 380.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        allOptions.forEach { option ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                draft = if (option in draft) draft - option else draft + option
              }
              .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Checkbox(
              checked = option in draft,
              onCheckedChange = {
                draft = if (option in draft) draft - option else draft + option
              },
            )
            Text(
              text = option,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(draft) }) { Text("确定") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

/**
 * 筛选面板里的「下拉选择」：一行摘要 + 点开后纵向铺开全部选项。
 *
 * 原来是横向滑动的 chip 组 —— 类型、演员这种动辄几十项的维度横向滑根本没法找，
 * 而且滑到后面完全不知道还剩多少。改成下拉后一屏能扫十几项，末尾还有「不限」一键清空。
 *
 * 选项为空时整组隐藏：说明这个维度服务端没给或该库没有，不占地方。
 * 展开区最高 260dp，超出后自己在组内滚动，不会把整个面板撑得很长。
 *
 * ⚠️ 2026-10-09：中间试过一版「两列网格 + 就地展开 + 选中自动收起」，真机上点选项
 * 没反应。已整体回退成这一版（展开状态由组件自己持有，不再由外部互斥控制）。
 * 目前仅「播放状态 / 字幕」这类单选维度还在用它（多选维度改走 `FilterCategoryRow`）。
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
 * 单个条目是否满足「类型 / 标签」这一维的筛选。
 *
 * [selected] 为空 = 这一维不限；否则只要 [values]（条目的 Genres 或 Tags）
 * 里任意一项命中就算通过（同一维多选取并集，与 Emby 语义一致）。
 *
 * 之所以是「按名字本地筛」而不是交给服务端，见 `EmbyLibraryScreen` 里
 * `load()` / `runScan()` 的说明：`/Items?Genres=` / `?Tags=` 在部分服务端会 500。
 */
private fun matchesSelectedNames(selected: Set<String>, values: List<String>?): Boolean =
  selected.isEmpty() || values.orEmpty().any { it in selected }

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
 * 这个容器条目要不要拼宫格封面。
 *
 * 判定只有两条：是容器、且自己没有主图。
 *
 * ⚠️⚠️ **这里绝对不能加「有借来的预览图就不拼」这种豁免** —— 曾经有过一条
 * `!item.hasBorrowedPrimaryImage()`，在真机上造成的现象是「同是视频文件夹，有的四宫格、
 * 有的一整张缩略图」（用户反馈）。原因是：
 *
 * 1. 服务端会**给视频文件夹也「借」一张图**（`PrimaryImageItemId` 指向它内部第一条媒体，
 *    见 `EmbyPrimaryImage` 的线上实证）。文件夹自己 `ImageTags` 里没有 `Primary`，
 *    于是「借来的图」这条路一走，宫格就被整条跳过，直接显示那**一张**图 ——
 *    而那张图就是它内部第一个视频的一帧，摆在宫格的位置上就是「只有一宫格」。
 * 2. 「服务端被借图」这件事对哪些文件夹成立、对哪些不成立，用户完全不可预期
 *    ⇒ 同一屏里就出现了四宫格 / 一宫格并存的割裂观感。
 * 3. 更关键的是：那条豁免**本来是想省掉相册的子项查询**（用户服务器上有 84 个相册），
 *    可相册（`PhotoAlbum`）压根不在 [FOLDER_TYPES] 里、也走不到这个函数 ——
 *    这条豁免从头到尾**没有一次是按设计意图生效的**，只是在误伤视频文件夹。
 *    相册要走照片网格页（见 `openItem` 的 PhotoAlbum 分支），预览图由那边显示。
 *
 * 两处调用（批量预取 `mosaicFolderIds` 与卡片 `EmbyLibraryMediaCell`）必须共用这一个
 * 函数：两处各写一份内联判定，一旦其中一处改了、另一处没改，就会出现
 * 「进了批量预取但卡片不认」或反过来的半套行为，比不做还难查。
 */
private fun needsFolderMosaic(item: EmbyItem): Boolean =
  isFolderLike(item) && item.ImageTags["Primary"] == null

/**
 * 能「扫描」的条目：媒体库（UserView / CollectionFolder）和真实文件夹。
 *
 * 与 [isFolderLike] 的差别是刻意排除 BoxSet（合集）—— 它只是个虚拟分组，
 * 背后没有对应的物理目录，让服务器去扫它扫不出任何东西。
 */
private fun isScannableFolder(item: EmbyItem): Boolean = item.Type in FOLDER_TYPES

/**
 * 文件夹宫格封面的缓存键：**文件夹 Id + 取样模式**。
 *
 * ⚠️ 键的拼法只留这一处 —— 卡片的读取、下拉刷新的重取、[EmbyFolderCoverCache] 的存取
 * 全都调它。分两处手写迟早会对不上（对不上就是「切开关后看到串掉的旧图」）。
 */
private fun folderCoverKey(
  folderId: String,
  random: Boolean,
): String = "$folderId|$random"

/**
 * 取一次文件夹宫格封面，并**同时**写进进程内缓存与当前页面这份内存映射。
 *
 * 两条路径都走它：卡片首次出现时的 `LaunchedEffect`、以及下拉刷新时的重取。
 * 抽成一个函数是因为「写进哪儿」必须一致 —— 只写内存映射不写缓存，
 * 离开页面再回来就又是旧的；只写缓存不写映射，当前这一屏不会变。
 *
 * ⚠️ 被取消（换代 / 离开页面）时**不能写入**：这时上层拿到的会是空列表（取封面那条
 * `runCatching` 语义），一写就把缓存里的旧封面覆盖成空 ⇒ 刷新瞬间反而变空白。
 * 所以这里捕获取消、摘掉「在飞」标记后原样抛出。
 */
private suspend fun loadAndStoreFolderCover(
  viewModel: EmbyViewModel,
  server: EmbyServer,
  folderId: String,
  random: Boolean,
  generation: Int,
  into: MutableMap<String, List<String>>,
) {
  val key = folderCoverKey(folderId, random)
  val urls =
    try {
      viewModel.loadFolderCoverUrls(server, folderId, count = 4, random = random)
    } catch (e: CancellationException) {
      EmbyFolderCoverCache.abort(key)
      throw e
    }
  EmbyFolderCoverCache.put(key, urls, generation)
  into[key] = urls
}

/**
 * 文件夹宫格封面的进程内缓存（键是「文件夹 Id + 取样模式」的组合串）。
 *
 * 一个文件夹的封面要额外发一次 /Items 查询才能拿到子项缩略图，比较贵；
 * 缓存后滚动列表、切分类、从子页面返回都不必重复请求。
 * 取不到的（空文件夹）会缓存成空列表，避免反复重试把服务器打满。
 *
 * ⚠️ 键里带「随机 / 固定」模式：随机封面的结果每次都可能不同，
 * 若只按文件夹 Id 存，用户在设置里来回切开关就会看到串掉的旧图。
 *
 * ⭐ 「换代」而不是「清空」：缓存里的旧封面是**下拉刷新期间要继续显示**的东西
 * （清空就会露出一片空占位，用户反馈「空占位有点丑」）。所以刷新只把 [generation] +1，
 * 老条目原地留着显示，重取回来再把同键的值换掉 —— 视觉上是「换脸」，不是「先变空再出现」。
 */
private object EmbyFolderCoverCache {
  /**
   * 最多留多少条合成结果。
   *
   * ⭐ 一条只是「4 个图片地址字符串」，几百条的开销可以忽略 —— 定小了反而是坑：
   *    被淘汰的文件夹下次进来要**重新取样**，四宫格当场洗牌，用户看到的就是
   *    「有些封面又重新生成了一遍，还挺随机」。定大一点，正常翻库根本碰不到淘汰。
   */
  private const val MAX_ENTRIES = 800

  /** 一条封面：图片地址 + 它属于哪一代（见 [generation]） */
  private class Cover(
    val urls: List<String>,
    val generation: Int,
  )

  private val entries = LinkedHashMap<String, Cover>()

  /** 正在飞的键（同一键同一时间只允许一个请求） */
  private val loading = HashSet<String>()

  /**
   * 「封面代数」：下拉刷新时 +1。
   *
   * 放在这里（而不是页面里）是因为**它必须跨页面存活** —— 页面重建时若从 0 重新数起，
   * 就会和缓存里条目的代数对不上，于是每次进页面都白重取一遍，进程内缓存白做。
   */
  @Volatile
  private var generation = 0

  fun currentGeneration(): Int = generation

  /** 下拉刷新时调用；之后所有文件夹（含走单文件夹兜底那批）都会在重取完成后换图。 */
  @Synchronized
  fun bumpGeneration() {
    generation++
  }

  @Synchronized
  fun snapshot(): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>(entries.size)
    entries.forEach { (key, cover) -> out[key] = cover.urls }
    return out
  }

  /**
   * 返回 true 表示这次由调用方发起加载。
   *
   * @param gen 调用方当前的封面代数（[currentGeneration]）。同一代里已经有值 ⇒ 不再取；
   *   换代后 ⇒ 允许再取一次。这就是「刷新时保留旧图、后台换新图」的开关。
   */
  /**
   * 这一代里这张封面**是不是已经有结果了**。
   *
   * 批量预取用它筛掉已经拼好的文件夹，避免「整批重取一遍」。
   *
   * ⭐ 用 `>=` 而不是 `==`：换代极快时可能出现「缓存里存的是更新的代」这种情况
   *    （比如连续两次下拉刷新），这时它对我们这一代**同样算已定**，不该再取；
   *    判成 `==` 就会把新代的结果又覆盖一次，画面白闪。
   */
  @Synchronized
  fun isResolved(
    folderId: String,
    gen: Int,
  ): Boolean {
    val cover = entries[folderId] ?: return false
    return cover.generation >= gen
  }

  @Synchronized
  fun beginLoad(
    folderId: String,
    gen: Int,
  ): Boolean {
    if (loading.contains(folderId)) return false
    if (isResolved(folderId, gen)) return false
    loading.add(folderId)
    return true
  }

  @Synchronized
  fun put(
    folderId: String,
    urls: List<String>,
    gen: Int,
  ) {
    loading.remove(folderId)
    // 迟到的上一代结果不要盖掉新代（换代瞬间两代请求可能同时在飞）
    val existing = entries[folderId]
    if (existing != null && existing.generation > gen) return
    entries.remove(folderId)
    entries[folderId] = Cover(urls, gen)
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }

  /**
   * 请求作废（失败 / 被取消）：只摘掉「在飞」标记，**不写值**。
   *
   * ⚠️ 不清这个标记的话，换代时被取消的那次请求会把键永久钉在 `loading` 里，
   * 之后 [beginLoad] 恒返回 false ⇒ 那个文件夹再也换不了图。
   */
  @Synchronized
  fun abort(folderId: String) {
    loading.remove(folderId)
  }
}

/**
 * 「全部」分类下按媒体库类型限定条目类型。
 *
 * Emby 的 Recursive 查询在不指定类型时会把季、集、文件夹一起返回，
 * 列表会变得很杂；官方客户端的做法就是按库类型取「顶层条目」。
 * 返回 null 表示不限制类型（未知 / 混合 / 家庭视频库）。
 */
private fun allItemTypesFor(collectionType: String?): List<String>? =
  when (collectionType?.lowercase()) {
    "movies" -> listOf("Movie")
    "tvshows" -> listOf("Series")
    "music" -> listOf("MusicAlbum")
    "musicvideos" -> listOf("MusicVideo")
    "boxsets" -> listOf("BoxSet")
    // 家庭视频库不能按条目类型卡：Emby 把这类库的内容存成 Movie / Episode
    // （MediaType=Video），库里根本没有 Type=Video 的条目 —— 线上实测 12741 条里
    // Video 类型为 0，传 IncludeItemTypes=Video 一条都取不到，就是「骨架屏转不停」。
    // 「只要视频」交给 mediaTypesFor 的 MediaTypes=Video（同一库实测返回 10615 条）。
    "homevideos" -> null
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
    /**
     * 普通分页已向服务端消耗的条数（续页 StartIndex 用）。
     *
     * 必须与 items.size 分开存：客户端过滤 / 去重会让后者小于真实消耗，
     * 恢复列表时若拿 items.size 当游标，返回后续页会退回到见过的地方重拉。
     */
    var consumedCount: Int = 0
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
    consumedCount: Int = 0,
    complete: Boolean = true,
  ) {
    val entry = entries.getOrPut(key) { Entry() }
    entry.items = items
    entry.totalCount = totalCount
    entry.hits = hits
    entry.scannedCount = scannedCount
    entry.consumedCount = consumedCount
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

