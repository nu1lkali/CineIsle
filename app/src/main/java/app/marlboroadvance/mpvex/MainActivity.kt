package app.marlboroadvance.mpvex

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.marlboroadvance.mpvex.ui.cine.CineRoute
import app.marlboroadvance.mpvex.ui.cine.LocalUiStyle
import app.marlboroadvance.mpvex.ui.cine.UiStyle
import app.marlboroadvance.mpvex.ui.lock.AppLockGate
import app.marlboroadvance.mpvex.ui.preferences.AppIconManager
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

/**
 * 启动页时序（毫秒）。
 *
 * ## 节奏（总计约 1.22s）
 *
 * | 阶段 | 区间 | 时长 |
 * |---|---|---|
 * | logo 淡入 + 收拢 | 0 → [SPLASH_LOGO_IN_MS] | 280 |
 * | 扫光 | [SPLASH_SWEEP_START_MS] → 640 | 520 |
 * | **静止停留** | 640 → [SPLASH_MIN_STAY_MS] | 240 |
 * | 退场 | [SPLASH_MIN_STAY_MS] → +[SPLASH_EXIT_MS] | 340 |
 *
 * ⚠️「静止停留」这一段**不能省**。第一版把开始退场定在 520ms，而扫光恰好 640ms 才收尾，
 * 结果画面一稳定就立刻淡出，用户感知是「刚出现就没了」—— 快，但读完不了任何东西。
 * 品牌展示的可用下限是「光带完整走完 + 留出一口气的静止」，约 1.1~1.3s 是舒适区；
 * 再长才开始像白等（早前 850+650+420 ≈ 1.92s 就是反例）。
 *
 * ⚠️ 这几个值之间是**包含关系**，不是相加：
 * - 进场 [SPLASH_LOGO_IN_MS] 与扫光 [SPLASH_SWEEP_START_MS] + [SPLASH_SWEEP_MS] 并行；
 * - [SPLASH_MIN_STAY_MS] 是从**开始**算到「进入退场」的时刻，必须 > 扫光结束点，
 *   否则光带会在淡出中途被切断。
 */
private const val SPLASH_LOGO_IN_MS = 280L
private const val SPLASH_SWEEP_START_MS = 120L
private const val SPLASH_SWEEP_MS = 520L
private const val SPLASH_MIN_STAY_MS = 880L
private const val SPLASH_EXIT_MS = 340L

/** 启动页 logo 的边长。取 104dp ≈ 系统启动窗口图标的观感尺寸，位置与系统那层同为屏幕正中。 */
private val SPLASH_LOGO_SIZE = 104.dp

/** 标题相对屏幕中心的纵向偏移。**logo 本身严格居中**，标题往它下方让位（见注释）。 */
private val SPLASH_TITLE_OFFSET = 78.dp

/**
 * 启动页是否已经在**本进程内**播过。
 *
 * ⚠️ 必须放在进程级而不是 `remember`：`remember` 只活到 Activity 销毁，
 * 屏幕旋转会重建 Activity ⇒ 每转一次屏就重播一次启动动画。
 * 早前正是这个行为（转屏 = 再看两秒 logo）。
 */
private object SplashGate {
  var shownThisProcess = false
}

/**
 * saved state 的体积预算（字节）。
 *
 * Binder 事务硬上限是 1MB，但那是**整个进程所有 Activity 的待发事务**共享的，
 * 所以这里取一半（512KB）留余量 —— 别的 Activity / 系统回调也在用同一条 Binder。
 */
private const val SAVED_STATE_BUDGET_BYTES = 512 * 1024

/** `SavedStateRegistry` 在 outState 里的容器 key（androidx.lifecycle 内部常量）。 */
private const val BUNDLABLE_REGISTRY_KEY = "androidx.lifecycle.BundlableSavedStateRegistry.key"

/** Compose `DisposableSaveableStateRegistry` 注册到上者时用的 key 前缀。 */
private const val SAVEABLE_KEY_PREFIX = "SaveableStateRegistry:"

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

  /**
   * 保存实例状态前的**体积闸门**。
   *
   * ⚠️ 背景（线上日志：`TransactionTooLargeException: data parcel size 2226748 bytes`）：
   * 崩在 `activityStopped` → `IActivityClientController$Stub$Proxy.activityStopped` 的 Binder
   * 回传上。`Bundle stats` 里 2.2MB 几乎全部集中在 Compose 那一个 `SaveableStateRegistry:-1`
   * 条目下（两个各约 1.1MB 的子项），而 Binder 事务的硬上限是 1MB ⇒ 切后台/按 Home 时必崩，
   * 用户毫无预警。
   *
   * 这些状态是 `rememberSaveable` 写进去的（本项目 80 处调用点存的都是 bool / 短字符串 /
   * 枚举，具体是哪个页面累积到 MB 级还没坐实，需要复现路径才能定位）。但**崩溃本身必须挡住**，
   * 所以这里做一道带阈值的裁剪，而不是无脑清空：
   *
   * - 正常体积（几百字节 ~ 几十 KB）**原样保留** —— 旋转屏幕该恢复的照旧恢复；
   * - 逼近上限（> 512KB）才丢掉 Compose 那一整块，换来「不崩」；
   *   代价仅是「进程被系统杀掉后重建」时滚动位置 / 弹窗开合回到默认值。
   *
   * ⚠️ 只清 `SaveableStateRegistry:` 前缀的项。`BundlableSavedStateRegistry` 里其他
   *    provider（例如自己注册的 SavedStateProvider）一律不动。
   */
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    runCatching {
      // 量不出来时（含 writeBundle 自己就抛）按「已超限」处理，宁可丢滚动位置也不能崩。
      val size = runCatching { approximateStateSize(outState) }.getOrElse { Int.MAX_VALUE }
      if (size <= SAVED_STATE_BUDGET_BYTES) return@runCatching

      val registry = outState.getBundle(BUNDLABLE_REGISTRY_KEY)
      val dropped = registry?.keySet()?.filter { it.startsWith(SAVEABLE_KEY_PREFIX) }.orEmpty()
      dropped.forEach { registry?.remove(it) }
      android.util.Log.w(
        "MainActivity",
        "saved state 体积 $size 字节，已丢弃 ${dropped.size} 项 Compose 状态，" +
          "避免 stop 时抛 TransactionTooLargeException",
      )
    }
  }

  /**
   * 用一次 Parcel 序列化估算 bundle 体积。
   *
   * Binder 判 1MB 上限用的就是这个字节数，所以比「数 key 个数」之类的启发式准确得多。
   * `recycle()` 必须执行 —— Parcel 是池化的，不还回去会累积 native 内存。
   */
  private fun approximateStateSize(bundle: Bundle): Int {
    val parcel = android.os.Parcel.obtain()
    return try {
      parcel.writeBundle(bundle)
      parcel.dataSize()
    } finally {
      parcel.recycle()
    }
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
          //
          // ⚠️ 初值读进程级标记，不用恒 true：转屏会重建 Activity，
          //   `remember` 跟着重置 ⇒ 每转一次屏就重播一次启动动画。
          var showSplash by remember { mutableStateOf(!SplashGate.shownThisProcess) }

          // 主界面在启动页背后就开始「拉近」（0.98 → 1）。
          // 与启动页淡出同一时长 ⇒ 观感是主界面接住 logo，而不是启动页消失后再出现。
          val contentScale by
            animateFloatAsState(
              targetValue = if (showSplash) 0.98f else 1f,
              animationSpec =
                tween(durationMillis = SPLASH_EXIT_MS.toInt(), easing = FastOutSlowInEasing),
              label = "splashContentScale",
            )

          // 应用锁（b7）：锁着时 Navigator 和启动页整块不组合，只显示解锁页；
          // 未设 PIN 或没开锁时等于不存在，内容原样渲染。
          AppLockGate {
            Box(modifier = Modifier.fillMaxSize()) {
              Box(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                      scaleX = contentScale
                      scaleY = contentScale
                    },
              ) {
                Navigator()
              }

              if (showSplash) {
                AppSplashScreen(
                  onFinished = {
                    SplashGate.shownThisProcess = true
                    showSplash = false
                  },
                )
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
   * 启动页：**与桌面图标同色的品牌揭幕**。
   *
   * ## 三段式（总时长约 800ms）
   *
   * 1. 进场（260ms）：logo 淡入 + 0.92→1 的轻微收拢，标题错峰浮现；
   * 2. 扫光（100ms 起，420ms 结束）：一道柔光斜向扫过 logo 一次，**不循环**；
   * 3. 退场（280ms）：logo 上移 14dp 并淡出，同时背后的主界面从 0.98 拉近到 1
   *    —— 观感是「主界面接住 logo」，而不是早前那种整体放大 1.2 的「被推远」。
   *
   * ## 三个刻意的取舍
   *
   * 1. **logo 严格居中**。系统启动窗口（Android 12+）把应用图标放在窗口正中，
   *    早前这里是 `Column`（图标 + 两行文字）整体居中 ⇒ 图标被下方文字顶到中上，
   *    两段动画衔接时图标会**往上跳一截**。现在图标走 `align(Center)`，
   *    标题用固定偏移挂在它下方，图标全程不动。
   * 2. **底色 = 当前桌面图标的背景色**。图标资源本身是带底色方块的 adaptive icon，
   *    直接铺在别的底色上就是一块「贴纸」；把启动页底色设成同一个颜色后，
   *    方块的边缘直接融进背景，只看得见图形本身。（9 套图标主题各有底色，见 AppIconManager）
   *    文字颜色按底色亮度自动切换深浅，浅色主题（极简 / 樱 / 素纸）不会白字看不清。
   * 3. **只留中文名**。英文名作副标题在中文环境下没有信息量，反而把视觉重心拉散。
   *
   * ## 动效降级
   *
   * 跟随系统的「动画时长缩放」= 0（开发者选项 / 无障碍里关闭动画）时，
   * 跳过扫光与缩放，只保留淡入淡出，且总时长压到 320ms。
   */
  @Composable
  private fun AppSplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val iconStyle = remember { AppIconManager.currentStyleReadOnly(context) }
    val backgroundColor = remember(iconStyle) { splashBackgroundColor(context, iconStyle) }
    val logoRes = remember(iconStyle) { splashLogoRes(iconStyle) }
    // 浅底（极简 #FAFAFA / 樱 / 素纸）必须换深色字，否则标题白字基本看不见
    val isDarkBackground = backgroundColor.luminance() < 0.5f
    val titleColor = if (isDarkBackground) Color.White else Color.Black.copy(alpha = 0.88f)

    // 系统级「动画已关闭」信号。这里不用播放器的「减少动画」开关：
    // 那个开关的语义是「减少播放器内的动画」，不该管应用启动。
    val animateSplash =
      remember {
        Settings.Global.getFloat(
          context.contentResolver,
          Settings.Global.ANIMATOR_DURATION_SCALE,
          1f,
        ) > 0f
      }

    var exiting by remember { mutableStateOf(false) }
    var titleIn by remember { mutableStateOf(false) }

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(if (animateSplash) 0.92f else 1f) }
    // 扫光位置：-1.2（logo 左侧之外）→ 1.35（右侧之外）。用归一化坐标，
    // 换屏宽不需要重算像素。
    val sweep = remember { Animatable(-1.2f) }

    val exit by animateFloatAsState(
      targetValue = if (exiting) 1f else 0f,
      animationSpec =
        tween(
          durationMillis = if (animateSplash) SPLASH_EXIT_MS.toInt() else 220,
          easing = FastOutSlowInEasing,
        ),
      finishedListener = { if (exiting) onFinished() },
      label = "splashExit",
    )

    LaunchedEffect(Unit) {
      launch {
        logoAlpha.animateTo(
          targetValue = 1f,
          animationSpec =
            tween(
              durationMillis = if (animateSplash) SPLASH_LOGO_IN_MS.toInt() else 160,
              easing = LinearOutSlowInEasing,
            ),
        )
      }
      if (animateSplash) {
        launch {
          logoScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
          )
        }
      }
      launch {
        delay(SPLASH_SWEEP_START_MS)
        if (animateSplash) {
          sweep.animateTo(
            targetValue = 1.35f,
            animationSpec = tween(durationMillis = SPLASH_SWEEP_MS.toInt(), easing = LinearEasing),
          )
        }
      }
      launch {
        delay(160)
        titleIn = true
      }
      // 降级路径（系统关闭动画）也要留出可读时间：纯淡入淡出如果只有 320ms，
      // 同样是「闪一下」，降级不等于敷衍。
      delay(if (animateSplash) SPLASH_MIN_STAY_MS else 460)
      exiting = true
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          // 整层随退场淡出：底色一淡，背后的主界面就露出来。
          // 不再做放大 —— 早前整体 scale 到 1.2 会被读成「页面被推远 / 关闭」。
          .graphicsLayer { alpha = 1f - exit }
          .background(backgroundColor)
          // 启动页展示期间吞掉点击，避免误触背后的界面
          .pointerInput(Unit) { detectTapGestures { } },
    ) {
      // 中心极淡的高光，给纯色底一点层次。浅色底上用更弱的值，
      // 否则在米白 / 樱粉上会变成一块明显的「脏印子」。
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.radialGradient(
                colors =
                  listOf(
                    Color.White.copy(alpha = if (isDarkBackground) 0.06f else 0.35f),
                    Color.Transparent,
                  ),
              ),
            ),
      )

      // ── logo：严格居中 ──
      // 与系统启动窗口那层图标对齐（同在屏幕正中），两段衔接时不会「往上跳」。
      // 退场时上移 14dp + 微缩，做出「被主界面接住」的收势。
      Box(
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(SPLASH_LOGO_SIZE)
            .graphicsLayer {
              // logo 比底色**先淡一点**（乘一次 1-exit，等于叠了两层淡出）。
              // 退场因此读作「logo 先退、底色随后化开」，而不是整块画面被一刀切掉。
              alpha = logoAlpha.value * (1f - exit)
              scaleX = logoScale.value
              scaleY = logoScale.value
              translationY = -exit * 14.dp.toPx()
            },
        contentAlignment = Alignment.Center,
      ) {
        // adaptive-icon 无法通过 painterResource 加载，仍走 ImageView；
        // 底色与启动页底色相同 ⇒ 图标自带的方形底直接融进背景，只留图形本身。
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            ImageView(ctx).apply {
              setImageResource(logoRes)
              scaleType = ImageView.ScaleType.CENTER_CROP
            }
          },
        )

        // 扫光：一次性柔光斜向掠过，只做圆形裁剪 —— 读起来是「光从图标上滑过」，
        // 而不是在方块上刷一层矩形高光。不循环：循环就退化成廉价的呼吸灯。
        Box(
          modifier =
            Modifier
              .matchParentSize()
              .clip(CircleShape)
              .drawBehind {
                val p = sweep.value
                if (p > -1.1f && p < 1.3f) {
                  val band = size.width * 0.42f
                  val cx = size.width * p
                  drawRect(
                    brush =
                      Brush.linearGradient(
                        colors =
                          listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.26f),
                            Color.Transparent,
                          ),
                        start = Offset(cx - band, 0f),
                        end = Offset(cx + band, size.height),
                      ),
                  )
                }
              },
        )
      }

      // ── 标题：挂在 logo 下方，**不参与 logo 的居中计算** ──
      // 早前是 Column 把图标和文字一起居中，结果图标被文字顶到中上，
      // 与系统启动窗口的正中图标错位。现在图标独占中心，文字按固定偏移让到下方。
      Column(
        modifier =
          Modifier
            .align(Alignment.Center)
            .offset(y = SPLASH_TITLE_OFFSET)
            .graphicsLayer {
              alpha = if (titleIn) 1f else 0f
              translationY = if (titleIn) 0f else 12.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(id = R.string.app_name),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 4.sp,
          color = titleColor,
        )
      }
    }
  }

  /**
   * 启动页底色：取当前桌面图标那套主题的背景色。
   *
   * ⚠️ 一定读 `R.color.*` 资源，**不要手抄十六进制**。这些颜色同时被
   * `app_icon_colors.xml` 与 adaptive icon 使用，任何一处改色，抄来的常量就会与图标
   * 底色错开 —— 表现是启动页正中浮出一块颜色略深的方块（即图标自带的底色没融进背景）。
   *
   * 用 `when` 而不是拿字符串去 `getIdentifier` 查：后者在开了资源压缩的 release 包里
   * 可能查不到（被当成无用资源剥掉），症状是「突然变回默认色」。
   */
  private fun splashBackgroundColor(context: android.content.Context, style: AppIconManager.Style): Color =
    Color(
      when (style) {
        AppIconManager.Style.Default -> context.getColor(R.color.ic_launcher_background)
        AppIconManager.Style.DeepSea -> context.getColor(R.color.app_icon_deepsea_background)
        AppIconManager.Style.Midnight -> context.getColor(R.color.app_icon_midnight_background)
        AppIconManager.Style.Minimal -> context.getColor(R.color.app_icon_minimal_background)
        AppIconManager.Style.Amber -> context.getColor(R.color.app_icon_amber_background)
        AppIconManager.Style.Sakura -> context.getColor(R.color.app_icon_sakura_background)
        AppIconManager.Style.Forest -> context.getColor(R.color.app_icon_forest_background)
        AppIconManager.Style.Neon -> context.getColor(R.color.app_icon_neon_background)
        AppIconManager.Style.Paper -> context.getColor(R.color.app_icon_paper_background)
      },
    )

  /** 启动页 logo：与当前图标主题同一套 mipmap（名字见 `mipmap-anydpi-v26/`）。 */
  private fun splashLogoRes(style: AppIconManager.Style): Int =
    when (style) {
      AppIconManager.Style.Default -> R.mipmap.ic_launcher
      AppIconManager.Style.DeepSea -> R.mipmap.ic_launcher_deepsea
      AppIconManager.Style.Midnight -> R.mipmap.ic_launcher_midnight
      AppIconManager.Style.Minimal -> R.mipmap.ic_launcher_minimal
      AppIconManager.Style.Amber -> R.mipmap.ic_launcher_amber
      AppIconManager.Style.Sakura -> R.mipmap.ic_launcher_sakura
      AppIconManager.Style.Forest -> R.mipmap.ic_launcher_forest
      AppIconManager.Style.Neon -> R.mipmap.ic_launcher_neon
      AppIconManager.Style.Paper -> R.mipmap.ic_launcher_paper
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

    // 界面风格（经典 / Cine）：由根页面 MainScreen 读取后决定渲染哪套外壳。
    // 在这里下发而不是在 MainScreen 里直接读偏好：保证「读一次、全树一致」——
    // 若各处自己订阅，切换风格的瞬间可能出现半秒的混搭。
    val uiStyle by appearancePreferences.uiStyle.collectAsState()

    // Provide both LocalBackStack and the LazyList/Grid states to all screens
    CompositionLocalProvider(
      LocalBackStack provides typedBackstack,
      LocalUiStyle provides uiStyle,
    ) {
      NavDisplay(
        backStack = typedBackstack,
        onBack = { typedBackstack.removeLastOrNull() },
        // 渲染分流：Cine 只接管登记过的路由（见 `CineRoute`），其余原样走老实现 ——
        // 这样「新界面」可以分期上线，任何一条没重做的路由都不会变成空白页。
        entryProvider = { route ->
          NavEntry(route) {
            if (uiStyle == UiStyle.Cine) {
              CineRoute(route) { route.Content() }
            } else {
              route.Content()
            }
          }
        },
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
