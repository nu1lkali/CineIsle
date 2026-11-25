package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.preferences.FEED_TOOLBAR_ALPHA_RANGE
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.coerceFeedToolbarAlpha
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.preferences.components.TickedSlider
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

/**
 * 视界流（竖屏连播）工具栏设置。
 *
 * 两项设置都在 [app.marlboroadvance.mpvex.ui.player.feed.FeedScreen] 里生效：
 * · [PlayerPreferences.feedToolbarBottomLeft] —— 工具栏锚点。关（默认）＝右侧中部竖排；
 *   开＝左下角横排。只换容器与对齐，按钮内容 / 顺序 / 点击语义不变。
 * · [PlayerPreferences.feedToolbarAlpha] —— 常驻透明度。最终 alpha =
 *   控制条显隐系数 × 本值 / 100，控制条淡出时整组照样消失。
 *
 * 设置页改动后下次进入视界流生效（VerticalFeedActivity 进入时读一次），
 * 不在播放中动态跟随，避免刷流途中按钮突然换位置。
 */
@Serializable
object FeedToolbarPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val preferences = koinInject<PlayerPreferences>()

    val bottomLeft by preferences.feedToolbarBottomLeft.collectAsState()
    val alpha by preferences.feedToolbarAlpha.collectAsState()
    val alphaValue = coerceFeedToolbarAlpha(alpha)

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "视界流工具栏",
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
        val listState = rememberLazyListState()
        SettingsSpotlightReveal(listState, FeedToolbarPreferencesScreen)
        LazyColumn(
          state = listState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item { PreferenceSectionHeader(title = "按钮位置") }
          item {
            PreferenceCard {
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.AutoMirrored.Outlined.ViewQuilt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = bottomLeft,
                onValueChange = preferences.feedToolbarBottomLeft::set,
                modifier = Modifier.settingsAnchor("左下角横排"),
                title = { Text("左下角横排", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    "关闭时位于右侧竖排",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          item { PreferenceSectionHeader(title = "透明度") }
          item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
              TickedSlider(
                value = alphaValue,
                onValueChange = { preferences.feedToolbarAlpha.set(it) },
                valueRange = FEED_TOOLBAR_ALPHA_RANGE,
                modifier = Modifier.settingsAnchor("控件透明度"),
                title = "控件透明度",
                valueLabel = "$alphaValue%",
                ticks = listOf(30, 40, 50, 60, 70, 80, 90, 100),
                tickLabels = listOf(30, 50, 100),
              )
            }
          }
        }
      }
    }
  }
}
