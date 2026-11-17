package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.marlboroadvance.mpvex.presentation.Screen
import me.zhanghai.compose.preference.Preference

/**
 * 按 [SettingsDestination] 渲染一行设置入口。
 *
 * 只做「元数据 → Preference」的映射，导航仍由调用方决定（传 [onOpen]）。
 * 这样 Classic / 未来的 Cine 设置页可以共用同一份入口清单。
 */
@Composable
internal fun SettingsDestinationRow(
  entry: SettingsDestination,
  onOpen: (Screen) -> Unit,
) {
  val title =
    entry.titleRes?.let { stringResource(it) } ?: entry.titleText.orEmpty()
  val summary =
    entry.summaryRes?.let { stringResource(it) } ?: entry.summaryText
  Preference(
    modifier =
      entry.titleRes?.let { Modifier.settingsAnchor(it) } ?: Modifier.settingsAnchor(title),
    title = {
      Text(
        text = title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    summary = summary?.let { text ->
      {
        Text(
          text = text,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    },
    icon = {
      Icon(
        imageVector = entry.icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    onClick = { onOpen(entry.screen) },
  )
}
