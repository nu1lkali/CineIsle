package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.browser.components.NetworkSearchField
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 相册网格里的媒体类型筛选 */
private enum class AlbumFilter(val label: String) {
  All("全部"),
  Images("只看图片"),
  Videos("只看视频"),
}

/** 「全部相册」的哨兵 id（真实的 bucketId 不会为 -1） */
private const val ALL_BUCKETS = -1L

/**
 * 相册式选择器：顶部相册条 + 三列缩略图网格。
 *
 * ## 交互
 *
 * - **点一下即选中**，不需要先进「选择模式」。选中的格子右上角显示**序号**，
 *   而不是一个勾 —— 序号同时表达了「传了几个」和「先传哪个」。
 * - **长按开预览**：预览要比对内容才能决定选不选（尤其是视频），所以长按是不占
 *   选中状态的那条路；点=选、长按=看，两个动作互不干扰。
 * - **跨相册累积**：切到别的相册接着点，之前选的还在（[selected] 由调用方持有）。
 * - 相册条是横向滚动的，第一个永远是「全部」，按各相册**最新一张的时间**倒序 ——
 *   和系统相册 App 的顺序一致，刚拍的东西永远在最左边。
 *
 * ## 为什么筛选做成菜单而不是三个 chip
 *
 * 「全部 / 只看图片 / 只看视频」是低频操作，三个 chip 会挤掉相册条一半的宽度；
 * 收进一个图标菜单，相册条就能放下更多字。
 *
 * ## v181：突破「只看到最新 6000 条」
 *
 * 首批只读最新 [AlbumMediaSource.MAX_ITEMS] 条（打开要快），但这**不再等于看不到更早的**：
 * - **按名字搜索**（放大镜）：直查 MediaStore 的 `_display_name LIKE`，
 *   **完全不受 6000 上限约束** —— 想找三年前那张截图，按名字搜就有；
 * - **加载更多**：网格底部按 `date_added` 键集分页继续往前翻，一次 2000 条。
 *   两条路合起来，「6000 之外」既可搜也可翻。
 */
@Composable
internal fun AlbumPickerGrid(
  context: Context,
  modifier: Modifier = Modifier,
  selected: SnapshotStateList<AlbumItem>,
  onToggle: (AlbumItem) -> Unit,
  /** 长按预览：回传**当前可见列表**与该格在列表里的下标，好让预览器能前后翻 */
  onPreview: (index: Int, items: List<AlbumItem>) -> Unit,
) {
  val loader = remember(context) { AlbumImageLoader.get(context) }
  val scope = rememberCoroutineScope()

  // 列表本体提升成局部 state：加载更多要能往上追加
  var items by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
  var buckets by remember { mutableStateOf<List<AlbumBucket>>(emptyList()) }
  var loadedAt by remember { mutableStateOf(0L) }
  var hasMore by remember { mutableStateOf(false) }
  var loadingMore by remember { mutableStateOf(false) }
  var initialLoadDone by remember { mutableStateOf(false) }

  var bucketId by remember { mutableStateOf(ALL_BUCKETS) }
  var filter by remember { mutableStateOf(AlbumFilter.All) }

  // 按名字搜索（直查 MediaStore，不受首批上限影响）
  var searchActive by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var searching by remember { mutableStateOf(false) }
  var searchResult by remember { mutableStateOf<AlbumSearchResult?>(null) }

  // 只查一次：面板开合很频繁，每次重查会让「打开就要等 1 秒」变成常态。
  // 进程内缓存足以覆盖一次上传会话（相册内容不会在这期间变）。
  LaunchedEffect(context) {
    if (!initialLoadDone) {
      val lib = AlbumMediaSource.load(context)
      items = lib.items
      buckets = lib.buckets
      loadedAt = lib.loadedAt
      hasMore = lib.hasMore
      initialLoadDone = true
    }
  }

  // 搜索：防抖 250ms（每敲一个字就查一次数据库没有意义），查询在 IO 线程
  LaunchedEffect(searchActive, query) {
    if (!searchActive || query.isBlank()) {
      searchResult = null
      searching = false
      return@LaunchedEffect
    }
    delay(250)
    searching = true
    searchResult = AlbumMediaSource.searchByName(context, query)
    searching = false
  }

  val loaded = initialLoadDone
  if (!loaded) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(
          text = "正在读取相册…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
    return
  }

  // key 用 loadedAt 而不是 items 本身：data class 的 equals 会深比较上千个条目，
  // 每次重组都做一次会拖慢滚动。
  val visible = remember(loadedAt, items, bucketId, filter, searchResult) {
    val hit = searchResult
    val base =
      when {
        // 搜索结果是**跨相册**的（本来就不该再被相册条过滤一次）
        hit != null -> hit.items
        bucketId == ALL_BUCKETS -> items
        else -> items.filter { it.bucketId == bucketId }
      }
    when (filter) {
      AlbumFilter.All -> base
      AlbumFilter.Images -> base.filter { !it.isVideo }
      AlbumFilter.Videos -> base.filter { it.isVideo }
    }
  }

  if (items.isEmpty()) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Text(
        text = "没有找到照片或视频",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    return
  }

  fun loadMore() {
    if (loadingMore || !hasMore) return
    loadingMore = true
    scope.launch {
      val oldest = items.lastOrNull()?.dateAdded ?: 0L
      val more = AlbumMediaSource.loadMore(context, oldest)
      val known = items.mapTo(HashSet(items.size)) { it.key }
      val merged = items + more.filter { it.key !in known }
      items = merged
      buckets = AlbumMediaSource.bucketsOf(merged)
      loadedAt = System.currentTimeMillis()
      // 拿回满一页 ⇒ 大概率还有；不足一页 ⇒ 到头了
      hasMore = more.size >= AlbumMediaSource.PAGE_SIZE
      AlbumMediaSource.rememberLoaded(merged, hasMore)
      loadingMore = false
    }
  }

  Column(modifier = modifier) {
    // ── 相册条（搜索时让位给搜索框）──
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (searchActive) {
        Text(
          text = if (query.isBlank()) "按文件名搜索全部相册" else "在全部相册里搜「${query.trim()}」",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      } else {
        LazyRow(
          modifier = Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          contentPadding = PaddingValues(end = 4.dp),
        ) {
          item(key = "all-buckets") {
            AlbumChip(
              text = "全部",
              count = items.size,
              more = hasMore,
              selected = bucketId == ALL_BUCKETS,
              onClick = { bucketId = ALL_BUCKETS },
            )
          }
          items(items = buckets, key = { it.id }) { bucket ->
            AlbumChip(
              text = bucket.name,
              count = bucket.count,
              // 单个相册的数字只统计**已加载**的部分，但没有逐相册的「还有多少」
              // 可算（要再查一次数据库），所以这里只给 6000 这个总数打加号
              selected = bucketId == bucket.id,
              onClick = { bucketId = bucket.id },
            )
          }
        }
      }
      IconButton(
        onClick = {
          searchActive = !searchActive
          if (!searchActive) query = ""
        },
      ) {
        Icon(
          imageVector = if (searchActive) Icons.Default.Close else Icons.Default.Search,
          contentDescription = if (searchActive) "退出搜索" else "按文件名搜索",
          modifier = Modifier.size(18.dp),
          tint =
            if (searchActive) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
      FilterMenu(current = filter, onSelect = { filter = it })
    }

    if (searchActive) {
      NetworkSearchField(
        query = query,
        onQueryChange = { query = it },
        onClose = {
          searchActive = false
          query = ""
        },
        placeholder = "搜索文件名（不受 6000 条上限限制）…",
      )
    }

    // ── 网格 ──
    val emptyText =
      when {
        searchActive ->
          if (query.isBlank()) {
            "输入文件名关键词开始搜索"
          } else {
            "没有名字含「${query.trim()}」的照片或视频"
          }
        filter == AlbumFilter.Images -> "这个相册里没有图片"
        filter == AlbumFilter.Videos -> "这个相册里没有视频"
        else -> "这个相册是空的"
      }

    if (searching && searchResult == null) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(
            text = "正在搜索…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    } else if (visible.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
          text = emptyText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(horizontal = 24.dp),
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        gridItemsIndexed(items = visible, key = { _, it -> it.key }) { index, item ->
          val order = selected.indexOfFirst { it.key == item.key } + 1
          AlbumCell(
            item = item,
            order = order,
            loader = loader,
            onClick = { onToggle(item) },
            onLongClick = { onPreview(index, visible) },
          )
        }
        // ── 底部两行说明：「还有更早的没读」/「搜索命中被截断」──
        // 两条都是**上限的诚实交代**：不说的话用户以为库里就这么多
        val hint =
          when {
            searchResult?.truncated == true ->
              "只显示前 ${AlbumMediaSource.SEARCH_LIMIT} 条结果，换个更精确的关键词试试"
            // 搜索中不提供「加载更多」：搜索结果来自整库，与时间游标无关
            searchResult != null -> null
            hasMore -> null // 有「加载更多」按钮就够，不必再写一遍
            items.size > AlbumMediaSource.MAX_ITEMS -> "已经到最早的一张了"
            else -> null
          }
        if (hint != null) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
            Text(
              text = hint,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
              textAlign = TextAlign.Center,
            )
          }
        }
        if (searchResult == null && hasMore) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "load-more") {
            LoadMoreRow(loading = loadingMore, onClick = { loadMore() })
          }
        }
      }
    }
  }
}

/** 网格底部的「加载更多」：一个横跨整行的按钮，点了才去查（不自动触发） */
@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (loading) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
          text = "正在读取更早的内容…",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Text(
        text = "加载更早的内容",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
          Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

@Composable
private fun AlbumChip(
  text: String,
  count: Int,
  selected: Boolean,
  onClick: () -> Unit,
  /** 这个数字只是**已加载**的部分（后面还有更早的没读）时打个 + */
  more: Boolean = false,
) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color =
          if (selected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
      Text(
        // 「6000+」而不是「6000」：不加这个加号，用户会把它读成精确总数
        text = if (more) "$count+" else count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color =
          if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
          } else {
            MaterialTheme.colorScheme.outline
          },
      )
    }
  }
}

@Composable
private fun FilterMenu(current: AlbumFilter, onSelect: (AlbumFilter) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(
        imageVector = Icons.Default.FilterList,
        contentDescription = "筛选",
        modifier = Modifier.size(18.dp),
        tint =
          if (current == AlbumFilter.All) {
            MaterialTheme.colorScheme.onSurfaceVariant
          } else {
            MaterialTheme.colorScheme.primary
          },
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      AlbumFilter.values().forEach { option ->
        DropdownMenuItem(
          text = {
            Text(
              text = option.label,
              color =
                if (option == current) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          },
          trailingIcon = {
            if (option == current) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          },
          onClick = {
            expanded = false
            onSelect(option)
          },
        )
      }
    }
  }
}

@Composable
private fun AlbumCell(
  item: AlbumItem,
  order: Int,
  loader: ImageLoader,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val selected = order > 0
  Box(
    modifier =
      Modifier.aspectRatio(1f)
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    AsyncImage(
      model = item.uri,
      contentDescription = item.name,
      imageLoader = loader,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )

    // 选中态用主色薄压一层：比描边更明显，又不会盖住缩略图内容
    if (selected) {
      Box(
        modifier =
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)),
      )
      Box(
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = order.toString(),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          // onPrimary：深色主题下 primary 是亮色，白字会糊
          color = MaterialTheme.colorScheme.onPrimary,
        )
      }
    }

    if (item.isVideo) {
      // 视频角标：黑底白字压在缩略图上（不跟随主题 —— 底下是画面，不是 UI 表面）
      Row(
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(10.dp),
          tint = Color.White,
        )
        Text(
          text = formatDuration(item.durationMs),
          fontSize = 10.sp,
          color = Color.White,
        )
      }
    }
  }
}

private fun formatDuration(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%d:%02d".format(minutes, seconds)
  }
}
