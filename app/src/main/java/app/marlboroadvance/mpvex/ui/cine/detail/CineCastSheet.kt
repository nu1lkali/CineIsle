package app.marlboroadvance.mpvex.ui.cine.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.dlna.DlnaCastManager
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.utils.pressDownScale
import com.yinnho.upnpcast.DLNACast
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * Cine 的投屏抽屉（玻璃版）。
 *
 * ## 与 Classic `DlnaSheet` 的关系
 * **状态与动作完全共用** [DlnaCastManager]（同一个 Koin 单例）：设备发现、连接、
 * 播放控制、音量、断开都是同一套调用，差别只在观感 —— 那边是 M3 白底表单，
 * 这边是半透明玻璃面板 + Cine 字阶。
 *
 * ## 为什么不直接在 DlnaSheet 上加个玻璃参数
 * `DlnaSheet` 是详情页与**播放器**共用的（`ui/player/` 目录下一行不改是 Cine 的硬约束），
 * 给它加样式分支等于让老路径也经过新代码。这里独立一份，老的那个字节不动。
 *
 * ## 玻璃靠什么
 * ModalBottomSheet 跑在自己的 Dialog 窗口里，Haze 只能采样同一窗口的绘制内容 ⇒
 * 取不到页面背景。所以这里用「更透的 `glassTint` + 压暗 scrim」做观感，
 * 与长按菜单（[app.marlboroadvance.mpvex.ui.cine.actions.CineActionSheetHost]）同一口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CineCastSheet(
  onDismissRequest: () -> Unit,
) {
  val manager = koinInject<DlnaCastManager>()
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val uiState by manager.uiState.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // 打开即发现：Idle 时用户看到空面板会以为坏了
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
    containerColor = colors.glassTint,
    tonalElevation = 0.dp,
    scrimColor = Color.Black.copy(alpha = if (colors.isLight) 0.34f else 0.52f),
    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.glassBorder),
        )
      }
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = space.screenH)
        .padding(bottom = 24.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Cast,
          contentDescription = null,
          tint = colors.sectionAction,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = stringResource(R.string.cine_cast_title),
          style = type.sectionTitle.copy(fontSize = 19.sp),
          color = colors.onSurface,
          maxLines = 1,
        )
      }
      Spacer(Modifier.height(14.dp))

      when (val state = uiState) {
        is DlnaCastManager.UiState.Idle -> Unit

        is DlnaCastManager.UiState.Discovering -> CastNotice(
          text = stringResource(R.string.cine_cast_searching),
          loading = true,
        )

        is DlnaCastManager.UiState.Devices -> {
          if (state.devices.isEmpty()) {
            CastNotice(text = stringResource(R.string.cine_cast_empty))
            Spacer(Modifier.height(10.dp))
            CastWideButton(
              label = stringResource(R.string.cine_cast_rescan),
              primary = false,
              onClick = { manager.startDiscovery() },
            )
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(state.devices) { device ->
                CastDeviceRow(
                  name = device.name,
                  address = device.address,
                  isTv = device.isTV,
                  onClick = { manager.connectAndCast(device) },
                )
              }
            }
            Spacer(Modifier.height(10.dp))
            CastWideButton(
              label = stringResource(R.string.cine_cast_rescan),
              primary = false,
              onClick = { manager.startDiscovery() },
            )
          }
        }

        is DlnaCastManager.UiState.Connecting -> CastNotice(
          text = stringResource(R.string.cine_cast_connecting, state.device.name),
          loading = true,
        )

        is DlnaCastManager.UiState.Casting -> CastingPanel(state = state, manager = manager)

        is DlnaCastManager.UiState.Error -> {
          CastNotice(text = state.message, isError = true)
          Spacer(Modifier.height(10.dp))
          CastWideButton(
            label = stringResource(R.string.cine_cast_rescan),
            primary = true,
            onClick = { manager.startDiscovery() },
          )
        }
      }
    }
  }
}

/** 状态提示行：转圈 + 一行文案（发现中 / 连接中 / 空态 / 错误共用） */
@Composable
private fun CastNotice(
  text: String,
  loading: Boolean = false,
  isError: Boolean = false,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = colors.progressFill,
      )
    }
    Text(
      text = text,
      style = type.body,
      color = if (isError) MaterialTheme.colorScheme.error else colors.onSurfaceVariant,
      maxLines = 3,
    )
  }
}

/** 设备行：玻璃描边卡片（整行可点，右侧箭头说明「点它就连过去」） */
@Composable
private fun CastDeviceRow(
  name: String,
  address: String,
  isTv: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(14.dp) }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(colors.chipSurface.copy(alpha = 0.55f))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(34.dp)
        .clip(CircleShape)
        .background(colors.chipSurface),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Outlined.Cast,
        contentDescription = null,
        tint = colors.sectionAction,
        modifier = Modifier.size(17.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp),
    ) {
      Text(
        text = name,
        style = type.itemTitle,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = stringResource(
          R.string.cine_cast_device_sub,
          stringResource(if (isTv) R.string.cine_cast_tv else R.string.cine_cast_device),
          address,
        ),
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
      contentDescription = null,
      tint = colors.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
  }
}

/** 正在投屏：设备名 + 进度 / 微调 + 播放控制 + 音量 */
@Composable
private fun CastingPanel(
  state: DlnaCastManager.UiState.Casting,
  manager: DlnaCastManager,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  val playing = state.playbackState == DLNACast.PlaybackState.PLAYING
  val durationMs = state.durationMs
  val hasDuration = durationMs > 0

  // 拖动中的本地比例：拖动期间不读设备上报 —— 轮询 1.5 秒一次，
  // 跟着它走会出现「手指还在拖，滑块被上一拍的位置拽回去」
  var dragFraction by remember { mutableStateOf<Float?>(null) }
  // 松手后到设备真正追上来之间继续显示我们下发的目标值，
  // 否则会「松手 → 弹回原处 → 再跳到目标」连闪两下
  var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

  LaunchedEffect(state.positionMs, pendingSeekMs) {
    val target = pendingSeekMs ?: return@LaunchedEffect
    if (abs(state.positionMs - target) <= SEEK_SETTLE_TOLERANCE_MS) {
      pendingSeekMs = null
    }
  }
  LaunchedEffect(pendingSeekMs) {
    if (pendingSeekMs == null) return@LaunchedEffect
    kotlinx.coroutines.delay(SEEK_SETTLE_TIMEOUT_MS)
    pendingSeekMs = null
  }

  val drag = dragFraction
  val shownMs = if (drag != null && hasDuration) {
    (drag * durationMs).toLong()
  } else {
    pendingSeekMs ?: state.positionMs
  }

  fun seekTo(rawMs: Long) {
    if (!hasDuration) return
    // 落到整秒：设备侧只认到秒，亚秒抖动只会让「预览时间」和「实际落点」差个零头
    val target = (rawMs / 1000L * 1000L).coerceIn(0L, durationMs)
    dragFraction = null
    pendingSeekMs = target
    manager.controlSeek(target)
  }

  Text(
    text = stringResource(R.string.cine_cast_casting_to, state.device.name),
    style = type.itemTitle,
    color = colors.onSurface,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  Spacer(Modifier.height(10.dp))

  Row(verticalAlignment = Alignment.Bottom) {
    Text(
      // 拖动时前面挂「定位到」：一眼能分清这是预览值而不是播放位置
      text = if (drag != null) {
        stringResource(R.string.cine_cast_seek_to, formatMs(shownMs))
      } else {
        formatMs(shownMs)
      },
      style = type.sectionTitle.copy(fontSize = 22.sp),
      color = if (drag != null) colors.sectionAction else colors.onSurface,
      maxLines = 1,
    )
    if (hasDuration) {
      Text(
        text = " / ${formatMs(durationMs)}",
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 3.dp),
      )
    }
  }

  Slider(
    value = if (hasDuration) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
    onValueChange = { dragFraction = it },
    onValueChangeFinished = {
      val f = dragFraction
      if (f != null && hasDuration) seekTo((f * durationMs).toLong()) else dragFraction = null
    },
    // 直播 / 时长未上报时不给拖：没有总时长，比例算不出来
    enabled = hasDuration,
    colors = SliderDefaults.colors(
      thumbColor = colors.progressFill,
      activeTrackColor = colors.progressFill,
      inactiveTrackColor = colors.progressTrack,
    ),
  )

  // 微调：整条进度条摊在手机宽度上，2 小时片子 1 像素就是十几秒，
  // 光靠拖永远对不准，补四个步进键落到整秒
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FINE_SEEK_STEPS.forEach { (deltaMs, label) ->
      val shape = remember { RoundedCornerShape(50) }
      Box(
        modifier = Modifier
          .weight(1f)
          .height(32.dp)
          .clip(shape)
          .background(colors.chipSurface.copy(alpha = if (hasDuration) 0.6f else 0.25f))
          .then(if (hasDuration) Modifier.pressDownScale().clickable { seekTo(shownMs + deltaMs) } else Modifier),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          style = type.cardMeta.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
          color = if (hasDuration) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f),
          maxLines = 1,
        )
      }
    }
  }

  Spacer(Modifier.height(16.dp))

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    CastCircleButton(
      icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      contentDescription = null,
      filled = true,
      onClick = { if (playing) manager.controlPause() else manager.controlPlay() },
    )
    CastCircleButton(
      icon = Icons.Filled.Stop,
      contentDescription = stringResource(R.string.cine_cast_stop),
      filled = false,
      onClick = { manager.controlStop() },
    )
    Spacer(Modifier.weight(1f))
    CastWideButton(
      label = stringResource(R.string.cine_cast_disconnect),
      primary = false,
      onClick = { manager.disconnect() },
      modifier = Modifier.width(132.dp),
    )
  }

  if (state.volume >= 0) {
    Spacer(Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.cine_cast_volume, state.volume),
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      maxLines = 1,
    )
    Slider(
      value = state.volume / 100f,
      onValueChange = { manager.controlVolume((it * 100).toInt()) },
      colors = SliderDefaults.colors(
        thumbColor = colors.progressFill,
        activeTrackColor = colors.progressFill,
        inactiveTrackColor = colors.progressTrack,
      ),
    )
  }
}

/** 圆形按钮：主控（播放/暂停）实心，次控（停止）描边 */
@Composable
private fun CastCircleButton(
  icon: ImageVector,
  contentDescription: String?,
  filled: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors

  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(if (filled) colors.progressFill else colors.chipSurface.copy(alpha = 0.55f))
      .pressDownScale()
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = if (filled) colors.surface else colors.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** 全宽按钮：`primary` 走主题色实心（错误重试），否则玻璃描边 */
@Composable
private fun CastWideButton(
  label: String,
  primary: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(42.dp)
      .clip(shape)
      .background(if (primary) colors.progressFill else colors.chipSurface.copy(alpha = 0.5f))
      .then(
        if (primary) Modifier else Modifier.border(1.dp, colors.chipBorder, shape),
      )
      .pressDownScale()
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = type.button,
      color = if (primary) colors.surface else colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
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
