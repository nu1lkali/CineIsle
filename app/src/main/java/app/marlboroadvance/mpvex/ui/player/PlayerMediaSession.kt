package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

/**
 * 系统媒体会话（锁屏 / 通知栏 / 蓝牙 / Android Auto / Wear OS 的播放控制）。
 *
 * ## 为什么要拆
 *
 * 原先这 90 行内嵌在 `PlayerActivity` 中段（3454-3551），而它的调用点散落在
 * 播放事件、载入完成、下一集预处理等七八个地方。留在 Activity 里时，
 * 「往系统发播放状态」这个动作要跨过两千行才能找到。
 *
 * ## 一个刻意的设计：初始化失败是**可容忍**的
 *
 * [create] 内部全包在 `runCatching` 里，失败只记日志、把 [initialized] 置 false，
 * **不抛**。原因是媒体会话纯属「锦上添花」—— 少数设备上 MediaSession 初始化会失败，
 * 但这不该让视频播不了。所有 [update*] 方法开头都先查 [initialized]，
 * 失败状态下调用它们是安全的空操作。
 *
 * 对称地，[release] 之后 [initialized] 也会被置回 false（不是提前 return），
 * 这样「释放后又有代码调 update」同样安全。
 *
 * @param onPlay 系统控件点了播放
 * @param onPause 系统控件点了暂停
 * @param onSeekTo 系统控件拖了进度（单位毫秒）
 * @param currentPositionMs 供播放状态上报当前进度（毫秒）
 * @param isPaused 供 seek 之后重新上报播放/暂停态
 */
class PlayerMediaSession(
  // 刻意不叫 `context`：那个名字会遮蔽 `android.content.Context` 这个类型，
  // 于是 `MediaSession(context, …)` 会被 Kotlin 解析成「调用名为 context 的函数」。
  private val appContext: Context,
  private val onPlay: () -> Unit,
  private val onPause: () -> Unit,
  private val onSeekTo: (posMs: Long) -> Unit,
  private val currentPositionMs: () -> Long,
  private val isPaused: () -> Boolean,
) {
  private var session: MediaSession? = null
  private var stateBuilder: PlaybackState.Builder? = null

  /** 是否已成功初始化。未初始化的状态下所有 [update*] 都是空操作。 */
  var initialized = false
    private set

  private val tag = "PlayerMediaSession"

  /**
   * 建立会话并注册回调。重复调用无效。
   *
   * 声明的动作里**没有** `ACTION_SKIP_TO_NEXT` / `ACTION_SKIP_TO_PREVIOUS`：
   * 播放列表是本项目自己的逻辑（支持随机/倒序等模式），交给系统会绕过这些语义。
   * 想要遥控器上下曲的用户走通知栏的 `MediaStyle` 自定义按钮。
   */
  fun create() {
    if (initialized) return
    runCatching {
      session =
        MediaSession(appContext, tag).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                onPlay()
                updatePlaybackState(isPlaying = true)
              }

              override fun onPause() {
                onPause()
                updatePlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                onSeekTo(pos)
                // seek 之后必须重报状态：进度变了，但播放/暂停态没变，
                // 不重报的话通知栏的进度条会停在旧位置直到下一次 play/pause。
                updatePlaybackState(isPlaying = !isPaused())
              }
            },
          )
          isActive = true
        }
      stateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      initialized = true
    }.onFailure { e ->
      Log.e(tag, "Failed to initialize MediaSession", e)
      initialized = false
    }
  }

  /** 上报播放/暂停状态。 */
  fun updatePlaybackState(isPlaying: Boolean) {
    if (!initialized) return
    val builder = stateBuilder ?: return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      session
        ?.setPlaybackState(
          builder
            .setState(state, currentPositionMs(), if (isPlaying) 1.0f else 0f)
            .build(),
        )
    }.onFailure { e -> Log.e(tag, "Error updating playback state", e) }
  }

  /** 上报标题与时长（锁屏与通知栏显示用）。 */
  fun updateMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!initialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      session?.setMetadata(metadata)
    }.onFailure { e -> Log.e(tag, "Error updating metadata", e) }
  }

  /**
   * 释放会话。
   *
   * 注意这里**没有** `if (!initialized) return` 的提前返回 ——
   * `release()` 之后要把 [initialized] 置回 false，让后续的 [update*] 自动变空操作。
   * 提前 return 会让状态残留为 true。
   */
  fun release() {
    if (!initialized) return
    runCatching {
      session?.isActive = false
      session?.release()
    }.onFailure { e -> Log.e(tag, "Error releasing MediaSession", e) }
    session = null
    stateBuilder = null
    initialized = false
  }
}
