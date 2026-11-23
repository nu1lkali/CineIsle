package app.marlboroadvance.mpvex.ui.subtitle.online

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 搜索结果的时间线预览浮层。
 *
 * ## 为什么是 Dialog 而不是再开一层 BottomSheet
 *
 * 它总是叠在「网络字幕」抽屉上面：一层 sheet 再套一层 sheet，两个都能被拖拽关闭，
 * 手势会互相抢。用 Dialog 明确「这是抽屉里的一个只读浮层」，也跟本文件旁边那个
 * 「删除确认」保持同一种层级表达。
 *
 * ## 但它长得像抽屉
 *
 * Dialog 默认是一个四周留白的居中卡片，看起来像「另一个应用弹了个窗」。这里用
 * `usePlatformDefaultWidth = false` 铺满窗口、内容贴底对齐、只圆上面两个角，
 * 视觉上就是一张从底边升起的抽屉；顶部给一条拖拽把手，往下拖超过阈值即关闭，
 * 上滑/回弹走 `Animatable`，与真正的 ModalBottomSheet 手感一致但**不会**产生
 * 两层 sheet 的拖拽抢手势问题。
 *
 * ## 只读、不下载
 *
 * 用户在这里做的事是「核对这条字幕对不对」：台词内容、出现时间、条目数量。
 * 看完关掉，本地库里不会多出任何文件。
 *
 * ## 高度必须与抽屉一致
 *
 * 它盖在「网络字幕」抽屉上，两者高度不一样就会出现「短面板压着长面板、后面那张
 * 从顶上露出来」的破面。所以高度取自 [ONLINE_SUBTITLE_PANEL_HEIGHT_FRACTION]
 * （与抽屉同一份常量）并加上抽屉把手的 [ONLINE_SUBTITLE_DRAG_HANDLE_HEIGHT]。
 */
@Composable
internal fun OnlineSubtitleTimelineDialog(
  preview: OnlineSubtitleViewModel.TimelinePreview,
  onClose: () -> Unit,
  onRetry: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // 初始偏移给一个足够大的值：第一帧就落在屏幕外，再从底部升上来。
  // 若从 0 开始再 snap 到屏幕外，会先闪一帧完整面板。
  val offsetY = remember { Animatable(2_400f) }
  var sheetHeightPx by remember { mutableIntStateOf(0) }
  val dismissThreshold = with(density) { 96.dp.toPx() }

  LaunchedEffect(Unit) {
    offsetY.animateTo(0f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
  }

  val settle: (Boolean) -> Unit = { dismiss ->
    scope.launch {
      if (dismiss) {
        val target = if (sheetHeightPx > 0) sheetHeightPx.toFloat() else 2_400f
        offsetY.animateTo(target, tween(durationMillis = 160, easing = FastOutSlowInEasing))
        onClose()
      } else {
        offsetY.animateTo(0f, spring(stiffness = 900f))
      }
    }
  }

  Dialog(
    onDismissRequest = onClose,
    // decorFitsSystemWindows = false：网络字幕抽屉（ModalBottomSheet）是满屏 edge-to-edge
    // 的窗口，这里必须同样是满屏，两边按同一个比例算出来的高度才可比 —— 否则本窗口
    // 先被系统栏裁掉一截，同样的 0.86f 会比抽屉矮，抽屉就会从上面露出来。
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      // 与网络字幕抽屉同高：内容那一截共用同一个比例，再加上抽屉自己的拖拽把手
      // （抽屉整体 = 把手 + 内容），这样时间线升上来正好**盖住**抽屉，不会露出后一张。
      val panelHeight = maxHeight * ONLINE_SUBTITLE_PANEL_HEIGHT_FRACTION +
        ONLINE_SUBTITLE_DRAG_HANDLE_HEIGHT
      Surface(
        // 只圆上两角：下沿与屏幕底边齐平，才像抽屉而不是浮动卡片
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
          .fillMaxWidth()
          .height(panelHeight)
          .onSizeChanged { sheetHeightPx = it.height }
          .offset { IntOffset(0, offsetY.value.roundToInt()) },
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          // ── 拖拽把手 ──
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .pointerInput(Unit) {
                detectVerticalDragGestures(
                  onDragEnd = { settle(offsetY.value > dismissThreshold) },
                  onDragCancel = { settle(false) },
                ) { change, dragAmount ->
                  change.consume()
                  scope.launch {
                    offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f))
                  }
                }
              }
              .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
            )
          }

          // ── 头部 ──
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "字幕时间线",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = preview.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
            IconButton(onClick = onClose) {
              Icon(Icons.Default.Close, contentDescription = "关闭")
            }
          }

          HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
          )

          // ── 正文 ──（底部补导航栏内边距：面板贴底，内容不该被手势条压住）
          Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
              preview.loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                      text = "正在读取字幕内容…",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(top = 10.dp),
                    )
                  }
                }
              }

              preview.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                  ) {
                    Text(
                      text = "无法预览这条字幕",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                    )
                    Text(
                      text = preview.error,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
                      Text("重试")
                    }
                  }
                }
              }

              preview.cues.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                  ) {
                    Text(
                      text = "这条字幕没有可解析的时间轴",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                    )
                    Text(
                      text = "图片字幕（PGS / VobSub）与未知格式无法列出条目。" +
                        "可以先用下方按钮下载，再到字幕实验室里用播放器试看。",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(top = 4.dp),
                    )
                  }
                }
              }

              else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                  if (preview.truncated) {
                    Text(
                      text = "该字幕条目过多，此处仅列出前 ${preview.cues.size} 条",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                  }
                  Text(
                    text = if (preview.fromLibrary) {
                      "共 ${preview.cues.size} 条 · 读取自本机已下载文件"
                    } else {
                      "共 ${preview.cues.size} 条 · 预览只读取内容，不会保存到本地"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                  )
                  LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    itemsIndexed(preview.cues) { index, cue ->
                      TimelineCueRow(index = index + 1, cue = cue)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/** 一条时间轴：序号 + 起止时间 + 台词。长台词最多 4 行，避免一条撑满整屏。 */
@Composable
private fun TimelineCueRow(
  index: Int,
  cue: SubtitleCue,
) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Text(
      text = index.toString(),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
      // 四位数序号（最多 6000 条）也放得下，不再按固定 34dp 裁掉
      modifier = Modifier.widthIn(min = 34.dp).padding(top = 2.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = cue.timeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(1.dp))
      Text(
        text = cue.text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
