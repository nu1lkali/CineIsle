package app.marlboroadvance.mpvex.ui.cine.livetv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.currentProgramsByChannel
import app.marlboroadvance.mpvex.ui.browser.emby.programInstant
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.components.CineGlassTopBar
import app.marlboroadvance.mpvex.ui.cine.components.CineIconButton
import app.marlboroadvance.mpvex.ui.cine.components.CineStatePanel
import app.marlboroadvance.mpvex.ui.cine.components.LocalCineHaze
import app.marlboroadvance.mpvex.ui.cine.components.cineHazeSource
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.pressDownScale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Cine 版「直播电视」：频道网格 + 正在播出 + 节目单入口。
 *
 * 取数与起播逻辑与 Classic 完全一致（`/LiveTv/Channels` + 一次 `/LiveTv/Programs`
 * 覆盖全部频道的「正在播出」），只换了外观：玻璃顶栏、图下标题的频道瓦片、
 * 压在封面右上角的「节目单」圆钮。
 *
 * - 点瓦片 = 起播该频道（老习惯不变）
 * - 点右上角日历钮 = 进该频道 24 小时节目单
 */
@Composable
fun CineLiveTvScreen(modifier: Modifier = Modifier) {
  val application = LocalContext.current.applicationContext as Application
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
  val server by vm.currentServer.collectAsState()

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var channels by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var nowPlaying by remember { mutableStateOf<Map<String, EmbyItem>>(emptyMap()) }
  var loading by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(server?.id) {
    val current = server ?: return@LaunchedEffect
    loading = true
    message = null
    val list = withContext(Dispatchers.IO) { vm.loadLiveTvChannels(current) }
    channels = list
    val programs = withContext(Dispatchers.IO) { vm.loadLiveTvPrograms(current, hoursAhead = 3) }
    nowPlaying = currentProgramsByChannel(programs)
    if (list.isEmpty()) message = "这台服务器没有可用的直播电视频道"
    loading = false
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = "直播电视",
      onBack = { backStack.removeLastOrNull() },
    )

    when {
      loading -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          color = colors.progressFill,
          modifier = Modifier.size(30.dp),
          strokeWidth = 2.5.dp,
        )
      }

      channels.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.LiveTv,
        title = "没有可用的频道",
        description = message ?: "服务端未启用直播电视，或还没有接入 EPG 数据源",
        primaryLabel = "返回",
        onPrimary = { backStack.removeLastOrNull() },
      )

      else -> {
        Text(
          text = "${channels.size} 个频道 · 点封面起播，点日历看节目单",
          style = type.cardMeta,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(start = space.screenH, top = 12.dp, bottom = 8.dp),
        )
        LazyVerticalGrid(
          columns = GridCells.Fixed(2),
          modifier = Modifier
            .fillMaxSize()
            .cineHazeSource(LocalCineHaze.current),
          contentPadding = PaddingValues(
            start = space.screenH,
            end = space.screenH,
            bottom = 32.dp,
          ),
          horizontalArrangement = Arrangement.spacedBy(space.itemGap),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          items(channels, key = { it.Id ?: it.Name ?: "" }) { channel ->
            CineChannelTile(
              channel = channel,
              imageUrl = server?.let { vm.imageUrl(it, channel, "Primary", 480) },
              nowPlaying = nowPlaying[channel.Id]?.Name,
              onPlay = {
                val current = server ?: return@CineChannelTile
                scope.launch { vm.play(current, channel) }
              },
              onGuide = { backStack.add(CineLiveTvGuideRoute(channel)) },
            )
          }
        }
      }
    }
  }
}

/** 频道瓦片：封面 + 播放态 + 右上角「节目单」圆钮。 */
@Composable
private fun CineChannelTile(
  channel: EmbyItem,
  imageUrl: String?,
  nowPlaying: String?,
  onPlay: () -> Unit,
  onGuide: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = LocalCardCorner.current
  val shape = remember(corner) { RoundedCornerShape(corner) }

  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(shape)
        .background(colors.skeleton)
        .pressDownScale()
        .clickable(onClick = onPlay),
      contentAlignment = Alignment.Center,
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = channel.Name,
        modifier = Modifier.fillMaxSize(),
        maxWidth = 480,
        contentScale = ContentScale.Crop,
        placeholder = Icons.Outlined.LiveTv,
      )

      // 频道号：左下角，压暗底保证在任何台标上都可读
      channel.ChannelNumber?.takeIf { it.isNotBlank() }?.let { number ->
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(7.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.badgeGhostSurface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
          Text(
            text = stringResource(R.string.livetv_channel_number, number),
            style = type.badge,
            color = colors.onImage,
            maxLines = 1,
          )
        }
      }

      // 节目单入口：右上角玻璃圆钮（与详情页的压图圆钮同一套语言）
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(6.dp)
          .size(32.dp)
          .clip(CircleShape)
          .background(colors.badgeGhostSurface)
          .clickable(onClick = onGuide),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Schedule,
          contentDescription = stringResource(R.string.livetv_program_guide),
          tint = colors.onImage,
          modifier = Modifier.size(16.dp),
        )
      }
    }

    Spacer(Modifier.height(7.dp))
    Text(
      text = channel.Name ?: "",
      style = type.posterTitle,
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = if (nowPlaying != null) {
        stringResource(R.string.livetv_now_playing, nowPlaying)
      } else {
        stringResource(R.string.livetv_live_now)
      },
      style = type.cardMeta,
      color = if (nowPlaying != null) colors.onSurfaceVariant else colors.sectionAction,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Cine 版节目单页（单个频道 24 小时）。
 *
 * 结构：频道头（台标 + 台名 + 观看直播）→「今天 / 明天」分组 → 节目行。
 * 「现在」每 30 秒走一步 —— 节目单是给人看时间的东西，时间必须自己走。
 */
@Serializable
data class CineLiveTvGuideRoute(val channel: EmbyItem) : Screen {
  @Composable
  override fun Content() {
    // 这条路由只存在于 Cine 世界，不会回落 Classic ⇒ 主题在这里自己包
    CineTheme { CineLiveTvGuideScreen(channel) }
  }
}

@Composable
fun CineLiveTvGuideScreen(channel: EmbyItem, modifier: Modifier = Modifier) {
  val application = LocalContext.current.applicationContext as Application
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()
  val vm: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(application))
  val server by vm.currentServer.collectAsState()

  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space

  var programs by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
  var loading by remember { mutableStateOf(false) }
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(30_000)
    }
  }

  LaunchedEffect(server?.id, channel.Id) {
    val current = server ?: return@LaunchedEffect
    loading = true
    programs = withContext(Dispatchers.IO) {
      vm.loadLiveTvPrograms(current, channelId = channel.Id, hoursAhead = 24)
    }.sortedBy { it.StartDate ?: "" }
    loading = false
  }

  val nowInstant = remember(now) { Instant.ofEpochMilli(now) }
  val today = remember { LocalDate.now() }
  val tomorrow = remember { today.plusDays(1) }
  val grouped = remember(programs) {
    programs.groupBy {
      programInstant(it.StartDate)?.atZone(ZoneId.systemDefault())?.toLocalDate()
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.surface),
  ) {
    CineGlassTopBar(
      title = channel.Name ?: stringResource(R.string.livetv_program_guide),
      onBack = { backStack.removeLastOrNull() },
    )

    when {
      loading -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          color = colors.progressFill,
          modifier = Modifier.size(30.dp),
          strokeWidth = 2.5.dp,
        )
      }

      programs.isEmpty() -> CineStatePanel(
        icon = Icons.Outlined.Schedule,
        title = "没有节目单",
        description = stringResource(R.string.livetv_no_programs),
        primaryLabel = "返回",
        onPrimary = { backStack.removeLastOrNull() },
      )

      else -> LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .cineHazeSource(LocalCineHaze.current),
        contentPadding = PaddingValues(
          start = space.screenH,
          end = space.screenH,
          top = 14.dp,
          bottom = 32.dp,
        ),
      ) {
        item(key = "header") {
          CineChannelHeader(
            channel = channel,
            imageUrl = server?.let { vm.imageUrl(it, channel, "Primary", 480) },
            onWatch = {
              val current = server ?: return@CineChannelHeader
              scope.launch { vm.play(current, channel) }
            },
          )
          Spacer(Modifier.height(6.dp))
        }

        grouped.forEach { (date, dayPrograms) ->
          item(key = "day-$date") {
            Text(
              text = when (date) {
                today -> stringResource(R.string.livetv_today)
                tomorrow -> stringResource(R.string.livetv_tomorrow)
                else -> date?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: ""
              },
              style = type.sectionTitle.copy(fontSize = 15.5.sp),
              color = colors.sectionAction,
              modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
          }
          items(dayPrograms, key = { it.Id ?: it.Name ?: "" }) { program ->
            CineProgramRow(
              program = program,
              now = nowInstant,
              onClick = {
                val current = server ?: return@CineProgramRow
                scope.launch { vm.play(current, channel) }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CineChannelHeader(
  channel: EmbyItem,
  imageUrl: String?,
  onWatch: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val corner = remember { RoundedCornerShape(16.dp) }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier = Modifier
        .size(64.dp)
        .clip(corner)
        .background(colors.skeleton),
      contentAlignment = Alignment.Center,
    ) {
      EmbyImage(
        url = imageUrl,
        contentDescription = channel.Name,
        modifier = Modifier.fillMaxSize(),
        maxWidth = 240,
        contentScale = ContentScale.Crop,
        placeholder = Icons.Outlined.LiveTv,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = channel.Name ?: "",
        style = type.pageTitle.copy(fontSize = 21.sp),
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      channel.ChannelNumber?.takeIf { it.isNotBlank() }?.let {
        Text(
          text = stringResource(R.string.livetv_channel_number, it),
          style = type.cardMeta,
          color = colors.onSurfaceVariant,
        )
      }
    }
    Box(
      modifier = Modifier
        .height(38.dp)
        .clip(RoundedCornerShape(50))
        .background(colors.progressFill)
        .clickable(onClick = onWatch)
        .padding(horizontal = 14.dp),
      contentAlignment = Alignment.Center,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
          text = stringResource(R.string.livetv_watch_live),
          style = type.cardMeta.copy(fontSize = 12.5.sp),
          color = MaterialTheme.colorScheme.onPrimary,
          maxLines = 1,
        )
      }
    }
  }
}

/** 节目行：左侧时间栏 + 标题 + 简介；正在播出加主色底与细进度。 */
@Composable
private fun CineProgramRow(
  program: EmbyItem,
  now: Instant,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val scheme = MaterialTheme.colorScheme

  val start = programInstant(program.StartDate)
  val end = programInstant(program.EndDate)
  val airing = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
  val progress = if (airing) {
    val total = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
    ((now.toEpochMilli() - start.toEpochMilli()).toFloat() / total).coerceIn(0f, 1f)
  } else {
    0f
  }

  val shape = remember { RoundedCornerShape(14.dp) }
  val container = if (airing) colors.progressFill.copy(alpha = 0.14f) else colors.surface

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
      .clip(shape)
      .background(container)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 9.dp),
  ) {
    Column(
      modifier = Modifier.width(46.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Text(
        text = start?.atZone(ZoneId.systemDefault())
          ?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--",
        style = type.itemTitle,
        color = if (airing) colors.sectionAction else colors.onSurface,
      )
      Text(
        text = end?.atZone(ZoneId.systemDefault())
          ?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--",
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
      )
    }
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = program.Name ?: "",
          style = type.itemTitle,
          color = colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (airing) {
          Spacer(Modifier.width(6.dp))
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(colors.progressFill)
              .padding(horizontal = 6.dp, vertical = 1.dp),
          ) {
            Text(
              text = stringResource(R.string.livetv_live_now),
              style = type.badge,
              color = scheme.onPrimary,
              maxLines = 1,
            )
          }
        }
      }
      program.Overview?.takeIf { it.isNotBlank() }?.let {
        Text(
          text = it,
          style = type.cardMeta,
          color = colors.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (airing) {
        Spacer(Modifier.height(5.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.progressTrack),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(progress)
              .height(3.dp)
              .background(colors.progressFill),
          )
        }
      }
    }
  }
}
