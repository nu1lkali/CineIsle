package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import app.marlboroadvance.mpvex.domain.emby.DownloadedMediaRepository
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyPosterCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.java.KoinJavaComponent.inject

/**
 * 离线内容页（清单 4.2）。
 *
 * 列出所有**已落盘**（`localUri` 非空）的下载内容，断网时照样能浏览、能播。
 * 数据源是纯 Room（[DownloadedMediaRepository]），不发起任何网络请求 ——
 * 所以「已下载」这件事不再依赖「现在能连上服务器」。
 *
 * 与下载管理页的分工：
 * - **下载管理页**管「正在下 / 暂停 / 续传」，是任务视角
 * - **本页**管「已经下好、可以直接看」，是内容视角
 * 两者数据来自不同的表（`emby_download_tasks` / `emby_downloaded_media`），
 * 所以在下载管理页清掉已完成记录（文件保留）不会让本页变空。
 */
@Serializable
object EmbyOfflineScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyOfflineViewModel = viewModel(
      factory = EmbyOfflineViewModel.factory(context.applicationContext as Application),
    )

    val items by viewModel.downloaded.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    // 分组是纯计算 + 列表不大，remember 一层就够了
    val groups = remember(items) { groupDownloadedMedia(items) }

    // 确认弹窗状态：itemId + 标题。null = 没在弹
    var pendingRemoval by remember { mutableStateOf<DownloadedMediaEntity?>(null) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text("离线内容")
              // 副标题只在有内容时给：有东西才需要知道「占了多少空间」，
              // 空列表时这句话是纯噪音
              if (items.isNotEmpty()) {
                Text(
                  text = "${items.size} 个 · ${formatBytes(totalBytes)}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          navigationIcon = {
            IconButton(onClick = { backStack.removeLastOrNull() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
          },
        )
      },
    ) { innerPadding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        // 离线提示条：只在断网时出现。
        // 有了它，用户看到本地内容还在就不会以为 App 坏了 ——
        // 之前没有任何联网状态反馈，断网的表现是「媒体库空了」。
        if (!isOnline) {
          OfflineBanner()
        }

        if (items.isEmpty()) {
          EmbyEmptyState(
            message = if (isOnline) {
              "还没有下载任何内容\n在媒体详情页点「下载」即可离线保存"
            } else {
              "当前离线，且没有已下载的内容\n联网后可从媒体详情页下载"
            },
            buttonText = "返回",
            onAction = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            groups.forEach { group ->
              item(key = "header_${group.title}") {
                OfflineSectionHeader(
                  title = group.title,
                  // 剧集段额外给「共 N 集」—— 一部剧下了 20 集时，
                  // 光看「剧集」两个字不知道规模
                  trailing = (group as? DownloadedGroup.BySeries)?.let { "共 ${it.totalEpisodes} 集" },
                )
              }
              when (group) {
                is DownloadedGroup.Flat -> item(key = "grid_${group.title}") {
                  OfflineMediaGrid(
                    items = group.items,
                    onPlay = { playOffline(context, it) },
                    onRemove = { pendingRemoval = it },
                  )
                }

                is DownloadedGroup.BySeries -> group.series.forEach { series ->
                  item(key = "series_${series.seriesName}") {
                    SeriesBlock(
                      series = series,
                      onPlay = { playOffline(context, it) },
                      onRemove = { pendingRemoval = it },
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // 删除确认：文件删了就没了，不做二次确认是危险的
    pendingRemoval?.let { target ->
      AlertDialog(
        onDismissRequest = { pendingRemoval = null },
        title = { Text("删除下载文件？") },
        text = {
          Text("「${target.title}」的文件会从本机删除，无法恢复。只想从列表里移除请选「仅移除记录」。")
        },
        confirmButton = {
          TextButton(
            onClick = {
              pendingRemoval = null
              viewModel.deleteFile(target)
            },
          ) { Text("删除文件") }
        },
        dismissButton = {
          Row {
            TextButton(
              onClick = {
                pendingRemoval = null
                viewModel.removeRecordOnly(target.itemId)
              },
            ) { Text("仅移除记录") }
            TextButton(onClick = { pendingRemoval = null }) { Text("取消") }
          }
        },
      )
    }
  }
}

/**
 * 离线内容页的 ViewModel。
 *
 * 用 [androidx.lifecycle.viewmodelScope] 而不是自己建 scope：页面销毁时
 * 收集协程自动取消，不会有「页面走了还在 collect 数据库」的泄漏。
 */
class EmbyOfflineViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<DownloadedMediaRepository>(DownloadedMediaRepository::class.java)
  private val manager by inject<app.marlboroadvance.mpvex.domain.emby.EmbyDownloadManager>(
    app.marlboroadvance.mpvex.domain.emby.EmbyDownloadManager::class.java
  )
  private val networkMonitor by inject<app.marlboroadvance.mpvex.utils.network.NetworkMonitor>(
    app.marlboroadvance.mpvex.utils.network.NetworkMonitor::class.java
  )

  /**
   * 不限服务器：离线页是「这台手机上有什么」的问题，不是「当前服务器有什么」的问题。
   * 换过服务器的用户下过的片子不该凭空消失。
   */
  val downloaded: StateFlow<List<DownloadedMediaEntity>> = repository.observeDownloaded(null)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val totalBytes: StateFlow<Long> = repository.observeTotalBytes()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

  val isOnline: StateFlow<Boolean> = networkMonitor.observe()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

  /** 只从离线列表里摘掉，文件留着 */
  fun removeRecordOnly(itemId: String) {
    viewModelScope.launch { repository.removeRecord(itemId) }
  }

  /**
   * 删文件 + 删记录。
   *
   * 走 manager 而不是自己动文件：文件删除要走 MediaStore（Android 10+）
   * 或 app 私有目录，逻辑在 manager 里（连同「已发布过的文件要 unpublish」那套），
   * 页面层重写一遍必然漏东西。
   */
  fun deleteFile(item: DownloadedMediaEntity) {
    viewModelScope.launch {
      manager.remove(item.itemId, deleteFile = true)
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyOfflineViewModel(application) }
    }
  }
}

/** 断网提示条 */
@Composable
private fun OfflineBanner() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = Icons.Default.CloudOff,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = "当前离线 · 下面显示的是已下载到本机的内容",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun OfflineSectionHeader(title: String, trailing: String? = null) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    if (trailing != null) {
      Text(
        text = "  $trailing",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 电影 / 其他：横滑网格 */
@Composable
private fun OfflineMediaGrid(
  items: List<DownloadedMediaEntity>,
  onPlay: (DownloadedMediaEntity) -> Unit,
  onRemove: (DownloadedMediaEntity) -> Unit,
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(items, key = { it.itemId }) { item ->
      EmbyPosterCard(
        title = item.title,
        subtitle = item.productionYear?.toString(),
        // ⚠️ 离线时海报本来就拉不到（URL 要拼服务器地址）。
        // 传 null 让 EmbyImage 走占位图，而不是给一个必然失败的 URL 去反复重试。
        imageUrl = null,
        progress = null,
        isFavorite = false,
        onClick = { onPlay(item) },
        onLongClick = { onRemove(item) },
        placeholder = if (item.type == "Episode") Icons.Default.Tv else Icons.Default.Movie,
      )
    }
  }
}

/** 一部剧：标题 + 横向集数 chip 条 */
@Composable
private fun SeriesBlock(
  series: SeriesGroup,
  onPlay: (DownloadedMediaEntity) -> Unit,
  onRemove: (DownloadedMediaEntity) -> Unit,
) {
  Column(modifier = Modifier.padding(top = 4.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Tv,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "  ${series.seriesName}",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(
        text = "  ${series.items.size} 集",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // 集数用 chip 而不是卡片：单集的区分度靠「集号」而不是「封面」，
    // 一行能塞下六七集，扫一眼就知道下了哪些
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(series.items, key = { it.itemId }) { item ->
        EpisodeChip(item = item, onClick = { onPlay(item) }, onLongClick = { onRemove(item) })
      }
    }
  }
}

@Composable
private fun EpisodeChip(
  item: DownloadedMediaEntity,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val tag = item.episodeTag()
  Card(
    onClick = onClick,
    modifier = Modifier.width(112.dp),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
      Text(
        // 有集号就显示「S01E05 · 标题」，没集号就只显示标题。
        // 别把集号单独放一行 —— chip 只有 112dp 宽，竖排三行会很高
        text = if (tag.isNotEmpty()) "$tag · ${item.title}" else item.title,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * 播放已下载的内容。
 *
 * 走 [MediaUtils.playFile] 而不是自己拼 Intent：它会 `setClass(PlayerActivity)`
 * 起 App 内的播放器（双内核、字幕、播放记录全都生效）。
 * 之前下载管理页是 `Intent(ACTION_VIEW)` 甩给系统播放器 —— 体验割裂，
 * 而且拿不到本项目的播放进度/续播能力。
 *
 * ⚠️ `content://` uri 必须带 `FLAG_GRANT_READ_URI_PERMISSION` 才能被播放器读，
 * MediaUtils 里已经加了，这里不用重复。
 */
private fun playOffline(context: android.content.Context, item: DownloadedMediaEntity) {
  val raw = item.localUri ?: return
  runCatching {
    MediaUtils.playFile(Uri.parse(raw), context, "emby_offline")
  }
}

/**
 * 离线内容入口按钮（首页顶栏，与 [EmbyDownloadEntryButton] 并排）。
 *
 * 角标规则与下载入口**刻意不同**：
 * - 下载入口：有任务在跑 → 红点（进行中，要盯着）
 * - 本入口：有已下载内容 → 灰点（静态状态，不需要实时提醒）
 *
 * 用 `error` 红色只留给「有东西正在动」的场景；离线内容是静态的，
 * 挂个红点会让人误以为还有东西在下载。
 */
@Composable
fun EmbyOfflineEntryButton() {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val repository by inject<DownloadedMediaRepository>(DownloadedMediaRepository::class.java)
  val flow = remember { repository.observeDownloaded(null) }
  val downloaded by flow.collectAsState(initial = emptyList())

  Box {
    IconButton(onClick = { backStack.add(EmbyOfflineScreen) }) {
      Icon(Icons.Default.OfflinePin, contentDescription = "离线内容")
    }
    if (downloaded.isNotEmpty()) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 2.dp, end = 2.dp)
          .size(16.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (downloaded.size > 9) "9+" else downloaded.size.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSecondary,
        )
      }
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024.0
  var index = 0
  while (value >= 1024.0 && index < units.lastIndex) {
    value /= 1024.0
    index++
  }
  return String.format(Locale.US, "%.1f %s", value, units[index])
}
