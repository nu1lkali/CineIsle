package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Video.Thumbnails
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistItem(
  val uri: Uri,
  val title: String,
  val index: Int,
  val isPlaying: Boolean,
  val progressPercent: Float = 0f, // 0-100, progress of video watched
  val isWatched: Boolean = false,  // True if video is fully watched (100%)
  val path: String = "", // Video path for thumbnail loading
  val duration: String = "", // Duration in formatted string (e.g., "10:30")
  val resolution: String = "", // Resolution (e.g., "1920x1080")
  /**
   * 网络播放源（Emby 等）的缩略图直链（背景图/海报）。
   * 网络流不走 MediaStore 缩略图（拿不到），有它就按 URL 加载。
   */
  val thumbnailUrl: String = "",
) {
  /**
   * 网络播放流（Emby / strm 直链等）。
   *
   * 这类条目的时长 / 分辨率**永远不会被探测填充**（见 PlayerViewModel.getVideoMetadata
   * 对网络流的跳过逻辑 —— strm 的真实媒体在云存储 CDN 上，逐个探测会触发风控），
   * UI 不能为它们渲染「加载中」占位，否则就是永远在加载的样子。
   */
  val isNetworkStream: Boolean
    get() = uri.scheme == "http" || uri.scheme == "https"
}

/**
 * LRU (Least Recently Used) cache for Bitmap thumbnails with a maximum size limit.
 * This prevents memory issues when dealing with large playlists (100+ videos).
 */
class LRUBitmapCache(private val maxSize: Int) {
  private val cache = LinkedHashMap<String, Bitmap?>(maxSize + 1, 1f, true)

  operator fun get(key: String): Bitmap? = synchronized(this) { cache[key] }

  operator fun set(key: String, value: Bitmap?) = synchronized(this) {
    cache[key] = value
    if (cache.size > maxSize) {
      // Remove the least recently used item
      cache.remove(cache.keys.firstOrNull())
    }
  }

  fun containsKey(key: String): Boolean = synchronized(this) { cache.containsKey(key) }

  fun clear() = synchronized(this) { cache.clear() }
}

/**
 * Loads a thumbnail from MediaStore cache (much faster than generating new thumbnails).
 * Uses the modern loadThumbnail API on Android Q+ for better performance.
 * Falls back to null if no cached thumbnail exists (in which case a placeholder will be shown).
 */
private suspend fun loadMediaStoreThumbnail(context: Context, uri: Uri): Bitmap? {
  return withContext(Dispatchers.IO) {
    try {
      when (uri.scheme) {
        // For content:// URIs, we need to find the video ID first
        "content" -> {
          val videoId = extractVideoId(uri, context)
          if (videoId != null) {
            // Use modern API on Android Q+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
              val contentUri = android.content.ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoId
              )
              context.contentResolver.loadThumbnail(
                contentUri,
                android.util.Size(512, 512),
                null
              )
            } else {
              @Suppress("DEPRECATION")
              Thumbnails.getThumbnail(
                context.contentResolver,
                videoId,
                Thumbnails.MINI_KIND,
                null
              )
            }
          } else {
            null
          }
        }
        // For file:// URIs, try to find the corresponding MediaStore entry
        "file" -> {
          val filePath = uri.path ?: return@withContext null
          val projection = arrayOf(MediaStore.Video.Media._ID)
          val selection = "${MediaStore.Video.Media.DATA} = ?"
          val selectionArgs = arrayOf(filePath)

          context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
              val videoId = cursor.getLong(idColumn)
              
              // Use modern API on Android Q+
              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentUri = android.content.ContentUris.withAppendedId(
                  MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                  videoId
                )
                context.contentResolver.loadThumbnail(
                  contentUri,
                  android.util.Size(512, 512),
                  null
                )
              } else {
                @Suppress("DEPRECATION")
                Thumbnails.getThumbnail(
                  context.contentResolver,
                  videoId,
                  Thumbnails.MINI_KIND,
                  null
                )
              }
            } else {
              null
            }
          }
        }
        else -> null
      }
    } catch (e: Exception) {
      // Fallback with placeholder if thumbnail loading fails
      android.util.Log.w("PlaylistSheet", "Failed to load MediaStore thumbnail for $uri", e)
      null
    }
  }
}

/**
 * 加载网络播放源（Emby 等）的缩略图。
 *
 * 走 [EmbyImageLoader]（内存 LRU + 磁盘缓存 + 服务端缩放失败退原图的兜底），
 * 这里再补一层「背景图 404 → 退海报」的回退；都失败返回 null，由调用方回退到占位图标。
 */
private suspend fun loadUrlThumbnail(url: String): Bitmap? {
  app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
    .load(url, maxWidth = 512)
    ?.let { return it }
  val primary = url.replace("/Images/Backdrop", "/Images/Primary")
  return if (primary != url) {
    app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader.load(primary, maxWidth = 512)
  } else {
    null
  }
}

/**
 * Extracts the video ID from a content:// URI.
 */
private fun extractVideoId(uri: Uri, context: Context): Long? {
  return try {
    val path = uri.path ?: return null
    // Extract ID from path like /external/video/media/123
    val idString = path.substringAfterLast('/').toLongOrNull() ?: return null

    // Verify this ID exists in MediaStore
    val projection = arrayOf(MediaStore.Video.Media._ID)
    val selection = "${MediaStore.Video.Media._ID} = ?"
    val selectionArgs = arrayOf(idString.toString())

    context.contentResolver.query(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
      projection,
      selection,
      selectionArgs,
      null
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        cursor.getLong(idColumn)
      } else {
        null
      }
    }
  } catch (e: Exception) {
    null
  }
}

@Composable
fun PlaylistSheet(
  playlist: ImmutableList<PlaylistItem>,
  onDismissRequest: () -> Unit,
  onItemClick: (PlaylistItem) -> Unit,
  /** 拖动排序回调：(旧下标, 新下标)。只在列表模式生效。 */
  onMoveItem: (Int, Int) -> Unit = { _, _ -> },
  /** 单条移除回调；传 null 表示不提供移除入口（例如只读队列 / M3U 列表）。 */
  onRemoveItem: ((Int) -> Unit)? = null,
  totalCount: Int = playlist.size,
  isM3UPlaylist: Boolean = false,
  playerPreferences: app.marlboroadvance.mpvex.preferences.PlayerPreferences,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current

  val accentColor = MaterialTheme.colorScheme.primary

  // Check portrait mode
  val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

  // Portrait mode => list mode
  val isListModePreference by playerPreferences.playlistViewMode.collectAsState()
  var isListMode by remember { mutableStateOf(if (isPortrait) true else isListModePreference) }

  LaunchedEffect(isPortrait) {
    if (isPortrait && !isListMode) {
      isListMode = true
    }
  }

  // Update preference when view mode changes (only in landscape)
  LaunchedEffect(isListMode) {
    if (!isPortrait && isListMode != isListModePreference) {
      playerPreferences.playlistViewMode.set(isListMode)
    }
  }

  // Thumbnail cache with LRU eviction - limited size to prevent memory issues with large playlists
  val thumbnailCache by remember {
    mutableStateOf(LRUBitmapCache(maxSize = 50))
  }

  // Scroll state for the playlist
  val lazyListState = rememberLazyListState()

  // Find the currently playing item index - tracks changes in playlist items
  val playingItemIndex by remember {
    derivedStateOf {
      playlist.indexOfFirst { it.isPlaying }
    }
  }

  // Scroll to the currently playing item when the playing item changes or when sheet opens
  LaunchedEffect(playingItemIndex) {
    if (playingItemIndex >= 0) {
      lazyListState.animateScrollToItem(playingItemIndex)
    }
  }

  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val sheetWidth = if (isListMode) {
    if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      640.dp
    } else {
      420.dp
    }
  } else {
    screenWidth * 0.85f
  }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = Modifier.fillMaxWidth(),
    customMaxWidth = sheetWidth,
    customMaxHeight = if (isPortrait) LocalConfiguration.current.screenHeightDp.dp * 0.5f else null,
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = Color.Transparent,
      shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
      ),
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = modifier.padding(
          vertical = MaterialTheme.spacing.smaller,
          horizontal = if (!isListMode) MaterialTheme.spacing.medium else 0.dp
        )
      ) {
        // Header showing current playlist info with toggle button
        val currentItem = playlist.find { it.isPlaying }
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = if (isListMode) MaterialTheme.spacing.medium else 0.dp,
              vertical = MaterialTheme.spacing.small,
            ),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
            modifier = Modifier.weight(1f)
          ) {
            if (currentItem != null) {
              Text(
                text = stringResource(R.string.i18n_now_playing),
                style = MaterialTheme.typography.titleSmall.copy(
                  fontWeight = FontWeight.Bold,
                  color = accentColor,
                ),
              )
              Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              text = stringResource(R.string.i18n_playlist_items, totalCount),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // Toggle button for list/grid view (only in landscape)
          if (!isPortrait) {
            IconButton(
              onClick = { isListMode = !isListMode }
            ) {
              Icon(
                imageVector = if (isListMode) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                contentDescription = stringResource(
                  if (isListMode) R.string.i18n_switch_to_list else R.string.i18n_switch_to_grid,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        // Conditional rendering based on view mode
        if (isListMode) {
          // 拖动排序只作用于列表模式：横向网格里「上下拖动」没有直观含义
          val reorder = rememberDragReorder(lazyListState, onMoveItem)

          // 松手后等真实数据换位落定再撤悬浮层。
          // 数据层的换位是异步的（Activity → ViewModel → 再回到 UI 要几帧），立刻撤掉会
          // 「先弹回原位、再跳到新位置」连闪两下；挂着不动则一次到位。
          LaunchedEffect(reorder.released, playlist) {
            if (!reorder.released) return@LaunchedEffect
            val arrived = playlist.getOrNull(reorder.targetPos)?.uri == reorder.liftedItem?.uri
            // 兜底：数据一直没落位也不能让悬浮层永久挂着
            if (!arrived) {
              delay(900)
              if (!reorder.released) return@LaunchedEffect
            }
            reorder.reset()
          }

          Box(
            // ⚠️ 拖拽手势必须挂在这个**不会随列表滚动被销毁**的外层 Box 上。
            // 挂到行内把手上时，行一旦滚出视口，手势协程就随之被取消 ——
            // 这正是「往队列后面拖、拖到一半突然断掉/那条没了」的根因。
            modifier =
              Modifier
                .fillMaxWidth()
                .pointerInput(reorder) {
                  awaitEachGesture {
                    // Initial 阶段拿 down：父层先于列表本身看到事件，才能抢下这次手势
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 只有从左侧把手带按下才算拖拽；从列表中间按下原样交给列表滚动
                    val hit = reorder.hitTestHandle(down.position) ?: return@awaitEachGesture
                    down.consume()
                    var started = false
                    var slop = 0f
                    try {
                      while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                          if (started) reorder.onDrop()
                          break
                        }
                        val dy = change.position.y - change.previousPosition.y
                        if (!started) {
                          slop += dy
                          // 走满触摸斜率才算「真的在拖」：单纯点一下把手不该把它拎起来
                          if (abs(slop) < viewConfiguration.touchSlop) {
                            change.consume()
                            continue
                          }
                          val item = playlist.getOrNull(hit) ?: break
                          reorder.onDragStart(change.position, hit, item)
                          started = true
                        }
                        // 直接把绝对坐标喂进去：手指在哪就是哪，不做任何累加补偿
                        reorder.onDrag(change.position.y)
                        change.consume()
                      }
                    } finally {
                      // 手势被系统取消时也要收尾，否则会一直卡在「拖着」的状态里
                      if (reorder.isDragging && !reorder.released) reorder.onCancel()
                    }
                  }
                },
          ) {
            LazyColumn(
              state = lazyListState,
              modifier = Modifier.fillMaxWidth(),
            ) {
              items(playlist) { item ->
                val lifted = reorder.fromIndex == item.index
                // 让位位移：目标位置的空位由其它行整体平移「一行高」让出来，动画平滑过渡
                val shift by animateFloatAsState(
                  targetValue = reorder.shiftFor(item.index),
                  animationSpec = tween(durationMillis = 160),
                  label = "queueItemShift",
                )
                PlaylistTrackListItem(
                  item = item,
                  context = context,
                  thumbnailCache = thumbnailCache,
                  onClick = { onItemClick(item) },
                  // 被拎起来那一条的缩略图交给悬浮层去画，原地只留高度占位
                  skipThumbnail = isM3UPlaylist || lifted,
                  accentColor = accentColor,
                  modifier =
                    Modifier.graphicsLayer {
                      translationY = shift
                      // 被拎起来的那条不在这里画（悬浮层才是真身），只留出槽位
                      if (lifted) alpha = 0f
                    },
                  dragHandle = { PlaylistDragHandle() },
                  // 队列只剩一条时不给移除入口 —— 清空队列后播放页就没有可播项了
                  onRemove = onRemoveItem?.takeIf { playlist.size > 1 }?.let { cb -> { cb(item.index) } },
                )
              }
            }

            // ── 悬浮层：被拎起来的那一条 ──
            //
            // 画在 LazyColumn **外面**：列表内容会被 LazyColumn 裁掉，画在里面的话一旦被拖到
            // 视口边缘整条就没了（用户反馈的「跳没了」）。画在外面则永远完整，而且它的位置
            // 只由手指决定 —— 这才是真正的「粘在手指下」。
            val liftedItem = reorder.liftedItem
            if (liftedItem != null) {
              Box(
                modifier =
                  Modifier
                    .offset { IntOffset(0, reorder.overlayTopPx().roundToInt()) }
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(12.dp)),
              ) {
                PlaylistTrackListItem(
                  item = liftedItem,
                  context = context,
                  thumbnailCache = thumbnailCache,
                  onClick = {},
                  skipThumbnail = isM3UPlaylist,
                  accentColor = accentColor,
                  dragHandle = { PlaylistDragHandle() },
                  onRemove = null,
                )
              }
            }
          }
        } else {
          // Horizontal grid mode
          LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(
              horizontal = if (isListMode) MaterialTheme.spacing.medium else 0.dp,
              vertical = MaterialTheme.spacing.small
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)
          ) {
            items(playlist) { item ->
              PlaylistTrackGridItem(
                item = item,
                context = context,
                thumbnailCache = thumbnailCache,
                onClick = {
                  onItemClick(item)
                },
                skipThumbnail = isM3UPlaylist,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 播放队列的拖拽排序状态。
 *
 * ## 为什么推翻了前两版
 *
 * 前两版都栽在同一处：**手感依赖「读到的列表布局是不是这一帧的」**。
 * 自动滚动时列表一直在动、被拖行的位置每帧都在变，而 layoutInfo 要下一帧才更新；
 * 再叠上「换位」是异步落到数据层的（Activity → ViewModel → 再回到 UI 已是几帧之后），
 * 「滚了多少 / 换了多少行 / 手指在哪」三者越拖越对不上 —— 用户看到的就是
 * 「不跟手、跳、拖到底那条直接没了」。
 *
 * 这一版把三件事**彻底解耦**，谁都不再依赖谁：
 *
 * 1. **手指在哪**只由手指自己说了算（[pointerY] 直接取事件里的绝对坐标，不做任何累加补偿）。
 *    自动滚动时列表在下面滚、手指不动 —— 这正是「浮起那条钉在手指下」的原因。
 * 2. **目标位置**由「手指压过原始布局的哪几个槽位」直接数出来（[computeTarget]）。
 *    拖动期间**从不改动渲染顺序**，所以 layoutInfo 始终稳定，不存在换位后位置算错一帧的问题。
 * 3. **让位**用 graphicsLayer 位移表达，位移量就是被拖那一行的高度（[shiftFor]）——
 *    与行高是否一致无关，行高不齐的列表也能严丝合缝，不依赖任何估算。
 *
 * 被拖的那一条**画在 LazyColumn 外面**（见调用处的悬浮层）：列表内容会被 LazyColumn 裁掉，
 * 画在里面的话一旦拖到视口边缘整条就没了；画在外面则永远完整。它原来占的槽位渲染成不可见，
 * 空位由 [shiftFor] 让出来。
 *
 * 手势挂在**列表外层**而不是把手上：把手在列表项里，项一旦被滚出视口就会被销毁，
 * 挂在它上面的手势随之被取消 —— 那正是「拖到后面突然断掉」的根因。
 * 命中判断交给 [hitTestHandle]。
 *
 * 松手后不立刻收工：真实数据换位是异步的，这里挂着悬浮层等它落定（见调用处的
 * `LaunchedEffect`），否则会「先弹回原位、再跳到新位置」连闪两下。
 */
private class PlaylistReorderState(
  private val listState: LazyListState,
  private val scope: CoroutineScope,
  /** 触发自动滚动的边缘带高度（px）。 */
  private val edgePx: Float,
  /** 手指贴到最边缘时每帧的滚动步长（px）。 */
  private val maxStepPx: Float,
  /** 左侧「把手带」宽度（px）：只有从这里按下去才算拖拽，从列表中间按下去仍是滚动。 */
  private val handleBandPx: Float,
  private val onMoveItem: (Int, Int) -> Unit,
) {
  /** 被拖走的原始下标；null = 当前没有在拖。 */
  var fromIndex by mutableStateOf<Int?>(null)
    private set

  /** 被拖的那一条（悬浮层按它渲染）。 */
  var liftedItem by mutableStateOf<PlaylistItem?>(null)
    private set

  /** 目标插入位置：**最终列表里的下标**，松手时直接交给 [onMoveItem]。 */
  var targetPos by mutableStateOf(0)
    private set

  /** 手指已抬起、正在等真实数据落位；这期间悬浮层继续挂着。 */
  var released by mutableStateOf(false)
    private set

  /** 手指在「列表视口坐标系」里的纵向位置（px）。 */
  private var pointerY by mutableStateOf(0f)

  /** 按下点在被拖行内部的位置（px）：悬浮层从「按住的那一点」起算，起手不会跳。 */
  private var grabDy = 0f

  /** 被拖那一行的高度（px），拖动开始时量一次。 */
  private var rowHeight = 0f

  /** 边缘自动滚动的循环任务。 */
  private var autoScrollJob: Job? = null

  val isDragging: Boolean
    get() = fromIndex != null

  /**
   * 按下点是否落在某一行左侧的「把手带」里；是则返回那一行的下标。
   *
   * 带子 64dp 是算出来的：左侧 16dp 行外距 + 8dp 行内距 + 40dp 把手 = 64dp，
   * 刚好把手连同一圈余量包住，也不会盖到缩略图（它从 76dp 才开始）。
   */
  fun hitTestHandle(position: Offset): Int? {
    if (position.x > handleBandPx) return null
    return listState.layoutInfo.visibleItemsInfo
      .firstOrNull { position.y >= it.offset && position.y <= it.offset + it.size }
      ?.index
  }

  fun onDragStart(
    pointer: Offset,
    index: Int,
    item: PlaylistItem,
  ) {
    val slot = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    rowHeight = slot.size.toFloat()
    // 用「当前指针位置」而不是按下的那一刻：起手时悬浮层顶边刚好落在原槽位上，不会跳一下
    grabDy = (pointer.y - slot.offset).coerceIn(0f, rowHeight)
    pointerY = pointer.y
    liftedItem = item
    fromIndex = index
    released = false
    targetPos = computeTarget()
  }

  fun onDrag(pointerYNow: Float) {
    if (fromIndex == null || released) return
    pointerY = pointerYNow
    targetPos = computeTarget()
    syncAutoScroll()
  }

  /** 松手：把最终位置交给数据层；悬浮层先留着，等数据落定再撤。 */
  fun onDrop() {
    val from = fromIndex ?: return
    stopAutoScroll()
    if (from != targetPos) onMoveItem(from, targetPos)
    released = true
  }

  /** 手势被取消：什么都没提交，直接撤回（列表本来就没被动过）。 */
  fun onCancel() {
    reset()
  }

  /** 撤掉悬浮层与所有拖动状态。 */
  fun reset() {
    stopAutoScroll()
    fromIndex = null
    liftedItem = null
    released = false
    targetPos = 0
    grabDy = 0f
    rowHeight = 0f
  }

  private fun stopAutoScroll() {
    autoScrollJob?.cancel()
    autoScrollJob = null
  }

  /** 悬浮层顶边在视口里的位置（px）：始终贴着手指，并被夹在可视区内，不会跑出列表。 */
  fun overlayTopPx(): Float {
    val info = listState.layoutInfo
    val top = info.viewportStartOffset.toFloat()
    val bottom = info.viewportEndOffset.toFloat()
    val raw = pointerY - grabDy
    val maxTop = (bottom - rowHeight).coerceAtLeast(top)
    return raw.coerceIn(top, maxTop)
  }

  /**
   * 让位位移：其它行要挪多少，才能把目标位置空出来。
   *
   * 只可能有三种值 —— -行高 / +行高 / 0：「抽走一行、再插到别处」对其它行的影响，
   * 就是整体平移一行的高度。跟每行自己的高度无关，所以行高不齐也不会错位。
   */
  fun shiftFor(index: Int): Float {
    val from = fromIndex ?: return 0f
    if (index == from) return 0f
    val to = targetPos
    return when {
      index > from && index <= to -> -rowHeight
      index >= to && index < from -> rowHeight
      else -> 0f
    }
  }

  /**
   * 手指压过几个槽位 —— 那就是它该插到的位置。
   *
   * 为什么不用「手指压在哪一行上」：那样等于拿渲染出来的行当刻度，而行的位置本身又取决于
   * 目标位置，会自己咬自己（目标一变、刻度就变，来回跳）。改成数**原始布局的槽位**就没有这问题：
   * 拖动期间渲染顺序完全不动，槽位是死的，数出来的目标单调、稳定。
   *
   * 计数口径：所有「在手指中心上方」的条目（不含被拖的那条）数量，就等于它在最终列表里的下标 ——
   * 被抽走的那一条不影响，因为它在移出去之后本来就不在别人的计数里。
   */
  private fun computeTarget(): Int {
    val from = fromIndex ?: return 0
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return 0
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return from
    val centerY = pointerY - grabDy + rowHeight / 2f
    // 已经滚到视口上方的那些行，自然也都在手指上方
    var count = 0
    val firstVisible = visible.first().index
    for (i in 0 until firstVisible) if (i != from) count++
    for (item in visible) {
      if (item.index == from) continue
      if (item.offset + item.size / 2f < centerY) count++
    }
    return count.coerceIn(0, total - 1)
  }

  /** 手指在边缘带里就保证有滚动任务在跑，离开就停。 */
  private fun syncAutoScroll() {
    if (edgeDirection() == null) {
      stopAutoScroll()
      return
    }
    if (autoScrollJob?.isActive == true) return
    autoScrollJob =
      scope.launch {
        // 手指停住不动时拖拽事件不会再送来，所以这里自发按帧滚动。
        // 注意：手指位置**不需要**任何补偿 —— 列表在手指下面滑走，浮起那条只跟手指走。
        while (isActive) {
          val dir = edgeDirection() ?: break
          // ⚠️ `scroll {}` 本身返回 Unit，实际滚了多少要由里面的 ScrollScope.scrollBy 带出来
          var consumed = 0f
          listState.scroll { consumed = scrollBy(dir * scrollStep()) }
          // 已经滚到队首/队尾，滚不动了：停下，等手指自己再动
          if (consumed == 0f) break
          // 列表滚了 → 手指底下换成了别的槽位，目标位置跟着重算
          targetPos = computeTarget()
          withFrameNanos { }
        }
        autoScrollJob = null
      }
  }

  /** 手指落在视口上/下边缘带里返回滚动方向（-1 上 / +1 下），不在带内返回 null。 */
  private fun edgeDirection(): Int? {
    if (fromIndex == null || released) return null
    val info = listState.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    val top = info.viewportStartOffset.toFloat()
    val bottom = info.viewportEndOffset.toFloat()
    return when {
      pointerY < top + edgePx -> -1
      pointerY > bottom - edgePx -> 1
      else -> null
    }
  }

  /** 滚得越贴边越快（每帧步长）。 */
  private fun scrollStep(): Float {
    val info = listState.layoutInfo
    val top = info.viewportStartOffset.toFloat()
    val bottom = info.viewportEndOffset.toFloat()
    val depth =
      (
        if (pointerY < top + edgePx) {
          (top + edgePx - pointerY) / edgePx
        } else {
          (pointerY - (bottom - edgePx)) / edgePx
        }
      ).coerceIn(0f, 1f)
    return maxStepPx * (0.35f + 0.65f * depth)
  }
}

// 边缘自动滚动的手感参数：进入 64dp 的边带开始滚，最深时每帧 16dp
private val DragAutoScrollEdge = 64.dp
private val DragAutoScrollMaxStep = 16.dp

// 左侧把手带宽度。64dp = 16dp 行外距 + 8dp 行内距 + 40dp 把手，
// 和 PlaylistTrackListItem 里把手的实际横向位置一一对应（改那里的内距要同步改这里）。
private val DragHandleBand = 64.dp

@Composable
private fun rememberDragReorder(
  listState: LazyListState,
  onMoveItem: (Int, Int) -> Unit,
): PlaylistReorderState {
  // onMoveItem 每次重组都是新的 lambda，用 rememberUpdatedState 让状态对象始终调用最新的那个
  val latest by rememberUpdatedState(onMoveItem)
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val edgePx = with(density) { DragAutoScrollEdge.toPx() }
  val maxStepPx = with(density) { DragAutoScrollMaxStep.toPx() }
  val bandPx = with(density) { DragHandleBand.toPx() }
  return remember(listState, edgePx, maxStepPx, bandPx) {
    PlaylistReorderState(listState, scope, edgePx, maxStepPx, bandPx) { from, to -> latest(from, to) }
  }
}

/**
 * 拖拽把手（**纯视觉**，不认手势）。
 *
 * 手势为什么不挂在这里：把手长在列表项里，项一旦被滚出视口就会被销毁，
 * 挂在它上面的手势随之被取消 —— 这正是「往后面拖、拖到一半突然断掉」的根因。
 * 手势统一挂在列表外层那个永不销毁的 Box 上（见 [PlaylistSheet] 里 isListMode 分支），
 * 这里只负责让人看得出「这一行能拖」。
 */
@Composable
private fun PlaylistDragHandle() {
  Box(
    // 视觉尺寸 40dp（原来 32dp 偏窄）；真正的触控判定用 DragHandleBand，比它更宽
    modifier = Modifier.size(40.dp),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.DragHandle,
      contentDescription = stringResource(R.string.i18n_drag_to_reorder),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      modifier = Modifier.size(22.dp),
    )
  }
}

@Composable
fun PlaylistTrackListItem(
  item: PlaylistItem,
  context: Context,
  thumbnailCache: LRUBitmapCache,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  accentColor: Color,
  /** 左侧拖拽把手；不传则不显示（例如只读列表）。 */
  dragHandle: (@Composable () -> Unit)? = null,
  /** 右侧移除按钮；不传则不显示。 */
  onRemove: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  // Use theme colors dynamically
  val accentSecondary = MaterialTheme.colorScheme.tertiary

  // Thumbnail state - uses cache to persist across recompositions
  val videoPath = item.path.ifBlank { item.uri.toString() }
  var thumbnail by remember(videoPath) {
    mutableStateOf(thumbnailCache[videoPath])
  }

  // Load thumbnail asynchronously
  // Skip thumbnail loading for M3U playlists (network streams)
  //
  // ⚠️ skipThumbnail 也要进 key：拖拽期间被拎起来的那一条是「先跳过、松手后又回来」的，
  // 只按 videoPath 做 key 的话，跳过时协程不重启、回来时也不会补加载，缩略图就永远空了。
  // 另外缓存命中时要把值回填进 state —— 同一个视频可能刚被悬浮层那份实例加载过。
  LaunchedEffect(videoPath, skipThumbnail) {
    if (thumbnailCache.containsKey(videoPath)) {
      thumbnail = thumbnailCache[videoPath]
      return@LaunchedEffect
    }
    if (skipThumbnail) return@LaunchedEffect
    val bmp =
      when {
        // 网络播放源（Emby 等）：背景图优先，退回海报，再退回占位图标
        item.thumbnailUrl.isNotBlank() ->
          loadUrlThumbnail(item.thumbnailUrl)
            ?: item.thumbnailUrl
              .replace("/Images/Backdrop", "/Images/Primary")
              .takeIf { it != item.thumbnailUrl }
              ?.let { loadUrlThumbnail(it) }
        else -> loadMediaStoreThumbnail(context, item.uri)
      }
    thumbnail = bmp
    thumbnailCache[videoPath] = bmp
  }

  val borderModifier = if (item.isPlaying) {
    Modifier.border(
      width = 2.dp,
      brush = Brush.linearGradient(listOf(accentColor, accentSecondary)),
      shape = RoundedCornerShape(12.dp),
    )
  } else {
    Modifier
  }

  Surface(
    // 让位位移 / 拎起后隐藏，都由调用方通过 modifier 传进来（见 isListMode 分支）
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = MaterialTheme.spacing.medium,
        vertical = MaterialTheme.spacing.extraSmall,
      )
      .clip(RoundedCornerShape(12.dp))
      .then(borderModifier)
      .clickable(onClick = onClick),
    color = if (item.isPlaying) {
      MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
    } else {
      Color.Transparent
    },
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(MaterialTheme.spacing.smaller),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      // 左侧拖拽把手（只在列表模式由调用方传入）
      dragHandle?.invoke()

      // Thumbnail with simple background, episode number, and progress
      Box(
        modifier = Modifier
          .width(100.dp)
          .height(56.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        // Show actual thumbnail or fallback icon
        thumbnail?.let { bmp ->
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = stringResource(R.string.i18n_thumbnail),
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        } ?: run {
          // Movie icon as fallback placeholder
          Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp),
          )
        }

        // Video number badge in top-left with better visibility
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(
              color = Color.Black.copy(alpha = 0.7f),
              shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${item.index + 1}",
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            ),
            color = Color.White,
          )
        }
      }

      // Title and info
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title,
          style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Normal,
            color = if (item.isPlaying) {
              accentColor
            } else if (item.isWatched) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        // Duration and resolution chips - always show with loading state if empty
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Duration chip
          if (item.duration.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.duration,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else if (!item.isNetworkStream) {
            // 网络流不探测元数据，不渲染加载占位
            LoadingChip(width = 40.dp)
          }
          
          // Resolution chip
          if (item.resolution.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else if (!item.isNetworkStream) {
            // 网络流（Emby / strm）不探测元数据，永远不会有值 —— 不渲染加载占位，
            // 否则就是「一直在加载」的样子（见 PlaylistItem.isNetworkStream 的说明）
            LoadingChip(width = 60.dp)
          }
        }
      }

      // Status badges
      when {
        item.isPlaying -> {
          Surface(
            color = accentColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
          ) {
            Text(
              text = stringResource(R.string.i18n_playing),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
              ),
            )
          }
        }

      }

      // 右侧移除按钮（队列只剩一条时调用方不会传进来）
      onRemove?.let { remove ->
        IconButton(
          onClick = remove,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.i18n_remove_from_queue),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
fun PlaylistTrackGridItem(
  item: PlaylistItem,
  context: Context,
  thumbnailCache: LRUBitmapCache,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  modifier: Modifier = Modifier,
) {
  // Use theme colors dynamically
  val accentColor = MaterialTheme.colorScheme.primary
  val accentSecondary = MaterialTheme.colorScheme.tertiary

  // Thumbnail state - uses cache to persist across recompositions
  val videoPath = item.path.ifBlank { item.uri.toString() }
  var thumbnail by remember(videoPath) {
    mutableStateOf(thumbnailCache[videoPath])
  }

  // Load thumbnail asynchronously
  // Skip thumbnail loading for M3U playlists (network streams)
  LaunchedEffect(videoPath) {
    if (!skipThumbnail && !thumbnailCache.containsKey(videoPath)) {
      val bmp =
        when {
          // 网络播放源（Emby 等）：背景图优先，退回海报，再退回占位图标
          item.thumbnailUrl.isNotBlank() ->
            loadUrlThumbnail(item.thumbnailUrl)
              ?: item.thumbnailUrl
                .replace("/Images/Backdrop", "/Images/Primary")
                .takeIf { it != item.thumbnailUrl }
                ?.let { loadUrlThumbnail(it) }
          else -> loadMediaStoreThumbnail(context, item.uri)
        }
      thumbnail = bmp
      thumbnailCache[videoPath] = bmp
    }
  }

  val borderModifier = if (item.isPlaying) {
    Modifier.border(
      width = 2.dp,
      brush = Brush.linearGradient(listOf(accentColor, accentSecondary)),
      shape = RoundedCornerShape(12.dp),
    )
  } else {
    Modifier
  }

  // YouTube-style vertical card
  Surface(
    modifier = modifier
      .width(200.dp)
      .clip(RoundedCornerShape(12.dp))
      .then(borderModifier)
      .clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.smaller),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      // Thumbnail with 16:9 aspect ratio
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(112.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        // Show actual thumbnail or fallback icon
        thumbnail?.let { bmp ->
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = stringResource(R.string.i18n_thumbnail),
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        } ?: run {
          // Movie icon as fallback placeholder
          Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp),
          )
        }

        // Video number badge in top-left
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(
              color = Color.Black.copy(alpha = 0.7f),
              shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${item.index + 1}",
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            ),
            color = Color.White,
          )
        }

        // Duration badge in bottom-right
        if (item.duration.isNotEmpty()) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(6.dp)
              .background(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(4.dp),
              )
              .padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = item.duration,
              style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
              ),
              color = Color.White,
            )
          }
        } else if (!item.isNetworkStream) {
          // Loading duration badge（网络流不探测，不显示）
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(6.dp)
          ) {
            LoadingChip(width = 40.dp, height = 18.dp, isDark = true)
          }
        }

        // Playing indicator overlay
        if (item.isPlaying) {
          Box(
            modifier = Modifier
              .matchParentSize()
              .background(
                brush = Brush.verticalGradient(
                  colors = listOf(
                    accentColor.copy(alpha = 0.3f),
                    accentColor.copy(alpha = 0.1f),
                  )
                )
              )
          )
        }
      }

      // Title and metadata
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title,
          modifier = Modifier.height(44.dp),
          style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            color = if (item.isPlaying) {
              accentColor
            } else if (item.isWatched) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        // Resolution and status
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Resolution chip
          if (item.resolution.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else if (!item.isNetworkStream) {
            // 网络流不探测元数据，不渲染加载占位
            LoadingChip(width = 60.dp)
          }

          if (item.isPlaying) {
            Surface(
              color = accentColor.copy(alpha = 0.15f),
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = stringResource(R.string.i18n_playing),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = accentColor,
                ),
              )
            }
          }
        }
      }
    }
  }
}


@Composable
fun LoadingChip(
  width: androidx.compose.ui.unit.Dp,
  height: androidx.compose.ui.unit.Dp = 18.dp,
  isDark: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
  val shimmerTranslate = infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "shimmer"
  )

  val baseColor = if (isDark) {
    Color.White.copy(alpha = 0.1f)
  } else {
    MaterialTheme.colorScheme.surfaceContainerHighest
  }
  
  val shimmerColor = if (isDark) {
    Color.White.copy(alpha = 0.2f)
  } else {
    MaterialTheme.colorScheme.surfaceContainerHigh
  }

  Box(
    modifier = modifier
      .width(width)
      .height(height)
      .clip(RoundedCornerShape(4.dp))
      .background(
        brush = Brush.linearGradient(
          colors = listOf(
            baseColor,
            shimmerColor,
            baseColor,
          ),
          start = Offset(shimmerTranslate.value - 200f, 0f),
          end = Offset(shimmerTranslate.value, 0f)
        )
      )
  )
}
