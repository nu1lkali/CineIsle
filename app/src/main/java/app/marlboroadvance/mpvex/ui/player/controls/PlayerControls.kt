package app.marlboroadvance.mpvex.ui.player.controls

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
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekPlayerUpdate
import app.marlboroadvance.mpvex.ui.player.controls.components.SeekbarWithTimers
import app.marlboroadvance.mpvex.ui.player.controls.components.SlideToUnlock
import app.marlboroadvance.mpvex.ui.player.controls.components.SpeedControlSlider
import app.marlboroadvance.mpvex.ui.player.controls.components.TextPlayerUpdate
import app.marlboroadvance.mpvex.ui.player.controls.components.VolumeSlider
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
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
 * 竖屏顶栏右侧的「快捷开关」集合。
 *
 * 这几个都是「设一次就不用再碰」的低频项：解码器 / 音轨 / 字幕 / 收藏 / 更多。
 * 放右上角既不占底部空间，也不影响常用操作。
 *
 * 刻意只保留 5 个：5 × 40dp + 间距 ≈ 220dp，竖屏顶栏还能给标题留出可读宽度；
 * 再多会把标题压成省略号。章节类按钮不在这里（它们落到左簇），避免顶栏被撑满。
 *
 * 用户仍然可以在「播放器控件」设置里自由增删 —— 这里只决定「显示在哪个区域」，
 * 不是硬性订阅；把某一项移出竖屏列表，顶栏自然也不会出现它。
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
 * 竖屏底部的**排列顺序**分组：画面类工具排在按钮条的右半边。
 *
 * 竖屏底部已经合并成一条连续按钮条（见 [CenteredBottomPlayerControlsPortrait]），
 * 这里不再决定「左右簇」的坐标，而是决定**谁排在右边**：
 * 这一组里的排在按钮条末尾，其余按用户配置的先后顺序排在前面。
 *
 * 屏幕旋转的按钮按用户反馈挪到了右边（原来画中画的位置），画中画则回到左边，
 * 两者位置对调 —— 旋转是「换一个视角看」的动作，和比例/缩放这类画面工具放一起更顺。
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
  val appearancePreferences = koinInject<AppearancePreferences>()
  val hideBackground by appearancePreferences.hidePlayerButtonsBackground.collectAsState()
  val playerPreferences = koinInject<PlayerPreferences>()
  val audioPreferences = koinInject<AudioPreferences>()
  val showSystemStatusBar by playerPreferences.showSystemStatusBar.collectAsState()
  val showSystemNavigationBar by playerPreferences.showSystemNavigationBar.collectAsState()
  val interactionSource = remember { MutableInteractionSource() }
  val controlsShown by viewModel.controlsShown.collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val seekBarShown by viewModel.seekBarShown.collectAsState()
  val pausedForCache by MPVLib.propBoolean["paused-for-cache"].collectAsState()
  val paused by MPVLib.propBoolean["pause"].collectAsState()
  val duration by MPVLib.propInt["duration"].collectAsState()
  val position by MPVLib.propInt["time-pos"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val playbackSpeed by MPVLib.propFloat["speed"].collectAsState()
  val doubleTapSeekAmount by viewModel.doubleTapSeekAmount.collectAsState()
  val showDoubleTapOvals by playerPreferences.showDoubleTapOvals.collectAsState()
  val showSeekTime by playerPreferences.showSeekTimeWhileSeeking.collectAsState()
  var isSeeking by remember { mutableStateOf(false) }
  var resetControlsTimestamp by remember { mutableStateOf(0L) }
  val seekText by viewModel.seekText.collectAsState()
  val currentChapter by MPVLib.propInt["chapter"].collectAsState()
  val mpvDecoder by MPVLib.propString["hwdec-current"].collectAsState()
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

  val topRightControlsPref by appearancePreferences.topRightControls.collectAsState()
  val bottomRightControlsPref by appearancePreferences.bottomRightControls.collectAsState()
  val bottomLeftControlsPref by appearancePreferences.bottomLeftControls.collectAsState()
  val portraitBottomControlsPref by appearancePreferences.portraitBottomControls.collectAsState()

  val (topRightButtons, bottomRightButtons, bottomLeftButtons) =
    remember(
      topRightControlsPref,
      bottomRightControlsPref,
      bottomLeftControlsPref,
    ) {
      val usedButtons = mutableSetOf<app.marlboroadvance.mpvex.preferences.PlayerButton>()
      val topR = appearancePreferences.parseButtons(topRightControlsPref, usedButtons)
      val bottomR = appearancePreferences.parseButtons(bottomRightControlsPref, usedButtons)
      val bottomL = appearancePreferences.parseButtons(bottomLeftControlsPref, usedButtons)
      listOf(topR, bottomR, bottomL)
    }

  // 竖屏控件列表按用途拆开：
  //   ① 顶栏右侧快捷开关：解码器 / 音轨 / 字幕 / 收藏 / 更多；
  //   ② 底部按钮条：剩下的全部，按「播放状态类 → 画面类」排成一条，居中显示。
  // 上一版是左右两簇分别贴边，中间空出一大块，看起来像被一条分割线切开；
  // 现在合并成一条连续（必要时可横向滚动）的按钮条，标题单独一行压在进度条上方。
  val (portraitTopButtons, portraitSideButtons) = remember(portraitBottomControlsPref) {
    appearancePreferences
      .parseButtons(portraitBottomControlsPref, mutableSetOf())
      .partition { it in PORTRAIT_TOP_SHORTCUTS }
  }
  // 上/下一集固定显示在屏幕正中的播放键两侧，从底部按钮条里剔除，避免重复出现；
  // 同时把「画面类」统一挪到按钮条末尾，保证它们始终在右半边。
  val portraitBottomButtons = remember(portraitSideButtons) {
    val playState = portraitSideButtons.filter { it !in PORTRAIT_RIGHT_CLUSTER }
    val displayState = portraitSideButtons.filter { it in PORTRAIT_RIGHT_CLUSTER }
    (playState + displayState)
      .filter { it != PlayerButton.PREVIOUS && it != PlayerButton.NEXT }
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
        // 竖屏：标题放在进度条上方，单独一条约束（横屏不使用）
        val portraitBottomTitle = createRef()
        val (playerUpdates) = createRefs()

        val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
        val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
        val brightness by viewModel.currentBrightness.collectAsState()
        val volume by viewModel.currentVolume.collectAsState()
        val mpvVolume by MPVLib.propInt["volume"].collectAsState()
        val swapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
        val reduceMotion by playerPreferences.reduceMotion.collectAsState()

        val activity = LocalActivity.current as PlayerActivity
        val aspect by viewModel.videoAspect.collectAsState()
        val currentZoom by viewModel.videoZoom.collectAsState()

        val rawMediaTitle by MPVLib.propString["media-title"].collectAsState()
        val mediaTitle by remember(rawMediaTitle, activity) {
          derivedStateOf {
            rawMediaTitle?.takeIf { it.isNotBlank() }
              ?: activity.getTitleForControls()
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
                  // Full speed control slider
                  SpeedControlSlider(currentSpeed = currentSpeed)
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
              // 竖屏与横屏一致：播放键（含上/下一集）压在屏幕垂直中线上
              top.linkTo(parent.top)
              bottom.linkTo(parent.bottom)
            },
        ) {
          val showLoadingCircle by playerPreferences.showLoadingCircle.collectAsState()
          val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
          val interaction = remember { MutableInteractionSource() }

          when {
            pausedForCache == true && showLoadingCircle -> {
              LoadingIndicator(
                modifier = Modifier.size(96.dp),
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
                  horizontalArrangement = Arrangement.spacedBy(24.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
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
                }
              } else {
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
                // 竖屏与横屏一致：进度条贴屏幕最底（左右两端是已播/总时长），
                // 按钮簇压在它上方 —— 与参考版式相同，按钮不会跟进度条抢位置。
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
            // 竖屏顶栏只有「返回 + 快捷开关」两段：标题挪到进度条上方单独一行（见下方 portraitBottomTitle），
            // 这样顶栏不会因为标题和 5 个开关抢宽度而把标题压成省略号。
            TopPlayerControlsPortrait(
              hideBackground = hideBackground,
              onBackPress = onBackPress,
              // 顶栏右侧挂快捷开关（解码器 / 音轨 / 字幕 / 收藏 / 更多），
              // 复用横屏那一行的渲染，只是缩到 40dp 适配竖屏顶栏高度
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
          )
        }

        if (isPortrait) {
          // ── 竖屏底部：标题（进度条上方，占满整行） ──
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
              // 有播放队列时点标题打开队列（与横屏顶栏那枚标题胶囊行为一致）
              clickable = playlistMode && viewModel.hasPlaylistSupport(),
              onClick = {
                resetControlsTimestamp = System.currentTimeMillis()
                onOpenSheet(Sheets.Playlist)
              },
            )
          }

          // ── 竖屏底部按钮条：原来的左右两簇合并成一条连续按钮条，整体居中 ──
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
                  // 横屏保持原有分区：右簇压在进度条之上、贴屏幕右缘（宽度随内容，不拉满）
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
                  // 横屏保持原有分区：左簇压在进度条之上、贴屏幕左缘，
                  // 宽度吃满到右簇左侧，行内内容靠左对齐
                  bottom.linkTo(seekbar.top, spacing.medium)
                  start.linkTo(parent.start, spacing.large)
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
            )
          }
        }

      }
    }

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
      onToggleSubtitle = viewModel::toggleSubtitle,
      isSubtitleSelected = viewModel::isSubtitleSelected,
      onRemoveSubtitle = viewModel::removeSubtitle,
      audioTracks = audioTracks.toImmutableList(),
      onAddAudio = viewModel::addAudio,
      onSelectAudio = {
        if (MPVLib.getPropertyInt("aid") == it.id) {
          MPVLib.setPropertyBoolean("aid", false)
        } else {
          MPVLib.setPropertyInt("aid", it.id)
          // 记住这条音轨（指纹匹配），下一集相同音轨自动选中；开关关闭时内部直接忽略
          PlaybackMemory.saveAudioTrack(playerPreferences, viewModel.seriesKey, it)
        }
      },
      chapter = chapters.getOrNull(currentChapter ?: 0),
      chapters = chapters.toImmutableList(),
      onSeekToChapter = {
        MPVLib.setPropertyInt("chapter", it)
        viewModel.unpause()
      },
      decoder = decoder,
      onUpdateDecoder = { MPVLib.setPropertyString("hwdec", it.value) },
      speed = playbackSpeed ?: playerPreferences.defaultSpeed.get(),
      onSpeedChange = {
        MPVLib.setPropertyFloat("speed", it.toFixed(2))
        // 记住这个倍速，同剧下一集沿用；开关关闭时内部直接忽略
        PlaybackMemory.saveSpeed(playerPreferences, viewModel.seriesKey, it.toFixed(2))
      },
      onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
      onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
      onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
      onResetSpeedPresets = playerPreferences.speedPresets::delete,
      speedPresets = speedPresets.map { it.toFloat() }.sorted(),
      onResetDefaultSpeed = {
        MPVLib.setPropertyFloat("speed", playerPreferences.defaultSpeed.deleteAndGet().toFixed(2))
      },
      sleepTimerTimeRemaining = sleepTimerTimeRemaining,
      onStartSleepTimer = viewModel::startTimer,
      onOpenPanel = onOpenPanel,
      onShowSheet = onOpenSheet,
      onDismissRequest = { onOpenSheet(Sheets.None) },
    )

    val panel by viewModel.panelShown.collectAsState()
    PlayerPanels(
      panelShown = panel,
      onDismissRequest = { onOpenPanel(Panels.None) },
    )
  }
}
