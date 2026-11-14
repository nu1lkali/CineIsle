package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import kotlinx.coroutines.launch

/**
 * Classic 界面的搜索页组件（v257）。
 *
 * ## 为什么要从 `EmbyHomeScreen` 里拆出来
 * 搜索页有两套状态机（空词 = 历史 + 推荐；有词 = 结果）和三块互不相干的版式，
 * 全塞在首页那个 1200 行的 Composable 里会让「哪块状态属于谁」彻底看不清。
 * 这里只放**纯展示**组件：状态仍然由 `EmbyHomeScreen` 持有（搜索逻辑一行没动）。
 *
 * ## 与 Cine 版的共同点 / 差异
 * 结构一致（返回 + 胶囊输入 + 搜索键 / 历史胶囊 / 两列推荐卡），但颜色一律读
 * `MaterialTheme.colorScheme`，不写死色值 —— Classic 这套界面必须跟着用户的主题走
 * （换主题、动态取色都要对），刻意不学设计稿里的固定紫色。
 */

/** 搜索页顶栏高度（含状态栏 inset 之外的部分），与设计稿 44dp 输入框 + 上下留白对齐 */
private val SearchBarHeight = 44.dp

private val SearchPillShape = RoundedCornerShape(50)

/**
 * 搜索页顶栏：返回 + 圆角输入框 + 搜索按钮。
 *
 * @param focusRequester 由调用方持有：进入搜索页时请求焦点（自动弹输入法），
 *   点空白处收起键盘后再点输入框仍能重新获得焦点。
 * @param onSubmit 键盘搜索键与右侧「搜索」按钮**共用**这一个回调 ——
 *   两条路径必须走同一套逻辑，否则会出现「按键盘只记历史、点按钮才真搜」的分裂。
 */
@Composable
fun EmbySearchTopBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  focusRequester: FocusRequester,
  modifier: Modifier = Modifier,
  hint: String = "搜索影片、剧集或演员…",
  /**
   * 进入搜索页是否自动聚焦。
   *
   * `false` 用在「从详情页返回」的恢复路径上：那时用户要的是继续看刚才那批结果，
   * 键盘一弹上来会把列表顶掉一半（Cine 版对这件事有同名取舍）。
   */
  autoFocus: Boolean = true,
) {
  if (autoFocus) {
    // 放在本组件内部而不是调用方：调用方那一层还没组合出输入框，
    // requestFocus 会找不到可聚焦节点而抛异常。
    LaunchedEffect(Unit) {
      runCatching { focusRequester.requestFocus() }
    }
  }

  // 「搜索」= 用户的注意力从输入框转到结果上。所以两条提交路径
  // （键盘搜索键 / 右侧搜索按钮）都先**摘掉输入框焦点**再收键盘：
  // 只 hide 不 clearFocus 时焦点还挂在输入框上，键盘随时会自己弹回来，
  // 用户会觉得「搜完了却关不掉输入法」。
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val submitAndDismiss = {
    keyboard?.hide()
    focusManager.clearFocus(force = true)
    onSubmit()
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .clickable(onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "退出搜索",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
      )
    }

    Row(
      modifier = Modifier
        .weight(1f)
        .height(SearchBarHeight)
        .clip(SearchPillShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), SearchPillShape)
        .padding(start = 14.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.Search,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
          Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = query,
          onValueChange = onQueryChange,
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
          textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
          ),
          singleLine = true,
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { submitAndDismiss() }),
        )
      }
      // 清除键：只在有内容时出现。用 Cancel（实心圆叉）而不是 Clear ——
      // 设计稿这里是一个实心圆底叉号，空心叉在深色胶囊里几乎看不见。
      if (query.isNotEmpty()) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable { onQueryChange("") },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "清空输入",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }

    TextButton(
      onClick = submitAndDismiss,
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
      Text(
        text = "搜索",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

/**
 * 搜索历史：「标题 + 清空」+ 一组可换行的胶囊。
 *
 * 三条交互都在这里落地（需求：点击直搜、长按删单条、清空要二次确认）：
 * - 点击胶囊 → [onPick]，调用方负责**填词并立即搜索**；
 * - 长按胶囊 → 删掉这一条，并给一句 Toast（没有反馈的删除会让人以为是没按到）；
 * - 右上角「清空」→ 先弹确认框。历史是本地持久化的，误触一次就得重新积累。
 *
 * 空历史不做大块空态：下面还有推荐区，占掉半屏说「你还没搜过」很浪费；
 * 这里给一行低调的提示，既优雅又不挡内容。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmbySearchHistorySection(
  history: List<String>,
  onPick: (String) -> Unit,
  onRemove: (String) -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var confirmClear by remember { mutableStateOf(false) }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("清空搜索历史？") },
      text = { Text("当前服务器下的 ${history.size} 条搜索记录会被删除，且无法恢复。") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmClear = false
            onClearAll()
          },
        ) { Text("清空") }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) { Text("取消") }
      },
    )
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.History,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = "搜索历史",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
      )
      Spacer(modifier = Modifier.weight(1f))
      if (history.isNotEmpty()) {
        TextButton(
          onClick = { confirmClear = true },
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.DeleteOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "清空",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }
    }

    if (history.isEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          // 与区标题的 History（时钟）区分开：提示语说的是「输入关键词开始检索」，
          // 配放大镜才对；两个时钟上下叠着出现会让人以为重复渲染了一遍标题。
          imageVector = Icons.Outlined.Search,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "还没有搜索记录，输入关键词开始检索",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
          modifier = Modifier.padding(start = 6.dp),
        )
      }
      return@Column
    }

    FlowRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      history.forEach { keyword ->
        SearchHistoryChip(
          keyword = keyword,
          onPick = { onPick(keyword) },
          onRemove = {
            onRemove(keyword)
            Toast.makeText(context, "已删除「$keyword」", Toast.LENGTH_SHORT).show()
          },
        )
      }
    }
  }
}

/**
 * 单条历史胶囊：关键词 + 右侧删除叉。点击检索，点叉删这一条（长按同样删）。
 *
 * ## 为什么这一版把叉放回来了
 * 之前只有长按删除，用户根本不知道能删。现在明摆一个叉，但必须守住 v256 的结论：
 * **文字区与删除区之间要有距离**，否则单字符词（`7`、`a`）整条只有几十 dp，
 * 想点词却落在叉上，历史会被越点越少。所以：
 *  - 文本区 `defaultMinSize(minWidth = 48.dp)` —— 内容再短也不塌；
 *  - 中间 1dp 发丝分隔线 + 两侧各 8dp，删除区固定 26dp 宽、贴最右；
 *  - 删除区自己吃掉点击，不会冒泡到「检索」那一层（子节点先处理手势）。
 */
@Composable
private fun SearchHistoryChip(
  keyword: String,
  onPick: () -> Unit,
  onRemove: () -> Unit,
) {
  Row(
    modifier = Modifier
      .height(34.dp)
      .clip(SearchPillShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        shape = SearchPillShape,
      )
      .clickable(onClick = onPick)
      .padding(start = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = keyword,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      // 长片名不把胶囊拉成一整行：封顶后省略，同一排才排得下三四个
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .widthIn(max = 168.dp)
        .defaultMinSize(minWidth = 48.dp)
        .padding(vertical = 8.dp),
    )
    Spacer(Modifier.width(8.dp))
    Box(
      modifier = Modifier
        .width(1.dp)
        .height(14.dp)
        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
    Box(
      modifier = Modifier
        .width(36.dp)
        .fillMaxHeight()
        .clickable(onClick = onRemove),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "删除「$keyword」",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(13.dp),
      )
    }
  }
}

/**
 * 推荐区标题：`推荐` + 右侧「换一批」。
 *
 * 图标用 AutoAwesome（与首页「猜你喜欢」同一个意象），不用火焰 ——
 * 火焰是「热门」的符号，而这里的每一条都来自本服务器真实数据，与「全站热搜」无关。
 *
 * 「换一批」与下拉刷新走**同一个回调**（四路游标轮转 + Id 去重天然给出不同内容）：
 * 一排横卡能同时看到的就两三张，与其让用户滑很远，不如一键换掉整排。
 */
@Composable
fun EmbyRecommendHeader(
  modifier: Modifier = Modifier,
  title: String = "推荐",
  /** 换一批；null = 不显示入口（例如推荐数据还在加载时调用方不想给入口） */
  onShuffle: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.AutoAwesome,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(start = 6.dp),
    )
    Spacer(modifier = Modifier.weight(1f))
    if (onShuffle != null) {
      TextButton(
        onClick = onShuffle,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(
          text = "换一批",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 推荐卡：16:9 横版封面 + 底部渐隐，标题与元信息压在图上。
 *
 * 为什么不用 [EmbyPosterCard]（2:3 竖版）：推荐区是「顺手看几部」的轻内容，
 * 竖版海报两列会把首屏填满，用户一进来就以为已经搜出结果了；
 * 16:9 横图能容纳「剧名 + 年份 · 分类」两行而不挤，与设计稿一致；
 * 且与下面搜索结果的竖版网格形成形状差异，一眼能分清「推荐」和「结果」。
 */
@Composable
fun EmbyRecommendCard(
  item: EmbyItem,
  imageUrl: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(LocalEmbyAppearance.current.corner)
  val title = item.Name?.takeIf { it.isNotBlank() } ?: "未命名"
  val meta = recommendMeta(item)
  val hasImage = !imageUrl.isNullOrBlank()

  Box(
    modifier = modifier
      .fillMaxWidth()
      // aspectRatio(16f / 9f) = 宽 / 高，高度由宽度算出来，两列自适应不用手算宽度
      .aspectRatio(16f / 9f)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .clickable(onClick = onClick),
  ) {
    EmbyImage(
      url = imageUrl,
      contentDescription = title,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
      placeholder = Icons.Filled.Movie,
    )

    if (hasImage) {
      // 底部渐隐：从 45% 处开始压暗，保证标题与元信息在任何浅色帧上都可读
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              0f to Color.Transparent,
              0.45f to Color.Transparent,
              1f to Color.Black.copy(alpha = 0.88f),
            ),
          ),
      )
    }

    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(start = 10.dp, end = 10.dp, bottom = 9.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(13.dp),
        )
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      Text(
        text = meta,
        style = MaterialTheme.typography.bodySmall,
        color = if (hasImage) {
          Color.White.copy(alpha = 0.75f)
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * 推荐卡的一行（默认两列）。
 *
 * v260 定版：v259 按「减负」试过单行横滑条，实测竖屏下横条只占一行，
 * 下面留出大片空白，观感比「挤」更难看 —— 回到两列网格，让内容自己把页面填满。
 *
 * 之所以暴露「一行」而不是「一整个网格」：搜索页整页是一个 `LazyColumn`
 * （历史 + 推荐标题 + 若干行），网格不能嵌进列表里（高度无限）。
 */
@Composable
fun EmbyRecommendRow(
  items: List<EmbyItem>,
  imageUrl: (EmbyItem) -> String?,
  onClick: (EmbyItem) -> Unit,
  modifier: Modifier = Modifier,
  columns: Int = 2,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items.forEach { item ->
      EmbyRecommendCard(
        item = item,
        imageUrl = imageUrl(item),
        onClick = { onClick(item) },
        modifier = Modifier.weight(1f),
      )
    }
    // 最后一行不满时补空位：少一个 weight，剩下那张卡会拉满整行，
    // 与其它行等高的卡片突然变宽，观感像「加载错位」
    repeat((columns - items.size).coerceAtLeast(0)) {
      Spacer(modifier = Modifier.weight(1f))
    }
  }
}

/** 推荐区加载态：两列、三行的骨架，形状与真卡片一致（16:9），落地时不跳版 */
@Composable
fun EmbyRecommendSkeleton(
  modifier: Modifier = Modifier,
  rows: Int = 3,
) {
  val shimmer = rememberShimmer()
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    repeat(rows) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        repeat(2) {
          Box(
            modifier = Modifier
              .weight(1f)
              .aspectRatio(16f / 9f)
              .clip(RoundedCornerShape(LocalEmbyAppearance.current.corner))
              .shimmer(shimmer),
          )
        }
      }
    }
  }
}

/**
 * 推荐区错误态：一句原因 + 一个重试入口。
 *
 * 需求里明确要求「网络异常时提供重试入口」—— 只写「加载失败」的文案等于让用户去重开 App。
 */
@Composable
fun EmbyRecommendError(
  message: String?,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = message ?: "推荐内容加载失败",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
      Text("重试")
    }
  }
}

/** 推荐区空态：这台服务器的收藏/最近/评分/随机都没返回内容时的一句人话 */
@Composable
fun EmbyRecommendEmpty(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Filled.AutoAwesome,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      modifier = Modifier.size(26.dp),
    )
    Text(
      text = "这台服务器上没有可推荐的影片",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      text = "媒体库里添加影片后，这里会自动出现推荐",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}


/**
 * 推荐卡的元信息：`2023 · 科幻 / 冒险`。
 *
 * 年份、分类拿不到就只写拿得到的；两者都没有才退回类型名（`电影` / `剧集`）。
 * 不写「未知」这类占位 —— 空着比一句废话干净（与 CineItemMapper 同一条原则）。
 */
private fun recommendMeta(item: EmbyItem): String {
  val year = item.ProductionYear?.toString()
  val genres = item.Genres
    .asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString(" / ")
    .takeUnless { it.isBlank() }
  val typeLabel = when (item.Type?.lowercase()) {
    "movie" -> "电影"
    "series" -> "剧集"
    "episode" -> "单集"
    "boxset" -> "合集"
    else -> item.Type?.takeIf { it.isNotBlank() }
  }
  return listOfNotNull(year, genres).joinToString(" · ").ifBlank { typeLabel.orEmpty() }
}

/**
 * 推荐卡用哪张图：**有横版剧照就用剧照，没有才退回竖版海报**。
 *
 * 之前一律按 `Backdrop` 取 URL —— 服务端没有剧照时那个 URL 也是拼得出来的
 * （tag 为空），结果就是一排 404 的空白卡。推荐列表只挑有主图的条目，
 * 但「有主图」不等于「有剧照」，所以这里必须再退一档，卡片才不会开天窗。
 */
internal fun recommendImageUrl(
  viewModel: app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel,
  server: app.marlboroadvance.mpvex.domain.emby.EmbyServer,
  item: EmbyItem,
  width: Int = 480,
): String? = if (item.BackdropImageTags.isNotEmpty()) {
  viewModel.imageUrl(server, item, "Backdrop", width)
} else {
  viewModel.imageUrl(server, item, "Primary", width)
}

/**
 * 搜索页的「空词态」：搜索历史 + 推荐网格。
 *
 * ## 上下布局
 * 搜索框 → 工具栏 → **历史 → 推荐网格**。历史在前：点一个旧词直接搜是最高频的动作。
 * v260 把 v259 的推荐横条改回两列网格：横条只占一行、下面大片留白，实测不如网格耐看；
 * 标题右侧「换一批」保留（与下拉刷新同一个回调，四路游标轮转给出不同内容）。
 *
 * ## 为什么历史和推荐在**同一个滚动容器**里
 * 两个区块各自 `verticalScroll` 会撞车（父与子抢手势，实测就是「滑不动」）。
 * 这里整页一个 `LazyColumn`：历史是一行 FlowRow，推荐是若干行两列卡片，
 * 推荐多的时候一起往上滚，历史自然会滑出屏幕。
 *
 * ## 触底续页
 * 没有「查看更多」入口，推荐靠滚动自己长出来；[SearchRecommendViewModel] 封顶后
 * 留一行「已显示全部」。
 *
 * ## 键盘收起
 * 点空白处（列表的非交互区域）收键盘。挂在最外层 Box 的 `clickable` 上而不是
 * `pointerInput` 上：子项（卡片 / 胶囊）自己的点击优先，不会被父层抢走，
 * 只有真正点到空白处才会落到这一层。
 */
@Composable
fun EmbySearchLanding(
  history: List<String>,
  onPickHistory: (String) -> Unit,
  onRemoveHistory: (String) -> Unit,
  onClearHistory: () -> Unit,
  recommendItems: List<EmbyItem>,
  recommendLoading: Boolean,
  recommendError: String?,
  recommendLoadingMore: Boolean,
  recommendEndReached: Boolean,
  imageUrl: (EmbyItem) -> String?,
  onOpenRecommend: (EmbyItem) -> Unit,
  onRetryRecommend: () -> Unit,
  onLoadMoreRecommend: () -> Unit,
  onRefresh: suspend () -> Unit,
  onDismissKeyboard: () -> Unit,
  bottomInset: Dp,
  modifier: Modifier = Modifier,
) {
  val isRefreshing = remember { mutableStateOf(false) }
  val rows = remember(recommendItems) { recommendItems.chunked(2) }
  val listState = rememberLazyListState()
  val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
  // 「换一批」与下拉刷新同一个 suspend 回调；标题行入口是普通点击，这里转一层协程
  val scope = rememberCoroutineScope()
  val shuffleRecommend: (() -> Unit)? = if (recommendItems.isNotEmpty()) {
    { scope.launch { onRefresh() } }
  } else {
    null
  }

  // 触底续页：最后两行露头就补下一页。
  // 用 snapshotFlow 而不是 derivedStateOf：后者会把首次组合的 rows 闭包住，
  // 补完一页之后判断依据还停在旧值上（只触发一次）。
  LaunchedEffect(listState, rows.size, recommendEndReached, recommendLoadingMore) {
    if (rows.isEmpty()) return@LaunchedEffect
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
      .collect { last ->
        if (!recommendEndReached && !recommendLoadingMore && last >= rows.size - 2) {
          onLoadMoreRecommend()
        }
      }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onDismissKeyboard,
      ),
  ) {
    PullRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = onRefresh,
      modifier = Modifier.fillMaxSize(),
      listState = listState,
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        item(key = "search-history") {
          EmbySearchHistorySection(
            history = history,
            onPick = onPickHistory,
            onRemove = onRemoveHistory,
            onClearAll = onClearHistory,
            modifier = Modifier.padding(bottom = 6.dp),
          )
        }

        item(key = "recommend-header") {
          EmbyRecommendHeader(
            modifier = Modifier.padding(top = 6.dp),
            onShuffle = shuffleRecommend,
          )
        }

        when {
          recommendLoading && recommendItems.isEmpty() -> item(key = "recommend-skeleton") {
            EmbyRecommendSkeleton(modifier = Modifier.padding(top = 4.dp))
          }

          recommendItems.isEmpty() && recommendError != null -> item(key = "recommend-error") {
            EmbyRecommendError(message = recommendError, onRetry = onRetryRecommend)
          }

          recommendItems.isEmpty() -> item(key = "recommend-empty") {
            EmbyRecommendEmpty()
          }

          else -> items(rows, key = { row -> row.firstOrNull()?.Id ?: row.hashCode().toString() }) { row ->
            EmbyRecommendRow(
              items = row,
              imageUrl = imageUrl,
              onClick = onOpenRecommend,
              modifier = Modifier.padding(bottom = 10.dp),
            )
          }
        }

        // 续页脚：只在真有下一页、且正在取的时候占一行 ——
        // 取完/到底都不留占位，免得历史区和推荐之间多一条莫名其妙的空白
        if (recommendLoadingMore) {
          item(key = "recommend-more") {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
              )
            }
          }
        }

        // 到底：推荐总量有上限（SearchRecommendViewModel.MAX_ITEMS），
        // 触顶后不再续页。留一行字说明「没有更多了」，否则用户会以为还在加载。
        if (recommendEndReached && recommendItems.isNotEmpty()) {
          item(key = "recommend-end") {
            Text(
              text = "已显示全部 ${recommendItems.size} 条推荐",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 8.dp),
            )
          }
        }
      }

      // 刷新失败时给一句提示，但不动列表：用户手上那批还在，滚走就看不见了
      if (recommendError != null && recommendItems.isNotEmpty()) {
        Text(
          text = "刷新失败：$recommendError",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 24.dp, start = 16.dp, end = 16.dp),
        )
      }
    }
  }
}

