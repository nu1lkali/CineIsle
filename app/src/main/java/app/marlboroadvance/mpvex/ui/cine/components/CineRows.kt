package app.marlboroadvance.mpvex.ui.cine.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CineTheme

/**
 * 手机端一屏最多露出几张（继续观看这种 16:9 卡更宽，所以更少）。
 */
private const val WIDE_ROW_MAX = 3
private const val POSTER_ROW_MAX = 4

/**
 * 分区里的横滑行。
 *
 * ## 宽屏为什么不是「更大的横滑」
 * 手机上是 `LazyRow`：353dp 可视宽只露出 2.4 张，第三张的半个边**本身就在说「还能滑」**。
 * 但宽屏（≥600dp）上 4 张海报刚好铺满内容区，露半个边就变成了「排版没对齐」。
 * 所以宽屏改成固定列数的 `Row` —— 不是两套卡片，只是两套排列。
 *
 * 多出来的条目走分区标题右侧的「全部」（打开网格页），所以宽屏不需要横向滚动。
 */
@Composable
fun CinePosterRow(
  items: List<CineMediaUi>,
  cardWidth: Dp,
  wide: Boolean,
  onClick: (CineMediaUi) -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: ((CineMediaUi) -> Unit)? = null,
) {
  val space = CineTheme.space
  if (items.isEmpty()) return

  if (wide) {
    Row(
      modifier = modifier.padding(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      items.take(POSTER_ROW_MAX).forEach { media ->
        CinePosterCard(
          media = media,
          width = cardWidth,
          onClick = { onClick(media) },
          onLongClick = onLongClick?.let { { it(media) } },
        )
      }
    }
  } else {
    LazyRow(
      modifier = modifier,
      contentPadding = PaddingValues(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      // 不传 key：同一批数据里理论上不会重复，但「随便看看」是随机接口、
      // 服务端偶尔会回重复条目 —— 撞 key 会直接抛异常，代价远大于收益
      itemsIndexed(items) { _, media ->
        CinePosterCard(
          media = media,
          width = cardWidth,
          onClick = { onClick(media) },
          onLongClick = onLongClick?.let { { it(media) } },
        )
      }
    }
  }
}

/** 继续观看的 16:9 横滑行。宽屏逻辑同 [CinePosterRow]。 */
@Composable
fun CineWideRow(
  items: List<CineMediaUi>,
  cardWidth: Dp,
  wide: Boolean,
  onClick: (CineMediaUi) -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: ((CineMediaUi) -> Unit)? = null,
) {
  val space = CineTheme.space
  if (items.isEmpty()) return

  if (wide) {
    Row(
      modifier = modifier.padding(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      items.take(WIDE_ROW_MAX).forEach { media ->
        CineWideCard(
          media = media,
          width = cardWidth,
          onClick = { onClick(media) },
          onLongClick = onLongClick?.let { { it(media) } },
        )
      }
    }
  } else {
    LazyRow(
      modifier = modifier,
      contentPadding = PaddingValues(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(space.itemGap),
    ) {
      itemsIndexed(items) { _, media ->
        CineWideCard(
          media = media,
          width = cardWidth,
          onClick = { onClick(media) },
          onLongClick = onLongClick?.let { { it(media) } },
        )
      }
    }
  }
}
