package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaStream
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
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

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        isLoading && item == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

        error != null && item == null -> EmbyEmptyState(
          message = error ?: "加载失败",
          buttonText = "重试",
          onAction = { scope.launch { load() } },
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val current = item ?: return@Box
          val currentServer = server ?: return@Box

          DetailBody(
            item = current,
            backdropUrl = viewModel.imageUrl(currentServer, current, "Backdrop", 1280),
            posterUrl = viewModel.imageUrl(currentServer, current, "Primary", 600),
            onPlay = { resume -> scope.launch { viewModel.play(currentServer, current, resume) } },
            onToggleFavorite = {
              val wasFavorite = current.UserData?.IsFavorite == true
              // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
              item = current.copy(
                UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = !wasFavorite),
              )
              scope.launch {
                val nowFavorite = runCatching { viewModel.toggleFavorite(currentServer, current) }
                  .getOrNull()
                item = current.copy(
                  UserData = (current.UserData ?: EmbyUserData())
                    .copy(IsFavorite = nowFavorite ?: wasFavorite),
                )
              }
            },
            onTogglePlayed = { played ->
              viewModel.markPlayed(currentServer, current.Id ?: return@DetailBody, played)
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
            },
            onBack = { backStack.removeLastOrNull() },
            onDelete = { showDeleteConfirm = true },
            onDownload = {
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
            },
            downloadLabel = downloadTasks.firstOrNull { it.itemId == current.Id }?.let { task ->
              when (task.status) {
                EmbyDownloadStatus.COMPLETED -> "已下载"
                EmbyDownloadStatus.PAUSED -> "已暂停"
                EmbyDownloadStatus.FAILED -> "下载失败"
                EmbyDownloadStatus.QUEUED -> "排队中"
                EmbyDownloadStatus.RUNNING ->
                  "下载中 ${((task.progressFraction ?: 0f) * 100).toInt()}%"
              }
            },
            moreMenuExpanded = showMoreMenu,
            onMoreMenuChange = { showMoreMenu = it },
          )
        }
      }
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
  backdropUrl: String?,
  posterUrl: String?,
  onPlay: (resumeSeconds: Long) -> Unit,
  onToggleFavorite: () -> Unit,
  onTogglePlayed: (played: Boolean) -> Unit,
  onBack: () -> Unit,
  onDelete: () -> Unit,
  onDownload: () -> Unit,
  downloadLabel: String?,
  moreMenuExpanded: Boolean,
  onMoreMenuChange: (Boolean) -> Unit,
) {
  val listState = rememberLazyListState()
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 96.dp),
    ) {
      // ── 沉浸式剧照 + 叠加标题 ──
      item {
        BackdropHeader(item = item, backdropUrl = backdropUrl, posterUrl = posterUrl)
      }

      // ── 播放 / 删除 / 下载 ──
      item {
        PlaySection(
          item = item,
          onPlay = onPlay,
          onDelete = onDelete,
          onDownload = onDownload,
          downloadLabel = downloadLabel,
        )
      }

      // ── 类型标签 ──
      if (item.Genres.isNotEmpty()) {
        item {
          Row(
            modifier = Modifier
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            item.Genres.forEach { genre ->
              SuggestionChip(
                onClick = {},
                label = { Text(genre) },
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
            Text("演职员", style = MaterialTheme.typography.titleMedium)
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
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(28.dp),
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                      Text(
                        text = person.Name?.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                      )
                    }
                  }
                  Text(
                    text = person.Name ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
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

      // ── 媒体信息（含完整视频 / 音频编码信息）──
      item {
        MediaInfoSection(item = item)
      }
    }

    // ── 顶部浮层操作栏（透明背景，白色图标，浮在剧照之上）──
    TopAppBar(
      title = {},
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = Color.White,
          )
        }
      },
      actions = {
        IconButton(onClick = { onTogglePlayed(!isPlayed) }) {
          Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = if (isPlayed) "标记为未播放" else "标记为已播放",
            tint = if (isPlayed) Color.White else Color.White.copy(alpha = 0.75f),
          )
        }
        IconButton(onClick = onToggleFavorite) {
          // 与播放页共用同一套动效：弹跳 + 星光 + 红心渐变（状态驱动，故上面做了乐观更新）
          FavoriteHeartIcon(
            isFavorite = isFavorite,
            isToggling = false,
            iconSize = 24.dp,
            idleColor = Color.White,
          )
        }
        Box {
          IconButton(onClick = { onMoreMenuChange(true) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
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
            DropdownMenuItem(
              text = { Text("删除媒体") },
              onClick = {
                onMoreMenuChange(false)
                onDelete()
              },
            )
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
      ),
    )
  }
}

/**
 * 沉浸式剧照头部。
 *
 * - 有横幅（Backdrop）时：横幅铺满整个头图区。
 * - 只有海报（Primary，2:3 竖版）时：为了**铺满整个容器**改用 [ContentScale.Crop]
 *   （原来用 Fit 会上下留出背景色条，看起来「图没铺满」）；Crop 会等比放大后居中裁切，
 *   不会把脸拉变形。
 * - 标题放大加粗并加阴影，保证在任何底图上都清晰可读。
 */
@Composable
private fun BackdropHeader(
  item: EmbyItem,
  backdropUrl: String?,
  posterUrl: String?,
) {
  val hasBackdrop = item.BackdropImageTags.isNotEmpty()
  val headerImage: String? = if (hasBackdrop) backdropUrl else posterUrl

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(if (hasBackdrop) BACKDROP_HEIGHT else POSTER_HEADER_HEIGHT),
  ) {
    // 底图：无论横幅还是海报都铺满容器（Crop = 等比放大 + 居中裁切，无变形）
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      if (headerImage != null) {
        EmbyImage(
          url = headerImage,
          // 横幅加载失败（服务器没有该图）时回退到海报
          fallbackUrl = if (hasBackdrop) posterUrl else null,
          contentDescription = item.Name,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          // 详情页头部要清晰，给一个较高的解码上限（同时避免原图过大撑爆内存）
          maxWidth = 1080,
        )
      }
    }

    // 顶部压暗 + 底部融入页面背景，保证白色按钮与标题都可读
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Black.copy(alpha = 0.45f),
              Color.Transparent,
              Color.Transparent,
              MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
              MaterialTheme.colorScheme.surface,
            ),
          ),
        ),
    )

    Row(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      // 头图区已经展示了横幅/海报，这里不再重复叠一张小海报
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.Name ?: "",
          style = MaterialTheme.typography.headlineMedium.copy(
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
        item.OriginalTitle?.takeIf { it.isNotBlank() && it != item.Name }?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium.copy(
              shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
            ),
            color = Color.White.copy(alpha = 0.85f),
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = metaSummary(item),
          style = MaterialTheme.typography.bodySmall.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f),
          ),
          color = Color.White.copy(alpha = 0.9f),
        )
      }
    }
  }
}

/** 剧照上的一行摘要：年份 · 时长 · 评分 · 官方分级 */
private fun metaSummary(item: EmbyItem): String {
  val parts = mutableListOf<String>()
  item.ProductionYear?.let { parts.add(it.toString()) }
  formatDuration(item.RunTimeTicks).takeIf { it.isNotBlank() }?.let { parts.add(it) }
  item.CommunityRating?.let { parts.add("★ %.1f".format(it)) }
  item.OfficialRating?.let { parts.add(it) }
  return parts.joinToString(" · ")
}

/**
 * 播放区：主按钮「播放 / 继续播放 · mm:ss」+ 右侧红色垃圾桶删除按钮。
 *
 * 进度说明改成**始终显示**（原来只在「可续播」时才出现，而且剩余时长不足 1 分钟会显示成空括号）：
 * `已观看 24% · 剩余 1小时12分 · 总时长 1小时35分`，
 * 剩余/总时长用 [formatDurationFull] 格式化，秒级也一定有位数字。
 * 只有真看过的片子才额外显示「从头播放 / 继续上次」两个按钮。
 */
@Composable
private fun PlaySection(
  item: EmbyItem,
  onPlay: (resumeSeconds: Long) -> Unit,
  onDelete: () -> Unit,
  onDownload: () -> Unit,
  downloadLabel: String?,
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
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = { onPlay(if (hasResume) resumeSeconds else 0) },
        modifier = Modifier
          .weight(1f)
          .height(56.dp),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (hasResume) "继续播放 · ${formatClock(resumeSeconds)}" else "播放")
      }

      // 删除：红底红桶，就放在播放按钮旁边；点击后仍会弹确认框（由外层控制）
      IconButton(
        onClick = onDelete,
        modifier =
          Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer),
      ) {
        Icon(
          imageVector = Icons.Default.Delete,
          contentDescription = "删除媒体",
          tint = MaterialTheme.colorScheme.error,
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

    // 次要操作：从头播放 / 继续上次 / 下载。
    // 三个按钮在窄屏上会挤，所以整行可横向滚动，避免被裁掉。
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (hasResume) {
        FilledTonalButton(onClick = { onPlay(0) }) {
          Text("从头播放")
        }
        OutlinedButton(onClick = { onPlay(resumeSeconds) }) {
          Text("继续上次")
        }
      }
      if (isDownloadable(item)) {
        OutlinedButton(onClick = onDownload, enabled = downloadLabel == null) {
          Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(downloadLabel ?: "下载")
        }
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
    Text("媒体信息", style = MaterialTheme.typography.titleMedium)
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

private val BACKDROP_HEIGHT = 280.dp

/** 没有横幅、只展示海报时头图区的高度（比横幅更高，容纳 2:3 竖版海报） */
private val POSTER_HEADER_HEIGHT = 340.dp
