package app.marlboroadvance.mpvex.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import app.marlboroadvance.mpvex.R
import com.shuyu.gsyvideoplayer.GSYVideoManager

private const val PIP_INTENTS_FILTER = "gsy_pip_action"
private const val PIP_INTENT_ACTION = "gsy_pip_action_code"
private const val PIP_PLAY = 1
private const val PIP_PAUSE = 2
private const val PIP_REWIND = 3
private const val PIP_FORWARD = 4

/**
 * GSY 播放页的画中画。
 *
 * 与 mpv 的 `MPVPipHelper` 是**同一套做法**（窗口参数 + 三个遥控键 + 宽高比/源矩形），
 * 只是把指令通道从 mpv 属性换成 GSY 自己的 `GSYVideoManager`：
 * `pause()/start()/seekTo()` —— 这三个是 `GSYVideoBaseManager` 的公开方法，
 * 操控的正是当前接管播放的那个实例（全屏克隆实例也归它管），所以不用去碰具体 View。
 */
class CineIsleGsyPipHelper(
  private val activity: ComponentActivity,
) {
  private var receiver: BroadcastReceiver? = null

  /** 设备 / 系统是否支持画中画（API 26 起才有这个 API，manifest 里也要声明 supportsPictureInPicture） */
  fun isSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

  fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
    if (isInPipMode) registerReceiver() else unregisterReceiver()
  }

  @Suppress("UnspecifiedRegisterReceiverFlag")
  private fun registerReceiver() {
    if (receiver != null) return
    receiver =
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context?,
          intent: Intent?,
        ) {
          val manager = GSYVideoManager.instance()
          when (intent?.getIntExtra(PIP_INTENT_ACTION, 0)) {
            PIP_PLAY -> manager.start()
            PIP_PAUSE -> manager.pause()
            PIP_REWIND -> manager.seekTo((manager.getCurrentPosition() - REWIND_STEP_MS).coerceAtLeast(0L))
            PIP_FORWARD -> {
              val target = manager.getCurrentPosition() + REWIND_STEP_MS
              val duration = manager.getDuration()
              manager.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
            }
          }
          updateParams()
        }
      }

    val filter = IntentFilter(PIP_INTENTS_FILTER)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      activity.registerReceiver(receiver, filter)
    }
  }

  private fun unregisterReceiver() {
    receiver?.let { runCatching { activity.unregisterReceiver(it) } }
    receiver = null
  }

  /** 播放状态 / 进度变了就刷新一次窗口参数（遥控键的图标要跟着切） */
  fun updateParams() {
    if (activity.isFinishing || activity.isDestroyed) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching { activity.setPictureInPictureParams(buildParams()) }
  }

  fun enter(): Boolean {
    if (!isSupported()) return false
    return runCatching {
      activity.enterPictureInPictureMode(buildParams())
      true
    }.onFailure { Log.e(TAG, "enterPictureInPictureMode failed", it) }.getOrDefault(false)
  }

  private fun buildParams(): PictureInPictureParams =
    PictureInPictureParams
      .Builder()
      .apply {
        aspectRatio()?.let {
          setAspectRatio(it)
          setSourceRectHint(sourceRect(it))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          // 用户按 Home 时自动进小窗
          setAutoEnterEnabled(true)
        }
        setActions(actions())
      }.build()

  /** 画面宽高比；拿不到或超出系统允许范围时返回 null（交给系统自己决定） */
  private fun aspectRatio(): Rational? {
    val manager = GSYVideoManager.instance()
    val width = manager.getVideoWidth()
    val height = manager.getVideoHeight()
    if (width <= 0 || height <= 0) return null
    return Rational(width, height).takeIf { it.toFloat() in 0.5f..2.39f }
  }

  /** 只把画面区域交给系统做缩放动画（去掉黑边） */
  private fun sourceRect(aspect: Rational): Rect {
    val view = activity.window.decorView
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return Rect(0, 0, 1, 1)

    val videoAspect = aspect.toFloat()
    val viewAspect = viewWidth / viewHeight
    return if (viewAspect < videoAspect) {
      val height = viewWidth / videoAspect
      val top = ((viewHeight - height) / 2).toInt()
      Rect(0, top, viewWidth.toInt(), (height + top).toInt())
    } else {
      val width = viewHeight * videoAspect
      val left = ((viewWidth - width) / 2).toInt()
      Rect(left, 0, (width + left).toInt(), viewHeight.toInt())
    }
  }

  private fun actions(): List<RemoteAction> {
    val playing = GSYVideoManager.instance().isPlaying
    return listOf(
      remoteAction(android.R.drawable.ic_media_rew, R.string.gsy_pip_rewind, PIP_REWIND),
      if (playing) {
        remoteAction(R.drawable.gsy_ic_pause, R.string.gsy_pip_pause, PIP_PAUSE)
      } else {
        remoteAction(R.drawable.gsy_ic_play, R.string.gsy_pip_play, PIP_PLAY)
      },
      remoteAction(android.R.drawable.ic_media_ff, R.string.gsy_pip_forward, PIP_FORWARD),
    )
  }

  private fun remoteAction(
    iconRes: Int,
    titleRes: Int,
    actionCode: Int,
  ): RemoteAction {
    val intent =
      Intent(PIP_INTENTS_FILTER).apply {
        putExtra(PIP_INTENT_ACTION, actionCode)
        setPackage(activity.packageName)
      }
    val pending =
      PendingIntent.getBroadcast(activity, actionCode, intent, PendingIntent.FLAG_IMMUTABLE)
    val title = activity.getString(titleRes)
    return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pending)
  }

  fun release() {
    unregisterReceiver()
  }

  private companion object {
    const val TAG = "CineIsleGsyPipHelper"

    /** 遥控键的快退/快进步长 */
    const val REWIND_STEP_MS = 10_000L
  }
}
