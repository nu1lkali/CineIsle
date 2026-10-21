package app.marlboroadvance.mpvex.ui.browser.dislike

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 左滑露出操作按钮的列表行。
 *
 * ## 交互
 *
 * · **左滑**：内容左移，右边依次露出两个动作按钮（取消标记 / 删除）。
 * · **滑回**：内容右移回原位，按钮重新盖住。
 * · 点别处 / 点别的行：**只收起当前这一行**（由外部持有展开状态来协调）。
 * · **松手时按位移决定去留**：滑过一半才吸附到"完全展开"，
 *   否则弹回去。用位移阈值而不是速度阈值 —— 幅度小但快速一甩也能展开，
 *   而慢慢拖一点点不会误触。
 *
 * ## 为什么不用 `Modifier.animateFloatAsState` 直接跟手
 *
 * 跟手阶段必须**同步**跟随手指（每帧都要更新），只有松手后的吸附才用动画 ——
 * 否则会有一种"拖不动的黏滞感"。这是 [Animatable] + `snapTo`（跟手）
 * 与 `animateTo`（松手）的分工。
 *
 * ⚠️ 复位必须同时通知外部（[onCollapse]）：本组件只管自己的动画，
 *   管不了"别的行是不是正开着"。父级据回调清空展开项即可。
 *
 * @param revealed 是否处于展开态（由父级统一持有，保证同一时刻只有一行展开）。
 * @param onRevealChange 展开态变化时通知父级。
 */
@Composable
fun SwipeToRevealRow(
  revealed: Boolean,
  onRevealChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  /** 按钮总宽度，需与内部两个按钮宽度之和一致（用于计算吸附阈值）。 */
  actionsWidthPx: Int,
  /**
   * 滑开露出的动作按钮区。
   *
   * ⚠️ 顺序很重要：[actions] 必须在 [content] **之前**。
   * Compose 的尾随 lambda 只会绑定到**最后一个**参数，所以调用方应当
   * 写 `SwipeToRevealRow(...) { actions内容 }` 的同时把动作区用具名参数传掉：
   * ```
   * SwipeToRevealRow(revealed = …, actions = { … }) { /* content */ }
   * ```
   * 若 content 在前，尾随 lambda 会去填 content、而具名的 actions 又填一次，
   * 报出来的是 "Too many arguments" —— 看起来像参数个数不对，真因是顺序。
   */
  actions: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  // 上次的拖动位移。放在 remember 里而不是 offset 里，
  // 是为了让"松手后动画"从当前实际位置出发，而不是从 0 跳过去。
  val offsetX = remember { Animatable(0f) }
  val halfThreshold = remember(actionsWidthPx) { actionsWidthPx / 2f }

  // 展开态变化（父级改了 revealed，比如点了别处要收起）→ 动画过去
  androidx.compose.runtime.LaunchedEffect(revealed) {
    val target = if (revealed) -actionsWidthPx.toFloat() else 0f
    if (offsetX.value != target) {
      offsetX.animateTo(target)
    }
  }

  // ⚠️ 行高必须用 **IntrinsicSize.Min** 定死，不能只写 `heightIn(min = …)`。
  //  这是「动作按钮总是比条目卡片矮」反复修不好的原因：
  //  `heightIn(min = 88.dp)` 只给了**最小高度**，而 `maxHeight` 仍然是 ±∞。
  //  在这种约束下 `fillMaxHeight()` **不生效**（它的语义是"填满 maxHeight"，
  //  而 maxHeight 无穷 ⇒ 退化成 wrap-content）——
  //  所以动作区、按钮、卡片外框全都白写了 fillMaxHeight，各自只占自己的内容高度：
  //    · 按钮 ≈ 图标 28 + 间距 4 + 文字 ≈ 20 → 约 52dp
  //    · 卡片内容 ≈ 缩略图 56 + 上下 padding 10×2 → 76dp
  //  于是滑开时按钮比卡片矮 ~20dp，怎么调按钮的尺寸都没用。
  //  ⚠️ 外层**必须是 Box，不能是 Row**。
  //   动作区要"垫在卡片下面、靠右"，靠的是 Box 的**叠放**能力（`align`）；
  //   换成 Row 就变成两个**并排**的子项 ⇒ 动作区常驻在卡片左边、把卡片挤窄，
  //   而且没有任何遮挡（看起来"滑不掉"）。滑动手势只改 offset，是**叠加层**的事，
  //   天生要求 Box 这种 z 轴叠放。
  //  `height(IntrinsicSize.Min)` 在 Box 上同样有效：它的固有最小高度 =
  //  所有子项固有高度的**最大值**。这里只有内容层贡献固有高度
  //  （动作区是 `fillMaxHeight`，跟随父级、不贡献），所以
  //  Box 的高 = 卡片内容的高 ⇒ `maxHeight` 有界 ⇒ 动作区/按钮的
  //  `fillMaxHeight()` 才真正生效，两边自然等高（且行高仍随两行备注等长高）。
  Box(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    // 底层：动作按钮，铺在右侧（被上面的卡片盖住，滑开才露出）
    // ⚠️ 圆角必须与上面那张 Card 一致（12.dp）：卡片是圆角、底下露出的是直角色块，
    //   滑开那一刻会明显"对不齐"，看着像两个拼起来的东西。
    Box(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .fillMaxHeight()
        .clip(RoundedCornerShape(ROW_CORNER_DP.dp)),
    ) { actions() }

    // 上层：内容，跟手横移。
    // 这一层盖在动作区之上（写在后面 ⇒ z 序更高），滑开才会露出底下的按钮。
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .offset { IntOffset(offsetX.value.roundToInt(), 0) }
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
              // 只在明确是横向拖动时才抢手势，避免与纵向滚动打架
              // ⚠️ [Animatable.stop] 是 suspend，而 onDragStart 不是 suspend 回调，
              //   所以必须借 scope.launch 调；直接写会编译不过。
              onDragStart = { scope.launch { offsetX.stop() } },
              onHorizontalDrag = { change, dragAmount ->
                change.consume()
                scope.launch {
                  val next = (offsetX.value + dragAmount)
                    // 左侧限位：不能拖出正数（那会露出右侧空白）
                    .coerceIn(-actionsWidthPx.toFloat(), 0f)
                  offsetX.snapTo(next)
                }
              },
              onDragEnd = {
                scope.launch {
                  // 过半才吸附展开 —— 慢拖一点点不会误触，快甩一下也能到
                  val shouldOpen = -offsetX.value > halfThreshold
                  onRevealChange(shouldOpen)
                  offsetX.animateTo(if (shouldOpen) -actionsWidthPx.toFloat() else 0f)
                }
              },
              onDragCancel = {
                scope.launch {
                  offsetX.animateTo(if (revealed) -actionsWidthPx.toFloat() else 0f)
                }
              },
            )
          },
    ) { content() }
  }
}

/**
 * 滑动露出的单个动作按钮。
 *
 * ⚠️ **颜色必须走 M3 的 container / on-container 语义色，不能硬编码**。
 * 本项目主题是**用户可选的**（紫 / 蓝 / 绿 / 橙…多套，见 `AppTheme.kt`），
 * 写死一个蓝色在紫主题下就是一块突兀的色块 —— 这正是「和总体 UI 不协调」的根因。
 * 用 `primaryContainer` + `onPrimaryContainer` / `errorContainer` + `onErrorContainer`
 * 会跟着当前主题走，且**深浅色都自动可用**（硬编码的白字在浅色 container 上会糊）。
 *
 * 两个按钮**必须用不同颜色**：颜色是唯一的区分手段 ——
 * 「取消标记」与「删除」一个用主色系一个用错误色，不靠图标也能一眼分清。
 */
@Composable
fun SwipeActionButton(
  label: String,
  icon: ImageVector,
  /** 背景：主题语义色（如 [androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer]）。 */
  containerColor: Color,
  /** 前景（图标 + 文字）：必须取对应 container 的 on- 色，保证对比度。 */
  contentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        // 88dp：比原先 72dp 宽一档，四字中文标签（「取消标记」）放得开、不再挤成一团
        .width(ACTION_BUTTON_WIDTH_DP.dp)
        .fillMaxHeight()
        .background(containerColor)
        // 直接用 clickable：这两个按钮在滑动层的**上层**，
        // 不参与横向拖动手势（那是 content 那层的事），所以不会抢手势。
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        icon,
        contentDescription = label,
        tint = contentColor,
        modifier = Modifier.size(28.dp),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = label,
        color = contentColor,
        // labelLarge 是 M3 按钮的标准字号（14sp）—— 原先 labelSmall(11sp) 在
        // 这么大一块底色上像蚂蚁腿，是"很小很扁"观感的主因之一。
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
      )
    }
  }
}

/** 单个动作按钮宽度。行内按「两个按钮」算总宽，必须与 [SwipeToRevealRow] 的吸附阈值一致。 */
const val ACTION_BUTTON_WIDTH_DP = 96

/** 行圆角。动作区与 Card **必须共用这个值**，否则滑开时圆角对不齐。 */
const val ROW_CORNER_DP = 12

/**
 * 行最小高度 —— 内容层（卡片）的**兜底高度**。
 *
 * ⚠️ 真正的"两边等高"是由 [SwipeToRevealRow] 里的 `IntrinsicSize.Min` 保证的
 *   （见那段注释）：行高取自内容层的固有高度，动作区/按钮再 `fillMaxHeight()`
 *   填满它。**光写 `heightIn(min = …)` 是不够的** —— 它只钉住下限，
 *   `maxHeight` 依旧无穷，`fillMaxHeight()` 会退化、按钮还是只占自己那点内容高度。
 *
 *   这个常量现在的作用是给**内容层**兜底：条目内容很矮时（比如没备注、
 *   缩略图也没加载出来）不让行塌下去。所以它写在这里、由 `DislikeRow` 的
 *   Card 用 `defaultMinSize` 应用，而**不是**由 [SwipeToRevealRow] 用。
 */
const val ROW_MIN_HEIGHT_DP = 88