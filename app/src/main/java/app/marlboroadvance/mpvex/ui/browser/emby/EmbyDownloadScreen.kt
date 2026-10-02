package app.marlboroadvance.mpvex.ui.browser.emby

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadTask
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * 下载管理页。
 *
 * 三类页面（首页 / 收藏 / 历史）右上角的下载图标都会进到这里。
 * 每条任务都能单独暂停 / 继续 / 取消——「继续」走 HTTP Range 断点续传，
 * 不会把已经下好的部分丢掉。
 */
@Serializable
object EmbyDownloadScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyDownloadViewModel = viewModel(
      factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
    )
    val tasks by viewModel.tasks.collectAsState()

    val anyActive = tasks.any { it.isActive }
    val anyPausable = tasks.any { it.isActive || it.status == EmbyDownloadStatus.PAUSED }
    val anyCompleted = tasks.any { it.status == EmbyDownloadStatus.COMPLETED }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("下载管理") },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
          actions = {
            IconButton(
              onClick = { if (anyActive) viewModel.pauseAll() else viewModel.resumeAll() },
              enabled = anyPausable,
            ) {
              Icon(
                imageVector = if (anyActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (anyActive) "全部暂停" else "全部继续",
              )
            }
            IconButton(onClick = { viewModel.clearCompleted() }, enabled = anyCompleted) {
              Icon(Icons.Default.DeleteSweep, contentDescription = "清除已完成")
            }
          },
        )
      },
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (tasks.isEmpty()) {
          EmbyEmptyState(
            message = "还没有下载任务\n在媒体详情页点「下载」即可离线保存",
            buttonText = "返回",
            onAction = { backStack.removeLastOrNull() },
            modifier = Modifier.align(Alignment.Center),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(tasks, key = { it.itemId }) { task ->
              DownloadTaskCard(
                task = task,
                onPause = { viewModel.pause(task.itemId) },
                onResume = { viewModel.resume(task.itemId) },
                onPrioritize = { viewModel.prioritize(task.itemId) },
                onCancel = { viewModel.remove(task.itemId, deleteFile = true) },
                onDiscard = { viewModel.remove(task.itemId, deleteFile = false) },
                onOpen = {
                  val uri = task.targetUri?.let { Uri.parse(it) }
                  if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                      setDataAndType(uri, "video/*")
                      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DownloadTaskCard(
  task: EmbyDownloadTask,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onPrioritize: () -> Unit,
  onCancel: () -> Unit,
  onDiscard: () -> Unit,
  onOpen: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = task.fileName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = statusLine(task),
            style = MaterialTheme.typography.bodySmall,
            color = if (task.status == EmbyDownloadStatus.FAILED) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (task.status == EmbyDownloadStatus.COMPLETED) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }

      DownloadProgressBar(progress = task.progressFraction)

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (task.status) {
          EmbyDownloadStatus.RUNNING, EmbyDownloadStatus.QUEUED -> {
            OutlinedButton(onClick = onPause) { Text("暂停") }
            // 置顶只对「排队中」有意义：正在下的那条本来就占着并发位
            if (task.status == EmbyDownloadStatus.QUEUED) {
              TextButton(onClick = onPrioritize) { Text("置顶") }
            }
            TextButton(onClick = onCancel) { Text("取消") }
          }

          EmbyDownloadStatus.PAUSED -> {
            OutlinedButton(onClick = onResume) { Text("继续下载") }
            // 暂停中的任务恢复后会重新排队，置顶同样影响它的启动顺序
            TextButton(onClick = onPrioritize) { Text("置顶") }
            TextButton(onClick = onCancel) { Text("取消") }
          }

          EmbyDownloadStatus.FAILED -> {
            OutlinedButton(onClick = onResume) { Text("重试") }
            TextButton(onClick = onDiscard) { Text("删除记录") }
          }

          EmbyDownloadStatus.COMPLETED -> {
            if (task.targetUri?.startsWith("content://") == true) {
              TextButton(onClick = onOpen) { Text("打开") }
            }
            TextButton(onClick = onDiscard) { Text("移除记录") }
          }
        }
      }
    }
  }
}

/**
 * 进度条：已知总大小画确定进度，未知就画来回扫的动画条。
 *
 * 自己用 Box 画而不用 `LinearProgressIndicator`，是为了不绑 Material3 的进度 API
 * （几个 alpha 版本签名不同，容易升级即编译失败）。
 */
@Composable
private fun DownloadProgressBar(progress: Float?) {
  val trackColor = MaterialTheme.colorScheme.surface
  val barColor = MaterialTheme.colorScheme.primary

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(6.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(trackColor),
  ) {
    val fullWidth = maxWidth
    if (progress != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth(progress.coerceIn(0f, 1f))
          .fillMaxHeight()
          .background(barColor),
      )
    } else {
      val transition = rememberInfiniteTransition(label = "download_indeterminate")
      val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(durationMillis = 1100, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
        label = "download_indeterminate_offset",
      )
      val segment = fullWidth * 0.32f
      val travel = fullWidth - segment
      Box(
        modifier = Modifier
          .offset(x = travel * offset)
          .width(segment)
          .fillMaxHeight()
          .background(barColor),
      )
    }
  }
}

private fun statusLine(task: EmbyDownloadTask): String {
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
      val eta = etaText(total - done, task.speedBps)
      if (eta != null) append(" · $eta")
    }

    EmbyDownloadStatus.PAUSED -> buildString {
      append("已暂停")
      if (percent != null) append(" · $percent%")
      if (total > 0) append(" · ${formatBytes(done)} / ${formatBytes(total)}")
      task.message?.let { append(" · $it") }
    }

    EmbyDownloadStatus.FAILED -> "下载失败：${task.message ?: "未知错误"}"

    EmbyDownloadStatus.COMPLETED ->
      "已保存到 ${task.locationLabel ?: "Download"}"
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

/**
 * 顶栏下载入口：首页 / 收藏 / 历史三个页面共用。
 *
 * 有正在下载或排队的任务时，图标右上角挂一个红色数字角标，
 * 这样用户不用进下载页也能知道后台还在下东西。
 */
@Composable
fun EmbyDownloadEntryButton() {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val viewModel: EmbyDownloadViewModel = viewModel(
    factory = EmbyDownloadViewModel.factory(context.applicationContext as Application),
  )
  val tasks by viewModel.tasks.collectAsState()
  val active = tasks.count { it.isActive }

  Box {
    IconButton(onClick = { backStack.add(EmbyDownloadScreen) }) {
      Icon(Icons.Default.Download, contentDescription = "下载管理")
    }
    if (active > 0) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 2.dp, end = 2.dp)
          .size(16.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (active > 9) "9+" else active.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onError,
        )
      }
    }
  }
}
