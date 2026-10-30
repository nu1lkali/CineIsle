package app.marlboroadvance.mpvex.ui.browser.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.theme.LocalThemeTransitionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Unified top bar for browser screens that switches between normal and selection modes
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserTopBar(
  title: String,
  isInSelectionMode: Boolean,
  selectedCount: Int,
  totalCount: Int,
  onCancelSelection: () -> Unit,
  modifier: Modifier = Modifier,
  onBackClick: (() -> Unit)? = null,
  onSortClick: (() -> Unit)? = null,
  onSearchClick: (() -> Unit)? = null,
  onSettingsClick: (() -> Unit)? = null,
  onDeleteClick: (() -> Unit)? = null,
  onRenameClick: (() -> Unit)? = null,
  /**
   * 复制 / 移动（只有需要「把条目搬到另一个目录」的页面才传，如网络浏览的 AList）。
   *
   * 单独开两个槽位而不是复用 `additionalActions`：`additionalActions` 只在**普通**顶栏
   * 渲染，选中态的顶栏是另一套布局（[SelectionTopBar]）。而复制 / 移动恰恰只在
   * 选中态才有意义 —— 塞进普通顶栏的追加区等于放错地方，永远不会显示。
   */
  onCopyClick: (() -> Unit)? = null,
  onMoveClick: (() -> Unit)? = null,
  isSingleSelection: Boolean = false,
  onInfoClick: (() -> Unit)? = null,
  onShareClick: (() -> Unit)? = null,
  onPlayClick: (() -> Unit)? = null,
  onBlacklistClick: (() -> Unit)? = null,
  onSelectAll: (() -> Unit)? = null,
  onInvertSelection: (() -> Unit)? = null,
  onDeselectAll: (() -> Unit)? = null,
  additionalActions: @Composable RowScope.() -> Unit = { },
  onTitleLongPress: (() -> Unit)? = null,
  useRemoveIcon: Boolean = false,
  onAddToPlaylistClick: (() -> Unit)? = null,
  /** 点标题的回调；非空时**接管**标题点击（不再切换深/浅色），并显示一个下拉小箭头提示可点。 */
  onTitleClick: (() -> Unit)? = null,
  /**
   * 标题是否用「小两档」字号（20sp）。默认走 `headlineSmall` = 24sp。
   * 给「标题常年是长文件夹名、右侧图标又多」的页面用（网络浏览）。
   */
  compactTitle: Boolean = false,
) {
  if (isInSelectionMode) {
    SelectionTopBar(
      selectedCount = selectedCount,
      totalCount = totalCount,
      onCancel = onCancelSelection,
      onDelete = onDeleteClick,
      onRename = onRenameClick,
      onCopy = onCopyClick,
      onMove = onMoveClick,
      isSingleSelection = isSingleSelection,
      onInfo = onInfoClick,
      onShare = onShareClick,
      onPlay = onPlayClick,
      onBlacklist = onBlacklistClick,
      onSelectAll = onSelectAll,
      onInvertSelection = onInvertSelection,
      onDeselectAll = onDeselectAll,
      modifier = modifier,
      useRemoveIcon = useRemoveIcon,
      onAddToPlaylist = onAddToPlaylistClick,
    )
  } else {
    NormalTopBar(
      title = title,
      onBackClick = onBackClick,
      onSortClick = onSortClick,
      onSearchClick = onSearchClick,
      onSettingsClick = onSettingsClick,
      additionalActions = additionalActions,
      modifier = modifier,
      onTitleLongPress = onTitleLongPress,
      onTitleClick = onTitleClick,
      compactTitle = compactTitle,
    )
  }
}

/**
 * Normal mode top bar
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NormalTopBar(
  title: String,
  onBackClick: (() -> Unit)?,
  onSortClick: (() -> Unit)?,
  onSearchClick: (() -> Unit)?,
  onSettingsClick: (() -> Unit)?,
  additionalActions: @Composable RowScope.() -> Unit,
  modifier: Modifier = Modifier,
  onTitleLongPress: (() -> Unit)?,
  onTitleClick: (() -> Unit)? = null,
  compactTitle: Boolean = false,
) {
  val preferences = koinInject<AppearancePreferences>()
  val darkMode by preferences.darkMode.collectAsState()
  val darkTheme = isSystemInDarkTheme()
  val themeTransition = LocalThemeTransitionState.current
  val coroutineScope = rememberCoroutineScope()
  
  // Track title bounds for animation position
  val titleBounds = remember { mutableStateOf(Rect.Zero) }
  
  // Helper function to toggle dark mode
  fun toggleDarkMode() {
    when (darkMode) {
      DarkMode.System -> if (darkTheme) {
        preferences.darkMode.set(DarkMode.Light)
      } else {
        preferences.darkMode.set(DarkMode.Dark)
      }
      DarkMode.Light -> if (darkTheme) {
        preferences.darkMode.set(DarkMode.System)
      } else {
        preferences.darkMode.set(DarkMode.Dark)
      }
      DarkMode.Dark -> if (darkTheme) {
        preferences.darkMode.set(DarkMode.Light)
      } else {
        preferences.darkMode.set(DarkMode.System)
      }
    }
  }

  TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = if (MaterialTheme.colorScheme.background == Color.Black) {
        Color.Black
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    ),
    title = {
      // 标题整块可点：
      //  · 默认 = 切换深/浅色（圆形揭露动画，起点取点击处）；
      //  · 传了 onTitleClick 的页面（如网络浏览）= 打开「跳转到」导航弹窗，
      //    并在标题右侧多一个下拉小箭头提示「可点」。此时不再切换深浅色。
      val titleModifier = Modifier
        .onGloballyPositioned { coordinates ->
          titleBounds.value = coordinates.boundsInWindow()
        }
        .pointerInput(onTitleLongPress, onTitleClick) {
          detectTapGestures(
            onTap = { localOffset ->
              if (onTitleClick != null) {
                onTitleClick()
                return@detectTapGestures
              }

              // Don't allow theme change if animation is in progress
              if (themeTransition?.isAnimating == true) return@detectTapGestures
              
              // Calculate window position for circular reveal
              val windowOffset = Offset(
                titleBounds.value.left + localOffset.x,
                titleBounds.value.top + localOffset.y
              )
              themeTransition?.startTransition(windowOffset)
              // Delay theme change to allow overlay to display first
              coroutineScope.launch {
                toggleDarkMode()
              }
            },
            onLongPress = if (onTitleLongPress != null) {
              { onTitleLongPress() }
            } else null
          )
        }

      Row(verticalAlignment = Alignment.CenterVertically, modifier = titleModifier) {
        Text(
          title,
          style =
            if (onBackClick == null) {
              MaterialTheme.typography.headlineMediumEmphasized
            } else if (compactTitle) {
              // 小两档（24sp → 20sp）：长文件夹名 + 右侧一堆图标时留出横向空间。
              // lineHeight 同步收（headlineSmall 原本是 32sp，按字号等比降到 28sp），
              // 否则单行标题会被 32sp 的行高撑得上下留白过多。
              MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp)
            } else {
              MaterialTheme.typography.headlineSmall
            },
          fontWeight = FontWeight.ExtraBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier =
            Modifier
              // ⚠️ 必须给 Text 一个 weight：Row 里**无 weight 的子项**是按「剩余空间」
              // 顺序测量的 —— 标题先测、可以吃掉整行宽度，右边那个下拉小箭头
              // 就只能拿到 0 宽（长标题时箭头消失）。fill = false 保证短标题时
              // Row 仍然按内容宽度收缩，观感与以前一致。
              .weight(1f, fill = false)
              .then(
                if (onBackClick == null) {
                  Modifier.padding(start = 8.dp)
                } else {
                  Modifier
                },
              ),
        )
        if (onTitleClick != null) {
          Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
    navigationIcon = {
      if (onBackClick != null) {
        IconButton(
          onClick = onBackClick,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    },
    actions = {
      additionalActions()
      if (onSearchClick != null) {
        IconButton(
          onClick = onSearchClick,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.Search,
            contentDescription = stringResource(R.string.i18n_search),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      if (onSortClick != null) {
        IconButton(
          onClick = onSortClick,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Default.ViewComfy,
            contentDescription = stringResource(R.string.sort),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      if (onSettingsClick != null) {
        IconButton(
          onClick = onSettingsClick,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.i18n_settings),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    },
    modifier = modifier.clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
  )
}

/**
 * Selection mode top bar
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionTopBar(
  selectedCount: Int,
  totalCount: Int,
  onCancel: () -> Unit,
  onDelete: (() -> Unit)?,
  onRename: (() -> Unit)?,
  onCopy: (() -> Unit)? = null,
  onMove: (() -> Unit)? = null,
  isSingleSelection: Boolean,
  onInfo: (() -> Unit)?,
  onShare: (() -> Unit)?,
  onPlay: (() -> Unit)?,
  onBlacklist: (() -> Unit)?,
  onSelectAll: (() -> Unit)?,
  onInvertSelection: (() -> Unit)?,
  onDeselectAll: (() -> Unit)?,
  modifier: Modifier = Modifier,
  useRemoveIcon: Boolean = false,
  onAddToPlaylist: (() -> Unit)? = null,
) {
  var showDropdown by remember { mutableStateOf(false) }

  TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = if (MaterialTheme.colorScheme.background == Color.Black) {
        Color.Black
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    ),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { showDropdown = true },
      ) {
        Text(
          stringResource(R.string.selected_items, selectedCount, totalCount),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Icon(
          Icons.Filled.ArrowDropDown,
          contentDescription = stringResource(R.string.selection_options),
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.primary,
        )

        DropdownMenu(
          expanded = showDropdown,
          onDismissRequest = { showDropdown = false },
        ) {
          if (onSelectAll != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.select_all)) },
              onClick = {
                onSelectAll()
                showDropdown = false
              },
            )
          }
          if (onInvertSelection != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.invert_selection)) },
              onClick = {
                onInvertSelection()
                showDropdown = false
              },
            )
          }
          if (onDeselectAll != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.deselect_all)) },
              onClick = {
                onDeselectAll()
                showDropdown = false
              },
            )
          }
        }
      }
    },
    navigationIcon = {
      IconButton(
        onClick = onCancel,
        modifier = Modifier.padding(horizontal = 2.dp),
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(R.string.generic_cancel),
          modifier = Modifier.size(28.dp),
          tint = MaterialTheme.colorScheme.secondary,
        )
      }
    },
    actions = {
      // Play icon
      if (onPlay != null) {
        IconButton(
          onClick = onPlay,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.i18n_play),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }

      // Add to Playlist icon (for Play Store builds)
      if (onAddToPlaylist != null) {
        IconButton(
          onClick = onAddToPlaylist,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.PlaylistAdd,
            contentDescription = stringResource(R.string.i18n_add_to_playlist),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      // Copy / Move（网络浏览的 AList 批量操作）
      // ⚠️ 0 项选中时置灰而不是隐藏：按钮位置稳定，用户能看出「这里本来有这个动作」，
      //    只是得先勾一个——隐藏会让顶栏随选择数跳来跳去。
      if (onCopy != null) {
        IconButton(
          onClick = onCopy,
          enabled = selectedCount > 0,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制",
            modifier = Modifier.size(24.dp),
            tint =
              if (selectedCount > 0) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }
      if (onMove != null) {
        IconButton(
          onClick = onMove,
          enabled = selectedCount > 0,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.DriveFileMove,
            contentDescription = "移动到",
            modifier = Modifier.size(24.dp),
            tint =
              if (selectedCount > 0) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Rename icon
      if (onRename != null) {
        IconButton(
          onClick = onRename,
          enabled = isSingleSelection,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.DriveFileRenameOutline,
            contentDescription = stringResource(R.string.rename),
            modifier = Modifier.size(24.dp),
            tint =
              if (isSingleSelection) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Info icon
      if (onInfo != null) {
        IconButton(
          onClick = onInfo,
          enabled = isSingleSelection,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.Info,
            contentDescription = stringResource(R.string.info),
            modifier = Modifier.size(24.dp),
            tint =
              if (isSingleSelection) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Share icon
      if (onShare != null) {
        IconButton(
          onClick = onShare,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.Share,
            contentDescription = stringResource(R.string.generic_share),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      // Blacklist icon
      if (onBlacklist != null) {
        IconButton(
          onClick = onBlacklist,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            Icons.Filled.Block,
            contentDescription = stringResource(R.string.pref_folders_blacklist),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      // Delete/Remove icon
      if (onDelete != null) {
        IconButton(
          onClick = onDelete,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          Icon(
            imageVector = if (useRemoveIcon) Icons.Filled.RemoveCircle else Icons.Filled.Delete,
            contentDescription = stringResource(R.string.delete),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    modifier = modifier.clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
  )
}
