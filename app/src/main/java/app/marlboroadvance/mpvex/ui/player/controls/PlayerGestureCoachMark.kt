package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.delay

/**
 * 引导层出现后的输入静默时长。
 *
 * 用户等加载时经常无意识地连点屏幕，而引导是 `delay()` 后突然浮起的全屏遮罩 ——
 * 没有这段静默，第一下点击就会把它点掉（且旧实现会当场记成「已看过」）。
 * 450ms 足够画面完整显形，又短到不影响正常阅读。
 */
private const val COACH_MARK_INPUT_GUARD_MS = 450L

/**
 * 播放器首次进入时的**一次性手势引导**（coach mark）。
 *
 * ## 为什么是「遮罩 + 热区」而不是一张指示图
 *
 * 播放器的三个隐藏手势作用在**屏幕区域**上（左半屏 / 右半屏 / 整块画面），
 * 不对应任何具体控件，所以没法像新手引导那样「挖个洞指到一个按钮」。
 * 用静态指示图还有一个硬伤：横竖屏两种版式、不同屏宽下点位全不同，图注定对不上。
 *
 * 因此这里画的是**三个手势热区**：半透明遮罩压暗画面，中间一条横向切成三格，
 * 每格给一层极淡的白色底 + 图标 + 两行文案，直接对应「这个区域双击会做什么」。
 * 全程 Compose 绘制，没有位图资源，深浅色/主题色/字号都跟着运行环境走。
 *
 * ## 交互
 *
 * 点屏幕任意位置或点「知道了」都关闭；关闭后由调用方把偏好置 true，不会再看第二次。
 * 遮罩本身吃掉全部触摸 —— 引导期间不应该有任何手势落到播放器上
 * （否则用户在看引导时手一滑就把视频拖走了，观感更差）。
 */
@Composable
fun PlayerGestureCoachMark(
  seekBackwardSeconds: Int,
  seekForwardSeconds: Int,
  /** 长按倍速的目标倍率；<= 0 表示该手势被用户关掉了，不显示这一格 */
  holdSpeed: Float,
  /** 是否显示「上下滑调亮度 / 音量」的补充说明 */
  showVerticalSwipeHint: Boolean,
  /**
   * 关闭回调，参数 `acknowledged` 区分**两种性质完全不同**的关闭：
   * - `true`：用户点了「知道了」⇒ 真的看过了，调用方应记账，此后不再出现；
   * - `false`：用户点了画面空白处离开 ⇒ 只是这一下不想看，调用方**不要记账**，
   *   下次进入播放器仍应出现。
   *
   * 之所以要分：引导是播放器里突然浮起的一层全屏遮罩，用户此时很可能正无意识地
   * 连点屏幕等加载。若把这种「点掉」当成已看过，一次手滑就永久失去引导。
   */
  onDismiss: (acknowledged: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrim = Color.Black.copy(alpha = 0.74f)
  val zoneFill = Color.White.copy(alpha = 0.07f)

  // ── 输入闸门 ──
  // 引导是 `delay()` 之后**突然出现**的。出现前用户往往正在狂点屏幕（等加载），
  // 第一下就会落在刚浮起的遮罩上。这里前 450ms 一律不响应点击，
  // 让画面先完整显形，再接受任何关闭操作。
  var inputReady by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(COACH_MARK_INPUT_GUARD_MS)
    inputReady = true
  }
  val requestDismiss: (Boolean) -> Unit = { acknowledged ->
    if (inputReady) onDismiss(acknowledged)
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(scrim)
        // 引导层吞掉所有触摸：拖拽、双击都不该穿透到播放器。
        // 点空白 = 跳过本次（acknowledged = false），**不是**永久关闭。
        .pointerInput(Unit) {
          detectTapGestures(onTap = { requestDismiss(false) })
        },
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = MaterialTheme.spacing.large),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))

      Text(
        text = "播放手势",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
      )

      Spacer(Modifier.height(MaterialTheme.spacing.medium))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      ) {
        GestureZone(
          icon = Icons.Default.FastRewind,
          title = "双击",
          subtitle = "快退 $seekBackwardSeconds 秒",
          fill = zoneFill,
          modifier = Modifier.weight(1f),
        )

        if (holdSpeed > 0f) {
          GestureZone(
            icon = Icons.Default.Speed,
            title = "长按",
            subtitle = formatSpeed(holdSpeed) + " 倍速",
            fill = zoneFill,
            modifier = Modifier.weight(1f),
          )
        }

        GestureZone(
          icon = Icons.Default.FastForward,
          title = "双击",
          subtitle = "快进 $seekForwardSeconds 秒",
          fill = zoneFill,
          modifier = Modifier.weight(1f),
        )
      }

      if (showVerticalSwipeHint) {
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
          Icon(
            imageVector = Icons.Default.SwipeVertical,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "上下滑动：左半屏调亮度，右半屏调音量",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
          )
        }
      }

      Spacer(Modifier.weight(1f))

      Button(
        // 闸门未开时置灰不可点，避免「刚出现就被连点命中」
        enabled = inputReady,
        onClick = { requestDismiss(true) },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.92f),
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.4f),
            disabledContentColor = Color.Black.copy(alpha = 0.5f),
          ),
      ) {
        Text(text = "知道了", style = MaterialTheme.typography.labelLarge)
      }

      // 必须写明「点空白只是跳过」——否则用户会以为点一下就永久关掉了，
      // 而实际下次还会出现，反而觉得这功能不听话。
      Spacer(Modifier.height(MaterialTheme.spacing.small))
      Text(
        text = "点空白处可跳过本次，下次进入仍会提示",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.5f),
        textAlign = TextAlign.Center,
      )

      Spacer(Modifier.height(MaterialTheme.spacing.larger))
    }
  }
}

/**
 * 单个手势热区。
 *
 * 用「半透明白底 + 圆角」而不是描边虚线：一个是虚线在 Compose 里要手绘 Canvas、
 * 横竖屏宽度一变就得重算，另一个是低透明度的面在亮画面上比线更容易看出「这一块是区域」。
 */
@Composable
private fun GestureZone(
  icon: ImageVector,
  title: String,
  subtitle: String,
  fill: Color,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .height(132.dp)
        .background(fill, RoundedCornerShape(20.dp))
        .padding(horizontal = 8.dp, vertical = MaterialTheme.spacing.medium),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(28.dp),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.small))
    Text(
      text = title,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = Color.White,
      textAlign = TextAlign.Center,
    )
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodySmall,
      color = Color.White.copy(alpha = 0.72f),
      textAlign = TextAlign.Center,
    )
  }
}

/** 2.0 → "2×"，1.5 → "1.5×"；去掉无意义的小数位。 */
private fun formatSpeed(speed: Float): String =
  if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

/**
 * 淡入淡出外壳。
 *
 * 单独抽出来是因为引导层挂在控制条的 `ConstraintLayout` 之外、
 * 由 [PlayerControls] 用 `AnimatedVisibility` 包住；进场 220ms 足够
 * 「从画面里浮出来」，太慢反而挡住用户看片。
 */
@Composable
fun PlayerGestureCoachMarkHost(
  visible: Boolean,
  seekBackwardSeconds: Int,
  seekForwardSeconds: Int,
  holdSpeed: Float,
  showVerticalSwipeHint: Boolean,
  onDismiss: (acknowledged: Boolean) -> Unit,
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(durationMillis = 220)),
    exit = fadeOut(tween(durationMillis = 180)),
  ) {
    PlayerGestureCoachMark(
      seekBackwardSeconds = seekBackwardSeconds,
      seekForwardSeconds = seekForwardSeconds,
      holdSpeed = holdSpeed,
      showVerticalSwipeHint = showVerticalSwipeHint,
      onDismiss = onDismiss,
    )
  }
}
