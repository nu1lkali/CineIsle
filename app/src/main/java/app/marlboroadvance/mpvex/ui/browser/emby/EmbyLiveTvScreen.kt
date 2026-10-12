package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyCardStyle
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 直播电视（Live TV）—— 频道网格 + 正在播出 + 节目单入口（a2 + c1）。
 *
 * 数据来自 Emby 的 `/LiveTv/Channels`：返回体的 `Items[]` 与 `/Items` 同构
 * （同一个 `EmbyItemsResult`），频道条目的 `Type` 是 `TvChannel`，
 * 可以直接喂给 [EmbyViewModel.play] —— 它不是 `Episode`，所以走单片播放那条路。
 *
 * 「正在播出」（c1）：进页面时顺带拉一次 `/LiveTv/Programs`（不带 ChannelId，
 * 未来 3 小时窗口）—— **一次请求覆盖所有频道**，按节目的 `ParentId` 归到各台，
 * 显示成卡片副标题。服务端没接 EPG 数据源时返回空，那就是没有副标题，不报错。
 *
 * 每张卡右上角有个节目单按钮，进 [EmbyLiveTvChannelScreen] 看该频道 24 小时
 * 时间轴；点卡片本体仍是起播（老习惯不变）。录制/定时录像是 a3，没做。
 *
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
    /** 频道 Id → 正在播出的节目（来自一次全频道节目单请求） */
    var nowPlaying by remember { mutableStateOf<Map<String, EmbyItem>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 只在「服务器」变化时拉一次：频道列表很稳，没必要每次重组都请求
    LaunchedEffect(server?.id) {
      val current = server ?: return@LaunchedEffect
      loading = true
      message = null
      val list = withContext(Dispatchers.IO) { vm.loadLiveTvChannels(current) }
      channels = list
      // 正在播出：一次请求拿所有频道未来 3 小时的节目。失败/无数据 ⇒ 空 map，
      // 卡片只是没有副标题，不值得为它报错。
      val programs = withContext(Dispatchers.IO) {
        vm.loadLiveTvPrograms(current, hoursAhead = 3)
      }
      nowPlaying = currentProgramsByChannel(programs)
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
            // ⚠️ 尺寸必须写进 modifier：AppLoadingIndicator 的 `size` 参数只在
            //   **完全没传 modifier** 时生效。原来这里只传了 align ——
            //   Lottie 分支会按 composition 固有像素渲染（yamby 那批 500×500px
            //   ≈ 170dp，直接变成巨物；自研的 200×200px ≈ 67dp，也不统一）。
            //   48dp 与其他整页加载态（视频列表 / 最近播放 / 文件浏览…）一致。
            modifier = Modifier.size(48.dp).align(Alignment.Center),
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
              Box {
                EmbyMediaCard(
                  title = channel.Name ?: "",
                  // 正在播出的节目名；没有 EPG 数据时为 null（不显示副标题）
                  subtitle = nowPlaying[channel.Id]?.Name?.let {
                    stringResource(R.string.livetv_now_playing, it)
                  },
                  imageUrl = current?.let { vm.imageUrl(it, channel, "Primary", 480) },
                  progress = null,
                  // ⚠️ isFavorite 是**必填**（没有默认值），漏了编译不过
                  isFavorite = false,
                  onClick = {
                    val target = current ?: return@EmbyMediaCard
                    scope.launch { vm.play(target, channel) }
                  },
                  style = EmbyCardStyle.POSTER,
                  // c1：左下角角标显示频道号（ChannelNumber，如 "12" / "12.1"）
                  badgeText = channel.ChannelNumber?.let {
                    stringResource(R.string.livetv_channel_number, it)
                  },
                )
                // 节目单入口：右上角小按钮，样式仿卡片收藏心形（半透明黑圆底 + 白图标）。
                // 点卡片本体仍是起播，这个按钮才进节目单 —— 两个入口分工明确。
                Surface(
                  onClick = { backStack.add(EmbyLiveTvChannelScreen(channel)) },
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50)),
                  shape = RoundedCornerShape(50),
                  color = Color.Black.copy(alpha = 0.32f),
                ) {
                  Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = stringResource(R.string.livetv_program_guide),
                    modifier = Modifier
                      .padding(5.dp)
                      .size(15.dp),
                    tint = Color.White,
                  )
                }
              }
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
