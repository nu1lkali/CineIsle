package app.marlboroadvance.mpvex.ui.player.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon

/**
 * 播放器 UI 需要内核做的动作，由 Activity 实现。
 *
 * 之所以两边分开：ViewModel 不认识播放器 View（它是 View，持有会泄漏），
 * 而 Compose 也不该直接碰 [android.view.View]，中间留出这一层。
 *
 * 注：**翻页不在这里** —— 那是 VerticalPager 的事。亮度 / 音量 / 拖进度三条手势
 * 也另有一套宿主接口（[FeedGestureHost]），因为它们碰的是系统亮度与音轨，
 * 由 Activity 直接处理更稳。
 */
interface FeedPlayerController {
  fun togglePlayPause()

  fun seekTo(sec: Double)

  fun reload(url: String, resumeSec: Double)

  fun screenshot()

  fun toggleOrientation()

  fun exit()
}

/** 拖方向变化时累计切换一次正负号，用来给快进/快退选图标 */
private const val SEEK_PREVIEW_NEUTRAL_MS = -1L

@Composable
internal fun FeedScreen(
  vm: FeedViewModel,
  controller: FeedPlayerController,
  gestureUi: FeedGestureUi,
) {
  val item = vm.current
  val controlsAlpha by animateFloatAsState(
    targetValue = if (vm.controlsVisible) 1f else 0f,
    animationSpec = tween(220),
    label = "controls",
  )

  if (item == null) {
    // 空列表：Activity 入口就该拒绝启动，这里只是兜底，避免出现「 index -1 找不到条目 」
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      Text(
        text = "没有可播放的内容",
        color = Color.White,
        modifier = Modifier.align(Alignment.Center),
      )
    }
    return
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // 注：单击 / 双击的点击层**不在这里** —— 它挪进了 VerticalPager 的每一页内部
    // （见 VerticalFeedActivity.FeedVideoPage）。原因也写在那里：一层盖住全屏的
    // 兄弟节点会把 Pager 的触摸全部拦掉，而翻页必须由 Pager 原生滚动来完成。
    //
    // 本层剩下的都是「边缘控件」：顶栏、右侧工具栏、底部进度条。它们各自只在自己
    // 的尺寸范围内拦截触摸，中间那一大片画面仍然完整地留给 Pager。

    // ── 顶栏：返回 + 进度 ──
    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .statusBarsPadding()
        .padding(horizontal = 4.dp)
        .alpha(controlsAlpha),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { controller.exit() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
      }
      Column {
        Text(
          text = item.title,
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.width(220.dp),
        )
        Text(
          text = "${vm.index + 1} / ${vm.items.size}",
          color = Color.White.copy(alpha = 0.6f),
          fontSize = 12.sp,
        )
      }
    }

    // ── 右侧工具栏：收藏 / 截图 / 翻转 / 重载 ──
    Column(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = 8.dp)
        .alpha(controlsAlpha),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // 收藏：动效与 Emby 详情页是同一个组件（弹跳 → 红心径向渐变 + 外发光 →
      // 一圈红色光环 + 8 颗金色四角星扩散淡出）。它是**状态驱动**的，所以
      // toggleFavorite 的乐观更新一翻转，动效立刻开始，不用等服务器回包。
      FeedToolButton(
        icon = {
          FavoriteHeartIcon(
            isFavorite = item.isFavorite,
            isToggling = false,
            iconSize = 24.dp,
          )
        },
        onClick = { vm.toggleFavorite() },
      )
      FeedToolButton(
        icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = "截图", tint = Color.White, modifier = Modifier.size(26.dp)) },
        onClick = { controller.screenshot() },
      )
      FeedToolButton(
        icon = { Icon(Icons.Filled.ScreenRotation, contentDescription = "横竖屏", tint = Color.White, modifier = Modifier.size(26.dp)) },
        onClick = { controller.toggleOrientation() },
      )
      FeedToolButton(
        icon = { Icon(Icons.Filled.Refresh, contentDescription = "重载", tint = Color.White, modifier = Modifier.size(26.dp)) },
        onClick = { vm.requestReload() },
      )
    }

    // ── 底部：进度条 + 标题 ──
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .alpha(controlsAlpha),
    ) {
      val dur = vm.durationSec
      val ready = dur > 0.0
      // 时长未知时**不换一套控件**：原来这里会在「滑块」和「一条 3dp 细线」之间
      // 二选一，而切条时 durationSec 必然要经历一次「旧值 → 未知 → 新值」，于是
      // 进度条每次都塌下去再撑起来 —— 一缩一放就是切条时那下闪烁。
      // 现在整条进度条恒在，只有数值与可拖性随就绪状态变，形态永远不跳。
      Row(verticalAlignment = Alignment.CenterVertically) {
        // 拖进度期间用本地值顶住：内核的位置上报会跟大面积拖动打架，
        // 表现为「手指刚松开，滑块又弹回原处」。松手才真正下发 seek。
        var scrubbing by remember { mutableStateOf<Float?>(null) }
        Text(
          text = if (ready) {
            fmtTime(scrubbing?.let { it * dur.toFloat() }?.toDouble() ?: vm.positionSec)
          } else {
            "--:--"
          },
          color = Color.White,
          fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Slider(
          value = scrubbing ?: if (ready) (vm.positionSec / dur).toFloat().coerceIn(0f, 1f) else 0f,
          onValueChange = { if (ready) scrubbing = it },
          onValueChangeFinished = {
            if (ready) scrubbing?.let { controller.seekTo((it * dur.toFloat()).toDouble()) }
            scrubbing = null
          },
          modifier = Modifier
            .weight(1f)
            .height(20.dp),
          colors = SliderDefaults.colors(
            thumbColor = Color(0xFFFE2C55),
            activeTrackColor = Color(0xFFFE2C55),
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            disabledThumbColor = Color(0xFFFE2C55),
            disabledActiveTrackColor = Color(0xFFFE2C55),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.25f),
          ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = if (ready) fmtTime(dur) else "--:--", color = Color.White, fontSize = 11.sp)
      }
      Spacer(modifier = Modifier.height(6.dp))
    }

    // ── 加载中 / 错误 / 暂停的中心态 ──
    val err = vm.errorOf(vm.index)
    if (err != null) {
      Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(text = err, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          onClick = { vm.requestReload() },
          shape = RoundedCornerShape(20),
          color = Color.White.copy(alpha = 0.16f),
        ) {
          Text(
            text = "重试",
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          )
        }
      }
    } else {
      // 转圈**淡出**，不做硬切。
      // 判据已经是「帧真的画到屏幕上」了（见 ExoPlayerPool.frameOnScreen），所以这里
      // 主要是把最后那一点点余量抹平：淡出期间画面从转圈背后浮上来，观感上就是「无缝」。
      // 硬切的话，哪怕只差一两个 vsync 也会被看成闪了一下。
      AnimatedVisibility(
        visible = !vm.buffered,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(260)),
        modifier = Modifier.align(Alignment.Center),
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(44.dp),
          color = Color.White.copy(alpha = 0.75f),
          strokeWidth = 3.dp,
        )
      }

      // 暂停按钮同样淡入淡出：它和转圈共用一个位置，硬切就会在暂停/播放时闪一下
      AnimatedVisibility(
        visible = vm.buffered && vm.paused,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.Center),
      ) {
        Surface(
          onClick = { controller.togglePlayPause() },
          modifier = Modifier.size(64.dp),
          shape = CircleShape,
          color = Color.Black.copy(alpha = 0.35f),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Filled.PlayArrow,
              contentDescription = "播放",
              tint = Color.White.copy(alpha = 0.9f),
              modifier = Modifier.size(36.dp),
            )
          }
        }
      }
    }

    // ── 亮度 / 音量指示条 ──
    // 各自贴在自己所代表的那一侧：亮度在左、音量在右。原来两个都画在左边，
    // 调音量时指示条却出现在屏幕左侧，用户没法从它确认自己摸到的是哪条带子。
    // 往屏内挪 64dp 是为了不被调节用的拇指压住 —— 贴边 20dp 时正好落在拇指底下。
    gestureUi.indicator?.let { indicator ->
      val isBrightness = indicator.kind == VerticalZone.BRIGHTNESS
      VerticalAdjustIndicator(
        indicator = indicator,
        modifier = if (isBrightness) {
          Modifier.align(Alignment.CenterStart).padding(start = 64.dp)
        } else {
          Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)
        },
        tint = if (isBrightness) Color(0xFFFFC043) else Color(0xFF54A9FF),
      )
    }

    // ── 拖动进度的预览 ──
    val previewMs = gestureUi.seekTargetMs
    if (previewMs != SEEK_PREVIEW_NEUTRAL_MS) {
      SeekPreviewCard(
        targetMs = previewMs,
        totalMs = (vm.durationSec * 1000.0).toLong().coerceAtLeast(0L),
        startMs = gestureUi.seekStartMs,
        modifier = Modifier.align(Alignment.Center),
      )
    }

    // ── 阅后即焚的提示条 ──
    vm.hint?.let { text ->
      if (text.isNotBlank()) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 72.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(16),
            color = Color.Black.copy(alpha = 0.6f),
          ) {
            Text(
              text = text,
              color = Color.White.copy(alpha = 0.85f),
              fontSize = 12.sp,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
        }
      }
    }

    // ── 已到尽头 ──
    if (!vm.hasNext && vm.index == vm.items.lastIndex && vm.buffered) {
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxHeight(0.14f)
          .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "已经是最后一条了",
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
        )
      }
    }
  }
}

/**
 * 亮度 / 音量的竖向指示条：图标 + 竖条 + 百分比。
 */
@Composable
private fun VerticalAdjustIndicator(
  indicator: FeedGestureUi.Indicator,
  modifier: Modifier = Modifier,
  tint: Color,
) {
  Column(
    modifier = modifier.width(56.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      shape = CircleShape,
      color = Color.Black.copy(alpha = 0.55f),
      modifier = Modifier.size(40.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = if (indicator.kind == VerticalZone.BRIGHTNESS) {
            Icons.Filled.BrightnessHigh
          } else {
            Icons.Filled.VolumeUp
          },
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(22.dp),
        )
      }
    }
    Box(
      modifier = Modifier
        .width(8.dp)
        .height(96.dp)
        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height((96 * indicator.fraction).dp.coerceAtLeast(4.dp))
          .background(tint, RoundedCornerShape(50)),
      )
    }
    Text(
      text = "${(indicator.fraction * 100).toInt()}%",
      color = Color.White,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    )
  }
}

/**
 * 拖动进度时的中央预览：目标时间点、相对起点的偏移、总时长。
 */
@Composable
private fun SeekPreviewCard(
  targetMs: Long,
  totalMs: Long,
  startMs: Long,
  modifier: Modifier = Modifier,
) {
  val deltaMs = targetMs - startMs
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16),
    color = Color.Black.copy(alpha = 0.72f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (deltaMs >= 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(22.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = "${fmtTime(targetMs / 1000.0)} / ${fmtTime(totalMs / 1000.0)}",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = if (deltaMs >= 0) {
          "+${fmtTime(deltaMs / 1000.0)}"
        } else {
          "-${fmtTime(-deltaMs / 1000.0)}"
        },
        color = Color(0xFFFE2C55),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

@Composable
private fun FeedToolButton(
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = Color.Black.copy(alpha = 0.35f),
    modifier = Modifier.size(48.dp),
  ) {
    Box(contentAlignment = Alignment.Center) { icon() }
  }
}

private fun fmtTime(sec: Double): String {
  val total = sec.toLong().coerceAtLeast(0L)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) {
    "%d:%02d:%02d".format(h, m, s)
  } else {
    "%02d:%02d".format(m, s)
  }
}
