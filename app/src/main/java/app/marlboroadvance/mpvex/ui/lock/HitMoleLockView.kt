package app.marlboroadvance.mpvex.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import app.marlboroadvance.mpvex.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sin

// ────────────────────────────────────────────────────────────────────────
// 时序常量：与 docs/design/hit-mole-lock.html 里的数值一一对应，
// 改手感先改原型，再同步到这里。
// ────────────────────────────────────────────────────────────────────────

/** 升起 / 收回。 */
private const val RISE_MS = 120
private const val RETRACT_MS = 120

/** 落锤四段：入场 / 下落 / 定格 / 回弹，合计 290ms。 */
private const val SWING_IN_MS = 90
private const val SWING_DOWN_MS = 70
private const val SWING_HOLD_MS = 40
private const val SWING_OUT_MS = 90

/** 落锤角度（度）。轴心 (75, 96)，见 MoleArt.drawMoleHammer。 */
private const val HAMMER_START = 8f
private const val HAMMER_RAISED = -6f
private const val HAMMER_HIT = -26f
private const val HAMMER_REST = -12f

/** 命中标记（勾）的停留时长。洞可复用，所以它只能是瞬时的。 */
private const val MARK_MS = 600L

/** 全部敲完到真正解锁之间的停顿。 */
private const val COMPLETE_MS = 320L

/** 首只延迟：一进来还没看清界面就出鼠，容易白漏一只。 */
private const val FIRST_DELAY_MS = 420L

/**
 * 打地鼠解锁控件（3×3 九宫格）。
 *
 * ## 定位
 *
 * 这不是凭证校验，是**技能完成** —— 没有密码，任何看得见屏幕的人都能过。
 * 它的作用是降低误入（儿童误点、旁人随手打开）并改善启动手感，不提供访问控制。
 * 界面里三处文案必须都出现（方式说明 / 确认弹窗 / 本控件底部），少一处
 * 用户就可能在「以为有密码」的前提下把它当锁用。
 *
 * ## 为什么不设失败
 *
 * 这个模式没有备用凭证：用户选它就没设过 PIN。一旦游戏能失败，就存在
 * 「永远进不去」的路径（手抖、屏幕不灵敏、孩子抢手机乱点）。所以漏掉不扣分、
 * 敲错不扣分、没有时限 —— 进度单调不减，必然可达，也就不需要跳过后门。
 *
 * ## 时序分工
 *
 * 时序判定全部交给 [HitMoleGameState]（纯 Kotlin，可单测），本控件只负责
 * 「把状态画出来」。落锤的四段动画用 `Animatable.animateTo` 顺序串起来 ——
 * `animateTo` 会挂起到动画结束，所以「锤头到位」天然就是这四段之间的那个点，
 * 不需要额外的定时器去对齐。命中计数（[HitMoleGameState.commitHit]）就放在
 * 这个位置上，与压扁、冲击线、震动同帧发生。
 */
@Composable
internal fun HitMoleUnlock(
  target: Int,
  onUnlocked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = molePalette(isSystemInDarkTheme())
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()

  val game = remember(target) { HitMoleGameState(target) }
  val taps = remember(target) { Channel<Int>(Channel.UNLIMITED) }

  var activeCell by remember(target) { mutableIntStateOf(-1) }
  var rising by remember(target) { mutableStateOf(false) }
  var shownHits by remember(target) { mutableIntStateOf(0) }
  var hammerCell by remember(target) { mutableIntStateOf(-1) }
  var shakeCell by remember(target) { mutableIntStateOf(-1) }
  var marks by remember(target) { mutableStateOf<Map<Int, Long>>(emptyMap()) }
  val markSeq = remember(target) { longArrayOf(0L) }

  val hammerAngle = remember(target) { Animatable(HAMMER_START) }
  val hammerAlpha = remember(target) { Animatable(0f) }
  val squash = remember(target) { Animatable(0f) }
  val impact = remember(target) { Animatable(0f) }
  val shake = remember(target) { Animatable(0f) }
  val cry = remember(target) { Animatable(0f) }

  val rise by animateFloatAsState(
    targetValue = if (rising) 0f else 1f,
    animationSpec = tween(RISE_MS, easing = FastOutSlowInEasing),
    label = "mole-rise",
  )

  fun mark(cell: Int) {
    val token = markSeq[0]++
    marks = marks + (cell to token)
    scope.launch {
      delay(MARK_MS)
      if (marks[cell] == token) marks = marks - cell
    }
  }

  LaunchedEffect(target) {
    var wait = FIRST_DELAY_MS
    while (!game.isComplete) {
      delay(wait)
      // 丢掉上一轮积压的点击（落锤期间用户可能还在点）
      while (taps.tryReceive().isSuccess) Unit

      val cell = game.nextSpawn()
      activeCell = cell
      // 上一只的哭脸必须清掉，否则新的一只一出洞就是哭的
      cry.snapTo(0f)
      rising = true
      delay(RISE_MS.toLong())

      val hit = withTimeoutOrNull(game.holdMs.toLong()) {
        var result = false
        while (true) {
          val tapped = taps.receive()
          when (game.tap(tapped)) {
            HitMoleTap.HIT -> {
              result = true
              break
            }
            HitMoleTap.MISS -> {
              shakeCell = tapped
              scope.launch {
                shake.snapTo(1f)
                shake.animateTo(0f, tween(200, easing = LinearEasing))
              }
            }
            HitMoleTap.IGNORED -> Unit
          }
        }
        result
      }

      if (hit == true) {
        hammerCell = cell
        hammerAlpha.snapTo(1f)
        hammerAngle.snapTo(HAMMER_START)
        hammerAngle.animateTo(HAMMER_RAISED, tween(SWING_IN_MS, easing = FastOutSlowInEasing))
        hammerAngle.animateTo(
          HAMMER_HIT,
          tween(SWING_DOWN_MS, easing = CubicBezierEasing(0.45f, 0f, 0.95f, 1f)),
        )

        // ── 落点帧：压扁、冲击线、震动、进度 +1 全部在这一刻 ──
        game.commitHit()
        shownHits = game.hits
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
          squash.snapTo(1f)
          squash.animateTo(0f, tween(110, delayMillis = 40))
        }
        scope.launch {
          impact.snapTo(0f)
          impact.animateTo(1f, tween(140, easing = LinearEasing))
        }
        // 被打哭：直接从 0.4 起步，眼睛「啪」地眯起来；之后 0.4→1 同时当泪珠下落的时间轴
        scope.launch {
          cry.snapTo(0.4f)
          cry.animateTo(1f, tween(320, easing = LinearEasing))
        }

        delay(SWING_HOLD_MS.toLong())
        hammerAngle.animateTo(HAMMER_REST, tween(SWING_OUT_MS, easing = LinearOutSlowInEasing))
        hammerAlpha.animateTo(0f, tween(60, easing = LinearEasing))
        hammerCell = -1

        rising = false
        game.finishHit()

        if (game.isComplete) {
          mark(cell)
          // 全部格子转勾，停顿一拍再放行
          marks = (0 until HitMoleGameState.CELL_COUNT).associateWith { markSeq[0]++ }
          delay(COMPLETE_MS)
          onUnlocked()
          return@LaunchedEffect
        }
        mark(cell)
        wait = game.gapMs.toLong().coerceAtLeast(RETRACT_MS.toLong())
      } else {
        game.onTimeout()
        rising = false
        wait = game.gapMs.toLong().coerceAtLeast(RETRACT_MS.toLong())
      }
    }
  }

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // 进度：几段等宽条，实心 = 已敲中
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      repeat(target) { index ->
        Box(
          modifier = Modifier
            .weight(1f)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (index < shownHits) palette.grassDim else palette.cell),
        )
      }
    }

    Spacer(Modifier.height(16.dp))

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      repeat(3) { row ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
          repeat(3) { col ->
            val index = row * 3 + col
            MoleCell(
              modifier = Modifier.weight(1f),
              palette = palette,
              // 每格一片自己的草：种子取格子下标，所以九格各不相同，
              // 但同一格每次重组都是同一片（不能用现取的随机数，否则每帧都在抖）
              tufts = remember(index) { grassTufts(index * 31 + 7) },
              active = index == activeCell && rising,
              showMole = index == activeCell,
              riseOffset = rise * 74f,
              squash = if (index == activeCell) squash.value else 0f,
              cry = if (index == activeCell) cry.value else 0f,
              hammerAngle = hammerAngle.value,
              hammerAlpha = if (index == hammerCell) hammerAlpha.value else 0f,
              impact = if (index == hammerCell) impact.value else 0f,
              shakeX = if (index == shakeCell) sin((1f - shake.value) * 18f) * 3f * shake.value else 0f,
              marked = marks.containsKey(index),
              onClick = { taps.trySend(index) },
            )
          }
        }
      }
    }

    Spacer(Modifier.height(16.dp))

    Text(
      text = stringResource(R.string.app_lock_hit_mole_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
  }
}

/** 单个格子：底面 + 地面层 + 地鼠 + 前草 + 木槌 + 冲击线 + 命中标记。 */
@Composable
private fun MoleCell(
  palette: MolePalette,
  tufts: List<GrassTuft>,
  active: Boolean,
  showMole: Boolean,
  riseOffset: Float,
  squash: Float,
  cry: Float,
  hammerAngle: Float,
  hammerAlpha: Float,
  impact: Float,
  shakeX: Float,
  marked: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(14.dp)
  Box(
    modifier = modifier
      .aspectRatio(MOLE_UNIT_W / MOLE_UNIT_H)
      .clip(shape)
      .background(if (active) palette.cellUp else palette.cell)
      .border(
        width = if (active) 1.5.dp else 1.dp,
        color = if (active) palette.activeStroke else palette.cellStroke,
        shape = shape,
      )
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      ),
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val k = size.width / MOLE_UNIT_W
      withTransform({
        scale(k, k, pivot = Offset.Zero)
        translate(shakeX, 0f)
      }) {
        drawMoleGround(palette, tufts)
        if (showMole) {
          withTransform({
            translate(0f, riseOffset)
            if (squash > 0f) {
              scale(
                scaleX = 1f + 0.06f * squash,
                scaleY = 1f - 0.14f * squash,
                pivot = Offset(45f, 100f),
              )
            }
          }) {
            drawMoleBody(palette, cry)
          }
        }
        drawMoleFrontGrass(palette, tufts)
        drawMoleHammer(palette, hammerAngle, hammerAlpha)
        drawMoleImpact(palette, impact)
      }
    }

    if (marked) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
          modifier = Modifier
            .fillMaxSize(0.32f)
            .clip(CircleShape)
            .background(palette.marker),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = palette.markerInk,
            modifier = Modifier.fillMaxSize(0.62f),
          )
        }
      }
    }
  }
}
