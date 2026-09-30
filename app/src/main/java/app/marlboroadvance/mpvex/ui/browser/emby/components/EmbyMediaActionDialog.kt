package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyImageType
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemLookupInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSearchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import kotlinx.coroutines.launch

/**
 * 媒体条目（电影 / 剧集 / 单集…）长按弹出的操作菜单。
 *
 * 七项操作里有四项会改服务器上的数据（收藏、已看、元数据、图片），
 * 两项是异步任务（刷新元数据、刮削），删除更是不可逆 —— 所以主菜单只做「选择」，
 * 真正要落地 destructive 的那几项还会再弹一层确认。
 *
 * @param onChanged 条目本身变了（收藏 / 已看 / 元数据），调用方拿去刷新列表项
 * @param onDeleted 删除成功，调用方把这条从列表里剔除
 * @param onImagesChanged 图片变了：调用方需要重新拉列表（封面带 tag，缓存已旧）
 */
@Composable
fun EmbyMediaActionsDialog(
  server: EmbyServer,
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onChanged: (EmbyItem) -> Unit,
  onDeleted: (String) -> Unit,
  onImagesChanged: () -> Unit,
) {
  val context = LocalContext.current
  // 用 ViewModel 作用域而非 rememberCoroutineScope()：菜单/抽屉一点完就关闭，
  // 界面作用域随之被取消，协程切回主线程时会抛 LeftCompositionCancellationException，
  // 明明服务端已生效却被当成失败。「发出去就该跑完」的动作必须挂在界面生命周期之外。
  val scope = viewModel.viewModelScope
  val name = item.Name ?: ""
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true

  var page by remember { mutableStateOf(MediaPage.MAIN) }

  when (page) {
    MediaPage.MAIN -> EmbyMediaActionMenu(
      item = item,
      server = server,
      viewModel = viewModel,
      onDismissRequest = onDismissRequest,
      onChanged = onChanged,
      onOpen = { page = it },
    )

    MediaPage.EDIT_METADATA -> EmbyEditMetadataDialog(
      item = item,
      onDismissRequest = onDismissRequest,
      onSave = { updated ->
        scope.launch {
          val ok = viewModel.updateItemMetadata(server, updated)
          Toast.makeText(
            context,
            if (ok) "元数据已保存" else "保存失败，可能需要管理员权限",
            Toast.LENGTH_LONG,
          ).show()
          if (ok) {
            onChanged(updated)
            onDismissRequest()
          }
        }
      },
    )

    MediaPage.EDIT_IMAGES -> EmbyEditImagesDialog(
      server = server,
      item = item,
      viewModel = viewModel,
      onDismissRequest = onDismissRequest,
      onImagesChanged = onImagesChanged,
    )

    MediaPage.IDENTIFY -> EmbyIdentifyDialog(
      server = server,
      item = item,
      viewModel = viewModel,
      onDismissRequest = onDismissRequest,
      onApplied = {
        onChanged(item)
        onImagesChanged()
      },
    )

    MediaPage.REFRESH -> EmbyRefreshMetadataDialog(
      name = name,
      kindLabel = "条目",
      onDismissRequest = onDismissRequest,
      onConfirm = { replaceMetadata, replaceImages ->
        runEmbyLibraryAction(
          context = context,
          scope = scope,
          server = server,
          itemId = item.Id,
          name = name,
          okMessage = "已通知服务器刷新「$name」的元数据，稍后下拉刷新查看",
          action = { s, id -> runCatching { viewModel.refreshLibraryMetadata(s, id, replaceMetadata, replaceImages) } },
        )
        onDismissRequest()
      },
    )

    // 删除是唯一保留居中弹窗的操作：它是不可逆的，需要一个「停下来看清楚」的
    // 阻断式确认，而不是顺手就能往下拖掉的底部抽屉
    MediaPage.DELETE -> AlertDialog(
      onDismissRequest = onDismissRequest,
      title = { Text("删除媒体") },
      text = {
        Text("确定删除「$name」吗？文件会从文件系统和媒体库中一并删除，且不可撤销。")
      },
      confirmButton = {
        TextButton(onClick = {
          val id = item.Id
          if (id != null) {
            viewModel.deleteItem(server, id)
            onDeleted(id)
          }
          onDismissRequest()
        }) {
          Text("删除", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = onDismissRequest) { Text("取消") } },
    )
  }
}

private enum class MediaPage { MAIN, EDIT_METADATA, EDIT_IMAGES, IDENTIFY, REFRESH, DELETE }

/**
 * 长按媒体弹出的**主菜单**：锚在长按位置旁、宽度只包住「图标 + 文字」的小菜单。
 *
 * 用 [DropdownMenu] 而不是 AlertDialog：后者是居中大卡片（最小宽度按 M3 规范就有
 * 280dp 起），而这里只有几个短条目，宽度应该跟着最长的那行文字走。
 * 菜单只负责「选哪个操作」—— 除了收藏 / 已看这种即时开关，其余都交给
 * 底部上划的功能窗（ModalBottomSheet）去承载。
 */
@Composable
private fun EmbyMediaActionMenu(
  item: EmbyItem,
  server: EmbyServer,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onChanged: (EmbyItem) -> Unit,
  onOpen: (MediaPage) -> Unit,
) {
  val context = LocalContext.current
  // 用 ViewModel 作用域而非 rememberCoroutineScope()：菜单/抽屉一点完就关闭，
  // 界面作用域随之被取消，协程切回主线程时会抛 LeftCompositionCancellationException，
  // 明明服务端已生效却被当成失败。「发出去就该跑完」的动作必须挂在界面生命周期之外。
  val scope = viewModel.viewModelScope
  val name = item.Name ?: ""
  val isFavorite = item.UserData?.IsFavorite == true
  val isPlayed = item.UserData?.Played == true

  DropdownMenu(expanded = true, onDismissRequest = onDismissRequest) {
    DropdownMenuItem(
      text = { Text(if (isFavorite) "取消收藏" else "添加到收藏") },
      leadingIcon = {
        Icon(
          if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
          contentDescription = null,
          tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      onClick = {
        runEmbyLibraryAction(
          context = context,
          scope = scope,
          server = server,
          itemId = item.Id,
          name = name,
          okMessage = if (isFavorite) "已从收藏移除「$name」" else "已加入收藏「$name」",
          action = { s, _ -> viewModel.toggleFavorite(s, item) },
        )
        onDismissRequest()
        onChanged(
          item.copy(UserData = (item.UserData ?: EmbyUserData()).copy(IsFavorite = !isFavorite)),
        )
      },
    )
    DropdownMenuItem(
      text = { Text(if (isPlayed) "标记为未播放" else "标记为已播放") },
      leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
      onClick = {
        val id = item.Id
        if (id != null) {
          runEmbyLibraryAction(
            context = context,
            scope = scope,
            server = server,
            itemId = id,
            name = name,
            okMessage = if (isPlayed) "已标记为未播放" else "已标记为已播放",
            action = { s, itemId -> viewModel.setPlayed(s, itemId, !isPlayed) },
          )
          onDismissRequest()
          onChanged(item.copy(UserData = (item.UserData ?: EmbyUserData()).copy(Played = !isPlayed)))
        }
      },
    )
    DropdownMenuItem(
      text = { Text("编辑元数据") },
      leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
      onClick = { onOpen(MediaPage.EDIT_METADATA) },
    )
    DropdownMenuItem(
      text = { Text("编辑图片") },
      leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
      onClick = { onOpen(MediaPage.EDIT_IMAGES) },
    )
    DropdownMenuItem(
      text = { Text("刮削元数据") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      onClick = { onOpen(MediaPage.IDENTIFY) },
    )
    DropdownMenuItem(
      text = { Text("刷新元数据") },
      leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) },
      onClick = { onOpen(MediaPage.REFRESH) },
    )
    DropdownMenuItem(
      text = { Text("删除", color = MaterialTheme.colorScheme.error) },
      leadingIcon = {
        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
      },
      onClick = { onOpen(MediaPage.DELETE) },
    )
  }
}

/**
 * 功能窗的通用外壳：底部上划（ModalBottomSheet）。
 *
 * 主菜单只负责选操作，真正干活的界面都走这里 —— 表单、网格、确认框都装得下，
 * 而且底部上划比居中弹框更好点（离手指近），内容多时还能直接往下拖关掉。
 */
@Composable
fun EmbyActionSheet(
  title: String,
  onDismissRequest: () -> Unit,
  confirmLabel: String? = null,
  onConfirm: (() -> Unit)? = null,
  confirmEnabled: Boolean = true,
  /** 危险操作（删除）：确认按钮换成 error 色，避免手滑 */
  confirmError: Boolean = false,
  /** 传 null 表示不要取消按钮，改成单个通栏按钮（对齐参考图的「搜索」） */
  dismissLabel: String? = "取消",
  content: @Composable ColumnScope.() -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.height(12.dp))
      content()
      if (onConfirm != null) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (dismissLabel != null) {
            OutlinedButton(onClick = onDismissRequest, modifier = Modifier.weight(1f)) {
              Text(dismissLabel)
            }
          }
          Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = if (confirmError) {
              androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
              )
            } else {
              androidx.compose.material3.ButtonDefaults.buttonColors()
            },
          ) {
            Text(confirmLabel ?: "确定")
          }
        }
      } else {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

/**
 * 编辑元数据对话框。
 *
 * 保存走的是 `PUT /Items/{Id}` —— Emby 会用传过去的对象**整体覆盖**，
 * 所以这里必须从原 item 上 copy 出一份改，绝不能只构造一个新对象（会丢字段）。
 */
@Composable
fun EmbyEditMetadataDialog(
  item: EmbyItem,
  onDismissRequest: () -> Unit,
  onSave: (EmbyItem) -> Unit,
) {
  var title by remember { mutableStateOf(item.Name ?: "") }
  var originalTitle by remember { mutableStateOf(item.OriginalTitle ?: "") }
  var year by remember { mutableStateOf(item.ProductionYear?.toString() ?: "") }
  var overview by remember { mutableStateOf(item.Overview ?: "") }
  var genres by remember { mutableStateOf(item.Genres.joinToString("、")) }
  var tags by remember { mutableStateOf(item.Tags.joinToString("、")) }
  var officialRating by remember { mutableStateOf(item.OfficialRating ?: "") }

  EmbyActionSheet(
    title = "编辑元数据",
    onDismissRequest = onDismissRequest,
    confirmLabel = "保存",
    onConfirm = {
      onSave(
        item.copy(
          Name = title.ifBlank { item.Name },
          OriginalTitle = originalTitle.ifBlank { null },
          ProductionYear = year.toIntOrNull(),
          Overview = overview.ifBlank { null },
          OfficialRating = officialRating.ifBlank { null },
          Genres = splitList(genres),
          Tags = splitList(tags),
        ),
      )
    },
  ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("片名") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = originalTitle,
          onValueChange = { originalTitle = it },
          label = { Text("原名") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = year,
          onValueChange = { year = it.filter(Char::isDigit) },
          label = { Text("年份") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = officialRating,
          onValueChange = { officialRating = it },
          label = { Text("分级") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = genres,
          onValueChange = { genres = it },
          label = { Text("类型（顿号分隔）") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = tags,
          onValueChange = { tags = it },
          label = { Text("标签（顿号分隔）") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = overview,
          onValueChange = { overview = it },
          label = { Text("简介") },
          minLines = 4,
          modifier = Modifier.fillMaxWidth(),
        )
      }
  }
}

/** 把「动作、科幻」这类输入切成列表；支持中英文逗号与顿号 */
private fun splitList(raw: String): List<String> =
  raw.split("、", ",", "，", ";", "；")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/**
 * 编辑图片：列出条目当前挂着的各类图片（含来源、尺寸），每张可搜索 / 更换 / 删除。
 *
 * 「搜索」走 Emby 的远程图源（GET /Items/{Id}/RemoteImages），
 * 「更换」是从本机选一张图上传（POST /Items/{Id}/Images/{Type}，base64）。
 */
@Composable
fun EmbyEditImagesDialog(
  server: EmbyServer,
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onImagesChanged: () -> Unit,
) {
  val context = LocalContext.current
  // 用 ViewModel 作用域而非 rememberCoroutineScope()：菜单/抽屉一点完就关闭，
  // 界面作用域随之被取消，协程切回主线程时会抛 LeftCompositionCancellationException，
  // 明明服务端已生效却被当成失败。「发出去就该跑完」的动作必须挂在界面生命周期之外。
  val scope = viewModel.viewModelScope
  val itemId = item.Id

  var images by remember { mutableStateOf(emptyList<EmbyImageInfo>()) }
  var loading by remember { mutableStateOf(true) }
  var busyType by remember { mutableStateOf<String?>(null) }
  /** 非 null 表示正在挑选某类图片的远程候选 */
  var pickingType by remember { mutableStateOf<String?>(null) }
  /**
   * 换图 / 删图后的「最新条目」。
   *
   * 预览地址要用它的图片 tag，不能一直用打开抽屉时传进来的那个 item ——
   * 那个是旧的，tag 还是换图前的，地址不变，图片库就直接把旧图从缓存里翻出来。
   */
  var currentItem by remember(item) { mutableStateOf(item) }
  /** 每次刷新递增：即使 tag 恰好没变，换个地址也能逼图片库重新下载 */
  var imageSeed by remember { mutableStateOf(0) }

  suspend fun reload() {
    if (itemId == null) return
    loading = true
    images = viewModel.loadItemImages(server, itemId)
    // 关键：拿新的图片 tag（见 [currentItem] 的说明）
    viewModel.loadItem(server, itemId)?.let { currentItem = it }
    imageSeed++
    loading = false
  }

  LaunchedEffect(itemId) { reload() }

  fun refreshAfterChange() {
    scope.launch {
      reload()
      onImagesChanged()
    }
  }

  // 本机选图：拿到 Uri 后读成字节上传（Emby 这条接口要 base64，ViewModel/Client 里处理）
  val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val type = busyType ?: return@rememberLauncherForActivityResult
    if (itemId == null) return@rememberLauncherForActivityResult
    scope.launch {
      val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      }.getOrNull()
      if (bytes == null) {
        Toast.makeText(context, "读取图片失败", Toast.LENGTH_SHORT).show()
        return@launch
      }
      val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
      val ok = viewModel.uploadItemImage(server, itemId, type, bytes, mime)
      Toast.makeText(
        context,
        if (ok) "已更换${EmbyImageType.entries.firstOrNull { it.apiName == type }?.label ?: type}"
        else "更换失败，可能需要管理员权限",
        Toast.LENGTH_LONG,
      ).show()
      busyType = null
      if (ok) refreshAfterChange()
    }
  }

  val picking = pickingType
  if (picking != null && itemId != null) {
    EmbyRemoteImagePickerDialog(
      server = server,
      itemId = itemId,
      imageType = picking,
      viewModel = viewModel,
      onDismissRequest = { pickingType = null },
      onPicked = { info ->
        val url = info.Url
        pickingType = null
        if (url == null) {
          Toast.makeText(context, "这张图没有可下载的地址", Toast.LENGTH_SHORT).show()
        } else {
          scope.launch {
            // ProviderName 必须跟着这张图走：图源是 MetaTube 却告诉服务器 Manual，
            // 服务器会拿错的图源去取图，多半直接失败
            val ok = viewModel.downloadRemoteImage(
              server = server,
              itemId = itemId,
              imageType = picking,
              imageUrl = url,
              providerName = info.ProviderName,
            )
            Toast.makeText(
              context,
              if (ok) "已应用新的${EmbyImageType.entries.firstOrNull { it.apiName == picking }?.label ?: picking}"
              else "应用失败，可能需要管理员权限",
              Toast.LENGTH_LONG,
            ).show()
            if (ok) refreshAfterChange()
          }
        }
      },
    )
    return
  }

  EmbyActionSheet(
    title = "编辑图片",
    onDismissRequest = onDismissRequest,
    dismissLabel = "关闭",
  ) {
    if (loading) {
      Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      // 分组标题（参考图里网格上方那行「图片」）
      Text(
        text = "图片",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp),
      )
      // 抽屉高度 = 刚好一行两列。格子是 2:3 海报，行高 = 格宽 × 1.5 + 文字与按钮那一截；
      // 写死数值会随屏宽变化露出半行（460dp 在常见屏上正好是一行半），所以按屏宽算。
      val cellWidthDp = (LocalConfiguration.current.screenWidthDp - 32 - 12) / 2f
      val rowHeightDp = cellWidthDp * 1.5f + 76
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
          .fillMaxWidth()
          .height(rowHeightDp.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(EmbyImageType.entries) { type ->
          val info = images.firstOrNull { it.ImageType.equals(type.apiName, ignoreCase = true) }
          ImageTypeCell(
            label = type.label,
            previewUrl = if (itemId != null && info != null) {
              // 用最新条目的 tag；再挂个递增参数，保证 tag 恰好没变时也会重新下载
              viewModel.imageUrl(server, currentItem, type.apiName, 200)?.let { url ->
                if (url.contains("?")) "$url&_r=$imageSeed" else "$url?_r=$imageSeed"
              }
            } else {
              null
            },
            sourceText = if (info != null) {
              buildString {
                append(if (info.ProviderName.isNullOrBlank()) "来源未知" else info.ProviderName)
                if (info.Width != null && info.Height != null) append(" · ${info.Width}×${info.Height}")
              }
            } else {
              "未设置"
            },
            hasImage = info != null,
            busy = busyType == type.apiName,
            onSearch = {
              busyType = null
              pickingType = type.apiName
            },
            onReplace = {
              busyType = type.apiName
              runCatching { pickLauncher.launch("image/*") }
            },
            onDelete = {
              val id = itemId
              if (id != null) {
                scope.launch {
                  val ok = viewModel.deleteItemImage(server, id, type.apiName)
                  Toast.makeText(
                    context,
                    if (ok) "已删除${type.label}" else "删除失败，可能需要管理员权限",
                    Toast.LENGTH_LONG,
                  ).show()
                  if (ok) refreshAfterChange()
                }
              }
            },
          )
        }
      }
    }
  }
}
/**
 * 图片类型格子（两列网格）：**上面的图 + 居中的名称 + 下面两个圆形按钮**。
 *
 * 对齐参考 app 的「编辑图片」：搜索（远程图源里挑一张）、添加/更换（本机选图上传），
 * 已有图片时再多一个删除。点整块图片区 = 看大图（走 onSearch 之外的预览不做，这里不拦）。
 */
@Composable
private fun ImageTypeCell(
  label: String,
  previewUrl: String?,
  sourceText: String,
  /** 这一类当前是否已有图；有图时按钮为「搜索 / 更换 / 删除」三个 */
  hasImage: Boolean,
  busy: Boolean,
  onSearch: () -> Unit,
  onReplace: () -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Card(
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (previewUrl != null) {
          EmbyImage(
            url = previewUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            maxWidth = 300,
          )
        } else {
          Icon(
            Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp),
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = sourceText,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 搜索：从元数据图源里挑一张
      IconButton(onClick = onSearch) {
        Icon(
          Icons.Default.Search,
          contentDescription = "搜索图片",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
      // 更换：从本机选一张传上去（没图时就是「添加」）
      IconButton(onClick = onReplace, enabled = !busy) {
        Icon(
          Icons.Default.Add,
          contentDescription = if (hasImage) "更换图片" else "添加图片",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
      }
      // 有图才给删除；正在上传时这个位置让给进度圈，避免误触
      if (hasImage) {
        if (busy) {
          Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
          }
        } else {
          IconButton(onClick = onDelete) {
            Icon(
              Icons.Default.Delete,
              contentDescription = "删除图片",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * 远程图源候选：把某类图片的可选图列出来，点一张即下载挂载。
 *
 * 图源列表来自服务器（/RemoteImages/Providers），为空说明服务端没配元数据图源，
 * 这时直接按「全部图源」搜一次，让服务器自己决定。
 */
@Composable
fun EmbyRemoteImagePickerDialog(
  server: EmbyServer,
  itemId: String,
  imageType: String,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onPicked: (EmbyRemoteImageInfo) -> Unit,
) {
  var candidates by remember { mutableStateOf<List<EmbyRemoteImageInfo>?>(null) }
  var errorText by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(itemId, imageType) {
    viewModel.searchRemoteImages(server, itemId, imageType)
      .onSuccess { candidates = it }
      .onFailure {
        errorText = it.message ?: "搜索失败"
        candidates = emptyList()
      }
  }

  val label = EmbyImageType.entries.firstOrNull { it.apiName == imageType }?.label ?: imageType

  EmbyActionSheet(
    title = "选择$label",
    onDismissRequest = onDismissRequest,
  ) {
    val list = candidates
    if (list == null) {
      Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else if (list.isEmpty()) {
      Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(
          errorText ?: "没有找到可用图片（服务器可能未配置元数据图源）",
          style = MaterialTheme.typography.bodyMedium,
          color = if (errorText != null) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
          .fillMaxWidth()
          .height(380.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        itemsIndexed(list) { _, info ->
          // 优先用缩略图：图源给的 Url 往往是几 MB 的原图，网格里一次性加载十几张会卡
          val url = info.ThumbnailUrl?.takeIf { it.isNotBlank() }
            ?: info.Url
            ?: return@itemsIndexed
          RemoteImageCell(
            url = url,
            caption = buildString {
              info.ProviderName?.takeIf { it.isNotBlank() }?.let { append(it) }
              if (info.Width != null && info.Height != null) {
                if (isNotEmpty()) append(" · ")
                append("${info.Width}×${info.Height}")
              }
              if (isEmpty()) append("未知图源")
            },
            onClick = { onPicked(info) },
          )
        }
      }
    }
  }
}

/** 远程候选图：**上图下文**（三列网格用） */
@Composable
private fun RemoteImageCell(
  url: String,
  caption: String,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Card(
      onClick = onClick,
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      EmbyImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        maxWidth = 300,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = caption,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * 刮削元数据（Emby Web 端叫「识别」）：按标题 / 年份 / 外部 ID 检索，选中后应用。
 *
 * 走的是 `POST /Items/RemoteSearch/{Type}` + `POST /Items/RemoteSearch/Apply/{Id}`，
 * 和「刷新元数据」的区别是：**这里可以指定匹配到哪一部**，
 * 适用于刮削错了（匹配成同名另一部）的情况。
 */
@Composable
fun EmbyIdentifyDialog(
  server: EmbyServer,
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onApplied: () -> Unit,
) {
  val context = LocalContext.current
  // 用 ViewModel 作用域而非 rememberCoroutineScope()：菜单/抽屉一点完就关闭，
  // 界面作用域随之被取消，协程切回主线程时会抛 LeftCompositionCancellationException，
  // 明明服务端已生效却被当成失败。「发出去就该跑完」的动作必须挂在界面生命周期之外。
  val scope = viewModel.viewModelScope
  val itemId = item.Id

  var title by remember { mutableStateOf(item.Name ?: "") }
  var year by remember { mutableStateOf(item.ProductionYear?.toString() ?: "") }
  // 已刮削过的外部 ID 预填进去：值在 item.ProviderIds 上（ExternalIdInfos 只描述「支持哪些」，
  // 返回体里并没有当前值），用户想微调时不用自己去翻条目详情
  var imdb by remember { mutableStateOf(providerIdOf(item, "imdb")) }
  var tmdb by remember { mutableStateOf(providerIdOf(item, "tmdb")) }
  var tvdb by remember { mutableStateOf(providerIdOf(item, "tvdb")) }

  var searching by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var results by remember { mutableStateOf<List<EmbyRemoteSearchResult>?>(null) }
  /** 选中候选项后先弹确认框，这里暂存待应用的那个结果 */
  var pending by remember { mutableStateOf<EmbyRemoteSearchResult?>(null) }

  fun search() {
    if (itemId == null) return
    scope.launch {
      searching = true
      errorText = null
      val ids = buildMap {
        if (imdb.isNotBlank()) put("Imdb", imdb.trim())
        if (tmdb.isNotBlank()) put("Tmdb", tmdb.trim())
        if (tvdb.isNotBlank()) put("Tvdb", tvdb.trim())
      }
      viewModel.remoteSearchMetadata(
        server = server,
        searchType = remoteSearchTypeOf(item),
        itemId = itemId,
        lookup = EmbyItemLookupInfo(
          Name = title.ifBlank { item.Name },
          Year = year.toIntOrNull(),
          ProviderIds = ids.ifEmpty { null },
          // 剧集按季号 / 集号帮服务器定位，不然整部剧的同名集会一起返回
          ParentIndexNumber = item.ParentIndexNumber,
          IndexNumber = item.IndexNumber,
        ),
      ).onSuccess { results = it }
        .onFailure { errorText = it.message ?: "搜索失败" }
      searching = false
    }
  }

  // 选中候选项后先确认，再真的应用
  val pendingResult = pending
  if (pendingResult != null) {
    EmbyIdentifyApplyDialog(
      result = pendingResult,
      onDismissRequest = { pending = null },
      onConfirm = { replaceImages ->
        val id = itemId
        pending = null
        results = null
        if (id != null) {
          scope.launch {
            val ok = viewModel.applyRemoteSearchResult(
              server = server,
              itemId = id,
              result = pendingResult,
              replaceAllImages = replaceImages,
            )
            Toast.makeText(
              context,
              if (ok) "已应用识别结果，服务器正在刷新元数据" else "应用失败，可能需要管理员权限",
              Toast.LENGTH_LONG,
            ).show()
            if (ok) {
              onApplied()
              onDismissRequest()
            }
          }
        }
      },
    )
    return
  }

  val list = results
  if (list != null) {
    EmbyIdentifyResultDialog(
      results = list,
      onDismissRequest = { results = null },
      onPick = { result -> pending = result },
    )
    return
  }

  EmbyActionSheet(
    title = "刮削元数据",
    onDismissRequest = onDismissRequest,
    confirmLabel = "搜索",
    onConfirm = ::search,
    confirmEnabled = !searching,
    dismissLabel = null,
  ) {
      // 外层 Box：搜索时把转圈叠在表单正中。
      // 之前转圈排在长表单的最后一项，等于贴在抽屉最底下，用户根本看不见。
      Box(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        // 把文件路径亮出来：刮削刮错了最常见的原因就是文件名本身不对，
        // 用户能在这里一眼看到服务器实际拿到的是什么
        item.Path?.takeIf { it.isNotBlank() }?.let { path ->
          Text(
            text = "路径：$path",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
          text = "留空的外部 ID 不参与匹配；填了 ID 会优先按 ID 精确匹配。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = year, onValueChange = { year = it.filter(Char::isDigit) }, label = { Text("年份") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = imdb, onValueChange = { imdb = it }, label = { Text("IMDb Id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = tmdb, onValueChange = { tmdb = it }, label = { Text("TheMovieDb Id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = tvdb, onValueChange = { tvdb = it }, label = { Text("TheTVDB Id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        // 「是否替换图片」不在这里问 —— 放到选中候选后的确认框里，跟具体的那一部一起决定
        // 出错要说得清：空结果 = 真没匹配上，报错 = 权限或元数据源没配
        errorText?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      // 转圈叠在表单正中（matchParentSize 让它铺满上面那一列的高度）
      if (searching) {
        Box(
          modifier = Modifier.matchParentSize(),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      }
      }
  }
}

/**
 * 应用识别结果前的最后一道确认。
 *
 * 「替换现有图片」默认**打开**：既然是重新识别，多数情况下就是要连新刮到的那套
 * 海报一起换掉；关掉则只更新文字信息，保留现有封面（自己上传过的图尤其需要关掉）。
 */
@Composable
private fun EmbyIdentifyApplyDialog(
  result: EmbyRemoteSearchResult,
  onDismissRequest: () -> Unit,
  onConfirm: (replaceAllImages: Boolean) -> Unit,
) {
  var replaceImages by remember { mutableStateOf(true) }
  val provider = result.SearchProviderName?.takeIf { it.isNotBlank() } ?: "元数据源"
  val title = result.Name ?: ""

  EmbyActionSheet(
    title = "应用识别结果",
    onDismissRequest = onDismissRequest,
    confirmLabel = "确定",
    onConfirm = { onConfirm(replaceImages) },
  ) {
      Column {
        Text(
          text = "确定要应用${provider}的《${title}》？",
          style = MaterialTheme.typography.bodyMedium,
        )
        if (result.ProductionYear != null) {
          Text(
            text = "年份：${result.ProductionYear}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { replaceImages = !replaceImages }
            .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "替换现有图片",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
          )
          Switch(checked = replaceImages, onCheckedChange = { replaceImages = it })
        }
        Text(
          text = "关闭则只更新文字信息，保留现有封面",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
  }
}

@Composable
private fun EmbyIdentifyResultDialog(
  results: List<EmbyRemoteSearchResult>,
  onDismissRequest: () -> Unit,
  onPick: (EmbyRemoteSearchResult) -> Unit,
) {
  EmbyActionSheet(
    title = "选择匹配结果",
    onDismissRequest = onDismissRequest,
  ) {
    if (results.isEmpty()) {
      Text(
        "没有找到匹配的结果",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
          .fillMaxWidth()
          .height(380.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(results) { result ->
          Column(modifier = Modifier.fillMaxWidth()) {
            Card(
              onClick = { onPick(result) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
              if (result.ImageUrl.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                  )
                }
              } else {
                EmbyImage(
                  url = result.ImageUrl,
                  contentDescription = null,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                  maxWidth = 300,
                )
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = result.Name ?: "",
              style = MaterialTheme.typography.bodySmall,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = listOfNotNull(
                result.ProductionYear?.toString(),
                result.SearchProviderName,
              ).joinToString(" · "),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/**
 * 远程检索的类型名。
 *
 * 单集（Episode）和季（Season）在 Emby 里没有独立的检索入口 ——
 * 它们挂在整个剧集下面，只能按 Series 去搜，所以这里统一映射过去。
 */
private fun remoteSearchTypeOf(item: EmbyItem): String = when (item.Type) {
  "Episode", "Season" -> "Series"
  "Movie", "Series", "BoxSet", "Person", "Book", "Game",
  "MusicAlbum", "MusicArtist", "MusicVideo", "Trailer",
  -> item.Type!!
  else -> "Movie"
}

/** 取条目已绑定的某个外部 ID；键名大小写不敏感（服务器返回的既有 Imdb 也有 imdb） */
private fun providerIdOf(item: EmbyItem, key: String): String =
  item.ProviderIds.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: ""

/** 供列表项长按判断：这个条目是不是「有元数据的媒体」（文件夹另有自己的菜单） */
fun isMediaItem(item: EmbyItem): Boolean =
  item.Type !in listOf("Folder", "CollectionFolder", "UserView", "BoxSet")
