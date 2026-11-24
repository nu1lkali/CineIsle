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
  
  val subtitleSaveFolder = preferenceStore.getString("sub_save_folder", "")

  /**
   * 手动绑定的外挂字幕账本：一行一条 `媒体键<TAB>字幕Uri`，见
   * [app.marlboroadvance.mpvex.ui.player.subtitle.SubtitleBindings]。
   *
   * 存成单个字符串而不是拆成多条偏好：条目数是动态的，SharedPreferences 没有「列」的概念。
   */
  val subtitleBindings = preferenceStore.getString("sub_bindings", "")

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
