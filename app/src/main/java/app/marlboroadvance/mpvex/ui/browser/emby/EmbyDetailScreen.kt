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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyMediaSource
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 媒体详情页。
 *
 * 布局（沉浸式）：
 * 1. 顶部透明栏浮在剧照之上：左侧返回，右侧「标记为已播放 / 收藏 / 更多」
 * 2. 剧照（Backdrop）从屏幕顶端一直延伸到按钮下方，标题叠在剧照上
 * 3. 剧照下方依次是播放按钮与元数据信息
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

      // ── 播放按钮 ──
      item {
        PlaySection(item = item, onPlay = onPlay)
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

      // ── 媒体信息 ──
      item.MediaSources?.takeIf { it.isNotEmpty() }?.let { sources ->
        item {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("媒体信息", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            sources.forEach { source -> MediaSourceInfo(source) }
          }
        }
      }

      item.Path?.takeIf { it.isNotBlank() }?.let { path ->
        item {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("文件路径", style = MaterialTheme.typography.titleSmall)
            Text(
              text = path,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
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
 * - 有横幅（Backdrop）时：横幅铺满整个头图区，左下角叠加小海报卡。
 * - 只有海报（Primary，2:3 竖版）时：**完整展示整张海报**（ContentScale.Fit），
 *   而不是把它裁成一条横切片——否则就会出现「图很小 / 不完整」的问题。
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
    // 底图（有横幅用横幅，否则完整展示海报；都没有时用纯色占位）
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
          contentScale = if (hasBackdrop) ContentScale.Crop else ContentScale.Fit,
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

@Composable
private fun PlaySection(
  item: EmbyItem,
  onPlay: (resumeSeconds: Long) -> Unit,
) {
  val positionTicks = item.UserData?.PlaybackPositionTicks ?: 0L
  val runTimeTicks = item.RunTimeTicks ?: 0L
  val resumeSeconds = EmbyTicks.ticksToSeconds(positionTicks)
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
    Button(
      onClick = { onPlay(if (hasResume) resumeSeconds else 0) },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Default.PlayArrow, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text(if (hasResume) "继续播放 · ${formatClock(resumeSeconds)}" else "播放")
    }

    if (hasResume) {
      Text(
        text = "已观看 $progressPercent%（剩余 ${formatDuration(runTimeTicks - positionTicks)}）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onPlay(0) }) {
          Text("从头播放")
        }
        OutlinedButton(onClick = { onPlay(resumeSeconds) }) {
          Text("继续上次")
        }
      }
    }
  }
}

@Composable
private fun MediaSourceInfo(source: EmbyMediaSource) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
  ) {
    Text(
      text = source.Name ?: "媒体源",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
    )
    val video = source.MediaStreams?.firstOrNull { it.Type == "Video" }
    val audio = source.MediaStreams?.firstOrNull { it.Type == "Audio" }
    val subtitleCount = source.MediaStreams?.count { it.Type == "Subtitle" } ?: 0

    val details = mutableListOf<String>()
    source.Container?.let { details.add(it.uppercase()) }
    video?.let {
      if (it.Width != null && it.Height != null) details.add("${it.Width}×${it.Height}")
      it.Codec?.let { c -> details.add(c.uppercase()) }
    }
    audio?.let {
      it.Codec?.let { c -> details.add("音频 ${c.uppercase()}") }
      it.Language?.let { l -> details.add(l) }
    }
    if (subtitleCount > 0) details.add("字幕 $subtitleCount")
    source.Size?.let { details.add(formatFileSize(it)) }

    if (details.isNotEmpty()) {
      Text(
        text = details.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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

private fun formatFileSize(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  return when {
    mb >= 1024 -> "%.2f GB".format(mb / 1024)
    else -> "%.0f MB".format(mb)
  }
}

private val BACKDROP_HEIGHT = 280.dp

/** 没有横幅、只展示完整海报时头图区的高度（比横幅更高，容纳 2:3 竖版海报） */
private val POSTER_HEADER_HEIGHT = 340.dp
