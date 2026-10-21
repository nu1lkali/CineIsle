package app.marlboroadvance.mpvex.ui.player.controls.components

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 播放控制条上的**系统时钟 + 电量**指示。
 *
 * 两个开关独立（设置里各一条），都关时调用方整块不创建，所以这里不做空态。
 * 样式刻意做得"轻"：小号字 + 单色，靠位置和留白与周围控件区分，
 * 不加背板也不加描边 —— 控制条本身已经有玻璃底，再加一层会显得脏。
 *
 * **电量图标内嵌数字**（电池轮廓里写百分比）而不是「图标 + 旁边写数字」：
 * 图标和数字分开时，数字会被误读成进度/时间；套进电池轮廓里，语义自明。
 * 画法是 Canvas 描边电池外壳 + 内部填充比例 + 正中叠文字 ——
 * 用现成 `Icons.Default.BatteryFull` 做不到"图标里带数字"。
 */
@Composable
fun PlayerStatusIndicators(
  showClock: Boolean,
  showBattery: Boolean,
  modifier: Modifier = Modifier,
) {
  val controlColor = MaterialTheme.colorScheme.onSurface

  // 时钟每分钟跳一次就够了；用「对齐到下一分钟」而不是每秒，
  // 否则控制条显示期间每秒重组一次，白烧电（红线 54 的性能纪律）。
  val clockText by rememberMinuteTicker(showClock)

  val batteryPercent by rememberBatteryPercent(showBattery)

  if (!showClock && !showBattery) return

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (showClock) {
      Text(
        text = clockText,
        color = controlColor.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
      )
    }
    if (showBattery && batteryPercent >= 0) {
      BatteryWithPercent(
        percent = batteryPercent,
        tint = controlColor.copy(alpha = 0.85f),
      )
    }
  }
}

/**
 * 每分钟返回一次当前时间字符串。
 *
 * 用 [DateFormat.getTimeInstance] + `is24HourFormat`：**跟随系统 12/24 小时制**，
 * 不写死 `HH:mm`（红线 24：别写死会随用户设置变化的格式）。
 */
@Composable
private fun rememberMinuteTicker(enabled: Boolean): State<String> {
  val context = LocalContext.current
  val text = remember { mutableStateOf("") }

  // ⚠️ 用 `enabled` 进key 而不是 `if (!enabled) return`：
  // 后者会让「开关关」分支根本不执行 LaunchedEffect，逻辑虽然对，
  // 但开关切换时 Composable 结构发生变化，读代码的人容易误判（红线 11/12 那类坑）。
  LaunchedEffect(enabled) {
    if (!enabled) return@LaunchedEffect
    fun current(): String {
      // ⚠️ 12/24 小时制要**跟随系统**，但两套 API 各管一半：
      // · 判定「系统是不是 24 小时制」= `android.text.format.DateFormat.is24HourFormat(context)`
      // · 格式化 = `java.text.DateFormat`（它**没有** is24HourFormat 属性）
      // 别把 `is24HourFormat` 写到 java.text.DateFormat 实例上 —— 编译期就报
      // 「Unresolved reference 'is24HourFormat'」。
      val pattern =
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
      return SimpleDateFormat(pattern, Locale.getDefault()).format(Calendar.getInstance().time)
    }
    text.value = current()
    while (true) {
      // 对齐到下一个整分钟再睡，避免"整点前后 1 秒内显示的还是旧值"。
      val now = Calendar.getInstance()
      val msToNextMinute = 60_000L - now[Calendar.SECOND] * 1000L - now[Calendar.MILLISECOND]
      delay(msToNextMinute.coerceAtLeast(1000L))
      text.value = current()
    }
  }
  return text
}

/**
 * 电量百分比（0~100）。关闭或读不到时返回 -1（调用方据此不渲染）。
 *
 * 用 [BatteryManager.getIntProperty]（`BATTERY_PROPERTY_CAPACITY`）而不是注册
 * `ACTION_BATTERY_CHANGED` 广播：控制条只要显示期间每 30 秒轮询一次就够，
 * 注册广播反而要成对注销，漏一次就泄漏。
 */
@Composable
private fun rememberBatteryPercent(enabled: Boolean): State<Int> {
  val context = LocalContext.current
  val percent = remember { mutableIntStateOf(-1) }

  LaunchedEffect(enabled) {
    if (!enabled) return@LaunchedEffect
    fun read(): Int {
      val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
      val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      return if (v in 0..100) v else -1
    }
    percent.intValue = read()
    while (true) {
      delay(30_000L)
      percent.intValue = read()
    }
  }
  return percent
}

/**
 * 电池轮廓 + 内部填充比例 + 轮廓内居中的百分比数字。
 *
 * 尺寸 22×12dp（真实电池比例），数字用 `labelSmall` 压在内部。
 * 填充颜色跟随系统低电量告警：≤15% 转红，用户一眼知道该插电了。
 */
@Composable
private fun BatteryWithPercent(
  percent: Int,
  tint: Color,
  modifier: Modifier = Modifier,
) {
  // 低电量阈值：系统版本间 `BATTERY_PROPERTY_*` 没有公开的阈值读取接口，
  // 固定用行业惯例的 15% —— 只影响一个颜色，不需要精确。
  val color = if (percent <= 15) Color(0xFFE5534B) else tint

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Canvas(modifier = Modifier.size(22.dp, 12.dp)) {
      val stroke = 1.2.dp.toPx()
      val capW = 2.dp.toPx()
      val bodyW = size.width - capW
      val bodyH = size.height

      // 外壳
      drawRoundRect(
        color = color.copy(alpha = 0.9f),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(bodyW - stroke, bodyH - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
        style = Stroke(width = stroke),
      )
      // 正极小凸起
      drawRect(
        color = color.copy(alpha = 0.9f),
        topLeft = Offset(bodyW, bodyH / 2 - capW / 2),
        size = Size(capW, capW),
      )
      // 填充比例（内部留 1.5dp 内边距）
      val innerPad = 2.4.dp.toPx()
      val fillW = (bodyW - innerPad * 2) * (percent.coerceIn(0, 100) / 100f)
      if (fillW > 0f) {
        drawRect(
          color = color,
          topLeft = Offset(innerPad, innerPad),
          size = Size(fillW, bodyH - innerPad * 2),
        )
      }
    }
    // 数字放图标右侧而不是内部：22×12dp 里塞两位数字会糊成一团，
    // 比图标小的字号反而更看不清 —— 这里取「看得清」优先。
    Text(
      text = stringResource(R.string.player_battery_percent, percent),
      color = color,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}
