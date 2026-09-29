package app.marlboroadvance.mpvex.dlna

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinnho.upnpcast.DLNACast
import org.koin.compose.koinInject

/**
 * 投屏面板（通用）：详情页与播放器共用。
 * 打开时若处于 Idle 会自动开始设备发现；面板关闭时由 [DlnaCastManager.onDismissed] 决定保留还是复位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlnaSheet(onDismissRequest: () -> Unit) {
  val manager = koinInject<DlnaCastManager>()
  val uiState by manager.uiState.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  LaunchedEffect(Unit) {
    if (manager.uiState.value is DlnaCastManager.UiState.Idle) {
      manager.startDiscovery()
    }
  }

  ModalBottomSheet(
    onDismissRequest = {
      manager.onDismissed()
      onDismissRequest()
    },
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("DLNA 投屏", style = MaterialTheme.typography.titleMedium)
      }
      Spacer(Modifier.height(12.dp))

      when (val state = uiState) {
        is DlnaCastManager.UiState.Idle -> {}

        is DlnaCastManager.UiState.Discovering -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在搜索局域网内的投屏设备…", style = MaterialTheme.typography.bodyMedium)
          }
        }

        is DlnaCastManager.UiState.Devices -> {
          if (state.devices.isEmpty()) {
            Text("未发现设备", style = MaterialTheme.typography.bodyMedium)
          } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
              items(state.devices) { dev ->
                DeviceRow(dev.name, dev.address, dev.isTV) { manager.connectAndCast(dev) }
              }
            }
          }
        }

        is DlnaCastManager.UiState.Connecting -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在连接「${state.device.name}」…", style = MaterialTheme.typography.bodyMedium)
          }
        }

        is DlnaCastManager.UiState.Casting -> {
          CastingControls(state, manager)
        }

        is DlnaCastManager.UiState.Error -> {
          Text(state.message, style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(8.dp))
          TextButton(onClick = { manager.startDiscovery() }) { Text("重新搜索") }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun DeviceRow(name: String, address: String, isTv: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${if (isTv) "电视" else "设备"} · $address",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(Icons.Outlined.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun CastingControls(
  state: DlnaCastManager.UiState.Casting,
  manager: DlnaCastManager,
) {
  val playing = state.playbackState == DLNACast.PlaybackState.PLAYING
  Text("正在投屏到「${state.device.name}」", style = MaterialTheme.typography.bodyLarge)
  Spacer(Modifier.height(4.dp))
  Text(
    "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)} · ${state.playbackState.name}",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(10.dp))

  val dur = if (state.durationMs > 0) state.durationMs else 1L
  val seekTarget = remember { mutableStateOf(0f) }
  Slider(
    value = (state.positionMs.toFloat() / dur).coerceIn(0f, 1f),
    onValueChange = { seekTarget.value = it },
    onValueChangeFinished = { manager.controlSeek((seekTarget.value * dur).toLong()) },
  )
  Spacer(Modifier.height(8.dp))

  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = { if (playing) manager.controlPause() else manager.controlPlay() },
    ) {
      Icon(
        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = null,
      )
    }
    IconButton(onClick = { manager.controlStop() }) {
      Icon(Icons.Filled.Stop, contentDescription = null)
    }
    TextButton(onClick = { manager.disconnect() }) { Text("断开连接") }
  }

  if (state.volume >= 0) {
    Spacer(Modifier.height(4.dp))
    Text("音量 ${state.volume}%", style = MaterialTheme.typography.bodySmall)
    Slider(
      value = state.volume / 100f,
      onValueChange = { manager.controlVolume((it * 100).toInt()) },
    )
  }
}

private fun formatMs(ms: Long): String {
  val totalSec = (ms / 1000).toInt()
  val h = totalSec / 3600
  val m = (totalSec % 3600) / 60
  val s = totalSec % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
