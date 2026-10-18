package app.marlboroadvance.mpvex.ui.browser.states

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.Shimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner

/**
 * 本地文件 / 网络 / 最近播放共用的骨架屏。
 *
 * 为什么不直接用 `EmbySkeletonGrid`：那套组件读的是 `LocalEmbyAppearance.corner`
 * （Emby 专属外观），列数与间距也是照 Emby 媒体库的版式定的。这三个页面属于
 * 「文件 / 连接」语境，圆角要跟随全局「卡片圆角」设置（[LocalCardCorner]），
 * 列数由各自页面决定。
 *
 * 扫光实现**不重复写**：直接复用 `EmbySkeleton.kt` 里的 [rememberShimmer] /
 * `Modifier.shimmer` —— 全 App 的骨架屏必须同一种质感、同一套动画策略
 * （详见那边关于「一份动画实例」的说明）。
 */
@Composable
fun BrowserSkeletonGrid(
  columns: Int = 3,
  ratio: Float = 1f,
  itemCount: Int = 12,
  showText: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  LazyVerticalGrid(
    columns = GridCells.Fixed(columns.coerceAtLeast(1)),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    // 骨架只是「预告」，让用户滚它没有意义，还会跟内容落地后的滚动位置打架
    userScrollEnabled = false,
  ) {
    items(itemCount) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(LocalCardCorner.current))
            .shimmer(shimmer),
        )
        if (showText) {
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonBar(shimmer = shimmer, widthFraction = 0.8f)
          Spacer(modifier = Modifier.height(4.dp))
          SkeletonBar(shimmer = shimmer, widthFraction = 0.45f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 「左图右两行文字」的骨架行，对应文件/网络列表视图的每一行。
 *
 * 是 Column 不是 LazyList（同一屏就那么几行，不需要回收）：
 * 放进 LazyColumn 当 item 时**不要**传 `fillMaxSize`。
 *
 * @param thumbWidth 缩略图宽度，应与目标页面的行内缩略图一致（列表 64dp、宽卡 160dp）
 * @param thumbRatio 缩略图宽高比（16:9 视频、1:1 正方形夹）
 */
@Composable
fun BrowserSkeletonRows(
  itemCount: Int = 8,
  thumbWidth: Dp = 64.dp,
  thumbRatio: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    repeat(itemCount) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .width(thumbWidth)
            .aspectRatio(thumbRatio)
            .clip(RoundedCornerShape(LocalCardCorner.current))
            .shimmer(shimmer),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          SkeletonBar(shimmer = shimmer, widthFraction = 0.72f)
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonBar(shimmer = shimmer, widthFraction = 0.4f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 骨架屏里的一根文字占位条。
 *
 * [shimmer] 是参数不是内部自取：同一屏几十个占位块共用一个动画实例，
 * 理由见 `EmbySkeleton.kt` 里 `SkeletonLine` 的说明。
 */
@Composable
private fun SkeletonBar(
  shimmer: Shimmer,
  widthFraction: Float,
  height: Dp = 10.dp,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth(widthFraction)
      .height(height)
      .clip(RoundedCornerShape(5.dp))
      .shimmer(shimmer),
  )
}
