package app.marlboroadvance.mpvex.ui.preferences

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Audiotrack
// ⚠️ material icons 的 `Icons.Outlined.X` 是**顶层扩展属性**，不是成员 ——
// 每个用到的图标都必须单独 `import androidx.compose.material.icons.outlined.X`，
// 漏了就是「Unresolved reference 'X'」（图标在库里明明存在）。
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.MediaSourcePreference
import app.marlboroadvance.mpvex.preferences.AppLockMethod
import app.marlboroadvance.mpvex.preferences.AppLockPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.DanmakuPreferences
import app.marlboroadvance.mpvex.preferences.FolderCoverRefreshPolicy
import app.marlboroadvance.mpvex.preferences.GridTitleLines
import app.marlboroadvance.mpvex.preferences.PhotoSlideshowInterval
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.PersonMergeScreen
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarAlgorithm
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoSaver
import app.marlboroadvance.mpvex.ui.browser.dialogs.FolderPickerDialog
import app.marlboroadvance.mpvex.ui.lock.AppLockManageDialog
import app.marlboroadvance.mpvex.ui.lock.PatternChangeDialog
import app.marlboroadvance.mpvex.ui.lock.PatternSetupDialog
import app.marlboroadvance.mpvex.ui.lock.PatternVerifyDialog
import app.marlboroadvance.mpvex.ui.lock.PinChangeDialog
import app.marlboroadvance.mpvex.ui.lock.PinSetupDialog
import app.marlboroadvance.mpvex.ui.lock.PinVerifyDialog
import app.marlboroadvance.mpvex.ui.lock.UnlockMethodDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.OpenDocumentTreeContract
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object PreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(R.string.pref_preferences),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
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
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          // Search bar - full width, prominent placement
          item {
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { backstack.add(SettingsSearchScreen) },
              shape = RoundedCornerShape(28.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              tonalElevation = 2.dp,
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Outlined.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(R.string.settings_search_hint),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            }
          }
          
          // UI & Appearance Section
          item {
            PreferenceSectionHeader(title = "界面与外观")
          }
          
          item {
            PreferenceCard {
              Preference(
                title = { Text(text = stringResource(id = R.string.pref_appearance_title)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_appearance_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Palette, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(AppearancePreferencesScreen) },
              )

              PreferenceDivider()

              // 外观深度定制（桌面图标 / 加载动画）—— 单独一页：
              // 这一页全都会**立刻看到画面变化**，混在长列表里对不上「改了什么」。
              Preference(

                title = { Text(text = stringResource(id = R.string.appearance_deep_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.appearance_icon_title) + " · " +
                      stringResource(id = R.string.appearance_loading_anim_title),
                    color = MaterialTheme.colorScheme.outline
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.AutoAwesomeMotion,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                onClick = { backstack.add(AppearanceDeepCustomScreen) },
              )

              PreferenceDivider()
              
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_layout_title)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_layout_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.AutoMirrored.Outlined.ViewQuilt, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(PlayerControlsPreferencesScreen) },
              )
            }
          }
          
          // Playback & Controls Section
          item {
            PreferenceSectionHeader(title = "播放与控件")
          }
          
          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_player)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_player_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.PlayCircle, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(PlayerPreferencesScreen) },
              )
              
              PreferenceDivider()
              
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_gesture)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_gesture_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Gesture, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(GesturePreferencesScreen) },
              )

              PreferenceDivider()

              // 弹幕独立设置页（开关 / 弹幕源 / 凭证 / 自动匹配）。
              // ⚠️ 原来这个入口挂在外观定制页里，但弹幕跟外观无关 ——
              //    用户原话「把弹幕单独挪到设置页面，不要放在外观定制里面」。
              //    它默认是关的，所以摘要里直接带开关状态：不显示状态的话，
              //    用户永远不知道有这东西、更不知道去哪开。
              val danmakuPrefs = koinInject<DanmakuPreferences>()
              val danmakuEnabled by danmakuPrefs.enabled.collectAsState()
              Preference(
                title = { Text(text = stringResource(id = R.string.danmaku_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.danmaku_settings_summary) +
                      if (danmakuEnabled) "（已开启）" else "（未开启）",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { backstack.add(DanmakuSettingsScreen) },
              )

              PreferenceDivider()

              // GSY 播放器是**独立的一套播放页**（内核 / 渲染 / 手势都跟 mpv 不共用），
              // 所以它的配置单开一页：这一页改什么都不影响 mpv，反之亦然。
              Preference(
                title = { Text(text = stringResource(id = R.string.pref_gsy_player)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_gsy_player_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { backstack.add(GsyPreferencesScreen) },
              )

              // 「标记为不喜欢」的三个开关 + 管理页入口。
              // 播放器按钮那个开关与 mpv 的布局编辑器是**双向同步**的
              // （关开关会清掉控件簇里的按钮、开开关会加回原簇），
              // 细节见 DislikeLayoutSync。
              Preference(
                title = { Text(text = stringResource(id = R.string.pref_dislike_title)) },
                summary = {
                  Text(
                    text = stringResource(id = R.string.pref_dislike_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.ThumbDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { backstack.add(DislikePreferencesScreen) },
              )
            }
          }
          
          // File Management Section
          item {
            PreferenceSectionHeader(title = "文件管理")
          }
          
          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_folders_title)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_folders_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Folder, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(FoldersPreferencesScreen) },
              )
            }
          }
          
          // Media Settings Section
          item {
            PreferenceSectionHeader(title = "媒体设置")
          }
          
          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_decoder)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_decoder_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Memory, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(DecoderPreferencesScreen) },
              )
              
              PreferenceDivider()
              
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_subtitles)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_subtitles_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Subtitles, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(SubtitlesPreferencesScreen) },
              )
              
              PreferenceDivider()
              
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_audio)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_audio_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Audiotrack, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(AudioPreferencesScreen) },
              )
            }
          }
          
          // Emby Media Library Section
          item {
            PreferenceSectionHeader(title = "Emby 媒体库")
          }

          item {
            val browserPreferences = koinInject<BrowserPreferences>()
            val quickFavorite by browserPreferences.embyQuickFavorite.collectAsState()
            val randomGenreRecommend by browserPreferences.embyRandomGenreRecommend.collectAsState()
            val showRecommendations by browserPreferences.embyShowRecommendations.collectAsState()
            val homeShowMix by browserPreferences.embyHomeShowMix.collectAsState()
            val mixColumns by browserPreferences.embyMixGridColumns.collectAsState()
            val resolveDirectLink by browserPreferences.embyResolveDirectLink.collectAsState()
            val clearProgressOnDownload by browserPreferences.embyClearProgressOnDownload.collectAsState()
            PreferenceCard {
              // 卡片右上角心形快捷收藏：点一下即可收藏 / 取消收藏
              SwitchPreference(
                value = quickFavorite,
                onValueChange = { browserPreferences.embyQuickFavorite.set(it) },
                title = { Text(text = "卡片快捷收藏") },
                summary = {
                  Text(
                    text = "媒体库卡片右上角显示可点的收藏心形；关闭后只作「已收藏」只读角标",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 详情页推荐总开关：关闭后「推荐」与「同类型推荐」在详情页整块不显示
              SwitchPreference(
                value = showRecommendations,
                onValueChange = { browserPreferences.embyShowRecommendations.set(it) },
                title = { Text(text = "详情页推荐") },
                summary = {
                  Text(
                    text = "详情页显示「推荐」与「同类型推荐」；关闭后详情页不再展示推荐内容",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 详情页「同类型」随机选取类型：一部片常属多个类型，随机后每次打开题材不同
              SwitchPreference(
                value = randomGenreRecommend,
                onValueChange = { browserPreferences.embyRandomGenreRecommend.set(it) },
                title = { Text(text = "同类型推荐随机选取类型") },
                summary = {
                  Text(
                    text = "详情页「同类型」每次从本片所属的多个类型里随机挑一个；关闭则固定用第一个类型",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 「猜你喜欢 / 即混」的算法选择（清单 #14）。
              // 用两个 FilterChip 而不是 ListPreference：只有两个选项，
              // 横向两个 chip 一眼就能看完，点错了立刻能看出来；
              // ListPreference 要点开弹窗才知道当前选的是哪个。
              val similarAlgorithm by browserPreferences.embySimilarAlgorithm.collectAsState()
              val excludePlayed by browserPreferences.embySimilarExcludePlayed.collectAsState()
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(text = "推荐算法", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  SimilarAlgorithm.entries.forEach { option ->
                    FilterChip(
                      selected = similarAlgorithm == option,
                      onClick = { browserPreferences.embySimilarAlgorithm.set(option) },
                      label = {
                        Text(
                          when (option) {
                            SimilarAlgorithm.EMBY -> "服务端"
                            SimilarAlgorithm.LOCAL -> "本地"
                          },
                        )
                      },
                    )
                  }
                }
                Text(
                  text = when (similarAlgorithm) {
                    SimilarAlgorithm.EMBY ->
                      "调用 Emby 自带的推荐算法，结果更准；但需要服务端支持且元数据已刮削，" +
                        "否则该服务器可能返回空"
                    SimilarAlgorithm.LOCAL ->
                      "由本机按类型 / 年代 / 评分 / 阵容打分排序；不依赖服务端，"+
                        "库里还有别的内容就一定有结果"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }

              PreferenceDivider()

              SwitchPreference(
                value = excludePlayed,
                onValueChange = { browserPreferences.embySimilarExcludePlayed.set(it) },
                title = { Text(text = "推荐时排除已看过") },
                summary = {
                  Text(
                    text = "关闭后看过的片子也会出现在推荐与连播列表里（适合重刷老片）",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 首页「随便看看」（即混的一批）总开关。
              // 关掉后首页不再发那一次推荐请求，也不占首页一整行的位置。
              SwitchPreference(
                value = homeShowMix,
                onValueChange = { browserPreferences.embyHomeShowMix.set(it) },
                title = { Text(text = "首页「随便看看」") },
                summary = {
                  Text(
                    text = "在首页「最新加入」下面显示一批可连播的推荐；关闭后首页不再请求这批内容",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 即混网格的列数单独给一项，不复用媒体库的列数。
              // 两者默认值一样，但改动的动机不同：媒体库列数是「屏幕宽窄」问题，
              // 即混列数是「一批推荐想看几列」问题。共用一个键会导致想调一边时另一边跟着变。
              SliderPreference(
                value = mixColumns.toFloat(),
                onValueChange = { browserPreferences.embyMixGridColumns.set(it.roundToInt()) },
                sliderValue = mixColumns.toFloat(),
                onSliderValueChange = { browserPreferences.embyMixGridColumns.set(it.roundToInt()) },
                // 标题与说明里用的一律是**界面上真实出现的字**。
                // 「连播列表」是「猜你喜欢」页里那个区块的标题（见EmbyDiscoverScreen），
                // 别写成「即混」—— 那是 Emby 的 InstantMix 接口名，
                // 玩家在界面上搜不到这个词，只会觉得说的是别的地方。
                title = { Text(text = "连播列表每行放几个") },
                valueRange = 2f..6f,
                valueSteps = 3,
                summary = {
                  Text(
                    text = "「猜你喜欢」页里「连播列表」那一块，一行放几张海报，当前 $mixColumns 个。" +
                      "数字越小海报越大，封面上的字看得更清；不影响媒体库的每行个数。",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 以下两项原在「Emby 服务器维护」页，现移到这里 ──────────
              //
              // 移过来的理由：这两项**与具体哪台服务器无关**，是全局播放偏好。
              // 放在维护页里自相矛盾 —— 那页是加删服务器 / 查连接状态的地方，
              // 用户要在那里找一个「跟哪台服务器都无关」的开关。
              // 放在本分区是因为存根同源：都是 BrowserPreferences 里的 Emby 偏好。
              // ⚠️ 偏好键**不能改**，改了等于重置所有用户已有的选择。
              SwitchPreference(
                value = resolveDirectLink,
                onValueChange = { browserPreferences.embyResolveDirectLink.set(it) },
                title = { Text(text = "预解析直链（302）") },
                summary = {
                  Text(
                    text = "播放前先取出跳转后的真实地址。用于网盘 / STRM 这类带 302 跳转、" +
                      "直链有有效期的源；解析失败会自动回退，不影响正常播放。",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              SwitchPreference(
                value = clearProgressOnDownload,
                onValueChange = { browserPreferences.embyClearProgressOnDownload.set(it) },
                title = { Text(text = "下载完成后清除服务端播放进度") },
                summary = {
                  Text(
                    text = "下载完一条就把服务器上这条的续播位置清零，「继续观看」不再挂着它。" +
                      "只清进度，不动「已看」标记。",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 多版本优先选择 ────────────────────────────────────────
              //
              // 同一影片有多个版本（4K/1080P/不同码率）时，播放前自动按这条规则挑，
              // 省掉每次都去点版本下拉框。
              // ⭐ 优先级是「用户手动选 > 本规则」：在详情页下拉里选过的版本永远优先，
              //   规则只在没选过时接管。
              // ⚠️ 用 DropDown 而非单选按钮组：条目有 7 个，单选组会把这个分区撑得很长。
              val sourcePrefKey by browserPreferences.embyMediaSourcePreference.collectAsState()
              val sourcePref = MediaSourcePreference.fromKey(sourcePrefKey)
              ListPreference(
                value = sourcePref,
                onValueChange = { browserPreferences.embyMediaSourcePreference.set(it.key) },
                values = MediaSourcePreference.entries,
                valueToText = { AnnotatedString(mediaSourcePreferenceLabel(it)) },
                title = { Text(text = stringResource(R.string.pref_media_source_pref_title)) },
                summary = {
                  Text(
                    text = mediaSourcePreferenceLabel(sourcePref),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 照片预加载张数 ────────────────────────────────────────
              //
              // 照片浏览器会提前把「当前这张前后各 N 张」拉进缓存，滑过去时不白屏。
              // 固定 1/2/3 三档：再大就是拿流量和内存换几乎感知不到的流畅度
              // （全屏图一张十几 MB，缓存封顶后多预取的会被后来者顶掉，等于白预取）。
              val preloadCount by browserPreferences.photoPreloadCount.collectAsState()
              ListPreference(
                value = preloadCount.coerceIn(1, 3),
                onValueChange = { browserPreferences.photoPreloadCount.set(it.coerceIn(1, 3)) },
                values = listOf(1, 2, 3),
                valueToText = { AnnotatedString("前后各 $it 张") },
                title = { Text(text = "照片预加载张数") },
                summary = {
                  Text(
                    text = "前后各预取 ${preloadCount.coerceIn(1, 3)} 张，翻页更快；" +
                      "张数越多占的内存和流量越多，缓存满时会自动淘汰最旧的",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 照片幻灯片间隔 ────────────────────────────────────────
              //
              // 照片浏览器（图集）里「幻灯片播放」的翻页间隔。三档固定值，
              // 不用滑块：常用就 3 / 5 / 10 秒，拖出来的中间值没有意义。
              val slideshowInterval by browserPreferences.photoSlideshowInterval.collectAsState()
              ListPreference(
                value = slideshowInterval,
                onValueChange = { browserPreferences.photoSlideshowInterval.set(it) },
                values = PhotoSlideshowInterval.entries,
                valueToText = { AnnotatedString("${it.seconds} 秒") },
                title = { Text(text = "照片幻灯片间隔") },
                summary = {
                  Text(
                    text = "照片浏览器幻灯片播放时，每 ${slideshowInterval.seconds} 秒自动翻到下一张",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 进照片浏览器是否**直接开始**幻灯片。关 = 停在第一张自己滑 / 自己点播放键；
              // 开 = 进去就按上面的间隔自动翻（适合当相框用）。默认关 ——
              // 大多数人进浏览器是想先看这一张，自动翻会抢走控制权。
              // 全局一份、不按库分：这是看照片的习惯，不是媒体库的属性。
              val photoAutoSlideshow by browserPreferences.photoAutoSlideshow.collectAsState()
              SwitchPreference(
                value = photoAutoSlideshow,
                onValueChange = { browserPreferences.photoAutoSlideshow.set(it) },
                title = { Text(text = "进入照片浏览器自动播放幻灯片") },
                summary = {
                  Text(
                    text = if (photoAutoSlideshow) {
                      "打开照片即按间隔自动翻页，播放中可随时暂停"
                    } else {
                      "打开照片停在第一张，是否播放由你点播放键决定"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 底部胶片条 ────────────────────────────────────────────
              //
              // 照片浏览器底部那条缩略图。默认**关**：它要吃掉约 56dp 的画面高度，
              // 而照片浏览器最值钱的就是画面面积；想要快速跳图的人自己打开。
              val photoFilmstrip by browserPreferences.photoFilmstrip.collectAsState()
              SwitchPreference(
                value = photoFilmstrip,
                onValueChange = { browserPreferences.photoFilmstrip.set(it) },
                title = { Text(text = "照片浏览器显示底部缩略图") },
                summary = {
                  Text(
                    text = if (photoFilmstrip) {
                      "底部显示一排缩略图，点一下跳到那张（会占一点画面高度）"
                    } else {
                      "整屏只显示当前这张，画面最大"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // ── 照片保存位置 ──────────────────────────────────────────
              //
              // 照片浏览器里「长按 → 保存原图 / 另存为 JPEG」落到哪里。默认进系统相册的
              // `Pictures/CineIsle`；想放自己的目录就点进来选一次。
              //
              // ⭐ v1.5.4 起弹的是 **App 自己的文件夹选择器**（FolderPickerDialog），
              //    不再是系统的 SAF `ACTION_OPEN_DOCUMENT_TREE` —— 系统那张界面是
              //    系统风格，跟本 App 的整体 UI 完全不搭。
              //    自研选择器给的是**绝对路径**（`/storage/emulated/0/DCIM/xxx`），
              //    落盘走 java.io.File（依赖「所有文件访问权限」，本 App 作为本地
              //    播放器本来就要这个权限；没授权时保存会给出明确的失败原因）。
              // ⚠️ 选完把老版本的 SAF URI 一起清空：两个位置并存时优先级会打架，
              //    用户会困惑「我到底存到哪了」。
              val context = LocalContext.current
              val saveDirPath by browserPreferences.photoSaveDirPath.collectAsState()
              var showFolderPicker by remember { mutableStateOf(false) }
              Preference(
                title = { Text(text = "照片保存位置") },
                summary = {
                  Text(
                    text = EmbyPhotoSaver.displayPathLabel(saveDirPath)
                      ?: "系统相册（Pictures/CineIsle）· 点击更改",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { showFolderPicker = true },
              )

              // 自研文件夹选择器：存储卷切换 / 返回上级 / 新建文件夹都在里面。
              // ⚠️ currentPath 传空串 —— 它是「与当前路径相同就禁用确定」的判据
              // （给移动/复制用的），选保存位置时没有「源目录」，不能让它生效。
              FolderPickerDialog(
                isOpen = showFolderPicker,
                currentPath = "",
                onDismiss = { showFolderPicker = false },
                onFolderSelected = { path ->
                  showFolderPicker = false
                  browserPreferences.photoSaveDirPath.set(path)
                  browserPreferences.photoSaveDirUri.set("")
                  Toast.makeText(context, "已设为照片保存位置", Toast.LENGTH_SHORT).show()
                },
              )

              // 只有选过自定义目录才给「恢复默认」—— 默认状态下这一项没有意义，
              // 常驻只会让人以为「默认」是个需要主动设置的东西。
              if (saveDirPath.isNotBlank()) {
                PreferenceDivider()
                Preference(
                  title = { Text(text = "恢复默认保存位置") },
                  summary = {
                    Text(
                      text = "改回系统相册的 Pictures/CineIsle",
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onClick = {
                    browserPreferences.photoSaveDirPath.set("")
                    browserPreferences.photoSaveDirUri.set("")
                    Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                  },
                )
              }
            }
          }

          item {
            // 卡片外观（圆角 / 角标）—— 收口成设置，改一次全 App 的 Emby 卡片一起变
            val browserPreferences = koinInject<BrowserPreferences>()
            val cardCorner by browserPreferences.embyCardCorner.collectAsState()
            val showCardBadges by browserPreferences.embyShowCardBadges.collectAsState()
            val folderRandomCover by browserPreferences.embyFolderRandomCover.collectAsState()
            PreferenceCard {
              SliderPreference(
                value = cardCorner.toFloat(),
                onValueChange = { browserPreferences.embyCardCorner.set(it.roundToInt()) },
                sliderValue = cardCorner.toFloat(),
                onSliderValueChange = { browserPreferences.embyCardCorner.set(it.roundToInt()) },
                title = { Text(text = "卡片圆角") },
                valueRange = 0f..24f,
                summary = {
                  Text(
                    text = "当前 ${cardCorner}dp · 全 App 的 Emby 卡片统一圆角",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              SwitchPreference(
                value = showCardBadges,
                onValueChange = { browserPreferences.embyShowCardBadges.set(it) },
                title = { Text(text = "显示封面角标") },
                summary = {
                  Text(
                    text = "在封面上显示播放进度条 / 作品数等角标；关闭后卡片更干净",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 文件夹 / 合集卡片没有自己的封面，客户端会拉内部视频的图拼宫格。
              // 开＝随机取样（每次可能不一样）；关＝按名称固定取前几张。
              // 两种模式都会跳过没封面的条目、一直往后凑满四张，所以不会整张空白。
              SwitchPreference(
                value = folderRandomCover,
                onValueChange = { browserPreferences.embyFolderRandomCover.set(it) },
                title = { Text(text = "合成随机封面图") },
                summary = {
                  Text(
                    text = "文件夹 / 合集卡片用内部视频的封面拼多宫格；打开后随机取样，" +
                      "每次看到的封面可能不同。关闭则按名称固定取前几个。" +
                      "没有封面的条目会自动跳过并往后补，直到凑满四张。",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              // 合成策略：只在「随机合成」开着时才有意义 —— 固定模式取样结果是确定的，
              // 重取还是同一批图，摆出来只是个死项。
              if (folderRandomCover) {
                val coverPolicy by browserPreferences.embyFolderCoverRefreshPolicy.collectAsState()
                val coverHours by browserPreferences.embyFolderCoverRefreshHours.collectAsState()
                val coverDays by browserPreferences.embyFolderCoverRefreshDays.collectAsState()
                var editHours by remember { mutableStateOf(false) }
                var editDays by remember { mutableStateOf(false) }

                PreferenceDivider()

                ListPreference(
                  value = coverPolicy,
                  onValueChange = { picked ->
                    browserPreferences.embyFolderCoverRefreshPolicy.set(picked)
                    // 改策略 = 明确要求「按新规矩来」⇒ 把上次合成时间清零，
                    // 下次进库一定重新合成一次。不清零的话选了「满 X 天」却半天看不到变化，
                    // 用户会以为设置根本没生效。
                    browserPreferences.embyFolderCoverLastSynthAt.set(0L)
                  },
                  values = FolderCoverRefreshPolicy.entries,
                  valueToText = { AnnotatedString(it.label) },
                  title = { Text(text = "重新合成策略") },
                  summary = {
                    Text(
                      text = "随机取样的封面每次都不一样，这一项决定多久换一批。" +
                        "默认「仅下拉刷新时」：进出文件夹（含从子文件夹返回）不重取，" +
                        "滑来滑去看到的还是同一批封面；选小时 / 天则在到期前同样完全复用、" +
                        "不产生请求。下拉刷新始终立即重取，不受这里限制。",
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                if (coverPolicy == FolderCoverRefreshPolicy.Hours) {
                  PreferenceDivider()
                  Preference(
                    title = { Text(text = "间隔小时数") },
                    summary = {
                      Text(
                        text = "当前 $coverHours 小时 · 取值范围 1~168",
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onClick = { editHours = true },
                  )
                }

                if (coverPolicy == FolderCoverRefreshPolicy.Days) {
                  PreferenceDivider()
                  Preference(
                    title = { Text(text = "间隔天数") },
                    summary = {
                      Text(
                        text = "当前 $coverDays 天 · 取值范围 1~30",
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                    onClick = { editDays = true },
                  )
                }

                if (editHours) {
                  IntervalInputDialog(
                    title = "间隔小时数",
                    hint = "1~168 小时",
                    current = coverHours,
                    range = 1..168,
                    onConfirm = { value ->
                      browserPreferences.embyFolderCoverRefreshHours.set(value)
                      browserPreferences.embyFolderCoverLastSynthAt.set(0L)
                      editHours = false
                    },
                    onCancel = { editHours = false },
                  )
                }

                if (editDays) {
                  IntervalInputDialog(
                    title = "间隔天数",
                    hint = "1~30 天",
                    current = coverDays,
                    range = 1..30,
                    onConfirm = { value ->
                      browserPreferences.embyFolderCoverRefreshDays.set(value)
                      browserPreferences.embyFolderCoverLastSynthAt.set(0L)
                      editDays = false
                    },
                    onCancel = { editDays = false },
                  )
                }
              }
            }
          }

          item {
            // 演职员合并：同一个演员因为名字写法不同会裂成多条 Person（作品页缺片、演职员表重复）。
            // 这是唯一会写服务器元数据的工具，单独一个页面承载，这里只放入口。
            PreferenceCard {
              Preference(
                title = { Text(text = "演职员合并") },
                summary = {
                  Text(
                    text = "把重复的演员条目归并成一条（写入服务器，所有客户端生效，可撤销）· 需要管理员",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { backstack.add(PersonMergeScreen()) },
              )
            }
          }

          // ── 网络浏览（SMB / WebDAV / FTP） ──────────────────────────
          //
          // 这里放「网络」模块自己的观感项：文件夹封面是这一轮新增的大功能
          // （懒加载 + 两级缓存，关掉后零额外请求），标题行数是网格信息密度。
          // 排序维度 / 升降序不进设置 —— 那两个是「当前目录怎么看」的即时操作，
          // 放在浏览页内容区的排序控件里更顺手，且切换不发请求。
          item {
            PreferenceSectionHeader(title = "网络浏览")
          }

          item {
            // 文件夹封面：文件夹卡用目录里第一张图片当封面。
            // 关掉的场景：服务器目录结构深、图片多，不想让浏览产生额外列目录请求；
            // 或者就喜欢朴素的名字列表。
            val browserPreferences = koinInject<BrowserPreferences>()
            val folderCoverEnabled by browserPreferences.folderCoverEnabled.collectAsState()
            SwitchPreference(
              value = folderCoverEnabled,
              onValueChange = { browserPreferences.folderCoverEnabled.set(it) },
              title = { Text(text = "文件夹封面") },
              summary = {
                Text(
                  text = if (folderCoverEnabled) {
                    "文件夹卡片显示目录内第一张图片作为封面；只在滚动到可见时加载，" +
                      "同一目录再次进入不再重复请求"
                  } else {
                    "文件夹卡片只显示名称；关闭后浏览过程不产生任何额外请求"
                  },
                  color = MaterialTheme.colorScheme.outline,
                )
              },
            )
          }

          item { PreferenceDivider() }

          item {
            // 网格标题行数：一行最整齐（信息密度低），两行折中，全显适合长片名库。
            val browserPreferences = koinInject<BrowserPreferences>()
            val gridTitleLines by browserPreferences.networkGridTitleLines.collectAsState()
            ListPreference(
              value = gridTitleLines,
              onValueChange = { browserPreferences.networkGridTitleLines.set(it) },
              values = GridTitleLines.entries,
              valueToText = { AnnotatedString(it.label) },
              title = { Text(text = "网格标题行数") },
              summary = {
                Text(
                  text = "网络浏览网格视图下，文件夹 / 图片 / 视频卡片标题最多显示" +
                    "${gridTitleLines.label}，超出部分省略",
                  color = MaterialTheme.colorScheme.outline,
                )
              },
            )
          }

          // Data & Cache Section
          item {
            PreferenceSectionHeader(title = "数据与缓存")
          }

          item {
            // 图片缓存：Emby 封面 / 播放列表缩略图的磁盘缓存。
            // 7 天有效期、256MB 上限（EmbyImageLoader 里维护），这里只管查看占用与清除。
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var cacheBytes by remember { mutableStateOf(0L) }
            var showClearConfirm by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
              cacheBytes = EmbyImageLoader.diskCacheSizeBytes()
            }
            PreferenceCard {
              Preference(
                title = { Text(text = "图片缓存") },
                summary = {
                  Text(
                    text = "封面等图片的离线缓存（7 天有效）· 当前占用 " +
                      formatCacheBytes(cacheBytes) +
                      "，点击清除后将从服务器重新拉取",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { showClearConfirm = true },
              )
            }
            if (showClearConfirm) {
              ConfirmDialog(
                title = "清除图片缓存",
                subtitle = "将删除 ${formatCacheBytes(cacheBytes)} 的缓存图片，浏览时会在需要时重新下载。继续吗？",
                onConfirm = {
                  showClearConfirm = false
                  scope.launch {
                    val freed = EmbyImageLoader.clearDiskCache()
                    cacheBytes = EmbyImageLoader.diskCacheSizeBytes()
                    android.widget.Toast
                      .makeText(context, "已清除 ${formatCacheBytes(freed)}", android.widget.Toast.LENGTH_SHORT)
                      .show()
                  }
                },
                onCancel = { showClearConfirm = false },
              )
            }
          }

          item {
            // 随机播放取多少条 —— 媒体库的两个随机按钮和「视界流」统一读这一个值。
            // 做成可编辑而不是写死：库里片子多的用户想要更长的一批，
            // 库小的用户觉得刷来刷去都是重复的，需要能缩短。
            val browserPreferences = koinInject<BrowserPreferences>()
            val randomCount by browserPreferences.randomPlayCount.collectAsState()
            var showRandomDialog by remember { mutableStateOf(false) }
            PreferenceCard {
              Preference(
                title = { Text(text = "随机播放视频数量") },
                summary = {
                  Text(
                    text = "当前 $randomCount 条 · 用于随机播放、随机播放收藏与视界流",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                icon = {
                  Icon(
                    Icons.Outlined.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = { showRandomDialog = true },
              )
            }
            if (showRandomDialog) {
              RandomPlayCountDialog(
                current = randomCount,
                onConfirm = { value ->
                  showRandomDialog = false
                  browserPreferences.randomPlayCount.set(value)
                },
                onCancel = { showRandomDialog = false },
              )
            }
          }

          // Advanced & About Section
          item {
            PreferenceSectionHeader(title = "高级与关于")
          }

          // 应用锁（b7）：未开启 → 先选方式（数字 PIN / 九宫格图案）再设凭证；
          // 已开启 → 管理入口（改凭证 / 自动解锁开关 / 验证后关锁）。
          item {
            val appLockPreferences = koinInject<AppLockPreferences>()
            val appLockEnabled by appLockPreferences.enabled.collectAsState()
            val appLockMethod by appLockPreferences.method.collectAsState()
            val appLockAutoUnlock by appLockPreferences.autoUnlock.collectAsState()
            var showMethodPicker by remember { mutableStateOf(false) }
            var showPinSetup by remember { mutableStateOf(false) }
            var showPatternSetup by remember { mutableStateOf(false) }
            var showManage by remember { mutableStateOf(false) }
            var showChangeCredential by remember { mutableStateOf(false) }
            var showDisableVerify by remember { mutableStateOf(false) }

            val isPattern = appLockMethod == AppLockMethod.PATTERN
            val methodLabel = stringResource(
              if (isPattern) R.string.app_lock_method_pattern else R.string.app_lock_method_pin,
            )
            val autoBadge = stringResource(R.string.app_lock_auto_unlock_badge)

            PreferenceCard {
              Preference(
                title = { Text(text = stringResource(R.string.app_lock_title)) },
                summary = {
                  val text = if (appLockEnabled) {
                    val base = stringResource(R.string.app_lock_summary_on_format, methodLabel)
                    if (appLockAutoUnlock) "$base · $autoBadge" else base
                  } else {
                    stringResource(R.string.app_lock_summary_off)
                  }
                  Text(text = text, color = MaterialTheme.colorScheme.outline)
                },
                icon = {
                  Icon(
                    if (isPattern) Icons.Outlined.Grid3x3 else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                onClick = {
                  if (appLockEnabled) showManage = true else showMethodPicker = true
                },
              )
            }

            if (showMethodPicker) {
              UnlockMethodDialog(
                onPickPin = {
                  showMethodPicker = false
                  showPinSetup = true
                },
                onPickPattern = {
                  showMethodPicker = false
                  showPatternSetup = true
                },
                onDismiss = { showMethodPicker = false },
              )
            }
            if (showPinSetup) {
              PinSetupDialog(
                onDismiss = { showPinSetup = false },
                onSet = { pin ->
                  appLockPreferences.setPin(pin)
                  appLockPreferences.enabled.set(true)
                },
              )
            }
            if (showPatternSetup) {
              PatternSetupDialog(
                onDismiss = { showPatternSetup = false },
                onSet = { pattern ->
                  appLockPreferences.setPattern(pattern)
                  appLockPreferences.enabled.set(true)
                },
              )
            }
            if (showManage) {
              AppLockManageDialog(
                method = appLockMethod,
                autoUnlock = appLockAutoUnlock,
                onToggleAutoUnlock = { appLockPreferences.autoUnlock.set(it) },
                onChangeCredential = {
                  showManage = false
                  showChangeCredential = true
                },
                onDisable = {
                  showManage = false
                  showDisableVerify = true
                },
                onDismiss = { showManage = false },
              )
            }
            if (showChangeCredential) {
              if (isPattern) {
                PatternChangeDialog(
                  onDismiss = { showChangeCredential = false },
                  onVerify = appLockPreferences::verifyPattern,
                  onSet = appLockPreferences::setPattern,
                )
              } else {
                PinChangeDialog(
                  onDismiss = { showChangeCredential = false },
                  onVerify = appLockPreferences::verifyPin,
                  onSet = appLockPreferences::setPin,
                )
              }
            }
            if (showDisableVerify) {
              if (isPattern) {
                PatternVerifyDialog(
                  title = stringResource(R.string.app_lock_verify_title),
                  message = stringResource(R.string.app_lock_change_verify_hint),
                  onDismiss = { showDisableVerify = false },
                  onVerify = appLockPreferences::verifyPattern,
                  onVerified = { appLockPreferences.disable() },
                )
              } else {
                PinVerifyDialog(
                  title = stringResource(R.string.app_lock_verify_title),
                  message = stringResource(R.string.app_lock_change_verify_hint),
                  onDismiss = { showDisableVerify = false },
                  onVerify = appLockPreferences::verifyPin,
                  onVerified = { appLockPreferences.disable() },
                )
              }
            }
          }

          item {
            PreferenceCard {
              Preference(

                title = { Text(text = stringResource(R.string.pref_advanced)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_advanced_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Code, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(AdvancedPreferencesScreen) },
              )
              
              PreferenceDivider()
              
              Preference(

                title = { Text(text = stringResource(id = R.string.pref_about_title)) },
                summary = { 
                  Text(
                    text = stringResource(id = R.string.pref_about_summary),
                    color = MaterialTheme.colorScheme.outline
                  ) 
                },
                icon = { 
                  Icon(
                    Icons.Outlined.Info, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  ) 
                },
                onClick = { backstack.add(AboutScreen) },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 缓存占用的人类可读格式（KB / MB / GB）。
 *
 * 三个设置项都要报「当前占用」（图片缓存 / 播放缓存 / …），所以放在
 * 顶层而不是留在文件私有 —— 复制一份到别处的结果就是两边的单位进位
 * 哪天对不上，用户看到两个数不一致会以为其中一处算错了。
 */
fun formatCacheBytes(bytes: Long): String =
  when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
  }

/**
 * 「随机播放视频数量」的输入框。
 *
 * 只接受数字（[Char.isDigit] 逐字符过滤），确认时再夹到 1~500 —— 空串 / 越界
 * 一律回落到默认值 100，而不是 disabling 按钮不给任何反馈：
 * 用户填了个 0 却点不动「确定」，他会以为是 App 坏了。
 */
@Composable
private fun RandomPlayCountDialog(
  current: Int,
  onConfirm: (Int) -> Unit,
  onCancel: () -> Unit,
) {
  var text by remember { mutableStateOf(current.toString()) }
  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(text = "随机播放视频数量") },
    text = {
      Column {
        Text(
          text = "取值 1~500。数量越大，每次随机取片越慢；视界流的一批视频也按这个数量来。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
          value = text,
          onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
          singleLine = true,
          label = { Text(text = "数量") },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(text.toIntOrNull()?.coerceIn(1, 500) ?: 100) },
      ) {
        Text(text = "确定")
      }
    },
    dismissButton = {
      TextButton(onClick = onCancel) { Text(text = "取消") }
    },
  )
}

/**
 * 「间隔 X 小时 / X 天」的数字输入框（合成随机封面图的策略用）。
 *
 * 与 [RandomPlayCountDialog] 同一套做法：逐字符过滤非数字，确认时夹到 [range]。
 * 空串 / 越界一律回落到 [current]（而不是 disabled 掉「确定」）—— 用户填了个 0
 * 却点不动按钮，他会以为 App 坏了。
 *
 * 与上面那个的唯一差别是范围来自参数：小时 1~168、天 1~30 共用这一个组件，
 * 免得两处各写一份「过滤 + 夹取」将来漂移。
 */
@Composable
private fun IntervalInputDialog(
  title: String,
  hint: String,
  current: Int,
  range: IntRange,
  onConfirm: (Int) -> Unit,
  onCancel: () -> Unit,
) {
  var text by remember { mutableStateOf(current.toString()) }
  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(text = title) },
    text = {
      Column {
        Text(
          text = "取值 $hint。改完会立刻重新合成一次，之后按这个间隔换新。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
          value = text,
          onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
          singleLine = true,
          label = { Text(text = "间隔") },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(text.toIntOrNull()?.coerceIn(range) ?: current) },
      ) {
        Text(text = "确定")
      }
    },
    dismissButton = {
      TextButton(onClick = onCancel) { Text(text = "取消") }
    },
  )
}

/**
 * [MediaSourcePreference] 的显示文案。
 *
 * 单独抽出来是因为同一段文案出现在两处（下拉当前值 + 下拉展开后的每一条），
 * 各写一遍就会漂移。
 *
 * ⚠️ 用 `when` 而不是 `when` + else 兜底丢 default：
 * 将来加了一个枚举值却忘了这里，编译期就报错（穷尽匹配），
 * 比运行时显示成空白好。
 */
@Composable
private fun mediaSourcePreferenceLabel(preference: MediaSourcePreference): String =
  when (preference) {
    MediaSourcePreference.DEFAULT -> stringResource(R.string.media_source_pref_default)
    MediaSourcePreference.HIGHEST_RESOLUTION ->
      stringResource(R.string.media_source_pref_highest_resolution)
    MediaSourcePreference.LOWEST_RESOLUTION ->
      stringResource(R.string.media_source_pref_lowest_resolution)
    MediaSourcePreference.HIGHEST_BITRATE ->
      stringResource(R.string.media_source_pref_highest_bitrate)
    MediaSourcePreference.LOWEST_BITRATE ->
      stringResource(R.string.media_source_pref_lowest_bitrate)
    MediaSourcePreference.SMALLEST_SIZE ->
      stringResource(R.string.media_source_pref_smallest_size)
    MediaSourcePreference.BEST_QUALITY_PER_SIZE ->
      stringResource(R.string.media_source_pref_best_quality_per_size)
  }
