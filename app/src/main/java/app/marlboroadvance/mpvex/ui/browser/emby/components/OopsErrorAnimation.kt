package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 「Oops」动态指示牌 —— 出错时放在错误信息上方。
 *
 * 造型是一块**插在地里的指示牌**：牌子钉在立柱上，柱子扎进地面（地平线 + 两撮土）。
 * 出错页原来只有一个静态的服务器图标，看着像「还没加载」，不像「出了问题」；
 * 一块立在地上、被风吹得轻轻晃的牌子一眼就是「这里坏了」，比图标更会说话。
 *
 * 动效只有两处，都刻意很轻（不抢错误文案的注意力）：
 *  - **摆动**：牌子和柱子**一起**绕柱子底部（地面处）左右摆 ±4°，2.1 秒一个来回 ——
 *    轴心在底部才像插在土里的牌子，绕顶部转那是吊牌；
 *  - **脉冲**：牌面上的警示图标缓慢缩放 0.92~1.08。
 *
 * 两个动画值都用 `State.value` 在 `graphicsLayer {}` **lambda 内**读（不是 `by`）：
 *    读在绘制期，每帧只失效 draw / layout，**不会每帧重组**整个错误页
 *    （项目红线：骨架屏扫光每帧重组那种写法别再复制）。
 *
 * @param label 牌面上的第一行字（默认 `Oops.....`）。
 *   中文页面里也保留 Oops —— 它是个语气词，翻成「哎呀」反而没有那个味。
 * @param accent 牌面上的第二行字（默认 `Error`），用错误色 + 大一号字号。
 *   **两行是刻意的**：连起来就是牌子上那句「Oops..... Error」，
 *   但挤成一行的话牌子得宽到 150dp+ 才放得下，在空态页里会比下面那条
 *   错误文案还抢戏；两行既放得下、又让 Error 那行单独更醒目。
 * @param caption 牌子**下面**那句友好提示（在摆动部分之外，不跟着晃）。
 *   光给一块牌子，用户还得自己猜「是不是我网络坏了」；一句话先把情绪接住，
 *   再让下面那条具体错误文案去说清楚到底是什么错。
 */
@Composable
fun OopsErrorAnimation(
  modifier: Modifier = Modifier,
  label: String = "Oops.....",
  accent: String = "Error",
  caption: String? = null,
) {
  val transition = rememberInfiniteTransition(label = "oops_sign")
  // ⚠️ 不写成 `by`：下面要在绘制期读 .value（见 KDoc 那段）
  val swing = transition.animateFloat(
    initialValue = -4f,
    targetValue = 4f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2100, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "oops_swing",
  )
  val pulse = transition.animateFloat(
    initialValue = 0.92f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1100, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "oops_pulse",
  )

  val postColor = MaterialTheme.colorScheme.outline
  val boardColor = MaterialTheme.colorScheme.errorContainer
  val boardContent = MaterialTheme.colorScheme.onErrorContainer

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // ── 摆动体：牌子 + 立柱，轴心在**柱子底部**（扎进地面的那一点）──
    Column(
      modifier = Modifier.graphicsLayer {
        rotationZ = swing.value
        transformOrigin = TransformOrigin(0.5f, 1f)
      },
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = boardColor,
        border = BorderStroke(1.5.dp, postColor.copy(alpha = 0.45f)),
        modifier = Modifier.width(104.dp),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
              .size(30.dp)
              .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
              },
          )
          Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = boardContent,
          )
          if (accent.isNotBlank()) {
            Text(
              text = accent,
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // 立柱
      Box(
        modifier = Modifier
          .width(7.dp)
          .height(30.dp)
          .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
          .background(postColor),
      )
    }

    // ── 地面：不跟着晃。地平线 + 两撮土，让牌子看着是「插进去的」──
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(top = 1.dp),
    ) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(postColor.copy(alpha = 0.55f)),
      )
      Box(
        modifier = Modifier
          .width(64.dp)
          .height(4.dp)
          .padding(horizontal = 3.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(postColor),
      )
      Box(
        modifier = Modifier
          .size(4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(postColor.copy(alpha = 0.4f)),
      )
    }

    if (!caption.isNullOrBlank()) {
      Text(
        text = caption,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
      )
    }
  }
}
