package app.marlboroadvance.mpvex.ui.cine.detail

import android.app.Application
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.dlna.CastPayload
import app.marlboroadvance.mpvex.dlna.DlnaCastManager
import app.marlboroadvance.mpvex.domain.dislike.DislikeKey
import app.marlboroadvance.mpvex.domain.dislike.DislikeRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.ExternalPlayerOption
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.ExternalPlayerPickerDialog
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarRecommendationViewModel
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.actions.CineActionExtras
import app.marlboroadvance.mpvex.ui.cine.actions.CineActionSheetHost
import app.marlboroadvance.mpvex.ui.cine.actions.rememberCineActionSheetState
import app.marlboroadvance.mpvex.ui.cine.components.CineFloatingBackButton
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CinePosterRow
import app.marlboroadvance.mpvex.ui.cine.components.CineSectionHeader
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineGlass
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.cine.rememberCinePlayController
import app.marlboroadvance.mpvex.ui.cine.rememberCineScreenHeightDp
import app.marlboroadvance.mpvex.ui.cine.rememberCineWide
import app.marlboroadvance.mpvex.ui.cine.toCineMedia
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.pressDownScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Cine 详情页（v134 版式）：剧照 + 居中信息块 + 横滑分集。
 *
 * ## 版式来源与本地化取舍
 * 信息结构与主流流媒体客户端的详情页对齐（剧照渐隐 → 评分 → 规格 → 类型 →
 * 全宽主按钮 → 次操作 → 简介 → 分集 → 演职员），**材质**沿用 Cine 自己的玻璃语言。
 * 两处刻意的本地化：
 * 1. 参考版没有文字标题（海报自带艺术字）。这里做成「有 ClearLogo 用 Logo、
 *    没有则居中文字标题」—— Emby 库里相当一部分条目没有 Logo，全去掉会认不出片子。
 * 2. 参考版右上角有搜索。详情页里调全局搜索会打断上下文，这里只保留「更多」。
 *
 * ## 数据都提到本层
 * 分集 / 相似推荐都放在这里而不是各自的区块里：LazyColumn 滚出视口会销毁组合，
 * 请求写在区块内部的话，上下滑一趟就会把整部剧的分集重复拉好几轮（Classic 踩过）。
 *
 * ## 功能对齐 Classic
 * 「更多」菜单直接复用 [CineActionSheetHost]（长按菜单同款），并补齐详情页特有的
 * 下载 / 不喜欢 / 外部播放器三项 —— Classic 详情页工具栏有的，这里一项不少。
 */
@Composable
fun CineDetailScreen(
  itemId: String,
  title: String,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val similarViewModel: SimilarRecommendationViewModel = viewModel(
    factory = SimilarRecommendationViewModel.factory(app),
  )
  val downloadViewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(app),
  )
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val server by viewModel.currentServer.collectAsState()
  val prefs = koinInject<BrowserPreferences>()
  val showRecommendations by prefs.embyShowRecommendations.collectAsState()

  val wide = rememberCineWide()
  val space = CineTheme.space
  val colors = CineTheme.colors
  val screenHeight = rememberCineScreenHeightDp()

  var item by remember(itemId) { mutableStateOf<EmbyItem?>(null) }
  var loading by remember(itemId) { mutableStateOf(true) }
  var error by remember(itemId) { mutableStateOf<String?>(null) }
  var reloadTick by remember(itemId) { mutableIntStateOf(0) }

  var episodes by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var children by remember(itemId) { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var seasonIndex by remember(itemId) { mutableIntStateOf(0) }
  // 正序 / 倒序：长篇番剧追更时倒序更实用，且只影响本地展示
  var descending by remember(itemId) { mutableStateOf(false) }

  val favoriteOverrides = remember { mutableStateMapOf<String, Boolean>() }
  var favoriteBusy by remember { mutableStateOf(false) }
  var overrideTick by remember { mutableIntStateOf(0) }
  var overviewExpanded by remember(itemId) { mutableStateOf(false) }

  val similar by similarViewModel.similar.collectAsState()
  val play = rememberCinePlayController()
  val failedText = stringResource(R.string.cine_favorite_failed)

  // ── 更多菜单（= 完整功能集） ──
  val actionSheet = rememberCineActionSheetState()

  // 下载状态：与老 UI 同一份任务队列，不另起一套
  val downloadTasks by downloadViewModel.tasks.collectAsState()
  val currentDownload = item?.Id?.let { id -> downloadTasks.firstOrNull { it.itemId == id } }

  // ── 「标记为不喜欢」──
  // 状态读仓库的内存快照（O(1)、零 IO）并订阅全量流：在播放器里标了 / 取消了，
  // 回到本页时菜单文案与图标自动跟着变，不需要任何「通知详情页刷新」的代码。
  val dislikeRepository = koinInject<DislikeRepository>()
  val embyRepository = koinInject<EmbyRepository>()
  val dislikeMarks by dislikeRepository.all.collectAsState()
  val showDislikeInMenu by prefs.dislikeShowInDetailMenu.collectAsState()
  val dislikeKey = remember(item?.Id, server?.id) {
    val sid = server?.id
    val iid = item?.Id
    if (sid != null && iid != null) DislikeKey(sid, iid) else null
  }
  val isDisliked = dislikeKey != null && dislikeMarks.any { it.key == dislikeKey && it.active }

  // ── 投屏 ──
  // 与 Classic 共用同一个 DlnaCastManager 单例：在播放器里正在投的片子，
  // 这边的面板一打开就是「正在投屏」状态，不会当成新会话
  val dlnaManager = koinInject<DlnaCastManager>()
  var castSheetShown by remember { mutableStateOf(false) }

  // ── 外部播放器 ──
  var externalPlayers by remember { mutableStateOf<List<ExternalPlayerOption>?>(null) }
  var externalLastKey by remember { mutableStateOf("") }

  val downloadStatusLabel = currentDownload?.let { task ->
    when (task.status) {
      EmbyDownloadStatus.COMPLETED -> stringResource(R.string.cine_download_completed)
      EmbyDownloadStatus.PAUSED -> stringResource(R.string.cine_download_paused)
      EmbyDownloadStatus.FAILED -> stringResource(R.string.cine_download_failed)
      EmbyDownloadStatus.QUEUED -> stringResource(R.string.cine_download_queued)
      EmbyDownloadStatus.RUNNING ->
        stringResource(R.string.cine_download_running, ((task.progressFraction ?: 0f) * 100).toInt())
    }
  }
  val downloadActive = currentDownload?.status == EmbyDownloadStatus.RUNNING ||
    currentDownload?.status == EmbyDownloadStatus.QUEUED

  suspend fun load() {
    val current = viewModel.currentServerOrAwait() ?: return
    loading = true
    runCatching { viewModel.loadItemDetail(current, itemId) }
      .onSuccess {
        item = it
        error = null
      }
      .onFailure { error = it.message ?: context.getString(R.string.cine_state_error_title) }
    loading = false
  }

  LaunchedEffect(itemId, reloadTick) { load() }

  // 分集 / 子条目：Series 拉全部分集（后面按季分组），季 / 合集 / 播放列表拉这一层
  LaunchedEffect(item?.Id, item?.Type, server?.id) {
    val current = item ?: return@LaunchedEffect
    val currentServer = server ?: return@LaunchedEffect
    val id = current.Id ?: return@LaunchedEffect
    when (current.Type) {
      "Series" -> episodes = runCatching { viewModel.loadSeriesEpisodes(currentServer, id) }
        .getOrDefault(emptyList())

      "Season", "BoxSet", "Playlist" -> children = runCatching {
        viewModel.loadItems(
          server = currentServer,
          parentId = id,
          recursive = false,
          limit = 100,
        ).items
      }.getOrDefault(emptyList())
    }
  }

  // 相似推荐：与 Classic 同一套算法与开关（设置里关掉就连请求都不发）
  LaunchedEffect(item?.Id, server?.id, showRecommendations) {
    val current = item ?: return@LaunchedEffect
    val currentServer = server ?: return@LaunchedEffect
    if (showRecommendations) similarViewModel.loadSimilar(currentServer, current)
  }

  // 从播放器返回：进度是异步上报的，延迟 800ms 等落库再拉，否则进度条停在旧值
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, itemId) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && item != null) {
        scope.launch {
          delay(800)
          load()
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val mediaUi: CineMediaUi? = remember(server?.id, item, overrideTick) {
    val current = server
    val loaded = item
    if (current == null || loaded == null) {
      null
    } else {
      val ui = viewModel.toCineMedia(current, loaded)
      val override = favoriteOverrides[ui.id]
      if (override == null || override == ui.isFavorite) ui else ui.copy(isFavorite = override)
    }
  }

  val listState = rememberLazyListState()
  val backdropHeight: Dp = if (wide) {
    minOf(340.dp, (screenHeight * 0.42f).dp).coerceAtLeast(240.dp)
  } else {
    minOf(386.dp, (screenHeight * 0.45f).dp).coerceAtLeast(260.dp)
  }
  val collapse by remember {
    derivedStateOf {
      if (listState.firstVisibleItemIndex > 0) {
        1f
      } else {
        (listState.firstVisibleItemScrollOffset / 220f).coerceIn(0f, 1f)
      }
    }
  }

  fun toggleFavorite(target: EmbyItem, next: Boolean) {
    val current = server ?: return
    if (favoriteBusy) return
    favoriteBusy = true
    val id = target.Id.orEmpty()
    favoriteOverrides[id] = next
    overrideTick++
    scope.launch {
      val result = viewModel.toggleFavorite(current, target, next)
      favoriteBusy = false
      val confirmed = result.getOrNull()
      if (result.isSuccess && confirmed != null) {
        favoriteOverrides[id] = confirmed
      } else {
        favoriteOverrides.remove(id)
        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
      }
      overrideTick++
    }
  }

  /**
   * 下载按钮：已有任务时它是「暂停 / 继续」开关（与下载管理页同一套动作）。
   * 没有任务才走「去重入队」—— 与 Classic 详情页的行为逐条一致。
   */
  fun toggleDownload() {
    val current = item ?: return
    val currentServer = server ?: return
    val existing = currentDownload
    when (existing?.status) {
      EmbyDownloadStatus.RUNNING, EmbyDownloadStatus.QUEUED -> {
        downloadViewModel.pause(existing.itemId)
        Toast.makeText(context, context.getString(R.string.cine_download_paused_toast), Toast.LENGTH_SHORT).show()
      }

      EmbyDownloadStatus.PAUSED, EmbyDownloadStatus.FAILED -> {
        downloadViewModel.resume(existing.itemId)
        Toast.makeText(context, context.getString(R.string.cine_download_resumed_toast), Toast.LENGTH_SHORT).show()
      }

      EmbyDownloadStatus.COMPLETED ->
        Toast.makeText(context, context.getString(R.string.cine_download_done), Toast.LENGTH_SHORT).show()

      null -> {
        val message = when (downloadViewModel.enqueue(currentServer, current)) {
          EmbyEnqueueResult.STARTED -> R.string.cine_download_enqueued
          EmbyEnqueueResult.EXISTS -> R.string.cine_download_exists
          EmbyEnqueueResult.COMPLETED -> R.string.cine_download_done
          EmbyEnqueueResult.INVALID -> R.string.cine_download_invalid
        }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun toggleDislike() {
    val key = dislikeKey ?: return
    if (isDisliked) {
      dislikeRepository.unmark(key)
      Toast.makeText(context, context.getString(R.string.cine_dislike_unmarked), Toast.LENGTH_SHORT).show()
    } else {
      val target = item
      dislikeRepository.mark(
        key = key,
        title = target?.Name.orEmpty(),
        reason = null,
        // ImageTags 是 Map，不是对象 —— 写 .Primary 会被解析成别的成员
        imageUrl = target?.ImageTags?.get("Primary")?.let { tag ->
          server?.let { s -> embyRepository.imageUrl(s, itemId, tag = tag) }
        },
      )
      Toast.makeText(context, context.getString(R.string.cine_dislike_marked), Toast.LENGTH_SHORT).show()
    }
  }

  fun openExternalPlayer() {
    val current = item ?: return
    val currentServer = server ?: return
    viewModel.listExternalPlayers(currentServer, current)
      .onSuccess { list ->
        externalLastKey = viewModel.lastExternalPlayerKey()
        externalPlayers = list
      }
      .onFailure { e ->
        Toast.makeText(
          context,
          e.message ?: context.getString(R.string.cine_action_external_failed),
          Toast.LENGTH_LONG,
        ).show()
      }
  }

  /**
   * 集号条要定位到的那一集：优先「有进度且没看完」的最近一集，其次第一个未看的。
   *
   * 用 `LastPlayedDate` 排序而不是 `PlaybackPositionTicks` 大小 —— 后者跟「看了多久」
   * 成正比，会和「最近看的」脱钩（看过一半的第 3 集 vs 刚点开 10 秒的第 9 集）。
   */
  val activeEpisodeId = remember(episodes) {
    episodes
      .filter {
        (it.UserData?.PlaybackPositionTicks ?: 0L) > 0L && it.UserData?.Played != true
      }
      .maxByOrNull { it.UserData?.LastPlayedDate.orEmpty() }
      ?.Id
      ?: episodes.filter { it.UserData?.Played != true }
        .minByOrNull { it.IndexNumber ?: Int.MAX_VALUE }
        ?.Id
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    when {
      loading && item == null -> CineDetailSkeleton(
        backdropHeight = backdropHeight,
        modifier = Modifier.fillMaxSize(),
      )

      item == null -> CineStatePanel(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.cine_state_error_title),
        description = error,
        isError = true,
        primaryLabel = stringResource(R.string.cine_state_error_action),
        onPrimary = { reloadTick++ },
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding(),
      )

      else -> {
        val loaded = item!!
        val ui = mediaUi
        val cast = remember(loaded.People) {
          loaded.People.orEmpty().filter { it.Type == "Actor" && it.Id != null }.take(12)
        }
        val seasonGroups = remember(episodes) { groupEpisodesBySeason(episodes) }
        val castAvatarUrl: (EmbyPerson) -> String? = { person ->
          val current = server
          val personId = person.Id
          if (current == null || personId == null) {
            null
          } else {
            EmbyClient.imageUrl(current, personId, "Primary", person.PrimaryImageTag, 240)
          }
        }
        val episodeThumbUrl: (EmbyItem) -> String? = { episode ->
          val current = server
          if (current == null) {
            null
          } else {
            viewModel.imageUrl(current, episode, "Backdrop", 480)
              ?: viewModel.imageUrl(current, episode, "Primary", 480)
          }
        }

        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            // 详情页顶栏两态都是玻璃（图上悬浮钮 / 滚过 backdrop 后的玻璃条），
            // 模糊源就是这一列 —— 一定要挂在滚动容器上，挂在 item 上滚走就没底了
            .cineHazeSource(LocalCineHaze.current)
            .navigationBarsPadding(),
          contentPadding = PaddingValues(bottom = 32.dp),
        ) {
          item(key = "hero") {
            CineDetailHero(
              item = loaded,
              media = ui,
              backdropHeight = backdropHeight,
              wide = wide,
              cast = cast,
              castAvatarUrl = castAvatarUrl,
              isFavorite = ui?.isFavorite ?: false,
              isPlayed = ui?.isPlayed ?: (loaded.UserData?.Played == true),
              favoriteBusy = favoriteBusy,
              overviewExpanded = overviewExpanded,
              onPlay = {
                if (viewModel.isContainerOnly(loaded)) {
                  play.playFromStart(loaded)
                } else {
                  play.play(loaded, ui?.resumeSeconds ?: 0L)
                }
              },
              onToggleFavorite = { toggleFavorite(loaded, !(ui?.isFavorite ?: false)) },
              onTogglePlayed = {
                val current = server
                val id = loaded.Id
                if (current != null && id != null) {
                  scope.launch {
                    viewModel.setPlayed(current, id, loaded.UserData?.Played != true)
                    delay(250)
                    reloadTick++
                  }
                }
              },
              onToggleOverview = { overviewExpanded = !overviewExpanded },
              onOpenPerson = onPerson@{ person ->
                val personId = person.Id ?: return@onPerson
                backStack.add(
                  EmbyPersonScreen(
                    personId = personId,
                    personName = person.Name ?: "",
                    personImageTag = person.PrimaryImageTag,
                  ),
                )
              },
              onCast = {
                // 容器类（剧集 / 季 / 合集）本身的 Id 不是媒体流：投屏前先挑一集，
                // 否则 getStreamUrl 会拼出一个设备拉不动的地址
                val castTarget = if (viewModel.isContainerOnly(loaded)) {
                  episodes.firstOrNull { it.Id != null }
                } else {
                  loaded
                }
                val castId = castTarget?.Id
                val castServer = server
                if (castTarget == null || castId == null || castServer == null) {
                  Toast.makeText(
                    context,
                    context.getString(R.string.cine_cast_open_failed),
                    Toast.LENGTH_SHORT,
                  ).show()
                } else {
                  dlnaManager.pendingPayload = CastPayload(
                    uri = Uri.parse(viewModel.getStreamUrl(castServer, castId, static = true)),
                    title = viewModel.displayTitle(castTarget),
                  )
                  castSheetShown = true
                }
              },
            )
          }

          if (seasonGroups.isNotEmpty()) {
            item(key = "episodes") {
              CineEpisodeSection(
                groups = seasonGroups,
                selectedIndex = seasonIndex.coerceIn(0, seasonGroups.lastIndex),
                onSelectSeason = { seasonIndex = it },
                currentEpisodeId = activeEpisodeId,
                thumbUrl = episodeThumbUrl,
                descending = descending,
                onToggleSort = { descending = !descending },
                onPlayEpisode = { episode ->
                  play.play(
                    episode,
                    EmbyTicks.ticksToSeconds(episode.UserData?.PlaybackPositionTicks ?: 0L),
                  )
                },
                onToggleEpisodePlayed = { episode ->
                  val current = server
                  val epId = episode.Id
                  if (current != null && epId != null) {
                    scope.launch {
                      viewModel.setPlayed(current, epId, episode.UserData?.Played != true)
                      delay(200)
                      val seriesId = loaded.Id
                      if (seriesId != null) {
                        episodes = runCatching { viewModel.loadSeriesEpisodes(current, seriesId) }
                          .getOrDefault(episodes)
                      }
                    }
                  }
                },
              )
            }
          }

          if (children.isNotEmpty()) {
            item(key = "children") {
              // remember 必须留在 item 的 composable 作用域里：
              // LazyListScope 的 if 分支不是 composable 上下文
              val childUi = remember(server?.id, children) {
                val current = server ?: return@remember emptyList<CineMediaUi>()
                children.map { viewModel.toCineMedia(current, it) }
              }
              Column {
                Spacer(Modifier.height(space.sectionGap))
                CineSectionHeader(title = stringResource(R.string.cine_detail_contents))
                CinePosterRow(
                  items = childUi,
                  cardWidth = space.posterWidth(wide),
                  wide = wide,
                  onClick = { media ->
                    if (viewModel.isContainerOnly(media.item)) {
                      backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
                    } else {
                      play.play(media.item, media.resumeSeconds)
                    }
                  },
                )
              }
            }
          }

          if (showRecommendations && similar.isNotEmpty()) {
            item(key = "similar") {
              val similarUi = remember(server?.id, similar) {
                val current = server ?: return@remember emptyList<CineMediaUi>()
                similar.take(12).map { viewModel.toCineMedia(current, it.item) }
              }
              Column {
                Spacer(Modifier.height(space.sectionGap))
                CineSectionHeader(title = stringResource(R.string.cine_detail_similar))
                CinePosterRow(
                  items = similarUi,
                  cardWidth = space.posterWidth(wide),
                  wide = wide,
                  onClick = { media ->
                    backStack.add(EmbyDetailScreen(itemId = media.id, title = media.title))
                  },
                )
              }
            }
          }
        }
      }
    }

    // 顶栏两态：压在剧照上 = 浮动圆形按钮；滚过 Backdrop = 玻璃条
    DetailTopBar(
      collapse = if (item == null) 1f else collapse,
      title = item?.Name ?: title,
      onBack = { backStack.removeLastOrNull() },
      onMore = mediaUi?.let { media -> { actionSheet.show(media) } },
      modifier = Modifier.align(Alignment.TopCenter),
    )
  }

  // ── 完整功能菜单（刮削 / 刷新 / 编辑元数据 / 编辑图片 / 删除 / 下载 / 不喜欢 / 外部播放器）──
  CineActionSheetHost(
    state = actionSheet,
    server = server,
    viewModel = viewModel,
    playController = play,
    // fromDetail = true 时「详情」项不画，所以这个回调不会被调用
    onOpenDetail = {},
    onChanged = { reloadTick++ },
    onDeleted = { backStack.removeLastOrNull() },
    fromDetail = true,
    extras = CineActionExtras(
      downloadLabel = downloadStatusLabel,
      downloadActive = downloadActive,
      onDownload = ::toggleDownload,
      showDislike = showDislikeInMenu && dislikeKey != null,
      disliked = isDisliked,
      onToggleDislike = ::toggleDislike,
      onPlayExternal = ::openExternalPlayer,
    ),
  )

  // 外部播放器选择：先枚举本机能吃这条流的播放器，再用我们自己的列表让用户挑
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
              Toast.makeText(
                context,
                e.message ?: context.getString(R.string.cine_action_external_failed),
                Toast.LENGTH_LONG,
              ).show()
            }
        }
      },
      onSystemChooser = {
        externalPlayers = null
        if (playerTarget != null && serverTarget != null) {
          viewModel.playWithExternalPlayerChooser(serverTarget, playerTarget)
            .onFailure { e ->
              Toast.makeText(
                context,
                e.message ?: context.getString(R.string.cine_action_external_failed),
                Toast.LENGTH_LONG,
              ).show()
            }
        }
      },
      onDismiss = { externalPlayers = null },
    )
  }

  // 投屏抽屉：玻璃质感版，共用 DlnaCastManager（Classic 的 DlnaSheet 一行不改）
  if (castSheetShown) {
    CineCastSheet(onDismissRequest = { castSheetShown = false })
  }
}

/**
 * 顶栏两态：压在剧照上是浮动圆钮，滚过之后是玻璃条（与首页同一套语言）。
 *
 * @param onMore 右上角「更多」。传 null 时不画（数据还没到时没有可操作的对象）
 */
@Composable
private fun DetailTopBar(
  collapse: Float,
  title: String,
  onBack: () -> Unit,
  onMore: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val c = collapse.coerceIn(0f, 1f)

  Box(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = c }
        // 真模糊：滚过 backdrop 之后这条栏压在剧照 / 海报上，平涂色会显脏
        .cineGlass(
          shape = RectangleShape,
          elevation = 0.dp,
          blurRadius = 20.dp,
          border = false,
        )
        .statusBarsPadding()
        .height(space.topBarHeight)
        .padding(start = 4.dp, end = space.screenH),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CineIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = null,
        tint = colors.onSurface,
        onClick = onBack,
      )
      Text(
        text = title,
        style = type.pageBarTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .padding(start = 2.dp),
      )
      if (onMore != null) {
        CineIconButton(
          icon = Icons.Rounded.MoreHoriz,
          contentDescription = stringResource(R.string.cine_detail_more),
          tint = colors.onSurface,
          onClick = onMore,
        )
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = 1f - c }
        .statusBarsPadding()
        .padding(start = space.screenH, end = space.screenH, top = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CineFloatingBackButton(onClick = onBack)
      if (onMore != null) {
        CineGlassRoundButton(
          icon = Icons.Rounded.MoreHoriz,
          contentDescription = stringResource(R.string.cine_detail_more),
          onClick = onMore,
        )
      }
    }
  }
}

/**
 * 压在剧照上的玻璃圆钮（与 [CineFloatingBackButton] 同一套材质）。
 *
 * 不直接复用 `CineFloatingBackButton`：那个组件内部写死了返回箭头，
 * 加个 icon 参数会让「返回」这个语义被稀释 —— 返回键在别的页也在用。
 */
@Composable
private fun CineGlassRoundButton(
  icon: ImageVector,
  contentDescription: String?,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .cineGlass(
        shape = CircleShape,
        elevation = 0.dp,
        blurRadius = 12.dp,
        flatAlpha = 0.46f,
        tintOverride = Color.Black.copy(alpha = 0.42f),
        borderColor = Color.White.copy(alpha = 0.22f),
      )
      .pressDownScale()
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = Color.White,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** 详情骨架：剧照 + 信息块轮廓，与真实版面同高，数据落地不跳位 */
@Composable
private fun CineDetailSkeleton(
  backdropHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(backdropHeight)
        .background(colors.skeleton),
    )
    Column(modifier = Modifier.padding(horizontal = CineTheme.space.screenH, vertical = 20.dp)) {
      Box(
        modifier = Modifier
          .fillMaxWidth(0.6f)
          .height(26.dp)
          .background(colors.skeleton),
      )
      Spacer(Modifier.height(10.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth(0.4f)
          .height(13.dp)
          .background(colors.skeleton),
      )
      Spacer(Modifier.height(22.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .background(colors.skeleton),
      )
    }
  }
}
