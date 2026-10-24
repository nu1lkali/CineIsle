package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 首页 Hero。
 *
 * 一屏只允许一个焦点（设计原则 2）：Hero 占屏高约 62%，把「今晚看什么」直接回答掉，
 * 下面的分区全部降级为「还可以看什么」。
 *
 * ## 视差怎么做
 * 图片层只做 `graphicsLayer` 的 scale + translationY —— 纯绘制变换，不参与布局。
 * 用 padding / offset 做视差会让 Hero 高度随滚动变化，进而导致列表位置抖动。
 *
 * @param parallaxFraction 0 = 未滚动，1 = Hero 已滚出；驱动 scale 1 → 1.08 与轻微下移
 */
@Composable
fun CineHeroBanner(
  media: CineMediaUi,
  heroHeight: Dp,
  parallaxFraction: Float,
  isFavorite: Boolean,
  favoriteBusy: Boolean,
  primaryLabel: String,
  onPlay: () -> Unit,
  onOpenDetail: () -> Unit,
  onToggleFavorite: () -> Unit,
  modifier: Modifier = Modifier,
  /**
   * 轮播页数 / 当前页。
   *
   * 只有一页时传 `1`（或 0）就不画指示点 —— 单张 Hero 配四个点会让人以为滑不动。
   * 宽屏不画点（设计稿 03 的宽屏 Hero 没有指示点，它一屏就能看到整幅图 + 分区）。
   */
  pageCount: Int = 0,
  currentPage: Int = 0,
  /**
   * 顶部压暗层高度。
   *
   * 手机端顶栏压在 Hero 上 ⇒ 需要一层暗底给品牌行与状态栏；宽屏顶栏在 Hero 之外，
   * 传 `0.dp` 让它彻底不画 —— 否则宽屏 Hero 上半截会莫名其妙比下半截暗。
   */
  topScrimHeight: Dp = 200.dp,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  // 标题可用宽度 = Hero 实际宽度 - 左右边距。
  // ⚠️ 用 onSizeChanged 拿真实宽度，**不用 LocalConfiguration.screenWidthDp**：
  //    宽屏（≥600dp）时 Hero 只占「内容区」宽度（屏宽减掉 88dp 的 Rail），
  //    拿屏宽会算多 88dp ⇒ 长标题按错误的宽度降到不该降的字号。
  //    Hero 的宽度也不等于「满宽出血」以外的情况（分屏 / 折叠屏展开），
  //    只有实测才在所有形态下都对。
  var heroWidth by remember { mutableStateOf(0.dp) }
  val density = LocalDensity.current
  // Hero 永远是满幅图，标题一律带投影（浅色海报上白字没有投影会糊成一片）
  val titleShadow = cineImageTextShadow(blur = 10.dp)
  val titleStyle = if (heroWidth > 0.dp) {
    fittedTitleStyle(
      text = media.title,
      maxWidth = (heroWidth - space.screenH * 2).coerceAtLeast(1.dp),
      base = type.heroTitle,
      minFontSize = 26.sp,
      maxLines = 2,
      scaleSteps = HERO_TITLE_SCALE_STEPS,
      shadow = titleShadow,
    )
  } else {
    // 首帧还不知道宽度：直接用基准字阶，**不要**拿 0 宽度去量
    // （量出来必然溢出 ⇒ 首帧会闪一下最小字号）
    type.heroTitle.copy(shadow = titleShadow)
  }

  val imageUrl = media.heroUrl ?: media.posterUrl
  val blurFraction = parallaxFraction.coerceIn(0f, 1f)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(heroHeight)
      .clipToBounds()
      .onSizeChanged { heroWidth = with(density) { it.width.toDp() } },
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          val scale = 1f + 0.08f * blurFraction
          scaleX = scale
          scaleY = scale
          translationY = size.height * 0.06f * blurFraction
        },
    ) {
      EmbyImage(
        url = imageUrl,
        fallbackUrl = media.posterUrl,
        contentDescription = media.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        // Hero 是满宽出血，解码上限要给足：默认 720 在 1080p 竖屏上会明显糊
        maxWidth = 1280,
      )
    }

    // 顶部压暗：给状态栏与品牌行留可读背景
    if (topScrimHeight > 0.dp) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(topScrimHeight)
          .background(colors.heroScrimTop),
      )
    }

    // 底部压暗：透明 → surface，与页面底色无缝接上（换任意主题都不会出现硬边）
    Box(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .height(260.dp)
        .background(colors.heroScrimBottom),
    )

    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(horizontal = space.screenH)
        .padding(bottom = 22.dp),
    ) {
      if (media.heroBadges.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          media.heroBadges.forEach { badge ->
            CineBadge(badge = badge)
          }
        }
        Spacer(Modifier.height(11.dp))
      }

      Text(
        text = media.title,
        style = titleStyle,
        color = colors.onImage,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )

      if (media.heroMetaParts.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        HeroMetaRow(parts = media.heroMetaParts)
      }

      Spacer(Modifier.height(16.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        HeroPrimaryButton(label = primaryLabel, onClick = onPlay)
        HeroTonalButton(label = stringResource(R.string.cine_action_detail), onClick = onOpenDetail)
        CineFavoriteButton(
          isFavorite = isFavorite,
          busy = favoriteBusy,
          onClick = onToggleFavorite,
        )
        // 指示点推到最右（设计稿 `.hero-dots{margin-left:auto}`），
        // 用 weight 占掉剩余空间 —— 用固定 spacer 在长标题/大字号下会顶出去
        if (pageCount > 1) {
          Spacer(Modifier.weight(1f))
          HeroPageDots(count = pageCount, current = currentPage)
        }
      }
    }
  }
}

/**
 * 元信息行：`2024 · 科幻 / 悬疑 · 2小时12分 · 9.1`。
 *
 * ⚠️ 这里**必须是一整条 Text**，不能拆成「若干 Text + 之间的圆点 Box」的 Row：
 * Row 在约束不够时会把后面的子项压成 0 宽，末尾的评分会**直接消失**（不是省略），
 * 用户看到的是「这条片子没有评分」而不是「没显示全」。
 * 拼成一个字符串后，超宽时由 Ellipsis 统一收尾，信息缺失是可感知的。
 */
@Composable
private fun HeroMetaRow(parts: List<String>) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  Text(
    text = parts.joinToString("  ·  "),
    style = type.meta,
    color = colors.onImageMuted,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Hero 轮播指示点（设计稿 `.hero-dots`）：当前页是 16×5 的胶囊，其余是 5dp 圆点。
 *
 * 用**形状**而不是颜色区分当前页：压在剧照上时颜色差异很容易被背景吃掉
 * （雪地 / 天空 / 白墙），长短差异在任何画面上都认得出来。
 */
@Composable
private fun HeroPageDots(count: Int, current: Int) {
  val colors = CineTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    repeat(count) { index ->
      val active = index == current
      // 当前页的胶囊宽度做动画：翻页时点「长出来」比瞬间跳变更容易被注意到
      val dotWidth by animateDpAsState(
        targetValue = if (active) 16.dp else 5.dp,
        animationSpec = tween(durationMillis = 180),
        label = "heroDotWidth",
      )
      Box(
        modifier = Modifier
          .height(5.dp)
          .width(dotWidth)
          .clip(RoundedCornerShape(3.dp))
          .background(if (active) colors.onImage else colors.onImage.copy(alpha = 0.34f)),
      )
    }
  }
}

/** 主按钮：实心 primary + 播放图标。高度 46 是拇指友好的下限。 */
@Composable
private fun HeroPrimaryButton(
  label: String,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = Modifier
      .height(46.dp)
      // 设计稿 .btn-fill 的投影（`0 8px 22px rgba(0,0,0,.32)`）：
      // 主按钮压在剧照上，没有投影就和画面糊在一起，看不出这是可点的按钮
      .shadow(elevation = 8.dp, shape = shape)
      .clip(shape)
      .background(colors.progressFill)
      .clickable(onClick = onClick)
      .padding(horizontal = 22.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.PlayArrow,
      contentDescription = null,
      // 主按钮的文字色由主题保证（onPrimary），不从 onImage 借
      tint = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
      modifier = Modifier.size(16.dp),
    )
    Text(
      text = label,
      style = type.button,
      color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
      maxLines = 1,
    )
  }
}

/** 次按钮：玻璃底 + 白描边（压在图上，所以不用 outlinedButton 的主题色） */
@Composable
private fun HeroTonalButton(
  label: String,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = Modifier
      .height(46.dp)
      .clip(shape)
      .background(colors.badgeGhostSurface)
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Icon(
      imageVector = Icons.Outlined.Info,
      contentDescription = null,
      tint = colors.onImage,
      modifier = Modifier.size(15.dp),
    )
    Text(
      text = label,
      style = type.button.copy(fontSize = 14.sp, fontWeight = FontWeight.W600),
      color = colors.onImage,
      maxLines = 1,
    )
  }
}

/**
 * Hero 标题的降档梯度：40 → 34 → 28.8sp。
 *
 * 海报卡用 0.92/0.84 的细梯度（字号小，差 1sp 就很明显）；
 * Hero 标题字号大，必须**一步差 6sp 左右**才看得出"变紧凑了"而不是"排错了"。
 */
private val HERO_TITLE_SCALE_STEPS = listOf(1f, 0.85f, 0.72f)
