package app.marlboroadvance.mpvex.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * @param label 剪贴板条目的标签（系统「复制自 XXX」提示用），只影响剪贴板元信息
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
        // 触觉只在**真的复制成功**时给：失败还震一下等于骗人。
        if (copyTextWithToast(context, label, value)) {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
      },
      // combinedClickable 的 onClick 是必填项。这些文字本来就没有点击行为，
      // 给个空实现即可 —— 它顺带把这一小块的点击「吃掉」，不会往上冒泡惊动父层。
      onClick = {},
    )
  }
}

/**
 * 复制到剪贴板并弹 Toast，供**非 Modifier 场景**（菜单项 / 对话框按钮）复用。
 *
 * Toast 文案带**实际复制的值**，而不是「已复制姓名」这种只报字段名的提示：
 * 用户长按之后要确认的是「粘到手里的是不是我要的那串」，只告诉他「复制了姓名」等于没说。
 *
 * @return 是否复制成功（供调用方决定要不要给触觉 / 其它反馈）
 */
fun copyTextWithToast(context: Context, label: String, text: String): Boolean {
  val value = text.trim()
  if (value.isEmpty()) return false
  val shown = value.toastSnippet()
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  val ok =
    clipboard != null &&
      runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, value)) }.isSuccess
  val tip = if (ok) "已复制：$shown" else "复制失败：$shown"
  Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
  return ok
}

/** Toast 里最多显示这么多个「半角宽度」（全角 / 中日韩字符按 2 记），超了截断加省略号 */
private const val TOAST_MAX_WIDTH = 44

/**
 * 截成适合 Toast 的一行：压掉换行 / 连续空白（Toast 里多行会把横幅撑成一大块），
 * 再按显示宽度截断。原名可能很长、媒体 ID 是 32 位 GUID，硬塞会铺满半个屏幕。
 */
private fun String.toastSnippet(): String {
  val flat = trim().replace(Regex("\\s+"), " ")
  if (flat.displayWidth() <= TOAST_MAX_WIDTH) return flat
  val sb = StringBuilder()
  var width = 0
  for (ch in flat) {
    val w = ch.displayWidth()
    if (width + w > TOAST_MAX_WIDTH) break
    width += w
    sb.append(ch)
  }
  return sb.toString() + "…"
}

private fun String.displayWidth(): Int = sumOf { it.displayWidth() }

/** 东亚全角字形按 2 个半角宽算，其余按 1 —— 只用于判断 Toast 会不会太宽 */
private fun Char.displayWidth(): Int =
  when {
    this.code < 0x1100 -> 1
    this in '\u1100'..'\u115F' ||
      this in '\u2E80'..'\uA4CF' ||
      this in '\uAC00'..'\uD7A3' ||
      this in '\uF900'..'\uFAFF' ||
      this in '\uFE30'..'\uFE6F' ||
      this in '\uFF00'..'\uFF60' ||
      this in '\uFFE0'..'\uFFE6' -> 2
    else -> 1
  }
