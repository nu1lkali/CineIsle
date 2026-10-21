package app.marlboroadvance.mpvex.ui.browser.emby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState

/**
 * 分页列表的**通用底部状态行**（清单 1.1）。
 *
 * 各分页页面（收藏影片 / 标签结果 / 演员作品 / 播放历史 / 搜索…）的底部都是同一套需求：
 * 续拉中给个提示、失败给个重试、到底给句「没有更多了」。各写一遍必然走样，
 * 所以收成一个组件，参数只有 [appending] / [hasMore] / [error] / [loadedCount]。
 *
 * 刻意**不渲染首屏加载态** —— 首屏由各页的骨架屏负责
 * （`EmbySkeletonGrid` / `EmbySkeletonHistory`）。两处都放会出现「上面一个转圈、
 * 下面一个转圈」，比不给提示更让人困惑。
 *
 * 用法：放进 `LazyColumn` / `LazyVerticalGrid` 的最后一个 `item(span = …)`。
 */
@Composable
fun EmbyPagingFooter(
  appending: Boolean,
  hasMore: Boolean,
  error: String?,
  loadedCount: Int,
  /** 失败时点「重试」；传 null 则不渲染重试按钮（只显示一句提示） */
  onRetry: (() -> Unit)? = null,
  /** 到底时的文案；默认按已加载条数生成 */
  endText: String? = null,
  modifier: Modifier = Modifier,
) {
  when {
    appending -> Row(
      modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = "正在加载更多…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    error != null -> Column(
      modifier = modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "加载失败：$error",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )
      if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }

    // 还有更多但当前不在加载：什么都不画，把空间让给内容的 bottom contentPadding
    hasMore -> Spacer(modifier = modifier.size(0.dp))

    // 到底了。有内容才提示「已加载全部」—— 空列表的提示由各页的空状态负责
    else -> if (loadedCount > 0) {
      Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = endText ?: "已加载全部 $loadedCount 项",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * 懒加载列表的位置来源。
 *
 * `LazyListState` 与 `LazyGridState` 都有 `layoutInfo`，但没有共同父接口，
 * 所以用这个薄包装把两者统一 —— 免得 [PagedListAutoLoadMore] 写两遍。
 */
class PagingScrollSource(
  private val lastVisibleIndex: () -> Int,
  private val totalItems: () -> Int,
) {
  /** 已渲染到第几项（含 header/footer 之类的不计入下标语义的差异由调用方处理） */
  fun lastVisible(): Int = lastVisibleIndex()

  /** 列表一共渲染了多少项 */
  fun total(): Int = totalItems()
}

/**
 * 把 [PagedList] 接到懒加载列表上的触底预取（清单 1.1 的通用接线）。
 *
 * 距末尾还剩 [distance] 格就拉下一页 —— 数据到得比用户滚到得快，
 * 感知不到等待。列表已到底 / 正在加载时 [PagedList.loadMore] 自己会直接返回，
 * 所以这里可以无脑调，不用在外层判状态。
 *
 * @param source 列表位置来源（`LazyListState` / `LazyGridState` 都能包一层）
 * @param enabled 传 false 时整个收集协程不启动（例如切到另一个 tab）
 * @param distance 预取距离（格）。**统一传 `rememberPredictivePreloadDistance(prefs)`** ——
 *   设置里的「预测性懒加载」开关决定它是约一屏（12）还是贴近末尾（2）
 * @param loader 拉页函数（与 [PagedList.reload] / [loadMore] 同一个）
 */
@Composable
fun <T> PagedList<T>.AutoLoadMore(
  source: PagingScrollSource,
  enabled: Boolean = true,
  distance: Int = PagedList.PREFETCH_DISTANCE,
  loader: suspend (startIndex: Int, limit: Int) -> PagedResult<T>?,
) {
  // 把 hasMore / appending 读进 key：到底之后再触底，直接停掉收集协程（省一次每帧的读）
  val stop = !hasMore
  LaunchedEffect(source, enabled, stop, distance) {
    if (!enabled || stop) return@LaunchedEffect
    snapshotFlow { source.lastVisible() to source.total() }
      .collect { (last, total) ->
        if (total > 0 && last >= total - distance) {
          loadMore(loader)
        }
      }
  }
}

/**
 * 「预测性懒加载」开关对应的**触底预取距离**（全 App 分页列表统一读这里）。
 *
 * 开（默认）= [PagedList.PREFETCH_DISTANCE]（约一屏，提前拉下一页）；
 * 关 = [PagedList.PREFETCH_DISTANCE_OFF]（贴近末尾才拉，老行为）。
 *
 * 自写触底判定的页面（媒体库 / 聚合库 / 播放列表详情 / 照片网格 / 照片浏览器）
 * 也用它换算自己的「还剩几格」，不要各页硬编码 —— 否则设置里关了某页还在预取。
 */
@Composable
fun rememberPredictivePreloadDistance(browserPreferences: BrowserPreferences): Int {
  val predictivePreload by browserPreferences.embyPredictivePreload.collectAsState()
  return if (predictivePreload) PagedList.PREFETCH_DISTANCE else PagedList.PREFETCH_DISTANCE_OFF
}

/** `LazyListState` → [PagingScrollSource] */
@Composable
fun androidx.compose.foundation.lazy.LazyListState.pagingSource(): PagingScrollSource {
  val snap = androidx.compose.runtime.derivedStateOf {
    PagingScrollSource(
      lastVisibleIndex = { layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 },
      totalItems = { layoutInfo.totalItemsCount },
    )
  }
  return snap.value
}

/** `LazyGridState` → [PagingScrollSource] */
@Composable
fun androidx.compose.foundation.lazy.grid.LazyGridState.pagingSource(): PagingScrollSource {
  val snap = androidx.compose.runtime.derivedStateOf {
    PagingScrollSource(
      lastVisibleIndex = { layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 },
      totalItems = { layoutInfo.totalItemsCount },
    )
  }
  return snap.value
}
