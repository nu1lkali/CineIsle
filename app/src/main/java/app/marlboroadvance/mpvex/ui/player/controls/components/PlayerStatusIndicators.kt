package app.marlboroadvance.mpvex.ui.player.controls.components

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * **电量数字压在电池轮廓里**（而不是「图标 + 旁边写数字」）：图标和数字并排时，
 * 数字很容易被读成进度 / 剩余时间；套进电池轮廓里，语义自明，横向还能省下十几 dp。
 *
 * 做法是 `Box` 里叠一层 `Canvas`（外壳 + 正极凸起 + 电量填充）和一个居中的 `Text`。
 * 不用 `Icons.Default.BatteryFull`：那个图标的轮廓里没有能放数字的位置。
 * 数字只敢用 9sp（再大就顶到轮廓边），所以电池从原来的 22×12dp 放大到 28×15dp ——
 * `100` 这种三位数也留得出左右边距，边框与数字之间不会糊在一起。
 */
@Composable
fun PlayerStatusIndicators(
  showClock: Boolean,
  showBattery: Boolean,
  showNetSpeed: Boolean,
  netSpeedBytesPerSec: Long?,
  modifier: Modifier = Modifier,
) {
  val controlColor = MaterialTheme.colorScheme.onSurface

  // 时钟每分钟跳一次就够了；用「对齐到下一分钟」而不是每秒，
  // 否则控制条显示期间每秒重组一次，白烧电（红线 54 的性能纪律）。
  val clockText by rememberMinuteTicker(showClock)

  val batteryPercent by rememberBatteryPercent(showBattery)

  // null 已经蕴含「不该显示」（本地文件 / 非 mpv 内核），这里再与开关取与：
  // 三个都关时整行不创建，别留一条空 Row 参与测量。
  val speed = netSpeedBytesPerSec.takeIf { showNetSpeed }
  if (!showClock && !showBattery && speed == null) return

  // ⚠️ 刻意**不用** `Arrangement.spacedBy` 等距排列。
  //
  // 三项是两类东西：时钟 / 电量是**设备状态**，网速是**这条流的运行状态**。
  // 等距摆成一排会读作「三个同级读数」，一行三个单元、又长又碎；
  // 分组之后段内 6dp、段间 12dp，眼睛看到的是「时间电量 │ 网速」两块，
  // 总宽也比等距略窄（少 4dp），且开关增减时不会把整条重新等分。
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (showClock) {
      Text(
        text = clockText,
        color = controlColor.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        // 电量不在时不留尾巴：padding 挂在自己的尾部，由「后面还有没有别的」决定
        modifier = if (showBattery && batteryPercent >= 0 || speed != null) {
          Modifier.padding(end = 6.dp)
        } else {
          Modifier
        },
      )
    }
    if (showBattery && batteryPercent >= 0) {
      BatteryWithPercent(
        percent = batteryPercent,
        tint = controlColor.copy(alpha = 0.85f),
        modifier = if (speed != null) Modifier.padding(end = 12.dp) else Modifier,
      )
    }
    // 网速放最后：顺序固定为 时钟 / 电量 / 网速，不随开关变化重排 —— 位置一动就会显得跳。
    if (speed != null) {
      NetworkSpeedLabel(speedBytesPerSec = speed)
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
 * 电池轮廓 + 内部电量填充 + 压在轮廓正中的百分比数字。
 *
 * 尺寸 28×15dp（按真实电池比例放大，为了容得下数字），数字 9sp。
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

  // 读屏念带 % 的完整文案；视觉上只放数字 —— 图标本身已经表达了"这是百分比"，
  // 轮廓里再挤一个 % 只会把数字压得更小。
  val label = stringResource(R.string.player_battery_percent, percent)

  val bodyWidth = 28.dp
  val bodyHeight = 15.dp
  val capWidth = 2.dp

  Box(
    modifier =
      modifier
        .size(bodyWidth, bodyHeight)
        // 整块对读屏只报一句话：不清掉子节点语义的话，会单独念出一个没有单位的 "88"
        .clearAndSetSemantics { contentDescription = label },
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val stroke = 1.2.dp.toPx()
      val cap = capWidth.toPx()
      val bodyW = size.width - cap
      val bodyH = size.height

      // ── ① 电量填充 ────────────────────────────────────────────────
      //
      // ⚠️ 顺序是「先填充、后描边」：填充的边**故意伸到描边下面**（pad 只有描边的 0.6 倍），
      // 由后画的描边把它盖住。早前把填充内缩到 2.4dp（比描边还宽），外壳内圈就多出
      // 一圈 1.2dp 的空隙 —— 满电时右边也还空着一条，看着就是「有缝隙」。
      // 现在填充与轮廓之间是**共用一条边**，不存在缝；伸进去的那一点被描边盖住，
      // 也不会因为抗锯齿在交界处闪出一条亮线。
      val fillPad = stroke * 0.6f
      val fillW = (bodyW - fillPad * 2) * (percent.coerceIn(0, 100) / 100f)
      if (fillW > 0f) {
        drawRoundRect(
          color = color.copy(alpha = 0.35f),
          topLeft = Offset(fillPad, fillPad),
          size = Size(fillW, bodyH - fillPad * 2),
          // 圆角取 1.5dp：正好贴着外壳描边的内圈圆角（3dp − 1.2dp），
          // 再大就会在四个圆角处悄悄留出一点点空隙 —— 又是「缝隙」。
          cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
        )
      }

      // ── ② 正极小凸起 ──────────────────────────────────────────────
      // 起点从「电池身右沿」再往左压半个描边宽：与外壳**共边**，否则抗锯齿会在
      // 正极和电池身之间留一条发白的细线（同样的「缝隙」问题）。
      drawRect(
        color = color.copy(alpha = 0.9f),
        topLeft = Offset(bodyW - stroke / 2, bodyH / 2 - capWidth.toPx() * 1.5f),
        size = Size(cap, capWidth.toPx() * 3f),
      )

      // ── ③ 外壳（最后画，压住上面两块的交界）──────────────────────
      drawRoundRect(
        color = color.copy(alpha = 0.9f),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(bodyW - stroke, bodyH - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
        style = Stroke(width = stroke),
      )
    }

    Text(
      text = percent.toString(),
      // `padding(end = capWidth)` 把文字整体左移半个正极宽度：
      // 居中是相对整个 28dp 算的，而视觉中心是「电池身」（26dp）的中心。
      modifier = Modifier.align(Alignment.Center).padding(end = capWidth),
      color = color,
      style =
        MaterialTheme.typography.labelSmall.copy(
          fontSize = 9.sp,
          // 行高跟字号一致 + 关掉首末行留白，文字才会真的落在轮廓正中
          lineHeight = 9.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.sp,
          platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
      maxLines = 1,
    )
  }
}
