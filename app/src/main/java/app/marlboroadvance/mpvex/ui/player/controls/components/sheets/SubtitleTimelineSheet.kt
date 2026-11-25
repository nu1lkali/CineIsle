package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTimeline
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.DraggablePanel
import app.marlboroadvance.mpvex.ui.player.controls.letterboxBars
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一条字幕文件的时间轴加载结果。 */
private sealed interface CueLoad {
  object Loading : CueLoad

  data class Ready(
    val cues: List<SubtitleCue>,
    val truncated: Boolean,
    /** 源文件里替换符的占比；> 0 说明这条字幕在上游就被吃过字符。 */
    val damagedRatio: Float = 0f,
    /** 越过门槛 ⇒ 换编码救不回来，只能如实告知。 */
    val damaged: Boolean = false,
  ) : CueLoad

  data class Failed(
    val reason: String,
  ) : CueLoad
}

/** 正在看的那条轨道此刻在播什么角色 —— 决定要不要高亮 + 用哪份延迟。 */
private enum class TrackRole { Primary, Secondary, Inactive }

/** 横屏悬浮面板的进场时长。出场稍短，收起来要显得利落。 */
private const val TIMELINE_PANEL_ENTER_MS = 240

/** 横屏悬浮面板的出场时长（播完才落 `Sheets.None`，否则动画从一开始就被扯断）。 */
private const val TIMELINE_PANEL_HIDE_MS = 190

/** 出场位移：略大于面板宽度上限（380dp），滑出屏幕右缘即彻底不可见。 */
private const val TIMELINE_PANEL_SLIDE_DP = 420f

/**
 * 竖屏「黑边停靠」可接受的最小高度（dp）。
 *
 * 停靠后这块高度还要装下标题行 + 轨道 chip + 搜索框（约 165dp），低于 240 就只剩
 * 一两行台词，与其勉强停靠不如退回大抽屉 —— 至少列表可用。
 */
private const val PORTRAIT_DOCK_MIN_DP = 240f

/**
 * 竖屏抽屉的不透明度。
 *
 * 留一点透明度，面板才不像一块实心板压上来：停靠黑边时背后是纯黑，观感更接近
 * 「黑边里长出来的」；退回大抽屉时背后是画面，也能隐约看见画面还在动。
 * 再低（< 0.8）正文对比度就撑不住了。
 */
private const val TIMELINE_SHEET_ALPHA = 0.88f

/**
 * 播放页「字幕时间线」面板。
 *
 * ## 它解决什么
 *
 * 播放中想按台词定位（「从这句开始对不上」「回去看那句怎么翻的」）时，进度条只能靠拖 ——
 * 没有参照物。这里把**当前已加载字幕**的整条时间轴列出来：点一句就跳过去，
 * 画面播到哪儿就高亮哪一句，并且自动把它滚进视野。
 *
 * ## 容器按朝向分两种（同一个面板，两种形态）
 *
 * · **竖屏** → 底部抽屉（[PlayerSheet]）。竖屏画面本来就窄，抽屉不挤压可用的画面宽度；
 *   抽屉高度只取约三分之二屏高，且 `scrimAlpha = 0` —— 遮罩不压暗画面（点空白仍可关闭）。
 * · **横屏** → 右侧悬浮面板（[DraggablePanel]），半透明、可横向拖动，**不盖住画面主体**，
 *   也不加遮罩：点时间线的同时还能看见画面在播什么，与「字幕延迟」面板同一种壳。
 *
 * 两种形态共用同一份 `header` / `body`，所以切主副轨、搜索、跟随、换算逻辑不会走岔。
 *
 * ## 与字幕轨道的联动
 *
 * · 主字幕（`sid`）与副字幕（`secondary-sid`）同时挂着时，顶部两个 chip 可切换
 *   **当前查看的是哪一条**；chip 上标出「主 / 副」，与字幕面板的口径一致。
 * · 正在看的那条**不是**主字幕时，header 出现「设为主字幕」—— 这才是真正改变播放状态的
 *   那一步，且只改 `sid`，不会把另一条顶掉（由 ViewModel 负责清冲突的 `secondary-sid`）。
 * · 高亮只对**真正在显示**的轨道生效。查看一条没被选中的轨道时，时间轴仍可用来定位，
 *   但不会画「正在播这一句」——那条字幕此刻根本不在画面上，画了就是撒谎。
 *
 * ## 跟随
 *
 * 与字幕实验室同一套口径：默认跟随，只在「当前句不在视野内」时才滚动（每句都居中的话
 * 画面会一直抖）；用户手动滑过列表后不会被他自己的滚动打断；搜索时自动停跟 ——
 * 搜台词是为了看别处，这时把列表拽回播放位置只会让人找不到刚搜到的结果。
 *
 * ## 时间基准
 *
 * 列表里的时间、点击跳转、高亮跟随**统一按「播放位置」**：字幕文件时间先过
 * `播放位置 = 文件时间 / sub-speed + sub-delay` 再显示 / 跳转。
 *
 * 所以调过字幕延迟之后，列表上的数字会和字幕文件里写的差那么多 —— 这是对的：
 * 用户要的是「跳到画面上哪一刻能看到这句」，不是「字幕文件第几秒」。反过来，
 * 只把跳转或只把显示换算过来都会自相矛盾。
 *
 * ⚠️ 跳转必须走 `exact`（见调用点 `seekTo(sec, exact = true)`）：普通 seek 默认落在
 * **关键帧**上，长 GOP 的片源会早好几秒 —— 那是「写着 2:03、点下去落在 1:56」的另一个来源，
 * 与字幕延迟无关。点台词要的是那一句，不是最近的关键帧。
 *
 * ## 降级
 *
 * 时间轴只能从**外挂字幕文件**读（内嵌在视频里的轨没有文件可读），所以：
 *  · 没有字幕轨 / 全是内嵌轨 / 字幕文件已被清理 / 格式解析不出条目 —— 各自给一句人话，
 *    并留一个「字幕列表」出口，而不是留一片空白。
 */
@Composable
fun SubtitleTimelineSheet(
  tracks: ImmutableList<TrackNode>,
  /** 当前主字幕（`sid`）轨号，0 = 没开 */
  primaryId: Int,
  /** 当前副字幕（`secondary-sid`）轨号，0 = 没有 */
  secondaryId: Int,
  /** 播放位置（秒）。mpv 的 `time-pos` 是整秒，够用于「哪一句在播」 */
  positionSeconds: Int,
  onSeekToMs: (Long) -> Unit,
  /** 把某条轨道切成主字幕（`sid`） */
  onPromoteToPrimary: (Int) -> Unit,
  /** 回字幕轨道面板 */
  onBackToTracks: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current

  // 只有外挂轨才有磁盘文件可读；内嵌轨（external=false）没有时间轴来源
  val viewable =
    remember(tracks) {
      tracks.filter { it.isSubtitle && it.external == true && !it.externalFilename.isNullOrBlank() }
    }

  // ── 当前挂在 mpv 上的字幕槽 ──
  //
  // ⚠️⚠️ 不能只信上层透传的 `primaryId` / `secondaryId`：那两个值来自 track-list 的**一次快照**，
  //   面板刚打开时它可能还没把 `selected` 报上来（读到 0）⇒ 这里会判定「这条轨没在播」，
  //   于是既不画高亮、也不跟随，列表就停在原地（现场：字幕已经在画面上换了好几句，
  //   面板却一直在老的那条）。直接问 mpv 的 `sid` / `secondary-sid` —— 那是权威值，
  //   字幕一挂上就有；读不到时再退回透传值。
  val sidFlow = remember { PlayerLib.propInt["sid"] }
  val secondarySidFlow = remember { PlayerLib.propInt["secondary-sid"] }
  val liveSid by sidFlow.collectAsState()
  val liveSecondarySid by secondarySidFlow.collectAsState()
  val activePrimaryId = liveSid?.takeIf { it > 0 } ?: primaryId
  val activeSecondaryId = liveSecondarySid?.takeIf { it > 0 } ?: secondaryId

  // 默认看「已经在播的那条」，其次副字幕，再次第一条可读的
  var viewedId by remember { mutableStateOf(0) }
  // 用户有没有自己在 chip 上挑过轨道。挑过就不再自动纠正 —— 否则他特意去看
  // 「一条没启用的轨」，会被立刻拽回主字幕。
  var userPicked by remember { mutableStateOf(false) }
  // 主 / 副槽里当前真正生效的那一条（有些片只有副槽挂着字幕）
  val effectiveId =
    listOf(activePrimaryId, activeSecondaryId)
      .firstOrNull { id -> id > 0 && viewable.any { it.id == id } }
  LaunchedEffect(viewable, activePrimaryId, activeSecondaryId) {
    when {
      // 看的那条已经不在可读列表里（文件被清理 / 轨被删）⇒ 重新挑
      viewable.none { it.id == viewedId } ->
        viewedId = effectiveId ?: viewable.firstOrNull()?.id ?: 0

      // 先挑了「第一条可读的轨」之后，主/副槽才报上来 ⇒ 必须跟上，否则会一直停在
      // 一条**未启用**的轨上（未启用就没有高亮，连「跟随中」都不出现）。
      // 只在用户没自己挑过轨时纠正。
      !userPicked && effectiveId != null && viewedId != effectiveId -> viewedId = effectiveId

      else -> Unit
    }
  }
  val viewed = viewable.firstOrNull { it.id == viewedId }

  // viewedId 还是 0（没挑出来）时不能落进 Primary —— 那会把一条根本不存在的轨当成「正在播」
  val role =
    when {
      viewed == null || viewedId <= 0 -> TrackRole.Inactive
      viewedId == activePrimaryId -> TrackRole.Primary
      viewedId == activeSecondaryId -> TrackRole.Secondary
      else -> TrackRole.Inactive
    }

  // 解析结果按「文件名」缓存在面板生命周期内：来回切主/副不该每次都重解析一遍
  // （一条 ASS 几千句，重解析是肉眼可见的卡顿）。关掉面板即释放。
  val cache = remember { mutableStateMapOf<String, CueLoad>() }
  val fileKey = viewed?.externalFilename
  LaunchedEffect(fileKey) {
    if (fileKey == null || cache.containsKey(fileKey)) return@LaunchedEffect
    cache[fileKey] = CueLoad.Loading
    cache[fileKey] = withContext(Dispatchers.IO) { loadCues(context, fileKey) }
  }
  val load = fileKey?.let { cache[it] }

  var query by remember { mutableStateOf("") }
  val tokens =
    remember(query) {
      query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

  val cues = (load as? CueLoad.Ready)?.cues.orEmpty()
  // 多词按 AND 组合，与字幕面板的筛选框同一口径：打「你 好」不会变成「你 或 好」
  val visible =
    remember(cues, tokens) {
      if (tokens.isEmpty()) {
        cues
      } else {
        cues.filter { cue -> tokens.all { it in cue.text.lowercase() } }
      }
    }

  // ── 时间基准（列表显示 / 点击跳转 / 跟随高亮共用这一套）──
  //
  // 字幕文件里的时间是「文件时间」，画面上看到它的时刻还要过 mpv 的两道换算：
  //     播放位置 = 文件时间 / sub-speed + sub-delay
  // （正的 sub-delay = 字幕整体延后显示；sub-speed > 1 = 字幕以更快的速度推进。）
  //
  // ⚠️⚠️ 旧版是「列表与跳转用文件时间、高亮用换算后的时间」—— 两边差了一个 delay，
  // 于是只要用户调过字幕延迟，就会出现「写着 2:03、点下去跳到 1:56」。
  // 现在三处都走下面这组换算（delay 为 0 / speed 为 1 时它就是恒等变换，没有副作用）。
  val primaryDelayFlow = remember { PlayerLib.propDouble["sub-delay"] }
  val secondaryDelayFlow = remember { PlayerLib.propDouble["secondary-sub-delay"] }
  val subSpeedFlow = remember { PlayerLib.propDouble["sub-speed"] }
  val primaryDelay by primaryDelayFlow.collectAsState()
  val secondaryDelay by secondaryDelayFlow.collectAsState()
  val subSpeedRaw by subSpeedFlow.collectAsState()

  // 没启用的轨道谈不上「显示延迟」，按文件时间原样用
  val delaySec =
    when (role) {
      TrackRole.Primary -> primaryDelay ?: 0.0
      TrackRole.Secondary -> secondaryDelay ?: 0.0
      TrackRole.Inactive -> 0.0
    }
  val speed = (subSpeedRaw ?: 1.0).takeIf { it in 0.1..10.0 } ?: 1.0
  val delayMs = (delaySec * 1000.0).toLong()
  /** 有没有偏移 / 变速：没有时列表直接显示文件时间，不额外拼串 */
  val shifted = delayMs != 0L || speed != 1.0

  fun fileToPlaybackMs(fileMs: Long): Long = (fileMs / speed + delayMs).toLong().coerceAtLeast(0L)

  // ── 播放位置：跟随与高亮的唯一依据 ──
  //
  // 自己在面板里订阅 mpv 的 `time-pos`，不只依赖上层透传的秒数：上层那个值是在
  // `PlayerSheets` 里读一次再传下来的，任何一次「该重组没重组」都会让这里停在旧值 ——
  // 表现就是「字幕明明已经在播，面板一动不动」。自己订阅之后，这个面板只认 mpv 的真实时间。
  val livePosFlow = remember { PlayerLib.propInt["time-pos"] }
  val livePos by livePosFlow.collectAsState()
  val currentSeconds = livePos ?: positionSeconds

  // 反算：当前播放位置对应字幕文件里的哪一刻（delay / speed 取默认值时就是恒等变换）
  val fileMs = ((currentSeconds * 1000L - delayMs) * speed).toLong()

  /** 真的正在播的那一句：严格落在 `[startMs, endMs)` 内才算。空隙里画面上没有台词，不画高亮。 */
  val activeIndex =
    remember(visible, fileMs, role) {
      if (role == TrackRole.Inactive || visible.isEmpty()) {
        -1
      } else {
        visible.indexOfFirst { fileMs >= it.startMs && fileMs < it.endMs }
      }
    }

  /**
   * 列表该停在哪一行（跟随锚点）。
   *
   * 与 [activeIndex] 分开是有意的：片头、静音段、两句之间的空隙里没有「正在播的那句」，
   * 但列表不能因此停在第 1 行不动 —— 那看起来就是「没跟随、没高亮」。
   * 这时退到「接下来这一句」（没有下一句就取最后一句），列表始终贴着播放位置。
   */
  val anchorIndex =
    remember(visible, fileMs, role, activeIndex) {
      when {
        role == TrackRole.Inactive || visible.isEmpty() -> -1
        activeIndex >= 0 -> activeIndex
        else -> {
          val next = visible.indexOfFirst { it.startMs > fileMs }
          if (next >= 0) next else visible.lastIndex
        }
      }
    }

  var follow by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()

  // 打开面板的第一件事：把列表直接落到播放位置。
  // 必须用 `scrollToItem`（立即落位）而不是 `animateScrollToItem`：首帧里列表还在测第一遍，
  // 动画滚动可能被随后的布局吞掉 —— 现场表现正是「面板停在第 1 行，看不到任何高亮」。
  var positioned by remember { mutableStateOf(false) }
  LaunchedEffect(anchorIndex, visible.size) {
    if (positioned || anchorIndex < 0) return@LaunchedEffect
    listState.scrollToItem(anchorIndex)
    positioned = true
  }

  // 之后跟着播放走：只有锚点跑出视野才滚（每句都居中会让画面一直抖）。
  // 四者缺一都会变成「我正翻列表，它自己往下跳」——
  //  · 首次定位还没落定（交给上一条 effect）
  //  · follow 被用户关掉
  //  · 用户正在滑动
  //  · 正在搜索（搜台词就是去看别处）
  LaunchedEffect(anchorIndex, follow, tokens, positioned) {
    if (!positioned || !follow || anchorIndex < 0 || tokens.isNotEmpty()) return@LaunchedEffect
    if (listState.isScrollInProgress) return@LaunchedEffect
    val first = listState.firstVisibleItemIndex
    // 「最后一条已经布好的行」的下标，比 first + size - 1 更稳（空列表时不会算出 first - 1）
    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
    // 提前一行就滚：等高亮贴到底边才滚，视觉上会「掉出去一下再跳回来」
    if (anchorIndex < first || anchorIndex >= last) {
      listState.animateScrollToItem(anchorIndex)
    }
  }

  val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

  // ── 竖屏：黑边停靠 ──
  //
  // 竖屏放横屏影片时画面只占屏幕中间一条（16:9 在 360×800dp 上只有约 202dp 高），
  // 上下各留一大截黑边。此时抽屉若还按屏高比例给（0.66 ≈ 528dp），必然压到画面 ——
  // 所以这里先**量出下黑边的高度，让面板整块停靠进去**，画面一点不挡。
  //
  // 三种情况下不硬停：
  //  · 读不到画幅（首帧前 `video-params/aspect` 为空）；
  //  · 用户手动缩放过画面（`video-zoom` / `video-pan-y` 非 0 ⇒ 画面不再是居中 fit，
  //    算出来的黑边是错的）；
  //  · 下黑边扣掉导航栏后不足 [PORTRAIT_DOCK_MIN_DP]，或输入法弹着（要让搜索框可用）。
  //   这些情况一律退回原来的 0.66 抽屉。
  val density = LocalDensity.current
  val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
  val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
  // 画幅与缩放只在面板打开时取一次：一次播放里不会变，没必要每秒订阅
  val videoAspect = remember { PlayerLib.getPropertyDouble("video-params/aspect") }
  val pictureTransformed =
    remember {
      fun nonzero(key: String): Boolean = (PlayerLib.getPropertyDouble(key) ?: 0.0).let { it > 1e-3 || it < -1e-3 }
      nonzero("video-zoom") || nonzero("video-pan-y")
    }
  val imeVisible = WindowInsets.ime.getBottom(density) > 0
  val navigationBarDp = with(density) { WindowInsets.systemBars.getBottom(density).toDp().value }
  val dockHeightDp =
    if (pictureTransformed || imeVisible) {
      null
    } else {
      letterboxBars(videoAspect, screenWidthDp, screenHeightDp)
        ?.bottom
        ?.minus(navigationBarDp)
        ?.takeIf { it >= PORTRAIT_DOCK_MIN_DP }
    }

  // ── 横屏悬浮面板的进出场 ──
  //
  // `PlayerSheets` 是个裸 `when`，状态一落 `Sheets.None` 这棵子树立刻被摘掉 ——
  // 面板会「啪」一下消失。所以关面板分两步：先播滑出 + 淡出，**播完**再落状态。
  // [slide] 1 = 完全收起（在屏幕右缘外），0 = 完全展开。
  val panelScope = rememberCoroutineScope()
  val slide = remember { Animatable(if (isPortrait) 0f else 1f) }
  var dismissing by remember { mutableStateOf(false) }

  /** 关闭面板的唯一出口：竖屏交给 `PlayerSheet`，横屏先播动画。 */
  val closePanel: () -> Unit = {
    when {
      isPortrait -> onDismissRequest()
      dismissing -> Unit
      else -> {
        dismissing = true
        panelScope.launch {
          slide.animateTo(1f, tween(TIMELINE_PANEL_HIDE_MS, easing = FastOutLinearInEasing))
          onDismissRequest()
        }
      }
    }
  }

  if (!isPortrait) {
    LaunchedEffect(Unit) {
      slide.animateTo(0f, tween(TIMELINE_PANEL_ENTER_MS, easing = FastOutSlowInEasing))
    }
  }

  // 标题里要显示「看的是哪条」；getTrackTitle 内部走 stringResource，先取出来再拼串
  val viewedTitle = viewed?.let { getTrackTitle(it) } ?: "未选择字幕"

  val header: @Composable () -> Unit = {
    // ── 标题 / 跟随 / 返回 ──
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = MaterialTheme.spacing.medium, end = MaterialTheme.spacing.small, top = MaterialTheme.spacing.small),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "字幕时间线",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = buildString {
            append(viewedTitle)
            if (cues.isNotEmpty()) append(" · ${cues.size} 句")
            if (tokens.isNotEmpty()) {
              append(" · 命中 ")
              append(if (visible.size == cues.size) "全部" else "${visible.size} 句")
            }
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // 跟随开关：默认开；用户往下翻看别的段落时点一下即可停止自动滚动。
      // 用锚点判断而不是「正在播某句」—— 片头 / 静音段没有正在播的句子，
      // 但跟随这件事依然是有效的（列表贴着播放位置），按钮不该消失。
      if (anchorIndex >= 0 && tokens.isEmpty()) {
        TextButton(onClick = { follow = !follow }) {
          Icon(
            if (follow) Icons.Filled.PauseCircle else Icons.Filled.PlayCircleOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = if (follow) "跟随中" else "已暂停跟随",
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
      TextButton(onClick = onBackToTracks) { Text("字幕列表") }
      // 抽屉形态可以下滑/点空白关闭；悬浮面板不拦画面点击，必须留一个明确的关闭
      if (!isPortrait) {
        IconButton(onClick = closePanel) {
          Icon(Icons.Default.Close, contentDescription = "关闭时间线", modifier = Modifier.size(20.dp))
        }
      }
    }
  }

  val body: @Composable ColumnScope.() -> Unit = {
    // 调过字幕延迟 / 速度时，列表里的数字和字幕文件里写的不一样 —— 必须说明一句，
    // 否则用户会以为时间轴算错了（旧版正是这里没交代，才让人以为是「跳转偏了 7 秒」）。
    if (shifted) {
      Text(
        text = buildString {
          append("字幕")
          if (delayMs != 0L) append("偏移 ").append("%+.1fs".format(delaySec))
          if (delayMs != 0L && speed != 1.0) append(" · ")
          if (speed != 1.0) append("速度 ").append("%.2f×".format(speed))
          append("，下列时间已按播放位置换算")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      )
    }

    // ── 轨道切换：主 / 副 ──
    if (viewable.size >= 2 || (viewable.size == 1 && role != TrackRole.Primary)) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        viewable.forEach { track ->
          val isViewed = track.id == viewedId
          FilterChip(
            selected = isViewed,
            onClick = {
              // 用户自己挑的轨 ⇒ 此后不再被「自动跟上主字幕」纠正
              userPicked = true
              viewedId = track.id
            },
            label = {
              Text(
                text = getTrackTitle(track),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 轨道标题可能是长文件名；不给上限会把 chip 撑到把整行挤满
                modifier = Modifier.widthIn(max = 180.dp),
              )
            },
            leadingIcon = {
              val badge =
                when (track.id) {
                  activePrimaryId -> "主"
                  activeSecondaryId -> "副"
                  else -> null
                }
              if (badge != null) {
                Text(
                  text = badge,
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (isViewed) {
                      MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }
            },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              ),
          )
        }
      }
    }

    // 查看的不是主字幕 ⇒ 给一步「真的把它切成主字幕」，这才是改变播放状态的动作
    val otherSelected = role == TrackRole.Secondary
    if (role != TrackRole.Primary && viewed != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            if (otherSelected) {
              "这一条是副字幕（画面上方），时间轴可定位，但高亮跟随的是主字幕。"
            } else {
              "这一条当前没有启用，时间轴可用于按台词定位。"
            },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onPromoteToPrimary(viewed.id) }) {
          Text("设为主字幕", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    if (load is CueLoad.Ready && load.truncated) {
      Text(
        text = "该字幕条目数超过 ${SubtitleTimeline.MAX_CUES} 条，此处仅列出前 ${SubtitleTimeline.MAX_CUES} 条",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      )
    }

    // 源文件在上游就被替换符吃掉一部分字符时，换任何编码都只会得到**另一种**乱码 ——
    // 这里必须说清楚是文件本身缺字，否则用户会以为是我们解析坏了，反复重下同一条。
    if (load is CueLoad.Ready && load.damaged) {
      Text(
        text =
          "这条字幕文件本身已损坏：约 " +
            "%.0f".format(load.damagedRatio * 100f) +
            "% 的字符在来源处就已丢失，无法通过换编码恢复。建议换一条字幕重新下载。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      )
    }

    // ── 搜台词 ──
    if (cues.isNotEmpty()) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("搜索台词文本", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(onClick = { query = "" }) {
              Icon(Icons.Default.Close, contentDescription = "清空搜索")
            }
          }
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
      )
    }

    // 轨道 / 轨道上的台词一条都没有时，先给一句为什么（搜索框这时也不出现，
    // 没有东西可搜）
    if (visible.isEmpty()) {
      EmptyHint(load = load, hasTracks = tracks.isNotEmpty(), hasViewable = viewable.isNotEmpty(), query = query.trim())
    }

    if (visible.isNotEmpty()) {
      LazyColumn(
        state = listState,
        // 面板给的是固定高度，列表吃掉剩余空间；不 weight 的话它只按内容量一次长，
        // 反而会把整个面板撑破（横屏时尤其明显）
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.large),
      ) {
        itemsIndexed(visible) { index, cue ->
          CueRow(
            index = index + 1,
            cue = cue,
            // 有偏移 / 变速时显示「播放位置时间」：写多少就跳到多少，和进度条对得上
            timeLabel =
              if (shifted) {
                "${SubtitleTimeline.clock(fileToPlaybackMs(cue.startMs))} → " +
                  SubtitleTimeline.clock(fileToPlaybackMs(cue.endMs))
              } else {
                cue.timeLabel
              },
            active = index == activeIndex,
            onClick = {
              // 点了某句 = 明确要「去那里」，所以把跟随打开，跳完继续跟着播
              follow = true
              onSeekToMs(fileToPlaybackMs(cue.startMs))
            },
          )
        }
      }
    }
  }

  if (isPortrait) {
    // 竖屏：底部抽屉，两种高度
    //
    //  · 有黑边可停（[dockHeightDp] 非空）⇒ 面板高度＝下黑边高度，整块落在黑边里，
    //    画面完全不挡；此时不再预留状态栏高度（那一块本来就贴着屏幕下半部）。
    //  · 没有黑边 / 画面被缩放 / 键盘弹起 ⇒ 退回 0.66 屏高的抽屉。[scrimAlpha = 0]
    //    保证遮罩不画黑（点空白仍能关），上方画面不被压暗。
    PlayerSheet(
      onDismissRequest = onDismissRequest,
      scrimAlpha = 0f,
      heightFraction = 0.66f,
      customMaxHeight = dockHeightDp?.dp,
      reserveStatusBar = dockHeightDp == null,
      // 稍微透一点：停靠时像从黑边里长出来的，退回大抽屉时还能看见画面在动
      surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = TIMELINE_SHEET_ALPHA),
      tonalElevation = 0.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        header()
        body()
      }
    }
  } else {
    // 横屏：右侧半透明悬浮面板，不做遮罩变暗 —— 看时间线的同时画面照常在播。
    //
    // 但**点击要能收回**：铺一层全屏透明点击层（不画任何东西、不压暗画面），
    // 面板之外任意位置被点到就收起。竖屏抽屉由 `PlayerSheet` 的全屏遮罩做同一件事，
    // 两种形态的「点空白关闭」口径才一致。
    //
    // 返回键同理：关面板而不是退出播放（抽屉形态由 PlayerSheet 自己接管，这里得自己挂）。
    BackHandler(onBack = closePanel)
    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = closePanel,
            ),
      )
      DraggablePanel(
        modifier =
          Modifier.graphicsLayer {
            // [slide] 直接读在 layer 里 ⇒ 每帧只重绘、不重组
            translationX = slide.value * TIMELINE_PANEL_SLIDE_DP.dp.toPx()
            alpha = 1f - slide.value
          },
        header = header,
        scrollable = false,
        heightFraction = 0.96f,
      ) {
        body()
      }
    }
  }
}

/**
 * 空态：把「为什么没有内容」分成四类说清楚。
 *
 * 时间段面板最常见的困惑是「我明明挂了字幕，为什么这里什么都没有」——
 * 而那通常是**内嵌轨**（视频里烧好的字幕），它没有可读的文件。
 */
@Composable
private fun EmptyHint(
  load: CueLoad?,
  hasTracks: Boolean,
  hasViewable: Boolean,
  query: String,
) {
  val text =
    when {
      query.isNotEmpty() -> "没有台词包含「$query」"
      !hasTracks -> "无字幕轨道，挂载外挂字幕后可读取时间轴"
      !hasViewable -> "当前字幕内嵌于视频，无法读取时间轴；按台词跳转仅支持外挂字幕（srt / ass / vtt）"
      load is CueLoad.Loading -> null
      load is CueLoad.Failed -> load.reason
      load == null -> "正在准备字幕…"
      else -> "该字幕无台词数据"
    }

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 120.dp)
        .padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
    contentAlignment = Alignment.Center,
  ) {
    if (load is CueLoad.Loading) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Text(
          text = "正在解析字幕时间轴…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else if (text != null) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 一句台词：序号 + 起止时间 + 正文，点击跳到该句起点。
 *
 * [active] 为真表示画面此刻正播这一句 —— 底色 + 左侧竖条同时在，是因为只改字色
 * 在一屏几十行里根本看不出来，而「哪句在播」正是这个面板的存在意义。
 */
@Composable
private fun CueRow(
  index: Int,
  cue: SubtitleCue,
  /** 显示用的「起 → 止」。有字幕偏移 / 变速时是播放位置时间，否则是文件时间 */
  timeLabel: String,
  active: Boolean,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.smaller)
          .clip(RoundedCornerShape(8.dp))
          .background(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
          )
          .clickable(onClick = onClick)
          .padding(vertical = 7.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Box(
        modifier =
          Modifier
            .padding(top = 1.dp)
            .width(3.dp)
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
      )
      Spacer(Modifier.width(5.dp))
      Text(
        text = "#$index",
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 34.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = timeLabel,
          style = MaterialTheme.typography.labelSmall,
          // 未选中的行不能也用 primary：每行时间都是强调色，正在播的那行就淹没在里头了
          fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
          color =
            if (active) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
          text = cue.text,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 1.dp),
        )
      }
    }
    HorizontalDivider(
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
    )
  }
}

/** 在 IO 线程读盘 + 解析；所有失败都收敛成一句用户能懂的话。 */
private fun loadCues(
  context: Context,
  raw: String,
): CueLoad {
  val file =
    resolveSubtitleFile(context, raw)
      ?: return CueLoad.Failed("找不到这条字幕的文件（可能已被清理），回字幕面板重新挂载一次")
  val info = runCatching { SubtitleTimeline.parseFileInfo(file) }.getOrNull()
    ?: return CueLoad.Failed("字幕文件读取失败，可能已被清理或没有读取权限")
  if (info.timeline.cues.isEmpty()) {
    return CueLoad.Failed("这个字幕里没有可读的台词（可能是图像字幕，或格式无法解析）")
  }
  return CueLoad.Ready(
    cues = info.timeline.cues,
    truncated = info.timeline.truncated,
    damagedRatio = info.decoded.damagedRatio,
    damaged = info.decoded.damaged,
  )
}

/**
 * mpv 的 `external-filename` → 可读的 [File]。
 *
 * 三种形态都要认：裸路径（绝大多数）、`file://`、以及 `content://`
 * （用户从 SAF 选的字幕，某些内核会原样交给 mpv）。
 * 解析器按扩展名挑语法，所以 content:// 落到缓存时保留原扩展名。
 */
private fun resolveSubtitleFile(
  context: Context,
  raw: String,
): File? {
  val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw).substringBefore('\u0000')
  val path =
    when {
      decoded.startsWith("content://") -> materialize(context, Uri.parse(decoded))
      decoded.startsWith("file://") -> Uri.parse(decoded).path
      else -> decoded
    } ?: return null
  return File(path).takeIf { it.isFile && it.length() > 0 }
}

/** 把 content:// 抄到私有缓存里，供解析器按普通文件读。 */
private fun materialize(
  context: Context,
  uri: Uri,
): String? =
  runCatching {
    val ext =
      uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
        ?: "srt"
    val target = File(context.cacheDir, "subtitle_timeline_${uri.toString().hashCode()}.$ext")
    if (!target.exists() || target.length() == 0L) {
      context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
    }
    target.takeIf { it.isFile && it.length() > 0 }?.absolutePath
  }.getOrNull()
