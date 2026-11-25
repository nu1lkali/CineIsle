package app.marlboroadvance.mpvex.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.ui.graphics.vector.ImageVector
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.PersonMergeScreen
import app.marlboroadvance.mpvex.ui.browser.emby.aggregate.EmbyAggregateLibraryManageScreen
import app.marlboroadvance.mpvex.ui.browser.emby.smartmerge.SmartMergeScreen

/**
 * 设置页顶部导航项的**元数据**（P0：Settings Registry 的第一刀）。
 *
 * 原先每个入口都在 `PreferencesScreen` 里手写一遍 `Preference(title/summary/icon/onClick)`，
 * 搜索索引又另写一份标题——两处漂移时就会出现「搜得到、点不过去」或反过来。
 * 这里先把**导航型入口**收成一份元数据；渲染与搜索都从这里取。
 *
 * 当前只覆盖导航入口，不包含页面内联的开关/列表项（那些仍在各设置页里）。
 * 下一步是把内联项也迁进同一个 Registry，最终让 `SearchablePreferences` 不再靠
 * 脚本从源码抽索引。
 */
internal data class SettingsDestination(
  val id: String,
  /** 设置页分组标题，如「外观」「播放与控件」。 */
  val section: String,
  @StringRes val titleRes: Int? = null,
  val titleText: String? = null,
  @StringRes val summaryRes: Int? = null,
  val summaryText: String? = null,
  val icon: ImageVector,
  val screen: Screen,
  /** 搜索别名；从 Registry 生成搜索项时使用。 */
  val keywords: List<String> = emptyList(),
) {
  val title: String
    get() = titleText ?: error("SettingsDestination $id 缺少标题")
}

/**
 * 设置页导航入口的唯一清单。
 *
 * 顺序按设置页实际分组排列；新增入口先加到这里，再在 `PreferencesScreen` 里用
 * `SettingsDestinationRow` 渲染。
 */
internal object SettingsRegistry {
  val appearance =
    SettingsDestination(
      id = "appearance",
      section = "外观",
      titleRes = R.string.pref_appearance_title,
      summaryRes = R.string.pref_appearance_summary,
      icon = Icons.Outlined.Palette,
      screen = AppearancePreferencesScreen,
      keywords = listOf("theme", "dark", "light", "amoled", "color"),
    )

  val player =
    SettingsDestination(
      id = "player",
      section = "播放与控件",
      titleRes = R.string.pref_player,
      summaryRes = R.string.pref_player_summary,
      icon = Icons.Outlined.PlayCircle,
      screen = PlayerPreferencesScreen,
      keywords = listOf("player", "playback", "resume"),
    )

  val layout =
    SettingsDestination(
      id = "player_layout",
      section = "播放与控件",
      titleRes = R.string.pref_layout_title,
      summaryRes = R.string.pref_layout_summary,
      icon = Icons.AutoMirrored.Outlined.ViewQuilt,
      screen = PlayerControlsPreferencesScreen,
      keywords = listOf("layout", "controls", "buttons"),
    )

  val gesture =
    SettingsDestination(
      id = "gesture",
      section = "播放与控件",
      titleRes = R.string.pref_gesture,
      summaryRes = R.string.pref_gesture_summary,
      icon = Icons.Outlined.Gesture,
      screen = GesturePreferencesScreen,
      keywords = listOf("gesture", "brightness", "volume", "seek"),
    )

  val gsy =
    SettingsDestination(
      id = "gsy",
      section = "播放与控件",
      titleRes = R.string.pref_gsy_player,
      summaryRes = R.string.pref_gsy_player_summary,
      icon = Icons.Outlined.PlayCircle,
      screen = GsyPreferencesScreen,
      keywords = listOf("gsy", "exo", "ijk", "kernel"),
    )

  val playerEngine =
    SettingsDestination(
      id = "player_engine",
      section = "播放与控件",
      titleText = "播放内核设置",
      summaryText = "MPV / GSY / VLC 三套内核的独立配置",
      icon = Icons.Outlined.Memory,
      screen = PlayerEngineSettingsScreen,
      keywords = listOf("mpv", "gsy", "vlc", "内核", "播放器", "engine", "kernel"),
    )

  val dislike =
    SettingsDestination(
      id = "dislike",
      section = "播放与控件",
      titleRes = R.string.pref_dislike_title,
      summaryRes = R.string.pref_dislike_summary,
      icon = Icons.Outlined.ThumbDown,
      screen = DislikePreferencesScreen,
      keywords = listOf("dislike", "hidden", "blacklist"),
    )

  val folders =
    SettingsDestination(
      id = "folders",
      section = "本地媒体库",
      titleRes = R.string.pref_folders_title,
      summaryRes = R.string.pref_folders_summary,
      icon = Icons.Outlined.Folder,
      screen = FoldersPreferencesScreen,
      keywords = listOf("folder", "local", "scan"),
    )

  val decoder =
    SettingsDestination(
      id = "decoder",
      section = "媒体渲染",
      titleRes = R.string.pref_decoder,
      summaryRes = R.string.pref_decoder_summary,
      icon = Icons.Outlined.Memory,
      screen = DecoderPreferencesScreen,
      keywords = listOf("decoder", "hardware", "software", "vulkan"),
    )

  val subtitles =
    SettingsDestination(
      id = "subtitles",
      section = "媒体渲染",
      titleRes = R.string.pref_subtitles,
      summaryRes = R.string.pref_subtitles_summary,
      icon = Icons.Outlined.Subtitles,
      screen = SubtitlesPreferencesScreen,
      keywords = listOf("subtitle", "srt", "ass", "font"),
    )

  val audio =
    SettingsDestination(
      id = "audio",
      section = "媒体渲染",
      titleRes = R.string.pref_audio,
      summaryRes = R.string.pref_audio_summary,
      icon = Icons.Outlined.Audiotrack,
      screen = AudioPreferencesScreen,
      keywords = listOf("audio", "track", "volume", "delay"),
    )

  /**
   * 字幕实验室。
   *
   * 放在「媒体渲染」而不是「高级」：它服务的是字幕这条主线（搜索 / 下载 / 校准），
   * 而不是排障工具。图标沿用 Subtitle 系列，与「字幕」设置页同族。
   */
  val subtitleLab =
    SettingsDestination(
      id = "subtitle_lab",
      section = "媒体渲染",
      titleText = "字幕实验室",
      summaryText = "网络字幕搜索与下载 · 已下载字幕管理 · 时间轴校准",
      icon = Icons.Outlined.Science,
      screen = SubtitleLabScreen,
      keywords = listOf("subtitle", "lab", "search", "字幕", "实验室", "下载", "校准", "偏移"),
    )

  val aggregate =
    SettingsDestination(
      id = "aggregate_library",
      section = "Emby 媒体库与元数据",
      titleText = "聚合库",
      summaryText = "把多个同类型媒体库合并成一个视图；只在本 App 内生效，不修改服务器",
      icon = Icons.Outlined.Layers,
      screen = EmbyAggregateLibraryManageScreen(),
      keywords = listOf("aggregate", "merge", "library"),
    )

  val personMerge =
    SettingsDestination(
      id = "person_merge",
      section = "Emby 媒体库与元数据",
      titleText = "演职员合并",
      summaryText = "把重复的演员条目归并成一条（写入服务器，所有客户端生效，可撤销）· 需要管理员",
      icon = Icons.Outlined.People,
      screen = PersonMergeScreen(),
      keywords = listOf("person", "merge", "actor"),
    )

  val smartMerge =
    SettingsDestination(
      id = "smart_merge",
      section = "Emby 媒体库与元数据",
      titleText = "智能媒体合并",
      summaryText = "扫描重复作品，生成多版本组合候选；组合/拆分通过 Emby 原生接口执行",
      icon = Icons.Outlined.CallMerge,
      screen = SmartMergeScreen,
      keywords = listOf("merge", "version", "duplicate", "smart merge", "多版本", "合并"),
    )

  val advanced =
    SettingsDestination(
      id = "advanced",
      section = "高级与关于",
      titleRes = R.string.pref_advanced,
      summaryRes = R.string.pref_advanced_summary,
      icon = Icons.Outlined.Code,
      screen = AdvancedPreferencesScreen,
      keywords = listOf("advanced", "debug", "log"),
    )

  /**
   * 法律与隐私。
   *
   * 与「关于」分开：这一页是**有行为**的（撤回授权会让主界面立刻退出），
   * 塞进 AboutScreen 会和「版本号 / 开源许可 / 捐赠」混在一起，
   * 用户找「我的数据在哪」时不会想到去「关于」里翻。
   */
  val legalPrivacy =
    SettingsDestination(
      id = "legal_privacy",
      section = "高级与关于",
      titleText = "法律与隐私",
      summaryText = "用户服务协议 · 隐私政策 · 协议版本与同意时间 · 撤回授权",
      icon = Icons.Outlined.Policy,
      screen = LegalPrivacyScreen,
      keywords = listOf("privacy", "policy", "agreement", "consent", "隐私", "协议", "授权", "撤回"),
    )

  val about =
    SettingsDestination(
      id = "about",
      section = "高级与关于",
      titleRes = R.string.pref_about_title,
      summaryRes = R.string.pref_about_summary,
      icon = Icons.Outlined.Info,
      screen = AboutScreen,
      keywords = listOf("about", "version", "license"),
    )

  val all: List<SettingsDestination> =
    listOf(
      appearance,
      player,
      layout,
      gesture,
      gsy,
      playerEngine,
      dislike,
      folders,
      decoder,
      subtitles,
      subtitleLab,
      audio,
      aggregate,
      personMerge,
      smartMerge,
      advanced,
      legalPrivacy,
      about,
    )
}
