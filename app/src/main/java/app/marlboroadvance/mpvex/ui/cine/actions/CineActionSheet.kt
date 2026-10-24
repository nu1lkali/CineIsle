package app.marlboroadvance.mpvex.ui.cine.actions

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyPersonScreen
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.runEmbyLibraryAction
import app.marlboroadvance.mpvex.ui.cine.CineMediaUi
import app.marlboroadvance.mpvex.ui.cine.CinePlayController
import app.marlboroadvance.mpvex.ui.cine.CineTheme
import app.marlboroadvance.mpvex.ui.cine.rememberCineScreenHeightDp
import app.marlboroadvance.mpvex.ui.theme.LocalCardCorner
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch

/**
 * Cine 的长按菜单：**功能对齐 Classic 的 `EmbyMediaActionsDialog`，外观换成玻璃面板**。
 *
 * ## 为什么单独一份而不是复用 Classic 的 Dialog
 * Classic 的主菜单是 [androidx.compose.material3.DropdownMenu]（锚在手指位置的小浮层、
 * M3 实色底）。Cine 的层级语言是「玻璃 + 上滑纸张」，两种形态放一起会打架。
 * 但**功能不能少**：收藏、已看、演职员跳转、刮削、刷新、编辑元数据 / 图片、删除，
 * 一个都不能缺 —— 所以这里只重画主菜单，二级表单（元数据 / 图片 / 刮削 / 刷新）
 * 也一并换成玻璃壳（见 [CineSheetFrame] / [CineIdentifyDialog] / [CineEditImagesDialog]），
 * 逻辑仍逐条对应 Classic，功能一个不少。
 *
 * ## 状态为什么提到外面
 * 五个页面（首页 / 媒体库 / 收藏 / 历史 / 搜索）都要长按，如果每页各存一份
 * `actionTarget`，接线会重复五遍，而且迟早出现「这页关了那页没关」。
 * [CineActionSheetState] 由页面 `remember` 一份，[CineActionSheetHost] 负责渲染。
 *
 * ## ⚠️ 写服务器的动作必须挂在 ViewModel 作用域
 * 菜单一点完就关闭，界面作用域随之取消 —— 用 `rememberCoroutineScope()` 的协程会在
 * 切回主线程时抛 `LeftCompositionCancellationException`，明明服务端已经生效却当成失败。
 * Classic 那边踩过这个坑，这里统一用 `viewModel.viewModelScope`。
 */
@Stable
class CineActionSheetState {
  var target by mutableStateOf<CineMediaUi?>(null)
    private set

  fun show(media: CineMediaUi) {
    target = media
  }

  fun dismiss() {
    target = null
  }
}

@Composable
fun rememberCineActionSheetState(): CineActionSheetState = remember { CineActionSheetState() }

/**
 * 详情页额外动作（长按菜单里没有的那几项）。
 *
 * ## 为什么做成一个可空对象，而不是给 Host 加七个参数
 * 长按菜单有五个页面在调用（首页 / 媒体库 / 收藏 / 历史 / 搜索），它们统统不需要
 * 下载、不喜欢、外部播放器 —— 参数一多，每个调用点都得写一长串 `null`。
 * 这里一个 `extras = null` 就表达「这是普通长按菜单」。
 *
 * ## 为什么这几项只在详情页有
 * 长按一张海报时用户想的是「这部片子怎么处理」；而「下到本地 / 换个播放器打开」
 * 是看过简介、决定要看的下一步动作，只有详情页有足够上下文承载。
 * 而且长按菜单已经十二项，再塞会变成一堵墙。
 */
@Immutable
data class CineActionExtras(
  /** 下载状态文案（`下载中 42%` / `已暂停` / `已下载`）；`null` = 还没下过 */
  val downloadLabel: String? = null,
  /** 正在下载 / 排队中：图标换成暂停（此时点它是「暂停」而不是「开始下载」） */
  val downloadActive: Boolean = false,
  val onDownload: (() -> Unit)? = null,
  /** 「不喜欢」受设置里的开关控制；`showDislike = false` 时整项不出现 */
  val showDislike: Boolean = false,
  val disliked: Boolean = false,
  val onToggleDislike: (() -> Unit)? = null,
  val onPlayExternal: (() -> Unit)? = null,
)

/** 主菜单之外的「二级页」：与 Classic 的 `MediaPage` 一一对应 */
private enum class CineActionPage { MENU, IDENTIFY, REFRESH, EDIT_METADATA, EDIT_IMAGES, DELETE }

/**
 * 渲染长按菜单。页面只需要把它摆在组合末尾（与页面同层），然后
 * `state.show(media)` 就能弹出来。
 *
 * @param onChanged 收藏 / 已看 / 元数据变了 —— 调用方刷新当前列表
 * @param onDeleted 删除成功 —— 调用方把这条从列表里剔除
 */
@Composable
fun CineActionSheetHost(
  state: CineActionSheetState,
  server: EmbyServer?,
  viewModel: EmbyViewModel,
  playController: CinePlayController,
  onOpenDetail: (CineMediaUi) -> Unit,
  onChanged: () -> Unit,
  onDeleted: (String) -> Unit,
  /** 由详情页打开：隐藏「详情」项（点了只会回到自己这一页） */
  fromDetail: Boolean = false,
  /** 详情页额外动作（下载 / 不喜欢 / 外部播放器）；长按菜单传 null */
  extras: CineActionExtras? = null,
) {
  val media = state.target ?: return
  val current = server ?: return

  val context = LocalContext.current
  // 见类注释：动作的生命周期不能跟着菜单走
  val scope = viewModel.viewModelScope
  val backStack = LocalBackStack.current
  var page by remember(media.id) { mutableStateOf(CineActionPage.MENU) }

  val title = media.title
  val metadataSaved = stringResource(R.string.cine_action_metadata_saved)
  val metadataFailed = stringResource(R.string.cine_action_metadata_failed)
  val refreshDone = stringResource(R.string.cine_action_refresh_done, title)
  val scanDone = stringResource(R.string.cine_action_scan_done, title)

  // 关掉菜单并把二级页复位：下一次长按永远从主菜单开始
  fun close() {
    page = CineActionPage.MENU
    state.dismiss()
  }

  when (page) {
    CineActionPage.MENU -> CineActionMenu(
      media = media,
      onDismiss = ::close,
      onPlay = { fromStart ->
        close()
        if (fromStart) {
          playController.playFromStart(media.item)
        } else {
          playController.play(media.item, media.resumeSeconds)
        }
      },
      onOpenDetail = {
        close()
        onOpenDetail(media)
      },
      onToggleFavorite = {
        close()
        scope.launch {
          val next = !(media.item.UserData?.IsFavorite == true)
          val result = viewModel.toggleFavorite(current, media.item, next)
          if (result.isFailure) {
            Toast.makeText(
              context,
              result.exceptionOrNull()?.message
                ?: context.getString(R.string.cine_favorite_failed),
              Toast.LENGTH_SHORT,
            ).show()
          }
          // 成功失败都刷一次：回读确认后服务端才是真值，界面照它显示
          onChanged()
        }
      },
      onTogglePlayed = {
        close()
        val itemId = media.item.Id
        if (itemId != null) {
          scope.launch {
            viewModel.setPlayed(current, itemId, media.item.UserData?.Played != true)
            onChanged()
          }
        }
      },
      onScan = {
        close()
        runEmbyLibraryAction(
          context = context,
          scope = scope,
          server = current,
          itemId = media.item.Id,
          name = title,
          okMessage = scanDone,
          action = { srv, id -> runCatching { viewModel.scanLibrary(srv, id) } },
        )
      },
      onOpenPerson = { personId, personName, imageTag ->
        close()
        backStack.add(
          EmbyPersonScreen(
            personId = personId,
            personName = personName,
            personImageTag = imageTag,
          ),
        )
      },
      onOpenPage = { page = it },
      fromDetail = fromDetail,
      extras = extras,
    )

    // ── 二级页：全部用 Cine 的玻璃表单（CineDialogs / CineMetadataDialogs） ──
    CineActionPage.IDENTIFY -> CineIdentifyDialog(
      server = current,
      item = media.item,
      viewModel = viewModel,
      onDismissRequest = ::close,
      onApplied = {
        close()
        onChanged()
      },
    )

    CineActionPage.REFRESH -> CineRefreshMetadataDialog(
      name = title,
      kindLabel = stringResource(R.string.cine_action_refresh_kind),
      onDismissRequest = ::close,
      onConfirm = { replaceMetadata, replaceImages ->
        close()
        runEmbyLibraryAction(
          context = context,
          scope = scope,
          server = current,
          itemId = media.item.Id,
          name = title,
          okMessage = refreshDone,
          action = { srv, id ->
            runCatching { viewModel.refreshLibraryMetadata(srv, id, replaceMetadata, replaceImages) }
          },
        )
      },
    )

    CineActionPage.EDIT_METADATA -> CineEditMetadataDialog(
      item = media.item,
      onDismissRequest = ::close,
      onSave = { updated ->
        scope.launch {
          val ok = runCatching { viewModel.updateItemMetadata(current, updated) }
            .getOrDefault(false)
          Toast.makeText(
            context,
            if (ok) metadataSaved else metadataFailed,
            Toast.LENGTH_LONG,
          ).show()
          if (ok) {
            close()
            onChanged()
          }
        }
      },
    )

    CineActionPage.EDIT_IMAGES -> CineEditImagesDialog(
      server = current,
      item = media.item,
      viewModel = viewModel,
      onDismissRequest = ::close,
      onImagesChanged = {
        close()
        onChanged()
      },
    )

    // 删除保留居中确认框：不可逆操作要有一次「停下来看清楚」的阻断，
    // 而不是顺手就能往下拖掉的底部抽屉（与 Classic 的取舍一致）
    CineActionPage.DELETE -> AlertDialog(
      onDismissRequest = ::close,
      title = { Text(stringResource(R.string.cine_action_delete_title)) },
      text = { Text(stringResource(R.string.cine_action_delete_message, title)) },
      confirmButton = {
        TextButton(onClick = {
          val id = media.item.Id
          close()
          if (id != null) {
            viewModel.deleteItem(current, id)
            onDeleted(id)
          }
        }) {
          Text(
            text = stringResource(R.string.cine_action_delete),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(onClick = ::close) {
          Text(stringResource(R.string.cine_action_cancel))
        }
      },
    )
  }
}

/**
 * 主菜单本体：玻璃面板 + 分组动作。
 *
 * ## 玻璃怎么做的
 * `containerColor` 取 `glassSurface`（主题表面的半透明版本），下面压着 BottomSheet
 * 自带的那层 scrim ⇒ 透出来的是「被压暗一档的页面内容」，观感是磨砂而不是实色。
 * 顶角 26dp 与详情页那张上滑面板同一个数 —— 两处浮层必须是同一张「纸」。
 *
 * ## 为什么分组
 * Classic 是一列十项。玻璃面板比 DropdownMenu 宽，十项连排会变成一堵文字墙；
 * 按「播放 / 收藏与状态 / 相关 / 维护 / 危险」分成五组、组间只留一条 1dp 细线，
 * 用户扫一眼就知道哪一段是干什么的。
 */
@Composable
private fun CineActionMenu(
  media: CineMediaUi,
  onDismiss: () -> Unit,
  onPlay: (fromStart: Boolean) -> Unit,
  onOpenDetail: () -> Unit,
  onToggleFavorite: () -> Unit,
  onTogglePlayed: () -> Unit,
  onScan: () -> Unit,
  onOpenPerson: (personId: String, personName: String, imageTag: String?) -> Unit,
  onOpenPage: (CineActionPage) -> Unit,
  fromDetail: Boolean,
  extras: CineActionExtras?,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val corner = LocalCardCorner.current
  val screenHeight = rememberCineScreenHeightDp()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val item = media.item
  val itemType = item.Type.orEmpty()
  val isFolder = itemType == "Folder" || itemType == "CollectionFolder"
  val isPerson = itemType.equals("Person", ignoreCase = true)
  val isPhoto = itemType.equals("Photo", ignoreCase = true)
  // 「媒体」= 能播的东西。容器类（剧集 / 季 / 合集）也算：play() 内部会挑集。
  // 已播放标记、刮削、刷新元数据同理 —— 照片 / 相册没有这些概念，整块都不出现。
  val isPlayable = !isFolder && !isPerson && !isPhoto
  val played = item.UserData?.Played == true
  val favorite = media.isFavorite

  // 演职员跳转：演员 / 导演各自独立判断，缺谁就不出现谁那一行（不留死项）
  val people = item.People.orEmpty()
  val leadActor = people.firstOrNull {
    it.Id != null && it.Type.equals("Actor", ignoreCase = true)
  }
  val leadDirector = people.firstOrNull {
    it.Id != null && it.Type.equals("Director", ignoreCase = true)
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    // 玻璃面板：半透明表面 + 底下那层 scrim = 磨砂观感（不需要 RenderEffect）
    // ModalBottomSheet 在自己的 Dialog 窗口里，取不到页面的 Haze 模糊源
    // （Haze 只能采样同一窗口的绘制内容）⇒ 这里靠「更透的底 + 压暗的 scrim」
    // 做出玻璃观感，把 alpha 从 0.78 降到 glassTint 那一档
    containerColor = colors.glassTint,
    tonalElevation = 0.dp,
    scrimColor = Color.Black.copy(alpha = if (colors.isLight) 0.34f else 0.52f),
    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    // 自己画拖拽条：默认那条是 M3 的实色，压在玻璃上像一道脏印
    dragHandle = {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.glassBorder),
        )
      }
    },
  ) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
      // ── 头部：缩略图 + 标题 + 元信息（确认「我长按的是哪一张」） ──
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = space.screenH)
          .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .width(44.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(corner))
            .background(colors.skeleton),
        ) {
          EmbyImage(
            url = media.posterUrl ?: media.thumbUrl,
            contentDescription = media.title,
            modifier = Modifier.size(44.dp, 66.dp),
            maxWidth = 200,
          )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
          Text(
            text = media.wideTitle,
            style = type.itemTitle,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (media.meta.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
              text = media.meta,
              style = type.cardMeta,
              color = colors.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      CineSheetDivider()

      Column(
        modifier = Modifier
          // 十项动作在矮屏上会顶出屏幕：限高 + 可滚动，保证「删除」永远够得着
          .heightIn(max = (screenHeight * 0.64f).dp)
          .verticalScroll(rememberScrollState()),
      ) {
        if (isFolder) {
          // 文件夹：Classic 只给「扫描 / 刷新元数据」，Cine 额外给一个「打开」，
          // 因为 Cine 的下钻是单击行为，长按里不给出口会显得断头
          CineSheetActionRow(
            icon = Icons.Filled.PlayArrow,
            label = stringResource(R.string.cine_action_open),
            onClick = onOpenDetail,
          )
          CineSheetActionRow(
            icon = Icons.Filled.Search,
            label = stringResource(R.string.cine_action_scan),
            onClick = onScan,
          )
          CineSheetActionRow(
            icon = Icons.Filled.Autorenew,
            label = stringResource(R.string.cine_action_refresh_metadata),
            onClick = { onOpenPage(CineActionPage.REFRESH) },
          )
          CineSheetActionRow(
            icon = Icons.Filled.Edit,
            label = stringResource(R.string.cine_action_edit_metadata),
            onClick = { onOpenPage(CineActionPage.EDIT_METADATA) },
          )
        } else {
          if (isPlayable) {
            CineSheetActionRow(
              icon = Icons.Filled.PlayArrow,
              label = stringResource(
                if (media.hasProgress) R.string.cine_action_resume else R.string.cine_action_play,
              ),
              trailing = if (media.hasProgress) media.remaining else null,
              onClick = { onPlay(false) },
            )
            if (media.hasProgress) {
              CineSheetActionRow(
                icon = Icons.Rounded.Replay,
                label = stringResource(R.string.cine_action_play_from_start),
                onClick = { onPlay(true) },
              )
            }
          }
          // 详情页里「详情」项点下去只会回到自己，那不画它
          if (!fromDetail) {
            CineSheetActionRow(
              icon = Icons.AutoMirrored.Outlined.Article,
              label = stringResource(R.string.cine_action_detail),
              onClick = onOpenDetail,
            )
          }
          CineSheetDivider()

          CineSheetActionRow(
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            label = stringResource(
              if (favorite) R.string.cine_action_unfavorite else R.string.cine_action_favorite,
            ),
            // 只在**已收藏**时上强调色。未收藏传 null ⇒ 文字跟正文同色（onSurface），
            // 图标自己落到 onSurfaceVariant 那一档。⚠️ 别在这里写
            // `else colors.onSurfaceVariant`：tint 是「文字 + 图标」共用的一档色，
            // 传了它文字就跟着变灰，菜单里会出现一行灰字夹在一片白字中间（v135 用户反馈）
            tint = if (favorite) colors.sectionAction else null,
            onClick = onToggleFavorite,
          )
          if (isPlayable) {
            CineSheetActionRow(
              icon = Icons.Filled.Check,
              label = stringResource(
                if (played) {
                  R.string.cine_action_unmark_played
                } else {
                  R.string.cine_action_mark_played
                },
              ),
              onClick = onTogglePlayed,
            )
          }

          // 「标记为不喜欢」：本项目自己的功能（本地库 + 设置里的开关），
          // 与 Emby 的评分无关。开关关掉时整项不出现。
          if (extras != null && extras.showDislike && extras.onToggleDislike != null) {
            val toggleDislike = extras.onToggleDislike
            CineSheetActionRow(
              icon = Icons.Outlined.HeartBroken,
              label = stringResource(
                if (extras.disliked) {
                  R.string.cine_action_undislike
                } else {
                  R.string.cine_action_dislike
                },
              ),
              // 同「收藏」：只有已标记时才上 error 色，未标记保持正文色
              tint = if (extras.disliked) MaterialTheme.colorScheme.error else null,
              onClick = toggleDislike,
            )
          }

          // 下载 / 外部播放器：拿到本地、或换个播放器打开 —— 都是「决定要看」之后的动作
          if (isPlayable && extras != null && (extras.onDownload != null || extras.onPlayExternal != null)) {
            CineSheetDivider()
            extras.onDownload?.let { action ->
              CineSheetActionRow(
                // 正在下载时点它是「暂停」，图标必须跟着变，否则用户以为会重复入队
                icon = if (extras.downloadActive) Icons.Filled.Pause else Icons.Filled.Download,
                label = stringResource(R.string.cine_action_download),
                trailing = extras.downloadLabel,
                onClick = action,
              )
            }
            extras.onPlayExternal?.let { action ->
              CineSheetActionRow(
                icon = Icons.Filled.OpenInNew,
                label = stringResource(R.string.cine_action_play_external),
                onClick = action,
              )
            }
          }

          // 演职员跳转：两个身份各自独立
          if (leadActor != null || leadDirector != null) {
            CineSheetDivider()
            leadActor?.let { person ->
              val name = person.Name.orEmpty()
              CineSheetActionRow(
                icon = Icons.Filled.Person,
                label = if (name.isBlank()) {
                  stringResource(R.string.cine_action_more_from_unknown)
                } else {
                  stringResource(R.string.cine_action_more_from, name)
                },
                onClick = { onOpenPerson(person.Id.orEmpty(), name, person.PrimaryImageTag) },
              )
            }
            leadDirector?.let { person ->
              val name = person.Name.orEmpty()
              CineSheetActionRow(
                icon = Icons.Filled.Person,
                label = if (name.isBlank()) {
                  stringResource(R.string.cine_action_more_directed_unknown)
                } else {
                  stringResource(R.string.cine_action_more_directed, name)
                },
                onClick = { onOpenPerson(person.Id.orEmpty(), name, person.PrimaryImageTag) },
              )
            }
          }

          // 维护类：只对影视有意义（照片没有可在线识别的库、没有播放状态）
          if (isPlayable) {
            CineSheetDivider()
            CineSheetActionRow(
              icon = Icons.Filled.Search,
              label = stringResource(R.string.cine_action_identify),
              onClick = { onOpenPage(CineActionPage.IDENTIFY) },
            )
            CineSheetActionRow(
              icon = Icons.Filled.Autorenew,
              label = stringResource(R.string.cine_action_refresh_metadata),
              onClick = { onOpenPage(CineActionPage.REFRESH) },
            )
          }
          CineSheetActionRow(
            icon = Icons.Filled.Edit,
            label = stringResource(R.string.cine_action_edit_metadata),
            onClick = { onOpenPage(CineActionPage.EDIT_METADATA) },
          )
          // 单张照片本身是 Primary 图，"换海报"等于覆盖自己 ⇒ 只有相册 / 影视给
          if (!isPhoto) {
            CineSheetActionRow(
              icon = Icons.Filled.Image,
              label = stringResource(R.string.cine_action_edit_images),
              onClick = { onOpenPage(CineActionPage.EDIT_IMAGES) },
            )
          }
        }

        CineSheetDivider()
        CineSheetActionRow(
          icon = Icons.Filled.Delete,
          label = stringResource(R.string.cine_action_delete),
          tint = MaterialTheme.colorScheme.error,
          onClick = { onOpenPage(CineActionPage.DELETE) },
        )
      }
    }
  }
}

/** 组内分隔线：1dp、左右内缩，颜色是玻璃描边（不要用 outline，太重） */
@Composable
private fun CineSheetDivider() {
  val colors = CineTheme.colors
  Box(
    modifier = Modifier
      .padding(horizontal = CineTheme.space.screenH)
      .padding(vertical = 6.dp)
      .fillMaxWidth()
      .height(1.dp)
      .background(colors.glassBorder),
  )
}

/**
 * 一行动作：图标 + 文案 + 可选的右侧弱信息。
 *
 * `tint` 是「状态色 / 危险色」，**同时作用于文字和图标**（已收藏的粉心、删除的红字）。
 * 不传时：文字 = [CineTheme.colors]`onSurface`，图标 = `onSurfaceVariant` ——
 * 图标天然比文字轻一档是排版惯例，不是状态。
 *
 * ⚠️ 因此「未激活」只能传 `null`，**不要传 `onSurfaceVariant`**：那会把文字一起降一档，
 * 菜单里就会出现一行灰字夹在一整列白字中间（v135 用户反馈的正是这个）。
 */
@Composable
private fun CineSheetActionRow(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailing: String? = null,
  tint: Color? = null,
) {
  val colors = CineTheme.colors
  val type = CineTheme.type
  val space = CineTheme.space
  val content = tint ?: colors.onSurface

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = space.screenH, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = tint ?: colors.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = label,
      style = type.itemTitle,
      color = content,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (trailing != null) {
      Text(
        text = trailing,
        style = type.cardMeta,
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}
