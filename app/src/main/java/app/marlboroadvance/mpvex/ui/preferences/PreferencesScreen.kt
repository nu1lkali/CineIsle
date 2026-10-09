package app.marlboroadvance.mpvex.ui.preferences

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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.PersonMergeScreen
import app.marlboroadvance.mpvex.ui.browser.emby.discover.SimilarAlgorithm
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
            }
          }

          item {
            // 卡片外观（圆角 / 角标）—— 收口成设置，改一次全 App 的 Emby 卡片一起变
            val browserPreferences = koinInject<BrowserPreferences>()
            val cardCorner by browserPreferences.embyCardCorner.collectAsState()
            val showCardBadges by browserPreferences.embyShowCardBadges.collectAsState()
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
