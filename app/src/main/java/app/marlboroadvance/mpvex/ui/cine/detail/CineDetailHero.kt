package app.marlboroadvance.mpvex.ui.cine.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyPerson
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

/**
 * 详情页头部：剧照 + 居中信息块（对齐流媒体客户端的详情页版式）。
 *
 * ## 与上一版的区别（v134 重构）
 * 上一版是「满宽剧照 + 26dp 圆角纸片从图底下滑上来」的层级关系。这一版改成**图直接
 * 收进底色**：剧照底部用一段渐变压到 `surface`，信息块紧接着从图底开始，中间没有
 * 圆角分界。视觉上整页是一块连续的暗面，只有海报在发光 —— 这是参考图的做法，
 * 比「一张纸压在图上」更像播放器，不像卡片列表。
 *
 * ## 为什么全部居中
 * 详情页只有一个主角（这部片子）。左对齐会让「评分 / 规格 / 类型」三行各自撞在左边缘，
 * 形成三条长度不一的锯齿；居中之后它们叠成一条视觉中轴，往下接到全宽主按钮，
 * 整块信息有明确的重量中心。
 *
 * ## 主按钮为什么是「反相实心」而不是主题色
 * `primary` 在动态取色下可能是任意色相，压在剧照上很容易和画面撞色（红底红按钮）。
 * 用 `onSurface` 作底、`surface` 作字，深色主题得到「白底黑字」、浅色主题得到
 * 「黑底白字」—— 无论什么主题它都是这一屏**对比度最高**的元素，而主操作必须如此。
 * 顶部再叠一道内高光，避免大块纯色板显得像系统控件。
 */
@Composable
internal fun CineDetailHero(
  item: EmbyItem,
  media: CineMediaUi?,
  backdropHeight: Dp,
  wide: Boolean,
  cast: List<EmbyPerson>,
  castAvatarUrl: (EmbyPerson) -> String?,
  isFavorite: Boolean,
  isPlayed: Boolean,
  favoriteBusy: Boolean,
  overviewExpanded: Boolean,
  onPlay: () -> Unit,
  onToggleFavorite: () -> Unit,
  onTogglePlayed: () -> Unit,
  onToggleOverview: () -> Unit,
  onOpenPerson: (EmbyPerson) -> Unit,
  /** 投屏：`null` 表示这条没有可投的播放地址，圆钮不出现 */
  onCast: (() -> Unit)? = null,
) {
  val colors = CineTheme.colors
  val space = CineTheme.space
  val type = CineTheme.type

  val positionTicks = item.UserData?.PlaybackPositionTicks ?: 0L
  val runtimeTicks = item.RunTimeTicks ?: 0L
  val progress = if (runtimeTicks > 0 && positionTicks > 0) {
    (positionTicks.toFloat() / runtimeTicks.toFloat()).coerceIn(0f, 1f)
  } else {
    null
  }

  Box(modifier = Modifier.fillMaxWidth()) {
    // ── 剧照 + 落地渐变 ──
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(backdropHeight)
        .background(colors.skeleton),
    ) {
      EmbyImage(
        url = media?.heroUrl ?: media?.posterUrl,
        fallbackUrl = media?.posterUrl,
        contentDescription = item.Name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 1600,
      )
      // 顶部压暗：状态栏图标与右下角的悬浮圆钮都要压在图上
      Box(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .height(120.dp)
          .background(colors.heroScrimTop),
      )
      // 底部落地：detailScrim 的最后一档是**全不透明** surface，
      // 所以图底与页面底色之间不存在色带或接缝，信息块可以紧跟其后
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(backdropHeight * 0.62f)
          .background(colors.detailScrim),
      )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
      Spacer(Modifier.height(backdropHeight))
      Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
          modifier = Modifier
            // 宽屏上信息块不跟着整屏拉满：一行 900dp 长的简介没人读得下去
            .widthIn(max = if (wide) 620.dp else Dp.Unspecified)
            .fillMaxWidth()
            .padding(horizontal = space.screenH),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          DetailTitleArea(
            name = item.Name.orEmpty(),
            logoUrl = media?.logoUrl,
          )

          RatingRow(
            rating = item.CommunityRating,
            officialRating = item.OfficialRating,
          )

          val specs = media?.techSpecs.orEmpty()
          if (specs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = specs.joinToString("  "),
              style = type.meta,
              color = colors.onSurfaceVariant,
              textAlign = TextAlign.Center,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          val genres = media?.genresLine
          if (!genres.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
              text = genres,
              style = type.meta,
              color = colors.onSurfaceVariant.copy(alpha = 0.82f),
              textAlign = TextAlign.Center,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          Spacer(Modifier.height(18.dp))
          HeroPlayButton(
            label = stringResource(
              if (progress != null) R.string.cine_action_resume else R.string.cine_action_play,
            ),
            progress = progress,
            onClick = onPlay,
          )

          Spacer(Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircleAction(
              icon = Icons.Filled.Check,
              contentDescription = stringResource(
                if (isPlayed) R.string.cine_action_unmark_played else R.string.cine_action_mark_played,
              ),
              active = isPlayed,
              onClick = onTogglePlayed,
            )
            CircleAction(
              icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
              contentDescription = stringResource(
                if (isFavorite) R.string.cine_action_unfavorite else R.string.cine_action_favorite,
              ),
              active = isFavorite,
              enabled = !favoriteBusy,
              onClick = onToggleFavorite,
            )
            // 投屏排在最后：前两颗是「这片子我什么状态」，投屏是「换块屏幕看」，
            // 语义上离主按钮远一格，扫视时不会和播放混淆
            if (onCast != null) {
              CircleAction(
                icon = Icons.Outlined.Cast,
                contentDescription = stringResource(R.string.cine_cast_title),
                active = false,
                onClick = onCast,
              )
            }
          }

          // 续播信息：主按钮里的进度条只说明「看到哪」，这里补上「还剩多少」
          if (progress != null && runtimeTicks > 0) {
            Spacer(Modifier.height(10.dp))
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            val remaining = formatRemainingPlain(
              EmbyTicks.ticksToSeconds((runtimeTicks - positionTicks).coerceAtLeast(0L)),
            )
            Text(
              text = stringResource(R.string.cine_detail_progress, percent, remaining),
              style = type.cardMeta,
              color = colors.onSurfaceVariant,
              textAlign = TextAlign.Center,
              maxLines = 1,
            )
          }

          val overview = item.Overview?.trim().orEmpty()
          if (overview.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            // 简介**左对齐**：三行以上居中会造成每行起止位置都不同，读起来眼睛没有锚点
            Text(
              text = overview,
              style = type.body,
              color = colors.onSurfaceVariant,
              maxLines = if (overviewExpanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleOverview),
            )
          }

          if (cast.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text(
              text = stringResource(R.string.cine_detail_cast),
              style = type.sectionTitle.copy(fontSize = 17.sp),
              color = colors.onSurface,
              textAlign = TextAlign.Start,
              // 不用 Modifier.align(CenterStart)：这里外层 Box + 内层 Column 两个 scope 都在场，
              // align 的隐式接收者会撞车（BoxScope.align vs ColumnScope.align），编译器直接报错
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      if (cast.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        CastRow(
          cast = cast,
          wide = wide,
          avatarUrl = castAvatarUrl,
          onOpenPerson = onOpenPerson,
        )
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}

/**
 * 标题区：有 ClearLogo 用艺术字，没有才退回文字。
 *
 * Logo 高度夹在 26~64dp：ClearLogo 多是宽幅（约 800×300），按 64dp 高、72% 宽做
 * `Fit` 之后实际高度通常在 30~45dp，两侧留白由 `Fit` 自己吃掉，不会撑满一行。
 */
@Composable
private fun DetailTitleArea(
  name: String,
  logoUrl: String?,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  if (logoUrl != null) {
    Box(
      modifier = Modifier
        .fillMaxWidth(0.72f)
        .heightIn(min = 26.dp, max = 64.dp),
      contentAlignment = Alignment.Center,
    ) {
      EmbyImage(
        url = logoUrl,
        contentDescription = name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        maxWidth = 660,
      )
    }
  } else if (name.isNotBlank()) {
    Text(
      text = name,
      style = type.pageTitle,
      color = colors.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** `★ 8.6` + 分级胶囊。两颗都不存在时整行不占位。 */
@Composable
private fun RatingRow(
  rating: Double?,
  officialRating: String?,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val ratingText = officialRating?.trim()?.takeUnless { it.isBlank() }
  if (rating == null && ratingText == null) return

  val pillShape = remember { RoundedCornerShape(50) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (rating != null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Star,
          contentDescription = null,
          tint = colors.ratingAccent,
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = "%.1f".format(rating),
          style = type.itemTitle.copy(fontWeight = FontWeight.W700),
          color = colors.ratingAccent,
          maxLines = 1,
        )
      }
    }
    if (ratingText != null) {
      Box(
        modifier = Modifier
          .height(20.dp)
          .clip(pillShape)
          .background(colors.chipSurface.copy(alpha = 0.7f))
          .border(1.dp, colors.chipBorder, pillShape)
          .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = ratingText,
          style = type.badge.copy(fontSize = 10.sp),
          color = colors.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * 全宽主按钮：反相实心胶囊 + 顶部内高光 + 投影。
 *
 * 续播进度画在按钮**内部**（左侧浅色填充），这样「有进度」这件事不需要额外的进度条
 * 来解释 —— 看到一个填了一半的按钮，就是「看到一半，点它接着看」。
 */
@Composable
private fun HeroPlayButton(
  label: String,
  progress: Float?,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(50.dp)
      .shadow(elevation = 12.dp, shape = shape, spotColor = colors.glassShadow)
      .clip(shape)
      .background(colors.onSurface)
      .pressDownScale()
      .clickable(onClick = onClick),
  ) {
    if (progress != null) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .fillMaxWidth(progress.coerceIn(0f, 1f))
          .height(50.dp)
          .background(colors.surface.copy(alpha = 0.16f)),
      )
    }
    // 内高光：纯色块加一道自上而下的极淡渐变，观感从「系统按钮」回到「玻璃」
    Box(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .height(25.dp)
        .background(
          Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.12f),
            1f to Color.Transparent,
          ),
        ),
    )
    Row(
      modifier = Modifier.align(Alignment.Center),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = null,
        tint = colors.surface,
        modifier = Modifier.size(19.dp),
      )
      Text(
        text = label,
        style = type.button.copy(fontSize = 15.sp),
        color = colors.surface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * 次操作圆钮（已看 / 收藏）。
 *
 * 默认是**描边空心**，激活才填主题色：这两个都是「开关」，未激活时用实心色块会和
 * 主按钮抢重量 —— 一屏里出现三个同样重的实心块，用户分不清哪个是「播放」。
 */
@Composable
private fun CircleAction(
  icon: ImageVector,
  contentDescription: String?,
  active: Boolean,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  val colors = CineTheme.colors

  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(if (active) colors.progressFill else colors.chipSurface.copy(alpha = 0.55f))
      .then(
        if (active) {
          Modifier
        } else {
          Modifier.border(1.dp, colors.chipBorder, CircleShape)
        },
      )
      .pressDownScale()
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = if (active) colors.surface else colors.onSurfaceVariant,
      modifier = Modifier.size(19.dp),
    )
  }
}

/** 演职员：圆形头像 + 名字。出血到屏幕边缘（LazyRow 自己带 screenH 内边距） */
@Composable
private fun CastRow(
  cast: List<EmbyPerson>,
  wide: Boolean,
  avatarUrl: (EmbyPerson) -> String?,
  onOpenPerson: (EmbyPerson) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val avatar = if (wide) 72.dp else 64.dp

  LazyRow(
    contentPadding = PaddingValues(horizontal = space.screenH),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    items(cast.size) { index ->
      val person = cast[index]
      Column(
        modifier = Modifier
          .width(avatar)
          .clickable { onOpenPerson(person) },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier = Modifier
            .size(avatar)
            .clip(CircleShape)
            .background(colors.skeleton),
        ) {
          EmbyImage(
            url = avatarUrl(person),
            contentDescription = person.Name,
            modifier = Modifier.fillMaxSize(),
            maxWidth = 240,
          )
        }
        Spacer(Modifier.height(7.dp))
        Text(
          text = person.Name ?: "",
          style = type.cardMeta.copy(fontSize = 11.sp),
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** `38 分钟` / `1 小时 2 分`（剩余时长；「剩」字在文案模板里） */
private fun formatRemainingPlain(seconds: Long): String {
  if (seconds < 3600) return "${(seconds / 60).coerceAtLeast(1)} 分钟"
  val hours = seconds / 3600
  val minutes = (seconds % 3600) / 60
  return if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
}
