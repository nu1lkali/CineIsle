package app.marlboroadvance.mpvex.ui.browser.networkstreaming.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils

/**
 * 下载进度条（常驻**底栏**，不是弹窗）。
 *
 * ## 为什么是底栏
 *
 * 下载是「点了就可以去干别的」的动作：挂个模态框会逼用户盯着进度条等几十分钟。
 * 放在 `Scaffold.bottomBar` 而不是浮在内容上，是因为 bottomBar **占位**——
 * 列表会被顶上去，最后一行永远不会被这条盖住（浮层最容易踩的就是这个）。
 *
 * ## 三段文案
 *
 * - **进行中**：文件名 / 阶段 + 进度条 + `已传 / 总量 · 速度`，右侧一个取消按钮；
 * - **成功**：`已保存` + 落盘路径（用户下一步就是去那儿找），右侧关闭；
 * - **失败 / 取消**：原因 + 重试（原始条目还在，重试不用重新选一遍）。
 *
 * @param onCancel 取消当前任务（半成品会被删掉）
 * @param onDismiss 收起这条提示（任务还在跑时收起 = 只是不看了，**不取消**）
 * @param onRetry 失败后重来一次
 */
@Composable
fun NetworkDownloadBar(
  progress: NetworkDownloadProgress,
  onCancel: () -> Unit,
  onDismiss: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 3.dp,
    shadowElevation = 6.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
      // ── 标题行：图标 + 名字 + 右上角动作 ──
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = progress.statusIcon(),
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = progress.statusTint(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = progress.title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = if (progress.running) onCancel else onDismiss, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = if (progress.running) "取消下载" else "关闭",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      when (progress.state) {
        NetworkDownloadState.Scanning, NetworkDownloadState.Running -> RunningBody(progress)
        NetworkDownloadState.Success -> ResultBody(
          text = progress.outputLabel?.let { "已保存到 $it" } ?: "已保存",
          // 成功时 errorMessage 里装的是「跳过了几个文件 / 截断了」这类说明，
          // 用正文色而不是错误色 —— 它不是一个失败
          detail = progress.errorMessage,
          tint = MaterialTheme.colorScheme.primary,
          action = null,
        )
        NetworkDownloadState.Failed -> ResultBody(
          text = progress.errorMessage ?: "下载失败",
          detail = null,
          tint = MaterialTheme.colorScheme.error,
          action = { TextButton(onClick = onRetry) { Text("重试") } },
        )
        NetworkDownloadState.Cancelled -> ResultBody(
          text = "已取消下载",
          detail = progress.outputLabel?.let { "未完成的临时文件已清理" },
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          action = { TextButton(onClick = onRetry) { Text("重新下载") } },
        )
      }
    }
  }
}

/** 进行中：进度条 + 字节数 / 速度 + 当前文件 */
@Composable
private fun RunningBody(progress: NetworkDownloadProgress) {
  val fraction = progress.progress
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (progress.indeterminate) {
      // 总量未知（服务端没给 size）：不定态光条，别假装知道进度
      LinearProgressIndicator(modifier = Modifier.weight(1f).height(6.dp))
    } else {
      // ⚠️ Material3 1.4 起 `progress: Float` 重载已废弃，必须传 lambda
      LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.weight(1f).height(6.dp),
      )
    }
    Spacer(Modifier.width(10.dp))
    Text(
      text = if (progress.indeterminate) "…" else "${(fraction * 100).toInt()}%",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
  }

  Spacer(Modifier.height(8.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = progress.byteText(),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (progress.speedBps > 0) {
      Text(
        text = "${MediaFormatUtils.formatFileSize(progress.speedBps)}/s",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  val detail = progress.phaseDetail()
  if (detail.isNotBlank()) {
    Spacer(Modifier.height(2.dp))
    Text(
      text = detail,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** 结束态：一行结论 +（可选）说明 +（可选）动作按钮 */
@Composable
private fun ResultBody(
  text: String,
  detail: String?,
  tint: Color,
  action: (@Composable () -> Unit)?,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tint,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (!detail.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (action != null) {
      Spacer(Modifier.width(4.dp))
      action()
    }
  }
}

/**
 * 字节数文案。
 *
 * 打包下载时还要带「第几个 / 共几个」—— 一个大目录里全是小文件，
 * 光看字节数几乎不动，用户会以为卡住了。
 */
private fun NetworkDownloadProgress.byteText(): String {
  val done = MediaFormatUtils.formatFileSize(doneBytes)
  val base =
    if (totalBytes > 0) {
      "$done / ${MediaFormatUtils.formatFileSize(totalBytes)}"
    } else {
      done
    }
  return if (isDirectory && totalFiles > 0) "$base · ${doneFiles + 1} / $totalFiles 个文件" else base
}

private fun NetworkDownloadProgress.phaseDetail(): String =
  when (state) {
    NetworkDownloadState.Scanning -> phase
    NetworkDownloadState.Running ->
      if (currentFile.isNotBlank()) "${phase} · $currentFile" else phase
    else -> ""
  }

private fun NetworkDownloadProgress.statusIcon(): ImageVector =
  when (state) {
    NetworkDownloadState.Success -> Icons.Default.CheckCircle
    NetworkDownloadState.Failed -> Icons.Default.Warning
    NetworkDownloadState.Cancelled -> Icons.Default.Close
    NetworkDownloadState.Scanning, NetworkDownloadState.Running ->
      if (isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile
  }

@Composable
private fun NetworkDownloadProgress.statusTint() =
  when (state) {
    NetworkDownloadState.Success -> MaterialTheme.colorScheme.primary
    NetworkDownloadState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.secondary
  }

/**
 * 底栏容器：没有任务时整条消失（**不留一条空白**），有任务时带展开/收起动画。
 *
 * 单独抽出来是为了让 `Scaffold(bottomBar = ...)` 那一行保持一句 ——
 * 调用方不需要自己写 `AnimatedVisibility` 与空值判断。
 */
@Composable
fun NetworkDownloadBarHost(
  progress: NetworkDownloadProgress?,
  onCancel: () -> Unit,
  onDismiss: () -> Unit,
  onRetry: () -> Unit,
) {
  // 收起动画期间 progress 已经变成 null，但内容还要再画几帧 ——
  // 用普通数组兜住最后一份非空值（不是 State，避免在组合里写状态触发重组）
  val holder = remember { arrayOfNulls<NetworkDownloadProgress>(1) }
  if (progress != null) holder[0] = progress
  AnimatedVisibility(
    visible = progress != null,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    holder[0]?.let { shown ->
      NetworkDownloadBar(
        progress = shown,
        onCancel = onCancel,
        onDismiss = onDismiss,
        onRetry = onRetry,
      )
    }
  }
}
