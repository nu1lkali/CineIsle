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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
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
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      if (media.isEmpty()) {
        EmptySharedState(
          modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
          onFinish = onFinish,
        )
        return@Column
      }

      // ── 这批文件（缩略图）──
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = media, key = { it.uri.toString() }) { item ->
          MediaThumb(media = item, size = 84.dp)
        }
      }

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

      Spacer(Modifier.height(24.dp))
    }
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
 * 分享项缩略图。
 *
 * 走 [AlbumImageLoader]（已注册 `VideoFrameDecoder`）：视频的 `content://` 用普通
 * 解码器只会得到一堆失败 —— 相册格子与这里共用同一个 Loader 实例，缩略图缓存也共享。
 */
@Composable
private fun MediaThumb(media: SharedMedia, size: Dp) {
  val context = LocalContext.current
  val loader = remember(context) { AlbumImageLoader.get(context) }
  val previewable = media.isImage || media.isVideo

  Box(
    modifier =
      Modifier.size(size)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    if (previewable) {
      AsyncImage(
        model = media.uri,
        contentDescription = media.name,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      // 音频 / 其它：没有画面可抽，给一个类型图标，别留个灰块让人以为加载失败
      Icon(
        imageVector = Icons.Default.AudioFile,
        contentDescription = media.name,
        modifier = Modifier.size(size * 0.4f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (media.isVideo) {
      // 黑底白三角：底下是画面而不是 UI 表面，所以不跟随主题
      Box(
        modifier =
          Modifier.size(size * 0.34f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(size * 0.24f),
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
