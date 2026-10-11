package app.marlboroadvance.mpvex.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import org.koin.compose.koinInject
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState

/**
 * 全局「正在加载」指示器 —— **「外观 → 加载动画」设置的消费入口**。
 *
 * ## 为什么要有这个组件
 *
 * 之前这个设置项是**死设置**：偏好写得进去（外观定制页点一下就存了），
 * 但全项目**没有任何地方读它** —— 二十多处加载态各自直接用
 * `CircularProgressIndicator` / `LoadingIndicator`。
 * 用户表现为「点了还是原来的动画」，但选中框会动 ⇒ 典型「改了没反应」。
 *
 * ⇒ 所以修法不是逐个去改二十几个调用点（漏一个就还是没反应），而是
 * **造这一个统一入口**，让所有显著的加载态都走它。
 *
 * ## 尺寸约定
 *
 * [size] 只在调用方**没给** [modifier] 时生效 —— 调用方已经写了
 * `Modifier.size(96.dp)`（播放器缓冲那个）时不该被默认值二次约束。
 */
@Composable
fun AppLoadingIndicator(modifier: Modifier = Modifier, size: Dp = 40.dp) {
  val anim = rememberLoadingAnimation()
  val sized = if (modifier == Modifier) Modifier.size(size) else modifier

  if (!anim.hasLottie) {
    CircularProgressIndicator(modifier = sized)
    return
  }

  // ⚠️ 第二个参数是 `key: String?`（不是 Compose 那个 `key {}` 函数！）。
  //   composition 按它缓存 —— 换档不换 key 就会「改了设置还在放上一档」。
  val composition by
    rememberLottieComposition(
      LottieCompositionSpec.RawRes(anim.resId),
      "loading_${anim.resId}",
    )
  LottieAnimation(
    composition = composition,
    // ⚠️ 必须无限循环，否则一次性动画播完就静止，看起来像卡住。
    iterations = LottieConstants.IterateForever,
    modifier = sized,
  )
}

/**
 * 取当前选中的加载动画（随设置变化重组）。
 *
 * ⚠️ 必须用 [Preference.collectAsState]（`preferences.preference` 那个**无参**重载），
 * 不能用 Compose 自带的 `StateFlow.collectAsState(initial)` ——
 * 那样读一次就不变了，用户改设置后这里不会更新。
 */
@Composable
fun rememberLoadingAnimation(): LoadingAnimation {
  val prefs = koinInject<AppearancePreferences>()
  val key by prefs.loadingAnimation.collectAsState()
  return LoadingAnimation.fromKey(key)
}