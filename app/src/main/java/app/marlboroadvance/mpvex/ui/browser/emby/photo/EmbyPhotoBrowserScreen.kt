package app.marlboroadvance.mpvex.ui.browser.emby.photo

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.rememberPredictivePreloadDistance
import app.marlboroadvance.mpvex.ui.browser.photo.PhotoViewerEntry
import app.marlboroadvance.mpvex.ui.browser.photo.SharedPhotoViewer
import app.marlboroadvance.mpvex.ui.browser.photo.formatPhotoFileSize
import app.marlboroadvance.mpvex.ui.browser.photo.rememberPhotoRotationState
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 照片全屏浏览器（Emby）。
 *
 * **外壳来自 [SharedPhotoViewer]**（与网络位置的 [app.marlboroadvance.mpvex.ui.browser
 * .networkstreaming.NetworkPhotoViewerScreen] 共用同一份实现）：翻页、沉浸、下拉关闭、
 * 幻灯片、胶片条、上一张/下一张、顶栏。改那些交互只需要改共享组件，两个入口同时生效。
 * 本文件只剩 Emby 特有的那一层：从服务端取图（`photoImageUrl` 的缩放 / JPEG 兜底）、
 * 长按操作面板、旋转、收藏、续页、记住浏览位置。
 *
 * 交互（需求锁定）：
 * - **缩放**：Telephoto（双指缩放 / 双击 / 拖拽），照片用 `ContentScale.Fit`
 *   不裁切；缩放中手势被 zoomable 消费，不会误触发翻页。最大 4×，越界橡皮筋回弹。
 * - **下拉关闭**：没放大的照片往下拖，整页跟手位移 + 缩小 + 变暗；拖过阈值松手就退出，
 *   没过就弹回去。⭐ 放大状态下不抢这个手势 —— 那时竖向拖动是「平移画面」，
 *   判据读 [PhotoZoomRegistry.zoomFractionOf]（不是 Compose State，所以不产生重组）。
 * - **沉浸（全屏）**：点画面任意处切换 —— 隐藏顶栏、翻页钮、幻灯片钮，只留照片；
 *   再点一次退出。顶栏也有一个入口（外壳画的「全屏」钮）。
 *   沉浸态下返回键先退出沉浸，再按才真的返回（不会「一键退出」）。
 * - **长按**：弹**底部操作面板**（保存原图 / 另存为 JPEG / 收藏 / 旋转 / 图片信息）。
 *   ⚠️ 长按只有这一个出口 —— 旧版「长按直接弹保存对话框」已收编进面板第一组，
 *   两个长按入口并存会让用户不知道长按到底会弹什么。
 * - **幻灯片**：右下角播放/暂停；间隔走「设置 → Emby 照片浏览与保存 → 幻灯片间隔」；
 *   **是否进页自播**走同页下方的「自动播放」开关（默认关，用户自己决定）。
 *   手动滑页只重置计时、不停止播放；播到最后一张自动停。
 *   **转场是淡出→换页→淡入**（不再是硬切），播放期间按钮上显示**剩余秒数**（圆环 + 数字）。
 *   播放期间 `keepScreenOn`，暂停/退出即解除。
 * - **底部胶片条**：设置开关（默认关），开着时底部多一条缩略图，点一下跳到那张。
 * - **左下角悬浮按钮**：横向排布的「上一张 / 下一张」半透明圆钮；
 *   第一张时上一张置灰、最后一张时下一张置灰（不留假按钮）。
 * - **底部信息栏**：文件名 + 「大小 · 日期」+ 序号，两个入口都有。
 * - **旋转**：顶栏按钮每次顺时针 90°，角度**按条目 Id** 记（翻页回来还在）。
 *   按钮由共享外壳画（[app.marlboroadvance.mpvex.ui.browser.photo.PhotoRotationState] 持有角度），
 *   长按面板的「旋转 90 度」走同一个入口。
 *   只影响显示与「另存为 JPEG」；「保存原图」永远是原字节，不跟着转。
 * - **前后预加载**：预取相邻 ±N 张（N 走「设置 → Emby 照片浏览与保存 → 照片预加载张数」，1~3）。
 * - **更多菜单**：图片信息（懒显示，只在点开时读当前条目的 EXIF 字段）/
 *   保存原图 / 另存为 JPEG。保存位置走「设置 → Emby 照片浏览与保存 → 照片保存位置」。
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

    // 幻灯片 / 沉浸 / 下拉关闭 / 胶片条 / 信息栏 / 顶栏的全屏与旋转钮
    // 全部在 [SharedPhotoViewer] 里（与网络位置的照片浏览器共用一份实现），
    // 这一层只留 Emby 特有的东西：保存、长按操作面板、续页、收藏、取图 URL。
    var saving by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage

    // 进页是否自播幻灯片：读全局开关（默认关）**一次**，之后改开关只影响新打开的浏览器
    val autoPlaySlideshow = remember { browserPreferences.photoAutoSlideshow.get() }

    // 交给共享外壳的最小信息：稳定 key + 标题 + 附注（大小 · 日期）。
    // 附注是 补的：信息栏现在两个入口都显示，Emby 这边不能留一行空白
    //   —— 同一套外壳，数据各填各的。日期取 DateCreated（拍摄/入库时间）前 10 位。
    val entries = remember(queue) {
      queue.map { item ->
        PhotoViewerEntry(
          key = item.Id ?: item.Name.orEmpty(),
          title = item.Name.orEmpty(),
          meta = listOfNotNull(
            item.Size?.takeIf { it > 0 }?.let { formatPhotoFileSize(it) },
            item.DateCreated?.takeIf { it.isNotBlank() }?.take(10),
          ).joinToString(" · ").takeIf { it.isNotBlank() },
        )
      }
    }

    // ── 旋转 ──
    // 角度按**条目 Id** 记（不是按页码）：续页追加、来回翻页都不会丢。
    // 状态实例与共享外壳**共用同一个**：顶栏旋转钮在外壳里画，这里的
    // 「另存为 JPEG」导出角度、长按面板的「旋转 90 度」读写的必须是同一份 ——
    // 各存一份的后果是「顶栏转过了，导出还是正的」。
    val rotation = rememberPhotoRotationState()

    /** 旋转状态的 key（与 entries 里的 key 完全一致，否则两边会记到不同槽位） */
    fun rotationKeyOf(item: EmbyItem): Any = item.Id ?: item.Name.orEmpty()

    /** 某张照片当前应显示的旋转角（顺时针，0/90/180/270） */
    fun rotationOf(item: EmbyItem?): Int = item?.let { rotation.of(rotationKeyOf(it)) } ?: 0

    /** 长按命中的条目；非空时弹底部操作面板 */
    var longPressTarget by remember { mutableStateOf<EmbyItem?>(null) }

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

    // 滑到距末尾 prefetchDistance 张以内续页（「预测性懒加载」开关：开 ≈ 一屏 / 关 ≈ 贴近末尾）
    val prefetchDistance = rememberPredictivePreloadDistance(browserPreferences)
    LaunchedEffect(currentPage, prefetchDistance) {
      if (queue.isNotEmpty() && currentPage >= queue.size - prefetchDistance) loadMore()
    }

    // ── 前后预加载（相邻 ±N 张）──
    // N 走设置（1~3，默认 1）。提前把左右的图拉进 Coil 的缓存，滑过去时直接命中。
    // 内存怎么兜的（用户问「会不会炸」）：
    //   · 图片尺寸由 URL 里的 maxWidth=2048 限制，不会拿相机原图；
    //   · Coil 的内存缓存按堆的 25% 封顶（见 EmbyPhotoImageLoader.MEMORY_PERCENT），
    //     超了自动丢最旧的 —— 所以 N 调大只是「缓存更容易被后来者顶掉」，
    //     不是无上限增长；N=3 时最多 6 张旁侧图，几十 MB，低内存机也不会崩。
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

    SharedPhotoViewer(
      entries = entries,
      pagerState = pagerState,
      title = title,
      autoPlaySlideshow = autoPlaySlideshow,
      rotation = rotation,
      onBack = { backStack.removeLastOrNull() },
      topBarActions = { chrome ->
        // ⚠️ 全屏 / 旋转**已经由外壳画**。
        //    这里只放「更多」——以前这两个按钮写在本文件里，直接导致网络版没有。
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
                savePhoto(queue.getOrNull(chrome.currentIndex), asJpeg = false)
              },
            )
            DropdownMenuItem(
              text = { Text("另存为 JPEG") },
              onClick = {
                showMenu = false
                savePhoto(queue.getOrNull(chrome.currentIndex), asJpeg = true)
              },
            )
          }
        }
      },
      renderPage = { index, zoomRegistry, rotationDegrees, onTap ->
        val item = queue.getOrNull(index)
        if (item != null) {
          val current = server
          EmbyPhotoPageImage(
            url = current?.let { vm.photoImageUrl(it, item, maxWidth = PHOTO_MAX_WIDTH) },
            fallbackUrl = current?.let {
              vm.photoImageUrl(it, item, maxWidth = PHOTO_MAX_WIDTH, format = "jpg")
            },
            contentDescription = item.Name,
            // 角度由外壳按 key 取出（顶栏旋转钮改的就是它），页面自己应用
            rotationDegrees = rotationDegrees,
            // 点一下画面 = 进出沉浸；长按 = 操作面板。
            // 这两个手势交给 zoomable 自己处理（见 EmbyPhotoPageImage 的注释）：
            // 外层再挂一套 tap 检测会跟「双击放大」抢事件。
            onTap = onTap,
            onLongPress = { longPressTarget = item },
            modifier = Modifier.fillMaxSize(),
            page = index,
            zoomRegistry = zoomRegistry,
          )
        }
      },
      renderThumbnail = { index, modifier ->
        val item = queue.getOrNull(index)
        if (item != null) {
          EmbyPhotoThumbnail(
            url = server?.let { vm.photoImageUrl(it, item, maxWidth = FILMSTRIP_WIDTH) },
            fallbackUrl = server?.let {
              vm.photoImageUrl(it, item, maxWidth = FILMSTRIP_WIDTH, format = "jpg")
            },
            contentDescription = item.Name,
            modifier = modifier,
          )
        }
      },
    )

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
      // 长按的**唯一**出口。「保存原图 / 另存为 JPEG」从旧版那个「长按直接弹的
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
            // 与顶栏旋转钮同一个动作入口（外壳持有的那份状态），别在这里另存一份角度
            rotation.rotate(rotationKeyOf(longPressed))
          },
          onShowInfo = {
            longPressTarget = null
            showInfo = true
          },
        )
      }

  }

  private companion object {
    /** 浏览器续页每页条数。比网格页小：全屏大图更占内存，一页少点翻得更顺 */
    const val PAGE_SIZE = 40

    /** 预加载张数的上限（与设置页那 1/2/3 三档一致） */
    const val MAX_PRELOAD = 3

    /** 全屏图的取图宽度。与预取用同一个值，缓存键才对得上 */
    const val PHOTO_MAX_WIDTH = 2048

    /** 胶片条缩略图的取图宽度。小图而已，别按全屏图的尺寸拉——那是几十倍的流量 */
    const val FILMSTRIP_WIDTH = 240
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
