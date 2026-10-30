package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album.AlbumImageLoader
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album.AlbumItem
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album.AlbumPickerGrid
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils
import app.marlboroadvance.mpvex.utils.permission.PermissionUtils
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.utils.storage.StorageVolumeUtils
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 三种模式：相册（缩略图网格，传图片/视频最方便）/ 文件（目录树多选）/ 文件夹（整目录） */
private enum class UploadMode { Album, Files, Folder }

/** 预览器里的一项。[uri] 为 null 时只能显示「无法预览」。 */
private data class PreviewItem(val uri: Uri?, val isVideo: Boolean, val title: String)

/**
 * 预览器的内容 + 当前页。
 *
 * 为什么是一「叠」而不是单个：用户点开一张图往往要看前后几张（这组是不是完整、
 * 下一个视频是不是那一集）。所以预览器自带翻页，**只在当前上下文里翻** ——
 * 相册模式翻当前筛选出的网格，文件模式翻同级可预览文件，进度页翻这一批任务。
 */
private data class PreviewDeck(val items: List<PreviewItem>, val index: Int)

private val PREVIEW_IMAGE_EXTENSIONS =
  setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

private fun File.isImageFile(): Boolean = extension.lowercase() in PREVIEW_IMAGE_EXTENSIONS

private fun File.isPreviewable(): Boolean = isImageFile() || FileTypeUtils.isVideoFile(this)

/** 一批本地文件 → 预览 deck（只收图片/视频）。[current] 不在其中时退到第 0 项 */
private fun deckOfFiles(files: List<File>, current: File): PreviewDeck {
  val list = files.filter { it.isFile && it.isPreviewable() }
  val index = list.indexOfFirst { it.absolutePath == current.absolutePath }.coerceAtLeast(0)
  return PreviewDeck(
    items =
      list.map { PreviewItem(Uri.fromFile(it), FileTypeUtils.isVideoFile(it), it.name) },
    index = index,
  )
}

/** 一批上传任务 → 预览 deck（只收拿得到本地源的项） */
private fun deckOfTasks(tasks: List<UploadTask>, current: UploadTask): PreviewDeck {
  val list = tasks.filter { it.previewUri != null }
  val index = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
  return PreviewDeck(
    items = list.map { PreviewItem(it.previewUri, it.isVideo, it.relativePath.substringAfterLast('/')) },
    index = index,
  )
}

/**
 * 网络位置（AList）→ 上传面板：**本地文件 / 文件夹选择器 + 上传进度**。
 *
 * ## 为什么自己做选择器而不是系统 SAF
 *
 * 1. 本应用已持有「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`），`java.io.File`
 *    就能读全部存储 —— SAF 那层 `content://` 只是多一次权限委托，拿不到额外能力；
 * 2. SAF 的界面是系统画的，和浏览页完全两个观感；
 * 3. SAF 选文件夹（`OpenDocumentTree`）给的是 DocumentFile 树，展开要逐层走
 *    ContentResolver，比 `File.listFiles()` 慢一个数量级。
 *
 * ## 交互上的两条取舍
 *
 * - **文件模式跨目录累积选择**：`selected` 是 `absolutePath → File` 的 Map，
 *   选几个 → 进下一层继续选 → 返回，之前的还在。
 * - **文件夹模式没有「勾选」这一步**：按钮就是「上传当前所在的这个文件夹」。
 *   少一个状态就少一处歧义 —— 想传哪个文件夹，走进去就是了。
 *
 * ## 上传期间关掉面板
 *
 * 不中断。队列活在 [NetworkUploadCenter]（进程级），重新打开入口即可看到进度 ——
 * 传 2GB 的时候用户总得能去干别的。
 */
@Composable
fun NetworkUploadSheet(
  connectionId: Long,
  targetDir: String,
  targetLabel: String,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val batch by NetworkUploadCenter.batch.collectAsState()

  // 只认「本次目标目录」的批次：换了个目录，旧批次的进度不该出现在这里
  val activeBatch =
    batch?.takeIf { it.connectionId == connectionId && it.targetDir == targetDir }

  // 有正在跑的批次就自动进进度页；用户手动点「继续选择」后不再强拉
  var showTransfer by remember { mutableStateOf(activeBatch?.running == true) }
  LaunchedEffect(activeBatch?.id, activeBatch?.running) {
    if (activeBatch?.running == true) showTransfer = true
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    // 自带拖拽条会和我们的标题行挤在一起，关掉换自定义头部
    dragHandle = null,
  ) {
    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
      val active = activeBatch
      if (showTransfer && active != null) {
        TransferPane(
          batch = active,
          onBack = { showTransfer = false },
          onClose = onDismiss,
        )
      } else {
        PickerPane(
          context = context,
          scope = scope,
          targetLabel = targetLabel,
          hasRunningBatch = batch?.running == true,
          onClose = onDismiss,
          onShowTransfer = { showTransfer = true },
          onStart = { picks ->
            NetworkUploadCenter.start(
              context = context,
              connectionId = connectionId,
              targetDir = targetDir,
              targetLabel = targetLabel,
              picks = picks,
            )
            showTransfer = true
          },
        )
      }
    }
  }
}

// ────────────────────────────── 选择器 ──────────────────────────────

@Composable
private fun PickerPane(
  context: Context,
  scope: CoroutineScope,
  targetLabel: String,
  hasRunningBatch: Boolean,
  onClose: () -> Unit,
  onShowTransfer: () -> Unit,
  onStart: (List<UploadPick>) -> Unit,
) {
  var mode by remember { mutableStateOf(UploadMode.Album) }
  var hasPermission by remember { mutableStateOf(PermissionUtils.hasStoragePermission(context)) }
  var preparing by remember { mutableStateOf(false) }
  var hint by remember { mutableStateOf<String?>(null) }

  // 从系统设置授权回来后要重新判一次，否则明明给了权限、面板还停在「去授权」
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasPermission = PermissionUtils.hasStoragePermission(context)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val volumes =
    remember {
      StorageVolumeUtils.getAllStorageVolumes(context)
        .mapNotNull { volume ->
          StorageVolumeUtils.getVolumePath(volume)?.let { path ->
            volume.getDescription(context).toString() to File(path)
          }
        }
        .distinctBy { it.second.absolutePath }
    }

  var currentDir by remember {
    mutableStateOf(
      NetworkUploadCenter.lastLocalDir?.let(::File)?.takeIf { it.isDirectory && it.canRead() }
        ?: volumes.firstOrNull()?.second
        ?: Environment.getExternalStorageDirectory(),
    )
  }
  // 每次换目录都记一笔：下次打开面板直接停在原处
  LaunchedEffect(currentDir) { NetworkUploadCenter.lastLocalDir = currentDir.absolutePath }

  val selected = remember { mutableStateMapOf<String, File>() }
  // 相册模式的选择（跨相册累积，再点一下取消）。与文件模式的 selected 各自独立 ——
  // 一次只上传「当前模式」选中的那批，混合来源分两次传，语义最不容易出错。
  val albumSelected = remember { mutableStateListOf<AlbumItem>() }
  // 选择器内的预览（长按触发）。deck 由触发点构造 —— 见 PreviewDeck 的说明
  var previewDeck by remember { mutableStateOf<PreviewDeck?>(null) }
  // 切模式时清空：文件夹模式下没有「勾选」这回事，留着只会在切回来时冒出旧选择
  LaunchedEffect(mode) {
    hint = null
    if (mode == UploadMode.Folder) selected.clear()
  }

  val isVolumeRoot = volumes.any { it.second.absolutePath == currentDir.absolutePath }
  val canGoUp = !isVolumeRoot && currentDir.parentFile != null

  val entries =
    remember(currentDir, mode) {
      runCatching {
          currentDir
            .listFiles()
            ?.filter { it.name.isNotBlank() && !it.name.startsWith(".") }
            ?.filter { file ->
              when {
                // 目录两种模式下都要显示（用来往下走）
                file.isDirectory -> file.canRead()
                mode == UploadMode.Folder -> false
                else -> file.isFile
              }
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        .getOrNull() ?: emptyList()
    }

  val selectedBytes = selected.values.sumOf { it.length() }
  val albumBytes = albumSelected.sumOf { it.sizeBytes }

  Column(modifier = Modifier.fillMaxSize()) {
    // ── 头部：标题 + 目标路径 ──
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "上传到网络位置",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = "目标 · $targetLabel",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
      }
    }

    Spacer(Modifier.height(10.dp))

    // ── 模式切换 ──
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ModeChip(
        text = "相册",
        icon = Icons.Default.PhotoLibrary,
        selected = mode == UploadMode.Album,
        onClick = { mode = UploadMode.Album },
      )
      ModeChip(
        text = "文件",
        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
        selected = mode == UploadMode.Files,
        onClick = { mode = UploadMode.Files },
      )
      ModeChip(
        text = "文件夹",
        icon = Icons.Default.Folder,
        selected = mode == UploadMode.Folder,
        onClick = { mode = UploadMode.Folder },
      )
      Spacer(Modifier.weight(1f))
      // 多卷（手机存储 + SD 卡）时才给切换入口，单卷不占地方
      if (volumes.size > 1) {
        VolumeSwitcher(
          volumes = volumes,
          currentPath = currentDir.absolutePath,
          onSelect = { currentDir = it },
        )
      }
    }

    Spacer(Modifier.height(10.dp))

    if (!hasPermission) {
      NoPermissionPane(
        modifier = Modifier.weight(1f),
        onGrant = { context.openAllFilesAccessSettings() },
        onRetry = { hasPermission = PermissionUtils.hasStoragePermission(context) },
      )
    } else if (mode == UploadMode.Album) {
      AlbumPickerGrid(
        context = context,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        selected = albumSelected,
        onToggle = { item ->
          val index = albumSelected.indexOfFirst { it.key == item.key }
          if (index >= 0) albumSelected.removeAt(index) else albumSelected.add(item)
          hint = null
        },
        onPreview = { index, items ->
          previewDeck =
            PreviewDeck(
              items = items.map { PreviewItem(it.uri, it.isVideo, it.name) },
              index = index,
            )
        },
      )
    } else {
      // ── 路径条 ──
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = { currentDir.parentFile?.let { currentDir = it } },
            enabled = canGoUp,
          ) {
            Icon(
              Icons.Default.ArrowUpward,
              contentDescription = "上一级",
              modifier = Modifier.size(18.dp),
              tint =
                if (canGoUp) {
                  MaterialTheme.colorScheme.secondary
                } else {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                },
            )
          }
          Text(
            text = currentDir.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = "${entries.size} 项",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp),
          )
        }
      }

      // ── 内容 ──
      if (entries.isEmpty()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          Text(
            text = if (mode == UploadMode.Folder) "这里没有子文件夹" else "这里没有可上传的文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(vertical = 4.dp),
        ) {
          items(items = entries, key = { it.absolutePath }) { entry ->
            if (entry.isDirectory) {
              PickerRow(
                icon = Icons.Default.Folder,
                tint = MaterialTheme.colorScheme.secondary,
                title = entry.name,
                subtitle = null,
                trailing = {
                  Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline,
                  )
                },
                onClick = { currentDir = entry },
              )
            } else {
              val isSelected = selected.containsKey(entry.absolutePath)
              // 图片/视频才给缩略图和长按预览：文档类没有可看的内容，
              // 给一个「能点开」的暗示反而是骗人
              val previewable = entry.isPreviewable()
              PickerRow(
                icon = iconFor(entry),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                title = entry.name,
                subtitle = MediaFormatUtils.formatFileSize(entry.length()),
                leading =
                  if (previewable) {
                    {
                      LocalMediaThumb(
                        uri = Uri.fromFile(entry),
                        isVideo = FileTypeUtils.isVideoFile(entry),
                        size = 40.dp,
                      )
                    }
                  } else {
                    null
                  },
                trailing = {
                  Icon(
                    imageVector =
                      if (isSelected) {
                        Icons.Default.CheckCircle
                      } else {
                        Icons.Default.RadioButtonUnchecked
                      },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint =
                      if (isSelected) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                      },
                  )
                },
                onClick = {
                  if (isSelected) selected.remove(entry.absolutePath)
                  else selected[entry.absolutePath] = entry
                  hint = null
                },
                onLongClick =
                  if (previewable) {
                    { previewDeck = deckOfFiles(entries, entry) }
                  } else {
                    null
                  },
              )
            }
          }
        }
      }
    }

    // ── 底部操作条 ──
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
      Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
        Text(
          text =
            hint
              ?: when (mode) {
                UploadMode.Album ->
                  if (albumSelected.isEmpty()) {
                    "点缩略图多选（可跨相册）· 长按预览"
                  } else {
                    "已选 ${albumSelected.size} 项 · " +
                      MediaFormatUtils.formatFileSize(albumBytes)
                  }
                UploadMode.Files ->
                  if (selected.isEmpty()) {
                    "点文件多选，进文件夹可以继续挑 · 长按预览"
                  } else {
                    "已选 ${selected.size} 项 · ${MediaFormatUtils.formatFileSize(selectedBytes)}"
                  }
                UploadMode.Folder ->
                  if (isVolumeRoot) {
                    "根目录不做整目录上传，进入某个文件夹后再试"
                  } else {
                    "将上传整个「${currentDir.name}」文件夹（含子文件夹）"
                  }
              },
          style = MaterialTheme.typography.bodySmall,
          color =
            if (hint != null) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (hasRunningBatch) {
            TextButton(onClick = onShowTransfer) { Text("查看上传进度") }
          }
          Spacer(Modifier.weight(1f))
          Button(
            enabled =
              !preparing &&
                when (mode) {
                  UploadMode.Album -> albumSelected.isNotEmpty()
                  UploadMode.Files -> selected.isNotEmpty()
                  UploadMode.Folder -> !isVolumeRoot
                },
            onClick = {
              when (mode) {
                UploadMode.Album ->
                  onStart(
                    albumSelected.map { item ->
                      UploadPick(
                        file = item.file,
                        relativePath = item.name,
                        contentUri = item.uri,
                        sizeHint = item.sizeBytes,
                      )
                    },
                  )
                UploadMode.Files -> onStart(selected.values.toList().map { UploadPick(it, it.name) })
                UploadMode.Folder -> {
                  // 递归读磁盘，别占主线程；扫完直接开传
                  preparing = true
                  scope.launch {
                    val result = NetworkUploadCenter.scanFolder(currentDir)
                    preparing = false
                    if (result.picks.isEmpty()) {
                      hint = "这个文件夹里没有可上传的文件"
                    } else {
                      if (result.truncated) {
                        hint = "文件过多，本次只上传前 ${NetworkUploadCenter.MAX_FOLDER_FILES} 个"
                      }
                      onStart(result.picks)
                    }
                  }
                }
              }
            },
          ) {
            if (preparing) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
              Spacer(Modifier.width(8.dp))
              Text("正在扫描…")
            } else {
              Text(
                when (mode) {
                  UploadMode.Album ->
                    if (albumSelected.isEmpty()) "上传" else "上传 ${albumSelected.size} 项"
                  UploadMode.Files -> if (selected.isEmpty()) "上传" else "上传 ${selected.size} 项"
                  UploadMode.Folder -> "上传此文件夹"
                },
              )
            }
          }
        }
      }
    }

    // 预览器挂在面板层级：放进列表项里会随滚动被回收，且每个 item 各持一份状态
    previewDeck?.let { deck ->
      MediaPreviewDialog(deck = deck, onDismiss = { previewDeck = null })
    }
  }
}

@Composable
private fun ModeChip(
  text: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(15.dp),
        tint =
          if (selected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color =
          if (selected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}

@Composable
private fun VolumeSwitcher(
  volumes: List<Pair<String, File>>,
  currentPath: String,
  onSelect: (File) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(
        Icons.Default.Storage,
        contentDescription = "切换存储",
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.secondary,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      volumes.forEach { (label, dir) ->
        DropdownMenuItem(
          text = {
            Text(
              text = label,
              color =
                if (dir.absolutePath == currentPath) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          },
          onClick = {
            expanded = false
            onSelect(dir)
          },
        )
      }
    }
  }
}

@Composable
private fun PickerRow(
  icon: ImageVector,
  tint: Color,
  title: String,
  subtitle: String?,
  trailing: @Composable () -> Unit,
  onClick: () -> Unit,
  /** 有缩略图时替掉左侧图标（图片/视频行）。缩略图本身不响应点击 —— 点击语义统一归整行 */
  leading: (@Composable () -> Unit)? = null,
  /** 长按预览。为 null 表示这一行没有可预览的内容（目录、文档类） */
  onLongClick: (() -> Unit)? = null,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .then(
          if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
          } else {
            Modifier.clickable(onClick = onClick)
          },
        )
        .padding(horizontal = 20.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (leading != null) {
      leading()
    } else {
      Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
    }
    Spacer(Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    trailing()
  }
}

@Composable
private fun NoPermissionPane(
  modifier: Modifier = Modifier,
  onGrant: () -> Unit,
  onRetry: () -> Unit,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text = "需要文件访问权限", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Text(
      text = "读取本机文件才能上传。「所有文件访问权限」授权后回来即可继续。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onGrant) { Text("去授权") }
      TextButton(onClick = onRetry) { Text("已授权，重试") }
    }
  }
}

// ────────────────────────────── 进度 ──────────────────────────────

@Composable
private fun TransferPane(
  batch: UploadBatch,
  onBack: () -> Unit,
  onClose: () -> Unit,
) {
  // 点缩略图放大看的那一条。放在这里而不是 TaskRow 里：Dialog 必须挂在窗口级，
  // 挂在列表项里会随 item 滚出屏幕而消失（且每个 item 各持一份状态）。
  // deck 覆盖**整批任务**里所有拿得到本地源的文件 —— 预览里能直接翻前后，
  // 不用关窗再点下一行（图片尤其需要：一组照片要连着比对）。
  var previewDeck by remember { mutableStateOf<PreviewDeck?>(null) }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = if (batch.running) "正在上传" else "上传结束",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = "目标 · ${batch.targetLabel}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
      }
    }

    Spacer(Modifier.height(14.dp))

    // ── 总进度 ──
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      LinearProgressIndicator(
        progress = { batch.progress },
        modifier = Modifier.fillMaxWidth().height(4.dp),
      )
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text =
            "${MediaFormatUtils.formatFileSize(batch.sentBytes)} / " +
              MediaFormatUtils.formatFileSize(batch.totalBytes),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
          text = summaryText(batch),
          style = MaterialTheme.typography.labelSmall,
          color =
            if (batch.failedCount > 0 && !batch.running) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }

    // ── 结果（只在结束后出现）──
    if (!batch.running) {
      UploadResultBanner(batch)
    }

    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
      items(items = batch.tasks, key = { it.id }) { task ->
        TaskRow(task = task, onPreview = { previewDeck = deckOfTasks(batch.tasks, task) })
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onBack, enabled = !batch.running) { Text("继续选择") }
        Spacer(Modifier.weight(1f))
        if (batch.running) {
          OutlinedButton(onClick = { NetworkUploadCenter.cancel() }) { Text("取消上传") }
        } else {
          Button(onClick = onClose) { Text("完成") }
        }
      }
    }

    previewDeck?.let { deck ->
      MediaPreviewDialog(deck = deck, onDismiss = { previewDeck = null })
    }
  }
}

/**
 * 结束后的结果横幅。
 *
 * 为什么单独做一块而不是只靠标题变字：用户在传几十个文件的间隙会去看别的，
 * 回来时先看到的是**图**（大图标）而不是一行标题 —— 「✓ 上传完成」扫一眼就够了。
 */
@Composable
private fun UploadResultBanner(batch: UploadBatch) {
  val failed = batch.failedCount > 0
  val cancelledOnly = !failed && batch.cancelledCount > 0
  val icon =
    when {
      failed -> Icons.Default.ErrorOutline
      cancelledOnly -> Icons.Default.Close
      else -> Icons.Default.CheckCircle
    }
  val tint =
    when {
      failed -> MaterialTheme.colorScheme.error
      cancelledOnly -> MaterialTheme.colorScheme.outline
      else -> MaterialTheme.colorScheme.primary
    }
  val title =
    when {
      failed -> "上传结束"
      cancelledOnly -> "上传已取消"
      else -> "上传完成"
    }

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = tint)
    Spacer(Modifier.height(8.dp))
    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(3.dp))
    Text(
      text = buildString {
        if (batch.successCount > 0) append("成功 ${batch.successCount} 个")
        if (failed) {
          if (isNotEmpty()) append(" · ")
          append("失败 ${batch.failedCount} 个")
        }
        if (batch.cancelledCount > 0) {
          if (isNotEmpty()) append(" · ")
          append("已取消 ${batch.cancelledCount} 个")
        }
        if (isNotEmpty()) append(" · ")
        append(MediaFormatUtils.formatFileSize(batch.sentBytes))
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun summaryText(batch: UploadBatch): String =
  when {
    batch.running -> "${batch.finishedCount} / ${batch.tasks.size} 个文件"
    batch.failedCount > 0 -> "成功 ${batch.successCount} · 失败 ${batch.failedCount}"
    batch.cancelledCount > 0 -> "已取消（成功 ${batch.successCount}）"
    else -> "全部完成 · ${batch.successCount} 个文件"
  }

@Composable
private fun TaskRow(task: UploadTask, onPreview: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = stateIcon(task.state),
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint =
        when (task.state) {
          UploadTaskState.Success -> MaterialTheme.colorScheme.primary
          UploadTaskState.Failed -> MaterialTheme.colorScheme.error
          UploadTaskState.Cancelled -> MaterialTheme.colorScheme.outline
          UploadTaskState.Uploading -> MaterialTheme.colorScheme.secondary
          UploadTaskState.Pending -> MaterialTheme.colorScheme.outline
        },
    )
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = task.relativePath,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color =
          if (task.state == UploadTaskState.Cancelled) {
            MaterialTheme.colorScheme.outline
          } else {
            MaterialTheme.colorScheme.onSurface
          },
      )
      when {
        task.state == UploadTaskState.Uploading -> {
          Spacer(Modifier.height(4.dp))
          LinearProgressIndicator(
            progress = { task.progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
          )
          Spacer(Modifier.height(3.dp))
          // 「已上传 x / 共 y」——进度条只说得清「大概到哪了」，说不清「还差多少」。
          // 网盘上传慢的时候，用户真正想知道的就是这两个绝对量：
          // 传到哪了、还剩多少 MB。（用户原话：「按单条目显示当前已上传容量与总容量」）
          // ⚠️ 总量未知（sizeBytes <= 0：例如统计不到的目录项）时不要摆一个假的
          //    「共 0 B」，那只说已传了多少，绝不编一个总量出来。
          Text(
            text =
              if (task.sizeBytes > 0) {
                "已上传 ${MediaFormatUtils.formatFileSize(task.sentBytes)}" +
                  " / 共 ${MediaFormatUtils.formatFileSize(task.sizeBytes)}"
              } else {
                "已上传 ${MediaFormatUtils.formatFileSize(task.sentBytes)}"
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        task.state == UploadTaskState.Failed && task.error != null -> {
          Spacer(Modifier.height(2.dp))
          Text(
            text = task.error.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    Spacer(Modifier.width(12.dp))
    Text(
      text =
        when (task.state) {
          UploadTaskState.Pending -> MediaFormatUtils.formatFileSize(task.sizeBytes)
          UploadTaskState.Uploading -> "${(task.progress * 100).toInt()}%"
          UploadTaskState.Success -> "已上传"
          UploadTaskState.Failed -> "失败"
          UploadTaskState.Cancelled -> "已取消"
        },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (task.previewUri != null) {
      Spacer(Modifier.width(10.dp))
      TaskPreviewThumb(task = task, onClick = onPreview)
    }
  }
}

private fun stateIcon(state: UploadTaskState): ImageVector =
  when (state) {
    UploadTaskState.Success -> Icons.Default.CheckCircle
    UploadTaskState.Failed -> Icons.Default.ErrorOutline
    UploadTaskState.Cancelled -> Icons.Default.Close
    UploadTaskState.Uploading, UploadTaskState.Pending -> Icons.AutoMirrored.Filled.InsertDriveFile
  }

/** 按扩展名挑图标；认不出就用通用文件图标（不猜 mime，够用且零依赖） */
private fun iconFor(file: File): ImageVector =
  when (file.extension.lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> Icons.Default.Image
    "mp4", "mkv", "avi", "mov", "webm", "ts", "m2ts", "flv", "wmv", "rmvb" -> Icons.Default.Movie
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape" -> Icons.Default.AudioFile
    "txt", "pdf", "doc", "docx", "nfo", "srt", "ass", "ssa", "json", "xml", "md", "epub" ->
      Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
  }

/** 跳到「所有文件访问权限」授权页（Android 11+ 才有这个概念） */
private fun Context.openAllFilesAccessSettings() {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
  val intent =
    Intent(
      Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
      Uri.parse("package:$packageName"),
    )
  runCatching { startActivity(intent) }
    .onFailure {
      // 个别 ROM 没有应用级入口，退回总列表
      runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}


// ────────────────────────────── 小窗预览 ──────────────────────────────

/**
 * 本地图片/视频的方缩略图（文件列表与进度页共用）。
 *
 * 用 [AlbumImageLoader] 而不是默认单例：它注册了 `VideoFrameDecoder`，
 * 能把视频（`content://…/video/…` 或 `file://…mp4`）抽成首帧；
 * 默认单例只会拿整段 MP4 去解码，必然失败。
 *
 * [onClick] 为 null 时不拦截触摸 —— 缩略图嵌在整行可点的条目里时，
 * 点击语义必须统一归整行，否则「点缩略图」和「点这一行」会做两件不同的事。
 */
@Composable
private fun LocalMediaThumb(
  uri: Uri?,
  isVideo: Boolean,
  size: Dp,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val loader = remember(context) { AlbumImageLoader.get(context) }
  Box(
    modifier =
      modifier.size(size)
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    AsyncImage(
      model = uri,
      contentDescription = null,
      imageLoader = loader,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )
    if (isVideo) {
      // 黑底白三角压在缩略图上：底下是画面而不是 UI 表面，所以不跟随主题
      val badge = size * 0.5f
      Box(
        modifier = Modifier.size(badge).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(badge * 0.7f),
          tint = Color.White,
        )
      }
    }
  }
}

/** 任务行左侧的缩略图（进度页用），点一下开预览 */
@Composable
private fun TaskPreviewThumb(task: UploadTask, onClick: () -> Unit) {
  LocalMediaThumb(
    uri = task.previewUri,
    isVideo = task.isVideo,
    size = 40.dp,
    onClick = onClick,
  )
}

/**
 * 预览器（选择器与进度页共用）：黑底卡片，图片按比例完整显示、视频用 [VideoView]
 * 循环播且可拖进度；一叠里有多项时底部给左右翻页与序号。
 *
 * ## 为什么图片不锁 16:9，视频的 16:9 交给 [VideoPreview] 自己锁
 *
 * 手机竖拍的照片塞进 16:9 只剩中间一条，看不出拍的是什么；图片改用「屏宽 + 高度
 * 夹在 180~440dp」的自由比例（`ContentScale.Fit`），横竖都够看。视频画面本身是
 * 横向的，锁 16:9 让控件位置稳定 —— 但**锁在 [VideoPreview] 内部**：那一层只包
 * 画面，下面还要留出固定高度的控件条；在外面这层锁比例会把控件条一起算进 16:9，
 * 结果就是控件被压扁（用户报的「控件显示不全」）。
 *
 * ## 为什么用 Dialog 而不是内嵌一个 Box
 *
 * [VideoView] 底层是 `SurfaceView`，它默认在**窗口**层级、不在 View 树里 ——
 * 内嵌在 Compose 的 Surface 上会被 Compose 画的内容盖住（或者反过来盖住别人）。
 * 独立 Dialog 有自己的窗口，层级天然正确，且关窗就停播。
 *
 * ## 为什么不用项目里的 mpv 内核
 *
 * 这里只需要"看一眼是不是这个视频"，要的是秒开和零状态；拉一整套播放器
 * （内核初始化、渲染线程、字幕/轨道菜单）会让一个预览窗变成第二个播放页。
 */
@Composable
private fun MediaPreviewDialog(deck: PreviewDeck, onDismiss: () -> Unit) {
  // 页码跟着 deck 走：从别的行点开是「另一叠」，必须回到它自己的起点
  var index by remember(deck) { mutableIntStateOf(deck.index) }
  val item = deck.items.getOrNull(index) ?: return
  val total = deck.items.size

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.92f),
      shape = RoundedCornerShape(16.dp),
      color = Color.Black,
      tonalElevation = 6.dp,
    ) {
      Column {
        Box(
          modifier =
            Modifier.fillMaxWidth().then(
              if (item.isVideo) {
                // 视频**自己不锁高度**：画面 16:9 由 [VideoPreview] 内部固定，
                // 底下还有一条固定高度的控件栏 —— 在本层再套一个 aspectRatio 会
                // 把控件栏挤到框外（或者压成一条线），这正是「控件显示不全」的老毛病。
                Modifier
              } else {
                // 图片不锁比例：手机竖拍塞进 16:9 只剩中间一条，看不出是什么
                Modifier.heightIn(min = 180.dp, max = 440.dp)
              },
            ),
          contentAlignment = Alignment.Center,
        ) {
          PreviewContent(item = item)
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (total > 1) {
            val canPrev = index > 0
            IconButton(onClick = { if (canPrev) index-- }, enabled = canPrev) {
              Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一个",
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = if (canPrev) 0.95f else 0.25f),
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = item.title,
              style = MaterialTheme.typography.labelMedium,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (total > 1) {
              Text(
                text = "${index + 1} / $total",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
              )
            }
          }
          if (total > 1) {
            val canNext = index < total - 1
            IconButton(onClick = { if (canNext) index++ }, enabled = canNext) {
              Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "下一个",
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = if (canNext) 0.95f else 0.25f),
              )
            }
          }
          TextButton(onClick = onDismiss) { Text(text = "关闭", color = Color.White) }
        }
      }
    }
  }
}

/**
 * 预览器里的一屏内容。
 *
 * 图片必须自己回填加载态：黑底上「还没解码完」和「解码失败」都是黑屏，
 * 不给转圈和失败文案，用户只能猜是不是卡死了。
 */
@Composable
private fun PreviewContent(item: PreviewItem) {
  val uri = item.uri
  if (uri == null) {
    Text(text = "无法预览", style = MaterialTheme.typography.bodySmall, color = Color.White)
    return
  }
  if (item.isVideo) {
    // key(uri)：AndroidView 的 factory 只跑一次，不给 key 时翻到下一个视频仍在播上一个
    key(uri) { VideoPreview(uri = uri) }
    return
  }

  val context = LocalContext.current
  val loader = remember(context) { AlbumImageLoader.get(context) }
  var ready by remember(uri) { mutableStateOf(false) }
  var failure by remember(uri) { mutableStateOf<String?>(null) }

  AsyncImage(
    model = uri,
    contentDescription = item.title,
    imageLoader = loader,
    contentScale = ContentScale.Fit,
    modifier = Modifier.fillMaxSize(),
    onState = { state ->
      when (state) {
        is AsyncImagePainter.State.Success -> {
          ready = true
          failure = null
        }
        is AsyncImagePainter.State.Error -> {
          ready = false
          failure = state.result.throwable.message ?: "未知原因"
        }
        else -> Unit
      }
    },
  )

  if (!ready) {
    val reason = failure
    if (reason == null) {
      CircularProgressIndicator(
        modifier = Modifier.size(22.dp),
        strokeWidth = 2.dp,
        color = Color.White,
      )
    } else {
      Text(
        text = "图片加载失败：$reason",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.75f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
      )
    }
  }
}

/**
 * 上传预览里的视频（画面 + **自绘控件条**）。
 *
 * ## 为什么不用 `MediaController`（这是用户报的那个 bug 的根因）
 *
 * 原先挂的是 `VideoView.setMediaController(MediaController(ctx).setAnchorView(view))`。
 * `MediaController` 是 Android 十多年前的控件，它的尺寸完全由**系统主题里的
 * `@dimen/mediacontroller_*`** 与锚点窗口决定，我们一行都改不了。这里的具体表现：
 *
 * - 它是「浮在画面底部的一层」，锚点就是那个 16:9 的画面框；
 *   框本身不高（手机竖屏下约 200dp），控件层按比例压下来就只剩一条；
 * - 时长很长时，两边的时间文本从 `01:23` 变成 `12:34:56`，**把中间的进度条挤没了** ——
 *   用户看到的正是「控件显示不全、过小」；
 * - 而且它显示/隐藏靠点画面切换，预览这种「只想看一眼」的场景经常一半控件在外面。
 *
 * 所以这里换成**自己排的一条控件栏**：固定 56dp 高、图标按钮 44dp 触摸区、
 * 时间文本给最小宽度、进度条吃剩余空间。时长是 3 分钟还是 30 小时，布局都一样。
 *
 * ## 为什么控件放在画面**下方**而不是浮在上面
 *
 * [VideoView] 底层是 `SurfaceView`：它直接在自己的 surface 上出画面，**Compose 画在
 * 同一层的内容盖不住它**（这正是本文件顶部 KDoc 说的「必须用独立 Dialog 窗口」的
 * 同一个原因）。浮层想稳，只能再开一个子窗口 —— 为一条控件栏不值。放在下方是纯
 * 布局关系，谁也不会盖住谁，且永远可见（不需要「点一下才出来」）。
 *
 * ## 为什么位置靠轮询
 *
 * `VideoView` 只有 `getCurrentPosition()`，没有任何进度回调。500ms 读一次够用
 * （进度条 1px 的移动肉眼分辨不出更细的粒度），也不会费电。拖动时跳过轮询 ——
 * 否则手指还没松，轮询就把滑块拽回播放位置，滑块会「抖」。
 */
@Composable
private fun VideoPreview(uri: Uri) {
  // 关窗必须显式停播：VideoView 不在 Compose 的生命周期里，不主动停会一直占着解码器
  var player by remember { mutableStateOf<VideoView?>(null) }
  var prepared by remember(uri) { mutableStateOf(false) }
  var playing by remember(uri) { mutableStateOf(false) }
  var durationMs by remember(uri) { mutableStateOf(0L) }
  var positionMs by remember(uri) { mutableStateOf(0L) }
  // 拖动中：此时滑块位置由手指决定，轮询让位
  var scrubbing by remember(uri) { mutableStateOf(false) }
  var scrubMs by remember(uri) { mutableStateOf(0L) }

  DisposableEffect(uri) { onDispose { player?.stopPlayback() } }

  LaunchedEffect(prepared) {
    if (!prepared) return@LaunchedEffect
    while (true) {
      delay(500)
      val view = player ?: continue
      playing = runCatching { view.isPlaying }.getOrDefault(false)
      if (!scrubbing) {
        positionMs = runCatching { view.currentPosition.toLong() }.getOrDefault(0L)
      }
    }
  }

  val shownPosition = if (scrubbing) scrubMs else positionMs
  val total = durationMs.coerceAtLeast(1L)

  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
      contentAlignment = Alignment.Center,
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          VideoView(ctx).apply {
            val view = this
            setOnPreparedListener { mp ->
              mp.isLooping = true
              // 时长在这里取一次就够了：本地文件不会中途改长度
              durationMs = mp.duration.toLong().coerceAtLeast(0L)
              prepared = true
              playing = true
            }
            setOnCompletionListener { playing = false }
            setOnErrorListener { _, _, _ ->
              prepared = false
              true
            }
            setVideoURI(uri)
            start()
            player = this
          }
        },
      )
    }

    // ── 控件条：高度固定，与时长无关 ──
    Row(
      modifier =
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 44dp = Material 的最小触摸目标，比图标本身大一圈，指头不容易点空
      IconButton(
        onClick = {
          val view = player ?: return@IconButton
          if (view.isPlaying) {
            view.pause()
            playing = false
          } else {
            // 播完了再点播放：回到开头，否则「点了没反应」（停在末尾）
            if (durationMs > 0 && positionMs >= durationMs - 200) view.seekTo(0)
            view.start()
            playing = true
          }
        },
        modifier = Modifier.size(44.dp),
        enabled = prepared,
      ) {
        Icon(
          imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (playing) "暂停" else "播放",
          modifier = Modifier.size(28.dp),
          tint = Color.White.copy(alpha = if (prepared) 0.95f else 0.35f),
        )
      }

      // 时间文本给最小宽度：否则「0:03」和「12:34:56」宽度跳变，滑块会左右抖
      Text(
        text = formatPlaybackTime(shownPosition),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = Modifier.widthIn(min = 54.dp),
      )

      Slider(
        value = shownPosition.coerceIn(0L, total).toFloat(),
        onValueChange = {
          scrubbing = true
          scrubMs = it.toLong()
        },
        onValueChangeFinished = {
          player?.seekTo(scrubMs.toInt())
          positionMs = scrubMs
          scrubbing = false
        },
        valueRange = 0f..total.toFloat(),
        enabled = prepared,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        // 黑底上必须显式给颜色：默认的 inactive track 在纯黑上几乎看不见
        colors =
          SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            disabledThumbColor = Color.White.copy(alpha = 0.35f),
            disabledActiveTrackColor = Color.White.copy(alpha = 0.35f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f),
          ),
      )

      Text(
        text = if (prepared) formatPlaybackTime(durationMs) else "--:--",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = Modifier.widthIn(min = 54.dp),
      )
    }
  }
}

/**
 * 毫秒 → `mm:ss` / `h:mm:ss`。
 *
 * 超过 1 小时才补小时段：几十分钟的视频显示成 `00:12:34` 只是白占宽度
 * （而预览窗的控件条宽度是有限的，这几十 dp 直接决定进度条长短）。
 */
private fun formatPlaybackTime(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format("%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format("%02d:%02d", minutes, seconds)
  }
}
