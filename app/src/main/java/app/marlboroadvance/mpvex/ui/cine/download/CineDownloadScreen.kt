package app.marlboroadvance.mpvex.ui.cine.download

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadTask
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyDownloadViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyOfflineScreen
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassPanel
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import java.util.Locale

/**
 * Cine 版「下载管理」。
 *
 * 数据与动作全部复用 [EmbyDownloadViewModel]（暂停 / 继续 / 置顶 / 取消 / 移除 /
 * 全部暂停 / 清已完成），这里只换皮：
 * 顶栏玻璃条 + 任务玻璃卡 + 细进度条 + 药丸动作按钮。
 *
 * 顶部还保留 Classic 那个「离线内容」入口（[EmbyOfflineScreen]）：
 * 「正在下」和「已下好」本来是该对照看的两件事，入口放在同一页最顺。
 */
@Composable
fun CineDownloadScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
  )
  val tasks by viewModel.tasks.collectAsState()

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  val anyActive = tasks.any { it.isActive }
  val anyPausable = tasks.any { it.isActive || it.status == EmbyDownloadStatus.PAUSED }
  val anyCompleted = tasks.any { it.status == EmbyDownloadStatus.COMPLETED }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = "下载管理",
      onBack = { backStack.removeLastOrNull() },
      actions = {
        CineIconButton(
          icon = Icons.Outlined.CloudOff,
          contentDescription = "离线内容",
          tint = colors.onSurfaceVariant,
          onClick = { backStack.add(EmbyOfflineScreen) },
        )
        CineIconButton(
          icon = if (anyActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
          contentDescription = if (anyActive) "全部暂停" else "全部继续",
          tint = if (anyPausable) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f),
          onClick = { if (anyActive) viewModel.pauseAll() else viewModel.resumeAll() },
        )
        CineIconButton(
          icon = Icons.Filled.DeleteSweep,
          contentDescription = "清除已完成",
          tint = if (anyCompleted) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f),
          onClick = { viewModel.clearCompleted() },
        )
      },
    )

    if (tasks.isEmpty()) {
      CineStatePanel(
        icon = Icons.Filled.Download,
        title = "还没有下载任务",
        description = "在影片详情页点「下载」，即可离线保存到本机",
        primaryLabel = "返回",
        onPrimary = { backStack.removeLastOrNull() },
      )
    } else {
      val activeCount = tasks.count { it.isActive }
      Text(
        text = buildString {
          append("共 ${tasks.size} 个任务")
          if (activeCount > 0) append(" · $activeCount 个进行中")
        },
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = space.screenH, top = 12.dp, bottom = 4.dp),
      )

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .cineHazeSource(LocalCineHaze.current),
        contentPadding = PaddingValues(
          start = space.screenH,
          end = space.screenH,
          top = 8.dp,
          bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(tasks, key = { it.itemId }) { task ->
          CineDownloadCard(
            task = task,
            onPause = { viewModel.pause(task.itemId) },
            onResume = { viewModel.resume(task.itemId) },
            onPrioritize = { viewModel.prioritize(task.itemId) },
            onCancel = { viewModel.remove(task.itemId, deleteFile = true) },
            onDiscard = { viewModel.remove(task.itemId, deleteFile = false) },
            onOpen = {
              val uri = task.targetUri?.let { Uri.parse(it) } ?: return@CineDownloadCard
              val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              runCatching { context.startActivity(intent) }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun CineDownloadCard(
  task: EmbyDownloadTask,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onPrioritize: () -> Unit,
  onCancel: () -> Unit,
  onDiscard: () -> Unit,
  onOpen: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme
  val failed = task.status == EmbyDownloadStatus.FAILED
  val done = task.status == EmbyDownloadStatus.COMPLETED

  CineGlassPanel(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = task.fileName,
            style = type.itemTitle,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = downloadStatusLine(task),
            style = type.cardMeta,
            color = when {
              failed -> scheme.error
              done -> colors.sectionAction
              else -> colors.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (done) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.progressFill,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      CineDownloadProgress(progress = task.progressFraction)

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (task.status) {
          EmbyDownloadStatus.RUNNING, EmbyDownloadStatus.QUEUED -> {
            CinePillAction(label = "暂停", onClick = onPause, filled = true)
            if (task.status == EmbyDownloadStatus.QUEUED) {
              CinePillAction(label = "置顶", onClick = onPrioritize)
            }
            CinePillAction(label = "取消", onClick = onCancel)
          }

          EmbyDownloadStatus.PAUSED -> {
            CinePillAction(label = "继续下载", onClick = onResume, filled = true)
            CinePillAction(label = "置顶", onClick = onPrioritize)
            CinePillAction(label = "取消", onClick = onCancel)
          }

          EmbyDownloadStatus.FAILED -> {
            CinePillAction(label = "重试", onClick = onResume, filled = true)
            CinePillAction(label = "删除记录", onClick = onDiscard)
          }

          EmbyDownloadStatus.COMPLETED -> {
            if (task.targetUri?.startsWith("content://") == true) {
              CinePillAction(label = "打开", onClick = onOpen, filled = true)
            }
            CinePillAction(label = "移除记录", onClick = onDiscard)
          }
        }
      }
    }
  }
}

/** 药丸动作按钮：主操作实心、其余描边。 */
@Composable
private fun CinePillAction(
  label: String,
  onClick: () -> Unit,
  filled: Boolean = false,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = Modifier
      .height(32.dp)
      .clip(shape)
      .background(if (filled) colors.progressFill else Color.Transparent)
      .then(
        if (filled) {
          Modifier
        } else {
          Modifier.background(colors.chipSurface, shape)
        },
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = type.cardMeta.copy(fontSize = 12.5.sp),
      color = if (filled) scheme.onPrimary else colors.onSurface,
      maxLines = 1,
    )
  }
}

/**
 * 进度条：已知总量画确定进度，未知画来回扫的动画条。
 *
 * 自己画而不用 `LinearProgressIndicator`：项目里已有先例（Classic 下载页同样理由），
 * 不绑 M3 那几个 alpha 版本的进度 API。
 */
@Composable
private fun CineDownloadProgress(progress: Float?) {
  val colors = CineTheme.colors
  val shape = remember { RoundedCornerShape(50) }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(5.dp)
      .clip(shape)
      .background(colors.progressTrack),
  ) {
    if (progress != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth(progress.coerceIn(0f, 1f))
          .fillMaxHeight()
          .background(colors.progressFill),
      )
    } else {
      val transition = rememberInfiniteTransition(label = "cine_download_indeterminate")
      val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(durationMillis = 1100, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
        label = "cine_download_offset",
      )
      val segment = maxWidth * 0.32f
      Box(
        modifier = Modifier
          .offset(x = (maxWidth - segment) * offset)
          .width(segment)
          .fillMaxHeight()
          .background(colors.progressFill),
      )
    }
  }
}

private fun downloadStatusLine(task: EmbyDownloadTask): String {
  val total = task.totalBytes
  val done = task.downloadedBytes
  val percent = task.progressFraction?.let { (it * 100).toInt() }

  return when (task.status) {
    EmbyDownloadStatus.QUEUED ->
      if (total > 0) "排队中 · ${formatBytes(done)} / ${formatBytes(total)}" else "排队中"

    EmbyDownloadStatus.RUNNING -> buildString {
      append(if (percent != null) "下载中 $percent%" else "下载中")
      if (total > 0) append(" · ${formatBytes(done)} / ${formatBytes(total)}")
      if (task.speedBps > 0) append(" · ${formatBytes(task.speedBps)}/s")
      etaText(total - done, task.speedBps)?.let { append(" · $it") }
    }

    EmbyDownloadStatus.PAUSED -> buildString {
      append("已暂停")
      if (percent != null) append(" · $percent%")
      if (total > 0) append(" · ${formatBytes(done)} / ${formatBytes(total)}")
      task.message?.let { append(" · $it") }
    }

    EmbyDownloadStatus.FAILED -> "下载失败：${task.message ?: "未知错误"}"

    EmbyDownloadStatus.COMPLETED -> "已保存到 ${task.locationLabel ?: "Download"}"
  }
}

private fun etaText(remaining: Long, speed: Long): String? {
  if (remaining <= 0 || speed <= 0) return null
  val seconds = remaining / speed
  if (seconds <= 0 || seconds > 86_400) return null
  val minutes = seconds / 60
  return if (minutes > 0) "约剩 ${minutes}分${seconds % 60}秒" else "约剩 ${seconds}秒"
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024.0
  var index = 0
  while (value >= 1024.0 && index < units.lastIndex) {
    value /= 1024.0
    index++
  }
  return String.format(Locale.US, "%.1f %s", value, units[index])
}
