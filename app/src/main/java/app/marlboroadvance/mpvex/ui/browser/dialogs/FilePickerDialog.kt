package app.marlboroadvance.mpvex.ui.browser.dialogs

import android.content.Context
import android.content.res.Configuration
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.subtitle.LocalSubtitleMatch
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import app.marlboroadvance.mpvex.utils.storage.StorageVolumeUtils
import java.io.File

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilePickerDialog(
  modifier: Modifier = Modifier,
  isOpen: Boolean,
  currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
  onDismiss: () -> Unit,
  onFileSelected: (String) -> Unit,
  onPathChanged: ((String?) -> Unit)? = null,
  onSystemPickerRequest: () -> Unit,
  matchToName: String? = null,
  allowedExtensions: List<String> = listOf(
    // Common & modern
    "srt", "vtt", "ass", "ssa",

    // DVD / Blu-ray
    "sub", "idx", "sup",

    // Streaming / XML / Professional
    "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",

    // Online platforms
    "sbv", "srv1", "srv2", "srv3", "json",

    // Legacy & niche
    "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",

    // Broadcast captions
    "scc", "vttx",

    // Karaoke / lyrics
    "lrc", "krc",

    // Fallback / raw text
    "txt"
  )

) {
  if (!isOpen) return

  val context = LocalContext.current
  
  // Get all available storage volumes
  val storageVolumes = remember(isOpen) {
    StorageVolumeUtils.getAllStorageVolumes(context)
  }
  
  // If there's only one storage volume, start there directly
  // Otherwise, start at storage root view to show all volumes
  // Respect currentPath if it's valid and exists
  var selectedPath by remember(isOpen, currentPath, storageVolumes) {
    val initialPath = if (currentPath.isNotEmpty() && File(currentPath).exists()) {
      currentPath
    } else if (storageVolumes.size == 1) {
      StorageVolumeUtils.getVolumePath(storageVolumes.first())
    } else {
      null // Show storage root with all volumes
    }
    mutableStateOf(initialPath)
  }

  // Notify parent when path changes for state persistence
  LaunchedEffect(selectedPath) {
    onPathChanged?.invoke(selectedPath)
  }

  // Determine what to show based on selectedPath
  val showStorageRoot = selectedPath == null
  
  val currentDir = remember(selectedPath) { 
    selectedPath?.let { File(it) }
  }
  
  // ── 当前目录内的筛选 ──
  // 与播放器字幕面板同一口径：小写 + 空格分词 + 每个词都要命中。
  // 只在**当前目录**里过一遍（不递归）—— 这是个目录浏览器，
  // 把别的目录的文件塞进来会让人搞不清「我现在到底在哪一层」。
  var filter by remember(isOpen) { mutableStateOf("") }
  val tokens =
    remember(filter) {
      filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

  // 搜索框默认收起，只留一个放大镜按钮。常驻输入框会让列表固定少看两行，
  // 而这个选择器 90% 的使用是「翻两层目录点文件」，只有找番号时才需要打字。
  var searchExpanded by remember(isOpen) { mutableStateOf(false) }
  val searchFocus = remember { FocusRequester() }

  // 与当前影片关联的「钥匙」（番号 / CRC / 标题词）。解析不出来就整段推荐逻辑不启用。
  val matchQuery =
    remember(matchToName) {
      matchToName?.takeIf { it.isNotBlank() }?.let { LocalSubtitleMatch.buildQuery(it) }
    }

  // 文件夹：同样只在当前层过滤，命中筛选词的留在列表里。
  val dirs =
    remember(selectedPath, tokens) {
      if (showStorageRoot) {
        emptyList()
      } else {
        currentDir
          ?.listFiles { file -> !file.name.startsWith(".") }
          ?.filter { it.isDirectory && matchesTokens(it.name, tokens) }
          ?.sortedWith(NAME_ORDER) ?: emptyList()
      }
    }

  // 文件分成两摞：命中当前影片的（按匹配分排序）与其余（自然序）。
  // **不递归**：推荐只覆盖眼前这一层，用户翻到哪一层就看那一层的推荐。
  val (recommended, others) =
    remember(selectedPath, tokens, matchQuery) {
      if (showStorageRoot) {
        Pair(emptyList<RecommendedFile>(), emptyList<File>())
      } else {
        val all =
          currentDir
            ?.listFiles { file -> !file.name.startsWith(".") }
            ?.filter { file ->
              !file.isDirectory &&
                allowedExtensions.any { ext -> file.name.endsWith(ext, ignoreCase = true) } &&
                matchesTokens(file.name, tokens)
            } ?: emptyList()

        val rec =
          if (matchQuery == null) {
            emptyList()
          } else {
            all
              .mapNotNull { file ->
                LocalSubtitleMatch.match(matchQuery, file.name)?.let {
                  RecommendedFile(file, it.reason, it.score)
                }
              }
              // 分数相同就短名优先：`011214-521.chs.srt` 比 `...whisperjav.chinese.srt` 更像正片字幕
              .sortedWith(compareByDescending<RecommendedFile> { it.score }.thenBy { it.file.name.length })
          }
        val recPaths = rec.mapTo(HashSet()) { it.file.absolutePath }
        val rest = all.filter { it.absolutePath !in recPaths }.sortedWith(NAME_ORDER)
        Pair(rec, rest)
      }
    }

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

  // 收起搜索 = 同时取消筛选：否则「框看不见、列表却仍是空的」会被当成这个目录里没文件。
  val toggleSearch: (() -> Unit)? =
    if (showStorageRoot) {
      null // 存储根上没有任何可筛的东西，按钮直接不给
    } else {
      {
        searchExpanded = !searchExpanded
        if (!searchExpanded) filter = ""
      }
    }

  // 回到存储根（那里没有搜索按钮）时把搜索态收干净：
  // 否则会留下一个「框还在、却筛不动任何东西」的状态。
  LaunchedEffect(showStorageRoot) {
    if (showStorageRoot && searchExpanded) {
      searchExpanded = false
      filter = ""
    }
  }

  // 展开时列表让出高度、收起时长回来，总高度始终不超出屏幕。
  val listHeight by
    animateDpAsState(
      targetValue =
        when {
          isPortrait && searchExpanded -> 320.dp
          isPortrait -> 400.dp
          searchExpanded -> 260.dp
          else -> 400.dp
        },
      animationSpec = tween(180),
    )

  // 换目录 / 改筛选词后必须回到顶部：推荐区永远在第一行，
  // 否则用户「什么都没变但列表好像空了」—— 其实是停在上一层的滚动位置。
  val listState = rememberLazyListState()
  LaunchedEffect(selectedPath, tokens) { listState.scrollToItem(0) }

  androidx.compose.ui.window.Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
      Surface(
          modifier = modifier.fillMaxWidth(if (isPortrait) 0.9f else 0.50f),
          shape = MaterialTheme.shapes.extraLarge,
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 6.dp,
      ) {
          // 注意：这里**不能**用 verticalArrangement = spacedBy(16.dp)。
          // 折叠的 AnimatedVisibility 高度虽然为 0，但仍是 Column 的一个子项，
          // spacedBy 会给它前后各加一份间距 —— 收起的搜索框会白留 32dp 空白。
          // 所以间距改成各段自己带 padding(top = 16.dp)，折叠时随高度一起归零。
          Column(
              modifier = Modifier.padding(24.dp),
          ) {
              // Title Section - orientation-aware layout
              if (isPortrait) {
                // Portrait: title/path stacked on top, nav buttons centered below
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Column(modifier = Modifier.fillMaxWidth()) {
                      Text(
                        text = stringResource(R.string.i18n_select_subtitle),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                      )
                      Text(
                        text = selectedPath ?: "选择存储位置",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                      )
                  }
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                  ) {
                    NavigationButtons(
                      selectedPath = selectedPath,
                      onBack = { selectedPath = currentDir?.parent },
                      onHome = { selectedPath = Environment.getExternalStorageDirectory().absolutePath },
                      onSystemPicker = onSystemPickerRequest,
                      onToggleSearch = toggleSearch,
                      searchActive = searchExpanded,
                      buttonSize = 48.dp,
                      iconSize = 26.dp,
                    )
                  }
                }
              } else {
                // Landscape: title/path left, nav buttons right (same row)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                  ) {
                      Column(modifier = Modifier.weight(1f)) {
                          Text(
                            text = stringResource(R.string.i18n_select_subtitle),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                          )
                          Text(
                            text = selectedPath ?: "选择存储位置",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                          )
                      }
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavigationButtons(
                          selectedPath = selectedPath,
                          onBack = { selectedPath = currentDir?.parent },
                          onHome = { selectedPath = Environment.getExternalStorageDirectory().absolutePath },
                          onSystemPicker = onSystemPickerRequest,
                          onToggleSearch = toggleSearch,
                          searchActive = searchExpanded,
                          buttonSize = 40.dp,
                          iconSize = 24.dp,
                        )
                      }
                  }
                }
              }

              // 筛选框：字幕目录动辄上百个文件，滚动翻找不如直接打两个字。
              // 但只在需要时展开 —— 高度是从 0 长出来的（AnimatedVisibility），
              // 收起时这一行完全不占位置。
              AnimatedVisibility(
                visible = searchExpanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120)),
              ) {
                // 展开即聚焦：点了放大镜就该能直接打字，不用再点一次输入框
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                OutlinedTextField(
                  value = filter,
                  onValueChange = { filter = it },
                  modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(searchFocus),
                  placeholder = {
                    Text(
                      text = stringResource(R.string.file_picker_filter_hint),
                      style = MaterialTheme.typography.bodyMedium,
                    )
                  },
                  leadingIcon = {
                    Icon(
                      Icons.Default.Search,
                      contentDescription = null,
                      modifier = Modifier.size(20.dp),
                    )
                  },
                  trailingIcon = {
                    if (filter.isNotEmpty()) {
                      IconButton(onClick = { filter = "" }) {
                        Icon(
                          Icons.Default.Close,
                          contentDescription = stringResource(R.string.file_picker_filter_clear),
                          modifier = Modifier.size(20.dp),
                        )
                      }
                    }
                  },
                  singleLine = true,
                  shape = MaterialTheme.shapes.extraLarge,
                  textStyle = MaterialTheme.typography.bodyMedium,
                )
              }

              // Content Section
              Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                // Folder/Volume/File list
                LazyColumn(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      // 随搜索框伸缩：收起时回到 400dp，展开时让出一行的高度
                      .height(listHeight),
                  state = listState,
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  if (showStorageRoot) {
                    // Show storage volumes
                    items(storageVolumes) { volume ->
                      val volumePath = StorageVolumeUtils.getVolumePath(volume)
                      if (volumePath != null) {
                        StorageVolumeItem(
                          context = context,
                          volume = volume,
                          volumePath = volumePath,
                          onClick = { selectedPath = volumePath },
                        )
                      }
                    }
                    if (storageVolumes.isEmpty()) {
                      item {
                         Text("未找到存储设备", modifier = Modifier.padding(16.dp))
                      }
                    }
                  } else {
                    // 推荐区置顶：用整块高亮卡片把它和下面的普通条目分开，
                    // 否则「番号一致的那一条」混在几十行同款文件名里，等于没推荐。
                    if (recommended.isNotEmpty()) {
                      item(key = "recommended-header") {
                        RecommendedHeader(count = recommended.size, label = matchQuery?.label)
                      }
                      items(recommended, key = { it.file.absolutePath }) { rec ->
                        RecommendedFileItem(
                          file = rec.file,
                          reasonText = stringResource(rec.reason.labelRes()),
                          onClick = { onFileSelected(rec.file.absolutePath) },
                        )
                      }
                    }
                    // Show folders
                    items(dirs, key = { it.absolutePath }) { folder ->
                      FolderItem(
                        folder = folder,
                        onClick = { selectedPath = folder.absolutePath },
                      )
                    }
                    // Show files
                    items(others, key = { it.absolutePath }) { file ->
                        FileItem(
                            file = file,
                            onClick = { onFileSelected(file.absolutePath) }
                        )
                    }
                    if (dirs.isEmpty() && recommended.isEmpty() && others.isEmpty()) {
                      item {
                        Text(
                          text =
                            if (tokens.isEmpty()) {
                              stringResource(R.string.file_picker_empty)
                            } else {
                              stringResource(R.string.file_picker_no_match, filter.trim())
                            },
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.padding(16.dp),
                        )
                      }
                    }
                  }
                }
              }

              // Footer Section (Analyze padding here)
              Row(
                  modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                  horizontalArrangement = Arrangement.End
              ) {
                  TextButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.extraLarge,
                    // Reduced padding for the button itself if needed, or rely on Row padding
                  ) {
                    Text("取消", fontWeight = FontWeight.Medium)
                  }
              }
          }
      }
  }
}

/**
 * 一条「疑似本片字幕」。存 `score` 只为排序，UI 只用到 [reason] 的那句话。
 *
 * 不直接用 `LocalSubtitleMatch.Hit` 是因为排序时 `compareByDescending` 需要同时拿到分数和文件，
 * 中间多一个 Pair 反而更难读。
 */
private data class RecommendedFile(
  val file: File,
  val reason: LocalSubtitleMatch.Reason,
  val score: Int,
)

/** 文件名的「自然序」（`2.srt` 在 `10.srt` 前面），与浏览器其它列表同一口径。 */
private val NAME_ORDER: Comparator<File> =
  Comparator { f1, f2 ->
    SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name)
  }

/**
 * 筛选词的判定：小写后按空格分词，**每个词都要出现**（AND），空词表视为全通过。
 *
 * 与播放器字幕面板的筛选完全一致：打 `ssis 123` 和 `ssis-123` 都能筛出同一批，
 * 连续输入时也不至于因为前缀没打完就整屏清空。
 */
private fun matchesTokens(name: String, tokens: List<String>): Boolean {
  if (tokens.isEmpty()) return true
  val lowered = name.lowercase()
  return tokens.all { lowered.contains(it) }
}

/** 命中理由的文案。与字幕面板 `SubtitleTracksSheet` 共用同一套字符串资源。 */
private fun LocalSubtitleMatch.Reason.labelRes(): Int = when (this) {
  LocalSubtitleMatch.Reason.Stem -> R.string.player_sheets_local_subs_reason_stem
  LocalSubtitleMatch.Reason.Code -> R.string.player_sheets_local_subs_reason_code
  LocalSubtitleMatch.Reason.Crc -> R.string.player_sheets_local_subs_reason_crc
  LocalSubtitleMatch.Reason.Episode -> R.string.player_sheets_local_subs_reason_episode
  LocalSubtitleMatch.Reason.Title -> R.string.player_sheets_local_subs_reason_title
}

/**
 * 推荐区的标题行：星标 + 「可能匹配：番号」+ 条数。
 *
 * 单独一行而不是塞进第一张卡片里，是因为下面每张卡片都要能整块点击选中；
 * 标题如果可点，用户很容易误以为点它会「一键选中全部」。
 */
@Composable
private fun RecommendedHeader(
  count: Int,
  label: String?,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Star,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text =
        if (label.isNullOrBlank()) {
          stringResource(R.string.file_picker_recommended_header_plain)
        } else {
          stringResource(R.string.file_picker_recommended_header, label)
        },
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    Text(
      text = stringResource(R.string.file_picker_recommended_count, count),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 推荐区里的一条字幕文件：整行可点，点了直接挂载。
 *
 * 高亮用 `primaryContainer` 满铺 + 星标 + 第二行的命中理由（「番号一致」/「同 CRC」），
 * 三件事都给上，是因为单靠颜色在高亮/深色主题下不足以让人一眼分辨，
 * 而理由那一行还顺带回答了「凭什么推荐它」。
 */
@Composable
private fun RecommendedFileItem(
  file: File,
  reasonText: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(24.dp),
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = file.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.basicMarquee(
          animationMode = MarqueeAnimationMode.Immediately,
          repeatDelayMillis = 2000,
        ),
      )
      Text(
        text = reasonText,
        style = MaterialTheme.typography.bodySmall,
        color = contentColor.copy(alpha = 0.72f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      imageVector = Icons.Default.Star,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun StorageVolumeItem(
  context: Context,
  volume: android.os.storage.StorageVolume,
  volumePath: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val description = volume.getDescription(context)
  val isPrimary = volume.isPrimary
  val isRemovable = volume.isRemovable
  
  val icon = when {
    isPrimary -> Icons.Default.Home
    isRemovable && volumePath.contains("usb", ignoreCase = true) -> Icons.Default.Usb
    isRemovable -> Icons.Default.SdCard
    else -> Icons.Default.Folder
  }
  
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(32.dp),
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = description,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = volumePath,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun FolderItem(
  folder: File,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Folder,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(28.dp),
    )
    Text(
      text = folder.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.basicMarquee(
        animationMode = MarqueeAnimationMode.Immediately,
        repeatDelayMillis = 2000,
      ),
    )
  }
}

@Composable
private fun FileItem(
  file: File,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.secondary,
      modifier = Modifier.size(28.dp),
    )
    Text(
      text = file.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.basicMarquee(
        animationMode = MarqueeAnimationMode.Immediately,
        repeatDelayMillis = 2000,
      ),
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavigationButtons(
  selectedPath: String?,
  onBack: () -> Unit,
  onHome: () -> Unit,
  onSystemPicker: () -> Unit,
  onToggleSearch: (() -> Unit)? = null,
  searchActive: Boolean = false,
  buttonSize: Dp,
  iconSize: Dp,
) {
  if (selectedPath != null) {
    FilledTonalIconButton(
      onClick = onBack,
      modifier = Modifier.size(buttonSize),
      colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    ) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(iconSize))
    }
  }

  FilledTonalIconButton(
    onClick = onHome,
    modifier = Modifier.size(buttonSize),
    colors = IconButtonDefaults.filledTonalIconButtonColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
  ) {
    Icon(Icons.Default.Home, "Home", modifier = Modifier.size(iconSize))
  }

  FilledTonalIconButton(
    onClick = onSystemPicker,
    modifier = Modifier.size(buttonSize),
    colors = IconButtonDefaults.filledTonalIconButtonColors(
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
  ) {
    Icon(Icons.Default.DriveFolderUpload, "System Picker", modifier = Modifier.size(iconSize))
  }

  // 搜索是个「开关」：展开时按钮换成关闭态并吃主色容器，
  // 让「框现在打开着」这件事在框被键盘挡住时也看得出来。
  if (onToggleSearch != null) {
    FilledTonalIconButton(
      onClick = onToggleSearch,
      modifier = Modifier.size(buttonSize),
      colors =
        IconButtonDefaults.filledTonalIconButtonColors(
          containerColor =
            if (searchActive) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.secondaryContainer
            },
          contentColor =
            if (searchActive) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSecondaryContainer
            },
        ),
    ) {
      Icon(
        imageVector = if (searchActive) Icons.Default.Close else Icons.Default.Search,
        contentDescription = stringResource(R.string.file_picker_filter_hint),
        modifier = Modifier.size(iconSize),
      )
    }
  }
}

