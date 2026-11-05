package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.KeyframesSpec
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

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

/**
 * 单抽揭晓的动画形态（设置项「抽卡动画」）。
 *
 * ⚠️ **只有单抽（1 张）能选滚动揭晓**：卡带的意义是「停在未知的那一张上」，
 * 十连要的是「一张张翻开、看这一批来了什么」，塞进一个轮盘既看不清也失去批量感。
 * 所以多抽永远走 [CLASSIC] 的错峰翻牌，与这里选什么无关。
 *
 * ⚠️ `getEnum` 存的是枚举 `name`，发布后**不能改名**（改了老用户的偏好看不到）。
 */
enum class EmbyGachaStyle(val label: String) {
  /** 一张大卡从 -82° 翻正（v197 起的默认形态） */
  CLASSIC("经典翻牌"),

  /** 卡片自右向左滚动的横向卡带，先加速后减速停在中奖卡上 */
  SLOT("滚动揭晓"),
}

/** 法阵蓄能时长 */
private const val ROLL_MS = 1250

/** 单张卡牌的翻转时长 */
private const val REVEAL_MS = 700

/** 滚动卡带的转动总时长 */
private const val SLOT_SPIN_MS = 3600

/**
 * 停稳后的高光扫过时长（单程，不回头）。
 *
 * 比 [land] 的脉冲略短：扫光是「叮」的那一下，拖长了会变成「缓慢变色」。
 */
private const val SLOT_SWEEP_MS = 460

/** 取景框「对焦」锁定时长（1.12 倍收拢到 1.0） */
private const val SLOT_LOCK_MS = 150

/**
 * 停稳时**冲过头**多少格（棘爪回弹）。
 *
 * 真实机械转盘停下的那一下不是「刚好卡在齿位上」，而是先冲过去一点、再被棘爪顶回来。
 * 0.36 格 ≈ 45dp：冲出去的那一段要看得清（太小只是「抖了一下」，读不出惯性）。
 * ⚠️ 距离和 [SLOT_SETTLE_MS] 要一起给：只加距离会重新变硬。两者的乘积感决定手感，
 *    45dp/320ms ≈ 145dp/s，仍在「柔和滑回」的区间里。
 * ⚠️ 过冲段是靠卡带末尾的 [SLOT_TAIL] 张陪跑卡兜住的 —— tail 为 0 时过冲会露出空位。
 */
private const val SLOT_OVERSHOOT = 0.36f

/**
 * 回拉归位时长（冲过头的那 0.36 格退回落点）。
 *
 * ⚠️ 180ms 那版真机反馈是「超过之后瞬间弹回去」—— 太硬。现在给到 320ms。
 * 与滚动**共用同一条 keyframes 曲线**（见 [slotSpinSpec]），所以没有「停住再起」的接缝。
 */
private const val SLOT_SETTLE_MS = 320

/**
 * 停稳后、交给结果卡之前**站住**多久。
 *
 * 这段是留给用户看清「停在哪一张」的，不能省：0 的话中奖框一闪而过，
 * 前面 3.6 秒滚动带出来的期待感全落空。但也别给多 —— 落点脉冲本身就占了
 * 90（上冲）+ 320（回落）≈ 400ms，这里再补 120 就够读完「停在这张」。
 */
private const val SLOT_HOLD_MS = 120L

/** 轮盘淡出时长（交接第一段） */
private const val SLOT_FADE_MS = 200

/** 结果卡从轮盘接手时的翻正时长（交接第二段）。比常规 [REVEAL_MS] 短：前面已经看够了 */
private const val SLOT_FLIP_MS = 380

/**
 * 卡池里最多用几张**不同**的陪跑卡。
 *
 * 跟「卡带多长」是两件事（后者见 [SLOT_MIN_TRAVEL]）：卡带循环拼接，所以这里只
 * 决定**要预加载几张封面**。8 张在限并发 4 的情况下 1~2 秒就能全部进内存缓存，
 * 张数再往上加，「开转前等封面」的时间就会明显变长 —— 而真机反馈的「滚到后面
 * 就没卡片了」，根因正是预加载没跟上。
 */
private const val SLOT_POOL_MAX = 8

/**
 * 卡带至少要**走多少格**。
 *
 * 跟池子大小脱钩：池子只有 4 张时，3.6 秒走 4 格 ≈ 1 格/秒，看起来像卡住不动。
 * 不足就循环铺满到这一格数，转速才稳定（见 slotStrip 的说明）。
 */
private const val SLOT_MIN_TRAVEL = 13

/**
 * 中奖卡的落点在 `[SLOT_MIN_TRAVEL - jitter, SLOT_MIN_TRAVEL]` 之间随机，
 * jitter 取 `0..SLOT_WIN_JITTER`。让每次抽的滚动距离不太一样，节奏不至于机械。
 */
private const val SLOT_WIN_JITTER = 3

/**
 * 中奖卡**后面**再摆几张陪跑卡。
 *
 * 这三张很关键：一屏看得见三格，中奖卡居中时右侧要还有卡，看上去才是
 * 「停在卡带中间」。不给的话它永远是最右边那张 —— 用户会读成「总是抽最后一张」。
 */
private const val SLOT_TAIL = 3

/**
 * 陪跑卡最少几张才值得转轮盘。
 *
 * 少于这个数，即使循环铺满，重复的封面会连成「同一张卡在眼前晃」——
 * 那比直接翻牌更像 bug。此时自动退回经典翻牌。
 */
private const val SLOT_MIN_POOL = 4

/**
 * 开转前等封面进内存缓存的**上限**。
 *
 * 这里不是「尽力而为」：超时没凑齐就整场退回经典翻牌（见 `slotAbort`）。
 * 给足 8 秒是因为一次要取 10 张（`EmbyImageLoader` 限并发 4，服务端每张都要缩放），
 * 局域网通常 1~2 秒就完；慢网宁可多等这 1 秒，也别让用户看一串空框。
 */
private const val SLOT_PRELOAD_MS = 8_000L

/** 轮盘卡片的解码宽度。与取图时传的 `maxWidth` 同一档，命中缓存才不会解码两次 */
private const val SLOT_POSTER_WIDTH = 320

/**
 * 快阶段加在轮盘上的模糊半径。
 *
 * 只在「已经转起来」的那一段给：慢启动时糊着看等于一开始就失焦，反而不像加速。
 * API < 31 上 `Modifier.blur` 是空实现 —— 那时退回「靠速度本身表达」，
 * 不加假特效（比如叠半透明残影），免得在低端机上变成三倍绘制。
 */
private val SLOT_BLUR = 7.dp

/** 多抽时第一张卡的翻转时长 */
private const val GRID_REVEAL_BASE_MS = 620

/** 多抽时相邻两张卡的起翻间隔 */
private const val GRID_STAGGER_MS = 80

/** 「共鸣」回缩时长（蓄满 → 炸开之间的那一下吸气） */
private const val GATHER_MS = 160

/** 第二道「回声」冲击波的铺开时长（比第一道慢约一倍） */
private const val ECHO_MS = 1100

/**
 * @param cards 抽到的卡；**空列表 = 还在取数**（只播「准备中」的法阵，不揭晓）
 * @param rollId 每次抽取自增。**动画重启只认它**：同一个条目重抽也可能再抽回来，
 *               用 `item.Id` 当 key 的话「又抽到上一条」时动画不会重播，看起来像没反应。
 * @param resolveDetail 点上某张小卡时补一次详情（列表接口不保证带 `Overview`）。
 *                      null = 用列表里的数据直接展示。失败请返回原卡片而不是抛异常。
 * @param style 单抽的揭晓动画形态；多抽一律走经典翻牌（见 [EmbyGachaStyle]）。
 * @param reelCards 滚动卡带的**陪跑卡**：同一次取数里没被抽中的那些条目。
 *                  为空 / 太少时自动退回经典翻牌 —— 只有一张卡的轮盘转不起来。
 *                  ⚠️ 这是「多拿一份数据」，不是「多打一次网络」：调用方本来就是按
 *                  20 条取的池子，只用 1 条剩下的全丢了，这里只是把剩下的接过来。
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
  style: EmbyGachaStyle = EmbyGachaStyle.CLASSIC,
  reelCards: List<GachaCard> = emptyList(),
) {
  val count = cards.size
  /** 中奖卡（只有单抽会给出来，多抽时为 null） */
  val drawnCard = cards.singleOrNull()
  /**
   * 陪跑卡**候选**：去打散，但**不铺满**。
   *
   * 它只决定「要不要转轮盘」；真正上轮盘的卡带要等封面全部到位才生成（见 [reelStrip]）。
   * 用 `Random(rollId)` 定种子：重抽时顺序会变（否则每次都是同一排卡停在同一个位置），
   * 同一次抽取内重组多少次都一样。
   */
  val slotCandidates = remember(rollId, cards, reelCards) {
    val drawn = cards.singleOrNull() ?: return@remember emptyList()
    (reelCards.ifEmpty { cards })
      .filter { it.item.Id != drawn.item.Id }
      .distinctBy { it.item.Id ?: it.title }
      .shuffled(Random(rollId))
      // 只留这么几张去预加载：卡带本来就会循环拼接，多用几张并不会显得更热闹，
      // 只会把「开转前等封面」的时间拉长（真机反馈的根因就在这）。
      .take(SLOT_POOL_MAX)
  }
  // 陪跑卡少于 4 张就别转轮盘了：两三个格子来回晃，比直接翻牌更像 bug
  val slotMode = style == EmbyGachaStyle.SLOT && count == 1 &&
    slotCandidates.size >= SLOT_MIN_POOL
  /**
   * 真正铺在轮盘上的卡带。**预加载完成并校验之后才填**（空 = 还没到 / 已放弃）。
   *
   * ⚠️ 它不能是 `remember` 直接算出来的常量：卡带里的每一张都必须**已经在内存缓存里**，
   * 否则滚动时半路开始出现空框 —— 真机反馈「滚着滚着就没了，然后在一个空白的地方
   * 选中了一张卡」就是这么来的（预加载 3 秒超时就带着一堆没下完的图开转了）。
   * 现在等预加载结束、按 [EmbyImageLoader.peek] 逐个核对，缺图的一律不上轮盘。
   */
  var reelStrip by remember(rollId) { mutableStateOf(emptyList<GachaCard>()) }
  /** 封面凑不齐（服务器慢 / 某张挂了）⇒ 放弃轮盘，退回经典翻牌 */
  var slotAbort by remember(rollId) { mutableStateOf(false) }

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
  /** 轮盘当前位置（单位 = 卡片序号，0 = 第 1 张停在中间）。 */
  val reel = remember { Animatable(0f) }
  /** 停稳那一下的高光（0→1→0），只用来让中奖卡「咬合」一下，不再闪白整屏。 */
  val land = remember { Animatable(0f) }
  /**
   * 停稳后的**一次性**高光扫过（0→1，不回头）。
   *
   * 为什么不复用 [land]：land 是 0→1→0 的脉冲，拿它驱动扫光会「扫到一半退回来」，
   * 看着像倒放。扫光是单程的，必须有自己的进度。
   */
  val sweep = remember { Animatable(0f) }
  /**
   * 取景框的**锁定度**（0→1，**不回退**）。
   *
   * 与 [land] 脉冲分开：脉冲要回 0（只闪一下），锁定得留在 1 ——
   * 滚动过程中画面上只有准星、没有框；停稳那一下框才「对焦」锁上并一直留着，
   * 直到随轮盘一起淡出。用一个脉冲兼任「出现 + 闪亮」就会闪完又消失。
   */
  val lock = remember { Animatable(0f) }
  /** 轮盘淡出（0 = 还在轮盘，1 = 已换成结果卡）。 */
  val reelFade = remember { Animatable(0f) }
  /**
   * 快阶段模糊半径（dp，0 = 不糊）。
   *
   * 用普通 State 而不是每帧插值的 Animatable：模糊只在这一段**开关两次**，
   * 若按帧取整条渐变，整棵轮盘每帧都要重组一次 —— 收益远小于代价。
   */
  var slotBlur by remember { mutableStateOf(0f) }
  /**
   * 轮盘是否已经退场。
   *
   * 淡出结束后必须**从组合里删掉**：留着的话 16 张海报会一直挂在树上，
   * 后面结果卡的呼吸光晕每帧重组都会把它们量一遍 —— 白费电。
   */
  var reelFinished by remember { mutableStateOf(false) }
  /**
   * 「共鸣」（v202）：蓄能打满之后、白光爆开之前的**那一下回缩**。
   *
   * 0 → 1 的过程里法阵整体向内收（半径 ×0.80、刻度线变短、中心核压小），
   * 收到位的一瞬间才炸开。加这一段是因为原来「蓄能拉满 → 直接闪白」太直 ——
   * 蓄能段走完时画面已经是最亮的一张，再闪白只是「亮上更亮」，看不出「要放了」。
   * 先收一下再炸，观感上有「吸一口气」的蓄势感，总时长只多 [GATHER_MS]（160ms），
   * 不做成大幅缩放/旋转，避免变成放烟花。
   */
  val gather = remember { Animatable(0f) }
  /** 第二道「回声」冲击波（见 [drawBurstRays] 的 echo 说明）。 */
  val echo = remember { Animatable(0f) }
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
  // 停稳的那一下要「叮」一下：视觉之外必须给触感，否则静音环境下只剩「画面停了」
  val haptic = LocalHapticFeedback.current

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

  // ── 重抽时「法阵闪回 0」的修复（v202）──
  //
  // 时序：`rollGacha` 先把 `gachaCards` 清空 → 本组件的 `count` 变 0 →
  // `LaunchedEffect(rollId, count)` 抢先重跑，而首行 `if (cards.isEmpty()) return` 让
  // **六个 Animatable 的 snapTo(0) 全被跳过**（idle 也在下面被 restart）。
  // 等新数据回来、effect 再跑一次时才 snapTo —— 此刻画面上法阵早已停在原来的角度，
  // 用户看到的就是刻度环「亮一下、转回去、重新转」。
  // 修法：在**组合期**发现「卡已清空但旧批的进度还在」就把进度归零。
  // 组合期改 Animatable 只能 `snapTo`（挂起函数），所以包一层 `LaunchedEffect(Unit)`；
  // 判据用 `phase != Awaiting`，只在「Card/Burst 态遇到清空」时触发一次，不会每帧重来。
  if (cards.isEmpty() && phase != GachaPhase.Awaiting) {
    LaunchedEffect(rollId) {
      charge.snapTo(0f)
      flash.snapTo(0f)
      shock.snapTo(0f)
      flip.snapTo(0f)
      grid.snapTo(0f)
      detailIn.snapTo(0f)
      gather.snapTo(0f)
      echo.snapTo(0f)
      reel.snapTo(0f)
      land.snapTo(0f)
      sweep.snapTo(0f)
      lock.snapTo(0f)
      reelFade.snapTo(0f)
      slotBlur = 0f
      reelFinished = false
      focused = null
      focusedDetail = null
      detailLoading = false
      // 归零后 idle 的接力点才有意义（下面 effect 里读到的才是「从头开始的那圈」）
      spinBase = 0f
      phase = GachaPhase.Awaiting
    }
  }

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
    gather.snapTo(0f)
    echo.snapTo(0f)
    reel.snapTo(0f)
    land.snapTo(0f)
    sweep.snapTo(0f)
    lock.snapTo(0f)
    reelFade.snapTo(0f)
    slotBlur = 0f
    reelFinished = false
    focused = null
    focusedDetail = null

    // 接力：把等待期法阵停下的位置接过来，蓄能从这里继续（见 spinBase 的说明）
    spinBase = idle.value * 0.4f
    phase = GachaPhase.Charging
    /**
     * 轮盘开转之前必须把**所有封面都拿进内存缓存**，否则转起来是一排空框。
     *
     * 为什么现在就发：蓄能段有 [ROLL_MS]（1.25s）可以重叠，等到要转的时候再取就是干等。
     *
     * ⚠️ 原来是 `withTimeoutOrNull(3000)` **尽力而为**：3 秒一到就带着一堆没下完的图
     * 开转 —— 真机现象正是「滚着滚着就没了，最后在一个空白的地方选中了一张卡」。
     * 用户的要求本来就是「确保所有卡片的封面都已经加载完」，所以这里给它足量时间
     * （[SLOT_PRELOAD_MS]），并且**拿不到就退回经典翻牌**，绝不让空框上轮盘。
     */
    val preload = if (slotMode) {
      async {
        withTimeoutOrNull(SLOT_PRELOAD_MS) {
          preloadGachaPosters((slotCandidates + listOfNotNull(drawnCard)).mapNotNull { it.posterUrl })
        }
      }
    } else {
      null
    }
    charge.animateTo(1f, tween(ROLL_MS, easing = FastOutSlowInEasing))

    // ── 共鸣：蓄满后先向内收一下（法阵半径缩到 0.80、中心核压小）──
    // 收的过程本身很慢（EaseIn），收到位才炸。只多 160ms，但「亮 → 收 → 炸」
    // 三个动作读得出来，比「亮 → 更亮 → 炸」有层次。
    gather.animateTo(1f, tween(GATHER_MS, easing = FastOutLinearInEasing))

    // ── 先把封面等齐、核对完，再炸开 ──
    //
    // ⚠️⚠️ 顺序很关键：等待必须放在**闪白之前**。
    // 放到闪白之后的话，等待期画面已经是黑的（法阵在 Burst 就退场了、卡带还没出来），
    // 网络慢时用户会盯着黑屏好几秒。放在这里，等待期画面还是转着的法阵，
    // 文案「祈愿中…」，等图到位才炸开 —— 观感上就是「蓄力久了点」，不是「卡死了」。
    //
    // 另外这段等待通常为零：预加载从蓄能的 1.25 秒之前就已经在并行跑了。
    var slotWinIndex = -1
    if (slotMode) {
      preload?.await()
      // 只有**真的进了内存缓存**的卡才允许上轮盘。用 `peek` 而不是预加载的返回值：
      // 缓存才是 `EmbyImage` 第一帧能直接拿到图的唯一依据。
      // 缺任何一张（包括中奖卡自己）就整场退回经典翻牌 —— 一排空框比不做轮盘难看十倍，
      // 也正是真机反馈「滚到后面没卡片了、在一个虚空位置选中一张卡」的成因。
      val drawn = drawnCard
      val readyCards = slotCandidates.filter { EmbyImageLoader.peek(it.posterUrl) != null }
      val drawnReady = drawn != null && EmbyImageLoader.peek(drawn.posterUrl) != null
      if (drawn == null || readyCards.size < SLOT_MIN_POOL || !drawnReady) {
        // 凑不齐：标记退场，下面走经典翻牌
        slotAbort = true
      } else {
        // 中奖卡的格位由卡带自己决定（随机），不再恒等于末位
        val (strip, winIndex) = buildSlotStrip(readyCards, drawn, rollId)
        reelStrip = strip
        slotWinIndex = winIndex
      }
    }

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
    // 回声波（v202）：第二道慢一倍、细一半的冲击波，跟着第一道一起铺开。
    // 只有这一层薄薄的回声，不加第二组放射线 —— 加了就成烟花了。
    launch {
      echo.animateTo(1f, tween(ECHO_MS, easing = LinearOutSlowInEasing))
    }
    if (slotWinIndex >= 0 && reelStrip.isNotEmpty()) {
      // ── 滚动揭晓：横向卡带 ──
      // 轮盘阶段 flip 一直是 0：单抽那张大卡此时侧立着（rotationY=-82°、alpha=0），
      // 屏幕上是卡带；停稳之后才把 flip 从 0 推到 1，让结果卡翻正接过去。
      // 停的格位 = 卡带自己给的下标（随机），不是 lastIndex —— 见 buildSlotStrip。
      val last = slotWinIndex.toFloat()
      // 模糊只在「真的转起来了」那段给：慢启动阶段糊着看等于一开始就失焦。
      // 两次赋值就够（开 / 关），不按帧插值 —— 见 slotBlur 的说明。
      launch {
        delay((SLOT_SPIN_MS * 0.26f).toLong())
        slotBlur = SLOT_BLUR.value
      }
      launch {
        delay((SLOT_SPIN_MS * 0.80f).toLong())
        slotBlur = 0f
      }
      // 棘爪回弹（v216）：滚动 + 过冲 + 归位**画在同一条 keyframes 里**（见 slotSpinSpec）——
      // 先带着惯性冲过齿位 SLOT_OVERSHOOT，再柔和地滑回落点，中间没有「停住再起」的接缝。
      // ⚠️ 别拆成两个 `animateTo`：那样两段之间会空一拍，真机读作「僵一下、然后瞬间弹回去」。
      // ⚠️ 过冲段占用的那点空间靠卡带末尾的 SLOT_TAIL 张陪跑卡兜住（见 buildSlotStrip）。
      val notch = last + SLOT_OVERSHOOT
      reel.animateTo(last, slotSpinSpec(notch, last))
      slotBlur = 0f
      // ── 停稳：三件事一起发生 ──
      // 注意时序：这一整段都在**回拉结束之后**才触发 —— 观感上「咔」的那一声应该落在
      // 卡片归位的那一刻，而不是刚冲过头的瞬间（那一下卡片还偏着，框就亮了会像错位）。
      // 1. 中奖框的描边「咬合」（land 脉冲 0→1→0）+ 触感反馈；
      // 2. 框内一道高光自左向右扫过（sweep 单程 0→1）—— 扫光是一次性的，
      //    不能挂在 land 上，否则会「扫一半退回来」；
      // 3. 一圈柔光从中奖卡背后散开（也由 sweep 驱动，是「叮」的那一下。
      //    这里不再闪白整屏：上面炸开时已经闪过一次，连闪两次会显得廉价）。
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      launch {
        sweep.animateTo(1f, tween(SLOT_SWEEP_MS, easing = LinearOutSlowInEasing))
      }
      // 取景框「对焦」锁上：与脉冲并行，不等它 —— 等它的话框会晚 150ms 才出现，
      // 那一下的空档正好是最该「啪」的时候
      launch {
        lock.animateTo(1f, tween(SLOT_LOCK_MS, easing = FastOutSlowInEasing))
      }
      land.animateTo(1f, tween(90, easing = LinearOutSlowInEasing))
      // 回落只用 320ms（不是 520）：这条尾段的亮度本来就很淡，拖长只是把交接推后，
      // 用户盯着一个「已经停稳」的框干等，比什么都难受
      land.animateTo(0f, tween(320, easing = LinearEasing))
      // 站住一小会儿让人看清停在哪一张，再交接
      delay(SLOT_HOLD_MS)
      // 交接：先把轮盘淡掉，再让结果卡翻正（两段**串行**）。
      // 不能像原来那样 `flip.snapTo(1f)` —— 那一下结果卡是**瞬间出现**的，
      // 观感上就是「轮盘被一张大卡砸掉」，白瞎了前面 3.6 秒的滚动。
      // 也不并发：轮盘那张卡只有屏宽 30%、结果卡 88%，两张同时在场会看出尺寸跳变；
      // 串起来就只是「轮盘退场 → 结果卡登场」，中间那点空档正好给翻牌一个起手。
      reelFade.animateTo(1f, tween(SLOT_FADE_MS, easing = LinearEasing))
      reelFinished = true
      flip.animateTo(1f, tween(SLOT_FLIP_MS, easing = FastOutSlowInEasing))
    } else if (count == 1) {
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
          // 「共鸣」只影响画法阵的**尺寸**，不影响它的进度 p ——
          // 转到的角度必须连续（见 spinBase），只有半径 / 刻度长度会收回来。
          drawSummonCircle(
            center = center,
            base = base,
            p = p,
            accent = GachaInk.Spark,
            gather = gather.value,
          )
        }
        val s = shock.value
        // 只在 0→1 的进行中画：跑完后 shock 停在 1，再画一遍只会是一堆 alpha=0 的
        // 路径（白算 20 个三角形 + 3 个圈），没有必要
        if (s > 0f && s < 1f) {
          drawBurstRays(center = center, base = base, s = s, accent = bestRarity.accent)
        }
        // 回声波：第二道细而慢的冲击波。不画放射线，只画圈 —— 多一层薄环让
        // 「炸开」有余韵，而不会变成连续两轮烟花。
        val e = echo.value
        if (e > 0f && e < 1f) {
          val fade = (1f - e).coerceIn(0f, 1f)
          for (i in 0 until 2) {
            drawCircle(
              color = bestRarity.accent.copy(alpha = (0.22f - i * 0.09f) * fade),
              radius = base * (0.10f + 0.62f * e) * (1f + i * 0.16f),
              center = center,
              style = Stroke(width = (1.6f - i * 0.6f).dp.toPx()),
            )
          }
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
        // 3.0 滚动卡带（只在「单抽 + 设置选了滚动揭晓」时）。画在结果卡**下面**：
        // 停稳后结果卡淡入盖住它，不需要再判断谁在上层。
        if (slotMode && !slotAbort && reelStrip.isNotEmpty() && !reelFinished) {
          GachaSlotReel(
            strip = reelStrip,
            // ⚠️ 三个会动的量一律传 lambda，不传 `xxx.value`：
            //   在调用处读 .value 等于在组合期订阅，轮盘每帧都要重组整条卡带
            //   （16 张海报 + 16 个图片节点）；传 lambda 后只在绘制/图层阶段读，
            //   快照系统只失效那一层。见 GachaSlotReel 的说明。
            position = { reel.value },
            blurDp = slotBlur,
            accent = bestRarity.accent,
            land = { land.value },
            sweep = { sweep.value },
            lock = { lock.value },
            pulse = { idle.value },
            fade = { reelFade.value },
          )
        }
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
            //
            // ⚠️ 必须 `remember(cards)`：这里是 `BoxWithConstraints` 的 content lambda，
            //    它在**测量阶段**执行，外层任何状态变化（单抽的 detailIn、详情层的
            //    focused、底栏的 actionsAlpha…）都会把它重跑一遍。而 gachaCodeOf 里
            //    是「一次全角归一（逐字符遍历）+ 最多两次正则」× 10 张 —— 不 remember
            //    就等于每次重组白跑 10 遍字符串归一，纯浪费（v202 实测发现）。
            val codes = remember(cards) { cards.map { gachaCodeOf(it.title) } }
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
              // 整批起播：第 1 张起播，其余按揭晓顺序排队（不打乱 —— 顺序就是刚看到的顺序）
              //
              // ⚠️ 文案是「队列播放」不是「全部入队」（v202）：`launchPlaylist` 起的是
              //    **新**播放列表，原有队列会被整批替换。「入队」会让人以为在旧队列后面
              //    接着排，名不副实。
              onClick = { onPlayAll(cards) },
              enabled = actionsEnabled,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = GachaInk.TextPrimary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Icon(
                // AutoMirrored 版：RTL 下箭头方向要跟着翻，非镜像版在阿拉伯语等
                // RTL 语言里箭头会反着指（虽然本项目只有中文，但一条 import 的事）
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.size(6.dp))
              Text("队列播放", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
      //
      // 文案跟着共鸣走（v202）：蓄满收那一下写「共鸣」，炸开前的那 160ms 是有内容的，
      // 一直写「祈愿中…」会让人以为卡住了。共鸣结束就进 Burst，文字随即切到「本次抽到」。
      Text(
        text = when (phase) {
          GachaPhase.Awaiting -> "正在准备卡池…"
          GachaPhase.Charging ->
            if (gather.value > 0.01f) {
              "共鸣 · 即将揭晓"
            } else if (count > 1) {
              "祈愿中… · $count 连"
            } else {
              "祈愿中…"
            }
          GachaPhase.Burst, GachaPhase.Card ->
            when {
              // 滚动揭晓在 Burst 阶段还在转（轮盘是 Burst 一起开始的），
              // 这时候写「本次抽到」是**提前宣布结果** —— 明明还没停
              slotMode && !reelFinished -> "祈愿中…"
              count > 1 -> "本次抽到 $count 张"
              else -> "本次抽到"
            }
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
 * 滚动揭晓的横向卡带。
 *
 * ## 会动的量一律传 lambda，在图层 / 绘制阶段读
 * 轮盘每秒要跑好几张卡。位移若当普通 `Float` 参数传（在调用处读 `.value`），
 * 等于在**组合期**订阅它 —— 每帧重组整条卡带（16 张卡 + 16 个图片节点）。
 * 所以 [position] / [land] / [sweep] / [fade] 全部传 `() -> Float`，只在
 * `graphicsLayer { }` 或 `Canvas` 的绘制块里读：快照系统只让那一层失效，
 * 重组一次都不发生。与 [GachaMiniCard] 的 `progress: () -> Float` 同一口径。
 *
 * ## 视觉由后到前四层
 * 1. **卡带**：一行等宽卡片，整体左移 `位置 × 步长`；
 * 2. **两侧压暗**：从窗口边缘向外渐黑，把注意力收到中间那一格；
 * 3. **准星**：一条竖直中线，只在**框外**上下各露 40dp（框内留一道 0.07 的痕把两截
 *    连起来）。**不贯穿整屏** —— 亮线切到顶上那行字、再从海报脸上划过去，
 *    读起来是划痕不是准星。
 * 4. **取景框**：停稳时才出现，只画**四个角**（L 形括号），不画整圈。
 *    整圈描边（尤其再套一层白内描边）像「选中态」，把海报压得很闷；
 *    四角留出中间大片空隙，海报不被框住。锁定瞬间从 1.12 倍收拢到 1.0（对焦）。
 *
 * 停稳特效：[land] 脉冲只负责「亮一下」，[sweep] 单程负责扫光 + 一片中心柔光 ——
 * 柔光用径向渐变，**不**再套一层矩形描边（双层矩形是上一版最丑的地方）。
 */
@Composable
private fun GachaSlotReel(
  strip: List<GachaCard>,
  position: () -> Float,
  blurDp: Float,
  accent: Color,
  /** 停稳脉冲 0→1→0，只用来「亮一下」 */
  land: () -> Float,
  /** 停稳后的单程扫光 0→1 */
  sweep: () -> Float,
  /** 取景框的锁定度 0→1（滚动时 0 = 没有框，停稳才出现且**不回退**） */
  lock: () -> Float,
  /**
   * 常驻循环脉动 0→1（接 `rememberInfiniteTransition`，2.8 秒一圈）。
   *
   * 停稳之后画面若是死的，那几秒就很呆（用户原话）。这里用它驱动三样东西一起
   * 缓慢起伏：取景框的缩放与亮度、中奖卡背后的柔光、准星上那颗滑动的扫描点。
   */
  pulse: () -> Float,
  /** 0 = 还在轮盘；1 = 已完全交给结果卡（同时整体放大 10%，做出「推近」的纵深） */
  fade: () -> Float,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // 卡宽按可用宽度取 30%：手机竖屏约 3 张满屏、平板/横屏自动变大，
    // 封顶 170dp 是为了平板上别把一张卡放大成「结果卡」而看不出在滚动。
    val cardW = minOf(maxWidth * 0.30f, 170.dp)
    val cardH = cardW * 1.5f
    val gap = 12.dp
    val step = cardW + gap
    // 第 0 张正好落在正中：整条卡带的起点要往右推半个「可用宽 - 卡宽」
    val startPx = with(LocalDensity.current) { ((maxWidth - cardW) / 2).toPx() }
    val stepPx = with(LocalDensity.current) { step.toPx() }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          alpha = 1f - fade()
          // 交接时整体推近：轮盘「退后」、结果卡「上前」，两件事一起做才有纵深。
          // 只放大不动画位置 —— 位置一动，中奖卡就和结果卡对不上了。
          val s = 1f + 0.10f * fade()
          scaleX = s
          scaleY = s
        },
    ) {
      // ── 1. 卡带（会滚动的那一层）──
      //
      // ⚠️⚠️⚠️ 这里**绝对不能**写成「一条 Row + graphicsLayer 整体平移」。
      //
      // Row 的布局宽受父约束（屏宽 ~400dp）压制，它的绘制图层边界也只跟着一屏；
      // 第 4 张之后的内容落在图层边界之外 —— 只要这条链上出现任何一次离屏渲染
      // （`blur` / `alpha < 1` / `scale != 1`，我们三样都有），超出部分的绘制就整段
      // 被丢掉。真机症状正是：**前三张滚得好好的，滚到后面卡带没了，然后在中央
      // 一块空白处锁定了一张卡**。（`requiredWidth` 试过，不保险。）
      //
      // 现在改成**逐张卡平移自己的节点**：每个节点的 bounds 就是卡片本身，
      // 永远不存在「内容溢出自身边界」，父级怎么离屏渲染都不会少一张。
      // 用 lambda 版 `offset { }`：只在布局的放置阶段读 [position]，不触发重组。
      //
      // blur 仍然挂在**屏宽**的这层 Box 上（纹理刚好一屏）；卡片在屏内时正常渲染。
      Box(
        modifier = Modifier
          .fillMaxSize()
          .clipToBounds()
          .blur(blurDp.dp),
      ) {
        strip.forEachIndexed { index, card ->
          // 卡位必须**先有实体、再等封面**：`EmbyImage` 没图时只铺一层
          // `surfaceVariant`（暗色主题下几乎与黑底同色），在轮盘上看起来就是
          // 「虚空里一个位置」。这里给它一块卡底 + 一层画在**最上面**的描边。
          val shape = RoundedCornerShape(16.dp)
          Box(
            modifier = Modifier
              // CenterStart = 垂直居中、水平贴左；再用 offset 把这张卡放到它的格位上。
              // 第 0 张居中 ⇒ 起点往右推半个「可用宽 - 卡宽」。
              .align(Alignment.CenterStart)
              .offset {
                IntOffset(
                  x = (startPx + index * stepPx - position() * stepPx).roundToInt(),
                  y = 0,
                )
              }
              .size(width = cardW, height = cardH)
              .clip(shape)
              .background(GachaInk.Card),
          ) {
              EmbyImage(
                url = card.posterUrl,
                contentDescription = null,
                fallbackUrl = card.backdropUrl,
                maxWidth = SLOT_POSTER_WIDTH,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
              // ⚠️ 描边单独叠一层：`EmbyImage` 内部会把 `surfaceVariant` 底铺在
              // 传入 modifier 的**后面**（draw 链上更靠内），直接给它 `.border()`
              // 会被那层底色盖掉
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
              )
            }
          }
        }

      // 覆盖层：两侧压暗 + 准星 + 取景框 + 停稳特效
      Canvas(modifier = Modifier.fillMaxSize()) {
        // ⚠️ 动效量都在**绘制阶段**读（不是组合期），只失效这一层绘制
        val landV = land()
        val sweepV = sweep()
        val lockV = lock()
        // 常驻呼吸 0→1→0：停稳之后画面不能是死的（用户反馈「停住那几秒很呆滞」）。
        // 用 `sin` 把循环进度掰成平滑的起伏，三处视觉共用同一个相位，节奏才统一。
        val breathP = pulse()
        val breathe = ((sin(breathP * 2.0 * PI).toFloat() + 1f) / 2f).coerceIn(0f, 1f)

        val winW = cardW.toPx()
        val winH = cardH.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val cardL = cx - winW / 2f
        val cardT = cy - winH / 2f
        val cardR = cx + winW / 2f
        val cardB = cy + winH / 2f
        val vault = GachaInk.Vault

        // ── 两侧由外向内«化开»：外侧几乎全黑，到窗口边缘刚好透明 ──
        val dimL = cardL - 6.dp.toPx()
        val dimR = cardR + 6.dp.toPx()
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(vault.copy(alpha = 0.97f), Color.Transparent),
            startX = 0f,
            endX = dimL,
          ),
          topLeft = Offset(0f, 0f),
          size = Size(dimL.coerceAtLeast(0f), size.height),
        )
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, vault.copy(alpha = 0.97f)),
            startX = dimR,
            endX = size.width,
          ),
          topLeft = Offset(dimR, 0f),
          size = Size((size.width - dimR).coerceAtLeast(0f), size.height),
        )

        // ── 准星：一条竖直中线，在框外上下各露一段 ──
        // 只画框外两段 + 框内一道几乎看不见的痕：贯穿整屏的亮线会切到顶上
        // 那行「本次抽到」和关闭键，还会从海报脸上划过去，读起来像划痕而不是准星。
        val tickLen = 40.dp.toPx()
        val tickGap = 5.dp.toPx()
        val tickW = 1.2.dp.toPx()
        val tickCol = accent.copy(alpha = 0.45f + 0.40f * landV)
        // 上段：越靠框越亮（顶端化开）
        drawLine(
          brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, tickCol),
            startY = cardT - tickGap - tickLen,
            endY = cardT - tickGap,
          ),
          start = Offset(cx, cardT - tickGap - tickLen),
          end = Offset(cx, cardT - tickGap),
          strokeWidth = tickW,
          cap = StrokeCap.Round,
        )
        // 下段：从框沿往下渐隐
        drawLine(
          brush = Brush.verticalGradient(
            colors = listOf(tickCol, Color.Transparent),
            startY = cardB + tickGap,
            endY = cardB + tickGap + tickLen,
          ),
          start = Offset(cx, cardB + tickGap),
          end = Offset(cx, cardB + tickGap + tickLen),
          strokeWidth = tickW,
          cap = StrokeCap.Round,
        )
        // 框内那一道：0.07 的 alpha 只为了让上下两段「读成一条线」，
        // 真按 0.2 以上画就会糊在封面脸上
        drawLine(
          color = accent.copy(alpha = 0.07f),
          start = Offset(cx, cardT),
          end = Offset(cx, cardB),
          strokeWidth = 1.dp.toPx(),
        )

        // ── 取景框：只画四个角，不画整圈 ──
        // 整圈描边（尤其再套一层白色内描边）像「选中态 / UI 轮廓」，压得海报很闷；
        // 四角括号留出中间大片空隙，是取景器的语言，海报不被框住。
        // 滚动时 lock=0 ⇒ 框不出现，画面上只有准星 + 卡片飞过；停稳瞬间才「对焦」锁上。
        if (lockV > 0.01f) {
          val pad = 6.dp.toPx() + 4.dp.toPx() * landV
          // 锁定：从 1.12 倍收拢到 1.0（对焦那一下）；锁上之后继续跟着呼吸微微张缩，
          // 让「停住」不是定格 —— ±1.4% 刚好能看出来，多了会像在抖
          val lockScale = (1.12f - 0.12f * lockV) * (1f + 0.014f * breathe * lockV)
          val halfW = (winW / 2f + pad) * lockScale
          val halfH = (winH / 2f + pad) * lockScale
          val l = cx - halfW
          val t = cy - halfH
          val r = cx + halfW
          val b = cy + halfH
          val arm = minOf(halfW * 0.40f, 24.dp.toPx())
          // 呼吸也走亮度：0.82~1.0 之间，跟缩放同相位
          val frameAlpha = (0.55f + 0.45f * landV) * lockV * (0.82f + 0.18f * breathe)

          val frame = Path().apply {
            moveTo(l, t + arm); lineTo(l, t); lineTo(l + arm, t)
            moveTo(r - arm, t); lineTo(r, t); lineTo(r, t + arm)
            moveTo(r, b - arm); lineTo(r, b); lineTo(r - arm, b)
            moveTo(l + arm, b); lineTo(l, b); lineTo(l, b - arm)
          }
          // 先画一层发光的「影子」（同形状、粗、低 alpha），深色底上单根线太干
          drawPath(
            path = frame,
            color = accent.copy(alpha = 0.16f * frameAlpha),
            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
          drawPath(
            path = frame,
            color = accent.copy(alpha = frameAlpha),
            style = Stroke(
              width = 2.dp.toPx() + 1.dp.toPx() * landV,
              cap = StrokeCap.Round,
              join = StrokeJoin.Round,
            ),
          )

          // ── 锁定后的常驻动效 ──
          // a) 中奖卡背后一片缓慢明暗的柔光（与结果卡的呼吸光晕同语言）
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(
                accent.copy(alpha = 0.13f * lockV * (0.45f + 0.55f * breathe)),
                Color.Transparent,
              ),
              center = Offset(cx, cy),
              radius = winW * 1.05f,
            ),
            radius = winW * 1.05f,
            center = Offset(cx, cy),
          )
          // b) 准星上的扫描点：沿竖直中线匀速滑过（含框外两段），
          //    像读取光一样把「还活着」说清楚 —— 这条最能把呆滞感打掉
          val spanTop = cardT - tickGap - tickLen
          val spanBottom = cardB + tickGap + tickLen
          val dotY = spanTop + (spanBottom - spanTop) * breathP
          // 首尾各淡入淡出 1/6 行程：不然循环回到顶端时会「跳」一下，比不动更扎眼
          val edgeFade = ((breathP * 6f).coerceAtMost(1f) * ((1f - breathP) * 6f).coerceAtMost(1f))
          val dotA = 0.85f * lockV * edgeFade
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(accent.copy(alpha = 0.30f * dotA), Color.Transparent),
              center = Offset(cx, dotY),
              radius = 13.dp.toPx(),
            ),
            radius = 13.dp.toPx(),
            center = Offset(cx, dotY),
          )
          drawCircle(
            color = accent.copy(alpha = dotA),
            radius = 2.dp.toPx(),
            center = Offset(cx, dotY),
          )
        }

        // ── 停稳特效：中心柔光 + 框内斜向高光扫过 ──
        if (sweepV > 0f && sweepV < 1f) {
          val fade = 1f - sweepV
          // 一片从中奖卡背后散开的柔光，不做「再套一个矩形」的外扩描边 ——
          // 那种双层矩形正是上一版最难看的地方
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(accent.copy(alpha = 0.22f * fade), Color.Transparent),
              center = Offset(cx, cy),
              radius = winW * 0.95f,
            ),
            radius = winW * 0.95f,
            center = Offset(cx, cy),
          )
          // 卡片上自左向右扫过的一道斜向高光（单程，不回头 —— 见 sweep 的说明）
          clipRect(cardL, cardT, cardR, cardB) {
            val band = winW * 0.62f
            val x = cardL - band + (winW + band * 2f) * sweepV
            drawRect(
              brush = Brush.linearGradient(
                colors = listOf(
                  Color.Transparent,
                  Color.White.copy(alpha = 0.40f * fade),
                  Color.Transparent,
                ),
                start = Offset(x - band / 2f, cardT),
                end = Offset(x + band / 2f, cardB),
              ),
              topLeft = Offset(cardL, cardT),
              size = Size(winW, winH),
            )
          }
        }
      }
    }
  }
}

/**
 * 轮盘的速度曲线：**慢启动 → 匀速冲刺 → 长减速 → 冲过齿位 → 柔和归位**，交界处速度对齐。
 *
 * 为什么不是「一条 `FastOutSlowInEasing`」：那条曲线中段才最快，起步就明显在动，
 * 看起来像「被推了一把」而不是一点点上劲的那种。这里按速度分段：
 *
 * 设三段时长占比 t1 : t2 : t3 = 0.35 : 0.35 : 0.30，加速度段与减速段的距离
 * 各为 `v·t/2`、匀速段为 `v·t`（v = 峰值速度），解得
 * d1 : d2 : d3 = 0.259 : 0.519 : 0.222 —— 就在下面这三个点上。
 * 交界处只要两段末速/初速相等，画面就不会「顿一下」。
 *
 * ## 棘爪回弹为什么也画在同一段 keyframes 里（v216）
 * 做成「先 `animateTo(notch)` 停下、再 `animateTo(rest)` 拉回」时，两个 `animateTo`
 * 之间会空出**一拍**（前一段收尾的那帧 dt=0、后一段重新计时起步）—— 真机手感就是
 * 「过冲之后先僵一下、再瞬间弹回去」。合进同一条 keyframes 之后，减速段的尾巴直接
 * 接到回拉段的起手，中间没有接缝，位移才是一条连续的线。
 *
 * 参数：`peak` = 冲过头的终点（落点 + [SLOT_OVERSHOOT]），`rest` = 真正的落点。
 */
private fun slotSpinSpec(peak: Float, rest: Float): KeyframesSpec<Float> = keyframes {
  durationMillis = SLOT_SPIN_MS + SLOT_SETTLE_MS
  0f at 0 using FastOutLinearInEasing
  peak * 0.259f at (SLOT_SPIN_MS * 35 / 100) using LinearEasing
  peak * 0.778f at (SLOT_SPIN_MS * 70 / 100) using LinearOutSlowInEasing
  peak at SLOT_SPIN_MS using LinearOutSlowInEasing
  rest at (SLOT_SPIN_MS + SLOT_SETTLE_MS) using FastOutSlowInEasing
}

/**
 * 把「已验证有封面的陪跑卡」**循环**铺成一条卡带，并把中奖卡插在**随机格位**上。
 *
 * 返回 `(卡带, 中奖卡的下标)` —— 下标就是轮盘要停的位置，不再恒等于末位。
 *
 * ## 为什么中奖卡不能固定摆末位
 * 固定末位时，每次都是「滑到尽头才停」，停稳后中奖卡右侧**一片空白**（后面没卡了）。
 * 屏幕只看得见三格，右边空着就等于告诉用户「这是最后一张」—— 真机反馈就是
 * 「他总是抽最后一张卡」。另外滚动距离每次一样，节奏也很机械。
 *
 * 现在：中奖卡落在 `[MIN_TRAVEL - jitter, MIN_TRAVEL]` 之间随机的一格，
 * 后面再补 [SLOT_TAIL] 张陪跑卡 —— 停稳时左右都有卡，看上去就是「停在了中间」，
 * 而且每抽一次的滚动距离也不一样。
 *
 * **为什么要循环铺长**：池子只有 4 张时若原样摆上，3.6 秒只走 4 格（≈1 格/秒）——
 * 那不是「在转」，那是「在挪」。循环铺到十几格之后，转速只与格数有关。
 * 相邻不会撞同一张：`pool.size >= SLOT_MIN_POOL = 4`，取模不会连出同款。
 */
private fun buildSlotStrip(
  pool: List<GachaCard>,
  drawn: GachaCard,
  rollId: Int,
): Pair<List<GachaCard>, Int> {
  val cards = pool.take(SLOT_POOL_MAX)
  if (cards.isEmpty()) return listOf(drawn) to 0
  // 用 rollId 定种子：重抽时停的格位会变，同一次抽取内怎么重组都一样
  val jitter = Random(rollId * 31 + 7).nextInt(SLOT_WIN_JITTER + 1)
  val win = maxOf(SLOT_MIN_TRAVEL - jitter, cards.size)
  val before = List(win) { cards[it % cards.size] }
  // 中奖卡后面这几张要和前面连得上（接着 win 继续取模），否则会看出「接缝」
  val after = List(SLOT_TAIL) { cards[(it + win) % cards.size] }
  return (before + drawn + after) to win
}

/**
 * 把所有陪跑卡的封面**先取进内存**，轮盘开转之后才不会一格一格往外蹦空框。
 *
 * 走的是全项目统一的 [EmbyImageLoader]：同一 URL 的并发请求会合并，
 * 并发数由它内部的信号量压到 4 —— 这里不用自己限流。
 * 单张失败只当那一张不可用（调用方用 `peek` 核对后把它剔出卡带）。
 */
private suspend fun preloadGachaPosters(urls: List<String>) {
  if (urls.isEmpty()) return
  coroutineScope {
    urls.distinct()
      .map { url -> async { runCatching { EmbyImageLoader.load(url, SLOT_POSTER_WIDTH) } } }
      .awaitAll()
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
private fun DrawScope.drawSummonCircle(
  center: Offset,
  base: Float,
  p: Float,
  accent: Color,
  /** 0 = 正常尺寸，1 = 收到最小（见 [GATHER_MS] 的说明）。 */
  gather: Float = 0f,
) {
  val g = gather.coerceIn(0f, 1f)
  // 半径整体收 20%，刻度线按 (1 - 0.55g) 缩短，中心核压到 0.70 ——
  // 三处一起收才有「吸气」的体积感；只缩外圈会像镜头拉远。
  val shrink = 1f - 0.20f * g
  val outer = base * 0.29f * shrink
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
  // 刻度长度也参与回缩：共鸣时线头往回收，读起来像法阵被「吸」了一下
  val tickScale = 1f - 0.55f * g
  for (i in 0 until ticks) {
    val ang = (i * 360.0 / ticks + rot) * PI / 180.0
    val r1 = outer * 1.0f
    val r2 = r1 + (if (i % 5 == 0) 9f else 4f).dp.toPx() * tickScale
    drawLine(
      // 共鸣时光尘环也提一点亮度，配合「收」的动作（收的时候更亮，放开才炸）
      color = accent.copy(alpha = (0.14f + 0.34f * heighten + 0.18f * g).coerceIn(0f, 1f)),
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

  // 中心核：越抽越亮；共鸣时再压小一点，炸开瞬间它才是「从这一点炸出来」的
  val coreR = base * (0.06f + 0.10f * heighten) * (1f - 0.30f * g)
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
