package app.marlboroadvance.mpvex.ui.browser.networkstreaming.properties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 目录统计的界面状态（由 ViewModel 提供）。
 *
 * `entry` 与 `running` 可以同时有值：重新统计时旧结果继续显示，只在行尾挂一个转圈 ——
 * 一进面板就把数字清空重算，用户会以为「刚才那个数没了」。
 */
data class DirSizeUiState(
  val running: Boolean = false,
  val entry: DirSizeEntry? = null,
  val error: String? = null,
)

/**
 * 文件 / 目录属性面板（右侧抽屉）。
 *
 * ## 为什么是右侧抽屉而不是对话框
 *
 * 属性是「边看边决定下一步」的东西：看一眼大小，决定要不要删；看一眼路径，
 * 决定要不要进上层。对话框会把整页盖住，看完还得关一次才能操作 —— 抽屉只占右侧一条，
 * 左边列表照旧可点。
 *
 * ## 结构
 *
 * 三组信息按「看它的目的」分组，都能单独收起：
 * - **基本信息**：类型 / 大小 / 修改时间 —— 判断「这是什么、值不值得留」；
 * - **位置**：存储（协议 + 连接名）/ 完整路径 —— 判断「它在哪」；
 * - **目录统计**：仅目录，异步算、结果带缓存（见 [NetworkDirSizeStore]）。
 *
 * @param protocolLabel 协议显示名（AList / SMB / WebDAV / FTP）
 * @param dirSize 目录统计状态；文件传 null
 * @param onRequestDirSize 点「统计 / 重新统计」时触发
 * @param onDownload 下载（目录 = 打包下载）
 */
@Composable
fun NetworkFilePropertiesPanel(
  file: NetworkFile,
  connectionName: String,
  protocolLabel: String,
  dirSize: DirSizeUiState?,
  onRequestDirSize: () -> Unit,
  onDownload: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboardManager.current
  var basicExpanded by remember(file.path) { mutableStateOf(true) }
  var locationExpanded by remember(file.path) { mutableStateOf(true) }
  var sizeExpanded by remember(file.path) { mutableStateOf(true) }

  // 宽度随屏走，但两头都有边界：
  // · 窄屏（360dp）取 86% ⇒ 剩一条能看见列表，不至于把整页盖死；
  // · 宽屏钉死 380dp —— 分屏/平板上铺满会变成一堵墙，属性面板不该抢主内容的地盘。
  val available = LocalConfiguration.current.screenWidthDp.dp
  val panelWidth = (available * 0.86f).coerceAtMost(380.dp)

  Surface(
    modifier = modifier.fillMaxHeight().width(panelWidth),
    color = MaterialTheme.colorScheme.surfaceContainer,
    // 只圆左侧两角：右缘贴着屏幕边，圆角在那儿反而会露出一条缝
    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
  ) {
    Column(modifier = Modifier.fillMaxHeight()) {
      // ── 头部 ──
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Box(
          modifier =
            Modifier
              .size(40.dp)
              .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = file.typeIcon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(22.dp),
          )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = file.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = if (file.isDirectory) "文件夹" else file.typeLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "关闭属性")
        }
      }

      Spacer(Modifier.height(10.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

      // ── 信息区（可滚） ──
      Column(
        modifier =
          Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
      ) {
        PropertySection(
          title = "基本信息",
          expanded = basicExpanded,
          onToggle = { basicExpanded = !basicExpanded },
        ) {
          PropertyRow("类型", if (file.isDirectory) "文件夹" else file.typeLabel())
          PropertyRow("大小", if (file.isDirectory) "见下方目录统计" else MediaFormatUtils.formatFileSize(file.size))
          PropertyRow("修改时间", formatModified(file.lastModified))
          file.mimeType?.takeIf { it.isNotBlank() }?.let { PropertyRow("MIME", it) }
        }

        PropertySection(
          title = "位置",
          expanded = locationExpanded,
          onToggle = { locationExpanded = !locationExpanded },
        ) {
          PropertyRow("存储", "$connectionName · $protocolLabel")
          PropertyRow(
            label = "路径",
            value = "/" + file.path.trim('/'),
            selectable = true,
            trailing = {
              IconButton(
                onClick = { clipboard.setText(AnnotatedString("/" + file.path.trim('/'))) },
                modifier = Modifier.size(28.dp),
              ) {
                Icon(
                  Icons.Default.ContentCopy,
                  contentDescription = "复制路径",
                  modifier = Modifier.size(15.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
          )
        }

        if (file.isDirectory) {
          PropertySection(
            title = "目录统计",
            expanded = sizeExpanded,
            onToggle = { sizeExpanded = !sizeExpanded },
          ) {
            DirSizeBlock(state = dirSize, onRequest = onRequestDirSize)
          }
        }
        Spacer(Modifier.height(8.dp))
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
          Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (file.isDirectory) "打包下载" else "下载到本地")
        }
        TextButton(onClick = onDismiss) { Text("关闭") }
      }
    }
  }
}

/**
 * 属性面板的**宿主**：遮罩 + 从右侧滑入 + 点空白关闭。
 *
 * @param file null = 收起。收起动画期间内容还要再画几帧，所以这里兜了最后一份非空值。
 * @param dirSize 只有目录才需要传（文件行显示「见下方目录统计」没有意义）
 * @param onDownload 传回**当前展示的那个条目**（面板内部不持有条目，只渲染）
 */
@Composable
fun NetworkFilePropertiesOverlay(
  file: NetworkFile?,
  connectionName: String,
  protocolLabel: String,
  dirSize: DirSizeUiState?,
  onRequestDirSize: () -> Unit,
  onDownload: (NetworkFile) -> Unit,
  onDismiss: () -> Unit,
) {
  val holder = remember { arrayOfNulls<NetworkFile>(1) }
  if (file != null) holder[0] = file
  val visible = file != null

  Box(modifier = Modifier.fillMaxSize()) {
    // 遮罩：点它关面板（与「点空白处收起」的常规预期一致）。
    // ⚠️ indication = null：不加的话整屏会闪一下涟漪，非常刺眼
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onDismiss,
            ),
      )
    }

    AnimatedVisibility(
      visible = visible,
      modifier = Modifier.align(Alignment.CenterEnd),
      enter = slideInHorizontally { it },
      exit = slideOutHorizontally { it },
    ) {
      holder[0]?.let { shown ->
        NetworkFilePropertiesPanel(
          file = shown,
          connectionName = connectionName,
          protocolLabel = protocolLabel,
          dirSize = dirSize,
          onRequestDirSize = onRequestDirSize,
          onDownload = { onDownload(shown) },
          onDismiss = onDismiss,
        )
      }
    }
  }
}

/** 目录统计那一块：转圈 → 结果 / 失败，都能「重新统计」 */
@Composable
private fun DirSizeBlock(
  state: DirSizeUiState?,
  onRequest: () -> Unit,
) {
  val entry = state?.entry
  val running = state?.running == true

  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = entry?.sizeText ?: if (running) "统计中…" else "尚未统计",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (entry != null) FontWeight.Medium else FontWeight.Normal,
      color =
        if (entry != null) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.weight(1f),
    )
    if (running) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
  }

  if (entry != null) {
    Spacer(Modifier.height(4.dp))
    Text(
      text = "${entry.fileCount} 个文件 · ${entry.dirCount} 个目录",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (entry.truncated) {
      Text(
        text = "已达统计上限，只算了已扫到的部分",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }

  val error = state?.error
  if (error != null) {
    Spacer(Modifier.height(4.dp))
    Text(
      text = error,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }

  Spacer(Modifier.height(6.dp))
  TextButton(
    onClick = onRequest,
    enabled = !running,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp),
  ) {
    Text(if (entry != null) "重新统计" else "统计大小")
  }
}

/** 可折叠分组：标题行点一下收起 / 展开，右侧箭头跟着转 */
@Composable
private fun PropertySection(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  content: @Composable () -> Unit,
) {
  val rotation by animateFloatAsState(targetValue = if (expanded) 0f else -90f, label = "sectionChevron")
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "收起" else "展开",
        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (expanded) {
      Column(modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)) { content() }
    }
  }
}

/** 一行「标签 : 值」。值太长时换行显示（路径尤其），不做省略号 —— 属性面板里看不到全路径等于白给 */
@Composable
private fun PropertyRow(
  label: String,
  value: String,
  selectable: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(64.dp),
    )
    Spacer(Modifier.width(8.dp))
    val text: @Composable () -> Unit = {
      Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      if (selectable) SelectionContainer { text() } else text()
    }
    if (trailing != null) {
      Spacer(Modifier.width(4.dp))
      trailing()
    }
  }
}

/** 类型文案：视频 / 图片 / 文件（与列表卡片同一口径） */
private fun NetworkFile.typeLabel(): String =
  when {
    isVideoFile() -> "视频"
    isPhoto() -> "图片"
    else -> "文件"
  }

private fun NetworkFile.typeIcon(): ImageVector =
  when {
    isDirectory -> Icons.Default.Folder
    isVideoFile() -> Icons.Default.Movie
    isPhoto() -> Icons.Default.Image
    else -> Icons.Default.InsertDriveFile
  }

private fun formatModified(ts: Long): String =
  if (ts <= 0L) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
