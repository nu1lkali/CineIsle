package app.marlboroadvance.mpvex.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Abstraction over host requirements so the player logic can run in an Activity or a Screen.
 */
interface PlayerHost {
  val context: Context
  val windowInsetsController: WindowInsetsControllerCompat
  val audioManager: AudioManager

  // Host OS primitives with non-conflicting names
  val hostWindow: Window
  val hostWindowManager: WindowManager
  val hostContentResolver: ContentResolver
  var hostRequestedOrientation: Int
  
  fun requestAudioFocus(): Boolean
  fun abandonAudioFocus()

  /**
   * 画面旋转后回调：宽高互换，宿主需要按新比例重算一次方向。
   *
   * 默认空实现 —— 不是每个宿主都关心（比如 VerticalFeed 里根本没有横竖屏切换）。
   * ~~不默认去调 setOrientation()~~: 调用方（ViewModel）不知道「用户是否手动锁过方向」，
   * 那个判断只有宿主持有，所以这里只发信号、一律由宿主自己决定要不要动。
   */
  fun onVideoRotationChanged() {}
}