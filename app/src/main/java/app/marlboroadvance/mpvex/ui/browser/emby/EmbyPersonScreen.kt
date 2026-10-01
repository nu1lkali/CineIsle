package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 演员 / 导演作品页：点详情页演职员头像进来，按 PersonIds 查 TA 参与过的条目。
 *
 * Emby 的 /Items 支持 PersonIds 过滤，所以这里不需要额外的「按名字搜」逻辑，
 * 直接用详情页拿到的 PersonId 查，结果精确。
 */
@Serializable
data class EmbyPersonScreen(
  val personId: String,
  val personName: String,
  /** 演员头像的 image tag；空则显示占位人形图标 */
  val personImageTag: String? = null,
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
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 演员本人的条目（含 UserData.IsFavorite）：右上角收藏红心的状态来源。
    // 进页面查一次；收藏/取消收藏成功后本地翻转，不再回查服务器。
    var personItem by remember { mutableStateOf<EmbyItem?>(null) }

    suspend fun load() {
      val current = server
      if (current == null) return
      isLoading = true
      error = null
      // 网格 key = Id ?: Name：按同一口径去重，防止服务端重复条目把网格撞崩
      val list = viewModel.loadPersonItems(current, personId).distinctBy { it.Id ?: it.Name ?: "" }
      items = list
      isLoading = false
      if (list.isEmpty()) error = "没有找到「$personName」的作品"
    }

    LaunchedEffect(personId, server) {
      load()
      // 红心状态单独拉。**必须保证拿到可点击的条目**：查询失败（服务端不支持等）
      // 就用 Id + 名字拼一个最小条目顶上 —— 否则红心永远禁用，点了没任何反馈。
      // UserData 未知时按「未收藏」处理，第一次点击会执行收藏，语义无损。
      val current = server ?: return@LaunchedEffect
      personItem =
        viewModel.loadPersonById(current, personId)
          ?: EmbyItem(Id = personId, Name = personName, Type = "Person")
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Text(
            text = personName,
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
        actions = {
          // ── 右上角收藏红心 ──
          // 与详情页同一套交互：乐观更新红心 → 请求服务器 → 按结果修正并弹 Toast；
          // 动效复用 FavoriteHeartIcon（弹跳 + 星光 + 红心渐变）。
          val isFavorite = personItem?.UserData?.IsFavorite == true
          IconButton(
            enabled = personItem != null,
            onClick = {
              val current = personItem ?: return@IconButton
              val currentServer = server ?: return@IconButton
              val wasFavorite = current.UserData?.IsFavorite == true
              // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
              personItem = current.copy(
                UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = !wasFavorite),
              )
              scope.launch {
                val result = viewModel.toggleFavorite(currentServer, current)
                val nowFavorite = result.getOrNull()
                personItem = current.copy(
                  UserData = (current.UserData ?: EmbyUserData())
                    .copy(IsFavorite = nowFavorite ?: wasFavorite),
                )
                Toast.makeText(
                  context,
                  when (nowFavorite) {
                    true -> "已收藏「$personName」"
                    false -> "已取消收藏「$personName」"
                    null -> "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                  },
                  Toast.LENGTH_SHORT,
                ).show()
              }
            },
          ) {
            // 与媒体详情页同款：弹跳 + 星光 + 红心渐变（状态驱动，点击时先乐观更新）
            // 尺寸对齐详情页的 24dp，避免「顶栏这颗比详情页小一圈」的观感落差
            FavoriteHeartIcon(
              isFavorite = isFavorite,
              isToggling = false,
              iconSize = 24.dp,
              idleColor = LocalContentColor.current,
            )
          }
        },
      )

      // ── 头部：演员头像 + 名字 ──
      val avatarUrl = remember(personId, personImageTag, server) {
        server?.let { EmbyClient.imageUrl(it, personId, "Primary", personImageTag, 300) }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.size(72.dp),
        ) {
          EmbyImage(
            url = avatarUrl,
            contentDescription = personName,
            modifier = Modifier
              .fillMaxSize()
              .clip(CircleShape),
            maxWidth = 300,
            placeholder = Icons.Default.Person,
          )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
          Text(
            text = personName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (isLoading) "加载中…" else "共 ${items.size} 部作品",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

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
              // 与媒体库「演员」分类、收藏页同一套规格：固定一行三列海报卡
              // （原先用 Adaptive(110dp)，与其它入口的卡片大小对不上，用户反馈已统一）
              columns = GridCells.Fixed(3),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
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
