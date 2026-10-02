package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * 媒体库 / 收藏页加载时的**骨架屏**：直接按目标版式画出灰块。
 *
 * 取代居中的转圈 —— 转圈只说明「在加载」，骨架屏顺带把「待会儿会出现什么」也先摆出来，
 * 列表真正落下来时不会整屏跳变，观感上更接近「内容本来就在、只是还没显影」。
 *
 * @param columns 每行几个，应当与列表实际列数一致，落下来时才不会位移
 * @param ratio 卡片宽高比（海报 3:4、背景图 16:9…）
 * @param itemCount 占位块数量，默认刚好铺满一屏多一点
 */
@Composable
fun EmbySkeletonGrid(
  columns: Int = 3,
  ratio: Float = 3f / 4f,
  itemCount: Int = 12,
  showTextLines: Boolean = true,
) {
  val shimmer = rememberShimmerBrush()
  LazyVerticalGrid(
    columns = GridCells.Fixed(columns.coerceAtLeast(1)),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    // 骨架屏只是「预告」，让用户滚它没有意义，还会跟前一屏的滚动位置打架
    userScrollEnabled = false,
  ) {
    items(itemCount) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(EMBY_CARD_CORNER))
            .background(shimmer),
        )
        if (showTextLines) {
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(widthFraction = 0.78f)
          Spacer(modifier = Modifier.height(4.dp))
          SkeletonLine(widthFraction = 0.48f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 紧凑列表的骨架屏：左边一块小海报 + 右边两行文字，规格与 [EmbyCompactRow] 对齐。
 */
@Composable
fun EmbySkeletonList(itemCount: Int = 8) {
  val shimmer = rememberShimmerBrush()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    repeat(itemCount) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .width(52.dp)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(6.dp))
            .background(shimmer),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          SkeletonLine(widthFraction = 0.7f)
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(widthFraction = 0.42f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 首页骨架：几组「小标题 + 一排宽卡」，规格与首页的横滑行一致。
 *
 * 首页是「媒体库 / 继续观看 / 最新加入」若干组横滑行拼起来的，骨架也按这个结构摆，
 * 数据落地时才不会整屏跳变。
 */
@Composable
fun EmbySkeletonHome(rowCount: Int = 3) {
  val shimmer = rememberShimmerBrush()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    repeat(rowCount) {
      Column {
        Box(
          modifier = Modifier
            .padding(horizontal = 16.dp)
            .width(96.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(shimmer),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // 用 weight 而不是固定宽度：窄屏上也不会把第三张挤到屏幕外面
          repeat(3) {
            Column(modifier = Modifier.weight(1f)) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .aspectRatio(16f / 9f)
                  .clip(RoundedCornerShape(EMBY_CARD_CORNER))
                  .background(shimmer),
              )
              Spacer(modifier = Modifier.height(6.dp))
              SkeletonLine(widthFraction = 0.82f)
            }
          }
        }
      }
    }
  }
}

/**
 * 播放历史骨架：一排「继续观看」宽卡 + 几条播放历史列表行。
 * 与 EmbyHistoryScreen 的真实结构一一对应（宽卡 16:9 + ListItem 行）。
 */
@Composable
fun EmbySkeletonHistory() {
  val shimmer = rememberShimmerBrush()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(vertical = 10.dp),
  ) {
    SkeletonSectionTitle(shimmer)
    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      repeat(3) {
        Column(modifier = Modifier.weight(1f)) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .clip(RoundedCornerShape(EMBY_CARD_CORNER))
              .background(shimmer),
          )
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(widthFraction = 0.85f)
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))
    SkeletonSectionTitle(shimmer)
    Spacer(modifier = Modifier.height(6.dp))
    repeat(6) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .width(56.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(6.dp))
            .background(shimmer),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          SkeletonLine(widthFraction = 0.6f)
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(widthFraction = 0.35f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 详情页骨架：顶部大图 + 标题/副标题 + 一排按钮 + 简介几行 + 一排推荐卡。
 *
 * 详情页原来是整屏居中的一圈转圈：条目越大、网络越慢，白屏时间越长。
 * 按真版式先摆好，用户一眼就知道「页面已经开了，内容在路上」。
 */
@Composable
fun EmbySkeletonDetail() {
  val shimmer = rememberShimmerBrush()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
  ) {
    // 顶部背景大图
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .background(shimmer),
    )
    Column(modifier = Modifier.padding(16.dp)) {
      SkeletonLine(widthFraction = 0.58f, height = 18.dp)
      Spacer(modifier = Modifier.height(10.dp))
      SkeletonLine(widthFraction = 0.34f, height = 10.dp)

      Spacer(modifier = Modifier.height(16.dp))
      // 播放 / 下载 / 收藏 那一排动作键
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
          Box(
            modifier = Modifier
              .weight(1f)
              .height(42.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(shimmer),
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))
      repeat(3) { index ->
        SkeletonLine(widthFraction = if (index == 2) 0.62f else 1f)
        Spacer(modifier = Modifier.height(8.dp))
      }

      Spacer(modifier = Modifier.height(14.dp))
      Box(
        modifier = Modifier
          .width(110.dp)
          .height(16.dp)
          .clip(RoundedCornerShape(5.dp))
          .background(shimmer),
      )
      Spacer(modifier = Modifier.height(12.dp))
      // 「同类型推荐」那排海报
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
          Box(
            modifier = Modifier
              .weight(1f)
              .aspectRatio(3f / 4f)
              .clip(RoundedCornerShape(EMBY_CARD_CORNER))
              .background(shimmer),
          )
        }
      }
    }
  }
}

/** 骨架屏里的分组小标题 */
@Composable
private fun SkeletonSectionTitle(shimmer: Brush) {
  Box(
    modifier = Modifier
      .padding(horizontal = 16.dp)
      .width(96.dp)
      .height(16.dp)
      .clip(RoundedCornerShape(5.dp))
      .background(shimmer),
  )
}

/** 骨架屏里的一根「文字占位条」 */
@Composable
private fun SkeletonLine(
  widthFraction: Float,
  height: androidx.compose.ui.unit.Dp = 10.dp,
) {
  val shimmer = rememberShimmerBrush()
  Box(
    modifier = Modifier
      .fillMaxWidth(widthFraction)
      .height(height)
      .clip(RoundedCornerShape(5.dp))
      .background(shimmer),
  )
}

/**
 * 整屏共用的一条扫光渐变。
 *
 * 用 Brush 而不是给每个灰块单独做动画：块的着色只是一次 `background(brush)`，
 * 动画本身只有一份（挂在 rememberInfiniteTransition 上），几十个占位块也不会发热。
 */
@Composable
private fun rememberShimmerBrush(): Brush {
  val base = MaterialTheme.colorScheme.surfaceContainerHighest
  val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
  val transition = rememberInfiniteTransition(label = "emby_skeleton")
  val x by transition.animateFloat(
    initialValue = -600f,
    targetValue = 1400f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1300, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "emby_skeleton_x",
  )
  return Brush.linearGradient(
    colors = listOf(base, highlight, base),
    start = Offset(x, 0f),
    end = Offset(x + 420f, 0f),
  )
}
