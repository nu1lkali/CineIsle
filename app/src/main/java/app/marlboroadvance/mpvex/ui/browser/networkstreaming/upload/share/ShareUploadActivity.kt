package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops.NetworkFolderPickerSheet
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.NetworkUploadCenter
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.TransferPane
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.UploadPick
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album.AlbumImageLoader
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview.MediaPreviewDialog
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview.PreviewDeck
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview.PreviewItem
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview.PreviewKind
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.preview.previewKindOf
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 「从系统相册 / 文件管理器分享」→ 上传到 AList。
 *
 * ## 为什么是一个独立 Activity 而不是复用浏览页
 *
 * 分享进来的入口和浏览页**没有共同的上下文**：用户此刻不在某台服务器上、也不在
 * 某个目录里（App 可能整个是刚被拉起来的）。浏览页的 ViewModel 是按「连接 + 路径」
 * 建的，硬要复用它就得先替用户猜一台服务器、猜一个目录 —— 猜错比让用户选一次更糟。
 *
 * ## 三个必须在这里做对的地方
 *
 * 1. **解析必须在 `onCreate` 里当场做完**：分享进来的 `content://` 读权限是绑在
 *    这个 Activity 上的（AMS 按 ActivityRecord 记），Activity 一销毁权限就释放。
 *    拖到协程里「稍后解析」会在界面上留下一个再也不会成功的空列表。
 * 2. **上传途中不 finish**：进程级的上传队列活得比 Activity 长，但这些 URI 的
 *    读权限不 —— 传 2GB 的视频时用户按返回，`finish()` 之后剩下几个文件打开流
 *    就会直接 `SecurityException`（表现为「传到一半全变失败」）。所以上传期间
 *    返回键改成 [moveTaskToBack]：任务进后台，Activity 还在，权限还在。
 * 3. **目标位置要记住**：分享上传是冷启动入口，每次都要重选连接 + 点进目录的话，
 *    这个功能就没有存在意义了。记忆见
 *    [BrowserPreferences.shareUploadConnectionId] / `shareUploadDir`。
 */
class ShareUploadActivity : ComponentActivity(), KoinComponent {
  private val repository: NetworkRepository by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()

  /** 解析结果。[SharedMedia] 只认 URI，见 [SharedUploadIntent] */
  private val sharedMedia = mutableStateOf<List<SharedMedia>>(emptyList())

  /** 上传是否在进行中 —— 只用来决定「返回键是退到后台还是关页面」 */
  private val uploadRunning = mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ⚠️ 立刻解析：见类注释第 1 条
    sharedMedia.value = SharedUploadIntent.parse(this, intent)

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (uploadRunning.value) {
            // 队列活在进程上，退到后台它照跑；这里绝不 finish（见类注释第 2 条）
            moveTaskToBack(true)
            Toast.makeText(
              this@ShareUploadActivity,
              "上传在后台继续，可从最近任务回到这里",
              Toast.LENGTH_SHORT,
            ).show()
          } else {
            finish()
          }
        }
      },
    )

    setContent {
      val dark by appearancePreferences.darkMode.collectAsState()
      val systemDark = isSystemInDarkTheme()
      val isDark = dark == DarkMode.Dark || (dark == DarkMode.System && systemDark)

      enableEdgeToEdge(
        SystemBarStyle.auto(
          lightScrim = Color.White.toArgb(),
          darkScrim = Color.Transparent.toArgb(),
        ) { isDark },
      )

      MpvexTheme {
        ShareUploadScreen(
          media = sharedMedia.value,
          repository = repository,
          preferences = browserPreferences,
          onRunningChange = { uploadRunning.value = it },
          onFinish = { finish() },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // 再分享一批：权限窗口同样是「这一刻」，所以也当场解析
    sharedMedia.value = SharedUploadIntent.parse(this, intent)
  }

  override fun finish() {
    // 收尾清掉已完成批次的记录：下次从相册分享进来不该看到上一批的进度页
    NetworkUploadCenter.clearFinished()
    super.finish()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareUploadScreen(
  media: List<SharedMedia>,
  repository: NetworkRepository,
  preferences: BrowserPreferences,
  onRunningChange: (Boolean) -> Unit,
  onFinish: () -> Unit,
) {
  val context = LocalContext.current

  // ── 连接列表（只认 AList：只有它有写接口，见 NetworkUploadCenter.runBatch）──
  var connections by remember { mutableStateOf<List<NetworkConnection>?>(null) }
  LaunchedEffect(Unit) {
    connections =
      runCatching { repository.getAllConnections().first() }
        .getOrDefault(emptyList())
        .filter { it.protocol == NetworkProtocol.ALIST }
  }

  val savedConnectionId = remember { preferences.shareUploadConnectionId.get() }
  val savedDir = remember { preferences.shareUploadDir.get() }
  var connectionId by remember { mutableStateOf<Long?>(null) }
  var targetDir by remember { mutableStateOf("") }

  // 连接列表到手后落一次默认选择：上次用过的那台优先，没有就用第一台
  LaunchedEffect(connections) {
    val list = connections ?: return@LaunchedEffect
    if (connectionId != null) return@LaunchedEffect
    val preferred = list.firstOrNull { it.id == savedConnectionId } ?: list.firstOrNull()
    connectionId = preferred?.id
    // 目录是「某台服务器上的路径」，换了服务器就必须丢掉，否则会指到不存在的地方
    targetDir = if (preferred != null && preferred.id == savedConnectionId) savedDir else ""
  }

  val connection = connections?.firstOrNull { it.id == connectionId }

  // ── 批次（进程级队列的观察者）──
  var batch by remember { mutableStateOf(NetworkUploadCenter.batch.value) }
  LaunchedEffect(Unit) { NetworkUploadCenter.batch.collect { batch = it } }

  /** 本页开出去的那一批。别的批次（浏览页开的）不该占着这一屏 */
  var startedBatchId by remember { mutableStateOf<Long?>(null) }
  val mine = batch?.takeIf { it.id == startedBatchId }

  LaunchedEffect(mine?.running) { onRunningChange(mine?.running == true) }

  var showConnectionPicker by remember { mutableStateOf(false) }
  var showFolderPicker by remember { mutableStateOf(false) }

  /**
   * 这次分享里**能预览**的那些项（图片 / 视频 / 音频）。
   *
   * 只有它们进 deck：pdf、字幕、压缩包之类没有预览器，进了 deck 就会翻到一屏
   * 「Failed to create image decoder」/空白 —— 那比不提供预览更让人困惑。
   */
  val deckItems =
    remember(media) {
      media.mapNotNull { item ->
        previewKindOf(item.name, item.mimeType)?.let { kind ->
          PreviewItem(uri = item.uri, kind = kind, title = item.name)
        }
      }
    }
  val previewableCount = deckItems.size
  var previewDeck by remember { mutableStateOf<PreviewDeck?>(null) }

  // 已经开传：整屏交给进度页（它自带标题行与关闭键，不再套一层 Scaffold）
  if (mine != null) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.statusBarsPadding()) {
        TransferPane(
          batch = mine,
          // 分享进来的这一批没有「选择器」可回，所以不给「继续选择」
          onBack = null,
          onClose = onFinish,
        )
      }
    }
    return
  }

  val totalBytes = remember(media) { media.sumOf { it.sizeBytes.coerceAtLeast(0L) } }
  val subtitle =
    if (media.isEmpty()) {
      "没有可上传的文件"
    } else {
      "${media.size} 个文件 · ${MediaFormatUtils.formatFileSize(totalBytes)}"
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "上传到网络位置",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = subtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onFinish) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
      )
    },
    bottomBar = {
      if (media.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (targetDir.isEmpty()) "目标 · 根目录" else "目标 · /$targetDir",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.height(2.dp))
              Text(
                text = connection?.name ?: "未选择网络位置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(Modifier.width(12.dp))
            Button(
              onClick = {
                val target = connection ?: return@Button
                preferences.shareUploadConnectionId.set(target.id)
                preferences.shareUploadDir.set(targetDir)
                NetworkUploadCenter.start(
                  context = context,
                  connectionId = target.id,
                  targetDir = targetDir,
                  targetLabel = targetLabel(target, targetDir),
                  picks =
                    media.map {
                      UploadPick(
                        file = null,
                        relativePath = it.name,
                        contentUri = it.uri,
                        sizeHint = it.sizeBytes,
                      )
                    },
                )
                startedBatchId = NetworkUploadCenter.batch.value?.id
              },
              enabled = connection != null,
            ) {
              Text("上传")
            }
          }
        }
      }
    },
  ) { padding ->
    if (media.isEmpty()) {
      Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
        EmptySharedState(
          modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
          onFinish = onFinish,
        )
      }
    } else {
      // 一批文件用**竖列表**而不是一排缩略图：文件名、类型、大小三样都得看见 ——
      // 横滑只有图、看不出哪一个是哪一个（音频更是只剩一个图标）。
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
      ) {
        item {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "待上传 ${media.size} 项",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
              text =
                if (previewableCount > 0) "点击可预览" else MediaFormatUtils.formatFileSize(totalBytes),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        itemsIndexed(items = media, key = { _, item -> item.uri.toString() }) { _, item ->
          SharedMediaRow(
            media = item,
            onPreview = {
              val deckIndex = deckItems.indexOfFirst { it.uri == item.uri }
              if (deckIndex >= 0) previewDeck = PreviewDeck(deckItems, deckIndex)
            },
          )
        }

        item {
          Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
              text = "目标位置",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SettingRow(
              icon = Icons.Default.Storage,
              title = "网络位置",
              value = connection?.let { "${it.name} · ${it.host}:${it.port}" } ?: "未选择",
              enabled = !connections.isNullOrEmpty(),
              onClick = { showConnectionPicker = true },
            )
            Spacer(Modifier.height(10.dp))
            SettingRow(
              icon = Icons.Default.Folder,
              title = "目标文件夹",
              value = if (targetDir.isEmpty()) "根目录" else "/$targetDir",
              enabled = connection != null,
              onClick = { showFolderPicker = true },
            )

            // 一台 AList 都没配：把「为什么不能传、去哪儿配」说清楚，别只留一个灰按钮
            if (connections != null && connections!!.isEmpty()) {
              Spacer(Modifier.height(16.dp))
              NoConnectionHint()
            }
          }
        }
      }
    }
  }

  previewDeck?.let { deck ->
    MediaPreviewDialog(deck = deck, onDismiss = { previewDeck = null })
  }

  if (showConnectionPicker && !connections.isNullOrEmpty()) {
    ConnectionPickerSheet(
      connections = connections!!,
      selectedId = connectionId,
      onPick = { picked ->
        if (picked.id != connectionId) {
          connectionId = picked.id
          // 换服务器 ⇒ 目录作废（同一路径在另一台上可能压根不存在）
          targetDir = ""
        }
        showConnectionPicker = false
      },
      onDismiss = { showConnectionPicker = false },
    )
  }

  if (showFolderPicker && connection != null) {
    NetworkFolderPickerSheet(
      title = "选择目标文件夹",
      confirmLabel = "上传到这里",
      startPath = targetDir,
      listDir = { path ->
        runCatching { repository.clientFor(connection).listFiles(path) }
          .getOrElse { Result.failure(it) }
      },
      onDismiss = { showFolderPicker = false },
      onConfirm = { picked ->
        targetDir = picked.trim('/')
        showFolderPicker = false
      },
    )
  }
}

/** 进度页标题行里的「目标 · 名字」：连接名 + 目录末级（整条路径太长，标题行会被吃掉） */
private fun targetLabel(connection: NetworkConnection, dir: String): String {
  val leaf = dir.trim('/').substringAfterLast('/')
  return if (leaf.isEmpty()) connection.name else "${connection.name} · $leaf"
}

/**
 * 一张「设置行」卡片：图标 + 标题 + 当前值 + 箭头。
 *
 * 为什么不用两个下拉框：分享场景里只做两个决定（传哪儿、传到这台机器的哪一层），
 * 用两行可点的卡片能一眼看到**当前值**，下拉框只显示标题、要点开才知道选的是谁。
 */
@Composable
private fun SettingRow(
  icon: ImageVector,
  title: String,
  value: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val alpha = if (enabled) 1f else 0.45f
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = value,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
      )
    }
  }
}

/**
 * 待上传列表里的一行：封面 + 文件名 + 类型/大小 + 预览按钮。
 *
 * ## 为什么这行必须显示文件名
 *
 * 分享进来的一批里常常有好几个 `IMG_2026…`、`VID_…`，光看缩略图根本分不清哪一个是
 * 哪一个（音频更极端：只有一枚图标）。名称 + 类型 + 大小三样凑齐，才谈得上「确认一下
 * 传的是不是这些」。
 *
 * ## 为什么整行可点、右侧还留一个眼睛按钮
 *
 * 整行可点是为了「顺手看一下」，眼睛按钮是为了**让人知道能看** —— 之前分享页
 * 根本没有预览，用户不会想到去点一行文字。不支持预览的格式（pdf / 字幕 / 压缩包）
 * 既不给按钮也不响应点击：点了没反应比没有入口更糟。
 */
@Composable
private fun SharedMediaRow(media: SharedMedia, onPreview: () -> Unit) {
  val kind = previewKindOf(media.name, media.mimeType)
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .then(if (kind != null) Modifier.clickable(onClick = onPreview) else Modifier)
        .padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    MediaThumb(media = media, kind = kind, size = 48.dp)
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = media.name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = metaLine(media, kind),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (kind != null) {
      Spacer(Modifier.width(8.dp))
      IconButton(onClick = onPreview, modifier = Modifier.size(40.dp)) {
        Icon(
          imageVector = Icons.Default.Visibility,
          contentDescription = "预览",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 一行的副标题：`视频 · 12.4 MB` / `文件 · PDF · 1.2 MB`。
 *
 * 有预览器的格式不必再报扩展名（`视频 · MP4` 是重复信息）；没有预览器的反而要说
 * 清是什么文件，好让用户理解「这个确实没法预览」。
 */
private fun metaLine(media: SharedMedia, kind: PreviewKind?): String =
  buildString {
    append(
      when (kind) {
        PreviewKind.Video -> "视频"
        PreviewKind.Audio -> "音频"
        PreviewKind.Image -> "图片"
        null -> "文件"
      },
    )
    if (kind == null) {
      val ext = media.name.substringAfterLast('.', "").uppercase()
      if (ext.isNotEmpty()) {
        append(" · ")
        append(ext)
      }
    }
    if (media.sizeBytes > 0) {
      append(" · ")
      append(MediaFormatUtils.formatFileSize(media.sizeBytes))
    }
  }

/**
 * 分享项封面。
 *
 * 走 [AlbumImageLoader]（已注册 `VideoFrameDecoder`）：视频的 `content://` 用普通
 * 解码器只会得到一堆失败 —— 相册格子、上传面板与这里共用同一个 Loader 实例，
 * 缩略图缓存也共享。
 *
 * 图片/视频出画面；音频与其它格式只画图标（把它们交给 Coil 就是那条
 * `Failed to create image decoder`），但**仍然占满同样的格子**，列表的左边线才整齐。
 */
@Composable
private fun MediaThumb(media: SharedMedia, kind: PreviewKind?, size: Dp) {
  val context = LocalContext.current
  val loader = remember(context) { AlbumImageLoader.get(context) }

  Box(
    modifier =
      Modifier.size(size)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    when (kind) {
      PreviewKind.Image, PreviewKind.Video ->
        AsyncImage(
          model = media.uri,
          contentDescription = media.name,
          imageLoader = loader,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      PreviewKind.Audio ->
        Icon(
          imageVector = Icons.Default.AudioFile,
          contentDescription = media.name,
          modifier = Modifier.size(size * 0.5f),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      null ->
        Icon(
          imageVector = Icons.Default.Description,
          contentDescription = media.name,
          modifier = Modifier.size(size * 0.45f),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (kind == PreviewKind.Video) {
      // 黑底白三角：底下是画面而不是 UI 表面，所以不跟随主题
      Box(
        modifier =
          Modifier.size(size * 0.36f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(size * 0.25f),
          tint = Color.White,
        )
      }
    }
  }
}

/** 一项都没解析出来 —— 说清楚是「来源 App 没带上文件」，而不是让用户对着空屏猜 */
@Composable
private fun EmptySharedState(modifier: Modifier = Modifier, onFinish: () -> Unit) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Default.CloudOff,
      contentDescription = null,
      modifier = Modifier.size(40.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(text = "没有可上传的文件", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
      text = "来源应用没有把文件一起分享过来（有些应用只分享文字或链接）。可以回到相册重新选择后分享。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onFinish) { Text("关闭") }
  }
}

@Composable
private fun NoConnectionHint() {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(text = "还没有 AList 连接", style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.height(4.dp))
      Text(
        text = "上传需要一台 AList 服务器（只有它的 API 有写接口）。请先在「网络」里添加并测试通过，再回来分享。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 选网络位置（只列 AList）。
 *
 * 不用下拉菜单：连接可能有好几台，带 `host:port` 副标题的抽屉更容易分辨
 * 「家里那台」和「公司那台」——两者名字常常一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPickerSheet(
  connections: List<NetworkConnection>,
  selectedId: Long?,
  onPick: (NetworkConnection) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
      Text(
        text = "选择网络位置",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      connections.forEach { item ->
        val selected = item.id == selectedId
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clickable { onPick(item) }
              .padding(horizontal = 24.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint =
              if (selected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = item.name,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${item.host}:${item.port}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (selected) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}
