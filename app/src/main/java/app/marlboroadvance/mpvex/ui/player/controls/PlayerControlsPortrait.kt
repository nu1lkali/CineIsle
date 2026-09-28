package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsGroup
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing
import dev.vivvvek.seeker.Segment

@Composable
fun TopPlayerControlsPortrait(
  mediaTitle: String?,
  hideBackground: Boolean,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  viewModel: PlayerViewModel,
  /** 顶栏右侧的快捷开关（竖屏把「锁屏 / 收藏 / 更多」这类低频按钮放这里，底部就不会挤成一排） */
  trailing: (@Composable () -> Unit)? = null,
) {
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val clickEvent = LocalPlayerButtonsClickEvent.current

  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // weight(1f)：标题组吃掉除右侧快捷开关以外的空间，标题过长时自己省略号收尾
      ControlsGroup(modifier = Modifier.weight(1f)) {
        ControlsButton(
          icon = Icons.AutoMirrored.Default.ArrowBack,
          onClick = onBackPress,
          color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
        )

        val titleInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

        androidx.compose.foundation.layout.Box(
          modifier =
            Modifier
              .clip(RoundedCornerShape(50))
              .clickable(
                enabled = playlistModeEnabled,
                onClick = {
                  clickEvent()
                  onOpenSheet(Sheets.Playlist)
                },
              ),
        ) {
          Surface(
            shape = RoundedCornerShape(50),
            color =
              if (hideBackground) {
                Color.Transparent
              } else {
                MaterialTheme.colorScheme.surfaceContainer.copy(
                  alpha = 0.55f,
                )
              },
            contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border =
              if (hideBackground) {
                null
              } else {
                BorderStroke(
                  1.dp,
                  PlayerControlGlassBorder,
                )
              },
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
              modifier =
                Modifier.padding(
                  horizontal = MaterialTheme.spacing.medium,
                  vertical = MaterialTheme.spacing.small,
                ),
            ) {
              viewModel.getPlaylistInfo()?.let { playlistInfo ->
                Text(
                  text = playlistInfo,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Visible,
                  fontFamily = FontFamily.Monospace,
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = Typography.bullet.toString(),
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                  overflow = TextOverflow.Clip,
                )
              }
              Text(
                text = mediaTitle ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
              )
            }
          }
        }
      }

      if (trailing != null) {
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.extraSmall))
        trailing()
      }
    }
  }
}

@Composable
fun BottomPlayerControlsPortrait(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
  val spacing = MaterialTheme.spacing

  // 竖屏底部只保留「一条按钮带」，位置落在屏幕下缘的视频黑边里，不压画面。
  //
  // 形态沿用官方客户端的经典做法：单行、按钮 44dp、间距收紧。
  // 外层 Box 负责对齐策略 —— 放得下时整条居中；放不下时贴左（首屏就能看见最常用的几个）并可横向滑动，
  // 避免换行堆成两三排把画面吃掉一大块。
  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      buttons.forEach { button ->
        RenderPlayerButton(
          button = button,
          chapters = chapters,
          currentChapter = currentChapter,
          isPortrait = true,
          isSpeedNonOne = isSpeedNonOne,
          currentZoom = currentZoom,
          aspect = aspect,
          mediaTitle = mediaTitle,
          hideBackground = hideBackground,
          onBackPress = onBackPress,
          onOpenSheet = onOpenSheet,
          onOpenPanel = onOpenPanel,
          viewModel = viewModel,
          activity = activity,
          decoder = decoder,
          playbackSpeed = playbackSpeed,
          buttonSize = 44.dp,
        )
      }
    }
  }
}

