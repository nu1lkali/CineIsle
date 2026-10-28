package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

/**
 * 弹幕设置（独立页）。
 *
 * ## 为什么单独一页
 *
 * 弹幕的全部参数 —— 总开关、源地址、AppId / AppSecret、自动匹配 —— 原本**只在 mpv
 * 播放页的「更多 ⋮」里**那个面板中才能改。问题有两个：
 * 1. 那个面板要**先进入播放器**才够得着，而弹幕默认是关的 ⇒ 用户在设置里
 *    找不到、也不知道有这东西（用户反馈「找不到弹幕」）；
 * 2. 源与凭证是**配置性质**的东西，放在播放中途的面板里既难找又难改。
 *
 * ⇒ 把它们都搬到这里。播放页那个面板保留（播着的时候顺手调字号/透明度很自然），
 * 但**两边写的是同一份偏好**，所以在哪改都同步。
 */
@Serializable
object DanmakuSettingsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val prefs = koinInject<DanmakuPreferences>()

    // ⚠️ 全部走 `Preference.collectAsState()`（无参那个重载）：
    //   读 `.get()` 只会读一次，用户改完这一页再回来看到的是旧值。
    val enabled by prefs.enabled.collectAsState()
    val host by prefs.host.collectAsState()
    val appId by prefs.appId.collectAsState()
    val appSecret by prefs.appSecret.collectAsState()
    val autoMatch by prefs.autoMatch.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.danmaku_settings_title),
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
        SettingsSpotlightReveal(listState, DanmakuSettingsScreen)
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.danmaku_title))
          }

          item {
            ToggleRow(
              modifier = Modifier.settingsAnchor("danmaku_enable_title"),
              title = stringResource(R.string.danmaku_enable_title),
              summary =
                stringResource(R.string.danmaku_enable_summary) +
                  if (enabled) "" else "（当前未开启）",
              checked = enabled,
              onCheckedChange = { prefs.enabled.set(it) },
            )
          }

          // ── 源与凭证 ──────────────────────────────────────────
          // 放在开关下面而不是「打开后才可见」：这是**配置**，
          // 应该在还没开弹幕时就能填好（自建服务尤其如此）。
          item {
            Text(
              text = stringResource(R.string.danmaku_source_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          item {
            OutlinedTextField(
              value = host,
              onValueChange = { prefs.host.set(it) },
              label = { Text(stringResource(R.string.danmaku_host)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          item {
            Text(
              text = stringResource(R.string.danmaku_host_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          item {
            OutlinedTextField(
              value = appId,
              onValueChange = { prefs.appId.set(it) },
              label = { Text(stringResource(R.string.danmaku_app_id)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          item {
            OutlinedTextField(
              value = appSecret,
              onValueChange = { prefs.appSecret.set(it) },
              label = { Text(stringResource(R.string.danmaku_app_secret)) },
              singleLine = true,
              // ⚠️ 密钥不能明文 —— 别人瞄一眼屏幕就能抄走。
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
            )
          }
          item {
            Text(
              text = stringResource(R.string.danmaku_credential_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // ── 匹配方式 ──────────────────────────────────────────
          item {
            Text(
              text = stringResource(R.string.danmaku_match_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          item {
            ToggleRow(
              modifier = Modifier.settingsAnchor("danmaku_auto_match"),
              title = stringResource(R.string.danmaku_auto_match),
              summary = stringResource(R.string.danmaku_auto_match_summary),
              checked = autoMatch,
              onCheckedChange = { prefs.autoMatch.set(it) },
            )
          }

          item {
            Text(
              text = stringResource(R.string.danmaku_render_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * 一行开关。
 *
 * 形状照搬播放页弹幕面板里那个（`DanmakuSheet` 内的私有版），
 * 但**不带图标** —— 设置页里每行都挂一个图标只会变噪声。
 */
@Composable
private fun ToggleRow(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  // 搜索定位用（见 SettingsSpotlight）：不传就是普通一行，视觉上无差别
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable { onCheckedChange(!checked) }
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}