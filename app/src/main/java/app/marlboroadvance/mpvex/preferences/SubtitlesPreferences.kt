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
