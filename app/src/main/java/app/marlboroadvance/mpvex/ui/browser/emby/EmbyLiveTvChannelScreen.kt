package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单个频道的节目单（a2 + c1 的「时间轴列表」形态）。
 *
 * 从「现在」起 24 小时，按开播时间排序，跨天按日期分组（今天 / 明天 / M月d日）。
 * 正在播出的那一行高亮 + 细进度条 —— 「现在演到哪儿了」一眼可见。
 *
 * 交互：点任意一行 = 观看该频道直播（节目单是信息视图，直播是唯一能做的播放动作；
 * 预约/录制是 a3，不在本页范围）。点还没开播的节目同样起播直播，
 * 不做静默无反应（红线 29）。
 */
@Serializable
data class EmbyLiveTvChannelScreen(val channel: EmbyItem) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val application =
      androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
    val server by vm.currentServer.collectAsState()

    var programs by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 「现在」每 30 秒走一步：否则「正在播出」的高亮会停在进页面那一刻，
    // 演到下一个节目也不动（节目单是给人看时间的东西，时间必须自己走）。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
      while (true) {
        now = System.currentTimeMillis()
        delay(30_000)
      }
    }

    // 只在「服务器」变化时拉一次：24 小时窗口在一次浏览里不会变
    LaunchedEffect(server?.id, channel.Id) {
      val current = server ?: return@LaunchedEffect
      loading = true
      programs = vm
        .loadLiveTvPrograms(current, channelId = channel.Id, hoursAhead = 24)
        // 服务端对这个端点的排序不做保证，客户端按开播时间自己排
        // （ISO 8601 字典序 = 时间序；null 排最前，反正没有开始时间的节目也没法看）
        .sortedBy { it.StartDate ?: "" }
      loading = false
    }

    val nowInstant = remember(now) { Instant.ofEpochMilli(now) }
    val today = remember { LocalDate.now() }
    val tomorrow = remember { today.plusDays(1) }
    val grouped = remember(programs) {
      programs.groupBy { programInstant(it.StartDate)?.atZone(ZoneId.systemDefault())?.toLocalDate() }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(channel.Name ?: "直播电视") },
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
          loading -> AppLoadingIndicator(
            modifier = Modifier
              .size(48.dp)
              .align(Alignment.Center),
          )

          programs.isEmpty() -> Text(
            text = stringResource(R.string.livetv_no_programs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
          )

          else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            // 频道头：台标 + 台名 + 频道号 + 「观看直播」
            item(key = "header") {
              ChannelHeader(
                channel = channel,
                imageUrl = server?.let { vm.imageUrl(it, channel, "Primary", 480) },
                onWatchLive = {
                  val target = server ?: return@ChannelHeader
                  scope.launch { vm.play(target, channel) }
                },
              )
              Spacer(Modifier.height(12.dp))
            }

            grouped.forEach { (date, dayPrograms) ->
              item(key = "date-$date") {
                val label = when (date) {
                  today -> stringResource(R.string.livetv_today)
                  tomorrow -> stringResource(R.string.livetv_tomorrow)
                  else -> date?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: ""
                }
                Text(
                  text = label,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(vertical = 8.dp),
                )
              }
              items(dayPrograms, key = { it.Id ?: it.Name ?: "" }) { program ->
                ProgramRow(
                  program = program,
                  now = nowInstant,
                  onClick = {
                    val target = server ?: return@ProgramRow
                    scope.launch { vm.play(target, channel) }
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/** 频道头：台标 + 台名 + 频道号 + 主行动按钮（观看直播）。 */
@Composable
private fun ChannelHeader(
  channel: EmbyItem,
  imageUrl: String?,
  onWatchLive: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      EmbyImage(
        url = imageUrl,
        fallbackUrl = null,
        contentDescription = channel.Name,
        modifier = Modifier
          .size(72.dp)
          .clip(RoundedCornerShape(8.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        placeholder = Icons.Outlined.LiveTv,
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = channel.Name ?: "",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val number = channel.ChannelNumber
        if (!number.isNullOrBlank()) {
          Text(
            text = stringResource(R.string.livetv_channel_number, number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onWatchLive, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.livetv_watch_live))
    }
  }
}

/**
 * 节目单的一行：左侧时间栏（开始/结束），右侧标题 + 简介。
 *
 * 正在播出的行：主色容器底 + 「正在播出」标 + 细进度条。
 */
@Composable
private fun ProgramRow(
  program: EmbyItem,
  now: Instant,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val start = programInstant(program.StartDate)
  val end = programInstant(program.EndDate)
  val airing = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
  // airing 的定义就蕴含 start/end 非空，编译器能证明（不再重复判空）
  val progress = if (airing) {
    val total = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
    ((now.toEpochMilli() - start.toEpochMilli()).toFloat() / total).coerceIn(0f, 1f)
  } else {
    0f
  }

  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = if (airing) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      Color.Transparent
    },
  ) {
    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      // 时间栏：开始时间在上（加粗），结束时间在下
      Column(
        modifier = Modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = start?.atZone(ZoneId.systemDefault())?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (airing) FontWeight.Bold else FontWeight.Medium,
          color = if (airing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = end?.atZone(ZoneId.systemDefault())?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--",
          style = MaterialTheme.typography.bodySmall,
          color = if (airing) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = program.Name ?: "",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (airing) {
            Spacer(Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primary,
            ) {
              Text(
                text = stringResource(R.string.livetv_live_now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        val overview = program.Overview
        if (!overview.isNullOrBlank()) {
          Text(
            text = overview,
            style = MaterialTheme.typography.bodySmall,
            color = if (airing) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (airing) {
          Spacer(Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
              .fillMaxWidth()
              .height(3.dp),
          )
        }
      }
    }
  }
}

// ────────────────────────────────────────────────────────────────────────
// 频道网格与节目单页共用的纯函数
// ────────────────────────────────────────────────────────────────────────

/** ISO 8601 → [Instant]；解析不了（null / 怪格式）返回 null，不让一页节目单崩掉。 */
internal fun programInstant(iso: String?): Instant? =
  iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * 一批节目里「每个频道正在播出」的那一条，key = 频道 Id（节目的 ParentId）。
 *
 * 频道网格的副标题用它：一次 `/LiveTv/Programs` 请求（不带 ChannelId）即可覆盖
 * 全部频道，不要按频道逐个拉。同一频道窗口内重叠的多条（理论上有）取第一条。
 */
internal fun currentProgramsByChannel(
  programs: List<EmbyItem>,
  now: Instant = Instant.now(),
): Map<String, EmbyItem> {
  val out = LinkedHashMap<String, EmbyItem>()
  programs.forEach { program ->
    val channelId = program.ParentId ?: return@forEach
    val start = programInstant(program.StartDate) ?: return@forEach
    val end = programInstant(program.EndDate) ?: return@forEach
    if (!now.isBefore(start) && now.isBefore(end)) {
      out.putIfAbsent(channelId, program)
    }
  }
  return out
}
