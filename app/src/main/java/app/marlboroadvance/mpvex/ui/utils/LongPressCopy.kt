package app.marlboroadvance.mpvex.ui.utils

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 长按复制一段文字（触觉反馈 + Toast 提示）。
 *
 * ## 为什么要单独抽一个 modifier
 * 需要「长按能复制」的地方（详情页标题 / 原名 / 演职员名字、演员页姓名）
 * 分散在好几个文件里，每个都手写一遍 `combinedClickable` + 剪贴板 + 触觉 + Toast
 * 必然会走样（文案不同、有的忘加触觉）。收口成一处，口径就只在一个地方定。
 *
 * ## 两个容易踩的点
 *  1. **别和宿主已有的 `clickable` 叠着用**：`combinedClickable` 自己就带
 *     tap 处理，叠两层会让其中一层（通常是宿主的点击）失效。
 *     下面几处挂载都选在**本来没有点击行为**的 `Text` 上，所以安全。
 *  2. **`indication = null`**：这些文字多半压在封面图上，默认的水波纹方框很难看，
 *     而且会让人误以为「这块能点」。长按复制的反馈交给触觉 + Toast 就够了。
 *
 * ⚠️ 剪贴板走 **Android 平台 API**（`ClipboardManager`）而不是 Compose 的
 * `LocalClipboardManager`：后者在近几个 Compose 版本里正被 `LocalClipboard` 取代、
 * 处于弃用状态，而平台 API 从 API 11 起就没变过。这里要的是「粘得住」，
 * 不需要 Compose 那层抽象。
 *
 * @param text 要复制的内容；**null / 全空白时完全不挂手势**（不留一个长按了没反应的死区）
 * @param label 提示文案里的名字，例如「片名」→「已复制片名」
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressToCopy(text: String?, label: String = "内容"): Modifier {
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val interaction = remember { MutableInteractionSource() }
  val value = text?.takeIf { it.isNotBlank() }
  return if (value == null) {
    this
  } else {
    this.combinedClickable(
      interactionSource = interaction,
      indication = null,
      onLongClick = {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        runCatching {
          clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        }.onSuccess {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
        }.onFailure {
          Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
        }
      },
      // combinedClickable 的 onClick 是必填项。这些文字本来就没有点击行为，
      // 给个空实现即可 —— 它顺带把这一小块的点击「吃掉」，不会往上冒泡惊动父层。
      onClick = {},
    )
  }
}
