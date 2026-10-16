package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * 照片全屏浏览器。
 *
 * 交互（需求锁定）：
 * - **缩放**：Telephoto（双指缩放 / 双击 / 拖拽），照片用 `ContentScale.Fit`
 *   不裁切；缩放中手势被 zoomable 消费，不会误触发翻页。最大 4×，越界橡皮筋回弹。
 * - **下拉关闭**：没放大的照片往下拖，整页跟手位移 + 缩小 + 变暗；拖过阈值松手就退出，
 *   没过就弹回去。⭐ 放大状态下不抢这个手势 —— 那时竖向拖动是「平移画面」，
 *   判据读 [PhotoZoomRegistry.zoomFractionOf]（不是 Compose State，所以不产生重组）。
 * - **沉浸（全屏）**：点画面任意处切换 —— 隐藏顶栏、翻页钮、幻灯片钮，只留照片；
 *   再点一次退出。顶栏也有一个入口（[Icons.Filled.Fullscreen]）。
 *   沉浸态下返回键先退出沉浸，再按才真的返回（不会「一键退出」）。
 * - **长按**：弹**底部操作面板**（保存原图 / 另存为 JPEG / 收藏 / 旋转 / 图片信息）。
 *   ⚠️ 长按只有这一个出口 —— 旧版「长按直接弹保存对话框」已收编进面板第一组，
 *   两个长按入口并存会让用户不知道长按到底会弹什么。
 * - **幻灯片**：右下角播放/暂停；间隔走「设置 → Emby 媒体库 → 幻灯片间隔」；
 *   **是否进页自播**走同页下方的「自动播放」开关（默认关，用户自己决定）。
 *   手动滑页只重置计时、不停止播放；播到最后一张自动停。
 *   **转场是淡出→换页→淡入**（不再是硬切），播放期间按钮上显示**剩余秒数**（圆环 + 数字）。
 *   播放期间 `keepScreenOn`，暂停/退出即解除。
 * - **底部胶片条**：设置开关（默认关），开着时底部多一条缩略图，点一下跳到那张。
 * - **左下角悬浮按钮**：横向排布的「上一张 / 下一张」半透明圆钮；
 *   第一张时上一张置灰、最后一张时下一张置灰（不留假按钮）。
 * - **旋转**：顶栏按钮每次顺时针 90°，角度**按条目 Id** 记（翻页回来还在）。
 *   只影响显示与「另存为 JPEG」；「保存原图」永远是原字节，不跟着转。
 * - **前后预加载**：预取相邻 ±N 张（N 走「设置 → Emby 媒体库 → 照片预加载张数」，1~3）。
 * - **更多菜单**：图片信息（懒显示，只在点开时读当前条目的 EXIF 字段）/
 *   保存原图 / 另存为 JPEG。保存位置走「设置 → Emby 媒体库 → 照片保存位置」。
 * - **续页**：滑到距队列末尾 3 张以内自动拉下一页（StartIndex 用独立
 *   [consumed] 计数，不用队列 size）。
 * - **记住位置**：退出时按 parentId 记下当前第几张，下次进同一个相册由网格页定位回去。
 */
@Serializable
data class EmbyPhotoBrowserScreen(
  /** 初始队列快照（网格页当前列表 / 随机播放结果） */
  val initialPhotos: List<EmbyItem>,
  val initialIndex: Int,
  /** 库 Id 或相册 Id：续页按同一父级拉 */
  val parentId: String,
  val title: String = "",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by vm.currentServer.collectAsState()
    val browserPreferences = koinInject<BrowserPreferences>()

    // ── 队列（续页会追加，所以是可变的） ──
    var queue by remember { mutableStateOf(initialPhotos) }
    val safeInitial = initialIndex.coerceIn(0, maxOf(0, initialPhotos.size - 1))
    val pagerState = rememberPagerState(initialPage = safeInitial) { queue.size }
    var totalCount by remember { mutableIntStateOf(0) }
    var consumed by remember { mutableIntStateOf(initialPhotos.size) }
    var loadingMore by remember { mutableStateOf(false) }

    // ── 幻灯片 ──
    // 进页是否自播：读全局开关（默认关）一次。改开关只影响之后打开的浏览器。
    var slideshowPlaying by remember {
      mutableStateOf(browserPreferences.photoAutoSlideshow.get())
    }
    val interval by browserPreferences.photoSlideshowInterval.collectAsState()
    // 本轮还剩几秒（右下角按钮上的倒计时）。0 = 没在播 / 刚翻完页。
    var slideshowRemaining by remember { mutableIntStateOf(0) }
    // 幻灯片转场用的整页透明度：翻页前淡到 0、换完页再淡回来。
    // 用 Animatable 而不是 Compose State：值每帧都变，走 State 会把整屏重组一遍。
    val slideshowAlpha = remember { Animatable(1f) }

    // 播放期间保持屏幕常亮；暂停 / 退出页面解除
    val view = LocalView.current
    DisposableEffect(slideshowPlaying) {
      view.keepScreenOn = slideshowPlaying
      onDispose { view.keepScreenOn = false }
    }

    var saving by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage

    // 沉浸（全屏）模式：只留照片，控件与标题全隐藏
    var immersive by remember { mutableStateOf(false) }

    // 底部胶片条：设置开关，默认关（它要吃掉画面高度，不是所有人都想要）
    val filmstrip by browserPreferences.photoFilmstrip.collectAsState()

    // ── 旋转 ──
    // 角度按**条目 Id** 记（不是按页码）：续页追加、来回翻页都不会丢。
    // 只影响显示与「另存为 JPEG」——「保存原图」写的是原字节，不能动。
    var rotations by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    /** 某张照片当前应显示的旋转角（顺时针，0/90/180/270） */
    fun rotationOf(item: EmbyItem?): Int = item?.Id?.let { rotations[it] } ?: 0

    /** 长按命中的条目；非空时弹底部操作面板 */
    var longPressTarget by remember { mutableStateOf<EmbyItem?>(null) }

    // ── 下拉关闭 ──
    // 竖向拖动的位移（px）。⚠️ 只在 graphicsLayer 的 lambda 里读 —— 那里是绘制阶段，
    // 读 State 只会让绘制失效，不会触发重组；放到组合里读就是每帧重组整屏。
    val dismissOffset = remember { Animatable(0f) }
    val zoomRegistry = remember { PhotoZoomRegistry() }
    val dismissThreshold = with(LocalDensity.current) { DISMISS_THRESHOLD_DP.dp.toPx() }

    // ── 记住上次浏览位置 ──
    // 退出时把「看到第几张」按 parentId 存下来，网格页下次进这个相册时定位回去。
    // ⚠️ 只在队列非空时记：空队列下 currentPage 是 0，会把真实位置冲掉。
    val lastIndexPref = remember(parentId) { browserPreferences.photoLastIndex(parentId) }
    DisposableEffect(parentId) {
      onDispose {
        if (queue.isNotEmpty()) lastIndexPref.set(pagerState.currentPage)
      }
    }

    // ── 续页 ──
    fun loadMore() {
      val current = server ?: return
      if (loadingMore) return
      if (totalCount in 1..consumed) return // 已知拉完
      loadingMore = true
      scope.launch {
        val result = runCatching {
          withContext(Dispatchers.IO) {
            vm.loadPhotos(current, parentId, startIndex = consumed, limit = PAGE_SIZE)
          }
        }
        result.onSuccess { res ->
          totalCount = res.TotalRecordCount
          val seen = queue.mapNotNull { it.Id }.toHashSet()
          queue = queue + res.Items.filter { it.Id == null || it.Id !in seen }
          consumed += res.Items.size
          if (res.Items.isEmpty()) totalCount = consumed // 没数据就停，别死循环
        }
        loadingMore = false
      }
    }

    // 滑到距末尾 3 张以内续页
    LaunchedEffect(currentPage) {
      if (queue.isNotEmpty() && currentPage >= queue.size - 3) loadMore()
    }

    // ── 幻灯片计时 ──
    //
    // ⭐⭐ **key 里绝不能有 currentPage**（这是「幻灯片只滑一半」的根因）：
    // currentPage 一变，这个协程被取消重建，而取消恰好发生在
    // `animateScrollToPage` 挂起等待动画的过程中 ⇒ 动画被腰斩，页面停在两张中间。
    // 改成「一个协程跑到底」：循环里自己 delay 到点、自己翻页。
    //
    // 「手动滑页重置计时」改在循环内部判（只有**用户**在滑才重置，幻灯片自己翻的不算），
    // 语义与原来一致：滑一下只是「这张要多看一会儿」，不停止播放。
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
            // 用户在自己滑：这张重新开始计时
            elapsed = 0
            slideshowRemaining = interval.seconds
          } else {
            elapsed += TICK_MS
            // 向上取整，让「还剩 1 秒」真的显示 1 而不是 0
            slideshowRemaining = ((totalMs - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
          }
        }
        val next = pagerState.currentPage + 1
        if (next >= queue.size) {
          slideshowPlaying = false // 播到最后一张自动停
          slideshowRemaining = 0
          break
        }
        autoScrolling = true
        // 转场：先淡出到全黑，换页（此时看不见，滑动过程被藏起来了），再淡入。
        // 只在**自动**翻页时走这条路 —— 手动左右滑就该是跟手的滑动，不该被淡入淡出打断。
        slideshowAlpha.animateTo(0f, tween(FADE_MS))
        pagerState.animateScrollToPage(next)
        slideshowAlpha.animateTo(1f, tween(FADE_MS))
        autoScrolling = false
      }
    }

    // ── 前后预加载（相邻 ±N 张）──
    //
    // N 走设置（1~3，默认 1）。提前把左右的图拉进 Coil 的缓存，滑过去时直接命中。
    //
    // 内存怎么兜的（用户问「会不会炸」）：
    //   · 图片尺寸由 URL 里的 maxWidth=2048 限制，不会拿相机原图；
    //   · Coil 的内存缓存按堆的 25% 封顶（见 EmbyPhotoImageLoader.MEMORY_PERCENT），
    //     超了自动丢最旧的 —— 所以 N 调大只是「缓存更容易被后来者顶掉」，
    //     不是无上限增长；N=3 时最多 6 张旁侧图，实测几十 MB，低内存机也不会崩。
    //
    // ⚠️ 仍坚持「用页面级 scope 发射，不在本 effect 里直接挂起下载」：
    //    本 effect 的 key 含 currentPage，翻页会取消它；预取一旦被取消，
    //    已经在途的那次下载会前功尽弃，恰好滑到那张就白一下。
    val preloadCount by browserPreferences.photoPreloadCount.collectAsState()
    val preload = preloadCount.coerceIn(1, MAX_PRELOAD)
    LaunchedEffect(currentPage, server, preload) {
      val current = server ?: return@LaunchedEffect
      for (offset in -preload..preload) {
        if (offset == 0) continue
        val item = queue.getOrNull(currentPage + offset) ?: continue
        scope.launch {
          withContext(Dispatchers.IO) {
            EmbyPhotoImageLoader.preload(
              context = context,
              url = vm.photoImageUrl(current, item, maxWidth = PHOTO_MAX_WIDTH),
              fallbackUrl = vm.photoImageUrl(
                current,
                item,
                maxWidth = PHOTO_MAX_WIDTH,
                format = "jpg",
              ),
            )
          }
        }
      }
    }

    /**
     * 保存某张照片。
     *
     * @param item 目标照片（顶部菜单传当前这张；长按菜单传长按到的那张）
     * @param asJpeg true = 另存为 JPEG（按用户当前旋转角度导出），false = 保存原图（原字节）
     */
    fun savePhoto(item: EmbyItem?, asJpeg: Boolean) {
      val current = server ?: return
      if (item == null) return
      if (saving) return
      saving = true
      // 保存位置优先级：自研选择器选的绝对路径 > 老版本的 SAF 树 URI > 系统相册
      val saveDirPath = browserPreferences.photoSaveDirPath.get().takeIf { it.isNotBlank() }
      val saveDirUri = browserPreferences.photoSaveDirUri.get().takeIf { it.isNotBlank() }
      scope.launch {
        val result = if (asJpeg) {
          EmbyPhotoSaver.saveAsJpeg(
            context = context,
            server = current,
            item = item,
            saveDirUri = saveDirUri,
            rotationDegrees = rotationOf(item),
            saveDirPath = saveDirPath,
          )
        } else {
          EmbyPhotoSaver.saveOriginal(
            context = context,
            server = current,
            item = item,
            saveDirUri = saveDirUri,
            saveDirPath = saveDirPath,
          )
        }
        saving = false
        val where = if (saveDirPath == null && saveDirUri == null) "系统相册" else "所选目录"
        val message = when (result) {
          is EmbyPhotoSaver.Result.Success -> if (asJpeg) "已保存 JPEG 到$where" else "原图已保存到$where"
          is EmbyPhotoSaver.Result.Failure -> result.reason
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    }

    /** 收藏 / 取消收藏当前这张（写服务端，失败要给提示，不能静默） */
    fun toggleFavorite(item: EmbyItem) {
      val current = server ?: return
      val target = item.UserData?.IsFavorite != true
      scope.launch {
        val ok = runCatching {
          withContext(Dispatchers.IO) { vm.toggleFavorite(current, item, target) }
        }.isSuccess
        Toast.makeText(
          context,
          if (!ok) {
            "操作失败，请检查网络"
          } else if (target) {
            "已加入收藏"
          } else {
            "已取消收藏"
          },
          Toast.LENGTH_SHORT,
        ).show()
      }
    }

    // 沉浸态下返回键先退出沉浸（再按才真的返回上一页）
    BackHandler(enabled = immersive) { immersive = false }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      if (queue.isEmpty()) {
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
                    backStack.removeLastOrNull()
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
              val progress =
                (abs(dismissOffset.value) / dismissThreshold).coerceIn(0f, 1f)
              translationY = dismissOffset.value
              scaleX = 1f - progress * 0.22f
              scaleY = scaleX
              // 下拉变暗 + 幻灯片转场淡入淡出，两者相乘（同时发生时也不会互相打架）
              alpha = (1f - progress * 0.55f) * slideshowAlpha.value
            },
        ) {
          HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> queue.getOrNull(index)?.Id ?: index },
          ) { page ->
            val item = queue.getOrNull(page)
            if (item == null) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("照片信息缺失", color = Color.White)
              }
            } else {
              val current = server
              EmbyPhotoPageImage(
                url = current?.let {
                  vm.photoImageUrl(it, item, maxWidth = PHOTO_MAX_WIDTH)
                },
                fallbackUrl = current?.let {
                  vm.photoImageUrl(it, item, maxWidth = PHOTO_MAX_WIDTH, format = "jpg")
                },
                contentDescription = item.Name,
                rotationDegrees = rotationOf(item),
                // 点一下画面 = 进出沉浸模式；长按 = 操作面板。
                // 这两个手势交给 zoomable 自己处理（见 EmbyPhotoPageImage 的注释）：
                // 外层再挂一套 tap 检测会跟「双击放大」抢事件。
                onTap = { immersive = !immersive },
                onLongPress = { longPressTarget = item },
                modifier = Modifier.fillMaxSize(),
                page = page,
                zoomRegistry = zoomRegistry,
              )
            }
          }
        }
      }

      // ── 顶栏：返回 + 标题 + 计数 + 全屏 + 旋转 + 更多 ──
      // 沉浸态整条淡出（不占手势、不挡画面），退出沉浸再淡入
      androidx.compose.animation.AnimatedVisibility(
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
          IconButton(onClick = { backStack.removeLastOrNull() }) {
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
            // 右侧留 8dp，标题与「3 / 120」计数器不贴在一起
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          if (queue.isNotEmpty()) {
            Text(
              text = "${currentPage + 1} / ${queue.size}",
              color = Color.White.copy(alpha = 0.85f),
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          // 全屏 / 退出全屏：与「点画面」同一个开关，只是给个明确的入口
          IconButton(onClick = { immersive = !immersive }) {
            Icon(
              imageVector = if (immersive) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
              contentDescription = if (immersive) "退出全屏" else "全屏",
              tint = Color.White,
            )
          }
          // 旋转 90°（顺时针）：点一次转一档，按条目记角度（翻页回来还在）。
          // 只影响显示与「另存为 JPEG」；「保存原图」永远是原字节，不跟着转。
          val rotateItem = queue.getOrNull(currentPage)
          if (rotateItem != null) {
            IconButton(
              onClick = {
                val key = rotateItem.Id ?: return@IconButton
                rotations = rotations + (key to (rotationOf(rotateItem) + 90) % 360)
              },
            ) {
              Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = "旋转 90 度",
                tint = Color.White,
              )
            }
          }
          Box {
            IconButton(onClick = { showMenu = true }) {
              Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
            }
            DropdownMenu(
              expanded = showMenu,
              onDismissRequest = { showMenu = false },
            ) {
              DropdownMenuItem(
                text = { Text("图片信息") },
                onClick = {
                  showMenu = false
                  showInfo = true
                },
              )
              DropdownMenuItem(
                text = { Text("保存原图") },
                onClick = {
                  showMenu = false
                  savePhoto(queue.getOrNull(currentPage), asJpeg = false)
                },
              )
              DropdownMenuItem(
                text = { Text("另存为 JPEG") },
                onClick = {
                  showMenu = false
                  savePhoto(queue.getOrNull(currentPage), asJpeg = true)
                },
              )
            }
          }
        }
      }

      // ── 左下角偏上：上一张 / 下一张（横向排布、半透明） ──
      // 首尾边界要「看得出来」：第一张时「上一张」置灰不可点，最后一张时「下一张」置灰 ——
      // 按钮在却点了没反应是最糟的交互（红线 29）。
      // 胶片条开着时整体上移，给底部那条缩略图让位。
      val controlsBottom = if (filmstrip) CONTROLS_BOTTOM_WITH_STRIP else CONTROLS_BOTTOM
      androidx.compose.animation.AnimatedVisibility(
        visible = !immersive && queue.size > 1,
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
            // 到队列末尾就先禁用；若服务端还有下一页，续页 effect 会把 queue 补上，
            // 补上后这个按钮自动亮起来（不是在按钮里偷偷发起加载 —— 那样会静默卡一下）
            enabled = currentPage < queue.size - 1,
            icon = Icons.Default.ChevronRight,
            contentDescription = "下一张",
            onClick = { scope.launch { pagerState.animateScrollToPage(currentPage + 1) } },
          )
        }
      }

      // ── 右下角：幻灯片播放 / 暂停（播放时显示倒计时） ──
      androidx.compose.animation.AnimatedVisibility(
        visible = !immersive && queue.size > 1,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd),
      ) {
        SlideshowButton(
          playing = slideshowPlaying,
          remainingSeconds = slideshowRemaining,
          totalSeconds = interval.seconds,
          saving = saving,
          bottomPadding = controlsBottom,
          onClick = { slideshowPlaying = !slideshowPlaying },
        )
      }

      // ── 底部胶片条：只在设置里打开时出现 ──
      androidx.compose.animation.AnimatedVisibility(
        visible = !immersive && filmstrip && queue.size > 1,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        PhotoFilmstrip(
          items = queue,
          currentPage = currentPage,
          thumbnailUrl = { item ->
            server?.let { vm.photoImageUrl(it, item, maxWidth = FILMSTRIP_WIDTH) }
          },
          fallbackUrl = { item ->
            server?.let { vm.photoImageUrl(it, item, maxWidth = FILMSTRIP_WIDTH, format = "jpg") }
          },
          onPageClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )
      }

      // ── 图片信息 ──
      if (showInfo) {
        val item = queue.getOrNull(currentPage)
        val lines = item?.let { photoInfoLines(it) } ?: emptyList()
        AlertDialog(
          onDismissRequest = { showInfo = false },
          title = { Text("图片信息") },
          text = {
            if (lines.isEmpty()) {
              Text("没有可显示的信息")
            } else {
              Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
              ) {
                lines.forEach { (label, value) ->
                  Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                      text = label,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                      text = value,
                      style = MaterialTheme.typography.bodyMedium,
                      modifier = Modifier.weight(1f),
                    )
                  }
                }
              }
            }
          },
          confirmButton = {
            TextButton(onClick = { showInfo = false }) { Text("知道了") }
          },
        )
      }

      // ── 长按操作面板 ──
      //
      // ⭐ 长按的**唯一**出口。「保存原图 / 另存为 JPEG」从旧版那个「长按直接弹的
      // 保存对话框」搬到这里的第一组 —— 功能一个没少，但不再有两个长按入口打架。
      val longPressed = longPressTarget
      if (longPressed != null) {
        PhotoActionSheet(
          title = longPressed.Name,
          favorite = longPressed.UserData?.IsFavorite == true,
          onDismiss = { longPressTarget = null },
          onSaveOriginal = {
            longPressTarget = null
            savePhoto(longPressed, asJpeg = false)
          },
          onSaveJpeg = {
            longPressTarget = null
            savePhoto(longPressed, asJpeg = true)
          },
          onToggleFavorite = {
            longPressTarget = null
            toggleFavorite(longPressed)
          },
          onRotate = {
            longPressTarget = null
            val key = longPressed.Id ?: return@PhotoActionSheet
            rotations = rotations + (key to (rotationOf(longPressed) + 90) % 360)
          },
          onShowInfo = {
            longPressTarget = null
            showInfo = true
          },
        )
      }
    }
  }

  private companion object {
    /** 浏览器续页每页条数。比网格页小：全屏大图更占内存，一页少点翻得更顺 */
    const val PAGE_SIZE = 40

    /** 幻灯片计时的检查间隔；250ms 足够让「还剩几秒」看着是整数跳变，又不至于每帧重组 */
    const val TICK_MS = 250L

    /** 幻灯片转场单向淡入 / 淡出时长（一次翻页共 2×280ms） */
    const val FADE_MS = 280

    /** 预加载张数的上限（与设置页那 1/2/3 三档一致） */
    const val MAX_PRELOAD = 3

    /** 全屏图的取图宽度。与预取用同一个值，缓存键才对得上 */
    const val PHOTO_MAX_WIDTH = 2048

    /** 胶片条缩略图的取图宽度。小图而已，别按全屏图的尺寸拉——那是几十倍的流量 */
    const val FILMSTRIP_WIDTH = 240

    /** 下拉多少 dp 算「确定要关闭」。约屏幕高度的 1/6，够明确又不至于要划很远 */
    const val DISMISS_THRESHOLD_DP = 120

    /** 悬浮控件（翻页钮 / 幻灯片钮）距底部的距离；胶片条开着时抬高给它让位 */
    val CONTROLS_BOTTOM: Dp = 96.dp
    val CONTROLS_BOTTOM_WITH_STRIP: Dp = 84.dp
  }
}

/**
 * 长按照片弹出的操作面板。
 *
 * ⚠️ **不加图标**：菜单项一律纯文字 + 可选副标题（项目 UI 纪律）。副标题只用在
 * 「两种保存方式」上 —— 这两个不解释一句，大部分人不知道有什么区别。
 *
 * 每项点完**先关面板再执行**：不关的话保存中的 Toast 会被面板盖住，
 * 而保存是几秒的异步操作，用户看到的是「点了没反应」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoActionSheet(
  title: String?,
  favorite: Boolean,
  onDismiss: () -> Unit,
  onSaveOriginal: () -> Unit,
  onSaveJpeg: () -> Unit,
  onToggleFavorite: () -> Unit,
  onRotate: () -> Unit,
  onShowInfo: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp),
    ) {
      if (!title.isNullOrBlank()) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          maxLines = 2,
        )
      }
      PhotoActionItem(
        title = "保存原图",
        subtitle = "保留 EXIF 与原始格式",
        onClick = onSaveOriginal,
      )
      PhotoActionItem(
        title = "另存为 JPEG",
        subtitle = "按当前旋转角度导出",
        onClick = onSaveJpeg,
      )
      PhotoActionItem(
        title = if (favorite) "取消收藏" else "加入收藏",
        onClick = onToggleFavorite,
      )
      PhotoActionItem(title = "旋转 90 度", onClick = onRotate)
      PhotoActionItem(title = "图片信息", onClick = onShowInfo)
    }
  }
}

@Composable
private fun PhotoActionItem(
  title: String,
  onClick: () -> Unit,
  subtitle: String? = null,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
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
  items: List<EmbyItem>,
  currentPage: Int,
  thumbnailUrl: (EmbyItem) -> String?,
  fallbackUrl: (EmbyItem) -> String?,
  onPageClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(currentPage) {
    if (currentPage in items.indices) {
      runCatching { listState.animateScrollToItem(currentPage) }
    }
  }
  LazyRow(
    state = listState,
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(bottom = 8.dp)
      .height(56.dp)
      .background(Color.Black.copy(alpha = 0.55f)),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    items(count = items.size, key = { index -> items[index].Id ?: index }) { index ->
      val item = items[index]
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
        EmbyPhotoThumbnail(
          url = thumbnailUrl(item),
          fallbackUrl = fallbackUrl(item),
          contentDescription = item.Name,
          modifier = Modifier.fillMaxSize(),
        )
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
  saving: Boolean,
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
      if (saving) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          color = Color.White,
        )
      } else if (playing) {
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
 * 半透明黑底 + 白图标：与右下角幻灯片按钮、顶栏同一套风格 —— 全屏浏览器底色是
 * 纯黑，用主题色反而会在深色照片上糊掉（本文件里所有悬浮控件都遵循这一套）。
 *
 * 到边界（第一张 / 最后一张）时**降到更低的不透明度并禁用点击**：让用户一眼看出
 * 「到头了」，而不是按了没反应（红线 29：交互里的静默无效是产品 bug）。
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
