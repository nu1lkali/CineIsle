package app.marlboroadvance.mpvex.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

/**
 * 播放页的音频焦点与「拔耳机」处理（清单 1.5 巨型类拆分，第 2 刀）。
 *
 * ## 为什么要拆
 *
 * 原先是 `PlayerActivity` 里的两个匿名内部类 + 三个字段。匿名类的问题不是写法不好，
 * 而是它们出现在类的顶部（330-450 行），读播放页初始化流程要跨过一大段与初始化
 * 毫无关系的音频代码。抽出来后「音频焦点这套规则」集中在一个文件里。
 *
 * ## 焦点丢失时必须记住「本来是不是暂停的」
 *
 * `AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT` 到来时，除了暂停还要记下**暂停之前**的状态，
 * 否则「本来就暂停着 → 焦点永久丢失 → 后来焦点回来」会错误地自动续播：
 * 用户明明手动暂停了却被声音唤醒，这是一类很招人烦的 bug。
 *
 * 注意这里读的是 [isPaused] 这个**实时**回调，而不是自己缓存一个布尔值 ——
 * 用户在焦点持有期间手动暂停的情况也必须被正确识别。
 *
 * @param isPaused 实时查询当前是否处于暂停态（转发 `PlayerViewModel.paused`）
 * @param onPause 暂停播放
 * @param onResume 恢复播放
 * @param onScreenKeepOnChange 增删「屏幕常亮」标志。拔耳机时要立刻灭屏，
 *   否则手机揣兜里一直亮屏。
 */
class PlayerAudioFocus(
  private val context: Context,
  private val isPaused: () -> Boolean,
  private val onPause: () -> Unit,
  private val onResume: () -> Unit,
  private val onScreenKeepOnChange: (keepOn: Boolean) -> Unit,
) {
  private val audioManager: AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var request: AudioFocusRequest? = null

  /**
   * 焦点恢复时要跑的动作。
   *
   * 用空 lambda `{}` 当哨兵（而不是 `pendingRestore: (() -> Unit)?`）：
   * [abandon] 靠「是否等于空 lambda」判断当前有没有挂着待恢复的动作。
   * 换成可空字段的话，每个分支都要记得置 null，少写一处就是「恢复后重复执行」的
   * 诡异 bug —— 这个坑值得留着现在的写法。
   */
  private var restore: () -> Unit = {}

  private val changeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      -> {
        val oldRestore = restore
        val wasPaused = isPaused()
        onPause()
        restore = {
          oldRestore()
          if (!wasPaused) onResume()
        }
      }

      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        // 提示音、导航播报这类「短暂占用」：压低音量而不是暂停。
        // 用「乘 0.5」而不是「设成绝对音量」，恢复时乘 2 才能准确还原原音量。
        PlayerLib.command("multiply", "volume", "0.5")
        restore = { PlayerLib.command("multiply", "volume", "2") }
      }

      AudioManager.AUDIOFOCUS_GAIN -> {
        restore()
        restore = {}
      }

      AudioManager.AUDIOFOCUS_REQUEST_FAILED -> Log.d(TAG, "Audio focus request failed")
    }
  }

  /**
   * 构建焦点请求并注册监听。**必须在开始播放前调一次**，重复调用无效。
   *
   * `setAcceptsDelayedFocusGain(true)`：蓝牙设备刚连上时系统常短暂返回 DELAYED，
   * 拒绝就等于「刚连上蓝牙没声音」，用户会以为 App 坏了。
   */
  fun create() {
    if (request != null) return
    request =
      AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build(),
        ).setOnAudioFocusChangeListener(changeListener)
        .setAcceptsDelayedFocusGain(true)
        .setWillPauseWhenDucked(true)
        .build()
    requestAudioFocus()
  }

  /**
   * @return true 焦点立刻到手；false 被拒或要等（DELAYED）
   */
  fun requestAudioFocus(): Boolean {
    val req = request ?: return false
    return when (audioManager.requestAudioFocus(req)) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restore = {}
        true
      }

      // 延迟授予：焦点稍后会自己回来（蓝牙连上、别的 App 释放占用）。
      // 挂一个「再来一次」而不是空 lambda，否则这次 DELAYED 就白要了。
      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restore = { requestAudioFocus() }
        false
      }

      else -> {
        restore = {}
        false
      }
    }
  }

  /** 放弃焦点。只在确实挂着待恢复动作时才真正还给系统。 */
  fun abandon() {
    if (restore != {}) {
      request?.let { audioManager.abandonAudioFocusRequest(it) }
      restore = {}
    }
  }

  /**
   * 拔耳机时暂停并灭屏。
   *
   * 插着线放着不管的话声音突然没一半是很糟的体验 —— 系统发这个广播就是要我们主动暂停。
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          onPause()
          onScreenKeepOnChange(false)
        }
      }
    }

  private var receiverRegistered = false

  /** 注册拔耳机监听。重复调用无效。 */
  fun registerNoisyReceiver() {
    if (receiverRegistered) return
    runCatching {
      context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
      receiverRegistered = true
    }.onFailure { e ->
      Log.w(TAG, "Failed to register noisy receiver: ${e.message}")
    }
  }

  /** 注销拔耳机监听。重复调用无害。 */
  fun unregisterNoisyReceiver() {
    if (!receiverRegistered) return
    runCatching {
      context.unregisterReceiver(noisyReceiver)
      receiverRegistered = false
    }.onFailure { e ->
      Log.w(TAG, "Failed to unregister noisy receiver: ${e.message}")
    }
  }

  private companion object {
    const val TAG = "PlayerAudioFocus"
  }
}
