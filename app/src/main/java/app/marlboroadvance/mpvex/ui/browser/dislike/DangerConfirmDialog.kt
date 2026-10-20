package app.marlboroadvance.mpvex.ui.browser.dislike

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * 「高危操作」确认框 —— **按压保持式确认（press-and-hold confirmation）**。
 *
 * 「删除影片」是**物理删除服务器上的源文件**，不可逆。按压保持式确认把「一次性点击」
 * 换成「持续按压直到进度条满格」，手指必须停留在按钮上并维持时长阈值才真正发起删除；
 * 任意时刻松手即中止并把进度平滑回退到 0%。单条与批量共用同一组件，保证措辞与交互一致。
 *
 * @param title 标题，一句话说清要发生什么（如「删除这 3 个视频文件？」）。
 * @param warning 警告正文。**必须写清"这是物理删除、不可恢复、影响服务器"**。
 * @param itemLines 要删除的条目名列表（单条就传一条），让用户能核对"到底删哪几个"。
 * @param onConfirm 进度满格（按压达到时长阈值）后触发。
 */
@Composable
fun DangerConfirmDialog(
  title: String,
  warning: String,
  itemLines: List<String>,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  // 用 Animatable 管理进度：按压时 0→1 推进（线性），松手时 1→0 平滑回退。
  // remember（而非 rememberSaveable）：一次性手势态，旋屏重建不应"记住半截进度"。
  val progress = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  val currentOnConfirm by rememberUpdatedState(onConfirm)
  val haptics = LocalHapticFeedback.current

  // 水波相位：无限循环，驱动水面横向流动，让"水"有流动感。
  val infiniteTransition = rememberInfiniteTransition(label = "water")
  val wavePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "wavePhase",
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = warning,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )

        if (itemLines.isNotEmpty()) {
          Text(
            text = "将删除以下内容：",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
          )
          itemLines.take(MAX_LISTED).forEach { line ->
            Text(
              text = "· $line",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (itemLines.size > MAX_LISTED) {
            Text(
              text = "…… 等共 ${itemLines.size} 个",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // 简洁的交互说明
        Text(
          text = "按住下方红色按钮 ${HOLD_TO_DELETE_MS / 1000} 秒才会删除，中途松手即取消。" +
            "此操作物理删除服务器端文件，不可恢复。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )

        // 红色删除按钮：按压保持的触发区，进度条以"水位 + 波浪"的形式内嵌在按钮里。
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(48.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.error)
              .pointerInput(Unit) {
                detectTapGestures(
              onPress = {
                // 长按开始：给一次"长按"触觉反馈，确认按压已被识别（与项目其余长按交互一致）。
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // 按压推进协程：从 0 线性推进到 1；正常跑满才触发 onConfirm。
                    // 用外部 scope 启动，使其生命周期独立于 onPress —— 否则松手后 onPress
                    // 返回，内部协程会被一起取消，推进尚未完成就中断。
                    val holdJob =
                      scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(
                          targetValue = 1f,
                          animationSpec =
                            tween(HOLD_TO_DELETE_MS.toInt(), easing = LinearEasing),
                        )
                        // 只有正常跑满（value == 1f）才确认；被取消时不会走到这里。
                        if (progress.value >= 1f) {
                          currentOnConfirm()
                        }
                      }

                    try {
                      // 挂起等待用户松手。
                      tryAwaitRelease()
                    } finally {
                      // 松手（或手势被取消）：停止推进，并"滚回"0 —— 用 350ms 动画平滑回退，
                      // 而不是瞬间归零；若用户再次按下，snapTo(0f) 会打断这段回退动画。
                      holdJob.cancel()
                      scope.launch {
                        progress.animateTo(0f, tween(RESET_ANIM_MS))
                      }
                    }
                  },
                )
              },
          contentAlignment = Alignment.Center,
        ) {
          // 水位 + 波浪层（在按钮内部，位于文字下方）。两层不同波长/相位的正弦波叠加，
          // 相位由 wavePhase 驱动持续横移，形成流动感。水位高度 = progress。
          Canvas(modifier = Modifier.fillMaxSize()) {
            val p = progress.value.coerceIn(0f, 1f)
            if (p <= 0f) return@Canvas
            val w = size.width
            val h = size.height
            val amp = 3.dp.toPx()                 // 波浪振幅
            val surfaceY = h - (h - amp) * p      // 水面基准 y：p=0 在底部，p=1 在顶部（留出振幅）

            // 生成一条"底部闭合"的波浪填充路径。
            fun wavePath(ampScale: Float, phaseOffset: Float, freq: Float): Path {
              val path = Path()
              path.moveTo(0f, h)
              val waveLen = w * freq
              var x = 0f
              while (x <= w) {
                val y =
                  surfaceY +
                    amp * ampScale *
                    sin((x / waveLen) * 2f * PI.toFloat() + wavePhase + phaseOffset)
                path.lineTo(x, y)
                x += 3f
              }
              path.lineTo(w, h)
              path.close()
              return path
            }

            // 后层：低幅、慢波、相位偏移，视觉上像更深的水
            drawPath(
              wavePath(ampScale = 0.85f, phaseOffset = PI.toFloat(), freq = 1f / 1.5f),
              color = Color.White.copy(alpha = 0.20f),
            )
            // 前层：高幅、快波，主体水面
            drawPath(
              wavePath(ampScale = 1f, phaseOffset = 0f, freq = 1f / 2f),
              color = Color.White.copy(alpha = 0.30f),
            )
          }

          Text(
            text =
              if (progress.value > 0f) {
                "保持按住…（${(progress.value * 100).toInt()}%）"
              } else {
                "按住删除"
              },
            style =
              MaterialTheme.typography.labelLarge.copy(
                // 水面上方加一点阴影，保证白字在水位较高时依然可读
                shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 6f),
              ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError,
          )
        }

        Text(
          text = "松手即取消",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    },
    confirmButton = {
      // 删除动作已内置在"按住"按钮里。
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

/** 按压保持达到该时长（毫秒）才解锁删除；中途松手即回退。当前为 3 秒。 */
private const val HOLD_TO_DELETE_MS = 3000L

/** 松手后进度回退到 0 的动画时长（毫秒）。 */
private const val RESET_ANIM_MS = 350

/** 清单里最多逐条列出多少个名字，超出部分收口成「等共 N 个」。 */
private const val MAX_LISTED = 8