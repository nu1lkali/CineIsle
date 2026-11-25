package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.isPhoto
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SearchCapability
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SearchHit
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SearchUnavailableReason
import app.marlboroadvance.mpvex.ui.browser.states.BrowserSkeletonRows
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils

/**
 * 搜索框下方的「服务端全库搜索」入口 / 说明行。
 *
 * 只在搜索框展开时出现，且**完全不碰本地过滤链**：
 * - [SearchCapability.Available] + 词非空 ⇒ 可点，点进去才发网络请求；
 * - [SearchCapability.Unavailable] ⇒ 一行次要色说明，让「为什么没有全库入口」有答案；
 * - [SearchCapability.Unknown] / 词为空 ⇒ 不渲染，界面与改造前逐像素一致。
 *
 * @param localMatchCount 本地过滤后的命中数，仅用于「当前目录没匹配」时的副标题措辞
 */
@Composable
fun ServerSearchEntryRow(
  capability: SearchCapability,
  keyword: String,
  localMatchCount: Int,
  onEnter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (capability) {
    SearchCapability.Available -> {
      if (keyword.isNotBlank()) {
        Surface(
          modifier =
            modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp)
              .padding(bottom = 6.dp),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable(onClick = onEnter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "在服务器所有目录中搜索「$keyword」",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text =
                  if (localMatchCount == 0) "当前目录没有匹配，试试跨目录检索"
                  else "跨目录检索服务端索引",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primary,
            ) {
              Text(
                text = "全库",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }

    is SearchCapability.Unavailable -> {
      Row(
        modifier =
          modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.Info,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = searchUnavailableText(capability.reason),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SearchCapability.Unknown -> Unit
  }
}

private fun searchUnavailableText(reason: SearchUnavailableReason): String =
  when (reason) {
    SearchUnavailableReason.INDEX_DISABLED -> "服务器未开启全局搜索，仅可搜索当前目录"
    SearchUnavailableReason.UNAUTHORIZED -> "当前账号无检索权限，仅可搜索当前目录"
    SearchUnavailableReason.SERVER_ERROR -> "服务器搜索暂不可用，仅可搜索当前目录"
    SearchUnavailableReason.TIMEOUT -> "服务器搜索超时，仅可搜索当前目录"
  }

/**
 * 全库搜索结果区：Loading / Empty / Error / Content 四态。
 *
 * ⚠️ 这是一个**独立分支**，不是把结果塞进既有网格 / 列表视图：
 *    - 结果跨目录、没有封面签名，复用网格卡片会把懒加载 / 长按 / 多选那套逻辑全带进来；
 *    - P0 明确不给长按写操作入口，独立实现最干净，也不会让常规浏览有任何回归。
 *
 * 分组折叠态用 `SnapshotStateMap` 按 [SearchGroup.parentLabel] 记：续页追加后集合不变，
 * 已折叠的组保持折叠、新组默认展开。折叠布尔量作为参数传给 [SearchGroupHeader]，
 * 不靠 item 内容里捕获的 lambda 去读状态 —— LazyColumn 复用会 skip 掉那种读法。
 */
@Composable
fun ServerSearchResults(
  state: ServerSearchUiState,
  onOpenHit: (SearchHit) -> Unit,
  onLoadMore: () -> Unit,
  onRetry: () -> Unit,
  onFallbackLocal: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (state) {
    ServerSearchUiState.Idle -> Box(modifier = modifier.fillMaxSize())

    is ServerSearchUiState.Loading -> {
      BrowserSkeletonRows(
        itemCount = 8,
        thumbWidth = 48.dp,
        thumbRatio = 1f,
        modifier = modifier,
      )
    }

    is ServerSearchUiState.Empty -> {
      ServerSearchMessage(
        icon = Icons.Filled.Search,
        title = "没有找到匹配内容",
        message = "服务器所有目录中没有「${state.keyword}」，可换个关键词或只在当前目录搜索",
        primaryAction = null,
        onPrimary = null,
        onFallbackLocal = onFallbackLocal,
        modifier = modifier,
      )
    }

    is ServerSearchUiState.Error -> {
      ServerSearchMessage(
        icon = Icons.Filled.Info,
        title = "搜索失败",
        message = state.message,
        primaryAction = "重试",
        onPrimary = onRetry,
        onFallbackLocal = onFallbackLocal,
        modifier = modifier,
      )
    }

    is ServerSearchUiState.Content -> {
      val collapsed = remember { mutableStateMapOf<String, Boolean>() }
      // 分组是 hits 的纯函数视图：换词 / 续页都会得到一份新的 hits，重新分组即可，
      // 不与分页耦合。保持首次出现顺序（见 groupSearchHits 的 KDoc），画面不跳。
      val groups = remember(state.hits) { groupSearchHits(state.hits) }

      LazyColumn(modifier = modifier.fillMaxSize()) {
        groups.forEach { group ->
          val expanded = collapsed[group.parentLabel] != true
          item(key = "header:${group.parentLabel}") {
            SearchGroupHeader(
              label = group.parentLabel,
              count = group.hits.size,
              expanded = expanded,
              onToggle = { collapsed[group.parentLabel] = expanded },
            )
          }
          if (expanded) {
            itemsIndexed(
              items = group.hits,
              key = { index, hit -> "hit:${group.parentLabel}:$index:${hit.file.path}" },
            ) { _, hit ->
              SearchHitRow(hit = hit, onClick = { onOpenHit(hit) })
            }
          }
        }
        item(key = "footer") {
          SearchResultFooter(state = state, onLoadMore = onLoadMore)
        }
      }
    }
  }
}

/** 分组头：目录路径 + 命中数 + 折叠箭头。整行可点切换。 */
@Composable
private fun SearchGroupHeader(
  label: String,
  count: Int,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle)
          .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.Folder,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Text(
          text = "$count 条",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Icon(
        imageVector = if (expanded) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
        contentDescription = if (expanded) "收起" else "展开",
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 结果行：类型图标 + 文件名 + （大小 · 类型）。不挂长按 —— P0 不给写操作入口。 */
@Composable
private fun SearchHitRow(
  hit: SearchHit,
  onClick: () -> Unit,
) {
  val file = hit.file
  val icon = iconFor(file)
  val typeLabel =
    when {
      file.isDirectory -> "文件夹"
      file.isVideoFile() -> "视频"
      file.isPhoto() -> "图片"
      else -> "文件"
    }
  val subtitle =
    if (file.isDirectory) typeLabel
    else "${MediaFormatUtils.formatFileSize(file.size)} · $typeLabel"

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(8.dp).size(22.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = file.name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun iconFor(file: NetworkFile): ImageVector =
  when {
    file.isDirectory -> Icons.Filled.Folder
    file.isVideoFile() -> Icons.Filled.Movie
    file.isPhoto() -> Icons.Filled.Image
    else -> Icons.Filled.Info
  }

/** 底部：已显示 / 共 N 条 + 加载更多（或续页失败的重试）。 */
@Composable
private fun SearchResultFooter(
  state: ServerSearchUiState.Content,
  onLoadMore: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val total = if (state.total > 0) state.total else state.hits.size
    Text(
      text = "已显示 ${state.hits.size} / 共 $total 条",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
      state.loadMoreError != null -> {
        Text(
          text = state.loadMoreError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
        TextButton(onClick = onLoadMore) { Text("重试加载更多") }
      }

      state.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

      state.hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多") }

      else ->
        Text(
          text = "已显示全部结果",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
  }
}

/** Empty / Error 共用的居中说明区，附带「重试」与「改用当前目录搜索」两个出口。 */
@Composable
private fun ServerSearchMessage(
  icon: ImageVector,
  title: String,
  message: String,
  primaryAction: String?,
  onPrimary: (() -> Unit)?,
  onFallbackLocal: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(20.dp).size(40.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    if (message.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (primaryAction != null && onPrimary != null) {
      TextButton(onClick = onPrimary) { Text(primaryAction) }
    }
    TextButton(onClick = onFallbackLocal) { Text("改用当前目录搜索") }
  }
}
