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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyStudio
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
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
import kotlin.math.abs
import kotlin.math.round

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

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        isLoading && item == null -> EmbySkeletonDetail()

        error != null && item == null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val current = item ?: return@Box
          val currentServer = server ?: return@Box
          // 该条目当前的下载任务（没有就是 null）：按钮的文字、进度、图标都由它决定
          val currentDownload = current.Id?.let { id ->
            downloadTasks.firstOrNull { it.itemId == id }
          }

          // ── 下拉刷新 ──
          // 详情页的数据（进度、已看状态、演员、推荐）都是进页面那一次拉回来的，
          // 从播放器返回时虽然有 800ms 延迟的自动刷新，但用户手动拉一下仍然是最直接的重取方式。
          // 手势与折叠头部天然分工：头部还没展开完时下拉先展开头部（内层 NestedScrollConnection
          // 会把这段位移吃掉），只有头部已经完全展开、列表又停在顶部时，位移才会漏到下拉刷新上。
          PullRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { load() },
            modifier = Modifier.fillMaxSize(),
          ) {
            DetailBody(
              item = current,
              server = currentServer,
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
                scope.launch { viewModel.play(currentServer, current, resume, reverse) }
              },
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
                  val url = viewModel.getStreamUrl(currentServer, id, true)
                  dlnaManager.pendingPayload =
                    CastPayload(Uri.parse(url), viewModel.displayTitle(current))
                  castSheetShown = true
                }
              },
              // 外部播放器：先枚举本机能吃这条流的播放器，再用我们自己的列表让用户挑
              // （为什么不用系统选择器，见 ExternalPlayerPickerDialog 的注释）。
              // 一个都没枚举到时把原因说出来，否则点了没反应会让人以为是按钮坏了
              onPlayExternal = {
                viewModel.listExternalPlayers(currentServer, current)
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
                val resumeSeconds =
                  EmbyTicks.ticksToSeconds(current.UserData?.PlaybackPositionTicks ?: 0L)
                Toast.makeText(context, "正在请求服务器转码…", Toast.LENGTH_SHORT).show()
                scope.launch {
                  viewModel.playTranscoded(currentServer, current, resumeSeconds)
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
              // 点类型 / 标签 chip：进「按该类型 / 标签找片」的结果页
              onMetaClick = { keyword, isGenre ->
                backStack.add(
                  EmbyTagItemsScreen(keyword = keyword, kind = if (isGenre) "genre" else "tag"),
                )
              },
              moreMenuExpanded = showMoreMenu,
              onMoreMenuChange = { showMoreMenu = it },
              viewModel = viewModel,
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
            viewModel.playWithExternalPlayer(serverTarget, playerTarget, player)
              .onFailure { e ->
                Toast.makeText(context, e.message ?: "打开外部播放器失败", Toast.LENGTH_LONG).show()
              }
          }
        },
        onSystemChooser = {
          externalPlayers = null
          if (playerTarget != null && serverTarget != null) {
            viewModel.playWithExternalPlayerChooser(serverTarget, playerTarget)
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
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBody(
  item: EmbyItem,
  server: EmbyServer?,
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
  /** 点类型 / 标签 chip：进「按该类型 / 标签找片」页（keyword / 是否类型） */
  onMetaClick: (keyword: String, isGenre: Boolean) -> Unit,
  /** 点简介 chip：跳演员作品页等 —— 这里只用到打开详情页 */
  downloadLabel: String?,
  /** 当前下载任务状态；null = 没有任务。用来决定按钮是「下载 / 暂停 / 继续 / 已完成」 */
  downloadStatus: EmbyDownloadStatus?,
  /** 下载填充进度 0..1；null = 没有下载任务（不画填充） */
  downloadProgress: Float?,
  downloadFailed: Boolean,
  moreMenuExpanded: Boolean,
  onMoreMenuChange: (Boolean) -> Unit,
  /** 「推荐」区要按人员 Id 反查作品，所以需要 ViewModel */
  viewModel: EmbyViewModel,
  /** 点推荐卡片：打开对应媒体的详情页（itemId / 标题） */
  onRecommendClick: (String, String) -> Unit,
) {
  // 详情页推荐区开关：关闭后「推荐」与「同类型推荐」两块整块不渲染，也不发那几次请求。
  val showRecommendations by
    koinInject<BrowserPreferences>().embyShowRecommendations.collectAsState()

  val listState = rememberLazyListState()
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true

  // ── 折叠头部的状态 ──
  // 等价于 XML 的 AppBarLayout + CollapsingToolbarLayout：头部大图随内容滚动逐步收起，
  // 工具栏保持不动（pin），收起一定程度后标题淡入工具栏。详见 [rememberCollapseState]。
  val collapse = rememberCollapseState(listState = listState)
  // collapse.collapsedPx 是 state，每帧变化都会触发这里的重组，所以直接用它算高度即可
  val collapsedPx = collapse.collapsedPx
  val headerHeightDp = with(LocalDensity.current) {
    (BACKDROP_HEADER_MAX_HEIGHT_DP.toPx() - collapsedPx).coerceAtLeast(TOOLBAR_HEIGHT_DP.toPx()).toDp()
  }

  Box(modifier = Modifier.fillMaxSize().nestedScroll(collapse.connection)) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      // 顶部的空档留给封面 + 工具栏：折叠时跟着一起变矮，视觉上就是「内容把封面顶上去」。
      // 这一步和 [NestedScrollConnection] 的消费是配套的 —— 滑动先把这段距离吃掉，
      // 列表本身在这一段里并不滚动，所以不会出现「封面收一半、列表也滚一半」的重影。
      contentPadding = PaddingValues(top = headerHeightDp, bottom = 96.dp),
    ) {
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
        )
      }

      // ── 类型 / 标签 chip ──
      // 点一下进「按该类型 / 标签找片」的结果页，和点演职员头像进作品页是同一套交互。
      // 类型在前、标签在后，同排横向滚动；两者都是 Emby /Items 支持的过滤维度。
      val genreList = item.Genres
      val tagList = item.Tags.filter { it.isNotBlank() }
      if (genreList.isNotEmpty() || tagList.isNotEmpty()) {
        item {
          Row(
            modifier = Modifier
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            genreList.forEach { genre ->
              SuggestionChip(
                onClick = { onMetaClick(genre, true) },
                label = { Text(genre) },
                colors = SuggestionChipDefaults.suggestionChipColors(),
              )
            }
            tagList.forEach { tag ->
              SuggestionChip(
                onClick = { onMetaClick(tag, false) },
                label = { Text(tag) },
                colors = SuggestionChipDefaults.suggestionChipColors(),
              )
            }
          }
        }
      }

      // ── 简介 ──
      item.Overview?.takeIf { it.isNotBlank() }?.let { overview ->
        item {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("简介", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = overview,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
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
      // 设置里关掉「详情页推荐」后整块不渲染（连带省掉下面的网络请求）。
      if (showRecommendations) {
        item {
          RecommendationSection(
            item = item,
            server = server,
            viewModel = viewModel,
            onOpenItem = onRecommendClick,
          )
        }

        // ── 同类型推荐：按本片第一个类型（Genres）横向推荐 ──
        // 与上面的「演员 / 导演」推荐互补：那边按人找，这边按题材找。
        item {
          GenreRecommendationSection(
            item = item,
            server = server,
            onOpenItem = onRecommendClick,
          )
        }
      }

      // ── 媒体信息（含完整视频 / 音频编码信息）──
      item {
        MediaInfoSection(item = item)
      }
    }

    // ── 封面区（浮在下层内容之上，随滚动折叠）──
    // 它不放在 LazyColumn 里当 item，是因为要能做视差位移、且高度随滚动变化；
    // 对应 XML 里 AppBarLayout 套 ImageView(layout_collapseMode="parallax") + Toolbar(pin)。
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(headerHeightDp)
        .align(Alignment.TopCenter)
        .graphicsLayer {
          // 收到最后阶段整体淡出，避免和 toolBar 的文字挤在一起
          alpha = (1f - collapse.progress * 1.6f).coerceIn(0f, 1f)
        },
    ) {
      BackdropHeader(
        item = item,
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        parallaxPx = collapsedPx,
      )
    }

    // ── 顶部浮层操作栏 ──
    // 展开时透明 + 白图标（压在封面上），折叠后过渡到实体的工具栏底色 + 常规前景色，
    // 并把标题淡入进来 —— 对应 XML 里 `layout_collapseMode="pin"` 那条 Toolbar。
    val barProgress = collapse.progress
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
) {
  val positionTicks = item.UserData?.PlaybackPositionTicks ?: 0L
  val runTimeTicks = item.RunTimeTicks ?: 0L
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

    // 进度：已观看百分比 + 剩余时长 + 总时长，三个数一起给，避免只看到一个百分比
    if (runTimeTicks > 0) {
      Text(
        text = buildString {
          append("已观看 $progressPercent%")
          append(" · 剩余 ${formatDurationFull(remainingTicks)}")
          append(" · 总时长 ${formatDurationFull(runTimeTicks)}")
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
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.primary)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
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
// ════════════════════════════════════════════════════════════════════════

/** 信息表的一行：分区标题（label == null 用 Section）或键值行 */
private sealed interface MediaInfoEntry {
  data class Section(
    val title: String,
  ) : MediaInfoEntry

  data class Row(
    val label: String,
    val value: String,
  ) : MediaInfoEntry
}

@Composable
private fun MediaInfoSection(item: EmbyItem) {
  val entries = remember(item) { buildInfoEntries(item) }

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

/**
 * 把 Emby 返回的字段整理成「分区 + 键值行」。
 *
 * 顺序：基本信息（含添加时间 / 文件路径）→ 每个视频轨道 → 每个音频轨道 → 字幕轨道。
 * 值为空的项直接跳过，不会出现「未知 / N/A」这种噪音行。
 */
private fun buildInfoEntries(item: EmbyItem): List<MediaInfoEntry> {
  val out = mutableListOf<MediaInfoEntry>()

  fun add(label: String, value: String?) {
    if (!value.isNullOrBlank()) out += MediaInfoEntry.Row(label, value)
  }

  fun section(title: String) {
    out += MediaInfoEntry.Section(title)
  }

  // ── 基本信息 ──
  val source = item.MediaSources?.firstOrNull()

  add("添加时间", formatIsoDate(item.DateCreated))
  add("首播日期", formatIsoDate(item.PremiereDate))
  add("时长", item.RunTimeTicks?.takeIf { it > 0 }?.let { formatDurationFull(it) })
  add("年份", item.ProductionYear?.toString())
  add("分级", item.OfficialRating)
  add("评分", item.CommunityRating?.let { "★ %.1f".format(it) })
  add("制作", item.Studios?.firstOrNull()?.Name)
  add("容器", source?.Container?.uppercase())
  add("文件大小", source?.Size?.takeIf { it > 0 }?.let { formatFileSize(it) })
  add("总码率", source?.Bitrate?.takeIf { it > 0 }?.let { formatBitrate(it) })
  add("文件路径", item.Path)
  if (out.isEmpty()) {
    add("名称", item.Name)
  }

  val streams = source?.MediaStreams.orEmpty()

  // ── 视频轨道 ──
  val videos = streams.filter { it.Type == "Video" }
  videos.forEachIndexed { index, v ->
    section(if (videos.size > 1) "视频轨道 ${index + 1}" else "视频")
    add("类型", "视频 (Video)")
    add("语言", languageLabel(v.Language))
    add("编码器", codecLine(v))
    add("编码标识", v.CodecTag)
    if (v.Width != null && v.Height != null) add("分辨率", "${v.Width}×${v.Height}")
    add("画面比例", v.AspectRatio)
    add("帧率", frameRateLabel(v))
    add("动态范围", (v.VideoRangeType ?: v.VideoRange)?.uppercase())
    add("比特率", v.BitRate?.takeIf { it > 0 }?.let { formatBitrate(it) })
    add("位深度", v.BitDepth?.let { "$it bit" })
    add("像素格式", v.PixelFormat)
    add("色彩空间", v.ColorSpace)
    add("传输特性", v.ColorTransfer)
    add("色彩原色", v.ColorPrimaries)
    v.IsInterlaced?.let { add("扫描方式", if (it) "隔行 (Interlaced)" else "逐行 (Progressive)") }
    v.RefFrames?.takeIf { it > 0 }?.let { add("参考帧", "$it 帧") }
  }

  // ── 音频轨道 ──
  val audios = streams.filter { it.Type == "Audio" }
  audios.forEachIndexed { index, a ->
    section(if (audios.size > 1) "音频轨道 ${index + 1}" else "音频")
    add("类型", "音频 (Audio)")
    add("语言", languageLabel(a.Language))
    add("编码器", codecLine(a))
    add("编码标识", a.CodecTag)
    add("声道", a.Channels?.let { c -> a.ChannelLayout?.let { "$c ($it)" } ?: "$c" })
    add("采样率", a.SampleRate?.takeIf { it > 0 }?.let { "$it Hz" })
    add("比特率", a.BitRate?.takeIf { it > 0 }?.let { formatBitrate(it) })
    add("位深度", a.BitDepth?.let { "$it bit" })
    a.IsDefault?.let { add("默认音轨", if (it) "是" else "否") }
  }

  // ── 字幕轨道 ──
  val subtitles = streams.filter { it.Type == "Subtitle" }
  if (subtitles.isNotEmpty()) {
    section("字幕（共 ${subtitles.size} 条）")
    subtitles.forEachIndexed { index, s ->
      val tags = buildList {
        if (s.IsDefault == true) add("默认")
        if (s.IsForced == true) add("强制")
        add(if (s.IsExternal == true) "外挂" else "内嵌")
      }
      add(
        "字幕 ${index + 1}",
        listOfNotNull(
          languageLabel(s.Language) ?: s.DisplayTitle,
          s.Codec?.uppercase(),
        ).joinToString(" · ") + " (${tags.joinToString("/")})",
      )
    }
  }

  return out
}

/** `HEVC (Main 10 · L4.1)` 这样的编码器描述；没有档次/级别时只给编码名 */
private fun codecLine(stream: EmbyMediaStream): String? {
  val codec = stream.Codec?.uppercase() ?: return null
  val extras = buildList {
    stream.Profile?.takeIf { it.isNotBlank() }?.let { add(it) }
    stream.Level?.let { add("L$it") }
  }
  return if (extras.isEmpty()) codec else "$codec (${extras.joinToString(" · ")})"
}

/** 帧率：优先标称值，其次实际值；整数帧率不带小数点 */
private fun frameRateLabel(stream: EmbyMediaStream): String? {
  val fps = stream.FrameRate ?: stream.RealFrameRate ?: stream.AverageFrameRate ?: return null
  if (fps <= 0.0) return null
  val rounded = round(fps * 100) / 100.0
  return if (abs(rounded - rounded.toInt()) < 0.005) "${rounded.toInt()} fps" else "%.2f fps".format(rounded)
}

/** `2024-01-15T10:23:45.0000000Z` → `2024-01-15` */
private fun formatIsoDate(raw: String?): String? {
  if (raw.isNullOrBlank()) return null
  val datePart = raw.substringBefore('T')
  return datePart.takeIf { it.length >= 8 }
}

/** ISO 639-2/1 语言码 → 中文名（带原码），未知码原样返回 */
private fun languageLabel(code: String?): String? {
  if (code.isNullOrBlank()) return null
  return when (code.lowercase()) {
    "chi", "zho", "zh", "chs", "cht" -> "中文 ($code)"
    "eng", "en" -> "英语 ($code)"
    "jpn", "ja" -> "日语 ($code)"
    "kor", "ko" -> "韩语 ($code)"
    "fra", "fre", "fr" -> "法语 ($code)"
    "deu", "ger", "de" -> "德语 ($code)"
    "spa", "es" -> "西班牙语 ($code)"
    "rus", "ru" -> "俄语 ($code)"
    "und" -> "未指定"
    else -> code
  }
}

private fun formatBitrate(bps: Long): String = when {
  bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
  bps >= 1_000 -> "%d kbps".format(bps / 1_000)
  else -> "$bps bps"
}

/**
 * tick 时长 → 中文可读文本，**秒级也一定有数字**。
 *
 * 与 [formatDuration]（首页/卡片用，只到分钟、不足 1 分钟返回空串）的区别就在这里：
 * 详情页的「剩余」如果不足 1 分钟会显示成空括号，看起来像没数据。
 */
private fun formatDurationFull(ticks: Long): String {
  val totalSeconds = EmbyTicks.ticksToSeconds(ticks).coerceAtLeast(0L)
  if (totalSeconds <= 0L) return "0 秒"
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return when {
    hours > 0 -> "${hours}小时${minutes}分"
    minutes > 0 -> "${minutes}分${seconds}秒"
    else -> "${seconds}秒"
  }
}

/** 秒数转 hh:mm:ss */
private fun formatClock(seconds: Long): String {
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatFileSize(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  return when {
    mb >= 1024 -> "%.2f GB".format(mb / 1024)
    else -> "%.0f MB".format(mb)
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部的尺寸常量
//
// 对应 XML 里 AppBarLayout 的展开高度与 Toolbar 的 pin 高度。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 头部大图完全展开时的高度。
 *
 * 取 240dp~320dp 区间的上限：这块是「整张剧照铺满」的观感来源，高度不够图会被压扁成一条；
 * 顶部 56dp 留给工具栏，其余全是图，标题叠在底部。
 */
private val BACKDROP_HEADER_MAX_HEIGHT_DP = 320.dp

/** 折叠完成后 toolbar 剩下的高度（`layout_collapseMode="pin"` 那一条）。取值对齐 M3 TopAppBar。 */
private val TOOLBAR_HEIGHT_DP = 56.dp

// ══════════════════════════════════════════════════════════════════════════
// 折叠头部：Compose 版的 CollapsingToolbarLayout
// ══════════════════════════════════════════════════════════════════════════

/**
 * 折叠状态。
 *
 * - `collapsedPx`：已经收掉的像素数（0 = 完全展开，[rangePx] = 完全折叠）
 * - [rangePx]：可折叠行程 = 展开高度 − 工具栏高度
 * - [progress]：0~1，用于驱动工具栏底色与过渡
 *
 * Compose 没有 `AppBarLayout`，所以要自己用 [NestedScrollConnection] 抢滚动量。
 * （`TopAppBarDefaults.exitUntilCollapsedScrollBehavior` 只管 Toolbar 自身那条，
 * 管不到内容区顶部那整块封面的视差位移。）
 */
private class CollapseState(
  private val listState: androidx.compose.foundation.lazy.LazyListState,
) {
  var collapsedPx by mutableFloatStateOf(0f)
  var rangePx by mutableFloatStateOf(0f)

  val progress: Float
    get() = if (rangePx <= 0f) 0f else (collapsedPx / rangePx).coerceIn(0f, 1f)

  /**
   * 抢滚动量的连接：挂在最外层容器上，内容滚动会先经过这里。
   *
   * [listState] 只用来判断「列表是否已经贴顶」——决定下拉时该展开封面还是滚列表。
   */
  val connection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      val range = rangePx
      if (range <= 0f) return Offset.Zero
      val dy = available.y
      return when {
        // 手指向上（内容上移）：先把展开的那段封面收回去
        dy < 0f -> {
          val consume = (-dy).coerceAtMost(range - collapsedPx)
          collapsedPx += consume
          Offset(0f, -consume)
        }

        // 手指向下（内容下移）：只有列表已经贴到顶部时才反向放出封面，
        // 否则一次下拉会同时「展开封面 + 滚动列表」，看着很怪
        dy > 0f &&
          listState.firstVisibleItemIndex == 0 &&
          listState.firstVisibleItemScrollOffset == 0 -> {
          val consume = dy.coerceAtMost(collapsedPx)
          collapsedPx -= consume
          Offset(0f, consume)
        }

        else -> Offset.Zero
      }
    }
  }
}

@Composable
private fun rememberCollapseState(
  listState: androidx.compose.foundation.lazy.LazyListState,
): CollapseState {
  val state = remember(listState) { CollapseState(listState) }
  val density = LocalDensity.current
  LaunchedEffect(density, state) {
    state.rangePx = with(density) { (BACKDROP_HEADER_MAX_HEIGHT_DP - TOOLBAR_HEIGHT_DP).toPx() }
  }
  return state
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
 * 详情页的推荐区。
 *
 * **随机在客户端做**：Emby 的 `/Items` 虽然有 `SortBy=Random`，但它跟 `SearchTerm`
 * 这类参数组合起来是否生效并不保证（媒体库搜索那趟已经验证过一次），
 * 所以这里一次多取一些候选，最后 `shuffled().take(6)` —— 每次进详情页看到的都不一样。
 *
 * **作者不足就往下补**：冷门片里排第一的演员可能只有一两部戏，所以只要还没凑够
 * [RECOMMEND_COUNT]，就顺着候选名单继续取下一个人的作品，补满为止。
 *
 * @param onOpenItem 打开推荐项的详情页（itemId / 标题）
 */
@Composable
private fun RecommendationSection(
  item: EmbyItem,
  server: EmbyServer?,
  viewModel: EmbyViewModel,
  onOpenItem: (String, String) -> Unit,
) {
  var recommendations by remember(item.Id) { mutableStateOf<List<EmbyItem>>(emptyList()) }

  LaunchedEffect(item.Id, server) {
    val current = server ?: return@LaunchedEffect
    recommendations = emptyList()
    recommendations = loadRecommendations(item = item, server = current, viewModel = viewModel)
  }

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
 * 详情页的「同类型」推荐区：按本片的某个类型（Genres）横向列出同类影片。
 *
 * 与 [RecommendationSection]（按演员 / 导演推荐）互补 —— 那边按「人」找，这边按「题材」找；
 * 冷门片常常凑不出足够的同演员作品，但题材相同的片子通常管够。
 *
 * **用哪个类型**：一部片子常同时属于多个类型（剧情 / 惊悚 / 犯罪…）。固定取第一个
 * 的话，同一部片每次打开看到的推荐都一模一样。开关 `embyRandomGenreRecommend` 打开时
 * 每次进入把本片的类型**随机打乱**再取，于是每次打开题材都不同；关闭时退回「取第一个」。
 *
 * 随机容易挑到没几部同类的冷门类型，所以这里按打乱后的顺序**逐个尝试、取第一个查得到
 * 同类的**，避免「随机之后整块推荐消失」。全查不到（或服务端没刮削）才整块不显示。
 */
@Composable
private fun GenreRecommendationSection(
  item: EmbyItem,
  server: EmbyServer?,
  onOpenItem: (String, String) -> Unit,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val randomGenre by browserPreferences.embyRandomGenreRecommend.collectAsState()

  // 候选类型：随机开关打开 → 本片类型打乱后的顺序；关闭 → 只用第一个。
  // remember 以 item.Id 为键：离开详情页再进来组合重建，于是「每次打开都换一批」。
  val genres = item.Genres
  val candidateGenres =
    remember(item.Id, genres, randomGenre) {
      when {
        genres.isEmpty() -> emptyList()
        randomGenre -> genres.shuffled()
        else -> listOf(genres.first())
      }
    }

  var genre by remember(item.Id) { mutableStateOf<String?>(null) }
  var picks by remember(item.Id) { mutableStateOf<List<EmbyItem>>(emptyList()) }

  LaunchedEffect(item.Id, candidateGenres, server) {
    val current = server ?: return@LaunchedEffect
    if (candidateGenres.isEmpty()) return@LaunchedEffect
    picks = emptyList()
    genre = null
    // 按候选顺序逐个试，命中第一个就停；全空才放弃（见上方注释）
    for (g in candidateGenres) {
      val found =
        withContext(Dispatchers.IO) {
          runCatching {
            EmbyClient.getItems(
              server = current,
              genres = listOf(g),
              includeItemTypes = RECOMMEND_ITEM_TYPES,
              // 随机排序：每次进详情页看到的同类顺序不同，避免「永远是那几部」
              sortBy = "Random",
              recursive = true,
              startIndex = 0,
              limit = 60,
            ).Items
          }.getOrDefault(emptyList())
            // 剔掉自己，并按 Id 去重（服务端偶发重复条目）
            .filter { it.Id != null && it.Id != item.Id }
            .distinctBy { it.Id }
            .take(RECOMMEND_COUNT)
        }
      if (found.isNotEmpty()) {
        genre = g
        picks = found
        break
      }
    }
  }

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
