package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Emby 模块的骨架屏：加载时按目标版式甩出灰块。
 *
 * 取代居中的转圈 —— 转圈只说明「在加载」，骨架屏顺带把「待会儿会出现什么」也先摆出来，
 * 列表真正落下来时不会整屏跳变，观感上更接近「内容本来就在、只是还没显影」。
 *
 * @param columns 每行几个，应当与列表实际列数一致，落下来时才不会位移
 * @param ratio 卡片宽高比（海报 3:4、背景图 16:9…）
 * @param itemCount 占位块数量，默认刚好铺满一屏多一点
 * @param modifier 传给内部列表。Scaffold 的 `innerPadding` 必须靠它传进来，
 *   否则骨架会顶到顶栏/底栏底下（`MainScreen` 忽略 innerPadding 的老坑，见 ui-patterns）
 */
@Composable
fun EmbySkeletonGrid(
  columns: Int = 3,
  ratio: Float = 3f / 4f,
  itemCount: Int = 12,
  showTextLines: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  LazyVerticalGrid(
    columns = GridCells.Fixed(columns.coerceAtLeast(1)),
    modifier = modifier.fillMaxSize(),
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
            .clip(RoundedCornerShape(LocalEmbyAppearance.current.corner))
            .shimmer(shimmer),
        )
        if (showTextLines) {
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.78f)
          Spacer(modifier = Modifier.height(4.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.48f, height = 9.dp)
        }
      }
    }
  }
}

/**
 * 紧凑列表的骨架屏：左边一块小海报 + 右边两行文字，规格与 `EmbyCompactRow` 对齐。
 *
 * @param modifier 同 [EmbySkeletonGrid]；这个组件是 Column 不是 LazyList，
 *   放进 LazyColumn 里当 item 时**不要**传 `fillMaxSize`（会顶掉整个列表高度）
 */
@Composable
fun EmbySkeletonList(
  itemCount: Int = 8,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier
      .fillMaxWidth()
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
            .shimmer(shimmer),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          SkeletonLine(shimmer = shimmer, widthFraction = 0.7f)
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.42f, height = 9.dp)
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
 *
 * @param cardRatio 卡片宽高比。首页横滑行用的是 16:9 宽卡；
 *   推荐 / 即混页那种竖版海报行要传 `3f/4f`，否则骨架和落地的卡片形状不一样
 * @param itemPerRow 每行几张，用 weight 均分
 */
@Composable
fun EmbySkeletonHome(
  rowCount: Int = 3,
  cardRatio: Float = 16f / 9f,
  itemPerRow: Int = 3,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(vertical = 10.dp),
  ) {
    // 段间距由 EmbySkeletonSection 自己的 paddingBottom 控制（末段为 0），
    // 这里不再叠 Arrangement.spacedBy，否则会变成两倍间距
    repeat(rowCount) {
      EmbySkeletonSection(
        shimmer = shimmer,
        cardRatio = cardRatio,
        itemPerRow = itemPerRow,
        modifier = Modifier.padding(bottom = if (it == rowCount - 1) 0.dp else 18.dp),
      )
    }
  }
}

/**
 * 播放历史骨架：一排「继续观看」宽卡 + 几条历史列表行。
 * 与 `EmbyHistoryScreen` 的真实结构一一对应（宽卡 16:9 + ListItem 行）。
 */
@Composable
fun EmbySkeletonHistory(modifier: Modifier = Modifier) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier
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
              .clip(RoundedCornerShape(LocalEmbyAppearance.current.corner))
              .shimmer(shimmer),
          )
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.85f)
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
            .shimmer(shimmer),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          SkeletonLine(shimmer = shimmer, widthFraction = 0.6f)
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.35f, height = 9.dp)
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
fun EmbySkeletonDetail(modifier: Modifier = Modifier) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
  ) {
    // 顶部背景大图
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .shimmer(shimmer),
    )
    Column(modifier = Modifier.padding(16.dp)) {
      SkeletonLine(shimmer = shimmer, widthFraction = 0.58f, height = 18.dp)
      Spacer(modifier = Modifier.height(10.dp))
      SkeletonLine(shimmer = shimmer, widthFraction = 0.34f, height = 10.dp)

      Spacer(modifier = Modifier.height(16.dp))
      // 播放 / 下载 / 收藏 那一排动作键
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
          Box(
            modifier = Modifier
              .weight(1f)
              .height(42.dp)
              .clip(RoundedCornerShape(12.dp))
              .shimmer(shimmer),
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))
      repeat(3) { index ->
        SkeletonLine(shimmer = shimmer, widthFraction = if (index == 2) 0.62f else 1f)
        Spacer(modifier = Modifier.height(8.dp))
      }

      Spacer(modifier = Modifier.height(14.dp))
      Box(
        modifier = Modifier
          .width(110.dp)
          .height(16.dp)
          .clip(RoundedCornerShape(5.dp))
          .shimmer(shimmer),
      )
      Spacer(modifier = Modifier.height(12.dp))
      // 「同类型推荐」那排海报
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
          Box(
            modifier = Modifier
              .weight(1f)
              .aspectRatio(3f / 4f)
              .clip(RoundedCornerShape(LocalEmbyAppearance.current.corner))
              .shimmer(shimmer),
          )
        }
      }
    }
  }
}

/**
 * 单个分组的骨架：「小标题 + 一排卡片」，高度只占这一段。
 *
 * 和 [EmbySkeletonHome] 的区别是**不自带滚动** —— 后者是整屏组件，塞进 `LazyColumn`
 * 会因为嵌套同向滚动直接抛异常。即混/推荐这类「本身就是一列分组」的页面要的是这个。
 *
 * @param cardRatio 卡片宽高比，16:9 宽卡传默认值，竖版海报传 `3f/4f`
 * @param itemPerRow 这一段摆几张
 */
@Composable
fun EmbySkeletonSection(
  cardRatio: Float = 16f / 9f,
  itemPerRow: Int = 3,
  modifier: Modifier = Modifier,
  shimmer: Shimmer = rememberShimmer(),
) {
  Column(modifier = modifier.fillMaxWidth()) {
    SkeletonSectionTitle(shimmer)
    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      repeat(itemPerRow.coerceAtLeast(1)) {
        Column(modifier = Modifier.weight(1f)) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .aspectRatio(cardRatio)
              .clip(RoundedCornerShape(LocalEmbyAppearance.current.corner))
              .shimmer(shimmer),
          )
          Spacer(modifier = Modifier.height(6.dp))
          SkeletonLine(shimmer = shimmer, widthFraction = 0.82f)
        }
      }
    }
  }
}

/**
 * 一组「行内文字占位」，用于弹窗/底部面板这类小容器。
 *
 * 底部面板（字幕搜索、播放列表选择…）空间小，上 [EmbySkeletonGrid] 会溢出、
 * 上 [EmbySkeletonList] 又太重，这里给一个刚好合身的高度。
 *
 * @param lineCount 几行
 * @param height 每行高度
 */
@Composable
fun EmbySkeletonLines(
  lineCount: Int = 3,
  height: Dp = 12.dp,
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    repeat(lineCount.coerceAtLeast(1)) { index ->
      // 末行短一点，模拟真实文本的参差感
      SkeletonLine(
        shimmer = shimmer,
        widthFraction = if (index == lineCount - 1) 0.55f else 1f,
        height = height,
      )
    }
  }
}

/** 骨架屏里的分组小标题 */
@Composable
private fun SkeletonSectionTitle(shimmer: Shimmer) {
  Box(
    modifier = Modifier
      .padding(horizontal = 16.dp)
      .width(96.dp)
      .height(16.dp)
      .clip(RoundedCornerShape(5.dp))
      .shimmer(shimmer),
  )
}

/**
 * 骨架屏里的一根「文字占位条」。
 *
 * [shimmer] 是**参数**而不是内部自己调 `rememberShimmer()`：
 * 早期版本在函数体里调，看着「每个组件都独立」，实际上
 * `EmbySkeletonGrid` 12 项 × 2 行 = **24 个独立的 rememberInfiniteTransition**，
 * 每个都在每帧驱动一次动画。同一屏几十个无限动画在中低端机上是实打实的发热源。
 * 现在由父级取一份传下来，整屏共用一个动画实例。
 */
@Composable
private fun SkeletonLine(
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

/**
 * 整屏共用的一条扫光渐变。
 *
 * ## 为什么不是「`rememberShimmerBrush()` 每次组合返回一个新Brush」
 *
 * 那样做的话，动画值是在**组合期**被读出来的（`animateFloat` 的 `by` 委托），
 * 于是每一帧都会：产出一个新 `Brush` → 让所有拿着它的占位块**整棵重组**。
 * 24 个独立动画是降下来了，但重组开销原封不动 —— 而骨架屏恰恰是最不该
 * 重组的地方（它自己就是「正在重组」的视觉信号）。
 *
 * 可见性必须是 public：它是 public 的 [EmbySkeletonSection] 的默认参数来源，
 * 可见性比宿主函数低会直接编译失败。
 *
 * 现在改成：动画值存进 [Shimmer]，由 [shimmer] 在**绘制期**读取。
 * `drawBehind` 的 lambda 属于 draw scope，读动画只让这一项**重绘**，
 * 不触发重组。父级把同一个 [Shimmer] 往下传，全屏共用一份动画状态。
 */
@Composable
fun rememberShimmer(): Shimmer {
  val base = MaterialTheme.colorScheme.surfaceContainerHighest
  val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
  val transition = rememberInfiniteTransition(label = "emby_skeleton")
  val x by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1300, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "emby_skeleton_x",
  )
  return remember(base, highlight) { Shimmer(base, highlight) { x } }
}

/**
 * 一份扫光参数。[x] 是 0f..1f 的进度，在**绘制期**被读，所以它是 `() -> Float`
 * 而不是 `Float` —— 后者会强迫在组合期取值，正是上面要避开的事。
 *
 * 可见性必须是 public（不是 private 也不是 internal）：[EmbySkeletonSection] 是
 * public 函数且把它当参数类型，Kotlin 不允许 public 函数暴露比它可见性更低/types 更低的类型
 * —— private 和 internal 都会报 "exposes its ... parameter type"。
 */
class Shimmer(
  private val base: Color,
  private val highlight: Color,
  private val x: () -> Float,
) {
  /**
   * @param size 当前占位块的尺寸。扫光带按它换算，而不是写死像素 ——
   *   写死的话 3x 屏上扫不到、1x 屏上大半时间扫不到块内。
   */
  fun brushFor(size: Size): Brush {
    val band = size.width * 0.6f
    val travel = size.width + band
    val head = x() * travel - band
    return Brush.linearGradient(
      colors = listOf(base, highlight, base),
      start = Offset(head, 0f),
      end = Offset(head + band, 0f),
    )
  }
}

/**
 * 用扫光渐变填背景。读动画发生在绘制阶段，所以只失效 draw，不失效组合。
 *
 * 等价于 `background(Brush)`，但那个 Brush 每帧都不一样。
 */
// ?? 刻意是 public：照片链路（ui/browser/emby/photo）的「加载中骨架屏」也要用这套扫光，
// 那边不在同一个包里。跨包复用比在 photo 包里另写一份更好 —— 扫光参数只有一处定义，
// 全 App 的骨架屏才是同一种质感（coding.md：别在各处复制同一件事的实现）。
fun Modifier.shimmer(shimmer: Shimmer): Modifier =
  this.drawBehind { drawRect(shimmer.brushFor(size)) }
