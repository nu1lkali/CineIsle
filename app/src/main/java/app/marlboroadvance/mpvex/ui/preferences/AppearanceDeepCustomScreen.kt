package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.theme.LoadingAnimation
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import org.koin.compose.koinInject

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

    // 弹幕总开关也放在这一页（见下方说明）。
    val danmakuPrefs = koinInject<app.marlboroadvance.mpvex.preferences.DanmakuPreferences>()
    val danmakuEnabled by danmakuPrefs.enabled.collectAsState()

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
        LazyColumn(
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

          // ── 弹幕 ──────────────────────────────────────────────
          // 为什么在这里给一个入口：弹幕的全部设置（开关 / 源地址 / AppId / Secret /
          // 自动匹配）原本**只在 mpv 播放页的「更多 ⋮」里**才够得着，而它默认是关的
          // ⇒ 用户在设置里找不到、也不知道去哪开，表现就是「找不到弹幕功能」。
          // 这里只放**一个入口**进独立页（见 [DanmakuSettingsScreen]），
          // 全部参数都在那一页改，不必先进播放器。
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.danmaku_title),
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          item {
            Preference(
              title = { Text(text = stringResource(R.string.danmaku_settings_title)) },
              summary = {
                Text(
                  // 当前开关状态直接写进摘要 ⇒ 一眼能看出开没开，不必点进去。
                  text = stringResource(R.string.danmaku_settings_summary) +
                    if (danmakuEnabled) "（已开启）" else "（未开启）",
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              icon = {
                Icon(
                  Icons.Outlined.ChatBubbleOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
              onClick = { backstack.add(DanmakuSettingsScreen) },
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
@Composable
private fun IconRow(
  current: AppIconManager.Style,
  onPick: (AppIconManager.Style) -> Unit,
) {
  val context = LocalContext.current
  LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    items(AppIconManager.Style.entries.size) { index ->
      val style = AppIconManager.Style.entries[index]
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
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

/** 加载动画选择行：每项**当场播放**自己的动画，而不是只给名字。 */
@Composable
private fun LoadingAnimationRow(
  current: LoadingAnimation,
  onPick: (LoadingAnimation) -> Unit,
) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    items(LoadingAnimation.entries.size) { index ->
      val anim = LoadingAnimation.entries[index]
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
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
          color = if (anim == current) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
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