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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
  val durationMs = state.durationMs
  val hasDuration = durationMs > 0

  // 拖动中的本地比例。拖动期间**不**读设备上报 —— 轮询 1.5 秒一次，
  // 跟着它走会出现「手指还在拖，滑块被上一拍的位置拽回去」，根本拖不动。
  var dragFraction by remember { mutableStateOf<Float?>(null) }
  // 松手后到设备真正追上来之间，界面继续显示我们下发的目标值。
  // 不顶住的话会「松手 → 弹回原处 → 再跳到目标」连闪两下 —— 而且那两下都发生在
  // 用户盯着看「到底定到哪一秒」的时候，最容易让人觉得没定准。
  var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

  // 设备回报的位置离目标足够近 → 认为已追上，放开顶住
  LaunchedEffect(state.positionMs, pendingSeekMs) {
    val target = pendingSeekMs ?: return@LaunchedEffect
    if (kotlin.math.abs(state.positionMs - target) <= SEEK_SETTLE_TOLERANCE_MS) {
      pendingSeekMs = null
    }
  }
  // 兜底：设备可能压根不回报新位置，顶太久就成假状态了
  LaunchedEffect(pendingSeekMs) {
    if (pendingSeekMs == null) return@LaunchedEffect
    kotlinx.coroutines.delay(SEEK_SETTLE_TIMEOUT_MS)
    pendingSeekMs = null
  }

  // 界面上真正显示的时间：拖动值 > 待落定目标 > 设备上报值
  val drag = dragFraction
  val shownMs = if (drag != null && hasDuration) {
    (drag * durationMs).toLong()
  } else {
    pendingSeekMs ?: state.positionMs
  }

  /** 拖动 / 微调 / 点击统一走这里下发：一律落到整秒 */
  fun seekTo(rawMs: Long) {
    if (!hasDuration) return
    // 落到整秒：设备侧本来也只认到秒，亚秒的抖动只会让「预览显示的时间」和
    // 「实际落点」差个零头，用户量一下就觉得对不准。
    val target = (rawMs / 1000L * 1000L).coerceIn(0L, durationMs)
    dragFraction = null
    pendingSeekMs = target
    manager.controlSeek(target)
  }

  Text("正在投屏到「${state.device.name}」", style = MaterialTheme.typography.bodyLarge)
  Spacer(Modifier.height(4.dp))

  // 时间显示：拖动时前面挂「定位到」，一眼能分清这是预览值而不是播放位置
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = if (drag != null) "定位到 ${formatMs(shownMs)}" else formatMs(shownMs),
      style = MaterialTheme.typography.titleMedium,
      color = if (drag != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
    )
    if (hasDuration) {
      Text(
        text = " / ${formatMs(durationMs)} · ${state.playbackState.name}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  Spacer(Modifier.height(8.dp))

  Slider(
    value = if (hasDuration) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
    onValueChange = { dragFraction = it },
    onValueChangeFinished = {
      val f = dragFraction
      if (f != null && hasDuration) seekTo((f * durationMs).toLong()) else dragFraction = null
    },
    // 直播 / 时长未上报时不给拖：没有总时长，比例算不出来，拖了也只能瞎跳
    enabled = hasDuration,
  )

  // ── 微调 ──
  // 整条进度条摊在手机宽度上，一部 2 小时的片子 1 像素就是十几秒，
  // 光靠拖永远对不准。补四个步进键，每次 1 秒 / 10 秒，落点精确到秒。
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    FINE_SEEK_STEPS.forEach { (deltaMs, label) ->
      TextButton(
        onClick = { seekTo(shownMs + deltaMs) },
        enabled = hasDuration,
        modifier = Modifier.weight(1f),
      ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
  Spacer(Modifier.height(4.dp))

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

/** 微调步进：负值往前、正值往后，单位毫秒 */
private val FINE_SEEK_STEPS = listOf(
  -10_000L to "-10秒",
  -1_000L to "-1秒",
  1_000L to "+1秒",
  10_000L to "+10秒",
)

/** 设备回报的位置离下发目标多近就算「已追上」，不用再顶住显示 */
private const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/** 顶住目标值的兜底时长：设备可能压根不回报新位置 */
private const val SEEK_SETTLE_TIMEOUT_MS = 6_000L

private fun formatMs(ms: Long): String {
  val totalSec = (ms / 1000).toInt()
  val h = totalSec / 3600
  val m = (totalSec % 3600) / 60
  val s = totalSec % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
