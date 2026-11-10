package app.marlboroadvance.mpvex.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.controls.LocalPlayerButtonsClickEvent
import app.marlboroadvance.mpvex.ui.theme.spacing
import org.koin.compose.koinInject

/**
 * 当前按钮的显示名，供「长按浮出名称」使用。
 *
 * 由 [app.marlboroadvance.mpvex.ui.player.controls.RenderPlayerButton] 按 `PlayerButton`
 * 逐按钮下发（`CompositionLocal` 包住整个 `when`，所以 20 多个分支不用各改一遍）。
 * 不提供时＝null ⇒ 不做名称气泡，行为与旧版完全一致。
 */
val LocalPlayerButtonLabel = staticCompositionLocalOf<String?> { null }

/** 名称气泡的停留时长。够看清一个词，又不至于挡住画面。 */
private const val BUTTON_LABEL_SHOW_MS = 1500L

/** 气泡与按钮边沿的间距。按钮 40~45dp，气泡高约 24dp，34dp 能保证完全不压住图标。 */
private val BUTTON_LABEL_GAP = 34.dp

@Suppress("ModifierClickableOrder")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlsButton(
  icon: ImageVector? = null,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  /**
   * 长按动作。**默认 null ⇒ 长按浮出按钮名称**（[LocalPlayerButtonLabel]）。
   *
   * 之所以把默认值从 `{}` 改成 null：早前每个调用点都要手写一个空 lambda，
   * 分不清「没写长按」和「写了空实现」。现在 null = 交给名称气泡，
   * 显式传入 = 那个动作优先（如字幕键长按开延迟面板、不喜欢键长按写备注）。
   */
  onLongClick: (() -> Unit)? = null,
  title: String? = null,
  color: Color? = null,
  /** 为 false 时按钮置灰且不响应点击（用于播放队列首尾 / 请求进行中） */
  enabled: Boolean = true,
  /**
   * 自定义图标内容（给了它就不用 [icon]）。
   *
   * 存在的原因：上一集 / 下一集要画**弧边三角 + 按下时的滑入动画**，那是自绘的
   * Canvas，不是 ImageVector。尺寸/内边距与外层 Icon 那条分支保持一致，
   * 否则同一排按钮的图标大小会不一致。
   *
   * 参数是该按钮当前的有效色（已含置灰的 0.38 alpha），自绘时直接用即可。
   */
  iconContent: (@Composable (Color) -> Unit)? = null,
  /**
   * 名称气泡出现在按钮**下方**。顶栏那一排必须为 true ——
   * 它们贴着屏幕顶边，气泡朝上会被裁掉。
   */
  labelBelow: Boolean = false,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val appearancePreferences = koinInject<AppearancePreferences>()
  val hideBackground by appearancePreferences.hidePlayerButtonsBackground.collectAsState()

  val effectiveColor = if (enabled) color else (color ?: controlColor).copy(alpha = 0.38f)

  val label = LocalPlayerButtonLabel.current
  var labelShown by remember { mutableStateOf(false) }
  // 到点自动收起。用 LaunchedEffect(labelShown) 而不是在长按回调里 launch：
  // 回调拿不到可取消的作用域，连续长按两个按钮会残留两个计时器，
  // 后一个被前一个提前清掉，表现就是「第二下气泡闪一下就没」。
  LaunchedEffect(labelShown) {
    if (labelShown) {
      delay(BUTTON_LABEL_SHOW_MS)
      labelShown = false
    }
  }
  val effectiveLongClick: (() -> Unit)? =
    onLongClick ?: label?.let { { labelShown = true } }

  val clickEvent = LocalPlayerButtonsClickEvent.current

  // Box 只负责给气泡一个定位参照，本身不加尺寸 —— 尺寸仍由调用方的 modifier 决定，
  // 这样 40dp / 42dp / 45dp 三种按钮不用各写一套。
  Box {
    Surface(
      modifier =
        modifier
          .clip(CircleShape)
          .combinedClickable(
            enabled = enabled,
            onClick = {
              // 点一下就意味着「我知道了」，顺手把气泡收掉，免得压在画面上
              labelShown = false
              clickEvent()
              onClick()
            },
            onLongClick = effectiveLongClick,
            interactionSource = interactionSource,
            indication = ripple(),
          ),
      shape = CircleShape,
      color = if (hideBackground) Color.Transparent else PlayerControlGlassFill,
      contentColor = effectiveColor ?: controlColor,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border =
        if (hideBackground) {
          null
        } else {
          BorderStroke(
            1.dp,
            PlayerControlGlassBorder,
          )
        },
    ) {
      if (iconContent != null) {
        Box(
          modifier =
            Modifier
              .padding(MaterialTheme.spacing.small)
              .size(20.dp),
        ) {
          iconContent(effectiveColor ?: controlColor)
        }
      } else if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = effectiveColor ?: controlColor,
          modifier =
            Modifier
              .padding(MaterialTheme.spacing.small)
              .size(20.dp),
        )
      }
    }

    if (labelShown && label != null) {
      Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier =
          Modifier
            .align(if (labelBelow) Alignment.TopCenter else Alignment.BottomCenter)
            .offset(y = if (labelBelow) BUTTON_LABEL_GAP else -BUTTON_LABEL_GAP),
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
  }
}

/**
 * 播放器控件的「玻璃底」：近黑半透明 + 极细高光描边。
 *
 * 之前用的是 `surfaceContainer` 55% 不透明度，压在视频上看起来就是一个个实心灰圆盘；
 * 换成近黑低透明度后，底下的画面会透出来，才像官方那种「浮在视频之上的玻璃按钮」。
 * 各页面共用同一组常量，改一处即可全局生效。
 */
val PlayerControlGlassFill = Color.Black.copy(alpha = 0.32f)

/** 播放器控件玻璃描边（极细白色高光），让圆边在明暗两种画面上都能被看清 */
val PlayerControlGlassBorder = Color.White.copy(alpha = 0.14f)

@Composable
fun ControlsGroup(
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = MaterialTheme.spacing

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      androidx.compose.foundation.layout.Arrangement
        .spacedBy(spacing.extraSmall),
    content = content,
  )
}

@Preview
@Composable
private fun PreviewControlsButton() {
  ControlsButton(
    Icons.Default.CatchingPokemon,
    onClick = {},
  )
}
