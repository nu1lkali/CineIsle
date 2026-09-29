package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing

/**
 * 竖屏顶栏。
 *
 * 只有两段：左侧返回键，右侧一串快捷开关（解码器 / 音轨 / 字幕 / 收藏 / 更多）。
 *
 * **标题不在这里** —— 竖屏顶栏要塞下返回键 + 5 个开关，剩给标题的宽度只有 ~95dp，
 * 再长的片名都会被压成省略号（用户反馈「视频标题看不全」）。标题改由
 * [PortraitBottomTitle] 渲染在进度条正上方，能独占整行宽度。
 */
@Composable
fun TopPlayerControlsPortrait(
  hideBackground: Boolean,
  onBackPress: () -> Unit,
  /** 顶栏右侧的快捷开关（竖屏把「解码器 / 音轨 / 字幕 / 收藏 / 更多」放这里） */
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ControlsButton(
      icon = Icons.AutoMirrored.Default.ArrowBack,
      onClick = onBackPress,
      color = controlColor,
    )

    // 返回键与快捷开关各占一端，中间留白
    Spacer(modifier = Modifier.weight(1f))

    if (trailing != null) {
      Spacer(modifier = Modifier.width(MaterialTheme.spacing.extraSmall))
      trailing()
    }
  }
}

/**
 * 竖屏底部的媒体标题，压在进度条正上方、占满整行。
 *
 * 为什么挪到下面：
 * 顶栏场景下标题可用的宽度只有 ~95dp（返回键 + 5 个快捷开关之后），中文片名 7~8 个字
 * 就到头了。放到进度条上方后可用宽度约 380dp，常见片名基本能一次显示完；
 * 仍然超长的用跑马灯滚一遍（[basicMarquee]），保证「完整标题一定看得到」。
 *
 * 有播放队列时整行可点击 → 打开队列面板（原顶栏标题胶囊就是这个行为）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortraitBottomTitle(
  mediaTitle: String?,
  playlistInfo: String?,
  hideBackground: Boolean,
  clickable: Boolean,
  onClick: () -> Unit,
) {
  val textColor = controlColor
  // 底部控件层下面就是画面，加一层阴影让白字在任何底图上都看得清
  val textShadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .clickable(enabled = clickable, onClick = onClick)
        .padding(horizontal = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    // 序号角标 + 片名作为一个整体水平居中，不再顶着左边排
    horizontalArrangement = Arrangement.Center,
  ) {
    if (!playlistInfo.isNullOrBlank()) {
      PlaylistIndexBadge(text = playlistInfo)
      Spacer(modifier = Modifier.width(8.dp))
    }

    Text(
      text = mediaTitle ?: "",
      style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
      fontFamily = FontFamily.Monospace,
      color = textColor,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.basicMarquee(),
    )
  }
}
