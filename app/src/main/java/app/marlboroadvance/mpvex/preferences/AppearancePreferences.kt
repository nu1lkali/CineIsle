package app.marlboroadvance.mpvex.preferences

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.theme.AppTheme
import app.marlboroadvance.mpvex.ui.theme.DarkMode
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList

class AppearancePreferences(
  preferenceStore: PreferenceStore,
) {
  val darkMode = preferenceStore.getEnum("dark_mode", DarkMode.System)
  val appTheme = preferenceStore.getEnum("app_theme", AppTheme.Dynamic)
  val amoledMode = preferenceStore.getBoolean("amoled_mode", false)
  val unlimitedNameLines = preferenceStore.getBoolean("unlimited_name_lines", false)
  val hidePlayerButtonsBackground = preferenceStore.getBoolean("hide_player_buttons_background", false)
  val showUnplayedOldVideoLabel = preferenceStore.getBoolean("show_unplayed_old_video_label", true)
  val unplayedOldVideoDays = preferenceStore.getInt("unplayed_old_video_days", 7)
  val seekbarStyle = preferenceStore.getEnum("seekbar_style", SeekbarStyle.Thick)

  val topLeftControls =
    preferenceStore.getString(
      "top_left_controls",
      "BACK_ARROW,VIDEO_TITLE",
    )

  val topRightControls =
    preferenceStore.getString(
      "top_right_controls",
      "CURRENT_CHAPTER,DECODER,AUDIO_TRACK,SUBTITLES,EMBY_FAVORITE,CAST,MORE_OPTIONS",
    )

  /**
   * 横屏右下簇（贴屏幕右缘）。
   *
   * 末尾那颗离拇指最近，留给「切回竖屏」（SCREEN_ROTATION）——
   * 用户反馈：横屏下最常做的就是看完切回竖屏，画面比例反而用得少，
   * 所以把两者对调：旋转挪到最右，比例退到左下簇原先旋转的位置。
   *
   * key 带 `_v2`：旧默认值已写进老用户本地存储，只改默认值不会生效。
   */
  val bottomRightControls =
    preferenceStore.getString(
      "bottom_right_controls_v2",
      "FRAME_NAVIGATION,VIDEO_ZOOM,PICTURE_IN_PICTURE,SCREEN_ROTATION",
    )

  val bottomLeftControls =
    preferenceStore.getString(
      "bottom_left_controls_v2",
      "PREVIOUS,NEXT,BACKGROUND_PLAYBACK,LOCK_CONTROLS,ASPECT_RATIO,PLAYBACK_SPEED,REPEAT_MODE,SHUFFLE,AB_LOOP",
    )

  /**
   * 竖屏播放页的控件列表（默认值）。
   *
   * 竖屏屏宽有限（约 411dp）。顶栏是「返回 + 快捷开关」，标题不再占顶栏（见下），
   * 所以顶栏能放 5 个：解码器 / 音轨 / 字幕 / 收藏 / 更多。
   *
   * 底部是**一条连续按钮条**（左簇 + 右簇已合并居中），默认 8 个：
   * 倍速 / 循环 / 随机 / 锁屏 / 画中画 / 缩放 / 比例 / 旋转。
   * 8 × 42dp + 7 × 4dp ≈ 364dp，411dp 屏刚好放得下；再多的项可以用「播放器控件」设置自行增删。
   *
   * 上/下一集固定在屏幕正中，标题移到进度条上方，都不占按钮条。
   *
   * 从这份默认里拿掉的低频项（AB 循环、后台播放、章节、逐帧导航、屏幕旋转以外的画面项等）
   * 并没有消失 —— 它们可以在「播放器控件 → 竖屏控件」里加回来，
   * 其中大部分也能从「更多」面板的「快捷功能」进入。
   *
   * 注意 key 带了 `_v3`：v2 的默认值（15 项）已经写进了老用户的本地存储，
   * 只改默认值不会生效，换 key 才能让新布局落地。
   *
   * v3 相比 v2 又拿掉了 3 项挤在底部按钮条里的按钮（随机播放 / 缩放 / 画面比例）——
   * 底部只剩「倍速 / 循环 / 锁屏 / 画中画 / 屏幕旋转」5 个高频操作，不再抢画面。
   * 随机播放改到「更多」面板的「播放」分区，缩放与比例本来就在「更多 → 快捷功能」里。
   */
  val portraitBottomControls =
    preferenceStore.getString(
      "portrait_bottom_controls_v3",
      "PREVIOUS,NEXT,EMBY_FAVORITE,DECODER,AUDIO_TRACK,SUBTITLES," +
        "PLAYBACK_SPEED,REPEAT_MODE,LOCK_CONTROLS," +
        "PICTURE_IN_PICTURE,SCREEN_ROTATION,CAST,MORE_OPTIONS",
    )

  fun parseButtons(
    csv: String,
    usedButtons: MutableSet<PlayerButton>,
  ): List<PlayerButton> =
    csv
      .splitToSequence(',')
      .map { it.trim().uppercase() }
      .mapNotNull { name ->
        try {
          PlayerButton.valueOf(name)
        } catch (_: IllegalArgumentException) {
          null
        }
      }.filter { it != PlayerButton.NONE }
      .filter { usedButtons.add(it) }
      .toList()
}

@Composable
fun MultiChoiceSegmentedButton(
  choices: ImmutableList<String>,
  selectedIndices: ImmutableList<Int>,
  onClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  MultiChoiceSegmentedButtonRow(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(MaterialTheme.spacing.medium),
  ) {
    choices.forEachIndexed { index, choice ->
      SegmentedButton(
        checked = selectedIndices.contains(index),
        onCheckedChange = { onClick(index) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
      ) {
        Text(text = choice)
      }
    }
  }
}
