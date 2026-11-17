package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.player.engine.EngineKind
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 播放内核设置入口：顶部三列直接切到 MPV / GSY / VLC 各自的设置页。
 *
 * 三个播放页内核的配置完全独立：
 *  - MPV → DecoderPreferencesScreen（硬解 / 渲染 / 滤镜）
 *  - GSY → GsyPreferencesScreen（IJK / ExoPlayer / 长按切换）
 *  - VLC → VlcPreferencesScreen（LibVLC 硬解 / 缓存 / 字幕）
 */
@Serializable
object PlayerEngineSettingsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val playerPrefs = koinInject<PlayerPreferences>()
    val defaultEngine by playerPrefs.playbackEngine.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              "播放内核设置",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backStack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            EngineColumn(
              title = "MPV",
              subtitle = "功能最全",
              icon = Icons.Outlined.Memory,
              selected = EngineKind.from(defaultEngine) == EngineKind.MPV,
              modifier = Modifier.weight(1f),
              onClick = { backStack.add(DecoderPreferencesScreen) },
            )
            EngineColumn(
              title = "GSY",
              subtitle = "兼容备用",
              icon = Icons.Outlined.PlayCircle,
              selected = EngineKind.from(defaultEngine) == EngineKind.GSY,
              modifier = Modifier.weight(1f),
              onClick = { backStack.add(GsyPreferencesScreen) },
            )
            EngineColumn(
              title = "VLC",
              subtitle = "独立内核",
              icon = Icons.Outlined.Movie,
              selected = EngineKind.from(defaultEngine) == EngineKind.VLC,
              modifier = Modifier.weight(1f),
              onClick = { backStack.add(VlcPreferencesScreen) },
            )
          }
        }
        item {
          Text(
            "点上面任一列进入对应内核的设置页。三套配置互不影响；" +
              "默认播放内核仍由「GSY 播放器 → 默认播放内核」决定，" +
              "详情页长按播放可以临时选 GSY / VLC。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
          )
        }
        item {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text("能力提示", fontWeight = FontWeight.SemiBold)
              Text(
                "MPV：字幕排版、滤镜、章节、第二字幕最完整。\n" +
                  "GSY：硬解 / 兼容性好，走独立 GSY 播放页。\n" +
                  "VLC：第三套独立播放页，当前覆盖播放/暂停/拖动/倍速/音轨/字幕轨/" +
                  "画面比例/画中画；外挂字幕文件选择、投屏、后台播放后续补。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EngineColumn(
  title: String,
  subtitle: String,
  icon: ImageVector,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = modifier,
    colors = CardDefaults.cardColors(
      containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      },
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
      Text(
        subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (selected) {
        Text(
          "默认",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}
