package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/**
 * 详情页的「分集」区（电视剧 / 综艺用）。
 *
 * ## 为什么做成「可折叠 + 横向一行」
 *
 * 长剧动辄几十上百集 —— 竖向铺开会把「简介 / 演职员 / 媒体信息」全推到屏幕外几百屏，
 * 详情页就废了。所以规则是：
 *
 *  · **默认收起**，只显示「第 N 集」+ 一句「共 X 集 · 已看 Y 集」；
 *  · 展开后是一次**横向滑动**的分集卡片行（不占竖向空间）；
 *  · 多季剧额外给一排季号 chip，切季只换那一行。
 *
 * ## 交互约定（与媒体库一致）
 *
 *  · **点集 = 直接播这一集**（并带后续连播）—— 这是最高频动作，不给它加二次确认；
 *  · **长按集 = 进那一集的详情页**（看简介 / 换版本 / 换音轨…）。
 *
 * `onClick` / `onLongClick` 由调用方决定去哪 —— 组件本身不认识 ViewModel。
 *
 * @param episodes 全剧所有分集（按季、按集号排好序）
 * @param isPlayed 取「这一集看没看完」，由调用方按 UserData 判断
 * @param progressOf 取「这一集看到哪」，返回 0f..1f；null = 不画进度条
 * @param resumeSecondsOf 取「这一集续播秒数」；null = 从头
 * @param currentEpisodeId 正在播放 / 上次播放的那一集（列表里高亮它）
 * @param downloadStateOf 取「这一集的离线下载状态」：null = 没下过（显示可下载），
 *   [EpisodeDownloadState.Queued] = 已排队 / 下载中，[EpisodeDownloadState.Done] = 已下好。
 *   只影响卡片左上角那个小按钮的样式，**不影响点击播放**。
 * @param onDownload 点卡片上的下载按钮（回调里应自己判重，避免重复入队）
 */
@Composable
fun EpisodeListSection(
  episodes: List<EmbyItem>,
  modifier: Modifier = Modifier,
  currentEpisodeId: String? = null,
  isPlayed: (EmbyItem) -> Boolean = { it.UserData?.Played == true },
  progressOf: (EmbyItem) -> Float? = { it.playbackProgress() },
  resumeSecondsOf: (EmbyItem) -> Long? = { it.resumeSeconds() },
  onClick: (EmbyItem, Long) -> Unit = { item, _ -> },
  onLongClick: (EmbyItem) -> Unit = {},
  downloadStateOf: (EmbyItem) -> EpisodeDownloadState? = { null },
  onDownload: (EmbyItem) -> Unit = {},
  onDownloadSeason: (List<EmbyItem>) -> Unit = {},
) {
  if (episodes.isEmpty()) return

  // 按季分组：只有一季（或压根没有季号）时不画季 chip，省掉一行无用控件。
  val seasons = remember(episodes) {
    episodes.mapNotNull { it.ParentIndexNumber }.distinct().sorted()
  }
  val showSeasonChips = seasons.size > 1

  // 默认收起。⚠️ 用 rememberSaveable：从播放器返回会重建本页组合，
  // 用 remember 的话用户刚展开的位置会被弹回收起态。
  var expanded by rememberSaveable { mutableStateOf(false) }
  // null = 没手动选季 ⇒ 跟随「当前播放的那一集」或第一季。
  var pickedSeason by rememberSaveable { mutableStateOf<Int?>(null) }

  val currentSeason = remember(episodes, currentEpisodeId) {
    episodes.firstOrNull { it.Id == currentEpisodeId }?.ParentIndexNumber
  }
  val activeSeason = pickedSeason ?: currentSeason ?: seasons.firstOrNull()
  // 单季剧把所有集都当作一季显示。
  val visible = remember(episodes, activeSeason, showSeasonChips) {
    if (!showSeasonChips) episodes
    else episodes.filter { it.ParentIndexNumber == activeSeason }
  }

  val playedCount = remember(episodes) { episodes.count { isPlayed(it) } }

  Column(modifier = modifier.fillMaxWidth()) {
    // ── 标题行（点击整行展开 / 收起）──
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text("分集", style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = buildString {
          append("共 ${episodes.size} 集")
          if (playedCount > 0) append(" · 已看 $playedCount 集")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "收起分集" else "展开分集",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column {
        // ── 季选择 ──
        if (showSeasonChips) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            seasons.forEach { season ->
              SeasonChip(
                text = if (season == 0) "特别篇" else "第 $season 季",
                selected = season == activeSeason,
                onClick = { pickedSeason = season },
              )
            }
          }
        }

        // ── 批量操作行 ──
        // 「下载本季」单独一行、**左对齐**：和上面季节 chip 行、下面分集行
        // 的 16dp 起始边距对齐，视觉上是一条竖线。
        // 单独成行的理由仍在：它是**作用于当前这一季**的动作，
        // 若和季 chip 放同一行最右，横向滚到末尾才会看见，长剧里等于藏起来了。
        // ⚠️ 做成**胶囊按钮**（tonal 底 + 圆角 50% + 下载图标）而不是一段带
        //    clip 的文本：后者看着像正文链接，用户会漏掉这个批量入口。
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp),
          horizontalArrangement = Arrangement.Start,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(MaterialTheme.colorScheme.secondaryContainer)
              .clickable(enabled = visible.isNotEmpty()) { onDownloadSeason(visible) }
              .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.Download,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "下载本季 ${visible.size} 集",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
        }

        // ── 分集横向行 ──
        // ⚠️ 只画一行、靠 LazyRow 横向懒加载：几百集也不会一次性组合。
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 12.dp,
          ),
        ) {
          items(visible, key = { it.Id ?: it.Name.orEmpty() }) { ep ->
            EpisodeCard(
              episode = ep,
              played = isPlayed(ep),
              progress = progressOf(ep),
              isCurrent = ep.Id != null && ep.Id == currentEpisodeId,
              downloadState = downloadStateOf(ep),
              onClick = { onClick(ep, resumeSecondsOf(ep) ?: 0L) },
              onLongClick = { onLongClick(ep) },
              onDownload = { onDownload(ep) },
            )
          }
        }

        // 展开时把手感细节补一句：长按能看到更多。
        Text(
          text = "点击直接播放 · 长按查看该集详情",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
      }
    }
  }
}

/**
 * 分集的「三分区」数据（对标 Yamby 的 `siblingEpisodes` / `showingEpisodes` / `personEpisodes`）。
 *
 * ## 为什么需要三个区而不是一个列表
 *
 * 详情页能拿到的分集只有一种来源——「这部剧全部分集」，而它**天然缺两类东西**：
 *
 *  1. **特别篇 / SP / 花絮**：Emby 把它们放在 `Season 0`，或者挂在不同的季下。
 *     按季 chip 切换的话，用户根本不会想到「特别篇在第 0 季」，等于不存在。
 *  2. **演员相关**：某一集里出现的演员，在**同一部剧**里还有哪些集 —— Emby 没有
 *     「本剧内按人筛选」的现成端点，但 `/Items?PersonIds&Recursive&IncludeItemTypes=Episode`
 *     能查出来，再按 `SeriesId` 过滤就得到了。
 *
 * ## 成本
 *
 * - [sameSeason] / [specials] 都来自**已有的那份分集列表**，纯本地分组，零请求；
 * - [actorEpisodes] 需要一次 `/Items` 请求 —— 只在详情页是单集时发（见 `EmbyDetailScreen`），
 *   且复用与「相关推荐」同一批演员，不额外多查。
 */
data class RelatedEpisodes(
  /** 同季其它集（含当前这一集本身，由组件高亮）。 */
  val sameSeason: List<EmbyItem> = emptyList(),
  /** 特别篇 / SP / OVA / 花絮（已排除正片）。 */
  val specials: List<EmbyItem> = emptyList(),
  /** 演员在本剧中参演的其它集；[actorName] 是这个区的标题后缀。 */
  val actorEpisodes: List<EmbyItem> = emptyList(),
  val actorName: String? = null,
) {
  val isEmpty: Boolean
    get() = sameSeason.isEmpty() && specials.isEmpty() && actorEpisodes.isEmpty()
}

/**
 * 详情页的「相关分集」区：**同季 / 特别篇 / 演员相关**三分区，各占一行。
 *
 * ⚠️ 只在「当前条目是单集」时用。电视剧详情页走的是 [EpisodeListSection]（全剧 + 季 chip），
 * 那里所有集都在，再摊一个三分区只会重复。
 *
 * 三个区**独立显示**：某一区为空就整块不画，不留一个空标题占位
 * （「特别篇（0）」比不显示更让人困惑）。
 */
@Composable
fun RelatedEpisodesSection(
  related: RelatedEpisodes,
  currentEpisodeId: String? = null,
  modifier: Modifier = Modifier,
  isPlayed: (EmbyItem) -> Boolean = { it.UserData?.Played == true },
  progressOf: (EmbyItem) -> Float? = { it.playbackProgress() },
  resumeSecondsOf: (EmbyItem) -> Long? = { it.resumeSeconds() },
  onClick: (EmbyItem, Long) -> Unit = { item, _ -> },
  onLongClick: (EmbyItem) -> Unit = {},
) {
  if (related.isEmpty) return

  Column(modifier = modifier.fillMaxWidth()) {
    // 同季区把「当前这一集」留在里面并高亮：用户从这里进来，需要看到自己在序列里的位置。
    RelatedEpisodeRow(
      title = "同季分集",
      episodes = related.sameSeason,
      currentEpisodeId = currentEpisodeId,
      isPlayed = isPlayed,
      progressOf = progressOf,
      resumeSecondsOf = resumeSecondsOf,
      onClick = onClick,
      onLongClick = onLongClick,
    )
    RelatedEpisodeRow(
      title = "特别篇",
      episodes = related.specials,
      currentEpisodeId = currentEpisodeId,
      isPlayed = isPlayed,
      progressOf = progressOf,
      resumeSecondsOf = resumeSecondsOf,
      onClick = onClick,
      onLongClick = onLongClick,
    )
    RelatedEpisodeRow(
      title = related.actorName?.let { "同剧中 TA 的其它分集 · $it" } ?: "同剧其它分集",
      episodes = related.actorEpisodes,
      currentEpisodeId = currentEpisodeId,
      isPlayed = isPlayed,
      progressOf = progressOf,
      resumeSecondsOf = resumeSecondsOf,
      onClick = onClick,
      onLongClick = onLongClick,
    )
  }
}

@Composable
private fun RelatedEpisodeRow(
  title: String,
  episodes: List<EmbyItem>,
  currentEpisodeId: String?,
  isPlayed: (EmbyItem) -> Boolean,
  progressOf: (EmbyItem) -> Float?,
  resumeSecondsOf: (EmbyItem) -> Long?,
  onClick: (EmbyItem, Long) -> Unit,
  onLongClick: (EmbyItem) -> Unit,
) {
  if (episodes.isEmpty()) return
  Column {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "共 ${episodes.size} 集",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    LazyRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        start = 16.dp,
        end = 16.dp,
        bottom = 10.dp,
      ),
    ) {
      // ⚠️ key 用季+集号而不是 Id：同一个 Id 理论上只出现一次，但「同季」与
      // 「演员相关」两个区会**同时**包含兄弟集；两个 LazyRow 各自独立作用域，不冲突。
      items(episodes, key = { it.Id ?: it.Name.orEmpty() }) { ep ->
        EpisodeCard(
          episode = ep,
          played = isPlayed(ep),
          progress = progressOf(ep),
          isCurrent = ep.Id != null && ep.Id == currentEpisodeId,
          // 这一区只负责「换一集看」，不放下载钮 —— 150dp 的卡上再挤一个按钮会吃掉标题。
          downloadState = EpisodeDownloadState.Done,
          onClick = { onClick(ep, resumeSecondsOf(ep) ?: 0L) },
          onLongClick = { onLongClick(ep) },
          onDownload = {},
        )
      }
    }
  }
}

/**
 * 分集卡片：一集一张，宽度固定。
 *
 * ⚠️ 这里**不复用** [EmbyMediaCard]：那张卡是按封面比例（16:9 / 2:3）画的，
 * 分集要的是「集号 + 标题 + 时长」这种紧凑信息块，塞进卡片会把每张都撑到 160dp 宽，
 * 一行看不了几集。所以另画一套更省空间的。
 */
@Composable
private fun EpisodeCard(
  episode: EmbyItem,
  played: Boolean,
  progress: Float?,
  isCurrent: Boolean,
  downloadState: EpisodeDownloadState?,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onDownload: () -> Unit,
) {
  val container =
    if (isCurrent) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  val number = episode.IndexNumber
  val label = when {
    number != null -> number.toString()
    else -> episode.Name?.take(2).orEmpty()
  }

  // 标题**至少**占两行高：实际只一行时也把位置占住，卡片才不会一张高一张矮
  // （横向分集行参差不齐）。行高取 bodySmall 的 lineHeight，缺省按 1.2×字号兜底；
  // sp→dp 要乘 fontScale —— 用户放大系统字号时，两行的高度跟着涨，不会挤爆。
  // ⚠️ 这里必须给 **heightIn(min=…)** 而不是 `height(…)`。
  //   先前写死 `height(两行)`，算出来的高度一旦比文字真正要的两行**少一两个像素**
  //   （dp→px 取整 + 字体 fallback 的余量），第二行就被裁掉 ——
  //   用户看到的正是「标题被截断成一行，那还叫不影响标题显示吗」。
  //   改成最小高度后：**保证至少两行**，两行标题想再高也不会被切，卡片照样等高。
  val titleLineHeightSp = MaterialTheme.typography.bodySmall.let { style ->
    if (style.lineHeight.isSpecified) style.lineHeight.value else style.fontSize.value * 1.2f
  }
  val titleHeight = with(LocalDensity.current) { (titleLineHeightSp * 2 * fontScale).dp }

  Surface(
    color = container,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .width(150.dp)
      .clip(RoundedCornerShape(12.dp))
      .tapOrLongPress(onClick = onClick, onLongPress = onLongClick),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
              if (played) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (played) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = episode.Name ?: "第 ${number ?: "?"} 集",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .weight(1f)
            // ⚠️ 是 **heightIn(min=…)** 不是 `height(…)`。
            //   `height()` 写死两行高，一旦算出来的高度比文字真正要的两行少一两个像素
            //   （sp→dp 取整、includeFontPadding 的余量），第二行就被裁掉
            //   ⇒ 用户看到的正是「标题被截断成一行」。
            //   `heightIn(min=)` 只保证**至少**两行高：一行标题照样占位、卡片等高，
            //   真有两行时容器自然长到够放，两行都完完整整显示出来，绝不会被切。
            //   maxLines=2 + 省略号兜底，超长标题不会把卡片撑高。
            .heightIn(min = titleHeight),
        )
        // 下载入口：卡宽只有 150dp，所以做成 24dp 的极小钮（48dp 的点击区由 IconButton 撑开
        // 会挤掉标题 ⇒ 这里用最小尺寸的 IconButton，靠外层 padding 补手感）。
        // ⚠️ 它必须和整卡的 tapOrLongPress **共存**：IconButton 自己消费点击，
        //    点它不会触发播放（Compose 的事件分发是「子节点优先」）。
        IconButton(
          onClick = onDownload,
          modifier = Modifier.size(24.dp),
          enabled = downloadState == null,
        ) {
          Icon(
            imageVector = when (downloadState) {
              null -> Icons.Default.Download
              EpisodeDownloadState.Queued -> Icons.Default.DownloadDone
              EpisodeDownloadState.Done -> Icons.Default.DownloadDone
            },
            contentDescription =
              when (downloadState) {
                null -> "下载这一集"
                EpisodeDownloadState.Queued -> "已在下载队列"
                EpisodeDownloadState.Done -> "已下载"
              },
            tint = if (downloadState == null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
      // ⚠️ 卡片高度一共三个变量，三个都要治，治漏一个就是「有的高有的矮」：
      //   ① 标题 1 行 vs 2 行   → 上面 `heightIn(min = titleHeight)` 已治；
      //   ② 时长行在 `RunTimeTicks` 缺失时返回 null ⇒ 整行塌掉（±20dp）；
      //   ③ 进度条只在「看过一部分」时才存在（±9dp）。
      // ②③ 合并进**同一行**并给它**固定高度**：缺内容时留空而不塌缩。
      // ⚠️ 不能写成两个 `if` —— 那正是 只治了 ① 之后仍然参差的原因：
      //   卡高由「有没有内容」决定，而不是由「最高那张卡」决定。
      //   卡宽只有 150dp，进度条挤不下一整行，所以做成 32dp 跟在时长右边。
      val metaLineHeightSp = MaterialTheme.typography.labelSmall.let { style ->
        if (style.lineHeight.isSpecified) style.lineHeight.value else style.fontSize.value * 1.2f
      }
      val metaHeight = with(LocalDensity.current) { (metaLineHeightSp * fontScale).dp }
      Spacer(modifier = Modifier.height(4.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .height(metaHeight),
      ) {
        Text(
          // 没有时长就渲染空串：**位置照样占住**，卡片才不会矮一截
          text = episode.runTimeLabel().orEmpty(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (progress != null && progress > 0f && progress < 1f) {
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
              .padding(start = 6.dp)
              .width(32.dp)
              .height(3.dp)
              .clip(RoundedCornerShape(2.dp)),
          )
        }
      }
    }
  }
}

/**
 * 分集卡片上「下载按钮」要显示的状态。
 *
 * 刻意做得很小（三态）而不是直接透传 [app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus]：
 * 卡片只关心「能不能点」，排队 / 下载中 / 暂停 / 失败在用户眼里都是「已经交出去了」，
 * 具体百分比与重试在下载管理页看 —— 150dp 的卡上塞不进 ProgressBar。
 */
enum class EpisodeDownloadState { Queued, Done }

@Composable
private fun SeasonChip(text: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(50),
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .clickable(onClick = onClick),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = if (selected) MaterialTheme.colorScheme.onPrimary
      else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

// ── 小工具 ──

/**
 * 「单击 / 长按」合成 modifier。
 *
 * ⚠️ 必须用 `detectTapGestures` 而不是「clickable + longClick」两段叠加：
 * 叠加时一次长按在松手后会**同时**触发 onClick 与长按回调 —— 表现就是
 * 「长按想进详情，结果直接开始播放了」。`combinedClickable` 语义是对的，
 * 但这里要自己加震动，所以干脆用底层手势。
 */
private fun Modifier.tapOrLongPress(
  onClick: () -> Unit,
  onLongPress: () -> Unit,
): Modifier = composed {
  val haptics = LocalHapticFeedback.current
  pointerInput(onClick, onLongPress) {
    detectTapGestures(
      onTap = { onClick() },
      onLongPress = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPress()
      },
    )
  }
}

/** 播放进度 0f..1f；未开始 / 已看完 / 时长未知都返回 null（不画进度条）。 */
internal fun EmbyItem.playbackProgress(): Float? {
  val pos = UserData?.PlaybackPositionTicks ?: return null
  val total = RunTimeTicks ?: return null
  if (pos <= 0 || total <= 0) return null
  val f = (pos.toDouble() / total.toDouble()).toFloat()
  return f.takeIf { it > 0.01f && it < 0.99f }
}

/** 续播秒数；没有进度返回 null。 */
internal fun EmbyItem.resumeSeconds(): Long? {
  val pos = UserData?.PlaybackPositionTicks ?: return null
  if (pos <= 0) return null
  return pos / 10_000_000L
}

/** 时长文案（「24 分钟」/「1 小时 2 分」）；未知返回 null。 */
internal fun EmbyItem.runTimeLabel(): String? {
  val ticks = RunTimeTicks ?: return null
  val minutes = (ticks / 10_000_000L / 60L).toInt()
  if (minutes <= 0) return null
  return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
}

/**
 * 这一集算不算「特别篇 / 花絮 / 预告」等非正片。
 *
 * 判据按可靠性排序，命中任一即算：
 *  1. `IsSpecialFeature`（服务端明确标注时最准）；
 *  2. `ExtraType` 非空（Emby 给花絮/预告打的是 Trailer / BehindTheScenes / DeletedScene …）；
 *  3. `Season 0`（Emby 与 TMDB 都把 SP / OVA 塞在「第 0 季」）。
 *
 * ⚠️ 只在**分区显示**时用它，绝不用来过滤掉分集：判错的最坏结果是
 * 「特别篇混进正片区」或反之，而不是「某一集凭空消失」。
 */
internal fun EmbyItem.isSpecial(): Boolean =
  IsSpecialFeature == true ||
    !ExtraType.isNullOrBlank() ||
    ParentIndexNumber == 0
