package app.marlboroadvance.mpvex.ui.browser.states

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 「正在扫描」整屏态：图标呼吸 + 标题 + 说明。
 *
 * 只有一个调用点（`FolderListScreen` 的本地目录扫描）。扫描是**秒级到分钟级**的
 * 长任务，用骨架屏不合适（没有已知版式），所以保留这种「图标 + 文案」形态。
 *
 * 说明文案由调用方给（扫描进度），这里的默认值必须是中文 ——
 * 早前默认写的是英文 "Please wait while we search your device"，
 * 传了 `scanStatus` 的路径没问题，但 `scanStatus == null` 时会漏出英文。
 */
@Composable
fun LoadingState(
  icon: ImageVector = Icons.Filled.FolderOpen,
  title: String = "正在扫描视频…",
  message: String = "正在搜索设备上的视频文件",
  modifier: Modifier = Modifier,
) {
  // Animated alpha for subtle pulsing effect (same as EmptyState)
  val infiniteTransition = rememberInfiniteTransition(label = "loading_state")
  val alpha by infiniteTransition.animateFloat(
    initialValue = 0.6f,
    targetValue = 1f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(2500, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
      ),
    label = "icon_alpha",
  )

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 48.dp)
        .padding(bottom = 80.dp), // Account for bottom navigation bar
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // Icon with Surface (same style as EmptyState)
      Surface(
        modifier =
          Modifier
            .size(96.dp)
            .alpha(alpha),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.padding(24.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Title
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Message
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
      )
    }
  }
}
