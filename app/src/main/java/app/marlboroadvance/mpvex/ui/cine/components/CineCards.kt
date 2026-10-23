package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineBadgeUi
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

/** 海报 2:3（高 = 宽 × 1.5） */
private const val POSTER_RATIO = 1.5f

/** 继续观看 16:9（高 = 宽 × 9/16） */
private const val WIDE_RATIO = 9f / 16f

/** 标题左右内缩（设计稿 9dp） */
private val TITLE_INSET = 9.dp

/** 标题行高 = 字号 × 1.24（设计稿 12.5 / 15.5） */
private const val TITLE_LINE_HEIGHT_SCALE = 1.24f

/**
 * 标题「阶梯降字号」的三档缩放（相对基准 12.5sp）。
 *
 * 0.92 ⇒ 11.5sp，0.84 ⇒ 10.5sp。再小就失去标题的属性了，宁可用省略号。
 */
private val TITLE_SCALE_STEPS = listOf(1f, 0.92f, 0.84f)

/**
 * 海报卡（2:3）。标题**压在图上**，图下只留一行元信息。
 *
 * 组件只吃 [CineMediaUi]（已映射好的展示模型），不直接读 `EmbyItem` ——
 * 字段口径统一在 `CineItemMapper` 里，见那边的说明。
 *
 * @param width 卡片宽（手机 132 / 宽屏 168）。高度按 2:3 由宽度算出、**不写死**，
 *   这样宽屏换尺寸时标题可用宽度也跟着变，自适应才成立。
 */
@Composable
fun CinePosterCard(
  media: CineMediaUi,
  width: Dp,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = LocalCardCorner.current
  val shape: Shape = remember(corner) { RoundedCornerShape(corner) }
  val artHeight = width * POSTER_RATIO

  // 标题两侧内缩 TITLE_INSET，自适应测量的可用宽度必须用同一个数
  val titleAvailableWidth = (width - TITLE_INSET * 2).coerceAtLeast(1.dp)
  // 海报可能只有 16:9 的 Backdrop（音乐专辑 / 部分剧集），没有竖版主图时退回横向图，
  // 总比显示一个灰色的占位图标强 —— 此时不能走「缺图」分支的那个黑底标题
  val poster = media.posterUrl ?: media.thumbUrl
  val hasImage = !poster.isNullOrBlank()
  val titleStyle = fittedTitleStyle(
    text = media.title,
    maxWidth = titleAvailableWidth,
    base = type.posterTitle,
    maxLines = 2,
    // 压图才加投影：缺图时底色是主题表面色，再叠一层黑影会脏
    shadow = if (hasImage) cineImageTextShadow() else null,
  )
  val progress = media.progress

  Column(modifier = modifier.width(width)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(artHeight)
        // 设计稿的卡片投影（0 6 18 / 34%）。放在 clip 之前：shadow 要画在形状之外，
        // 先 clip 的话投影会被裁掉，卡片看起来就是「贴」在背景上而不是浮着
        .shadow(elevation = 6.dp, shape = shape)
        .clip(shape)
        .background(colors.skeleton)
        .pressDownScale()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
      if (hasImage) {
        EmbyImage(
          url = poster,
          contentDescription = media.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          // 与 CineItemMapper 的 POSTER_DECODE_WIDTH 对齐：URL 已按 420 请求，
          // 解码上限再大也只是白占内存（大库横滑时同时活着的封面能有几十张）
          maxWidth = 420,
        )
        // 压暗层：标题压图的可读性全靠它。缺图时不画（空图上一块脏渐变毫无意义）
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(colors.posterScrim),
        )
      } else {
        // 缺图：占位图标居中，标题走 onSurface（不是白色 —— 底色是主题色，白字会糊）
        Icon(
          imageVector = Icons.Outlined.Movie,
          contentDescription = null,
          tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier
            .align(Alignment.Center)
            .size(28.dp),
        )
      }

      // 角标：右上角单枚（HDR / 4K / 评分），没有就不画
      media.badge?.let { badge ->
        CineBadge(
          badge = badge,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 7.dp, end = 7.dp),
        )
      }

      // 收藏：左上角。只画「已收藏」—— 未收藏时这个位置留给角标，空着更干净
      if (media.isFavorite) {
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 6.dp, top = 6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(colors.progressFill),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "已收藏",
            tint = colors.onImage,
            modifier = Modifier.size(11.dp),
          )
        }
      }

      // 进度条贴底：有进度时标题上抬 6dp 让位（设计稿 .pcard.hasprog）
      if (progress != null) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.onImageTrack),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(progress.coerceIn(0f, 1f))
              .fillMaxHeight()
              .background(colors.progressFill),
          )
        }
      }

      Text(
        text = media.title,
        style = titleStyle,
        color = if (hasImage) colors.onImage else colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(
            start = TITLE_INSET,
            end = TITLE_INSET,
            bottom = if (progress != null) 15.dp else TITLE_INSET,
          ),
      )
    }

    // ⚠️ 用 minLines = 1 而不是「有值才画」：同一行里有的卡有元信息、有的没有时，
    //    「有没有这一行」会让卡片总高不同，导致横滑行里卡片底部参差。
    Text(
      text = media.meta,
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      minLines = 1,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/**
 * 继续观看卡（16:9）。信息分层与海报卡不同：集号在左上、剩余时长在左下、进度条贴底。
 *
 * 为什么不用竖版海报：横滑的「继续观看」是**剧集语义**，16:9 缩略更接近用户对
 * 「这一集」的识别习惯（设计稿 06 组件明细）。
 */
@Composable
fun CineWideCard(
  media: CineMediaUi,
  width: Dp,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = LocalCardCorner.current
  val shape: Shape = remember(corner) { RoundedCornerShape(corner) }
  val artHeight = width * WIDE_RATIO
  // 16:9 缩略上压着的两行字（集号 / 剩余时长）都是白字，同样需要投影兜底
  val imageShadow = cineImageTextShadow()

  Column(modifier = modifier.width(width)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(artHeight)
        .shadow(elevation = 6.dp, shape = shape)
        .clip(shape)
        .background(colors.skeleton)
        .pressDownScale()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
      EmbyImage(
        url = media.thumbUrl,
        fallbackUrl = media.posterUrl,
        contentDescription = media.wideTitle,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 720,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(colors.wideScrim),
      )

      // 左上角集号：S1 · E4 / 影片
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 7.dp, top = 7.dp)
          .clip(RoundedCornerShape(5.dp))
          .background(colors.badgeGhostSurface)
          .padding(horizontal = 6.dp, vertical = 4.dp),
      ) {
        Text(
          text = media.episodeTag,
          style = type.badge.copy(fontSize = 9.5.sp, shadow = imageShadow),
          color = colors.onImage,
          maxLines = 1,
        )
      }

      // 左下角剩余时长：拿不到就整块不画（不写「剩余未知」这种废话）
      media.remaining?.let { remaining ->
        Row(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 8.dp, bottom = 9.dp, end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = colors.onImage,
            modifier = Modifier.size(11.dp),
          )
          Text(
            text = remaining,
            style = type.cardMeta.copy(fontSize = 11.sp, shadow = imageShadow),
            color = colors.onImage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // 进度条：贴底满宽（设计稿 w-progress）
      media.progress?.let { p ->
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(3.dp)
            .background(colors.onImageTrack),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(p.coerceIn(0f, 1f))
              .fillMaxHeight()
              .background(colors.progressFill),
          )
        }
      }
    }

    // 两行都是 minLines = 1：继续观看卡的高度必须一致，否则横滑时下方卡片边缘参差
    Text(
      text = media.wideTitle,
      style = type.itemTitle,
      color = colors.onSurface,
      minLines = 1,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 9.dp),
    )
    Text(
      text = media.wideMeta,
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      minLines = 1,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

/**
 * 角标（4K / HDR / 评分）。强角标走 `tertiaryContainer`，弱角标是压图用的半透明白 + 描边。
 */
@Composable
fun CineBadge(
  badge: CineBadgeUi,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }
  val bg = if (badge.ghost) colors.badgeGhostSurface else colors.badgeStrong
  val fg = if (badge.ghost) colors.onImage else colors.onBadgeStrong

  Box(
    modifier = modifier
      .clip(shape)
      .background(bg)
      .then(if (badge.ghost) Modifier.border(1.dp, colors.badgeGhostBorder, shape) else Modifier)
      .height(17.dp)
      .padding(horizontal = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = badge.text,
      style = type.badge.copy(fontSize = 9.sp),
      color = fg,
      maxLines = 1,
    )
  }
}

/**
 * Hero 上的收藏圆钮。
 *
 * 三种状态必须能区分：已收藏（实心 primary）、未收藏（玻璃）、请求中（透明度降低）。
 * 分散写容易出现「点了没反应」的错觉 —— 所以把它收成一个组件。
 */
@Composable
fun CineFavoriteButton(
  isFavorite: Boolean,
  busy: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = CineTheme.colors
  val shape = remember { CircleShape }

  Box(
    modifier = modifier
      .size(46.dp)
      .clip(shape)
      .background(
        if (isFavorite) {
          colors.progressFill.copy(alpha = if (busy) 0.6f else 1f)
        } else {
          colors.badgeGhostSurface
        },
      )
      .clickable(enabled = !busy, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
      contentDescription = if (isFavorite) "取消收藏" else "收藏",
      tint = colors.onImage,
      modifier = Modifier.size(18.dp),
    )
  }
}

/**
 * 卡片标题的自适应字号。
 *
 * ## 要解决的具体问题
 * 海报只有 132dp 宽，标题压在图上、最多 2 行。三个字的标题一行很漂亮，
 * 但「我的天才女友第四季」这种 9 字标题在 12.5sp 下会溢出到第 3 行 ——
 * 直接 `maxLines = 2 + Ellipsis` 会截成「我的天才女友第四…」，
 * 而且同一行里各卡片的断行位置不同，观感参差。
 *
 * ## 做法：先量再定档
 * 用 [rememberTextMeasurer] 按真实可用宽度量一遍：
 * 1. 12.5sp 能放进 2 行 → 就用 12.5（绝大多数标题，包括 3~4 字短标题）；
 * 2. 否则降到 11.5sp 再量；
 * 3. 再不行降到 10.5sp 并允许省略号。
 *
 * 这样「短标题保持设计稿字号」「长标题尽量少截字」「极长标题至少不溢出」，
 * 而且字号只有三档 —— 同一屏里不会出现五个不同字号的标题。
 *
 * ## 为什么不用 `TextAutoSize`
 * `autoSize` 会按可用空间连续缩放，**每个标题自己算一个字号**，
 * 同一行卡片会出现 12.3 / 11.8 / 13.1 这种参差；阶梯档位是有意的取舍。
 *
 * @param maxWidth 文本可用宽度（已扣掉左右内缩）
 * @param base 基准字阶（只用到 fontSize / lineHeight / weight / letterSpacing）
 * @param minFontSize 下限，低于它就不再缩
 * @param maxLines 允许的最大行数
 */
@Composable
fun fittedTitleStyle(
  text: String,
  maxWidth: Dp,
  base: TextStyle,
  minFontSize: TextUnit = 10.5.sp,
  maxLines: Int = 2,
  scaleSteps: List<Float> = TITLE_SCALE_STEPS,
  shadow: Shadow? = null,
): TextStyle {
  val measurer = rememberTextMeasurer(cacheSize = 32)
  val density = LocalDensity.current
  // 测量用的字体必须与最终渲染一致：`Text` 的实际样式是 `LocalTextStyle.merge(style)`，
  // 只拿 base 量会漏掉字体族 —— 一旦换了自定义字体，同一串字的实测宽度与测量宽度
  // 差出一截，降档判断就会失准（该降的没降 ⇒ 真机上溢出）。
  val inherited = LocalTextStyle.current
  return remember(
    text,
    maxWidth,
    base,
    maxLines,
    minFontSize,
    scaleSteps,
    density,
    inherited,
    shadow,
  ) {
    val widthPx = with(density) { maxWidth.roundToPx() }
    val resolved = if (text.isEmpty() || widthPx <= 0) {
      base
    } else {
      val measuringBase = inherited.merge(base)
      var chosen: TextUnit? = null
      for (step in scaleSteps) {
        val size = (base.fontSize.value * step).sp
        if (size < minFontSize) continue
        val trial = measuringBase.copy(
          fontSize = size,
          lineHeight = size * TITLE_LINE_HEIGHT_SCALE,
        )
        val result = measurer.measure(
          text = AnnotatedString(text),
          style = trial,
          overflow = TextOverflow.Ellipsis,
          maxLines = maxLines,
          constraints = Constraints(maxWidth = widthPx),
        )
        if (!result.hasVisualOverflow) {
          chosen = size
          break
        }
      }
      val size = chosen ?: minFontSize
      base.copy(fontSize = size, lineHeight = size * TITLE_LINE_HEIGHT_SCALE)
    }
    if (shadow != null) resolved.copy(shadow = shadow) else resolved
  }
}

/**
 * 压在图片上的标题投影（设计稿 `text-shadow: 0 1px 6px rgba(0,0,0,.6)`）。
 *
 * 光靠渐变压暗不够：海报底部经常是浅色画面（雪地 / 天空 / 白墙），
 * 白色标题在 12.5sp 下会糊在背景上。投影是第二道保险，代价只有一次绘制。
 *
 * 偏移与模糊半径都按 dp 换算 —— 写死 px 在不同密度屏上会一边太硬一边看不见。
 */
@Composable
fun cineImageTextShadow(
  color: Color = Color.Black.copy(alpha = 0.6f),
  dy: Dp = 1.dp,
  blur: Dp = 6.dp,
): Shadow {
  val density = LocalDensity.current
  return remember(density, color, dy, blur) {
    Shadow(
      color = color,
      offset = Offset(0f, with(density) { dy.toPx() }),
      blurRadius = with(density) { blur.toPx() },
    )
  }
}
