package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.marlboroadvance.mpvex.domain.dislike.DislikeKey
import app.marlboroadvance.mpvex.domain.dislike.DislikeRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyStudio
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.domain.emby.EmbyVersionProgress
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry
import app.marlboroadvance.mpvex.domain.emby.MediaSourcePreference
import app.marlboroadvance.mpvex.domain.emby.buildEmbyMediaInfoEntries
import app.marlboroadvance.mpvex.domain.emby.effectiveEmbyMediaSourceId
import app.marlboroadvance.mpvex.domain.emby.embyMediaSourceLabel
import app.marlboroadvance.mpvex.domain.emby.formatEmbyDurationFull
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyIdentifyDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyRefreshMetadataDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonDetail
import app.marlboroadvance.mpvex.ui.browser.emby.components.ExternalPlayerPickerDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberDominantColor
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import android.net.Uri
import androidx.compose.material.icons.outlined.Cast
import org.koin.compose.koinInject
import app.marlboroadvance.mpvex.dlna.DlnaSheet
import app.marlboroadvance.mpvex.dlna.DlnaCastManager
import app.marlboroadvance.mpvex.dlna.CastPayload
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 媒体详情页。
 *
 * 布局（沉浸式）：
 * 1. 顶部透明栏浮在剧照之上：左侧返回，右侧「标记为已播放 / 收藏 / 更多」
 * 2. 剧照（Backdrop）从屏幕顶端一直延伸到按钮下方，标题叠在剧照上
 * 3. 剧照下方依次是播放/删除按钮、简介、演职员，最后是完整的媒体编码信息
 */
@Serializable
data class EmbyDetailScreen(
  val itemId: String,
  val title: String,
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
    val dlnaManager = koinInject<DlnaCastManager>()
    var castSheetShown by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }

    // 下载状态：用来把详情页的下载按钮显示成「下载 / 下载中 12% / 已下载」
    val downloadViewModel: EmbyDownloadViewModel = viewModel(
      factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
    )
    val downloadTasks by downloadViewModel.tasks.collectAsState()

    var item by remember { mutableStateOf<EmbyItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 「更多」菜单里的刮削 / 刷新元数据：改为与媒体库长按菜单同一套的抽屉式实现
    var showIdentifySheet by remember { mutableStateOf(false) }
    var showRefreshSheet by remember { mutableStateOf(false) }
    // 「用外部播放器打开」：null = 弹窗没开；非 null = 这份候选列表
    var externalPlayers by remember { mutableStateOf<List<ExternalPlayerOption>?>(null) }
    var externalLastKey by remember { mutableStateOf("") }
    // 下拉刷新的转圈状态（转完由 PullRefreshBox 自己收起）
    val isRefreshing = remember { mutableStateOf(false) }
    // 收藏请求进行中：挡住连点。否则「点了取消、请求还没回来又点一下」会拿旧状态
    // 再算一次方向，把刚取消的又收藏回去 —— 看起来就像「只能点亮、取消不了」。
    var favoriteBusy by remember { mutableStateOf(false) }
    // 点播放 → 播放器真正起来之间有一段**实在的等待**：
    // 要解析直链、探测 ISO、拼 intent、再拉起 Activity，冷启动时尤其明显。
    // 这段空窗期什么都不显示的话，用户会以为「点了没反应」而反复点。
    // ⇒ 置 true 后立刻把播放键换成转圈 + 禁用，Activity 一起来就由页面被覆盖自然结束。
    var playLaunching by remember { mutableStateOf(false) }

    // ── 多版本媒体：当前选中的版本 ──
    // null = 还没选，等价于「服务端默认版本」（= MediaSources 的第一条）。
    // 按 itemId 记忆：同一条目在页面存活期间保留选择，换条目则重置。
    var selectedMediaSourceId by remember(itemId) { mutableStateOf<String?>(null) }

    // ── 多版本媒体：当前版本自己的续播进度（本地）──
    //
    // Emby 服务端的 `UserData.PlaybackPositionTicks` 是**按条目**存的 —— 4K / 1080P /
    // 导演剪辑版共用一个续播点，服务端层面无解。所以多版本条目按版本各记各的本地进度
    // （播放页退出时按版本写入，键见 [EmbyVersionProgress]），这里读出来覆盖服务端进度。
    //
    // null = 不覆盖（单版本条目 / 本地还没按版本记过进度 ⇒ 沿用服务端进度）。
    var versionResumeSeconds by remember(itemId) { mutableStateOf<Long?>(null) }
    val playbackStateRepository = koinInject<PlaybackStateRepository>()
    // 「多版本优先选择」规则：用户在播放入口（含外部播放器、含按版本存续播进度）
    // 没手动选版本时据此自动挑。用 .get() 而不是 collectAsState 是**刻意的**：
    // 这是一次性取值（点播放的那一刻决定用哪个版本），不需要在改设置时让整页重组。
    // ⚠️ 位置很要紧：下面的 LaunchedEffect 与后面的播放/媒体信息都要用它，
    // Kotlin 属性按源码顺序初始化（红线 17），定义晚了这里会拿到 null。
    val browserPrefsForSource = koinInject<BrowserPreferences>()
    val autoSourcePreference =
      remember(browserPrefsForSource.embyMediaSourcePreference.get()) {
        MediaSourcePreference.fromKey(browserPrefsForSource.embyMediaSourcePreference.get())
      }
    LaunchedEffect(itemId, selectedMediaSourceId, item, server?.id) {
      val current = item
      val currentServer = server
      versionResumeSeconds =
        if (current == null || currentServer == null) {
          null
        } else {
          loadVersionResumeSeconds(
            repository = playbackStateRepository,
            serverId = currentServer.id,
            item = current,
            selectedMediaSourceId = selectedMediaSourceId,
            preference = autoSourcePreference,
          )
        }
    }

    // ── 「标记为不喜欢」──
    // 状态直接读仓库的内存快照（O(1)、零 IO），并订阅全量流 ⇒
    // 在播放器里标了 / 取消了，回到本页菜单项的文案与图标自动跟着变，
    // 不需要任何"通知详情页刷新"的代码。
    val dislikeRepository = koinInject<DislikeRepository>()
    val browserPreferencesForDislike = koinInject<BrowserPreferences>()
    // 提前取出图片 URL 生成器：koinInject 只能在组合函数体里调，
    // 而真正用它的地方在 onToggleDislike 回调（此时已出组合作用域）。
    val embyRepositoryForDislike = koinInject<EmbyRepository>()
    // 用 currentServer（StateFlow）而不是 currentServerOrAwait()（suspend）：
    // 这里是组合函数体，不能调挂起函数；而且服务器恢复本来是异步的，
    // 订阅 StateFlow 天然覆盖「先 null 后有值」这一路。
    val dislikeServer by viewModel.currentServer.collectAsState()
    val dislikeKey = remember(itemId, dislikeServer?.id) {
      dislikeServer?.id?.let { DislikeKey(it, itemId) }
    }
    val dislikeMarks by dislikeRepository.all.collectAsState()
    val isDisliked = dislikeKey != null &&
      dislikeMarks.any { it.key == dislikeKey && it.active }
    val showDislikeInMenu by browserPreferencesForDislike.dislikeShowInDetailMenu
      .collectAsState()

    suspend fun load() {
      // 冷启动时当前服务器可能还没恢复，这里等一下，避免静默不加载
      val current = viewModel.currentServerOrAwait() ?: return
      isLoading = true
      runCatching { viewModel.loadItemDetail(current, itemId) }
        .onSuccess { item = it }
        .onFailure { error = it.message ?: "加载详情失败" }
      isLoading = false
    }

    LaunchedEffect(itemId) { load() }

    // 从播放器返回时刷新详情：播放器的进度/已看上报是异步落到服务器的，而本页组合
    // 在播放期间并未销毁（播放器是另一个 Activity），返回时 LaunchedEffect 不会重跑，
    // 不刷新的话进度条就停在进来之前的旧值。延迟 800ms 等上报先落库再拉。
    val detailLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(detailLifecycleOwner, itemId) {
      val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && item != null) {
          scope.launch {
            kotlinx.coroutines.delay(800)
            load()
          }
        }
      }
      detailLifecycleOwner.lifecycle.addObserver(observer)
      onDispose { detailLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 折叠头部的状态（提到这里是因为「下拉刷新」要用到它）──
    //
    // ⭐ 提到 EmbyDetailScreen 这一层、而不是留在 DetailBody 里，是因为下拉刷新挂在
    // DetailBody **外面**（PullRefreshBox 包着它），而「封面收着的时候禁止下拉刷新」
    // 这个判断需要读到折叠进度。详见下面 PullRefreshBox 处的注释。
    val listState = rememberLazyListState()
    // 详情页推荐区开关：关闭后「推荐」与「同类型推荐」两块整块不渲染，也不发那几次请求。
    val recommendPreferences = koinInject<BrowserPreferences>()
    val showRecommendations by recommendPreferences.embyShowRecommendations.collectAsState()
    /** 「同类型推荐」每次进入是否随机挑一个类型（关掉则固定用第一个类型）。 */
    val randomGenreRecommend by recommendPreferences.embyRandomGenreRecommend.collectAsState()
    // 折叠状态：**只是列表滚动位置的函数**（没有任何自定义手势 / 动画状态）。
    val collapse = rememberHeaderCollapse(
      listState = listState,
      maxHeight = BACKDROP_HEADER_MAX_HEIGHT_DP,
      toolbarHeight = TOOLBAR_HEIGHT_DP,
    )

    // ── 推荐区数据：**提升到本层**（别挪回推荐区内部，见下方注释）──
    //
    // 背景：两个推荐区是 LazyColumn 里的 `item {}`。LazyColumn 会把滚出视口的 item
    // **销毁组合**、滚回来时**重新组合**；而请求原先挂在推荐区内部的 LaunchedEffect 上 ——
    // 键（item.Id / server）虽然没变，但**组合重建本身**就会让 LaunchedEffect 重跑。
    // 于是「从上滑到底再滑回来」会把两个推荐区各重新请求好几轮，用户看到的就是
    // 「滚动时推荐在重复刷新」。
    //
    // 提升到本层后，加载只由这两个键决定：
    //   · item.Id 变化        ⇒ 重新进（或切到另一部片的）详情页
    //   · recommendReloadKey  ⇒ 下拉刷新（见 PullRefreshBox 的 onRefresh）
    // 滚动不在键里，所以无论怎么滚都不会再触发。
    var recommendItems by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var genreRecommendLabel by remember(itemId) { mutableStateOf<String?>(null) }
    var genreRecommendItems by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var recommendReloadKey by remember(itemId) { mutableStateOf(0) }

    LaunchedEffect(itemId, item?.Id, server?.id, showRecommendations, recommendReloadKey) {
      if (!showRecommendations) return@LaunchedEffect
      val current = item ?: return@LaunchedEffect
      val currentServer = server ?: return@LaunchedEffect
      // 清空再取：换条目 / 下拉刷新时不该让用户先看到上一批推荐再被换掉
      recommendItems = emptyList()
      genreRecommendLabel = null
      genreRecommendItems = emptyList()
      // 两块推荐互不依赖，并发取 —— 别让「同类型」等「按人推荐」跑完才开始
      coroutineScope {
        launch {
          recommendItems =
            loadRecommendations(item = current, server = currentServer, viewModel = viewModel)
        }
        launch {
          val genres = current.Genres
          // 候选类型：随机开关打开 → 本片类型打乱后的顺序；关闭 → 只用第一个。
          // 打乱放在这里（effect 每次按上面的键重跑一次），于是「每次进页面 / 每次下拉刷新
          // 都换一批类型」，而滚动期间不会变。
          val candidates = when {
            genres.isEmpty() -> emptyList()
            randomGenreRecommend -> genres.shuffled()
            else -> listOf(genres.first())
          }
          // 逐个试：随机挑到的冷门类型可能查不到同类，命中第一个有结果的就停
          for (g in candidates) {
            val found = loadGenreRecommendations(currentServer, g, current.Id)
            if (found.isNotEmpty()) {
              genreRecommendLabel = g
              genreRecommendItems = found
              break
            }
          }
        }
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        isLoading && item == null -> EmbySkeletonDetail()

        error != null && item == null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          isError = true,
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val current = item ?: return@Box
          val currentServer = server ?: return@Box
          // 多版本媒体：真正要播放的版本 Id。只有存在多个版本时才非空 ——
          // 单版本媒体一律 null，播放链路与改动前完全一致。
          // 用户没在下拉框里选过时，按设置里的「多版本优先选择」规则自动挑。
          val playSourceId =
            effectiveEmbyMediaSourceId(current, selectedMediaSourceId, autoSourcePreference)
          // 版本显示名（与下拉框里显示的**一模一样**）——播放页顶栏据此显示「正在播的是哪个版本」。
          // 单版本条目为 null：那时片名后面不该多出一截。
          // ⚠️ 这个名字必须由 UI 传下去，不能让播放页自己重算：两边算出不同标签就会出现
          //   「下拉里写着 1080P、播放页顶栏写着别的」。
          val playSourceLabel = playSourceId
            ?.let { id -> current.MediaSources.orEmpty().firstOrNull { it.Id == id } }
            ?.let { embyMediaSourceLabel(it) }
          // 该条目当前的下载任务（没有就是 null）：按钮的文字、进度、图标都由它决定
          val currentDownload = current.Id?.let { id ->
            downloadTasks.firstOrNull { it.itemId == id }
          }

          // ── 下拉刷新 ──
          // 详情页的数据（进度、已看状态、演员、推荐）都是进页面那一次拉回来的，
          // 从播放器返回时虽然有 800ms 延迟的自动刷新，但用户手动拉一下仍然是最直接的重取方式。
          //
          // 新版折叠头部已经**不再消费任何手势**（封面高度只是列表滚动位置的函数），
          // 所以这里只要老老实实把 listState 传进去 + 加一道「封面已完全展开」的闸门，
          // 两者就不会再抢手势：
          //  - `listState` ⇒ PullRefreshBox 只在列表贴顶时才接管下拉；
          //  - `enabled = collapse.isExpanded` ⇒ 封面还没完全放回来时，下拉继续归列表
          //    （那段下拉本来就是「把封面滚回来」用的）。
          PullRefreshBox(
            isRefreshing = isRefreshing,
            // 下拉刷新：详情 + 两块推荐一起重取（推荐靠 recommendReloadKey 触发上面那个
            // LaunchedEffect；不加这一句的话下拉只会刷新详情，推荐原地不动）。
            onRefresh = {
              load()
              recommendReloadKey++
            },
            enabled = collapse.isExpanded,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
          ) {
            DetailBody(
              item = current,
              server = currentServer,
              listState = listState,
              collapse = collapse,
              showRecommendations = showRecommendations,
              // 剧照当封面区的整张背景图用，所以要按屏幕级别的宽度取（1080 足够铺满常见机型）：
              // 之前取 128px 是为了「放大即虚化」的糊底效果，用户不喜欢 —— 现在直接看清晰大图。
              // EmbyImageLoader 的 maxWidth 只做客户端降采样、不会改写 URL，
              // 所以尺寸必须从源头（这个 URL）给定。
              backdropUrl = viewModel.imageUrl(currentServer, current, "Backdrop", 1080),
              posterUrl = viewModel.imageUrl(currentServer, current, "Primary", 600),
              onPlay = { resume, reverse ->
                // 长按切内核时给个明确反馈，否则用户不知道这一下到底换了什么
                viewModel.resolveEngineOverride(reverse)?.let {
                  Toast.makeText(context, "使用 ${it.label} 内核播放", Toast.LENGTH_SHORT).show()
                }
                // 已经点过一次就别再放一次（连点会起两个播放页）
                if (!playLaunching) {
                  playLaunching = true
                  scope.launch {
                    try {
                      viewModel.play(
                        currentServer,
                        current,
                        resume,
                        reverse,
                        playSourceId,
                        mediaSourceLabel = playSourceLabel,
                      )
                    } finally {
                      // ⚠️ 只在**没成功拉起播放器**时复位（比如解析失败 / 抛异常），
                      //  成功拉起时本页已经不显示了，复位也会随重组被丢掉；
                      //  但若这里无条件复位，慢路径下会让转圈提前消失、用户又能连点。
                      playLaunching = false
                    }
                  }
                }
              },
              playLaunching = playLaunching,
              selectedMediaSourceId = selectedMediaSourceId,
              onSelectMediaSource = { selectedMediaSourceId = it },
              versionResumeSeconds = versionResumeSeconds,
              onToggleFavorite = {
                if (!favoriteBusy) {
                  val wasFavorite = current.UserData?.IsFavorite == true
                  // 目标状态在**这里**定死，并显式传给 ViewModel：
                  // 由 ViewModel 从传进去的 item 反推方向的话，item 是旧快照时
                  // 方向就会跟用户看到的相反（红心只能点亮、取消不了）。
                  val target = !wasFavorite
                  favoriteBusy = true
                  // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
                  item = current.copy(
                    UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = target),
                  )
                  scope.launch {
                    val result = viewModel.toggleFavorite(currentServer, current, target)
                    val nowFavorite = result.getOrNull()
                    item = current.copy(
                      UserData = (current.UserData ?: EmbyUserData())
                        .copy(IsFavorite = nowFavorite ?: wasFavorite),
                    )
                    favoriteBusy = false
                    // 同步媒体库的进程内缓存：从详情页返回列表时命中缓存、不重新请求，
                    // 缓存不同步的话列表卡片上的红心会停在旧状态
                    current.Id?.let { id ->
                      EmbyLibraryCache.updateFavorite(id, nowFavorite ?: wasFavorite)
                    }
                    // 红心有动效，但「到底收没收藏成功」得给个字，服务端失败时才不会误以为成了
                    Toast.makeText(
                      context,
                      when (nowFavorite) {
                        true -> "已加入收藏"
                        false -> "已取消收藏"
                        null -> "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                      },
                      Toast.LENGTH_SHORT,
                    ).show()
                  }
                }
              },
              onTogglePlayed = { played ->
                val playedItemId = current.Id ?: return@DetailBody
                // 乐观更新：先把勾翻到预期状态，失败再回滚
                item = current.copy(
                  UserData = (current.UserData ?: EmbyUserData()).copy(
                    Played = played,
                    PlaybackPositionTicks = if (played) {
                      current.UserData?.PlaybackPositionTicks ?: 0
                    } else {
                      0
                    },
                  ),
                )
                scope.launch {
                  val result = viewModel.setPlayed(currentServer, playedItemId, played)
                  if (result.isFailure) item = current
                  // 勾的填充色会变，但点完到底成没成要有字说得清楚
                  Toast.makeText(
                    context,
                    when {
                      result.isSuccess && played -> "已标记为已播放"
                      result.isSuccess -> "已标记为未播放"
                      else -> "标记失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    },
                    Toast.LENGTH_SHORT,
                  ).show()
                }
              },
              onBack = { backStack.removeLastOrNull() },
              onDelete = { showDeleteConfirm = true },
              onDownload = {
                // 已有任务时，这个按钮变成「暂停 / 继续」开关（与下载管理页同一套动作）：
                //   下载中 / 排队中 → 暂停；已暂停 / 失败 → 继续；已完成 → 只提示。
                // 没有任务才走去重入队，行为与以前一致。
                val downloadItemId = current.Id
                val existing = downloadItemId?.let { id ->
                  downloadTasks.firstOrNull { it.itemId == id }
                }
                when (existing?.status) {
                  EmbyDownloadStatus.RUNNING, EmbyDownloadStatus.QUEUED -> {
                    downloadViewModel.pause(existing.itemId)
                    Toast.makeText(
                      context,
                      if (existing.status == EmbyDownloadStatus.QUEUED) "已暂停下载（已移出队列）" else "已暂停下载",
                      Toast.LENGTH_SHORT,
                    ).show()
                  }

                  EmbyDownloadStatus.PAUSED, EmbyDownloadStatus.FAILED -> {
                    downloadViewModel.resume(existing.itemId)
                    Toast.makeText(context, "已继续下载", Toast.LENGTH_SHORT).show()
                  }

                  EmbyDownloadStatus.COMPLETED ->
                    Toast.makeText(context, "该媒体已经下载过了", Toast.LENGTH_SHORT).show()

                  null ->
                    when (downloadViewModel.enqueue(currentServer, current)) {
                      EmbyEnqueueResult.STARTED ->
                        Toast.makeText(context, "已加入下载队列", Toast.LENGTH_SHORT).show()

                      EmbyEnqueueResult.EXISTS ->
                        Toast.makeText(context, "该媒体已在下载列表中", Toast.LENGTH_SHORT).show()

                      EmbyEnqueueResult.COMPLETED ->
                        Toast.makeText(context, "该媒体已经下载过了", Toast.LENGTH_SHORT).show()

                      EmbyEnqueueResult.INVALID ->
                        Toast.makeText(context, "该媒体不支持下载", Toast.LENGTH_SHORT).show()
                    }
                }
              },
              downloadLabel = currentDownload?.let { task ->
                when (task.status) {
                  EmbyDownloadStatus.COMPLETED -> "已下载"
                  EmbyDownloadStatus.PAUSED -> "已暂停"
                  EmbyDownloadStatus.FAILED -> "下载失败"
                  EmbyDownloadStatus.QUEUED -> "排队中"
                  EmbyDownloadStatus.RUNNING ->
                    "下载中 ${((task.progressFraction ?: 0f) * 100).toInt()}%"
                }
              },
              downloadStatus = currentDownload?.status,
              // 下载按钮的自下而上填充进度：下载中/暂停用真实进度，已完成填满，
              // 排队给一个 0 值（按钮内部会做呼吸式待机动画），没任务传 null 不画
              downloadProgress = currentDownload?.let { task ->
                when (task.status) {
                  EmbyDownloadStatus.RUNNING -> task.progressFraction ?: 0f
                  EmbyDownloadStatus.PAUSED -> task.progressFraction ?: 0f
                  EmbyDownloadStatus.COMPLETED -> 1f
                  EmbyDownloadStatus.QUEUED -> 0f
                  EmbyDownloadStatus.FAILED -> 0f
                }
              },
              downloadFailed = currentDownload?.status == EmbyDownloadStatus.FAILED,
              onCast = {
                val id = current.Id
                if (id != null) {
                  val url = viewModel.getStreamUrl(currentServer, id, true, playSourceId)
                  dlnaManager.pendingPayload =
                    CastPayload(Uri.parse(url), viewModel.displayTitle(current))
                  castSheetShown = true
                }
              },
              // 外部播放器：先枚举本机能吃这条流的播放器，再用我们自己的列表让用户挑
              // （为什么不用系统选择器，见 ExternalPlayerPickerDialog 的注释）。
              // 一个都没枚举到时把原因说出来，否则点了没反应会让人以为是按钮坏了
              onPlayExternal = {
                viewModel.listExternalPlayers(currentServer, current, playSourceId)
                  .onSuccess { list ->
                    externalLastKey = viewModel.lastExternalPlayerKey()
                    externalPlayers = list
                  }
                  .onFailure { e ->
                    Toast.makeText(
                      context,
                      e.message ?: "打开外部播放器失败",
                      Toast.LENGTH_LONG,
                    ).show()
                  }
              },
              // 服务器转码：要先向服务器问一次播放方案（有网络往返），
              // 所以立刻回一个提示，免得点完一两秒里毫无动静、让人以为没反应
              onPlayTranscoded = {
                // 与播放键同一套续播口径：多版本条目优先用「该版本自己的」本地进度
                val resumeSeconds = versionResumeSeconds
                  ?: EmbyTicks.ticksToSeconds(current.UserData?.PlaybackPositionTicks ?: 0L)
                Toast.makeText(context, "正在请求服务器转码…", Toast.LENGTH_SHORT).show()
                scope.launch {
                  viewModel.playTranscoded(currentServer, current, resumeSeconds, mediaSourceId = playSourceId)
                    .onFailure { e ->
                      Toast.makeText(
                        context,
                        e.message ?: "服务器转码失败",
                        Toast.LENGTH_LONG,
                      ).show()
                    }
                }
              },
              // 以前这两项是「点了直接对服务器发一把 full 刷新」，现在换成
              // 媒体库长按菜单同款的抽屉：刮削=可检索的识别（EmbyIdentifyDialog），
              // 刷新=带选项的确认（EmbyRefreshMetadataDialog），见文件底部挂载处
              onRefreshMetadata = { showRefreshSheet = true },
              onScrapeMetadata = { showIdentifySheet = true },
              onEditMetadata = { showEditMeta = true },
              onPersonClick = { pid, pname, tag ->
                backStack.add(
                  EmbyPersonScreen(personId = pid, personName = pname, personImageTag = tag),
                )
              },
              // 点类型 / 标签 / 工作室 chip：进「按该维度找片」的结果页
              onMetaClick = { keyword, kind, filterId ->
                backStack.add(
                  EmbyTagItemsScreen(keyword = keyword, kind = kind, filterId = filterId),
                )
              },
              moreMenuExpanded = showMoreMenu,
              onMoreMenuChange = { showMoreMenu = it },
              isDisliked = isDisliked,
              showDislikeInMenu = showDislikeInMenu && dislikeKey != null,
              onToggleDislike = {
                val key = dislikeKey
                if (key != null) {
                  val target = item
                  if (isDisliked) {
                    dislikeRepository.unmark(key)
                    Toast.makeText(context, "已取消标记", Toast.LENGTH_SHORT).show()
                  } else {
                    dislikeRepository.mark(
                      key = key,
                      title = target?.Name.orEmpty(),
                      reason = null,
                      // ImageTags 是 Map（见 EmbyModels），不是对象 ——
                      // 写 .Primary 会被解析成别的成员，报 "Unresolved reference 'Primary'"。
                      imageUrl = target?.ImageTags?.get("Primary")?.let { tag ->
                        dislikeServer?.let { s -> embyRepositoryForDislike.imageUrl(s, itemId, tag = tag) }
                      },
                    )
                    Toast.makeText(context, "已标记为不喜欢", Toast.LENGTH_SHORT).show()
                  }
                }
              },
              // 推荐（按演员 / 导演）与同类型推荐的数据：由本层加载好后传进去，
              // 详情区内只负责画 —— 避免滚动时 item 被回收 / 重组而反复发请求。
              recommendItems = recommendItems,
              genreRecommendLabel = genreRecommendLabel,
              genreRecommendItems = genreRecommendItems,
              // 点推荐卡片：同一位演员 / 导演的另一部片子，直接再开一层详情页
              onRecommendClick = { id, name ->
                backStack.add(EmbyDetailScreen(itemId = id, title = name))
              },
            )
          }
        }
      }
    }

    if (castSheetShown) {
      DlnaSheet(onDismissRequest = { castSheetShown = false })
    }

    // ── 外部播放器选择弹窗：把本机能吃这条流的播放器全列出来（已剔除自家两个播放页）──
    externalPlayers?.let { list ->
      val playerTarget = item
      val serverTarget = server
      ExternalPlayerPickerDialog(
        players = list,
        lastUsedKey = externalLastKey.takeIf { it.isNotEmpty() },
        onPick = { player ->
          externalPlayers = null
          if (playerTarget != null && serverTarget != null) {
            viewModel.playWithExternalPlayer(
              serverTarget,
              playerTarget,
              player,
              effectiveEmbyMediaSourceId(
                playerTarget,
                selectedMediaSourceId,
                autoSourcePreference,
              ),
            )
              .onFailure { e ->
                Toast.makeText(context, e.message ?: "打开外部播放器失败", Toast.LENGTH_LONG).show()
              }
          }
        },
        onSystemChooser = {
          externalPlayers = null
          if (playerTarget != null && serverTarget != null) {
            viewModel.playWithExternalPlayerChooser(
              serverTarget,
              playerTarget,
              effectiveEmbyMediaSourceId(
                playerTarget,
                selectedMediaSourceId,
                autoSourcePreference,
              ),
            )
              .onFailure { e ->
                Toast.makeText(context, e.message ?: "打开外部播放器失败", Toast.LENGTH_LONG).show()
              }
          }
        },
        onDismiss = { externalPlayers = null },
      )
    }

    // ── 刮削元数据（= Emby 的「识别」）：与媒体库长按菜单同一个组件 ──
    // ⚠️ 这两个弹窗此前**只声明了开关、忘了挂载**，所以「更多」里点它们毫无反应。
    val identifyItem = item
    val identifyServer = server
    if (showIdentifySheet && identifyItem != null && identifyServer != null) {
      EmbyIdentifyDialog(
        server = identifyServer,
        item = identifyItem,
        viewModel = viewModel,
        onDismissRequest = { showIdentifySheet = false },
        // 应用成功后片名 / 简介 / 图片都可能被换掉，重拉一次详情
        onApplied = { scope.launch { load() } },
      )
    }

    // ── 刷新元数据：与媒体库长按菜单同一个二级选项框（可选强制覆盖）──
    if (showRefreshSheet && item != null) {
      val refreshName = item?.Name ?: ""
      EmbyRefreshMetadataDialog(
        name = refreshName,
        kindLabel = "条目",
        onDismissRequest = { showRefreshSheet = false },
        onConfirm = { replaceMetadata, replaceImages ->
          showRefreshSheet = false
          runEmbyLibraryAction(
            context = context,
            // 抽屉一点完就关：这类「发出去就该跑完」的动作挂 ViewModel 作用域，
            // 免得退出页面把请求连坐取消（与 EmbyIdentifyDialog 同一口径）
            scope = viewModel.viewModelScope,
            server = server,
            itemId = itemId,
            name = refreshName,
            okMessage = "已通知服务器刷新「$refreshName」的元数据，稍后下拉刷新查看",
            action = { s, id ->
              runCatching { viewModel.refreshLibraryMetadata(s, id, replaceMetadata, replaceImages) }
            },
          )
        },
      )
    }

    // ── 编辑元数据弹窗：名称 / 原名 / 排序名 / 简介 / 年份 / 首映日期 /
    //    类型 / 标签 / 制作地区 / 工作室 / 评分 / 分级 / 演员与导演，整体 PUT 回服务器 ──
    if (showEditMeta && item != null) {
      val targetItem = item
      val targetServer = server
      var nameState by remember { mutableStateOf(targetItem?.Name ?: "") }
      var originalTitleState by remember { mutableStateOf(targetItem?.OriginalTitle ?: "") }
      var sortNameState by remember { mutableStateOf(targetItem?.SortName ?: "") }
      var overviewState by remember { mutableStateOf(targetItem?.Overview ?: "") }
      var yearState by remember { mutableStateOf(targetItem?.ProductionYear?.toString() ?: "") }
      var premiereState by remember { mutableStateOf(targetItem?.PremiereDate?.take(10) ?: "") }
      var genresState by remember { mutableStateOf(targetItem?.Genres?.joinToString("、") ?: "") }
      var tagsState by remember { mutableStateOf(targetItem?.Tags?.joinToString("、") ?: "") }
      var locationsState by remember {
        mutableStateOf(targetItem?.ProductionLocations?.joinToString("、") ?: "")
      }
      var studiosState by remember {
        mutableStateOf(targetItem?.Studios?.mapNotNull { it.Name }?.joinToString("、") ?: "")
      }
      var ratingState by remember { mutableStateOf(targetItem?.CommunityRating?.toString() ?: "") }
      var officialState by remember { mutableStateOf(targetItem?.OfficialRating ?: "") }
      var peopleState by remember {
        mutableStateOf<List<EmbyPerson>>(targetItem?.People?.map { it.copy() } ?: emptyList())
      }

      AlertDialog(
        onDismissRequest = { showEditMeta = false },
        title = { Text("编辑元数据") },
        text = {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedTextField(
              value = nameState,
              onValueChange = { nameState = it },
              label = { Text("名称") },
              singleLine = true,
            )
            OutlinedTextField(
              value = originalTitleState,
              onValueChange = { originalTitleState = it },
              label = { Text("原名") },
              singleLine = true,
            )
            OutlinedTextField(
              value = sortNameState,
              onValueChange = { sortNameState = it },
              label = { Text("排序名") },
              singleLine = true,
            )
            OutlinedTextField(
              value = overviewState,
              onValueChange = { overviewState = it },
              label = { Text("简介") },
              minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                value = yearState,
                onValueChange = { yearState = it },
                label = { Text("年份") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
              )
              OutlinedTextField(
                value = premiereState,
                onValueChange = { premiereState = it },
                label = { Text("首映日期") },
                placeholder = { Text("2020-05-01") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }
            OutlinedTextField(
              value = genresState,
              onValueChange = { genresState = it },
              label = { Text("类型（、或逗号分隔）") },
              singleLine = true,
            )
            OutlinedTextField(
              value = tagsState,
              onValueChange = { tagsState = it },
              label = { Text("标签（、或逗号分隔）") },
              singleLine = true,
            )
            OutlinedTextField(
              value = locationsState,
              onValueChange = { locationsState = it },
              label = { Text("制作地区（、或逗号分隔）") },
              singleLine = true,
            )
            OutlinedTextField(
              value = studiosState,
              onValueChange = { studiosState = it },
              label = { Text("工作室（、或逗号分隔）") },
              singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                value = ratingState,
                onValueChange = { ratingState = it },
                label = { Text("评分") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
              )
              OutlinedTextField(
                value = officialState,
                onValueChange = { officialState = it },
                label = { Text("分级") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }

            // ── 演员与导演：可改姓名 / 角色，可删除 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("演员与导演", style = MaterialTheme.typography.titleSmall)
            }
            peopleState.forEachIndexed { index, person ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = person.Name ?: "",
                  onValueChange = { v ->
                    peopleState = peopleState.toMutableList().also {
                      it[index] = it[index].copy(Name = v)
                    }
                  },
                  label = { Text("姓名") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedTextField(
                  value = person.Role ?: "",
                  onValueChange = { v ->
                    peopleState = peopleState.toMutableList().also {
                      it[index] = it[index].copy(Role = v.ifBlank { null })
                    }
                  },
                  label = { Text("角色") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                  peopleState = peopleState.toMutableList().also { it.removeAt(index) }
                }) {
                  Icon(Icons.Default.Delete, contentDescription = "删除该人员")
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = {
            if (targetItem != null && targetServer != null) {
              scope.launch {
                val ok = viewModel.updateItemMetadata(
                  targetServer,
                  targetItem.copy(
                    Name = nameState.trim().ifBlank { null },
                    OriginalTitle = originalTitleState.trim().ifBlank { null },
                    SortName = sortNameState.trim().ifBlank { null },
                    Overview = overviewState.ifBlank { null },
                    ProductionYear = yearState.trim().toIntOrNull(),
                    PremiereDate = premiereState.trim().ifBlank { null },
                    Genres = parseMetaList(genresState),
                    Tags = parseMetaList(tagsState),
                    ProductionLocations = parseMetaList(locationsState),
                    Studios = parseMetaList(studiosState)
                      .map { EmbyStudio(Name = it) }
                      .takeIf { it.isNotEmpty() },
                    CommunityRating = ratingState.trim().toDoubleOrNull(),
                    OfficialRating = officialState.trim().ifBlank { null },
                    People = peopleState.takeIf { it.isNotEmpty() },
                  ),
                )
                Toast.makeText(
                  context,
                  if (ok) "已保存元数据" else "保存失败",
                  Toast.LENGTH_SHORT,
                ).show()
              }
            }
            showEditMeta = false
          }) { Text("保存") }
        },
        dismissButton = {
          TextButton(onClick = { showEditMeta = false }) { Text("取消") }
        },
      )
    }

    if (showDeleteConfirm && item != null) {
      ConfirmDialog(
        title = "删除媒体",
        subtitle = "确定要从 Emby 服务器删除「${item?.Name}」吗？\n" +
          "这会同时删除服务器上的物理文件，且无法撤销。",
        onConfirm = {
          showDeleteConfirm = false
          val current = server
          if (current != null) {
            viewModel.deleteItem(current, itemId)
            backStack.removeLastOrNull()
          }
        },
        onCancel = { showDeleteConfirm = false },
      )
    }

    // ── 「正在打开播放器」整页加载层 ──────────────────────────────
    // 详情页点播放 → 播放器真正起来之间有段网络 Prep（解析直链 / ISO 探测 /
    // 建连播列表，慢的时候要好几秒）。原来只有播放按钮里一枚 20dp 小转圈，
    // 用户视线不在按钮上就等于「点了没反应」。
    //
    // ⇒ 整页居中渲染大加载动画（走统一入口，「外观 → 加载动画」在这里也生效）；
    //   按钮内的小转圈随之撤掉（见 PlayButton），免得两处同时转、互相抢注意力。
    //
    // ⚠️ 挂在**内容 Box 之后**（同一个 Content() 体层级）：后声明的子节点画在
    //   上层，才盖得住页面内容。这层的对话框（castSheet / externalPlayers /
    //   identify / refresh / editMeta / deleteConfirm）本来就是挂在这层的 ——
    //   Dialog 是独立窗口，声明位置只影响组合顺序、不影响层级。
    // ⚠️ 这里**拿不到 BoxScope**（不在内容 Box 的 lambda 里），用不了
    //   `Modifier.align` ⇒ 自己套一个 `Box(contentAlignment = Center)`。
    // ⚠️ 尺寸必须写进 modifier —— AppLoadingIndicator 的 `size` 参数只在
    //   **完全没传 modifier** 时生效（`if (modifier == Modifier)`），传了
    //   modifier 就得自带 size，否则 Lottie 分支会按 composition 固有像素渲染
    //   （yamby 那批是 500×500px ≈ 170dp，直接变成巨物）。
    // ⚠️ `playLaunching` 的复位在 onPlay 的 finally 里：解析失败才会留下来
    //   （供用户重点一次），成功拉起时本页随即被播放页盖住。
    if (playLaunching) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        // ⚠️ 用 size 参数（基准 96dp，还会乘用户设的缩放系数）而不是
        //   Modifier.size —— 后者不走「加载动画大小」缩放。
        app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
          size = 96.dp,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBody(
  item: EmbyItem,
  server: EmbyServer?,
  /**
   * 列表状态 + 折叠状态。由 [EmbyDetailScreen] 持有并传进来 ——
   * 因为「下拉刷新」挂在 Body 外面，需要在那边读到折叠进度（见 `PullRefreshBox` 调用处）。
   */
  listState: androidx.compose.foundation.lazy.LazyListState,
  collapse: HeaderCollapse,
  /** 详情页推荐区开关。由 [EmbyDetailScreen] 读出后传进来。 */
  showRecommendations: Boolean,
  backdropUrl: String?,
  posterUrl: String?,
  /** resumeSeconds = 续播秒数（0 = 从头）；reverseEngine = 用与默认相反的内核（长按） */
  onPlay: (resumeSeconds: Long, reverseEngine: Boolean) -> Unit,
  onToggleFavorite: () -> Unit,
  onTogglePlayed: (played: Boolean) -> Unit,
  onBack: () -> Unit,
  onDelete: () -> Unit,
  onDownload: () -> Unit,
  onCast: () -> Unit,
  /** 交给系统里别的播放器打开（不进 App 自己的播放页） */
  onPlayExternal: () -> Unit,
  /**
   * 让**服务器转码**后播放。
   *
   * 默认播放是「直连原文件」，能不能播全看本机解不解得了这个编码。远程 strm 指向的
   * AVI、AVI + Xvid（MPEG-4 ASP）这类源，手机上硬解不出画面、软解又跟不上，
   * 换内核也救不了（两条路都用系统解码器）—— 这时让服务器转成 H.264 再下发就通了。
   */
  onPlayTranscoded: () -> Unit,
  onEditMetadata: () -> Unit,
  onScrapeMetadata: () -> Unit,
  onRefreshMetadata: () -> Unit,
  /** 点演职员头像：进「演员作品」页（personId / 名字 / 头像 tag） */
  onPersonClick: (personId: String, personName: String, imageTag: String?) -> Unit,
  /**
   * 点类型 / 标签 / 工作室 chip：进「按该维度找片」的结果页。
   * @param keyword 显示名（就是 chip 上的字）
   * @param kind "genre" / "tag" / "studio"
   * @param filterId 过滤用的 Id（工作室需要；类型 / 标签传空串，按名字过滤）
   */
  onMetaClick: (keyword: String, kind: String, filterId: String) -> Unit,
  /** 点简介 chip：跳演员作品页等 —— 这里只用到打开详情页 */
  downloadLabel: String?,
  /** 当前下载任务状态；null = 没有任务。用来决定按钮是「下载 / 暂停 / 继续 / 已完成」 */
  downloadStatus: EmbyDownloadStatus?,
  /** 下载填充进度 0..1；null = 没有下载任务（不画填充） */
  downloadProgress: Float?,
  downloadFailed: Boolean,
  moreMenuExpanded: Boolean,
  onMoreMenuChange: (Boolean) -> Unit,
  /** 当前条目是否已标记为「不喜欢」—— 决定菜单项文案与图标 */
  isDisliked: Boolean,
  /** 点击「标记为不喜欢 / 取消标记」 */
  onToggleDislike: () -> Unit,
  /** 设置页「详情页菜单项」开关。为 false 时整个菜单项不渲染。 */
  showDislikeInMenu: Boolean,
  /**
   * 两块推荐区的数据。**由 [EmbyDetailScreen] 加载好后传进来**，这里只负责画 ——
   * 请求留在区块内部的话，LazyColumn 回收 / 重组 item 时会被反复触发（见那边的注释）。
   */
  recommendItems: List<EmbyItem>,
  genreRecommendLabel: String?,
  genreRecommendItems: List<EmbyItem>,
  /** 是否正在拉起播放器：播放键转圈 + 禁点（转给 [PlaySection]）。 */
  playLaunching: Boolean = false,
  /** 多版本媒体：当前选中的版本 Id（null = 默认版本）。转给 [PlaySection] 画下拉框。 */
  selectedMediaSourceId: String? = null,
  /** 多版本媒体：用户在下拉框里选了某个版本。 */
  onSelectMediaSource: (String) -> Unit = {},
  /**
   * 多版本媒体：当前版本的**本地**续播秒数（null = 按服务端进度）。
   *
   * 服务端进度按条目存、各版本共享，所以多版本条目要按版本各算各的（见 [loadVersionResumeSeconds]）。
   * 转给 [PlaySection] 决定「继续播放 · hh:mm:ss / 已观看 x%」显示的是哪个进度。
   */
  versionResumeSeconds: Long? = null,
  /** 点推荐卡片：打开对应媒体的详情页（itemId / 标题） */
  onRecommendClick: (String, String) -> Unit,
) {
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true
  // 多版本媒体：真正生效的版本 Id（单版本媒体为 null）。下面「媒体信息」也要按它取源，
  // 否则容器 / 文件大小 / 码率 / 音视频轨道会一直显示第一个版本，与选中的版本对不上。
  // ⚠️ 必须与播放侧用**同一条规则**（autoSourcePreference 同一个值），
  // 否则会出现「顶栏写着一个版本、媒体信息里写着另一个」。
  val browserPrefsForSource = koinInject<BrowserPreferences>()
  val effectiveSourceId =
    effectiveEmbyMediaSourceId(
      item,
      selectedMediaSourceId,
      MediaSourcePreference.fromKey(browserPrefsForSource.embyMediaSourcePreference.get()),
    )

  // ── 折叠头部：高度**完全由列表滚动位置推导**，没有任何自定义手势 / 嵌套滚动 ──
  //
  // 此前这里同时挂着两套纵向滚动的消费者（自定义 NestedScrollConnection + 下拉刷新），
  // 两者抢同一个手势，就是「卡手 / 时灵时不灵」的根源。现在整块删掉，只留一条规则：
  //     封面有多高 = 列表滚了多少（见 [rememberHeaderCollapse]）
  // 手势只有 LazyColumn 一个消费者；下拉刷新只在列表贴顶时接住溢出量 —— 结构上不可能再打架。
  //
  // ⭐ 因为播放区一直贴着封面底边一起上移，封面收完的那一刻播放区正好停在工具栏下方，
  //    所以天然就是「滑到播放按钮就折叠」，不需要任何触发距离常量。
  val density = LocalDensity.current
  val headerHeightDp = with(density) { collapse.headerHeightPx.toDp() }
  val headerMaxHeightPx = with(density) { BACKDROP_HEADER_MAX_HEIGHT_DP.toPx() }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 96.dp),
    ) {
      // 第 0 项是「封面占位稿」：高度 = 封面最大高度，本身不可见（被上层的封面盖住）。
      //
      // ⚠️ 用**真实 item** 而不是 `contentPadding.top`，是为了避开一个语义坑：
      // `firstVisibleItemIndex` / `firstVisibleItemScrollOffset` 与
      // `visibleItemsInfo` 的 offset 对 contentPadding 的处理并不一致（前者「不含内容内边距」），
      // 拿它推折叠量会在某些情况下差一个 padding 的高度。改用真实 item 后，
      // 它的 offset 就是「相对视口的真实 y」，从 0 一路减到 −最大高度，毫无歧义。
      item { Spacer(modifier = Modifier.height(BACKDROP_HEADER_MAX_HEIGHT_DP)) }

      // ── 播放 / 下载 / 投屏（删除在右上角「更多」菜单里）──
      item {
        PlaySection(
          item = item,
          onPlay = onPlay,
          onDownload = onDownload,
          onCast = onCast,
          onPlayExternal = onPlayExternal,
          downloadLabel = downloadLabel,
          downloadStatus = downloadStatus,
          downloadProgress = downloadProgress,
          downloadFailed = downloadFailed,
          playLaunching = playLaunching,
          selectedMediaSourceId = selectedMediaSourceId,
          onSelectMediaSource = onSelectMediaSource,
          versionResumeSeconds = versionResumeSeconds,
        )
      }

      // ── 类型 / 标签 / 工作室 / 外部链接 ──
      // 四组都是「点一下进按该维度找片的结果页」的 chip，共用 [MetaChipRow] 排版：
      // 左边一个分组名，右边横向滚动的一排 chip（分组名固定宽度 ⇒ 各组的 chip 起始边对齐）。
      //
      // 类型 / 标签按**名字**过滤（Emby 的 Genres / Tags 参数，实测可用）；
      // 工作室优先按 **Id** 过滤（`StudioIds`）—— 条目自带的 Studios 就是 NameGuidPair，
      // 拿得到 Id 就按 Id 走；万一只回了名字，结果页会自动退回按名字的 `Studios` 参数。
      val genreList = item.Genres.filter { it.isNotBlank() }
      // ⚠️ 标签**优先 TagItems、为空才退回 Tags**：`Tags` 是纯名字数组、`TagItems` 每项带 Id，
      //    但有些服务端（用户环境就是）只写 `TagItems` 而把 `Tags` 留空甚至整个字段不返回
      //    ⇒ 只看 `Tags` 会一条标签都显示不出来。两份是同一份数据的两种形态，取到即可。
      val tagList = item.TagItems.mapNotNull { it.Name?.takeIf { n -> n.isNotBlank() } }
        .ifEmpty { item.Tags.filter { it.isNotBlank() } }
      val studioList = item.Studios.orEmpty().filter { !it.Name.isNullOrBlank() }
      // 外部链接：**首选服务端给的 `ExternalUrls`**，它取不到才退回用 ProviderIds 现拼。
      //
      // 为什么首选它（v73 真机返工）：`ExternalUrls[].Url` 是**服务端 / 元数据插件已经
      // 拼好的完整地址**（用户环境里 MetaTube 给的是
      // `http://192.168.2.124:8080/?redirect=JavBus:ACZD-218`）—— 直接用，打开的就是
      // Emby Web 与 Yamby 打开的同一条链接。
      //
      // 退回分支（内置元数据源不产生 ExternalUrls）按 `<服务器根>/?redirect=<Provider>:<Id>`
      // 现拼，细节见 [externalLinkUrl]。
      //
      // ⚠️ 曾经**只**按 `"$baseUrl?redirect=$value"` 现拼，真机结果是「跳到了 Emby 首页」：
      //    少了根路径的 `/`，且 `redirect` 的参数是 `Provider:Id` 而不是裸 Id。
      val externalLinks = item.ExternalUrls
        .mapNotNull { e ->
          val name = e.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          val url = e.Url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          name to url
        }
        .ifEmpty {
          item.ProviderIds.filterValues { it.isNotBlank() }
            .mapNotNull { (name, value) -> externalLinkUrl(server?.baseUrl, name, value) }
        }
        // 同名只留第一条：chip 是按名字渲染的，重名会让两个 chip 点出不同地址
        .distinctBy { it.first }
        .toMap()
      if (
        genreList.isNotEmpty() || tagList.isNotEmpty() ||
        studioList.isNotEmpty() || externalLinks.isNotEmpty()
      ) {
        item {
          // Composable 才能取到 UriHandler；LazyListScope 的 content lambda 不是 composable，
          // 所以必须写在 item{} 里面。
          val uriHandler = LocalUriHandler.current
          Column(modifier = Modifier.padding(vertical = 4.dp)) {
            MetaChipRow(
              label = "类型",
              chips = genreList,
              onClick = { onMetaClick(it, "genre", "") },
            )
            MetaChipRow(
              label = "标签",
              chips = tagList,
              onClick = { onMetaClick(it, "tag", "") },
            )
            MetaChipRow(
              label = "工作室",
              chips = studioList.mapNotNull { it.Name },
              onClick = { name ->
                // chips 是按名字渲染的，回查 Id 交给 onMetaClick
                val id = studioList.firstOrNull { it.Name == name }?.Id.orEmpty()
                onMetaClick(name, "studio", id)
              },
            )
            // chip 上显示的是链接名（服务端 ExternalUrls 的 `Name`，如 MetaTube；退回分支里
            // 就是 ProviderIds 的键），点一下交给系统浏览器打开那条地址
            MetaChipRow(
              label = "外部链接",
              chips = externalLinks.keys.toList(),
              onClick = { label ->
                externalLinks[label]?.let { uriHandler.openUri(it) }
              },
            )
          }
        }
      }

      // ── 宣传语 / 导演 ──
      // 宣传语（Taglines）在用户环境里是一条短句（如「配信開始日 2025-04-11」），单独一行；
      // 紧跟其下一行显示导演 —— **用户指定的格式就是「导演：名字」**，名字取 People 里
      // `Type=Director` 的条目（与 Emby Web 的「导演」同源）。
      // 两者都不是筛选维度，所以是**纯文本**而不是 chip —— 做了 chip 却没反应就是「死项」。
      val taglines = item.Taglines.filter { it.isNotBlank() }
      val directors = item.People.orEmpty()
        .filter { it.Type.equals("Director", ignoreCase = true) }
        .mapNotNull { it.Name?.takeIf { name -> name.isNotBlank() } }
      if (taglines.isNotEmpty() || directors.isNotEmpty()) {
        item {
          Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            taglines.forEach { line ->
              Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.longPressToCopy(line, "宣传语"),
              )
            }
            if (directors.isNotEmpty()) {
              val names = directors.joinToString("、")
              Text(
                text = "导演：$names",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.longPressToCopy(names, "导演"),
              )
            }
          }
        }
      }

      // ── 简介 ──
      item.Overview?.takeIf { it.isNotBlank() }?.let { overview ->
        item { OverviewSection(overview) }
      }

      // ── 演职员 ──
      item.People?.takeIf { it.isNotEmpty() }?.let { people ->
        item {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("演职员", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              people.take(20).forEach { person ->
                Column(
                  modifier = Modifier.width(72.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Card(
                    modifier = Modifier
                      .size(56.dp)
                      .clip(RoundedCornerShape(28.dp))
                      .clickable {
                        val pid = person.Id
                        if (pid != null) {
                          onPersonClick(pid, person.Name ?: "", person.PrimaryImageTag)
                        }
                      },
                    shape = RoundedCornerShape(28.dp),
                  ) {
                    val avatarUrl = remember(person.Id, person.PrimaryImageTag, server) {
                      server?.let { s ->
                        person.Id?.let { id ->
                          EmbyClient.imageUrl(
                            server = s,
                            itemId = id,
                            imageType = "Primary",
                            tag = person.PrimaryImageTag,
                            maxWidth = 240,
                          )
                        }
                      }
                    }
                    EmbyImage(
                      url = avatarUrl,
                      contentDescription = person.Name,
                      modifier = Modifier.fillMaxSize(),
                      contentScale = ContentScale.Crop,
                      placeholder = Icons.Default.Person,
                      maxWidth = 240,
                    )
                  }
                  Text(
                    text = person.Name ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    // 这一栏是「演职员」，混着演员 / 导演 / 编剧，复制提示用中性的「姓名」
                    modifier = Modifier.longPressToCopy(person.Name, "姓名"),
                  )
                  person.Role?.let {
                    Text(
                      text = it,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // ── 推荐：同一位演员 / 导演参与的其他影片 ──
      // 放在演职员之后：看完了主演阵容，顺势往下推荐 TA 的片子，上下文是连着的。
      // 设置里关掉「详情页推荐」后整块不渲染（连带省掉上面的网络请求）。
      //
      // ⚠️ 这里只是**把上层加载好的数据画出来**：数据与请求都在 [EmbyDetailScreen]
      // 那一层（不随滚动被回收），所以滚出视口再滚回来不会重新请求。
      if (showRecommendations) {
        item {
          RecommendationSection(
            server = server,
            recommendations = recommendItems,
            onOpenItem = onRecommendClick,
          )
        }

        // ── 同类型推荐：按本片某个类型（Genres）横向推荐 ──
        // 与上面的「演员 / 导演」推荐互补：那边按人找，这边按题材找。
        item {
          GenreRecommendationSection(
            server = server,
            genre = genreRecommendLabel,
            picks = genreRecommendItems,
            onOpenItem = onRecommendClick,
          )
        }
      }

      // ── 媒体信息（含完整视频 / 音频编码信息）──
      item {
        MediaInfoSection(item = item, selectedMediaSourceId = effectiveSourceId)
      }
    }

    // ── 封面区（浮在下层内容之上，随滚动折叠）──
    // 它不放在 LazyColumn 里当 item，是因为要能做视差位移、且高度随滚动变化；
    // 对应 XML 里 AppBarLayout 套 ImageView(layout_collapseMode="parallax") + Toolbar(pin)。
    // 高度来自 [collapse]，而 [collapse] 只是列表滚动位置的函数 —— 这里只负责画。
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(headerHeightDp)
        .align(Alignment.TopCenter)
        .graphicsLayer {
          // 收到最后阶段整体淡出，避免和 toolBar 的文字挤在一起。
          // ⚠️ 用**线性**的 collapse.progress 而不是缓动后的值 —— 淡出是「退场」，
          // 缓动会让它拖到最后一刻才消失，那时标题已经压上来了。
          alpha = (1f - collapse.progress * 1.6f).coerceIn(0f, 1f)
        },
    ) {
      BackdropHeader(
        item = item,
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        // 视差位移按**缓动后**的刻度走，跟容器的收缩节奏一致
        parallaxPx = collapse.collapsedPx * easeInQuad(collapse.progress),
        // 底图按头部宽度反向缩放：容器变矮多少，图就放大多少，
        // 保证它在折叠过程中**始终维持 CenterCrop**，不会被压扁变形。
        // 高度只剩 56dp 时 widthScale ≈ 5.7，但那时整块 alpha 已经归零，不会看到过放大的画面。
        widthScale = if (collapse.headerHeightPx > 0f) {
          headerMaxHeightPx / collapse.headerHeightPx
        } else {
          1f
        },
      )
    }

    // ── 顶部浮层操作栏 ──
    // 展开时透明 + 白图标（压在封面上），折叠后过渡到实体的工具栏底色 + 常规前景色，
    // 并把标题淡入进来 —— 对应 XML 里 `layout_collapseMode="pin"` 那条 Toolbar。
    val barProgress = easeInQuad(collapse.progress)
    val barBg = lerp(
      Color.Transparent,
      MaterialTheme.colorScheme.surface,
      barProgress,
    )
    // 图标颜色跟着背景走：透明时是白色，实体背景时切回常规前景色
    val barContentColor = lerp(
      Color.White,
      MaterialTheme.colorScheme.onSurface,
      barProgress,
    )
    TopAppBar(
      title = {
        Text(
          text = item.Name ?: "",
          // 顶栏标题也支持长按复制：片名常有生僻字 / 外文，用户要拿去别处搜
          modifier = Modifier.longPressToCopy(item.Name, "片名"),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          // 折叠加深到一定程度才把标题放出来，避免展开时和封面区的大标题重复
          color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = ((barProgress - 0.5f) / 0.5f).coerceIn(0f, 1f),
          ),
        )
      },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = barContentColor,
          )
        }
      },
      actions = {
        IconButton(onClick = { onTogglePlayed(!isPlayed) }) {
          Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = if (isPlayed) "标记为未播放" else "标记为已播放",
            tint = barContentColor.copy(alpha = if (isPlayed) 1f else 0.75f),
          )
        }
        IconButton(onClick = onToggleFavorite) {
          // 与播放页共用同一套动效：弹跳 + 星光 + 红心渐变（状态驱动，故上面做了乐观更新）
          FavoriteHeartIcon(
            isFavorite = isFavorite,
            isToggling = false,
            iconSize = 24.dp,
            idleColor = barContentColor,
          )
        }
        Box {
          IconButton(onClick = { onMoreMenuChange(true) }) {
            Icon(
              Icons.Default.MoreVert,
              contentDescription = "更多",
              tint = barContentColor,
            )
          }
          DropdownMenu(
            expanded = moreMenuExpanded,
            onDismissRequest = { onMoreMenuChange(false) },
          ) {
            DropdownMenuItem(
              text = { Text(if (isPlayed) "标记为未播放" else "标记为已播放") },
              onClick = {
                onMoreMenuChange(false)
                onTogglePlayed(!isPlayed)
              },
            )
            // 「标记为不喜欢」：单击循环切换（已标记时文案变「取消标记为不喜欢」），
            // 不在这里弹备注框 —— 详情页不拦播放，且备注入口在播放器里长按即可。
            // ⚠️ **不要给它加 leadingIcon**：本菜单其余 6 项全是纯文字，
            //   只有这一项带图标会显得突兀、不像同一个菜单里的东西。
            //   选中/取消态由**文案本身**表达（「标记为」→「取消标记为」）。
            if (showDislikeInMenu) {
              DropdownMenuItem(
                text = {
                  Text(if (isDisliked) "取消标记为不喜欢" else "标记为不喜欢")
                },
                onClick = {
                  onMoreMenuChange(false)
                  onToggleDislike()
                },
              )
            }
            // 直连播不动时才需要它：本机解不了的老编码（AVI/Xvid、WMV…）、远程 strm 源。
            // 放菜单里而不是动作行 —— 它是「备用方案」，不该和播放键抢位置。
            DropdownMenuItem(
              text = { Text("服务器转码播放") },
              onClick = {
                onMoreMenuChange(false)
                onPlayTranscoded()
              },
            )
            DropdownMenuItem(
              text = { Text("删除媒体") },
              onClick = {
                onMoreMenuChange(false)
                onDelete()
              },
            )
            DropdownMenuItem(
              text = { Text("编辑元数据") },
              onClick = {
                onMoreMenuChange(false)
                onEditMetadata()
              },
            )
            DropdownMenuItem(
              text = { Text("刮削元数据") },
              onClick = {
                onMoreMenuChange(false)
                onScrapeMetadata()
              },
            )
            DropdownMenuItem(
              text = { Text("刷新元数据") },
              onClick = {
                onMoreMenuChange(false)
                onRefreshMetadata()
              },
            )
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(
        // 背景从全透明渐变到实体底色：还没折叠时能看见封面，折完了就是一条正常的工具栏
        containerColor = barBg,
        scrolledContainerColor = barBg,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
      ),
    )
  }
}

/**
 * 详情页头部：**一整张剧照（Backdrop）铺满**，标题压在图上。
 *
 * 上一版是「左侧 2:3 小封面 + 128px 剧照放大当虚化底」，用户反馈太糊、不喜欢 ——
 * 现在回到整版背景图：
 *
 * - **底图**：向服务器要屏幕级别的剧照（1080 宽，尺寸由调用方 [backdropUrl] 给定），
 *   Crop 铺满整个头部，**不做任何模糊、也不压半透明**，保持清晰。
 * - **文字**：贴着底部排（标题 / 原名 / 信息角标），靠下面那层渐变把亮度压下来保证可读，
 *   上方留白是纯图 —— 也就是常见的详情页大图观感。
 *
 * [parallaxPx] 是已折叠的像素数，底图跟着做半速位移 —— 对应 XML 里的
 * `app:layout_collapseMode="parallax"`。
 *
 * **注意：本函数的高度由调用方决定**（折叠时容器会变矮），所以这里一律 [Modifier.fillMaxSize]。
 */
/**
 * 把 [accent] 按 [weight] 混进 [base]。
 *
 * 用于「海报主色渐变」：直接拿封面主色铺背景容易和下面的主题底色打架，
 * 掺进主题色里既带上封面的调子，又保证深浅风格一致。
 */
private fun mixColor(base: Color, accent: Color, weight: Float): Color {
  val w = weight.coerceIn(0f, 1f)
  return Color(
    red = base.red * (1f - w) + accent.red * w,
    green = base.green * (1f - w) + accent.green * w,
    blue = base.blue * (1f - w) + accent.blue * w,
    alpha = base.alpha,
  )
}

@Composable
private fun BackdropHeader(
  item: EmbyItem,
  backdropUrl: String?,
  posterUrl: String?,
  parallaxPx: Float,
  /**
   * 底图的横向放大倍率。
   *
   * 容器高度随折叠变小，若底图仍铺满容器就会被**纵向压扁** —— 人物越来越矮胖，
   * 慢滚时一帧帧变形，这是「压迫感」的主要来源。
   * 按 `展开高度 / 当前高度` 反向放大横向，底图就始终维持原比例（CenterCrop），
   * 表现成「缓慢推近」而不是「被压扁」。
   */
  widthScale: Float = 1f,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      // 折叠过程中容器变矮，不裁剪的话标题会溢出到下方内容区上
      .clipToBounds(),
  ) {
    // ① 剧照整版铺底（只有这一层做视差位移）
    if (backdropUrl != null || posterUrl != null) {
      EmbyImage(
        url = backdropUrl,
        // 很多单集 / 音乐只有封面没有剧照，退回到封面顶上，免得整个头部是一片灰底
        fallbackUrl = posterUrl,
        contentDescription = null,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            translationY = parallaxPx * 0.5f
            // 横向放大「N 倍再缩回去」：pivot 取中上（0.5, 0）——
            // 竖屏裁切时标题本来就压在底部、人物多在中上部，锚在这里裁掉的最少。
            scaleX = widthScale
            transformOrigin = TransformOrigin(0.5f, 0f)
          },
        contentScale = ContentScale.Crop,
        // 0 = 不降采样：URL 已经跟服务器要了 1080，客户端再降一次只会白白变糊
        maxWidth = 0,
      )
    }

    // ② 暗化渐变：顶上有白图标、底部压着白字，无论剧照多亮都要看得清。
    //    最下面两层现在掺入**海报主色**（需求里的「海报主色渐变」）：
    //    整页往下过渡到封面自己的色调，比直接切成中性主题底色更有整体感。
    //    取不到主色（图挂了 / 图片太灰）时回退成原来的主题底色，观感不变差；
    //    混合时以主题底色为主 —— 否则头部一片暖色、下面列表还是冷色，衔接处会断成两截。
    val surface = MaterialTheme.colorScheme.surface
    val dominant = rememberDominantColor(backdropUrl ?: posterUrl)
    val bottomTint = dominant?.let { mixColor(surface, it, 0.45f) } ?: surface
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Black.copy(alpha = 0.45f),
              Color.Transparent,
              Color.Transparent,
              bottomTint.copy(alpha = 0.75f),
              bottomTint,
            ),
          ),
        ),
    )

    // ③ 标题区：顶部给 Toolbar 让位，文字贴着头部底边排
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(start = 16.dp, end = 16.dp, top = TOOLBAR_HEIGHT_DP, bottom = 16.dp),
      verticalArrangement = Arrangement.Bottom,
    ) {
      Text(
        text = item.Name ?: "",
        style = MaterialTheme.typography.headlineSmall.copy(
          shadow = Shadow(
            color = Color.Black.copy(alpha = 0.65f),
            blurRadius = 10f,
          ),
        ),
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      // 原名只在「实质不同」时才显示（去首尾空白、忽略大小写），并加「原名：」前缀，
      // 否则标题下面再跟一行差不多的文字，看起来就像标题显示了两遍
      item.OriginalTitle
        ?.takeIf {
          it.isNotBlank() && !it.trim().equals(item.Name?.trim(), ignoreCase = true)
        }
        ?.let {
          Text(
            text = "原名：$it",
            style = MaterialTheme.typography.bodyMedium.copy(
              shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
            ),
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.longPressToCopy(it, "原名"),
          )
        }
      // 媒体 ID：排障与「App 里看到的条目到底是不是服务器上那一条」对照用，长按复制。
      item.Id?.takeIf { it.isNotBlank() }?.let { id ->
        Text(
          text = "媒体 ID：$id",
          style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
          ),
          color = Color.White.copy(alpha = 0.6f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.longPressToCopy(id, "媒体 ID"),
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      // 年份 / 时长 / 评分 / 分级：包成圆角角标，压在剧照上也看得清。
      // 用 FlowRow 而不是 Row —— 片名长、角标多的时候会自动换行，不会把角标挤出屏幕边缘。
      val chips = metaChips(item)
      if (chips.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          chips.forEach { chip ->
            Surface(
              shape = RoundedCornerShape(50),
              color = Color.Black.copy(alpha = 0.42f),
              contentColor = Color.White,
            ) {
              Text(
                text = chip,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }
  }
}

/** 剧照上的角标内容：年份 / 时长 / 评分 / 官方分级，每项单独渲染成一个圆角角标 */
/** 把「、」/ 逗号 / 分号分隔的输入切成列表：去空白、去空项、保序去重。空输入返回空列表。 */
private fun parseMetaList(raw: String): List<String> =
  raw
    .split("、", ",", "，", ";", "；")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

/** 剧照上的一行摘要：年份 · 时长 · 评分 · 官方分级，渲染成圆角角标 */
private fun metaChips(item: EmbyItem): List<String> {
  val parts = mutableListOf<String>()
  item.ProductionYear?.let { parts.add(it.toString()) }
  formatDuration(item.RunTimeTicks).takeIf { it.isNotBlank() }?.let { parts.add(it) }
  item.CommunityRating?.let { parts.add("★ %.1f".format(it)) }
  item.OfficialRating?.let { parts.add(it) }
  return parts
}

/**
 * 播放区：主按钮「播放 / 继续播放 · mm:ss」（占满除动作键以外的宽度）
 * + 右侧下载、投屏两个紧凑动作键。
 *
 * 删除已收进右上角「更多」菜单（那里本来就有确认框），行内不再放第三个图标键 ——
 * 多出来的宽度全部给播放按钮。「从头播放」也只在「更多」菜单里出现（有进度时）。
 *
 * 进度说明**始终显示**：
 * `已观看 24% · 剩余 1小时12分 · 总时长 1小时35分`。
 */
@Composable
private fun PlaySection(
  item: EmbyItem,
  /** resumeSeconds = 续播秒数（0 = 从头）；reverseEngine = 用与默认相反的内核（长按） */
  onPlay: (resumeSeconds: Long, reverseEngine: Boolean) -> Unit,
  onDownload: () -> Unit,
  onCast: () -> Unit,
  onPlayExternal: () -> Unit,
  downloadLabel: String?,
  downloadStatus: EmbyDownloadStatus?,
  /** 下载填充进度 0..1；null = 没有下载任务（不画填充） */
  downloadProgress: Float?,
  downloadFailed: Boolean,
  /** 是否正在拉起播放器：播放键转圈 + 禁点。 */
  playLaunching: Boolean = false,
  /** 多版本媒体：当前选中的版本 Id（null = 默认版本）。 */
  selectedMediaSourceId: String? = null,
  /** 多版本媒体：用户切换了版本。 */
  onSelectMediaSource: (String) -> Unit = {},
  /**
   * 多版本媒体：当前版本的本地续播秒数（null = 用服务端进度）。
   *
   * Emby 的 `UserData.PlaybackPositionTicks` 按**条目**存、各版本共享，
   * 所以多版本条目必须让「当前版本自己的进度」盖过它 —— 否则切成 1080P 会从
   * 4K 看到的位置继续。null（单版本条目）时行为与改动前完全一致。
   */
  versionResumeSeconds: Long? = null,
) {
  val serverPositionTicks = item.UserData?.PlaybackPositionTicks ?: 0L
  val positionTicks =
    versionResumeSeconds?.let { EmbyTicks.secondsToTicks(it) } ?: serverPositionTicks
  val runTimeTicks = item.RunTimeTicks ?: 0L
  // 多版本媒体：MediaSources 就是各版本；只有一个（或 Id 缺失）时不算多版本。
  val mediaSources = item.MediaSources.orEmpty().filter { !it.Id.isNullOrBlank() }
  val resumeSeconds = EmbyTicks.ticksToSeconds(positionTicks)
  val remainingTicks = (runTimeTicks - positionTicks).coerceAtLeast(0L)
  val hasResume = resumeSeconds > 10 && runTimeTicks > 0 &&
    (positionTicks.toFloat() / runTimeTicks.toFloat()) < 0.95f
  val progressPercent = if (runTimeTicks > 0) {
    (positionTicks.toFloat() / runTimeTicks.toFloat() * 100).toInt().coerceIn(0, 100)
  } else {
    0
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      // 这一行现在有四个动作（播放 + 下载 + 投屏 + 外部播放），间距收到 8dp、
      // 三个图标键收到 48dp，留给播放按钮的宽度才够放「继续播放 · 1:23:45」
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 播放按钮：单击用默认内核（有进度即续播），长按用「另一个」内核（Media3 ↔ mpv）应急。
      // 不用 Button + 内层 clickable 的写法：内层会把单击吃掉，外层 combinedClickable
      // 只能收到长按，两者行为对不上。这里直接用 Box + combinedClickable 自己画。
      // 占满除两个动作键以外的全部宽度（删除键已收进右上角「更多」菜单）。
      PlayButton(
        label = if (hasResume) "继续播放 · ${formatClock(resumeSeconds)}" else "播放",
        onClick = { onPlay(if (hasResume) resumeSeconds else 0, false) },
        onLongClick = { onPlay(if (hasResume) resumeSeconds else 0, true) },
        // 拉起播放器期间：转圈 + 禁点。
        // 单看"转圈"还不够 —— 不挡掉点击的话，用户等得不耐烦会再点，起两个播放页。
        loading = playLaunching,
        // 有续播进度时，按钮里垫一块「已看多少」的填充带（画法与右边下载按钮一致）。
        // 出现条件与按钮文案严格对齐（都用 hasResume）—— 看到填充 ⟺ 看到「继续播放」，
        // 这样不会出现「按钮写着 播放、里面却填了一半」这种自相矛盾的画面。
        progressFraction = if (hasResume && runTimeTicks > 0) {
          (positionTicks.toFloat() / runTimeTicks.toFloat()).coerceIn(0f, 1f)
        } else {
          null
        },
        modifier = Modifier
          .weight(1f)
          .height(56.dp),
      )

      // 下载：与播放按钮同一行，只保留图标。按钮内部**自下而上**填充一块进度色带 ——
      //   下载中：跟真实进度走（300ms 平滑过渡，不会一格格跳）
      //   已暂停：停在当前进度
      //   已下载：填满整枚按钮 + 图标换成对勾
      //   排队中：细条填充 + 透明度呼吸，示意「在队列里等」
      // 这个按钮同时也是「暂停 / 继续」开关：下载中（或排队）点一下暂停、已暂停点一下继续，
      // 图标随状态换成 暂停 / 播放 箭头，让用户能一眼看出再点会发生什么。
      // 已完成只弹提示；不用 enabled 置灰（置灰会把整个按钮压成半透明，进度带就看不清了）。
      if (isDownloadable(item)) {
        val animatedFill by animateFloatAsState(
          targetValue = downloadProgress ?: 0f,
          animationSpec = tween(durationMillis = 300),
          label = "downloadFill",
        )
        val queuedPulse by rememberInfiniteTransition(label = "downloadQueued")
          .animateFloat(
            initialValue = 0.25f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900)),
            label = "downloadQueuedAlpha",
          )
        val isQueued = downloadStatus == EmbyDownloadStatus.QUEUED
        val fillFraction = when {
          downloadProgress == null -> 0f
          isQueued -> 0.06f
          else -> animatedFill.coerceIn(0f, 1f)
        }
        val fillAlpha = if (isQueued) queuedPulse else 1f

        Box(
          modifier =
            Modifier
              .size(48.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(
                if (downloadLabel == null) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceVariant
                }
              )
              .clickable(onClick = onDownload),
          contentAlignment = Alignment.Center,
        ) {
          if (fillFraction > 0f) {
            // 贴底的填充层：fraction=1 时正好铺满整枚按钮
            Box(
              modifier =
                Modifier
                  .align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(fillFraction)
                  .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f * fillAlpha)),
            )
          }
          val (downloadIcon, downloadActionLabel) = when (downloadStatus) {
            EmbyDownloadStatus.RUNNING, EmbyDownloadStatus.QUEUED ->
              Icons.Default.Pause to "暂停下载"

            EmbyDownloadStatus.PAUSED, EmbyDownloadStatus.FAILED ->
              Icons.Default.PlayArrow to "继续下载"

            EmbyDownloadStatus.COMPLETED -> Icons.Default.Check to "已下载"
            null -> Icons.Default.Download to "下载"
          }
          Icon(
            imageVector = downloadIcon,
            contentDescription = downloadActionLabel,
            tint = when {
              downloadFailed -> MaterialTheme.colorScheme.error
              downloadLabel == null -> MaterialTheme.colorScheme.onPrimaryContainer
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
      }

      // 投屏：把当前媒体投到 DLNA 设备（与播放 / 下载同一行）
      Box(
        modifier =
          Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onCast),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Cast,
          contentDescription = "投屏",
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }

      // 外部播放器：把流地址交给 MX / VLC 这类第三方播放器。
      // 用「方框 + 右上角箭头」的 OpenInNew，和旁边的投屏（Cast）区分得开 ——
      // 两者都是「把内容送出去」，光看图标不会混。
      Box(
        modifier =
          Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onPlayExternal),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.OpenInNew,
          contentDescription = "用外部播放器打开",
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }

    // ── 多版本媒体：版本下拉框 ──
    // Emby 把同一个片子的多个版本（4K / 1080P / 不同压制组…）放在 MediaSources 里，
    // 这里给一个与网页端一致的版本切换入口：选中的版本会用于播放 / 转码 / 投屏 / 外部播放器。
    // 只有真的存在多个版本时才渲染 —— 单版本媒体（绝大多数）界面上完全看不出变化。
    if (mediaSources.size > 1) {
      MediaSourceSelector(
        sources = mediaSources,
        selectedId = selectedMediaSourceId,
        onSelect = onSelectMediaSource,
      )
    }

    // 进度：已观看百分比 + 剩余时长 + 总时长，三个数一起给，避免只看到一个百分比
    if (runTimeTicks > 0) {
      Text(
        text = buildString {
          append("已观看 $progressPercent%")
          append(" · 剩余 ${formatEmbyDurationFull(remainingTicks)}")
          append(" · 总时长 ${formatEmbyDurationFull(runTimeTicks)}")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
    }

    // 有续播进度时给一个「从头播放」（蓝色主键此时是续播，两者各司其职）。
    // 「继续上次」不单独出现 —— 蓝色主键就是续播，再放一个是冗余的。
    if (hasResume) {
      FilledTonalButton(onClick = { onPlay(0, false) }) {
        Text("从头播放")
      }
    }
  }
}
/**
 * 多版本条目「当前选中版本」的本地续播秒数；返回 null 表示不要覆盖服务端进度。
 *
 * 规则（三个分支，逐一对应下面三种真实情形）：
 *  1. 这个版本本地有记录 → 用它（**各版本互不干扰**，这就是本功能的全部意义）；
 *  2. 没记录，但**该条目下任一版本**有记录 → 这条版本本地确实没播过 ⇒ 0（从头）；
 *  3. 该条目一条本地记录都没有 → 退回服务端进度（可能是在别的 Emby 客户端看的，
 *     那套进度我们这边无从得知，只能照用）。
 *
 * 单版本条目直接返回 null —— 走服务端进度，行为与改动前完全一致。
 */
private suspend fun loadVersionResumeSeconds(
  repository: PlaybackStateRepository,
  serverId: Long,
  item: EmbyItem,
  selectedMediaSourceId: String?,
  /** 设置里的「多版本优先选择」规则；必须与播放侧同一个值，否则读错版本的进度 */
  preference: MediaSourcePreference,
): Long? {
  if (serverId <= 0) return null
  val itemId = item.Id ?: return null
  val sourceId =
    effectiveEmbyMediaSourceId(item, selectedMediaSourceId, preference) ?: return null

  repository.getVideoDataByTitle(EmbyVersionProgress.key(serverId, itemId, sourceId))?.let {
    return it.lastPosition.toLong()
  }
  val anyVersion =
    repository.getVideoDataByTitlePrefix(EmbyVersionProgress.itemPrefix(serverId, itemId))
  return if (anyVersion != null) 0L else null
}

/**
 * 用 ProviderIds 现拼一条「外部链接」：`<服务器根>/?redirect=<Provider>:<Id>`。
 *
 * 什么时候会走到这里：服务端**没有**回 `ExternalUrls`（内置元数据源不带这个字段，
 * 只有 MetaTube 这类插件型数据源才有），只能自己拼。回的是
 * `名字 to 地址`（[MetaChipRow] 的 chip 文案与点击地址），服务器地址缺失时回 null（不显示这一项）。
 *
 * ## 两个坑（都是真机踩出来的）
 * 1. **必须落在服务器根路径上**：`?redirect=` 是 Emby Web 在**站点根**处理的跳转
 *    （用户环境里的正确地址就是 `http://192.168.2.124:8080/?redirect=JavBus:ACZD-218`）。
 *    写成 `$baseUrl?redirect=...`（baseUrl 是 `http://host:port`，没有结尾斜杠）时
 *    浏览器拿到的是「无路径 + query」，Emby Web 认不出来 ⇒ **跳回首页**。
 *    所以这里统一补上 `/`。
 * 2. **参数是 `Provider:Id` 两段式**，不是裸 Id：Emby 靠前面那段去认「这是哪个外部站点」
 *    （Providers 的 `Key`，见 [EmbyExternalIdInfo] 的 `UrlFormatString`）。
 *    有些数据源自己就把值存成了 `JavBus:ACZD-218` 这种已带前缀的形态 ⇒ 那时**不能再补**
 *    前缀，否则会拼出 `MetaTube:JavBus:ACZD-218` 这种双前缀。这就是下面那句
 *    `contains(':')` 判断的由来。
 */
private fun externalLinkUrl(
  baseUrl: String?,
  providerName: String,
  providerId: String,
): Pair<String, String>? {
  // baseUrl 的形态是 `scheme://host:port`（见 EmbyServer.baseUrl），去掉可能的结尾斜杠，
  // 免得拼出 `http://host:8080//?redirect=` 这种双斜杠
  val origin = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
  val qualified = if (providerId.contains(':')) providerId else "$providerName:$providerId"
  return providerName to "$origin/?redirect=$qualified"
}

/**
 * 详情页的一行「分组元数据」：左边分组名，右边一排可点的 chip，超出屏宽横向滚动。
 *
 * ⚠️ 分组名用**固定宽度**（[META_LABEL_WIDTH]）而不是自适应：各行名字字数不同
 * （类型 / 标签 / 工作室 / 外部链接），不固定的话每行 chip 的起始边都不一样，整块看着就是歪的。
 *
 * [chips] 为空时整行不渲染 —— 免得出现「只有分组名、右边空着」的噪音行。
 */
@Composable
private fun MetaChipRow(
  label: String,
  chips: List<String>,
  onClick: (String) -> Unit,
) {
  if (chips.isEmpty()) return
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      modifier = Modifier.width(META_LABEL_WIDTH),
    )
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      chips.forEach { value ->
        SuggestionChip(
          onClick = { onClick(value) },
          label = { Text(value) },
          colors = SuggestionChipDefaults.suggestionChipColors(),
        )
      }
    }
  }
}

/**
 * 分组名的固定宽度：容得下最长的一个。
 *
 * 最长的是「外部链接」四个字 —— `titleSmall`（14sp）下约 56dp，取 76dp 留出余量，
 * 这样用户把系统字号调大一两档时这个标签也不会被挤断（此前的 64dp 只够三个字）。
 */
private val META_LABEL_WIDTH = 76.dp

/**
 * 多版本媒体的版本下拉框：当前版本胶囊 + 下拉菜单。
 *
 * 与收藏页的类型切换器同一套写法（小 Surface + DropdownMenu）—— Compose 下 M3
 * 没有现成的 Spinner，ExposedDropdownMenuBox 的展开态在可滚动列表里点击判定容易出问题；
 * 这个小组合最稳，观感也与项目里其它下拉一致。
 */
@Composable
private fun MediaSourceSelector(
  sources: List<EmbyMediaSource>,
  selectedId: String?,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val current = sources.firstOrNull { it.Id == selectedId } ?: sources.first()

  Box {
    Surface(
      onClick = { expanded = true },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        // 版本名不可控（有的压制组会把整串信息塞进 Name），不封顶的话一个超长名字
        // 能把整行撑到换行、把下面一排按钮挤变形。
        // weight(1f, fill = false) 让中间的标题吃掉剩余空间 ⇒ 箭头稳定停在最右边，
        // 不再随版本名长短左右晃。
        modifier = Modifier
          .widthIn(min = 140.dp, max = 240.dp)
          .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
      ) {
        Text(
          text = "版本：${embyMediaSourceLabel(current)}",
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          // 吃掉整行剩余宽度（fill 默认 true）⇒ 箭头被顶到最右端，不随版本名长短左右晃。
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "切换版本",
          modifier = Modifier.size(20.dp),
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      // 菜单默认跟着锚点（那个小 Surface）的宽度走 —— 版本名一长就整条被省略号截掉，
      // 用户根本分不清两个版本哪个是哪个。这里单独给一个更宽的区间，
      // 配合下面「最多 3 行」让长名字**换行显示完整**，而不是截成一条看不懂的半句。
      modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
    ) {
      sources.forEach { source ->
        val id = source.Id ?: return@forEach
        DropdownMenuItem(
          text = {
            Text(
              text = embyMediaSourceLabel(source),
              // 不截断：宁可换行把名字显示全。3 行是上限，再多菜单会高得离谱。
              maxLines = 3,
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          leadingIcon = {
            // 选中项打勾；未选中放一个同宽占位，两行文字左对齐
            if (id == current.Id) {
              Icon(Icons.Default.Check, contentDescription = null)
            } else {
              Spacer(modifier = Modifier.width(24.dp))
            }
          },
          onClick = {
            expanded = false
            onSelect(id)
          },
        )
      }
    }
  }
}
/**
 * 主播放按钮：单击 = 默认内核，长按 = 备用内核。
 *
 * 长按的落点是「这片子 mpv 播不动，临时换个内核试试」—— 不用先去设置里改默认内核
 * 再回来点一次。开关关掉（设置 → 长按反选内核）后长按与单击行为一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayButton(
  label: String,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  /**
   * 拉起播放器期间为 true：图标换成转圈、并**禁掉点击**。
   *
   * 禁点比转圈更重要 —— 只转圈不挡，用户等得不耐烦会再点一次，
   * 结果是起了两个播放页（而且后一个会把前一个顶掉，播放进度也乱）。
   */
  loading: Boolean = false,
  /**
   * 已观看进度（0..1）；null = 不画。
   *
   * **自左向右**垫一块色带（进度条形态），fraction=1 时正好铺满整枚按钮 ——
   * 播放进度是「一条时间轴」，横向走更符合直觉。
   * 填充层声明在内容层**之前** ⇒ 永远垫在图标 / 文字底下，不会糊住字。
   */
  progressFraction: Float? = null,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.primary)
        .combinedClickable(
          enabled = !loading,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    val animatedPlayFill by animateFloatAsState(
      targetValue = progressFraction ?: 0f,
      animationSpec = tween(durationMillis = 300),
      label = "playProgressFill",
    )
    if (!loading && progressFraction != null && animatedPlayFill > 0f) {
      Box(
        modifier =
          Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(animatedPlayFill.coerceIn(0f, 1f))
            // 底色本身就是 primary，所以这里反过来用 **onPrimary 的浅色叠加**：
            // 下载按钮是「primary 叠在浅底上」，这里是「浅色叠在 primary 上」，同一个思路。
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      if (loading) {
        // ⚠️ 原来这里还有一枚 20dp 小转圈，已撤掉：等待期间主动效改成
        //   **整页居中**的大加载动画（见 EmbyDetailScreen 根 Box 末尾的
        //   `playLaunching` 覆盖层，走「外观 → 加载动画」统一入口）。
        //   按钮这一小块只留文字说明状态 —— 两处同时转会互相抢注意力。
        //   禁点仍然保留（combinedClickable 的 enabled = !loading）：
        //   连点会起两个播放页，这个比转圈更重要。
        Text(
          text = "正在打开播放器…",
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = label,
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelLarge,
          // 动作键多了一个之后播放按钮更窄，「继续播放 · 1:23:45」得能优雅收尾
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * 能否下载。
 *
 * Series / Season / 文件夹这类条目本身没有视频文件（真正的内容在子条目上），
 * 点下载只会拿到一个 404 或被服务器当成目录处理，所以直接不给按钮。
 * 支持的类型里最常见的三类就是电影、剧集单集、以及家庭视频。
 */
private fun isDownloadable(item: EmbyItem): Boolean {
  val type = item.Type ?: return false
  return type !in setOf(
    "Series",
    "Season",
    "BoxSet",
    "Folder",
    "CollectionFolder",
    "PhotoAlbum",
    "MusicAlbum",
    "MusicArtist",
    "Playlist",
    "Photo",
  )
}

// ════════════════════════════════════════════════════════════════════════
// 媒体信息：基本信息 + 视频/音频/字幕轨道编码信息
//
// 数据组装（buildEmbyMediaInfoEntries / MediaInfoEntry）已抽到
// domain/emby/EmbyMediaInfo.kt —— 播放页「更多 → 媒体信息」弹窗用的是同一份，
// 两个页面不会再各写一套。这里只负责把 entries 画成详情页里的静态分区。
// ════════════════════════════════════════════════════════════════════════

@Composable
private fun MediaInfoSection(
  item: EmbyItem,
  /** 当前选中的版本 Id；多版本媒体下「容器 / 文件大小 / 码率 / 音视频轨道」都跟着它走 */
  selectedMediaSourceId: String? = null,
) {
  val entries =
    remember(item, selectedMediaSourceId) { buildEmbyMediaInfoEntries(item, selectedMediaSourceId) }

  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Default.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("媒体信息", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))

    if (entries.isEmpty()) {
      Text(
        text = "服务器没有返回可用的媒体信息",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(vertical = 6.dp)) {
        entries.forEach { entry ->
          when (entry) {
            is MediaInfoEntry.Section -> {
              Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
              )
            }

            is MediaInfoEntry.Row -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Text(
                  text = entry.label,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.width(76.dp),
                )
                Text(
                  text = entry.value,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}

/** 秒数转 hh:mm:ss */
private fun formatClock(seconds: Long): String {
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部的尺寸常量
//
// 对应 XML 里 AppBarLayout 的展开高度与 Toolbar 的 pin 高度。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 头部大图展开时的高度（静止时封面的高度）。
 *
 * 它同时是：① 封面能撑到的最大高度；② 列表第 0 项「封面占位稿」的高度。
 * 折叠时封面按滚动量线性缩矮，收到 [TOOLBAR_HEIGHT_DP] 为止。
 *
 * ⭐ 想改「折叠触发点」就调这个值：调高 = 封面撑得更久、要滚更多才收完（触发点更靠后）；
 * 调低 = 收得更早更快。**不需要另外维护任何「触发距离」常量** —— 折叠点由几何关系决定。
 */
private val BACKDROP_HEADER_MAX_HEIGHT_DP = 320.dp

/** 折叠完成后 toolbar 剩下的高度（`layout_collapseMode="pin"` 那一条）。取值对齐 M3 TopAppBar。 */
private val TOOLBAR_HEIGHT_DP = 56.dp

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部：把「滚动位置」变成「封面高度」
//
// 这是整个折叠头部**唯一的状态来源**。设计上刻意不引入任何自定义手势：
// 列表滚动是唯一的输入，封面高度是它的纯函数。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 折叠头部的当前形态（由 [rememberHeaderCollapse] 每帧算出）。
 *
 * - `collapsedPx`：已经收掉的像素数（0 = 完全展开）
 * - `maxCollapsePx`：可收掉的总量 = 最大高度 − 工具栏高度
 * - `headerHeightPx`：当前封面高度（工具栏高度 .. 最大高度）
 */
@Immutable
private class HeaderCollapse(
  val collapsedPx: Float,
  val maxCollapsePx: Float,
  val headerHeightPx: Float,
) {
  /** 0（完全展开）~ 1（完全收起）。驱动淡出、视差、顶栏过渡。 */
  val progress: Float
    get() = if (maxCollapsePx <= 0f) 0f else (collapsedPx / maxCollapsePx).coerceIn(0f, 1f)

  /** 封面是否已经完全展开。下拉刷新只在此时接管下拉手势。 */
  val isExpanded: Boolean
    get() = collapsedPx <= 0.5f
}

/**
 * 由列表滚动位置推导封面形态。
 *
 * **原理**：列表第 0 项是一个「封面占位稿」（高度 = [maxHeight]），封面盖在它上面。
 * 占位稿的 y 从 0 一路减到 `−maxHeight`，取它的负值就是「已经滚了多远」，也就是封面该收多少。
 * 因为播放区紧跟着占位稿，所以**内容顶部永远贴着封面底边一起上移**：
 *  - 滚到「播放区到达工具栏下方」时封面正好收完（`collapsedPx == maxHeight − toolbarHeight`）；
 *  - 再往下滚，封面固定为工具栏高度，内容从它下面滑过。
 *
 * 于是「滑到播放按钮就折叠」是几何上自然成立的，不需要任何触发距离常量；
 * 也不需要在滚动时抢手势 —— 只有列表一个消费者，就没有手势冲突。
 *
 * ⚠️ 这里刻意读 `visibleItemsInfo` 里**真实 item** 的 `offset`，而不是
 * `firstVisibleItemScrollOffset`：后者与 contentPadding 的关系有歧义
 * （官方文档：`firstVisibleItemIndex` 不含内容内边距区），拿它推折叠量会差一截。
 * `visibleItemsInfo[i].offset` 是相对视口的真实布局位置，没有这个问题。
 */
@Composable
private fun rememberHeaderCollapse(
  listState: androidx.compose.foundation.lazy.LazyListState,
  maxHeight: Dp,
  toolbarHeight: Dp,
): HeaderCollapse {
  val density = LocalDensity.current
  val maxPx = with(density) { maxHeight.toPx() }
  val toolbarPx = with(density) { toolbarHeight.toPx() }

  val collapsedPx by
    remember(listState, maxPx) {
      derivedStateOf {
        // 占位稿已经滚出第一屏 ⇒ 封面收到最扁
        if (listState.firstVisibleItemIndex > 0) return@derivedStateOf maxPx
        val spacerY = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.offset
          // 首帧还没量出来：先当完全展开，避免封面闪一下
          ?: return@derivedStateOf 0f
        (-spacerY).toFloat().coerceIn(0f, maxPx)
      }
    }

  return HeaderCollapse(
    collapsedPx = collapsedPx,
    maxCollapsePx = (maxPx - toolbarPx).coerceAtLeast(0f),
    headerHeightPx = (maxPx - collapsedPx).coerceAtLeast(toolbarPx),
  )
}

// ══════════════════════════════════════════════════════════════════════════// 折叠动效参数
//
// 折叠**位置**由滚动驱动（见 [rememberHeaderCollapse]）—— 这里只放「外观怎么变」的参数：
// 封面反压扁（底图横向反向放大、维持 CenterCrop 的「缓慢推近」）+ 缓动曲线。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 折叠进度的缓动曲线：先慢后快。
 *
 * 匀速的「刻度感」本身就显廉价、也显压迫；`t²` 让画面一开始很从容、
 * 接近收起时才加速，收尾干脆。开方（`sqrt`）则是反效果 —— 一上来就窜，更压迫。
 */
private fun easeInQuad(t: Float): Float = t * t

// ══════════════════════════════════════════════════════════════════════════
// 简介（可展开 / 收起）
// ══════════════════════════════════════════════════════════════════════════

/** 收起时简介最多显示几行。5 行 ≈ 手机上 150~180 个字，太短了会「还没看出讲什么就被截掉」。 */
private const val OVERVIEW_COLLAPSED_LINES = 5

/**
 * 简介区。
 *
 * 长简介默认只显示 [OVERVIEW_COLLAPSED_LINES] 行 + 省略号，正文下方给一个
 * 「展开 / 收起」的文字按钮（带箭头图标，点标题或箭头整行都能点）。
 *
 * ⚠️ **按钮只在「真的被截断」时才显示**：判断依据是 `onTextLayout` 回调里的
 * `hasVisualOverflow`（布局器事后给出的真实结论），而不是「字数超过 N 个」这种估算。
 * 估算在中文 / 英文 / 不同字号 / 不同屏宽下必然误判 —— 短简介会无端多出一个点了没反应的
 * 「展开」，长简介又可能该给按钮却没给。见记忆 `ui-patterns.md`「文本截断」条。
 *
 * 展开状态用 `rememberSaveable`，转屏 / 返回再进不丢。
 */
@Composable
private fun OverviewSection(overview: String) {
  // key 带上正文：同一屏里条目被换掉（推荐区点进另一部片）时要重新收起 + 重新判定截断
  var expanded by rememberSaveable(overview) { mutableStateOf(false) }
  var overflowed by remember(overview) { mutableStateOf(false) }

  Column(modifier = Modifier.padding(16.dp)) {
    Text("简介", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = overview,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      // 展开后不限行数，让简介自然撑开
      maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_COLLAPSED_LINES,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { result ->
        // 展开态下 onTextLayout 报的是「没截断」，别用它把 overflowed 抹掉 ——
        // 否则一展开按钮就消失、再也没法收起。
        if (!expanded) overflowed = result.hasVisualOverflow
      },
    )
    if (overflowed) {
      Spacer(modifier = Modifier.height(2.dp))
      // 靠左、贴着正文起始边：简介正文本来就从这里开始，按钮跟它对齐读起来才是一体的。
      // 居中反而像一枚独立控件，且整行铺开会白占一行宽度。
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { expanded = !expanded }
          .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (expanded) "收起" else "展开",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Icon(
          imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 推荐：同一位演员 / 导演的其他影片
// ══════════════════════════════════════════════════════════════════════════

/** 推荐区实际展示几条 */
private const val RECOMMEND_COUNT = 6

/** 每个人最多取多少部候选作品回来再洗牌 —— 取少了随机不出来 */
private const val RECOMMEND_PERSON_LIMIT = 60

/** 推荐区要的条目类型：只要正片，不要单集 / MV */
private val RECOMMEND_ITEM_TYPES = listOf("Movie", "Series")

/** 一个人凑不满时，最多再往后看几个人 */
private const val RECOMMEND_MAX_PERSONS = 5

/**
 * 详情页的推荐区（纯渲染）。
 *
 * **随机在客户端做**：Emby 的 `/Items` 虽然有 `SortBy=Random`，但它跟 `SearchTerm`
 * 这类参数组合起来是否生效并不保证（媒体库搜索那趟已经验证过一次），
 * 所以一次多取一些候选，最后 `shuffled().take(6)` —— 每次进详情页看到的都不一样。
 *
 * **作者不足就往下补**：冷门片里排第一的演员可能只有一两部戏，所以只要还没凑够
 * [RECOMMEND_COUNT]，就顺着候选名单继续取下一个人的作品，补满为止。
 *
 * ⚠️ 这里**不自己发请求**：数据（含那次随机的取样）由 [EmbyDetailScreen] 加载好后作为
 * [recommendations] 传进来。请求留在这里的话，LazyColumn 把滚出视口的 item 回收、滚回来
 * 再重组时，LaunchedEffect 会跟着重跑 —— 表现就是「滚动也在刷推荐」。详见那边的注释。
 *
 * @param recommendations 已经加载好、且已经随机取样过的推荐列表；空则不渲染整块
 * @param onOpenItem 打开推荐项的详情页（itemId / 标题）
 */
@Composable
private fun RecommendationSection(
  server: EmbyServer?,
  recommendations: List<EmbyItem>,
  onOpenItem: (String, String) -> Unit,
) {
  // 查不到就整块不显示，不留一个空标题占地方
  if (recommendations.isEmpty()) return

  Column(modifier = Modifier.padding(top = 16.dp)) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("推荐", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(
        items = recommendations,
        key = { it.Id ?: it.Name.orEmpty() },
      ) { rec ->
        RecommendationCard(
          server = server,
          item = rec,
          onClick = {
            val id = rec.Id ?: return@RecommendationCard
            onOpenItem(id, rec.Name ?: "")
          },
        )
      }
    }
  }
}

/**
 * 详情页的「同类型」推荐区（纯渲染）。
 *
 * 与 [RecommendationSection]（按演员 / 导演推荐）互补 —— 那边按「人」找，这边按「题材」找；
 * 冷门片常常凑不出足够的同演员作品，但题材相同的片子通常管够。
 *
 * **用哪个类型**：一部片子常同时属于多个类型（剧情 / 惊悚 / 犯罪…）。固定取第一个
 * 的话，同一部片每次打开看到的推荐都一模一样。开关 `embyRandomGenreRecommend` 打开时
 * 每次进入把本片的类型**随机打乱**再取，于是每次打开题材都不同；关闭时退回「取第一个」。
 *
 * 随机容易挑到没几部同类的冷门类型，所以加载侧按打乱后的顺序**逐个尝试、取第一个查得到
 * 同类的**，避免「随机之后整块推荐消失」。全查不到（或服务端没刮削）才整块不显示。
 *
 * ⚠️ 与 [RecommendationSection] 同理：这里只画，不请求（原因见那边的注释）。
 *
 * @param genre 最终选中的类型名（显示在标题里）；null = 还没选出来，整块不渲染
 * @param picks 已经加载好的同类影片；空则整块不渲染
 */
@Composable
private fun GenreRecommendationSection(
  server: EmbyServer?,
  genre: String?,
  picks: List<EmbyItem>,
  onOpenItem: (String, String) -> Unit,
) {
  val activeGenre = genre
  if (picks.isEmpty() || activeGenre == null) return

  Column(modifier = Modifier.padding(top = 16.dp)) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("同类型 · $activeGenre", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(
        items = picks,
        key = { it.Id ?: it.Name.orEmpty() },
      ) { rec ->
        RecommendationCard(
          server = server,
          item = rec,
          onClick = {
            val id = rec.Id ?: return@RecommendationCard
            onOpenItem(id, rec.Name ?: "")
          },
        )
      }
    }
  }
}

/**
 * 一条推荐：竖版封面 + 片名 + 年份。
 *
 * 只显示 Primary 图，没有封面就不占位 —— 这种小卡片缺封面很常见，
 * 用占位图把整行撑起来反而更难看。
 */
@Composable
private fun RecommendationCard(
  server: EmbyServer?,
  item: EmbyItem,
  onClick: () -> Unit,
) {
  val primaryTag = item.ImageTags["Primary"]
  val posterUrl = remember(item.Id, primaryTag, server) {
    server?.let { s ->
      item.Id?.let { id -> EmbyClient.imageUrl(s, id, "Primary", primaryTag, 360) }
    }
  }
  Column(
    modifier = Modifier
      .width(100.dp)
      .clickable(onClick = onClick),
  ) {
    Card(
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f),
    ) {
      EmbyImage(
        url = posterUrl,
        contentDescription = item.Name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 360,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = item.Name ?: "",
      style = MaterialTheme.typography.bodySmall,
      maxLines = 2,
      minLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    item.ProductionYear?.let { year ->
      Text(
        text = year.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 取推荐列表：作者候选 → 逐个取作品 → 去重 → 客户端随机取样。
 *
 * 全程在 IO 线程，里面每次 [EmbyViewModel.loadPersonItems] 都是一次网络请求，
 * 所以「顺位补人」是有成本的 —— 因此缓存一 [RECOMMEND_MAX_PERSONS] 上限，
 * 并且一旦凑够 [RECOMMEND_COUNT] 就立刻停手，不再往下请求。
 */
private suspend fun loadRecommendations(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
): List<EmbyItem> = withContext(Dispatchers.IO) {
  val candidates = item.People.orEmpty()
    .filter { it.Id != null && (it.Type == "Actor" || it.Type == "Director") }
    // Actor 优先：演员参演的片子更容易是「同款想看」，导演排在后面补位
    .sortedBy { if (it.Type == "Actor") 0 else 1 }
    .take(RECOMMEND_MAX_PERSONS)

  if (candidates.isEmpty()) return@withContext emptyList<EmbyItem>()

  val selfId = item.Id
  // LinkedHashMap：保序 + 按 Id 去重（同一个条日可能从多位作者的查询里重复返回）
  val pool = LinkedHashMap<String, EmbyItem>()

  for (person in candidates) {
    if (pool.size >= RECOMMEND_COUNT) break
    val works = viewModel.loadPersonItems(
      server = server,
      personId = person.Id ?: continue,
      limit = RECOMMEND_PERSON_LIMIT,
      // 只要正片：单集（Episode）会把推荐区塞满同一部剧的几十集，MV 同理
      includeItemTypes = RECOMMEND_ITEM_TYPES,
    )
    works.forEach { work ->
      val id = work.Id ?: return@forEach
      // 把当前这部剔掉：一进详情页就推荐自己，看着像 bug
      if (id == selfId) return@forEach
      pool.putIfAbsent(id, work)
    }
  }

  if (pool.isEmpty()) return@withContext emptyList<EmbyItem>()
  pool.values.shuffled().take(RECOMMEND_COUNT)
}

/**
 * 取某个题材下的同类影片：`SortBy=Random` 由服务端排序（本区不需要客户端再随机），
 * 拿到后剔掉自己、按 Id 去重（服务端偶发重复条目），最后截到 [RECOMMEND_COUNT]。
 *
 * 只查一个题材、一趟请求，所以「逐个试候选题材」的循环交给调用方（见 [EmbyDetailScreen]
 * 里那块推荐加载逻辑）—— 那边命中第一个非空结果就停。
 */
private suspend fun loadGenreRecommendations(
  server: EmbyServer,
  genre: String,
  selfId: String?,
): List<EmbyItem> = withContext(Dispatchers.IO) {
  runCatching {
    EmbyClient.getItems(
      server = server,
      genres = listOf(genre),
      includeItemTypes = RECOMMEND_ITEM_TYPES,
      // 随机排序：每次进详情页看到的同类顺序不同，避免「永远是那几部」
      sortBy = "Random",
      recursive = true,
      startIndex = 0,
      limit = 60,
    ).Items
  }.getOrDefault(emptyList())
    // 剔掉自己，并按 Id 去重（服务端偶发重复条目）
    .filter { it.Id != null && it.Id != selfId }
    .distinctBy { it.Id }
    .take(RECOMMEND_COUNT)
}
