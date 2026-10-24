package app.marlboroadvance.mpvex.ui.cine.actions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.domain.emby.EmbyImageInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyImageType
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyItemLookupInfo
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSearchResult
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyRemoteImagePickerDialog
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import kotlinx.coroutines.launch

/**
 * 「刮削元数据」的玻璃版。
 *
 * 流程与 Classic 的 `EmbyIdentifyDialog` 一致：填条件 → 搜 → 选候选 → 确认替换图片 → 应用。
 * 差别只在皮：三步都是玻璃 Sheet，候选项带海报缩略图，并且**整个流程留在同一个玻璃壳里**，
 * 不会走到一半突然弹出一个 M3 白框。
 */
@Composable
fun CineIdentifyDialog(
  server: EmbyServer,
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onApplied: () -> Unit,
) {
  val context = LocalContext.current
  // 挂 ViewModel 作用域：抽屉一点完界面作用域就没了，
  // 用 rememberCoroutineScope 会把「服务端已生效」误报成失败
  val scope = viewModel.viewModelScope
  val itemId = item.Id

  var title by remember { mutableStateOf(item.Name ?: "") }
  var year by remember { mutableStateOf(item.ProductionYear?.toString() ?: "") }
  var imdb by remember { mutableStateOf(providerIdOf(item, "imdb")) }
  var tmdb by remember { mutableStateOf(providerIdOf(item, "tmdb")) }
  var tvdb by remember { mutableStateOf(providerIdOf(item, "tvdb")) }

  var searching by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var results by remember { mutableStateOf<List<EmbyRemoteSearchResult>?>(null) }
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
          // 剧集按季号 / 集号帮服务器定位，否则整部剧的同名集会一起返回
          ParentIndexNumber = item.ParentIndexNumber,
          IndexNumber = item.IndexNumber,
        ),
      ).onSuccess { results = it }
        .onFailure { errorText = it.message ?: "搜索失败" }
      searching = false
    }
  }

  val pendingResult = pending
  if (pendingResult != null) {
    CineIdentifyApplySheet(
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
    CineIdentifyResultSheet(
      results = list,
      onDismissRequest = { results = null },
      onPick = { pending = it },
    )
    return
  }

  CineSheetFrame(
    title = "刮削元数据",
    onDismissRequest = onDismissRequest,
    confirmLabel = if (searching) "搜索中…" else "搜索",
    onConfirm = ::search,
    confirmEnabled = !searching,
    dismissLabel = null,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 440.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // 把文件路径亮出来：刮削刮错了最常见的原因就是文件名本身不对
      item.Path?.takeIf { it.isNotBlank() }?.let { path ->
        Text(
          text = "路径：$path",
          style = CineTheme.type.cardMeta,
          color = CineTheme.colors.onSurfaceVariant,
        )
      }
      Text(
        text = "留空的外部 ID 不参与匹配；填了 ID 会优先按 ID 精确匹配。",
        style = CineTheme.type.cardMeta,
        color = CineTheme.colors.onSurfaceVariant,
      )
      CineSheetTextField(value = title, onValueChange = { title = it }, label = "标题")
      CineSheetTextField(
        value = year,
        onValueChange = { year = it.filter(Char::isDigit) },
        label = "年份",
      )
      CineSheetTextField(value = imdb, onValueChange = { imdb = it }, label = "IMDb Id")
      CineSheetTextField(value = tmdb, onValueChange = { tmdb = it }, label = "TheMovieDb Id")
      CineSheetTextField(value = tvdb, onValueChange = { tvdb = it }, label = "TheTVDB Id")
      errorText?.let {
        Text(
          text = it,
          style = CineTheme.type.cardMeta,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/** 候选列表：带海报缩略图，点一行就进确认。 */
@Composable
private fun CineIdentifyResultSheet(
  results: List<EmbyRemoteSearchResult>,
  onDismissRequest: () -> Unit,
  onPick: (EmbyRemoteSearchResult) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  CineSheetFrame(
    title = "选择匹配项",
    onDismissRequest = onDismissRequest,
    // 兼作「返回上一步」：列出空结果时用户必须能退回改条件
    dismissLabel = "返回",
  ) {
    if (results.isEmpty()) {
      Text(
        text = "没有匹配到结果。试着填上 IMDb / TMDB Id 再搜一次。",
        style = type.body,
        color = colors.onSurfaceVariant,
      )
    } else {
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(results.size, key = { "$it" }) { index ->
          val result = results[index]
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(colors.chipSurface.copy(alpha = 0.55f))
              .clickable { onPick(result) }
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(width = 46.dp, height = 66.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.skeleton),
              contentAlignment = Alignment.Center,
            ) {
              if (!result.ImageUrl.isNullOrBlank()) {
                EmbyImage(
                  url = result.ImageUrl,
                  contentDescription = null,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                  maxWidth = 200,
                )
              } else {
                Icon(
                  imageVector = Icons.Rounded.PhotoLibrary,
                  contentDescription = null,
                  tint = colors.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            ) {
              Text(
                text = result.Name ?: "未命名",
                style = type.itemTitle,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              val meta = buildString {
                result.ProductionYear?.let { append(it) }
                result.SearchProviderName?.takeIf { it.isNotBlank() }?.let {
                  if (isNotEmpty()) append("  ·  ")
                  append(it)
                }
              }
              if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = meta, style = type.cardMeta, color = colors.onSurfaceVariant)
              }
            }
          }
        }
      }
    }
  }
}

/** 应用前的最后一道确认（是否连图片一起换）。 */
@Composable
private fun CineIdentifyApplySheet(
  result: EmbyRemoteSearchResult,
  onDismissRequest: () -> Unit,
  onConfirm: (replaceImages: Boolean) -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  // 默认打开：既然是重新识别，多数情况下就是要连新刮到的那套海报一起换掉
  var replaceImages by remember { mutableStateOf(true) }

  CineSheetFrame(
    title = "应用识别结果",
    onDismissRequest = onDismissRequest,
    confirmLabel = "应用",
    onConfirm = { onConfirm(replaceImages) },
  ) {
    Column {
      Text(
        text = result.Name ?: "未命名",
        style = type.itemTitle,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      result.ProductionYear?.let {
        Spacer(Modifier.height(2.dp))
        Text(text = "$it", style = type.cardMeta, color = colors.onSurfaceVariant)
      }
      Spacer(Modifier.height(12.dp))
      CineSheetCheckRow(
        checked = replaceImages,
        onCheckedChange = { replaceImages = it },
        title = "替换现有图片",
        subtitle = "关掉则只更新文字信息，保留当前封面（自己上传过的图建议关掉）",
      )
    }
  }
}

/**
 * 「编辑图片」的玻璃版。
 *
 * 逻辑与 Classic 的 `EmbyEditImagesDialog` 一一对应（列表 / 远程搜索 / 本地上传 / 删除），
 * 只是每类图片的卡片换成了玻璃底 + 药丸小按钮。
 */
@Composable
fun CineEditImagesDialog(
  server: EmbyServer,
  item: EmbyItem,
  viewModel: EmbyViewModel,
  onDismissRequest: () -> Unit,
  onImagesChanged: () -> Unit,
) {
  val context = LocalContext.current
  val scope = viewModel.viewModelScope
  val itemId = item.Id

  var images by remember { mutableStateOf(emptyList<EmbyImageInfo>()) }
  var loading by remember { mutableStateOf(true) }
  var busyType by remember { mutableStateOf<String?>(null) }
  var pickingType by remember { mutableStateOf<String?>(null) }
  // 预览地址要用最新条目的 tag，否则换图后地址不变、图片库仍给旧图
  var currentItem by remember(item) { mutableStateOf(item) }
  var imageSeed by remember { mutableStateOf(0) }

  suspend fun reload() {
    if (itemId == null) return
    loading = true
    images = viewModel.loadItemImages(server, itemId)
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

  val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val targetType = busyType ?: return@rememberLauncherForActivityResult
    val id = itemId ?: return@rememberLauncherForActivityResult
    scope.launch {
      val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      }.getOrNull()
      if (bytes == null) {
        Toast.makeText(context, "读取图片失败", Toast.LENGTH_SHORT).show()
        busyType = null
        return@launch
      }
      val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
      val ok = viewModel.uploadItemImage(server, id, targetType, bytes, mime)
      Toast.makeText(
        context,
        if (ok) "已更换${EmbyImageType.entries.firstOrNull { it.apiName == targetType }?.label ?: targetType}"
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

  CineSheetFrame(
    title = "编辑图片",
    onDismissRequest = onDismissRequest,
    dismissLabel = "关闭",
  ) {
    if (loading) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        AppLoadingIndicator()
      }
    } else {
      val cellWidthDp = (LocalConfiguration.current.screenWidthDp - 40 - 12) / 2f
      val rowHeightDp = cellWidthDp * 1.5f + 84

      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
          .fillMaxWidth()
          .height(rowHeightDp.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(EmbyImageType.entries) { imageType ->
          val info = images.firstOrNull { it.ImageType.equals(imageType.apiName, ignoreCase = true) }
          val previewUrl = if (itemId != null && info != null) {
            viewModel.imageUrl(server, currentItem, imageType.apiName, 200)?.let { url ->
              if (url.contains("?")) "$url&_r=$imageSeed" else "$url?_r=$imageSeed"
            }
          } else {
            null
          }
          CineImageTypeCell(
            label = imageType.label,
            previewUrl = previewUrl,
            sourceText = if (info != null) imageSourceText(info) else "未设置",
            hasImage = info != null,
            busy = busyType == imageType.apiName,
            onSearch = { pickingType = imageType.apiName },
            onReplace = {
              busyType = imageType.apiName
              pickLauncher.launch("image/*")
            },
            onDelete = {
              if (itemId == null) return@CineImageTypeCell
              busyType = imageType.apiName
              scope.launch {
                val ok = viewModel.deleteItemImage(server, itemId, imageType.apiName, info?.ImageIndex ?: 0)
                Toast.makeText(
                  context,
                  if (ok) "已删除${imageType.label}" else "删除失败，可能需要管理员权限",
                  Toast.LENGTH_LONG,
                ).show()
                busyType = null
                if (ok) refreshAfterChange()
              }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun CineImageTypeCell(
  label: String,
  previewUrl: String?,
  sourceText: String,
  hasImage: Boolean,
  busy: Boolean,
  onSearch: () -> Unit,
  onReplace: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .clip(RoundedCornerShape(12.dp))
        .background(colors.skeleton),
      contentAlignment = Alignment.Center,
    ) {
      if (previewUrl != null) {
        EmbyImage(
          url = previewUrl,
          contentDescription = label,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          maxWidth = 320,
        )
      } else {
        Icon(
          imageVector = Icons.Rounded.PhotoLibrary,
          contentDescription = null,
          tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(24.dp),
        )
      }
      // 类别标签压在左上角：一屏多格，不标就分不清哪格是封面哪格是背景
      Text(
        text = label,
        style = type.badge.copy(fontSize = 9.sp),
        color = Color.White,
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(6.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(Color.Black.copy(alpha = 0.55f))
          .padding(horizontal = 6.dp, vertical = 3.dp),
      )
    }

    Spacer(Modifier.height(6.dp))
    Text(
      text = sourceText,
      style = type.cardMeta,
      color = colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      CineMiniButton(text = "搜索", enabled = !busy, onClick = onSearch)
      CineMiniButton(text = "更换", enabled = !busy, onClick = onReplace)
      if (hasImage) {
        CineMiniButton(text = "删除", enabled = !busy, onClick = onDelete)
      }
    }
  }
}

/** 26dp 高的小药丸：图片格子里塞三个标准按钮会横向溢出，只能用小号。 */
@Composable
private fun CineMiniButton(
  text: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val shape = remember { RoundedCornerShape(50) }

  Box(
    modifier = Modifier
      .height(26.dp)
      .clip(shape)
      .background(colors.chipSurface)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = type.cardMeta.copy(fontSize = 11.sp),
      color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f),
      maxLines = 1,
    )
  }
}

private fun imageSourceText(info: EmbyImageInfo): String = buildString {
  info.ProviderName?.takeIf { it.isNotBlank() }?.let { append(it) }
  val size = if (info.Width != null && info.Height != null) "${info.Width}×${info.Height}" else null
  if (size != null) {
    if (isNotEmpty()) append("  ")
    append(size)
  }
  if (isEmpty()) append("已设置")
}

/**
 * 远程检索的类型名要跟服务器口径一致：单集 / 季都按「Series」去搜，
 * 否则服务器会当成一部电影去找，结果必然是空的。
 */
private fun remoteSearchTypeOf(item: EmbyItem): String {
  val raw = item.Type.orEmpty()
  return when (raw) {
    "Episode", "Season" -> "Series"
    "Movie", "Series", "BoxSet", "Person", "Book", "Game",
    "MusicAlbum", "MusicArtist", "MusicVideo", "Trailer",
    -> raw
    else -> "Movie"
  }
}

private fun providerIdOf(item: EmbyItem, key: String): String =
  item.ProviderIds.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: ""
