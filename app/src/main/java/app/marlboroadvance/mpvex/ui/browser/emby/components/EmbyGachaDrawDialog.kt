package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emby 媒体库「随机抽卡」全屏揭晓层。
 *
 * 流程：抽取中的召唤法阵（旋转环 + 刻度 + 粒子内聚 + 卡背）→ 白光爆发 + 冲击波
 * → 卡牌翻转展开 → 结果卡（封面 / 标题 / 简介 / 稀有度）+ 播放 / 重抽 / 取消。
 *
 * ## 为什么要独立一个 Dialog 而不是在页面里叠一层 Box
 * 页面里已经有网格、顶栏、工具行和取数遮罩，再叠一层全屏动画要反复处理「谁盖谁」；
 * Dialog 自带独立窗口层级，天然盖住一切，返回键也天然走 `onDismissRequest`。
 * 配上 `usePlatformDefaultWidth = false` 才拿得到真正的全屏画布（默认宽度会留出边距）。
 *
 * ## 配色
 * 抽卡是一件「仪式感」的事，画面刻意**恒为暗底**（不跟随亮/暗主题）：亮色卡片在暗底上
 * 才有被光打亮的感觉，稀有度金 / 紫 / 蓝也只有压深的背景上才立得住。因此本文件集中
 * 定义了一套自己的调色板（[GachaInk] / [EmbyGachaRarity]），不做主题取色 ——
 * 这是**有意为之的唯一例外**，其它界面仍按主题取色（见红线「控制条外禁硬编码颜色」）。
 *
 * ## 稀有度
 * 见 [embyGachaRarityOf]：评分 ≥ 8 直接 SSR，其余用条目 Id 的哈希当种子分配 ——
 * 同一部片子每次抽到的稀有度一致（重抽不会莫名其妙「降级」），又不会让没评分的库
 * 全变 R。
 */

/** 抽卡层专用配色：暗底 + 中性暖白（抽取中）/ 稀有度色（揭晓后）。 */
private object GachaInk {
  /** 最深底：整层铺底 */
  val Vault = Color(0xFF060409)

  /** 法阵 / 卡背的主色（抽取中还没揭晓，用中性暖金，不带稀有度暗示） */
  val Spark = Color(0xFFFFD79A)

  /** 卡背渐变的两端 */
  val BackTop = Color(0xFF2B2140)
  val BackBottom = Color(0xFF0B0813)

  /** 结果卡底色（半透明深紫黑） */
  val Card = Color(0xFF14101F)
  val CardEdge = Color(0xFF2C2440)

  /** 卡面文字：主 / 次 */
  val TextPrimary = Color(0xFFF3EEFF)
  val TextSecondary = Color(0xFFB9AECB)
}

/**
 * 稀有度档位。
 *
 * [accent] 用于边框 / 光效 / 播放按钮；[deep] 是卡片周围那圈底色；
 * [glow] 是稀有度文字与描边上的高光。
 */
internal enum class EmbyGachaRarity(
  val label: String,
  val accent: Color,
  val deep: Color,
  val glow: Color,
) {
  SSR("SSR", Color(0xFFFFD24A), Color(0xFF3A2600), Color(0xFFFFF6D0)),
  SR("SR", Color(0xFFC79BFF), Color(0xFF25103B), Color(0xFFF0E2FF)),
  R("R", Color(0xFF7FC4FF), Color(0xFF0C2236), Color(0xFFD9EDFF)),
}

/**
 * 判定稀有度。
 *
 * ① 评分 ≥ 8 的一律 SSR —— 用户对「这片好不好」有直觉，抽到高分片的仪式感要对得上；
 * ② 其余用 `Id.hashCode()` 当种子：同一个条目每次抽到的档位固定，重抽只换片子、
 *    不会让用户觉得「同一部片子刚才还是 SSR，现在变 R 了」；
 * ③ 没评分的老库也不会全变 R：种子落在 0~17 / 18~44 / 其余 ≈ SSR 18% / SR 27% / R 55%，
 *    和抽卡手游的常见概率分布同个量级。
 */
internal fun embyGachaRarityOf(item: EmbyItem?): EmbyGachaRarity {
  val rating = item?.CommunityRating
  if (rating != null && rating >= 8.0) return EmbyGachaRarity.SSR
  val seed = ((item?.Id ?: item?.Name ?: "")).hashCode() and 0x7FFFFFFF
  return when (seed % 100) {
    in 0..17 -> EmbyGachaRarity.SSR
    in 18..44 -> EmbyGachaRarity.SR
    else -> EmbyGachaRarity.R
  }
}

/** 动画阶段（决定画哪一层）。 */
private enum class GachaPhase { Awaiting, Charging, Burst, Card }

/** 法阵蓄能时长 */
private const val ROLL_MS = 1250

/** 卡牌翻转时长 */
private const val REVEAL_MS = 700

/**
 * @param item 抽到的条目；null = 还在取数（此时只播「准备中」的法阵，不揭晓）
 * @param rollId 每次抽取自增。**动画重启只认它**：同一个条目重抽也可能再抽回来，
 *               用 `item.Id` 当 key 的话「又抽到上一条」时动画不会重播，看起来像没反应。
 * @param title 展示标题（由调用方用 `viewModel.displayTitle` 生成，剧集带 SxxExx 前缀）
 * @param posterUrl 竖版封面
 * @param backdropUrl 横版剧照（缺失时 [EmbyImage] 自动回退到 [posterUrl]）
 */
@Composable
fun EmbyGachaDrawDialog(
  item: EmbyItem?,
  rollId: Int,
  title: String,
  posterUrl: String?,
  backdropUrl: String?,
  onPlay: () -> Unit,
  onReroll: () -> Unit,
  onDismiss: () -> Unit,
) {
  val itemId = item?.Id
  val rarity = remember(itemId, item?.CommunityRating) { embyGachaRarityOf(item) }

  val charge = remember { Animatable(0f) }
  val flash = remember { Animatable(0f) }
  val shock = remember { Animatable(0f) }
  val flip = remember { Animatable(0f) }
  val detailIn = remember { Animatable(0f) }
  var phase by remember { mutableStateOf(GachaPhase.Awaiting) }

  // 取数等待期（item == null）没有 charge 可读，用一个循环量驱动法阵的缓慢转动，
  // 否则那段时间画面是**静止**的，看着像卡死。
  // ⚠️ 只在这里 remember State 对象、**不在组合期读 .value**：下面的 Canvas 在绘制阶段
  //    读它，每帧只失效绘制，不触发整棵树重组。
  val idleTransition = rememberInfiniteTransition(label = "gacha-idle")
  val idle = idleTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
    label = "gacha-idle-ring",
  )

  LaunchedEffect(rollId, itemId) {
    if (item == null) {
      phase = GachaPhase.Awaiting
      return@LaunchedEffect
    }
    charge.snapTo(0f)
    flash.snapTo(0f)
    shock.snapTo(0f)
    flip.snapTo(0f)
    detailIn.snapTo(0f)

    phase = GachaPhase.Charging
    charge.animateTo(1f, tween(ROLL_MS, easing = LinearEasing))

    // ── 揭晓：闪白 + 冲击波 + 卡牌翻转三件事同时起 ──
    // 闪光先极快地打满再淡出（「爆发」就是这一下），冲击波慢一点铺开，
    // 卡牌翻转在闪光淡出后才完全展开，三者的错峰就是「炸开 → 看见卡」的层次。
    phase = GachaPhase.Burst
    launch {
      flash.animateTo(1f, tween(70, easing = LinearEasing))
      flash.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
    }
    launch {
      shock.animateTo(1f, tween(760, easing = LinearOutSlowInEasing))
    }
    flip.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))

    phase = GachaPhase.Card
    detailIn.animateTo(1f, tween(260))
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      // 全屏画布：默认宽度会留出系统边距，动画就没法铺满了
      usePlatformDefaultWidth = false,
      // 点背景不关：揭晓瞬间误触关掉 = 白抽一次，代价比「多点一下取消」大
      dismissOnClickOutside = false,
      dismissOnBackPress = true,
      decorFitsSystemWindows = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.radialGradient(
            colors = listOf(
              if (phase == GachaPhase.Card || phase == GachaPhase.Burst) {
                rarity.deep
              } else {
                Color(0xFF170F22)
              },
              GachaInk.Vault,
            ),
            radius = 1400f,
          ),
        ),
    ) {
      // ── 1. 光效层（法阵 / 粒子 / 爆发光线）──
      Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = minOf(size.width, size.height)
        val rolling = phase == GachaPhase.Awaiting || phase == GachaPhase.Charging
        if (rolling) {
          val p = if (phase == GachaPhase.Awaiting) idle.value * 0.4f else charge.value
          drawSummonCircle(center = center, base = base, p = p, accent = GachaInk.Spark)
        }
        val s = shock.value
        // 只在 0→1 的进行中画：跑完后 shock 停在 1，再画一遍只会是一堆 alpha=0 的
        // 路径（白算 20 个三角形 + 3 个圈），没有必要
        if (s > 0f && s < 1f) {
          drawBurstRays(center = center, base = base, s = s, accent = rarity.accent)
        }
        // 揭晓后卡片背后常驻的稀有度光晕（缓慢明暗，暗示这是「活着」的卡）
        if (phase == GachaPhase.Card) {
          val pulse = (sin(idle.value * 2.0 * PI).toFloat() + 1f) / 2f
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(
                rarity.accent.copy(alpha = 0.16f * pulse),
                Color.Transparent,
              ),
              center = center,
              radius = base * 0.55f,
            ),
            radius = base * 0.55f,
            center = center,
          )
        }
      }

      // ── 2. 抽取中：卡背 ──
      if (phase == GachaPhase.Awaiting || phase == GachaPhase.Charging) {
        val p = if (phase == GachaPhase.Awaiting) idle.value else charge.value
        GachaCardBack(
          progress = p,
          modifier = Modifier.align(Alignment.Center),
        )
      }

      // ── 3. 揭晓：卡牌正面（从侧面翻转展开）──
      if (item != null && (phase == GachaPhase.Burst || phase == GachaPhase.Card)) {
        val f = flip.value
        // ⚠️ 卡面尺寸必须在**这里**按可用空间算，不能在卡片内部用
        //   `fillMaxWidth(0.88f) + widthIn(max=380.dp)` —— 约束类修饰符要写在尺寸类
        //   修饰符**之前**才生效，反过来写 widthIn 会被 fillMaxWidth 已经固定的
        //   min=max 顶掉：平板上卡片被拉成一整屏宽，横屏时高度还会超出屏幕。
        BoxWithConstraints(modifier = Modifier.align(Alignment.Center)) {
          val cardWidth = minOf(maxWidth * 0.88f, 380.dp)
          // 留 14% 给顶部说明与上下呼吸空间；横屏（屏幕只有 360dp 高）时卡片内部自己滚动
          val cardMaxHeight = maxHeight * 0.86f
          Box(
            modifier = Modifier
              .align(Alignment.Center)
              .graphicsLayer {
                // 起始 -82°：卡片几乎是「一张纸的侧边」，翻到 0° 才是正面
                rotationY = (1f - f) * -82f
                // cameraDistance 影响透视强度：太小会畸变，太大就看不出来在翻转
                cameraDistance = 18f * density
                scaleX = 0.86f + 0.14f * f
                scaleY = 0.86f + 0.14f * f
                alpha = if (f < 0.06f) 0f else 1f
              },
          ) {
            GachaResultCard(
              item = item,
              title = title,
              rarity = rarity,
              posterUrl = posterUrl,
              backdropUrl = backdropUrl,
              detailAlpha = detailIn.value,
              modifier = Modifier
                .width(cardWidth)
                .heightIn(max = cardMaxHeight),
              onPlay = onPlay,
              onReroll = onReroll,
              onDismiss = onDismiss,
            )
          }
        }
      }

      // ── 4. 闪白 ──
      // 放在最上层：它要盖住法阵、卡背、卡片一起「炸白」，而不是只盖背景
      val flashAlpha = flash.value
      if (flashAlpha > 0.001f) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = flashAlpha * 0.92f)),
        )
      }

      // ── 5. 顶部说明 + 关闭 ──
      // 关闭键**全程可用**：抽卡是锦上添花的功能，任何阶段都得让人能退出
      Text(
        text = when (phase) {
          GachaPhase.Awaiting -> "正在准备卡池…"
          GachaPhase.Charging -> "祈愿中…"
          GachaPhase.Burst, GachaPhase.Card -> "本次抽到"
        },
        color = GachaInk.TextSecondary,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 56.dp),
      )
      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 40.dp, end = 8.dp),
      ) {
        Icon(Icons.Default.Close, contentDescription = "关闭", tint = GachaInk.TextSecondary)
      }
    }
  }
}

/**
 * 抽取中的卡背：深紫渐变 + 金色符文圈，带轻微摆动与呼吸。
 *
 * 用 `rotationY` 摆动（而不是整体旋转）是刻意的：整张卡转起来像加载失败，
 * 小幅左右摆动才像「卡在能量里悬着」。
 */
@Composable
private fun GachaCardBack(progress: Float, modifier: Modifier = Modifier) {
  val breath = 0.92f + 0.08f * progress
  Box(
    modifier = modifier
      .graphicsLayer {
        rotationY = sin(progress * 2.0 * PI * 1.5).toFloat() * 10f
        cameraDistance = 18f * density
        scaleX = breath
        scaleY = breath
      }
      .size(width = 190.dp, height = 270.dp)
      .clip(RoundedCornerShape(18.dp))
      .background(Brush.linearGradient(listOf(GachaInk.BackTop, GachaInk.BackBottom)))
      .border(
        width = 1.dp,
        brush = Brush.linearGradient(
          listOf(
            GachaInk.Spark.copy(alpha = 0.25f + 0.45f * progress),
            Color.Transparent,
            GachaInk.Spark.copy(alpha = 0.2f + 0.4f * progress),
          ),
        ),
        shape = RoundedCornerShape(18.dp),
      ),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val c = Offset(size.width / 2f, size.height / 2f)
      val r = minOf(size.width, size.height) * 0.30f
      drawCircle(
        color = GachaInk.Spark.copy(alpha = 0.35f + 0.35f * progress),
        radius = r,
        center = c,
        style = Stroke(width = 1.5.dp.toPx()),
      )
      drawCircle(
        color = GachaInk.Spark.copy(alpha = 0.18f + 0.25f * progress),
        radius = r * 1.35f,
        center = c,
        style = Stroke(width = 1.dp.toPx()),
      )
      // 卡背中心的「核」：亮到发白的一小团光
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(Color.White, GachaInk.Spark, Color.Transparent),
          center = c,
          radius = r * 0.75f,
        ),
        radius = r * 0.75f,
        center = c,
      )
    }
  }
}

/**
 * 结果卡：封面 + 稀有度 + 标题 + 元信息 + 简介 + 三个操作。
 *
 * [detailAlpha] 用来做「卡先出来、字后浮现」的错峰：卡牌翻转结束时文字才淡入，
 * 直接一次性全画会显得糊成一团。
 */
@Composable
private fun GachaResultCard(
  item: EmbyItem,
  title: String,
  rarity: EmbyGachaRarity,
  posterUrl: String?,
  backdropUrl: String?,
  detailAlpha: Float,
  modifier: Modifier = Modifier,
  onPlay: () -> Unit,
  onReroll: () -> Unit,
  onDismiss: () -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  Column(
    modifier = modifier
      .clip(shape)
      .background(
        Brush.verticalGradient(
          listOf(GachaInk.Card.copy(alpha = 0.96f), Color(0xFF0C0913).copy(alpha = 0.97f)),
        ),
      )
      .border(
        width = 1.5.dp,
        brush = Brush.linearGradient(
          listOf(rarity.accent, rarity.accent.copy(alpha = 0.25f), rarity.glow.copy(alpha = 0.8f)),
        ),
        shape = shape,
      )
      .padding(16.dp)
      // 横屏 / 小屏时卡片会比屏幕高：内容自己滚，绝不让「播放 / 重抽」被挤出屏幕。
      // 放在 padding 之后 ⇒ 内边距固定，滚动的是内容本身。
      .verticalScroll(rememberScrollState()),
  ) {
    // ── 稀有度条 ──
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(rarity.accent.copy(alpha = 0.16f))
          .border(1.dp, rarity.accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
          .padding(horizontal = 10.dp, vertical = 3.dp),
      ) {
        Text(
          text = rarity.label,
          color = rarity.accent,
          fontSize = 13.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 2.sp,
        )
      }
      Spacer(Modifier.size(8.dp))
      Text(
        text = "稀有度揭晓",
        color = GachaInk.TextSecondary,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
      )
    }

    Spacer(Modifier.height(12.dp))

    // ── 封面 + 名称 / 元信息 ──
    Row {
      EmbyImage(
        url = posterUrl,
        contentDescription = title,
        fallbackUrl = backdropUrl,
        maxWidth = 420,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .size(width = 96.dp, height = 144.dp)
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
      )
      Spacer(Modifier.size(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title.ifBlank { "未命名" },
          color = GachaInk.TextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 24.sp,
        )
        Spacer(Modifier.height(8.dp))
        val meta = buildList {
          item.ProductionYear?.let { add("$it") }
          gachaRuntimeText(item.RunTimeTicks)?.let { add(it) }
          item.OfficialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
          Text(
            text = meta.joinToString(" · "),
            color = GachaInk.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        item.CommunityRating?.let { rating ->
          Spacer(Modifier.height(6.dp))
          Text(
            text = "★ %.1f".format(rating),
            color = rarity.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
        val genres = item.Genres.take(3).filter { it.isNotBlank() }
        if (genres.isNotEmpty()) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = genres.joinToString(" / "),
            color = GachaInk.TextSecondary.copy(alpha = 0.8f),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    // ── 简介 ──
    Text(
      text = item.Overview?.trim()?.takeIf { it.isNotEmpty() } ?: "这部作品还没有简介。",
      color = if (item.Overview.isNullOrBlank()) {
        GachaInk.TextSecondary.copy(alpha = 0.6f)
      } else {
        GachaInk.TextSecondary
      },
      fontSize = 12.sp,
      lineHeight = 18.sp,
      maxLines = 5,
      overflow = TextOverflow.Ellipsis,
    )

    Spacer(Modifier.height(16.dp))

    // ── 操作 ──
    // 三个键的层级：播放是主操作（实心稀有度色），重抽是次操作（描边），取消最弱（纯文字）
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = detailAlpha },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = onPlay,
        enabled = detailAlpha > 0.99f,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = rarity.accent,
          contentColor = Color(0xFF17110A),
        ),
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text("立即播放", fontWeight = FontWeight.Bold, fontSize = 14.sp)
      }
      OutlinedButton(
        onClick = onReroll,
        enabled = detailAlpha > 0.99f,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextPrimary),
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text("重抽", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
      }
    }
    TextButton(
      onClick = onDismiss,
      enabled = detailAlpha > 0.5f,
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 2.dp),
    ) {
      Text("取消", color = GachaInk.TextSecondary, fontSize = 13.sp)
    }
  }
}

/** 时长文案（`RunTimeTicks` 是 100ns 为单位）；为 0 / 缺失时返回 null。 */
private fun gachaRuntimeText(ticks: Long?): String? {
  val totalMinutes = (ticks ?: 0L) / 10_000_000L / 60L
  if (totalMinutes <= 0L) return null
  val h = totalMinutes / 60
  val m = totalMinutes % 60
  return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}

/**
 * 召唤法阵：三圈旋转环 + 外圈刻度 + 向内汇聚的粒子。
 *
 * 所有尺寸都按 [base]（屏幕短边）取比例，横竖屏 / 平板都能铺满不留半截。
 */
private fun DrawScope.drawSummonCircle(center: Offset, base: Float, p: Float, accent: Color) {
  val outer = base * 0.29f
  val heighten = p.coerceIn(0f, 1f)

  for (i in 0 until 3) {
    val r = outer * (0.55f + i * 0.24f)
    val alpha = (0.10f + 0.30f * heighten) * (1f - i * 0.18f)
    drawCircle(
      color = accent.copy(alpha = alpha.coerceIn(0f, 1f)),
      radius = r,
      center = center,
      style = Stroke(width = (1.2f + i * 0.7f).dp.toPx()),
    )
  }

  // 外圈刻度：每 5 格一根长线，方向随 p 转，转起来才像「法阵在跑」
  val ticks = 60
  val rot = p * 320f
  for (i in 0 until ticks) {
    val ang = (i * 360.0 / ticks + rot) * PI / 180.0
    val r1 = outer * 1.0f
    val r2 = r1 + (if (i % 5 == 0) 9f else 4f).dp.toPx()
    drawLine(
      color = accent.copy(alpha = (0.14f + 0.34f * heighten).coerceIn(0f, 1f)),
      start = Offset(center.x + (cos(ang) * r1).toFloat(), center.y + (sin(ang) * r1).toFloat()),
      end = Offset(center.x + (cos(ang) * r2).toFloat(), center.y + (sin(ang) * r2).toFloat()),
      strokeWidth = 1.dp.toPx(),
    )
  }

  // 向内汇聚的光尘：每个粒子有自己的相位偏移，不会整圈一起「齐步走」
  val particles = 30
  for (i in 0 until particles) {
    val t = ((heighten * 1.25f) + i * 0.041f) % 1f
    val ang = (i * 360.0 / particles + heighten * 110.0) * PI / 180.0
    val startR = outer * 1.5f
    val r = startR + (outer * 0.15f - startR) * t
    val alpha = (sin(t * PI).toFloat() * 0.85f).coerceIn(0f, 1f)
    drawCircle(
      color = Color.White.copy(alpha = alpha),
      radius = (1.6f + 1.4f * (1f - t)).dp.toPx(),
      center = Offset(center.x + (cos(ang) * r).toFloat(), center.y + (sin(ang) * r).toFloat()),
    )
  }

  // 中心核：越抽越亮
  val coreR = base * (0.06f + 0.10f * heighten)
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        Color.White.copy(alpha = 0.55f + 0.45f * heighten),
        accent.copy(alpha = 0.35f),
        Color.Transparent,
      ),
      center = center,
      radius = coreR,
    ),
    radius = coreR,
    center = center,
  )
}

/** 揭晓瞬间的爆发：放射光线 + 三层冲击波，[s] 是 0→1 的进度。 */
private fun DrawScope.drawBurstRays(center: Offset, base: Float, s: Float, accent: Color) {
  val fade = (1f - s).coerceIn(0f, 1f)
  val rays = 20
  val len = base * (0.12f + 0.95f * s)
  val halfWidth = 0.032f
  for (i in 0 until rays) {
    val ang = (i * 360.0 / rays + 9.0) * PI / 180.0
    val path = Path().apply {
      moveTo(center.x, center.y)
      lineTo(
        center.x + (cos(ang - halfWidth) * len).toFloat(),
        center.y + (sin(ang - halfWidth) * len).toFloat(),
      )
      lineTo(
        center.x + (cos(ang + halfWidth) * len).toFloat(),
        center.y + (sin(ang + halfWidth) * len).toFloat(),
      )
      close()
    }
    drawPath(path, color = accent.copy(alpha = 0.45f * fade))
  }

  for (i in 0 until 3) {
    val r = base * (0.08f + 0.52f * s) * (1f + i * 0.22f)
    drawCircle(
      color = accent.copy(alpha = (0.40f - i * 0.11f) * fade),
      radius = r,
      center = center,
      style = Stroke(width = (2.2f - i * 0.5f).dp.toPx()),
    )
  }
}
