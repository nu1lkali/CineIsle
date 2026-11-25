package app.marlboroadvance.mpvex.ui.preferences

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import app.marlboroadvance.mpvex.utils.media.OpenDocumentTreeContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.browser.dialogs.FolderPickerDialog
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibrary
import app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveRestore
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.CustomFontEntry
import app.marlboroadvance.mpvex.utils.media.copyFontsFromDirectory
import app.marlboroadvance.mpvex.utils.media.loadCustomFontEntries
import com.github.k1rakishou.fsaf.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Subtitles
import android.net.Uri
import org.koin.compose.koinInject

@Serializable
object SubtitlesPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<SubtitlesPreferences>()
    val fileManager = koinInject<FileManager>()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_subtitles),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(
              onClick = backstack::removeLastOrNull,
            ) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val fontsFolder by preferences.fontsFolder.collectAsState()
        var availableFonts by remember { mutableStateOf<List<String>>(emptyList()) }
        var customFontEntries by remember { mutableStateOf<List<CustomFontEntry>>(emptyList()) }
        var fontLoadTrigger by remember { mutableIntStateOf(0) }
        var isLoadingFonts by remember { mutableStateOf(false) }

        val locationPicker =
          rememberLauncherForActivityResult(
            OpenDocumentTreeContract(),
          ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            preferences.fontsFolder.set(uri.toString())

            // Copy fonts immediately in background
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
              isLoadingFonts = true
              copyFontsFromDirectory(context, fileManager, uri.toString())
              withContext(Dispatchers.Main) {
                fontLoadTrigger++
                isLoadingFonts = false
              }
            }
          }

        // Load fonts when folder changes or trigger is fired
        LaunchedEffect(fontsFolder, fontLoadTrigger) {
          customFontEntries = loadCustomFontEntries(context)
          availableFonts = listOf("Default") + customFontEntries.map { it.familyName }
        }

        // Auto-refresh fonts on app restart if directory is set
        LaunchedEffect(Unit) {
          if (fontsFolder.isNotBlank()) {
            isLoadingFonts = true
            withContext(Dispatchers.IO) {
              copyFontsFromDirectory(context, fileManager, fontsFolder)
            }
            fontLoadTrigger++
            isLoadingFonts = false
          }
        }

        val listState = rememberLazyListState()
        SettingsSpotlightReveal(listState, SubtitlesPreferencesScreen)
        LazyColumn(
          state = listState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          // === GENERAL SECTION ===
          item {
            PreferenceSectionHeader(title = stringResource(R.string.general))
          }

          item {
            PreferenceCard {

              val preferredLanguages by preferences.preferredLanguages.collectAsState()
              TextFieldPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = preferredLanguages,
                onValueChange = preferences.preferredLanguages::set,
                textToValue = { it },
                modifier = Modifier.settingsAnchor(R.string.pref_preferred_languages),
                title = { Text(stringResource(R.string.pref_preferred_languages), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  if (preferredLanguages.isNotBlank()) {
                    Text(
                      preferredLanguages,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(
                      stringResource(R.string.not_set_video_default),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
                textField = { value, onValueChange, _ ->
                  Column {
                    Text(stringResource(R.string.enter_language_codes))
                    TextField(
                      value,
                      onValueChange,
                      modifier = Modifier.fillMaxWidth(),
                      placeholder = { Text(stringResource(R.string.language_codes_placeholder)) },
                    )
                  }
                },
              )
              
              PreferenceDivider()

              val autoload by preferences.autoloadMatchingSubtitles.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = autoload,
                onValueChange = { preferences.autoloadMatchingSubtitles.set(it) },
                modifier = Modifier.settingsAnchor(R.string.pref_subtitles_autoload_title),
                title = { Text(stringResource(R.string.pref_subtitles_autoload_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_autoload_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── Emby 服务端字幕（内嵌轨 + 旁挂文件）──
              // 与上面「同名外挂字幕自动加载」是两条独立的路：那个按文件名在目录里找，
              // 这个用 Emby 自己的媒体识别结果。所以在设置里也分成两个开关。
              val embyAuto by preferences.embyAutoSubtitles.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = embyAuto,
                onValueChange = { preferences.embyAutoSubtitles.set(it) },
                modifier = Modifier.settingsAnchor(R.string.pref_subtitles_emby_auto_title),
                title = {
                  Text(
                    stringResource(R.string.pref_subtitles_emby_auto_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_emby_auto_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 网络字幕兜底：与前两个开关串成三级 —— 同名文件 → 服务端字幕 → 网络检索
              val onlineAuto by preferences.onlineAutoFetch.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = onlineAuto,
                onValueChange = { preferences.onlineAutoFetch.set(it) },
                modifier = Modifier.settingsAnchor(R.string.pref_subtitles_online_auto_title),
                title = {
                  Text(
                    stringResource(R.string.pref_subtitles_online_auto_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_online_auto_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val embyFollowDefault by preferences.embyFollowDefault.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = embyFollowDefault,
                onValueChange = { preferences.embyFollowDefault.set(it) },
                modifier = Modifier.settingsAnchor(R.string.pref_subtitles_emby_default_title),
                title = {
                  Text(
                    stringResource(R.string.pref_subtitles_emby_default_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                summary = {
                  Text(
                    stringResource(R.string.pref_subtitles_emby_default_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 「允许的其它语言」—— 首选语言完全没候选时的兜底（典型：只想要中文，
              // 简体优先、繁体也行）。填法与上面的首选语言一致，都是逗号分隔的语言码。
              val embyAllowed by preferences.embyAllowedLanguages.collectAsState()
              TextFieldPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = embyAllowed,
                onValueChange = preferences.embyAllowedLanguages::set,
                textToValue = { it },
                modifier = Modifier.settingsAnchor(R.string.pref_subtitles_emby_allowed_title),
                title = {
                  Text(
                    stringResource(R.string.pref_subtitles_emby_allowed_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                summary = {
                  if (embyAllowed.isBlank()) {
                    Text(
                      stringResource(R.string.pref_subtitles_emby_allowed_empty),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  } else {
                    Text(embyAllowed, color = MaterialTheme.colorScheme.outline)
                  }
                },
                textField = { value, onValueChange, _ ->
                  Column {
                    Text(stringResource(R.string.pref_subtitles_emby_allowed_summary))
                    TextField(
                      value,
                      onValueChange,
                      modifier = Modifier.fillMaxWidth(),
                      placeholder = { Text(stringResource(R.string.language_codes_placeholder)) },
                    )
                  }
                },
              )

              PreferenceDivider()

              val overrideAss by preferences.overrideAssSubs.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = overrideAss,
                onValueChange = { preferences.overrideAssSubs.set(it) },
                modifier = Modifier.settingsAnchor(R.string.player_sheets_sub_override_ass),
                title = { Text(stringResource(R.string.player_sheets_sub_override_ass), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.player_sheets_sub_override_ass_subtitle),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val followPicture by preferences.followPictureScale.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = followPicture,
                onValueChange = { preferences.followPictureScale.set(it) },
                modifier = Modifier.settingsAnchor(R.string.player_sheets_sub_follow_picture),
                title = { Text(stringResource(R.string.player_sheets_sub_follow_picture), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.player_sheets_sub_follow_picture_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // Directory picker preference with reload and clear icons on the right
              Box(
                modifier =
                  Modifier
                    // 这一行是手写的（标题走 Text 而不是 Preference 的 title 参数），
                    // 生成器看不见，锚点手挂 —— 否则搜索「字体目录」点进来定位不到。
                    .settingsAnchor(R.string.pref_subtitles_fonts_dir)
                    .fillMaxWidth()
                    .clickable { locationPicker.launch(null) }
                    .padding(vertical = 16.dp, horizontal = 16.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  // Left side: Title + summary
                  Column(
                    modifier = Modifier.weight(1f),
                  ) {
                    Text(
                      stringResource(R.string.pref_subtitles_fonts_dir),
                      style = MaterialTheme.typography.titleMedium,
                    )
                    if (fontsFolder.isBlank()) {
                      Text(
                        stringResource(R.string.not_set_system_fonts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    } else {
                      if (availableFonts.isNotEmpty()) {
                        Text(
                          stringResource(R.string.fonts_loaded, availableFonts.size),
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                      }
                    }
                  }

                  // Right side: Action icons
                  if (fontsFolder.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      // Refresh icon or loading spinner
                      if (isLoadingFonts) {
                        Box(
                          modifier = Modifier.size(48.dp),
                          contentAlignment = Alignment.Center,
                        ) {
                          CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                          )
                        }
                      } else {
                        IconButton(
                          onClick = {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                              isLoadingFonts = true
                              copyFontsFromDirectory(context, fileManager, fontsFolder)
                              withContext(Dispatchers.Main) {
                                fontLoadTrigger++
                                isLoadingFonts = false
                              }
                            }
                          },
                        ) {
                          Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reload_fonts),
                            tint = MaterialTheme.colorScheme.primary,
                          )
                        }
                      }

                      // Clear icon (always visible when directory is set)
                      IconButton(
                        onClick = {
                          preferences.fontsFolder.set("")
                          fontLoadTrigger++
                        },
                      ) {
                        Icon(
                          Icons.Default.Clear,
                          contentDescription = stringResource(R.string.clear_font_directory),
                          tint = MaterialTheme.colorScheme.tertiary,
                        )
                      }
                    }
                  }
                }
              }

              PreferenceDivider()

              // 字幕保存目录：下载时另存一份副本，并把「哪条记录对应哪个文件」写进目录里的
              // 清单 —— 卸载重装后私有库与索引都会被清掉，这份清单是唯一能精确还原的依据。
              SubtitleSaveFolderPreference(
                context = context,
                preferences = preferences,
              )
            }
          }

        }
      }
    }
  }
}

/** 字节数 → 容量文本。固定 `Locale.US`，避免部分区域把小数点渲染成逗号。 */
private fun formatBytesCompact(bytes: Long): String =
  if (bytes >= 1024L * 1024L) {
    String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  } else {
    String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
  }

/**
 * 「字幕保存目录」设置项。
 *
 * ## 三项职责
 *
 * 1. 选择目录（自研 [FolderPickerDialog]，与照片保存目录同一套交互，落绝对路径）；
 * 2. 显示当前目录名与说明；
 * 3. 从该目录恢复本地字幕库 —— 卸载重装后私有库与索引都被清空，保存目录里的副本
 *    与清单是唯一可用的还原依据（见 [SubtitleSaveRestore]）。
 *
 * ## 为什么选完目录就统计一次
 *
 * 重装后重新选目录是恢复索引的唯一入口。若把恢复藏在二级菜单里，用户不会主动去找，
 * 结果就是「字幕还在磁盘上，但已下载列表是空的」。选完即统计、有内容就弹确认，
 * 让这个动作落在用户本来就会做的那一步上。
 */
@Composable
private fun SubtitleSaveFolderPreference(
  context: Context,
  preferences: SubtitlesPreferences,
) {
  val scope = rememberCoroutineScope()
  val library = remember { OnlineSubtitleLibrary(context.applicationContext, preferences) }
  var dirPath by remember { mutableStateOf(preferences.subtitleSaveDirPath.get()) }
  // 早期版本存的是 SAF 树 URI。这里只用来兜底展示，新选择一律写绝对路径。
  var legacyTreeUri by remember { mutableStateOf(preferences.subtitleSaveFolder.get()) }
  var showPicker by remember { mutableStateOf(false) }
  var pending by remember { mutableStateOf<SubtitleSaveRestore.Plan?>(null) }
  var busy by remember { mutableStateOf(false) }
  var resultText by remember { mutableStateOf<String?>(null) }

  val configured =
    remember(dirPath, legacyTreeUri) {
      app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveStore
        .isConfigured(dirPath, legacyTreeUri)
    }
  // 展示用短标签：绝对路径去掉 `/storage/emulated/0` 前缀，遗留 URI 解成「内部存储/…」
  val locationLabel =
    remember(dirPath, legacyTreeUri) {
      app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoStorage.displayPathLabel(dirPath)
        ?: app.marlboroadvance.mpvex.ui.browser.emby.photo.PhotoStorage.displayLabel(legacyTreeUri)
        ?: ""
    }

  fun planRestore(showWhenEmpty: Boolean) {
    if (!configured || busy) return
    scope.launch {
      busy = true
      val plan = SubtitleSaveRestore.plan(context, dirPath, legacyTreeUri, library)
      busy = false
      when {
        !plan.isEmpty -> pending = plan
        // 清单缺失 / 未覆盖：告知这些文件不能按清单归属，而不是静默当作「没有字幕」
        plan.unlistedFiles > 0 ->
          resultText =
            context.getString(R.string.subtitle_restore_unlisted, plan.unlistedFiles)
        showWhenEmpty -> resultText = context.getString(R.string.subtitle_restore_empty)
        else -> Unit
      }
    }
  }

  if (showPicker) {
    FolderPickerDialog(
      isOpen = true,
      currentPath =
        dirPath.ifBlank { android.os.Environment.getExternalStorageDirectory().absolutePath },
      onDismiss = { showPicker = false },
      onFolderSelected = { path ->
        showPicker = false
        preferences.subtitleSaveDirPath.set(path)
        // 选了绝对路径就清掉遗留 SAF 值：两套值并存时优先级虽明确，但界面上会出现
        // 「改了目录却还显示旧目录」的错觉。
        preferences.subtitleSaveFolder.set("")
        dirPath = path
        legacyTreeUri = ""
        resultText = null
        // 选完立刻统计一次：重装后重新选目录是恢复索引的唯一入口
        planRestore(showWhenEmpty = false)
      },
    )
  }

  Box(
    modifier =
      Modifier
        // 这一行与字体目录一样是手写的，生成器看不见，锚点手动挂
        .settingsAnchor(R.string.pref_subtitles_save_dir)
        .fillMaxWidth()
        .clickable(enabled = !busy) { showPicker = true }
        .padding(vertical = 16.dp, horizontal = 16.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.pref_subtitles_save_dir),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text =
            when {
              !configured -> stringResource(R.string.not_set_subtitle_save_dir)
              locationLabel.isNotBlank() -> locationLabel
              else -> dirPath.ifBlank { legacyTreeUri }
            },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          stringResource(R.string.pref_subtitles_save_dir_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        resultText?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (configured) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (busy) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          } else {
            IconButton(onClick = { planRestore(showWhenEmpty = true) }) {
              Icon(
                Icons.Outlined.SettingsBackupRestore,
                contentDescription = stringResource(R.string.subtitle_restore_title),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
          IconButton(
            onClick = {
              preferences.subtitleSaveDirPath.set("")
              preferences.subtitleSaveFolder.set("")
              dirPath = ""
              legacyTreeUri = ""
              pending = null
              resultText = null
            },
          ) {
            Icon(
              Icons.Default.Clear,
              contentDescription = stringResource(R.string.clear_subtitle_save_dir),
              tint = MaterialTheme.colorScheme.tertiary,
            )
          }
        }
      }
    }
  }

  pending?.let { plan ->
    AlertDialog(
      onDismissRequest = { if (!busy) pending = null },
      title = { Text(stringResource(R.string.subtitle_restore_title)) },
      text = {
        Text(
          stringResource(
            R.string.subtitle_restore_message,
            plan.restorable,
            formatBytesCompact(plan.bytes),
            plan.alreadyPresent,
          ),
        )
      },
      confirmButton = {
        TextButton(
          enabled = !busy,
          onClick = {
            val items = plan.items
            busy = true
            scope.launch {
              val outcome =
                SubtitleSaveRestore.restore(context, dirPath, legacyTreeUri, library, items)
              busy = false
              pending = null
              resultText =
                context.getString(R.string.subtitle_restore_result, outcome.restored, outcome.failed)
            }
          },
        ) {
          Text(stringResource(R.string.subtitle_restore_confirm))
        }
      },
      dismissButton = {
        TextButton(enabled = !busy, onClick = { pending = null }) {
          Text(stringResource(R.string.subtitle_restore_cancel))
        }
      },
    )
  }
}

