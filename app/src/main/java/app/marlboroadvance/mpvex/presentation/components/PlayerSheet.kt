@file:Suppress("DEPRECATION")

package app.marlboroadvance.mpvex.presentation.components

import android.annotation.SuppressLint
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val sheetAnimationSpec = tween<Float>(350)

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  /**
   * 是否允许关闭（点空白 / 下拉 / 返回）。
   *
   * 传 false 时任何关闭动作都会被**弹回原位**，而不是「面板滑走了、状态还占着」。
   * 后者会让这层全屏遮罩留在原地继续吃掉所有点击 —— 表现就是播放器 UI 整个失灵，
   * 点空白再也叫不出控制条（GIF 录制中就是这么踩的）。
   *
   * 需要「不能关」的面板（如录制中）就传 false，别在 [onDismissRequest] 里自己吞掉。
   */
  dismissEnabled: Boolean = true,
  tonalElevation: Dp = 1.dp,
  customMaxWidth: Dp? = null,
  customMaxHeight: Dp? = null,
  surfaceColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val latestDismissEnabled by rememberUpdatedState(dismissEnabled)
  val maxWidth = customMaxWidth ?:
  if (LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) {
    640.dp
  } else {
    420.dp
  }
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0
  val maxHeight = customMaxHeight ?: when {
    isImeVisible -> LocalConfiguration.current.screenHeightDp.dp
    LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT ->
      LocalConfiguration.current.screenHeightDp.dp * .90f
    else -> LocalConfiguration.current.screenHeightDp.dp
  }

  var backgroundAlpha by remember { mutableFloatStateOf(0f) }
  val alpha by animateFloatAsState(
    backgroundAlpha,
    animationSpec = sheetAnimationSpec,
    label = "alpha",
  )

  val decayAnimationSpec = rememberSplineBasedDecay<Float>()
  val anchoredDraggableState =
    remember {
      AnchoredDraggableState(
        initialValue = 1,
        snapAnimationSpec = sheetAnimationSpec,
        decayAnimationSpec = decayAnimationSpec,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
      )
    }
  /** 把已经滑走的面板拉回展开位（用于「此刻不允许关闭」）。 */
  val springBack = {
    scope.launch {
      backgroundAlpha = 0.5f
      anchoredDraggableState.animateTo(0)
    }
  }
  val internalOnDismissRequest = {
    if (!latestDismissEnabled) {
      // 不允许关闭：看得见的面板才关得掉，绝不留下一个隐形却还吃点击的空壳
      if (anchoredDraggableState.currentValue != 0) springBack()
    } else if (anchoredDraggableState.currentValue == 0) {
      scope.launch {
        backgroundAlpha = 0f
        anchoredDraggableState.animateTo(1)
      }
    }
  }
  Box(
    modifier =
      Modifier
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = internalOnDismissRequest,
        ).fillMaxSize()
        .background(Color.Black.copy(alpha))
        .onSizeChanged {
          val anchors =
            DraggableAnchors {
              0 at 0f
              1 at it.height.toFloat()
            }
          anchoredDraggableState.updateAnchors(anchors)
        },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      modifier =
        Modifier
          .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ).nestedScroll(
            remember(anchoredDraggableState) {
              anchoredDraggableState.preUpPostDownNestedScrollConnection()
            },
          ).then(modifier)
          .offset {
            IntOffset(
              0,
              anchoredDraggableState.offset
                .takeIf { it.isFinite() }
                ?.roundToInt()
                ?: 0,
            )
          }.anchoredDraggable(
            state = anchoredDraggableState,
            orientation = Orientation.Vertical,
          ).windowInsetsPadding(
            WindowInsets.systemBars
              .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
          ).imePadding(),
      shape = MaterialTheme.shapes.extraLarge.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
      color = surfaceColor ?: MaterialTheme.colorScheme.surface,
      tonalElevation = tonalElevation,
      content = {
        BackHandler(
          enabled = anchoredDraggableState.targetValue == 0,
          onBack = internalOnDismissRequest,
        )
        content()
      },
    )

    LaunchedEffect(true) {
      backgroundAlpha = 0.5f
    }

    LaunchedEffect(anchoredDraggableState) {
      scope.launch { anchoredDraggableState.animateTo(0) }
      snapshotFlow { anchoredDraggableState.currentValue }
        .drop(1)
        .filter { it == 1 }
        .collectLatest {
          if (latestDismissEnabled) {
            latestOnDismissRequest()
          } else {
            // 拖拽 / 惯性把它甩到了关闭位，但调用方此刻不让关 → 弹回展开位。
            // 这里绝不能「什么都不做」：那正是面板消失后整屏点击失灵的原因。
            backgroundAlpha = 0.5f
            anchoredDraggableState.animateTo(0)
          }
        }
    }
  }
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection() =
  object : NestedScrollConnection {
    override fun onPreScroll(
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      val delta = available.toFloat()
      return if (delta < 0 && source == NestedScrollSource.UserInput) {
        dispatchRawDelta(delta).toOffset()
      } else {
        Offset.Zero
      }
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset =
      if (source == NestedScrollSource.UserInput) {
        dispatchRawDelta(available.toFloat()).toOffset()
      } else {
        Offset.Zero
      }

    override suspend fun onPreFling(available: Velocity): Velocity {
      val toFling = available.toFloat()
      return if (toFling < 0 && offset > anchors.minPosition()) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    override suspend fun onPostFling(
      consumed: Velocity,
      available: Velocity,
    ): Velocity {
      val toFling = available.toFloat()
      return if (toFling > 0) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = y

    private fun Offset.toFloat(): Float = y
  }
