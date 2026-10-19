package app.marlboroadvance.mpvex.ui.player.danmaku

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuMatcher
import app.marlboroadvance.mpvex.domain.danmaku.DandanAnime
import app.marlboroadvance.mpvex.domain.danmaku.DandanEpisode
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 弹幕面板的状态：由宿主（PlayerViewModel）持有并回传。 */
data class DanmakuSheetState(
  val enabled: Boolean = false,
  val host: String = "",
  val appId: String = "",
  val appSecret: String = "",
  val autoMatch: Boolean = true,
  val fontScale: Float = 1f,
  val speedScale: Float = 1f,
  val alphaPercent: Int = 100,
  val areaRatio: Float = 1f,
  val densityKey: String = "normal",
  /** 防重叠：同轨道会撞车时自动换轨道。 */
  val preventOverlapping: Boolean = true,
  val blockWords: String = "",
  /** 时间轴偏移（毫秒，正数 = 弹幕延后）。**当前这部剧**生效的值。 */
  val timeOffsetMs: Int = 0,
  /** 这个偏移是本剧单独记住的（而不是跟随全局默认）。 */
  val seriesOffsetApplied: Boolean = false,
  /** 手动选过的番是否跨集沿用。 */
  val rememberSelection: Boolean = true,
  /** 合并关联剧集弹幕（提高密度；关掉则只取当前这一条时间轴）。 */
  val withRelated: Boolean = true,
  /** 已命中的集名；null = 还没匹配。 */
  val matchedTitle: String? = null,
  val commentCount: Int = 0,
  val loading: Boolean = false,
  /** 搜索/匹配失败的提示；成功时为 null。 */
  val error: String? = null,
)

/**
 * 弹幕面板。
 *
 * 面板本身**不持有业务状态**：所有开关直接写偏好、立刻生效；
 * 只有「搜索结果」这种一次性的中间态留在本地 `remember` 里 ——
 * 否则「关掉面板再打开」会看到上一次搜到的一半结果，很困惑。
 */
@Composable
fun DanmakuSheet(
  state: DanmakuSheetState,
  onEnabledChange: (Boolean) -> Unit,
  onHostChange: (String) -> Unit,
  onAppIdChange: (String) -> Unit,
  onAppSecretChange: (String) -> Unit,
  onAutoMatchChange: (Boolean) -> Unit,
  onFontScaleChange: (Float) -> Unit,
  onSpeedScaleChange: (Float) -> Unit,
  onAlphaChange: (Int) -> Unit,
  onAreaChange: (Float) -> Unit,
  onDensityChange: (String) -> Unit,
  /** 防重叠开关（立刻生效，不重建引擎）。 */
  onPreventOverlappingChange: (Boolean) -> Unit = {},
  onBlockWordsChange: (String) -> Unit,
  onTimeOffsetChange: (Int) -> Unit,
  /** 清掉本剧的偏移覆盖（回落到全局默认）。 */
  onClearTimeOffset: () -> Unit,
  onRememberSelectionChange: (Boolean) -> Unit,
  /** 切换「合并关联剧集弹幕」（改了会重新拉一次当前集）。 */
  onWithRelatedChange: (Boolean) -> Unit = {},
  /** 手动搜番；返回命中列表由本面板展示。 */
  onSearch: suspend (keyword: String) -> List<DandanAnime>,
  /** 选定某个搜索结果后加载它的弹幕（自动按当前集号挑）。 */
  onPickAnime: suspend (anime: DandanAnime) -> Unit,
  /** 展开某部番的分集列表（官方搜索端点不带分集，要单独取一次）。 */
  onLoadEpisodes: suspend (anime: DandanAnime) -> List<DandanEpisode>,
  /** 用户明确指定了某一集 ⇒ 直接用它，不再按集号猜。 */
  onPickEpisode: suspend (anime: DandanAnime, episode: DandanEpisode) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var keyword by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<DandanAnime>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  // 正在展开分集的番（null = 没展开）。与「搜索结果」一样都是一次性中间态。
  var episodesFor by remember { mutableStateOf<DandanAnime?>(null) }
  var episodes by remember { mutableStateOf<List<DandanEpisode>>(emptyList()) }
  var loadingEpisodes by remember { mutableStateOf(false) }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    Column(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
      ToggleRow(
        title = stringResource(R.string.danmaku_title),
        summary = state.matchedTitle?.let {
          stringResource(R.string.danmaku_matched_count, it, state.commentCount)
        } ?: stringResource(R.string.danmaku_not_matched),
        checked = state.enabled,
        onCheckedChange = {
          // 点击回执：排查「开关点了没反应」时先看这一行有没有打出来 ——
          // 没打 = 手势没落到开关上（那是布局问题）；打了 = 状态机没生效（往 setEnabled 里查）。
          Log.i("DanmakuSheet", "弹幕总开关点击：${state.enabled} → $it")
          onEnabledChange(it)
        },
      )

      if (!state.enabled) {
        // 关着的时候不列一堆用不上的参数 —— 但要说明白「打开后能干什么」。
        Text(
          text = stringResource(R.string.danmaku_off_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
        )
      } else {
        // ⚠️ 正在拉弹幕时必须给反馈：开关刚打开时没有任何弹幕，
        //    画面上看起来和「没打开」一模一样 —— 用户会以为开关是坏的、点不动。
        if (state.loading) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.smaller),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
            )
            Text(
              text = stringResource(R.string.danmaku_matching),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        LabeledSlider(
          label = stringResource(R.string.danmaku_font_size),
          value = state.fontScale,
          valueText = "${(state.fontScale * 100).toInt()}%",
          range = 0.5f..2f,
          onValueChange = onFontScaleChange,
        )
        LabeledSlider(
          label = stringResource(R.string.danmaku_speed),
          value = state.speedScale,
          valueText = "${(state.speedScale * 100).toInt()}%",
          range = 0.5f..2f,
          onValueChange = onSpeedScaleChange,
        )
        LabeledSlider(
          label = stringResource(R.string.danmaku_opacity),
          value = state.alphaPercent.toFloat(),
          valueText = "${state.alphaPercent}%",
          range = 10f..100f,
          steps = 8,
          onValueChange = { onAlphaChange(it.toInt()) },
        )
        LabeledSlider(
          label = stringResource(R.string.danmaku_area),
          value = state.areaRatio,
          valueText = "${(state.areaRatio * 100).toInt()}%",
          range = 0.25f..1f,
          steps = 2,
          onValueChange = onAreaChange,
        )

        Text(
          text = stringResource(R.string.danmaku_density),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(top = MaterialTheme.spacing.small),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller)) {
          listOf(
            "sparse" to R.string.danmaku_density_sparse,
            "normal" to R.string.danmaku_density_normal,
            "dense" to R.string.danmaku_density_dense,
          ).forEach { (key, labelRes) ->
            FilterChip(
              selected = state.densityKey == key,
              onClick = { onDensityChange(key) },
              label = { Text(stringResource(labelRes)) },
            )
          }
        }

        OutlinedTextField(
          value = state.blockWords,
          onValueChange = onBlockWordsChange,
          label = { Text(stringResource(R.string.danmaku_block_words)) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.small),
        )

        // 防重叠放在密度下面：它和密度是同一类诉求（画面怎么排），
        // 但语义相反 —— 密度想要更多、防重叠宁可少也不叠。
        ToggleRow(
          title = stringResource(R.string.danmaku_prevent_overlapping),
          summary = stringResource(R.string.danmaku_prevent_overlapping_summary),
          checked = state.preventOverlapping,
          onCheckedChange = onPreventOverlappingChange,
        )

        ToggleRow(
          title = stringResource(R.string.danmaku_auto_match),
          summary = stringResource(R.string.danmaku_auto_match_summary),
          checked = state.autoMatch,
          onCheckedChange = onAutoMatchChange,
        )

        ToggleRow(
          title = stringResource(R.string.danmaku_remember_selection),
          summary = stringResource(R.string.danmaku_remember_selection_summary),
          checked = state.rememberSelection,
          onCheckedChange = onRememberSelectionChange,
        )

        ToggleRow(
          title = stringResource(R.string.danmaku_with_related),
          summary = stringResource(R.string.danmaku_with_related_summary),
          checked = state.withRelated,
          onCheckedChange = onWithRelatedChange,
        )

        // ── 时间轴偏移 ──
        // 正数 = 弹幕延后出现。步长 100ms：人眼看着「差半秒」就明显了，
        // 再细的步长纯属骚扰。±5 秒覆盖绝大多数版本差（多一段片头 / 少一段回顾）。
        LabeledSlider(
          label = stringResource(R.string.danmaku_time_offset),
          value = state.timeOffsetMs.toFloat(),
          valueText = formatOffset(state.timeOffsetMs),
          range = -5000f..5000f,
          steps = 49,
          onValueChange = { onTimeOffsetChange((it / 100f).roundToInt() * 100) },
        )
        Text(
          text = stringResource(R.string.danmaku_time_offset_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = MaterialTheme.spacing.smaller),
        )
        // 偏移是**按剧**记的。给出「本剧已记住 + 可清除」的反馈，
        // 否则用户会以为这是个全局设置，换片后看到数值不变反而困惑。
        if (state.seriesOffsetApplied) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = stringResource(R.string.danmaku_time_offset_saved),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearTimeOffset) {
              Text(stringResource(R.string.danmaku_time_offset_clear))
            }
          }
        }

        // ── 弹幕源 ──
        // ⚠️ 放在「已打开」这一侧的原因：默认 host 已经能用了，
        //   凭证和自建 host 属于「兜底/进阶」，不该占掉关着时的空间。
        OutlinedTextField(
          value = state.host,
          onValueChange = onHostChange,
          label = { Text(stringResource(R.string.danmaku_host)) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.small),
        )
        Text(
          text = stringResource(R.string.danmaku_credential_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = MaterialTheme.spacing.smaller),
        )
        OutlinedTextField(
          value = state.appId,
          onValueChange = onAppIdChange,
          label = { Text(stringResource(R.string.danmaku_app_id)) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.smaller),
        )
        OutlinedTextField(
          value = state.appSecret,
          onValueChange = onAppSecretChange,
          label = { Text(stringResource(R.string.danmaku_app_secret)) },
          singleLine = true,
          // ⚠️ 密钥不能明文显示 —— 别人凑过来瞄一眼屏幕就能抄走。
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.smaller),
        )
      }

      // ── 手动搜番 ──
      Text(
        text = stringResource(R.string.danmaku_manual_search),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = keyword,
          onValueChange = { keyword = it },
          label = { Text(stringResource(R.string.danmaku_search_hint)) },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.smaller))
        IconButton(
          onClick = {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@IconButton
            searching = true
            scope.launch {
              results = runCatching { onSearch(kw) }.getOrDefault(emptyList())
              searching = false
            }
          },
          enabled = !searching && keyword.isNotBlank(),
        ) {
          if (searching) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.danmaku_search_action))
          }
        }
      }

      if (results.isNotEmpty()) {
        results.forEach { anime ->
          val episodes2 = anime.episodeCount ?: anime.episodes.size
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .clickable {
                scope.launch { runCatching { onPickAnime(anime) } }
              }
              .padding(vertical = MaterialTheme.spacing.smaller),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = anime.animeTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = buildString {
                  append(anime.typeDescription ?: anime.type ?: "")
                  if (episodes2 > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.danmaku_episode_count, episodes2))
                  }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // 「选集」：自动匹配把集选错了时（跨季番最常见）的唯一出路。
            // 不把它做成默认点击路径 —— 大多数情况自动挑的那一集就是对的。
            TextButton(
              enabled = !loadingEpisodes,
              onClick = {
                if (episodesFor?.animeId == anime.animeId) {
                  episodesFor = null
                  episodes = emptyList()
                } else {
                  episodesFor = anime
                  loadingEpisodes = true
                  scope.launch {
                    episodes = runCatching { onLoadEpisodes(anime) }.getOrDefault(emptyList())
                    loadingEpisodes = false
                  }
                }
              },
            ) {
              Text(stringResource(R.string.danmaku_pick_episode))
            }
            TextButton(onClick = { scope.launch { runCatching { onPickAnime(anime) } } }) {
              Text(stringResource(R.string.danmaku_load))
            }
          }

          // 展开的分集列表：网格排列，按播放顺序（库里通常就是按集号排的）。
          if (episodesFor?.animeId == anime.animeId) {
            if (loadingEpisodes) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(
                  text = stringResource(R.string.danmaku_select_episode),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else if (episodes.isEmpty()) {
              Text(
                text = stringResource(R.string.danmaku_select_episode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
              )
            } else {
              // 每行 5 个：集号是 1~3 位数，5 列在手机竖屏上刚好不挤。
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
              ) {
                episodes.forEachIndexed { i, ep ->
                  val label = ep.episodeNumber?.trim()?.takeIf { it.isNotEmpty() }
                    ?: (i + 1).toString()
                  FilterChip(
                    selected = state.matchedTitle == ep.episodeTitle.ifBlank { anime.animeTitle },
                    onClick = {
                      scope.launch {
                        runCatching { onPickEpisode(anime, ep) }
                        episodesFor = null
                        episodes = emptyList()
                      }
                    },
                    label = { Text(label) },
                  )
                }
              }
            }
          }
        }
      }

      state.error?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = MaterialTheme.spacing.small),
        )
      }

      Spacer(Modifier.height(MaterialTheme.spacing.large))
    }
  }
}

@Composable
private fun ToggleRow(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable { onCheckedChange(!checked) }
      .padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.smaller),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Outlined.Subtitles,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
    Spacer(Modifier.width(MaterialTheme.spacing.small))
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

/**
 * 偏移量的读数文案：`+0.6s` / `-1.2s` / `0`。
 *
 * 只在 0 时显示「0」而不是「+0.0s」—— 用户看不出差别，但多出来的符号会让人
 * 以为「明明没调却有偏移」。
 */
private fun formatOffset(ms: Int): String {
  if (ms == 0) return "0"
  val sign = if (ms > 0) "+" else "-"
  val abs = kotlin.math.abs(ms)
  val sec = abs / 1000
  val tenth = (abs % 1000) / 100
  return if (tenth == 0) "$sign${sec}.0s" else "$sign$sec.${tenth}s"
}

/**
 * 带数值读数的滑杆。
 *
 * 读数放行尾而不是只靠滑块位置 —— 用户拖的时候看不到精确值，
 * 回头又不知道刚才调到了多少。
 */
@Composable
private fun LabeledSlider(
  label: String,
  value: Float,
  valueText: String,
  range: ClosedFloatingPointRange<Float>,
  steps: Int = 0,
  onValueChange: (Float) -> Unit,
) {
  Column(modifier = Modifier.padding(top = MaterialTheme.spacing.small)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(
        text = valueText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
    )
  }
}