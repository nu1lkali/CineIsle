package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.koin.compose.koinInject

/**
 * 照片全屏浏览器。
 *
 * 交互（需求锁定）：
 * - **缩放**：Telephoto（双指缩放 / 双击 / 拖拽），照片用 `ContentScale.Fit`
 *   不裁切；缩放中手势被 zoomable 消费，不会误触发翻页。
 * - **幻灯片**：右下角播放/暂停；间隔走「设置 → Emby 媒体库 → 幻灯片间隔」；
 *   **是否进页自播**走同页下方的「自动播放」开关（默认关，用户自己决定）。
 *   手动滑页只重置计时、不停止播放；播到最后一张自动停。
 *   播放期间 `keepScreenOn`，暂停/退出即解除。
 * - **左下角悬浮按钮**：横向排布的「上一张 / 下一张」半透明圆钮；
 *   第一张时上一张置灰、最后一张时下一张置灰（不留假按钮）。
 * - **旋转**：顶栏按钮每次顺时针 90°，角度**按条目**记（翻页回来还在）。
 *   只影响显示与「另存为 JPEG」；「保存原图」永远是原字节，不跟着转。
 * - **长按图片**：直接弹保存选择（保存原图 / 另存为 JPEG），不必进「更多」菜单。
 * - **前后预加载**：自动预取相邻 ±1 张（同一 URL / 同一 maxWidth，保证缓存命中）。
 * - **更多菜单**：图片信息（懒显示，只在点开时读当前条目的 EXIF 字段）/
 *   保存原图 / 另存为 JPEG。保存位置走「设置 → Emby 媒体库 → 照片保存位置」。
 * - **续页**：滑到距队列末尾 3 张以内自动拉下一页（StartIndex 用独立
 *   [consumed] 计数，不用队列 size）。
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

    // ── 旋转 ──
    // 角度按**条目 Id** 记（不是按页码）：续页追加、来回翻页都不会丢。
    // 只影响显示与「另存为 JPEG」——「保存原图」写的是原字节，不能动。
    var rotations by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    /** 某张照片当前应显示的旋转角（顺时针，0/90/180/270） */
    fun rotationOf(item: EmbyItem?): Int = item?.Id?.let { rotations[it] } ?: 0

    /** 长按命中的条目；非空时弹「保存」选择框 */
    var longPressTarget by remember { mutableStateOf<EmbyItem?>(null) }

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
    // currentPage 进 key：手动滑页会重启本 effect ⇒ **重置计时但不停止播放**
    // （这正是需求：滑一下只是「这张要多看一会儿」）。播到最后一张自动停。
    LaunchedEffect(currentPage, slideshowPlaying, interval) {
      if (!slideshowPlaying) return@LaunchedEffect
      while (true) {
        delay(interval.seconds * 1000L)
        val next = currentPage + 1
        if (next >= queue.size) {
          slideshowPlaying = false
          break
        }
        pagerState.animateScrollToPage(next)
      }
    }

    // ── 前后预加载（相邻 ±1 张）──
    // 提前把左右两张拉进 EmbyImageLoader 的内存/磁盘缓存，滑过去时直接命中，不白屏。
    //
    // 只预取 ±1 是刻意的：全屏图按 maxWidth=2048 解码，一张位图十几 MB，
    // 预取更多只会把 LRU 里其它可见内容顶出去（越预越卡）。
    // EmbyImageLoader 自带并发闸门（同时最多 4 个下载）与同 URL 在途合并，
    // 这些预取不会把服务器打爆 —— 也是「预加载」和「一次性拉爆」的分界线。
    // ⚠️ URL 必须与展示时**逐字节相同**（同一个 vm.photoImageUrl + 同一个 maxWidth），
    //    否则缓存键对不上，预取等于白做。
    //
    // ⚠️ 预取**不在 LaunchedEffect 里直接挂起下载**，而是用页面级 scope 发射：
    //    本 effect 的 key 是 currentPage，翻页会取消上一次的协程；而预取一旦被
    //    取消，它已经在途的那次下载会被判空（见 EmbyImageLoader.downloadBytes 的
    //    取消分支），恰好滑到那张就白一下 —— 正是要避免的「刷新空占位」。
    //    用 scope.launch 发射后，翻页不打断在途预取；离开本页时才随组合一起取消。
    LaunchedEffect(currentPage, server) {
      val current = server ?: return@LaunchedEffect
      for (offset in intArrayOf(-1, 1)) {
        val item = queue.getOrNull(currentPage + offset) ?: continue
        scope.launch {
          withContext(Dispatchers.IO) {
            val primary = vm.photoImageUrl(current, item, maxWidth = 2048)
            val loaded = primary != null && EmbyImageLoader.load(primary, 2048) != null
            if (!loaded) {
              // 原格式本地解不了（HEIC/AVIF）时把「服务端转码成 JPEG」那张也预取，
              // 否则真滑过去还是要现等一次网络
              vm.photoImageUrl(current, item, maxWidth = 2048, format = "jpg")?.let {
                EmbyImageLoader.load(it, 2048)
              }
            }
          }
        }
      }
    }

    /**
     * 保存某张照片。
     *
     * @param item 目标照片（顶部菜单传当前这张；长按保存传长按到的那张）
     * @param asJpeg true = 另存为 JPEG（按用户当前旋转角度导出），false = 保存原图（原字节）
     */
    fun savePhoto(item: EmbyItem?, asJpeg: Boolean) {
      val current = server ?: return
      if (item == null) return
      if (saving) return
      saving = true
      // 保存位置：用户在设置里选过目录就写那儿，否则进系统相册
      val saveDir = browserPreferences.photoSaveDirUri.get().takeIf { it.isNotBlank() }
      scope.launch {
        val result = if (asJpeg) {
          EmbyPhotoSaver.saveAsJpeg(context, current, item, saveDir, rotationOf(item))
        } else {
          EmbyPhotoSaver.saveOriginal(context, current, item, saveDir)
        }
        saving = false
        val where = if (saveDir == null) "系统相册" else "所选目录"
        val message = when (result) {
          is EmbyPhotoSaver.Result.Success -> if (asJpeg) "已保存 JPEG 到$where" else "原图已保存到$where"
          is EmbyPhotoSaver.Result.Failure -> result.reason
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    }

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
            // 缩放状态按页 remember：翻页后回到上一页仍是「未缩放」的自然状态
            val zoomableState = rememberZoomableState()
            // 照片不裁切：内容按 Fit 排，Letterbox 区域就是页面黑底
            zoomableState.contentScale = ContentScale.Fit
            val current = server
            val degrees = rotationOf(item)
            Box(
              modifier = Modifier
                .fillMaxSize()
                // 长按保存：放在最外层。子级的 zoomable 只消费拖拽 / 双击，
                // 静止长按不会被它吃掉（真机若发现冲突，见这里）。
                .pointerInput(item.Id) {
                  detectTapGestures(onLongPress = { longPressTarget = item })
                },
              contentAlignment = Alignment.Center,
            ) {
              EmbyImage(
                url = current?.let { vm.photoImageUrl(it, item, maxWidth = 2048) },
                fallbackUrl = current?.let {
                  vm.photoImageUrl(it, item, maxWidth = 2048, format = "jpg")
                },
                contentDescription = item.Name,
                modifier = Modifier
                  .fillMaxSize()
                  // 旋转放在 zoomable **之前**（外层）：转的是整个画面，
                  // 转完再缩放，手势方向与看到的画面一致。
                  // Fit 之后旋转 90° 的包围盒仍然装得下（拟合宽高互换后
                  // 依旧同时 <= 屏宽与屏高），不会把画面裁掉。
                  .rotate(degrees.toFloat())
                  .zoomable(zoomableState),
                contentScale = ContentScale.Fit,
                maxWidth = 2048,
              )
            }
          }
        }
      }

      // ── 顶栏：返回 + 标题 + 计数 + 更多 ──
      Row(
        modifier = Modifier
          .align(Alignment.TopCenter)
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
          modifier = Modifier.weight(1f),
        )
        if (queue.isNotEmpty()) {
          Text(
            text = "${currentPage + 1} / ${queue.size}",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
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

      // ── 左下角偏上：上一张 / 下一张（横向排布、半透明） ──
      // 首尾边界要「看得出来」：第一张时「上一张」置灰不可点，最后一张时「下一张」置灰 ——
      // 按钮在却点了没反应是最糟的交互（红线 29）。
      if (queue.size > 1) {
        Row(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 20.dp, bottom = 96.dp),
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

      // ── 右下角：幻灯片播放 / 暂停 ──
      if (queue.size > 1) {
        Surface(
          onClick = { slideshowPlaying = !slideshowPlaying },
          shape = CircleShape,
          color = Color.Black.copy(alpha = 0.45f),
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 20.dp, bottom = 28.dp)
            .size(52.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (saving) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
              )
            } else {
              Icon(
                imageVector = if (slideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (slideshowPlaying) "暂停幻灯片" else "播放幻灯片",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
              )
            }
          }
        }
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

      // ── 长按保存：在图片上长按弹出，两个选项与「更多」菜单里的一致 ──
      val longPressed = longPressTarget
      if (longPressed != null) {
        AlertDialog(
          onDismissRequest = { longPressTarget = null },
          title = { Text("保存这张照片") },
          text = {
            Text("「保存原图」保留 EXIF 与原始格式；「另存为 JPEG」按当前旋转角度导出，转过图就存转过的样子。")
          },
          dismissButton = {
            TextButton(
              onClick = {
                longPressTarget = null
                savePhoto(longPressed, asJpeg = true)
              },
            ) { Text("另存为 JPEG") }
          },
          confirmButton = {
            TextButton(
              onClick = {
                longPressTarget = null
                savePhoto(longPressed, asJpeg = false)
              },
            ) { Text("保存原图") }
          },
        )
      }
    }
  }

  private companion object {
    /** 浏览器续页每页条数。比网格页小：全屏大图更占内存，一页少点翻得更顺 */
    const val PAGE_SIZE = 40
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
