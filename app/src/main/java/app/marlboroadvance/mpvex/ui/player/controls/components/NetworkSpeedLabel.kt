package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import app.marlboroadvance.mpvex.ui.player.formatNetworkSpeed

/**
 * 播放器「网络加载速度」文字。
 *
 * ## 为什么是纯文字，而不是玻璃胶囊
 *
 * 它曾经是一个带 `CloudDownload` 图标的玻璃胶囊，跟在顶栏的操作按钮里 ——
 * 那套视觉语言的意思是「这是一个可以点的按钮」，而网速只是一条读数。
 * 更要紧的是它挤在操作簇里跟标题争宽度：横屏顶栏本来就是
 * 「返回 + 标题 + 5 个按钮」，再塞一个胶囊进去，标题立刻被压成省略号。
 *
 * 现在它和时钟 / 电量同处一条状态行（见 [PlayerStatusIndicators]），
 * 用同一档字号、同一档透明度、同一套等宽数字 —— 一眼能读、但不抢戏。
 *
 * @param speedBytesPerSec 当前速度（字节/秒）；null = 非网络流等「不适用」情形，不渲染。
 *                         0 显示成 `↓ 0 KB/s`（暂停 / 缓存已满时的真实读数，见 `MpvNetSpeedSampler`）。
 */
@Composable
fun NetworkSpeedLabel(
  speedBytesPerSec: Long?,
  modifier: Modifier = Modifier,
) {
  if (speedBytesPerSec == null) return

  Text(
    // 「↓」标明这是**下载**方向。本地文件上采样器直接给 null（整条不渲染），
    // 所以不用担心出现一个恒 0 的摆设。
    text = "↓ ${formatNetworkSpeed(speedBytesPerSec)}",
    style = MaterialTheme.typography.labelMedium,
    // 等宽：数字每秒都在变，比例字体会让整行左右抖动
    fontFamily = FontFamily.Monospace,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    maxLines = 1,
    modifier = modifier,
  )
}
