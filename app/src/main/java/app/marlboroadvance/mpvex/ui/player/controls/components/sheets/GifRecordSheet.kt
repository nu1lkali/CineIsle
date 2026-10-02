package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.GifRecorder
import app.marlboroadvance.mpvex.ui.theme.spacing

/**
 * GIF 录制面板：选清晰度 + 时长 → 开录 → 显示进度。
 *
 * 录制期间**不允许关闭面板**（[PlayerSheet] 的 `dismissEnabled = false`）：中途关掉会让
 * 用户以为录完了、却既没有文件也没有提示。想中途退出只能走「取消录制」—— 它会真的停掉
 * 采集/编码并清掉临时帧，面板随即恢复可关（返回键在录制中同样等于取消）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GifRecordSheet(
  progress: Float?,
  /** 当前清晰度 = 输出宽度（px）；由调用方从偏好读写，录制面板只负责展示与切换。 */
  qualityWidth: Int,
  onQualityChange: (Int) -> Unit,
  onStart: (Int) -> Unit,
  /** 中止录制。与「关掉面板」是两回事：关面板只是看不见，录制还在后台跑。 */
  onCancel: () -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var durationSec by remember { mutableIntStateOf(5) }
  val recording = progress != null

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    // 录制中：点空白 / 下拉 / 返回都不会把面板收走（会被弹回原位），
    // 否则那层全屏遮罩会留在原地吃掉所有点击，播放器 UI 看上去整个失灵。
    dismissEnabled = !recording,
  ) {
    // 录制中的返回键 = 取消录制。放在内容里（比 PlayerSheet 自己的 BackHandler 更深），
    // 于是返回键不会去关面板，而是停下录制，再按一次才是正常关闭。
    BackHandler(enabled = recording, onBack = onCancel)
    Column(
      modifier =
        modifier
          .fillMaxWidth()
          .padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      Text(
        text = stringResource(R.string.player_gif_title),
        style = MaterialTheme.typography.headlineSmall,
      )
      Text(
        text = stringResource(R.string.player_gif_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // 清晰度：宽度直接决定「糊不糊」。越宽越清楚、文件也越大。
      // 档位是 320 / 480 / 640 / 960 / 1280，窄屏一行放不下 → 用 FlowRow 自动换行。
      Text(
        text = stringResource(R.string.player_gif_quality),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
      ) {
        GifRecorder.WIDTH_OPTIONS.forEach { width ->
          FilterChip(
            selected = qualityWidth == width,
            enabled = !recording,
            onClick = { onQualityChange(width) },
            label = { Text(stringResource(R.string.player_gif_quality_px, width)) },
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller)) {
        GifRecorder.DURATIONS.forEach { seconds ->
          FilterChip(
            selected = durationSec == seconds,
            enabled = !recording,
            onClick = { durationSec = seconds },
            label = { Text(stringResource(R.string.player_gif_seconds, seconds)) },
          )
        }
      }

      if (recording) {
        LinearProgressIndicator(
          progress = { progress ?: 0f },
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = stringResource(R.string.player_gif_recording),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
        )
        TextButton(onClick = onCancel) {
          Text(text = stringResource(R.string.player_gif_cancel), maxLines = 1)
        }
      } else {
        Button(
          onClick = { onStart(durationSec) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(text = stringResource(R.string.player_gif_start))
        }
      }
    }
  }
}
