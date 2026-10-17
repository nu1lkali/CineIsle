package app.marlboroadvance.mpvex.ui.browser.photo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoZoomRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * 一张照片在共享浏览器里需要的**最小信息**。
 *
 * 两个入口（Emby 媒体库 / 网络位置）的数据模型完全不同（[app.marlboroadvance.mpvex
 * .domain.emby.EmbyItem] vs [app.marlboroadvance.mpvex.domain.network.NetworkFile]），
 * 但它们对**外壳**的要求是同一套：一个稳定 key、一个标题、一行附加信息。
 * 把这三样收在这里，外壳就不需要知道数据来自哪个协议。
 *
 * @param key pager / 胶片条的稳定 key（Emby 用条目 Id，网络用 path）
 * @param title 信息栏第一行 + 无障碍描述（文件名）
 * @param meta 信息栏第二行（如「719 KB · 2026-10-17」）；null 则不显示
 */
@Immutable
data class PhotoViewerEntry(
  val key: Any,
  val title: String,
  val meta: String? = null,
)

/**
 * 顶栏右侧插槽拿到的上下文。
 *
 * 「全屏 / 旋转 / 计数」这些**外壳已经自己画**了，这里下发是为了让插槽里的
 * 「更多菜单」「长按面板」也能读到同一份状态、走同一个动作入口 ——
 * 不能让调用方自己再存一份（两份状态迟早不同步）。
 */
class PhotoViewerChrome(
  /** 当前页下标（0 基） */
  val currentIndex: Int,
  /** 是否处于沉浸（全屏）模式 */
  val immersive: Boolean,
  /** 切换沉浸模式（与「点画面任意处」是同一个开关） */
  val toggleImmersive: () -> Unit,
  /** 当前这张的旋转角（0/90/180/270，顺时针） */
  val rotation: Int,
  /** 把当前这张顺时针转 90°（与顶栏那个旋转钮同一个动作） */
  val rotateCurrent: () -> Unit,
)

/**
 * 照片旋转角：**按条目 key 记**，不是按页码。
 *
 * 为什么按 key：Emby 版会续页追加、来回翻页，按页码记会在队列变化时把角度错位到
 * 别的照片上；按 key（Emby 用条目 Id、网络用 path）就永远不会串。
 *
 * 为什么放在外壳里：顶栏旋转钮、长按面板的「旋转 90 度」、「另存为 JPEG」的导出角度
 * 三处要读**同一份**状态。做成一个小 holder 由适配层创建、外壳持有，两边都能用。
 */
@Stable
class PhotoRotationState {
  private var angles by mutableStateOf<Map<Any, Int>>(emptyMap())

  fun of(key: Any): Int = angles[key] ?: 0

  fun rotate(key: Any) {
    angles = angles + (key to ((angles[key] ?: 0) + 90) % 360)
  }
}

@Composable
fun rememberPhotoRotationState(): PhotoRotationState = remember { PhotoRotationState() }

/**
 * 照片全屏浏览器的**共享外壳**：Emby 媒体库与网络位置共用这一份。
 *
 * 为什么合并（用户要求）：「那边改了这边没改」是这个项目最常踩的坑 ——
 * 两个浏览器原先各写一份翻页 / 沉浸 / 幻灯片 / 胶片条 / 下拉关闭，
 * 加了网络版的胶片条之后立刻出现「Emby 有、网络没有」的分叉。
 * 现在**外壳只有一份**，剩下的差异全是插槽：
 *  · [renderPage] —— 每页的照片怎么画（Emby 走服务端缩放 URL，网络走流式拉全图）；
 *  · [renderThumbnail] —— 胶片条里的缩略图怎么画；
 *  · [topBarActions] —— 计数 / 全屏 / 旋转之后再放什么（两个入口都放「更多菜单」）。
 *
 * 外壳自己负责（**改这里两个入口同时生效**）：
 * - 横向翻页（[HorizontalPager]）+ 沉浸模式（点画面切换、顶栏钮、返回键先退沉浸）；
 * - 下拉关闭（未放大时跟手位移 + 缩小 + 变暗，过阈值松手退出）；
 * - 幻灯片（间隔读「幻灯片间隔」设置、淡出→换页→淡入、倒计时圆环、播到最后自动停、
 *   播放期间 keepScreenOn、手动滑页只重置计时不停止播放）；
 * - 底部胶片条（开关两个入口共用 `photoFilmstrip`，当前页自动滚入可见）；
 * - 左下「上一张 / 下一张」、右下幻灯片钮、**底部信息栏**（文件名 + 大小日期 + 序号，
 *   两个入口都有 —— 以前只有网络版有，用户直接指出这是无谓的分叉）；
 * - **旋转**（顶栏钮 + [PhotoRotationState]，只影响显示，不改原字节）。
 *
 * ⚠️ 外壳**不碰数据**：队列怎么来、怎么续页、怎么保存，全在调用方（适配层）里。
 *    这正是两边能合并的原因 —— 共同的是交互，不同的是来源。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedPhotoViewer(
  /** 队列（Emby 版会随续页增长，所以由调用方持有并传入最新值） */
  entries: List<PhotoViewerEntry>,
  /** 分页状态由调用方持有：续页、记住浏览位置都要读它 */
  pagerState: PagerState,
  /** 顶栏标题 */
  title: String,
  /** 进页即播幻灯片（Emby 读设置项，网络版按「随机播放图片」入口传 true） */
  autoPlaySlideshow: Boolean = false,
  /** 旋转状态：调用方也持有同一个实例，长按面板 / 导出 JPEG 时要用同一份角度 */
  rotation: PhotoRotationState = rememberPhotoRotationState(),
  onBack: () -> Unit,
  /** 顶栏右侧额外按钮（如「更多菜单」），排在计数 / 全屏 / 旋转之后 */
  topBarActions: @Composable RowScope.(PhotoViewerChrome) -> Unit = {},
  /**
   * 画某一页。`onTap` 交给页面内部的手势（zoomable 的 tap）调用 ——
   * 外层不要再挂一套 tap 检测，那会跟「双击放大」抢事件。
   * `rotation` 是这一张当前的旋转角，页面自己应用（`.`rotate(deg)`）。
   */
  renderPage: @Composable (
    index: Int,
    zoomRegistry: PhotoZoomRegistry,
    rotation: Int,
    onTap: () -> Unit,
  ) -> Unit,
  /** 画胶片条里的某一格缩略图 */
  renderThumbnail: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val scope = rememberCoroutineScope()
  val currentPage = pagerState.currentPage

  // 胶片条：**两个入口共用同一个偏好**（设置 → Emby 媒体库 → 照片浏览器）
  val filmstrip by browserPreferences.photoFilmstrip.collectAsState()
  val stripVisible = filmstrip && entries.size > 1

  // ── 幻灯片 ──
  var slideshowPlaying by remember { mutableStateOf(autoPlaySlideshow) }
  val interval by browserPreferences.photoSlideshowInterval.collectAsState()
  // 本轮还剩几秒（右下角按钮上的倒计时）。0 = 没在播 / 刚翻完页。
  var slideshowRemaining by remember { mutableIntStateOf(0) }
  // 转场用的整页透明度：翻页前淡到 0、换完页再淡回来。
  // 用 Animatable 而不是 Compose State：值每帧都变，走 State 会把整屏重组一遍。
  val slideshowAlpha = remember { Animatable(1f) }

  // 播放期间保持屏幕常亮；暂停 / 退出页面解除
  val view = LocalView.current
  DisposableEffect(slideshowPlaying) {
    view.keepScreenOn = slideshowPlaying
    onDispose { view.keepScreenOn = false }
  }

  // 沉浸（全屏）：只留照片
  var immersive by remember { mutableStateOf(false) }
  BackHandler(enabled = immersive) { immersive = false }

  // ── 下拉关闭 ──
  // 竖向拖动的位移（px）。⚠️ 只在 graphicsLayer 的 lambda 里读 —— 那里是绘制阶段，
  // 读 State 只会让绘制失效，不会触发重组；放到组合里读就是每帧重组整屏。
  val dismissOffset = remember { Animatable(0f) }
  val zoomRegistry = remember { PhotoZoomRegistry() }
  val dismissThreshold = with(LocalDensity.current) { DISMISS_THRESHOLD_DP.dp.toPx() }

  // ── 幻灯片计时 ──
  //
  // ⭐⭐ **key 里绝不能有 currentPage**（这是「幻灯片只滑一半」的根因）：
  // currentPage 一变，这个协程被取消重建，而取消恰好发生在
  // `animateScrollToPage` 挂起等待动画的过程中 ⇒ 动画被腰斩，页面停在两张中间。
  // 改成「一个协程跑到底」：循环里自己 delay 到点、自己翻页。
  //
  // 「手动滑页重置计时」在循环内部判（只有**用户**在滑才重置，幻灯片自己翻的不算）：
  // 滑一下只是「这张要多看一会儿」，不停止播放。
  LaunchedEffect(slideshowPlaying, interval) {
    if (!slideshowPlaying) {
      slideshowRemaining = 0
      // 中途关掉播放时把透明度收回 1，别让画面卡在半透明
      slideshowAlpha.snapTo(1f)
      return@LaunchedEffect
    }
    val totalMs = interval.seconds * 1000L
    // 幻灯片自己翻页期间 isScrollInProgress 也是 true，用这个标志把它与用户手势区分开
    var autoScrolling = false
    while (true) {
      var elapsed = 0L
      slideshowRemaining = interval.seconds
      while (elapsed < totalMs) {
        delay(TICK_MS)
        if (!autoScrolling && pagerState.isScrollInProgress) {
          // 用户在自己滑：这张重新开始计时（不停止播放）
          elapsed = 0
          slideshowRemaining = interval.seconds
        } else {
          elapsed += TICK_MS
          // 向上取整，让「还剩 1 秒」真的显示 1 而不是 0
          slideshowRemaining = ((totalMs - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
        }
      }
      val next = pagerState.currentPage + 1
      if (next >= entries.size) {
        slideshowPlaying = false // 播到最后一张自动停
        slideshowRemaining = 0
        break
      }
      autoScrolling = true
      // 转场：先淡出，换页（滑动过程被藏起来），再淡入。
      // 只在**自动**翻页时走这条路 —— 手动左右滑就该是跟手的滑动。
      slideshowAlpha.animateTo(0f, tween(FADE_MS))
      pagerState.animateScrollToPage(next)
      slideshowAlpha.animateTo(1f, tween(FADE_MS))
      autoScrolling = false
    }
  }

  // 信息栏两个入口都有（不再按入口分叉），只剩胶片条会影响按钮抬高多少
  val controlsBottom = photoControlsBottom(filmstrip = stripVisible)
  val canNavigate = entries.size > 1

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    if (entries.isEmpty()) {
      // 友好提示：不该发生（浏览页点进来必有照片），但队列被外部改空时不能甩一个黑屏
      Text(
        text = "没有照片",
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.align(Alignment.Center),
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxSize()
          // 竖向拖动 = 下拉关闭。放在 graphicsLayer **之前**（外层）：
          // 手势拿到的是未变换的坐标，拖动距离与手指位移一致。
          .pointerInput(Unit) {
            detectVerticalDragGestures(
              onDragEnd = {
                if (abs(dismissOffset.value) > dismissThreshold) {
                  onBack()
                } else {
                  scope.launch { dismissOffset.animateTo(0f, spring()) }
                }
              },
              onDragCancel = {
                scope.launch { dismissOffset.animateTo(0f, spring()) }
              },
              onVerticalDrag = { _, dragAmount ->
                // ⭐ 放大状态下竖向拖动是「平移画面」，不能抢 —— 那种情况 Telephoto
                //    自己会消费掉，根本走不到这里；这里的判断是双保险（例如刚好
                //    捏到边界、zoomable 这一帧放行了）。
                if (zoomRegistry.zoomFractionOf(currentPage) <= 0.01f) {
                  scope.launch { dismissOffset.snapTo(dismissOffset.value + dragAmount) }
                }
              },
            )
          }
          .graphicsLayer {
            val progress = (abs(dismissOffset.value) / dismissThreshold).coerceIn(0f, 1f)
            translationY = dismissOffset.value
            val scale = 1f - progress * 0.22f
            scaleX = scale
            scaleY = scale
            // 下拉变暗 + 幻灯片转场淡入淡出，两者相乘（同时发生也不打架）
            alpha = (1f - progress * 0.55f) * slideshowAlpha.value
          },
      ) {
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize(),
          key = { index -> entries.getOrNull(index)?.key ?: index },
        ) { page ->
          if (entries.getOrNull(page) == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text("照片信息缺失", color = Color.White)
            }
          } else {
            // 旋转角由外壳按 key 取出、交给页面自己应用（`.rotate(deg)` 放在 zoomable 之前，
            // 手势方向才与所见一致）。点一下画面 = 进出沉浸；长按的语义两边不同，由页面自己绑定
            val rotationDegrees = rotation.of(entries[page].key)
            renderPage(page, zoomRegistry, rotationDegrees) { immersive = !immersive }
          }
        }
      }

      // ── 顶栏：返回 + 标题 + 计数 + 调用方按钮 ──
      // 沉浸态整条淡出（不占手势、不挡画面），退出沉浸再淡入
      AnimatedVisibility(
        visible = !immersive,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
              tint = Color.White,
            )
          }
          Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            // ⚠️ 显式 Ellipsis：Text 默认 overflow = Clip，长标题会被硬切成半截
            overflow = TextOverflow.Ellipsis,
            // 右侧留 8dp：标题与「3 / 120」计数器不要贴在一起
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          if (entries.isNotEmpty()) {
            // ⚠️ 顶栏**不再放「3 / 120」计数器**（v94）：信息栏右下角已经有同一个序号，
            //    重复一遍只会把标题挤到只剩几个字（网络版顶栏 3 个钮 + 返回已经占满 192dp）。
            //    信息栏跟着沉浸态一起隐藏，所以「没计数器」不会出现死角。
            // ── 全屏（沉浸）──
            // 与「点画面任意处」是同一个开关，这里只是给一个明确的入口。
            // ⚠️ 不做「退出全屏」图标：沉浸态整条顶栏是淡出的（见上面的 AnimatedVisibility），
            //    那个图标永远没机会显示 —— 写了就是死代码。退出靠点画面 / 返回键。
            IconButton(onClick = { immersive = true }) {
              Icon(
                Icons.Filled.Fullscreen,
                contentDescription = "全屏",
                tint = Color.White,
              )
            }
            // ── 旋转 90°（顺时针）──
            // 角度按条目 key 记（[PhotoRotationState]），翻页回来还在；
            // 只影响显示与「另存为 JPEG」，「保存原图」永远是原字节。
            val rotateKey = entries.getOrNull(currentPage)?.key
            if (rotateKey != null) {
              IconButton(onClick = { rotation.rotate(rotateKey) }) {
                Icon(
                  Icons.AutoMirrored.Filled.RotateRight,
                  contentDescription = "旋转 90 度",
                  tint = Color.White,
                )
              }
            }
          }
          topBarActions(
            PhotoViewerChrome(
              currentIndex = currentPage,
              immersive = immersive,
              toggleImmersive = { immersive = !immersive },
              rotation = entries.getOrNull(currentPage)?.let { rotation.of(it.key) } ?: 0,
              // 插槽里的「旋转 90 度」（长按面板那类）必须走同一个入口，
              // 否则它自己再存一份角度，顶栏转过的角度就丢了
              rotateCurrent = { entries.getOrNull(currentPage)?.let { rotation.rotate(it.key) } },
            ),
          )
        }
      }

      // ── 左下角：上一张 / 下一张（首尾边界置灰，不留假按钮） ──
      AnimatedVisibility(
        visible = !immersive && canNavigate,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomStart),
      ) {
        Row(
          modifier = Modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, bottom = controlsBottom),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PhotoNavButton(
            enabled = currentPage > 0,
            icon = Icons.Default.ChevronLeft,
            contentDescription = "上一张",
            onClick = { scope.launch { pagerState.animateScrollToPage(currentPage - 1) } },
          )
          PhotoNavButton(
            enabled = currentPage < entries.size - 1,
            icon = Icons.Default.ChevronRight,
            contentDescription = "下一张",
            onClick = { scope.launch { pagerState.animateScrollToPage(currentPage + 1) } },
          )
        }
      }

      // ── 右下角：幻灯片播放 / 暂停（播放时显示倒计时） ──
      AnimatedVisibility(
        visible = !immersive && canNavigate,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd),
      ) {
        SlideshowButton(
          playing = slideshowPlaying,
          remainingSeconds = slideshowRemaining,
          totalSeconds = interval.seconds,
          bottomPadding = controlsBottom,
          onClick = { slideshowPlaying = !slideshowPlaying },
        )
      }

      // ── 底部（自下而上）：胶片条（可选）+ 信息栏 ──
      // 两者都贴底边，所以堆进同一个 Column —— 不用手算信息栏多高，
      // 也不会因为字号 / 语言一换就错位。
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth(),
      ) {
        AnimatedVisibility(
          visible = !immersive && stripVisible,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          PhotoFilmstrip(
            entries = entries,
            currentPage = currentPage,
            renderThumbnail = renderThumbnail,
            onPageClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
          )
        }
        // ⚠️ 信息栏**两个入口都显示**（不再有 showInfoBar 开关）：
        //    以前只有网络版有、Emby 版没有，用户直接把这当成「无谓的分叉」。
        //    数据来自 entries 的 title/meta，与数据源无关。
        AnimatedVisibility(
          visible = !immersive,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          PhotoInfoBar(
            entry = entries.getOrNull(currentPage),
            index = currentPage + 1,
            total = entries.size,
          )
        }
      }
    }
  }
}

/**
 * 底部悬浮控件（翻页 / 幻灯片）距底的高度。
 *
 * 这几个数是把两个入口**现有的观感**原样固定下来的结果，别再各自手算：
 *  · 信息栏（两个入口都有）：两行文字 ≈ 84dp；
 *  · 胶片条再吃掉 64dp（56dp 高 + 8dp 间距）。
 * ⚠️ 有胶片条时按钮**不能**压在缩略图上，宁可多留一点。
 */
private fun photoControlsBottom(filmstrip: Boolean): Dp = if (filmstrip) 148.dp else 84.dp

/**
 * 文件大小的人类可读写法（`719 KB` / `1.2 MB`）。
 *
 * 放在外壳里共用：信息栏的 meta 行两个入口都要拼「大小 · 日期」，
 * 各写一份必然出现「Emby 显示 1.2 MB、网络显示 1.20 MB」这种无谓差异。
 */
fun formatPhotoFileSize(bytes: Long): String = when {
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> "${bytes / 1024} KB"
  bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
  else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * 底部信息栏：**文件名独占一行**，第二行是「大小 · 日期」+ 序号。
 *
 * ⚠️ 为什么拆成两行：原先「文件名(weight) + 大小 + 序号」挤一行，文件名没有右间距、
 * 大小紧贴其后 —— 当文件名尾部是数字时（如 `e836runa_kobayashi1027` 接 `719 KB`），
 * 眼睛会把两段数字连读成「1027719」，用户直接读错。拆两行后二者物理隔开，错觉消失。
 */
@Composable
private fun PhotoInfoBar(
  entry: PhotoViewerEntry?,
  index: Int,
  total: Int,
) {
  if (entry == null) return
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.4f))
      .navigationBarsPadding()
      .padding(horizontal = 20.dp, vertical = 8.dp),
  ) {
    Text(
      text = entry.title,
      color = Color.White,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!entry.meta.isNullOrBlank()) {
        Text(
          text = entry.meta,
          color = Color.White.copy(alpha = 0.6f),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
        )
      }
      // 把序号推到最右；有/无 meta 都稳定靠右
      Spacer(Modifier.weight(1f))
      Text(
        text = "$index / $total",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
      )
    }
  }
}

/**
 * 底部缩略图胶片条：横向一排小图，点一下跳到那张。
 *
 * 当前那张用**白框 + 满不透明度**标出来，其余降到 55% —— 不靠颜色，
 * 全屏浏览器底色是纯黑，主题色在这里会糊。
 *
 * 当前页变化时自动把它滚进可见区（`animateScrollToItem`），否则翻到第 50 张时
 * 高亮早已滚出屏幕，这条就失去意义了。
 */
@Composable
private fun PhotoFilmstrip(
  entries: List<PhotoViewerEntry>,
  currentPage: Int,
  renderThumbnail: @Composable (index: Int, modifier: Modifier) -> Unit,
  onPageClick: (Int) -> Unit,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(currentPage) {
    if (currentPage in entries.indices) {
      // 越界 / 布局未完成时 animateScrollToItem 会抛，包一层免得整页崩
      runCatching { listState.animateScrollToItem(currentPage) }
    }
  }
  LazyRow(
    state = listState,
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 8.dp)
      .height(56.dp)
      .background(Color.Black.copy(alpha = 0.55f)),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    items(count = entries.size, key = { index -> entries[index].key }) { index ->
      val selected = index == currentPage
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(RoundedCornerShape(6.dp))
          .alpha(if (selected) 1f else 0.55f)
          .border(
            width = if (selected) 2.dp else 0.dp,
            color = if (selected) Color.White else Color.Transparent,
            shape = RoundedCornerShape(6.dp),
          )
          .clickable { onPageClick(index) },
      ) {
        renderThumbnail(index, Modifier.fillMaxSize())
      }
    }
  }
}

/**
 * 右下角的幻灯片按钮：播放中显示**倒计时**（圆环 + 剩余秒数），暂停时是播放图标。
 *
 * 圆环自绘（Canvas）而不用带进度的 `CircularProgressIndicator`：那个 API 在
 * material3 1.5 的 alpha 版里签名一直在动，自绘一了百了，也不受主题色影响
 * —— 全屏浏览器底色是纯黑，所有悬浮控件统一「半透明黑底 + 白前景」。
 */
@Composable
private fun SlideshowButton(
  playing: Boolean,
  remainingSeconds: Int,
  totalSeconds: Int,
  bottomPadding: Dp,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = Color.Black.copy(alpha = 0.45f),
    modifier = Modifier
      .navigationBarsPadding()
      .padding(end = 20.dp, bottom = bottomPadding)
      .size(52.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      if (playing) {
        val progress = (remainingSeconds.toFloat() / totalSeconds.coerceAtLeast(1).toFloat())
          .coerceIn(0f, 1f)
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
          val stroke = Stroke(width = 3.dp.toPx())
          // 底环
          drawArc(
            color = Color.White.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
          )
          // 剩余进度：从 12 点顺时针，随倒计时变短
          drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = stroke,
          )
        }
        Text(
          text = remainingSeconds.coerceAtLeast(0).toString(),
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
        )
      } else {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = "播放幻灯片",
          tint = Color.White,
          modifier = Modifier.size(26.dp),
        )
      }
    }
  }
}

/**
 * 照片浏览器左下角的悬浮切换按钮（上一张 / 下一张）。
 *
 * 到边界（第一张 / 最后一张）时**降到更低的不透明度并禁用点击**：让用户一眼看出
 * 「到头了」，而不是按了没反应（交互里的静默无效是产品 bug）。
 */
@Composable
private fun PhotoNavButton(
  enabled: Boolean,
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = if (enabled) 0.45f else 0.22f))
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
      modifier = Modifier.size(26.dp),
    )
  }
}

/** 下拉关闭的位移阈值（dp）—— 约屏幕高度的 1/6，够明确又不至于要划很远 */
private const val DISMISS_THRESHOLD_DP = 120f

/** 幻灯片计时 tick（ms）与淡入淡出时长（ms） */
private const val TICK_MS = 250L
private const val FADE_MS = 220
