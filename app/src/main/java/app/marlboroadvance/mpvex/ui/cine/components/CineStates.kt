package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.tokens.CineSpacing
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner

/**
 * 空态 / 错误态的统一版式。
 *
 * 设计稿 04 的要求：**图标容器 + 一句「为什么空」+ 一个出口动作**。
 * 老界面的空态是一行居中文字，用户看完还是不知道该干什么 —— 这里必须给出下一步。
 *
 * @param isError `true` = 出错了（连不上 / 加载失败），图标容器换成 `errorContainer`。
 *   错误与「本来就没内容」必须长得不一样，否则用户会以为服务坏了。
 * @param description 限宽 250dp：超过这个宽度一句话会折成三行，很难看（设计稿标注）
 */
@Composable
fun CineStatePanel(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  isError: Boolean = false,
  primaryLabel: String? = null,
  onPrimary: (() -> Unit)? = null,
  secondaryLabel: String? = null,
  onSecondary: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val iconShape = remember { RoundedCornerShape(24.dp) }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 34.dp, vertical = 72.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .size(76.dp)
        .clip(iconShape)
        .background(if (isError) colors.dangerContainer else colors.skeleton),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isError) colors.onDangerContainer else colors.onSurfaceVariant,
        modifier = Modifier.size(34.dp),
      )
    }

    Spacer(Modifier.height(14.dp))

    Text(
      text = title,
      style = type.sectionTitle,
      color = colors.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )

    if (!description.isNullOrBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = description,
        style = type.body,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 250.dp),
      )
    }

    if (primaryLabel != null && onPrimary != null) {
      Spacer(Modifier.height(18.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CinePrimaryButton(label = primaryLabel, onClick = onPrimary)
        if (secondaryLabel != null && onSecondary != null) {
          CineOutlineButton(label = secondaryLabel, onClick = onSecondary)
        }
      }
    }
  }
}

/** 主按钮：实心 `primary`，高 42、全圆 */
@Composable
fun CinePrimaryButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = modifier
      .height(42.dp)
      .clip(shape)
      .background(colors.progressFill)
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = type.body.copy(fontWeight = FontWeight.W600),
      color = MaterialTheme.colorScheme.onPrimary,
      maxLines = 1,
    )
  }
}

/** 次按钮：透明底 + `outline` 描边（空态的「去逛逛首页」用这个，不抢主操作） */
@Composable
fun CineOutlineButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = modifier
      .height(42.dp)
      .clip(shape)
      .border(1.dp, colors.chipBorder, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
      color = colors.sectionAction,
      maxLines = 1,
    )
  }
}

/**
 * 骨架块：底色 + 一道从左扫到右的微光。
 *
 * 用 `drawBehind` 而不是把渐变做成 `background` 再位移 —— 位移版要给整块 `graphicsLayer`，
 * 一屏十几个骨架块就是十几个图层；`drawBehind` 只重绘，不起图层。
 */
@Composable
fun CineShimmer(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(8.dp),
) {
  val colors = CineTheme.colors
  val transition = rememberInfiniteTransition(label = "cineShimmer")
  val progress by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1500, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "cineShimmerProgress",
  )
  // 浅色下高光是白色会更"亮"，深色下反而是灰白 —— 统一用 onSurface 的低 alpha，
  // 在深浅两种底色上都是"有一道光扫过"，不会变成一块白斑
  val highlight = colors.onSurface.copy(alpha = 0.06f)

  Box(
    modifier = modifier
      .clip(shape)
      .background(colors.skeleton)
      .drawBehind {
        val sweep = size.width * 0.9f
        val startX = -sweep + progress * (size.width + sweep)
        drawRect(
          brush = Brush.linearGradient(
            colors = listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)),
            start = Offset(startX, 0f),
            end = Offset(startX + sweep, size.height),
          ),
        )
      },
  )
}

/**
 * 首页骨架屏。
 *
 * 尺寸与真实版面**一一对齐**（Hero 高度、媒体库 Chip 行、继续观看的 16:9 卡），
 * 数据落地时不会有整屏跳动 —— 这是设计稿 04「骨架屏」的核心要求。
 *
 * ⚠️ 骨架屏最容易犯的错是「摆几个差不多的灰块」：那个偏差会在数据到达的瞬间
 * 变成一次可见的位移。这里刻意按首页的真实顺序与真实高度画：
 * Hero → 媒体库标题 + Chip 行 → 继续观看标题 + 16:9 卡。
 */
@Composable
fun CineHomeSkeleton(
  heroHeight: Dp,
  wideCardWidth: Dp,
  modifier: Modifier = Modifier,
) {
  val space = CineTheme.space
  val cardCorner = LocalCardCorner.current

  Column(modifier = modifier.fillMaxSize()) {
    // Hero：满宽无圆角（与真实 Hero 一致）
    CineShimmer(
      modifier = Modifier
        .fillMaxWidth()
        .height(heroHeight),
      shape = RoundedCornerShape(0.dp),
    )

    // ── 媒体库：标题 + 一行 Chip ──
    Spacer(Modifier.height(space.firstSectionTop))
    ShimmerTitleRow(space = space)
    Spacer(Modifier.height(space.sectionHeadGap))
    Row(
      modifier = Modifier.padding(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      // Chip 是「图标 + 库名」的自适应宽度，这里用三种宽度近似
      repeat(3) { index ->
        CineShimmer(
          modifier = Modifier
            .width(if (index == 0) 104.dp else 88.dp)
            .height(40.dp),
          shape = RoundedCornerShape(50),
        )
      }
    }

    // ── 继续观看：标题 + 16:9 卡 + 图下两行 ──
    Spacer(Modifier.height(space.sectionGap))
    ShimmerTitleRow(space = space)
    Spacer(Modifier.height(space.sectionHeadGap))
    Row(
      modifier = Modifier.padding(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      repeat(2) {
        Column {
          CineShimmer(
            modifier = Modifier
              .width(wideCardWidth)
              .height(wideCardWidth * space.wideRatio),
            shape = RoundedCornerShape(cardCorner),
          )
          Spacer(Modifier.height(9.dp))
          CineShimmer(
            modifier = Modifier
              .width(wideCardWidth * 0.72f)
              .height(15.dp),
            shape = RoundedCornerShape(4.dp),
          )
          Spacer(Modifier.height(6.dp))
          CineShimmer(
            modifier = Modifier
              .width(wideCardWidth * 0.45f)
              .height(11.dp),
            shape = RoundedCornerShape(4.dp),
          )
        }
      }
    }
  }
}

/** 分区标题占位：20dp 高的短条（与 `sectionTitle` 的视觉高度一致） */
@Composable
private fun ShimmerTitleRow(space: CineSpacing) {
  CineShimmer(
    modifier = Modifier
      .padding(horizontal = space.screenH)
      .width(96.dp)
      .height(20.dp),
    shape = RoundedCornerShape(6.dp),
  )
}
