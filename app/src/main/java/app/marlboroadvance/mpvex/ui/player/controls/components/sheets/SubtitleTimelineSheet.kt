package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleCue
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTimeline
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 一条字幕文件的时间轴加载结果。 */
private sealed interface CueLoad {
  object Loading : CueLoad

  data class Ready(
    val cues: List<SubtitleCue>,
    val truncated: Boolean,
  ) : CueLoad

  data class Failed(
    val reason: String,
  ) : CueLoad
}

/** 正在看的那条轨道此刻在播什么角色 —— 决定要不要高亮 + 用哪份延迟。 */
private enum class TrackRole { Primary, Secondary, Inactive }

/**
 * 播放页「字幕时间线」面板。
 *
 * ## 它解决什么
 *
 * 播放中想按台词定位（「从这句开始对不上」「回去看那句怎么翻的」）时，进度条只能靠拖 ——
 * 没有参照物。这里把**当前已加载字幕**的整条时间轴列出来：点一句就跳过去，
 * 画面播到哪儿就高亮哪一句，并且自动把它滚进视野。
 *
 * ## 与字幕轨道的联动
 *
 * · 主字幕（`sid`）与副字幕（`secondary-sid`）同时挂着时，顶部两个 chip 可切换
 *   **当前查看的是哪一条**；chip 上标出「主 / 副」，与字幕面板的口径一致。
 * · 正在看的那条**不是**主字幕时，header 出现「设为主字幕」—— 这才是真正改变播放状态的
 *   那一步，且只改 `sid`，不会把另一条顶掉（由 ViewModel 负责清冲突的 `secondary-sid`）。
 * · 高亮只对**真正在显示**的轨道生效。查看一条没被选中的轨道时，时间轴仍可用来定位，
 *   但不会画「正在播这一句」——那条字幕此刻根本不在画面上，画了就是撒谎。
 *
 * ## 跟随
 *
 * 与字幕实验室同一套口径：默认跟随，只在「当前句不在视野内」时才滚动（每句都居中的话
 * 画面会一直抖）；用户手动滑过列表后不会被他自己的滚动打断；搜索时自动停跟 ——
 * 搜台词是为了看别处，这时把列表拽回播放位置只会让人找不到刚搜到的结果。
 *
 * ## 降级
 *
 * 时间轴只能从**外挂字幕文件**读（内嵌在视频里的轨没有文件可读），所以：
 *  · 没有字幕轨 / 全是内嵌轨 / 字幕文件已被清理 / 格式解析不出条目 —— 各自给一句人话，
 *    并留一个「字幕列表」出口，而不是留一片空白。
 */
@Composable
fun SubtitleTimelineSheet(
  tracks: ImmutableList<TrackNode>,
  /** 当前主字幕（`sid`）轨号，0 = 没开 */
  primaryId: Int,
  /** 当前副字幕（`secondary-sid`）轨号，0 = 没有 */
  secondaryId: Int,
  /** 播放位置（秒）。mpv 的 `time-pos` 是整秒，够用于「哪一句在播」 */
  positionSeconds: Int,
  onSeekToMs: (Long) -> Unit,
  /** 把某条轨道切成主字幕（`sid`） */
  onPromoteToPrimary: (Int) -> Unit,
  /** 回字幕轨道面板 */
  onBackToTracks: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current

  // 只有外挂轨才有磁盘文件可读；内嵌轨（external=false）没有时间轴来源
  val viewable =
    remember(tracks) {
      tracks.filter { it.isSubtitle && it.external == true && !it.externalFilename.isNullOrBlank() }
    }

  // 默认看「已经在播的那条」，其次副字幕，再次第一条可读的
  var viewedId by remember { mutableStateOf(0) }
  LaunchedEffect(viewable, primaryId, secondaryId) {
    if (viewable.none { it.id == viewedId }) {
      viewedId =
        listOf(primaryId, secondaryId).firstOrNull { id -> viewable.any { it.id == id } }
          ?: viewable.firstOrNull()?.id
          ?: 0
    }
  }
  val viewed = viewable.firstOrNull { it.id == viewedId }

  val role =
    when (viewedId) {
      primaryId -> TrackRole.Primary
      secondaryId -> TrackRole.Secondary
      else -> TrackRole.Inactive
    }

  // 解析结果按「文件名」缓存在面板生命周期内：来回切主/副不该每次都重解析一遍
  // （一条 ASS 几千句，重解析是肉眼可见的卡顿）。关掉面板即释放。
  val cache = remember { mutableStateMapOf<String, CueLoad>() }
  val fileKey = viewed?.externalFilename
  LaunchedEffect(fileKey) {
    if (fileKey == null || cache.containsKey(fileKey)) return@LaunchedEffect
    cache[fileKey] = CueLoad.Loading
    cache[fileKey] = withContext(Dispatchers.IO) { loadCues(context, fileKey) }
  }
  val load = fileKey?.let { cache[it] }

  var query by remember { mutableStateOf("") }
  val tokens =
    remember(query) {
      query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

  val cues = (load as? CueLoad.Ready)?.cues.orEmpty()
  // 多词按 AND 组合，与字幕面板的筛选框同一口径：打「你 好」不会变成「你 或 好」
  val visible =
    remember(cues, tokens) {
      if (tokens.isEmpty()) {
        cues
      } else {
        cues.filter { cue -> tokens.all { it in cue.text.lowercase() } }
      }
    }

  // 字幕延迟（`sub-delay` 秒）：正数表示字幕整体延后，于是「画面这一刻」对应的是
  // 文件时间里更早的位置。副字幕有自己的 `secondary-sub-delay`，但本项目的延迟面板
  // 两个属性写的是同一个值（见 MPVView.setSubDelay），这里取主属性即可。
  val delayFlow = remember { PlayerLib.propDouble["sub-delay"] }
  val subDelay by delayFlow.collectAsState()
  val offsetMs = if (role == TrackRole.Inactive) 0L else ((subDelay ?: 0.0) * 1000.0).toLong()

  val activeIndex =
    remember(visible, positionSeconds, role, offsetMs) {
      if (role == TrackRole.Inactive || visible.isEmpty()) {
        -1
      } else {
        val fileMs = positionSeconds * 1000L - offsetMs
        visible.indexOfFirst { fileMs >= it.startMs && fileMs < it.endMs }
      }
    }

  var follow by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()

  // 跟随播放：三者缺一都会变成「我正翻列表，它自己往下跳」——
  //  · follow 被用户关掉
  //  · 当前句不在视野内时才有必要滚（每句都居中会让画面一直抖）
  //  · 用户正在滑动
  //  · 正在搜索（搜台词就是去看别处）
  LaunchedEffect(activeIndex, follow, tokens) {
    if (!follow || activeIndex < 0 || tokens.isNotEmpty()) return@LaunchedEffect
    if (listState.isScrollInProgress) return@LaunchedEffect
    val first = listState.firstVisibleItemIndex
    val last = first + listState.layoutInfo.visibleItemsInfo.size - 1
    if (activeIndex < first || activeIndex > last - 1) {
      listState.animateScrollToItem(activeIndex)
    }
  }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    // 标题里要显示「看的是哪条」；getTrackTitle 内部走 stringResource，先取出来再拼串
    val viewedTitle = viewed?.let { getTrackTitle(it) } ?: "未选择字幕"

    Column(modifier = Modifier.fillMaxWidth()) {
      // ── 标题 / 跟随 / 返回 ──
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.spacing.medium, end = MaterialTheme.spacing.small, top = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "字幕时间线",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = buildString {
              append(viewedTitle)
              if (cues.isNotEmpty()) append(" · ${cues.size} 句")
              if (tokens.isNotEmpty()) {
                append(" · 命中 ")
                append(if (visible.size == cues.size) "全部" else "${visible.size} 句")
              }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // 跟随开关：默认开；用户往下翻看别的段落时点一下即可停止自动滚动
        if (activeIndex >= 0 && tokens.isEmpty()) {
          TextButton(onClick = { follow = !follow }) {
            Icon(
              if (follow) Icons.Filled.PauseCircle else Icons.Filled.PlayCircleOutline,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
              text = if (follow) "跟随中" else "已暂停跟随",
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
        TextButton(onClick = onBackToTracks) { Text("字幕列表") }
      }

      // ── 轨道切换：主 / 副 ──
      if (viewable.size >= 2 || (viewable.size == 1 && role != TrackRole.Primary)) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          viewable.forEach { track ->
            val isViewed = track.id == viewedId
            FilterChip(
              selected = isViewed,
              onClick = { viewedId = track.id },
              label = {
                Text(
                  text = getTrackTitle(track),
                  style = MaterialTheme.typography.labelMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  // 轨道标题可能是长文件名；不给上限会把 chip 撑到把整行挤满
                  modifier = Modifier.widthIn(max = 180.dp),
                )
              },
              leadingIcon = {
                val badge =
                  when (track.id) {
                    primaryId -> "主"
                    secondaryId -> "副"
                    else -> null
                  }
                if (badge != null) {
                  Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                      if (isViewed) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                  )
                }
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
          }
        }
      }

      // 查看的不是主字幕 ⇒ 给一步「真的把它切成主字幕」，这才是改变播放状态的动作
      val otherSelected = role == TrackRole.Secondary
      if (role != TrackRole.Primary && viewed != null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text =
              if (otherSelected) {
                "这一条是副字幕（画面上方），时间轴可定位，但高亮跟随的是主字幕。"
              } else {
                "这一条当前没有启用，时间轴可用于按台词定位。"
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { onPromoteToPrimary(viewed.id) }) {
            Text("设为主字幕", style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      if (load is CueLoad.Ready && load.truncated) {
        Text(
          text = "该字幕条目数超过 ${SubtitleTimeline.MAX_CUES} 条，此处仅列出前 ${SubtitleTimeline.MAX_CUES} 条",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )
      }

      // ── 搜台词 ──
      if (cues.isNotEmpty()) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium,
          placeholder = { Text("搜索台词文本", style = MaterialTheme.typography.bodyMedium) },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Default.Close, contentDescription = "清空搜索")
              }
            }
          },
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
        )
      }

      // 轨道 / 轨道上的台词一条都没有时，先给一句为什么（搜索框这时也不出现，
      // 没有东西可搜）
      if (visible.isEmpty()) {
        EmptyHint(load = load, hasTracks = tracks.isNotEmpty(), hasViewable = viewable.isNotEmpty(), query = query.trim())
      }

      if (visible.isNotEmpty()) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(bottom = MaterialTheme.spacing.large),
        ) {
          itemsIndexed(visible) { index, cue ->
            CueRow(
              index = index + 1,
              cue = cue,
              active = index == activeIndex,
              onClick = {
                // 点了某句 = 明确要「去那里」，所以把跟随打开，跳完继续跟着播
                follow = true
                onSeekToMs(cue.startMs)
              },
            )
          }
        }
      }
    }
  }
}

/**
 * 空态：把「为什么没有内容」分成四类说清楚。
 *
 * 时间段面板最常见的困惑是「我明明挂了字幕，为什么这里什么都没有」——
 * 而那通常是**内嵌轨**（视频里烧好的字幕），它没有可读的文件。
 */
@Composable
private fun EmptyHint(
  load: CueLoad?,
  hasTracks: Boolean,
  hasViewable: Boolean,
  query: String,
) {
  val text =
    when {
      query.isNotEmpty() -> "没有台词包含「$query」"
      !hasTracks -> "当前没有字幕轨道。先在字幕面板里挂一条外挂字幕，这里才有时间轴可读。"
      !hasViewable -> "正在用的字幕内嵌在视频里，读不到时间轴。外挂字幕（srt / ass / vtt）才支持按台词跳转。"
      load is CueLoad.Loading -> null
      load is CueLoad.Failed -> load.reason
      load == null -> "正在准备字幕…"
      else -> "这条字幕里没有可读的台词"
    }

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 120.dp)
        .padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
    contentAlignment = Alignment.Center,
  ) {
    if (load is CueLoad.Loading) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Text(
          text = "正在解析字幕时间轴…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else if (text != null) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 一句台词：序号 + 起止时间 + 正文，点击跳到该句起点。
 *
 * [active] 为真表示画面此刻正播这一句 —— 底色 + 左侧竖条同时在，是因为只改字色
 * 在一屏几十行里根本看不出来，而「哪句在播」正是这个面板的存在意义。
 */
@Composable
private fun CueRow(
  index: Int,
  cue: SubtitleCue,
  active: Boolean,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.smaller)
          .clip(RoundedCornerShape(8.dp))
          .background(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
          )
          .clickable(onClick = onClick)
          .padding(vertical = 7.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Box(
        modifier =
          Modifier
            .padding(top = 1.dp)
            .width(3.dp)
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
      )
      Spacer(Modifier.width(5.dp))
      Text(
        text = "#$index",
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 34.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = cue.timeLabel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = cue.text,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 1.dp),
        )
      }
    }
    HorizontalDivider(
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
    )
  }
}

/** 在 IO 线程读盘 + 解析；所有失败都收敛成一句用户能懂的话。 */
private fun loadCues(
  context: Context,
  raw: String,
): CueLoad {
  val file =
    resolveSubtitleFile(context, raw)
      ?: return CueLoad.Failed("找不到这条字幕的文件（可能已被清理），回字幕面板重新挂载一次")
  val parsed = runCatching { SubtitleTimeline.parseFileDetailed(file) }.getOrNull()
  if (parsed == null) return CueLoad.Failed("字幕文件读取失败，可能已被清理或没有读取权限")
  if (parsed.cues.isEmpty()) return CueLoad.Failed("这个字幕里没有可读的台词（可能是图像字幕，或格式无法解析）")
  return CueLoad.Ready(parsed.cues, parsed.truncated)
}

/**
 * mpv 的 `external-filename` → 可读的 [File]。
 *
 * 三种形态都要认：裸路径（绝大多数）、`file://`、以及 `content://`
 * （用户从 SAF 选的字幕，某些内核会原样交给 mpv）。
 * 解析器按扩展名挑语法，所以 content:// 落到缓存时保留原扩展名。
 */
private fun resolveSubtitleFile(
  context: Context,
  raw: String,
): File? {
  val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw).substringBefore('\u0000')
  val path =
    when {
      decoded.startsWith("content://") -> materialize(context, Uri.parse(decoded))
      decoded.startsWith("file://") -> Uri.parse(decoded).path
      else -> decoded
    } ?: return null
  return File(path).takeIf { it.isFile && it.length() > 0 }
}

/** 把 content:// 抄到私有缓存里，供解析器按普通文件读。 */
private fun materialize(
  context: Context,
  uri: Uri,
): String? =
  runCatching {
    val ext =
      uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
        ?: "srt"
    val target = File(context.cacheDir, "subtitle_timeline_${uri.toString().hashCode()}.$ext")
    if (!target.exists() || target.length() == 0L) {
      context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
    }
    target.takeIf { it.isFile && it.length() > 0 }?.absolutePath
  }.getOrNull()
