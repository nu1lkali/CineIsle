package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

sealed class SubtitleItem {
  data class Track(val node: TrackNode) : SubtitleItem()
  data class Header(val title: String) : SubtitleItem()
  /** 筛选后一条都不剩时的提示行（跟着列表一起滚，不占固定高度） */
  data class EmptyHint(val query: String) : SubtitleItem()
  object Divider : SubtitleItem()
}

/**
 * 常见语言码 → 中文别名。
 *
 * 只参与**筛选匹配**，不参与显示：轨道标题里是 mpv 给的原始语言码（chi / eng …），
 * 用户手打「中文」时若只比字面会对不上，于是「明明有中文字幕却筛不出来」。
 */
private val LANG_ALIASES: Map<String, String> = mapOf(
  "chi" to "中文", "zho" to "中文", "zh" to "中文", "cmn" to "中文",
  "eng" to "英语", "en" to "英语",
  "jpn" to "日语", "ja" to "日语",
  "kor" to "韩语", "ko" to "韩语",
  "fre" to "法语", "fra" to "法语", "fr" to "法语",
  "ger" to "德语", "deu" to "德语", "de" to "德语",
  "spa" to "西班牙语", "es" to "西班牙语",
  "rus" to "俄语", "ru" to "俄语",
  "por" to "葡萄牙语", "pt" to "葡萄牙语",
  "ita" to "意大利语", "it" to "意大利语",
  "tha" to "泰语", "th" to "泰语",
  "ara" to "阿拉伯语", "ar" to "阿拉伯语",
  "hin" to "印地语", "hi" to "印地语",
  "vie" to "越南语", "vi" to "越南语",
)

/**
 * 一条轨道的可搜索文本：标题 / 语言码 / 语言中文别名 / 外挂文件名 / 轨号 / 内嵌或外挂。
 *
 * 拼成一段再按空格切词比对，于是「外挂 中」这种多词组合也能命中。
 */
private fun TrackNode.searchHaystack(): String {
  val langCode = lang.orEmpty()
  val alias = LANG_ALIASES[langCode.lowercase()].orEmpty()
  val fileName = externalFilename
    ?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
    ?.substringAfterLast('/')
    .orEmpty()
  val kind = if (external == true) "外挂 external" else "内嵌 embedded"
  return "$title $langCode $alias $fileName #$id $kind".lowercase()
}

@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  onAddSubtitle: () -> Unit,
  /**
   * 打开「在线字幕搜索」（Emby 字幕源）。默认关闭 —— 非 Emby 播放（本地文件 /
   * 网盘直链）没有 itemId 和服务端，在线搜索对它无意义，传 null 就自动隐藏。
   */
  onSearchOnline: (() -> Unit)? = null,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier
) {
  // ── 本地筛选 ──
  // 只在**眼前这份轨道列表**里按名字 / 语言 / 文件名过滤，一个请求都不发 ——
  // 与顶栏的「在线字幕」（去服务端搜并入库）是两条完全不同的路。
  // 关掉面板即丢弃：这是「这一次找轨道」的临时口径，不跨次记忆。
  var filter by remember { mutableStateOf("") }
  val tokens = remember(filter) {
    filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
  }

  // 自己持列表状态：筛选一变就回到顶部。
  // 否则上一屏停在「第 8 条」，筛完只剩 2 条时视口停在一片空白处，
  // 看起来像「筛出个空列表」。
  val listState = rememberLazyListState()
  LaunchedEffect(tokens) {
    if (tokens.isNotEmpty()) listState.scrollToItem(0)
  }

  val items = remember(tracks, tokens) {
    val matched =
      if (tokens.isEmpty()) tracks
      else tracks.filter { track -> tokens.all { it in track.searchHaystack() } }

    val list = mutableListOf<SubtitleItem>()

    // Internal/Local tracks section
    val internal = matched.filter { it.external != true }
    val external = matched.filter { it.external == true }

    if (internal.isNotEmpty() || external.isNotEmpty()) {
        list.add(SubtitleItem.Header(if (internal.isNotEmpty()) "内嵌字幕" else "本地字幕"))
        list.addAll(internal.map { SubtitleItem.Track(it) })
        if (internal.isNotEmpty() && external.isNotEmpty()) {
          list.add(SubtitleItem.Header("外挂字幕"))
        }
        list.addAll(external.map { SubtitleItem.Track(it) })
    }

    // 筛掉了全部：给一句人话，而不是留一片空白（此时「添加外挂字幕」那行还在，
    // 用户仍有出路，所以只在列表里提示，不弹别的东西）
    if (matched.isEmpty() && tokens.isNotEmpty()) {
      list.add(SubtitleItem.EmptyHint(filter.trim()))
    }

    list.toImmutableList()
  }

  GenericTracksSheet(
    tracks = items,
    onDismissRequest = onDismissRequest,
    lazyListState = listState,
    header = {
      AddTrackRow(
        stringResource(R.string.player_sheets_add_ext_sub),
        onAddSubtitle,
        actions = {
          // 在线搜索放在 actions 里而不是单独一行：这一行右侧已经有
          // 「调色板 / 延迟」两个图标，再塞一个文字按钮会被 Row 压扁
          //（中文按钮排不下的经典原因，见项目红线 9）。
          if (onSearchOnline != null) {
            IconButton(onClick = onSearchOnline) {
              Icon(Icons.Default.CloudDownload, contentDescription = "在线字幕")
            }
          }
          IconButton(onClick = onOpenSubtitleSettings) {
            Icon(Icons.Default.Palette, null)
          }
          IconButton(onClick = onOpenSubtitleDelay) {
            Icon(Icons.Default.MoreTime, null)
          }
        },
      )
      // 没有任何轨道时不出这一行：那时唯一要做的事是「添加字幕」，摆个搜索框纯属占地方。
      // 放在 header 里 ⇒ 它是**钉住**的，列表滚动时不会跟着走。
      if (tracks.isNotEmpty()) {
        SubtitleFilterField(query = filter, onQueryChange = { filter = it })
      }
    },
    track = { item ->
      when (item) {
        is SubtitleItem.Track -> {
          val track = item.node
          SubtitleTrackRow(
            title = getTrackTitle(track),
            isSelected = isSubtitleSelected(track.id),
            isExternal = track.external == true,
            onToggle = { onToggleSubtitle(track.id) },
            onRemove = { onRemoveSubtitle(track.id) },
          )
        }
        is SubtitleItem.Header -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        is SubtitleItem.EmptyHint -> {
            Text(
              text = stringResource(R.string.player_sheets_subtitle_filter_empty, item.query),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.large),
            )
        }
        SubtitleItem.Divider -> {
            HorizontalDivider(
              modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
              color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
      }
    },
    footer = {
      Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
    },
    modifier = modifier,
  )
}

/**
 * 字幕轨道的本地筛选输入框。
 *
 * 单行、贴列表、即时生效（没有防抖也没有提交动作）：数据本来就在内存里，
 * 每次敲键重算一趟十几个字符串的成本可以忽略。
 */
@Composable
private fun SubtitleFilterField(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = modifier
      .fillMaxWidth()
      .padding(start = MaterialTheme.spacing.medium, end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.extraSmall),
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyMedium,
    placeholder = {
      Text(
        text = stringResource(R.string.player_sheets_subtitle_filter_hint),
        style = MaterialTheme.typography.bodyMedium,
      )
    },
    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }) {
          Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.player_sheets_subtitle_filter_clear),
          )
        }
      }
    },
  )
}

@Composable
fun SubtitleTrackRow(
  title: String,
  isSelected: Boolean,
  isExternal: Boolean,
  onToggle: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    if (isExternal) {
      IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = null) }
    }
  }
}
