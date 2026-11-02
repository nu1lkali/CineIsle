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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emby 媒体库「随机抽卡」全屏揭晓层。
 *
 * 流程：抽取中的召唤法阵（旋转环 + 刻度 + 粒子内聚 + 卡背）→ 白光爆发 + 冲击波
 * → 卡牌翻转展开 → 结果。**结果分两种形态**：
 *  · 抽 1 张（默认）：一张大卡直接给出封面 / 标题 / 简介 + 播放 / 重抽 / 取消；
 *  · 抽 N 张（设置里「抽卡数量」= 3 / 5 / 10）：N 张小卡按 [GRID_STAGGER_MS] 错峰翻牌，
 *    排成网格；底部「再抽 N 张 / 关闭」，点某一张才展开它的详情卡（播放 / 返回列表）。
 *    为什么不一上来就给 N 张详情：10 张卡要 10 段简介，屏幕根本排不下，也不符合
 *    「先看抽到了什么，再决定深看哪一张」的顺序。
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
 * 全变 R。多抽时光效取**这一批里最高的稀有度**（抽到 SSR 就得有 SSR 的排场）。
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
 * 抽卡层的一张卡：条目本体 + 卡面要用的文字与图地址。
 *
 * 为什么由调用方（媒体库页）构造而不是在这里算：标题要走 `viewModel.displayTitle`
 * （剧集要拼 `SxxExx` 前缀）、图片地址要走 `viewModel.imageUrl`（带 api_key 与
 * Primary 重定向），这两件事都只有 ViewModel 知道。
 */
internal data class GachaCard(
  val item: EmbyItem,
  val title: String,
  val posterUrl: String?,
  val backdropUrl: String?,
)

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

/** 多抽网格里「番号」那一行占的总高（含与海报之间的 4dp 间隙）。 */
private val GACHA_CAPTION_HEIGHT = 20.dp

/**
 * 标题最前面的「番号」：`ABC-123` / `ABP_123` / `FC2-PPV-1234567` / `SSIS-001-C`。
 *
 * ## 为什么强制要求分隔符
 *
 * 不加 `[-_]` 的话，普通片名里的「字母 + 数字」也会被当成番号（`Blade2049`、
 * `BladeRunner2049`…）。宁可漏掉少数没写分隔符的条目，也不要满屏乱标 ——
 * 这一行是给用户「认片」用的，标错了比不标更糟。
 *
 * ## 支持的形态
 *
 * · `[A-Za-z]{2,8}` 开头，**后面可以跟最多两位数字**（`FC2-PPV-1234567` 的 `FC2`）；
 * · 再接可选的字母段（`-PPV`），最后 `[-_]\d{2,7}` 才是真正的编号；
 * · 尾部可选 `[-_][A-Za-z0-9]{1,4}`（`SSIS-001-C`）。中文标题紧跟其后（`ABP-123 标题`）
 *   不受影响 —— 后缀段只收 ASCII 字母数字。
 *
 * ## 全角
 *
 * 日文源的标题常把字母 / 数字 / 连字符写成全角，先折成半角再匹配。
 * ⚠️ **不折 `ー`（片假名长音）**：它长得像连字符，但折成 `-` 会把片名切坏。
 */
private val GACHA_CODE_REGEX = Regex(
  "^\\s*(?:[\\[【(（]\\s*)?" +
    "([A-Za-z]{2,8}\\d{0,2}(?:[-_][A-Za-z]{1,4})?[-_]\\d{2,7}(?:[-_][A-Za-z0-9]{1,4})?)",
)

/**
 * 标题开头常见的**标签**（`【中文字幕】`、`[4K]`），匹配番号前先摘掉一层。
 * 只摘一个、最多 24 字：再往后的括号多半是片名本身的一部分，不是标签。
 */
private val GACHA_LEADING_TAG = Regex("^\\s*[\\[【(（][^\\]】)）]{0,24}[\\]】)）]\\s*")

/** 把全角字母 / 数字 / 几种「像连字符」的符号折成半角（见 [GACHA_CODE_REGEX] 的说明）。 */
private fun normalizeGachaCodeSource(raw: String): String {
  val sb = StringBuilder(raw.length)
  for (ch in raw) {
    sb.append(
      when {
        ch in '０'..'９' -> '0' + (ch - '０')
        ch in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
        ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
        ch == '－' || ch == '‐' || ch == '–' || ch == '—' -> '-'
        else -> ch
      },
    )
  }
  return sb.toString()
}

/**
 * 取标题最前面的番号（大写）；认不出来返回 null。
 *
 * 多抽网格在卡下方显示它 —— 小卡只有海报，十张排一起很难分辨是哪一部，
 * 番号是最短的身份标识（用户原话「番号能从标题中提取出来，最前面的就是」）。
 */
internal fun gachaCodeOf(title: String?): String? {
  val text = title?.trim().orEmpty()
  if (text.isEmpty()) return null
  val normalized = normalizeGachaCodeSource(text)
  // 两种写法各试一次：
  //  ① `[ABP-123] 标题` —— 番号自己在括号里（正则允许吃掉一个开括号）；
  //  ② `【中文字幕】ABP-123` —— 括号里是标签，得先整段摘掉再匹配。
  // 顺序不能反：① 先试，失败再摘标签（摘完 `[ABP-123]` 就什么都不剩了）。
  GACHA_CODE_REGEX.find(normalized)?.let { return it.groupValues[1].uppercase() }
  val bare = GACHA_LEADING_TAG.replace(normalized, "")
  if (bare == normalized) return null
  return GACHA_CODE_REGEX.find(bare)?.groupValues?.get(1)?.uppercase()
}

/** 动画阶段（决定画哪一层）。 */
private enum class GachaPhase { Awaiting, Charging, Burst, Card }

/** 法阵蓄能时长 */
private const val ROLL_MS = 1250

/** 单张卡牌的翻转时长 */
private const val REVEAL_MS = 700

/** 多抽时第一张卡的翻转时长 */
private const val GRID_REVEAL_BASE_MS = 620

/** 多抽时相邻两张卡的起翻间隔 */
private const val GRID_STAGGER_MS = 80

/**
 * @param cards 抽到的卡；**空列表 = 还在取数**（只播「准备中」的法阵，不揭晓）
 * @param rollId 每次抽取自增。**动画重启只认它**：同一个条目重抽也可能再抽回来，
 *               用 `item.Id` 当 key 的话「又抽到上一条」时动画不会重播，看起来像没反应。
 * @param resolveDetail 点上某张小卡时补一次详情（列表接口不保证带 `Overview`）。
 *                      null = 用列表里的数据直接展示。失败请返回原卡片而不是抛异常。
 */
@Composable
internal fun EmbyGachaDrawDialog(
  cards: List<GachaCard>,
  rollId: Int,
  onPlay: (GachaCard) -> Unit,
  onReroll: () -> Unit,
  onDismiss: () -> Unit,
  /**
   * 把**这一批**整批丢进播放队列：默认第 1 张起播，其余按揭晓顺序排在后面。
   *
   * 与「随机一张」是两个不同的意图：那个是「帮我挑一个」，这个是「这十张我都要看」。
   * 所以这里**不 shuffle** —— 顺序就是用户刚看到的顺序，别自作主张打乱。
   */
  onPlayAll: (List<GachaCard>) -> Unit = {},
  resolveDetail: (suspend (GachaCard) -> GachaCard)? = null,
) {
  val count = cards.size
  // 多抽的「主色」取这一批里最高的稀有度：抽到 SSR 时整场都是金色排场
  val bestRarity = remember(cards) {
    cards.map { embyGachaRarityOf(it.item) }.minByOrNull { it.ordinal } ?: EmbyGachaRarity.R
  }

  val charge = remember { Animatable(0f) }
  val flash = remember { Animatable(0f) }
  val shock = remember { Animatable(0f) }
  val flip = remember { Animatable(0f) }
  val grid = remember { Animatable(0f) }
  val detailIn = remember { Animatable(0f) }
  var phase by remember { mutableStateOf(GachaPhase.Awaiting) }

  /**
   * 「准备卡池」→「祈愿中」的**进度接力点**。
   *
   * 等待期法阵由 idle（无穷循环）驱动，进度 p = idle×0.4；一取到卡就切成
   * p = charge（0→1）。若直接切，p 会从「idle 当时停在哪」**跳回 0** ——
   * 用户看到的就是刻度环、卡背摆动、中心核一起「闪一下重来」。
   * 所以切相位的那一刻先把 idle 的当前值记下来，之后一律
   * p = spinBase + charge×(1-spinBase)：
   * 起点就是等待期停下的那个值（无跳变），终点仍然是 1（蓄能打满的亮度不超调）。
   * 角度连续、亮度连续，动画是一条线走完的。
   */
  var spinBase by remember { mutableStateOf(0f) }

  // 多抽：点开的那一张（null = 看网格）
  var focused by remember { mutableStateOf<GachaCard?>(null) }
  // 补完详情的副本：网格上的小卡用列表数据，展开后才换成带简介的那一份
  var focusedDetail by remember { mutableStateOf<GachaCard?>(null) }
  var detailLoading by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // 取数等待期（cards 为空）没有 charge 可读，用一个循环量驱动法阵的缓慢转动，
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

  val gridTotalMs = GRID_REVEAL_BASE_MS + (count - 1).coerceAtLeast(0) * GRID_STAGGER_MS

  LaunchedEffect(rollId, count) {
    if (cards.isEmpty()) {
      phase = GachaPhase.Awaiting
      return@LaunchedEffect
    }
    charge.snapTo(0f)
    flash.snapTo(0f)
    shock.snapTo(0f)
    flip.snapTo(0f)
    grid.snapTo(0f)
    detailIn.snapTo(0f)
    focused = null
    focusedDetail = null

    // 接力：把等待期法阵停下的位置接过来，蓄能从这里继续（见 spinBase 的说明）
    spinBase = idle.value * 0.4f
    phase = GachaPhase.Charging
    charge.animateTo(1f, tween(ROLL_MS, easing = LinearEasing))

    // ── 揭晓：闪白 + 冲击波 + 翻牌三件事同时起 ──
    // 闪光先极快地打满再淡出（「爆发」就是这一下），冲击波慢一点铺开，
    // 翻牌在闪光淡出后才完全展开，三者的错峰就是「炸开 → 看见卡」的层次。
    phase = GachaPhase.Burst
    launch {
      flash.animateTo(1f, tween(70, easing = LinearEasing))
      flash.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
    }
    launch {
      shock.animateTo(1f, tween(760, easing = LinearOutSlowInEasing))
    }
    if (count == 1) {
      flip.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
    } else {
      // 多抽用一条总时间轴，每张卡从自己的位移算局部进度（见 GachaMiniCard）——
      // 给每张卡各起一个 Animatable 也行，但 N 个动画协程的调度顺序不保证，
      // 一条时间轴才能保证「从左到右、从上到下」严格依次翻开。
      grid.animateTo(1f, tween(gridTotalMs, easing = LinearEasing))
    }

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
                bestRarity.deep
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
          // ⚠️ 蓄能段必须带上 spinBase：否则切相位那一下 p 归零，法阵会「重来一遍」
          // 蓄能部分按 (1 - spinBase) 归一化：终点仍然是 1。直接写 spinBase + charge
          // 会冲到 1.4 —— 法阵那边对 heighten 做了 coerceIn 看不出来，但卡背的呼吸
          // 缩放（0.92+0.08p）与描边 alpha 都是按 [0,1] 调的，会停在「过头」的亮度上。
          val p = if (phase == GachaPhase.Awaiting) idle.value * 0.4f else spinBase + charge.value * (1f - spinBase)
          drawSummonCircle(center = center, base = base, p = p, accent = GachaInk.Spark)
        }
        val s = shock.value
        // 只在 0→1 的进行中画：跑完后 shock 停在 1，再画一遍只会是一堆 alpha=0 的
        // 路径（白算 20 个三角形 + 3 个圈），没有必要
        if (s > 0f && s < 1f) {
          drawBurstRays(center = center, base = base, s = s, accent = bestRarity.accent)
        }
        // 揭晓后卡片背后常驻的稀有度光晕（缓慢明暗，暗示这是「活着」的卡）
        if (phase == GachaPhase.Card) {
          val pulse = (sin(idle.value * 2.0 * PI).toFloat() + 1f) / 2f
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(
                bestRarity.accent.copy(alpha = 0.16f * pulse),
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
        // 与上面 Canvas 那一份同口径（含归一化）—— 两处必须算出同一个 p，否则
        // 法阵转到的角度和卡背摆动的角度对不上，切相位时会看出「两层各走各的」
        val p = if (phase == GachaPhase.Awaiting) idle.value * 0.4f else spinBase + charge.value * (1f - spinBase)
        GachaCardBack(
          progress = p,
          modifier = Modifier.align(Alignment.Center),
        )
      }

      // ── 3. 揭晓 ──
      if (cards.isNotEmpty() && (phase == GachaPhase.Burst || phase == GachaPhase.Card)) {
        if (count == 1) {
          // 3a. 单抽：一张大卡，自 -82° 翻正
          val f = flip.value
          // ⚠️ 卡面尺寸必须在**这里**按可用空间算，不能在卡片内部用
          //   `fillMaxWidth(0.88f) + widthIn(max=380.dp)` —— 约束类修饰符要写在尺寸类
          //   修饰符**之前**才生效，反过来写 widthIn 会被 fillMaxWidth 已经固定的
          //   min=max 顶掉：平板上卡片被拉成一整屏宽，横屏时高度还会超出屏幕。
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxSize()
              // Dialog 是 edge-to-edge（decorFitsSystemWindows=false）⇒ 不显式让开
              // 状态栏和顶部那行「本次抽到」，卡面会被顶到状态栏/挖孔下面。
              // padding(top=44) 是那行 13sp 说明 + 16dp 余量。
              .statusBarsPadding()
              .padding(top = 44.dp, bottom = 16.dp),
          ) {
            val cardWidth = minOf(maxWidth * 0.88f, 380.dp)
            // 上下留白已经从上面扣掉了，这里再留 6% 呼吸空间；
            // 横屏（屏幕只有 360dp 高）时卡片内部自己滚动
            val cardMaxHeight = maxHeight * 0.94f
            val card = cards.first()
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
                item = card.item,
                title = card.title,
                rarity = embyGachaRarityOf(card.item),
                posterUrl = card.posterUrl,
                backdropUrl = card.backdropUrl,
                detailAlpha = detailIn.value,
                secondaryLabel = "重抽",
                onSecondary = onReroll,
                onPlay = { onPlay(card) },
                onDismiss = onDismiss,
                modifier = Modifier
                  .width(cardWidth)
                  .heightIn(max = cardMaxHeight),
              )
            }
          }
        } else {
          // 3b. 多抽：网格 + 底部操作条
          //
          // ## 列数是**算出来的**，不是写死的
          //
          // 原来 `count >= 5 -> 5`：400dp 宽的机器上每张只剩 68dp（(376-32)/5），
          // 海报看不清、屏幕上下还空着一大半 —— 用户原话「一行要塞 5 张，卡很小」。
          // 现在从 2 列一路试到 6 列，每一档都按**宽高两边**分别算能放下多大
          // （`byWidth` 按列均分、`byHeight` 按行均分，取小值才是真塞得下的），
          // 再挑能给出最大卡片的那一档。10 张 → 3 列（111dp/张），
          // 6 张 → 2 列（147dp/张），比原来大一倍上下。
          //
          // 为什么上限 6 列：再多只会把卡压成缩略图，用户还得歪头认脸；
          // 10 张排 4 列（86dp）算出来就是比 3 列（111dp）小，自然会落到 3 列。
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxSize()
              // 顶部状态栏 + 「本次抽到 N 张」那行、底部导航栏，都必须让开：
              // 10 张时网格几乎顶满整屏，不留边就会压在状态栏和标题上（真机反馈）。
              // 让开之后 `maxHeight` 本身就是可用区，下面的列数试算直接基于它。
              .statusBarsPadding()
              .navigationBarsPadding()
              .padding(top = 44.dp),
          ) {
            // 底栏（四个键）自身的高度：竖屏是两行（40 + 8 + 40 + 24dp 下边距 ≈ 112），
            // 横屏是一行。导航栏已经由上面的 navigationBarsPadding 让开了。
            val bottomReserve = if (maxHeight < 420.dp) 72.dp else 120.dp
            val gap = 10.dp
            // 海报比例（宽:高 = 1:1.5），与单抽卡面同源
            val cellAspect = 1.5f
            val availW = maxWidth * 0.94f
            val availH = (maxHeight - bottomReserve - 16.dp).coerceAtLeast(96.dp)

            // 番号：从标题最前面抠（见 [gachaCodeOf]）。**只要这一批里有一张有**，
            // 每张卡都留出这一行的高度 —— 只有有的有有的没有时，行高会参差不齐，
            // 网格看着像没对齐；留空的那张只是少一行字。
            val codes = cards.map { gachaCodeOf(it.title) }
            val captionH = if (codes.any { it != null }) GACHA_CAPTION_HEIGHT else 0.dp

            // 逐档试算，同时记两个候选：
            //  · best = 卡最大（不管尾排剩几张）；
            //  · tidy = 尾排**不只剩一张**的候选里卡最大的。
            // 只要 tidy 不比 best 小太多（≥ 78%）就用 tidy：5 张从「2+2+1」变「3+2」、
            // 10 张从「3+3+3+1」变「4+4+2」，用一个孤零零的尾排换来整批看起来是「一批」，
            // 尺寸只掉 10% 左右。7 张那种换法要掉两成多（3+3+1 → 4+3），
            // 那就老实保持最大卡 —— 大小优先。
            var bestColumns = 2
            var bestWidth = 0.dp
            var tidyColumns = 0
            var tidyWidth = 0.dp
            for (c in 2..minOf(count, 6)) {
              val rows = (count + c - 1) / c
              val byWidth = (availW - gap * (c - 1)) / c
              // 每格高度 = 海报高(cellW×比例) + 番号行，所以要先扣掉 captionH 再折成宽度
              val byHeight = (((availH - gap * (rows - 1)) / rows) - captionH) / cellAspect
              // 260dp 封顶：2 张 / 4 张在大屏上会算出一张巨卡，看着不像「一批」
              val w = minOf(minOf(byWidth, byHeight), 260.dp)
              if (w > bestWidth) {
                bestWidth = w
                bestColumns = c
              }
              val tail = count % c
              if ((tail == 0 || tail >= 2) && w > tidyWidth) {
                tidyWidth = w
                tidyColumns = c
              }
            }
            val useTidy = tidyColumns > 0 && tidyWidth >= bestWidth * 0.78f
            val columns = if (useTidy) tidyColumns else bestColumns
            val cellWidth = if (useTidy) tidyWidth else bestWidth
            val cellHeight = cellWidth * cellAspect
            Column(
              modifier = Modifier
                .align(Alignment.Center)
                // 底部把操作条的位置让出来；整体居中后网格自然落在上半屏，
                // 不会压在「再抽 / 关闭」上
                .padding(bottom = bottomReserve),
              verticalArrangement = Arrangement.spacedBy(gap),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              // 最后一排不满时用 Row 居中（Column 的 CenterHorizontally 会把它摆正）
              cards.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                  row.forEachIndexed { colIndex, card ->
                    GachaMiniCard(
                      card = card,
                      rarity = embyGachaRarityOf(card.item),
                      code = codes[rowIndex * columns + colIndex],
                      captionHeight = captionH,
                      progress = { grid.value },
                      totalRevealMs = gridTotalMs,
                      index = rowIndex * columns + colIndex,
                      width = cellWidth,
                      height = cellHeight,
                      interactive = phase == GachaPhase.Card && focused == null,
                      // 长按直接播：这条不补详情（播放只需要 Id，Overview 用不上），
                      // 也就少一次 /Items 往返。
                      onPlayDirect = { onPlay(card) },
                      onClick = {
                        focused = card
                        focusedDetail = card
                        val resolver = resolveDetail ?: return@GachaMiniCard
                        detailLoading = true
                        scope.launch {
                          val enriched = runCatching { resolver(card) }.getOrNull()
                          // 用户可能已经点了别的一张：只有还停在它上面才回填
                          if (focused?.item?.Id == card.item.Id) {
                            focusedDetail = enriched ?: card
                            detailLoading = false
                          }
                        }
                      },
                    )
                  }
                }
              }
            }
          }

          // ── 底部操作区：随机一张 / 全部入队 / 再抽 / 关闭 ──
          //
          // 四个出口挤成一行，360dp 宽的机器放不下（带图标的按钮一个就要 100dp 上下）：
          // 竖屏拆两行，横屏高度宝贵、宽度富裕，反倒一行更合适。
          //
          // ⚠️ 「关闭」也用 OutlinedButton，不再是 TextButton：一行光秃秃的字摆在
          //    另外三个有包裹的按钮旁边，看着像没做完（用户原话）。
          //    用次要前景色（TextSecondary）+ 同样的形状 / 内边距，层级仍然最弱。
          val actionsAlpha = detailIn.value
          val actionsEnabled = actionsAlpha > 0.99f
          val randomAction: @Composable () -> Unit = {
            OutlinedButton(
              onClick = { onPlay(cards.random()) },
              enabled = actionsEnabled,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextPrimary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.size(6.dp))
              Text("随机一张", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
          }
          val queueAllAction: @Composable () -> Unit = {
            OutlinedButton(
              // 整批入队：第 1 张起播，其余按揭晓顺序排队（不打乱 —— 顺序就是刚看到的顺序）
              onClick = { onPlayAll(cards) },
              enabled = actionsEnabled,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextPrimary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Icon(
                Icons.Default.PlaylistAdd,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.size(6.dp))
              Text("全部入队", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
          }
          val rerollAction: @Composable () -> Unit = {
            Button(
              onClick = onReroll,
              enabled = actionsEnabled,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = bestRarity.accent,
                contentColor = Color(0xFF17110A),
              ),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(6.dp))
              Text("再抽 $count 张", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
          }
          val closeAction: @Composable () -> Unit = {
            OutlinedButton(
              onClick = onDismiss,
              enabled = actionsEnabled,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextSecondary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.size(6.dp))
              Text("关闭", fontSize = 13.sp)
            }
          }
          if (LocalConfiguration.current.screenHeightDp < 420) {
            // 横屏 / 矮窗口：四个排一排
            Row(
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 20.dp)
                .graphicsLayer { alpha = actionsAlpha },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              randomAction()
              queueAllAction()
              rerollAction()
              closeAction()
            }
          } else {
            Column(
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .graphicsLayer { alpha = actionsAlpha },
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                randomAction()
                queueAllAction()
              }
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                rerollAction()
                closeAction()
              }
            }
          }

          // 点开某一张：半透明遮罩 + 详情卡（与单抽同一张卡，只是按钮换文案）
          val focus = focused
          if (focus != null) {
            val shown = focusedDetail ?: focus
            // ⚠️ 遮罩 + 详情卡整体包一层并抬 `zIndex`：网格里每张卡都带 `graphicsLayer`
            //   （3D 翻转层），真机上出现过详情卡被网格盖住的情况 —— 显式抬层比依赖
            //   「后声明者在上」稳。
            Box(
              modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            ) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  // 0.62 太透：10 张亮海报透上来，会让人以为详情卡浮在网格后面
                  .background(Color.Black.copy(alpha = 0.82f))
                  .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                  ) {
                    // 点卡面外的空白 = 返回列表（不是关闭抽卡）
                    focused = null
                    focusedDetail = null
                    detailLoading = false
                  },
              )
              BoxWithConstraints(
                modifier = Modifier
                  .fillMaxSize()
                  // 与网格同一套安全区：详情卡也不许压住状态栏 / 顶部说明
                  .statusBarsPadding()
                  .padding(top = 44.dp, bottom = 24.dp),
              ) {
                val cardWidth = minOf(maxWidth * 0.88f, 380.dp)
                val cardMaxHeight = maxHeight * 0.94f
                Box(
                  modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                      val v = detailIn.value
                      scaleX = 0.94f + 0.06f * v
                      scaleY = 0.94f + 0.06f * v
                    },
                ) {
                  GachaResultCard(
                    // ⚠️ 元信息 / 海报 / 稀有度一律取**列表里的那条**（focus），只有简介用
                    //   后补到的（shown）：详情接口会带出列表没有的字段（OfficialRating、
                    //   Genres…），换源会让海报右侧那一列变高，卡片整体跳一下。
                    item = focus.item,
                    title = focus.title,
                    rarity = embyGachaRarityOf(focus.item),
                    posterUrl = shown.posterUrl,
                    backdropUrl = shown.backdropUrl,
                    overview = shown.item.Overview,
                    detailAlpha = 1f,
                    overviewLoading = detailLoading,
                    secondaryLabel = "返回列表",
                    onSecondary = {
                      focused = null
                      focusedDetail = null
                      detailLoading = false
                    },
                    // 多抽的详情卡不摆「取消」：这一层是「看某一张」，退出就是返回列表，
                    // 关掉整个抽卡由底部的「关闭」负责（两层各有各的出口）
                    dismissLabel = null,
                    onPlay = { onPlay(shown) },
                    onDismiss = {},
                    modifier = Modifier
                      .width(cardWidth)
                      .heightIn(max = cardMaxHeight),
                  )
                }
              }
            }
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
          GachaPhase.Charging -> if (count > 1) "祈愿中… · $count 连" else "祈愿中…"
          GachaPhase.Burst, GachaPhase.Card -> if (count > 1) "本次抽到 $count 张" else "本次抽到"
        },
        color = GachaInk.TextSecondary,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .statusBarsPadding()
          .padding(top = 16.dp),
      )
      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .statusBarsPadding(),
      ) {
        Icon(Icons.Default.Close, contentDescription = "关闭", tint = GachaInk.TextSecondary)
      }
    }
  }
}

/**
 * 多抽网格里的一张**小卡**：只有封面 + 稀有度角标，点上才展开详情。
 *
 * 翻牌进度不通过参数传 Float —— 那会让整张网格每帧重组一次。这里传的是
 * `progress: () -> Float`（读 [Animatable] 的值），**在 `graphicsLayer` 里读**：
 * 快照系统只会让这一层失效，重组一次都不会发生。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun GachaMiniCard(
  card: GachaCard,
  rarity: EmbyGachaRarity,
  code: String?,
  captionHeight: Dp,
  progress: () -> Float,
  totalRevealMs: Int,
  index: Int,
  width: Dp,
  height: Dp,
  interactive: Boolean,
  onClick: () -> Unit,
  /** 长按 = 跳过详情层，直接播这张。 */
  onPlayDirect: () -> Unit,
) {
  val haptic = LocalHapticFeedback.current
  val shape = RoundedCornerShape(10.dp)
  // 总时间轴 → 这张卡自己的局部进度：起点 = index * 间隔，时长 = BASE。
  // 海报的翻转和下方番号的淡入共用同一条，卡片翻完番号刚好亮起来。
  // ⚠️ 只在 graphicsLayer 里读 progress()（绘制阶段），不在组合期读 ⇒ 每帧只失效图层。
  val easedOf: () -> Float = {
    val elapsed = progress() * totalRevealMs
    val local = ((elapsed - index * GRID_STAGGER_MS) / GRID_REVEAL_BASE_MS.toFloat())
      .coerceIn(0f, 1f)
    FastOutSlowInEasing.transform(local)
  }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.width(width),
  ) {
    Box(
      modifier = Modifier
        .graphicsLayer {
          val eased = easedOf()
          rotationY = (1f - eased) * -82f
          cameraDistance = 18f * density
          scaleX = 0.88f + 0.12f * eased
          scaleY = 0.88f + 0.12f * eased
          alpha = if (eased < 0.04f) 0f else 1f
        }
        .width(width)
        .height(height)
        .clip(shape)
        .background(GachaInk.Card)
        .border(
          width = if (rarity == EmbyGachaRarity.SSR) 2.dp else 1.dp,
          color = rarity.accent.copy(alpha = 0.9f),
          shape = shape,
        )
        // 点 = 看详情，长按 = 直接播放（跳过详情层）。
        // ⚠️ 用 `combinedClickable` 而不是「clickable + pointerInput 长按检测」：
        //   后者会和 clickable 抢同一个手势，松手时两个回调都触发（既开详情又起播）。
        // 长按震动少不了 —— 没有触感反馈时用户分不清「按住了」和「点轻了没反应」。
        .combinedClickable(
          enabled = interactive,
          onClick = onClick,
          onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPlayDirect()
          },
        ),
    ) {
      EmbyImage(
        url = card.posterUrl,
        contentDescription = card.title,
        fallbackUrl = card.backdropUrl,
        maxWidth = 240,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      // 稀有度角标：放左上角，不压海报下沿的字幕 / 标题
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(4.dp)
          .clip(RoundedCornerShape(5.dp))
          .background(Color.Black.copy(alpha = 0.62f))
          .border(0.8.dp, rarity.accent.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
          .padding(horizontal = 4.dp, vertical = 1.dp),
      ) {
        Text(
          text = rarity.label,
          color = rarity.accent,
          // 角标随卡宽缩放：列数不再是写死的 5，同一套 9sp 在 111dp 的卡上刚好、
          // 在 183dp 的卡上就像个小点。10~14sp 之间夹住，两端都不难看。
          fontSize = (width.value * 0.085f).coerceIn(10f, 14f).sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.6.sp,
        )
      }
    }

    // ── 番号（卡下方一行）──
    // 小卡只有海报，十张并排时很难分辨是哪一部；番号是最短的身份标识。
    // 高度由调用方按 GACHA_CAPTION_HEIGHT 统一预留（有的条目认不出番号时留空），
    // 保证每一行网格等高 —— 否则行高参差，看着像没对齐。
    if (captionHeight > 0.dp) {
      Spacer(Modifier.height(4.dp))
      Box(
        modifier = Modifier
          .width(width)
          .height(captionHeight - 4.dp)
          .graphicsLayer { alpha = easedOf() },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = code.orEmpty(),
          color = GachaInk.TextPrimary.copy(alpha = 0.9f),
          fontSize = (width.value * 0.078f).coerceIn(10f, 13f).sp,
          fontWeight = FontWeight.SemiBold,
          // 等宽数字（`123` 与 `456` 同宽）让一排番号看起来是梳齐的。
          // ⚠️ material3 的 Text 没有 fontFeatureSettings 参数，只能走 style。
          style = TextStyle(fontFeatureSettings = "tnum"),
          letterSpacing = 0.8.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
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
 * 结果卡：封面 + 稀有度 + 标题 + 元信息 + 简介 + 操作。
 *
 * [detailAlpha] 用来做「卡先出来、字后浮现」的错峰：卡牌翻转结束时文字才淡入，
 * 直接一次性全画会显得糊成一团。
 *
 * [secondaryLabel] / [dismissLabel] 让同一张卡在两处复用：单抽是「重抽 / 取消」，
 * 多抽点开的详情是「返回列表 / 无」。别看只有两个字符串 —— 抽卡的两层出口必须
 * 各说各的，否则用户分不清「返回」是回网格还是关掉抽卡。
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
  /**
   * 简介正文。默认取 [item] 自己的 —— 多抽「点开某张」时**基础信息用列表里的那条**、
   * 只把后补到的简介传进来：否则详情里多出来的字段（OfficialRating / Genres…）
   * 会让海报右侧那一列变高，卡片照样跳一下。
   */
  overview: String? = item.Overview,
  overviewLoading: Boolean = false,
  secondaryLabel: String = "重抽",
  onSecondary: () -> Unit = {},
  dismissLabel: String? = "取消",
  onPlay: () -> Unit,
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
    // 多抽点开详情时要现补一次 `/Items/{id}`：补的过程中说「正在加载」，补不到再说「没有」，
    // 否则用户会以为这条真的没有简介（这两种情况要能分清）。
    //
    // ⚠️ 这一块**固定 3 行高**（3×18sp），不随内容伸缩，`maxLines` 也压到 3：
    //   简介是**异步**到位的，按内容自适应的话，文字一到卡片就长高；而整张卡是
    //   `align(Center)` 居中的 ⇒ 连同上下的海报、播放键一起「跳一下」。
    //   预留高度 + 同值 maxLines ⇒ 换字不换框，位置纹丝不动。
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 54.dp),
      contentAlignment = Alignment.TopStart,
    ) {
      if (overviewLoading && overview.isNullOrBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.5.dp,
            color = rarity.accent,
          )
          Spacer(Modifier.size(8.dp))
          Text(
            text = "正在加载简介…",
            color = GachaInk.TextSecondary.copy(alpha = 0.75f),
            fontSize = 12.sp,
          )
        }
      } else {
        Text(
          text = overview?.trim()?.takeIf { it.isNotEmpty() } ?: "这部作品还没有简介。",
          color = if (overview.isNullOrBlank()) {
            GachaInk.TextSecondary.copy(alpha = 0.6f)
          } else {
            GachaInk.TextSecondary
          },
          fontSize = 12.sp,
          lineHeight = 18.sp,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Spacer(Modifier.height(16.dp))

    // ── 操作 ──
    // 三个键的层级：播放是主操作（实心稀有度色），次操作（描边），退出最弱（纯文字）
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
        onClick = onSecondary,
        enabled = detailAlpha > 0.99f,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextPrimary),
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(secondaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
      }
    }
    if (dismissLabel != null) {
      TextButton(
        onClick = onDismiss,
        enabled = detailAlpha > 0.5f,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 2.dp),
      ) {
        Text(dismissLabel, color = GachaInk.TextSecondary, fontSize = 13.sp)
      }
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
