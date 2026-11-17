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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.VlcPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * VLC 独立内核的设置页。
 *
 * 只写 [VlcPreferences]（`vlc_` 前缀），不会影响 mpv / GSY 的任何行为。
 */
@Serializable
object VlcPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val prefs = koinInject<VlcPreferences>()

    val hardware by prefs.hardwareDecode.collectAsState()
    val networkCaching by prefs.networkCachingMs.collectAsState()
    val fileCaching by prefs.fileCachingMs.collectAsState()
    val deinterlace by prefs.deinterlace.collectAsState()
    val dropLateFrames by prefs.dropLateFrames.collectAsState()
    val subtitleScale by prefs.subtitleScalePercent.collectAsState()
    val audioLanguage by prefs.audioLanguage.collectAsState()
    val subtitleLanguage by prefs.subtitleLanguage.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              "VLC 播放器",
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Text(
            "这些设置只作用于 VLC 播放页。mpv / GSY 的解码与网络参数在各自页面里配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
          )
        }

        item { VlcSectionTitle("解码") }
        item {
          VlcSettingsCard {
            VlcSwitchRow(
              title = "硬件解码",
              summary = "使用 MediaCodec。10-bit HEVC 卡顿时可关掉走 VLC 软件解码。",
              checked = hardware,
              onCheckedChange = prefs.hardwareDecode::set,
            )
            VlcSwitchRow(
              title = "丢晚帧 / 跳帧",
              summary = "解码跟不上时优先保持音画同步；关闭后宁可掉帧也不丢画面。",
              checked = dropLateFrames,
              onCheckedChange = prefs.dropLateFrames::set,
            )
          }
        }

        item { VlcSectionTitle("网络与文件缓存") }
        item {
          VlcSettingsCard {
            VlcSliderRow(
              title = "网络缓存",
              valueText = "${networkCaching} ms",
              value = networkCaching.toFloat(),
              range = 0f..10_000f,
              steps = 19,
              onValueChange = { prefs.networkCachingMs.set(it.toInt()) },
            )
            VlcSliderRow(
              title = "文件缓存",
              valueText = "${fileCaching} ms",
              value = fileCaching.toFloat(),
              range = 0f..5_000f,
              steps = 9,
              onValueChange = { prefs.fileCachingMs.set(it.toInt()) },
            )
          }
        }

        item { VlcSectionTitle("画面") }
        item {
          VlcSettingsCard {
            VlcSwitchRow(
              title = "去隔行",
              summary = "老 DVD / 电视录制源再打开；普通片源保持关闭。",
              checked = deinterlace,
              onCheckedChange = prefs.deinterlace::set,
            )
            VlcSliderRow(
              title = "字幕字号",
              valueText = "$subtitleScale%",
              value = subtitleScale.toFloat(),
              range = 50f..200f,
              steps = 14,
              onValueChange = { prefs.subtitleScalePercent.set(it.toInt()) },
            )
          }
        }

        item { VlcSectionTitle("语言偏好") }
        item {
          VlcSettingsCard {
            var audioText by remember(audioLanguage) { mutableStateOf(audioLanguage) }
            var subtitleText by remember(subtitleLanguage) { mutableStateOf(subtitleLanguage) }
            OutlinedTextField(
              value = audioText,
              onValueChange = {
                audioText = it
                prefs.audioLanguage.set(it.trim())
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              label = { Text("首选音轨语言") },
              placeholder = { Text("如 chi / eng；留空自动") },
            )
            OutlinedTextField(
              value = subtitleText,
              onValueChange = {
                subtitleText = it
                prefs.subtitleLanguage.set(it.trim())
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              label = { Text("首选字幕语言") },
              placeholder = { Text("如 chi / eng；留空自动") },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun VlcSectionTitle(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 4.dp),
  )
}

@Composable
private fun VlcSettingsCard(content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      content()
    }
  }
}

@Composable
private fun VlcSwitchRow(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun VlcSliderRow(
  title: String,
  valueText: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  onValueChange: (Float) -> Unit,
) {
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(valueText, style = MaterialTheme.typography.bodySmall)
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
    )
  }
}
