package app.marlboroadvance.mpvex.ui.player.controls

import android.widget.Toast
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.preferences.preference.plusAssign
import app.marlboroadvance.mpvex.preferences.preference.minusAssign
import app.marlboroadvance.mpvex.ui.player.Decoder.Companion.getDecoderFromValue
import app.marlboroadvance.mpvex.ui.player.DislikePlayerActions
import app.marlboroadvance.mpvex.ui.player.DislikeRemarkDialog
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlaybackMemory
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerUpdates
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import app.marlboroadvance.mpvex.ui.player.controls.components.BrightnessSlider
import app.marlboroadvance.mpvex.ui.player.controls.components.CompactSpeedIndicator
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.player.controls.components.MultipleSpeedPlayerUpdate
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerStatusIndicators
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekPlayerUpdate
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekbarWithTimers
import app.marlboroadvance.mpvex.ui.player.controls.components.SlideToUnlock
import app.marlboroadvance.mpvex.ui.player.controls.components.SpeedControlSlider
import app.marlboroadvance.mpvex.ui.player.controls.components.TextPlayerUpdate
import app.marlboroadvance.mpvex.ui.player.controls.components.VolumeSlider
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.engine.supportsButton
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.playerRippleConfiguration
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import org.koin.compose.koinInject
import kotlin.math.abs

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

/**
 * 绔栧睆椤舵爮鍙充晶鐨勩€屽揩鎹峰紑鍏炽€嶉泦鍚堛€?
 *
 * 杩欏嚑涓兘鏄€岃涓€娆″氨涓嶇敤鍐嶇銆嶇殑浣庨椤癸細瑙ｇ爜鍣?/ 闊宠建 / 瀛楀箷 / 鏀惰棌 / 鏇村銆?
 * 鏀惧彸涓婅鏃笉鍗犲簳閮ㄧ┖闂达紝涔熶笉褰卞搷甯哥敤鎿嶄綔銆?
 *
 * 鍒绘剰鍙繚鐣?5 涓細5 脳 40dp + 闂磋窛 鈮?220dp锛岀珫灞忛《鏍忚繕鑳界粰鏍囬鐣欏嚭鍙瀹藉害锛?
 * 鍐嶅浼氭妸鏍囬鍘嬫垚鐪佺暐鍙枫€傜珷鑺傜被鎸夐挳涓嶅湪杩欓噷锛堝畠浠惤鍒板乏绨囷級锛岄伩鍏嶉《鏍忚鎾戞弧銆?
 *
 * 鐢ㄦ埛浠嶇劧鍙互鍦ㄣ€屾挱鏀惧櫒鎺т欢銆嶈缃噷鑷敱澧炲垹 鈥斺€?杩欓噷鍙喅瀹氥€屾樉绀哄湪鍝釜鍖哄煙銆嶏紝
 * 涓嶆槸纭€ц闃咃紱鎶婃煇涓€椤圭Щ鍑虹珫灞忓垪琛紝椤舵爮鑷劧涔熶笉浼氬嚭鐜板畠銆?
 */
private val PORTRAIT_TOP_SHORTCUTS =
  setOf(
    PlayerButton.DECODER,
    PlayerButton.AUDIO_TRACK,
    PlayerButton.SUBTITLES,
    PlayerButton.EMBY_FAVORITE,
    PlayerButton.MORE_OPTIONS,
  )

/**
 * 绔栧睆搴曢儴鐨?*鎺掑垪椤哄簭**鍒嗙粍锛氱敾闈㈢被宸ュ叿鎺掑湪鎸夐挳鏉＄殑鍙冲崐杈广€?
 *
 * 绔栧睆搴曢儴宸茬粡鍚堝苟鎴愪竴鏉¤繛缁寜閽潯锛堣 [CenteredBottomPlayerControlsPortrait]锛夛紝
 * 杩欓噷涓嶅啀鍐冲畾銆屽乏鍙崇皣銆嶇殑鍧愭爣锛岃€屾槸鍐冲畾**璋佹帓鍦ㄥ彸杈?*锛?
 * 杩欎竴缁勯噷鐨勬帓鍦ㄦ寜閽潯鏈熬锛屽叾浣欐寜鐢ㄦ埛閰嶇疆鐨勫厛鍚庨『搴忔帓鍦ㄥ墠闈€?
 *
 * 灞忓箷鏃嬭浆鐨勬寜閽寜鐢ㄦ埛鍙嶉鎸埌浜嗗彸杈癸紙鍘熸潵鐢讳腑鐢荤殑浣嶇疆锛夛紝鐢讳腑鐢诲垯鍥炲埌宸﹁竟锛?
 * 涓よ€呬綅缃璋?鈥斺€?鏃嬭浆鏄€屾崲涓€涓瑙掔湅銆嶇殑鍔ㄤ綔锛屽拰姣斾緥/缂╂斁杩欑被鐢婚潰宸ュ叿鏀句竴璧锋洿椤恒€?
 */
private val PORTRAIT_RIGHT_CLUSTER =
  setOf(
    PlayerButton.FRAME_NAVIGATION,
    PlayerButton.VIDEO_ZOOM,
    PlayerButton.ASPECT_RATIO,
    PlayerButton.SCREEN_ROTATION,
  )

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> =
  tween(
    durationMillis = 300,
    easing = FastOutSlowInEasing,
  )

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> =
  tween(
    durationMillis = 100,
    easing = LinearOutSlowInEasing,
  )

/**
 * 姝ｄ腑鎺т欢缁勪袱渚х殑銆屽悗閫€ / 鍓嶈繘 10 绉掋€嶉敭銆?
 *
 * 澶栬涓庝笂/涓嬩竴闆嗗悓娆撅紙鐜荤拑鍦嗗簳锛夛紝灏哄鐣ュ皬涓€妗ｏ紙44dp锛屾涓挱鏀鹃敭 64dp銆?
 * 涓?涓嬩竴闆?56dp锛夛紝鎺掑竷椤哄簭锛歔鍚庨€€10s 路 涓婁竴闆?路 鎾斁 路 涓嬩竴闆?路 鍓嶈繘10s]銆?
 */
@Composable
private fun CenterSeekButton(
  forward: Boolean,
  onClick: () -> Unit,
) {
  val hideBackground by koinInject<AppearancePreferences>()
    .hidePlayerButtonsBackground.collectAsState()
  // 步长跟着偏好走 ⇒ 改完设置立刻反映在按钮上，无需重启播放器。
  // collectAsState 而不是 .get()：后者在 Composable 里读一次就不变了（红线 14）。
  // 前进/后退是两个独立值（跳片头要 10s、跳过场可能要 30s，合成一个两边都别扭）。
  val playerPrefs = koinInject<PlayerPreferences>()
  val stepSeconds by
    remember(forward) {
      if (forward) playerPrefs.seekForwardSeconds else playerPrefs.seekBackwardSeconds
    }
      .collectAsState()
  val buttonShadow =
    Brush.radialGradient(
      0.0f to Color.Black.copy(alpha = 0.3f),
      0.7f to Color.Transparent,
      1.0f to Color.Transparent,
    )
  Surface(
    modifier =
      Modifier
        .size(44.dp)
        .clip(CircleShape)
        .clickable(onClick = onClick)
        .then(
          if (hideBackground) {
            Modifier.background(brush = buttonShadow, shape = CircleShape)
          } else {
            Modifier
          },
        ),
    shape = CircleShape,
    color =
      if (!hideBackground) {
        PlayerControlGlassFill
      } else {
        Color.Transparent
      },
    contentColor = controlColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border =
      if (!hideBackground) {
        BorderStroke(1.dp, PlayerControlGlassBorder)
      } else {
        null
      },
  ) {
    // 时长可变（前进/后退还不一样）⇒ 不能用 Icons 的 Forward10/Replay30
    // 这类**固定档位**图标 —— 那套图标只覆盖 10/30/45/60，用户调到别的秒数就没有对应图标。
    // ⚠️ **不要叠在一起**：早前是 `Box(Alignment.Center)` 里 Icon(fillMaxSize) + Text
    //   居中重合，结果数字正好压在双箭头的两个三角之间，糊成一团（用户反馈「数字压在
    //   箭头上了」）。
    // ⇒ 改成**上下排布**：箭头像小尺寸的标题图标在上、秒数在下。
    //   箭头保持全不透明（它现在是方向主体，不再是「半透明背景」）；
    //   秒数用 labelSmall 避免 3 位数时撑破 44dp。
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
        contentDescription =
          stringResource(
            if (forward) R.string.player_control_forward_10s else R.string.player_control_rewind_10s,
            stepSeconds,
          ),
        tint = controlColor,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = stepSeconds.toString(),
        color = controlColor,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
      )
    }
  }
}

@OptIn(
  ExperimentalAnimationGraphicsApi::class,
  ExperimentalMaterial3Api::class,
  ExperimentalMaterial3ExpressiveApi::class,
  ExperimentalFoundationApi::class,
)
@Composable
@Suppress("CyclomaticComplexMethod", "ViewModelForwarding")
fun PlayerControls(
  viewModel: PlayerViewModel,
  onBackPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = MaterialTheme.spacing
  val context = LocalContext.current
  val appearancePreferences = koinInject<AppearancePreferences>()
  val hideBackground by appearancePreferences.hidePlayerButtonsBackground.collectAsState()
  val playerPreferences = koinInject<PlayerPreferences>()
  val audioPreferences = koinInject<AudioPreferences>()
  val showSystemStatusBar by playerPreferences.showSystemStatusBar.collectAsState()
  val showSystemNavigationBar by playerPreferences.showSystemNavigationBar.collectAsState()
  // 控制条 << / >> 的步长（秒，forward 与 backward 是两个独立偏好）。
  val seekForwardSeconds by playerPreferences.seekForwardSeconds.collectAsState()
  val seekBackwardSeconds by playerPreferences.seekBackwardSeconds.collectAsState()
  // 控制条上的系统时钟 / 电量（两个独立开关，默认都关）。
  val showPlayerClock by playerPreferences.showPlayerClock.collectAsState()
  val showPlayerBattery by playerPreferences.showPlayerBattery.collectAsState()
  val interactionSource = remember { MutableInteractionSource() }
  val controlsShown by viewModel.controlsShown.collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val seekBarShown by viewModel.seekBarShown.collectAsState()
  val pausedForCache by PlayerLib.propBoolean["paused-for-cache"].collectAsState()
  val paused by PlayerLib.propBoolean["pause"].collectAsState()
  val duration by PlayerLib.propInt["duration"].collectAsState()
  val position by PlayerLib.propInt["time-pos"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val playbackSpeed by PlayerLib.propFloat["speed"].collectAsState()
  val doubleTapSeekAmount by viewModel.doubleTapSeekAmount.collectAsState()
  val showDoubleTapOvals by playerPreferences.showDoubleTapOvals.collectAsState()
  val showSeekTime by playerPreferences.showSeekTimeWhileSeeking.collectAsState()
  var isSeeking by remember { mutableStateOf(false) }
  var resetControlsTimestamp by remember { mutableStateOf(0L) }
  val seekText by viewModel.seekText.collectAsState()
  val currentChapter by PlayerLib.propInt["chapter"].collectAsState()
  val mpvDecoder by PlayerLib.propString["hwdec-current"].collectAsState()
  val decoder by remember { derivedStateOf { getDecoderFromValue(mpvDecoder ?: "auto") } }
  val isSpeedNonOne by remember(playbackSpeed) {
    derivedStateOf { abs((playbackSpeed ?: 1f) - 1f) > 0.001f }
  }
  val playerTimeToDisappear by playerPreferences.playerTimeToDisappear.collectAsState()
  val chapters by viewModel.chapters.collectAsState(persistentListOf())
  val playlistMode by playerPreferences.playlistMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    
  val abLoopA by viewModel.abLoopA.collectAsState()
  val abLoopB by viewModel.abLoopB.collectAsState()

  val onOpenSheet: (Sheets) -> Unit = {
    viewModel.sheetShown.update { _ -> it }
    if (it == Sheets.None) {
      viewModel.showControls()
    } else {
      viewModel.hideControls()
      viewModel.panelShown.update { Panels.None }
    }
  }

  val onOpenPanel: (Panels) -> Unit = {
    viewModel.panelShown.update { _ -> it }
    if (it == Panels.None) {
      viewModel.showControls()
    } else {
      viewModel.hideControls()
      viewModel.sheetShown.update { Sheets.None }
    }
  }

  // ── 「标记为不喜欢」：单击切换、长按写备注 ──
  // 弹框状态放在控制条这一层（而不是按钮的 when 分支里）是因为 Dialog 需要访问它：
  // 按钮那边拿不到这里的 state，也没法在保存时回调。
  var showDislikeRemark by remember { mutableStateOf(false) }
  val dislikeIsMarked by DislikePlayerActions.isMarked.collectAsState()
  val onDislikeRemarkRequest: () -> Unit = {
    showDislikeRemark = true
    // 弹框时把控制条收起来，否则它会盖在弹框上面。
    viewModel.hideControls()
  }
  if (showDislikeRemark) {
    DislikeRemarkDialog(
      initialValue = DislikePlayerActions.currentReason(),
      showMarked = dislikeIsMarked,
      onDismiss = {
        showDislikeRemark = false
        viewModel.showControls()
      },
      onConfirm = { reason ->
        DislikePlayerActions.saveRemark(reason)
        showDislikeRemark = false
        viewModel.showControls()
      },
    )
  }

  // 轻提示：单击标记后的「已标记为不喜欢 / 已取消标记」。
  // 刻意用 collectAsState 而不是 LaunchedEffect：后者会随控制条整块被移除
  // （自动隐藏、切面板、退出全屏）而丢掉协程，提示就永远不弹了。
  val dislikeToast by DislikePlayerActions.toast.collectAsState()
  dislikeToast?.let {
    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
    DislikePlayerActions.consumeToast()
  }

  val topRightControlsPref by appearancePreferences.topRightControls.collectAsState()
  val bottomRightControlsPref by appearancePreferences.bottomRightControls.collectAsState()
  val bottomLeftControlsPref by appearancePreferences.bottomLeftControls.collectAsState()
  val portraitBottomControlsPref by appearancePreferences.portraitBottomControls.collectAsState()

  // 当前内核：不支持的按钮直接从控件栏移除（而不是「渲染了但点了没反应」）
  val engineKind = PlayerLib.kind

  val (topRightButtons, bottomRightButtons, bottomLeftButtons) =
    remember(
      topRightControlsPref,
      bottomRightControlsPref,
      bottomLeftControlsPref,
      engineKind,
    ) {
      val usedButtons = mutableSetOf<app.marlboroadvance.mpvex.preferences.PlayerButton>()
      val topR = appearancePreferences.parseButtons(topRightControlsPref, usedButtons)
      val bottomR = appearancePreferences.parseButtons(bottomRightControlsPref, usedButtons)
      val bottomL = appearancePreferences.parseButtons(bottomLeftControlsPref, usedButtons)
      listOf(topR, bottomR, bottomL)
    }.map { buttons -> buttons.filter { engineKind.supportsButton(it) } }

  // 绔栧睆鎺т欢鍒楄〃鎸夌敤閫旀媶寮€锛?
  //   鈶?椤舵爮鍙充晶蹇嵎寮€鍏筹細瑙ｇ爜鍣?/ 闊宠建 / 瀛楀箷 / 鏀惰棌 / 鏇村锛?
  //   鈶?搴曢儴鎸夐挳鏉★細鍓╀笅鐨勫叏閮紝鎸夈€屾挱鏀剧姸鎬佺被 鈫?鐢婚潰绫汇€嶆帓鎴愪竴鏉★紝灞呬腑鏄剧ず銆?
  // 涓婁竴鐗堟槸宸﹀彸涓ょ皣鍒嗗埆璐磋竟锛屼腑闂寸┖鍑轰竴澶у潡锛岀湅璧锋潵鍍忚涓€鏉″垎鍓茬嚎鍒囧紑锛?
  // 鐜板湪鍚堝苟鎴愪竴鏉¤繛缁紙蹇呰鏃跺彲妯悜婊氬姩锛夌殑鎸夐挳鏉★紝鏍囬鍗曠嫭涓€琛屽帇鍦ㄨ繘搴︽潯涓婃柟銆?
  val (portraitTopButtons, portraitSideButtons) = remember(portraitBottomControlsPref) {
    appearancePreferences
      .parseButtons(portraitBottomControlsPref, mutableSetOf())
      .partition { it in PORTRAIT_TOP_SHORTCUTS }
  }
  // 涓?涓嬩竴闆嗗浐瀹氭樉绀哄湪灞忓箷姝ｄ腑鐨勬挱鏀鹃敭涓や晶锛屼粠搴曢儴鎸夐挳鏉￠噷鍓旈櫎锛岄伩鍏嶉噸澶嶅嚭鐜帮紱
  // 鍚屾椂鎶娿€岀敾闈㈢被銆嶇粺涓€鎸埌鎸夐挳鏉℃湯灏撅紝淇濊瘉瀹冧滑濮嬬粓鍦ㄥ彸鍗婅竟銆?
  val portraitBottomButtons = remember(portraitSideButtons, engineKind) {
    val playState = portraitSideButtons.filter { it !in PORTRAIT_RIGHT_CLUSTER }
    val displayState = portraitSideButtons.filter { it in PORTRAIT_RIGHT_CLUSTER }
    (playState + displayState)
      .filter { it != PlayerButton.PREVIOUS && it != PlayerButton.NEXT }
      .filter { engineKind.supportsButton(it) }
  }

  var isUnlockSliderDragging by remember { mutableStateOf(false) }

  LaunchedEffect(
    controlsShown,
    paused,
    isSeeking,
    resetControlsTimestamp,
    areControlsLocked,
    isUnlockSliderDragging,
  ) {
    if (controlsShown && paused == false && !isSeeking && !isUnlockSliderDragging) {
      // Use 2 second delay when controls are locked, otherwise use user preference
      val delayTime = if (areControlsLocked) 2000L else playerTimeToDisappear.toLong()
      delay(delayTime)
      viewModel.hideControls()
    }
  }

  val transparentOverlay by animateFloatAsState(
    if (controlsShown && !areControlsLocked) .8f else 0f,
    animationSpec = playerControlsExitAnimationSpec(),
    label = "controls_transparent_overlay",
  )

  GestureHandler(
    viewModel = viewModel,
    interactionSource = interactionSource,
  )

  DoubleTapToSeekOvals(doubleTapSeekAmount, seekText, showDoubleTapOvals, showSeekTime, showSeekTime, interactionSource)

  CompositionLocalProvider(
    LocalRippleConfiguration provides playerRippleConfiguration,
    LocalPlayerButtonsClickEvent provides { resetControlsTimestamp = System.currentTimeMillis() },
    LocalContentColor provides Color.White,
  ) {
    CompositionLocalProvider(
      LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
      val configuration = LocalConfiguration.current
      val isPortrait by remember(configuration) {
        derivedStateOf { configuration.orientation == ORIENTATION_PORTRAIT }
      }

      ConstraintLayout(
        modifier =
          modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                Pair(0f, Color.Black),
                Pair(.4f, Color.Transparent),
                Pair(.6f, Color.Transparent),
                Pair(1f, Color.Black),
              ),
              alpha = transparentOverlay,
            ),
      ) {
        val (topLeftControls, topRightControls) = createRefs()
        val (volumeSlider, brightnessSlider) = createRefs()
        val unlockControlsButton = createRef()
        val (bottomRightControls, bottomLeftControls) = createRefs()
        val playerPauseButton = createRef()
        val seekbar = createRef()
        // 系统时钟 + 电量：独立约束块。
        // ⚠️ **锚点必须跟着朝向变**（这条吃过亏）：
        // 之前两种朝向都锚 `bottom→seekbar.top`，而那一行在两种朝向下都已被占 ——
        //   · 竖屏：portraitBottomTitle 也锚 seekbar.top 且横向占满 ⇒ 压在标题上；
        //   · 横屏：bottomRightControls（含翻转按钮）同样锚 seekbar.top ⇒ 压在一起。
        // ⇒ 现在分别锚到**那一行控件的上方**：竖屏锚按钮簇上方、横屏锚右下簇上方，
        //   那一带本来就是留白，不与任何控件冲突。
        val statusIndicators = createRef()
        // 绔栧睆锛氭爣棰樻斁鍦ㄨ繘搴︽潯涓婃柟锛屽崟鐙竴鏉＄害鏉燂紙妯睆涓嶄娇鐢級
        val portraitBottomTitle = createRef()
        val (playerUpdates) = createRefs()

        val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
        val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
        val brightness by viewModel.currentBrightness.collectAsState()
        val volume by viewModel.currentVolume.collectAsState()
        val mpvVolume by PlayerLib.propInt["volume"].collectAsState()
        val swapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
        val reduceMotion by playerPreferences.reduceMotion.collectAsState()

        val activity = LocalActivity.current as PlayerActivity
        val aspect by viewModel.videoAspect.collectAsState()
        val currentZoom by viewModel.videoZoom.collectAsState()

        val rawMediaTitle by PlayerLib.propString["media-title"].collectAsState()
        val mediaTitle by remember(rawMediaTitle, activity) {
          derivedStateOf {
            // mpv 拿不到片名时会把整条播放直链当 media-title 回传（Emby 的链里带 api_key），
            // 顶栏不能原样显示 —— 只对**显示**的那份打码，播放用的地址不受影响。
            val raw =
              rawMediaTitle?.takeIf { it.isNotBlank() }
                ?: activity.getTitleForControls()
            // ⚠️ 多版本媒体的**版本名不再拼进顶栏**：片名本身可能已经很长，版本名（如
            //   「4K · HEVC · 65.2 GB」）再追加一截会把整条标题撑到换行/截断，反而更难看。
            //   版本信息改由「更多 → 媒体信息」弹窗承载（见 PlayerMediaInfoDialog），
            //   那里能一次看完版本 + 容器 / 分辨率 / 码率 / 轨道等全部信息。
            app.marlboroadvance.mpvex.ui.player.redactUrlSecrets(raw).orEmpty()
          }
        }

        // Slider display duration: 1000ms shown + 300ms exit animation = 1300ms total
        val sliderDisplayDuration = 1000L

        val volumeSliderTimestamp by viewModel.volumeSliderTimestamp.collectAsState()
        val brightnessSliderTimestamp by viewModel.brightnessSliderTimestamp.collectAsState()

        // Track timestamp to restart timer on every gesture event
        LaunchedEffect(volumeSliderTimestamp) {
          if (isVolumeSliderShown && volumeSliderTimestamp > 0) {
            delay(sliderDisplayDuration)
            viewModel.isVolumeSliderShown.update { false }
          }
        }

        LaunchedEffect(brightnessSliderTimestamp) {
          if (isBrightnessSliderShown && brightnessSliderTimestamp > 0) {
            delay(sliderDisplayDuration)
            viewModel.isBrightnessSliderShown.update { false }
          }
        }

        val areSlidersShown = isBrightnessSliderShown || isVolumeSliderShown

        AnimatedVisibility(
          isBrightnessSliderShown,
          enter =
            if (!reduceMotion) {
              slideInHorizontally(playerControlsEnterAnimationSpec()) {
                if (swapVolumeAndBrightness) -it else it
              } + fadeIn(playerControlsEnterAnimationSpec())
            } else {
              fadeIn(playerControlsEnterAnimationSpec())
            },
          exit =
            if (!reduceMotion) {
              slideOutHorizontally(playerControlsExitAnimationSpec()) {
                if (swapVolumeAndBrightness) -it else it
              } + fadeOut(playerControlsExitAnimationSpec())
            } else {
              fadeOut(playerControlsExitAnimationSpec())
            },
          modifier =
            Modifier.constrainAs(brightnessSlider) {
              if (swapVolumeAndBrightness) {
                start.linkTo(parent.start, if (isPortrait) spacing.large else spacing.extraLarge)
              } else {
                end.linkTo(parent.end, if (isPortrait) spacing.large else spacing.extraLarge)
              }
              top.linkTo(parent.top, spacing.larger)
              bottom.linkTo(parent.bottom, spacing.extraLarge)
            },
        ) { BrightnessSlider(brightness, 0f..1f) }

        AnimatedVisibility(
          isVolumeSliderShown,
          enter =
            if (!reduceMotion) {
              slideInHorizontally(playerControlsEnterAnimationSpec()) {
                if (swapVolumeAndBrightness) it else -it
              } + fadeIn(playerControlsEnterAnimationSpec())
            } else {
              fadeIn(playerControlsEnterAnimationSpec())
            },
          exit =
            if (!reduceMotion) {
              slideOutHorizontally(playerControlsExitAnimationSpec()) {
                if (swapVolumeAndBrightness) it else -it
              } + fadeOut(playerControlsExitAnimationSpec())
            } else {
              fadeOut(playerControlsExitAnimationSpec())
            },
          modifier =
            Modifier.constrainAs(volumeSlider) {
              if (swapVolumeAndBrightness) {
                end.linkTo(parent.end, if (isPortrait) spacing.large else spacing.extraLarge)
              } else {
                start.linkTo(parent.start, if (isPortrait) spacing.large else spacing.extraLarge)
              }
              top.linkTo(parent.top, spacing.larger)
              bottom.linkTo(parent.bottom, spacing.extraLarge)
            },
        ) {
          val boostCap by audioPreferences.volumeBoostCap.collectAsState()
          val displayVolumeAsPercentage by playerPreferences.displayVolumeAsPercentage.collectAsState()
          
          // Show if boost is allowed (boostCap > 0) OR if we are currently boosted (> 100)
          val currentBoost = (mpvVolume ?: 100) - 100
          val showBoost = boostCap > 0 || currentBoost > 0
          val effBoostCap = maxOf(boostCap, currentBoost)
          
          VolumeSlider(
            volume,
            mpvVolume = mpvVolume ?: 100,
            range = 0..viewModel.maxVolume,
            boostRange = if (showBoost) 0..effBoostCap else null,
            displayAsPercentage = displayVolumeAsPercentage,
          )
        }

        val holdForMultipleSpeed by playerPreferences.holdForMultipleSpeed.collectAsState()
        val currentPlayerUpdate by viewModel.playerUpdate.collectAsState()
        val aspectRatio by viewModel.videoAspect.collectAsState()
        val currentAspectRatio by viewModel.currentAspectRatio.collectAsState()
        val videoZoom by viewModel.videoZoom.collectAsState()

        LaunchedEffect(currentPlayerUpdate, aspectRatio, videoZoom) {
          if (currentPlayerUpdate is PlayerUpdates.MultipleSpeed ||
            currentPlayerUpdate is PlayerUpdates.DynamicSpeedControl ||
            currentPlayerUpdate is PlayerUpdates.None
          ) {
            return@LaunchedEffect
          }
          delay(2000)
          viewModel.playerUpdate.update { PlayerUpdates.None }
        }

        AnimatedVisibility(
          currentPlayerUpdate !is PlayerUpdates.None,
          enter = fadeIn(playerControlsEnterAnimationSpec()),
          exit = fadeOut(playerControlsExitAnimationSpec()),
          modifier =
            Modifier
              .then(
                if (showSystemStatusBar) {
                  Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                  Modifier
                }
              )
              .constrainAs(playerUpdates) {
                linkTo(parent.start, parent.end)
                top.linkTo(parent.top, if (isPortrait) 104.dp else 64.dp)
              },
        ) {
          when (currentPlayerUpdate) {
            is PlayerUpdates.MultipleSpeed -> MultipleSpeedPlayerUpdate(currentSpeed = holdForMultipleSpeed)
            is PlayerUpdates.DynamicSpeedControl -> {
              val speedUpdate = currentPlayerUpdate as PlayerUpdates.DynamicSpeedControl
              val currentSpeed = speedUpdate.speed
              val showDynamicSpeedOverlay by playerPreferences.showDynamicSpeedOverlay.collectAsState()
              val shouldShowFull = speedUpdate.showFullOverlay
              var isCollapsed by remember { mutableStateOf(false) }
              
              LaunchedEffect(currentSpeed, shouldShowFull) {
                if (shouldShowFull) {
                  isCollapsed = false
                  delay(1500)
                  isCollapsed = true
                } else {
                  isCollapsed = true
                }
              }
              
              if (showDynamicSpeedOverlay) {
                if (isCollapsed) {
                  // Simple compact indicator
                  CompactSpeedIndicator(currentSpeed = currentSpeed)
                } else {
                  // Full speed control slider 鈥斺€?甯︿笂 0.05 姝ヨ繘鐨勫井璋冩寜閽?
                  SpeedControlSlider(
                    currentSpeed = currentSpeed,
                    onSpeedChange = { raw ->
                      // 澶栭儴浼犲叆鐨勫凡缁忔槸 0.05 鐨勬暣鏁板€嶏紝杩欓噷鍐嶅厹涓€娆￠槻姝㈡诞鐐圭疮绉宸?
                      val snapped = Math.round(raw * 20) / 20f
                      PlayerLib.setPropertyFloat("speed", snapped)
                      PlaybackMemory.saveSpeed(playerPreferences, viewModel.seriesKey, snapped)
                    },
                  )
                }
              } else {
                // fallback, simple indicator
                CompactSpeedIndicator(currentSpeed = currentSpeed)
              }
            }
            is PlayerUpdates.AspectRatio -> {
              val customRatiosSet by playerPreferences.customAspectRatios.collectAsState()
              val displayText = if (currentAspectRatio > 0) {
                // Custom aspect ratio - try to find its label first
                val customLabel = customRatiosSet.firstNotNullOfOrNull { str ->
                  val parts = str.split("|")
                  if (parts.size == 2) {
                    val savedRatio = parts[1].toDoubleOrNull()
                    if (savedRatio != null && kotlin.math.abs(savedRatio - currentAspectRatio) < 0.01) {
                      parts[0] // Return the label
                    } else null
                  } else null
                }
                
                customLabel ?: run {
                  // No custom label found, use preset names or format as ratio
                  val ratio = currentAspectRatio
                  when {
                    kotlin.math.abs(ratio - 16.0/9.0) < 0.01 -> "16:9"
                    kotlin.math.abs(ratio - 4.0/3.0) < 0.01 -> "4:3"
                    kotlin.math.abs(ratio - 16.0/10.0) < 0.01 -> "16:10"
                    kotlin.math.abs(ratio - 21.0/9.0) < 0.01 -> "21:9"
                    kotlin.math.abs(ratio - 32.0/9.0) < 0.01 -> "32:9"
                    kotlin.math.abs(ratio - 1.0) < 0.01 -> "1:1"
                    kotlin.math.abs(ratio - 2.35) < 0.01 -> "2.35:1"
                    kotlin.math.abs(ratio - 2.39) < 0.01 -> "2.39:1"
                    else -> String.format("%.2f:1", ratio)
                  }
                }
              } else {
                // Standard mode (Fit/Crop/Stretch)
                stringResource(aspectRatio.titleRes)
              }
              TextPlayerUpdate(displayText)
            }
            is PlayerUpdates.ShowText ->
              TextPlayerUpdate(
                (currentPlayerUpdate as PlayerUpdates.ShowText).value,
                modifier = Modifier.widthIn(min = 120.dp),
              )

            is PlayerUpdates.VideoZoom -> {
              val zoomPercentage = (videoZoom * 100).toInt()
              TextPlayerUpdate(
                text = stringResource(R.string.player_update_zoom, zoomPercentage), 
                modifier = Modifier, // Let content size determine width
              )
            }

            is PlayerUpdates.HorizontalSeek -> {
              val seekUpdate = currentPlayerUpdate as PlayerUpdates.HorizontalSeek
              SeekPlayerUpdate(
                currentTime = seekUpdate.currentTime,
                seekDelta = "[${seekUpdate.seekDelta}]",
                modifier = Modifier, // Let content size determine width
              )
            }

            is PlayerUpdates.RepeatMode -> {
              val mode = (currentPlayerUpdate as PlayerUpdates.RepeatMode).mode
              val text = when (mode) {
                app.marlboroadvance.mpvex.ui.player.RepeatMode.OFF -> stringResource(R.string.player_update_repeat_off)
                app.marlboroadvance.mpvex.ui.player.RepeatMode.ONE -> stringResource(R.string.player_update_repeat_one)
                app.marlboroadvance.mpvex.ui.player.RepeatMode.ALL -> {
                  if (playlistMode && viewModel.hasPlaylistSupport()) {
                    stringResource(R.string.player_update_repeat_all)
                  } else {
                    stringResource(R.string.player_update_repeat_one)
                  }
                }
              }
              TextPlayerUpdate(text)
            }

            is PlayerUpdates.Shuffle -> {
              val enabled = (currentPlayerUpdate as PlayerUpdates.Shuffle).enabled
              val text = if (enabled) {
                if (playlistMode && viewModel.hasPlaylistSupport()) {
                  stringResource(R.string.player_update_shuffle_on)
                } else {
                  stringResource(R.string.player_update_shuffle_unavailable)
                }
              } else {
                stringResource(R.string.player_update_shuffle_off)
              }
              TextPlayerUpdate(text)
            }

            is PlayerUpdates.FrameInfo -> {
              val frameInfo = (currentPlayerUpdate as PlayerUpdates.FrameInfo)
              val text = if (frameInfo.totalFrames > 0) {
                stringResource(R.string.player_update_frame_with_total, frameInfo.currentFrame, frameInfo.totalFrames)
              } else {
                stringResource(R.string.player_update_frame, frameInfo.currentFrame)
              }
              TextPlayerUpdate(text)
            }

            else -> {}
          }
        }

        val areButtonsVisible = controlsShown && !areControlsLocked && !areSlidersShown

        AnimatedVisibility(
          visible = controlsShown && areControlsLocked,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier =
            Modifier
              .constrainAs(unlockControlsButton) {
                bottom.linkTo(parent.bottom, spacing.extraLarge)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
              },
        ) {
          SlideToUnlock(
            onUnlock = { viewModel.unlockControls() },
            onDraggingChanged = { isDragging -> isUnlockSliderDragging = isDragging },
          )
        }

        AnimatedVisibility(
          visible = controlsShown && !areControlsLocked,
          enter = fadeIn(playerControlsEnterAnimationSpec()),
          exit = fadeOut(playerControlsExitAnimationSpec()),
          modifier =
            Modifier.constrainAs(playerPauseButton) {
              end.linkTo(parent.absoluteRight)
              start.linkTo(parent.absoluteLeft)
              // 绔栧睆涓庢í灞忎竴鑷达細鎾斁閿紙鍚笂/涓嬩竴闆嗭級鍘嬪湪灞忓箷鍨傜洿涓嚎涓?
              top.linkTo(parent.top)
              bottom.linkTo(parent.bottom)
            },
        ) {
          val showLoadingCircle by playerPreferences.showLoadingCircle.collectAsState()
          val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
          val interaction = remember { MutableInteractionSource() }

          when {
            pausedForCache == true && showLoadingCircle -> {
              // 走统一入口 ⇒ 「外观 → 加载动画」的选择对缓冲指示也生效。
              // ⚠️ 96dp 是**基准**尺寸（还会乘用户设的缩放系数，默认 1.1×）；
              //    用 size 参数而不是 Modifier.size，否则缩放对它不生效。
              //    与原来的 LoadingIndicator 同尺寸，切换时画面不会跳。
              app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
                size = 96.dp,
              )
            }

            else -> {
              val buttonShadow =
                Brush.radialGradient(
                  0.0f to Color.Black.copy(alpha = 0.3f),
                  0.7f to Color.Transparent,
                  1.0f to Color.Transparent,
                )

              if (playlistMode && viewModel.hasPlaylistSupport()) {
                androidx.compose.foundation.layout.Row(
                  horizontalArrangement = Arrangement.spacedBy(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CenterSeekButton(forward = false) {
                    resetControlsTimestamp = System.currentTimeMillis()
                    viewModel.seekBy(-seekBackwardSeconds)
                  }

                  Surface(
                    modifier =
                      Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable(
                          enabled = viewModel.hasPrevious(),
                          onClick = {
                            resetControlsTimestamp = System.currentTimeMillis()
                            if (viewModel.hasPrevious()) viewModel.playPrevious()
                          },
                        )
                        .then(
                          if (hideBackground) {
                            Modifier.background(brush = buttonShadow, shape = CircleShape)
                          } else {
                            Modifier
                          },
                        ),
                    shape = CircleShape,
                    color =
                      if (!hideBackground) {
                        PlayerControlGlassFill
                      } else {
                        Color.Transparent
                      },
                    contentColor = controlColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border =
                      if (!hideBackground) {
                        BorderStroke(1.dp, PlayerControlGlassBorder)
                      } else {
                        null
                      },
                  ) {
                    Icon(
                      imageVector = Icons.Default.SkipPrevious,
                      contentDescription = stringResource(R.string.player_control_previous),
                      tint =
                        if (viewModel.hasPrevious()) {
                          controlColor
                        } else {
                          if (hideBackground) {
                            controlColor.copy(alpha = 0.38f)
                          } else {
                            controlColor.copy(alpha = 0.38f)
                          }
                        },
                      modifier = Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.small),
                    )
                  }

                  Surface(
                    modifier =
                      Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable(interaction, ripple(), onClick = {
                          resetControlsTimestamp = System.currentTimeMillis()
                          viewModel.pauseUnpause()
                        })
                        .then(
                          if (hideBackground) {
                            Modifier.background(brush = buttonShadow, shape = CircleShape)
                          } else {
                            Modifier
                          },
                        ),
                    shape = CircleShape,
                    color =
                      if (!hideBackground) {
                        PlayerControlGlassFill
                      } else {
                        Color.Transparent
                      },
                    contentColor = controlColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border =
                      if (!hideBackground) {
                        BorderStroke(1.dp, PlayerControlGlassBorder)
                      } else {
                        null
                      },
                  ) {
                    Image(
                      painter = rememberAnimatedVectorPainter(icon, paused == false),
                      modifier = Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.medium),
                      contentDescription = null,
                      colorFilter = ColorFilter.tint(LocalContentColor.current),
                    )
                  }

                  Surface(
                    modifier =
                      Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable(
                          enabled = viewModel.hasNext(),
                          onClick = {
                            resetControlsTimestamp = System.currentTimeMillis()
                            if (viewModel.hasNext()) viewModel.playNext()
                          },
                        )
                        .then(
                          if (hideBackground) {
                            Modifier.background(brush = buttonShadow, shape = CircleShape)
                          } else {
                            Modifier
                          },
                        ),
                    shape = CircleShape,
                    color =
                      if (!hideBackground) {
                        PlayerControlGlassFill
                      } else {
                        Color.Transparent
                      },
                    contentColor = controlColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border =
                      if (!hideBackground) {
                        BorderStroke(1.dp, PlayerControlGlassBorder)
                      } else {
                        null
                      },
                  ) {
                    Icon(
                      imageVector = Icons.Default.SkipNext,
                      contentDescription = stringResource(R.string.player_control_next),
                      tint =
                        if (viewModel.hasNext()) {
                          controlColor
                        } else {
                          if (hideBackground) {
                            controlColor.copy(alpha = 0.38f)
                          } else {
                            controlColor.copy(alpha = 0.38f)
                          }
                        },
                      modifier = Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.small),
                    )
                  }

                  CenterSeekButton(forward = true) {
                    resetControlsTimestamp = System.currentTimeMillis()
                    viewModel.seekBy(seekForwardSeconds)
                  }
                }
              } else {
                androidx.compose.foundation.layout.Row(
                  horizontalArrangement = Arrangement.spacedBy(24.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CenterSeekButton(forward = false) {
                    resetControlsTimestamp = System.currentTimeMillis()
                    viewModel.seekBy(-seekBackwardSeconds)
                  }

                  Surface(
                    modifier =
                      Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable(interaction, ripple(), onClick = {
                          resetControlsTimestamp = System.currentTimeMillis()
                          viewModel.pauseUnpause()
                        })
                        .then(
                          if (hideBackground) {
                            Modifier.background(brush = buttonShadow, shape = CircleShape)
                          } else {
                            Modifier
                          },
                        ),
                    shape = CircleShape,
                    color =
                      if (!hideBackground) {
                        PlayerControlGlassFill
                      } else {
                        Color.Transparent
                      },
                    contentColor = controlColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border =
                      if (!hideBackground) {
                        BorderStroke(1.dp, PlayerControlGlassBorder)
                      } else {
                        null
                      },
                  ) {
                    Image(
                      painter = rememberAnimatedVectorPainter(icon, paused == false),
                      modifier = Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.medium),
                      contentDescription = null,
                      colorFilter = ColorFilter.tint(LocalContentColor.current),
                    )
                  }

                  CenterSeekButton(forward = true) {
                    resetControlsTimestamp = System.currentTimeMillis()
                    viewModel.seekBy(seekForwardSeconds)
                  }
                }
              }
            }
          }
        }

        AnimatedVisibility(
          visible = controlsShown && !areControlsLocked,
          enter =
            if (!reduceMotion) {
              slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                fadeIn(playerControlsEnterAnimationSpec())
            } else {
              fadeIn(playerControlsEnterAnimationSpec())
            },
          exit =
            if (!reduceMotion) {
              slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                fadeOut(playerControlsExitAnimationSpec())
            } else {
              fadeOut(playerControlsExitAnimationSpec())
            },
          modifier =
            Modifier
              .then(
                if (showSystemNavigationBar) {
                  val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                  Modifier.padding(
                    start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                  )
                } else {
                  Modifier
                }
              )
              .constrainAs(seekbar) {
                // 绔栧睆涓庢í灞忎竴鑷达細杩涘害鏉¤创灞忓箷鏈€搴曪紙宸﹀彸涓ょ鏄凡鎾?鎬绘椂闀匡級锛?
                // 鎸夐挳绨囧帇鍦ㄥ畠涓婃柟 鈥斺€?涓庡弬鑰冪増寮忕浉鍚岋紝鎸夐挳涓嶄細璺熻繘搴︽潯鎶綅缃€?
                bottom.linkTo(parent.bottom, spacing.small)
                start.linkTo(parent.start, spacing.large)
                end.linkTo(parent.end, spacing.large)
              },
        ) {
          val invertDuration by playerPreferences.invertDuration.collectAsState()
          val seekbarStyle by appearancePreferences.seekbarStyle.collectAsState()
          var wasPlayerAlreadyPaused by remember { mutableStateOf(false) }

          SeekbarWithTimers(
            position = precisePosition,
            duration = if (preciseDuration > 0) preciseDuration else duration?.toFloat() ?: 0f,
            onValueChange = {
              if (!isSeeking) {
                // First drag frame - pause playback
                wasPlayerAlreadyPaused = paused ?: false
                if (!wasPlayerAlreadyPaused) {
                  viewModel.pause()
                }
              }
              isSeeking = true
              resetControlsTimestamp = System.currentTimeMillis()
              viewModel.seekTo(it.toInt())
            },
            onValueChangeFinished = {
              isSeeking = false
              resetControlsTimestamp = System.currentTimeMillis()
              // Unpause if it wasn't paused before seeking
              if (!wasPlayerAlreadyPaused) {
                viewModel.unpause()
              }
              viewModel.showControls()
            },
            timersInverted = Pair(false, invertDuration),
            durationTimerOnCLick = {
              resetControlsTimestamp = System.currentTimeMillis()
              playerPreferences.invertDuration.set(!invertDuration)
            },
            positionTimerOnClick = {},
            chapters = chapters.toImmutableList(),
            paused = paused ?: false,
            seekbarStyle = seekbarStyle,
            loopStart = abLoopA?.toFloat(),
            loopEnd = abLoopB?.toFloat(),
          )
        }

        AnimatedVisibility(
          visible = controlsShown && !areControlsLocked,
          enter =
            if (!reduceMotion) {
              slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                fadeIn(playerControlsEnterAnimationSpec())
            } else {
              fadeIn(playerControlsEnterAnimationSpec())
            },
          exit =
            if (!reduceMotion) {
              slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                fadeOut(playerControlsExitAnimationSpec())
            } else {
              fadeOut(playerControlsExitAnimationSpec())
            },
          modifier =
            Modifier
              .then(
                if (showSystemStatusBar) {
                  Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                  Modifier
                }
              )
              .then(
                if (showSystemNavigationBar) {
                  val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                  Modifier.padding(
                    start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                  )
                } else {
                  Modifier
                }
              )
              .constrainAs(topLeftControls) {
                top.linkTo(parent.top, if (isPortrait) spacing.extraLarge else spacing.small)
                start.linkTo(parent.start, spacing.large)
                if (isPortrait) {
                  width = Dimension.fillToConstraints
                  end.linkTo(parent.end, spacing.large)
                } else {
                  width = Dimension.fillToConstraints
                  end.linkTo(topRightControls.start, spacing.extraSmall)
                }
              },
        ) {
          if (isPortrait) {
            // 绔栧睆椤舵爮鍙湁銆岃繑鍥?+ 蹇嵎寮€鍏炽€嶄袱娈碉細鏍囬鎸埌杩涘害鏉′笂鏂瑰崟鐙竴琛岋紙瑙佷笅鏂?portraitBottomTitle锛夛紝
            // 杩欐牱椤舵爮涓嶄細鍥犱负鏍囬鍜?5 涓紑鍏虫姠瀹藉害鑰屾妸鏍囬鍘嬫垚鐪佺暐鍙枫€?
            TopPlayerControlsPortrait(
              hideBackground = hideBackground,
              onBackPress = onBackPress,
              // 椤舵爮鍙充晶鎸傚揩鎹峰紑鍏筹紙瑙ｇ爜鍣?/ 闊宠建 / 瀛楀箷 / 鏀惰棌 / 鏇村锛夛紝
              // 澶嶇敤妯睆閭ｄ竴琛岀殑娓叉煋锛屽彧鏄缉鍒?40dp 閫傞厤绔栧睆椤舵爮楂樺害
              trailing = {
                if (portraitTopButtons.isNotEmpty()) {
                  TopRightPlayerControlsLandscape(
                    buttons = portraitTopButtons,
                    chapters = chapters,
                    currentChapter = currentChapter,
                    isSpeedNonOne = isSpeedNonOne,
                    currentZoom = currentZoom,
                    aspect = aspect,
                    mediaTitle = mediaTitle,
                    hideBackground = hideBackground,
                    decoder = decoder,
                    playbackSpeed = playbackSpeed ?: 1f,
                    onBackPress = onBackPress,
                    onOpenSheet = onOpenSheet,
                    onOpenPanel = onOpenPanel,
                    viewModel = viewModel,
                    activity = activity,
                    isPortrait = true,
                    buttonSize = 40.dp,
                    onDislikeRemarkRequest = onDislikeRemarkRequest,
                  )
                }
              },
            )
          } else {
            TopLeftPlayerControlsLandscape(
              mediaTitle = mediaTitle,
              hideBackground = hideBackground,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              viewModel = viewModel,
            )
          }
        }

        AnimatedVisibility(
          visible = controlsShown && !areControlsLocked && !isPortrait,
          enter =
            if (!reduceMotion) {
              slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                fadeIn(playerControlsEnterAnimationSpec())
            } else {
              fadeIn(playerControlsEnterAnimationSpec())
            },
          exit =
            if (!reduceMotion) {
              slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                fadeOut(playerControlsExitAnimationSpec())
            } else {
              fadeOut(playerControlsExitAnimationSpec())
            },
          modifier =
            Modifier
              .then(
                if (showSystemStatusBar) {
                  Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                  Modifier
                }
              )
              .then(
                if (showSystemNavigationBar) {
                  val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                  Modifier.padding(
                    start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                  )
                } else {
                  Modifier
                }
              )
              .constrainAs(topRightControls) {
                top.linkTo(parent.top, spacing.small)
                end.linkTo(parent.end, spacing.large)
              },
        ) {
          TopRightPlayerControlsLandscape(
            buttons = topRightButtons,
            chapters = chapters,
            currentChapter = currentChapter,
            isSpeedNonOne = isSpeedNonOne,
            currentZoom = currentZoom,
            aspect = aspect,
            mediaTitle = mediaTitle,
            hideBackground = hideBackground,
            decoder = decoder,
            playbackSpeed = playbackSpeed ?: 1f,
            onBackPress = onBackPress,
            onOpenSheet = onOpenSheet,
            onOpenPanel = onOpenPanel,
            viewModel = viewModel,
            activity = activity,
            onDislikeRemarkRequest = onDislikeRemarkRequest,
          )
        }

        // 系统时钟 / 电量：**按朝向锚到按钮簇上方**、右对齐。
        // 为什么是这个位置：
        // - 进度条那一行在竖屏是媒体标题（占满宽）、在横屏是含翻转按钮的右下簇，
        //   早前硬锚 `seekbar.top` 就会和它们重叠（用户反馈「叠在标题/翻转按钮下面」）。
        // - 按钮簇**上方**那一带两种朝向都是留白，且属于「控件区」而不是画面主体 ——
        //   白字 + 阴影都成立，且不会被字幕区抢走。
        // 两个开关都关时**整块不创建**（不进 composition），避免无谓的重排。
        if (showPlayerClock || showPlayerBattery) {
          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = fadeIn(playerControlsEnterAnimationSpec()),
            exit = fadeOut(playerControlsExitAnimationSpec()),
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      end = navBarPadding.calculateRightPadding(LocalLayoutDirection.current)
                    )
                  } else {
                    Modifier
                  }
                )
                .constrainAs(statusIndicators) {
                  // ⚠️ 竖屏锚 bottomLeftControls（按钮簇）、横屏锚 bottomRightControls
                  //   （含翻转按钮）。两者都是「该朝向真正贴着进度条的那一簇」，
                  //   所以锚它们的上沿才能保证状态栏落在控件区内的留白里。
                  if (isPortrait) {
                    bottom.linkTo(bottomLeftControls.top, spacing.small)
                  } else {
                    bottom.linkTo(bottomRightControls.top, spacing.small)
                  }
                  end.linkTo(parent.end, spacing.large)
                },
          ) {
            PlayerStatusIndicators(
              showClock = showPlayerClock,
              showBattery = showPlayerBattery,
            )
          }
        }

        if (isPortrait) {
          // 鈹€鈹€ 绔栧睆搴曢儴锛氭爣棰橈紙杩涘害鏉′笂鏂癸紝鍗犳弧鏁磋锛?鈹€鈹€
          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter =
              if (!reduceMotion) {
                slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                  fadeIn(playerControlsEnterAnimationSpec())
              } else {
                fadeIn(playerControlsEnterAnimationSpec())
              },
            exit =
              if (!reduceMotion) {
                slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                  fadeOut(playerControlsExitAnimationSpec())
              } else {
                fadeOut(playerControlsExitAnimationSpec())
              },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                    )
                  } else {
                    Modifier
                  }
                )
                .constrainAs(portraitBottomTitle) {
                  bottom.linkTo(seekbar.top, spacing.smaller)
                  start.linkTo(parent.start, spacing.large)
                  end.linkTo(parent.end, spacing.large)
                },
          ) {
            PortraitBottomTitle(
              mediaTitle = mediaTitle,
              playlistInfo = viewModel.getPlaylistInfo(),
              hideBackground = hideBackground,
              // 鏈夋挱鏀鹃槦鍒楁椂鐐规爣棰樻墦寮€闃熷垪锛堜笌妯睆椤舵爮閭ｆ灇鏍囬鑳跺泭琛屼负涓€鑷达級
              clickable = playlistMode && viewModel.hasPlaylistSupport(),
              onClick = {
                resetControlsTimestamp = System.currentTimeMillis()
                onOpenSheet(Sheets.Playlist)
              },
            )
          }

          // 鈹€鈹€ 绔栧睆搴曢儴鎸夐挳鏉★細鍘熸潵鐨勫乏鍙充袱绨囧悎骞舵垚涓€鏉¤繛缁寜閽潯锛屾暣浣撳眳涓?鈹€鈹€
          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !areSlidersShown,
            enter =
              if (!reduceMotion) {
                slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                  fadeIn(playerControlsEnterAnimationSpec())
              } else {
                fadeIn(playerControlsEnterAnimationSpec())
              },
            exit =
              if (!reduceMotion) {
                slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                  fadeOut(playerControlsExitAnimationSpec())
              } else {
                fadeOut(playerControlsExitAnimationSpec())
              },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                    )
                  } else {
                    Modifier
                  }
                )
                .constrainAs(bottomLeftControls) {
                  bottom.linkTo(portraitBottomTitle.top, spacing.smaller)
                  start.linkTo(parent.start, spacing.medium)
                  end.linkTo(parent.end, spacing.medium)
                  width = Dimension.fillToConstraints
                },
          ) {
            CenteredBottomPlayerControlsPortrait(
              buttons = portraitBottomButtons,
              buttonSize = 42.dp,
              chapters = chapters,
              currentChapter = currentChapter,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              hideBackground = hideBackground,
              decoder = decoder,
              playbackSpeed = playbackSpeed ?: 1f,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = activity,
              onDislikeRemarkRequest = onDislikeRemarkRequest,
            )
          }
        } else {
          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !areSlidersShown,
            enter =
              if (!reduceMotion) {
                slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                  fadeIn(playerControlsEnterAnimationSpec())
              } else {
                fadeIn(playerControlsEnterAnimationSpec())
              },
            exit =
              if (!reduceMotion) {
                slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                  fadeOut(playerControlsExitAnimationSpec())
              } else {
                fadeOut(playerControlsExitAnimationSpec())
              },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                    )
                  } else {
                    Modifier
                  }
                )
                .constrainAs(bottomRightControls) {
                  // 妯睆淇濇寔鍘熸湁鍒嗗尯锛氬彸绨囧帇鍦ㄨ繘搴︽潯涔嬩笂銆佽创灞忓箷鍙崇紭锛堝搴﹂殢鍐呭锛屼笉鎷夋弧锛?
                  bottom.linkTo(seekbar.top, spacing.medium)
                  end.linkTo(parent.end, spacing.large)
                },
          ) {
            BottomRightPlayerControlsLandscape(
              buttons = bottomRightButtons,
              chapters = chapters,
              currentChapter = currentChapter,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              hideBackground = hideBackground,
              decoder = decoder,
              playbackSpeed = playbackSpeed ?: 1f,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = activity,
              onDislikeRemarkRequest = onDislikeRemarkRequest,
            )
          }

          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !areSlidersShown,
            enter =
              if (!reduceMotion) {
                slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                  fadeIn(playerControlsEnterAnimationSpec())
              } else {
                fadeIn(playerControlsEnterAnimationSpec())
              },
            exit =
              if (!reduceMotion) {
                slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                  fadeOut(playerControlsExitAnimationSpec())
              } else {
                fadeOut(playerControlsExitAnimationSpec())
              },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                    )
                  } else {
                    Modifier
                  }
                )
              .constrainAs(bottomLeftControls) {
                // 妯睆淇濇寔鍘熸湁鍒嗗尯锛氬乏绨囧帇鍦ㄨ繘搴︽潯涔嬩笂锛屼絾**宸︾紭鍙崇Щ**锛?
                // 缁欏乏涓嬭甯搁┗鐨勩€屼笂涓€鏉?涓嬩竴鏉°€嶆偓娴欢锛坰tart=14dp锛屽绾?84dp锛?
                // 璁╁嚭绌洪棿 鈥斺€?鍚﹀垯涓よ€呬細鍙犲湪宸︿笅瑙掋€傚彸绨囦笉鍙橈紝瀹藉害鍚冩弧鍒板畠鐨勫乏渚с€?
                bottom.linkTo(seekbar.top, spacing.medium)
                start.linkTo(parent.start, 96.dp)
                width = Dimension.fillToConstraints
                end.linkTo(bottomRightControls.start, spacing.medium)
              },
          ) {
            BottomLeftPlayerControlsLandscape(
              buttons = bottomLeftButtons,
              chapters = chapters,
              currentChapter = currentChapter,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              hideBackground = hideBackground,
              decoder = decoder,
              playbackSpeed = playbackSpeed ?: 1f,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = activity,
              onDislikeRemarkRequest = onDislikeRemarkRequest,
            )
          }
        }

      }
    }

    // 鍐呮牳鍒囨崲瑕佷氦缁欐壙杞介〉锛坢pv 鎾斁椤?PlayerActivity锛夋潵鍋氥€?
    // 杩欓噷鍏堝彇涓€娆?Activity锛氫笅闈紶缁欓潰鏉跨殑 lambda 涓嶆槸 @Composable 涓婁笅鏂囷紝
    // 涓嶈兘鍦ㄩ噷闈㈣皟 LocalActivity.current銆?
    val hostActivity = LocalActivity.current

    val sheetShown by viewModel.sheetShown.collectAsState()
    val subtitles by viewModel.subtitleTracks.collectAsState(persistentListOf())
    val audioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
    val sleepTimerTimeRemaining by viewModel.remainingTime.collectAsState()
    val speedPresets by playerPreferences.speedPresets.collectAsState()

    PlayerSheets(
      viewModel = viewModel,
      sheetShown = sheetShown,
      subtitles = subtitles.toImmutableList(),
      onAddSubtitle = viewModel::addSubtitle,
      onToggleSubtitle = { id ->
        viewModel.toggleSubtitle(id)
        // 记住这条字幕轨（只在选中那一刻记；取消选中不写盘，
        // 否则「这集不想看字幕」会把上一集的记忆也抹掉）
        if (viewModel.isSubtitleSelected(id)) {
          subtitles.toImmutableList().find { it.id == id }?.let {
            PlaybackMemory.saveSubtitleTrack(playerPreferences, viewModel.seriesKey, it)
          }
        }
      },
      isSubtitleSelected = viewModel::isSubtitleSelected,
      onRemoveSubtitle = viewModel::removeSubtitle,
      audioTracks = audioTracks.toImmutableList(),
      onAddAudio = viewModel::addAudio,
      onSelectAudio = {
        if (PlayerLib.getPropertyInt("aid") == it.id) {
          PlayerLib.setPropertyBoolean("aid", false)
        } else {
          PlayerLib.setPropertyInt("aid", it.id)
          // 璁颁綇杩欐潯闊宠建锛堟寚绾瑰尮閰嶏級锛屼笅涓€闆嗙浉鍚岄煶杞ㄨ嚜鍔ㄩ€変腑锛涘紑鍏冲叧闂椂鍐呴儴鐩存帴蹇界暐
          PlaybackMemory.saveAudioTrack(playerPreferences, viewModel.seriesKey, it)
        }
      },
      chapter = chapters.getOrNull(currentChapter ?: 0),
      chapters = chapters.toImmutableList(),
      onSeekToChapter = {
        PlayerLib.setPropertyInt("chapter", it)
        viewModel.unpause()
      },
      decoder = decoder,
      onUpdateDecoder = { PlayerLib.setPropertyString("hwdec", it.value) },
      speed = playbackSpeed ?: playerPreferences.defaultSpeed.get(),
      onSpeedChange = {
        PlayerLib.setPropertyFloat("speed", it.toFixed(2))
        // 璁颁綇杩欎釜鍊嶉€燂紝鍚屽墽涓嬩竴闆嗘部鐢紱寮€鍏冲叧闂椂鍐呴儴鐩存帴蹇界暐
        PlaybackMemory.saveSpeed(playerPreferences, viewModel.seriesKey, it.toFixed(2))
        // b4 global speed memory: cross-episode, cross-restart. Ignored internally when off.
        if (playerPreferences.rememberGlobalSpeed.get()) {
          playerPreferences.lastPlaybackSpeed.set(it.toFixed(2))
        }
      },
      onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
      onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
      onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
      onResetSpeedPresets = playerPreferences.speedPresets::delete,
      speedPresets = speedPresets.map { it.toFloat() }.sorted(),
      onResetDefaultSpeed = {
        PlayerLib.setPropertyFloat("speed", playerPreferences.defaultSpeed.deleteAndGet().toFixed(2))
      },
      sleepTimerTimeRemaining = sleepTimerTimeRemaining,
      onStartSleepTimer = viewModel::startTimer,
      onOpenPanel = onOpenPanel,
      onShowSheet = onOpenSheet,
      onSwitchToGsy = { (hostActivity as? PlayerActivity)?.switchToGsyPlayer() },
      onDismissRequest = { onOpenSheet(Sheets.None) },
    )

    val panel by viewModel.panelShown.collectAsState()
    PlayerPanels(
      panelShown = panel,
      onDismissRequest = { onOpenPanel(Panels.None) },
    )
  }
}
