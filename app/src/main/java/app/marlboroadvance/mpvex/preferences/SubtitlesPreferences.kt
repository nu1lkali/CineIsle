package app.marlboroadvance.mpvex.preferences

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle

class SubtitlesPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("sub_preferred_languages")
  val autoloadMatchingSubtitles = preferenceStore.getBoolean("sub_autoload_enabled", true)

  // ── Emby 字幕（服务端已识别的字幕：内嵌轨 + 旁挂文件）──

  /**
   * 播放 Emby 媒体时，自动按语言偏好挂载服务端已识别的字幕。
   *
   * 与 [autoloadMatchingSubtitles] 是**两件不同的事**：那个是「本地/网络目录里找同名
   * 字幕文件」（靠文件名），这个是「用 Emby 自己的媒体识别结果」（靠服务端元数据）。
   * 分开两个开关，是因为前者在网盘直链 / 本地文件上也能用，后者只对 Emby 生效。
   */
  val embyAutoSubtitles = preferenceStore.getBoolean("sub_emby_auto", true)

  /**
   * 没有首选语言候选时，是否跟随 Emby 标为「默认」的字幕轨。
   *
   * 关掉后只认用户自己设的语言；一条都对不上就**保持关闭**（不随便塞一条）。
   */
  val embyFollowDefault = preferenceStore.getBoolean("sub_emby_follow_default", true)

  /**
   * 允许的替补语言（逗号分隔）。为空 = 不限。
   *
   * 与 [preferredLanguages] 的区别是**只在首选语言完全没候选时**才用；
   * 典型场景：首选简体中文，但片子只有繁体 —— 用户愿意看繁体就把它填进来。
   */
  val embyAllowedLanguages = preferenceStore.getString("sub_emby_allowed_languages", "")

  /**
   * 「这部片子用 Emby 的哪条字幕」的账本：一行一条 `itemId<TAB>mediaSourceId#index`。
   *
   * 与 [subtitleBindings]（片名 → 本地字幕 Uri）分开存：那边记的是**文件**，
   * 这边记的是**服务端流**。混在一起的话，本地缓存被清掉时会把服务端选择也一起丢掉。
   */
  val embySubtitleChoices = preferenceStore.getString("sub_emby_choices", "")

  val fontsFolder = preferenceStore.getString("sub_fonts_folder")
  val font = preferenceStore.getString("sub_font", "")
  val fontSize = preferenceStore.getInt("sub_font_size", 55)
  val subScale = preferenceStore.getFloat("sub_scale", 1f)
  val borderSize = preferenceStore.getInt("sub_border_size", 3)
  val bold = preferenceStore.getBoolean("sub_bold", false)
  val italic = preferenceStore.getBoolean("sub_italic", false)

  val textColor = preferenceStore.getInt("sub_color_text", Color.White.toArgb())

  val borderColor = preferenceStore.getInt("sub_color_border", Color.Black.toArgb())
  val borderStyle = preferenceStore.getEnum("sub_border_style", SubtitlesBorderStyle.OutlineAndShadow)
  val shadowOffset = preferenceStore.getInt("sub_shadow_offset", 0)
  val backgroundColor = preferenceStore.getInt("sub_color_bg", Color.Transparent.toArgb())

  val justification = preferenceStore.getEnum("sub_justify", SubtitleJustification.Auto)
  val subPos = preferenceStore.getInt("sub_pos", 100)

  val overrideAssSubs = preferenceStore.getBoolean("sub_override_ass")

  /**
   * 字幕大小是否**跟随画面**（默认开）。
   *
   * 对应 mpv 的 `sub-scale-with-window`：开着它时 mpv 按**窗口高度**缩放字号，
   * 竖屏窗口高而画面只占中间一条，字会被放大 3~4 倍；横屏又偏小 —— 也就是
   * 「横屏调好竖屏太大、竖屏调好横屏太小」。关掉后字号改成按**画面高度**等比，
   * 横竖屏占画面的比例一致（也是 VLC / MPC 的做法）。
   *
   * ⚠️ 键名/含义与旧版 `sub_scale_by_window` **相反**（那个是「按窗口缩放」），
   * 所以换了新键 `sub_follow_picture_scale`，不复用旧值 —— 旧用户一律落到默认「跟随画面」，
   * 正是想要的行为。
   */
  val followPictureScale = preferenceStore.getBoolean("sub_follow_picture_scale", true)

  val defaultSubDelay = preferenceStore.getInt("sub_default_delay")
  val defaultSubSpeed = preferenceStore.getFloat("sub_default_speed", 1f)
  
  val pickerPath = preferenceStore.getString("sub_picker_path")
  
  /**
   * 字幕保存目录（**绝对路径**，自研 `FolderPickerDialog` 的产物）。空 = 未设置。
   *
   * 与照片目录同一套选择器：本地播放器本来就有「所有文件访问权限」，走 `java.io.File`
   * 比 SAF 树 URI 直接得多，用户看到的也是「内部存储/Movies」这种能对上的路径。
   *
   * 优先级高于 [subtitleSaveFolder]（后者是早期 `ACTION_OPEN_DOCUMENT_TREE` 的遗留值，
   * 只在绝对路径为空时才用）。两者的解析集中在
   * [app.marlboroadvance.mpvex.domain.subtitle.online.SubtitleSaveStore]。
   */
  val subtitleSaveDirPath = preferenceStore.getString("sub_save_dir_path", "")

  /** SAF 树 URI 形态的字幕保存目录。**仅作兼容保留**，新设置一律写 [subtitleSaveDirPath]。 */
  val subtitleSaveFolder = preferenceStore.getString("sub_save_folder", "")

  /**
   * 手动绑定的外挂字幕账本：一行一条 `媒体键<TAB>字幕Uri`，见
   * [app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings]。
   *
   * 存成单个字符串而不是拆成多条偏好：条目数是动态的，SharedPreferences 没有「列」的概念。
   */
  val subtitleBindings = preferenceStore.getString("sub_bindings", "")

  /**
   * 已删除字幕的持久忽略账本：外挂文件 / 在线下载 / Emby 流各自使用隔离键。
   * 只记“不再恢复”，不表示删除用户媒体目录里的文件。格式见 `SubtitleTombstones`。
   */
  val subtitleTombstones = preferenceStore.getString("sub_title_tombstones", "")

  /**
   * 用户确认过的番号别名账本：一行一条
   * `canonical<TAB>alias<TAB>source<TAB>createdAt<TAB>confidence`，见
   * [app.marlboroadvance.mpvex.ui.player.subtitle.smart.AliasRecord]。
   *
   * 与 [subtitleBindings] 分开存：绑定记的是「这部片子用哪个文件」，
   * 别名记的是「这两个编号是同一部作品」。后者跨片子生效，混淆会让一次手动选择
   * 悄悄扩散成全局规则 —— 那正是设计里明令禁止的事。
   */
  val subtitleCodeAliases = preferenceStore.getString("sub_code_aliases", "")

  /**
   * 本地在线字幕库索引：一行一条记录，字段以 `\t` 分隔，见
   * [app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle]。
   *
   * 记的是「哪部片子（服务器 + ItemId）用过哪一条下载来的字幕、时间轴偏移多少」。
   * 与 [subtitleBindings] 分开：那个是「片名 → 任意本地文件」，这个是
   * 「媒体身份 → 我们管得着的那个文件」，三个入口（播放器 / 详情页 / 字幕实验室）
   * 都靠它认出彼此下载的字幕。
   */
  val onlineSubtitleLibrary = preferenceStore.getString("sub_online_library", "")

  /**
   * 服务端与本地都没有可用字幕时，自动检索网络字幕并按匹配度阈值挂载。
   *
   * 默认**关闭**：会向外站发请求并占用移动流量，必须由用户显式开启。
   * 只在「本次播放既没有手动绑定、也没有已下载网络字幕、Emby 也没选出一条」时触发。
   */
  val onlineAutoFetch = preferenceStore.getBoolean("sub_online_auto_fetch", false)

  /**
   * 「中文字幕版本」跳过网络字幕自动下载与挂载。
   *
   * 中文字幕版本（文件名 / 路径带中字标记，见 `ChineseSubtitleMarks`）本身就带中文字幕，
   * 再挂一条网络字幕会画面叠两层字。开启后，自动兜底前按路径判一次，命中就跳过下载。
   *
   * 默认**开启**：这是避免重复挂载的修复，符合大多数用户的预期；
   * 关掉则恢复「一律走网络兜底」的旧行为（例如内嵌中字质量差、想用网络字幕替代时）。
   * 只影响**自动**下载，用户在字幕面板手动搜索不受此开关约束。
   */
  val skipOnlineFetchForChineseVersion =
    preferenceStore.getBoolean("sub_skip_online_for_chinese_version", true)

  /** 自动检索的匹配度阈值（0–100）。低于该分数不下载、不挂载 */
  val onlineAutoFetchThreshold = preferenceStore.getInt("sub_online_auto_threshold", 85)

  /**
   * 网络字幕自动下载**排除的媒体库**集合，元素格式见
   * [app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibraryScope.key]
   *（`serverId:userId|libraryId`）。
   *
   * 默认**空集合**＝全部媒体库生效，与现状一致。被排除库中的视频起播时不再向字幕源
   * 发检索请求 —— 用于确定没有字幕的库（例如纪录片 / 家庭录像）省掉逐片无效请求。
   * 只影响**自动**下载；用户在字幕面板手动搜索不受此约束。
   */
  val onlineFetchExcludedLibraries =
    preferenceStore.getStringSet("sub_online_fetch_excluded_libraries", emptySet())

  /**
   * 字幕编码归一化迁移的版本号（一次性）。
   *
   * 旧版本下载字幕时按字节原样落盘，本地库里因此混着 GBK / Big5 / UTF-16 的文件 ——
   * 时间线预览能解对，mpv 挂载却是乱码（它自己又猜了一次编码）。升级后扫描私有字幕
   * 目录把这类文件就地重写成 UTF-8（原文件留 `.bak`），做完置版本号，不再重复扫。
   */
  val subtitleEncodingMigrationVersion = preferenceStore.getInt("sub_encoding_migration_v", 0)
}

enum class SubtitleJustification(
  val value: String,
  val icon: ImageVector,
) {
  Left("left", Icons.AutoMirrored.Default.FormatAlignLeft),
  Center("center", Icons.Default.FormatAlignCenter),
  Right("right", Icons.AutoMirrored.Default.FormatAlignRight),
  Auto("auto", Icons.Default.FormatAlignJustify),
}
