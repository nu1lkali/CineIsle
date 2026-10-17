package app.marlboroadvance.mpvex

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.update.UpdateDialog
import app.marlboroadvance.mpvex.utils.update.UpdateViewModel
import app.marlboroadvance.mpvex.ui.browser.MainScreen
import app.marlboroadvance.mpvex.ui.lock.AppLockGate
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.permission.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/** 启动页各阶段时长（毫秒）：图标弹入 + 名称逐字 → 停留 → 揭幕淡出。 */
private const val SPLASH_ENTER_MS = 850L
private const val SPLASH_HOLD_MS = 650L
private const val SPLASH_EXIT_MS = 420L

/**
 * Main entry point for the application
 */
class MainActivity : ComponentActivity() {
  private val appearancePreferences by inject<AppearancePreferences>()
  private val networkRepository by inject<NetworkRepository>()
  
  // Create a coroutine scope tied to the activity lifecycle
  private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  // Register the ActivityResultLauncher at class level
  private val mediaAccessLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
    PermissionUtils.handleMediaAccessResult(result.resultCode)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    PermissionUtils.setMediaAccessLauncher(mediaAccessLauncher)

    // Register proxy lifecycle observer for network streaming
    lifecycle.addObserver(app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.ProxyLifecycleObserver())

    setContent {
      // Set up theme and edge-to-edge display
      val dark by appearancePreferences.darkMode.collectAsState()
      val isSystemInDarkTheme = isSystemInDarkTheme()
      val isDarkMode = dark == DarkMode.Dark || (dark == DarkMode.System && isSystemInDarkTheme)
      enableEdgeToEdge(
        SystemBarStyle.auto(
          lightScrim = Color.White.toArgb(),
          darkScrim = Color.Transparent.toArgb(),
        ) { isDarkMode },
      )

      // Auto-connect to saved network connections
      LaunchedEffect(Unit) {
        autoConnectToNetworks()
      }

      MpvexTheme {
        Surface {
          // 启动页盖在主界面之上：背后 Navigator 已经完成首帧布局，
          // 淡出后直接是可用界面，避免「先白屏、再跳一下」的观感。
          var showSplash by remember { mutableStateOf(true) }

          // 应用锁（b7）：锁着时 Navigator 和启动页整块不组合，只显示解锁页；
          // 未设 PIN 或没开锁时等于不存在，内容原样渲染。
          AppLockGate {
            Box(modifier = Modifier.fillMaxSize()) {
              Navigator()

              if (showSplash) {
                AppSplashScreen(onFinished = { showSplash = false })
              }
            }
          }
        }

        // 首帧成功渲染即标记 boot_ok，清除「上次启动卡在 Application 阶段」的疑似状态。
        LaunchedEffect(Unit) {
          App.markBootOk()
        }
      }
    }
  }

  override fun onDestroy() {
    try {
      super.onDestroy()
    } catch (e: Exception) {
      Log.e("MainActivity", "Error during onDestroy", e)
    }
  }

  /**
   * Auto-connect to network connections that are marked for auto-connection
   */
  private suspend fun autoConnectToNetworks() {
    // Delay auto-connect to let UI settle first
    kotlinx.coroutines.delay(500)
    
    // Use coroutineScope for properly structured concurrency
    withContext(Dispatchers.IO) {
      try {
        val autoConnectConnections = networkRepository.getAutoConnectConnections()
        autoConnectConnections.forEach { connection ->
          withContext(Dispatchers.Main) {
            Log.d("MainActivity", "Auto-connecting to: ${connection.name}")
          }
          networkRepository.connect(connection)
            .onSuccess {
              withContext(Dispatchers.Main) {
                Log.d("MainActivity", "Auto-connected successfully: ${connection.name}")
              }
            }
            .onFailure { e ->
              withContext(Dispatchers.Main) {
                Log.e("MainActivity", "Auto-connect failed for ${connection.name}: ${e.message}")
              }
            }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Log.e("MainActivity", "Error during auto-connect", e)
        }
      }
    }
  }

  /**
   * 启动页：品牌化「揭幕」动画。
   *
   * 三段式时序（见顶部 SPLASH_*_MS 常量）：
   *  1. 进入：logo 以弹性曲线弹入（scale 0.6→1 + alpha 0→1），背后 tertiary 光晕呼吸；
   *           名称错峰上移淡入。
   *  2. 停留：画面稳定，光晕持续脉冲。
   *  3. 揭幕：整体放大并淡出，露出背后已布局好的主界面（Navigation3 覆盖层方案，
   *           避免「先白屏、再跳一下」）。
   *
   * 用覆盖层而不是替换内容：主界面在背后已经完成首帧布局，
   * 淡出后直接是可用界面。
   */
  @Composable
  private fun AppSplashScreen(onFinished: () -> Unit) {
    val appName = stringResource(id = R.string.app_name)
    val appNameEn = stringResource(id = R.string.i18n_project_name_en)
    val colors = MaterialTheme.colorScheme

    // 阶段：进入 → 停留 → 揭幕退出
    var entered by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }

    // 图标弹入：挂载即 0.6→1（scale + alpha），FastOutSlowIn 弹性曲线。
    // ⚠️ 本版本 animateFloatAsState 没有 initialValue 参数，弹入初值必须用 Animatable 起手。
    val logoScaleAnim = remember { Animatable(0.6f) }
    val logoAlphaAnim = remember { Animatable(0f) }
    val logoScale = logoScaleAnim.value
    val logoAlpha = logoAlphaAnim.value
    // 名称错峰浮现：logo 落定后上移 + 淡入
    val nameAlpha by animateFloatAsState(
      targetValue = if (entered) 1f else 0f,
      animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
    )
    val nameOffsetY by animateFloatAsState(
      targetValue = if (entered) 0f else 16f,
      animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
    )
    // 揭幕退出：整体放大 + 淡出，露出背后的主界面
    val exitScale by animateFloatAsState(
      targetValue = if (exiting) 1.2f else 1f,
      animationSpec = tween(durationMillis = SPLASH_EXIT_MS.toInt(), easing = FastOutSlowInEasing),
    )
    val exitAlpha by animateFloatAsState(
      targetValue = if (exiting) 0f else 1f,
      animationSpec = tween(durationMillis = SPLASH_EXIT_MS.toInt(), easing = FastOutSlowInEasing),
      finishedListener = { if (exiting) onFinished() },
    )

    // 光晕呼吸：tertiary 色在 logo 背后做无限脉冲（柔和 glow，无需 blur）
    val infinite = rememberInfiniteTransition(label = "splashHalo")
    val haloAlpha by infinite.animateFloat(
      initialValue = 0.22f,
      targetValue = 0.5f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1500, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "haloAlpha",
    )
    val haloScale by infinite.animateFloat(
      initialValue = 0.9f,
      targetValue = 1.15f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1500, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "haloScale",
    )

    LaunchedEffect(Unit) {
      launch {
        logoScaleAnim.animateTo(
          targetValue = 1f,
          animationSpec =
            tween(durationMillis = SPLASH_ENTER_MS.toInt(), easing = FastOutSlowInEasing),
        )
      }
      launch {
        logoAlphaAnim.animateTo(
          targetValue = 1f,
          animationSpec =
            tween(durationMillis = (SPLASH_ENTER_MS * 0.8).toInt(), easing = FastOutSlowInEasing),
        )
      }
      delay(SPLASH_ENTER_MS)
      entered = true
      delay(SPLASH_HOLD_MS)
      exiting = true
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            alpha = exitAlpha
            scaleX = exitScale
            scaleY = exitScale
          }
          // 启动页必须是不透明底色：用 surface 完全盖住背后的主界面，
          // 否则径向辉光中心是半透明的、主界面内容会从中间透出来。
          .background(colors.surface)
          // 启动页展示期间吞掉点击，避免误触背后的界面
          .pointerInput(Unit) { detectTapGestures { } },
      contentAlignment = Alignment.Center,
    ) {
      // 品牌化径向辉光层（叠在 surface 之上、不影响不透明度）：中心 primary 微光 → 透明
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.radialGradient(
                colors =
                  listOf(
                    colors.primary.copy(alpha = 0.18f),
                    Color.Transparent,
                  ),
              ),
            ),
      )

      // 光晕层（logo 背后）
      Box(
        modifier =
          Modifier
            .size(220.dp)
            .graphicsLayer {
              alpha = haloAlpha
              scaleX = haloScale
              scaleY = haloScale
            }.background(
              Brush.radialGradient(
                colors =
                  listOf(
                    colors.tertiary.copy(alpha = 0.85f),
                    colors.tertiary.copy(alpha = 0f),
                  ),
              ),
              shape = androidx.compose.foundation.shape.CircleShape,
            ),
      )

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 用 ImageView 渲染启动器图标：adaptive-icon 无法通过 painterResource 加载
        AndroidView(
          modifier =
            Modifier
              .size(96.dp)
              .graphicsLayer {
                scaleX = logoScale
                scaleY = logoScale
                alpha = logoAlpha
              },
          factory = { ctx ->
            ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) }
          },
        )

        Spacer(Modifier.height(20.dp))

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
              Modifier.graphicsLayer {
                alpha = nameAlpha
                translationY = nameOffsetY.dp.roundToPx().toFloat()
              },
        ) {
          Text(
            text = appName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
          )

          Spacer(Modifier.height(4.dp))

          Text(
            text = appNameEn,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
          )
        }
      }
    }
  }

  /**
   * Navigator that handles screen transitions and provides shared states
   */
  @Composable
  fun Navigator() {
    val backstack = rememberNavBackStack(MainScreen)

    @Suppress("UNCHECKED_CAST")
    val typedBackstack = backstack as NavBackStack<Screen>

    val context = LocalContext.current
    val currentVersion = BuildConfig.VERSION_NAME.replace("-dev", "")

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? = if (BuildConfig.ENABLE_UPDATE_FEATURE) {
      viewModel(context as ComponentActivity)
    } else {
      null
    }
    val updateState by (updateViewModel?.updateState ?: MutableStateFlow(UpdateViewModel.UpdateState.Idle)).collectAsState()
    val isDownloading by (updateViewModel?.isDownloading ?: MutableStateFlow(false)).collectAsState()
    val downloadProgress by (updateViewModel?.downloadProgress ?: MutableStateFlow(0f)).collectAsState()

    // Provide both LocalBackStack and the LazyList/Grid states to all screens
    CompositionLocalProvider(
      LocalBackStack provides typedBackstack
    ) {
      NavDisplay(
        backStack = typedBackstack,
        onBack = { typedBackstack.removeLastOrNull() },
        entryProvider = { route -> NavEntry(route) { route.Content() } },
        popTransitionSpec = {
          (
            fadeIn(animationSpec = tween(220)) +
              slideIn(animationSpec = tween(220)) { IntOffset(-it.width / 2, 0) }
          ) togetherWith (
              fadeOut(animationSpec = tween(220)) +
                slideOut(animationSpec = tween(220)) { IntOffset(it.width / 2, 0) }
          )
        },
        transitionSpec = {
          (
            fadeIn(animationSpec = tween(220)) +
              slideIn(animationSpec = tween(220)) { IntOffset(it.width / 2, 0) }
          ) togetherWith (
              fadeOut(animationSpec = tween(220)) +
                slideOut(animationSpec = tween(220)) { IntOffset(-it.width / 2, 0) }
          )
        },
        predictivePopTransitionSpec = {
          (
            fadeIn(animationSpec = tween(220)) +
              scaleIn(
                animationSpec = tween(220, delayMillis = 30),
                initialScale = .9f,
                TransformOrigin(-1f, .5f),
              )
          ) togetherWith (
              fadeOut(animationSpec = tween(220)) +
                scaleOut(
                  animationSpec = tween(220, delayMillis = 30),
                  targetScale = .9f,
                  TransformOrigin(-1f, .5f),
                )
          )
        },
      )

      // Display Update Dialog when appropriate (only if update feature is enabled)
      if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
        when (updateState) {
          is UpdateViewModel.UpdateState.Available -> {
            val release = (updateState as UpdateViewModel.UpdateState.Available).release
            UpdateDialog(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              actionLabel = if (isDownloading) "Downloading..." else "Download",
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.downloadUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName.removePrefix("v")) }
            )
          }
          is UpdateViewModel.UpdateState.ReadyToInstall -> {
            val release = (updateState as UpdateViewModel.UpdateState.ReadyToInstall).release
            UpdateDialog(
              release = release,
              isDownloading = isDownloading,
              progress = downloadProgress,
              actionLabel = "Install",
              currentVersion = currentVersion,
              onDismiss = { updateViewModel.dismiss() },
              onAction = { updateViewModel.installUpdate(release) },
              onIgnore = { updateViewModel.ignoreVersion(release.tagName.removePrefix("v")) }
            )
          }
          else -> {}
        }
      }
    }
  }
}
