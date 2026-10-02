package app.marlboroadvance.mpvex.ui.player.controls.components.panels
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.SliderItem
import app.marlboroadvance.mpvex.ui.player.controls.CARDS_MAX_WIDTH
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
  val preferences = koinInject<SubtitlesPreferences>()
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.Default.Tune, null)
        Text(stringResource(R.string.player_sheets_sub_misc_card_title))
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    ProvidePreferenceLocals {
      Column {
        var overrideAssSubs by remember {
          mutableStateOf(PlayerLib.getPropertyString("sub-ass-override") == "force")
        }
        SwitchPreference(
          overrideAssSubs,
          onValueChange = {
            overrideAssSubs = it
            preferences.overrideAssSubs.set(it)
            PlayerLib.setPropertyString("sub-ass-override", if (it) "force" else "scale")
            PlayerLib.setPropertyString("secondary-sub-ass-override", if (it) "force" else "scale")
          },
          { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
        )
        // 字幕大小基准：开 = 跟随画面（横竖屏观感一致），关 = 随窗口/屏幕大小。
        // 对应 mpv 的 `sub-scale-with-window=no` —— 关掉「随窗口缩放」后，字号改成按
        // **画面高度**等比。开着时 mpv 按窗口高度缩放，竖屏窗口高会把字放大好几倍。
        var followPicture by remember {
          mutableStateOf(PlayerLib.getPropertyString("sub-scale-with-window") != "yes")
        }
        SwitchPreference(
          followPicture,
          onValueChange = {
            followPicture = it
            preferences.followPictureScale.set(it)
            val withWindow = if (it) "no" else "yes"
            PlayerLib.setPropertyString("sub-scale-with-window", withWindow)
            PlayerLib.setPropertyString("secondary-sub-scale-with-window", withWindow)
          },
          { Text(stringResource(R.string.player_sheets_sub_follow_picture)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_follow_picture_summary)) },
        )
        val subScale by PlayerLib.propFloat["sub-scale"].collectAsState()
        val subPos by PlayerLib.propInt["sub-pos"].collectAsState()
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_scale),
          value = subScale ?: preferences.subScale.get(),
          valueText = (subScale ?: preferences.subScale.get()).toFixed(2).toString(),
          onChange = {
            preferences.subScale.set(it)
            PlayerLib.setPropertyFloat("sub-scale", it)
          },
          max = 5f,
          icon = {
            Icon(
              Icons.Default.FormatSize,
              null,
            )
          },
        )
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_position),
          value = subPos ?: preferences.subPos.get(),
          valueText = (subPos ?: preferences.subPos.get()).toString(),
          onChange = {
            preferences.subPos.set(it)
            PlayerLib.setPropertyInt("sub-pos", it)
          },
          max = 150,
          icon = {
            Icon(
              Icons.Default.AlignVerticalCenter,
              null,
            )
          },
        )
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = {
              preferences.subPos.deleteAndGet().let {
                PlayerLib.setPropertyInt("sub-pos", it)
              }
              preferences.subScale.deleteAndGet().let {
                PlayerLib.setPropertyFloat("sub-scale", it)
              }
              val defaultOverride = preferences.overrideAssSubs.deleteAndGet()
              overrideAssSubs = defaultOverride
              PlayerLib.setPropertyString("sub-ass-override", if (defaultOverride) "force" else "scale")
              PlayerLib.setPropertyString("secondary-sub-ass-override", if (defaultOverride) "force" else "scale")
              val defaultFollowPicture = preferences.followPictureScale.deleteAndGet()
              followPicture = defaultFollowPicture
              val withWindow = if (defaultFollowPicture) "no" else "yes"
              PlayerLib.setPropertyString("sub-scale-with-window", withWindow)
              PlayerLib.setPropertyString("secondary-sub-scale-with-window", withWindow)
            },
          ) {
            Row {
              Icon(Icons.Default.EditOff, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
    }
  }
}
