package app.marlboroadvance.mpvex.ui.player.danmaku

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.launch

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
  val blockWords: String = "",
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
  onBlockWordsChange: (String) -> Unit,
  /** 手动搜番；返回命中列表由本面板展示。 */
  onSearch: suspend (keyword: String) -> List<DandanAnime>,
  /** 选定某个搜索结果后加载它的弹幕。 */
  onPickAnime: suspend (anime: DandanAnime) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var keyword by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<DandanAnime>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }

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
        onCheckedChange = onEnabledChange,
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

        ToggleRow(
          title = stringResource(R.string.danmaku_auto_match),
          summary = stringResource(R.string.danmaku_auto_match_summary),
          checked = state.autoMatch,
          onCheckedChange = onAutoMatchChange,
        )

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
          val episodes = anime.episodeCount ?: anime.episodes.size
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
                  if (episodes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.danmaku_episode_count, episodes))
                  }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            TextButton(onClick = { scope.launch { runCatching { onPickAnime(anime) } } }) {
              Text(stringResource(R.string.danmaku_load))
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