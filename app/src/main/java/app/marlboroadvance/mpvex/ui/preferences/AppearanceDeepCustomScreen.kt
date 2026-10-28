package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import app.marlboroadvance.mpvex.ui.theme.LoadingAnimation
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import org.koin.compose.koinInject

/**
 * 加载动画网格的列数。
 *
 * 4 列是这个宽度下「一屏看全」的上限：每格 64dp + 12dp 间距 ⇒
 * 4×64 + 3×12 = 292dp，常见机型（≥320dp 宽）都放得下；
 * 再多 5 列（5×64+4×12=368dp）就会在窄屏 / 大字号下被 FlowRow 自动换行，
 * 反而变成参差不齐的两排。
 */
private const val LOADING_ANIM_COLUMNS = 4

/**
 * 外观深度定制：桌面图标 + 加载动画。
 *
 * 分成两个独立页面而不是塞进 [AppearancePreferencesScreen]：这一页全都会
 * **立刻看到画面变化**（换图标要重启应用、换动画当场变），混在长列表里
 * 用户很难把「改了什么」和「看到什么」对应上。
 */
@Serializable
object AppearanceDeepCustomScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    // 图标当前选择**不是**偏好存储，而是 PackageManager 的组件启用状态 ——
    // 所以只能现读，不能 collectAsState。
    var iconStyle by remember { mutableStateOf(AppIconManager.currentStyle(context)) }

    // 切换失败要**说给用户听**（红线 29：交互里的静默失败是产品 bug）。
    // 用 snackbar 而不是 Toast：Toast 容易被上层 surface 盖住半截。
    val snackbarHostState = remember { SnackbarHostState() }

    val appearancePrefs = koinInject<app.marlboroadvance.mpvex.preferences.AppearancePreferences>()
    // ⚠️ `Preference<T>` 有**自己的**无参 `collectAsState()`
    // （`preferences/preference/Preference.kt` 里定义，返回 `State<T>`），
    // 不是 Compose 那个接收 `State<T>` 的重载 —— 混用会报
    // 「Unresolved reference 'collectAsState'」（参数不匹配时编译器找不到重载）。
    val loadingAnim by appearancePrefs.loadingAnimation.collectAsState()

    // 加载动画大小（缩放系数，1.0 = 各调用点写死的基准尺寸）。
    // 与上面同一个坑：必须用无参的 Preference.collectAsState()，拖滑块才实时生效。
    val loadingSizeScale by appearancePrefs.loadingAnimationScale.collectAsState()

    Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.appearance_deep_title),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val listState = rememberLazyListState()
        SettingsSpotlightReveal(listState, AppearanceDeepCustomScreen)
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.appearance_icon_title))
          }
          item {
            Text(
              text = stringResource(R.string.appearance_icon_summary),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 4.dp),
            )
          }
          item {
            // ⚠️ 提示文案在 Composable 里算好再传下去：`stringResource` 是
            //   @Composable、`showSnackbar` 是 suspend，两者都不能写在 onClick 回调里。
            //   （这个页面已被编译错误教过一次：直接在回调里调会报
            //   「Suspend function … can only be called from a coroutine」
            //   和「@Composable invocations can only happen from …」。）
            val iconAlreadyMsg = stringResource(R.string.appearance_icon_already)
            val iconFailedMsg =
              stringResource(R.string.appearance_icon_failed, "%s")
            IconRow(
              current = iconStyle,
              onPick = { style ->
                // ⚠️ 别在这里「先乐观更新 iconStyle 再切」：
                //   setComponentEnabledSetting 对**不存在**的组件既不抛异常也不生效
                //   （namespace ≠ applicationId 时手抄全限定名就会踩到），
                //   曾经表现为「点了完全没反应」。所以必须看返回值。
                val changed = AppIconManager.setStyle(context, style)
                if (changed) {
                  iconStyle = style
                } else {
                  // 没改成 ⇒ 要么已经是它了，要么清单里少了对应 alias。
                  // 两种都提示一下，否则用户只看到「点了没反应」。
                  val missing = AppIconManager.missingAliases(context)
                  val text =
                    if (missing.isEmpty()) {
                      iconAlreadyMsg
                    } else {
                      iconFailedMsg.replace("%s", missing.joinToString())
                    }
                  scope.launch { snackbarHostState.showSnackbar(text) }
                }
              },
            )
          }

          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.appearance_loading_anim_title),
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item {
            Text(
              text = stringResource(R.string.appearance_loading_anim_summary),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 4.dp),
            )
          }
          item {
            LoadingAnimationRow(
              current = LoadingAnimation.fromKey(loadingAnim),
              onPick = { anim -> appearancePrefs.loadingAnimation.set(anim.storageKey) },
            )
          }
          item {
            LoadingAnimationSizeRow(
              scale = loadingSizeScale,
              onScaleChange = { appearancePrefs.loadingAnimationScale.set(it) },
            )
          }
        }
      }
    }
  }
}

/**
 * 图标选择行。
 *
 * ⚠️ 预览必须**真图标**（`mipmap/ic_launcher_xxx`），不能用 painterResource 拼近似 ——
 * 拼出来的和桌面上的不一样，用户选了才发现难看。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconRow(
  current: AppIconManager.Style,
  onPick: (AppIconManager.Style) -> Unit,
) {
  val context = LocalContext.current
  // 同样是网格而不是一行：5 个图标一行拉完，一屏只露 4 个，
  // 用户横向滑才发现还有 ⇒ 会以为「只有 4 个图标可选」（和加载动画同一个坑）。
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    maxItemsInEachRow = LOADING_ANIM_COLUMNS,
  ) {
    // 普通 for 循环：`items(...)` 是 LazyListScope 的扩展，FlowRow 里没有。
    for (style in AppIconManager.Style.entries) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          // 固定宽度，否则「默认」和「深海」两种标签长度会让格子参差。
          .width(76.dp)
          .clip(RoundedCornerShape(16.dp))
          .clickable { onPick(style) }
          .padding(6.dp),
      ) {
        // adaptive icon 的预览：用前景 drawable叠在背景色上，等价于系统裁切。
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(resolveColor(context, style.backgroundColorRes))
            .border(
              width = if (style == current) 3.dp else 0.dp,
              color = if (style == current) MaterialTheme.colorScheme.primary else Color.Transparent,
              shape = RoundedCornerShape(16.dp),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Image(
            painter = painterResource(resolveDrawable(context, style.foregroundRes)),
            contentDescription = null,
            // ⚠️ 前景矢量是按图标配色画的（深底浅字 / 浅底深字），
            //   预览时不能染色，否则用户看到的是「单色剪影」而不是真图标。
            colorFilter = null,
            modifier = Modifier.size(52.dp),
          )
        }
        Text(
          text = stringResource(style.labelRes),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          maxLines = 2,
          color = if (style == current) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 加载动画选择区：**网格**，不是横向滑动的一行。
 *
 * ## 为什么不用 LazyRow
 *
 * 原来是一行 `LazyRow`，一屏只放得下 4 个 —— 用户看到「吃豆人 / 三环 / 小蜜蜂 / 小恐龙」
 * 之后，得**横向滑动才知道后面还有**，于是反馈「怎么只有 4 个」。
 * 选项一多，横向滑动就是最容易被忽略的交互（用户不知道右边还有东西）。
 *
 * ⇒ 改成网格：一屏铺开，**有多少一眼看全**，不用猜。
 *
 * ## 为什么用 FlowRow 而不是 LazyVerticalGrid
 *
 * 本函数被放在外层 `LazyColumn` 的 `item {}` 里 ⇒ 高度无界。
 * `LazyVerticalGrid` 在无界高度约束下会直接抛异常，必须外层再套一层固定高度容器才行，
 * 反而更绕。`FlowRow` 非惰性、没有高度约束问题，正是这种「网格化的短列表」该用的。
 *
 * ⚠️ 代价：所有预览动画会**同时**跑（FlowRow 不惰性）。
 *   这里能接受，因为预览只有 44dp、且 JSON 都是解析一次后缓存
 *   （`rememberLottieComposition` 按 key 缓存），十几个小动画不至于掉帧。
 *   真要上百项时才需要换成惰性网格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadingAnimationRow(
  current: LoadingAnimation,
  onPick: (LoadingAnimation) -> Unit,
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    maxItemsInEachRow = LOADING_ANIM_COLUMNS,
  ) {
    // ⚠️ 这里用**普通 for 循环**而不是 `items(count)`：
    //   `items(...)` 是 `LazyListScope` 的扩展，FlowRow 的作用域里没有 ⇒ 会报 Unresolved。
    //   FlowRow 本来就要求子项是直接 emit 的（它自己排版），普通循环才是正确用法。
    for (anim in LoadingAnimation.entries) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          // ⚠️ 必须给固定宽度：FlowRow 是按**内容**排布的，标签「呼吸（自研）」比「三环」宽得多，
          //   不锁宽的话每格宽度都不同 ⇒ 网格看起来是歪的（而且会挤掉一列）。
          .width(76.dp)
          .clip(RoundedCornerShape(16.dp))
          .clickable { onPick(anim) }
          .padding(6.dp),
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
              width = if (anim == current) 3.dp else 0.dp,
              color = if (anim == current) MaterialTheme.colorScheme.primary else Color.Transparent,
              shape = RoundedCornerShape(16.dp),
            ),
          contentAlignment = Alignment.Center,
        ) {
          if (anim.hasLottie) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(anim.resId))
            LottieAnimation(
              composition = composition,
              // ⚠️ Infinity + 无限重复：这就是「加载中」该有的样子。
              //   预览里若只播一次就停，用户看不出它是动图还是静态图。
              iterations = LottieConstants.IterateForever,
              modifier = Modifier.size(44.dp),
            )
          } else {
            Text(
              text = stringResource(R.string.loading_anim_system_short),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Text(
          text = stringResource(anim.labelRes),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          color = if (anim == current) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 加载动画大小：滑块 + **实时预览**。
 *
 * 预览框里渲染的就是统一入口 [AppLoadingIndicator] 本身 —— 它会读用户选的动画
 * 和这里的缩放系数 ⇒ 所见即所得；另画一个近似预览必然「预览与实物不符」。
 *
 * ⚠️ 预览基准 48dp、框 96dp：2.0× 时刚好 96dp 不出框；列表里那些整页加载态
 *   正是 48dp 基准，预览大小与真实观感一致（播放器画面层是 96dp 基准，
 *   同比例放大，不会超出屏幕）。
 */
@Composable
private fun LoadingAnimationSizeRow(
  scale: Float,
  onScaleChange: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.appearance_loading_anim_size_title),
        style = MaterialTheme.typography.bodyLarge,
      )
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = "${(scale * 100).roundToInt()}%",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(96.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        AppLoadingIndicator(size = 48.dp)
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Slider(
          value = scale,
          onValueChange = onScaleChange,
          // 松手时吸附到 10% 整数档：拖动过程是连续的，不吸附的话存下来会是
          // 87.3% 这种没法复述的值，想调回刚才那样时对不上。
          onValueChangeFinished = { onScaleChange((scale * 10f).roundToInt() / 10f) },
          valueRange = 0.5f..2.0f,
          steps = 14,
        )
        Text(
          text = stringResource(R.string.appearance_loading_anim_size_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun resolveColor(context: android.content.Context, name: String): Color =
  remember(name) {
    val id = context.resources.getIdentifier(name, "color", context.packageName)
    if (id != 0) androidx.compose.ui.graphics.Color(context.resources.getColor(id, null))
    else Color.Gray
  }

@Composable
private fun resolveDrawable(context: android.content.Context, name: String): Int =
  remember(name) {
    context.resources.getIdentifier(name, "drawable", context.packageName)
      .takeIf { it != 0 } ?: R.drawable.ic_launcher_foreground
  }