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

  /**
   * 加载动画（见 `ui/theme/LoadingAnimation.kt`）。
   *
   * ⚠️ 存的是枚举的 `storageKey` 字符串，不是 `name` —— 与别处 `getEnum`（用 name）不同，
   *    所以这里手动读写，**发布后不可改这些 key 字符串**。
   * 默认 `system`（不启用 Lottie）：列表里每屏可能有几十个占位，
   * 全都跑 Lottie 会明显掉帧，所以动画默认只作用于「整屏加载」这类一次性场景。
   */
  val loadingAnimation = preferenceStore.getString("loading_animation", "system")

  /**
   * 加载动画的显示大小（缩放系数，1.0 = 各调用点写死的基准尺寸）。
   *
   * 默认 **1.1**：，但具体多大因人而异
   * （有人嫌列表里 48dp 够用，有人要看视频缓冲时更醒目的）⇒ 给一个略大的默认值，
   * 再放开到 0.5×~2.0× 让用户自己缩放。
   *
   * ⚠️ 存的是 Float 系数而不是 dp 绝对值：调用点的基准尺寸本就不同
   *    （列表整页 48dp、播放器画面层 96dp、行内 40dp），乘同一个系数才能
   *    「哪都变大、比例不变」；存绝对值就得给每个场景各配一个偏好。
   * ⚠️ 消费方是 `AppLoadingIndicator`（ui/theme/AppLoadingIndicator.kt）——
   *    它乘在 `size` 参数上，所以**调用方传了带尺寸的 modifier 时不吃缩放**
   *    （那种场景尺寸由布局决定，组件不该二次约束）。
   */
  val loadingAnimationScale = preferenceStore.getFloat("loading_animation_scale", 1.1f)

  val topLeftControls =
    preferenceStore.getString(
      "top_left_controls",
      "BACK_ARROW,VIDEO_TITLE",
    )

  /**
   * 横屏顶栏右侧。
   *
   * 末位的 `DISLIKE` 是「标记为不喜欢」的出厂位置（见
   * [app.marlboroadvance.mpvex.ui.preferences.DislikeLayoutSync.DEFAULT_CLUSTER]）。
   *
   * ⚠️ **改这个默认值只对全新用户生效** —— 老用户本地已经存过值了（这正是
   * `bottom_left_controls` / `portrait_bottom_controls` 要带 v2/v3 的原因）。
   * 老用户靠 `App.onCreate` 里的一次性播种补上。两道都留着是因为：播种跑在后台线程，
   * 理论上存在「播放器先起来、播种还没落地」的空档，默认值能保证那个空档里按钮也在。
   */
  val topRightControls =
    preferenceStore.getString(
      "top_right_controls",
      "CURRENT_CHAPTER,EMBY_FAVORITE,CAST,MORE_OPTIONS,DISLIKE",
    )

  /**
   * 横屏右下簇（贴屏幕右缘）。
   *
   * 末尾那颗离拇指最近，留给「切回竖屏」（SCREEN_ROTATION）——
   * 横屏下最常做的就是看完切回竖屏，画面比例反而用得少，
   * 所以把两者对调：旋转挪到最右，比例退到左下簇原先旋转的位置。
   *
   * key 带 `_v2`：旧默认值已写进老用户本地存储，只改默认值不会生效。
   */
  val bottomRightControls =
    preferenceStore.getString(
      "bottom_right_controls_v2",
      "PICTURE_IN_PICTURE,SCREEN_ROTATION",
    )

  val bottomLeftControls =
    preferenceStore.getString(
      "bottom_left_controls_v2",
      "BACKGROUND_PLAYBACK,LOCK_CONTROLS,REPEAT_MODE,SHUFFLE,AB_LOOP",
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
      "PREVIOUS,NEXT,EMBY_FAVORITE,DISLIKE,REPEAT_MODE,LOCK_CONTROLS," +
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
