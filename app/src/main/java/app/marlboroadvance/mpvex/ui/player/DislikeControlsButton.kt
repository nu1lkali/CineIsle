package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.theme.controlColor

/**
 * 「标记为不喜欢」按钮 —— mpv 控制栏用（走 [ControlsButton] 的玻璃底风格）。
 *
 * ## 选中态怎么表达
 *
 * 用**实心图标 + error 色**，未选中是**空心 + 常规控件色**。
 * 刻意不用「加个角标 / 变背景色」：控制条上已有好几个按钮会变色
 * （循环 / 随机 / 镜像都用 primary 色），再多一个同色系的会分不清谁是谁。
 * 图标从空心变实心 + 颜色转红，是两个独立通道，混淆概率低得多。
 *
 * ## 为什么是心碎
 *
 * 原先用的是「拇指向下」，反馈**看不懂** —— 它在中文语境里先被读成
 * 「点踩 / 差评」，而这里表达的是「我不想再看到这部」，与收藏（心形）是一对。
 * 心碎与心形构成对仗，一眼能懂。GSY / 视界流都用同一枚，三套播放器保持一致。
 *
 * ## 长按
 *
 * 长按弹备注框，**单击永不弹**（弹框会挡画面）。见 [DislikeRemarkDialog]。
 *
 * @param enabled 当前条目没有可标记的 id 时置灰（非 Emby 播放）。
 */
@Composable
fun DislikeControlsButton(
  isMarked: Boolean,
  onToggle: () -> Unit,
  onLongPress: () -> Unit,
  buttonSize: Dp,
  controlColorValue: androidx.compose.ui.graphics.Color = controlColor,
  enabled: Boolean = true,
) {
  ControlsButton(
    icon = if (isMarked) Icons.Filled.HeartBroken else Icons.Outlined.HeartBroken,
    onClick = onToggle,
    onLongClick = onLongPress,
    color = if (isMarked) MaterialTheme.colorScheme.error else controlColorValue,
    enabled = enabled,
    modifier = Modifier.size(buttonSize),
  )
}