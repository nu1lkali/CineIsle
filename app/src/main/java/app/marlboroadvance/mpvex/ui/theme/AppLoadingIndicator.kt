package app.marlboroadvance.mpvex.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
 * ## 接入标准（这条是后来补的，踩过坑）
 *
 * 第一版只接了 6 处（媒体库 / 直播 / 发现 / 最近播放 / 视频列表 / 播放器缓冲），
 * 结果用户依然报「没反应」—— 因为**日常真正会看到加载态的那些页面**
 * （Emby 详情页、文件浏览、播放列表详情、网络浏览、视界流）全都没接，
 * 一进去看到的还是裸转圈 ⇒ 设置等于没用。
 *
 * ⇒ 所以判定标准是**看尺寸，不看页面**：
 *  · **≥ 32dp 的独立加载态**（整屏居中、卡片内独立一块）⇒ 接进来；
 *  · **< 32dp 的内联微指示器**（按钮里 20dp、分页尾巴 18~24dp、chip 里 16dp）
 *    ⇒ 保留 `CircularProgressIndicator`。Lottie 是带细节的矢量动画，
 *    压到 20dp 以下只会糊成一团色块，反而比转圈更难看，还白付一次解析开销。
 *
 * @param tint 着色。给 null 时 Lottie 保持原色；转圈分支会回落到主题 primary。
 *   播放器画面上的加载层要传白色（压在画面上，主题色会看不见）。
 * @param size 仅在调用方**没给** [modifier] 时生效 —— 调用方已经写了
 *   `Modifier.size(96.dp)`（播放器缓冲那个）时不该被默认值二次约束。
 */
@Composable
fun AppLoadingIndicator(
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  tint: Color? = null,
) {
  val anim = rememberLoadingAnimation()
  val sized = if (modifier == Modifier) Modifier.size(size) else modifier

  if (!anim.hasLottie) {
    CircularProgressIndicator(
      color = tint ?: MaterialTheme.colorScheme.primary,
      modifier = sized,
    )
    return
  }

  // ⚠️ 第二个参数是 `key: String?`（不是 Compose 那个 `key {}` 函数！）。
  //   composition 按它缓存 —— 换档不换 key 就会「改了设置还在放上一档」。
  val composition by
    rememberLottieComposition(
      LottieCompositionSpec.RawRes(anim.resId),
      "loading_${anim.resId}",
    )

  // ⚠️ lottie-compose 的 `LottieAnimation` **没有 colorFilter 参数**（已 javap 核实 6.7.1：
  //   只有 composition / progress / modifier / iterations / renderMode 等，没有染色口）。
  //   `colorFilter` 存在于 `LottieDrawable` 那层，compose 封装没透出来。
  // ⇒ 想染色只能自己在 Modifier 层做，思路与 `FavoriteHeart.heartGradient()` 相同：
  //   离屏合成后用 **SrcAtop** 把颜色「涂」在动画的不透明像素上（形状来自动画自身的 alpha）。
  //   ⚠️ `CompositingStrategy.Offscreen` 不能省：没有离屏层时 SrcAtop 会把 tint 铺满整个矩形，
  //   表现为「一块色板盖住动画」而不是染色。
  val colored =
    if (tint == null) {
      sized
    } else {
      sized
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
          drawContent()
          drawRect(color = tint, blendMode = BlendMode.SrcAtop)
        }
    }

  LottieAnimation(
    composition = composition,
    // ⚠️ 必须无限循环，否则一次性动画播完就静止，看起来像卡住。
    iterations = LottieConstants.IterateForever,
    modifier = colored,
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
