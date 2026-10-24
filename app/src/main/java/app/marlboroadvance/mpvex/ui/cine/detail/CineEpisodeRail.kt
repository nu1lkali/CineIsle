package app.marlboroadvance.mpvex.ui.cine.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.utils.pressDownScale
import kotlinx.coroutines.delay

/** 一季（或「特典」）的剧集分组 */
internal data class SeasonGroup(
  val seasonNumber: Int?,
  val episodes: List<EmbyItem>,
)

internal fun groupEpisodesBySeason(episodes: List<EmbyItem>): List<SeasonGroup> {
  if (episodes.isEmpty()) return emptyList()
  return episodes
    .groupBy { it.ParentIndexNumber }
    .entries
    .sortedBy { it.key ?: Int.MAX_VALUE }
    .map { (season, items) ->
      SeasonGroup(
        seasonNumber = season,
        episodes = items.sortedBy { it.IndexNumber ?: Int.MAX_VALUE },
      )
    }
}

/**
 * 分集区：季下拉 + 集号胶囊 + 横滑卡。
 *
 * ## 为什么不用竖向长列表（v134 重构）
 * 竖向列表每集占 77dp，376 集就是 29000dp —— 用户从第 1 集滑到第 200 集要滑几十屏，
 * 而「找到第 200 集」恰恰是追长篇番剧最高频的操作。这种做法把「定位」交给横向的
 * 集号条（一屏能扫过 8~9 个集号、滑十几次就能到头），把「预览」交给下面的大卡。
 *
 * ## 集号条的自动滚动
 * 进页面时集号条必须停在**当前正在看的那一集**附近，而不是从头显示 1 2 3。
 * 留 2 格前距：让高亮胶囊离左边缘有一点上下文，不是孤零零贴边。
 *
 * ## 正序 / 倒序
 * 长篇番剧最常见的用法是「追更」：用户只关心最新几集，倒序能让他一进来就看见。
 * 顺序只影响本地展示，不改服务端数据。
 */
@Composable
internal fun CineEpisodeSection(
  groups: List<SeasonGroup>,
  selectedIndex: Int,
  onSelectSeason: (Int) -> Unit,
  currentEpisodeId: String?,
  thumbUrl: (EmbyItem) -> String?,
  descending: Boolean,
  onToggleSort: () -> Unit,
  onPlayEpisode: (EmbyItem) -> Unit,
  onToggleEpisodePlayed: (EmbyItem) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val group = groups.getOrNull(selectedIndex) ?: return

  val ordered = remember(group.episodes, descending) {
    if (descending) group.episodes.reversed() else group.episodes
  }
  // 没有续播记录时把第一集当作「当前」：用户扫的时候需要一个起点
  val activeId = currentEpisodeId ?: ordered.firstOrNull()?.Id
  val activeIndex = ordered.indexOfFirst { it.Id == activeId }.coerceAtLeast(0)

  val stripState = rememberLazyListState()

  Column(modifier = Modifier.fillMaxWidth()) {
    Spacer(Modifier.height(space.sectionGap))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = space.screenH, end = space.screenH),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SeasonPicker(
        groups = groups,
        selectedIndex = selectedIndex,
        onSelect = onSelectSeason,
      )
      Spacer(Modifier.weight(1f))
      SortSwitch(
        descending = descending,
        episodeCount = group.episodes.size,
        onToggle = onToggleSort,
      )
    }

    if (group.episodes.isEmpty()) {
      Spacer(Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.cine_detail_no_episodes),
        style = type.body,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = space.screenH),
      )
      return
    }

    Spacer(Modifier.height(12.dp))

    LazyRow(
      state = stripState,
      contentPadding = PaddingValues(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      items(ordered.size) { index ->
        val episode = ordered[index]
        EpisodeNumberPill(
          number = episode.IndexNumber ?: (index + 1),
          active = index == activeIndex,
          played = episode.UserData?.Played == true,
          onClick = { onPlayEpisode(episode) },
        )
      }
    }

    // 首帧滚到当前集。delay 一帧：LazyRow 要先完成测量，否则 animateScrollToItem
    // 在列表还没布局时调用会被忽略，停在第 0 项
    LaunchedEffect(activeId, ordered.size, selectedIndex) {
      if (activeIndex > 0) {
        delay(80)
        stripState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
      }
    }

    Spacer(Modifier.height(14.dp))

    LazyRow(
      contentPadding = PaddingValues(horizontal = space.screenH),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(ordered.size) { index ->
        val episode = ordered[index]
        EpisodeCard(
          episode = episode,
          thumbUrl = thumbUrl(episode),
          current = index == activeIndex,
          onClick = { onPlayEpisode(episode) },
          onLongClick = { onToggleEpisodePlayed(episode) },
        )
      }
    }
  }
}

/** `第 1 季 ⌄`：玻璃胶囊 + 下拉。只有一季时不可点（下拉里只有一项没有意义）。 */
@Composable
private fun SeasonPicker(
  groups: List<SeasonGroup>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
) {
  val colors = CineTheme.colors
  val shape = remember { RoundedCornerShape(50) }
  var expanded by remember { mutableStateOf(false) }

  val label = groups.getOrNull(selectedIndex)?.seasonNumber?.let {
    stringResource(R.string.cine_detail_season, it)
  } ?: stringResource(R.string.cine_detail_specials)

  Box {
    Row(
      modifier = Modifier
        .height(34.dp)
        .clip(shape)
        .background(colors.chipSurface.copy(alpha = if (groups.size > 1) 0.8f else 0f))
        .then(
          if (groups.size > 1) Modifier.border(1.dp, colors.chipBorder, shape) else Modifier,
        )
        .then(if (groups.size > 1) Modifier.pressDownScale().clickable { expanded = true } else Modifier)
        .padding(horizontal = if (groups.size > 1) 12.dp else 0.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = CineTheme.type.itemTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.W700),
        color = colors.onSurface,
        maxLines = 1,
      )
      if (groups.size > 1) {
        Icon(
          imageVector = Icons.Rounded.KeyboardArrowDown,
          contentDescription = null,
          tint = colors.onSurfaceVariant,
          modifier = Modifier.size(19.dp),
        )
      }
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      containerColor = colors.chipSurface,
    ) {
      groups.forEachIndexed { index, item ->
        val text = item.seasonNumber?.let {
          stringResource(R.string.cine_detail_season, it)
        } ?: stringResource(R.string.cine_detail_specials)
        DropdownMenuItem(
          text = {
            Text(
              text = text,
              style = CineTheme.type.itemTitle,
              color = if (index == selectedIndex) colors.sectionAction else colors.onSurface,
            )
          },
          onClick = {
            expanded = false
            onSelect(index)
          },
        )
      }
    }
  }
}

/** 正序 / 倒序切换。带集数，顺带把「这季有多少集」告诉用户。 */
@Composable
private fun SortSwitch(
  descending: Boolean,
  episodeCount: Int,
  onToggle: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Row(
    modifier = Modifier
      .height(32.dp)
      .clip(shape)
      .clickable(onClick = onToggle)
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Icon(
      imageVector = Icons.Rounded.SwapVert,
      contentDescription = null,
      tint = colors.sectionAction,
      modifier = Modifier.size(15.dp),
    )
    Text(
      text = stringResource(
        if (descending) R.string.cine_detail_sort_desc else R.string.cine_detail_sort_asc,
      ),
      style = type.cardMeta.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
      color = colors.sectionAction,
      maxLines = 1,
    )
    Text(
      text = stringResource(R.string.cine_detail_episode_count, episodeCount),
      style = type.cardMeta.copy(fontSize = 11.5.sp),
      color = colors.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

/** 集号胶囊。选中 = 主题色实心；已看 = 文字降透明度（与选中态互不干扰）。 */
@Composable
private fun EpisodeNumberPill(
  number: Int,
  active: Boolean,
  played: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = Modifier
      .height(30.dp)
      .clip(shape)
      .background(if (active) colors.progressFill else colors.chipSurface)
      .then(
        if (active) Modifier else Modifier.border(1.dp, colors.chipBorder.copy(alpha = 0.5f), shape),
      )
      .pressDownScale()
      .clickable(onClick = onClick)
      .padding(horizontal = 11.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = number.toString(),
      style = CineTheme.type.cardMeta.copy(
        fontSize = 12.5.sp,
        fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
      ),
      color = when {
        active -> colors.surface
        played -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        else -> colors.onSurfaceVariant
      },
      maxLines = 1,
    )
  }
}

/**
 * 剧集大卡：16:9 缩略图 + 一行标题。
 *
 * ## 描边
 * 图本身是暗调的时候，卡片边缘会糊进页面底色（详情页底色也是暗的），加一道
 * `glassBorder` 描边才能把「这是一张可点的卡」说清楚 —— 与参考图的白细边同源。
 * 当前集的描边换成主题色：扫这排卡片时，用户需要一个「我在这儿」的锚点。
 */
@Composable
private fun EpisodeCard(
  episode: EmbyItem,
  thumbUrl: String?,
  current: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(11.dp) }

  val total = episode.RunTimeTicks ?: 0L
  val position = episode.UserData?.PlaybackPositionTicks ?: 0L
  val progress = if (total > 0 && position > 0) {
    (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
  } else {
    null
  }
  val played = episode.UserData?.Played == true
  val number = episode.IndexNumber
  val episodeName = episode.Name?.trim().orEmpty()

  Column(
    modifier = Modifier
      .width(168.dp)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(94.dp)
        .clip(shape)
        .background(colors.skeleton)
        .border(
          width = if (current) 1.5.dp else 1.dp,
          color = if (current) colors.progressFill else colors.glassBorder,
          shape = shape,
        ),
    ) {
      EmbyImage(
        url = thumbUrl,
        contentDescription = episode.Name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 480,
      )
      if (played) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(colors.progressFill),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = colors.surface,
            modifier = Modifier.size(12.dp),
          )
        }
      }
      if (progress != null) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp)
            .background(colors.onImageTrack),
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

    Spacer(Modifier.height(6.dp))
    Text(
      text = buildString {
        if (number != null) append("E$number")
        // 单集名常常就叫「第 375 集」，跟集号重复时只留 E375。
        // ⚠️ 必须写 `${number}集`：`$number集` 会被当成变量名 `number集`（中文是合法标识符）
        val redundant = number != null &&
          (episodeName == "第 $number 集" || episodeName == "第${number}集")
        if (episodeName.isNotEmpty() && !redundant) {
          if (isNotEmpty()) append(" · ")
          append(episodeName)
        }
      },
      style = type.cardMeta.copy(fontSize = 12.sp),
      color = if (current) colors.onSurface else colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
