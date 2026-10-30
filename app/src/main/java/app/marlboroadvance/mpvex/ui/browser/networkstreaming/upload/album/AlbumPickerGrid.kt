package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album

import android.content.Context
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalFocusManager
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

  // 相册选择面板（3 列封面网格 + 按相册名过滤）开着的时候，内容区让给它
  var albumPickerOpen by remember { mutableStateOf(false) }

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

  // 当前相册（「全部」= 没有具体相册）——顶栏按钮要一直显示你在看哪一档
  val selectedBucket = buckets.firstOrNull { it.id == bucketId }
  val focusManager = LocalFocusManager.current

  Column(modifier = modifier) {
    // ── 顶栏：返回 · 相册选择器 · 按文件名搜索 · 类型筛选 ──
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      // 进了某个相册就是「深了一层」，左上角必须有个回上一层的箭头 ——
      // 这是 Google 相册 / 三星相册的同一套层级逻辑：相册列表 → 相册 → 照片。
      // 「全部照片」是顶层，没有上一层，所以不显示箭头（否则箭头点下去只是原地打转）。
      if (selectedBucket != null) {
        IconButton(
          onClick = {
            focusManager.clearFocus()
            searchActive = false
            query = ""
            albumPickerOpen = true
          },
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回相册列表",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      AlbumSelectButton(
        label = selectedBucket?.name ?: "全部照片",
        countText =
          if (selectedBucket == null) {
            if (hasMore) "${items.size}+" else items.size.toString()
          } else {
            selectedBucket.count.toString()
          },
        open = albumPickerOpen,
        onClick = {
          val opening = !albumPickerOpen
          albumPickerOpen = opening
          // 两个「找东西」的界面不能叠着：进相册面板就把文件名搜索收掉
          if (opening) {
            searchActive = false
            query = ""
          }
          // 面板里有自己的输入框：不收焦点的话键盘会一直压着面板底部
          focusManager.clearFocus()
        },
      ) {
        if (selectedBucket == null) {
          AlbumMosaicCover(items = items.take(4), loader = loader)
        } else {
          AlbumCoverImage(item = selectedBucket.cover, loader = loader)
        }
      }
      // 按钮靠左，把剩余宽度让出来：相册名再长也不该把右边的图标挤到换行
      Spacer(Modifier.weight(1f))
      IconButton(
        onClick = {
          searchActive = !searchActive
          if (!searchActive) query = ""
          if (searchActive) {
            albumPickerOpen = false
            focusManager.clearFocus()
          }
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

    if (albumPickerOpen) {
      // 相册**多**的时候，横向轨道是死路：几十个相册一路滑过去根本找不准。
      // 这里换成「3 列封面网格 + 按相册名过滤」，竖着翻两三屏就到头，名字还能直接搜。
      AlbumGridPanel(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        buckets = buckets,
        mosaic = items.take(4),
        totalCount = items.size,
        hasMore = hasMore,
        selectedId = bucketId,
        loader = loader,
        onCollapse = {
          focusManager.clearFocus()
          albumPickerOpen = false
        },
        onSelect = { id ->
          // 记一笔「最近使用」：下次打开面板它就在最上面，不用再滑
          AlbumMediaSource.rememberAlbumUsed(id)
          bucketId = id
          focusManager.clearFocus()
          albumPickerOpen = false
        },
      )
    } else {
      if (searchActive) {
        NetworkSearchField(
          query = query,
          onQueryChange = { query = it },
          onClose = {
            searchActive = false
            query = ""
          },
          placeholder = "搜索文件名…",
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

/**
 * 顶栏那个「当前相册」按钮：**封面小头像 + 相册名 + 数量 + ▾**。
 *
 * ## 为什么不是一排可横滑的相册 chip
 *
 * 相册一多就废：几十个相册横向排开，用户只能一路滑着找，滑到一半连自己滑到哪儿
 * 都不知道，而且 chip 越宽（加头像之后）一屏能看到的越少 —— 越加越难找。
 * 横向滚动**天生不适合「在一堆同类里挑一个」**：它的目标序列是线性且不可预读的。
 *
 * 现在这个按钮只承担两件事：告诉用户「当前在看哪一档」、点开真正的选择面板。
 * 一行一个按钮，横向空间怎么都不会被相册数量拖垮；顺带把整条轨道的高度（约 44dp）
 * 还给照片网格。
 *
 * @param countText 传字符串而不是数字：可能带「+」（还有没读的）
 */
@Composable
private fun AlbumSelectButton(
  label: String,
  countText: String,
  open: Boolean,
  onClick: () -> Unit,
  cover: @Composable () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (open) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(start = 4.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box(
        modifier =
          Modifier.size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        cover()
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 名字再长也有上限：一个「2024 年春节全家福精选」不该把右边图标挤出去
        modifier = Modifier.widthIn(max = 150.dp),
        color =
          if (open) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
      )
      Text(
        text = countText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
      Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint =
          if (open) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}

/**
 * 相册选择面板：**全部照片横条 + 最近使用 + 所有相册**，替换掉原来那条横向轨道。
 *
 * ## 为什么这个才解决「相册太多找不到」
 *
 * - **竖向网格**：一屏 9~12 个，几十个相册两三屏就到底，而且滑动距离与位置一一对应
 *   （「我记得它在靠下一点」这种模糊记忆用得上）；
 * - **名字可搜**：记得名字就直接打两个字，不用滑；
 * - **封面足够大**（1/3 屏宽）：靠画面认相册，而不是靠读小字；
 * - 点一下即选定并自动退出，不需要额外的「确定」。
 *
 * ## 为什么「全部照片」是一整行横条，不当网格第一格
 *
 * 网格第一格会让它和相册**并列**：读起来像「叫全部的那个相册」，位置还会随过滤/排序
 * 乱跳。它其实是**上一层**（不筛相册）。做成整行横条，层级关系和「最近使用」「所有相册」
 * 两个小标题一起成立，正好是 Google 相册 / 三星相册相册页的那套结构。
 *
 * ## 为什么有「最近使用」
 *
 * 「最近有新照片」是时间顺序，「最近用过的」是你亲手挑过的 —— 后者才是真正的快捷入口。
 * 几十个相册时，第一行就是上次用的那几个，多数情况下根本不用滚。
 *
 * 注意这里**不显示**类型筛选（图片/视频）—— 那是「看什么」的事，选相册时不该掺进来。
 */
@Composable
private fun AlbumGridPanel(
  modifier: Modifier,
  buckets: List<AlbumBucket>,
  /** 「全部照片」那一行的四宫格拼贴素材（最新四张） */
  mosaic: List<AlbumItem>,
  totalCount: Int,
  hasMore: Boolean,
  selectedId: Long,
  loader: ImageLoader,
  onCollapse: () -> Unit,
  onSelect: (Long) -> Unit,
) {
  var nameQuery by remember { mutableStateOf("") }
  val needle = nameQuery.trim()
  val filtered =
    if (needle.isEmpty()) {
      buckets
    } else {
      buckets.filter { it.name.contains(needle, ignoreCase = true) }
    }

  // 最近使用：只留还在当前库里的（相册被删掉后 id 会失效）
  val recent =
    remember(buckets) {
      AlbumMediaSource.recentAlbumIds().mapNotNull { id -> buckets.firstOrNull { it.id == id } }
    }
  val recentIds = recent.mapTo(HashSet(recent.size)) { it.id }
  // 最近用过的已经在上面露过脸了，再在「所有相册」里出现一次只会让人以为有两份
  val rest = filtered.filter { it.id !in recentIds }

  Column(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "相册",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "共 ${buckets.size} 个",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 8.dp),
      )
      Spacer(Modifier.weight(1f))
      // 与按钮上的 ▾ 呼应：▾ 展开、▴ 收起。面板占满内容区，必须有一眼可见的出口
      IconButton(onClick = onCollapse) {
        Icon(
          imageVector = Icons.Default.KeyboardArrowUp,
          contentDescription = "收起相册面板",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    NetworkSearchField(
      query = nameQuery,
      onQueryChange = { nameQuery = it },
      onClose = { nameQuery = "" },
      placeholder = "搜索相册名…",
      // 面板一开就抢焦点弹键盘的话，下面第一行的相册会被键盘压掉一半
      autoFocus = false,
    )
    if (filtered.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
          text = "没有名字含「$needle」的相册",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(horizontal = 24.dp),
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // 搜索时不显示「全部照片」横条与分组：此刻用户要的是一个平铺的命中列表
        if (needle.isEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "all-photos") {
            AllPhotosRow(
              countText = if (hasMore) "$totalCount+" else totalCount.toString(),
              selected = selectedId == ALL_BUCKETS,
              onClick = { onSelect(ALL_BUCKETS) },
            ) {
              AlbumMosaicCover(items = mosaic, loader = loader)
            }
          }
          if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-recent") {
              AlbumSectionHeader(text = "最近使用")
            }
            items(items = recent, key = { "r${it.id}" }) { bucket ->
              AlbumBucketCell(
                label = bucket.name,
                countText = bucket.count.toString(),
                selected = selectedId == bucket.id,
                onClick = { onSelect(bucket.id) },
              ) {
                AlbumCoverImage(item = bucket.cover, loader = loader)
              }
            }
          }
          item(span = { GridItemSpan(maxLineSpan) }, key = "h-all") {
            AlbumSectionHeader(text = "所有相册")
          }
        }
        items(items = if (needle.isEmpty()) rest else filtered, key = { "a${it.id}" }) { bucket ->
          AlbumBucketCell(
            label = bucket.name,
            countText = bucket.count.toString(),
            selected = selectedId == bucket.id,
            onClick = { onSelect(bucket.id) },
          ) {
            AlbumCoverImage(item = bucket.cover, loader = loader)
          }
        }
      }
    }
  }
}

/** 分组小标题：最近使用 / 所有相册 */
@Composable
private fun AlbumSectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
  )
}

/**
 * 「全部照片」一整行横条：四宫格缩略 + 名称 + 总数 + 选中勾。
 *
 * 比网格里的一格更像「上一层」而不是「一个相册」—— 它确实是上一层（不筛相册）。
 */
@Composable
private fun AllPhotosRow(
  countText: String,
  selected: Boolean,
  onClick: () -> Unit,
  cover: @Composable () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier.size(44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        cover()
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "全部照片",
          style = MaterialTheme.typography.bodyLarge,
          color =
            if (selected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
          text = "$countText 项",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
      if (selected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

/** 相册面板里的一格：大封面 + 数量角标 + 名字 */
@Composable
private fun AlbumBucketCell(
  label: String,
  countText: String,
  selected: Boolean,
  onClick: () -> Unit,
  cover: @Composable () -> Unit,
) {
  Column(modifier = Modifier.clickable(onClick = onClick)) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      cover()
      Text(
        text = countText,
        fontSize = 10.sp,
        // 压在封面上，不跟主题走（底下是画面，不是 UI 表面）——与视频时长角标同一口径
        color = Color.White,
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
      )
      if (selected) {
        Box(
          modifier =
            Modifier.matchParentSize()
              .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
        )
      }
    }
    Spacer(Modifier.height(5.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color =
        if (selected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurface
        },
    )
  }
}

/** 相册封面：该相册最新一张的缩略图 */
@Composable
private fun AlbumCoverImage(item: AlbumItem, loader: ImageLoader, modifier: Modifier = Modifier) {
  AsyncImage(
    model = item.uri,
    contentDescription = null,
    imageLoader = loader,
    contentScale = ContentScale.Crop,
    modifier = modifier.fillMaxSize(),
  )
}

/**
 * 「全部」的头像：最新四张的**四宫格拼贴**。
 *
 * 不能拿第一张当头像 —— 那样它和最新那个相册长得一模一样，两个挨着的 chip 会互相冒充。
 * 拼贴一眼就能认出「这是一堆照片」，正好表达「全部」。
 */
@Composable
private fun AlbumMosaicCover(items: List<AlbumItem>, loader: ImageLoader) {
  when {
    items.size >= 4 ->
      Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
          AlbumCoverImage(item = items[0], loader = loader, modifier = Modifier.weight(1f))
          AlbumCoverImage(item = items[1], loader = loader, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.weight(1f)) {
          AlbumCoverImage(item = items[2], loader = loader, modifier = Modifier.weight(1f))
          AlbumCoverImage(item = items[3], loader = loader, modifier = Modifier.weight(1f))
        }
      }
    // 不到四张就退回第一张：这时也谈不上「混淆」，因为没几个相册
    items.isNotEmpty() -> AlbumCoverImage(item = items.first(), loader = loader)
    else -> Unit
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
