package app.marlboroadvance.mpvex.ui.browser.emby.subtitle

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSubtitle
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonLines
import kotlinx.coroutines.launch

/**
 * 可选语言。**三字母 ISO 码**是 Emby 硬要求的（进 path 段，没有多语言端点）。
 *
 * 默认只勾「简体 + 英文」：这是国内用户绝大多数情况。多勾每勾一个就多一次串行请求。
 */
private val LANGUAGE_OPTIONS = listOf(
  "chi" to "简体中文",
  "eng" to "English",
  "jpn" to "日本語",
  "kor" to "한국어",
  "zht" to "繁體中文",
  "spa" to "Español",
  "fre" to "Français",
  "ger" to "Deutsch",
  "rus" to "Русский",
  "ara" to "العربية",
)

/**
 * 在线字幕搜索 BottomSheet。
 *
 * 流程：选语言（可多选） → 搜索 → 从候选里点一条 → 自动下载 + 落盘 + 挂载。
 *
 * 设计上刻意**不自动搜索**：一进 sheet 就发请求的话，用户只是想看看有没有字幕
 * 也会触发一轮外部 provider 调用（慢且可能计费）。让用户先选好语言再点搜索。
 *
 * @param mediaSourceId 当前播放的媒体源 Id。搜在线字幕**必须**传对 ——
 *   多版本媒体（4K/1080P 各带不同字幕）时传错就搜到别的版本的字幕。
 * @param mediaTitle 媒体标题（含扩展名），用于落盘目录命名，口径必须与
 *   `PlayerViewModel.scanLocalSubtitles` 一致，否则下次开播扫不到。
 * @param onDownloaded 拿到落盘后的字幕 Uri 时回调（用于立刻挂载到播放器）。
 * @param onPickLocal 打开本地字幕文件选择器（与在线搜索并排，用户想自己挑时用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchSheet(
  server: EmbyServer?,
  itemId: String?,
  mediaSourceId: String?,
  mediaTitle: String,
  onDownloaded: (android.net.Uri) -> Unit,
  onPickLocal: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val vm: SubtitleSearchViewModel = viewModel(
    factory = SubtitleSearchViewModel.factory(context.applicationContext as Application),
  )
  val searching by vm.searching.collectAsState()
  val candidates by vm.candidates.collectAsState()
  val error by vm.error.collectAsState()
  val toast by vm.toast.collectAsState()
  val saveFolderConfigured by vm.saveFolderConfigured.collectAsState()

  // 用 Set 而不是单个语言码：Emby 没有多语言端点，勾两个就是两次串行请求
  var selectedLanguages by rememberSaveable {
    mutableStateOf(setOf("chi", "eng"))
  }
  var perfectMatchOnly by rememberSaveable { mutableStateOf(false) }
  var searched by rememberSaveable { mutableStateOf(false) }
  /** 正在下载的那条（防连点：一次只允许一条在飞） */
  var downloadingId by rememberSaveable { mutableStateOf<String?>(null) }

  val currentServer = server
  val currentItemId = itemId

  fun doSearch() {
    val s = currentServer ?: return
    val id = currentItemId ?: return
    searched = true
    scope.launch {
      vm.search(
        server = s,
        itemId = id,
        mediaSourceId = mediaSourceId,
        languages = selectedLanguages.toList(),
        perfectMatchOnly = perfectMatchOnly,
      )
    }
  }

  // 参数变了（换片/换语言）就把上次的搜索结果作废 —— 留着旧候选只会误导
  LaunchedEffect(currentItemId, currentServer?.id, selectedLanguages, perfectMatchOnly) {
    if (searched) {
      vm.reset()
      searched = false
    }
  }

  LaunchedEffect(toast) {
    val message = toast ?: return@LaunchedEffect
    // toast 由下载成功后设置；这里不弹 Snackbar（sheet 里也没 snackbar host），
    // 成功与否通过列表上的状态行表达
    vm.consumeToast()
  }

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
      Text(
        text = "在线字幕",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )

      // ── 语言选择 ──
      Spacer(Modifier.height(8.dp))
      Text(
        text = "语言（可多选）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // 用 FlowRow 而不是 Row：语言标签有 10 个，Row 会把非 weight 子项压扁
      // （中文按钮排不下的经典原因，见项目红线 9）
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 8.dp),
      ) {
        LANGUAGE_OPTIONS.forEach { (code, label) ->
          val selected = code in selectedLanguages
          FilterChip(
            selected = selected,
            onClick = {
              selectedLanguages = if (selected) {
                selectedLanguages - code
              } else {
                selectedLanguages + code
              }
            },
            label = { Text(label) },
            colors = FilterChipDefaults.filterChipColors(),
          )
        }
      }

      // ── 只看精确匹配 ──
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("只看精确匹配", style = MaterialTheme.typography.bodyMedium)
          Text(
            text = "结果通常只剩几条，但都是按文件名哈希对上本片的",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = perfectMatchOnly, onCheckedChange = { perfectMatchOnly = it })
      }

      Spacer(Modifier.height(8.dp))

      // ── 操作区 ──
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(onClick = onPickLocal, modifier = Modifier.weight(1f)) {
          Text("本地文件…")
        }
        androidx.compose.material3.Button(
          onClick = { doSearch() },
          enabled = currentServer != null && currentItemId != null &&
            selectedLanguages.isNotEmpty() && !searching,
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.size(6.dp))
          Text(if (searching) "搜索中…" else "搜索")
        }
      }

      if (!saveFolderConfigured) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "⚠ 还没设置字幕保存目录，下载后无处存放。请到「设置 → 字幕」里选一个目录。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      error?.let {
        Spacer(Modifier.height(8.dp))
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Spacer(Modifier.height(8.dp))

      // ── 结果 ──
      when {
        // 面板高度有限，用文字占位条而不是骨架卡：这里预期落下来的是一列文件名，
        // 摆网格海报反而会让人以为要点卡片。
        searching && candidates.isEmpty() -> EmbySkeletonLines(
          lineCount = 3,
          modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 4.dp),
        )

        !searched -> Hint("选好语言后点「搜索」")

        candidates.isEmpty() -> Hint(
          if (perfectMatchOnly) "没有精确匹配的字幕，关掉开关看看其他结果" else "没找到字幕",
        )

        else -> LazyColumn(
          state = rememberLazyListState(),
          modifier = Modifier.heightIn(max = 360.dp),
        ) {
          // 改语言重搜时candidates 里还压着此前的旧结果，如果什么都不加，
          // 用户会以为搜索已经结束、结果就是这些 —— 完全没有加载反馈。
          // 所以在列表顶部挂一条「正在搜索」，旧结果照常显示（比清空更平滑）。
          if (searching) {
            item(key = "__searching__") {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(14.dp),
                  strokeWidth = 2.dp,
                )
                Text(
                  text = "正在搜索…",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          items(candidates, key = { it.Id ?: it.Name ?: it.hashCode().toString() }) { subtitle ->
            SubtitleCandidateRow(
              subtitle = subtitle,
              downloading = downloadingId == subtitle.Id,
              enabled = downloadingId == null && currentServer != null,
              onClick = {
                val s = currentServer
                val id = currentItemId
                val sid = subtitle.Id
                // 缺任何一项都点了也白点（没有可下载的句柄）。用 if 而不是
                // `return@items`：items 的 itemContent 里再套了 composable lambda，
                // 那个标签在这里解析不到，直接编译报错。
                if (s != null && id != null && sid != null) {
                  downloadingId = sid
                  scope.launch {
                    val uri = vm.searchAndDownload(
                      server = s,
                      itemId = id,
                      mediaSourceId = mediaSourceId,
                      mediaTitle = mediaTitle,
                      language = subtitle.Language ?: selectedLanguages.first(),
                      subtitle = subtitle,
                      perfectMatchOnly = perfectMatchOnly,
                    )
                    downloadingId = null
                    if (uri != null) {
                      onDownloaded(uri)
                      // 下载完不自动关 sheet：用户可能想再看看有没有更好的版本
                    }
                  }
                }
              },
            )
          }
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun Hint(text: String) {
  Box(
    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 16.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 一条字幕候选。
 *
 * 左侧标题用 [EmbyRemoteSubtitle.Name]（提供方给的名字，通常含完整文件名），
 * 而不是「中文」这种没信息量的标签 —— 用户是靠文件名里的**版本号**
 * （2019 公映版 vs 2020 加长版）来区分同名字幕的。
 */
@Composable
private fun SubtitleCandidateRow(
  subtitle: EmbyRemoteSubtitle,
  downloading: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 10.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = subtitle.Name ?: "未命名字幕",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 精确匹配 → 绿勾，这是最能帮用户决策的信号，所以图标先于评分显示
        if (subtitle.IsHashMatch) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "精确匹配",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = "精确匹配",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        if (subtitle.IsHearingImpaired) {
          Text(
            text = "听障",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (subtitle.IsForced) {
          Text(
            text = "强制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        val rating = subtitle.CommunityRating ?: 0f
        if (rating > 0f) {
          Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = String.format("%.1f", rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        subtitle.DownloadCount?.takeIf { it > 0 }?.let { count ->
          Text(
            text = "↓$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        subtitle.ProviderName?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    if (downloading) {
      CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
  }
}