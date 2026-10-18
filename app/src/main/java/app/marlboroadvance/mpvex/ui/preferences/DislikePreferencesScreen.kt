package app.marlboroadvance.mpvex.ui.preferences

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.dislike.DislikeManageRoute
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
// ⚠️ PreferenceCard / PreferenceDivider / PreferenceSectionHeader 用的是**本项目自己的**
//   同包实现（见 CardPreferences.kt），不是 me.zhanghai.compose.preference 里的那份。
//   两者同名 —— 从库里 import 会把本包的遮蔽掉，编译报「Unresolved reference」。
//   同包定义直接可见，**不要 import**。
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

/**
 * 「标记为不喜欢」设置页。
 *
 * 三项开关分别控制三个入口（详情页菜单 / 播放器控制栏 / 视界流工具栏），默认全开；
 * 外加「不喜欢视频管理」页入口。
 *
 * ## 播放器开关为什么做成「双向同步」
 *
 * 播放器上的按钮还能被**控件布局编辑器**手动添加，两个入口互不知情，
 * 就会出现「我在编辑器里放了但按钮不出现」（用户完全不知道该去哪儿修）。所以：
 * · **关开关** ⇒ 连带把布局里的按钮清掉（破坏性，有二次确认）；
 * · **开开关** ⇒ 自动加回**它上次所在的簇**（而不是猜一个位置）；
 * · 在编辑器里放了按钮 ⇒ 开关自动打开（见 `ControlLayoutEditorScreen` 的 `onDispose`）。
 *
 * 这样开关永远是布局的镜像，用户不需要知道存在两套机制。详见 [DislikeLayoutSync]。
 */
@Serializable
object DislikePreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()

    val showInDetailMenu by browserPreferences.dislikeShowInDetailMenu.collectAsState()
    val showInPlayer by browserPreferences.dislikeShowInPlayerControls.collectAsState()
    val showInFeed by browserPreferences.dislikeShowInFeed.collectAsState()

    var pendingTurnOff by remember { mutableStateOf(false) }

    // 五个控件簇当前的 CSV（双向同步要按 key 定位改）
    // ⚠️ 映射统一取 [DislikeLayoutSync.clusterPrefs]：逻辑 key 与存储 key 不同名
    //   （bottom_left_controls 存的是 …_v2），两处各写一份迟早对不上。
    val clusterPrefs: Map<String, Preference<String>> =
      remember { DislikeLayoutSync.clusterPrefs(appearancePreferences) }
    fun currentClusters(): Map<String, String> =
      DislikeLayoutSync.readClusters(clusterPrefs)

    // ⚠️ `Scaffold` + `TopAppBar` **不能省**。本项目 `enableEdgeToEdge`，
    //   内容会一路铺到屏幕最顶端；不加这一层，列表就**压在系统状态栏底下**
    //   （第一行「标记为不喜欢」标题被时钟/信号糊住）。
    //   Scaffold 会自动为状态栏留出安全区域，写法对齐 `AudioPreferencesScreen`。
    // ⚠️ `ProvidePreferenceLocals` **也不能漏**：库的 Preference / SwitchPreference /
    //   PreferenceCard 在组合时会读 `LocalPreferenceTheme` 等 CompositionLocal，
    //   缺了会抛 `IllegalStateException: CompositionLocal LocalPreferenceTheme not present`
    //   —— 而且是在**进入页面那一刻**崩（栈顶是 Icons.noLocalProvidedFor，很像库内部问题，
    //   实际是调用方少提供了一层）。项目里所有设置页都有这层。
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "不喜欢",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
      item { PreferenceSectionHeader(title = "标记为不喜欢") }
      item {
        PreferenceCard {
          SwitchPreference(
            icon = {
              Icon(
                Icons.Outlined.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            },
            value = showInDetailMenu,
            onValueChange = browserPreferences.dislikeShowInDetailMenu::set,
            title = { Text("详情页菜单项") },
            summary = {
              Text(
                "在媒体详情页的「更多」菜单里显示「标记为不喜欢」。",
                color = MaterialTheme.colorScheme.outline,
              )
            },
          )
          PreferenceDivider()
          SwitchPreference(
            icon = {
              Icon(
                Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            },
            value = showInPlayer,
            // 关走二次确认（破坏性：要连带清掉布局里的按钮）
            onValueChange = { wantOn ->
              if (wantOn) {
                val plan =
                  DislikeLayoutSync.planTurnOn(
                    current = currentClusters(),
                    clusterKey =
                      browserPreferences.dislikeLayoutCluster.get().takeIf { it.isNotBlank() },
                  )
                DislikeLayoutSync.writeClusters(clusterPrefs, plan.writes)
                browserPreferences.dislikeShowInPlayerControls.set(true)
                if (plan.writes.isNotEmpty()) {
                  Toast.makeText(
                    context,
                    "已把按钮加回控件栏，可在「布局」里换位置",
                    Toast.LENGTH_SHORT,
                  ).show()
                }
              } else {
                pendingTurnOff = true
              }
            },
            title = { Text("播放器按钮") },
            summary = {
              Text(
                "在播放器控制栏显示「不喜欢」按钮。关闭会同时从布局中移除它。",
                color = MaterialTheme.colorScheme.outline,
              )
            },
          )
          PreferenceDivider()
          SwitchPreference(
            icon = {
              Icon(
                Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            },
            value = showInFeed,
            onValueChange = browserPreferences.dislikeShowInFeed::set,
            title = { Text("视界流按钮") },
            summary = {
              Text(
                "在视界流（竖屏连播）右侧工具栏显示「不喜欢」按钮。",
                color = MaterialTheme.colorScheme.outline,
              )
            },
          )
        }
      }
      item {
        PreferenceCard {
          Preference(
            icon = {
              Icon(
                Icons.Outlined.ThumbDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            },
            title = { Text("不喜欢视频管理") },
            summary = {
              Text(
                "查看已标记的条目、取消标记或删除。",
                color = MaterialTheme.colorScheme.outline,
              )
            },
            onClick = { backstack.add(DislikeManageRoute) },
          )
        }
      }
      }
    }
    }

    // 关开关的二次确认：这一步会改写用户的控件布局，不能静默发生
    if (pendingTurnOff) {
      ConfirmDialog(
        title = "关闭播放器按钮？",
        subtitle =
          "同时会从控制栏布局中移除「不喜欢」按钮。\n" +
            "之后重新打开本开关会自动加回它上次所在的位置。",
        onConfirm = {
          val plan = DislikeLayoutSync.planTurnOff(current = currentClusters())
          DislikeLayoutSync.writeClusters(clusterPrefs, plan.writes)
          browserPreferences.dislikeShowInPlayerControls.set(false)
          if (plan.changedClusters > 0) {
            Toast.makeText(
              context,
              "已从 ${plan.changedClusters} 个控件位置移除",
              Toast.LENGTH_SHORT,
            ).show()
          }
          pendingTurnOff = false
        },
        onCancel = { pendingTurnOff = false },
      )
    }
  }
}