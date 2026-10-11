package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 直播电视（Live TV）—— 频道网格，点一个频道直接用现有播放器起播。
 *
 * 数据来自 Emby 的 `/LiveTv/Channels`：返回体的 `Items[]` 与 `/Items` 同构
 * （同一个 `EmbyItemsResult`），频道条目的 `Type` 是 `TvChannel`，
 * 可以直接喂给 [EmbyViewModel.play] —— 它不是 `Episode`，所以走单片播放那条路。
 *
 * 与 Yamby 对齐的是「薄」这个取舍：只做频道列表 + 播放，不做 EPG 节目单 / 录制管理。
 * 服务端没启用直播电视时列表为空，这里按「没有这个功能」显示，不报错。
 */
@Serializable
object EmbyLiveTvScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val application =
      androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by vm.currentServer.collectAsState()

    var channels by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 只在「服务器」变化时拉一次：频道列表很稳，没必要每次重组都请求
    LaunchedEffect(server?.id) {
      val current = server ?: return@LaunchedEffect
      loading = true
      message = null
      val list = withContext(Dispatchers.IO) { vm.loadLiveTvChannels(current) }
      channels = list
      if (list.isEmpty()) message = "这台服务器没有可用的直播电视频道"
      loading = false
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("直播电视") },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        when {
          loading -> app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator(
            modifier = Modifier.align(Alignment.Center),
          )
          channels.isNotEmpty() -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(channels, key = { it.Id ?: it.Name ?: "" }) { channel ->
              val current = server
              // ⚠️ 别去读 `channel.Number`（频道号）：`EmbyItem` 上没有这个字段，
              // 加了永远是 null，还会编译不过。频道页只显示台名 + 台标。
              EmbyMediaCard(
                title = channel.Name ?: "",
                subtitle = null,
                imageUrl = current?.let { vm.imageUrl(it, channel, "Primary", 480) },
                progress = null,
                // ⚠️ isFavorite 是**必填**（没有默认值），漏了编译不过
                isFavorite = false,
                onClick = {
                  val target = current ?: return@EmbyMediaCard
                  scope.launch { vm.play(target, channel) }
                },
                style = EmbyCardStyle.POSTER,
              )
            }
          }
          else -> Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = message ?: "直播电视",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
