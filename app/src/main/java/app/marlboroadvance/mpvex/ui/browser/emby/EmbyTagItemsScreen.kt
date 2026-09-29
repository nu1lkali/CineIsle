package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable

/**
 * 「按类型 / 标签找片」结果页。
 *
 * 详情页顶部的类型 chip（以及标签 chip）点一下进来：
 * 和点头像进「演员作品页」是同一套交互 —— 只是过滤维度从 PersonIds 换成 Genres / Tags。
 *
 * 走 Emby 的 /Items 过滤（不传 ParentId，跨全部媒体库），只保留可播放类型，
 * 结果按名称升序，点卡片照常进详情页。
 *
 * @param keyword 类型名或标签名（就是 chip 上的文字）
 * @param kind    "genre" = 类型，其它值按标签处理
 */
@Serializable
data class EmbyTagItemsScreen(
  val keyword: String,
  val kind: String = "genre",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()

    val isGenre = kind == "genre"
    val kindLabel = if (isGenre) "类型" else "标签"

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
      val current = server
      if (current == null) return
      isLoading = true
      error = null
      val list = if (isGenre) {
        viewModel.loadGenreItems(current, keyword)
      } else {
        viewModel.loadTagItems(current, keyword)
      }
      items = list
      isLoading = false
      if (list.isEmpty()) error = "没有找到该${kindLabel}下的媒体"
    }

    LaunchedEffect(keyword, kind, server) { load() }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Text(
            text = keyword,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
            )
          }
        },
      )

      // 一行说明，告诉用户当前是按什么维度筛的
      Text(
        text = if (isLoading) "加载中…" else "$kindLabel · 共 ${items.size} 项",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          isLoading && items.isEmpty() -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
          }

          error != null && items.isEmpty() -> {
            Text(
              text = error ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            )
          }

          else -> {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 110.dp),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              items(items, key = { it.Id ?: it.Name ?: "" }) { item ->
                EmbyMediaCard(
                  title = item.Name ?: "",
                  subtitle = item.ProductionYear?.toString(),
                  imageUrl = server?.let { viewModel.imageUrl(it, item, "Primary", 400) },
                  progress = null,
                  isFavorite = item.UserData?.IsFavorite == true,
                  fillWidth = true,
                  onClick = {
                    item.Id?.let { id ->
                      backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
                    }
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}
