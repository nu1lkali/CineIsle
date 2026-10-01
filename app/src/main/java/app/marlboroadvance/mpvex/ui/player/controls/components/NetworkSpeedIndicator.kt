package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.player.formatNetworkSpeed
import app.marlboroadvance.mpvex.ui.theme.controlColor

/**
 * 播放器顶栏下方的「网络加载速度」指示器。
 *
 * 外观与标题胶囊同一套玻璃质感（圆角 50 + PlayerControlGlassFill + 细边框），
 * 贴在左上控件簇正下方，不占按钮条 / 进度条的位置。
 *
 * @param speedBytesPerSec 当前速度（字节/秒）；null = 非网络流等「不适用」情形，
 *                         整个指示器不渲染。0 显示为 0 KB/s（见 PlayerViewModel 的策略注释）。
 */
@Composable
fun NetworkSpeedIndicator(
  speedBytesPerSec: Long?,
  modifier: Modifier = Modifier,
) {
  // null：本地文件 / 非网络流 —— 直接不渲染，而不是显示一个恒 0 的摆设
  if (speedBytesPerSec == null) return

  Surface(
    shape = RoundedCornerShape(50),
    color = PlayerControlGlassFill,
    contentColor = controlColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(1.dp, PlayerControlGlassBorder),
    modifier = modifier,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 10.dp),
    ) {
      Icon(
        imageVector = Icons.Default.CloudDownload,
        contentDescription = "网络加载速度",
        tint = controlColor.copy(alpha = 0.9f),
        modifier = Modifier
          .padding(vertical = 5.dp)
          .size(14.dp),
      )
      Text(
        text = formatNetworkSpeed(speedBytesPerSec),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = controlColor,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, top = 5.dp, bottom = 5.dp),
      )
    }
  }
}
